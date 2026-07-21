# Nether Lava Stress Performance Analysis

This document records the reproducible Nether lava-stress route and the diagnostic data captured with it. It is deliberately **not** a performance-acceptance report.

> [!CAUTION]
> The committed `benchmark/nether_lava_stress_results.json` has `fps: 0.0` and `gpu_p95: 0.0` for every configuration; `static_off` also records zero measured frames. Therefore it does not prove any frame-rate or GPU-frame-time result. Do not use it to compare lighting presets until the telemetry capture is rerun and validated.

---

## 1. What the Route Establishes

The route targets a large Nether lava lake at `[0.0, 32.0, 0.0]`, pitch `15.0`, on an Apple M1 Pro. It supplies eight deterministic configuration labels: static and rotating camera variants of OFF, PERFORMANCE, BALANCED, and ULTRA lighting presets.

The route, settings, benchmark controller, server-tick coordination, and report parser are useful infrastructure: they make the scene, warm-up, camera behavior, and preset matrix repeatable. The JSON also captures useful light-registry state for the active lighting presets:

- about 1.72 million raw candidate lights are compacted to about 106 thousand candidates;
- uploaded-light budgets are 1,024, 2,048, and 4,096 for Performance, Balanced, and Ultra;
- cluster occupancy reaches its hard limit of 256 and reports overflow in the dense static view.

Those observations identify a real stress case, but are not a substitute for valid whole-frame timing.

## 2. Captured Diagnostic Fields

| Scenario | FPS | GPU p95 (ms) | Uploaded lights | Cluster occupancy p95 | Cluster overflow |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `static_off` | unavailable | unavailable | 0 | 0 | 0 |
| `static_performance` | unavailable | unavailable | 1,024 | 256 | 150 |
| `static_balanced` | unavailable | unavailable | 2,048 | 256 | 228 |
| `static_ultra` | unavailable | unavailable | 4,096 | 256 | 372 |
| `rotate_off` | unavailable | unavailable | 0 | 0 | 0 |
| `rotate_performance` | unavailable | unavailable | 1,024 | 256 | 7 |
| `rotate_balanced` | unavailable | unavailable | 2,048 | 256 | 96 |
| `rotate_ultra` | unavailable | unavailable | 4,096 | 256 | 212 |

`dynamic local shadow` stage time is zero in the captured artifact. That is expected for a frozen scene containing only static lava emitters; it says nothing about L6 dynamic-shadow cost.

## 3. Required Measurement Before a Performance Decision

1. Run every matrix entry with the built-in Metal timestamp profiler enabled and capture the JSONL report.
2. Reject a run if any measured configuration has zero frames, zero FPS, or zero GPU p95 without an explicit unavailable marker.
3. Keep the warm-up and measurement-frame counts fixed, then publish median and tail whole-frame timing alongside the cluster telemetry.
4. Test static and rotating views independently; rotating the camera changes the visible lava density and is not a replacement for the static worst case.

## 4. Engineering Implication

The only supported conclusion from the current artifact is that dense lava is capable of saturating the 256-lights-per-cluster cap. A future optimization should be benchmarked independently, with particular attention to source merging or distance/importance-aware admission before GPU upload. Raising the cap alone is not justified without valid tail-time evidence.
