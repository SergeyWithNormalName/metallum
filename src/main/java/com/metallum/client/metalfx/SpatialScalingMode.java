package com.metallum.client.metalfx;

import java.util.Locale;

public enum SpatialScalingMode {
    OFF("metallum.options.metalfx_spatial_scaling.off"),
    AUTO("metallum.options.metalfx_spatial_scaling.auto"),
    QUALITY("metallum.options.metalfx_spatial_scaling.quality"),
    PERFORMANCE("metallum.options.metalfx_spatial_scaling.performance");

    private final String translationKey;

    SpatialScalingMode(final String translationKey) {
        this.translationKey = translationKey;
    }

    public float linearScale() {
        return switch (this) {
            case OFF -> 1.0f;
            case QUALITY -> 2.0f / 3.0f;
            case PERFORMANCE -> 0.50f;
            case AUTO -> throw new IllegalStateException("AUTO must be resolved to a concrete MetalFX preset");
        };
    }

    public int nominalLinearPercent() {
        return Math.round(this.linearScale() * 100.0f);
    }

    public int nominalPixelPercent() {
        float scale = this.linearScale();
        return Math.round(scale * scale * 100.0f);
    }

    public String translationKey() {
        return this.translationKey;
    }

    public boolean enabled() {
        return this == QUALITY || this == PERFORMANCE;
    }

    public boolean concrete() {
        return this != AUTO;
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
