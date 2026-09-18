package com.metallum.mixin.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only resource-reload access to the CPU sprite image; never used from the frame loop. */
@Mixin(SpriteContents.class)
public interface SpriteContentsImageAccess {
    @Accessor("originalImage")
    NativeImage metallum$getOriginalImage();
}
