package com.metallum.client.metalfx;

import com.metallum.client.hdr.HdrOutputMode;

import java.util.Properties;

public final class SpatialScalingTests {
    private SpatialScalingTests() {
    }

    public static void main(final String[] args) {
        testModeParsing();
        testAutoResolution();
        testAutoResizePolicy();
        testRequestedModeSelection();
        testPresetDimensions();
        testOddAndTinyDimensions();
        testDynamicSpatialNativeBypass();
    }

    private static void testModeParsing() {
        Properties properties = new Properties();
        require(MetalFxSpatialScaling.from(properties) == SpatialScalingMode.OFF, "default mode");
        properties.setProperty("mode", "quality");
        require(MetalFxSpatialScaling.from(properties) == SpatialScalingMode.QUALITY, "quality parsing");
        properties.setProperty("mode", "PERFORMANCE");
        require(MetalFxSpatialScaling.from(properties) == SpatialScalingMode.PERFORMANCE, "performance parsing");
        properties.setProperty("mode", "auto");
        require(MetalFxSpatialScaling.from(properties) == SpatialScalingMode.AUTO, "auto parsing");
        properties.setProperty("mode", "corrupt");
        require(MetalFxSpatialScaling.from(properties) == SpatialScalingMode.OFF, "corrupt fallback");
    }

    private static void testAutoResolution() {
        require(
                MetalFxSpatialScaling.resolveRequestedMode(SpatialScalingMode.AUTO, HdrOutputMode.SDR)
                        == SpatialScalingMode.OFF,
                "auto SDR policy"
        );
        require(
                MetalFxSpatialScaling.resolveRequestedMode(SpatialScalingMode.AUTO, HdrOutputMode.EDR)
                        == SpatialScalingMode.OFF,
                "auto EDR policy"
        );
        require(
                MetalFxSpatialScaling.resolveRequestedMode(SpatialScalingMode.AUTO, HdrOutputMode.ENHANCED)
                        == SpatialScalingMode.PERFORMANCE,
                "auto enhanced policy"
        );
        require(
                MetalFxSpatialScaling.resolveRequestedMode(SpatialScalingMode.AUTO, null)
                        == SpatialScalingMode.OFF,
                "auto unknown-output fallback"
        );
        for (SpatialScalingMode forced : new SpatialScalingMode[] {
                SpatialScalingMode.OFF,
                SpatialScalingMode.QUALITY,
                SpatialScalingMode.PERFORMANCE,
                SpatialScalingMode.ULTRA_PERFORMANCE
        }) {
            for (HdrOutputMode outputMode : HdrOutputMode.values()) {
                require(
                        MetalFxSpatialScaling.resolveRequestedMode(forced, outputMode) == forced,
                        "forced preset changed by auto policy"
                );
            }
        }
    }

    private static void testRequestedModeSelection() {
        require(
                MetalFxSpatialScaling.selectRequestedMode(SpatialScalingMode.AUTO, null)
                        == SpatialScalingMode.AUTO,
                "persisted policy selection"
        );
        require(
                MetalFxSpatialScaling.selectRequestedMode(
                        SpatialScalingMode.AUTO,
                        SpatialScalingMode.QUALITY
                ) == SpatialScalingMode.QUALITY,
                "benchmark override selection"
        );
        expectIllegalArgument(
                () -> MetalFxSpatialScaling.selectRequestedMode(
                        SpatialScalingMode.OFF,
                        SpatialScalingMode.AUTO
                ),
                "automatic benchmark override"
        );
    }

    private static void testAutoResizePolicy() {
        require(
                !MetalFxSpatialScaling.requiresResizeForOutputModeChange(
                        SpatialScalingMode.AUTO,
                        HdrOutputMode.SDR,
                        HdrOutputMode.EDR
                ),
                "auto native-output transition resize"
        );
        require(
                MetalFxSpatialScaling.requiresResizeForOutputModeChange(
                        SpatialScalingMode.AUTO,
                        HdrOutputMode.SDR,
                        HdrOutputMode.ENHANCED
                ),
                "auto enhanced activation resize"
        );
        require(
                MetalFxSpatialScaling.requiresResizeForOutputModeChange(
                        SpatialScalingMode.AUTO,
                        HdrOutputMode.ENHANCED,
                        HdrOutputMode.EDR
                ),
                "auto enhanced fallback resize"
        );
        for (SpatialScalingMode forced : new SpatialScalingMode[] {
                SpatialScalingMode.OFF,
                SpatialScalingMode.QUALITY,
                SpatialScalingMode.PERFORMANCE,
                SpatialScalingMode.ULTRA_PERFORMANCE
        }) {
            require(
                    !MetalFxSpatialScaling.requiresResizeForOutputModeChange(
                            forced,
                            HdrOutputMode.SDR,
                            HdrOutputMode.ENHANCED
                    ),
                    "forced preset output transition resize"
            );
        }
    }

    private static void testPresetDimensions() {
        MetalFxSpatialScaling.Dimensions off = MetalFxSpatialScaling.dimensions(
                SpatialScalingMode.OFF,
                3024,
                1964
        );
        require(off.renderWidth() == 3024 && off.renderHeight() == 1964, "off dimensions");

        MetalFxSpatialScaling.Dimensions quality = MetalFxSpatialScaling.dimensions(
                SpatialScalingMode.QUALITY,
                3024,
                1964
        );
        require(quality.renderWidth() == 2268 && quality.renderHeight() == 1473, "quality dimensions");
        require(Math.abs(quality.actualPixelScale() - 0.5625f) < 0.0001f, "quality pixel workload");

        MetalFxSpatialScaling.Dimensions performance = MetalFxSpatialScaling.dimensions(
                SpatialScalingMode.PERFORMANCE,
                3024,
                1964
        );
        require(performance.renderWidth() == 1512 && performance.renderHeight() == 982, "performance dimensions");
        require(Math.abs(performance.actualPixelScale() - 0.25f) < 0.0001f, "performance pixel workload");

        MetalFxSpatialScaling.Dimensions ultra = MetalFxSpatialScaling.dimensions(
                SpatialScalingMode.ULTRA_PERFORMANCE,
                3024,
                1964
        );
        require(ultra.renderWidth() == 1210 && ultra.renderHeight() == 786, "ultra dimensions");
        require(Math.abs(ultra.actualPixelScale() - 0.16f) < 0.0005f, "ultra pixel workload");

        expectIllegalArgument(
                () -> MetalFxSpatialScaling.dimensions(SpatialScalingMode.AUTO, 3024, 1964),
                "unresolved auto dimensions"
        );
    }

    private static void testOddAndTinyDimensions() {
        for (int[] display : new int[][] {
                {1, 1},
                {1279, 719},
                {1513, 983},
                {2560, 1440},
                {5120, 2880}
        }) {
            for (SpatialScalingMode mode : SpatialScalingMode.values()) {
                if (!mode.concrete()) {
                    continue;
                }
                MetalFxSpatialScaling.Dimensions dimensions = MetalFxSpatialScaling.dimensions(
                        mode,
                        display[0],
                        display[1]
                );
                require(dimensions.renderWidth() >= 1, "positive render width");
                require(dimensions.renderHeight() >= 1, "positive render height");
                require(dimensions.renderWidth() <= dimensions.displayWidth(), "render width clamp");
                require(dimensions.renderHeight() <= dimensions.displayHeight(), "render height clamp");
                float displayAspect = dimensions.displayWidth() / (float) dimensions.displayHeight();
                float renderAspect = dimensions.renderWidth() / (float) dimensions.renderHeight();
                if (dimensions.displayWidth() > 8 && dimensions.displayHeight() > 8) {
                    require(Math.abs(displayAspect - renderAspect) / displayAspect < 0.01f, "aspect drift");
                }
            }
        }
    }

    private static void testDynamicSpatialNativeBypass() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);
        MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.SPATIAL);
        require(MetalFxSpatialScaling.requestedMode() == SpatialScalingMode.SPATIAL, "requested dynamic mode is SPATIAL");
        require(MetalFxSpatialScaling.isRequested(), "isRequested is true for dynamic SPATIAL");
        require(MetallumDrsController.isEnabled(), "DRS controller remains enabled for dynamic SPATIAL");
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "initial scale is 1.00");

        // At scale 1.00, Native bypass is active
        require(MetalFxSpatialScaling.isDynamicNativeFallbackActive(), "dynamic native fallback active at scale 1.00");
        require(
                MetalFxSpatialScaling.selectEffectiveMode(
                        SpatialScalingMode.SPATIAL, null, HdrOutputMode.SDR, false, true, 1.00f
                ) == SpatialScalingMode.OFF,
                "effective mode is OFF at scale 1.00 on supported device"
        );
        require(
                MetalFxSpatialScaling.selectEffectiveMode(
                        SpatialScalingMode.SPATIAL, null, HdrOutputMode.ENHANCED, false, true, 1.00f
                ) == SpatialScalingMode.OFF,
                "effective mode is OFF at scale 1.00 in Enhanced HDR on supported device"
        );

        MetalFxSpatialScaling.Dimensions dim100 = MetalFxSpatialScaling.dimensions(SpatialScalingMode.SPATIAL, 3024, 1964);
        require(dim100.renderWidth() == 3024 && dim100.renderHeight() == 1964, "dimensions at 1.00 match display dimensions");

        // When scale drops below 1.00 (e.g. 0.95), Spatial scaling activates
        MetallumDrsController.setScaleDirectForTest(0.95f);
        require(!MetalFxSpatialScaling.isDynamicNativeFallbackActive(), "dynamic native fallback inactive at scale 0.95");
        require(
                MetalFxSpatialScaling.selectEffectiveMode(
                        SpatialScalingMode.SPATIAL, null, HdrOutputMode.SDR, false, true, 0.95f
                ) == SpatialScalingMode.SPATIAL,
                "effective mode is SPATIAL at scale 0.95 on supported device"
        );

        MetalFxSpatialScaling.Dimensions dim95 = MetalFxSpatialScaling.dimensions(SpatialScalingMode.SPATIAL, 3024, 1964);
        require(dim95.renderWidth() == 2873 && dim95.renderHeight() == 1866, "dimensions at 0.95 scaled down");

        // Fixed presets are never bypassed at 1.00 or any scale
        MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.QUALITY);
        require(!MetalFxSpatialScaling.isDynamicNativeFallbackActive(), "fallback inactive for fixed QUALITY preset");
        require(
                MetalFxSpatialScaling.selectEffectiveMode(
                        SpatialScalingMode.QUALITY, null, HdrOutputMode.SDR, false, true, 1.00f
                ) == SpatialScalingMode.QUALITY,
                "effective mode is QUALITY at 1.00"
        );
        MetalFxSpatialScaling.Dimensions dimQuality = MetalFxSpatialScaling.dimensions(SpatialScalingMode.QUALITY, 3024, 1964);
        require(dimQuality.renderWidth() == 2268 && dimQuality.renderHeight() == 1473, "QUALITY dimensions are 75%");

        // Benchmark overrides lock concrete presets deterministically
        MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.SPATIAL);
        MetallumDrsController.setScaleDirectForTest(1.00f);
        require(MetalFxSpatialScaling.isDynamicNativeFallbackActive(), "fallback active before override");
        MetalFxSpatialScaling.setBenchmarkOverride(SpatialScalingMode.PERFORMANCE);
        require(!MetalFxSpatialScaling.isDynamicNativeFallbackActive(), "fallback inactive during benchmark override");
        require(
                MetalFxSpatialScaling.selectEffectiveMode(
                        SpatialScalingMode.SPATIAL, SpatialScalingMode.PERFORMANCE, HdrOutputMode.SDR, false, true, 1.00f
                ) == SpatialScalingMode.PERFORMANCE,
                "effective mode is PERFORMANCE under override"
        );
        MetalFxSpatialScaling.Dimensions dimPerf = MetalFxSpatialScaling.dimensions(SpatialScalingMode.PERFORMANCE, 3024, 1964);
        require(dimPerf.renderWidth() == 1512 && dimPerf.renderHeight() == 982, "PERFORMANCE dimensions under override are 50%");
        MetalFxSpatialScaling.clearBenchmarkOverride();
        require(MetalFxSpatialScaling.isDynamicNativeFallbackActive(), "fallback active after clear override at 1.00");

        // Clean up
        MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.OFF);
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(false);
    }

    private static void expectIllegalArgument(final Runnable action, final String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
