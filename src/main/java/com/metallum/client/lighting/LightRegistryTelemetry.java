package com.metallum.client.lighting;

/** Monotonic counters owned and observed by the Advanced light registry. */
public record LightRegistryTelemetry(
        long worldTransitions,
        long resourceReloads,
        long staticTasksStarted,
        long staticSectionsScanned,
        long staticStatesScanned,
        long acceptedPublications,
        long stalePublications,
        long discardedCandidates,
        long blockOverrides,
        long sectionUnloads,
        long dynamicFrames,
        long dynamicCandidates,
        long residentSectionCapacityDrops,
        long sectionLightOverflows,
        long frameLightOverflows,
        long protectedFrameLightOverflows,
        long registryFailures,
        int residentSections,
        int residentDynamicLights
) {
}
