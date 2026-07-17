package com.metallum.client.voxel;

import java.util.Objects;

/**
 * One-byte material payload: upper three bits are {@link VoxelMaterialClass#abiId()},
 * lower five bits are a round-to-nearest transmittance value.
 */
public record VoxelMaterialDescriptor(VoxelMaterialClass materialClass, float transmittance) {
    public static final int CLASS_BITS = 3;
    public static final int TRANSMITTANCE_BITS = 5;
    public static final int TRANSMITTANCE_MAX = (1 << TRANSMITTANCE_BITS) - 1;
    public static final int CLASS_SHIFT = TRANSMITTANCE_BITS;
    public static final int PACKED_MASK = 0xff;

    public VoxelMaterialDescriptor {
        Objects.requireNonNull(materialClass, "materialClass");
        if (!Float.isFinite(transmittance) || transmittance < 0.0f || transmittance > 1.0f) {
            throw new IllegalArgumentException("Voxel transmittance must be finite and in [0, 1]");
        }
        // Store the quantized value itself so CPU and GPU-visible optical calculations agree.
        transmittance = quantizedTransmittance(transmittance) / (float) TRANSMITTANCE_MAX;
    }

    public static VoxelMaterialDescriptor defaults(final VoxelMaterialClass materialClass) {
        Objects.requireNonNull(materialClass, "materialClass");
        return new VoxelMaterialDescriptor(materialClass, materialClass.defaultTransmittance());
    }

    public int quantizedTransmittance() {
        return quantizedTransmittance(this.transmittance);
    }

    public float opacity() {
        return 1.0f - this.transmittance;
    }

    public int packedUnsignedByte() {
        return ((this.materialClass.abiId() << CLASS_SHIFT) | quantizedTransmittance()) & PACKED_MASK;
    }

    public static VoxelMaterialDescriptor fromPackedUnsignedByte(final int packed) {
        if ((packed & ~PACKED_MASK) != 0) {
            throw new IllegalArgumentException("Packed voxel material is not an unsigned byte: " + packed);
        }
        int classId = packed >>> CLASS_SHIFT;
        int transmittance = packed & TRANSMITTANCE_MAX;
        return new VoxelMaterialDescriptor(
                VoxelMaterialClass.fromAbiId(classId),
                transmittance / (float) TRANSMITTANCE_MAX
        );
    }

    private static int quantizedTransmittance(final float transmittance) {
        return Math.round(transmittance * TRANSMITTANCE_MAX);
    }
}
