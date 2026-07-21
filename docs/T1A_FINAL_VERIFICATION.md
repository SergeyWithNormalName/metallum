# T1A/T1A.5 Final Verification Report

This document details the final verification results of the **T1A Foundation** motion/reactive vector generation path and the opt-in developer-only **T1A.5 Debug Visualization** pass in the **Metallum** rendering backend.

---

## 1. Executive Summary

A comprehensive verification of the T1A/T1A.5 graphics pipelines has been performed. All automated verification tests pass, and dry runs confirm stable operation. The implementation conforms to all correctness, visual excellence, and zero-cost optimization requirements.

---

## 2. Debug OFF Zero-Cost Optimization

When `METALLUM_DEBUG_VISUALIZATION` is **not** set in the environment, the debug visualization features are guaranteed to be zero-cost:

1. **0 Classification Bytes Allocated**:
   In [TemporalDiagnosticResources.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/TemporalDiagnosticResources.java#L38-L43), the third `R8_UNORM` texture attachment (`classification`) is allocated **only** if the environment variable is active. In production/default-off runs, the classification handle remains `null` (mapped to `MemorySegment.NULL`), resulting in **0 byte** allocation overhead.
2. **0 Compile Cost (Debug PSO)**:
   The debug post-pass pipeline state object (PSO) is loaded and compiled via `ensureDebugPostPassPipeline` inside [MetallumNative.swift](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L7008-L7025) strictly on-demand. When debug visualization is OFF, this function is never called, saving shader compilation CPU and GPU overhead on startup.
3. **0 Cache Footprint (Debug Pipeline)**:
   The `NativeState.debugVisualizationPipelines` cache dictionary remains empty, incurring zero memory overhead.
4. **0 Extra Render Passes**:
   In the main presentation resolve code ([MetallumNative.swift](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L12000-L12049)), the conditional `if NativeState.debugVisualizationMode != 0` ensures that no extra render pass or render command encoder is created.
5. **0 Extra Command Encoders**:
   No additional command encoding work is submitted to the GPU, avoiding any overhead on the GPU scheduling queue.

---

## 3. ABI Validation Contracts

### 3.1. Frame Graph validation (`metallum_validate_frame_graph_v1`)
Java constructs and serializes the diagnostic frame graph ([TemporalDiagnosticFrameGraph.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/framegraph/TemporalDiagnosticFrameGraph.java#L28-L44)) into a contiguous direct off-heap Panama memory segment. The layout details:
- **Header**: 32 bytes (verifying version, declared size, required capabilities, and counts).
- **Resource Records**: 24 bytes per resource (validating types, persistence class, and lifetime passes).
- **Pass Records**: 24 bytes per pass (validating encoder class, dependencies, and ordering).
- **Access Records**: 24 bytes per access (validating kind, pipeline stage, role, and load/store actions).

The Swift validator function `metallum_validate_frame_graph_v1` ([MetallumNative.swift](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9085-L9250)) checks:
- Strict ABI version matching (current version is `1`).
- Section bounds offsets and memory sizes.
- Graph acyclicity (dependencies must strictly target preceding passes).
- Access validation (stages must match encoder class, roles must match attachment types).

If validation fails, the native code returns stable diagnostics negative codes instead of panic-crashing the JVM, allowing clean error logging.

### 3.2. Resource Binding Validation (`applyResourceBindings_v1`)
Strict, high-frequency resource validation is performed in `metallum_MTLRenderCommandEncoder_applyResourceBindings_v1` ([MetallumNative.swift](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L10576-L10752)) before any encoder state changes:
- **Version and Layout**: Verifies capacity limits and structure matches.
- **Duplicate Binding Check**: Uses bitmask checks `occupiedBindingMask & bindingBit` to prevent duplicate binding slots in the same shader stage.
- **Pointer Safety**: Validates that raw addresses sent by Java map to valid, unmanaged Swift resources (`MTLBuffer`, `MTLTexture`, `MTLSamplerState`) using `bindingPacketObject`.
- **Bounds Checking**: For uniform buffers, it performs strict bounds checks:
  $$\text{offset} \le \text{nativeLength}$$
  $$\text{length} \le \text{nativeLength} - \text{offset}$$
  This prevents GPU out-of-bounds page faults and security vulnerabilities.

---

## 4. Capture Evidence (Overworld Static)

Three screenshots were successfully captured during a deterministic Overworld static route benchmark run to demonstrate the T1A/T1A.5 output validity:

### 4.1. Reprojection Validity Mode Screenshot
- **Environment**: `METALLUM_DEBUG_VISUALIZATION=3` (or `reprojection_validity`)
- **Visual Description**:
  - Sky regions are rendered in Dark Gray (`0.15` RGB).
  - Valid reprojected terrain block sections are rendered in solid Green.
  - Camera-cut/load frames render Orange Reset pixels.
  - UI text and crosshair remain clear and free of debug colors.
- **File Link**: [validity_mode.png](file:///Users/sergejgenerozov/.gemini/antigravity/brain/e76c0651-5402-4b8e-bd64-eb3e1a4f14cf/validity_mode.png)
- **SHA256**: `086eab05e09266ce79bd998b2d5af0b1ee74adf9e72046dc4f711e2a857abc11`

![Validity Mode](file:///Users/sergejgenerozov/.gemini/antigravity/brain/e76c0651-5402-4b8e-bd64-eb3e1a4f14cf/validity_mode.png)

---

### 4.2. Motion Direction Screenshot
- **Environment**: `METALLUM_DEBUG_VISUALIZATION=1` (or `motion_direction`)
- **Visual Description**:
  - Encodes 2D screen-space pixel movement direction into the Red and Green color channels.
  - Color intensity corresponds to motion magnitude.
  - Static areas appear pitch black.
- **File Link**: [motion_direction.png](file:///Users/sergejgenerozov/.gemini/antigravity/brain/e76c0651-5402-4b8e-bd64-eb3e1a4f14cf/motion_direction.png)
- **SHA256**: `369e9d704b5dac4bc46076e508f5cc6f310cbf3475a5036381f32f6e890d72c8`

![Motion Direction](file:///Users/sergejgenerozov/.gemini/antigravity/brain/e76c0651-5402-4b8e-bd64-eb3e1a4f14cf/motion_direction.png)

---

### 4.3. Motion Magnitude Screenshot
- **Environment**: `METALLUM_DEBUG_VISUALIZATION=2` (or `motion_magnitude`)
- **Visual Description**:
  - Heatmap segmenting motion vectors:
    - **Blue**: Micro-drift / subpixel jitter ($0 < \text{magnitude} \le 0.5$ px).
    - **Green**: Standard motion ($0.5 < \text{magnitude} \le 16.0$ px).
    - **Red**: Extreme motion or resets ($\text{magnitude} > 16.0$ px).
- **File Link**: [motion_magnitude.png](file:///Users/sergejgenerozov/.gemini/antigravity/brain/e76c0651-5402-4b8e-bd64-eb3e1a4f14cf/motion_magnitude.png)
- **SHA256**: `b89f7abdcac487fe9d98154165125436132b5f3c7d10656b7e08216aa34729f0`

![Motion Magnitude](file:///Users/sergejgenerozov/.gemini/antigravity/brain/e76c0651-5402-4b8e-bd64-eb3e1a4f14cf/motion_magnitude.png)

---

## 5. Reactive Mask Scope Limitations

The T1A reactive mask visualization (`METALLUM_DEBUG_VISUALIZATION=4`) matches the **T1A Foundation** architectural specifications and boundaries:
- **Camera-Induced Motion Only**: Since dynamic geometry tracking is deferred to the T1B phase, rigid entities, players, and first-person held items (hand) do not produce correct motion vectors and are reprojected using the main camera transform. They appear as static or invalidated regions.
- **Out-of-Frame Invalidation**: The reactive mask is set to `1.0` (white) only for off-screen boundaries, camera cuts, dimensions change, teleportation, or invalid depth. Inside-frame depth-mismatch disocclusion checks are deferred.
- No support is claimed for dynamic tracking or depth disocclusion within this scope.

---

## 6. Debug Pass Isolation

The debug post-pass is strictly isolated from production graphics features:

- **HDR Path**: The HDR scene luminance calculations, PQ/HLG encoding, bloom reductions, and precompose pipelines remain completely untouched. The debug pass runs only on the intermediate world texture `sourceTexture`.
- **Spatial**: MetalFX Spatial upscaling or downscaling is unaffected. The debug post-pass uses the scaled texture output and conforms to its resolution.
- **UI**: The UI overlay texture is rendered separately and blended *on top of* the world texture after the debug post-pass is completed, so the UI is free of debug tints or jitter.
- **Temporal Contracts**: The diagnostics output textures (`motion`, `reactive`, `classification`) are accessed as read-only inputs by the debug pass. It does not write to the temporal ring buffers or modify the per-frame history state.
