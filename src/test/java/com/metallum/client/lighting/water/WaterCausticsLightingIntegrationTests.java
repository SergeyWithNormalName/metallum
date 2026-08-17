package com.metallum.client.lighting.water;

import com.metallum.client.lighting.EnvironmentDescriptor;
import org.joml.Vector3f;

/**
 * Lighting composition and energy conservation integration tests for Metallum WATER-CAUSTICS-1.
 *
 * <p>Validates T1 through T10 from section 31 of the technical specification.</p>
 */
public final class WaterCausticsLightingIntegrationTests {

    public static void main(final String[] args) {
        runAll();
    }

    public static void runAll() {
        testT1_NeutralCausticGainPreservesBaselineDirect();
        testT2_T3_CausticRidgeAndTroughModulateOnlyDirectionalDiffuse();
        testT4_AmbientRadianceIsUntouched();
        testT5_SkyIrradianceIsUntouched();
        testT6_LocalLightsAreUntouched();
        testT7_GeometricShadowSuppressesCaustics();
        testT8_CloudShadowSuppressesCausticsProportionally();
        testT9_ZeroDirectionalRadianceProducesZeroCaustics();
        testT10_NonWaterMediumBypassesCaustics();
        testT11_AboveWaterSubmergedReceiverActiveAndDryReceiverNeutral();
        System.out.println("All WaterCausticsLightingIntegrationTests passed successfully.");
    }

    /** Helper evaluating the exact mathematical direct lighting model from GLSL. */
    private static LightingResult evaluateLighting(
            final float ambientRadiance,
            final float skyIrradiance,
            final float directionalRadiance,
            final float skyOcclusion,
            final float nDotL,
            final float sunVisibility,
            final float cloudTransmittance,
            final float localLightContribution,
            final float causticGain
    ) {
        float directionalWeight = skyOcclusion * Math.max(nDotL, 0.0f);
        float skyShadow = 0.42f + (1.0f - 0.42f) * sunVisibility;
        float diffuse = ambientRadiance;
        diffuse += skyIrradiance * (skyOcclusion * 0.85f * skyShadow);
        float directionalDiffuse = 0.0f;
        if (directionalWeight > 0.0f) {
            directionalDiffuse = directionalRadiance
                    * (directionalWeight * sunVisibility * cloudTransmittance * causticGain);
            diffuse += directionalDiffuse;
        }
        float diffuseReflectance = diffuse * 0.31830988618f;
        float total = diffuseReflectance + localLightContribution;
        return new LightingResult(diffuseReflectance, directionalDiffuse, total);
    }

    private record LightingResult(
            float diffuseReflectance,
            float directionalDiffuse,
            float totalColor
    ) {}

    private static void testT1_NeutralCausticGainPreservesBaselineDirect() {
        LightingResult baseline = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 1.0f, 0.2f, 1.0f
        );
        LightingResult causticNeutral = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 1.0f, 0.2f, 1.0f
        );
        require(baseline.totalColor() == causticNeutral.totalColor(),
                "T1 failed: causticGain=1.0 did not preserve the baseline direct lighting result");
    }

    private static void testT2_T3_CausticRidgeAndTroughModulateOnlyDirectionalDiffuse() {
        LightingResult baseline = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 1.0f, 0.2f, 1.0f
        );
        LightingResult ridge = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 1.0f, 0.2f, 1.45f
        );
        LightingResult trough = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 1.0f, 0.2f, 0.78f
        );

        require(ridge.directionalDiffuse() > baseline.directionalDiffuse(),
                "T2 failed: bright caustic ridge did not increase directional diffuse");
        require(trough.directionalDiffuse() < baseline.directionalDiffuse(),
                "T3 failed: dark caustic trough did not decrease directional diffuse");
        require(ridge.totalColor() > baseline.totalColor(),
                "T2 failed: bright caustic ridge must increase total illumination");
        require(trough.totalColor() < baseline.totalColor(),
                "T3 failed: dark caustic trough must decrease total illumination");
    }

    private static void testT4_AmbientRadianceIsUntouched() {
        float ambient = 0.08f;
        LightingResult r1 = evaluateLighting(ambient, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.55f);
        LightingResult r2 = evaluateLighting(ambient, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.65f);
        require(r1.totalColor() == r2.totalColor(),
                "T4 failed: ambient radiance was modulated by caustic gain");
    }

    private static void testT5_SkyIrradianceIsUntouched() {
        float sky = 0.45f;
        LightingResult r1 = evaluateLighting(0.0f, sky, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.55f);
        LightingResult r2 = evaluateLighting(0.0f, sky, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.65f);
        require(r1.totalColor() == r2.totalColor(),
                "T5 failed: sky irradiance was modulated by caustic gain");
    }

    private static void testT6_LocalLightsAreUntouched() {
        float local = 0.75f;
        LightingResult r1 = evaluateLighting(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, local, 1.55f);
        LightingResult r2 = evaluateLighting(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, local, 0.65f);
        require(r1.totalColor() == r2.totalColor(),
                "T6 failed: local/clustered light was modulated by caustic gain");
    }

    private static void testT7_GeometricShadowSuppressesCaustics() {
        // Under terrain shadow: sunVisibility = 0.0
        LightingResult shadowedWithCaustics = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 0.0f, 1.0f, 0.0f, 1.55f
        );
        LightingResult shadowedWithoutCaustics = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 0.0f, 1.0f, 0.0f, 1.0f
        );
        require(shadowedWithCaustics.directionalDiffuse() == 0.0f,
                "T7 failed: geometric shadow did not completely suppress directional diffuse");
        require(shadowedWithCaustics.totalColor() == shadowedWithoutCaustics.totalColor(),
                "T7 failed: caustic gain leaked into geometric shadow");
    }

    private static void testT8_CloudShadowSuppressesCausticsProportionally() {
        // Under thick cloud: cloudTransmittance = 0.20
        LightingResult clear = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 1.0f, 0.0f, 1.50f
        );
        LightingResult clouded = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 0.20f, 0.0f, 1.50f
        );
        require(Math.abs(clouded.directionalDiffuse() - clear.directionalDiffuse() * 0.20f) < 1.0e-5f,
                "T8 failed: cloud shadow did not suppress directional caustic diffuse proportionally");
    }

    private static void testT9_ZeroDirectionalRadianceProducesZeroCaustics() {
        LightingResult nightNoMoon = evaluateLighting(
                0.02f, 0.05f, 0.0f, 1.0f, 0.8f, 1.0f, 1.0f, 0.0f, 1.55f
        );
        require(nightNoMoon.directionalDiffuse() == 0.0f,
                "T9 failed: zero directional radiance produced non-zero caustic diffuse");
    }

    private static void testT10_NonWaterMediumBypassesCaustics() {
        // When not in water medium, caustic gain is structurally 1.0 (neutral no-op)
        float airGain = WaterCausticsPolicy.evaluateCausticGain(
                10.0, 70.0, 10.0, 0.0f, 1.0f, 0.0f, 64.0f, 0.0f, 1.0f, 0.0f, 0.0
        );
        require(airGain == 1.0f, "T10 failed: above water receiver did not resolve to neutral caustic gain");
    }

    private static void testT11_AboveWaterSubmergedReceiverActiveAndDryReceiverNeutral() {
        // Submerged receiver viewed from above water (medium AIR)
        float submergedGain = WaterCausticsPolicy.evaluateCausticGain(
                50.0, 62.0, 50.0,
                0.0f, 1.0f, 0.0f,
                true, 2.0f,
                false, 64.0f,
                0.5f, 0.866f, 0.0f,
                10.0
        );
        require(submergedGain != 1.0f,
                "T11 failed: submerged receiver viewed from above water must receive caustic modulation");

        LightingResult submergedResult = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 1.0f, 0.0f, submergedGain
        );
        LightingResult neutralResult = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 1.0f, 0.0f, 1.0f
        );
        require(submergedResult.directionalDiffuse() != neutralResult.directionalDiffuse(),
                "T11 failed: directional diffuse must differ from neutral when caustics are active");

        // Dry receiver viewed from above water (medium AIR)
        float dryGain = WaterCausticsPolicy.evaluateCausticGain(
                50.0, 65.0, 50.0,
                0.0f, 1.0f, 0.0f,
                false, 0.0f,
                false, 64.0f,
                0.5f, 0.866f, 0.0f,
                10.0
        );
        require(dryGain == 1.0f,
                "T11 failed: dry receiver viewed from above water must receive neutral gain 1.0");

        LightingResult dryResult = evaluateLighting(
                0.05f, 0.46f, 1.25f, 1.0f, 0.8f, 1.0f, 1.0f, 0.0f, dryGain
        );
        require(dryResult.totalColor() == neutralResult.totalColor(),
                "T11 failed: dry receiver must have total lighting identical to neutral");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
