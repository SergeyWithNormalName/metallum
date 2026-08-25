package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldCandidateBudget;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-byte reservation which transfers its lease exactly once to an immutable candidate. */
public final class GiSemanticSectionReservation implements AutoCloseable {
    private static final int OPEN = 0;
    private static final int TRANSFERRED = 1;
    private static final int CLOSED = 2;

    private final GiSemanticSectionTask task;
    private final GiSemanticPalette palette;
    private final GiFieldCandidateBudget.Lease lease;
    private final AtomicInteger state = new AtomicInteger(OPEN);

    GiSemanticSectionReservation(
            final GiSemanticSectionTask task,
            final GiSemanticPalette palette,
            final GiFieldCandidateBudget.Lease lease
    ) {
        this.task = Objects.requireNonNull(task, "task");
        this.palette = Objects.requireNonNull(palette, "palette");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (task.paletteGeneration() != palette.generation()) {
            throw new IllegalArgumentException("G2 reservation palette differs from task generation");
        }
    }

    public GiSemanticSectionTask task() { return this.task; }
    public GiSemanticPalette palette() { return this.palette; }

    public GiSemanticSectionCandidate complete(
            final GiSemanticSectionSeed seed,
            final List<GiSemanticQuadObservation> observations
    ) {
        Objects.requireNonNull(observations, "observations");
        GiSemanticSectionBuilder builder = new GiSemanticSectionBuilder(this.palette, seed);
        for (GiSemanticQuadObservation observation : List.copyOf(observations)) {
            builder.observe(observation);
        }
        return transfer(builder.build());
    }

    public GiSemanticSectionCandidate authoritativeEmpty() {
        return transfer(GiSemanticSectionSnapshot.empty());
    }

    private GiSemanticSectionCandidate transfer(final GiSemanticSectionSnapshot snapshot) {
        if (!this.state.compareAndSet(OPEN, TRANSFERRED)) {
            throw new IllegalStateException("G2 reservation was already consumed");
        }
        return new GiSemanticSectionCandidate(this.task, snapshot, this.lease);
    }

    @Override
    public void close() {
        if (this.state.compareAndSet(OPEN, CLOSED)) this.lease.close();
    }
}
