package com.metallum.client.sodium;

/** Immutable render-thread decision transferred to one Sodium worker task. */
public record SodiumRelightTaskStamp(
        SodiumRelightRebuildCause cause,
        long geometryEpoch,
        int submitTime,
        boolean blockingTask,
        boolean forceSort,
        boolean isolatedAtCreation
) {
    public SodiumRelightTaskStamp {
        if (cause == null) {
            throw new NullPointerException("cause");
        }
    }
}
