package com.metallum.mixin.sodium;

import com.metallum.client.lighting.AdvancedLightResidentSlot;
import com.metallum.client.voxel.VoxelClipmapController;
import com.metallum.client.voxel.VoxelEmptyTaskSlot;
import com.metallum.client.voxel.VoxelResidentSlot;
import com.metallum.client.voxel.VoxelSectionTask;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Associates lifecycle deletion with the exact accepted registry owner. */
@Mixin(value = RenderSection.class, remap = false)
abstract class RenderSectionAdvancedLightMixin implements AdvancedLightResidentSlot, VoxelResidentSlot,
        VoxelEmptyTaskSlot {
    @Unique
    private Object metallum$advancedLightWorldIdentity;

    @Unique
    private long metallum$advancedLightSectionKey;

    @Unique
    private long metallum$advancedLightOwnerToken;

    @Unique
    private Object metallum$voxelWorldIdentity;

    @Unique
    private long metallum$voxelSectionKey;

    @Unique
    private long metallum$voxelOwnerToken;

    @Unique
    private VoxelSectionTask metallum$emptyVoxelSectionTask;

    @Override
    public void metallum$bindAdvancedLightSection(
            final Object worldIdentity,
            final long sectionKey
    ) {
        if (worldIdentity == null) {
            throw new NullPointerException("worldIdentity");
        }
        if (this.metallum$advancedLightWorldIdentity != null
                && (this.metallum$advancedLightWorldIdentity != worldIdentity
                || this.metallum$advancedLightSectionKey != sectionKey)) {
            throw new IllegalStateException("RenderSection light lifecycle was rebound");
        }
        this.metallum$advancedLightWorldIdentity = worldIdentity;
        this.metallum$advancedLightSectionKey = sectionKey;
    }

    @Override
    public Object metallum$getAdvancedLightWorldIdentity() {
        return this.metallum$advancedLightWorldIdentity;
    }

    @Override
    public long metallum$getAdvancedLightSectionKey() {
        return this.metallum$advancedLightSectionKey;
    }

    @Override
    public long metallum$getAdvancedLightOwnerToken() {
        return this.metallum$advancedLightOwnerToken;
    }

    @Override
    public void metallum$setAdvancedLightOwnerToken(final long ownerToken) {
        if (ownerToken < 0L) {
            throw new IllegalArgumentException("Advanced light owner token must be non-negative");
        }
        this.metallum$advancedLightOwnerToken = ownerToken;
    }

    @Override
    public void metallum$bindVoxelSection(final Object worldIdentity, final long sectionKey) {
        if (worldIdentity == null) {
            throw new NullPointerException("worldIdentity");
        }
        if (this.metallum$voxelWorldIdentity != null
                && (this.metallum$voxelWorldIdentity != worldIdentity
                || this.metallum$voxelSectionKey != sectionKey)) {
            throw new IllegalStateException("RenderSection voxel lifecycle was rebound");
        }
        this.metallum$voxelWorldIdentity = worldIdentity;
        this.metallum$voxelSectionKey = sectionKey;
    }

    @Override
    public Object metallum$getVoxelWorldIdentity() {
        return this.metallum$voxelWorldIdentity;
    }

    @Override
    public long metallum$getVoxelSectionKey() {
        return this.metallum$voxelSectionKey;
    }

    @Override
    public long metallum$getVoxelOwnerToken() {
        return this.metallum$voxelOwnerToken;
    }

    @Override
    public void metallum$setVoxelOwnerToken(final long ownerToken) {
        if (ownerToken < 0L) {
            throw new IllegalArgumentException("Voxel owner token must be non-negative");
        }
        this.metallum$voxelOwnerToken = ownerToken;
    }

    @Override
    public synchronized void metallum$setEmptyVoxelSectionTask(final VoxelSectionTask task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        // A newer Sodium rebuild attempt supersedes an unclaimed empty result from the same
        // RenderSection. The controller's owner/revision checks reject the older output.
        this.metallum$emptyVoxelSectionTask = task;
    }

    @Override
    public synchronized VoxelSectionTask metallum$claimEmptyVoxelSectionTask() {
        VoxelSectionTask task = this.metallum$emptyVoxelSectionTask;
        this.metallum$emptyVoxelSectionTask = null;
        return task;
    }

    @Inject(method = "delete()V", at = @At("HEAD"))
    private void metallum$deleteOwnedAdvancedLights(final CallbackInfo ci) {
        this.metallum$emptyVoxelSectionTask = null;
        if (this.metallum$advancedLightWorldIdentity != null) {
            com.metallum.client.lighting.AdvancedLightRegistry.global().removeSectionIfOwner(
                    this.metallum$advancedLightWorldIdentity,
                    this.metallum$advancedLightSectionKey,
                    this.metallum$advancedLightOwnerToken
            );
        }
        if (this.metallum$voxelWorldIdentity != null) {
            VoxelClipmapController.global().removeSectionIfOwner(
                    this.metallum$voxelWorldIdentity,
                    this.metallum$voxelSectionKey,
                    this.metallum$voxelOwnerToken
            );
        }
    }
}
