package com.metallum.client.gi.semantic;

/** Exact byte-sized source attribution carried by every semantic cell. */
public final class GiSemanticProvenance {
    public static final int CLONED_STATE = 1;
    public static final int ACCEPTED_QUAD = 1 << 1;
    public static final int AUTHORITATIVE_EMPTY = 1 << 2;
    public static final int MODDED_FALLBACK = 1 << 3;
    public static final int PALETTE_FALLBACK = 1 << 4;
    public static final int RESOURCE_DERIVED = 1 << 5;
    public static final int MATERIAL_DERIVED = 1 << 6;
    public static final int BIOME_TINTED = 1 << 7;
    public static final int MASK = 0xff;

    private GiSemanticProvenance() {
    }

    public static int requireMask(final int mask) {
        if ((mask & ~MASK) != 0) {
            throw new IllegalArgumentException("G2 provenance does not fit one byte: " + mask);
        }
        return mask;
    }
}
