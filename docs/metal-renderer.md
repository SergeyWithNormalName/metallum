# Metal Renderer Backend

This document details the architecture and implementation of the macOS native Apple Metal graphics backend in **Metallum**.

---

## 1. Device and Queue Management
The entry point of the Metal backend is the creation of the system default graphics device via [MTLCreateSystemDefaultDevice](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9368-L9373) and the setup of the primary queue via `MTLCommandQueue` in [metallum_MTLDevice_makeCommandQueue](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9675-L9690).

### Residency Sets (macOS 15+)
On macOS 15+ (internally marked as `macOS 26.0` or newer API in the Swift bridge), Metallum adds the EDR window's layer residency set to the queue:
```swift
if #available(macOS 26.0, *),
   let residencySet = layer.residencySet as MTLResidencySet? {
    queue.addResidencySet(residencySet)
}
```
This forces all textures and buffers bound to the window's layer to remain resident in GPU memory while the window is active, minimizing memory thrashing and OS paging overhead.

---

## 2. Command Buffers and Command Encoders
Metallum uses a standard triple-buffered execution loop. For each frame, it creates a command buffer via `MTLCommandQueue.makeCommandBuffer()` linked dynamically inside [metallum_MTLCommandQueue_makeCommandBuffer](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9692-L9707).

To compile and execute commands on the GPU, Metallum leverages three kinds of encoders:
1. **MTLRenderCommandEncoder** ([metallum_MTLCommandBuffer_makeRenderCommandEncoder](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9753)): Used for rasterization passes such as world geometry rendering, UI rendering, highlight extraction, and final compositing.
2. **MTLComputeCommandEncoder** ([metallum_MTLCommandBuffer_makeComputeCommandEncoder](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9772)): Used for compute passes such as building cluster bounds, building voxels, performing histogram luminance reduction, and running downscaling/bloom filters.
3. **MTLBlitCommandEncoder** ([metallum_MTLCommandBuffer_makeBlitCommandEncoder](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9792)): Used to copy buffers, generate mipmaps, and clear textures when fixed-function passes are not optimal.

---

## 3. Pipeline States (PSOs) Caching
To prevent mid-game compilation stutter (pipeline compilation on the render thread is a major cause of frame drops), Metallum caches all compiled PSOs:
1. **Dynamic Caching**: In [MetallumNative.swift](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9-L26), the Swift backend defines hash keys such as `PipelineVariantKey` and `PresentPipelineKey`.
2. **Precompilation on Startup**: A default set of shaders is compiled into a precompiled `.metallib` (found at `/natives/macos/metallum.metallib`). The Swift function `metallum_init_pipelines` ([MetallumNative.swift:L9203](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9203)) reads this library and creates standard PSOs for:
   - Presentation (tone mapping, EDR scaling, SDR GUI compositing).
   - HDR extraction and bloom.
   - Cluster build and light culling.
   - Voxel clipmap culling and ray-tracing.

---

## 4. Textures and Buffers

### 4.1. Buffer Allocation and Storage Modes
Metallum handles buffers by choosing storage modes strategically:
1. **CPU-to-GPU Staging**: Uses `MTLStorageModeShared` or `MTLStorageModeManaged`. Java writes to direct memory segments, and the bridge copies or maps them via a Dynamic Backing Pool [DynamicBackingPool.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/DynamicBackingPool.java).
2. **GPU Private Data**: Geometry meshes and cached shadow/voxel textures are stored in `MTLStorageModePrivate` to maximize bandwidth on Apple Silicon's unified memory system, bypassing CPU cache snooping overhead.

### 4.2. Bindless Texture Handles
Textures are bound explicitly via arguments or referenced bindlessly on compatible Apple Silicon GPUs. When bindless rendering is active, textures are made resident explicitly inside [LocalVoxelShadowGpuResources](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/LocalVoxelShadowGpuResources.java).

---

## 5. Render Targets and Depth Buffers

Metallum isolates world rendering from UI rendering to enable clean HDR output:
- **World Color Target (`MAIN_COLOR`)**: Configured as an `rgba16Float` target. This maintains linear color precision up to EDR highlight levels (values $> 1.0$) before tone mapping.
- **SDR UI Target (`SDR_UI_COLOR`)**: Configured as an `rgba8Unorm` target. Minecraft's standard GUI, menus, and text are drawn here and preserved strictly in the $[0, 1]$ range.
- **Final Output Target**: The final CAMetalLayer drawable is configured as `rgba16Float` in extended linear sRGB mode when HDR is active.

### Depth Buffers
The primary depth target uses `depth32Float`. During `scene_depth_snapshot` ([NativeHdrFrameGraph.java:L120](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/framegraph/NativeHdrFrameGraph.java#L120)), depth is snapshotted to a separate texture. The bloom pass uses this depth snapshot to reject blurred emission pixels that lie behind solid blocks, preventing light from leaking through walls.

---

## 6. CPU/GPU Synchronization

To prevent the CPU from running too far ahead of the GPU (which causes input lag and spikes in memory consumption), Metallum implements a **DispatchSemaphore** throttling mechanism:

1. **Semaphore Allocation**: In Java, a semaphore is created on startup via `MetalNativeBridge.metallum_create_semaphore()`.
2. **Commit with Signal**: In Swift, when the render thread submits the final command buffer of a frame, it attaches a completed handler that signals the semaphore, and commits the buffer:
   ```swift
   commandBuffer.addCompletedHandler { completed in
       semaphore?.signal()
   }
   ```
3. **Throttling Wait**: Before starting a new frame, the Java render thread waits on the semaphore using `metallum_semaphore_wait`. If the GPU has more than 2 frames in-flight, the CPU blocks until the oldest frame completes.
4. **Detailed Telemetry**: The time spent waiting for the GPU semaphore is tracked in CPU wait telemetry ([MetallumNative.swift:L9727](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L9727)) and output to timing JSON reports.
