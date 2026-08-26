package com.metallum.client.gi.source;

import com.metallum.client.gi.field.GiFieldLayout;

/** Fixed CPU contract for G3 source-injection bricks. */
public final class GiDirectSourceLayout {
    public static final int CASCADE_COUNT = GiFieldLayout.CASCADE_COUNT;
    public static final int CELLS_PER_AXIS = GiFieldLayout.CELLS_PER_AXIS;
    public static final int BRICK_EDGE_CELLS = 8;
    public static final int BRICKS_PER_AXIS = CELLS_PER_AXIS / BRICK_EDGE_CELLS;
    public static final int BRICKS_PER_CASCADE = BRICKS_PER_AXIS * BRICKS_PER_AXIS * BRICKS_PER_AXIS;
    public static final int TOTAL_BRICKS = CASCADE_COUNT * BRICKS_PER_CASCADE;
    public static final int MAX_DRAIN_PER_FRAME = 8;
    public static final int MAX_STATIC_SOURCES_PER_BRICK = 16;

    private GiDirectSourceLayout() {
    }

    public static int cellSizeBlocks(final int cascade) {
        validateCascade(cascade);
        return GiFieldLayout.cellSizeBlocks(cascade);
    }

    public static int brickId(final int cascade, final int brickX, final int brickY, final int brickZ) {
        validateCascade(cascade);
        validateBrickCoordinate(brickX);
        validateBrickCoordinate(brickY);
        validateBrickCoordinate(brickZ);
        return cascade * BRICKS_PER_CASCADE + (brickZ * BRICKS_PER_AXIS + brickY) * BRICKS_PER_AXIS + brickX;
    }

    public static int cascadeForBrickId(final int brickId) {
        validateBrickId(brickId);
        return brickId / BRICKS_PER_CASCADE;
    }

    public static int brickX(final int brickId) {
        validateBrickId(brickId);
        return brickId % BRICKS_PER_AXIS;
    }

    public static int brickY(final int brickId) {
        validateBrickId(brickId);
        return (brickId / BRICKS_PER_AXIS) % BRICKS_PER_AXIS;
    }

    public static int brickZ(final int brickId) {
        validateBrickId(brickId);
        return (brickId % BRICKS_PER_CASCADE) / (BRICKS_PER_AXIS * BRICKS_PER_AXIS);
    }

    /** Returns -1 when the absolute world block is outside this world-snapped cascade. */
    public static int brickIdForWorld(
            final int cascade, final int originX, final int originY, final int originZ,
            final int worldX, final int worldY, final int worldZ
    ) {
        validateCascade(cascade);
        int cellSize = cellSizeBlocks(cascade);
        int cellX = Math.floorDiv(worldX - originX, cellSize);
        int cellY = Math.floorDiv(worldY - originY, cellSize);
        int cellZ = Math.floorDiv(worldZ - originZ, cellSize);
        if (cellX < 0 || cellX >= CELLS_PER_AXIS || cellY < 0 || cellY >= CELLS_PER_AXIS
                || cellZ < 0 || cellZ >= CELLS_PER_AXIS) {
            return -1;
        }
        return brickId(cascade, Math.floorDiv(cellX, BRICK_EDGE_CELLS),
                Math.floorDiv(cellY, BRICK_EDGE_CELLS), Math.floorDiv(cellZ, BRICK_EDGE_CELLS));
    }

    public static int brickMinWorldBlock(final int cascade, final int originBlock, final int brickCoordinate) {
        validateCascade(cascade);
        validateBrickCoordinate(brickCoordinate);
        return Math.addExact(originBlock, Math.multiplyExact(brickCoordinate * BRICK_EDGE_CELLS,
                cellSizeBlocks(cascade)));
    }

    public static void validateBrickId(final int brickId) {
        if (brickId < 0 || brickId >= TOTAL_BRICKS) {
            throw new IndexOutOfBoundsException("G3 brick id outside fixed topology: " + brickId);
        }
    }

    private static void validateCascade(final int cascade) {
        if (cascade < 0 || cascade >= CASCADE_COUNT) {
            throw new IndexOutOfBoundsException("G3 cascade outside fixed topology: " + cascade);
        }
    }

    private static void validateBrickCoordinate(final int coordinate) {
        if (coordinate < 0 || coordinate >= BRICKS_PER_AXIS) {
            throw new IndexOutOfBoundsException("G3 brick coordinate outside fixed topology: " + coordinate);
        }
    }
}
