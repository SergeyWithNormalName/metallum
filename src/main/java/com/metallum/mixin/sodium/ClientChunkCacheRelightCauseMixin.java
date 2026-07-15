package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.sodium.SodiumRelightCauseTracker;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Transfers exact visible light-map invalidations into the relight cause scope. */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheRelightCauseMixin {
    @WrapOperation(
            method = "onLightUpdate(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;setSectionDirty(III)V"
            ),
            require = 1,
            allow = 1
    )
    private void metallum$withExactRelightCause(
            final LevelExtractor extractor,
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final Operation<Void> original
    ) {
        try (SodiumRelightCauseTracker.Scope ignored =
                     SodiumRelightCauseTracker.openExact(sectionX, sectionY, sectionZ)) {
            original.call(extractor, sectionX, sectionY, sectionZ);
        }
    }
}
