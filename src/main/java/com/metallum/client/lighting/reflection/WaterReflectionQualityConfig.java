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

/**
 * Persistent, restart-gated quality choices for the voxel water-reflection receiver.
 *
 * <p>The three choices deliberately use their own file rather than changing the
 * experiment opt-in. This keeps a user's reflection-quality comparison reproducible
 * while the experiment itself remains independently disabled by default.</p>
 */
public final class WaterReflectionQualityConfig {
    public static final String FACE_AWARE_APPEARANCE_PROPERTY =
            "metallum.waterReflection.faceAwareAppearance";
    public static final String FIRST_SURFACE_BIASED_INTEGRATION_PROPERTY =
            "metallum.waterReflection.firstSurfaceBiasedIntegration";
    public static final String REPRESENTATION_CONFIDENCE_PROPERTY =
            "metallum.waterReflection.representationConfidence";
    private static final String FILE_NAME = "metallum-water-reflection-quality.properties";
    private static final String FACE_AWARE_APPEARANCE_KEY = "faceAwareAppearance";
    private static final String FIRST_SURFACE_BIASED_INTEGRATION_KEY = "firstSurfaceBiasedIntegration";
    private static final String REPRESENTATION_CONFIDENCE_KEY = "representationConfidence";

    private static volatile Settings settings;

    private WaterReflectionQualityConfig() {
    }

    public static boolean isFaceAwareAppearanceEnabled() {
        Boolean override = propertyOverride(FACE_AWARE_APPEARANCE_PROPERTY);
        return override != null ? override : settings().faceAwareAppearance();
    }

    public static boolean isFirstSurfaceBiasedIntegrationEnabled() {
        Boolean override = propertyOverride(FIRST_SURFACE_BIASED_INTEGRATION_PROPERTY);
        return override != null ? override : settings().firstSurfaceBiasedIntegration();
    }

    public static boolean isRepresentationConfidenceEnabled() {
        Boolean override = propertyOverride(REPRESENTATION_CONFIDENCE_PROPERTY);
        return override != null ? override : settings().representationConfidence();
    }

    public static void setFaceAwareAppearanceEnabled(final boolean value) {
        update(current -> new Settings(
                value,
                current.firstSurfaceBiasedIntegration(),
                current.representationConfidence()
        ));
    }

    public static void setFirstSurfaceBiasedIntegrationEnabled(final boolean value) {
        update(current -> new Settings(
                current.faceAwareAppearance(),
                value,
                current.representationConfidence()
        ));
    }

    public static void setRepresentationConfidenceEnabled(final boolean value) {
        update(current -> new Settings(
                current.faceAwareAppearance(),
                current.firstSurfaceBiasedIntegration(),
                value
        ));
    }

    static Settings from(final Properties properties) {
        return new Settings(
                enabledByDefault(properties, FACE_AWARE_APPEARANCE_KEY),
                enabledByDefault(properties, FIRST_SURFACE_BIASED_INTEGRATION_KEY),
                enabledByDefault(properties, REPRESENTATION_CONFIDENCE_KEY)
        );
    }

    static Settings load(final Path path) {
        if (!Files.isRegularFile(path)) {
            return Settings.DEFAULT;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return from(properties);
        } catch (IOException | IllegalArgumentException exception) {
            Metallum.LOGGER.warn("Failed to read {}; keeping all water-reflection quality options enabled", path, exception);
            return Settings.DEFAULT;
        }
    }

    private static Settings settings() {
        Settings current = settings;
        if (current != null) {
            return current;
        }
        synchronized (WaterReflectionQualityConfig.class) {
            if (settings == null) {
                try {
                    settings = load(path());
                } catch (RuntimeException unavailableLoader) {
                    // Dependency-free shader/source tests run before Fabric assigns game/config dirs.
                    settings = Settings.DEFAULT;
                }
            }
            return settings;
        }
    }

    private static void update(final java.util.function.UnaryOperator<Settings> update) {
        synchronized (WaterReflectionQualityConfig.class) {
            Settings updated = update.apply(settings());
            settings = updated;
            save(path(), updated);
        }
    }

    private static boolean enabledByDefault(final Properties properties, final String key) {
        return !"false".equalsIgnoreCase(properties.getProperty(key));
    }

    /**
     * A launch-only override keeps automated A/B runs independent of a user's Sodium settings.
     * The Sodium values remain the source of truth when no JVM property is supplied.
     */
    private static Boolean propertyOverride(final String key) {
        String value = System.getProperty(key);
        return value == null ? null : !"false".equalsIgnoreCase(value.trim());
    }

    private static void save(final Path path, final Settings value) {
        Properties properties = new Properties();
        properties.setProperty(FACE_AWARE_APPEARANCE_KEY, Boolean.toString(value.faceAwareAppearance()));
        properties.setProperty(
                FIRST_SURFACE_BIASED_INTEGRATION_KEY,
                Boolean.toString(value.firstSurfaceBiasedIntegration())
        );
        properties.setProperty(
                REPRESENTATION_CONFIDENCE_KEY,
                Boolean.toString(value.representationConfidence())
        );
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum water reflection quality (restart required)");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save water-reflection quality config at {}", path, exception);
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    static record Settings(
            boolean faceAwareAppearance,
            boolean firstSurfaceBiasedIntegration,
            boolean representationConfidence
    ) {
        static final Settings DEFAULT = new Settings(true, true, true);
    }
}
