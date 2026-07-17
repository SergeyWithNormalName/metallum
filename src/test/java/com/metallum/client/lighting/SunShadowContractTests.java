package com.metallum.client.lighting;

import com.metallum.client.lighting.shader.EnvironmentShadowBindingAbi;
import com.metallum.client.metal.render.SodiumShadowUniformState;
import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RenderContractMode;
import com.metallum.client.renderer.RendererFeatureMask;
import com.metallum.client.renderer.RendererGenerationConfig;
import com.metallum.client.renderer.SunShadowLayout;
import com.metallum.client.renderer.temporal.FrameContract;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Set;

/** Dependency-free numeric and generation contracts for the L4 environment and CSM path. */
public final class SunShadowContractTests {
    private SunShadowContractTests() {
    }

    public static void main(final String[] args) {
        testEnvironmentProfiles();
        testPresetLayoutsAndMemoryCaps();
        testCascadeSplits();
        testCameraRelativePlanningAndOutputIndependence();
        testSunRotationAndLightBasisContinuity();
        testTranslationPhaseLockedAndRotationStableCascades();
        testRotatedCascadeCoverageAndCasterExtrusion();
        testCascadeBlendCoverageAndScaleAwareBias();
        testOuterCascadeFadeAndClosedCaveFallback();
        testSodiumCascadeUniformLifecycle();
        testBindingAbi();
        System.out.println("PASS L4 environment and cascaded sun-shadow numeric contracts");
    }

    private static void testEnvironmentProfiles() {
        EnvironmentDescriptor noon = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.0f,
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                10.0f / 255.0f,
                10.0f / 255.0f,
                10.0f / 255.0f,
                0.0f,
                0.0f,
                1.0f
        );
        EnvironmentDescriptor storm = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.0f,
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                10.0f / 255.0f,
                10.0f / 255.0f,
                10.0f / 255.0f,
                0.0f,
                1.0f,
                1.0f
        );
        EnvironmentDescriptor midnight = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                (float) Math.PI,
                122.0f / 255.0f,
                122.0f / 255.0f,
                1.0f,
                0.24f,
                10.0f / 255.0f,
                10.0f / 255.0f,
                10.0f / 255.0f,
                0.0f,
                0.0f,
                0.75f
        );
        EnvironmentDescriptor lava = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.LAVA,
                0.0f,
                1.0f,
                0.4f,
                0.1f,
                1.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                1.0f
        );
        EnvironmentDescriptor end = EnvironmentDescriptor.ambientOnly(
                EnvironmentDescriptor.Profile.END,
                EnvironmentDescriptor.Medium.AIR,
                0.16f,
                0.12f,
                0.20f
        );

        require(noon.sunShadowEligible() && !noon.moon() && noon.directionalRed() > 1.0f,
                "daylight descriptor lost its directional sun");
        require(storm.directionalRed() < noon.directionalRed()
                        && storm.skyBlue() < noon.skyBlue(),
                "weather did not attenuate direct and diffuse sky radiance");
        require(midnight.sunShadowEligible() && midnight.moon()
                        && midnight.directionalBlue() > midnight.directionalRed(),
                "night descriptor lost its blue moon directional light");
        float inversePi = 0.31830988618f;
        float expectedNightSkyIrradiance = 0.46f * (float) Math.sqrt(0.24f);
        require(close(midnight.skyBlue(), expectedNightSkyIrradiance)
                        && close(midnight.skyRed(), expectedNightSkyIrradiance * 122.0f / 255.0f)
                        && close(midnight.ambientRed() * inversePi, 10.0f / 255.0f),
                "data-driven night sky/ambient calibration changed");
        require((midnight.ambientRed() + midnight.skyRed() * 0.30f) * inversePi
                        >= 0.0494f
                        && (midnight.ambientBlue() + midnight.skyBlue() * 0.30f) * inversePi
                        >= 0.0606f,
                "night side-face irradiance regressed below the visible terrain floor");
        float noonUpwardDiffuse = (noon.ambientRed() + noon.skyRed()
                + noon.directionalRed()) * inversePi;
        require(noonUpwardDiffuse >= 0.68f && noonUpwardDiffuse <= 0.75f,
                "daylight terrain calibration clips or under-lights diffuse albedo");
        require(!lava.sunShadowEligible() && lava.directionalRed() == 0.0f,
                "opaque lava medium retained an external celestial shadow");
        require(end.profile() == EnvironmentDescriptor.Profile.END
                        && !end.sunShadowEligible() && end.ambientBlue() == 0.20f,
                "ambient-only dimension retained directional state");
        expectIllegalArgument(() -> new EnvironmentDescriptor(
                EnvironmentDescriptor.VERSION,
                EnvironmentDescriptor.Profile.CELESTIAL,
                EnvironmentDescriptor.Medium.AIR,
                2.0f, 0.0f, 0.0f,
                1.0f, 1.0f, 1.0f,
                1.0f, 1.0f, 1.0f,
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f,
                false, true
        ));
        expectIllegalArgument(() -> new EnvironmentDescriptor(
                EnvironmentDescriptor.VERSION,
                EnvironmentDescriptor.Profile.CELESTIAL,
                EnvironmentDescriptor.Medium.AIR,
                0.0f, 0.0f, 1.0f,
                1.0f, 1.0f, 1.0f,
                1.0f, 1.0f, 1.0f,
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f,
                false, true
        ));
    }

    private static void testPresetLayoutsAndMemoryCaps() {
        LightingPreset[] presets = LightingPreset.values();
        int[] expectedCascades = {2, 3, 3};
        int[] expectedResolution = {768, 1024, 1536};
        long[] memoryCaps = {192L << 20, 256L << 20, 384L << 20};
        for (int index = 0; index < presets.length; index++) {
            SunShadowLayout.Budget budget = SunShadowLayout.forPreset(presets[index]);
            long expectedTextureBytes = (long) expectedCascades[index]
                    * expectedResolution[index] * expectedResolution[index]
                    * (SunShadowLayout.SHADOW_COLOR_BYTES_PER_PIXEL
                    + SunShadowLayout.SHADOW_DEPTH_BYTES_PER_PIXEL);
            require(budget.cascadeCount() == expectedCascades[index]
                            && budget.resolution() == expectedResolution[index],
                    "preset changed its declared cascade topology");
            require(budget.paramsRingBytes()
                            == (long) SunShadowLayout.PARAMS_BYTES * SunShadowLayout.PARAMS_RING_SLOTS
                            && budget.shadowTextureBytes() == expectedTextureBytes
                            && budget.totalBytes() < memoryCaps[index],
                    "shadow layout byte accounting or cap changed");
            require(budget.pcfRadiusTexels() >= 1.0f
                            && budget.receiverDepthBias() > 0.0f
                            && budget.receiverNormalBias() > 0.0f
                            && budget.receiverNormalBiasTexels() >= 0.25f,
                    "PCF/bias controls are not usable");
        }
    }

    private static void testCascadeSplits() {
        for (LightingPreset preset : LightingPreset.values()) {
            SunShadowLayout.Budget budget = SunShadowLayout.forPreset(preset);
            float[] splits = SunShadowLayout.cascadeSplits(budget, 0.05f, 1_024.0f);
            float previous = 0.05f;
            for (int cascade = 0; cascade < budget.cascadeCount(); cascade++) {
                require(Float.isFinite(splits[cascade]) && splits[cascade] > previous,
                        "cascade splits are not strictly increasing");
                previous = splits[cascade];
            }
            require(splits[budget.cascadeCount() - 1] == budget.maximumDistance(),
                    "last cascade does not end at the preset shadow distance");
        }
    }

    private static void testCameraRelativePlanningAndOutputIndependence() {
        EnvironmentDescriptor environment = shadowEnvironment();
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        FrameState sdr = frame(DisplayOutputMode.SDR, 30_000_000.0);
        FrameState hdr = frame(DisplayOutputMode.HDR, 30_000_000.0);
        SunShadowFrame first = SunShadowFrame.plan(environment, budget, sdr);
        SunShadowFrame outputVariant = SunShadowFrame.plan(environment, budget, hdr);
        SunShadowFrame subTexelMove = SunShadowFrame.plan(
                environment,
                budget,
                frame(DisplayOutputMode.SDR, 30_000_000.00001)
        );

        require(first.needsShadowPass() && first.cascadeCount() == 3,
                "eligible environment did not plan all Balanced cascades");
        require(first.descriptorHash() == outputVariant.descriptorHash(),
                "SDR/HDR output mode changed the environment/shadow contract");
        for (int cascade = 0; cascade < first.cascadeCount(); cascade++) {
            Matrix4f matrix = first.shadowFromView(cascade);
            Matrix4f moved = subTexelMove.shadowFromView(cascade);
            require(matrix.isFinite() && moved.isFinite(),
                    "large camera coordinates produced a non-finite cascade matrix");
            float[] left = new float[16];
            float[] right = new float[16];
            matrix.get(left);
            moved.get(right);
            float maximumDelta = 0.0f;
            for (int component = 0; component < left.length; component++) {
                maximumDelta = Math.max(maximumDelta, Math.abs(left[component] - right[component]));
            }
            require(maximumDelta < 0.01f,
                    "sub-texel camera motion destabilized a cascade projection");
        }
    }

    private static void testSunRotationAndLightBasisContinuity() {
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        FrameState state = frame(DisplayOutputMode.SDR, 29_999_900.0);
        Vector3f receiver = new Vector3f(3.25f, 1.75f, -8.0f);
        float maximumMotionTexels = 0.0f;
        for (int sample = 0; sample < 64; sample++) {
            float angle = 0.22f + sample * 0.0031f;
            SunShadowFrame first = SunShadowFrame.plan(
                    shadowEnvironment(angle),
                    budget,
                    state
            );
            SunShadowFrame next = SunShadowFrame.plan(
                    shadowEnvironment(angle + 1.0e-6f),
                    budget,
                    state
            );
            for (int cascade = 0; cascade < first.cascadeCount(); cascade++) {
                TexelPosition before = shadowTexelPosition(first, cascade, receiver);
                TexelPosition after = shadowTexelPosition(next, cascade, receiver);
                maximumMotionTexels = Math.max(
                        maximumMotionTexels,
                        before.distance(after)
                );
            }
        }
        require(maximumMotionTexels < 0.02f,
                "smooth sun rotation rebased a stationary large-world cascade by "
                        + maximumMotionTexels + " texels");

        float minecraftTickAngle = (float) (Math.PI * 2.0 / 24_000.0);
        SunShadowFrame previousTickSample = SunShadowFrame.plan(
                shadowEnvironment(0.40f),
                budget,
                state
        );
        for (int partial = 1; partial <= 4; partial++) {
            SunShadowFrame tickSample = SunShadowFrame.plan(
                    shadowEnvironment(0.40f + minecraftTickAngle * partial * 0.25f),
                    budget,
                    state
            );
            for (int cascade = 0; cascade < tickSample.cascadeCount(); cascade++) {
                TexelPosition before = shadowTexelPosition(
                        previousTickSample, cascade, receiver
                );
                TexelPosition after = shadowTexelPosition(tickSample, cascade, receiver);
                require(before.distance(after) < 0.25f,
                        "partial-tick sun motion caused a visible cascade rebase");
            }
            previousTickSample = tickSample;
        }

        float formerThreshold = (float) Math.acos(0.94f);
        SunShadowFrame lower = SunShadowFrame.plan(
                shadowEnvironment(formerThreshold - 1.0e-6f),
                budget,
                state
        );
        SunShadowFrame upper = SunShadowFrame.plan(
                shadowEnvironment(formerThreshold + 1.0e-6f),
                budget,
                state
        );
        for (int cascade = 0; cascade < lower.cascadeCount(); cascade++) {
            Vector3f lowerU = shadowAxisU(lower.shadowFromWorldRelative(cascade));
            Vector3f upperU = shadowAxisU(upper.shadowFromWorldRelative(cascade));
            Vector3f lowerV = shadowAxisV(lower.shadowFromWorldRelative(cascade));
            Vector3f upperV = shadowAxisV(upper.shadowFromWorldRelative(cascade));
            require(lowerU.dot(upperU) > 0.9999f && lowerV.dot(upperV) > 0.9999f,
                    "celestial light basis jumped at the former high-sun threshold");
        }
    }

    private static void testTranslationPhaseLockedAndRotationStableCascades() {
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        EnvironmentDescriptor environment = shadowEnvironment();
        for (double sign : new double[]{-1.0, 1.0}) {
            double seed = sign * 29_999_900.0;
            double receiverX = seed + 3.25;
            double receiverY = 97.75;
            double receiverZ = -30_000_008.0;
            SunShadowStabilizer stabilizer = new SunShadowStabilizer();
            TexelPosition[] previous = new TexelPosition[SunShadowLayout.MAX_CASCADES];
            int[] integerShifts = new int[SunShadowLayout.MAX_CASCADES];
            for (int sample = 0; sample <= 256; sample++) {
                SunShadowFrame moved = SunShadowFrame.plan(
                        environment,
                        budget,
                        frame(
                                DisplayOutputMode.SDR,
                                seed + sample * 0.0375,
                                96.0 + sample * 0.00625,
                                -30_000_000.0 + sample * 0.021875,
                                0.0f,
                                0.0f,
                                1_000L + sample,
                                Set.of(),
                                2L,
                                3L
                        ),
                        stabilizer
                );
                for (int cascade = 0; cascade < moved.cascadeCount(); cascade++) {
                    TexelPosition position = shadowTexelPosition(
                            moved, cascade, receiverX, receiverY, receiverZ
                    );
                    if (previous[cascade] != null) {
                        float deltaX = position.x() - previous[cascade].x();
                        float deltaY = position.y() - previous[cascade].y();
                        require(close(deltaX, (float) Math.rint(deltaX), 0.002f)
                                        && close(deltaY, (float) Math.rint(deltaY), 0.002f),
                                "camera motion changed the fractional shadow-texel phase");
                        if (Math.rint(deltaX) != 0.0 || Math.rint(deltaY) != 0.0) {
                            integerShifts[cascade]++;
                        }
                    }
                    previous[cascade] = position;
                }
            }
            for (int cascade = 0; cascade < budget.cascadeCount(); cascade++) {
                require(integerShifts[cascade] > 0,
                        "walking never advanced cascade " + cascade + " by a whole texel");
            }
        }

        FrameState.CameraPosition camera = frame(
                DisplayOutputMode.SDR, 29_999_900.0, 0.0f, 0.0f
        ).currentCameraPosition();
        Vector3f receiverRelative = new Vector3f(3.25f, 1.75f, -8.0f);
        SunShadowStabilizer rotationStabilizer = new SunShadowStabilizer();
        SunShadowFrame.plan(
                environment,
                budget,
                frame(
                        DisplayOutputMode.SDR,
                        camera.x() - 0.03125,
                        96.0,
                        -30_000_000.0,
                        0.0f,
                        0.0f,
                        2_000L,
                        Set.of(),
                        2L,
                        3L
                ),
                rotationStabilizer
        );
        SunShadowFrame reference = SunShadowFrame.plan(
                environment,
                budget,
                frame(
                        DisplayOutputMode.SDR,
                        camera.x(),
                        96.0,
                        -30_000_000.0,
                        0.0f,
                        0.0f,
                        2_001L,
                        Set.of(),
                        2L,
                        3L
                ),
                rotationStabilizer
        );
        for (int sample = 1; sample <= 24; sample++) {
            float yaw = (float) Math.toRadians(sample * 15.0);
            float pitch = (float) Math.toRadians(-35.0 + sample * 2.5);
            SunShadowFrame rotated = SunShadowFrame.plan(
                    environment,
                    budget,
                    frame(
                            DisplayOutputMode.SDR,
                            camera.x(),
                            96.0,
                            -30_000_000.0,
                            yaw,
                            pitch,
                            2_001L + sample,
                            Set.of(),
                            2L,
                            3L
                    ),
                    rotationStabilizer
            );
            for (int cascade = 0; cascade < rotated.cascadeCount(); cascade++) {
                TexelPosition expected = shadowWorldRelativeTexelPosition(
                        reference, cascade, receiverRelative
                );
                TexelPosition actual = shadowWorldRelativeTexelPosition(
                        rotated, cascade, receiverRelative
                );
                require(expected.distance(actual) < 0.002f,
                        "camera rotation moved the world-aligned cascade by "
                                + expected.distance(actual) + " texels");
            }
        }

        testTranslationPhaseResetLifecycle(environment, budget);
    }

    private static void testTranslationPhaseResetLifecycle(
            final EnvironmentDescriptor environment,
            final SunShadowLayout.Budget budget
    ) {
        double cameraX = 29_999_900.0;
        SunShadowStabilizer stabilizer = new SunShadowStabilizer();
        SunShadowFrame.plan(
                environment,
                budget,
                frame(
                        DisplayOutputMode.SDR, cameraX, 96.0, -30_000_000.0,
                        0.0f, 0.0f, 3_000L, Set.of(), 2L, 3L
                ),
                stabilizer
        );
        FrameState movedState = frame(
                DisplayOutputMode.SDR, cameraX + 0.03125, 96.0, -30_000_000.0,
                0.0f, 0.0f, 3_001L, Set.of(), 2L, 3L
        );
        SunShadowFrame moved = SunShadowFrame.plan(
                environment, budget, movedState, stabilizer
        );
        SunShadowFrame centered = SunShadowFrame.plan(environment, budget, movedState);
        require(shadowWorldRelativeTexelPosition(
                        moved, 0, new Vector3f(3.25f, 1.75f, -8.0f)
                ).distance(shadowWorldRelativeTexelPosition(
                        centered, 0, new Vector3f(3.25f, 1.75f, -8.0f)
                )) > 0.01f,
                "walking did not establish a non-zero translation phase");

        FrameState fovEvent = frame(
                DisplayOutputMode.SDR, cameraX + 0.03125, 96.0, -30_000_000.0,
                0.0f, 0.0f, 3_002L,
                Set.of(FrameState.HistoryResetReason.FOV_PROJECTION_CHANGE), 2L, 3L
        );
        SunShadowFrame preserved = SunShadowFrame.plan(
                environment, budget, fovEvent, stabilizer
        );
        for (int cascade = 0; cascade < moved.cascadeCount(); cascade++) {
            TexelPosition before = shadowWorldRelativeTexelPosition(
                    moved, cascade, new Vector3f(3.25f, 1.75f, -8.0f)
            );
            TexelPosition after = shadowWorldRelativeTexelPosition(
                    preserved, cascade, new Vector3f(3.25f, 1.75f, -8.0f)
            );
            require(before.distance(after) < 0.002f,
                    "FOV reset marker discarded the walking shadow phase");
        }
        SunShadowFrame duplicate = SunShadowFrame.plan(
                environment, budget, fovEvent, stabilizer
        );
        require(duplicate.descriptorHash() == preserved.descriptorHash(),
                "planning one submit twice advanced its translation phase twice");
        expectIllegalArgument(() -> SunShadowFrame.plan(
                environment,
                budget,
                frame(
                        DisplayOutputMode.SDR, cameraX + 1.0, 96.0, -30_000_000.0,
                        0.0f, 0.0f, 3_002L, Set.of(), 2L, 3L
                ),
                stabilizer
        ));

        FrameState teleportedState = frame(
                DisplayOutputMode.SDR, cameraX + 128.0, 96.0, -30_000_128.0,
                0.0f, 0.0f, 3_003L,
                Set.of(FrameState.HistoryResetReason.TELEPORT), 2L, 3L
        );
        SunShadowFrame teleported = SunShadowFrame.plan(
                environment, budget, teleportedState, stabilizer
        );
        SunShadowFrame teleportCentered = SunShadowFrame.plan(
                environment, budget, teleportedState
        );
        require(teleported.descriptorHash() == teleportCentered.descriptorHash(),
                "teleport inherited a stale walking shadow phase");

        FrameState afterTeleport = frame(
                DisplayOutputMode.SDR, cameraX + 128.03125, 96.0, -30_000_128.0,
                0.0f, 0.0f, 3_004L, Set.of(), 2L, 3L
        );
        SunShadowFrame.plan(environment, budget, afterTeleport, stabilizer);
        FrameState changedWorld = frame(
                DisplayOutputMode.SDR, cameraX + 128.03125, 96.0, -30_000_128.0,
                0.0f, 0.0f, 3_005L, Set.of(), 99L, 3L
        );
        SunShadowFrame worldReset = SunShadowFrame.plan(
                environment, budget, changedWorld, stabilizer
        );
        require(worldReset.descriptorHash()
                        == SunShadowFrame.plan(environment, budget, changedWorld).descriptorHash(),
                "world identity change inherited a stale walking shadow phase");
    }

    private static void testRotatedCascadeCoverageAndCasterExtrusion() {
        EnvironmentDescriptor environment = shadowEnvironment();
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        FrameState unrotatedState = frame(DisplayOutputMode.SDR, 29_999_900.0, 0.0f, 0.0f);
        float yaw = (float) Math.toRadians(81.0);
        float pitch = (float) Math.toRadians(-27.0);
        FrameState rotatedState = frame(DisplayOutputMode.SDR, 29_999_900.0, yaw, pitch);
        SunShadowFrame unrotated = SunShadowFrame.plan(environment, budget, unrotatedState);
        SunShadowFrame rotated = SunShadowFrame.plan(environment, budget, rotatedState);
        Matrix4f viewToWorld = new Matrix4f().rotateY(yaw).rotateX(pitch);
        float tangentY = (float) Math.tan(Math.toRadians(70.0) * 0.5);
        float tangentX = tangentY * (16.0f / 9.0f);
        float previousSplit = 0.05f;

        for (int cascade = 0; cascade < budget.cascadeCount(); cascade++) {
            Matrix4f unrotatedWorld = unrotated.shadowFromWorldRelative(cascade);
            Matrix4f rotatedWorld = rotated.shadowFromWorldRelative(cascade);
            require(close(rowLengthX(unrotatedWorld), rowLengthX(rotatedWorld), 1.0e-5f)
                            && close(rowLengthY(unrotatedWorld), rowLengthY(rotatedWorld), 1.0e-5f),
                    "camera rotation changed a stabilized cascade scale");

            float split = rotated.cascadeSplit(cascade);
            for (float depth : new float[]{previousSplit, split}) {
                for (int y = -1; y <= 1; y += 2) {
                    for (int x = -1; x <= 1; x += 2) {
                        Vector4f clip = rotated.shadowFromView(cascade).transform(
                                new Vector4f(
                                        x * depth * tangentX,
                                        y * depth * tangentY,
                                        -depth,
                                        1.0f
                                )
                        );
                        require(validClip(clip, 0.01f),
                                "rotated receiver frustum escaped its cascade");
                    }
                }
            }

            float centerDepth = (previousSplit + split) * 0.5f;
            Vector3f receiverView = new Vector3f(0.0f, 0.0f, -centerDepth);
            Vector3f casterView = new Vector3f(rotated.toLightView())
                    .mul(SunShadowFrame.casterExtrusion(budget) * 0.95f)
                    .add(receiverView);
            Vector4f casterClip = rotated.shadowFromView(cascade).transform(
                    new Vector4f(casterView, 1.0f)
            );
            require(validClip(casterClip, 0.01f),
                    "near cascade clipped a caster inside the declared shadow range: "
                            + casterClip);

            Vector3f casterWorldRelative = viewToWorld.transformDirection(casterView);
            FrameState.CameraPosition camera = rotated.cameraPosition();
            double casterX = camera.x() + casterWorldRelative.x;
            double casterY = camera.y() + casterWorldRelative.y;
            double casterZ = camera.z() + casterWorldRelative.z;
            require(SunShadowClipVolume.intersectsAabb(
                            rotated.shadowFromWorldRelative(cascade),
                            camera,
                            casterX - 0.5,
                            casterY - 0.5,
                            casterZ - 0.5,
                            casterX + 0.5,
                            casterY + 0.5,
                            casterZ + 0.5
                    ),
                    "light-frustum terrain collection rejected a valid caster");
            previousSplit = split;
        }
    }

    private static void testOuterCascadeFadeAndClosedCaveFallback() {
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        float[] splits = SunShadowLayout.cascadeSplits(budget, 0.05f, 1_024.0f);
        int last = budget.cascadeCount() - 1;
        float previous = splits[last - 1];
        float split = splits[last];
        float width = (split - previous) * budget.blendFraction();
        float shadowed = 0.2f;
        float start = outerVisibility(shadowed, split - width, previous, split,
                budget.blendFraction());
        float middle = outerVisibility(shadowed, split - width * 0.5f, previous, split,
                budget.blendFraction());
        float edge = outerVisibility(shadowed, split, previous, split,
                budget.blendFraction());
        require(close(start, shadowed) && middle > shadowed && middle < 1.0f
                        && close(edge, 1.0f),
                "outer cascade does not fade continuously to its bounded fallback");
        require(close(0.0f * 1.0f * edge, 0.0f),
                "closed-cave sky visibility did not suppress directional sunlight");
    }

    private static void testCascadeBlendCoverageAndScaleAwareBias() {
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        float yaw = (float) Math.toRadians(57.0);
        float pitch = (float) Math.toRadians(-21.0);
        SunShadowFrame frame = SunShadowFrame.plan(
                shadowEnvironment(),
                budget,
                frame(DisplayOutputMode.SDR, 29_999_900.0, yaw, pitch)
        );
        float tangentY = (float) Math.tan(Math.toRadians(70.0) * 0.5);
        float tangentX = tangentY * (16.0f / 9.0f);

        require(frame.reverseZRasterDepthBias() < 0.0f
                        && frame.reverseZRasterSlopeBias() < 0.0f,
                "reverse-Z shadow pass retained forward-Z raster bias signs");

        float previousWorldTexel = 0.0f;
        for (int cascade = 0; cascade < frame.cascadeCount(); cascade++) {
            float worldTexel = frame.cascadeWorldUnitsPerTexel(cascade);
            float normalBias = frame.cascadeReceiverNormalBias(cascade);
            require(Float.isFinite(worldTexel) && worldTexel > previousWorldTexel,
                    "cascade texel footprint did not grow with receiver distance");
            require(close(normalBias, Math.max(
                            budget.receiverNormalBias(),
                            worldTexel * budget.receiverNormalBiasTexels()
                    )),
                    "receiver normal bias is not tied to the active cascade footprint");
            previousWorldTexel = worldTexel;

            if (cascade == 0) {
                require(close(frame.cascadeNearDepth(cascade), 0.05f),
                        "first cascade no longer starts at the camera near plane");
                continue;
            }

            float previousSplit = frame.cascadeSplit(cascade - 1);
            float transitionPrevious = cascade == 1
                    ? 0.0f
                    : frame.cascadeSplit(cascade - 2);
            float blendStart = Math.max(
                    0.05f,
                    SunShadowLayout.cascadeBlendStart(
                            budget,
                            transitionPrevious,
                            previousSplit
                    )
            );
            require(close(frame.cascadeNearDepth(cascade), blendStart),
                    "next cascade does not include the full receiver blend interval");

            for (float depth : new float[]{
                    blendStart,
                    (blendStart + previousSplit) * 0.5f,
                    previousSplit
            }) {
                for (int y = -1; y <= 1; y += 2) {
                    for (int x = -1; x <= 1; x += 2) {
                        Vector3f receiver = new Vector3f(
                                x * depth * tangentX,
                                y * depth * tangentY,
                                -depth
                        );
                        Vector3f[] normalOffsets = {
                                new Vector3f(),
                                new Vector3f(normalBias, 0.0f, 0.0f),
                                new Vector3f(-normalBias, 0.0f, 0.0f),
                                new Vector3f(0.0f, normalBias, 0.0f),
                                new Vector3f(0.0f, -normalBias, 0.0f),
                                new Vector3f(0.0f, 0.0f, normalBias),
                                new Vector3f(0.0f, 0.0f, -normalBias)
                        };
                        for (Vector3f normalOffset : normalOffsets) {
                            Vector4f clip = frame.shadowFromView(cascade).transform(
                                    new Vector4f(
                                            new Vector3f(receiver).add(normalOffset),
                                            1.0f
                                    )
                            );
                            require(validClip(clip, 0.01f),
                                    "biased cascade blend sampled outside the next receiver volume");
                        }
                    }
                }
            }
        }
    }

    private static void testBindingAbi() {
        require(EnvironmentShadowBindingAbi.VERSION == SunShadowLayout.ABI_VERSION
                        && EnvironmentShadowBindingAbi.PARAMS_BYTES == 384
                        && EnvironmentShadowBindingAbi.PARAMS_SLOT == 26,
                "environment parameter ABI changed");
        require(java.util.Arrays.equals(
                        EnvironmentShadowBindingAbi.shadowTextureSlots(),
                        new int[]{13, 14, 15}
                ),
                "shadow texture ABI changed");
        require(EnvironmentShadowBindingAbi.MATRIX_0_OFFSET == 0
                        && EnvironmentShadowBindingAbi.MATRIX_2_OFFSET == 128
                        && EnvironmentShadowBindingAbi.CONTRACT_OFFSET == 304
                        && EnvironmentShadowBindingAbi.WORLD_UP_AND_MEDIUM_OFFSET == 320
                        && EnvironmentShadowBindingAbi.CASCADE_NORMAL_BIAS_OFFSET == 336,
                "environment packet offsets changed");
    }

    private static void testSodiumCascadeUniformLifecycle() {
        SodiumShadowUniformState state = new SodiumShadowUniformState();
        require(!state.transition(0L), "ordinary terrain invalidated an unused uniform cache");
        require(state.transition(41L), "first shadow cascade did not request an upload");
        require(!state.transition(41L), "CUTOUT duplicated the SOLID cascade upload");
        require(state.transition(42L), "next shadow cascade reused stale matrices");
        require(!state.transition(42L), "second layer duplicated the cascade upload");
        require(state.transition(0L), "main terrain did not restore its projection after shadows");
        require(!state.transition(0L), "main CUTOUT duplicated the restored terrain upload");
    }

    private static FrameState frame(final DisplayOutputMode output, final double cameraX) {
        return frame(output, cameraX, 0.0f, 0.0f);
    }

    private static FrameState frame(
            final DisplayOutputMode output,
            final double cameraX,
            final float yaw,
            final float pitch
    ) {
        return frame(output, cameraX, 96.0, -30_000_000.0, yaw, pitch);
    }

    private static FrameState frame(
            final DisplayOutputMode output,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final float yaw,
            final float pitch
    ) {
        return frame(
                output,
                cameraX,
                cameraY,
                cameraZ,
                yaw,
                pitch,
                7L,
                Set.of(),
                2L,
                3L
        );
    }

    private static FrameState frame(
            final DisplayOutputMode output,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final float yaw,
            final float pitch,
            final long sequence,
            final Set<FrameState.HistoryResetReason> resetReasons,
            final long worldIdentity,
            final long dimensionIdentity
    ) {
        Matrix4f cameraJoml = new Matrix4f().rotateY(yaw).rotateX(pitch);
        Matrix4 camera = Matrix4.ofJoml(cameraJoml);
        Matrix4 view = Matrix4.ofJoml(new Matrix4f(cameraJoml).invert());
        Matrix4 projection = Matrix4.ofJoml(new Matrix4f().setPerspective(
                (float) Math.toRadians(70.0),
                16.0f / 9.0f,
                0.05f,
                1_024.0f,
                true
        ));
        FrameState.Transforms transforms = new FrameState.Transforms(
                camera, view, projection, camera, view, projection
        );
        double headroom = output == DisplayOutputMode.HDR ? 2.0 : 1.0;
        return new FrameState(
                FrameContract.temporalPreparationV1(),
                sequence, 4L, 0L, 1L, 9L, 1L,
                RenderContractMode.METALLUM, LightingModel.ADVANCED, output,
                LightingPreset.BALANCED, RendererFeatureMask.NONE, MetalExecutorKind.METAL3,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION,
                FrameState.ResourceBytes.NONE, FrameState.AdvancedLightingWork.NONE,
                transforms, transforms,
                new FrameState.Extent(1_920, 1_080), new FrameState.Extent(1_920, 1_080),
                1.0, 1.0, FrameState.JitterOffset.ZERO, resetReasons,
                sequence, 1, 1.0 / 60.0, 0.05, 1_024.0,
                new FrameState.CameraPosition(cameraX, cameraY, cameraZ),
                new FrameState.CameraPosition(cameraX, cameraY, cameraZ),
                worldIdentity, dimensionIdentity, headroom, headroom
        );
    }

    private static EnvironmentDescriptor shadowEnvironment() {
        return shadowEnvironment(0.35f);
    }

    private static EnvironmentDescriptor shadowEnvironment(final float sunAngle) {
        return EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                sunAngle,
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                10.0f / 255.0f,
                10.0f / 255.0f,
                10.0f / 255.0f,
                0.0f,
                0.0f,
                1.0f
        );
    }

    private static Vector3f shadowAxisU(final Matrix4f matrix) {
        return new Vector3f(matrix.m00(), matrix.m10(), matrix.m20()).normalize();
    }

    private static Vector3f shadowAxisV(final Matrix4f matrix) {
        return new Vector3f(matrix.m01(), matrix.m11(), matrix.m21()).normalize();
    }

    private static TexelPosition shadowTexelPosition(
            final SunShadowFrame frame,
            final int cascade,
            final Vector3f viewPosition
    ) {
        Vector4f clip = frame.shadowFromView(cascade).transform(
                new Vector4f(viewPosition, 1.0f)
        );
        float scale = frame.budget().resolution() * 0.5f / clip.w;
        return new TexelPosition(
                (clip.x + clip.w) * scale,
                (clip.y + clip.w) * scale
        );
    }

    private static TexelPosition shadowWorldRelativeTexelPosition(
            final SunShadowFrame frame,
            final int cascade,
            final Vector3f worldRelativePosition
    ) {
        Vector4f clip = frame.shadowFromWorldRelative(cascade).transform(
                new Vector4f(worldRelativePosition, 1.0f)
        );
        float scale = frame.budget().resolution() * 0.5f / clip.w;
        return new TexelPosition(
                (clip.x + clip.w) * scale,
                (clip.y + clip.w) * scale
        );
    }

    private static TexelPosition shadowTexelPosition(
            final SunShadowFrame frame,
            final int cascade,
            final double worldX,
            final double worldY,
            final double worldZ
    ) {
        FrameState.CameraPosition camera = frame.cameraPosition();
        return shadowTexelPosition(
                frame,
                cascade,
                new Vector3f(
                        (float) (worldX - camera.x()),
                        (float) (worldY - camera.y()),
                        (float) (worldZ - camera.z())
                )
        );
    }

    private static float rowLengthX(final Matrix4f matrix) {
        return (float) Math.sqrt(matrix.m00() * matrix.m00()
                + matrix.m10() * matrix.m10() + matrix.m20() * matrix.m20());
    }

    private static float rowLengthY(final Matrix4f matrix) {
        return (float) Math.sqrt(matrix.m01() * matrix.m01()
                + matrix.m11() * matrix.m11() + matrix.m21() * matrix.m21());
    }

    private static boolean validClip(final Vector4f clip, final float epsilon) {
        if (!clip.isFinite() || Math.abs(clip.w) < 1.0e-6f) {
            return false;
        }
        float x = clip.x / clip.w;
        float y = clip.y / clip.w;
        float z = clip.z / clip.w;
        return x >= -1.0f - epsilon && x <= 1.0f + epsilon
                && y >= -1.0f - epsilon && y <= 1.0f + epsilon
                && z >= -epsilon && z <= 1.0f + epsilon;
    }

    private static float outerVisibility(
            final float visibility,
            final float viewDepth,
            final float previous,
            final float split,
            final float blendFraction
    ) {
        float width = Math.max((split - previous) * blendFraction, 0.0001f);
        float blend = smoothstep(split - width, split, viewDepth);
        return visibility + (1.0f - visibility) * blend;
    }

    private static float smoothstep(final float edge0, final float edge1, final float value) {
        float normalized = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return normalized * normalized * (3.0f - 2.0f * normalized);
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static boolean close(final float left, final float right) {
        return Math.abs(left - right) <= 1.0e-6f;
    }

    private static boolean close(final float left, final float right, final float tolerance) {
        return Math.abs(left - right) <= tolerance;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record TexelPosition(float x, float y) {
        float distance(final TexelPosition other) {
            return (float) Math.hypot(this.x - other.x, this.y - other.y);
        }
    }
}
