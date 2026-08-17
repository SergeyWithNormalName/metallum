package com.metallum.client.renderer.temporal;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.RenderContractMode;
import com.metallum.client.renderer.MetalCapabilities;
import com.metallum.client.renderer.MetalExecutorKind;

import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.RendererConfig;
import com.metallum.client.renderer.interpolation.FrameInterpolationPolicy;
import com.metallum.client.renderer.interpolation.FrameInterpolationCompatibilityProfile;
import com.metallum.client.renderer.interpolation.FrameInterpolationRuntimeStatus;
import com.metallum.client.metalfx.GeneratedFrameRateTracker;

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
        testFrameInterpolationPolicyAndContractRules();
        testFrameInterpolationCompatibilityProfile();
        testFrameInterpolationRuntimeStatus();
        testGeneratedFrameRateTracker();
        System.out.println("Frame synthesis P6 contract tests passed");
    }

    private static void testGeneratedFrameRateTracker() {
        GeneratedFrameRateTracker tracker = new GeneratedFrameRateTracker();
        require(tracker.shouldSample(1_000_000_000L),
                "generated-frame tracker did not request its initial sample");
        tracker.observe(1_000_000_000L, 7L, 100L);
        require(tracker.framesPerSecond() == 0 && tracker.presentedCount() == 100L,
                "initial cumulative count was mistaken for a generated-frame burst");
        require(!tracker.shouldSample(1_500_000_000L) && tracker.shouldSample(2_000_000_000L),
                "generated-frame tracker sampling interval drifted");

        tracker.observe(2_000_000_000L, 7L, 160L);
        require(tracker.framesPerSecond() == 60 && tracker.presentedCount() == 160L,
                "generated-frame rate did not use the on-screen cumulative delta");
        tracker.observe(3_000_000_000L, 7L, 160L);
        require(tracker.framesPerSecond() == 0,
                "stopped generated presentations retained a stale non-zero rate");

        tracker.observe(7_000_000_000L, 7L, 220L);
        require(tracker.framesPerSecond() == 0 && tracker.presentedCount() == 220L,
                "long HUD pause produced an averaged or synthetic generated-frame rate");
        tracker.observe(8_000_000_000L, 8L, 10L);
        require(tracker.framesPerSecond() == 0 && tracker.presentedCount() == 10L,
                "new FI session produced a negative or wrapped rate");
        tracker.observe(9_000_000_000L, 8L, 40L);
        require(tracker.framesPerSecond() == 30,
                "generated-frame tracker did not recover after a counter reset");
    }

    private static void testFrameInterpolationRuntimeStatus() {
        FrameInterpolationRuntimeStatus warming =
                FrameInterpolationRuntimeStatus.fromPackedNative(
                        (17L << 16) | (1L << 8) | 1L,
                        9L
                );
        require(warming.state() == FrameInterpolationRuntimeStatus.State.WARMING
                        && warming.reason()
                        == FrameInterpolationRuntimeStatus.Reason.MEASURING_ON_GLASS
                        && warming.sessionId() == 9L
                        && warming.presentedGeneratedCount() == 17L,
                "packed FI warm-up/session counter status was decoded incorrectly");
        FrameInterpolationRuntimeStatus active =
                FrameInterpolationRuntimeStatus.fromPackedNative(
                        (42L << 16) | 2L,
                        9L
                );
        require(active.state() == FrameInterpolationRuntimeStatus.State.ACTIVE
                        && active.reason() == FrameInterpolationRuntimeStatus.Reason.NONE
                        && active.presentedGeneratedCount() == 42L,
                "native FI Active was not tied to the shown-frame counter");
        FrameInterpolationRuntimeStatus failed =
                FrameInterpolationRuntimeStatus.fromPackedNative(
                        (43L << 16) | (2L << 8) | 3L,
                        9L
                );
        require(failed.state() == FrameInterpolationRuntimeStatus.State.UNAVAILABLE
                        && failed.reason()
                        == FrameInterpolationRuntimeStatus.Reason.ON_GLASS_CADENCE
                        && failed.presentedGeneratedCount() == 43L,
                "on-glass circuit-breaker status was not decoded fail-closed");
        require(FrameInterpolationRuntimeStatus.fromPackedNative(99L, 9L).state()
                        == FrameInterpolationRuntimeStatus.State.UNAVAILABLE,
                "unknown native FI state did not fail closed");
        FrameInterpolationRuntimeStatus awaiting =
                FrameInterpolationRuntimeStatus.fromPackedNative(
                        (5L << 8) | 1L,
                        10L
                );
        require(awaiting.state() == FrameInterpolationRuntimeStatus.State.WARMING
                        && awaiting.reason()
                        == FrameInterpolationRuntimeStatus.Reason.AWAITING_PRODUCTION_SOURCE,
                "FI workspace claimed to measure on-glass cadence before a world source");
        require(!FrameInterpolationRuntimeStatus.warmupBudgetExhausted(
                        FrameInterpolationRuntimeStatus.State.WARMING,
                        FrameInterpolationRuntimeStatus.Reason.MEASURING_ON_GLASS,
                        7_999L,
                        8_000L
                ),
                "FI warm-up watchdog fired before its bounded probation ended");
        require(FrameInterpolationRuntimeStatus.warmupBudgetExhausted(
                        FrameInterpolationRuntimeStatus.State.WARMING,
                        FrameInterpolationRuntimeStatus.Reason.MEASURING_ON_GLASS,
                        8_000L,
                        8_000L
                ),
                "FI warm-up watchdog did not end an indefinitely unproven session");
        require(!FrameInterpolationRuntimeStatus.warmupBudgetExhausted(
                        FrameInterpolationRuntimeStatus.State.ACTIVE,
                        FrameInterpolationRuntimeStatus.Reason.NONE,
                        80_000L,
                        8_000L
                ),
                "FI warm-up watchdog quarantined an already proven session");
        require(FrameInterpolationRuntimeStatus.boundedWarmupSample(
                        1_000L, 51_000L, 50_000L
                ) == 50_000L
                        && FrameInterpolationRuntimeStatus.boundedWarmupSample(
                        1_000L, 51_001L, 50_000L
                ) == 50_000L
                        && FrameInterpolationRuntimeStatus.boundedWarmupSample(
                        2_000L, 1_000L, 50_000L
                ) == 0L,
                "FI warm-up failed to bound a slow active frame or counted a regressing clock");
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

        FrameState styleResetState = new FrameState(
                valid.current().state().contract(),
                2L, 1L, 1L, 1L, 1L, 1L,
                RenderContractMode.LEGACY, LightingModel.VANILLA, DisplayOutputMode.HDR,
                LightingPreset.BALANCED, com.metallum.client.renderer.RendererFeatureMask.NONE,
                MetalExecutorKind.METAL3, 1, FrameState.ResourceBytes.NONE, FrameState.AdvancedLightingWork.NONE,
                FrameState.Transforms.identity(), FrameState.Transforms.identity(),
                RENDER_EXTENT, DISPLAY_EXTENT, 1.0, 1.0, FrameState.JitterOffset.ZERO,
                Set.of(FrameState.HistoryResetReason.VISUAL_STYLE_CHANGE)
        );
        FrameSynthesisContract.RenderedFrame styleResetFrame = new FrameSynthesisContract.RenderedFrame(
                styleResetState, 1L, valid.current().worldColor(),
                valid.current().depth(), valid.current().motion(),
                valid.current().reactiveMask(), valid.current().sdrUi(),
                false, Set.of()
        );
        FrameSynthesisContract.Request styleResetRequest = copy(
                valid, styleResetFrame, valid.previous(),
                valid.generatedPresentation(), valid.drawableOwnership(), valid.inFlightOwnership()
        );
        require(FrameSynthesisContract.evaluate(styleResetRequest).rejectionReasons().contains(
                        FrameSynthesisContract.RejectionReason.HISTORY_DISCONTINUITY),
                "VISUAL_STYLE_CHANGE history reset reason did not invalidate synthesis history");
    }

    private static void testGenerationTransitionsFailClosed() {
        FrameSynthesisContract.Request valid = validRequest(MetalExecutorKind.METAL3);
        FrameState source = valid.current().state();
        for (FrameState changed : Set.of(
                stateWithGenerationAndModes(
                        source,
                        source.lightingGenerationId() + 1L,
                        source.outputGenerationId(),
                        source.renderContractMode(),
                        source.outputMode()
                ),
                stateWithGenerationAndModes(
                        source,
                        source.lightingGenerationId(),
                        source.outputGenerationId() + 1L,
                        source.renderContractMode(),
                        source.outputMode()
                ),
                stateWithGenerationAndModes(
                        source,
                        source.lightingGenerationId(),
                        source.outputGenerationId(),
                        RenderContractMode.METALLUM,
                        source.outputMode()
                ),
                stateWithGenerationAndModes(
                        source,
                        source.lightingGenerationId(),
                        source.outputGenerationId(),
                        source.renderContractMode(),
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

        // Generated N-1/2 after or equal to real N is invalid (generated ID must be < real ID)
        FrameSynthesisContract.PresentationIntent outOfOrder = new FrameSynthesisContract.PresentationIntent(
                11L,
                FrameSynthesisContract.PresentationKind.GENERATED_FRAME,
                2L,
                110L,
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

    private static void testFrameInterpolationPolicyAndContractRules() {
        RendererConfig configOff = RendererConfig.defaults();
        RendererConfig configOn = configOff.withFrameInterpolation(true);
        long fiSnapshot = 1L | (1L << 10) | (1L << 14) | (120L << 48); // METAL3_BASE | METALFX_FRAME_INTERPOLATION | DISPLAY_REFRESH | 120 Hz
        MetalCapabilities capabilitiesWithFI = MetalCapabilities.fromNativeSnapshot(
                fiSnapshot, new com.metallum.client.hdr.EdrCapabilities(1.0f, 1.0f)
        );
        MetalCapabilities capabilitiesNoFI = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE
        );

        // 1. User request disabled
        FrameInterpolationPolicy.Evaluation evalDisabled = FrameInterpolationPolicy.evaluate(
                configOff, capabilitiesWithFI, FrameInterpolationPolicy.UpstreamMode.FIXED_TEMPORAL,
                true, 60.0, Set.of()
        );
        require(!evalDisabled.requested() && !evalDisabled.profileEligible() && !evalDisabled.effectiveAdmitted()
                        && evalDisabled.eligibilityReason() == FrameInterpolationPolicy.EligibilityReason.USER_REQUEST_DISABLED
                        && evalDisabled.effectiveReason() == FrameInterpolationPolicy.EffectiveReason.NOT_PROFILE_ELIGIBLE,
                "user request disabled policy test failed");

        // 2. Feature unsupported on device
        FrameInterpolationPolicy.Evaluation evalNoCap = FrameInterpolationPolicy.evaluate(
                configOn, capabilitiesNoFI, FrameInterpolationPolicy.UpstreamMode.FIXED_TEMPORAL,
                true, 60.0, Set.of()
        );
        require(evalNoCap.requested() && !evalNoCap.profileEligible() && !evalNoCap.effectiveAdmitted()
                        && evalNoCap.eligibilityReason() == FrameInterpolationPolicy.EligibilityReason.FEATURE_UNSUPPORTED,
                "unsupported capability policy test failed");

        // 3. Fixed Temporal stays fail-closed until the native fixed-profile probe succeeds.
        FrameInterpolationPolicy.Evaluation evalEligible = FrameInterpolationPolicy.evaluate(
                configOn, capabilitiesWithFI, FrameInterpolationPolicy.UpstreamMode.FIXED_TEMPORAL,
                true, 60.0, Set.of()
        );
        require(evalEligible.requested() && evalEligible.profileEligible() && !evalEligible.effectiveAdmitted()
                        && evalEligible.eligibilityReason() == FrameInterpolationPolicy.EligibilityReason.ELIGIBLE_FIXED_TEMPORAL
                        && evalEligible.effectiveReason() == FrameInterpolationPolicy.EffectiveReason.NATIVE_PROFILE_UNVALIDATED,
                "unvalidated Fixed Temporal profile was admitted");

        long validatedFiSnapshot = fiSnapshot | (1L << 17);
        MetalCapabilities validatedCapabilitiesWithFI = MetalCapabilities.fromNativeSnapshot(
                validatedFiSnapshot, new com.metallum.client.hdr.EdrCapabilities(1.0f, 1.0f)
        );
        FrameInterpolationPolicy.Evaluation evalAdmitted = FrameInterpolationPolicy.evaluate(
                configOn, validatedCapabilitiesWithFI,
                FrameInterpolationPolicy.UpstreamMode.FIXED_TEMPORAL, true, 60.0, Set.of()
        );
        require(evalAdmitted.requested() && evalAdmitted.profileEligible()
                        && evalAdmitted.effectiveAdmitted()
                        && evalAdmitted.effectiveReason()
                        == FrameInterpolationPolicy.EffectiveReason.ADMITTED_FIXED_TEMPORAL,
                "validated Fixed Temporal profile was not admitted");

        FrameInterpolationPolicy.Evaluation evalVsyncOff = FrameInterpolationPolicy.evaluate(
                configOn, validatedCapabilitiesWithFI,
                FrameInterpolationPolicy.UpstreamMode.FIXED_TEMPORAL, false, 60.0, Set.of()
        );
        require(evalVsyncOff.requested() && !evalVsyncOff.profileEligible()
                        && !evalVsyncOff.effectiveAdmitted()
                        && evalVsyncOff.eligibilityReason()
                        == FrameInterpolationPolicy.EligibilityReason.DISPLAY_SYNC_DISABLED,
                "VSync-off presentation admitted Frame Interpolation");

        // 4. Dynamic Temporal and Native remain later stages; validated Spatial
        // is a standalone Frame Interpolation profile as of Stage 10.
        for (FrameInterpolationPolicy.UpstreamMode mode : Set.of(
                FrameInterpolationPolicy.UpstreamMode.DYNAMIC_TEMPORAL,
                FrameInterpolationPolicy.UpstreamMode.NATIVE)) {
            FrameInterpolationPolicy.Evaluation evalUpstream = FrameInterpolationPolicy.evaluate(
                    configOn, capabilitiesWithFI, mode, true, 60.0, Set.of()
            );
            require(!evalUpstream.profileEligible()
                            && evalUpstream.eligibilityReason() == FrameInterpolationPolicy.EligibilityReason.UNSUPPORTED_UPSTREAM_MODE,
                    mode + " was not rejected by policy");
        }
        FrameInterpolationPolicy.Evaluation spatial = FrameInterpolationPolicy.evaluate(
                configOn, validatedCapabilitiesWithFI,
                FrameInterpolationPolicy.UpstreamMode.SPATIAL, true, 60.0, Set.of()
        );
        require(spatial.profileEligible() && spatial.effectiveAdmitted()
                        && spatial.eligibilityReason()
                        == FrameInterpolationPolicy.EligibilityReason.ELIGIBLE_SPATIAL
                        && spatial.effectiveReason()
                        == FrameInterpolationPolicy.EffectiveReason.ADMITTED_SPATIAL,
                "validated Spatial profile was not admitted");

        // 5. Adaptive FI accepts 30 -> 60 through 60 -> 120 on a 120-Hz panel.
        FrameInterpolationPolicy.Evaluation evalLowCadence = FrameInterpolationPolicy.evaluate(
                configOn, capabilitiesWithFI, FrameInterpolationPolicy.UpstreamMode.FIXED_TEMPORAL,
                true, 29.0, Set.of()
        );
        require(!evalLowCadence.profileEligible()
                        && evalLowCadence.eligibilityReason() == FrameInterpolationPolicy.EligibilityReason.CADENCE_OUT_OF_BOUNDS,
                "low cadence was not rejected");

        FrameInterpolationPolicy.Evaluation evalAdaptiveCadence = FrameInterpolationPolicy.evaluate(
                configOn, validatedCapabilitiesWithFI,
                FrameInterpolationPolicy.UpstreamMode.FIXED_TEMPORAL,
                true, 40.0, Set.of()
        );
        require(evalAdaptiveCadence.profileEligible() && evalAdaptiveCadence.effectiveAdmitted(),
                "valid 40 -> 80 adaptive cadence was rejected at Java admission");

        FrameInterpolationPolicy.Evaluation evalHighCadence = FrameInterpolationPolicy.evaluate(
                configOn, capabilitiesWithFI, FrameInterpolationPolicy.UpstreamMode.FIXED_TEMPORAL,
                true, 75.0, Set.of()
        );
        require(!evalHighCadence.profileEligible()
                        && evalHighCadence.eligibilityReason() == FrameInterpolationPolicy.EligibilityReason.CADENCE_OUT_OF_BOUNDS,
                "high cadence was not rejected");

        // 6. History discontinuity invalidates eligibility
        FrameInterpolationPolicy.Evaluation evalDiscont = FrameInterpolationPolicy.evaluate(
                configOn, capabilitiesWithFI, FrameInterpolationPolicy.UpstreamMode.FIXED_TEMPORAL,
                true, 60.0, Set.of(FrameSynthesisContract.Discontinuity.RESIZE)
        );
        require(!evalDiscont.profileEligible()
                        && evalDiscont.eligibilityReason() == FrameInterpolationPolicy.EligibilityReason.HISTORY_DISCONTINUITY,
                "discontinuity was not rejected by policy");
    }

    private static void testFrameInterpolationCompatibilityProfile() {
        RendererConfig configOn = RendererConfig.defaults().withFrameInterpolation(true);
        long compatibleSnapshot = 1L
                | (1L << 9)   // METALFX_TEMPORAL
                | (1L << 10)  // METALFX_FRAME_INTERPOLATION
                | (1L << 13)  // REQUIRED_TEXTURE_FORMATS_USAGES
                | (1L << 14)  // DISPLAY_REFRESH
                | (1L << 16)  // TEMPORAL_PROFILE
                | (1L << 17)  // FRAME_INTERPOLATION_PROFILE
                | (120L << 48);
        MetalCapabilities compatible = MetalCapabilities.fromNativeSnapshot(
                compatibleSnapshot,
                new com.metallum.client.hdr.EdrCapabilities(1.0f, 1.0f)
        );
        FrameInterpolationCompatibilityProfile.Decision admitted =
                FrameInterpolationCompatibilityProfile.evaluate(configOn, compatible, true);
        require(admitted.active()
                        && admitted.temporalMode()
                        == com.metallum.client.metalfx.TemporalScalingMode.ULTRA_PERFORMANCE
                        && admitted.sourceFrameLimit() == 30,
                "FI Auto did not resolve the non-persistent 30 -> 60 Ultra profile");
        require(FrameInterpolationCompatibilityProfile.applySourceLimit(60, true) == 30
                        && FrameInterpolationCompatibilityProfile.applySourceLimit(20, true) == 20
                        && FrameInterpolationCompatibilityProfile.applySourceLimit(60, false) == 60,
                "FI Auto source limiter did not preserve lower/user-disabled limits");

        FrameInterpolationCompatibilityProfile.Decision noSync =
                FrameInterpolationCompatibilityProfile.evaluate(configOn, compatible, false);
        require(!noSync.active()
                        && noSync.reason()
                        == FrameInterpolationCompatibilityProfile.Reason.DISPLAY_SYNC_DISABLED,
                "FI Auto activated without VSync");
        FrameInterpolationCompatibilityProfile.Decision disabled =
                FrameInterpolationCompatibilityProfile.evaluate(
                        RendererConfig.defaults(), compatible, true
                );
        require(!disabled.active()
                        && disabled.reason()
                        == FrameInterpolationCompatibilityProfile.Reason.USER_DISABLED,
                "FI Auto changed the upstream profile while disabled");
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
        // Generated N-1/2 presentation ID (9L) precedes real N presentation ID (10L)
        FrameSynthesisContract.PresentationIntent generated = new FrameSynthesisContract.PresentationIntent(
                9L, FrameSynthesisContract.PresentationKind.GENERATED_FRAME, 2L, 90L, "drawable-owner");
        FrameSynthesisContract.PresentationIntent real = new FrameSynthesisContract.PresentationIntent(
                10L, FrameSynthesisContract.PresentationKind.REAL_FRAME, 2L, 100L, "drawable-owner");
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
                new FrameSynthesisContract.InFlightGenerationOwnership(7L, 9L, 10L, retained),
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
                2L,
                3L,
                4L,
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
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
            final RenderContractMode lightingMode,
            final DisplayOutputMode outputMode
    ) {
        return new FrameState(
                source.contract(),
                source.frameId(),
                source.rendererGenerationId(),
                source.historyGeneration(),
                source.renderContractGenerationId(),
                lightingGeneration,
                outputGeneration,
                lightingMode,
                source.lightingModel(),
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
