package com.metallum.client.gi.field;

import java.util.Objects;

/** Render-thread-owned generation/reset gate; stale worker results never become field truth. */
public final class GiFieldGenerationState {
    public enum ResetReason {
        INITIALIZE,
        WORLD_CHANGE,
        DIMENSION_CHANGE,
        TELEPORT,
        RESOURCE_RELOAD
    }

    public record Snapshot(long generation, String dimension, ResetReason lastResetReason,
                           long acceptedCandidates, long staleCandidates) {
    }

    private final Thread ownerThread;
    private long generation;
    private String dimension;
    private ResetReason lastResetReason;
    private long acceptedCandidates;
    private long staleCandidates;

    public GiFieldGenerationState(final long generation, final String dimension) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("G1 world generation must be positive");
        }
        this.ownerThread = Thread.currentThread();
        this.generation = generation;
        this.dimension = requireDimension(dimension);
        this.lastResetReason = ResetReason.INITIALIZE;
    }

    public boolean admitCandidate(final long candidateGeneration, final String candidateDimension) {
        assertOwnerThread();
        if (candidateGeneration != this.generation || !this.dimension.equals(candidateDimension)) {
            this.staleCandidates++;
            return false;
        }
        this.acceptedCandidates++;
        return true;
    }

    public long reset(final String dimension, final ResetReason reason) {
        assertOwnerThread();
        String nextDimension = requireDimension(dimension);
        ResetReason nextReason = Objects.requireNonNull(reason, "reason");
        this.generation = Math.incrementExact(this.generation);
        this.dimension = nextDimension;
        this.lastResetReason = nextReason;
        return this.generation;
    }

    public Snapshot snapshot() {
        assertOwnerThread();
        return new Snapshot(
                this.generation,
                this.dimension,
                this.lastResetReason,
                this.acceptedCandidates,
                this.staleCandidates
        );
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G1 generation state is confined to its render thread");
        }
    }

    private static String requireDimension(final String dimension) {
        String value = Objects.requireNonNull(dimension, "dimension");
        if (value.isBlank()) {
            throw new IllegalArgumentException("G1 dimension must not be blank");
        }
        return value;
    }
}
