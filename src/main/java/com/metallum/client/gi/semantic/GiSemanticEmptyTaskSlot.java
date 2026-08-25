package com.metallum.client.gi.semantic;

import org.jspecify.annotations.Nullable;

public interface GiSemanticEmptyTaskSlot {
    void metallum$setEmptyGiSemanticTask(GiSemanticSectionTask task);

    @Nullable
    GiSemanticSectionTask metallum$claimEmptyGiSemanticTask();
}
