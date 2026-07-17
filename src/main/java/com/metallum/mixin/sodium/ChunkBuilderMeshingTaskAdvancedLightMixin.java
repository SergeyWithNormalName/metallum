package com.metallum.mixin.sodium;

import com.metallum.client.lighting.AdvancedLightCandidateSlot;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightTaskSlot;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.LightSectionCandidate;
import com.metallum.client.lighting.LightSectionTask;
import com.metallum.client.lighting.SodiumStaticLightExtractor;
import com.metallum.client.sodium.SodiumRelightFastOutputSlot;
import com.metallum.client.voxel.SodiumVoxelSectionExtractor;
import com.metallum.client.voxel.VoxelCandidateSlot;
import com.metallum.client.voxel.VoxelClipmapController;
import com.metallum.client.voxel.VoxelSectionCandidate;
import com.metallum.client.voxel.VoxelSectionTask;
import com.metallum.client.voxel.VoxelTaskSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs one 16^3 emitter scan after the real Sodium full-mesh implementation returns. */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
abstract class ChunkBuilderMeshingTaskAdvancedLightMixin implements AdvancedLightTaskSlot, VoxelTaskSlot {
    @Shadow
    @Final
    private ChunkRenderContext renderContext;

    @Unique
    @Nullable
    private LightSectionTask metallum$advancedLightTask;

    @Unique
    @Nullable
    private VoxelSectionTask metallum$voxelSectionTask;

    @Override
    public synchronized void metallum$setAdvancedLightTask(final LightSectionTask task) {
        if (task == null || this.metallum$advancedLightTask != null) {
            throw new IllegalStateException("Advanced light task stamp was assigned more than once");
        }
        this.metallum$advancedLightTask = task;
    }

    @Override
    @Nullable
    public synchronized LightSectionTask metallum$claimAdvancedLightTask() {
        LightSectionTask task = this.metallum$advancedLightTask;
        this.metallum$advancedLightTask = null;
        return task;
    }

    @Override
    public synchronized void metallum$setVoxelSectionTask(final VoxelSectionTask task) {
        if (task == null || this.metallum$voxelSectionTask != null) {
            throw new IllegalStateException("Voxel section task stamp was assigned more than once");
        }
        this.metallum$voxelSectionTask = task;
    }

    @Override
    @Nullable
    public synchronized VoxelSectionTask metallum$claimVoxelSectionTask() {
        VoxelSectionTask task = this.metallum$voxelSectionTask;
        this.metallum$voxelSectionTask = null;
        return task;
    }

    @Inject(
            method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            at = @At("RETURN")
    )
    private void metallum$extractStaticAdvancedLights(
            final ChunkBuildContext context,
            final CancellationToken cancellationToken,
        final CallbackInfoReturnable<ChunkBuildOutput> cir
    ) {
        ChunkBuildOutput output = cir.getReturnValue();
        if (output == null || !AdvancedLightingRuntime.shouldCollect()) {
            return;
        }
        LightSectionTask task = this.metallum$claimAdvancedLightTask();
        if (task == null) {
            return;
        }
        if (output instanceof SodiumRelightFastOutputSlot fast && fast.metallum$isFastRelightOutput()) {
            return;
        }
        AdvancedLightRegistry registry = AdvancedLightRegistry.global();
        try {
            LightSectionCandidate candidate = SodiumStaticLightExtractor.scan(
                    task,
                    this.renderContext
            );
            registry.noteStaticScan(candidate);
            ((AdvancedLightCandidateSlot) output).metallum$setAdvancedLightCandidate(candidate);
        } catch (Throwable failure) {
            registry.failClosed("static light extraction failed", failure);
            // Sodium's successful geometry output remains valid; lighting falls back atomically.
        }
        VoxelSectionTask voxelTask = this.metallum$claimVoxelSectionTask();
        if (voxelTask == null) {
            return;
        }
        try {
            VoxelSectionCandidate candidate = SodiumVoxelSectionExtractor.encode(
                    voxelTask,
                    context.cache.getWorldSlice()
            );
            VoxelClipmapController.global().noteSectionCandidateEncoded(candidate);
            ((VoxelCandidateSlot) output).metallum$setVoxelSectionCandidate(candidate);
        } catch (RuntimeException ignored) {
            // L5 is a producer only. A bad voxel candidate must not invalidate accepted L3/L4.
        }
    }
}
