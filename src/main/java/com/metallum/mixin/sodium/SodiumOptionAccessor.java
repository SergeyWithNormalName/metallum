package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Option.class, remap = false)
public interface SodiumOptionAccessor {
    @Accessor("id")
    Identifier metallum$getId();
}
