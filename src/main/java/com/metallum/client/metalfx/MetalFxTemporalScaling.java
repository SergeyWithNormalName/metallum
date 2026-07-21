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

/** Persistent Temporal policy. Spatial and Temporal remain mutually exclusive renderer paths. */
public final class MetalFxTemporalScaling {
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

    private static final String FILE_NAME = "metallum-metalfx-temporal.properties";
    private static final AtomicBoolean RESIZE_PENDING = new AtomicBoolean();
    private static volatile TemporalScalingMode requestedMode = TemporalScalingMode.OFF;
    private static volatile boolean configLoaded;
    private static volatile boolean runtimeDisabled;

    private MetalFxTemporalScaling() {
    }

    public static TemporalScalingMode requestedMode() {
        ensureConfigLoaded();
        return requestedMode;
    }

    public static TemporalScalingMode effectiveMode() {
        ensureConfigLoaded();
        MetalDevice device = MetalDevice.getInstance();
        if (runtimeDisabled || device == null || !device.supportsTemporalScaling()) {
            return TemporalScalingMode.OFF;
        }
        return requestedMode;
    }

    public static boolean isActive() {
        return effectiveMode().enabled();
    }

    public static boolean isRequested() {
        return requestedMode().enabled();
    }

    public static boolean isSupported() {
        MetalDevice device = MetalDevice.getInstance();
        return device != null && device.supportsTemporalScaling();
    }

    public static boolean isRuntimeDisabled() {
        return runtimeDisabled;
    }

    public static void setRequestedMode(final TemporalScalingMode mode) {
        ensureConfigLoaded();
        TemporalScalingMode selected = mode == null ? TemporalScalingMode.OFF : mode;
        TemporalScalingMode previous = requestedMode;
        boolean wasRuntimeDisabled = runtimeDisabled;
        requestedMode = selected;
        runtimeDisabled = false;
        saveMode(selected);
        if (selected.enabled()) {
            MetalFxSpatialScaling.disableForTemporalSelection();
        }
        if (previous != selected || wasRuntimeDisabled) {
            requestRendererResize();
        }
    }

    static void disableForSpatialSelection() {
        ensureConfigLoaded();
        if (requestedMode == TemporalScalingMode.OFF && !runtimeDisabled) {
            return;
        }
        requestedMode = TemporalScalingMode.OFF;
        runtimeDisabled = false;
        saveMode(TemporalScalingMode.OFF);
        requestRendererResize();
    }

    public static void disableRuntimeAfterFailure(final Throwable cause) {
        if (runtimeDisabled) {
            return;
        }
        runtimeDisabled = true;
        Metallum.LOGGER.error(
                "MetalFX temporal scaling failed; falling back to native-resolution rendering",
                cause
        );
        requestRendererResize();
    }

    public static Dimensions dimensions(
            final TemporalScalingMode mode,
            final int displayWidth,
            final int displayHeight
    ) {
        int safeDisplayWidth = Math.max(displayWidth, 1);
        int safeDisplayHeight = Math.max(displayHeight, 1);
        TemporalScalingMode safeMode = mode == null ? TemporalScalingMode.OFF : mode;
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

    public static boolean consumePendingResize() {
        return RESIZE_PENDING.getAndSet(false);
    }

    static TemporalScalingMode from(final Properties properties) {
        return TemporalScalingMode.parse(properties.getProperty("mode"));
    }

    private static int scaledDimension(final int displayDimension, final float scale) {
        return Math.clamp(Math.round(displayDimension * scale), 1, displayDimension);
    }

    private static TemporalScalingMode loadMode() {
        try {
            Path path = configPath();
            if (!Files.isRegularFile(path)) {
                return TemporalScalingMode.OFF;
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
                return from(properties);
            } catch (IOException exception) {
                Metallum.LOGGER.warn("Failed to read {}, using MetalFX Temporal defaults", path, exception);
            }
        } catch (RuntimeException ignored) {
            return TemporalScalingMode.OFF;
        }
        return TemporalScalingMode.OFF;
    }

    private static void ensureConfigLoaded() {
        if (configLoaded) {
            return;
        }
        synchronized (MetalFxTemporalScaling.class) {
            if (!configLoaded) {
                requestedMode = loadMode();
                configLoaded = true;
            }
        }
    }

    private static void saveMode(final TemporalScalingMode mode) {
        final Path path;
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
                properties.store(writer, "Metallum MetalFX Temporal settings");
            }
        } catch (IOException exception) {
            Metallum.LOGGER.warn("Failed to save MetalFX Temporal config at {}", path, exception);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static void requestRendererResize() {
        RESIZE_PENDING.set(true);
    }
}
