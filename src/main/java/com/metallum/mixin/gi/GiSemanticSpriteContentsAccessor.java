package com.metallum.mixin.gi;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Render-thread-only source; callers immediately reduce pixels and never retain this image. */
@Mixin(SpriteContents.class)
public interface GiSemanticSpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage metallum$getOriginalImage();
}
