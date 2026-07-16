package com.metallum.client.lighting;

import org.jspecify.annotations.Nullable;

/** Owns a static-light candidate until the Sodium output is accepted or destroyed. */
public interface AdvancedLightCandidateSlot {
    void metallum$setAdvancedLightCandidate(@Nullable LightSectionCandidate candidate);

    @Nullable
    LightSectionCandidate metallum$takeAdvancedLightCandidate();

    void metallum$discardAdvancedLightCandidate();
}
