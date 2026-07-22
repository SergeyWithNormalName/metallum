package com.metallum.client.metalfx;

import java.util.Locale;

/**
 * Concrete, non-persistent scaler selections accepted by the deterministic
 * benchmark harness. Temporal names are deliberately explicit so telemetry
 * can never label a Temporal run as Spatial {@code OFF}.
 */
public enum BenchmarkScalingMode {
    OFF(SpatialScalingMode.OFF, TemporalScalingMode.OFF),
    QUALITY(SpatialScalingMode.QUALITY, TemporalScalingMode.OFF),
    PERFORMANCE(SpatialScalingMode.PERFORMANCE, TemporalScalingMode.OFF),
    ULTRA_PERFORMANCE(SpatialScalingMode.ULTRA_PERFORMANCE, TemporalScalingMode.OFF),
    TEMPORAL_QUALITY(SpatialScalingMode.OFF, TemporalScalingMode.QUALITY),
    TEMPORAL_PERFORMANCE(SpatialScalingMode.OFF, TemporalScalingMode.PERFORMANCE),
    TEMPORAL_ULTRA_PERFORMANCE(SpatialScalingMode.OFF, TemporalScalingMode.ULTRA_PERFORMANCE);

    private final SpatialScalingMode spatialMode;
    private final TemporalScalingMode temporalMode;

    BenchmarkScalingMode(
            final SpatialScalingMode spatialMode,
            final TemporalScalingMode temporalMode
    ) {
        this.spatialMode = spatialMode;
        this.temporalMode = temporalMode;
    }

    public static BenchmarkScalingMode parse(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A benchmark scaler mode is required");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public void apply() {
        // Both overrides are set together so persisted user settings never
        // leak into a measured segment. Temporal's request keeps Spatial
        // disabled in the regular renderer policy as an additional guard.
        MetalFxSpatialScaling.setBenchmarkOverride(this.spatialMode);
        MetalFxTemporalScaling.setBenchmarkOverride(this.temporalMode);
    }

    public static void clearOverrides() {
        MetalFxSpatialScaling.clearBenchmarkOverride();
        MetalFxTemporalScaling.clearBenchmarkOverride();
    }

    SpatialScalingMode spatialMode() {
        return this.spatialMode;
    }

    TemporalScalingMode temporalMode() {
        return this.temporalMode;
    }
}
