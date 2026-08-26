package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldLayout;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Render-thread view of the frozen near-cascade G2 material truth admitted for G4 transport.
 * It exposes neither the mutable backing arrays nor the two outer cascades.
 */
public final class GiSemanticTransportFieldView {
    public static final int EDGE = GiFieldLayout.CELLS_PER_AXIS;
    public static final int CELL_COUNT = EDGE * EDGE * EDGE;
    /** ushort4 material, six ordered face weights, validity and known coverage. */
    public static final int CELL_BYTES = 16;
    public static final long PAYLOAD_BYTES = (long) CELL_COUNT * CELL_BYTES;

    public record CopyResult(long contentStamp, int knownContentCells, int unknownCells) {
        public CopyResult {
            if (contentStamp < 0L || knownContentCells < 0 || unknownCells < 0
                    || knownContentCells > CELL_COUNT || unknownCells > CELL_COUNT
                    || knownContentCells + unknownCells > CELL_COUNT) {
                throw new IllegalArgumentException("Invalid G4 near-cascade copy result");
            }
        }
    }

    private final GiSemanticFieldAssembler assembler;

    GiSemanticTransportFieldView(final GiSemanticFieldAssembler assembler) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    public GiSemanticWorldToken world() { return this.assembler.world(); }
    public long worldGeneration() { return this.assembler.world().worldGeneration(); }
    public long resourceEpoch() { return this.assembler.world().resourceEpoch(); }
    public long materialEpoch() { return this.assembler.world().materialEpoch(); }
    public long clipmapGeneration() { return this.assembler.clipmapGeneration(); }
    public long paletteGeneration() { return this.assembler.palette().generation(); }
    public long contentGeneration() { return this.assembler.contentGeneration(); }
    public int nearOriginX() { return this.assembler.originComponent(0); }
    public int nearOriginY() { return this.assembler.originComponent(1); }
    public int nearOriginZ() { return this.assembler.originComponent(2); }

    /** Copies one exact, deterministic near-cascade image without cloning the G2 backing arrays. */
    public CopyResult copyNearCascade(final MemorySegment destination) {
        return this.assembler.copyTransportNearCascade(destination);
    }
}
