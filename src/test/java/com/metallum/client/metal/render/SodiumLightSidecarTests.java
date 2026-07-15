package com.metallum.client.metal.render;

import com.metallum.client.sodium.SodiumLightSidecar;
import com.metallum.client.sodium.SodiumLightSidecarPacking;
import com.metallum.client.sodium.SodiumTerrainMeshLayout;
import com.metallum.client.sodium.SodiumTerrainStaticShadow;
import com.metallum.client.sodium.SodiumTerrainUploadBaseline;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class SodiumLightSidecarTests {
    private static final String SYNTHETIC_SODIUM_MSL = """
            #include <metal_stdlib>
            using namespace metal;

            struct main0_in {
                uint2 a_Position [[attribute(0)]];
                float4 a_Color [[attribute(1)]];
                uint4 a_LightAndData [[attribute(3)]];
            };

            struct main0_out {
                float4 gl_Position [[position]];
            };

            vertex main0_out main0(main0_in in [[stage_in]]) {
                main0_out out = {};
                float2 light = float2(in.a_LightAndData.xy);
                out.gl_Position = float4(float2(in.a_Position), light.x, 1.0);
                return out;
            }
            """;
    private static final String SYNTHETIC_SODIUM_MSL_WITH_RESOURCES = SYNTHETIC_SODIUM_MSL.replace(
            "main0_in in [[stage_in]])",
            "main0_in in [[stage_in]],\n"
                    + "        constant uint4& existingUniform [[buffer(0)]],\n"
                    + "        texture2d<float> existingTexture [[texture(0)]])"
    );

    private SodiumLightSidecarTests() {
    }

    public static void main(final String[] args) {
        testAllPackedValuesRoundTripExactly();
        testGeometryPayloadPacking();
        testReusedArenaCapacityRoundsDownToWholeVertices();
        testPackingRejectsInvalidInputs();
        testTerrainMeshLayoutUsesOnlyUploadMetadata();
        testTerrainUploadBaselineMatchesOnlyUploadLayout();
        testTerrainStaticShadowIgnoresOnlyLightBytes();
        testTerrainStaticShadowReconstructsExactGeometry();
        testTerrainStaticShadowCacheIsBoundedAndLru();
        testTerrainUploadBaselineRequiresExactStaticGeometry();
        testMslPatchAndIdempotence();
        testMslPatchPreservesExistingResourceArguments();
        testMslPatchRejectsUnsafeAnchors();
    }

    private static void testAllPackedValuesRoundTripExactly() {
        int cases = 0;
        for (int blockCoordinate = 0; blockCoordinate < 256; blockCoordinate++) {
            for (int skyCoordinate = 0; skyCoordinate < 256; skyCoordinate++) {
                int packed = SodiumLightSidecarPacking.packCoordinates(blockCoordinate, skyCoordinate);
                require(
                        SodiumLightSidecarPacking.blockCoordinate(packed) == blockCoordinate,
                        "block light coordinate did not round-trip"
                );
                require(
                        SodiumLightSidecarPacking.skyCoordinate(packed) == skyCoordinate,
                        "sky light coordinate did not round-trip"
                );
                require((packed & 0xffff0000) == 0, "packed light used more than two bytes");
                cases++;
            }
        }
        require(cases == 65_536, "exhaustive sidecar case count changed");
    }

    private static void testGeometryPayloadPacking() {
        int sourcePosition = 3;
        ByteBuffer geometry = ByteBuffer.allocate(sourcePosition
                + SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE * 2);
        Arrays.fill(geometry.array(), (byte) 0x5a);
        geometry.position(sourcePosition);

        int firstOffset = sourcePosition;
        geometry.put(firstOffset + 16, (byte) 8);
        geometry.put(firstOffset + 17, (byte) 248);
        geometry.put(firstOffset + 18, (byte) ((21 << 3) | 5));

        int secondOffset = sourcePosition + SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
        // Smooth Sodium lighting is not restricted to 8 + 16*n; 28 was the
        // first real runtime value that disproved the lossy four-bit model.
        geometry.put(secondOffset + 16, (byte) 28);
        geometry.put(secondOffset + 17, (byte) 56);
        geometry.put(secondOffset + 18, (byte) ((7 << 3) | 2));

        byte[] originalGeometry = geometry.array().clone();
        ByteBuffer destination = ByteBuffer.allocate(13);
        Arrays.fill(destination.array(), (byte) 0x33);
        destination.position(2);

        int vertices = SodiumLightSidecarPacking.packGeometry(geometry, destination);
        require(vertices == 2, "geometry pack changed the vertex count");
        require(geometry.position() == sourcePosition, "geometry source position changed");
        require(Arrays.equals(geometry.array(), originalGeometry), "geometry source bytes changed");
        require(destination.position() == 6, "sidecar destination position did not advance by four bytes");

        int firstPacked = packedUnsignedShort(destination, 2);
        require(SodiumLightSidecarPacking.blockCoordinate(firstPacked) == 8, "first block coordinate changed");
        require(SodiumLightSidecarPacking.skyCoordinate(firstPacked) == 248, "first sky coordinate changed");

        int secondPacked = packedUnsignedShort(destination, 4);
        require(SodiumLightSidecarPacking.blockCoordinate(secondPacked) == 28, "second block coordinate changed");
        require(SodiumLightSidecarPacking.skyCoordinate(secondPacked) == 56, "second sky coordinate changed");

        require(destination.get(0) == 0x33 && destination.get(1) == 0x33,
                "sidecar packing overwrote its prefix");
        for (int offset = 6; offset < destination.capacity(); offset++) {
            require(destination.get(offset) == 0x33, "sidecar packing overwrote its suffix");
        }
    }

    private static void testPackingRejectsInvalidInputs() {
        expectIllegalArgument(() -> SodiumLightSidecarPacking.packCoordinates(-1, 0));
        expectIllegalArgument(() -> SodiumLightSidecarPacking.packCoordinates(0, 256));
        expectIllegalArgument(() -> SodiumLightSidecarPacking.packGeometry(
                ByteBuffer.allocate(SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE - 1),
                ByteBuffer.allocate(SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE)
        ));
        expectIllegalArgument(() -> SodiumLightSidecarPacking.packGeometry(
                validGeometryVertex(),
                ByteBuffer.allocate(SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE - 1)
        ));
    }

    private static void testReusedArenaCapacityRoundsDownToWholeVertices() {
        long geometryBytesWithIndexArenaTail = 104L;
        require(
                SodiumLightSidecar.usableGeometryBytes(geometryBytesWithIndexArenaTail) == 100L,
                "reused arena geometry capacity did not round down to whole vertices"
        );
        require(
                SodiumLightSidecar.expectedSidecarBytes(geometryBytesWithIndexArenaTail) == 10L,
                "sidecar capacity did not follow the rounded geometry vertex count"
        );
        expectIllegalArgument(() -> SodiumLightSidecar.expectedSidecarBytes(19L));
    }

    private static void testTerrainMeshLayoutUsesOnlyUploadMetadata() {
        int geometryBytes = SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE * 5;
        int[] segments = {4, 1};
        SodiumTerrainMeshLayout layout = SodiumTerrainMeshLayout.capture(geometryBytes, segments);
        segments[0] = 99;

        require(layout.geometryBytes() == geometryBytes, "mesh layout changed geometry bytes");
        require(layout.vertexCount() == 5, "mesh layout changed vertex count");
        int[] exportedSegments = layout.vertexSegments();
        exportedSegments[0] = 77;
        require(Arrays.equals(layout.vertexSegments(), new int[]{4, 1}),
                "mesh layout exposed mutable vertex segments");
        require(layout.matches(SodiumTerrainMeshLayout.capture(geometryBytes, new int[]{4, 1})),
                "mesh layout retained mutable vertex segments");
        require(!layout.matches(SodiumTerrainMeshLayout.capture(geometryBytes, new int[]{4, 2})),
                "mesh layout ignored vertex segments");
        require(!layout.matches(SodiumTerrainMeshLayout.capture(
                geometryBytes + SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE,
                new int[]{4, 1}
        )), "mesh layout ignored geometry length");
        expectIllegalArgument(() -> SodiumTerrainMeshLayout.capture(
                SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE - 1,
                new int[0]
        ));
        expectIllegalArgument(() -> SodiumTerrainMeshLayout.capture(-1, new int[0]));
    }

    private static void testTerrainUploadBaselineMatchesOnlyUploadLayout() {
        SodiumTerrainMeshLayout residentLayout = SodiumTerrainMeshLayout.capture(
                SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE,
                new int[]{1}
        );
        SodiumTerrainMeshLayout changedSegments = SodiumTerrainMeshLayout.capture(
                SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE,
                new int[]{2}
        );
        long[] visibility = {11L, 22L};
        SodiumTerrainMeshLayout[] meshes = {residentLayout, null};
        SodiumTerrainUploadBaseline baseline = new SodiumTerrainUploadBaseline(7, visibility, meshes);
        require(baseline.generation() == 0, "legacy baseline generation changed");

        visibility[0] = 99L;
        meshes[0] = changedSegments;
        meshes[1] = residentLayout;
        require(
                baseline.matchesUploadLayout(new SodiumTerrainUploadBaseline(
                        7,
                        new long[]{11L, 22L},
                        new SodiumTerrainMeshLayout[]{residentLayout, null}
                )),
                "baseline retained mutable constructor arrays"
        );
        require(
                !baseline.matchesUploadLayout(new SodiumTerrainUploadBaseline(
                        8,
                        new long[]{11L, 22L},
                        new SodiumTerrainMeshLayout[]{residentLayout, null}
                )),
                "baseline ignored section flags"
        );
        require(
                !baseline.matchesUploadLayout(new SodiumTerrainUploadBaseline(
                        7,
                        new long[]{11L, 23L},
                        new SodiumTerrainMeshLayout[]{residentLayout, null}
                )),
                "baseline ignored visibility data"
        );
        require(
                !baseline.matchesUploadLayout(new SodiumTerrainUploadBaseline(
                        7,
                        new long[]{11L, 22L},
                        new SodiumTerrainMeshLayout[]{null, null}
                )),
                "baseline ignored a missing render-pass mesh"
        );
        require(
                !baseline.matchesUploadLayout(new SodiumTerrainUploadBaseline(
                        7,
                        new long[]{11L, 22L},
                        new SodiumTerrainMeshLayout[]{residentLayout, residentLayout}
                )),
                "baseline ignored an added render-pass mesh"
        );
        require(
                !baseline.matchesUploadLayout(new SodiumTerrainUploadBaseline(
                        7,
                        new long[]{11L, 22L},
                        new SodiumTerrainMeshLayout[]{changedSegments, null}
                )),
                "baseline ignored changed vertex segments"
        );
    }

    private static void testTerrainStaticShadowIgnoresOnlyLightBytes() {
        SodiumTerrainStaticShadow.Cache cache = new SodiumTerrainStaticShadow.Cache(36L);
        ByteBuffer resident = patternedGeometry(2, 5);
        byte[] original = resident.array().clone();
        SodiumTerrainStaticShadow shadow = cache.capture(resident);
        require(Arrays.equals(resident.array(), original), "static shadow capture changed source geometry");

        ByteBuffer lightChanged = resident.duplicate();
        lightChanged.put(16, (byte) 91);
        lightChanged.put(17, (byte) 92);
        lightChanged.put(36, (byte) 93);
        lightChanged.put(37, (byte) 94);
        require(shadow.matches(lightChanged), "exact static shadow treated light bytes as geometry");

        ByteBuffer prefixChanged = lightChanged.duplicate();
        prefixChanged.put(15, (byte) (prefixChanged.get(15) + 1));
        require(!shadow.matches(prefixChanged), "exact static shadow ignored a pre-light geometry byte");

        ByteBuffer suffixChanged = lightChanged.duplicate();
        suffixChanged.put(18, (byte) (suffixChanged.get(18) + 1));
        require(!shadow.matches(suffixChanged), "exact static shadow ignored a post-light geometry byte");
        require(shadow.isResident(), "ordinary static shadow was not retained");
        shadow.close();
        require(cache.snapshot().liveBytes() == 0L, "closed static shadow retained cache bytes");
    }

    private static void testTerrainStaticShadowReconstructsExactGeometry() {
        SodiumTerrainStaticShadow.Cache cache = new SodiumTerrainStaticShadow.Cache(36L);
        ByteBuffer resident = patternedGeometry(2, 12);
        byte[] residentBytes = resident.array().clone();
        SodiumTerrainStaticShadow shadow = cache.capture(resident);

        ByteBuffer lightStorage = ByteBuffer.allocate(9);
        Arrays.fill(lightStorage.array(), (byte) 0x26);
        lightStorage.put(3, (byte) 11);
        lightStorage.put(4, (byte) 22);
        lightStorage.put(5, (byte) 33);
        lightStorage.put(6, (byte) 44);
        lightStorage.position(3);
        lightStorage.limit(7);

        ByteBuffer destination = ByteBuffer.allocate(51);
        Arrays.fill(destination.array(), (byte) 0x5c);
        destination.position(4);
        destination.limit(49);
        require(shadow.reconstruct(lightStorage, destination),
                "resident static shadow did not reconstruct full geometry");
        require(lightStorage.position() == 3, "reconstruction changed the light source position");
        require(destination.position() == 44, "reconstruction advanced by the wrong geometry size");
        for (int vertex = 0; vertex < 2; vertex++) {
            int sourceOffset = vertex * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
            int destinationOffset = 4 + sourceOffset;
            for (int offset = 0; offset < SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE; offset++) {
                int expected = residentBytes[sourceOffset + offset];
                if (offset == SodiumLightSidecarPacking.BLOCK_LIGHT_OFFSET) {
                    expected = lightStorage.get(3 + vertex * 2);
                } else if (offset == SodiumLightSidecarPacking.SKY_LIGHT_OFFSET) {
                    expected = lightStorage.get(4 + vertex * 2);
                }
                require(destination.get(destinationOffset + offset) == (byte) expected,
                        "reconstruction changed compact vertex byte " + offset);
            }
        }
        require(destination.get(3) == (byte) 0x5c && destination.get(44) == (byte) 0x5c,
                "reconstruction overwrote destination bounds");

        byte[] overlappingStorage = new byte[48];
        Arrays.fill(overlappingStorage, (byte) 0x71);
        overlappingStorage[20] = 51;
        overlappingStorage[21] = 52;
        overlappingStorage[22] = 53;
        overlappingStorage[23] = 54;
        ByteBuffer overlappingLight = ByteBuffer.wrap(overlappingStorage);
        overlappingLight.position(20);
        overlappingLight.limit(24);
        ByteBuffer overlappingDestination = ByteBuffer.wrap(overlappingStorage);
        overlappingDestination.position(2);
        require(shadow.reconstruct(overlappingLight, overlappingDestination),
                "overlapping light/destination views were rejected");
        require(overlappingStorage[18] == 51
                        && overlappingStorage[19] == 52
                        && overlappingStorage[38] == 53
                        && overlappingStorage[39] == 54,
                "overlapping reconstruction did not snapshot all light bytes");

        assertReconstructionFailureLeavesDestinationUnchanged(
                shadow,
                ByteBuffer.allocate(3),
                ByteBuffer.allocate(40),
                "mis-sized light payload"
        );
        assertReconstructionFailureLeavesDestinationUnchanged(
                shadow,
                ByteBuffer.allocate(4),
                ByteBuffer.allocate(39),
                "short geometry destination"
        );
        ByteBuffer readOnlyBacking = ByteBuffer.allocate(40);
        assertReconstructionFailureLeavesDestinationUnchanged(
                shadow,
                ByteBuffer.allocate(4),
                readOnlyBacking.asReadOnlyBuffer(),
                "read-only geometry destination"
        );

        shadow.close();
        assertReconstructionFailureLeavesDestinationUnchanged(
                shadow,
                ByteBuffer.allocate(4),
                ByteBuffer.allocate(40),
                "evicted static shadow"
        );
    }

    private static void testTerrainStaticShadowCacheIsBoundedAndLru() {
        SodiumTerrainStaticShadow.Cache cache = new SodiumTerrainStaticShadow.Cache(36L);
        SodiumTerrainStaticShadow first = cache.capture(patternedGeometry(1, 1));
        SodiumTerrainStaticShadow second = cache.capture(patternedGeometry(1, 2));
        require(first.matches(patternedGeometry(1, 1)), "LRU touch rejected identical static geometry");
        SodiumTerrainStaticShadow third = cache.capture(patternedGeometry(1, 3));

        require(first.isResident(), "recently touched static shadow was evicted");
        require(!second.isResident(), "least-recently-used static shadow survived eviction");
        require(third.isResident(), "new static shadow was not retained");
        require(cache.snapshot().liveBytes() == 36L, "bounded static shadow cache exceeded capacity");
        require(cache.snapshot().evictionCount() == 1L, "static shadow eviction was not counted");

        SodiumTerrainStaticShadow rejected = new SodiumTerrainStaticShadow.Cache(17L)
                .capture(patternedGeometry(1, 4));
        require(!rejected.isResident(), "oversized static shadow entered a bounded cache");
        first.close();
        second.close();
        third.close();
        rejected.close();
    }

    private static void testTerrainUploadBaselineRequiresExactStaticGeometry() {
        int geometryBytes = SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE * 2;
        SodiumTerrainMeshLayout layout = SodiumTerrainMeshLayout.capture(geometryBytes, new int[]{2});
        SodiumTerrainUploadBaseline metadata = new SodiumTerrainUploadBaseline(
                3,
                new long[]{7L},
                new SodiumTerrainMeshLayout[]{layout, null},
                42
        );
        require(metadata.generation() == 42, "explicit baseline generation changed");
        ByteBuffer resident = patternedGeometry(2, 9);
        SodiumTerrainUploadBaseline exact = metadata.withStaticGeometry(new ByteBuffer[]{resident, null});
        require(exact.hasResidentStaticGeometry(), "captured baseline did not retain exact static geometry");
        require(exact.generation() == 42, "static capture discarded the baseline generation");

        byte[] replacementLights = {31, 47, 63, 79};
        ByteBuffer reconstructed = ByteBuffer.allocate(geometryBytes);
        require(exact.reconstructGeometry(0, ByteBuffer.wrap(replacementLights), reconstructed),
                "baseline did not reconstruct its resident pass geometry");
        require(reconstructed.position() == geometryBytes,
                "baseline reconstruction advanced by the wrong geometry size");
        for (int vertex = 0; vertex < 2; vertex++) {
            int vertexOffset = vertex * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
            for (int offset = 0; offset < SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE; offset++) {
                byte expected = resident.get(vertexOffset + offset);
                if (offset == SodiumLightSidecarPacking.BLOCK_LIGHT_OFFSET
                        || offset == SodiumLightSidecarPacking.SKY_LIGHT_OFFSET) {
                    expected = replacementLights[vertex * 2
                            + offset - SodiumLightSidecarPacking.BLOCK_LIGHT_OFFSET];
                }
                require(reconstructed.get(vertexOffset + offset) == expected,
                        "baseline reconstruction changed compact vertex byte " + offset);
            }
        }
        require(!exact.reconstructGeometry(1, ByteBuffer.allocate(0), ByteBuffer.allocate(0)),
                "baseline reconstructed an absent render-pass mesh");

        SodiumTerrainUploadBaseline emptyMetadata = new SodiumTerrainUploadBaseline(
                BuiltSectionInfo.EMPTY,
                new SodiumTerrainMeshLayout[0],
                42
        );
        require(emptyMetadata.matchesResidentMetadata(42, BuiltSectionInfo.EMPTY),
                "baseline rejected exact resident metadata and generation");
        require(!emptyMetadata.matchesResidentMetadata(41, BuiltSectionInfo.EMPTY),
                "baseline ignored resident generation mismatch");
        emptyMetadata.close();
        SodiumTerrainUploadBaseline legacyMetadata = new SodiumTerrainUploadBaseline(
                BuiltSectionInfo.EMPTY.flags,
                BuiltSectionInfo.EMPTY.visibilityData,
                new SodiumTerrainMeshLayout[0]
        );
        require(!legacyMetadata.matchesResidentMetadata(0, BuiltSectionInfo.EMPTY),
                "legacy generation admitted exact-output fast-path metadata");
        legacyMetadata.close();

        ByteBuffer relit = resident.duplicate();
        relit.put(16, (byte) 31);
        relit.put(17, (byte) 47);
        relit.put(36, (byte) 63);
        relit.put(37, (byte) 79);
        require(
                exact.matchesStaticGeometry(new ByteBuffer[]{relit, null}),
                "second light-only transition did not match resident static geometry"
        );
        ByteBuffer relitAgain = relit.duplicate();
        relitAgain.put(16, (byte) 95);
        require(
                exact.matchesStaticGeometry(new ByteBuffer[]{relitAgain, null}),
                "repeated light-only transition lost exact admission"
        );

        ByteBuffer materialChanged = relitAgain.duplicate();
        materialChanged.put(19, (byte) (materialChanged.get(19) ^ 0x40));
        require(
                !exact.matchesStaticGeometry(new ByteBuffer[]{materialChanged, null}),
                "static material change was admitted as light-only"
        );
        require(
                !metadata.matchesStaticGeometry(new ByteBuffer[]{resident, null}),
                "metadata-only baseline admitted compact light upload"
        );
        exact.close();
        metadata.close();
    }

    private static void assertReconstructionFailureLeavesDestinationUnchanged(
            final SodiumTerrainStaticShadow shadow,
            final ByteBuffer light,
            final ByteBuffer destination,
            final String scenario
    ) {
        ByteBuffer originalLight = light.duplicate();
        byte[] lightBytes = new byte[originalLight.remaining()];
        originalLight.get(lightBytes);
        int lightPosition = light.position();
        ByteBuffer original = destination.duplicate();
        byte[] bytes = new byte[original.remaining()];
        original.get(bytes);
        int position = destination.position();
        require(!shadow.reconstruct(light, destination), scenario + " unexpectedly reconstructed");
        require(light.position() == lightPosition, scenario + " changed light source position");
        ByteBuffer afterLight = light.duplicate();
        byte[] afterLightBytes = new byte[afterLight.remaining()];
        afterLight.get(afterLightBytes);
        require(Arrays.equals(lightBytes, afterLightBytes), scenario + " changed light source contents");
        require(destination.position() == position, scenario + " changed destination position");
        ByteBuffer after = destination.duplicate();
        byte[] afterBytes = new byte[after.remaining()];
        after.get(afterBytes);
        require(Arrays.equals(bytes, afterBytes), scenario + " changed destination contents");
    }

    private static void testMslPatchAndIdempotence() {
        SodiumLightSidecarMslPatcher.Result result = SodiumLightSidecarMslPatcher.patch(
                SYNTHETIC_SODIUM_MSL,
                "main0",
                21,
                22
        );
        require(result.success(), "valid Sodium MSL was not patched: " + result.reason());
        require(SodiumLightSidecarMslPatcher.isPatched(result.source()), "patched MSL marker is missing");
        require(count(result.source(), "METALLUM_SODIUM_LIGHT_SIDECAR_V2") == 1,
                "patched MSL marker was not unique");
        require(result.source().contains("metallumLightSidecar [[buffer(21)]]"),
                "sidecar data buffer index changed");
        require(result.source().contains("const device ushort* metallumLightSidecar"),
                "sidecar data buffer type changed");
        require(result.source().contains("metallumLightSidecarEnabled [[buffer(22)]]"),
                "sidecar control buffer index changed");
        require(result.source().contains("metallumLightSidecarVertexId [[vertex_id]]"),
                "absolute Metal vertex ID was not requested");
        require(result.source().contains("metallumLightSidecar[metallumLightSidecarVertexId]"),
                "sidecar was not indexed by the absolute Metal vertex ID");
        require(!result.source().contains("in.a_LightAndData.z ="),
                "static material/HDR semantic geometry was overwritten by the light sidecar");

        SodiumLightSidecarMslPatcher.Result repeated = SodiumLightSidecarMslPatcher.patch(
                result.source(),
                "main0",
                21,
                22
        );
        require(repeated.success(), "idempotent patch was rejected");
        require(repeated.source().equals(result.source()), "idempotent patch changed MSL source");
        require("already patched".equals(repeated.reason()), "idempotent patch reason changed");
    }

    private static void testMslPatchPreservesExistingResourceArguments() {
        SodiumLightSidecarMslPatcher.Result result = SodiumLightSidecarMslPatcher.patch(
                SYNTHETIC_SODIUM_MSL_WITH_RESOURCES,
                "main0",
                21,
                22
        );
        require(result.success(), "Sodium MSL with resource arguments was not patched: " + result.reason());
        require(result.source().contains("existingUniform [[buffer(0)]]"),
                "existing uniform argument was not preserved");
        require(result.source().contains("existingTexture [[texture(0)]]"),
                "existing texture argument was not preserved");
        require(result.source().contains(
                        "metallumLightSidecarVertexId [[vertex_id]],\n        constant uint4& existingUniform"
                ),
                "sidecar arguments were not inserted before existing resources");
        require(count(result.source(), "METALLUM_SODIUM_LIGHT_SIDECAR_V2") == 1,
                "resource-bearing MSL was patched more than once");
    }

    private static void testMslPatchRejectsUnsafeAnchors() {
        SodiumLightSidecarMslPatcher.Result invalidSlots = SodiumLightSidecarMslPatcher.patch(
                SYNTHETIC_SODIUM_MSL,
                "main0",
                30,
                31
        );
        require(!invalidSlots.success(), "out-of-range Metal slots were accepted");
        require(invalidSlots.source().equals(SYNTHETIC_SODIUM_MSL), "failed slot validation changed MSL");

        String missingLight = SYNTHETIC_SODIUM_MSL.replace("in.a_LightAndData.xy", "in.a_Position.xy");
        SodiumLightSidecarMslPatcher.Result missingLightResult = SodiumLightSidecarMslPatcher.patch(
                missingLight,
                "main0",
                21,
                22
        );
        require(!missingLightResult.success(), "MSL without the live light member was patched");
        require(missingLightResult.source().equals(missingLight), "failed light validation changed MSL");

        String ambiguousEntry = SYNTHETIC_SODIUM_MSL
                + SYNTHETIC_SODIUM_MSL.substring(SYNTHETIC_SODIUM_MSL.indexOf("vertex main0_out main0"));
        SodiumLightSidecarMslPatcher.Result ambiguousResult = SodiumLightSidecarMslPatcher.patch(
                ambiguousEntry,
                "main0",
                21,
                22
        );
        require(!ambiguousResult.success(), "ambiguous MSL entry anchors were accepted");
        require(ambiguousResult.source().equals(ambiguousEntry), "failed anchor validation changed MSL");

        String missingBody = SYNTHETIC_SODIUM_MSL.replace(") {", ");");
        SodiumLightSidecarMslPatcher.Result missingBodyResult = SodiumLightSidecarMslPatcher.patch(
                missingBody,
                "main0",
                21,
                22
        );
        require(!missingBodyResult.success(), "MSL entry without a function body was patched");
        require(missingBodyResult.source().equals(missingBody), "failed body validation changed MSL");
    }

    private static ByteBuffer validGeometryVertex() {
        ByteBuffer geometry = ByteBuffer.allocate(SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE);
        geometry.put(16, (byte) 8);
        geometry.put(17, (byte) 8);
        return geometry;
    }

    private static ByteBuffer patternedGeometry(final int vertices, final int seed) {
        ByteBuffer geometry = ByteBuffer.allocate(
                Math.multiplyExact(vertices, SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE)
        );
        for (int offset = 0; offset < geometry.capacity(); offset++) {
            geometry.put(offset, (byte) (seed * 17 + offset * 13));
        }
        return geometry;
    }

    private static int packedUnsignedShort(final ByteBuffer buffer, final int offset) {
        return Byte.toUnsignedInt(buffer.get(offset))
                | Byte.toUnsignedInt(buffer.get(offset + 1)) << 8;
    }

    private static int count(final String value, final String needle) {
        int matches = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            matches++;
            offset += needle.length();
        }
        return matches;
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
