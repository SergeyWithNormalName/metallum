package com.metallum.mixin.gi;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.gi.capture.GiSemanticCaptureScope;
import com.metallum.client.gi.semantic.GiSemanticCandidateSlot;
import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.gi.semantic.GiSemanticSectionCandidate;
import com.metallum.client.gi.semantic.GiSemanticSectionReservation;
import com.metallum.client.gi.semantic.GiSemanticSectionTask;
import com.metallum.client.gi.semantic.GiSemanticTaskSlot;
import com.metallum.client.sodium.SodiumRelightFastOutputSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;

/** Worker ownership exactly brackets Sodium's authoritative execute implementation. */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
abstract class GiSemanticMeshingScopeMixin {
    @WrapMethod(
            method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            remap = false,
            require = 1,
            allow = 1
    )
    private ChunkBuildOutput metallum$captureGiAcceptedMesh(
            final ChunkBuildContext context,
            final CancellationToken cancellationToken,
            final Operation<ChunkBuildOutput> original
    ) {
        GiSemanticSectionTask task = ((GiSemanticTaskSlot) this).metallum$claimGiSemanticTask();
        if (task == null) {
            return original.call(context, cancellationToken);
        }
        GiSemanticController controller = GiSemanticController.global();
        GiSemanticSectionReservation reservation;
        try {
            reservation = controller.reserve(task);
        } catch (RuntimeException ignored) {
            return original.call(context, cancellationToken);
        }
        if (reservation == null) {
            return original.call(context, cancellationToken);
        }
        GiSemanticCaptureScope scope;
        try {
            scope = GiSemanticCaptureScope.open(task);
        } catch (RuntimeException ignored) {
            reservation.close();
            return original.call(context, cancellationToken);
        }
        try (reservation; scope) {
            ChunkBuildOutput output = original.call(context, cancellationToken);
            if (output == null || output instanceof SodiumRelightFastOutputSlot fast
                    && fast.metallum$isFastRelightOutput()) {
                return output;
            }
            GiSemanticSectionCandidate candidate = null;
            try {
                GiSemanticCaptureScope.Result captured = scope.finish(context.cache.getWorldSlice());
                if (captured != null) {
                    candidate = reservation.complete(captured.seed(), captured.observations());
                    ((GiSemanticCandidateSlot) output).metallum$setGiSemanticCandidate(candidate);
                    candidate = null;
                }
            } catch (RuntimeException ignored) {
                controller.discardCandidate(candidate);
                // The accepted geometry output remains valid; G2 stays unknown for this revision.
            }
            return output;
        }
    }
}
