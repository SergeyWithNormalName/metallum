# Technical Debt Registry

This document records the identified technical debt, safety hazards, and concurrency limitations in the **Metallum** project, along with recommendations for resolving them.

---

## Priority Classification
- **P0**: Critical issue causing immediate crashes, severe security flaws, or data loss.
- **P1**: Serious impact on stability, correctness, or performance.
- **P2**: Standard technical debt or architectural design limitation.
- **P3**: Minor improvements, cleanups, or non-critical enhancements.

---

## 1. Force Unwrapping & Unsafe Casting in Native Swift Bindings
- **Location**: [MetallumNative.swift:L10652, L10660-L10661, L10671](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift#L10652)
- **Priority**: **P1**
- **Description**: The Swift bridge deserializes object pointers from raw Java addresses (`UInt64`) and uses force-unwrapping and force-casting:
  ```swift
  let primaryObject = bindingPacketObject(primaryAddress)!
  let buffer = primaryObject as! MTLBuffer
  ```
- **Why it is a problem**: If the Java side sends an invalid handle, a pointer to a deallocated object, or a mismatching handle type, the Swift runtime will panic or segmentation-fault.
- **Possible Impact**: Direct crash of the entire JVM process (`SIGSEGV` or `SIGABRT`) without throwing any catchable Java exception, preventing clean game error recovery.
- **Fixing Complexity**: Medium. Replace force casting (`as!`) with conditional casting (`as?`) and handle optional unwrapping safely by returning error codes (e.g. `MetallumResourceBindingAbi.errorInvalidObject`) to the Java caller.

---

## 2. Heavy Lock Contention on AdvancedLightRegistry
- **Location**: [AdvancedLightRegistry.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/lighting/AdvancedLightRegistry.java)
- **Priority**: **P2**
- **Description**: Almost all methods inside the light registry are globally synchronized:
  ```java
  public synchronized void noteStaticScan(final LightSectionCandidate candidate) { ... }
  public synchronized LightFrameSnapshot snapshotForFrame( ... ) { ... }
  ```
- **Why it is a problem**: Background chunk meshing worker threads call `noteStaticScan` and `publishAccepted` to notify the registry of lights, while the main render thread calls `snapshotForFrame` to prepare GPU uploads. This creates a synchronization bottleneck on a single lock object.
- **Possible Impact**: Micro-stutters and frame-time spikes on the render thread when multiple chunk workers finish meshing simultaneously.
- **Fixing Complexity**: High. Redesign the registry using fine-grained locks (such as `ReentrantReadWriteLock`) or lock-free concurrent collections (like `ConcurrentHashMap` and thread-safe queues).

---

## 3. Concurrency Lock Contention on VoxelClipmapController
- **Location**: [VoxelClipmapController.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/voxel/VoxelClipmapController.java)
- **Priority**: **P2**
- **Description**: Like the light registry, the voxel clipmap controller synchronizes nearly all public methods globally.
- **Why it is a problem**: Workers uploading voxel occupancy segments conflict with the render thread updating camera transformations and leasing upload batches.
- **Possible Impact**: Increased CPU frame overhead and thread contention under high chunk updates (e.g. when flying fast).
- **Fixing Complexity**: High. Transition to thread-confined state updates and atomic double-buffered references for active snapshots.

---

## 4. Native Shader Compilation Failures Trigger Hard Crash
- **Location**: [MetalDevice.java:L2500, L2516, L2534, L2553](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/MetalDevice.java#L2500)
- **Priority**: **P2**
- **Description**: If a shader pipeline fail-closed check detects compilation failure on startup, it immediately throws a fatal exception:
  ```java
  throw new IllegalStateException("Failed to compile " + k.flavor() + " shader " + k.id(), e);
  ```
- **Why it is a problem**: Throwing a fatal exception stops graphics initialization and crashes the game client immediately.
- **Possible Impact**: Mismatching macOS system dylibs or corrupted `.metallib` caches result in a game crash on startup instead of fallback.
- **Fixing Complexity**: Medium. Implement a graceful fallback that catches compilation exceptions and redirects the graphics backend to SDR mode or standard Vulkan/OpenGL.

---

## 5. Potential Use-After-Free in Panama Object Pointer Bridging
- **Location**: [MetalNativeBridge.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java)
- **Priority**: **P1**
- **Description**: Java handles raw native memory pointers as long values. Although the JVM keeps memory segments alive during a call, there is no automatic GC synchronization that prevents a native Swift object (wrapped in a Java reference wrapper) from being garbage collected on the Java side while the GPU is still executing commands containing that pointer.
- **Why it is a problem**: The JVM garbage collector does not understand Metal GPU execution queues and could deallocate a Java resource wrapper before the GPU completes its work.
- **Possible Impact**: Intermittent GPU crashes and driver hangs (`AGXMetalG13X` SIGSEGV crashes) under high memory pressure.
- **Fixing Complexity**: Medium. Ensure all Java resource wrappers explicitly register their handles in the deferred destruction queue (`MetalDestructionQueue`) rather than relying on standard finalize hooks or simple GC deallocations.

---

## 6. T1B Entity Motion Replay Is Not Yet Connected to Live Draw Packets
- **Location**: `EntityVelocityDrawRecorder`, `MetallumEntityMotion.metal`, `MetallumNative.swift`
- **Priority**: **P1**
- **Description**: T1B currently has transform history, packet layout, shader math, and native matrix tests, but no production hook supplies the actual Metal entity vertex/index buffers. The recorder therefore emits no in-game replay packets by design.
- **Risk**: Treating the implementation as ready would either render no dynamic entity motion or tempt a caller to pass invalid raw handles to the native bridge.
- **Required completion**: Capture only deferred-lifetime real `MTLBuffer` handles from the Metal draw path, validate packet bounds/device ownership and render pipeline state, then prove the path in a live moving/teleporting-entity scene before enabling it.
