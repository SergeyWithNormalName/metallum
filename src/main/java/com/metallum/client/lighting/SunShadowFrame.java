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
    private final float[] cascadeSplits;
    private final Vector3f toLightView;
    private final Vector3f worldUpView;
    private final long descriptorHash;

    private SunShadowFrame(
            final EnvironmentDescriptor environment,
            final SunShadowLayout.Budget budget,
            final long lightingGeneration,
            final long submitIndex,
            final Matrix4f[] shadowFromView,
            final float[] cascadeSplits,
            final Vector3f toLightView,
            final Vector3f worldUpView,
            final long descriptorHash
    ) {
        this.environment = environment;
        this.budget = budget;
        this.lightingGeneration = lightingGeneration;
        this.submitIndex = submitIndex;
        this.shadowFromView = shadowFromView;
        this.cascadeSplits = cascadeSplits;
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
        Arrays.setAll(matrices, ignored -> new Matrix4f());

        if (environment.sunShadowEligible()) {
            float previousSplit = nearPlane;
            for (int cascade = 0; cascade < budget.cascadeCount(); cascade++) {
                matrices[cascade].set(planCascade(
                        previousSplit,
                        splits[cascade],
                        budget,
                        projection,
                        camera,
                        toLightWorld,
                        frame.currentCameraPosition()
                ));
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
                splits,
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

    public float cascadeSplit(final int cascade) {
        if (cascade < 0 || cascade >= SunShadowLayout.MAX_CASCADES) {
            throw new IllegalArgumentException("Invalid cascade " + cascade);
        }
        return this.cascadeSplits[cascade];
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

    private static Matrix4f planCascade(
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

        float minimumX = Float.POSITIVE_INFINITY;
        float maximumX = Float.NEGATIVE_INFINITY;
        float minimumY = Float.POSITIVE_INFINITY;
        float maximumY = Float.NEGATIVE_INFINITY;
        float minimumZ = Float.POSITIVE_INFINITY;
        float maximumZ = Float.NEGATIVE_INFINITY;
        for (Vector3f corner : corners) {
            Vector3f light = lightView.transformPosition(new Vector3f(corner));
            minimumX = Math.min(minimumX, light.x);
            maximumX = Math.max(maximumX, light.x);
            minimumY = Math.min(minimumY, light.y);
            maximumY = Math.max(maximumY, light.y);
            minimumZ = Math.min(minimumZ, light.z);
            maximumZ = Math.max(maximumZ, light.z);
        }
        float halfExtent = Math.max(maximumX - minimumX, maximumY - minimumY)
                * 0.5f * CASCADE_PADDING;
        halfExtent = Math.max(halfExtent, 1.0f);
        float centerX = (minimumX + maximumX) * 0.5f;
        float centerY = (minimumY + maximumY) * 0.5f;
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

        float casterMargin = Math.max(24.0f, farDepth * 0.55f);
        float nearDistance = casterMargin;
        float farDistance = (maximumZ - minimumZ) + 2.0f * casterMargin;
        lightView.m32(-maximumZ - casterMargin);
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
        Matrix4f shadowFromView = shadowFromWorldRelative.mul(viewToWorld);
        if (!shadowFromView.isFinite()) {
            throw new IllegalStateException("Cascade matrix is not finite");
        }
        return shadowFromView;
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
}
