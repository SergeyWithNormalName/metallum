# Documentation map

The source code is authoritative for runtime behaviour. This directory records
stable contracts and the workflow needed to verify them; it is not an archive
of one-off investigations.

| Need | Canonical document |
| --- | --- |
| Architecture, thread confinement, ABI | [architecture.md](architecture.md) |
| Native Metal resources and presentation | [metal-renderer.md](metal-renderer.md) |
| Lighting and local shadows | [lighting.md](lighting.md), [shadows.md](shadows.md) |
| Resource lifetime | [memory.md](memory.md) |
| Benchmarks and evidence rules | [BENCHMARKING.md](BENCHMARKING.md) |
| Temporal scaling and DRS | [TEMPORAL_UPSCALING_DRS.md](TEMPORAL_UPSCALING_DRS.md) |
| Frame-interpolation cadence | [promotion-frame-scheduler.md](promotion-frame-scheduler.md) |
| Current technical priorities | [../TECH_DEBT.md](../TECH_DEBT.md) |
| Accepted and rejected performance work | [../OptimizationHistory.md](../OptimizationHistory.md) |
| Product direction and acceptance boundaries | [ROADMAP.md](ROADMAP.md), [../FUTURE_RENDERING.md](../FUTURE_RENDERING.md) |

Rules for maintaining this set:

- Put a repeatable operating contract here, not a per-run report or a task
  prompt.
- Put open, actionable risks in `TECH_DEBT.md`.
- Put a performance decision, its date, method, effect and disposition in
  `OptimizationHistory.md`; old entries remain as history.
- Delete superseded reports after their durable conclusion is recorded in one
  of those documents. Do not keep two documents claiming to be canonical.
