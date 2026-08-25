package com.metallum.client.gi.field;

import com.metallum.client.gi.semantic.GiSemanticFieldSnapshot;
import com.metallum.client.gi.semantic.GiSemanticValidity;

import java.util.HexFormat;
import java.util.Objects;

/** Deterministic, allocation-bounded CPU semantic truth to G2 Metal-plane encoder. */
public final class GiSemanticGpuEncoder {
    private static final float UNORM16_SCALE = 1.0F / 65_535.0F;

    private GiSemanticGpuEncoder() {
    }

    /**
     * Emission is encoded as non-premultiplied half RGB plus half intensity. The mip kernel uses
     * intensity as the reduction weight and stores averaged intensity as support; it never sums
     * emitted energy. UNKNOWN and KNOWN_FALLBACK carry zero RGB and zero intensity.
     */
    public static GiSemanticGpuSnapshot encode(final GiSemanticFieldSnapshot source) {
        Objects.requireNonNull(source, "source");
        short[] albedo = source.albedoRgb();
        short[] emission = source.emissionRgbIntensity();
        byte[] occupancy = source.occupancy();
        byte[] medium = source.mediumMasks();
        byte[] validity = source.validity();
        byte[] provenance = source.provenance();
        byte[] faces = source.faceWeights();
        byte[] coverage = source.knownCoverage();
        short[] palette = source.dominantMaterialIds();

        short[] materialPlane = new short[GiSemanticGpuSnapshot.CELL_COUNT * 4];
        short[] emissionPlane = new short[GiSemanticGpuSnapshot.CELL_COUNT * 4];
        byte[] faces0Plane = new byte[GiSemanticGpuSnapshot.CELL_COUNT * 4];
        byte[] faces1Plane = new byte[GiSemanticGpuSnapshot.CELL_COUNT * 2];
        byte[] statePlane = new byte[GiSemanticGpuSnapshot.CELL_COUNT * 4];
        short[] palettePlane = palette.clone();
        byte[] coveragePlane = coverage.clone();

        for (int cell = 0; cell < GiSemanticGpuSnapshot.CELL_COUNT; cell++) {
            int materialBase = cell * 4;
            int rgbBase = cell * 3;
            int validityId = Byte.toUnsignedInt(validity[cell]);
            boolean content = validityId == GiSemanticValidity.KNOWN_CONTENT.abiId();
            boolean empty = validityId == GiSemanticValidity.KNOWN_EMPTY.abiId();
            if (content) {
                materialPlane[materialBase] = albedo[rgbBase];
                materialPlane[materialBase + 1] = albedo[rgbBase + 1];
                materialPlane[materialBase + 2] = albedo[rgbBase + 2];
                materialPlane[materialBase + 3] = (short) (Byte.toUnsignedInt(occupancy[cell]) * 257);
                if (Byte.toUnsignedInt(coverage[cell]) != 0) {
                    int emissionBase = cell * 4;
                    emissionPlane[emissionBase] = unorm16ToHalf(emission[emissionBase]);
                    emissionPlane[emissionBase + 1] = unorm16ToHalf(emission[emissionBase + 1]);
                    emissionPlane[emissionBase + 2] = unorm16ToHalf(emission[emissionBase + 2]);
                    emissionPlane[emissionBase + 3] = unorm16ToHalf(emission[emissionBase + 3]);
                }
                int faceBase = cell * 6;
                System.arraycopy(faces, faceBase, faces0Plane, materialBase, 4);
                faces1Plane[cell * 2] = faces[faceBase + 4];
                faces1Plane[cell * 2 + 1] = faces[faceBase + 5];
            } else if (empty) {
                palettePlane[cell] = (short) GiSemanticFieldGpuResources.PALETTE_AIR;
            } else {
                materialPlane[materialBase + 3] = (short) 0xffff;
                if (validityId == GiSemanticValidity.KNOWN_FALLBACK.abiId()) {
                    palettePlane[cell] = (short) GiSemanticFieldGpuResources.PALETTE_FALLBACK;
                } else {
                    palettePlane[cell] = (short) GiSemanticFieldGpuResources.PALETTE_UNKNOWN;
                    coveragePlane[cell] = 0;
                }
            }
            statePlane[materialBase] = medium[cell];
            statePlane[materialBase + 1] = validity[cell];
            statePlane[materialBase + 2] = provenance[cell];
        }

        return new GiSemanticGpuSnapshot(
                source.world().worldGeneration(),
                source.clipmapGeneration(),
                source.paletteGeneration(),
                source.contentGeneration(),
                source.origins(),
                HexFormat.of().parseHex(source.digest()),
                materialPlane,
                emissionPlane,
                faces0Plane,
                faces1Plane,
                statePlane,
                palettePlane,
                coveragePlane
        );
    }

    private static short unorm16ToHalf(final short value) {
        return Float.floatToFloat16(Short.toUnsignedInt(value) * UNORM16_SCALE);
    }
}
