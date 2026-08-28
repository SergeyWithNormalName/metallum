package com.metallum.mixin.sodium;

import com.metallum.client.gi.receiver.GiReceiverRuntime;
import com.metallum.client.sodium.SodiumG5CarrierBuilderAccess;
import com.metallum.client.sodium.SodiumG5CarrierInfoBuilderAccess;
import com.metallum.client.sodium.SodiumG5CarrierUpdatedQuadsAccess;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.BakedChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.UpdatedQuadsList;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reduces exact builder/live-BSP carrier ownership into the three accepted pass masks. */
@Mixin(value = ChunkBuildBuffers.class, remap = false)
abstract class ChunkBuildBuffersG5CarrierMixin {
    @Shadow
    @Final
    private Reference2ReferenceOpenHashMap<TerrainRenderPass, BakedChunkModelBuilder> builders;

    @Unique
    private BuiltSectionInfo.Builder metallum$activeInfoBuilder;
    @Unique
    private int metallum$remainingG5Passes;

    @Inject(method = "init", at = @At("HEAD"), require = 1)
    private void metallum$beginG5CarrierCensus(
            final BuiltSectionInfo.Builder renderData,
            final int sectionIndex,
            final CallbackInfo ci
    ) {
        this.metallum$activeInfoBuilder = GiReceiverRuntime.isRequested() ? renderData : null;
        this.metallum$remainingG5Passes = this.metallum$activeInfoBuilder == null ? 0 : 3;
    }

    @Inject(method = "createMesh", at = @At("RETURN"), require = 1)
    private void metallum$captureOrdinaryG5CarrierSlices(
            final TerrainRenderPass pass,
            final int visibleFaces,
            final boolean forceUnassigned,
            final boolean sliceReordering,
            final CallbackInfoReturnable<BuiltSectionMeshParts> cir
    ) {
        BuiltSectionInfo.Builder active = this.metallum$activeInfoBuilder;
        if (active == null) {
            return;
        }
        int faceMask = 0;
        BuiltSectionMeshParts mesh = cir.getReturnValue();
        if (mesh != null) {
            BakedChunkModelBuilder modelBuilder = this.builders.get(pass);
            if (forceUnassigned) {
                for (ModelQuadFacing facing : ModelQuadFacing.VALUES) {
                    if (((SodiumG5CarrierBuilderAccess) modelBuilder.getVertexBuffer(facing))
                            .metallum$hasG5Carrier()) {
                        faceMask = ModelQuadFacing.UNASSIGNED_MASK;
                        break;
                    }
                }
            } else {
                int[] segments = mesh.getVertexSegments();
                for (int segment = 0; segment < segments.length; segment += 2) {
                    int vertexCount = segments[segment];
                    int facingIndex = segments[segment + 1];
                    if (vertexCount > 0
                            && facingIndex >= 0
                            && facingIndex < ModelQuadFacing.COUNT) {
                        ChunkMeshBufferBuilder faceBuilder = modelBuilder.getVertexBuffer(
                                ModelQuadFacing.VALUES[facingIndex]
                        );
                        if (((SodiumG5CarrierBuilderAccess) faceBuilder)
                                .metallum$hasG5Carrier()) {
                            faceMask |= 1 << facingIndex;
                        }
                    }
                }
            }
        }
        ((SodiumG5CarrierInfoBuilderAccess) active).metallum$setG5CarrierFaceMask(
                pass,
                faceMask
        );
        metallum$finishG5Pass();
    }

    @Inject(method = "createModifiedTranslucentMesh", at = @At("RETURN"), require = 1)
    private void metallum$captureModifiedTranslucentG5CarrierSlice(
            final UpdatedQuadsList updatedQuads,
            final CallbackInfoReturnable<BuiltSectionMeshParts> cir
    ) {
        BuiltSectionInfo.Builder active = this.metallum$activeInfoBuilder;
        if (active == null) {
            return;
        }
        int faceMask = 0;
        BuiltSectionMeshParts mesh = cir.getReturnValue();
        if (mesh != null
                && ((SodiumG5CarrierUpdatedQuadsAccess) updatedQuads)
                        .metallum$liveHasG5Carrier()) {
            faceMask = ModelQuadFacing.UNASSIGNED_MASK;
        }
        ((SodiumG5CarrierInfoBuilderAccess) active).metallum$setG5CarrierFaceMask(
                DefaultTerrainRenderPasses.TRANSLUCENT,
                faceMask
        );
        metallum$finishG5Pass();
    }

    @Unique
    private void metallum$finishG5Pass() {
        this.metallum$remainingG5Passes--;
        if (this.metallum$remainingG5Passes == 0) {
            this.metallum$activeInfoBuilder = null;
        } else if (this.metallum$remainingG5Passes < 0) {
            throw new IllegalStateException("G5 carrier pass census exceeded Sodium's three passes");
        }
    }
}
