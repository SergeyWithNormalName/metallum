package com.metallum.client.lighting.reflection;

import org.jspecify.annotations.Nullable;

/** One-shot reflection stamp attached to Sodium's existing full-mesh task. */
public interface FrozenReflectionTaskSlot {
    void metallum$setFrozenReflectionTask(FrozenReflectionSectionTask task);

    @Nullable
    FrozenReflectionSectionTask metallum$claimFrozenReflectionTask();
}
