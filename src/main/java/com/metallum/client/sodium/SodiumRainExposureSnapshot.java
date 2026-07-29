package com.metallum.client.sodium;

import java.util.Objects;

/**
 * Immutable-by-contract 16x16 precipitation height snapshot captured before Sodium dispatches a
 * section mesh task. It keeps worker threads away from the live level heightmap and turns rain
 * eligibility into one array lookup per rendered block rather than per fragment.
 */
public final class SodiumRainExposureSnapshot {
    public static final int WIDTH = 16;
    public static final int AREA = WIDTH * WIDTH;

    private final int minBlockX;
    private final int minBlockZ;
    private final int[] precipitationHeights;

    public SodiumRainExposureSnapshot(
            final int minBlockX,
            final int minBlockZ,
            final int[] precipitationHeights
    ) {
        this.minBlockX = minBlockX;
        this.minBlockZ = minBlockZ;
        this.precipitationHeights = Objects.requireNonNull(
                precipitationHeights, "precipitation heights");
        if (precipitationHeights.length != AREA) {
            throw new IllegalArgumentException("precipitation height snapshot must contain 256 columns");
        }
    }

    public boolean canRainReach(final int blockX, final int surfaceY, final int blockZ) {
        int localX = blockX - this.minBlockX;
        int localZ = blockZ - this.minBlockZ;
        if ((localX | localZ) < 0 || localX >= WIDTH || localZ >= WIDTH) {
            return false;
        }
        return surfaceY >= this.precipitationHeights[(localZ << 4) | localX];
    }
}
