package com.metallum.client.metal.render;

import com.metallum.client.lighting.reflection.WaterReflectionConfig;
import com.metallum.client.lighting.reflection.WaterReflectionMode;

/**
 * Deterministic unit tests for ScreenSpaceReflectionRenderer lifecycle,
 * capture guards, and benchmark activation flags.
 */
public final class ScreenSpaceReflectionRendererTests {
    private ScreenSpaceReflectionRendererTests() {
    }

    public static void main(final String[] args) {
        testShouldCaptureWithModes();
        testBenchmarkCaptureOnlyOverride();
        testNullSafetyAndGuards();
        testFrameLifecycle();
        System.out.println("ScreenSpaceReflectionRendererTests passed successfully.");
    }

    private static void testShouldCaptureWithModes() {
        String override = System.getProperty(WaterReflectionConfig.PROPERTY_MODE);
        String captureOnly = System.getProperty(ScreenSpaceReflectionRenderer.PROPERTY_CAPTURE_ONLY);
        try {
            System.clearProperty(ScreenSpaceReflectionRenderer.PROPERTY_CAPTURE_ONLY);

            System.setProperty(WaterReflectionConfig.PROPERTY_MODE, "off");
            require(!ScreenSpaceReflectionRenderer.shouldCapture(),
                    "OFF mode must not request scene capture");

            System.setProperty(WaterReflectionConfig.PROPERTY_MODE, "voxels");
            require(!ScreenSpaceReflectionRenderer.shouldCapture(),
                    "VOXELS mode must not request SSR scene capture");

            System.setProperty(WaterReflectionConfig.PROPERTY_MODE, "screen_space");
            require(ScreenSpaceReflectionRenderer.shouldCapture(),
                    "SCREEN_SPACE mode must request scene capture");
        } finally {
            restoreProperty(WaterReflectionConfig.PROPERTY_MODE, override);
            restoreProperty(ScreenSpaceReflectionRenderer.PROPERTY_CAPTURE_ONLY, captureOnly);
            WaterReflectionConfig.resetForTesting();
        }
    }

    private static void testBenchmarkCaptureOnlyOverride() {
        String override = System.getProperty(WaterReflectionConfig.PROPERTY_MODE);
        String captureOnly = System.getProperty(ScreenSpaceReflectionRenderer.PROPERTY_CAPTURE_ONLY);
        try {
            System.setProperty(WaterReflectionConfig.PROPERTY_MODE, "off");
            System.setProperty(ScreenSpaceReflectionRenderer.PROPERTY_CAPTURE_ONLY, "true");
            require(ScreenSpaceReflectionRenderer.shouldCapture(),
                    "metallum.ssr.capture_only=true must force capture even when mode is OFF");

            System.setProperty(ScreenSpaceReflectionRenderer.PROPERTY_CAPTURE_ONLY, "false");
            require(!ScreenSpaceReflectionRenderer.shouldCapture(),
                    "metallum.ssr.capture_only=false with mode=OFF must not capture");
        } finally {
            restoreProperty(WaterReflectionConfig.PROPERTY_MODE, override);
            restoreProperty(ScreenSpaceReflectionRenderer.PROPERTY_CAPTURE_ONLY, captureOnly);
            WaterReflectionConfig.resetForTesting();
        }
    }

    private static void testNullSafetyAndGuards() {
        ScreenSpaceReflectionRenderer.beginFrame();
        require(!ScreenSpaceReflectionRenderer.isCaptureActive(),
                "capture must not be active before any frame passes execute");

        // Null target must fail closed without throwing
        ScreenSpaceReflectionRenderer.captureOpaqueScene(null);
        require(!ScreenSpaceReflectionRenderer.isCaptureActive(),
                "capturing null target must fail closed");

        require(ScreenSpaceReflectionRenderer.capturedColorTexture() == null,
                "color texture must be null when capture has not occurred");
        require(ScreenSpaceReflectionRenderer.capturedDepthTexture() == null,
                "depth texture must be null when capture has not occurred");
        require(ScreenSpaceReflectionRenderer.capturedColorView() == null,
                "color view must be null when capture has not occurred");
        require(ScreenSpaceReflectionRenderer.capturedDepthView() == null,
                "depth view must be null when capture has not occurred");
    }

    private static void testFrameLifecycle() {
        ScreenSpaceReflectionRenderer.beginFrame();
        require(!ScreenSpaceReflectionRenderer.isCaptureActive(), "beginFrame must clear active state");
        ScreenSpaceReflectionRenderer.destroy();
        require(!ScreenSpaceReflectionRenderer.isCaptureActive(), "destroy must clear active state");
        require(ScreenSpaceReflectionRenderer.target() == null, "target must be null after destroy");
    }

    private static void restoreProperty(final String key, final String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
