package com.metallum.client.gi.field;

import java.util.Arrays;

/** Immutable one-shot mechanical field payload. G1 assigns no lighting semantics to its channels. */
public final class GiFieldSnapshot {
    private final long worldGeneration;
    private final int[] origins;
    private final short[] packedField;
    private final byte[] coverage;

    public GiFieldSnapshot(
            final long worldGeneration,
            final int[] origins,
            final short[] packedField,
            final byte[] coverage
    ) {
        if (worldGeneration <= 0L) {
            throw new IllegalArgumentException("G1 snapshot generation must be positive");
        }
        int totalCells = Math.multiplyExact(GiFieldLayout.CASCADE_COUNT, GiFieldLayout.CELLS_PER_CASCADE);
        if (origins == null || origins.length != GiFieldLayout.CASCADE_COUNT * 3
                || packedField == null || packedField.length != totalCells * GiFieldLayout.FIELD_CHANNELS
                || coverage == null || coverage.length != totalCells) {
            throw new IllegalArgumentException("G1 snapshot arrays do not match the fixed field topology");
        }
        this.worldGeneration = worldGeneration;
        this.origins = origins.clone();
        this.packedField = packedField.clone();
        this.coverage = coverage.clone();
    }

    public static GiFieldSnapshot empty(final long worldGeneration, final int cameraX, final int cameraY,
                                        final int cameraZ) {
        int[] origins = new int[GiFieldLayout.CASCADE_COUNT * 3];
        for (int cascade = 0; cascade < GiFieldLayout.CASCADE_COUNT; cascade++) {
            origins[cascade * 3] = GiFieldLayout.centeredOriginBlock(cameraX, cascade);
            origins[cascade * 3 + 1] = GiFieldLayout.centeredOriginBlock(cameraY, cascade);
            origins[cascade * 3 + 2] = GiFieldLayout.centeredOriginBlock(cameraZ, cascade);
        }
        int cells = Math.multiplyExact(GiFieldLayout.CASCADE_COUNT, GiFieldLayout.CELLS_PER_CASCADE);
        return new GiFieldSnapshot(
                worldGeneration,
                origins,
                new short[cells * GiFieldLayout.FIELD_CHANNELS],
                new byte[cells]
        );
    }

    public long worldGeneration() {
        return this.worldGeneration;
    }

    public int[] origins() {
        return this.origins.clone();
    }

    public short[] packedField() {
        return this.packedField.clone();
    }

    public byte[] coverage() {
        return this.coverage.clone();
    }

    @Override
    public String toString() {
        return "GiFieldSnapshot[generation=" + this.worldGeneration
                + ", origins=" + Arrays.toString(this.origins) + "]";
    }
}
