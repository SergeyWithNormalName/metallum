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
- Будущие архитектурные идеи не внедряются раньше времени и без отдельной
  измеримой причины.
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
убрать основную стоимость трёх полных caster passes. Но для этого нужен полноценный
L6 static/dynamic split, invalidation, локальные voxel shadows и сохранение
стабильной texel phase.

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

---

## L6 rework — все видимые источники и устранение self-shadow rings

**Статус:** завершено и проверено на Apple M1 Pro.

1. Кольца около sea lantern создаёт не L5 LOD: nearest-выборка `64×64` cube cache
   сравнивает receiver с глубиной центрального луча texel и фиксированным bias `0.08`.
   На плоском полу чужой луч входит в сам пол заметно раньше receiver, поэтому пол
   затеняет себя квадратными концентрическими полосами. Нужна receiver-plane проверка,
   а не большой глобальный bias, который дал бы light leaking через стены.
2. Исчезновение теней заложено в текущий hard cap `1/2/2`: только BLOCK lights,
   полностью покрытые coarsest L5, сортируются по расстоянию до камеры. Повышать cap
   при старом `64² × 6 × 4 × 8 B` формате нельзя: это `768 KiB` на источник.

Реализован descriptor-driven resident atlas по `stableId` со страницами
`8/16/32/64`, fence-safe replacement и bounded build/upload budgets. Каждый источник
фактического L3 snapshot получает `READY/STALE/BUILDING/FAIL_CLOSED`; незатенённого
direct-вклада нет. Production DDA отключён: достижимость 96-шагового traversal в
обычном fragment PSO снижала короткий прогон до `53.42 FPS`, даже при двух DDA lights.

Квадратные кольца исправлены сравнением cache hit с tangent plane receiver. Heap
page payload больше не передаётся как native pointer: данные копируются прямо в
mapped Metal staging, что устранило воспроизводимый SIGSEGV на странице `64²`.

Финальный accepted Balanced HDR Native run (`1800 + 3000`) дал `70.43 FPS`, GPU p95
`20.21 ms`, минимум окна `68.62 FPS`, `0` dropped events. Установившееся состояние:
`594/594` descriptors, `46 READY`, `548 FAIL_CLOSED`, `DDA=0`, очереди и
`unshadowedContributing=0`. `clean check` (64 задачи) и отдельный live Metal API +
Shader Validation прошли без crash/fault и fallback после Advanced admission.

---

## 2026-07-19 — L6 dynamic hero shadows и atlas receiver A/B

### Динамический GPU-путь — оставлено

**Статус:** реализовано и принято.

Статический CPU-atlas сохранён. Свет в руке и движущиеся `ENTITY` используют один
Metal compute-dispatch по уже загруженному L5 clipmap; три suffix-страницы на hero-slot
соответствуют трём submit in flight и не уменьшают static residency. Preset budgets:
`1 × 16² × 32` шага (`147456 B` suffix), `2 × 32² × 96` (`1179648 B`) и
`4 × 32² × 96` (`2359296 B`) для Performance/Balanced/Ultra. Невыбранные entity
сохраняют `APPROXIMATE_DIRECT`.

Held light получает первый слот и свежую страницу каждого кадра. Moving entity
определяется порогом `1/64` блока и остаётся dynamic ещё 12 кадров; после остановки
CPU-page строится параллельно, а переходы static↔dynamic атомарны. Stable entity ID
передаётся в свободных `w` proxy ABI и исключает только self-shadow emitter. При
L5-scroll compute читает последний snapshot, уже принятый native context, и выбирает
самый детальный полностью tagged level; это устранило двухкадровые coverage gaps.

| Accepted route | FPS | GPU p95 | Dynamic p95 | Hero coverage | Fallback/miss/failure |
|---|---:|---:|---:|---:|---:|
| Balanced, `1800+3000` | 41.108 | 28.909 ms | 0.237 ms | 3000/3000, 2 slots | 0/0/0 |
| Ultra, `1800+3000` | 36.900 | 34.459 ms | 0.399 ms | 3000/3000, 4 slots | 0/0/0 |

Маршрут намеренно тяжелее static fixture: плавная camera orbit, факел в руке и четыре
движущихся entity-probe. Поэтому его FPS не является A/B со static route; acceptance
gate для новой работы — per-frame coverage и stage p95 (`≤1.0/2.0 ms`). Sparse L5
scroll stage продолжает измеряться, но его stationary budget не используется как
gate именно этого L6-маршрута; whole-frame tails остаются в отчёте. Static
`1800+3000` подтвердил `candidates/dispatches/rays = 0` и отсутствие dynamic stage.

`300+300` Metal API/Shader Validation дало 300/300 READY held frames, dynamic p95
`0.721 ms`, без crash/fault/fallback. `clean check` пересобрал и проверил 68 задач.
Third-person anchor использует интерполированную body-space точку руки игрока, а не
animated arm bone: стабильного bone transform в текущем renderer contract нет.

### Fragment atlas candidate 1 — отклонено после двух A/B

**Гипотеза:** dominant soft-shadow может переиспользовать уже проверенные hard lookup
данные: world transform, proxy result, light-to-receiver, distance и page bounds.

**Метод:** soft helper получал подготовленные данные hard lookup; число taps, веса,
seams, четыре transmittance layers, tangent-plane correction и порядок накопления не
менялись. Каждый прогон: одинаковый static route, `1800+3000`, detailed timing.

### Fragment atlas candidate 2 — отклонено после двух A/B

**Гипотеза:** normal length, receiver-plane numerator и `cacheFaceEdgeFloat` можно
вычислить один раз для трёх дополнительных taps.

**Метод:** значения передавались в три resolved-tap helpers без изменения выборок или
арифметики visibility. Кандидат тестировался отдельно на исходном коде, без candidate 1.

| Два прогона, среднее | FPS | GPU p95 | GPU p99 | 1% low | 0.1% low | CPU p95 |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 56.305 | 20.782 ms | 21.924 ms | 41.673 | 38.789 | 4.683 ms |
| Candidate 1 | 56.502 | 20.280 ms | 21.379 ms | 41.497 | 38.074 | 4.689 ms |
| Candidate 2 | 56.491 | 20.593 ms | 21.704 ms | 40.839 | 36.908 | 4.643 ms |

Candidate 1 улучшил средний GPU p95 на `0.502 ms`, но второй повтор ухудшил оба lows,
а средние lows и CPU p95 не прошли строгий критерий «без ухудшения». Candidate 2 дал
только `0.189 ms`, ниже порога `0.20 ms`, и также ухудшил lows. Оба изменения и их
goldens полностью удалены. Комбинация 1+2 не испытывалась, потому что независимо не
прошёл ни один кандидат.

### Explicit first-hit fast path — не реализовывался

Generated MSL для реального Sodium solid shader сохранил bounded
`for (layer < 4)` и ранние `return` для infinite/receiver-plane/zero-visibility
случаев. Значит явное дублирование первого слоя не имеет compiler-backed основания;
по условию эксперимента код не добавлялся.

**Итог:** в production оставлен только динамический GPU-путь. Lossless fragment
atlas-кандидаты честно остаются в журнале, но не в исходниках.

---

## 2026-07-19 — Отклонено: направленно отбрасывать внутреннюю лаву

**Статус:** не внедрялось.

### Гипотеза

В большом лавовом озере не считать свет от ячеек, окружённых лавой, либо оставлять
ему только направление вверх у верхнего слоя: соседние ячейки всё равно являются
источниками, поэтому расчёт «внутрь» якобы не влияет на картинку и должен заметно
поднять FPS.

### Почему отклонено

1. В vanilla `BlockLightEngine` это не постоянный render hot path. Источники лавы
   ставятся в очередь при загрузке чанка или изменении блока; `runLightUpdates()`
   затем хранит уже вычисленный lightmap. При распространении движок прекращает луч,
   когда сосед уже имеет равный или более высокий уровень света. Поэтому внутренние
   ячейки дают ограниченную одноразовую работу при загрузке/обновлении, а не
   значимую работу каждый кадр.
2. Отсечение «только наверх» некорректно. Боковая стенка, пещера под озером,
   ступенчатая граница жидкости и позднее удаление соседней лавы должны получать
   свет от соответствующих источников. Простая проверка шести соседей не описывает
   достижимость воздуха и меняет vanilla block-light результат.
3. В собственном Advanced-lighting пути Metallum полезная часть идеи уже сделана
   безопаснее: `DenseBlockLightCompactor` объединяет одинаковые плотные fluid
   emitters в пространственные proxy-источники. Контрактный пример сжимает плоскость
   `16×1×16` из `256` источников в `4`, а полный `16³` объём — из `4096` в `8`;
   proxy сохраняет охват каждого источника, суммарную radial energy и точный
   footprint для L6 self-shadow. Значит GPU direct-light loop уже не обрабатывает
   тысячи внутренних лавовых источников по отдельности.

### Проверка

`./gradlew advancedLightRegistryUnitTest` завершился успешно 19 июля 2026 года;
свойства compaction для плотной лавы, сохранение energy/support/footprint и границы
ресурсов подтверждены.

### Мой вывод

Гипотеза в данной форме отвергнута: она неверно называет per-frame источник затрат
и заменяет сохранение света визуально небезопасным правилом. Отдельным кандидатом
может быть только A/B-исследование palette-aware извлечения источников для
chunk-streaming: оно должно доказывать CPU-узкое место, сохранять vanilla lightmap
и сравнивать 1% low при полёте через лавовую местность. Это не является реализацией
данной гипотезы и не должно начинаться без такого профиля.

---

## 2026-07-22 — Исправлен HiDPI-contract Nether benchmark

**Статус:** внедрено; это исправление достоверности стенда, не оптимизация renderer.

### Гипотеза и причина проверки

Первый Nether production-запуск на `1e6697a` не дошёл до route и measurement:
launcher подтвердил Built-in Retina `3024×1964@120`, но controller завершился с
`timed out waiting for exact external 5K framebuffer`. На этом Mac framebuffer
display mode действительно имеет `3024×1964` backing pixels, тогда как logical
workarea имеет `1512×949` points. Значит validator ошибочно сравнивал две разные
единицы и делал любой такой Nether baseline невалидным.

### Метод и сохранённый контракт

`MetalFxBenchmarkController.isTargetFramebuffer` продолжает требовать выбранный
target monitor через exact GLFW monitor attachment (runtime exclusive-fullscreen
contract) и точный GLFW backing framebuffer; cached `Window.isFullscreen()` не
используется, потому что на macOS он может оставаться false уже после входа в exact
video mode. Logical `Window` width/height теперь проверяются только как
положительные. Refresh остаётся привязан к выбранному exact video mode и проверяется
launcher marker. Добавлен
`BenchmarkWindowContractTests`: Retina `3024×1964` framebuffer с logical
`1512×982` принимается, а неверный backing size и нулевое logical окно отвергаются.
`METALLUM_BENCHMARK=1` теперь неизменно оставляет GLFW mode changes ванильному
пути и не переводит startup exclusive mode в AppKit fullscreen; обычный production
client сохраняет AppKit policy.

### Результат и побочные эффекты

`./gradlew benchmarkWindowContractUnitTest --console=plain` прошёл. Проверка
`scripts/run_metal_benchmark.sh ... --preflight-only` подтвердила фиксированные
Nether route/settings: MetalFX off, Balanced, VSync off, `3024×1964@120`, HDR scene
и frozen simulation. Первый runtime на clean `0ae06ac` всё ещё не дошёл до
measurement: `Window.isFullscreen()` оказался stale false при уже прикреплённом к
target monitor GLFW окне. Это выявило вторую ошибочную cached-проверку validator;
она удалена. Follow-up smoke `300+300` после этой правки всё равно завершился тем
же timeout до `WINDOW_READY`; следовательно, stale cached flag был реальным
недостоверным условием, но не единственной причиной сбоя window transition. Нет
`ROUTE_READY`, `MEASURE_START/END` или nonzero summary. Renderer, изображение,
качество и tracked route/settings не менялись.

Финальная benchmark-only адаптация выключила AppKit fullscreen interception для
`METALLUM_BENCHMARK=1`. Smoke `300+300` подтвердил exact `WINDOW_READY` на
Built-in Retina с `3024×1964@120`, затем `ROUTE_READY`, `MEASURE_START/END` и
`COMPLETE`; raw JSONL дал валидный nonzero non-release summary (FPS `21.709`).
Короткий smoke не создаёт release attestation, потому что release contract требует
десять `300`-frame окон, то есть стандартные `3000` measured frames.

### Итог

HiDPI и native fullscreen benchmark contract восстановлены. Следующий baseline должен
идти на clean commit с тремя `1800+3000` production runs и отдельным detailed run;
короткий smoke не использовать как performance baseline.

---

## 2026-07-22 — Nether: исходные данные после восстановления contract

### Достоверность стенда — внедрено, но не является оптимизацией

**Причина проверки:** до этого этапа Nether-прогоны могли завершаться до
`ROUTE_READY` или сравнивать logical points с Retina backing pixels. Такие цифры нельзя
использовать ни как baseline, ни как результат A/B.

**Способ измерения и результат:** benchmark-contract последовательно восстановлен
четырьмя отдельными commit: `0ae06ac` (Retina framebuffer в pixels), `c4e1b61`
(exact GLFW fullscreen mode), `6662597` (attestation Nether HDR profile) и `452b98d`
(fullscreen launcher config). Это изменения условий достоверного измерения, а не
renderer hot path и не оптимизация FPS. После них сохранены три обязательных
production baseline-артефакта (`1800+3000`, без detailed markers) и отдельный detailed
артефакт с GPU-stage метриками; условия во всех случаях остаются неизменными:
Nether lava-stress, MetalFX off, Balanced lighting, VSync off, HDR, frozen
simulation, Built-in Retina `3024×1964@120`.

| Роль | Artifact stem | FPS | CPU p95 | GPU p95 |
|---|---|---:|---:|---:|
| Production baseline 1 | `20260721T232601Z-gc4e1b614a588-clean-nether-baseline-1-off` | 8.6924 | 272.5201 ms | 57.8272 ms |
| Production baseline 2 | `20260721T234157Z-g666259768439-clean-nether-baseline-2-off` | 12.3003 | 268.4227 ms | 56.5651 ms |
| Production baseline 3 | `20260721T235234Z-g452b98dd1aa2-clean-nether-baseline-3-off` | 8.5917 | 277.7705 ms | 57.7181 ms |
| Detailed, только атрибуция | `20260722T000347Z-g452b98dd1aa2-clean-nether-baseline-detail-off` | 8.5931 | 283.6399 ms | 57.8990 ms |

Все артефакты лежат в `run/logs/metallum-benchmarks/`. У первого production raw
валиден, но legacy analyzer оставил zero-byte summary; его текущая ручная агрегация
даёт приведённые `8.6924 FPS / 272.5201 ms CPU p95 / 57.8272 ms GPU p95`. Detailed
дополнительно показывает World Opaque около `37.2 ms`, Cluster Build `1.131 ms`;
его FPS не является production-метрикой.

**Побочные эффекты:** detailed report нужен только для атрибуции CPU/GPU стадий и не
смешивается с production FPS/frame-pacing. Сами четыре commit не дают основания
заявлять какой-либо выигрыш производительности.

**Итог:** внедрено как benchmark-contract. Production baseline теперь допускается к
сравнению следующих одиночных гипотез; улучшение Nether пока не заявлено.

### JFR tail diagnostic — опровергнут sidecar как главный источник CPU-tail

**Причина проверки:** после валидного baseline требовалось отделить CPU tail,
allocation/GC pressure и синхронизацию от неизменного GPU workload, прежде чем менять
native heaps или shader path.

**Способ измерения:** отдельный 77-секундный JFR diagnostic capture с последующей
агрегацией Render samples, Long allocation samples, young-GC pauses и monitor
contention. Это профиль причины, не acceptance benchmark и не A/B.

**Результат:** из `4697` Render samples `3880` (`82.6%`) пришлись на ресурсы L6.
Long allocation sample экстраполирует около `148.6 GB` или `~1.93 GB/s` allocation
pressure; за capture произошло `93` young GC, pause p99 `16.0 ms`, максимум
`17.1 ms`. Monitor contention не найден, а GPU-метрики относительно production/detailed
артефактов не изменились. Следовательно, Sodium light sidecar не подтверждается как
главная причина CPU-tail; профиль указывает прежде всего на L6 resource/capacity path.

**Побочные эффекты и ограничение:** JFR сам меняет нагрузку и Long allocation даёт
оценку, а не точный allocation counter. Поэтому эти числа нельзя принимать как FPS
результат или как доказательство качества изображения.

**Итог:** sidecar как главный CPU-tail кандидат отклонён. Диагностика оставлена как
основание для изолированного L6 A/B, но не как acceptance evidence.

### O3 private heaps — отложено для текущего CPU-tail

**Причина проверки:** старый план предлагал private heaps для GPU residency и memory
pressure. После JFR возник риск принять сложную native-memory работу за ответ на
Java/L6 allocation tail.

**Способ измерения:** сопоставлены назначение O3 (GPU resources/placement/aliasing) и
JFR-источник нагрузки; новый O3 A/B не запускался, чтобы не смешивать гипотезы.
Упомянутые здесь прежние current-Nether evidence относятся к старому dirty run, а не
к свежему clean/same-artifact A/B этой записи.

**Результат:** private heaps не устраняют наблюдаемый L6 allocation/capacity CPU-tail
и не имеют новой причины для первоочередной проверки. GPU workload в diagnostic не
изменился.

**Побочные эффекты:** это не общий запрет O3: heaps могут быть полезны при отдельно
доказанном native allocation, residency или bandwidth bottleneck.

**Итог:** для текущего Nether-tail **отложено/deprioritized**, не внедрено и не
объявлено отвергнутым навсегда.

### L6 capacity recovery без `O(blocked × lights)` — принято

**Гипотеза и причина проверки:** L6 capacity-recovery повторно обходил blocked lights
и создавал allocation/CPU tail. Нужно было сохранить качество и fairness, но сделать
recovery bounded по реально изменившимся/capacity-blocked объектам, а не по
`blocked × lights`.

**Семантика изменения:** lazy cache хранит только выбор наименее важного кандидата
для capacity recovery. Перед фактическим действием он не является источником истины:
live conditions перепроверяются, включая актуальные blocked/capacity/epoch условия.
Поэтому cache не отменяет retry, starvation/fairness, readiness либо fallback и не
меняет семантику света или теней.

**Способ измерения:** два независимых полных Nether A/B на одинаковых source и
built-artifact digest, `1800+3000` кадров, raw + summary со статусом `COMPLETE`:
`20260722T003215Z-g452b98dd1aa2-dirty-nether-l6-recovery-cache-1-off` и
`20260722T003725Z-g452b98dd1aa2-dirty-nether-l6-recovery-cache-2-off`. Сравнение
идёт с тремя production baseline выше; detailed не участвует в FPS/frame-pacing
acceptance. `metalRuntimeUnitTest` и `./gradlew clean check` прошли.

| Candidate | FPS | min window | 1% low | CPU p95/p99 | GPU p95/p99 | present p95/p99 |
|---|---:|---:|---:|---:|---:|---:|
| #1 | 21.927 | 21.784 | 18.921 | 14.311 / 15.135 ms | 49.861 / 52.142 ms | 48.505 / 50.889 ms |
| #2 | 21.593 | 21.498 | 18.712 | 14.330 / 14.844 ms | 50.614 / 53.045 ms | 49.116 / 51.779 ms |

**Результат:** средний candidate FPS `21.760` против `9.861` у трёх baseline. Даже
консервативное сопоставление худшего candidate с лучшим baseline даёт FPS `+75.55%`,
CPU p95 `-94.66%`, present p95 `-83.68%`, GPU p95 `-10.52%`, shared→private copy
`-61.29%`. Копирование составляет `74,400 B/frame` вместо `192,210–271,244 B/frame`,
успешных Metal buffer allocations — `0` вместо `1.55–2.60/frame`. Качество не
деградировало: `READY 92/102` против baseline `86–87`, `APPROXIMATE 1956/1946`,
`FAIL_CLOSED=0`, coverage/failures неизменны, `lightCount=2048`.

**Побочные эффекты и ограничение evidence:** compare tool не создал accepted receipt
из-за известного post-evidence ordering и отсутствующего `.accepted.json`. Это не
скрывается: raw + summary каждого прогона валидны и имеют `COMPLETE`, но receipt
нужно восстановить отдельной работой над инструментом, не подменяя им результат A/B.

**Итог:** **ВНЕДРЕНО/accepted**. Это устойчивый lossless результат по двум полным
прогонам, без регрессии качества или L6 safety contracts.

### Lossless `nDotL` early reject — принято

**Гипотеза и причина проверки:** для direct light с нулевым `nDotL` дальнейшие
range/sqrt/attenuation/radiance вычисления не могут изменить вклад. Нужен был
строго output-equivalent early reject, уменьшающий fragment работу в Nether без
перестановки вычислений, которые могут влиять на NaN/edge behavior.

**Семантика изменения:** используются существующие exact `inverseDistance` и `nDotL`;
сравнение строго `== 0.0` выполняется до range, sqrt, attenuation и radiance. Раньше
при таком `nDotL` shadow gate был false, а вклад уже был нулевым. NaN не равен нулю,
поэтому его прежнее поведение сохранено. Изменение покрыто только четырьмя fragment
goldens и не меняет материал, свет, тень или order для ненулевого вклада.

**Способ измерения:** два полных production Nether run с одинаковыми source/artifact
digest, `1800+3000` кадров, raw + summary `COMPLETE`:
`20260722T005156Z-gef373bfda794-dirty-nether-ndotl-reject-1-off` и
`20260722T005628Z-gef373bfda794-dirty-nether-ndotl-reject-2-off`. Acceptance
сравнивает их с accepted L6 pair; `materialContractUnitTest` и `./gradlew clean check`
прошли. Detailed-attribution, не участвующий в FPS acceptance:
`20260722T010217Z-gef373bfda794-dirty-nether-ndotl-detail-off`.

| Candidate | FPS | min window | 1% low | CPU p95/p99 | GPU p95/p99 | present p95/p99 |
|---|---:|---:|---:|---:|---:|---:|
| #1 | 25.709 | 25.516 | 22.593 | 14.015 / 14.614 ms | 44.605 / 45.972 ms | 41.039 / 42.731 ms |
| #2 | 25.678 | 25.554 | 22.218 | 13.920 / 14.810 ms | 44.712 / 46.688 ms | 41.402 / 43.625 ms |

**Результат:** средние accepted L6 → `nDotL` значения: FPS `21.760 → 25.694`
(`+18.079%`), GPU p95 `50.238 → 44.659 ms` (`-11.105%`), 1% low
`18.816 → 22.405` (`+19.072%`), present p95 `48.811 → 41.221 ms` (`-15.550%`).
Copies остаются `74,400 B/frame`, allocations `0`. Quality: в обоих run
`READY=87`, `APPROXIMATE=1961`, `FAIL_CLOSED=0`; coverage/failures неизменны при
`lightCount=2048`. READY находится в исходном baseline range, что согласуется с
математической output-equivalence, а не с понижением качества.

Detailed показывает World Opaque mean/p95-mean/max `28.861/29.785/35.438 ms` против
baseline `37.235/46.357/53.396 ms` (`-22.49/-35.75/-33.63%`). Cluster Build:
`0.7575/1.1876/2.2626 ms` против `1.1307/2.6833/11.8215 ms`. Это attribution, не
дополнительный FPS evidence.

**Побочные эффекты и ограничение evidence:** compare tool снова не выпустил accepted
receipt из-за известного post-evidence ordering/missing `.accepted.json`. Raw+summary
обоих production artifacts валидны и имеют `COMPLETE`; исправление receipt остаётся
отдельной benchmark-задачей и не скрывается.

**Итог:** **ВНЕДРЕНО/accepted**. Это второй раздельно измеренный lossless кандидат
после L6 recovery, с улучшением CPU/GPU/present tail и без visual/L6 regression.

### Conservative cluster-side planes — принято

**Гипотеза и причина проверки:** после L6 recovery и `nDotL` оставалось слишком много
ложных coarse cluster membership: sphere попадает в грубый X/Y/Z AABB, хотя целиком
лежит вне конкретной XY tile. Это повышает requested indices, per-cluster overflow и
fragment direct-light work. Нужен был reject без изменения видимого света.

**Семантика изменения:** после существующих coarse bounds строятся четыре side plane
конкретной tile. Sphere исключается только если она **целиком** находится снаружи хотя
бы одной valid plane; tangency сохранён строгим `<`, а не `<=`. Любой invalid,
nonfinite или overflow во входе/plane — fail-open. Z-range не уточняется; stable
upload order, candidate indices и caps не меняются.

**Способ измерения:** targeted `lightClusterValidation` с Metal API/GPU Validation и
`./gradlew clean check` прошли. Независимый CPU oracle покрывает четыре
plane tangent/outside fixture, edge/corner, invalid fail-open, witness без ложного
отбрасывания на representative tile/depth/projection с view bob, а также прежние
determinism/capped-prefix contracts.

Diagnostic artifact
`20260722T011853Z-g45ffbae7adb7-dirty-nether-cluster-side-planes-diagnostic-off`
дал `30.241 FPS`, requested `514226` против `733180–733738` у accepted `nDotL`,
p50 occupancy `48` против `68`, overflow `124` против `220`, Cluster Build average
`0.6717 ms` и World Opaque около `23.27 ms`. Это attribution, не production
acceptance.

Два full production artifact с одинаковым built-artifact digest `c2fcc5…`,
`1800+3000`, raw + summary `COMPLETE`:
`20260722T012057Z-g45ffbae7adb7-dirty-nether-cluster-side-planes-1-off` и
`20260722T012447Z-g45ffbae7adb7-dirty-nether-cluster-side-planes-2-off`.

| Candidate | FPS | min window | 1% low | CPU p95/p99 | GPU p95/p99 | present p95/p99 | requested/dropped | p50/overflow |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| #1 | 30.1959 | 30.1055 | 26.1780 | 13.6804 / 14.6092 ms | 36.6391 / 38.3044 ms | 35.2902 / 36.8553 ms | 516669 / 15199 | 48 / 130 |
| #2 | 30.0339 | 29.9038 | 26.1683 | 14.1949 / 14.7223 ms | 38.5898 / 40.0930 ms | 35.4377 / 36.8759 ms | 514594 / 15144 | 48 / 128 |

**Результат:** accepted `nDotL` mean → cluster planes mean: FPS
`25.6938 → 30.1149` (`+17.21%`), 1% low `22.4055 → 26.1731` (`+16.82%`), GPU p95
`44.6585 → 37.6145 ms` (`-15.77%`), present p95 `41.2206 → 35.3639 ms`
(`-14.21%`), CPU p95 `13.9675 → 13.9376 ms` (`-0.21%`). Requested indices
`733459 → 515631.5` (`-29.70%`), dropped `37363 → 15171.5` (`-59.39%`), overflow
`220 → 129` (`-41.36%`). Copies остаются `74,400 B/frame`, successful Metal buffer
allocations `0`. L6 в обоих production run: `READY=87`, `APPROXIMATE=1961`,
`FAIL_CLOSED=0`, coverage/failures неизменны, `lightCount=2048`.

**Побочные эффекты и ограничение evidence:** добавлены четыре plane checks в Cluster
Build, но их стоимость не вызвала измеримой регрессии; cap p95 остаётся `256`, однако
overflow значительно ниже. Compare receipt отсутствует по известному event-ordering/
missing `.accepted.json`; это не скрывается: raw + summary `COMPLETE` сохранены.

**Итог:** **ВНЕДРЕНО/accepted**, третий раздельный lossless Nether кандидат. Следующие
направления — только после обновлённого baseline и нового profile: не возвращаться к
уже принятому shader/cluster пути без новой причины и не подменять quality снижением.

## 2026-07-23 — Nether: conservative cluster corner-wedge culling

### Гипотеза и новая причина проверки

После accepted side-plane culling плотный Nether/lava профиль всё ещё имел p95/p99
occupancy `256`, `124/130` overflow clusters и `14,675/15,258` dropped indices. Sphere
может пересекать каждый из четырёх per-tile side plane по отдельности, но не иметь
общей точки с их парным угловым клином. Такие diagonal corner misses остаются в
compact list и заставляют World Opaque обходить источники, которые не могут осветить
ни один fragment tile. Это новая причина после side planes, а не повторение их A/B.

### Семантика изменения

После существующих single-plane checks для четырёх adjacent pair
`(left,bottom)`, `(left,top)`, `(right,bottom)`, `(right,top)` вычисляется точная
минимальная Euclidean distance от центра sphere до пересечения двух inward
half-spaces. Reject разрешён только когда sphere нарушает **обе** plane, оба
multiplier constrained projection положительны и расстояние строго больше radius.
Tile является подмножеством такого wedge, поэтому miss более широкого wedge не может
изменить ни один direct-light contribution в tile.

Tangency сохранён: strict comparison имеет дополнительный relative float guard
`1e-5`, который может только оставить почти касательный member. Любые invalid,
nonfinite, overflow, zero-normal, parallel/near-parallel plane, multiplier или
distance дают fail-open. Z slices, coarse bounds, caps, candidate/upload order,
buffers, ABI и telemetry не менялись.

### Способ проверки

`lightClusterValidation` с Metal API/GPU Validation и `./gradlew clean check` прошли.
Независимый normalized CPU oracle покрывает все четыре corner fixture
(inside/tangent/corner-only outside), single-plane case, invalid/parallel/
near-parallel fail-open, interior corner witnesses на representative depth и
normal/view-bob projection. Отдельные GPU fixtures проверяют, что каждый diagonal
miss не остаётся в target tile; прежние CPU/GPU determinism, capped-prefix и
in-sphere view-bob contracts сохранены.

Diagnostic artifact
`20260722T173901Z-gb1ee55ca41e5-dirty-dense-lights-corner-wedge-diagnostic-off`
использовался только для атрибуции и решения запускать production A/B. Acceptance —
два независимых full production run при прежнем Nether contract, MetalFX off,
Balanced, HDR, VSync off, `1800+3000`:

`20260722T174051Z-gb1ee55ca41e5-dirty-dense-lights-corner-wedge-production-1-off` и
`20260722T174426Z-gb1ee55ca41e5-dirty-dense-lights-corner-wedge-production-2-off`.

| Run | FPS | min window | 1% / 0.1% low | CPU p95/p99 | GPU p95/p99 | present p95/p99 | requested/dropped | occupancy p50/p95/p99 | overflow |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Side-plane baseline #1 | 32.1040 | 32.0423 | 28.5964 / 27.2278 | 13.0865 / 13.9563 ms | 34.2910 / 34.5278 ms | 32.1245 / 33.3577 ms | 514368 / 14675 | 48 / 256 / 256 | 124 |
| Side-plane baseline #2 | 31.9836 | 31.9438 | 28.6624 / 27.6401 | 13.4523 / 14.0540 ms | 34.5881 / 34.7771 ms | 32.1263 / 33.4132 ms | 516534 / 15258 | 48 / 256 / 256 | 130 |
| Corner wedge #1 | 34.3243 | 34.2513 | 31.2519 / 29.8757 | 13.2454 / 13.7789 ms | 32.4382 / 32.6462 ms | 29.8901 / 30.7230 ms | 443653 / 9948 | 44 / 116 / 256 | 87 |
| Corner wedge #2 | 34.0348 | 34.0013 | 30.2150 / 28.7590 | 13.0902 / 13.6333 ms | 32.6792 / 32.8884 ms | 30.1436 / 31.2690 ms | 445605 / 10297 | 44 / 116 / 256 | 88 |

### Результат, побочные эффекты и итог

Среднее side-plane baseline → corner wedge: FPS `32.0438 → 34.1796`
(`+6.67%`), 1% low `28.6294 → 30.7334` (`+7.35%`), 0.1% low
`27.4340 → 29.3173` (`+6.87%`), GPU p95 `34.4395 → 32.5587 ms`
(`-5.46%`), present p95 `32.1254 → 30.0169 ms` (`-6.56%`). CPU p95
`13.2694 → 13.1678 ms` остаётся без регрессии. Requested indices
`515451 → 444629` (`-13.74%`), dropped `14966.5 → 10122.5` (`-32.37%`),
overflow `127 → 87.5` (`-31.10%`); p95 occupancy падает `256 → 116`, при
сохранении p99 `256` как честного remaining tail.

Copies остались `74,400 B/frame`, successful Metal buffer allocations — `0`. Цена
изменения — четыре bounded two-plane check на candidate tile; near-tangent и
ill-conditioned случаи намеренно retain, поэтому false-positive work может остаться,
но false-negative cull не вводится. Compare receipt снова не выпущен из-за known
event-order failure; raw+summary обоих production artifacts имеют `COMPLETE`,
`0` dropped evidence events. Это ограничение benchmark tooling, не скрыто как
принятый receipt.

**Итог:** **ВНЕДРЕНО/accepted**, четвёртый отдельный quality-preserving Nether
кандидат. Следующий профиль должен измерять оставшийся World Opaque dense-light tail;
не возвращаться к уже принятому side-plane/wedge пути без новой причины.

## 2026-07-23 — Nether: conservative cluster side×depth-wedge culling

### Гипотеза и новая причина проверки

После accepted corner-wedge culling плотный Nether/lava маршрут всё ещё передавал в
World Opaque sphere, которая могла пересекать XY side planes по отдельности, но при
этом целиком находилась за внутренней границей своего log-depth slice. Для такой
sphere не достаточно одной XY plane: нужен тест пересечения с более широким
двухплоскостным side×depth wedge. Если sphere не пересекает этот wedge, она не может
осветить ни один fragment соответствующего tile/slice. Это новый геометрический
случай после corner wedges, а не повторный тест отдельного Z-range: `centerDepth ±
radius` уже точно определяет monotonic диапазон slice, но не устраняет совместный
side+depth false positive.

### Семантика изменения и correctness contract

После прежних coarse bounds, four side-plane и four XY-corner-wedge checks для
кандидата, нарушающего допустимую внутреннюю depth boundary, проверяется эта boundary
в паре с каждым из четырёх inward side planes. Reject разрешён только когда строгая
distance-to-intersection двух half-spaces больше radius; существующий conservative
near-tangent guard оставляет пограничный случай. Не вычисляется новый approximate
light range и не меняются порядок/лимиты compact prefix.

Endpoint clamp сохранён точно: для первого slice нет lower plane, для последнего —
upper plane, потому что fragment path clamp-ит depth index. Поэтому эти две
гипотетические внешние boundaries никогда не участвуют в reject. Глубинные границы
вычисляются один раз на threadgroup, а не `exp2` для каждого candidate. Invalid,
nonfinite, nonpositive scale/boundary, overflow, zero/ill-conditioned/parallel plane
или near-tangent input fail-open и retain candidate. Изменение не трогает quality,
Z-slice count, light/order/cap contracts, ABI, buffers, uploads/copies либо число
passes/encoders.

### Способ проверки

`./gradlew lightClusterValidation --console=plain` — **PASS** с Metal API и GPU
Validation на Apple M1 Pro. Независимый CPU oracle покрывает все `4×2` сочетания
side plane с нижней/верхней внутренней depth boundary: inside, tangent и edge-only
outside. Отдельно проверены endpoint clamp, invalid depth boundary и existing
parallel/near-parallel fail-open, normal/view-bob projection witness и GPU fixtures
для каждого side×depth miss. Existing determinism/capped-prefix/in-sphere contracts
сохранены.

Diagnostic `20260722T184506Z` использовался только для атрибуции: `36.7656 FPS`,
1%/0.1% low `31.0543/29.8332`, CPU p95/p99 `13.1433/13.8121 ms`, GPU p95/p99
`30.5468/30.8576 ms`. Cluster Build average/p95 выросли `0.89837/0.93423 →
1.1255/1.2665 ms`: дополнительная conservative geometry имеет измеримую локальную
цену. Но World Opaque average упал `21.4144 → 19.0044 ms`, requested
`444160 → 369055`, dropped `10318 → 8570`, occupancy p50/p95/p99 `44/116/256 →
36/100/256`, overflow `88 → 74`; поэтому гипотеза прошла к production A/B.

Три независимых full production run при прежнем Nether contract (MetalFX off,
Balanced, HDR, VSync off, `1800+3000`) дали:

| Run | FPS | 1% / 0.1% low | CPU p95/p99 | GPU p95/p99 | present p95/p99 | requested/dropped | overflow | allocations |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Side×depth #1 | 37.4334 | 30.6569 / 26.8534 | 12.9785 / 13.5786 ms | 29.9300 / 30.1403 ms | 27.4694 / 29.1441 ms | 367243 / 8343 | 72 | 0 |
| Side×depth #2 | 37.3986 | 31.2878 / 28.5979 | 13.0472 / 13.6917 ms | 30.0796 / 30.2333 ms | 27.5701 / 29.0123 ms | 367366 / 8282 | 71 | 10 buffers / 2.5 MiB |
| Side×depth #3 | 37.4145 | 33.5754 / 31.8977 | 12.8109 / 13.4184 ms | 30.0756 / 30.2793 ms | 27.4999 / 28.2804 ms | 367393 / 8250 | 71 | 0 |

### Результат, побочные эффекты и итог

Среднее accepted corner-wedge pair → side×depth mean: FPS `34.1796 → 37.4155`
(`+9.47%`), 1% low `30.7334 → 31.8400` (`+3.60%`), CPU p95/p99 `-1.69/-1.04%`,
GPU p95/p99 `-7.77/-7.78%`, present p95/p99 `-8.34/-7.05%`. Requested indices
`444629 → 367334` (`-17.39%`), dropped `10122.5 → 8291.7` (`-18.09%`), overflow
`87.5 → 71.3` (`-18.48%`), p50/p95 occupancy `44/116 → 36/100`. 0.1% low
`29.3173 → 29.1163` (`-0.69%`) находится в обычном разбросе: первые два run имели
редкие `nextDrawable` pauses, а третий их не подтвердил и показал `31.8977`.

Все три raw+summary reports имеют `COMPLETE` и `0` dropped timing events. Known
receipt-order exit 2 по-прежнему не создаёт accepted receipt, поэтому не
подменяется benchmark evidence. Во втором run отдельно наблюдалось окно `10`
buffers/`2.5 MiB`; кандидат не создаёт resources, buffers или copies, и #1/#3 имеют
`0` allocations, поэтому это не приписывается данному изменению.

Отдельно выявлен существовавший до этой работы риск correctness: aliasing L6 metadata
и stable-ID. Он не вызван и не исправлен этим кандидатом; считать его отдельным
последующим блокером перед расширением L6-зависимых оптимизаций.

**Итог:** **ВНЕДРЕНО/accepted**, пятый раздельно измеренный quality-preserving
Nether кандидат. Side×depth geometry даёт устойчивый выигрыш GPU/present tails и
FPS при неизменной картинке; не повторять эту проверку без нового профиля/геометрии.

## 2026-07-23 — Nether: post-L6 shadow-index tagging

### Гипотеза и причина проверки

В принятом corner-wedge профиле только `87/2048` источников имели L6 state
`READY`, остальные `1961` работали как `APPROXIMATE_DIRECT`. При этом fragment path
после range/`nDotL` reject всё равно читал 16-byte shadow descriptor каждого
подходящего источника. Гипотеза: после окончательной публикации L6 descriptors
пометить high bit только у compact indices со state `READY`/`STALE_RETAINED`, чтобы
для остальных источников не выполнять descriptor read/branch.

### Проверенный вариант и safety contract

Был реализован отдельный post-L6 compute pass после `uploadPending` и до World
Opaque. Он сохранял порядок и low 31 bits индекса, помечал только states `1/2`, а
state `0/3/4` и неизвестные оставлял untagged. Per-frame flag в уже reserved params
word включал новую интерпретацию только после успешно закодированного pass; при
любой ошибке оставался прежний shader path. Invalid/high-bit/OOB member переводился
в invalid sentinel. Новых buffers, readbacks, copies либо per-frame packets не было.

Targeted `lightClusterValidation`, `metalRuntimeUnitTest`,
`materialContractUnitTest`, precompiled и forced source-fallback shader validation
прошли. Они покрывали states `0..4` и unknown, empty batch, malformed range/header,
duplicate call, legacy fallback, index masking/order и отсутствие L6 read для
untagged source.

### Способ измерения и результат

Diagnostic `300+600` artifact
`20260722T180508Z-gce39e98f87a4-dirty-l6-shadow-index-tag-diagnostic-off`
показал небольшой согласованный stage-сигнал относительно corner-wedge diagnostic:
FPS `33.572 → 33.841` (`+0.80%`), GPU p95 `32.720 → 32.488 ms` (`-0.71%`), World
Opaque average `21.414 → 21.221 ms` (`-0.90%`). Однако дополнительный tag pass
поднял light-upload/cluster-build average `0.898 → 0.941 ms` и p95
`0.934 → 1.062 ms`; compute encoders выросли `3 → 4/frame` в detailed режиме.

Чтобы не принять короткий шум, выполнены два полных `1800+3000` production run с
одинаковым source/artifact digest и неизменным Nether contract:

| Run | FPS | min window | 1% / 0.1% low | CPU p95/p99 | GPU p95/p99 | present p95/p99 |
|---|---:|---:|---:|---:|---:|---:|
| Tag #1 | 33.7868 | 33.7288 | 30.0904 / 28.4375 | 13.2729 / 14.0708 ms | 31.8917 / 32.3451 ms | 30.4020 / 31.4801 ms |
| Tag #2 | 33.6728 | 33.6120 | 30.3303 / 28.8815 | 13.0990 / 13.7552 ms | 33.0984 / 33.4219 ms | 30.5330 / 31.3745 ms |

Artifacts:
`20260722T180715Z-gce39e98f87a4-dirty-l6-shadow-index-tag-production-1-off` и
`20260722T181108Z-gce39e98f87a4-dirty-l6-shadow-index-tag-production-2-off`.
Оба raw/summary имеют `COMPLETE`, `0` dropped timing events; receipt не создан из-за
известного event-order defect.

Среднее accepted corner-wedge pair → tag pair: FPS `34.1796 → 33.7298`
(`-1.32%`), 1% low `30.7334 → 30.2104` (`-1.70%`), 0.1% low
`29.3173 → 28.6595` (`-2.24%`), present p95 `30.0169 → 30.4675 ms`
(`+1.50%`). CPU p95 практически неизменен (`+0.14%`), GPU p95 также не дал
устойчивого выигрыша: среднее `32.5587 → 32.4951 ms` (`-0.20%`), причём runs
разошлись `31.8917/33.0984 ms`. Copies остались `74,400 B/frame`, successful Metal
buffer allocations — `0`.

### Побочные эффекты и итог

Вариант добавлял один compute pass/encoder/dispatch/PSO в каждый Advanced frame.
Экономия чтения маленького, хорошо кэшируемого descriptor ring не компенсировала
дополнительную границу pass и ухудшила реальный present pacing и lows. Вся
12-файловая реализация удалена; коммита кода нет.

**Итог:** **ОТКЛОНЕНО/rejected**. Не повторять отдельный post-L6 tag pass без новой
причины. Вернуться к самой идее можно только если tagging удастся встроить в уже
существующий pass после готовности descriptors либо передать shadow-state без нового
encoder/synchronization boundary.

## 2026-07-23 — Nether: equivalent nonzero block-light profile update skip

### Гипотеза и причина проверки

При плотной лаве изменение только `LiquidBlock.LEVEL` многократно приходит через
block-change hook. Для L3 оно раньше каждый раз materialize-ило тот же point light,
добавляло override, поднимало epoch и invalidated cached dense compaction, хотя
итоговые photometry, stable IDs и shadow footprint не менялись. Гипотеза: можно
консервативно пропустить только такой L3 update, если старый и новый state дают
гарантированно одинаковый **ненулевой** profile.

### Семантика изменения и safety contract

`MinecraftLightPolicy` сравнивает тот же `emissiveCell`, который authority для
materialization: emission должен совпадать и быть `>0`, chosen color/emissive block
должен быть тем же object identity, а `denseCellEligible` — совпадать. Поэтому
разные blocks/profiles, different emission, null и non-emissive states всегда идут
через прежний `recordBlockChange` path. Mixin сначала использует дешёвый
`newState.getLightEmission() > 0` pre-gate; для target lava это исключает
`LightTemplate`, RGB-array и dimension String allocation. Fluid-only exotic source
с нулевой block emission лишь консервативно не получает skip, без изменения картинки.

`observeHook` и `try/failClosed` остаются прежними. Критично, L5
`VoxelClipmapController.markBlockDirty` сохранён безусловным в отдельном try после
L3: geometry/optics invalidation не пропускается даже при L3 skip или L3 failure.

### Способ проверки и результаты

Targeted `advancedLightRegistryUnitTest` и `voxelOccupancyUnitTest` — **PASS**.
Контрактные tests проверяют реальные `Blocks.LAVA` states для всех допустимых
`LiquidBlock.LEVEL 0..15`, обе стороны сравнения, а также lava↔air, magma, другой
emission, non-emissive и null как conservative non-skip.

Детерминированный secondary workload сначала published real 4096-cell lava section,
затем прогнал 128 level transitions со snapshot после **каждого** update, с прогревом
и пятью повторами. Median skip/reuse — `0.388208 ms`; legacy
record+recompact — `157.520209 ms` (`~405.76x`). Exact operation counts:
`0/0` block overrides/registry epoch у skip против `128/128` у legacy. В финальном
property test 512 transitions дополнительно подтверждают zero extra overrides,
epoch-visible mutation и compaction-cache invalidation, при идентичных final
photometry, stable IDs и footprints; generation intentionally не считается shading
input.

Основной Nether detailed `300+600` regression diagnostic при закреплённом contract
дал `33.765 FPS`, GPU p95 `32.9272 ms`, cluster average/p95 `0.9922/1.0705 ms`,
`COMPLETE` и `0` dropped timing events. Для context accepted corner-wedge diagnostic
имел `33.57195 FPS`, GPU p95 `32.71996 ms`, cluster average/p95
`0.89837/0.93423 ms`. Это исключает явную static regression в этом diagnostic, но
не доказывает FPS benefit dynamic block-update path. Invalid `600`-frame non-detail
smoke исключён и не используется как evidence.

### Побочные эффекты и итог

Fast path намеренно не расширяет scope на L5 и не меняет world geometry, optics,
vanilla lightmap, lighting quality, benchmark settings или основной Nether route.
В production нет новой очереди, packet, copy или allocation на accepted lava-level
target; неизвестные state families остаются на прежнем safe path.

**Итог:** **ВНЕДРЕНО/accepted** как точное устранение эквивалентного L3
update-churn. Оно не принимается как утверждение о повышении основного Nether FPS:
для этого нужен отдельный valid dynamic-path A/B при неизменном benchmark contract.

## 2026-07-23 — L6: регрессия утечки света через близкую сплошную геометрию

### Гипотеза и причина проверки

После `f43acf7` на пользовательском скриншоте из Ancient City локальный cyan light
стал освещать поверхности за сплошными блоками. Установленный `HRDMetal.jar`
побайтно совпал с текущей `Pre-release`, поэтому экспериментальные Nether-ветки
исключены. Diff `f43acf7` показал единственное релевантное изменение L6 sampling:
фиксированный self-hit tolerance `0.08` был заменён на
`max(0.08, 0.28 / max(abs(N·L), 0.15))`. На скользящем угле он достигает
`1.8667` блока и способен принять настоящий одноблочный окклюдер за поверхность
receiver.

### Способ измерения и проверенные альтернативы

Из пользовательского сохранения создан read-only APFS fixture с digest
`f9a251e50d5112ccce2a29b8b4eb1e7d6999b75d6487541f50326bc4d97de087`.
Короткие Native и Spatial captures на сохранённых позициях, L6 descriptor telemetry
и временный shader sentinel проверили альтернативы по одной:

- ближайшие влияющие источники имели полное L5 coverage; общий высокий
  `coverageLimited` относился к дальним источникам и не объяснял дефект;
- L3/L6 frame contract в Spatial оставался валиден, глобальный fail-open теней не
  происходил;
- Native и Spatial сохраняли одну lighting-семантику, поэтому upscaler не был
  причиной геометрической утечки.

Точная промежуточная camera pose исходного clipboard-кадра не сохранилась, поэтому
её нельзя честно выдать за воспроизведённый pixel-perfect A/B. Вместо подгонки
маршрута добавлен детерминированный grazing-angle contract: прежняя формула
классифицирует hit на один блок перед receiver plane как self-hit, тогда как
ограничение `0.08` сохраняет его окклюдером. Source-contract запрещает возврат
углового деления, а shader goldens обновлены для всех Sodium/Minecraft fragment
вариантов.

### Результат, побочные эффекты и итог

Self-hit tolerance возвращён к `0.08` блока. Receiver-plane correction, cubemap
filtering, atlas layout, L3 photometry и качество открыто освещённых поверхностей не
изменены. `materialContractUnitTest` и `localVoxelShadowUnitTest` — **PASS**;
контрольный Spatial capture на том же immutable world не показал нового shadow acne
или shader-contract fallback. Capture-only `300+300` не используется как
performance evidence. Потенциальный побочный эффект — возврат acne, если будущий
cache filter перестанет распознавать receiver plane; это отдельно закрыто уже
существующим plane-recognition contract и новым one-block blocker witness.

**Итог:** **ВНЕДРЕНО/fixed**. Не масштабировать L6 self-hit tolerance обратно
пропорционально `1/abs(N·L)`: такой bias обязан оставаться существенно меньше
толщины блока либо сопровождаться отдельным доказательством, что реальные близкие
окклюдеры не отбрасываются.

## 2026-07-29 — Nether: raw `N·L` sign reject before normalization

### Гипотеза и safety contract

После принятого normalized `nDotL == 0` early reject fragment loop всё ещё выполнял
`inversesqrt` для каждого попавшего в radius источника, включая back-facing lights.
Проверялся более ранний reject по знаку finite `dot(normal, toLight)`: положительная
нормализация не может изменить знак. Non-finite значения fail-open проходили в прежний
путь, а существующий normalized `nDotL == 0` оставался shading authority. ABI, light
order/caps, cluster membership, L6 visibility и front-facing формула не менялись.

`materialContractUnitTest` прошёл после синхронизации четырёх fragment-source goldens.
Короткий detailed `300+600` A/B дал FPS `36.171 → 36.681`, но одновременно GPU p95
`29.992 → 30.065 ms` и 0.1% low `24.780 → 23.598`; signal признан шумным и был
проверен полным production run.

### Production A/B и итог

Одинаковый Nether contract: Built-in Retina `3024×1964@120`, HDR, MetalFX OFF,
Balanced, VSync OFF, frozen simulation, `1800+3000` frames. Baseline artifact
`20260728T213137Z-...-raw-ndotl-baseline-production-off` против candidate
`20260728T213525Z-...-raw-ndotl-candidate-production-off`:

| Variant | FPS / min window | 1% / 0.1% low | GPU p95 / p99 | worst GPU |
|---|---:|---:|---:|---:|
| Baseline | 36.427 / 36.178 | 29.333 / 26.111 | 30.8809 / 31.5699 ms | 34.2665 ms |
| Raw sign reject | 36.659 / 36.557 | 29.606 / 26.961 | 30.6955 / 31.5264 ms | 33.7065 ms |

Разница слишком мала для production: FPS `+0.64%`, 1% low `+0.93%`, GPU p95
`-0.60%`, GPU p99 `-0.14%`. Это ниже требуемого заметного выигрыша и не отделяется
от обычного межзапускового шума одним полным pair. Оба raw/summary имеют `COMPLETE`
и `0` dropped timing events; candidate acceptance receipt не создан из-за известного
event-order defect. Вся shader/test реализация удалена, кодового коммита нет.

**Итог:** **ОТКЛОНЕНО/rejected**. Не повторять отдельный raw-sign precheck без нового
профиля или способа избежать дублирующего dot для front-facing candidates.

## 2026-07-29 — L5: publication-gated critical brick invalidation

### Причина и correctness contract

`markBlockDirty` раньше сразу enqueue-ил все пересекающиеся L5 bricks с CRITICAL
priority, хотя новый immutable Sodium section snapshot ещё отсутствовал. На каждом
следующем `leaseUploadBatch` `captureContributors` видел
`snapshotRevision != revision`, возвращал `null`, а scheduler defer/requeue-ил ту же
заведомо невыполнимую работу. После accepted publication этот section всё равно
enqueue-ился повторно.

Изменение оставляет block event чистой revision/invalidation операцией и запоминает
`pendingCriticalVoxelRefresh` в section state. Ровно matching `publishAccepted`
сбрасывает флаг и enqueue-ит bricks с прежним CRITICAL priority; обычные публикации
остаются HIGH. Stale publication не выпускает work. Старый voxel mirror и shadows до
accepted geometry остаются теми же, поэтому timing публикации и качество картинки не
меняются; удалён только бесполезный pre-publication contributor scan/defer churn.

`voxelOccupancyUnitTest` проверяет три границы: block invalidation не увеличивает
queue offered, stale Sodium candidate также не выпускает work, matching empty/full
accepted candidate выпускает deferred CRITICAL refresh. Async ownership, unload,
retry, teleport и GPU-recovery state-machine tests сохранены.

### Torch-toggle A/B

Стенд: Built-in Retina `3024×1964@120`, HDR, MetalFX OFF, Balanced, VSync OFF,
`hdrtest-torch-toggle-v1`, `300+900`, detailed timing. Placement на measured frame
300, removal на 450, epoch end на 600. Первый baseline исключён: epoch пересёкся с
незавершённой первичной chunk queue (`1006` tasks вместо `14`). В accepted comparison
вошли два повторных baseline и два candidate run; все четыре имеют одинаковые
`34/8` rebuild requests, `14/7` meshing tasks, `16/8` outputs, `1,101,760` bytes,
zero telemetry errors/overflow и `0` dropped timing events.

| Pair mean | FPS / min window | 1% / 0.1% low | CPU p95 / p99 / max | present p95 / p99 / max |
|---|---:|---:|---:|---:|
| Baseline | 61.938 / 59.877 | 35.946 / 30.957 | 5.432 / 9.523 / 24.160 ms | 18.769 / 22.925 / 41.581 ms |
| Publication gate | 61.909 / 59.782 | 43.437 / 40.065 | 5.050 / 8.265 / 14.962 ms | 18.589 / 21.825 / 28.230 ms |

Средний FPS и min-window нейтральны (`-0.05/-0.16%`), как ожидается для редкого
event-path. Статтерные хвосты улучшились заметно: 1%/0.1% low `+20.84/+29.42%`,
CPU p95/p99/max `-7.03/-13.21/-38.07%`, present p95/p99/max
`-0.96/-4.80/-32.11%`; presenting-CB GPU p95/p99/worst `-3.03/-4.00/-6.68%`.

**Итог:** **ВНЕДРЕНО/accepted** как lossless архитектурная оптимизация block-change
stutter. Не переносить packing раньше matching Sodium publication и не снижать
CRITICAL priority уже готовой replacement geometry.

## 2026-07-29 — L6: page-local DDA scratch и packed-material lookup

### Причина и границы кандидата

`VoxelShadowCacheBuilder.buildPage` строит для 64-edge cache page ровно
`6 × 64² = 24 576` лучей. До изменения каждый луч создавал `Direction`, три
`Point`, два `float[4]`, `BrickCursor` и обычно `Trace`; occupied sample дополнительно
создавал `VoxelMaterialDescriptor`, clone `VoxelMaterialClass.values()` и `Sample`.
Это background worker path, но burst нескольких L6 rebuild конкурирует за CPU и
создаёт GC pressure именно при появлении/изменении локального источника и geometry.

Git archaeology обнаружил старую, неслитую и не описанную в журнале ветку
`b188498 perf: reuse L6 page ray scratch`. Она не переносилась автоматически:
текущий код и L5/L6 contracts изменились. На актуальном `promotion` гипотеза
перепроверена заново и расширена только в том же allocation scope: один
page-local `TraceScratch` теперь владеет hit arrays, `BrickCursor`, direction и
sample scalars, а packed optical byte читает precomputed transmittance LUT. LUT
строится через прежний `VoxelMaterialDescriptor.fromPackedUnsignedByte`, поэтому
quantization и float bits не меняются. Scratch не static/ThreadLocal и не делится
между atlas-builder и atlas-refresh workers.

Не менялись edge/LOD/maxSteps, порядок DDA arithmetic, tie handling, cancellation
polling, emitter self-exclusion, atlas layout, payload/ABI, приоритет workers или
качество shadows. `PageResult` defensive clone и allocation `Key` на переходе
между bricks оставлены отдельными будущими кандидатами.

### Allocation и correctness oracle

Детерминированный прогретый probe измерял total thread-allocated bytes пяти
последовательных 64-edge pages. Baseline дважды дал `7 972 329.6 B/page` и
`10.439/10.444 ms/page`; candidate дал `3 248 425.6`, `3 248 425.6` и
`3 248 408.0 B/page`, то есть **−59.25% allocations**. Время sparse unit fixture
осталось практически нейтральным (`~10.44 → ~10.37 ms/page`); это не заявляется
как DDA throughput win. Метрика включает два payload arrays из defensive clone,
поэтому показывает conservative total allocation, а не только inner loop.

Для всех page edges `8/16/32/64` добавлены endian-independent SHA-256 oracles по
canonical 32-bit words. 64-edge result сохранил `3171/24576` hits; payload
canonical SHA-256 остался
`6b26eb1adcae0a799902493bd37a8fb0d302576719689b66fc694b5d8683c79b`.
Также проходят immediate и deterministic mid-DDA cancellation, восемь concurrent
16-edge builds против sequential byte oracle и существующие opaque/glass/slab/
fence/missing-coverage/self-emitter contracts.

### Static-L6 toggle A/B

Старый `hdrtest-torch-toggle-v1` оказался невалидным для этой гипотезы:
`resident_shadows=0`, `shadow_live_bytes=0`, ни одного фактического static L6 page.
Добавлен `hdrtest-l6-static-toggle-v1`: тот же immutable Overworld fixture и pose,
но torch в подтверждённой air/grass точке `[96,75,-96]`, достаточно близко к
camera для 64-edge static shadow page. Contract прежний: Built-in Retina
`3024×1964@120`, fullscreen HDR, Balanced, MetalFX OFF, VSync OFF, `300+900`,
detailed timing. Временный INFO-log существующего `L6 atlas page ready` использован
только для attribution и возвращён в DEBUG.

Два baseline из detached `00cbb37` и два чистых candidate run имели одинаковые
`26/8` rebuild requests, `14/7` meshing tasks, `16/8` outputs,
`1 032 320` accepted bytes, zero errors/overflow и `0` dropped timing events.
Candidate `20260728T221818Z-...-candidate-buildms-detailed-off` исключён: epoch
пересёкся с initial chunk queue (`158` tasks).

| Pair mean | FPS / min window | 1% / 0.1% low | CPU p95 / p99 / max | present p95 / p99 / max |
|---|---:|---:|---:|---:|
| Baseline | 61.564 / 58.951 | 38.703 / 36.186 | 5.542 / 10.123 / 19.732 ms | 19.121 / 23.196 / 32.238 ms |
| Page scratch | 61.881 / 59.154 | 41.151 / 37.088 | 5.379 / 8.254 / 16.631 ms | 18.967 / 22.279 / 29.594 ms |

Средний FPS/min-window почти нейтральны (`+0.52/+0.34%`), а stutter tails
улучшились: 1%/0.1% low `+6.33/+2.49%`, CPU p95/p99/max
`−2.94/−18.46/−15.71%`, present p95/p99/max `−0.80/−3.95/−8.20%`.
Presenting GPU p95/p99 улучшились `−2.07/−2.78%`, но worst GPU был шумно хуже
`+9.47%`; это не GPU optimization. Четыре фактических 64-edge rebuild заняли в
среднем `19.259 → 18.817 ms` (`−2.30%`), слишком мало для отдельного latency claim.

Baseline artifacts:
`20260728T221623Z-...-l6-builder-baseline-buildms-diagnostic-off` и
`20260728T222013Z-...-l6-builder-baseline-buildms-repeat-off`.
Clean candidate artifacts:
`20260728T222138Z-...-l6-builder-candidate-buildms-repeat-off` и
`20260728T222337Z-...-l6-builder-candidate-buildms-clean-repeat-off`.

**Итог:** **ВНЕДРЕНО/accepted** как lossless L6 allocation/CPU-tail optimization.
Не заявлять рост среднего FPS или заметное ускорение самой DDA. Следующий отдельный
кандидат — убрать `VoxelShadowCacheMirror.Key` allocation на brick transition либо
устранить второй page payload clone с явным ownership contract; не смешивать их.

## 2026-07-29 — Nether L3: conservative corner×depth trihedral culling

### Причина и correctness contract

После принятых single side-plane, XY corner-wedge и side×depth-wedge проверок
плотный Nether всё ещё имел `p99=256` lights/cluster. Оставался отдельный
геометрический случай: sphere пересекает каждую из трёх inward half-space и каждую
их пару, но не достигает общего пересечения двух соседних XY planes и одной
нарушенной внутренней depth plane. Такие false-positive members не могут осветить
ни один fragment cluster, но оставались в compact list и выполняли полный L3/L6
fragment loop.

Новый final refinement решает точную three-active-constraint projection через
Gram/KKT. Reject разрешён только если центр нарушает все три planes, Gram matrix
хорошо обусловлена, все три multipliers строго положительны и exact distance² до
trihedral intersection больше inflated tangent radius². NaN/Inf, degenerate или
near-singular Gram, non-positive multiplier и near-tangent случаи fail-open retain.
Проверка выполняется после существующих single/pair rejects и только для той
внутренней depth boundary, которую нарушает центр. Первый и последний logarithmic
slice остаются незамкнутыми согласно fragment clamp contract.

Не менялись light radius/color/intensity, L3/L6 photometry или visibility, cluster
grid/caps, stable candidate order, compact-prefix semantics, buffers/ABI, uploads,
passes/encoders и quality settings. Независимый CPU oracle нормализует planes и
решает Gram system через matrix inverse, то есть не повторяет MSL cofactor algebra.
Explicit fixtures покрывают `4 corners × lower/upper`, inside/tangent/
trihedral-only miss, invalid/near-singular fail-open, representative perspective и
view-bob witnesses. Реальные Metal GPU fixtures покрывают те же восемь vertices;
отдельные CPU/GPU endpoint fixtures доказывают, что hypothetical first/last depth
plane не используется. `lightClusterValidation` прошёл с Metal API/GPU Validation.

### Detailed attribution

Одинаковый Nether contract: Built-in Retina `3024×1964@120`, fullscreen HDR,
Balanced, MetalFX OFF, VSync OFF, frozen simulation, `300+600`, detailed timing.
Baseline `20260728T234425Z-...-trihedral-baseline-detailed-off` против clean
candidate `20260728T235347Z-...-trihedral-candidate-detailed-clean-off`:

- requested indices `367243 → 354495` (`−3.47%`), dropped `8345 → 8022`,
  occupancy p50/p95/p99 `36/100/256 → 36/96/256`, overflow `72 → 70`;
- World Opaque average `14.399 → 14.048 ms` (`−2.44%`);
- light upload + cluster build average `0.804 → 0.928 ms` (`+15.39%`, `+0.124 ms`);
- сумма двух stages `15.203 → 14.976 ms` (`−1.49%`), FPS `50.693 → 51.166`.

Первый candidate detailed `20260728T235131Z-...-trihedral-candidate-detailed-off`
исключён полностью: endpoint tests изменили source tree после сборки artifact, и
attestation корректно завершила run с `source tree changed during the run`.

### Production A/B и итог

Два baseline и два candidate run по `1800+3000` кадров, все raw/summary `COMPLETE`,
без FAIL/screenshots и с `0` dropped timing events:

- baseline `20260728T235556Z-...-trihedral-baseline-production-1-off`,
  `20260729T000158Z-...-trihedral-baseline-production-2-off`;
- candidate `20260728T235858Z-...-trihedral-candidate-production-1-off`,
  `20260729T000453Z-...-trihedral-candidate-production-2-off`.

| Pair mean | FPS / min window | 1% / 0.1% low | CPU p95 / p99 / max | GPU p95 / p99 | present p95 / p99 / max |
|---|---:|---:|---:|---:|---:|
| Baseline | 51.149 / 50.971 | 38.312 / 32.847 | 13.878 / 15.022 / 20.825 ms | 22.631 / 23.084 ms | 20.522 / 21.937 / 31.396 ms |
| Trihedral | 52.403 / 52.253 | 39.050 / 33.308 | 13.844 / 14.798 / 20.155 ms | 22.210 / 22.721 ms | 19.957 / 21.987 / 30.579 ms |

Средний результат: FPS/min-window `+2.45/+2.51%`, 1%/0.1% low
`+1.93/+1.40%`, CPU p95/p99/max `−0.25/−1.49/−3.22%`, GPU p95/p99
`−1.86/−1.57%`, present p95/max `−2.75/−2.60%`; present p99 почти нейтрален
`+0.23%`. Requested indices `368180 → 354351` (`−3.76%`), dropped
`8450.5 → 8095.5` (`−4.20%`), overflow `73 → 70`; p95 occupancy `100 → 96`,
p99 честно остаётся `256`.

Known benchmark event-order defect снова не создал `.accepted.json` и дал launcher
exit 2 после каждого полного run; это не скрывается и не подменяется receipt:
каждый raw/summary содержит полные 3000 measured frames, `COMPLETE` и zero dropped
events. Frozen route доказывает dense-light steady GPU effect, но не моделирует
активное перемещение/chunk rebuild. Для провалов активной игры ниже 20 FPS следующий
отдельный приоритет — static-only fast path, исключающий per-frame dynamic L6
admission allocations при `dynamic=0/0/0`; не смешивать его с этим GPU-кандидатом.

**Итог:** **ВНЕДРЕНО/accepted** как шестой раздельный quality-preserving Nether
cluster refinement. Он даёт воспроизводимый, пусть умеренный, выигрыш без снижения
качества; не расширять его approximate plane math или ослаблением tangent guards.

## 2026-07-29 — L6 static-only admission fast path

### Причина и correctness contract

В `nether-lava-stress-v1` все 2048 L3 sources имеют
`LocalShadowSourceClass.STATIC_CACHE`, а L6 telemetry стабильно показывает
`dynamic=0/0/0`. Несмотря на это, каждый render frame строил 2048
`DynamicShadowAdmission.Candidate`, `LinkedHashMap` с entries/table, 2048
`TrackedCandidate`, несколько `HashSet`/reference arrays, а затем ещё одну
копию 2048 `FrameLight`. Это не меняло ни admission, ни тени, но создавало
CPU/GC pressure и периодические статтеры.

Новый guard срабатывает только если **каждый** source имеет точный class
`STATIC_CACHE`; `dynamicCoverage=false`, `selected=0` или stationary entity не
считаются доказательством. Pre-admission `FrameLight` уже имеет точно
те же `staticBuildAllowed=true`, `phase=STATIC`, `heroSlot=-1`, cache key/level,
edge, distance и clipmap, поэтом список переиспользуется без пересоздания.
Fast path обязательно очищает `motion` и все `retainedSlots`, точно как
обычный all-static `select`; последующий dynamic frame побайтно равен свежему
admission по phases, transitions и hero-slot order. Empty `DynamicFramePlan` также
переведён на immutable singleton.

Не менялись descriptor bytes/states/order, READY/STALE/APPROXIMATE policy,
resident atlas, L5/L6 rays/maxSteps, light photometry/radius/color, ABI/buffers, shaders,
GPU passes/dispatches и quality settings.

### Allocation proof и тесты

Env-gated `METALLUM_L6_STATIC_ADMISSION_PROBE=1`, 2048 static sources, 20 warmup +
100 measured calls на одном render-thread-like thread:

- legacy `select`: `304440 bytes/frame`, `0.291 ms/frame`, tracked checksum `204800`;
- final strict guard + reset: `0 bytes/frame`, `0.034 ms/frame`, checksum `0`.

То есть доказанно убрано 304.4 KiB JVM allocations и ~`0.257 ms`
(`−88.3%`) admission CPU на каждом dense-static frame; в этот probe не входит
дополнительно убранная вторая `FrameLight` copy. При 50–60 FPS это
около 15–18 MiB/s ненужного young-generation pressure.

`localShadowAtlasUnitTest` покрывает строгий predicate, duplicate stable ID,
dynamic→static-only→dynamic motion reset и очистку hero hysteresis. Короткий
`hdrtest-l6-dynamic-v1` (`300+900`, detailed) прошёл: held READY,
`5/2/3` candidates/selected/dropped, `12288` rays, fallback/coverageMiss/failures = 0,
FAIL_CLOSED=0. `./gradlew clean check --console=plain`: `79` tasks, SUCCESS.

### Production evidence и честная граница

Финальный source/artifact: `b2b9ce49...` / `301836c3...`. Три candidate run
`20260729T003232Z-...-final-production-1`,
`20260729T003519Z-...-final-production-2` и
`20260729T004225Z-...-adjacent-candidate`; все `1800+3000`, native
`3024×1964`, Balanced, MetalFX OFF, VSync OFF, COMPLETE, zero dropped timing events.
Качество и резидентность идентичны: descriptors `2048`, READY `87`,
APPROXIMATE `1961`, FAIL_CLOSED `0`, residents `87`, dynamic/fallback/failures `0`.

Против предыдущей exact baseline pair candidate mean дал: FPS/min-window
`−1.71/−2.27%`, 1%/0.1% lows `+1.88/+5.46%`, CPU p95/p99/max
`−0.33/−0.75/+0.79%`; GPU p95/p99 был шумно хуже `+2.28/+1.84%`.
Поэтом старый разнесённый baseline не используется для FPS claim.

Свежий legacy control `20260729T003901Z-...-fresh-control` и сразу следующий
final candidate `20260729T004225Z-...-adjacent-candidate` дали:
FPS/min-window `−1.01/−0.33%`, 1%/0.1% lows `+1.57/+3.94%`, CPU max
`−23.56%`, GPU p95/p99 `−0.04/−0.26%`, present max `−8.37%`; CPU p95/p99
и present p95/p99 были шумно хуже на `+0.76/+2.51%` и `+0.95/+0.85%`.
В frozen GPU-bound route нет воспроизводимого роста среднего FPS; есть
умеренное улучшение lows/max tails и детерминированное устранение
крупного per-frame GC source. Frozen simulation не моделирует активную игру с
chunk/entity churn, где young-GC relief должен быть полезнее.

Known event-order defect снова не создал `.accepted.json` для full Nether runs
и дал launcher exit 2; raw/summary содержат все 3000 measured frames,
COMPLETE и zero dropped events. Это не подменяется attestation.

**Итог:** **ВНЕДРЕНО/accepted** только как quality-preserving L6
allocation/anti-stutter architecture optimization. Не заявлять рост среднего
FPS. Не расширять guard за пределы exact all-`STATIC_CACHE` входа.

## 2026-07-29 — пять независимых dense-light гипотез: radiance prepare и L6 descriptor no-op

### Контрольный baseline

Все GPU-пробы использовали один `nether-lava-stress-v1` contract: native
`3024×1964`, exclusive fullscreen HDR, Balanced, MetalFX OFF, VSync OFF,
frozen simulation, detailed timing, `300+600`. Baseline
`20260729T072635Z-...-five-variants-baseline-detailed-off`: FPS/min-window
`50.552/50.406`, GPU p50/p95/p99/worst
`21.2797/21.8185/22.6404/23.5980 ms`, 1%/0.1% low `26.628/20.056`, light
upload + cluster build average/p95/worst `0.9044/1.0304/1.8718 ms`.
Raw/summary COMPLETE, zero dropped timing events. В scene 2048 L3 sources,
READY L6 pages 87 и APPROXIMATE 1961; cluster build занимает лишь около
`0.9 ms`, поэтому дальнейшие cluster-only правки не имеют реалистичного бюджета
на самостоятельные `+3%` всего кадра.

### 1. Lazy L6 receiver context — REJECTED

Receiver world transforms и partial-surface classification вычислялись только
после встречи первого READY/STALE descriptor, а APPROXIMATE sources обходили их.
Контракт и goldens прошли, но run
`20260729T073103Z-...-variant-1-lazy-l6-receiver-detailed-off` дал FPS `47.818`
(`−5.41%`), GPU p95 `23.1996 ms` (`+6.33%`) и cluster stage average
`0.9355 ms`. Вероятная причина — дополнительная divergence/register lifetime в
длинном fragment loop. Правка полностью откатана; не повторять lazy state machine
внутри per-light loop.

### 2. Повторное использование inverseDistance — ACCEPTED, малый эффект

Для обычного `distanceSquared >= 1e-6` distance теперь восстанавливается как
`distanceSquared * inverseDistance`, используя уже выполненный `inversesqrt`;
epsilon-ветка сохраняет прежний `sqrt(max(distanceSquared, 0))`. Первый чистый
run `20260729T073629Z-...-variant-2-reuse-inversedistance-detailed-off` дал FPS
`51.461` (`+1.80%`) и GPU p50 `21.0079 ms` (`−1.28%`), но GPU p95 был почти
нейтрален `21.8474 ms`. Повтор поверх финальных accepted changes
`20260729T074908Z-...-repeat-2-with-accepted-4-5-detailed-off` улучшил adjacent
FPS `51.279 → 51.680` (`+0.78%`). Направление среднего FPS повторилось дважды;
эффект меньше 3%, поэтому не заявлять его как крупный самостоятельный выигрыш.

### 3. Empty-cluster early return перед L6 contract — REJECTED

Точный `countLimit == 0` return был перенесён перед optional L6 validation и
receiver preparation. Run
`20260729T073936Z-...-variant-3-empty-cluster-early-return-detailed-off` дал FPS
`50.970` (`+0.83%`), но GPU p95 ухудшился до `22.2770 ms` (`+2.10%`), cluster
p95 — до `1.3338 ms`. Dense Nether почти не имеет полезной empty-cluster доли;
правка полностью откатана.

### 4. Radiance precompute в существующем cluster prepare — ACCEPTED

`metallum_cluster_prepare_v1` один раз на uploaded light вычисляет прежнее
`max(rgb, 0) * max(intensity, 0)`. Fragment loop теперь читает готовый scene-linear
RGB radiance вместо повторения clamp и vector×scalar для каждого contributing
fragment/light. Новый pass, buffer и ABI не добавлены; position, radius, cluster
membership, L6 visibility и итоговая формула не менялись. Metal compiler,
material goldens и полный GPU validation прошли.

Run `20260729T074319Z-...-variant-4-precomputed-radiance-detailed-off` дал FPS
`52.002` (`+2.87%`), GPU p50/p95 `20.8788/21.3189 ms`
(`−1.88/−2.29%`), 1%/0.1% low `36.695/31.758`; cluster stage average остался
`0.9016 ms`. Это лучший отдельный кандидат, но один run недостаточен для строгого
claim `+2.87%`; финальная совокупная пара ниже даёт более консервативную оценку.
Native `lightClusterValidation` дополнительно readback-проверяет prepared
`(0.2, 0.9, 0.4) × 2 → (0.4, 1.8, 0.8, marker=1)` на реальном Apple GPU.
Предвычисление не блокирует будущий colored-glass light: RGB transmittance должна
умножаться на prepared radiance на shadow/sample stage; для этого независимо
потребуется RGB вместо scalar visibility/cache contract.

### 5. Пропуск второго L6 descriptor repack — ACCEPTED как anti-stutter no-op removal

`prepareFrame` уже упаковывает descriptor slot текущего submit. Раньше
`uploadPending` немедленно повторно проходил и переписывал до 2048 descriptors,
даже когда `uploadedPages == 0` и dynamic page не стала READY. Теперь повторная
упаковка и coverage scan выполняются только при одном из этих двух точных state
changes; иначе переиспользуются уже провалидированные coverage counters текущего
submit. Negative-count guard и unit contract покрывают gate. Descriptor bytes,
states/order, atlas readiness и fail-open policy не меняются.

Static run `20260729T074629Z-...-variant-5-skip-l6-descriptor-repack-detailed-off`
не доказал рост среднего FPS относительно варианта 4 (`52.002 → 51.279`, шумный
`−1.39%`), но 1%/0.1% lows выросли `36.695/31.758 → 38.017/34.146`. Принимается
не как FPS claim, а как детерминированное устранение лишней render-thread работы
без изменения результата; особенно релевантно активной игре и submit-кадрам без
новой страницы.

### Финальная совокупность и граница результата

Два финальных identical-source run:
`20260729T074908Z-...-repeat-2-with-accepted-4-5-detailed-off` и
`20260729T075113Z-...-five-variants-final-confirmation-detailed-off`. Их mean:
FPS `51.379` против baseline `50.552` (`+1.64%`), min-window `51.197` против
`50.406` (`+1.57%`), 1%/0.1% lows `35.627/33.849` против `26.628/20.056`
(`+33.80/+68.77%`). GPU p50 улучшился примерно на `0.93%`, p95 практически
нейтрален (`+0.09%`), p99/worst лучше примерно на `0.68/2.78%`. Все runs COMPLETE,
без FAIL/screenshots и с zero dropped timing events; source/artifact финальной
пары `1b2bbde5...` / `f62670b8...`. Known event-order issue по-прежнему не создал
`.accepted.json`, поэтому доказательство — complete raw/summary, не receipt.

`./gradlew clean check --console=plain`: `79` tasks, BUILD SUCCESSFUL; в том числе
Metal API/GPU Validation, light-cluster, material, L6 atlas/voxel, dynamic shadow,
ABI и HDR validation. Runtime `exclusiveFullscreen` возвращён к пользовательскому
`false` после измерений.

**Итог:** проверено пять разных кандидатов. Внедрены 2, 4 и 5; 1 и 3 полностью
удалены. Честный подтверждённый итог — не `+3%` среднего FPS, а около `+1.6%` в
финальной паре при существенно лучших measured lows и меньшей лишней L6 CPU-работе.
Следующий потенциально крупный шаг требует не ещё одной микроправки fragment loop,
а изменения представления: embedded/per-cluster READY/STALE shadow-state summary
или compact light/index storage с отдельным quality-preserving A/B.

## 2026-07-29 — три архитектурные гипотезы: event-driven L6, uint16 L3, owned page payload

### Контрольный baseline

Один строгий `nether-lava-stress-v1` run
`20260729T080551Z-...-three-architectures-baseline-detailed-off`: native
`3024×1964`, exclusive fullscreen HDR, Balanced, MetalFX OFF, VSync OFF,
frozen simulation, detailed timing, `300+600`. FPS/min-window `51.810/51.807`,
GPU p50/p95/p99/worst `20.8819/21.4842/22.0714/22.8311 ms`, 1%/0.1% low
`32.292/31.103`; light upload + cluster average/p95/worst
`0.9064/1.0369/1.4309 ms`. COMPLETE, no FAIL/screenshots, zero dropped events.

### 1. Event-driven ожидание L6 atlas capacity — ACCEPTED как bounded anti-stutter work removal

При полном 64 MiB Balanced atlas 1961 видимый static source не имеет страницы и
остаётся в `capacityBlockedBuilds`. Старый scheduler каждый кадр снова проходил
весь snapshot и заново доказывал отсутствие места. Теперь scan можно пропустить
только при точном immutable snapshot: тот же полный список `FrameLight`, mirror
revision, free bytes и blocked count; нет builds/uploads/failures, recovery fences,
evicted targets или retired pages; каждый missing eligible source уже capacity-blocked.
Любое изменение света, L5 mirror, residency или async state немедленно возвращает
обычный scheduler.

Изолированный run
`20260729T081143Z-...-three-architectures-1-capacity-event-wait-detailed-off`
дал FPS `51.248` (`−1.08%`) и шумно худшие GPU tails, поэтому роста среднего FPS
не заявляется. 1%/0.1% lows были лучше на `+22.1/+13.0%`, но одного короткого run
недостаточно для причинного claim. Финальный production log даёт прямое
доказательство срабатывания: cumulative `capacityWaitSkips=1494` при неизменных
2048 descriptors, 87 READY, 1961 APPROXIMATE, 1961 capacity-blocked и zero
FAIL_CLOSED. Внедрено как точное устранение до 2048 бесполезных scheduler checks
и, главное, повторного построения/sort трёх candidate collections на стабильном
кадре, не как FPS win. Exact guard сам остаётся линейным по snapshot.

### 2. Компактные uint16 cluster indices — ACCEPTED, малый GPU/memory эффект

L3 допускает максимум 4096 lights, поэтому каждый индекс без потерь помещается в
`uint16`. Fill kernel теперь пишет `ushort`, все patched fragment variants читают
`uint16_t` через явное GLSL extension, Java/native layout сообщает stride 2.
Логические offsets/count/capacity и порядок света не изменены. Физический buffer
имеет независимый `max(indexCapacity×2, prefixBlocks×160)` floor, потому что prefix
passes до fill временно используют его начало для block statistics; это устраняет
опасную зависимость scratch capacity от новой ширины элемента.

Для `3024×1964` индексный payload уменьшился `9,142,272 → 4,571,136 bytes`
(`−4.36 MiB`, ровно `−50%`). Native Metal API/GPU Validation прошла, включая
candidate index `4095`, overflow/OOB guards, малый 160-byte prefix floor, ring и
teardown; material variants реально скомпилированы, production log подтверждает
active Advanced generation. Run
`20260729T082012Z-...-three-architectures-2-compact-u16-indices-detailed-off`
против baseline: FPS `52.229` (`+0.81%`), GPU p50/p95/p99
`20.8110/21.2335/21.6476 ms` (`−0.34/−1.17/−1.92%`). Cluster-stage average был
шумно хуже `0.9246 ms`. Эффект ниже 3%, но направление полезно по памяти и GPU
tails без изменения качества, поэтому принято.

### 3. Sole-owner transfer готовой CPU L6 page — ACCEPTED как allocation/stutter fix

`buildPage` создавал sole-owned payload, после чего public record constructor
безусловно клонировал его. Для edge 64 это второй массив и memcpy размером
`786432 bytes` ровно в момент завершения фонового shadow build — плохой источник
allocation/GC pressure после установки препятствия. `PageResult` теперь сохраняет
старый defensive-copy public constructor, но внутренний builder публикует свой
массив через private ownership-transfer path. Массив создаётся и передаётся один
раз; render thread по-прежнему только загружает его и не мутирует.

Canonical SHA для всех edges, exact legacy parity, cancellation, parallel builds,
page counters/coverage и отдельный тест defensive public ownership прошли.
Встроенный `METALLUM_L6_BUILDER_PROBE=1`, одинаковые 3 warmup + 5 measured
64-edge pages: прежний clone-path `3,248,408 bytes/page`, final ownership path
`2,461,960 bytes/page`; разница ровно `786,448 bytes/page` (`786,432` payload +
16-byte array object/alignment). SHA и hits `3171/24576` идентичны. Короткое время
`10.371 → 10.541 ms/page` шумно нейтрально, поэтому CPU-time win не заявляется.
Отдельный steady frozen route почти не нагружает page rebuild, поэтому run
`20260729T082313Z-...-three-architectures-3-owned-l6-payload-detailed-off`
не используется как прямое доказательство CPU-copy gain; он служит regression
gate. Против варианта 2 FPS `52.944` (`+1.37%`), GPU p50/p95
`20.5611/21.1417 ms`, но это может быть обычный run-to-run шум. Доказанный эффект —
минус одна 768 KiB allocation+copy на каждую готовую 64-edge CPU page без изменения
байтов результата.

### Совокупный результат и отклонённый следующий shortcut

Финал против свежего baseline: FPS/min-window `+2.19/+2.12%`, GPU p50/p95/p99
`−1.54/−1.59/−1.88%`, 1%/0.1% lows `+31.0/+15.1%`; последний показатель
не заявляется как устойчивый без длинной пары. Все три runs COMPLETE, zero dropped.
Ни одна правка отдельно не доказала ожидаемые `+3%`, но каждая имеет отдельный
correctness/mechanical proof и не меняет radiance, radius, membership, shadow
states, atlas samples, DDA limits или material/HDR quality.

Проверенный, но **НЕ РЕАЛИЗОВАННЫЙ** shortcut — embedded/per-cluster READY/STALE
summary в текущем L3 build. Сейчас окончательное состояние L6 появляется после
cluster build, поэтому tag этого кадра был бы stale и мог бы ложно пропустить новую
READY shadow (потеря качества). Дополнительно cluster prepare переиспользует все
четыре `GpuLight.metadata` words под bounds/admission, хотя fragment L6 всё ещё
читает `metadata.xy` как stable ID; старый комментарий «direct shaders never consume»
этому противоречит. Перед state summary нужен отдельный correctness stage:
сохранить stable ID в явном ABI и переставить frame order так, чтобы final L6
descriptor state был известен до L3 summary build. Не повторять прямой tag/high-bit
header shortcut без этой перестройки.

`./gradlew clean check --console=plain`: `79` tasks, BUILD SUCCESSFUL, включая
Metal API/GPU Validation, source-fallback и precompiled pipelines, L3 compact-list
OOB/overflow/max-index contracts, L6 canonical pages/cancellation/atlas, ABI/HDR/L5.
После измерений `exclusiveFullscreen` возвращён к пользовательскому `false`.

## 2026-07-29 — L8 materials/water: два отклонённых hot-path варианта и финальный gate

### Scope и контроль

Реализовался только L8: GGX/Schlick для metal/smooth, water/glass optics,
rain-driven wet surfaces, analytic dimension environment fallback и production
Temporal reactive weights. L6.5-L7 не реализовывались. SSR, depth ray marching,
full-resolution normal buffer, новые probes/pass/texture и per-frame cubemap capture
не добавлялись.

Контроль — текущий architecture baseline
`20260729T080551Z-gfcf6177ae911-dirty-three-architectures-baseline-detailed-off`.
Все сравниваемые runs использовали один `nether-lava-stress-v1` contract: native
`3024x1964`, exclusive fullscreen HDR, Balanced, MetalFX OFF, VSync OFF, frozen
simulation, detailed timing, `300+600`. Контроль: FPS/min-window `51.810/51.807`,
GPU p50/p95/p99/worst `20.8819/21.4842/22.0714/22.8311 ms`, 1%/0.1% low
`32.292/31.103`.

### Отклонено 1 — material resolver на каждом terrain fragment

Run `20260729T093438Z-...-l8-materials-detailed-smoke`: `42.643 FPS`, GPU p95
`25.7393 ms`. Регрессия критическая: dry dielectric платил за material struct,
weather, view vector, transmission setup и material-aware helper state. Вариант
полностью удалён.

### Отклонено 2 — ранний material gate, но дублированные lighting branches

Run `20260729T094257Z-...-l8-material-gated-detailed-smoke`: `48.677 FPS`, GPU p95
`22.5124 ms`. Gate вернул большую часть стоимости, но две версии environment/cluster
helpers всё ещё расширяли shader и register pressure. Примерно `-6%` FPS относительно
контроля — неприемлемо; вариант полностью удалён.

### Принято — единый буквальный L3-L6 common path и отдельные bounded L8 terms

Финальный shader один раз вызывает прежние environment и clustered diffuse helpers.
Material resolver, water normal/transmission, environment GGX и один dominant local
GGX candidate выполняются только за coherent gate для tagged/translucent/rainy
surface. Entity и End Portal возвращены на точный прежний L3-L6 путь.

Run `20260729T094641Z-...-l8-single-legacy-call-detailed-smoke`: FPS/min-window
`52.858/52.609`, GPU p50/p95/p99/worst
`20.4921/20.9015/21.3232/22.6685 ms`, 1%/0.1% low `35.313/29.709`, COMPLETE,
zero dropped events. Против контроля: FPS `+2.02%`, GPU p95 `-2.71%`, 1% low
`+9.36%`, 0.1% low `-4.48%`. Последний tail находится внутри short-run regression
limit `-12%`; устойчивый FPS win по одному detailed run не заявляется, но
критического ущерба common path не обнаружено.

Reactive вариант компилируется и выбирается только для Sodium terrain при активном
Temporal, пишет в уже существующий `R8` ring и объединяется с diagnostic reactive
через `max`. Native/Spatial/MetalFX OFF используют обычный измеренный flavor.
Отдельный Metal runtime test подтверждает, что seeded weight `191/255` переживает
diagnostic pass. Runtime `exclusiveFullscreen` после прогонов возвращён к
пользовательскому `false`.

### Temporal reactive MRT: исправление output ABI и отклонённый blending

Первый live Temporal PSO обнаружил, что явные GLSL locations недостаточны: Mojang
intermediary compacted outputs в reflected order, и SPIRV-Cross выдавал scalar
`metallumL8ReactiveMask` в `color(0)`, а `fragColor` в `color(1)`. Metal закономерно
отклонял scalar против `RGBA8Unorm`, после чего Advanced fail-closed переходил на
Vanilla. Такие runs, включая `20260729T101906Z`, `20260729T102050Z`,
`20260729T102209Z` и `20260729T102636Z`, недействительны как L8 performance evidence.

Runtime теперь до MSL emission повторно назначает locations по точным SPIR-V output
names, требует ровно два output и проверяет тип/слот `float4 fragColor -> color(0)`,
`float reactive -> color(1)`. Live run `20260729T103125Z` подтвердил успешный Metal
PSO и активный Advanced, но вариант с аппаратным `max` blending на terrain R8 дал
`44.442 FPS / GPU p95 25.1049 ms` против Advanced+Temporal контроля
`57.788 FPS / 20.2936 ms`: `-23.09% FPS`, поэтому полностью отклонён.

### Принято — overwrite видимой terrain surface, max только при diagnostic merge

Для opaque depth test оставляет видимый fragment; Sodium translucent рисуется
back-to-front, поэтому конечный вес принадлежит ближайшей поверхности. R8 terrain
attachment теперь пишет без blending, а необходимый `max(material, disocclusion)`
остаётся в последующем diagnostic merge. Это не меняет resource lifetime, формат,
clear/load/store contract или Native/Spatial отсутствие attachment.

Интерливированный comparable Temporal Quality A/B на `hdrtest-static-v1`, native
`3024x1964`, HDR, Balanced, VSync OFF, detailed `300+600`:

- control без выбора reactive flavor, но с тем же Advanced+Temporal:
  `20260729T103845Z`, FPS/min-window `55.475/53.372`, GPU p50/p95/p99/worst
  `20.8395/22.1505/23.2338/39.5352 ms`, 1%/0.1% low `23.873/18.540`;
- production candidate: `20260729T103720Z`, FPS/min-window `51.147/48.402`,
  GPU `21.8299/23.9044/24.1374/26.2249 ms`, lows `32.641/30.358`, COMPLETE,
  zero dropped events, Advanced/clustered lighting active.

Разница: FPS `-7.80%`, GPU p50/p95/p99 `+4.75/+7.92/+3.89%`, 1%/0.1% lows
`+36.73/+63.74%`; worst GPU tail ниже на `33.67%`. Один более ранний no-blend run
был отклонён analyzer из-за нулевого clustered telemetry snapshot во втором окне и
не используется как доказательство. Финальный FPS loss остаётся внутри принятого
short-run regression limit `-12%`: ущерб некритичный, но FPS win не заявляется.
После A/B production branch восстановлен, `exclusiveFullscreen=false`.

## 2026-07-29 — L8 alpha-cutout ошибочно входил в glass optics

Live-скриншоты выявили серо-серебряную листву, траву и боковой overlay grass block,
особенно вдали. Причина находилась строго в L8: untagged terrain с sampled alpha
ниже `0.985` объявлялся glass непосредственно во fragment shader. Alpha-tested
текстуры и усредненная mip-alpha поэтому получали transmission, environment
refraction и GGX стекла.

Fragment-alpha heuristic удален. Material fallback теперь определяется один раз при
Sodium remesh: только настоящий `TRANSLUCENT` pass продвигает неизвестный dielectric
в glass, а `CUTOUT`/`CUTOUT_MIPPED` остаются dielectric независимо от texture alpha.
Регрессии фиксируют oak leaves, grass block и сохранение glass fallback для
translucent terrain. `./gradlew clean check --console=plain` прошел полностью:
`80` tasks, включая actual GLSL -> SPIR-V, Metal API/GPU Validation и L8 reactive
merge. Исправление не добавляет ресурсов, проходов или fragment work; на cutout
common path оно удаляет ветку L8. Для уже собранных chunk meshes требуется remesh
(`F3+A`) или перезапуск; live-проверка тех же ракурсов остается пользовательским
signoff.

## 2026-07-29 — L8 water wave phase прыгала при ходьбе

Live-скриншот и shader audit локализовали артефакт в процедурных water normals.
`metallumWaterNormalV1` восстанавливал позицию как camera-relative view position плюс
только дробная camera fraction. Целый `cameraBlockAndFlags.xz`, уже находящийся в
L6/L8 fragment packet, отсутствовал; при каждом переходе камеры через границу блока
дробная часть сбрасывалась, и оба синусоидальных узора скачком меняли фазу.

Фаза теперь разделена на integer block turns и bounded camera-block-relative часть.
Два близких к прежним wave vector заданы целыми turns в точном 256-блочном периоде;
это сохраняет непрерывность на положительных, отрицательных и period-wrap границах,
не конвертируя большие absolute coordinates во float. Численная регрессия проверяет
эти границы, actual Sodium/Minecraft GLSL компилируется в SPIR-V, а
`./gradlew clean check --console=plain` прошел полностью: `80` tasks, включая Metal
API/GPU Validation и Temporal runtime validation.

Новых buffers, passes, textures или allocations нет. Обычный terrain path не менялся;
несколько integer ALU и bounded phase reduction выполняются только внутри уже
существующего water material gate рядом с двумя прежними `sin/cos`. Live walking
signoff на том же берегу остается обязательным: автоматические тесты доказывают
непрерывность формулы, но не заменяют наблюдение движения в игре.

## 2026-07-29 — L8 выбеливал vanilla biome tint воды

Live-скриншот показал бледную серо-голубую воду в сравнении с vanilla. CPU/remesh tint
не терялся: `metallumUnlitBase` уже содержал linear atlas texel, умноженный на vanilla
fluid vertex/biome tint. Потеря насыщенности происходила позже внутри L8: analytic
`refractedEnvironment * transmittance` сначала занимал до `0.96 * 0.78 = 74.88%`
prepared albedo, а затем этот результат повторно заменял до `0.96 * 0.62 = 59.52%`
уже освещённого vanilla color.

Для water базовый RGB теперь остаётся vanilla. L8 refraction используется как scalar
luminance modulation: отношение refracted/vanilla luminance ограничено `0.82..1.18`
и смешивается с единицей весом `0.45`, поэтому итоговый диапазон равен `0.919..1.081`,
а RGB chromaticity biome tint математически сохраняется. После этого diffuse input
возвращается к vanilla albedo. Glass сохраняет прежний цветной transmission path;
water procedural normal, `refract`, Beer-Lambert calculation, Fresnel, environment/sun
GGX, dominant local specular и Temporal reactive weight не удалены.

Изменение добавляет только два dot, bounded scalar ratio и mix внутри уже активного
water L8 gate. Новых texture samples, buffers, passes, allocations или non-water work
нет; steady-state FPS impact ожидается ниже измеримого шума. Автоматические numerical,
actual-source GLSL/SPIR-V и полный renderer validation не заменяют live visual signoff.

## 2026-07-29 — L8 material-aware wet response

Live-скриншоты показали over-glazing: земля и дерево получали почти одинаковую
низкую roughness, а снег и растительность — слишком сильный ровный блик. Причина —
единая fragment-side wet policy для всего sky-visible opaque terrain: при полном
дожде обычный dielectric опускался до roughness `0.2176`, F0 одновременно рос с
`0.04` до `0.075`, а микроструктура альбедо не влияла на блик.

Приняты отдельные remesh-time классы stone/wood/porous в свободных compact base
codes `1/5/7`. Полные wet targets: roughness `0.28/0.42/0.72`, specular scale
`0.78/0.48/0.10`; общий грунт ограничен `0.50/0.28`. Wet F0 теперь стремится к
`0.025`, а небольшая bounded-модуляция roughness использует luminance уже выбранного
albedo texel. Поэтому добавочных texture samples, buffers, passes или allocations нет.

Производительный dry common path сохранен буквально: wet-only stone/wood/porous
декодируются только внутри существующего rain gate и без wetness не запускают
environment/local GGX. В дождевом пути добавлены только несколько scalar mix/dot
операций поверх уже загруженного albedo. Целевые material/actual-shader тесты и
полный `clean check` являются автоматическим доказательством структуры и контрактов,
но новый live A/B FPS и визуальный signoff на пользовательской сцене всё ещё нужны;
неизмеренное улучшение производительности не заявляется. Dry Sky Fresnel не
расширялся в рамках этого изменения по явному разрешению пользователя.

## 2026-07-29 — L8 wet specular полоса на вертикальной стене

Live-скриншот показал узкую яркую полосу на боковой грани здания только во время
дождя. Wet eligibility опиралась на normal, восстановленную через `dFdx/dFdy`.
Производные корректны внутри плоского примитива, но на границе/тонкой геометрии helper
fragments могут дать нестабильную ориентацию. Прежняя формула принимала любое
положительное `dot(normal, up)`: даже малая ложная wetness открывала environment и
local GGX, который на grazing angle превращался в заметную вертикальную полосу.

Wet-only dielectric/stone/wood/porous теперь получают compact material tag только
для квадов с устойчивой remesh-time `faceNormal.y > 0.55`. Шейдер дополнительно
использует smooth transition `0.55..0.85`, а rain resolver требует non-emissive
tagged surface. Вертикальная стена поэтому не может войти в wet optics даже при
неустойчивой экранной производной. Крыши и верхние грани сохраняют намокание.

Новых GPU resources, texture samples, passes и allocations нет. Dot ориентации
перенесён внутрь uniform/material rain gate; в сухом common path он не выполняется,
а во время дождя вертикальные стены теперь пропускают весь material resolver/GGX.
Автоматические numerical и actual GLSL/SPIR-V проверки фиксируют пороги и gate;
финальный visual signoff требует дождя и remesh (`F3+A`) на исходном здании.

## 2026-07-29 — L8 резко выключался после окончания дождя

В material packet записывался raw `EnvironmentDescriptor.rain()`. Дополнительная
live-проверка обнаружила, что `getRainLevel()` на клиенте способен оставаться высоким
после остановки видимых частиц, поэтому даже корректный release не начинался вовремя.
Нужен target, соответствующий именно визуальному weather state.

В `SunShadowGpuResources` добавлен один persistent scalar `materialRainWetness`:
экспоненциальный response с attack `1.25 s` и release `4.0 s` обновляется только при
сборке существующего environment packet и записывается в уже выделенный
`materialWeatherAndTime.x`. Шейдер и ABI не расширяются; нет texture history,
buffers, passes, allocations или per-fragment temporal state. Его target равен
`getRainLevel()` только пока `ClientLevel.isRaining()` true; при остановке видимого
дождя target становится нулём немедленно, а влажность убывает непрерывно и за
12 секунд опускается ниже 6%.

## 2026-07-29 — L8 rain-level, конечное высыхание и защита от дождя

Повторная live-проверка выявила две ошибки прежнего transition fix. Экспоненциальный
release асимптотически приближался к нулю, но никогда его не достигал; wet-only shader
gate проверял `> 0.0`, поэтому даже спустя минуту оставался в material optics. Кроме
того, eligibility использовала только upward normal и интерполированный skylight.
Skylight не является precipitation visibility: он распространяется под крыши и в
пещерные проёмы, из-за чего закрытые поверхности выглядели мокрыми.

Источник target возвращён к единственному `ClientLevel.getRainLevel(partialTick)` без
`isRaining()`. Ненулевой rain level отображается в узкий диапазон film strength
`0.80..1.0`: зависимость от интенсивности сохраняется, но слабый дождь не выключает
эффект почти полностью. Существующие attack/release `1.25/4.0 s` сохранены; CPU
response теперь защёлкивает значение в точный target при остатке `<= 0.01`, а оба
wet-only shader gate используют тот же epsilon. Таким образом появление остаётся
плавным, высыхание гарантированно заканчивается нулём и сухой common path восстанавливается.

Для каждого Sodium section render context на потоке подготовки сохраняется `16x16`
снимок vanilla `MOTION_BLOCKING` heightmap. Worker переносит ссылку в переиспользуемый
`LevelSlice`, а `BlockRenderer` делает один array lookup на блок и выдаёт wet tag только
upward quad, чья поверхность находится не ниже precipitation height. Запросов к live
world из worker thread, per-frame/per-fragment heightmap reads, новых GPU buffers,
textures, passes или ABI нет. Дополнительная стоимость ограничена 256 O(1) heightmap
чтениями и примерно 1 KiB на полную mesh-задачу; закрытые wet-only surfaces одновременно
перестают попадать в дорогой rain GGX path. После изменения roof geometry соответствующим
секциям нужен обычный Sodium remesh; visual signoff в игре остаётся обязательным.

## 2026-07-29 — L8 vanilla-style water и непрерывный wet optics

Повторный live-скриншот показал, что после возврата vanilla biome tint вода всё ещё выглядела
слишком прозрачной и стеклянной: analytic environment reflection доминировал над исходной
палитрой, а optical transmission `0.96` оставлял только `4%` diffuse-вклада. Sampled alpha и
Sodium translucent pass при этом уже оставались ванильными, поэтому менять alpha означало бы
ломать сортировку и ресурс-паки вместо устранения причины.

Water transmission уменьшен до `0.82`, что повышает долю vanilla texture × biome tint diffuse
до `18%`. Только гладкое environment reflection дополнительно получает stylistic вес `0.58`;
sun GGX не масштабируется этим весом. Процедурные волны, Fresnel, `refract`, Beer-Lambert,
bounded luminance modulation и local/solar highlights сохранены. Новых samples, ресурсов,
passes и allocations нет; меняются только константы и один scalar multiply внутри water gate.

Причина резкого начала/окончания дождевого эффекта оказалась второй: CPU wetness уже имела
attack/release, но fragment shader при `wetness > 0.01` сразу добавлял environment и local GGX
в полном объёме. Порог удалён из визуального смешивания. Для wet-only материалов оба optic
вклада теперь умножаются на текущую `wetness`; intrinsic water/glass/metal/smooth optics всегда
имеют вес `1.0`. CPU exact-zero snap остаётся, поэтому сухой common path бесплатен. Цена — два
scalar multiply только в уже активном material branch, без новых texture reads или GPU state.
Actual-source GLSL/SPIR-V contract прошёл; окончательная субъективная плотность воды и плавность
weather transition требуют live signoff.

## 2026-07-29 — L8 устраняет повторяемую волну на воде

Live-скриншот показал, что две фиксированные синусоиды образуют заметную диагональную решётку
на большой плоской поверхности. Это было свойством procedural normal, а не texture atlas:
частоты и амплитуда были одинаковы в каждой точке воды.

В L8 добавлен один smooth value-noise слой: четыре integer-hash угла 8-блочной ячейки
интерполируются cubic fade и детерминированно сдвигают фазы `sin/cos` и их общую амплитуду
`0.065..0.090`. Координата собирается из modulo-256 integer camera block и camera-relative
fractional position; и hash period, и noise scale замкнуты на 256 блоков. Поэтому изменённый
рисунок стабилен при walking и large-world wrap, но больше не выглядит копией одной волны.

Стоимость ограничена четырьмя small integer hash evaluations и несколькими scalar ALU только
для water fragments, без texture samples, passes, buffers, allocations или работы над сушей.
Actual-source GLSL/SPIR-V тест дополнительно проверяет 256-block periodicity и block-boundary
continuity; live signoff остаётся нужен для субъективной плотности рисунка и fps на полной воде.

## 2026-08-12 — Nether L4 Sun-Shadow Runtime Guard (Эксперимент отклонен)

**Статус:** Отклонено (`REJECTED_NO_MEASURABLE_SCREENING_GAIN`), изменённый код откатён из продакшена.

### Описание и аудит гипотезы

Была предложена гипотеза, что динамический uniform-guard в MSL вызывающей функции позволит пропустить выполнение `metallumSunVisibilityV1` в Nether/End измерениях:
```glsl
bool sunShadowActive = (metallumEnvironment.contract.w & 1u) != 0u
        && metallumEnvironment.directionAndFlags.w > 0.0;
if (sunShadowActive && directionalWeight > 0.0 && (hasDirectionalLight || hasSkyLight)) {
    sunVisibility = metallumSunVisibilityV1(viewPosition, normal);
}
```

Аудит показал:
1. В исходном базовом коде уже присутствовала проверка `if (directionalWeight > 0.0)`.
2. Для фрагментов ландшафта в Cave `skyVisibility` равен `0.0`, поэтому `directionalWeight` равен `0.0`, и исходный код уже пропускал вызов `metallumSunVisibilityV1` для ландшафта.
3. Прямое парное Tier B сравнение (Guard OFF vs Guard ON) на `hdrtest-cave-v1` показало:
   - Guard OFF: `163.32 FPS` (GPU p95 `5.97 ms`)
   - Guard ON: `164.67 FPS` (GPU p95 `5.65 ms`)
   - Разница: `+1.35 FPS (+0.83%)` — находится в пределах повседневного шума стенда для 160+ FPS.

### Аудит раннего claim Tier C

Предыдущий 2x2 Tier C claim классифицирован как `INVALID_BASELINE_CANDIDATE_SEPARATION`: оба кандидатных и один из "baseline" прогонов выполнялись на коммите с уже введённым guard, а один baseline прогон являлся reference capture и игнорировался анализатором. Плейсхолдер отменён, код откатён.

### Сохранённый диагностический результат (Ablation Finding)

- **FULL_ADVANCED компилированный шейдер**: ~160–168 FPS в Cave при тихой обстановке, ~31 FPS при тяжелых взаимодействиях L3/L4/L6.
- **Компил-тайм абеляция `#define METALLUM_ABLATE_L4_SUN_SHADOWS 1`**: ~165–168 FPS (SUPPORTED: удаление кода L4 receiver на этапе MSL препроцессора радикально меняет генерацию кода/распределение регистров).
- **Runtime guard**: не воспроизводит компил-тайм прирост над базовым `directionalWeight > 0.0` (SUPPORTED).
- **Причина**: UNKNOWN (требует прямых счетчиков компилятора/GPU для точного подтверждения).

Шейдер откатён к чистому baseline. Следующий гипотетический шаг — специализация MSL PSO под измерение.

---

## 2026-08-17 — OPT-AMBIENT-PSO-1: Compile-Time AMBIENT_ONLY Advanced Terrain Shader Specialization

**Статус:** Принято (`ACCEPTED_PRODUCTION_PERFORMANCE_GATE_MET`), оставлено в коде.

### Контекст и стенд

- **ID задачи:** `OPT-AMBIENT-PSO-1`
- **GPU:** Apple M1 Pro (Apple Silicon).
- **Дисплей:** Встроенный Retina, `3024×1964 @ 120 Hz`, exclusive fullscreen.
- **Настройки:** HDR scene output, Advanced lighting, Balanced shadows, Fancy, render/simulation distance `16/12`, VSync off, MetalFX off (`benchmark/settings/native-hdr-fancy-v1.json`).
- **Маршрут:** `benchmark/routes/nether-lava-stress-v1.json`, 1800 кадров прогрева + 3000 измеряемых кадров.
- **Цель:** Экспериментально проверить, дает ли компил-тайм специализация фрагментного шейдера ландшафта под профиль окружения `EnvironmentDescriptor.Profile.AMBIENT_ONLY` измеримое снижение времени кадра GPU без изменения качества и математики освещения.

### Реализация

1. **Семантический контракт и авторитет:**
   - Создан `TerrainEnvironmentSpecialization` (`FULL`, `AMBIENT_ONLY`), авторитетом выбора которого является исключительно семантический контракт `EnvironmentDescriptor.Profile.AMBIENT_ONLY`. Все остальные профили (`CELESTIAL`, `END`, неизвестные/ошибочные) строго fail-open переходят на `FULL`.
2. **Расширение пайплайнов и чистота кеша:**
   - Добавлены варианты `METALLUM_ADVANCED_AMBIENT_ONLY` и `METALLUM_ADVANCED_REACTIVE_AMBIENT_ONLY` в `HdrShaderFlavor` и `MetalCompiledRenderPipeline`.
3. **Физическое исключение неиспользуемых ресурсов:**
   - В `AdvancedDirectLightingShaderPatcher` через `buildAmbientOnlyFragmentAbiAndHelpers` деривативно удалены:
     - 4 текстурных/сэмплерных слота: `metallumSunShadow0`, `metallumSunShadow1`, `metallumSunShadow2`, `metallumCloudShadow`.
     - 6 небесных вспомогательных функций: `metallumPcfV1`, `metallumCascadeVisibilityV1`, `metallumSunVisibilityV1`, `metallumCloudTransmittanceV1`, `metallumUnderwaterCausticGainV1`, `metallumWaterSquareCelestialMaskV1`.
     - Небесные ветки в `metallumEvaluateEnvironmentV1` и `metallumEvaluateMaterialEnvironmentV1` (GGX specular для солнца/луны).
   - Все локальные источники света, L6 воксельные тени, расчет шероховатости, Fresnel, GGX аналитические источники сохранены в 100% эквивалентности.
4. **Валидация компилятора и тесты:**
   - `MetalCrossShaderCompiler` проверяет, что сгенерированный MSL для ambient-only не содержит привязок к слотам теней (`[[texture(12..15)]]`, `[[sampler(12..15)]]`).
   - Написан полный набор юнит-тестов (`AdvancedDirectLightingShaderTests`, `MetalRuntimeTests`), подтверждающих отсутствие небесных символов и математическую эквивалентность при нулевом небесном освещении.

### Основные результаты измерений (Primary Acceptance: Same-Artifact Round B3 -> A3 -> A4 -> B4)

Для устранения теплового и позиционного смещения второй раунд выполнен в реверсивном порядке на едином бинарном артефакте (`2a63827c39df921a`):

- **B3 (Candidate AMBIENT #3):**
  - FPS: `22.693` (min-window `22.577`), 1% low `20.733`, 0.1% low `20.093`
  - GPU ms (p50/p95/p99): `45.6086 / 47.1517 / 48.0048 ms` (worst `50.8271 ms`)
- **A3 (Baseline FULL #3):**
  - FPS: `21.266` (min-window `21.218`), 1% low `19.318`, 0.1% low `18.787`
  - GPU ms (p50/p95/p99): `48.4885 / 50.5544 / 51.6903 ms` (worst `53.8587 ms`)
- **A4 (Baseline FULL #4):**
  - FPS: `22.343` (min-window `22.303`), 1% low `20.629`, 0.1% low `19.954`
  - GPU ms (p50/p95/p99): `46.3096 / 47.7178 / 48.2416 ms` (worst `50.6255 ms`)
- **B4 (Candidate AMBIENT #4):**
  - FPS: `22.662` (min-window `22.624`), 1% low `20.812`, 0.1% low `20.300`
  - GPU ms (p50/p95/p99): `45.7041 / 47.1088 / 47.8454 ms` (worst `50.3949 ms`)

**Сводка независимого раунда (Baseline Mean A3+A4 -> Candidate Mean B3+B4, 4 парных сравнения):**
- **Направление:** `CONSISTENT_IMPROVEMENT` (4 улучшено, 0 в пределах погрешности, 0 регрессий).
- **FPS:** `21.8045 ± 0.5383` -> `22.6776 ± 0.0219` (`+0.8731 FPS`, **`+4.00%`**)
- **GPU p95:** `49.1361 ± 1.4183 ms` -> `47.1303 ± 0.0215 ms` (**`-2.0058 ms`**, **`-4.08%`**)
- **GPU p50:** `47.3990 ± 1.0894 ms` -> `45.6564 ± 0.0478 ms` (**`-1.7426 ms`**, **`-3.68%`**)
- **GPU p99:** `49.9660 ± 1.7243 ms` -> `47.9251 ± 0.0797 ms` (**`-2.0409 ms`**, **`-4.08%`**)
- **FPS 1% Low:** `19.9735 ± 0.6555` -> `20.7723 ± 0.0396` (`+0.7988`, **`+4.00%`**)
- **FPS 0.1% Low:** `19.3704 ± 0.5835` -> `20.1963 ± 0.1035` (`+0.8259`, **`+4.26%`**)
- **Present Interval p95:** `47.6869 ± 1.4682 ms` -> `45.6682 ± 0.0343 ms` (**`-2.0187 ms`**, **`-4.23%`**)
- **Измеренный steady-state allocation delta:** `0 bytes` по отслеживаемым категориям ресурсов.

### Дополнительные свидетельства (Supporting Evidence: All 8 Runs Combined)

Объединенные 8 прогонов двух сессий (A1..A4 vs B1..B4), потребовавшие флаг `--allow-source-change` из-за промежуточных правок журнала между сессиями при неизменном бинарном артефакте:
- **FPS:** `21.7913 ± 0.6080` [21.26..22.34] -> `22.6989 ± 0.0488` [22.66..22.77] (`+4.16%`)
- **GPU p95:** `49.1665 ± 1.5626 ms` [47.72..50.55] -> `47.3659 ± 0.4793 ms` [47.11..48.08] (`-1.8006 ms`, `-3.66%`)
- **GPU p50:** `47.4393 ± 1.2368 ms` -> `45.8971 ± 0.4692 ms` (`-1.5422 ms`, `-3.25%`)
- **GPU p99:** `50.0349 ± 1.8905 ms` -> `48.1688 ± 0.4140 ms` (`-1.8661 ms`, `-3.73%`)

### Детальная атрибуция и анализ гипотезы

Одиночная последовательная пара detailed-прогонов с включенными тайминг-маркерами (`detailed-full` затем `detailed-ambient`):
- `world opaque` avg: FULL `30.5044 ms` -> AMBIENT `31.6986 ms` (`+3.91%`)
- `world opaque` p95: FULL `31.3941 ms` -> AMBIENT `32.9602 ms` (`+4.99%`)
- `light upload + cluster build` avg: FULL `0.8951 ms` -> AMBIENT `0.8961 ms` (`+0.11%`)
- `full frame GPU` p95: FULL `48.4440 ms` -> AMBIENT `49.8302 ms` (`+2.86%`)

**Интерпретация атрибуции:**
1. Detailed-прогоны служат исключительно для качественной проверки отсутствия лишних стадий (число проходов, построение кластеров и т.д. идентичны), но не являются критерием acceptance.
2. В этой единичной последовательной паре прогон `detailed-ambient` шел вторым после ~40 минут непрерывного рендеринга и продемонстрировал хронологический дрейф времени выполнения (~+3,5 % по ряду стадий), совместимый с изменением thermal/power state; телеметрия частот GPU и thermal state не снималась, поэтому причина дрейфа экспериментально не установлена. Данная одиночная пара не позволяет напрямую подтвердить микроархитектурный механизм выигрыша.
3. Продакшен-ускорение (стабильные `47.13 ms` против `49.14 ms` в симметричном A/B) **согласуется с гипотезой** снижения объема кода шейдера, уменьшения давления на регистровый файл и улучшения планирования инструкций компилятором Metal при удалении неиспользуемых сэмплеров и функций, однако точная аппаратная причина (регистры/occupancy/ISA) прямыми GPU-счетчиками не измерялась и остается не доказанной экспериментально.

### Заключение

Кандидат `OPT-AMBIENT-PSO-1` уверенно проходит Performance Gate по независимому same-artifact раунду B3/A3/A4/B4 (GPU p95 `-2.01 ms` / `-4.08%`, FPS `+4.00%`, улучшение низких квантилей 1% и 0.1% на `+4.00%..+4.26%` при нулевых аллокациях по отслеживаемым категориям). Специализация утверждена и сохранена в основном коде.

---

### [2026-08-20] GOD-RAYS-2.5: Volumetric Shaft Isolation & Physical Atmospheric Single Scattering Model

#### 1. Context & Root Cause
- **Problem**: Предыдущая интеграция объёмного света формировала равномерную дымку/туман по всей сцене ($L = \text{vis} \times \text{const}$). В результате помещения и лес заполнялись белесым свечением без ощущения направленных солнечных столбов.
- **Physical Defect**:
  1. Отсутствовала фазовая анизотропия рассеяния ($p(\theta)$): боковое рассеяние света воздухом имело ту же интенсивность, что и рассеяние прямо по направлению к источнику света.
  2. Отсутствовало физическое затухание по закону Бера-Ламберта ($T = \exp(-\sigma_t \cdot t)$).
  3. Отсутствовало разделение диагностических компонентов для аудита засветки.

#### 2. Physical Scattering Implementation
- Интегрирована модель однократного атмосферного рассеяния:
  $$S(t) = \text{vis}(\mathbf{x}(t)) \cdot \sigma_s \cdot p(\cos\theta, g) \cdot \mathbf{L}_{\text{sun}} \cdot \exp(-\sigma_t \cdot t)$$
  - $\sigma_s = 0.02\,\text{m}^{-1}$ (коэффициент рассеяния);
  - $\sigma_t = 0.03\,\text{m}^{-1}$ (коэффициент экстинкции);
  - $p(\cos\theta, g) = \frac{1}{4\pi} \frac{1 - g^2}{(1 + g^2 - 2g \cos\theta)^{3/2}}$ (фазовая функция Хеньи-Гринштейна с прямым пиком $g = 0.60$, дающая отношение яркости луча $\approx 25:1$ по сравнению с боковым воздухом).
- Добавлены 5 режимов покомпонентной диагностики (`GOD_RAY_COMPONENT_DEBUG`):
  - **Mode 14 (`RAW_VISIBILITY`)**: $\frac{\text{accumulatedLit}}{\text{rayLength}}$ (чистая геометрическая видимость CSM);
  - **Mode 15 (`SCATTERING_ONLY`)**: нефазовая плотность рассеяния;
  - **Mode 16 (`PHASE_FUNCTION_ONLY`)**: тепловая карта фазовой анизотропии $p(\theta, g)$;
  - **Mode 17 (`EXTINCTION_ONLY`)**: оптическое пропускание по глубине $T = \exp(-\sigma_t \cdot d)$;
  - **Mode 18 / Mode 13 (`FINAL_RADIANCE` / `HDR_PREVIEW`)**: итоговый физический аддитивный композит.
- Унифицирован размер буфера `GodRayVisibilityUniforms` до 320 байт (16-байтовое выравнивание Metal).

#### 3. Verification & Metrics
- Unit-тесты `godRayWorldSpaceUnitTest` и `sodiumConfigUnitTest` успешно пройдены (контракты `GR-0` through `GR-2.5 F`).
- Строго сохранена пространственная стабильность, инвариантность к перемещению/прыжку и привязка к мировым вокселям.

---

## 2026-08-22 — Goal 45: Nether WORLD_OPAQUE / L6 soft-shadow sprint

### Статус и строгий контракт

Работа ещё не завершена: цель `>=45 FPS` в production пока **не достигнута**.
Все сравнимые прогоны используют `nether-lava-stress-v1`, встроенный Retina
`3024x1964@120`, exclusive fullscreen, native HDR scene output, Advanced/Balanced,
Fancy `16/12`, MetalFX OFF, VSync OFF, frozen simulation. Перед прогонами закрывались
Minecraft/launchers, BetterDisplay/ElyPrism/Steam UI и Gradle daemons; thermal state
во всех приведённых валидных измерениях был `nominal`.

Detailed timing остаётся Tier B-атрибуцией. Production-решение требует отдельного
Tier C `2x baseline + 2x candidate` и live-проверки изображения/ощущения frame pacing.
Поэтому оставленные ниже изменения до такого A/B имеют статус **retained candidate**,
а не окончательно принятой оптимизации.

### Подтверждённая потеря времени кадра

Исходный повтор full-detail дал `17.288 FPS`, GPU p95 `62.314 ms`, lows
`16.177/16.017`. Диагностическое выключение planar capture подняло результат до
`21.897 FPS / 49.157 ms p95`, но это не production-вариант. Последующие валидные
ablation-прогоны после устранения submission overhead показали:

- production full с L6: около `23.5-26.1 FPS` до следующих shader-кандидатов;
- `NO_L6_VISIBILITY`: `53.693 FPS / 21.268 ms GPU p95`;
- nearest-only L6 после последних безопасных изменений: `49.777 FPS / 22.790 ms`,
  lows `44.136/40.687`;
- полный четырёхtapовый L6 перед all-visible fast path: `28.910 FPS / 37.952 ms`,
  lows `25.991/25.062`.

Stage timestamps устойчиво помещают основной выигрыш/регрессию в `WORLD_OPAQUE`.
Это доказывает конкретный дорогой участок — L6 soft-shadow receiver filter в terrain
fragment shader, особенно три дополнительных cache taps. Timestamp-профиль не
доказывает микроархитектурную причину (bandwidth/cache/register occupancy), поэтому
такие объяснения остаются гипотезами.

### Оставлено как полезные кандидаты

#### Indirect-command preupload для Sodium

Новый `SodiumIndexedIndirectBatcher` и узкие mixin/accessor hooks переносят upload
immutable indexed-indirect snapshot из hot render submission. В steady state topology
снизилась примерно с `100 render / 95 blit` до `14 / 9` encoders на кадр. Full-detail
изменился с `17.288 FPS / 62.314 ms p95` до `20.234 FPS / 52.499 ms`; planar-off
control дал `21.897 FPS / 49.157 ms`. Consume/retirement сохраняют in-flight ownership;
немедленного освобождения GPU resources нет.

#### L6 proxy broadphase, mask и sparse iteration

Оставлены три последовательных lossless изменения:

1. exact segment-AABB broadphase перед дорогим voxel proxy intersection;
2. CPU-built conservative proxy mask для каждого light descriptor с ABI-synced
   fixed proxy records/masks и сохранённым stable-entity exclusion;
3. sparse set-bit iteration вместо полного обхода заведомо отсутствующих proxy bits.

На nearest диагностике proxy-mask поднял результат до `37.485 FPS / 30.023 ms p95`,
а sparse iteration — до `38.206 FPS / 29.832 ms`. Полный soft path соответственно
дал `23.294` и `23.525 FPS`. Это cumulative Tier B evidence; снижение качества,
числа proxies или shadow coverage не применялось.

#### Cluster depth D10 — откат

Кандидат с `D10` дал положительный Tier B signal против D8, но после него live
Advanced Lighting перестал работать корректно. Поэтому D10 не считается принятой
оптимизацией и согласованно удалён из Java/Swift/native validation; текущий
проверенный baseline снова использует `D6`. Исторический измеренный результат не
является основанием оставлять регрессионный runtime-контракт.

#### Exact skip мёртвого planar pass

`PlanarReflectionRenderer.addFramePass` теперь не планирует reflection capture, если
terrain specialization строго `AMBIENT_ONLY` и debug visualization выключена. В этом
режиме отражение не имеет ни одного downstream consumer, поэтому это удаление
семантически мёртвой работы, а не выключение видимого эффекта. Debug и будущие
не-ambient профили сохраняют pass. Проверяется `PlanarReflectionMatrixTests`.

Результат после D10: `28.910 FPS`, GPU p50/p95/p99
`36.688/37.952/38.460 ms`, lows `25.991/25.062`; `WORLD_OPAQUE` около
`18.26/19.04 ms avg/p95`.

#### Exact all-visible layer-0 witness

В soft filter nearest tap вычисляется прежним полным helper. Только если его результат
точно `vec3(1)`, три дополнительные taps последовательно проверяют валидный layer 0.
Если все три имеют `+inf` либо receiver находится не дальше first hit с прежним
`0.002` coincidence tolerance, старое weighted expression вычисляется в прежнем
порядке с тремя `vec3(1)`. При любом miss вызывается буквально прежний fallback;
prefetch в него не переиспользуется, поэтому register lifetime не расширяется.

Warmed Tier B `1800+900`:

- `32.192 FPS`, min window `32.145`;
- GPU p50/p95/p99 `33.154/34.403/34.949 ms`;
- lows `27.528/26.367`;
- `WORLD_OPAQUE` около `15.01/15.73 ms avg/p95`;
- `READY=87`, `STALE=0`, `dirty bricks=0`, zero measured resource allocations.

Первый production Tier C `1800+3000` подтвердил устойчивость, но ещё не закрывает
обязательный `2x2` gate: `32.489 FPS`, min window `32.389`, GPU p95/p99
`35.493/36.091 ms`, lows `28.298/26.987`, zero timing drops/allocations. До цели
остаются примерно `12.5 FPS`, поэтому нельзя объявлять задачу завершённой.

#### Exact interior fast path L6 soft filter

После GPU-counter атрибуции добавлен отдельный ALU-кандидат только для внутренней
области cubemap face. При `faceEdgeDistanceTexels >= 1.5` старый `smoothstep`
гарантированно равен единице, `lowerTexel` лежит в `[1, edge-2]`, а все четыре taps
остаются на исходной face. Поэтому этот coherent path строит те же четыре прямых
taps и bilinear weights, но не выполняет seam resolve, четыре tap-ID, deterministic
diagonal и triangle math. Полоса `<1.5` выполняет старый код буквально.

Число visibility taps, выбор nearest при tie `.5`, порядок трёх extra reads,
all-visible witness, left-associated weighted accumulation и final finite/clamp
guards не менялись. Новый CPU oracle перебирает faces, edge `8/16/32/64`, четыре
nearest quadrants и значения `nextDown/.5/nextUp`; direct taps, nearest ID, coverage
и weights совпадают со старым resolve-path. Actual GLSL→SPIR-V compilation и
`metalRuntimeUnitTest` прошли; обновлены четыре source goldens.

Warmed detailed Tier B `1800+900`:
`20260822T084623Z-ge343bd0a5d9f-dirty-goal45-l6-interior-exact-warm-off`:

- `35.071 FPS`, min window `35.056` против retained `32.192/32.145`;
- GPU p50/p95/p99 `31.841/32.893/33.359 ms` против
  `33.154/34.403/34.949 ms`;
- lows `32.033/31.152` против `27.528/26.367`;
- `WORLD_OPAQUE` около `14.17/14.87 ms avg/p95` против `15.01/15.73 ms`;
- `READY=87`, `STALE=0`, `APPROXIMATE=1961`, zero measured allocations;
  `dirty_bricks_remaining=5`, поэтому receipt немного тяжелее идеального frozen
  reference, а не легче.

Это около `+8.9%` whole-frame и согласованное улучшение tails/stage, поэтому кандидат
оставлен. Он ещё не принят окончательно: это один instrumented Tier B run, не
production `2x2`, и до цели `45 FPS` остаётся почти `10 FPS`.

#### Per-face all-visible L6 census и отклонённый descriptor bypass

Перед изменением GPU path выполнен отдельный opt-in census полного resident payload,
а не оценка по `raysWithHits`. Строгий классификатор принимал face только для
`complete` page и только если все `edge² * 4` hits имели non-NaN non-negative
distance (`+inf` и `-0` допустимы) и точный `VISIBLE_PACKED_RGB`; incomplete,
`NaN`, отрицательные, `-inf` и любой не-white packed RGB отклонялись.

Диагностический run
`20260822T090228Z-ge343bd0a5d9f-dirty-goal45-l6-visible-face-census-off`
дал 116 upload events и 87 финальных unique residents. В финальной residency:

- 28/87 pages имели ровно одну полностью прозрачную face, остальные 59 — ни одной;
- это 28/522 faces (`5.36%`), все 28 были edge `64`, face mask `0x04` (`+Y`);
- ранние заменяемые pages давали 60 прозрачных faces в 49/116 events, но transient
  статистика не использовалась как production coverage.

После GO-stop-gate был проверен точный production bypass. Шесть face bits временно
упаковывались в старшие биты descriptor state и переносились вместе с конкретной
READY/STALE resident page; dynamic/fallback states всегда имели нулевую маску.
Маска вычислялась на builder worker, не на render thread. Shader декодировал state,
а white bypass срабатывал только после прежних finite/edge/atlas-range/faceUv guards
и только при `faceEdgeDistanceTexels >= 1.5`. Это важная seam-граница: при меньшем
значении soft taps могут перейти на соседнюю cubemap face, поэтому там выполнялся
старый resolver и все visibility reads буквально. Java descriptor contracts,
classifier tests, diagnostic patch contract и actual GLSL -> SPIR-V прошли.

Warmed detailed Tier B `1800+900`
`20260822T091316Z-ge343bd0a5d9f-dirty-goal45-l6-visible-face-bypass-warm-off`
не прошёл whole-frame gate против retained interior-fast-path run:

- FPS `35.071 -> 34.518`, min window `35.056 -> 34.480`;
- GPU p50/p95/p99 `31.841/32.893/33.359 -> 32.359/33.576/34.077 ms`;
- lows `32.033/31.152 -> 29.940/27.114`;
- `world opaque` локально улучшился примерно
  `14.168/14.759 -> 13.588/14.059 ms` avg/p95, но whole presenting command buffer
  и tails стали хуже; thermal state оставался nominal, READY/STALE были те же
  `87/0`, timing drops и measured allocations — zero.

Локального stage win недостаточно: дополнительный raw-state decode, branch и
`out bool` изменили codegen общего fragment helper и дали итоговую регрессию,
вероятно в другом terrain pass. Production bypass, descriptor packing, census env,
classifier и связанные goldens/tests полностью удалены; retained interior fast path
снова проходит исходные contracts. **DO NOT RETRY** per-face descriptor bypass в
этой форме без нового receiver-direction evidence и способа не менять codegen
непокрытых fragment paths.

### Отклонено и полностью удалено — DO NOT RETRY без новых данных

| Кандидат | Результат | Решение |
|---|---|---|
| Global cluster tile `32x32` | `19.657 FPS / 55.461 ms p95` | Регрессия; reverted. |
| Opaque static first-layer specialization | `23.169 FPS / 46.835 ms` | Не быстрее полного path; reverted. |
| Conservative first-hit min-mip | `24.276 FPS / 45.001 ms`, WORLD около `22.16/22.73 ms` | Регрессия; reverted. |
| MSL `noinline` для soft filter | `24.705 FPS / 44.034 ms`, WORLD около `21.24/21.90 ms` | Регрессия; reverted. |
| Cluster depth `D12` | `25.156 FPS / 44.591 ms`, cluster build хуже | Возврат к D10. |
| Quad ballot + четыре address broadcast на cache load | `26.889 FPS / 40.822 ms`, lows `23.805/23.333` | WORLD стал быстрее, но полный кадр и lows хуже; reverted. |
| Сокращённый quad clustered min/max | `25.388 FPS / 43.039 ms`, lows `22.189/21.780` | Регрессия; reverted. |
| Terminal-opaque first-hit texture/gather architecture | Только `6/87` steady READY pages (`6.9%`) прошли строгий terminal gate | Не окупает ресурс/ABI; telemetry и код удалены. |
| Перенос layer-0 load перед direction/plane внутри каждого tap вместо group witness | `27.508 FPS / 39.762 ms`, lows `24.526/23.656`, WORLD около `20.23/21.02 ms` против `32.192 / 34.403` и `15.01/15.73` | Математически exact, но shader execution существенно хуже; полностью reverted. |
| Exact Sodium depth prepass | warmed `32.211 FPS / 35.819 ms p95`, WORLD около `14.70/15.06 ms`; activation-proof `32.334 FPS / 34.193 ms`, WORLD около `14.48/14.86 ms` | Сэкономил менее `0.75 ms` WORLD p95 и не дал `5%` whole-frame; дополнительный pass полностью удалён. |
| Отдельный exact 2x2/layer-0 proof atlas | Не запускался: минимум `+10.4–12.1%` к page payload, около `8–9 MiB` в Balanced либо потеря residency при полном 64 MiB atlas | Hard reject до кода: повтор min-mip/texture family, ABI/resource expansion и риск видимых approximate shadows. |
| Lazy RGB decode после early-visible guards | `31.772 FPS`, GPU p95 `34.875 ms`, lows `28.316/27.098`, WORLD `15.282/15.992 ms` против retained `32.192 / 34.403` и `15.01/15.73` | Exact source reorder не дал выигрыша; Metal compiler, вероятно, уже делает code motion. Полностью reverted. |
| Per-face all-visible descriptor bypass | `34.518 FPS / 33.576 ms GPU p95`, lows `29.940/27.114`; локальный `world opaque` win не пережил whole-frame gate | Полностью removed; не повторять shared-helper `out bool`/state-branch вариант. |
| Удаление недостижимой finite/length guard для L6 cube direction | Adjacent screening: `35.893` против control `35.712 FPS`, но WORLD `14.025` против `13.653 ms` avg; GPU-разница совпала с дрейфом cluster/clipmap workload | Нет причинного WORLD-выигрыша; guard, tests и goldens восстановлены. |
| Global shaderc `optimization_level_performance` для Metal GLSL corpus | Фальшивые `183.056 FPS / 4.701 ms GPU p95`: Advanced отключён, clustered/voxel telemetry inactive | Performance DCE удалил один external sampler (`4` вместо `5`), сработал fail-closed Vanilla fallback. Код и тест полностью removed. |

Layer0-first прогон имел `READY=87/STALE=0` и стабильную регрессию во всех трёх
окнах; его `dirty_bricks_remaining=6` делает workload receipt несовершенным, но
`WORLD_OPAQUE` ухудшился примерно на `5.2 ms` и был стабилен около `20.2 ms` во всех
окнах. Этого достаточно для hard reject, но не для каких-либо claims о размере
регрессии в идеально равном A/B.

Depth-prepass эксперимент использовал тот же Sodium vertex path, ту же RGSS/nearest
alpha-проверку, `colorWriteMask=none`, исходный depth compare и повторное использование
immutable indirect snapshot. Runtime log отдельно подтвердил, что экспериментальный
pass был активен. Несмотря на небольшой сдвиг самой terrain-stage, целый кадр остался
на уровне шума, а warmed GPU p95 ухудшился; Java/Swift/Metal hooks и тесты кандидата
полностью удалены, исходный одноразовый prepared indirect path восстановлен.

Для 2x2 proof-atlas единственным точным payload был бы conservative minimum первого
валидного layer-0 hit для каждого внутреннего footprint с fallback на текущий SSBO
path на seams/invalid/NaN. Текущие page allocations не имеют свободного места, а
Balanced atlas уже capacity-bound (`READY` порядка `87`, `APPROXIMATE` порядка
`1961`). Поэтому вариант либо вытесняет качественные resident shadows, либо добавляет
новый ресурс, offset/retirement и ABI; это противоречит текущему quality/future gate.

Lazy-RGB прогон был nominal thermal и COMPLETE, но workload receipt оказался даже
слегка легче retained reference (`READY=85, STALE=1`, `dirty_bricks_remaining=9`).
Даже в этих условиях не появилось ни whole-frame, ни WORLD_OPAQUE улучшения. Артефакт:
`20260822T080256Z-ge343bd0a5d9f-dirty-lazy-rgb-decode-warm-off` (`1800+900`, detailed).

Для direction-guard кандидата исходная guard действительно была избыточна для
всех допустимых face/texel/edge: cube direction конечно и имеет length-squared
в `[1,3)`. Но измерение не показало экономии именно в дорогом terrain fragment
path. В кандидате clipmap остался недостроенным (`dirty=7`), cluster build был
`1.344/2.066 ms avg/p95`; в контроле clipmap достроился (`dirty=0`), а cluster build
стал `1.932/2.776 ms`. При этом сам WORLD был быстрее в исходном control. Артефакты:
`20260822T092619Z-ge343bd0a5d9f-dirty-goal45-l6-direction-guard-screen-off` и
`20260822T092844Z-ge343bd0a5d9f-dirty-goal45-l6-direction-guard-control-off`.

Global shaderc experiment повторил Mojang front-end settings (Vulkan 1.2,
auto-bind/locations, debug info и `gl_VertexID/gl_InstanceID` aliases) и менял только
optimization level `zero -> performance`. Synthetic interface test прошёл, но actual
runtime preflight сразу отверг `minecraft:pipeline/armor_cutout_no_cull`:
`Advanced fragment must expose exactly five external L4/cloud/planar shadow samplers
(removed 4, expected 5)`. После этого renderer честно перешёл на
`lighting=VANILLA`; clustered lighting и voxel clipmaps были inactive. Поэтому
числа прогона не являются performance evidence и не приближают цель.
Артефакт: `20260822T093803Z-ge343bd0a5d9f-dirty-goal45-shaderc-performance-screen-off`.
Не повторять global optimization без dual-profile semantic/resource/MSL parity harness;
даже с таким harness Apple Metal compiler уже оптимизирует финальный MSL,
поэтому нужен отдельный emitted-MSL/counter мотив до нового кода.

Также не повторялись и не комбинировались старые отклонённые families из этого
журнала: prepared-context/common-arguments, FP16 soft weights, `uvec4` mechanical
packing и row-major texture variants. Fused four-tap proposal отклонён до кода как
повтор этих уже измеренных идей без сокращения 16 potential reads.

### Metal System Trace: второй крупный fragment bottleneck вне WORLD_OPAQUE

После отката всех невыигравших кандидатов снят короткий production-structure trace,
привязанный к JVM только после начала measured-фазы:
`20260822T0814Z-nether-production-structure.trace`. Запуск был без detailed markers,
Metal Validation и debug labels; полный companion report завершился `COMPLETE` без
`FAIL` и dropped timing events:
`20260822T081248Z-ge343bd0a5d9f-dirty-xctrace-production-structure-off`.
Его `32.516 FPS / 35.192 ms GPU p95` нельзя использовать как acceptance evidence:
5-секундная trace-запись намеренно вмешивалась в measured window, что также ухудшило
lows до `27.148/25.093` и добавило диагностические resource allocations.

Структурный результат trace:

- fragment channel: около `5228.7 ms` суммарных active intervals за окно;
- compute: около `393.0 ms`; vertex: около `120.0 ms`;
- основной terrain-like encoder (`Render Command 4/3`) имеет fragment interval
  около `13.8 ms` на кадр;
- второй terrain-like encoder (`Render Command 8/7`) имеет ещё около `11.7 ms` на
  кадр; по строгому порядку vanilla `LevelRenderer.addMainPass` он следует после
  translucent features и соответствует `ChunkSectionLayerGroup.TRANSLUCENT`;
- остальные render encoders в этом участке находятся примерно в диапазоне
  `0.4–1.0 ms`.

Следовательно, кадр не является только `WORLD_OPAQUE`-bound: большая lava/translucent
terrain fragment-stage раньше не отображалась в основной WORLD сводке и сопоставима
с opaque terrain по стоимости. Первый trace не содержит shader-profiler samples и GPU
counter values, поэтому не доказывает occupancy/cache/bandwidth причину и сам по себе
не разрешает менять качество translucent shadows, blending, sorting или L8 optics.
Production pass/attachment аудит также не нашёл лишнего clear/store, reactive MRT,
duplicate draw или безопасно удаляемого attachment; диагностическое дробление
encoder'ов существует только при detailed timing и не является production-кандидатом.

### Metal GPU Counters: opaque и translucent terrain ALU-bound

После структурной записи отдельно снят screening trace с инструментом
`Metal GPU Counters`:
`20260822T0830Z-nether-gpu-counters.trace`. Его production companion
`20260822T082837Z-ge343bd0a5d9f-dirty-xctrace-gpu-counters-off` завершился
`COMPLETE`: `32.544 FPS`, GPU p95 `35.460 ms`, lows `28.674/26.911`, zero
measured allocations. Эти числа не являются acceptance evidence: инструмент был
подключён внутри measured-фазы, а JVM завершилась примерно через `1.55 s` после
начала capture вместо запрошенных пяти секунд. Запись годится только для
микроархитектурной атрибуции.

В fragment-active samples (`133211` из `154962`, около `86%`) агрегаты составили:

- ALU Limiter: mean `52.47%`, p50 `48.65%`, p95 `94.18%`;
- ALU utilization: mean `44.75%`;
- Texture Sample Limiter: mean `7.92%`;
- Texture Cache Limiter: mean `4.12%`;
- Buffer Read Limiter: mean `2.48%`;
- GPU LLC Limiter: mean `8.57%`;
- GPU Read Bandwidth: mean `6.76%`.

Корреляция global counter samples с encoder intervals показывает ту же картину для
обоих дорогих terrain passes. У opaque-like `Render Command 4/3` ALU Limiter mean
около `47-48%`, Texture Sample около `3.6%`, Buffer Read около `3.5%`, LLC около
`3.1-3.3%`. У translucent-like `Render Command 8/7` ALU Limiter ещё выше — около
`54-55%`, тогда как Texture Sample около `3.1-3.5%`, Buffer Read около `1.1%`, LLC
около `6.5%`. Названия pass выводятся из порядка encoders, а counters глобальны,
поэтому это сильная относительная атрибуция, но не shader-level sample proof.

Практический вывод: следующий кандидат должен сокращать точную ALU-работу L6 soft
filter одновременно в opaque и translucent terrain shader. Повторять resource
packing, row-major/texture atlas, bandwidth или cache-only варианты без новых данных
нельзя: counters прямо показывают, что они атакуют вторичный limiter. Малые
post-process passes могут быть bandwidth-heavy, но занимают лишь доли миллисекунды и
не способны закрыть разрыв до `45 FPS`.

### Незакрытая граница доказательства

Static tests и golden shader hashes доказывают ABI/source/output contracts, но не
FPS и не приятность игры. Перед окончательным принятием retained set обязательны:

1. same-code Tier C `2x2` A/B без detailed markers;
2. визуальная проверка Nether и другой сложной сцены с множеством источников;
3. проверка движения камеры, coloured/translucent shadows, seams, held/dynamic light,
   chunk streaming и frame pacing;
4. полный `clean check` и возврат временных benchmark/display настроек.

### Диагностика exact +inf-footprint proof (telemetry удалена)

Перед production-кодом отдельно измерен строгий верхний предел покрытия компактного
bitset, который мог бы доказывать, что все четыре layer-0 texel выбранного interior
`2x2` footprint имеют distance `+inf`. Диагностический run
`20260822T094449Z-ge343bd0a5d9f-dirty-goal45-l6-infinite-footprint-census-off`
завершился `COMPLETE`: `35.807 FPS`, GPU p50/p95/p99
`31.225/32.452/33.152 ms`, lows `30.728/28.939`, zero measured allocations.

Для последних версий 92 unique pages строгая доля составила `249713/2059488`
interior footprints (`12.13%`). У edge `64` — `249426/2052696` (`12.15%`),
причём face `+Y` имела `42.31%`, а остальные face примерно `5.6-6.2%`.
Это только page-space census: он не измеряет экранную частоту обращений и сам по
себе не доказывает FPS. Временные record/helper, env
`METALLUM_L6_INFINITE_FOOTPRINT_CENSUS` и upload logging полностью удалены до
production-кандидата.

#### Compact one-bit +inf proof tail — отклонён и полностью удалён

После census отдельно проверен exact production-вариант: один bit на visibility
texel (`staticAtlasBytes / 256`, для Balanced ровно `256 KiB`). Bit выставлялся на
builder worker только для complete page и только если все четыре layer-0 записи
interior `2x2` имели valid marker и точный `+inf`. При hit shader возвращал то же
left-associated all-visible выражение; при miss, seam или dynamic page буквально
сохранялся прежний трёхtapовый witness/fallback. Visibility payload, четыре слоя,
soft weights и shadow result не менялись.

Из-за исчерпанных fragment buffer slots proof временно размещался хвостом того же
private visibility buffer. Java packet ABI v6 передавал точную static boundary,
debug flag занимал старший bit, а Swift требовал exact buffer length
`static + dynamic + static/256`; CPU oracle, actual GLSL -> SPIR-V -> MSL и runtime
contracts прошли. Несмотря на малый объём metadata, screening
`20260822T100502Z-ge343bd0a5d9f-dirty-goal45-l6-infinite-proof-screen-off`
устойчиво проиграл соседнему census/control
`20260822T094449Z-ge343bd0a5d9f-dirty-goal45-l6-infinite-footprint-census-off`:

- FPS `35.807 -> 32.243` (`-9.96%`), min window `32.239`;
- GPU p50/p95/p99 `31.225/32.452/33.152 -> 33.112/34.308/34.874 ms`;
- lows `30.728/28.939 -> 28.846/27.536`;
- cluster build дрейфовал в более лёгкую сторону (`1.904/2.757 -> 1.327/2.142 ms`),
  поэтому он не объясняет ухудшение кадра;
- `READY/STALE` совпали (`87/0`), Advanced shaders были active, timing drops и
  measured allocations — zero.

Кроме performance-регрессии Java exact layout guard обнаружил расширенный atlas и
fail-closed отключил optional dynamic L6 backend (`bound to a different atlas
layout`). В статичной benchmark-сцене dynamic candidates были нулевыми, поэтому это
не объясняет измеренный проигрыш, но само по себе является недопустимой
функциональной регрессией. Proof tail, ABI v6, Swift guard, builder payload и все
tests/goldens полностью удалены; ABI v5 и исходный dynamic atlas contract
восстановлены. **DO NOT RETRY** metadata proof (tail, отдельный buffer/texture либо
старый 4-byte atlas) без принципиально новых per-fragment coverage и codegen данных:
даже `12.13%` page-space coverage не окупило обязательную проверку на каждом
interior nearest-white fragment.

#### Exact trusted-address microkernel — отклонён и полностью удалён

Следующий узкий кандидат затрагивал только уже доказанную interior-ветку L6 soft
filter и не менял ни одного результата. После miss существующего all-visible
witness он один раз строил точные row-major адреса `hit00/hit10/hit01/hit11`, а
три fallback taps вызывали копию старого cached-texel helper без повторных
face/texel bounds checks и адресной арифметики. Четыре ordered cache layers,
direction/receiver-plane math, RGB decode, early returns, веса, seam fallback,
ресурсы и ABI оставались прежними. Exhaustive CPU oracle проверял все допустимые
edge `8/16/32/64`, faces, interior cells и границы bilinear quadrant; actual
GLSL -> SPIR-V -> MSL, runtime и golden contracts прошли. Независимый Terra-аудит
также подтвердил семантическую эквивалентность и указал только ожидаемый codegen/
register-pressure риск.

Comparable screening
`20260822T101621Z-ge343bd0a5d9f-dirty-goal45-l6-trusted-address-screen-off`
этот риск реализовал и проиграл соседнему control/census
`20260822T094449Z-ge343bd0a5d9f-dirty-goal45-l6-infinite-footprint-census-off`:

- FPS `35.807 -> 32.577` (`-9.02%`), min window `32.577`;
- GPU p50/p95/p99 `31.225/32.452/33.152 -> 32.748/34.064/34.480 ms`, worst
  `35.117 ms`;
- lows `30.728/28.939 -> 29.847/28.868`;
- cluster build снова был существенно легче (`1.904/2.757 -> 1.348/2.144 ms`
  avg/p95), поэтому не объясняет ухудшение whole frame;
- `READY/STALE` совпали (`87/0`), Advanced shaders были active, run завершился
  `COMPLETE`, timing drops и measured allocations — zero;
- исходный atlas/ABI был сохранён: dynamic L6 не получил layout failure и реально
  выполнил один ранний dispatch до стабилизации статичной сцены.

Helper, точные адреса, повторная quadrant selection и тестовые goldens полностью
удалены; retained interior/seam filter и исходный dynamic backend восстановлены,
targeted CPU/shader/runtime tests снова проходят. **DO NOT RETRY** duplicated
trusted-address helper. Также не переходить к более широкой face-frame/shared-ray
форме без принципиально нового emitted-MSL или occupancy proof: она увеличивает
живой ALU state и code size в том же дорогом fallback-домене, где меньший кандидат
уже ухудшил GPU p95 на `1.612 ms`.

#### Conservative 32x32 microtile mask в compact indices — отклонён и удалён

Отдельно проверен новый cluster/fragment-кандидат, не повторяющий global tile `32`
и post-L6 shadow tagging. Сетка headers/scratch/prefix оставалась прежней
`64x64 x D10`; low 12 bits каждого `uint16` compact index сохраняли исходный
light index и его порядок, а свободный high nibble передавал conservative overlap
mask четырёх `32x32` microtiles. Prepare квантизовал уже существующий projected
view-space light AABB в half-tile ranges; старые macro bounds восстанавливались
точно как `lower >> 1` и `(upper + 1) >> 1`. Bounds расширялись наружу на один
pixel внутри прежнего macro range, invalid/near-camera случай оставался full-screen,
а нулевая маска превращалась в `0xF` fail-open. Fragment direct и dominant-specular
loops проверяли bit до чтения light record, range/normal и L6.

Новых buffers, passes, encoders, allocations либо перестановки/cap lights не было.
Совместимый старый batch flag включал интерпретацию; raw high-zero index оставался
legacy/fail-open для mixed old/new code. Native CPU/GPU oracle проверял неизменные
headers/stats/order, P/B/U caps, Retina partial tile, SDR/HDR, empty/overflow/OOB,
`#4095` и точное соответствие packed mask подготовленным bounds. Metal API + GPU
Validation, actual GLSL -> SPIR-V -> MSL и runtime contracts прошли.

Screening
`20260822T104001Z-ge343bd0a5d9f-dirty-goal45-cluster-microtile-screen-off`
решительно проиграл тому же retained control/census
`20260822T094449Z-ge343bd0a5d9f-dirty-goal45-l6-infinite-footprint-census-off`:

- FPS `35.807 -> 30.278` (`-15.44%`), min window `30.248`;
- GPU p50/p95/p99 `31.225/32.452/33.152 -> 35.139/36.263/36.800 ms`, worst
  `37.284 ms`;
- lows `30.728/28.939 -> 26.626/25.661`;
- cluster build был даже легче (`1.904/2.757 -> 1.613/2.452 ms` avg/p95), поэтому
  не объясняет fragment/whole-frame проигрыш;
- macro workload остался в том же классе: `2048` lights, `14880` clusters,
  occupancy `32/80/256`, `499723` accepted indices, `68` overflow clusters;
- `READY/STALE=87/0`, Advanced active, `COMPLETE`, zero timing drops и measured
  resource allocations; clipmap ещё имел `dirty=6`, но это не может объяснить
  стабильный GPU-регресс порядка `3.8 ms`.

Production flag, metadata interpretation, packed indices, fragment decode, native
binding и tests/goldens полностью удалены; прежние raw indices и contracts снова
проходят. **DO NOT RETRY** per-index microtile/quadrant sideband в текущем forward
shader: даже точное culling metadata расширяет hot loop/codegen и ухудшает оба
дорогих terrain passes сильнее, чем экономит skipped lights. Возвращаться можно
только с прямым shader/ISA proof, позволяющим применить cull без per-candidate
decode/branch в fragment path; более мелкие bins либо новые sideband resources сами
по себе таким proof не являются.

#### Direction-free all-black witness — отклонён до реализации

Рассмотрен симметричный all-visible witness exact-кандидат: если nearest tap уже
вернул точный `vec3(0)`, для трёх соседних taps предполагалось читать только layer 0
и доказывать чёрный результат через общую нижнюю границу receiver plane
`abs(dot(normal, lightToReceiver)) * inversesqrt(dot(normal, normal))`. В вещественной
арифметике идея выглядит корректно: для единичного cache direction абсолютный
denominator не превосходит длину normal. При успехе можно было бы не повторять
direction/plane math и остальные cache layers для трёх taps.

Независимый Terra-аудит нашёл точный float-контрпример до внесения production-кода.
Текущий helper нормализует cubemap direction умножением на `inversesqrt`, но
GLSL -> SPIR-V -> MSL contract не гарантирует, что полученный float-вектор имеет
длину не больше единицы. Для `edge=8`, face `+X`, texel `(2,0)`, raw direction
`(1, 0.875, 0.375)` после float normalization имеет `dot(d,d)=1.0000001`. При
`normal=d`, подходящем конечном `lightToReceiver`, black marker
`0xff000000`, `hit=0.998` и epsilon `0.002` старый per-tap plane distance округляется
до `1.0` и возвращает прежнюю белую visibility по условию `hit+epsilon >= plane`,
тогда как proposed direction-free bound равен `1.0000001` и ошибочно доказывает
чёрный результат по `hit+epsilon < bound`.

Следовательно, кандидат не является bit-exact и мог бы создавать редкие ложные
тени. Эвристический safety multiplier тоже не даёт platform-independent proof и
противоречит quality-preserving gate. Код, ABI и shaders не менялись; benchmark не
запускался, потому что correctness gate уже провален. **DO NOT RETRY** общую
direction-free plane bound без формально специфицированной directed-rounding схемы
либо без вычисления точного старого per-tap direction/plane expression. Последний
вариант не является новым shortcut: он повторяет почти всю работу исходного helper
до его существующего layer-0 black return.

#### Fusion diffuse L3/L6 и dominant local GGX — отклонён по coverage gate

Проверена более широкая идея убрать второй cluster traversal у terrain material
path: текущий `metallumEvaluateClusteredMaterialSpecularV1` отдельно выбирает
dominant light и повторно запрашивает его proxy/L6 visibility, после чего обычный
`metallumEvaluateClusteredDirectV1` снова обходит тот же cluster. Теоретически
terrain-only helper мог бы сохранить literal порядок diffuse accumulation, прежний
strict `score > dominantScore` tie-break и использовать уже вычисленную visibility
dominant light для одного GGX.

Для целевой сцены кандидат провалил coverage gate до кода. Route фиксирует clear
weather и frozen simulation, поэтому rain-wet material path недостижим. Основной
translucent workload — lava; её material packet имеет ненулевой emission code, а
L8 special-surface gate намеренно требует `emissionCode == 0`. Следовательно,
дорогие lava fragments вообще не вызывают dominant local GGX. Возможные редкие
water/glass/metal pixels не могут закрыть разрыв примерно `10 ms` GPU p95 до цели.

Кроме отсутствия целевого покрытия fusion удерживал бы diffuse sum и dominant
state через material/environment работу, расширяя register lifetime, и обязан был
бы развести разные semantics: diffuse L6 fail-open/debug contribution против
specular fail-closed, partial receiver, proxy и descriptor state. Это пересекается
с уже отклонённым 2026-07-29 material-gated duplicated-lighting вариантом, который
ухудшил FPS примерно на 6%, после чего был закреплён единый literal L3-L6 common
path. Код и shaders не менялись, benchmark не запускался. **DO NOT RETRY** fusion
для Nether без прямой telemetry значимого L8-material pixel coverage и emitted-MSL/
register evidence; для отдельной material-heavy сцены это может быть самостоятельной
будущей гипотезой, но не решением dense-emissive L6 bottleneck.

#### Repack 32-byte L6 texel с normalization metadata — отклонён до кода

Проверена возможность использовать четыре кажущихся избыточными `0xff` marker bytes
в четырёх `{float distance, uint packedRgb}` records одного texel. При неизменных
`32 bytes/texel` можно было бы уплотнить четыре RGB24 в три words, а освободившийся
word занять normalization scalar и убрать часть direction ALU у каждого tap.

Exact-аудит показал три блокера. Во-первых, старый marker является независимым
fail-closed contract для каждого из четырёх layers: malformed marker при конечной
неотрицательной distance обязан вернуть black. Один NaN/negative sentinel или общий
texel bit не представляет те же четыре независимые invalid states; четыре validity
bits вместе с полным IEEE scalar уже не помещаются без потери информации либо роста
payload. Во-вторых, RGB1/RGB2 после плотной упаковки пересекают границы words и
добавляют shifts/OR и live state в каждый hot layer; atlas traffic/residency остаются
теми же, хотя GPU counters указывают ALU, а не buffer bandwidth как основной limiter.

В-третьих, stored normalization не имеет bit-exact producer/consumer parity. Static
builder использует CPU/double sqrt, dynamic builder — MSL `normalize`, а fragment
consumer — GLSL `inversesqrt(dot(...))`; независимое округление на tangent-plane
границе может изменить white/black early return. Сохранение raw length убирает лишь
малый `dot`, но оставляет `inversesqrt` и добавляет repack decode. Код, ABI и benchmark
не менялись. **DO NOT RETRY** marker-byte repack или normalization metadata без
полного сохранения per-layer malformed-state contract, bit-identical GPU generation
и emitted-code proof; в текущем 32-byte payload это не даёт several-ms потенциала.

#### Contiguous 32x32 sublists внутри 64x64 macro cluster — отклонены до кода

После проигрыша per-index microtile sideband отдельно проверен архитектурно иной
вариант: build мог бы сформировать четыре contiguous quadrant sublists для каждого
текущего 64x64xD10 macro cluster, а fragment shader выбирал бы один header/list один
раз до light loop. В hot loop не было бы ни mask decode, ни новой ветки на каждый
light, поэтому это не повторяет конкретную причину microtile shader-регресса.

Exact-аудит, однако, показал, что по ресурсам это фактически возврат к global 32x32
grid. При native 3024x1964 текущие `48*31*10 = 14880` macro clusters превращаются в
`59520` quadrant sublists — на `68%` больше уже отклонённого global-32x32 D6
(`35340`). Прямое четырёхкратное membership хранение потребовало бы
`30474240 bytes` вместо `7618560 bytes` (`+22.86 MiB`), headers ещё около
`357120 bytes`. Беспропускной worst-case list достигает `59520*256 = 15237120`
indices, тогда как Balanced layout имеет fixed cap `4M`, а native ABI допускает
максимум `8M`.

Применять cap независимо после quadrant cull нельзя: так sublist может вернуть
light, который прежний macro cap 256 уже отбросил, и изменить изображение. Для
точной семантики нужно сначала сохранить первые 256 macro light IDs в прежнем
ascending порядке, затем строить quadrant lists только из этого промежуточного
списка. Это означает двухступенчатый build, дополнительные headers/passes и
обязательный fallback на literal macro list при переполнении. Historical raw
global-32x32 уже запросил `1351005` indices и cluster-build p95 `4.012 ms` при D6;
retained macro-D10 использует около `500717` и p95 `2.757 ms`. Доказательства
потенциала вернуть необходимые 5–6 ms при таком росте compute/memory нет.

Production code, ABI и benchmark не менялись. **DO NOT RETRY** прямые contiguous
quadrant sublists без предварительного census cardinality после исходного macro-cap,
доли header-level fallback и worst-case capacity. Возвращаться можно только если
этот census одновременно докажет exact overflow behavior и достаточное сокращение
L6 вызовов; реализация затем всё равно обязана сохранить literal macro-list fallback
и пройти текущие whole-frame/lows/visual gates.

#### Fused four-tap all-visible prefilter — проверен и полностью удалён

Проверен output-exact вариант существующего layer-0 all-visible witness. Soft helper
сначала разрешал прежние четыре taps/weights, после существующего seam nearest-ID
fail-closed gate делал nested layer-0 proof для nearest и трёх extras, и при четырёх
white proofs возвращал прежнюю left-associated сумму четырёх `vec3(1)` с теми же
finite/clamp. На любом proof miss выполнялся literal старый nearest helper, затем
неизменённый retained three-extra witness и полный fallback. Invalid marker,
NaN/negative/infinite distances, signed zero, epsilon equality, face seams и `.5`
ties были проверены source/numeric contracts; actual GLSL -> SPIR-V -> MSL,
L6_NEAREST_ONLY diagnostic, Metal API/GPU Validation и runtime trio прошли.

Первый screening
`20260822T111537Z-ge343bd0a5d9f-dirty-goal45-l6-prefilter-all-visible-screen-off`
дал отрицательный сигнал (`33.791 FPS`, GPU p95 `33.629 ms`), но не был принят как
сопоставимое доказательство: к measure сохранились `READY/STALE=85/1` вместо
контрольных `87/0`. Поэтому выполнен ровно один повтор того же `600 warmup + 300
measure` после чистого process preflight.

Повтор
`20260822T111804Z-ge343bd0a5d9f-dirty-goal45-l6-prefilter-all-visible-screen-repeat-off`
был полностью сопоставим: native 3024x1964 HDR, Advanced/Balanced, MetalFX OFF,
VSync OFF, `READY/STALE=87/0`, 2048 lights, `COMPLETE`, zero dropped timings,
zero measured resource allocations. Относительно retained control
`20260822T094449Z-ge343bd0a5d9f-dirty-goal45-l6-infinite-footprint-census-off`:

- FPS `35.807 -> 33.785` (`-5.65%`);
- presenting GPU p50/p95/p99 `31.225/32.452/33.152 ->
  32.965/34.106/34.581 ms`;
- WORLD OPAQUE avg/p95 `13.591/13.983 -> 14.894/15.643 ms`;
- 1% low `30.728 -> 29.611`; 0.1% low `28.939 -> 29.371` не компенсирует
  проигрыш whole-frame и 1% low;
- cluster build практически совпал (`1.905/2.757 -> 1.889/2.792 ms` avg/p95),
  поэтому регресс локализован в terrain fragment/codegen, а не в culling compute.

Кандидат, его новые contracts и golden hashes полностью удалены; retained
nearest-first helper/witness восстановлен. **DO NOT RETRY** fusion, который держит
разрешённые taps/weights live через возможный full nearest fallback: skipped nearest
direction/plane work на all-visible footprints меньше, чем register pressure,
повторные layer-0 reads и более тяжёлый control flow в обоих terrain passes.
Возвращаться к four-tap prefilter можно только с emitted-ISA proof, устраняющим эту
live-state цену без изменения старого fallback и без per-tap proof на miss path.

#### Goal45: разрешён контролируемый quality/performance режим

После исчерпания очередной серии output-exact кандидатов пользователь отдельно
разрешил рассматривать **небольшую, визуально малозаметную** потерю качества ради
заметного FPS win. Это не отменяет safety/pleasantness gates: запрещены заметная
пикселизация или пропадание важных теней, spatial/temporal shimmer, нестабильность
при движении, ухудшение frame pacing/1% lows и скрытое уменьшение light radius/count,
HDR либо других несвязанных эффектов.

Каждый approximate кандидат обязан быть независимо обратимым: отдельный явный
runtime/config policy с сохранённым исходным full-quality path, узкий diff и отдельная
запись A/B. Производственный default можно менять только после Tier B win, live
визуальной проверки в Nether и другой dense-light сцене, затем Tier C 2x2. Уже
измеренный `L6_NEAREST_ONLY` (`49.777 FPS`, GPU p95 `22.790 ms`) используется только
как верхняя performance-граница: сам по себе он не принят, потому что полностью
убирает 2D shadow filtering и может вернуть заметные texel silhouettes.

#### Повторный exact-аудит receiver-plane hoist — NO-GO без кода

Идея вычислять `dot(receiverWorldNormal, receiverWorldNormal)` и
`dot(receiverWorldNormal, lightToReceiver)` один раз для nearest+soft taps оказалась
прямым расширением уже отклонённого `Fragment atlas candidate 2`. Там те же scalars
и `cacheFaceEdgeFloat` были вынесены для трёх extras: GPU p95 улучшился только на
`0.189 ms`, ниже gate, при одновременном ухудшении обоих lows. Добавление nearest
экономит лишь ещё один normal dot и максимум один условный numerator dot, но держит
два scalars live через nearest и soft calls. Нового several-ms основания нет.
Production code/tests/benchmark не менялись. **DO NOT RETRY** без emitted-MSL proof,
что compiler не делает CSE сам и что register occupancy не ухудшается.

#### Полный RG32Uint texture/gather atlas — NO-GO до реализации

Проверена не metadata-надстройка, а полная bit-exact замена 4-layer L6 SSBO atlas на
integer texture layout. На M1 Pro `RG32Uint` не является filterable format, поэтому
нужный hardware 2x2 `textureGather` для raw distance/RGB bits недоступен; integer
`read()` оставляет те же per-layer/per-tap reads. Float/UNORM варианты теряют NaN,
marker и payload bit identity либо добавляют reconstruction ALU.

Кроме того, fixed-size texture-array slices раздувают edge 8/16/32 pages в
64x64 storage, а четыре edge-specific pools либо tiled atlas требуют новых fragment
resources, descriptor ABI, allocators и отдельного dynamic-L6 texture write/hazard
пути вместо текущего общего 64 MiB buffer+suffix. Прямого no-copy/no-residency-loss
перехода нет. GPU counters также показывают buffer read secondary (`2.48% mean`)
против fragment ALU limiter порядка `47-55%`. Код/ABI/benchmark не менялись.
**DO NOT RETRY** texture/bandwidth family без нового hardware raw-integer gather,
изменившегося limiter profile и доказанного layout с той же residency/dynamic safety.

#### Nearest-wrapper layer-0 proof — проверен и полностью удалён

Последним distinct exact micro-кандидатом existing layer-0 white proof был добавлен
только в nearest wrapper после всех прежних finite/edge/atlas/face/nearest-address
guards. При valid layer 0 и `+inf` либо прежнем
`receiverDistance <= hitDistance + 0.002` он возвращал тот же initial `vec3(1)` до
direction/normal/receiver-plane math; на любом miss буквально вызывался исходный
full texel helper. В отличие от отклонённых per-tap layer0-first и fused prefilter,
никакие soft taps/weights не жили через nearest fallback. Marker/NaN/negative,
signed-zero и epsilon boundaries, source order, actual GLSL -> SPIR-V -> MSL,
diagnostics, Metal API/GPU Validation и runtime tests прошли.

Clean Tier B
`20260822T113035Z-ge343bd0a5d9f-dirty-goal45-l6-nearest-layer0-proof-screen-off`
имел native HDR/Advanced Balanced/MetalFX OFF/VSync OFF, 2048 lights,
`READY/STALE=87/0`, `COMPLETE`, zero dropped timings и zero measured allocations.
Относительно retained control:

- FPS `35.807 -> 33.247` (`-7.15%`);
- presenting GPU p50/p95/p99 `31.225/32.452/33.152 ->
  33.750/35.290/35.849 ms`;
- WORLD OPAQUE avg/p95 `13.591/13.983 -> 14.848/15.547 ms`;
- 1%/0.1% lows `30.728/28.939 -> 30.574/30.438`; более высокий 0.1% tail не
  компенсирует устойчивый проигрыш whole-frame, GPU и WORLD;
- cluster build был легче (`1.667/2.552 ms` avg/p95), поэтому не объясняет регресс.

Proof branch/read, tests и goldens полностью удалены; прежний nearest wrapper
восстановлен. **DO NOT RETRY** source-level nearest proof без нового emitted-MSL/
coverage evidence: даже без soft live state duplicate layer-0 read и control-flow
изменили terrain codegen сильнее, чем сэкономили direction/plane ALU. На этом
source-level exact L6 micro-tuning закрыт; дальнейший exact шаг требует отдельного
opaque+translucent shader capture и coverage census, а не новой перестановки source.

#### L6 continuous triangle three-tap — проверен и полностью удалён

Первым independently reversible approximate-кандидатом был диагностический режим
`L6_TRIANGLE_3_TAP`; production/default оставался byte-identical full-quality.
Внутри face обычный bilinear 2x2 заменялся непрерывной triangle interpolation по
фиксированной диагонали 00--11, а в seam-полосе использовалась уже существующая
детерминированная resolved-ID диагональ. Это сохраняло C0 continuity и три
ненулевых corner weights вместо четырёх, но могло дать слабую facet-форму, поэтому
при достаточном win требовало отдельной live-проверки движения.

До запуска numeric oracle исчерпывающе проверил edge 8/16/32/64, шесть faces,
границы и `.5` ties. Он нашёл важный seam-case, где nearest logical tap имеет
нулевой triangle weight; поэтому реализация не выкидывала его вслепую, а сохраняла
ordered three-extra fallback. Positive-anchor footprint использовал nearest плюс
не более двух extras. Full filter при выключенном режиме восстанавливался
byte-identical; actual GLSL -> SPIR-V, idempotence, runtime trio и Metal API/GPU
Validation прошли.

Clean Tier B
`20260822T115223Z-ge343bd0a5d9f-dirty-goal45-l6-triangle-3tap-screen-off`
имел native 3024x1964 HDR, Advanced/Balanced, MetalFX OFF, VSync OFF, 2048 lights,
`READY/STALE=87/0`, `COMPLETE`, zero dropped timings и zero measured allocations.
Относительно retained control
`20260822T094449Z-ge343bd0a5d9f-dirty-goal45-l6-infinite-footprint-census-off`:

- FPS `35.807 -> 35.374` (`-1.21%`);
- presenting GPU p50/p95/p99 `31.225/32.452/33.152 ->
  31.933/33.303/34.231 ms`;
- WORLD OPAQUE avg/p95 `13.591/13.983 -> 14.756/15.629 ms`;
- 1%/0.1% lows `30.728/28.939 -> 32.652/32.537`, но улучшенный tail не
  компенсирует проигрыш whole-frame/GPU/WORLD и отсутствие FPS win;
- cluster build `1.905/2.757 -> 1.641/2.540 ms` avg/p95 был легче контроля,
  поэтому terrain shader regression тем более не объясняется culling compute.

Кандидат не прошёл минимальный performance stop-gate, поэтому live visual gate не
запускался. Enum, source transform, oracle/contracts и diagnostic test полностью
удалены; полный фильтр сохранён. **DO NOT RETRY** triangle-only interpolation как
source-level сокращение одного tap: на M1 Pro более тяжёлый control-flow/register
shape перекрывает арифметическую экономию. Возвращаться можно только с принципиально
иной emitted-MSL формой и предварительным offline ISA/occupancy доказательством;
само по себе разрешение небольшой потери качества не делает этот вариант полезным.

#### Edge64 interior nearest с сохранёнными cubemap seams — проверен и удалён

Временный CPU-census уже упакованных READY/STALE descriptors был добавлен только в
существующий report-log раз в 300 submit. Он не создавал GPU buffer/readback и не
менял fragment path. Clean full-filter run
`20260822T120449Z-ge343bd0a5d9f-dirty-goal45-l6-page-edge-census-off`
показал устойчивое распределение cached pages `8:1 / 16:1 / 32:1 / 64:84` при
`READY/STALE=87/0`. Это descriptor-count, не screen-weighted coverage, но edge64
охватывал `96.6%` resident pages и давал основание для одного диагностического A/B.

`L6_EDGE64_INTERIOR_NEAREST` сохранял full filtering на страницах 8/16/32 и в
полосе `<1.5` texel от каждой cubemap seam, а внутри edge64 возвращал уже вычисленный
nearest visibility. Production/default оставался byte-identical; режим был отдельным
shader variant без ABI/resource изменений. Actual GLSL -> SPIR-V, idempotence,
restore-default и runtime tests прошли.

Проверены две эквивалентные по изображению формы на одинаковом clean контракте
(native 3024x1964 HDR, Advanced/Balanced, MetalFX/VSync OFF, `600+300`, 2048 lights,
`READY/STALE=87/0`, edges `1/1/1/84`, `COMPLETE`, zero drops/allocations):

- early return внутри soft helper,
  `20260822T121112Z-ge343bd0a5d9f-dirty-goal45-l6-edge64-interior-nearest-screen-off`:
  `42.297 FPS`, GPU p50/p95/p99 `26.546/27.501/28.236 ms`, lows
  `39.314/39.173`, WORLD OPAQUE avg/p95 `12.786/13.220 ms`;
- branch до вызова soft helper,
  `20260822T121532Z-ge343bd0a5d9f-dirty-goal45-l6-edge64-interior-nearest-wrapper-screen-off`:
  `42.458 FPS`, GPU p50/p95/p99 `27.060/28.101/29.046 ms`, lows
  `35.561/33.228`, WORLD OPAQUE avg/p95 `13.015/13.504 ms`.

Оба варианта дали большой throughput win относительно full filter, но не прошли
заранее заданный gate `>=45 FPS` и `GPU p95 <=24.72 ms`; wrapper placement не
устранил предел и ухудшил tails относительно первого варианта. Live visual gate не
запускался. Enum/source transform/tests полностью удалены. **DO NOT RETRY** смешанный
edge64-interior/full-seam CFG с threshold 1.5: сохранение старой seam-полосы плюс
ветвление оставляет слишком много GPU frame time. Отдельный full-edge64 nearest
может быть проверен только как более рискованный independently reversible probe и
обязан быть отклонён при первом видимом texel silhouette, seam или temporal pop.

#### Full edge64 nearest — быстрый diagnostic, не production; полностью удалён

Отдельный `L6_EDGE64_NEAREST` variant оставлял literal full soft filter для страниц
8/16/32, включая Balanced dynamic edge32, но на edge64 после прежнего exact nearest
не вызывал три дополнительных soft taps. Default оставался byte-identical, менялся
ровно один call site после descriptor validation и до неизменного distance fade;
descriptor ABI, atlas, residency и builders не менялись. Shader/source/runtime и
`localVoxelShadowUnitTest` прошли.

Это был намеренно **quality- и fail-closed-semantics-changing diagnostic**, а не
готовый policy. Если nearest tap валиден, но один из трёх дополнительных taps имеет
invalid marker/NaN/negative distance, старый full filter может вернуть black, тогда
как nearest-only extra tap вообще не читает и сохранит nearest result. Кроме того,
на edge64 исчезает cubemap seam filtering и возможны квадратные texel silhouettes;
при замене resident page 32<->64 меняется режим фильтра и возможен temporal pop.

Clean `600 warmup + 600 measure` run
`20260822T122043Z-ge343bd0a5d9f-dirty-goal45-l6-edge64-nearest-diagnostic-off`
имел native 3024x1964 HDR, Advanced/Balanced, MetalFX/VSync OFF, 2048 lights,
`READY/STALE=87/0`, cached edges `1/1/1/84`, `COMPLETE`, zero dropped timings и
zero measured allocations. Два полных окна дали `44.231` и `44.175 FPS`;
агрегат:

- `44.203 FPS` против retained full-quality control `35.807` (`+23.45%`), но ниже
  заранее заданной цели `45`;
- presenting GPU p50/p95/p99 `25.165/26.136/26.742 ms`, хуже stop-gate
  `p95 <=24.72 ms`;
- 1%/0.1% lows `37.601/34.296` против контроля `30.728/28.939`;
- WORLD OPAQUE avg/p95 около `13.994/14.591 ms`; cluster avg/p95
  `1.378/2.102 ms`.

До still-image/live gate вариант не допущен: throughput не достиг 45, а статический
аудит уже выявил fail-closed и seam/replacement цену. Enum, transform и diagnostic
test полностью удалены. **DO NOT RETRY** как production shortcut. Результат полезен
как верхняя граница: снятие soft taps с `96.6%` resident pages даёт около 44.2 FPS,
но оставшийся шаг к 45 требует затронуть low-edge pages или иной системный путь;
делать это за счёт ещё большей пикселизации/temporal instability запрещено.

#### Full edge64 nearest возвращён только как default-OFF пользовательский переключатель

После отдельного объяснения цены исторического `L6_EDGE64_NEAREST` пользователь
явно попросил вернуть **именно этот измеренный режим** как легко отключаемую
настройку Sodium для собственного live A/B. Это осознанное исключение из прежнего
`DO NOT RETRY as production shortcut`, а не пересмотр результата: режим не принят
как production default, не считается quality-preserving оптимизацией и по-прежнему
не доказывает достижение цели 45 FPS.

Добавлен boolean `Fast Local Shadows (Experimental)` / `Быстрые локальные тени
(экспериментально)` в группе Lighting. Default и миграции renderer config v1--v4
дают `LocalShadowFilterMode.FULL`; schema v5 строго хранит `full` либо
`fast_edge64_nearest`. Выключенное состояние использует прежний source byte-for-byte
(старые golden SHA-256 не изменились). Включённое состояние меняет ровно resident
L6 filter call: `cacheFaceEdge == 64` берёт уже рассчитанный `nearestVisibility`,
а 8/16/32 выполняют literal прежний soft helper. Descriptor guards, nearest result,
distance fade, ABI, atlas, residency, builders и light selection не менялись.

Переключение применяет `REQUIRES_ASSET_RELOAD`, а не per-fragment runtime uniform.
Option binding только атомарно сохраняет следующую policy. В начале одного
`ShaderManager.apply` policy фиксируется на всю shader generation; preflight и
компиляция получают один snapshot. Minecraft очищает Metal pipeline/shader caches
до precompile, а `ShaderCompilationKey` дополнительно включает filter mode. Поэтому
ON/OFF не смешиваются внутри одной generation и возврат в OFF собирает исходный
full-filter shader заново.

Цена остаётся той же и намеренно написана в tooltip: на edge64 исчезают три extra
taps, включая cubemap seam filtering; возможны blocky/texel silhouettes, seams,
изменение fail-closed результата при invalid extra tap и temporal pop при замене
page 32<->64. Режим применяется к тем же общим L6 helpers у Sodium terrain,
entity и end portal. Исторический clean Tier B diagnostic дал `44.203 FPS`
(`+23.45%` к тогдашнему detailed full-filter control), GPU p95 `26.136 ms` и
не прошёл цель 45 / p95 stop-gate. Это **не новый benchmark результата UI path** и
не production FPS claim.

Проверки переключателя: config v1--v5 migration/default/strict round-trip,
Sodium option type + asset-reload flag, default golden identity, ON/OFF stale-source
rejection, exact 64 branch и literal 8/16/32 fallback, FULL + AMBIENT_ONLY,
все terrain/entity/portal GLSL -> SPIR-V и active MSL binding reflection,
exclusive diagnostic modes, `localVoxelShadowUnitTest` и `metalRuntimeUnitTest`.
Целевой набор прошёл. Полный `check` прошёл 87 задач при исключении двух вариантов
одного уже несвязанного native `BuiltinShaderLibraryValidation` failure
`Lazy clustered-lighting recovery failed`; оба варианта падают также отдельно и не
используют Sodium config/GLSL patcher. Benchmark launcher теперь fail-closed
требует `localShadowFilterMode=full`, поэтому включённый пользовательский режим не
может случайно выдать себя за обычный full-filter benchmark. Отдельная attested
quality-policy A/B поддержка не добавлялась до live визуального решения пользователя.

Статус: **IMPLEMENTED AS USER OPT-IN, DEFAULT OFF, LIVE VISUAL A/B PENDING**.
При первом заметном seam, silhouette, light leak, shimmer/pop либо неприятном pacing
режим нужно выключить; OFF является полным откатом без удаления кода. Если пользователь
его отвергнет окончательно, изолированные UI/config enum axis и source specialization
можно удалить без ABI/native/world-data migration.

#### Full edge64 nearest отвергнут live-визуально; переключатель полностью удалён

Пользователь провёл предусмотренный live A/B и оценил включённый вариант как
«выглядит ужасно». Это окончательный visual rejection: большой диагностический
throughput win не компенсирует разрушение мягкости и ступенчатые/резкие края.
Экспериментальный Sodium option, translations, renderer-config schema v5 и
`LocalShadowFilterMode`, generation snapshot/cache-key axis, preflight/source
specialization, benchmark guard и специализированные tests полностью удалены.
Renderer config возвращён к schema v4; production снова имеет единственный полный
мягкий L6 filter. Пользовательский `run/config/metallum-renderer.properties` не
содержал нового ключа, поэтому удаление не потребовало runtime migration.

**DO NOT RETRY** edge64 nearest, interior nearest или иной режим, который получает
скорость удалением soft taps и заметно превращает тени в texel silhouettes. Ранее
разрешённая небольшая потеря качества не распространяется на уже доказанно неприятный
вид. Возвращение такого переключателя возможно только по новому явному запросу, но не
как кандидат достижения 45 FPS.

#### Exact edge64 full-filter specialization — проверен и полностью удалён

После visual rejection проверен единственный новый узкий кандидат, который не менял
картинку и семантику: отдельная literal-edge64 копия полного soft helper. Она сохраняла
все четыре taps, прежние bilinear weights, cubemap seam resolution, ordered colored
transmittance, invalid/NaN fail-closed guards, layer-0 all-visible proof и distance
fade. Изменений ABI, atlas, descriptors, builders или residency не было. До запуска
проверен реальный GLSL -> SPIR-V -> MSL: emitted MSL содержал отдельную функцию без
runtime `cacheFaceEdge`, с literal `64`, `4096u` texels на face и теми же четырьмя
visibility reads. Более слабая source-форма, где literal передавался прежнему helper,
была отклонена до benchmark, потому что emitted MSL сохранил generic runtime parameter.

Fresh full-soft control
`20260822T131338Z-ge343bd0a5d9f-dirty-goal45-full-soft-control-screen-off`
и candidate
`20260822T132336Z-ge343bd0a5d9f-dirty-goal45-l6-edge64-full-specialized-screen-off`
выполнены последовательно на одном контракте: native 3024x1964 HDR,
Advanced/Balanced, MetalFX/VSync OFF, `600 warmup + 600 measure`, 2048 lights,
`COMPLETE`, zero dropped timings/allocations, clipmap `dirty=0`, overflow `68`.
Результат — consistent regression:

- FPS `34.771 -> 34.410` (`-1.04%`);
- presenting GPU p50/p95 `30.772/32.156 -> 31.164/32.364 ms`;
- 1% low `30.625 -> 29.394 FPS` (`-4.02%`);
- WORLD OPAQUE mean avg примерно `13.875 -> 14.114 ms`, mean p95
  `14.413 -> 14.640 ms`;
- cluster build `1.341/2.074 -> 1.382/2.034 ms` avg/p95 не объясняет
  проигрыш fragment path.

Кандидат не прошёл даже минимальный performance gate. Наиболее вероятная причина —
хуже code size/register/codegen из-за дублирования большого full helper; это inference,
а не доказанная occupancy причина. Diagnostic enum, duplicated helper, marker и tests
удалены, полный мягкий source восстановлен.

Source/representation audit также закрыл «очевидные» альтернативы без повторного
benchmark: hardware bilinear/gather неприменимы к packed integer SSBO hit lists с
receiver-dependent сравнением distance на каждом tap; предварительно фильтрованные
depth/moments/VSM меняют ordered colored transmittance и дают light leaks/halos;
triangle three-tap уже измеренно регрессировал; nearest уже визуально отвергнут;
FP16/generic source перестановки и seam-only lookup не имеют нового emitted-MSL или
достаточного cost evidence. Поэтому **в текущей L6 representation нет доказанного
способа удешевить саму фильтрацию, сохранив красивые мягкие тени**. Никакое изменение
фильтрации не оставлено. Следующий путь к 45 FPS должен быть вне потери soft-shadow
samples либо начинаться с нового GPU-capture/representation evidence, а не с ещё одной
аппроксимации текущих четырёх taps.

#### Shared receiver-plane ray для трёх extra taps — отклонён и полностью удалён

Проверен новый диагностический вариант `L6_SHARED_PLANE_RAY`, не повторяющий прежний
exact receiver-plane hoist: nearest tap всё ещё вычислялся исходным путём, а для трёх
остальных seam-safe bilinear taps переиспользовались его cubemap direction и
receiver-plane distance. Все четыре веса, face/seam resolution, четыре ordered cache
layers каждого tap, invalid/NaN fail-closed guards, colored transmittance и distance
fade сохранялись. Следовательно, это была небольшая **неэквивалентная** аппроксимация
только receiver-plane correction; её потенциальная цена — иной порог остановки за
окклюдером у соседнего texel. Целью было убрать три `cubeDirection + inversesqrt` из
ALU-bound fragment path без nearest-only деградации силуэтов.

Первый live launch
`20260824T114554Z-g366f066cd8e2-dirty-l6-shared-plane-ray-off` не является
результатом: Metal/GLSL цепочка отвергла helper, потому что его определение находилось
после первого вызова. Advanced admission правильно перевёл renderer в vanilla и
benchmark завершился `FAIL`; FPS из этого запуска не используются. Добавлены только
diagnostic forward declarations, после чего actual GLSL -> SPIR-V diagnostic test,
default golden/restore contract и `localVoxelShadowUnitTest` прошли; runtime admission
во втором запуске подтвердил `advanced`, `l3=true`, `l5=true`, `l6=true`.

Сопоставимый Tier B на Apple M1 Pro: built-in 3024x1964 HDR,
Advanced/Balanced, MetalFX/VSync OFF, Nether `nether-lava-stress-v1`, `600 warmup +
600 measure`, два окна, nominal thermal, zero dropped timing events. Control
`20260824T113705Z-g366f066cd8e2-dirty-l6-shared-plane-baseline-off` против valid
candidate
`20260824T114804Z-g366f066cd8e2-dirty-l6-shared-plane-ray-rerun-off`:

- FPS `35.676 -> 34.127` (`-4.34%`); 1% low `30.571 -> 30.662`, 0.1% low
  `29.040 -> 29.202` — mixed low-frame signal не компенсирует lost throughput;
- presenting GPU average `31.093 -> 31.046 ms` (`-0.048 ms`, около `-0.15%`),
  p95 `32.191 -> 32.132 ms`, но p99 `32.774 -> 32.828 ms`;
- candidate получил `EVENT=COMPLETE`, 600 measured frames и healthy L6 residency;
  это не fallback и не startup-telemetry result.

Такой микроскопический и противоречивый Tier B с худшим FPS не достигает даже
screening gate, поэтому live visual A/B и Tier C не запускались. Diagnostic enum,
source transform и его test удалены полностью; default full filter не менялся,
пользовательские benchmark preflight edits (`schema v4` и exclusive fullscreen)
возвращены к исходным runtime settings.

**DO NOT RETRY** shared receiver-plane/direction reuse в текущем L6 filter без нового
Metal capture, который отдельно покажет заметную стоимость именно этих трёх
normalizations, и без нового representation path. Уже измеренный выигрыш менее 0.2%
GPU time не оправдывает даже малую receiver-plane ошибку и не приближает к требуемому
35 -> 44 FPS уровню.

#### L6 bounded direct-error budget 1/512 — отклонён и полностью удалён

Новый независимый diagnostic `L6_BOUNDED_ERROR_1_OVER_512` не упрощал геометрию
soft filter: после точной proxy-occlusion он мог пропустить resident L6 только тогда,
когда абсолютный unshadowed direct contribution помещался в оставшийся покомпонентный
budget `1/512`. Каждый пропуск вычитал свой bound, поэтому суммарная ошибка прямого
линейного света в одном fragment была формально ограничена `<= 0.001953125` в каждом
RGB-канале. Все не помещающиеся в budget lights выполняли исходный четырёхtap L6 путь;
default source оставался byte-identical.

Это не стало выигрышем. Свежий Tier B на Apple M1 Pro, built-in 3024x1964 HDR,
Advanced/Balanced, MetalFX/VSync OFF, Nether `nether-lava-stress-v1`, `600 warmup +
600 measure`, nominal thermal, two complete windows и zero dropped timing events:
control `20260824T120439Z-g366f066cd8e2-dirty-l6-bounded-error-baseline-off` против
candidate
`20260824T120620Z-g366f066cd8e2-dirty-l6-bounded-error-1-over-512-off`.

- FPS `35.766 -> 34.396` (`-3.83%`); 1% / 0.1% low `32.145 / 31.702 ->
  30.801 / 29.255`;
- presenting GPU average `31.050 -> 32.174 ms` (`+1.124 ms`, `+3.62%`),
  p50/p95/p99 `31.029/32.066/32.716 -> 32.146/33.214/33.757 ms`;
- оба прогона имеют `resolved_lighting=advanced`, active voxel clipmaps, 2048 lights,
  `COMPLETE`, 600 presented frames и zero timing drops, то есть это не fallback и не
  неполный workload.

Поскольку screening уже заметно хуже, visual A/B и Tier C не запускались. Diagnostic
enum, source transform и shader test удалены полностью; `localShadowFilterMode=full`
и исходные fullscreen/settings возвращены. **DO NOT RETRY** source-level
per-fragment budget/conditional-skip family при таком или меньшем budget без
emitted-MSL/counter evidence, что условие реально избегает L6 работы, а не добавляет
register/control-flow pressure в ALU-bound terrain shaders.

#### L6 nearest только для translucent terrain — отклонён и полностью удалён

Следующая distinct гипотеза использовала прежний `L6_NEAREST_ONLY` лишь как верхнюю
границу, но не переносила его на весь мир: отдельный Sodium
`pipeline/translucent_terrain` fragment module по явному diagnostic mode
`L6_NEAREST_TRANSLUCENT_ONLY` сохранял opaque terrain full four-tap filter, а в
translucent terrain оставлял только уже вычисленный nearest cache tap. Мотивация была
из нового Metal System Trace: translucent terrain — второй крупный ALU-bound fragment
encoder. Это не production policy: lava/water и coloured translucent shadows могли
получить texel silhouettes, поэтому для начала требовался только screening win.

Перед запуском source test подтвердил, что diagnostic marker и отсутствие soft helper
есть исключительно у translucent fragment; opaque source с тем же runtime mode
byte-identical к full-quality source. Actual GLSL -> SPIR-V test прошёл. Fresh Tier B
на том же Apple M1 Pro/3024x1964 HDR/Advanced Balanced/MetalFX OFF/VSync OFF,
Nether `nether-lava-stress-v1`, `600 warmup + 600 measure`, nominal thermal:
control `20260824T121644Z-g366f066cd8e2-dirty-l6-nearest-translucent-baseline-off-off`
против candidate
`20260824T121821Z-g366f066cd8e2-dirty-l6-nearest-translucent-only-off-off`.

- FPS `35.653 -> 34.779` (`-2.45%`); 1% / 0.1% low `31.294 / 29.792 ->
  31.333 / 30.235` не компенсируют lost throughput;
- presenting GPU average `31.216 -> 31.905 ms` (`+0.689 ms`),
  p50/p95/p99 `31.159/32.263/32.770 -> 31.841/32.933/33.393 ms`;
- оба запуска имеют explicit `ADVANCED_ADMISSION` (`l3=true`, `l5=true`, `l6=true`),
  `COMPLETE`, 600 frames, 2048 lights, active voxel clipmaps и zero timing drops.

Live visual A/B и Tier C не запускались: diagnostic проиграл раньше quality gate.
Enum, pipeline-specific shader-cache define/routing, source transform и test удалены;
opaque и translucent снова используют исходный full L6 filter, runtime settings
восстановлены. **DO NOT RETRY** nearest-only filter только для translucent terrain
без нового counter/emitted-MSL evidence, объясняющего этот regression: текущий
fragment/PSO codegen не превращает удаление three taps в выигрыш whole-frame GPU time.

#### Apple Metal fast-math compile option — NO-GO без кода

Проверен отдельный AppleSilicon/Metal путь в настоящем native runtime. Динамически
сгенерированный terrain MSL создаётся в `metallum_create_shader_function` через
`device.makeLibrary(source: ..., options: nil)`. Однако заголовок текущего macOS SDK
(`Metal.framework/Headers/MTLLibrary.h`) прямо устанавливает для
`MTLCompileOptions.fastMathEnabled` default `YES`; для новых API default
`mathFloatingPointFunctions` также `Fast`. Следовательно, подстановка явного
`MTLCompileOptions` с fast math не создаёт новую оптимизацию, а relaxed/fast mathMode
может нарушить NaN/Inf fail-closed guards L6 и изменить итог изображения.

Код и benchmark не менялись. **DO NOT RETRY** явное включение Metal fast math как
FPS-кандидат: оно уже включено по умолчанию; не рассматривать более агрессивный
mathMode без отдельного visual/numerical safety контракта.

#### Apple Silicon TBDR/imageblock L6 audit — архитектурный кандидат, не micro-opt

Отдельно проверен оставшийся Apple-specific путь для M1 и новее: tile-based deferred
lighting с tile shaders/imageblocks. На Apple GPU imageblock хранится в локальной
tile-memory в течение render pass; при удачном переносе opaque direct lighting из
forward terrain pass это потенциально может убрать повторное выполнение L6 на hidden
fragments и дать tile-local reuse для G-buffer и списка lights. Это единственный
найденный путь, который может уменьшить **число запусков** текущего full-quality L6,
не меняя четыре soft taps, ordered coloured transmittance или receiver-plane test.

Это не switch для существующего shader. Сейчас L6 зависит от позиции receiver,
world normal, направления от light к receiver, receiver distance и четырёх ordered
integer-hit layers каждого cache texel. Поэтому нельзя exact-переиспользовать готовую
visibility между соседними fragment lanes/quad и нельзя отдать текущий `uvec2` cache
hardware bilinear/gather: filtering должен происходить после per-receiver visibility
test. SIMD/quad reuse был бы новой аппроксимацией с риском silhouette/temporal ошибок,
а float/depth/moment representation меняет coloured transmittance и уже закрыта как
семейство representation-risk. Metal 4 argument tables/binary archives изменяют
создание/encoding pipelines, но не стоимость executed L6 fragment; на actual M1 Pro
runtime probe также нет dynamic caching, hardware ray tracing, mesh shader family 9
и per-draw statistic counters.

Код и FPS benchmark намеренно не менялись: без доказательства overdraw такой большой
перевод легко проиграет за счёт G-buffer bandwidth, tile-memory pressure и нового
translucent path. Перед прототипом обязательны: (1) labeling текущих opaque/cutout/
translucent PSO только для диагностики; (2) новый 5 s Metal System Trace на исходном
Nether contract, показывающий raster/fragment work и depth/overdraw именно для L6;
(3) paper-budget G-buffer + imageblock formats против saved fragment invocations;
(4) отдельный opaque-only A/B, где translucent сохраняет исходный forward L6;
(5) тот же visual + Tier B/C contract. Если capture не покажет достаточную лишнюю
fragment работу, **DO NOT START** tile-deferred rewrite: это не доказанный способ
получить 35 -> 44 FPS и не замена текущей качественной фильтрации.

#### Follow-up: opaque-only tile/deferred L6 — REJECTED до production-прототипа

Повторный аудит исправил исходную предпосылку предыдущего раздела. Полноценный
imageblock/deferred L6 в Metallum раньше не внедрялся: в Apple Silicon sprint был
остановлен только Tile Forward+ для light-list (`0.465–1.317 ms` cluster-build p95),
а ранний аудит pass fusion/memoryless констатировал отсутствие G-buffer. Однако уже
существующие измерения закрывают именно предполагаемый источник выигрыша — лишние
L6-вызовы на hidden opaque fragments.

**SUPPORTED evidence:**

- **SUPPORTED (structural):** финальный solid-terrain MSL/PSO ранее классифицирован
  как `HSR_OPTIMAL`: обычные
  scene/reactive outputs, без `discard_fragment`, fragment-depth/sample-mask output,
  atomics или записей в buffers/textures; depth compare/write включены, blending
  выключен. Текущий `MetalCompiledRenderPipeline` сохранил тот же PSO contract, а
  свежие `advancedDirectLightingShaderUnitTest` и `metalRuntimeUnitTest` прошли.
  Следовательно, Apple TBDR уже может выполнять hidden-surface removal внутри
  существующего opaque render pass; imageblock не открывает новый механизм удаления
  скрытых solid fragments.
- **SUPPORTED (Tier B):** Exact Sodium camera depth-prepass уже был измерен на том же
  Nether/L6 workload.
  Он повторял тот же vertex path и alpha/depth semantics, затем запускал исходный
  full-quality pass с готовой depth. Это наиболее близкий практический upper-bound
  для гипотезы «сначала видимость, потом дорогой L6»: `WORLD_OPAQUE` p95 выиграл менее
  `0.75 ms`, whole-frame не достиг даже `5%`, а warmed presenting GPU p95 ухудшился.
  Эксперимент был полностью удалён; activation-proof сохранился в артефакте
  `20260822T075114Z-...-depth-prepass-activation-proof-off`.
- **SUPPORTED (counter trace):** Metal GPU Counters
  `20260822T0830Z-nether-gpu-counters.trace` показывают ALU-bound
  terrain: opaque-like encoder имеет ALU Limiter около `47–48%`, Texture Sample около
  `3.6%`, Buffer Read около `3.5%`, LLC около `3.1–3.3%`. Translucent-like encoder,
  который opaque-only deferred вообще не меняет, стоит ещё около `11.7 ms/frame` и
  имеет ALU Limiter около `54–55%`. Перенос той же L6 математики в tile function не
  уменьшает ALU на каждом уже видимом sample.

**Paper budget / break-even:**

- При `3024x1964`, sample count `1`, текущий render pass несёт примерно `13 B/sample`
  attachment payload (`RGBA16F scene + R8 reactive + D32 depth`). Для byte-conservative
  split текущей функции нужны как минимум float32 view position (`16 B` attachment),
  float32 normal (`16 B`), pre-light color/albedo (`16 B`) и material + rain/sky
  inputs (`12 B`), после чего всё ещё нужны final scene/reactive/depth. Это около
  `73 B/sample`: `18,688 B` на `16x16`, но `74,752 B` на `32x32`. Runtime M1 Pro
  сообщает `maxThreadgroupMemoryLength=32,768`; это не pipeline-specific
  `imageblockSampleLength`, но достаточный pressure warning: quality-conservative
  вариант потребует меньших tiles/occupancy, а компактный вариант около `29 B/sample`
  достигается только reconstruction/half/packed normal/material и уже не гарантирует
  текущую numerical/visual parity.
- Чтобы поднять примерно `35 -> 44 FPS`, нужно убрать около `5.84 ms/frame`. Даже если
  считать весь opaque-like interval `13.8 ms` потенциально удаляемым, кандидат должен
  сэкономить более `42%` этого encoder **до** оплаты G-buffer/tile pass. Existing exact
  depth-prepass обнаружил менее `0.75 ms`; новый GPU trace также не даст отсутствующие
  per-draw fragment statistics, потому что capability probe отмечает statistic counters
  unavailable. Для `5%` Gate A candidate должен был бы показать хотя бы `1.5–2.0 ms`
  правдоподобного net upside; доказанный upper-bound этого не даёт.

Implementation census подтверждает, что это не bounded shader toggle. Текущий
`MetalCrossShaderCompiler` создаёт только vertex/fragment functions; native/Panama
bridge не имеет `MTLTileRenderPipelineDescriptor`, `setTileRenderPipelineState` или
`dispatchThreadsPerTile`; render-pass owner предоставляет scene color, optional
semantic/reactive attachment и depth. Прототип потребовал бы нового PSO/cache axis,
tile ABI и split общего Sodium solid/cutout/translucent source, сохраняя отдельный
forward translucent path. Это затрагивает renderer lifetime/pipeline ownership раньше,
чем появляется измеримый кандидат.

**Решение: REJECT/DO NOT IMPLEMENT на текущей архитектуре.** Большой rewrite не
построен и production source не изменён: он дублировал бы уже работающий HSR,
оставлял бы второй крупный translucent L6 path нетронутым и добавлял tile-memory,
pipeline/cache/attachment complexity. Вернуться к нему можно только с новым фактом,
который одновременно опровергает `HSR_OPTIMAL` на actual opaque PSO и показывает
`>1.5–2.0 ms` removable hidden-fragment work после учёта G-buffer/tile overhead.
Обычный новый System Trace без таких counters не является этим фактом.

#### Exact four-tap Apple layout/codegen follow-up — отклонён и удалён

После запроса не считать разрыв full `~35 FPS` против nearest `~44 FPS`
исчерпанным проверены ещё четыре exact-варианта. Все сохраняли четыре bilinear taps,
четыре ordered `{float distance, RGB8 visibility + valid marker}` слоя на tap,
receiver-distance/receiver-plane tests, веса и итоговую арифметику. Synthetic Metal
microbenchmark использовал реальный Apple M1 Pro, private `32 MiB` atlas, `262144`
queries, восемь повторов на sample и 12 interleaved samples; correctness сравнивалась
побитно с текущим lazy AoS buffer path.

- Два private texture reads на tap (`RGBA32Float` distances + `RGBA32Uint` colours),
  то есть восемь vector reads вместо до 16 lazy `uint2`: `0` mismatches, но
  `3.2633 -> 17.1454 ms` (`+425.39%`). Texture unit не является бесплатной заменой
  SSBO на этом ALU-bound shader и eager-загрузка всех layers уничтожает ранние exits.
- Аналогичный private SoA buffer (`float4` distances + `uint4` colours): `0`
  mismatches, `3.2611/3.2633 -> 5.4870–5.6797 ms` (`+68–74%`). Меньшее число
  формальных vector loads не компенсирует eager state/liveness.
- Настоящий layer-major `RG32Uint` texture-array с двумя hardware gathers на layer
  обрабатывал четыре соседних taps совместно и сохранял ранний выход, когда все taps
  завершены. MSL на M1 Pro поддержал integer gather; после явной перестановки Metal
  gather order результат дал `0` mismatches, но `3.2611 -> 6.9405 ms` (`+112.83%`).
- Layer-major private buffer с двумя соседними records в каждом unaligned vector load
  также дал `0` mismatches, но `3.2611 -> 5.4870 ms` (`+68.26%`). Параллельное
  ведение четырёх tap states дороже текущих независимых lazy loops.
- MSL `[[likely]]/[[unlikely]]` hints были exact, но в повторных samples дали
  примерно `+1.5–3.2%` cost. Не переносить в production.

Единственный microbench-кандидат около noise floor — запрет автоматического unroll
четырёхслойного cache loop (`#pragma clang loop unroll(disable)`, `0` mismatches,
примерно `1–1.5%` synthetic improvement) — был проверен в реальном terrain MSL через
временный, default-OFF environment diagnostic. Fresh Tier B, built-in
`3024x1964` HDR, Advanced/Balanced, MetalFX/VSync OFF, Nether
`nether-lava-stress-v1`, `600 warmup + 600 measure`, nominal thermal:

- control `20260824T130827Z-g366f066cd8e2-dirty-l6-layer-loop-control-off`:
  `35.700 FPS`, GPU average/p50/p95/p99
  `31.112/31.097/32.284/32.862 ms`, 1%/0.1% lows `31.544/30.235`;
- candidate `20260824T131008Z-g366f066cd8e2-dirty-l6-layer-loop-no-unroll-off`:
  `33.889 FPS`, GPU average/p50/p95/p99
  `31.807/31.715/33.141/36.155 ms`, 1%/0.1% lows `27.507/25.885`;
- оба отчёта `COMPLETE`, `ADVANCED_ADMISSION status=PASS` с
  `l3=true/l5=true/l6=true`, 2048 lights, L6 READY/STALE `87/0`, active voxel
  clipmaps, zero dropped timing events и thermal `nominal`. Candidate log отдельно
  подтвердил diagnostic activation. Различный total copy traffic не позволяет
  повышать single-pair regression до `PROVEN`, но candidate явно не показал
  требуемого выигрыша и не прошёл даже screening gate.

Diagnostic constants, environment switch, generated-MSL transform и helper удалены;
runtime fullscreen/schema settings восстановлены. Production shader снова использует
исходный full four-tap lazy AoS path. **DO NOT RETRY** texture/SoA/layer-major gather,
branch-hint или cache-loop no-unroll family без новой архитектуры данных либо нового
counter evidence: на M1 Pro они либо кратно медленнее exact baseline, либо не дают
real-frame выигрыша. Исторические `35 -> 44 FPS` остаются реальной верхней границей
стоимости трёх дополнительных taps, но nearest получает её именно удалением трёх
независимых visibility evaluations; текущие exact-перестановки эту работу не убирают.

#### Opaque L6 stochastic temporal reconstruction — RETAINED as default-OFF experiment

Повторная проверка истории не нашла ранее реализованного temporal L6. Старый L8
temporal/reactive path накапливает всю сцену после world pass и не является заменой
пространственного L6-фильтра. Tile/deferred rewrite также не был начат: прежние HSR,
depth-prepass и G-buffer/imageblock budgets по-прежнему делают его неоправданным.

Сначала измерена только цена сокращения taps, без temporal resolve. Fresh screening,
built-in `3024x1964` HDR, `hdrtest-static-v1`, Advanced/Balanced, MetalFX/VSync OFF,
`600 warmup + 600 measure`:

- exact control `20260824T134725Z-...-l6-stochastic-gatea-control-off`: `35.315 FPS`,
  GPU p50/p95/p99 `29.982/31.010/31.920 ms`;
- nearest + one stochastic extra (два taps) `20260824T134903Z-...-gatea-two-tap-off`:
  `35.783 FPS` (`+1.3%`), GPU p95 `30.635 ms`; этого недостаточно;
- новый exact control `20260824T135339Z-...-gatea-control-off`: `35.620 FPS`, GPU
  p50/p95/p99 `31.136/32.443/32.862 ms`;
- unbiased один tap, выбираемый по исходным четырём bilinear weights,
  `20260824T135516Z-...-gatea-one-tap-off`: `40.507 FPS` (`+13.7%`), GPU
  p50/p95/p99 `27.636/28.378/28.841 ms`, lows `35.738/32.904` против
  `29.730/28.815`. Это подтвердило достаточный ALU budget для reconstruction.

Полноэкранный Apple MetalFX Temporal при native scale был проверен и отвергнут как
решение L6. Exact + Temporal
`20260824T140206Z-...-gateb-control-exact-temporal` дал `27.075 FPS`, one-tap +
Temporal `20260824T140405Z-...-gateb-one-tap-temporal` — `31.113 FPS`. Он подтвердил
экономию taps, но temporal resolve всей сцены стоил заметно дороже исходного exact/OFF
пути (`35.620 FPS`). Поэтому retained implementation не накапливает scene color.

Реализован более узкий forward/MRT path только для
`sodium:pipeline/solid_terrain`:

- diffuse L6 выбирает один из исходных четырёх taps с вероятностью его bilinear weight;
  material specular, cutout, translucent, entity и остальные L6 paths сохраняют exact
  four-tap filter;
- fragment пишет в private `RGBA16F` history только RGB-отношение
  `shadowedDirect/unshadowedDirect` и current depth. Два ping-pong history при
  `3024x1964` занимают около `92 MiB`; отдельного fullscreen pass нет;
- previous-frame lookup использует camera reprojection и Metal raster Y convention.
  История отклоняется по previous view-space depth (`0.03125..0.25` block tolerance),
  восстановленной depth-normal (`dot >= 0.78`), bounds/finite checks, history reset и
  L6 contract validity. Accepted history смешивается EMA weight `0.75`;
- albedo, environment, fog и final scene color не накапливаются. Lighting/renderer/
  extent/world changes уже входят в frame `resetMask`, поэтому история на них
  инвалидируется;
- отдельный `METALLUM_ADVANCED_L6_TEMPORAL` PSO использует `color(1)=RGBA16F`, native
  fragment buffer slot 12 и texture/sampler slot 10. Первые варианты корректно
  fail-closed обнаружили invalid Metal slots 31/16 и auxiliary-role mismatch; после
  переноса и исправления Metal PSO прошёл runtime admission без Advanced fallback.

Идентичная final-source screening-пара `600+600`:

- exact `20260824T143432Z-...-l6-temporal-final-screen-control-off`:
  `44.834 FPS`, GPU p50/p95/p99 `23.783/24.179/24.774 ms`;
- temporal `20260824T143551Z-...-l6-temporal-final-screen-candidate-off`:
  `50.934 FPS` (`+13.6%`), GPU `20.749/21.530/22.211 ms`, lows
  `37.522/36.038` против `33.766/32.079`.

Две независимые production-length пары (`1800 warmup + 3000 measure`, одинаковые
source/artifact hashes, nominal thermal, `COMPLETE`, zero dropped events, Advanced
admission `l3/l5/l6=true`) повторили результат:

| run | FPS | 1% / 0.1% low | GPU p50 / p95 / p99 ms |
|---|---:|---:|---:|
| exact 1 `20260824T143855Z-...-tierc-control-1-off` | 45.314 | 34.430 / 32.932 | 23.594 / 23.916 / 24.533 |
| temporal 1 `20260824T144130Z-...-tierc-candidate-1-off` | 50.934 | 37.969 / 36.007 | 20.762 / 21.550 / 22.251 |
| exact 2 `20260824T144350Z-...-tierc-control-2-off` | 45.364 | 33.077 / 31.434 | 23.926 / 24.294 / 24.880 |
| temporal 2 `20260824T144621Z-...-tierc-candidate-2-off` | 50.966 | 37.022 / 35.608 | 20.747 / 21.544 / 22.083 |

Средние: `45.339 -> 50.950 FPS` (`+12.38%`), GPU p95
`24.105 -> 21.547 ms` (`-2.558 ms`, `-10.61%`). Однако launcher не создал
`.accepted.json`: после каждого полного run attestation остановилась на
`benchmark evidence events are out of order` в текущем dirty WIP. Raw/summary
контракты валидны и две пары согласованы, поэтому evidence — **SUPPORTED**, не
release-`PROVEN` Tier C.

Camera-motion/L6-dynamic screening также сохранил throughput gain:
`20260824T144914Z-...-l6-temporal-dynamic-control-off` против
`20260824T145042Z-...-l6-temporal-dynamic-candidate-off`: `33.186 -> 37.426 FPS`
(`+12.78%`), GPU p95 `35.760 -> 30.104 ms`. Но window-summary lows ухудшились:
1% `22.148 -> 17.735`, 0.1% `20.562 -> 16.358`. Single-pair dynamic allocation/
entity variability не доказывает причинность, но это обязательное предупреждение для
ручной проверки плавности.

**Решение:** оставить как явно экспериментальный, default-OFF user opt-in. В Sodium
добавлена restart-required галочка `Экспериментальные тени`; она сохраняется отдельно
в `metallum-experimental-shadows.properties`. Environment/property overrides остаются
для воспроизводимого A/B. При активном MetalFX Temporal эксперимент автоматически не
выбирается, чтобы два temporal history path не конкурировали за auxiliary output.

Проверены `advancedDirectLightingShaderUnitTest` (actual GLSL -> SPIR-V -> MSL и exact
specular isolation), `rendererArchitectureUnitTest`, `metalRuntimeUnitTest`,
`temporalScalingUnitTest`, native Swift build и обе language JSON. **Визуальная
приёмка остаётся открытой:** spatial four-tap result не вычисляется в одном кадре, а
реконструируется во времени. Проверить прежде всего slow camera orbit, тонкие цветные
полупрозрачные границы рядом с opaque terrain, появление/исчезновение факела,
disocclusion и возможный shimmer/ghosting. До такой проверки не включать по умолчанию.

#### Water-only frozen voxel cone reflection — RETAINED as default-OFF experiment

Planar rendering в этом кандидате отсутствует. Retained carrier использует один
accepted-Sodium source `64^3`, два блока на cell, `RGBA16F` radiance/optical presence,
R8 validity и шесть mip levels. Только water vertices выполняют ограниченный
40-шаговый world-space cone trace (`0.75` start, `1.75` block step, около 69 blocks)
вдоль `reflect(view, up)`; roughness выбирает mip LOD. Fragment получает один
интерполированный `vec4`, не делает `texture3D` reads и смешивает результат только с
environment term через water Fresnel. Solid/cutout/translucent non-water paths обходят
все reflection reads. Retained visual constants: strength `0.75`, roughness `0.18`.

Dedicated immutable fixture `reflection-house-voxel-v1` имеет digest
`6c2d00ee96ecfead1b0dac27d21b95323b02980e84084e3dd79d718c4045d2bb`; static route
digest — `2316274b3b58814788391ff76217f91ec7f59ba8458ec985ee0d028b8a06b286`.
Source-level contribution capture
`20260825T071756Z-...-voxel-house-quality-candidate-contribution-v8-off.png`
(`27827ea52f43...`) сохраняет узнаваемые redstone columns/wall, sand, brown house и
green shore. Final production capture
`20260825T081052Z-...-voxel-house-final-production-v6-off.png`
(`8e7010dc5295...`) сохраняет дальний красный reflection, тёплую полосу дома/берега и
rough water breakup без чёрных chunk-like псевдоотражений. Это voxel lookup: никакого
scene/planar capture pass нет.

Три реальные причины первоначально нулевого/ложного результата исправлены узко:

- Java source layout `(y,z,x)` раньше копировался в Metal staging как `(z,y,x)` без
  transpose; upload теперь явно переставляет destination rows;
- contribution-only диагностика раньше оставляла прозрачный water alpha, и видимое
  дно ошибочно принималось за reflection; diagnostic output теперь opaque;
- L6 camera/world params в slot 16 были fragment-only, хотя reflection vertex shader
  использует их для world origin. При включённом experiment slot 16 теперь bindится и
  во vertex stage; до этого все rays фактически стартовали около world origin.

CPU source исключает receiver water, агрегирует все восемь blocks каждого `2x2x2`
cell и центрирует domain к ближайшей section. Native ownership остаётся double-buffered:
старое READY field bound до exact completion нового `PENDING/READY/FAILED` build.
Motion route `reflection-house-voxel-motion-v1` выявил дополнительный recenter bug:
выход за X guard заново центрировал также стабильный Z и вызывал rebuild oscillation.
Per-axis recenter сохраняет оси внутри guard. Validated motion receipt
`20260825T080811Z-...-voxel-house-motion-recenter-v3-off` дал ровно initial + one
recenter: `[176,0,80] -> [144,0,80]`, два `512/512 READY`, final admission `READY`,
`COMPLETE`, zero timing drops и ни одного `INVALID/FAILED`.

Tier C M1 Pro, built-in `3024x1964` HDR, Advanced/Balanced, MetalFX/VSync OFF,
immutable fixture, source `0d338d5141fae79357e7592407f688193f0337e536a9a61341085fdf314359a0`,
artifact `269bba90ca8d2f8c309c75018f1b5873f448fbdc756bf3c8bc4c9ec52fe8f26b`,
`1800 warmup + 3000 measure`, `COMPLETE`, zero drops, exact Advanced L3/L5/L6 and
reflection READY admission; все четыре run имеют `.accepted.json`:

| run | FPS | 1% / 0.1% low | GPU p50 / p95 / p99 ms |
|---|---:|---:|---:|
| OFF A `20260825T081216Z-...-tierc-a-off-off` | 77.067 | 55.091 / 51.078 | 14.9284 / 15.6984 / 16.2654 |
| ON A `20260825T081410Z-...-tierc-a-on-off` | 75.362 | 53.289 / 49.732 | 15.2264 / 16.0152 / 16.6019 |
| ON B `20260825T081605Z-...-tierc-b-on-off` | 75.658 | 54.233 / 50.425 | 15.1305 / 15.9190 / 16.5548 |
| OFF B `20260825T081803Z-...-tierc-b-off-off` | 76.640 | 54.723 / 50.926 | 15.0102 / 15.7620 / 16.5105 |

`compare-multi`: baseline mean `76.85 FPS`, GPU p95 `15.73 ms`; candidate mean
`75.51 FPS`, GPU p95 `15.97 ms`. Delta `-1.75% FPS`, `+0.24 ms / +1.51% GPU p95`;
строгий `+0.20 ms` gate не пройден. ON также закономерно поднимает instrumented
GPU-shared upload high-water `155668 -> 160848` bytes. UVW recurrence, polynomial LOD
approximation и `35 x 2.0` same-range march были измерены по одному и отклонены: все
ухудшили timing; не повторять без нового shader/counter evidence. `80^3` source также
отклонён: persistent bytes `14,417,920` превышают экспериментальный 8 MiB budget.

Benchmark evidence ordering исправлен без ослабления attestor: единственный
`SERVER_TICKS_FROZEN` теперь публикуется после server confirmation и `ROUTE_APPLY`;
shell runner использует ту же каноническую последовательность.

**Решение:** визуально и lifecycle-корректный voxel reflection carrier retained, но
остаётся restart-gated default-OFF opt-in из-за воспроизводимого `+0.24 ms` Tier C
near-miss. Не включать по умолчанию и не снижать дальность/roughness ради FPS. Future
wet/glossy receivers могут переиспользовать carrier только после явного material
predicate и отдельного performance/visual gate; не расширять water predicate неявно.

#### Water voxel-reflection receiver refinement — HUMAN PENDING

Термин `frozen` уточнён: READY texture snapshot не обновляется по block edits и не
имеет per-frame upload, но полный replacement build запускается после выхода камеры
за 32-block per-axis guard. Это snapshot-built/recentered voxel reflection, не planar
reflection. Текущая topology также не совпадала со старым audit: один `RGBA16F`
radiance resource, один syntactic `texture3d.sample` внутри bounded 40-step vertex
cone loop, без Cartesian moment texture. Следовательно, это до 40 runtime samples на
water vertex, а не «ровно два lookup».

Старая receiver-композиция сначала вычисляла общий
`metallumEvaluateEnvironmentV1(...)`, затем для water заменяла весь этот term через
`mix(environmentTerm, coarseRGB, confidence * (0.08 + 0.92 * F))` и прибавляла его к
`color.rgb`. Direction был заранее выбран в vertex как `reflect(viewRay, up)`; wave
normal влиял на Fresnel, но не менял coarse reflection direction. При этом material
environment/sun GGX добавлялись отдельным L8 block, а framebuffer alpha/body
transmission не получали complementary Fresnel. Поэтому broad world RGB выглядел как
окрашенная diffuse-подсветка, а дно оставалось слишком сильным на grazing angles.

Refined carrier сохраняет тот же vertex cone trace/resource и добавляет только второй
`float4` varying: `coarseRGB/confidence` плюс `flatTraceDirection/roughness`. Fragment
строит `Rwave = reflect(-V, Nprocedural)`, переводит его в world, считает bounded
alignment lobe (`roughness=0.28`, floor `0.45`), затем
`W=clamp(confidence*directionalResponse,0,1)`. Аналитическое environment заменяется
через `mix(analyticEnvironment, max(coarseRGB,0), W)` до единственного water Fresnel.
Для body используется `T=1-max(F)`: `color.rgb*=T`, prepared diffuse albedo `*=T`,
`color.a=1-(1-color.a)*T`. Sun highlight и local clustered GGX остаются отдельными.
Contribution-only теперь opaque и показывает только
`max(coarseRGB,0)*confidence*directionalResponse`, не final water color.

Generated GLSL -> SPIR-V -> MSL gate:

| | OLD | REFINED |
|---|---:|---:|
| vertex SHA-256 | `ce76bdcf7244...` | `24acff3bcc20...` |
| fragment SHA-256 | `be5bbc4134c8...` | `30aa7182fd7f...` |
| vertex chars | 11,581 | 11,879 |
| fragment chars | 143,064 | 147,370 |
| user varyings vertex/fragment | 9/9 | 10/10 |
| reflection resources/sample sites | 1/1 | 1/1 |
| fragment `texture3d` | 0 | 0 |

Delta: +298 vertex chars, +4306 fragment chars (~3.0%), exactly one additional
`float4` varying. Generated source cannot prove physical register occupancy; runtime
A/B is the occupancy/cost gate.

Immutable-fixture visual receipts, all `3024x1964`, Advanced/Balanced, MetalFX OFF,
same route pose/time/weather between OLD and NEW:

| view | OLD | NEW production | NEW contribution-only |
|---|---|---|---|
| broad lake/shore | `voxel-reflection-house-v1/20260825T081052Z-...-voxel-house-final-production-v6-off.png` | `voxel-reflection-refinement-v1/20260825T133724Z-...-water-refine-wide-final-r028-off.png` | `.../20260825T133827Z-...-wide-final-contribution-r028-off.png` |
| top-down | `.../20260825T132001Z-...-topdown-old-off.png` | `.../20260825T133931Z-...-topdown-final-r028-off.png` | `.../20260825T134048Z-...-topdown-final-contribution-r028-off.png` |
| low/red landmark | `.../20260825T132132Z-...-low-red-old-off.png` | `.../20260825T133338Z-...-low-red-new-v3-r028-off.png` | `.../20260825T134203Z-...-low-red-final-contribution-r028-off.png` |
| grazing/late sun | `.../20260825T132403Z-...-grazing-old-v2-off.png` | `.../20260825T134306Z-...-grazing-final-r028-off.png` | `.../20260825T134408Z-...-grazing-final-contribution-r028-off.png` |

Observed, без объявления subjective acceptance: red landmark остаётся spatially
localized, но более размытым; broad yellow/green shore bands значительно ослаблены;
top-down transparency сохранена; grazing water получает более сильную reflection/
alpha и менее доминирующее дно. Contribution diagnostic показывает, что coarse field
остаётся low-frequency. Static captures не выявили явных vertex/probe facets, но
shimmer/swimming требуют human motion review.

Deterministic motion receipt
`20260825T134833Z-...-water-refine-motion-final-r028-single-window-off`:
initial `[176,0,80]` READY, ровно один per-axis recenter в `[144,0,80]`, replacement
`512/512 READY`, measured admission READY, `COMPLETE`, zero timing drops, no measured
reflection rebuild. Первоначальный 600-frame/2-window report сохранён как rejected:
динамический маршрут дал разные renderer-generation declarations между окнами;
single-window 300-frame receipt является валидным lifecycle evidence, но не видео-
доказательством отсутствия shimmer.

Tier C detail-OFF, два независимых `1800+3000` run, source
`dda14fd9a9c3a17de91a8e32a9ecbb3e6cbda446eefea484c28e036fc85466d2`, artifact
`bcdbe67bf714712736b49fa6151f01c89746e811b98cc454e23f927ff352da46`, Advanced/
Balanced, MetalFX/VSync OFF, READY, `COMPLETE`, zero drops:

| state | run FPS | mean FPS | run GPU p95 ms | mean GPU p95 ms |
|---|---|---:|---|---:|
| refined ON | 74.091 / 74.140 | 74.116 | 16.273 / 16.287 | 16.280 |
| refined source OFF | 76.266 / 77.272 | 76.769 | 15.706 / 15.532 | 15.619 |

Против прежней current-prototype ON пары (`75.510 FPS`, `15.967 ms` mean GPU p95)
refinement даёт `-1.847% FPS`, `+0.313 ms / +1.958% GPU p95`: GOOD по visual-task
порогу `<=2%`, но у самой границы. Полная refined reflection цена против OFF той же
source: `-3.456% FPS`, `+0.661 ms / +4.231% GPU p95`. Detailed stage receipts дают
indicative translucent `3.866 -> 4.161 ms avg`, `4.518 -> 4.765 ms p95`; old side
имеет только два 300-frame measured windows, поэтому это attribution, не отдельный
release gate. Короткий `600+600` refined screen с GPU p95 `17.347 ms` отклонён как
неустоявшийся и не используется в решении.

Cloud feasibility audit: текущий R8 cloud-shadow texture — уже интегрированная вдоль
sun direction transmittance и не подходит как arbitrary water reflection color.
Позднее water-only cloud reflection можно сделать без voxel field: пересечь water
reflection vector с cloud layer/slab и читать отдельное 2D coverage/density source с
global cloud tint/opacity. В этой итерации cloud production code не добавлялся.

**Решение:** сохранить refinement default-OFF для human review. Не усиливать coarse
RGB и не делать поле резче: оставшийся tint/parallax — ограничение единственного
vertex trace direction и coarse spatial field. Один следующий эксперимент после
human review — заменить single-direction carrier на bounded two-lobe directional
representation при неизменном fragment zero-volume-read contract; не добавлять
planar/SSR/temporal/cloud/live updates.

#### Grazing shoreline and dawn-color confidence correction — HUMAN PENDING

Последующий human review подтвердил, что top-down/дальний refined receiver заметно
лучше, но почти горизонтальный взгляд у поверхности воды всё ещё превращал песок в
широкие грязно-жёлтые полосы. Отдельный редкий рассветный ракурс давал однородный
мутно-розовый wash. Source audit выявил две связанные причины: минимальный
directional response `0.45` не учитывал, что двухблочный voxel cell особенно плохо
представляет почти горизонтальный cone ray, а обычный Schlick доводил весь rough
environment lobe до идеального зеркала при `NdotV -> 0`.

Исправление не меняет поле, vertex trace, ABI, ресурсы или прямой specular. При
включённом `Representation Confidence` fragment теперь умножает directional response
на bounded horizon confidence: `0.30` при elevation `<=0.035`, плавно до `1.0` при
`>=0.18`. Это сохраняет сильные horizon landmarks, но не позволяет одному shoreline
cell занимать длинную полосу воды. Общий environment/body composition использует
roughness-aware Schlick с grazing limit `max(1-roughness,F0)`; при coarse roughness
`0.28` предел равен `0.72`, поэтому низкочастотное рассветное небо не вытесняет всё
тело воды. Sun GGX и local clustered GGX остаются отдельными и неизменными.

Generated MSL: vertex SHA и размер не изменились
(`7120b44b6c...`, 15,327 chars), fragment стал `0e97ea58d4b6...`, 148,774 chars;
varyings остались `10/10`, одна syntactic vertex `texture3d.sample`, fragment volume
resources/reads — ноль. Полный `./gradlew build` прошёл 97 задач.

Детерминированный `reflection-house-voxel-grazing-v1` capture текущего кандидата:
`run/lighting-reference/l0/20260825T161949Z-...-grazing-horizon-fix-capture-off.png`.
Против прежнего exact-route capture `20260825T134306Z-...-grazing-final-r028-off.png`
широкие жёлтая/зелёная/красная shoreline-полосы существенно ослаблены; top-down
transmission в ближней части кадра сохранён. Это fixture observation, не human
acceptance пользовательского рассветного ракурса.

Короткий одинаковый `600+600` Advanced/Balanced screen, field READY, MetalFX OFF:
confidence OFF `85.028 FPS`, GPU p95 `14.4943 ms`; refined ON `85.336 FPS`, GPU p95
`14.2929 ms`. p99 изменился `15.4443 -> 15.8807 ms`, поэтому результат считается
Tier-B/noise-level и доказывает только отсутствие явной большой регрессии, не
production improvement. **Решение:** оставить узкий receiver fix и передать точные
water-level sand/dawn ракурсы на повторную human review; не ослаблять redstone или
весь world reflection глобальной strength-константой.

#### Rough reflection continuity follow-up — HUMAN PENDING

Human review следующего кандидата выявил, что песчаное отражение местами распадается
на горизонтальные полосы «есть/нет». Причина находилась в receiver, а не в voxel
field: при roughness `0.28` прежний squared-roughness directional lobe имел ширину
около `0.043`, поэтому обычный procedural wave slope пересекал узкий `smoothstep`.
Кроме того, тот же fragment wave повторно уменьшал representation confidence через
`min(referenceElevation, reflectedElevation)`, то есть одна волна дважды гасила
coarse reflection.

Исправление разделяет семантику сигналов. Representation confidence теперь зависит
только от стабильного vertex-trace `referenceElevation`; fragment wave остаётся лишь
в directional alignment. Lobe использует roughness-linear bounded footprint
`max(roughness * 0.55, 0.12)`: он сохраняет мягкую wave modulation, но не вырезает
отражение в ноль между соседними гребнями. Field, trace, ABI, texture resources,
varyings, Fresnel/composition и direct sun/local GGX не менялись.

Generated MSL сохранил vertex SHA `7120b44b6c01...` и размер `15,327` chars;
fragment SHA стал `8bb21901f488...`, размер уменьшился `148,774 -> 148,642` chars.
Varyings остались `10/10`, vertex содержит одну syntactic reflection sample site,
fragment volume resources/reads — ноль. Targeted reflection/shader tests и полный
`./gradlew build` прошли; build выполнил 97 задач.

Exact-route visual comparison: прежний candidate
`run/lighting-reference/l0/20260825T161949Z-...-grazing-horizon-fix-capture-off.png`;
continuity candidate
`run/lighting-reference/l0/20260825T164847Z-...-grazing-continuity-candidate-off.png`.
Новый capture имеет Advanced/Balanced admission PASS, reflection field READY и все
три quality-флага ON. Он показывает непрерывную широкую rough response вместо
жёстких wave holes, сохраняя мягкие водные полосы. Exact night/sand clipboard pose
не воспроизведена, поэтому окончательная оценка остаётся за human review.
