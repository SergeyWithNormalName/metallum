package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalHdrFrame;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class HdrSceneCaptureMixin {
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
            final boolean renderLevel,
            final CallbackInfo ci
    ) {
        GameRenderer self = (GameRenderer) (Object) this;
        MetalHdrFrame.captureScene(
                self.mainRenderTarget().getColorTextureView(),
                self.mainRenderTarget().getDepthTextureView()
        );
    }
}
