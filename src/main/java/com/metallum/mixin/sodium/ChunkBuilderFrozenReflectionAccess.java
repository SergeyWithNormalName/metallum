package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobTyped;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Consumer;

/** Pinned Sodium 0.9.1 bridge used only for the bounded frozen-field preload. */
@Mixin(value = ChunkBuilder.class, remap = false)
interface ChunkBuilderFrozenReflectionAccess {
    @Invoker("scheduleTask")
    <TASK extends ChunkBuilderTask<OUTPUT>, OUTPUT extends BuilderTaskOutput>
    ChunkJobTyped<TASK, OUTPUT> metallum$scheduleFrozenReflectionTask(
            TASK task,
            boolean important,
            Consumer<ChunkJobResult<OUTPUT>> consumer
    );
}
