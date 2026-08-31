package com.metallum.mixin.sodium;

import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightResidentSlot;
import com.metallum.client.lighting.AdvancedLightTaskSlot;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.LightSectionTask;
import com.metallum.client.lighting.reflection.FrozenReflectionEmptyTaskSlot;
import com.metallum.client.lighting.reflection.FrozenReflectionFieldController;
import com.metallum.client.lighting.reflection.FrozenReflectionSectionTask;
import com.metallum.client.lighting.reflection.FrozenReflectionTaskSlot;
import com.metallum.client.lighting.reflection.FrozenReflectionVerticalBounds;
import com.metallum.client.lighting.reflection.VertexReflectionExperiment;
import com.metallum.client.voxel.VoxelClipmapController;
import com.metallum.client.voxel.VoxelEmptyTaskSlot;
import com.metallum.client.voxel.VoxelResidentSlot;
import com.metallum.client.voxel.VoxelSectionTask;
import com.metallum.client.voxel.VoxelTaskSlot;
import org.joml.Vector3dc;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobTyped;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.NoData;
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

import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Captures world/epoch ownership only after Sodium creates its cloned full-mesh task. */
@Mixin(value = RenderSectionManager.class, remap = false)
abstract class RenderSectionManagerAdvancedLightMixin {
    private static final int FROZEN_REFLECTION_PRELOAD_TASKS_PER_FRAME = 8;

    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    @Final
    private SectionStorage renderSections;

    @Shadow
    @Final
    private ChunkBuilder builder;

    @Shadow
    @Final
    private ConcurrentLinkedDeque<ChunkJobResult<? extends BuilderTaskOutput>> buildResults;

    @Shadow
    @Final
    private SortBehavior sortBehavior;

    @Shadow
    private int frame;

    @Shadow
    public abstract void scheduleRebuild(int sectionX, int sectionY, int sectionZ, boolean playerChanged);

    @Shadow
    public abstract void onSectionAdded(int sectionX, int sectionY, int sectionZ);

    @Shadow
    public abstract ChunkBuilderMeshingTask createRebuildTask(
            RenderSection section,
            int submitTime,
            boolean blockingTask
    );

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
            FrozenReflectionFieldController.global().openWorld(level);
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
        FrozenReflectionSectionTask reflectionTask = FrozenReflectionFieldController.global().beginSectionTask(
                this.level, section.getPosition().asLong()
        );
        if (reflectionTask != null) {
            if (task != null) {
                ((FrozenReflectionTaskSlot) task).metallum$setFrozenReflectionTask(reflectionTask);
            } else {
                ((FrozenReflectionEmptyTaskSlot) section).metallum$setEmptyFrozenReflectionTask(reflectionTask);
            }
        }
    }

    @Inject(method = "onSectionRemoved(III)V", at = @At("HEAD"), cancellable = true)
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
        if (VertexReflectionExperiment.isRuntimeEnabled()
                && FrozenReflectionFieldController.global().retainsSectionDuringCollection(this.level, sectionKey)) {
            ci.cancel();
            return;
        }
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
        FrozenReflectionFieldController.global().removeSection(this.level, sectionKey);
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void metallum$closeAdvancedLightWorld(final CallbackInfo ci) {
        AdvancedLightRegistry.global().closeWorld(this.level);
        VoxelClipmapController.global().closeWorld(this.level);
        FrozenReflectionFieldController.global().closeWorld(this.level);
    }

    @Inject(method = "prepareFrame(Lorg/joml/Vector3dc;)V", at = @At("TAIL"))
    private void metallum$scrollVoxelClipmap(final Vector3dc camera, final CallbackInfo ci) {
        if (AdvancedLightingRuntime.shouldCollect()) {
            VoxelClipmapController.global().updateCamera(
                    this.level,
                    camera.x(),
                    camera.y(),
                    camera.z()
            );
            // Enrollment is intentionally deferred to the bounded preload below. Starting a
            // guarded replacement must never materialize or invalidate all 8^3 Sodium sections
            // synchronously on the render thread.
            FrozenReflectionFieldController.global().activateAtCamera(
                    this.level, camera.x(), camera.y(), camera.z()
            );
            metallum$submitFrozenReflectionPreload();
        }
    }

    /**
     * Sodium's normal task selection is visibility-driven, while this finite experiment requires
     * one immutable 8x8x8 source cube. Submit only eight already-created, not-yet-built sections
     * per frame through Sodium's own worker/result queue. The result is still accepted by
     * RenderRegionManager before the reflection controller sees it.
     */
    private void metallum$submitFrozenReflectionPreload() {
        FrozenReflectionFieldController controller = FrozenReflectionFieldController.global();
        FrozenReflectionFieldController.Snapshot snapshot = controller.snapshot();
        if (snapshot.state() != FrozenReflectionFieldController.State.COLLECTING) {
            return;
        }
        int processed = 0;
        for (int z = 0; z < FrozenReflectionFieldController.SECTIONS_PER_EDGE; z++) {
            for (int y = 0; y < FrozenReflectionFieldController.SECTIONS_PER_EDGE; y++) {
                for (int x = 0; x < FrozenReflectionFieldController.SECTIONS_PER_EDGE; x++) {
                    if (processed >= FROZEN_REFLECTION_PRELOAD_TASKS_PER_FRAME) {
                        return;
                    }
                    int sectionX = (snapshot.originX() >> 4) + x;
                    int sectionY = (snapshot.originY() >> 4) + y;
                    int sectionZ = (snapshot.originZ() >> 4) + z;
                    long sectionKey = SectionPos.asLong(
                            sectionX,
                            sectionY,
                            sectionZ
                    );
                    if (!controller.needsSectionTask(this.level, sectionKey)) {
                        continue;
                    }
                    if (metallum$isAuthoritativeEmptySection(sectionX, sectionY, sectionZ)) {
                        controller.publishAuthoritativeEmpty(this.level, sectionKey);
                        processed++;
                        continue;
                    }
                    if (!this.renderSections.hasSectionConsistent(sectionKey)) {
                        // Queue only this bounded slice through Sodium's normal lifecycle. Its
                        // pending update is consumed by this preload on a later frame once storage
                        // publication makes the new RenderSection visible.
                        this.onSectionAdded(sectionX, sectionY, sectionZ);
                        processed++;
                        continue;
                    }
                    RenderSection section = this.renderSections.getConsistent(sectionKey);
                    if (section == null || section.isDisposed()) {
                        continue;
                    }
                    // Preserve the old collection's freshness contract, but invalidate and rebuild
                    // no more than the bounded number of sections in this frame.
                    this.scheduleRebuild(sectionX, sectionY, sectionZ, true);
                    metallum$submitFrozenReflectionTask(section);
                    processed++;
                }
            }
        }
    }

    private void metallum$submitFrozenReflectionTask(final RenderSection section) {
        ChunkBuilderMeshingTask task = this.createRebuildTask(section, this.frame, true);
        if (task == null) {
            this.buildResults.add(ChunkJobResult.successfully(new ChunkBuildOutput(
                    section,
                    this.frame,
                    this.sortBehavior == SortBehavior.OFF ? null : NoData.forEmptySection(section.getPosition()),
                    BuiltSectionInfo.EMPTY,
                    Collections.emptyMap(),
                    false
            )));
            section.clearPendingUpdate();
            return;
        }
        ChunkJobTyped<ChunkBuilderMeshingTask, ChunkBuildOutput> job =
                ((ChunkBuilderFrozenReflectionAccess) this.builder).metallum$scheduleFrozenReflectionTask(
                        task,
                        true,
                        this.buildResults::add
                );
        section.addRunningJob(job);
        section.clearPendingUpdate();
    }

    /** Mirrors Sodium's onSectionAdded air fast path; no geometry task exists for these sections. */
    private boolean metallum$isAuthoritativeEmptySection(final int sectionX, final int sectionY, final int sectionZ) {
        // A camera near the dimension floor/ceiling can center the finite reflection cube partly
        // outside build height. Those cells are real empty space, but Sodium's LevelChunk section
        // array has no slots for them; never forward such coordinates to its array-based fast path.
        if (FrozenReflectionVerticalBounds.isOutsideBuildHeight(this.level, sectionY)) {
            return true;
        }
        return this.level.getChunk(sectionX, sectionZ)
                .getSections()[this.level.getSectionIndexFromSectionY(sectionY)]
                .hasOnlyAir();
    }

    private static String metallum$dimensionId(final ClientLevel level) {
        return level.dimension().identifier().toString();
    }
}
