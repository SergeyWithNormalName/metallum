package com.metallum.mixin.render;

import com.metallum.client.metal.render.PlanarReflectionRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes reusable terrain draw groups to the reflected HDR target while it is being written. */
@Mixin(ChunkSectionLayerGroup.class)
abstract class ChunkSectionLayerGroupPlanarReflectionMixin {
    @Inject(method = "outputTarget", at = @At("HEAD"), cancellable = true)
    private void metallum$usePlanarReflectionTarget(final CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = PlanarReflectionRenderer.activeTarget();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }
}
