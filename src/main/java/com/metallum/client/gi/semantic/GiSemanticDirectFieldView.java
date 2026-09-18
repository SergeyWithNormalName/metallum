package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldLayout;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Reusable render-thread view used by G3 to copy only bounded dirty semantic bricks.
 * It never exposes the backing arrays or retains a snapshot of mutable field truth.
 */
public final class GiSemanticDirectFieldView implements GiSemanticFieldIdentityView {
    public static final int BRICK_EDGE = 8;
    public static final int BRICKS_PER_AXIS = GiFieldLayout.CELLS_PER_AXIS / BRICK_EDGE;
    public static final int BRICKS_PER_CASCADE = BRICKS_PER_AXIS * BRICKS_PER_AXIS * BRICKS_PER_AXIS;
    public static final int BRICK_COUNT = GiFieldLayout.CASCADE_COUNT * BRICKS_PER_CASCADE;
    public static final int CELLS_PER_BRICK = BRICK_EDGE * BRICK_EDGE * BRICK_EDGE;
    /** half4 emission RGB/intensity, one G2 validity byte, seven reserved zero bytes. */
    public static final int CELL_BYTES = 16;
    static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final GiSemanticFieldAssembler assembler;

    GiSemanticDirectFieldView(final GiSemanticFieldAssembler assembler) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    public GiSemanticWorldToken world() { return this.assembler.world(); }
    public long clipmapGeneration() { return this.assembler.clipmapGeneration(); }
    public long paletteGeneration() { return this.assembler.palette().generation(); }
    public long contentGeneration() { return this.assembler.contentGeneration(); }
    public int originComponent(final int index) { return this.assembler.originComponent(index); }
    public long brickContentStamp(final int brickId) {
        return this.assembler.directBrickContentStamp(brickId);
    }

    public long copyBrick(
            final int brickId,
            final MemorySegment destination,
            final long destinationOffset,
            final int cellStride
    ) {
        return this.assembler.copyDirectBrick(brickId, destination, destinationOffset, cellStride);
    }

    public static int cascade(final int brickId) {
        requireBrickId(brickId);
        return brickId / BRICKS_PER_CASCADE;
    }

    public static int brickX(final int brickId) {
        requireBrickId(brickId);
        return (brickId % BRICKS_PER_CASCADE) % BRICKS_PER_AXIS;
    }

    public static int brickY(final int brickId) {
        requireBrickId(brickId);
        return ((brickId % BRICKS_PER_CASCADE) / BRICKS_PER_AXIS) % BRICKS_PER_AXIS;
    }

    public static int brickZ(final int brickId) {
        requireBrickId(brickId);
        return (brickId % BRICKS_PER_CASCADE) / (BRICKS_PER_AXIS * BRICKS_PER_AXIS);
    }

    static void requireBrickId(final int brickId) {
        if (brickId < 0 || brickId >= BRICK_COUNT) {
            throw new IllegalArgumentException("G3 brick ID is outside the fixed field");
        }
    }
}
