package com.metallum.client.lighting;

/**
 * Produces a camera/hand anchor without making camera motion wait for temporal smoothing.
 *
 * <p>Only an actual hand-side change is blended, over at most 100 ms. Position, forward and
 * vertical camera components are sampled directly for every call, so walking, looking and vanilla
 * view bob never leave a trailing light behind the player.</p>
 */
public final class CameraHeldLightTracker {
    public static final long SIDE_SMOOTHING_NANOS = 100_000_000L;
    public static final double FORWARD_OFFSET = 0.35;
    public static final double VERTICAL_OFFSET = -0.20;
    public static final double HAND_SIDE_OFFSET = 0.22;

    private long trackedStableId;
    private long previousSampleNanos = Long.MIN_VALUE;
    private double smoothedSide;

    public CameraHeldLightAnchor update(
            final long stableId,
            final CameraPose pose,
            final double requestedSideOffset,
            final long nowNanos
    ) {
        if (stableId == 0L) {
            throw new IllegalArgumentException("stableId zero is reserved");
        }
        if (!Double.isFinite(requestedSideOffset)) {
            throw new IllegalArgumentException("requestedSideOffset must be finite");
        }
        if (this.trackedStableId != stableId || this.previousSampleNanos == Long.MIN_VALUE
                || nowNanos < this.previousSampleNanos) {
            this.trackedStableId = stableId;
            this.smoothedSide = requestedSideOffset;
        } else {
            long elapsedNanos = nowNanos - this.previousSampleNanos;
            double blend = Math.min(1.0, (double) elapsedNanos / SIDE_SMOOTHING_NANOS);
            this.smoothedSide += (requestedSideOffset - this.smoothedSide) * blend;
        }
        this.previousSampleNanos = nowNanos;
        return new CameraHeldLightAnchor(
                pose.x + pose.forwardX * FORWARD_OFFSET + pose.upX * VERTICAL_OFFSET
                        + pose.rightX * this.smoothedSide,
                pose.y + pose.forwardY * FORWARD_OFFSET + pose.upY * VERTICAL_OFFSET
                        + pose.rightY * this.smoothedSide,
                pose.z + pose.forwardZ * FORWARD_OFFSET + pose.upZ * VERTICAL_OFFSET
                        + pose.rightZ * this.smoothedSide
        );
    }

    public void reset() {
        this.trackedStableId = 0L;
        this.previousSampleNanos = Long.MIN_VALUE;
        this.smoothedSide = 0.0;
    }

    public record CameraPose(
            double x,
            double y,
            double z,
            double forwardX,
            double forwardY,
            double forwardZ,
            double upX,
            double upY,
            double upZ,
            double rightX,
            double rightY,
            double rightZ
    ) {
        public CameraPose {
            if (!finite(x) || !finite(y) || !finite(z)
                    || !finite(forwardX) || !finite(forwardY) || !finite(forwardZ)
                    || !finite(upX) || !finite(upY) || !finite(upZ)
                    || !finite(rightX) || !finite(rightY) || !finite(rightZ)) {
                throw new IllegalArgumentException("Camera-held pose contains a non-finite component");
            }
        }
    }

    public record CameraHeldLightAnchor(double x, double y, double z) {
        public CameraHeldLightAnchor {
            if (!finite(x) || !finite(y) || !finite(z)) {
                throw new IllegalArgumentException("Camera-held anchor contains a non-finite coordinate");
            }
        }
    }

    private static boolean finite(final double value) {
        return Double.isFinite(value);
    }
}
