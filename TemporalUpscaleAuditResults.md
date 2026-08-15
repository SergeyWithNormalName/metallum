# Аудит входных данных Temporal Upscale

Дата аудита: 15 августа 2026
Область: текущий рабочий каталог, путь Java -> FFM ABI v3 -> Swift/Metal -> MTLFXTemporalScaler.
Изменений реализации в рамках аудита не вносилось.

## Итоговый вердикт

Данные нельзя назвать полностью качественными и готовыми к visual acceptance. Базовый путь для статичного мира сделан хорошо: форматы, размерности, jitter, reset, Dynamic input-content и GPU-синхронизация согласованы и покрыты автоматическими проверками. Однако есть два актуальных P1-дефекта данных для Temporal:

1. В реальной игре в scaler не попадают per-object motion vectors сущностей: production не записывает ни одного draw-пакета. Движущиеся сущности получают только camera/static-depth motion.
2. Камерная репроекция теряет малые перемещения на больших абсолютных координатах мира, потому что разность двух координат типа double сначала сужается до float, а вычитается уже в MSL.

Также есть незавершённый latent-путь entity replay, частичная reactive-семантика и отсутствие живого визуального подтверждения. Поэтому текущая оценка такая:

| Область | Оценка |
| --- | --- |
| Статичный непрозрачный мир около обычных координат | Хорошо спроектирован и автоматически проверен |
| Движущиеся сущности и часть динамической геометрии | Не готово: требуемые данные не поступают |
| Далёкие координаты мира | Некорректно из-за потери точности camera delta |
| Alpha/translucent/particles/reactive semantics | Частично покрыто, качество не доказано |
| Полное визуальное качество в Minecraft | Не подтверждено live-плейтестом |

В локальной конфигурации запуска run/config/metallum-metalfx-temporal.properties сейчас стоит mode=off. То есть именно этот запуск вообще не подаёт кадры в MTLFX. Ниже оценивается реализованный путь, который используется после включения и успешного admission Temporal.

## Что именно было прослежено

Поток данных выглядит так:

1. GameRendererMetalFxMixin получает итоговые camera/projection данные, применяет jitter до vanilla post-projection и публикует FrameCapture.
2. MetalDevice.publishFrameState собирает FrameState, FrameStateTracker формирует previous/current пару и reset reasons, а FrameStateAbi передаёт 848-байтный ABI v3 в Swift.
3. Между world depth и UI depth clear MetalDevice.encodeTemporalDiagnostics создаёт motion/reactive inputs и, если есть пакеты, должен дорисовать object motion.
4. metallum_encode_temporal_diagnostics_v2 в Swift строит static camera motion из D32 depth, current/previous matrices, camera positions и history depth.
5. encodeTemporalScale передаёт color, depth, RG16Float motion, R8Unorm reactive, jitter, reset и reversed-Z флаг в MTLFXTemporalScaler.
6. Temporal output используется непосредственно в presentation; Spatial после Temporal не запускается.

Ниже слово «проверено» означает проверку текущего исходного кода и/или успешный автоматический native/GPU test. Это не означает визуальное доказательство в настоящем клиенте.

## Карта входов MTLFX

| Вход | Фактический источник и контракт | Оценка |
| --- | --- | --- |
| Color | Render-sized world color; допускаются RGBA8Unorm и RGBA16Float. В Dynamic low-resolution прямоугольник GPU-blit копируется в display-sized private input; UI не смешивается с world input. | Хорошо. Формат, устройство, размеры и usage проверяются до encode. |
| Depth | Главный D32Float depth world target. В Dynamic тот же active low-resolution прямоугольник копируется в display-sized private depth. В scaler передаётся reversed depth. | Хорошо для статичного мира. Формат и размер жёстко валидируются; CPU readback нет. |
| Static motion | RG16Float, вычисляется из depth, inverse current view/projection, previous view/un-jittered projection и camera delta. Семантика: previousNdc - currentNdc, в input/render pixels, X вправо, Y вниз. | Хорошо около обычных координат; P1-дефект на больших абсолютных координатах описан ниже. |
| Object motion | Планировался отдельный replay draw-пакетов поверх static motion. | Не поступает в production: P1. |
| Reactive mask | R8Unorm. Базовый pass помечает reset, sky/invalid depth, out-of-frame, invalid reprojection и depth disocclusion. Материальный L8 seed сохраняется через max, а не затирается. | Базовая защита хорошая; authoring семантики неполный: P2. |
| Jitter | Halton sequence, амплитуда в pixel space; фаза считается из фактического render/display отношения; projection jitter применяется до post-projection. | Хорошо. Знаки и pixel conversion покрыты GPU validation. |
| History/reset | Previous depth history и previous matrices; reset на первом кадре, resize, internal scale, renderer generation, world/dimension, camera/projection, output и других discontinuity. | Хорошо. Reset-кадр использует current как previous, поэтому не должен reprojection-ить старую историю. |
| Pre-exposure | В текущем frame state передаётся нейтральное 1.0. MTLFX использует этот параметр только если входной color заранее умножен на фиксированную exposure. | Не является найденной ошибкой: нейтральное значение корректно при отсутствии pre-exposed color contract. |

## Что подтверждено как корректное

### Форматы, размерности и Dynamic input-content

MetallumNative.swift:6029-6163 создаёт MTLFX descriptor с RGBA8Unorm/RGBA16Float color/output, D32Float depth, RG16Float motion и R8Unorm reactive. Перед каждым encode Swift повторно проверяет format, device ownership, размерности и требуемые MTLTextureUsage.

Для Dynamic физические input textures имеют display extent, а active render rectangle задаётся через inputContentWidth/Height. Это правильная модель API. Масштаб, передаваемый в inputContentMinScale/inputContentMaxScale, намеренно равен output resolution / input content resolution, то есть display / render, а не обратной величине. Это подтверждается локальным SDK header MetalFX. Следовательно, найденное при первичном чтении «перевёрнутое» отношение не является багом.

motionVectorScaleX/Y = 1.0 также корректны: MSL уже выдаёт векторы в пикселях входного render extent, а MTLFX ожидает именно такую семантику при масштабе 1.

### Jitter, reset и static reprojection

JitterSequence считает число фаз из реального отношения display/render, а не из приблизительного preset name. TemporalJitterProjection и MetallumTemporalDiagnostics.metal используют согласованные знаки для right-handed Minecraft projection. GPU test отдельно проверил jitter unjittering и camera-bob depth grid.

Static pass корректно:

- даёт reactive=1 и zero motion при reset, sky/invalid depth, invalid/infinite math и выходе за экран;
- сравнивает history в view-space distance, а не по нелинейному reversed-Z значению;
- применяет previous jitter только для lookup предыдущего depth, оставляя motion в unjittered reference frame;
- сохраняет L8 material reactive через максимальное значение.

### Текущие автоматические проверки

15 августа 2026 успешно завершились следующие целевые проверки:

~~~~text
./gradlew temporalContractValidation entityMotionUnitTest temporalScalingUnitTest \
  drsScalingUnitTest frameStateAbiNativeValidation motionVectorValidation \
  entityMatrixValidation temporalDiagnosticRuntimeValidation temporalScalingValidation \
  --console=plain
~~~~

Они подтвердили:

- FrameState ABI v3: 12 отрицательных кейсов и 4 точных native generation;
- production Temporal contract и mutual exclusion со Spatial;
- GPU motion-vector: 11 scalar cases и camera-bob depth grid;
- fixed presets, DRS controller и Dynamic fixed-input resize;
- runtime: warm standby, L8 reactive merge, Dynamic DRS resize, HDR precompose/UI backdrop, depth disocclusion, far view-space depth, resize/FOV и reset;
- базовую математику entity transforms.

Это сильное контрактное покрытие, но оно не тестирует реальный Minecraft draw path для entity replay, большие абсолютные camera coordinates или живую картинку.

## Найденные проблемы

### P1-1. Движущиеся сущности не дают настоящих motion vectors

**Факт.** EntityVelocityDrawRecorder.recordDraw(...) прямо документирован как намеренно inert до установки hook, который передаст реальные live MTLBuffer handles. Поиск production-кода находит вызовы recordDraw только в тестах. EntityRendererMixin открывает и закрывает entity submit scope, но не записывает draw packet.

В MetalDevice.encodeTemporalDiagnostics native replay вызывается только при packetCount > 0. В реальной игре это условие не наступает, поэтому MTLFX получает static camera/depth motion на пикселях движущихся сущностей.

**Качество.** Это не просто недостающее улучшение. Для движущихся игроков, мобов, предметов и другой динамики motion vector не описывает объектное движение. Depth disocclusion/reactive иногда подавит плохую history, но не заменит правильную per-object velocity. В частности, при движении объекта на фоне с близким или совпадающим depth возможны ghosting, trails или нестабильный detail.

**Подтверждение.**

- src/main/java/com/metallum/client/renderer/temporal/EntityVelocityDrawRecorder.java:60-66;
- src/main/java/com/metallum/mixin/render/EntityRendererMixin.java:60-118;
- src/main/java/com/metallum/client/metal/render/MetalDevice.java:2076-2106;
- уже зафиксированный проектом P1 в TECH_DEBT.md:77-83.

**Что нужно до acceptance.** Реальный hook должен передавать только отложенно-живущие, проверенные vertex/index buffers и весь pipeline state. Затем нужен live маршрут с движущимся/телепортирующимся entity и отдельная проверка motion/reactive target.

### P1-2. Потеря camera delta на больших абсолютных координатах

**Факт.** Java ABI хранит currentCameraPosition и previousCameraPosition как double. Swift сужает каждую абсолютную координату до Float при создании MetallumTemporalDiagnosticUniforms, а MSL затем вычисляет:

~~~~text
previousRelative = worldRelativeCurrent
                 + currentCameraPosition
                 - previousCameraPosition
~~~~

То есть вычисляется Float(currentAbsolute) - Float(previousAbsolute), а не точная double-разность, суженная один раз.

Это прямо видно в:

- src/main/native/MetallumNative.swift:8339-8340,8466-8475;
- src/main/metal/MetallumTemporalDiagnostics.metal:119-126.

**Воспроизводимый численный эффект.** Локальный Swift runtime был использован для того же сужения Double -> Float:

| Абсолютная X | Реальный delta X | Получившийся Float delta |
| --- | ---: | ---: |
| 1,000,000 | 0.01 | 0.0 |
| 1,000,000 | 0.05 | 0.0625 |
| 1,000,000 | 0.10 | 0.125 |
| 16,777,216 | 0.01 / 0.05 / 0.10 / 1.0 | 0.0 |
| 29,999,984 | 0.01 / 0.05 / 0.10 / 1.0 | 0.0 |

Следствие: в дальнем мире небольшое перемещение камеры либо исчезает, либо становится ступенчатым. Static motion больше не соответствует реально отрендерованному кадру, а MTLFX получает неверную историю. Это влияет на качество именно входных данных, а не только на телеметрию.

**Почему тесты это не поймали.** Runtime validation передаёт camera translation 1 около координаты 0; нет кейса с абсолютной координатой 1,000,000, 16,777,216 или границей мира.

**Исправление.** Вычислить currentCameraPosition - previousCameraPosition пока значения ещё double (Java или Swift), проверить конечность, затем передать в MSL уже относительный float3 cameraDelta. Альтернатива — high/low split. Нельзя оставлять вычитание двух независимо суженных абсолютных float. Добавить GPU/runtime tests как минимум для 1M, 16,777,216 и примерно 30M координат с субблочными delta.

### P1-3. Latent entity replay не готов к включению даже после появления пакетов

Это пока не меняет текущую картинку, поскольку P1-1 гарантирует ноль пакетов. Но его надо исправить до подключения реального hook.

1. metallum_commit_entity_velocity_replay создаёт новый render encoder и выдаёт drawIndexedPrimitives, но в этом проходе отсутствует setRenderPipelineState. Новый encoder не наследует pipeline state другого encoder, поэтому путь нельзя считать готовым к execution.
2. Entity MSL требует current jittered projection для raster position и equal depth test. Native replay заполняет currentJitteredProjection значением packet.currentUnjitteredProjection. При jittered world depth это не соответствует глубине, с которой сравнивается replay.
3. entityMatrixValidation проверяет изолированную matrix math; он не вызывает metallum_commit_entity_velocity_replay, не создаёт vertex/index buffers и не проверяет MSL/PSO/depth-equal интеграцию.

Доказательства:

- src/main/native/MetallumNative.swift:8622-8761, особенно 8733-8743;
- src/main/metal/MetallumEntityMotion.metal:37-51;
- src/test/native/EntityMatrixValidationNative.swift:1-156.

**Требование перед включением.** Создать и установить format-aware entity motion PSO, передавать действительную current jittered projection в packet/uniform, и добавить native integration test с реальными MTLBuffer, depth target, jittered raster и non-zero packet count.

### P2-1. Материальная reactive-семантика покрывает только узкий путь

Базовый diagnostic pass хорошо помечает дисокклюзию и reset. Но authored material reactive в данный момент создаётся только для Sodium terrain в Advanced fragment:

- L8ReactiveShaderPatcher прямо отклоняет любой не-Sodium-terrain shader;
- MetalCrossShaderCompiler компилирует METALLUM_ADVANCED_REACTIVE только для Sodium terrain receiver;
- MetalDevice включает L8 attachment только при одновременных Advanced world pass и active Temporal.

Следовательно, нет доказанного generic authoring пути для entity, particles, большинства translucent/cutout renderer types и прочей динамической/альфа-геометрии. Это особенно важно вместе с P1-1: когда object motion отсутствует, reactive mask — последний механизм, который может снизить доверие к history для таких пикселей.

Это P2, потому что базовая reactive защита существует, L8 seed действительно сохраняется, а не затирается. Но текущие данные нельзя назвать полными для сцены Minecraft.

### P2-2. Нет live visual acceptance

Ни один из запущенных тестов не доказывает, что в настоящем Minecraft отсутствуют shimmer, ghosting или depth-history artifacts при ходьбе, прыжке, повороте камеры и движении entity. Они не могут показать:

- фактическое количество entity replay packets;
- правильность real Metal buffer handles/vertex layout;
- картину RG16 motion и R8 reactive в игре;
- результат около границ мира;
- transparency/particles/water;
- качество в разных fixed preset и переходах Dynamic Native -> Temporal -> Native.

Поэтому «автоматические тесты проходят» нельзя интерпретировать как «данные визуально качественные».

### P3. Документация о fixed presets устарела

docs/TEMPORAL_UPSCALING_DRS.md:11-17 сообщает Quality = 18 phases и Ultra = около 33% / 72 phases. Реальный код задаёт Quality 0.75 и Ultra 0.40 в TemporalScalingMode.java:7-11, а JitterSequence выводит phase count из действительного отношения. При точных 75% и 40% это 15 и 50 фаз соответственно. Это не меняет текущие GPU inputs, но вводит в заблуждение при диагностике jitter/quality и должно быть синхронизировано отдельно.

## Рекомендированный порядок исправления

1. Исправить P1-2: вычислять camera delta до float narrowing и добавить дальние coordinate tests. Это изолированная правка static motion data.
2. Закрыть P1-1 и P1-3 как один вертикальный slice: live draw capture -> validated packet -> jitter-correct native PSO/replay -> GPU integration test -> live moving-entity validation. Не включать entity replay частично.
3. Расширить reactive authoring или явно определить безопасную стратегию для entity/translucent/particles; затем проверить, что diagnostic pass сохраняет эти seeds.
4. Обновить preset/phase documentation.
5. Выполнить visual acceptance с Temporal действительно включённым и telemetry/debug targets:
   - статичная сцена, медленная ходьба, бег, прыжок, поворот камеры;
   - entity пересекает контрастный фон, teleport и быстрые перемещения;
   - вода, cutout foliage, particles и translucency;
   - координаты около 0, 1M, 16.8M и по возможности near world border;
   - Quality, Performance, Ultra и Dynamic;
   - capture RG16 motion, R8 reactive, reset reason, render/display extents и resolved Temporal status.

## Итоговая граница утверждений

Можно утверждать: static Temporal input contract, форматы, размерности, Dynamic input-content, jitter/reset и базовая camera/depth reprojection имеют хорошую архитектуру и прошли целевые проверки.

Нельзя утверждать: что все данные уже качественны для реальной сцены, что движущиеся сущности имеют корректные motion vectors, что дальний мир стабилен, или что эффект визуально принят. До устранения P1-1 и P1-2 честный статус — **частично качественный static path, но не готовый полный production-quality Temporal input set**.

---

# Аудит стоимости Temporal Upscale и возможности удешевления

Дата аудита: 15 августа 2026
Область: текущий renderer path, Apple MetalFX Temporal, свежие GPU stage-метрики и сохранённые длинные benchmark-прогоны.
Изменений реализации не вносилось; этот раздел фиксирует выводы и следующий безопасный эксперимент.

## Краткий ответ

**Крупного безвредного удешевления в текущем fixed Temporal path не найдено.** Архитектура уже убирает очевидные лишние расходы: fixed preset напрямую передаёт world color/depth в MetalFX, workspace и output кэшируются и остаются GPU-private, CPU readback отсутствует, а MetalFX output сразу имеет display extent. Самый дорогой элемент — сам full-display MetalFX resolve, который невозможно существенно «оптимизировать вокруг» без отключения или замены Temporal.

Это не означает математически доказанный абсолютный optimum. Остался один локальный, правдоподобный, но пока **SPECULATIVE** кандидат: для Dynamic Temporal попеременно использовать два display-sized D32 input slot вместо отдельного depth-history copy. Он может убрать ровно один history blit в Dynamic на 50%, но:

- не действует на fixed Quality 75% и не объясняет отрицательный FPS на высоких scale;
- не затрагивает измеренные 5.28–6.80 ms самого MetalFX;
- пока не имеет ни stage-метрики именно этого blit, ни visual/Tier C доказательства эквивалентности.

Следовательно, практический вердикт такой: **архитектура не абсолютно «дальше некуда», но большого безопасного выигрыша уже не осталось.** Отрицательный FPS на 75–80% — ожидаемая точка безубыточности: экономия от меньшего world render почти равна цене full-display реконструкции. Для 65% ситуация может быть лучше, но на текущей ветке это не стандартный режим и для него нет честного измерения.

## Важное уточнение про 80% и 65%

В проверенном текущем коде публичные fixed presets такие:

| Режим | Linear render scale | Доля world pixels |
| --- | ---: | ---: |
| Quality | 75% | 56.25% |
| Performance | 50% | 25% |
| Ultra Performance | 40% | 16% |

Dynamic Temporal не является непрерывным рядом 80% -> 65% -> 50%. Его state machine переключает только между Native 100% и Temporal 50% после порогов GPU time. Это видно в src/main/java/com/metallum/client/metalfx/TemporalScalingMode.java:7-11 и MetalFxTemporalScaling.java:20-34,339-352.

Поэтому я не приписываю свежих цифр несуществующим в этой конфигурации 80% или 65%. Если они наблюдались ранее, это был старый revision, ручной/out-of-band scale или другой маршрут. Для стандартного текущего меню/benchmark route утверждение можно делать только для 75%, 50% и 40%.

## Что реально выполняется каждый кадр

### Fixed Quality / Performance / Ultra

~~~~text
world color + D32 depth @ render extent
  -> camera motion + reactive MRT diagnostic
  -> D32 copy в previous-depth history
  -> MTLFXTemporalScaler encode
  -> private output @ full display extent
  -> HDR/presentation
~~~~

У fixed пути нет Dynamic color pack и нет Dynamic depth pack: encodeTemporalScale напрямую присваивает colorTexture и depthTexture scaler-у. MTLFX output всё равно создаётся в displayWidth x displayHeight. Это подтверждается src/main/native/MetallumNative.swift:6029-6163,6233-6388.

### Dynamic Temporal в своей 50% фазе

~~~~text
world color + D32 depth @ low-resolution content rectangle
  -> D32 depth pack в display-sized private input
  -> camera motion + reactive MRT diagnostic
  -> D32 copy в previous-depth history
  -> color pack в display-sized private input
  -> MTLFXTemporalScaler encode с inputContentWidth/Height
  -> private output @ full display extent
~~~~

Эти два pack-copy нужны потому, что Dynamic API MetalFX держит физические input textures в display extent, а меняет только active content rectangle. Это корректный контракт API, а не случайная лишняя промежуточная текстура. Путь находится в MetallumNative.swift:6266-6347 и 8411-8447.

Static diagnostic — не косметическая обвязка: он создаёт RG16Float camera motion и R8 reactive, включая reset/disocclusion protection. Предыдущая часть этого аудита уже установила, что качество входов и так неполно для entity и дальних координат; удалять или прореживать этот pass нельзя считать безвредным. Entity replay сейчас не имел samples на статичном benchmark route; это не экономия, а следствие незавершённого production object-motion path.

## Свежие измерения: что именно они доказывают

Ниже — **Tier B stage attribution**, не Tier C performance verdict. Все три успешных запуска использовали один artefact 09af31824dbe109fcef3e1d8ddeddb3a1de089afd629f293dae19bd15a41d1a4, dirty source d61b0f6b070e / 65160e85932fe167573022b948ca3b900edf255a043ecb6ffcb1586cb7ff72d7, Built-in Retina 3024x1964@120, HDR, Advanced Balanced, VSync off, hdrtest-static-v1, nominal thermal state, 600 warm-up + 600 measure frames и detailed GPU timing.

| Mode | Render extent | World opaque, avg / p95 ms | MetalFX, avg / p95 ms | Diagnostic raster, avg / p95 ms | Full command buffer, p50 / p95 ms |
| --- | --- | ---: | ---: | ---: | ---: |
| OFF | 3024x1964 | 20.516 / 21.696 | — | — | 27.675 / 29.314 |
| Temporal Quality | 2268x1473 (75%) | 13.147 / 14.492 | 6.799 / 7.552 | 0.507 / 1.253 | 28.715 / 30.835 |
| Temporal Performance | 1512x982 (50%) | 8.029 / 9.319 | 5.283 / 6.035 | 0.243 / 0.657 | 21.091 / 22.818 |

Исходные артефакты:

- run/logs/metallum-benchmarks/20260815T122356Z-gd61b0f6b070e-dirty-temporal-cost-audit-off-detailed-off.raw.jsonl;
- run/logs/metallum-benchmarks/20260815T122523Z-gd61b0f6b070e-dirty-temporal-cost-audit-quality-detailed-temporal_quality.raw.jsonl;
- run/logs/metallum-benchmarks/20260815T123228Z-gd61b0f6b070e-dirty-temporal-cost-audit-performance-retry-detailed-temporal_performance.raw.jsonl.

Повторный Performance run имеет COMPLETE, no FAIL/screenshots и zero dropped timing events. Первый запуск Performance от 12:26:51 был корректно исключён: внутренний validator отверг его из-за некорректного Advanced generation/frame, поэтому он не использован ни для одной цифры.

### Как читать эти цифры

1. На 75% world opaque уменьшился на 7.369 ms против OFF. Уже измеренные MetalFX плюс diagnostic raster стоят 6.799 + 0.507 = 7.306 ms. То есть только на этой паре величин почти вся экономия world pixels уже поглощена реконструкцией; остаётся около 0.063 ms до учёта history copy и остальных стадий. Это прямо объясняет, почему высокие scale легко дают отрицательный FPS.
2. На 50% world opaque экономит 12.487 ms, а измеренные MetalFX плюс diagnostic raster стоят 5.526 ms. Пространства для выигрыша существенно больше, поэтому 50% — единственная разумная динамическая точка, а не 75–80%.
3. MetalFX уменьшился только с 6.799 ms на 75% до 5.283 ms на 50%, хотя площадь input world падает с 56.25% до 25% display area. На этой сцене цена scaler-а намного сильнее привязана к full-display reconstruction, чем к числу входных world pixels.
4. Даже измеренный diagnostic raster уже в 13.4 раза дешевле MetalFX на 75% и в 21.7 раза дешевле на 50%. Поэтому оптимизация только его shader-а не сможет решить проблему нескольких миллисекунд.

### Ограничение наблюдаемости, которое нельзя скрывать

Stage с именем temporal inputs измеряет только render pass camera-motion diagnostic: timestamp прикреплён к MTLRenderPassDescriptor в MetallumNative.swift:8492-8503. Он **не включает** preceding Dynamic depth pack и following depth-history blit. Аналогично external marker MetalFX открывается после Dynamic color pack в encodeTemporalScale. Следовательно, 0.507/0.243 ms — это точная цена diagnostic raster, но не полная цена всех Temporal input copies.

Это не меняет главный вывод: один только MetalFX уже 5.28–6.80 ms. Но не позволяет честно назвать точную стоимость каждого D32/color copy. До любой «микрооптимизации» надо добавить detailed-only stage markers отдельно для:

- temporal dynamic depth pack;
- temporal previous-depth history copy;
- temporal dynamic color pack.

Такое измерение не меняет рендеринговую семантику и является первым безопасным шагом, если будет принято решение продолжать оптимизацию.

## Длинные исторические прогоны: почему результат зависит от сцены

Старые accepted Tier C артефакты на едином тогдашнем commit 25abb2c24225 были длиннее: 3,000 measure frames и 10 независимых 300-frame windows, HDR/Balanced/VSync off/nominal. Они не доказывают текущую производительность — с тех пор source изменился, — но хорошо подтверждают саму экономику break-even:

| Маршрут | OFF FPS | Quality 75% FPS / delta | Performance 50% FPS / delta |
| --- | ---: | ---: | ---: |
| hdrtest-static-v1 | 65.293 | 59.302 / -9.18% | 84.789 / +29.86% |
| nether-lava-stress-v1 | 38.440 | 44.545 / +15.88% | 70.111 / +82.39% |

Артефакты находятся в run/logs/metallum-benchmarks/20260723T084613Z-..., 20260723T085106Z-..., 20260723T085308Z-..., 20260723T085445Z-..., 20260723T090341Z-... и 20260723T090628Z-.... Каждый accepted, COMPLETE и без dropped timing events.

То есть отрицательный эффект Quality в лёгкой/менее GPU-bound сцене уже был воспроизведён длинным тестом, тогда как тяжёлый Nether окупал даже 75%. Это не баг переключателя FPS; это зависимая от нагрузки цена full-display Temporal resolve. Свежий Tier B показывает ту же границу затрат на текущем source, но не подменяет новые Tier C сравнения.

## Что можно и нельзя безопасно удешевить

| Идея | Что может убрать | Где работает | Статус и причина |
| --- | --- | --- | --- |
| Dynamic input-depth ping-pong | Один copy: current dynamic D32 input -> separate previous-depth history | Только Dynamic в 50% temporal phase | **SPECULATIVE, единственный локальный кандидат.** Сейчас уже существуют два display-sized D32 ресурса. Их можно превратить в два полноценных input slot: current slot получает world depth и идёт в MetalFX/replay, previous slot читается diagnostic pass; на следующем кадре роли меняются. Нужны per-slot valid+jitter, reset/resize handling, render-target usage и доказанная in-flight/fence безопасность. Не затрагивает fixed Quality/Performance и не сокращает MetalFX. |
| Записать depth history прямо в diagnostic pass | Возможно убрать blit encoder | Fixed и Dynamic | **SPECULATIVE, низкий приоритет.** Те же D32 данные всё равно нужно записать; programmable depth store может ухудшить tile/bandwidth behaviour и обязан быть побитово/визуально эквивалентен текущему copy. Нельзя принять по рассуждению. |
| Отдельно измерить три copy | Ничего в steady-state рендере, но устраняет слепую зону | Все режимы | **Рекомендуется первым.** Это instrumentation-only шаг, а не обещание FPS. Он определит, имеет ли первый кандидат вообще измеримый потенциал. |
| Убрать/проредить motion, reactive или history | Частично снимает input pass/bandwidth | Все режимы | **Отклонено как безвредный путь.** Это ухудшает temporal data; тем более текущий production path ещё не покрывает object motion и дальние camera delta. |
| Выключать MetalFX на 65–80% | Снимает главный cost | Высокие scale | **Не optimisation, а отключение функции.** Dynamic уже выбирает Native 100% вместо своего 50% temporal state по GPU-time policy. Изменять пороги без Tier C нельзя. |
| Рендерить world сразу в display-sized Dynamic input | Могло бы убрать pack-copy | Только Dynamic | **Не безопасно как локальная правка.** Меняет render-target/viewport/projection contract и отменяет именно тот input-content design, который сейчас корректен для API. |
| Отключить material reactive MRT | Может снять часть terrain bandwidth | Только Advanced + Temporal terrain | **Не безвредно.** Исторический A/B уже показал не-FPS-win production reactive path, а mask нужна для history rejection. Аппаратный max blending был ещё хуже и полностью отклонён: 44.442 FPS против 57.788 FPS, GPU p95 25.105 против 20.294 ms. |

Для Dynamic ping-pong важно не выдавать потенциальный выигрыш за измеренный. Нынешний profiler не таймит этот history blit отдельной стадией, а benchmark harness намеренно не принимает dynamic mode TEMPORAL: scripts/run_metal_benchmark.sh:73-75,174-176 разрешает только OFF и fixed presets. Поэтому сначала нужна наблюдаемость и отдельный reproducible Dynamic route, а уже затем Tier C.

## Почему fixed path уже выглядит здоровым

- MTLFX workspace key кэшируется по device/formats/extents; при стабильном key texture allocation не происходит на hot path после warm-up.
- Color, depth, motion и reactive остаются private GPU textures; CPU readback в кадре нет.
- Fixed path не создаёт display-sized dynamic color input и не копирует в него world color.
- Static motion/reactive генерируются одной MRT render pass, а не несколькими screen passes.
- MetalFX scaler и output создаются с asynchronous initialization, чтобы resize/DRS transition не компилировал оптимизированный scaler синхронно на render thread.
- Spatial после Temporal не включается, то есть нет второго upscale.

Это именно те меры, которые обычно устраняют архитектурную расточительность. Оставшаяся крупная цена — opaque работа Apple MTLFX на полном display output; application-side код не имеет доступа к её внутреннему algorithmic budget.

## Решение и следующий шаг

1. Не рекомендую production-правку с целью сделать fixed Quality 75% (тем более гипотетические 80%/65%) «дешёвым». Без отключения Temporal здесь нет большого безопасного резерва.
2. Для FPS-политики разумно оставить текущую стратегию: Native при достаточной производительности, Dynamic Temporal только на проверенном 50%, а 75% рассматривать как quality preset с заведомо возможным отрицательным FPS.
3. Если продолжать работу, первый узкий эксперимент — detailed-only timing трёх неразмеченных copy. Только если history copy действительно заметен, реализовать Dynamic two-slot D32 ping-pong как отдельный reversible candidate.
4. Критерий принятия кандидата: contract/native GPU tests, отсутствие reset/disocclusion и visual regression, затем минимум два независимых Tier C прогона current baseline против кандидата на одинаковом Dynamic 50% маршруте, 1,800 warm-up + 3,000 measure frames, hdrtest-static-v1 и nether-lava-stress-v1, nominal thermal state, Advanced admission и zero relevant fallback.

Итог: **сейчас Temporal не «максимально дешёвый» в формальном смысле, но для проблемы отрицательного FPS на высоком scale его архитектура уже близка к практическому пределу.** Единственный честный резерв — маленькая Dynamic-copy оптимизация после измерения; он не способен отменить стоимость full-display MetalFX и не является решением для 75–80%.
