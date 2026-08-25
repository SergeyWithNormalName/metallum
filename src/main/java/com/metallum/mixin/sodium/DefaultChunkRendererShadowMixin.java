package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.SodiumIndexedIndirectBatcher;
import com.metallum.client.metal.render.SunShadowRenderer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Selects directional-light-facing mesh slices without depending on the main camera. */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
abstract class DefaultChunkRendererShadowMixin {
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()"
                            + "Lcom/mojang/blaze3d/systems/GpuDevice;",
                    shift = At.Shift.BEFORE
            ),
            require = 1
    )
    private void metallum$prepareIndexedIndirectBatches(
            final ChunkRenderMatrices matrices,
            final ChunkRenderListIterable renderLists,
            final TerrainRenderPass pass,
            final CameraTransform camera,
            final FogParameters fog,
            final boolean translucentSorting,
            final GpuSampler sampler,
            final GpuBufferSlice globals,
            final GpuBuffer sectionTimeInfo,
            final CallbackInfo ci
    ) {
        SodiumIndexedIndirectBatcher.prepare(renderLists, pass);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/"
                            + "MultiDrawBatch;draw(Lnet/caffeinemc/mods/sodium/client/gpu/device/context/"
                            + "DrawContext;)V"
            ),
            require = 1
    )
    private void metallum$drawPreparedIndexedIndirectBatch(
            final MultiDrawBatch batch,
            final DrawContext context
    ) {
        if (!SodiumIndexedIndirectBatcher.drawPrepared(batch, context)) {
            batch.draw(context);
        }
    }

    @Redirect(
            method = "fillCommandBuffer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/"
                            + "DefaultChunkRenderer;getVisibleFaces(IIIIII)I"
            ),
            require = 1
    )
    private static int metallum$useLightFacingSlices(
            final int originX,
            final int originY,
            final int originZ,
            final int chunkX,
            final int chunkY,
            final int chunkZ
    ) {
        Vector3fc toLight = SunShadowRenderer.activeTerrainToLightWorld();
        if (toLight == null) {
            return DefaultChunkRenderer.getVisibleFaces(
                    originX,
                    originY,
                    originZ,
                    chunkX,
                    chunkY,
                    chunkZ
            );
        }
        int centerX = (chunkX << 4) + 8;
        int centerY = (chunkY << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;
        int lightX = directionalOrigin(centerX, toLight.x());
        int lightY = directionalOrigin(centerY, toLight.y());
        int lightZ = directionalOrigin(centerZ, toLight.z());
        return DefaultChunkRenderer.getVisibleFaces(
                lightX,
                lightY,
                lightZ,
                chunkX,
                chunkY,
                chunkZ
        );
    }

    private static int directionalOrigin(final int center, final float direction) {
        if (direction > 1.0e-4f) {
            return center + 1_000_000;
        }
        if (direction < -1.0e-4f) {
            return center - 1_000_000;
        }
        return center;
    }
}
