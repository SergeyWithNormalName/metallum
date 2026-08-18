package com.metallum.client.lighting;

import java.util.Objects;

/**
 * Compile-time terrain fragment specialization mode based on the active environment profile.
 */
public enum TerrainEnvironmentSpecialization {
    /** Canonical full Advanced Lighting terrain fragment path supporting all celestial terms. */
    FULL,

    /** Compile-time specialized terrain fragment path omitting unused celestial terms for ambient-only profiles. */
    AMBIENT_ONLY;

    /**
     * Maps an {@link EnvironmentDescriptor.Profile} to its matching terrain specialization.
     * Only {@link EnvironmentDescriptor.Profile#AMBIENT_ONLY} receives specialization; all other
     * profiles (including {@code CELESTIAL}, {@code END}, or null) fail open to {@link #FULL}.
     */
    public static TerrainEnvironmentSpecialization from(final EnvironmentDescriptor.Profile profile) {
        if (profile == null) {
            return FULL;
        }
        return profile == EnvironmentDescriptor.Profile.AMBIENT_ONLY
                ? AMBIENT_ONLY
                : FULL;
    }
}
