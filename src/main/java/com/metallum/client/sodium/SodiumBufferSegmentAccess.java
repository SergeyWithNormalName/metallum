package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;

public interface SodiumBufferSegmentAccess {
    GlBufferArena metallum$getArena();

    boolean metallum$isFree();
}
