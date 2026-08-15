# Future Rendering Technologies Readiness

This document analyzes the readiness of the **Metallum** rendering engine for future graphic technologies, highlighting what is already present in the codebase, what is missing, and which systems must be modified to support them.

---

## 1. Temporal Upscaling

Apple Silicon GPUs support two upscalers: **Spatial** and **Temporal**.
- **Current Status**: Spatial MetalFX and **Temporal Upscaling** live in the same Sodium `MetalFX` group. `MetalFxTemporalScaling` persists three Temporal presets: **Quality** (75% linear resolution), **Performance** (50%), and **Ultra Performance** (40%). `JitterSequence` derives phase count from the actual render/display ratio, rather than from a preset name. Selecting either scaler clears the other setting, so exactly one owns a frame.
- **Production path**: Fixed Temporal presets use their render-sized inputs. Temporal Dynamic has no Spatial fallback: it is Native at 100% or MetalFX Temporal at the benchmark-validated 50% input. After 45 consecutive completed Native GPU samples above 16.5 ms (below 60 FPS), it enters Temporal. After 60 consecutive Temporal samples below 14.0 ms, it returns directly to Native. Spatial MetalFX is invoked only by its separately selected user mode. Dynamic Temporal uses the established Quality mip-bias sampler for a stable 50% input. While Temporal is active it uses MetalFX's `inputContent*` API: one display-sized scaler, color/depth inputs and output, with GPU blits packing only the active low-resolution world rectangle at the origin. The triple-buffered `RG16Float` motion and `R8Unorm` reactive ring is likewise display-sized but rasterized only inside that active rectangle.
- **Safety contract**: Format, usage, device, active-content and generation checks fail closed to native resolution. Dynamic Temporal keeps a warm standby while its policy renders at 100% Native: it retains the display-sized, GPU-private scaler, motion/reactive ring, staging depth/history and presentation PSOs without encoding an upscale. Thus normal Native → Temporal admission reuses those resources and resets history, rather than allocating/compiling on that threshold frame. The memory is released when the user chooses Off/Spatial, on a fail-closed Temporal error or device release; selecting Dynamic Temporal and a display/output generation change can still pay the one-time setup cost.
- **Automated proof**: Metal API/GPU validation encodes the MetalFX descriptor for all three fixed scales and the Dynamic path. The native runtime harness validates a `64×64 → 48×48` DRS transition inside unchanged `96×96` physical inputs, plus motion/reactive → Temporal → HDR-precompose/UI-backdrop, camera and depth-disocclusion cases.

---

## 2. Motion Vectors (Velocity Buffer)

Motion vectors represent the screen-space velocity of each pixel from the previous frame to the current frame:
- **Current Status**: Camera/static-depth reprojection and reactive invalidation are production Temporal inputs. Invalid depth, resets, non-finite projections and out-of-frame reprojections are reactive, preventing invalid history reuse.
- **Known quality boundary**:
  - **Entity motion**: Transform tracking, packet contracts, and shader math exist, but live Metal draw-buffer interception is still not wired. The replay remains fail-closed rather than inventing unsafe buffer pointers.
  - **Animated terrain**: Flowing liquids, wind-blown foliage and block breaking are currently covered by the camera/static-depth path rather than per-vertex velocity. A future draw-level velocity hookup can improve these cases without changing the scaler contract.

---

## 3. Temporal Rendering (TAA / History Blending)

Temporal Anti-Aliasing (TAA) blends the current frame with the historical accumulated frames to reduce alias-shimmering:
- **Current Status**: Temporal selection enables a deterministic sub-pixel Halton projection jitter. Apple MetalFX owns history resolve, filtering and internal clamping for every Temporal preset. Metallum supplies one-shot reset reasons for first frame, resize, teleport, world/dimension change and generation changes.
- **Future quality work**: Feed live per-pixel velocity for entities and animated terrain into the existing reactive/motion attachments. This is an input-quality improvement, not a missing Temporal scaler or history implementation.

---

## 4. Dynamic Resolution Scaling (DRS)

Dynamic Resolution alters the rendering target resolution based on GPU workload:
- **Current Status**: `MetallumDrsController` consumes completed presented-command-buffer GPU timing. Dynamic Temporal fixes the world extent at either 100% Native or 50% Temporal, with explicit 16.5 ms / 45-sample admission and 14.0 ms / 60-sample return thresholds. Spatial DRS resizes world targets only when Spatial is selected. While Temporal is already active, its display-sized MetalFX workspace updates only `inputContentWidth/Height`; color and depth are packed GPU-to-GPU into the active origin rectangle, with no CPU readback.
- **Boundary and pacing**: Minecraft still physically resizes world targets to reduce shaded pixels. Dynamic Temporal now keeps its fixed GPU resources and relevant presentation PSOs warm during a Native fallback, so the regular policy entry does not redo that cold work. It still changes the renderer generation and resets history, and the target resize or a first-time/recreated standby setup can produce an isolated long frame. The policy also uses Temporal frame time as a proxy for whether Native is affordable; unusual workloads can therefore alternate between modes. The authoritative current audit and measurement plan are in [docs/TEMPORAL_UPSCALING_DRS.md](docs/TEMPORAL_UPSCALING_DRS.md).

---

## 5. Frame Interpolation / Generation

Generates synthetic intermediate frames on the GPU:
- **Current Status**: experimental production profiles exist for **fixed Temporal + Frame Interpolation** and **Spatial + Frame Interpolation** on supported macOS 26+ devices. Native-resolution and Dynamic-Temporal FI remain disabled. Sodium persists the `frameInterpolation` request; effective admission additionally requires a validated native workspace, separated SDR UI and a real cadence that a 2x stream can display without dropping mandatory real frames.
- **Production path**: Java hands the generation-local coordinator world-only color, depth/motion and separate SDR UI before renderer-command commit. Swift copies them GPU-to-GPU into a preallocated three-slot private ring. The Extended ProMotion scheduler then owns ordered generated→real presentation, uses runtime `NSScreen` VRR timing, an absolute Mach midpoint and `MTLDrawable.presentedTime` feedback. Generated frames are disposable; missing inputs, oversubscribed/fixed cadence, a stale display generation, drawable/encoder failure, resize or drain failure preserve real-only presentation. There is no CPU readback or synchronous GPU wait in the frame loop.
- **FI Off / ProMotion**: the same scheduler selects a sustainable real-only cadence for fullscreen Adaptive-Sync without changing the rendered image. Fixed/windowed displays retain plain display-synced presentation; VSync Off remains unmanaged.
- **Automated proof**: `frameInterpolationValidation` covers lifecycle, ticket ordering, Temporal/Spatial profiles, shared SDR UI, exact-extent/fail-open bridge, real-only adaptive pacing, FI 60→120 and 40→80, fixed/windowed rejection, VSync-off and display-plan invalidation under Metal validation. `clean check` runs the broader Java/Swift/ABI regression suite.
- **Remaining acceptance boundary**: the feature stays Experimental until the documented live visual/HUD/long-session matrix proves world/UI quality, actual cadence, latency and resource retirement. Native + FI, Dynamic Temporal + FI and live entity-buffer motion are later profiles. See [Extended ProMotion Frame Scheduler](docs/promotion-frame-scheduler.md).

---

## 6. Required Architectural Modifications

Further per-object temporal-quality work must modify:
1. **Draw interception**: Wire the real entity and animated-geometry Metal buffers to `EntityVelocityDrawRecorder.recordDraw(...)`; never fabricate native buffer pointers.
2. **Shaders**: Preserve the current motion/reactive convention (`previousNdc - currentNdc`, render pixels, Y down) when adding per-vertex velocity.
3. **Validation**: Extend the native runtime harness with live-buffer coverage before enabling entity replay.
