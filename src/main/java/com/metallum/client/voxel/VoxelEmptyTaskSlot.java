package com.metallum.client.voxel;

import org.jspecify.annotations.Nullable;

/**
 * One-shot stamp for Sodium's authoritative empty-section fast path. Sodium does not create a
 * meshing task for that path, so the stamp lives briefly on the owning RenderSection until the
 * matching {@code BuiltSectionInfo.EMPTY} output is constructed.
 */
public interface VoxelEmptyTaskSlot {
    void metallum$setEmptyVoxelSectionTask(VoxelSectionTask task);

    @Nullable
    VoxelSectionTask metallum$claimEmptyVoxelSectionTask();
}
