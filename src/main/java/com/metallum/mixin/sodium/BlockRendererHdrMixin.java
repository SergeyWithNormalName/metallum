package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.hdr.SodiumHdrSemantic;
import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.model.SodiumShadeMode;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockRenderer.class)
abstract class BlockRendererHdrMixin {
    @Shadow
    @Final
    private ChunkVertexEncoder.Vertex[] vertices;

    @Unique
    private BlockState metallum$blockState;

    @Unique
    private int metallum$blockLightEmission;

    @Inject(method = "renderModel", at = @At("HEAD"), remap = false)
    private void metallum$captureBlockEmission(
            final BlockStateModel model,
            final BlockState state,
            final BlockPos pos,
            final BlockPos origin,
            final CallbackInfo ci
    ) {
        this.metallum$blockState = state;
        this.metallum$blockLightEmission = state.getLightEmission();
    }

    @WrapOperation(
            method = "processQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;shadeQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;Lnet/caffeinemc/mods/sodium/client/model/light/LightMode;ZLnet/caffeinemc/mods/sodium/client/render/model/SodiumShadeMode;)V"
            ),
            remap = false
    )
    private void metallum$overrideEmissive(
            final BlockRenderer renderer,
            final MutableQuadViewImpl quad,
            final LightMode lightMode,
            final boolean emissive,
            final SodiumShadeMode shadeMode,
            final Operation<Void> original
    ) {
        boolean overriddenEmissive = emissive || metallum$isSpecialEmissiveSource();
        original.call(renderer, quad, lightMode, overriddenEmissive, shadeMode);
    }

    @Inject(
            method = "bufferQuad",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;sprite(Lnet/caffeinemc/mods/sodium/client/render/texture/SodiumSpriteFinder;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void metallum$tagEmissionVertices(
            final MutableQuadViewImpl quad,
            final float[] brightness,
            final Material material,
            final CallbackInfo ci
    ) {
        boolean exact = quad.emissive();
        int emission = exact ? 15 : this.metallum$blockLightEmission;
        SodiumHdrSemantic.tagQuad(this.vertices, emission, exact);
    }

    @Unique
    private boolean metallum$isSpecialEmissiveSource() {
        if (this.metallum$blockState == null) {
            return false;
        }
        return this.metallum$blockState.is(Blocks.SOUL_FIRE)
                || this.metallum$blockState.is(Blocks.SOUL_TORCH)
                || this.metallum$blockState.is(Blocks.SOUL_WALL_TORCH)
                || this.metallum$blockState.is(Blocks.SOUL_LANTERN)
                || this.metallum$blockState.is(Blocks.SOUL_CAMPFIRE)
                || this.metallum$blockState.is(Blocks.NETHER_PORTAL);
    }
}
