package com.metallum.client.renderer.temporal;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingMode;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable state declaration for one future temporal-capable rendered frame. */
public record FrameState(
        FrameContract contract,
        long frameId,
        long rendererGenerationId,
        long historyGeneration,
        long lightingGenerationId,
        long outputGenerationId,
        LightingMode lightingMode,
        DisplayOutputMode outputMode,
        Transforms currentTransforms,
        Transforms previousTransforms,
        Extent renderExtent,
        Extent displayExtent,
        double exposure,
        double preExposure,
        JitterOffset jitterOffset,
        Set<HistoryResetReason> historyResetReasons
) {
    public enum HistoryResetReason {
        FIRST_FRAME,
        RESIZE,
        WORLD_LOAD_UNLOAD,
        DIMENSION_CHANGE,
        TELEPORT,
        CAMERA_CUT,
        FOV_PROJECTION_CHANGE,
        RENDERER_GENERATION_CHANGE,
        LIGHTING_MODE_CHANGE,
        OUTPUT_MODE_CHANGE,
        INTERNAL_RENDER_SCALE_CHANGE,
        RESOURCE_PACK_SHADER_RELOAD
    }

    public record Extent(int width, int height) {
        public Extent {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Frame extents must be positive");
            }
        }
    }

    public record JitterOffset(double x, double y) {
        public static final JitterOffset ZERO = new JitterOffset(0.0, 0.0);

        public JitterOffset {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("Jitter must be finite");
            }
            if (Double.doubleToLongBits(x) != Double.doubleToLongBits(0.0)
                    || Double.doubleToLongBits(y) != Double.doubleToLongBits(0.0)) {
                throw new IllegalArgumentException("P2 preparation contract requires exact zero jitter");
            }
        }
    }

    public record Transforms(
            Matrix4 camera,
            Matrix4 view,
            Matrix4 projection,
            Matrix4 unjitteredCamera,
            Matrix4 unjitteredView,
            Matrix4 unjitteredProjection
    ) {
        public Transforms {
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(unjitteredCamera, "unjitteredCamera");
            Objects.requireNonNull(unjitteredView, "unjitteredView");
            Objects.requireNonNull(unjitteredProjection, "unjitteredProjection");
        }

        public static Transforms identity() {
            Matrix4 identity = Matrix4.identity();
            return new Transforms(identity, identity, identity, identity, identity, identity);
        }
    }

    public FrameState {
        Objects.requireNonNull(contract, "contract");
        requireNonNegative(frameId, "frame ID");
        requireNonNegative(rendererGenerationId, "renderer generation ID");
        requireNonNegative(historyGeneration, "history generation");
        requireNonNegative(lightingGenerationId, "lighting generation ID");
        requireNonNegative(outputGenerationId, "output generation ID");
        Objects.requireNonNull(lightingMode, "lightingMode");
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(currentTransforms, "currentTransforms");
        Objects.requireNonNull(previousTransforms, "previousTransforms");
        Objects.requireNonNull(renderExtent, "renderExtent");
        Objects.requireNonNull(displayExtent, "displayExtent");
        requirePositiveFinite(exposure, "exposure");
        requirePositiveFinite(preExposure, "pre-exposure");
        Objects.requireNonNull(jitterOffset, "jitterOffset");
        Objects.requireNonNull(historyResetReasons, "historyResetReasons");
        EnumSet<HistoryResetReason> resetCopy = historyResetReasons.isEmpty()
                ? EnumSet.noneOf(HistoryResetReason.class)
                : EnumSet.copyOf(historyResetReasons);
        historyResetReasons = Collections.unmodifiableSet(resetCopy);
    }

    /**
     * Derives only transitions that can be proven from two immutable snapshots.
     * World/camera/reload discontinuities stay explicit event inputs.
     */
    public static Set<HistoryResetReason> transitionResetReasons(
            final FrameState previous,
            final FrameState current,
            final Set<HistoryResetReason> eventReasons
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(eventReasons, "eventReasons");
        EnumSet<HistoryResetReason> reasons = eventReasons.isEmpty()
                ? EnumSet.noneOf(HistoryResetReason.class)
                : EnumSet.copyOf(eventReasons);
        if (previous == null) {
            reasons.add(HistoryResetReason.FIRST_FRAME);
            return Collections.unmodifiableSet(reasons);
        }
        if (!previous.displayExtent.equals(current.displayExtent)) {
            reasons.add(HistoryResetReason.RESIZE);
        }
        if (!previous.renderExtent.equals(current.renderExtent)
                && previous.displayExtent.equals(current.displayExtent)) {
            reasons.add(HistoryResetReason.INTERNAL_RENDER_SCALE_CHANGE);
        }
        if (previous.rendererGenerationId != current.rendererGenerationId) {
            reasons.add(HistoryResetReason.RENDERER_GENERATION_CHANGE);
        }
        if (previous.lightingGenerationId != current.lightingGenerationId
                || previous.lightingMode != current.lightingMode) {
            reasons.add(HistoryResetReason.LIGHTING_MODE_CHANGE);
        }
        if (previous.outputGenerationId != current.outputGenerationId
                || previous.outputMode != current.outputMode) {
            reasons.add(HistoryResetReason.OUTPUT_MODE_CHANGE);
        }
        return Collections.unmodifiableSet(reasons);
    }

    private static void requireNonNegative(final long value, final String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requirePositiveFinite(final double value, final String name) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
    }
}
