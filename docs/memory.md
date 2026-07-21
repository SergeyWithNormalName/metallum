# Memory Management

This document describes how memory is managed across the boundary between the JVM (Java) and the native code (Swift/Metal).

---

## 1. Java Heap vs. Off-Heap Memory

Because Metallum relies on high-frequency communication between Java and native Swift/Metal libraries, standard JNI object overhead is avoided by utilizing **Project Panama (Foreign Function & Memory API)**. Memory is divided into:

### 1.1. Java Heap
Ordinary Java objects (such as task slots, registry trackers, config files) are allocated on the Java heap and managed by the standard Garbage Collector (GC).

### 1.2. Off-Heap Memory (Direct Segments)
Data sent to the Swift native layer is allocated as raw direct byte segments using `java.lang.foreign.Arena`:
- **Short-Lived Arenas (`Arena.ofConfined()`)**: Bound to a single thread and a localized scope. Typically allocated inside `try-with-resources` blocks (e.g. [NativeHdrFrameGraph.java:L99](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/framegraph/NativeHdrFrameGraph.java#L99)):
  ```java
  try (Arena arena = Arena.ofConfined()) {
      MemorySegment packet = FrameGraphAbi.encode(graph, capabilities, arena);
      // Calls Swift and frees the segment immediately when exiting the block
  }
  ```
- **Long-Lived Arenas (`Arena.ofShared()`)**: Shared across multiple threads (e.g. for triple-buffered frame state rings [FrameStatePacketRing.java:L10](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/renderer/temporal/FrameStatePacketRing.java#L10) and resource buffers). These must be explicitly closed when the owning object is destroyed to prevent off-heap leaks.

---

## 2. Swift Memory Management
Swift uses **Automatic Reference Counting (ARC)**. However, since Swift functions are exposed as C-compatible entry points (`@_cdecl`), the JVM does not track references to Swift class instances.
- **Retained Pointers**: Swift objects managed in Java are stored as raw `UnsafeMutableRawPointer?` addresses. In Swift, these are retained manually on creation using helper wrappers:
  ```swift
  return retainedPointer(object) // Increments ARC reference count
  ```
- **Explicit Release**: When Java decides a resource is no longer needed, it calls a release function (e.g. `voxelReleaseContextV1`), which maps back to Swift:
  ```swift
  unretainedRelease(pointer) // Decrements ARC reference count
  ```
  Failure to call release from Java results in a permanent leak of the Swift object.

---

## 3. Metal Resource Lifetimes & Destruction Queue

Because the GPU operates asynchronously, resources (textures, vertex buffers, uniform buffers) cannot be destroyed immediately when Java discards them. If they are freed while a command buffer is executing on the GPU, the game will crash (`SIGSEGV` or GPU hang).

To prevent this, Metallum implements a deferred deletion mechanism:
1. **Queue Rotation**: In [MetalCommandEncoder.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java#L48), a `MetalDestructionQueue` is allocated with a capacity matching the maximum number of in-flight frames (`MAX_SUBMITS_IN_FLIGHT`).
2. **Deallocation Scheduling**: When a texture or buffer is freed, Java adds the release callback to the queue:
   ```java
   destroyQueue.add(() -> MetalNativeBridge.metallum_MTLTexture_release(handle));
   ```
3. **Frame Execution**: On every completed frame submission, [destroyQueue.rotate()](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java#L182) is called. The queue cycles and executes callbacks that were scheduled several frames ago, guaranteeing that the GPU has finished reading those resources before they are released.

---

## 4. Leak Hazards & Allocation Bottlenecks

1. **Retain Loops in Swift**: Swift classes marked `@unchecked Sendable` (like `MetallumLightingContext` and `MetallumVoxelContext`) hold strong references to Metal resources and timers. If any closure captures these context instances strongly, they will leak.
2. **Orphaned Panama Arenas**: Classes using `Arena.ofShared()` must implement `AutoCloseable` or explicit `close()` methods. If these objects are discarded by the JVM without closing their arenas, native virtual memory space will leak.
3. **High-Frequency Allocations**: Creating new `MTLBuffer` objects for transient data (like dynamic light uploads or particle meshes) on the render thread is expensive. Metallum mitigates this by pooling and reusing buffers via a dynamic backing pool ([DynamicBackingPool.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/DynamicBackingPool.java)), but custom allocations bypassing this pool pose performance risks.
