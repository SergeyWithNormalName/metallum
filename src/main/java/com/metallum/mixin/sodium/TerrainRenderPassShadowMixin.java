package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.SunShadowRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps Sodium's explicit terrain render pass out of Minecraft's main color/depth target. */
@Mixin(value = TerrainRenderPass.class, remap = false)
abstract class TerrainRenderPassShadowMixin {
    @Inject(method = "getTarget", at = @At("HEAD"), cancellable = true)
    private void metallum$useSunShadowTarget(final CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = SunShadowRenderer.activeTarget();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }
}
