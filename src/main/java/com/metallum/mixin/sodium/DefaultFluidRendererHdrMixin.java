package com.metallum.mixin.sodium;

import com.metallum.client.hdr.SodiumHdrSemantic;
import com.metallum.client.sodium.SodiumRainExposureSnapshot;
import com.metallum.client.sodium.SodiumRainExposureSnapshotAccess;
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
import net.minecraft.tags.FluidTags;
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

    @Unique
    private FluidState metallum$fluidState;

    /**
     * Captured once per fluid block from the remesh-time motion-blocking heightmap. It is not
     * equivalent to propagated skylight, which can remain bright under a roof or in a cave.
     */
    @Unique
    private boolean metallum$fluidSkyExposed = true;

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
        this.metallum$fluidState = fluidState;
        this.metallum$fluidLightEmission = fluidState.createLegacyBlock().getLightEmission();
        SodiumRainExposureSnapshot snapshot =
                ((SodiumRainExposureSnapshotAccess) (Object) slice).metallum$getRainExposureSnapshot();
        // Fail open when Sodium's asynchronous snapshot is unavailable: preserve the exact
        // vanilla light coordinate rather than darkening a potentially open water surface.
        this.metallum$fluidSkyExposed = snapshot == null
                || snapshot.canSeeSky(pos.getX(), pos.getY() + 1, pos.getZ());
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
        boolean water = this.metallum$fluidLightEmission == 0
                && fluidIsWater(this.metallum$fluidState);
        if (water && !this.metallum$fluidSkyExposed) {
            // Advanced water optics use the sky coordinate as their exposure signal. Clear only
            // its upper lightmap half for a roofed/cave column; block-light remains untouched.
            for (ChunkVertexEncoder.Vertex vertex : this.vertices) {
                vertex.light &= 0x0000ffff;
            }
        }
        SodiumHdrSemantic.tagQuad(
                this.vertices,
                this.metallum$fluidLightEmission,
                this.metallum$fluidLightEmission > 0,
                water
                        ? SodiumHdrSemantic.SURFACE_CLASS_WATER
                        : SodiumHdrSemantic.SURFACE_CLASS_NONE
        );
    }

    @Unique
    private static boolean fluidIsWater(final FluidState state) {
        return state != null && state.is(FluidTags.WATER);
    }
}
