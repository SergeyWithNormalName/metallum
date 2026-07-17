package com.metallum.client.voxel;

import org.jspecify.annotations.Nullable;

/** One-shot voxel task stamp attached to Sodium's already-cloned full mesh task. */
public interface VoxelTaskSlot {
    void metallum$setVoxelSectionTask(VoxelSectionTask task);

    @Nullable
    VoxelSectionTask metallum$claimVoxelSectionTask();
}
