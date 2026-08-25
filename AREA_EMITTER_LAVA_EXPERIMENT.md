# Static rectangular emitters for lava: gated experiment

Date: 2026-08-15

Target: Apple M1 Pro, 3024×1964, Native HDR, Balanced, MetalFX OFF

Final status: **REJECTED; no production area-emitter code retained**

The initial feasibility gate passed because a new exact-family ablation showed that
lava can dominate the downstream Nether lighting cost. A bounded horizontal-rectangle
prototype was therefore justified. The prototype then failed the production-value
gate: it did not reduce the 2,048 uploaded-light working set, reduced accepted cluster
references by only about 6.5%, and exchanged cheap point evaluations for a four-sample
area approximation. One matched screen produced no meaningful FPS gain and regressed
whole-frame GPU p95 by 0.554 ms. Tier B and Tier C were not entered.

## 1. Current DenseBlockLightCompactor behavior

`MinecraftLightPolicy` marks emitting lava fluid cells as dense-cell eligible. Magma
blocks and other ordinary block emitters are not eligible. The current compactor is
camera-independent and world-grid stable:

- dense 8³ groups with at least 32 identical cells become one proxy;
- remaining radius-at-least-9 lights use 4³ groups with a threshold of 4;
- remaining radius-at-least-3 lights use 2³ groups with a threshold of 2;
- sparse leftovers remain exact point lights;
- a proxy center is the member centroid;
- support expands by the maximum member offset;
- intensity is scaled to preserve the shader's `intensity * radius³` integrated
  radial-energy invariant;
- the exact member footprint is retained for L6 self-shadow exclusion;
- output proxies are deliberately not recursively compacted.

Contract tests establish these representative cases:

| Topology | Raw lava cells | Current proxies |
|---|---:|---:|
| flat 16×1×16 surface | 256 | 4 |
| full 16³ volume | 4,096 | 8 |

The important correction to prior project notes is that current compaction is already
very aggressive for lava, but not for magma.

## 2. Raw lava/magma source census

An opt-in exact provenance census was added only for this experiment. It classified
sources from the Minecraft registry identifier before color/intensity folding and
counted raw, compacted, uploaded, and GPU cluster-reference families. The validated
600-frame census artifact is:

`run/logs/metallum-benchmarks/20260815T082040Z-gd61b0f6b070e-dirty-area-emitter-exact-census-off.raw.jsonl`

| Family | Raw | After current compaction | Uploaded | Accepted cluster refs | Raw geometric refs |
|---|---:|---:|---:|---:|---:|
| lava | 1,627,794 | 10,709 | 581 | 311,857 | 314,078 |
| magma | 88,228 | 88,228 | 1,357 | 27,799 | 33,570 |
| other static | 7,660 | 7,660 | 109 | 9,735 | 9,776 |
| dynamic | 1 | 1 | 1 | 5,047 | 5,047 |
| **total** | **1,723,683** | **106,598** | **2,048** | **354,438** | **362,471** |

Consequences:

- lava is compressed by about 152:1 before frame admission;
- lava is only 28.4% of uploaded descriptors, but 88.0% of accepted references;
- magma is 66.3% of uploaded descriptors, but only 7.8% of accepted references;
- lava plus magma occupy 94.6% of uploaded descriptors;
- `2048 active lights` must not be read as `2048 lava lights`.

The older P0 Static Light Hybrid census independently reported 581–582 admitted lava
and 1,356–1,365 admitted magma descriptors across three full runs, consistent with the
new exact census.

## 3. Current proxy cost

The exact census measured a frozen-scene Java snapshot-build average of 5.245 ms
(15.514 ms maximum), zero steady extraction work, 2,048 uploaded descriptors, and
L6 `READY=86 / APPROXIMATE=1962` with no fail-closed or dynamic fallback state.
The census counters are intrusive, so its FPS is not a production metric.

The matched upper-bound baseline without census counters measured:

| Metric | Value |
|---|---:|
| FPS | 30.147 |
| whole-frame GPU p95 | 37.500 ms |
| cluster upload/build average | 1.450 ms |
| cluster upload/build p95 | 3.574 ms |
| 1% / 0.1% low | 25.286 / 24.736 FPS |

An exact-family GPU ablation invalidated only the selected family's cluster bounds. It
continued to perform Java extraction, compaction, descriptor selection, and upload;
the light contribution intentionally vanished. It is therefore an upper bound on
downstream recovery, not a visually equivalent candidate:

| Mode | FPS | GPU p95 | Delta from full | Cluster p95 |
|---|---:|---:|---:|---:|
| full | 30.147 | 37.500 ms | — | 3.574 ms |
| exact lava absent | 82.642 | 9.918 ms | −27.582 ms | 1.304 ms |
| exact magma absent | 30.802 | 36.943 ms | −0.557 ms | 2.985 ms |
| exact lava+magma absent | 83.049 | 8.823 ms | −28.677 ms | 1.203 ms |

Do not add these deltas: the stages overlap and the combined result is nonlinear.
Family-attributable terrain-versus-entity fragment time and L6 admission time remain
unknown. The ablation proves that exact lava references have large leverage in this
fixture; it does not prove that a correct area replacement can recover that leverage.

The historical `L3_NO_LAVA` result does not answer this question. Recovered shader
code classified already-folded `color * intensity` with an RGB threshold and had no
source-family, static, or kind check. Full-strength lava and magma fail that predicate.
Its reported 0.84 ms (1.037 ms when all valid windows are combined) was a heuristic
radiance-subset delta, not exact lava cost. The associated claims of approximately 48
lava proxies and 12,400 lava references have no supporting raw telemetry.

## 4. Maximum plausible upside

The cost model deliberately separates evidence levels and avoids adding overlapping
stage deltas.

| Cost or effect | Evidence | Maximum useful interpretation |
|---|---|---|
| exact lava downstream cluster/direct/material/L6 lookup interaction | **PROVEN measured upper bound** | 27.582 ms GPU p95 disappears when exact lava contribution disappears; no appearance equivalence |
| exact magma downstream interaction | **PROVEN measured upper bound** | 0.557 ms GPU p95; magma is descriptor-heavy but reference-light |
| Java steady extraction | **PROVEN measured** | 0 ms in the frozen measured window; no per-frame recovery available there |
| Java snapshot/selection | **PROVEN measured** | 5.245 ms average in the intrusive census, but an area topology must still select a 2,048-light frame |
| descriptor upload | **SUPPORTED bound** | 2,048 × 48 B descriptors remain 98,304 B when freed slots refill; the prototype proved zero descriptor-count recovery |
| cluster build/references | **PROVEN for prototype** | accepted refs fell 354,438 → about 331,756 (6.4%); cluster p95 fell 0.673 ms in the matched screen |
| terrain/entity local-light traversal | **SUPPORTED bound** | a one-sample version could reduce accepted evaluations by only the same roughly 6.4%; exact pass split is unknown |
| four-sample rectangle traversal | **PROVEN accounting** | 331,756 accepted refs plus three extra evaluations for 28,478 area refs = about 417,190 point-equivalent samples, 17.7% above baseline |
| hypothetical one-sample closest point | **SUPPORTED bound** | about 331,756 evaluations; extrapolating the fixed-sample A/B suggests only roughly 0.2 ms whole-frame recovery, before its visual error |
| L6 residency/admission | **PROVEN state, UNKNOWN family cost** | READY remained 86–87; no claim that L6 cost disappears |
| memory bandwidth/secondary cache effects | **SPECULATIVE** | no timestamp artifact can isolate them |

Before topology was known, the exact 27.582 ms removal upper bound made a narrow
prototype rational. After topology and accepted-reference measurements, the maximum
plausible upside of the remaining cheap one-sample variant is below the project's
roughly 1 ms complexity threshold. An analytic rectangle integral would cost more and
does not have a credible performance path in this workload.

## 5. Gate A decision

**Gate A: PASS, prototype only.**

The exact-family lava ablation exceeded both the 2 ms and 5% feasibility thresholds by
a wide margin. That was materially new evidence and justified a benchmark-only source
model despite current compaction.

Idea classification:

| Idea | Classification | Disposition |
|---|---|---|
| exact registry-family census and ablation | `NEW` evidence related to deferred Static Light Hybrid work | used to correct the old RGB heuristic |
| horizontal rectangle source | `NEW` | benchmark prototype built |
| reduce static sources before cluster upload | `RELATED_TO_DEFERRED_WORK` | tested narrowly for lava only |
| near/far Static Light Hybrid | `RELATED_TO_DEFERRED_WORK` | not implemented; no transition evidence |
| discard internal lava merely because neighbours are lava | `RELATED_TO_PRIOR_REJECTED` | not adopted as a general rule |
| old RGB-threshold `L3_NO_LAVA` experiment | `RELATED_TO_PRIOR_REJECTED` | not retried; replaced by materially new exact provenance |
| one giant point at a lake center | `RELATED_TO_PRIOR_REJECTED` unsafe source-removal logic and explicitly out of scope | forbidden and not attempted |

`EXACT_RETRY`: none. No rejected experiment was renamed and repeated.

## 6. Area-emitter representation

The opt-in benchmark prototype used a minimal horizontal, world-axis-aligned
rectangle encoded in the existing 48-byte light descriptor:

- stable world-space center;
- X/Z half extents quantized to quarter blocks in unused metadata bits;
- existing RGB/intensity;
- existing influence radius expanded by the rectangle half-diagonal for conservative
  clustering;
- exact lava source-family flag;
- stable ID derived from dimension, minimum corner, Y, width, and depth;
- area/proxy type bit;
- exact represented footprint;
- maximum 16×16 footprint per patch.

No screen-space state, camera-relative topology, arbitrary point-at-center model, or
new descriptor stride was introduced. The code was gated by
`METALLUM_AREA_EMITTER_PROTOTYPE=1` and never became a production default.

## 7. Topology/patch generation

The pure topology builder consumed exposed horizontal surface cells and performed a
deterministic, exact-coverage greedy tiling:

1. sort anchors by world `(Y, Z, X)`;
2. at the first unconsumed cell, select the largest anchored rectangle;
3. break equal-area ties by width, then depth;
4. never cross a 16×16 section boundary, including negative coordinates;
5. repeat independently per Y plane;
6. reject duplicates, nulls, overflow, and invalid patches.

Tests covered an empty set, one cell, a 16×16 lake (one patch), a 33×1 channel
(three stable section-bounded strips), an L-shaped shoreline (two rectangles),
multiple planes, negative section boundaries, forty shuffled orders, and exact
one-time coverage.

The runtime prototype was deliberately conservative. A section fell back to current
proxies unless it had at least 64 lava cells and was either hidden or had a sufficiently
regular exposed top. It retained L6 READY/STALE lava proxies and excluded their exact
cells from patches. Final Nether topology was:

Magma was not converted: it is a solid block-material family rather than a connected
liquid surface, and its exact removal upper bound was only 0.557 ms. General magma
source merging would be a different experiment with different material/occlusion
semantics.

| Item | Count |
|---|---:|
| raw lava cells | 1,627,818 |
| current lava proxies | 10,725 |
| exposed top cells accepted for rectangles | 81,706 |
| area patches in candidate pool | 1,802 |
| retained shadow-critical lava proxies | 20 |
| replaced / fallback sections | 314 / 2,297 |
| candidate static sources, all families | 105,263 |

There were no fully hidden sections in this fixture under the conservative predicate.
That is evidence against relying on interior-volume removal as the primary win.

## 8. Direct-light equation

The implemented benchmark equation was option C: four fixed representative samples,
at the quadrant centers of the rectangle. Each sample used the current Lambertian
scene-linear formula and existing quadratic range attenuation. Each carried one
quarter of represented area, so all four preserve the far-field power of one raw point
per surface cell. The positions and sample order were fixed in world space.

Alternatives were evaluated as follows:

- **A, closest point on rectangle:** one evaluation and stable edges, but concentrating
  the rectangle's power at the nearest point over-brightens the near field and gives
  incorrect angular/range response. Exact reference accounting bounds its likely
  recovery to far below the architecture threshold, so it was not implemented.
- **B, analytic diffuse rectangle irradiance/solid angle:** the best diffuse-area
  model conceptually, but it does not match the current non-inverse-square radial
  lighting contract and would add enough ALU/transcendental work to erase the small
  6.4% reference reduction. It also leaves L6 and L8 unresolved.
- **C, four fixed samples:** bounded, stable, and closest to the existing point field;
  implemented and measured, but too expensive.
- **D, exact point/area hybrid:** used only for shadow/material-critical sources; its
  duplicate representation did not create enough downstream savings.

## 9. L8/material interaction

The prototype was diffuse-only. Area descriptors were explicitly skipped by the
dominant local-light GGX/specular loop. Existing retained exact proxies remained the
only L8 local-specular sources. This avoided pretending that a rectangle center is an
exact specular source, but approximate lava patches consequently could not provide a
fully equivalent broad specular response.

That limitation is acceptable for a workload prototype, not for production. A real
area-specular model or a broader duplicate exact path would add cost and complexity
without a demonstrated whole-frame budget.

## 10. L6/shadow strategy

The prototype did not fake area shadows. It waited for the set of READY/STALE exact
lava proxy IDs to remain unchanged for 120 frames, froze that set, retained those
proxies exactly, and forced every rectangle to `APPROXIMATE`/unshadowed contribution.
All irregular or uncertain topology used the existing correct proxy path.

The candidate census preserved `READY=87 / APPROXIMATE=1961`, with zero fail-closed,
fallback, or dynamic-shadow failures. Twenty exact lava proxies were retained by the
topology build. This proves that the experiment did not simply delete the current
resident set.

It does not solve production transitions. Rebuilding the representation after chunk
loads or a different camera-priority set would require atomic reseeding or a world-
stable crossfade/hysteresis policy. No pop-free transition was demonstrated. That is
an independent production blocker.

## 11. Cluster integration

The cluster builder decoded the rectangle flag/extents and used a conservative sphere
whose radius was the original influence radius plus patch half-diagonal. A rectangle
could therefore contribute even when its center lay outside a cluster. No cluster
fallback or capacity failure occurred.

The result was not the hoped-for structural reduction:

| Metric | Current | Prototype |
|---|---:|---:|
| uploaded descriptors | 2,048 | 2,048 |
| uploaded lava | 581 | 475–477 |
| uploaded magma | 1,357 | 1,450–1,459 |
| uploaded rectangles | 0 | 116 |
| accepted refs | 354,438 | 331,756 |
| accepted rectangle refs | 0 | 28,478 |
| occupancy p50 / p95 / max | 36 / 96 / 256 | 36 / 92 / 256 |

Freed lava slots were largely refilled by unmerged magma, and conservative rectangle
bounds gave each patch broad cluster coverage. Descriptor count and 256-cap pressure
therefore remained.

## 12. Benchmark prototype

All runs used a fresh JVM, strict APFS fixture clone, real Advanced generation 2,
M1 Pro, 3024×1964@120, Native HDR, Balanced, MetalFX OFF, frozen simulation, and the
canonical `nether-lava-stress-v1` route/settings. Every cited performance run ended
`COMPLETE`, had no screenshot or FAIL marker, and dropped zero timing events.
The measured JSONL windows independently report `resolved_lighting_model=advanced`,
`resolved_render_contract=metallum`, active clustered lighting, and active settled L5.

Validation before runtime:

- Java unit suite: pass;
- actual patched Sodium/entity/end-portal shader compilation and source goldens: pass;
- forced Metal cluster shader source compilation: pass;
- native Swift build: pass;
- deterministic topology/property tests: pass;
- `git diff --check`: pass.

Prototype artifacts:

- intrusive workload census:
  `20260815T085312Z-...-area-prototype-census-off`;
- matched clean baseline:
  `20260815T085803Z-...-area-clean-baseline-1-off`;
- matched clean candidate:
  `20260815T090000Z-...-area-clean-candidate-1-off`;
- area-reference confirmation:
  `20260815T090447Z-...-area-ref-census-off`.

| Cohort | Source SHA-256 | Artifact SHA-256 | Use |
|---|---|---|---|
| exact census | `a8943b25...cd30` | `bcb2f16a...3036` | workload only |
| exact upper-bound modes | `cbd140c5...4cbb` | `5ec99ab0...fe09` | one-run non-equivalent bounds |
| first prototype census | `09a4c4a4...a7b7` | `8e4cf97f...16f1` | stable candidate topology/workload only |
| clean baseline and candidate | `813ecc5e...7852` | `d0bc3b1c...c5fb` | matched whole-frame screen |

The 300+300 area-reference run had insufficient topology warmup and is invalid for
FPS; only its later stable repeated GPU counters (`accepted_area=28478`) are used.

## 13. Visual validation

The pure topology tests prove deterministic exact surface coverage and world-space
patch stability. The shader contract proves fixed world-space samples and no
screen-space behavior. These are automated invariants, not visual acceptance.

The requested ten-scene visual matrix was not promoted to a production acceptance
exercise because the first whole-frame screen already failed and the prototype was
known to be diffuse-only with an unresolved transition contract. No claim is made
that center/edge brightness, material response, or all occluder cases are production-
equivalent. Continuing through ten visual fixtures would not rescue a performance-
focused candidate whose best remaining cheap variant is bounded below the required
gain.

This is a deliberate gated stop, not a visual pass.

## 14. Tier B

**Not reached.** A single benchmark-prototype baseline/candidate screen was enough to
reject promotion:

| 600+600 matched run | FPS | GPU p50 | GPU p95 | 1% low | 0.1% low | cluster avg / p95 | CPU submit p95 |
|---|---:|---:|---:|---:|---:|---:|---:|
| baseline | 26.815 | 38.744 ms | 42.238 ms | 22.041 | 21.470 | 1.461 / 3.697 ms | 11.825 ms |
| four-sample rectangle | 26.938 | 39.829 ms | 42.792 ms | 22.873 | 22.642 | 1.699 / 3.025 ms | 15.448 ms |
| delta | +0.46% | +1.085 ms | **+0.554 ms** | +3.77% | +5.46% | +0.238 / −0.673 ms | **+3.622 ms** |

The other required whole-workload telemetry was:

| Metric, average / p95 where applicable | Baseline | Rectangle candidate |
|---|---:|---:|
| WORLD_OPAQUE GPU | 20.869 / 22.625 ms | 21.187 / 22.686 ms |
| entities/features GPU | 0.719 / 1.574 ms | 0.406 / 1.685 ms |
| present interval p95 | 40.693 ms | 40.576 ms |
| snapshot build average | 4.992 ms | 8.531 ms |
| steady extraction/compaction average | 0 ms | 0 ms |
| active descriptors / descriptor payload | 2,048 / 98,304 B | 2,048 / 98,304 B |
| Advanced upload telemetry | 165,056 B | 165,056 B |
| instrumented CPU→shared / shared→private, 600 frames | 72,000 / 43,516,800 B | 72,000 / 43,516,800 B |
| cluster requested/accepted refs | 356,283 / 356,283 | 331,633 / 331,633 |
| occupancy p50/p95/max | 36 / 96 / 256 | 36 / 92 / 256 |
| overflow clusters / dropped refs | 72 / 8,216 | 71 / 8,971 |
| L5 | active, dirty=0, stale=0 | active, dirty=0, stale=0 |
| L6 | 87 READY / 1961 APPROXIMATE | 87 READY / 1961 APPROXIMATE |

The small FPS/lows movement is not accepted evidence against a simultaneous GPU p95
and CPU-tail regression. Cluster p95 alone improved, but whole-frame p95 did not.
The same artifacts' −6.92% accepted-reference movement and +3.539 ms Java snapshot
average reinforce that the candidate traded one kind of work for another rather than
producing a broad structural win.
The signal fails the preferred `>=1 ms` or `>=5%` production threshold, so mandatory
route baseline×2/candidate×2 was not spent on this candidate.

## 15. Tier C if reached

**Not reached.** There was no strong Tier B winner, so no 1800+3000 production matrix,
Cave/Nether/Overworld compare-multi, or release acceptance was run.

## 16. Whole-frame gain

There was no whole-frame gain. The only clean matched screen showed:

- FPS: +0.123 FPS (+0.46%), within ordinary variance;
- GPU p95: **0.554 ms slower**;
- GPU p50: **1.085 ms slower**;
- CPU submit p95: **3.622 ms slower**;
- cluster p95: 0.673 ms faster, consumed by fragment and CPU work.

The old no-prototype exact upper-bound baseline was faster than the dormant-prototype
baseline as well (37.500 versus 42.238 ms GPU p95), although those source artifacts
differ and are not a strict A/B. That is an additional warning that even an inactive
general source branch/code-size tax would need proof before retention.

## 17. DenseBlockLightCompactor comparison

What did the rectangle architecture accomplish that `DenseBlockLightCompactor` does
not already accomplish?

It identified exposed horizontal topology and could describe a perfect 16×16 lake
with one world-space patch rather than four volumetric proxies. It also offered an
area-aware diffuse distribution instead of one enlarged proxy point. Those are real
semantic differences.

They were not a useful global runtime difference in this fixture:

| Scope | Raw lava | Current compacted proxies | Rectangle candidate |
|---|---:|---:|---:|
| ideal 16×16 flat surface | 256 | 4 | 1 rectangle |
| ideal 33×1 channel | 33 | sparse current points/proxies by cell thresholds | 3 section-bounded strips |
| measured Nether candidate pool | 1,627,818 | 10,725 | 9,898 total lava sources: 1,802 rectangles plus 8,096 retained/fallback proxies |
| measured uploaded frame | selected from above | 581 lava proxies | 116 rectangles + about 361 exact/fallback lava proxies |

Thus the measured global post-compaction reduction was only about 7.7% for the lava
candidate pool and about 17.9% for uploaded lava descriptors. Total uploaded lights
did not fall because magma refilled the cap. Accepted refs fell only 6.4–6.5%.

The current compactor is already near the useful runtime optimum for lava under the
present 2,048-slot, point-light-centered architecture. A rectangle can be conceptually
more compact without reducing the real bottleneck.

## 18. ACCEPTED / REJECTED / FEASIBILITY_REJECTED

**REJECTED.**

This is not `FEASIBILITY_REJECTED`: exact-family evidence made Gate A legitimately
pass and justified one bounded benchmark prototype. It is rejected after that
prototype because:

1. total descriptors remained 2,048;
2. accepted refs fell only about 6.5%;
3. four-sample area evaluation raised effective diffuse samples by about 17.7%;
4. whole-frame GPU p95 regressed 0.554 ms;
5. CPU submit p95 regressed 3.622 ms;
6. a one-sample approximation is bounded to a sub-threshold likely gain and has worse
   near-field/angular correctness;
7. L8 area specular and pop-free L6/representation transitions remain unsolved;
8. no material visual improvement was demonstrated that could justify neutral cost.

All production renderer/shader/native candidate changes are reverted. Raw benchmark
artifacts and this decision record are preserved.

## 19. Exact next recommendation

**DO NOT RETRY this section-local lava-rectangle + clustered per-fragment evaluation
architecture without materially new evidence.** Specifically, do not retry by merely
changing four samples to one closest point or by allowing larger rectangles; the
measured descriptor refill and conservative cluster footprint remain.

The next performance investigation, if desired, should target the actual admitted
work rather than lava topology alone:

1. first measure whether magma's 1,350+ admitted descriptors create avoidable CPU
   selection/upload cost despite their small downstream reference cost;
2. separately investigate a representation that replaces static emissive contribution
   **outside the per-fragment clustered loop** (for example a rigorously bounded static
   irradiance/radiance field), with its own Gate A and visual-leak tests;
3. revisit rectangles only if a future GI/material system needs true surface emission
   for quality and can reuse the source abstraction without paying the current
   clustered-light tax.

Future emissive panels, windows, creator content, and GI emission remain plausible
long-term consumers of a surface-emitter abstraction. This experiment supplies no
current production-performance justification to build that abstraction now.
