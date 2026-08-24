package com.metallum.client.benchmark;

import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.renderer.RendererConfig;

/** Ensures benchmark timing cannot silently substitute a fallback lighting workload. */
public final class BenchmarkLightingAdmissionTests {
    public static void main(final String[] args) {
        AdvancedLightingRuntime.Admission admission = new AdvancedLightingRuntime.Admission(true, 9L, "");
        AdvancedLightingRuntime.FrameHealth healthy = new AdvancedLightingRuntime.FrameHealth(
                true, true, true, true, 17L, 42L
        );
        AdvancedLightingRuntime.BenchmarkStatus advancedHealthy = new AdvancedLightingRuntime.BenchmarkStatus(
                true, true, admission, healthy, ""
        );
        RendererConfig.LoadStatus current = new RendererConfig.LoadStatus(
                "5", false, RendererConfig.LoadDisposition.CURRENT
        );
        require(BenchmarkLightingAdmission.evaluate(
                BenchmarkLightingAdmission.RequiredModel.ADVANCED, current, advancedHealthy
        ).valid(), "current Advanced config plus L3/L5/L6 health must admit a benchmark");

        RendererConfig.LoadStatus fallback = new RendererConfig.LoadStatus(
                "unknown", true, RendererConfig.LoadDisposition.FALLBACK_UNKNOWN_SCHEMA
        );
        require(!BenchmarkLightingAdmission.evaluate(
                BenchmarkLightingAdmission.RequiredModel.ADVANCED, fallback, advancedHealthy
        ).valid(), "fail-closed config fallback must invalidate benchmark evidence");

        AdvancedLightingRuntime.BenchmarkStatus missingL5 = new AdvancedLightingRuntime.BenchmarkStatus(
                true,
                true,
                admission,
                new AdvancedLightingRuntime.FrameHealth(true, true, false, true, 17L, 42L),
                ""
        );
        require(!BenchmarkLightingAdmission.evaluate(
                BenchmarkLightingAdmission.RequiredModel.ADVANCED, current, missingL5
        ).valid(), "missing L5 must not be benchmarked as Advanced");

        AdvancedLightingRuntime.BenchmarkStatus runtimeFallback = new AdvancedLightingRuntime.BenchmarkStatus(
                true, false, admission, healthy, "native pipeline rejected"
        );
        require(!BenchmarkLightingAdmission.evaluate(
                BenchmarkLightingAdmission.RequiredModel.ADVANCED, current, runtimeFallback
        ).valid(), "resolved Vanilla fallback must invalidate Advanced benchmark evidence");
        System.out.println("BenchmarkLightingAdmissionTests passed successfully.");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
