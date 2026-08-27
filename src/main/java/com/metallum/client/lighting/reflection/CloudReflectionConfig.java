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

/** Persistent live toggle for Minecraft cloud geometry inside reflected targets. */
public final class CloudReflectionConfig {
    public static final String PROPERTY_ENABLED = "metallum.cloud_reflections";
    private static final String FILE_NAME = "metallum-cloud-reflections.properties";
    private static final String ENABLED_KEY = "enabled";

    private static volatile Boolean enabled;

    private CloudReflectionConfig() {
    }

    public static boolean isEnabled() {
        String forced = System.getProperty(PROPERTY_ENABLED);
        if (forced != null) {
            return Boolean.parseBoolean(forced);
        }
        Boolean current = enabled;
        if (current != null) {
            return current;
        }
        synchronized (CloudReflectionConfig.class) {
            if (enabled == null) {
                try {
                    enabled = load(path());
                } catch (RuntimeException unavailableLoader) {
                    // Source-only tests can run before Fabric assigns a config directory.
                    enabled = true;
                }
            }
            return enabled;
        }
    }

    public static void setEnabled(final boolean value) {
        synchronized (CloudReflectionConfig.class) {
            enabled = value;
            save(path(), value);
        }
    }

    static boolean load(final Path path) {
        if (!Files.isRegularFile(path)) {
            return true;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return !"false".equalsIgnoreCase(properties.getProperty(ENABLED_KEY));
        } catch (IOException | IllegalArgumentException exception) {
            Metallum.LOGGER.warn("Failed to read {}; cloud reflections remain enabled", path, exception);
            return true;
        }
    }

    private static void save(final Path path, final boolean value) {
        Properties properties = new Properties();
        properties.setProperty(ENABLED_KEY, Boolean.toString(value));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum cloud reflections");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save cloud-reflection config at {}", path, exception);
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
