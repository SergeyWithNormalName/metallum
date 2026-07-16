package com.metallum.client.lighting.shader;

import com.metallum.client.hdr.MetallumMaterialShaderPatcher;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.renderer.AdvancedLightingLayout;
import com.metallum.client.renderer.LightingModel;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Actual-source and numeric contracts for the L3 terrain/entity shader adapter. */
public final class AdvancedDirectLightingShaderTests {
    private static final ShaderDefines SODIUM_SOLID_DEFINES = ShaderDefines.builder()
            .define("USE_VERTEX_COMPRESSION")
            .define("USE_FOG")
            .build();
    private static final ShaderDefines SODIUM_CUTOUT_DEFINES = ShaderDefines.builder()
            .define("USE_VERTEX_COMPRESSION")
            .define("USE_FOG")
            .define("ALPHA_CUTOUT", 0.5f)
            .build();
    private static final ShaderDefines ENTITY_CUTOUT_DEFINES = ShaderDefines.builder()
            .define("PER_FACE_LIGHTING")
            .define("ALPHA_CUTOUT", 0.1f)
            .build();
    private static final ShaderDefines ENTITY_EMISSIVE_DEFINES = ShaderDefines.builder()
            .define("EMISSIVE")
            .define("NO_OVERLAY")
            .build();
    private static final ShaderDefines ENTITY_BANNER_PATTERN_DEFINES = ShaderDefines.builder()
            .define("NO_OVERLAY")
            .build();
    private static final ShaderDefines ENTITY_ENERGY_SWIRL_DEFINES = ShaderDefines.builder()
            .define("ALPHA_CUTOUT", 0.1f)
            .define("EMISSIVE")
            .define("NO_OVERLAY")
            .define("NO_CARDINAL_LIGHTING")
            .define("APPLY_TEXTURE_MATRIX")
            .build();
    private static final ShaderDefines ENTITY_DISSOLVE_DEFINES = ShaderDefines.builder()
            .define("DISSOLVE")
            .build();

    private static final Map<String, String> EXPECTED_SOURCE_GOLDENS = Map.of(
            "sodium-solid-vsh", "f913b8ea289baab7cdf380f6d24607ff6b5b08323401211286946e1b301fc175",
            "sodium-solid-fsh", "c845722d94efc1bb462d198e70ef2529766e199604a681a6515e72b915374483",
            "sodium-cutout-vsh", "74b70909ad3131e8bb1c0f4a14634c9563ff6dc83d0f83679c0d1daef5a892ea",
            "sodium-cutout-fsh", "54513289a76471df3c60619b6b4556f9bccfda991a0417991516e03292d8c9cb",
            "minecraft-entity-vsh", "e3e387d53246ebab5353ef062370d5b9e3e1fea37ae0529d288406f6b600acd4",
            "minecraft-entity-fsh", "af12ac60f58099cb10c4a3a9907b6798b7b91b9042f9ab96beaa307c96a9861e"
    );

    private AdvancedDirectLightingShaderTests() {
    }

    public static void runAll() throws IOException {
        testVersionedBindingAbi();
        testDepthSliceBoundaries();
        testScaleInvariantSurfaceNormal();
        testLightingModelIsAnIndependentVariantAxis();
        testSharedDirectFormulaAndGeometryInputs();
        testFailClosedSourceContracts();
        testTwoPhasePreflightGate();
        testActualSourcesCompileAndMatchGoldens();
    }

    private static void testVersionedBindingAbi() {
        require(AdvancedLightingBindingAbi.VERSION == 1, "Advanced binding ABI version changed");
        require(AdvancedLightingBindingAbi.PARAMS_SLOT == 27, "params slot changed");
        require(AdvancedLightingBindingAbi.LIGHTS_SLOT == 28, "lights slot changed");
        require(AdvancedLightingBindingAbi.CLUSTER_MASKS_SLOT == 29, "masks slot changed");
        require(AdvancedLightingBindingAbi.CLUSTER_INDICES_SLOT == 30, "indices slot changed");
        require(AdvancedLightingBindingAbi.PARAMS_BYTES == 256, "params block is not 256 bytes");
        require(AdvancedLightingBindingAbi.GPU_LIGHT_STRIDE == 48, "GpuLight is not 48 bytes");
        require(AdvancedLightingBindingAbi.CLUSTER_HEADER_STRIDE == 8, "cluster header is not uint2");
        require(AdvancedLightingBindingAbi.CLUSTER_INDEX_STRIDE == 4, "cluster index is not uint");
        require(AdvancedLightingBindingAbi.LIGHT_METADATA_OFFSET == 32,
                "GpuLight metadata offset changed");
        require(AdvancedLightingBindingAbi.PARAMS_VIEW_ROTATION_OFFSET == 0
                        && AdvancedLightingBindingAbi.PARAMS_PROJECTION_OFFSET == 64
                        && AdvancedLightingBindingAbi.PARAMS_GRID_AND_LIGHT_COUNT_OFFSET == 128
                        && AdvancedLightingBindingAbi.PARAMS_EXTENT_AND_CLUSTER_CAP_OFFSET == 144
                        && AdvancedLightingBindingAbi.PARAMS_DEPTH_OFFSET == 160
                        && AdvancedLightingBindingAbi.PARAMS_FRAME_ID_AND_GENERATION_OFFSET == 176
                        && AdvancedLightingBindingAbi.PARAMS_CAPACITIES_AND_FLAGS_OFFSET == 192
                        && AdvancedLightingBindingAbi.PARAMS_RESERVED0_OFFSET == 208
                        && AdvancedLightingBindingAbi.PARAMS_RESERVED1_OFFSET == 224
                        && AdvancedLightingBindingAbi.PARAMS_RESERVED2_OFFSET == 240,
                "params member offsets diverged from the native 256-byte ABI");
        require(AdvancedLightingLayout.TILE_SIZE == 64
                        && AdvancedLightingLayout.DEPTH_SLICES == 6
                        && AdvancedLightingLayout.MAX_LIGHTS_PER_CLUSTER == 128,
                "shader cluster constants diverged from generation layout");

        AdvancedLightingBindingAbi.requireCompatibleLayout(1, 256, 48, 8, 4);
        expectIllegalArgument(() -> AdvancedLightingBindingAbi.requireCompatibleLayout(2, 256, 48, 8, 4));
        expectIllegalArgument(() -> AdvancedLightingBindingAbi.requireCompatibleLayout(1, 256, 32, 8, 4));
    }

    private static void testDepthSliceBoundaries() {
        float nearPlane = 0.05f;
        float farPlane = 1_024.0f;
        int slices = AdvancedLightingLayout.DEPTH_SLICES;
        AdvancedLightingBindingAbi.DepthCoefficients coefficients =
                AdvancedLightingBindingAbi.depthCoefficients(nearPlane, farPlane, slices);
        float middleBoundary = (float) Math.sqrt(nearPlane * farPlane);

        require(AdvancedLightingBindingAbi.depthSlice(
                        nearPlane * 0.5f, nearPlane, coefficients, slices) == 0,
                "depth below near plane did not clamp to the first cluster slice");
        require(AdvancedLightingBindingAbi.depthSlice(
                        nearPlane, nearPlane, coefficients, slices) == 0,
                "near plane did not map to the first cluster slice");
        require(AdvancedLightingBindingAbi.depthSlice(
                        middleBoundary * 0.999f, nearPlane, coefficients, slices)
                        == slices / 2 - 1,
                "depth immediately below the middle boundary left the lower middle slice");
        require(AdvancedLightingBindingAbi.depthSlice(
                        middleBoundary * 1.001f, nearPlane, coefficients, slices)
                        == slices / 2,
                "depth immediately above the middle boundary did not enter the upper middle slice");
        require(AdvancedLightingBindingAbi.depthSlice(
                        farPlane * 0.999f, nearPlane, coefficients, slices) == slices - 1,
                "depth immediately below far plane left the last cluster slice");
        require(AdvancedLightingBindingAbi.depthSlice(
                        farPlane * 2.0f, nearPlane, coefficients, slices) == slices - 1,
                "depth beyond far plane did not clamp to the last cluster slice");
    }

    private static void testScaleInvariantSurfaceNormal() {
        boolean legacyGuardWouldReject = false;
        for (float footprint : new float[]{1.0e-1F, 1.0e-2F, 1.0e-3F, 1.0e-4F, 1.0e-5F}) {
            float derivativeCrossZ = footprint * footprint;
            float legacyLengthSquared = derivativeCrossZ * derivativeCrossZ;
            legacyGuardWouldReject |= legacyLengthSquared <= 1.0e-8F;
            float[] normalized = normalizeSurfaceNormal(0.0F, 0.0F, derivativeCrossZ);
            require(normalized != null
                            && Math.abs(normalized[0]) <= 1.0e-6F
                            && Math.abs(normalized[1]) <= 1.0e-6F
                            && Math.abs(normalized[2] - 1.0F) <= 1.0e-6F,
                    "surface normal changed with projected pixel footprint " + footprint);
        }
        require(legacyGuardWouldReject,
                "normal regression fixture no longer crosses the removed fixed epsilon");
        require(normalizeSurfaceNormal(0.0F, 0.0F, 0.0F) == null
                        && normalizeSurfaceNormal(Float.NaN, 0.0F, 1.0F) == null
                        && normalizeSurfaceNormal(Float.POSITIVE_INFINITY, 0.0F, 1.0F) == null,
                "invalid derivative normals did not fail closed");
    }

    private static void testLightingModelIsAnIndependentVariantAxis() throws IOException {
        for (ShaderCase shader : actualTargetSources()) {
            String material = materialSource(shader);
            AdvancedDirectLightingShaderPatcher.Result vanilla =
                    AdvancedDirectLightingShaderPatcher.patch(
                            shader.namespace(),
                            shader.path(),
                            shader.stage(),
                            LightingModel.VANILLA,
                            material
                    );
            require(vanilla.success(), "Vanilla lighting no-op failed for " + shader.key());
            require(vanilla.source().equals(material),
                    "Vanilla lighting changed the METALLUM source for " + shader.key());
            require(!AdvancedDirectLightingShaderPatcher.isPatched(vanilla.source()),
                    "Vanilla lighting installed Advanced resources for " + shader.key());
        }

        String sodiumVertex = materialSource(actualTargetSources()[0]);
        require(sodiumVertex.contains(
                        "metallumMaterialDecodeLegacyLightmap(texture(u_LightTex, _vert_tex_light_coord))"),
                "Advanced-off terrain no longer retains the L2/Vanilla lightmap path");
        String entityVertex = materialSource(actualTargetSources()[2]);
        require(entityVertex.contains(
                        "metallumMaterialDecodeLegacyLightmap(sample_lightmap(Sampler2, UV2))"),
                "Advanced-off entity no longer retains the L2/Vanilla lightmap path");
    }

    private static void testSharedDirectFormulaAndGeometryInputs() throws IOException {
        ShaderCase[] sources = actualTargetSources();
        String sodiumVertex = advancedSource(sources[0]);
        String sodiumFragment = advancedSource(sources[1]);
        String entityVertex = advancedSource(sources[2]);
        String entityFragment = advancedSource(sources[3]);

        require(sodiumVertex.contains(
                        "metallumLightingPosition = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;"),
                "Sodium terrain does not forward view-space position");
        require(sodiumVertex.contains(
                        "texture(u_LightTex, vec2(8.0 / 256.0, _vert_tex_light_coord.y))"),
                "Advanced terrain retained the legacy block-light channel beside clustered direct light");
        require(sodiumFragment.contains("cross(\n            dFdx(metallumLightingPosition),\n"
                        + "            dFdy(metallumLightingPosition))"),
                "Sodium terrain does not reconstruct its flat derivative normal");
        require(entityVertex.contains("metallumLightingNormal = mat3(ModelViewMat) * Normal;"),
                "entity normal is not forwarded in view space");
        require(entityVertex.contains(
                        "metallumLightingTint = metallumMaterialDecodeColor(Color);"),
                "entity material tint is not separated from vanilla cardinal lighting");
        require(entityVertex.contains(
                        "sample_lightmap(Sampler2, ivec2(0, UV2.y))"),
                "Advanced entities retained the legacy block-light channel beside clustered direct light");
        require(entityFragment.contains("? metallumLightingNormal\n"
                        + "            : -metallumLightingNormal;"),
                "entity back faces do not orient the supplied normal");
        require(entityFragment.contains("color.rgb * metallumLightingTint.rgb\n"
                        + "                    * metallumMaterialDecodeColor(ColorModulator).rgb"),
                "entity direct albedo does not preserve texture and authored tint");
        require(!entityFragment.contains(
                        "metallumEntityNormal, metallumUnlitBase);"),
                "entity direct light is still modulated by vanilla cardinal/per-face lighting");

        String sodiumFormula = directHelper(sodiumFragment);
        String entityFormula = directHelper(entityFragment);
        require(sodiumFormula.equals(entityFormula),
                "terrain and entities do not use one shared direct-light formula");
        require(sodiumFormula.contains("* (attenuation * nDotL * 0.31830988618);"),
                "direct-light formula is no longer Lambertian scene-linear radiance");
        require(sodiumFormula.contains("float normalScale = max(")
                        && sodiumFormula.contains("vec3 scaledNormal = surfaceNormal / normalScale;")
                        && sodiumFormula.contains(
                        "vec3 normal = scaledNormal * inversesqrt(scaledLengthSquared);")
                        && !sodiumFormula.contains("normalLengthSquared <= 0.00000001"),
                "direct-light normal normalization depends on projected pixel footprint");
        require(sodiumFormula.contains(
                        "uint countLimit = min(metallumLighting.extentAndClusterCap.z, 128u);"),
                "fragment loop lost its hard per-cluster bound");
        require(sodiumFormula.contains("min((activeLightCount + 31u) / 32u, 8u)"),
                "fragment loop can read beyond the fixed membership-mask stride");
        require(sodiumFormula.contains("uint bit = uint(findLSB(membership));")
                        && sodiumFormula.contains("membership &= membership - 1u;"),
                "tile-local membership enumeration is not deterministic");
        require(countOccurrences(sodiumFormula, "evaluated += 1u;") == 1
                        && before(sodiumFormula,
                        "distanceSquared >= radius * radius",
                        "evaluated += 1u;"),
                "conservative cluster candidates can consume the exact-light evaluation cap");
        require(sodiumFormula.contains("lightIndex >= activeLightCount"),
                "fragment loop can read beyond the uploaded light count");
        require(before(sodiumFormula, "activeLightCount == 0u",
                        "metallumClusterMaskBuffer.membership["),
                "zero-light frames can read intentionally stale cluster masks");
        require(countOccurrences(sodiumFormula, "uint activeLightCount =") == 1,
                "active light count is not cached once per fragment");
        require(sodiumFormula.contains(
                        "max(light.linearColorIntensity.rgb, vec3(0.0))"),
                "direct-light formula collapsed colored lights to a scalar");

        require(before(sodiumFragment,
                        "metallumEvaluateClusteredDirectV1(",
                        "fragColor = _linearFog("),
                "terrain direct lighting moved after fog");
        require(before(entityFragment,
                        "metallumEvaluateClusteredDirectV1(",
                        "fragColor = apply_fog("),
                "entity direct lighting moved after fog");
        require(!sodiumFormula.contains("HDR") && !sodiumFormula.contains("SDR")
                        && !sodiumFormula.contains("Edr") && !sodiumFormula.contains("Output"),
                "display output leaked into the direct-light formula");
    }

    private static void testFailClosedSourceContracts() throws IOException {
        ShaderCase[] sources = actualTargetSources();
        ShaderCase sodiumFragmentCase = sources[1];
        String rawSodiumFragment = preprocess(
                sodiumFragmentCase.namespace(),
                sodiumFragmentCase.path(),
                sodiumFragmentCase.stage()
        );
        require(!AdvancedDirectLightingShaderPatcher.patch(
                        sodiumFragmentCase.namespace(),
                        sodiumFragmentCase.path(),
                        sodiumFragmentCase.stage(),
                        LightingModel.ADVANCED,
                        rawSodiumFragment
                ).success(),
                "Advanced accepted a source that did not pass the METALLUM material adapter");

        String materialFragment = materialSource(sodiumFragmentCase);
        require(!AdvancedDirectLightingShaderPatcher.patch(
                        "minecraft",
                        "core/text",
                        MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                        LightingModel.ADVANCED,
                        materialFragment
                ).success(),
                "unsupported render role received clustered resources");

        String slotCollision = materialFragment.replace(
                "out vec4 fragColor;",
                "layout(std430, binding = 29) readonly buffer Existing { uint data[]; } existing;\n"
                        + "out vec4 fragColor;"
        );
        AdvancedDirectLightingShaderPatcher.Result collision =
                AdvancedDirectLightingShaderPatcher.patch(
                        sodiumFragmentCase.namespace(),
                        sodiumFragmentCase.path(),
                        sodiumFragmentCase.stage(),
                        LightingModel.ADVANCED,
                        slotCollision
                );
        require(!collision.success() && collision.failureReason().contains("slot 29"),
                "occupied Advanced buffer slot did not fail closed");

        String malformedBinding = materialFragment.replace(
                "out vec4 fragColor;",
                "layout(std430, binding = 999999999999999999999) readonly buffer Broken "
                        + "{ uint data[]; } broken;\nout vec4 fragColor;"
        );
        require(!AdvancedDirectLightingShaderPatcher.patch(
                        sodiumFragmentCase.namespace(),
                        sodiumFragmentCase.path(),
                        sodiumFragmentCase.stage(),
                        LightingModel.ADVANCED,
                        malformedBinding
                ).success(),
                "malformed explicit buffer binding escaped fail-closed patching");

        String symbolicBinding = materialFragment.replace(
                "out vec4 fragColor;",
                "#define UNKNOWN_RUNTIME_SLOT 29\n"
                        + "layout(std430, binding = UNKNOWN_RUNTIME_SLOT) readonly buffer Unknown "
                        + "{ uint data[]; } unknown;\nout vec4 fragColor;"
        );
        require(!AdvancedDirectLightingShaderPatcher.patch(
                        sodiumFragmentCase.namespace(),
                        sodiumFragmentCase.path(),
                        sodiumFragmentCase.stage(),
                        LightingModel.ADVANCED,
                        symbolicBinding
                ).success(),
                "symbolic explicit buffer binding escaped fail-closed patching");

        for (String expression : new String[]{"033", "20 + 7"}) {
            String expressionBinding = materialFragment.replace(
                    "out vec4 fragColor;",
                    "layout(std430, binding = " + expression + ") readonly buffer Expression "
                            + "{ uint data[]; } expressionData;\nout vec4 fragColor;"
            );
            require(!AdvancedDirectLightingShaderPatcher.patch(
                            sodiumFragmentCase.namespace(),
                            sodiumFragmentCase.path(),
                            sodiumFragmentCase.stage(),
                            LightingModel.ADVANCED,
                            expressionBinding
                    ).success(),
                    "non-canonical binding expression escaped fail-closed patching: "
                            + expression);
        }

        String missingFog = materialFragment.replace(
                "fragColor = _linearFog(",
                "fragColor = changedFog("
        );
        require(!AdvancedDirectLightingShaderPatcher.patch(
                        sodiumFragmentCase.namespace(),
                        sodiumFragmentCase.path(),
                        sodiumFragmentCase.stage(),
                        LightingModel.ADVANCED,
                        missingFog
                ).success(),
                "changed terrain fog anchor did not fail closed");

        String advanced = advancedSource(sodiumFragmentCase);
        AdvancedDirectLightingShaderPatcher.Result idempotent =
                AdvancedDirectLightingShaderPatcher.patch(
                        sodiumFragmentCase.namespace(),
                        sodiumFragmentCase.path(),
                        sodiumFragmentCase.stage(),
                        LightingModel.ADVANCED,
                        advanced
                );
        require(idempotent.success() && idempotent.source().equals(advanced),
                "Advanced fragment adapter is not idempotent");
        String staleAdvanced = advanced.replace(
                "    return direct;",
                "    return vec3(0.0);"
        );
        require(!staleAdvanced.equals(advanced), "stale-helper mutation anchor changed");
        require(!AdvancedDirectLightingShaderPatcher.patch(
                        sodiumFragmentCase.namespace(),
                        sodiumFragmentCase.path(),
                        sodiumFragmentCase.stage(),
                        LightingModel.ADVANCED,
                        staleAdvanced
                ).success(),
                "stale Advanced marker bypassed canonical helper validation");
        require(!AdvancedDirectLightingShaderPatcher.patch(
                        sodiumFragmentCase.namespace(),
                        sodiumFragmentCase.path(),
                        sodiumFragmentCase.stage(),
                        LightingModel.VANILLA,
                        advanced
                ).success(),
                "Vanilla lighting accepted an already-Advanced shader layout");

        Map<AdvancedDirectLightingShaderPatcher.ShaderKey, String> coverage =
                new LinkedHashMap<>();
        for (ShaderCase shader : sources) {
            coverage.put(
                    new AdvancedDirectLightingShaderPatcher.ShaderKey(
                            shader.namespace(), shader.path(), shader.stage()),
                    preprocess(shader.namespace(), shader.path(), shader.stage())
            );
        }
        require(AdvancedDirectLightingShaderPatcher.preflight(coverage).ready(),
                "complete actual shader coverage failed source preflight");
        coverage.remove(new AdvancedDirectLightingShaderPatcher.ShaderKey(
                "minecraft",
                AdvancedDirectLightingShaderPatcher.VANILLA_ENTITY_PATH,
                MetallumMaterialShaderPatcher.Stage.FRAGMENT
        ));
        AdvancedDirectLightingShaderPatcher.Preflight missing =
                AdvancedDirectLightingShaderPatcher.preflight(coverage);
        require(!missing.ready() && missing.failureReason().contains("core/entity"),
                "missing entity fragment did not reject Advanced coverage");
    }

    private static void testActualSourcesCompileAndMatchGoldens() throws IOException {
        ShaderCase[] sources = actualTargetSources();
        String sodiumVertex = advancedSource(sources[0]);
        String sodiumFragment = advancedSource(sources[1]);
        String entityVertex = advancedSource(sources[2]);
        String entityFragment = advancedSource(sources[3]);

        compilePair("sodium-solid", sodiumVertex, sodiumFragment, SODIUM_SOLID_DEFINES);
        compilePair("sodium-cutout", sodiumVertex, sodiumFragment, SODIUM_CUTOUT_DEFINES);
        compilePair("minecraft-entity", entityVertex, entityFragment, ShaderDefines.EMPTY);
        compilePair("minecraft-entity-cutout", entityVertex, entityFragment,
                ENTITY_CUTOUT_DEFINES);
        compilePair("minecraft-entity-emissive", entityVertex, entityFragment,
                ENTITY_EMISSIVE_DEFINES);
        compilePair("minecraft-banner-pattern", entityVertex, entityFragment,
                ENTITY_BANNER_PATTERN_DEFINES);
        compilePair("minecraft-energy-swirl", entityVertex, entityFragment,
                ENTITY_ENERGY_SWIRL_DEFINES);
        compilePair("minecraft-entity-dissolve", entityVertex, entityFragment,
                ENTITY_DISSOLVE_DEFINES);

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("sodium-solid-vsh", digest(withDefines(sodiumVertex, SODIUM_SOLID_DEFINES)));
        actual.put("sodium-solid-fsh", digest(withDefines(sodiumFragment, SODIUM_SOLID_DEFINES)));
        actual.put("sodium-cutout-vsh", digest(withDefines(sodiumVertex, SODIUM_CUTOUT_DEFINES)));
        actual.put("sodium-cutout-fsh", digest(withDefines(sodiumFragment, SODIUM_CUTOUT_DEFINES)));
        actual.put("minecraft-entity-vsh", digest(entityVertex));
        actual.put("minecraft-entity-fsh", digest(entityFragment));
        require(actual.equals(EXPECTED_SOURCE_GOLDENS),
                "Minecraft 26.2 / Sodium 0.9.1 Advanced source golden changed: " + actual);
    }

    private static void testTwoPhasePreflightGate() throws IOException {
        AdvancedLightingRuntime.reset();
        AdvancedLightingPreflightGate.resetForTests();
        AdvancedLightingRuntime.configureRequested(true);
        AdvancedLightingRuntime.reportNativeAdmission(true, "");

        Map<AdvancedDirectLightingShaderPatcher.ShaderKey, String> coverage =
                actualCoverageSources();
        AdvancedLightingPreflightGate.Evaluation evaluated =
                AdvancedLightingPreflightGate.evaluate(coverage, true, true);
        require(evaluated.active(), "complete Advanced sources did not produce a candidate");

        AdvancedLightingPreflightGate.beginCandidate(evaluated);
        require(!AdvancedLightingPreflightGate.isActive(),
                "Advanced source candidate became visible before pipeline compilation");
        require(AdvancedLightingPreflightGate.shouldCompileAdvancedVariants(),
                "source-complete candidate did not request Advanced pipeline variants");
        require(!AdvancedLightingRuntime.admission().ready()
                        && AdvancedLightingRuntime.admission().blocker().contains("pending"),
                "pending Advanced candidate was reported shader-ready");
        require(AdvancedLightingRuntime.shouldCollect(),
                "pending shader preflight prematurely disabled light collection");

        AdvancedLightingPreflightGate.commitCandidate();
        require(AdvancedLightingPreflightGate.isActive(),
                "valid Advanced candidate did not commit");
        require(AdvancedLightingRuntime.admission().ready(),
                "committed Advanced candidate did not publish shader admission");
        AdvancedLightingRuntime.admitGeneration(true);

        AdvancedLightingPreflightGate.beginCandidate(evaluated);
        require(!AdvancedLightingRuntime.isActive(),
                "resource reload retained a partially stale Advanced generation");
        AdvancedLightingPreflightGate.rejectAdvancedVariant("synthetic Advanced PSO failure");
        require(!AdvancedLightingPreflightGate.shouldCompileAdvancedVariants(),
                "rejected Advanced candidate continued compiling optional variants");
        require(AdvancedLightingRuntime.shouldCollect(),
                "uncommitted shader rejection prematurely disabled light collection");
        AdvancedLightingPreflightGate.commitCandidate();
        require(!AdvancedLightingPreflightGate.isActive()
                        && !AdvancedLightingRuntime.admission().ready(),
                "rejected Advanced candidate became shader-ready at commit");
        require(!AdvancedLightingRuntime.shouldCollect(),
                "committed shader rejection kept registry work enabled");

        require(!AdvancedLightingPreflightGate.evaluate(coverage, false, true).active(),
                "unrequested Advanced shaders produced an active candidate");
        require(!AdvancedLightingPreflightGate.evaluate(coverage, true, false).active(),
                "Advanced shaders bypassed the full METALLUM material candidate");

        AdvancedLightingPreflightGate.resetForTests();
        AdvancedLightingRuntime.reset();
    }

    private static void compilePair(
            final String name,
            final String vertex,
            final String fragment,
            final ShaderDefines defines
    ) {
        try (GlslCompiler compiler = new GlslCompiler();
             IntermediaryShaderModule vertexModule = compiler.createIntermediary(
                     name + ".vsh", withDefines(vertex, defines), ShaderType.VERTEX);
             IntermediaryShaderModule fragmentModule = compiler.createIntermediary(
                     name + ".fsh", withDefines(fragment, defines), ShaderType.FRAGMENT)) {
            require(vertexModule.spirv() != null && fragmentModule.spirv() != null,
                    name + " produced an invalid SPIR-V module");
            require(vertexModule.outputs().stream().anyMatch(output ->
                            output.name().equals("metallumLightingPosition")),
                    name + " vertex module dropped the Advanced position varying");
            require(fragmentModule.inputs().stream().anyMatch(input ->
                            input.name().equals("metallumLightingPosition")),
                    name + " fragment module dropped the Advanced position varying");

            StorageReflection storage = storageBufferLayout(fragmentModule);
            require(storage.bytes().equals(Map.of(
                            AdvancedLightingBindingAbi.PARAMS_SLOT,
                            (long) AdvancedLightingBindingAbi.PARAMS_BYTES,
                            AdvancedLightingBindingAbi.LIGHTS_SLOT,
                            (long) AdvancedLightingBindingAbi.GPU_LIGHT_STRIDE,
                            AdvancedLightingBindingAbi.CLUSTER_MASKS_SLOT,
                            (long) AdvancedLightingBindingAbi.CLUSTER_MASK_WORD_STRIDE,
                            AdvancedLightingBindingAbi.CLUSTER_INDICES_SLOT,
                            (long) AdvancedLightingBindingAbi.CLUSTER_INDEX_STRIDE
                    )),
                    name + " compiled storage-buffer ABI changed: " + storage.bytes());
            require(storage.paramsOffsets().equals(List.of(
                            0, 64, 128, 144, 160, 176, 192, 208, 224, 240)),
                    name + " compiled params offsets changed: " + storage.paramsOffsets());
            require(storage.paramsMatrixStrides().equals(List.of(16, 16)),
                    name + " compiled params matrix stride changed: "
                            + storage.paramsMatrixStrides());
            String fragmentMsl = toDecorationBoundMsl(fragmentModule);
            for (int slot : AdvancedLightingBindingAbi.fragmentSlots()) {
                require(fragmentMsl.contains("[[buffer(" + slot + ")]]"),
                        name + " SPIRV-Cross output lost Metal fragment slot " + slot);
            }
        } catch (ShaderCompileException exception) {
            throw new AssertionError(name + " actual-source compile failed", exception);
        }
    }

    private static StorageReflection storageBufferLayout(
            final IntermediaryShaderModule module
    ) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pointer), "create SPIRV-Cross context");
            long context = pointer.get(0);
            try {
                IntBuffer words = module.spirv().asIntBuffer();
                checkSpvc(
                        Spvc.spvc_context_parse_spirv(
                                context, words, words.remaining(), pointer),
                        "parse Advanced fragment SPIR-V"
                );
                long ir = pointer.get(0);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(
                                context,
                                Spvc.SPVC_BACKEND_NONE,
                                ir,
                                Spvc.SPVC_CAPTURE_MODE_COPY,
                                pointer
                        ),
                        "create reflection compiler"
                );
                long compiler = pointer.get(0);
                checkSpvc(
                        Spvc.spvc_compiler_create_shader_resources(compiler, pointer),
                        "create storage resource list"
                );
                long resources = pointer.get(0);
                PointerBuffer count = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_resources_get_resource_list_for_type(
                                resources,
                                Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
                                pointer,
                                count
                        ),
                        "list storage buffers"
                );
                int resourceCount = (int) count.get(0);
                SpvcReflectedResource.Buffer reflected =
                        SpvcReflectedResource.create(pointer.get(0), resourceCount);
                Map<Integer, Long> layout = new LinkedHashMap<>();
                List<Integer> paramsOffsets = new ArrayList<>();
                List<Integer> paramsMatrixStrides = new ArrayList<>();
                for (int index = 0; index < resourceCount; index++) {
                    SpvcReflectedResource resource = reflected.get(index);
                    int binding = Spvc.spvc_compiler_get_decoration(
                            compiler, resource.id(), Spv.SpvDecorationBinding);
                    long blockType = Spvc.spvc_compiler_get_type_handle(
                            compiler, resource.base_type_id());
                    long bytes;
                    if (binding == AdvancedLightingBindingAbi.PARAMS_SLOT) {
                        int members = Spvc.spvc_type_get_num_member_types(blockType);
                        for (int member = 0; member < members; member++) {
                            IntBuffer offset = stack.mallocInt(1);
                            checkSpvc(
                                    Spvc.spvc_compiler_type_struct_member_offset(
                                            compiler, blockType, member, offset),
                                    "read params member offset " + member
                            );
                            paramsOffsets.add(offset.get(0));
                            if (member < 2) {
                                IntBuffer stride = stack.mallocInt(1);
                                checkSpvc(
                                        Spvc.spvc_compiler_type_struct_member_matrix_stride(
                                                compiler, blockType, member, stride),
                                        "read params matrix stride " + member
                                );
                                paramsMatrixStrides.add(stride.get(0));
                            }
                        }
                        PointerBuffer size = stack.mallocPointer(1);
                        checkSpvc(
                                Spvc.spvc_compiler_get_declared_struct_size(
                                        compiler, blockType, size),
                                "read params block size"
                        );
                        bytes = size.get(0);
                    } else {
                        IntBuffer stride = stack.mallocInt(1);
                        checkSpvc(
                                Spvc.spvc_compiler_type_struct_member_array_stride(
                                        compiler, blockType, 0, stride),
                                "read runtime-array stride at slot " + binding
                        );
                        bytes = Integer.toUnsignedLong(stride.get(0));
                    }
                    layout.put(binding, bytes);
                }
                return new StorageReflection(
                        Map.copyOf(layout),
                        List.copyOf(paramsOffsets),
                        List.copyOf(paramsMatrixStrides)
                );
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static String toDecorationBoundMsl(
            final IntermediaryShaderModule module
    ) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pointer), "create MSL SPIRV-Cross context");
            long context = pointer.get(0);
            try {
                IntBuffer words = module.spirv().asIntBuffer();
                checkSpvc(
                        Spvc.spvc_context_parse_spirv(
                                context, words, words.remaining(), pointer),
                        "parse fragment SPIR-V for MSL"
                );
                long ir = pointer.get(0);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(
                                context,
                                Spvc.SPVC_BACKEND_MSL,
                                ir,
                                Spvc.SPVC_CAPTURE_MODE_COPY,
                                pointer
                        ),
                        "create MSL compiler"
                );
                long compiler = pointer.get(0);
                checkSpvc(
                        Spvc.spvc_compiler_create_compiler_options(compiler, pointer),
                        "create MSL compiler options"
                );
                long options = pointer.get(0);
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(
                                options,
                                Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM,
                                Spvc.SPVC_MSL_PLATFORM_MACOS
                        ),
                        "select macOS MSL"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(
                                options,
                                Spvc.SPVC_COMPILER_OPTION_MSL_VERSION,
                                0x040000
                        ),
                        "select MSL 4.0"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(
                                options,
                                Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING,
                                true
                        ),
                        "preserve explicit Advanced bindings"
                );
                checkSpvc(
                        Spvc.spvc_compiler_install_compiler_options(compiler, options),
                        "install MSL options"
                );
                checkSpvc(
                        Spvc.spvc_compiler_get_active_interface_variables(compiler, pointer),
                        "collect active Advanced interface"
                );
                checkSpvc(
                        Spvc.spvc_compiler_set_enabled_interface_variables(
                                compiler, pointer.get(0)),
                        "enable active Advanced interface"
                );
                checkSpvc(Spvc.spvc_compiler_compile(compiler, pointer), "compile Advanced MSL");
                return MemoryUtil.memUTF8(pointer.get(0));
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void checkSpvc(final int result, final String stage)
            throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new ShaderCompileException(stage + " failed with SPIRV-Cross status " + result);
        }
    }

    private static ShaderCase[] actualTargetSources() throws IOException {
        return new ShaderCase[]{
                new ShaderCase("sodium-terrain-vsh", "sodium",
                        AdvancedDirectLightingShaderPatcher.SODIUM_TERRAIN_PATH,
                        MetallumMaterialShaderPatcher.Stage.VERTEX),
                new ShaderCase("sodium-terrain-fsh", "sodium",
                        AdvancedDirectLightingShaderPatcher.SODIUM_TERRAIN_PATH,
                        MetallumMaterialShaderPatcher.Stage.FRAGMENT),
                new ShaderCase("minecraft-entity-vsh", "minecraft",
                        AdvancedDirectLightingShaderPatcher.VANILLA_ENTITY_PATH,
                        MetallumMaterialShaderPatcher.Stage.VERTEX),
                new ShaderCase("minecraft-entity-fsh", "minecraft",
                        AdvancedDirectLightingShaderPatcher.VANILLA_ENTITY_PATH,
                        MetallumMaterialShaderPatcher.Stage.FRAGMENT)
        };
    }

    private static Map<AdvancedDirectLightingShaderPatcher.ShaderKey, String>
            actualCoverageSources() throws IOException {
        Map<AdvancedDirectLightingShaderPatcher.ShaderKey, String> coverage =
                new LinkedHashMap<>();
        for (ShaderCase shader : actualTargetSources()) {
            coverage.put(
                    new AdvancedDirectLightingShaderPatcher.ShaderKey(
                            shader.namespace(), shader.path(), shader.stage()),
                    preprocess(shader.namespace(), shader.path(), shader.stage())
            );
        }
        return coverage;
    }

    private static String advancedSource(final ShaderCase shader) throws IOException {
        AdvancedDirectLightingShaderPatcher.Result advanced =
                AdvancedDirectLightingShaderPatcher.patch(
                        shader.namespace(),
                        shader.path(),
                        shader.stage(),
                        LightingModel.ADVANCED,
                        materialSource(shader)
                );
        require(advanced.success(), shader.key() + " Advanced patch failed: " + advanced.failureReason());
        return advanced.source();
    }

    private static String materialSource(final ShaderCase shader) throws IOException {
        String preprocessed = preprocess(shader.namespace(), shader.path(), shader.stage());
        MetallumMaterialShaderPatcher.Result material = MetallumMaterialShaderPatcher.patch(
                shader.namespace(),
                shader.path(),
                shader.stage(),
                preprocessed
        );
        require(material.success(), shader.key() + " material patch failed: " + material.failureReason());
        return material.source();
    }

    private static String preprocess(
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage
    ) throws IOException {
        String extension = stage == MetallumMaterialShaderPatcher.Stage.VERTEX ? ".vsh" : ".fsh";
        String source = resource("assets/" + namespace + "/shaders/" + path + extension);
        Set<String> imported = new HashSet<>();
        GlslPreprocessor preprocessor = new GlslPreprocessor() {
            @Override
            public String applyImport(final boolean relative, final String importPath) {
                String importNamespace = namespace;
                String relativePath = importPath;
                int separator = importPath.indexOf(':');
                if (!relative && separator > 0) {
                    importNamespace = importPath.substring(0, separator);
                    relativePath = importPath.substring(separator + 1);
                }
                String resourcePath = "assets/" + importNamespace
                        + "/shaders/include/" + relativePath;
                if (!imported.add(resourcePath)) {
                    return null;
                }
                return resourceOrNull(resourcePath);
            }
        };
        return String.join("", preprocessor.process(source));
    }

    private static String directHelper(final String source) {
        int start = source.indexOf("uint metallumClusterIndexV1(");
        int end = source.indexOf("// METALLUM_MATERIAL_LINEAR_V1", start);
        require(start >= 0 && end > start, "Advanced direct helper block is missing");
        return source.substring(start, end).strip();
    }

    private static boolean before(final String source, final String first, final String second) {
        int firstIndex = source.lastIndexOf(first);
        int secondIndex = source.lastIndexOf(second);
        return firstIndex >= 0 && secondIndex > firstIndex;
    }

    private static int countOccurrences(final String source, final String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String withDefines(final String source, final ShaderDefines defines) {
        return GlslPreprocessor.injectDefines(source, defines);
    }

    private static String digest(final String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static String resource(final String path) throws IOException {
        String source = resourceOrNull(path);
        if (source == null) {
            throw new IOException("Required runtime shader resource is missing: " + path);
        }
        return source;
    }

    private static String resourceOrNull(final String path) {
        ClassLoader loader = AdvancedDirectLightingShaderTests.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(path)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read shader resource " + path, exception);
        }
    }

    private static float[] normalizeSurfaceNormal(
            final float x,
            final float y,
            final float z
    ) {
        float scale = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (!(scale > 0.0F) || !Float.isFinite(scale)) {
            return null;
        }
        float scaledX = x / scale;
        float scaledY = y / scale;
        float scaledZ = z / scale;
        float lengthSquared = scaledX * scaledX + scaledY * scaledY + scaledZ * scaledZ;
        if (!(lengthSquared > 0.0F) || !Float.isFinite(lengthSquared)) {
            return null;
        }
        float inverseLength = 1.0F / (float) Math.sqrt(lengthSquared);
        return new float[]{
                scaledX * inverseLength,
                scaledY * inverseLength,
                scaledZ * inverseLength
        };
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record ShaderCase(
            String key,
            String namespace,
            String path,
            MetallumMaterialShaderPatcher.Stage stage
    ) {
    }

    private record StorageReflection(
            Map<Integer, Long> bytes,
            List<Integer> paramsOffsets,
            List<Integer> paramsMatrixStrides
    ) {
    }
}
