package com.metallum.client.renderer;

import com.metallum.client.renderer.temporal.FrameContract;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;

import java.util.EnumSet;
import java.util.Set;

/** Dependency-free renderer architecture contract tests. */
public final class RendererArchitectureTests {
    private RendererArchitectureTests() {
    }

    public static void main(final String[] args) {
        testIndependentModeMatrix();
        testProductionMetallumRejection();
        testFailClosedSelection();
        testImmutableCapabilitySnapshot();
        testInvalidConfigsAreRejected();
        testTemporalPreparationContract();
        testFrameStateGenerationTransitions();
        testAllHistoryResetReasonsAreRepresentable();
        testFrameStateNumericContracts();
        testFrameStateImmutability();
        System.out.println("Renderer architecture P1/P2 tests passed");
    }

    private static void testIndependentModeMatrix() {
        MetalCapabilities all = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.METAL4_CORE,
                MetalCapabilities.Feature.HDR_OUTPUT,
                MetalCapabilities.Feature.METALLUM_LIGHTING
        );
        for (LightingMode lighting : LightingMode.values()) {
            for (DisplayOutputMode output : DisplayOutputMode.values()) {
                for (MetalExecutorKind executor : MetalExecutorKind.values()) {
                    RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                            lighting,
                            output,
                            executor,
                            DisplayOutputMode.SDR,
                            all,
                            RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
                    );
                    require(!resolution.fellBack(), "supported matrix entry fell back");
                    require(resolution.config().lightingMode() == lighting, "lighting/output coupling");
                    require(resolution.config().outputMode() == output, "output/lighting coupling");
                    require(resolution.config().executorKind() == executor, "executor selection changed");
                }
            }
        }

        RendererGenerationConfig.Resolution metallumSdr = RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.SDR,
                all,
                1
        );
        require(metallumSdr.config().lightingMode() == LightingMode.METALLUM,
                "HDR off incorrectly forced legacy lighting");
    }

    private static void testProductionMetallumRejection() {
        MetalCapabilities production = MetalCapabilities.productionMetal3(true);
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.HDR,
                production,
                1
        );
        require(resolution.fellBack(), "unimplemented Metallum lighting was accepted");
        require(resolution.rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.LIGHTING_UNAVAILABLE)),
                "unexpected production fallback reason");
        require(resolution.config().lightingMode() == LightingMode.LEGACY,
                "unimplemented lighting did not fail closed");
        require(resolution.config().outputMode() == DisplayOutputMode.HDR,
                "lighting fallback changed the current safe HDR output");
    }

    private static void testFailClosedSelection() {
        MetalCapabilities sdrMetal3 = MetalCapabilities.productionMetal3(false);
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL4,
                DisplayOutputMode.HDR,
                sdrMetal3,
                1
        );
        require(resolution.rejectionReasons().equals(EnumSet.allOf(
                        RendererGenerationConfig.RejectionReason.class)),
                "unsupported combination did not report every rejection");
        require(resolution.config().lightingMode() == LightingMode.LEGACY
                        && resolution.config().outputMode() == DisplayOutputMode.SDR
                        && resolution.config().executorKind() == MetalExecutorKind.METAL3,
                "unsupported combination did not fail closed to Legacy + safe output + Metal 3");
    }

    private static void testImmutableCapabilitySnapshot() {
        EnumSet<MetalCapabilities.Feature> source = EnumSet.of(MetalCapabilities.Feature.METAL3_BASE);
        MetalCapabilities snapshot = MetalCapabilities.of(source.toArray(MetalCapabilities.Feature[]::new));
        source.add(MetalCapabilities.Feature.METAL4_CORE);
        require(!snapshot.supports(MetalCapabilities.Feature.METAL4_CORE),
                "capability snapshot retained a mutable source");
        expectUnsupported(() -> snapshot.features().add(MetalCapabilities.Feature.HDR_OUTPUT));
    }

    private static void testInvalidConfigsAreRejected() {
        MetalCapabilities production = MetalCapabilities.productionMetal3(false);
        expectIllegalArgument(() -> new RendererGenerationConfig(
                LightingMode.METALLUM,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                production,
                1
        ));
        expectIllegalArgument(() -> new RendererGenerationConfig(
                LightingMode.LEGACY,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                production,
                0
        ));
        expectIllegalState(() -> RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL4,
                DisplayOutputMode.SDR,
                MetalCapabilities.of(MetalCapabilities.Feature.METAL4_CORE),
                1
        ));
    }

    private static void testTemporalPreparationContract() {
        FrameContract contract = FrameContract.temporalPreparationV1();
        require(contract.version() == FrameContract.CURRENT_VERSION, "frame contract version mismatch");
        require(contract.motionVectors().availability()
                        == FrameContract.MotionVectorAvailability.UNAVAILABLE,
                "P2 unexpectedly advertises motion vectors");
        require(contract.motionVectors().delta()
                        == FrameContract.MotionVectorDelta.PREVIOUS_NDC_MINUS_CURRENT_NDC
                        && contract.motionVectors().units() == FrameContract.MotionVectorUnits.RENDER_PIXELS
                        && contract.motionVectors().horizontalAxis()
                        == FrameContract.HorizontalAxis.POSITIVE_RIGHT
                        && contract.motionVectors().verticalAxis()
                        == FrameContract.VerticalAxis.POSITIVE_DOWN,
                "motion-vector coordinate convention changed");
        require(contract.depth().range() == FrameContract.DepthRange.ZERO_TO_ONE
                        && contract.depth().reversedZ()
                        && contract.depth().nearPlaneValue() == 1.0
                        && contract.depth().farPlaneValue() == 0.0,
                "reversed-Z declaration changed");
        require(contract.reactiveMask() == FrameContract.ReactiveMaskAvailability.UNAVAILABLE,
                "P2 unexpectedly advertises a reactive mask");
        require(contract.uiComposition() == FrameContract.UiComposition.SEPARATE_SDR_TEXTURE,
                "SDR UI is no longer declared separately");
    }

    private static void testFrameStateGenerationTransitions() {
        FrameState base = frame(
                1L, 2L, 3L, 4L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(1280, 720), new FrameState.Extent(2560, 1440), Set.of()
        );
        FrameState changed = frame(
                2L, 3L, 4L, 5L,
                LightingMode.METALLUM, DisplayOutputMode.HDR,
                new FrameState.Extent(1920, 1080), new FrameState.Extent(2560, 1440), Set.of()
        );
        Set<FrameState.HistoryResetReason> resets = FrameState.transitionResetReasons(
                base,
                changed,
                EnumSet.of(FrameState.HistoryResetReason.DIMENSION_CHANGE)
        );
        require(resets.equals(EnumSet.of(
                        FrameState.HistoryResetReason.DIMENSION_CHANGE,
                        FrameState.HistoryResetReason.INTERNAL_RENDER_SCALE_CHANGE,
                        FrameState.HistoryResetReason.RENDERER_GENERATION_CHANGE,
                        FrameState.HistoryResetReason.LIGHTING_MODE_CHANGE,
                        FrameState.HistoryResetReason.OUTPUT_MODE_CHANGE)),
                "generation transition reset policy mismatch: " + resets);
        require(FrameState.transitionResetReasons(null, base, Set.of()).equals(
                        EnumSet.of(FrameState.HistoryResetReason.FIRST_FRAME)),
                "first frame did not invalidate history");

        FrameState resized = frame(
                1L, 2L, 3L, 4L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(1600, 900), new FrameState.Extent(3200, 1800), Set.of()
        );
        Set<FrameState.HistoryResetReason> resizeResets = FrameState.transitionResetReasons(
                base, resized, Set.of());
        require(resizeResets.equals(EnumSet.of(FrameState.HistoryResetReason.RESIZE)),
                "display resize was confused with an internal render-scale change");
    }

    private static void testAllHistoryResetReasonsAreRepresentable() {
        EnumSet<FrameState.HistoryResetReason> everyReason = EnumSet.allOf(
                FrameState.HistoryResetReason.class);
        FrameState state = frame(
                1L, 1L, 1L, 1L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(640, 360), new FrameState.Extent(1280, 720), everyReason
        );
        require(state.historyResetReasons().equals(everyReason),
                "one or more required history reset reasons cannot be represented");
    }

    private static void testFrameStateNumericContracts() {
        FrameState sdr = frame(
                7L, 11L, 13L, 17L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(1280, 720), new FrameState.Extent(2560, 1440), Set.of()
        );
        FrameState hdr = frame(
                7L, 11L, 13L, 18L,
                LightingMode.LEGACY, DisplayOutputMode.HDR,
                sdr.renderExtent(), sdr.displayExtent(), Set.of()
        );
        require(sdr.renderExtent().width() == 1280 && sdr.renderExtent().height() == 720
                        && sdr.displayExtent().width() == 2560 && sdr.displayExtent().height() == 1440,
                "render/display extents were coupled or reordered");
        require(sdr.lightingGenerationId() == hdr.lightingGenerationId()
                        && sdr.lightingMode() == hdr.lightingMode()
                        && hdr.outputMode() == DisplayOutputMode.HDR,
                "changing SDR/HDR output changed the lighting contract");
        require(Double.doubleToLongBits(sdr.jitterOffset().x()) == Double.doubleToLongBits(0.0)
                        && Double.doubleToLongBits(sdr.jitterOffset().y())
                        == Double.doubleToLongBits(0.0),
                "P2 zero jitter is not deterministic");
        expectIllegalArgument(() -> new FrameState.JitterOffset(0.25, 0.0));
        expectIllegalArgument(() -> new FrameState.Extent(0, 720));
    }

    private static void testFrameStateImmutability() {
        double[] source = Matrix4.identity().elements();
        Matrix4 matrix = Matrix4.of(source);
        source[0] = 9.0;
        double[] copy = matrix.elements();
        copy[5] = 8.0;
        require(matrix.element(0) == 1.0 && matrix.element(5) == 1.0,
                "matrix retained mutable source/output storage");

        EnumSet<FrameState.HistoryResetReason> reasons = EnumSet.of(
                FrameState.HistoryResetReason.CAMERA_CUT);
        FrameState state = frame(
                1L, 1L, 1L, 1L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(640, 360), new FrameState.Extent(1280, 720), reasons
        );
        reasons.add(FrameState.HistoryResetReason.TELEPORT);
        require(!state.historyResetReasons().contains(FrameState.HistoryResetReason.TELEPORT),
                "frame state retained a mutable reset-reason source");
        expectUnsupported(() -> state.historyResetReasons().add(
                FrameState.HistoryResetReason.RESOURCE_PACK_SHADER_RELOAD));
    }

    private static FrameState frame(
            final long rendererGeneration,
            final long historyGeneration,
            final long lightingGeneration,
            final long outputGeneration,
            final LightingMode lighting,
            final DisplayOutputMode output,
            final FrameState.Extent renderExtent,
            final FrameState.Extent displayExtent,
            final Set<FrameState.HistoryResetReason> resetReasons
    ) {
        FrameState.Transforms transforms = FrameState.Transforms.identity();
        return new FrameState(
                FrameContract.temporalPreparationV1(),
                42L,
                rendererGeneration,
                historyGeneration,
                lightingGeneration,
                outputGeneration,
                lighting,
                output,
                transforms,
                transforms,
                renderExtent,
                displayExtent,
                1.0,
                1.0,
                FrameState.JitterOffset.ZERO,
                resetReasons
        );
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectIllegalState(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private static void expectUnsupported(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
