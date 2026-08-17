package com.metallum.client.renderer.temporal;

import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.lighting.cloud.CloudShadowFrameState;

import java.util.Objects;

/** Final world-camera values captured immediately before renderer publication. */
public record FrameCapture(
        FrameState.Transforms transforms,
        FrameState.CameraPosition cameraPosition,
        double deltaSeconds,
        double nearPlane,
        double farPlane,
        long worldIdentity,
        long dimensionIdentity,
        EnvironmentDescriptor environment,
        CloudShadowFrameState cloudShadow
) {
    public FrameCapture {
        Objects.requireNonNull(transforms, "transforms");
        Objects.requireNonNull(cameraPosition, "cameraPosition");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(cloudShadow, "cloudShadow");
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

    public FrameCapture(
            final FrameState.Transforms transforms,
            final FrameState.CameraPosition cameraPosition,
            final double deltaSeconds,
            final double nearPlane,
            final double farPlane,
            final long worldIdentity,
            final long dimensionIdentity,
            final EnvironmentDescriptor environment
    ) {
        this(
                transforms,
                cameraPosition,
                deltaSeconds,
                nearPlane,
                farPlane,
                worldIdentity,
                dimensionIdentity,
                environment,
                CloudShadowFrameState.disabled()
        );
    }

    public FrameCapture(
            final FrameState.Transforms transforms,
            final FrameState.CameraPosition cameraPosition,
            final double deltaSeconds,
            final double nearPlane,
            final double farPlane,
            final long worldIdentity,
            final long dimensionIdentity
    ) {
        this(
                transforms,
                cameraPosition,
                deltaSeconds,
                nearPlane,
                farPlane,
                worldIdentity,
                dimensionIdentity,
                EnvironmentDescriptor.NONE,
                CloudShadowFrameState.disabled()
        );
    }
}
