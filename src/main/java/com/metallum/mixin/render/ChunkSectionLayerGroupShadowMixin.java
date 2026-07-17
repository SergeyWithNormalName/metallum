package com.metallum.mixin.render;

import com.metallum.client.metal.render.SunShadowRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Redirects Minecraft's reusable terrain draw groups only while the explicit L4 pass is active. */
@Mixin(ChunkSectionLayerGroup.class)
abstract class ChunkSectionLayerGroupShadowMixin {
    @Inject(method = "outputTarget", at = @At("HEAD"), cancellable = true)
    private void metallum$useSunShadowTarget(final CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = SunShadowRenderer.activeTarget();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }
}
