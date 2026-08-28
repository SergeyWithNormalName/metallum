package com.metallum.client.sodium;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.jspecify.annotations.Nullable;

/** Metal-only access to Sodium's finalized CPU indirect commands and their owned GPU snapshot. */
public interface SodiumIndexedIndirectBatchAccess {
    long metallum$commandAddress();

    void metallum$resetDrawnG5CarrierSlices();

    void metallum$addDrawnG5CarrierSlices(int drawnSlices);

    long metallum$getDrawnG5CarrierSlices();

    void metallum$setPreparedSnapshot(
            long submitIndex,
            long renderInvocationEpoch,
            long drawnG5CarrierSlices,
            GpuBufferSlice snapshot
    );

    long metallum$getPreparedDrawnG5CarrierSlices(long submitIndex, long renderInvocationEpoch);

    @Nullable GpuBufferSlice metallum$takePreparedSnapshot(long submitIndex, long renderInvocationEpoch);
}
