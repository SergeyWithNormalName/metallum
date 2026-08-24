package com.metallum.mixin.render;

import com.metallum.client.metal.render.PlanarReflectionRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes cloud rendering into the same reflected target without sampling the main frame. */
@Mixin(CloudRenderer.class)
abstract class CloudRendererPlanarReflectionMixin {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()"
                            + "Lcom/mojang/blaze3d/pipeline/RenderTarget;"
            )
    )
    private RenderTarget metallum$usePlanarReflectionMainTarget(final GameRenderer renderer) {
        RenderTarget target = PlanarReflectionRenderer.activeTarget();
        return target != null ? target : renderer.mainRenderTarget();
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;cloudsTarget()"
                            + "Lcom/mojang/blaze3d/pipeline/RenderTarget;"
            )
    )
    private RenderTarget metallum$usePlanarReflectionCloudTarget(final LevelRenderer renderer) {
        RenderTarget target = PlanarReflectionRenderer.activeTarget();
        return target != null ? target : renderer.cloudsTarget();
    }
}
