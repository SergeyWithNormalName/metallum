package com.metallum.client.metalfx;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalDevice;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared policy for the Sodium control, render-target sizing and native scaler. */
public final class MetalFxSpatialScaling {
    public record Dimensions(int displayWidth, int displayHeight, int renderWidth, int renderHeight) {
        public float actualWidthScale() {
            return this.renderWidth / (float) this.displayWidth;
        }

        public float actualHeightScale() {
            return this.renderHeight / (float) this.displayHeight;
        }

        public float actualPixelScale() {
            return (this.renderWidth * (float) this.renderHeight)
                    / (this.displayWidth * (float) this.displayHeight);
        }
    }

    private static final String FILE_NAME = "metallum-metalfx.properties";
    private static volatile SpatialScalingMode requestedMode = SpatialScalingMode.OFF;
    private static volatile boolean configLoaded;
    private static volatile int configuredDisplayWidth;
    private static volatile int configuredDisplayHeight;
    private static volatile boolean runtimeDisabled;
    private static final AtomicBoolean RESIZE_PENDING = new AtomicBoolean();

    private MetalFxSpatialScaling() {
    }

    public static SpatialScalingMode requestedMode() {
        ensureConfigLoaded();
        return requestedMode;
    }

    public static SpatialScalingMode effectiveMode() {
        ensureConfigLoaded();
        SpatialScalingMode mode = requestedMode;
        MetalDevice device = MetalDevice.getInstance();
        return mode.enabled() && !runtimeDisabled && device != null && device.supportsSpatialScaling()
                ? mode
                : SpatialScalingMode.OFF;
    }

    public static boolean isActive() {
        return effectiveMode().enabled();
    }

    public static boolean isSupported() {
        MetalDevice device = MetalDevice.getInstance();
        return device != null && device.supportsSpatialScaling();
    }

    public static boolean isRuntimeDisabled() {
        return runtimeDisabled;
    }

    public static void setRequestedMode(final SpatialScalingMode mode) {
        ensureConfigLoaded();
        SpatialScalingMode nonNullMode = mode == null ? SpatialScalingMode.OFF : mode;
        SpatialScalingMode previous = requestedMode;
        boolean wasRuntimeDisabled = runtimeDisabled;
        requestedMode = nonNullMode;
        runtimeDisabled = false;
        saveMode(nonNullMode);
        if (previous != nonNullMode || wasRuntimeDisabled) {
            requestRendererResize();
        }
    }

    public static void disableRuntimeAfterFailure(final Throwable cause) {
        if (runtimeDisabled) {
            return;
        }
        runtimeDisabled = true;
        Metallum.LOGGER.error(
                "MetalFX spatial scaling failed; falling back to native-resolution rendering",
                cause
        );
        requestRendererResize();
    }

    public static Dimensions dimensions(
            final SpatialScalingMode mode,
            final int displayWidth,
            final int displayHeight
    ) {
        int safeDisplayWidth = Math.max(displayWidth, 1);
        int safeDisplayHeight = Math.max(displayHeight, 1);
        SpatialScalingMode safeMode = mode == null ? SpatialScalingMode.OFF : mode;
        if (!safeMode.enabled()) {
            return new Dimensions(safeDisplayWidth, safeDisplayHeight, safeDisplayWidth, safeDisplayHeight);
        }
        return new Dimensions(
                safeDisplayWidth,
                safeDisplayHeight,
                scaledDimension(safeDisplayWidth, safeMode.linearScale()),
                scaledDimension(safeDisplayHeight, safeMode.linearScale())
        );
    }

    public static Dimensions effectiveDimensions(final int displayWidth, final int displayHeight) {
        return dimensions(effectiveMode(), displayWidth, displayHeight);
    }

    public static void recordDisplaySize(final int width, final int height) {
        configuredDisplayWidth = Math.max(width, 1);
        configuredDisplayHeight = Math.max(height, 1);
    }

    public static int configuredDisplayWidth(final int fallback) {
        return configuredDisplayWidth > 0 ? configuredDisplayWidth : fallback;
    }

    public static int configuredDisplayHeight(final int fallback) {
        return configuredDisplayHeight > 0 ? configuredDisplayHeight : fallback;
    }

    public static boolean consumePendingResize() {
        return RESIZE_PENDING.getAndSet(false);
    }

    static SpatialScalingMode from(final Properties properties) {
        return SpatialScalingMode.parse(properties.getProperty("mode"));
    }

    private static int scaledDimension(final int displayDimension, final float scale) {
        // MetalFX accepts arbitrary dimensions. Exact rounding preserves the
        // display aspect ratio better than independent 8-pixel alignment.
        return Math.clamp(Math.round(displayDimension * scale), 1, displayDimension);
    }

    private static SpatialScalingMode loadMode() {
        try {
            Path path = configPath();
            if (!Files.isRegularFile(path)) {
                return SpatialScalingMode.OFF;
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
                return from(properties);
            } catch (IOException exception) {
                Metallum.LOGGER.warn("Failed to read {}, using MetalFX defaults", path, exception);
            }
        } catch (RuntimeException ignored) {
            // Dependency-free unit tests do not bootstrap FabricLoader.
            return SpatialScalingMode.OFF;
        }
        return SpatialScalingMode.OFF;
    }

    private static void ensureConfigLoaded() {
        if (configLoaded) {
            return;
        }
        synchronized (MetalFxSpatialScaling.class) {
            if (!configLoaded) {
                requestedMode = loadMode();
                configLoaded = true;
            }
        }
    }

    private static void saveMode(final SpatialScalingMode mode) {
        Path path;
        try {
            path = configPath();
        } catch (RuntimeException exception) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("mode", mode.name().toLowerCase(Locale.ROOT));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "Metallum MetalFX settings");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save MetalFX config at {}", path, exception);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static void requestRendererResize() {
        // Mode changes and native failures may be reported while the GUI is
        // still encoding the current frame. Reallocating MainTarget there can
        // invalidate native texture handles, so GameRenderer consumes this at
        // the HEAD of the next frame.
        RESIZE_PENDING.set(true);
    }
}
