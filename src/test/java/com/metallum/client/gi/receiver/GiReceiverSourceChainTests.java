package com.metallum.client.gi.receiver;

import java.nio.file.Files;
import java.nio.file.Path;

/** Source/generated-shader proof for the isolated vertex-only G5 implementation. */
public final class GiReceiverSourceChainTests {
    private GiReceiverSourceChainTests() {
    }

    public static void main(final String[] arguments) throws Exception {
        testVertexOnlyPatch();
        testReflectionCoexistence();
        testEmittedMslContract();
        testHotPathAllocationBoundary();
        System.out.println("G5 receiver source-chain tests passed");
    }

    private static void testVertexOnlyPatch() {
        GiReceiverShaderPatcher.Result vertex = GiReceiverShaderPatcher.patch(
                GiReceiverShaderPatcher.Stage.VERTEX, basicVertexSource()
        );
        GiReceiverShaderPatcher.Result fragment = GiReceiverShaderPatcher.patch(
                GiReceiverShaderPatcher.Stage.FRAGMENT, fragmentSource()
        );
        require(vertex.success(), "G5 vertex patch failed: " + vertex.failureReason());
        require(fragment.success(), "G5 fragment patch failed: " + fragment.failureReason());
        require(count(vertex.source(), "uniform sampler3D metallumGi") == 4
                        && count(vertex.source(), "textureLod(") == 4
                        && vertex.source().contains("layout(std430, binding = 25)")
                        && vertex.source().contains("metallumGiShRedValue.x + dot(")
                        && vertex.source().contains("metallumGiFaceCode == 6u")
                        && vertex.source().contains("metallumGiArm == 1u")
                        && vertex.source().contains("metallumGiArm == 2u")
                        && vertex.source().contains("metallumGiSamplesFinite")
                        && count(vertex.source(), "isnan(") == 4
                        && count(vertex.source(), "isinf(") == 4
                        && vertex.source().contains(": vec4(0.0);"),
                "G5 vertex path lost its four bounded SH/confidence reads or six-face evaluation");
        require(vertex.source().contains("metallumGiSampledConfidence")
                        && vertex.source().contains(
                        "float metallumGiConfidenceValue = metallumGiSampledConfidence;")
                        && !vertex.source().contains(
                        "metallumGiDrySample ? 0.0 : metallumGiSampledConfidence"),
                "G5 dry candidate no longer derives confidence from the sampled zero field");
        require(vertex.source().contains(
                        "uint metallumGiPositionCarrier = ((a_Position.x >> 30u) & 3u)")
                        && vertex.source().contains(
                        "| (((a_Position.y >> 30u) & 3u) << 2u);")
                        && vertex.source().contains(
                        "metallumGiPositionFaceCode = metallumGiPositionCarrier & 7u")
                        && vertex.source().contains(
                        "(metallumGiPositionCarrier & 8u) != 0u")
                        && vertex.source().contains("metallumGiPositionFaceCode >= 1u")
                        && vertex.source().contains("metallumGiPositionFaceCode <= 6u")
                        && !vertex.source().contains("metallumGiLightSignature")
                        && !vertex.source().contains("metallumGiAlphaCarrier")
                        && !vertex.source().contains("_vert_color.a =")
                        && !vertex.source().contains("_vert_tex_light_coord.x =")
                        && !vertex.source().contains("_vert_tex_light_coord.y =")
                        && vertex.source().contains("metallumGiDryOrigin")
                        && vertex.source().contains("metallumGiDrySample = metallumGiArm == 1u")
                        && vertex.source().contains("&& metallumGiInside;")
                        && vertex.source().contains(
                        "metallumGiIncomingIrradiance = metallumGiEvaluated")
                        && !vertex.source().contains(
                        "metallumGiDrySample ? vec4(0.0) : metallumGiEvaluated")
                        && !vertex.source().contains("? vec3(0.5) : metallumGiUvw"),
                "G5 position carrier decode or sampled-zero spatial path is incomplete");
        require(!fragment.source().contains("sampler3D")
                        && !fragment.source().contains("textureLod(")
                        && fragment.source().contains("in vec4 metallumGiIncomingIrradiance;")
                        && fragment.source().contains("vec3 diffuse = metallumGiFallbackAmbient;")
                        && fragment.source().contains("if (metallumGiConfidence > 0.0)")
                        && !fragment.source().contains("mix(metallumGiFallbackAmbient")
                        && count(fragment.source(), "0.31830988618") == 1,
                "G5 fragment retained a texture receiver or lost the single albedo/pi composition");
        require(GiReceiverShaderPatcher.patch(
                        GiReceiverShaderPatcher.Stage.VERTEX, vertex.source()).success()
                        && GiReceiverShaderPatcher.patch(
                        GiReceiverShaderPatcher.Stage.FRAGMENT, fragment.source()).success(),
                "G5 shader patch is not idempotent");
        for (String forbidden : new String[]{"cone", "DDA", "visible_function"}) {
            require(!vertex.source().contains(forbidden) && !fragment.source().contains(forbidden),
                    "G5 revived forbidden receiver path " + forbidden);
        }
    }

    private static void testReflectionCoexistence() {
        GiReceiverShaderPatcher.Result result = GiReceiverShaderPatcher.patch(
                GiReceiverShaderPatcher.Stage.VERTEX, reflectionVertexSource()
        );
        require(result.success(), "G5/reflection coexistence patch failed: " + result.failureReason());
        require(!result.source().contains("MetallumGiVoxelCameraV1")
                        && result.source().contains(
                        "metallumReflectionGiAxisEligible ? metallumReflectionFaceCode : 0u")
                        && result.source().contains(
                        "uint metallumGiPositionCarrier = ((a_Position.x >> 30u) & 3u)")
                        && result.source().contains(
                        "(metallumGiPositionCarrier & 8u) != 0u")
                        && !result.source().contains("metallumGiLightSignature")
                        && !result.source().contains("metallumGiAlphaCarrier")
                        && !result.source().contains("_vert_color.a =")
                        && !result.source().contains("_vert_tex_light_coord.y ="),
                "G5 did not reuse the existing reflection world/face carrier safely");
    }

    private static void testEmittedMslContract() {
        StringBuilder vertex = new StringBuilder();
        int[] slots = GiReceiverBindingAbi.textureSlots();
        for (int index = 0; index < slots.length; index++) {
            String name = GiReceiverBindingAbi.samplerNames().get(index);
            vertex.append("texture3d<float> ").append(name)
                    .append(" [[texture(").append(slots[index]).append(")]];\n")
                    .append("sampler ").append(name).append("Smplr [[sampler(")
                    .append(slots[index]).append(")]];\n")
                    .append("float4 sample_").append(index).append(" = ")
                    .append(name).append(".sample(").append(name).append("Smplr);\n");
        }
        vertex.append("constant Params& metallumGiReceiver [[buffer(25)]];\n")
                .append("float4 metallumGiIncomingIrradiance;\n");
        String fragment = "float4 value = in.metallumGiIncomingIrradiance;\n";
        GiReceiverBindingAbi.validateMsl(vertex.toString(), fragment, true);
        GiReceiverBindingAbi.validateMsl("vertex main", "fragment main", false);
        expectFailure(
                () -> GiReceiverBindingAbi.validateMsl(
                        vertex.toString(), fragment + "texture3d<float> metallumGiShRed;", true),
                "fragment 3D texture survived G5 validation"
        );
    }

    private static void testHotPathAllocationBoundary() throws Exception {
        String resources = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/receiver/GiReceiverGpuResources.java"
        ));
        int bindStart = resources.indexOf("public synchronized int bindVertex(");
        int statsStart = resources.indexOf("public synchronized @Nullable Stats stats()", bindStart);
        require(bindStart >= 0 && statsStart > bindStart,
                "G5 allocation guard could not isolate bindVertex");
        String bind = resources.substring(bindStart, statsStart);
        require(!bind.contains("Arena") && !bind.contains("allocate(")
                        && !bind.contains("MemorySegment.ofArray"),
                "G5 per-draw binding allocates CPU/native memory");
        require(resources.contains("this.deferredRelease.accept(stale)"),
                "G5 native owner bypasses deferred in-flight retirement");

        String transport = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/transport/GiTransportGpuResources.java"
        ));
        require(transport.contains("private final GiTransportGpuResources owner;")
                        && transport.contains("return this.owner.nativeContextFor(this);")
                        && transport.contains("token == this.readToken")
                        && transport.contains("assertOwnerThread();")
                        && !transport.contains("private final MemorySegment nativeContext;"),
                "G4 read capability can outlive its owner or bypass render-thread validation");
        require(count(resources, "transport.nativeContext()") == 1,
                "G5 receiver performs a time-of-check/time-of-use duplicate token lookup");

        String semantic = Files.readString(Path.of(
                "src/main/java/com/metallum/client/hdr/SodiumHdrSemantic.java"
        ));
        require(semantic.contains("POSITION_CARRIER_G5_BIT = 0x8")
                        && semantic.contains("POSITION_CARRIER_FACE_MASK = 0x7")
                        && semantic.contains("int positionHiBits = (carrierCode & 0x3) << 30")
                        && semantic.contains(
                        "int positionLoBits = ((carrierCode >>> 2) & 0x3) << 30")
                        && semantic.contains("writeCompactPositionCarrier(")
                        && semantic.contains("G5_CARRIER_WRITES.incrementAndGet()")
                        && semantic.contains("g5CarrierWriteCount()"),
                "G5 source chain lost the compact-position carrier layout");

        String meshBuilder = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/sodium/ChunkMeshBufferBuilderHdrMixin.java"
        ));
        require(meshBuilder.contains("compactPositionCarrierCode(vertices)")
                        && meshBuilder.contains("writeCompactPositionCarrier(")
                        && meshBuilder.contains("this.metallum$hasG5Carrier = true;")
                        && meshBuilder.contains("method = \"start(I)V\""),
                "Sodium mesh emission no longer installs the position carrier after encoding");

        String metadata = Files.readString(Path.of(
                "src/main/java/com/metallum/client/sodium/SodiumG5CarrierMetadata.java"
        ));
        require(metadata.contains("INFO_SOLID_SHIFT = 8")
                        && metadata.contains("INFO_CUTOUT_SHIFT = 15")
                        && metadata.contains("INFO_TRANSLUCENT_SHIFT = 22")
                        && metadata.contains("RESIDENT_SHIFT = 8")
                        && metadata.contains("hasLiveG5Carrier(final List<?> bspSlots)")
                        && metadata.contains("for (int index = 0; index < size; index++)")
                        && metadata.contains("slot instanceof FullTQuad quad")
                        && metadata.contains("compactPositionCarrierCode(quad.getVertices())")
                        && metadata.contains("quad.isInvalid()")
                        && metadata.contains("CompactPositionCarrierSafety.reportConflict(")
                        && !metadata.contains("MemoryIntrinsics")
                        && !metadata.contains("ByteBuffer")
                        && !metadata.contains("new byte[")
                        && !metadata.contains(".iterator()"),
                "G5 live-BSP proof is not zero-storage/allocation-free or fail-closed");
        String bspWorkspace = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/sodium/BSPWorkspaceG5CarrierMixin.java"
        ));
        require(bspWorkspace.contains("method = \"getFinalizedUpdatedQuads\"")
                        && bspWorkspace.contains("at = @At(\"RETURN\")")
                        && bspWorkspace.indexOf("if (!GiReceiverRuntime.isRequested()")
                        < bspWorkspace.indexOf("hasLiveG5Carrier(")
                        && bspWorkspace.contains("updatedQuads == null")
                        && bspWorkspace.contains("(List<?>) (Object) this")
                        && bspWorkspace.contains("metallum$setLiveHasG5Carrier("),
                "G5 finalized BSP census lost its exact default-off/live-slot seam");
        String updatedQuads = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/sodium/UpdatedQuadsListG5CarrierMixin.java"
        ));
        require(updatedQuads.contains("private boolean metallum$liveHasG5Carrier;")
                        && !updatedQuads.contains("[")
                        && !updatedQuads.contains("List<")
                        && !updatedQuads.contains("ByteBuffer"),
                "G5 UpdatedQuadsList hand-off is not one primitive field");
        String buildBuffers = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/sodium/ChunkBuildBuffersG5CarrierMixin.java"
        ));
        require(buildBuffers.contains("if (forceUnassigned)")
                        && buildBuffers.contains("ModelQuadFacing.UNASSIGNED_MASK")
                        && buildBuffers.contains("SodiumG5CarrierUpdatedQuadsAccess")
                        && buildBuffers.contains("metallum$liveHasG5Carrier()")
                        && !buildBuffers.contains("@Local")
                        && !buildBuffers.contains("ByteBuffer")
                        && !buildBuffers.contains("MemoryUtil")
                        && !buildBuffers.contains("getDirectBuffer()"),
                "G5 worker metadata does not consume the exact live-BSP primitive");
        String carrierTests = Files.readString(Path.of(
                "src/test/java/com/metallum/client/gi/receiver/"
                        + "GiReceiverCarrierCoexistenceTests.java"
        ));
        require(carrierTests.contains("testFinalizedLiveBspCarrierCensus()")
                        && carrierTests.contains("originalG5.setNoWrite()")
                        && carrierTests.contains("deletedUpdate.setQuadCounts(2, 1)")
                        && carrierTests.contains("liveNonG5.setWriteToIndex(0)")
                        && carrierTests.contains("overwrittenUpdate.setQuadCounts(1, 1)")
                        && carrierTests.contains("FullTQuad.splittingCopy(originalG5)")
                        && carrierTests.contains("replacementG5.setWriteToIndex(0)")
                        && carrierTests.contains("inconsistent live BSP vertex semantics"),
                "G5 live-BSP stale-hole/split/replacement negative matrix regressed");
        String residentUpload = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/sodium/RenderRegionManagerG5CarrierMixin.java"
        ));
        require(residentUpload.contains("at = @At(\"RETURN\")")
                        && residentUpload.contains("METALLUM$SLICE_MASK_OFFSET = 16L")
                        && residentUpload.contains("withResidentCarrierFaceMask("),
                "G5 worker metadata is not published at the accepted resident upload seam");
        String chunkRenderer = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/sodium/DefaultChunkRendererShadowMixin.java"
        ));
        require(chunkRenderer.contains("metallum$resetDrawnG5CarrierSlices()")
                        && chunkRenderer.contains("metallum$addDrawnG5CarrierSlices(")
                        && chunkRenderer.contains("stockSlices & carrierSlices "
                        + "& metallum$currentVisibleFaces")
                        && chunkRenderer.indexOf("if (!GiReceiverRuntime.isRequested())")
                        < chunkRenderer.indexOf("residentCarrierFaceMask(residentMask)"),
                "G5 actual fill-command slice census is missing or costs the default-off path");
        String batcher = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/SodiumIndexedIndirectBatcher.java"
        ));
        require(batcher.contains("metallum$getDrawnG5CarrierSlices()")
                        && batcher.contains("metallum$setPreparedSnapshot(")
                        && batcher.contains("drawnG5CarrierSlices")
                        && !batcher.contains("sectionsWithGeometryIterator("),
                "G5 carrier proof is recomputed instead of following the exact prepared batch");

        String pipeline = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalCompiledRenderPipeline.java"
        ));
        require(pipeline.contains(
                        "validateG5TerrainDraw(flavor, materialSceneAttachment);")
                        && pipeline.contains(
                        "G5 terrain selected an incompatible shader flavor")
                        && pipeline.contains("admission.reportInvalid(")
                        && pipeline.contains("return;")
                        && pipeline.contains("flavor == HdrShaderFlavor.SUN_SHADOW")
                        && pipeline.contains("depth-only L4 caster")
                        && pipeline.contains("PlanarReflectionRenderer.isRendering()"),
                "G5 terrain can enter an incompatible or reflected-world shader draw");

        String device = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
        ));
        require(device.contains("if (key.flavor() == HdrShaderFlavor.METALLUM)")
                        && device.contains("Base Metallum therefore needs no decode/restore")
                        && device.contains("return material.source();")
                        && !device.contains("patchCarrierRestore"),
                "base Metallum no longer ignores position spare bits without G5 shader work");
        require(device.contains("beginCarrierWriteCensus(")
                        && device.contains("successfulCarrierWrites(")
                        && device.contains("if (g5CarrierWrites <= 0L)")
                        && device.contains("g5_carrier_writes={}")
                        && device.contains("drawn_g5_carrier_slices={}")
                        && device.contains("reportTerrainDrawEncoded(drawnG5CarrierSlices)"),
                "G5 live receipt no longer requires a positive exact-axis carrier write");

        String controller = Files.readString(Path.of(
                "src/main/java/com/metallum/client/benchmark/MetalFxBenchmarkController.java"
        ));
        int finalGate = controller.indexOf("CompactPositionCarrierSafety.beginCarrierAwareDraw();");
        int finalSnapshot = controller.indexOf(".finalSnapshot(", finalGate);
        int finalReceipt = controller.indexOf("METALLUM_BENCHMARK EVENT=GI_G5_FINAL ", finalSnapshot);
        int complete = controller.indexOf("METALLUM_BENCHMARK EVENT=COMPLETE ", finalReceipt);
        int finalUnlock = controller.indexOf("CompactPositionCarrierSafety.endCarrierAwareDraw();",
                complete);
        require(finalGate >= 0 && finalSnapshot > finalGate
                        && finalReceipt > finalSnapshot
                        && complete > finalReceipt
                        && finalUnlock > complete
                        && controller.contains("snapshot.drawnG5CarrierSlices() <= 0L"),
                "G5 FINAL and COMPLETE are not emitted from one gated immutable census");

        String planar = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/PlanarReflectionConfig.java"
        ));
        require(planar.contains("if (giReceiverRequested)")
                        && planar.contains("return CaptureMode.DISABLED;"),
                "G5 runtime no longer keeps FULL_PLANAR reflected terrain dormant");
    }

    private static String basicVertexSource() {
        return """
                #version 450
                out float metallumSkyVisibility;
                // METALLUM_ADVANCED_DIRECT_LIGHTING_V1
                void main() {
                    vec3 position = _vert_position + translation;
                    metallumLightingPosition = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;
                }
                """;
    }

    private static String reflectionVertexSource() {
        return """
                #version 450
                out float metallumSkyVisibility;
                // METALLUM_ADVANCED_DIRECT_LIGHTING_V1
                void main() {
                    vec3 position = _vert_position + translation;
                    metallumLightingPosition = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;
                    uint metallumReflectionFaceCode = 2u;
                    vec3 metallumWorldPos = vec3(1.0);
                    vec4 metallumCoarseReflectionDirectionVal = vec4(0.0);
                    metallumCoarseReflectionDirection = metallumCoarseReflectionDirectionVal;
                }
                """;
    }

    private static String fragmentSource() {
        return """
                #version 450
                in float metallumSkyVisibility;
                // METALLUM_ADVANCED_DIRECT_LIGHTING_V1
                vec3 environment(vec3 albedo) {
                    vec3 diffuse = max(metallumEnvironment.ambientRadiance.rgb, vec3(0.0));
                    diffuse += skyAndSun;
                    return albedo * diffuse * 0.31830988618;
                }
                """;
    }

    private static void expectFailure(final Runnable runnable, final String message) {
        try {
            runnable.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static int count(final String source, final String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
