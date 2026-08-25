package com.metallum.client.gi.semantic;

/** Minecraft-compatible section keys plus floor-correct block/section conversion. */
public final class GiSemanticCoordinates {
    private static final int X_BITS = 22;
    private static final int Z_BITS = 22;
    private static final int Y_BITS = 20;
    private static final long X_MASK = (1L << X_BITS) - 1L;
    private static final long Z_MASK = (1L << Z_BITS) - 1L;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + Z_BITS;

    private GiSemanticCoordinates() {
    }

    public static long sectionKey(final int sectionX, final int sectionY, final int sectionZ) {
        return ((long) sectionX & X_MASK) << X_SHIFT
                | ((long) sectionZ & Z_MASK) << Z_SHIFT
                | ((long) sectionY & Y_MASK);
    }

    public static int sectionX(final long key) {
        return signExtend(key >>> X_SHIFT, X_BITS);
    }

    public static int sectionY(final long key) {
        return signExtend(key & Y_MASK, Y_BITS);
    }

    public static int sectionZ(final long key) {
        return signExtend(key >>> Z_SHIFT & Z_MASK, Z_BITS);
    }

    public static int blockToSection(final int block) {
        return Math.floorDiv(block, GiSemanticSectionSeed.SECTION_EDGE);
    }

    public static int sectionToBlock(final int section) {
        return Math.multiplyExact(section, GiSemanticSectionSeed.SECTION_EDGE);
    }

    private static int signExtend(final long value, final int bits) {
        int shift = Long.SIZE - bits;
        return (int) (value << shift >> shift);
    }
}
