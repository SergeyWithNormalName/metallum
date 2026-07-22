package com.metallum.mixin.sodium;

import com.metallum.client.hdr.SodiumHdrSemantic;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultFluidRenderer.class)
abstract class DefaultFluidRendererHdrMixin {
    @Shadow
    @Final
    private ChunkVertexEncoder.Vertex[] vertices;

    @Unique
    private int metallum$fluidLightEmission;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void metallum$captureFluidEmission(
            final LevelSlice slice,
            final BlockState blockState,
            final FluidState fluidState,
            final BlockPos pos,
            final BlockPos origin,
            final TranslucentGeometryCollector collector,
            final ChunkModelBuilder meshBuilder,
            final Material material,
            final ColorProvider<FluidState> colorProvider,
            final FluidModel fluidModel,
            final CallbackInfo ci
    ) {
        this.metallum$fluidLightEmission = fluidState.createLegacyBlock().getLightEmission();
    }

    @Inject(
            method = "writeQuad",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/model/quad/ModelQuadView;getSprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void metallum$tagFluidVertices(
            final ChunkModelBuilder meshBuilder,
            final TranslucentGeometryCollector collector,
            final Material material,
            final BlockPos pos,
            final ModelQuadView quad,
            final ModelQuadFacing facing,
            final boolean flip,
            final CallbackInfo ci
    ) {
        SodiumHdrSemantic.tagQuad(
                this.vertices,
                this.metallum$fluidLightEmission,
                this.metallum$fluidLightEmission > 0
        );
    }
}
