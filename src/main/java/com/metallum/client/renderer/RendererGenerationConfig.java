package com.metallum.client.renderer;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable mode, executor and contract selection for one renderer generation. */
public record RendererGenerationConfig(
        LightingMode lightingMode,
        DisplayOutputMode outputMode,
        MetalExecutorKind executorKind,
        MetalCapabilities capabilities,
        int frameResourceContractVersion
) {
    public static final int CURRENT_FRAME_RESOURCE_CONTRACT_VERSION = 1;

    public enum RejectionReason {
        LIGHTING_UNAVAILABLE,
        OUTPUT_UNAVAILABLE,
        EXECUTOR_UNAVAILABLE
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
        Objects.requireNonNull(lightingMode, "lightingMode");
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(executorKind, "executorKind");
        Objects.requireNonNull(capabilities, "capabilities");
        if (frameResourceContractVersion <= 0) {
            throw new IllegalArgumentException("Frame/resource contract version must be positive");
        }
        if (!capabilities.supports(lightingMode)) {
            throw new IllegalArgumentException("Lighting mode is not supported by the capability snapshot");
        }
        if (!capabilities.supports(outputMode)) {
            throw new IllegalArgumentException("Output mode is not supported by the capability snapshot");
        }
        if (!capabilities.supports(executorKind)) {
            throw new IllegalArgumentException("Executor is not supported by the capability snapshot");
        }
    }

    public static Resolution resolve(
            final LightingMode requestedLighting,
            final DisplayOutputMode requestedOutput,
            final MetalExecutorKind requestedExecutor,
            final DisplayOutputMode currentSafeOutput,
            final MetalCapabilities capabilities,
            final int frameResourceContractVersion
    ) {
        Objects.requireNonNull(requestedLighting, "requestedLighting");
        Objects.requireNonNull(requestedOutput, "requestedOutput");
        Objects.requireNonNull(requestedExecutor, "requestedExecutor");
        Objects.requireNonNull(currentSafeOutput, "currentSafeOutput");
        Objects.requireNonNull(capabilities, "capabilities");

        EnumSet<RejectionReason> reasons = EnumSet.noneOf(RejectionReason.class);
        if (!capabilities.supports(requestedLighting)) {
            reasons.add(RejectionReason.LIGHTING_UNAVAILABLE);
        }
        if (!capabilities.supports(requestedOutput)) {
            reasons.add(RejectionReason.OUTPUT_UNAVAILABLE);
        }
        if (!capabilities.supports(requestedExecutor)) {
            reasons.add(RejectionReason.EXECUTOR_UNAVAILABLE);
        }

        if (reasons.isEmpty()) {
            return new Resolution(
                    new RendererGenerationConfig(
                            requestedLighting,
                            requestedOutput,
                            requestedExecutor,
                            capabilities,
                            frameResourceContractVersion
                    ),
                    reasons
            );
        }

        if (!capabilities.supports(MetalExecutorKind.METAL3)) {
            throw new IllegalStateException("Fail-closed selection requires the Metal 3 baseline");
        }
        DisplayOutputMode safeOutput = capabilities.supports(currentSafeOutput)
                ? currentSafeOutput
                : DisplayOutputMode.SDR;
        return new Resolution(
                new RendererGenerationConfig(
                        LightingMode.LEGACY,
                        safeOutput,
                        MetalExecutorKind.METAL3,
                        capabilities,
                        frameResourceContractVersion
                ),
                reasons
        );
    }
}
