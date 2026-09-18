package com.metallum.client.lighting.reflection;

import com.metallum.Metallum;
import com.metallum.client.metal.render.PlanarReflectionConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Authoritative runtime configuration and persistence for water reflections.
 *
 * <p>Coordinates the user's choice between {@link WaterReflectionMode#OFF},
 * {@link WaterReflectionMode#VOXELS}, and {@link WaterReflectionMode#SCREEN_SPACE}.
 * Serves as the sole authoritative source of truth, performing a one-time migration
 * of legacy reflection settings and driving legacy reflection mirrors unidirectionally.</p>
 */
public final class WaterReflectionConfig {
    public static final String FILE_NAME = "metallum-reflections.properties";
    public static final String KEY_MODE = "water_reflection_mode";
    public static final String PROPERTY_MODE = "metallum.water_reflection_mode";

    private static final String LEGACY_VOXEL_FILE = "metallum-vertex-reflection.properties";
    private static final String LEGACY_PLANAR_FILE = "metallum-water-planar-reflections.properties";

    private static volatile WaterReflectionMode persistedMode;

    private WaterReflectionConfig() {
    }

    public static WaterReflectionMode getPersistedMode() {
        String forced = System.getProperty(PROPERTY_MODE);
        if (forced != null && !forced.isBlank()) {
            return WaterReflectionMode.fromString(forced);
        }
        WaterReflectionMode current = persistedMode;
        if (current != null) {
            return current;
        }
        synchronized (WaterReflectionConfig.class) {
            if (persistedMode == null) {
                persistedMode = load(path());
            }
            return persistedMode;
        }
    }

    /**
     * Resolves the mode active in the current rendering session.
     *
     * <p>If {@link WaterReflectionMode#VOXELS} is persisted but the current JVM booted
     * without the required vertex layout admission, this returns {@link WaterReflectionMode#OFF}
     * to protect the active world against carrier/layout conflicts until restart.</p>
     */
    public static WaterReflectionMode getActiveMode() {
        WaterReflectionMode persisted = getPersistedMode();
        if (persisted == WaterReflectionMode.VOXELS) {
            return VertexReflectionExperiment.isLayoutEnabled()
                    ? WaterReflectionMode.VOXELS
                    : WaterReflectionMode.OFF;
        }
        return persisted;
    }

    public static boolean isVoxelActive() {
        return getActiveMode() == WaterReflectionMode.VOXELS;
    }

    public static boolean isScreenSpaceActive() {
        return getActiveMode() == WaterReflectionMode.SCREEN_SPACE;
    }

    public static boolean isOff() {
        return getActiveMode() == WaterReflectionMode.OFF;
    }

    /**
     * Checks if switching to {@code targetMode} requires a full game restart from
     * the perspective of the current active session's vertex layout.
     */
    public static boolean isRestartRequiredFor(final WaterReflectionMode targetMode) {
        boolean sessionLayoutEnabled = VertexReflectionExperiment.isLayoutEnabled();
        if (!sessionLayoutEnabled && targetMode == WaterReflectionMode.VOXELS) {
            return true;
        }
        if (sessionLayoutEnabled && targetMode != WaterReflectionMode.VOXELS) {
            return true;
        }
        return false;
    }

    public static void setMode(final WaterReflectionMode mode) {
        WaterReflectionMode sanitized = mode != null ? mode : WaterReflectionMode.OFF;
        synchronized (WaterReflectionConfig.class) {
            persistedMode = sanitized;
            save(path(), sanitized);
            // Drive legacy settings unidirectionally to keep old reflection field controllers in sync.
            VertexReflectionExperimentConfig.setEnabled(sanitized == WaterReflectionMode.VOXELS);
            PlanarReflectionConfig.setEnabled(false);
        }
    }

    static WaterReflectionMode load(final Path configPath) {
        if (Files.isRegularFile(configPath)) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                properties.load(reader);
                String raw = properties.getProperty(KEY_MODE);
                if (raw != null && !raw.isBlank()) {
                    return WaterReflectionMode.fromString(raw);
                }
            } catch (IOException | IllegalArgumentException exception) {
                Metallum.LOGGER.warn("Failed to read {}; falling back to migration check", configPath, exception);
            }
        }

        // One-time migration from legacy settings
        WaterReflectionMode migrated = migrateLegacySettings(configPath.getParent());
        save(configPath, migrated);
        return migrated;
    }

    private static WaterReflectionMode migrateLegacySettings(final Path configDir) {
        if (configDir != null) {
            Path legacyVoxelPath = configDir.resolve(LEGACY_VOXEL_FILE);
            if (Files.isRegularFile(legacyVoxelPath)) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(legacyVoxelPath, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                    if (Boolean.parseBoolean(properties.getProperty("enabled", "false"))) {
                        Metallum.LOGGER.info("Migrated legacy voxel reflection setting: VOXELS");
                        return WaterReflectionMode.VOXELS;
                    }
                } catch (IOException ignored) {
                }
            }

            Path legacyPlanarPath = configDir.resolve(LEGACY_PLANAR_FILE);
            if (Files.isRegularFile(legacyPlanarPath)) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(legacyPlanarPath, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                    if (Boolean.parseBoolean(properties.getProperty("enabled", "false"))) {
                        Metallum.LOGGER.info("Legacy planar reflection detected; planar is deprecated, migrating to OFF");
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return WaterReflectionMode.OFF;
    }

    static void save(final Path configPath, final WaterReflectionMode mode) {
        Properties properties = new Properties();
        properties.setProperty(KEY_MODE, mode.persistentName());
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum water reflection configuration (OFF, VOXELS, SCREEN_SPACE)");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save water reflection config at {}", configPath, exception);
        }
    }

    static Path path() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException uninitialized) {
            return Path.of("config").resolve(FILE_NAME);
        }
    }

    public static void resetForTesting() {
        synchronized (WaterReflectionConfig.class) {
            persistedMode = null;
        }
    }
}
