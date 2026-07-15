package com.metallum.client.renderer.temporal;

import java.util.Objects;

/** Final world-camera values captured immediately before renderer publication. */
public record FrameCapture(
        FrameState.Transforms transforms,
        FrameState.CameraPosition cameraPosition,
        double deltaSeconds,
        double nearPlane,
        double farPlane,
        long worldIdentity,
        long dimensionIdentity
) {
    public FrameCapture {
        Objects.requireNonNull(transforms, "transforms");
        Objects.requireNonNull(cameraPosition, "cameraPosition");
        if (deltaSeconds < 0.0 || !Double.isFinite(deltaSeconds)) {
            throw new IllegalArgumentException("Frame delta must be non-negative and finite");
        }
        if (!(nearPlane > 0.0) || !Double.isFinite(nearPlane)
                || !(farPlane > nearPlane) || !Double.isFinite(farPlane)) {
            throw new IllegalArgumentException("Frame projection range is invalid");
        }
        if (worldIdentity < 0L || dimensionIdentity < 0L) {
            throw new IllegalArgumentException("World identities must be non-negative");
        }
    }
}
