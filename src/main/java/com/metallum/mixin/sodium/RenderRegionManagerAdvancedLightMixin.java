package com.metallum.mixin.sodium;

import com.metallum.client.lighting.AdvancedLightCandidateSlot;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightResidentSlot;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.LightSectionCandidate;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** Publishes only the exact collection Sodium has already accepted and uploaded. */
@Mixin(value = RenderRegionManager.class, remap = false)
abstract class RenderRegionManagerAdvancedLightMixin {
    @Inject(
            method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("RETURN")
    )
    private void metallum$publishAcceptedAdvancedLights(
            final Collection<BuilderTaskOutput> acceptedResults,
            final UniformBufferManager uniforms,
            final CallbackInfo ci
    ) {
        if (!AdvancedLightingRuntime.shouldCollect()) {
            return;
        }
        AdvancedLightRegistry registry = AdvancedLightRegistry.global();
        registry.observeHook(AdvancedLightRegistry.Hook.ACCEPTED_UPLOAD);
        for (BuilderTaskOutput result : acceptedResults) {
            if (!(result instanceof ChunkBuildOutput output)) {
                continue;
            }
            LightSectionCandidate candidate = null;
            try {
                candidate = ((AdvancedLightCandidateSlot) output).metallum$takeAdvancedLightCandidate();
                if (candidate == null) {
                    continue;
                }
                if (output.section.isDisposed()) {
                    registry.discardCandidate(candidate);
                    continue;
                }
                if (registry.publishAccepted(candidate)) {
                    ((AdvancedLightResidentSlot) output.section).metallum$setAdvancedLightOwnerToken(
                            registry.currentOwnerToken(
                                    candidate.task().world(),
                                    candidate.task().sectionKey()
                            )
                    );
                }
                candidate = null;
            } catch (Throwable failure) {
                registry.discardCandidate(candidate);
                registry.failClosed("accepted light publication failed", failure);
                // Sodium's successful geometry upload remains valid; lighting falls back atomically.
            }
        }
    }
}
