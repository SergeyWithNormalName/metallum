package com.metallum.client.voxel;

import java.util.HashMap;
import java.util.Map;

/** CPU mirror of batches accepted by the native L5 command encoder, used only by the HUD. */
public final class VoxelPreviewMirror {
    private static final VoxelPreviewMirror GLOBAL = new VoxelPreviewMirror();
    private final Map<Key, Brick> bricks = new HashMap<>();
    private long worldGeneration;
    private long clipmapGeneration;

    private VoxelPreviewMirror() {
    }

    public static VoxelPreviewMirror global() {
        return GLOBAL;
    }

    public synchronized void acknowledge(final VoxelUploadBatch batch) {
        if (batch.world().generation() != this.worldGeneration
                || batch.clipmapGeneration() != this.clipmapGeneration) {
            this.bricks.clear();
            this.worldGeneration = batch.world().generation();
            this.clipmapGeneration = batch.clipmapGeneration();
        }
        for (VoxelBrickPatch patch : batch.patches()) {
            this.bricks.put(new Key(patch.level(), patch.logicalBrickX(), patch.logicalBrickY(),
                    patch.logicalBrickZ()), new Brick(patch.contentStamp(), patch.occupancyWords(),
                    patch.opticalPayload()));
        }
    }

    public synchronized Snapshot snapshot(final VoxelClipmapSnapshot clipmap, final int level) {
        if (clipmap == null || level < 0 || level >= clipmap.levels().size()
                || clipmap.world().generation() != this.worldGeneration
                || clipmap.clipmapGeneration() != this.clipmapGeneration) {
            return null;
        }
        return new Snapshot(clipmap.levels().get(level), Map.copyOf(this.bricks));
    }

    public record Key(int level, int x, int y, int z) {
    }

    public record Brick(int contentStamp, int[] occupancy, byte[] optical) {
        public Brick {
            occupancy = occupancy.clone();
            optical = optical.clone();
        }
    }

    public record Snapshot(VoxelClipmapSnapshot.Level level, Map<Key, Brick> bricks) {
    }
}
