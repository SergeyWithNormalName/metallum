# Native Metal renderer

`MetallumNative.swift` owns Metal objects behind opaque handles exposed through
`MetalNativeBridge`. Java is responsible for validating input and scheduling
release; Swift must reject an invalid handle cleanly rather than force-casting
it.

## Submission and lifetime

The renderer limits work to three in-flight submissions. `MetalCommandEncoder`
uses completion signaling to reuse a slot, and `MetalDestructionQueue` defers
release callbacks until a resource cannot be referenced by the GPU.

Use bounded upload rings and `DynamicBackingPool` for transient data. Do not
allocate a buffer, texture, pipeline or direct arena per draw or per frame.

## Resources and output

- The generated native artifact is
  `build/generated/metallum/natives/macos/libmetallum.dylib`; resources do not
  load from `src/main/resources` at runtime.
- World color, depth and display-sized UI are separate contracts. HDR may use
  an extended linear `RGBA16Float` layer, while UI stays SDR until composition.
- Private GPU textures and GPU blits are used for temporal packing and history;
  CPU readback is forbidden on the presentation path.

## Presentation

Normal presentation is render-thread owned. The Extended ProMotion scheduler
uses the actual display contract and `MTLDrawable.presentedTime`; Frame
Interpolation may discard a late generated frame but always retains the real
frame as the fail-open member. Details and the still-required live acceptance
evidence are in [promotion-frame-scheduler.md](promotion-frame-scheduler.md).

Pipeline compilation or native initialization failures are presently tracked as
technical debt in [TECH_DEBT.md](../TECH_DEBT.md); do not mislabel a startup
failure as a rendering-performance result.
