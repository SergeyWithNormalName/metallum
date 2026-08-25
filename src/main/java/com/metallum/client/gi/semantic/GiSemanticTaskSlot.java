package com.metallum.client.gi.semantic;

import org.jspecify.annotations.Nullable;

public interface GiSemanticTaskSlot {
    void metallum$setGiSemanticTask(GiSemanticSectionTask task);

    @Nullable
    GiSemanticSectionTask metallum$claimGiSemanticTask();
}
