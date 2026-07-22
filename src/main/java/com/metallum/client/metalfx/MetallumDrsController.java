package com.metallum.client.metalfx;

import com.metallum.client.metal.render.MetalDevice;

/**
 * Dynamic Resolution Scaling (DRS) Controller.
 *
 * <p>Target frame budget: 60 FPS (16.66ms / 16.67ms frame time limit).
 * Dynamically adjusts render_extent linear scale between 1.0x (100% native)
 * and 0.5x (or 0.4x) based on completed presented-command-buffer GPU duration. Uses a dual-threshold
 * hysteresis state machine with EMA smoothing and a 30-frame scale-up holdoff delay
 * to eliminate rapid resolution oscillation ("breathing" effect).</p>
 */
public final class MetallumDrsController {
    public static final float TARGET_FRAME_TIME_MS = 16.666666f;
    // Keep a small GPU headroom below the 60 FPS frame budget. The lower
    // threshold prevents the controller from immediately undoing a downscale
    // during ordinary frame-time noise.
    public static final float HIGH_THRESHOLD_MS = 15.50f;
    public static final float LOW_THRESHOLD_MS = 13.50f;
    public static final float MIN_SCALE = 0.50f;
    public static final float ABSOLUTE_MIN_SCALE = 0.40f;
    public static final float MAX_SCALE = 1.00f;
    public static final float SCALE_STEP = 0.05f;
    public static final int SCALE_UP_HOLDOFF_FRAMES = 30;
    public static final float EMA_ALPHA = 0.10f;

    private static boolean enabled = false;
    private static float currentScale = MAX_SCALE;
    private static float emaGpuTimeMs = 16.0f;
    private static int scaleUpHoldoffCounter = 0;
    private static float minScaleBound = MIN_SCALE;

    private MetallumDrsController() {
    }

    public static synchronized boolean isEnabled() {
        return enabled;
    }

    public static synchronized void setEnabled(final boolean isEnabled) {
        if (enabled != isEnabled) {
            enabled = isEnabled;
            if (!enabled) {
                reset();
            } else {
                currentScale = MAX_SCALE;
                scaleUpHoldoffCounter = 0;
                emaGpuTimeMs = 16.0f;
                applyScaleChange();
            }
        }
    }

    public static synchronized float currentScale() {
        return currentScale;
    }

    public static synchronized float emaGpuTimeMs() {
        return emaGpuTimeMs;
    }

    public static synchronized int scaleUpHoldoffCounter() {
        return scaleUpHoldoffCounter;
    }

    public static synchronized float minScaleBound() {
        return minScaleBound;
    }

    public static synchronized void setMinScaleBound(final float minBound) {
        minScaleBound = Math.clamp(minBound, ABSOLUTE_MIN_SCALE, MAX_SCALE);
        if (currentScale < minScaleBound) {
            currentScale = minScaleBound;
            applyScaleChange();
        }
    }

    public static synchronized void reset() {
        currentScale = MAX_SCALE;
        emaGpuTimeMs = 16.0f;
        scaleUpHoldoffCounter = 0;
        minScaleBound = MIN_SCALE;
    }

    public static synchronized void setScaleDirectForTest(final float scale) {
        float clamped = Math.clamp(scale, ABSOLUTE_MIN_SCALE, MAX_SCALE);
        if (Math.abs(currentScale - clamped) > 1.0e-5f) {
            currentScale = clamped;
            applyScaleChange();
        }
    }

    public static synchronized void setEmaGpuTimeDirectForTest(final float emaMs) {
        emaGpuTimeMs = emaMs;
    }

    public static synchronized boolean isLockedOut() {
        return MetalFxSpatialScaling.isBenchmarkOverrideActive()
                || MetalFxTemporalScaling.isBenchmarkOverrideActive()
                || MetalFxSpatialScaling.isFixedPresetActive()
                || MetalFxTemporalScaling.isFixedPresetActive();
    }

    public static synchronized void updateGpuFrameTime(final float gpuTimeMs) {
        if (!enabled || !Float.isFinite(gpuTimeMs) || gpuTimeMs <= 0.0f) {
            return;
        }

        if (isLockedOut()) {
            return;
        }

        if (emaGpuTimeMs <= 0.0f) {
            emaGpuTimeMs = gpuTimeMs;
        } else {
            emaGpuTimeMs = EMA_ALPHA * gpuTimeMs + (1.0f - EMA_ALPHA) * emaGpuTimeMs;
        }

        if (emaGpuTimeMs > HIGH_THRESHOLD_MS) {
            scaleUpHoldoffCounter = 0;
            if (currentScale > minScaleBound) {
                float newScale = Math.max(minScaleBound, Math.round((currentScale - SCALE_STEP) * 100.0f) / 100.0f);
                if (Math.abs(newScale - currentScale) > 1.0e-5f) {
                    currentScale = newScale;
                    applyScaleChange();
                }
            }
        } else if (emaGpuTimeMs < LOW_THRESHOLD_MS) {
            scaleUpHoldoffCounter++;
            if (scaleUpHoldoffCounter >= SCALE_UP_HOLDOFF_FRAMES) {
                scaleUpHoldoffCounter = 0;
                if (currentScale < MAX_SCALE) {
                    float newScale = Math.min(MAX_SCALE, Math.round((currentScale + SCALE_STEP) * 100.0f) / 100.0f);
                    if (Math.abs(newScale - currentScale) > 1.0e-5f) {
                        currentScale = newScale;
                        applyScaleChange();
                    }
                }
            }
        } else {
            // Stable frame budget region (13.5ms <= emaGpuTimeMs <= 15.5ms)
            scaleUpHoldoffCounter = 0;
        }
    }

    private static void applyScaleChange() {
        MetalFxSpatialScaling.requestRendererResize();
        MetalFxTemporalScaling.requestRendererResize();
    }
}
