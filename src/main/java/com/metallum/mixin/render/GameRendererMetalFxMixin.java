package com.metallum.mixin.render;

import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.renderer.temporal.FrameCapture;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;
import com.metallum.client.renderer.temporal.TemporalResetEvents;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps world render targets low-resolution while window and GUI state stay native-resolution. */
@Mixin(GameRenderer.class)
abstract class GameRendererMetalFxMixin {
    @Shadow @Final
    private RenderTarget mainRenderTarget;
    @Shadow @Final
    private Minecraft minecraft;
    @Shadow @Final
    private GameRenderState gameRenderState;

    private final Matrix4f metallum$previousBaseProjection = new Matrix4f();
    private final Matrix4f metallum$cameraInverse = new Matrix4f();
    private boolean metallum$hasPreviousBaseProjection;
    private Entity metallum$previousCameraEntity;
    private Object metallum$dimensionKey;
    private long metallum$dimensionIdentity;

    @Inject(method = "render", at = @At("HEAD"))
    private void metallum$applyDeferredScale(final CallbackInfo ci) {
        int displayWidth = MetalFxSpatialScaling.configuredDisplayWidth(this.mainRenderTarget.width);
        int displayHeight = MetalFxSpatialScaling.configuredDisplayHeight(this.mainRenderTarget.height);
        if (MetalFxSpatialScaling.consumePendingResize()) {
            ((GameRenderer) (Object) this).resize(displayWidth, displayHeight);
        }
        if (!MetalFxSpatialScaling.isActive()) {
            MetalDevice device = MetalDevice.getInstance();
            if (device != null) {
                device.publishRendererGenerationState(displayWidth, displayHeight);
            }
            return;
        }
        MetalFxSpatialScaling.Dimensions dimensions = MetalFxSpatialScaling.effectiveDimensions(
                displayWidth,
                displayHeight
        );
        if (this.mainRenderTarget.width != dimensions.renderWidth()
                || this.mainRenderTarget.height != dimensions.renderHeight()) {
            ((GameRenderer) (Object) this).resize(displayWidth, displayHeight);
        }
        MetalDevice device = MetalDevice.getInstance();
        if (device != null) {
            device.publishRendererGenerationState(displayWidth, displayHeight);
        }
    }

    @Inject(method = "resize", at = @At("HEAD"))
    private void metallum$recordDisplaySize(final int width, final int height, final CallbackInfo ci) {
        MetalFxSpatialScaling.recordDisplaySize(width, height);
    }

    @Redirect(
            method = "resize",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;resize(II)V"
            )
    )
    private void metallum$resizeMainTarget(
            final RenderTarget target,
            final int displayWidth,
            final int displayHeight
    ) {
        MetalFxSpatialScaling.Dimensions dimensions = MetalFxSpatialScaling.effectiveDimensions(
                displayWidth,
                displayHeight
        );
        target.resize(dimensions.renderWidth(), dimensions.renderHeight());
    }

    @Redirect(
            method = "resize",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;resize(II)V"
            )
    )
    private void metallum$resizeLevelRenderer(
            final LevelRenderer renderer,
            final int displayWidth,
            final int displayHeight
    ) {
        MetalFxSpatialScaling.Dimensions dimensions = MetalFxSpatialScaling.effectiveDimensions(
                displayWidth,
                displayHeight
        );
        renderer.resize(dimensions.renderWidth(), dimensions.renderHeight());
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
                    ordinal = 0
            )
    )
    private GpuBufferSlice metallum$captureFinalWorldProjection(
            final ProjectionMatrixBuffer projectionBuffer,
            final Matrix4f finalProjection,
            final DeltaTracker deltaTracker
    ) {
        CameraRenderState camera = this.gameRenderState.levelRenderState.cameraRenderState;
        MetalDevice device = MetalDevice.getInstance();
        if (device != null && this.minecraft.level != null) {
            this.metallum$cameraInverse.set(camera.viewRotationMatrix).invert();
            Vec3 position = camera.pos;
            double deltaSeconds = deltaTracker.getRealtimeDeltaTicks() / 20.0;
            if (!camera.projectionMatrix.isFinite()
                    || !camera.viewRotationMatrix.isFinite()
                    || !finalProjection.isFinite()
                    || !this.metallum$cameraInverse.isFinite()
                    || !Double.isFinite(position.x)
                    || !Double.isFinite(position.y)
                    || !Double.isFinite(position.z)
                    || !Double.isFinite(deltaSeconds)
                    || deltaSeconds < 0.0
                    || !Float.isFinite(camera.depthFar)
                    || camera.depthFar <= 0.05f) {
                return projectionBuffer.getBuffer(finalProjection);
            }
            int displayWidth = MetalFxSpatialScaling.configuredDisplayWidth(this.mainRenderTarget.width);
            int displayHeight = MetalFxSpatialScaling.configuredDisplayHeight(this.mainRenderTarget.height);
            device.publishRendererGenerationState(displayWidth, displayHeight);
            if (this.metallum$hasPreviousBaseProjection
                    && !this.metallum$previousBaseProjection.equals(camera.projectionMatrix)) {
                TemporalResetEvents.signal(FrameState.HistoryResetReason.FOV_PROJECTION_CHANGE);
            }
            this.metallum$previousBaseProjection.set(camera.projectionMatrix);
            this.metallum$hasPreviousBaseProjection = true;

            Entity cameraEntity = this.minecraft.getCameraEntity();
            if (this.metallum$previousCameraEntity != null
                    && cameraEntity != this.metallum$previousCameraEntity) {
                TemporalResetEvents.signal(FrameState.HistoryResetReason.CAMERA_CUT);
            }
            this.metallum$previousCameraEntity = cameraEntity;

            Matrix4 view = matrix(camera.viewRotationMatrix);
            Matrix4 cameraMatrix = matrix(this.metallum$cameraInverse);
            Matrix4 projection = matrix(finalProjection);
            FrameState.Transforms transforms = new FrameState.Transforms(
                    cameraMatrix,
                    view,
                    projection,
                    cameraMatrix,
                    view,
                    projection
            );
            Object dimensionKey = this.minecraft.level.dimension();
            if (dimensionKey != this.metallum$dimensionKey) {
                this.metallum$dimensionKey = dimensionKey;
                this.metallum$dimensionIdentity = stableIdentity(
                        this.minecraft.level.dimension().identifier().toString()
                );
            }
            device.publishFrameState(new FrameCapture(
                    transforms,
                    new FrameState.CameraPosition(position.x, position.y, position.z),
                    deltaSeconds,
                    0.05,
                    camera.depthFar,
                    Integer.toUnsignedLong(System.identityHashCode(this.minecraft.level)),
                    this.metallum$dimensionIdentity
            ));
        }
        return projectionBuffer.getBuffer(finalProjection);
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"
            )
    )
    private void metallum$diagnoseWorldBeforeUiDepthClear(
            final CommandEncoder encoder,
            final GpuTexture depthTexture,
            final double clearDepth,
            final DeltaTracker deltaTracker
    ) {
        MetalDevice device = MetalDevice.getInstance();
        if (device != null) {
            device.encodeTemporalDiagnostics(depthTexture);
        }
        encoder.clearDepthTexture(depthTexture, clearDepth);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "FIELD",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;width:I",
                    ordinal = 0
            )
    )
    private int metallum$compareDisplayWidth(final RenderTarget target) {
        return target instanceof MainTarget && MetalFxSpatialScaling.isActive()
                ? MetalFxSpatialScaling.configuredDisplayWidth(target.width)
                : target.width;
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "FIELD",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;height:I",
                    ordinal = 0
            )
    )
    private int metallum$compareDisplayHeight(final RenderTarget target) {
        return target instanceof MainTarget && MetalFxSpatialScaling.isActive()
                ? MetalFxSpatialScaling.configuredDisplayHeight(target.height)
                : target.height;
    }

    private static Matrix4 matrix(final Matrix4f value) {
        return Matrix4.ofJoml(value);
    }

    private static long stableIdentity(final String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }
}
