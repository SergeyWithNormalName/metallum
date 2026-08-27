package com.metallum.client.gi.debug;

import com.metallum.Metallum;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Persistent, restart-gated user opt-in for the field-only G4 diagnostic HUD. */
public final class GiTransportDebugSettings {
    private static final String FILE_NAME = "metallum-gi-g4-debug.properties";
    private static final String ENABLED_KEY = "enabled";

    private static volatile Boolean enabled;

    private GiTransportDebugSettings() {
    }

    public static boolean isEnabled() {
        Boolean current = enabled;
        if (current != null) {
            return current;
        }
        synchronized (GiTransportDebugSettings.class) {
            if (enabled == null) {
                enabled = load(configPath());
            }
            return enabled;
        }
    }

    public static void setEnabled(final boolean value) {
        synchronized (GiTransportDebugSettings.class) {
            enabled = value;
            Path path = configPath();
            if (path != null) {
                save(path, value);
            }
        }
    }

    static boolean load(final Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return Boolean.parseBoolean(properties.getProperty(ENABLED_KEY, "false"));
        } catch (IOException | IllegalArgumentException exception) {
            Metallum.LOGGER.warn("Invalid G4 debug settings at {}; keeping the HUD disabled",
                    path, exception);
            return false;
        }
    }

    static boolean save(final Path path, final boolean value) {
        Properties properties = new Properties();
        properties.setProperty(ENABLED_KEY, Boolean.toString(value));
        Path temporary = null;
        try {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum G4 debug HUD (restart required)");
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save G4 debug settings at {}", path, exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static Path configPath() {
        try {
            Path directory = FabricLoader.getInstance().getConfigDir();
            return directory == null ? null : directory.resolve(FILE_NAME);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
