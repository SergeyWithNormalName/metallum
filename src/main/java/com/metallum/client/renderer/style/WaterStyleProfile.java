package com.metallum.client.renderer.style;

import java.util.Objects;

/**
 * Immutable L8 water appearance policy for one built-in visual style.
 *
 * <p>The stable shader policy id is uploaded through the existing material environment
 * contract. The generated shader embeds the remaining values from these authoritative profiles,
 * so live style switching needs no pipeline rebuild, allocation, or additional GPU resource.</p>
 */
public record WaterStyleProfile(
        int shaderPolicyId,
        boolean enhanced,
        float waveStrength,
        float reflectionStrength,
        float refractionStrength,
        float reflectionBodyStrength,
        float causticStrength,
        float roughness,
        float transmission,
        float opticalDepth,
        LinearColor absorption
) {
    public WaterStyleProfile {
        if (shaderPolicyId < 0 || shaderPolicyId > 2) {
            throw new IllegalArgumentException("shaderPolicyId must be within [0, 2]");
        }
        requireUnit(waveStrength, "waveStrength");
        requireUnit(reflectionStrength, "reflectionStrength");
        requireUnit(refractionStrength, "refractionStrength");
        requireUnit(reflectionBodyStrength, "reflectionBodyStrength");
        requireUnit(causticStrength, "causticStrength");
        requireUnit(roughness, "roughness");
        requireUnit(transmission, "transmission");
        requireNonNegative(opticalDepth, "opticalDepth");
        Objects.requireNonNull(absorption, "absorption");
        if (!enhanced && (waveStrength != 0.0f
                || reflectionStrength != 0.0f
                || refractionStrength != 0.0f
                || reflectionBodyStrength != 0.0f
                || causticStrength != 0.0f
                || transmission != 0.0f
                || opticalDepth != 0.0f
                || !absorption.equals(LinearColor.BLACK))) {
            throw new IllegalArgumentException("disabled water profile must be an optical identity");
        }
    }

    /** True only for the exact no-L8-water-effects profile. */
    public boolean isVanillaIdentity() {
        return !this.enhanced;
    }

    private static void requireUnit(final float value, final String name) {
        requireFinite(value, name);
        if (value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be within [0.0, 1.0]");
        }
    }

    private static void requireNonNegative(final float value, final String name) {
        requireFinite(value, name);
        if (value < 0.0f) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
