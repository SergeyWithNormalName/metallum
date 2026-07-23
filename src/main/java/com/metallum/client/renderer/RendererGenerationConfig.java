package com.metallum.client.renderer;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable material, lighting, output, executor and contract selection for one generation. */
public record RendererGenerationConfig(
        RenderContractMode renderContractMode,
        LightingModel lightingModel,
        DisplayOutputMode outputMode,
        MetalExecutorKind executorKind,
        LightingPreset lightingPreset,
        RendererFeatureMask featureMask,
        MetalCapabilities capabilities,
        int frameResourceContractVersion
) {
    public static final int CURRENT_FRAME_RESOURCE_CONTRACT_VERSION = 6;

    public enum RejectionReason {
        MATERIAL_CONTRACT_UNAVAILABLE,
        ADVANCED_LIGHTING_UNAVAILABLE,
        OUTPUT_UNAVAILABLE,
        EXECUTOR_UNAVAILABLE,
        UPSCALER_UNAVAILABLE,
        INTERPOLATION_UNAVAILABLE
    }

    public record Resolution(
            RendererGenerationConfig config,
            Set<RejectionReason> rejectionReasons
    ) {
        public Resolution {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(rejectionReasons, "rejectionReasons");
            EnumSet<RejectionReason> copy = rejectionReasons.isEmpty()
                    ? EnumSet.noneOf(RejectionReason.class)
                    : EnumSet.copyOf(rejectionReasons);
            rejectionReasons = Collections.unmodifiableSet(copy);
        }

        public boolean fellBack() {
            return !this.rejectionReasons.isEmpty();
        }
    }

    public RendererGenerationConfig {
        Objects.requireNonNull(renderContractMode, "renderContractMode");
        Objects.requireNonNull(lightingModel, "lightingModel");
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(executorKind, "executorKind");
        Objects.requireNonNull(lightingPreset, "lightingPreset");
        Objects.requireNonNull(featureMask, "featureMask");
        Objects.requireNonNull(capabilities, "capabilities");
        if (renderContractMode == RenderContractMode.LEGACY
                && lightingModel == LightingModel.ADVANCED) {
            throw new IllegalArgumentException("Legacy render contract cannot use Advanced lighting");
        }
        if (frameResourceContractVersion <= 0) {
            throw new IllegalArgumentException("Frame/resource contract version must be positive");
        }
        if (!capabilities.supports(renderContractMode)) {
            throw new IllegalArgumentException("Render contract is not supported by the capability snapshot");
        }
        if (!capabilities.supports(lightingModel)) {
            throw new IllegalArgumentException("Lighting model is not supported by the capability snapshot");
        }
        if (!capabilities.supports(outputMode)) {
            throw new IllegalArgumentException("Output mode is not supported by the capability snapshot");
        }
        if (!capabilities.supports(executorKind)) {
            throw new IllegalArgumentException("Executor is not supported by the capability snapshot");
        }
        if (featureMask.contains(RendererFeatureMask.SPATIAL_UPSCALING)
                && !capabilities.supports(MetalCapabilities.Feature.METALFX_SPATIAL)) {
            throw new IllegalArgumentException("Spatial upscaling is not supported");
        }
        if (featureMask.contains(RendererFeatureMask.TEMPORAL_UPSCALING)
                && !capabilities.supports(MetalCapabilities.Feature.METALFX_TEMPORAL)) {
            throw new IllegalArgumentException("Temporal upscaling is not supported");
        }
        if (featureMask.contains(RendererFeatureMask.TEMPORAL_WARM_STANDBY)
                && !capabilities.supports(MetalCapabilities.Feature.METALFX_TEMPORAL)) {
            throw new IllegalArgumentException("Temporal warm standby is not supported");
        }
        if (featureMask.contains(RendererFeatureMask.FRAME_INTERPOLATION)
                && !capabilities.supports(MetalCapabilities.Feature.METALFX_FRAME_INTERPOLATION)) {
            throw new IllegalArgumentException("Frame Interpolation is not supported");
        }
    }

    public RendererGenerationConfig(
            final RenderContractMode renderContractMode,
            final LightingModel lightingModel,
            final DisplayOutputMode outputMode,
            final MetalExecutorKind executorKind,
            final MetalCapabilities capabilities,
            final int frameResourceContractVersion
    ) {
        this(
                renderContractMode,
                lightingModel,
                outputMode,
                executorKind,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                capabilities,
                frameResourceContractVersion
        );
    }

    public static Resolution resolve(
            final RenderContractMode requestedContract,
            final LightingModel requestedLighting,
            final DisplayOutputMode requestedOutput,
            final MetalExecutorKind requestedExecutor,
            final DisplayOutputMode currentSafeOutput,
            final MetalCapabilities capabilities,
            final int frameResourceContractVersion
    ) {
        return resolve(
                requestedContract,
                requestedLighting,
                requestedOutput,
                requestedExecutor,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                currentSafeOutput,
                capabilities,
                frameResourceContractVersion
        );
    }

    public static Resolution resolve(
            final RenderContractMode requestedContract,
            final LightingModel requestedLighting,
            final DisplayOutputMode requestedOutput,
            final MetalExecutorKind requestedExecutor,
            final LightingPreset requestedPreset,
            final RendererFeatureMask requestedFeatures,
            final DisplayOutputMode currentSafeOutput,
            final MetalCapabilities capabilities,
            final int frameResourceContractVersion
    ) {
        Objects.requireNonNull(requestedContract, "requestedContract");
        Objects.requireNonNull(requestedLighting, "requestedLighting");
        Objects.requireNonNull(requestedOutput, "requestedOutput");
        Objects.requireNonNull(requestedExecutor, "requestedExecutor");
        Objects.requireNonNull(requestedPreset, "requestedPreset");
        Objects.requireNonNull(requestedFeatures, "requestedFeatures");
        Objects.requireNonNull(currentSafeOutput, "currentSafeOutput");
        Objects.requireNonNull(capabilities, "capabilities");

        if (requestedContract == RenderContractMode.LEGACY
                && requestedLighting == LightingModel.ADVANCED) {
            throw new IllegalArgumentException("Legacy render contract cannot request Advanced lighting");
        }

        EnumSet<RejectionReason> reasons = EnumSet.noneOf(RejectionReason.class);
        RenderContractMode resolvedContract = requestedContract;
        LightingModel resolvedLighting = requestedLighting;
        if (!capabilities.supports(requestedContract)) {
            reasons.add(RejectionReason.MATERIAL_CONTRACT_UNAVAILABLE);
            resolvedContract = RenderContractMode.LEGACY;
            resolvedLighting = LightingModel.VANILLA;
        } else if (!capabilities.supports(requestedLighting)) {
            reasons.add(RejectionReason.ADVANCED_LIGHTING_UNAVAILABLE);
            resolvedLighting = LightingModel.VANILLA;
        }
        if (!capabilities.supports(requestedOutput)) {
            reasons.add(RejectionReason.OUTPUT_UNAVAILABLE);
        }
        if (!capabilities.supports(requestedExecutor)) {
            reasons.add(RejectionReason.EXECUTOR_UNAVAILABLE);
        }

        RendererFeatureMask resolvedFeatures = requestedFeatures;
        if (requestedFeatures.contains(RendererFeatureMask.SPATIAL_UPSCALING)
                && !capabilities.supports(MetalCapabilities.Feature.METALFX_SPATIAL)) {
            resolvedFeatures = resolvedFeatures.without(RendererFeatureMask.SPATIAL_UPSCALING);
            reasons.add(RejectionReason.UPSCALER_UNAVAILABLE);
        }
        if (requestedFeatures.contains(RendererFeatureMask.TEMPORAL_UPSCALING)
                && (!capabilities.supports(MetalCapabilities.Feature.METALFX_TEMPORAL)
                || !capabilities.temporalProfile().diagnosticsSupported())) {
            resolvedFeatures = resolvedFeatures.withoutTemporalUpscaling();
            reasons.add(RejectionReason.UPSCALER_UNAVAILABLE);
        }
        if (requestedFeatures.contains(RendererFeatureMask.TEMPORAL_WARM_STANDBY)
                && (!capabilities.supports(MetalCapabilities.Feature.METALFX_TEMPORAL)
                || !capabilities.temporalProfile().diagnosticsSupported())) {
            resolvedFeatures = resolvedFeatures.withoutTemporalWarmStandby();
            reasons.add(RejectionReason.UPSCALER_UNAVAILABLE);
        }
        if (requestedFeatures.contains(RendererFeatureMask.FRAME_INTERPOLATION)
                && !capabilities.supports(MetalCapabilities.Feature.METALFX_FRAME_INTERPOLATION)) {
            resolvedFeatures = resolvedFeatures.without(RendererFeatureMask.FRAME_INTERPOLATION);
            reasons.add(RejectionReason.INTERPOLATION_UNAVAILABLE);
        }

        if (!capabilities.supports(MetalExecutorKind.METAL3)) {
            throw new IllegalStateException("Fail-closed selection requires the Metal 3 baseline");
        }
        DisplayOutputMode safeOutput = capabilities.supports(currentSafeOutput)
                ? currentSafeOutput
                : DisplayOutputMode.SDR;
        DisplayOutputMode resolvedOutput = capabilities.supports(requestedOutput)
                ? requestedOutput
                : safeOutput;
        MetalExecutorKind resolvedExecutor = capabilities.supports(requestedExecutor)
                ? requestedExecutor
                : MetalExecutorKind.METAL3;
        return new Resolution(
                new RendererGenerationConfig(
                        resolvedContract,
                        resolvedLighting,
                        resolvedOutput,
                        resolvedExecutor,
                        requestedPreset,
                        resolvedFeatures,
                        capabilities,
                        frameResourceContractVersion
                ),
                reasons
        );
    }
}
