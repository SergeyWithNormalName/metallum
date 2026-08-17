package com.metallum.client.lighting.cloud;

import net.minecraft.client.CloudStatus;

import java.util.Objects;

/**
 * Mapped semantic modes for Metallum cloud shadow generation.
 *
 * <p>Cloud shadows automatically follow Minecraft's resolved cloud configuration.
 * When clouds are OFF, transparent, or disabled by the environment, mode resolves to {@link #NONE}
 * and effectively zero cloud-shadow GPU work is executed.</p>
 */
public enum CloudShadowMode {
    NONE(0),
    FLAT(1),
    VOLUMETRIC(2);

    private final int id;

    CloudShadowMode(final int id) {
        this.id = id;
    }

    public int id() {
        return this.id;
    }

    public boolean isShadowActive() {
        return this != NONE;
    }

    /**
     * Resolves the actual semantic cloud shadow mode from Minecraft runtime state.
     *
     * @param cloudStatus actual Minecraft CloudStatus enum
     * @param cloudHeight resolved cloud height (NaN if dimension has no clouds)
     * @param cloudOpacity resolved cloud visual opacity [0..1]
     * @param sourceAvailable whether cloud source coverage data is available
     * @return resolved CloudShadowMode
     */
    public static CloudShadowMode fromMinecraft(
            final CloudStatus cloudStatus,
            final float cloudHeight,
            final float cloudOpacity,
            final boolean sourceAvailable
    ) {
        if (cloudStatus == null || cloudStatus == CloudStatus.OFF) {
            return NONE;
        }
        if (!Float.isFinite(cloudHeight) || cloudHeight <= 0.0f) {
            return NONE;
        }
        if (!Float.isFinite(cloudOpacity) || cloudOpacity <= 0.005f) {
            return NONE;
        }
        if (!sourceAvailable) {
            return NONE;
        }
        return switch (cloudStatus) {
            case FAST -> FLAT;
            case FANCY -> VOLUMETRIC;
            default -> NONE;
        };
    }

    public static CloudShadowMode fromId(final int id) {
        return switch (id) {
            case 1 -> FLAT;
            case 2 -> VOLUMETRIC;
            default -> NONE;
        };
    }
}
