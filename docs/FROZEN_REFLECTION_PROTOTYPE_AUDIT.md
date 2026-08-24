# Frozen world-space reflection prototype audit

## Scope and safety boundary

This prototype is an opt-in, **frozen** world-space reflection field for the
translucent water receiver.  It is not a dynamic cubemap, a screen-space
reflection system, or a replacement for L3/L5/L6 lighting.  It has no scrolling
or per-frame capture path.

The runtime switch requires both `METALLUM_VERTEX_REFLECTION_EXPERIMENT=1` and
`-Dmetallum.vertex.reflection.runtime=true`.  Normal benchmark runs force the
environment switch off.  This keeps the prototype quarantined until visual and
benchmark acceptance are recorded.

## Forensic findings

The abandoned experiment contained two invalid native ownership paths:

1. It wrote a CPU `replaceRegion` into a private Metal texture.  The recorded
   AGX crash occurred in that upload path.
2. It passed a Metal object through a generic Swift `setBuffer` bridge.  The
   recorded `swift_unknownObjectRetain` crash showed that the object ABI was not
   safe at that boundary.

Both paths were removed.  Java now retains only an opaque native context, and
Swift owns all Metal textures, sampler, parameter buffer, staging buffers, and
compute pipelines.  The dedicated native binding function sets only vertex
texture slots 10/11 and vertex buffer slot 27; no `MTLBuffer` object crosses the
generic object bridge.

## Frozen field contract

The field is a fixed 128-block cube around the first accepted camera origin.
Its 64³ source lattice corresponds to 2-block cells and requires an authoritative
outcome for all 8³ chunk sections before it can upload:

- published content is copied from the Sodium worker result only after that
  result is accepted;
- known-empty sections are represented as valid black source cells;
- unavailable, discarded, or failed work invalidates the collection rather than
  inventing black radiance;
- subsequent camera positions do not scroll or recenter it.

The native build runs once.  It copies the frozen source snapshot through
correctly row-aligned shared staging buffers into private source textures, then
builds a 32³ directional probe field and its mip chain in a separate command
buffer.  There is no per-frame CPU texture readback or resource creation.

## Shader contract

The feature is present only in the translucent Advanced pipeline.  The vertex
shader performs exactly two 3D lookups (radiance and validity) and a single
Cartesian directional moment dot product.  The fragment receives the resulting
varying and makes no 3D lookup.  Only marked water surfaces consume it:

- contribution mode adds the small reflected term;
- replacement mode replaces only the water diffuse component;
- non-water translucent materials are untouched.

The field deliberately reports zero where it has no valid support, so it cannot
be mistaken for an irradiance cache or provide fabricated far-field detail.

## Admission and verification status

`BenchmarkLightingAdmission` now rejects Advanced benchmark claims if config
defaults were used, requested/resolved lighting disagrees, fallback occurred, or
any observed frame lacks the L3/L5/L6 health contract.  The benchmark script
uses this strict schema and disables the reflection experiment by default.

The following automated checks passed after the native repair:

```text
./gradlew compileJava frozenReflectionFieldUnitTest \
  realWorldVertexReflectionUnitTest benchmarkLightingAdmissionUnitTest \
  frozenReflectionNativeValidation advancedDirectLightingShaderUnitTest \
  rendererArchitectureUnitTest --console=plain
./gradlew compileRadianceClipmapMetal buildMacNative compileTestJava
./gradlew forcedSourceStartupValidation
bash -n scripts/run_metal_benchmark.sh
```

`frozenReflectionNativeValidation` performs a source-fallback Java/FFM/Swift/
Metal run, waits for the one-shot build, and binds the resources through a real
render encoder.  It validates the repaired native boundary, not the visual
appearance.

Still required before enabling the experiment by default: an in-game water
appearance A/B at the fixed origin, a long Advanced benchmark with valid L3/L5/
L6 admission, and GPU-capture evidence that the active translucent PSO contains
the vertex resources and never falls back.
