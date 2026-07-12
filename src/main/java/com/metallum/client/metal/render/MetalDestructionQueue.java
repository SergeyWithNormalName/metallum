package com.metallum.client.metal.render;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalDestructionQueue {
    private final List<Runnable>[] queues;
    private List<Runnable> spareQueue = new ArrayList<>();
    private int currentQueueIndex;

    @SuppressWarnings("unchecked")
    MetalDestructionQueue(final int queueCount) {
        this.queues = (List<Runnable>[]) new List<?>[queueCount];
        for (int i = 0; i < queueCount; i++) {
            this.queues[i] = new ArrayList<>();
        }
    }

    void add(final Runnable destroyAction) {
        if (destroyAction == null) {
            return;
        }
        this.queues[this.currentQueueIndex].add(destroyAction);
    }

    void rotate() {
        this.currentQueueIndex = (this.currentQueueIndex + 1) % this.queues.length;
        List<Runnable> toDestroy = this.queues[this.currentQueueIndex];
        List<Runnable> replacement = this.spareQueue;
        this.spareQueue = null;
        if (replacement == null) {
            // rotate() is not expected from a destruction callback, but keep
            // that rare reentrant path correct without aliasing queue slots.
            replacement = new ArrayList<>();
        }
        this.queues[this.currentQueueIndex] = replacement;
        try {
            for (Runnable destroyAction : toDestroy) {
                try {
                    destroyAction.run();
                } catch (Exception e) {
                    Metallum.LOGGER.error("[metallum] Destroy action threw an exception; resource may have leaked", e);
                }
            }
        } finally {
            toDestroy.clear();
            if (this.spareQueue == null) {
                this.spareQueue = toDestroy;
            }
        }
    }

    void close() {
        for (int i = 0; i < this.queues.length; i++) {
            this.rotate();
        }
    }
}
