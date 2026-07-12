package com.metallum.mixin.sodium;

import com.metallum.client.hdr.HdrEmissionVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkVertexEncoder.Vertex.class)
abstract class ChunkVertexEncoderVertexHdrMixin implements HdrEmissionVertex {
    @Unique
    private int metallum$hdrSemantic;

    @Override
    public int metallum$getHdrSemantic() {
        return this.metallum$hdrSemantic;
    }

    @Override
    public void metallum$setHdrSemantic(final int semantic) {
        this.metallum$hdrSemantic = semantic;
    }

    @Inject(method = "writeVertex", at = @At("TAIL"), remap = false)
    private static void metallum$clearSemanticOnWrite(
            final ChunkVertexEncoder.Vertex vertex,
            final float x,
            final float y,
            final float z,
            final int color,
            final float ao,
            final float u,
            final float v,
            final int light,
            final CallbackInfo ci
    ) {
        ((HdrEmissionVertex) vertex).metallum$setHdrSemantic(0);
    }

    @Inject(method = "copyVertexTo", at = @At("TAIL"), remap = false)
    private static void metallum$copySemantic(
            final ChunkVertexEncoder.Vertex source,
            final ChunkVertexEncoder.Vertex destination,
            final CallbackInfo ci
    ) {
        ((HdrEmissionVertex) destination).metallum$setHdrSemantic(
                ((HdrEmissionVertex) source).metallum$getHdrSemantic()
        );
    }
}
