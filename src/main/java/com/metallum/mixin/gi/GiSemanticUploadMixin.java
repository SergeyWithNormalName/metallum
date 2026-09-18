package com.metallum.mixin.gi;

import com.metallum.client.gi.semantic.GiSemanticCandidateSlot;
import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.gi.semantic.GiSemanticResidentSlot;
import com.metallum.client.gi.semantic.GiSemanticSectionCandidate;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** Publication occurs only after Sodium has accepted and uploaded this exact output collection. */
@Mixin(value = RenderRegionManager.class, remap = false)
abstract class GiSemanticUploadMixin {
    @Inject(method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V", at = @At("RETURN"))
    private void metallum$publishAcceptedGi(final Collection<BuilderTaskOutput> accepted,
                                            final UniformBufferManager uniforms, final CallbackInfo ci) {
        GiSemanticController controller = GiSemanticController.global();
        for (BuilderTaskOutput result : accepted) {
            if (!(result instanceof ChunkBuildOutput output)) continue;
            GiSemanticSectionCandidate candidate = null;
            try {
                candidate = ((GiSemanticCandidateSlot) output).metallum$takeGiSemanticCandidate();
                if (candidate == null) continue;
                if (output.section.isDisposed()) {
                    controller.discardCandidate(candidate);
                    candidate = null;
                    continue;
                }
                if (controller.publishAccepted(candidate)) {
                    ((GiSemanticResidentSlot) output.section).metallum$setGiSemanticOwnerToken(
                            controller.currentOwnerToken(
                                    ((GiSemanticResidentSlot) output.section)
                                            .metallum$getGiSemanticWorldIdentity(),
                                    candidate.task().sectionKey()
                            )
                    );
                }
                candidate = null;
            } catch (Throwable ignored) {
                controller.discardCandidate(candidate);
                // Sodium has already uploaded valid geometry; G2 failure stays isolated.
            }
        }
    }
}
