package com.metallum.client.hdr;

import com.metallum.client.lighting.shader.AdvancedDirectLightingShaderTests;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;

/** Source-backed L2 material adapter tests for Minecraft 26.2 and Sodium 0.9.1. */
public final class MetallumMaterialContractTests {
    private static final String SODIUM_SHADER = "blocks/block_layer_opaque";

    private MetallumMaterialContractTests() {
    }

    public static void main(final String[] args) throws IOException {
        Map<MetallumMaterialPreflightGate.ShaderKey, String> sources = loadRequiredSources();
        testActualShaderCoverageAndMappings(sources);
        testVanillaLinearMaterialAdapters(sources);
        testSodiumLinearMaterialAdapter(sources);
        testPostProcessingContracts(sources);
        testNumericMaterialContracts();
        testInWorldItemEmissionContract();
        testLoadingUiContinuity();
        testFailClosedBehavior(sources);
        AdvancedDirectLightingShaderTests.runAll();
        System.out.println(
                "PASS L2/L3 material and direct-light adapters cover Minecraft 26.2 and Sodium 0.9.1 actual shader resources"
        );
    }

    private static void testActualShaderCoverageAndMappings(
            final Map<MetallumMaterialPreflightGate.ShaderKey, String> sources
    ) {
        MetallumMaterialPreflightGate.Evaluation active = MetallumMaterialPreflightGate.evaluate(
                sources,
                true,
                false,
                true
        );
        require(active.active(), "complete official shader set did not pass preflight: " + active.reason());

        require(
                HdrPipelinePolicy.requiredVanillaRasterVertexShaders().contains("core/debug_point"),
                "debug_point -> position_color vertex exception is missing"
        );
        require(
                HdrPipelinePolicy.requiredVanillaRasterVertexShaders().contains("core/screenquad"),
                "screenquad -> blit_screen vertex exception is missing"
        );
        require(
                HdrPipelinePolicy.requiredVanillaPostVertexShaders().contains("post/rotscale"),
                "post rotscale vertex role is missing"
        );
        require(
                !HdrPipelinePolicy.requiredVanillaRasterVertexShaders().contains("core/blit_screen"),
                "preflight still invents the nonexistent core/blit_screen.vsh resource"
        );

        require(
                HdrPipelinePolicy.classify(
                        "minecraft", "pipeline/debug_points",
                        "minecraft", "core/debug_point",
                        "minecraft", "core/position_color"
                ) == HdrPipelinePolicy.Role.SCENE_RASTER,
                "debug-point shader pair is not scene raster"
        );
        require(
                HdrPipelinePolicy.classify(
                        "minecraft", "pipeline/entity_outline_blit",
                        "minecraft", "core/screenquad",
                        "minecraft", "core/blit_screen"
                ) == HdrPipelinePolicy.Role.SCENE_RASTER,
                "screenquad/blit shader pair is not scene raster"
        );
        require(
                HdrPipelinePolicy.classify(
                        "minecraft", "pipeline/post/box_blur",
                        "minecraft", "post/rotscale",
                        "minecraft", "post/box_blur"
                ) == HdrPipelinePolicy.Role.SCENE_POST,
                "rotscale/post shader pair is not scene post"
        );

        for (String path : HdrPipelinePolicy.requiredVanillaRasterVertexShaders()) {
            assertPatchable(sources, "minecraft", path, MetallumMaterialShaderPatcher.Stage.VERTEX);
        }
        for (String path : HdrPipelinePolicy.requiredVanillaRasterFragmentShaders()) {
            assertPatchable(sources, "minecraft", path, MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        }
        for (String path : HdrPipelinePolicy.requiredVanillaPostVertexShaders()) {
            assertPatchable(sources, "minecraft", path, MetallumMaterialShaderPatcher.Stage.VERTEX);
        }
        for (String path : HdrPipelinePolicy.requiredVanillaPostFragmentShaders()) {
            assertPatchable(sources, "minecraft", path, MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        }
        assertPatchable(sources, "sodium", SODIUM_SHADER, MetallumMaterialShaderPatcher.Stage.VERTEX);
        assertPatchable(sources, "sodium", SODIUM_SHADER, MetallumMaterialShaderPatcher.Stage.FRAGMENT);
    }

    private static void testVanillaLinearMaterialAdapters(
            final Map<MetallumMaterialPreflightGate.ShaderKey, String> sources
    ) {
        String blockVertex = patched(sources, "minecraft", "core/block",
                MetallumMaterialShaderPatcher.Stage.VERTEX);
        require(blockVertex.contains(
                        "metallumMaterialDecodeColor(Color) * metallumMaterialDecodeLegacyLightmap(sample_lightmap"),
                "block tint/lightmap are not calibrated before linear multiplication");

        String entityVertex = patched(sources, "minecraft", "core/entity",
                MetallumMaterialShaderPatcher.Stage.VERTEX);
        require(entityVertex.contains("minecraft_mix_light_separate(-light, metallumMaterialDecodeColor(Color))"),
                "entity per-face tint is not decoded before lighting");
        require(entityVertex.contains(
                        "overlayColor = metallumMaterialDecodeColor(texelFetch(Sampler1, UV1, 0));"),
                "entity overlay RGB is not decoded");
        require(entityVertex.contains(
                        "lightMapColor = metallumMaterialDecodeLegacyLightmap(sample_lightmap(Sampler2, UV2));"),
                "entity lightmap attenuation was not calibrated for the linear material domain");

        String itemVertex = patched(sources, "minecraft", "core/item",
                MetallumMaterialShaderPatcher.Stage.VERTEX);
        require(itemVertex.contains("flat out int metallumHeldEmission;")
                        && itemVertex.contains("UV2.x >= 257 && UV2.x <= 271")
                        && itemVertex.contains("sample_lightmap(Sampler2, metallumHeldLightCoords)"),
                "in-world item emission marker is not decoded before item lightmap sampling");

        String entityFragment = patched(sources, "minecraft", "core/entity",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(entityFragment.contains(
                        "metallumMaterialDecodeColor(texture(DissolveMaskSampler, texCoord0)).a"),
                "dissolve alpha sample is not alpha-preserving");
        require(entityFragment.contains("mix(overlayColor.rgb, color.rgb, overlayColor.a)"),
                "entity overlay no longer blends in the linear material domain");
        require(entityFragment.contains("color *= lightMapColor;"),
                "entity lightmap multiplication changed");
        require(entityFragment.contains("metallumMaterialDecodeColor(FogColor)"),
                "entity fog color is not decoded before fog blending");
        require(entityFragment.contains("metallumMaterialSrgbToLinear(value.rgb), value.a"),
                "material decode no longer preserves alpha as linear coverage data");
        require(entityFragment.contains("color.rgb = max(color.rgb, metallumUnlitBase * 4.0);"),
                "entity EMISSIVE does not author exact 4x radiance");

        String itemFragment = patched(sources, "minecraft", "core/item",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(itemFragment.contains("flat in int metallumHeldEmission;")
                        && itemFragment.contains("float metallumEmission = 1.75")
                        && itemFragment.contains(
                        "color.rgb = max(color.rgb, metallumUnlitBase * metallumEmission);"),
                "in-world block items do not use scaled block-surface emission");

        String terrain = patched(sources, "minecraft", "core/terrain",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(terrain.contains("return metallumMaterialDecodeColor(textureGrad(source, uv, du, dv));"),
                "terrain nearest sample is not decoded before RGSS blending");
        require(terrain.contains("rgssColorLow += metallumMaterialDecodeColor(textureLod"),
                "terrain RGSS taps are not decoded before accumulation");
        require(terrain.contains("mix(metallumMaterialDecodeColor(FogColor)"),
                "terrain visibility fog is not linear");

        String text = patched(sources, "minecraft", "core/text",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(text.contains("vec4 texColor = texture(Sampler0, texCoord0).rrrr;"),
                "grayscale glyph coverage was decoded as sRGB color");
        require(text.contains(
                        "vec4 texColor = metallumMaterialDecodeColor(texture(Sampler0, texCoord0));"),
                "colored glyph albedo is not decoded");
        require(!text.contains("metallumMaterialDecodeColor(texture(Sampler0, texCoord0)).rrrr"),
                "coverage mask has an accidental color decode");

        String outline = patched(sources, "minecraft", "core/rendertype_outline",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(outline.contains("vec4 color = texture(Sampler0, texCoord0);"),
                "outline coverage texture should remain raw alpha data");
        require(outline.contains("metallumMaterialDecodeColor(ColorModulator).rgb"),
                "outline authored color is not decoded");

        String blit = patched(sources, "minecraft", "core/blit_screen",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(blit.contains("fragColor = texture(InSampler, texCoord);"),
                "scene blit added a second color decode");
        require(!blit.contains("metallumMaterialDecodeColor(texture(InSampler"),
                "scene blit double-decodes its linear input");

        String portal = patched(sources, "minecraft", "core/rendertype_end_portal",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(portal.contains("metallumMaterialSrgbToLinear(COLORS[0])")
                        && portal.contains("metallumMaterialSrgbToLinear(COLORS[i])"),
                "end-portal authored palette remains display encoded");

        String beacon = patched(sources, "minecraft", VanillaHdrShaderPatcher.BEACON_BEAM,
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        String lightning = patched(sources, "minecraft", VanillaHdrShaderPatcher.LIGHTNING,
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        String stars = patched(sources, "minecraft", VanillaHdrShaderPatcher.STARS,
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(beacon.contains("metallumUnlitBase * 4.0"), "beacon exact emission is absent");
        require(lightning.contains("max(color.rgb, vec3(0.0)) * 4.0"),
                "lightning exact emission is absent");
        require(stars.contains("color.rgb *= 1.8666667"), "star emission code 7 is absent");
    }

    private static void testSodiumLinearMaterialAdapter(
            final Map<MetallumMaterialPreflightGate.ShaderKey, String> sources
    ) {
        String vertex = patched(sources, "sodium", SODIUM_SHADER,
                MetallumMaterialShaderPatcher.Stage.VERTEX);
        require(vertex.contains("metallumTintColor = metallumMaterialDecodeColor(_vert_color);"),
                "Sodium vertex tint is not decoded");
        require(vertex.contains(
                        "vec4 metallumLightmap = metallumMaterialDecodeLegacyLightmap(texture(u_LightTex, _vert_tex_light_coord));"),
                "Sodium lightmap attenuation was not calibrated for the linear material domain");
        require(vertex.contains("metallumMaterial = _material_params;"),
                "Sodium material/emission bits are not forwarded");

        String fragment = patched(sources, "sodium", SODIUM_SHADER,
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(fragment.contains("return metallumMaterialDecodeColor(textureGrad(source, uv, du, dv));"),
                "Sodium nearest atlas tap is not decoded before filtering");
        require(fragment.contains("rgssColor += metallumMaterialDecodeColor(textureLod"),
                "Sodium RGSS atlas taps are not decoded before accumulation");
        require(fragment.contains("vec4 metallumAlbedo = metallumSample;"),
                "Sodium filtered albedo is decoded at the wrong boundary");
        require(!fragment.contains("metallumMaterialDecodeColor(metallumSample)"),
                "Sodium filtered albedo is double-decoded");
        require(fragment.contains("(metallumMaterial >> 3u) & 15u"),
                "Sodium emission strength bits are absent");
        require(fragment.contains("((metallumMaterial >> 7u) & 1u) != 0u"),
                "Sodium exact-emission bit is absent");
        require(fragment.contains("? 4.0 * metallumEmissionStrength"),
                "Sodium exact emission lost its full radiance range");
        require(fragment.contains(": 1.75 * metallumEmissionStrength"),
                "Sodium block emission does not scale from black");
        require(fragment.contains("color.rgb = max(color.rgb, metallumAuthoredRadiance)"),
                "Sodium emission no longer preserves the lit surface as a floor");
        require(!fragment.contains("color.rgb + metallumAuthoredRadiance"),
                "Sodium block emission still adds a duplicate albedo copy");
        require(fragment.contains("metallumMaterialDecodeColor(u_FogColor)"),
                "Sodium fog color is not decoded");
    }

    private static void testPostProcessingContracts(
            final Map<MetallumMaterialPreflightGate.ShaderKey, String> sources
    ) {
        for (String path : HdrPipelinePolicy.requiredVanillaPostFragmentShaders()) {
            String patched = patched(sources, "minecraft", path,
                    MetallumMaterialShaderPatcher.Stage.FRAGMENT);
            require(!patched.contains("metallumMaterialDecodeColor(texture"),
                    path + " double-decodes an already-linear scene input");
        }

        for (String path : new String[]{"post/bits", "post/color_convolve", "post/invert"}) {
            String patched = patched(sources, "minecraft", path,
                    MetallumMaterialShaderPatcher.Stage.FRAGMENT);
            require(SceneLinearShaderPatcher.isDisplayPostPatched(patched),
                    path + " lost its encode-operation-decode compatibility wrapper");
        }

        String blit = patched(sources, "minecraft", "post/blit",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(blit.contains("texture(InSampler, texCoord) * metallumMaterialDecodeColor(ColorModulate)"),
                "post blit does not apply its authored modulator in linear space");
        require(!SceneLinearShaderPatcher.isDisplayPostPatched(blit),
                "linear post blit received an encoded-operation wrapper");

        String transparency = patched(sources, "minecraft", "post/transparency",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT);
        require(transparency.contains("depth_layers[0] = texture(MainDepthSampler, texCoord).r;"),
                "post transparency altered raw depth data");
        require(transparency.contains("return (dst * (1.0 - src.a)) + src.rgb;"),
                "post transparency no longer blends linear premultiplied color");
    }

    private static void testNumericMaterialContracts() {
        require(close(MetallumMaterialShaderPatcher.srgbToLinear(0.5f), 0.21404114f),
                "sRGB midpoint decode");
        require(close(MetallumMaterialShaderPatcher.srgbToLinear(-0.5f), -0.21404114f),
                "extended negative sRGB decode");
        require(MetallumMaterialShaderPatcher.srgbToLinear(0.5f) < 0.22f,
                "legacy half-light attenuation remained an over-bright linear 0.5");
        require(close(MetallumMaterialShaderPatcher.emissionRadiance(0), 0.0f),
                "zero emission radiance");
        require(close(MetallumMaterialShaderPatcher.emissionRadiance(7), 28.0f / 15.0f),
                "star emission radiance");
        require(close(MetallumMaterialShaderPatcher.emissionRadiance(15), 4.0f),
                "maximum emission radiance");
        require(close(MetallumMaterialShaderPatcher.emissionRadiance(99), 4.0f),
                "emission clamp");
        require(close(MetallumMaterialShaderPatcher.blockEmissionRadiance(0), 0.0f),
                "zero block emission radiance");
        require(close(MetallumMaterialShaderPatcher.blockEmissionRadiance(1), 1.75f / 15.0f),
                "level-one block emission became nearly reference white");
        require(close(MetallumMaterialShaderPatcher.blockEmissionRadiance(14), 1.75f * 14.0f / 15.0f),
                "glow-berry block emission radiance");
        require(close(MetallumMaterialShaderPatcher.blockEmissionRadiance(15), 1.75f),
                "maximum block emission radiance");
        require(MetallumMaterialShaderPatcher.blockEmissionRadiance(15)
                        < MetallumMaterialShaderPatcher.emissionRadiance(15),
                "block-state emission retained the exact-emissive 4x peak");
        require(close(MetallumMaterialShaderPatcher.linearBlend(1.0f, 0.0f, 0.5f), 0.5f),
                "linear alpha blend");
        require(close(MetallumMaterialShaderPatcher.linearBlend(2.0f, 0.25f, 0.0f), 0.25f),
                "zero-alpha blend");
    }

    private static void testInWorldItemEmissionContract() {
        int emission = HeldItemEmission.surfaceEmission(
                14,
                ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
        );
        require(emission == 14, "held torch did not inherit its block-state emission");
        require(HeldItemEmission.surfaceEmission(
                        14,
                        ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                ) == emission,
                "third-person held emission differs from first person");
        require(HeldItemEmission.surfaceEmission(15, ItemDisplayContext.GROUND) == 15,
                "dropped block item did not inherit its block-state emission");
        require(HeldItemEmission.surfaceEmission(14, ItemDisplayContext.GUI) == 0,
                "in-world item emission leaked into the GUI");
        require(HeldItemEmission.surfaceEmission(14, ItemDisplayContext.FIXED) == 0,
                "dropped-item emission leaked into item frames");
        require(HeldItemEmission.surfaceEmission(14, ItemDisplayContext.ON_SHELF) == 0,
                "dropped-item emission leaked onto shelves");
        require(HeldItemEmission.surfaceEmission(
                        0,
                        ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                ) == 0,
                "non-emitting in-world block item received an emission marker");

        int original = LightCoordsUtil.pack(2, 5);
        int encoded = HeldItemEmission.encodeLightCoords(original, emission);
        require(HeldItemEmission.encodedEmission(encoded) == emission,
                "item emission did not round-trip through light coordinates");
        require((encoded & 0xffff0000) == (original & 0xffff0000),
                "item emission marker changed the sky-light coordinate");
        require(HeldItemEmission.encodeLightCoords(original, 0) == original,
                "zero item emission changed light coordinates");
    }

    private static void testLoadingUiContinuity() {
        require(HdrUiRenderTarget.shouldReuseActiveTarget(true, true, true),
                "panorama preparation is not reused by the following GUI draw");
        require(!HdrUiRenderTarget.shouldReuseActiveTarget(true, true, false),
                "a panorama target was reused for a different scene source");
        require(!HdrUiRenderTarget.shouldReuseActiveTarget(false, true, true),
                "an inactive panorama target bypassed normal GUI preparation");
        require(!HdrUiRenderTarget.shouldPrecomposeHdrBackdrop(true),
                "loading transition still admits adaptive HDR precompose behind SDR UI");
        require(HdrUiRenderTarget.shouldPrecomposeHdrBackdrop(false),
                "settled world UI lost its coherent HDR precompose");
        require(!HdrUiRenderTarget.shouldProcessSdrBackdropBlur(true),
                "separated HDR presentation still permits a mismatched SDR backdrop blur");
        require(HdrUiRenderTarget.shouldProcessSdrBackdropBlur(false),
                "output-only UI lost its ordinary SDR backdrop blur");
        require(HdrUiRenderTarget.shouldSuppressSceneEnhancement(true, false, false, false),
                "non-precomposed blurred UI lost its safe output-only fallback");
        require(!HdrUiRenderTarget.shouldSuppressSceneEnhancement(true, true, false, false),
                "precomposed in-world UI still forces an HDR-to-SDR presentation flash");
        require(HdrUiRenderTarget.shouldSuppressSceneEnhancement(false, true, true, false),
                "level-loading UI inherited world HDR exposure");
        require(HdrUiRenderTarget.shouldSuppressSceneEnhancement(false, true, false, true),
                "resource-loading overlay inherited world HDR exposure");
        require(!HdrUiRenderTarget.shouldSuppressSceneEnhancement(false, false, false, false),
                "ordinary in-world UI disabled HDR scene presentation");
    }

    private static void testFailClosedBehavior(
            final Map<MetallumMaterialPreflightGate.ShaderKey, String> sources
    ) {
        require(!MetallumMaterialPreflightGate.evaluate(sources, false, false, true).active(),
                "unrequested material generation activated");
        require(!MetallumMaterialPreflightGate.evaluate(sources, true, true, true).active(),
                "Iris material generation activated without an adapter");
        require(!MetallumMaterialPreflightGate.evaluate(sources, true, false, false).active(),
                "material generation activated without Sodium terrain coverage");

        Map<MetallumMaterialPreflightGate.ShaderKey, String> missingScreenquad = new HashMap<>(sources);
        missingScreenquad.remove(key("minecraft", "core/screenquad",
                MetallumMaterialShaderPatcher.Stage.VERTEX));
        MetallumMaterialPreflightGate.Evaluation missingVertex = MetallumMaterialPreflightGate.evaluate(
                missingScreenquad, true, false, true
        );
        require(!missingVertex.active() && missingVertex.reason().contains("core/screenquad VERTEX"),
                "missing real blit vertex did not fail closed");

        Map<MetallumMaterialPreflightGate.ShaderKey, String> missingPostVertex = new HashMap<>(sources);
        missingPostVertex.remove(key("minecraft", "post/rotscale",
                MetallumMaterialShaderPatcher.Stage.VERTEX));
        MetallumMaterialPreflightGate.Evaluation missingPost = MetallumMaterialPreflightGate.evaluate(
                missingPostVertex, true, false, true
        );
        require(!missingPost.active() && missingPost.reason().contains("post/rotscale VERTEX"),
                "missing real post vertex did not fail closed");

        String entity = sources.get(key("minecraft", "core/entity",
                MetallumMaterialShaderPatcher.Stage.FRAGMENT));
        String incompatibleEntity = entity.replace("#ifndef EMISSIVE\n    color *= lightMapColor;\n#endif",
                "color *= lightMapColor;");
        require(!MetallumMaterialShaderPatcher.patch(
                        "minecraft", "core/entity", MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                        incompatibleEntity
                ).success(),
                "changed entity emission contract did not fail closed");

        require(!MetallumMaterialShaderPatcher.patch(
                        "examplemod", "core/custom", MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                        "#version 330\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }"
                ).success(),
                "unknown namespace received a material adapter");
        require(!MetallumMaterialShaderPatcher.patch(
                        "minecraft", "core/lightmap", MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                        "#version 330\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }"
                ).success(),
                "lightmap data shader received a scene-color adapter");
        require(!MetallumMaterialShaderPatcher.patch(
                        "minecraft", "core/blit_screen", MetallumMaterialShaderPatcher.Stage.VERTEX,
                        "#version 330\nvoid main() { gl_Position = vec4(0.0); }"
                ).success(),
                "nonexistent blit_screen vertex role was accepted");
        require(!MetallumMaterialShaderPatcher.patch(
                        "minecraft", "core/entity", MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                        entity + "\nlayout(location = 1) out vec4 metallumHdrSemantic;\n"
                ).success(),
                "METALLUM accepted a Legacy HDR semantic MRT output");
        require(HdrUiRenderTarget.shouldRejectMaterialGenerationAfterSeedFailure(true),
                "failed METALLUM SDR UI seed did not invalidate material lighting");
        require(!HdrUiRenderTarget.shouldRejectMaterialGenerationAfterSeedFailure(false),
                "Legacy UI failure changed the lighting generation");
    }

    private static Map<MetallumMaterialPreflightGate.ShaderKey, String> loadRequiredSources()
            throws IOException {
        Map<MetallumMaterialPreflightGate.ShaderKey, String> sources = new HashMap<>();
        for (String path : HdrPipelinePolicy.requiredVanillaRasterVertexShaders()) {
            sources.put(key("minecraft", path, MetallumMaterialShaderPatcher.Stage.VERTEX),
                    resource("assets/minecraft/shaders/" + path + ".vsh"));
        }
        for (String path : HdrPipelinePolicy.requiredVanillaRasterFragmentShaders()) {
            sources.put(key("minecraft", path, MetallumMaterialShaderPatcher.Stage.FRAGMENT),
                    resource("assets/minecraft/shaders/" + path + ".fsh"));
        }
        for (String path : HdrPipelinePolicy.requiredVanillaPostVertexShaders()) {
            sources.put(key("minecraft", path, MetallumMaterialShaderPatcher.Stage.VERTEX),
                    resource("assets/minecraft/shaders/" + path + ".vsh"));
        }
        for (String path : HdrPipelinePolicy.requiredVanillaPostFragmentShaders()) {
            sources.put(key("minecraft", path, MetallumMaterialShaderPatcher.Stage.FRAGMENT),
                    resource("assets/minecraft/shaders/" + path + ".fsh"));
        }
        sources.put(key("sodium", SODIUM_SHADER, MetallumMaterialShaderPatcher.Stage.VERTEX),
                resource("assets/sodium/shaders/" + SODIUM_SHADER + ".vsh"));
        sources.put(key("sodium", SODIUM_SHADER, MetallumMaterialShaderPatcher.Stage.FRAGMENT),
                resource("assets/sodium/shaders/" + SODIUM_SHADER + ".fsh"));
        return sources;
    }

    private static void assertPatchable(
            final Map<MetallumMaterialPreflightGate.ShaderKey, String> sources,
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage
    ) {
        String original = sources.get(key(namespace, path, stage));
        require(original != null, "actual shader resource is absent: " + namespace + ':' + path + ' ' + stage);
        MetallumMaterialShaderPatcher.Result first = MetallumMaterialShaderPatcher.patch(
                namespace, path, stage, original
        );
        require(first.success(), namespace + ':' + path + ' ' + stage + " failed: " + first.failureReason());
        require(MetallumMaterialShaderPatcher.isPatched(first.source()),
                namespace + ':' + path + ' ' + stage + " has no material marker");
        MetallumMaterialShaderPatcher.Result second = MetallumMaterialShaderPatcher.patch(
                namespace, path, stage, first.source()
        );
        require(second.success() && second.source().equals(first.source()),
                namespace + ':' + path + ' ' + stage + " is not idempotent");
    }

    private static String patched(
            final Map<MetallumMaterialPreflightGate.ShaderKey, String> sources,
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage
    ) {
        MetallumMaterialShaderPatcher.Result result = MetallumMaterialShaderPatcher.patch(
                namespace,
                path,
                stage,
                sources.get(key(namespace, path, stage))
        );
        require(result.success(), namespace + ':' + path + ' ' + stage + " failed: " + result.failureReason());
        return result.source();
    }

    private static MetallumMaterialPreflightGate.ShaderKey key(
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage
    ) {
        return new MetallumMaterialPreflightGate.ShaderKey(namespace, path, stage);
    }

    private static String resource(final String path) throws IOException {
        ClassLoader loader = MetallumMaterialContractTests.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Required runtime shader resource is missing: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean close(final float actual, final float expected) {
        return Math.abs(actual - expected) < 0.00001f;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
