package com.metallum.mixin.render;

import com.metallum.client.metal.render.PlanarReflectionRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Sends sky primitives through the active reflection target; no primary-frame copy is used. */
@Mixin(SkyRenderer.class)
abstract class SkyRendererPlanarReflectionMixin {
    @Redirect(
            method = {
                    "renderSkyDisc", "renderDarkDisc", "renderSun", "renderMoon", "renderStars",
                    "renderSunriseAndSunset", "renderEndSky", "renderEndFlash"
            },
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:"
                            + "Lcom/mojang/blaze3d/pipeline/RenderTarget;"
            ),
            require = 16
    )
    private RenderTarget metallum$usePlanarReflectionTarget(final SkyRenderer renderer) {
        RenderTarget target = PlanarReflectionRenderer.activeTarget();
        return target != null ? target : ((SkyRendererPlanarReflectionAccessor) renderer).metallum$renderTarget();
    }
}
