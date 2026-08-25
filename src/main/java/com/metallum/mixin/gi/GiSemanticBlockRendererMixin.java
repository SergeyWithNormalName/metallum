package com.metallum.mixin.gi;

import com.metallum.client.gi.capture.GiSemanticCaptureScope;
import com.metallum.client.gi.capture.GiSemanticQuadSample;
import com.metallum.client.gi.semantic.GiSemanticMedium;
import com.metallum.client.hdr.EmissiveTextureRegistry;
import com.metallum.client.hdr.SodiumHdrSemantic;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact Sodium 0.9.1 accepted-quad observer, structurally absent unless G2 capture is enabled. */
@Mixin(value = BlockRenderer.class, remap = false)
abstract class GiSemanticBlockRendererMixin {
    @Unique @Nullable private BlockState metallum$giBlockState;
    @Unique @Nullable private BlockPos metallum$giBlockPos;
    @Unique @Nullable private Identifier metallum$giExpectedOverlay;
    @Unique @Nullable private GiSemanticQuadSample metallum$giPendingBase;

    @Inject(method = "prepare", at = @At("HEAD"), remap = false)
    private void metallum$seedGiState(final ChunkBuildBuffers buffers, final LevelSlice slice,
                                     final TranslucentGeometryCollector collector, final CallbackInfo ci) {
        GiSemanticCaptureScope.seedIfNeeded(slice);
    }

    @Inject(method = "renderModel", at = @At("HEAD"), remap = false)
    private void metallum$captureGiBlock(final BlockStateModel model, final BlockState state,
                                        final BlockPos pos, final BlockPos origin, final CallbackInfo ci) {
        metallum$flushPendingBase();
        this.metallum$giBlockState = state;
        this.metallum$giBlockPos = pos;
        this.metallum$giExpectedOverlay = null;
    }

    @Inject(
            method = "bufferQuad",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;sprite(Lnet/caffeinemc/mods/sodium/client/render/texture/SodiumSpriteFinder;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;", shift = At.Shift.BEFORE),
            remap = false,
            require = 1,
            allow = 1
    )
    private void metallum$observeGiBlockQuad(final MutableQuadViewImpl quad, final float[] ignoredBrightness,
                                             final Material material, final CallbackInfo ci) {
        if (!GiSemanticCaptureScope.isActive()) return;
        try {
            metallum$observeGiBlockQuadChecked(quad, material);
        } catch (Throwable ignored) {
            GiSemanticCaptureScope.failClosed();
        }
    }

    @Unique
    private void metallum$observeGiBlockQuadChecked(final MutableQuadViewImpl quad, final Material material) {
        BlockState state = this.metallum$giBlockState;
        BlockPos pos = this.metallum$giBlockPos;
        if (state == null || pos == null) return;
        TextureAtlasSprite sprite = quad.sprite(SpriteFinderCache.forBlockAtlas());
        if (sprite == null) return;
        Identifier spriteId = sprite.contents().name();
        if (!com.metallum.client.gi.capture.GiSemanticSpriteColorAtlas.contains(spriteId)) {
            GiSemanticCaptureScope.markUnknown(pos);
            return;
        }
        GiSemanticMedium medium = state.getFluidState().is(FluidTags.WATER)
                ? GiSemanticMedium.WATER
                : material.isTranslucent() ? GiSemanticMedium.TRANSLUCENT
                : state.isSolidRender() ? GiSemanticMedium.OPAQUE : GiSemanticMedium.CUTOUT;
        GiSemanticQuadSample sample = GiSemanticQuadSample.capture(
                quad, pos, spriteId, material, state, medium
        );
        if (sample == null) return;

        boolean overlayPass = spriteId.equals(this.metallum$giExpectedOverlay);
        if (overlayPass) {
            GiSemanticQuadSample base = this.metallum$giPendingBase;
            this.metallum$giPendingBase = null;
            this.metallum$giExpectedOverlay = null;
            if (base != null) {
                int emission = SodiumHdrSemantic.terrainQuadSurfaceEmission(
                        state, state.getLightEmission(), true, false, true
                );
                submit(base, sample.red(), sample.green(), sample.blue(), emission);
            }
            return;
        }

        // A well-formed partial-emission pair is immediate. If a different accepted quad arrives
        // first, retain the prior base as a non-emitting surface instead of overwriting it or
        // accidentally pairing it with a later overlay from another face.
        if (this.metallum$giPendingBase != null) {
            metallum$flushPendingBase();
        }

        TextureAtlasSprite overlay = EmissiveTextureRegistry.overlayFor(sprite, state.getLightEmission());
        if (overlay != null) {
            this.metallum$giExpectedOverlay = overlay.contents().name();
            this.metallum$giPendingBase = sample;
            return;
        }
        int emission = SodiumHdrSemantic.terrainQuadSurfaceEmission(
                state, state.getLightEmission(), quad.emissive(), false, false
        );
        submit(sample, sample.red(), sample.green(), sample.blue(), emission);
    }

    @Inject(method = "release", at = @At("HEAD"), remap = false)
    private void metallum$releaseGiBlock(final CallbackInfo ci) {
        metallum$flushPendingBase();
        this.metallum$giBlockState = null;
        this.metallum$giBlockPos = null;
        this.metallum$giExpectedOverlay = null;
    }

    @Unique
    private void metallum$flushPendingBase() {
        GiSemanticQuadSample pending = this.metallum$giPendingBase;
        this.metallum$giPendingBase = null;
        this.metallum$giExpectedOverlay = null;
        if (pending != null) {
            submit(pending, 0.0F, 0.0F, 0.0F, 0);
        }
    }

    @Unique
    private static void submit(final GiSemanticQuadSample sample, final float emissionRed,
                               final float emissionGreen, final float emissionBlue, final int emission) {
        for (GiSemanticQuadSample.Observation observation : sample.observations(
                emissionRed, emissionGreen, emissionBlue, emission
        )) {
            observation.submit();
        }
    }
}
