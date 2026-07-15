package com.metallum.client.renderer.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Owner-thread, capacity-bounded model for future renderer work queues.
 * It is intentionally disconnected from production lighting work.
 */
public final class BoundedWorkQueue {
    public enum WorkType {
        LIGHT_UPDATE,
        SHADOW_UPDATE,
        VOXEL_DIRTY_BRICK,
        IRRADIANCE_UPDATE,
        FROXEL_REFRESH,
        PROBE_REFLECTION_UPDATE
    }

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

    public record CoalescingKey(WorkType type, String value) {
        public CoalescingKey {
            Objects.requireNonNull(type, "type");
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Coalescing key must not be blank");
            }
        }
    }

    public record OfferResult(OfferStatus status, OverflowReason overflowReason) {
        public OfferResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(overflowReason, "overflowReason");
            if ((status == OfferStatus.REJECTED) != (overflowReason != OverflowReason.NONE)) {
                throw new IllegalArgumentException("Only a rejected offer may declare overflow");
            }
        }
    }

    public record ProcessedWork(
            CoalescingKey key,
            Priority priority,
            long ageTicks,
            long estimatedNanos,
            long enqueueSequence,
            int coalescedUpdates,
            boolean starvationPromoted
    ) {
        public ProcessedWork {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(priority, "priority");
        }
    }

    public record TelemetrySnapshot(
            int capacity,
            int queuedItems,
            int highWaterMark,
            int hardItemBudget,
            OptionalLong estimatedTimeBudgetNanos,
            long starvationBoundTicks,
            long currentTick,
            long oldestAgeTicks,
            long offered,
            long enqueued,
            long coalesced,
            long rejected,
            long processed,
            long starvationPromotions,
            OverflowReason lastOverflowReason
    ) {
        public TelemetrySnapshot {
            Objects.requireNonNull(estimatedTimeBudgetNanos, "estimatedTimeBudgetNanos");
            Objects.requireNonNull(lastOverflowReason, "lastOverflowReason");
        }
    }

    private static final class PendingWork {
        private final CoalescingKey key;
        private final long enqueuedTick;
        private final long enqueueSequence;
        private Priority priority;
        private long estimatedNanos;
        private int coalescedUpdates;

        private PendingWork(
                final CoalescingKey key,
                final Priority priority,
                final long estimatedNanos,
                final long enqueuedTick,
                final long enqueueSequence
        ) {
            this.key = key;
            this.priority = priority;
            this.estimatedNanos = estimatedNanos;
            this.enqueuedTick = enqueuedTick;
            this.enqueueSequence = enqueueSequence;
        }
    }

    private final int capacity;
    private final int hardItemBudget;
    private final OptionalLong estimatedTimeBudgetNanos;
    private final long starvationBoundTicks;
    private final Map<CoalescingKey, PendingWork> pending = new LinkedHashMap<>();
    private long currentTick;
    private long nextSequence;
    private int highWaterMark;
    private long offered;
    private long enqueued;
    private long coalesced;
    private long rejected;
    private long processed;
    private long starvationPromotions;
    private OverflowReason lastOverflowReason = OverflowReason.NONE;

    public BoundedWorkQueue(
            final int capacity,
            final int hardItemBudget,
            final OptionalLong estimatedTimeBudgetNanos,
            final long starvationBoundTicks
    ) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Queue capacity must be positive");
        }
        if (hardItemBudget <= 0) {
            throw new IllegalArgumentException("Hard item budget must be positive");
        }
        Objects.requireNonNull(estimatedTimeBudgetNanos, "estimatedTimeBudgetNanos");
        if (estimatedTimeBudgetNanos.isPresent() && estimatedTimeBudgetNanos.getAsLong() <= 0L) {
            throw new IllegalArgumentException("Estimated-time budget must be positive when present");
        }
        if (starvationBoundTicks <= 0L) {
            throw new IllegalArgumentException("Starvation bound must be positive");
        }
        this.capacity = capacity;
        this.hardItemBudget = hardItemBudget;
        this.estimatedTimeBudgetNanos = estimatedTimeBudgetNanos;
        this.starvationBoundTicks = starvationBoundTicks;
    }

    public void advanceTo(final long tick) {
        if (tick < this.currentTick) {
            throw new IllegalArgumentException("Work queue time cannot move backwards");
        }
        this.currentTick = tick;
    }

    public OfferResult offer(
            final WorkType type,
            final String coalescingKey,
            final Priority priority,
            final long estimatedNanos
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(priority, "priority");
        if (estimatedNanos <= 0L) {
            throw new IllegalArgumentException("Estimated work time must be positive");
        }
        this.offered++;
        CoalescingKey key = new CoalescingKey(type, coalescingKey);
        PendingWork existing = this.pending.get(key);
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

        PendingWork work = new PendingWork(
                key,
                priority,
                estimatedNanos,
                this.currentTick,
                this.nextSequence++
        );
        this.pending.put(key, work);
        this.enqueued++;
        this.highWaterMark = Math.max(this.highWaterMark, this.pending.size());
        return new OfferResult(OfferStatus.ENQUEUED, OverflowReason.NONE);
    }

    public List<ProcessedWork> drainBudget() {
        if (this.pending.isEmpty()) {
            return List.of();
        }
        List<PendingWork> ordered = new ArrayList<>(this.pending.values());
        ordered.sort(processingOrder());
        List<ProcessedWork> selected = new ArrayList<>(Math.min(this.hardItemBudget, ordered.size()));
        long estimatedTotal = 0L;
        for (PendingWork work : ordered) {
            if (selected.size() >= this.hardItemBudget) {
                break;
            }
            boolean exceedsTime = this.estimatedTimeBudgetNanos.isPresent()
                    && saturatingAdd(estimatedTotal, work.estimatedNanos)
                    > this.estimatedTimeBudgetNanos.getAsLong();
            // The time budget is estimated/soft: allow one item so the queue always makes progress.
            if (exceedsTime && !selected.isEmpty()) {
                break;
            }
            estimatedTotal = saturatingAdd(estimatedTotal, work.estimatedNanos);
            long age = age(work);
            boolean promoted = age >= this.starvationBoundTicks;
            selected.add(new ProcessedWork(
                    work.key,
                    work.priority,
                    age,
                    work.estimatedNanos,
                    work.enqueueSequence,
                    work.coalescedUpdates,
                    promoted
            ));
        }

        for (ProcessedWork work : selected) {
            this.pending.remove(work.key());
            if (work.starvationPromoted()) {
                this.starvationPromotions++;
            }
        }
        this.processed += selected.size();
        return List.copyOf(selected);
    }

    public int size() {
        return this.pending.size();
    }

    public TelemetrySnapshot telemetry() {
        long oldestAge = 0L;
        for (PendingWork work : this.pending.values()) {
            oldestAge = Math.max(oldestAge, age(work));
        }
        return new TelemetrySnapshot(
                this.capacity,
                this.pending.size(),
                this.highWaterMark,
                this.hardItemBudget,
                this.estimatedTimeBudgetNanos,
                this.starvationBoundTicks,
                this.currentTick,
                oldestAge,
                this.offered,
                this.enqueued,
                this.coalesced,
                this.rejected,
                this.processed,
                this.starvationPromotions,
                this.lastOverflowReason
        );
    }

    private Comparator<PendingWork> processingOrder() {
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
            return priority != 0
                    ? priority
                    : Long.compare(left.enqueueSequence, right.enqueueSequence);
        };
    }

    private long age(final PendingWork work) {
        return this.currentTick - work.enqueuedTick;
    }

    private static long saturatingAdd(final long left, final long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
