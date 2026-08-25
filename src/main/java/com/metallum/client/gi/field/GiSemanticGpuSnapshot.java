package com.metallum.client.gi.field;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Immutable, full-field G2 GPU upload packet. This deliberately does not depend on the CPU
 * section assembler: the render-thread adapter must make palette validity and emission scaling
 * explicit before an upload can cross the versioned native ABI.
 */
public final class GiSemanticGpuSnapshot {
    public static final int CASCADE_COUNT = 3;
    public static final int EDGE = 32;
    public static final int MIP_COUNT = 6;
    public static final int CELLS_PER_CASCADE = EDGE * EDGE * EDGE;
    public static final int CELL_COUNT = CASCADE_COUNT * CELLS_PER_CASCADE;
    public static final int ORIGIN_COMPONENTS = CASCADE_COUNT * 3;
    public static final int DIGEST_BYTES = 32;

    private final long worldGeneration;
    private final long clipmapGeneration;
    private final long paletteGeneration;
    private final long contentGeneration;
    private final int[] origins;
    private final byte[] sourceDigest;
    private final short[] materialRgbaUnorm16;
    private final short[] emissionRgbaFloat16;
    private final byte[] faceWeights0RgbaUnorm8;
    private final byte[] faceWeights1RgUnorm8;
    private final byte[] stateRgbaUint8;
    private final short[] paletteIdsUint16;
    private final byte[] knownCoverageUnorm8;

    public GiSemanticGpuSnapshot(
            final long worldGeneration,
            final long clipmapGeneration,
            final long paletteGeneration,
            final long contentGeneration,
            final int[] origins,
            final byte[] sourceDigest,
            final short[] materialRgbaUnorm16,
            final short[] emissionRgbaFloat16,
            final byte[] faceWeights0RgbaUnorm8,
            final byte[] faceWeights1RgUnorm8,
            final byte[] stateRgbaUint8,
            final short[] paletteIdsUint16,
            final byte[] knownCoverageUnorm8
    ) {
        if (worldGeneration <= 0L || clipmapGeneration <= 0L
                || paletteGeneration <= 0L || contentGeneration <= 0L) {
            throw new IllegalArgumentException("G2 snapshot generations must be positive");
        }
        this.worldGeneration = worldGeneration;
        this.clipmapGeneration = clipmapGeneration;
        this.paletteGeneration = paletteGeneration;
        this.contentGeneration = contentGeneration;
        this.origins = requireLength(origins, ORIGIN_COMPONENTS, "origins").clone();
        this.sourceDigest = requireLength(sourceDigest, DIGEST_BYTES, "sourceDigest").clone();
        this.materialRgbaUnorm16 = requireLength(
                materialRgbaUnorm16, CELL_COUNT * 4, "materialRgbaUnorm16").clone();
        this.emissionRgbaFloat16 = requireLength(
                emissionRgbaFloat16, CELL_COUNT * 4, "emissionRgbaFloat16").clone();
        this.faceWeights0RgbaUnorm8 = requireLength(
                faceWeights0RgbaUnorm8, CELL_COUNT * 4, "faceWeights0RgbaUnorm8").clone();
        this.faceWeights1RgUnorm8 = requireLength(
                faceWeights1RgUnorm8, CELL_COUNT * 2, "faceWeights1RgUnorm8").clone();
        this.stateRgbaUint8 = requireLength(stateRgbaUint8, CELL_COUNT * 4, "stateRgbaUint8").clone();
        this.paletteIdsUint16 = requireLength(paletteIdsUint16, CELL_COUNT, "paletteIdsUint16").clone();
        this.knownCoverageUnorm8 = requireLength(
                knownCoverageUnorm8, CELL_COUNT, "knownCoverageUnorm8").clone();
        validateStates(this.stateRgbaUint8);
    }

    public long worldGeneration() {
        return this.worldGeneration;
    }

    public long clipmapGeneration() {
        return this.clipmapGeneration;
    }

    public long contentGeneration() {
        return this.contentGeneration;
    }

    public long paletteGeneration() {
        return this.paletteGeneration;
    }

    public int[] origins() {
        return this.origins.clone();
    }

    public byte[] sourceDigest() {
        return this.sourceDigest.clone();
    }

    void copyPlanesTo(
            final MemorySegment material,
            final MemorySegment emission,
            final MemorySegment faces0,
            final MemorySegment faces1,
            final MemorySegment state,
            final MemorySegment palette,
            final MemorySegment coverage
    ) {
        MemorySegment.copy(MemorySegment.ofArray(this.materialRgbaUnorm16), ValueLayout.JAVA_SHORT, 0L,
                material, ValueLayout.JAVA_SHORT, 0L, this.materialRgbaUnorm16.length);
        MemorySegment.copy(MemorySegment.ofArray(this.emissionRgbaFloat16), ValueLayout.JAVA_SHORT, 0L,
                emission, ValueLayout.JAVA_SHORT, 0L, this.emissionRgbaFloat16.length);
        MemorySegment.copy(MemorySegment.ofArray(this.faceWeights0RgbaUnorm8), 0L,
                faces0, 0L, this.faceWeights0RgbaUnorm8.length);
        MemorySegment.copy(MemorySegment.ofArray(this.faceWeights1RgUnorm8), 0L,
                faces1, 0L, this.faceWeights1RgUnorm8.length);
        MemorySegment.copy(MemorySegment.ofArray(this.stateRgbaUint8), 0L,
                state, 0L, this.stateRgbaUint8.length);
        MemorySegment.copy(MemorySegment.ofArray(this.paletteIdsUint16), ValueLayout.JAVA_SHORT, 0L,
                palette, ValueLayout.JAVA_SHORT, 0L, this.paletteIdsUint16.length);
        MemorySegment.copy(MemorySegment.ofArray(this.knownCoverageUnorm8), 0L,
                coverage, 0L, this.knownCoverageUnorm8.length);
    }

    void copyHeaderMetadataTo(final MemorySegment header) {
        MemorySegment.copy(MemorySegment.ofArray(this.origins), ValueLayout.JAVA_INT, 0L,
                header, ValueLayout.JAVA_INT, 32L, this.origins.length);
        MemorySegment.copy(MemorySegment.ofArray(this.sourceDigest), 0L,
                header, 80L, this.sourceDigest.length);
    }

    private static void validateStates(final byte[] state) {
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            int base = cell * 4;
            int mediumMask = state[base] & 0xff;
            int validity = state[base + 1] & 0xff;
            int reserved = state[base + 3] & 0xff;
            if ((mediumMask & ~0x1f) != 0 || validity > 3 || reserved != 0) {
                throw new IllegalArgumentException("Invalid G2 state at cell " + cell);
            }
        }
    }

    private static int[] requireLength(final int[] value, final int length, final String name) {
        Objects.requireNonNull(value, name);
        if (value.length != length) {
            throw new IllegalArgumentException(name + " length must be " + length);
        }
        return value;
    }

    private static byte[] requireLength(final byte[] value, final int length, final String name) {
        Objects.requireNonNull(value, name);
        if (value.length != length) {
            throw new IllegalArgumentException(name + " length must be " + length);
        }
        return value;
    }

    private static short[] requireLength(final short[] value, final int length, final String name) {
        Objects.requireNonNull(value, name);
        if (value.length != length) {
            throw new IllegalArgumentException(name + " length must be " + length);
        }
        return value;
    }
}
