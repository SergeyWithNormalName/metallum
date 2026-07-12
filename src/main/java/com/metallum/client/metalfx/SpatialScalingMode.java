package com.metallum.client.metalfx;

import java.util.Locale;

public enum SpatialScalingMode {
    OFF(1.0f, "metallum.options.metalfx_spatial_scaling.off"),
    QUALITY(0.75f, "metallum.options.metalfx_spatial_scaling.quality"),
    PERFORMANCE(0.50f, "metallum.options.metalfx_spatial_scaling.performance");

    private final float linearScale;
    private final String translationKey;

    SpatialScalingMode(final float linearScale, final String translationKey) {
        this.linearScale = linearScale;
        this.translationKey = translationKey;
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

    public String translationKey() {
        return this.translationKey;
    }

    public boolean enabled() {
        return this != OFF;
    }

    public static SpatialScalingMode parse(final String value) {
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
