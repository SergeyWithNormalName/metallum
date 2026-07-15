package com.metallum.client.renderer.temporal;

/** Deterministic Halton jitter contract; L1 production always requests zero amplitude. */
public final class JitterSequence {
    private static final int PERIOD = 16;

    private JitterSequence() {
    }

    public static FrameState.JitterOffset sample(final long frameId, final double amplitude) {
        if (frameId < 0L) {
            throw new IllegalArgumentException("Frame ID must be non-negative");
        }
        if (!Double.isFinite(amplitude) || amplitude < 0.0 || amplitude > 1.0) {
            throw new IllegalArgumentException("Jitter amplitude must be finite and in [0, 1]");
        }
        if (amplitude == 0.0) {
            return FrameState.JitterOffset.ZERO;
        }
        int index = (int) (frameId % PERIOD) + 1;
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
