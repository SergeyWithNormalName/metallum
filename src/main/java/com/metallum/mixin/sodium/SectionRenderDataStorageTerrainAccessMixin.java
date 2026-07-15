package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumSectionRenderDataAccess;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = SectionRenderDataStorage.class, remap = false)
abstract class SectionRenderDataStorageTerrainAccessMixin implements SodiumSectionRenderDataAccess {
    @Shadow
    @Final
    private GlBufferSegment[] vertexAllocations;

    @Override
    @Nullable
    public GlBufferSegment metallum$getVertexAllocation(final int sectionIndex) {
        return this.vertexAllocations[sectionIndex];
    }
}
