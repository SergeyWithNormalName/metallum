package com.metallum.client.sodium;

import java.util.Arrays;

/**
 * Immutable metadata that determines Sodium's resident vertex draw layout.
 *
 * <p>Vertex content is deliberately absent: the fast path refreshes every
 * compact 20-byte vertex. The skipped Sodium {@code setVertexData} call only
 * derives draw metadata from the allocation and vertex segments.</p>
 */
public final class SodiumTerrainMeshLayout {
    private final int geometryBytes;
    private final int[] vertexSegments;

    private SodiumTerrainMeshLayout(
            final int geometryBytes,
            final int[] vertexSegments
    ) {
        this.geometryBytes = geometryBytes;
        this.vertexSegments = vertexSegments;
    }

    public static SodiumTerrainMeshLayout capture(
            final int geometryBytes,
            final int[] vertexSegments
    ) {
        if (geometryBytes < 0
                || geometryBytes % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0) {
            throw new IllegalArgumentException("unaligned compact terrain geometry: " + geometryBytes);
        }
        return new SodiumTerrainMeshLayout(geometryBytes, vertexSegments.clone());
    }

    public boolean matches(final SodiumTerrainMeshLayout other) {
        return other != null
                && this.geometryBytes == other.geometryBytes
                && Arrays.equals(this.vertexSegments, other.vertexSegments);
    }

    public int geometryBytes() {
        return this.geometryBytes;
    }

    public int vertexCount() {
        return this.geometryBytes / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
    }
}
