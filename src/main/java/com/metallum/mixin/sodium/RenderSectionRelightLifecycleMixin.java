package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRelightPlanCache;
import com.metallum.client.sodium.SodiumRelightOracle;
import com.metallum.client.sodium.SodiumRelightResidentPlanSlot;
import com.metallum.client.sodium.SodiumRelightResidentState;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderSection.class, remap = false)
abstract class RenderSectionRelightLifecycleMixin implements SodiumRelightResidentPlanSlot {
    @Unique
    @Nullable
    private volatile SodiumRelightResidentState metallum$residentRelightState;

    @Unique
    @Nullable
    private BuiltSectionInfo metallum$latestBuiltSectionInfo;

    @Override
    public SodiumRelightResidentState.@Nullable Lease metallum$acquireRelightState() {
        SodiumRelightResidentState state = this.metallum$residentRelightState;
        return state == null ? null : state.acquire();
    }

    @Override
    public void metallum$replaceRelightState(@Nullable final SodiumRelightResidentState state) {
        SodiumRelightResidentState previous = this.metallum$residentRelightState;
        if (previous == state) {
            return;
        }
        this.metallum$residentRelightState = state;
        if (previous != null) {
            previous.close();
        }
    }

    @Override
    public SodiumRelightPlanCache.@Nullable Lease metallum$acquireRelightPlan() {
        SodiumRelightResidentState state = this.metallum$residentRelightState;
        return state == null ? null : state.acquirePlan();
    }

    @Override
    public void metallum$replaceRelightPlan(@Nullable final SodiumRelightPlanCache.Owner owner) {
        if (owner == null) {
            this.metallum$replaceRelightState(null);
            return;
        }
        BuiltSectionInfo info = this.metallum$latestBuiltSectionInfo;
        if (info == null) {
            owner.close();
            this.metallum$replaceRelightState(null);
            metallum$recordOracleError();
            return;
        }
        // The current publisher supplies only an Owner. Generation zero preserves its legacy
        // contract until the publisher constructs an explicit state from one output generation.
        this.metallum$replaceRelightState(new SodiumRelightResidentState(
                owner,
                info,
                SodiumRelightResidentState.LEGACY_GENERATION
        ));
    }

    @Override
    public void metallum$clearRelightPlan() {
        this.metallum$replaceRelightState(null);
    }

    @Inject(
            method = "setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)I",
            at = @At("RETURN")
    )
    private void metallum$captureBuiltSectionInfo(
            final BuiltSectionInfo info,
            final CallbackInfoReturnable<Integer> cir
    ) {
        this.metallum$latestBuiltSectionInfo = info;
    }

    @Inject(method = "delete()V", at = @At("HEAD"))
    private void metallum$releaseRelightState(final CallbackInfo ci) {
        this.metallum$latestBuiltSectionInfo = null;
        try {
            this.metallum$clearRelightPlan();
        } catch (Throwable ignored) {
            metallum$recordOracleError();
        }
    }

    @Unique
    private static void metallum$recordOracleError() {
        try {
            SodiumRelightOracle.recordError();
        } catch (Throwable ignored) {
            // The diagnostic must never interfere with Sodium's deletion path.
        }
    }
}
