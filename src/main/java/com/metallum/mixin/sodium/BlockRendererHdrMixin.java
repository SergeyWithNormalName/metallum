package com.metallum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.client.hdr.EmissiveTextureRegistry;
import com.metallum.client.hdr.SodiumHdrSemantic;
import com.metallum.client.lighting.SurfaceMaterialPolicy;
import com.metallum.client.sodium.SodiumRainExposureSnapshot;
import com.metallum.client.sodium.SodiumRainExposureSnapshotAccess;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.model.SodiumShadeMode;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
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

    @Unique
    private LevelSlice metallum$slice;

    @Unique
    private BlockPos metallum$blockPos;

    @Unique
    private SodiumRainExposureSnapshot metallum$rainExposureSnapshot;

    @Unique
    private boolean metallum$blockRainExposed;

    @Unique
    private boolean metallum$blockSubmerged;

    @Unique
    private int metallum$blockSubmergedDepth;

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

    @Inject(method = "prepare", at = @At("HEAD"), remap = false)
    private void metallum$captureRainExposureSnapshot(
            final ChunkBuildBuffers buffers,
            final LevelSlice slice,
            final TranslucentGeometryCollector collector,
            final CallbackInfo ci
    ) {
        this.metallum$slice = slice;
        this.metallum$rainExposureSnapshot =
                ((SodiumRainExposureSnapshotAccess) (Object) slice)
                        .metallum$getRainExposureSnapshot();
    }

    @Inject(method = "release", at = @At("TAIL"), remap = false)
    private void metallum$releaseRainExposureSnapshot(final CallbackInfo ci) {
        this.metallum$slice = null;
        this.metallum$blockPos = null;
        this.metallum$rainExposureSnapshot = null;
        this.metallum$blockRainExposed = false;
        this.metallum$blockSubmerged = false;
        this.metallum$blockSubmergedDepth = 0;
    }

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
        this.metallum$blockPos = pos;
        SodiumRainExposureSnapshot rainExposure = this.metallum$rainExposureSnapshot;
        this.metallum$blockRainExposed = rainExposure != null
                && rainExposure.canRainReach(pos.getX(), pos.getY() + 1, pos.getZ());

        LevelSlice slice = this.metallum$slice;
        if (slice != null && pos != null) {
            boolean blockWater = state.getFluidState().is(FluidTags.WATER);
            boolean waterAbove = slice.getFluidState(pos.above()).is(FluidTags.WATER);
            if (blockWater || waterAbove) {
                int waterY = pos.getY() + (waterAbove ? 1 : 0);
                int posX = pos.getX();
                int posZ = pos.getZ();
                for (int step = 1; step <= 63; step++) {
                    if (slice.getFluidState(new BlockPos(posX, waterY + 1, posZ)).is(FluidTags.WATER)) {
                        waterY++;
                    } else {
                        break;
                    }
                }
                int surfaceY = waterY + 1;
                this.metallum$blockSubmergedDepth = Math.clamp(surfaceY - pos.getY(), 1, 63);
                this.metallum$blockSubmerged = true;
            } else {
                this.metallum$blockSubmerged = false;
                this.metallum$blockSubmergedDepth = 0;
            }
        } else {
            this.metallum$blockSubmerged = false;
            this.metallum$blockSubmergedDepth = 0;
        }
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
        SurfaceMaterialPolicy.Kind surfaceKind = SurfaceMaterialPolicy.forTerrain(
                this.metallum$blockState,
                material.isTranslucent()
        ).kind();
        boolean upwardFace = quad.faceNormal().y() > SurfaceMaterialPolicy.RAIN_FACING_START;
        boolean rainExposed = upwardFace && this.metallum$blockRainExposed;
        int surfaceClass = switch (surfaceKind) {
            // Preserve intrinsic optics on vertical faces. Upward sheltered faces are left on the
            // legacy path because the compact byte has no independent precipitation bit.
            case METAL -> !upwardFace || rainExposed
                    ? SodiumHdrSemantic.SURFACE_CLASS_METAL
                    : SodiumHdrSemantic.SURFACE_CLASS_NONE;
            case SMOOTH_DIELECTRIC -> !upwardFace || rainExposed
                    ? SodiumHdrSemantic.SURFACE_CLASS_SMOOTH_DIELECTRIC
                    : SodiumHdrSemantic.SURFACE_CLASS_NONE;
            case GLASS -> SodiumHdrSemantic.SURFACE_CLASS_GLASS;
            case STONE -> rainExposed
                    ? SodiumHdrSemantic.SURFACE_CLASS_STONE
                    : SodiumHdrSemantic.SURFACE_CLASS_NONE;
            case WOOD -> rainExposed
                    ? SodiumHdrSemantic.SURFACE_CLASS_WOOD
                    : SodiumHdrSemantic.SURFACE_CLASS_NONE;
            case POROUS -> rainExposed
                    ? SodiumHdrSemantic.SURFACE_CLASS_POROUS
                    : SodiumHdrSemantic.SURFACE_CLASS_NONE;
            case DIELECTRIC -> rainExposed
                    ? SodiumHdrSemantic.SURFACE_CLASS_DIELECTRIC
                    : SodiumHdrSemantic.SURFACE_CLASS_NONE;
            default -> SodiumHdrSemantic.SURFACE_CLASS_NONE;
        };
        boolean submerged = this.metallum$blockSubmerged;
        int submergedDepth = this.metallum$blockSubmergedDepth;
        if (!submerged && this.metallum$slice != null && this.metallum$blockPos != null) {
            Direction facing = quad.getLightFace();
            if (facing != null) {
                BlockPos waterPos = this.metallum$blockPos.relative(facing);
                if (this.metallum$slice.getFluidState(waterPos).is(FluidTags.WATER)) {
                    int waterY = waterPos.getY();
                    int posX = waterPos.getX();
                    int posZ = waterPos.getZ();
                    for (int step = 1; step <= 63; step++) {
                        if (this.metallum$slice.getFluidState(new BlockPos(posX, waterY + 1, posZ)).is(FluidTags.WATER)) {
                            waterY++;
                        } else {
                            break;
                        }
                    }
                    int surfaceY = waterY + 1;
                    submerged = true;
                    submergedDepth = Math.clamp(surfaceY - this.metallum$blockPos.getY(), 1, 63);
                }
            }
        }
        SodiumHdrSemantic.tagQuad(this.vertices, emission, exact, surfaceClass, submerged, submergedDepth);
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
