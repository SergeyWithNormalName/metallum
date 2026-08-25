# Dense static BLOCK-light compaction feasibility

Date: 2026-08-15
Target: Apple M1 Pro, 3024×1964, Native HDR, Balanced, MetalFX OFF
Status: **STATIC_BLOCK_COMPACTION_FEASIBILITY_REJECTED**

This was a gated source-count experiment, not an area-light experiment and not permission to remove lights. It did not implement a magma compactor because the current evidence fails Gate A before a visually equivalent point/proxy candidate is justified.

## 1. Current source-family census

The current renderer had no source family after Sodium extraction. A new opt-in benchmark-only CPU sidecar, METALLUM_DENSE_STATIC_CENSUS=1, classifies the effective source BlockState identifier before RGB folding, carries the code beside the static section candidate, and joins it to the final L3 upload order by stable ID. It neither changes the 48-byte descriptor ABI nor the L3 equation. L6 state sampling reads only the final CPU descriptor state; it adds no per-fragment counter.

The three 600 warmup + 600 measured diagnostic runs were valid Advanced sessions (resolved_lighting_model=advanced, COMPLETE, no dropped timing events). Their source-side counters are diagnostic instrumentation and are **not** FPS A/B evidence.

### Cave — hdrtest-cave-v1

| Family | Raw | After existing compaction | Selected / uploaded avg | Upload share (current / 2,048 cap) | L6 READY / APPROX avg |
|---|---:|---:|---:|---:|---:|
| lava/fluid | 1,627,793 | 10,711 | 581.007 | 28.369% / 28.369% | 82.995 / 498.012 |
| magma_block | 88,298 | 88,298 | 1,359.993 | 66.406% / 66.406% | 2.000 / 1,357.993 |
| fire | 579 | 579 | 2.000 | 0.098% / 0.098% | 0 / 2 |
| soul fire | 141 | 141 | 0 | 0% / 0% | 0 / 0 |
| glowstone | 2,500 | 2,500 | 15.000 | 0.732% / 0.732% | 0 / 15 |
| shroomlight | 4,263 | 4,263 | 89.000 | 4.346% / 4.346% | 1 / 88 |
| torch/lantern | 1 | 1 | 0 | 0% / 0% | 0 / 0 |
| other block emitter | 53 | 53 | 0 | 0% / 0% | 0 / 0 |
| held light | 0 | 0 | 0 | 0% / 0% | 0 / 0 |
| dynamic/entity | 1 | 1 | 1.000 | 0.049% / 0.049% | 1 / 0 |
| **total** | **1,723,629** | **106,547** | **2,048.000** | **100% / 100%** | **87 / 1,961** |

Aggregate cluster telemetry was 354,411 requested/accepted indices, 8,065 dropped references, occupancy p50/p95/p99/max 36/96/256/256.

### Nether — nether-lava-stress-v1

| Family | Raw | After existing compaction | Selected / uploaded avg | Upload share (current / 2,048 cap) | L6 READY / APPROX avg |
|---|---:|---:|---:|---:|---:|
| lava/fluid | 1,627,778 | 10,700 | 581.007 | 28.369% / 28.369% | 82.997 / 498.010 |
| magma_block | 87,908 | 87,908 | 1,356.993 | 66.259% / 66.259% | 2.000 / 1,354.993 |
| fire | 577 | 577 | 2.000 | 0.098% / 0.098% | 0 / 2 |
| soul fire | 141 | 141 | 0 | 0% / 0% | 0 / 0 |
| glowstone | 2,500 | 2,500 | 15.000 | 0.732% / 0.732% | 0 / 15 |
| shroomlight | 4,341 | 4,341 | 92.000 | 4.492% / 4.492% | 1 / 91 |
| torch/lantern | 1 | 1 | 0 | 0% / 0% | 0 / 0 |
| other block emitter | 53 | 53 | 0 | 0% / 0% | 0 / 0 |
| held light | 0 | 0 | 0 | 0% / 0% | 0 / 0 |
| dynamic/entity | 1 | 1 | 1.000 | 0.049% / 0.049% | 1 / 0 |
| **total** | **1,723,300** | **106,222** | **2,048.000** | **100% / 100%** | **87 / 1,961** |

Aggregate cluster telemetry was 356,202 requested/accepted indices, 8,256 dropped references, occupancy p50/p95/p99/max 36/96/256/256.

### Static control — hdrtest-static-v1

| Family | Raw | After existing compaction | Selected / uploaded avg | Upload share (current / 2,048 cap) | L6 READY / APPROX avg |
|---|---:|---:|---:|---:|---:|
| lava/fluid | 1,601 | 168 | 61.000 | 10.796% / 2.979% | 9 / 52 |
| magma_block | 654 | 654 | 105.000 | 18.584% / 5.127% | 10 / 95 |
| fire / soul fire | 0 | 0 | 0 | 0% / 0% | 0 / 0 |
| glowstone | 1 | 1 | 1.000 | 0.177% / 0.049% | 1 / 0 |
| shroomlight | 1 | 1 | 1.000 | 0.177% / 0.049% | 1 / 0 |
| torch/lantern | 16 | 16 | 12.000 | 2.124% / 0.586% | 1 / 11 |
| other block emitter | 1,303 | 1,303 | 385.000 | 68.142% / 18.799% | 16 / 369 |
| held light | 0 | 0 | 0 | 0% / 0% | 0 / 0 |
| dynamic/entity | 7 | 7 | 0 | 0% / 0% | 0 / 0 |
| **total** | **3,583** | **2,150** | **565.000** | **100% / 27.588%** | **38 / 527** |

Aggregate cluster telemetry was 37,623 requested/accepted references, with no overflow or dropped references and occupancy p50/p95/p99/max 4/12/16/127.

Per-family requested/accepted references and occupancy are deliberately **UNKNOWN** in this fresh sidecar. L3 owns only upload indices and exposes aggregate GPU counters; recovering family shares would require an additional asynchronous membership-buffer readback. That instrumentation is not justified once the stronger Gate A bound below fails. The previous exact Nether family-reference census remains supporting evidence only: lava 311,857, magma 27,799, other static 9,735, dynamic 5,047, total 354,438 accepted references.

## 2. Lava vs magma distinction

MinecraftLightPolicy chooses the effective emitting FluidState before assigning a source family. Emissive fluid cells are denseCellEligible; solid minecraft:magma_block is an ordinary BLOCK light. Classification is identifier/provenance based, never a color heuristic.

Current Nether facts are therefore unambiguous:

- lava is already reduced from about 1.628 million raw cells to about 10.7 thousand ordinary point proxies before admission;
- magma is not reduced at all, and consumes 1,356.993 of 2,048 uploaded descriptors (66.26%);
- magma contributes only a small share of the source count that actually reaches L3 clusters, as verified by the prior exact family-reference census.

## 3. Existing DenseBlockLightCompactor behavior

The accepted compactor only admits BLOCK && denseCellEligible sources. It groups identical dense sources in deterministic world-grid cells: 8³ at a 32-source threshold, then 4³ for radius at least 9 and 2³ for radius at least 3, with sparse fallback exact. It preserves centroid, support radius, the intensity × radius³ radial-energy invariant, stable proxy identity, and the complete ShadowEmitterFootprint. It never recursively compacts its own output.

That explains both the roughly 152:1 Nether lava reduction and why simply turning magma denseCellEligible on is invalid: the existing GroupKey is photometric and topological, not a magma-family key, so it could merge distinct same-profile solid emitters without a new conservative topology contract.

## 4. Current magma/static proxy pressure

Magma is descriptor-heavy but not cluster-heavy:

| Current Nether property | Value |
|---|---:|
| magma raw / compacted sources | 87,908 / 87,908 |
| magma selected descriptors | 1,356.993 / 2,048 (66.26%) |
| exact historic magma accepted refs | 27,799 / 354,438 (7.84%) |
| magma L6 READY / APPROXIMATE | 2 / 1,354.993 |
| static-control magma descriptors | 105 / 565 (18.58%) |

The exact source-family ablation on the identical renderer source (d61b0f6; e40a588 adds documentation only) removed selected magma contribution without claiming visual equivalence. Its full versus magma-absent whole-frame GPU p95 was 37.500 → 36.943 ms: **0.557 ms**. That is a hard downstream upper bound for any correct magma topology replacement in this workload; a replacement cannot recover more than removing every magma contribution outright.

## 5. Maximum plausible whole-frame upside

| Cost or effect | Bound | Evidence | Conservative interpretation |
|---|---:|---|---|
| magma L3/downstream contribution | ≤0.557 ms GPU p95 | **PROVEN** exact-family removal | stronger than a proxy-compaction estimate, but visually invalid upper bound |
| magma reference share | 7.84% | **PROVEN** exact prior family census | at current 1.218/3.716 ms cluster avg/p95, a linear cluster-only ceiling is roughly 0.10/0.29 ms; not added to the 0.557 ms bound |
| descriptor bytes | 98,304 B per full 2,048-light frame | **PROVEN** | no recovery while a quality-preserving top-K refills freed magma slots from the >10,000 lava proxy pool |
| snapshot/selection | 5.574 ms average in diagnostic Nether | **PROVEN** | potentially source-count-sensitive CPU work, but CPU p95 is only 12.549 ms while GPU p95 is 35.993 ms; it cannot produce a GPU-bound whole-frame FPS win here |
| L6 admission/residency | unknown family cost | **UNKNOWN** | 2 magma are READY and 1,355 are APPROXIMATE; no L6 saving is claimed because a proxy changes cache identity and exact descriptor coverage |
| terrain/entity L3 receiver marginal cost | unknown split | **UNKNOWN** | no trustworthy family-attributed terrain/entity stage was exposed; the exact removal bound remains the only safe total bound |

No rows are summed: cluster work is contained in the removal bound, and L6/CPU cannot be double-counted. The bound is below both 1.5–2 ms and 5% whole-frame recovery.

## 6. Gate A decision

**FAIL — STATIC_BLOCK_COMPACTION_FEASIBILITY_REJECTED.**

The new current census confirms magma's descriptor pressure, but the same renderer's exact magma-removal upper bound proves that descriptor pressure does not translate to enough player-relevant GPU pressure. A new point/proxy compactor would add topology, energy, footprint, and L6-cache correctness risk for a maximum plausible GPU gain below 0.557 ms; it does not satisfy the gate.


## 7. Candidate architecture

No production or benchmark-only magma compactor was implemented. The only code change is the gated provenance census needed to make this decision.

Had Gate A passed, the sole allowed first candidate would have been a magma-only, chunk/section-local extension of the current ordinary point/proxy model—never an area emitter, shader rewrite, cap reduction, or light disappearance rule. It was not entered.

## 8. Topology rules

No new topology rule ran. A hypothetical candidate would have required a pure minecraft:magma_block family key, bounded deterministic section-local dense patches, and exact fallback for sparse/irregular cases. Flipping the existing dense flag is explicitly rejected before implementation because its family-blind key is unsafe.

## 9. Energy and footprint preservation

No source was merged. Existing lava preservation remains unchanged: centroid, conservative support, integrated radial energy, stable ID, deterministic order, and full shadow-emitter footprint. No bright-center proxy, radius expansion, or dropped edge was introduced for magma.

## 10. L6 / shadow policy

No L6 behavior changed. L6 requires descriptor coverage matching the L3 snapshot; its static cache key includes the light identity, center, radius, and footprint. A merged magma proxy therefore cannot silently replace a READY source or retain old L6-only originals. The fresh census confirms that almost all admitted magma is APPROXIMATE, but that is camera/cache state and is not a valid topology gate.

## 11. Visual validation

No visual candidate exists, so no visual acceptance is claimed. The required magma floor/wall/irregular/near-field/occluder/boundary fixtures were not run: visual work cannot redeem a candidate that fails the performance feasibility bound.

## 12. Tier B results

No Tier B baseline-versus-candidate A/B was run. The following are diagnostic census runs only (600+600, detail timing enabled):

| Fixture | FPS* | GPU p95* | CPU p95* | light upload + cluster avg/p95 | refs |
|---|---:|---:|---:|---:|---:|
| Cave | 30.818 | 35.820 ms | 12.429 ms | 1.316 / 3.706 ms | 354,411 |
| Nether | 30.641 | 35.993 ms | 12.549 ms | 1.218 / 3.716 ms | 356,202 |
| Static control | 38.290 | 28.659 ms | 4.660 ms | 0.517 / 1.276 ms | 37,623 |

*The source-side diagnostic switch and detailed timing make these unsuitable for production FPS comparison. They establish cost shape and admission, not a win.

Current stage availability is intentionally bounded: LIGHT_UPLOAD_CLUSTER_BUILD is measured above, but entity L3 traversal and World Opaque contain mixed receiver work and have no trustworthy family-marginal split. They are therefore **UNKNOWN**, not assigned to magma. Likewise, no source-count-specific L6 admission/update time is exposed; the 2 READY / 1,355 APPROXIMATE magma state is reported for correctness only, not counted as recoverable cost.

## 13. Tier C

Not reached. Gate A failed, so Tier C would be an unjustified benchmark repeat.

## 14. Source / descriptor / reference reduction

There is no candidate reduction. Existing lava compaction remains the only reduction: Nether lava 1,627,778 → 10,700 before selection and 581.007 uploaded. Magma is 87,908 → 87,908 → 1,356.993; this fact is recorded, not celebrated as a win. Total descriptors remain 2,048 and no light cap was changed.

## 15. Whole-frame FPS / GPU gain

**None.** No candidate was run; no FPS or GPU gain is claimed. The proven 0.557 ms magma-removal ceiling is insufficient to authorize a quality-risking source merger.

## 16. CPU impact

No candidate CPU result exists. The opt-in census confirms zero steady extraction work and 5.574 ms average snapshot build in Nether; its provenance aggregation is outside the timed snapshot span. Current registry telemetry does not split steady-state existing compaction, selection/admission, and upload preparation from that snapshot span, so they remain **UNKNOWN** rather than being estimated. A future CPU-only optimization must demonstrate a whole-frame benefit on a CPU-bound workload before it can be accepted as an FPS feature.

## 17. Comparison to rejected area emitters

This is not an area-emitter retry. The rejected lava rectangle work used four representative samples, retained 2,048 descriptors because freed slots refilled, and regressed whole-frame GPU p95 by 0.554 ms despite reducing cluster references. It correctly excluded magma because magma's exact removal upper bound was already only 0.557 ms. This feasibility decision extends that lesson: a cheap ordinary-proxy magma merger still lacks enough measurable downstream leverage to justify implementation.

## 18. Final decision

**STATIC_BLOCK_COMPACTION_FEASIBILITY_REJECTED**

Classifications:

| Item | Classification | Disposition |
|---|---|---|
| source-family CPU provenance census | NEW | retained as a benchmark-only diagnostic switch |
| magma-only ordinary point/proxy topology compactor | NEW, with RELATED_TO_PRIOR_ACCEPTED DenseBlockLightCompactor lessons | not implemented; Gate A failed |
| static near/far hybrid | RELATED_TO_DEFERRED | not revived |
| lava area rectangles / four samples | RELATED_TO_PRIOR_REJECTED | forbidden; not retried |
| old Static Light Hybrid prototype | RELATED_TO_DEFERRED | not implemented |
| repeat exact magma-removal ablation | EXACT_RETRY | forbidden; source parity and fresh census made repetition unnecessary |
| source disappearance / interior dropping | RELATED_TO_PRIOR_REJECTED | not implemented |

## 19. Exact next recommendation

Close magma/ordinary-static proxy merging for this workload. Do not launch a second tile-size or near-field variation: two alternatives are not needed when the maximum plausible whole-frame GPU upside is already below the gate.

If future work revisits static emission, document—not implement—a static emissive irradiance representation that could later unify with a voxel/static GI field. It would have to remove static emission from per-fragment source traversal rather than repackage it into another proxy list. That is a future GI architecture question, not this goal.

## 20. Raw artifact paths

- run/logs/metallum-benchmarks/20260815T105630Z-ge40a588bf9cb-dirty-dense-static-census-cave-off.raw.jsonl
- run/logs/metallum-benchmarks/20260815T105630Z-ge40a588bf9cb-dirty-dense-static-census-cave-off.summary.json
- run/logs/metallum-benchmarks/20260815T105847Z-ge40a588bf9cb-dirty-dense-static-census-nether-off.raw.jsonl
- run/logs/metallum-benchmarks/20260815T105847Z-ge40a588bf9cb-dirty-dense-static-census-nether-off.summary.json
- run/logs/metallum-benchmarks/20260815T110041Z-ge40a588bf9cb-dirty-dense-static-census-static-off.raw.jsonl
- run/logs/metallum-benchmarks/20260815T110041Z-ge40a588bf9cb-dirty-dense-static-census-static-off.summary.json
- Historical exact family-ref / ablation artifact recorded by AREA_EMITTER_LAVA_EXPERIMENT.md:
  run/logs/metallum-benchmarks/20260815T082040Z-gd61b0f6b070e-dirty-area-emitter-exact-census-off.raw.jsonl

Run identity:

- clean pre-instrumentation tree: branch codex/dense-static-block-compaction at e40a588bf9cbbc29ce731cd04a9f814e31a51e1b; that commit differs from the exact-ablation source commit d61b0f6 only by the rejected area-experiment documentation and OptimizationHistory entry;
- census runs: same e40a588bf9cb commit, dirty only for this opt-in diagnostic plus report/history; source SHA-256 e3da893314b33352986a81810b15ab4857e85d0dec72a1373183cce00bf822c1; packaged artifact SHA-256 d2521271ec05daba7b547fe88724117e5ef1c459dfe2ff9f1ca45777242856db;
- all three census summaries identify Apple M1 Pro, 3024×1964, Native HDR, Balanced, MetalFX OFF, nominal thermal state, and a COMPLETE Advanced session.
- agent-router/job state and local Minecraft processes were checked before the window; Cave, Nether, and static-control runs were executed serially with no concurrent Minecraft benchmark.
