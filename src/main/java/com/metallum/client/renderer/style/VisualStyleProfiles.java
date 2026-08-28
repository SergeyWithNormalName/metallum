package com.metallum.client.renderer.style;

/**
 * Authoritative registry of built-in visual style profiles.
 *
 * <p>Provides immutable, static profiles for VANILLA, NATURAL, and REALISM without runtime
 * allocations or disk I/O on render hot paths.</p>
 */
public final class VisualStyleProfiles {
    private static final CelestialLightingProfile VANILLA_CELESTIAL = new CelestialLightingProfile(
            new LinearColor(1.00f, 0.93f, 0.78f),
            new LinearColor(1.00f, 0.93f, 0.78f),
            0.015f,
            0.16f,
            1.65f,
            new LinearColor(0.50f, 0.62f, 0.90f),
            0.13f,
            0.18f,
            0.82f
    );

    private static final CelestialLightingProfile NATURAL_CELESTIAL = new CelestialLightingProfile(
            new LinearColor(1.00f, 0.98f, 0.92f),
            new LinearColor(1.00f, 0.50f, 0.18f),
            0.035f,
            0.42f,
            1.65f,
            new LinearColor(0.72f, 0.80f, 1.00f),
            0.10f,
            0.06f,
            0.94f
    );

    private static final CelestialLightingProfile REALISM_CELESTIAL = new CelestialLightingProfile(
            new LinearColor(1.00f, 0.995f, 0.97f),
            new LinearColor(1.00f, 0.32f, 0.07f),
            0.020f,
            0.55f,
            1.65f,
            new LinearColor(0.90f, 0.94f, 1.00f),
            0.085f,
            0.00f,
            1.00f
    );

    private static final AtmosphereProfile VANILLA_ATMOSPHERE = new AtmosphereProfile(
            1.00f,
            0.00f,
            0.00f,
            0.00f,
            0.00f,
            0.00f
    );

    private static final AtmosphereProfile NATURAL_ATMOSPHERE = new AtmosphereProfile(
            0.75f,
            0.28f,
            0.12f,
            0.15f,
            0.15f,
            0.10f
    );

    private static final AtmosphereProfile REALISM_ATMOSPHERE = new AtmosphereProfile(
            0.55f,
            0.45f,
            0.22f,
            0.25f,
            0.20f,
            0.20f
    );

    private static final WaterStyleProfile VANILLA_WATER = new WaterStyleProfile(
            0,
            false,
            false,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.075f,
            0.0f,
            0.0f,
            LinearColor.BLACK
    );

    private static final WaterStyleProfile NATURAL_WATER = new WaterStyleProfile(
            1,
            true,
            true,
            1.0f,
            1.0f,
            0.28f,
            0.0f,
            1.0f,
            0.055f,
            0.30f,
            0.85f,
            new LinearColor(0.15f, 0.040f, 0.015f)
    );

    private static final WaterStyleProfile REALISM_WATER = new WaterStyleProfile(
            2,
            true,
            false,
            1.0f,
            1.0f,
            0.55f,
            0.85f,
            1.0f,
            0.065f,
            0.38f,
            1.15f,
            new LinearColor(0.18f, 0.055f, 0.022f)
    );

    private static final VisualStyleProfile VANILLA_PROFILE = new VisualStyleProfile(
            VANILLA_CELESTIAL,
            VANILLA_ATMOSPHERE,
            VANILLA_WATER
    );

    private static final VisualStyleProfile NATURAL_PROFILE = new VisualStyleProfile(
            NATURAL_CELESTIAL,
            NATURAL_ATMOSPHERE,
            NATURAL_WATER
    );

    private static final VisualStyleProfile REALISM_PROFILE = new VisualStyleProfile(
            REALISM_CELESTIAL,
            REALISM_ATMOSPHERE,
            REALISM_WATER
    );

    private VisualStyleProfiles() {
    }

    /**
     * Resolves the immutable profile for the given visual style without runtime allocations.
     */
    public static VisualStyleProfile profile(final VisualStyle style) {
        if (style == null) {
            return VANILLA_PROFILE;
        }
        return switch (style) {
            case VANILLA -> VANILLA_PROFILE;
            case NATURAL -> NATURAL_PROFILE;
            case REALISM -> REALISM_PROFILE;
        };
    }
}
