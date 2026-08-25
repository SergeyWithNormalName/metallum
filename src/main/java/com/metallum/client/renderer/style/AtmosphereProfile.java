package com.metallum.client.renderer.style;

/**
 * Immutable atmospheric rendering policy defining style-specific aerial perspective,
 * celestial coupling, and weather response for existing Minecraft fog.
 */
public record AtmosphereProfile(
        float fogDistanceStartRatio,
        float sunsetAtmosphereWeight,
        float nightCoolWeight,
        float rainDistanceScale,
        float thunderDistanceScale,
        float weatherDarkeningScale
) {
    public AtmosphereProfile {
        requireFinite(fogDistanceStartRatio, "fogDistanceStartRatio");
        if (fogDistanceStartRatio <= 0.0f || fogDistanceStartRatio > 1.0f) {
            throw new IllegalArgumentException("fogDistanceStartRatio must be in (0.0, 1.0]");
        }
        requireUnitRange(sunsetAtmosphereWeight, "sunsetAtmosphereWeight");
        requireUnitRange(nightCoolWeight, "nightCoolWeight");
        requireUnitRange(rainDistanceScale, "rainDistanceScale");
        requireUnitRange(thunderDistanceScale, "thunderDistanceScale");
        requireUnitRange(weatherDarkeningScale, "weatherDarkeningScale");
    }

    /**
     * Returns whether this profile represents the zero-adjustment Vanilla identity.
     */
    public boolean isVanillaIdentity() {
        return this.fogDistanceStartRatio >= 1.0f - 1.0e-5f
                && this.sunsetAtmosphereWeight <= 1.0e-5f
                && this.nightCoolWeight <= 1.0e-5f
                && this.rainDistanceScale <= 1.0e-5f
                && this.thunderDistanceScale <= 1.0e-5f
                && this.weatherDarkeningScale <= 1.0e-5f;
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireUnitRange(final float value, final String name) {
        requireFinite(value, name);
        if (value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be within [0.0, 1.0]");
        }
    }
}
