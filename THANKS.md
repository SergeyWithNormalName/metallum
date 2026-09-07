# Заимствования из форков Metallum

Этот файл фиксирует не только взятый код, но и осознанно отклонённые
переносы. Для renderer-кода схожее название функции ещё не означает, что
реализация совместима с нашими HDR, Advanced Lighting, MetalFX, resource-lifetime и
benchmark-контрактами.

## Аудит 7 сентября 2026 года

Сравнение выполнялось с локальным `ForksHunting` на исходном commit
`b9f81022eec2b72199504d34895f3a39a738fddb`. Существовавшие до аудита локальные
изменения не перезаписывались и не ставились в коммиты заимствований.

| Репозиторий | Проверенный ref | SHA |
|---|---|---|
| [21Z121Z1/MetalUniversal](https://github.com/21Z121Z1/MetalUniversal) | `master` | `cf4e66250c8fd3bcf6fdf58e00e0cd841052cd58` |
| [21Z121Z1/MetalUniversal](https://github.com/21Z121Z1/MetalUniversal) | `agent/metalfx-motion-coverage-26.2` | `4eb5b6a0f68bc7cef639ceb46c8c2affebcce8a7` |
| [pentaoa/lodeframe](https://github.com/pentaoa/lodeframe) | `master` | `695ba0a08e9ce385051c8e1cbe716246ef4401fd` |
| [NetworkCats/metallum](https://github.com/NetworkCats/metallum) | `master` | `5d07a7c218b47b600547b685953d2e8014697b9f` |
| [prodbyichigo/metallum-metalfx](https://github.com/prodbyichigo/metallum-metalfx) | `master` | `93b429b2dab3f5b308e53092cf7cf90152a9798b` |
| [EternityQwQ/MetalUniversal](https://github.com/EternityQwQ/MetalUniversal) | `26.2-fabric` | `31b2d94c291a03b2873a8cd7f491e99679b76081` |
| [EternityQwQ/MetalUniversal](https://github.com/EternityQwQ/MetalUniversal) | `feature/metalfx-upscale-frameinterp` | `f1e2de78637b9bee4738069da4c5d8f94d30a86e` |
| [functy23/metallum-meteor-client](https://github.com/functy23/metallum-meteor-client) | `master` | `402bf01eab3e8f483f75897f82cb7a18146b087d` |
| [coderobe/metallum-patches](https://github.com/coderobe/metallum-patches) | `master` | `f8cf65f6993da64bd1734af63104768f0c2275fc` |

Оценка производительности следует `docs/BENCHMARKING.md`: аудит исходников не
считается доказанным FPS-выигрышем. В этом аудите не заявляется ни одного
performance-улучшения; принятые изменения только усиливают fail-closed поведение.

## Что заимствовано

### `coderobe/metallum-patches`: реентерабельное закрытие texture view

- Источник идеи: [`f8cf65f`](https://github.com/coderobe/metallum-patches/commit/f8cf65f6993da64bd1734af63104768f0c2275fc),
  `MetalGpuTextureView.close()`.
- Локальная реализация: `closed` публикуется до постановки native view в
  deferred-destruction queue и до `removeView()` родительской texture.
- Зачем: будущий release callback или instrumentation hook может реентерабельно
  вызвать `close()`. Теперь такой вызов не может дважды поставить handle на
  уничтожение или дважды уменьшить view count.
- Граница доказательства: это defensive lifecycle fix, а не воспроизведённое
  падение и не FPS-оптимизация.
- Откат: отдельный локальный commit `9c33fde`.

### `coderobe/metallum-patches`: не обрезать transient upload молча

- Источник идеи: [`f8cf65f`](https://github.com/coderobe/metallum-patches/commit/f8cf65f6993da64bd1734af63104768f0c2275fc),
  `MetalTransientMemory.upload()`.
- Локальная реализация: перед каждой `memCopy` проверяется полный
  исходный range. При нарушении allocator contract кадр падает явно, до копирования
  части буфера.
- Зачем: прежний `min(available, requested)` мог скрыть ошибку allocator-а. GPU slice
  при этом сохранял полный размер, а его хвост мог остаться stale/uninitialized. Это
  хуже детерминированного отказа.
- Проверка: exact-boundary и negative-range cases вынесены в
  `TransientMemoryRangeTests` и выполняются через `rendererArchitectureUnitTest`.
  Отдельно проходят `metalRuntimeUnitTest` и immutable G4 contract.
- Граница доказательства: normal allocator path не воспроизвёл overflow; это
  стабилизация аварийного поведения, а не performance claim.
- Откат: production guard — `a0fdb43`; его изолированная test wiring вне
  G4 evidence seam — `db5bf1b`. Для полного отката этого заимствования откатывать
  их в обратном порядке.

## Что изучено, но не перенесено

### `21Z121Z1/MetalUniversal`

Наиболее интересна не старая default-ветка, а активная
`agent/metalfx-motion-coverage-26.2`. В ней есть depth, motion, reactive mask, reset,
CUTOUT/hand coverage, Frame Synthesis contracts и `CAMetalDisplayLink`-презентер. Полезные
контрактные идеи видны в [`f03935c`](https://github.com/21Z121Z1/MetalUniversal/commit/f03935c92048e3ca8037c7a21610c064a9377ca3)
и [`89b9f58`](https://github.com/21Z121Z1/MetalUniversal/commit/89b9f584acd0c91fadfd468380e6b72ed9893026).

Перенос не нужен:

- наш `FrameSynthesisContract` и native FI validation уже отклоняют missing/stale inputs,
  generation mismatch и discontinuity, сохраняют mandatory real frame и требуют re-prime;
- наш Temporal уже использует `D32Float`, `RG16Float`, `R8Unorm`, reverse-Z,
  `inputContentWidth/Height`, раздельный SDR UI и строгий FrameState ABI;
- их previous-vertex history из [`7ccd758`](https://github.com/21Z121Z1/MetalUniversal/commit/7ccd758463479a0c670c6f300b344a2cdaf132c7)
  копирует CPU-вершины и создаёт `HashMap`/`ArrayList`/`float[]` в frame loop, что
  нарушает наш allocation/lifetime contract;
- их `CAMetalDisplayLink` нельзя добавить вторым владельцем drawable к нашему
  scheduler/пути `nextDrawable()`;
- их собственный README всё ещё оставляет production gate Frame Generation закрытым.

Итог: хороший независимый reference, но не лучший runtime path для нашей архитектуры.

### `pentaoa/lodeframe`

Это самостоятельная shader-pack архитектура: program conditions, preprocessing,
custom uniforms, textures, world gbuffers, shadow/composite/final graph и Iris/Sodium mixins. Она не
разлагается на безопасные локальные patches. В том числе:

- [`3437546`](https://github.com/pentaoa/lodeframe/commit/3437546d261fd737d08d9f6923b21aef17e11c16)
  выделяет offline GLSL-to-MSL translation, но она завязана на их pack API;
- [`05683ff`](https://github.com/pentaoa/lodeframe/commit/05683ff320c3b3826708166feb55b074fce7c120)
  освобождает `CAMetalLayer` при shutdown, что у нас уже делают rollback и `MetalDevice.close()`;
- [`70492d6`](https://github.com/pentaoa/lodeframe/commit/70492d66748a04a98913365a22137be4acbe06b3)
  и [`89eb995`](https://github.com/pentaoa/lodeframe/commit/89eb99598fb57ad42b08d5404f0213a484861ed4)
  закрывают triangle-fan edge cases, уже покрытые нашим `drawIndexedNative`.

Итог: ничего не перенесено. Shader packs требуют отдельно согласованного
milestone с собственным acceptance suite, а не скрытого включения Iris в текущий renderer.

### `NetworkCats/metallum`

В форке есть Metal 4 backend, Metal 3 fallback, argument tables, residency/barriers,
group commit, pipeline/archive и параллельная compilation. Основные commits:
[`84afc4b`](https://github.com/NetworkCats/metallum/commit/84afc4bb4ce8eaa5845fdeaa40ce60fad7567623),
[`2956575`](https://github.com/NetworkCats/metallum/commit/2956575046bd655a5dce5e98de0c063527253f5c),
[`358db12`](https://github.com/NetworkCats/metallum/commit/358db12299f923b0a278ff29df97438f50609efa).

Перенос отклонён:

- на M1 Pro наш подтверждённый bottleneck — terrain fragment ALU/L6, а не Metal
  submission/binding;
- Metal 4 command buffers не удерживают ресурсы. Смена наших three-slot ownership,
  deferred destruction и Sodium indirect snapshots рискует вернуть AGX lifetime faults;
- в истории форка после Metal 4 потребовались ещё четыре corrective commits
  ([`cdd3e07`](https://github.com/NetworkCats/metallum/commit/cdd3e0702af6e9ac163fe95c0c9a560bcab8a34c),
  [`ef71f21`](https://github.com/NetworkCats/metallum/commit/ef71f2162fb298b2b9009cba75ef8dc498b5c74f),
  [`5ec6a41`](https://github.com/NetworkCats/metallum/commit/5ec6a4188f3f485584225fe9e70c42c23a5b53ef),
  [`5d07a7c`](https://github.com/NetworkCats/metallum/commit/5d07a7c218b47b600547b685953d2e8014697b9f));
- upstream archive identity не включает в filename наши artifact/source/metallib digests и не
  соответствует нашему generation/resource-pack invalidation contract;
- upstream async compiler тут же ждёт `waitUntilCompleted`, поэтому сам по себе не
  доказывает asynchronous publication;
- сопоставимых raw benchmark receipts в форке нет, поэтому performance этого
  пути для нас остаётся `UNKNOWN`.

Единственная сохранённая идея — узкий opt-in POC для одного builtin PSO через
`MTL4Compiler`/archive. Он уже описан в `Metal4.md` и не должен появиться в production code до
положительного in-game `supportsFamily(.metal4)` и двух воспроизводимых compilation-correlated
hitch-замеров.

### `prodbyichigo/metallum-metalfx`

Название не означает наличие `MTLFXTemporalScaler`. Реальные изменения в
[`54c7dbc`](https://github.com/prodbyichigo/metallum-metalfx/commit/54c7dbc5e9965af9331f4fcb67f7ca4e9ba8d738)
касаются encoder reuse, texture views, redundant bindings, `MTLBinaryArchive`, drawable count и
локальных metrics.

Ничего не перенесено:

- blit encoder reuse, three in-flight submits, lazy mip-view lifetime и dirty-binding batching у нас
  уже есть;
- broad пропуск uniform binding по Java identity опасен: backing может смениться при
  той же wrapper identity;
- hard-coded `maximumDrawableCount = 3` не доказывает, что у нас есть drawable stall;
- их log-only metrics не связывают source, artifact, route, admission и thermal state, поэтому
  не заменяют наш JSONL/attestation pipeline;
- performance claims не подкреплены сопоставимыми Tier C receipts.

### `EternityQwQ/MetalUniversal`

Интересен как macOS/iOS развилка, но ветка `feature/metalfx-upscale-frameinterp` для
нашего runtime-контракта слабее:

- Temporal получает null depth, а motion texture остаётся нулевой;
- нет reactive mask, `inputContentWidth/Height`, reverse-Z, FI planes/FOV и отдельного
  generated-to-real presenter;
- jitter жёстко задан в 8 phases независимо от scale;
- BGRA8 path не совпадает с нашим FP16/HDR output contract.

Даже crash fixes из
[`f4f3ac6`](https://github.com/EternityQwQ/MetalUniversal/commit/f4f3ac66148dad54122bebdbff5aaa697e943f51)
не переносились механически: zero-size guards и deferred release уже покрыты нашей
более строгой архитектурой.

### `functy23/metallum-meteor-client`

[`555c41e`](https://github.com/functy23/metallum-meteor-client/commit/555c41e78f1e2a77b246ed8313051f38fe500831)
адаптирует Meteor `IGpuDevice` и переносит scissor state в следующий render pass. Это
реальная compatibility-идея, но она требует точной версии Meteor и его API. Без
совместимого compile-only artifact и live-воспроизведения `WView`-crash мы не добавляем
новый optional mixin и global-scissor owner.

[`591c5af`](https://github.com/functy23/metallum-meteor-client/commit/591c5af4582822edf82780c953f2f76d361429f9)
также пытается продолжить draw с закрытым texture view. У нас это отклонено: пропуск
descriptor может оставить previously-bound texture и скрыто нарисовать её в новом draw.
Наш fail-closed exception сохранён до того, как будет доказан корректный null/unbind contract.

### `coderobe/metallum-patches`

Помимо двух принятых safeguards остальная ветка старше нашей архитектуры.
Built-in uniforms, active SPIR-V interface filtering, color write mask, semaphore, pools, indirect
draw и destruction-queue isolation у нас уже реализованы. Изменение empty scissor из
`0x0` в `1x1` не перенесено: оно может нарисовать один пиксель вместо точного
«не рисовать».

## Лицензии и границы проверки

Верхнеуровневые `LICENSE` во всех проверенных репозиториях совместимы с нашим
MIT `LICENSE`. Для фактически перенесённых идей provenance сохранён в этом файле и в
commit messages. У Lodeframe есть отдельный shader submodule; его лицензия не проверялась,
поскольку код из submodule не переносился.

Проверки исходников, unit/native tests и Metal Validation не заменяют живую проверку
внешнего вида, motion, long-session stability и on-glass FI cadence. Ни одно отклонённое
заимствование не должно попадать в production без указанного выше нового доказательства.
