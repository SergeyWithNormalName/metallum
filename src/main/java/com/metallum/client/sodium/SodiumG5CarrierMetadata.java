package com.metallum.client.sodium;

import com.metallum.client.gi.receiver.CompactPositionCarrierSafety;
import com.metallum.client.hdr.SodiumHdrSemantic;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.quad.FullTQuad;

import java.util.List;

/** Version-locked zero-storage packing for G5 carrier face masks. */
public final class SodiumG5CarrierMetadata {
    public static final int FACE_MASK = ModelQuadFacing.ALL;
    public static final int INFO_SOLID_SHIFT = 8;
    public static final int INFO_CUTOUT_SHIFT = 15;
    public static final int INFO_TRANSLUCENT_SHIFT = 22;
    public static final int RESIDENT_SHIFT = 8;
    public static final int STOCK_INFO_MASK = 0xff;
    public static final int STOCK_RESIDENT_MASK = FACE_MASK;
    public static final int RESIDENT_OWNER_BIT = 1 << 7;
    public static final int RESIDENT_METADATA_MASK = FACE_MASK << RESIDENT_SHIFT;

    private SodiumG5CarrierMetadata() {
    }

    public static int infoShift(final TerrainRenderPass pass) {
        if (pass == DefaultTerrainRenderPasses.SOLID) {
            return INFO_SOLID_SHIFT;
        }
        if (pass == DefaultTerrainRenderPasses.CUTOUT) {
            return INFO_CUTOUT_SHIFT;
        }
        if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
            return INFO_TRANSLUCENT_SHIFT;
        }
        throw new IllegalArgumentException("G5 carrier metadata received an unknown terrain pass");
    }

    public static int replaceInfoFaceMask(
            final int packedMasks,
            final TerrainRenderPass pass,
            final int faceMask
    ) {
        requireFaceMask(faceMask);
        int shift = infoShift(pass);
        return (packedMasks & ~(FACE_MASK << shift)) | (faceMask << shift);
    }

    public static int infoFaceMask(final int infoFlags, final TerrainRenderPass pass) {
        return (infoFlags >>> infoShift(pass)) & FACE_MASK;
    }

    public static int stockResidentFaceMask(final int residentMask) {
        return residentMask & STOCK_RESIDENT_MASK;
    }

    public static int residentCarrierFaceMask(final int residentMask) {
        return (residentMask >>> RESIDENT_SHIFT) & FACE_MASK;
    }

    public static int withResidentCarrierFaceMask(
            final int residentMask,
            final int carrierFaceMask
    ) {
        requireFaceMask(carrierFaceMask);
        int allowed = STOCK_RESIDENT_MASK | RESIDENT_OWNER_BIT | RESIDENT_METADATA_MASK;
        if ((residentMask & ~allowed) != 0) {
            throw new IllegalArgumentException("Sodium resident slice mask has a foreign high-bit owner");
        }
        int extensionBits = residentMask & (RESIDENT_OWNER_BIT | RESIDENT_METADATA_MASK);
        if (extensionBits != 0 && (residentMask & RESIDENT_OWNER_BIT) == 0) {
            throw new IllegalArgumentException("Sodium resident carrier bits lack the G5 owner marker");
        }
        int stockMask = stockResidentFaceMask(residentMask);
        if ((carrierFaceMask & ~stockMask) != 0) {
            throw new IllegalArgumentException("G5 carrier metadata names a non-resident face slice");
        }
        return stockMask | RESIDENT_OWNER_BIT | (carrierFaceMask << RESIDENT_SHIFT);
    }

    public static int mergeInfoFlags(final int stockFlags, final int packedMasks) {
        if ((stockFlags & ~0x7) != 0 || (packedMasks & STOCK_INFO_MASK) != 0) {
            throw new IllegalArgumentException("BuiltSectionInfo.flags has a foreign G5 metadata owner");
        }
        return stockFlags | packedMasks;
    }

    /**
     * Recomputes the modified translucent mesh's carrier census from the live BSP slots. Sodium
     * can leave a null hole whose old compact bytes still contain a G5 code, so the finalized
     * high-water buffer is not an ownership source. The index loop is allocation-free and keeps
     * scanning after the first G5 quad so a later malformed live quad still rejects the stage.
     */
    public static boolean hasLiveG5Carrier(final List<?> bspSlots) {
        if (!CompactPositionCarrierSafety.isSafe()) {
            return false;
        }
        boolean liveHasG5 = false;
        int size = bspSlots.size();
        for (int index = 0; index < size; index++) {
            Object slot = bspSlots.get(index);
            if (slot == null) {
                continue;
            }
            if (!(slot instanceof FullTQuad quad) || quad.isInvalid()) {
                CompactPositionCarrierSafety.reportConflict(
                        "Sodium BSP contains an unexpected live translucent quad"
                );
                return false;
            }
            int carrierCode = SodiumHdrSemantic.compactPositionCarrierCode(quad.getVertices());
            if (!CompactPositionCarrierSafety.isSafe()) {
                return false;
            }
            liveHasG5 |= (carrierCode & SodiumHdrSemantic.POSITION_CARRIER_G5_BIT) != 0;
        }
        return liveHasG5;
    }

    private static void requireFaceMask(final int faceMask) {
        if ((faceMask & ~FACE_MASK) != 0) {
            throw new IllegalArgumentException("G5 carrier face mask exceeds Sodium's seven slices");
        }
    }
}
