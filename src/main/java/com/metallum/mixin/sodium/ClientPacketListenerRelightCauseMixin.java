package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.sodium.SodiumRelightCauseTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.BitSet;
import java.util.Iterator;

/** Captures standalone packet light invalidations, excluding initial chunk light. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerRelightCauseMixin {
    @WrapOperation(
            method = "readSectionList(IILnet/minecraft/world/level/lighting/LevelLightEngine;Lnet/minecraft/world/level/LightLayer;Ljava/util/BitSet;Ljava/util/BitSet;Ljava/util/Iterator;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionDirtyWithNeighbors(III)V"
            ),
            require = 1,
            allow = 1
    )
    private void metallum$withNeighborRelightCause(
            final ClientLevel level,
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final Operation<Void> original,
            final int chunkX,
            final int chunkZ,
            final LevelLightEngine lightEngine,
            final LightLayer lightLayer,
            final BitSet presentSections,
            final BitSet emptySections,
            final Iterator<byte[]> sectionData,
            final boolean scheduleLightUpdates
    ) {
        if (!scheduleLightUpdates) {
            original.call(level, sectionX, sectionY, sectionZ);
            return;
        }
        try (SodiumRelightCauseTracker.Scope ignored =
                     SodiumRelightCauseTracker.openNeighbors(sectionX, sectionY, sectionZ)) {
            original.call(level, sectionX, sectionY, sectionZ);
        }
    }
}
