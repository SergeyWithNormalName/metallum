package com.metallum.client.renderer.style;

import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.RendererConfig;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.TemporalResetEvents;

import java.util.EnumSet;
import java.util.Set;

/**
 * Production test suite for Visual Styles and Style-Aware Celestial Lighting (STYLE-1).
 */
public final class VisualStyleTests {
    private static final float EPSILON = 1.0e-5f;

    private VisualStyleTests() {
    }

    public static void main(final String[] args) {
        testVisualStyleParsing();
        testProfileCoverage();
        testConfiguredStyleProfiles();
        testProfileValidation();
        testRendererConfigDefaults();
        testLiveRuntimeSwitching();
        testTemporalResetSignalSafety();
        testVanillaRegressionOracle();
        testHighSunColors();
        testLowSunWarmth();
        testSmoothAltitudeTransition();
        testChromaticityEnergySeparation();
        testMoonColorOrdering();
        testMoonPhaseResponses();
        testNightReadabilitySeparation();
        testWeatherAndMediumAttenuation();
        testStyleRuntimeSwitchingIntegration();
        System.out.println("All VisualStyle & STYLE-1 celestial lighting tests passed");
    }

    private static void testVisualStyleParsing() {
        require(VisualStyle.parse("vanilla") == VisualStyle.VANILLA, "parse vanilla mismatch");
        require(VisualStyle.parse("natural") == VisualStyle.NATURAL, "parse natural mismatch");
        require(VisualStyle.parse("realism") == VisualStyle.REALISM, "parse realism mismatch");
        require(VisualStyle.parse("VANILLA") == VisualStyle.VANILLA, "parse uppercase vanilla mismatch");
        require(VisualStyle.parse("  natural  ") == VisualStyle.NATURAL, "parse padded natural mismatch");
        require(VisualStyle.parse(null) == VisualStyle.VANILLA, "parse null did not return default");
        require(VisualStyle.parse("") == VisualStyle.VANILLA, "parse empty did not return default");
        require(VisualStyle.parse("   ") == VisualStyle.VANILLA, "parse blank did not return default");
        require(VisualStyle.parse("cinematic") == VisualStyle.VANILLA, "parse unknown did not return default");
        require(VisualStyle.parse("invalid_style") == VisualStyle.VANILLA, "parse invalid did not return default");

        require(VisualStyle.VANILLA.persistentName().equals("vanilla"), "persistentName vanilla mismatch");
        require(VisualStyle.NATURAL.persistentName().equals("natural"), "persistentName natural mismatch");
        require(VisualStyle.REALISM.persistentName().equals("realism"), "persistentName realism mismatch");
    }

    private static void testProfileCoverage() {
        VisualStyle[] values = VisualStyle.values();
        require(values.length == 3, "Exactly three visual styles must exist");
        require(values[0] == VisualStyle.VANILLA
                && values[1] == VisualStyle.NATURAL
                && values[2] == VisualStyle.REALISM, "Unexpected visual style enum order or elements");

        for (VisualStyle style : values) {
            VisualStyleProfile profile = VisualStyleProfiles.profile(style);
            require(profile != null, "Profile for " + style + " must not be null");
            require(profile.celestialLighting() != null, "CelestialLightingProfile for " + style + " must not be null");
        }
        require(VisualStyleProfiles.profile(null) != null, "Null style must resolve to safe fallback profile");
    }

    private static void testConfiguredStyleProfiles() {
        CelestialLightingProfile vanilla = VisualStyleProfiles.profile(VisualStyle.VANILLA).celestialLighting();
        CelestialLightingProfile natural = VisualStyleProfiles.profile(VisualStyle.NATURAL).celestialLighting();
        CelestialLightingProfile realism = VisualStyleProfiles.profile(VisualStyle.REALISM).celestialLighting();

        // 1. VANILLA exact reference values
        require(vanilla.normalSunColor().equals(new LinearColor(1.00f, 0.93f, 0.78f)), "Vanilla sun mismatch");
        require(vanilla.horizonSunColor().equals(new LinearColor(1.00f, 0.93f, 0.78f)), "Vanilla horizon sun mismatch");
        require(Math.abs(vanilla.sunTransitionMinAltitude() - 0.015f) < EPSILON, "Vanilla sun min altitude mismatch");
        require(Math.abs(vanilla.sunTransitionMaxAltitude() - 0.16f) < EPSILON, "Vanilla sun max altitude mismatch");
        require(Math.abs(vanilla.sunIntensityScale() - 1.65f) < EPSILON, "Vanilla sun intensity scale mismatch");
        require(vanilla.moonColor().equals(new LinearColor(0.50f, 0.62f, 0.90f)), "Vanilla moon color mismatch");
        require(Math.abs(vanilla.moonIntensityScale() - 0.13f) < EPSILON, "Vanilla moon intensity scale mismatch");
        require(Math.abs(vanilla.moonPhaseFloor() - 0.18f) < EPSILON, "Vanilla moon phase floor mismatch");
        require(Math.abs(vanilla.moonPhaseResponse() - 0.82f) < EPSILON, "Vanilla moon phase response mismatch");

        // 2. NATURAL target values
        require(natural.normalSunColor().equals(new LinearColor(1.00f, 0.98f, 0.92f)), "Natural sun mismatch");
        require(natural.horizonSunColor().equals(new LinearColor(1.00f, 0.50f, 0.18f)), "Natural horizon sun mismatch");
        require(Math.abs(natural.sunTransitionMinAltitude() - 0.035f) < EPSILON, "Natural sun min altitude mismatch");
        require(Math.abs(natural.sunTransitionMaxAltitude() - 0.42f) < EPSILON, "Natural sun max altitude mismatch");
        require(Math.abs(natural.sunIntensityScale() - 1.65f) < EPSILON, "Natural sun intensity scale mismatch");
        require(natural.moonColor().equals(new LinearColor(0.72f, 0.80f, 1.00f)), "Natural moon color mismatch");
        require(Math.abs(natural.moonIntensityScale() - 0.10f) < EPSILON, "Natural moon intensity scale mismatch");
        require(Math.abs(natural.moonPhaseFloor() - 0.06f) < EPSILON, "Natural moon phase floor mismatch");
        require(Math.abs(natural.moonPhaseResponse() - 0.94f) < EPSILON, "Natural moon phase response mismatch");

        // 3. REALISM target values
        require(realism.normalSunColor().equals(new LinearColor(1.00f, 0.995f, 0.97f)), "Realism sun mismatch");
        require(realism.horizonSunColor().equals(new LinearColor(1.00f, 0.32f, 0.07f)), "Realism horizon sun mismatch");
        require(Math.abs(realism.sunTransitionMinAltitude() - 0.020f) < EPSILON, "Realism sun min altitude mismatch");
        require(Math.abs(realism.sunTransitionMaxAltitude() - 0.55f) < EPSILON, "Realism sun max altitude mismatch");
        require(Math.abs(realism.sunIntensityScale() - 1.65f) < EPSILON, "Realism sun intensity scale mismatch");
        require(realism.moonColor().equals(new LinearColor(0.90f, 0.94f, 1.00f)), "Realism moon color mismatch");
        require(Math.abs(realism.moonIntensityScale() - 0.085f) < EPSILON, "Realism moon intensity scale mismatch");
        require(Math.abs(realism.moonPhaseFloor() - 0.00f) < EPSILON, "Realism moon phase floor mismatch");
        require(Math.abs(realism.moonPhaseResponse() - 1.00f) < EPSILON, "Realism moon phase response mismatch");
    }

    private static void testProfileValidation() {
        LinearColor sun = new LinearColor(1.00f, 0.93f, 0.78f);
        LinearColor moon = new LinearColor(0.50f, 0.62f, 0.90f);

        // LinearColor validation
        expectIllegalArgument(() -> new LinearColor(Float.NaN, 1.0f, 1.0f));
        expectIllegalArgument(() -> new LinearColor(1.0f, Float.POSITIVE_INFINITY, 1.0f));
        expectIllegalArgument(() -> new LinearColor(1.0f, 1.0f, -0.01f));
        expectIllegalArgument(() -> sun.scale(-0.5f));
        expectIllegalArgument(() -> sun.scale(Float.NaN));
        expectNullPointer(() -> LinearColor.lerp(null, moon, 0.5f));
        expectNullPointer(() -> LinearColor.lerp(sun, null, 0.5f));
        expectIllegalArgument(() -> LinearColor.lerp(sun, moon, Float.NaN));

        // CelestialLightingProfile validation
        expectNullPointer(() -> new CelestialLightingProfile(
                null, sun, 0.015f, 0.16f, 1.65f, moon, 0.13f, 0.18f, 0.82f
        ));
        expectNullPointer(() -> new CelestialLightingProfile(
                sun, null, 0.015f, 0.16f, 1.65f, moon, 0.13f, 0.18f, 0.82f
        ));
        expectNullPointer(() -> new CelestialLightingProfile(
                sun, sun, 0.015f, 0.16f, 1.65f, null, 0.13f, 0.18f, 0.82f
        ));

        // Altitude interval bounds and ordering
        expectIllegalArgument(() -> new CelestialLightingProfile(
                sun, sun, -0.01f, 0.16f, 1.65f, moon, 0.13f, 0.18f, 0.82f
        ));
        expectIllegalArgument(() -> new CelestialLightingProfile(
                sun, sun, 0.20f, 0.10f, 1.65f, moon, 0.13f, 0.18f, 0.82f
        ));
        expectIllegalArgument(() -> new CelestialLightingProfile(
                sun, sun, Float.NaN, 0.16f, 1.65f, moon, 0.13f, 0.18f, 0.82f
        ));
        expectIllegalArgument(() -> new CelestialLightingProfile(
                sun, sun, 0.015f, Float.POSITIVE_INFINITY, 1.65f, moon, 0.13f, 0.18f, 0.82f
        ));

        // Intensity and response validation
        expectIllegalArgument(() -> new CelestialLightingProfile(
                sun, sun, 0.015f, 0.16f, -0.5f, moon, 0.13f, 0.18f, 0.82f
        ));
        expectIllegalArgument(() -> new CelestialLightingProfile(
                sun, sun, 0.015f, 0.16f, 1.65f, moon, -0.01f, 0.18f, 0.82f
        ));
        expectIllegalArgument(() -> new CelestialLightingProfile(
                sun, sun, 0.015f, 0.16f, 1.65f, moon, 0.13f, -0.01f, 0.82f
        ));
        expectIllegalArgument(() -> new CelestialLightingProfile(
                sun, sun, 0.015f, 0.16f, 1.65f, moon, 0.13f, 1.05f, 0.82f
        ));
        expectIllegalArgument(() -> new CelestialLightingProfile(
                sun, sun, 0.015f, 0.16f, 1.65f, moon, 0.13f, 0.18f, -0.1f
        ));

        // VisualStyleProfile validation
        expectNullPointer(() -> new VisualStyleProfile(null));
    }

    private static void testRendererConfigDefaults() {
        RendererConfig defaults = RendererConfig.defaults();
        require(RendererConfig.SCHEMA_VERSION == 4, "SCHEMA_VERSION must be 4");
        require(!defaults.improvedLighting(), "improvedLighting default must be false");
        require(defaults.lightingPreset() == LightingPreset.BALANCED, "lightingPreset default must be BALANCED");
        require(!defaults.frameInterpolation(), "frameInterpolation default must be false");
        require(!defaults.voxelDebugChecksum(), "voxelDebugChecksum default must be false");
        require(defaults.visualStyle() == VisualStyle.VANILLA, "visualStyle default must be VANILLA");

        expectNullPointer(() -> new RendererConfig(false, null, false, false, VisualStyle.VANILLA));
        expectNullPointer(() -> new RendererConfig(false, LightingPreset.BALANCED, false, false, null));

        RendererConfig withStyle = defaults.withVisualStyle(VisualStyle.NATURAL);
        require(withStyle.visualStyle() == VisualStyle.NATURAL, "withVisualStyle failed to update style");
        require(!withStyle.improvedLighting() && withStyle.lightingPreset() == LightingPreset.BALANCED,
                "withVisualStyle mutated other fields");

        RendererConfig withLight = withStyle.withImprovedLighting(true);
        require(withLight.improvedLighting() && withLight.visualStyle() == VisualStyle.NATURAL,
                "withImprovedLighting lost visualStyle");
    }

    private static void testLiveRuntimeSwitching() {
        TemporalResetEvents.consume(); // clear any pending resets
        VisualStyleRuntime.initialize(VisualStyle.VANILLA);
        require(VisualStyleRuntime.activeStyle() == VisualStyle.VANILLA, "Startup style must be VANILLA");
        require(VisualStyleRuntime.activeProfile().equals(VisualStyleProfiles.profile(VisualStyle.VANILLA)),
                "Startup profile must be VANILLA profile");
        require(TemporalResetEvents.consume().isEmpty(), "Startup initialization must NOT emit a temporal reset");

        // Re-selecting the same style must be a no-op
        boolean changedSame = VisualStyleRuntime.setStyle(VisualStyle.VANILLA);
        require(!changedSame, "setStyle with same style must return false");
        require(TemporalResetEvents.consume().isEmpty(), "Selecting active style must NOT emit a temporal reset");

        // Live switch: VANILLA -> NATURAL
        boolean changedNatural = VisualStyleRuntime.setStyle(VisualStyle.NATURAL);
        require(changedNatural, "setStyle to NATURAL must return true");
        require(VisualStyleRuntime.activeStyle() == VisualStyle.NATURAL, "Active style must be NATURAL");
        require(VisualStyleRuntime.activeProfile().equals(VisualStyleProfiles.profile(VisualStyle.NATURAL)),
                "Active profile must be NATURAL profile");
        Set<FrameState.HistoryResetReason> resets = TemporalResetEvents.consume();
        require(resets.equals(Set.of(FrameState.HistoryResetReason.VISUAL_STYLE_CHANGE)),
                "Style change must emit VISUAL_STYLE_CHANGE");
        require(TemporalResetEvents.consume().isEmpty(), "Reset event must be one-shot and consumed");

        // Selecting NATURAL again -> no reset
        boolean reselectNatural = VisualStyleRuntime.setStyle(VisualStyle.NATURAL);
        require(!reselectNatural, "Reselecting NATURAL must return false");
        require(TemporalResetEvents.consume().isEmpty(), "Reselecting NATURAL must not emit a reset");

        // Live switch: NATURAL -> REALISM
        boolean changedRealism = VisualStyleRuntime.setStyle(VisualStyle.REALISM);
        require(changedRealism, "setStyle to REALISM must return true");
        require(VisualStyleRuntime.activeStyle() == VisualStyle.REALISM, "Active style must be REALISM");
        require(VisualStyleRuntime.activeProfile().equals(VisualStyleProfiles.profile(VisualStyle.REALISM)),
                "Active profile must be REALISM profile");
        require(TemporalResetEvents.consume().equals(Set.of(FrameState.HistoryResetReason.VISUAL_STYLE_CHANGE)),
                "Switch to REALISM must emit VISUAL_STYLE_CHANGE");
        require(TemporalResetEvents.consume().isEmpty(), "Reset event must be consumed");

        // Reset to default for tests
        VisualStyleRuntime.resetForTests();
        require(VisualStyleRuntime.activeStyle() == VisualStyle.VANILLA, "resetForTests must restore VANILLA");
    }

    private static void testTemporalResetSignalSafety() {
        EnumSet<FrameState.HistoryResetReason> allReasons = EnumSet.allOf(FrameState.HistoryResetReason.class);
        require(allReasons.contains(FrameState.HistoryResetReason.VISUAL_STYLE_CHANGE),
                "HistoryResetReason must contain VISUAL_STYLE_CHANGE");
        require(allReasons.size() <= 64, "HistoryResetReason count must fit in a 64-bit mask");

        TemporalResetEvents.consume();
        TemporalResetEvents.signal(FrameState.HistoryResetReason.VISUAL_STYLE_CHANGE);
        Set<FrameState.HistoryResetReason> consumed = TemporalResetEvents.consume();
        require(consumed.contains(FrameState.HistoryResetReason.VISUAL_STYLE_CHANGE),
                "Signaled VISUAL_STYLE_CHANGE was not consumed");
        require(TemporalResetEvents.consume().isEmpty(), "Pending reset mask must be 0 after consume");
    }

    /**
     * Section 15: Automated Vanilla regression oracle.
     * Independently computes the pre-STYLE-1 legacy celestial values and tests across 720 combinations.
     */
    private static void testVanillaRegressionOracle() {
        float[] sunAngles = {
                0.0f,                     // high noon
                (float) (Math.PI * 0.25), // moderate altitude
                (float) (Math.PI * 0.45), // low sun
                (float) (Math.PI * 0.49), // near horizon
                (float) (Math.PI * 0.55), // moon side
                (float) Math.PI           // high moon
        };
        float[] moonPhases = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
        float[] rains = {0.0f, 0.5f, 1.0f};
        float[] thunders = {0.0f, 1.0f};
        EnvironmentDescriptor.Medium[] media = EnvironmentDescriptor.Medium.values();

        float skyRed = 0.65f, skyGreen = 0.75f, skyBlue = 0.90f, skyFactor = 0.85f;
        float ambientRed = 0.05f, ambientGreen = 0.06f, ambientBlue = 0.08f;

        CelestialLightingProfile vanillaProfile = VisualStyleProfiles.profile(VisualStyle.VANILLA).celestialLighting();

        for (float sunAngle : sunAngles) {
            for (float moonPhase : moonPhases) {
                for (float rain : rains) {
                    for (float thunder : thunders) {
                        for (EnvironmentDescriptor.Medium medium : media) {
                            // 1. Production output with VANILLA profile
                            EnvironmentDescriptor actual = EnvironmentDescriptor.celestial(
                                    medium, sunAngle, skyRed, skyGreen, skyBlue, skyFactor,
                                    ambientRed, ambientGreen, ambientBlue, rain, thunder, moonPhase,
                                    vanillaProfile
                            );

                            // Also test default overload (which defaults to Vanilla)
                            EnvironmentDescriptor defaultActual = EnvironmentDescriptor.celestial(
                                    medium, sunAngle, skyRed, skyGreen, skyBlue, skyFactor,
                                    ambientRed, ambientGreen, ambientBlue, rain, thunder, moonPhase
                            );
                            requireDescriptorsEqual(actual, defaultActual, "Default celestial overload did not match Vanilla profile");

                            // 2. Independently encoded legacy reference formula
                            LegacyReference expected = computeLegacyReference(
                                    medium, sunAngle, skyRed, skyGreen, skyBlue, skyFactor,
                                    ambientRed, ambientGreen, ambientBlue, rain, thunder, moonPhase
                            );

                            // 3. Strict assertions
                            require(Math.abs(actual.toLightX() - expected.toLightX) < EPSILON, "toLightX drift");
                            require(Math.abs(actual.toLightY() - expected.toLightY) < EPSILON, "toLightY drift");
                            require(actual.toLightZ() == 0.0f, "toLightZ must be 0");
                            require(Math.abs(actual.directionalRed() - expected.directionalRed) < EPSILON, "directionalRed drift");
                            require(Math.abs(actual.directionalGreen() - expected.directionalGreen) < EPSILON, "directionalGreen drift");
                            require(Math.abs(actual.directionalBlue() - expected.directionalBlue) < EPSILON, "directionalBlue drift");
                            require(Math.abs(actual.skyRed() - expected.skyRed) < EPSILON, "skyRed drift");
                            require(Math.abs(actual.skyGreen() - expected.skyGreen) < EPSILON, "skyGreen drift");
                            require(Math.abs(actual.skyBlue() - expected.skyBlue) < EPSILON, "skyBlue drift");
                            require(Math.abs(actual.ambientRed() - expected.ambientRed) < EPSILON, "ambientRed drift");
                            require(Math.abs(actual.ambientGreen() - expected.ambientGreen) < EPSILON, "ambientGreen drift");
                            require(Math.abs(actual.ambientBlue() - expected.ambientBlue) < EPSILON, "ambientBlue drift");
                            require(actual.moon() == expected.moon, "moon flag mismatch");
                            require(actual.sunShadowEligible() == expected.sunShadowEligible, "sunShadowEligible mismatch");
                        }
                    }
                }
            }
        }
    }

    private static record LegacyReference(
            float toLightX, float toLightY,
            float directionalRed, float directionalGreen, float directionalBlue,
            float skyRed, float skyGreen, float skyBlue,
            float ambientRed, float ambientGreen, float ambientBlue,
            boolean moon, boolean sunShadowEligible
    ) {
    }

    private static LegacyReference computeLegacyReference(
            final EnvironmentDescriptor.Medium medium,
            final float sunAngle,
            final float skyRed,
            final float skyGreen,
            final float skyBlue,
            final float skyFactor,
            final float ambientRed,
            final float ambientGreen,
            final float ambientBlue,
            final float rain,
            final float thunder,
            final float moonPhaseBrightness
    ) {
        float sunX = (float) -Math.sin(sunAngle);
        float sunY = (float) Math.cos(sunAngle);
        boolean moon = sunY < 0.0f;
        float toLightX = moon ? -sunX : sunX;
        float toLightY = moon ? -sunY : sunY;
        float altitude = Math.max(toLightY, 0.0f);

        float t = Math.clamp((altitude - 0.015f) / (0.16f - 0.015f), 0.0f, 1.0f);
        float horizon = t * t * (3.0f - 2.0f * t);

        float weatherTransmission = Math.max(0.08f, 1.0f - rain * 0.72f - thunder * 0.20f);
        float mediumTransmission = switch (medium) {
            case WATER -> 0.55f;
            case LAVA -> 0.0f;
            case POWDER_SNOW -> 0.22f;
            case AIR -> 1.0f;
        };
        float directionalScale = moon
                ? 0.13f * (0.18f + 0.82f * moonPhaseBrightness) * horizon
                : 1.65f * horizon;
        directionalScale *= weatherTransmission * mediumTransmission;

        float directionalRed = directionalScale * (moon ? 0.50f : 1.00f);
        float directionalGreen = directionalScale * (moon ? 0.62f : 0.93f);
        float directionalBlue = directionalScale * (moon ? 0.90f : 0.78f);

        float diffuseWeather = 1.0f - thunder * 0.45f;
        float diffuseToIrradiance = (float) Math.PI;
        float skyScale = 0.46f * (float) Math.sqrt(skyFactor) * diffuseWeather
                * Math.max(mediumTransmission, 0.12f);

        return new LegacyReference(
                toLightX, toLightY,
                directionalRed, directionalGreen, directionalBlue,
                skyRed * skyScale, skyGreen * skyScale, skyBlue * skyScale,
                ambientRed * diffuseToIrradiance, ambientGreen * diffuseToIrradiance, ambientBlue * diffuseToIrradiance,
                moon, altitude > 0.035f && directionalScale > 0.001f
        );
    }

    /**
     * Section 16.1: High Sun color assertions.
     */
    private static void testHighSunColors() {
        CelestialLightingProfile natural = VisualStyleProfiles.profile(VisualStyle.NATURAL).celestialLighting();
        CelestialLightingProfile realism = VisualStyleProfiles.profile(VisualStyle.REALISM).celestialLighting();

        LinearColor naturalHigh = natural.evaluateSunColor(0.8f);
        LinearColor realismHigh = realism.evaluateSunColor(0.8f);

        require(Math.abs(naturalHigh.red() - 1.00f) < EPSILON
                        && Math.abs(naturalHigh.green() - 0.98f) < EPSILON
                        && Math.abs(naturalHigh.blue() - 0.92f) < EPSILON,
                "Natural high sun mismatch");

        require(Math.abs(realismHigh.red() - 1.00f) < EPSILON
                        && Math.abs(realismHigh.green() - 0.995f) < EPSILON
                        && Math.abs(realismHigh.blue() - 0.97f) < EPSILON,
                "Realism high sun mismatch");

        require(Float.isFinite(naturalHigh.red()) && naturalHigh.red() >= 0.0f, "Natural sun color must be finite non-negative");
        require(Float.isFinite(realismHigh.red()) && realismHigh.red() >= 0.0f, "Realism sun color must be finite non-negative");
    }

    /**
     * Section 16.2: Low Sun warmth assertions.
     */
    private static void testLowSunWarmth() {
        CelestialLightingProfile natural = VisualStyleProfiles.profile(VisualStyle.NATURAL).celestialLighting();
        CelestialLightingProfile realism = VisualStyleProfiles.profile(VisualStyle.REALISM).celestialLighting();

        float lowAltitude = 0.035f;
        LinearColor naturalHigh = natural.evaluateSunColor(0.8f);
        LinearColor naturalLow = natural.evaluateSunColor(lowAltitude);
        LinearColor realismHigh = realism.evaluateSunColor(0.8f);
        LinearColor realismLow = realism.evaluateSunColor(lowAltitude);

        float naturalBlueRatioHigh = naturalHigh.blue() / naturalHigh.red();
        float naturalBlueRatioLow = naturalLow.blue() / naturalLow.red();
        float naturalGreenRatioHigh = naturalHigh.green() / naturalHigh.red();
        float naturalGreenRatioLow = naturalLow.green() / naturalLow.red();

        require(naturalBlueRatioLow < naturalBlueRatioHigh, "Natural low sun must have lower blue ratio than high sun");
        require(naturalGreenRatioLow < naturalGreenRatioHigh, "Natural low sun must have lower green ratio than high sun");

        float realismBlueRatioLow = realismLow.blue() / realismLow.red();
        float realismGreenRatioLow = realismLow.green() / realismLow.red();

        require(realismBlueRatioLow < naturalBlueRatioLow, "Realism low sun must be warmer (lower blue ratio) than Natural low sun");
        require(realismGreenRatioLow < naturalGreenRatioLow, "Realism low sun must be warmer (lower green ratio) than Natural low sun");
    }

    /**
     * Section 16.3: Smooth monotonic transition.
     */
    private static void testSmoothAltitudeTransition() {
        for (VisualStyle style : new VisualStyle[]{VisualStyle.NATURAL, VisualStyle.REALISM}) {
            CelestialLightingProfile profile = VisualStyleProfiles.profile(style).celestialLighting();
            float prevBlueRatio = -1.0f;
            for (float alt = 0.0f; alt <= 0.9f; alt += 0.01f) {
                LinearColor color = profile.evaluateSunColor(alt);
                require(Float.isFinite(color.red()) && Float.isFinite(color.green()) && Float.isFinite(color.blue()),
                        "Color must be finite across altitude sweep");
                float blueRatio = color.blue() / color.red();
                if (prevBlueRatio >= 0.0f) {
                    require(blueRatio >= prevBlueRatio - 1.0e-6f,
                            style + " blue ratio must increase monotonically with altitude, got " + blueRatio + " < " + prevBlueRatio);
                }
                prevBlueRatio = blueRatio;
            }
        }
    }

    /**
     * Section 16.4: Chromaticity and energy separation.
     */
    private static void testChromaticityEnergySeparation() {
        for (VisualStyle style : VisualStyle.values()) {
            CelestialLightingProfile profile = VisualStyleProfiles.profile(style).celestialLighting();
            float targetLuminance = profile.normalSunColor().luminance();
            for (float alt = 0.0f; alt <= 1.0f; alt += 0.05f) {
                LinearColor evaluated = profile.evaluateSunColor(alt);
                float evalLuminance = evaluated.luminance();
                require(Math.abs(evalLuminance - targetLuminance) < 1.0e-4f,
                        style + " sun color luminance drifted across altitude: expected " + targetLuminance + ", got " + evalLuminance);
            }
        }
    }

    /**
     * Section 16.5: Moon color ordering.
     */
    private static void testMoonColorOrdering() {
        CelestialLightingProfile vanilla = VisualStyleProfiles.profile(VisualStyle.VANILLA).celestialLighting();
        CelestialLightingProfile natural = VisualStyleProfiles.profile(VisualStyle.NATURAL).celestialLighting();
        CelestialLightingProfile realism = VisualStyleProfiles.profile(VisualStyle.REALISM).celestialLighting();

        float vanillaBlueRatio = vanilla.moonColor().blue() / vanilla.moonColor().red();
        float naturalBlueRatio = natural.moonColor().blue() / natural.moonColor().red();
        float realismBlueRatio = realism.moonColor().blue() / realism.moonColor().red();

        require(vanillaBlueRatio > naturalBlueRatio, "Vanilla moon must be more blue-biased than Natural moon");
        require(naturalBlueRatio > realismBlueRatio, "Natural moon must be more blue-biased than Realism moon");
        require(realismBlueRatio > 1.0f && realismBlueRatio < 1.25f, "Realism moon should be subtly cool");
    }

    /**
     * Section 16.6: Moon phase responses.
     */
    private static void testMoonPhaseResponses() {
        for (VisualStyle style : VisualStyle.values()) {
            CelestialLightingProfile profile = VisualStyleProfiles.profile(style).celestialLighting();
            float scale0 = profile.evaluateMoonPhaseScale(0.0f);
            float scale25 = profile.evaluateMoonPhaseScale(0.25f);
            float scale50 = profile.evaluateMoonPhaseScale(0.50f);
            float scale75 = profile.evaluateMoonPhaseScale(0.75f);
            float scale100 = profile.evaluateMoonPhaseScale(1.0f);

            require(scale100 > scale75 && scale75 > scale50 && scale50 > scale25 && scale25 >= scale0,
                    style + " moon phase scale ordering violation");
        }

        // Specific style checks
        CelestialLightingProfile natural = VisualStyleProfiles.profile(VisualStyle.NATURAL).celestialLighting();
        require(Math.abs(natural.evaluateMoonPhaseScale(0.0f) - 0.06f) < EPSILON, "Natural moon phase 0 scale mismatch");
        require(Math.abs(natural.evaluateMoonPhaseScale(1.0f) - 1.00f) < EPSILON, "Natural moon phase 1 scale mismatch");

        CelestialLightingProfile realism = VisualStyleProfiles.profile(VisualStyle.REALISM).celestialLighting();
        require(Math.abs(realism.evaluateMoonPhaseScale(0.0f) - 0.00f) < EPSILON, "Realism moon phase 0 scale must be 0");
        require(Math.abs(realism.evaluateMoonPhaseScale(1.0f) - 1.00f) < EPSILON, "Realism moon phase 1 scale must be 1");
    }

    /**
     * Section 16.7: Night readability separation.
     */
    private static void testNightReadabilitySeparation() {
        CelestialLightingProfile realism = VisualStyleProfiles.profile(VisualStyle.REALISM).celestialLighting();
        float midnightAngle = (float) Math.PI;

        EnvironmentDescriptor fullMoon = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR, midnightAngle,
                0.1f, 0.15f, 0.25f, 0.24f,
                0.04f, 0.04f, 0.04f,
                0.0f, 0.0f, 1.0f,
                realism
        );

        EnvironmentDescriptor newMoon = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR, midnightAngle,
                0.1f, 0.15f, 0.25f, 0.24f,
                0.04f, 0.04f, 0.04f,
                0.0f, 0.0f, 0.0f,
                realism
        );

        // Directional radiance changes strongly
        float fullDir = fullMoon.directionalRed() + fullMoon.directionalGreen() + fullMoon.directionalBlue();
        float newDir = newMoon.directionalRed() + newMoon.directionalGreen() + newMoon.directionalBlue();
        require(fullDir > 0.05f, "Full moon must have directional radiance");
        require(newDir == 0.0f, "Realism new moon must have zero directional radiance");

        // Sky and ambient radiance remain bit-identical
        require(fullMoon.skyRed() == newMoon.skyRed(), "Sky red must remain identical between moon phases");
        require(fullMoon.skyGreen() == newMoon.skyGreen(), "Sky green must remain identical between moon phases");
        require(fullMoon.skyBlue() == newMoon.skyBlue(), "Sky blue must remain identical between moon phases");
        require(fullMoon.ambientRed() == newMoon.ambientRed(), "Ambient red must remain identical between moon phases");
        require(fullMoon.ambientGreen() == newMoon.ambientGreen(), "Ambient green must remain identical between moon phases");
        require(fullMoon.ambientBlue() == newMoon.ambientBlue(), "Ambient blue must remain identical between moon phases");
    }

    /**
     * Section 16.8 & 16.9: Weather and Medium attenuation.
     */
    private static void testWeatherAndMediumAttenuation() {
        CelestialLightingProfile profile = VisualStyleProfiles.profile(VisualStyle.NATURAL).celestialLighting();

        EnvironmentDescriptor clear = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR, 0.0f, 0.5f, 0.5f, 0.5f, 1.0f,
                0.04f, 0.04f, 0.04f, 0.0f, 0.0f, 1.0f, profile
        );
        EnvironmentDescriptor storm = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR, 0.0f, 0.5f, 0.5f, 0.5f, 1.0f,
                0.04f, 0.04f, 0.04f, 1.0f, 1.0f, 1.0f, profile
        );
        require(storm.directionalRed() < clear.directionalRed() * 0.15f, "Storm did not attenuate directional light");

        EnvironmentDescriptor water = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.WATER, 0.0f, 0.5f, 0.5f, 0.5f, 1.0f,
                0.04f, 0.04f, 0.04f, 0.0f, 0.0f, 1.0f, profile
        );
        require(Math.abs(water.directionalRed() - clear.directionalRed() * 0.55f) < EPSILON, "Water transmission mismatch");

        EnvironmentDescriptor lava = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.LAVA, 0.0f, 0.5f, 0.5f, 0.5f, 1.0f,
                0.04f, 0.04f, 0.04f, 0.0f, 0.0f, 1.0f, profile
        );
        require(lava.directionalRed() == 0.0f && lava.directionalGreen() == 0.0f && lava.directionalBlue() == 0.0f,
                "Lava medium must completely suppress celestial direct light");
    }

    /**
     * Section 17: Style switch integration test.
     */
    private static void testStyleRuntimeSwitchingIntegration() {
        VisualStyleRuntime.initialize(VisualStyle.VANILLA);
        EnvironmentDescriptor vanillaDesc = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR, (float) (Math.PI * 0.45), 0.5f, 0.5f, 0.5f, 1.0f,
                0.04f, 0.04f, 0.04f, 0.0f, 0.0f, 1.0f,
                VisualStyleRuntime.activeProfile().celestialLighting()
        );

        VisualStyleRuntime.setStyle(VisualStyle.NATURAL);
        EnvironmentDescriptor naturalDesc = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR, (float) (Math.PI * 0.45), 0.5f, 0.5f, 0.5f, 1.0f,
                0.04f, 0.04f, 0.04f, 0.0f, 0.0f, 1.0f,
                VisualStyleRuntime.activeProfile().celestialLighting()
        );

        VisualStyleRuntime.setStyle(VisualStyle.REALISM);
        EnvironmentDescriptor realismDesc = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR, (float) (Math.PI * 0.45), 0.5f, 0.5f, 0.5f, 1.0f,
                0.04f, 0.04f, 0.04f, 0.0f, 0.0f, 1.0f,
                VisualStyleRuntime.activeProfile().celestialLighting()
        );

        // Vanilla preserves legacy color at sunset (B/R == 0.78 / 1.00 = 0.78)
        require(Math.abs(vanillaDesc.directionalBlue() / vanillaDesc.directionalRed() - 0.78f) < EPSILON,
                "Vanilla low sun did not preserve 0.78 blue ratio");

        // Natural low sun is warmer (B/R < 0.78)
        float naturalBlueRatio = naturalDesc.directionalBlue() / naturalDesc.directionalRed();
        require(naturalBlueRatio < 0.78f, "Natural low sun is not warmer than Vanilla");

        // Realism low sun is warmer than Natural (B/R < Natural B/R)
        float realismBlueRatio = realismDesc.directionalBlue() / realismDesc.directionalRed();
        require(realismBlueRatio < naturalBlueRatio, "Realism low sun is not warmer than Natural");

        // Restore default
        VisualStyleRuntime.resetForTests();
    }

    private static void requireDescriptorsEqual(
            final EnvironmentDescriptor a,
            final EnvironmentDescriptor b,
            final String message
    ) {
        if (!a.equals(b)) {
            throw new AssertionError(message + ": expected " + a + ", got " + b);
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException but succeeded");
        } catch (IllegalArgumentException expected) {
            // Success
        }
    }

    private static void expectNullPointer(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected NullPointerException but succeeded");
        } catch (NullPointerException expected) {
            // Success
        }
    }
}
