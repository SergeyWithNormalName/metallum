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
        Set<HistoryResetReason> historyResetReasons,
        long submitIndex,
        int inFlightSlot,
        double deltaSeconds,
        double nearPlane,
        double farPlane,
        CameraPosition currentCameraPosition,
        CameraPosition previousCameraPosition,
        long worldIdentity,
        long dimensionIdentity,
        double currentDisplayHeadroom,
        double potentialDisplayHeadroom
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
            if (Math.abs(x) > 0.5 || Math.abs(y) > 0.5) {
                throw new IllegalArgumentException("Jitter must stay within half a render pixel");
            }
        }
    }

    public record CameraPosition(double x, double y, double z) {
        public static final CameraPosition ORIGIN = new CameraPosition(0.0, 0.0, 0.0);

        public CameraPosition {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Camera position must be finite");
            }
        }
    }

    /** Owned resource estimates for the active generation, split by independent feature axis. */
    public record ResourceBytes(
            long base,
            long hdr,
            long lighting,
            long upscale,
            long interpolation,
            long diagnostic
    ) {
        public static final ResourceBytes NONE = new ResourceBytes(0L, 0L, 0L, 0L, 0L, 0L);

        public ResourceBytes {
            requireNonNegative(base, "base resource bytes");
            requireNonNegative(hdr, "HDR resource bytes");
            requireNonNegative(lighting, "lighting resource bytes");
            requireNonNegative(upscale, "upscale resource bytes");
            requireNonNegative(interpolation, "interpolation resource bytes");
            requireNonNegative(diagnostic, "diagnostic resource bytes");
        }

        public ResourceBytes(
                final long base,
                final long hdr,
                final long lighting,
                final long upscale,
                final long interpolation
        ) {
            this(base, hdr, lighting, upscale, interpolation, 0L);
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
        historyResetReasons = historyResetReasons.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(historyResetReasons));
        requireNonNegative(submitIndex, "submit index");
        if (inFlightSlot < 0 || inFlightSlot >= 3) {
            throw new IllegalArgumentException("In-flight slot must be in [0, 2]");
        }
        requireNonNegativeFinite(deltaSeconds, "delta seconds");
        requirePositiveFinite(nearPlane, "near plane");
        requirePositiveFinite(farPlane, "far plane");
        if (farPlane <= nearPlane) {
            throw new IllegalArgumentException("Far plane must be greater than near plane");
        }
        Objects.requireNonNull(currentCameraPosition, "currentCameraPosition");
        Objects.requireNonNull(previousCameraPosition, "previousCameraPosition");
        requireNonNegative(worldIdentity, "world identity");
        requireNonNegative(dimensionIdentity, "dimension identity");
        requireHeadroom(currentDisplayHeadroom, "current display headroom");
        requireHeadroom(potentialDisplayHeadroom, "potential display headroom");
        if (potentialDisplayHeadroom < currentDisplayHeadroom) {
            throw new IllegalArgumentException("Potential display headroom must cover current headroom");
        }
    }

    /** Compatibility constructor for L0 callers while production migrates to the v2 frame ABI. */
    public FrameState(
            final FrameContract contract,
            final long frameId,
            final long rendererGenerationId,
            final long historyGeneration,
            final long lightingGenerationId,
            final long outputGenerationId,
            final LightingMode lightingMode,
            final DisplayOutputMode outputMode,
            final LightingPreset lightingPreset,
            final RendererFeatureMask featureMask,
            final MetalExecutorKind executorKind,
            final int frameGraphVersion,
            final ResourceBytes resourceBytes,
            final LightingWork lightingWork,
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
                lightingPreset,
                featureMask,
                executorKind,
                frameGraphVersion,
                resourceBytes,
                lightingWork,
                currentTransforms,
                previousTransforms,
                renderExtent,
                displayExtent,
                exposure,
                preExposure,
                jitterOffset,
                historyResetReasons,
                frameId,
                (int) (frameId % 3L),
                1.0 / 60.0,
                0.05,
                1_000.0,
                CameraPosition.ORIGIN,
                CameraPosition.ORIGIN,
                0L,
                0L,
                1.0,
                1.0
        );
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
                ? null
                : EnumSet.copyOf(eventReasons);
        if (previous == null) {
            if (reasons == null) {
                reasons = EnumSet.noneOf(HistoryResetReason.class);
            }
            reasons.add(HistoryResetReason.FIRST_FRAME);
            return Collections.unmodifiableSet(reasons);
        }
        if (previous.worldIdentity != current.worldIdentity) {
            if (reasons == null) reasons = EnumSet.noneOf(HistoryResetReason.class);
            reasons.add(HistoryResetReason.WORLD_LOAD_UNLOAD);
        } else if (previous.dimensionIdentity != current.dimensionIdentity) {
            if (reasons == null) reasons = EnumSet.noneOf(HistoryResetReason.class);
            reasons.add(HistoryResetReason.DIMENSION_CHANGE);
        }
        if (!previous.displayExtent.equals(current.displayExtent)) {
            if (reasons == null) reasons = EnumSet.noneOf(HistoryResetReason.class);
            reasons.add(HistoryResetReason.RESIZE);
        }
        if (!previous.renderExtent.equals(current.renderExtent)
                && previous.displayExtent.equals(current.displayExtent)) {
            if (reasons == null) reasons = EnumSet.noneOf(HistoryResetReason.class);
            reasons.add(HistoryResetReason.INTERNAL_RENDER_SCALE_CHANGE);
        }
        if (previous.rendererGenerationId != current.rendererGenerationId) {
            if (reasons == null) reasons = EnumSet.noneOf(HistoryResetReason.class);
            reasons.add(HistoryResetReason.RENDERER_GENERATION_CHANGE);
        }
        if (previous.lightingGenerationId != current.lightingGenerationId
                || previous.lightingMode != current.lightingMode) {
            if (reasons == null) reasons = EnumSet.noneOf(HistoryResetReason.class);
            reasons.add(HistoryResetReason.LIGHTING_MODE_CHANGE);
        }
        if (previous.outputGenerationId != current.outputGenerationId
                || previous.outputMode != current.outputMode) {
            if (reasons == null) reasons = EnumSet.noneOf(HistoryResetReason.class);
            reasons.add(HistoryResetReason.OUTPUT_MODE_CHANGE);
        }
        return reasons == null ? Set.of() : Collections.unmodifiableSet(reasons);
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

    private static void requireNonNegativeFinite(final double value, final String name) {
        if (value < 0.0 || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be non-negative and finite");
        }
    }

    private static void requireHeadroom(final double value, final String name) {
        if (!(value >= 1.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and at least 1.0");
        }
    }
}
