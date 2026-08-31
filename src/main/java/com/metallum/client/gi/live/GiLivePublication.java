package com.metallum.client.gi.live;

import java.util.Objects;

/** Versioned CPU publication gate preventing incompatible live GI from reaching a submit. */
public final class GiLivePublication {
    public enum CoverageState {
        /** No prior cell is compatible with the active world/reset identity. */
        ZERO,
        /** Only cells independently proven exact by logical coordinate/content stamps may survive. */
        EXACT_VALID_ONLY,
        /** The complete compatible field generation is available to the receiver. */
        READY
    }

    public enum BeginResult { STARTED, SAME_EPOCH }

    public enum PublishResult {
        PUBLISHED,
        ALREADY_READY,
        STALE_EPOCH,
        FIRST_SUBMIT_MUST_BE_ZERO
    }

    public record Snapshot(
            long publicationVersion,
            GiLiveEpoch epoch,
            CoverageState coverage,
            GiLiveUpdateClass classification,
            long updateSubmitIndex,
            long publicationSubmitIndex
    ) {
        public boolean receiverReady() {
            return this.coverage == CoverageState.READY;
        }
    }

    private volatile Snapshot snapshot = new Snapshot(
            0L, null, CoverageState.ZERO, GiLiveUpdateClass.FULL_RESET, -1L, -1L
    );
    private long lastSubmitIndex = -1L;

    public synchronized BeginResult beginEpoch(
            final GiLiveEpoch next,
            final GiLiveUpdateClass classification,
            final long firstAffectedSubmitIndex
    ) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(classification, "classification");
        advanceSubmit(firstAffectedSubmitIndex);
        Snapshot current = this.snapshot;
        if (next.equals(current.epoch())) {
            return BeginResult.SAME_EPOCH;
        }
        if (current.epoch() != null) {
            next.requireStrictlyNewerThan(current.epoch());
        }
        CoverageState coverage = classification.requiresFirstSubmitZero()
                ? CoverageState.ZERO : CoverageState.EXACT_VALID_ONLY;
        this.snapshot = new Snapshot(
                Math.incrementExact(current.publicationVersion()), next, coverage,
                classification, firstAffectedSubmitIndex, firstAffectedSubmitIndex
        );
        return BeginResult.STARTED;
    }

    public synchronized PublishResult publishReady(
            final GiLiveEpoch expected,
            final long submitIndex
    ) {
        Objects.requireNonNull(expected, "expected");
        advanceSubmit(submitIndex);
        Snapshot current = this.snapshot;
        if (!expected.equals(current.epoch())) {
            return PublishResult.STALE_EPOCH;
        }
        if (current.coverage() == CoverageState.READY) {
            return PublishResult.ALREADY_READY;
        }
        if (current.classification().requiresFirstSubmitZero()
                && submitIndex <= current.updateSubmitIndex()) {
            return PublishResult.FIRST_SUBMIT_MUST_BE_ZERO;
        }
        this.snapshot = new Snapshot(
                Math.incrementExact(current.publicationVersion()), expected,
                CoverageState.READY, current.classification(),
                current.updateSubmitIndex(), submitIndex
        );
        return PublishResult.PUBLISHED;
    }

    public Snapshot snapshot() {
        return this.snapshot;
    }

    private void advanceSubmit(final long submitIndex) {
        if (submitIndex < 0L || submitIndex < this.lastSubmitIndex) {
            throw new IllegalArgumentException("G6 submit index cannot move backwards");
        }
        this.lastSubmitIndex = submitIndex;
    }
}
