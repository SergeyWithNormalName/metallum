package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.jspecify.annotations.Nullable;

/** Exact-version worker access attached by the task mixin. */
public interface SodiumRelightTaskAccess {
    void metallum$setRelightTaskStamp(SodiumRelightTaskStamp stamp);

    @Nullable
    SodiumRelightTaskStamp metallum$getRelightTaskStamp();

    ChunkRenderContext metallum$getRelightRenderContext();
}
