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
    /**
     * The static cascade allocates this factor around the camera-centred receiver sphere.
     * L6 may only reuse a map while the current sphere remains inside that reserved border.
     */
    private static final float CASCADE_PADDING = 1.06f;

    private final EnvironmentDescriptor environment;
    private final SunShadowLayout.Budget budget;
    private final long lightingGeneration;
    private final long submitIndex;
    private final Matrix4f[] shadowFromView;
    private final Matrix4f[] shadowFromWorldRelative;
    private final Matrix4f worldFromView;
    private final float[] cascadeSplits;
    private final float[] cascadeNearDepths;
    private final float[] cascadeWorldUnitsPerTexel;
    private final FrameState.CameraPosition cameraPosition;
    private final long worldIdentity;
    private final long dimensionIdentity;
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
            final Matrix4f worldFromView,
            final float[] cascadeSplits,
            final float[] cascadeNearDepths,
            final float[] cascadeWorldUnitsPerTexel,
            final FrameState.CameraPosition cameraPosition,
            final long worldIdentity,
            final long dimensionIdentity,
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
        this.worldFromView = worldFromView;
        this.cascadeSplits = cascadeSplits;
        this.cascadeNearDepths = cascadeNearDepths;
        this.cascadeWorldUnitsPerTexel = cascadeWorldUnitsPerTexel;
        this.cameraPosition = cameraPosition;
        this.worldIdentity = worldIdentity;
        this.dimensionIdentity = dimensionIdentity;
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
        return plan(environment, budget, frame, null);
    }

    public static SunShadowFrame plan(
            final EnvironmentDescriptor environment,
            final SunShadowLayout.Budget budget,
            final FrameState frame,
            final SunShadowStabilizer stabilizer
    ) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(frame, "frame");
        if (stabilizer != null) {
            stabilizer.prepare(frame, environment);
        }
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
        float[] cascadeNearDepths = new float[SunShadowLayout.MAX_CASCADES];
        float[] cascadeWorldUnitsPerTexel = new float[SunShadowLayout.MAX_CASCADES];
        Arrays.setAll(matrices, ignored -> new Matrix4f());
        Arrays.setAll(worldRelativeMatrices, ignored -> new Matrix4f());

        if (environment.sunShadowEligible()) {
            float previousSplit = nearPlane;
            for (int cascade = 0; cascade < budget.cascadeCount(); cascade++) {
                float cascadeNearDepth = previousSplit;
                if (cascade > 0) {
                    float transitionPrevious = cascade == 1 ? 0.0f : splits[cascade - 2];
                    cascadeNearDepth = Math.max(
                            nearPlane,
                            SunShadowLayout.cascadeBlendStart(
                                    budget,
                                    transitionPrevious,
                                    previousSplit
                            )
                    );
                }
                CascadePlan planned = planCascade(
                        splits[cascade],
                        budget,
                        projection,
                        camera,
                        toLightWorld,
                        stabilizer,
                        cascade
                );
                matrices[cascade].set(planned.shadowFromView());
                worldRelativeMatrices[cascade].set(planned.shadowFromWorldRelative());
                cascadeNearDepths[cascade] = cascadeNearDepth;
                cascadeWorldUnitsPerTexel[cascade] = planned.worldUnitsPerTexel();
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
                camera,
                splits,
                cascadeNearDepths,
                cascadeWorldUnitsPerTexel,
                frame.currentCameraPosition(),
                frame.worldIdentity(),
                frame.dimensionIdentity(),
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

    /**
     * Reuses a frozen terrain shadow map from the current camera. The static map was rendered
     * with {@code frozen.cameraPosition}; translating the current camera-relative world by this
     * exact delta makes it address the same frozen world coordinates without losing L4's
     * integer-texel phase.
     */
    public static SunShadowFrame reprojectCached(
            final SunShadowFrame frozen,
            final SunShadowFrame current
    ) {
        Objects.requireNonNull(frozen, "frozen");
        Objects.requireNonNull(current, "current");
        if (!cacheCoverageMatches(frozen, current)) {
            throw new IllegalArgumentException("Cached cascade coverage no longer matches");
        }
        double deltaX = current.cameraPosition.x() - frozen.cameraPosition.x();
        double deltaY = current.cameraPosition.y() - frozen.cameraPosition.y();
        double deltaZ = current.cameraPosition.z() - frozen.cameraPosition.z();
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)
                || Math.abs(deltaX) > Float.MAX_VALUE || Math.abs(deltaY) > Float.MAX_VALUE
                || Math.abs(deltaZ) > Float.MAX_VALUE) {
            throw new IllegalArgumentException("Cached camera rebase is not representable");
        }
        Matrix4f[] rebasedWorldRelative = new Matrix4f[SunShadowLayout.MAX_CASCADES];
        Matrix4f[] rebasedView = new Matrix4f[SunShadowLayout.MAX_CASCADES];
        for (int cascade = 0; cascade < frozen.cascadeCount(); cascade++) {
            Matrix4f worldRelative = new Matrix4f(frozen.shadowFromWorldRelative[cascade]).translate(
                    (float) deltaX, (float) deltaY, (float) deltaZ
            );
            Matrix4f view = new Matrix4f(worldRelative).mul(current.worldFromView);
            if (!worldRelative.isFinite() || !view.isFinite()) {
                throw new IllegalArgumentException("Cached cascade rebase is not finite");
            }
            rebasedWorldRelative[cascade] = worldRelative;
            rebasedView[cascade] = view;
        }
        for (int cascade = frozen.cascadeCount(); cascade < SunShadowLayout.MAX_CASCADES; cascade++) {
            rebasedWorldRelative[cascade] = new Matrix4f();
            rebasedView[cascade] = new Matrix4f();
        }
        long hash = descriptorHash(current.environment, current.budget, rebasedView,
                frozen.cascadeSplits);
        return new SunShadowFrame(
                current.environment,
                current.budget,
                current.lightingGeneration,
                current.submitIndex,
                rebasedView,
                rebasedWorldRelative,
                new Matrix4f(current.worldFromView),
                frozen.cascadeSplits.clone(),
                frozen.cascadeNearDepths.clone(),
                frozen.cascadeWorldUnitsPerTexel.clone(),
                current.cameraPosition,
                current.worldIdentity,
                current.dimensionIdentity,
                new Vector3f(current.toLightWorld),
                new Vector3f(current.toLightView),
                new Vector3f(current.worldUpView),
                hash
        );
    }

    /** True when a frozen map can cover the current receiver volume without resampling. */
    public static boolean cacheCoverageMatches(
            final SunShadowFrame frozen,
            final SunShadowFrame current
    ) {
        Objects.requireNonNull(frozen, "frozen");
        Objects.requireNonNull(current, "current");
        if (frozen.lightingGeneration != current.lightingGeneration
                || !frozen.budget.equals(current.budget)
                || frozen.cascadeCount() != current.cascadeCount()
                || frozen.worldIdentity != current.worldIdentity
                || frozen.dimensionIdentity != current.dimensionIdentity) {
            return false;
        }
        for (int cascade = 0; cascade < frozen.cascadeCount(); cascade++) {
            if (!sameCoverage(frozen.cascadeSplits[cascade], current.cascadeSplits[cascade])
                    || !sameCoverage(frozen.cascadeNearDepths[cascade], current.cascadeNearDepths[cascade])
                    || !sameCoverage(frozen.cascadeWorldUnitsPerTexel[cascade],
                    current.cascadeWorldUnitsPerTexel[cascade])) {
                return false;
            }
        }
        return true;
    }

    /**
     * True only when the camera translation leaves every current receiver sphere inside the
     * frozen cascade's real 1.06 footprint.  The test is performed in each frozen cascade's
     * light-space coordinates, rather than with an arbitrary world-space distance.
     *
     * <p>{@link #planCascade(float, SunShadowLayout.Budget, Matrix4f, Matrix4f,
     * Vector3f, SunShadowStabilizer, int)} fits the receiver frustum into a sphere of radius
     * {@code halfExtent / CASCADE_PADDING}; the remaining normalized map border is therefore
     * exactly {@code 1 - 1 / CASCADE_PADDING}.  A sphere contains every yaw/pitch variant of
     * the receiver frustum, so accepting this border proves XYZ containment for the current
     * receiver footprint.</p>
     */
    static boolean cachedReceiverFootprintContains(
            final SunShadowFrame frozen,
            final SunShadowFrame current
    ) {
        if (!cacheCoverageMatches(frozen, current)) {
            return false;
        }
        double deltaX = current.cameraPosition.x() - frozen.cameraPosition.x();
        double deltaY = current.cameraPosition.y() - frozen.cameraPosition.y();
        double deltaZ = current.cameraPosition.z() - frozen.cameraPosition.z();
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)
                || Math.abs(deltaX) > Float.MAX_VALUE || Math.abs(deltaY) > Float.MAX_VALUE
                || Math.abs(deltaZ) > Float.MAX_VALUE) {
            return false;
        }
        float normalizedBorder = 1.0f - 1.0f / CASCADE_PADDING;
        Vector3f lightAxisZ = new Vector3f(frozen.toLightWorld).normalize();
        double lightSpaceZ = deltaX * lightAxisZ.x
                + deltaY * lightAxisZ.y
                + deltaZ * lightAxisZ.z;
        if (!Double.isFinite(lightSpaceZ)) {
            return false;
        }
        for (int cascade = 0; cascade < frozen.cascadeCount(); cascade++) {
            Vector3f translated = frozen.shadowFromWorldRelative[cascade].transformDirection(
                    new Vector3f((float) deltaX, (float) deltaY, (float) deltaZ)
            );
            float worldPadding = frozen.cascadeCacheWorldPadding(cascade);
            float paddingTolerance = Math.max(1.0e-5f, worldPadding * 1.0e-5f);
            if (!translated.isFinite()
                    || Math.abs(translated.x) > normalizedBorder
                    || Math.abs(translated.y) > normalizedBorder
                    || Math.abs(lightSpaceZ) > worldPadding + paddingTolerance) {
                return false;
            }
        }
        return true;
    }

    public float cascadeSplit(final int cascade) {
        if (cascade < 0 || cascade >= SunShadowLayout.MAX_CASCADES) {
            throw new IllegalArgumentException("Invalid cascade " + cascade);
        }
        return this.cascadeSplits[cascade];
    }

    public float cascadeNearDepth(final int cascade) {
        validateCascade(cascade);
        return this.cascadeNearDepths[cascade];
    }

    public float cascadeWorldUnitsPerTexel(final int cascade) {
        validateCascade(cascade);
        return this.cascadeWorldUnitsPerTexel[cascade];
    }

    float cascadeCacheWorldPadding(final int cascade) {
        validateCascade(cascade);
        float halfExtent = this.cascadeWorldUnitsPerTexel[cascade]
                * this.budget.resolution() * 0.5f;
        float padding = halfExtent * (1.0f - 1.0f / CASCADE_PADDING);
        if (!Float.isFinite(padding) || padding <= 0.0f) {
            throw new IllegalStateException("Cascade cache padding is not positive and finite");
        }
        return padding;
    }

    public float cascadeReceiverNormalBias(final int cascade) {
        validateCascade(cascade);
        return Math.max(
                this.budget.receiverNormalBias(),
                this.cascadeWorldUnitsPerTexel[cascade]
                        * this.budget.receiverNormalBiasTexels()
        );
    }

    /** Metal adds raster bias to depth; reverse-Z shadow casters must move toward zero. */
    public float reverseZRasterDepthBias() {
        return -this.budget.rasterDepthBias();
    }

    public float reverseZRasterSlopeBias() {
        return -this.budget.rasterSlopeBias();
    }

    public FrameState.CameraPosition cameraPosition() {
        return this.cameraPosition;
    }

    public long worldIdentity() {
        return this.worldIdentity;
    }

    public long dimensionIdentity() {
        return this.dimensionIdentity;
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

    private static void validateCascade(final int cascade) {
        if (cascade < 0 || cascade >= SunShadowLayout.MAX_CASCADES) {
            throw new IllegalArgumentException("Invalid cascade " + cascade);
        }
    }

    private static boolean sameCoverage(final float left, final float right) {
        return Float.isFinite(left) && Float.isFinite(right)
                && Math.abs(left - right) <= Math.max(1.0e-5f, Math.abs(left) * 1.0e-5f);
    }

    private static CascadePlan planCascade(
            final float farDepth,
            final SunShadowLayout.Budget budget,
            final Matrix4f projection,
            final Matrix4f viewToWorld,
            final Vector3f toLightWorld,
            final SunShadowStabilizer stabilizer,
            final int cascade
    ) {
        float tangentX = Math.abs(1.0f / projection.m00());
        float tangentY = Math.abs(1.0f / projection.m11());
        if (!Float.isFinite(tangentX) || !Float.isFinite(tangentY)
                || tangentX <= 0.0f || tangentY <= 0.0f) {
            throw new IllegalArgumentException("Invalid projection for cascade planning");
        }
        // Celestial directions are constrained to the world XY plane. A fixed Z up axis keeps
        // the light basis continuous through noon instead of switching the cascade roll by 90°.
        Vector3f up = new Vector3f(0.0f, 0.0f, 1.0f);
        Matrix4f lightView = new Matrix4f().lookAlong(
                new Vector3f(toLightWorld).negate(),
                up
        );

        // Enclose the receiver slice in a camera-centred sphere. Its world-space footprint is
        // independent of camera yaw/pitch, so looking around cannot move either the shadow
        // texel lattice or its receiver depth interval. Camera translation remains continuous
        // in camera-relative coordinates.
        float halfExtent = farDepth * (float) Math.sqrt(
                1.0f + tangentX * tangentX + tangentY * tangentY
        ) * CASCADE_PADDING;
        halfExtent = Math.max(halfExtent, 1.0f);
        float worldUnitsPerTexel = (2.0f * halfExtent) / budget.resolution();
        SunShadowStabilizer.LightSpaceCenter center = stabilizer == null
                ? SunShadowStabilizer.LightSpaceCenter.ZERO
                : stabilizer.center(cascade, worldUnitsPerTexel, lightView);

        float casterMargin = casterExtrusion(budget);
        float nearDistance = 1.0f;
        float farDistance = 2.0f * halfExtent + casterMargin + nearDistance;
        lightView.m32(-halfExtent - casterMargin - nearDistance);
        Matrix4f reversedOrtho = new Matrix4f().setOrtho(
                center.x() - halfExtent,
                center.x() + halfExtent,
                center.y() - halfExtent,
                center.y() + halfExtent,
                farDistance,
                nearDistance,
                true
        );
        Matrix4f shadowFromWorldRelative = reversedOrtho.mul(lightView);
        Matrix4f shadowFromView = new Matrix4f(shadowFromWorldRelative).mul(viewToWorld);
        if (!shadowFromWorldRelative.isFinite() || !shadowFromView.isFinite()) {
            throw new IllegalStateException("Cascade matrix is not finite");
        }
        return new CascadePlan(shadowFromView, shadowFromWorldRelative, worldUnitsPerTexel);
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
            Matrix4f shadowFromWorldRelative,
            float worldUnitsPerTexel
    ) {
    }
}
