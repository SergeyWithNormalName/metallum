package com.metallum.client.renderer;

import com.metallum.Metallum;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Lighting-only user policy; HDR and Spatial settings retain their existing files. */
public record RendererConfig(
        boolean improvedLighting,
        LightingPreset lightingPreset,
        boolean frameInterpolation
) {
    private static final String FILE_NAME = "metallum-renderer.properties";

    public RendererConfig {
        if (lightingPreset == null) {
            throw new NullPointerException("lightingPreset");
        }
    }

    public static RendererConfig defaults() {
        return new RendererConfig(false, LightingPreset.BALANCED, false);
    }

    public static RendererConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties properties = defaultProperties();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException exception) {
                Metallum.LOGGER.warn("Failed to read {}, using renderer defaults", path, exception);
            }
        } else {
            writeDefaults(path, properties);
        }
        return from(properties);
    }

    static RendererConfig from(final Properties properties) {
        return new RendererConfig(
                Boolean.parseBoolean(properties.getProperty("improvedLighting", "false")),
                LightingPreset.parse(properties.getProperty("lightingPreset")),
                Boolean.parseBoolean(properties.getProperty("frameInterpolation", "false"))
        );
    }

    private static Properties defaultProperties() {
        Properties properties = new Properties();
        properties.setProperty("improvedLighting", "false");
        properties.setProperty("lightingPreset", "balanced");
        properties.setProperty("frameInterpolation", "false");
        return properties;
    }

    private static void writeDefaults(final Path path, final Properties properties) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(
                        writer,
                        "Metallum renderer settings (restart Minecraft after changing)"
                );
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to create default renderer config at {}", path, exception);
        }
    }
}
