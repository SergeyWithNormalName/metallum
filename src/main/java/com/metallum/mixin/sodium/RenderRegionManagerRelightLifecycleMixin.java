package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRelightCandidateSlot;
import com.metallum.client.sodium.SodiumRelightFastOutputSlot;
import com.metallum.client.sodium.SodiumRelightOracle;
import com.metallum.client.sodium.SodiumRelightPlanCache;
import com.metallum.client.sodium.SodiumRelightResidentPlanSlot;
import com.metallum.client.sodium.SodiumRelightResidentState;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** Publishes only outputs already selected and uploaded by Sodium. */
@Mixin(value = RenderRegionManager.class, remap = false)
abstract class RenderRegionManagerRelightLifecycleMixin {
    @Inject(
            method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("RETURN")
    )
    private void metallum$publishAcceptedRelightPlans(
            final Collection<BuilderTaskOutput> acceptedResults,
            final UniformBufferManager uniforms,
            final CallbackInfo ci
    ) {
        try {
            for (BuilderTaskOutput result : acceptedResults) {
                if (!(result instanceof ChunkBuildOutput output)) {
                    continue;
                }
                SodiumRelightPlanCache.Owner candidate = null;
                SodiumRelightResidentPlanSlot resident = null;
                try {
                    candidate = ((SodiumRelightCandidateSlot) output).metallum$takeRelightCandidate();
                    RenderSection section = output.section;
                    resident = (SodiumRelightResidentPlanSlot) section;
                    boolean fastOutput = output instanceof SodiumRelightFastOutputSlot marker
                            && marker.metallum$isFastRelightOutput();
                    if (section.isDisposed()) {
                        try {
                            resident.metallum$clearRelightPlan();
                        } catch (Throwable cleanupFailure) {
                            metallum$recordOracleError();
                        }
                        metallum$closeDiscardedCandidate(candidate);
                        continue;
                    }
                    if (fastOutput) {
                        if (candidate != null) {
                            metallum$closeDiscardedCandidate(candidate);
                            candidate = null;
                            metallum$recordOracleError();
                        }
                        // The synthetic output is derived from the exact resident state.
                        // A compact commit keeps that state; a full-upload fallback makes
                        // the new baseline generation mismatch and the next task remeshes.
                        continue;
                    }
                    // A full accepted mesh without an exact recipe invalidates the
                    // previous resident plan just as decisively as a new candidate.
                    SodiumRelightResidentState next = candidate == null
                            ? null
                            : new SodiumRelightResidentState(
                                    candidate,
                                    output.info,
                                    output.submitTime
                            );
                    resident.metallum$replaceRelightState(next);
                    if (candidate != null) {
                        SodiumRelightOracle.recordPublishedCandidate();
                        candidate = null;
                    }
                } catch (Throwable ignored) {
                    metallum$recordOracleError();
                    if (resident != null) {
                        try {
                            resident.metallum$clearRelightPlan();
                            if (candidate != null) {
                                metallum$recordDiscardedCandidate();
                            }
                            candidate = null;
                        } catch (Throwable cleanupFailure) {
                            metallum$recordOracleError();
                        }
                    }
                    metallum$closeDiscardedCandidate(candidate);
                }
            }
        } catch (Throwable ignored) {
            metallum$recordOracleError();
        }
    }

    @Unique
    private static void metallum$closeDiscardedCandidate(
            final SodiumRelightPlanCache.Owner candidate
    ) {
        if (candidate == null) {
            return;
        }
        try {
            candidate.close();
        } catch (Throwable ignored) {
            metallum$recordOracleError();
        }
        metallum$recordDiscardedCandidate();
    }

    @Unique
    private static void metallum$recordDiscardedCandidate() {
        try {
            SodiumRelightOracle.recordDiscardedCandidate();
        } catch (Throwable ignored) {
            metallum$recordOracleError();
        }
    }

    @Unique
    private static void metallum$recordOracleError() {
        try {
            SodiumRelightOracle.recordError();
        } catch (Throwable ignored) {
            // The diagnostic must never interfere with a successful upload.
        }
    }
}
