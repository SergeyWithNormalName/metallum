package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.sodium.SodiumRelightOracle;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;

/** Binds one exact-version oracle session around the mandatory full meshing task. */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
abstract class ChunkBuilderMeshingTaskRelightOracleMixin {
    @WrapMethod(
            method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            remap = false,
            require = 1,
            allow = 1
    )
    private ChunkBuildOutput metallum$runExactRelightOracle(
            final ChunkBuildContext context,
            final CancellationToken cancellationToken,
            final Operation<ChunkBuildOutput> original
    ) {
        return SodiumRelightOracle.executeMeshingTask(
                (ChunkBuilderMeshingTask) (Object) this,
                context,
                cancellationToken,
                original
        );
    }
}
