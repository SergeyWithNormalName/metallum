package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalHdrFrame;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class HdrSceneCaptureMixin {
    @Unique
    private boolean metallum$renderOwnsMaterialWorldPass;

    @Inject(method = "render", at = @At("HEAD"))
    private void metallum$beginMaterialWorldPass(
            final DeltaTracker deltaTracker,
            final boolean advanceGameTime,
            final CallbackInfo ci
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean worldSceneRendered = MetalHdrFrame.shouldCaptureWorldScene(
                minecraft.isGameLoadFinished(),
                advanceGameTime,
                minecraft.level != null
        );
        this.metallum$renderOwnsMaterialWorldPass = worldSceneRendered;
        GameRenderer self = (GameRenderer) (Object) this;
        MetalHdrFrame.setWorldScenePass(
                self.mainRenderTarget().getColorTextureView(),
                worldSceneRendered
        );
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void metallum$beginDirectMaterialWorldPass(
            final DeltaTracker deltaTracker,
            final CallbackInfo ci
    ) {
        if (!this.metallum$renderOwnsMaterialWorldPass) {
            GameRenderer self = (GameRenderer) (Object) this;
            MetalHdrFrame.setWorldScenePass(
                    self.mainRenderTarget().getColorTextureView(),
                    true
            );
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void metallum$endDirectMaterialWorldPass(
            final DeltaTracker deltaTracker,
            final CallbackInfo ci
    ) {
        if (!this.metallum$renderOwnsMaterialWorldPass) {
            GameRenderer self = (GameRenderer) (Object) this;
            MetalHdrFrame.setWorldScenePass(
                    self.mainRenderTarget().getColorTextureView(),
                    false
            );
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V",
                    ordinal = 0
            )
    )
    private void metallum$captureSceneBeforeGui(
            final DeltaTracker deltaTracker,
            final boolean advanceGameTime,
            final CallbackInfo ci
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean worldSceneRendered = MetalHdrFrame.shouldCaptureWorldScene(
                minecraft.isGameLoadFinished(),
                advanceGameTime,
                minecraft.level != null
        );
        GameRenderer self = (GameRenderer) (Object) this;
        try {
            MetalHdrFrame.captureScene(
                    self.mainRenderTarget().getColorTextureView(),
                    self.mainRenderTarget().getDepthTextureView(),
                    worldSceneRendered
            );
        } finally {
            MetalHdrFrame.setWorldScenePass(
                    self.mainRenderTarget().getColorTextureView(),
                    false
            );
            this.metallum$renderOwnsMaterialWorldPass = false;
        }
    }
}
