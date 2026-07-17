package com.metallum.client.voxel;

/**
 * Compact optical categories. The numeric identifier is part of the GPU ABI. ID seven is
 * the fail-closed fallback used when state/shape extraction cannot establish a safe class.
 */
public enum VoxelMaterialClass {
    AIR(0, 1.0f),
    OPAQUE(1, 0.0f),
    CUTOUT(2, 0.0f),
    GLASS(3, 0.75f),
    FOLIAGE(4, 0.55f),
    WATER(5, 0.70f),
    TRANSLUCENT(6, 0.50f),
    UNKNOWN_CONSERVATIVE(7, 0.0f);

    private final int abiId;
    private final float defaultTransmittance;

    VoxelMaterialClass(final int abiId, final float defaultTransmittance) {
        this.abiId = abiId;
        this.defaultTransmittance = defaultTransmittance;
    }

    public int abiId() {
        return this.abiId;
    }

    public float defaultTransmittance() {
        return this.defaultTransmittance;
    }

    public static VoxelMaterialClass fromAbiId(final int abiId) {
        for (VoxelMaterialClass value : values()) {
            if (value.abiId == abiId) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown voxel material ABI ID: " + abiId);
    }
}
