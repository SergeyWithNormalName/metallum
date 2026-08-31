# GI Stage G5: frozen vertex receiver feasibility

Status: `PASSED_BY_EXPLICIT_USER_DECISION` on 2026-08-29. The user explicitly
accepted G5, including performance, and opened G6. Visual acceptance was not
performed and remains pending. No live dry A/B bundle is claimed by this
document; the decision is recorded as a decision, not reconstructed evidence.
The P1 carrier audit rejected the earlier packed light/color ownership scheme;
it is not implementation or benchmark evidence. The only current carrier
contract is the collision-free packed-position sideband defined below, and all
mechanical receipts must be regenerated against it before a live run is valid.

G5 is a separate, default-off consumer of the completed G4 field. It does not
change the G4 transport algorithm, topology, textures, evidence receipt or
field-only result. It adds no scrolling, live updates, dynamic sources, second
bounce, reflection path or product-release setting.

## Fixed scope and admission

The only first candidate is a Sodium terrain vertex receiver. The canonical
benchmark request is

```text
METALLUM_GI_G5_RECEIVER=1
```

The interactive runtime parser also accepts normalized `true`, `yes` and `on`,
matching the other diagnostic environments. Missing, false or unknown runtime
values are structurally off. The benchmark runner rejects a non-boolean request
and always writes exact `1` to the launched JVM. OFF means no G5 native context,
terrain resource binding, G5 shader flavor or G5-accounted allocation. The
persistent renderer `globalIllumination` option remains `off`; G5 is an explicit
diagnostic stage, not a G7 release mode.

Admission requires all of the following:

- requested and resolved Advanced/Balanced Metallum lighting, with no renderer
  fallback;
- accepted G2 material truth, a settled G3 source and a `READY` frozen G4 near
  cascade with matching world/origin/content/source/environment identity;
- render-thread ownership and the same Metal device/queue as the G4 producer;
- a generated terrain pipeline that passes the vertex/fragment MSL contract;
- successful native attachment before the first G5 terrain draw.

Missing, stale, wrong-thread, wrong-device or released input fails closed to the
existing terrain fallback. The base `METALLUM` terrain flavor has no G5
textures, params, varying or irradiance work. Its existing position unpack
masks naturally ignore the two unused high bits in each packed position word;
there is no light/color decode or restore shim. It must not expose a raw
`MTLTexture` handle to Java, wait for the GPU, read the field back or rebuild
G4.

G5 covers the Sodium `SOLID`, `CUTOUT` and `TRANSLUCENT` terrain pipeline
variants. Entity, particle, cloud, UI, reflected-terrain and vanilla non-Sodium
pipelines do not enter the candidate unless a later stage defines and measures
them separately. A G5 launch forces persisted planar terrain reflections off;
the runtime capture selector also suppresses a later live `FULL_PLANAR` toggle.
Cloud-only capture used by the separate voxel-reflection experiment remains
independent and performs no reflected terrain draw.

## Runtime arms

An enabled G5 launch selects one arm with

```text
METALLUM_GI_G5_RECEIVER_ARM=control|candidate|field
```

Omitting the arm selects `field` for an explicit interactive request. The strict
benchmark runner requires an explicit arm for every ABBA launch. Any unknown
value fails admission; it never silently becomes `field`.

All three arms compile the same G5 shader flavor, allocate the same native
params resources and bind the same four G4 textures plus params buffer:

- `control`: the uniform control bypasses the four vertex reads and preserves
  the exact existing fallback;
- `candidate`: executes the complete four-read vertex receiver against the
  create-time-cleared G4 textures. The four sampled values flow into the
  interpolator without an arm-dependent zero, so a shader optimizer cannot
  discard the reads; the zero field itself produces zero irradiance/confidence.
  This is the dry cost arm;
- `field`: evaluates the actual frozen G4 SH/confidence and is reserved for
  functional and visual validation after the dry stop-gate passes.

The arm is immutable for a launch. Changing it requires a fresh process so a
cached PSO, resource layout or in-flight draw cannot cross arms.

## Reused field and binding ABI

G5 reuses, without copying, the four shader-readable G4 outputs:

| Vertex binding | Shader name | G4 resource | Format |
|---:|---|---|---|
| texture `6` | `metallumGiShRed` | red L1 SH | `RGBA16Float` |
| texture `7` | `metallumGiShGreen` | green L1 SH | `RGBA16Float` |
| texture `8` | `metallumGiShBlue` | blue L1 SH | `RGBA16Float` |
| texture `9` | `metallumGiConfidence` | known-path confidence | `R8Unorm` |
| buffer `25` | `metallumGiReceiver` | immutable receiver params | versioned ABI |

The params buffer contains only the frozen near origin/cell mapping, arm,
readiness and ABI/version data required by the shader. G5 creates no new 3D
texture, mip chain, field copy, separately allocated geometry sidecar, terrain
mesh, capture buffer or frame-loop staging resource. The four carrier bits
occupy already-unused bits in the existing compact vertex and add zero bytes to
the resource census. A native G5 context strongly retains its G4
producer until all G5 draws retire; Java release uses the existing deferred
in-flight destruction discipline.

The vertex stage performs the only four field reads. It maps world coordinates
to the frozen `32^3`, two-block near cascade, samples the three SH textures and
confidence, reconstructs incoming irradiance for the world-space terrain
normal, and writes

```text
metallumGiIncomingIrradiance = float4(E_indirect, confidence)
```

for ordinary raster interpolation. An out-of-range coordinate, non-finite
value, non-unit/unsupported normal, unready field or incompatible identity
forces `confidence = 0` and zero G5 irradiance.

Generated fragment MSL may consume this interpolator, but it must contain no G5
3D texture, G5 sampler, DDA, cone trace, visible function or field-address
helper. Slots `6..9` are vertex-only and buffer `25` is the only G5 params
binding.

## Geometry sideband and version boundary

G5 accepts only an explicitly tagged, finite axis-aligned unit terrain normal;
the epsilon is `1e-4`. It does not turn a dominant diagonal normal into one of
the six G2 faces, so crossed plants and sloped/foreign geometry take the exact
fallback.

The carrier is version-locked to Minecraft 26.2, Sodium
`mc26.2-0.9.1-fabric` (`0.9.1+mc26.2`) and MixinExtras 0.5.4. G5 aborts
initialization before carrier admission when installed metadata differs. In that exact
`CompactChunkVertex` layout, `a_Position` is `RG32_UINT`: the first word is
`positionHi`, the second is `positionLo`, and each word uses bits `0..29` for
three 10-bit position components. Bits `30..31` of both words are unused by the
pinned encoder and by the existing position unpack. Those four bits are the
only carrier storage; light, color/alpha, texture and draw-data words remain
byte-exact.

The encoder forms one four-bit `code` and writes it only after the ordinary
Sodium position packing:

```text
precondition: ((baseHi | baseLo) & 0xc0000000) == 0
positionHi = (baseHi & 0x3fffffff) | ((code & 0x3) << 30)
positionLo = (baseLo & 0x3fffffff) | (((code >>> 2) & 0x3) << 30)
face               = code & 0b0111
exactG5            = (code & 0b1000) != 0
```

The low `face` field is `1..6` whenever a face is present. `code=0` means no
carrier; `code=1..6` carries only the L8 dominant-face approximation with
`exactG5=false`; and `code=9..14` carries the exact authored G5 axis face with
G5 ownership. Codes `7`, `8` and `15` are reserved and invalid. L8 may consume
the face bits only when its own material policy admits the receiver; G5 must
additionally see `exactG5=true`. Neither consumer may infer a face or ownership
bit from position, light or alpha values.

Writing the sideband must preserve `(word & 0x3fffffff)` exactly. Existing base
Metallum and `SUN_SHADOW` position decoders already select the three 10-bit
fields and therefore ignore bits `30..31` without a restore pass. Light-only
relight may update the light word without touching `a_Position`; G5 does not
disable that path or require a remesh solely to preserve ownership. Authored
light and alpha are never rewritten by this carrier.

When both G5 and the default-off L8 vertex receiver are disabled, the compact
encoder hook exits before face aggregation and performs no position-memory
preflight or sideband write. Strict G5 axis extraction is likewise skipped, so
the default renderer does not pay the diagnostic carrier cost.

The live receipt does not infer residency from the process-wide write counter.
On the pinned Sodium build, each persistent face builder keeps one primitive
`hasG5` bit. The finalized solid/cutout/translucent masks occupy otherwise
unused bits `8..28` of `BuiltSectionInfo.flags`; stock readers use only `& 7`
or truncate the value to one byte. After `uploadResults(...):RETURN`, the
accepted mask moves into bits `8..14` of the existing 48-byte resident
`SectionRenderDataUnsafe` record at slice-mask offset `16`; bit `7` is an owner
marker, while stock faces remain bits `0..6`. No array, mesh sidecar, GPU buffer
or additional per-output object is allocated. For modified translucent
geometry, the finalized `BSPWorkspace` scans its live non-null `FullTQuad`
slots by index and puts one primitive boolean on the already-existing
`UpdatedQuadsList`. The high-water destination buffer is not an ownership
source: Sodium may retain old bytes in a deleted middle slot that is absent
from the final index list. The live-slot census therefore excludes both that
null hole and a same-slot non-G5 replacement, while retaining split or reused
live G5 quads; an invalid or inconsistent live quad rejects the carrier
fail-closed.

`DefaultChunkRenderer.fillCommandBuffer` intersects those resident bits with
the exact current visible-face mask while it emits the real local/shared
commands. The resulting primitive count follows the same submit and render
invocation epoch as the private indirect snapshot. Generic/fallback indirect
draws carry zero and cannot qualify G5. The default-off branch returns before
metadata decoding or `bitCount` work.

Any Sodium version/layout mismatch, non-zero pre-owned high bit, invalid code,
inconsistent four-vertex face, or disagreement between the pre-compaction face
and encoded sideband is an ownership conflict. The encoder must not merge,
overwrite or guess through a conflict: it preserves ordinary geometry, refuses
the G5 contribution, increments the carrier failure census and makes benchmark
admission fail closed. The spare-bit preflight also runs for `code=0`, because
otherwise an untagged quad could expose a foreign `code=9..14` to the shader.
A Tier B launch must complete with both the early admission and final carrier
census reporting `carrier_skips=0`, a strictly positive diagnostic
`g5_carrier_writes=[1-9][0-9]*`, and a strictly positive exact draw proof
`drawn_g5_carrier_slices=[1-9][0-9]*`. Zero or a missing drawn-slice count means
that the run did not prove any accepted resident G5 carrier reached the pinned
terrain batch and is invalid evidence. `GI_G5_FINAL` and `COMPLETE` are emitted
from one immutable admission snapshot while the shared carrier read gate is
held; a queued worker conflict becomes visible only after that decision and
therefore cannot land between the two receipts.

## Physical composition

G4 already applied source reflectance when it built

```text
L_bounce0 = rho_source / pi * E_direct
```

and stored incoming indirect irradiance as L1 SH. G5 reconstructs

```text
E_indirect(n) = max(0, c0 + dot(cxyz, n))
L_indirect_receiver = rho_receiver / pi * E_indirect(n)
```

`rho_receiver / pi` is applied exactly once, after vertex interpolation and
using the existing prepared terrain albedo. Source rho is never applied again.
L3/L4 direct, directional sun, clustered local light and local/material GGX
remain byte-for-byte on their existing paths.

For valid confidence, G5 replaces only the declared approximate ambient or
indirect term. It is not added on top of that term or the vanilla lightmap. For
`confidence == 0`, the shader takes the untouched existing fallback branch; a
zero-weight arithmetic rewrite is not sufficient evidence for an exact
fallback. Values must remain finite and non-negative within the G4 FP16
tolerance.

Mechanical tests must prove at least:

- zero SH or black receiver rho gives exact zero indirect contribution;
- red SH cannot create green or blue energy;
- all six axis normals reconstruct the declared L1 lobe and unsupported normals
  fail closed;
- out-of-coverage and confidence zero preserve the exact existing fallback;
- receiver rho appears once and source rho does not appear in G5;
- control, candidate and field share one generated shader/resource ABI;
- an exact Sodium 0.9.1 encoder test covers `code=0..15`, proves the two
  high-bit writes above, proves both lower 30-bit position payloads unchanged,
  and proves every non-position byte, including light and color/alpha,
  byte-exact;
- encoder negative tests reject reserved codes, a foreign high-bit owner,
  non-axis/quad-inconsistent faces and every source/layout version mismatch;
- every Sodium terrain variant has vertex reads only, while fragment MSL has
  zero G5 texture reads;
- generated MSL for every Sodium solid/cutout/translucent vertex variant
  reconstructs exactly the declared four-bit code, requires bit 3 for G5 and
  masks positions identically; actual base Metallum and `SUN_SHADOW` MSL ignore
  the high bits and have zero G5 resource bindings or irradiance work;
- reflected terrain is suppressed, while ordinary light-only relight remains
  enabled and cannot modify the position sideband;
- bind-before-ready, stale owner, wrong thread/device and release-in-flight are
  fail-closed.

## Resource gate

The fixed diffuse-GI cap remains `25,165,824` bytes (24 MiB). Accepted G2+G3+G4
accounting is `22,637,928` bytes, leaving `2,527,896` bytes for all G5 native and
Java persistent/in-flight accounting. The G5 census includes a conservative
`65,536`-byte opaque lifetime charge for its sampler, Swift registry/context,
Java owner/read capability and allocator metadata, plus the native
`allocatedSize` of all four immutable 64-byte params buffers. It is not a claim
that opaque object overhead is free or exactly queryable.

G5 must report its actual params/lifetime allocation and prove

```text
22,637,928 + G5_accounted_bytes <= 25,165,824
```

Texture reuse is counted once. Reporting the four G4 textures again as G5
memory is an accounting error; omitting G5 params or lifetime storage is also an
error. There are no steady-state allocations, readbacks or new texture/buffer
objects after admission.

The four in-word carrier bits are not a geometry sidecar and allocate no
additional vertex storage. A separately allocated compact geometry sidecar is
not part of this implementation. If the vertex candidate fails, sidecar work
needs a separate request, predeclared census and new stop-gate. Fragment
sampling and screen-space reconstruction are not fallback candidates.

## Predeclared Tier B dry ABBA gate

The performance decision uses four independent launches in exact order:

```text
control -> candidate -> candidate -> control
```

Each launch uses 600 presented warm-up frames and 600 measured frames, native
3024x1964 HDR, Advanced/Balanced, Fancy 16/12, MetalFX and VSync off, the same
immutable frozen route/fixture/settings, nominal or fair thermals, detailed
stage timing, zero timing drops and no renderer/pipeline fallback. Source,
artifact, fixture, settings and shader-library mode must match across all four
runs. Every launch must contain exactly one admission and one final receipt
with `carrier_skips=0`, a strictly positive
`g5_carrier_writes=[1-9][0-9]*`, and a strictly positive
`drawn_g5_carrier_slices=[1-9][0-9]*`. In particular, the final receipt is
`GI_G5_FINAL state=READY carrier_skips=0 g5_carrier_writes=[1-9][0-9]* drawn_g5_carrier_slices=[1-9][0-9]* status=PASS ... contract=4`
after `MEASURE_END` and before `COMPLETE`.

The two comparisons are `candidate_1 - control_1` and
`candidate_2 - control_2`, using the adjacent outer control for each candidate.
Both comparisons must independently satisfy:

- `WORLD_OPAQUE` p95 increase no greater than `0.30 ms`;
- whole-frame presenting-command-buffer GPU p95 increase no greater than `2%`.

Tier B FPS and 1% low may be recorded descriptively but are never compared with
or presented as production FPS. Passing this gate supports only receiver
feasibility. It is not Tier C, G6 live-update acceptance, G7 product acceptance
or visual signoff.

A measured result is `PASS_RECEIVER_FEASIBILITY` only when both pairs pass and every raw
artifact is complete. Either pair exceeding a bound yields
`REJECT_DRY_PERFORMANCE_GATE`. This measurement grammar remains the reproducible
historical G5 gate even though the explicit 2026-08-29 user decision opened G6.

## Evidence boundary and reproduction

The structural contract is checked with

```bash
python3 tools/gi_g5_contract.py --root .
```

When `benchmark/gi/g5-receiver-evidence-v1.json` is absent, a successful command
means only that the structural implementation is valid. It must not synthesize
metrics, hashes or visual evidence. Use `--require-evidence` when a caller
requires the real Tier B bundle rather than the explicit user decision.

An evidence manifest, if added later, must bind the exact commit/source/artifact,
the four raw reports and recomputed summaries/logs/transcripts, G5 memory census,
ABBA order and both pair deltas. The contract rejects a pending/final decision
that is not backed by those files. Historical G4 raw receipts and
`benchmark/gi/g4-transport-evidence-v1.json` remain unchanged.

The explicit user decision is the stage-completion authority for G6 sequencing.
It does not replace focused receiver CPU/runtime/shader tests, source-compiled
and bundled validation, generated MSL inspection for all
terrain variants, the existing G0-G4 contracts and the full project `check`.
Those mechanical results do not replace the dry ABBA gate or a manual `field`
inspection of the frozen red-wall/white-floor, zero-source and sealed-wall
fixtures.
