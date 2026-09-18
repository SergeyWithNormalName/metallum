package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.PlanarReflectionRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes Sodium terrain into the active water reflection target only for the nested pass. */
@Mixin(value = TerrainRenderPass.class, remap = false)
abstract class TerrainRenderPassPlanarReflectionMixin {
    @Inject(method = "getTarget", at = @At("HEAD"), cancellable = true)
    private void metallum$usePlanarReflectionTarget(final CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = PlanarReflectionRenderer.activeTarget();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }
}
