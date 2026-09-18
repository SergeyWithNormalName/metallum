package com.metallum.client.lighting.reflection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Deterministic contract and migration tests for authoritative water reflection configuration. */
public final class WaterReflectionConfigTests {
    private WaterReflectionConfigTests() {
    }

    public static void main(final String[] args) {
        testModeParsing();
        testMissingConfigDefaultsToOff();
        testLegacyVoxelMigration();
        testLegacyPlanarMigrationDefaultsToOff();
        testPersistenceRoundTrip();
        testSystemPropertyOverride();
        testRestartSemantics();
        System.out.println("WaterReflectionConfigTests passed successfully.");
    }

    private static void testModeParsing() {
        require(WaterReflectionMode.fromString(null) == WaterReflectionMode.OFF, "null must parse to OFF");
        require(WaterReflectionMode.fromString("") == WaterReflectionMode.OFF, "empty must parse to OFF");
        require(WaterReflectionMode.fromString("unknown") == WaterReflectionMode.OFF, "unknown must parse to OFF");
        require(WaterReflectionMode.fromString("off") == WaterReflectionMode.OFF, "off must parse to OFF");
        require(WaterReflectionMode.fromString("OFF") == WaterReflectionMode.OFF, "OFF must parse to OFF");

        require(WaterReflectionMode.fromString("voxels") == WaterReflectionMode.VOXELS, "voxels must parse to VOXELS");
        require(WaterReflectionMode.fromString("voxel") == WaterReflectionMode.VOXELS, "voxel must parse to VOXELS");
        require(WaterReflectionMode.fromString("VOXELS") == WaterReflectionMode.VOXELS, "VOXELS must parse to VOXELS");

        require(WaterReflectionMode.fromString("screen_space") == WaterReflectionMode.SCREEN_SPACE,
                "screen_space must parse to SCREEN_SPACE");
        require(WaterReflectionMode.fromString("screenspace") == WaterReflectionMode.SCREEN_SPACE,
                "screenspace must parse to SCREEN_SPACE");
        require(WaterReflectionMode.fromString("ssr") == WaterReflectionMode.SCREEN_SPACE,
                "ssr must parse to SCREEN_SPACE");
    }

    private static void testMissingConfigDefaultsToOff() {
        try {
            Path tempDir = Files.createTempDirectory("metallum-refl-test-");
            Path configPath = tempDir.resolve("metallum-reflections.properties");
            WaterReflectionMode mode = WaterReflectionConfig.load(configPath);
            require(mode == WaterReflectionMode.OFF, "missing config without legacy files must default to OFF");
            require(Files.isRegularFile(configPath), "load must write default config if missing");
        } catch (IOException exception) {
            throw new AssertionError("testMissingConfigDefaultsToOff failed", exception);
        }
    }

    private static void testLegacyVoxelMigration() {
        try {
            Path tempDir = Files.createTempDirectory("metallum-refl-mig-voxel-");
            Path legacyVoxel = tempDir.resolve("metallum-vertex-reflection.properties");
            Files.writeString(legacyVoxel, "enabled=true\n");

            Path configPath = tempDir.resolve("metallum-reflections.properties");
            WaterReflectionMode mode = WaterReflectionConfig.load(configPath);
            require(mode == WaterReflectionMode.VOXELS,
                    "legacy voxel enabled=true must migrate to WaterReflectionMode.VOXELS");
            require(Files.isRegularFile(configPath), "migrated config must be saved");

            // Verify file content
            Properties props = new Properties();
            try (var reader = Files.newBufferedReader(configPath)) {
                props.load(reader);
            }
            require("voxels".equals(props.getProperty("water_reflection_mode")),
                    "saved mode in properties must be 'voxels'");
        } catch (IOException exception) {
            throw new AssertionError("testLegacyVoxelMigration failed", exception);
        }
    }

    private static void testLegacyPlanarMigrationDefaultsToOff() {
        try {
            Path tempDir = Files.createTempDirectory("metallum-refl-mig-planar-");
            Path legacyPlanar = tempDir.resolve("metallum-water-planar-reflections.properties");
            Files.writeString(legacyPlanar, "enabled=true\n");

            Path configPath = tempDir.resolve("metallum-reflections.properties");
            WaterReflectionMode mode = WaterReflectionConfig.load(configPath);
            require(mode == WaterReflectionMode.OFF,
                    "legacy planar reflection must migrate to OFF (planar deprecated)");
        } catch (IOException exception) {
            throw new AssertionError("testLegacyPlanarMigrationDefaultsToOff failed", exception);
        }
    }

    private static void testPersistenceRoundTrip() {
        try {
            Path tempDir = Files.createTempDirectory("metallum-refl-roundtrip-");
            Path configPath = tempDir.resolve("metallum-reflections.properties");

            for (WaterReflectionMode mode : WaterReflectionMode.values()) {
                WaterReflectionConfig.save(configPath, mode);
                WaterReflectionMode loaded = WaterReflectionConfig.load(configPath);
                require(loaded == mode, "round-trip failed for mode: " + mode);
            }
        } catch (IOException exception) {
            throw new AssertionError("testPersistenceRoundTrip failed", exception);
        }
    }

    private static void testSystemPropertyOverride() {
        String key = WaterReflectionConfig.PROPERTY_MODE;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "voxels");
            require(WaterReflectionConfig.getPersistedMode() == WaterReflectionMode.VOXELS,
                    "system property override must take precedence");

            System.setProperty(key, "screen_space");
            require(WaterReflectionConfig.getPersistedMode() == WaterReflectionMode.SCREEN_SPACE,
                    "system property override must support screen_space");

            System.setProperty(key, "off");
            require(WaterReflectionConfig.getPersistedMode() == WaterReflectionMode.OFF,
                    "system property override must support off");
        } finally {
            if (previous != null) {
                System.setProperty(key, previous);
            } else {
                System.clearProperty(key);
            }
            WaterReflectionConfig.resetForTesting();
        }
    }

    private static void testRestartSemantics() {
        // When session layout is not enabled (e.g. during test run where layoutEnabled == false):
        if (!VertexReflectionExperiment.isLayoutEnabled()) {
            require(WaterReflectionConfig.isRestartRequiredFor(WaterReflectionMode.VOXELS),
                    "enabling voxels when session was booted without voxel layout must require restart");
            require(!WaterReflectionConfig.isRestartRequiredFor(WaterReflectionMode.OFF),
                    "staying on or switching to OFF must not require restart");
            require(!WaterReflectionConfig.isRestartRequiredFor(WaterReflectionMode.SCREEN_SPACE),
                    "switching to SCREEN_SPACE in non-voxel session must not require restart");
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
