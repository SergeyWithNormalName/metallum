package com.metallum.client.renderer.style;

import java.util.Objects;

/**
 * Immutable profile holding style-specific rendering policies.
 */
public record VisualStyleProfile(
        CelestialLightingProfile celestialLighting
) {
    public VisualStyleProfile {
        Objects.requireNonNull(celestialLighting, "celestialLighting");
    }
}
