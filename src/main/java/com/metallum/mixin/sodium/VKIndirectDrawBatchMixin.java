package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumIndexedIndirectBatchAccess;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.VKIndirectDrawBatch;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Carries one submit-local immutable Metal snapshot without changing Sodium's batch contents. */
@Mixin(value = VKIndirectDrawBatch.class, remap = false)
abstract class VKIndirectDrawBatchMixin implements SodiumIndexedIndirectBatchAccess {
    @Shadow
    @Final
    private long pCommands;

    @Unique
    private long metallum$preparedSubmitIndex = Long.MIN_VALUE;
    @Unique
    private long metallum$preparedRenderInvocationEpoch = Long.MIN_VALUE;
    @Unique
    private @Nullable GpuBufferSlice metallum$preparedSnapshot;

    @Override
    public long metallum$commandAddress() {
        return this.pCommands;
    }

    @Override
    public void metallum$setPreparedSnapshot(
            final long submitIndex,
            final long renderInvocationEpoch,
            final GpuBufferSlice snapshot
    ) {
        this.metallum$preparedSubmitIndex = submitIndex;
        this.metallum$preparedRenderInvocationEpoch = renderInvocationEpoch;
        this.metallum$preparedSnapshot = snapshot;
    }

    @Override
    public @Nullable GpuBufferSlice metallum$takePreparedSnapshot(
            final long submitIndex,
            final long renderInvocationEpoch
    ) {
        if (this.metallum$preparedSubmitIndex != submitIndex
                || this.metallum$preparedRenderInvocationEpoch != renderInvocationEpoch) {
            return null;
        }
        GpuBufferSlice snapshot = this.metallum$preparedSnapshot;
        this.metallum$preparedSubmitIndex = Long.MIN_VALUE;
        this.metallum$preparedRenderInvocationEpoch = Long.MIN_VALUE;
        this.metallum$preparedSnapshot = null;
        return snapshot;
    }
}
