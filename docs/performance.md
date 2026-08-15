# Performance and profiling

[BENCHMARKING.md](BENCHMARKING.md) is the complete operating contract for
performance work. This page exists only to make the division of evidence clear:

- `METALLUM_GPU_TIMING=1` records running-game GPU timing. Read GPU p50/p95/p99,
  present pacing and 1%/0.1% lows together; average FPS alone is insufficient.
- `METALLUM_GPU_TIMING_DETAIL=1` adds stage boundaries for diagnosis. It can
  attribute a candidate cost, but its FPS must never be compared numerically
  with an uninstrumented production run.
- A valid optimization preserves image quality and the intended admitted
  renderer. A fallback or a quality reduction is not a win.
- Record each accepted or rejected hypothesis in
  [OptimizationHistory.md](../OptimizationHistory.md). The history prevents a
  failed experiment from returning as a new proposal.
