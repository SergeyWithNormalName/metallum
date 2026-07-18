package com.metallum.client.lighting.shader;

import com.metallum.client.hdr.MetallumMaterialShaderPatcher;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.renderer.AdvancedLightingLayout;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.SunShadowLayout;
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
    private static final ShaderDefines END_CRYSTAL_BEAM_DEFINES = ShaderDefines.builder()
            .define("ALPHA_CUTOUT", 0.1f)
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
            "sodium-solid-vsh", "31f8f71f2f960dfe65c3fba6841cc70fe7d2e67cf21003f70a92305dcb6c7ec0",
            "sodium-solid-fsh", "4ce6e4000a3330881b8ab149248e1f503d05a8f22a703e1a8b44a4fc50f25df5",
            "sodium-cutout-vsh", "351359cf6eb94f1d87c281cbdd047b96856955edc387a8a2ba77c1d8491423b1",
            "sodium-cutout-fsh", "50483b535ca414eae08a02e89046523154a364c989daece7e63751d39c8f2fae",
            "minecraft-entity-vsh", "66efb68cce816ffbe3238fbca265f0fd78d0b9fe5c2eb162d642803220305d82",
            "minecraft-entity-fsh", "dc7badafee7fdbb95226e1a9136f4b8fce6bc1831f6ddbfabeb0dae0e622f632"
    );

    private AdvancedDirectLightingShaderTests() {
    }

    public static void runAll() throws IOException {
        testVersionedBindingAbi();
        testDepthSliceBoundaries();
        testPowerOfTwoAddressingMatchesFloorArithmetic();
        testScaleInvariantSurfaceNormal();
        testDominantSoftShadowFilterContinuityAndBlur();
        testLightingModelIsAnIndependentVariantAxis();
        testSharedDirectFormulaAndGeometryInputs();
        testFailClosedSourceContracts();
        testTwoPhasePreflightGate();
        testActualSourcesCompileAndMatchGoldens();
        testDedicatedSunShadowVariants();
    }

    private static void testVersionedBindingAbi() {
        require(AdvancedLightingBindingAbi.VERSION == 1, "Advanced binding ABI version changed");
        require(AdvancedLightingBindingAbi.PARAMS_SLOT == 27, "params slot changed");
        require(AdvancedLightingBindingAbi.LIGHTS_SLOT == 28, "lights slot changed");
        require(AdvancedLightingBindingAbi.CLUSTER_HEADERS_SLOT == 29, "headers slot changed");
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
                        && AdvancedLightingLayout.MAX_LIGHTS_PER_CLUSTER == 256,
                "shader cluster constants diverged from generation layout");
        require(EnvironmentShadowBindingAbi.VERSION == 1
                        && EnvironmentShadowBindingAbi.PARAMS_SLOT == 26
                        && EnvironmentShadowBindingAbi.PARAMS_BYTES == 384
                        && java.util.Arrays.equals(
                        EnvironmentShadowBindingAbi.shadowTextureSlots(),
                        new int[]{13, 14, 15})
                        && SunShadowLayout.MAX_CASCADES == 3,
                "L4 environment/shadow binding ABI changed");
        require(VoxelShadowBindingAbi.VERSION == 3
                        && VoxelShadowBindingAbi.VISIBILITY_CACHE_BUFFER_SLOT == 14
                        && VoxelShadowBindingAbi.PROXY_BUFFER_SLOT == 15
                        && VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT == 16
                        && java.util.Arrays.equals(
                        VoxelShadowBindingAbi.occupancyTextureSlots(),
                        new int[]{17, 18, 19})
                        && java.util.Arrays.equals(
                        VoxelShadowBindingAbi.opticalTextureSlots(),
                        new int[]{20, 21, 22})
                        && java.util.Arrays.equals(
                        VoxelShadowBindingAbi.metadataBufferSlots(),
                        new int[]{23, 24, 25})
                        && VoxelShadowBindingAbi.SHADOW_REF_BUFFER_SLOT == 13
                        && VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_STRIDE_BYTES == 16
                        && VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_STATE_OFFSET == 0
                        && VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_ATLAS_OFFSET_LO_OFFSET == 4
                        && VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_ATLAS_OFFSET_HI_OFFSET == 8
                        && VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_PAGE_EDGE_OFFSET == 12
                        && VoxelShadowBindingAbi.SHADOW_REF_STATE_APPROXIMATE_DIRECT == 0
                        && VoxelShadowBindingAbi.SHADOW_REF_STATE_READY == 1
                        && VoxelShadowBindingAbi.SHADOW_REF_STATE_STALE_RETAINED == 2
                        && VoxelShadowBindingAbi.SHADOW_REF_STATE_BUILDING == 3
                        && VoxelShadowBindingAbi.ownsFragmentSlot(13)
                        && !VoxelShadowBindingAbi.ownsFragmentSlot(31),
                "L6 local-shadow binding slots changed");
        require(VoxelShadowBindingAbi.PARAMS_BYTES == 256
                        && VoxelShadowBindingAbi.PROXY_STRIDE_BYTES == 32
                        && VoxelShadowBindingAbi.WORLD_FROM_VIEW_MATRIX_OFFSET == 0
                        && VoxelShadowBindingAbi.CAMERA_BLOCK_AND_FLAGS_OFFSET == 64
                        && VoxelShadowBindingAbi.ATLAS_HIT_CAPACITY_OFFSET == 76
                        && VoxelShadowBindingAbi.CAMERA_FRACTION_AND_MIN_TRANSMITTANCE_OFFSET == 80
                        && VoxelShadowBindingAbi.CAPS_OFFSET == 96
                        && VoxelShadowBindingAbi.PROXY_AND_FRAME_OFFSET == 112
                        && VoxelShadowBindingAbi.levelOffset(0) == 128
                        && VoxelShadowBindingAbi.levelOffset(1) == 160
                        && VoxelShadowBindingAbi.levelOffset(2) == 192
                        && VoxelShadowBindingAbi.CONTRACT_OFFSET == 224
                        && VoxelShadowBindingAbi.WORLD_AND_FLAGS_OFFSET == 240,
                "L6 local-shadow parameter packet changed");

        AdvancedLightingBindingAbi.requireCompatibleLayout(1, 256, 48, 8, 4);
        expectIllegalArgument(() -> AdvancedLightingBindingAbi.requireCompatibleLayout(2, 256, 48, 8, 4));
        expectIllegalArgument(() -> AdvancedLightingBindingAbi.requireCompatibleLayout(1, 256, 32, 8, 4));
    }

    private static void testPowerOfTwoAddressingMatchesFloorArithmetic() {
        int[] values = {
                -500_000_000, -257, -256, -255, -33, -32, -31, -1,
                0, 1, 31, 32, 33, 255, 256, 257, 500_000_000
        };
        for (int shift = 0; shift <= 8; shift++) {
            int divisor = 1 << shift;
            int mask = divisor - 1;
            for (int value : values) {
                require((value >> shift) == Math.floorDiv(value, divisor),
                        "signed shift diverged from power-of-two floor division");
                require((value & mask) == Math.floorMod(value, divisor),
                        "power-of-two mask diverged from positive toroidal modulo");
            }
        }
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

    private static void testDominantSoftShadowFilterContinuityAndBlur() {
        double epsilon = 1.0e-8;
        for (int edge : new int[]{8, 16, 32, 64}) {
            for (int face = 0; face < 6; face++) {
                for (int cellY = 1; cellY < edge - 2; cellY++) {
                    for (int cellX = 1; cellX < edge - 2; cellX++) {
                        double cornerX = cellX + 0.5;
                        double cornerY = cellY + 0.5;
                        requireShadowWeightsContinuous(
                                edge,
                                cubeDirection(face, texelPositionUv(edge,
                                        cornerX - epsilon, cornerY + epsilon)),
                                cubeDirection(face, texelPositionUv(edge,
                                        cornerX + epsilon, cornerY - epsilon)),
                                "interior triangle diagonal");
                        requireShadowWeightsContinuous(
                                edge,
                                cubeDirection(face, texelPositionUv(edge,
                                        cornerX - epsilon, cornerY - epsilon)),
                                cubeDirection(face, texelPositionUv(edge,
                                        cornerX + epsilon, cornerY + epsilon)),
                                "interior texel corner");
                    }
                }
            }

            for (int firstAxis = 0; firstAxis < 3; firstAxis++) {
                for (int secondAxis = firstAxis + 1; secondAxis < 3; secondAxis++) {
                    int remainingAxis = 3 - firstAxis - secondAxis;
                    for (int firstSign : new int[]{-1, 1}) {
                        for (int secondSign : new int[]{-1, 1}) {
                            for (int sample = 0; sample <= 64; sample++) {
                                double remaining = -0.98 + 1.96 * sample / 64.0;
                                double[] firstSide = new double[3];
                                double[] secondSide = new double[3];
                                firstSide[firstAxis] = firstSign * (1.0 + epsilon);
                                firstSide[secondAxis] = secondSign * (1.0 - epsilon);
                                firstSide[remainingAxis] = remaining;
                                secondSide[firstAxis] = firstSign * (1.0 - epsilon);
                                secondSide[secondAxis] = secondSign * (1.0 + epsilon);
                                secondSide[remainingAxis] = remaining;
                                requireShadowWeightsContinuous(
                                        edge, firstSide, secondSide, "cubemap seam");
                            }
                        }
                    }
                }
            }

            for (int xSign : new int[]{-1, 1}) {
                for (int ySign : new int[]{-1, 1}) {
                    for (int zSign : new int[]{-1, 1}) {
                        double[][] approaches = {
                                {xSign * (1.0 + epsilon), ySign, zSign},
                                {xSign, ySign * (1.0 + epsilon), zSign},
                                {xSign, ySign, zSign * (1.0 + epsilon)}
                        };
                        requireShadowWeightsContinuous(
                                edge, approaches[0], approaches[1], "cubemap corner x/y");
                        requireShadowWeightsContinuous(
                                edge, approaches[1], approaches[2], "cubemap corner y/z");
                        requireShadowWeightsContinuous(
                                edge, approaches[2], approaches[0], "cubemap corner z/x");
                    }
                }
            }
        }

        for (int edge : new int[]{32, 64}) {
            int leftTexel = edge / 2 - 1;
            int row = edge / 2;
            Map<Integer, Double> weights = softShadowWeights(
                    edge,
                    cubeDirection(0, texelPositionUv(
                            edge, leftTexel + 0.5, row)));
            double softVisibility = 0.0;
            for (Map.Entry<Integer, Double> entry : weights.entrySet()) {
                int tapX = entry.getKey() % edge;
                if (tapX > leftTexel) {
                    softVisibility += entry.getValue();
                }
            }
            double hardVisibility = 1.0;
            require(softVisibility > 0.45 && softVisibility < 0.55
                            && Math.abs(softVisibility - hardVisibility) > 0.45,
                    "dominant soft-shadow A/B fixture did not visibly blur a hard "
                            + edge + "-texel step: soft=" + softVisibility);
        }
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

        require(countOccurrences(sodiumFragment,
                        "layout(std430, binding = 14)") == 1
                        && countOccurrences(entityFragment,
                        "layout(std430, binding = 14)") == 1,
                "L6 visibility-cache slot is not unique and fragment-only");
        for (int slot = VoxelShadowBindingAbi.PROXY_BUFFER_SLOT;
             slot <= VoxelShadowBindingAbi.METADATA_BUFFER_2_SLOT;
             slot++) {
            String declaration = "layout(std430, binding = " + slot + ")";
            require(countOccurrences(sodiumFragment, declaration) == 1
                            && countOccurrences(entityFragment, declaration) == 1
                            && !sodiumVertex.contains(declaration)
                            && !entityVertex.contains(declaration),
                    "L6 slot is not unique and fragment-only: " + slot);
        }
        String shadowRefDeclaration = "layout(std430, binding = "
                + VoxelShadowBindingAbi.SHADOW_REF_BUFFER_SLOT + ")";
        require(countOccurrences(sodiumFragment, shadowRefDeclaration) == 1
                        && countOccurrences(entityFragment, shadowRefDeclaration) == 1
                        && !sodiumVertex.contains(shadowRefDeclaration)
                        && !entityVertex.contains(shadowRefDeclaration),
                "L6 shadow-reference slot is not unique and fragment-only");

        require(sodiumVertex.contains(
                        "metallumLightingPosition = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;"),
                "Sodium terrain does not forward view-space position");
        require(sodiumVertex.contains(
                        "texture(u_LightTex, vec2(8.0 / 256.0))")
                        && sodiumVertex.contains("metallumSkyVisibility = clamp("),
                "Advanced terrain did not split sky visibility from physical irradiance");
        require(sodiumFragment.contains("cross(\n            dFdx(metallumLightingPosition),\n"
                        + "            dFdy(metallumLightingPosition))"),
                "Sodium terrain does not reconstruct its flat derivative normal");
        require(entityVertex.contains("metallumLightingNormal = mat3(ModelViewMat) * Normal;"),
                "entity normal is not forwarded in view space");
        require(entityVertex.contains(
                        "metallumLightingTint = metallumMaterialDecodeColor(Color);"),
                "entity material tint is not separated from vanilla cardinal lighting");
        require(entityVertex.contains(
                        "sample_lightmap(Sampler2, ivec2(0, 0))")
                        && entityVertex.contains("metallumSkyVisibility = clamp("),
                "Advanced entities did not split sky visibility from physical irradiance");
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
        String sodiumEnvironment = environmentHelper(sodiumFragment);
        String entityEnvironment = environmentHelper(entityFragment);
        require(sodiumFormula.equals(entityFormula),
                "terrain and entities do not use one shared direct-light formula");
        require(sodiumFormula.contains(
                        "* (attenuation * nDotL * 0.31830988618);")
                        && sodiumFormula.contains(
                        "direct += unshadowedContribution * visibility;"),
                "direct-light formula is no longer visibility-weighted Lambertian scene-linear radiance");
        require(sodiumFragment.contains("vec3 metallumSafeNormalV1(vec3 surfaceNormal)")
                        && sodiumFragment.contains("vec3 scaledNormal = surfaceNormal / normalScale;")
                        && sodiumFragment.contains(
                        "return scaledNormal * inversesqrt(lengthSquared);")
                        && !sodiumFragment.contains("normalLengthSquared <= 0.00000001"),
                "direct-light normal normalization depends on projected pixel footprint");
        require(countOccurrences(sodiumFragment,
                        "vec3 metallumDirectNormal = metallumSafeNormalV1(") == 1
                        && countOccurrences(entityFragment,
                        "vec3 metallumDirectNormal = metallumSafeNormalV1(") == 1
                        && countOccurrences(sodiumFragment,
                        "metallumSafeNormalV1(") == 2
                        && countOccurrences(entityFragment,
                        "metallumSafeNormalV1(") == 2,
                "surface normal is not prepared exactly once per fragment");
        require(!sodiumEnvironment.contains("metallumSafeNormalV1("),
                "environment helper redundantly normalizes its prepared normal");
        require(countOccurrences(sodiumFragment,
                        "vec3 metallumPreparedAlbedo =") == 1
                        && countOccurrences(entityFragment,
                        "vec3 metallumPreparedAlbedo =") == 1,
                "albedo is not prepared exactly once per fragment");
        require(sodiumFormula.contains("vec3 unshadowedContribution = albedo")
                        && !sodiumFormula.contains("direct += max(linearAlbedo"),
                "clustered-light loop redundantly clamps prepared albedo");
        require(sodiumFormula.contains(
                        "min(header.count, metallumLighting.extentAndClusterCap.z)"),
                "fragment loop lost its hard per-cluster bound");
        require(sodiumFormula.contains("MetallumClusterHeaderV1 header =")
                        && sodiumFormula.contains(
                        "metallumClusterHeaderBuffer.headers[cluster]")
                        && sodiumFormula.contains("header.offset > indexCapacity")
                        && sodiumFormula.contains(
                        "header.count > indexCapacity - header.offset"),
                "fragment loop can read outside the compact header/index allocation");
        require(sodiumFormula.contains("metallumClusterIndexBuffer.indices[")
                        && sodiumFormula.contains("header.offset + candidate]"),
                "tile-local compact index enumeration is missing");
        require(countOccurrences(sodiumFormula, "evaluated += 1u;") == 1
                        && before(sodiumFormula,
                        "distanceSquared >= radius * radius",
                        "evaluated += 1u;"),
                "conservative cluster candidates can consume the exact-light evaluation cap");
        require(sodiumFormula.contains("lightIndex >= activeLightCount"),
                "fragment loop can read beyond the uploaded light count");
        require(before(sodiumFormula, "activeLightCount == 0u",
                        "metallumClusterHeaderBuffer.headers["),
                "zero-light frames can read intentionally stale cluster headers");
        require(countOccurrences(sodiumFormula, "uint activeLightCount =") == 1,
                "active light count is not cached once per fragment");
        require(sodiumFormula.contains(
                        "max(light.linearColorIntensity.rgb, vec3(0.0))"),
                "direct-light formula collapsed colored lights to a scalar");
        require(!sodiumFragment.contains("metallumVoxelShadowLightSlotV1")
                        && !sodiumFragment.contains("selected0")
                        && !sodiumFragment.contains("selected1")
                        && !sodiumFragment.contains("shadowedCap")
                        && sodiumFragment.contains(
                        "layout(std430, binding = 13) readonly buffer MetallumVoxelShadowRefsV1")
                        && sodiumFragment.contains(
                        "metallumVoxelShadowRefBuffer.refs[lightIndex]")
                        && sodiumFragment.contains("metallumVoxelShadow.caps.w > 4096u")
                        && countOccurrences(sodiumFormula,
                        "metallumVoxelVisibilityV1(") == 1
                        && countOccurrences(sodiumFormula,
                        "metallumVoxelSoftVisibilityV1(") == 1
                        && sodiumFormula.contains("shadowRef);")
                        && !sodiumFormula.contains("shadowSlot")
                        && sodiumFormula.contains(
                        "metallumVoxelShadow.caps.w != activeLightCount")
                        && sodiumFormula.contains(
                        "attenuation * nDotL * 0.31830988618")
                        && sodiumFormula.contains(
                        "direct += unshadowedContribution * visibility;")
                        && before(sodiumFormula,
                        "distanceSquared >= radius * radius",
                        "metallumVoxelVisibilityV1("),
                "every evaluated contributing light does not consult its all-visible shadow descriptor");
        require(sodiumFragment.contains(
                        "return uvec3(sourceFace, uvec2(logicalTexel));")
                        && sodiumFragment.contains(
                        "return (tap.x * cacheFaceEdge + tap.z) * cacheFaceEdge + tap.y;")
                        && !sodiumFragment.contains("float filterHalfWidth")
                        && !sodiumFragment.contains("if (cacheFaceEdge >= 32u)")
                        && sodiumFragment.contains(
                        "ivec2 lowerTexel = ivec2(floor(texelPosition));")
                        && sodiumFragment.contains(
                        "float metallumVoxelSoftCachedVisibilityV1(")
                        && sodiumFragment.contains(
                        "float metallumVoxelSoftVisibilityV1(")
                        && sodiumFragment.contains("vec4 bilinearWeight = vec4(")
                        && sodiumFragment.contains("vec4 triangleWeight;")
                        && sodiumFragment.contains(
                        "uvec2 diagonal00And11 = uvec2(min(id00, id11), max(id00, id11));")
                        && sodiumFragment.contains(
                        "uvec2 diagonal10And01 = uvec2(min(id10, id01), max(id10, id01));")
                        && sodiumFragment.contains(
                        "float interiorWeight = smoothstep(")
                        && sodiumFragment.contains("0.0, 1.5, faceEdgeDistanceTexels")
                        && sodiumFragment.contains(
                        "triangleWeight, bilinearWeight, interiorWeight")
                        && countOccurrences(sodiumFragment,
                        "float visibility0 = metallumVoxelResolvedTapVisibilityV1(") == 1
                        && countOccurrences(sodiumFragment,
                        "float visibility1 = metallumVoxelResolvedTapVisibilityV1(") == 1
                        && countOccurrences(sodiumFragment,
                        "float visibility2 = metallumVoxelResolvedTapVisibilityV1(") == 1
                        && sodiumFragment.contains(
                        "float visibility = nearestVisibility * nearestWeight")
                        && sodiumFormula.contains(
                        "float shadowScore = dot(")
                        && sodiumFormula.contains(
                        "vec3(0.2126, 0.7152, 0.0722)")
                        && sodiumFormula.contains(
                        "softShadowVisibility - softShadowHardVisibility")
                        && before(sodiumFormula,
                        "for (uint candidate = 0u; candidate < countLimit; ++candidate)",
                        "metallumVoxelSoftVisibilityV1(")
                        && !sodiumFragment.contains("bool filterAlongX"),
                "L6 dominant soft-shadow filter lost bounded full-resolution blur");
        require(sodiumFragment.contains(
                        "layout(std430, binding = 14) readonly buffer MetallumVoxelVisibilityCacheV1")
                        && sodiumFragment.contains(
                        "for (uint layer = 0u; layer < 4u; ++layer)")
                        && sodiumFragment.contains(
                        "metallumVoxelVisibilityCache.hits[firstHit + layer]")
                        && sodiumFragment.contains(
                        "uint firstHit = baseHitIndex + texelIndex * 4u;")
                        && sodiumFragment.contains(
                        "float cacheFaceEdgeFloat = float(cacheFaceEdge);")
                        && sodiumFragment.contains(
                        "* cacheFaceEdgeFloat - vec2(0.5);")
                        && sodiumFragment.contains(
                        "vec3 tapFaceUv = metallumVoxelCubeFaceUvV1(tapDirection);")
                        && sodiumFragment.contains(
                        "vec3 tapDirection = metallumVoxelCubeDirectionV1(sourceFace, logicalUv);")
                        && sodiumFragment.contains("atlasByteOffset >> 3u")
                        && sodiumFragment.contains("atlasOffsetHigh != 0u")
                        && sodiumFragment.contains("(atlasByteOffset & 255u) != 0u")
                        && !sodiumFragment.contains("(atlasByteOffset & 7u) != 0u")
                        && sodiumFragment.contains(
                        "int atlasHitCountSigned = metallumVoxelShadow.cameraBlockAndFlags.w;")
                        && !sodiumFragment.contains("metallumVoxelVisibilityCache.hits.length()")
                        && !sodiumFragment.contains("metallumVoxelShadowRefBuffer.refs.length()")
                        && sodiumFragment.contains(
                        "receiverDistance <= hitDistance + 0.08")
                        && sodiumFragment.contains(
                        "* (2.0 / cacheFaceEdgeFloat) - 1.0;")
                        && sodiumFragment.contains("vec3 receiverWorldNormal =")
                        && sodiumFragment.contains(
                        "mat3(metallumVoxelShadow.worldFromView) * normal")
                        && sodiumFragment.contains(
                        "hitDistance + 0.08 >= receiverPlaneDistance")
                        && before(sodiumFragment,
                        "hitDistance + 0.08 >= receiverPlaneDistance",
                        "visibility = hitVisibility;")
                        && sodiumFragment.contains("if (visibility <= 0.0)")
                        && !sodiumFormula.contains("metallumVoxelDdaVisibilityV1("),
                "L6 direct lighting is not sampling variable resident-atlas pages safely");
        require(sodiumFormula.contains("if (shadowState == 0u)")
                        && sodiumFormula.contains("visibility = 1.0;")
                        && sodiumFormula.contains(
                        "if (shadowState == 1u || shadowState == 2u)")
                        && before(sodiumFormula,
                        "if (shadowState == 0u)",
                        "if (shadowState == 1u || shadowState == 2u)")
                        && countOccurrences(sodiumFragment,
                        "return metallumVoxelDdaVisibilityV1(") == 0,
                "L6 approximate descriptors do not keep DDA unreachable or contribute directly");
        int ddaStart = sodiumFragment.indexOf("float metallumVoxelDdaVisibilityV1(");
        int ddaEnd = sodiumFragment.indexOf("float metallumVoxelCachedVisibilityV1(", ddaStart);
        require(ddaStart >= 0 && ddaEnd > ddaStart,
                "L6 exact DDA fallback helper is missing");
        String ddaFormula = sodiumFragment.substring(ddaStart, ddaEnd);
        require(sodiumFragment.contains(
                        "for (uint hardStep = 0u; hardStep < maxSteps; ++hardStep)")
                        && !sodiumFragment.contains("if (hardStep >= maxSteps)")
                        && !ddaFormula.contains("return 1.0;")
                        && ddaFormula.contains("return visibility;")
                        && before(ddaFormula, "return visibility;", "return 0.0;")
                        && sodiumFragment.contains(
                        "bool powerOfTwoAddressing = metallumPowerOfTwoV1(subdivisionInt)")
                        && sodiumFragment.contains(
                        "worldBlock = metallumShiftRightV1(cell, subdivisionShift);")
                        && sodiumFragment.contains(
                        "logicalBrick = metallumShiftRightV1(worldBlock, brickShift);")
                        && sodiumFragment.contains(
                        "metallumPowerOfTwoModV1(cell, logicalEdge - 1)")
                        && sodiumFragment.contains(
                        "metallumFloorDivV1(cell.x, subdivisionInt)")
                        && sodiumFragment.contains(
                        "metallumPositiveModV1(logicalBrick.x, brickDimension)")
                        && sodiumFragment.contains(
                        "metadata.w == 0u")
                        && sodiumFragment.contains(
                        "any(notEqual(ivec3(metadata.xyz), logicalBrick))")
                        && sodiumFragment.contains("opticalByteIndex >> 2u")
                        && sodiumFragment.contains(
                        "(opticalByteIndex & 3u) * 8u")
                        && sodiumFragment.contains(
                        "any(notEqual(worldBlock, lastOpticalBlock))")
                        && sodiumFragment.contains(
                        "materialClass == 1u || materialClass == 7u")
                        && sodiumFragment.contains(
                        "metallumVoxelShadow.contract.xy")
                        && sodiumFragment.contains(
                        "metallumLighting.frameIdAndGeneration.zw")
                        && sodiumFragment.contains(
                        "metallumVoxelShadow.proxyAndFrame.zw")
                        && sodiumFragment.contains(
                        "metallumLighting.frameIdAndGeneration.xy")
                        && ddaFormula.contains("vec3 receiverCameraRelative,")
                        && ddaFormula.contains("vec3 receiverWorldRelative,")
                        && ddaFormula.contains("vec3 receiverWorldNormal,")
                        && !ddaFormula.contains("receiverViewPosition")
                        && !ddaFormula.contains("receiverViewNormal"),
                "L6 DDA lost its bounded toroidal/tag/optical fail-closed contract");
        int visibilityStart = sodiumFragment.indexOf("float metallumVoxelVisibilityV1(");
        int visibilityEnd = sodiumFragment.indexOf(
                "uint metallumClusterIndexV1(", visibilityStart);
        require(visibilityStart >= 0 && visibilityEnd > visibilityStart,
                "L6 atlas visibility helper is missing");
        String visibilityFormula = sodiumFragment.substring(visibilityStart, visibilityEnd);
        String receiverPositionTransform =
                "mat3(metallumVoxelShadow.worldFromView) * viewPosition";
        String receiverNormalTransform =
                "mat3(metallumVoxelShadow.worldFromView) * normal";
        int visibilityCallStart = sodiumFormula.indexOf(
                "visibility = metallumVoxelVisibilityV1(");
        int visibilityCallEnd = sodiumFormula.indexOf(");", visibilityCallStart);
        require(visibilityCallStart >= 0 && visibilityCallEnd > visibilityCallStart,
                "L6 per-light visibility call is missing");
        String visibilityCall = sodiumFormula.substring(visibilityCallStart, visibilityCallEnd);
        require(countOccurrences(sodiumFragment, receiverPositionTransform) == 1
                        && countOccurrences(sodiumFragment, receiverNormalTransform) == 1
                        && before(sodiumFormula,
                        "vec3 receiverCameraRelative =",
                        "for (uint candidate = 0u; candidate < countLimit; ++candidate)")
                        && before(sodiumFormula,
                        "vec3 receiverWorldNormal =",
                        "for (uint candidate = 0u; candidate < countLimit; ++candidate)")
                        && !visibilityFormula.contains(receiverPositionTransform)
                        && !visibilityFormula.contains(receiverNormalTransform)
                        && !ddaFormula.contains(receiverPositionTransform)
                        && !ddaFormula.contains(receiverNormalTransform)
                        && before(visibilityCall,
                        "receiverCameraRelative,", "receiverWorldRelative,")
                        && before(visibilityCall,
                        "receiverWorldRelative,", "receiverWorldNormal,")
                        && before(visibilityCall,
                        "receiverWorldNormal,", "light.positionRadius.xyz,"),
                "L6 receiver shadow context is not hoisted once outside the per-light loop");
        require(sodiumFragment.contains(
                        "for (uint proxyIndex = 0u; proxyIndex < 32u; ++proxyIndex)")
                        && sodiumFragment.contains("proxyCapacity > 32u")
                        && sodiumFragment.contains(
                        "vec3 receiverCameraRelative =")
                        && sodiumFragment.contains(
                        "- metallumVoxelShadow.cameraFractionAndMinTrans.xyz")
                        && sodiumFragment.contains(
                        "all(greaterThanEqual(startWorldRelative, minimum))")
                        && sodiumFragment.contains(
                        "all(lessThanEqual(startWorldRelative, maximum))")
                        && sodiumFragment.contains(
                        "all(greaterThanEqual(endWorldRelative, minimum))")
                        && sodiumFragment.contains(
                        "all(lessThanEqual(endWorldRelative, maximum))")
                        && sodiumFragment.contains("return false;"),
                "L6 bounded proxy AABB occlusion or endpoint self-exclusion is missing");
        require(sodiumFragment.contains("metallumVoxelStepBudgetFitsV1(")
                        && sodiumFragment.contains(
                        "uint requiredSteps = cellDelta.x + cellDelta.y + cellDelta.z;")
                        && sodiumFragment.contains("requiredSteps <= maxSteps")
                        && before(sodiumFragment,
                        "metallumVoxelStepBudgetFitsV1(",
                        "selectedLevel = levelIndex;"),
                "L6 finest-level selection does not coarsen before the DDA hard cap");

        require(before(sodiumFragment,
                        "metallumEvaluateClusteredDirectV1(",
                        "fragColor = _linearFog("),
                "terrain direct lighting moved after fog");
        require(before(entityFragment,
                        "metallumEvaluateClusteredDirectV1(",
                        "fragColor = apply_fog("),
                "entity direct lighting moved after fog");
        require(before(sodiumFragment,
                        "color.rgb += metallumEvaluateEnvironmentV1(",
                        "color.rgb += metallumEvaluateClusteredDirectV1(")
                        && before(entityFragment,
                        "color.rgb += metallumEvaluateEnvironmentV1(",
                        "color.rgb += metallumEvaluateClusteredDirectV1("),
                "environment/cluster accumulation changed floating-point order");
        require(!sodiumFormula.contains("HDR") && !sodiumFormula.contains("SDR")
                        && !sodiumFormula.contains("Edr") && !sodiumFormula.contains("Output"),
                "display output leaked into the direct-light formula");
        require(sodiumEnvironment.equals(entityEnvironment)
                        && sodiumEnvironment.contains("metallumSunVisibilityV1")
                        && sodiumFragment.contains("uniform sampler2DShadow metallumSunShadow0")
                        && sodiumFragment.contains("cascadeNormalBias")
                        && sodiumFragment.contains("float receiverDepth = coordinate.z")
                        && sodiumFragment.contains(
                        "texture(shadowMap, vec3(uv, receiverDepth))")
                        && !sodiumFragment.contains("float storedDepth =")
                        && sodiumEnvironment.contains("const float shadowedSkyVisibility = 0.42;")
                        && sodiumEnvironment.contains(
                        "float skyShadow = mix(shadowedSkyVisibility, 1.0, sunVisibility);")
                        && sodiumEnvironment.contains(
                        "skyOcclusion * hemisphere * skyShadow")
                        && sodiumEnvironment.contains(
                        "float directionalWeight = skyOcclusion * nDotL;")
                        && sodiumEnvironment.contains(
                        "directionalWeight * sunVisibility")
                        && sodiumFragment.contains(
                        "visibility = mix(visibility, 1.0, blend);")
                        && sodiumFragment.contains("for (int y = -1; y <= 1; ++y)")
                        && sodiumFragment.contains("for (int x = -1; x <= 1; ++x)")
                        && sodiumFragment.contains("smoothstep(split - blendWidth, split, viewDepth)"),
                "terrain/entities do not share bounded PCF environment lighting with cascade blending");
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
        for (int slot : new int[]{13, 14, 15, 16, 17, 22, 25}) {
            String l6Collision = materialFragment.replace(
                    "out vec4 fragColor;",
                    "layout(std430, binding = " + slot
                            + ") readonly buffer ExistingL6 { uint data[]; } existingL6;\n"
                            + "out vec4 fragColor;"
            );
            AdvancedDirectLightingShaderPatcher.Result l6Result =
                    AdvancedDirectLightingShaderPatcher.patch(
                            sodiumFragmentCase.namespace(),
                            sodiumFragmentCase.path(),
                            sodiumFragmentCase.stage(),
                            LightingModel.ADVANCED,
                            l6Collision
                    );
            require(!l6Result.success()
                            && l6Result.failureReason().contains("slot " + slot),
                    "occupied L6 local-shadow buffer slot did not fail closed: " + slot);
        }
        String environmentCollision = materialFragment.replace(
                "out vec4 fragColor;",
                "layout(std430, binding = 26) readonly buffer ExistingEnvironment "
                        + "{ uint data[]; } existingEnvironment;\nout vec4 fragColor;"
        );
        require(!AdvancedDirectLightingShaderPatcher.patch(
                        sodiumFragmentCase.namespace(), sodiumFragmentCase.path(),
                        sodiumFragmentCase.stage(), LightingModel.ADVANCED,
                        environmentCollision).success(),
                "occupied L4 environment buffer slot did not fail closed");

        for (String identifier : new String[]{
                "metallumDirectNormal",
                "metallumPreparedAlbedo",
                "MetallumVoxelVisibilityCacheV1",
                "MetallumVoxelShadowRefsV1",
                "metallumVoxelDdaVisibilityV1",
                "metallumVoxelCubeFaceUvV1",
                "metallumVoxelCubeDirectionV1",
                "metallumVoxelCachedTexelVisibilityV1",
                "metallumVoxelResolveTapV1",
                "metallumVoxelTapIdV1",
                "metallumVoxelResolvedTapVisibilityV1",
                "metallumVoxelCachedVisibilityV1",
                "metallumVoxelSoftCachedVisibilityV1",
                "metallumVoxelVisibilityV1",
                "metallumVoxelSoftVisibilityV1",
                "MetallumVoxelProxyV1"
        }) {
            String helperCollision = materialFragment.replace(
                    "out vec4 fragColor;",
                    "// " + identifier + "\nout vec4 fragColor;"
            );
            AdvancedDirectLightingShaderPatcher.Result helperResult =
                    AdvancedDirectLightingShaderPatcher.patch(
                            sodiumFragmentCase.namespace(),
                            sodiumFragmentCase.path(),
                            sodiumFragmentCase.stage(),
                            LightingModel.ADVANCED,
                            helperCollision
                    );
            require(!helperResult.success()
                            && helperResult.failureReason().contains(identifier),
                    "injected local collision did not fail closed: " + identifier);
        }

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
        compilePair("minecraft-end-crystal-beam", entityVertex, entityFragment,
                END_CRYSTAL_BEAM_DEFINES);
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

    private static void testDedicatedSunShadowVariants() throws IOException {
        ShaderCase[] sources = actualTargetSources();
        String sodiumVertex = sunShadowSource(sources[0]);
        String sodiumFragment = sunShadowSource(sources[1]);
        String entityVertex = sunShadowSource(sources[2]);
        String entityFragment = sunShadowSource(sources[3]);

        compileShadowPair("sodium-shadow-solid", sodiumVertex, sodiumFragment,
                SODIUM_SOLID_DEFINES);
        compileShadowPair("sodium-shadow-cutout", sodiumVertex, sodiumFragment,
                SODIUM_CUTOUT_DEFINES);
        compileShadowPair("entity-shadow-solid", entityVertex, entityFragment,
                ShaderDefines.EMPTY);
        compileShadowPair("entity-shadow-cutout", entityVertex, entityFragment,
                ENTITY_CUTOUT_DEFINES);
        compileShadowPair("entity-shadow-dissolve", entityVertex, entityFragment,
                ENTITY_DISSOLVE_DEFINES);

        String sodiumMain = mainBody(sodiumFragment);
        String entityMain = mainBody(entityFragment);
        require(sodiumMain.contains("texture(u_BlockTex, v_TexCoord).a")
                        && !sodiumMain.contains("sampleRGSS")
                        && !sodiumMain.contains("_linearFog"),
                "terrain shadow fragment retained ordinary material/fog work");
        require(entityMain.contains("texture(Sampler0, texCoord0).a")
                        && entityMain.contains("DissolveMaskSampler")
                        && !entityMain.contains("apply_fog")
                        && !entityMain.contains("ColorModulator"),
                "entity shadow fragment retained ordinary lighting/fog work");
        require(mainBody(sodiumVertex).contains("gl_Position")
                        && !mainBody(sodiumVertex).contains("u_LightTex")
                        && !mainBody(sodiumVertex).contains("getFragDistance"),
                "terrain shadow vertex retained lightmap/fog work");
        require(mainBody(entityVertex).contains("gl_Position")
                        && !mainBody(entityVertex).contains("sample_lightmap")
                        && !mainBody(entityVertex).contains("fog_spherical_distance"),
                "entity shadow vertex retained lightmap/fog work");

        SunShadowShaderPatcher.Result idempotent = SunShadowShaderPatcher.patch(
                sources[1].namespace(), sources[1].path(), sources[1].stage(), sodiumFragment);
        require(idempotent.success() && idempotent.source().equals(sodiumFragment),
                "sun-shadow patch is not idempotent");
        require(!SunShadowShaderPatcher.patch(
                        "minecraft", "core/text",
                        MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                        entityFragment).success(),
                "unsupported shader entered the L4 caster contract");
    }

    private static void compileShadowPair(
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
                    name + " produced an invalid shadow SPIR-V module");
            require(fragmentModule.uniformBuffers().stream().noneMatch(buffer ->
                            buffer.name().startsWith("Metallum")),
                    name + " retained Advanced lighting buffers in the caster fragment");
        } catch (ShaderCompileException exception) {
            throw new AssertionError(name + " shadow-source compile failed", exception);
        }
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
            require(storage.bytes().equals(Map.ofEntries(
                            Map.entry(VoxelShadowBindingAbi.VISIBILITY_CACHE_BUFFER_SLOT, 8L),
                            Map.entry(VoxelShadowBindingAbi.PROXY_BUFFER_SLOT, 32L),
                            Map.entry(VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT, 256L),
                            Map.entry(17, 4L), Map.entry(18, 4L), Map.entry(19, 4L),
                            Map.entry(20, 4L), Map.entry(21, 4L), Map.entry(22, 4L),
                            Map.entry(23, 16L), Map.entry(24, 16L), Map.entry(25, 16L),
                            Map.entry(EnvironmentShadowBindingAbi.PARAMS_SLOT,
                                    (long) EnvironmentShadowBindingAbi.PARAMS_BYTES),
                            Map.entry(AdvancedLightingBindingAbi.PARAMS_SLOT,
                                    (long) AdvancedLightingBindingAbi.PARAMS_BYTES),
                            Map.entry(AdvancedLightingBindingAbi.LIGHTS_SLOT,
                                    (long) AdvancedLightingBindingAbi.GPU_LIGHT_STRIDE),
                            Map.entry(AdvancedLightingBindingAbi.CLUSTER_HEADERS_SLOT,
                                    (long) AdvancedLightingBindingAbi.CLUSTER_HEADER_STRIDE),
                            Map.entry(AdvancedLightingBindingAbi.CLUSTER_INDICES_SLOT,
                                    (long) AdvancedLightingBindingAbi.CLUSTER_INDEX_STRIDE),
                            Map.entry(VoxelShadowBindingAbi.SHADOW_REF_BUFFER_SLOT,
                                    (long) VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_STRIDE_BYTES)
                    )),
                    name + " compiled storage-buffer ABI changed: " + storage.bytes());
            require(storage.paramsOffsets().equals(List.of(
                            0, 64, 128, 144, 160, 176, 192, 208, 224, 240)),
                    name + " compiled params offsets changed: " + storage.paramsOffsets());
            require(storage.paramsMatrixStrides().equals(List.of(16, 16)),
                    name + " compiled params matrix stride changed: "
                            + storage.paramsMatrixStrides());
            require(storage.voxelParamsOffsets().equals(List.of(
                            0, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 240)),
                    name + " compiled L6 params offsets changed: "
                            + storage.voxelParamsOffsets());
            require(storage.voxelParamsMatrixStride() == 16,
                    name + " compiled L6 matrix stride changed: "
                            + storage.voxelParamsMatrixStride());
            String fragmentMsl = toDecorationBoundMsl(fragmentModule);
            require(!fragmentMsl.contains("spvBufferSizeConstants"),
                    name + " unexpectedly requires an unbound SPIRV-Cross size buffer");
            for (int slot : AdvancedLightingBindingAbi.fragmentSlots()) {
                require(fragmentMsl.contains("[[buffer(" + slot + ")]]"),
                        name + " SPIRV-Cross output lost Metal fragment slot " + slot);
            }
            require(fragmentMsl.contains("[[buffer(26)]]"),
                    name + " SPIRV-Cross output lost L4 environment slot");
            require(countOccurrences(fragmentMsl, "[[buffer(14)]]") == 1,
                    name + " SPIRV-Cross output lost L6 visibility-cache slot");
            for (int slot = VoxelShadowBindingAbi.PROXY_BUFFER_SLOT;
                 slot <= VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT;
                 slot++) {
                require(countOccurrences(fragmentMsl, "[[buffer(" + slot + ")]]") == 1,
                        name + " SPIRV-Cross output lost or repeated active L6 fragment slot " + slot);
            }
            for (int slot = VoxelShadowBindingAbi.OCCUPANCY_TEXTURE_0_SLOT;
                 slot <= VoxelShadowBindingAbi.METADATA_BUFFER_2_SLOT;
                 slot++) {
                int occurrences = countOccurrences(fragmentMsl, "[[buffer(" + slot + ")]]");
                require(occurrences == 0,
                        name + " SPIRV-Cross output retained unreachable L6 DDA slot "
                                + slot + ": occurrences=" + occurrences);
            }
            require(fragmentMsl.contains("[[buffer(13)]]"),
                    name + " SPIRV-Cross output lost L6 descriptor slot");
            for (int slot : EnvironmentShadowBindingAbi.shadowTextureSlots()) {
                require(fragmentMsl.contains("[[texture(" + slot + ")]]")
                                && fragmentMsl.contains("[[sampler(" + slot + ")]]"),
                        name + " SPIRV-Cross output lost L4 shadow slot " + slot);
            }
            require(fragmentMsl.contains("depth2d<float>")
                            && fragmentMsl.contains("sample_compare"),
                    name + " lost hardware-filtered depth comparison PCF in Metal output");
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
                List<Integer> voxelParamsOffsets = new ArrayList<>();
                int voxelParamsMatrixStride = -1;
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
                    } else if (binding == VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT) {
                        int members = Spvc.spvc_type_get_num_member_types(blockType);
                        for (int member = 0; member < members; member++) {
                            IntBuffer offset = stack.mallocInt(1);
                            checkSpvc(
                                    Spvc.spvc_compiler_type_struct_member_offset(
                                            compiler, blockType, member, offset),
                                    "read L6 params member offset " + member
                            );
                            voxelParamsOffsets.add(offset.get(0));
                        }
                        IntBuffer stride = stack.mallocInt(1);
                        checkSpvc(
                                Spvc.spvc_compiler_type_struct_member_matrix_stride(
                                        compiler, blockType, 0, stride),
                                "read L6 params worldFromView matrix stride"
                        );
                        voxelParamsMatrixStride = stride.get(0);
                        PointerBuffer size = stack.mallocPointer(1);
                        checkSpvc(
                                Spvc.spvc_compiler_get_declared_struct_size(
                                        compiler, blockType, size),
                                "read L6 params block size"
                        );
                        bytes = size.get(0);
                    } else if (binding == EnvironmentShadowBindingAbi.PARAMS_SLOT) {
                        PointerBuffer size = stack.mallocPointer(1);
                        checkSpvc(
                                Spvc.spvc_compiler_get_declared_struct_size(
                                        compiler, blockType, size),
                                "read environment block size"
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
                        List.copyOf(paramsMatrixStrides),
                        List.copyOf(voxelParamsOffsets),
                        voxelParamsMatrixStride
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

    private static String sunShadowSource(final ShaderCase shader) throws IOException {
        String preprocessed = preprocess(shader.namespace(), shader.path(), shader.stage());
        SunShadowShaderPatcher.Result shadow = SunShadowShaderPatcher.patch(
                shader.namespace(), shader.path(), shader.stage(), preprocessed);
        require(shadow.success(), shader.key() + " shadow patch failed: "
                + shadow.failureReason());
        return shadow.source();
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

    private static String environmentHelper(final String source) {
        int start = source.indexOf("vec3 metallumEvaluateEnvironmentV1(");
        int end = source.indexOf("uint metallumClusterIndexV1(", start);
        require(start >= 0 && end > start, "L4 environment helper block is missing");
        return source.substring(start, end).strip();
    }

    private static String mainBody(final String source) {
        int marker = source.indexOf(SunShadowShaderPatcher.MARKER);
        int opening = source.indexOf('{', marker);
        require(marker >= 0 && opening >= 0, "sun-shadow main marker is missing");
        int depth = 0;
        for (int index = opening; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(opening + 1, index);
            }
        }
        throw new AssertionError("sun-shadow main body is unbalanced");
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

    private static void requireShadowWeightsContinuous(
            final int edge,
            final double[] firstDirection,
            final double[] secondDirection,
            final String boundary
    ) {
        Map<Integer, Double> first = softShadowWeights(edge, firstDirection);
        Map<Integer, Double> second = softShadowWeights(edge, secondDirection);
        requireShadowWeightContract(edge, first, boundary + " first side");
        requireShadowWeightContract(edge, second, boundary + " second side");
        Set<Integer> ids = new HashSet<>(first.keySet());
        ids.addAll(second.keySet());
        double difference = 0.0;
        for (int id : ids) {
            difference += Math.abs(first.getOrDefault(id, 0.0)
                    - second.getOrDefault(id, 0.0));
        }
        require(difference <= 1.0e-5,
                boundary + " is discontinuous at " + edge + " texels: L1=" + difference);
    }

    private static void requireShadowWeightContract(
            final int edge,
            final Map<Integer, Double> weights,
            final String location
    ) {
        require(!weights.isEmpty() && weights.size() <= 4,
                location + " does not use one to four taps");
        double sum = 0.0;
        for (Map.Entry<Integer, Double> entry : weights.entrySet()) {
            require(entry.getKey() >= 0 && entry.getKey() < 6 * edge * edge,
                    location + " resolved an out-of-range tap");
            require(Double.isFinite(entry.getValue()) && entry.getValue() >= 0.0,
                    location + " produced an invalid tap weight");
            sum += entry.getValue();
        }
        require(Math.abs(sum - 1.0) <= 1.0e-12,
                location + " weights do not sum to one: " + sum);
    }

    private static Map<Integer, Double> softShadowWeights(
            final int edge,
            final double[] direction
    ) {
        CubeFaceUv faceUv = cubeFaceUv(direction);
        double positionX = (faceUv.u() * 0.5 + 0.5) * edge - 0.5;
        double positionY = (faceUv.v() * 0.5 + 0.5) * edge - 0.5;
        int lowerX = (int) Math.floor(positionX);
        int lowerY = (int) Math.floor(positionY);
        double blendX = positionX - lowerX;
        double blendY = positionY - lowerY;

        ResolvedTap tap00 = resolveTap(edge, faceUv.face(), lowerX, lowerY);
        ResolvedTap tap10 = resolveTap(edge, faceUv.face(), lowerX + 1, lowerY);
        ResolvedTap tap01 = resolveTap(edge, faceUv.face(), lowerX, lowerY + 1);
        ResolvedTap tap11 = resolveTap(edge, faceUv.face(), lowerX + 1, lowerY + 1);
        int id00 = resolvedTapId(edge, tap00);
        int id10 = resolvedTapId(edge, tap10);
        int id01 = resolvedTapId(edge, tap01);
        int id11 = resolvedTapId(edge, tap11);
        int diagonal00Low = Math.min(id00, id11);
        int diagonal00High = Math.max(id00, id11);
        int diagonal10Low = Math.min(id10, id01);
        int diagonal10High = Math.max(id10, id01);
        boolean use00And11 = diagonal00Low < diagonal10Low
                || (diagonal00Low == diagonal10Low && diagonal00High <= diagonal10High);
        double[] bilinear = {
                (1.0 - blendX) * (1.0 - blendY),
                blendX * (1.0 - blendY),
                (1.0 - blendX) * blendY,
                blendX * blendY
        };
        double[] triangle = new double[4];
        if (use00And11 && blendX >= blendY) {
            triangle[0] = 1.0 - blendX;
            triangle[1] = blendX - blendY;
            triangle[3] = blendY;
        } else if (use00And11) {
            triangle[0] = 1.0 - blendY;
            triangle[2] = blendY - blendX;
            triangle[3] = blendX;
        } else if (blendX + blendY <= 1.0) {
            triangle[0] = 1.0 - blendX - blendY;
            triangle[1] = blendX;
            triangle[2] = blendY;
        } else {
            triangle[1] = 1.0 - blendY;
            triangle[2] = 1.0 - blendX;
            triangle[3] = blendX + blendY - 1.0;
        }
        double edgeDistanceTexels = (1.0 - Math.max(
                Math.abs(faceUv.u()), Math.abs(faceUv.v()))) * 0.5 * edge;
        double interior = smoothstep(0.0, 1.5, edgeDistanceTexels);
        int[] ids = {id00, id10, id01, id11};
        Map<Integer, Double> weights = new LinkedHashMap<>();
        for (int tap = 0; tap < ids.length; tap++) {
            addShadowWeight(
                    weights,
                    ids[tap],
                    triangle[tap] * (1.0 - interior) + bilinear[tap] * interior);
        }
        return weights;
    }

    private static void addShadowWeight(
            final Map<Integer, Double> weights,
            final int id,
            final double weight
    ) {
        if (weight > 0.0) {
            weights.merge(id, weight, Double::sum);
        }
    }

    private static ResolvedTap resolveTap(
            final int edge,
            final int sourceFace,
            final int logicalX,
            final int logicalY
    ) {
        if (logicalX >= 0 && logicalX < edge && logicalY >= 0 && logicalY < edge) {
            return new ResolvedTap(sourceFace, logicalX, logicalY);
        }
        double u = (logicalX + 0.5) * (2.0 / edge) - 1.0;
        double v = (logicalY + 0.5) * (2.0 / edge) - 1.0;
        CubeFaceUv remapped = cubeFaceUv(cubeDirection(sourceFace, new double[]{u, v}));
        int x = clamp((int) Math.floor((remapped.u() * 0.5 + 0.5) * edge), 0, edge - 1);
        int y = clamp((int) Math.floor((remapped.v() * 0.5 + 0.5) * edge), 0, edge - 1);
        return new ResolvedTap(remapped.face(), x, y);
    }

    private static int resolvedTapId(final int edge, final ResolvedTap tap) {
        return (tap.face() * edge + tap.y()) * edge + tap.x();
    }

    private static CubeFaceUv cubeFaceUv(final double[] direction) {
        double xMagnitude = Math.abs(direction[0]);
        double yMagnitude = Math.abs(direction[1]);
        double zMagnitude = Math.abs(direction[2]);
        double major = Math.max(xMagnitude, Math.max(yMagnitude, zMagnitude));
        require(major > 1.0e-12 && Double.isFinite(major),
                "invalid cubemap direction in shadow-filter contract");
        if (xMagnitude >= yMagnitude && xMagnitude >= zMagnitude) {
            return direction[0] >= 0.0
                    ? new CubeFaceUv(0, -direction[2] / major, -direction[1] / major)
                    : new CubeFaceUv(1, direction[2] / major, -direction[1] / major);
        }
        if (yMagnitude >= zMagnitude) {
            return direction[1] >= 0.0
                    ? new CubeFaceUv(2, direction[0] / major, direction[2] / major)
                    : new CubeFaceUv(3, direction[0] / major, -direction[2] / major);
        }
        return direction[2] >= 0.0
                ? new CubeFaceUv(4, direction[0] / major, -direction[1] / major)
                : new CubeFaceUv(5, -direction[0] / major, -direction[1] / major);
    }

    private static double[] cubeDirection(final int face, final double[] uv) {
        return switch (face) {
            case 0 -> new double[]{1.0, -uv[1], -uv[0]};
            case 1 -> new double[]{-1.0, -uv[1], uv[0]};
            case 2 -> new double[]{uv[0], 1.0, uv[1]};
            case 3 -> new double[]{uv[0], -1.0, -uv[1]};
            case 4 -> new double[]{uv[0], -uv[1], 1.0};
            case 5 -> new double[]{-uv[0], -uv[1], -1.0};
            default -> throw new AssertionError("invalid cubemap face " + face);
        };
    }

    private static double[] texelPositionUv(
            final int edge,
            final double positionX,
            final double positionY
    ) {
        return new double[]{
                (positionX + 0.5) * (2.0 / edge) - 1.0,
                (positionY + 0.5) * (2.0 / edge) - 1.0
        };
    }

    private static double smoothstep(
            final double lower,
            final double upper,
            final double value
    ) {
        double unit = Math.max(0.0, Math.min(1.0, (value - lower) / (upper - lower)));
        return unit * unit * (3.0 - 2.0 * unit);
    }

    private static int clamp(final int value, final int lower, final int upper) {
        return Math.max(lower, Math.min(upper, value));
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
            List<Integer> paramsMatrixStrides,
            List<Integer> voxelParamsOffsets,
            int voxelParamsMatrixStride
    ) {
    }

    private record CubeFaceUv(int face, double u, double v) {
    }

    private record ResolvedTap(int face, int x, int y) {
    }
}
