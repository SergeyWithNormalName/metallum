package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRelightCandidateSlot;
import com.metallum.client.sodium.SodiumRelightOracle;
import com.metallum.client.sodium.SodiumRelightPlanCache;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkBuildOutput.class, remap = false)
abstract class ChunkBuildOutputRelightLifecycleMixin implements SodiumRelightCandidateSlot {
    @Unique
    @Nullable
    private volatile SodiumRelightPlanCache.Owner metallum$relightCandidate;

    @Override
    public synchronized void metallum$setRelightCandidate(
            @Nullable final SodiumRelightPlanCache.Owner candidate
    ) {
        SodiumRelightPlanCache.Owner previous = this.metallum$relightCandidate;
        if (previous == candidate) {
            return;
        }
        this.metallum$relightCandidate = candidate;
        if (previous != null) {
            metallum$closeDiscardedCandidate(previous);
        }
    }

    @Override
    @Nullable
    public synchronized SodiumRelightPlanCache.Owner metallum$takeRelightCandidate() {
        SodiumRelightPlanCache.Owner candidate = this.metallum$relightCandidate;
        this.metallum$relightCandidate = null;
        return candidate;
    }

    @Override
    public synchronized void metallum$discardRelightCandidate() {
        SodiumRelightPlanCache.Owner candidate = this.metallum$relightCandidate;
        this.metallum$relightCandidate = null;
        if (candidate != null) {
            metallum$closeDiscardedCandidate(candidate);
        }
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void metallum$discardStaleRelightCandidate(final CallbackInfo ci) {
        try {
            SodiumRelightPlanCache.Owner candidate = this.metallum$takeRelightCandidate();
            if (candidate == null) {
                return;
            }
            metallum$closeDiscardedCandidate(candidate);
            metallum$recordStaleCandidate();
        } catch (Throwable ignored) {
            metallum$recordOracleError();
        }
    }

    @Unique
    private static void metallum$closeDiscardedCandidate(
            final SodiumRelightPlanCache.Owner candidate
    ) {
        try {
            candidate.close();
        } catch (Throwable ignored) {
            metallum$recordOracleError();
        }
        try {
            SodiumRelightOracle.recordDiscardedCandidate();
        } catch (Throwable ignored) {
            metallum$recordOracleError();
        }
    }

    @Unique
    private static void metallum$recordStaleCandidate() {
        try {
            SodiumRelightOracle.recordStaleCandidate();
        } catch (Throwable ignored) {
            metallum$recordOracleError();
        }
    }

    @Unique
    private static void metallum$recordOracleError() {
        try {
            SodiumRelightOracle.recordError();
        } catch (Throwable ignored) {
            // The diagnostic must never interfere with Sodium's destruction path.
        }
    }
}
