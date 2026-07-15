package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumBufferSegmentAccess;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = GlBufferSegment.class, remap = false)
abstract class GlBufferSegmentTerrainAccessMixin implements SodiumBufferSegmentAccess {
    @Shadow
    @Final
    private GlBufferArena arena;

    @Shadow
    private boolean free;

    @Override
    public GlBufferArena metallum$getArena() {
        return this.arena;
    }

    @Override
    public boolean metallum$isFree() {
        return this.free;
    }
}
