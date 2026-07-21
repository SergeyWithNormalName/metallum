# Future Rendering Technologies Readiness

This document analyzes the readiness of the **Metallum** rendering engine for future graphic technologies, highlighting what is already present in the codebase, what is missing, and which systems must be modified to support them.

---

## 1. MetalFX Temporal Upscaler

Apple Silicon GPUs support two upscalers: **Spatial** and **Temporal**.
- **Current Status**: Metallum already supports the **Spatial** scaler (`MTLFXSpatialScaler`) via the class [MetalFxSpatialScaling.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metalfx/MetalFxSpatialScaling.java).
- **Temporal Readiness**: The temporal scaler requires three high-resolution input textures:
  1. The low-resolution color target (input).
  2. The low-resolution depth target.
  3. The motion vector (velocity) target.
- **Current diagnostic foundation**: An opt-in T1A diagnostic path now writes camera/static-depth motion and a reactive mask, with reset and out-of-frame invalidation. It is not wired to the production temporal scaler.
- **What is Missing**: The temporal upscaler context is not instantiated in Swift, dynamic geometry is not replayed from live Metal draw buffers, and no temporal history resolve exists.

---

## 2. Motion Vectors (Velocity Buffer)

Motion vectors represent the screen-space velocity of each pixel from the previous frame to the current frame:
- **Current Status**: The opt-in diagnostic motion buffer reconstructs camera/static-depth motion. It remains marked as `"never a production motion declaration"` and is not a MetalFX Temporal input.
- **What is Missing**:
  - **Camera Motion**: Implemented for the diagnostic path only; it still needs production-contract validation before a scaler consumes it.
  - **Entity Motion**: Transform tracking, packet contracts, and shader math exist, but there is no live Metal draw-buffer interception. Entity replay must remain disabled until real handles and a runtime ABI test are available.
  - **Block/Terrain animations**: Flowing liquids, wind-blown foliage, and block breaking must write displacement values to the velocity texture.

---

## 3. Temporal Rendering (TAA / History Blending)

Temporal Anti-Aliasing (TAA) blends the current frame with the historical accumulated frames to reduce alias-shimmering:
- **Current Status**: A sub-pixel Halton jitter sequence is active only with temporal diagnostics. The normal production path remains unjittered:
  ```java
  // JitterSequence.java: L3
  /** Deterministic Halton jitter contract; L1 production always requests zero amplitude. */
  ```
- **What is Missing**:
  - The projection matrix must be jittered by sub-pixel offsets on a Halton cycle, and the final presentation pass must resolve and filter this jitter using history.
  - A feedback history loop buffer must be added to the Frame Graph to accumulate linear color values across frames.
  - A clamping/clipping pass must be added to prevent ghosting behind moving objects.

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

If other developers or agents decide to implement these features, they must modify:
1. **Shaders**: Update MSL vertex and fragment shaders to accept previous-frame projection matrices and output an extra color attachment (`float2 velocity`).
2. **Frame Graph**: Add history buffer nodes to `NativeHdrFrameGraph` and change lifetime assertions to span across frames.
3. **Java State**: Activate Halton jitter in `JitterSequence` and pass the jitter offsets into the projection matrices.
