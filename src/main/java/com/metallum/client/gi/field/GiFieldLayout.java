package com.metallum.client.gi.field;

/** Fixed, world-snapped topology for the field-only G1 scaffold. */
public final class GiFieldLayout {
    public static final int CASCADE_COUNT = 3;
    public static final int CELLS_PER_AXIS = 32;
    public static final int CELLS_PER_CASCADE = CELLS_PER_AXIS * CELLS_PER_AXIS * CELLS_PER_AXIS;
    public static final int MIP_LEVEL_COUNT = 6;
    public static final int FIELD_CHANNELS = 4;
    public static final int FIELD_BYTES_PER_CELL = FIELD_CHANNELS * Short.BYTES;
    public static final int COVERAGE_BYTES_PER_CELL = Byte.BYTES;
    public static final long DIFFUSE_GI_BUDGET_BYTES = 24L * 1024L * 1024L;

    // Minecraft's smallest opaque/light-emitting unit is one block. C0 therefore uses one block
    // per cell so a floor and the air above it cannot collapse into one binary CONTENT cell.
    // C1/C2 retain their accepted coverage while C0 carries exact block-scale local transport.
    private static final int[] CELL_SIZES_BLOCKS = {1, 4, 8};
    // A two-block C0 origin quantum preserves the accepted scroll frequency and bounded work;
    // the physical cells themselves remain one block wide.
    private static final int[] ORIGIN_SNAP_BLOCKS = {2, 4, 8};

    private GiFieldLayout() {
    }

    public static int cellSizeBlocks(final int cascade) {
        validateCascade(cascade);
        return CELL_SIZES_BLOCKS[cascade];
    }

    /** World-space origin quantum. It may be coarser than the physical cell size. */
    public static int originSnapBlocks(final int cascade) {
        validateCascade(cascade);
        return ORIGIN_SNAP_BLOCKS[cascade];
    }

    public static int spanBlocks(final int cascade) {
        return Math.multiplyExact(CELLS_PER_AXIS, cellSizeBlocks(cascade));
    }

    public static int mipEdge(final int mip) {
        if (mip < 0 || mip >= MIP_LEVEL_COUNT) {
            throw new IndexOutOfBoundsException("Invalid G1 field mip: " + mip);
        }
        return CELLS_PER_AXIS >> mip;
    }

    public static int cellsIncludingMipsPerCascade() {
        int total = 0;
        for (int mip = 0; mip < MIP_LEVEL_COUNT; mip++) {
            int edge = mipEdge(mip);
            total = Math.addExact(total, Math.multiplyExact(Math.multiplyExact(edge, edge), edge));
        }
        return total;
    }

    public static long arithmeticPersistentBytes() {
        return Math.multiplyExact(
                (long) CASCADE_COUNT * cellsIncludingMipsPerCascade(),
                FIELD_BYTES_PER_CELL + COVERAGE_BYTES_PER_CELL
        );
    }

    public static int centeredOriginBlock(final int cameraBlock, final int cascade) {
        int cellSize = cellSizeBlocks(cascade);
        int unsnapped = cameraBlock - spanBlocks(cascade) / 2;
        int snap = originSnapBlocks(cascade);
        if (snap % cellSize != 0) {
            throw new IllegalStateException("GI origin quantum lost cell alignment");
        }
        return Math.multiplyExact(Math.floorDiv(unsnapped, snap), snap);
    }

    public static boolean contains(
            final int worldX,
            final int worldY,
            final int worldZ,
            final int originX,
            final int originY,
            final int originZ,
            final int cascade
    ) {
        int span = spanBlocks(cascade);
        return worldX >= originX && worldX < originX + span
                && worldY >= originY && worldY < originY + span
                && worldZ >= originZ && worldZ < originZ + span;
    }

    public static int cellIndex(final int x, final int y, final int z, final int edge) {
        if (edge <= 0 || x < 0 || x >= edge || y < 0 || y >= edge || z < 0 || z >= edge) {
            throw new IndexOutOfBoundsException("G1 field cell is outside edge " + edge);
        }
        return (z * edge + y) * edge + x;
    }

    private static void validateCascade(final int cascade) {
        if (cascade < 0 || cascade >= CASCADE_COUNT) {
            throw new IndexOutOfBoundsException("Invalid G1 field cascade: " + cascade);
        }
    }
}
