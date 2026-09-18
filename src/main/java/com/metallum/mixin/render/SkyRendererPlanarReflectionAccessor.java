package com.metallum.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SkyRenderer.class)
interface SkyRendererPlanarReflectionAccessor {
    @Accessor("renderTarget")
    RenderTarget metallum$renderTarget();
}
