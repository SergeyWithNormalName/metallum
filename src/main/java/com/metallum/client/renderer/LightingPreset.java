package com.metallum.client.renderer;

import java.util.Locale;

/** Quality and work-budget policy for Metallum lighting. */
public enum LightingPreset {
    PERFORMANCE,
    BALANCED,
    ULTRA;

    public static LightingPreset parse(final String value) {
        if (value == null || value.isBlank()) {
            return BALANCED;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BALANCED;
        }
    }
}
