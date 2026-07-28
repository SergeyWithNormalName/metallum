package com.metallum.mixin.render;

import com.metallum.client.hdr.EmissiveTextureRegistry;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes only a completely stitched block-atlas generation to terrain meshing. */
@Mixin(TextureAtlas.class)
abstract class TextureAtlasEmissiveMixin {
    @Inject(method = "upload", at = @At("TAIL"))
    private void metallum$publishStitchedEmissiveSprites(
            final SpriteLoader.Preparations preparations,
            final CallbackInfo ci
    ) {
        EmissiveTextureRegistry.onBlockAtlasUploaded((TextureAtlas) (Object) this, preparations);
    }
}
