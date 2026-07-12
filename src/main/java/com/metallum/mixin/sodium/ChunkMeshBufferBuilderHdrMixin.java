package com.metallum.mixin.sodium;

import com.metallum.client.hdr.SodiumHdrSemantic;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkMeshBufferBuilder.class)
abstract class ChunkMeshBufferBuilderHdrMixin {
    @Redirect(
            method = {
                    "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V",
                    "writeExternal(Ljava/nio/ByteBuffer;I[Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder;write(JI[Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)J"
            ),
            remap = false
    )
    private long metallum$writeSemanticMaterial(
            final ChunkVertexEncoder encoder,
            final long pointer,
            final int materialBits,
            final ChunkVertexEncoder.Vertex[] vertices,
            final int sectionIndex
    ) {
        return encoder.write(
                pointer,
                SodiumHdrSemantic.packMaterialBits(materialBits, vertices),
                vertices,
                sectionIndex
        );
    }
}
