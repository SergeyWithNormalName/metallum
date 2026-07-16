package com.metallum.client.lighting;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Immutable worker result owned by one Sodium output until accepted or destroyed. */
public final class LightSectionCandidate {
    private static final int OPEN = 0;
    private static final int CLAIMED = 1;
    private static final int DISCARDED = 2;

    public record Entry(int localIndex, AdvancedLight light) {
        public Entry {
            if (localIndex < 0 || localIndex >= StaticLightSectionScanner.BLOCKS_PER_SECTION) {
                throw new IllegalArgumentException("localIndex is outside a 16^3 section");
            }
            if (light == null) {
                throw new NullPointerException("light");
            }
            if (light.kind() != LightSourceKind.BLOCK) {
                throw new IllegalArgumentException("Static section candidates may contain only block lights");
            }
        }
    }

    private final LightSectionTask task;
    private final List<Entry> entries;
    private final int scannedStateCount;
    private final int emittedLightCount;
    private final AtomicInteger ownership = new AtomicInteger(OPEN);

    public LightSectionCandidate(
            final LightSectionTask task,
            final List<Entry> entries,
            final int scannedStateCount,
            final int emittedLightCount
    ) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (scannedStateCount != StaticLightSectionScanner.BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("A full static scan must visit exactly 4096 states");
        }
        if (emittedLightCount < entries.size()) {
            throw new IllegalArgumentException("emittedLightCount cannot be smaller than retained entries");
        }
        this.task = task;
        this.entries = List.copyOf(entries);
        this.scannedStateCount = scannedStateCount;
        this.emittedLightCount = emittedLightCount;
    }

    public LightSectionTask task() {
        return this.task;
    }

    public List<Entry> entries() {
        return this.entries;
    }

    public int scannedStateCount() {
        return this.scannedStateCount;
    }

    public int emittedLightCount() {
        return this.emittedLightCount;
    }

    public int droppedLightCount() {
        return this.emittedLightCount - this.entries.size();
    }

    public boolean isOpen() {
        return this.ownership.get() == OPEN;
    }

    boolean claimForPublication() {
        return this.ownership.compareAndSet(OPEN, CLAIMED);
    }

    public boolean discard() {
        return this.ownership.compareAndSet(OPEN, DISCARDED);
    }
}
