package com.metallum.mixin.gi;

import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.gi.semantic.GiSemanticEmptyTaskSlot;
import com.metallum.client.gi.semantic.GiSemanticResidentSlot;
import com.metallum.client.gi.semantic.GiSemanticSectionTask;
import com.metallum.client.gi.semantic.GiSemanticTaskSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.joml.Vector3dc;

@Mixin(value = RenderSectionManager.class, remap = false)
abstract class GiSemanticRenderSectionManagerMixin {
    @Shadow @Final private ClientLevel level;
    @Shadow @Final private SectionStorage renderSections;

    @Inject(method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;ILnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/SortBehavior;)V", at = @At("RETURN"))
    private void metallum$openGiWorld(final ClientLevel level, final int distance,
                                      final SortBehavior sortBehavior, final CallbackInfo ci) {
        try {
            Entity camera = metallum$cameraFor(level);
            if (camera == null) {
                GiSemanticController.global().openWorld(level, metallum$dimension(level));
            } else {
                GiSemanticController.global().openWorld(
                        level, metallum$dimension(level),
                        (int) Math.floor(camera.getX()),
                        (int) Math.floor(camera.getY()),
                        (int) Math.floor(camera.getZ())
                );
            }
            com.metallum.Metallum.LOGGER.info(
                    "[GI_G2] accepted-output semantic capture active for {}",
                    metallum$dimension(level)
            );
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "createRebuildTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;IZ)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask;", at = @At("RETURN"))
    private void metallum$stampGiTask(final RenderSection section, final int submitTime,
                                     final boolean blocking, final CallbackInfoReturnable<ChunkBuilderMeshingTask> cir) {
        try {
            GiSemanticSectionTask stamp = GiSemanticController.global().beginSectionTask(
                    this.level, metallum$dimension(this.level), section.getPosition().asLong()
            );
            if (stamp == null) return;
            ((GiSemanticResidentSlot) section).metallum$bindGiSemanticSection(
                    this.level, section.getPosition().asLong()
            );
            ChunkBuilderMeshingTask task = cir.getReturnValue();
            if (task == null) {
                ((GiSemanticEmptyTaskSlot) section).metallum$setEmptyGiSemanticTask(stamp);
            } else {
                ((GiSemanticTaskSlot) task).metallum$setGiSemanticTask(stamp);
            }
        } catch (RuntimeException ignored) {
            // Sodium's task remains valid; this revision stays unknown to G2.
        }
    }

    @Inject(method = "onSectionRemoved(III)V", at = @At("HEAD"))
    private void metallum$removeGiSection(final int x, final int y, final int z, final CallbackInfo ci) {
        long key = SectionPos.asLong(x, y, z);
        RenderSection section = this.renderSections.getConsistent(key);
        long owner = section instanceof GiSemanticResidentSlot slot
                ? slot.metallum$getGiSemanticOwnerToken() : 0L;
        try {
            GiSemanticController.global().removeSectionIfOwner(this.level, key, owner);
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void metallum$closeGiWorld(final CallbackInfo ci) {
        try {
            GiSemanticController controller = GiSemanticController.global();
            GiSemanticController.Telemetry telemetry = controller.telemetry();
            com.metallum.Metallum.LOGGER.info(
                    "[GI_G2] accepted={} stale={} outside={} capacityRejected={} discarded={} "
                            + "residentTags={} activeCandidates={} peakCandidates={} peakCandidateBytes={}",
                    telemetry.accepted(), telemetry.stale(), telemetry.outside(),
                    telemetry.capacityRejected(), telemetry.discarded(), telemetry.residentSectionTags(),
                    telemetry.candidateBudget().activeCandidates(),
                    telemetry.candidateBudget().peakCandidates(),
                    telemetry.candidateBudget().peakBytes()
            );
            controller.closeWorld(this.level);
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "prepareFrame(Lorg/joml/Vector3dc;)V", at = @At("TAIL"))
    private void metallum$scrollGiField(final Vector3dc camera, final CallbackInfo ci) {
        try {
            GiSemanticController.global().updateCamera(
                    this.level,
                    (int) Math.floor(camera.x()),
                    (int) Math.floor(camera.y()),
                    (int) Math.floor(camera.z())
            );
        } catch (RuntimeException ignored) {
        }
    }

    private static String metallum$dimension(final ClientLevel level) {
        return level.dimension().identifier().toString();
    }

    @Nullable
    private static Entity metallum$cameraFor(final ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        if (!metallum$isCurrentFiniteCamera(camera, level)) {
            camera = minecraft.player;
        }
        return metallum$isCurrentFiniteCamera(camera, level) ? camera : null;
    }

    private static boolean metallum$isCurrentFiniteCamera(
            @Nullable final Entity camera,
            final ClientLevel level
    ) {
        return camera != null
                && camera.level() == level
                && Double.isFinite(camera.getX())
                && Double.isFinite(camera.getY())
                && Double.isFinite(camera.getZ());
    }
}
