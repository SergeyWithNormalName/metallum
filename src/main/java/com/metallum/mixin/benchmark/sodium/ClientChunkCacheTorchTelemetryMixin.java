package com.metallum.mixin.benchmark.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.benchmark.TorchEpochTelemetry;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Captures the exact synchronous dirty call emitted by visible light-map swaps. */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheTorchTelemetryMixin {
    @WrapOperation(
            method = "onLightUpdate(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;setSectionDirty(III)V"
            ),
            require = 1,
            allow = 1
    )
    private void metallum$withExactLightRebuildScope(
            final LevelExtractor extractor,
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final Operation<Void> original
    ) {
        if (!TorchEpochTelemetry.isActive()) {
            original.call(extractor, sectionX, sectionY, sectionZ);
            return;
        }
        try (TorchEpochTelemetry.LightRebuildScope ignored =
                     TorchEpochTelemetry.openExactLightRebuildScope(sectionX, sectionY, sectionZ)) {
            original.call(extractor, sectionX, sectionY, sectionZ);
        }
    }
}
