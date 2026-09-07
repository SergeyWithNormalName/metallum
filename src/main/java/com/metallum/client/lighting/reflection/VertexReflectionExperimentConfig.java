package com.metallum.client.lighting.reflection;

import com.metallum.Metallum;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent, restart-gated user opt-in for the quarantined reflection experiment. */
public final class VertexReflectionExperimentConfig {
    private static final String FILE_NAME = "metallum-vertex-reflection.properties";
    private static final String ENABLED_KEY = "enabled";

    private static volatile Boolean enabled;

    private VertexReflectionExperimentConfig() {
    }

    public static boolean isEnabled() {
        return WaterReflectionConfig.getPersistedMode() == WaterReflectionMode.VOXELS;
    }

    public static void setEnabled(final boolean value) {
        synchronized (VertexReflectionExperimentConfig.class) {
            enabled = value;
            save(path(), value);
        }
    }

    static boolean load(final Path path) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return Boolean.parseBoolean(properties.getProperty(ENABLED_KEY, "false"));
        } catch (IOException | IllegalArgumentException exception) {
            Metallum.LOGGER.warn("Failed to read {}; frozen reflection experiment remains disabled", path, exception);
            return false;
        }
    }

    private static void save(final Path path, final boolean value) {
        Properties properties = new Properties();
        properties.setProperty(ENABLED_KEY, Boolean.toString(value));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum frozen vertex-reflection experiment (restart required)");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save frozen reflection experiment config at {}", path, exception);
        }
    }

    private static Path path() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException uninitialized) {
            return Path.of("config").resolve(FILE_NAME);
        }
    }
}
