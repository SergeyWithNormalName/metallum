package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/** Worker-local primitive carrier masks accumulated on Sodium's existing section-info builder. */
public interface SodiumG5CarrierInfoBuilderAccess {
    void metallum$setG5CarrierFaceMask(TerrainRenderPass pass, int faceMask);
}
