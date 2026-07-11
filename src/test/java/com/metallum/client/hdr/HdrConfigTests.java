package com.metallum.client.hdr;

import java.util.Properties;

public final class HdrConfigTests {
    private HdrConfigTests() {
    }

    public static void main(final String[] args) {
        testConfigurationParsing();
        testCapabilitySanitization();
        testOutputModeResolution();
    }

    private static void testConfigurationParsing() {
        Properties properties = new Properties();
        properties.setProperty("mode", "enhanced");
        properties.setProperty("sourceEncoding", "linear");
        properties.setProperty("diagnosticPattern", "true");

        HdrConfig config = HdrConfig.from(properties);
        require(config.mode() == HdrMode.ENHANCED, "mode parsing");
        require(config.sourceEncoding() == HdrSourceEncoding.LINEAR, "source encoding parsing");
        require(config.diagnosticPattern(), "diagnostic flag parsing");

        HdrConfig defaults = HdrConfig.from(new Properties());
        require(defaults.mode() == HdrMode.AUTO, "default mode");
        require(defaults.sourceEncoding() == HdrSourceEncoding.SRGB, "default source encoding");
        require(!defaults.diagnosticPattern(), "default diagnostic flag");
    }

    private static void testCapabilitySanitization() {
        EdrCapabilities invalid = new EdrCapabilities(Float.NaN, -4.0f);
        require(invalid.equals(EdrCapabilities.SDR), "invalid EDR values fall back to SDR");

        EdrCapabilities reversed = new EdrCapabilities(3.0f, 2.0f);
        require(reversed.currentHeadroom() == 3.0f, "current headroom is retained");
        require(reversed.potentialHeadroom() == 3.0f, "potential is never below current");
    }

    private static void testOutputModeResolution() {
        EdrCapabilities hdr = new EdrCapabilities(2.0f, 8.0f);
        require(HdrMode.AUTO.resolve(hdr) == HdrOutputMode.EDR, "auto starts with transport-safe EDR");
        require(HdrMode.ENHANCED.resolve(hdr) == HdrOutputMode.ENHANCED, "enhanced mode on HDR display");
        require(HdrMode.OFF.resolve(hdr) == HdrOutputMode.SDR, "explicit SDR mode");
        require(HdrMode.ENHANCED.resolve(EdrCapabilities.SDR) == HdrOutputMode.SDR, "SDR display fallback");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
