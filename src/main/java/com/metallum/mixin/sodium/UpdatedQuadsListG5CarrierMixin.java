package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumG5CarrierUpdatedQuadsAccess;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.UpdatedQuadsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Stores the exact finalized live-BSP census without retaining quad or buffer state. */
@Mixin(value = UpdatedQuadsList.class, remap = false)
abstract class UpdatedQuadsListG5CarrierMixin implements SodiumG5CarrierUpdatedQuadsAccess {
    @Unique
    private boolean metallum$liveHasG5Carrier;

    @Override
    public void metallum$setLiveHasG5Carrier(final boolean liveHasG5Carrier) {
        this.metallum$liveHasG5Carrier = liveHasG5Carrier;
    }

    @Override
    public boolean metallum$liveHasG5Carrier() {
        return this.metallum$liveHasG5Carrier;
    }
}
