package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact Sodium 0.9.1 fields needed by the diagnostic relight oracle. */
@Mixin(value = AbstractBlockRenderContext.class, remap = false)
public interface SodiumRelightBlockContextAccess {
    @Accessor("pos")
    BlockPos metallum$getRelightPosition();

    @Accessor("lighters")
    LightPipelineProvider metallum$getRelightLighters();
}
