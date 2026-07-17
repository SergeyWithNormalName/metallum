# Optimization History

Личный инженерный журнал экспериментов с производительностью Metallum.

Здесь я фиксирую не только оставленные оптимизации, но и идеи, которые выглядели
правдоподобно и не прошли измерение. Цель журнала — не повторять неудачные
эксперименты без новых данных и не превращать предположение в «общеизвестный факт».

## Правила журнала

- Оптимизация остаётся в коде только после воспроизводимого A/B на одинаковом
  маршруте, дисплее, настройках и workload.
- Для FPS и frame pacing используются production-прогоны без detailed stage
  markers. Detailed-прогоны служат только для атрибуции.
- Изменение качества, дальности, количества каскадов или набора функций не считается
  бесплатной оптимизацией.
- Идеи, принадлежащие будущим этапам `LIGHTING_ARCHITECTURE_PLAN.md`, не внедряются
  раньше времени.
- Отрицательный кандидат полностью удаляется из исходников. В истории остаётся
  только метод, результат и мой вывод.

---

## 2026-07-17 — аудит просадки после L4

### Контекст и стенд

- Ветка: `l4-shadows`.
- Исходный HEAD: `f40023e3dffa` (`Fix L4 particle shadow-pass artifacts`).
- GPU: Apple M1 Pro.
- Дисплей: встроенный Retina, `3024×1964 @ 120 Hz`, exclusive fullscreen.
- Настройки: HDR scene output, Advanced lighting, Balanced shadows, Fancy,
  render/simulation distance `16/12`, VSync off, MetalFX off.
- Маршрут: `hdrtest-static-v1`, 1800 кадров прогрева + 3000 измеряемых кадров.
- Production baseline: `86.552 FPS`, минимальное окно `86.202 FPS`, GPU
  p50/p95/p99 `13.7909/14.5972/15.1224 ms`, 1%/0.1% low
  `50.462/46.549 FPS`.
- Detailed baseline: `83.793 FPS`, GPU p95 `14.1366 ms`, SUN_SHADOW p95
  `2.9599 ms`. FPS этого прогона нельзя смешивать с production baseline:
  detailed markers меняют границы энкодеров.

Отчёт пользователя о падении примерно до 80 FPS правдоподобен. Абсолютное число
зависит от сцены, но подтверждённый проектный benchmark тоже показывает регрессию
после L4: историческая точка L3 в плане — около `105.20 FPS / 12.44 ms GPU p95`,
а свежий L4 baseline — `86.55 FPS / 14.60 ms GPU p95`.

### Подтверждённая причина

L4 добавил реальную постоянную работу, а не скрытую аллокацию:

1. Balanced каждый кадр полностью очищает и заново рисует три каскада `1024²`.
2. В каждый каскад повторно идут terrain casters и solid feature renderers.
3. Получатель тени выполняет 3×3 PCF — 9 comparison samples; в зоне blend двух
   каскадов возможно до 18 samples на пиксель.
4. Production workload вырос примерно с `6.19` до `10.23` render encoders на кадр,
   при этом command buffer остался один, а steady-state resource allocations равны
   нулю.

Это соответствует текущему плану. Полная перерисовка каскадов является контрактом
L4; cached/dirty-page shadows относятся к L6. Поэтому я не пытался маскировать
регрессию преждевременной реализацией L6.

---

## Оставлено — единая подготовка normal/albedo для direct lighting

**Статус:** подтверждённый выигрыш, оставлено в коде.

### Гипотеза

Terrain/entity fragment shader передавал одинаковые `surfaceNormal` и
`linearAlbedo` двум последовательным вычислениям:

- environment + sun shadow;
- clustered direct lights.

Обе ветки независимо вызывали безопасную нормализацию normal. Кроме того,
`max(linearAlbedo, 0)` находился внутри цикла clustered lights. Даже если Metal
compiler иногда способен выполнить CSE/LICM сам, явный контракт уменьшает риск
рематериализации и не заставляет оптимизатор доказывать неизменность через тяжёлые
texture/SSBO ветки.

### Метод

1. Один раз на fragment вычисляется `metallumDirectNormal` через существующий
   fail-closed `metallumSafeNormalV1`.
2. Один раз вычисляется `metallumPreparedAlbedo = max(albedo, 0)`.
3. Подготовленные значения передаются environment и clustered helpers.
4. В обоих helpers сохранена проверка нулевой/невалидной normal после их собственных
   ABI/config guards.
5. Сохранены две прежние операции `color.rgb +=` и их порядок. Первая пробная версия
   складывала `environment + clustered` заранее; я её не принял, потому что она
   меняла IEEE floating-point associativity.
6. Golden hashes реальных Minecraft 26.2 / Sodium 0.9.1 shaders обновлены, а tests
   теперь отдельно запрещают повторную normal/albedo подготовку, clamp внутри
   light-loop и перестановку двух прибавлений. Новые локальные имена также добавлены
   в fail-closed collision gate для сторонних shader sources.

### Результаты

| Прогон | FPS | min window | GPU p50 | GPU p95 | GPU p99 | 1% low | 0.1% low |
|---|---:|---:|---:|---:|---:|---:|---:|
| Baseline | 86.552 | 86.202 | 13.7909 ms | 14.5972 ms | 15.1224 ms | 50.462 | 46.549 |
| Candidate A | 87.605 | 87.253 | 13.6229 ms | 14.4625 ms | 14.9733 ms | 50.182 | 47.442 |
| Candidate B | 87.810 | 87.477 | 13.5917 ms | 14.2914 ms | 14.7873 ms | 49.876 | 47.263 |

Среднее двух кандидатных прогонов: `87.708 FPS`, то есть `+1.34%` к baseline.
Средний GPU p95 — `14.377 ms`, улучшение примерно на `0.220 ms`; GPU p50 и p99
тоже улучшились в обоих прогонах. Автоматический comparison gate на втором прогоне
выдал `IMPROVEMENT` (`14.5972 -> 14.2914 ms`, порог `0.2000 ms`).

1% low оказался немного ниже baseline, а 0.1% low — выше. CPU p95 также плавал в
худшую сторону, хотя изменение находится только в GPU shader source. Я считаю эти
CPU/1% колебания шумом стенда, но фиксирую их честно. Решение оставить кандидат
основано не на одном среднем FPS, а на двух повторившихся улучшениях FPS, GPU
p50/p95/p99 и 0.1% low при неизменном workload и нулевых steady-state allocations.

### Моё мнение

Это правильный размер оптимизации для текущего этапа: небольшой, lossless,
изолированный и не мешающий L5/L6. Он не вернёт в одиночку все потерянные после L4
кадры — основная цена находится в самой перерисовке shadow cascades, — но уменьшает
стоимость receiver shader без снижения PCF-качества.

---

## Отклонено после A/B — убрать явный clamp из девяти PCF samples

**Статус:** проверено, полностью удалено.

### Гипотеза

Перед каждой shadow comparison выборкой UV явно ограничивался `clamp(0, 1)`, хотя
sampler уже использует `CLAMP_TO_EDGE`, а центральная координата предварительно
проверена. Удаление могло убрать 9 пар min/max, а в cascade blend — до 18.

### Результат

- Detailed baseline: `83.793 FPS`, GPU p95 `14.1366 ms`, 1%/0.1% low
  `49.019/45.248`.
- Candidate: `84.070 FPS`, GPU p95 `14.0842 ms`, 1%/0.1% low
  `49.661/46.045`.
- SUN_SHADOW p95 остался практически тем же: `2.9599 -> 2.9601 ms` (ожидаемо,
  receiver cost не входит в caster stage).

Изменение `+0.33% FPS / -0.053 ms GPU p95` слишком мало и находится в шуме. Скорее
всего, shader compiler уже устраняет лишнюю работу либо стоимость texture comparison
доминирует над clamp. Я откатил исходник и golden hashes.

### Моё мнение

Идея корректна по sampler semantics, но не имеет доказанной ценности на M1 Pro.
Повторять её стоит только при смене compiler/backend или при наличии shader ISA
evidence, которого текущий timestamp profiler не даёт.

---

## Отклонено после A/B — сортировать Sodium regions один раз на shadow frame

**Статус:** проверено, полностью удалено.

### Гипотеза

`SodiumShadowCasterLists` копирует и сортирует loaded regions отдельно для каждого
из трёх каскадов. Я сделал frame-local snapshot: сортировка один раз, а точный
region/section culling по-прежнему выполнялся для каждого каскада. Кэш depth или
caster selection не вводился, поэтому L6-контракт не затрагивался.

### Результат

- Baseline: `83.793 FPS`, GPU p95 `14.1366 ms`, 1% low `49.019`.
- Candidate: `83.694 FPS`, GPU p95 `14.1424 ms`, 1% low `49.016`.
- SUN_SHADOW p95: `2.9599 -> 2.9558 ms`.

Выигрыша нет. Сортировка мала по сравнению с обходом секций, построением точных
списков и GPU raster. Все frame-cache поля и lifecycle hooks удалены.

### Моё мнение

CPU-направление само по себе не ошибочно, но оптимизировать только sort бессмысленно.
Более сильный reuse exact selection требует надёжной topology-generation invalidation;
без неё можно получить пропавшие casters. Такой контракт нельзя добавлять ради
неподтверждённого микровыигрыша.

---

## Отклонено после A/B — dontCare load/store для R8 shadow color attachment

**Статус:** проверено, полностью удалено вместе с пробным Java/Swift ABI.

### Гипотеза

Sun-shadow pipeline выключает color writes, а последующее освещение читает только
depth. На Apple TBDR было логично не загружать, не очищать и не сохранять R8 color
attachment. Пробная реализация передавала явный color store action через Java FFM
bridge в Swift и выбирала `.dontCare` только во время `SunShadowRenderer`.

### Результат

- Full-frame FPS: `83.793 -> 82.965`.
- GPU p95: `14.1366 -> 14.3521 ms`.
- Минимальное окно FPS: `83.467 -> 79.522`.
- SUN_SHADOW p95 локально слегка улучшился: `2.9599 -> 2.9414 ms`.

Локальная экономия около `0.019 ms` не перенеслась на кадр, а итоговые хвосты стали
хуже. Дополнительная сложность render-encoder ABI не оправдана. Изменение полностью
удалено.

### Моё мнение

Это полезное напоминание о TBDR: правильный `.dontCare` не обязан заметно ускорять
кадр, особенно для трёх R8 targets, когда основная цена — vertex/raster/PCF. Нельзя
оставлять усложнение только потому, что оно теоретически красиво.

---

## Отклонено по коду и телеметрии — переписывать orphanWrite ради L4

**Статус:** не внедрялось; исходная формулировка проблемы не соответствует L4 hot
path.

`orphanWrite` действительно сохраняет prefix/suffix при частичном dynamic update,
и слепой discard нарушил бы содержимое буфера. Но реализация использует ограниченный
in-flight backing pool, а не безусловную новую allocation на каждый write. Главное:
L4 shadow parameters уже идут прямо в bounded shared ring, минуя этот путь.

Измерение показало только `312 bytes/frame` CPU-to-shared при примерно
`213 KB/frame` shared-to-private. Поэтому «переписать всё на ring buffers» не может
объяснить падение L4 и несёт риск сломать partial-write contract.

Мой вывод: возвращаться к full-range fast path можно только после per-call histogram,
который покажет заметный объём именно `orphanWrite`. Сейчас это не FPS-задача L4.

---

## Уже реализовано — precompiled metallib для встроенных shaders

**Статус:** полезная существующая оптимизация; повторно делать не нужно.

Gradle уже компилирует встроенные `.metal` sources в AIR и связывает
`metallum.metallib`. Swift сначала загружает precompiled library, а source compile
оставлен кэшированным fallback. Оба production benchmark подтвердили:

- `native_shader_library_mode = PRECOMPILED`;
- `native_shader_source_compile_count = 0`;
- pipeline failures = `0`.

Runtime-компиляция всё ещё нужна для сгенерированных Minecraft/Sodium MSL variants:
их нельзя заранее собрать в один статический metallib, потому что source зависит от
реальных resource packs/defines. Эти variants кэшируются по source/entry point.

Мой вывод: тезис о том, что Present/Clear/HDR постоянно компилируются во время игры,
устарел. Возможный отдельный проект — prewarm реально встреченных generated PSOs,
но только если лог покажет first-use spike после warmup. Текущий steady-state FPS от
этого не вырастет.

---

## Отклонено по correctness — удалить глобальный MTLFence

**Статус:** не внедрялось.

Один глобальный fence действительно консервативен, но ресурсы проекта используют
untracked hazard contracts. Между L4 shadow depth producer и main lighting consumer
есть настоящая зависимость. Широко удалить wait/update или механически заменить их
на «точечные» без полного resource dependency graph означает получить редкие data
races, мерцание или чтение незавершённой depth map.

В native cluster build уже есть точные intra-compute barriers там, где они нужны.
Дальнейшее дробление fence допустимо как отдельный архитектурный этап: сначала граф
producer/consumer для каждого ресурса, затем Metal Validation и A/B. Это не безопасная
локальная оптимизация после L4.

---

## Отклонено по точности — массово перевести cluster/culling math в half и fast

**Статус:** не внедрялось.

`MetallumClusterBuild.metal` выполняет conservative culling, finite checks и
координатные преобразования. Blanket `half` уменьшает диапазон/точность bounds и
может дать false-negative cull: свет будет всплывать или исчезать на границе cluster.
`fast::min/max/clamp` сам по себе не является гарантией двукратного ускорения и может
ослабить NaN/Inf semantics, на которых держится fail-closed поведение.

Detailed baseline показывает LIGHT_UPLOAD_CLUSTER_BUILD около `0.363 ms average /
0.413 ms p95`; это не источник основной L4-регрессии. Риск визуальной ошибки выше
доступного выигрыша.

Мой вывод: half имеет смысл только для отдельно доказанного bounded поля с CPU/GPU
property tests и conservative widening. Матрицы, depth bounds и culling planes сейчас
оставляю float.

---

## Отложено строго до L6 — cached clipmaps / dirty shadow pages

**Статус:** потенциально самый сильный shadow upgrade, но сознательно не внедрялся.

Кэшировать статический terrain и обновлять только dirty pages действительно может
убрать основную стоимость трёх полных caster passes. Но это буквально содержание L6
в `LIGHTING_ARCHITECTURE_PLAN.md`: static/dynamic split, invalidation, локальные
voxel shadows и сохранение стабильной texel phase.

Попытка сделать «маленький cache» сейчас создала бы скрытую вторую архитектуру без
world/chunk/light invalidation и помешала бы будущему L6. До этого этапа правильный
путь — уменьшать стоимость текущего receiver/caster кода lossless-правками, а не
подменять план.

---

## Отклонено для текущей архитектуры — pass fusion и memoryless G-buffers

**Статус:** общая идея верна, текущего безопасного объекта применения нет.

- HDR/UI seed fusion в проекте уже существует и является хорошей TBDR-оптимизацией.
- Shadow depth нельзя сделать memoryless: он производится в shadow pass и читается
  позже main lighting pass.
- Deferred G-buffers сейчас отсутствуют. Предлагать для них memoryless storage до
  появления единого render pass — оптимизировать несуществующую архитектуру.
- Попытка убрать load/store единственного неиспользуемого R8 shadow color target была
  реально измерена выше и не прошла full-frame A/B.

Мой вывод: pass fusion нужно рассматривать по фактическим producer/consumer edges,
а не по числу файлов или названиям эффектов. Закрытие encoder не всегда означает
избежимый bandwidth, если следующий проход действительно читает промежуточный
texture.

---

## Другие наблюдения и не-принятые направления

### Performance preset вместо Balanced

Performance использует два каскада `768²` и меньшую дальность. Это почти наверняка
вернёт больше FPS, но является изменением качества/покрытия, а не оптимизацией
одинакового результата. Я не подменял им Balanced benchmark и не менял default.

### Entity culling внутри каскадов

`featureFrame.executeSolid()` вызывается для каждого каскада, а явного per-cascade
entity culling в L4 bridge не видно. Это правдоподобный следующий L4-совместимый
кандидат для entity-heavy сцены, но только после создания точного visibility contract
и отдельного benchmark route. На текущем static fixture число entities мало; грубый
skip мог бы ломать off-camera casters, поэтому я его не вводил.

### Неиспользуемый cluster-mask PSO

`metallum_cluster_masks_v1` компилируется, но в текущем executor не dispatch-ится.
Удаление может уменьшить startup/maintenance, но не steady-state frame time. Без
отдельного startup trace и проверки будущих executor contracts я не стал смешивать
cleanup с FPS-изменением.

### Что в проекте уже сделано удачно

- Три submit-in-flight с semaphore back-pressure вместо busy wait.
- Shared memory для CPU-visible dynamic data и Private для статической geometry.
- Bounded parameter/upload rings без steady-state allocations.
- Precompiled builtin metallib с кэшированным fallback.
- Threadgroup memory в cluster/HDR compute kernels.
- Реальная HDR/UI pass fusion.
- Встроенный timestamp profiler и attested benchmark route; именно они не позволили
  оставить три теоретически красивых, но бесполезных кандидата.

---

## Итог записи

Я оставил одну lossless shader-оптимизацию с повторяемым приростом примерно `1.34%`
FPS и улучшением среднего GPU p95 примерно на `0.220 ms`. Три реализованных
кандидата были полностью удалены после отрицательного или шумового A/B. Остальные
громкие предложения отклонены по текущей телеметрии, correctness или границам
архитектурного плана.

Главный оставшийся резерв после L4 — стоимость полной перерисовки каскадов и PCF
receiver. Радикальное уменьшение первой части должно происходить на L6 с полноценным
cache/invalidation contract. До этого я предпочту несколько небольших доказанных
выигрышей одному «ускорению», которое меняет качество или создаёт редкие shadow bugs.
