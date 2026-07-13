package com.metallum.client.metal.render;

/** Stable Java/native identifiers for the opt-in per-stage GPU profiler. */
public enum MetalGpuTimingStage {
    NONE(-1),
    WORLD_OPAQUE(0),
    TRANSLUCENT(1),
    ENTITIES(2),
    HDR_EXTRACT(3),
    HISTOGRAM_EXPOSURE(4),
    BLOOM_HORIZONTAL(5),
    BLOOM_VERTICAL(6),
    HDR_RECONSTRUCTION(7),
    METAL_FX(8),
    UI(9),
    PRESENT(10);

    public static final int PROFILED_STAGE_COUNT = 11;

    private final int nativeId;

    MetalGpuTimingStage(final int nativeId) {
        this.nativeId = nativeId;
    }

    int nativeId() {
        return this.nativeId;
    }
}
