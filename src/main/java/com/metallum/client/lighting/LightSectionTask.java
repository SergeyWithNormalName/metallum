package com.metallum.client.lighting;

/** Snapshot identity captured after Sodium has cloned one full section task. */
public record LightSectionTask(
        LightWorldToken world,
        long sectionKey,
        long baseEpoch,
        long ownerToken
) {
    public LightSectionTask {
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (baseEpoch <= 0L || ownerToken <= 0L) {
            throw new IllegalArgumentException("Task epochs and owner tokens must be positive");
        }
    }
}
