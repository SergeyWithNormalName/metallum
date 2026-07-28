package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.hdr.EmissiveTextureRegistry;
import com.metallum.client.hdr.SodiumHdrSemantic;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.model.SodiumShadeMode;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.LightCoordsUtil;
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
    @Unique
    private static final float[] METALLUM_FULL_BRIGHTNESS = {1.0f, 1.0f, 1.0f, 1.0f};

    @Shadow
    @Final
    private ChunkVertexEncoder.Vertex[] vertices;

    @Unique
    private BlockState metallum$blockState;

    @Unique
    private int metallum$blockLightEmission;

    /** Reused by one Sodium block-mesher instance; never allocated in the quad hot path. */
    @Unique
    private final float[] metallum$overlayUvs = new float[8];

    @Unique
    private final int[] metallum$overlayLights = new int[4];

    @Unique
    private TextureAtlasSprite metallum$partialEmissionOverlay;

    @Unique
    private boolean metallum$bufferingPartialEmissionOverlay;

    @Unique
    private boolean metallum$originalQuadEmissive;

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
        TextureAtlasSprite baseSprite = quad.sprite(SpriteFinderCache.forBlockAtlas());
        this.metallum$partialEmissionOverlay = EmissiveTextureRegistry.overlayFor(
                baseSprite, this.metallum$blockLightEmission
        );
        boolean overriddenEmissive = this.metallum$partialEmissionOverlay == null
                && (emissive || metallum$isSpecialEmissiveSource());
        original.call(renderer, quad, lightMode, overriddenEmissive, shadeMode);
    }

    @WrapOperation(
            method = "processQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;bufferQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;[FLnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V"
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private void metallum$bufferBaseThenEmissionOverlay(
            final BlockRenderer renderer,
            final MutableQuadViewImpl quad,
            final float[] brightness,
            final Material material,
            final Operation<Void> original
    ) {
        TextureAtlasSprite overlay = this.metallum$partialEmissionOverlay;
        original.call(renderer, quad, brightness, material);
        if (overlay == null) {
            return;
        }

        TextureAtlasSprite base = quad.sprite(SpriteFinderCache.forBlockAtlas());
        if (base == null) {
            this.metallum$partialEmissionOverlay = null;
            return;
        }

        metallum$saveQuadState(quad);
        try {
            metallum$mapUvsToOverlay(quad, base, overlay);
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.setLight(vertex, LightCoordsUtil.FULL_BRIGHT);
            }
            quad.setEmissive(true);
            quad.cachedSprite(overlay);
            this.metallum$bufferingPartialEmissionOverlay = true;
            original.call(renderer, quad, METALLUM_FULL_BRIGHTNESS, metallum$overlayMaterial(material));
        } finally {
            this.metallum$bufferingPartialEmissionOverlay = false;
            metallum$restoreQuadState(quad, base);
            this.metallum$partialEmissionOverlay = null;
        }
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
        boolean partialOverlay = this.metallum$partialEmissionOverlay != null;
        boolean exact = SodiumHdrSemantic.isExactTerrainQuad(
                quad.emissive(),
                partialOverlay,
                this.metallum$bufferingPartialEmissionOverlay
        );
        int emission = SodiumHdrSemantic.terrainQuadSurfaceEmission(
                this.metallum$blockState,
                this.metallum$blockLightEmission,
                quad.emissive(),
                partialOverlay,
                this.metallum$bufferingPartialEmissionOverlay
        );
        SodiumHdrSemantic.tagQuad(this.vertices, emission, exact);
    }

    @Unique
    private void metallum$saveQuadState(final MutableQuadViewImpl quad) {
        this.metallum$originalQuadEmissive = quad.emissive();
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 2;
            this.metallum$overlayUvs[offset] = quad.getTexU(vertex);
            this.metallum$overlayUvs[offset + 1] = quad.getTexV(vertex);
            this.metallum$overlayLights[vertex] = quad.getLight(vertex);
        }
    }

    @Unique
    private void metallum$mapUvsToOverlay(
            final MutableQuadViewImpl quad,
            final TextureAtlasSprite base,
            final TextureAtlasSprite overlay
    ) {
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 2;
            quad.setUV(
                    vertex,
                    EmissiveTextureRegistry.remapCoordinate(
                            this.metallum$overlayUvs[offset], base.getU0(), base.getU1(), overlay.getU0(), overlay.getU1()
                    ),
                    EmissiveTextureRegistry.remapCoordinate(
                            this.metallum$overlayUvs[offset + 1], base.getV0(), base.getV1(), overlay.getV0(), overlay.getV1()
                    )
            );
        }
    }

    @Unique
    private void metallum$restoreQuadState(final MutableQuadViewImpl quad, final TextureAtlasSprite base) {
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 2;
            quad.setUV(vertex, this.metallum$overlayUvs[offset], this.metallum$overlayUvs[offset + 1]);
            quad.setLight(vertex, this.metallum$overlayLights[vertex]);
        }
        quad.setEmissive(this.metallum$originalQuadEmissive);
        quad.cachedSprite(base);
    }

    @Unique
    private static Material metallum$overlayMaterial(final Material original) {
        return original == DefaultMaterials.SOLID ? DefaultMaterials.CUTOUT_MIPPED : original;
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
