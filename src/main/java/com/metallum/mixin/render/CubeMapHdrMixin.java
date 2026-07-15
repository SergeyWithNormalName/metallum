package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrUiRenderTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps menu and loading panoramas out of the scene-linear FP16 attachment. */
@Mixin(CubeMap.class)
abstract class CubeMapHdrMixin {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"
            ),
            require = 1
    )
    private RenderTarget metallum$drawPanoramaIntoSdrTarget(final GameRenderer renderer) {
        RenderTarget uiTarget = HdrUiRenderTarget.activeTarget();
        return uiTarget != null ? uiTarget : renderer.mainRenderTarget();
    }
}
