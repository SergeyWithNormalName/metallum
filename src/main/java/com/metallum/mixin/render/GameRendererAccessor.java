package com.metallum.mixin.render;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
interface GameRendererAccessor {
    @Accessor("BLUR_POST_CHAIN_ID")
    static Identifier metallum$getBlurPostChainId() {
        throw new AssertionError();
    }

    @Accessor("resourcePool")
    CrossFrameResourcePool metallum$getResourcePool();
}
