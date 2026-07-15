package com.metallum.client.metal.render;

import com.metallum.client.sodium.SodiumLightSidecar;
import com.metallum.client.sodium.SodiumLightSidecarPacking;

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
