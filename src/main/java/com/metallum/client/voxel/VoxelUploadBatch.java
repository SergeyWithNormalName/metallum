package com.metallum.client.voxel;

import java.util.List;
import java.util.Objects;

/** Immutable leased producer batch. The bridge must report complete or retry exactly once. */
public record VoxelUploadBatch(
        long batchId,
        VoxelWorldToken world,
        long clipmapGeneration,
        long frameId,
        List<VoxelBrickPatch> patches,
        int queueRemaining,
        long oldestAgeTicks,
        int scrollSlabs,
        int unloadClears,
        long coalescedDelta,
        long rejectedDelta
) {
    public VoxelUploadBatch {
        if (batchId <= 0L || clipmapGeneration <= 0L || frameId < 0L
                || queueRemaining < 0 || oldestAgeTicks < 0L || scrollSlabs < 0
                || unloadClears < 0 || coalescedDelta < 0L || rejectedDelta < 0L) {
            throw new IllegalArgumentException("Voxel upload batch metadata is invalid");
        }
        Objects.requireNonNull(world, "world");
        patches = List.copyOf(Objects.requireNonNull(patches, "patches"));
        if (patches.isEmpty()) {
            throw new IllegalArgumentException("A leased voxel upload batch must contain a patch");
        }
        int previousLevel = -1;
        for (VoxelBrickPatch patch : patches) {
            if (patch.worldGeneration() != world.generation()
                    || patch.clipmapGeneration() != clipmapGeneration) {
                throw new IllegalArgumentException("Voxel batch patch generations do not match its context");
            }
            if (patch.level() < previousLevel) {
                throw new IllegalArgumentException("Voxel batch patches must already be grouped by level");
            }
            previousLevel = patch.level();
        }
    }
}
