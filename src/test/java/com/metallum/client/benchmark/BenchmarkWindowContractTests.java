package com.metallum.client.benchmark;

import com.metallum.client.renderer.interpolation.FrameInterpolationRuntimeStatus;
import com.metallum.client.metalfx.BenchmarkScalingMode;

import java.util.List;

public final class BenchmarkWindowContractTests {
    private BenchmarkWindowContractTests() {
    }

    public static void main(final String[] args) {
        acceptsHiDpiLogicalWindowForExactBackingFramebuffer();
        rejectsWrongBackingFramebuffer();
        rejectsNonLiveLogicalWindow();
        retriesInactiveBenchmarkWindowFocusWithoutSpammingActiveRuns();
        closesNativeTimingOnTheTickAfterTheLastMeasuredSubmission();
        emitsFrozenEvidenceOnlyAfterRouteApply();
        acceptsOnlyOneFrozenOffModeForG4();
        acceptsRequiredOnGlassGeneratedDelta();
        rejectsInsufficientOrRegressingGeneratedCounter();
        derivesMonotonicFiTransportCounterDelta();
        acceptsHealthyFiTransportWithBoundedSnapshotSkew();
        rejectsMissingRealFramesAndBackpressure();
        acceptsQuantizedProMotionCadenceByAggregateMean();
        rejectsSlowOrSparseFiCadenceAndLateTails();
        validatesOnlyRuntimeGateFallbackReasons();
        requiresStableG6OrbitGenerationAndLatencyEvidence();
        requiresIndependentG6MutationGenerationAndSampleEvidence();
        acceptsG6ClearWithoutRestoringRouteClock();
        requiresNewCurrentG6TerrainBindingAfterChunkReload();
        scopesG6TerrainQueueGateToBindingRecovery();
        validatesExactG6MatrixFinalCensus();
        keepsTeleportAllCascadeStabilizationSeparateFromTheNearSla();
        requiresSufficientG6MatrixRecoveryWindows();
        requiresSufficientG6MatrixScrollWindows();
        acceptsNearOnlyCoverageForRapidScrollAndTeleportOutHandoff();
        System.out.println("Benchmark window contract tests passed");
    }

    private static void acceptsHiDpiLogicalWindowForExactBackingFramebuffer() {
        require(MetalFxBenchmarkController.hasExactTargetFramebuffer(
                        3024, 1964, 3024, 1964, 1512, 982),
                "Retina logical points must not invalidate the exact backing framebuffer");
    }

    private static void rejectsWrongBackingFramebuffer() {
        require(!MetalFxBenchmarkController.hasExactTargetFramebuffer(
                        3022, 1964, 3024, 1964, 1512, 982),
                "wrong backing width must fail the benchmark contract");
    }

    private static void rejectsNonLiveLogicalWindow() {
        require(!MetalFxBenchmarkController.hasExactTargetFramebuffer(
                        3024, 1964, 3024, 1964, 0, 982),
                "zero-width logical window must fail the benchmark contract");
    }

    private static void retriesInactiveBenchmarkWindowFocusWithoutSpammingActiveRuns() {
        require(MetalFxBenchmarkController.shouldRequestBenchmarkWindowFocus(false, 0)
                        && MetalFxBenchmarkController.shouldRequestBenchmarkWindowFocus(false, 30)
                        && !MetalFxBenchmarkController.shouldRequestBenchmarkWindowFocus(false, 29),
                "an inactive benchmark window must receive bounded focus retries");
        require(!MetalFxBenchmarkController.shouldRequestBenchmarkWindowFocus(true, 0)
                        && !MetalFxBenchmarkController.shouldRequestBenchmarkWindowFocus(true, 30),
                "an already active benchmark window must not receive focus requests");
    }

    private static void closesNativeTimingOnTheTickAfterTheLastMeasuredSubmission() {
        require(MetalFxBenchmarkController.shouldCloseBenchmarkTiming(true, 0),
                "the first MEASURE_END boundary tick must close native timing");
        require(!MetalFxBenchmarkController.shouldCloseBenchmarkTiming(false, 0)
                        && !MetalFxBenchmarkController.shouldCloseBenchmarkTiming(true, 1),
                "native timing must close neither on MEASURE_START nor twice");
    }

    private static void emitsFrozenEvidenceOnlyAfterRouteApply() {
        require(!MetalFxBenchmarkController.shouldEmitServerTicksFrozenEvidence(false, false, true),
                "server freeze evidence must not precede ROUTE_APPLY");
        require(!MetalFxBenchmarkController.shouldEmitServerTicksFrozenEvidence(true, false, false),
                "server freeze evidence requires an observed frozen server");
        require(MetalFxBenchmarkController.shouldEmitServerTicksFrozenEvidence(true, false, true),
                "server freeze evidence must follow ROUTE_APPLY after server confirmation");
        require(!MetalFxBenchmarkController.shouldEmitServerTicksFrozenEvidence(true, true, true),
                "server freeze evidence must be emitted exactly once");
    }

    private static void acceptsOnlyOneFrozenOffModeForG4() {
        require(MetalFxBenchmarkController.isFrozenG4Sequence(
                        List.of(BenchmarkScalingMode.OFF)),
                "one OFF segment must be the only frozen G4 sequence");
        require(!MetalFxBenchmarkController.isFrozenG4Sequence(List.of())
                        && !MetalFxBenchmarkController.isFrozenG4Sequence(List.of(
                        BenchmarkScalingMode.OFF, BenchmarkScalingMode.OFF))
                        && !MetalFxBenchmarkController.isFrozenG4Sequence(List.of(
                        BenchmarkScalingMode.QUALITY)),
                "G4 admitted a missing, changing, or scaled benchmark sequence");
    }

    private static void acceptsRequiredOnGlassGeneratedDelta() {
        require(MetalFxBenchmarkController.hasMinimumGeneratedPresentations(41L, 52L, 11L),
                "exact on-glass generated delta must satisfy the FI minimum");
    }

    private static void rejectsInsufficientOrRegressingGeneratedCounter() {
        require(!MetalFxBenchmarkController.hasMinimumGeneratedPresentations(41L, 51L, 11L),
                "insufficient generated delta must fail FI validation");
        require(!MetalFxBenchmarkController.hasMinimumGeneratedPresentations(52L, 41L, 1L),
                "a regressing generated counter must fail FI validation");
    }

    private static void derivesMonotonicFiTransportCounterDelta() {
        require(MetalFxBenchmarkController.frameInterpolationTransportCounterDelta(17L, 29L) == 12L,
                "FI transport diagnostics must report the measurement-boundary delta");
        require(MetalFxBenchmarkController.frameInterpolationTransportCounterDelta(29L, 17L) == -1L,
                "a regressing process-lifetime FI transport counter must remain visible");
    }

    private static void acceptsHealthyFiTransportWithBoundedSnapshotSkew() {
        require(MetalFxBenchmarkController.hasHealthyFrameInterpolationTransport(
                        240L, 241L, 298L, 0L, 0L, 0L, 240L, 300L, 2L),
                "two in-flight callbacks may straddle the benchmark counter snapshots");
        require(MetalFxBenchmarkController.hasHealthyFrameInterpolationTransport(
                        200L, 204L, 298L, 0L, 0L, 0L, 200L, 300L, 2L),
                "both measurement boundaries may contribute the bounded callback skew");
    }

    private static void rejectsMissingRealFramesAndBackpressure() {
        require(!MetalFxBenchmarkController.hasHealthyFrameInterpolationTransport(
                        250L, 250L, 250L, 0L, 0L, 0L, 240L, 300L, 2L),
                "generated output must not hide missing mandatory real presentations");
        require(!MetalFxBenchmarkController.hasHealthyFrameInterpolationTransport(
                        250L, 250L, 300L, 0L, 1L, 0L, 240L, 300L, 2L),
                "the stable FI benchmark must reject fallback-causing backpressure");
        require(!MetalFxBenchmarkController.hasHealthyFrameInterpolationTransport(
                        -1L, 250L, 300L, 0L, 0L, 0L, 240L, 300L, 2L),
                "regressing transport counters must fail the FI contract");
        require(!MetalFxBenchmarkController.hasHealthyFrameInterpolationTransport(
                        300L, 240L, 298L, 0L, 0L, 0L, 240L, 300L, 2L),
                "accepted FI jobs that never reach glass must fail validation");
        require(!MetalFxBenchmarkController.hasHealthyFrameInterpolationTransport(
                        240L, 240L, 298L, 1L, 0L, 0L, 240L, 300L, 2L),
                "a late generated drop must fail the stable FI profile");
        require(!MetalFxBenchmarkController.hasHealthyFrameInterpolationTransport(
                        240L, 240L, 298L, 0L, 0L, 1L, 240L, 300L, 2L),
                "an on-glass order regression must fail the FI transport contract");
    }

    private static void acceptsQuantizedProMotionCadenceByAggregateMean() {
        require(MetalFxBenchmarkController.hasHealthyFrameInterpolationCadence(
                        240L,
                        480L,
                        6_000_000_000L,
                        6_000_000_000L,
                        500_000_000L,
                        238L,
                        24L,
                        7L,
                        240L,
                        2L),
                "legal alternating ProMotion steps must pass by their aggregate mean");
        require(MetalFxBenchmarkController.hasPreferredFrameInterpolationMinorLateTail(
                        480L, 24L, 2L),
                "the preferred p95 minor-late target must accept its exact boundary");
    }

    private static void rejectsSlowOrSparseFiCadenceAndLateTails() {
        require(!MetalFxBenchmarkController.hasHealthyFrameInterpolationCadence(
                        240L, 480L, 6_720_000_000L, 6_000_000_000L, 500_000_000L,
                        238L, 0L, 0L, 240L, 2L),
                "a sustained 14-ms stream must fail an 80-Hz aggregate cadence budget");
        require(!MetalFxBenchmarkController.hasHealthyFrameInterpolationCadence(
                        240L, 479L, 5_987_500_000L, 5_987_500_000L, 498_958_333L,
                        238L, 0L, 0L, 240L, 2L),
                "insufficient stable on-glass interval coverage must fail");
        require(MetalFxBenchmarkController.hasHealthyFrameInterpolationCadence(
                        240L, 480L, 6_000_000_000L, 6_000_000_000L, 500_000_000L,
                        238L, 27L, 0L, 240L, 2L),
                "a missed preferred p95 target must not masquerade as transport failure");
        require(!MetalFxBenchmarkController.hasPreferredFrameInterpolationMinorLateTail(
                        480L, 27L, 2L),
                "more than the preferred p95 minor-late budget must remain visible as a warning");
        require(!MetalFxBenchmarkController.hasHealthyFrameInterpolationCadence(
                        240L, 480L, 6_000_000_000L, 6_000_000_000L, 500_000_000L,
                        238L, 0L, 8L, 240L, 2L),
                "more than the p99 severe-late budget must fail");
    }

    private static void validatesOnlyRuntimeGateFallbackReasons() {
        require(MetalFxBenchmarkController.isFrameInterpolationRuntimeFallbackReason(
                        FrameInterpolationRuntimeStatus.Reason.ON_GLASS_CADENCE
                ) && MetalFxBenchmarkController.isFrameInterpolationRuntimeFallbackReason(
                        FrameInterpolationRuntimeStatus.Reason.ON_GLASS_TIMESTAMP
                ) && MetalFxBenchmarkController.isFrameInterpolationRuntimeFallbackReason(
                        FrameInterpolationRuntimeStatus.Reason.WARMUP_TIMEOUT
                ),
                "FI runtime gate reasons did not admit safe-fallback validation");
        require(!MetalFxBenchmarkController.isFrameInterpolationRuntimeFallbackReason(
                        FrameInterpolationRuntimeStatus.Reason.NATIVE_FACTORY_UNAVAILABLE
                ) && !MetalFxBenchmarkController.isFrameInterpolationRuntimeFallbackReason(
                        FrameInterpolationRuntimeStatus.Reason.COORDINATOR_NOT_INSTALLED
                ),
                "unsupported or uninstalled FI was mislabeled as a runtime safe fallback");
    }

    private static void requiresIndependentG6MutationGenerationAndSampleEvidence() {
        require(MetalFxBenchmarkController.g6MatrixMutationRecovered(41L, 42L, 7L, 8L),
                "one new field generation and one class sample must close a G6 mutation");
        require(!MetalFxBenchmarkController.g6MatrixMutationRecovered(41L, 41L, 7L, 8L),
                "a prior mutation's generation must not close the next G6 mutation");
        require(!MetalFxBenchmarkController.g6MatrixMutationRecovered(41L, 42L, 7L, 7L),
                "a generation advance without the expected latency sample must fail");
        require(!MetalFxBenchmarkController.g6MatrixMutationRecovered(41L, 43L, 7L, 9L),
                "coalesced or unrelated extra samples must not qualify one matrix mutation");
    }

    private static void requiresStableG6OrbitGenerationAndLatencyEvidence() {
        require(MetalFxBenchmarkController.g6MatrixOrbitStable(
                        41L, 41L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L),
                "camera-only orbit must preserve field generation and every latency class");
        require(!MetalFxBenchmarkController.g6MatrixOrbitStable(
                        41L, 42L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L),
                "an orbit-triggered field rebuild must fail the stationary-source control");
        require(!MetalFxBenchmarkController.g6MatrixOrbitStable(
                        41L, 41L, 2L, 2L, 3L, 3L, 4L, 5L, 5L, 5L),
                "an orbit-triggered latency sample must fail the stationary-source control");
    }

    private static void acceptsG6ClearWithoutRestoringRouteClock() {
        require(MetalFxBenchmarkController.g6MatrixClearVisible(true, 0.0F),
                "CLEAR must become visible after its single weather mutation");
        require(!MetalFxBenchmarkController.g6MatrixClearVisible(false, 0.0F)
                        && !MetalFxBenchmarkController.g6MatrixClearVisible(true, 0.25F),
                "CLEAR must still reject another dimension or incomplete weather clearing");
    }

    private static void requiresNewCurrentG6TerrainBindingAfterChunkReload() {
        require(MetalFxBenchmarkController.g6MatrixTerrainReloadRecovered(
                        100L, 3L, 101L, 3L, 3L, 1, true, true, true),
                "F3+A must observe a newer exact current terrain bind on the same device");
        require(!MetalFxBenchmarkController.g6MatrixTerrainReloadRecovered(
                        100L, 3L, 100L, 3L, 3L, 1, true, true, true),
                "a pre-F3+A terrain receipt must not qualify the reload");
        require(!MetalFxBenchmarkController.g6MatrixTerrainReloadRecovered(
                        100L, 3L, 101L, 4L, 4L, 1, true, true, true),
                "F3+A must not silently cross a device generation");
        require(!MetalFxBenchmarkController.g6MatrixTerrainReloadRecovered(
                        100L, 3L, 101L, 3L, 3L, 0, true, true, true),
                "a fallback terrain bind must fail the F3+A receipt");
    }

    private static void scopesG6TerrainQueueGateToBindingRecovery() {
        require(MetalFxBenchmarkController.g6MatrixTerrainQueueGateSatisfied(false, false),
                "field/dimension recovery must not wait on an unrelated terrain queue");
        require(MetalFxBenchmarkController.g6MatrixTerrainQueueGateSatisfied(true, true)
                        && !MetalFxBenchmarkController.g6MatrixTerrainQueueGateSatisfied(
                        true, false),
                "terrain reload recovery must still require a drained terrain queue");
    }

    private static void validatesExactG6MatrixFinalCensus() {
        MetalFxBenchmarkController.G6MatrixFinalCensus clean = g6FinalCensus(
                0L, 100L, true, 24_000_000L, 1L, true);
        require(clean.queueConverged() && clean.accountingStable()
                        && clean.latencyCensusComplete() && clean.passed(),
                "an exact drained/stable/all-SLA final census must pass");
        require(!g6FinalCensus(1L, 100L, true, 24_000_000L, 1L, true).passed()
                        && !g6FinalCensus(
                        0L, 99L, true, 24_000_000L, 1L, true).passed()
                        && !g6FinalCensus(
                        0L, 100L, true, 24_000_001L, 1L, true).passed()
                        && !g6FinalCensus(
                        0L, 100L, true, 24_000_000L, 0L, true).passed()
                        && !g6FinalCensus(
                        0L, 100L, true, 24_000_000L, 1L, false).passed(),
                "pending/misalgebra/accounting/sample/SLA failures must remain fail-closed");
    }

    private static MetalFxBenchmarkController.G6MatrixFinalCensus g6FinalCensus(
            final long queuePending,
            final long queueQueued,
            final boolean queueAlgebra,
            final long accountedCurrent,
            final long fullResetSamples,
            final boolean fullResetSla
    ) {
        return new MetalFxBenchmarkController.G6MatrixFinalCensus(
                queuePending, 0L, queueQueued, 90L, 10L, queueAlgebra,
                accountedCurrent, 24_000_000L, 24_000_000L,
                1L, 1L, 2L, true,
                2L, 2L, 3L, true,
                3L, 8L, 12L, true,
                fullResetSamples, 20L, 30L, fullResetSla
        );
    }

    private static void requiresSufficientG6MatrixRecoveryWindows() {
        require(g6RecoveryWindows(
                        490, 760, 1030, 1230, 1430, 1630, 1830,
                        2170, 2430, 2620, 3090),
                "the canonical G6 matrix must satisfy every recovery window floor");
        require(!g6RecoveryWindows(
                        490, 759, 1030, 1230, 1430, 1630, 1830,
                        2170, 2430, 2620, 3090)
                        && !g6RecoveryWindows(
                        490, 760, 1029, 1230, 1430, 1630, 1830,
                        2170, 2430, 2620, 3090),
                "both reload recovery windows must preserve the 270-frame floor");
        require(!g6RecoveryWindows(
                        490, 760, 1030, 1229, 1430, 1630, 1830,
                        2170, 2430, 2620, 3090)
                        && !g6RecoveryWindows(
                        490, 760, 1030, 1230, 1429, 1630, 1830,
                        2170, 2430, 2620, 3090)
                        && !g6RecoveryWindows(
                        490, 760, 1030, 1230, 1430, 1629, 1830,
                        2170, 2430, 2620, 3090)
                        && !g6RecoveryWindows(
                        490, 760, 1030, 1230, 1430, 1630, 1829,
                        2170, 2430, 2620, 3090),
                "static and clear-to-stream recovery windows must preserve 200 frames");
        require(!g6RecoveryWindows(
                        490, 760, 1030, 1230, 1430, 1630, 1830,
                        2170, 2429, 2620, 3090)
                        && !g6RecoveryWindows(
                        490, 760, 1030, 1230, 1430, 1630, 1830,
                        2170, 2430, 2619, 3090)
                        && !g6RecoveryWindows(
                        490, 760, 1030, 1230, 1430, 1630, 1830,
                        2170, 2430, 2620, 3089)
                        && !g6RecoveryWindows(
                        490, 760, 1030, 1230, 1430, 1630, 1830,
                        2170, 2430, 2620, 3131),
                "teleport/reset handoff must preserve 260/190 frames and dimension windows 470");
    }

    private static void requiresSufficientG6MatrixScrollWindows() {
        require(MetalFxBenchmarkController.g6MatrixScrollWindowsSufficient(
                        1830, 40, 8, 2170),
                "canonical rapid scroll must preserve near-step and final convergence windows");
        require(!MetalFxBenchmarkController.g6MatrixScrollWindowsSufficient(
                        1830, 39, 8, 2170)
                        && !MetalFxBenchmarkController.g6MatrixScrollWindowsSufficient(
                        1830, 61, 8, 2340)
                        && !MetalFxBenchmarkController.g6MatrixScrollWindowsSufficient(
                        1830, 40, 8, 2169)
                        && !MetalFxBenchmarkController.g6MatrixScrollWindowsSufficient(
                        1830, 40, 7, 2170),
                "rapid scroll accepted an unsafe cadence, tail, or topology");
    }

    private static void keepsTeleportAllCascadeStabilizationSeparateFromTheNearSla() {
        int teleport = MetalFxBenchmarkController
                .g6MatrixAllCascadeStabilizationTimeoutFrames(true, false);
        int dimension = MetalFxBenchmarkController
                .g6MatrixAllCascadeStabilizationTimeoutFrames(false, true);
        int regular = MetalFxBenchmarkController
                .g6MatrixAllCascadeStabilizationTimeoutFrames(false, false);
        require(teleport == 260 && dimension == 470 && regular == 180,
                "reset actions lost their harness-only all-cascade stabilization budgets");
    }

    private static void acceptsNearOnlyCoverageForRapidScrollAndTeleportOutHandoff() {
        require(MetalFxBenchmarkController.g6MatrixRecoveryCoverageReady(1, true, true)
                        && MetalFxBenchmarkController.g6MatrixRecoveryCoverageReady(3, true, true)
                        && !MetalFxBenchmarkController.g6MatrixRecoveryCoverageReady(2, false, true),
                "rapid scroll and TELEPORT_OUT handoff did not require exact near coverage");
        require(MetalFxBenchmarkController.g6MatrixRecoveryCoverageReady(7, false, false)
                        && !MetalFxBenchmarkController.g6MatrixRecoveryCoverageReady(7, true, false)
                        && !MetalFxBenchmarkController.g6MatrixRecoveryCoverageReady(1, false, false),
                "TELEPORT_RETURN or dimension recovery accepted a partial or in-flight field");
    }

    private static boolean g6RecoveryWindows(
            final long chunkReloadFrame,
            final long resourceReloadFrame,
            final long dayFrame,
            final long nightFrame,
            final long rainFrame,
            final long clearFrame,
            final long streamStartFrame,
            final long teleportFrame,
            final long teleportReturnFrame,
            final long netherEnterFrame,
            final long netherReturnFrame
    ) {
        return MetalFxBenchmarkController.g6MatrixRecoveryWindowsSufficient(
                chunkReloadFrame, resourceReloadFrame, dayFrame, nightFrame,
                rainFrame, clearFrame, streamStartFrame, teleportFrame,
                teleportReturnFrame, netherEnterFrame, netherReturnFrame, 3600
        );
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
