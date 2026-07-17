package com.metallum.client.lighting;

import java.util.Objects;

/**
 * Output-independent, linear-light description of the current Minecraft environment.
 *
 * <p>The descriptor deliberately contains no SDR/HDR state. It is produced from the extracted
 * world render state, then consumed by the Advanced lighting generation for both output modes.</p>
 */
public record EnvironmentDescriptor(
        int version,
        Profile profile,
        Medium medium,
        float toLightX,
        float toLightY,
        float toLightZ,
        float directionalRed,
        float directionalGreen,
        float directionalBlue,
        float skyRed,
        float skyGreen,
        float skyBlue,
        float ambientRed,
        float ambientGreen,
        float ambientBlue,
        float rain,
        float thunder,
        float moonPhaseBrightness,
        boolean moon,
        boolean sunShadowEligible
) {
    public static final int VERSION = 1;

    public enum Profile {
        CELESTIAL,
        AMBIENT_ONLY,
        END
    }

    public enum Medium {
        AIR,
        WATER,
        LAVA,
        POWDER_SNOW
    }

    public static final EnvironmentDescriptor NONE = ambientOnly(
            Profile.AMBIENT_ONLY,
            Medium.AIR,
            0.04f,
            0.04f,
            0.04f
    );

    public EnvironmentDescriptor {
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported environment descriptor version " + version);
        }
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(medium, "medium");
        requireFinite(toLightX, "light direction x");
        requireFinite(toLightY, "light direction y");
        requireFinite(toLightZ, "light direction z");
        requireNonNegative(directionalRed, "directional red");
        requireNonNegative(directionalGreen, "directional green");
        requireNonNegative(directionalBlue, "directional blue");
        requireNonNegative(skyRed, "sky red");
        requireNonNegative(skyGreen, "sky green");
        requireNonNegative(skyBlue, "sky blue");
        requireNonNegative(ambientRed, "ambient red");
        requireNonNegative(ambientGreen, "ambient green");
        requireNonNegative(ambientBlue, "ambient blue");
        rain = clampUnit(rain, "rain");
        thunder = clampUnit(thunder, "thunder");
        moonPhaseBrightness = clampUnit(moonPhaseBrightness, "moon phase brightness");

        float directionLengthSquared = toLightX * toLightX
                + toLightY * toLightY
                + toLightZ * toLightZ;
        if (profile == Profile.CELESTIAL) {
            if (!(directionLengthSquared > 0.999f && directionLengthSquared < 1.001f)) {
                throw new IllegalArgumentException("Celestial light direction must be normalized");
            }
        } else if (directionLengthSquared != 0.0f || sunShadowEligible || moon) {
            throw new IllegalArgumentException(
                    "Non-celestial environments cannot retain directional shadow state"
            );
        }
        if (sunShadowEligible && directionalRed + directionalGreen + directionalBlue <= 0.0f) {
            throw new IllegalArgumentException("A shadow-casting environment needs directional radiance");
        }
    }

    public static EnvironmentDescriptor celestial(
            final Medium medium,
            final float sunAngle,
            final float skyRed,
            final float skyGreen,
            final float skyBlue,
            final float skyFactor,
            final float ambientRed,
            final float ambientGreen,
            final float ambientBlue,
            final float rain,
            final float thunder,
            final float moonPhaseBrightness
    ) {
        Objects.requireNonNull(medium, "medium");
        requireFinite(sunAngle, "sun angle");
        requireNonNegative(skyRed, "sky red");
        requireNonNegative(skyGreen, "sky green");
        requireNonNegative(skyBlue, "sky blue");
        requireNonNegative(skyFactor, "sky factor");
        requireNonNegative(ambientRed, "ambient red");
        requireNonNegative(ambientGreen, "ambient green");
        requireNonNegative(ambientBlue, "ambient blue");
        float safeRain = clampUnit(rain, "rain");
        float safeThunder = clampUnit(thunder, "thunder");
        float safeMoonPhase = clampUnit(moonPhaseBrightness, "moon phase brightness");

        float sunX = (float) -Math.sin(sunAngle);
        float sunY = (float) Math.cos(sunAngle);
        boolean moon = sunY < 0.0f;
        float toLightX = moon ? -sunX : sunX;
        float toLightY = moon ? -sunY : sunY;
        float altitude = Math.max(toLightY, 0.0f);
        float horizon = smoothstep(0.015f, 0.16f, altitude);
        float weatherTransmission = Math.max(
                0.08f,
                1.0f - safeRain * 0.72f - safeThunder * 0.20f
        );
        float mediumTransmission = switch (medium) {
            case WATER -> 0.55f;
            case LAVA -> 0.0f;
            case POWDER_SNOW -> 0.22f;
            case AIR -> 1.0f;
        };
        float directionalScale = moon
                ? 0.13f * (0.18f + 0.82f * safeMoonPhase) * horizon
                : 1.65f * horizon;
        directionalScale *= weatherTransmission * mediumTransmission;

        float directionalRed = directionalScale * (moon ? 0.50f : 1.00f);
        float directionalGreen = directionalScale * (moon ? 0.62f : 0.93f);
        float directionalBlue = directionalScale * (moon ? 0.90f : 0.78f);
        float diffuseWeather = 1.0f - safeThunder * 0.45f;
        // Minecraft 26.2 authors skyFactor for its encoded lightmap. Use it for the data-driven
        // day/night curve, but keep L4's scene-linear reference scale: copying skyFactor through
        // pi clips daytime HDR terrain, while the old fixed moon value made night terrain black.
        // The square-root remap preserves the accepted 0.46 daytime irradiance and lifts the
        // 0.24 night plateau enough for side faces to retain readable diffuse color.
        float diffuseToIrradiance = (float) Math.PI;
        float skyScale = 0.46f * (float) Math.sqrt(skyFactor) * diffuseWeather
                * Math.max(mediumTransmission, 0.12f);

        return new EnvironmentDescriptor(
                VERSION,
                Profile.CELESTIAL,
                medium,
                toLightX,
                toLightY,
                0.0f,
                directionalRed,
                directionalGreen,
                directionalBlue,
                skyRed * skyScale,
                skyGreen * skyScale,
                skyBlue * skyScale,
                ambientRed * diffuseToIrradiance,
                ambientGreen * diffuseToIrradiance,
                ambientBlue * diffuseToIrradiance,
                safeRain,
                safeThunder,
                safeMoonPhase,
                moon,
                altitude > 0.035f && directionalScale > 0.001f
        );
    }

    public static EnvironmentDescriptor ambientOnly(
            final Profile profile,
            final Medium medium,
            final float red,
            final float green,
            final float blue
    ) {
        if (profile == Profile.CELESTIAL) {
            throw new IllegalArgumentException("Use celestial() for a celestial profile");
        }
        return new EnvironmentDescriptor(
                VERSION,
                profile,
                medium,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                red,
                green,
                blue,
                0.0f,
                0.0f,
                0.0f,
                false,
                false
        );
    }

    private static float smoothstep(final float low, final float high, final float value) {
        float t = Math.clamp((value - low) / (high - low), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float clampUnit(final float value, final String label) {
        requireFinite(value, label);
        return Math.clamp(value, 0.0f, 1.0f);
    }

    private static void requireNonNegative(final float value, final String label) {
        requireFinite(value, label);
        if (value < 0.0f) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
    }

    private static void requireFinite(final float value, final String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}
