package com.metallum.client.metalfx;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

/** Single source of truth for world sizing while preserving the two native scaler implementations. */
public final class MetalFxUpscaling {
    public enum Type {
        NONE,
        SPATIAL,
        TEMPORAL
    }

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

    private static boolean resolutionOverlayEnabled = false;

    private MetalFxUpscaling() {
    }

    public static boolean isResolutionOverlayEnabled() {
        return resolutionOverlayEnabled;
    }

    public static void setResolutionOverlayEnabled(final boolean enabled) {
        resolutionOverlayEnabled = enabled;
    }

    public static Type activeType() {
        if (MetalFxTemporalScaling.isActive()) {
            return Type.TEMPORAL;
        }
        if (MetalFxSpatialScaling.isActive()) {
            return Type.SPATIAL;
        }
        return Type.NONE;
    }

    public static boolean isActive() {
        return activeType() != Type.NONE;
    }

    public static MetalFxUpscalingMode requestedMode() {
        if (MetalFxTemporalScaling.isRequested()) {
            return MetalFxUpscalingMode.TEMPORAL;
        }
        if (MetalFxSpatialScaling.isRequested()) {
            return MetalFxUpscalingMode.SPATIAL;
        }
        return MetalFxUpscalingMode.OFF;
    }

    public static void setRequestedMode(final MetalFxUpscalingMode mode) {
        MetalFxUpscalingMode nonNullMode = mode == null ? MetalFxUpscalingMode.OFF : mode;
        switch (nonNullMode) {
            case OFF -> {
                MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.OFF);
                MetalFxTemporalScaling.setRequestedMode(TemporalScalingMode.OFF);
                MetallumDrsController.setEnabled(false);
            }
            case SPATIAL -> {
                MetalFxTemporalScaling.setRequestedMode(TemporalScalingMode.OFF);
                MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.SPATIAL);
                MetallumDrsController.setEnabled(true);
            }
            case TEMPORAL -> {
                MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.OFF);
                MetalFxTemporalScaling.setRequestedMode(TemporalScalingMode.TEMPORAL);
                MetallumDrsController.setEnabled(true);
            }
        }
    }

    /**
     * Feeds DRS with one fresh, completed GPU sample when native presentation
     * has made one available. The native side returns zero when there is no new
     * sample, so this is safe to call once at the start of every render frame.
     */
    public static void updateDynamicResolution() {
        // Configuration is lazily loaded, including on the first rendered
        // frame after a client restart. Derive controller ownership from the
        // admitted scaler every frame so a persisted dynamic choice cannot
        // silently behave like a fixed preset until the user changes it again.
        Type activeType = activeType();
        boolean dynamic = activeType == Type.TEMPORAL
                ? MetalFxTemporalScaling.requestedMode().isDynamic()
                : activeType == Type.SPATIAL && MetalFxSpatialScaling.requestedMode().isDynamic();
        MetallumDrsController.setEnabled(dynamic);
        if (!dynamic) {
            return;
        }
        updateDynamicResolution(MetalNativeBridge.metallum_drs_consume_completed_frame_time_seconds());
    }

    /** Package-private so the native seconds-to-milliseconds boundary stays unit-testable. */
    static void updateDynamicResolution(final double completedFrameSeconds) {
        if (!Double.isFinite(completedFrameSeconds) || completedFrameSeconds <= 0.0) {
            return;
        }
        MetallumDrsController.updateGpuFrameTime((float) (completedFrameSeconds * 1_000.0));
    }

    public static Dimensions effectiveDimensions(final int displayWidth, final int displayHeight) {
        if (activeType() == Type.TEMPORAL) {
            MetalFxTemporalScaling.Dimensions dimensions = MetalFxTemporalScaling.effectiveDimensions(
                    displayWidth, displayHeight
            );
            return new Dimensions(
                    dimensions.displayWidth(), dimensions.displayHeight(),
                    dimensions.renderWidth(), dimensions.renderHeight()
            );
        }
        MetalFxSpatialScaling.Dimensions dimensions = MetalFxSpatialScaling.effectiveDimensions(
                displayWidth, displayHeight
        );
        return new Dimensions(
                dimensions.displayWidth(), dimensions.displayHeight(),
                dimensions.renderWidth(), dimensions.renderHeight()
        );
    }

    public static void recordDisplaySize(final int width, final int height) {
        MetalFxSpatialScaling.recordDisplaySize(width, height);
    }

    public static int configuredDisplayWidth(final int fallback) {
        return MetalFxSpatialScaling.configuredDisplayWidth(fallback);
    }

    public static int configuredDisplayHeight(final int fallback) {
        return MetalFxSpatialScaling.configuredDisplayHeight(fallback);
    }

    public static boolean consumePendingResize() {
        return MetalFxSpatialScaling.consumePendingResize() || MetalFxTemporalScaling.consumePendingResize();
    }

    public static void disableRuntimeAfterFailure(final Throwable cause) {
        if (activeType() == Type.TEMPORAL) {
            MetalFxTemporalScaling.disableRuntimeAfterFailure(cause);
        } else {
            MetalFxSpatialScaling.disableRuntimeAfterFailure(cause);
        }
    }
}
