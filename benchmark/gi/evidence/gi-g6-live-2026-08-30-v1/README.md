# G6 live dynamic GI evidence

This immutable bundle is bound to runtime source digest
`10f157bef162025e874219b53a9574d572329408e31201490374359995b55092`
and artifact digest
`2dae57f64db16658d10326b3ad5ed21a95f8759d789b7e203a96dcbc64bd8bbb`.

The complete deterministic update matrix was produced with:

```bash
scripts/run_metal_benchmark.sh --gi-g6-matrix --label gi-g6-matrix-final-v18
```

It completed 1800 warm-up plus 3600 measured frames and all 22 ordered
mutations with 20 matching recovery receipts. Camera orbit kept the field
identity stable; lava, chunk/resource reload, day/night, weather, eight clipmap
scroll steps, far teleport, and Nether round-trip all recovered inside their
class SLA. The final field was exact `ready_mask=7`, scheduler work converged
with zero pending/in-flight items, accounted memory stayed unchanged at
24,637,728 bytes, and no GPU-to-CPU readback occurred.

The performance-clean live run was produced with:

```bash
scripts/run_metal_benchmark.sh --gi-live --label gi-g6-live-final
```

It completed 1800 warm-up plus 3000 measured frames at 3024x1964 HDR on an
Apple M1 Pro. The final receipt had exact `ready_mask=7`, no current build,
zero stale/rejected publications, a current terrain/carrier binding, and 178
accepted block-update samples with p95/p99 latency of 1/1 renderer submits.

The static reference was produced separately with:

```bash
scripts/run_metal_benchmark.sh --gi-live --capture-reference --label gi-g6-image-final
```

The PNG was captured at measured frame 400 after the fixture torch was applied
and before it was removed. It passed a direct full-resolution gross-artifact
review. This still image is not performance-attested and does not replace the
G7 human motion or artistic product review.

See `benchmark/gi/g6-live-evidence-v1.json` for exact hashes, receipts, metrics,
scope, and limitations.
