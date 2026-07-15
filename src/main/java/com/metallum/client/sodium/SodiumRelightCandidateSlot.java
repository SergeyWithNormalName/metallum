package com.metallum.client.sodium;

import org.jspecify.annotations.Nullable;

/** Owns a relight plan until its chunk build output is accepted or destroyed. */
public interface SodiumRelightCandidateSlot {
    void metallum$setRelightCandidate(@Nullable SodiumRelightPlanCache.Owner candidate);

    /** Transfers candidate ownership to the caller without closing it. */
    @Nullable
    SodiumRelightPlanCache.Owner metallum$takeRelightCandidate();

    void metallum$discardRelightCandidate();
}
