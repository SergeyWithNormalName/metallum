# Lighting

Metallum keeps display/HDR policy separate from the Advanced lighting model.
The active renderer generation and telemetry identify which model was actually
admitted; benchmark results are invalid if an intended Advanced route silently
falls back.

## Data flow

1. Sodium chunk work extracts static emissive blocks into
   `AdvancedLightRegistry` candidates.
2. Bounded dynamic collectors add supported moving/held sources.
3. The render thread snapshots a bounded light set, uploads it and builds
   camera-frustum clusters on the GPU.
4. Advanced material shaders evaluate only the lights listed for a fragment's
   cluster, then combine that direct contribution with the existing world
   lighting contract.

The current hard limits live in `AdvancedLightRegistry`: 8,192 resident
sections, 512 dynamic lights and 4,096 frame lights. The shader-side cluster
cap is 256 lights. These caps are workload bounds, not quality settings to
change casually.

## Working rules

- Preserve stable IDs and the current publication order when touching light,
  cluster or L6 descriptor data.
- A light-only update must not trigger terrain geometry remeshing unless the
  geometry, material or visibility really changed.
- Interpret `cluster_index_capacity_drops` as reported saturation. It is valid
  benchmark evidence, not a reason to erase or ignore a run.
- Use [OptimizationHistory.md](../OptimizationHistory.md) before revisiting a
  lighting performance idea; accepted and rejected results are recorded there.
