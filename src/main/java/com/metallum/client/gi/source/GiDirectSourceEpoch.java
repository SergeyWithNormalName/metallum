package com.metallum.client.gi.source;

import com.metallum.client.lighting.LightWorldToken;

import java.util.Objects;

/** Complete source identity required before a G3 dirty brick may execute. */
public record GiDirectSourceEpoch(
        long g2WorldGeneration,
        long g2ResourceEpoch,
        long g2MaterialEpoch,
        long g2ClipmapGeneration,
        long g2PaletteGeneration,
        long g2ContentGeneration,
        LightWorldToken staticLightWorld,
        long staticLightRegistryEpoch,
        long environmentEpoch
) {
    public GiDirectSourceEpoch {
        if (g2WorldGeneration <= 0L || g2ResourceEpoch <= 0L || g2MaterialEpoch <= 0L
                || g2ClipmapGeneration <= 0L || g2ContentGeneration <= 0L
                || g2PaletteGeneration <= 0L
                || staticLightRegistryEpoch <= 0L || environmentEpoch <= 0L) {
            throw new IllegalArgumentException("G3 source epochs must be positive");
        }
        Objects.requireNonNull(staticLightWorld, "staticLightWorld");
    }

    public boolean isCurrentOrNewerThan(final GiDirectSourceEpoch previous) {
        Objects.requireNonNull(previous, "previous");
        return this.g2WorldGeneration >= previous.g2WorldGeneration
                && this.g2ResourceEpoch >= previous.g2ResourceEpoch
                && this.g2MaterialEpoch >= previous.g2MaterialEpoch
                && this.g2ClipmapGeneration >= previous.g2ClipmapGeneration
                && this.g2PaletteGeneration >= previous.g2PaletteGeneration
                && this.g2ContentGeneration >= previous.g2ContentGeneration
                && this.staticLightWorld.generation() >= previous.staticLightWorld.generation()
                && this.staticLightRegistryEpoch >= previous.staticLightRegistryEpoch
                && this.environmentEpoch >= previous.environmentEpoch;
    }

    public void requireStrictlyNewerThan(final GiDirectSourceEpoch previous) {
        if (!isCurrentOrNewerThan(previous) || this.equals(previous)) {
            throw new IllegalArgumentException("G3 source epoch regressed or did not advance");
        }
    }
}
