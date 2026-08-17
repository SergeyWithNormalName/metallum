package com.metallum.client.renderer.temporal;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RenderContractMode;
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
        long renderContractGenerationId,
        long lightingGenerationId,
        long outputGenerationId,
        RenderContractMode renderContractMode,
        LightingModel lightingModel,
        DisplayOutputMode outputMode,
        LightingPreset lightingPreset,
        RendererFeatureMask featureMask,
        MetalExecutorKind executorKind,
        int frameGraphVersion,
        ResourceBytes resourceBytes,
        AdvancedLightingWork advancedLightingWork,
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
        RENDER_CONTRACT_CHANGE,
        LIGHTING_MODEL_CHANGE,
        OUTPUT_MODE_CHANGE,
        INTERNAL_RENDER_SCALE_CHANGE,
        RESOURCE_PACK_SHADER_RELOAD,
        VISUAL_STYLE_CHANGE
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
            long material,
            long hdr,
            long advancedLighting,
            long upscale,
            long interpolation,
            long diagnostic
    ) {
        public static final ResourceBytes NONE = new ResourceBytes(
                0L, 0L, 0L, 0L, 0L, 0L, 0L
        );

        public ResourceBytes {
            requireNonNegative(base, "base resource bytes");
            requireNonNegative(material, "material resource bytes");
            requireNonNegative(hdr, "HDR resource bytes");
            requireNonNegative(advancedLighting, "Advanced-lighting resource bytes");
            requireNonNegative(upscale, "upscale resource bytes");
            requireNonNegative(interpolation, "interpolation resource bytes");
            requireNonNegative(diagnostic, "diagnostic resource bytes");
        }

        public ResourceBytes(
                final long base,
                final long material,
                final long hdr,
                final long advancedLighting,
                final long upscale,
                final long interpolation
        ) {
            this(base, material, hdr, advancedLighting, upscale, interpolation, 0L);
        }
    }

    /** Per-frame Advanced work. L2.5 keeps every field exactly zero for Vanilla. */
    public record AdvancedLightingWork(
            int lightCount,
            int passCount,
            int encoderCount,
            int psoCount,
            int workQueueCount,
            int dispatchCount,
            long uploadBytes
    ) {
        public static final AdvancedLightingWork NONE = new AdvancedLightingWork(
                0, 0, 0, 0, 0, 0, 0L
        );

        public AdvancedLightingWork {
            requireNonNegative(lightCount, "light count");
            requireNonNegative(passCount, "Advanced pass count");
            requireNonNegative(encoderCount, "Advanced encoder count");
            requireNonNegative(psoCount, "Advanced PSO count");
            requireNonNegative(workQueueCount, "Advanced work-queue count");
            requireNonNegative(dispatchCount, "Advanced dispatch count");
            requireNonNegative(uploadBytes, "Advanced upload bytes");
        }

        public boolean isEmpty() {
            return this.lightCount == 0 && this.passCount == 0
                    && this.encoderCount == 0 && this.psoCount == 0
                    && this.workQueueCount == 0
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

    public FrameState withAdvancedLightingWork(final AdvancedLightingWork work) {
        return new FrameState(
                this.contract,
                this.frameId,
                this.rendererGenerationId,
                this.historyGeneration,
                this.renderContractGenerationId,
                this.lightingGenerationId,
                this.outputGenerationId,
                this.renderContractMode,
                this.lightingModel,
                this.outputMode,
                this.lightingPreset,
                this.featureMask,
                this.executorKind,
                this.frameGraphVersion,
                this.resourceBytes,
                Objects.requireNonNull(work, "work"),
                this.currentTransforms,
                this.previousTransforms,
                this.renderExtent,
                this.displayExtent,
                this.exposure,
                this.preExposure,
                this.jitterOffset,
                this.historyResetReasons,
                this.submitIndex,
                this.inFlightSlot,
                this.deltaSeconds,
                this.nearPlane,
                this.farPlane,
                this.currentCameraPosition,
                this.previousCameraPosition,
                this.worldIdentity,
                this.dimensionIdentity,
                this.currentDisplayHeadroom,
                this.potentialDisplayHeadroom
        );
    }

    public FrameState {
        Objects.requireNonNull(contract, "contract");
        requireNonNegative(frameId, "frame ID");
        requireNonNegative(rendererGenerationId, "renderer generation ID");
        requireNonNegative(historyGeneration, "history generation");
        requireNonNegative(renderContractGenerationId, "render-contract generation ID");
        requireNonNegative(lightingGenerationId, "lighting generation ID");
        requireNonNegative(outputGenerationId, "output generation ID");
        Objects.requireNonNull(renderContractMode, "renderContractMode");
        Objects.requireNonNull(lightingModel, "lightingModel");
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(lightingPreset, "lightingPreset");
        Objects.requireNonNull(featureMask, "featureMask");
        Objects.requireNonNull(executorKind, "executorKind");
        if (frameGraphVersion <= 0) {
            throw new IllegalArgumentException("Frame graph version must be positive");
        }
        Objects.requireNonNull(resourceBytes, "resourceBytes");
        Objects.requireNonNull(advancedLightingWork, "advancedLightingWork");
        if (renderContractMode == RenderContractMode.LEGACY && resourceBytes.material() != 0L) {
            throw new IllegalArgumentException("Legacy frames must contain zero material resources");
        }
        if (renderContractMode == RenderContractMode.LEGACY
                && lightingModel == LightingModel.ADVANCED) {
            throw new IllegalArgumentException("Legacy frames cannot use Advanced lighting");
        }
        if (lightingModel == LightingModel.VANILLA
                && (resourceBytes.advancedLighting() != 0L || !advancedLightingWork.isEmpty())) {
            throw new IllegalArgumentException(
                    "Vanilla frames must contain zero Advanced work/resources"
            );
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

    /** Compact constructor for callers that use default presentation metadata. */
    public FrameState(
            final FrameContract contract,
            final long frameId,
            final long rendererGenerationId,
            final long historyGeneration,
            final long renderContractGenerationId,
            final long lightingGenerationId,
            final long outputGenerationId,
            final RenderContractMode renderContractMode,
            final LightingModel lightingModel,
            final DisplayOutputMode outputMode,
            final LightingPreset lightingPreset,
            final RendererFeatureMask featureMask,
            final MetalExecutorKind executorKind,
            final int frameGraphVersion,
            final ResourceBytes resourceBytes,
            final AdvancedLightingWork advancedLightingWork,
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
                renderContractGenerationId,
                lightingGenerationId,
                outputGenerationId,
                renderContractMode,
                lightingModel,
                outputMode,
                lightingPreset,
                featureMask,
                executorKind,
                frameGraphVersion,
                resourceBytes,
                advancedLightingWork,
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

    /** Compact preparation constructor for callers without a generation manifest. */
    public FrameState(
            final FrameContract contract,
            final long frameId,
            final long rendererGenerationId,
            final long historyGeneration,
            final long renderContractGenerationId,
            final long lightingGenerationId,
            final long outputGenerationId,
            final RenderContractMode renderContractMode,
            final LightingModel lightingModel,
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
                renderContractGenerationId,
                lightingGenerationId,
                outputGenerationId,
                renderContractMode,
                lightingModel,
                outputMode,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                MetalExecutorKind.METAL3,
                1,
                ResourceBytes.NONE,
                AdvancedLightingWork.NONE,
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
        if (previous.renderContractGenerationId != current.renderContractGenerationId
                || previous.renderContractMode != current.renderContractMode) {
            if (reasons == null) reasons = EnumSet.noneOf(HistoryResetReason.class);
            reasons.add(HistoryResetReason.RENDER_CONTRACT_CHANGE);
        }
        if (previous.lightingGenerationId != current.lightingGenerationId
                || previous.lightingModel != current.lightingModel) {
            if (reasons == null) reasons = EnumSet.noneOf(HistoryResetReason.class);
            reasons.add(HistoryResetReason.LIGHTING_MODEL_CHANGE);
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
