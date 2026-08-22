# Metal 4: решение для Metallum

Дата аудита: 2026-08-22.

## Краткий вердикт

**Не переводить renderer на Metal 4 и не ожидать от него роста steady-state FPS.**
Текущий Nether-профиль упирается в ALU двух terrain fragment-pass'ов, прежде всего
в L6 soft-shadow filter, а не в CPU submission, память или пропускную способность.
Metal 4 не уменьшает число или стоимость этих fragment-инструкций. Очереди,
argument tables, residency и explicit barriers затронут критичный Java--FFM--Swift
путь и, скорее всего, добавят риск регрессии стабильности.

Есть **ровно один узкий кандидат для отдельного opt-in исследования**: новый
Metal 4 compiler/archive workflow для устранения измеренных hitch'ей при создании
pipeline state. Его потенциальный результат — более ровный запуск или shader reload;
это **не** кандидат на FPS-оптимизацию кадра.

## Исходные факты и ограничения

- Metal 4 требует macOS 26+ и должен включаться только после runtime capability
  check. На M1 Pro в `benchmark/current/M1_PRO_GPU_CAPABILITIES.json` Metal 4 пока
  отмечен лишь как `DOCUMENTATION_CONFIRMED`, а не как подтверждённая текущим
  capability probe возможность. `apple7` и Metal 3 подтверждены; hardware ray
  tracing, mesh shaders family 9 и statistic counters недоступны.
- Renderer намеренно render-thread confined, использует три in-flight submit и
  отложенное destruction. Это особенно важно, потому что `MTL4CommandBuffer` не
  удерживает strong references на ресурсы. История AGX/Sodium indexed-indirect crash
  уже потребовала owned in-flight snapshots; миграция не должна ослабить это правило.
- По последнему trace/counter-аудиту `opaque` и `translucent` terrain являются
  главным fragment/ALU bottleneck. Buffer-read, texture-cache и bandwidth limiter'ы
  вторичны; production attachment audit не нашёл лишних clear/store, reactive MRT
  или duplicate draw.
- Ранее уже уменьшены submission/encoder overhead: indirect preupload сократил
  topology примерно со `100 render / 95 blit` до `14 / 9` encoders на кадр. Это
  полезная историческая оптимизация, но не доказательство, что оставшийся лимит
  находится в command-buffer allocation или binding.

Основные проектные источники: [OptimizationHistory.md](OptimizationHistory.md),
[architecture](docs/architecture.md), [native renderer](docs/metal-renderer.md),
[benchmark contract](docs/BENCHMARKING.md) и
[M1 Pro capability receipt](benchmark/current/M1_PRO_GPU_CAPABILITIES.json).

## Единственный кандидат: MTL4Compiler + archive для pipeline hitch'ей

### Зачем проверять

[Metal 4 compilation API](https://developer.apple.com/documentation/metal/using-the-metal-4-compilation-api)
даёт явный контроль над временем/QoS компиляции и позволяет harvest/serialize
pipeline states into a binary archive. Apple прямо указывает на уменьшение runtime
compile delay; binary archives совместимы между Metal 3 и Metal 4. Это хорошо
согласуется с известной технической границей проекта: pipeline/native initialization
failures не должны маскироваться как рендер-результат.

Цель POC: уменьшить только подтверждённый compilation stall при cold start,
resource-pack/shader reload или смене generation. Не переносить command submission,
drawable presentation, resource ownership либо shader semantics.

### Предварительный gate — без него кода не писать

1. На реальном M1 Pro записать runtime capability (`supportsFamily(.metal4)`) в
   capability receipt. Если ответ false или ОС ниже 26, POC прекращается.
2. Воспроизвести не менее двух cold-start/reload с той же artifact/source/settings
   identity и измерить длительность каждой pipeline compilation. Correlate hitch с
   конкретным созданием pipeline, а не с world loading, Java GC или renderer fallback.
3. Если нет повторяемого user-visible stall, не добавлять archive: это лишний
   persistent cache и новый invalidation surface без полезного эффекта.

### Минимальный и безопасный POC

- Только `#available(macOS 26, *)` и положительный runtime capability check; старый
  Metal 3 path остаётся единственным fallback.
- Начать с одного self-contained native builtin pipeline, который уже создаётся из
  precompiled metallib. Не касаться Sodium terrain, L3--L8 ABI, MetalFX/FI, drawable
  или command encoder.
- Harvest/archive key обязан включать artifact/source digest, shader-library digest,
  renderer-generation/pipeline descriptor identity, registry ID GPU и OS/Metal
  compatibility boundary. Писать атомарно; cache miss, archive error или validation
  error должны тихо отбросить archive и вызвать существующий безопасный путь, а не
  source fallback/Vanilla rendering.
- Не выполнять compilation на render thread. До publication pipeline должен пройти
  существующие native library/binding validation; не публиковать частично готовый PSO.
- Не обещать выгоду до измерения: ожидаемая метрика — cold/reload hitch и p99 CPU
  frame time, не FPS/GPU ms.

### Критерии принятия и stop gate

Принять только если все ниже выполнены:

1. Два повторяемых cold/reload запуска подтверждают существенное уменьшение
   compilation-correlated hitch без роста p99 CPU frame time в обычной игре.
2. Builtin shader library, active binding reflection, HDR/Advanced admission и
   ordinary fallback contracts остаются идентичны; нет new allocation в frame loop.
3. Обычный Tier C A/B не показывает регрессии FPS, GPU p95/p99 или lows. Tier B
   может лишь локализовать проблему, но не служит результатом FPS.
4. Все archive miss/corruption/old-key cases fail closed к старому Metal 3 path.

Остановить и полностью удалить POC при отсутствии повторяемого hitch, при любом
runtime path/fallback mismatch, либо если cache invalidation усложняет generation
ownership. Эта работа не должна разрастаться в "частичную миграцию Metal 4".

## Не внедрять сейчас

| Технология Metal 4 | Решение | Причина |
|---|---|---|
| `MTL4CommandQueue`, reusable command buffers, allocators | Нет | Может уменьшить CPU/memory overhead, но текущий bottleneck GPU/fragment. Командные буферы не retain'ят ресурсы, поэтому это прямо конфликтует с наиболее рискованной частью ownership/lifetime. |
| Argument tables | Нет | Потенциально снижают CPU binding overhead, но требуют новый binding model для Java/FFM, Sodium indirect geometry и shader ABI. Нет профиля, доказывающего, что это значимее L6 ALU. |
| Explicit barriers, fences, residency sets | Нет | Metal 4 убирает implicit hazard tracking. Ошибка даст stale tiles, GPU fault или lifetime race; текущие fence/deferred destruction уже защищают production path. Не является FPS-ответом на ALU limiter. |
| Group commit / parallel command encoding | Нет | Нарушает render-thread confinement и повышает риск гонок вокруг generation, indirect snapshots и presentation. Пробовать можно лишь после отдельного CPU-bound trace, которого сейчас нет. |
| Unified compute encoder (blit + compute) | Нет | Нужна полная смена encoder model. После indirect preupload encoder overhead уже снижался; trace показывает, что оставшееся время съедают terrain fragment pass'ы. |
| Color-attachment mapping / flexible PSO | Нет | Project audit не нашёл лишних passes/attachments, а число известных output variants не оправдывает новый M4 pipeline/binding path. Может вернуться только при доказанной PSO-variant explosion. |
| Texture view pools, new residency/view APIs | Нет | Нет доказанного churn texture views либо memory-pressure bottleneck. M1 Pro counters указывают не на память/texture cache. |
| `MTL4CounterHeap` | Нет | На M1 Pro доступны timestamp stage samples, но statistic counters не доступны. Новая оболочка не создаст недостающих аппаратных счётчиков; существующая GPU timing методика достаточна. |
| Metal 4 presentation flow | Нет | FI/ProMotion уже имеют тонкий two-queue, `MTLSharedEvent`, drawable and real-frame fail-open protocol. Замена `present` workflow не уменьшит L6 cost и несёт высокий риск pacing regression. |
| Mesh shaders, hardware ray tracing | Нет | Не поддерживаются текущим M1 Pro capability receipt; кроме того, не решают существующий terrain fragment bottleneck. |
| Machine-learning encoder / Core ML | Нет | Не использовать как источник GI/radiance или fps shortcut. В проекте ML допустим только как поздний, bounded experiment поверх физически проверяемых anchors. |

## Что остаётся главным путём к FPS

Metal 4 не заменяет текущую задачу: искать новый доказанный способ сократить
**exact** работу полного L6 soft filter одновременно для opaque и translucent terrain
без ухудшения seams, fail-closed semantics или temporal stability. Повторять уже
отклонённые nearest/three-tap/texture-packing/representation варианты нельзя без
нового emitted-MSL и GPU-capture evidence. Любой следующий FPS-кандидат должен идти
через одну гипотезу, Tier B attribution, затем две независимые Tier C A/B и live
visual acceptance по [BENCHMARKING.md](docs/BENCHMARKING.md).

## Первичные Apple-источники

- [Understanding the Metal 4 core API](https://developer.apple.com/documentation/metal/understanding-the-metal-4-core-api)
- [Using the Metal 4 compilation API](https://developer.apple.com/documentation/metal/using-the-metal-4-compilation-api)
- [Resource synchronization](https://developer.apple.com/documentation/metal/resource-synchronization)
- [Resource fundamentals](https://developer.apple.com/documentation/metal/resource-fundamentals)
