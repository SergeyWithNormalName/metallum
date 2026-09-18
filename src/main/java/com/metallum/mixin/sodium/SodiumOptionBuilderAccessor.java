package com.metallum.mixin.sodium;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.config.builder.OptionBuilderImpl", remap = false)
public interface SodiumOptionBuilderAccessor {
    @Accessor("id")
    Identifier metallum$getId();
}
