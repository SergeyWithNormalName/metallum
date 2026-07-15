package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.joml.Vector3dc;

/** Separate estimator category for tasks admitted to the relight fast path. */
public final class SodiumRelightFastMeshingTask extends ChunkBuilderMeshingTask {
    public SodiumRelightFastMeshingTask(
            final RenderSection section,
            final int submitTime,
            final Vector3dc absoluteCameraPos,
            final ChunkRenderContext renderContext,
            final SortBehavior sortBehavior,
            final boolean forceSort,
            final boolean blockingTask
    ) {
        super(
                section,
                submitTime,
                absoluteCameraPos,
                renderContext,
                sortBehavior,
                forceSort,
                blockingTask
        );
    }
}
