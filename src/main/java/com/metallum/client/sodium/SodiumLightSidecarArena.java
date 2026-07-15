package com.metallum.client.sodium;

import java.nio.ByteBuffer;

public interface SodiumLightSidecarArena {
    void metallum$enableLightSidecar();

    /** Queues an exact full-vertex refresh into an existing resident allocation. */
    long metallum$enqueueInPlaceTerrainRefresh(
            ByteBuffer geometry,
            long allocationVertexOffset,
            long allocationVertexCount
    );
}
