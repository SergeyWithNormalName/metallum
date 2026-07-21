package com.metallum.client.metalfx;

import java.util.Locale;

/** Explicit MetalFX Temporal presets. The lowest preset is intentionally opt-in. */
public enum TemporalScalingMode {
    OFF("metallum.options.metalfx_temporal_scaling.off", 1.0f),
    QUALITY("metallum.options.metalfx_temporal_scaling.quality", 2.0f / 3.0f),
    PERFORMANCE("metallum.options.metalfx_temporal_scaling.performance", 0.50f),
    ULTRA_PERFORMANCE("metallum.options.metalfx_temporal_scaling.ultra_performance", 1.0f / 3.0f);

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
        return this.linearScale;
    }

    public int nominalLinearPercent() {
        return Math.round(this.linearScale * 100.0f);
    }

    public int nominalPixelPercent() {
        return Math.round(this.linearScale * this.linearScale * 100.0f);
    }

    /**
     * Biases texture sampling back toward the mip detail appropriate for the
     * display-resolution image reconstructed by MetalFX.
     *
     * <p>The render target is smaller than the displayed image, so its raw
     * derivatives select a coarser mip than the reconstructed output needs.
     * The additional {@code -1} follows MetalFX's temporal integration
     * guidance and is intentionally unavailable while Temporal is off.</p>
     */
    public double textureMipBias() {
        if (!this.enabled()) {
            return 0.0;
        }
        return Math.log(this.linearScale) / Math.log(2.0) - 1.0;
    }

    public boolean enabled() {
        return this != OFF;
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
