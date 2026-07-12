package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalHdrFrame;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
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
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"
            )
    )
    private void metallum$captureSceneBeforeGui(
            final DeltaTracker deltaTracker,
            final boolean renderLevel,
            final CallbackInfo ci
    ) {
        if (Minecraft.getInstance().gui.screen() != null || Minecraft.getInstance().gui.overlay() != null) {
            return;
        }
        GameRenderer self = (GameRenderer) (Object) this;
        MetalHdrFrame.captureScene(self.mainRenderTarget().getColorTextureView());
    }
}
