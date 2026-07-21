package com.metallum.client.metalfx;

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

    private MetalFxUpscaling() {
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
