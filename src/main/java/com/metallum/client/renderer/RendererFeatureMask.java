package com.metallum.client.renderer;

/** Immutable optional-renderer feature selection for one generation. */
public record RendererFeatureMask(long bits) {
    public static final long SPATIAL_UPSCALING = 1L << 0;
    public static final long TEMPORAL_UPSCALING = 1L << 1;
    public static final long FRAME_INTERPOLATION = 1L << 2;
    public static final long VALID_BITS = SPATIAL_UPSCALING
            | TEMPORAL_UPSCALING
            | FRAME_INTERPOLATION;

    public static final RendererFeatureMask NONE = new RendererFeatureMask(0L);

    public RendererFeatureMask {
        if ((bits & ~VALID_BITS) != 0L) {
            throw new IllegalArgumentException("Renderer feature mask has unknown bits");
        }
        if ((bits & SPATIAL_UPSCALING) != 0L && (bits & TEMPORAL_UPSCALING) != 0L) {
            throw new IllegalArgumentException("Spatial and temporal upscalers are mutually exclusive");
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
}
