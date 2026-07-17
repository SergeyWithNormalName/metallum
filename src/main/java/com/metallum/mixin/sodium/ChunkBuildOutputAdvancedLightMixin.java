package com.metallum.mixin.sodium;

import com.metallum.client.lighting.AdvancedLightCandidateSlot;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.LightSectionCandidate;
import com.metallum.client.voxel.VoxelCandidateSlot;
import com.metallum.client.voxel.VoxelClipmapController;
import com.metallum.client.voxel.VoxelEmptyTaskSlot;
import com.metallum.client.voxel.VoxelSectionCandidate;
import com.metallum.client.voxel.VoxelSectionTask;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.TranslucentData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Candidate ownership follows the exact lifetime of its Sodium build output. */
@Mixin(value = ChunkBuildOutput.class, remap = false)
abstract class ChunkBuildOutputAdvancedLightMixin implements AdvancedLightCandidateSlot, VoxelCandidateSlot {
    @Unique
    @Nullable
    private volatile LightSectionCandidate metallum$advancedLightCandidate;

    @Unique
    @Nullable
    private volatile VoxelSectionCandidate metallum$voxelSectionCandidate;

    @Inject(
            method = "<init>(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/data/TranslucentData;Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;Ljava/util/Map;Z)V",
            at = @At("RETURN")
    )
    private void metallum$captureAuthoritativeEmptyVoxelSection(
            final RenderSection section,
            final int buildTime,
            @Nullable final TranslucentData translucentData,
            final BuiltSectionInfo info,
            final Map<TerrainRenderPass, BuiltSectionMeshParts> meshes,
            final boolean blockingTask,
            final CallbackInfo ci
    ) {
        if (info != BuiltSectionInfo.EMPTY) {
            return;
        }
        VoxelSectionTask task = ((VoxelEmptyTaskSlot) section).metallum$claimEmptyVoxelSectionTask();
        if (task == null || !AdvancedLightingRuntime.shouldCollect()) {
            return;
        }
        VoxelSectionCandidate candidate = VoxelSectionCandidate.empty(task);
        VoxelClipmapController.global().noteSectionCandidateEncoded(candidate);
        this.metallum$setVoxelSectionCandidate(candidate);
    }

    @Override
    public synchronized void metallum$setAdvancedLightCandidate(
            @Nullable final LightSectionCandidate candidate
    ) {
        LightSectionCandidate previous = this.metallum$advancedLightCandidate;
        if (previous == candidate) {
            return;
        }
        this.metallum$advancedLightCandidate = candidate;
        if (previous != null) {
            AdvancedLightRegistry.global().discardCandidate(previous);
        }
    }

    @Override
    @Nullable
    public synchronized LightSectionCandidate metallum$takeAdvancedLightCandidate() {
        LightSectionCandidate candidate = this.metallum$advancedLightCandidate;
        this.metallum$advancedLightCandidate = null;
        return candidate;
    }

    @Override
    public synchronized void metallum$discardAdvancedLightCandidate() {
        LightSectionCandidate candidate = this.metallum$takeAdvancedLightCandidate();
        if (candidate != null) {
            AdvancedLightRegistry.global().discardCandidate(candidate);
        }
    }

    @Override
    public synchronized void metallum$setVoxelSectionCandidate(
            @Nullable final VoxelSectionCandidate candidate
    ) {
        VoxelSectionCandidate previous = this.metallum$voxelSectionCandidate;
        if (previous == candidate) {
            return;
        }
        this.metallum$voxelSectionCandidate = candidate;
        if (previous != null) {
            VoxelClipmapController.global().discardCandidate(previous);
        }
    }

    @Override
    @Nullable
    public synchronized VoxelSectionCandidate metallum$takeVoxelSectionCandidate() {
        VoxelSectionCandidate candidate = this.metallum$voxelSectionCandidate;
        this.metallum$voxelSectionCandidate = null;
        return candidate;
    }

    @Override
    public synchronized void metallum$discardVoxelSectionCandidate() {
        VoxelSectionCandidate candidate = this.metallum$takeVoxelSectionCandidate();
        if (candidate != null) {
            VoxelClipmapController.global().discardCandidate(candidate);
        }
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void metallum$discardDestroyedAdvancedLightCandidate(final CallbackInfo ci) {
        if (this.metallum$advancedLightCandidate != null) {
            this.metallum$discardAdvancedLightCandidate();
        }
        if (this.metallum$voxelSectionCandidate != null) {
            this.metallum$discardVoxelSectionCandidate();
        }
    }
}
