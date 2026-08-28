package com.metallum.mixin.sodium;

import com.metallum.client.gi.receiver.CompactPositionCarrierSafety;
import com.metallum.client.gi.receiver.GiReceiverRuntime;
import com.metallum.client.sodium.SodiumG5CarrierMetadata;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** Moves accepted worker metadata into unused bits of the exact resident slice-mask word. */
@Mixin(value = RenderRegionManager.class, remap = false)
abstract class RenderRegionManagerG5CarrierMixin {
    private static final long METALLUM$SLICE_MASK_OFFSET = 16L;

    @Inject(
            method = "uploadResults(Ljava/util/Collection;"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void metallum$publishResidentG5CarrierMasks(
            final Collection<BuilderTaskOutput> results,
            final UniformBufferManager uniforms,
            final CallbackInfo ci
    ) {
        if (!GiReceiverRuntime.isRequested() || !CompactPositionCarrierSafety.isSafe()) {
            return;
        }
        try {
            for (BuilderTaskOutput result : results) {
                if (result instanceof ChunkBuildOutput output) {
                    metallum$publishOutput(output);
                }
            }
        } catch (Throwable throwable) {
            String detail = throwable.getMessage();
            CompactPositionCarrierSafety.reportConflict(
                    "could not publish exact resident G5 carrier metadata"
                            + (detail == null || detail.isBlank() ? "" : ": " + detail)
            );
        }
    }

    private static void metallum$publishOutput(final ChunkBuildOutput output) {
        RenderSection section = output.section;
        if (section.isDisposed()) {
            throw new IllegalStateException("accepted G5 carrier section was disposed");
        }
        RenderRegion region = section.getRegion();
        int sectionIndex = section.getSectionIndex();
        for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
            SectionRenderDataStorage storage = region.getStorage(pass);
            if (storage == null) {
                if (SodiumG5CarrierMetadata.infoFaceMask(output.info.flags, pass) != 0) {
                    throw new IllegalStateException("G5 metadata survived without resident storage");
                }
                continue;
            }
            long maskAddress = storage.getDataPointer(sectionIndex)
                    + METALLUM$SLICE_MASK_OFFSET;
            int residentMask = MemoryIntrinsics.getInt(maskAddress);
            int carrierFaceMask = SodiumG5CarrierMetadata.infoFaceMask(
                    output.info.flags,
                    pass
            );
            MemoryIntrinsics.putInt(
                    maskAddress,
                    SodiumG5CarrierMetadata.withResidentCarrierFaceMask(
                            residentMask,
                            carrierFaceMask
                    )
            );
        }
    }
}
