package com.metallum.client.voxel;

/** Monotonic identity for one client world instance used by the L5 producer. */
public record VoxelWorldToken(long generation, String dimensionId) {
    public VoxelWorldToken {
        if (generation <= 0L) {
            throw new IllegalArgumentException("World generation must be positive");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
    }
}
