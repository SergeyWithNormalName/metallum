package com.metallum.mixin.sodium;

import com.metallum.client.lighting.AdvancedLightCandidateSlot;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.LightSectionCandidate;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Candidate ownership follows the exact lifetime of its Sodium build output. */
@Mixin(value = ChunkBuildOutput.class, remap = false)
abstract class ChunkBuildOutputAdvancedLightMixin implements AdvancedLightCandidateSlot {
    @Unique
    @Nullable
    private volatile LightSectionCandidate metallum$advancedLightCandidate;

    @Override
    public synchronized void metallum$setAdvancedLightCandidate(
            @Nullable final LightSectionCandidate candidate
    ) {
        LightSectionCandidate previous = this.metallum$advancedLightCandidate;
        if (previous == candidate) {
            return;
        }
        this.metallum$advancedLightCandidate = candidate;
        if (previous != null) {
            AdvancedLightRegistry.global().discardCandidate(previous);
        }
    }

    @Override
    @Nullable
    public synchronized LightSectionCandidate metallum$takeAdvancedLightCandidate() {
        LightSectionCandidate candidate = this.metallum$advancedLightCandidate;
        this.metallum$advancedLightCandidate = null;
        return candidate;
    }

    @Override
    public synchronized void metallum$discardAdvancedLightCandidate() {
        LightSectionCandidate candidate = this.metallum$takeAdvancedLightCandidate();
        if (candidate != null) {
            AdvancedLightRegistry.global().discardCandidate(candidate);
        }
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void metallum$discardDestroyedAdvancedLightCandidate(final CallbackInfo ci) {
        if (this.metallum$advancedLightCandidate != null) {
            this.metallum$discardAdvancedLightCandidate();
        }
    }
}
