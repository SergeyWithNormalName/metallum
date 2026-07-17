package com.metallum.client.voxel;

/** Fixed sub-block occupancy resolutions supported by the L5 clipmap contract. */
public enum VoxelSubdivision {
    ONE(1),
    TWO(2),
    FOUR(4);

    private final int scale;
    private final int cellCount;

    VoxelSubdivision(final int scale) {
        this.scale = scale;
        this.cellCount = Math.multiplyExact(Math.multiplyExact(scale, scale), scale);
    }

    public int scale() {
        return this.scale;
    }

    public int cellCount() {
        return this.cellCount;
    }

    /**
     * Canonical X-fastest bit order used by Java masks and future upload packets.
     * The result is in {@code [0, cellCount)}.
     */
    public int cellIndex(final int x, final int y, final int z) {
        requireCoordinate(x, "x");
        requireCoordinate(y, "y");
        requireCoordinate(z, "z");
        return x + this.scale * (y + this.scale * z);
    }

    public long fullMask() {
        return this.cellCount == Long.SIZE ? -1L : (1L << this.cellCount) - 1L;
    }

    public static VoxelSubdivision fromScale(final int scale) {
        return switch (scale) {
            case 1 -> ONE;
            case 2 -> TWO;
            case 4 -> FOUR;
            default -> throw new IllegalArgumentException("Unsupported voxel subdivision: " + scale);
        };
    }

    private void requireCoordinate(final int coordinate, final String axis) {
        if (coordinate < 0 || coordinate >= this.scale) {
            throw new IndexOutOfBoundsException(
                    "Voxel " + axis + " coordinate " + coordinate + " outside [0, " + this.scale + ')'
            );
        }
    }
}
