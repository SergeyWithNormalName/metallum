package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumShadowCasterLists;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Replaces Sodium's main-camera list with the active light-frustum caster list. */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
abstract class SodiumWorldRendererShadowMixin {
    @Shadow
    private RenderSectionManager renderSectionManager;

    @ModifyArg(
            method = "renderLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderer;render("
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;"
                            + "Lnet/caffeinemc/mods/sodium/client/util/FogParameters;Z"
                            + "Lcom/mojang/blaze3d/textures/GpuSampler;"
                            + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                            + "Lcom/mojang/blaze3d/buffers/GpuBuffer;)V"
            ),
            index = 1,
            require = 1
    )
    private ChunkRenderListIterable metallum$useLightFrustumCasters(
            final ChunkRenderListIterable ordinaryLists
    ) {
        RenderSectionManagerShadowAccess access =
                (RenderSectionManagerShadowAccess) this.renderSectionManager;
        return SodiumShadowCasterLists.select(ordinaryLists, access.metallum$shadowRegions());
    }
}
