package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldCandidateBudget;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Immutable worker output whose fixed-budget lease survives until acceptance or discard. */
public final class GiSemanticSectionCandidate implements AutoCloseable {
    private static final int OPEN = 0;
    private static final int CLAIMED = 1;
    private static final int RETIRED = 2;

    private final GiSemanticSectionTask task;
    private final GiSemanticSectionSnapshot snapshot;
    private final GiFieldCandidateBudget.Lease lease;
    private final AtomicInteger ownership = new AtomicInteger(OPEN);

    GiSemanticSectionCandidate(
            final GiSemanticSectionTask task,
            final GiSemanticSectionSnapshot snapshot,
            final GiFieldCandidateBudget.Lease lease
    ) {
        this.task = Objects.requireNonNull(task, "task");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.lease = Objects.requireNonNull(lease, "lease");
    }

    public GiSemanticSectionTask task() { return this.task; }
    public GiSemanticSectionSnapshot snapshot() { return this.snapshot; }

    boolean claimForPublication() {
        return this.ownership.compareAndSet(OPEN, CLAIMED);
    }

    public boolean discard() {
        if (!this.ownership.compareAndSet(OPEN, RETIRED)) return false;
        this.lease.close();
        return true;
    }

    @Override
    public void close() {
        int previous = this.ownership.getAndSet(RETIRED);
        if (previous != RETIRED) this.lease.close();
    }
}
