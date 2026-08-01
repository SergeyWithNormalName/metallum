package com.metallum.client.voxel;

import java.util.Arrays;

/** Immutable 16^3, 4x-subdivision source data for one accepted Sodium section. */
public final class VoxelSectionSnapshot {
    public static final int SECTION_EDGE = 16;
    public static final int BLOCK_COUNT = SECTION_EDGE * SECTION_EDGE * SECTION_EDGE;
    public static final int SOURCE_SUBDIVISION = 4;
    private static final VoxelSectionSnapshot EMPTY = new VoxelSectionSnapshot(
            new long[BLOCK_COUNT],
            new byte[BLOCK_COUNT],
            new byte[BLOCK_COUNT]
    );

    private final long[] occupancyMasks;
    private final byte[] optical;
    private final byte[] chromaticIds;

    public VoxelSectionSnapshot(final long[] occupancyMasks, final byte[] optical) {
        this(occupancyMasks, optical, new byte[BLOCK_COUNT]);
    }

    public VoxelSectionSnapshot(
            final long[] occupancyMasks,
            final byte[] optical,
            final byte[] chromaticIds
    ) {
        if (occupancyMasks == null || occupancyMasks.length != BLOCK_COUNT) {
            throw new IllegalArgumentException("Voxel section requires exactly 4096 occupancy masks");
        }
        if (optical == null || optical.length != BLOCK_COUNT) {
            throw new IllegalArgumentException("Voxel section requires exactly 4096 optical values");
        }
        if (chromaticIds == null || chromaticIds.length != BLOCK_COUNT) {
            throw new IllegalArgumentException("Voxel section requires exactly 4096 chromatic values");
        }
        for (byte chromaticId : chromaticIds) {
            int id = Byte.toUnsignedInt(chromaticId);
            if (id > VoxelChromaticFilter.MAX_ID) {
                throw new IllegalArgumentException("Voxel section contains an invalid chromatic palette ID");
            }
        }
        this.occupancyMasks = Arrays.copyOf(occupancyMasks, occupancyMasks.length);
        this.optical = Arrays.copyOf(optical, optical.length);
        this.chromaticIds = Arrays.copyOf(chromaticIds, chromaticIds.length);
    }

    /** Shared immutable zero snapshot used by Sodium's no-mesh empty-section result. */
    public static VoxelSectionSnapshot empty() {
        return EMPTY;
    }

    public long occupancyMask(final int localIndex) {
        requireLocalIndex(localIndex);
        return this.occupancyMasks[localIndex];
    }

    public byte optical(final int localIndex) {
        requireLocalIndex(localIndex);
        return this.optical[localIndex];
    }

    public long[] occupancyMasks() {
        return Arrays.copyOf(this.occupancyMasks, this.occupancyMasks.length);
    }

    public byte[] opticalValues() {
        return Arrays.copyOf(this.optical, this.optical.length);
    }

    public byte chromaticId(final int localIndex) {
        requireLocalIndex(localIndex);
        return this.chromaticIds[localIndex];
    }

    public byte[] chromaticIds() {
        return Arrays.copyOf(this.chromaticIds, this.chromaticIds.length);
    }

    long occupancyMaskUnchecked(final int localIndex) {
        return this.occupancyMasks[localIndex];
    }

    byte opticalUnchecked(final int localIndex) {
        return this.optical[localIndex];
    }

    byte chromaticIdUnchecked(final int localIndex) {
        return this.chromaticIds[localIndex];
    }

    private static void requireLocalIndex(final int localIndex) {
        if (localIndex < 0 || localIndex >= BLOCK_COUNT) {
            throw new IllegalArgumentException("localIndex is outside a 16^3 section");
        }
    }
}
