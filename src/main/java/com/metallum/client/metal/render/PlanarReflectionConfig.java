package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.gi.receiver.GiReceiverRuntime;
import com.metallum.client.lighting.reflection.CloudReflectionConfig;
import com.metallum.client.lighting.reflection.VertexReflectionExperiment;
import com.metallum.client.renderer.PlanarReflectionLayout;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Runtime configuration for Planar Reflections. */
public final class PlanarReflectionConfig {
    public enum CaptureMode {
        DISABLED,
        CLOUDS_ONLY,
        FULL_PLANAR
    }

    public static final String PROPERTY_ENABLED = "metallum.planar_reflections";
    public static final String PROPERTY_RESOLUTION = "metallum.planar_reflection.resolution";
    public static final String PROPERTY_UPDATE_INTERVAL = "metallum.planar_reflection.update_interval";
    private static final String FILE_NAME = "metallum-water-planar-reflections.properties";
    private static final String ENABLED_KEY = "enabled";

    private static volatile Boolean enabled;
    private static volatile float resolutionScale = parseInitialResolution();

    private PlanarReflectionConfig() {
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
        synchronized (PlanarReflectionConfig.class) {
            if (enabled == null) {
                enabled = load(path());
            }
            return enabled;
        }
    }

    /** Planar capture and the voxel receiver are mutually exclusive water architectures. */
    public static boolean isRuntimeEnabled() {
        return captureMode() == CaptureMode.FULL_PLANAR;
    }

    static boolean runtimeEnabled(final boolean planarEnabled, final boolean voxelEnabled) {
        return captureMode(planarEnabled, voxelEnabled) == CaptureMode.FULL_PLANAR;
    }

    /**
     * Resolves the shared reflected-target owner.
     *
     * <p>The voxel receiver never renders reflected terrain. It does, however, need a stable
     * image of Minecraft's real cloud geometry; that narrow capture may safely share the dormant
     * planar target and binding without mixing the two world-reflection architectures.</p>
     */
    public static CaptureMode captureMode() {
        return captureMode(
                isEnabled(),
                VertexReflectionExperiment.isLayoutEnabled(),
                CloudReflectionConfig.isEnabled(),
                GiReceiverRuntime.isRequested()
        );
    }

    static CaptureMode captureMode(final boolean planarEnabled, final boolean voxelEnabled) {
        return captureMode(planarEnabled, voxelEnabled, true);
    }

    static CaptureMode captureMode(
            final boolean planarEnabled,
            final boolean voxelEnabled,
            final boolean cloudReflectionsEnabled
    ) {
        return captureMode(planarEnabled, voxelEnabled, cloudReflectionsEnabled, false);
    }

    static CaptureMode captureMode(
            final boolean planarEnabled,
            final boolean voxelEnabled,
            final boolean cloudReflectionsEnabled,
            final boolean giReceiverRequested
    ) {
        if (voxelEnabled) {
            return cloudReflectionsEnabled ? CaptureMode.CLOUDS_ONLY : CaptureMode.DISABLED;
        }
        // G5's cost and shader contract cover one main-view terrain raster only.  Keep a
        // live-toggled planar option dormant for the lifetime of a G5 process so reflected
        // terrain can never add an unmeasured second set of vertex field reads.
        if (giReceiverRequested) {
            return CaptureMode.DISABLED;
        }
        return planarEnabled ? CaptureMode.FULL_PLANAR : CaptureMode.DISABLED;
    }

    public static void setEnabled(final boolean enabled) {
        synchronized (PlanarReflectionConfig.class) {
            PlanarReflectionConfig.enabled = enabled;
            save(path(), enabled);
        }
    }

    public static float resolutionScale() {
        return resolutionScale;
    }

    public static void setResolutionScale(final float scale) {
        resolutionScale = Math.clamp(scale, 0.25f, 1.0f);
    }

    public static int updateIntervalFrames() {
        String value = System.getProperty(PROPERTY_UPDATE_INTERVAL);
        if (value == null) {
            return PlanarReflectionLayout.DEFAULT_UPDATE_INTERVAL_FRAMES;
        }
        try {
            return Math.clamp(Integer.parseInt(value), 1, 4);
        } catch (NumberFormatException ignored) {
            return PlanarReflectionLayout.DEFAULT_UPDATE_INTERVAL_FRAMES;
        }
    }

    private static float parseInitialResolution() {
        String prop = System.getProperty(PROPERTY_RESOLUTION);
        if (prop != null) {
            try {
                return Math.clamp(Float.parseFloat(prop), 0.25f, 1.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        return PlanarReflectionLayout.DEFAULT_RESOLUTION_SCALE;
    }

    static boolean load(final Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return Boolean.parseBoolean(properties.getProperty(ENABLED_KEY, "false"));
        } catch (IOException | IllegalArgumentException exception) {
            Metallum.LOGGER.warn("Failed to read {}; water planar reflections remain disabled", path, exception);
            return false;
        }
    }

    private static void save(final Path path, final boolean value) {
        Properties properties = new Properties();
        properties.setProperty(ENABLED_KEY, Boolean.toString(value));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum live water planar reflections (experimental)");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save water planar reflection config at {}", path, exception);
        }
    }

    private static Path path() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException uninitialized) {
            return Path.of("config").resolve(FILE_NAME);
        }
    }
}
