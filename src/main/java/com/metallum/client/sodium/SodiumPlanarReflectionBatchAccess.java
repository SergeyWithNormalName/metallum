package com.metallum.client.sodium;

/** Exact-version bridge used while constructing one region's planar-reflection list. */
public interface SodiumPlanarReflectionBatchAccess {
    void metallum$preparePlanarReflectionBatch(
            long reflectionToken,
            long sections0,
            long sections1,
            long sections2,
            long sections3
    );
}
