package com.metallum.client.voxel;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounded CPU mirror of native-accepted L5 bricks used to build cached L6 point shadows.
 * Keys use the same toroidal destination as the GPU, while each value retains its logical tag.
 */
public final class VoxelShadowCacheMirror {
    private static final VoxelShadowCacheMirror GLOBAL = new VoxelShadowCacheMirror();

    private final Map<Key, Brick> bricks = new HashMap<>();
    private long worldGeneration;
    private long clipmapGeneration;
    private long revision;
    private int queueRemaining = Integer.MAX_VALUE;
    /**
     * Last clipmap contract for which the native upload queue was fully drained.
     * Incremental batches may be exposed immediately only while this exact toroidal
     * topology is still in force: every acknowledged patch already carries its
     * logical tag, so that publication is atomic at the patch-batch boundary.
     */
    private VoxelClipmapSnapshot establishedClipmap;
    private Map<Key, Brick> publishedBricks;
    private Snapshot publishedSnapshot;

    private VoxelShadowCacheMirror() {
    }

    public static VoxelShadowCacheMirror global() {
        return GLOBAL;
    }

    public synchronized void reset() {
        this.bricks.clear();
        this.worldGeneration = 0L;
        this.clipmapGeneration = 0L;
        this.revision = 0L;
        this.queueRemaining = Integer.MAX_VALUE;
        this.establishedClipmap = null;
        this.publishedBricks = null;
        this.publishedSnapshot = null;
    }

    public synchronized void acknowledge(final VoxelUploadBatch batch) {
        boolean generationChanged = batch.world().generation() != this.worldGeneration
                || batch.clipmapGeneration() != this.clipmapGeneration;
        if (generationChanged) {
            this.bricks.clear();
            this.worldGeneration = batch.world().generation();
            this.clipmapGeneration = batch.clipmapGeneration();
            this.revision = 0L;
            this.establishedClipmap = null;
            this.publishedBricks = null;
            this.publishedSnapshot = null;
        }
        for (VoxelBrickPatch patch : batch.patches()) {
            this.bricks.put(
                    new Key(
                            patch.level(),
                            patch.destinationBrickX(),
                            patch.destinationBrickY(),
                            patch.destinationBrickZ()
                    ),
                    Brick.fromPatch(patch)
            );
        }
        this.queueRemaining = batch.queueRemaining();
        this.revision = Math.incrementExact(this.revision);
        this.publishedBricks = null;
        this.publishedSnapshot = null;
    }

    public synchronized Snapshot snapshot(final VoxelClipmapSnapshot clipmap) {
        if (clipmap == null
                || clipmap.world().generation() != this.worldGeneration
                || clipmap.clipmapGeneration() != this.clipmapGeneration) {
            return null;
        }
        if (this.queueRemaining != 0) {
            if (this.establishedClipmap == null
                    || !this.establishedClipmap.equals(clipmap)) {
                return null;
            }
            if (this.publishedSnapshot == null) {
                if (this.publishedBricks == null) {
                    this.publishedBricks = Map.copyOf(this.bricks);
                }
                this.publishedSnapshot = new Snapshot(
                        this.establishedClipmap, this.revision, this.publishedBricks, true
                );
            }
            return this.publishedSnapshot;
        }
        if (this.publishedSnapshot != null) {
            return this.publishedSnapshot.clipmap().equals(clipmap)
                    ? this.publishedSnapshot : null;
        }
        if (this.publishedBricks == null) {
            this.publishedBricks = Map.copyOf(this.bricks);
        }
        this.publishedSnapshot = new Snapshot(
                clipmap, this.revision, this.publishedBricks, true
        );
        this.establishedClipmap = clipmap;
        return this.publishedSnapshot;
    }

    public record Key(int level, int destinationX, int destinationY, int destinationZ) {
    }

    public static final class Brick {
        private final int logicalX;
        private final int logicalY;
        private final int logicalZ;
        private final int contentStamp;
        private final int[] occupancy;
        private final byte[] optical;

        private Brick(
                final int logicalX,
                final int logicalY,
                final int logicalZ,
                final int contentStamp,
                final int[] ownedOccupancy,
                final byte[] ownedOptical
        ) {
            if (contentStamp == 0 || ownedOccupancy == null
                    || ownedOccupancy.length != VoxelBrickPatch.OCCUPANCY_WORDS
                    || ownedOptical == null || ownedOptical.length == 0) {
                throw new IllegalArgumentException("Invalid L6 cache-mirror brick");
            }
            this.logicalX = logicalX;
            this.logicalY = logicalY;
            this.logicalZ = logicalZ;
            this.contentStamp = contentStamp;
            this.occupancy = ownedOccupancy;
            this.optical = ownedOptical;
        }

        private static Brick fromPatch(final VoxelBrickPatch patch) {
            return new Brick(
                    patch.logicalBrickX(),
                    patch.logicalBrickY(),
                    patch.logicalBrickZ(),
                    patch.contentStamp(),
                    patch.occupancyWords(),
                    patch.opticalPayload()
            );
        }

        public int logicalX() {
            return this.logicalX;
        }

        public int logicalY() {
            return this.logicalY;
        }

        public int logicalZ() {
            return this.logicalZ;
        }

        public int contentStamp() {
            return this.contentStamp;
        }

        public int[] occupancy() {
            return this.occupancy;
        }

        public byte[] optical() {
            return this.optical;
        }
    }

    public record Snapshot(
            VoxelClipmapSnapshot clipmap,
            long revision,
            Map<Key, Brick> bricks,
            boolean current
    ) {
        public Snapshot {
            if (clipmap == null || revision <= 0L || bricks == null) {
                throw new IllegalArgumentException("Invalid L6 cache-mirror snapshot");
            }
            bricks = Map.copyOf(bricks);
        }
    }
}
