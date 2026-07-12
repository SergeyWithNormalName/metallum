package com.metallum.client.hdr;

import java.util.Properties;

public final class HdrConfigTests {
    private HdrConfigTests() {
    }

    public static void main(final String[] args) {
        testConfigurationParsing();
        testCapabilitySanitization();
        testOutputModeResolution();
        testSemanticState();
        testSceneState();
        testSodiumShaderPatching();
        testVanillaShaderPatching();
        testLightmapShaderPatching();
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
        require(!config.experimentalFp16(), "legacy FP16 flag defaults off");

        properties.setProperty("mode", "scene");
        require(HdrConfig.from(properties).mode() == HdrMode.SCENE, "scene mode parsing");
        properties.setProperty("mode", "hdr_scene");
        require(HdrConfig.from(properties).mode() == HdrMode.SCENE, "hdr_scene alias parsing");
        properties.setProperty("mode", "full");
        require(HdrConfig.from(properties).mode() == HdrMode.SCENE, "full alias parsing");

        HdrConfig defaults = HdrConfig.from(new Properties());
        require(defaults.mode() == HdrMode.AUTO, "default mode");
        require(defaults.sourceEncoding() == HdrSourceEncoding.SRGB, "default source encoding");
        require(!defaults.experimentalFp16(), "deprecated flag is absent from new defaults");
        require(HdrSourceEncoding.SRGB.nativeValue(false) == 0, "RGBA8 uses bounded sRGB source contract");
        require(HdrSourceEncoding.SRGB.nativeValue(true) == 1, "FP16 uses extended sRGB source contract");
        require(HdrSourceEncoding.LINEAR.nativeValue(true) == 2, "explicit linear source contract is retained");
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
        require(HdrMode.AUTO.resolve(hdr) == HdrOutputMode.ENHANCED, "auto enables enhanced HDR on an HDR display");
        require(HdrMode.SCENE.resolve(hdr) == HdrOutputMode.ENHANCED, "scene mode uses enhanced HDR output");
        require(HdrMode.ENHANCED.resolve(hdr) == HdrOutputMode.ENHANCED, "enhanced mode on HDR display");
        require(HdrMode.EDR.resolve(hdr) == HdrOutputMode.EDR, "explicit EDR output mode");
        require(HdrMode.OFF.resolve(hdr) == HdrOutputMode.SDR, "explicit SDR mode");
        require(HdrMode.SCENE.resolve(EdrCapabilities.SDR) == HdrOutputMode.SDR, "scene mode falls back on an SDR display");
        require(HdrMode.ENHANCED.resolve(EdrCapabilities.SDR) == HdrOutputMode.SDR, "SDR display fallback");
    }

    private static void testSemanticState() {
        require(!HdrSemanticState.isRequested(), "semantic MRT defaults off");
        EdrCapabilities hdr = new EdrCapabilities(1.0f, 4.0f);
        HdrSemanticState.configure(HdrMode.AUTO, hdr);
        require(HdrSemanticState.isRequested(), "auto requests semantic MRT on an HDR display");
        HdrSemanticState.configure(HdrMode.SCENE, hdr);
        require(HdrSemanticState.isRequested(), "scene mode requests semantic MRT");
        HdrSemanticState.configure(HdrMode.ENHANCED, hdr);
        require(HdrSemanticState.isRequested(), "enhanced mode keeps semantic MRT");
        HdrSemanticState.configure(HdrMode.EDR, hdr);
        require(!HdrSemanticState.isRequested(), "EDR mode avoids semantic MRT");
        HdrSemanticState.configure(HdrMode.ENHANCED, EdrCapabilities.SDR);
        require(!HdrSemanticState.isRequested(), "SDR displays avoid semantic MRT");
        HdrSemanticState.configure(HdrMode.OFF, hdr);
        require(!HdrSemanticState.isRequested(), "off mode avoids semantic MRT");
    }

    private static void testSceneState() {
        Properties properties = new Properties();
        properties.setProperty("mode", "auto");
        HdrConfig automatic = HdrConfig.from(properties);
        EdrCapabilities hdr = new EdrCapabilities(1.0f, 4.0f);

        HdrSceneState.reset();
        require(!HdrSceneState.isRequested(), "FP16 scene path defaults off before device policy");
        HdrSceneState.configure(automatic, hdr);
        require(HdrSceneState.isRequested(), "auto requests the FP16 scene path on an HDR display");
        require(HdrSceneState.sourceEncoding() == HdrSourceEncoding.SRGB, "scene source contract follows configuration");
        HdrSceneState.configure(automatic, EdrCapabilities.SDR);
        require(!HdrSceneState.isRequested(), "FP16 scene path stays off on SDR displays");

        properties.setProperty("mode", "scene");
        HdrSceneState.configure(HdrConfig.from(properties), hdr);
        require(HdrSceneState.isRequested(), "explicit scene mode requests FP16 without the legacy flag");

        properties.setProperty("mode", "enhanced");
        HdrSceneState.configure(HdrConfig.from(properties), hdr);
        require(!HdrSceneState.isRequested(), "enhanced mode keeps the legacy semantic compositor by default");
        properties.setProperty("experimentalFp16", "true");
        HdrSceneState.configure(HdrConfig.from(properties), hdr);
        require(HdrSceneState.isRequested(), "legacy flag still enables FP16 for enhanced mode");

        properties.setProperty("mode", "edr");
        HdrSceneState.configure(HdrConfig.from(properties), hdr);
        require(!HdrSceneState.isRequested(), "EDR mode does not request the scene compositor");

        properties.setProperty("mode", "off");
        HdrSceneState.configure(HdrConfig.from(properties), hdr);
        require(!HdrSceneState.isRequested(), "explicit SDR mode disables FP16 scene path");
        HdrSceneState.reset();
        require(HdrSceneState.sourceEncoding() == HdrSourceEncoding.SRGB, "scene source contract reset");
        require(Math.abs(HdrScreenshot.linearToSrgb(0.21404114f) - 0.5f) < 0.0001f, "linear screenshot conversion");
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
        require(patchedFragment.contains("clamp(fragColor.a, 0.0, 1.0)"), "Sodium translucent emission scales with visible alpha");
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

    private static void testVanillaShaderPatching() {
        String entityAssignment = "    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);";
        String entity = "out vec4 fragColor;\nvoid main() {\n" + entityAssignment + "\n}";
        String patchedEntity = VanillaHdrShaderPatcher.patchFragmentSource(VanillaHdrShaderPatcher.ENTITY, entity);
        require(patchedEntity.contains("#ifdef EMISSIVE\nlayout(location = 1) out vec4 metallumHdrSemantic;\n#endif"), "entity MRT declaration is emissive-only");
        require(patchedEntity.contains("#ifdef EMISSIVE\n    uint metallumHdrEmission"), "entity semantic write is emissive-only");
        require(patchedEntity.contains("clamp(fragColor.a, 0.0, 1.0) * 15.0"), "entity strength follows visible alpha");
        require(patchedEntity.contains("(48u | metallumHdrEmission)"), "entity exact semantic flags");
        require(!patchedEntity.contains("discard;"), "entity patch never changes main color coverage");
        require(VanillaHdrShaderPatcher.patchFragmentSource(VanillaHdrShaderPatcher.ENTITY, patchedEntity).equals(patchedEntity), "entity patch idempotence");

        requireVanillaPatch(
                VanillaHdrShaderPatcher.BEACON_BEAM,
                "    fragColor = apply_fog(color, fragmentDistance, fragmentDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);",
                15,
                true,
                "beacon"
        );
        requireVanillaPatch(
                VanillaHdrShaderPatcher.LIGHTNING,
                "    fragColor = vertexColor * ColorModulator * (1.0f - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd));",
                15,
                true,
                "lightning"
        );
        requireVanillaPatch(
                VanillaHdrShaderPatcher.STARS,
                "    fragColor = ColorModulator;",
                7,
                false,
                "stars"
        );
        requireVanillaPatch(
                VanillaHdrShaderPatcher.CELESTIAL,
                "    fragColor = color * ColorModulator;",
                12,
                false,
                "celestial"
        );
        String celestial = VanillaHdrShaderPatcher.patchFragmentSource(
                VanillaHdrShaderPatcher.CELESTIAL,
                "out vec4 fragColor;\nvoid main() {\n    fragColor = color * ColorModulator;\n}"
        );
        require(celestial.contains("dot(max(fragColor.rgb, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722))"), "celestial black background is luminance-gated");

        String unknown = "out vec4 fragColor;\nvoid main() {\n    fragColor = ColorModulator;\n}";
        require(VanillaHdrShaderPatcher.patchFragmentSource("core/gui", unknown).equals(unknown), "GUI shader is never patched");
        require(VanillaHdrShaderPatcher.patchFragmentSource(VanillaHdrShaderPatcher.STARS, "out vec4 fragColor;\nvoid main() {}").equals("out vec4 fragColor;\nvoid main() {}"), "anchor mismatch stays unchanged");
    }

    private static void testLightmapShaderPatching() {
        String clamp = "    color = clamp(color, 0.0, 1.0);";
        String gamma = "    vec3 notGamma = notGamma(color);\n"
                + "    color = mix(color, notGamma, lightmapInfo.BrightnessFactor);";
        String output = "    fragColor = vec4(color, 1.0);";
        String source = "void main() {\n" + clamp + "\n" + gamma + "\n" + output + "\n}";

        require(LightmapHdrShaderPatcher.isTarget("core/lightmap"), "vanilla lightmap target");
        require(!LightmapHdrShaderPatcher.isTarget("core/gui"), "non-lightmap shader is not targeted");

        String patched = LightmapHdrShaderPatcher.patchFragmentSource(source);
        require(LightmapHdrShaderPatcher.isPatched(patched), "lightmap patch marker");
        require(patched.contains("vec3 metallumHdrUnclampedColor = max(color, vec3(0.0));"), "unclamped light is preserved");
        require(patched.contains(clamp), "vanilla SDR clamp remains the base curve");
        require(patched.contains(gamma), "vanilla brightness curve remains unchanged");
        require(patched.contains("max(metallumHdrUnclampedColor - vec3(1.0), vec3(0.0))"), "only lighting excess above SDR white is extended");
        require(patched.contains("metallumHdrExcess / (vec3(1.0) + metallumHdrExcess)"), "lighting excess uses a bounded shoulder");
        require(patched.contains("fragColor = vec4(color + metallumHdrCompressedExcess, 1.0);"), "compressed excess is added after the vanilla base");
        require(LightmapHdrShaderPatcher.patchFragmentSource(patched).equals(patched), "lightmap patch idempotence");

        String missingOutput = "void main() {\n" + clamp + "\n}";
        require(LightmapHdrShaderPatcher.patchFragmentSource(missingOutput).equals(missingOutput), "missing lightmap output anchor stays unchanged");

        String duplicateClamp = "void main() {\n" + clamp + "\n" + clamp + "\n" + output + "\n}";
        require(LightmapHdrShaderPatcher.patchFragmentSource(duplicateClamp).equals(duplicateClamp), "ambiguous lightmap clamp anchors stay unchanged");

        String partialPatch = "void main() {\n    // METALLUM_HDR_LIGHTMAP_EXCESS\n" + clamp + "\n" + output + "\n}";
        require(!LightmapHdrShaderPatcher.isPatched(partialPatch), "partial lightmap patch is not accepted");
        require(LightmapHdrShaderPatcher.patchFragmentSource(partialPatch).equals(partialPatch), "partial lightmap patch fails safely");
    }

    private static void requireVanillaPatch(
            final String path,
            final String assignment,
            final int emission,
            final boolean exact,
            final String label
    ) {
        String source = "out vec4 fragColor;\nvoid main() {\n" + assignment + "\n}";
        String patched = VanillaHdrShaderPatcher.patchFragmentSource(path, source);
        require(patched.contains("layout(location = 1) out vec4 metallumHdrSemantic;"), label + " MRT output");
        require(patched.contains("clamp(fragColor.a, 0.0, 1.0) * " + emission + ".0"), label + " alpha-scaled emission");
        require(patched.contains("(" + (exact ? 48 : 16) + "u | metallumHdrEmission)"), label + " semantic flags");
        require(patched.contains("gl_FragCoord.z"), label + " depth packing");
        require(!patched.contains("fragColor.a ="), label + " main color alpha remains untouched");
        require(VanillaHdrShaderPatcher.patchFragmentSource(path, patched).equals(patched), label + " patch idempotence");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
