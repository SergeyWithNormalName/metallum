package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.PlanarReflectionRenderer;
import com.metallum.client.metal.render.SodiumPlanarReflectionUniformState;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Supplies the mirrored projection and model-view for the isolated water reflection pass. */
@Mixin(value = UniformBufferManager.class, remap = false)
abstract class UniformBufferManagerPlanarReflectionMixin {
    @Shadow
    private boolean hasUpdatedThisFrame;

    @Unique
    private SodiumPlanarReflectionUniformState metallum$reflectionUniformState;

    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private ChunkRenderMatrices metallum$usePlanarReflectionMatrices(final ChunkRenderMatrices matrices) {
        if (this.metallum$reflectionUniformState == null) {
            this.metallum$reflectionUniformState = new SodiumPlanarReflectionUniformState();
        }
        long token = PlanarReflectionRenderer.activeTerrainToken();
        if (this.metallum$reflectionUniformState.transition(token)) {
            this.hasUpdatedThisFrame = false;
        }
        Matrix4fc projection = PlanarReflectionRenderer.activeTerrainProjection();
        Matrix4fc modelView = PlanarReflectionRenderer.activeTerrainModelView();
        if (token == 0L || projection == null || modelView == null) {
            return matrices;
        }
        return new ChunkRenderMatrices(projection, modelView);
    }
}
