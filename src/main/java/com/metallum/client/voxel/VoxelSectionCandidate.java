package com.metallum.client.voxel;

import java.util.concurrent.atomic.AtomicInteger;

/** Worker-owned voxel result retained by exactly one Sodium output until upload acceptance. */
public final class VoxelSectionCandidate {
    private static final int OPEN = 0;
    private static final int CLAIMED = 1;
    private static final int DISCARDED = 2;

    private final VoxelSectionTask task;
    private final VoxelSectionSnapshot snapshot;
    private final int scannedStateCount;
    private final AtomicInteger ownership = new AtomicInteger(OPEN);

    public VoxelSectionCandidate(
            final VoxelSectionTask task,
            final VoxelSectionSnapshot snapshot,
            final int scannedStateCount
    ) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (snapshot == null) {
            throw new NullPointerException("snapshot");
        }
        if (scannedStateCount != 0 && scannedStateCount != VoxelSectionSnapshot.BLOCK_COUNT) {
            throw new IllegalArgumentException(
                    "Voxel section candidate must be an authoritative empty result or scan exactly 4096 states"
            );
        }
        this.task = task;
        this.snapshot = snapshot;
        this.scannedStateCount = scannedStateCount;
    }

    /** Creates the accepted empty-section fast-path result without a redundant 4096-state scan. */
    public static VoxelSectionCandidate empty(final VoxelSectionTask task) {
        return new VoxelSectionCandidate(task, VoxelSectionSnapshot.empty(), 0);
    }

    public VoxelSectionTask task() {
        return this.task;
    }

    public VoxelSectionSnapshot snapshot() {
        return this.snapshot;
    }

    public int scannedStateCount() {
        return this.scannedStateCount;
    }

    boolean claimForPublication() {
        return this.ownership.compareAndSet(OPEN, CLAIMED);
    }

    public boolean discard() {
        return this.ownership.compareAndSet(OPEN, DISCARDED);
    }
}
