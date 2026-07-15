package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import org.jspecify.annotations.Nullable;

public interface SodiumSectionRenderDataAccess {
    @Nullable
    GlBufferSegment metallum$getVertexAllocation(int sectionIndex);
}
