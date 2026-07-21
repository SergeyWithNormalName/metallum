package com.metallum.client.renderer.temporal;

import org.joml.Matrix4f;

import java.util.Objects;

/** Applies MetalFX pixel-space jitter to the projection matrix used for world rendering. */
public final class TemporalJitterProjection {
    private TemporalJitterProjection() {
    }

    public static void apply(
            final Matrix4f projection,
            final FrameState.JitterOffset jitter,
            final int renderWidth,
            final int renderHeight
    ) {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(jitter, "jitter");
        if (renderWidth <= 0 || renderHeight <= 0) {
            throw new IllegalArgumentException("Render dimensions must be positive");
        }
        projection.m20(projection.m20() + clipOffsetX(jitter, renderWidth));
        projection.m21(projection.m21() + clipOffsetY(jitter, renderHeight));
    }

    static float clipOffsetX(final FrameState.JitterOffset jitter, final int renderWidth) {
        return (float) (jitter.x() * 2.0 / renderWidth);
    }

    static float clipOffsetY(final FrameState.JitterOffset jitter, final int renderHeight) {
        // Metal's clip-space Y is up while the MetalFX jitter value is expressed
        // in render-target pixels (Y down). The native unjittering shader applies
        // the inverse of this sign convention.
        return (float) (-jitter.y() * 2.0 / renderHeight);
    }
}
