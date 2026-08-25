package com.metallum.client.sodium;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.jspecify.annotations.Nullable;

/** Metal-only access to Sodium's finalized CPU indirect commands and their owned GPU snapshot. */
public interface SodiumIndexedIndirectBatchAccess {
    long metallum$commandAddress();

    void metallum$setPreparedSnapshot(long submitIndex, long renderInvocationEpoch, GpuBufferSlice snapshot);

    @Nullable GpuBufferSlice metallum$takePreparedSnapshot(long submitIndex, long renderInvocationEpoch);
}
