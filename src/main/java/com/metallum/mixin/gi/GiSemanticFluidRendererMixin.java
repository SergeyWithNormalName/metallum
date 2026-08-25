package com.metallum.mixin.gi;

import com.metallum.client.gi.capture.GiSemanticCaptureScope;
import com.metallum.client.gi.capture.GiSemanticQuadSample;
import com.metallum.client.gi.semantic.GiSemanticMedium;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact Sodium 0.9.1 fluid writeQuad observer; it never reads light or brightness arrays. */
@Mixin(value = DefaultFluidRenderer.class, remap = false)
abstract class GiSemanticFluidRendererMixin {
    @Unique @Nullable private BlockState metallum$giFluidBlock;
    @Unique @Nullable private FluidState metallum$giFluidState;
    @Unique private int metallum$giFluidEmission;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void metallum$captureGiFluid(final LevelSlice slice, final BlockState blockState,
                                        final FluidState fluidState, final BlockPos pos, final BlockPos origin,
                                        final TranslucentGeometryCollector collector,
                                        final ChunkModelBuilder meshBuilder, final Material material,
                                        final ColorProvider<FluidState> colorProvider, final FluidModel fluidModel,
                                        final CallbackInfo ci) {
        GiSemanticCaptureScope.seedIfNeeded(slice);
        this.metallum$giFluidBlock = blockState;
        this.metallum$giFluidState = fluidState;
        this.metallum$giFluidEmission = fluidState.createLegacyBlock().getLightEmission();
    }

    @Inject(
            method = "writeQuad",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/model/quad/ModelQuadView;getSprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;", shift = At.Shift.BEFORE),
            remap = false,
            require = 1,
            allow = 1
    )
    private void metallum$observeGiFluidQuad(final ChunkModelBuilder meshBuilder,
                                             final TranslucentGeometryCollector collector,
                                             final Material material, final BlockPos pos,
                                             final ModelQuadView quad, final ModelQuadFacing facing,
                                             final boolean flip, final CallbackInfo ci) {
        if (!GiSemanticCaptureScope.isActive()) return;
        try {
            metallum$observeGiFluidQuadChecked(material, pos, quad);
        } catch (Throwable ignored) {
            GiSemanticCaptureScope.failClosed();
        }
    }

    @Unique
    private void metallum$observeGiFluidQuadChecked(
            final Material material,
            final BlockPos pos,
            final ModelQuadView quad
    ) {
        BlockState block = this.metallum$giFluidBlock;
        FluidState fluid = this.metallum$giFluidState;
        TextureAtlasSprite sprite = quad.getSprite();
        if (block == null || fluid == null || sprite == null) return;
        if (!com.metallum.client.gi.capture.GiSemanticSpriteColorAtlas.contains(sprite.contents().name())) {
            GiSemanticCaptureScope.markUnknown(pos);
            return;
        }
        GiSemanticMedium medium = fluid.is(FluidTags.WATER)
                ? GiSemanticMedium.WATER : GiSemanticMedium.TRANSLUCENT;
        GiSemanticQuadSample sample = GiSemanticQuadSample.capture(
                quad, pos, sprite.contents().name(), material, block, medium
        );
        if (sample == null) return;
        int emission = Math.clamp(this.metallum$giFluidEmission, 0, 15);
        for (GiSemanticQuadSample.Observation observation : sample.observations(
                sample.red(), sample.green(), sample.blue(), emission
        )) {
            observation.submit();
        }
    }
}
