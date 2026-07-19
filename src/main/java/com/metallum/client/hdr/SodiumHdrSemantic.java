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
