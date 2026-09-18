package com.metallum.mixin.gi;

import com.metallum.client.gi.semantic.GiSemanticSectionTask;
import com.metallum.client.gi.semantic.GiSemanticTaskSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
abstract class GiSemanticTaskMixin implements GiSemanticTaskSlot {
    @Unique
    @Nullable
    private GiSemanticSectionTask metallum$giSemanticTask;

    @Override
    public synchronized void metallum$setGiSemanticTask(final GiSemanticSectionTask task) {
        if (task == null || this.metallum$giSemanticTask != null) {
            throw new IllegalStateException("G2 task stamp was assigned more than once");
        }
        this.metallum$giSemanticTask = task;
    }

    @Override
    @Nullable
    public synchronized GiSemanticSectionTask metallum$claimGiSemanticTask() {
        GiSemanticSectionTask task = this.metallum$giSemanticTask;
        this.metallum$giSemanticTask = null;
        return task;
    }
}
