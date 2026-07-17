package com.metallum.client.lighting;

import com.metallum.client.renderer.temporal.FrameState;
import org.joml.Matrix4fc;

import java.util.Objects;

/** Allocation-free conservative AABB test for one camera-relative orthographic cascade. */
public final class SunShadowClipVolume {
    private static final float CLIP_EPSILON = 0.002f;

    private SunShadowClipVolume() {
    }

    public static boolean intersectsAabb(
            final Matrix4fc clipFromWorldRelative,
            final FrameState.CameraPosition camera,
            final double minimumX,
            final double minimumY,
            final double minimumZ,
            final double maximumX,
            final double maximumY,
            final double maximumZ
    ) {
        Objects.requireNonNull(clipFromWorldRelative, "clipFromWorldRelative");
        Objects.requireNonNull(camera, "camera");
        if (!validBounds(minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ)) {
            return true;
        }
        if (Math.abs(clipFromWorldRelative.m03()) > 1.0e-6f
                || Math.abs(clipFromWorldRelative.m13()) > 1.0e-6f
                || Math.abs(clipFromWorldRelative.m23()) > 1.0e-6f
                || Math.abs(clipFromWorldRelative.m33() - 1.0f) > 1.0e-6f) {
            return true;
        }

        float centerX = (float) ((minimumX + maximumX) * 0.5 - camera.x());
        float centerY = (float) ((minimumY + maximumY) * 0.5 - camera.y());
        float centerZ = (float) ((minimumZ + maximumZ) * 0.5 - camera.z());
        float halfX = (float) ((maximumX - minimumX) * 0.5);
        float halfY = (float) ((maximumY - minimumY) * 0.5);
        float halfZ = (float) ((maximumZ - minimumZ) * 0.5);

        float clipX = clipFromWorldRelative.m00() * centerX
                + clipFromWorldRelative.m10() * centerY
                + clipFromWorldRelative.m20() * centerZ
                + clipFromWorldRelative.m30();
        float clipY = clipFromWorldRelative.m01() * centerX
                + clipFromWorldRelative.m11() * centerY
                + clipFromWorldRelative.m21() * centerZ
                + clipFromWorldRelative.m31();
        float clipZ = clipFromWorldRelative.m02() * centerX
                + clipFromWorldRelative.m12() * centerY
                + clipFromWorldRelative.m22() * centerZ
                + clipFromWorldRelative.m32();
        float extentX = Math.abs(clipFromWorldRelative.m00()) * halfX
                + Math.abs(clipFromWorldRelative.m10()) * halfY
                + Math.abs(clipFromWorldRelative.m20()) * halfZ;
        float extentY = Math.abs(clipFromWorldRelative.m01()) * halfX
                + Math.abs(clipFromWorldRelative.m11()) * halfY
                + Math.abs(clipFromWorldRelative.m21()) * halfZ;
        float extentZ = Math.abs(clipFromWorldRelative.m02()) * halfX
                + Math.abs(clipFromWorldRelative.m12()) * halfY
                + Math.abs(clipFromWorldRelative.m22()) * halfZ;
        if (!Float.isFinite(clipX) || !Float.isFinite(clipY) || !Float.isFinite(clipZ)
                || !Float.isFinite(extentX) || !Float.isFinite(extentY)
                || !Float.isFinite(extentZ)) {
            return true;
        }
        return clipX + extentX >= -1.0f - CLIP_EPSILON
                && clipX - extentX <= 1.0f + CLIP_EPSILON
                && clipY + extentY >= -1.0f - CLIP_EPSILON
                && clipY - extentY <= 1.0f + CLIP_EPSILON
                && clipZ + extentZ >= -CLIP_EPSILON
                && clipZ - extentZ <= 1.0f + CLIP_EPSILON;
    }

    private static boolean validBounds(
            final double minimumX,
            final double minimumY,
            final double minimumZ,
            final double maximumX,
            final double maximumY,
            final double maximumZ
    ) {
        return Double.isFinite(minimumX) && Double.isFinite(minimumY)
                && Double.isFinite(minimumZ) && Double.isFinite(maximumX)
                && Double.isFinite(maximumY) && Double.isFinite(maximumZ)
                && maximumX >= minimumX && maximumY >= minimumY && maximumZ >= minimumZ;
    }
}
