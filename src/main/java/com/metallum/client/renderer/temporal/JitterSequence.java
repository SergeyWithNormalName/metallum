package com.metallum.client.renderer.temporal;

/** Deterministic Halton jitter contract shared by world projection and MetalFX. */
public final class JitterSequence {
    private static final int BASE_PHASE_COUNT = 8;
    private static final int LEGACY_DEFAULT_PHASE_COUNT = 16;

    private JitterSequence() {
    }

    public static FrameState.JitterOffset sample(final long frameId, final double amplitude) {
        return sample(frameId, amplitude, LEGACY_DEFAULT_PHASE_COUNT);
    }

    /**
     * Samples the sequence for the actual render/display ratio used by MetalFX.
     *
     * <p>MetalFX needs {@code ceil(8 * scale^2)} phases to distribute its subpixel samples:
     * 15 at the 4/3x Quality scale, 32 at 2x, and 72 at 3x. Render targets are rounded to whole
     * pixels, so calculate from the actual ratio and round upward to avoid undersampling. Keeping
     * this calculation here prevents the world projection and native scaler packet from drifting
     * apart.</p>
     */
    public static FrameState.JitterOffset sample(
            final long frameId,
            final double amplitude,
            final int renderWidth,
            final int renderHeight,
            final int displayWidth,
            final int displayHeight
    ) {
        return sample(
                frameId,
                amplitude,
                phaseCount(renderWidth, renderHeight, displayWidth, displayHeight)
        );
    }

    public static int phaseCount(
            final int renderWidth,
            final int renderHeight,
            final int displayWidth,
            final int displayHeight
    ) {
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("Render and display dimensions must be positive");
        }
        final double scale = Math.max(
                displayWidth / (double) renderWidth,
                displayHeight / (double) renderHeight
        );
        final long phases = (long) Math.ceil(BASE_PHASE_COUNT * scale * scale);
        return (int) Math.max(BASE_PHASE_COUNT, Math.min(phases, (long) Integer.MAX_VALUE));
    }

    static FrameState.JitterOffset sample(
            final long frameId,
            final double amplitude,
            final int phaseCount
    ) {
        if (frameId < 0L) {
            throw new IllegalArgumentException("Frame ID must be non-negative");
        }
        if (!Double.isFinite(amplitude) || amplitude < 0.0 || amplitude > 1.0) {
            throw new IllegalArgumentException("Jitter amplitude must be finite and in [0, 1]");
        }
        if (phaseCount <= 0) {
            throw new IllegalArgumentException("Jitter phase count must be positive");
        }
        if (amplitude == 0.0) {
            return FrameState.JitterOffset.ZERO;
        }
        int index = (int) (frameId % phaseCount) + 1;
        return new FrameState.JitterOffset(
                (halton(index, 2) - 0.5) * amplitude,
                (halton(index, 3) - 0.5) * amplitude
        );
    }

    static double halton(final int index, final int base) {
        double result = 0.0;
        double fraction = 1.0 / base;
        int value = index;
        while (value > 0) {
            result += fraction * (value % base);
            value /= base;
            fraction /= base;
        }
        return result;
    }
}
