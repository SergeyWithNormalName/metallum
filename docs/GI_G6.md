# GI Stage G6: live diffuse transport

Status: `PASS_LIVE_DYNAMIC_GI` on 2026-08-31. G5 was accepted by explicit user
decision on 2026-08-29, including its performance gate; its visual acceptance
is still pending. The completed G6 receipt does not manufacture a missing G5
ABBA bundle or visual evidence.

G6 turns the accepted frozen source/transport/receiver chain into a restart-gated
production path. The persistent Sodium option is `Global illumination`; it
writes `globalIllumination=dynamic`. `off` is structural: after restart there is
no live GI owner, transport dispatch, live receiver resource binding or G6
allocation. Enabling GI also enables Advanced lighting. Disabling Advanced
lighting switches GI off. Production G6 and the private G2--G5 diagnostic
environments are mutually exclusive.

## Live epoch and update contract

The live owner is world/dimension scoped. It consumes immutable G2/G3 semantic
snapshots and a camera-independent dynamic source snapshot. Held/entity sources
have stable IDs, bounded world-space membership and explicit expiry. A camera
turn cannot change their epoch or contribution while the source and coverage
are unchanged.

Updates are scheduled by the monotonic real-renderer submit index, never by
presented-frame count or frozen server `gameTime`. The dynamic collector keeps a
separate immutable membership epoch and publication tick; those prove current
source truth but do not spend GPU work. Thus a deterministic benchmark may
freeze simulation without starving live convergence, while MetalFX-generated
presentations cannot accelerate it. At most one G3 batch and one near-to-far G6
cascade are submitted for one renderer submit. Static block/material/source
changes, clipmap movement, dynamic source movement/expiry and full reset produce
a new field epoch. An incompatible prior field is masked to zero in the first
submit; it cannot remain visible while a new epoch is built. A cascade's
full-ready bit is published only from the successful command-buffer completion
handler after all 64 of its bricks are exact. Partial masks are allowed during
a bounded transition, and the vertex receiver admits each exact trilinear
footprint while blending fine coverage into the compatible coarser cascade.

The fixed release SLA is:

- accepted near static/source change: recovery p95 at most 8 submits, p99 at
  most 16;
- one-brick scroll: near coverage p95 at most 16, p99 at most 32;
- teleport/full reset: incompatible coverage is zero immediately, new near
  coverage p95 at most 32, p99 at most 64.

Time-of-day and weather rotate the global environment input used by every
direct-field brick, so all 64 bricks are rebuilt and retain the full-reset latency
class. Inside the same world/resource/palette/origin root, however, the last proven
receiver field remains spatially compatible and visible until its replacement is
ready. World, resource, material, palette, teleport and device resets remain
structurally fail-closed. Bounded registry, held, entity and block-emitter deltas
remain local static-source updates under the 8/16-submit SLA.

For block/static-source changes, recovery means the affected near cascade is
fully exact again; unrelated retained bricks cannot close the interval. For
scroll/full reset, which first publish incompatible zero coverage, recovery is
the first current exact near brick admitted by that same per-brick vertex
receiver contract. Full readiness of all three cascades remains a separate
publication and transaction-closure condition and is required by the final
receipt.

A bounded scroll first remaps compatible exact near bricks in the current
command buffer without waiting for the new G3 source texture. The later
authoritative handoff rebases on that completed mask and rebuilds exposed or
source-affected bricks near-to-far. This preserves the per-brick exact contract
while preventing unrelated G3 convergence or source churn from turning a
one-brick scroll into a long zero-coverage interval.

There are no steady-state CPU allocations, CPU texture readbacks, queue waits or
frame-count-driven rebuilds in the G6 path. The shared G2+G3+G6 accounting must
remain at or below `25,165,824` bytes. Resource reuse is counted once.

## Receiver contract

The terrain receiver remains vertex-only. It samples L1 irradiance and
confidence for the three compatible cascades, evaluates the authored exact
axis normal, and passes ready irradiance to the fragment shader. The fragment
stage contains no G6 `sampler3D`, DDA or cone trace. Receiver reflectance is
applied once; confidence zero preserves the existing ambient fallback. The
Sodium four-bit position sideband and its fail-closed carrier rules remain the
G5 contract.

## Automated live route

First enable Global illumination in Sodium and restart Minecraft. The runner
does not edit or temporarily override this persistent choice. Then run:

```bash
scripts/run_metal_benchmark.sh --gi-live --preflight-only
scripts/run_metal_benchmark.sh --gi-live --label gi-g6-live
```

`--gi-live` selects the tracked `hdrtest-torch-toggle-v1` route and
`native-hdr-fancy-gi-live-v1` settings. The measured route places and removes a
torch at fixed source epochs while the deterministic world clock and weather
remain frozen. It requires native 3024x1964 HDR, Advanced/Balanced, Fancy 16/12,
MetalFX/VSync off, 1800 warm-up plus 3000 measured frames, and rejects every
explicit G2/G3/G4/G5 diagnostic environment.

The run is valid only when it contains exactly one clean `GI_G6_ADMISSION`
during warm-up, the complete ordered torch-toggle epoch receipts, one clean
`GI_G6_FINAL` after `MEASURE_END`, and then one `COMPLETE`. Admission permits a
partial non-zero ready mask. The stable final receipt requires `ready_mask=7`,
`build_in_flight=false`, no current fallback, no stale/rejected publication,
and combined accounted memory within the fixed cap. Any `GiLiveRuntime.INVALID`,
command-buffer failure, missing route marker or receipt ordering error makes the
run fail.

The deterministic functional matrix is:

```bash
scripts/run_metal_benchmark.sh --gi-live \
  --route benchmark/routes/hdrtest-gi-g6-matrix-v1.json \
  --label gi-g6-matrix-final-v18
```

It uses the same quality profile for 1800 warm-up plus 3600 measured frames and
executes 22 ordered events with 20 matching recovery receipts: camera orbit,
lava placement/removal, F3+A, resource reload, day/night, rain/clear, eight
clipmap-scroll steps, far teleport and Nether round-trip. It requires all four
latency classes to meet their fixed SLA, the terminal scheduler algebra to
close with zero pending/in-flight work, unchanged accounted memory, no readback,
and exact `ready_mask=7` before `COMPLETE`.

For one automated static image while the fixture torch is on:

```bash
scripts/run_metal_benchmark.sh --gi-live --capture-reference --label gi-g6-image
```

The controller requests the PNG exactly at measured frame 400, after the torch
placement has been confirmed client-side (frame 300 or later) and before the
removal request at frame 450. The runner rejects a capture marker outside that
ordered torch-on interval. The PNG is copied to the ignored reference directory
and is deliberately not performance-attested. A still image can check obvious
composition and gross artifacts while indirect light is present; it cannot
prove post-removal decay, camera-motion stability, scroll, popping, teleport
recovery or artistic acceptance. Those remain separate live-review evidence and
belong to G7 product acceptance.

## Evidence boundary

The structural runner contract is:

```bash
./gradlew giG6RunnerContractTest
```

It validates the tracked route/settings digests, persistent dynamic setting,
diagnostic isolation, screenshot availability, admission/final grammar and
memory gate. It does not claim that Minecraft was launched.

The source-bound live and full-matrix receipt is complete in
`benchmark/gi/g6-live-evidence-v1.json`, with immutable artifacts under
`benchmark/gi/evidence/gi-g6-live-2026-08-30-v1/`. The accepted run used source
digest `10f157bef162025e874219b53a9574d572329408e31201490374359995b55092`
and artifact digest
`2dae57f64db16658d10326b3ad5ed21a95f8759d789b7e203a96dcbc64bd8bbb`.
The torch-toggle run completed 1800 warm-up plus 3000 measured frames with exact final
`ready_mask=7`, no in-flight build, zero stale/rejected publications, a current
terrain/carrier binding, and 178 accepted block-update samples. Recovery was
p95 1 and p99 1 renderer submits, inside the fixed 8/16 SLA. The historical matrix
passed all 22 events/20 recoveries; static p95/p99 was 1/2, scroll 12/12 and full
reset 20/20 submits. The later flicker repair keeps that latency attribution but
retains compatible visible history during same-root environment rebuilds; fresh
runtime evidence is required for that successor contract. Combined accounted
memory was 24,637,728 bytes against the 25,165,824-byte cap.

The separately captured 3024x1964 torch-on PNG passed a direct static
gross-artifact review: no black/zero atlas, NaN bands, cascade seams, broken
geometry, or lost terrain detail were visible, and local warm illumination was
spatially distinct on terrain and foliage. This is static reference evidence,
not human motion or artistic product acceptance; those remain G7. The automated
orbit/scroll/teleport matrix is functional G6 evidence and does not substitute
for that product review.
