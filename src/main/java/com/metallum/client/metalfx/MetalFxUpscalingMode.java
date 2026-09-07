package com.metallum.client.metalfx;

import java.util.Locale;

/** Unified dropdown selection for MetalFX upscaling in Sodium GUI. */
public enum MetalFxUpscalingMode {
    OFF("metallum.options.metalfx_upscaling.off"),
    SPATIAL("metallum.options.metalfx_upscaling.spatial"),
    TEMPORAL("metallum.options.metalfx_upscaling.temporal"),
    TEMPORAL_FI("metallum.options.metalfx_upscaling.temporal_fi");

    private final String translationKey;

    MetalFxUpscalingMode(final String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    public boolean isFrameInterpolationEnabled() {
        return this == TEMPORAL_FI;
    }

    public static MetalFxUpscalingMode parse(final String value) {
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
