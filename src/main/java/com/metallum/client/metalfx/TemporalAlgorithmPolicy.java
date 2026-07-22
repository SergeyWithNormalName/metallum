package com.metallum.client.metalfx;

import com.metallum.client.renderer.RendererFeatureMask;

import java.util.Locale;

/**
 * Selects the Temporal reconstruction implementation independently from the
 * render-resolution preset. The selection is encoded into the immutable
 * renderer feature mask, so native work never reads mutable UI state.
 */
public enum TemporalAlgorithmPolicy {
    AUTO("metallum.options.metalfx_temporal_algorithm.auto", 0L),
    APPLE_METALFX(
            "metallum.options.metalfx_temporal_algorithm.apple_metalfx",
            RendererFeatureMask.TEMPORAL_FORCE_APPLE_METALFX
    ),
    METALLUM_OPTIMIZED(
            "metallum.options.metalfx_temporal_algorithm.metallum_optimized",
            RendererFeatureMask.TEMPORAL_FORCE_METALLUM_OPTIMIZED
    );

    private final String translationKey;
    private final long featureBit;

    TemporalAlgorithmPolicy(final String translationKey, final long featureBit) {
        this.translationKey = translationKey;
        this.featureBit = featureBit;
    }

    public String translationKey() {
        return this.translationKey;
    }

    public long featureBit() {
        return this.featureBit;
    }

    public static TemporalAlgorithmPolicy parse(final String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AUTO;
        }
    }
}
