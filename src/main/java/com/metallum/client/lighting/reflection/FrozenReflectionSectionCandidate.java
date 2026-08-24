package com.metallum.client.lighting.reflection;

import com.metallum.client.radiance.CompactSectionPayload;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Worker-owned result retained by exactly one Sodium output until accepted upload. */
public final class FrozenReflectionSectionCandidate {
    private static final int OPEN = 0;
    private static final int CLAIMED = 1;
    private static final int DISCARDED = 2;

    private final FrozenReflectionSectionTask task;
    private final CompactSectionPayload payload;
    private final AtomicInteger ownership = new AtomicInteger(OPEN);

    public FrozenReflectionSectionCandidate(
            final FrozenReflectionSectionTask task,
            final CompactSectionPayload payload
    ) {
        this.task = Objects.requireNonNull(task, "task");
        this.payload = Objects.requireNonNull(payload, "payload");
        if (payload.worldGeneration() != task.worldGeneration()
                || payload.sectionKey() != task.sectionKey()) {
            throw new IllegalArgumentException("Reflection payload provenance does not match its task");
        }
    }

    public FrozenReflectionSectionTask task() {
        return this.task;
    }

    public CompactSectionPayload payload() {
        return this.payload;
    }

    boolean claimForPublication() {
        return this.ownership.compareAndSet(OPEN, CLAIMED);
    }

    boolean discard() {
        return this.ownership.compareAndSet(OPEN, DISCARDED);
    }
}
