package com.metallum.client.benchmark;

public final class BenchmarkWindowContractTests {
    private BenchmarkWindowContractTests() {
    }

    public static void main(final String[] args) {
        acceptsHiDpiLogicalWindowForExactBackingFramebuffer();
        rejectsWrongBackingFramebuffer();
        rejectsNonLiveLogicalWindow();
        acceptsRequiredOnGlassGeneratedDelta();
        rejectsInsufficientOrRegressingGeneratedCounter();
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

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
