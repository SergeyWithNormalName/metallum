package com.metallum.mixin.benchmark.sodium;

import com.metallum.client.benchmark.TorchEpochTelemetry;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentLinkedDeque;

/** Loaded only for a benchmark using Sodium; never transforms a normal client. */
@Mixin(RenderSectionManager.class)
abstract class RenderSectionManagerTorchTelemetryMixin {
    @Shadow
    @Final
    private ChunkBuilder builder;

    @Shadow
    @Final
    private ConcurrentLinkedDeque<ChunkJobResult<? extends BuilderTaskOutput>> buildResults;

    @Inject(
            method = "scheduleRebuild(IIIZ)V",
            at = @At("HEAD"),
            remap = false
    )
    private void metallum$recordRebuildRequest(
            final int x,
            final int y,
            final int z,
            final boolean playerChanged,
            final CallbackInfo ci
    ) {
        if (!TorchEpochTelemetry.isActive()) {
            return;
        }
        TorchEpochTelemetry.recordRebuildRequest(SectionPos.asLong(x, y, z), x, y, z);
    }

    @Inject(
            method = "createRebuildTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;IZ)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask;",
            at = @At("RETURN"),
            remap = false
    )
    private void metallum$recordRebuildTask(
            final RenderSection section,
            final int frame,
            final boolean blocking,
            final CallbackInfoReturnable<ChunkBuilderMeshingTask> cir
    ) {
        if (!TorchEpochTelemetry.isActive() || cir.getReturnValue() == null) {
            return;
        }
        long pendingSince = section.getPendingUpdateSince();
        long now = System.nanoTime();
        long pendingAge = pendingSince > 0L && now >= pendingSince ? now - pendingSince : -1L;
        TorchEpochTelemetry.recordRebuildTask(
                SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()),
                pendingAge
        );
    }

    @Inject(
            method = "processChunkBuilds",
            at = @At("HEAD"),
            remap = false
    )
    private void metallum$recordQueueBeforeBuildProcessing(
            final Viewport viewport,
            final UniformBufferManager uniforms,
            final CallbackInfo ci
    ) {
        this.metallum$recordBuilderWorkState();
    }

    @Inject(
            method = "processChunkBuilds",
            at = @At("RETURN"),
            remap = false
    )
    private void metallum$recordQueueAfterBuildProcessing(
            final Viewport viewport,
            final UniformBufferManager uniforms,
            final CallbackInfo ci
    ) {
        this.metallum$recordBuilderWorkState();
    }

    @Unique
    private void metallum$recordBuilderWorkState() {
        if (!TorchEpochTelemetry.isActive()) {
            return;
        }
        TorchEpochTelemetry.recordBuilderWorkState(
                this.builder.getScheduledJobCount(),
                this.builder.getBusyThreadCount(),
                this.buildResults.size()
        );
    }
}
