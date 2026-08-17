package com.metallum.client.renderer.style;

import java.util.Locale;

/**
 * Built-in artistic rendering style policy for Metallum.
 *
 * <p>A visual style defines artistic lighting and atmospheric intent without affecting GPU
 * performance budgets, lighting presets, MetalFX, HDR, or DRS.</p>
 */
public enum VisualStyle {
    VANILLA,
    NATURAL,
    REALISM;

    public static final VisualStyle DEFAULT = VANILLA;

    /**
     * Safely parses a visual style from its persistent name or enum name.
     * Returns {@link #DEFAULT} for null, blank, or unrecognized values.
     */
    public static VisualStyle parse(final String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }

    /**
     * Returns the stable lowercase configuration name (e.g. "vanilla", "natural", "realism").
     */
    public String persistentName() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
