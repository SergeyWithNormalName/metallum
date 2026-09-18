# Lava L6 shadow architecture experiment

Date: 2026-08-16

Target: Apple M1 Pro, 3024x1964, Native HDR, Balanced, MetalFX OFF

Final decisions:

- **GPU-on-demand static lava bootstrap: ACCEPTED.** Two important coarse-dense
  static lava pages now become valid through the existing GPU L5-to-L6 builder in
  6.672-6.805 ms, then refine through the existing CPU 64x64 path. The measured
  baseline was 407.919-471.080 ms for the same stable IDs.
- **Progressive 32x32 to 64x64 replacement: ACCEPTED as part of Candidate 1.** The
  first page is a real six-face/four-hit L6 page, the old page remains routable, and
  replacement commits atomically.
- **Shared/grouped lava shadow visibility: REJECTED.** A bounded, exact-provenance,
  world-space prototype regressed GPU p95 by 0.594 ms and FPS by 1.12% even though it
  shared five READY proxy evaluations through one anchor. All group code was removed.
- **Rectangular area direct lighting: not retried.** L3 direct-light identity and its
  point/proxy equation remain unchanged.

## Run identity and experiment classification

The dedicated clean worktree was created from
`fb16969575074ac1d96d63e44fd09e712494572d` on branch
`codex/lava-l6-shadow-architecture`. The accepted-tree benchmark identity was:

| Field | Value |
|---|---|
| final measured commit | `2b3e37d36aee4ab802a3967988c3ec0216a3c565` |
| source SHA-256 | `33d5505485bfdd0e3ce2817c398a414f4b1907ee8ca9909d9fe83845ff465a92` |
| packaged artifact SHA-256 | `4a984641ab2ab080756ff8f643650b5f61364897d6014c9926a59e9e7ba27d1e` |
| worktree state for accepted runs | clean |
| renderer admission | `FULL_ADVANCED`, generation 2, `COMPLETE`, no relevant fallback |
| thermal state | nominal |

Candidate classification:

| Candidate or evidence | Classification | Disposition |
|---|---|---|
| exact-provenance `NO_LAVA_L6_VISIBILITY` | `NEW`, correcting the old RGB heuristic | retained as benchmark-only attribution |
| static GPU bootstrap using existing dynamic builder | `NEW` and `RELATED_TO_PRIOR_ACCEPTED` dynamic L6 work | accepted |
| valid 32x32 page followed by CPU 64x64 refinement | `NEW`, related to deferred progressive quality | accepted in the narrow bootstrap |
| broad fill of every missing lava page | `RELATED_TO_PRIOR_REJECTED` admission expansion | rejected and removed |
| small 6-7-block footprint GPU admission | `NEW` bounded variant | rejected and removed |
| shared receiver visibility for connected lava proxies | `NEW`, but related to prior rejected receiver-layout/lazy-branch work | benchmark prototype rejected and removed |
| rectangular area-emitter direct equation | `EXACT_RETRY` | forbidden; not implemented |
| foreground priority-only CPU scheduler retry | `EXACT_RETRY` of a measured no-win scheduling variant | not retained |

## 1. Current lava direct-light architecture

Minecraft extraction selects the effective emitting `FluidState`. Exact lava and
flowing-lava cells enter the accepted `DenseBlockLightCompactor`, which deterministically
groups identical dense sources in world-grid cells. The coarse tier is an 8x8x8 cell
with a 32-source threshold; 4x4x4 and 2x2x2 tiers handle smaller eligible sources.
Each proxy preserves centroid, conservative support, the `intensity * radius^3`
radial-energy invariant, stable identity, and its exact emitter footprint.

The prior exact census measured about 1.628 million raw lava cells, about 10.7 thousand
compacted candidates, and 581 selected/uploaded lava proxies in the Nether fixture.
Those 581 proxies remain independent L3 direct-light sources. This experiment does
not change their radiance, range attenuation, Lambertian/material/specular behavior,
cluster bounds, selection order, or 48-byte descriptor population.

## 2. Current lava L6 architecture

The accepted L6 receiver consumes one shadow reference per L3 light. `READY` and
`STALE` references address a resident cube page and use the current nearest plus
three soft taps. Every texel stores the same four ordered distance/transmittance hits.
`APPROXIMATE_DIRECT` preserves direct light with visibility 1 while no resident page
is available; `BUILDING` and fail-closed states retain their existing semantics.

In the stable Nether workload there are 581 lava proxies, 83 underlying/published
shadowed lava sources, and 83 resident lava pages. The other lava proxies remain
direct-only approximations. Resident state is world-space and independent of camera
orientation; camera priority affects which limited sources are admitted, not the
payload's spatial definition.

Static and dynamic page generation share the receiver payload but previously differed
in preparation:

| Property | Static path | Existing dynamic GPU path |
|---|---|---|
| producer | background CPU `VoxelShadowCacheBuilder` | Metal compute over L5 |
| supported edge | 8/16/32/64 | 16/32 |
| geometry source | CPU L5 mirror snapshot | resident GPU L5 clipmap |
| page payload | six faces, four ordered hits | same |
| atlas consumer | same L6 receiver | same |
| lifetime | persistent resident page | triple-buffered dynamic suffix |

## 3. True lava source provenance

`LightSourceProvenance` now carries `STATIC_BLOCK`, `LAVA_FLUID`,
`DYNAMIC_ENTITY`, or `MIXED` on the CPU side without altering photometry or the
native descriptor ABI. `MinecraftLightPolicy` assigns `LAVA_FLUID` only when the
effective fluid type is exactly `Fluids.LAVA` or `Fluids.FLOWING_LAVA`. Templates,
compaction, registry snapshots, and L6 frame lights preserve it. A compaction group
with conflicting origins becomes `MIXED` and fails closed for lava-only diagnostics.

No RGB, intensity, radius, material color, or viewport heuristic is used. This fixes
the principal defect of the historical `L3_NO_LAVA` experiment, which selected a
radiance subset rather than the real source family.

## 4. Lava L6 exact-removal bound

`NO_LAVA_L6_VISIBILITY` marks only exact `LAVA_FLUID` shadow references with a
benchmark-only descriptor state. The shader converts that state to visibility 1 in
both ordinary direct and dominant material/specular paths. All L3 work, lava proxies,
source radiance, attenuation, clusters, HDR, L5, and non-lava L6 remain unchanged.

Fresh-JVM Tier-B diagnostic results before Candidate 1:

| Nether run | Mode | FPS | GPU p50 | GPU p95 | GPU p99 |
|---|---|---:|---:|---:|---:|
| R1 | FULL | 31.307 | 33.546 ms | 34.722 ms | 35.145 ms |
| R1 | NO_LAVA_L6 | 58.799 | 18.656 ms | 19.073 ms | 19.470 ms |
| R2 | FULL | 32.570 | 32.287 ms | 33.611 ms | 33.997 ms |
| R2 | NO_LAVA_L6 | 59.174 | 18.522 ms | 19.084 ms | 19.498 ms |
| **mean** | **FULL -> NO_LAVA_L6** | **31.939 -> 58.986** | **32.916 -> 18.589 ms** | **34.166 -> 19.078 ms** | **34.571 -> 19.484 ms** |

Therefore:

`LAVA_L6_EXACT_REMOVAL_BOUND = 15.08788 ms GPU p95 (-44.16%)`.

This is a marginal, non-additive upper bound. It is not a promised recoverable saving:
removing the whole receiver branch can change compiled code, registers, cache behavior,
and control flow that a correct replacement must retain.

Static control repeated the same large effect (mean GPU p95 bound 12.83576 ms).
The exact lava-free End control had eight lights, zero lava proxies, and zero lava
pages; FULL versus selector-enabled means changed FPS by +0.95%, GPU p50 by -0.028 ms,
and GPU p95 by +0.044 ms. That is within threshold and confirms the selector is a
no-op when no lava provenance exists.

The detailed report exposed only `light upload + cluster build` as a named stage.
Entity, `WORLD_OPAQUE`, and an isolated L6 receiver stage are therefore **UNKNOWN**
for this ablation rather than inferred from whole-frame time.

After accepting Candidate 1, the same 2x2 diagnostic measured 31.601 -> 57.376 FPS
and 34.912 -> 20.095 ms GPU p95: a 14.81659 ms bound. The bootstrap therefore does
not disguise the steady receiver cost and grouping, if ever revisited, must be judged
against this accepted tree.

## 5. Gate A decision

**PASS: serious production target.** Both the original 15.088 ms and accepted-tree
14.817 ms p95 bounds greatly exceed 2 ms and 5% of whole-frame GPU time. This gate
authorizes investigation; it does not authorize reducing direct light or accepting an
uncorrect replacement.

## 6. Shadow request-to-READY latency

The complete event-only lifecycle records relevance, request, L5 eligibility, queue,
build, upload/copy, capacity/fence, descriptor commit, and first READY visibility. It
also records stable ID, true provenance, page edge, L5 level, path, priority, attempts,
cancellations, evictions, and state transitions without per-fragment counters.

Across all completed cold lava pages, two repeated pre-candidate Nether runs showed:

| Run | completed pages | p50 | p95 | p99/max |
|---|---:|---:|---:|---:|
| Gate-A R1 | 83 | 4085.6 ms | 4689.6 ms | 4817.5 / 4817.5 ms |
| Gate-A R2 | 85 | 1343.1 ms | 2077.9 ms | 2225.6 / 2225.6 ms |

These broad cold-population distributions include low-priority pages and are highly
sensitive to when the L5 mirror settles. The deterministic acceptance population is
the two coarse-dense foreground hero IDs selected in both baseline runs:

| Population | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|
| CPU baseline, four observations | 427.709 ms | 471.080 ms | 471.080 ms | 471.080 ms |
| GPU bootstrap, four observations | 6.688 ms | 6.805 ms | 6.805 ms | 6.805 ms |

Percentiles use nearest rank. Candidate 1 improves important-page p95 by 98.56%.

## 7. Latency decomposition

The four baseline hero observations were:

| READY total | attempts / cancellations | queue wait | final CPU build | build-to-upload | upload | descriptor commit | L5 / fence / other |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 407.919 ms | 21 / 39 | 289.113 ms | 115.462 ms | 3.018 ms | 0.172 ms | 0.155 ms | 0 / 0 / 0 ms |
| 458.994 ms | 17 / 30 | 404.657 ms | 45.352 ms | 8.638 ms | 0.167 ms | 0.179 ms | 0 / 0 / 0 ms |
| 427.709 ms | 22 / 40 | 335.479 ms | 71.273 ms | 20.453 ms | 0.291 ms | 0.213 ms | 0 / 0 / 0 ms |
| 471.080 ms | 17 / 28 | 406.756 ms | 52.502 ms | 11.390 ms | 0.214 ms | 0.218 ms | 0 / 0 / 0 ms |
| **mean 441.426 ms** | **19.25 / 34.25** | **359.001 ms** | **71.147 ms** | **10.875 ms** | **0.211 ms** | **0.191 ms** | **0 / 0 / 0 ms** |

`executor_queue_wait_ms` is a subspan of `queue_wait_ms`, not an additional term.
It ranged from 0.265 to 117.167 ms while maximum executor depth was only 1-3.

The candidate's four totals were 6.672, 6.688, 6.779, and 6.805 ms. Each used one
attempt and zero cancellations: about 4.43-4.45 ms to admission/encode, 1.64-1.70 ms
GPU build encode, and 0.54-0.71 ms to visible commit.

## 8. Root cause of second-scale delay

The measured root cause is repeated invalidation and replacement of CPU build tickets
while L5 mirror revisions and relevant geometry stream toward a settled snapshot.
The final build itself is tens of milliseconds, but the important source can undergo
17-22 attempts and 28-40 cancellation notifications before one ticket matches the
current mirror and frame light. Upload, fences, capacity, and descriptor publication
are not material in these traces.

Thus "queue backlog" alone is not the cause. The queue span is large because work is
repeatedly made obsolete; low executor depth proves this is not simply a long FIFO of
unrelated pages. A prior scheduler-priority-only experiment reached roughly three
submits at p50 and four at p95 without eliminating revision invalidation and produced
no useful latency change; it was not retained.

## 9. Gate B decision

**PASS: serious UX defect.** Important pages at 408-471 ms exceed the 250 ms
significance gate, and the broad cold-page p95 reaches 2.1-4.7 seconds. The delay is in
page preparation/revalidation, so an equivalent immediate build path is justified.

## 10. Static GPU-on-demand feasibility

Feasibility passed because the existing dynamic builder already consumes the same L5
geometry and writes the same resident-page byte format expected by the static receiver.
Candidate 1 extends its request ABI to version 2 (144 bytes/request) with a request kind
and an exact, bounded emitter footprint: origin plus an 8x8x8/512-bit mask. The Metal
kernel uses that mask for exact self-emitter exclusion.

Static requests reserve an address only in the static atlas prefix. Dynamic pages stay
in the disjoint triple-buffered suffix. A two-phase replacement reservation becomes
resident only after successful GPU encoding; an exception abandons it. This preserves
atlas ownership, in-flight lifetime, and READY semantics without a second format or a
new receiver path.

Admission is deliberately narrow and production-default:

- exact `LAVA_FLUID` provenance;
- static-cache source;
- exact footprint of at least 32 cells, bounded to one 8x8x8 coarse group;
- a complete current L5 level;
- at most `shadowedLocalLights` latched hero IDs per world generation (two in Balanced);
- one bootstrap per hero, with no persistent per-frame rebuild.

`METALLUM_STATIC_LAVA_GPU_BOOTSTRAP=0` is the diagnostic opt-out used for A/B.

## 11. Progressive page feasibility

**Feasible and accepted.** The dynamic builder's 32x32 output is not a fake placeholder:
it contains all six cube faces and four ordered distance/transmittance hits from actual
world-space L5 geometry. It becomes READY in the request submit. The existing CPU path
continues toward the desired 64x64 page in the background.

During refinement, the valid coarse page remains addressable (READY or retained STALE).
The higher-quality allocation is prepared separately and descriptor replacement is
atomic. Stable ID, source position, world generation, mirror revision, and emitter
footprint must still match before commit. There is no unshadowed interval and no
camera-space transition.

## 12. Candidate 1 implementation and results

The accepted implementation is commit `c50c0668e2f15dd8ca736e24cebb0447fbd3964e`.
The two Nether heroes represented exact footprints of 512 and 152 lava cells. Their
coarse pages reached READY in one submit and later used the existing 64x64 CPU
refinement. Direct descriptors and the L3 shader were unchanged.

Rejected admission variants were removed:

- A broad fill selected every missing lava page, increased residents from 83 to 157,
  regressed GPU p95 from about 33.69 to 40.61 ms, and reduced FPS from 32.47 to 26.47.
- Small fine footprints of 6-7 cells did not become L5-complete early enough to improve
  Static readiness and added enabled-path overhead.
- The final prefilter avoids empty-list allocation and sorting when no eligible source
  exists.

Contract and native validation cover ABI parity, range partitioning, non-overlap,
bounded masks, exact self-skip, reservation failure, and lifecycle telemetry.

## 13. Direct identity versus shadow identity

The accepted candidate deliberately leaves the mapping one direct proxy to one L6
page unchanged. It improves only how the first page is generated.

The separate grouping investigation tested a different mapping: every proxy remained
an independent L3 direct source, while several exact-provenance lava proxy descriptors
could refer to one already-resident visibility anchor. No radiance, intensity,
attenuation, cluster membership, material, or specular term was merged.

## 14. Lava shadow grouping feasibility

The pre-prototype maximum bound was large enough to justify a bounded experiment, but
the actual world topology was much less favorable. Exact emitter footprints were
connected only when blocks face-touched across adjacent deterministic 8x8x8 compactor
cells. The largest selected group had eight direct proxies, only five of which were
READY, out of 83 resident lava pages.

The prototype kept all pages and all build/residency work deliberately unchanged so
that its A/B isolated receiver reuse. It selected one centroid-nearest existing
world-space anchor and cached that visibility result within the fragment's light loop
for other READY members. This answered feasibility without inventing a new page format.

## 15. Maximum plausible grouping upside

Before topology census, deliberately optimistic arithmetic was:

| Model | Receiver representations versus 83 pages | Naive reduction | Optimistic share of 15.088 ms bound | Evidence |
|---|---:|---:|---:|---|
| one group anchor + two exact near proxies | 3 | 96.4% | 14.54 ms | `SPECULATIVE` |
| four anchors + two exact near proxies | 6 | 92.8% | 13.99 ms | `SPECULATIVE` |
| eight groups x four anchors + two exact | 34 | 59.0% | 8.90 ms | `SPECULATIVE` |

These values were never recoverable forecasts; they linearly scale a non-linear
whole-branch removal bound and assume physically valid grouping of nearly all pages.

The actual bounded group could replace four of 83 READY evaluations (five members to
one anchor), only 4.82% of current resident evaluations. Even an invalid linear scaling
would cap that at about 0.71 ms before anchor logic, branch/code-size cost, near-field
retention, or multiple anchors. The prototype did not reduce atlas bytes, page builds,
queue work, or residency by design, so those quantities cannot be added as savings.

## 16. Gate C decision

**Initial gate: PASS_TO_BOUNDED_PROTOTYPE. Final gate: FAIL / REJECTED.** The exact
removal bound supplied materially new evidence for a minimal prototype. Actual topology
then reduced plausible benefit below 1 ms, and the measured implementation moved in
the wrong direction:

| Shared-group Tier-B mean | OFF | ON | Delta |
|---|---:|---:|---:|
| FPS | 27.115 | 26.810 | -1.12% |
| GPU p50 | 38.457 ms | 38.872 ms | +0.415 ms |
| GPU p95 | 40.123 ms | 40.717 ms | +0.594 ms (+1.48%) |
| GPU p99 | 40.873 ms | 41.535 ms | +0.662 ms |
| 1% low | 24.037 | 23.036 | -4.16% |
| 0.1% low | 23.623 | 22.178 | -6.11% |
| CPU p95 | 11.972 ms | 12.227 ms | +0.255 ms |

The analyzer classified the pair `CONSISTENT_REGRESSION`. More importantly, merely
compiling the dormant general shared-group receiver path regressed the accepted
production source from 32.253 to 27.115 FPS and from 33.871 to 40.123 ms GPU p95.
Shader code size/register/control-flow pressure is a supported explanation consistent
with prior lazy-receiver failures; the exact compiler-counter cause remains unknown.
All group production and diagnostic code was removed, and Tier C was not run.

## 17. Group topology

The rejected prototype's topology was nevertheless conservative:

- exact `LAVA_FLUID` provenance only;
- face-connected exact emitter footprints across adjacent 8x8x8 compactor cells;
- deterministic group and anchor IDs from world topology;
- no viewport or camera membership;
- no merge across non-touching surfaces, so a wall-separated region cannot join merely
  because it is nearby;
- invalidation follows the existing world-generation/topology rebuild.

This topology found too little useful sharing in the measured fixture. It is evidence,
not retained architecture.

## 18. Visibility and anchor model

The minimum one-anchor model was used only to measure whether reuse itself could be
cheap. Its anchor was an existing group proxy nearest the group centroid, so its page
remained a real world-space L6 visibility field. It did not blur or alter direct light.

One anchor cannot generally represent partial area occlusion around corners or a
pillar. Four or more anchors would be more plausible physically, but the one-anchor
lower-cost case already regressed and the measured group covered only five READY
members. Adding samples cannot satisfy the performance gate without new compiler and
topology evidence, so no multi-anchor production model was implemented.

## 19. Near-field exact policy

No production near-field policy is accepted because grouping itself is rejected. A
future valid design would need one or more exact dominant/shadow-sensitive proxies and
stable world-space hysteresis, which further reduces the already sub-1-ms measured
upside. Camera visibility is not an acceptable grouping rule. No hard LOD or
camera-dependent transition was added.

## 20. Visual validation

Candidate 1 used deterministic screenshots of the same large lava/pillar view:

- refined 64x64 baseline SHA-256:
  `bdff85c2bf2d141f98917822370c88babd1763ab94bafbecbf8b0ebba3b73cbf`;
- held 32x32 bootstrap SHA-256:
  `b738c496782306fd6a13ed8ab67a0533155658533900087af1b749ebb137950b`.

After HUD crop and a 24-pixel Lab-luma blur, MAE was 0.00550 and RMSE 0.00817.
Inspection found no obvious new wall leak or dark edge; much of the residual is moving
lava texture. The final steady result is the unchanged refined 64x64 page, not the
coarse capture.

The rejected shared-group ON/OFF screenshots were byte-identical in the one flat-lake
view (SHA-256
`c5bffbb10d679d6ec456862bc276f8d26b81664ca6ffbbcf0e8f3047707242fc`).
That does not validate corners, partial occlusion, or topology updates.

Automated invariants prove world-space source math, exact self-skip, stable identity,
and atomic replacement. The in-app automation system could not enumerate the Java/GLFW
Minecraft window, so no human live-play signoff is claimed. The full requested visual
matrix (small pool, river, corner, separating wall, roof, disconnected surfaces,
moving camera, and topology update) is **UNKNOWN** and was not spent on a candidate
that failed its performance gate. Candidate 1 still merits a later subjective live
check, particularly during the brief coarse-to-fine transition.

## 21. Tier B

All production comparisons used fresh JVMs, Advanced, nominal thermals, the same light
population, Native HDR, Balanced, MetalFX OFF, and 600 warmup plus 900 measured frames.

| Scene / metric | Baseline mean | Candidate 1 mean | Delta |
|---|---:|---:|---:|
| Nether FPS | 32.236 | 32.254 | +0.05% |
| Nether GPU p95 | 33.880 ms | 33.871 ms | -0.009 ms |
| Nether hero READY p95 | 471.080 ms | 6.805 ms | -98.56% |
| Static FPS | 40.845 | 40.729 | -0.28%, within threshold |
| Static GPU p95 | 26.102 ms | 26.073 ms | -0.029 ms |

Static had 565 lights, 61 lava proxies, nine resident lava pages, and no eligible
coarse bootstrap. All four no-op run comparisons were within threshold. Candidate 1
therefore passed `ACCEPT_FOR_TIER_C` on latency with neutral steady-state cost.

The shared-group prototype's separate Tier-B result is the consistent regression in
section 16. It was tested only after Candidate 1 was accepted, then removed.

## 22. Tier C

Candidate 1 used 1800 warmup and 3000 measured frames, baseline x2/candidate x2:

| Nether mean | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FPS | 32.433 | 32.694 | +0.81% |
| GPU p50 | 33.522 ms | 33.268 ms | -0.254 ms |
| GPU p95 | 34.787 ms | 34.537 ms | -0.250 ms (-0.72%) |
| GPU p99 | 35.157 ms | 34.928 ms | -0.229 ms |
| 1% low | 27.953 | 28.527 | +2.05% |
| 0.1% low | 27.123 | 27.418 | +1.09% |
| CPU p95 | 11.578 ms | 11.522 ms | -0.056 ms |

The analyzer classified Nether `CONSISTENT_IMPROVEMENT` (three improved metrics, one
within threshold, zero regressions). This is best interpreted as neutral to slightly
better steady state, not a claimed FPS architecture gain.

| Static control mean | Baseline | Candidate | Delta |
|---|---:|---:|---:|
| FPS | 40.888 | 41.234 | +0.85% |
| GPU p95 | 26.633 ms | 26.333 ms | -0.301 ms |
| 1% low | 29.683 | 29.676 | -0.02% |
| 0.1% low | 28.596 | 28.814 | +0.76% |
| CPU p95 | 6.753 ms | 6.661 ms | -0.092 ms |

Static compare-multi was mixed because one baseline p95 was an outlier, while means
remained neutral/slightly favorable. Its stronger Tier-B no-op control already showed
all four comparisons within threshold. No shared-group Tier C was authorized.

## 23. Whole-frame gain

Candidate 1 is accepted for latency. Its Tier-C Nether steady-state delta is +0.262 FPS
(+0.81%) and -0.250 ms GPU p95, below the threshold for an independent FPS claim but
comfortably non-regressive. L3 direct, the number of receiver evaluations, and the
14.817 ms lava-L6 removal bound remain unchanged.

Shared grouping has no gain: its active A/B regressed GPU p95 by 0.594 ms, and the
dormant compiled path had a still larger cross-source warning. It is not retained.

## 24. Latency improvement

For the admitted important pages, READY p50/p95/max changed from
427.709/471.080/471.080 ms to 6.688/6.805/6.805 ms. The p95 improvement is 464.275 ms
or 98.56%, converting a visible half-second correction into a same-submit result.

This does not claim that every one of the 83 resident lava pages now arrives in a few
frames. Admission is intentionally two coarse heroes per world generation; the broad
cold-page distribution remains a separate lower-priority scheduling problem.

## 25. Atlas and residency improvement

Resident lava pages remain 83 -> 83, intentionally. Candidate 1 adds two one-shot
32x32 GPU writes during startup and retains normal CPU refinement; steady measured
windows recorded zero page-build completions per frame on both sides. It reduces
time-to-valid-content, not steady atlas bytes or page count.

The group prototype also retained 83 pages to isolate receiver cost. It therefore
proved no page-build, queue, memory, or residency benefit and none is claimed.

## 26. Candidate decisions

| Candidate | Final decision | Reason |
|---|---|---|
| exact lava-L6 removal diagnostic | `ACCEPTED_DIAGNOSTIC_ONLY` | exact provenance and no-op control; never production appearance |
| two-hero coarse-dense static GPU bootstrap | `ACCEPTED` | p95 471.080 -> 6.805 ms, neutral Tier B/Tier C |
| progressive 32x32 -> 64x64 replacement | `ACCEPTED` | valid first page, old-page retention, atomic matching commit |
| broad missing-page GPU fill | `REJECTED` | 83 -> 157 pages and major GPU/FPS regression |
| fine 6-7-cell GPU admission | `REJECTED` | no latency benefit and measurable enabled-path tax |
| connected shared lava visibility | `REJECTED` | actual upside <1 ms; active and dormant shader regressions |
| rectangular direct area emitter | `REJECTED` previously; `EXACT_RETRY` forbidden here | prior experiment already failed; not repeated |

## 27. Exact next recommendation

Keep Candidate 1 as the production architecture and do not broaden admission yet.
Perform one human live Nether check focused on the first 7 ms and the later atomic
32x32-to-64x64 refinement; automated evidence is strong, but subjective transition
acceptance remains outstanding.

Do not retry shared receiver caching, more anchors, rectangular direct lights, broad
GPU fill, or scheduler-only priority without materially new evidence. A future grouping
attempt first needs an actual topology census with substantially more than five READY
members per group and compiler/GPU-capture evidence that the receiver specialization
can be compiled without the dormant code-size/register tax. Direct L3 must remain
unchanged unless a separate goal proves otherwise.

## 28. Commits

| Commit | Purpose |
|---|---|
| `8b51879` | exact lava provenance, L6 ablation, lifecycle telemetry |
| `a329e9c` | deterministic lava-free End control route |
| `eadcfe6` | preserve material/specular direct light in the ablation |
| `c50c066` | accepted GPU static-lava bootstrap and progressive refinement |
| `2b3e37d` | Tier-B decision record |

The rejected shared-group prototype was never committed and was completely removed.

## 29. Raw evidence paths

Gate A, source `eadcfe6`, artifact
`663c169e1f8adaf6e97f4d0fcb94e018c175dc43ae69acc1373e7c6624735b49`:

- `run/logs/metallum-benchmarks/20260816T051704Z-geadcfe641e6b-clean-lava-l6-gatea2-nether-full-r1-off.{raw.jsonl,summary.json,minecraft.log}`
- `run/logs/metallum-benchmarks/20260816T051910Z-geadcfe641e6b-clean-lava-l6-gatea2-nether-no-r1-off.{raw.jsonl,summary.json,minecraft.log}`
- `run/logs/metallum-benchmarks/20260816T052041Z-geadcfe641e6b-clean-lava-l6-gatea2-nether-full-r2-off.{raw.jsonl,summary.json,minecraft.log}`
- `run/logs/metallum-benchmarks/20260816T052236Z-geadcfe641e6b-clean-lava-l6-gatea2-nether-no-r2-off.{raw.jsonl,summary.json,minecraft.log}`
- `run/logs/metallum-benchmarks/20260816T052416Z-geadcfe641e6b-clean-lava-l6-gatea2-static-full-r1-off.{raw.jsonl,summary.json,minecraft.log}`
- `run/logs/metallum-benchmarks/20260816T052539Z-geadcfe641e6b-clean-lava-l6-gatea2-static-no-r1-off.{raw.jsonl,summary.json,minecraft.log}`
- `run/logs/metallum-benchmarks/20260816T052643Z-geadcfe641e6b-clean-lava-l6-gatea2-static-full-r2-off.{raw.jsonl,summary.json,minecraft.log}`
- `run/logs/metallum-benchmarks/20260816T052809Z-geadcfe641e6b-clean-lava-l6-gatea2-static-no-r2-off.{raw.jsonl,summary.json,minecraft.log}`
- lava-free control: `run/logs/metallum-benchmarks/20260816T050649Z-...-gatea-end-full-r1-node-off`, `050803Z-...-no-r1`, `050856Z-...-full-r2`, `050950Z-...-no-r2`.

Candidate 1 Tier B and lifecycle:

- `run/logs/metallum-benchmarks/20260816T065317Z-gc50c0668e2f1-clean-lava-l6-prefilter-bootstrap-nether-baseline-r1-off.*`
- `run/logs/metallum-benchmarks/20260816T065520Z-gc50c0668e2f1-clean-lava-l6-prefilter-bootstrap-nether-candidate-r1-off.*`
- `run/logs/metallum-benchmarks/20260816T065712Z-gc50c0668e2f1-clean-lava-l6-prefilter-bootstrap-nether-baseline-r2-off.*`
- `run/logs/metallum-benchmarks/20260816T065909Z-gc50c0668e2f1-clean-lava-l6-prefilter-bootstrap-nether-candidate-r2-off.*`
- Static B1/C1/B2/C2: `064719Z`, `064844Z`, `065009Z`, `065135Z` with prefix
  `run/logs/metallum-benchmarks/`.

Candidate 1 Tier C, source `2b3e37d`, artifact
`4a984641ab2ab080756ff8f643650b5f61364897d6014c9926a59e9e7ba27d1e`:

- Nether B1/C1/B2/C2: `070352Z`, `070728Z`, `071103Z`, `071439Z`, prefix
  `run/logs/metallum-benchmarks/20260816T`, each with `.accepted.json`,
  `.raw.jsonl`, `.summary.json`, and `.minecraft.log`.
- Static B1/C1/B2/C2: `071821Z`, `072106Z`, `072352Z`, `072648Z`, same prefix.
- Post-acceptance Gate A: `073116Z`, `073309Z`, `073441Z`, `073649Z`, same prefix.

Visual evidence:

- `run/lighting-reference/lava-l6/20260816T074221Z-g2b3e37d36aee-dirty-lava-l6-visual-baseline-refined64-off.png`
- `run/lighting-reference/lava-l6/20260816T074347Z-g2b3e37d36aee-dirty-lava-l6-visual-bootstrap-coarse32-off.png`
- shared group ON/OFF: `run/lighting-reference/lava-l6/20260816T080801Z-...-shared-group-visual-off.png` and `20260816T080958Z-...-shared-group-visual-control-off.png`.

Rejected shared-group evidence:

- census: `run/logs/metallum-benchmarks/20260816T075657Z-g2b3e37d36aee-dirty-lava-l6-shared-group-census2-off.*`
- OFF/ON/OFF/ON: `20260816T075911Z`, `080119Z`, `080325Z`, `080530Z`, prefix
  `run/logs/metallum-benchmarks/` and suffixes `.raw.jsonl`, `.summary.json`,
  `.minecraft.log`.

## Final required summary

The READY distribution below is the deterministic important two-hero population.
The broader cold-page distribution remains 1.3-4.8 seconds and is not silently folded
into the accepted result.

| Metric | BASELINE | AFTER ACCEPTED CHANGES |
|---|---:|---:|
| Nether FPS, Tier-C mean | 32.433 | 32.694 |
| whole-frame GPU p95, Tier-C mean | 34.787 ms | 34.537 ms |
| lava L6 marginal p95 cost | 14.817 ms upper bound | 14.817 ms upper bound; receiver unchanged |
| important lava READY p50 / p95 / max | 427.709 / 471.080 / 471.080 ms | 6.688 / 6.805 / 6.805 ms |
| resident lava pages | 83 | 83 |
| page-build rate | 0/frame steady; CPU startup builds | 0/frame steady; two one-shot GPU bootstraps plus existing CPU refinement |

**PROVEN**

- Exact lava L6 visibility is a major Nether receiver cost: 14.817-15.088 ms GPU p95
  removal upper bound with unchanged direct light, proxy population, clusters, L5,
  HDR, and non-lava L6.
- Important static lava latency was dominated by repeated invalidation/cancellation,
  not upload, fence, capacity, or a deep unrelated executor FIFO.
- The accepted bootstrap reduces the same IDs from 407.919-471.080 ms to
  6.672-6.805 ms without steady-state performance or residency regression.
- The bounded shared-group implementation regressed whole-frame GPU and was removed.

**SUPPORTED**

- The dormant shared-receiver branch likely triggered code-size/register/control-flow
  pressure similar to prior lazy L6 receiver experiments.
- The 32x32 coarse page is visually close in the measured flat-lake/pillar scene and
  the unchanged 64x64 refinement remains the final representation.

**SPECULATIVE**

- A future representation outside the current per-fragment receiver loop might share
  large-surface visibility efficiently if it finds much larger physically valid groups.
- Multi-anchor area visibility could improve partial-occlusion quality, but current
  measurements provide no performance budget for it.

**UNKNOWN**

- Exact entity versus `WORLD_OPAQUE` shares of the lava-L6 removal bound.
- Compiler register, occupancy, cache, and code-size counters behind the dormant group
  regression.
- Subjective live-play acceptance of the brief coarse-to-fine transition and the full
  requested visual scene matrix.
