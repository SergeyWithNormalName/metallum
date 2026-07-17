package com.metallum.client.voxel;

/** Accepted L5 owner token stored on the exact Sodium RenderSection that owns the snapshot. */
public interface VoxelResidentSlot {
    void metallum$bindVoxelSection(Object worldIdentity, long sectionKey);

    Object metallum$getVoxelWorldIdentity();

    long metallum$getVoxelSectionKey();

    long metallum$getVoxelOwnerToken();

    void metallum$setVoxelOwnerToken(long ownerToken);
}
