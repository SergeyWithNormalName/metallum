package com.metallum.client.metalfx;

import com.metallum.client.metal.render.MetalDevice;

/**
 * Dynamic Resolution Scaling (DRS) Controller.
 *
 * <p>Target frame budget: 60 FPS (16.66ms / 16.67ms frame time limit).
 * Dynamically adjusts render_extent linear scale between 1.0x (100% native)
 * and 0.5x (or 0.4x) based on completed presented-command-buffer GPU duration. Uses a dual-threshold
 * hysteresis state machine with EMA smoothing. A downscale settles for a
 * bounded number of completed frames before another resize can be requested;
 * this is essential for Temporal MetalFX, whose input resources are sized per
 * extent.</p>
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
    /**
     * The three-frame renderer ring gets several fresh samples, but downsizing
     * remains deliberately faster than the 30-frame quality recovery.
     */
    public static final int SCALE_DOWN_SETTLE_FRAMES = 12;
    public static final float EMA_ALPHA = 0.10f;

    private static boolean enabled = false;
    private static float currentScale = MAX_SCALE;
    /** Zero means no completed GPU sample has arrived for this DRS session yet. */
    private static float emaGpuTimeMs = 0.0f;
    private static int scaleUpHoldoffCounter = 0;
    private static int scaleDownSettleCounter = 0;
    private static float minScaleBound = MIN_SCALE;
    /** Dynamic Temporal narrows this without changing the normal Spatial DRS policy. */
    private static float maxScaleBound = MAX_SCALE;

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
                scaleDownSettleCounter = 0;
                emaGpuTimeMs = 0.0f;
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

    public static synchronized int scaleDownSettleCounter() {
        return scaleDownSettleCounter;
    }

    public static synchronized float minScaleBound() {
        return minScaleBound;
    }

    public static synchronized float maxScaleBound() {
        return maxScaleBound;
    }

    public static synchronized void setMinScaleBound(final float minBound) {
        setScaleBounds(minBound, maxScaleBound);
    }

    /**
     * Changes the active DRS interval without forcing native 100% down to a
     * Spatial maximum. A later overloaded sample makes that first transition.
     */
    public static synchronized void setScaleBounds(final float minBound, final float maxBound) {
        minScaleBound = Math.clamp(minBound, ABSOLUTE_MIN_SCALE, MAX_SCALE);
        maxScaleBound = Math.clamp(maxBound, minScaleBound, MAX_SCALE);
        if (currentScale < minScaleBound) {
            currentScale = minScaleBound;
            applyScaleChange();
        }
    }

    public static synchronized void reset() {
        currentScale = MAX_SCALE;
        emaGpuTimeMs = 0.0f;
        scaleUpHoldoffCounter = 0;
        scaleDownSettleCounter = 0;
        minScaleBound = MIN_SCALE;
        maxScaleBound = MAX_SCALE;
    }

    public static synchronized void setScaleDirectForTest(final float scale) {
        float clamped = Math.clamp(scale, ABSOLUTE_MIN_SCALE, MAX_SCALE);
        if (Math.abs(currentScale - clamped) > 1.0e-5f) {
            currentScale = clamped;
            scaleDownSettleCounter = 0;
            applyScaleChange();
        }
    }

    /** Atomically enters a Dynamic Temporal range; callers have already selected its scaler path. */
    static synchronized void setScaleForDynamicMode(final float scale) {
        float clamped = Math.clamp(scale, minScaleBound, maxScaleBound);
        if (Math.abs(currentScale - clamped) > 1.0e-5f) {
            currentScale = clamped;
            emaGpuTimeMs = 0.0f;
            scaleUpHoldoffCounter = 0;
            scaleDownSettleCounter = 0;
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

        if (scaleDownSettleCounter > 0) {
            scaleDownSettleCounter--;
            scaleUpHoldoffCounter = 0;
            return;
        }

        if (emaGpuTimeMs > HIGH_THRESHOLD_MS) {
            scaleUpHoldoffCounter = 0;
            if (currentScale > minScaleBound) {
                // Pixel-bound work approximately follows the square of the
                // linear extent. Jump directly toward the measured budget
                // instead of resizing once for every stale completed sample.
                float requestedScale = currentScale * (float) Math.sqrt(
                        HIGH_THRESHOLD_MS / emaGpuTimeMs
                );
                float newScale = roundedDownScale(requestedScale);
                if (Math.abs(newScale - currentScale) > 1.0e-5f) {
                    currentScale = newScale;
                    scaleDownSettleCounter = SCALE_DOWN_SETTLE_FRAMES;
                    applyScaleChange();
                }
            }
        } else if (emaGpuTimeMs < LOW_THRESHOLD_MS) {
            scaleUpHoldoffCounter++;
            if (scaleUpHoldoffCounter >= SCALE_UP_HOLDOFF_FRAMES) {
                scaleUpHoldoffCounter = 0;
                if (currentScale < maxScaleBound) {
                    float newScale = Math.min(maxScaleBound, Math.round((currentScale + SCALE_STEP) * 100.0f) / 100.0f);
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

    private static float roundedDownScale(final float requestedScale) {
        float bounded = Math.clamp(requestedScale, minScaleBound, maxScaleBound);
        float stepped = (float) Math.floor((bounded + 1.0e-5f) / SCALE_STEP) * SCALE_STEP;
        return Math.max(minScaleBound, Math.round(stepped * 100.0f) / 100.0f);
    }
}
