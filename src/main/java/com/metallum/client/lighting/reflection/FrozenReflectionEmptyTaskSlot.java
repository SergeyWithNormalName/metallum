package com.metallum.client.lighting.reflection;

import org.jspecify.annotations.Nullable;

/** Exact owner for Sodium's no-worker, authoritative-empty section path. */
public interface FrozenReflectionEmptyTaskSlot {
    void metallum$setEmptyFrozenReflectionTask(FrozenReflectionSectionTask task);

    @Nullable
    FrozenReflectionSectionTask metallum$claimEmptyFrozenReflectionTask();
}
