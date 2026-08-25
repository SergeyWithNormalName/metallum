package com.metallum.mixin.gi;

import com.metallum.client.gi.semantic.GiSemanticController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class GiSemanticLevelExtractorMixin {
    @Shadow @Nullable private ClientLevel level;

    @Inject(method = "onResourceManagerReload(Lnet/minecraft/server/packs/resources/ResourceManager;)V", at = @At("HEAD"))
    private void metallum$reloadGiWorld(final ResourceManager resources, final CallbackInfo ci) {
        ClientLevel current = this.level;
        if (current != null) {
            try {
                GiSemanticController.global().beginResourceReload(
                        current, current.dimension().identifier().toString()
                );
            } catch (RuntimeException ignored) {
            }
        }
    }
}
