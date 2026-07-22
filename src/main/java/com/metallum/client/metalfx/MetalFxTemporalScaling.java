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
    private static volatile TemporalAlgorithmPolicy requestedAlgorithmPolicy = TemporalAlgorithmPolicy.AUTO;
    private static volatile boolean configLoaded;
    private static volatile boolean runtimeDisabled;
    private static volatile TemporalScalingMode benchmarkOverride;
    private static volatile TemporalAlgorithmPolicy benchmarkAlgorithmOverride;

    private MetalFxTemporalScaling() {
    }

    public static TemporalScalingMode requestedMode() {
        ensureConfigLoaded();
        return selectRequestedMode(requestedMode, benchmarkOverride);
    }

    public static TemporalScalingMode effectiveMode() {
        ensureConfigLoaded();
        MetalDevice device = MetalDevice.getInstance();
        return selectEffectiveMode(
                requestedMode,
                benchmarkOverride,
                runtimeDisabled,
                device != null && device.supportsTemporalScaling()
        );
    }

    /**
     * Persisted algorithm selection shown by Sodium. It has no effect while
     * Temporal is off, but is retained so users can prepare a preset before
     * enabling it.
     */
    public static TemporalAlgorithmPolicy requestedAlgorithmPolicy() {
        ensureConfigLoaded();
        return selectRequestedAlgorithmPolicy(
                requestedAlgorithmPolicy,
                benchmarkAlgorithmOverride
        );
    }

    public static boolean isActive() {
        return effectiveMode().enabled();
    }

    public static boolean isRequested() {
        ensureConfigLoaded();
        return selectRequestedMode(requestedMode, benchmarkOverride).enabled();
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
        saveSettings(selected, requestedAlgorithmPolicy);
        if (selected.enabled()) {
            MetalFxSpatialScaling.disableForTemporalSelection();
        }
        if (previous != selected || wasRuntimeDisabled) {
            requestRendererResize();
        }
    }

    /** Changes only the resolver policy and invalidates the current Temporal generation. */
    public static void setRequestedAlgorithmPolicy(final TemporalAlgorithmPolicy policy) {
        ensureConfigLoaded();
        TemporalAlgorithmPolicy selected = policy == null ? TemporalAlgorithmPolicy.AUTO : policy;
        TemporalAlgorithmPolicy previous = requestedAlgorithmPolicy;
        boolean wasRuntimeDisabled = runtimeDisabled;
        requestedAlgorithmPolicy = selected;
        runtimeDisabled = false;
        saveSettings(requestedMode, selected);
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
        saveSettings(TemporalScalingMode.OFF, requestedAlgorithmPolicy);
        requestRendererResize();
    }

    /** Installs a non-persistent concrete preset for the automated benchmark. */
    public static void setBenchmarkOverride(final TemporalScalingMode mode) {
        ensureConfigLoaded();
        TemporalScalingMode concreteMode = mode == null ? TemporalScalingMode.OFF : mode;
        TemporalScalingMode previous = benchmarkOverride;
        TemporalAlgorithmPolicy previousAlgorithm = benchmarkAlgorithmOverride;
        boolean wasRuntimeDisabled = runtimeDisabled;
        benchmarkOverride = concreteMode;
        // Benchmarks must not inherit a user's resolver A/B selection: existing
        // TEMPORAL_* names remain the documented AUTO baseline.
        benchmarkAlgorithmOverride = TemporalAlgorithmPolicy.AUTO;
        runtimeDisabled = false;
        if (previous != concreteMode || previousAlgorithm != benchmarkAlgorithmOverride || wasRuntimeDisabled) {
            requestRendererResize();
        }
    }

    /** Restores the persisted user policy after an automated benchmark. */
    public static void clearBenchmarkOverride() {
        ensureConfigLoaded();
        if (benchmarkOverride == null && benchmarkAlgorithmOverride == null) {
            return;
        }
        benchmarkOverride = null;
        benchmarkAlgorithmOverride = null;
        runtimeDisabled = false;
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

    static TemporalAlgorithmPolicy algorithmFrom(final Properties properties) {
        return TemporalAlgorithmPolicy.parse(properties.getProperty("algorithm"));
    }

    static TemporalScalingMode selectRequestedMode(
            final TemporalScalingMode persistedMode,
            final TemporalScalingMode overrideMode
    ) {
        return overrideMode != null
                ? overrideMode
                : (persistedMode == null ? TemporalScalingMode.OFF : persistedMode);
    }

    static TemporalAlgorithmPolicy selectRequestedAlgorithmPolicy(
            final TemporalAlgorithmPolicy persistedPolicy,
            final TemporalAlgorithmPolicy overridePolicy
    ) {
        return overridePolicy != null
                ? overridePolicy
                : (persistedPolicy == null ? TemporalAlgorithmPolicy.AUTO : persistedPolicy);
    }

    /** Applies the one canonical requested-mode selector before runtime admission. */
    static TemporalScalingMode selectEffectiveMode(
            final TemporalScalingMode persistedMode,
            final TemporalScalingMode overrideMode,
            final boolean disabledAtRuntime,
            final boolean supportedByDevice
    ) {
        TemporalScalingMode selected = selectRequestedMode(persistedMode, overrideMode);
        return disabledAtRuntime || !supportedByDevice ? TemporalScalingMode.OFF : selected;
    }

    private static int scaledDimension(final int displayDimension, final float scale) {
        return Math.clamp(Math.round(displayDimension * scale), 1, displayDimension);
    }

    private record Settings(TemporalScalingMode mode, TemporalAlgorithmPolicy algorithmPolicy) {
    }

    private static Settings loadSettings() {
        try {
            Path path = configPath();
            if (!Files.isRegularFile(path)) {
                return new Settings(TemporalScalingMode.OFF, TemporalAlgorithmPolicy.AUTO);
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
                return new Settings(from(properties), algorithmFrom(properties));
            } catch (IOException exception) {
                Metallum.LOGGER.warn("Failed to read {}, using MetalFX Temporal defaults", path, exception);
            }
        } catch (RuntimeException ignored) {
            return new Settings(TemporalScalingMode.OFF, TemporalAlgorithmPolicy.AUTO);
        }
        return new Settings(TemporalScalingMode.OFF, TemporalAlgorithmPolicy.AUTO);
    }

    private static void ensureConfigLoaded() {
        if (configLoaded) {
            return;
        }
        synchronized (MetalFxTemporalScaling.class) {
            if (!configLoaded) {
                Settings settings = loadSettings();
                requestedMode = settings.mode();
                requestedAlgorithmPolicy = settings.algorithmPolicy();
                configLoaded = true;
            }
        }
    }

    private static void saveSettings(
            final TemporalScalingMode mode,
            final TemporalAlgorithmPolicy algorithmPolicy
    ) {
        final Path path;
        try {
            path = configPath();
        } catch (RuntimeException exception) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("mode", mode.name().toLowerCase(Locale.ROOT));
        properties.setProperty(
                "algorithm",
                algorithmPolicy.name().toLowerCase(Locale.ROOT)
        );
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
