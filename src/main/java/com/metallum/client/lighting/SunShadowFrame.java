package com.metallum.client.lighting;

import com.metallum.client.renderer.SunShadowLayout;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.Objects;

/** Immutable camera-relative CSM state for one submitted Advanced-lighting frame. */
public final class SunShadowFrame {
    private static final float CASCADE_PADDING = 1.06f;

    private final EnvironmentDescriptor environment;
    private final SunShadowLayout.Budget budget;
    private final long lightingGeneration;
    private final long submitIndex;
    private final Matrix4f[] shadowFromView;
    private final Matrix4f[] shadowFromWorldRelative;
    private final float[] cascadeSplits;
    private final FrameState.CameraPosition cameraPosition;
    private final Vector3f toLightWorld;
    private final Vector3f toLightView;
    private final Vector3f worldUpView;
    private final long descriptorHash;

    private SunShadowFrame(
            final EnvironmentDescriptor environment,
            final SunShadowLayout.Budget budget,
            final long lightingGeneration,
            final long submitIndex,
            final Matrix4f[] shadowFromView,
            final Matrix4f[] shadowFromWorldRelative,
            final float[] cascadeSplits,
            final FrameState.CameraPosition cameraPosition,
            final Vector3f toLightWorld,
            final Vector3f toLightView,
            final Vector3f worldUpView,
            final long descriptorHash
    ) {
        this.environment = environment;
        this.budget = budget;
        this.lightingGeneration = lightingGeneration;
        this.submitIndex = submitIndex;
        this.shadowFromView = shadowFromView;
        this.shadowFromWorldRelative = shadowFromWorldRelative;
        this.cascadeSplits = cascadeSplits;
        this.cameraPosition = cameraPosition;
        this.toLightWorld = toLightWorld;
        this.toLightView = toLightView;
        this.worldUpView = worldUpView;
        this.descriptorHash = descriptorHash;
    }

    public static SunShadowFrame plan(
            final EnvironmentDescriptor environment,
            final SunShadowLayout.Budget budget,
            final FrameState frame
    ) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(frame, "frame");
        float nearPlane = (float) frame.nearPlane();
        float farPlane = (float) frame.farPlane();
        float[] splits = SunShadowLayout.cascadeSplits(budget, nearPlane, farPlane);
        Matrix4f camera = toJoml(frame.currentTransforms().unjitteredCamera());
        Matrix4f view = toJoml(frame.currentTransforms().unjitteredView());
        Matrix4f projection = toJoml(frame.currentTransforms().unjitteredProjection());
        Vector3f toLightWorld = new Vector3f(
                environment.toLightX(),
                environment.toLightY(),
                environment.toLightZ()
        );
        Vector3f toLightView = environment.profile() == EnvironmentDescriptor.Profile.CELESTIAL
                ? view.transformDirection(new Vector3f(toLightWorld)).normalize()
                : new Vector3f();
        Vector3f worldUpView = view.transformDirection(new Vector3f(0.0f, 1.0f, 0.0f)).normalize();
        Matrix4f[] matrices = new Matrix4f[SunShadowLayout.MAX_CASCADES];
        Matrix4f[] worldRelativeMatrices = new Matrix4f[SunShadowLayout.MAX_CASCADES];
        Arrays.setAll(matrices, ignored -> new Matrix4f());
        Arrays.setAll(worldRelativeMatrices, ignored -> new Matrix4f());

        if (environment.sunShadowEligible()) {
            float previousSplit = nearPlane;
            for (int cascade = 0; cascade < budget.cascadeCount(); cascade++) {
                CascadePlan planned = planCascade(
                        previousSplit,
                        splits[cascade],
                        budget,
                        projection,
                        camera,
                        toLightWorld,
                        frame.currentCameraPosition()
                );
                matrices[cascade].set(planned.shadowFromView());
                worldRelativeMatrices[cascade].set(planned.shadowFromWorldRelative());
                previousSplit = splits[cascade];
            }
        }
        long hash = descriptorHash(environment, budget, matrices, splits);
        return new SunShadowFrame(
                environment,
                budget,
                frame.lightingGenerationId(),
                frame.submitIndex(),
                matrices,
                worldRelativeMatrices,
                splits,
                frame.currentCameraPosition(),
                toLightWorld,
                toLightView,
                worldUpView,
                hash
        );
    }

    public EnvironmentDescriptor environment() {
        return this.environment;
    }

    public SunShadowLayout.Budget budget() {
        return this.budget;
    }

    public long lightingGeneration() {
        return this.lightingGeneration;
    }

    public long submitIndex() {
        return this.submitIndex;
    }

    public boolean needsShadowPass() {
        return this.environment.sunShadowEligible();
    }

    public int cascadeCount() {
        return this.budget.cascadeCount();
    }

    public Matrix4f shadowFromView(final int cascade) {
        if (cascade < 0 || cascade >= SunShadowLayout.MAX_CASCADES) {
            throw new IllegalArgumentException("Invalid cascade " + cascade);
        }
        return new Matrix4f(this.shadowFromView[cascade]);
    }

    /** Orthographic caster volume accepting camera-relative world coordinates. */
    public Matrix4f shadowFromWorldRelative(final int cascade) {
        if (cascade < 0 || cascade >= SunShadowLayout.MAX_CASCADES) {
            throw new IllegalArgumentException("Invalid cascade " + cascade);
        }
        return new Matrix4f(this.shadowFromWorldRelative[cascade]);
    }

    public float cascadeSplit(final int cascade) {
        if (cascade < 0 || cascade >= SunShadowLayout.MAX_CASCADES) {
            throw new IllegalArgumentException("Invalid cascade " + cascade);
        }
        return this.cascadeSplits[cascade];
    }

    public FrameState.CameraPosition cameraPosition() {
        return this.cameraPosition;
    }

    public Vector3f toLightWorld() {
        return new Vector3f(this.toLightWorld);
    }

    public Vector3f toLightView() {
        return new Vector3f(this.toLightView);
    }

    public Vector3f worldUpView() {
        return new Vector3f(this.worldUpView);
    }

    public long descriptorHash() {
        return this.descriptorHash;
    }

    static float casterExtrusion(final SunShadowLayout.Budget budget) {
        return Math.max(24.0f, budget.maximumDistance());
    }

    private static CascadePlan planCascade(
            final float nearDepth,
            final float farDepth,
            final SunShadowLayout.Budget budget,
            final Matrix4f projection,
            final Matrix4f viewToWorld,
            final Vector3f toLightWorld,
            final FrameState.CameraPosition cameraPosition
    ) {
        float tangentX = Math.abs(1.0f / projection.m00());
        float tangentY = Math.abs(1.0f / projection.m11());
        if (!Float.isFinite(tangentX) || !Float.isFinite(tangentY)
                || tangentX <= 0.0f || tangentY <= 0.0f) {
            throw new IllegalArgumentException("Invalid projection for cascade planning");
        }
        Vector3f[] corners = frustumCorners(
                nearDepth,
                farDepth,
                tangentX,
                tangentY,
                viewToWorld
        );
        Vector3f up = Math.abs(toLightWorld.y) > 0.94f
                ? new Vector3f(0.0f, 0.0f, 1.0f)
                : new Vector3f(0.0f, 1.0f, 0.0f);
        Matrix4f lightView = new Matrix4f().lookAlong(
                new Vector3f(toLightWorld).negate(),
                up
        );

        float minimumZ = Float.POSITIVE_INFINITY;
        float maximumZ = Float.NEGATIVE_INFINITY;
        for (Vector3f corner : corners) {
            Vector3f light = lightView.transformPosition(new Vector3f(corner));
            minimumZ = Math.min(minimumZ, light.z);
            maximumZ = Math.max(maximumZ, light.z);
        }
        float centerDepth = (nearDepth + farDepth) * 0.5f;
        float nearX = nearDepth * tangentX;
        float nearY = nearDepth * tangentY;
        float farX = farDepth * tangentX;
        float farY = farDepth * tangentY;
        float nearZ = centerDepth - nearDepth;
        float farZ = farDepth - centerDepth;
        float nearRadiusSquared = nearX * nearX + nearY * nearY + nearZ * nearZ;
        float farRadiusSquared = farX * farX + farY * farY + farZ * farZ;
        float halfExtent = (float) Math.sqrt(Math.max(nearRadiusSquared, farRadiusSquared))
                * CASCADE_PADDING;
        halfExtent = Math.max(halfExtent, 1.0f);
        Vector3f sliceCenter = viewToWorld.transformDirection(
                new Vector3f(0.0f, 0.0f, -centerDepth)
        );
        Vector3f lightCenter = lightView.transformPosition(sliceCenter);
        float centerX = lightCenter.x;
        float centerY = lightCenter.y;
        float worldUnitsPerTexel = (2.0f * halfExtent) / budget.resolution();

        double cameraLightX = lightView.m00() * cameraPosition.x()
                + lightView.m10() * cameraPosition.y()
                + lightView.m20() * cameraPosition.z();
        double cameraLightY = lightView.m01() * cameraPosition.x()
                + lightView.m11() * cameraPosition.y()
                + lightView.m21() * cameraPosition.z();
        double absoluteCenterX = centerX + cameraLightX;
        double absoluteCenterY = centerY + cameraLightY;
        centerX = (float) (Math.rint(absoluteCenterX / worldUnitsPerTexel)
                * worldUnitsPerTexel - cameraLightX);
        centerY = (float) (Math.rint(absoluteCenterY / worldUnitsPerTexel)
                * worldUnitsPerTexel - cameraLightY);

        float casterMargin = casterExtrusion(budget);
        float nearDistance = 1.0f;
        float farDistance = (maximumZ - minimumZ) + casterMargin + nearDistance;
        lightView.m32(-maximumZ - casterMargin - nearDistance);
        Matrix4f reversedOrtho = new Matrix4f().setOrtho(
                centerX - halfExtent,
                centerX + halfExtent,
                centerY - halfExtent,
                centerY + halfExtent,
                farDistance,
                nearDistance,
                true
        );
        Matrix4f shadowFromWorldRelative = reversedOrtho.mul(lightView);
        Matrix4f shadowFromView = new Matrix4f(shadowFromWorldRelative).mul(viewToWorld);
        if (!shadowFromWorldRelative.isFinite() || !shadowFromView.isFinite()) {
            throw new IllegalStateException("Cascade matrix is not finite");
        }
        return new CascadePlan(shadowFromView, shadowFromWorldRelative);
    }

    private static Vector3f[] frustumCorners(
            final float nearDepth,
            final float farDepth,
            final float tangentX,
            final float tangentY,
            final Matrix4f viewToWorld
    ) {
        Vector3f[] corners = new Vector3f[8];
        int index = 0;
        for (float depth : new float[]{nearDepth, farDepth}) {
            for (int y = -1; y <= 1; y += 2) {
                for (int x = -1; x <= 1; x += 2) {
                    Vector3f viewCorner = new Vector3f(
                            x * depth * tangentX,
                            y * depth * tangentY,
                            -depth
                    );
                    corners[index++] = viewToWorld.transformDirection(viewCorner);
                }
            }
        }
        return corners;
    }

    private static Matrix4f toJoml(final Matrix4 matrix) {
        double[] source = matrix.elements();
        float[] values = new float[source.length];
        for (int index = 0; index < source.length; index++) {
            values[index] = (float) source[index];
        }
        Matrix4f result = new Matrix4f().set(values);
        if (!result.isFinite()) {
            throw new IllegalArgumentException("Frame matrix is not finite");
        }
        return result;
    }

    private static long descriptorHash(
            final EnvironmentDescriptor environment,
            final SunShadowLayout.Budget budget,
            final Matrix4f[] matrices,
            final float[] splits
    ) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, environment.hashCode());
        hash = mix(hash, budget.hashCode());
        for (float split : splits) {
            hash = mix(hash, Float.floatToIntBits(split));
        }
        float[] values = new float[16];
        for (Matrix4f matrix : matrices) {
            matrix.get(values);
            for (float value : values) {
                hash = mix(hash, Float.floatToIntBits(value));
            }
        }
        return hash;
    }

    private static long mix(final long hash, final int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * 0x100000001b3L;
    }

    private record CascadePlan(
            Matrix4f shadowFromView,
            Matrix4f shadowFromWorldRelative
    ) {
    }
}
