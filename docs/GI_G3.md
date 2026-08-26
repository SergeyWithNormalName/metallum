# GI Stage G3: bounded direct-source irradiance field

Status: `G3_COMPLETE_FIELD_ONLY`. The opt-in `METALLUM_GI_G3_INJECT=1` path
builds deterministic direct irradiance from accepted G2 emission,
camera-independent L4 sun/sky, and static L3 block emitters. It has no bounce,
terrain receiver, image binding, or production-default activation. Machine
evidence is bound by `benchmark/gi/g3-direct-source-evidence-v1.json` and
`tools/gi_g3_contract.py`.

## Physical and source boundary

G3 writes linear HDR direct irradiance `E_direct`. It deliberately has no
albedo/reflectance input and never applies `rho / pi`; that operation belongs
to G4. Consequently changing only black albedo cannot create or suppress G3
energy. G3 also does not read vanilla lightmap, camera, depth, history, screen,
receiver, or transport state.

The admitted sources are:

- non-premultiplied emission RGB plus intensity from accepted G2 cells;
- the L4 AIR environment's world-space sun direction, directional RGB, sky RGB,
  and quantized epoch;
- L3 `BLOCK` and `STATIC_CACHE` emitters from the world-space registry before
  camera/frustum top-K admission, capped at 16 deterministic sources per brick.

Held, entity, and other dynamic frame sources remain excluded until G6. Static
source positions are serialized relative to the cascade origin, so large world
coordinates do not lose precision and camera movement cannot affect selection.

## Field, visibility, and ordering

The private native context owns three `32^3 RGBA16Float` direct-irradiance
textures and three `32^3 R8Uint` geometry-state textures at 2/4/8 blocks per
cell. UNKNOWN and FALLBACK are conservative occluders; only known empty cells
permit a ray to pass. Emission is admitted only for known content. Sun/sky and
static-local visibility use a fixed 32-step DDA in update compute, never in a
terrain fragment.

Each update encodes all geometry-apply dispatches, one texture barrier, then
all source-injection dispatches in the existing Advanced command buffer and
fence domain. The separately validated frame graph declares six private
resources and two compute passes, with no scene-color, terrain, UI, or
presentation consumer.

## Bounded scheduling and lifetime

The logical field contains 192 `8^3` bricks. A fixed render-thread queue drains
at most eight bricks per frame and coalesces repeated content stamps. The L3
registry maintains a separate static epoch; dynamic frame publication cannot
rotate G3. Before an initial or changed static source generation is admitted,
the registry must remain unchanged for 16 render ticks. This prevents chunk
startup from repeatedly discarding an almost complete full-field population.

Headers, bricks, cells, static sources, statistics, and scratch arrays are
preallocated. The native side owns a three-slot shared staging ring. No
`Arena.allocate`, `MTLBuffer`, or `MTLTexture` creation occurs in the frame-loop
update. The one-shot raw slice capture is test/debug-only. Context destruction
goes through the existing deferred GPU retirement queue; wrong-thread calls
fail cleanly.

Measured Apple M1 Pro allocation:

| Item | Bytes |
| --- | ---: |
| Private direct + geometry fields | `884,736` |
| Three staging slots | `210,144` |
| Diagnostic readback | `9,216` |
| Accounted total | `1,104,096` |
| GI hard budget | `25,165,824` |

## Correctness verification

Both source-compiled MSL and the bundled metallib run under Metal API and GPU
Validation. They prove exact-zero output with no sources, red-channel isolation
for G2 emission and static L3 emitters, zero sky in a sealed cave, sky admission
through an aperture only after its geometry batch, repeat-identical raw field
payload for identical world-space inputs, stale rejection, render-thread
confinement, and safe release with work in flight.

The Java source-chain test rejects camera, lightmap, brightness, screen, depth,
history, albedo, reflectance, receiver, and transport dependencies. The CPU
tests cover negative coordinates, deterministic static top-K, dynamic-source
exclusion, quantized L4 epochs, stale queue rotation, starvation, the eight
brick drain cap, explicit full-field accounting, and the separate L3 static
epoch.

## Tier B result and retained negative evidence

The final live receipt used the frozen `hdrtest-static-v1` overworld route at
3024x1964 HDR, Advanced/Balanced, MetalFX and VSync off, with 600 warmup and 600
measured frames. It completed with nominal thermals and zero timing drops.

- One initial population queued and completed exactly 192 bricks with zero
  discarded and zero pending work.
- The population completed during warmup in 24 active frames, exactly eight
  bricks per frame. `GI_INJECT` measured `0.081 ms` average, `0.122 ms` p95,
  and `0.123 ms` maximum.
- Both measured windows retained `full_volume_rebuilds=1`, had no `GI_INJECT`
  work, and had no scheduler-counter delta.
- Active telemetry reported 6 resources, 2 compute passes, 0 image bindings,
  0 transport dispatches, 0 stale rejects, and 0 fallback failures.
- The run measured `43.115 FPS`, `32.488` 1% low, and `25.698 ms` whole-GPU p95.
  Those whole-frame values are descriptive Tier B data, not a Tier C baseline
  comparison.

Two earlier runs are retained as rejected evidence. Sharing the L3 dynamic
epoch left 192 bricks perpetually pending and discarded 260,560 entries. A
first static-epoch fix converged at steady state but performed 478 startup
full-field invalidations and discarded 89,600 entries. The separate static
epoch plus 16-tick settle gate reduced the accepted run to one initialization
and zero discarded work.

## Verification

Run:

```bash
./gradlew giG3SourceContractTest \
  giDirectSourceCpuUnitTest \
  giDirectSourceSourceChainUnitTest \
  giDirectSourceGpuValidationSource \
  giDirectSourceGpuValidationBundled \
  frameGraphValidation \
  benchmarkReportContractTest
./gradlew check
```

For live Tier B attribution, keep production `globalIllumination=off` and use
the diagnostic environment flag with detailed timing:

```bash
METALLUM_GI_G3_INJECT=1 \
METALLUM_L2_TIMING_DETAIL=1 \
METALLUM_L2_WARMUP_FRAMES=600 \
METALLUM_L2_MEASURE_FRAMES=600 \
./scripts/run_metal_benchmark.sh \
  --route benchmark/routes/gi-g0-overworld-v1.json \
  --settings benchmark/settings/native-hdr-fancy-v1.json
```

## Stop gate

G3 passes its field-only correctness and bounded-work gates. This makes G4
eligible only as a new, separately scoped request. G4 transport, SH projection,
`rho / pi`, every bounce, and every image receiver remain absent. Because G3
does not affect the rendered image, this stage makes no visual GI acceptance or
Tier C production-performance claim. The final integration run completed all
113 actionable Gradle tasks successfully.
