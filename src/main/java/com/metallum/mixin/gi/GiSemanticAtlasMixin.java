package com.metallum.mixin.gi;

import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.gi.capture.GiSemanticSpriteColorAtlas;
import com.metallum.client.gi.capture.GiSemanticPaletteFactory;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
abstract class GiSemanticAtlasMixin {
    @Inject(method = "upload", at = @At("TAIL"))
    private void metallum$advanceGiMaterialEpoch(final SpriteLoader.Preparations preparations,
                                                 final CallbackInfo ci) {
        TextureAtlas atlas = (TextureAtlas) (Object) this;
        if (TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) {
            try {
                GiSemanticSpriteColorAtlas.publish(preparations);
                GiSemanticController.global().advanceMaterialAtlasEpoch(GiSemanticPaletteFactory.seeds());
            } catch (Throwable ignored) {
                // A failed palette build still retires stale tasks and admits no new G2 work.
                try {
                    GiSemanticController.global().advanceMaterialAtlasEpoch();
                } catch (Throwable ignoredAgain) {
                }
            }
        }
    }
}
