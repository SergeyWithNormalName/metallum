package com.metallum.client.gi.live;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** Fixed-memory submit-latency histograms for the predeclared G6 p95/p99 SLAs. */
public final class GiLiveLatencyTracker {
    public static final int MAX_TRACKED_SUBMITS = 1024;
    private static final int OVERFLOW_BIN = MAX_TRACKED_SUBMITS + 1;

    public record Snapshot(
            GiLiveUpdateClass classification,
            long samples,
            int p95Submits,
            int p99Submits,
            long overflowSamples,
            boolean meetsSla
    ) {
    }

    private final long[][] histograms = new long[GiLiveUpdateClass.values().length][OVERFLOW_BIN + 1];
    private final long[] sampleCounts = new long[GiLiveUpdateClass.values().length];
    @Nullable private GiLiveUpdateClass pendingClassification;
    private long pendingFirstAffectedSubmitIndex = -1L;

    /**
     * Keeps unfinished reset/scroll barriers while cheaper incremental epochs retain
     * last-epoch semantics. A moving held source may publish just after the clipmap origin;
     * that refinement belongs to the same visible scroll recovery and must not erase its SLA.
     */
    public void beginPending(
            final GiLiveUpdateClass classification,
            final long firstAffectedSubmitIndex
    ) {
        Objects.requireNonNull(classification, "classification");
        if (firstAffectedSubmitIndex < 0L) {
            throw new IllegalArgumentException("G6 pending recovery submit is invalid");
        }
        if (this.pendingClassification == null) {
            this.pendingClassification = classification;
            this.pendingFirstAffectedSubmitIndex = firstAffectedSubmitIndex;
            return;
        }
        if (firstAffectedSubmitIndex < this.pendingFirstAffectedSubmitIndex) {
            throw new IllegalArgumentException("G6 pending recovery submit moved backwards");
        }
        if (this.pendingClassification == GiLiveUpdateClass.FULL_RESET) return;
        if (classification == GiLiveUpdateClass.FULL_RESET) {
            this.pendingClassification = classification;
            return;
        }
        if (this.pendingClassification == GiLiveUpdateClass.SCROLL) return;
        this.pendingClassification = classification;
        this.pendingFirstAffectedSubmitIndex = firstAffectedSubmitIndex;
    }

    public boolean hasPending() {
        return this.pendingClassification != null;
    }

    @Nullable
    public GiLiveUpdateClass pendingClassification() {
        return this.pendingClassification;
    }

    public long pendingFirstAffectedSubmitIndex() {
        return this.pendingFirstAffectedSubmitIndex;
    }

    public void recordPendingReady(final long readySubmitIndex) {
        GiLiveUpdateClass classification = this.pendingClassification;
        if (classification == null) {
            throw new IllegalStateException("G6 has no pending recovery to record");
        }
        record(classification, this.pendingFirstAffectedSubmitIndex, readySubmitIndex);
        this.pendingClassification = null;
        this.pendingFirstAffectedSubmitIndex = -1L;
    }

    /** Records recovery as readySubmitIndex - firstAffectedSubmitIndex. */
    public void record(
            final GiLiveUpdateClass classification,
            final long firstAffectedSubmitIndex,
            final long readySubmitIndex
    ) {
        Objects.requireNonNull(classification, "classification");
        if (firstAffectedSubmitIndex < 0L || readySubmitIndex < firstAffectedSubmitIndex) {
            throw new IllegalArgumentException("G6 recovery submit interval is invalid");
        }
        long latency = readySubmitIndex - firstAffectedSubmitIndex;
        int bin = latency > MAX_TRACKED_SUBMITS ? OVERFLOW_BIN : (int) latency;
        int classIndex = classification.ordinal();
        this.histograms[classIndex][bin] = Math.incrementExact(this.histograms[classIndex][bin]);
        this.sampleCounts[classIndex] = Math.incrementExact(this.sampleCounts[classIndex]);
    }

    public Snapshot snapshot(final GiLiveUpdateClass classification) {
        Objects.requireNonNull(classification, "classification");
        int classIndex = classification.ordinal();
        long samples = this.sampleCounts[classIndex];
        int p95 = percentile(classIndex, samples, 95);
        int p99 = percentile(classIndex, samples, 99);
        boolean meets = samples > 0L
                && p95 <= classification.p95SubmitLimit()
                && p99 <= classification.p99SubmitLimit();
        return new Snapshot(
                classification, samples, p95, p99,
                this.histograms[classIndex][OVERFLOW_BIN], meets
        );
    }

    public long sampleCount(final GiLiveUpdateClass classification) {
        Objects.requireNonNull(classification, "classification");
        return this.sampleCounts[classification.ordinal()];
    }

    public int p95Submits(final GiLiveUpdateClass classification) {
        Objects.requireNonNull(classification, "classification");
        int index = classification.ordinal();
        return percentile(index, this.sampleCounts[index], 95);
    }

    public int p99Submits(final GiLiveUpdateClass classification) {
        Objects.requireNonNull(classification, "classification");
        int index = classification.ordinal();
        return percentile(index, this.sampleCounts[index], 99);
    }

    public boolean meetsSla(final GiLiveUpdateClass classification) {
        long samples = sampleCount(classification);
        return samples > 0L
                && p95Submits(classification) <= classification.p95SubmitLimit()
                && p99Submits(classification) <= classification.p99SubmitLimit();
    }

    private int percentile(final int classIndex, final long samples, final int numerator) {
        if (samples == 0L) return -1;
        long wholeHundreds = samples / 100L;
        long remainder = samples % 100L;
        long rank = Math.addExact(
                Math.multiplyExact(wholeHundreds, numerator),
                (remainder * numerator + 99L) / 100L
        );
        long seen = 0L;
        for (int bin = 0; bin <= OVERFLOW_BIN; bin++) {
            seen = Math.addExact(seen, this.histograms[classIndex][bin]);
            if (seen >= rank) return bin;
        }
        throw new IllegalStateException("G6 latency histogram diverged from its sample count");
    }
}
