package com.metallum.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.renderer.temporal.EntityAccessor;
import com.metallum.client.renderer.temporal.EntityRenderStateAccessor;
import com.metallum.client.renderer.temporal.EntityVelocityDrawRecorder;
import com.metallum.client.renderer.temporal.FrameState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void metallum$onExtractRenderState(
            Entity entity, EntityRenderState state, float partialTicks,
            CallbackInfo ci
    ) {
        MetalDevice device = MetalDevice.getInstance();
        if (device != null && device.temporalDiagnosticsActive()) {
            EntityRenderStateAccessor accessor = (EntityRenderStateAccessor) state;
            accessor.metallum$setUuid(entity.getUUID());
            accessor.metallum$setEntityId(entity.getId());
            accessor.metallum$setSubmitCount(0); // reset submit count for new frame

            EntityAccessor entityAccessor = (EntityAccessor) (Object) entity;
            boolean teleported = entityAccessor.metallum$isExplicitTeleport();
            entityAccessor.metallum$setExplicitTeleport(false);

            device.entityTransformTracker().incrementExtractCalls();

            if (teleported) {
                device.entityTransformTracker().incrementExplicitTeleports();
            } else {
                double dx = entity.getX() - entity.xOld;
                double dy = entity.getY() - entity.yOld;
                double dz = entity.getZ() - entity.zOld;
                boolean fallback = (dx * dx + dy * dy + dz * dz) > 100.0;
                if (fallback) {
                    teleported = true;
                    device.entityTransformTracker().incrementDistanceFallbacks();
                }
            }
            accessor.metallum$setTeleported(teleported);

            FrameState frameState = device.frameStateTracker().previous();
            accessor.metallum$setLastFrameId(frameState != null ? frameState.frameId() : -1L);
        }
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void metallum$onEntitySubmitHead(
            EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState,
            CallbackInfo ci
    ) {
        MetalDevice device = MetalDevice.getInstance();
        if (device != null && device.temporalDiagnosticsActive()) {
            device.entityTransformTracker().incrementSubmitCalls();
            EntityRenderStateAccessor accessor = (EntityRenderStateAccessor) state;
            if (accessor.metallum$getUuid() == null) {
                device.entityTransformTracker().incrementMissingIdentity();
                return;
            }

            FrameState frameState = device.frameStateTracker().previous();
            long currentFrameId = frameState != null ? frameState.frameId() : 0L;

                if (isSupportedRigidEntity(state)) {
                    // Extract PoseStack matrix (Model-View matrix in camera/view space)
                    Matrix4f currentRootMV = new Matrix4f(poseStack.last().pose());

                    accessor.metallum$setSubmitCount(accessor.metallum$getSubmitCount() + 1);

                    // Record transformation directly in tracker
                    device.entityTransformTracker().record(
                            accessor.metallum$getUuid(),
                            accessor.metallum$getEntityId(),
                            currentRootMV,
                            accessor.metallum$isTeleported(),
                            accessor.metallum$getLastFrameId(),
                            accessor.metallum$getSubmitCount()
                    );

                    // Begin explicit entity submit scope
                    int useAlphaTest = (state.entityType == EntityTypes.ITEM || state.entityType == EntityTypes.ITEM_FRAME || state.entityType == EntityTypes.GLOW_ITEM_FRAME) ? 1 : 0;
                    int indexCount = (state.entityType == EntityTypes.ITEM_FRAME || state.entityType == EntityTypes.GLOW_ITEM_FRAME) ? 12 : 36;
                    EntityVelocityDrawRecorder.getInstance().beginEntitySubmit(
                            accessor.metallum$getUuid(),
                            accessor.metallum$getEntityId(),
                            state.entityType != null ? state.entityType.toShortString() : "unknown",
                            device.entityTransformTracker(),
                            currentRootMV,
                            currentFrameId,
                            useAlphaTest,
                            indexCount
                    );
                }
        }
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void metallum$onEntitySubmitReturn(
            EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState,
            CallbackInfo ci
    ) {
        MetalDevice device = MetalDevice.getInstance();
        if (device != null && device.temporalDiagnosticsActive()) {
            EntityVelocityDrawRecorder.getInstance().endEntitySubmit();
        }
    }

    private static boolean isSupportedRigidEntity(EntityRenderState state) {
        if (state == null || state.entityType == null) {
            return false;
        }
        String s = state.entityType.toShortString();
        return s.contains("zombie") || s.contains("player") || s.contains("item") || s.contains("item_frame") || s.contains("armor_stand");
    }
}
