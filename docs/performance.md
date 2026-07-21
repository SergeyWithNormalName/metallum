# Performance Analysis and Profiling

This document outlines the performance characteristics of the **Metallum** Metal graphics backend, identifying common CPU and GPU bottlenecks, profiling results, and documented optimization outcomes.

---

## 1. CPU Bottlenecks

1. **Project Panama Call Overhead**: Although Project Panama is significantly faster than traditional JNI, calling down into native code from high-frequency Java loops (e.g. per-vertex or per-draw-call) still carries JVM transition costs. To mitigate this, Metallum bundles draw calls and resource bindings into unified off-heap packets (e.g. `FrameStatePacketRing`, `VoxelUploadBatch`) rather than making individual downcalls.
2. **Render-Thread Blocking**: If the GPU is heavily loaded, the render thread will block at `metallum_semaphore_wait` waiting for the GPU to release older frame resources. While this prevents frame piling and input lag, it shows up as a CPU bottleneck that is actually caused by GPU execution delays.
3. **Chunk Meshing Sync**: Consuming the `VoxelDirtyQueue` and chunk updates on the render thread can cause micro-stuttering if a large number of chunk updates arrive simultaneously (e.g. during rapid chunk generation or teleportation).

---

## 2. GPU Bottlenecks

As a macOS Metal renderer targeting Apple Silicon GPUs (M1/M2/M3 families), Metallum operates on a **Tile-Based Deferred Rendering (TBDR)** architecture. TBDR performance is governed by specific rules:

1. **Memory Bandwidth & Load/Store Actions**:
   - TBDR GPUs render geometry into fast local on-chip tile memory before flushing the final result to system RAM.
   - Using `MTLLoadActionLoad` forces the GPU to read textures back from system RAM to tile memory, which consumes memory bandwidth. Metallum configures load actions to `MTLLoadActionClear` or `MTLLoadActionDontCare` where possible.
   - For G-buffers that are only used within a single render pass, storing them back to system RAM is wasteful.
2. **Shader Complexity (MSL)**:
   - Point/spot light culling in `MetallumClusterBuild` and local shadow raymarching in `MetallumDynamicVoxelShadow` run heavy math operations per pixel.
   - High-cascade Cascaded Shadow Maps (CSM) require multiple rendering passes of chunk geometry, which strains the GPU's rasterization hardware.

---

## 3. Costly Shaders

1. **`MetallumClusterBuild.metal`**: Runs bounding box calculations for frustum clusters. If cluster counts are set too high, this compute pass slows down the frame setup phase.
2. **`MetallumDynamicVoxelShadow.metal`**: Traverses the voxel occupancy clipmap voxel-by-voxel using a 3D DDA algorithm. Raymarching is extremely bandwidth-intensive because it reads from multiple occupancy and optical density buffers per step.

---

## 4. Documented Optimization Outcomes (from OptimizationHistory.md)

### 4.1. Already Optimized / Successful Features
- **HDR/UI Seed Fusion**: Fuses the tonemapping/compositing of the world scene with the initialization of the SDR UI target within a single render pass, saving memory bandwidth on TBDR.
- **Precompiled metallib**: Compiles shaders into a binary `.metallib` package on startup rather than compiling from raw source strings, eliminating mid-game compiler stutter.

### 4.2. Tested and Rejected Approaches (Why they failed)
- **DontCare Load/Store for R8 Shadow Color Attachment**: Removing load/store actions for a dummy shadow attachment did not yield measurable A/B improvements because the GPU's bandwidth was already dominated by depth buffer updates.
- **Sorting Sodium Regions Once Per Shadow Frame**: Attempting to sort mesh regions once per frame instead of per-cascade caused visual popping and did not improve performance.
- **Removing explicit Clamp from PCF Shadow Samples**: Stripping clamp checks from the 9-sample PCF filter did not improve rendering speeds and risked reading out-of-bounds shadow map texels.
- **Global MTLFence Deletion**: Attempting to remove the global fence caused write-after-read hazards on the GPU and resulted in visual glitches.
