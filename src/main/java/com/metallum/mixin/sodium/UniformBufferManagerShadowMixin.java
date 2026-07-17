package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.SodiumShadowUniformState;
import com.metallum.client.metal.render.SunShadowRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Supplies one Sodium terrain-uniform upload per shadow cascade and restores main matrices. */
@Mixin(value = UniformBufferManager.class, remap = false)
abstract class UniformBufferManagerShadowMixin {
    @Shadow
    private boolean hasUpdatedThisFrame;

    @Unique
    private SodiumShadowUniformState metallum$shadowUniformState;

    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private ChunkRenderMatrices metallum$useSunShadowProjection(
            final ChunkRenderMatrices matrices
    ) {
        if (this.metallum$shadowUniformState == null) {
            this.metallum$shadowUniformState = new SodiumShadowUniformState();
        }
        long token = SunShadowRenderer.activeCascadeToken();
        if (this.metallum$shadowUniformState.transition(token)) {
            this.hasUpdatedThisFrame = false;
        }
        Matrix4fc shadowProjection = SunShadowRenderer.activeTerrainProjection();
        if (token == 0L || shadowProjection == null) {
            return matrices;
        }
        return new ChunkRenderMatrices(shadowProjection, matrices.modelView());
    }
}
