# GI Stage G4: frozen one-bounce diffuse transport

Status: `G4_COMPLETE_FIELD_ONLY`, decision `PASS_FIELD_ONLY_ONE_BOUNCE`. The
predeclared clean live Tier B stop-gate and source/bundled Metal validation have
passed. This completion does not authorize a receiver or G5.

G4 is a private field-only experiment. It consumes accepted G2 material truth
and the completed G3 direct-irradiance field, but exposes no terrain/image
binding, changes no generated terrain shader and cannot affect the production
image. G5 remains a separate stop-gated receiver stage.

## Frozen input boundary

The first candidate builds exactly one near cascade:

- edge `32^3`, cell size `2` blocks and world-snapped G2/G3 near origin;
- one immutable tuple of world, clipmap, palette, content, static-source and
  environment epochs;
- G3 must have completed and retired all 192 initial dirty bricks before G4 is
  admitted;
- benchmark preparation opens only after the route has been stable for 120
  frames and G2 has zero active candidates. The sole `OFF` benchmark mode is
  applied at that boundary, stability is acquired again after its deferred
  resize, and only then may G3 preparation begin. The complete
  G2/world/origin/static source/environment tuple must remain unchanged for 600 submitted frames;
  once committed, any drift is terminal and cannot rotate or rebuild G3;
- G3 must be `ready`, not in flight and have zero pending/discarded work;
- G2 rho/faces/validity and G3 geometry/direct origins and epochs must match;
- native acceptance first enters `SUBMITTED`; Java promotes it to `READY` only
  after stats prove one completed dispatch/build with the exact tuple. A failed
  completion or a submission still in flight after 120 frames fails closed;
- after `READY` the context never scrolls, rebuilds or admits a new epoch.
  Changes to G2/world/origin, the static-light registry or quantized sun/sky
  input are reported stale once and cannot replace the frozen field.
- after the exact 192-brick G3 population becomes ready, the route remains in
  startup for another 300 frames. This drains a complete timing window and
  proves exactly 24 `GI_INJECT` active frames before G4's sole warmup dispatch;
  measurement closes both G3 and G4 submission.

The runtime can be requested either by `METALLUM_GI_G4_TRANSPORT=1` or by the
restart-gated `G4 Transport Debug HUD` option on Metallum's Sodium page. Both
paths are diagnostic-only and require requested Advanced lighting, the G2
accepted-output capture path and G3 source injection. With neither request,
G4 remains structurally OFF: zero G4 resources, passes, symbols, bindings and
dispatches. The Sodium option defaults to OFF and is suppressed during benchmark
runs so it cannot contaminate a tracked receipt.

The restart-gated Sodium option is also consumed by the early mixin config
plugin. This is required because G2 observes Sodium meshing and upload classes
before `GiTransportRuntime` is initialized. The setting therefore enables the
same exact-version-locked set of twelve G2 capture mixins as the environment
request; runtime-only enablement would leave G3 permanently without an accepted
semantic field. The HUD reports G2 and G3 readiness separately so a missing
Sodium capture can no longer look like ordinary G3 settling.

The G2 mixin lock covers only the bytecode it actually targets: Minecraft
`26.2`, Sodium `0.9.1+mc26.2` and MixinExtras `0.5.4`. The independent relight
oracle retains its stricter Fabric Renderer API lock. Coupling G2 to that
unrelated module previously disabled all semantic capture on the supported
Fabric API `0.154.2+26.2` distribution (`fabric-renderer-api-v1 14.1.2`) even
though every inspected G2 Sodium target descriptor was unchanged.

## Accepted G2 transport cell

G4 uses a dedicated immutable render-thread view; it does not reinterpret G3
padding or the L5 optical byte. Each near-cascade cell is exactly 16 bytes:

| Bytes | Meaning |
|---:|---|
| `0..5` | diffuse reflectance rho RGB, raw little-endian `UNorm16` from accepted G2 albedo |
| `6..7` | G2 occupancy expanded to `UNorm16`; geometry admission only, never radiance |
| `8..13` | signed face weights `-X,+X,-Y,+Y,-Z,+Z` as `UNorm8` |
| `14` | G2 validity ABI |
| `15` | accepted known-coverage `UNorm8` |

UNKNOWN, AIR and FALLBACK cells cannot produce a bounce. Only accepted CONTENT
with non-zero occupancy, coverage and at least one face has surface support.

## Bounce and deterministic stencil

The source bounce is initialized once in a private `RGBA16Float` volume:

```text
L_bounce0(q) = rho(q) / pi * E_direct(q)
```

There is no second bounce. Initialization reads G3 direct irradiance and the
frozen G2 transport cell, then writes a distinct texture. Transport reads that
immutable texture and writes separate SH outputs, so it is one fixed Jacobi
iteration with no in-place feedback, float atomics or race-dependent reduction.

For each receiver cell `p`, the gather visits a fixed lexicographic stencil:

- every non-zero signed direction in `{-1,0,1}^3` (26 directions);
- integer distance `k = 1..8` along each direction;
- 208 possible source offsets in total, always in the same order.

For direction vector `d` and distance `k`, the unnormalized weight is

```text
w(d,k) = 1 / (length(d) * k^2)
S      = sum(w) = 29.17999846648958
```

The source-facing support is the L1-normalized interpolation of the six G2 face
weights in the outward direction from `q` to `p`, so it is in `[0,1]` even when
all six max-normalized G2 weights are one. The declared form factor is

```text
F(q,p) = pi * w(d,k) / S * sourceFaceSupport(q -> p)
```

Receiver CONTENT/coverage is a validity gate, not another reflectance
multiplication. Because visibility and every support gate are in `[0,1]`, the
complete destination set for any source satisfies `sum_p(F(q,p)) <= pi`.
Therefore

```text
sum_p E_indirect_DC(p) <= sum_q rho(q) * E_direct(q)
```

before the declared FP16 tolerance. This prevents the max-normalized G2 face
representation from multiplying source energy.

Visibility is conservative supercover traversal. Every lattice cell touched
between `q` and `p` must be authoritative AIR. CONTENT before the endpoint,
UNKNOWN, FALLBACK, an out-of-range endpoint or a mismatched epoch transfers
strictly zero. Diagonal corner/edge rays check all crossed subcells and cannot
skip a one-cell sealed wall.

## L1 SH contract

The incoming direction `omega` points from receiver `p` toward source `q` in
world axes `+X,+Y,+Z`. G4 stores the scaled real L1 basis `[1,x,y,z]`. Three
private `RGBA16Float` volumes are channel-major:

```text
SH_R = [c0.r, cx.r, cy.r, cz.r]
SH_G = [c0.g, cx.g, cy.g, cz.g]
SH_B = [c0.b, cx.b, cy.b, cz.b]

c0   = sum(T)
cxyz = sum(T * omega)
T    = L_bounce0(q) * V(q,p) * F(q,p)
```

The diagnostic reconstruction for a unit normal is
`E(n) = c0 + dot(cxyz,n)`. Non-negative weights and unit directions guarantee
`length(cxyz[channel]) <= c0[channel]`, hence a finite non-negative
reconstruction in exact arithmetic. Before storing FP16, the shader clamps DC
to the largest finite half value, quantizes DC first, and projects the
directional lobe to at most `DC * (1 - 1/512)`. Independent half rounding can
therefore neither overflow nor create a negative reconstruction beyond the
declared tolerance. G5, if later authorized, must apply
receiver `rho_receiver / pi` exactly once and must not reapply source rho.

A private `R8Unorm` confidence volume stores the normalized weight of fully
known paths. Known occlusion contributes confidence but zero energy; UNKNOWN or
out-of-coverage paths contribute neither. Debug paths expose the G3 source
separately; the G4 one-shot capture returns bounce, all three SH lobes,
confidence and the separately retained accepted-cell validity without an image
binding.

The live Sodium HUD deliberately does not call the blocking validation capture.
It publishes the already available immutable admission statistics while G3 settles. For an
interactive debug request, the sun/sky descriptor is latched once so the normal
day cycle cannot reset the 600-frame immutable-input gate forever. After READY,
native schedules one asynchronous blit into its preallocated shared buffer;
Java polls without waiting and publishes a middle-Z CPU preview of the captured
indirect SH DC field. This adds no terrain, fragment or present binding and no
GPU wait in the frame loop. The debug-only Java destinations add `1,081,344`
bytes, bringing the diagnostic end-to-end total to `23,719,272` bytes, still
below the 24 MiB gate; benchmark mode allocates none of these destinations.

## Fixed numerical and resource gates

- Jacobi iteration count: exactly `1`.
- Maximum transport distance: `8` near cells (`16` blocks).
- Raw repeat on the same GPU, source and shader-library mode: byte-identical
  captured bounce/SH/confidence hash.
- FP16 reconstruction absolute tolerance: `1/1024` irradiance unit.
- Global captured energy tolerance: `max(1/1024, reflectedInput / 512)` per
  channel (`0.1953125%` relative above the absolute floor).
- G2 conservative end-to-end (`18,022,528`) + G3 native `allocatedSize`
  (`1,104,096`) and persistent Java FFM packets (`70,216`) + G4 native
  `allocatedSize` (`2,916,480`) and persistent Java FFM packets (`524,608`)
  totals `22,637,928` bytes and must remain at or below `25,165,824` bytes
  (24 MiB). Small on-heap Java control objects are outside this byte census.
- No `Arena.allocate`, `makeBuffer`, `makeTexture` or readback in repeated frame
  iterations. G3/G4 staging/output/debug resources and PSOs are precreated at
  device admission, outside frame submission, and retired through the existing
  in-flight destruction discipline. The steady-state observer reuses the
  accepted source identity and performs no G3 stats downcall.
- Java makes the raw G3 owner an unforgeable capability. Native live-context
  registries validate both G3 and G4 opaque handles before `Unmanaged` access,
  so forged or already released owners return a clean invalid status.
- G4 attaches its telemetry owner to G3 at resource admission, before any
  dispatch. The attachment is same-thread/device/queue checked, idempotent only
  for the same pair, rejects a second live G4, and strongly retains G3 until
  keyed detach. Thus every startup timing window truthfully reports contract v3
  and the combined 11-resource/4-pass footprint.

## Correctness gates

Source and bundled Metal Validation must independently prove:

- zero source and black rho produce exact zero bounce and SH;
- red input cannot create green or blue energy;
- a red wall can reach a visible white floor only through a known path;
- a sealed supercover wall transfers exact zero;
- opening/closing the synthetic aperture changes only the physically connected
  field after a new independently created frozen context;
- UNKNOWN, FALLBACK, AIR and occluded endpoints transfer zero;
- DC energy respects the global reflected-input bound;
- reconstructed L1 values are finite/non-negative within tolerance;
- repeat contexts in the same shader mode produce the same raw hash;
- stale epoch, wrong thread and release-while-in-flight are fail-closed;
- the compact logical native G3 epoch is kept separate from the captured
  process-local static-registry epoch: a stable registry identity remains valid,
  while a real post-freeze registry mutation is rejected;
- the pre-route `OFF` mode freeze occurs before G3 preparation and cannot issue
  another renderer resize at `SEGMENT_START`;
- a forged/stale native G3 capability is rejected without dereference;
- telemetry attach rejects wrong-thread, forged, released and different-queue
  G3 owners; same-pair attach is idempotent, a second G4 is rejected and an
  attached G3 remains alive until G4 release;
- source-fallback and bundled tasks assert the actual shader-library mode.

The compute-only frame graph is `G2 cells + G3 direct/geometry -> bounce0 ->
one Jacobi transport/SH + confidence`. It contains no scene color, depth,
terrain, fragment, render, UI or present consumer.

## Predeclared Tier B stop-gate

The live diagnostic uses the frozen `600+600` M1 Pro profile from the accepted
GI fixtures: 3024x1964 HDR, Advanced/Balanced, native resolution, MetalFX and
VSync off, nominal/fair non-invalid thermals, zero timing drops and no renderer
fallback.

Every attested raw timing window must already use schema 6, GI contract v3 and
metadata mode `g4_transport`; a v2 window cannot hide pre-attachment G4 memory
or work inside a completed receipt.

- the tracked route/settings digests, clean source/artifact identity and exact
  300-frame windows are immutable across startup, warmup and measurement;
- the Minecraft receipt proves pre-route `OFF` mode freeze before G3 preparation;
- startup contains at least 600 reported frames, the frozen epoch cannot drift
  after population begins, and `GI_INJECT` totals exactly 24 active frames;
- exactly one frozen near-cascade build during warmup;
- exactly one `GI_TRANSPORT` dispatch and no measured-window counter growth;
- `GI_TRANSPORT` p95 no greater than `4.0 ms` and maximum no greater than
  `6.0 ms`;
- no second bounce, scroll, full-volume rebuild loop or steady-state work;
- combined memory remains within 24 MiB;
- whole-frame/FPS values are descriptive Tier B data only and are not compared
  numerically with Tier C production baselines.

A complete receipt hashes five same-stem artifacts: raw JSONL, recomputed
summary, Minecraft log, Gradle console log and the whole-launch transcript. The
transcript binds the exact clean commit/source, artifact, route, fixture and
settings identities; the Minecraft log binds preparation, startup drain,
Advanced admission, G4 admission and measurement order.

A sealed-wall leak, non-repeatable hash, energy amplification, non-finite SH,
memory excess, unbounded/repeated work or any production image binding rejects
G4 and blocks G5. Passing G4 proves only a private physical field and bounded
diagnostic cost; it is not visual or product acceptance.

## Accepted Tier B receipt

The clean Apple M1 Pro run on 2026-08-27 attests implementation commit
`460d29431321` and source identity
`9085da37f69d31c7267a086acde895a021aa0c6a395564b40fc4b509b1ad5a07`.
It completed the frozen 600-frame warmup and two exact 300-frame measurement
windows at 3024x1964 HDR, Advanced/Balanced, native resolution, MetalFX and
VSync off.

- G3 completed 192/192 initial bricks in exactly 24 active injection frames;
- G4 admitted one frozen near-cascade build and exactly one
  `GI_TRANSPORT` dispatch during warmup;
- that dispatch measured `0.923291 ms` p95 and maximum, below the predeclared
  `4.0/6.0 ms` gates;
- both measurement windows retained one total dispatch, so measured dispatch
  growth was zero;
- the receipt had zero timing drops, zero renderer fallbacks and `COMPLETE`;
- combined G2+G3+G4 end-to-end memory is `22,637,928` bytes of the
  `25,165,824`-byte budget;
- source-compiled and bundled-metallib validation produced the same raw digest
  `310720372c19146c4b1a83e5c031e6696dfed41c37f4a67fc42e85d929d0025a`.

The whole-frame value, `43.391665 FPS`, is descriptive Tier B telemetry only.
There is no production A/B, image-quality claim or Tier C acceptance because
G4 has no receiver or image binding. The canonical hashes and five same-stem
artifacts are recorded in
`benchmark/gi/g4-transport-evidence-v1.json` and
`benchmark/gi/evidence/gi-g4-transport-2026-08-27-v1/`. G5 remains blocked
until a new explicit receiver request and stop-gate.
