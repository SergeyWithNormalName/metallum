package com.metallum.client.radiance;

import java.util.Objects;

/**
 * Compact, bounded in-flight payload for a 16^3 chunk section extracted from Sodium's LevelSlice.
 *
 * <p>Uses 16-bit half-precision floats (RGBA16F) and classification bytes instead of 32-bit floats
 * and separate albedo volumes. Discarded immediately upon publication into the source field.</p>
 */
public record CompactSectionPayload(
        long sectionKey,
        long worldGeneration,
        boolean isEmpty,
        short[] packedRgba,     // 4096 * 4 shorts (RGBA16F) = 32,768 bytes
        byte[] classification   // 4096 bytes: 0=empty, 1=occupied, 2=emissive
) {
    public static final int SECTION_BLOCK_COUNT = 16 * 16 * 16; // 4096
    public static final int SHORTS_PER_BLOCK = 4; // R, G, B, A in half-precision
    public static final int CONTENT_PAYLOAD_BYTES = (SECTION_BLOCK_COUNT * SHORTS_PER_BLOCK * Short.BYTES) + SECTION_BLOCK_COUNT; // 36,864 bytes
    public static final long ESTIMATED_NON_EMPTY_BYTES = CONTENT_PAYLOAD_BYTES;

    private static final short[] EMPTY_SHORTS = new short[0];
    private static final byte[] EMPTY_BYTES = new byte[0];

    public static CompactSectionPayload empty(final long sectionKey, final long worldGeneration) {
        return new CompactSectionPayload(
                sectionKey,
                worldGeneration,
                true,
                EMPTY_SHORTS,
                EMPTY_BYTES
        );
    }

    public CompactSectionPayload {
        Objects.requireNonNull(packedRgba, "packedRgba");
        Objects.requireNonNull(classification, "classification");
        if (!isEmpty && (packedRgba.length != SECTION_BLOCK_COUNT * SHORTS_PER_BLOCK
                || classification.length != SECTION_BLOCK_COUNT)) {
            throw new IllegalArgumentException("Invalid compact section payload array dimensions");
        }
    }

    /**
     * Calculates the exact primitive array payload bytes.
     */
    public long payloadByteSize() {
        if (this.isEmpty) {
            return 0L;
        }
        return (long) this.packedRgba.length * Short.BYTES + (long) this.classification.length;
    }

    /**
     * Calculates conservative retained JVM heap footprint including object and array headers.
     */
    public long totalHeapBytes() {
        if (this.isEmpty) {
            return 32L; // Object header + primitives + empty array refs
        }
        return 32L // Object header + primitives
                + 24L + ((long) this.packedRgba.length * Short.BYTES) // short[] header + elements
                + 24L + ((long) this.classification.length);           // byte[] header + elements
    }
}
