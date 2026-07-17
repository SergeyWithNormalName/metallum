package com.metallum.client.voxel;

import java.util.List;
import java.util.Objects;

/** Exact generation and toroidal-layout context required to consume a leased voxel batch. */
public record VoxelClipmapSnapshot(
        VoxelWorldToken world,
        long clipmapGeneration,
        List<Level> levels
) {
    public record Level(
            int level,
            int subdivision,
            int logicalEdge,
            long originBrickX,
            long originBrickY,
            long originBrickZ,
            int brickDimension
    ) {
        public Level {
            if (level < 0 || (subdivision != 1 && subdivision != 2 && subdivision != 4)
                    || logicalEdge <= 0 || logicalEdge % VoxelBrickPatch.LOGICAL_EDGE != 0
                    || brickDimension != logicalEdge / VoxelBrickPatch.LOGICAL_EDGE) {
                throw new IllegalArgumentException("Voxel clipmap level descriptor is invalid");
            }
        }
    }

    public VoxelClipmapSnapshot {
        Objects.requireNonNull(world, "world");
        if (clipmapGeneration <= 0L) {
            throw new IllegalArgumentException("Voxel clipmap generation must be positive");
        }
        levels = List.copyOf(Objects.requireNonNull(levels, "levels"));
    }
}
