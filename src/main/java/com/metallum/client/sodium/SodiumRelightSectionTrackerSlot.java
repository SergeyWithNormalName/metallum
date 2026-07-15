package com.metallum.client.sodium;

/** Render-thread state that coalesces causes before one task is created. */
public interface SodiumRelightSectionTrackerSlot {
    void metallum$recordRelightCause(SodiumRelightRebuildCause cause);

    SodiumRelightTaskStamp metallum$takeRelightTaskStamp(
            int submitTime,
            boolean blockingTask,
            boolean forceSort
    );

    void metallum$discardPendingRelightCause();

    long metallum$getRelightGeometryEpoch();
}
