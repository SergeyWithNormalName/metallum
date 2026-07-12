package com.metallum.client.hdr;

import com.metallum.Metallum;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SodiumHdrSemantic {
    private static final AtomicBoolean ACTIVE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean MATERIAL_CONFLICT_LOGGED = new AtomicBoolean();

    private SodiumHdrSemantic() {
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
