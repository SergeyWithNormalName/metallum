package com.metallum.client.voxel;

/** Immutable stamp for one Sodium full-geometry rebuild attempt, including its empty fast path. */
public record VoxelSectionTask(
        VoxelWorldToken world,
        long sectionKey,
        long revision,
        long ownerToken
) {
    public VoxelSectionTask {
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (revision <= 0L || ownerToken <= 0L) {
            throw new IllegalArgumentException("Voxel task revision and owner must be positive");
        }
    }
}
