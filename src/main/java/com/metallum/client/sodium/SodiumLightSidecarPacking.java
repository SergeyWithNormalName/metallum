package com.metallum.client.sodium;

import java.nio.ByteBuffer;

/** Exact two-byte light companion for Sodium's compact 20-byte terrain vertices. */
public final class SodiumLightSidecarPacking {
    public static final int GEOMETRY_VERTEX_STRIDE = 20;
    public static final int SIDECAR_VERTEX_STRIDE = 2;

    private static final int BLOCK_COORDINATE_SHIFT = 0;
    private static final int SKY_COORDINATE_SHIFT = 8;

    private SodiumLightSidecarPacking() {
    }

    public static int packCoordinates(final int blockCoordinate, final int skyCoordinate) {
        requireRange("block light coordinate", blockCoordinate, 255);
        requireRange("sky light coordinate", skyCoordinate, 255);
        return (blockCoordinate << BLOCK_COORDINATE_SHIFT)
                | (skyCoordinate << SKY_COORDINATE_SHIFT);
    }

    public static int blockCoordinate(final int packed) {
        return (packed >>> BLOCK_COORDINATE_SHIFT) & 0xff;
    }

    public static int skyCoordinate(final int packed) {
        return (packed >>> SKY_COORDINATE_SHIFT) & 0xff;
    }

    /**
     * Packs the exact light coordinates already encoded in Sodium's compact
     * vertex payload. Material and HDR semantic bits remain static geometry data.
     * The source position is unchanged; the destination advances by the packed size.
     */
    public static int packGeometry(final ByteBuffer geometry, final ByteBuffer destination) {
        int sourcePosition = geometry.position();
        int sourceBytes = geometry.remaining();
        if (sourceBytes % GEOMETRY_VERTEX_STRIDE != 0) {
            throw new IllegalArgumentException(
                    "Sodium geometry payload is not a whole number of compact vertices: " + sourceBytes
            );
        }

        int vertexCount = sourceBytes / GEOMETRY_VERTEX_STRIDE;
        int requiredBytes = Math.multiplyExact(vertexCount, SIDECAR_VERTEX_STRIDE);
        if (destination.remaining() < requiredBytes) {
            throw new IllegalArgumentException(
                    "Light sidecar destination is too small: required=" + requiredBytes
                            + ", remaining=" + destination.remaining()
            );
        }

        int destinationPosition = destination.position();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int geometryOffset = sourcePosition + vertex * GEOMETRY_VERTEX_STRIDE;
            int packed = packCoordinates(
                    Byte.toUnsignedInt(geometry.get(geometryOffset + 16)),
                    Byte.toUnsignedInt(geometry.get(geometryOffset + 17))
            );
            int sidecarOffset = destinationPosition + vertex * SIDECAR_VERTEX_STRIDE;
            destination.put(sidecarOffset, (byte) packed);
            destination.put(sidecarOffset + 1, (byte) (packed >>> 8));
        }
        destination.position(destinationPosition + requiredBytes);
        return vertexCount;
    }

    private static void requireRange(final String name, final int value, final int maximum) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(name + " is outside 0.." + maximum + ": " + value);
        }
    }
}
