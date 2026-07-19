package com.metallum.mixin.render;

import com.metallum.client.hdr.HeldItemEmission;
import com.metallum.client.hdr.HeldItemEmissionState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.class)
abstract class ItemStackRenderStateEmissionMixin implements HeldItemEmissionState {
    @Unique
    private int metallum$heldItemEmission;

    @Override
    public void metallum$setHeldItemEmission(final int emission) {
        this.metallum$heldItemEmission = emission;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void metallum$clearHeldItemEmission(final CallbackInfo ci) {
        this.metallum$heldItemEmission = 0;
    }

    @ModifyVariable(method = "submit", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int metallum$encodeHeldItemEmission(final int lightCoords) {
        return HeldItemEmission.encodeLightCoords(lightCoords, this.metallum$heldItemEmission);
    }
}
