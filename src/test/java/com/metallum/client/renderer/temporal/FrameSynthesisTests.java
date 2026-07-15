package com.metallum.client.renderer.temporal;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingMode;
import com.metallum.client.renderer.MetalCapabilities;
import com.metallum.client.renderer.MetalExecutorKind;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public final class FrameSynthesisTests {
    private static final FrameState.Extent RENDER_EXTENT = new FrameState.Extent(1280, 720);
    private static final FrameState.Extent DISPLAY_EXTENT = new FrameState.Extent(2560, 1440);

    private FrameSynthesisTests() {
    }

    public static void main(final String[] args) {
        testValidExecutorNeutralContract();
        testMissingTemporalInputsFailClosed();
        testUiMustRemainSeparate();
        testDiscontinuitiesInvalidateHistory();
        testGenerationTransitionsFailClosed();
        testPresentationAndOwnershipFailures();
        testCadenceAndImmutableOwnership();
        System.out.println("Frame synthesis P6 contract tests passed");
    }

    private static void testValidExecutorNeutralContract() {
        FrameSynthesisContract.Request metal3 = validRequest(MetalExecutorKind.METAL3);
        FrameSynthesisContract.Request metal4 = validRequest(MetalExecutorKind.METAL4);
        FrameSynthesisContract.Admission metal3Admission = FrameSynthesisContract.evaluate(metal3);
        FrameSynthesisContract.Admission metal4Admission = FrameSynthesisContract.evaluate(metal4);
        require(metal3Admission.allowed() && metal4Admission.allowed(),
                "valid synthesis contract was rejected");
        require(metal3Admission.rejectionReasons().equals(metal4Admission.rejectionReasons()),
                "Metal 3 and Metal 4 changed the input semantics");
        require(!metal3.drawableOwnership().additionalAcquisitionAllowed(),
                "preparation contract allowed an additional drawable acquisition");
    }

    private static void testMissingTemporalInputsFailClosed() {
        FrameSynthesisContract.Request valid = validRequest(MetalExecutorKind.METAL3);
        FrameSynthesisContract.RenderedFrame current = frame(
                2L,
                FrameContract.temporalPreparationV1(),
                Optional.of(texture("current-depth", FrameSynthesisContract.TextureRole.DEPTH, RENDER_EXTENT)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(texture("current-ui", FrameSynthesisContract.TextureRole.SDR_UI, DISPLAY_EXTENT)),
                false,
                Set.of()
        );
        FrameSynthesisContract.Request missing = copy(valid, current, valid.previous(),
                valid.generatedPresentation(), valid.drawableOwnership(), valid.inFlightOwnership());
        Set<FrameSynthesisContract.RejectionReason> reasons =
                FrameSynthesisContract.evaluate(missing).rejectionReasons();
        require(reasons.contains(FrameSynthesisContract.RejectionReason.MISSING_MOTION)
                        && reasons.contains(FrameSynthesisContract.RejectionReason.MISSING_REACTIVE_MASK)
                        && reasons.contains(FrameSynthesisContract.RejectionReason.TEMPORAL_CONTRACT_UNAVAILABLE),
                "missing motion/reactive inputs did not fail closed: " + reasons);

        MetalCapabilities noEffects = MetalCapabilities.of(MetalCapabilities.Feature.METAL3_BASE);
        FrameSynthesisContract.Request unavailable = new FrameSynthesisContract.Request(
                valid.current(), valid.previous(), valid.realPresentation(), valid.generatedPresentation(),
                valid.cadence(), valid.drawableOwnership(), valid.inFlightOwnership(), noEffects, valid.executor());
        Set<FrameSynthesisContract.RejectionReason> unavailableReasons =
                FrameSynthesisContract.evaluate(unavailable).rejectionReasons();
        require(unavailableReasons.contains(FrameSynthesisContract.RejectionReason.METALFX_TEMPORAL_UNAVAILABLE)
                        && unavailableReasons.contains(
                        FrameSynthesisContract.RejectionReason.FRAME_INTERPOLATION_UNAVAILABLE),
                "missing MetalFX support did not reject synthesis");
    }

    private static void testUiMustRemainSeparate() {
        FrameSynthesisContract.Request valid = validRequest(MetalExecutorKind.METAL3);
        FrameSynthesisContract.RenderedFrame composited = frame(
                2L,
                temporalContract(),
                valid.current().depth(),
                valid.current().motion(),
                valid.current().reactiveMask(),
                Optional.empty(),
                true,
                Set.of()
        );
        FrameSynthesisContract.Request invalid = copy(valid, composited, valid.previous(),
                valid.generatedPresentation(), valid.drawableOwnership(), valid.inFlightOwnership());
        require(FrameSynthesisContract.evaluate(invalid).rejectionReasons().contains(
                        FrameSynthesisContract.RejectionReason.UI_NOT_SEPARATED),
                "world-composited UI was admitted for interpolation");
    }

    private static void testDiscontinuitiesInvalidateHistory() {
        FrameSynthesisContract.Request valid = validRequest(MetalExecutorKind.METAL3);
        for (FrameSynthesisContract.Discontinuity discontinuity : Set.of(
                FrameSynthesisContract.Discontinuity.RESIZE,
                FrameSynthesisContract.Discontinuity.WORLD_RELOAD,
                FrameSynthesisContract.Discontinuity.GENERATION_SWITCH)) {
            FrameSynthesisContract.RenderedFrame reset = frame(
                    2L,
                    temporalContract(),
                    valid.current().depth(),
                    valid.current().motion(),
                    valid.current().reactiveMask(),
                    valid.current().sdrUi(),
                    false,
                    Set.of(discontinuity)
            );
            FrameSynthesisContract.Request invalid = copy(valid, reset, valid.previous(),
                    valid.generatedPresentation(), valid.drawableOwnership(), valid.inFlightOwnership());
            require(FrameSynthesisContract.evaluate(invalid).rejectionReasons().contains(
                            FrameSynthesisContract.RejectionReason.HISTORY_DISCONTINUITY),
                    discontinuity + " did not invalidate synthesis history");
        }
    }

    private static void testGenerationTransitionsFailClosed() {
        FrameSynthesisContract.Request valid = validRequest(MetalExecutorKind.METAL3);
        FrameState source = valid.current().state();
        for (FrameState changed : Set.of(
                stateWithGenerationAndModes(
                        source,
                        source.lightingGenerationId() + 1L,
                        source.outputGenerationId(),
                        source.lightingMode(),
                        source.outputMode()
                ),
                stateWithGenerationAndModes(
                        source,
                        source.lightingGenerationId(),
                        source.outputGenerationId() + 1L,
                        source.lightingMode(),
                        source.outputMode()
                ),
                stateWithGenerationAndModes(
                        source,
                        source.lightingGenerationId(),
                        source.outputGenerationId(),
                        LightingMode.METALLUM,
                        source.outputMode()
                ),
                stateWithGenerationAndModes(
                        source,
                        source.lightingGenerationId(),
                        source.outputGenerationId(),
                        source.lightingMode(),
                        DisplayOutputMode.SDR
                )
        )) {
            FrameSynthesisContract.RenderedFrame changedFrame = frameWithState(valid.current(), changed);
            FrameSynthesisContract.Request invalid = copy(
                    valid,
                    changedFrame,
                    valid.previous(),
                    valid.generatedPresentation(),
                    valid.drawableOwnership(),
                    valid.inFlightOwnership()
            );
            Set<FrameSynthesisContract.RejectionReason> reasons =
                    FrameSynthesisContract.evaluate(invalid).rejectionReasons();
            require(reasons.contains(FrameSynthesisContract.RejectionReason.GENERATION_MISMATCH)
                            && reasons.contains(FrameSynthesisContract.RejectionReason.HISTORY_DISCONTINUITY),
                    "lighting/output generation transition did not fail closed: " + reasons);
        }
    }

    private static void testPresentationAndOwnershipFailures() {
        FrameSynthesisContract.Request valid = validRequest(MetalExecutorKind.METAL3);
        FrameSynthesisContract.DrawableOwnership oneSlot =
                FrameSynthesisContract.DrawableOwnership.preparation("drawable-owner", 1);
        FrameSynthesisContract.InFlightGenerationOwnership shortLease =
                new FrameSynthesisContract.InFlightGenerationOwnership(7L, 9L, 10L, Set.of());
        FrameSynthesisContract.Request invalid = copy(
                valid,
                valid.current(),
                valid.previous(),
                Optional.empty(),
                oneSlot,
                shortLease
        );
        Set<FrameSynthesisContract.RejectionReason> reasons =
                FrameSynthesisContract.evaluate(invalid).rejectionReasons();
        require(reasons.contains(
                        FrameSynthesisContract.RejectionReason.GENERATED_PRESENTATION_NOT_DECLARED)
                        && reasons.contains(
                        FrameSynthesisContract.RejectionReason.IN_FLIGHT_OWNERSHIP_INSUFFICIENT),
                "missing generated presentation/lease did not fail closed");

        FrameSynthesisContract.PresentationIntent outOfOrder = new FrameSynthesisContract.PresentationIntent(
                9L,
                FrameSynthesisContract.PresentationKind.GENERATED_FRAME,
                2L,
                99L,
                "drawable-owner"
        );
        FrameSynthesisContract.Request ordering = copy(valid, valid.current(), valid.previous(),
                Optional.of(outOfOrder), oneSlot, valid.inFlightOwnership());
        Set<FrameSynthesisContract.RejectionReason> orderingReasons =
                FrameSynthesisContract.evaluate(ordering).rejectionReasons();
        require(orderingReasons.contains(FrameSynthesisContract.RejectionReason.PRESENTATION_ORDER_INVALID)
                        && orderingReasons.contains(
                        FrameSynthesisContract.RejectionReason.DRAWABLE_OWNERSHIP_INSUFFICIENT),
                "presentation/drawable ownership violations were accepted");
    }

    private static void testCadenceAndImmutableOwnership() {
        FrameSynthesisContract.Request valid = validRequest(MetalExecutorKind.METAL3);
        require(valid.cadence().renderFps() == 60.0
                        && valid.cadence().generatedFps() == 60.0
                        && valid.cadence().displayFps() == 120.0
                        && valid.cadence().presentationFps() == 120.0,
                "real/generated/display cadence became coupled");

        Set<String> mutable = new HashSet<>(Set.of("world"));
        FrameSynthesisContract.InFlightGenerationOwnership ownership =
                new FrameSynthesisContract.InFlightGenerationOwnership(1L, 2L, 3L, mutable);
        mutable.add("late");
        require(!ownership.retainedResourceIds().contains("late"),
                "in-flight ownership retained mutable input storage");
        expectUnsupported(() -> ownership.retainedResourceIds().add("mutate"));
    }

    private static FrameSynthesisContract.Request validRequest(final MetalExecutorKind executor) {
        FrameSynthesisContract.RenderedFrame previous = frame(
                1L, temporalContract(),
                Optional.of(texture("previous-depth", FrameSynthesisContract.TextureRole.DEPTH, RENDER_EXTENT)),
                Optional.of(texture("previous-motion", FrameSynthesisContract.TextureRole.MOTION, RENDER_EXTENT)),
                Optional.of(texture("previous-reactive", FrameSynthesisContract.TextureRole.REACTIVE_MASK,
                        RENDER_EXTENT)),
                Optional.of(texture("previous-ui", FrameSynthesisContract.TextureRole.SDR_UI, DISPLAY_EXTENT)),
                false, Set.of());
        FrameSynthesisContract.RenderedFrame current = frame(
                2L, temporalContract(),
                Optional.of(texture("current-depth", FrameSynthesisContract.TextureRole.DEPTH, RENDER_EXTENT)),
                Optional.of(texture("current-motion", FrameSynthesisContract.TextureRole.MOTION, RENDER_EXTENT)),
                Optional.of(texture("current-reactive", FrameSynthesisContract.TextureRole.REACTIVE_MASK,
                        RENDER_EXTENT)),
                Optional.of(texture("current-ui", FrameSynthesisContract.TextureRole.SDR_UI, DISPLAY_EXTENT)),
                false, Set.of());
        FrameSynthesisContract.PresentationIntent real = new FrameSynthesisContract.PresentationIntent(
                10L, FrameSynthesisContract.PresentationKind.REAL_FRAME, 2L, 100L, "drawable-owner");
        FrameSynthesisContract.PresentationIntent generated = new FrameSynthesisContract.PresentationIntent(
                11L, FrameSynthesisContract.PresentationKind.GENERATED_FRAME, 2L, 110L, "drawable-owner");
        Set<String> retained = new HashSet<>(current.resourceIds());
        retained.addAll(previous.resourceIds());
        MetalCapabilities capabilities = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.METAL4_CORE,
                MetalCapabilities.Feature.METALFX_TEMPORAL,
                MetalCapabilities.Feature.METALFX_FRAME_INTERPOLATION
        );
        return new FrameSynthesisContract.Request(
                current,
                Optional.of(previous),
                real,
                Optional.of(generated),
                new FrameSynthesisContract.DisplayCadence(60.0, 60.0, 120.0),
                FrameSynthesisContract.DrawableOwnership.preparation("drawable-owner", 2),
                new FrameSynthesisContract.InFlightGenerationOwnership(7L, 9L, 11L, retained),
                capabilities,
                executor
        );
    }

    private static FrameSynthesisContract.Request copy(
            final FrameSynthesisContract.Request source,
            final FrameSynthesisContract.RenderedFrame current,
            final Optional<FrameSynthesisContract.RenderedFrame> previous,
            final Optional<FrameSynthesisContract.PresentationIntent> generated,
            final FrameSynthesisContract.DrawableOwnership drawable,
            final FrameSynthesisContract.InFlightGenerationOwnership ownership
    ) {
        return new FrameSynthesisContract.Request(
                current, previous, source.realPresentation(), generated, source.cadence(), drawable,
                ownership, source.capabilities(), source.executor());
    }

    private static FrameSynthesisContract.RenderedFrame frame(
            final long frameId,
            final FrameContract contract,
            final Optional<FrameSynthesisContract.TextureInput> depth,
            final Optional<FrameSynthesisContract.TextureInput> motion,
            final Optional<FrameSynthesisContract.TextureInput> reactive,
            final Optional<FrameSynthesisContract.TextureInput> ui,
            final boolean worldContainsUi,
            final Set<FrameSynthesisContract.Discontinuity> discontinuities
    ) {
        FrameState.Transforms transforms = FrameState.Transforms.identity();
        FrameState state = new FrameState(
                contract,
                frameId,
                7L,
                8L,
                3L,
                4L,
                LightingMode.LEGACY,
                DisplayOutputMode.HDR,
                transforms,
                transforms,
                RENDER_EXTENT,
                DISPLAY_EXTENT,
                1.0,
                1.0,
                FrameState.JitterOffset.ZERO,
                Set.of()
        );
        return new FrameSynthesisContract.RenderedFrame(
                state,
                9L,
                texture("frame-" + frameId + "-world", FrameSynthesisContract.TextureRole.WORLD_COLOR,
                        DISPLAY_EXTENT),
                depth,
                motion,
                reactive,
                ui,
                worldContainsUi,
                discontinuities
        );
    }

    private static FrameState stateWithGenerationAndModes(
            final FrameState source,
            final long lightingGeneration,
            final long outputGeneration,
            final LightingMode lightingMode,
            final DisplayOutputMode outputMode
    ) {
        return new FrameState(
                source.contract(),
                source.frameId(),
                source.rendererGenerationId(),
                source.historyGeneration(),
                lightingGeneration,
                outputGeneration,
                lightingMode,
                outputMode,
                source.currentTransforms(),
                source.previousTransforms(),
                source.renderExtent(),
                source.displayExtent(),
                source.exposure(),
                source.preExposure(),
                source.jitterOffset(),
                Set.of()
        );
    }

    private static FrameSynthesisContract.RenderedFrame frameWithState(
            final FrameSynthesisContract.RenderedFrame source,
            final FrameState state
    ) {
        return new FrameSynthesisContract.RenderedFrame(
                state,
                source.inFlightGeneration(),
                source.worldColor(),
                source.depth(),
                source.motion(),
                source.reactiveMask(),
                source.sdrUi(),
                source.worldColorContainsUi(),
                source.discontinuities()
        );
    }

    private static FrameSynthesisContract.TextureInput texture(
            final String id,
            final FrameSynthesisContract.TextureRole role,
            final FrameState.Extent extent
    ) {
        String format = switch (role) {
            case WORLD_COLOR -> "rgba16_float";
            case DEPTH -> "depth32_float";
            case MOTION -> "rg16_float";
            case REACTIVE_MASK, SDR_UI -> "r8_unorm";
        };
        return new FrameSynthesisContract.TextureInput(id, role, format, extent, 7L);
    }

    private static FrameContract temporalContract() {
        return new FrameContract(
                FrameContract.CURRENT_VERSION,
                new FrameContract.MotionVectorContract(
                        FrameContract.MotionVectorAvailability.AVAILABLE,
                        FrameContract.MotionVectorDelta.PREVIOUS_NDC_MINUS_CURRENT_NDC,
                        FrameContract.MotionVectorUnits.RENDER_PIXELS,
                        FrameContract.HorizontalAxis.POSITIVE_RIGHT,
                        FrameContract.VerticalAxis.POSITIVE_DOWN
                ),
                new FrameContract.DepthContract(FrameContract.DepthRange.ZERO_TO_ONE, true),
                FrameContract.ReactiveMaskAvailability.AVAILABLE,
                FrameContract.UiComposition.SEPARATE_SDR_TEXTURE
        );
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
