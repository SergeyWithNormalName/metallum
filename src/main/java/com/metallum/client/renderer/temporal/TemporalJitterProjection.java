package com.metallum.client.renderer.temporal;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

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

    /**
     * Applies jitter to the canonical camera projection before vanilla's
     * view-bob and screen-effect transforms are appended.
     *
     * <p>{@link #apply(Matrix4f, FrameState.JitterOffset, int, int)} changes
     * the projection's z column, which is a constant pixel offset only while
     * the input is a perspective projection. Applying it after a {@code P * B}
     * composition lets the bob matrix {@code B} make the offset depth-dependent.
     * Rebuilding {@code P_jittered * B} preserves a constant MetalFX jitter for
     * every scene depth.</p>
     *
     * @param destination receives the jittered final projection
     * @param baseProjection the unmodified camera perspective projection
     * @param finalProjection vanilla's final {@code baseProjection * postProjection} matrix
     * @param postProjectionScratch reusable scratch matrix; no frame allocation is needed
     */
    public static void applyBeforePostProjection(
            final Matrix4f destination,
            final Matrix4fc baseProjection,
            final Matrix4fc finalProjection,
            final Matrix4f postProjectionScratch,
            final FrameState.JitterOffset jitter,
            final int renderWidth,
            final int renderHeight
    ) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(baseProjection, "baseProjection");
        Objects.requireNonNull(finalProjection, "finalProjection");
        Objects.requireNonNull(postProjectionScratch, "postProjectionScratch");
        postProjectionScratch.set(baseProjection).invert().mul(finalProjection);
        destination.set(baseProjection);
        apply(destination, jitter, renderWidth, renderHeight);
        destination.mul(postProjectionScratch);
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
