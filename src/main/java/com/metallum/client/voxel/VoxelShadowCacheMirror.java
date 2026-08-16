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
    /**
     * Exact origin contract captured for the most recently native-accepted batch. This lets L6
     * publish partial, logically tagged coverage after a scroll without interpreting a batch
     * from an older toroidal window as current.
     */
    private VoxelClipmapSnapshot acceptedClipmap;
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
        this.acceptedClipmap = null;
        this.publishedBricks = null;
        this.publishedSnapshot = null;
    }

    public synchronized void acknowledge(
            final VoxelUploadBatch batch,
            final VoxelClipmapSnapshot clipmap
    ) {
        validateBatchContract(batch, clipmap);
        boolean generationChanged = batch.world().generation() != this.worldGeneration
                || batch.clipmapGeneration() != this.clipmapGeneration;
        if (generationChanged) {
            this.bricks.clear();
            this.worldGeneration = batch.world().generation();
            this.clipmapGeneration = batch.clipmapGeneration();
            this.revision = 0L;
            this.acceptedClipmap = null;
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
        this.acceptedClipmap = clipmap;
        this.revision = Math.incrementExact(this.revision);
        this.publishedBricks = null;
        this.publishedSnapshot = null;
    }

    public synchronized Snapshot snapshot(final VoxelClipmapSnapshot clipmap) {
        if (clipmap == null
                || clipmap.world().generation() != this.worldGeneration
                || clipmap.clipmapGeneration() != this.clipmapGeneration
                || this.revision <= 0L
                || this.acceptedClipmap == null
                || !this.acceptedClipmap.equals(clipmap)) {
            return null;
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
        return this.publishedSnapshot;
    }

    /**
     * Returns the newest clipmap contract actually accepted by the native L5 upload ring.
     *
     * <p>The controller may publish a newer scroll origin one or two frames before its first
     * incoming brick reaches Metal. L6 keeps consuming the still-valid accepted window during
     * that gap instead of dropping every dynamic candidate.</p>
     */
    public synchronized Snapshot latestAcceptedSnapshot(
            final VoxelClipmapSnapshot requestedClipmap
    ) {
        if (requestedClipmap == null
                || requestedClipmap.world().generation() != this.worldGeneration
                || requestedClipmap.clipmapGeneration() != this.clipmapGeneration
                || this.revision <= 0L
                || this.acceptedClipmap == null) {
            return null;
        }
        if (this.publishedSnapshot != null) {
            return this.publishedSnapshot;
        }
        if (this.publishedBricks == null) {
            this.publishedBricks = Map.copyOf(this.bricks);
        }
        this.publishedSnapshot = new Snapshot(
                this.acceptedClipmap, this.revision, this.publishedBricks, true
        );
        return this.publishedSnapshot;
    }

    public static void validateBatchContract(
            final VoxelUploadBatch batch,
            final VoxelClipmapSnapshot clipmap
    ) {
        if (batch == null || clipmap == null
                || !batch.world().equals(clipmap.world())
                || batch.clipmapGeneration() != clipmap.clipmapGeneration()) {
            throw new IllegalArgumentException("L6 mirror batch lacks its exact clipmap contract");
        }
        for (VoxelBrickPatch patch : batch.patches()) {
            if (patch.level() < 0 || patch.level() >= clipmap.levels().size()) {
                throw new IllegalArgumentException("L6 mirror patch level is outside its clipmap");
            }
            VoxelClipmapSnapshot.Level level = clipmap.levels().get(patch.level());
            int dimension = level.brickDimension();
            long maximumX = (long) level.originBrickX() + dimension;
            long maximumY = (long) level.originBrickY() + dimension;
            long maximumZ = (long) level.originBrickZ() + dimension;
            if (patch.logicalBrickX() < level.originBrickX()
                    || patch.logicalBrickX() >= maximumX
                    || patch.logicalBrickY() < level.originBrickY()
                    || patch.logicalBrickY() >= maximumY
                    || patch.logicalBrickZ() < level.originBrickZ()
                    || patch.logicalBrickZ() >= maximumZ
                    || patch.destinationBrickX()
                    != Math.floorMod(patch.logicalBrickX(), dimension)
                    || patch.destinationBrickY()
                    != Math.floorMod(patch.logicalBrickY(), dimension)
                    || patch.destinationBrickZ()
                    != Math.floorMod(patch.logicalBrickZ(), dimension)) {
                throw new IllegalArgumentException(
                        "L6 mirror patch does not belong to its captured toroidal window"
                );
            }
        }
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
        private final byte[] chromatic;
        private final short[] shapeProxyIds;

        private Brick(
                final int logicalX,
                final int logicalY,
                final int logicalZ,
                final int contentStamp,
                final int[] ownedOccupancy,
                final byte[] ownedOptical,
                final byte[] ownedChromatic,
                final short[] ownedShapeProxyIds
        ) {
            if (contentStamp == 0 || ownedOccupancy == null
                    || ownedOccupancy.length != VoxelBrickPatch.OCCUPANCY_WORDS
                    || ownedOptical == null || ownedOptical.length == 0
                    || ownedChromatic == null
                    || ownedChromatic.length != VoxelChromaticFilter.packedBytesFor(
                    ownedOptical.length)
                    || ownedShapeProxyIds == null
                    || ownedShapeProxyIds.length != ownedOptical.length) {
                throw new IllegalArgumentException("Invalid L6 cache-mirror brick");
            }
            this.logicalX = logicalX;
            this.logicalY = logicalY;
            this.logicalZ = logicalZ;
            this.contentStamp = contentStamp;
            this.occupancy = ownedOccupancy;
            this.optical = ownedOptical;
            this.chromatic = ownedChromatic;
            this.shapeProxyIds = ownedShapeProxyIds;
        }

        private static Brick fromPatch(final VoxelBrickPatch patch) {
            return new Brick(
                    patch.logicalBrickX(),
                    patch.logicalBrickY(),
                    patch.logicalBrickZ(),
                    patch.contentStamp(),
                    patch.occupancyWords(),
                    patch.opticalPayload(),
                    patch.chromaticPayload(),
                    patch.shapeProxyIds()
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

        public byte[] chromatic() {
            return this.chromatic;
        }

        public short[] shapeProxyIds() {
            return this.shapeProxyIds;
        }

        public short shapeProxyId(final int blockIndex) {
            if (blockIndex < 0 || blockIndex >= this.shapeProxyIds.length) {
                return 0;
            }
            return this.shapeProxyIds[blockIndex];
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
