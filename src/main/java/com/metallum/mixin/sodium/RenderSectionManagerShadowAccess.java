package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact-version access to Sodium's loaded region owner for L4 caster collection. */
@Mixin(value = RenderSectionManager.class, remap = false)
public interface RenderSectionManagerShadowAccess {
    @Accessor("regions")
    RenderRegionManager metallum$shadowRegions();
}
