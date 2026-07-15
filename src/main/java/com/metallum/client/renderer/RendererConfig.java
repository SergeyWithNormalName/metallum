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
        return load(path);
    }

    static RendererConfig load(final Path path) {
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

    public RendererConfig withImprovedLighting(final boolean enabled) {
        return new RendererConfig(enabled, this.lightingPreset, this.frameInterpolation);
    }

    public void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        this.save(path);
    }

    void save(final Path path) {
        Properties properties = new Properties();
        properties.setProperty("improvedLighting", Boolean.toString(this.improvedLighting));
        properties.setProperty(
                "lightingPreset",
                this.lightingPreset.name().toLowerCase(java.util.Locale.ROOT)
        );
        properties.setProperty("frameInterpolation", Boolean.toString(this.frameInterpolation));
        writeProperties(path, properties, "Metallum renderer settings");
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
        writeProperties(
                path,
                properties,
                "Metallum renderer settings (restart Minecraft after changing)"
        );
    }

    private static void writeProperties(
            final Path path,
            final Properties properties,
            final String comment
    ) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, comment);
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to write renderer config at {}", path, exception);
        }
    }
}
