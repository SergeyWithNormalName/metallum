package com.metallum.mixin.gi;

import com.metallum.client.gi.semantic.GiSemanticController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class GiSemanticClientLevelMixin {
    @Inject(method = "setBlocksDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"))
    private void metallum$markGiContentDirty(final BlockPos pos, final BlockState oldState,
                                             final BlockState newState, final CallbackInfo ci) {
        ClientLevel level = (ClientLevel) (Object) this;
        try {
            GiSemanticController.global().markBlockDirty(
                    level, level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ()
            );
        } catch (RuntimeException ignored) {
            // Content invalidation never interferes with the client world's accepted block update.
        }
    }
}
