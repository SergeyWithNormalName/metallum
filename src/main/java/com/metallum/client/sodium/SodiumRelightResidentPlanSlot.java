package com.metallum.client.sodium;

import org.jspecify.annotations.Nullable;

/** Owns the plan matching one RenderSection's currently resident terrain. */
public interface SodiumRelightResidentPlanSlot {
    SodiumRelightPlanCache.@Nullable Lease metallum$acquireRelightPlan();

    void metallum$replaceRelightPlan(@Nullable SodiumRelightPlanCache.Owner owner);

    void metallum$clearRelightPlan();
}
