package com.metallum.client.metalfx;

import java.util.Properties;

public final class SpatialScalingTests {
    private SpatialScalingTests() {
    }

    public static void main(final String[] args) {
        testModeParsing();
        testPresetDimensions();
        testOddAndTinyDimensions();
    }

    private static void testModeParsing() {
        Properties properties = new Properties();
        require(MetalFxSpatialScaling.from(properties) == SpatialScalingMode.OFF, "default mode");
        properties.setProperty("mode", "quality");
        require(MetalFxSpatialScaling.from(properties) == SpatialScalingMode.QUALITY, "quality parsing");
        properties.setProperty("mode", "PERFORMANCE");
        require(MetalFxSpatialScaling.from(properties) == SpatialScalingMode.PERFORMANCE, "performance parsing");
        properties.setProperty("mode", "corrupt");
        require(MetalFxSpatialScaling.from(properties) == SpatialScalingMode.OFF, "corrupt fallback");
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
        require(quality.renderWidth() == 2016 && quality.renderHeight() == 1309, "quality dimensions");
        require(Math.abs(quality.actualPixelScale() - 0.4443f) < 0.0001f, "quality pixel workload");

        MetalFxSpatialScaling.Dimensions performance = MetalFxSpatialScaling.dimensions(
                SpatialScalingMode.PERFORMANCE,
                3024,
                1964
        );
        require(performance.renderWidth() == 1512 && performance.renderHeight() == 982, "performance dimensions");
        require(Math.abs(performance.actualPixelScale() - 0.25f) < 0.0001f, "performance pixel workload");
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

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
