package com.metallum.client.renderer.temporal;

import java.util.Objects;

/**
 * Versioned, executor-neutral description of the data semantics expected by a frame.
 * This declaration does not allocate history resources or alter a production projection.
 */
public record FrameContract(
        int version,
        MotionVectorContract motionVectors,
        DepthContract depth,
        ReactiveMaskAvailability reactiveMask,
        UiComposition uiComposition
) {
    public static final int CURRENT_VERSION = 1;
    private static final FrameContract TEMPORAL_PREPARATION = new FrameContract(
            CURRENT_VERSION,
            new MotionVectorContract(
                    MotionVectorAvailability.UNAVAILABLE,
                    MotionVectorDelta.PREVIOUS_NDC_MINUS_CURRENT_NDC,
                    MotionVectorUnits.RENDER_PIXELS,
                    HorizontalAxis.POSITIVE_RIGHT,
                    VerticalAxis.POSITIVE_DOWN
            ),
            new DepthContract(DepthRange.ZERO_TO_ONE, true),
            ReactiveMaskAvailability.UNAVAILABLE,
            UiComposition.SEPARATE_SDR_TEXTURE
    );
    private static final FrameContract TEMPORAL_PRODUCTION = new FrameContract(
            CURRENT_VERSION,
            new MotionVectorContract(
                    MotionVectorAvailability.AVAILABLE,
                    MotionVectorDelta.PREVIOUS_NDC_MINUS_CURRENT_NDC,
                    MotionVectorUnits.RENDER_PIXELS,
                    HorizontalAxis.POSITIVE_RIGHT,
                    VerticalAxis.POSITIVE_DOWN
            ),
            new DepthContract(DepthRange.ZERO_TO_ONE, true),
            ReactiveMaskAvailability.AVAILABLE,
            UiComposition.SEPARATE_SDR_TEXTURE
    );

    public enum MotionVectorAvailability {
        UNAVAILABLE,
        AVAILABLE
    }

    public enum MotionVectorDelta {
        PREVIOUS_NDC_MINUS_CURRENT_NDC
    }

    public enum MotionVectorUnits {
        RENDER_PIXELS
    }

    public enum HorizontalAxis {
        POSITIVE_RIGHT
    }

    public enum VerticalAxis {
        POSITIVE_DOWN
    }

    public enum DepthRange {
        ZERO_TO_ONE
    }

    public enum ReactiveMaskAvailability {
        UNAVAILABLE,
        AVAILABLE
    }

    public enum UiComposition {
        SEPARATE_SDR_TEXTURE,
        COMPOSITED_WITH_WORLD
    }

    public record MotionVectorContract(
            MotionVectorAvailability availability,
            MotionVectorDelta delta,
            MotionVectorUnits units,
            HorizontalAxis horizontalAxis,
            VerticalAxis verticalAxis
    ) {
        public MotionVectorContract {
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(delta, "delta");
            Objects.requireNonNull(units, "units");
            Objects.requireNonNull(horizontalAxis, "horizontalAxis");
            Objects.requireNonNull(verticalAxis, "verticalAxis");
        }
    }

    public record DepthContract(DepthRange range, boolean reversedZ) {
        public DepthContract {
            Objects.requireNonNull(range, "range");
        }

        public double nearPlaneValue() {
            return this.reversedZ ? 1.0 : 0.0;
        }

        public double farPlaneValue() {
            return this.reversedZ ? 0.0 : 1.0;
        }
    }

    public FrameContract {
        if (version <= 0) {
            throw new IllegalArgumentException("Frame contract version must be positive");
        }
        Objects.requireNonNull(motionVectors, "motionVectors");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(reactiveMask, "reactiveMask");
        Objects.requireNonNull(uiComposition, "uiComposition");
    }

    /** Current preparation contract: declarations only, with all future temporal inputs disabled. */
    public static FrameContract temporalPreparationV1() {
        return TEMPORAL_PREPARATION;
    }

    /** Production MetalFX Temporal contract: all sampled history inputs are present and typed. */
    public static FrameContract temporalProductionV1() {
        return TEMPORAL_PRODUCTION;
    }
}
