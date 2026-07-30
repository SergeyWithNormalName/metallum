package com.metallum.client.benchmark;

import com.metallum.client.renderer.interpolation.FrameInterpolationRuntimeStatus;

public final class BenchmarkWindowContractTests {
    private BenchmarkWindowContractTests() {
    }

    public static void main(final String[] args) {
        acceptsHiDpiLogicalWindowForExactBackingFramebuffer();
        rejectsWrongBackingFramebuffer();
        rejectsNonLiveLogicalWindow();
        acceptsRequiredOnGlassGeneratedDelta();
        rejectsInsufficientOrRegressingGeneratedCounter();
        derivesMonotonicFiTransportCounterDelta();
        acceptsHealthyFiTransportWithBoundedSnapshotSkew();
        rejectsMissingRealFramesAndBackpressure();
        acceptsQuantizedProMotionCadenceByAggregateMean();
        rejectsSlowOrSparseFiCadenceAndLateTails();
        validatesOnlyRuntimeGateFallbackReasons();
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

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
