package com.metallum.client.metal.render;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalDestructionQueue {
    private static final int DEFAULT_MAX_ACTIONS_PER_ROTATION = 256;
    private final List<Runnable>[] queues;
    private final int maxActionsPerRotation;
    private final ArrayDeque<Runnable> readyActions = new ArrayDeque<>();
    private List<Runnable> spareQueue = new ArrayList<>();
    private int currentQueueIndex;

    MetalDestructionQueue(final int queueCount) {
        this(queueCount, DEFAULT_MAX_ACTIONS_PER_ROTATION);
    }

    @SuppressWarnings("unchecked")
    MetalDestructionQueue(final int queueCount, final int maxActionsPerRotation) {
        if (queueCount <= 0) {
            throw new IllegalArgumentException("Destruction queue count must be positive");
        }
        if (maxActionsPerRotation <= 0) {
            throw new IllegalArgumentException("Destruction queue drain budget must be positive");
        }
        this.queues = (List<Runnable>[]) new List<?>[queueCount];
        this.maxActionsPerRotation = maxActionsPerRotation;
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
        this.rotate(this.maxActionsPerRotation);
    }

    private void rotate(final int actionBudget) {
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
            this.readyActions.addAll(toDestroy);
            this.drainReadyActions(actionBudget);
        } finally {
            toDestroy.clear();
            if (this.spareQueue == null) {
                this.spareQueue = toDestroy;
            }
        }
    }

    void close() {
        do {
            for (int i = 0; i < this.queues.length; i++) {
                this.rotate(Integer.MAX_VALUE);
            }
            this.drainReadyActions(Integer.MAX_VALUE);
        } while (!this.readyActions.isEmpty() || this.hasQueuedActions());
    }

    private void drainReadyActions(final int actionBudget) {
        for (int executed = 0; executed < actionBudget; executed++) {
            Runnable destroyAction = this.readyActions.pollFirst();
            if (destroyAction == null) {
                return;
            }
            try {
                destroyAction.run();
            } catch (Exception e) {
                Metallum.LOGGER.error("[metallum] Destroy action threw an exception; resource may have leaked", e);
            }
        }
    }

    private boolean hasQueuedActions() {
        for (List<Runnable> queue : this.queues) {
            if (!queue.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
