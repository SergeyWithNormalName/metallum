package com.metallum.client.hdr;

import com.metallum.Metallum;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record HdrConfig(
        HdrMode mode,
        HdrSourceEncoding sourceEncoding,
        float hdrStrength,
        float bloomStrength,
        boolean diagnosticPattern,
        boolean experimentalFp16
) {
    public static final float OUTPUT_HEADROOM = 8.0f;

    private static final String FILE_NAME = "metallum-hdr.properties";

    public static HdrConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties properties = defaults();

        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException exception) {
                Metallum.LOGGER.warn("Failed to read {}, using HDR defaults", path, exception);
            }
        } else {
            writeDefaults(path, properties);
        }

        String modeOverride = System.getProperty("metallum.hdr.mode");
        if (modeOverride != null && !modeOverride.isBlank()) {
            properties.setProperty("mode", modeOverride);
        }

        String diagnosticOverride = System.getProperty("metallum.hdr.diagnosticPattern");
        if (diagnosticOverride != null && !diagnosticOverride.isBlank()) {
            properties.setProperty("diagnosticPattern", diagnosticOverride);
        }

        return from(properties);
    }

    static HdrConfig from(final Properties properties) {
        return new HdrConfig(
                HdrMode.parse(properties.getProperty("mode")),
                HdrSourceEncoding.parse(properties.getProperty("sourceEncoding")),
                parseFloat(properties, "hdrStrength", 1.0f, 0.0f, 2.0f),
                parseFloat(properties, "bloomStrength", 0.22f, 0.0f, 1.0f),
                Boolean.parseBoolean(properties.getProperty("diagnosticPattern", "false")),
                Boolean.parseBoolean(properties.getProperty("experimentalFp16", "false"))
        );
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        properties.setProperty("mode", "auto");
        properties.setProperty("sourceEncoding", "srgb");
        properties.setProperty("hdrStrength", "1.0");
        properties.setProperty("bloomStrength", "0.22");
        properties.setProperty("diagnosticPattern", "false");
        return properties;
    }

    private static void writeDefaults(final Path path, final Properties properties) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum HDR settings (restart Minecraft after changing)");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to create default HDR config at {}", path, exception);
        }
    }

    private static float parseFloat(
            final Properties properties,
            final String key,
            final float fallback,
            final float minimum,
            final float maximum
    ) {
        try {
            float parsed = Float.parseFloat(properties.getProperty(key, Float.toString(fallback)));
            return Float.isFinite(parsed) ? Math.clamp(parsed, minimum, maximum) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties properties = new Properties();
        properties.setProperty("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        properties.setProperty("sourceEncoding", sourceEncoding.name().toLowerCase(java.util.Locale.ROOT));
        properties.setProperty("hdrStrength", Float.toString(hdrStrength));
        properties.setProperty("bloomStrength", Float.toString(bloomStrength));
        properties.setProperty("diagnosticPattern", Boolean.toString(diagnosticPattern));
        properties.setProperty("experimentalFp16", Boolean.toString(experimentalFp16));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum HDR settings");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save HDR config at {}", path, exception);
        }
    }

}
