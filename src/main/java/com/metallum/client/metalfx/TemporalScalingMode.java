package com.metallum.client.metalfx;

import java.util.Locale;

/** Explicit MetalFX Temporal presets and dynamic mode. */
public enum TemporalScalingMode {
    OFF("metallum.options.metalfx_temporal_scaling.off", 1.0f),
    TEMPORAL("metallum.options.metalfx_temporal_scaling.temporal", 1.0f),
    QUALITY("metallum.options.metalfx_temporal_scaling.quality", 0.75f),
    PERFORMANCE("metallum.options.metalfx_temporal_scaling.performance", 0.50f),
    ULTRA_PERFORMANCE("metallum.options.metalfx_temporal_scaling.ultra_performance", 0.40f);

    private final String translationKey;
    private final float linearScale;

    TemporalScalingMode(final String translationKey, final float linearScale) {
        this.translationKey = translationKey;
        this.linearScale = linearScale;
    }

    public String translationKey() {
        return this.translationKey;
    }

    public float linearScale() {
        if (this == TEMPORAL) {
            return MetallumDrsController.currentScale();
        }
        return this.linearScale;
    }

    public int nominalLinearPercent() {
        return Math.round(this.linearScale() * 100.0f);
    }

    public int nominalPixelPercent() {
        float scale = this.linearScale();
        return Math.round(scale * scale * 100.0f);
    }

    public double textureMipBias() {
        if (!this.enabled()) {
            return 0.0;
        }
        return Math.log(this.linearScale()) / Math.log(2.0) - 1.0;
    }

    public boolean enabled() {
        return this != OFF;
    }

    public boolean isDynamic() {
        return this == TEMPORAL;
    }

    public boolean isFixedPreset() {
        return this == QUALITY || this == PERFORMANCE || this == ULTRA_PERFORMANCE;
    }

    public static TemporalScalingMode parse(final String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return OFF;
        }
    }
}
