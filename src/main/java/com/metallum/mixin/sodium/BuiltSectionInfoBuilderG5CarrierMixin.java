package com.metallum.mixin.sodium;

import com.metallum.client.gi.receiver.GiReceiverRuntime;
import com.metallum.client.sodium.SodiumG5CarrierInfoAccess;
import com.metallum.client.sodium.SodiumG5CarrierInfoBuilderAccess;
import com.metallum.client.sodium.SodiumG5CarrierMetadata;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds only one primitive word to the already allocated worker builder; no mesh sidecar exists. */
@Mixin(value = BuiltSectionInfo.Builder.class, remap = false)
abstract class BuiltSectionInfoBuilderG5CarrierMixin
        implements SodiumG5CarrierInfoBuilderAccess {
    @Unique
    private int metallum$packedG5CarrierMasks;

    @Override
    public void metallum$setG5CarrierFaceMask(
            final TerrainRenderPass pass,
            final int faceMask
    ) {
        this.metallum$packedG5CarrierMasks = SodiumG5CarrierMetadata.replaceInfoFaceMask(
                this.metallum$packedG5CarrierMasks,
                pass,
                faceMask
        );
    }

    @Inject(method = "build()Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;",
            at = @At("RETURN"), require = 1)
    private void metallum$attachG5CarrierMasks(
            final CallbackInfoReturnable<BuiltSectionInfo> cir
    ) {
        if (GiReceiverRuntime.isRequested()) {
            ((SodiumG5CarrierInfoAccess) cir.getReturnValue()).metallum$mergeG5CarrierMasks(
                    this.metallum$packedG5CarrierMasks
            );
        }
    }
}
