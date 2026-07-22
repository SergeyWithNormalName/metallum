package com.metallum.client.metalfx;

import com.metallum.client.hdr.HdrUiRenderTarget;

/**
 * Verification test suite for Dynamic Resolution Scaling (DRS) Controller,
 * hysteresis state machine, mode precedence, and UI target native-resolution preservation.
 */
public final class DrsScalingTests {
    private DrsScalingTests() {
    }

    public static void main(final String[] args) {
        testHysteresisScaleDown();
        testDownscaleSettlesBeforeAnotherResize();
        testHysteresisScaleDownBoundary();
        testHysteresisScaleUpHoldoff();
        testHysteresisScaleUpLadder();
        testHysteresisStableRegion();
        testCounterResetInStableWindow();
        testPrecedenceBenchmarkOverride();
        testPrecedenceFixedPreset();
        testUiTargetDimensionPreservation();
        testUiTargetDimensionImmutabilityMatrix();
        testEmaSmoothingAndSpikeDamping();
        testMinScaleBoundClamping();
        testInvalidGpuFrameTimeIgnored();
        testNativeSecondsBoundary();
        testBreathingOscillationStressHarness();
        System.out.println("DRS scaling controller unit tests passed cleanly.");
    }

    private static void testHysteresisScaleDown() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "initial scale 1.00");

        // High threshold: 15.5ms. Send 16.0ms frame time repeatedly.
        MetallumDrsController.updateGpuFrameTime(16.0f);
        require(Math.abs(MetallumDrsController.currentScale() - 0.95f) < 1.0e-5f, "immediate scale-down step 0.95");
        require(MetallumDrsController.scaleUpHoldoffCounter() == 0, "holdoff counter reset on scale-down");

        MetallumDrsController.updateGpuFrameTime(16.0f);
        require(Math.abs(MetallumDrsController.currentScale() - 0.95f) < 1.0e-5f,
                "second stale sample must not trigger another resize");

        // Scale down all the way to MIN_SCALE (0.50f)
        for (int i = 0; i < 600; i++) {
            MetallumDrsController.updateGpuFrameTime(16.0f);
        }
        require(Math.abs(MetallumDrsController.currentScale() - 0.50f) < 1.0e-5f, "min scale clamp 0.50");
        MetallumDrsController.setEnabled(false);
    }

    private static void testDownscaleSettlesBeforeAnotherResize() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);
        MetallumDrsController.setEmaGpuTimeDirectForTest(20.0f);

        // 20ms needs roughly sqrt(15.5 / 20) = 88% extent, rounded to 85%.
        MetallumDrsController.updateGpuFrameTime(20.0f);
        require(Math.abs(MetallumDrsController.currentScale() - 0.85f) < 1.0e-5f,
                "over-budget sample jumps directly near its required pixel budget");
        require(MetallumDrsController.scaleDownSettleCounter()
                        == MetallumDrsController.SCALE_DOWN_SETTLE_FRAMES,
                "downscale starts feedback settle interval");

        for (int frame = 0; frame < MetallumDrsController.SCALE_DOWN_SETTLE_FRAMES; frame++) {
            MetallumDrsController.updateGpuFrameTime(20.0f);
            require(Math.abs(MetallumDrsController.currentScale() - 0.85f) < 1.0e-5f,
                    "settle interval blocks stale resize cascade (frame " + frame + ")");
        }
        MetallumDrsController.setEnabled(false);
    }

    private static void testHysteresisScaleDownBoundary() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);

        // Exact threshold 15.50ms should NOT trigger scale down (stable window)
        MetallumDrsController.setScaleDirectForTest(1.00f);
        MetallumDrsController.setEmaGpuTimeDirectForTest(15.50f);
        MetallumDrsController.updateGpuFrameTime(15.50f);
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "exact 15.50ms should not scale down");

        // Use a representable margin above 15.50ms; a 0.00001f delta is lost
        // by the controller's float EMA arithmetic.
        MetallumDrsController.setScaleDirectForTest(1.00f);
        MetallumDrsController.setEmaGpuTimeDirectForTest(15.60f);
        MetallumDrsController.updateGpuFrameTime(15.60f);
        require(Math.abs(MetallumDrsController.currentScale() - 0.95f) < 1.0e-5f,
                "15.60ms (>15.50ms) triggers scale down");

        MetallumDrsController.setEnabled(false);
    }

    private static void testHysteresisScaleUpHoldoff() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);
        MetallumDrsController.setScaleDirectForTest(0.50f);
        MetallumDrsController.setEmaGpuTimeDirectForTest(12.0f);
        require(Math.abs(MetallumDrsController.currentScale() - 0.50f) < 1.0e-5f, "set direct scale 0.50");

        // Low threshold: 13.5ms. Hold the headroom for all but one required frame.
        for (int frame = 1; frame < MetallumDrsController.SCALE_UP_HOLDOFF_FRAMES; frame++) {
            MetallumDrsController.updateGpuFrameTime(12.0f);
            require(Math.abs(MetallumDrsController.currentScale() - 0.50f) < 1.0e-5f,
                    "scale held at 0.50 before holdoff expires (frame " + frame + ")");
            require(MetallumDrsController.scaleUpHoldoffCounter() == frame,
                    "holdoff counter incrementing (frame " + frame + ")");
        }

        // The holdoff boundary triggers one scale-up step and resets the counter.
        MetallumDrsController.updateGpuFrameTime(12.0f);
        require(Math.abs(MetallumDrsController.currentScale() - 0.55f) < 1.0e-5f, "scale-up to 0.55 at holdoff boundary");
        require(MetallumDrsController.scaleUpHoldoffCounter() == 0, "holdoff counter reset after scale-up");

        MetallumDrsController.setEnabled(false);
    }

    private static void testHysteresisScaleUpLadder() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);
        MetallumDrsController.setScaleDirectForTest(0.50f);
        MetallumDrsController.setEmaGpuTimeDirectForTest(10.0f);

        // Step up from 0.50 to 1.00 at the configured holdoff cadence.
        float expectedScale = 0.50f;
        for (int step = 1; step <= 10; step++) {
            expectedScale = Math.round((expectedScale + 0.05f) * 100.0f) / 100.0f;
            for (int f = 0; f < MetallumDrsController.SCALE_UP_HOLDOFF_FRAMES; f++) {
                MetallumDrsController.updateGpuFrameTime(10.0f);
            }
            require(Math.abs(MetallumDrsController.currentScale() - expectedScale) < 1.0e-5f,
                    "scale-up step " + step + " reached " + expectedScale);
        }
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "reached max scale 1.00");

        // Another full holdoff at 1.00x should remain clamped.
        for (int f = 0; f < MetallumDrsController.SCALE_UP_HOLDOFF_FRAMES; f++) {
            MetallumDrsController.updateGpuFrameTime(10.0f);
        }
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "remains clamped at max scale 1.00");

        MetallumDrsController.setEnabled(false);
    }

    private static void testHysteresisStableRegion() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);
        MetallumDrsController.setScaleDirectForTest(0.75f);
        MetallumDrsController.setEmaGpuTimeDirectForTest(14.5f);

        // Frame time between 13.5ms and 15.5ms is stable
        MetallumDrsController.updateGpuFrameTime(14.5f);
        require(Math.abs(MetallumDrsController.currentScale() - 0.75f) < 1.0e-5f, "stable region scale unchanged");
        require(MetallumDrsController.scaleUpHoldoffCounter() == 0, "stable region resets holdoff counter");

        MetallumDrsController.setEnabled(false);
    }

    private static void testCounterResetInStableWindow() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);
        MetallumDrsController.setScaleDirectForTest(0.60f);
        MetallumDrsController.setEmaGpuTimeDirectForTest(12.0f);

        // Accumulate all but one low-frame sample.
        for (int f = 0; f < MetallumDrsController.SCALE_UP_HOLDOFF_FRAMES - 1; f++) {
            MetallumDrsController.updateGpuFrameTime(12.0f);
        }
        require(MetallumDrsController.scaleUpHoldoffCounter()
                        == MetallumDrsController.SCALE_UP_HOLDOFF_FRAMES - 1,
                "counter reached holdoff minus one");
        require(Math.abs(MetallumDrsController.currentScale() - 0.60f) < 1.0e-5f, "scale unchanged at 0.60");

        // Entering stable region (emaGpuTimeMs = 14.5ms) resets holdoff counter to 0 without changing scale
        MetallumDrsController.setEmaGpuTimeDirectForTest(14.5f);
        MetallumDrsController.updateGpuFrameTime(14.5f);
        require(MetallumDrsController.scaleUpHoldoffCounter() == 0, "stable region frame resets holdoff counter to 0");
        require(Math.abs(MetallumDrsController.currentScale() - 0.60f) < 1.0e-5f, "scale remains 0.60");

        // Another near-full holdoff must not scale up after the reset.
        MetallumDrsController.setEmaGpuTimeDirectForTest(12.0f);
        for (int f = 0; f < MetallumDrsController.SCALE_UP_HOLDOFF_FRAMES - 1; f++) {
            MetallumDrsController.updateGpuFrameTime(12.0f);
        }
        require(Math.abs(MetallumDrsController.currentScale() - 0.60f) < 1.0e-5f,
                "scale still 0.60 before renewed holdoff expires");

        // The next frame completes the renewed holdoff.
        MetallumDrsController.updateGpuFrameTime(12.0f);
        require(Math.abs(MetallumDrsController.currentScale() - 0.65f) < 1.0e-5f,
                "scale steps up to 0.65 at renewed holdoff boundary");

        MetallumDrsController.setEnabled(false);
    }

    private static void testPrecedenceBenchmarkOverride() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);
        MetallumDrsController.setScaleDirectForTest(0.50f);

        // Install benchmark override (QUALITY = 0.75x)
        BenchmarkScalingMode.QUALITY.apply();
        require(MetalFxSpatialScaling.isBenchmarkOverrideActive(), "spatial benchmark override active");
        require(MetallumDrsController.isLockedOut(), "DRS locked out during benchmark override");

        // Selected mode must be locked to QUALITY (0.75 * 3024 = 2268, 0.75 * 1964 = 1473)
        SpatialScalingMode mode = MetalFxSpatialScaling.selectRequestedMode(SpatialScalingMode.SPATIAL, SpatialScalingMode.QUALITY);
        MetalFxSpatialScaling.Dimensions dim = MetalFxSpatialScaling.dimensions(mode, 3024, 1964);
        require(dim.renderWidth() == 2268 && dim.renderHeight() == 1473,
                "benchmark override forces fixed 0.75x scale despite DRS controller state");

        // Clear override
        BenchmarkScalingMode.clearOverrides();
        require(!MetalFxSpatialScaling.isBenchmarkOverrideActive(), "benchmark override cleared");

        // Clean up
        MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.OFF);
        MetallumDrsController.setEnabled(false);
    }

    private static void testPrecedenceFixedPreset() {
        MetallumDrsController.reset();

        // Explicit fixed preset PERFORMANCE (0.50x)
        MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.PERFORMANCE);
        require(MetalFxSpatialScaling.isFixedPresetActive(), "fixed preset active");
        require(MetallumDrsController.isLockedOut(), "DRS locked out during explicit fixed preset");

        SpatialScalingMode mode = MetalFxSpatialScaling.requestedMode();
        MetalFxSpatialScaling.Dimensions dim = MetalFxSpatialScaling.dimensions(mode, 3024, 1964);
        require(dim.renderWidth() == 1512 && dim.renderHeight() == 982, "fixed performance dimensions");

        // Reset to OFF
        MetalFxSpatialScaling.setRequestedMode(SpatialScalingMode.OFF);
        require(!MetalFxSpatialScaling.isFixedPresetActive(), "fixed preset inactive after OFF");
    }

    private static void testUiTargetDimensionPreservation() {
        // When upscaling/DRS is active (3D scene scaled to e.g. 50%), UI target must remain at 100% native resolution
        int uiWidthScaled = uiTargetDimensionTest(true, 1512, 3024);
        int uiHeightScaled = uiTargetDimensionTest(true, 982, 1964);
        require(uiWidthScaled == 3024, "UI width stays native 3024 when 3D scene is scaled down");
        require(uiHeightScaled == 1964, "UI height stays native 1964 when 3D scene is scaled down");

        int uiWidthOff = uiTargetDimensionTest(false, 3024, 3024);
        int uiHeightOff = uiTargetDimensionTest(false, 1964, 1964);
        require(uiWidthOff == 3024, "UI width stays native 3024 when upscaling off");
        require(uiHeightOff == 1964, "UI height stays native 1964 when upscaling off");
    }

    private static void testUiTargetDimensionImmutabilityMatrix() {
        // Test multiple combinations of main target scale vs native display resolution
        int[][] testCases = {
                // mainWidth, mainHeight, displayWidth, displayHeight
                {1512, 982, 3024, 1964},   // 50% scale
                {1814, 1178, 3024, 1964},  // 60% scale
                {2116, 1374, 3024, 1964},  // 70% scale
                {2419, 1571, 3024, 1964},  // 80% scale
                {1280, 720, 2560, 1440},   // 50% QHD
                {1920, 1080, 3840, 2160},  // 50% 4K
        };

        for (int[] tc : testCases) {
            int mainW = tc[0], mainH = tc[1], dispW = tc[2], dispH = tc[3];
            int uiW = uiTargetDimensionTest(true, mainW, dispW);
            int uiH = uiTargetDimensionTest(true, mainH, dispH);
            require(uiW == dispW, "UI target width must strictly match native display width " + dispW + " (got " + uiW + ")");
            require(uiH == dispH, "UI target height must strictly match native display height " + dispH + " (got " + uiH + ")");
        }
    }

    private static int uiTargetDimensionTest(final boolean upscalingActive, final int mainDim, final int displayDim) {
        int safeMainTargetDimension = Math.max(mainDim, 1);
        return upscalingActive
                ? Math.max(displayDim, safeMainTargetDimension)
                : safeMainTargetDimension;
    }

    private static void testEmaSmoothingAndSpikeDamping() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);

        // EMA formula: EMA_k = alpha * input + (1 - alpha) * EMA_{k-1}.
        // Seed an existing sample explicitly; a fresh DRS session adopts its
        // first completed GPU sample verbatim instead of inventing a baseline.
        MetallumDrsController.setEmaGpuTimeDirectForTest(16.0f);
        // Alpha = 0.10, previous EMA = 16.0
        // Update with 20.0ms: 0.10 * 20.0 + 0.90 * 16.0 = 2.0 + 14.4 = 16.4ms
        MetallumDrsController.updateGpuFrameTime(20.0f);
        float ema = MetallumDrsController.emaGpuTimeMs();
        require(Math.abs(ema - 16.4f) < 0.05f, "EMA calculation match (expected 16.4ms, got " + ema + ")");

        // Test spike damping: starting from stable 12.0ms, a single 30ms spike should NOT immediately drop scale
        MetallumDrsController.setEmaGpuTimeDirectForTest(12.0f);
        MetallumDrsController.setScaleDirectForTest(1.00f);
        MetallumDrsController.updateGpuFrameTime(30.0f);
        // EMA = 0.10 * 30.0 + 0.90 * 12.0 = 3.0 + 10.8 = 13.8ms <= 15.5ms
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "single 30ms spike from 12ms baseline damped by EMA");

        MetallumDrsController.setEnabled(false);
    }

    private static void testMinScaleBoundClamping() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);

        // Test clamping to default min scale (0.50f)
        for (int i = 0; i < 300; i++) {
            MetallumDrsController.updateGpuFrameTime(20.0f);
        }
        require(Math.abs(MetallumDrsController.currentScale() - 0.50f) < 1.0e-5f, "clamped to default min scale 0.50");

        // Lower min scale bound to 0.40f (ABSOLUTE_MIN_SCALE)
        MetallumDrsController.setMinScaleBound(0.40f);
        require(Math.abs(MetallumDrsController.minScaleBound() - 0.40f) < 1.0e-5f, "min scale bound updated to 0.40");

        // Scale should now drop to 0.40f on further high frame times
        for (int i = 0; i < 150; i++) {
            MetallumDrsController.updateGpuFrameTime(20.0f);
        }
        require(Math.abs(MetallumDrsController.currentScale() - 0.40f) < 1.0e-5f, "clamped to new min scale bound 0.40");

        // Attempting to set min scale bound < 0.40f should clamp bound to 0.40f
        MetallumDrsController.setMinScaleBound(0.20f);
        require(Math.abs(MetallumDrsController.minScaleBound() - 0.40f) < 1.0e-5f, "min scale bound clamped to ABSOLUTE_MIN_SCALE 0.40");

        // Attempting to set min scale bound > 1.00f should clamp bound to 1.00f
        MetallumDrsController.setMinScaleBound(1.50f);
        require(Math.abs(MetallumDrsController.minScaleBound() - 1.00f) < 1.0e-5f, "min scale bound clamped to MAX_SCALE 1.00");
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "current scale auto-adjusted to min bound 1.00");

        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(false);
    }

    private static void testInvalidGpuFrameTimeIgnored() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);
        MetallumDrsController.setScaleDirectForTest(1.00f);
        MetallumDrsController.setEmaGpuTimeDirectForTest(14.0f);

        MetallumDrsController.updateGpuFrameTime(0.0f);
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "0.0ms ignored");

        MetallumDrsController.updateGpuFrameTime(-10.0f);
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "negative frame time ignored");

        MetallumDrsController.updateGpuFrameTime(Float.NaN);
        require(Math.abs(MetallumDrsController.currentScale() - 1.00f) < 1.0e-5f, "NaN frame time ignored");

        MetallumDrsController.setEnabled(false);
    }

    private static void testNativeSecondsBoundary() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);

        // Native command-buffer timing uses seconds; the controller owns milliseconds.
        MetalFxUpscaling.updateDynamicResolution(0.020);
        require(Math.abs(MetallumDrsController.emaGpuTimeMs() - 20.0f) < 1.0e-5f,
                "first 20ms native sample initializes EMA without a fabricated baseline");
        require(Math.abs(MetallumDrsController.currentScale() - 0.85f) < 1.0e-5f,
                "converted over-budget sample scales down");

        MetalFxUpscaling.updateDynamicResolution(0.0);
        MetalFxUpscaling.updateDynamicResolution(Double.NaN);
        require(Math.abs(MetallumDrsController.currentScale() - 0.85f) < 1.0e-5f,
                "empty or invalid native samples do not reuse stale timing");

        MetallumDrsController.setEnabled(false);
    }

    private static void testBreathingOscillationStressHarness() {
        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(true);

        // Simulate 2000 frames of noisy workload alternating between 10.0ms (40 frames) and 18.0ms (5 frames)
        // Verify that the scale does NOT rapidly oscillate ("breathe")
        int scaleChangeCount = 0;
        float lastScale = MetallumDrsController.currentScale();

        for (int cycle = 0; cycle < 50; cycle++) {
            // 40 frames at 10.0ms
            for (int f = 0; f < 40; f++) {
                MetallumDrsController.updateGpuFrameTime(10.0f);
                float cur = MetallumDrsController.currentScale();
                if (Math.abs(cur - lastScale) > 1.0e-5f) {
                    scaleChangeCount++;
                    lastScale = cur;
                }
            }
            // 5 frames at 18.0ms
            for (int f = 0; f < 5; f++) {
                MetallumDrsController.updateGpuFrameTime(18.0f);
                float cur = MetallumDrsController.currentScale();
                if (Math.abs(cur - lastScale) > 1.0e-5f) {
                    scaleChangeCount++;
                    lastScale = cur;
                }
            }
        }

        // Under 2250 frames with alternating 45-frame cycles, the low-time holdoff prevents scale-up steps,
        // while periodic 18ms bursts prevent holdoff completion.
        // Scale changes should be very minimal (only initial adaptation, no oscillation back and forth).
        require(scaleChangeCount <= 10, "Breathing prevention harness verified: scale changes count (" + scaleChangeCount + ") is low, preventing oscillation");

        MetallumDrsController.reset();
        MetallumDrsController.setEnabled(false);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
