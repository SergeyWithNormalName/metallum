package com.metallum.mixin.sodium;

import com.metallum.client.gi.receiver.GiReceiverRuntime;
import com.metallum.client.sodium.SodiumG5CarrierMetadata;
import com.metallum.client.sodium.SodiumG5CarrierUpdatedQuadsAccess;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.UpdatedQuadsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Captures G5 ownership from the exact live BSP slots after Sodium finalizes their counts. */
@Mixin(
        targets = "net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting."
                + "bsp_tree.BSPWorkspace",
        remap = false
)
abstract class BSPWorkspaceG5CarrierMixin {
    @Inject(method = "getFinalizedUpdatedQuads", at = @At("RETURN"), require = 1)
    private void metallum$captureFinalizedLiveG5Carrier(
            final CallbackInfoReturnable<UpdatedQuadsList> cir
    ) {
        UpdatedQuadsList updatedQuads = cir.getReturnValue();
        if (!GiReceiverRuntime.isRequested() || updatedQuads == null) {
            return;
        }
        boolean liveHasG5Carrier = SodiumG5CarrierMetadata.hasLiveG5Carrier(
                (List<?>) (Object) this
        );
        ((SodiumG5CarrierUpdatedQuadsAccess) updatedQuads)
                .metallum$setLiveHasG5Carrier(liveHasG5Carrier);
    }
}
