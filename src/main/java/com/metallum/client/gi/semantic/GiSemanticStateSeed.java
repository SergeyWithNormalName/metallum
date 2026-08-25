package com.metallum.client.gi.semantic;

/** Immutable clone of the material/shape facts available before Sodium emits actual quads. */
public record GiSemanticStateSeed(
        int localIndex,
        String canonicalMaterialKey,
        int occupancyUnorm16,
        int conservativeFaceMask,
        GiSemanticValidity validity,
        int provenance
) {
    public GiSemanticStateSeed {
        if (localIndex < 0 || localIndex >= GiSemanticSectionSeed.BLOCK_COUNT
                || canonicalMaterialKey == null || canonicalMaterialKey.isBlank()
                || occupancyUnorm16 < 0 || occupancyUnorm16 > GiSemanticPacking.UNORM16_MAX
                || (conservativeFaceMask & ~GiSemanticPacking.FACE_MASK) != 0
                || validity == null) {
            throw new IllegalArgumentException("Invalid G2 cloned-state seed");
        }
        GiSemanticProvenance.requireMask(provenance);
        if (validity == GiSemanticValidity.KNOWN_EMPTY && occupancyUnorm16 != 0) {
            throw new IllegalArgumentException("Known-empty G2 state cannot carry occupancy");
        }
        if (validity == GiSemanticValidity.KNOWN_CONTENT && occupancyUnorm16 == 0) {
            throw new IllegalArgumentException("Known-content G2 state requires occupancy");
        }
    }

    public static GiSemanticStateSeed unknown(final int localIndex) {
        return new GiSemanticStateSeed(
                localIndex, GiSemanticPalette.FALLBACK_KEY, GiSemanticPacking.UNORM16_MAX, 0,
                GiSemanticValidity.UNKNOWN, 0
        );
    }

    public static GiSemanticStateSeed empty(final int localIndex) {
        return new GiSemanticStateSeed(
                localIndex, GiSemanticPalette.AIR_KEY, 0, 0, GiSemanticValidity.KNOWN_EMPTY,
                GiSemanticProvenance.CLONED_STATE | GiSemanticProvenance.AUTHORITATIVE_EMPTY
        );
    }

    public static GiSemanticStateSeed content(
            final int localIndex,
            final String canonicalMaterialKey,
            final float occupancy,
            final int conservativeFaceMask,
            final int provenance
    ) {
        int packedOccupancy = Math.max(1, Short.toUnsignedInt(GiSemanticPacking.unorm16(occupancy)));
        return new GiSemanticStateSeed(
                localIndex, canonicalMaterialKey, packedOccupancy, conservativeFaceMask,
                GiSemanticValidity.KNOWN_CONTENT,
                provenance | GiSemanticProvenance.CLONED_STATE
        );
    }

    public static GiSemanticStateSeed fallback(final int localIndex, final int provenance) {
        return new GiSemanticStateSeed(
                localIndex, GiSemanticPalette.FALLBACK_KEY, GiSemanticPacking.UNORM16_MAX, 0,
                GiSemanticValidity.KNOWN_FALLBACK,
                provenance | GiSemanticProvenance.CLONED_STATE | GiSemanticProvenance.MODDED_FALLBACK
        );
    }
}
