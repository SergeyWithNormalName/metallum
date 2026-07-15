package com.metallum.client.renderer.temporal;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.LightingMode;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RendererFeatureMask;

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
        LightingPreset lightingPreset,
        RendererFeatureMask featureMask,
        MetalExecutorKind executorKind,
        int frameGraphVersion,
        ResourceBytes resourceBytes,
        LightingWork lightingWork,
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

    /** Owned resource estimates for the active generation, split by independent feature axis. */
    public record ResourceBytes(
            long base,
            long hdr,
            long lighting,
            long upscale,
            long interpolation
    ) {
        public static final ResourceBytes NONE = new ResourceBytes(0L, 0L, 0L, 0L, 0L);

        public ResourceBytes {
            requireNonNegative(base, "base resource bytes");
            requireNonNegative(hdr, "HDR resource bytes");
            requireNonNegative(lighting, "lighting resource bytes");
            requireNonNegative(upscale, "upscale resource bytes");
            requireNonNegative(interpolation, "interpolation resource bytes");
        }
    }

    /** Per-frame light work. L0 keeps every field exactly zero in Legacy generations. */
    public record LightingWork(
            int lightCount,
            int passCount,
            int dispatchCount,
            long uploadBytes
    ) {
        public static final LightingWork NONE = new LightingWork(0, 0, 0, 0L);

        public LightingWork {
            requireNonNegative(lightCount, "light count");
            requireNonNegative(passCount, "lighting pass count");
            requireNonNegative(dispatchCount, "lighting dispatch count");
            requireNonNegative(uploadBytes, "lighting upload bytes");
        }

        public boolean isEmpty() {
            return this.lightCount == 0 && this.passCount == 0
                    && this.dispatchCount == 0 && this.uploadBytes == 0L;
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
        Objects.requireNonNull(lightingPreset, "lightingPreset");
        Objects.requireNonNull(featureMask, "featureMask");
        Objects.requireNonNull(executorKind, "executorKind");
        if (frameGraphVersion <= 0) {
            throw new IllegalArgumentException("Frame graph version must be positive");
        }
        Objects.requireNonNull(resourceBytes, "resourceBytes");
        Objects.requireNonNull(lightingWork, "lightingWork");
        if (lightingMode == LightingMode.LEGACY
                && (resourceBytes.lighting() != 0L || !lightingWork.isEmpty())) {
            throw new IllegalArgumentException("Legacy frames must contain zero lighting work/resources");
        }
        if (outputMode == DisplayOutputMode.SDR && resourceBytes.hdr() != 0L) {
            throw new IllegalArgumentException("SDR frames must contain zero HDR resource bytes");
        }
        if (!featureMask.contains(RendererFeatureMask.SPATIAL_UPSCALING)
                && !featureMask.contains(RendererFeatureMask.TEMPORAL_UPSCALING)
                && resourceBytes.upscale() != 0L) {
            throw new IllegalArgumentException("Native-resolution frames must contain zero upscale bytes");
        }
        if (!featureMask.contains(RendererFeatureMask.FRAME_INTERPOLATION)
                && resourceBytes.interpolation() != 0L) {
            throw new IllegalArgumentException("Frames without interpolation must contain zero interpolation bytes");
        }
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

    /** Compatibility constructor for preparation callers that do not yet own a generation manifest. */
    public FrameState(
            final FrameContract contract,
            final long frameId,
            final long rendererGenerationId,
            final long historyGeneration,
            final long lightingGenerationId,
            final long outputGenerationId,
            final LightingMode lightingMode,
            final DisplayOutputMode outputMode,
            final Transforms currentTransforms,
            final Transforms previousTransforms,
            final Extent renderExtent,
            final Extent displayExtent,
            final double exposure,
            final double preExposure,
            final JitterOffset jitterOffset,
            final Set<HistoryResetReason> historyResetReasons
    ) {
        this(
                contract,
                frameId,
                rendererGenerationId,
                historyGeneration,
                lightingGenerationId,
                outputGenerationId,
                lightingMode,
                outputMode,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                MetalExecutorKind.METAL3,
                1,
                ResourceBytes.NONE,
                LightingWork.NONE,
                currentTransforms,
                previousTransforms,
                renderExtent,
                displayExtent,
                exposure,
                preExposure,
                jitterOffset,
                historyResetReasons
        );
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

    private static void requireNonNegative(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requirePositiveFinite(final double value, final String name) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
    }
}
