package com.metallum.client.lighting.reflection;

import java.util.Properties;

/** Dependency-free persistence contract for water-reflection quality settings. */
public final class WaterReflectionQualityConfigTests {
    private WaterReflectionQualityConfigTests() {
    }

    public static void main(final String[] args) {
        testMissingPropertiesKeepEveryRefinementEnabled();
        testEachRefinementCanBeDisabledIndependently();
        testMalformedValuesFailTowardQuality();
        testLaunchOverridesAreIndependent();
        System.out.println("WaterReflectionQualityConfigTests passed successfully.");
    }

    private static void testMissingPropertiesKeepEveryRefinementEnabled() {
        WaterReflectionQualityConfig.Settings settings = WaterReflectionQualityConfig.from(new Properties());
        require(settings.equals(WaterReflectionQualityConfig.Settings.DEFAULT),
                "missing water-reflection quality properties must default to all refinements enabled");
    }

    private static void testEachRefinementCanBeDisabledIndependently() {
        Properties properties = new Properties();
        properties.setProperty("faceAwareAppearance", "false");
        WaterReflectionQualityConfig.Settings faceDisabled = WaterReflectionQualityConfig.from(properties);
        require(!faceDisabled.faceAwareAppearance()
                        && faceDisabled.firstSurfaceBiasedIntegration()
                        && faceDisabled.representationConfidence(),
                "face-aware appearance must be independently disableable");

        properties.clear();
        properties.setProperty("firstSurfaceBiasedIntegration", "FALSE");
        WaterReflectionQualityConfig.Settings firstSurfaceDisabled = WaterReflectionQualityConfig.from(properties);
        require(firstSurfaceDisabled.faceAwareAppearance()
                        && !firstSurfaceDisabled.firstSurfaceBiasedIntegration()
                        && firstSurfaceDisabled.representationConfidence(),
                "first-surface integration must be independently disableable");

        properties.clear();
        properties.setProperty("representationConfidence", "false");
        WaterReflectionQualityConfig.Settings confidenceDisabled = WaterReflectionQualityConfig.from(properties);
        require(confidenceDisabled.faceAwareAppearance()
                        && confidenceDisabled.firstSurfaceBiasedIntegration()
                        && !confidenceDisabled.representationConfidence(),
                "representation confidence must be independently disableable");
    }

    private static void testMalformedValuesFailTowardQuality() {
        Properties properties = new Properties();
        properties.setProperty("faceAwareAppearance", "maybe");
        properties.setProperty("firstSurfaceBiasedIntegration", "");
        properties.setProperty("representationConfidence", "0");
        require(WaterReflectionQualityConfig.from(properties)
                        .equals(WaterReflectionQualityConfig.Settings.DEFAULT),
                "only an explicit false value may disable a quality refinement");
    }

    private static void testLaunchOverridesAreIndependent() {
        String key = WaterReflectionQualityConfig.REPRESENTATION_CONFIDENCE_PROPERTY;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "false");
            require(!WaterReflectionQualityConfig.isRepresentationConfidenceEnabled(),
                    "launch override must disable representation confidence for deterministic A/B");
            require(WaterReflectionQualityConfig.isFaceAwareAppearanceEnabled()
                            && WaterReflectionQualityConfig.isFirstSurfaceBiasedIntegrationEnabled(),
                    "one launch override must not alter the other refinements");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
