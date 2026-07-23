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
    /**
     * MetalFX Temporal has a display-resolution reconstruction cost. Below this
     * scale it can repay that cost with the world pixels it saves. Dynamic
     * sessions therefore use an explicit Native <-> Temporal state machine:
     * Spatial belongs exclusively to its separately selected user mode.
     */
    static final float DYNAMIC_NATIVE_SCALE = 1.00f;
    /** The only dynamic Temporal input scale validated as a win in both benchmark worlds. */
    static final float DYNAMIC_TEMPORAL_SCALE = 0.50f;
    /** Do not pay Temporal's fixed cost while Native remains at or above 60 FPS. */
    static final float NATIVE_TO_TEMPORAL_GPU_MS = 16.50f;
    static final int NATIVE_TO_TEMPORAL_FRAMES = 45;
    /** 14.0 ms GPU time is approximately 71.4 FPS before the return holdoff. */
    static final float TEMPORAL_TO_NATIVE_GPU_MS = 14.00f;
    /** One second at 60 FPS of proven Temporal headroom before returning to Native. */
    static final int TEMPORAL_TO_NATIVE_FRAMES = 60;
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
    private static volatile TemporalScalingMode benchmarkOverride;
    /** True while Dynamic Temporal deliberately uses native resolution. */
    private static volatile boolean dynamicNativeFallback;
    private static int nativeOverBudgetFrames;
    private static int temporalUnderBudgetFrames;

    private MetalFxTemporalScaling() {
    }

    public static boolean isBenchmarkOverrideActive() {
        return benchmarkOverride != null;
    }

    public static boolean isFixedPresetActive() {
        ensureConfigLoaded();
        return requestedMode.isFixedPreset();
    }

    public static void requestRendererResize() {
        RESIZE_PENDING.set(true);
    }

    public static TemporalScalingMode requestedMode() {
        ensureConfigLoaded();
        return selectRequestedMode(requestedMode, benchmarkOverride);
    }

    public static TemporalScalingMode effectiveMode() {
        ensureConfigLoaded();
        MetalDevice device = MetalDevice.getInstance();
        TemporalScalingMode selected = selectEffectiveMode(
                requestedMode,
                benchmarkOverride,
                runtimeDisabled,
                device != null && device.supportsTemporalScaling()
        );
        return dynamicNativeFallbackActive(selected)
                ? TemporalScalingMode.OFF
                : selected;
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

    /** Called from the render-thread DRS feedback loop after a completed GPU sample. */
    static void updateDynamicReconstructionPolicy(final float gpuTimeMs) {
        ensureConfigLoaded();
        TemporalScalingMode selected = selectRequestedMode(requestedMode, benchmarkOverride);
        if (!selected.isDynamic() || runtimeDisabled || !Float.isFinite(gpuTimeMs) || gpuTimeMs <= 0.0f) {
            return;
        }
        if (dynamicNativeFallback) {
            temporalUnderBudgetFrames = 0;
            nativeOverBudgetFrames = nextConsecutiveFrameCount(
                    nativeOverBudgetFrames, gpuTimeMs > NATIVE_TO_TEMPORAL_GPU_MS
            );
            if (nativeOverBudgetFrames >= NATIVE_TO_TEMPORAL_FRAMES) {
                enterTemporalRange();
            }
        } else {
            nativeOverBudgetFrames = 0;
            temporalUnderBudgetFrames = nextConsecutiveFrameCount(
                    temporalUnderBudgetFrames, gpuTimeMs < TEMPORAL_TO_NATIVE_GPU_MS
            );
            if (temporalUnderBudgetFrames >= TEMPORAL_TO_NATIVE_FRAMES) {
                returnToNativeRange();
            }
        }
    }

    public static void setRequestedMode(final TemporalScalingMode mode) {
        ensureConfigLoaded();
        TemporalScalingMode selected = mode == null ? TemporalScalingMode.OFF : mode;
        TemporalScalingMode previous = requestedMode;
        boolean wasRuntimeDisabled = runtimeDisabled;
        requestedMode = selected;
        runtimeDisabled = false;
        initializeDynamicSession(selected);
        saveSettings(selected);
        if (selected.enabled()) {
            MetalFxSpatialScaling.disableForTemporalSelection();
            if (selected.isDynamic()) {
                MetallumDrsController.setEnabled(true);
            } else {
                MetallumDrsController.setEnabled(false);
            }
        } else {
            if (!MetalFxSpatialScaling.isActive()) {
                MetallumDrsController.setEnabled(false);
            }
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
        dynamicNativeFallback = false;
        resetDynamicTransitionCounters();
        restoreDefaultScaleBounds();
        saveSettings(TemporalScalingMode.OFF);
        requestRendererResize();
    }

    /** Installs a non-persistent concrete preset for the automated benchmark. */
    public static void setBenchmarkOverride(final TemporalScalingMode mode) {
        ensureConfigLoaded();
        TemporalScalingMode concreteMode = mode == null ? TemporalScalingMode.OFF : mode;
        TemporalScalingMode previous = benchmarkOverride;
        boolean wasRuntimeDisabled = runtimeDisabled;
        benchmarkOverride = concreteMode;
        runtimeDisabled = false;
        dynamicNativeFallback = false;
        resetDynamicTransitionCounters();
        restoreDefaultScaleBounds();
        if (previous != concreteMode || wasRuntimeDisabled) {
            requestRendererResize();
        }
    }

    /** Restores the persisted user preset after an automated benchmark. */
    public static void clearBenchmarkOverride() {
        ensureConfigLoaded();
        if (benchmarkOverride == null) {
            return;
        }
        benchmarkOverride = null;
        runtimeDisabled = false;
        initializeDynamicSession(requestedMode);
        requestRendererResize();
    }

    public static void disableRuntimeAfterFailure(final Throwable cause) {
        if (runtimeDisabled) {
            return;
        }
        runtimeDisabled = true;
        resetDynamicTransitionCounters();
        restoreDefaultScaleBounds();
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
        float scale = safeMode.isDynamic() && !isBenchmarkOverrideActive() && !isFixedPresetActive()
                ? MetallumDrsController.currentScale()
                : safeMode.linearScale();
        return new Dimensions(
                safeDisplayWidth,
                safeDisplayHeight,
                scaledDimension(safeDisplayWidth, scale),
                scaledDimension(safeDisplayHeight, scale)
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

    static TemporalScalingMode selectRequestedMode(
            final TemporalScalingMode persistedMode,
            final TemporalScalingMode overrideMode
    ) {
        return overrideMode != null
                ? overrideMode
                : (persistedMode == null ? TemporalScalingMode.OFF : persistedMode);
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

    static boolean dynamicNativeFallbackActive(final TemporalScalingMode selectedMode) {
        return selectedMode != null
                && selectedMode.isDynamic()
                && dynamicNativeFallback;
    }

    static int nextConsecutiveFrameCount(final int currentCount, final boolean condition) {
        return condition ? currentCount + 1 : 0;
    }

    private static void enterTemporalRange() {
        dynamicNativeFallback = false;
        resetDynamicTransitionCounters();
        MetallumDrsController.setScaleBounds(DYNAMIC_TEMPORAL_SCALE, DYNAMIC_TEMPORAL_SCALE);
        MetallumDrsController.setScaleForDynamicMode(DYNAMIC_TEMPORAL_SCALE);
        requestRendererResize();
    }

    private static void returnToNativeRange() {
        dynamicNativeFallback = true;
        resetDynamicTransitionCounters();
        MetallumDrsController.setScaleBounds(DYNAMIC_NATIVE_SCALE, DYNAMIC_NATIVE_SCALE);
        MetallumDrsController.setScaleForDynamicMode(DYNAMIC_NATIVE_SCALE);
        requestRendererResize();
    }

    /** Applies the same safe initial state for persisted and menu-selected Dynamic Temporal. */
    private static void initializeDynamicSession(final TemporalScalingMode selected) {
        dynamicNativeFallback = selected != null && selected.isDynamic();
        resetDynamicTransitionCounters();
        if (dynamicNativeFallback) {
            MetallumDrsController.setScaleBounds(DYNAMIC_NATIVE_SCALE, DYNAMIC_NATIVE_SCALE);
        } else {
            restoreDefaultScaleBounds();
        }
    }

    private static void restoreDefaultScaleBounds() {
        MetallumDrsController.setScaleBounds(MetallumDrsController.MIN_SCALE, MetallumDrsController.MAX_SCALE);
    }

    private static void resetDynamicTransitionCounters() {
        nativeOverBudgetFrames = 0;
        temporalUnderBudgetFrames = 0;
    }

    private static int scaledDimension(final int displayDimension, final float scale) {
        return Math.clamp(Math.round(displayDimension * scale), 1, displayDimension);
    }

    private record Settings(TemporalScalingMode mode) {
    }

    private static Settings loadSettings() {
        try {
            Path path = configPath();
            if (!Files.isRegularFile(path)) {
                return new Settings(TemporalScalingMode.OFF);
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
                return new Settings(from(properties));
            } catch (IOException exception) {
                Metallum.LOGGER.warn("Failed to read {}, using MetalFX Temporal defaults", path, exception);
            }
        } catch (RuntimeException ignored) {
            return new Settings(TemporalScalingMode.OFF);
        }
        return new Settings(TemporalScalingMode.OFF);
    }

    private static void ensureConfigLoaded() {
        if (configLoaded) {
            return;
        }
        synchronized (MetalFxTemporalScaling.class) {
            if (!configLoaded) {
                Settings settings = loadSettings();
                requestedMode = settings.mode();
                // Loading a saved Dynamic choice bypasses setRequestedMode(),
                // so it must still pin the first frame to Native 100% before
                // completed GPU samples can admit Temporal at 50%.
                initializeDynamicSession(requestedMode);
                configLoaded = true;
            }
        }
    }

    private static void saveSettings(final TemporalScalingMode mode) {
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
}
