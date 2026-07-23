# Future Rendering Technologies Readiness

This document analyzes the readiness of the **Metallum** rendering engine for future graphic technologies, highlighting what is already present in the codebase, what is missing, and which systems must be modified to support them.

---

## 1. Temporal Upscaling

Apple Silicon GPUs support two upscalers: **Spatial** and **Temporal**.
- **Current Status**: Spatial MetalFX and **Temporal Upscaling** live in the same Sodium `MetalFX` group. `MetalFxTemporalScaling` persists three Temporal presets: **Quality** (3/4 linear resolution, 18 jitter phases), **Performance** (1/2), and **Ultra Performance** (1/3). Selecting either scaler clears the other setting, so exactly one owns a frame.
- **Production path**: Fixed Temporal presets use their render-sized inputs. Temporal Dynamic has no Spatial fallback: it is Native at 100% or MetalFX Temporal at the benchmark-validated 50% input. After 45 consecutive completed Native GPU samples above 16.5 ms (below 60 FPS), it enters Temporal. After 60 consecutive Temporal samples below 14.0 ms, it returns directly to Native. Spatial MetalFX is invoked only by its separately selected user mode. Dynamic Temporal uses the established Quality mip-bias sampler for a stable 50% input. While Temporal is active it uses MetalFX's `inputContent*` API: one display-sized scaler, color/depth inputs and output, with GPU blits packing only the active low-resolution world rectangle at the origin. The triple-buffered `RG16Float` motion and `R8Unorm` reactive ring is likewise display-sized but rasterized only inside that active rectangle.
- **Safety contract**: Format, usage, device, active-content and generation checks fail closed to native resolution. Dynamic depth staging and depth history are GPU-private and retained across DRS generations; a resolution change resets history but does not recreate the scaler, motion/reactive ring, staging depth or history texture.
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
- **Current Status**: `MetallumDrsController` consumes completed presented-GPU timing. Dynamic Temporal fixes the world extent at either 100% Native or 50% Temporal, with explicit 16.5 ms / 45-frame admission and 14.0 ms / 60-frame return thresholds. Spatial DRS resizes the world targets directly only when Spatial is selected. Temporal Dynamic keeps MetalFX's physical inputs at the display extent and updates only `inputContentWidth/Height`; low-resolution color and depth are copied GPU-to-GPU into the active origin rectangle, with no CPU readback or render-thread resource allocation on a scale transition.
- **Boundary**: Minecraft still physically resizes its world targets to reduce shaded pixels. The one-time cost of enabling Temporal Dynamic, changing display size or changing color format remains intentional; ordinary DRS scale changes reuse the Temporal resources and reset history safely.

---

## 5. Frame Interpolation / Generation

Generates synthetic intermediate frames on the GPU:
- **Current Status**: Metal currently does not expose a native frame interpolation API for macOS.
- **What is Missing**: To implement a custom compute-based frame generator (or integrate FSR 3 / DLSS 3 equivalents), the engine would require highly accurate depth buffers, optical flow buffers, and UI composition isolation. The UI is already isolated to `SDR_UI_COLOR`, but the optical flow culling path is absent.

---

## 6. Required Architectural Modifications

Further per-object temporal-quality work must modify:
1. **Draw interception**: Wire the real entity and animated-geometry Metal buffers to `EntityVelocityDrawRecorder.recordDraw(...)`; never fabricate native buffer pointers.
2. **Shaders**: Preserve the current motion/reactive convention (`previousNdc - currentNdc`, render pixels, Y down) when adding per-vertex velocity.
3. **Validation**: Extend the native runtime harness with live-buffer coverage before enabling entity replay.
