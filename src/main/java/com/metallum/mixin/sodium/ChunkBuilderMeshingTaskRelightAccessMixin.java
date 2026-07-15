package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRelightTaskAccess;
import com.metallum.client.sodium.SodiumRelightTaskStamp;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Exact task metadata copied once before the job crosses onto a worker. */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
abstract class ChunkBuilderMeshingTaskRelightAccessMixin implements SodiumRelightTaskAccess {
    @Shadow
    @Final
    private ChunkRenderContext renderContext;

    @Unique
    @Nullable
    private volatile SodiumRelightTaskStamp metallum$relightTaskStamp;

    @Override
    public void metallum$setRelightTaskStamp(final SodiumRelightTaskStamp stamp) {
        if (stamp == null || this.metallum$relightTaskStamp != null) {
            throw new IllegalStateException("Sodium relight task stamp was assigned more than once");
        }
        this.metallum$relightTaskStamp = stamp;
    }

    @Override
    @Nullable
    public SodiumRelightTaskStamp metallum$getRelightTaskStamp() {
        return this.metallum$relightTaskStamp;
    }

    @Override
    public ChunkRenderContext metallum$getRelightRenderContext() {
        return this.renderContext;
    }
}
