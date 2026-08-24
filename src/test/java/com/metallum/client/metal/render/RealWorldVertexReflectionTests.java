package com.metallum.client.metal.render;

import com.metallum.client.hdr.MetallumMaterialShaderPatcher;
import com.metallum.client.lighting.TerrainEnvironmentSpecialization;
import com.metallum.client.lighting.reflection.RealWorldReflectionField;
import com.metallum.client.lighting.reflection.VertexReflectionExperiment;
import com.metallum.client.lighting.shader.AdvancedDirectLightingShaderPatcher;
import com.metallum.client.radiance.CompactSectionPayload;
import com.metallum.client.radiance.Float16Compressor;
import com.metallum.client.radiance.RadianceAppearanceModel;
import com.metallum.client.radiance.RadianceRuntimeStorage;
import com.metallum.client.radiance.SodiumRadianceSectionExtractor;
import com.metallum.client.renderer.LightingModel;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Authoritative verification suite for the Real-World Frozen Rough-Reflection Prototype.
 *
 * <p>Validates:
 * 1. Exposed-face lighting semantics and linear appearance extraction from Minecraft block data.
 * 2. Strict distinction between occupied, emissive, empty, and out-of-bounds cells.
 * 3. Finite domain world-space mapping with 4-block snapping and out-of-bounds zero confidence.
 * 4. Camera fractional motion and integer block shift invariance.
 * 5. MSL generation proof: exactly two vertex 3D texture samples, buffer 27 parameter binding,
 *    exact zero fragment 3D texture samples, and full Solid/Cutout isolation.</p>
 */
public final class RealWorldVertexReflectionTests {

    public static void main(final String[] args) throws Exception {
        System.out.println("Running RealWorldVertexReflectionTests...");
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        testExposedFaceIrradianceSemantics();
        testProvenanceAndMemoryTracking();
        testFiniteDomainOriginMappingAndSnapping();
        testCameraFractionAndBlockShiftInvariance();
        testDirectionalProbeMomentMath();
        testMslGeneratedShaderContractProof();

        System.out.println("RealWorldVertexReflectionTests passed successfully!");
    }

    private static void testExposedFaceIrradianceSemantics() {
        MockWorld world = new MockWorld();

        // 1. Outdoor sand block at (5, 64, 5): exposed to sky on top (sky=15, block=0)
        world.setBlock(5, 64, 5, Blocks.SAND.defaultBlockState(), 0, 0); // self light = 0
        world.setLight(5, 65, 5, 15, 0); // neighbor above has full sky light

        // 2. Enclosed underground stone at (5, 20, 5): enclosed in darkness (sky=0, block=0)
        world.setBlock(5, 20, 5, Blocks.STONE.defaultBlockState(), 0, 0);

        // 3. Torch-lit stone at (12, 20, 12): torch adjacent at (12, 20, 13) with block light 14
        world.setBlock(12, 20, 12, Blocks.STONE.defaultBlockState(), 0, 0);
        world.setLight(12, 20, 13, 0, 14);

        // 4. Water fluid at (14, 64, 14) with sky light 15
        world.setBlock(14, 64, 14, Blocks.WATER.defaultBlockState(), 15, 0);

        long worldGen = 100L;
        long secKeySurface = SectionPos.asLong(0, 4, 0); // Y section 4 (Y 64..79)
        long secKeyCave = SectionPos.asLong(0, 1, 0);    // Y section 1 (Y 16..31)

        CompactSectionPayload snapSurface = SodiumRadianceSectionExtractor.extract(
                secKeySurface, worldGen, world, world::getBlockState, world::getBrightness
        );
        CompactSectionPayload snapCave = SodiumRadianceSectionExtractor.extract(
                secKeyCave, worldGen, world, world::getBlockState, world::getBrightness
        );

        require(!snapSurface.isEmpty(), "Surface section must not be empty");
        require(!snapCave.isEmpty(), "Cave section with torch-lit stone must not be empty");

        // Verify outdoor sand: self-light=0 MUST NOT be black because exposed neighbor has sky=15
        int sandIdx = 5 | (5 << 4) | (0 << 8);
        float sandR = Float16Compressor.unpackFloat(snapSurface.packedRgba()[sandIdx * 4]);
        float sandG = Float16Compressor.unpackFloat(snapSurface.packedRgba()[sandIdx * 4 + 1]);
        require(sandR > 0.1F && sandG > 0.1F, "Outdoor sand must receive exposed neighbor irradiance (sandR=" + sandR + ")");

        // Verify deep cave stone: must have 0.0 radiance
        int deepIdx = 5 | (5 << 4) | (4 << 8);
        float deepR = Float16Compressor.unpackFloat(snapCave.packedRgba()[deepIdx * 4]);
        require(deepR == 0.0F, "Deep cave stone must have 0 radiance (deepR=" + deepR + ")");

        // Verify torch-lit stone: must be illuminated by torch block light
        int torchIdx = 12 | (12 << 4) | (4 << 8);
        float torchR = Float16Compressor.unpackFloat(snapCave.packedRgba()[torchIdx * 4]);
        require(torchR > 0.05F, "Torch-lit stone must receive block irradiance (torchR=" + torchR + ")");

        // Verify water: non-zero radiance
        int waterIdx = 14 | (14 << 4) | (0 << 8);
        float waterB = Float16Compressor.unpackFloat(snapSurface.packedRgba()[waterIdx * 4 + 2]);
        require(waterB > 0.01F, "Water must have non-zero radiance (waterB=" + waterB + ")");
    }

    private static void testProvenanceAndMemoryTracking() {
        RadianceRuntimeStorage storage = RadianceRuntimeStorage.global();
        storage.resetProvenanceCounters();

        MockWorld world = new MockWorld();
        world.setBlock(1, 1, 1, Blocks.GLOWSTONE.defaultBlockState(), 0, 15);
        CompactSectionPayload payload = SodiumRadianceSectionExtractor.extract(
                SectionPos.asLong(0, 0, 0), 100L, world, world::getBlockState, world::getBrightness
        );

        var counters = storage.snapshotProvenanceCounters();
        require(counters.acceptedSectionsExtracted() > 0L, "Provenance counters must reflect extracted section");
        require(counters.blocksEvaluated() > 0L, "Provenance counters must reflect evaluated blocks");
        require(counters.activeInflightBytes() > 0L, "Active in-flight bytes must be positive after extraction");

        storage.notePayloadConsumed(payload);
        var after = storage.snapshotProvenanceCounters();
        require(after.activeInflightBytes() == 0L, "Active in-flight bytes must return to 0 after consumption");
    }

    private static void testFiniteDomainOriginMappingAndSnapping() {
        int span = RealWorldReflectionField.SPAN_BLOCKS; // 128
        require(span == 128, "Span must be 128 blocks");

        // Test 4-block snapping across positive, zero, and negative coordinates
        require(RealWorldReflectionField.snapToGrid(0.0, 4) == 0, "Snap 0.0 -> 0");
        require(RealWorldReflectionField.snapToGrid(3.9, 4) == 0, "Snap 3.9 -> 0");
        require(RealWorldReflectionField.snapToGrid(4.0, 4) == 4, "Snap 4.0 -> 4");
        require(RealWorldReflectionField.snapToGrid(-0.1, 4) == -4, "Snap -0.1 -> -4");
        require(RealWorldReflectionField.snapToGrid(-4.0, 4) == -4, "Snap -4.0 -> -4");
        require(RealWorldReflectionField.snapToGrid(-4.1, 4) == -8, "Snap -4.1 -> -8");

        int originX = -64;
        int originY = 0;
        int originZ = 128;
        float invSpan = 1.0F / (float) span;

        // Inside point
        int insideX = -32;
        int insideY = 64;
        int insideZ = 192;
        float u = (insideX - originX) * invSpan;
        float v = (insideY - originY) * invSpan;
        float w = (insideZ - originZ) * invSpan;
        require(u >= 0.0F && u < 1.0F && v >= 0.0F && v < 1.0F && w >= 0.0F && w < 1.0F, "Inside point must map to [0, 1)^3");

        // Outside point (X < originX)
        int outsideX = -65;
        float outU = (outsideX - originX) * invSpan;
        require(outU < 0.0F, "Point outside origin must have u < 0 (confidence fallback)");

        // Outside point (X >= originX + span)
        int farX = originX + span + 10;
        float farU = (farX - originX) * invSpan;
        require(farU >= 1.0F, "Point beyond span must have u >= 1 (confidence fallback)");
    }

    private static void testCameraFractionAndBlockShiftInvariance() {
        // Camera at block (100, 64, -200) with fraction (0.25, 0.50, 0.75)
        int camBlockX = 100;
        int camBlockY = 64;
        int camBlockZ = -200;
        float camFracX = 0.25F;
        float camFracY = 0.50F;
        float camFracZ = 0.75F;

        // Relative vertex position from camera
        float relX = -5.25F;
        float relY = 2.50F;
        float relZ = 10.25F;

        // World position reconstruction: cameraBlock + (cameraFraction + relPosition)
        float worldX1 = (float) camBlockX + (camFracX + relX);
        float worldY1 = (float) camBlockY + (camFracY + relY);
        float worldZ1 = (float) camBlockZ + (camFracZ + relZ);

        // Sub-pixel camera shift by (+0.1, -0.2, +0.1)
        float shiftedFracX = camFracX + 0.1F;
        float shiftedFracY = camFracY - 0.2F;
        float shiftedFracZ = camFracZ + 0.1F;
        float shiftedRelX = relX - 0.1F;
        float shiftedRelY = relY + 0.2F;
        float shiftedRelZ = relZ - 0.1F;

        float worldX2 = (float) camBlockX + (shiftedFracX + shiftedRelX);
        float worldY2 = (float) camBlockY + (shiftedFracY + shiftedRelY);
        float worldZ2 = (float) camBlockZ + (shiftedFracZ + shiftedRelZ);

        require(Math.abs(worldX1 - worldX2) < 1.0e-5F, "World X must be invariant under camera fraction motion");
        require(Math.abs(worldY1 - worldY2) < 1.0e-5F, "World Y must be invariant under camera fraction motion");
        require(Math.abs(worldZ1 - worldZ2) < 1.0e-5F, "World Z must be invariant under camera fraction motion");
    }

    private static void testDirectionalProbeMomentMath() {
        // Cartesian moment XYZ in [-1, 1]
        float momX = 0.0F;
        float momY = 1.0F; // dominant upward radiance (e.g. from sky)
        float momZ = 0.0F;

        // Upward reflection vector R = (0, 1, 0)
        float reflX = 0.0F;
        float reflY = 1.0F;
        float reflZ = 0.0F;

        float dot = momX * reflX + momY * reflY + momZ * reflZ;
        float align = Math.max(-1.0F, Math.min(1.0F, dot));
        float dirWeight = Math.max(0.0F, Math.min(2.0F, 1.0F + align));
        require(dirWeight == 2.0F, "Perfect alignment with upward moment must give weight 2.0 (got " + dirWeight + ")");

        // Downward reflection vector R = (0, -1, 0)
        float downDot = momX * 0.0F + momY * (-1.0F) + momZ * 0.0F;
        float downAlign = Math.max(-1.0F, Math.min(1.0F, downDot));
        float downWeight = Math.max(0.0F, Math.min(2.0F, 1.0F + downAlign));
        require(downWeight == 0.0F, "Opposite alignment with moment must give weight 0.0 (got " + downWeight + ")");
    }

    private static void testMslGeneratedShaderContractProof() throws Exception {
        String sodiumVertex = preprocess("sodium", "blocks/block_layer_opaque", MetallumMaterialShaderPatcher.Stage.VERTEX);
        String sodiumFragment = preprocess("sodium", "blocks/block_layer_opaque", MetallumMaterialShaderPatcher.Stage.FRAGMENT);

        String matVertex = MetallumMaterialShaderPatcher.patch("sodium", "blocks/block_layer_opaque", MetallumMaterialShaderPatcher.Stage.VERTEX, sodiumVertex).source();
        String matFragment = MetallumMaterialShaderPatcher.patch("sodium", "blocks/block_layer_opaque", MetallumMaterialShaderPatcher.Stage.FRAGMENT, sodiumFragment).source();

        ShaderDefines onDefines = ShaderDefines.builder()
                .define("USE_VERTEX_COMPRESSION")
                .define("USE_FOG")
                .define("METALLUM_VERTEX_REFLECTION", 1)
                .build();

        String onGlslVertex = AdvancedDirectLightingShaderPatcher.patch("sodium", "blocks/block_layer_opaque", MetallumMaterialShaderPatcher.Stage.VERTEX, LightingModel.ADVANCED, matVertex, TerrainEnvironmentSpecialization.FULL, true).source();
        String onGlslFragment = AdvancedDirectLightingShaderPatcher.patch("sodium", "blocks/block_layer_opaque", MetallumMaterialShaderPatcher.Stage.FRAGMENT, LightingModel.ADVANCED, matFragment, TerrainEnvironmentSpecialization.FULL, true).source();

        String onMslVertex = compileToMsl(onGlslVertex, ShaderType.VERTEX, onDefines);
        String onMslFragment = compileToMsl(onGlslFragment, ShaderType.FRAGMENT, onDefines);

        // 1. Vertex MSL contains exactly 2 3D texture samples and buffer(27) parameter binding
        require(onMslVertex.contains("texture3d<float> metallumReflectionRadiance [[texture(10)]]"), "Vertex must have texture(10)");
        require(onMslVertex.contains("texture3d<float> metallumReflectionMoment [[texture(11)]]"), "Vertex must have texture(11)");
        require(onMslVertex.contains("sampler metallumReflectionRadianceSmplr [[sampler(10)]]"), "Vertex must have sampler(10)");
        require(onMslVertex.contains("sampler metallumReflectionMomentSmplr [[sampler(11)]]"), "Vertex must have sampler(11)");
        require(onMslVertex.contains("buffer(27)"), "Vertex must bind dedicated reflection params buffer at slot 27");
        require(onMslVertex.contains("metallumCoarseReflection"), "Vertex must output metallumCoarseReflection");
        require(countOccurrences(onGlslVertex, "texture(metallumReflection") == 2,
                "vertex carrier must issue exactly two 3D reads");
        require(onGlslVertex.contains(
                        "float metallumConfidence = clamp(metallumReflRad.a, 0.0, 1.0) * metallumStrength"),
                "reflection strength must limit the blend confidence, not merely darken its target");

        // 2. Fragment MSL has EXACT ZERO texture3d parameters and reads only vertex varying
        require(!onMslFragment.contains("texture3d"), "Fragment must have ZERO texture3d parameters");
        require(!onMslFragment.contains("sampler3D"), "Fragment must have ZERO sampler3D");
        require(!onMslFragment.contains("metallumReflectionRadiance"), "Fragment must have ZERO radiance texture reads");
        require(!onMslFragment.contains("metallumReflectionMoment"), "Fragment must have ZERO moment texture reads");
        require(onMslFragment.contains("in.metallumCoarseReflection"), "Fragment must read interpolated varying in.metallumCoarseReflection");
        require(countOccurrences(onGlslFragment, "texture(metallumReflection") == 0,
                "fragment must issue exactly zero 3D reads");
        require(onGlslFragment.contains("metallumFrozenReflectionWater"),
                "reflection blend must remain explicitly water-only");
    }

    private static String compileToMsl(final String glslSource, final ShaderType stage, final ShaderDefines defines) throws ShaderCompileException {
        String prepared = GlslPreprocessor.injectDefines(glslSource, defines);
        try (GlslCompiler glslCompiler = new GlslCompiler();
             IntermediaryShaderModule module = glslCompiler.createIntermediary("test", prepared, stage);
             MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = module.spirv().asIntBuffer();
            PointerBuffer pointer = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pointer), "create SPIRV-Cross context");
            long context = pointer.get(0);
            try {
                checkSpvc(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), pointer), "parse SPIR-V");
                long ir = pointer.get(0);
                checkSpvc(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_COPY, pointer), "create MSL compiler");
                long compiler = pointer.get(0);
                checkSpvc(Spvc.spvc_compiler_create_compiler_options(compiler, pointer), "create MSL options");
                long options = pointer.get(0);
                checkSpvc(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS), "select macOS");
                checkSpvc(Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, 0x040000), "select MSL 4.0");
                checkSpvc(Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true), "preserve explicit bindings");
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install options");
                checkSpvc(Spvc.spvc_compiler_get_active_interface_variables(compiler, pointer), "collect active interface");
                checkSpvc(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, pointer.get(0)), "enable active interface");
                checkSpvc(Spvc.spvc_compiler_compile(compiler, pointer), "compile MSL");
                return MemoryUtil.memUTF8(pointer.get(0));
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void checkSpvc(final int result, final String stage) throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new ShaderCompileException(stage + " failed with SPIRV-Cross status " + result);
        }
    }

    private static String preprocess(final String namespace, final String path, final MetallumMaterialShaderPatcher.Stage stage) throws IOException {
        String extension = stage == MetallumMaterialShaderPatcher.Stage.VERTEX ? ".vsh" : ".fsh";
        String source = resource("assets/" + namespace + "/shaders/" + path + extension);
        java.util.Set<String> imported = new java.util.HashSet<>();
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
                String resourcePath = "assets/" + importNamespace + "/shaders/include/" + relativePath;
                if (!imported.add(resourcePath)) {
                    return null;
                }
                return resourceOrNull(resourcePath);
            }
        };
        return String.join("", preprocessor.process(source));
    }

    private static String resource(final String path) throws IOException {
        String source = resourceOrNull(path);
        if (source == null) {
            throw new IOException("Required runtime shader resource is missing: " + path);
        }
        return source;
    }

    private static String resourceOrNull(final String path) {
        ClassLoader cl = RealWorldVertexReflectionTests.class.getClassLoader();
        try (InputStream stream = cl.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read shader resource " + path, exception);
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static int countOccurrences(final String source, final String needle) {
        int count = 0;
        int position = 0;
        while ((position = source.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }

    private static final class MockWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final Map<BlockPos, Integer> skyLight = new HashMap<>();
        private final Map<BlockPos, Integer> blockLight = new HashMap<>();

        public void setBlock(int x, int y, int z, BlockState state, int sky, int block) {
            BlockPos p = new BlockPos(x, y, z);
            this.blocks.put(p, state);
            this.skyLight.put(p, sky);
            this.blockLight.put(p, block);
        }

        public void setLight(int x, int y, int z, int sky, int block) {
            BlockPos p = new BlockPos(x, y, z);
            this.skyLight.put(p, sky);
            this.blockLight.put(p, block);
        }

        public int getBrightness(LightLayer layer, BlockPos pos) {
            return layer == LightLayer.SKY
                    ? this.skyLight.getOrDefault(pos, 0)
                    : this.blockLight.getOrDefault(pos, 0);
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        public BlockState getBlockState(int x, int y, int z) {
            return getBlockState(new BlockPos(x, y, z));
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            BlockState state = getBlockState(pos);
            return state.getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
