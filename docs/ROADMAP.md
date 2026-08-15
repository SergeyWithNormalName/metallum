# Rendering roadmap and acceptance boundaries

This is a compact status map, not a substitute for the source code or the
experiment diary.

## Delivered foundation

- Metal backend, versioned Java–Swift ABI validation, bounded in-flight
  resources and deferred GPU destruction.
- Scene HDR/EDR with separate SDR UI, clustered direct lighting, solar shadows,
  voxel occupancy/local shadows, materials and water optics.
- MetalFX Spatial and Temporal scaling. Dynamic Temporal is a two-state
  Native-100% / Temporal-50% policy; see
  [TEMPORAL_UPSCALING_DRS.md](TEMPORAL_UPSCALING_DRS.md).
- Optional MetalFX Frame Interpolation with a fail-open real-frame path and
  runtime display cadence policy; it remains Experimental until live
  acceptance is complete.
- Reproducible console-driven benchmark routes and structured GPU timing;
  [BENCHMARKING.md](BENCHMARKING.md) defines what counts as evidence.

## Open gates

- Temporal entity motion has no live Metal draw-buffer capture. Static-camera
  reprojection is usable, but a full moving-entity quality claim is not.
- Frame Interpolation still needs the live visual, cadence and latency matrix
  on the built-in display before losing its Experimental label.
- Lock contention, unsafe native pointer handling and renderer fallback work
  are tracked in [TECH_DEBT.md](../TECH_DEBT.md), not in separate plans.

## Working rules

1. Keep Java, Swift and MSL ABI changes synchronized and validate their exact
   layout.
2. Keep GPU creation, commits and presentation on the render thread; retire
   resources only after in-flight work completes.
3. Treat image quality, fallback-free admission and telemetry as prerequisites
   to interpreting FPS. A fast fallback is not an optimization.
4. Make one performance hypothesis at a time. Record an accept/reject decision
   and its evidence in [OptimizationHistory.md](../OptimizationHistory.md).
5. Metal 4, Hi-Z/ICB, cached-shadow changes and other optional execution work
   need an independent measured reason; they do not block the established
   Metal 3 path.
