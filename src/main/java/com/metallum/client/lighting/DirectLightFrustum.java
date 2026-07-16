package com.metallum.client.lighting;

import com.metallum.client.renderer.temporal.FrameCapture;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;

import java.util.Objects;

/**
 * Conservative L3 direct-light view used only to bound the per-frame GPU candidate list.
 * The persistent registry remains camera-independent for future shadow, GI and volume consumers.
 */
public final class DirectLightFrustum {
    /** Keeps a one-block halo without allowing it to displace an actually intersecting sphere. */
    public static final double GUARD_BAND_BLOCKS = 1.0;
    private static final double MINIMUM_NORMAL_LENGTH_SQUARED = 1.0e-12;
    private static final double MINIMUM_VIEW_DETERMINANT = 1.0e-9;
    private static final double ORTHONORMAL_TOLERANCE = 1.0e-3;

    enum Tier {
        INTERSECTING,
        GUARD_BAND,
        BACKGROUND
    }

    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final double nearPlane;
    private final double farPlane;
    private final double[] view;
    private final Plane[] sidePlanes;
    private final boolean valid;

    private DirectLightFrustum(
            final FrameState.CameraPosition camera,
            final Matrix4 view,
            final Matrix4 projection,
            final double nearPlane,
            final double farPlane
    ) {
        this.cameraX = camera.x();
        this.cameraY = camera.y();
        this.cameraZ = camera.z();
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
        this.view = view.elements();
        double[] projectionElements = projection.elements();
        this.sidePlanes = sidePlanes(projectionElements);
        this.valid = validViewRotation(this.view) && validPlanes(this.sidePlanes);
    }

    public static DirectLightFrustum from(final FrameCapture capture) {
        Objects.requireNonNull(capture, "capture");
        FrameState.Transforms transforms = capture.transforms();
        return new DirectLightFrustum(
                capture.cameraPosition(),
                transforms.unjitteredView(),
                transforms.unjitteredProjection(),
                capture.nearPlane(),
                capture.farPlane()
        );
    }

    double cameraX() {
        return this.cameraX;
    }

    double cameraY() {
        return this.cameraY;
    }

    double cameraZ() {
        return this.cameraZ;
    }

    Tier classify(final AdvancedLight light) {
        Objects.requireNonNull(light, "light");
        if (!this.valid) {
            // Invalid view data must never create a dark frame.
            return Tier.INTERSECTING;
        }
        double relativeX = light.x() - this.cameraX;
        double relativeY = light.y() - this.cameraY;
        double relativeZ = light.z() - this.cameraZ;
        double viewX = this.view[0] * relativeX
                + this.view[4] * relativeY
                + this.view[8] * relativeZ;
        double viewY = this.view[1] * relativeX
                + this.view[5] * relativeY
                + this.view[9] * relativeZ;
        double viewZ = this.view[2] * relativeX
                + this.view[6] * relativeY
                + this.view[10] * relativeZ;
        if (!Double.isFinite(viewX) || !Double.isFinite(viewY) || !Double.isFinite(viewZ)) {
            return Tier.INTERSECTING;
        }
        if (this.intersects(viewX, viewY, viewZ, light.radius())) {
            return Tier.INTERSECTING;
        }
        if (this.intersects(
                viewX,
                viewY,
                viewZ,
                light.radius() + GUARD_BAND_BLOCKS
        )) {
            return Tier.GUARD_BAND;
        }
        return Tier.BACKGROUND;
    }

    private boolean intersects(
            final double viewX,
            final double viewY,
            final double viewZ,
            final double radius
    ) {
        double centerDepth = -viewZ;
        if (centerDepth + radius <= this.nearPlane
                || centerDepth - radius > this.farPlane) {
            return false;
        }
        for (Plane plane : this.sidePlanes) {
            double distance = plane.x * viewX + plane.y * viewY + plane.z * viewZ + plane.w;
            if (!Double.isFinite(distance)) {
                return true;
            }
            if (distance < -radius * plane.normalLength) {
                return false;
            }
        }
        return true;
    }

    private static Plane[] sidePlanes(final double[] projection) {
        double rowX0 = projection[0];
        double rowX1 = projection[4];
        double rowX2 = projection[8];
        double rowX3 = projection[12];
        double rowY0 = projection[1];
        double rowY1 = projection[5];
        double rowY2 = projection[9];
        double rowY3 = projection[13];
        double rowW0 = projection[3];
        double rowW1 = projection[7];
        double rowW2 = projection[11];
        double rowW3 = projection[15];
        return new Plane[] {
                new Plane(rowW0 + rowX0, rowW1 + rowX1, rowW2 + rowX2, rowW3 + rowX3),
                new Plane(rowW0 - rowX0, rowW1 - rowX1, rowW2 - rowX2, rowW3 - rowX3),
                new Plane(rowW0 + rowY0, rowW1 + rowY1, rowW2 + rowY2, rowW3 + rowY3),
                new Plane(rowW0 - rowY0, rowW1 - rowY1, rowW2 - rowY2, rowW3 - rowY3)
        };
    }

    private static boolean validPlanes(final Plane[] planes) {
        for (Plane plane : planes) {
            if (!Double.isFinite(plane.normalLength)
                    || plane.normalLength * plane.normalLength
                    <= MINIMUM_NORMAL_LENGTH_SQUARED) {
                return false;
            }
        }
        return true;
    }

    private static boolean validViewRotation(final double[] matrix) {
        double determinant = matrix[0] * (matrix[5] * matrix[10] - matrix[9] * matrix[6])
                - matrix[4] * (matrix[1] * matrix[10] - matrix[9] * matrix[2])
                + matrix[8] * (matrix[1] * matrix[6] - matrix[5] * matrix[2]);
        if (!Double.isFinite(determinant)
                || Math.abs(determinant) <= MINIMUM_VIEW_DETERMINANT
                || Math.abs(Math.abs(determinant) - 1.0) > ORTHONORMAL_TOLERANCE) {
            return false;
        }
        double firstLengthSquared = squaredLength(matrix[0], matrix[1], matrix[2]);
        double secondLengthSquared = squaredLength(matrix[4], matrix[5], matrix[6]);
        double thirdLengthSquared = squaredLength(matrix[8], matrix[9], matrix[10]);
        return Math.abs(firstLengthSquared - 1.0) <= ORTHONORMAL_TOLERANCE
                && Math.abs(secondLengthSquared - 1.0) <= ORTHONORMAL_TOLERANCE
                && Math.abs(thirdLengthSquared - 1.0) <= ORTHONORMAL_TOLERANCE
                && Math.abs(dot(
                        matrix[0], matrix[1], matrix[2],
                        matrix[4], matrix[5], matrix[6]
                )) <= ORTHONORMAL_TOLERANCE
                && Math.abs(dot(
                        matrix[0], matrix[1], matrix[2],
                        matrix[8], matrix[9], matrix[10]
                )) <= ORTHONORMAL_TOLERANCE
                && Math.abs(dot(
                        matrix[4], matrix[5], matrix[6],
                        matrix[8], matrix[9], matrix[10]
                )) <= ORTHONORMAL_TOLERANCE;
    }

    private static double squaredLength(final double x, final double y, final double z) {
        return x * x + y * y + z * z;
    }

    private static double dot(
            final double leftX,
            final double leftY,
            final double leftZ,
            final double rightX,
            final double rightY,
            final double rightZ
    ) {
        return leftX * rightX + leftY * rightY + leftZ * rightZ;
    }

    private static final class Plane {
        private final double x;
        private final double y;
        private final double z;
        private final double w;
        private final double normalLength;

        private Plane(final double x, final double y, final double z, final double w) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.normalLength = Math.sqrt(x * x + y * y + z * z);
        }
    }
}
