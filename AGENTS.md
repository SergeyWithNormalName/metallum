# Developer & AI Agent Guide (AGENTS.md)

Welcome to **Metallum**! This document serves as your primary entry point for understanding, navigating, and safely developing this codebase.

---

## 1. Project Purpose
**Metallum** is an experimental Apple Metal rendering backend for Minecraft on macOS (optimized for Apple Silicon). Its primary objective is to replace the traditional OpenGL/Vulkan pipeline with a native Metal pipeline that integrates extended-range high-dynamic-range (EDR/HDR) output, clustered culling, and voxel-grid shadow tracing.

---

## 2. Key Technologies
1. **Java 22+ (Project Panama)**: Uses `java.lang.foreign` to bind directly to native macOS dylibs without standard JNI overhead.
2. **Swift & Objective-C**: Implements the native rendering logic, resource lifecycles, and window layering.
3. **Metal & Metal Shading Language (MSL)**: Performs GPU-accelerated rasterization, compute, and culling.

---

## 3. High-Level Architecture
- **Graphics Hook**: [PreferredGraphicsApiMixin](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/mixin/render/PreferredGraphicsApiMixin.java) overrides Mojang's PreferredGraphicsApi list, loading [MetalBackend](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/MetalBackend.java) to set up GLFW hints and create a Cocoa Metal Layer.
- **Java-to-Native Bridge**: [MetalNativeBridge](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java) loads `libmetallum.dylib` on startup, linking Swift functions via Project Panama downcalls.
- **Native Implementation**: [MetallumNative.swift](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift) manages the underlying command queues, pipelines, buffers, and textures.

---

## 4. Key Folders
- `/src/main/java/com/metallum`: Java backend source code.
  - `/hdr`: HDR encoding/decoding and highlight reconstruction controllers.
  - `/metal/render`: Metal graphics device and frame graph management.
  - `/metal/render/bridge`: Project Panama downcalls definitions.
  - `/metal/render/framegraph`: Frame graph validation structures.
  - `/lighting`: Point/spot light registries and scanners.
  - `/voxel`: Clipmap voxel builders and controllers.
- `/src/main/native`: Swift native code containing `MetallumNative.swift`.
- `/src/main/metal`: Metal Shading Language (`.metal`) source code.

---

## 5. Rules for Safe Code Modification

### 5.1. Strict ABI Synchronization
Java and Swift exchange complex packets using direct contiguous byte memory segment structs.
- If you modify any parameter fields in a Java struct (e.g. in [FrameStateAbi.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/renderer/temporal/FrameStateAbi.java)), you **MUST** modify the corresponding Swift struct definition (e.g. `MetallumRendererFrameStateSnapshot`) in [MetallumNative.swift](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift) to match byte offsets and types exactly.
- Failure to sync both sides will result in heap corruption, wrong rendering results, or JVM segmentation crashes.

### 5.2. Thread Confinement
- The GPU Device, Command Queue, and presentation pipelines are **confined to the Render Thread**.
- Never make downcalls to commit command buffers or allocate textures from background Sodium worker threads.

### 5.3. Safe Resource Disposal
- The GPU executes command buffers asynchronously. Never dispose of an `MTLBuffer` or `MTLTexture` handle immediately when Java discards it.
- You **MUST** register the deallocation callback in the [MetalDestructionQueue](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java#L48) to defer destruction until pending GPU command buffers finish executing.

---

## 6. Forbidden Actions
1. **No High-Frequency Allocations**: Do not allocate direct byte segments (`Arena.allocate`) or create `MTLBuffer` objects inside render-loop iterations. Use the `DynamicBackingPool` or thread-local reusable packet rings instead.
2. **No Unchecked Swift Casts**: Avoid adding unchecked pointer conversions (`as!`) in Swift. If raw pointers sent by Java fail validation, return a clean error code back to Java instead of crashing the process.
3. **No Direct GPU-to-CPU Sync Readbacks**: Never read texture/buffer pixels back to the CPU inside the frame loop. This stalls the pipeline and tank performance.

---

## 7. Links to Detailed Documentation
- [Documentation map](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/docs/README.md): Canonical documents and placement rules.
- [Architecture & Frame Graph](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/docs/architecture.md): Lifecycle of a frame and JNI-bridge.
- [Metal Renderer Backend](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/docs/metal-renderer.md): Command buffers, encoders, and caching.
- [Lighting System](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/docs/lighting.md): Clustered lighting and emitters extraction.
- [Shadow Systems](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/docs/shadows.md): Cascaded shadows and ray-traced voxel occupancy shadows.
- [Memory Management](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/docs/memory.md): Heap vs off-heap memory safety.
- [Performance & Profiling](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/docs/performance.md): Bottlenecks and optimization guidelines.
- [Benchmarking & Performance Methodology](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/docs/BENCHMARKING.md): For Metallum performance work, read docs/BENCHMARKING.md and use the `metallum-benchmarking` skill.
- [Technical Debt Registry](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/TECH_DEBT.md): Tracked issues, priority list, and locks.
- [Future Technologies Readiness](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/FUTURE_RENDERING.md): Temporal upscaling, dynamic resolution, and TAA.
