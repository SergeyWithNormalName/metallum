package com.metallum.client.renderer.style;

import java.util.Objects;

/**
 * Immutable celestial lighting policy defining style-specific sun, moon, and transition curves.
 */
public record CelestialLightingProfile(
        LinearColor normalSunColor,
        LinearColor horizonSunColor,
        float sunTransitionMinAltitude,
        float sunTransitionMaxAltitude,
        float sunIntensityScale,
        LinearColor moonColor,
        float moonIntensityScale,
        float moonPhaseFloor,
        float moonPhaseResponse
) {
    public CelestialLightingProfile {
        Objects.requireNonNull(normalSunColor, "normalSunColor");
        Objects.requireNonNull(horizonSunColor, "horizonSunColor");
        requireFinite(sunTransitionMinAltitude, "sunTransitionMinAltitude");
        requireFinite(sunTransitionMaxAltitude, "sunTransitionMaxAltitude");
        if (sunTransitionMinAltitude < 0.0f) {
            throw new IllegalArgumentException("sunTransitionMinAltitude must be non-negative");
        }
        if (sunTransitionMaxAltitude < sunTransitionMinAltitude) {
            throw new IllegalArgumentException("sunTransitionMaxAltitude must be >= sunTransitionMinAltitude");
        }
        requireNonNegative(sunIntensityScale, "sunIntensityScale");
        Objects.requireNonNull(moonColor, "moonColor");
        requireNonNegative(moonIntensityScale, "moonIntensityScale");
        requireUnitRange(moonPhaseFloor, "moonPhaseFloor");
        requireNonNegative(moonPhaseResponse, "moonPhaseResponse");
    }

    /**
     * Evaluates the sun directional light color at the specified celestial altitude.
     *
     * <p>Preserves the high-sun reference luminance across the transition to decouple
     * chromaticity change from accidental energy loss.</p>
     */
    public LinearColor evaluateSunColor(final float altitude) {
        if (this.normalSunColor.equals(this.horizonSunColor)) {
            return this.normalSunColor;
        }
        float clampedAltitude = Math.max(altitude, 0.0f);
        float warmth = 1.0f - smoothstep(this.sunTransitionMinAltitude, this.sunTransitionMaxAltitude, clampedAltitude);
        LinearColor rawColor = LinearColor.lerp(this.normalSunColor, this.horizonSunColor, warmth);
        float refY = this.normalSunColor.luminance();
        float rawY = rawColor.luminance();
        return rawY > 1.0e-6f ? rawColor.scale(refY / rawY) : rawColor;
    }

    /**
     * Evaluates the lunar phase directional contribution scale.
     */
    public float evaluateMoonPhaseScale(final float moonPhaseBrightness) {
        float safePhase = Math.clamp(moonPhaseBrightness, 0.0f, 1.0f);
        return this.moonPhaseFloor + this.moonPhaseResponse * safePhase;
    }

    private static float smoothstep(final float low, final float high, final float value) {
        if (high <= low) {
            return value >= high ? 1.0f : 0.0f;
        }
        float t = Math.clamp((value - low) / (high - low), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireNonNegative(final float value, final String name) {
        requireFinite(value, name);
        if (value < 0.0f) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requireUnitRange(final float value, final String name) {
        requireFinite(value, name);
        if (value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be within [0.0, 1.0]");
        }
    }
}
