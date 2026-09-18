package com.metallum.mixin.sodium;

import com.metallum.client.gi.receiver.CompactPositionCarrierSafety;
import com.metallum.client.sodium.SodiumG5CarrierInfoAccess;
import com.metallum.client.sodium.SodiumG5CarrierMetadata;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/** Packs worker-only face masks into exact-version unused bits before publication. */
@Mixin(value = BuiltSectionInfo.class, remap = false)
abstract class BuiltSectionInfoG5CarrierMixin implements SodiumG5CarrierInfoAccess {
    @Shadow
    @Final
    @Mutable
    public int flags;

    @Override
    public void metallum$mergeG5CarrierMasks(final int packedMasks) {
        try {
            this.flags = SodiumG5CarrierMetadata.mergeInfoFlags(this.flags, packedMasks);
        } catch (IllegalArgumentException conflict) {
            CompactPositionCarrierSafety.reportConflict(
                    "could not claim BuiltSectionInfo.flags for G5 carrier metadata: "
                            + conflict.getMessage()
            );
        }
    }
}
