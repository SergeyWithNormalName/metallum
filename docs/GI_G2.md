# GI Stage G2: accepted semantic material/emission truth

Status: `PASS_STRUCTURALLY_OFF_DIAGNOSTIC`. Commit `d750491` implements the
complete G2 semantic field and keeps it absent from ordinary production
execution. G2 is enabled only with `METALLUM_GI_G2_CAPTURE=1` on the exact
Minecraft 26.2 / Sodium 0.9.1 / Fabric Renderer 14.0.1 / MixinExtras 0.5.4
source contract.

G2 does not render GI. It provides deterministic, versioned material and
emission truth for a later source/transport stage. No G3 source injection,
transport, bounce, receiver binding, frame-graph pass, or production GPU field
context is present.

## Authoritative source and publication

The source is the immutable Sodium build attempt that produced accepted
geometry:

1. `RenderSectionManager.createRebuildTask` stamps world, resource, material,
   clipmap, content revision, section and ownership generations.
2. Before the worker starts, a fixed-byte candidate lease is acquired.
3. `LevelSlice` supplies cloned occupancy/state facts. It is never read after
   the candidate is published.
4. The exact block `bufferQuad` and fluid `writeQuad` paths copy accepted quad
   values. Sprite pixels are reduced on the render thread to immutable linear
   RGB; candidates retain no sprite or `NativeImage`.
5. A candidate is attached to the exact `ChunkBuildOutput`. Cancellation,
   failed capture, fast relight, output destruction and stale generations
   retire it without changing Sodium geometry.
6. Truth becomes resident only after `RenderRegionManager.uploadResults`
   returns. Block events only advance the revision; they do not publish mutable
   live-world data.

The source chain never reads vanilla/Sodium brightness arrays, packed light,
the camera-dependent lightmap, L3/L4 lighting, or an existing pre-lit radiance
estimate. Ordinary state emission remains valid even when a quad lacks an
emissive flag. Partial emissive overlays are paired with their base surface
without duplicating albedo or losing an unpaired accepted face.

## Semantic and lifetime contract

- Topology remains the fixed G1 `3 x 32^3` field at `2/4/8` blocks per cell,
  spans `64/128/256` blocks, with six mip levels.
- The global palette is sorted and immutable per resource/material epoch. IDs
  `0/1/2` mean UNKNOWN/AIR/FALLBACK; capacity is 65,536.
- Each accepted section reduces to 584 cells and a fixed 15,768-byte candidate.
  At most 64 candidates and 4 MiB may be in flight.
- The field stores no section snapshots after publication. It retains at most
  4,913 ownership tags and the fixed three-cascade arrays.
- Cell-aligned camera scrolling preserves exact overlap and invalidates only
  exposed slabs. World/resource/material/clipmap/content mismatches fail stale.
- An accepted quad determines albedo, medium, face distribution, provenance
  and dominant material. The cloned seed is used only where no quad was emitted.
- UNKNOWN and FALLBACK are conservative black/opaque with zero emission and
  zero face weights. Empty remains distinct and authoritative.
- Emission uses non-premultiplied RGB plus intensity. Recursive mips use
  conditional means and separate known coverage, avoiding `coverage^2`.

The synthetic retirement test performs 10,000 accepted publications of the
same section and ends with one resident tag, zero active candidate leases and a
peak of one lease.

## Native diagnostic field

The isolated Metal context owns private 3D textures in these formats:

| Plane | Format |
| --- | --- |
| Diffuse albedo + occupancy | `RGBA16Unorm` |
| Emission RGB + intensity | `RGBA16Float` |
| Signed face weights 0..3 | `RGBA8Unorm` |
| Signed face weights 4..5 | `RG8Unorm` |
| Medium, validity, provenance | `RGBA8Uint` |
| Dominant palette ID | `R16Uint` |
| Known coverage | `R8Unorm` |

The Java wrapper exposes upload, reset, statistics and a one-shot raw Z-slice
capture of all seven planes. It exposes no texture handle or production bind
method. Source-compiled and bundled-metallib validations both cover exact ABI,
formats, private allocation, five mip dispatches per cascade, partial coverage,
mixed palettes, fallback zero energy, terminal `1^3`, stale generations,
wrong-thread access and release while upload is in flight.

Measured M1 Pro memory:

| Item | Bytes |
| --- | ---: |
| Private textures | `4,128,768` |
| Peak upload staging | `5,505,024` |
| Peak capture readback | `32,768` |
| Native peak | `9,666,560` |
| Conservative end-to-end peak | `18,022,528` |
| Diffuse GI budget | `25,165,824` |

## Runtime screening

The same dirty source and built artifact were run in a provisional 600 warmup +
600 measured-frame pair at native 3024x1964 HDR, Advanced/Balanced, MetalFX and
VSync off:

| Mode | FPS | 1% low | GPU p95 | CPU submit p95 |
| --- | ---: | ---: | ---: | ---: |
| G2 structural-off | `27.331` | `21.650` | `40.734 ms` | `5.331 ms` |
| G2 accepted capture on | `27.434` | `23.249` | `39.595 ms` | `4.749 ms` |

The measured deltas are `+0.37%` FPS, `+7.39%` 1% low and `-1.139 ms` GPU p95.
Both runs were nominal with zero timing drops and identical source/artifact
digests. The enabled run accepted 768 snapshots with zero stale, outside or
capacity rejects; peak in flight was 13 candidates / 204,984 bytes and shutdown
reported zero active candidates. GI production resources, passes and bindings
remained zero.

This is a Tier B provisional screening, not an attested Tier C throughput or
release claim. It is sufficient only for the field-only G2 technical gate: the
enabled CPU source chain shows no measured regression. G2 has no receiver-visible
output, so there is no visual GI acceptance claim. The active G1 absolute-floor
gate remains independent and is not overridden by this historical diagnostic
screening or by the optional `1–2 FPS` tolerance recorded in its source evidence.

## Verification

Run:

```bash
./gradlew giG2SemanticContractTest \
  giSemanticCpuUnitTest \
  giSemanticSourceChainUnitTest \
  giSemanticGpuEncoderUnitTest \
  giSemanticFieldGpuValidationSource \
  giSemanticFieldGpuValidationBundled
./gradlew check
```

The integration run completed 105 Gradle tasks. Canonical machine-readable
evidence is `benchmark/gi/g2-semantic-evidence-v1.json`.

## Stop gate

G2 passes its technical stop-gate: unavailable sections remain UNKNOWN; stale
world/resource/material/clipmap/content truth is rejected; no camera-dependent
lightmap enters the field; memory and retirement are bounded; debug slices and
live accepted publication are proven.

This does not start G3 or authorize any production receiver or transport path.
On the active branch, the pending G1 absolute-floor gate also blocks a G3
proposal until fresh Tier C baseline evidence or a new explicit user decision.
