# GI Stage G0: baseline, fixtures and zero contract

Status: `REJECTED_BASELINE_FLOOR`. The G0 contracts and all six Tier C receipts
are complete, but the current renderer fails the approved Overworld absolute
floor. `benchmark/gi/g0-acceptance-v1.json` records the exact evidence and keeps
`g1_allowed=false`; G1 must not start until a separate quality-preserving
baseline recovery passes the same matrix.

## Accepted M1 Pro profile

- Device: Apple M1 Pro, non-headless, non-low-power; capability snapshot
  `benchmark/current/M1_PRO_GPU_CAPABILITIES.json`.
- Display: Built-in Retina Display, exclusive fullscreen,
  `3024x1964@120 Hz`.
- Renderer: Metallum render contract, Advanced lighting, Balanced preset.
- Output: native scene HDR/EDR, sRGB source encoding, Fancy graphics,
  render/simulation distance `16/12`, MetalFX Off, VSync Off, max FPS `260`.
- Instrumentation: `METALLUM_L2_TIMING_DETAIL=0`, Metal validation Off, no
  screenshots or HUD/capture instrumentation.
- Tier C: 1800 presented warm-up frames plus 3000 measured frames, exactly ten
  complete 300-frame windows, at least two independent runs per route.
- Thermal validity: no Serious or Critical state during the measured interval.

The single settings contract is `benchmark/settings/native-hdr-fancy-v1.json`.
Its schema-v3 digest includes `improvedLighting=true`,
`lightingPreset=balanced`, and `globalIllumination=off`; a runtime config that
omits or changes any of those values fails preflight.

## Baseline routes and floors

| Role | Route | Absolute average floor | Absolute 1% low floor |
| --- | --- | ---: | ---: |
| Overworld | `gi-g0-overworld-v1` | 30 FPS | 20 FPS |
| Sealed cave | `gi-g0-sealed-cave-v1` | 20 FPS | 10 FPS |
| Nether | `gi-g0-nether-v1` | 20 FPS | 10 FPS |

These floors are approved for native HDR Balanced on the exact profile above.
They do not move downward when the renderer becomes slower. A future GI
candidate must simultaneously satisfy:

- whole-GPU p95 regression no greater than 8% versus the matching multi-run
  baseline;
- 1% low regression no greater than 10%;
- the route-specific absolute average and 1% low floors above.

Tier B stage timings can attribute `GI_INJECT`, `GI_TRANSPORT`, and
`GI_RECEIVER` after those stages exist, but cannot replace these Tier C gates.

## G0 decision on 2026-08-24

All six final runs used commit `9c13e15b4669`, source digest
`cd0d6a105368ad3cdb3cc9b7b3bc06bb5c312963c5d31f459bcfa046279d7b5b`,
and artifact digest
`35e67f8ffd23ebff764b2131f02e8c603fcd1566c6b5e7d169db9c8deb77f225`.
Every run produced ten complete schema-v6 windows, nominal thermal state, a
strict `.accepted.json` receipt, Advanced L3/L5/L6 admission, and all-zero
GI_OFF telemetry.

| Route | Run average FPS | Run 1% lows | Mean GPU p95 | Floor | Decision |
| --- | --- | --- | ---: | --- | --- |
| Overworld | 20.073 / 20.076 | 17.063 / 16.727 | 53.218 ms | 30 / 20 FPS | **FAIL** |
| Sealed cave | 55.789 / 55.862 | 36.182 / 37.146 | 20.812 ms | 20 / 10 FPS | PASS |
| Nether | 21.691 / 20.699 | 19.082 / 17.600 | 50.468 ms | 20 / 10 FPS | PASS |

The Overworld failure is repeatable and is not a GI allocation, fallback,
thermal, dirty-build, or lighting-admission artifact. The floor is not lowered
to fit the slow baseline. The next permitted action is a separate baseline
diagnosis/recovery with its own hypotheses and A/B evidence; no G1 field work is
admitted by this result.

A follow-up Tier B attribution on the same route found `world opaque` at
`42.227 ms` average / `42.971 ms` p95. Sequential `NO_L3_RECEIVER`,
`NO_L4_RECEIVER`, and `NO_L6_RECEIVER` compile-time ablations produced GPU p95
deltas of `+1.424`, `+1.317`, and `+0.199 ms` versus `FULL_ADVANCED`; none
supported a receiver-local recovery candidate. These diagnostic runs are not
release receipts. The retained negative evidence is recorded in
`OptimizationHistory.md`; a future recovery now requires pipeline/draw/material
GPU-capture attribution inside `world opaque`, not a quality-reducing shader
shortcut.

## Immutable functional fixtures

`benchmark/gi/g0-fixtures-v1.json` binds ten deterministic, tracked Minecraft
function sources by SHA-256:

- sealed white room without a source;
- red emitter and green emitter rooms;
- one-block skylight aperture;
- Nether lava landmark;
- intersecting thin geometry;
- exact chunk and proposed cascade test boundaries;
- scroll and out-of-coverage teleport targets.

The source functions are the immutable authoring truth. They contain no random,
loot, entity-spawn, camera-history, or screen-space input. The manifest also
binds all three static baseline routes to the verified read-only
`hdrtest-static-v1` APFS fixture. `tools/gi_g0_contract.py` checks source, route,
fixture and settings digests plus the boundary geometry invariants.

Materializing the functional sources into a future world snapshot must produce
a new fixture ID and tree digest; editing the existing read-only fixture in
place is forbidden. This avoids changing G0 baselines when G1/G2 add field
debug views.

## Telemetry schema v6

Every native timing window contains an exact `global_illumination` v1 object.
It reserves:

- allocated/resident bytes and resource/pass/binding/shader-symbol counts;
- valid/unknown probes;
- dirty queued/completed/discarded/pending counters;
- injection and transport dispatches;
- source, probe and field epochs;
- stale-cell rejects;
- fixed reset-reason and fallback-reason maps.

The parser rejects unknown/missing keys, negative counters, resident bytes above
allocation, inconsistent dirty algebra, mixed modes/contracts, and any non-zero
numeric leaf or reason while `mode=off`. Release schema-v6 windows must also
carry `metadata.global_illumination_mode=off`.

## Compile-time and runtime `GI_OFF`

G0 is structural rather than a zero-valued enabled path:

- renderer config schema 5 admits only `GlobalIlluminationMode.OFF`;
- the Advanced frame graph contains no `gi_*`, global-illumination, irradiance
  field or GI probe resource/pass;
- no GI context, Metal texture/buffer, pipeline, encoder, binding, collector or
  downcall exists;
- the real Sodium terrain shader is compiled through GLSL, SPIR-V and
  SPIRV-Cross, and emitted MSL is rejected if any reserved GI helper/resource
  token survives;
- benchmark preflight emits `GI_OFF_ADMISSION ... status=PASS`; G0 routes also
  forbid the separate vertex-reflection experiment.

The already quarantined frozen-reflection research asset is not GI and remains
a separate default-off experiment. It may be packaged, but G0 routes cannot
enable it, and ordinary generated terrain MSL contains neither its bindings nor
any GI symbols.

## Reproduction

Preflight each route before any long run:

```bash
scripts/run_metal_benchmark.sh --preflight-only \
  --route benchmark/routes/gi-g0-overworld-v1.json \
  --settings benchmark/settings/native-hdr-fancy-v1.json \
  --lighting-preset balanced
```

Remove `--preflight-only` and use unique labels for the two Tier C runs of each
route. Do not set frame overrides: the default is the required `1800+3000`.
All six `.accepted.json` receipts must pass `verify-attestation` against their
raw, summary, Minecraft and console logs before their hashes enter the G0
acceptance artifact.

## Evidence boundary

- Contract/build/generated-MSL/preflight results are `PROVEN` only for their
  mechanical claims.
- Valid Tier C receipts are `PROVEN` performance evidence for the exact routes,
  source and settings digests they attest.
- Fixture sources are deterministic test definitions; their future GI visual
  appearance remains `UNKNOWN` until G1+ field/debug and live acceptance.
