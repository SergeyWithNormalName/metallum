package com.metallum.client.gi.live;

import org.jspecify.annotations.Nullable;

/** Fixed-storage proof and failure budget for one native-accepted asynchronous G6 batch. */
final class GiLiveCompletionTracker {
    static final int MAX_ASYNC_COMPLETION_FAILURES = 3;

    enum State {
        IDLE,
        IN_FLIGHT,
        COMPLETED_CURRENT,
        FAILED_CURRENT,
        STALE_COMPLETION,
        INVALID_COMPLETION
    }

    private boolean pending;
    private int batchCount;
    private int cascade;
    private boolean preparedCascade;
    private boolean remapOnly;
    private long batchMask;
    private long epochVersion;
    private long dispatchBaseline;
    private long rejectBaseline;
    private int consecutiveFailures;

    void admit(
            final int acceptedBatchCount,
            final int acceptedCascade,
            final boolean acceptedPreparedCascade,
            final long acceptedBatchMask,
            final long acceptedEpochVersion,
            final long acceptedDispatchBaseline,
            final long acceptedRejectBaseline
    ) {
        boolean acceptedRemapOnly = acceptedBatchCount == 0;
        if (this.pending
                || acceptedBatchCount < 0
                || acceptedBatchCount > GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                || acceptedCascade < 0 || acceptedCascade >= GiLiveLayout.CASCADE_COUNT
                || Long.bitCount(acceptedBatchMask) != acceptedBatchCount
                || acceptedRemapOnly && !acceptedPreparedCascade
                || acceptedEpochVersion <= 0L
                || acceptedDispatchBaseline < 0L || acceptedRejectBaseline < 0L) {
            throw new IllegalArgumentException("Invalid G6 accepted-batch proof");
        }
        this.pending = true;
        this.batchCount = acceptedBatchCount;
        this.cascade = acceptedCascade;
        this.preparedCascade = acceptedPreparedCascade;
        this.remapOnly = acceptedRemapOnly;
        this.batchMask = acceptedBatchMask;
        this.epochVersion = acceptedEpochVersion;
        this.dispatchBaseline = acceptedDispatchBaseline;
        this.rejectBaseline = acceptedRejectBaseline;
    }

    State classify(
            final @Nullable GiLiveEpoch activeEpoch,
            final boolean buildInFlight,
            final long nativeFieldGeneration,
            final long transportDispatches,
            final long rejectedCount
    ) {
        if (!this.pending) return State.IDLE;
        if (buildInFlight) return State.IN_FLIGHT;
        boolean completed = exactlyOneGreater(transportDispatches, this.dispatchBaseline)
                && rejectedCount == this.rejectBaseline;
        boolean failed = transportDispatches == this.dispatchBaseline
                && exactlyOneGreater(rejectedCount, this.rejectBaseline);
        // Classify the counter receipt before considering rotation. An impossible receipt is a
        // terminal native contract violation even when the accepted epoch has become stale.
        if (!completed && !failed) return State.INVALID_COMPLETION;
        if (activeEpoch == null || activeEpoch.version() != this.epochVersion) {
            return State.STALE_COMPLETION;
        }
        if (nativeFieldGeneration != this.epochVersion) {
            return State.INVALID_COMPLETION;
        }
        return completed ? State.COMPLETED_CURRENT : State.FAILED_CURRENT;
    }

    boolean recordFailure() {
        this.consecutiveFailures = Math.incrementExact(this.consecutiveFailures);
        return this.consecutiveFailures >= MAX_ASYNC_COMPLETION_FAILURES;
    }

    void recordSuccess() {
        this.consecutiveFailures = 0;
    }

    void beginEpoch() {
        this.consecutiveFailures = 0;
    }

    boolean pending() { return this.pending; }
    int batchCount() { return this.batchCount; }
    int cascade() { return this.cascade; }
    boolean preparedCascade() { return this.preparedCascade; }
    boolean remapOnly() { return this.remapOnly; }
    long batchMask() { return this.batchMask; }
    long epochVersion() { return this.epochVersion; }
    int consecutiveFailures() { return this.consecutiveFailures; }

    void clearAccepted() {
        this.pending = false;
        this.batchCount = 0;
        this.cascade = 0;
        this.preparedCascade = false;
        this.remapOnly = false;
        this.batchMask = 0L;
        this.epochVersion = 0L;
        this.dispatchBaseline = 0L;
        this.rejectBaseline = 0L;
    }

    private static boolean exactlyOneGreater(final long value, final long baseline) {
        return baseline >= 0L && baseline != Long.MAX_VALUE && value == baseline + 1L;
    }
}
