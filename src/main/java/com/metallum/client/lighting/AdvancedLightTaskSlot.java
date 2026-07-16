package com.metallum.client.lighting;

import org.jspecify.annotations.Nullable;

/** One-shot task stamp attached after Sodium has cloned the section context. */
public interface AdvancedLightTaskSlot {
    void metallum$setAdvancedLightTask(LightSectionTask task);

    @Nullable
    LightSectionTask metallum$claimAdvancedLightTask();
}
