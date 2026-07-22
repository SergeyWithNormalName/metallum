package com.metallum.client.renderer;

/** Immutable optional-renderer feature selection for one generation. */
public record RendererFeatureMask(long bits) {
    public static final long SPATIAL_UPSCALING = 1L << 0;
    public static final long TEMPORAL_UPSCALING = 1L << 1;
    public static final long FRAME_INTERPOLATION = 1L << 2;
    /** Requests the full Apple MetalFX Temporal route for this generation. */
    public static final long TEMPORAL_FORCE_APPLE_METALFX = 1L << 3;
    /** Requests Metallum's bounded resolver when that native route is eligible. */
    public static final long TEMPORAL_FORCE_METALLUM_OPTIMIZED = 1L << 4;
    public static final long VALID_BITS = SPATIAL_UPSCALING
            | TEMPORAL_UPSCALING
            | FRAME_INTERPOLATION
            | TEMPORAL_FORCE_APPLE_METALFX
            | TEMPORAL_FORCE_METALLUM_OPTIMIZED;

    public static final RendererFeatureMask NONE = new RendererFeatureMask(0L);

    public RendererFeatureMask {
        if ((bits & ~VALID_BITS) != 0L) {
            throw new IllegalArgumentException("Renderer feature mask has unknown bits");
        }
        if ((bits & SPATIAL_UPSCALING) != 0L && (bits & TEMPORAL_UPSCALING) != 0L) {
            throw new IllegalArgumentException("Spatial and temporal upscalers are mutually exclusive");
        }
        long temporalAlgorithmBits = TEMPORAL_FORCE_APPLE_METALFX
                | TEMPORAL_FORCE_METALLUM_OPTIMIZED;
        if ((bits & temporalAlgorithmBits) != 0L && (bits & TEMPORAL_UPSCALING) == 0L) {
            throw new IllegalArgumentException("Temporal algorithm policy requires Temporal upscaling");
        }
        if ((bits & temporalAlgorithmBits) == temporalAlgorithmBits) {
            throw new IllegalArgumentException("Temporal algorithm policy is ambiguous");
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

    /** Removes Temporal and its optional algorithm modifier as one fail-closed unit. */
    public RendererFeatureMask withoutTemporalUpscaling() {
        return new RendererFeatureMask(this.bits & ~(TEMPORAL_UPSCALING
                | TEMPORAL_FORCE_APPLE_METALFX
                | TEMPORAL_FORCE_METALLUM_OPTIMIZED));
    }
}
