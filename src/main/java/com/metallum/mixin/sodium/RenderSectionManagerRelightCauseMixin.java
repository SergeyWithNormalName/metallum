package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.sodium.SodiumRelightCauseTracker;
import com.metallum.client.sodium.SodiumRelightFastMeshingTask;
import com.metallum.client.sodium.SodiumRelightFastPath;
import com.metallum.client.sodium.SodiumRelightRebuildCause;
import com.metallum.client.sodium.SodiumRelightSectionTrackerSlot;
import com.metallum.client.sodium.SodiumRelightTaskAccess;
import com.metallum.client.sodium.SodiumRelightTaskStamp;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.core.SectionPos;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Binds a render-thread cause and geometry epoch to one exact Sodium task. */
@Mixin(value = RenderSectionManager.class, remap = false)
abstract class RenderSectionManagerRelightCauseMixin {
    @Shadow
    @Final
    private SectionStorage renderSections;

    @Inject(method = "scheduleRebuild(IIIZ)V", at = @At("HEAD"))
    private void metallum$recordPendingRelightCause(
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final boolean playerChanged,
            final CallbackInfo ci
    ) {
        RenderSection section = this.renderSections.getConsistent(
                SectionPos.asLong(sectionX, sectionY, sectionZ)
        );
        if (section == null || !section.isBuilt()) {
            return;
        }
        SodiumRelightRebuildCause cause = SodiumRelightCauseTracker.classify(
                sectionX,
                sectionY,
                sectionZ
        );
        ((SodiumRelightSectionTrackerSlot) section).metallum$recordRelightCause(cause);
    }

    @WrapOperation(
            method = "createRebuildTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;IZ)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask;",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILorg/joml/Vector3dc;Lnet/caffeinemc/mods/sodium/client/world/cloned/ChunkRenderContext;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/SortBehavior;ZZ)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask;"
            ),
            require = 1,
            allow = 1
    )
    private ChunkBuilderMeshingTask metallum$createTrackedRelightTask(
            final RenderSection section,
            final int submitTime,
            final Vector3dc absoluteCameraPos,
            final ChunkRenderContext renderContext,
            final SortBehavior sortBehavior,
            final boolean forceSort,
            final boolean blockingTask,
            final Operation<ChunkBuilderMeshingTask> original
    ) {
        SodiumRelightTaskStamp stamp = ((SodiumRelightSectionTrackerSlot) section)
                .metallum$takeRelightTaskStamp(submitTime, blockingTask, forceSort);
        ChunkBuilderMeshingTask task;
        if (SodiumRelightFastPath.shouldUseFastTaskClass(section, stamp)) {
            task = new SodiumRelightFastMeshingTask(
                    section,
                    submitTime,
                    absoluteCameraPos,
                    renderContext,
                    sortBehavior,
                    forceSort,
                    blockingTask
            );
        } else {
            task = original.call(
                    section,
                    submitTime,
                    absoluteCameraPos,
                    renderContext,
                    sortBehavior,
                    forceSort,
                    blockingTask
            );
        }
        ((SodiumRelightTaskAccess) task).metallum$setRelightTaskStamp(stamp);
        return task;
    }

    @Inject(
            method = "createRebuildTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;IZ)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask;",
            at = @At("RETURN")
    )
    private void metallum$discardCauseWhenNoTaskWasCreated(
            final RenderSection section,
            final int submitTime,
            final boolean blockingTask,
            final CallbackInfoReturnable<ChunkBuilderMeshingTask> cir
    ) {
        if (cir.getReturnValue() == null) {
            ((SodiumRelightSectionTrackerSlot) section).metallum$discardPendingRelightCause();
        }
    }
}
