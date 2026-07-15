package com.metallum.mixin.render;

import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.TemporalResetEvents;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Converts server camera discontinuities into one-shot temporal reset events. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerTemporalMixin {
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void metallum$signalTeleport(
            final ClientboundPlayerPositionPacket packet,
            final CallbackInfo ci
    ) {
        TemporalResetEvents.signal(FrameState.HistoryResetReason.TELEPORT);
    }
}
