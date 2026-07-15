package com.metallum.mixin.render;

import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.metallum.client.metal.render.MetalDevice;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
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
}
