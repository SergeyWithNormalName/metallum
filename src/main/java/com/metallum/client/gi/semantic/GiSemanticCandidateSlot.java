package com.metallum.client.gi.semantic;

import org.jspecify.annotations.Nullable;

public interface GiSemanticCandidateSlot {
    void metallum$setGiSemanticCandidate(@Nullable GiSemanticSectionCandidate candidate);

    @Nullable
    GiSemanticSectionCandidate metallum$takeGiSemanticCandidate();

    void metallum$discardGiSemanticCandidate();
}
