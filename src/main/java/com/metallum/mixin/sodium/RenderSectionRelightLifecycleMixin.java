package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRelightPlanCache;
import com.metallum.client.sodium.SodiumRelightOracle;
import com.metallum.client.sodium.SodiumRelightResidentPlanSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSection.class, remap = false)
abstract class RenderSectionRelightLifecycleMixin implements SodiumRelightResidentPlanSlot {
    @Unique
    @Nullable
    private volatile SodiumRelightPlanCache.Owner metallum$residentRelightPlan;

    @Override
    public SodiumRelightPlanCache.@Nullable Lease metallum$acquireRelightPlan() {
        SodiumRelightPlanCache.Owner owner = this.metallum$residentRelightPlan;
        return owner == null ? null : owner.acquire();
    }

    @Override
    public void metallum$replaceRelightPlan(@Nullable final SodiumRelightPlanCache.Owner owner) {
        SodiumRelightPlanCache.Owner previous = this.metallum$residentRelightPlan;
        if (previous == owner) {
            return;
        }
        this.metallum$residentRelightPlan = owner;
        if (previous != null) {
            previous.close();
        }
    }

    @Override
    public void metallum$clearRelightPlan() {
        this.metallum$replaceRelightPlan(null);
    }

    @Inject(method = "delete()V", at = @At("HEAD"))
    private void metallum$releaseRelightState(final CallbackInfo ci) {
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
