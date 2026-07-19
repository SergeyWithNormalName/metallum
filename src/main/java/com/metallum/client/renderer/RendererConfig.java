package com.metallum.client.renderer;

import com.metallum.Metallum;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/** Advanced-lighting user policy; material, HDR and Spatial selection remain independent. */
public record RendererConfig(
        boolean improvedLighting,
        LightingPreset lightingPreset,
        boolean frameInterpolation,
        boolean voxelDebugChecksum
) {
    public static final int SCHEMA_VERSION = 3;
    private static final String FILE_NAME = "metallum-renderer.properties";

    public RendererConfig {
        if (lightingPreset == null) {
            throw new NullPointerException("lightingPreset");
        }
    }

    public static RendererConfig defaults() {
        return new RendererConfig(false, LightingPreset.BALANCED, false, false);
    }

    public static RendererConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        return load(path);
    }

    static RendererConfig load(final Path path) {
        if (!Files.isRegularFile(path)) {
            RendererConfig defaults = defaults();
            defaults.save(path);
            return defaults;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            Metallum.LOGGER.warn(
                    "Failed to read {}, using fail-closed renderer defaults without rewriting it",
                    path,
                    exception
            );
            return defaults();
        }

        String rawVersion = properties.getProperty("schemaVersion");
        if (rawVersion == null) {
            RendererConfig migrated = parseV1(properties);
            if (migrated == null) {
                Metallum.LOGGER.warn(
                        "Malformed v1 renderer config at {}; using defaults without rewriting it",
                        path
                );
                return defaults();
            }
            if (migrated.save(path)) {
                Metallum.LOGGER.info(
                        "Migrated renderer config {} from schema 1 to schema {}; Advanced lighting is Off",
                        path,
                        SCHEMA_VERSION
                );
            }
            return migrated;
        }
        String normalizedVersion = rawVersion.strip();
        if ("2".equals(normalizedVersion)) {
            RendererConfig migrated = parseV2(properties);
            if (migrated == null) {
                Metallum.LOGGER.warn(
                        "Malformed renderer config schema 2 at {}; using defaults without rewriting it",
                        path
                );
                return defaults();
            }
            if (migrated.save(path)) {
                Metallum.LOGGER.info(
                        "Migrated renderer config {} from schema 2 to {}; L5 GPU checksum is Off",
                        path,
                        SCHEMA_VERSION
                );
            }
            return migrated;
        }
        if (!Integer.toString(SCHEMA_VERSION).equals(normalizedVersion)) {
            Metallum.LOGGER.warn(
                    "Unknown renderer config schema '{}' at {}; using defaults without rewriting it",
                    rawVersion,
                    path
            );
            return defaults();
        }
        RendererConfig parsed = parseV3(properties);
        if (parsed == null) {
            Metallum.LOGGER.warn(
                    "Malformed renderer config schema {} at {}; using defaults without rewriting it",
                    SCHEMA_VERSION,
                    path
            );
            return defaults();
        }
        return parsed;
    }

    public RendererConfig withImprovedLighting(final boolean enabled) {
        return new RendererConfig(
                enabled, this.lightingPreset, this.frameInterpolation, this.voxelDebugChecksum
        );
    }

    public RendererConfig withLightingPreset(final LightingPreset preset) {
        return new RendererConfig(
                this.improvedLighting, preset, this.frameInterpolation, this.voxelDebugChecksum
        );
    }

    public RendererConfig withVoxelDebugChecksum(final boolean enabled) {
        return new RendererConfig(
                this.improvedLighting, this.lightingPreset, this.frameInterpolation, enabled
        );
    }

    public void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        this.save(path);
    }

    boolean save(final Path path) {
        return writeProperties(path, toProperties(this), "Metallum renderer settings (schema 3)");
    }

    static RendererConfig from(final Properties properties) {
        String version = properties.getProperty("schemaVersion");
        RendererConfig parsed = version == null
                ? parseV1(properties)
                : switch (version.strip()) {
                    case "2" -> parseV2(properties);
                    case "3" -> parseV3(properties);
                    default -> null;
                };
        return parsed != null ? parsed : defaults();
    }

    private static RendererConfig parseV1(final Properties properties) {
        Boolean oldLighting = strictBoolean(properties, "improvedLighting", false);
        Boolean interpolation = strictBoolean(properties, "frameInterpolation", false);
        LightingPreset preset = strictPreset(properties, "lightingPreset", LightingPreset.BALANCED);
        if (oldLighting == null || interpolation == null || preset == null) {
            return null;
        }
        return new RendererConfig(false, preset, interpolation, false);
    }

    private static RendererConfig parseV2(final Properties properties) {
        Boolean advanced = strictBoolean(properties, "improvedLighting", false);
        Boolean interpolation = strictBoolean(properties, "frameInterpolation", false);
        LightingPreset preset = strictPreset(properties, "lightingPreset", LightingPreset.BALANCED);
        if (advanced == null || interpolation == null || preset == null) {
            return null;
        }
        return new RendererConfig(advanced, preset, interpolation, false);
    }

    private static RendererConfig parseV3(final Properties properties) {
        Boolean advanced = strictBoolean(properties, "improvedLighting", false);
        Boolean interpolation = strictBoolean(properties, "frameInterpolation", false);
        Boolean voxelChecksum = strictBoolean(properties, "voxelDebugChecksum", false);
        LightingPreset preset = strictPreset(properties, "lightingPreset", LightingPreset.BALANCED);
        if (advanced == null || interpolation == null || voxelChecksum == null || preset == null) {
            return null;
        }
        return new RendererConfig(advanced, preset, interpolation, voxelChecksum);
    }

    private static Boolean strictBoolean(
            final Properties properties,
            final String key,
            final boolean defaultValue
    ) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
    }

    private static LightingPreset strictPreset(
            final Properties properties,
            final String key,
            final LightingPreset defaultValue
    ) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return LightingPreset.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Properties toProperties(final RendererConfig config) {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", Integer.toString(SCHEMA_VERSION));
        properties.setProperty("improvedLighting", Boolean.toString(config.improvedLighting));
        properties.setProperty(
                "lightingPreset",
                config.lightingPreset.name().toLowerCase(Locale.ROOT)
        );
        properties.setProperty("frameInterpolation", Boolean.toString(config.frameInterpolation));
        properties.setProperty("voxelDebugChecksum", Boolean.toString(config.voxelDebugChecksum));
        return properties;
    }

    private static boolean writeProperties(
            final Path path,
            final Properties properties,
            final String comment
    ) {
        Path temporary = null;
        try {
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, comment);
            }
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
            return true;
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to atomically write renderer config at {}", path, exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort cleanup; the write failure was already logged.
                }
            }
        }
    }
}
