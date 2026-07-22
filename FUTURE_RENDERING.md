# Future Rendering Technologies Readiness

This document analyzes the readiness of the **Metallum** rendering engine for future graphic technologies, highlighting what is already present in the codebase, what is missing, and which systems must be modified to support them.

---

## 1. Temporal Upscaling

Apple Silicon GPUs support two upscalers: **Spatial** and **Temporal**.
- **Current Status**: Spatial MetalFX and **Temporal Upscaling** live in the same Sodium `MetalFX` group. `MetalFxTemporalScaling` persists three Temporal presets: **Quality** (3/4 linear resolution, 15 jitter phases), **Performance** (1/2), and **Ultra Performance** (1/3). Selecting either scaler clears the other setting, so exactly one owns a frame.
- **Production path**: Quality uses a persistent Apple `MTLFXTemporalScaler` and a private full-resolution output per renderer generation. The same MetalFX path serves Performance and Ultra Performance unless the device is Apple M1 and the HDR input is `RGBA16Float`; that M1 HDR combination uses Metallum's optimized bounded temporal resolver instead. Both routes consume low-resolution color/depth-derived motion plus triple-buffered `RG16Float` motion and `R8Unorm` reactive inputs, and seed the full-resolution UI/present route.
- **Safety contract**: Format, usage, extent, device and generation checks fail closed to native resolution. The MetalFX scaler is initialized synchronously only on a generation change; neither route allocates a texture or scaler in the frame loop.
- **Automated proof**: Metal API/GPU validation encodes the MetalFX descriptor for all three scales. The native runtime harness validates the motion/reactive → Temporal → HDR-precompose/UI-backdrop route, including camera and depth-disocclusion cases.

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
- **Current Status**: Temporal selection enables a deterministic sub-pixel Halton projection jitter. Apple MetalFX owns history resolve, filtering and internal clamping for Quality and non-M1-FP16 configurations; the Apple-M1 FP16 HDR Performance/Ultra route uses Metallum's bounded history resolver. Metallum supplies one-shot reset reasons for first frame, resize, teleport, world/dimension change and generation changes to both routes.
- **Future quality work**: Feed live per-pixel velocity for entities and animated terrain into the existing reactive/motion attachments. This is an input-quality improvement, not a missing Temporal scaler or history implementation.

---

## 4. Dynamic Resolution Scaling (DRS)

Dynamic Resolution alters the rendering target resolution based on GPU workload:
- **Current Status**: Sizing descriptors (e.g. `render_extent` vs `display_extent` in [NativeHdrFrameGraph.java:L114-L131](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/framegraph/NativeHdrFrameGraph.java#L114-L131)) are already separated, which is a major prerequisite.
- **What is Missing**:
  - A dynamic workload controller that monitors GPU timing reports (`MetalGpuTiming`).
  - If GPU frame time exceeds 8.3ms (for 120Hz) or 16.6ms (for 60Hz), it must scale the `render_extent` dynamically, forcing the viewport and culling grids to adapt mid-frame.

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
