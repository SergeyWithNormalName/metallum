package com.metallum.client.gi.live;

import com.metallum.client.gi.source.GiDirectSourceLayout;

import java.util.Arrays;
import java.util.Objects;

/**
 * Fixed-storage G6 dirty scheduler. Its drain allowance advances with source ticks, never with
 * the number of render/presentation calls made during one tick.
 */
public final class GiLiveDirtyScheduler {
    public static final int DEFAULT_CAPACITY = GiDirectSourceLayout.TOTAL_BRICKS;
    public static final int DEFAULT_MAX_DRAIN_PER_TICK =
            GiDirectSourceLayout.MAX_DRAIN_PER_FRAME;

    public enum OfferResult { ENQUEUED, COALESCED, CAPACITY, STALE_EPOCH }

    public enum CompletionResult { COMPLETED, STALE_EPOCH }

    public record Telemetry(
            long queued,
            long coalesced,
            long completed,
            long discarded,
            long capacityRejected,
            long staleRejected,
            int pending,
            int inFlight,
            int owned,
            long activeEpochVersion,
            long lastSourceTick
    ) {
        public boolean algebraIsExact() {
            return this.queued == this.completed + this.discarded + this.owned;
        }
    }

    private final int capacity;
    private final int maxDrainPerTick;
    private final boolean[] queued = new boolean[GiDirectSourceLayout.TOTAL_BRICKS];
    private final boolean[] inFlight = new boolean[GiDirectSourceLayout.TOTAL_BRICKS];
    private final long[] enqueueTick = new long[GiDirectSourceLayout.TOTAL_BRICKS];
    private final GiLiveUpdateClass[] updateClass =
            new GiLiveUpdateClass[GiDirectSourceLayout.TOTAL_BRICKS];

    private GiLiveEpoch activeEpoch;
    private long lastSourceTick = -1L;
    private long drainBudgetTick = -1L;
    private int drainedThisTick;
    private int pendingCount;
    private int inFlightCount;
    private int ownedCount;
    private long queuedTotal;
    private long coalescedTotal;
    private long completedTotal;
    private long discardedTotal;
    private long capacityRejectedTotal;
    private long staleRejectedTotal;

    public GiLiveDirtyScheduler() {
        this(DEFAULT_CAPACITY, DEFAULT_MAX_DRAIN_PER_TICK);
    }

    public GiLiveDirtyScheduler(final int capacity, final int maxDrainPerTick) {
        if (capacity <= 0 || capacity > GiDirectSourceLayout.TOTAL_BRICKS) {
            throw new IllegalArgumentException("G6 dirty capacity is outside fixed topology");
        }
        if (maxDrainPerTick <= 0 || maxDrainPerTick > capacity) {
            throw new IllegalArgumentException("G6 drain bound is outside queue capacity");
        }
        this.capacity = capacity;
        this.maxDrainPerTick = maxDrainPerTick;
    }

    /** Discards pending and native-in-flight ownership from the superseded identity. */
    public boolean rotateEpoch(final GiLiveEpoch next) {
        Objects.requireNonNull(next, "next");
        if (next.equals(this.activeEpoch)) {
            return false;
        }
        if (this.activeEpoch != null) {
            next.requireStrictlyNewerThan(this.activeEpoch);
            this.discardedTotal = Math.addExact(this.discardedTotal, this.ownedCount);
        }
        Arrays.fill(this.queued, false);
        Arrays.fill(this.inFlight, false);
        Arrays.fill(this.updateClass, null);
        this.pendingCount = 0;
        this.inFlightCount = 0;
        this.ownedCount = 0;
        this.activeEpoch = next;
        return true;
    }

    public OfferResult enqueue(
            final GiLiveEpoch expected,
            final int brickId,
            final GiLiveUpdateClass classification,
            final long sourceTick
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(classification, "classification");
        GiDirectSourceLayout.validateBrickId(brickId);
        advanceSourceTick(sourceTick);
        if (!expected.equals(this.activeEpoch)) {
            this.staleRejectedTotal = Math.incrementExact(this.staleRejectedTotal);
            return OfferResult.STALE_EPOCH;
        }
        if (this.queued[brickId] || this.inFlight[brickId]) {
            this.updateClass[brickId] = GiLiveUpdateClass.merge(
                    this.updateClass[brickId], classification
            );
            this.coalescedTotal = Math.incrementExact(this.coalescedTotal);
            return OfferResult.COALESCED;
        }
        if (this.ownedCount >= this.capacity) {
            this.capacityRejectedTotal = Math.incrementExact(this.capacityRejectedTotal);
            return OfferResult.CAPACITY;
        }
        this.queued[brickId] = true;
        this.enqueueTick[brickId] = sourceTick;
        this.updateClass[brickId] = classification;
        this.pendingCount++;
        this.ownedCount++;
        this.queuedTotal = Math.incrementExact(this.queuedTotal);
        return OfferResult.ENQUEUED;
    }

    public void enqueueAll(
            final GiLiveEpoch expected,
            final GiLiveUpdateClass classification,
            final long sourceTick
    ) {
        for (int brickId = 0; brickId < GiDirectSourceLayout.TOTAL_BRICKS; brickId++) {
            enqueue(expected, brickId, classification, sourceTick);
        }
    }

    /**
     * Moves work into caller-owned arrays. Repeated calls on the same source tick share one drain
     * allowance, so additional presented frames cannot accelerate convergence.
     */
    public int drainTo(
            final GiLiveEpoch expected,
            final long sourceTick,
            final int[] destinationBrickIds,
            final GiLiveUpdateClass[] destinationClassifications
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(destinationBrickIds, "destinationBrickIds");
        Objects.requireNonNull(destinationClassifications, "destinationClassifications");
        if (destinationClassifications.length < destinationBrickIds.length) {
            throw new IllegalArgumentException("G6 classification destination is too small");
        }
        advanceSourceTick(sourceTick);
        if (!expected.equals(this.activeEpoch)) {
            return 0;
        }
        if (this.drainBudgetTick != sourceTick) {
            this.drainBudgetTick = sourceTick;
            this.drainedThisTick = 0;
        }
        int remainingBudget = this.maxDrainPerTick - this.drainedThisTick;
        int limit = Math.min(remainingBudget, destinationBrickIds.length);
        int count = 0;
        while (count < limit) {
            int selected = selectNext();
            if (selected < 0) break;
            this.queued[selected] = false;
            this.inFlight[selected] = true;
            this.pendingCount--;
            this.inFlightCount++;
            destinationBrickIds[count] = selected;
            destinationClassifications[count] = this.updateClass[selected];
            count++;
        }
        this.drainedThisTick += count;
        return count;
    }

    /** Atomically validates and completes a caller-owned native batch. */
    public CompletionResult completeBatch(
            final GiLiveEpoch expected,
            final int[] brickIds,
            final int count
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(brickIds, "brickIds");
        validateBatchCount(brickIds, count);
        if (!expected.equals(this.activeEpoch)) {
            return CompletionResult.STALE_EPOCH;
        }
        validateDistinctInFlight(brickIds, count);
        for (int index = 0; index < count; index++) {
            int brickId = brickIds[index];
            this.inFlight[brickId] = false;
            this.updateClass[brickId] = null;
        }
        this.inFlightCount -= count;
        this.ownedCount -= count;
        this.completedTotal = Math.addExact(this.completedTotal, count);
        return CompletionResult.COMPLETED;
    }

    /** Requeues a transient native rejection without granting another same-tick drain budget. */
    public CompletionResult retryBatch(
            final GiLiveEpoch expected,
            final int[] brickIds,
            final int count
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(brickIds, "brickIds");
        validateBatchCount(brickIds, count);
        if (!expected.equals(this.activeEpoch)) {
            return CompletionResult.STALE_EPOCH;
        }
        validateDistinctInFlight(brickIds, count);
        for (int index = 0; index < count; index++) {
            int brickId = brickIds[index];
            this.inFlight[brickId] = false;
            this.queued[brickId] = true;
        }
        this.inFlightCount -= count;
        this.pendingCount += count;
        return CompletionResult.COMPLETED;
    }

    public Telemetry telemetry() {
        return new Telemetry(
                this.queuedTotal, this.coalescedTotal, this.completedTotal,
                this.discardedTotal, this.capacityRejectedTotal,
                this.staleRejectedTotal, this.pendingCount, this.inFlightCount,
                this.ownedCount, this.activeEpoch == null ? 0L : this.activeEpoch.version(),
                this.lastSourceTick
        );
    }

    /** Allocation-free hot-path ownership counters. */
    public int pendingCount() { return this.pendingCount; }
    public int inFlightCount() { return this.inFlightCount; }
    public int ownedCount() { return this.ownedCount; }
    public long queuedTotal() { return this.queuedTotal; }
    public long completedTotal() { return this.completedTotal; }
    public long discardedTotal() { return this.discardedTotal; }
    public boolean algebraIsExact() {
        return this.queuedTotal == this.completedTotal + this.discardedTotal + this.ownedCount;
    }

    private int selectNext() {
        int selected = -1;
        for (int brickId = 0; brickId < this.queued.length; brickId++) {
            if (!this.queued[brickId]) continue;
            if (selected < 0 || this.enqueueTick[brickId] < this.enqueueTick[selected]
                    || (this.enqueueTick[brickId] == this.enqueueTick[selected]
                    && brickId < selected)) {
                selected = brickId;
            }
        }
        return selected;
    }

    private void advanceSourceTick(final long sourceTick) {
        if (sourceTick < 0L || sourceTick < this.lastSourceTick) {
            throw new IllegalArgumentException("G6 source tick cannot move backwards");
        }
        this.lastSourceTick = sourceTick;
    }

    private void validateBatchCount(final int[] brickIds, final int count) {
        if (count < 0 || count > brickIds.length || count > this.maxDrainPerTick) {
            throw new IllegalArgumentException("G6 completion exceeds its fixed drain bound");
        }
    }

    private void validateDistinctInFlight(final int[] brickIds, final int count) {
        for (int index = 0; index < count; index++) {
            int brickId = brickIds[index];
            GiDirectSourceLayout.validateBrickId(brickId);
            if (!this.inFlight[brickId]) {
                throw new IllegalArgumentException("G6 completion does not own the brick");
            }
            for (int prior = 0; prior < index; prior++) {
                if (brickIds[prior] == brickId) {
                    throw new IllegalArgumentException("G6 completion repeats a brick");
                }
            }
        }
    }
}
