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
