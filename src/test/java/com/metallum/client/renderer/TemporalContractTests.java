package com.metallum.client.renderer;

import com.metallum.client.hdr.EdrCapabilities;
import com.metallum.client.renderer.temporal.FrameContract;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.FrameStatePacketRing;
import com.metallum.client.renderer.temporal.FrameStateTracker;
import com.metallum.client.renderer.temporal.JitterSequence;
import com.metallum.client.renderer.temporal.Matrix4;
import com.metallum.client.renderer.temporal.TemporalResetEvents;

import java.lang.foreign.MemorySegment;
import java.util.Set;

/** Dependency-free L1 temporal contract and one-shot reset validation. */
public final class TemporalContractTests {
    private TemporalContractTests() {
    }

    public static void main(final String[] args) {
        testOneShotResetsAndPreviousPublication();
        testDeterministicDisabledJitter();
        testReusablePacketRing();
        testDiagnosticManifestIsolation();
        testTemporalCapabilityProfileAndProductionContract();
        System.out.println("L1 temporal contract validation passed");
    }

    private static void testOneShotResetsAndPreviousPublication() {
        FrameStateTracker tracker = new FrameStateTracker();
        FrameState first = tracker.publish(state(3L, 1L, 1L, 1280, 720, Matrix4.identity()), Set.of());
        require(first.historyResetReasons().equals(Set.of(FrameState.HistoryResetReason.FIRST_FRAME)),
                "first frame reset mismatch");
        require(first.previousTransforms().equals(first.currentTransforms())
                        && first.previousCameraPosition().equals(first.currentCameraPosition()),
                "first frame did not publish current as previous");

        FrameState steady = tracker.publish(state(4L, 1L, 1L, 1280, 720, Matrix4.identity()), Set.of());
        require(steady.historyResetReasons().isEmpty(), "first-frame reset leaked into the next frame");

        FrameState resized = tracker.publish(state(5L, 1L, 1L, 1600, 900, Matrix4.identity()), Set.of());
        require(resized.historyResetReasons().equals(Set.of(FrameState.HistoryResetReason.RESIZE)),
                "resize reset mismatch");
        FrameState afterResize = tracker.publish(state(6L, 1L, 1L, 1600, 900, Matrix4.identity()), Set.of());
        require(afterResize.historyResetReasons().isEmpty(), "resize reset was not one-shot");

        TemporalResetEvents.signal(FrameState.HistoryResetReason.TELEPORT);
        FrameState teleported = tracker.publish(
                state(7L, 1L, 1L, 1600, 900, Matrix4.identity()),
                TemporalResetEvents.consume()
        );
        require(teleported.historyResetReasons().equals(Set.of(FrameState.HistoryResetReason.TELEPORT)),
                "teleport reset mismatch");
        require(TemporalResetEvents.consume().isEmpty(), "teleport event was not atomically consumed");
        require(tracker.publish(state(8L, 1L, 1L, 1600, 900, Matrix4.identity()), Set.of())
                        .historyResetReasons().isEmpty(),
                "teleport reset leaked into the next frame");

        FrameState dimension = tracker.publish(state(9L, 1L, 2L, 1600, 900, Matrix4.identity()), Set.of());
        require(dimension.historyResetReasons().equals(Set.of(FrameState.HistoryResetReason.DIMENSION_CHANGE)),
                "dimension reset mismatch");
        require(tracker.publish(state(10L, 1L, 2L, 1600, 900, Matrix4.identity()), Set.of())
                        .historyResetReasons().isEmpty(),
                "dimension reset leaked into the next frame");

        FrameState world = tracker.publish(state(11L, 2L, 3L, 1600, 900, Matrix4.identity()), Set.of());
        require(world.historyResetReasons().equals(Set.of(FrameState.HistoryResetReason.WORLD_LOAD_UNLOAD)),
                "world transition reset mismatch");
        require(world.historyGeneration() == 5L, "history generation did not advance once per reset frame");
    }

    private static void testDeterministicDisabledJitter() {
        for (int frame = 0; frame < 64; frame++) {
            require(JitterSequence.sample(frame, 0.0).equals(FrameState.JitterOffset.ZERO),
                    "L1 runtime jitter amplitude is not zero");
            require(JitterSequence.sample(frame, 1.0).equals(JitterSequence.sample(frame + 16L, 1.0)),
                    "Halton jitter period is not deterministic");
        }
    }

    private static void testReusablePacketRing() {
        try (FrameStatePacketRing ring = new FrameStatePacketRing()) {
            MemorySegment first = ring.encode(state(3L, 1L, 1L, 1280, 720, Matrix4.identity()));
            MemorySegment reused = ring.encode(state(6L, 1L, 1L, 1280, 720, Matrix4.identity()));
            require(first.address() == reused.address(), "in-flight ABI slot allocated a replacement packet");
            require(ring.packet(1).address() != first.address(), "in-flight ABI slots alias each other");
        }
    }

    private static void testDiagnosticManifestIsolation() {
        MetalCapabilities capabilities = MetalCapabilities.productionMetal3(false);
        RendererGenerationConfig config = new RendererGenerationConfig(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                capabilities,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        );
        RendererGenerationPlanner.Extent extent = new RendererGenerationPlanner.Extent(1280, 720);
        RendererGenerationManifest off = RendererGenerationPlanner.manifest(config, extent, extent, false);
        require(off.resourceBytes(RendererGenerationManifest.Domain.DIAGNOSTIC_ONLY) == 0L
                        && off.passCount(RendererGenerationManifest.Domain.DIAGNOSTIC_ONLY) == 0L,
                "diagnostics-off manifest retained GPU work");
        RendererGenerationManifest on = RendererGenerationPlanner.manifest(config, extent, extent, true);
        require(on.resourceBytes(RendererGenerationManifest.Domain.DIAGNOSTIC_ONLY)
                        == 1280L * 720L * 5L * 3L
                        && on.passCount(RendererGenerationManifest.Domain.DIAGNOSTIC_ONLY) == 1L,
                "diagnostic ring/pass declaration mismatch");
    }

    private static void testTemporalCapabilityProfileAndProductionContract() {
        MetalCapabilities unavailable = MetalCapabilities.fromNativeSnapshot(
                MetalCapabilities.NATIVE_METAL3_BASE | MetalCapabilities.NATIVE_METALFX_TEMPORAL,
                EdrCapabilities.SDR
        );
        require(!unavailable.temporalProfile().diagnosticsSupported(),
                "coarse Temporal support bypassed the diagnostic profile");
        MetalCapabilities available = MetalCapabilities.fromNativeSnapshot(
                MetalCapabilities.NATIVE_METAL3_BASE
                        | MetalCapabilities.NATIVE_METALFX_TEMPORAL
                        | MetalCapabilities.NATIVE_REQUIRED_TEXTURE_FORMATS_USAGES
                        | MetalCapabilities.NATIVE_TEMPORAL_PROFILE,
                EdrCapabilities.SDR
        );
        require(available.temporalProfile().diagnosticsSupported()
                        && available.formatUsageProfile().effectSpecificUsagesValidated(),
                "reactive/format/usage profile was not decoded independently");
        FrameContract preparation = FrameContract.temporalPreparationV1();
        require(preparation.motionVectors().availability() == FrameContract.MotionVectorAvailability.UNAVAILABLE
                        && preparation.reactiveMask() == FrameContract.ReactiveMaskAvailability.UNAVAILABLE,
                "camera-only diagnostics were advertised as production temporal inputs");
        FrameContract production = FrameContract.temporalProductionV1();
        require(production.motionVectors().availability() == FrameContract.MotionVectorAvailability.AVAILABLE
                        && production.reactiveMask() == FrameContract.ReactiveMaskAvailability.AVAILABLE
                        && production.depth().reversedZ(),
                "MetalFX Temporal production contract is missing typed history inputs");

        RendererGenerationConfig temporalConfig = new RendererGenerationConfig(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.of(RendererFeatureMask.TEMPORAL_UPSCALING),
                available,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        );
        RendererGenerationManifest temporalManifest = RendererGenerationPlanner.manifest(
                temporalConfig,
                new RendererGenerationPlanner.Extent(192, 108),
                new RendererGenerationPlanner.Extent(288, 162),
                false
        );
        require(temporalManifest.resourceBytes(RendererGenerationManifest.Domain.UPSCALE_ONLY)
                        == 192L * 108L * 5L * 3L + 288L * 162L * 4L
                        && temporalManifest.passCount(RendererGenerationManifest.Domain.UPSCALE_ONLY) == 2L
                        && temporalManifest.passCount(RendererGenerationManifest.Domain.DIAGNOSTIC_ONLY) == 0L,
                "Temporal manifest does not own its typed inputs independently of diagnostics");
    }

    private static FrameState state(
            final long submit,
            final long world,
            final long dimension,
            final int width,
            final int height,
            final Matrix4 projection
    ) {
        Matrix4 identity = Matrix4.identity();
        FrameState.Transforms transforms = new FrameState.Transforms(
                identity, identity, projection, identity, identity, projection
        );
        return new FrameState(
                FrameContract.temporalPreparationV1(),
                0L, 1L, 0L, 1L, 1L, 1L,
                RenderContractMode.LEGACY, LightingModel.VANILLA,
                DisplayOutputMode.SDR, LightingPreset.BALANCED,
                RendererFeatureMask.NONE, MetalExecutorKind.METAL3,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION,
                FrameState.ResourceBytes.NONE, FrameState.AdvancedLightingWork.NONE,
                transforms, transforms,
                new FrameState.Extent(width, height), new FrameState.Extent(width, height),
                1.0, 1.0, FrameState.JitterOffset.ZERO, Set.of(),
                submit, (int) (submit % 3L), 1.0 / 60.0, 0.05, 1024.0,
                new FrameState.CameraPosition(1.0, 2.0, 3.0), FrameState.CameraPosition.ORIGIN,
                world, dimension, 1.0, 1.0
        );
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
