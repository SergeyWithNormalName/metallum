# План интеграции Apple MetalFX Frame Interpolation в Metallum

Статус документа: архитектурная спецификация и пошаговый план реализации. Это не описание уже готовой функции.

Целевая технология называется **Apple MetalFX Frame Interpolation**. Название «AppleFX Frame Interpolation» далее не используется, потому что такого публичного API у Apple нет.

Документ рассчитан на ИИ-агентов, которые будут реализовывать функцию по этапам. Каждый этап ниже имеет ограниченную область изменений, обязательные проверки и явный критерий завершения. Нельзя пропускать этап, объединять несколько этапов в один большой патч или объявлять функцию готовой только по успешной компиляции.

---

## 1. Цель

Добавить в Metallum аппаратно поддерживаемую генерацию одного промежуточного кадра между двумя реально отрендеренными кадрами:

```text
реальный кадр N-1
        |
        |  render interval T
        v
сгенерированный кадр N-1/2  ->  реальный кадр N
          T/2                         T/2
```

Ожидаемый основной сценарий на встроенном дисплее 120 Гц:

- игра симулирует и рендерит примерно 60 реальных кадров/с;
- MetalFX создаёт примерно 60 промежуточных кадров/с;
- presentation scheduler показывает примерно 120 кадров/с с равномерным интервалом;
- input, ticks, сеть, звук и игровая логика продолжают работать с частотой реальных кадров;
- функция не выдаёт рост presentation FPS за рост производительности рендера.

Функция должна работать и в SDR, и в HDR/EDR, не ломать существующий MetalFX Temporal Upscaling, сохранять отдельный SDR UI, безопасно отключаться при любой ошибке и никогда не удерживать устаревшие ресурсы другого мира, размера или renderer generation.

---

## 2. Решения, которые считаются зафиксированными

Агент не должен менять эти решения без отдельного архитектурного ревью.

1. **Первая production-реализация использует Metal 3 command-buffer API.** Создаётся `MTLFXFrameInterpolator`, работа кодируется в `MTLCommandBuffer`. Миграция renderer execution на Metal 4 не входит в эту задачу.
2. **Минимальная ОС для активной функции — macOS 26.0.** Общий deployment target проекта остаётся macOS 14.0; все обращения к API Frame Interpolation находятся под `#available(macOS 26.0, *)`.
3. **Поддержка определяется только runtime-проверкой.** Нельзя определять её по названию GPU, версии macOS или предположению об Apple Silicon. Обязательны `MTLFXFrameInterpolatorDescriptor.supportsDevice(device)`, успешное создание interpolator и проверка реальных format/usage контрактов.
4. **Первым реализуется fixed Temporal + Frame Interpolation, но Spatial + Frame Interpolation также является целевым production-режимом.** Temporal удобнее как первый вертикальный срез: он уже производит depth/motion и его scaler можно передать в descriptor. Это порядок реализации, а не ограничение Apple API.
5. **Frame Interpolation не требует Temporal scaler.** `descriptor.scaler` nullable. Только Temporal и Temporal Denoised scaler реализуют `MTLFXFrameInterpolatableScaler`; Spatial scaler этот protocol не реализует. Поэтому при Spatial + FI descriptor получает `scaler = nil`, а Metallum самостоятельно предоставляет корректные depth/motion inputs.
6. **Dynamic Temporal не считается сломанным или неправильно реализованным.** Текущий scaler корректно использует Apple active-content API (`inputContentPropertiesEnabled`, `inputContentWidth/Height`) и display-sized private resources. Ограничение относится только к первому способу подключения Frame Interpolator на macOS 26: сам interpolator там ещё не принимает active-content rectangle. Совместимость добавляется отдельным этапом через compact FI inputs либо macOS 27 content properties.
7. **Интерполяция выполняется после финального tone mapping/HDR reconstruction и до UI.** В MetalFX поступает display-ready world color без UI.
8. **UI остаётся отдельной текстурой.** V1 не просит MetalFX извлекать UI из уже скомпозированного кадра. Один и тот же существующий SDR UI отдельно композится поверх generated и real frames через Metallum present shader.
9. **Presentation полностью принадлежит нативному coordinator.** При активной интерполяции Java render thread не вызывает `nextDrawable()` и не кодирует второй случайный `present`.
10. **Generated frame всегда расходный, real frame — обязательный.** Любая ошибка, опоздание или нехватка drawable отменяет только generated presentation; следующий валидный real frame должен быть показан.
11. **Нет CPU readback и ожидания GPU в frame loop.** Нельзя использовать `getBytes`, `waitUntilCompleted`, синхронное чтение текстуры или создавать Metal resources каждый кадр.
12. **Ресурсы освобождаются только после последнего GPU/present consumer.** Сильные native references и completion-based retirement обязательны; Java GC не является механизмом lifetime.
13. **Metal 4 — отдельный будущий backend.** Типы `MTLCommandBuffer` и `MTL4CommandBuffer` нельзя смешивать в одном пути или переключать внутри generation.

---

## 3. Официальный контракт Apple

### 3.1. Доступность

В актуальном SDK `MTLFXFrameInterpolatorDescriptor`, `MTLFXFrameInterpolatorBase` и `MTLFXFrameInterpolator` объявлены для macOS 26.0+. Для Metal 4 существуют отдельные `supportsMetal4FX`, фабрика с `MTL4Compiler` и `MTL4FXFrameInterpolator`.

Baseline-проверка:

```swift
guard #available(macOS 26.0, *),
      MTLFXFrameInterpolatorDescriptor.supportsDevice(device) else {
    return .unsupported
}
```

Даже после `supportsDevice == true` фабрика может вернуть `nil`. Это штатный отказ capability, а не причина падения игры.

### 3.2. Descriptor

До создания interpolator descriptor фиксирует:

- `colorTextureFormat` — формат двух display-ready world frames;
- `outputTextureFormat` — формат generated output;
- `depthTextureFormat` — формат текущего depth input;
- `motionTextureFormat` — формат текущего motion input;
- `uiTextureFormat` — только если UI будет передаваться непосредственно MetalFX;
- `inputWidth/inputHeight` — физический размер depth/motion inputs;
- `outputWidth/outputHeight` — физический размер color/output;
- `scaler` — существующий `MTLFXTemporalScaler`, с которым interpolator должен совместно работать.

Для первого вертикального среза fixed Temporal + FI:

```text
colorTextureFormat  = фактический формат presentationWorldColor
outputTextureFormat = тот же формат
depthTextureFormat  = depth32Float
motionTextureFormat = rg16Float
inputWidth/Height   = render extent фиксированного Temporal preset
outputWidth/Height  = display extent
scaler              = тот же объект, который произвёл Temporal output кадра
uiTexture           = nil в V1
```

### 3.3. Матрица совместимости upscale и interpolation

Frame Interpolation является отдельным эффектом. Baseline V1/macOS 26 требует две caller-provided final color textures, depth и motion, но не всегда требует связанный upscaler object. В macOS 27 descriptor может сообщить `requiresPrevColorTexture == false`; internal-history вариант не входит в V1 и исследуется отдельно.

| Upstream render mode | Возможно одновременно с FI | `descriptor.scaler` | Откуда берутся depth/motion | Порядок реализации |
|---|---:|---|---|---|
| Fixed Temporal | Да | Тот же `MTLFXTemporalScaler` | Существующий production Temporal ring | Первый вертикальный срез |
| Spatial | Да | `nil`: Spatial не реализует `MTLFXFrameInterpolatableScaler` | Отдельный FI input ring, построенный из current world depth и camera/entity motion | Обязательный следующий production-этап |
| Native resolution | Да | `nil` | Отдельный FI input ring в native render extent | После Spatial; нужен performance/latency смысл |
| Dynamic Temporal, active 50% | Да, но требует адаптации inputs | Temporal scaler, если descriptor/input contract совпадает | Compact render-sized FI depth/motion либо macOS 27 content rectangle | Отдельный этап после fixed/Spatial |
| Dynamic Temporal, Native fallback | Технически да как Native + FI | `nil` или отдельный workspace | Native-resolution FI inputs | Сначала real-only; не переключать descriptor внутри history |

Для Spatial + FI поток выглядит так:

```text
low-resolution spatial world color -> MTLFXSpatialScaler -> display-resolution color
current world depth -------------------------------> FI depth input
camera/entity motion pass -------------------------> FI motion input
display color -> HDR/tone map -> presentationWorldColor[N]
presentationWorldColor[N-1/N] + depth/motion ------> Frame Interpolator
```

Spatial scaler нельзя присвоить `descriptor.scaler`: тип property допускает только objects, реализующие `MTLFXFrameInterpolatableScaler`, а `MTLFXSpatialScaler` к нему не относится. Это не запрещает комбинацию. Это означает, что Frame Interpolator работает как самостоятельный effect и Metallum отвечает за согласованность размеров, motion scale и history.

При Spatial projection jitter для FI равен фактически применённому jitter. Если Spatial path рендерит без jitter, передавать `0, 0`; нельзя копировать Halton jitter из неактивного Temporal режима.

Для Spatial отдельно вычисляется motion scale. `prevColorTexture` имеет display extent, а существующий motion generator хранит displacement в render pixels. Поэтому baseline без linked Temporal scaler:

```text
motionVectorScaleX = displayWidth  / renderWidth
motionVectorScaleY = displayHeight / renderHeight
```

`1.0` допустим только если motion texture уже хранит displacement в display pixels либо render extent равен display extent. Формула проверяется тестом с разными X/Y scale; нельзя предполагать одинаковый aspect или использовать один scalar для обеих осей. Для linked fixed Temporal profile первоначальный `1.0` сохраняется только после native proof, что scaler/interpolator совместно интерпретируют текущие render-pixel vectors корректно.

### 3.4. Usage contract

Нельзя захардкодить texture usage. После создания interpolator нужно прочитать:

- `colorTextureUsage`;
- `outputTextureUsage`;
- `depthTextureUsage`;
- `motionTextureUsage`;
- `uiTextureUsage`, если UI позже будет передаваться MetalFX.

Каждая созданная texture обязана содержать минимум запрошенные bits. Дополнительные bits разрешены. `outputTexture` должна иметь `storageMode == .private`.

### 3.5. Per-frame свойства

Перед каждым `encode` задаются и валидируются:

```text
colorTexture        = presentation-ready world N без UI
prevColorTexture    = presentation-ready world N-1 без UI
depthTexture        = depth N
motionTexture       = motion N
outputTexture       = generated N-1/2
deltaTime           = монотонный интервал между real N-1 и real N
nearPlane/farPlane  = параметры камеры N
fieldOfView         = vertical FOV N, degrees
aspectRatio         = camera/render aspect N
jitterOffsetX/Y     = jitter, реально применённый к кадру N
motionVectorScaleX/Y= доказанный scale до pixel displacement
depthReversed       = true для текущего reversed-Z контракта Metallum
shouldResetHistory  = true при invalidation/priming
fence               = только если фактически используются untracked resources
```

Если `shouldResetHistory == false`, `prevColorTexture` обязана содержать именно color из предыдущего вызова `encode`. Нельзя подменять её копией current frame, кадром другой generation или старой текстурой после resize.

### 3.6. Motion convention

Apple определяет motion vector как displacement из текущего пикселя к его положению в предыдущем color frame. При pixel scale `1.0` объект, переместившийся вправо и вниз на 10 пикселей, должен иметь `(-10, -10)`.

Существующий production Temporal contract Metallum уже использует:

```text
previousNdc - currentNdc
units: render pixels
X: right-positive coordinates, vector sign current -> previous
Y: screen coordinates, down-positive coordinates, vector sign current -> previous
```

Это похоже на требуемый контракт, но совпадение нельзя считать доказанным только по комментариям. V1 сначала передаёт `motionVectorScaleX/Y = 1.0`, потому что texture хранит pixel units и interpolator получает связанный Temporal scaler. Этот scale остаётся только после детерминированного native test и live motion calibration. Если тест показывает другой scale, исправляется место формирования motion vectors или явно документированная конверсия; запрещено подбирать знак «на глаз».

### 3.7. Camera parameters

`FrameState` уже содержит `nearPlane`, `farPlane`, unjittered projection и display/render extents. Для macOS 26 vertical FOV и aspect вычисляются из **unjittered** projection:

```text
fovYRadians = 2 * atan(1 / abs(P[1][1]))
fovYDegrees = fovYRadians * 180 / pi
aspect      = abs(P[1][1] / P[0][0])
```

Перед использованием проверяются finite values, `0 < fovY < 180`, `near > 0`, `far > near` и совпадение derived aspect с render/display aspect в разумном epsilon. При ошибке generated frame пропускается.

На macOS 27 появились `worldToViewMatrix` и `viewToClipMatrix`. Они не являются частью baseline macOS 26 и не должны блокировать V1. Поддержка macOS 27 добавляется отдельным улучшением после V1.

### 3.8. Delta time и минимальная частота

Apple рекомендует не подавать на interpolation входной render cadence ниже 30 FPS. `deltaTime` должен быть реальным интервалом между двумя real rendered frames, а не фиксированным `1/60` и не временем generated presentation.

Использовать монотонные timestamps, снятые на границе принятия real frame coordinator-ом. Значение `FrameState.deltaSeconds` разрешено только после теста, доказывающего, что оно отражает тот же интервал. Clamp не должен маскировать stall: при слишком большом или не-finite delta history сбрасывается, generated frame пропускается.

Начальная политика:

```text
valid delta range: 1/240 ... 1/30 seconds
recovery: 8 последовательных валидных real intervals
backlog: не более 1 ожидающей frame pair и 2 FI works in flight
```

Константы должны жить в одном policy-классе и иметь unit tests; нельзя размножать magic numbers по Java и Swift.

### 3.9. Источники Apple

- [MTLFXFrameInterpolatorDescriptor](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatordescriptor)
- [MTLFXFrameInterpolatorBase](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatorbase)
- [MTLFXFrameInterpolator](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolator)
- [MTLFXFrameInterpolatableScaler](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatablescaler)
- [WWDC25: Go further with Metal 4 games, Frame Interpolation](https://developer.apple.com/videos/play/wwdc2025/211/?time=437)
- [Metal Feature Set Tables](https://developer.apple.com/metal/capabilities/)
- [CAMetalLayer](https://developer.apple.com/documentation/quartzcore/cametallayer)
- [Metal API Validation](https://developer.apple.com/documentation/xcode/validating-your-apps-metal-api-usage)
- [Metal Performance HUD](https://developer.apple.com/documentation/xcode/customizing-metal-performance-hud)

При расхождении этого документа с заголовком установленного SDK агент обязан остановиться, записать точную версию Xcode/SDK и обновить план до реализации. Нельзя угадывать API по памяти.

---

## 4. Что уже есть в Metallum

Перед изменениями агент обязан повторно открыть перечисленные файлы: номера строк могут сместиться.

### 4.1. Готовый каркас

| Область | Существующий код | Как использовать |
|---|---|---|
| Capability discovery | `metallum_renderer_capabilities_v1` в `src/main/native/MetallumNative.swift` | Уже проверяет `supportsDevice` и `supportsMetal4FX` на macOS 26+. Расширить effect-specific profile, не создавать второй независимый probe. |
| Java capability model | `src/main/java/com/metallum/client/renderer/MetalCapabilities.java` | Уже содержит Metal 3/Metal 4 Frame Interpolation feature bits. Добавить typed `FrameInterpolationProfile`. |
| Persisted request | `src/main/java/com/metallum/client/renderer/RendererConfig.java` | Уже содержит `frameInterpolation`, default `false`. Не создавать второй config-файл. |
| Feature mask | `RendererFeatureMask.FRAME_INTERPOLATION` | Использовать только как **effective admitted** feature, не как user request. |
| Generation fallback | `RendererGenerationConfig` | Уже умеет независимо отбрасывать неподдержанный FI bit. Усилить profile/policy checks. |
| Resource domain | `RendererGenerationManifest.Domain.INTERPOLATION_ONLY` | Заполнить реальными resources/passes после native preflight. |
| Frame ABI | `FrameStateAbi` и зеркальный Swift parser | Уже содержит interpolation bit и `interpolationResourceBytes` по offset 232. Сохранять строгую синхронность. |
| Admission model | `FrameSynthesisContract` | Уже описывает frame pair, presentation intents, drawable ownership, generations и in-flight retention. Подключить к production policy, не переписывать с нуля. |
| Temporal inputs | `TemporalDiagnosticResources`, `encodeTemporalScale`, temporal native workspace | Уже есть D32 depth, RG16F motion, R8 reactive, jitter, reset, triple buffering и fixed-preset Temporal scaler. |
| UI separation | `HdrUiRenderTarget`, `GuiRendererHdrMixin`, native HDR/UI present paths | Сохранить отдельный SDR UI и переиспользовать текущую HDR-aware композицию. |
| Present shader | `src/main/metal/MetallumPresent.metal` | Рефакторить в reusable offscreen/drawable target path, не писать новый несовместимый color transform. |
| Timing | `MetalGpuTimingStage`, native timestamp profiler, `nextDrawable` CPU wait | Добавить FI stages/counters, сохранив отдельно real и generated cadence. |

### 4.2. Намеренные блокировки, которые надо снять только по этапам

- `MetalDevice` сейчас всегда публикует renderer generation с interpolation `OFF` и логирует, что production admission ещё отсутствует.
- `RendererGenerationPlanner` бросает исключение, если manifest содержит `FRAME_INTERPOLATION`.
- В native коде есть capability probe, но нет создания interpolator, назначения textures или `encode`.
- `RendererConfig.frameInterpolation` не выведен как полноценная Sodium option.
- `FUTURE_RENDERING.md` ошибочно утверждает, что macOS не предоставляет native frame interpolation API.

Нельзя просто удалить guard в `RendererGenerationPlanner` и включить feature bit. Это приведёт к generation, которая заявляет ресурсы и presentation semantics, которых в действительности нет.

Существующий `FrameSynthesisContract.validatePresentations` является preparation scaffold и сейчас ставит `REAL_FRAME` раньше `GENERATED_FRAME`: generated intent обязан иметь больший `presentationId` и более поздний target time. Для реального MetalFX порядка это нужно исправить. Между уже показанным N-1 и новым N сначала показывается generated N-1/2, затем real N. Следовательно, внутри accepted pair:

```text
generated.presentationId < real.presentationId
generated.targetDisplayTime < real.targetDisplayTime
generated.sourceFrameId == real.sourceFrameId == current.frameId
```

`InFlightGenerationOwnership.lastPresentationId` должен покрывать фактически последний intent (`max` IDs, в нормальном pair — real), а не безусловно generated. Это изменение относится к pure-Java policy этапу и должно быть сделано до native scheduler.

### 4.3. Известная граница качества motion vectors

`TECH_DEBT.md` фиксирует, что entity motion replay имеет transforms, packets и shader math, но ещё не получает реальные live Metal vertex/index buffers. Поэтому быстрые entities, held items, animated terrain, liquids, foliage и часть particles могут иметь неверные vectors/reactivity.

Правило rollout:

- до подключения live entity velocity функция маркируется **Experimental**;
- нельзя обещать production visual quality для динамических объектов;
- capability и memory safety не должны зависеть от фиктивных buffer handles;
- запрещено фабриковать native pointers ради прохождения теста;
- закрытие T1B entity velocity — отдельный quality gate перед снятием Experimental label, но не повод смешивать unsafe interception с базовым present scheduler в одном коммите.

### 4.4. Dynamic Temporal: текущий статус и граница FI

Текущий Dynamic Temporal не требует исправления в рамках Frame Interpolation. Его production path:

- создаёт display-sized private color/depth/motion/reactive textures;
- включает `inputContentPropertiesEnabled` в descriptor;
- перед каждым encode задаёт фактические `inputContentWidth/Height`;
- GPU-to-GPU копирует только активный low-resolution rectangle;
- проверяет `inputContentMinScale/MaxScale`;
- сохраняет fixed physical workspace между 100% Native и 50% Temporal состояниями;
- не делает CPU readback.

Это соответствует active-content контракту `MTLFXTemporalScaler` и уже описано в [`docs/TEMPORAL_UPSCALING_DRS.md`](docs/TEMPORAL_UPSCALING_DRS.md). Там отдельно зафиксированы реальные оставшиеся долги: Minecraft target resize, renderer-generation churn, эмпирическая exit policy и дополнительная bandwidth cost. Они не означают, что scaler получает неверные размеры или используется не по API.

Граница только у соединения с Frame Interpolator:

- macOS 26 FI descriptor видит physical `inputWidth/inputHeight`, но не имеет per-frame `contentWidth/contentHeight`;
- нельзя считать пустую часть display-sized motion/depth texture валидным active content;
- для Dynamic + FI на macOS 26 нужно создавать заранее выделенный compact 50%-sized FI motion/depth ring либо иной доказанный adapter;
- на macOS 27 можно отдельно исследовать FI content properties, не меняя корректный Temporal DRS path.

Поэтому отдельный документ о «неправильном Dynamic Temporal» создавать не надо: такого установленного дефекта нет. Если будущие live benchmarks докажут pacing или performance regression, обновляется существующий `docs/TEMPORAL_UPSCALING_DRS.md` с измерениями, а не с предположением.

---

## 5. Целевая архитектура

### 5.1. Компоненты

Добавить следующие логические компоненты. Имена можно скорректировать только если в проекте уже есть точный эквивалент.

```text
Java render side
  FrameInterpolationPolicy
  FrameSynthesisContract (existing)
  MetalCommandEncoder prepare/publish boundary
                  |
                  | Panama ABI: typed status + native coordinator handle/ticket
                  v
Native Swift side
  MetallumFrameInterpolationCoordinator
    |- immutable configuration key
    |- MTLFXFrameInterpolator workspace
    |- 3 real-color back buffers
    |- 3 generated-output buffers
    |- retained depth/motion/UI frame leases
    |- interpolation command queue
    |- presentation command queue
    |- PresentThread
    |- PacingThread
    |- MTLEvent / MTLSharedEvent ordering
    `- telemetry and failure latch
                  |
                  v
             CAMetalLayer
```

### 5.2. Почему coordinator должен быть native

Coordinator обязан владеть:

- `CAMetalLayer.nextDrawable()`;
- `MTLFXFrameInterpolator`;
- Metal command queues и command buffers для interpolation/present;
- `MTLEvent`/`MTLSharedEvent` values;
- сильными ссылками на textures до GPU completion;
- двумя dedicated threads и их shutdown;
- pacing timestamps;
- native completion handlers.

Java не должен управлять каждым generated drawable через Panama: это увеличит число downcalls, усложнит lifetime и создаст возможность вызвать Metal presentation с не-render thread. Java управляет policy/generation и передаёт один подготовленный ticket на real frame.

### 5.3. Новый Swift-файл

Предпочтительно создать:

```text
src/main/native/MetallumFrameInterpolation.swift
```

В нём находятся coordinator, state machine, resource key, work item, threads и ABI entry points. `MetallumNative.swift` сохраняет общие frame-state parsing, HDR helpers, capability discovery и вызывает новый компонент.

После добавления файла обновить **каждую** Gradle Swift compilation, которая должна линковать production native код или runtime validation. Нельзя обновить только основной dylib и оставить native test tasks с другим набором исходников.

Objective-C++ helper из Apple sample/skill можно использовать как эталон алгоритма, но не надо добавлять `.mm` wrapper только ради копирования sample: основной native backend Metallum написан на Swift. Если Swift не позволяет выразить требуемый `kevent64`/thread primitive без небезопасного workaround, решение о `.mm` helper принимается отдельным маленьким build-spike этапом.

### 5.4. Configuration key

Один coordinator workspace соответствует immutable key:

```text
device identity
renderer generation id
output generation id
upstream mode: Native / Spatial / Temporal Fixed / Temporal Dynamic
optional Temporal workspace/scaler identity
upscale preset/mode
render width/height
display width/height
world-color format
depth format
motion format
layer pixel format
SDR/HDR output mode
UI composition mode
target presentation Hz
```

Изменение любого поля переводит coordinator в `draining`, запрещает новые generated frames, дожидается/retire старых works и создаёт новый workspace. Нельзя менять descriptor fields после создания interpolator и надеяться, что history останется валидной.

### 5.5. Runtime state machine

Использовать явные состояния:

```text
UNAVAILABLE  API/device/profile не поддержан
OFF          user request выключен
WARMING      ресурсы и pipelines создаются, generated present запрещён
PRIMING_0    принят первый валидный real frame
PRIMING_1    принят следующий frame после reset; всё ещё real-only
ACTIVE       разрешён encode и dual presentation
BYPASS       временно real-only из-за cadence/backlog/transient error
DRAINING     resize/toggle/generation/device change; новые works не принимаются
FAILED       ошибка залатчена до следующей renderer generation
```

Допустимые переходы должны быть unit-tested. Ошибка не должна переводить весь renderer в FAILED; это состояние только FI subsystem. Общий Metal backend продолжает обычный single present.

### 5.6. Per-frame resource lease

Каждый accepted real frame создаёт immutable native work item:

```text
rendererGenerationId
outputGenerationId
historyGeneration
frameId / submitIndex
realPresentationId
optionalGeneratedPresentationId
monotonicRenderTimestamp
presentationWorldColor slot
generatedOutput slot
depth texture strong reference
motion texture strong reference
UI texture strong reference
jitter
near/far/fov/aspect
reset/discontinuity flags
target cadence
```

Work item нельзя изменять после публикации. Он освобождает references только после completion последнего command buffer, способного читать его resources. На shutdown/detach незапущенные items отменяются, запущенные retire через completion.

---

## 6. Новый frame flow

### 6.1. Текущий flow

Сейчас `MetalSurface`/`MetalCommandEncoder` передаёт source и HDR/UI inputs в native `metallum_MTLCommandBuffer_encodePresentTextureToDrawable`. Native немедленно вызывает `layer.nextDrawable()`, рендерит final image прямо в drawable и вызывает `commandBuffer.present(drawable)`. Затем Java `submit()` commit-ит тот же command buffer.

Этот flow нельзя расширить вторым `present` внутри той же функции: для generated и real frames нужны разные drawables, отдельное GPU ordering и midpoint pacing.

### 6.2. Обязательный split encode/commit/publish

Ввести двухфазный контракт:

```text
Phase A, Java render thread, ДО commit:
  prepareFrame(..., renderCommandBuffer)
    - validates generation and inputs
    - reserves coordinator slot without unbounded wait
    - renders final world without UI into native real-color slot
    - retains current depth/motion/UI
    - encodes signal renderReadyEvent(value)
    - returns PREPARED(ticket) or BYPASS(reason)

Phase B, Java render thread:
  commitWithSignal(renderCommandBuffer)

Phase C, сразу ПОСЛЕ успешного commit:
  publishCommittedFrame(ticket)
    - marks ticket visible to PresentThread
    - never encodes into the already committed render command buffer
```

Критическое правило: interpolation command buffer нельзя commit-ить **перед** renderer command buffer, если он на той же ordered queue ждёт event, который renderer buffer должен сигналить. Поэтому native work становится runnable только после Java commit. Этот порядок покрывается отдельным unit/native test.

Не смешивать два разных механизма синхронизации:

- `renderReadyEvent` — `MTLEvent`, signal кодируется в renderer command buffer **до** commit; другой GPU command buffer делает межочередной wait по его value;
- существующий `commitWithSignal(completedSemaphore)` — Java `DispatchSemaphore`/completion lifecycle для трёх in-flight submit slots;
- `publishCommittedFrame(ticket)` вызывается сразу после того, как commit был принят, но не ждёт GPU completion semaphore.

Java completion semaphore нельзя использовать как замену GPU event: это превратит межочередную зависимость в CPU wait.

Если `prepareFrame` вернул `BYPASS`, старый single-present path кодируется до commit, и `publishCommittedFrame` не вызывается. Если prepare был успешен, старый path не должен дополнительно acquire drawable.

Если commit бросил исключение, Java вызывает `cancelPreparedFrame(ticket)`. Coordinator освобождает reservation, invalidates pair и не ждёт событие, которое никогда не будет сигналено.

### 6.3. GPU/present sequence для ACTIVE

```text
Render queue / Java CB
  encode world, temporal scale, HDR/tone map -> realColor[N]
  encodeSignalEvent(renderReady, rN)
  commit

Interpolation queue / native CB
  wait renderReady(rN)
  bind prev realColor[N-1], current realColor[N], depth[N], motion[N]
  MetalFX encode -> generatedColor[N]
  signal interpolationReady(iN)
  commit

Present queue / generated CB
  wait interpolationReady(iN)
  acquire drawable only when work is ready
  composite generatedColor[N] + ui[N] -> drawableGenerated
  present generated drawable
  commit

Pacing thread
  waits measured/scheduled half interval
  signals realPresentGate(pN)

Present queue / real CB
  wait renderReady(rN)
  wait realPresentGate(pN)
  acquire drawable only near use
  composite realColor[N] + ui[N] -> drawableReal
  present real drawable
  commit
```

Presentation order для pair всегда generated, затем real. `presentationId` монотонный и уникальный. Если generated path отменён, real frame не ждёт midpoint gate и показывается как можно раньше по обычной безопасной present policy.

### 6.4. Priming/reset sequence

Точный алгоритм:

1. После enable/recreate/reset принять real N, показать только N, сохранить его как candidate previous. Состояние `PRIMING_1`.
2. Принять real N+1, показать только N+1, обновить previous и установить MetalFX `shouldResetHistory = true` для следующего допустимого encode. Состояние `ACTIVE`.
3. Между N+1 и N+2 впервые разрешить generated frame.
4. Любая discontinuity повторяет последовательность с шага 1.

Нельзя генерировать кадр между состояниями до и после teleport, dimension change, resize, FOV jump или renderer generation switch.

### 6.5. Backpressure

Render thread не должен бесконечно ждать present system. Coordinator держит:

- 3 real-color slots;
- 3 generated-output slots;
- максимум 2 interpolation command buffers in flight;
- максимум 1 work item, ожидающий запуска;
- максимум 2 drawable presentation slots на pair.

Если свободного slot нет в заданный короткий reservation budget, текущий frame идёт real-only, `generated_skipped_backpressure` увеличивается, а history либо сохраняется только при доказанном порядке, либо сбрасывается. Никогда не расширять массив автоматически и не аллоцировать emergency texture в кадре.

### 6.6. Pacing

Создать два native thread:

- `MetallumPresentThread` — user-interactive priority; encode/copy/present;
- `MetallumPacingThread` — user-interactive priority; precise midpoint timing через `kqueue` + `kevent64`, `NOTE_MACHTIME | NOTE_ABSOLUTE`, затем `MTLSharedEvent`.

Алгоритм ориентируется на Apple PresentThread helper:

- измеряет время между завершениями/публикациями real frames;
- ограничивает аномальный delta, но рассматривает его как reset, а не как нормальный cadence;
- назначает real gate примерно в середине интервала после generated presentation;
- использует `present(afterMinimumDuration:)` только с рассчитанным и валидным minimum duration;
- никогда не использует `Thread.sleep`/`usleep` для frame pacing;
- не удерживает mutex во время `nextDrawable`, Metal encode или commit;
- все condition waits имеют shutdown predicate;
- destructor/shutdown будит оба thread и `join`-ит их.

`CAMetalLayer.maximumDrawableCount = 3` устанавливается при активной dual presentation после проверки доступности и возвращается к безопасной конфигурации при teardown, если слой принадлежит Metallum. `nextDrawable` запрашивается как можно позже; strong drawable reference освобождается после commit present CB.

### 6.7. Refresh-rate policy

V1 поддерживает только 2x synthesis. Effective enable допускается, если:

- refresh rate известен;
- display refresh не ниже 60 Гц;
- real cadence стабильно не ниже 30 FPS;
- real cadence находится около половины measured target display Hz, а не просто выше минимума;
- target presentation cadence не выше текущей display capability;
- display не сменился после создания workspace.

Формализовать V1 gate:

```text
desiredRealHz = measuredTargetDisplayHz / 2
lowerBound    = max(30, desiredRealHz * 0.85)
upperBound    = desiredRealHz * 1.05
admit only after a stable-window EWMA lies in [lowerBound, upperBound]
```

Для основного 120 Гц профиля целевой режим — около 60 real + 60 generated. Если игра уже рендерит 90 real FPS на 120 Гц, 2x потребовал бы 180 presentations/с; такой кадр идёт real-only BYPASS, а не вытесняет/пропускает произвольные real frames. Автоматический render cap до половины refresh не добавлять скрыто: это отдельная user-visible policy с собственным latency proof. На 60 Гц допустим 30 + 30 только после отдельного pacing proof. До такого proof можно оставить 60 Гц в real-only BYPASS и явно логировать причину. Variable Refresh Rate/ProMotion нельзя считать фиксированным только по `maximumFramesPerSecond`: telemetry и Metal HUD должны доказать фактический cadence.

---

## 7. Color, HDR и UI

### 7.1. Presentation-ready world color

MetalFX нельзя кормить сырым Temporal output до HDR reconstruction/tone mapping. Рефакторить native present код так, чтобы один reusable helper мог рендерить финальный **world-only** result в произвольный render target:

```text
encodePresentationWorld(
  commandBuffer,
  targetTexture,
  source/scene/depth/semantic,
  outputMode,
  sourceEncoding,
  currentHeadroom,
  hdrStrength,
  bloomStrength,
  globalFence
)
```

Тот же helper используется:

- старым single-present path, чтобы доказать parity;
- FI path для заполнения `realColor[N]`.

`realColor` и `generatedColor` должны иметь display extent, одинаковый pixel format и private storage. Формат выбирается из фактического presentation contract/layer format, а не из предположения «HDR всегда RGBA16Float». Descriptor строится после проверки реального формата.

### 7.2. UI strategy V1

V1 использует отдельную существующую SDR UI texture:

- `colorTexture` и `prevColorTexture` не содержат UI;
- `interpolator.uiTexture = nil`;
- generated и real drawable получают UI через общий Metallum present composite pass;
- shader выполняет тот же SDR-to-HDR/EDR mapping, alpha semantics и sampler choice, что текущий single-present path;
- UI texture удерживается work item до завершения обеих presentations.

Это намеренно стоит дополнительного pass, но избегает:

- невозможного точного unblend composited UI;
- различий HDR UI между real и generated frames;
- interpolation текста, crosshair, menu и debug overlay;
- изменения UI semantics до появления доказательств.

После V1 можно отдельно сравнить `interpolator.uiTexture` offscreen mode. Переход допускается только при pixel/visual parity в SDR и HDR и измеренном выигрыше.

### 7.3. Меню и быстро меняющийся UI

Generated frame не запускает второй GUI render и не меняет game state. V1 повторно использует UI texture текущего real frame. Проверить отдельно:

- HUD и crosshair;
- F3/debug overlays;
- inventory и pause menu;
- чат, scrolling text и subtitles;
- loading/progress screen;
- Sodium settings;
- menu blur в SDR/HDR + Temporal;
- screenshot path;
- системный mouse cursor.

Loading screen, resource reload и смена world должны вызывать reset/real-only. Если отдельный UI path недоступен или texture contract не совпадает, generated frame пропускается.

### 7.4. Screenshot semantics

Обычный screenshot должен захватывать последний **real game frame**, а не случайный generated drawable. Он проходит существующий screenshot pipeline до presentation. Отдельная debug-команда для захвата generated output допустима позже, но не меняет пользовательское поведение.

---

## 8. Admission и настройки

### 8.1. Requested, admitted и active — разные понятия

Не смешивать:

```text
requested: RendererConfig.frameInterpolation == true
admitted: renderer generation имеет FRAME_INTERPOLATION bit и workspace preflight успешен
active: coordinator находится в ACTIVE и конкретная frame pair прошла FrameSynthesisContract
```

UI показывает requested/effective status; telemetry сообщает все три.

`FrameSynthesisContract` также должен перейти с текущего scaffold-порядка real→generated на фактический display-порядок generated→real. Не адаптировать native scheduler к ошибочному старому predicate.

### 8.2. Общий admission checklist

Feature bit можно включить только если одновременно истинны:

- macOS 26+;
- `supportsDevice(device)`;
- `makeFrameInterpolator` вернул объект;
- upstream mode входит в уже реализованный и проверенный FI profile;
- для Temporal profile доступен `MetalCapabilities.METALFX_TEMPORAL` и scaler identity стабилен;
- для Spatial/Native profile доступны отдельные production depth/motion inputs;
- render/display extents ненулевые;
- D32/RG16F/color formats и usages прошли native profile probe;
- separated SDR UI contract доступен;
- display refresh policy разрешает 2x;
- FrameState ABI и renderer generation согласованы;
- native coordinator prewarm завершён;
- нет latched FI failure в текущей generation.

`R8 reactive` не является прямым texture input FrameInterpolator, но production Temporal contract продолжает требовать его для корректного upstream Temporal output. Spatial/Native + FI не обязаны создавать reactive mask только ради interpolator. Не добавлять несуществующее свойство `interpolator.reactiveTexture`.

### 8.3. Upstream-mode rule

Admission добавляется по профилям, а не одной глобальной галочкой:

1. `TEMPORAL_FIXED` — первый профиль; linked Temporal scaler, existing depth/motion/reactive contract.
2. `SPATIAL` — второй обязательный профиль; `descriptor.scaler = nil`, отдельные depth/motion inputs, jitter равен фактически применённому Spatial path jitter.
3. `NATIVE` — следующий профиль; `descriptor.scaler = nil`, native-resolution inputs.
4. `TEMPORAL_DYNAMIC_ACTIVE` — отдельный adapter/profile.
5. `TEMPORAL_DYNAMIC_NATIVE_FALLBACK` — не переиспользует active Temporal descriptor как будто он Native.

Если выбранный mode ещё не реализован или не прошёл preflight:

- request сохраняется;
- effective FI остаётся OFF только для этого profile;
- upscale policy не переключается автоматически;
- лог/tooltip сообщает конкретную причину;
- renderer не создаёт неподходящие FI resources.

Автоматически менять пользовательский Spatial/Temporal preset запрещено.

`FrameSynthesisContract` сейчас безусловно требует `METALFX_TEMPORAL`. Перед Spatial admission его нужно расширить полем `UpstreamMode` и conditional rules: Temporal capability/reactive contract обязательны только для Temporal profile; общие previous/depth/motion/UI/generation/presentation checks обязательны всегда.

### 8.4. Renderer generation

Добавить effective interpolation state и policy version в `RendererGenerationKey` и `RendererAdmissionLogState`. Любой transition request/admission вызывает новую generation и существующие reset events.

`RendererGenerationPlanner` должен перестать бросать безусловное исключение только после появления:

- реального `INTERPOLATION_ONLY` resource manifest;
- native preflight;
- lifecycle owner;
- unit tests отрицательных комбинаций.

Manifest объявляет минимум:

- 3 display-sized real-color textures;
- 3 display-sized generated-color textures;
- coordinator/interpolator object estimate;
- present/interpolation passes и PSO count;
- native queue/work item capacity;
- UI references не считаются owned bytes, если они только retained.

`FrameState.ResourceBytes.interpolation` должен быть ненулевым только при admitted bit и совпадать с manifest estimate.

### 8.5. Sodium UI

Добавить option в общей группе MetalFX рядом с Spatial/Temporal settings в `MetallumSodiumConfig`:

```text
Frame Interpolation: Off / Auto (Experimental)
```

Для V1 persisted boolean можно сохранить: `false = Off`, `true = Auto`. Не менять schema только ради переименования. Добавить `RendererConfig.withFrameInterpolation(boolean)` и save/reload callback.

Tooltip обязан сообщать:

- macOS 26+ и поддерживаемый GPU;
- первым доступен fixed Temporal profile, а Spatial profile включается после собственного input preflight;
- текущую effective комбинацию: Temporal + FI, Spatial + FI, Native + FI либо конкретную причину bypass;
- создаётся один generated кадр между real frames;
- рекомендуемый real FPS не ниже 30;
- функция experimental до завершения entity motion quality gate;
- возможен рост input latency, хотя presentation выглядит плавнее.

Недоступный option не должен падать или исчезать без объяснения: показывать disabled/effective reason.

### 8.6. Debug override

Добавить только test/benchmark override через environment variable, например:

```text
METALLUM_FRAME_INTERPOLATION=off|auto
```

Override не обходит capability, format, generation или safety gates. `force` mode, который кодирует unsupported path, запрещён.

---

## 9. ABI и bridge

### 9.1. Не расширять ABI v3 неявно

FrameState ABI v3 фиксирован: 848 bytes, Java offsets и Swift parser зеркальны. Уже существующих полей достаточно для базовых camera/reset/resource данных.

Presentation tickets, native coordinator handle, queue state и timestamps не нужно встраивать в каждый FrameState packet. Передавать их отдельным typed bridge API.

Если всё же требуется новое поле внутри FrameState:

1. создать ABI v4 с новым packet size;
2. одновременно изменить `FrameStateAbi.java`;
3. одновременно изменить Swift enum/parser/snapshot;
4. добавить Java layout tests и Swift native validation;
5. сохранить явный v3 rejection/fallback;
6. не использовать reserved bytes без версии.

### 9.2. Предлагаемый native API

Точные C symbols могут отличаться, но responsibilities должны остаться разделёнными:

```text
metallum_frame_interpolation_create_v1(device, layer, configPacket) -> context?
metallum_frame_interpolation_prepare_v1(
    context,
    renderCommandBuffer,
    source/scene/depth/semantic/ui/fence,
    frameStatePacket,
    presentConfigPacket,
    outTicket
) -> status
metallum_frame_interpolation_publish_committed_v1(context, ticket) -> status
metallum_frame_interpolation_cancel_v1(context, ticket) -> status
metallum_frame_interpolation_drain_v1(context, timeoutNanos) -> status
metallum_frame_interpolation_reset_v1(context, reasonMask) -> status
metallum_frame_interpolation_stats_v1(context, outPacket) -> status
metallum_frame_interpolation_release_v1(context)
```

Нельзя передавать unchecked `UInt64` и делать `as!`. Каждый raw object:

- проверяется на null;
- conditional-cast к ожидаемому Metal protocol;
- сверяется device identity;
- проверяется размер/format/usage/storageMode;
- проверяется command buffer status `.notEnqueued` для prepare;
- при ошибке возвращается стабильный status code.

### 9.3. Status model

Не использовать только `0/1`. Ввести стабильные категории:

```text
PREPARED
BYPASS_DISABLED
BYPASS_UNSUPPORTED
BYPASS_PRIMING
BYPASS_CADENCE
BYPASS_BACKPRESSURE
BYPASS_NO_UI
BYPASS_GENERATION
BYPASS_INPUT_CONTRACT
NO_DRAWABLE
STALE_TICKET
TRANSIENT_FAILURE
FATAL_FOR_GENERATION
```

Java wrapper преобразует только известные значения. Неизвестное значение считается `FATAL_FOR_GENERATION`, отключает FI, но не весь renderer.

### 9.4. Commit integration

Изменить `MetalCommandEncoder.submit()` осторожно:

1. сохранить optional prepared ticket перед commit;
2. выполнить существующий `commitWithSignal`;
3. после успешного commit вызвать publish;
4. при исключении commit вызвать cancel в `finally`/catch;
5. не закрывать `MTLCommandBuffer` wrapper до регистрации ticket;
6. сохранить существующие 3 Java in-flight slots;
7. native coordinator самостоятельно удерживает FI resources дольше Java render completion, если это требуется.

Добавить тест порядка вызовов на fake bridge: `prepare -> commit -> publish`, а для ошибки `prepare -> failed commit -> cancel`.

---

## 10. Resource lifetime и teardown

### 10.1. Texture rings

Для каждого workspace заранее создать:

- `realColor[3]`;
- `generatedColor[3]`;
- необходимые reusable presentation intermediates, только если existing shader не может писать прямо в drawable;
- event objects;
- command queues;
- copy/composite PSO references.

Все textures `.private`, fixed dimensions/formats. Usage — union фактического producer/consumer и MetalFX-reported requirements.

### 10.2. Retention

Work item должен держать strong native references на:

- current/previous real color slots;
- current depth;
- current motion;
- current UI;
- interpolator/scaler;
- event/queue objects;
- generated output;
- оба drawables только до commit соответствующего present CB.

Slot возвращается в free ring после completion последнего consumer, а не после `encode` и не после Java render CB completion.

### 10.3. Drain order

При resize, display move, fullscreen transition, HDR mode change, Temporal preset change, resource reload, device release или user disable:

1. atomically прекратить admission новых FI frames;
2. пометить pending tickets cancelled;
3. разбудить coordinator threads;
4. дать уже committed real presents завершиться;
5. дождаться bounded drain по condition/completion, не через render-loop `waitUntilCompleted`;
6. при timeout залатчить FI failure и сохранить native objects до их completion handler;
7. join threads только при полном context release;
8. очистить history и generation references;
9. вернуть обычный single-present path;
10. retire textures/queues/interpolator.

Zero-size resize — нормальное состояние окна. При `width == 0 || height == 0` не создавать textures, перейти в real-only/offscreen unavailable и ждать следующую валидную generation.

### 10.4. Deadlock rules

Запрещено:

- ждать condition, удерживая lock, который нужен completion handler;
- вызывать `Drain` из PresentThread самого себя;
- commit-ить waiting CB раньше signaling renderer CB на той же serial queue;
- удерживать coordinator mutex во время `nextDrawable`;
- ждать Java semaphore из native PresentThread;
- вызывать Panama callback из Metal completion handler;
- освобождать event/context до join threads;
- вызывать renderer/device allocation с Sodium worker thread.

Добавить debug-only assertions thread identity и lock state на public coordinator entry points.

---

## 11. History и failure policy

### 11.1. Reset reasons

Сбрасывать pair/history и минимум два real frames показывать без synthesis при:

- first frame после enable;
- resize или render-scale change;
- output/layer pixel format change;
- HDR mode/headroom contract generation change;
- fullscreen/window/display migration;
- world load/unload;
- dimension/portal change;
- respawn;
- teleport/camera entity change;
- резкий FOV/projection change;
- resource pack/shader reload;
- Temporal preset/workspace/scaler identity change;
- Dynamic Temporal transition;
- renderer/render-contract/output/history generation change;
- non-finite/слишком большой delta;
- dropped/missing/misordered real frame;
- native encode/present error;
- drawable starvation/backlog, если continuity больше нельзя доказать.

Использовать существующие `TemporalResetEvents` и `FrameState.resetMask`; не создавать независимый неполный список только в coordinator.

### 11.2. Fail-open to real presentation

Термин «fail-open» здесь означает: ошибка FI открывает путь обычному real frame, а не оставляет чёрный кадр. Это не означает игнорирование validation.

На любой отказ:

```text
drop generated
present real
increment exact reason counter
invalidate FI history when pair continuity uncertain
continue renderer
```

Только repeated/fatal generation failures отключают FI до следующей generation. Они не должны отключать HDR, Temporal, lighting или весь Metal backend.

### 11.3. No stale reuse

Перед encode сверить current и previous:

- `frameId` последовательны;
- `submitIndex` последовательны в принятой coordinator sequence;
- renderer/history/output/resource generations совпадают;
- extents/formats совпадают с workspace key;
- previous color slot ещё retained;
- current depth/motion принадлежат current frame;
- discontinuity sets пусты;
- presentation IDs возрастают.

Любое несовпадение — reset и real-only. Нельзя искать «ближайший подходящий» старый кадр в ring.

---

## 12. Telemetry и диагностика

### 12.1. Необходимые GPU stages

Расширить `MetalGpuTimingStage` и зеркальный native enum, сохраняя стабильные IDs:

- `FRAME_INTERPOLATION_PREPARE` — final world into real back buffer, если не входит в существующий present stage;
- `FRAME_INTERPOLATION` — `MTLFXFrameInterpolator.encode`;
- `GENERATED_PRESENT` — copy/composite/present generated;
- `REAL_PRESENT` — copy/composite/present real.

Если profiler имеет фиксированный `PROFILED_STAGE_COUNT`, изменить Java/native/tests одновременно.

### 12.2. Counters

JSONL/report обязан различать:

```text
real_frames_rendered
real_frames_presented
generated_frames_encoded
generated_frames_presented
generated_frames_skipped_total
generated_skipped_by_reason
frame_pairs_reset
queue_depth_current/high_water
interpolation_in_flight_current/high_water
drawable_wait_generated p50/p95/p99/max
drawable_wait_real p50/p95/p99/max
interpolation_gpu p50/p95/p99
generated_present_gpu p50/p95/p99
real_present_gpu p50/p95/p99
render_to_real_present_latency
render_to_generated_present_latency
target_present_hz
measured_real_fps
measured_generated_fps
measured_total_present_fps
interpolation_resource_bytes
workspace_recreate_count
fatal_generation_disable_count
```

Нельзя складывать два present command buffers в один «rendered FPS». DRS policy продолжает принимать решения по стоимости real rendering/Temporal path, а не по удвоенному total presentation rate.

### 12.3. Logging

Один structured log на workspace creation:

- OS/API route;
- device name и support result;
- input/output extents;
- formats;
- requested/actual usage bits;
- storage modes;
- Temporal preset/scaler identity;
- target refresh;
- resource bytes.

Один log на effective transition и один на latched failure. Нельзя печатать per-frame success logs.

### 12.4. Debug visualizations

Сохранить существующие motion/reactive debug views. Добавить только debug mode:

- alternating labels/colours для real/generated frames;
- freeze generated output;
- motion scale override только в dev build;
- optional generated-vs-real difference view.

Override не попадает в normal config и всегда отражается в telemetry.

---

## 13. Пошаговый план реализации

Каждый этап — отдельный focused commit. После этапа рабочее дерево должно содержать только намеренные изменения этого этапа. Чужие/пользовательские изменения не включать.

### Этап 0. Зафиксировать baseline и исправить документацию

Изменения:

- обновить устаревший раздел Frame Interpolation в `FUTURE_RENDERING.md`;
- записать macOS 26+, Metal 3/Metal 4 split и текущий статус «capability scaffold only»;
- сохранить existing production behavior OFF;
- зафиксировать исходные `frameSynthesisUnitTest`, `temporalScalingValidation`, `temporalDiagnosticRuntimeValidation`, `frameStateAbiNativeValidation` и `clean check`.

Gate:

- никаких runtime изменений;
- все исходные тесты проходят;
- diff только docs.

### Этап 1. Усилить pure-Java policy

Изменения:

- добавить typed `FrameInterpolationProfile` в `MetalCapabilities`;
- добавить `FrameInterpolationPolicy` с единым набором cadence/display/upstream-profile gates;
- подключить `RendererConfig.frameInterpolation` как requested, но effective оставить false;
- расширить `FrameSynthesisContract` только недостающими reason codes;
- исправить presentation intent order на generated N-1/2 → real N и last-presentation ownership;
- проверить, что reactive mask — upstream Temporal quality gate, не FI texture binding;
- добавить tests для fixed presets, Dynamic rejection, old OS/device, unknown refresh, cadence ниже/около/выше половины refresh, reset, generation mismatch и backpressure.

Gate:

- `frameSynthesisUnitTest` и `rendererArchitectureUnitTest` зелёные;
- production FI всё ещё невозможно активировать.

### Этап 2. Native descriptor/profile validation harness

Создать `src/test/native/FrameInterpolationValidation.swift` и Gradle task `frameInterpolationValidation`.

Harness на macOS 26+:

1. получает system default device;
2. проверяет `supportsDevice`;
3. создаёт совместимый Temporal scaler;
4. создаёт descriptor с маленькими fixed textures;
5. создаёт interpolator;
6. считывает effect-specific usage bits;
7. создаёт private color/output и D32/RG16F inputs;
8. кодирует минимум две согласованные real frames и один generated output;
9. проверяет command buffer completion/error без CPU pixel readback;
10. печатает стабильный pass/skip result для unsupported machine.

Gate:

- supported hardware: encode успешно с Metal API Validation;
- unsupported hardware/OS: clean SKIP, не failure и не crash;
- capability profile публикуется только после успешного probe.

### Этап 3. Refactor single-present в reusable final-world + UI composite

Это самый важный regression-control этап. FI ещё выключен.

Изменения:

- выделить из `metallum_MTLCommandBuffer_encodePresentTextureToDrawable` reusable world-only finalization;
- выделить reusable UI composite/copy в drawable;
- сохранить старое acquire-one-drawable/one-present поведение;
- не менять color math, HDR reconstruction, menu blur, screenshot и sampler semantics;
- добавить native parity harness для SDR, EDR/Enhanced и actual HDR material generation.

Gate:

- byte/metric-compatible contracts там, где возможно;
- visual live proof: SDR/HDR world и UI без чёрных кадров/гамма-сдвига;
- Temporal menu blur и Native/Spatial/Temporal transitions проходят;
- performance tails не регрессируют вне согласованного noise budget.

### Этап 4. Coordinator lifecycle без dual presentation

Изменения:

- добавить `MetallumFrameInterpolationCoordinator` и immutable key;
- добавить create/reset/drain/release;
- создать texture rings, events, queues и threads;
- scheduler в этом этапе показывает только real frames;
- реализовать zero-size, resize, display change, shutdown и bounded backpressure;
- добавить native lifetime/deadlock tests.

Gate:

- coordinator real-only path визуально совпадает со старым single present;
- 10 000 synthetic enqueue/reset/drain cycles без leak/deadlock;
- no per-frame allocations после warmup согласно counters/instruments;
- old single-present остаётся fallback.

### Этап 5. Split Java commit boundary и tickets

Изменения:

- добавить bridge create/prepare/publish/cancel/drain/release;
- изменить `MTLCommandBuffer` wrapper;
- изменить `MetalCommandEncoder.presentTextureToDrawable` и `submit`;
- сохранить old path для BYPASS;
- native ticket не может быть опубликован до commit;
- Java in-flight ring и destruction queue остаются работоспособны.

Gate:

- mock ordering tests;
- injected commit failure вызывает cancel;
- stale generation вызывает old real fallback;
- ни один ticket не остаётся pending после shutdown.

### Этап 6. MetalFX encode и history ring

Изменения:

- создать `MTLFXFrameInterpolator` из existing fixed Temporal scaler;
- bind exact textures и per-frame properties;
- реализовать priming/reset rules;
- retain previous/current resources;
- encode generated output, но пока не включать пользовательскую настройку;
- добавить interpolation GPU stage и reason counters.

Gate:

- native harness видит реальные MetalFX encoders;
- static scene не дрожит;
- known-pixel motion calibration подтверждает scale/sign;
- teleport/resize/FOV reset не создаёт cross-generation output;
- unsupported/nil/error path остаётся real-only.

### Этап 7. Dual presentation и pacing

Изменения:

- включить generated затем real drawable sequence;
- добавить pacing thread/shared events;
- установить 3 drawable pool при активной функции;
- реализовать drop-generated-on-late;
- добавить Metal HUD proof и telemetry.

Gate:

- presentation IDs строго возрастают;
- не бывает real-before-corresponding-generated внутри accepted pair;
- при drop следующий real показывается;
- Metal HUD frame interval graph ровный или имеет регулярный pattern;
- histogram имеет не более двух ожидаемых buckets;
- нет случайных burst из двух presents;
- input latency измерена и задокументирована.

### Этап 8. HDR/UI integration

Изменения:

- generated и real paths используют общий SDR/HDR UI composite;
- проверить `.rgba16Float`/фактический layer format, EDR headroom и output generation;
- UI texture lifetime входит в work item;
- screenshot остаётся real-frame;
- menu blur получает current Temporal output до FI.

Gate:

- SDR, EDR и Enhanced/HDR проходят live matrix;
- текст/UI не ghost-ится и не интерполируется;
- яркость generated и real world визуально/метрически согласована;
- HDR headroom change вызывает safe reset/recreate;
- full-screen green-button/F11/display moves не deadlock-ят coordinator.

### Этап 9. Production admission и Sodium setting

Только после этапов 0–8:

- убрать unconditional OFF в `MetalDevice`;
- убрать planner throw и добавить реальный manifest;
- добавить interpolation в generation key/log state;
- добавить Sodium `Off/Auto (Experimental)`;
- первым включить effective admission для fixed Temporal profile;
- включить runtime stats overlay/logs.

Gate:

- option persist/reload работает;
- unsupported configuration показывает reason и не выделяет resources;
- toggle on/off, preset switch и renderer reload безопасны;
- resource bytes и feature bit соответствуют реальному workspace.

#### Реализация Stage 9 (2026-07-24)

Выполнен первый production vertical slice только для **fixed Temporal + FI**:

- Sodium toggle `Frame Interpolation (Experimental)` сохраняет существующий
  `frameInterpolation` setting и требует restart;
- admission разрешает только supported macOS 26+ fixed-Temporal profile с
  successful native workspace preflight, известной частотой дисплея не ниже
  60 Гц и реальной cadence внутри окна half-refresh;
- generation key, feature bit, manifest и `FrameState.ResourceBytes` содержат
  FI только после успешного preflight; manifest объявляет 3-slot world,
  depth/motion, SDR-UI и composite rings;
- v2 typed Java/Swift bridge передаёт source/scene/UI/fence только до commit.
  Native coordinator создаёт display-ready world без UI, копирует depth/motion/
  UI в preallocated private ring, а затем один владеет drawable/present;
- generated member расходный, а любой input/cadence/drawable/encoder failure
  оставляет old single-real-frame presenter; resource lifetime завершается
  completion handlers и drain, без readback или GPU wait в frame loop;
- native telemetry публикует accepted/generated/real/drop/backpressure counters
  через существующий GPU JSONL report, admission/fallback логируется в Java.

Automated proof: `frameInterpolationValidation` на Apple M1 Pro прошёл Stage
4–9, включая v2 exact-extent и incomplete-input fail-open contract; unit tests
и `clean check` также прошли. Live visual/HUD/long-session matrix из разделов
14.3–14.4 остаётся обязательным ручным acceptance evidence перед снятием
Experimental label или объявлением общей функции полностью готовой.

### Этап 10. Spatial Upscaling + Frame Interpolation

Этот этап обязателен: Spatial и FI совместимы, но не могут совместно использовать Temporal scaler object.

Изменения:

- добавить `SPATIAL` в `FrameSynthesisContract.UpstreamMode`;
- сохранить обычный `MTLFXSpatialScaler` resolve;
- создать отдельный reusable FI depth/motion ring в Spatial render extent;
- получать world depth до UI clear тем же безопасным способом, что Temporal inputs;
- запускать camera/static-depth и entity motion generation даже без Temporal scaler;
- не создавать reactive mask, если он не нужен другому активному эффекту;
- после Spatial upscale выполнить общий HDR/tone-map world-only pass;
- передать две final display-resolution color textures в FI;
- установить `descriptor.scaler = nil`;
- если motion хранится в render pixels, установить per-axis scale `displayExtent / renderExtent`, а не `1.0`;
- установить jitter `0,0`, если Spatial path фактически не jittered, иначе передать реальный Spatial jitter;
- добавить отдельный workspace key/profile, resource estimate и telemetry label `spatial+fi`;
- не менять user-selected Spatial preset автоматически.

Gate:

- сначала проверить fixed `QUALITY`, `PERFORMANCE`, `ULTRA_PERFORMANCE` из `SpatialScalingMode`;
- dynamic `SPATIAL`/resolved `AUTO` допускается только после bounded workspace/recreate policy: любое изменение render extent делает drain/reset, а все разрешённые descriptor/resources prewarm-ятся вне кадра; per-frame recreation запрещён;
- FI input depth/motion formats и extents проходят native validation;
- scaler property действительно nil и encode успешен;
- non-uniform test подтверждает отдельные X/Y motion scales;
- Spatial output не апскейлится второй раз;
- HDR/UI parity с Temporal + FI;
- camera/object motion calibration;
- measured benefit и tails на фиксированном benchmark route;
- switching Temporal + FI ↔ Spatial + FI создаёт новую generation, drain/reset и не переиспользует history.

### Этап 11. Native и Dynamic Temporal profiles

#### 11A. Native + FI

- использовать native-resolution world color/depth/motion;
- `descriptor.scaler = nil`;
- доказать, что GPU cost/latency FI имеет смысл без upscale savings;
- не включать profile по умолчанию, если benchmark не показывает устойчивый presentation benefit.

#### 11B. Dynamic Temporal + FI на macOS 26

- не менять корректный existing Temporal active-content scaler path;
- при active 50% заранее держать compact render-sized FI depth/motion textures;
- копировать/генерировать только необходимые FI inputs GPU-to-GPU;
- descriptor key соответствует compact 50% input и display output;
- безопасный macOS 26 baseline использует `descriptor.scaler = nil`, потому что compact FI inputs не совпадают с physical display-sized active-content descriptor existing Temporal scaler;
- linked scaler допускается только как отдельный последующий profile, если native validation докажет точное совпадение descriptor/scaler/input contract;
- при 100% Native fallback drain Temporal-linked FI workspace и использовать отдельный Native profile либо real-only;
- Dynamic transition всегда reset; никаких descriptor mutations внутри history.

#### 11C. macOS 27 optimization

- исследовать `contentWidth/contentHeight`, offsets и `requiresPrevColorTexture` по актуальному SDK;
- сравнить active-content FI с compact adapter;
- принять только measured win без quality regression;
- сохранить macOS 26 fallback.

Gate:

- существующий `temporalDiagnosticRuntimeValidation` остаётся зелёным;
- `docs/TEMPORAL_UPSCALING_DRS.md` не получает неподтверждённых утверждений;
- Dynamic Temporal OFF/ON без FI идентичен baseline;
- Dynamic + FI не читает пустую область physical inputs;
- Native fallback никогда не использует 50% Temporal history.

### Этап 12. Entity/animated motion quality

Отдельная серия изменений:

- подключить реальные deferred-lifetime entity draw buffers к `EntityVelocityDrawRecorder`;
- валидировать device, bounds, index type, generation и PSO assumptions;
- добавить held item/entity/armor/translucent entity coverage;
- затем оценить fluids/foliage/particles и reactive policy.

Gate:

- никакого fabricated pointer;
- native buffer validation;
- moving entity/held-item live proof;
- только после этого убрать Experimental label.

### Этап 13. Metal 4 backend — не часть V1

Отдельный план после production Metal 3:

- `MTL4Compiler` lifecycle;
- `MTL4FXFrameInterpolator`;
- Metal 4 command allocator/buffer/queue semantics;
- отдельный coordinator backend;
- `supportsMetal4FX` admission;
- parity tests с Metal 3.

Нельзя заменить типы механически: Metal 4 command-buffer lifecycle отличается.

---

## 14. Обязательная тестовая матрица

### 14.1. Unit tests

- requested/admitted/active distinction;
- unsupported OS/device;
- `supportsDevice == false`;
- factory nil;
- fixed Temporal allowed;
- Spatial allowed только после Spatial input/profile preflight;
- Spatial не требует Temporal capability и использует `descriptor.scaler = nil`;
- Native allowed только после Native profile preflight;
- Dynamic rejected до compact/content adapter и разрешён после него;
- переходы Temporal/Spatial/Native/Dynamic создают новую generation и reset;
- display refresh unknown/60/120;
- real FPS below/above 30;
- real FPS below/inside/above half-refresh admission window;
- first frame and two-frame priming;
- every reset reason;
- frame/generation/extent/format mismatch;
- presentation ordering;
- two drawable slots;
- insufficient in-flight ownership;
- backpressure and recovery;
- submit failure cancel;
- stale/duplicate ticket;
- config persistence and migration;
- manifest resource bytes.

### 14.2. Native validation

- descriptor creation and nil path;
- exact texture formats/usages/storage;
- current/previous color relation;
- D32 reversed depth;
- RG16F motion scale/sign;
- jitter X/Y convention;
- camera FOV/aspect derivation;
- event ordering;
- commit-before-publish;
- generated output encode;
- real-only fallback;
- resize 0x0 and normal resize;
- rapid create/drain/release;
- device mismatch rejection;
- injected no-drawable;
- completion-based retirement;
- Metal API/Shader Validation clean run.

### 14.3. Live visual scenes

Для каждого сценария сравнивать OFF и ON при одинаковом route/settings:

1. статичная камера и мелкая геометрия;
2. медленный/быстрый camera pan;
3. strafe и вращение одновременно;
4. движущиеся mobs разных размеров;
5. held item и hand animation;
6. minecart/boat/projectiles;
7. flowing water/lava;
8. foliage/animated textures;
9. particles, fire, portal, rain;
10. transparent blocks/entities;
11. chunk load/unload на высокой скорости;
12. teleport;
13. portal/dimension switch;
14. FOV change/sprint/zoom;
15. inventory/chat/pause/debug overlay;
16. menu blur;
17. resize/window/fullscreen/F11/green button;
18. move between displays;
19. HDR headroom change;
20. resource reload `F3+A`;
21. long 30–60 minute session.

Искать: ghosting, wobble, doubled edges, disocclusion holes, UI trails, brightness alternation real/generated, tearing, black frame, incorrect ordering и latency spikes.

### 14.4. Performance/pacing proof

Основной профиль Metallum:

```text
Built-in Retina display
fullscreen HDR
Balanced lighting
VSync/display sync policy фиксирована и записана
Temporal fixed preset фиксирован
одинаковый world/route/camera path
warmup 1800 frames
measure 3000 real frames
```

Сравнения:

- Temporal ON, FI OFF;
- тот же Temporal, FI ON;
- FI ON with forced generated bypass для измерения coordinator overhead;
- при необходимости SDR control.

Публиковать:

- real FPS и real frame CPU/GPU median/p95/p99;
- generated FPS;
- total presented FPS;
- 1%/0.1% low по real cadence;
- interpolation GPU cost;
- present delay и drawable waits отдельно;
- missed/dropped generated ratio;
- input-to-real-present latency;
- memory/resource bytes;
- Metal HUD interval graph/histogram evidence.

Нельзя принимать функцию, если total presentation FPS вырос, но real FPS, latency или tails деградировали сверх заранее записанного бюджета.

### 14.5. Команды проверки

Минимум после соответствующих этапов:

```bash
./gradlew frameSynthesisUnitTest --console=plain
./gradlew rendererArchitectureUnitTest --console=plain
./gradlew frameStateAbiNativeValidation --console=plain
./gradlew temporalScalingValidation --console=plain
./gradlew temporalDiagnosticRuntimeValidation --console=plain
./gradlew frameInterpolationValidation --console=plain
./gradlew clean check --console=plain
```

Последняя задача появится на этапе 2. Если точное имя существующего Gradle task отличается, сначала проверить `./gradlew tasks`; не объявлять test passed по несуществующей задаче.

---

## 15. Acceptance criteria

Функция считается готовой только если выполнены все пункты.

### Correctness

- MetalFX API реально кодируется на supported macOS 26+ device.
- Generated frame находится между правильными N-1/N.
- Motion/depth/jitter/camera contracts доказаны тестами.
- UI не входит в interpolated world history.
- SDR/HDR real и generated frames имеют согласованный color output.
- Все reset/discontinuity paths real-only и не используют stale history.

### Safety

- Unsupported path не создаёт resources и не падает.
- Любой FI failure сохраняет real presentation.
- Нет unchecked Swift casts.
- Нет use-after-free, early texture reuse или Java-GC lifetime dependency.
- Нет per-frame resource allocation, CPU readback или GPU synchronous wait.
- Resize/teardown не deadlock-ят threads.
- Старый single-present path остаётся рабочим.

### Pacing

- Generated и real presentations равномерно чередуются.
- HUD interval evidence соответствует требованиям Apple.
- Backlog bounded.
- Опоздавший generated frame отбрасывается, а не показывается после real.
- Real FPS, generated FPS и total presented FPS считаются отдельно.

### Product

- Настройка сохраняется и объясняет effective rejection.
- Функция не переключает Spatial/Temporal preset сама.
- Spatial + Frame Interpolation является поддерживаемым отдельным profile, а не ошибочно запрещённой комбинацией.
- Dynamic Temporal без FI сохраняет текущий проверенный active-content path без изменений.
- Experimental label остаётся до entity motion gate.
- Screenshot и gameplay logic сохраняют real-frame semantics.
- Документация `FUTURE_RENDERING.md`, `TECH_DEBT.md` и performance docs обновлены по фактическому состоянию.

### Evidence

- все unit/native/clean-check tests прошли;
- Metal API Validation прошёл;
- приложены telemetry/HUD artifacts;
- проведён live visual matrix;
- проведён продолжительный gameplay run;
- commit не содержит generated artifacts или чужих изменений.

---

## 16. Правила для ИИ-агента-исполнителя

1. Сначала прочитать `AGENTS.md`, этот документ и все файлы текущего этапа целиком.
2. Проверить `git status --short`; не трогать чужие изменения.
3. Выполнять только один этап за раз.
4. Перед кодом записать ожидаемый invariant и failure behavior этапа.
5. Не копировать Apple sample без адаптации к Swift/Java commit boundary Metallum.
6. Не менять Java ABI без зеркального Swift изменения и теста.
7. Не включать user-facing option до завершения этапа 9.
8. Не использовать screenshots как единственное performance/correctness доказательство.
9. Не называть generated presentation «удвоенной производительностью рендера».
10. При неясном API открыть заголовок установленного SDK и официальную Apple документацию.
11. При nil/unsupported/error реализовать real-only fallback до happy path.
12. После успешных релевантных тестов создать focused commit с названием этапа.
13. В отчёте разделять automated proof, Metal validation, telemetry и pending live proof.
14. Если acceptance gate этапа не пройден, остановиться, описать точный blocker и не переходить дальше.

---

## 17. Краткая карта файлов будущей реализации

| Файл | Ожидаемое изменение |
|---|---|
| `src/main/native/MetallumFrameInterpolation.swift` | Новый coordinator, workspace, threads, pacing, tickets, ABI. |
| `src/main/native/MetallumNative.swift` | Capability profile, reusable final-world/UI helpers, coordinator calls, telemetry integration. |
| `src/main/metal/MetallumPresent.metal` | Только необходимые reusable copy/UI composite variants с сохранением color math. |
| `src/main/java/.../MetalCapabilities.java` | Typed FI profile и native evidence. |
| `src/main/java/.../RendererFeatureMask.java` | Использовать existing bit; не добавлять дубликат. |
| `src/main/java/.../RendererGenerationConfig.java` | FI admission/fallback reasons. |
| `src/main/java/.../RendererGenerationPlanner.java` | Реальный interpolation manifest вместо unconditional throw. |
| `src/main/java/.../RendererConfig.java` | `withFrameInterpolation`, persistence semantics. |
| `src/main/java/.../FrameSynthesisContract.java` | Production policy wiring и недостающие rejection reasons. |
| `src/main/java/.../MetalDevice.java` | Requested/admitted generation, key, lifecycle owner. |
| `src/main/java/.../MetalCommandEncoder.java` | prepare/commit/publish/cancel order. |
| `src/main/java/.../mtl/MTLCommandBuffer.java` | Typed bridge wrappers/status. |
| `src/main/java/.../bridge/MetalNativeBridge.java` | Strict Panama descriptors для FI ABI. |
| `src/main/java/.../MetalSurface.java` | Выбор coordinator vs old single-present без двойного submit. |
| `src/main/java/.../MetallumSodiumConfig.java` | Off/Auto Experimental option и tooltip. |
| `src/main/java/.../MetalGpuTimingStage.java` | FI/present timing stages. |
| `src/test/native/FrameInterpolationValidation.swift` | Descriptor/encode/lifecycle runtime proof. |
| `src/test/java/.../FrameSynthesisTests.java` | Policy, pair, ownership, reset и pacing tests. |
| `src/test/java/.../RendererArchitectureTests.java` | Generation/capability/manifest/fallback tests. |
| `build.gradle` | Новый Swift source во всех нужных compile tasks и validation task. |
| `FUTURE_RENDERING.md` | Актуальный статус и ограничения. |
| `TECH_DEBT.md` | Entity/animated motion quality boundary до её закрытия. |

---

## 18. Definition of Done в одной фразе

MetalFX Frame Interpolation готов только тогда, когда supported macOS 26+ Metal 3 path безопасно создаёт и равномерно показывает один корректный generated frame между двумя согласованными real frames, имеет отдельно проверенные Temporal и Spatial integration profiles, сохраняет отдельный SDR/HDR UI, автоматически возвращается к real-only при любом нарушении контракта и подтверждён unit/native tests, Metal validation, telemetry, HUD pacing и длительным live gameplay.
