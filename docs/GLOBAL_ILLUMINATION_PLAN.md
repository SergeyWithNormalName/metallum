# Global illumination: архитектура, этапы и критерии приёмки

Статус: архитектурный план, обновлён 27 августа 2026 года. Исторический результат
G0 `REJECTED_BASELINE_FLOOR` сохранён; отдельный текущий recovery gate имеет
`PASS_RECOVERED_BASELINE`, а G1 — `PASS_ABSOLUTE_FLOOR_REVALIDATED`. Эти новые
receipts не переписывают исходный G0 no-win. G2 остаётся diagnostic material
truth, а G3 и G4 завершены только как private field-only этапы. Production GI
ещё не влияет на изображение: receiver G5 заблокирован до отдельного запроса и
stop-gate. Исходный аудит voxel/radiance-наработок находится в
[GI_VOXEL_AUDIT.md](GI_VOXEL_AUDIT.md).

## 1. Решение в одном абзаце

Основной кандидат для Metallum — отдельное, привязанное к мировым координатам
поле непрямного освещения: GPU irradiance clipmap с явными источниками энергии,
детерминированной инъекцией и ограниченным однократным diffuse transport. L5
остаётся источником геометрии/пропускания, L3/L4 — источниками прямого света,
но ни L5, ни L6 не объявляются готовым GI. Первый production receiver не должен
трассировать лучи и читать radiance volume в common fragment path: измеренные
L7-прототипы уже показали, что даже два 3D texture sample внутри L8-gated
fragment shader слишком дороги на M1 Pro. Сначала проверяется field-only
транспорт, затем vertex-stage или компактный geometry-side receiver. Отражения
и CoreML идут только после успешного diffuse GI.

```text
accepted Sodium snapshot ----> material/emission field ----+
L5 occupancy/optics ----------> visibility ---------------+--> deterministic
camera-independent static L3 --> local injection ----------+    one-bounce
L4 sun/sky --------------------> environment injection -----+    transport
                                                               |
                                                               v
                                                   world-space irradiance field
                                                               |
                                 +-----------------------------+------------------+
                                 v                                                v
                    vertex/geometry diffuse receiver                 prefiltered rough-reflection data
                                 |                                                |
                                 v                                                v
                     existing direct + HDR path                         coherent L8 materials only

CoreML experiment: only after both physical fields exist; it may choose bounded
interpolation weights, but may not be a source of radiance.
```

## 2. Что означает «честный GI» в этом проекте

GI считается физически обоснованным приближением, если выполняются все условия:

1. У каждой ненулевой энергии есть явное происхождение: emissive voxel,
   camera-independent L3 emitter, солнце или небо L4.
2. Свет доходит до приёмника только через валидную геометрию и объявленный
   transport. Неизвестная ячейка не становится «примерно освещённой».
3. Diffuse bounce ограничен альбедо и видимостью; материал не может усилить
   входящую энергию выше заданного emissive/source term.
4. Положение результата задаётся координатами мира, а не экраном, depth buffer
   текущего кадра или тем, что камера недавно видела.
5. Одинаковая последовательность world/source epochs даёт одинаковое поле с
   заранее оговорённой FP16-погрешностью. Случайных лучей, noise seed, dropout и
   зависящего от FPS temporal accumulation нет.
6. При нехватке данных используется существующее стабильное ambient/environment
   освещение; старый radiance другого chunk/window никогда не показывается.

Нулевой source test является обязательным инвариантом:

```text
emission = 0, sun/sky = 0, local sources = 0  =>  transported GI = 0
```

Это отличает GI от реконструкции, способной нарисовать правдоподобный, но
несуществующий свет.

## 3. Реалистичные ожидания

### Что можно ожидать от первой принятой версии

- Один стабильный diffuse bounce от солнца/неба, факелов, ламп, лавы и других
  поддержанных emissive blocks.
- Заметное, но мягкое цветное переотражение рядом с крупными цветными
  поверхностями; более естественное заполнение открытых помещений и пещер.
- World-space результат, не исчезающий при повороте камеры и не обрывающийся на
  границе экрана.
- Ограниченное разрешение и плавные переходы между каскадами. Небольшие детали
  могут сливаться; это ожидаемая цена стабильности.
- Плавная, ограниченная по времени реакция на изменение блока или источника.
  Мгновенный полный пересчёт всего охвата не является целью.
- Нулевой вклад и аналитический fallback там, где field coverage ещё не готов.

### Чего первая версия обещать не должна

- Многократные bounces уровня offline path tracer.
- Точные sharp mirror reflections, caustics второго порядка или отражение каждой
  мелкой детали.
- Полное совпадение texture-pack albedo и всех modded materials до отдельного
  resource-pack/material этапа.
- Мгновенный GI от всех динамических entities. Сначала принимаются static world
  и bounded dynamic emitters.
- Бесплатный GPU cost. Кандидат выпускается только при измеренном запасе на M1
  Pro; отсутствие 15/3 FPS нельзя заменять красивым single-frame screenshot.

Размытый rough reflection является допустимым продуктовым результатом. Резкое
зеркало из coarse field — нет: оно неизбежно покажет voxel/probe resolution.

## 4. Непереговорные технические контракты

### 4.1. Пространственная стабильность

- Все origins привязаны к world grid, а не к дробной позиции камеры.
- Scroll переиспользует physical cells только после проверки logical coordinate,
  `worldGeneration`, `clipmapGeneration`, source epoch и content stamp.
- Teleport, dimension switch и resource reload сначала инвалидируют coverage,
  затем допускают новые samples.
- Не используются SSR, screen-space depth marching, camera history и
  reprojection как источник GI.

### 4.2. Детерминизм

- Transport использует ping-pong/Jacobi dispatch с фиксированным порядком и
  числом итераций; floating-point atomics и race-dependent accumulation
  запрещены.
- Очередь dirty bricks коалесцируется по стабильному ключу и имеет фиксированные
  capacity/drain/starvation bounds.
- Амортизация по кадрам допустима только как детерминированный progress одного
  epoch. При смене epoch несовместимый progress выбрасывается.
- Field hash и per-stage counters должны воспроизводиться при повторе frozen
  fixture на одном GPU/сборке.

### 4.3. Ограниченность ресурсов

- Balanced target: не более 24 MiB дополнительных постоянных и bounded
  in-flight ресурсов GI сверх принятых L5/L6. Это проектный hard cap, а не уже
  доказанный размер.
- Никаких `Arena.allocate`, новых `MTLBuffer`/`MTLTexture`, model objects или
  readback внутри frame loop.
- GPU field остаётся private; CPU readback разрешён только явному one-shot debug
  режиму вне измеряемого production path.
- Контекст и ring resources уничтожаются через существующую in-flight retirement
  discipline.

### 4.4. Совместимость освещения

- L5 optical/chromatic byte не переименовывается в albedo: его семантика нужна
  L5/L6 visibility и transmittance.
- L6 atlas остаётся light-centric visibility cache; это не radiance cache.
- На G3 GI использует только camera-independent static L3 registry. Текущий
  dynamic collector проходит camera/frustum admission и не подходит как GI
  truth. Для held/entity lights на G6 нужен отдельный bounded world-space
  collector с expiry/epoch. View-frustum/top-K snapshot direct renderer не может
  быть источником GI: иначе поворот камеры изменит off-screen illumination.
- Непрямой свет не складывается вслепую с vanilla lightmap. При достаточной
  confidence GI замещает соответствующую approximation ambient/indirect term;
  L3/L4 direct terms остаются отдельными.

### 4.5. Доказательства

Во всех документах и результатах используются метки из
[BENCHMARKING.md](BENCHMARKING.md): `PROVEN`, `SUPPORTED`, `SPECULATIVE`,
`UNKNOWN`.

- Unit/ABI/build/startup доказывают механические контракты, но не FPS и не
  визуальное качество.
- Tier B нужен для атрибуции `GI_INJECT`, `GI_TRANSPORT`, `GI_RECEIVER` и
  обновлений. Его FPS не сравнивается с production baseline.
- Tier C: 1800 warm-up + 3000 measured frames, не менее двух независимых runs на
  baseline и candidate, без detailed markers. Только он принимает whole-frame
  performance.
- Live visual matrix остаётся ручной границей приёмки даже при полностью зелёных
  тестах.

## 5. Предварительный формат поля

Первый кандидат — три world-snapped probe cascades с охватами порядка 64, 128 и
256 blocks. Точный edge и spacing выбираются только после Stage G1/G2 memory
audit. В отличие от прежнего L7 `64^3` radiance volume, diffuse receiver должен
читать уже свёрнутую irradiance representation, а не делать cone trace.

Рекомендуемый начальный формат:

- first-order spherical harmonics: 4 coefficients × RGB в трёх `RGBA16Float`
  volumes;
- отдельные validity/confidence и content epoch;
- ping-pong только для каскада/brick, который сейчас транспортируется;
- материал/source field хранится отдельно от irradiance output.

Для трёх каскадов `32^3` арифметический single-buffer payload трёх SH textures
равен примерно 2.25 MiB без alignment, confidence, source field и ping-pong.
Это `SPECULATIVE` sizing exercise, не device allocation proof. Начальный
field-only эксперимент обязан запросить фактические Metal allocation sizes.

SH выбран для diffuse transport: он компактнее ambient cube и не пытается
сохранить sharp specular signal. Для отражений потребуется отдельное,
предварительно фильтрованное directional representation.

## 6. Проверяемые этапы GI

Каждый этап — отдельная ветка/узкий commit. Следующий этап не начинается, пока
предыдущий не прошёл свой stop-gate.

### G0 — baseline, fixture и нулевой контракт

Цель: определить, что именно будет считаться успехом до production-кода.

Работа:

- Зафиксировать M1 Pro Tier C baselines для Overworld, sealed cave и Nether.
- Добавить immutable GI fixtures: белая комната без источника; красный и зелёный
  emitter; skylight aperture; lava landmark; thin geometry; chunk boundary;
  cascade boundary; scroll и teleport.
- Зарезервировать telemetry schema: allocated/resident bytes, valid/unknown
  probes, dirty queued/completed/discarded, injection/transport dispatches,
  source epochs, reset reasons, stale-cell rejects и fallback reasons.
- Описать compile-time/runtime `GI_OFF`: ноль L7 allocations, passes, bindings и
  shader symbols.

Проверка:

- Fixture/source/settings digests и benchmark preflight.
- Contract tests на 300-frame alignment и telemetry parsing.
- Generated MSL audit: `GI_OFF` не содержит GI resources/helpers.

Stop-gate:

- Без валидного baseline и нулевого варианта дальнейшая реализация не начинается.
- G0 завершается отдельным committed acceptance artifact с exact M1 Pro profile,
  routes/settings, Tier C baseline receipts, relative p95/low limits и absolute
  floors. Начальное предложение floors приведено в G7; оно утверждается или
  меняется явным решением здесь. Без этого artifact G1 не начинается.
- Если исходный renderer уже не имеет запаса относительно утверждённого
  абсолютного FPS floor, сначала исправляется baseline; GI не получает скидку за
  хороший относительный процент.

Результат G0 от 2026-08-24: `REJECTED_BASELINE_FLOOR`. Шесть валидных Tier C
receipt зафиксированы в `benchmark/gi/g0-acceptance-v1.json`; sealed cave и
Nether прошли утверждённые floors, но два Overworld run дали около `20.07 FPS`
и `16.90 FPS` mean 1% low против `30/20`. Поэтому `g1_allowed=false`, floor не
ослаблен, и перед G1 требуется отдельное восстановление baseline.

Повторная проверка 2026-08-26 не переписывает этот исторический результат.
Текущий clean source `6a52f90` прошёл новую полную матрицу: Overworld
`44.104/44.058 FPS`, sealed cave `87.949/87.581`, Nether `35.705/35.739`; все
шесть runs имеют строгие Tier C receipts, nominal thermals, Advanced admission
и нулевой GI production contract. Текущий статус — `PASS_RECOVERED_BASELINE`,
`g1_allowed=true`; evidence находится в
`benchmark/gi/gi-stage-gates-2026-08-26-v1.json`.
Current receipts, summaries, raw reports and logs are stored in its tracked
immutable evidence bundle; the current gate does not pass from manifest claims
alone.

### G1 — выборочное спасение L7 field infrastructure

Цель: перенести полезную механику из
`.codex-worktrees/l7-radiance-clipmap`, не возвращая отклонённые receivers.

Разрешено переносить после построчного аудита:

- topology/address helpers;
- bounded candidate accounting и memory auditor;
- world-generation/reset discipline;
- private 3D texture ownership, upload и coverage-aware mip kernels;
- one-shot field capture и механические CPU/GPU tests.

Не переносить как есть:

- `RadianceAppearanceModel` как источник GI: он формирует pre-lit value из
  vanilla sky/block light, а не считает bounce;
- `SodiumRadianceSectionExtractor` до разделения material/emission/source
  semantics;
- six-step cone receiver, visible-function bridge, two-sample directional probe
  receiver и terrain bindings;
- dedicated sparse reflective pass без нового fixed-cap allocator.

Проверка:

- Новая ветка начинается от текущего commit; исходный dirty worktree остаётся
  нетронутым.
- Allowed-file diff, exact Java/Swift/MSL ABI tests, actual Metal texture/mip test,
  `./gradlew check`.
- Sodium workers могут создать только immutable CPU candidates. Allocation,
  encoding, commit и replacement L7 native resources происходят только на
  Render Thread; это проверяется thread-confinement assertion.
- Fault/lifetime test удерживает старый L7 context до completion последнего
  использующего его command buffer, даже если Java уже запросила replacement или
  release. Фактическое уничтожение проходит через in-flight lease/deferred
  destruction, а не немедленный raw-handle release.
- Field-only build не меняет production shader output.

Stop-gate:

- Если инфраструктуру нельзя отделить от rejected shader/benchmark hooks узким
  diff, перенос отменяется и минимальный field scaffold пишется заново.

Результат G1 от 2026-08-25: `SUPPORTED_PENDING_ABSOLUTE_FLOOR`. Новый
изолированный scaffold в `88f9dda` прошёл Java/FFM/Swift/Metal ABI, private 3D
allocation, coverage mip, generation/reset, bounded accounting, in-flight
lifetime, one-shot capture и полный `check`. Production bindings/passes равны
нулю. Contemporaneous control/candidate A/B не показал регрессии, однако точный
Tier C Overworld receipt дал `27.430 FPS`, `22.473` 1% low и `39.483 ms` GPU p95:
условный average floor `28 FPS` не пройден. Поэтому
`benchmark/gi/g1-field-evidence-v1.json` сохраняет `g2_allowed=false`.

Повторная проверка 2026-08-26 закрыла только этот outstanding blocker: два
независимых current-source Overworld Tier C run дали `44.104/44.058 FPS` и
`31.833/32.060` 1% low против неизменного `28/20` floor. Исторический artifact
остаётся неизменным, а current gate artifact фиксирует
`PASS_ABSOLUTE_FLOOR_REVALIDATED` и `g2_allowed=true`.

### G2 — semantic material/emission field

Цель: получить данные, необходимые transport, но отсутствующие в L5.

Минимальный payload:

- conservative occupancy/medium;
- diffuse albedo или versioned palette ID;
- emission RGB/intensity;
- coarse face/normal distribution;
- validity, world/clipmap/content generations.

Источник — только accepted Sodium meshing snapshot. Worker не читает mutable live
world после публикации. Block event лишь помечает участок dirty; новая truth
появляется после принятого remesh snapshot.

Проверка:

- Deterministic pack/unpack golden tests, negative coordinates, palette limits,
  modded fallback, biome/resource reload, empty/unavailable distinction.
- Debug slices для albedo, emission, face mask, validity и provenance.
- 10 000 synthetic section publications не увеличивают steady memory после
  bounded retirement.

Stop-gate:

- Неизвестная section остаётся unknown; использование данных предыдущего world
  или camera-dependent lightmap запрещает переход к G3.

Результат G2 от 2026-08-25: `PASS_STRUCTURALLY_OFF_DIAGNOSTIC`. Commit
`d750491` публикует truth только после accepted Sodium output, различает
UNKNOWN/AIR/FALLBACK, отбрасывает stale generations и не читает lightmap.
10 000 publications оставляют один resident tag и ноль active leases.
Source/bundled Metal проверки дали 18 022 528 bytes conservative peak
при бюджете 24 MiB. Live 600+600 screening принял 768 snapshots без
stale/capacity rejects и показал 27.331 FPS OFF против 27.434 FPS ON;
это Tier B, не Tier C claim. Production GI resources/passes/bindings равны
нулю. Технический gate к отдельно запрошенному G3 пройден, но G3
не начат. Полный контракт: `docs/GI_G2.md`.

Текущая clean revalidation 2026-08-26 сохранила отрицательный короткий 2x2
screening (`+0.565 ms` GPU p95), затем локализовала `WORLD_OPAQUE` delta как
`+0.014 ms` и получила соседнюю десятиоконную ON/OFF пару: `44.718/44.151 FPS`,
`30.469/32.527` 1% low, `24.411/24.518 ms` GPU p95. Неизменные stop-gates
пройдены, 738 snapshots приняты без reject, cap 64 / 1,009,152 bytes не
превышен, production resources/passes/bindings остались нулевыми. Это
зафиксированный prerequisite к отдельно запрошенному G3; текущий результат G3
приведён ниже и не переписывает этот G2 receipt.

### G3 — direct source injection без bounce

Цель: построить детерминированное outgoing/source radiance field.

Работа:

- Emission берётся из G2.
- Sun/sky parameters и их epoch берутся из L4 environment.
- Static local emitters берутся из camera-independent L3 registry до
  view-frustum top-K compaction; admission в brick остаётся bounded. Текущие
  dynamic/held/entity sources до G6 в GI не участвуют.
- Visibility считается в compute/update work на dirty probes. Допустим bounded
  L5 traversal здесь, потому что он амортизирован по probes; он запрещён в каждом
  fragment.
- Vanilla sky/block light можно использовать только как debug oracle для
  сравнения, не как shipping source «честного GI».

Проверка:

- Нет source — поле строго нулевое.
- Красный source не создаёт зелёную/синюю энергию; black albedo не отражает.
- Sealed cave не получает sky; aperture пропускает его только после валидного
  geometry epoch.
- Поворот камеры не меняет field hash.

Performance gate:

- Tier B доказывает bounded work: стоимость зависит от числа dirty bricks, а не
  от полного объёма или разрешения экрана.
- Любой full-volume rebuild в steady state — hard stop.

Результат G3 от 2026-08-26: `PASS_FIELD_ONLY_BOUNDED_DIRECT_SOURCE`. Реализация
строит private `E_direct` в трёх `32^3 RGBA16Float` каскадах, использует G2
emission, camera-independent L4 AIR environment и только static L3
`BLOCK/STATIC_CACHE` до view admission. Albedo/`rho/pi`, transport, bounce,
receiver и image binding отсутствуют. Source/bundled Metal Validation прошли
zero/red/sealed/aperture/repeat-hash/stale/thread/lifetime проверки. Учтённый
объём — `1,104,096` bytes при 24 MiB cap.

Live Tier B на frozen 600+600 fixture выполнил ровно одну initial population:
192 queued/completed, 0 discarded, 0 pending, максимум 8 bricks/frame.
`GI_INJECT` на 24 warmup-кадрах дал `0.081 ms` average и `0.122 ms` p95;
оба измерительных окна имели нулевую работу и неизменный full-field counter. Два
предыдущих прогона с dynamic-epoch и startup-publication churn сохранены как
rejected evidence. Текущий статус — `G3_COMPLETE_FIELD_ONLY`; отдельно
запрошенный G4 также прошёл собственный field-only stop-gate. Полный контракт
G3: `docs/GI_G3.md`.

### G4 — детерминированный one-bounce diffuse transport

Цель: получить собственно GI, пока без воздействия на production image.

Алгоритм первого кандидата:

1. G2 хранит безразмерную diffuse reflectance `rho` в `[0, 1]`; это не radiance.
2. G3 вычисляет direct irradiance `E_direct` в линейных HDR irradiance units на
   валидных surface elements. Emission/sun/sky/L3 являются источниками этого
   direct term; существующий L3/L4 renderer продолжает отвечать за zero-bounce
   direct lighting на видимом receiver.
3. Начальное outgoing bounce field имеет определённый смысл:
   `L_bounce0(q) = rho(q) / pi * E_direct(q)`. Это diffuse outgoing radiance
   поверхности `q`, а не уже готовый receiver color.
4. Один фиксированный Jacobi transport собирает incoming indirect irradiance:
   `E_indirect(p) = sum_q L_bounce0(q) * V(q,p) * F(q,p)`, где `V` равно нулю
   для unknown/occluded faces, а non-negative discrete form weights `F`
   включают projected solid angle и нормированы `sum(F) <= pi` для одного source
   element. Поэтому radiance × `F` имеет смысл irradiance, а discrete stencil не
   размножает энергию произвольным числом соседей.
5. `E_indirect` проецируется в L1 SH irradiance; ping-pong исключает order races.
   Receiver на G5 применяет `rho_receiver / pi` ровно один раз. Он не применяет
   source albedo повторно.
6. Второй bounce не добавляется, пока один не принят визуально и по стоимости.

Сначала строится один frozen near cascade. Три каскада, scrolling и live updates
добавляются только после его correctness/performance gate.

Проверка:

- Global one-bounce energy bound: интеграл перенесённой энергии не превышает
  reflected direct input с учётом `rho`; все values finite/non-negative после
  reconstruction.
- Air/unknown/occluded face имеет строго нулевой transfer независимо от соседней
  яркости.
- Repeat run даёт тот же field hash/tolerance.
- Red-wall/white-floor fixture показывает только физически достижимый color
  bleed; закрытие aperture удаляет energy после фиксированного convergence.
- Debug modes разделяют source, bounce, SH lobes, confidence и invalid cells.

Stop-gate:

- Light leaks через sealed wall, race-dependent hash, amplification без emissive
  source или unbounded convergence закрывают гипотезу до receiver.

Статус реализации от 2026-08-27: `G4_COMPLETE_FIELD_ONLY`, решение
`PASS_FIELD_ONLY_ONE_BOUNCE`. Frozen near-cascade candidate завершён в
Java/Swift/Metal и остаётся default-off. Source/bundled Metal Validation, exact
ABI/resource census, field-only source-chain и чистый live Tier B receipt
прошли: один warmup transport dispatch дал `0.923291 ms` p95/max, а measured
dispatch growth остался нулевым. Это не Tier C, не визуальная приёмка и не
production FPS claim. G5 остаётся заблокирован. Канонический контракт:
`docs/GI_G4.md`.

### G5 — receiver feasibility

Цель: добавить GI к terrain без повторения L7 fragment regressions.

Порядок кандидатов:

1. **Vertex-stage SH lookup**: world-space probe sample на terrain vertex,
   normal-aware SH evaluation и интерполяция готового irradiance к fragment.
2. Если vertex sampling не проходит gate — **compact GI sidecar/update** для
   accepted geometry без remesh. Он допускается только после census, который
   удерживает общий GI memory cap и не дублирует terrain mesh.
3. Common fragment `sampler3D`, fragment DDA/cone trace и Metal visible function
   не являются запасными вариантами: они уже закрыты измерениями L7.

Композиция:

- L3/L4 direct и local GGX остаются неизменными.
- При valid confidence GI замещает соответствующую approximate ambient/indirect
  часть; при confidence=0 получается точный существующий fallback.
- Нельзя просто прибавить GI поверх vanilla ambient/lightmap и назвать двойную
  энергию bounce.

Dry performance gate до визуальной настройки:

- Frozen black/zero GI field, одинаковый shader flavor и allocations в обоих
  плечах A/B.
- Tier B target: `WORLD_OPAQUE` p95 delta не более 0.30 ms и repeatable
  whole-frame delta не более 2%. Это screening/stop-gate, не Tier C claim.
- Generated MSL должен подтверждать фактический vertex path и отсутствие старых
  fragment receivers.

Если оба bounded receiver carrier не проходят, GI остаётся field/debug feature;
качество не спасается возвратом к screen-space.

### G6 — live updates, scroll и динамические источники

Цель: сделать принятый frozen GI пригодным для игры.

Добавляются по одному:

- incremental brick update;
- clipmap scroll/cascade overlap;
- block placement/removal и chunk unload/reload;
- time/weather/sun epoch;
- bounded held/entity light с expiry;
- teleport/dimension/resource-pack reset.

Update schedule привязан к renderer/source ticks, а не к числу показанных кадров.
Несовместимый stale light запрещён уже в первом submit после reset. Начальный
release SLA, который G0 либо утверждает, либо меняет отдельным решением до G1:

- accepted near-field block/static-source change: recovery p95 ≤ 8 submits,
  p99 ≤ 16;
- one-brick scroll: visible near-field coverage recovery p95 ≤ 16, p99 ≤ 32;
- teleport/full reset: incompatible coverage немедленно 0, новое near coverage
  p95 ≤ 32, p99 ≤ 64 submits.

Dynamic held/entity collector добавляется здесь отдельно: bounded world-space
membership без camera frustum, stable source ID, explicit expiry и source epoch.
Hard test поворачивает камеру, оставляя entity/source неподвижным внутри coverage:
field hash и GI contribution не должны измениться.

Проверка:

- Camera orbit, torch toggle, lava update, F3+A, rapid chunk streaming, day/night,
  rain, Nether transition и teleport.
- Queue overflow не включает старый свет: exact-valid cache можно удержать,
  incompatible cell — только fallback.
- Ноль steady-state allocations/readbacks; counters сходятся по каждому epoch.

### G7 — продуктовая приёмка GI

Обязательная live matrix:

- Overworld daylight, forest/foliage, открытый дом и цветная стена;
- sealed cave, cave with aperture, torch toggle;
- Nether lava stress;
- rain/wet materials;
- cascade boundary, постоянное движение, быстрый разворот, scroll и teleport;
- MetalFX off и поддержанные production scaling modes отдельно.

Performance release gates для Balanced-кандидата:

- дополнительные tracked GI resources ≤ 24 MiB;
- ноль pipeline failures и ноль stale/incompatible-cell presentation;
- на stable measured interval после route warm-up coverage fallback равен нулю.
  Во время явно размеченного block/scroll/teleport transition safe fallback
  разрешён; release принимает его только при соблюдении p95/p99 SLA из G6 и
  возвращении fallback counters к нулю;
- не менее двух валидных Tier C runs на baseline и candidate для каждой
  обязательной route;
- предварительный целевой предел whole-GPU p95 regression ≤ 8% и 1% low
  regression ≤ 10%. Это продуктовый budget, не уже доказанная характеристика;
- G0 обязан закоммитить exact M1 Pro profile/routes и численные absolute floors;
  без этого G1 не начинается. Начальное предложение для native HDR Balanced:
  Overworld average ≥ 30 FPS и 1% low ≥ 20 FPS; Nether average ≥ 20 FPS и 1%
  low ≥ 10 FPS. Candidate одновременно должен удержать относительные gates выше.
  Если baseline сам ниже floor, сначала исправляется baseline, а не ослабляется
  GI gate. Поэтому 15 FPS в Overworld или 3 FPS в Nether не могут пройти по
  одному лишь хорошему проценту относительно медленного baseline.

Quality presets не считаются оптимизационным доказательством. Если Balanced не
проходит, снижение разрешения может существовать как явно худший opt-in preset,
но не превращает отклонённый Balanced-кандидат в «ускорение».

## 7. Отражения после GI

### 7.1. Что исключено

- SSR и screen-depth marching: нарушают пространственную стабильность.
- Fragment L5 DDA и six-step radiance cone trace: измеренно слишком дороги.
- Вынесение cone trace в Metal visible function: измеренно ещё дороже.
- Два 3D texture reads из directional probe в L8-gated terrain fragment:
  dry-screening regression уже неприемлем.
- General planar reflection: требует повторного render мира и не масштабируется
  на wet terrain, metal и множество разных плоскостей.
- Дублирование water mesh без independent fixed-cap allocator: текущая Sodium
  partition этого безопасно не поддерживает.

### 7.2. Реалистичный reflection track

Отражения используют те же physical sources и geometry epochs, но не diffuse SH
как будто это sharp radiance. Нужен отдельный coarse directional/prefiltered
output.

#### R0 — отдельный directional field, debug only

- Построить отдельные outgoing-radiance lobes/moments compute-side из G2 geometry,
  G3 physical sources и совместимых epochs. Diffuse L1 irradiance SH из G4 не
  содержит достаточного направления и не может быть единственным input или
  «восстановленным» sharp radiance.
- Directional textures, ping-pong/prefilter scratch и in-flight state получают
  отдельный memory census; они не прячутся внутри 24 MiB diffuse GI budget.
  Начальный reflection cap — 8 MiB, combined GI+reflection cap — 32 MiB до
  фактического device-allocation решения.
- Проверить off-screen colored landmark, sealed wall, missing coverage и cascade
  continuity без shader receiver.
- Никаких отражений в production image.

#### R1 — prefiltered rough reflection carrier

- Минимальная roughness 0.25–0.35; поле заранее фильтруется по нескольким roughness
  bands.
- Runtime не делает per-fragment cone traversal.
- Первый carrier — per-vertex или bounded per-section coefficients, чтобы common
  fragment path получил только интерполированные radiance/direction/confidence,
  а не 3D sampler.

Stop-gate: точный memory census и generated-MSL proof. Если carrier требует
unbounded duplicate geometry либо возвращает volume samples в common fragment,
R1 закрывается.

#### R2 — coherent L8 integration

- Только water, wet terrain, metal и явно tagged smooth materials.
- Existing analytic environment, sharp sun/sky и local GGX остаются fallback.
- Coarse local term всегда blurred и confidence-weighted.
- Camera rotation не влияет на наличие off-screen contribution.

Dry gate не мягче G5. После него — отдельные lake/wet-ground/metal/cave/rain live
scenes и Tier C matrix.

#### R3 — water-specific planar option

Текущий uncommitted planar WIP можно доводить независимо как water-only quality
mode. Он не считается GI или general reflections. Принимаются отдельно:

- визуальная корректность mirrored frustum, waves, edges, caves/clouds;
- отсутствие Metal feedback loop;
- фактическая стоимость повторного sky/opaque/cloud render на M1 Pro.

Если planar cost слишком высок, analytic environment + blurred world-space
reflection остаются честным fallback; screen-copy замена запрещена.

## 8. CoreML — только финальный эксперимент

CoreML не участвует в G0–G7 и не блокирует выпуск GI. Эксперимент начинается
только после появления принятого physical low-resolution field и offline
reference dataset.

### 8.1. Разрешённая задача модели

Модель может:

- предсказывать non-negative interpolation weights для соседних валидных probes;
- выбирать LOD/roughness band;
- реконструировать более плотную сетку как convex combination физически
  вычисленных anchors;
- оценивать confidence, после которого renderer выбирает physical fallback.

Модель не может:

- получать final screen color/depth/history как главный источник;
- генерировать RGB radiance напрямую из материала/семантики;
- использовать noise/latent seed или state, зависящий от порядка кадров;
- выдавать свет при нулевых/invalid physical anchors.

Жёсткая форма output:

```text
w_i >= 0
sum(w_i) <= 1
L_ml = sum(w_i * visibility_i * L_physical_i)
invalid physical support => confidence = 0 => non-ML fallback
```

Так сеть может восстановить/сгладить известный сигнал, но не создать новый цвет
или энергию.

### 8.2. Детерминизм CoreML

- Feed-forward model без dropout, random ops и mutable state.
- Model SHA-256, CoreML package version, converter/toolchain и selected compute
  configuration входят в renderer generation.
- Одинаковый input выполняется не менее 100 раз; output должен быть bit-stable
  либо укладываться в утверждённую FP16 tolerance. Требование проверяется отдельно
  для каждого допущенного compute backend.
- Переключение backend или model version делает полный field reset.
- Любое расхождение, видимое на сцене или меняющее energy bound, отклоняет ML
  режим; averaging нескольких непредсказуемых outputs не допускается.

Apple Core ML позволяет задавать compute units и preferred Metal device через
[`MLModelConfiguration`](https://developer.apple.com/documentation/coreml/mlmodelconfiguration),
но это не является обещанием bit-identical результата — такой контракт должен
доказать Metallum. `MLTensor` поддерживает no-copy CPU buffer construction, что
также не доказывает zero-copy private Metal texture path.

Для более тесного GPU timeline Apple описывает Metal 4 tensor resources и
`MTL4MachineLearningCommandEncoder` в
[WWDC25: Combine Metal 4 machine learning and graphics](https://developer.apple.com/videos/play/wwdc2025/262/).
Текущий M1 Pro capability snapshot подтверждает Apple7/Metal3 runtime, но только
documentation-level наличие Metal4. Поэтому Metal4 ML — отдельный capability
probe, а не предположение основной архитектуры.

### 8.3. Этапы ML-эксперимента

1. **ML0, offline**: reference solver создаёт physical low/high-resolution pairs;
   модель учит только bounded weights. Zero-source, sealed-wall и unseen-material
   adversarial set обязателен.
2. **ML1, native microbenchmark**: persistent model/tensors, отсутствие frame-loop
   allocations, CPU readback и hidden copies; отдельные GPU/ANE/CPU timings.
3. **ML2, field-only live**: модель пишет только experimental field, production
   image не читает его; repeatability/energy/provenance telemetry.
4. **ML3, A/B receiver**: тот же carrier, что у физического GI, чтобы измерялся
   ML compute, а не новый shader path.
5. **ML4, release decision**: отдельные Tier C и live visual gates. Режим остаётся
   Experimental/default-off, пока нет воспроизводимости на всех поддержанных
   конфигурациях.

Hard stop для CoreML:

- zero-source создаёт ненулевую radiance;
- результат зависит от камеры при неизменном world field;
- нет безопасного GPU timeline/ownership без CPU round-trip;
- повторные outputs выходят за tolerance;
- ML выигрывает только ценой потери physical details или маскирует stale cells;
- thermal/memory/whole-frame gates хуже физического baseline.

## 9. Порядок фактической реализации

```text
G0 baseline/contracts
  -> G1 selective field salvage
  -> G2 material/emission truth
  -> G3 physical source injection
  -> G4 deterministic one-bounce field
  -> G5 receiver feasibility
  -> G6 live updates
  -> G7 GI release matrix
  -> R0..R2 rough reflections
  -> optional R3 planar water
  -> ML0..ML4 CoreML experiment
```

Первый полезный milestone — не «GI появился на экране», а G4: физически
объяснимое, детерминированное поле с debug views и нулевым production receiver.
Это позволяет остановить дорогую архитектуру до того, как она разрастётся по
Java–FFM–Swift–Metal ABI и common terrain shader.
