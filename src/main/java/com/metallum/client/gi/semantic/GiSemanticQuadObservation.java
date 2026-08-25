package com.metallum.client.gi.semantic;

/** One immutable, quantized observation of a quad Sodium actually emitted for the accepted mesh. */
public record GiSemanticQuadObservation(
        int localIndex,
        int faceBit,
        String canonicalMaterialKey,
        short albedoRed,
        short albedoGreen,
        short albedoBlue,
        short emissionRed,
        short emissionGreen,
        short emissionBlue,
        short emissionIntensity,
        int areaWeight,
        int provenance
) {
    public GiSemanticQuadObservation {
        if (localIndex < 0 || localIndex >= GiSemanticSectionSeed.BLOCK_COUNT
                || canonicalMaterialKey == null || canonicalMaterialKey.isBlank()
                || areaWeight <= 0 || areaWeight > GiSemanticPacking.UNORM16_MAX) {
            throw new IllegalArgumentException("Invalid G2 quad observation");
        }
        GiSemanticPacking.faceIndex(faceBit);
        GiSemanticProvenance.requireMask(provenance);
        provenance |= GiSemanticProvenance.ACCEPTED_QUAD;
    }

    public static GiSemanticQuadObservation fromLinear(
            final int localIndex,
            final float normalX,
            final float normalY,
            final float normalZ,
            final String canonicalMaterialKey,
            final float albedoRed,
            final float albedoGreen,
            final float albedoBlue,
            final float emissionRed,
            final float emissionGreen,
            final float emissionBlue,
            final float emissionIntensity,
            final float normalizedArea,
            final int provenance
    ) {
        int area = Math.max(1, Short.toUnsignedInt(GiSemanticPacking.unorm16(normalizedArea)));
        return new GiSemanticQuadObservation(
                localIndex,
                GiSemanticPacking.faceForNormal(normalX, normalY, normalZ),
                canonicalMaterialKey,
                GiSemanticPacking.unorm16(albedoRed),
                GiSemanticPacking.unorm16(albedoGreen),
                GiSemanticPacking.unorm16(albedoBlue),
                GiSemanticPacking.unorm16(emissionRed),
                GiSemanticPacking.unorm16(emissionGreen),
                GiSemanticPacking.unorm16(emissionBlue),
                GiSemanticPacking.unorm16(emissionIntensity),
                area,
                provenance
        );
    }
}
