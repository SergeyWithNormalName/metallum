package com.metallum.client.renderer.style;

import java.util.Objects;

/**
 * Immutable profile holding style-specific rendering policies.
 */
public record VisualStyleProfile(
        CelestialLightingProfile celestialLighting,
        AtmosphereProfile atmosphere,
        WaterStyleProfile water
) {
    public VisualStyleProfile {
        Objects.requireNonNull(celestialLighting, "celestialLighting");
        Objects.requireNonNull(atmosphere, "atmosphere");
        Objects.requireNonNull(water, "water");
    }
}
