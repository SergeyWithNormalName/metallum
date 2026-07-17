package com.metallum.client.voxel;

import org.jspecify.annotations.Nullable;

/** Owns an L5 candidate until its geometry output is accepted or destroyed by Sodium. */
public interface VoxelCandidateSlot {
    void metallum$setVoxelSectionCandidate(@Nullable VoxelSectionCandidate candidate);

    @Nullable
    VoxelSectionCandidate metallum$takeVoxelSectionCandidate();

    void metallum$discardVoxelSectionCandidate();
}
