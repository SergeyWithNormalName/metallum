package com.metallum.client.lighting;

import com.metallum.client.renderer.SunShadowLayout;
import com.metallum.client.renderer.temporal.FrameState;
import org.joml.Matrix4f;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps camera translation on an integer light-space texel phase without ever projecting
 * absolute large-world coordinates into the rotating sun basis.
 */
public final class SunShadowStabilizer {
    private static final Set<FrameState.HistoryResetReason> HARD_RESETS = Set.of(
            FrameState.HistoryResetReason.FIRST_FRAME,
            FrameState.HistoryResetReason.WORLD_LOAD_UNLOAD,
            FrameState.HistoryResetReason.DIMENSION_CHANGE,
            FrameState.HistoryResetReason.TELEPORT,
            FrameState.HistoryResetReason.CAMERA_CUT,
            FrameState.HistoryResetReason.RENDERER_GENERATION_CHANGE,
            FrameState.HistoryResetReason.LIGHTING_MODEL_CHANGE
    );

    private final double[] residualX = new double[SunShadowLayout.MAX_CASCADES];
    private final double[] residualY = new double[SunShadowLayout.MAX_CASCADES];
    private final boolean[] cascadeUpdated = new boolean[SunShadowLayout.MAX_CASCADES];
    private FrameState.CameraPosition previousCamera;
    private long previousWorldIdentity = Long.MIN_VALUE;
    private long previousDimensionIdentity = Long.MIN_VALUE;
    private long preparedSubmitIndex = Long.MIN_VALUE;
    private FrameState preparedFrame;
    private EnvironmentDescriptor preparedEnvironment;
    private double cameraDeltaX;
    private double cameraDeltaY;
    private double cameraDeltaZ;
    private boolean advancePhase;
    private boolean preparedEligible;
    private boolean preparedMoon;

    public SunShadowStabilizer() {
    }

    void prepare(
            final FrameState frame,
            final EnvironmentDescriptor environment
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(environment, "environment");
        boolean eligible = environment.sunShadowEligible();
        if (frame.submitIndex() == this.preparedSubmitIndex) {
            if (frame != this.preparedFrame || !environment.equals(this.preparedEnvironment)) {
                throw new IllegalArgumentException(
                        "One submitted frame cannot change its sun-shadow phase inputs"
                );
            }
            return;
        }

        FrameState.CameraPosition current = frame.currentCameraPosition();
        boolean hardReset = this.previousCamera == null
                || frame.submitIndex() < this.preparedSubmitIndex
                || frame.worldIdentity() != this.previousWorldIdentity
                || frame.dimensionIdentity() != this.previousDimensionIdentity
                || hasHardReset(frame.historyResetReasons())
                || !eligible
                || !this.preparedEligible
                || environment.moon() != this.preparedMoon;
        if (hardReset) {
            Arrays.fill(this.residualX, 0.0);
            Arrays.fill(this.residualY, 0.0);
            this.cameraDeltaX = 0.0;
            this.cameraDeltaY = 0.0;
            this.cameraDeltaZ = 0.0;
        } else {
            this.cameraDeltaX = current.x() - this.previousCamera.x();
            this.cameraDeltaY = current.y() - this.previousCamera.y();
            this.cameraDeltaZ = current.z() - this.previousCamera.z();
        }
        Arrays.fill(this.cascadeUpdated, false);
        this.advancePhase = eligible && !hardReset;
        this.previousCamera = current;
        this.previousWorldIdentity = frame.worldIdentity();
        this.previousDimensionIdentity = frame.dimensionIdentity();
        this.preparedSubmitIndex = frame.submitIndex();
        this.preparedFrame = frame;
        this.preparedEnvironment = environment;
        this.preparedEligible = eligible;
        this.preparedMoon = environment.moon();
    }

    LightSpaceCenter center(
            final int cascade,
            final float worldUnitsPerTexel,
            final Matrix4f lightView
    ) {
        if (cascade < 0 || cascade >= SunShadowLayout.MAX_CASCADES) {
            throw new IllegalArgumentException("Invalid cascade " + cascade);
        }
        if (!Float.isFinite(worldUnitsPerTexel) || worldUnitsPerTexel <= 0.0f) {
            throw new IllegalArgumentException("Shadow texel scale must be positive and finite");
        }
        Objects.requireNonNull(lightView, "lightView");
        if (this.preparedSubmitIndex == Long.MIN_VALUE || !this.preparedEligible) {
            throw new IllegalStateException("Sun-shadow stabilizer was not prepared for a shadow frame");
        }
        if (!this.cascadeUpdated[cascade]) {
            if (this.advancePhase) {
                this.residualX[cascade] += lightView.m00() * this.cameraDeltaX
                        + lightView.m10() * this.cameraDeltaY
                        + lightView.m20() * this.cameraDeltaZ;
                this.residualY[cascade] += lightView.m01() * this.cameraDeltaX
                        + lightView.m11() * this.cameraDeltaY
                        + lightView.m21() * this.cameraDeltaZ;
                this.residualX[cascade] = wrapToHalfTexel(
                        this.residualX[cascade], worldUnitsPerTexel
                );
                this.residualY[cascade] = wrapToHalfTexel(
                        this.residualY[cascade], worldUnitsPerTexel
                );
            }
            this.cascadeUpdated[cascade] = true;
        }
        return new LightSpaceCenter(
                (float) -this.residualX[cascade],
                (float) -this.residualY[cascade]
        );
    }

    private static boolean hasHardReset(
            final Set<FrameState.HistoryResetReason> resetReasons
    ) {
        for (FrameState.HistoryResetReason reason : resetReasons) {
            if (HARD_RESETS.contains(reason)) {
                return true;
            }
        }
        return false;
    }

    private static double wrapToHalfTexel(final double value, final double texel) {
        double wholeTexels = Math.floor(value / texel + 0.5);
        double wrapped = value - wholeTexels * texel;
        if (!Double.isFinite(wrapped)) {
            throw new IllegalStateException("Shadow translation phase is not finite");
        }
        return wrapped;
    }

    record LightSpaceCenter(float x, float y) {
        static final LightSpaceCenter ZERO = new LightSpaceCenter(0.0f, 0.0f);
    }
}
