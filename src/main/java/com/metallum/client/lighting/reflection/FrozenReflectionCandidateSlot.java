package com.metallum.client.lighting.reflection;

import org.jspecify.annotations.Nullable;

/** Owns a frozen-reflection candidate until Sodium accepts or destroys its output. */
public interface FrozenReflectionCandidateSlot {
    void metallum$setFrozenReflectionCandidate(@Nullable FrozenReflectionSectionCandidate candidate);

    @Nullable
    FrozenReflectionSectionCandidate metallum$takeFrozenReflectionCandidate();

    void metallum$discardFrozenReflectionCandidate();
}
