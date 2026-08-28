package com.metallum.mixin.sodium;

import com.metallum.client.hdr.SodiumHdrSemantic;
import com.metallum.client.sodium.SodiumG5CarrierBuilderAccess;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMeshBufferBuilder.class)
abstract class ChunkMeshBufferBuilderHdrMixin implements SodiumG5CarrierBuilderAccess {
    @Unique
    private boolean metallum$hasG5Carrier;

    @Inject(method = "start(I)V", at = @At("HEAD"), require = 1)
    private void metallum$resetG5CarrierCensus(final int sectionIndex, final CallbackInfo ci) {
        this.metallum$hasG5Carrier = false;
    }

    @Override
    public boolean metallum$hasG5Carrier() {
        return this.metallum$hasG5Carrier;
    }

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
        boolean positionCarrier = SodiumHdrSemantic.compactPositionCarrierRequested();
        int carrierCode = positionCarrier
                ? SodiumHdrSemantic.compactPositionCarrierCode(vertices) : 0;
        long endPointer = encoder.write(
                pointer,
                SodiumHdrSemantic.packMaterialBits(materialBits, vertices),
                vertices,
                sectionIndex
        );
        if (positionCarrier
                && SodiumHdrSemantic.writeCompactPositionCarrier(
                pointer,
                endPointer,
                carrierCode
        ) && (carrierCode & SodiumHdrSemantic.POSITION_CARRIER_G5_BIT) != 0) {
            this.metallum$hasG5Carrier = true;
        }
        return endPointer;
    }
}
