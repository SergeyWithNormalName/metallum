package com.metallum.mixin.sodium;

import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightResidentSlot;
import com.metallum.client.lighting.AdvancedLightTaskSlot;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.LightSectionTask;
import com.metallum.client.voxel.VoxelClipmapController;
import com.metallum.client.voxel.VoxelEmptyTaskSlot;
import com.metallum.client.voxel.VoxelResidentSlot;
import com.metallum.client.voxel.VoxelSectionTask;
import com.metallum.client.voxel.VoxelTaskSlot;
import org.joml.Vector3dc;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures world/epoch ownership only after Sodium creates its cloned full-mesh task. */
@Mixin(value = RenderSectionManager.class, remap = false)
abstract class RenderSectionManagerAdvancedLightMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    @Final
    private SectionStorage renderSections;

    @Inject(
            method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;ILnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/SortBehavior;)V",
            at = @At("RETURN")
    )
    private void metallum$openAdvancedLightWorld(
            final ClientLevel level,
            final int renderDistance,
            final SortBehavior sortBehavior,
            final CallbackInfo ci
    ) {
        if (AdvancedLightingRuntime.shouldCollect()) {
            AdvancedLightRegistry registry = AdvancedLightRegistry.global();
            registry.observeHook(AdvancedLightRegistry.Hook.WORLD_LIFECYCLE);
            registry.openWorld(level, metallum$dimensionId(level));
            VoxelClipmapController.global().openWorld(level, metallum$dimensionId(level));
        }
    }

    @Inject(
            method = "createRebuildTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;IZ)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask;",
            at = @At("RETURN")
    )
    private void metallum$stampAdvancedLightTask(
            final RenderSection section,
            final int submitTime,
            final boolean blockingTask,
            final CallbackInfoReturnable<ChunkBuilderMeshingTask> cir
    ) {
        ChunkBuilderMeshingTask task = cir.getReturnValue();
        if (!AdvancedLightingRuntime.shouldCollect()) {
            return;
        }
        if (task != null) {
            AdvancedLightRegistry registry = AdvancedLightRegistry.global();
            registry.observeHook(AdvancedLightRegistry.Hook.STATIC_TASK);
            LightSectionTask lightTask = registry.beginSectionTask(
                    this.level,
                    metallum$dimensionId(this.level),
                    section.getPosition().asLong()
            );
            ((AdvancedLightResidentSlot) section).metallum$bindAdvancedLightSection(
                    this.level,
                    section.getPosition().asLong()
            );
            ((AdvancedLightTaskSlot) task).metallum$setAdvancedLightTask(lightTask);
        }
        VoxelSectionTask voxelTask = VoxelClipmapController.global().beginSectionTask(
                this.level,
                metallum$dimensionId(this.level),
                section.getPosition().asLong()
        );
        ((VoxelResidentSlot) section).metallum$bindVoxelSection(
                this.level,
                section.getPosition().asLong()
        );
        if (task != null) {
            ((VoxelTaskSlot) task).metallum$setVoxelSectionTask(voxelTask);
        } else {
            // LevelSlice.prepare() returns null for an authoritative empty central section. Sodium
            // immediately constructs BuiltSectionInfo.EMPTY without a worker task; retain this
            // exact revision/owner stamp until that output is created.
            ((VoxelEmptyTaskSlot) section).metallum$setEmptyVoxelSectionTask(voxelTask);
        }
    }

    @Inject(method = "onSectionRemoved(III)V", at = @At("HEAD"))
    private void metallum$removeAdvancedLightSection(
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final CallbackInfo ci
    ) {
        if (!AdvancedLightingRuntime.shouldCollect()) {
            return;
        }
        long sectionKey = SectionPos.asLong(sectionX, sectionY, sectionZ);
        RenderSection section = this.renderSections.getConsistent(sectionKey);
        long ownerToken = section instanceof AdvancedLightResidentSlot resident
                ? resident.metallum$getAdvancedLightOwnerToken()
                : 0L;
        AdvancedLightRegistry.global().removeSectionIfOwner(
                this.level,
                sectionKey,
                ownerToken
        );
        long voxelOwner = section instanceof VoxelResidentSlot resident
                ? resident.metallum$getVoxelOwnerToken()
                : 0L;
        VoxelClipmapController.global().removeSectionIfOwner(this.level, sectionKey, voxelOwner);
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void metallum$closeAdvancedLightWorld(final CallbackInfo ci) {
        AdvancedLightRegistry.global().closeWorld(this.level);
        VoxelClipmapController.global().closeWorld(this.level);
    }

    @Inject(method = "prepareFrame(Lorg/joml/Vector3dc;)V", at = @At("HEAD"))
    private void metallum$scrollVoxelClipmap(final Vector3dc camera, final CallbackInfo ci) {
        if (AdvancedLightingRuntime.shouldCollect()) {
            VoxelClipmapController.global().updateCamera(
                    this.level,
                    camera.x(),
                    camera.y(),
                    camera.z()
            );
        }
    }

    private static String metallum$dimensionId(final ClientLevel level) {
        return level.dimension().identifier().toString();
    }
}
