# Metallum architecture

## Entry and ownership

`PreferredGraphicsApiMixin` puts `MetalBackend` ahead of the normal graphics
backend. `MetalBackend` creates the `CAMetalLayer` for the GLFW/Cocoa window;
`MetalDevice` then owns renderer generations, feature admission and the Java
side of the frame lifecycle.

All Metal device, queue, drawable, pipeline, command-buffer and presentation
operations are render-thread confined. Sodium workers may build chunk data and
publish bounded work, but must not commit GPU work or create Metal resources.

## Java, Swift and Metal boundary

`MetalNativeBridge` loads the packaged `libmetallum.dylib` and calls the
`@_cdecl` Swift exports in `MetallumNative.swift` through Project Panama. ABI
packets are fixed-width `MemorySegment` data, not Java object graphs.

When a packet changes, update the Java layout, Swift reader/struct and MSL
consumer together. `FrameGraphAbi` and `FrameStateAbi` are versioned validation
boundaries; their tests are the minimum proof that an ABI edit is safe.

The bridge uses two explicit downcall policies: one requests
`Linker.Option.critical(false)` and the other uses the default linker policy.
Neither is a license to put blocking work, JVM callbacks or unvalidated pointers
in a hot path.

## Frame outline

1. Minecraft/Sodium renders the world at the active render extent.
2. The native graph performs the required HDR, lighting, voxel, temporal or
   scaling work for the immutable renderer generation.
3. World output is composed with a separate display-sized SDR UI target.
4. The render thread commits and presents. Resource retirement waits for the
   in-flight submission fence rather than Java reachability.

Spatial and Temporal MetalFX are mutually exclusive. Dynamic Temporal chooses
either Native 100% or Temporal 50%; it is not a continuous Spatial fallback.
See [TEMPORAL_UPSCALING_DRS.md](TEMPORAL_UPSCALING_DRS.md) for its exact policy.

## Invariants

- No GPU-to-CPU readback in the frame loop.
- No per-frame direct arena, `MTLBuffer` or pipeline allocation in a hot path.
- A renderer-generation transition is atomic between frames; an admitted
  feature may fail closed, but must not leave stale resources live.
- A performance result is valid only when telemetry proves the intended
  renderer path was active. See [BENCHMARKING.md](BENCHMARKING.md).
