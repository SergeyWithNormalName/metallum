# METALFX TEMPORAL UPSCALE 22 AUG

Дата проверки: 22 августа 2026 года

Стенд: MacBook Pro 14-inch (MacBookPro18,3), Apple M1 Pro, 16 GPU cores, 16 GB unified memory, встроенный дисплей ProMotion 120 Hz, macOS 27.0 (26A5416b)

Целевой HDR output: 3024×1964, `RGBA16Float`
Основной проверенный renderer commit: `6e69586afa85d17173aa7dba8b82f747f6b8488b`; последующие commits до `c8808b0` не меняют проверенные Temporal/FI source files.

## Краткий вердикт

1. **MetalFX Temporal Upscale имеет смысл не только при 50% input.** Apple API не задаёт обязательный пресет 50%. На этом M1 Pro scaler сообщил поддерживаемый диапазон `output/input = 1.0...3.0`, то есть 70% является штатным поддерживаемым input scale.
2. **70% в нашей тяжёлой HDR benchmark-сцене дал реальную производительность:** 42,71 мс GPU / 25,39 FPS против 54,33 мс / 19,30 FPS в native и 45,13 мс / 23,76 FPS при 75%. Это +31,5% throughput против native и +6,8% против 75%.
3. **При этом 70% не является универсально выгодным.** В более раннем benchmark route 75% был практически в ноль: сэкономленное время world render почти целиком съедалось самим Temporal resolve и temporal inputs. Точка окупаемости зависит от сцены и от того, насколько renderer pixel-bound.
4. **50% остаётся самым надёжным performance preset, а не единственным осмысленным.** В текущем live-контроле он дал 31,56 мс / 35,72 FPS: большой выигрыш против native, но даже 40 real FPS в этой сцене не достигнуты, а GPU p95 находился около 33,4 мс.
5. **Сама production-интеграция Temporal сделана по публичному контракту Apple.** Она подаёт low-resolution color/depth/motion/reactive mask, корректный jitter и history reset, получает display-resolution output и не запускает Spatial второй раз. Крупного очевидного «костыля», который можно удалить и бесплатно ускорить Temporal без потери качества, не найдено.
6. **Предложенный режим “Temporal + FI, причём FI работает до upscale в 50%” технически собрать можно, но quality-preserving и документированным решением он не является.** FI создаёт midpoint color, но не создаёт соответствующие midpoint depth, motion, reactive mask и jitter/history state для следующего Temporal resolve.
7. **Правильная Apple-схема Temporal+FI уже реализована:** existing Temporal scaler передаётся в `MTLFXFrameInterpolatorDescriptor.scaler`. При 50% input и native output она занимает 21,87 мс mean / 22,40 мс p95 только на MetalFX critical path. Для 40→80 остаётся примерно 3,1 мс mean на всю игру, HDR composite и presentation — на M1 Pro это нереально.
8. **Режим 30→60 остаётся вычислительно возможным только в более лёгких сценах, но сначала требует исправить FI presentation cadence.** Режимы 40→80 и 60→120 при native FI output следует отклонить для M1 Pro.
9. **Ничего в production implementation не изменено.** Добавлять 70% preset преждевременно без Tier C проверки качества в движении, а low-resolution-FI-before-Temporal не проходит одновременно quality и performance gates.

Итог: **70% Temporal — нормальная, иногда полезная точка, а не бессмысленный полупресет. Но объединять её или 50% с FI следует только штатным linked-способом Apple, где FI всё равно формирует native output; на M1 Pro такой режим годится максимум как будущий 30→60 experiment, а не как 40→80 или 60→120.**

## Что говорит документация Apple

Apple формулирует критерий не как «используйте только 50%», а как простой budget test: low-resolution rendering плюс MetalFX resolve должны быть дешевле native rendering. Temporal scaler использует color, depth и motion и восстанавливает display-sized кадр с temporal history ([MetalFX overview](https://developer.apple.com/documentation/metalfx)).

[`MTLFXTemporalScalerDescriptor`](https://developer.apple.com/documentation/metalfx/mtlfxtemporalscalerdescriptor) принимает явные `inputWidth/inputHeight` и `outputWidth/outputHeight`, а для dynamic resolution — диапазон `inputContentMinScale/inputContentMaxScale`. В официальном API нет особого правила, запрещающего 70%.

В [WWDC22 MetalFX session](https://developer.apple.com/videos/play/wwdc2022/10103/) пример 1280→2560 использует 50%, но это пример конфигурации, а не требование API.

Для совместного использования эффектов [WWDC25: Go further with Metal 4 games](https://developer.apple.com/videos/play/wwdc2025/211/) рекомендует:

- выполнять Temporal upscale после jittered scene rendering;
- передавать depth/motion/reactivity;
- выполнять FI после tone mapping;
- повторно использовать те же depth/motion;
- для combined performance передавать Temporal scaler в descriptor Frame Interpolation.

Именно последняя схема важна: Apple не предлагает сначала интерполировать low-resolution color, а потом прогонять generated midpoint через отдельную temporal history.

## Аудит текущей реализации Temporal

### Presets и policy

В `TemporalScalingMode.java` сейчас определены fixed presets:

| Preset | Linear input | Pixel count относительно native |
|---|---:|---:|
| Quality | 75% | 56,25% |
| Performance | 50% | 25% |
| Ultra Performance | 40% | 16% |

Dynamic Temporal намеренно переключается только между Native 100% и Temporal 50% (`MetalFxTemporalScaling.java:19-34`). Комментарий о том, что 50% — единственный dynamic scale, выигравший в двух benchmark worlds, относится к нашей validation history, а не к ограничению MetalFX.

### Descriptor и per-frame inputs

`MetallumNative.swift:6201-6334` создаёт нормальный `MTLFXTemporalScalerDescriptor`:

- color/output: `RGBA8Unorm` или `RGBA16Float`;
- depth: `Depth32Float`;
- motion: `RG16Float`;
- reactive mask: `R8Unorm`;
- fixed input/output extents для fixed presets;
- content-scale properties для dynamic resolution;
- auto exposure отключён, потому что renderer передаёт собственный `preExposure`;
- asynchronous optimized-scaler initialization включена, чтобы resize/DRS transition не компилировал scaler синхронно на render thread.

`MetallumNative.swift:6404-6560` на каждом кадре передаёт color, depth, motion, reactive mask, реальные content dimensions, pre-exposure, jitter, reversed-Z и reset history. Fixed preset использует настоящие render-sized textures без дополнительного full-size color pack. Dynamic preset держит физические display-sized inputs и копирует в них активный rect — это соответствует dynamic-content API, но добавляет небольшую цену.

`MetallumNative.swift:12975-13029` выполняет Temporal один раз и получает display-sized output. Код `:13030-13041` специально не запускает Spatial после успешного Temporal, потому что это уничтожило бы temporal result и добавило второй full-screen upscale.

### Что можно оптимизировать без потери качества

Не обнаружено крупной ошибки уровня «двойной upscale», wrong-size fixed textures, CPU readback или лишнего пересоздания scaler каждый кадр.

Измеренная стадия `temporal inputs` занимала около 0,21 мс при 50%, 0,37 мс при 70% и 0,41 мс при 75%. Это не ноль, но и не источник 5–7-мс цены MetalFX. Удалять motion/reactive inputs ради экономии нельзя: это ухудшит качество именно в движении и disocclusions.

Production использует `requiresSynchronousInitialization = false`. Microbenchmark для steady-state использовал `true`, чтобы исключить промежуточный scaler и измерить уже оптимизированный kernel. Это не признак ошибки production: асинхронный режим нужен против transition hitch; сравнивать надо после завершения оптимизации.

## Чистая цена Temporal scaler

Изолированный Swift probe создавал настоящий `MTLFXTemporalScaler`, private ненулевые `RGBA16Float`, `Depth32Float`, `RG16Float` и `R8Unorm` textures, выполнял 30 warm-up и 240 measured encodes. Output во всех случаях был 3024×1964. Это Tier B measurement чистого MetalFX, не FPS игры и не визуальная приёмка.

| Linear input | Input extent | Pixel count | Mean, мс | p95, мс |
|---:|---:|---:|---:|---:|
| 100% | 3024×1964 | 100% | 9,240 | 9,520 |
| 90% | 2722×1768 | 81% | 8,398 | 8,684 |
| 80% | 2419×1571 | 64% | 7,264 | 7,666 |
| 75% | 2268×1473 | 56,25% | 6,889 | 7,192 |
| **70%** | **2117×1375** | **49%** | **6,447** | **6,856** |
| 67% | 2016×1309 | 44,9% | 7,223 | 7,745 |
| 60% | 1814×1178 | 36% | 5,551 | 5,946 |
| 50% | 1512×982 | 25% | 4,885 | 5,330 |
| 40% | 1210×786 | 16% | 4,231 | 4,739 |

Выводы из этой таблицы:

- У Temporal есть большой output-sized floor: уменьшение input с 75% до 70% экономит у самого scaler только около 0,44 мс.
- Цена всё же не полностью фиксирована: 50% дешевле 70% примерно на 1,56 мс.
- 70% — это уже 49% native pixels, поэтому renderer способен сэкономить заметно больше, чем кажется по разнице «100 против 70».
- Необычная регрессия ровно на 67% показывает, что нельзя выбирать произвольный процент по формуле: конкретные округлённые extents могут попасть в менее удачный внутренний kernel/alignment path. Каждый новый preset надо измерять на точных размерах.

Probe: `/private/tmp/temporal_scale_probe_22aug.swift` (в production repository не добавлялся).

## Live A/B в Minecraft

### Условия

- изолированный worktree на `6e69586`;
- `hdrtest-static-v1`, frozen deterministic route;
- built-in 3024×1964@120, exclusive fullscreen;
- HDR scene output, `RGBA16Float`, Advanced Balanced lighting;
- VSync off, cap 260;
- 600 presented warm-up + 600 measured frames;
- detailed per-stage GPU timestamps.

### Результаты

| Режим | Render extent | GPU mean, мс | GPU p95, два окна, мс | FPS | 1% low | World opaque, мс | MetalFX, мс |
|---|---:|---:|---:|---:|---:|---:|---:|
| Native | 3024×1964 | 54,329 | 57,884 / 57,342 | 19,304 | 15,354 | 43,619 | — |
| Temporal 75% | 2268×1473 | 45,130 | 48,304 / 47,551 | 23,761 | 18,819 | 28,201 | 7,381 |
| **Temporal 70%** | **2117×1375** | **42,708** | **45,119 / 45,378** | **25,386** | **19,944** | **26,028** | **7,161** |
| Temporal 50% | 1512×982 | 31,558 | 33,534 / 33,365 | 35,715 | 25,717 | 16,979 | 5,768 |

Относительно native:

- 75%: GPU −16,9%, throughput +23,1%;
- 70%: GPU −21,4%, throughput +31,5%;
- 50%: GPU −41,9%, throughput +85,0%.

Относительно 75% режим 70% дал:

- −2,421 мс GPU mean;
- −5,4% GPU time;
- +1,625 FPS;
- +6,8% throughput.

Поэтому утверждение «Temporal имеет смысл исключительно при 50%» неверно. Верное утверждение другое: **чем легче сцена и чем меньше pixel-bound её native frame, тем ниже должен быть input scale, чтобы окупить 5–7 мс reconstruction cost.**

### Ограничение live-артефактов

Native OFF report полностью прошёл validator. В Temporal 75/70/50 последний 300-frame telemetry window был записан после renderer teardown и получил `clustered_lighting.frame_id = 0`; harness отверг reports с `Advanced clustered-lighting generation/frame must be positive`. При этом оба 300-frame measure windows содержат полные, согласованные GPU/stage данные, `MEASURE_END` и `COMPLETE`, а одинаковый teardown-only дефект воспроизвёлся во всех трёх Temporal runs.

Поэтому таблица используется как **Tier B directional evidence с rejected harness artifact**, а не как production PASS. Она надёжно отвечает, есть ли измеримый эффект 70%, но не заменяет Tier C quality/cadence acceptance.

Raw reports в isolated worktree:

- `20260822T144120Z-g6e69586afa85-dirty-temporal-native-control-22aug-off.raw.jsonl` — validated OFF control;
- `20260822T143638Z-g6e69586afa85-clean-temporal-75-live-ab-22aug-temporal_quality.raw.jsonl`;
- `20260822T143905Z-g6e69586afa85-dirty-temporal-70-live-ab-22aug-temporal_quality.raw.jsonl`;
- `20260822T144442Z-g6e69586afa85-clean-temporal-50-live-control-22aug-temporal_performance.raw.jsonl`.

### Почему более ранний 75% почти не выигрывал

В benchmark от 15 августа на другом состоянии renderer были получены такие selected-stage значения:

- Native: `WORLD_OPAQUE 20,516 мс`;
- Temporal 75%: `WORLD_OPAQUE 13,147 + MetalFX 6,799 + temporal diagnostic 0,507 = 20,453 мс`;
- Temporal 50%: `WORLD_OPAQUE 8,029 + MetalFX 5,283 + temporal diagnostic 0,243 = 13,555 мс`.

Это хороший пример scene dependence: 75% может быть почти в ноль, 70% — умеренно полезным, а 50% — устойчиво выгодным. Нельзя объявлять один процент лучшим для всех сцен только по isolated scaler timing.

## Разбор режима Temporal Upscale + FI

### Вариант A: штатная linked-схема Apple

Порядок работы:

`low-resolution real render + depth/motion/reactive → Temporal scaler → full-resolution real color → linked FI → full-resolution generated color`

В Metallum он уже реализован:

- FI descriptor получает render-sized `inputWidth/inputHeight` и display-sized `outputWidth/outputHeight` (`MetallumFrameInterpolationCoordinator.swift:1287-1297`);
- fixed Temporal scaler из renderer workspace передаётся в `descriptor.scaler` (`:1303-1315`);
- FI получает low-resolution depth/motion, но real/generated color и FI output остаются display-sized;
- UI композится отдельно, чтобы текст и HUD не интерполировались.

Это правильная quality-preserving архитектура. Но её стоимость определяется главным образом native FI output:

| Linked path | Temporal input | FI output | Mean, мс | p95, мс |
|---|---:|---:|---:|---:|
| Temporal 50% + FI | 1512×982 | 3024×1964 | 21,87 | 22,40 |
| Temporal 40% + FI | 1210×786 | 3024×1964 | 20,61 | 20,99 |

#### Бюджет 40→80

При 40 source FPS доступно 25,00 мс на real-frame period. Linked MetalFX path при 50% оставляет:

`25,00 − 21,87 = 3,13 мс mean`

В эти 3,13 мс должны поместиться Minecraft world render, HDR passes, UI, command submission, drawable/presentation и запас на p95. Это невозможно: один только current 50% real frame без FI занял 31,56 мс mean / около 33,4 мс p95 и дал 35,72 FPS.

Следовательно, **Temporal не поднимает текущую тяжёлую сцену с 30 до 40 real FPS, а добавление FI не превращает её в 80 FPS.**

#### Бюджет 30→60

При 30 source FPS доступно 33,33 мс. На более лёгких сценах linked MetalFX critical path способен поместиться в этот период, и прежние runs действительно создавали 297–300 generated frames на 300 real. Но текущая presentation pipeline проваливает on-glass cadence, а в нынешней тяжёлой сцене Temporal 50% уже находится у p95-границы 30 FPS до FI.

Поэтому 30→60 — не готовый второй режим, а отдельная экспериментальная цель после исправления pacing и строгого scene gate.

### Вариант B: буквальный FI в 50% до Temporal

Порядок, предложенный в вопросе:

`low-res previous/current color → low-res FI midpoint → Temporal upscale real и generated → native display`

Чтобы оба показанных кадра имели native resolution, нужен один Temporal resolve для real и ещё один для generated. Измеренный critical path:

| Порядок | Input/FI | Output | Mean, мс | p95, мс |
|---|---:|---:|---:|---:|
| Temporal(real) + low-res FI + Temporal(generated) | 1512×982 | 3024×1964 | 16,30 | 17,10 |
| То же при 40% | 1210×786 | 3024×1964 | 11,91 | 12,19 |

На первый взгляд 16,30 мс выглядит дешевле linked 21,87 мс. Но это не готовый выигрыш:

1. FI возвращает interpolated color, но не midpoint depth.
2. FI не возвращает midpoint motion vectors.
3. FI не создаёт midpoint reactive mask.
4. Generated midpoint не имеет корректной jitter sample/history identity для Temporal scaler.
5. Один scaler, в который попеременно подаются real и generated frames, смешивает разные temporal cadences и history semantics.
6. Два отдельных scaler histories всё равно не получают корректные auxiliary данные для generated midpoint и удваивают workspace/history memory.

Подставить auxiliary textures текущего real frame означает ожидаемые ghosting, disocclusion errors и нестабильность history. Подменить второй Temporal обычным Spatial можно, но это уже явная потеря качества и пользователь просил обойтись без собственных костылей.

По performance этот порядок тоже не спасает 40→80: даже isolated MetalFX p95 занимает 17,10 из 25 мс, а current 50% world render один занимает около 16,98 мс mean, не считая прочих стадий. Полный critical path не помещается.

Вердикт: **не реализовывать low-resolution FI before Temporal как production mode.** Это не документированный combined flow Apple, не quality-preserving и не проходит M1 Pro budget.

## Какими должны быть два пользовательских режима

Если цель — оставить интерфейс простым, технически честное разделение выглядит так.

### 1. Adaptive Spatial Upscale

Оставить существующий режим как дешёвый throughput-oriented scaler. Он не требует temporal history и подходит для динамического снижения resolution, когда важнее FPS и стабильность.

### 2. Adaptive/Quality Temporal Upscale

Базовый второй режим должен быть **Temporal без обязательного FI**:

- 70% можно рассматривать как будущий Balanced/Quality preset: он доказал performance value в тяжёлой сцене;
- 50% оставить performance/dynamic fallback, потому что он имеет более надёжную межсценовую окупаемость;
- FI показывать отдельным experimental sub-option, а не делать обязательной частью Temporal;
- на M1 Pro ограничить FI целевым профилем 30→60 и только штатным linked scaler path;
- не обещать 40→80 или 60→120 при native FI output.

Если продуктово нужны ровно два пункта без sub-options, то второй пункт `Temporal + FI` сейчас вводил бы пользователя в заблуждение: в тяжёлых сценах Temporal 50% не держит 40 real FPS, а native-output FI не держит нужный combined budget и cadence. Честнее назвать его просто `Temporal Upscale`, а FI оставить отдельной experimental capability до завершения pacing work.

## Что можно сделать дальше

### 70% Temporal preset

Добавить его технически просто: API, jitter phase calculation и fixed workspace поддерживают фактический input/output ratio. Но текущих данных недостаточно для production acceptance, потому что не проверены:

- тонкие линии и foliage в движении;
- particles и transparencies;
- water/reflection disocclusions;
- emissive edges в HDR;
- moving entities и entity motion vectors;
- camera cuts/history reset;
- качество относительно 75% и 50% на реальном gameplay route.

Поэтому в этой работе preset не добавлялся. Следующий правильный gate: alternating 75/70/75/70 live captures на одной движущейся трассе, плюс 600-frame valid performance runs после исправления teardown telemetry.

### Temporal+FI 30→60

Не менять разрешения и descriptor. Сначала исправить уже найденные FI presentation issues:

- completion-driven ownership пары real/generated;
- drawable lifetime;
- admission feedback, который тормозит source producer;
- on-glass phase по `presentedTime`.

Acceptance остаётся строгим: `ACTIVE`, не менее 297/300 generated, zero failures/out-of-order/backpressure и cadence misses в пределах health gate, затем отдельный visual review.

### 120-Hz experiment

Для M1 Pro он требует снижать не только renderer input, но и **FI output**. Предыдущий probe показал 10,67 мс mean / 11,54 мс p95 для linked Temporal50+FI при output около 2048×1330. Это лишь кандидат с явным resolution compromise; native 3024×1964 output следует считать `DO NOT RETRY` для 60→120.

## Финальные ответы

- **Temporal имеет смысл только при 50%?** Нет. 70% штатно поддерживается и в текущей тяжёлой сцене дал +31,5% throughput против native.
- **Будет ли 70% всегда иметь смысл?** Нет. На более лёгком или CPU/geometry-bound workload fixed reconstruction cost может съесть выигрыш. 75% уже показывал почти нулевой net benefit.
- **Есть ли в нашей Temporal implementation явная бесплатная оптимизация?** Крупной — нет. Fixed path и inputs соответствуют Apple contract; auxiliary cost мал по сравнению с самим resolve.
- **Стоит ли добавить 70%?** Как кандидат Balanced/Quality — да; как принятый production preset — только после live visual acceptance и валидного повторного A/B.
- **Реализуем ли режим Temporal + FI?** Штатный linked режим уже реализован, но на M1 Pro с native FI output он годится максимум для будущего 30→60 после pacing fixes.
- **Можно ли сделать FI в input resolution Temporal, затем upscale?** Вычислить можно, но корректного quality-preserving temporal protocol для generated midpoint нет, а 40→80 budget всё равно не сходится. Не реализовывать.
- **Получится ли “30 real → 40 real через Temporal → 80 с FI” в текущих реалиях?** Нет: Temporal50 в проверенной тяжёлой сцене дал 35,72 FPS без FI, а combined native-output FI оставляет недостаточный GPU budget.
- **Какой продуктовый выбор сейчас честный?** Adaptive Spatial как performance mode; Temporal как quality reconstruction mode; FI отдельно и experimental, с первым целевым профилем 30→60.
