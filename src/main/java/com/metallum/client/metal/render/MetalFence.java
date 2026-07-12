package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuFence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalFence implements GpuFence {
    private final MetalCommandEncoder encoder;
    private final long submitIndex;
    private boolean closed;

    MetalFence(final MetalCommandEncoder encoder, final long submitIndex) {
        this.encoder = encoder;
        this.submitIndex = submitIndex;
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public boolean awaitCompletion(final long timeoutNS) {
        return this.closed || this.encoder.awaitSubmitCompletion(this.submitIndex, timeoutMillis(timeoutNS));
    }

    static long timeoutMillis(final long timeoutNS) {
        if (timeoutNS <= 0L) {
            return 0L;
        }
        return 1L + (timeoutNS - 1L) / 1_000_000L;
    }
}
