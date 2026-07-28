package com.metallum.client.hdr;

import com.metallum.Metallum;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SodiumHdrSemantic {
    private static final AtomicBoolean ACTIVE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean MATERIAL_CONFLICT_LOGGED = new AtomicBoolean();
    private static final int AMETHYST_GROWTH_SURFACE_EMISSION = 2;
    /**
     * Gamma 2.5 remap of Minecraft's 0..15 light-source levels. Keeping it
     * as a lookup table makes terrain meshing allocation-free and ensures
     * level 15 remains the exact HDR reference point.
     */
    private static final int[] PARTIAL_OVERLAY_EMISSION_CURVE = {
            0, 1, 1, 1, 1, 1, 2, 2, 3, 4, 5, 7, 9, 10, 13, 15
    };

    private SodiumHdrSemantic() {
    }

    /**
     * Returns the HDR semantic used for a block's visible surface. This is deliberately
     * independent from MinecraftLightPolicy: adjusting a material's apparent brightness
     * must never change its direct-light contribution to the world.
     */
    public static int surfaceEmission(
            final BlockState state,
            final int blockLightEmission,
            final boolean exact
    ) {
        if (exact) {
            return 15;
        }

        int emission = Math.clamp(blockLightEmission, 0, 15);
        if (state != null && isAmethystGrowth(state)) {
            return Math.min(emission, AMETHYST_GROWTH_SURFACE_EMISSION);
        }
        return emission;
    }

    /**
     * Decides whether a terrain quad uses the exact HDR-emission semantic.
     * Partial-emission textures are written twice: their base pass is normally
     * lit, while the immediately following mask overlay is exact-emissive.
     *
     * <p>This stays allocation-free because Sodium calls it for every terrain
     * quad.</p>
     */
    public static boolean isExactTerrainQuad(
            final boolean quadExactEmission,
            final boolean hasPartialEmissionOverlay,
            final boolean bufferingPartialEmissionOverlay
    ) {
        return bufferingPartialEmissionOverlay || (quadExactEmission && !hasPartialEmissionOverlay);
    }

    /**
     * Resolves surface emission for either half of a partial-emission terrain
     * pair. The base must not emit. The overlay retains exact-HDR treatment
     * but uses the block state's emission strength whenever one exists, so a
     * low-power source (such as a redstone torch) is visibly weaker than a
     * normal torch. Purely visual authored sidecars on non-emitting blocks
     * retain the established full-strength fallback.
     */
    public static int terrainQuadSurfaceEmission(
            final BlockState state,
            final int blockLightEmission,
            final boolean quadExactEmission,
            final boolean hasPartialEmissionOverlay,
            final boolean bufferingPartialEmissionOverlay
    ) {
        if (hasPartialEmissionOverlay && !bufferingPartialEmissionOverlay) {
            return 0;
        }
        if (bufferingPartialEmissionOverlay) {
            int sourceEmission = Math.clamp(blockLightEmission, 0, 15);
            return sourceEmission > 0 ? partialOverlayEmissionStrength(sourceEmission) : 15;
        }

        return surfaceEmission(
                state,
                blockLightEmission,
                isExactTerrainQuad(
                        quadExactEmission,
                        hasPartialEmissionOverlay,
                        bufferingPartialEmissionOverlay
                )
        );
    }

    static int partialOverlayEmissionStrength(final int blockLightEmission) {
        return PARTIAL_OVERLAY_EMISSION_CURVE[Math.clamp(blockLightEmission, 0, 15)];
    }

    private static boolean isAmethystGrowth(final BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD)
                || state.is(Blocks.MEDIUM_AMETHYST_BUD)
                || state.is(Blocks.LARGE_AMETHYST_BUD)
                || state.is(Blocks.AMETHYST_CLUSTER);
    }

    public static void tagQuad(
            final ChunkVertexEncoder.Vertex[] vertices,
            final int lightEmission,
            final boolean exact
    ) {
        int semantic = SodiumHdrShaderPatcher.encodeVertexSemantic(lightEmission, exact);
        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            ((HdrEmissionVertex) vertex).metallum$setHdrSemantic(semantic);
        }
        if (semantic != 0 && ACTIVE_LOGGED.compareAndSet(false, true)) {
            Metallum.LOGGER.info(
                    "Sodium semantic HDR emission tagging is active (strength {}, exact {})",
                    semantic & SodiumHdrShaderPatcher.HDR_VERTEX_EMISSION_MASK,
                    exact
            );
        }
    }

    public static int packMaterialBits(
            final int materialBits,
            final ChunkVertexEncoder.Vertex[] vertices
    ) {
        if ((materialBits & ~SodiumHdrShaderPatcher.SODIUM_MATERIAL_BASE_MASK) != 0) {
            if (MATERIAL_CONFLICT_LOGGED.compareAndSet(false, true)) {
                Metallum.LOGGER.warn(
                        "Sodium uses terrain material bits reserved by semantic HDR; HDR tagging is disabled for conflicting quads"
                );
            }
            return materialBits;
        }

        int emission = 0;
        boolean exact = false;
        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            int vertexSemantic = ((HdrEmissionVertex) vertex).metallum$getHdrSemantic();
            emission = Math.max(
                    emission,
                    vertexSemantic & SodiumHdrShaderPatcher.HDR_VERTEX_EMISSION_MASK
            );
            exact |= (vertexSemantic & SodiumHdrShaderPatcher.HDR_VERTEX_EXACT_BIT) != 0;
        }
        int semantic = SodiumHdrShaderPatcher.encodeVertexSemantic(emission, exact);
        return SodiumHdrShaderPatcher.packMaterialBits(materialBits, semantic);
    }
}
