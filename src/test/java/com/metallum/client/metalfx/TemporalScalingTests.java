package com.metallum.client.metalfx;

import com.metallum.client.renderer.RendererFeatureMask;

import java.util.Properties;

/** Pure policy checks for the persistent Temporal preset surface. */
public final class TemporalScalingTests {
    private TemporalScalingTests() {
    }

    public static void main(final String[] args) {
        testModeParsing();
        testAlgorithmPolicyParsingAndMask();
        testPresetDimensions();
        testOddAndTinyDimensions();
        testBenchmarkOverrides();
        testEffectiveSelection();
        System.out.println("MetalFX Temporal scaling policy validation passed");
    }

    private static void testModeParsing() {
        Properties properties = new Properties();
        require(MetalFxTemporalScaling.from(properties) == TemporalScalingMode.OFF, "default mode");
        properties.setProperty("mode", "quality");
        require(MetalFxTemporalScaling.from(properties) == TemporalScalingMode.QUALITY, "quality parsing");
        properties.setProperty("mode", "PERFORMANCE");
        require(MetalFxTemporalScaling.from(properties) == TemporalScalingMode.PERFORMANCE, "performance parsing");
        properties.setProperty("mode", "ultra_performance");
        require(MetalFxTemporalScaling.from(properties) == TemporalScalingMode.ULTRA_PERFORMANCE,
                "ultra-performance parsing");
        properties.setProperty("mode", "corrupt");
        require(MetalFxTemporalScaling.from(properties) == TemporalScalingMode.OFF, "corrupt fallback");
    }

    private static void testPresetDimensions() {
        require(Math.abs(TemporalScalingMode.QUALITY.linearScale() - 0.75f) <= 1.0e-6f,
                "quality linear scale");
        MetalFxTemporalScaling.Dimensions off = MetalFxTemporalScaling.dimensions(
                TemporalScalingMode.OFF, 3024, 1964
        );
        require(off.renderWidth() == 3024 && off.renderHeight() == 1964, "off dimensions");

        MetalFxTemporalScaling.Dimensions quality = MetalFxTemporalScaling.dimensions(
                TemporalScalingMode.QUALITY, 3024, 1964
        );
        require(quality.renderWidth() == 2268 && quality.renderHeight() == 1473, "quality dimensions");
        require(Math.abs(quality.actualPixelScale() - 0.5625f) < 0.0001f, "quality pixel workload");

        MetalFxTemporalScaling.Dimensions performance = MetalFxTemporalScaling.dimensions(
                TemporalScalingMode.PERFORMANCE, 3024, 1964
        );
        require(performance.renderWidth() == 1512 && performance.renderHeight() == 982,
                "performance dimensions");
        require(Math.abs(performance.actualPixelScale() - 0.25f) < 0.0001f, "performance pixel workload");

        MetalFxTemporalScaling.Dimensions ultra = MetalFxTemporalScaling.dimensions(
                TemporalScalingMode.ULTRA_PERFORMANCE, 3024, 1964
        );
        require(ultra.renderWidth() == 1008 && ultra.renderHeight() == 655, "ultra dimensions");
        require(Math.abs(ultra.actualPixelScale() - 1.0f / 9.0f) < 0.0002f,
                "ultra pixel workload");
    }

    private static void testAlgorithmPolicyParsingAndMask() {
        Properties properties = new Properties();
        require(MetalFxTemporalScaling.algorithmFrom(properties) == TemporalAlgorithmPolicy.AUTO,
                "default Temporal algorithm policy");
        properties.setProperty("algorithm", "apple_metalfx");
        require(MetalFxTemporalScaling.algorithmFrom(properties) == TemporalAlgorithmPolicy.APPLE_METALFX,
                "Apple MetalFX policy parsing");
        properties.setProperty("algorithm", "METALLUM_OPTIMIZED");
        require(MetalFxTemporalScaling.algorithmFrom(properties)
                        == TemporalAlgorithmPolicy.METALLUM_OPTIMIZED,
                "Metallum optimized policy parsing");
        properties.setProperty("algorithm", "corrupt");
        require(MetalFxTemporalScaling.algorithmFrom(properties) == TemporalAlgorithmPolicy.AUTO,
                "corrupt Temporal algorithm policy fallback");
        require(MetalFxTemporalScaling.selectRequestedAlgorithmPolicy(
                        TemporalAlgorithmPolicy.APPLE_METALFX,
                        TemporalAlgorithmPolicy.AUTO
                ) == TemporalAlgorithmPolicy.AUTO,
                "benchmark algorithm override must win over persisted policy");

        RendererFeatureMask apple = RendererFeatureMask.of(
                RendererFeatureMask.TEMPORAL_UPSCALING,
                TemporalAlgorithmPolicy.APPLE_METALFX.featureBit()
        );
        require(apple.contains(RendererFeatureMask.TEMPORAL_UPSCALING)
                        && apple.contains(RendererFeatureMask.TEMPORAL_FORCE_APPLE_METALFX),
                "Apple policy feature-mask contract");
        expectIllegalArgument(() -> RendererFeatureMask.of(
                RendererFeatureMask.TEMPORAL_FORCE_APPLE_METALFX
        ));
        expectIllegalArgument(() -> RendererFeatureMask.of(
                RendererFeatureMask.TEMPORAL_UPSCALING,
                RendererFeatureMask.TEMPORAL_FORCE_APPLE_METALFX,
                RendererFeatureMask.TEMPORAL_FORCE_METALLUM_OPTIMIZED
        ));
    }

    private static void testOddAndTinyDimensions() {
        for (int[] display : new int[][] {{1, 1}, {1279, 719}, {1513, 983}, {2560, 1440}, {5120, 2880}}) {
            for (TemporalScalingMode mode : TemporalScalingMode.values()) {
                MetalFxTemporalScaling.Dimensions dimensions = MetalFxTemporalScaling.dimensions(
                        mode, display[0], display[1]
                );
                require(dimensions.renderWidth() >= 1 && dimensions.renderHeight() >= 1,
                        "positive render dimensions");
                require(dimensions.renderWidth() <= dimensions.displayWidth()
                                && dimensions.renderHeight() <= dimensions.displayHeight(),
                        "render dimensions clamp");
            }
        }
    }

    private static void testBenchmarkOverrides() {
        require(MetalFxTemporalScaling.selectRequestedMode(
                        TemporalScalingMode.QUALITY,
                        null
                ) == TemporalScalingMode.QUALITY,
                "persisted Temporal mode without override");
        require(MetalFxTemporalScaling.selectRequestedMode(
                        TemporalScalingMode.QUALITY,
                        TemporalScalingMode.PERFORMANCE
                ) == TemporalScalingMode.PERFORMANCE,
                "Temporal benchmark override wins");
        require(BenchmarkScalingMode.parse("temporal_performance")
                        == BenchmarkScalingMode.TEMPORAL_PERFORMANCE,
                "Temporal benchmark parsing");
        require(BenchmarkScalingMode.TEMPORAL_PERFORMANCE.spatialMode() == SpatialScalingMode.OFF,
                "Temporal benchmark disables Spatial");
        require(BenchmarkScalingMode.TEMPORAL_PERFORMANCE.temporalMode()
                        == TemporalScalingMode.PERFORMANCE,
                "Temporal benchmark selects requested preset");
    }

    private static void testEffectiveSelection() {
        require(MetalFxTemporalScaling.selectEffectiveMode(
                        TemporalScalingMode.QUALITY,
                        TemporalScalingMode.PERFORMANCE,
                        false,
                        true
                ) == TemporalScalingMode.PERFORMANCE,
                "Benchmark override must also select the effective Temporal mode");
        require(MetalFxTemporalScaling.selectEffectiveMode(
                        TemporalScalingMode.QUALITY,
                        TemporalScalingMode.PERFORMANCE,
                        true,
                        true
                ) == TemporalScalingMode.OFF,
                "Runtime disable must override a benchmark Temporal mode");
        require(MetalFxTemporalScaling.selectEffectiveMode(
                        TemporalScalingMode.QUALITY,
                        null,
                        false,
                        false
                ) == TemporalScalingMode.OFF,
                "Unsupported devices must reject the selected Temporal mode");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }
}
