package com.metallum.client.gi.semantic;

/** Coarse G2 medium bits. Mixed coarse cells retain every observed bit. */
public enum GiSemanticMedium {
    AIR(0),
    OPAQUE(1),
    CUTOUT(1 << 1),
    TRANSLUCENT(1 << 2),
    WATER(1 << 3),
    UNKNOWN_CONSERVATIVE(1 << 4);

    public static final int KNOWN_MASK = OPAQUE.mask | CUTOUT.mask | TRANSLUCENT.mask | WATER.mask;
    public static final int ALL_MASK = KNOWN_MASK | UNKNOWN_CONSERVATIVE.mask;

    private final int mask;

    GiSemanticMedium(final int mask) {
        this.mask = mask;
    }

    public int mask() {
        return this.mask;
    }
}
