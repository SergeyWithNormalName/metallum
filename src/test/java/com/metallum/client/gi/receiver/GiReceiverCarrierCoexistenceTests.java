package com.metallum.client.gi.receiver;

import com.metallum.client.hdr.HdrEmissionVertex;
import com.metallum.client.hdr.SodiumHdrSemantic;
import com.metallum.client.lighting.reflection.VertexReflectionExperiment;
import com.metallum.client.lighting.reflection.VoxelReflectionFace;
import com.metallum.client.sodium.SodiumG5CarrierMetadata;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.UpdatedQuadsList;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.quad.FullTQuad;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/** Exact Sodium 0.9.1 CPU-to-GLSL position-sideband contract shared by L8 and G5. */
public final class GiReceiverCarrierCoexistenceTests {
    private static final int QUAD_BYTES = SodiumHdrSemantic.COMPACT_VERTEX_STRIDE
            * SodiumHdrSemantic.COMPACT_QUAD_VERTEX_COUNT;
    private static final int[] FACES = {
            VoxelReflectionFace.NEG_X, VoxelReflectionFace.POS_X,
            VoxelReflectionFace.NEG_Y, VoxelReflectionFace.POS_Y,
            VoxelReflectionFace.NEG_Z, VoxelReflectionFace.POS_Z
    };

    private GiReceiverCarrierCoexistenceTests() {
    }

    public static void main(final String[] arguments) throws Exception {
        require(GiReceiverRuntime.isRequested(),
                "carrier coexistence test must run with the G5 diagnostic requested");
        String previousRuntime = System.getProperty(VertexReflectionExperiment.RUNTIME_PROPERTY);
        GiReceiverCompatibility.setTestOverride(true);
        CompactPositionCarrierSafety.resetForTests();
        VertexReflectionExperiment.setOverride(true);
        System.setProperty(VertexReflectionExperiment.RUNTIME_PROPERTY, "true");
        try {
            GiReceiverRuntime.resetDeviceState();
            require(GiReceiverRuntime.admitCompactPositionCarrier(true),
                    "carrier test emitted G5 codes before exact-version admission");
            testExactG5CodesThroughRealCompactEncoder();
            testL8DominantFaceClass();
            testBspSplitThroughRealWriteExternal();
            testFinalizedLiveBspCarrierCensus();
            testAuthoredColorAndLightRemainByteExact();
            testAllCarrierCodesAndLayoutConflicts();
        } finally {
            CompactPositionCarrierSafety.resetForTests();
            GiReceiverCompatibility.setTestOverride(null);
            VertexReflectionExperiment.setOverride(null);
            if (previousRuntime == null) {
                System.clearProperty(VertexReflectionExperiment.RUNTIME_PROPERTY);
            } else {
                System.setProperty(VertexReflectionExperiment.RUNTIME_PROPERTY, previousRuntime);
            }
        }
        System.out.println("G5/L8 compact position carrier coexistence tests passed");
    }

    private static void testExactG5CodesThroughRealCompactEncoder() {
        long initialWrites = SodiumHdrSemantic.g5CarrierWriteCount();
        for (int face : FACES) {
            int faceCode = SodiumHdrSemantic.reflectionFaceCode(face);
            int expectedCode = SodiumHdrSemantic.POSITION_CARRIER_G5_BIT | faceCode;
            ChunkVertexEncoder.Vertex[] vertices = quad(0xff80a0c0, 0x00f000a0);
            SodiumHdrSemantic.tagQuad(
                    vertices, 0, false, SodiumHdrSemantic.SURFACE_CLASS_METAL,
                    false, 0, face, face
            );
            int packedMaterial = SodiumHdrSemantic.packMaterialBits(0, vertices);
            int carrierCode = SodiumHdrSemantic.compactPositionCarrierCode(vertices);
            require(carrierCode == expectedCode,
                    "strict G5 face did not select the exact-axis carrier class");
            verifyRealEncoderRoundTrip(vertices, packedMaterial, carrierCode);
        }
        require(SodiumHdrSemantic.g5CarrierWriteCount() - initialWrites == FACES.length,
                "successful exact-axis carrier census did not count codes 9..14 once each");
    }

    private static void testL8DominantFaceClass() {
        ChunkVertexEncoder.Vertex[] vertices = quad(0x8080a0c0, 0x00e500a1);
        SodiumHdrSemantic.tagQuad(
                vertices, 0, false, SodiumHdrSemantic.SURFACE_CLASS_METAL,
                false, 0, VoxelReflectionFace.POS_X, 0
        );
        int material = SodiumHdrSemantic.packMaterialBits(0, vertices);
        int code = SodiumHdrSemantic.compactPositionCarrierCode(vertices);
        require(code == SodiumHdrSemantic.reflectionFaceCode(VoxelReflectionFace.POS_X)
                        && (code & SodiumHdrSemantic.POSITION_CARRIER_G5_BIT) == 0,
                "dominant L8 approximation was mislabeled as an exact G5 axis");
        verifyRealEncoderRoundTrip(vertices, material, code);
    }

    private static void testBspSplitThroughRealWriteExternal() throws Exception {
        TestVertex endpointA = testVertex(0.0f, 0.0f, 0.0f, 0xff204060, 0x00f00020);
        TestVertex endpointB = testVertex(1.0f, 0.0f, 0.0f, 0xff80a0c0, 0x002000f0);
        SodiumHdrSemantic.tagQuad(
                new ChunkVertexEncoder.Vertex[]{endpointA, endpointB},
                0, false, SodiumHdrSemantic.SURFACE_CLASS_METAL,
                false, 0, VoxelReflectionFace.POS_X, VoxelReflectionFace.POS_X
        );

        TestVertex destinationA = testVertex(-1.0f, -1.0f, -1.0f, 0, 0);
        TestVertex destinationB = endpointB;
        TestVertex destinationC = testVertex(-1.0f, -1.0f, -1.0f, 0, 0);
        destinationA.metallum$setHdrSemantic(0x1357_9bdf);
        destinationC.metallum$setHdrSemantic(0x55aa_55aa);
        int semanticA = SodiumHdrSemantic.bspVertexSemanticSnapshot(endpointA);
        int semanticB = SodiumHdrSemantic.bspVertexSemanticSnapshot(endpointB);

        Method interpolation = Class.forName(
                "net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting."
                        + "bsp_tree.InnerPartitionBSPNode"
        ).getDeclaredMethod(
                "interpolateAttributes",
                float.class,
                Vector3fc.class,
                ChunkVertexEncoder.Vertex.class,
                ChunkVertexEncoder.Vertex.class,
                ChunkVertexEncoder.Vertex.class,
                ChunkVertexEncoder.Vertex.class,
                ChunkVertexEncoder.Vertex.class
        );
        require(interpolation.trySetAccessible(),
                "exact Sodium BSP interpolation helper is no longer reflectively reachable");
        interpolation.invoke(
                null,
                0.5f,
                new Vector3f(1.0f, 0.0f, 0.0f),
                endpointA,
                endpointB,
                destinationA,
                destinationB,
                destinationC
        );
        require(Math.abs(destinationA.x - 0.5f) <= 1.0e-6f
                        && Math.abs(destinationB.x - 0.5f) <= 1.0e-6f
                        && Math.abs(destinationC.x - 0.5f) <= 1.0e-6f,
                "exact Sodium BSP helper did not create the expected split vertex");
        int interpolatedColor = destinationA.color;
        int interpolatedLight = destinationA.light;
        // Direct JavaExec tests do not transform Sodium. Reproduce clear-on-write after capturing
        // both endpoints, including the endpointB == destinationB alias in splitTriangleCorner.
        require(destinationB == endpointB,
                "BSP alias regression no longer exercises endpointB == destinationB");
        destinationA.metallum$setHdrSemantic(0);
        destinationB.metallum$setHdrSemantic(0);
        destinationC.metallum$setHdrSemantic(0);
        int inheritedSemantic = semanticA;
        require(SodiumHdrSemantic.propagateBspInterpolatedSemantic(
                        semanticA, semanticB, destinationA, destinationB, destinationC
                ), "matching BSP edge semantics were rejected");
        require(destinationA.metallum$getHdrSemantic() == inheritedSemantic
                        && destinationB.metallum$getHdrSemantic() == inheritedSemantic
                        && destinationC.metallum$getHdrSemantic() == inheritedSemantic,
                "BSP split vertices did not inherit the agreed quad semantic");
        require(destinationA.color == interpolatedColor && destinationA.light == interpolatedLight,
                "BSP semantic propagation changed Sodium-authored interpolated attributes");

        ChunkVertexEncoder.Vertex[] splitQuad = {
                destinationA, destinationB, destinationC, endpointA
        };
        int expectedCarrier = SodiumHdrSemantic.compactPositionCarrierCode(splitQuad);
        require((expectedCarrier & SodiumHdrSemantic.POSITION_CARRIER_G5_BIT) != 0,
                "BSP split quad lost its strict G5 carrier before external encoding");
        CompactChunkVertex compact = new CompactChunkVertex();
        ChunkVertexEncoder rawEncoder = compact.getEncoder();
        ChunkVertexEncoder semanticEncoder = (pointer, materialBits, vertices, sectionIndex) -> {
            boolean positionCarrier = SodiumHdrSemantic.compactPositionCarrierRequested();
            int carrierCode = positionCarrier
                    ? SodiumHdrSemantic.compactPositionCarrierCode(vertices) : 0;
            long endPointer = rawEncoder.write(
                    pointer,
                    SodiumHdrSemantic.packMaterialBits(materialBits, vertices),
                    vertices,
                    sectionIndex
            );
            if (positionCarrier) {
                SodiumHdrSemantic.writeCompactPositionCarrier(
                        pointer, endPointer, carrierCode
                );
            }
            return endPointer;
        };
        ChunkVertexType semanticType = new CompactChunkVertex() {
            @Override
            public ChunkVertexEncoder getEncoder() {
                return semanticEncoder;
            }
        };
        ChunkMeshBufferBuilder builder = new ChunkMeshBufferBuilder(semanticType, 4);
        ByteBuffer external = MemoryUtil.memCalloc(QUAD_BYTES);
        try {
            builder.writeExternal(external, 0, splitQuad, DefaultMaterials.TRANSLUCENT);
            long pointer = MemoryUtil.memAddress(external);
            for (int vertex = 0; vertex < SodiumHdrSemantic.COMPACT_QUAD_VERTEX_COUNT;
                    vertex++) {
                long vertexPointer = pointer
                        + (long) vertex * SodiumHdrSemantic.COMPACT_VERTEX_STRIDE;
                require(SodiumHdrSemantic.decodeCompactPositionCarrier(
                                MemoryIntrinsics.getInt(vertexPointer),
                                MemoryIntrinsics.getInt(vertexPointer + Integer.BYTES)
                        ) == expectedCarrier,
                        "real Sodium writeExternal path lost a BSP split carrier");
            }
        } finally {
            MemoryUtil.memFree(external);
        }

        endpointB.metallum$setHdrSemantic(inheritedSemantic ^ 1);
        int mismatchedSemanticA = SodiumHdrSemantic.bspVertexSemanticSnapshot(endpointA);
        int mismatchedSemanticB = SodiumHdrSemantic.bspVertexSemanticSnapshot(endpointB);
        destinationA.metallum$setHdrSemantic(inheritedSemantic);
        destinationB.metallum$setHdrSemantic(0);
        destinationC.metallum$setHdrSemantic(inheritedSemantic);
        require(!SodiumHdrSemantic.propagateBspInterpolatedSemantic(
                        mismatchedSemanticA, mismatchedSemanticB,
                        destinationA, destinationB, destinationC
                ), "mismatched BSP endpoint semantics were inherited");
        require(destinationA.metallum$getHdrSemantic() == 0
                        && destinationB.metallum$getHdrSemantic() == 0
                        && destinationC.metallum$getHdrSemantic() == 0,
                "mismatched BSP endpoint semantics did not clear every split destination");
        require(!CompactPositionCarrierSafety.isSafe()
                        && GiReceiverRuntime.admission().state()
                        == GiReceiverRuntime.AdmissionState.INVALID,
                "mismatched BSP endpoint semantics did not reject the carrier fail-closed");
        CompactPositionCarrierSafety.resetForTests();
        GiReceiverRuntime.resetDeviceState();
        require(GiReceiverRuntime.admitCompactPositionCarrier(true),
                "carrier admission did not recover after the isolated BSP mismatch test");
    }

    private static void testAuthoredColorAndLightRemainByteExact() {
        for (int alpha = 0; alpha <= 255; alpha++) {
            int color = alpha << 24 | 0x0080a0c0;
            ChunkVertexEncoder.Vertex[] vertices = taggedQuad(color, 0x00e500a1);
            SodiumHdrSemantic.packMaterialBits(0, vertices);
            for (ChunkVertexEncoder.Vertex vertex : vertices) {
                require(vertex.color == color && vertex.light == 0x00e500a1,
                        "position sideband changed authored alpha or the A1/E5 collision proof");
            }
        }
        for (int lightByte = 0; lightByte <= 255; lightByte++) {
            int[] lights = {
                    0x00e50000 | lightByte,
                    lightByte << 16 | 0x000000a1
            };
            for (int light : lights) {
                ChunkVertexEncoder.Vertex[] vertices = taggedQuad(0x9180a0c0, light);
                SodiumHdrSemantic.packMaterialBits(0, vertices);
                for (ChunkVertexEncoder.Vertex vertex : vertices) {
                    require(vertex.color == 0x9180a0c0 && vertex.light == light,
                            "position sideband changed an authored raw light byte");
                }
            }
        }

        ChunkVertexEncoder.Vertex[] collision = taggedQuad(0xff80a0c0, 0x00e500a1);
        int material = SodiumHdrSemantic.packMaterialBits(0, collision);
        int code = SodiumHdrSemantic.compactPositionCarrierCode(collision);
        long pointer = MemoryUtil.nmemCalloc(1, QUAD_BYTES);
        try {
            long end = new CompactChunkVertex().getEncoder().write(
                    pointer, material, collision, 0
            );
            int compactLightAndData = MemoryIntrinsics.getInt(pointer + 16L);
            require((compactLightAndData & 0xffff) == 0xeda9,
                    "exact Sodium encoder no longer reproduces the historical A1/E5 collision");
            require(SodiumHdrSemantic.writeCompactPositionCarrier(pointer, end, code),
                    "position carrier rejected the exact Sodium compact layout");
            require(MemoryIntrinsics.getInt(pointer + 16L) == compactLightAndData,
                    "position carrier modified compact light/material/draw bytes");
        } finally {
            MemoryUtil.nmemFree(pointer);
        }
    }

    private static void testFinalizedLiveBspCarrierCensus() {
        ChunkVertexEncoder.Vertex[] g5Vertices = taggedQuad(0xff507090, 0x00f000f0);
        int g5Material = SodiumHdrSemantic.packMaterialBits(0, g5Vertices);
        int g5Code = SodiumHdrSemantic.compactPositionCarrierCode(g5Vertices);
        require((g5Code & SodiumHdrSemantic.POSITION_CARRIER_G5_BIT) != 0,
                "live-BSP census fixture did not create a G5 carrier");
        FullTQuad originalG5 = fullQuad(g5Vertices);
        FullTQuad liveNonG5 = fullQuad(quad(0xff203040, 0x00f000f0));

        long staleGeometry = MemoryUtil.nmemCalloc(1, QUAD_BYTES * 2L);
        try {
            long end = new CompactChunkVertex().getEncoder().write(
                    staleGeometry, g5Material, g5Vertices, 0
            );
            require(SodiumHdrSemantic.writeCompactPositionCarrier(
                            staleGeometry, end, g5Code
                    ), "stale-hole fixture could not write the original G5 bytes");
            require((SodiumHdrSemantic.decodeCompactPositionCarrier(
                    MemoryIntrinsics.getInt(staleGeometry),
                    MemoryIntrinsics.getInt(staleGeometry + Integer.BYTES)
            ) & SodiumHdrSemantic.POSITION_CARRIER_G5_BIT) != 0,
                    "stale-hole fixture no longer retains packed G5 bytes");

            ArrayList<Object> liveSlots = new ArrayList<>(2);
            originalG5.setNoWrite();
            liveSlots.add(null);
            liveSlots.add(liveNonG5);
            UpdatedQuadsList deletedUpdate = new UpdatedQuadsList();
            deletedUpdate.setQuadCounts(2, 1);
            require(deletedUpdate.getMeshQuadCount() > deletedUpdate.getIndexQuadCount()
                            && !SodiumG5CarrierMetadata.hasLiveG5Carrier(liveSlots),
                    "a deleted non-tail G5 hole was inferred from stale high-water bytes");

            liveNonG5.setWriteToIndex(0);
            liveSlots.set(0, liveNonG5);
            liveSlots.set(1, null);
            UpdatedQuadsList overwrittenUpdate = new UpdatedQuadsList();
            overwrittenUpdate.setQuadCounts(1, 1);
            require(overwrittenUpdate.getMeshQuadCount()
                            == overwrittenUpdate.getIndexQuadCount()
                            && !SodiumG5CarrierMetadata.hasLiveG5Carrier(liveSlots),
                    "a live non-G5 overwrite inherited the original slot's sticky G5 state");

            FullTQuad splitG5 = FullTQuad.splittingCopy(originalG5);
            installTestVertices(splitG5, g5Vertices);
            splitG5.setWriteToIndex(0);
            liveSlots.set(0, splitG5);
            require(SodiumG5CarrierMetadata.hasLiveG5Carrier(liveSlots),
                    "a live G5 split fragment was absent from the finalized BSP census");

            FullTQuad replacementG5 = fullQuad(g5Vertices);
            replacementG5.setWriteToIndex(0);
            liveSlots.set(0, replacementG5);
            require(SodiumG5CarrierMetadata.hasLiveG5Carrier(liveSlots),
                    "a live G5 replacement that reused a deleted hole was not admitted");

            ChunkVertexEncoder.Vertex[] inconsistent = taggedQuad(
                    0xff506070, 0x00f000f0
            );
            ((TestVertex) inconsistent[3]).metallum$setHdrSemantic(0);
            liveSlots.set(0, fullQuad(inconsistent));
            require(!SodiumG5CarrierMetadata.hasLiveG5Carrier(liveSlots)
                            && !CompactPositionCarrierSafety.isSafe()
                            && GiReceiverRuntime.admission().state()
                            == GiReceiverRuntime.AdmissionState.INVALID,
                    "inconsistent live BSP vertex semantics did not reject G5 terminally");
        } finally {
            MemoryUtil.nmemFree(staleGeometry);
            CompactPositionCarrierSafety.resetForTests();
            GiReceiverRuntime.resetDeviceState();
            require(GiReceiverRuntime.admitCompactPositionCarrier(true),
                    "carrier admission did not recover after live-BSP negative tests");
        }
    }

    private static void testAllCarrierCodesAndLayoutConflicts() {
        ChunkVertexEncoder.Vertex[] vertices = quad(0xffabcdef, 0x00f000f0);
        int material = SodiumHdrSemantic.packMaterialBits(0, vertices);
        for (int code = 0; code < 16; code++) {
            boolean valid = code == 0 || ((code & 7) >= 1 && (code & 7) <= 6);
            long pointer = MemoryUtil.nmemCalloc(1, QUAD_BYTES);
            try {
                long end = new CompactChunkVertex().getEncoder().write(
                        pointer, material, vertices, 0
                );
                int[] before = snapshot(pointer);
                boolean written = SodiumHdrSemantic.writeCompactPositionCarrier(pointer, end, code);
                require(written == valid, "reserved carrier code admission changed: " + code);
                int[] after = snapshot(pointer);
                for (int vertex = 0; vertex < SodiumHdrSemantic.COMPACT_QUAD_VERTEX_COUNT;
                        vertex++) {
                    int word = vertex * 5;
                    require(SodiumHdrSemantic.compactPositionPayload(after[word])
                                    == SodiumHdrSemantic.compactPositionPayload(before[word])
                                    && SodiumHdrSemantic.compactPositionPayload(after[word + 1])
                                    == SodiumHdrSemantic.compactPositionPayload(before[word + 1]),
                            "carrier changed a quantized position payload");
                    require(after[word + 2] == before[word + 2]
                                    && after[word + 3] == before[word + 3]
                                    && after[word + 4] == before[word + 4],
                            "carrier changed compact color/UV/light/data bytes");
                    int decoded = SodiumHdrSemantic.decodeCompactPositionCarrier(
                            after[word], after[word + 1]
                    );
                    require(decoded == (valid ? code : 0),
                            "compact position carrier round-trip changed code " + code);
                }
            } finally {
                MemoryUtil.nmemFree(pointer);
            }
            if (!valid) {
                GiReceiverRuntime.resetDeviceState();
                GiReceiverRuntime.admission().reportCarrierSafe();
            }
        }

        long conflictPointer = MemoryUtil.nmemCalloc(1, QUAD_BYTES);
        try {
            long end = new CompactChunkVertex().getEncoder().write(
                    conflictPointer, material, vertices, 0
            );
            MemoryIntrinsics.putInt(
                    conflictPointer,
                    MemoryIntrinsics.getInt(conflictPointer) | 0x4000_0000
            );
            MemoryIntrinsics.putInt(
                    conflictPointer + Integer.BYTES,
                    MemoryIntrinsics.getInt(conflictPointer + Integer.BYTES) | 0x8000_0000
            );
            int[] before = snapshot(conflictPointer);
            require(!SodiumHdrSemantic.writeCompactPositionCarrier(
                            conflictPointer, end, 0
                    ),
                    "pre-owned code 9 on an untagged quad was not rejected fail-closed");
            int[] after = snapshot(conflictPointer);
            require(java.util.Arrays.equals(before, after),
                    "layout-conflict rejection partially wrote the compact quad");
            require(GiReceiverRuntime.admission().state()
                            == GiReceiverRuntime.AdmissionState.INVALID,
                    "G5 layout conflict did not terminally invalidate admission");
        } finally {
            MemoryUtil.nmemFree(conflictPointer);
        }
    }

    private static void verifyRealEncoderRoundTrip(
            final ChunkVertexEncoder.Vertex[] vertices,
            final int material,
            final int carrierCode
    ) {
        long pointer = MemoryUtil.nmemCalloc(1, QUAD_BYTES);
        try {
            long end = new CompactChunkVertex().getEncoder().write(
                    pointer, material, vertices, 0
            );
            require(end - pointer == QUAD_BYTES,
                    "Sodium compact quad stride changed from the version-locked 80 bytes");
            int[] before = snapshot(pointer);
            require(SodiumHdrSemantic.writeCompactPositionCarrier(pointer, end, carrierCode),
                    "exact Sodium compact layout rejected a valid position carrier");
            int[] after = snapshot(pointer);
            for (int vertex = 0; vertex < SodiumHdrSemantic.COMPACT_QUAD_VERTEX_COUNT;
                    vertex++) {
                int word = vertex * 5;
                require(SodiumHdrSemantic.decodeCompactPositionCarrier(
                                after[word], after[word + 1]) == carrierCode,
                        "real compact encoder lost the four-bit carrier");
                require(SodiumHdrSemantic.compactPositionPayload(before[word])
                                == SodiumHdrSemantic.compactPositionPayload(after[word])
                                && SodiumHdrSemantic.compactPositionPayload(before[word + 1])
                                == SodiumHdrSemantic.compactPositionPayload(after[word + 1]),
                        "position carrier changed a real quantized coordinate");
                require(before[word + 2] == after[word + 2]
                                && before[word + 3] == after[word + 3]
                                && before[word + 4] == after[word + 4],
                        "position carrier changed real compact non-position words");
            }
        } finally {
            MemoryUtil.nmemFree(pointer);
        }
    }

    private static int[] snapshot(final long pointer) {
        int[] words = new int[QUAD_BYTES / Integer.BYTES];
        for (int index = 0; index < words.length; index++) {
            words[index] = MemoryIntrinsics.getInt(pointer + (long) index * Integer.BYTES);
        }
        return words;
    }

    private static ChunkVertexEncoder.Vertex[] taggedQuad(final int color, final int light) {
        ChunkVertexEncoder.Vertex[] vertices = quad(color, light);
        SodiumHdrSemantic.tagQuad(
                vertices, 0, false, SodiumHdrSemantic.SURFACE_CLASS_METAL,
                false, 0, VoxelReflectionFace.POS_X, VoxelReflectionFace.POS_X
        );
        return vertices;
    }

    private static FullTQuad fullQuad(final ChunkVertexEncoder.Vertex[] vertices) {
        FullTQuad quad = FullTQuad.fromVertices(
                vertices,
                ModelQuadFacing.POS_Z,
                ModelQuadFacing.POS_Z.getPackedAlignedNormal()
        );
        require(quad != null, "live-BSP fixture created an invalid quad");
        installTestVertices(quad, vertices);
        return quad;
    }

    private static void installTestVertices(
            final FullTQuad quad,
            final ChunkVertexEncoder.Vertex[] vertices
    ) {
        ChunkVertexEncoder.Vertex[] stored = quad.getVertices();
        require(stored.length == SodiumHdrSemantic.COMPACT_QUAD_VERTEX_COUNT,
                "pinned Sodium FullTQuad no longer exposes four live vertices");
        for (int index = 0; index < stored.length; index++) {
            stored[index] = vertices[index];
        }
    }

    private static ChunkVertexEncoder.Vertex[] quad(final int color, final int light) {
        ChunkVertexEncoder.Vertex[] vertices = new ChunkVertexEncoder.Vertex[4];
        for (int index = 0; index < vertices.length; index++) {
            vertices[index] = testVertex(
                    index == 1 || index == 2 ? 1.0f : 0.0f,
                    index >= 2 ? 1.0f : 0.0f,
                    0.0f,
                    color,
                    light
            );
        }
        return vertices;
    }

    private static TestVertex testVertex(
            final float x,
            final float y,
            final float z,
            final int color,
            final int light
    ) {
        TestVertex vertex = new TestVertex();
        vertex.x = x;
        vertex.y = y;
        vertex.z = z;
        vertex.u = x;
        vertex.v = y;
        vertex.ao = 1.0f;
        vertex.color = color;
        vertex.light = light;
        return vertex;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class TestVertex extends ChunkVertexEncoder.Vertex
            implements HdrEmissionVertex {
        private int semantic;

        @Override
        public int metallum$getHdrSemantic() {
            return this.semantic;
        }

        @Override
        public void metallum$setHdrSemantic(final int semantic) {
            this.semantic = semantic;
        }
    }
}
