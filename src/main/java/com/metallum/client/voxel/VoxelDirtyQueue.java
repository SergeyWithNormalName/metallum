package com.metallum.client.voxel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Predicate;

/** Owner-thread bounded dirty-brick queue with deterministic coalescing and starvation control. */
public final class VoxelDirtyQueue {
    public enum Priority {
        CRITICAL,
        HIGH,
        NORMAL,
        LOW
    }

    public enum OfferStatus {
        ENQUEUED,
        COALESCED,
        REJECTED
    }

    public enum OverflowReason {
        NONE,
        CAPACITY_REACHED_REQUIRES_FALLBACK
    }

    /** World and clipmap generations make late worker patches unambiguously stale. */
    public record BrickKey(
            long worldGeneration,
            long clipmapGeneration,
            int level,
            long brickX,
            long brickY,
            long brickZ
    ) {
        public BrickKey {
            if (worldGeneration < 0L || clipmapGeneration < 0L || level < 0) {
                throw new IllegalArgumentException("Dirty brick generations and level must be non-negative");
            }
        }
    }

    public record OfferResult(OfferStatus status, OverflowReason overflowReason) {
        public OfferResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(overflowReason, "overflowReason");
            if ((status == OfferStatus.REJECTED) != (overflowReason != OverflowReason.NONE)) {
                throw new IllegalArgumentException("Only a rejected dirty-brick offer may report overflow");
            }
        }
    }

    public record DirtyBrick(
            BrickKey key,
            Priority priority,
            long enqueuedTick,
            long ageTicks,
            long estimatedNanos,
            long enqueueSequence,
            int coalescedUpdates,
            boolean starvationPromoted
    ) {
        public DirtyBrick {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(priority, "priority");
            if (enqueuedTick < 0L || ageTicks < 0L || estimatedNanos <= 0L
                    || enqueueSequence < 0L || coalescedUpdates < 0) {
                throw new IllegalArgumentException("Dirty-brick lease metadata is invalid");
            }
        }
    }

    /** The actual count is the sole dispatch count a future GPU layer may consume. */
    public record Drain(List<DirtyBrick> bricks, int actualCount, long estimatedNanos) {
        public Drain {
            bricks = List.copyOf(Objects.requireNonNull(bricks, "bricks"));
            if (actualCount != bricks.size() || actualCount < 0 || estimatedNanos < 0L) {
                throw new IllegalArgumentException("Dirty-brick drain declaration is inconsistent");
            }
        }
    }

    public record Telemetry(
            int capacity,
            int queuedBricks,
            int highWaterMark,
            int hardDrainBudget,
            OptionalLong estimatedTimeBudgetNanos,
            long starvationBoundTicks,
            long currentTick,
            long oldestAgeTicks,
            long offered,
            long enqueued,
            long coalesced,
            long rejected,
            long processed,
            long discardedStale,
            long starvationPromotions,
            OverflowReason lastOverflowReason
    ) {
        public Telemetry {
            Objects.requireNonNull(estimatedTimeBudgetNanos, "estimatedTimeBudgetNanos");
            Objects.requireNonNull(lastOverflowReason, "lastOverflowReason");
        }
    }

    private static final class PendingBrick {
        private final BrickKey key;
        private final long enqueuedTick;
        private final long enqueueSequence;
        private Priority priority;
        private long estimatedNanos;
        private int coalescedUpdates;

        private PendingBrick(
                final BrickKey key,
                final Priority priority,
                final long estimatedNanos,
                final long enqueuedTick,
                final long enqueueSequence,
                final int coalescedUpdates
        ) {
            this.key = key;
            this.priority = priority;
            this.estimatedNanos = estimatedNanos;
            this.enqueuedTick = enqueuedTick;
            this.enqueueSequence = enqueueSequence;
            this.coalescedUpdates = coalescedUpdates;
        }
    }

    private final int capacity;
    private final int hardDrainBudget;
    private final OptionalLong estimatedTimeBudgetNanos;
    private final long starvationBoundTicks;
    private final Map<BrickKey, PendingBrick> pending = new LinkedHashMap<>();
    private long currentTick;
    private long nextSequence;
    private int highWaterMark;
    private long offered;
    private long enqueued;
    private long coalesced;
    private long rejected;
    private long processed;
    private long discardedStale;
    private long starvationPromotions;
    private OverflowReason lastOverflowReason = OverflowReason.NONE;

    public VoxelDirtyQueue(
            final int capacity,
            final int hardDrainBudget,
            final OptionalLong estimatedTimeBudgetNanos,
            final long starvationBoundTicks
    ) {
        if (capacity <= 0 || hardDrainBudget <= 0 || starvationBoundTicks <= 0L) {
            throw new IllegalArgumentException("Dirty-brick capacity, drain budget and starvation bound must be positive");
        }
        this.estimatedTimeBudgetNanos = Objects.requireNonNull(
                estimatedTimeBudgetNanos, "estimatedTimeBudgetNanos"
        );
        if (this.estimatedTimeBudgetNanos.isPresent()
                && this.estimatedTimeBudgetNanos.getAsLong() <= 0L) {
            throw new IllegalArgumentException("Estimated dirty-brick time budget must be positive");
        }
        this.capacity = capacity;
        this.hardDrainBudget = hardDrainBudget;
        this.starvationBoundTicks = starvationBoundTicks;
    }

    public void advanceTo(final long tick) {
        if (tick < this.currentTick) {
            throw new IllegalArgumentException("Dirty-brick queue time cannot move backwards");
        }
        this.currentTick = tick;
    }

    public OfferResult offer(final BrickKey key, final Priority priority, final long estimatedNanos) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(priority, "priority");
        if (estimatedNanos <= 0L) {
            throw new IllegalArgumentException("Dirty-brick work estimate must be positive");
        }
        this.offered++;
        PendingBrick existing = this.pending.get(key);
        if (existing != null) {
            if (priority.ordinal() < existing.priority.ordinal()) {
                existing.priority = priority;
            }
            existing.estimatedNanos = Math.max(existing.estimatedNanos, estimatedNanos);
            existing.coalescedUpdates++;
            this.coalesced++;
            return new OfferResult(OfferStatus.COALESCED, OverflowReason.NONE);
        }
        if (this.pending.size() >= this.capacity) {
            this.rejected++;
            this.lastOverflowReason = OverflowReason.CAPACITY_REACHED_REQUIRES_FALLBACK;
            return new OfferResult(OfferStatus.REJECTED, this.lastOverflowReason);
        }
        this.pending.put(key, new PendingBrick(
                key, priority, estimatedNanos, this.currentTick, this.nextSequence++, 0
        ));
        this.enqueued++;
        this.highWaterMark = Math.max(this.highWaterMark, this.pending.size());
        return new OfferResult(OfferStatus.ENQUEUED, OverflowReason.NONE);
    }

    public Drain drainActualCount() {
        return drainActualCount(this.hardDrainBudget);
    }

    /** Owner-selected async admission, still capped by the queue's hard drain budget. */
    public Drain drainActualCount(final int requestedCount) {
        return drain(requestedCount, this.hardDrainBudget);
    }

    /** Moves bounded work into the async pack queue; upload drain limits do not throttle workers. */
    public Drain drainForAsyncPacking(final int requestedCount) {
        return drain(requestedCount, this.capacity);
    }

    private Drain drain(final int requestedCount, final int hardLimit) {
        if (requestedCount < 0) {
            throw new IllegalArgumentException("Requested dirty-brick count must be non-negative");
        }
        if (this.pending.isEmpty()) {
            return new Drain(List.of(), 0, 0L);
        }
        List<PendingBrick> ordered = new ArrayList<>(this.pending.values());
        ordered.sort(processingOrder());
        int limit = Math.min(hardLimit, requestedCount);
        List<DirtyBrick> drained = new ArrayList<>(Math.min(limit, ordered.size()));
        long estimatedTotal = 0L;
        for (PendingBrick brick : ordered) {
            if (drained.size() >= limit) {
                break;
            }
            boolean exceedsBudget = this.estimatedTimeBudgetNanos.isPresent()
                    && saturatingAdd(estimatedTotal, brick.estimatedNanos)
                    > this.estimatedTimeBudgetNanos.getAsLong();
            // A soft time budget must not prevent the queue from making any progress.
            if (exceedsBudget && !drained.isEmpty()) {
                break;
            }
            long age = age(brick);
            boolean starved = age >= this.starvationBoundTicks;
            drained.add(new DirtyBrick(
                    brick.key,
                    brick.priority,
                    brick.enqueuedTick,
                    age,
                    brick.estimatedNanos,
                    brick.enqueueSequence,
                    brick.coalescedUpdates,
                    starved
            ));
            estimatedTotal = saturatingAdd(estimatedTotal, brick.estimatedNanos);
        }
        for (DirtyBrick brick : drained) {
            this.pending.remove(brick.key());
            if (brick.starvationPromoted()) {
                this.starvationPromotions++;
            }
        }
        this.processed += drained.size();
        return new Drain(drained, drained.size(), estimatedTotal);
    }

    /**
     * Returns a leased brick to the queue without making it young or changing its FIFO order.
     * This is intentionally not {@link #offer(BrickKey, Priority, long)}: a transient GPU-ring
     * retry must retain the original starvation age, sequence, coalescing count and estimate.
     */
    public OfferResult requeue(final DirtyBrick brick) {
        Objects.requireNonNull(brick, "brick");
        if (brick.enqueuedTick() > this.currentTick) {
            throw new IllegalArgumentException("Dirty-brick retry comes from the future");
        }
        PendingBrick existing = this.pending.get(brick.key());
        if (existing != null) {
            existing.priority = existing.priority.ordinal() <= brick.priority().ordinal()
                    ? existing.priority : brick.priority();
            existing.estimatedNanos = Math.max(existing.estimatedNanos, brick.estimatedNanos());
            existing.coalescedUpdates = saturatingAdd(existing.coalescedUpdates, brick.coalescedUpdates());
            return new OfferResult(OfferStatus.COALESCED, OverflowReason.NONE);
        }
        if (this.pending.size() >= this.capacity) {
            this.rejected++;
            this.lastOverflowReason = OverflowReason.CAPACITY_REACHED_REQUIRES_FALLBACK;
            return new OfferResult(OfferStatus.REJECTED, this.lastOverflowReason);
        }
        this.pending.put(brick.key(), new PendingBrick(
                brick.key(),
                brick.priority(),
                brick.estimatedNanos(),
                brick.enqueuedTick(),
                brick.enqueueSequence(),
                brick.coalescedUpdates()
        ));
        this.nextSequence = Math.max(this.nextSequence, saturatingIncrement(brick.enqueueSequence()));
        this.highWaterMark = Math.max(this.highWaterMark, this.pending.size());
        return new OfferResult(OfferStatus.ENQUEUED, OverflowReason.NONE);
    }

    /**
     * Defers an async source dependency without resetting age, but rotates FIFO order so a
     * pending accepted snapshot cannot head-of-line block unrelated packable bricks forever.
     */
    public OfferResult defer(final DirtyBrick brick) {
        Objects.requireNonNull(brick, "brick");
        if (brick.enqueuedTick() > this.currentTick) {
            throw new IllegalArgumentException("Deferred dirty brick comes from the future");
        }
        PendingBrick existing = this.pending.get(brick.key());
        if (existing != null) {
            return new OfferResult(OfferStatus.COALESCED, OverflowReason.NONE);
        }
        if (this.pending.size() >= this.capacity) {
            this.rejected++;
            this.lastOverflowReason = OverflowReason.CAPACITY_REACHED_REQUIRES_FALLBACK;
            return new OfferResult(OfferStatus.REJECTED, this.lastOverflowReason);
        }
        this.pending.put(brick.key(), new PendingBrick(
                brick.key(), Priority.LOW, brick.estimatedNanos(), brick.enqueuedTick(),
                this.nextSequence++, brick.coalescedUpdates()
        ));
        this.highWaterMark = Math.max(this.highWaterMark, this.pending.size());
        return new OfferResult(OfferStatus.ENQUEUED, OverflowReason.NONE);
    }

    /** Drops late results only when their explicit lifecycle key no longer belongs to this world. */
    public int discardIf(final Predicate<BrickKey> stalePredicate) {
        Objects.requireNonNull(stalePredicate, "stalePredicate");
        int before = this.pending.size();
        this.pending.entrySet().removeIf(entry -> stalePredicate.test(entry.getKey()));
        int discarded = before - this.pending.size();
        this.discardedStale += discarded;
        return discarded;
    }

    public int size() {
        return this.pending.size();
    }

    public Telemetry telemetry() {
        long oldestAge = 0L;
        for (PendingBrick brick : this.pending.values()) {
            oldestAge = Math.max(oldestAge, age(brick));
        }
        return new Telemetry(
                this.capacity,
                this.pending.size(),
                this.highWaterMark,
                this.hardDrainBudget,
                this.estimatedTimeBudgetNanos,
                this.starvationBoundTicks,
                this.currentTick,
                oldestAge,
                this.offered,
                this.enqueued,
                this.coalesced,
                this.rejected,
                this.processed,
                this.discardedStale,
                this.starvationPromotions,
                this.lastOverflowReason
        );
    }

    private Comparator<PendingBrick> processingOrder() {
        return (left, right) -> {
            boolean leftStarved = age(left) >= this.starvationBoundTicks;
            boolean rightStarved = age(right) >= this.starvationBoundTicks;
            if (leftStarved != rightStarved) {
                return leftStarved ? -1 : 1;
            }
            if (leftStarved) {
                return Long.compare(left.enqueueSequence, right.enqueueSequence);
            }
            int priority = Integer.compare(left.priority.ordinal(), right.priority.ordinal());
            return priority != 0 ? priority : Long.compare(left.enqueueSequence, right.enqueueSequence);
        };
    }

    private long age(final PendingBrick brick) {
        return this.currentTick - brick.enqueuedTick;
    }

    private static long saturatingAdd(final long left, final long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static int saturatingAdd(final int left, final int right) {
        return right > Integer.MAX_VALUE - left ? Integer.MAX_VALUE : left + right;
    }

    private static long saturatingIncrement(final long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }
}
