package com.metallum.client.hdr;

import java.util.Properties;

public final class HdrConfigTests {
    private HdrConfigTests() {
    }

    public static void main(final String[] args) {
        testConfigurationParsing();
        testCapabilitySanitization();
        testOutputModeResolution();
        testSodiumShaderPatching();
    }

    private static void testConfigurationParsing() {
        Properties properties = new Properties();
        properties.setProperty("mode", "enhanced");
        properties.setProperty("sourceEncoding", "linear");
        properties.setProperty("hdrStrength", "1.4");
        properties.setProperty("bloomStrength", "0.3");
        properties.setProperty("diagnosticPattern", "true");

        HdrConfig config = HdrConfig.from(properties);
        require(config.mode() == HdrMode.ENHANCED, "mode parsing");
        require(config.sourceEncoding() == HdrSourceEncoding.LINEAR, "source encoding parsing");
        require(config.hdrStrength() == 1.4f, "HDR strength parsing");
        require(config.bloomStrength() == 0.3f, "bloom strength parsing");
        require(config.diagnosticPattern(), "diagnostic flag parsing");

        HdrConfig defaults = HdrConfig.from(new Properties());
        require(defaults.mode() == HdrMode.AUTO, "default mode");
        require(defaults.sourceEncoding() == HdrSourceEncoding.SRGB, "default source encoding");
        require(!defaults.diagnosticPattern(), "default diagnostic flag");

        Properties invalid = new Properties();
        invalid.setProperty("hdrStrength", "NaN");
        invalid.setProperty("bloomStrength", "Infinity");
        HdrConfig sanitized = HdrConfig.from(invalid);
        require(sanitized.hdrStrength() == 1.0f, "non-finite HDR strength fallback");
        require(sanitized.bloomStrength() == 0.22f, "non-finite bloom fallback");

        Properties outOfRange = new Properties();
        outOfRange.setProperty("hdrStrength", "-1");
        outOfRange.setProperty("bloomStrength", "4");
        HdrConfig clamped = HdrConfig.from(outOfRange);
        require(clamped.hdrStrength() == 0.0f, "HDR strength lower clamp");
        require(clamped.bloomStrength() == 1.0f, "bloom strength upper clamp");
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
        require(HdrMode.AUTO.resolve(hdr) == HdrOutputMode.EDR, "auto remains on verified EDR before enhanced QA");
        require(HdrMode.ENHANCED.resolve(hdr) == HdrOutputMode.ENHANCED, "enhanced mode on HDR display");
        require(HdrMode.OFF.resolve(hdr) == HdrOutputMode.SDR, "explicit SDR mode");
        require(HdrMode.ENHANCED.resolve(EdrCapabilities.SDR) == HdrOutputMode.SDR, "SDR display fallback");
    }

    private static void testSodiumShaderPatching() {
        String vertex = "out vec2 v_TexCoord;\nvoid main() {\n    _vert_init();\n}";
        String patchedVertex = SodiumHdrShaderPatcher.patchVertexSource(vertex);
        require(patchedVertex.contains("flat out uint metallumHdrMaterial;"), "Sodium vertex material varying");
        require(patchedVertex.contains("metallumHdrMaterial = _material_params;"), "full Sodium material forwarding");
        require(SodiumHdrShaderPatcher.patchVertexSource(patchedVertex).equals(patchedVertex), "vertex patch idempotence");

        String assignment = "    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);";
        String fragment = "in vec2 v_TexCoord;\nout vec4 fragColor;\nvoid main() {\n" + assignment + "\n}";
        String patchedFragment = SodiumHdrShaderPatcher.patchFragmentSource(fragment);
        require(patchedFragment.contains("flat in uint metallumHdrMaterial;"), "Sodium fragment material varying");
        require(patchedFragment.contains("layout(location = 1) out vec4 metallumHdrSemantic;"), "Sodium semantic MRT output");
        require(patchedFragment.contains("gl_FragCoord.z"), "Sodium semantic depth packing");
        require(patchedFragment.contains("16777215.0"), "24-bit semantic depth precision");
        require(!patchedFragment.contains("fragColor.a ="), "main color alpha remains untouched");
        require(SodiumHdrShaderPatcher.patchFragmentSource(patchedFragment).equals(patchedFragment), "fragment patch idempotence");
        require(SodiumHdrShaderPatcher.encodeVertexSemantic(7, false) == 7, "block light strength encoding");
        require(SodiumHdrShaderPatcher.encodeVertexSemantic(15, true) == 31, "exact emissive encoding");
        require(SodiumHdrShaderPatcher.encodeVertexSemantic(0, true) == 0, "zero emission is never marked");
        require(SodiumHdrShaderPatcher.encodeVertexSemantic(20, false) == 15, "emission strength clamp");
        require(SodiumHdrShaderPatcher.packMaterialBits(5, 31) == 253, "Sodium material semantic packing");
        require(SodiumHdrShaderPatcher.packMaterialBits(0x45, 31) == 0x45, "unknown Sodium bits are preserved");
        require(SodiumHdrShaderPatcher.HDR_MATERIAL_MASK == 0xf8, "only Sodium 0.9.0 unused material bits are occupied");
        require(SodiumHdrShaderPatcher.patchVertexSource("void main() {}").equals("void main() {}"), "unknown shader stays unchanged");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
