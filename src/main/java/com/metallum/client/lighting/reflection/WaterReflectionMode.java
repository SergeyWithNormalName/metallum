package com.metallum.client.lighting.reflection;

import java.util.Locale;

/** Authoritative user-facing water reflection mode. */
public enum WaterReflectionMode {
    OFF("off"),
    VOXELS("voxels"),
    SCREEN_SPACE("screen_space");

    private final String persistentName;

    WaterReflectionMode(final String persistentName) {
        this.persistentName = persistentName;
    }

    public String persistentName() {
        return this.persistentName;
    }

    public static WaterReflectionMode fromString(final String raw) {
        if (raw == null) {
            return OFF;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "voxels", "voxel" -> VOXELS;
            case "screen_space", "screenspace", "ssr" -> SCREEN_SPACE;
            default -> OFF;
        };
    }
}
