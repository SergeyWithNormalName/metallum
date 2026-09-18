package com.metallum.client.lighting.shader;

import com.metallum.Metallum;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent, restart-gated user opt-in for temporal reconstruction of L6 shadows. */
public final class L6TemporalShadowExperimentConfig {
    private static final String FILE_NAME = "metallum-experimental-shadows.properties";
    private static final String ENABLED_KEY = "enabled";

    private static volatile Boolean enabled;

    private L6TemporalShadowExperimentConfig() {
    }

    public static boolean isEnabled() {
        Boolean current = enabled;
        if (current != null) {
            return current;
        }
        synchronized (L6TemporalShadowExperimentConfig.class) {
            if (enabled == null) {
                enabled = load(path());
            }
            return enabled;
        }
    }

    public static void setEnabled(final boolean value) {
        synchronized (L6TemporalShadowExperimentConfig.class) {
            enabled = value;
            Path configPath = path();
            if (configPath != null) {
                save(configPath, value);
            }
        }
    }

    static boolean load(final Path path) {
        Properties properties = new Properties();
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return Boolean.parseBoolean(properties.getProperty(ENABLED_KEY, "false"));
        } catch (IOException | IllegalArgumentException exception) {
            Metallum.LOGGER.warn(
                    "Failed to read {}; experimental temporal shadows remain disabled",
                    path,
                    exception
            );
            return false;
        }
    }

    private static void save(final Path path, final boolean value) {
        Properties properties = new Properties();
        properties.setProperty(ENABLED_KEY, Boolean.toString(value));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum experimental temporal shadows (restart required)");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save experimental temporal shadows at {}", path, exception);
        }
    }

    private static Path path() {
        try {
            Path configDirectory = FabricLoader.getInstance().getConfigDir();
            return configDirectory == null ? null : configDirectory.resolve(FILE_NAME);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
