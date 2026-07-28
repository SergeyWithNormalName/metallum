package com.metallum.client.metalfx;

/**
 * Converts a monotonic cumulative on-screen frame counter into a stable rate.
 * The cumulative value remains the source of truth; long sampling gaps and a
 * counter reset start a new window instead of reporting a synthetic spike.
 */
public final class GeneratedFrameRateTracker {
    static final long SAMPLE_INTERVAL_NANOS = 1_000_000_000L;
    static final long MAXIMUM_CONTIGUOUS_GAP_NANOS = 2_500_000_000L;

    private boolean initialized;
    private long lastSampleNanos;
    private long lastPresentedCount;
    private long presentedCount;
    private int framesPerSecond;

    public boolean shouldSample(final long nowNanos) {
        return !this.initialized
                || nowNanos < this.lastSampleNanos
                || nowNanos - this.lastSampleNanos >= SAMPLE_INTERVAL_NANOS;
    }

    public void observe(final long nowNanos, final long cumulativePresentedCount) {
        if (nowNanos < 0L || cumulativePresentedCount < 0L) {
            throw new IllegalArgumentException("Generated-frame telemetry must be non-negative");
        }

        this.presentedCount = cumulativePresentedCount;
        if (!this.initialized) {
            this.initialize(nowNanos, cumulativePresentedCount);
            return;
        }

        long elapsed = nowNanos - this.lastSampleNanos;
        long generated = cumulativePresentedCount - this.lastPresentedCount;
        if (elapsed <= 0L
                || elapsed > MAXIMUM_CONTIGUOUS_GAP_NANOS
                || generated < 0L) {
            this.framesPerSecond = 0;
        } else {
            double measuredRate = generated * 1_000_000_000.0 / elapsed;
            this.framesPerSecond = measuredRate >= Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : (int) Math.round(measuredRate);
        }
        this.lastSampleNanos = nowNanos;
        this.lastPresentedCount = cumulativePresentedCount;
    }

    public int framesPerSecond() {
        return this.framesPerSecond;
    }

    public long presentedCount() {
        return this.presentedCount;
    }

    private void initialize(final long nowNanos, final long cumulativePresentedCount) {
        this.initialized = true;
        this.lastSampleNanos = nowNanos;
        this.lastPresentedCount = cumulativePresentedCount;
        this.framesPerSecond = 0;
    }
}
