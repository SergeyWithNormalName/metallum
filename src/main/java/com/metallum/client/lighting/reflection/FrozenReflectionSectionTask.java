package com.metallum.client.lighting.reflection;

/**
 * One immutable stamp for an accepted-Sodium snapshot which belongs to the single frozen
 * reflection domain.  It deliberately has no revision reuse: a frozen build either receives
 * the exact generation it requested or becomes invalid.
 */
public record FrozenReflectionSectionTask(long worldGeneration, long fieldGeneration, long sectionKey) {
    public FrozenReflectionSectionTask {
        if (worldGeneration <= 0L || fieldGeneration <= 0L) {
            throw new IllegalArgumentException("Reflection generations must be positive");
        }
    }
}
