package com.metallum.client.renderer;

import com.metallum.Metallum;
import com.metallum.client.renderer.style.VisualStyle;
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

/** Advanced-lighting user policy; material, HDR, Visual Style and Spatial selection remain independent. */
public record RendererConfig(
        boolean improvedLighting,
        LightingPreset lightingPreset,
        boolean frameInterpolation,
        boolean voxelDebugChecksum,
        VisualStyle visualStyle,
        GlobalIlluminationMode globalIllumination
) {
    public static final int SCHEMA_VERSION = 5;
    private static final String FILE_NAME = "metallum-renderer.properties";

    /** Disk-load provenance: defaults are safe for an interactive client but invalidate a benchmark. */
    public enum LoadDisposition {
        CURRENT,
        CREATED_DEFAULTS,
        MIGRATED_V1,
        MIGRATED_V2,
        MIGRATED_V3,
        MIGRATED_V4,
        FALLBACK_IO,
        FALLBACK_UNKNOWN_SCHEMA,
        FALLBACK_MALFORMED
    }

    public record LoadStatus(String parsedSchema, boolean defaultsUsed, LoadDisposition disposition) {
        public LoadStatus {
            parsedSchema = parsedSchema == null || parsedSchema.isBlank() ? "missing" : parsedSchema.strip();
            if (disposition == null) {
                throw new NullPointerException("disposition");
            }
        }
    }

    private record LoadResult(RendererConfig config, LoadStatus status) {
    }

    private static volatile LoadStatus lastLoadStatus = new LoadStatus(
            "not-loaded", true, LoadDisposition.FALLBACK_IO
    );

    public RendererConfig {
        if (lightingPreset == null) {
            throw new NullPointerException("lightingPreset");
        }
        if (visualStyle == null) {
            throw new NullPointerException("visualStyle");
        }
        if (globalIllumination == null) {
            throw new NullPointerException("globalIllumination");
        }
    }

    public static RendererConfig defaults() {
        return new RendererConfig(
                false, LightingPreset.BALANCED, false, false,
                VisualStyle.VANILLA, GlobalIlluminationMode.OFF
        );
    }

    public static RendererConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        return load(path);
    }

    /**
     * Reads the persistent policy for the early mixin gate without creating, migrating or
     * rewriting the file and without changing benchmark load provenance.
     */
    public static RendererConfig loadForStartupGate() {
        // Plain JVM contract tests load renderer classes without a Fabric game directory.
        // Some Fabric Loader versions fail inside getConfigDir() before they can return null,
        // so the complete lookup must remain behind this fail-closed boundary. A real client
        // startup supplies the directory before mixin selection.
        try {
            Path configDirectory = FabricLoader.getInstance().getConfigDir();
            return configDirectory == null
                    ? defaults()
                    : loadForStartupGate(configDirectory.resolve(FILE_NAME));
        } catch (RuntimeException | LinkageError unavailableLoader) {
            return defaults();
        }
    }

    static RendererConfig loadForStartupGate(final Path path) {
        if (!Files.isRegularFile(path)) {
            return defaults();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return from(properties);
        } catch (IOException | IllegalArgumentException exception) {
            Metallum.LOGGER.warn(
                    "Failed to read {} for the startup mixin gate; keeping global illumination off",
                    path,
                    exception
            );
            return defaults();
        }
    }

    static RendererConfig load(final Path path) {
        LoadResult loaded = loadWithStatus(path);
        lastLoadStatus = loaded.status();
        return loaded.config();
    }

    /** Last file provenance consumed by startup/benchmark admission. */
    public static LoadStatus lastLoadStatus() {
        return lastLoadStatus;
    }

    static LoadResult loadWithStatus(final Path path) {
        if (!Files.isRegularFile(path)) {
            RendererConfig defaults = defaults();
            defaults.save(path);
            return loaded(defaults, "missing", true, LoadDisposition.CREATED_DEFAULTS);
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
            return loaded(defaults(), "unreadable", true, LoadDisposition.FALLBACK_IO);
        }

        String rawVersion = properties.getProperty("schemaVersion");
        if (rawVersion == null) {
            RendererConfig migrated = parseV1(properties);
            if (migrated == null) {
                Metallum.LOGGER.warn(
                        "Malformed v1 renderer config at {}; using defaults without rewriting it",
                        path
                );
                return loaded(defaults(), "missing", true, LoadDisposition.FALLBACK_MALFORMED);
            }
            if (migrated.save(path)) {
                Metallum.LOGGER.info(
                        "Migrated renderer config {} from schema 1 to schema {}; Advanced lighting is Off, style is Vanilla",
                        path,
                        SCHEMA_VERSION
                );
            }
            return loaded(migrated, "1", false, LoadDisposition.MIGRATED_V1);
        }
        String normalizedVersion = rawVersion.strip();
        if ("2".equals(normalizedVersion)) {
            RendererConfig migrated = parseV2(properties);
            if (migrated == null) {
                Metallum.LOGGER.warn(
                        "Malformed renderer config schema 2 at {}; using defaults without rewriting it",
                        path
                );
                return loaded(defaults(), normalizedVersion, true, LoadDisposition.FALLBACK_MALFORMED);
            }
            if (migrated.save(path)) {
                Metallum.LOGGER.info(
                        "Migrated renderer config {} from schema 2 to {}; L5 GPU checksum is Off, style is Vanilla",
                        path,
                        SCHEMA_VERSION
                );
            }
            return loaded(migrated, "2", false, LoadDisposition.MIGRATED_V2);
        }
        if ("3".equals(normalizedVersion)) {
            RendererConfig migrated = parseV3(properties);
            if (migrated == null) {
                Metallum.LOGGER.warn(
                        "Malformed renderer config schema 3 at {}; using defaults without rewriting it",
                        path
                );
                return loaded(defaults(), normalizedVersion, true, LoadDisposition.FALLBACK_MALFORMED);
            }
            if (migrated.save(path)) {
                Metallum.LOGGER.info(
                        "Migrated renderer config {} from schema 3 to {}; style is Vanilla",
                        path,
                        SCHEMA_VERSION
                );
            }
            return loaded(migrated, "3", false, LoadDisposition.MIGRATED_V3);
        }
        if ("4".equals(normalizedVersion)) {
            RendererConfig migrated = parseV4(properties);
            if (migrated == null) {
                Metallum.LOGGER.warn(
                        "Malformed renderer config schema 4 at {}; using defaults without rewriting it",
                        path
                );
                return loaded(defaults(), normalizedVersion, true, LoadDisposition.FALLBACK_MALFORMED);
            }
            if (migrated.save(path)) {
                Metallum.LOGGER.info(
                        "Migrated renderer config {} from schema 4 to {}; global illumination is Off",
                        path,
                        SCHEMA_VERSION
                );
            }
            return loaded(migrated, "4", false, LoadDisposition.MIGRATED_V4);
        }
        if (!Integer.toString(SCHEMA_VERSION).equals(normalizedVersion)) {
            Metallum.LOGGER.warn(
                    "Unknown renderer config schema '{}' at {}; using defaults without rewriting it",
                    rawVersion,
                    path
            );
            return loaded(defaults(), normalizedVersion, true, LoadDisposition.FALLBACK_UNKNOWN_SCHEMA);
        }
        RendererConfig parsed = parseV5(properties);
        if (parsed == null) {
            Metallum.LOGGER.warn(
                    "Malformed renderer config schema {} at {}; using defaults without rewriting it",
                    SCHEMA_VERSION,
                    path
            );
            return loaded(defaults(), normalizedVersion, true, LoadDisposition.FALLBACK_MALFORMED);
        }
        return loaded(parsed, normalizedVersion, false, LoadDisposition.CURRENT);
    }

    private static LoadResult loaded(
            final RendererConfig config,
            final String parsedSchema,
            final boolean defaultsUsed,
            final LoadDisposition disposition
    ) {
        return new LoadResult(config, new LoadStatus(parsedSchema, defaultsUsed, disposition));
    }

    public RendererConfig withImprovedLighting(final boolean enabled) {
        return new RendererConfig(
                enabled, this.lightingPreset, this.frameInterpolation, this.voxelDebugChecksum,
                this.visualStyle, this.globalIllumination
        );
    }

    public RendererConfig withLightingPreset(final LightingPreset preset) {
        return new RendererConfig(
                this.improvedLighting, preset, this.frameInterpolation, this.voxelDebugChecksum,
                this.visualStyle, this.globalIllumination
        );
    }

    public RendererConfig withFrameInterpolation(final boolean enabled) {
        return new RendererConfig(
                this.improvedLighting, this.lightingPreset, enabled, this.voxelDebugChecksum,
                this.visualStyle, this.globalIllumination
        );
    }

    public RendererConfig withVoxelDebugChecksum(final boolean enabled) {
        return new RendererConfig(
                this.improvedLighting, this.lightingPreset, this.frameInterpolation, enabled,
                this.visualStyle, this.globalIllumination
        );
    }

    public RendererConfig withVisualStyle(final VisualStyle style) {
        return new RendererConfig(
                this.improvedLighting, this.lightingPreset, this.frameInterpolation,
                this.voxelDebugChecksum, style, this.globalIllumination
        );
    }

    public RendererConfig withGlobalIllumination(final GlobalIlluminationMode mode) {
        return new RendererConfig(
                this.improvedLighting, this.lightingPreset, this.frameInterpolation,
                this.voxelDebugChecksum, this.visualStyle, mode
        );
    }

    public void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        this.save(path);
    }

    boolean save(final Path path) {
        return writeProperties(path, toProperties(this), "Metallum renderer settings (schema 5)");
    }

    static RendererConfig from(final Properties properties) {
        String version = properties.getProperty("schemaVersion");
        RendererConfig parsed = version == null
                ? parseV1(properties)
                : switch (version.strip()) {
                    case "2" -> parseV2(properties);
                    case "3" -> parseV3(properties);
                    case "4" -> parseV4(properties);
                    case "5" -> parseV5(properties);
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
        return new RendererConfig(
                false, preset, interpolation, false,
                VisualStyle.VANILLA, GlobalIlluminationMode.OFF
        );
    }

    private static RendererConfig parseV2(final Properties properties) {
        Boolean advanced = strictBoolean(properties, "improvedLighting", false);
        Boolean interpolation = strictBoolean(properties, "frameInterpolation", false);
        LightingPreset preset = strictPreset(properties, "lightingPreset", LightingPreset.BALANCED);
        if (advanced == null || interpolation == null || preset == null) {
            return null;
        }
        return new RendererConfig(
                advanced, preset, interpolation, false,
                VisualStyle.VANILLA, GlobalIlluminationMode.OFF
        );
    }

    private static RendererConfig parseV3(final Properties properties) {
        Boolean advanced = strictBoolean(properties, "improvedLighting", false);
        Boolean interpolation = strictBoolean(properties, "frameInterpolation", false);
        Boolean voxelChecksum = strictBoolean(properties, "voxelDebugChecksum", false);
        LightingPreset preset = strictPreset(properties, "lightingPreset", LightingPreset.BALANCED);
        if (advanced == null || interpolation == null || voxelChecksum == null || preset == null) {
            return null;
        }
        return new RendererConfig(
                advanced, preset, interpolation, voxelChecksum,
                VisualStyle.VANILLA, GlobalIlluminationMode.OFF
        );
    }

    private static RendererConfig parseV4(final Properties properties) {
        Boolean advanced = strictBoolean(properties, "improvedLighting", false);
        Boolean interpolation = strictBoolean(properties, "frameInterpolation", false);
        Boolean voxelChecksum = strictBoolean(properties, "voxelDebugChecksum", false);
        LightingPreset preset = strictPreset(properties, "lightingPreset", LightingPreset.BALANCED);
        VisualStyle style = strictStyle(properties, "visualStyle", VisualStyle.VANILLA);
        if (advanced == null || interpolation == null || voxelChecksum == null || preset == null || style == null) {
            return null;
        }
        return new RendererConfig(
                advanced, preset, interpolation, voxelChecksum,
                style, GlobalIlluminationMode.OFF
        );
    }

    private static RendererConfig parseV5(final Properties properties) {
        RendererConfig base = parseV4(properties);
        GlobalIlluminationMode gi = strictGlobalIlluminationMode(
                properties, "globalIllumination", GlobalIlluminationMode.OFF
        );
        if (base == null || gi == null) {
            return null;
        }
        return new RendererConfig(
                base.improvedLighting, base.lightingPreset, base.frameInterpolation,
                base.voxelDebugChecksum, base.visualStyle, gi
        );
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

    private static VisualStyle strictStyle(
            final Properties properties,
            final String key,
            final VisualStyle defaultValue
    ) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return VisualStyle.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static GlobalIlluminationMode strictGlobalIlluminationMode(
            final Properties properties,
            final String key,
            final GlobalIlluminationMode defaultValue
    ) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return GlobalIlluminationMode.valueOf(value.strip().toUpperCase(Locale.ROOT));
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
        properties.setProperty("visualStyle", config.visualStyle.persistentName());
        properties.setProperty("globalIllumination", config.globalIllumination.persistentName());
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
