package com.metallum.mixin.render;

import com.metallum.client.metal.render.SunShadowRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Quad particles select Minecraft's main render target directly instead of
 * honoring RenderSystem's output overrides. They therefore must not run while
 * the L4 caster pass temporarily owns a shadow target and projection.
 */
@Mixin(QuadParticleFeatureRenderer.class)
abstract class QuadParticleFeatureRendererShadowMixin {
    @Inject(method = "executeGroup", at = @At("HEAD"), cancellable = true)
    private void metallum$excludeParticlesFromSunShadow(
            final FeatureFrameContext context,
            final int groupIndex,
            final List<QuadParticleFeatureRenderer.Submit> submits,
            final boolean strictlyOrdered,
            final CallbackInfo ci
    ) {
        if (SunShadowRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
