package com.metallum.client.renderer;

/** Immutable optional-renderer feature selection for one generation. */
public record RendererFeatureMask(long bits) {
    public static final long SPATIAL_UPSCALING = 1L << 0;
    public static final long TEMPORAL_UPSCALING = 1L << 1;
    public static final long FRAME_INTERPOLATION = 1L << 2;
    /**
     * Retains Dynamic-Temporal GPU resources while the policy temporarily
     * renders at native resolution. This is a lifecycle hint, not an active
     * upscaler: no Temporal or Spatial resolve is encoded for this bit.
     */
    public static final long TEMPORAL_WARM_STANDBY = 1L << 3;
    public static final long VALID_BITS = SPATIAL_UPSCALING
            | TEMPORAL_UPSCALING
            | FRAME_INTERPOLATION
            | TEMPORAL_WARM_STANDBY;

    public static final RendererFeatureMask NONE = new RendererFeatureMask(0L);

    public RendererFeatureMask {
        if ((bits & ~VALID_BITS) != 0L) {
            throw new IllegalArgumentException("Renderer feature mask has unknown bits");
        }
        if ((bits & SPATIAL_UPSCALING) != 0L && (bits & TEMPORAL_UPSCALING) != 0L) {
            throw new IllegalArgumentException("Spatial and temporal upscalers are mutually exclusive");
        }
        if ((bits & TEMPORAL_WARM_STANDBY) != 0L
                && (bits & (SPATIAL_UPSCALING | TEMPORAL_UPSCALING)) != 0L) {
            throw new IllegalArgumentException("Temporal warm standby cannot enable an upscaler");
        }
    }

    public static RendererFeatureMask of(final long... features) {
        long bits = 0L;
        for (long feature : features) {
            bits |= feature;
        }
        return new RendererFeatureMask(bits);
    }

    public boolean contains(final long feature) {
        if ((feature & ~VALID_BITS) != 0L || Long.bitCount(feature) != 1) {
            throw new IllegalArgumentException("Expected one known renderer feature bit");
        }
        return (this.bits & feature) != 0L;
    }

    public RendererFeatureMask without(final long feature) {
        return new RendererFeatureMask(this.bits & ~feature);
    }

    public RendererFeatureMask withoutTemporalUpscaling() {
        return new RendererFeatureMask(this.bits & ~TEMPORAL_UPSCALING);
    }

    public RendererFeatureMask withoutTemporalWarmStandby() {
        return new RendererFeatureMask(this.bits & ~TEMPORAL_WARM_STANDBY);
    }
}
