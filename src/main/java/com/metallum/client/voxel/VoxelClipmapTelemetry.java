package com.metallum.client.voxel;

/** Lifecycle and bounded-work counters for the L5 producer-only controller. */
public record VoxelClipmapTelemetry(
        long worldTransitions,
        long resourceReloads,
        long sectionTasksStarted,
        long sectionCandidatesEncoded,
        long sectionStatesScanned,
        long acceptedPublications,
        long stalePublications,
        long discardedCandidates,
        long blockInvalidations,
        long sectionUnloads,
        long cameraScrolls,
        long scrollSlabsScheduled,
        long queueRejected,
        long batchesLeased,
        long batchesCompleted,
        long busyRetries,
        long staleBatches,
        long deferredWithoutAcceptedGeometry,
        int trackedSections,
        int inFlightBatches,
        VoxelDirtyQueue.Telemetry dirtyQueue
) {
}
