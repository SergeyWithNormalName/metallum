# Frozen world-space reflection prototype audit

## Scope and safety boundary

This prototype is an opt-in, **snapshot-built** world-space reflection field for
the translucent water receiver. It is not planar reflection, a dynamic cubemap,
a screen-space reflection system, or a replacement for L3/L5/L6 lighting. It has
no per-frame capture/update path. The active field is immutable while READY, but
the controller can build and atomically publish a replacement after the camera
leaves its per-axis guard region. Calling it permanently frozen is therefore
inaccurate.

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
compute pipelines. The dedicated native binding function sets vertex
texture/sampler slot 10 and vertex buffer slot 27; no `MTLBuffer` object crosses
the generic object bridge.

## Frozen field contract

The field is a 128-block cube around an accepted camera origin. Its 64³ source
lattice corresponds to 2-block cells and requires an authoritative outcome for
all 8³ chunk sections before it can upload:

- published content is copied from the Sodium worker result only after that
  result is accepted;
- known-empty sections are represented as valid black source cells;
- unavailable, discarded, or failed work invalidates the collection rather than
  inventing black radiance;
- edits inside the READY domain do not live-update it;
- crossing a 32-block per-axis guard starts a complete replacement build while
  the previous READY field remains bound;
- X/Y/Z recenter independently, which prevents rebuild oscillation on stable
  axes.

Each native build copies the accepted source snapshot through correctly
row-aligned shared staging into native-owned private storage and prepares the
radiance mip chain in a separate command buffer. Completion publishes the new
field atomically. There is no per-frame CPU texture readback, reflection upload,
or reflection resource creation.

## Shader contract

The feature is present only in the translucent Advanced pipeline. Only marked
water surfaces consume it. The current topology is a bounded 40-step vertex
cone trace over one native-owned radiance texture. Generated MSL contains one
syntactic 3D sample instruction inside that loop, so it may execute up to 40
samples per affected water vertex. There is no Cartesian moment texture in the
current implementation.

The refined carrier exports two `float4` varyings:

- coarse world RGB plus confidence;
- the flat vertex trace direction plus roughness.

The fragment performs zero 3D reads. It reflects the per-fragment view vector
about the existing procedural water normal, evaluates bounded alignment against
the interpolated trace direction, and applies confidence, roughness and the same
water Fresnel used by the analytic environment. Coarse world RGB replaces the
analytic environment only by that bounded weight; it is not added as diffuse
illumination. The transmitted/body term and alpha receive the complementary
Fresnel energy. Sun and local-light GGX remain separate.

Diagnostic contribution-only output is opaque and contains only
`coarseRGB * confidence * directionalResponse`; it is not final water color.

Receiver isolation remains:

- the general environment/diffuse term never consumes coarse world RGB;
- the analytic environment remains the zero-confidence fallback;
- non-water translucent materials are untouched.

The field deliberately reports zero confidence where it has no valid support,
so it cannot be mistaken for an irradiance cache or provide fabricated
far-field detail.

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

The receiver refinement has generated-MSL, deterministic fixture, motion-route,
and Tier C timing receipts recorded in `OptimizationHistory.md`. Human visual
acceptance is still required before changing the default-OFF state. A Metal GPU
capture/counter-capable target would still be needed to turn the varying change
into a measured register/occupancy claim; source length and varying counts alone
do not prove occupancy.
