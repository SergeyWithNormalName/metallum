# Metallum Apple Silicon Optimization Sprint

Date: 2026-08-16
Hardware: Apple M1 Pro, built-in 3024×1964 Retina display, 120 Hz, Native HDR/EDR
Branch: `codex/apple-silicon-native-sprint`

**Verdict:** no renderer optimization was accepted. The bounded FP16 candidate did
not clear Tier B, and the exact texture-backed L6 candidate caused a reproducible
`-30.264%` FPS / `+14.304 ms` GPU-p95 regression. Both were fully reverted. The
result is therefore the sprint's acceptable outcome, not its good/excellent target:
the major Apple-specific hypotheses were narrowed without a quality regression, but
the production renderer is not faster.

## 1. Starting baseline

The renderer starting point was `7292be9`. Three benchmark-only commits made the
requested route matrix and diagnostic comparison attestable; the first clean sprint
baseline was then captured at `c2b03b6772a99f6c2dd988804e86a75d24508bae`.

| Field | Starting value |
|---|---|
| Branch | `codex/apple-silicon-native-sprint` |
| Clean state | clean |
| Source SHA-256 | `410140d3c34d50d83adb047b67a02cfbcb5ebcc0ff64ab3e4173ce92780bff24` |
| Built artifact SHA-256 | `2ed69bdc24b5bbd91045125c6a0cf3e793138383c6c29032a74e787c7436e1a7` |
| Native dylib SHA-256 | `15501b5592e5f191cb59243e71fd229dce89628f9762348c05c5e75feafe96e2` |
| Contract | Native HDR, Balanced Advanced, MetalFX OFF, VSync OFF, 3024×1964 |

`./gradlew check`, native build/validation, analyzer self-test, and benchmark
preflight passed before candidate work. The compact `600+600` baseline used detailed
GPU timing and is diagnostic evidence, not production FPS acceptance evidence.

| Route | FPS | GPU p95 | 1% / 0.1% low | L3 lights / clusters |
|---|---:|---:|---:|---:|
| Cave | 32.452 | 34.7163 ms | 27.422 / 26.806 | 2048 / 8928 |
| Nether | 32.215 | 34.6932 ms | 27.976 / 26.261 | 2048 / 8928 |
| Static | 40.810 | 26.0265 ms | 30.230 / 29.863 | 565 / 8928 |

All three were `COMPLETE`, nominal, `resolved_lighting_model=advanced`, and had no
relevant fallback. Raw paths are listed in section 19.

## 2. Apple hardware/API facts verified from primary sources

| Apple claim | Primary evidence | Decision relevance |
|---|---|---|
| Apple GPUs use tile-based deferred rendering and can remove hidden fragment work when depth/side-effect semantics permit it. | [WWDC20: Optimize Metal apps and games with GPU counters](https://developer.apple.com/videos/play/wwdc2020/10632/?time=1187), [WWDC20: Harness Apple GPUs with Metal](https://developer.apple.com/videos/play/wwdc2020/10602/?time=966) | Audit final MSL and actual PSOs, not source GLSL. |
| Resource usage/storage declarations help Metal choose GPU layouts; GPU-optimized texture contents must not be disabled without need. | [Optimizing texture data](https://developer.apple.com/documentation/metal/optimizing-texture-data), [`allowGPUOptimizedContents`](https://developer.apple.com/documentation/metal/mtltexturedescriptor/allowgpuoptimizedcontents), [memory bandwidth guidance](https://developer.apple.com/documentation/xcode/measuring-the-gpus-use-of-memory-bandwidth) | Search for broader-than-use descriptors, CPU-visible or buffer-backed hot textures. |
| Native 16-bit values can reduce register and data movement pressure, but conversion must respect range and promotion rules. | [WWDC20 GPU counters](https://developer.apple.com/videos/play/wwdc2020/10632/?time=891), [WWDC21: Discover compilation workflows in Metal](https://developer.apple.com/videos/play/wwdc2021/10153/?time=812) | Try only a bounded, range-proven hot-shader specialization. |
| MPS image histogram computes per-channel, linearly ranged histograms. | [MPSImageHistogramInfo](https://developer.apple.com/documentation/metalperformanceshaders/mpsimagehistograminfo), current SDK `MPSImageHistogram.h` | It is not automatically equivalent to Metallum's log-luminance histogram. |
| Explicit synchronization should match real producer/consumer dependencies; a fence wait pauses later work in its scope. | [Metal resource synchronization](https://developer.apple.com/documentation/metal/resource-synchronization), [`MTLFence`](https://developer.apple.com/documentation/metal/mtlfence) | Build a dependency DAG before changing scheduling. |
| Tile shaders/imageblocks can keep data tile-local, but only within a compatible render-pass architecture. | [Apple tile shaders](https://developer.apple.com/videos/play/tech-talks/604/?time=208) | Bound the removable cluster/list cost before a Forward+ rewrite. |
| M1-series GPUs are Apple GPU family 7 and expose tile shaders, imageblocks and mesh shading; API support alone says nothing about workload speedup. | [Metal feature tables](https://developer.apple.com/metal/capabilities/) (May 21, 2026 tables) | Feature availability passed; performance gates still apply. |

The local authority was the macOS 27.0 SDK in Xcode beta. No conclusion in this
report relies on a newer API being inherently faster.

## 3. Current Metallum Apple-specific architecture

- Minecraft/Sodium GLSL is patched, compiled to SPIR-V, translated to MSL by
  `MetalCrossShaderCompiler`, compiled/cached at runtime, then used by
  `MetalCompiledRenderPipeline`. Built-in Clear/Cluster/L5/L6/HDR/Present MSL is
  precompiled into `metallum.metallib`.
- A production frame uses one Metal 3 command buffer. Cluster construction, L5
  updates, optional L6 updates, L4 shadow maps, terrain/entity rendering, HDR and
  presentation are encoded in dependency order. Three frames may be in flight.
- Java-owned textures are private and normally hazard-untracked; the global
  `MTLFence` is therefore part of their explicit correctness contract. Native HDR,
  MetalFX and L5 workspaces use private, tracked resources.
- The main scene is actual-radiance `RGBA16Float`. Presentation uses an
  `RGBA16Float` `CAMetalLayer`, extended-linear-sRGB, high dynamic range, no system
  tone mapping, and real-frame presentation. MetalFX and FI were off for this goal.
- L3 uses private light/cluster buffers. L5 is a private tracked heap of occupancy,
  optical, chromatic and metadata buffers. L6 is a private untracked raw buffer:
  the static visibility atlas followed by a dynamic-compute suffix.

## 4. HSR audit

**Classification:** `HSR_OPTIMAL`. No code candidate was justified.

**APPLE CLAIM:** side-effect-free opaque fragments with ordinary depth/color output
are eligible for Apple's automatic hidden-surface removal; discard, fragment-depth
output and externally visible fragment side effects can restrict early rejection.

**PROJECT FACT:** the final generated production variants and PSO metadata show:

| Path | Final MSL | PSO/resource state | HSR result |
|---|---|---|---|
| Sodium solid terrain | Two ordinary outputs: scene color and R8 reactive MRT; both assigned on every return. No `discard_fragment`, depth/sample-mask output, atomics, buffer writes or texture writes. Device buffers are read-only. | `GREATER_THAN_OR_EQUAL`, depth write on, blending off, color write mask all, reactive write mask red, culling on. | Opaque path has no identified Apple HSR blocker. |
| Sodium cutout terrain | Separate variant with `discard_fragment` for `ALPHA_CUTOUT=0.5`. | Depth semantics retained; blending off. | Correctly remains alpha-tested; not treated as opaque. |
| Sodium translucent terrain | Separate `ALPHA_CUTOUT=0.01` discard plus alpha blending. | Existing sorted/blended semantics retained. | Correctly excluded from the opaque HSR claim. |

`[[early_fragment_tests]]` is absent. Adding it would not remove a discovered blocker
and would be unsafe to force across cutout/translucent variants. The ordinary solid
PSO is already isolated from their discard/blend semantics. The historical generic
"ordinary-only Terrain PSO" idea is therefore an `EXACT_RETRY` without new evidence
and was not attempted. The earlier Apple-TBDR `.dontCare` attachment experiment was
also not repeated: it recovered only about `0.019 ms` locally and regressed the frame.

Final generated source: `run/logs/apple-sprint/hsr-final-msl-v2/`; audit and hashes:
`run/logs/apple-sprint/hsr-final-msl-audit.txt` and
`run/logs/apple-sprint/hsr-final-msl-sha256.txt`.

## 5. Resource/texture layout audit

Unless a row says otherwise, textures are 2D, one mip, one sample, not buffer-backed,
have no CPU mapping and request no pixel-format view. Metallum never disables
`allowGPUOptimizedContents`; the descriptor default remains available to Metal.

| Resource | Format and extent | Storage / hazard / usage | Writers → readers | Lifetime and exact requirements |
|---|---|---|---|---|
| Main scene HDR | `RGBA16Float`, render extent | private / untracked / renderTarget+shaderRead | world+entities → HDR, presentation, temporal | Persistent frame target; shader read and full HDR precision are required. |
| Main depth | `Depth32Float` (or stencil variant), render extent | private / untracked / renderTarget+shaderRead | depth-tested passes → temporal/presentation/depth consumers | Cannot be memoryless because later passes read it. |
| Scene reactive/semantic MRT | `R8Unorm` or semantic `RGBA8Unorm`, render extent | private / untracked / renderTarget+shaderRead | terrain/material pass → temporal/HDR consumers | Write mask matches the declared channel(s); no view or shader write. |
| CAMetalLayer drawable | HDR `RGBA16Float`, 3024×1964 | driver-owned drawable / render target | presentation → display | Exact extended-linear-sRGB output; no hidden intermediate introduced. |
| Actual-HDR/UI world intermediates | `RGBA16Float`, full relevant extent | private / tracked / renderTarget+shaderRead | HDR/UI composite → presentation/MetalFX | Conditional persistent workspace; no CPU access. |
| HDR emission | `RGBA16Float`, quarter scene extent | private / tracked / renderTarget+shaderRead | fused 4×4 extract → bloom/presentation | Persistent workspace; exact bloom and average-luminance payload. |
| HDR bloom | `RGBA16Float`, quarter scene extent | private / tracked / shaderRead+shaderWrite | combined compute blur → presentation | The only audited hot texture with `shaderWrite`; compute output requires it. |
| Histogram/exposure | 64×`uint32` private buffer plus private adaptive state | private / default tracked / buffer read-write | extract atomics → reduce → presentation | 256-byte persistent histogram; not a texture/layout candidate. |
| UI masks | `RGBA8Unorm`, full display extent | private / tracked / renderTarget+shaderRead | UI compare/seed → presentation | Allocated only for the relevant UI path; no shader write/view. |
| Temporal depth/motion/reactive/classification | `Depth32Float`, `RG16Float`, `R8Unorm`, `R8Unorm` | private Java textures / untracked / renderTarget+shaderRead | scene/replay → MetalFX temporal | Three-slot conditional ring; MetalFX OFF in the primary contract. |
| MetalFX color/input/output/history | `RGBA16Float`, render/display extent | private / tracked / scaler-required usage union | precompose/scaler → presentation/history | Conditional and absent from the measured primary path. Required scaler usage is not broader-than-use. |
| L4 sun depth | depth textures, cascade extent | private / untracked / renderTarget+shaderRead | shadow pass → world fragment | Persistent and shader-read; real producer/consumer dependency. |
| L5 occupancy/optical/chromatic/metadata | Raw buffers in private tracked heap | private / tracked; shared staging only for CPU upload | blit/compute update → L6/world | World-persistent exact bytes; no high-bandwidth shared payload. |
| L6 visibility atlas | Exact 8-byte logical hits in one private raw buffer, 32/64/128 MiB static budget plus dynamic suffix | private / untracked / COPY_DST plus native fragment/compute binding | static blit + dynamic compute → world/entity fragment | Persistent; exact page identity, READY/STALE and invalidation semantics. |
| L3 cluster resources | Light/header/index/parameter/stat buffers | private/default tracked; bounded shared upload ring | upload/compute → world/entity fragment | Persistent context plus in-flight upload; no CPU-visible hot result. |

**Gate result:** no high-bandwidth texture had an unnecessary `shaderWrite`,
`pixelFormatView`, shared storage, CPU mapping or buffer-backed texture declaration.
The main scene/depth and HDR workspace already use the narrow capabilities their
actual readers/writers require. Changing descriptors without a semantic mismatch
would be flag churn, not a measurable hypothesis, so no resource candidate was made.

## 6. 16-bit shader audit

**Classification:** `RELATED_TO_PRIOR_REJECTED`. The prior blanket half conversion
was correctly rejected for conservative culling/world/depth math; this candidate was
materially narrower.

**APPLE CLAIM:** bounded half values may reduce register pressure and improve SIMD
occupancy/throughput. **PROJECT FACT:** the hot generated WORLD_OPAQUE MSL has a long
FP32 direct-light/L6 function, while the four normalized soft-filter weights are
bounded after all exact coordinate/address calculations. **HYPOTHESIS:** storing only
those weights as half could shorten FP32 live ranges without changing L6 texels.

The candidate left world/camera positions, matrices, depth, page/address arithmetic,
visibility samples, HDR accumulation and output in FP32/32-bit integers. The
predeclared parity epsilon was `0.002`; range/property tests, shader goldens, large
coordinate/seam/material checks and the Static visual comparison passed.

| Interleaved Cave Tier B | FPS | GPU p95 | 1% low | 0.1% low |
|---|---:|---:|---:|---:|
| Baseline mean (B1/B2) | 32.544 | 34.125 ms | 27.953 | 26.742 |
| FP16 mean (C1/C2) | 32.777 | 33.704 ms | 27.826 | 27.168 |
| Delta | +0.717% | -0.422 ms (-1.235%) | -0.456% | +1.594% |

This is weak/noisy, below both Tier-B gates, and includes a 1% low regression. It did
not proceed to Tier C. The candidate and its tests were reverted; the independent
visual masks were retained. **REJECTED. DO NOT RETRY** soft-weight-only FP16 without
new compiler/occupancy evidence showing that these values drive register pressure.

## 7. L6 exact texture experiment

**Classification:** `NEW`. It did not repeat tile-major 2×2, four-tap invariant
batching, prepared hard-lookup reuse, or receiver-plane/cache-edge reuse.

**APPLE CLAIM:** native textures can use texture-specific layouts and access paths.
**PROJECT FACT:** exact L6 static hits are currently fetched as packed `uvec2` values
from a large raw buffer. **HYPOTHESIS:** the same eight-byte logical hits might execute
more efficiently as Apple-native texture reads.

### Feasibility and parity gate

The static region was representable as a row-major `RG32Uint` 2D texture without
changing bits, page identity, four tap offsets, weights, seams or state transitions.
The dynamic suffix remained a buffer. The first candidate used explicit integer
texel reads only: no filtering, bilinear interpolation, gather, format reduction,
copy in the frame loop or changed arithmetic.

The old-vs-new oracle covered all cube faces, face edges/corners, page/cache bounds,
every tap offset, READY/STALE transitions, invalidation/update, moving camera and the
entity receiver path. Storage values were bit-exact. ABI, generated MSL, native
validation, Metal validation and visual comparisons passed; comparison screenshots
were visually indistinguishable apart from HUD/timing pixels.

Candidate identity: source
`6ef880b9fee4b0b1e3adff09213f38e248b5c52720cc45fcf37bf87f10336a8a`,
artifact `bab3385ef5326800830076f18a5d280a8be04d3453760cd028705325140d9a35`,
candidate-diff SHA-256
`26d0b3fd2ea846dc54ca80b5aa862018ab8c275b6e4252790e6174a896253231`.

### Performance gate

| Interleaved Cave Tier B | FPS | GPU p95 | 1% low | 0.1% low |
|---|---:|---:|---:|---:|
| B1 control | 32.749 | 33.422 ms | 28.132 | 27.594 |
| C1 texture | 22.862 | 47.407 ms | 20.884 | 20.087 |
| B2 control | 32.299 | 33.680 ms | 27.077 | 26.764 |
| C2 texture | 22.500 | 48.302 ms | 19.913 | 19.175 |
| Mean delta | **-30.264%** | **+14.304 ms (+42.632%)** | **-26.104%** | **-27.771%** |

All four baseline/candidate pairings regressed. Stage detail attributed the change to
WORLD_OPAQUE: control-run p95 values `16.193/16.626 ms`, candidate-run values
`21.890/22.729 ms`; entities, HDR and cluster work remained essentially unchanged.
Advanced stayed active with 2048 lights/8928 clusters and no voxel stale/rejects.

Per the neutral/regression stop rule, Nether, Static and L6 Dynamic performance runs
were deliberately not spent after the reproducible Cave 2×2 result. The entire
candidate was reverted. **REJECTED. DO NOT RETRY** this exact row-major `RG32Uint`
static split on M1 Pro without materially new layout/cache-counter evidence. Gather
stage was not reached.

## 8. HDR/MPS experiment

**Classification:** `NEW`; stopped at the exactness/feasibility gate.

Metallum's production fragment pass already combines two required jobs at quarter
resolution: it averages each exact 4×4 block into luminance, emits bloom data, maps
`log2(Y)` from `[-12,+4]` into 64 quarter-stop bins, and atomically increments one
histogram. Exposure reduction consumes and clears those same 64 bins.

Current SDK `MPSImageHistogram` instead bins each source channel independently over
linear per-channel min/max bounds and stores R bins, then G, B and optionally A.
`histogramForAlpha` controls inclusion; it does not select an alpha-only derived
luminance channel. Direct MPS over the scene or emission texture therefore changes
the histogram definition. An exact MPS version would first need a new log-luminance
texture/attachment or extra pass and then MPS, while the current histogram is fused
into mandatory bloom extraction.

That violates the "same definition, no extra full-resolution copies" gate and has no
defensible speedup mechanism. No MPS code or A/B was created. **STOPPED / NOT
APPLICABLE**, not kept merely because it is an Apple framework.

## 9. Scheduling/concurrency audit

**Classification:** `RELATED_TO_PRIOR_REJECTED` global-fence removal.

| Stage | Reads | Writes | Must precede |
|---|---|---|---|
| L3 upload/cluster build | CPU upload ring, light parameters | GPU lights, headers, indices | world/entity direct lighting |
| L5 upload/update | staging packets, prior clipmap state | occupancy, optical, chromatic, metadata | dynamic L6 and world/L6 fallback |
| Dynamic/static L6 update | L5, page packets/staging | L6 atlas/suffix | world/entity visibility reads |
| L4 shadow passes | caster geometry | cascade depth | world/environment shadow reads |
| World/entity rendering | clusters, L4, L5, L6, materials | scene HDR, depth, reactive | HDR/temporal/presentation |
| HDR extract | scene HDR | emission, 64-bin histogram | exposure reduction and bloom |
| Exposure/bloom | histogram/emission | adaptive state/bloom | presentation |
| Presentation | scene, depth, reactive, HDR outputs, UI | drawable | present |

The frame uses one command buffer and explicit fence scopes because several Java
resources are hazard-untracked. Most major edges are real. Cluster build could in
principle overlap some L5/L4 work before their join, but the fresh dense-route cluster
p95 is only about `1.0–1.19 ms`, L5 is usually sparse/no-op, and no captured GPU
timeline showed an idle serialization gap or a >1 ms critical-path opportunity.

The concurrency gate requires two meaningful independent stages, a measured gap and
plausible whole-frame shortening. It failed. No queue/command-buffer/fence change was
made; theoretical overlap was not substituted for present-time evidence.

## 10. Tile-shader feasibility

Apple family 7 supports tile shaders and imageblocks, and Apple's tile Forward+
architecture can avoid a light-list round trip inside a compatible render pass.
Metallum's removable upper bound did not pass the build gate:

- fresh primary-route cluster-build p95: `0.465 ms` Static, `1.185 ms` Nether,
  `1.317 ms` Cave in the compact diagnostic baseline;
- current historical dense-light evidence: about `0.904 ms` average / `1.030 ms`
  p95;
- direct-light equations, shadow/L6 work, material response and fragment traversal
  would remain.

Even deleting the measured cluster stage entirely is below the required
`1.5–2.0 ms` plausible whole-frame upside. Unknown memory traffic is not evidence.
**STOPPED** before prototype; no tiled Forward+ architecture was built.

## 11. Other Apple-specific findings

- **Mesh shaders/GPU-driven terrain:** M1 support is confirmed, but no current
  geometry/binning or CPU-submission measurement shows a substantial heavy-route
  bottleneck. WORLD_OPAQUE dominance alone does not prove geometry cost. Stopped.
- **Metal 4:** final runs report the Metal 3 executor. Cave is GPU-bound (about
  33.8 ms GPU p95), and there is no isolated CPU-submission hypothesis. Broad
  migration remains deferred.
- **Presentation/ProMotion:** the layer is already `RGBA16Float`, extended-linear
  sRGB, high dynamic range, `toneMapMode=.never`, and does not use generated frames
  for FPS. No goal-relevant `CAMetalDisplayLink` defect was found; FI remains a
  separate investigation.
- **Agent routing:** a bounded read-only repository census was routed to Gemini. The
  isolated worker checkout lacked the Gradle wrapper JAR, and the router's
  anti-cheating verifier rejected an empty read-only diff. No worker result was
  credited and no renderer file was modified by a worker.
- **Benchmark evidence:** the Static final run exposed the known contradiction where
  an early server-freeze marker preceded `ARMED`, while the shell and release
  analyzer expected incompatible orders. The harness now freezes at the same early
  time but publishes exactly one proof marker after `ROUTE_APPLY`; both validators
  enforce the same order. This is measurement infrastructure, not an FPS change.

## 12. Accepted optimizations

**None.** No renderer, MSL, PSO, resource, L6, HDR or scheduling change survived
production Tier B. There is intentionally no performance commit.

Accepted non-performance infrastructure:

- deterministic Cave/Foliage/Rain routes and M1 Pro capability/matrix records;
- diagnostic-mode metadata comparison;
- deterministic post-route freeze-evidence publication and aligned release checks.

## 13. Rejected optimizations

| Candidate/gate | Classification | Decision |
|---|---|---|
| Opaque HSR blocker fix | audit only | `HSR_OPTIMAL`; no blocker/candidate |
| High-bandwidth descriptor narrowing | audit only | no broader-than-use property found |
| L6 soft weights in half | related, bounded new specialization | REJECTED: +0.717% FPS, -0.422 ms GPU p95, 1% low worse; below gate |
| Exact static L6 `RG32Uint` texture | NEW | REJECTED: -30.264% FPS, +14.304 ms GPU p95 |
| MPS histogram | NEW feasibility | STOPPED: different histogram contract or extra transform/pass |
| Concurrent command-buffer work | related to prior fence rejection | STOPPED: no measured meaningful independent critical-path pair |
| Tile Forward+ | feasibility | STOPPED: maximum measured removable stage <1.5 ms gate |
| Mesh shaders | feasibility | STOPPED: no geometry/submission bottleneck evidence |
| Broad Metal 4 migration | policy | DEFERRED |

All failed production candidates were reverted. Independently useful benchmark
routes, tests, evidence and visual comparison artifacts were retained.

## 14. Cumulative whole-frame improvement

Accepted renderer delta: **0.000 FPS, 0.000%, 0.000 ms GPU**.

The final native dylib SHA-256 is
`15501b5592e5f191cb59243e71fd229dce89628f9762348c05c5e75feafe96e2`,
bit-identical to the preserved starting dylib. `git diff 7292be9..a7cf55f` contains
benchmark routes, analyzer/controller/tooling and tests only; there is no diff under
`src/main/native`, `src/main/metal`, renderer, lighting or Metal backend production
packages. The final aggregate Java artifact hash differs because the benchmark
controller is packaged, not because a renderer optimization survived.

## 15. Final benchmark matrix

The five rows below are fresh `1800+3000` production, no-detail release runs on clean
`a7cf55f`, source
`388e3b8141bac3065327b82abd999f715ea53f85d9d156fdcbfb343a83bdf35c`,
artifact `77092092269ddf026de4e71fe4e4c42073237d2b44062c4515c3717d38a93017`.
Every row has an accepted receipt, nominal thermal state, Advanced/Balanced, native
HDR, MetalFX OFF and zero dropped timing events.

Because no renderer change was accepted and the native artifact is bit-identical,
START and END denote the same production renderer state. They are intentionally not
computed by comparing the detail-enabled starting diagnostics with no-detail final
runs.

| Route | START FPS / GPU p95 | END FPS / GPU p95 | Delta FPS / GPU | 1% / 0.1% low | L3 lights; L6 READY/STALE/APPROX/FAIL |
|---|---:|---:|---:|---:|---:|
| Cave | 32.254 / 33.804 ms | 32.254 / 33.804 ms | 0.000 / 0.000 ms | 27.743 / 26.755 | 2048; 87/0/1961/0 |
| Nether | 32.925 / 33.225 ms | 32.925 / 33.225 ms | 0.000 / 0.000 ms | 28.202 / 26.972 | 2048; 87/0/1961/0 |
| Static | 41.081 / 25.885 ms | 41.081 / 25.885 ms | 0.000 / 0.000 ms | 30.159 / 29.448 | 565; 38/0/527/0 |
| Foliage/Water | 109.390 / 11.045 ms | 109.390 / 11.045 ms | 0.000 / 0.000 ms | 55.210 / 49.948 | 397; 109/0/288/0 |
| Rain | 39.851 / 26.729 ms | 39.851 / 26.729 ms | 0.000 / 0.000 ms | 29.037 / 27.371 | 565; 38/0/527/0 |

L5 reports zero stale/rejected work at the selected windows. L6 Dynamic was not
rerun in the final census because the L6 candidate was reverted and did not affect
production.

## 16. Remaining top bottlenecks

1. WORLD_OPAQUE remains the largest attributed heavy-route stage (`~16.5 ms` p95 in
   Cave/Nether compact detail; `19.7 ms` Static). Prior ablation only classifies its
   remaining cost as `UNKNOWN`; it does not prove bandwidth, occupancy or geometry.
2. The current exact L6 raw-buffer fetch/evaluation is expensive, but the obvious
   Apple texture path made it dramatically worse. Storage architecture is no longer
   a justified lever without new counter evidence.
3. Full-display actual-radiance mapping (`~1.83–2.86 ms` p95 in compact detail) and
   HDR extract/histogram (`~1.45–2.44 ms`) are meaningful, but MPS cannot replace the
   current exact fused histogram directly.
4. Cluster build is measurable but too small for a tile-Forward+ rewrite by itself.

## 17. Next Apple-specific recommendation

Capture one representative Cave frame with Xcode's Apple-GPU shader/memory counters
and the exact final generated WORLD_OPAQUE MSL. The decision must distinguish:

- register pressure/occupancy or spills in the long direct-light/L6 fragment path;
- texture/buffer cache and memory-bandwidth pressure;
- fragment invocation/HSR effectiveness;
- vertex/binning cost versus fragment cost.

Only then select one bounded candidate. If registers/occupancy dominate, specialize a
larger mathematically bounded FP16 block with a predeclared parity epsilon; the
soft-weight-only retry is forbidden. If raw-buffer fetch/cache dominates, first use
the capture to design an Apple-friendly exact layout different from rejected
row-major `RG32Uint`. If neither dominates, move to a lossless fusion of the measured
full-display HDR mapping path. This diagnostic changes which implementation is
allowed, so it satisfies the sprint's stop rule.

## 18. Commits

| Commit | Purpose |
|---|---|
| `45b792b` | establish the M1 Pro sprint matrix/capability record |
| `9956523` | add the deterministic Cave route |
| `c2b03b6` | compare diagnostic-mode metadata correctly |
| `eda2111` | publish freeze evidence after route application |
| `a7cf55f` | make shell/release analyzers enforce one freeze-event order |
| this documentation commit | this report and OptimizationHistory result |

There is no accepted renderer optimization commit. Rejected candidates were never
committed and are absent from the final tree.

## 19. Raw evidence paths

Starting diagnostics:

- `run/logs/metallum-benchmarks/20260816T010311Z-gc2b03b6772a9-clean-apple-sprint-baseline-cave-tierb-1-off.{raw.jsonl,summary.json}`
- `run/logs/metallum-benchmarks/20260816T010523Z-gc2b03b6772a9-clean-apple-sprint-baseline-nether-tierb-1-off.{raw.jsonl,summary.json}`
- `run/logs/metallum-benchmarks/20260816T010721Z-gc2b03b6772a9-clean-apple-sprint-baseline-static-tierb-1-off.{raw.jsonl,summary.json}`

HSR:

- `run/logs/apple-sprint/hsr-final-msl-v2/`
- `run/logs/apple-sprint/hsr-final-msl-audit.txt`
- `run/logs/apple-sprint/hsr-final-msl-sha256.txt`

FP16 B1/C1/B2/C2:

- `run/logs/metallum-benchmarks/20260816T014346Z-gc2b03b6772a9-dirty-apple-fp16-softweights-cave-b1-off.{raw.jsonl,summary.json}`
- `run/logs/metallum-benchmarks/20260816T014530Z-gc2b03b6772a9-dirty-apple-fp16-softweights-cave-c1-off.{raw.jsonl,summary.json}`
- `run/logs/metallum-benchmarks/20260816T014759Z-gc2b03b6772a9-dirty-apple-fp16-softweights-cave-b2-off.{raw.jsonl,summary.json}`
- `run/logs/metallum-benchmarks/20260816T014942Z-gc2b03b6772a9-dirty-apple-fp16-softweights-cave-c2-off.{raw.jsonl,summary.json}`
- `run/logs/apple-sprint/fp16-static-difference-{mask,amplified}.png`

L6 exact texture B1/C1/B2/C2 and visuals:

- `../apple-silicon-native-sprint-control/run/logs/metallum-benchmarks/20260816T023012Z-gc2b03b6772a9-clean-apple-l6-texture-cave-b1-off.{raw.jsonl,summary.json}`
- `run/logs/metallum-benchmarks/20260816T023154Z-gc2b03b6772a9-dirty-apple-l6-texture-cave-c1-off.{raw.jsonl,summary.json}`
- `../apple-silicon-native-sprint-control/run/logs/metallum-benchmarks/20260816T023402Z-gc2b03b6772a9-clean-apple-l6-texture-cave-b2-off.{raw.jsonl,summary.json}`
- `run/logs/metallum-benchmarks/20260816T023548Z-gc2b03b6772a9-dirty-apple-l6-texture-cave-c2-off.{raw.jsonl,summary.json}`
- `run/logs/apple-sprint/l6-visual-comparison/`
- `run/logs/apple-sprint/l6-texture-baseline-artifact/libmetallum.dylib`

Final accepted production census (each stem has `.raw.jsonl`, `.summary.json`,
`.minecraft.log`, `.console.log`, and `.accepted.json`):

- `run/logs/metallum-benchmarks/20260816T030930Z-ga7cf55f5dc35-clean-apple-sprint-final-production-static-off`
- `run/logs/metallum-benchmarks/20260816T031215Z-ga7cf55f5dc35-clean-apple-sprint-final-production-cave-off`
- `run/logs/metallum-benchmarks/20260816T031551Z-ga7cf55f5dc35-clean-apple-sprint-final-production-nether-off`
- `run/logs/metallum-benchmarks/20260816T031924Z-ga7cf55f5dc35-clean-apple-sprint-final-production-foliage-water-off`
- `run/logs/metallum-benchmarks/20260816T032104Z-ga7cf55f5dc35-clean-apple-sprint-final-production-rain-off`
