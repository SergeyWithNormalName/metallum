package com.metallum.client.sodium;

/** Exact-version bridge used while constructing one region's shadow caster list. */
public interface SodiumShadowBatchAccess {
    void metallum$prepareShadowBatch(
            long cascadeToken,
            long sections0,
            long sections1,
            long sections2,
            long sections3
    );
}
