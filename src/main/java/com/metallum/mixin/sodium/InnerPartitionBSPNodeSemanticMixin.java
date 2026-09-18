package com.metallum.mixin.sodium;

import com.metallum.client.hdr.SodiumHdrSemantic;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserves one agreed quad semantic across Sodium's version-locked translucent BSP split. */
@Mixin(
        targets = "net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting."
                + "bsp_tree.InnerPartitionBSPNode",
        remap = false
)
abstract class InnerPartitionBSPNodeSemanticMixin {
    @Shadow
    private static void copyVertexToMultiple(
            final ChunkVertexEncoder.Vertex source,
            final ChunkVertexEncoder.Vertex destinationA,
            final ChunkVertexEncoder.Vertex destinationB,
            final ChunkVertexEncoder.Vertex destinationC
    ) {
        throw new AssertionError();
    }

    @Redirect(
            method = "interpolateAttributes(FLorg/joml/Vector3fc;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                            + "ChunkVertexEncoder$Vertex;writeVertex("
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                            + "ChunkVertexEncoder$Vertex;FFFIFFFI)V"
            ),
            remap = false,
            require = 3,
            allow = 3
    )
    private static void metallum$writeWithAgreedSemantic(
            final ChunkVertexEncoder.Vertex destination,
            final float x,
            final float y,
            final float z,
            final int color,
            final float ao,
            final float u,
            final float v,
            final int light,
            final float planeDistance,
            final Vector3fc planeNormal,
            final ChunkVertexEncoder.Vertex endpointA,
            final ChunkVertexEncoder.Vertex endpointB,
            final ChunkVertexEncoder.Vertex destinationA,
            final ChunkVertexEncoder.Vertex destinationB,
            final ChunkVertexEncoder.Vertex destinationC
    ) {
        int semanticA = SodiumHdrSemantic.bspVertexSemanticSnapshot(endpointA);
        int semanticB = SodiumHdrSemantic.bspVertexSemanticSnapshot(endpointB);
        ChunkVertexEncoder.Vertex.writeVertex(
                destination, x, y, z, color, ao, u, v, light
        );
        SodiumHdrSemantic.propagateBspInterpolatedSemantic(
                semanticA, semanticB, destinationA, destinationB, destinationC
        );
    }

    @Redirect(
            method = "interpolateAttributes(FLorg/joml/Vector3fc;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                    + "ChunkVertexEncoder$Vertex;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/"
                            + "bsp_tree/InnerPartitionBSPNode;copyVertexToMultiple("
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                            + "ChunkVertexEncoder$Vertex;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                            + "ChunkVertexEncoder$Vertex;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                            + "ChunkVertexEncoder$Vertex;"
                            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                            + "ChunkVertexEncoder$Vertex;)V"
            ),
            remap = false,
            require = 3,
            allow = 3
    )
    private static void metallum$copyWithAgreedSemantic(
            final ChunkVertexEncoder.Vertex source,
            final ChunkVertexEncoder.Vertex copyDestinationA,
            final ChunkVertexEncoder.Vertex copyDestinationB,
            final ChunkVertexEncoder.Vertex copyDestinationC,
            final float planeDistance,
            final Vector3fc planeNormal,
            final ChunkVertexEncoder.Vertex endpointA,
            final ChunkVertexEncoder.Vertex endpointB,
            final ChunkVertexEncoder.Vertex destinationA,
            final ChunkVertexEncoder.Vertex destinationB,
            final ChunkVertexEncoder.Vertex destinationC
    ) {
        int semanticA = SodiumHdrSemantic.bspVertexSemanticSnapshot(endpointA);
        int semanticB = SodiumHdrSemantic.bspVertexSemanticSnapshot(endpointB);
        copyVertexToMultiple(
                source, copyDestinationA, copyDestinationB, copyDestinationC
        );
        SodiumHdrSemantic.propagateBspInterpolatedSemantic(
                semanticA, semanticB, destinationA, destinationB, destinationC
        );
    }
}
