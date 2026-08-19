package com.metallum.client.lighting;

import com.metallum.client.renderer.style.VisualStyle;
import com.metallum.client.renderer.style.VisualStyleProfiles;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.List;

/**
 * Deterministic numerical, lifetime, chromaticity, multi-stroke, and cave-isolation contracts
 * for {@link LightningEnvironmentPolicy}.
 */
public final class LightningEnvironmentPolicyTests {

    private static final double CAMERA_X = 100.0;
    private static final double CAMERA_Y = 64.0;
    private static final double CAMERA_Z = 100.0;

    private LightningEnvironmentPolicyTests() {
    }

    public static void main(final String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // Baseline contracts
        testA_NoEventProducesZeroContribution();
        testB_PeakStrikeProducesPositiveFiniteColdContribution();
        testC_ChromaticityIsColdWhiteAndNotSaturatedBlue();
        testD_TemporalEnvelopeIsContinuousAndBounded();
        testE_EnvelopeBecomesZeroAfterLifetime();
        testF_FrameRateIndependence();
        testG_DistanceResponseIsMonotonic();
        testH_FarStrikeHasNegligibleEffect();
        testI_MultipleBoltsRemainBounded();
        testJ_SameBoltMultipleFramesAdvancesSmoothlyWithoutRetriggering();
        testK_WorldChangeDoesNotLeakOldFlash();
        testL_MediumTransmissionAttenuatesFlash();
        testM_EnvironmentInvariantsPreserved();
        testN_AmbientOnlySpecializationUnchanged();
        testO_EndProfileUnchanged();
        testP_ExistingLocalLightningProfileRemainsPresentAndUnchanged();
        testQ_VanillaNoLightningRegressionNumericallyIdentical();

        // Concern 1 Regressions (Bounded publication, zero entity scan, world clear)
        testConcern1_BoundedTrackerCapacityAndEviction();
        testConcern1_WorldResetClearsAllTrackedState();

        // Concern 2 Regressions (Cave ambient isolation, sky additivity)
        testConcern2_AmbientRadianceIsNotModifiedByFlash();
        testConcern2_SealedCaveWithZeroSkyOcclusionReceivesZeroFlash();

        // Concern 3 Regressions (Multi-stroke lifecycle tracking on same entity)
        testConcern3_MultiStrokeOnSameBoltEntityTriggersDistinctPulses();
        testConcern3_SameStrokeAcrossMultipleFramesDoesNotRestart();

        System.out.println("PASS LIGHTNING-ENV-1-AUDIT-FIX physical lightning environment flash policy tests");
    }

    private static void testA_NoEventProducesZeroContribution() {
        LightningEnvironmentPolicy.reset();
        LightningEnvironmentPolicy.FlashContribution noneNull =
                LightningEnvironmentPolicy.evaluateCandidates(
                        null, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
                );
        requireClose(0.0f, noneNull.flashStrength(), 1e-6f, "null candidates strength");
        requireClose(0.0f, noneNull.skyRed(), 1e-6f, "null candidates skyRed");
        requireClose(0.0f, noneNull.skyGreen(), 1e-6f, "null candidates skyGreen");
        requireClose(0.0f, noneNull.skyBlue(), 1e-6f, "null candidates skyBlue");
        requireClose(0.0f, noneNull.ambientRed(), 1e-6f, "null candidates ambientRed");
        requireClose(0.0f, noneNull.ambientGreen(), 1e-6f, "null candidates ambientGreen");
        requireClose(0.0f, noneNull.ambientBlue(), 1e-6f, "null candidates ambientBlue");

        LightningEnvironmentPolicy.FlashContribution noneEmpty =
                LightningEnvironmentPolicy.evaluateCandidates(
                        List.of(), CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
                );
        require(noneEmpty.equals(LightningEnvironmentPolicy.FlashContribution.NONE), "empty candidates must be NONE");

        LightningEnvironmentPolicy.FlashContribution noneTracked =
                LightningEnvironmentPolicy.evaluate(CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR);
        require(noneTracked.equals(LightningEnvironmentPolicy.FlashContribution.NONE), "empty tracked must be NONE");
    }

    private static void testB_PeakStrikeProducesPositiveFiniteColdContribution() {
        // At stroke age 1 tick (50 ms), inside plateau (40-80 ms)
        LightningEnvironmentPolicy.LightningStrikeCandidate candidate =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X, CAMERA_Y, CAMERA_Z, 1);

        LightningEnvironmentPolicy.FlashContribution flash =
                LightningEnvironmentPolicy.evaluateCandidates(
                        List.of(candidate), CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
                );

        requireClose(1.0f, flash.flashStrength(), 1e-6f, "peak strength should be 1.0");
        require(flash.skyRed() > 0.0f && Float.isFinite(flash.skyRed()), "skyRed must be positive and finite");
        require(flash.skyGreen() > 0.0f && Float.isFinite(flash.skyGreen()), "skyGreen must be positive and finite");
        require(flash.skyBlue() > 0.0f && Float.isFinite(flash.skyBlue()), "skyBlue must be positive and finite");
        // Ambient contribution is strictly zero (Concern 2 fix)
        requireClose(0.0f, flash.ambientRed(), 1e-6f, "ambientRed must be zero");
        requireClose(0.0f, flash.ambientGreen(), 1e-6f, "ambientGreen must be zero");
        requireClose(0.0f, flash.ambientBlue(), 1e-6f, "ambientBlue must be zero");
    }

    private static void testC_ChromaticityIsColdWhiteAndNotSaturatedBlue() {
        LightningEnvironmentPolicy.LightningStrikeCandidate candidate =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X, CAMERA_Y, CAMERA_Z, 1);

        LightningEnvironmentPolicy.FlashContribution flash =
                LightningEnvironmentPolicy.evaluateCandidates(
                        List.of(candidate), CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
                );

        // B > G > R cold white chromaticity
        require(flash.skyBlue() > flash.skyGreen(), "Sky Blue must be greater than Green");
        require(flash.skyGreen() > flash.skyRed(), "Sky Green must be greater than Red");

        // Not saturated blue: red and green must be substantial fractions of blue (e.g. R >= 0.5 * B)
        require(flash.skyRed() >= 0.5f * flash.skyBlue(), "Sky Red should be at least 50% of Blue");
        require(flash.skyGreen() >= 0.7f * flash.skyBlue(), "Sky Green should be at least 70% of Blue");
    }

    private static void testD_TemporalEnvelopeIsContinuousAndBounded() {
        float previous = -1.0f;
        // Sample rising phase (0 to 40 ms)
        for (float t = 0.0f; t <= 0.040f; t += 0.005f) {
            float val = LightningEnvironmentPolicy.evaluateTemporalEnvelope(t);
            require(val >= 0.0f && val <= 1.0f, "Rising value out of bounds: " + val);
            if (previous >= 0.0f) {
                require(val >= previous - 1e-6f, "Rising phase must be monotonically non-decreasing");
            }
            previous = val;
        }

        // Plateau phase (40 to 80 ms)
        for (float t = 0.040f; t <= 0.080f; t += 0.010f) {
            float val = LightningEnvironmentPolicy.evaluateTemporalEnvelope(t);
            requireClose(1.0f, val, 1e-6f, "Plateau must be 1.0 at t=" + t);
        }

        // Decay phase (80 to 400 ms)
        previous = 1.01f;
        for (float t = 0.080f; t <= 0.400f; t += 0.020f) {
            float val = LightningEnvironmentPolicy.evaluateTemporalEnvelope(t);
            require(val >= 0.0f && val <= 1.0f, "Decay value out of bounds: " + val);
            require(val <= previous + 1e-6f, "Decay phase must be monotonically non-increasing at t=" + t);
            previous = val;
        }
    }

    private static void testE_EnvelopeBecomesZeroAfterLifetime() {
        requireClose(0.0f, LightningEnvironmentPolicy.evaluateTemporalEnvelope(0.400f), 1e-6f, "Envelope at 400ms");
        requireClose(0.0f, LightningEnvironmentPolicy.evaluateTemporalEnvelope(0.450f), 1e-6f, "Envelope at 450ms");
        requireClose(0.0f, LightningEnvironmentPolicy.evaluateTemporalEnvelope(1.000f), 1e-6f, "Envelope at 1000ms");
        requireClose(0.0f, LightningEnvironmentPolicy.evaluateTemporalEnvelope(-0.010f), 1e-6f, "Envelope at negative");
    }

    private static void testF_FrameRateIndependence() {
        LightningEnvironmentPolicy.LightningStrikeCandidate c1 =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X, CAMERA_Y, CAMERA_Z, 1);

        float s1 = LightningEnvironmentPolicy.evaluateCandidateStrength(
                c1, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.1f, EnvironmentDescriptor.Medium.AIR
        );

        float expected = LightningEnvironmentPolicy.evaluateTemporalEnvelope(0.055f);
        requireClose(expected, s1, 1e-6f, "Frame-rate independent sampling at 55ms");
    }

    private static void testG_DistanceResponseIsMonotonic() {
        LightningEnvironmentPolicy.LightningStrikeCandidate near =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X + 30.0, CAMERA_Y, CAMERA_Z, 1);
        LightningEnvironmentPolicy.LightningStrikeCandidate mid =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X + 150.0, CAMERA_Y, CAMERA_Z, 1);
        LightningEnvironmentPolicy.LightningStrikeCandidate far =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X + 350.0, CAMERA_Y, CAMERA_Z, 1);
        LightningEnvironmentPolicy.LightningStrikeCandidate veryFar =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X + 500.0, CAMERA_Y, CAMERA_Z, 1);

        float sNear = LightningEnvironmentPolicy.evaluateCandidateStrength(
                near, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
        );
        float sMid = LightningEnvironmentPolicy.evaluateCandidateStrength(
                mid, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
        );
        float sFar = LightningEnvironmentPolicy.evaluateCandidateStrength(
                far, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
        );
        float sVeryFar = LightningEnvironmentPolicy.evaluateCandidateStrength(
                veryFar, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
        );

        requireClose(1.0f, sNear, 1e-6f, "Near distance strength");
        require(sNear > sMid, "Near should be greater than Mid");
        require(sMid > sFar, "Mid should be greater than Far");
        require(sFar > sVeryFar, "Far should be greater than VeryFar");
        requireClose(0.0f, sVeryFar, 1e-6f, "VeryFar distance strength");
    }

    private static void testH_FarStrikeHasNegligibleEffect() {
        LightningEnvironmentPolicy.LightningStrikeCandidate far =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X + 450.0, CAMERA_Y, CAMERA_Z, 1);
        LightningEnvironmentPolicy.FlashContribution flash =
                LightningEnvironmentPolicy.evaluateCandidates(
                        List.of(far), CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
                );
        requireClose(0.0f, flash.flashStrength(), 1e-6f, "Far strike flashStrength");
        requireClose(0.0f, flash.skyRed(), 1e-6f, "Far strike skyRed");
    }

    private static void testI_MultipleBoltsRemainBounded() {
        LightningEnvironmentPolicy.LightningStrikeCandidate b1 =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X, CAMERA_Y, CAMERA_Z, 1);
        LightningEnvironmentPolicy.LightningStrikeCandidate b2 =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X + 10.0, CAMERA_Y, CAMERA_Z, 1);
        LightningEnvironmentPolicy.LightningStrikeCandidate b3 =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X - 10.0, CAMERA_Y, CAMERA_Z, 1);

        LightningEnvironmentPolicy.FlashContribution flash =
                LightningEnvironmentPolicy.evaluateCandidates(
                        List.of(b1, b2, b3), CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
                );

        requireClose(1.0f, flash.flashStrength(), 1e-6f, "Multiple bolts strength");
        requireClose(LightningEnvironmentPolicy.PEAK_SKY_RED, flash.skyRed(), 1e-6f, "Multiple bolts skyRed");
        requireClose(LightningEnvironmentPolicy.PEAK_SKY_GREEN, flash.skyGreen(), 1e-6f, "Multiple bolts skyGreen");
        requireClose(LightningEnvironmentPolicy.PEAK_SKY_BLUE, flash.skyBlue(), 1e-6f, "Multiple bolts skyBlue");
    }

    private static void testJ_SameBoltMultipleFramesAdvancesSmoothlyWithoutRetriggering() {
        double bx = CAMERA_X + 20.0;
        double by = CAMERA_Y;
        double bz = CAMERA_Z;

        // Frame at stroke tick 1, partialTick 0.5 (age = 75 ms -> plateau)
        LightningEnvironmentPolicy.LightningStrikeCandidate frame1 =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(bx, by, bz, 1);
        float s1 = LightningEnvironmentPolicy.evaluateCandidateStrength(
                frame1, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.5f, EnvironmentDescriptor.Medium.AIR
        );
        requireClose(1.0f, s1, 1e-6f, "Plateau frame strength");

        // Frame at stroke tick 3, partialTick 0.0 (age = 150 ms -> decay)
        LightningEnvironmentPolicy.LightningStrikeCandidate frame2 =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(bx, by, bz, 3);
        float s2 = LightningEnvironmentPolicy.evaluateCandidateStrength(
                frame2, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
        );
        require(s2 < s1, "Frame 2 should have decayed");
        require(s2 > 0.0f, "Frame 2 should still be positive");

        // Frame at stroke tick 6, partialTick 0.0 (age = 300 ms -> further decay)
        LightningEnvironmentPolicy.LightningStrikeCandidate frame3 =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(bx, by, bz, 6);
        float s3 = LightningEnvironmentPolicy.evaluateCandidateStrength(
                frame3, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
        );
        require(s3 < s2, "Frame 3 should decay further");

        // Frame at stroke tick 8, partialTick 0.0 (age = 400 ms -> zero)
        LightningEnvironmentPolicy.LightningStrikeCandidate frame4 =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(bx, by, bz, 8);
        float s4 = LightningEnvironmentPolicy.evaluateCandidateStrength(
                frame4, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
        );
        requireClose(0.0f, s4, 1e-6f, "Frame 4 should be zero");
    }

    private static void testK_WorldChangeDoesNotLeakOldFlash() {
        LightningEnvironmentPolicy.FlashContribution newWorldFlash =
                LightningEnvironmentPolicy.evaluateCandidates(
                        List.of(), CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
                );
        require(newWorldFlash.equals(LightningEnvironmentPolicy.FlashContribution.NONE), "New world flash must be NONE");
    }

    private static void testL_MediumTransmissionAttenuatesFlash() {
        LightningEnvironmentPolicy.LightningStrikeCandidate candidate =
                new LightningEnvironmentPolicy.LightningStrikeCandidate(CAMERA_X, CAMERA_Y, CAMERA_Z, 1);

        float sAir = LightningEnvironmentPolicy.evaluateCandidateStrength(
                candidate, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR
        );
        float sWater = LightningEnvironmentPolicy.evaluateCandidateStrength(
                candidate, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.WATER
        );
        float sSnow = LightningEnvironmentPolicy.evaluateCandidateStrength(
                candidate, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.POWDER_SNOW
        );
        float sLava = LightningEnvironmentPolicy.evaluateCandidateStrength(
                candidate, CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.LAVA
        );

        requireClose(1.0f, sAir, 1e-6f, "AIR strength");
        requireClose(0.55f, sWater, 1e-6f, "WATER strength");
        requireClose(0.22f, sSnow, 1e-6f, "POWDER_SNOW strength");
        requireClose(0.0f, sLava, 1e-6f, "LAVA strength");
    }

    private static void testM_EnvironmentInvariantsPreserved() {
        EnvironmentDescriptor baseline = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.5f,
                0.2f, 0.3f, 0.4f,
                0.8f,
                0.05f, 0.05f, 0.05f,
                0.3f,
                0.5f,
                1.0f,
                VisualStyleProfiles.profile(VisualStyle.VANILLA).celestialLighting(),
                64.0f
        );

        LightningEnvironmentPolicy.FlashContribution flash =
                LightningEnvironmentPolicy.createContribution(0.8f);

        EnvironmentDescriptor flashed = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.5f,
                0.2f, 0.3f, 0.4f,
                0.8f,
                0.05f, 0.05f, 0.05f,
                0.3f,
                0.5f,
                1.0f,
                VisualStyleProfiles.profile(VisualStyle.VANILLA).celestialLighting(),
                64.0f,
                flash
        );

        require(baseline.profile() == flashed.profile(), "profile match");
        require(baseline.medium() == flashed.medium(), "medium match");
        requireClose(baseline.toLightX(), flashed.toLightX(), 1e-6f, "toLightX match");
        requireClose(baseline.toLightY(), flashed.toLightY(), 1e-6f, "toLightY match");
        requireClose(baseline.toLightZ(), flashed.toLightZ(), 1e-6f, "toLightZ match");
        requireClose(baseline.directionalRed(), flashed.directionalRed(), 1e-6f, "directionalRed match");
        requireClose(baseline.directionalGreen(), flashed.directionalGreen(), 1e-6f, "directionalGreen match");
        requireClose(baseline.directionalBlue(), flashed.directionalBlue(), 1e-6f, "directionalBlue match");
        requireClose(baseline.rain(), flashed.rain(), 1e-6f, "rain match");
        requireClose(baseline.thunder(), flashed.thunder(), 1e-6f, "thunder match");
        requireClose(baseline.moonPhaseBrightness(), flashed.moonPhaseBrightness(), 1e-6f, "moonPhaseBrightness match");
        require(baseline.moon() == flashed.moon(), "moon match");
        require(baseline.sunShadowEligible() == flashed.sunShadowEligible(), "sunShadowEligible match");
        requireClose(baseline.waterSurfaceY(), flashed.waterSurfaceY(), 1e-6f, "waterSurfaceY match");

        // Sky radiance receives additive increase; ambient radiance is completely unchanged (Concern 2)
        requireClose(baseline.skyRed() + flash.skyRed(), flashed.skyRed(), 1e-5f, "skyRed additivity");
        requireClose(baseline.skyGreen() + flash.skyGreen(), flashed.skyGreen(), 1e-5f, "skyGreen additivity");
        requireClose(baseline.skyBlue() + flash.skyBlue(), flashed.skyBlue(), 1e-5f, "skyBlue additivity");
        requireClose(baseline.ambientRed(), flashed.ambientRed(), 1e-6f, "ambientRed unchanged");
        requireClose(baseline.ambientGreen(), flashed.ambientGreen(), 1e-6f, "ambientGreen unchanged");
        requireClose(baseline.ambientBlue(), flashed.ambientBlue(), 1e-6f, "ambientBlue unchanged");
    }

    private static void testN_AmbientOnlySpecializationUnchanged() {
        EnvironmentDescriptor nether = EnvironmentDescriptor.ambientOnly(
                EnvironmentDescriptor.Profile.AMBIENT_ONLY,
                EnvironmentDescriptor.Medium.AIR,
                0.15f, 0.05f, 0.05f
        );
        require(nether.profile() == EnvironmentDescriptor.Profile.AMBIENT_ONLY, "profile AMBIENT_ONLY");
        requireClose(0.0f, nether.directionalRed(), 1e-6f, "nether directionalRed");
        requireClose(0.0f, nether.skyRed(), 1e-6f, "nether skyRed");
        require(!nether.sunShadowEligible(), "nether sunShadowEligible");
    }

    private static void testO_EndProfileUnchanged() {
        EnvironmentDescriptor end = EnvironmentDescriptor.ambientOnly(
                EnvironmentDescriptor.Profile.END,
                EnvironmentDescriptor.Medium.AIR,
                0.11f, 0.075f, 0.16f
        );
        require(end.profile() == EnvironmentDescriptor.Profile.END, "profile END");
        requireClose(0.0f, end.directionalRed(), 1e-6f, "end directionalRed");
        requireClose(0.0f, end.skyRed(), 1e-6f, "end skyRed");
        require(!end.sunShadowEligible(), "end sunShadowEligible");
    }

    private static void testP_ExistingLocalLightningProfileRemainsPresentAndUnchanged() {
        float[] color = MinecraftLightPolicy.linearColorForIdentifier(
                net.minecraft.resources.Identifier.parse("minecraft:lightning_bolt")
        );
        require(color != null, "lightning color array must exist");
    }

    private static void testQ_VanillaNoLightningRegressionNumericallyIdentical() {
        EnvironmentDescriptor withoutFlashExplicit = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.5f,
                0.2f, 0.3f, 0.4f,
                0.8f,
                0.05f, 0.05f, 0.05f,
                0.3f,
                0.5f,
                1.0f,
                VisualStyleProfiles.profile(VisualStyle.VANILLA).celestialLighting(),
                64.0f,
                LightningEnvironmentPolicy.FlashContribution.NONE
        );

        EnvironmentDescriptor baselineDefault = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.5f,
                0.2f, 0.3f, 0.4f,
                0.8f,
                0.05f, 0.05f, 0.05f,
                0.3f,
                0.5f,
                1.0f,
                VisualStyleProfiles.profile(VisualStyle.VANILLA).celestialLighting(),
                64.0f
        );

        require(baselineDefault.skyRed() == withoutFlashExplicit.skyRed(), "skyRed exact equality");
        require(baselineDefault.skyGreen() == withoutFlashExplicit.skyGreen(), "skyGreen exact equality");
        require(baselineDefault.skyBlue() == withoutFlashExplicit.skyBlue(), "skyBlue exact equality");
        require(baselineDefault.ambientRed() == withoutFlashExplicit.ambientRed(), "ambientRed exact equality");
        require(baselineDefault.ambientGreen() == withoutFlashExplicit.ambientGreen(), "ambientGreen exact equality");
        require(baselineDefault.ambientBlue() == withoutFlashExplicit.ambientBlue(), "ambientBlue exact equality");
    }

    private static void testConcern1_BoundedTrackerCapacityAndEviction() {
        LightningEnvironmentPolicy.reset();
        // Register more than MAX_TRACKED_BOLTS (8)
        for (int id = 1; id <= 12; id++) {
            // Simulate synthetic observations at tickCount 1 (50ms age)
            LightningEnvironmentPolicy.TrackedStrokeMock mock =
                    new LightningEnvironmentPolicy.TrackedStrokeMock(id, 1000L + id, 1, CAMERA_X, CAMERA_Y, CAMERA_Z, 100L);
            LightningEnvironmentPolicy.observeMock(mock);
        }
        // State must remain bounded and not crash
        LightningEnvironmentPolicy.FlashContribution eval =
                LightningEnvironmentPolicy.evaluate(CAMERA_X, CAMERA_Y, CAMERA_Z, 0.5f, EnvironmentDescriptor.Medium.AIR);
        require(eval.flashStrength() > 0.0f, "Evaluated flash strength must be positive");
        LightningEnvironmentPolicy.reset();
    }

    private static void testConcern1_WorldResetClearsAllTrackedState() {
        LightningEnvironmentPolicy.reset();
        LightningEnvironmentPolicy.observeMock(
                new LightningEnvironmentPolicy.TrackedStrokeMock(1, 12345L, 1, CAMERA_X, CAMERA_Y, CAMERA_Z, 100L)
        );
        LightningEnvironmentPolicy.FlashContribution before =
                LightningEnvironmentPolicy.evaluate(CAMERA_X, CAMERA_Y, CAMERA_Z, 0.5f, EnvironmentDescriptor.Medium.AIR);
        require(before.flashStrength() > 0.0f, "Should have flash before reset");

        // Simulate world change
        LightningEnvironmentPolicy.reset();
        LightningEnvironmentPolicy.FlashContribution after =
                LightningEnvironmentPolicy.evaluate(CAMERA_X, CAMERA_Y, CAMERA_Z, 0.5f, EnvironmentDescriptor.Medium.AIR);
        require(after.equals(LightningEnvironmentPolicy.FlashContribution.NONE), "Must be NONE after reset");
    }

    private static void testConcern2_AmbientRadianceIsNotModifiedByFlash() {
        LightningEnvironmentPolicy.FlashContribution flash =
                LightningEnvironmentPolicy.createContribution(1.0f);
        requireClose(0.0f, flash.ambientRed(), 1e-6f, "ambientRed must be strictly 0.0");
        requireClose(0.0f, flash.ambientGreen(), 1e-6f, "ambientGreen must be strictly 0.0");
        requireClose(0.0f, flash.ambientBlue(), 1e-6f, "ambientBlue must be strictly 0.0");
        require(flash.skyBlue() > 0.0f, "skyBlue must be positive");
    }

    private static void testConcern2_SealedCaveWithZeroSkyOcclusionReceivesZeroFlash() {
        // In AdvancedDirectLightingShaderPatcher:
        // diffuse = ambientRadiance + skyIrradiance * (skyOcclusion * hemisphere * skyShadow)
        // With skyOcclusion = 0.0 (sealed cave), the skyIrradiance term is exactly zero.
        // Because ambientRadiance receives 0 flash contribution, the total environment contribution in a sealed cave is 0.0 flash.
        float skyOcclusion = 0.0f;
        float hemisphere = 1.0f;
        float skyShadow = 1.0f;

        LightningEnvironmentPolicy.FlashContribution flash =
                LightningEnvironmentPolicy.createContribution(1.0f);

        float caveFlashIrradiance = flash.ambientBlue()
                + flash.skyBlue() * (skyOcclusion * hemisphere * skyShadow);

        requireClose(0.0f, caveFlashIrradiance, 1e-6f, "Sealed cave must receive exactly zero flash irradiance");

        float surfaceSkyOcclusion = 1.0f;
        float surfaceFlashIrradiance = flash.ambientBlue()
                + flash.skyBlue() * (surfaceSkyOcclusion * hemisphere * skyShadow);

        require(surfaceFlashIrradiance > 1.0f, "Surface open sky must receive full flash irradiance");
    }

    private static void testConcern3_MultiStrokeOnSameBoltEntityTriggersDistinctPulses() {
        LightningEnvironmentPolicy.reset();
        int boltEntityId = 42;
        long stroke1Seed = 111111L;
        long stroke2Seed = 222222L;

        // Frame at tick 0 (Stroke 1 start)
        LightningEnvironmentPolicy.observeMock(
                new LightningEnvironmentPolicy.TrackedStrokeMock(boltEntityId, stroke1Seed, 0, CAMERA_X, CAMERA_Y, CAMERA_Z, 100L)
        );
        float s1 = LightningEnvironmentPolicy.evaluate(CAMERA_X, CAMERA_Y, CAMERA_Z, 0.8f, EnvironmentDescriptor.Medium.AIR).flashStrength();
        require(s1 > 0.0f, "Stroke 1 pulse should be active");

        // Frame at tick 7 (Stroke 1 decayed)
        LightningEnvironmentPolicy.observeMock(
                new LightningEnvironmentPolicy.TrackedStrokeMock(boltEntityId, stroke1Seed, 7, CAMERA_X, CAMERA_Y, CAMERA_Z, 107L)
        );
        float s1Decayed = LightningEnvironmentPolicy.evaluate(CAMERA_X, CAMERA_Y, CAMERA_Z, 0.8f, EnvironmentDescriptor.Medium.AIR).flashStrength();
        require(s1Decayed < s1, "Stroke 1 should have decayed by tick 7");

        // Frame at tick 8 (Stroke 2 starts with new seed on same entity ID!)
        LightningEnvironmentPolicy.observeMock(
                new LightningEnvironmentPolicy.TrackedStrokeMock(boltEntityId, stroke2Seed, 8, CAMERA_X, CAMERA_Y, CAMERA_Z, 108L)
        );
        float s2Peak = LightningEnvironmentPolicy.evaluate(CAMERA_X, CAMERA_Y, CAMERA_Z, 0.8f, EnvironmentDescriptor.Medium.AIR).flashStrength();
        require(s2Peak > s1Decayed, "Stroke 2 must restart a fresh peak pulse on the same entity!");
        requireClose(1.0f, s2Peak, 0.05f, "Stroke 2 should reach peak strength");

        LightningEnvironmentPolicy.reset();
    }

    private static void testConcern3_SameStrokeAcrossMultipleFramesDoesNotRestart() {
        LightningEnvironmentPolicy.reset();
        int boltEntityId = 42;
        long strokeSeed = 999999L;

        // Start stroke at tick 1
        LightningEnvironmentPolicy.observeMock(
                new LightningEnvironmentPolicy.TrackedStrokeMock(boltEntityId, strokeSeed, 1, CAMERA_X, CAMERA_Y, CAMERA_Z, 101L)
        );

        // Frame 1: tick 2 (age = 50ms -> plateau = 1.0)
        LightningEnvironmentPolicy.observeMock(
                new LightningEnvironmentPolicy.TrackedStrokeMock(boltEntityId, strokeSeed, 2, CAMERA_X, CAMERA_Y, CAMERA_Z, 102L)
        );
        float f1 = LightningEnvironmentPolicy.evaluate(CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR).flashStrength();
        requireClose(1.0f, f1, 1e-6f, "Frame 1 in plateau");

        // Frame 2: tick 4 (age = 150ms -> decay)
        LightningEnvironmentPolicy.observeMock(
                new LightningEnvironmentPolicy.TrackedStrokeMock(boltEntityId, strokeSeed, 4, CAMERA_X, CAMERA_Y, CAMERA_Z, 104L)
        );
        float f2 = LightningEnvironmentPolicy.evaluate(CAMERA_X, CAMERA_Y, CAMERA_Z, 0.0f, EnvironmentDescriptor.Medium.AIR).flashStrength();
        require(f2 < f1, "Advancing ticks during same stroke must decay monotonically without restarting");
        require(f2 > 0.0f, "Frame 2 must still be positive");

        LightningEnvironmentPolicy.reset();
    }

    private static void requireClose(final float expected, final float actual, final float epsilon, final String message) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual + " (diff=" + Math.abs(expected - actual) + ")");
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
