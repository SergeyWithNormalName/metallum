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
        boolean diagnosticPattern
) {
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
                Boolean.parseBoolean(properties.getProperty("diagnosticPattern", "false"))
        );
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        properties.setProperty("mode", "auto");
        properties.setProperty("sourceEncoding", "srgb");
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

}
