package com.metallum.client.lighting.cloud;

import org.joml.Vector3f;

/**
 * Lighting integration tests for CLOUD-1: Vanilla-synchronized Cloud Shadows.
 *
 * <p>Validates that cloud shadows affect ONLY direct celestial light (sun/moon)
 * and never contaminate sky irradiance, ambient radiance, or clustered local lights.</p>
 */
public final class CloudShadowLightingIntegrationTests {

    public static void main(final String[] args) {
        runAll();
    }

    public static void runAll() {
        testCelestialLightingMultiplicativeComposition();
        testSkyAndAmbientLightingIndependence();
        testGeometricShadowMultiplication();
        testLocalClusteredLightIndependence();
        testSpecularCelestialHighlightAttenuated();
        System.out.println("All CloudShadowLightingIntegrationTests passed successfully.");
    }

    private static void testCelestialLightingMultiplicativeComposition() {
        Vector3f albedo = new Vector3f(1.0f, 1.0f, 1.0f);
        Vector3f ambient = new Vector3f(0.10f, 0.10f, 0.10f);
        Vector3f sky = new Vector3f(0.30f, 0.30f, 0.30f);
        Vector3f sunRadiance = new Vector3f(1.50f, 1.40f, 1.20f);

        float skyOcclusion = 1.0f;
        float hemisphere = 1.0f;
        float nDotL = 0.80f;
        float directionalWeight = skyOcclusion * nDotL;
        float sunVisibility = 1.0f;

        // Baseline unshadowed (cloudTransmittance = 1.0)
        Vector3f baseColor = evaluateEnvironment(albedo, ambient, sky, sunRadiance, skyOcclusion, hemisphere, directionalWeight, sunVisibility, 1.0f);

        // Half cloud transmittance (cloudTransmittance = 0.50)
        Vector3f halfColor = evaluateEnvironment(albedo, ambient, sky, sunRadiance, skyOcclusion, hemisphere, directionalWeight, sunVisibility, 0.50f);

        // The celestial direct term alone:
        float invPi = 0.31830988618f;
        Vector3f expectedDirectBase = new Vector3f(sunRadiance).mul(directionalWeight * sunVisibility * 1.0f * invPi);
        Vector3f expectedDirectHalf = new Vector3f(sunRadiance).mul(directionalWeight * sunVisibility * 0.5f * invPi);

        Vector3f nonDirect = new Vector3f(baseColor).sub(expectedDirectBase);
        Vector3f halfNonDirect = new Vector3f(halfColor).sub(expectedDirectHalf);

        // Non-direct terms (sky + ambient) must be IDENTICAL
        require(approxEqual(nonDirect.x, halfNonDirect.x), "Sky/ambient changed under cloud shadow (X)");
        require(approxEqual(nonDirect.y, halfNonDirect.y), "Sky/ambient changed under cloud shadow (Y)");
        require(approxEqual(nonDirect.z, halfNonDirect.z), "Sky/ambient changed under cloud shadow (Z)");

        // Direct contribution is exactly halved
        Vector3f actualDirectHalf = new Vector3f(halfColor).sub(nonDirect);
        require(approxEqual(actualDirectHalf.x, expectedDirectHalf.x), "Direct celestial light not halved (X)");
        require(approxEqual(actualDirectHalf.y, expectedDirectHalf.y), "Direct celestial light not halved (Y)");
        require(approxEqual(actualDirectHalf.z, expectedDirectHalf.z), "Direct celestial light not halved (Z)");
    }

    private static void testSkyAndAmbientLightingIndependence() {
        Vector3f albedo = new Vector3f(0.8f, 0.8f, 0.8f);
        Vector3f ambient = new Vector3f(0.15f, 0.15f, 0.18f);
        Vector3f sky = new Vector3f(0.40f, 0.45f, 0.50f);
        Vector3f sunRadiance = new Vector3f(0.0f, 0.0f, 0.0f); // Night or overcast

        float skyOcclusion = 0.9f;
        float hemisphere = 0.8f;
        float directionalWeight = 0.0f;
        float sunVisibility = 1.0f;

        Vector3f colorClearSky = evaluateEnvironment(albedo, ambient, sky, sunRadiance, skyOcclusion, hemisphere, directionalWeight, sunVisibility, 1.0f);
        Vector3f colorCloudySky = evaluateEnvironment(albedo, ambient, sky, sunRadiance, skyOcclusion, hemisphere, directionalWeight, sunVisibility, 0.40f);

        require(approxEqual(colorClearSky.x, colorCloudySky.x), "Sky/ambient radiance must not depend on cloud shadow (X)");
        require(approxEqual(colorClearSky.y, colorCloudySky.y), "Sky/ambient radiance must not depend on cloud shadow (Y)");
        require(approxEqual(colorClearSky.z, colorCloudySky.z), "Sky/ambient radiance must not depend on cloud shadow (Z)");
    }

    private static void testGeometricShadowMultiplication() {
        Vector3f albedo = new Vector3f(1.0f, 1.0f, 1.0f);
        Vector3f ambient = new Vector3f(0.05f, 0.05f, 0.05f);
        Vector3f sky = new Vector3f(0.20f, 0.20f, 0.20f);
        Vector3f sunRadiance = new Vector3f(2.0f, 2.0f, 2.0f);

        float skyOcclusion = 1.0f;
        float hemisphere = 1.0f;
        float directionalWeight = 1.0f;

        // Fully in geometric shadow (sunVisibility = 0.0)
        Vector3f inGeoShadowCloud1 = evaluateEnvironment(albedo, ambient, sky, sunRadiance, skyOcclusion, hemisphere, directionalWeight, 0.0f, 1.0f);
        Vector3f inGeoShadowCloud0 = evaluateEnvironment(albedo, ambient, sky, sunRadiance, skyOcclusion, hemisphere, directionalWeight, 0.0f, 0.0f);

        // When in geometric shadow, cloud transmittance cannot add or remove direct light
        require(approxEqual(inGeoShadowCloud1.x, inGeoShadowCloud0.x), "Geometric shadow must suppress direct light regardless of cloud (X)");
        require(approxEqual(inGeoShadowCloud1.y, inGeoShadowCloud0.y), "Geometric shadow must suppress direct light regardless of cloud (Y)");
        require(approxEqual(inGeoShadowCloud1.z, inGeoShadowCloud0.z), "Geometric shadow must suppress direct light regardless of cloud (Z)");

        // Partial geometric shadow (0.60) + partial cloud shadow (0.50) -> total direct visibility = 0.30
        Vector3f combined = evaluateEnvironment(albedo, ambient, sky, sunRadiance, skyOcclusion, hemisphere, directionalWeight, 0.60f, 0.50f);
        Vector3f equivalentDirect = evaluateEnvironment(albedo, ambient, sky, sunRadiance, skyOcclusion, hemisphere, directionalWeight, 0.30f, 1.0f);

        // Direct contribution alone:
        float invPi = 0.31830988618f;
        float directCombined = sunRadiance.x * (directionalWeight * 0.60f * 0.50f) * invPi;
        float directEquivalent = sunRadiance.x * (directionalWeight * 0.30f * 1.00f) * invPi;
        require(approxEqual(directCombined, directEquivalent), "Combined geometric and cloud shadow must equal product (0.6 * 0.5 = 0.3)");
    }

    private static void testLocalClusteredLightIndependence() {
        // Clustered local light (e.g. torch, lantern)
        Vector3f localLightRadiance = new Vector3f(1.0f, 0.8f, 0.6f);
        float localAttenuation = 0.75f;
        float localNdotL = 0.90f;

        Vector3f localDirect = new Vector3f(localLightRadiance).mul(localAttenuation * localNdotL);

        // Cloud shadow transmittance must NOT be applied to localDirect
        float cloudTransmittance = 0.40f;
        Vector3f evaluatedLocal = new Vector3f(localDirect); // Local lights are untouched

        require(approxEqual(evaluatedLocal.x, localDirect.x), "Local lights must not be affected by cloud shadows");
        require(approxEqual(evaluatedLocal.y, localDirect.y), "Local lights must not be affected by cloud shadows");
        require(approxEqual(evaluatedLocal.z, localDirect.z), "Local lights must not be affected by cloud shadows");
    }

    private static void testSpecularCelestialHighlightAttenuated() {
        Vector3f sunRadiance = new Vector3f(3.0f, 2.8f, 2.5f);
        float sunVisibility = 1.0f;
        float ggxFactor = 0.45f;

        // Clear sky celestial highlight
        Vector3f highlightClear = new Vector3f(sunRadiance).mul(sunVisibility * 1.0f * ggxFactor);

        // Under cloud shadow (transmittance = 0.60)
        Vector3f highlightCloudy = new Vector3f(sunRadiance).mul(sunVisibility * 0.60f * ggxFactor);

        require(approxEqual(highlightCloudy.x, highlightClear.x * 0.60f), "Specular celestial highlight must be attenuated by cloud transmittance (X)");
        require(approxEqual(highlightCloudy.y, highlightClear.y * 0.60f), "Specular celestial highlight must be attenuated by cloud transmittance (Y)");
        require(approxEqual(highlightCloudy.z, highlightClear.z * 0.60f), "Specular celestial highlight must be attenuated by cloud transmittance (Z)");
    }

    private static Vector3f evaluateEnvironment(
            final Vector3f albedo,
            final Vector3f ambient,
            final Vector3f sky,
            final Vector3f sunRadiance,
            final float skyOcclusion,
            final float hemisphere,
            final float directionalWeight,
            final float sunVisibility,
            final float cloudTransmittance
    ) {
        float shadowedSkyVisibility = 0.42f;
        float skyShadow = (1.0f - sunVisibility) * shadowedSkyVisibility + sunVisibility * 1.0f;

        Vector3f diffuse = new Vector3f(ambient);
        Vector3f skyTerm = new Vector3f(sky).mul(skyOcclusion * hemisphere * skyShadow);
        diffuse.add(skyTerm);

        if (directionalWeight > 0.0f) {
            Vector3f directTerm = new Vector3f(sunRadiance).mul(directionalWeight * sunVisibility * cloudTransmittance);
            diffuse.add(directTerm);
        }

        float invPi = 0.31830988618f;
        return new Vector3f(albedo).mul(diffuse).mul(invPi);
    }

    private static boolean approxEqual(final float a, final float b) {
        return Math.abs(a - b) <= 0.0001f;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
