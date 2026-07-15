package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.sodium.SodiumRelightFastOutputSlot;
import com.metallum.client.sodium.SodiumRelightFastPath;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Rejects a synthetic output if newer resident state won the race. */
@Mixin(value = RenderSectionManager.class, remap = false)
abstract class RenderSectionManagerRelightOutputGuardMixin {
    @Shadow
    public abstract void scheduleRebuild(int sectionX, int sectionY, int sectionZ, boolean playerChanged);

    @WrapOperation(
            method = "applyBuildOutputs(Ljava/util/ArrayList;)Ljava/util/List;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;addBuildOutput(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/BuilderTaskOutput;)Z"
            ),
            require = 1,
            allow = 1
    )
    private boolean metallum$rejectStaleFastRelightOutput(
            final RenderSection section,
            final BuilderTaskOutput result,
            final Operation<Boolean> original
    ) {
        if (result instanceof ChunkBuildOutput output
                && output instanceof SodiumRelightFastOutputSlot marker
                && marker.metallum$isFastRelightOutput()
                && !SodiumRelightFastPath.isOutputCurrent(output)) {
            this.scheduleRebuild(
                    section.getChunkX(),
                    section.getChunkY(),
                    section.getChunkZ(),
                    true
            );
            SodiumRelightFastPath.recordForcedRebuild();
            return false;
        }
        return original.call(section, result);
    }
}
