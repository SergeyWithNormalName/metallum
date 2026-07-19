package com.metallum.mixin.render;

import com.metallum.client.hdr.HeldItemEmission;
import com.metallum.client.hdr.HeldItemEmissionState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemModelResolver.class)
abstract class ItemModelResolverEmissionMixin {
    @Inject(method = "updateForTopItem", at = @At("RETURN"))
    private void metallum$tagHeldBlockEmission(
            final ItemStackRenderState output,
            final ItemStack item,
            final ItemDisplayContext displayContext,
            @Nullable final Level level,
            @Nullable final ItemOwner owner,
            final int seed,
            final CallbackInfo ci
    ) {
        ((HeldItemEmissionState) output).metallum$setHeldItemEmission(
                HeldItemEmission.surfaceEmission(item, displayContext)
        );
    }
}
