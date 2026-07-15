package com.metallum.client.sodium;

import com.metallum.client.metal.render.SodiumLightLegacyPatchBatch;

import java.nio.ByteBuffer;

public interface SodiumLightSidecarArena {
    void metallum$enableLightSidecar();

    /** Queues an exact full-vertex refresh into an existing resident allocation. */
    long metallum$enqueueInPlaceTerrainRefresh(
            ByteBuffer geometry,
            long allocationVertexOffset,
            long allocationVertexCount
    );

    /** Queues only the contiguous two-byte light payload and returns its legacy dual-write command. */
    SodiumLightLegacyPatchBatch.Patch metallum$enqueueInPlaceTerrainLightRefresh(
            ByteBuffer geometry,
            long allocationVertexOffset,
            long allocationVertexCount
    );
}
