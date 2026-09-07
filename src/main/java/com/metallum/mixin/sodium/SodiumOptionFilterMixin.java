package com.metallum.mixin.sodium;

import com.metallum.client.gui.SodiumOptionFilter;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.config.builder.OptionGroupBuilderImpl", remap = false)
public abstract class SodiumOptionFilterMixin {
    @Inject(method = "addOption", at = @At("HEAD"), cancellable = true)
    private void metallum$filterDeadOptions(final OptionBuilder builder, final CallbackInfoReturnable<OptionGroupBuilder> cir) {
        if (builder instanceof SodiumOptionBuilderAccessor accessor) {
            Identifier id = accessor.metallum$getId();
            if (SodiumOptionFilter.isBlocked(id)) {
                cir.setReturnValue((OptionGroupBuilder) (Object) this);
            }
        }
    }
}
