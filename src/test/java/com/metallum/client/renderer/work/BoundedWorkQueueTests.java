package com.metallum.client.renderer.work;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalLong;

public final class BoundedWorkQueueTests {
    private BoundedWorkQueueTests() {
    }

    public static void main(final String[] args) {
        testAllFutureWorkTypesAreDeclared();
        testBoundedQueueAndOverflow();
        testCoalescingAndPriorityUpgrade();
        testPriorityAndDeterministicOrder();
        testStarvationPromotion();
        testEstimatedTimeBudgetMakesProgress();
        testTelemetrySnapshot();
        System.out.println("Bounded work-budget P7 tests passed");
    }

    private static void testAllFutureWorkTypesAreDeclared() {
        require(EnumSet.allOf(BoundedWorkQueue.WorkType.class).equals(EnumSet.of(
                        BoundedWorkQueue.WorkType.LIGHT_UPDATE,
                        BoundedWorkQueue.WorkType.SHADOW_UPDATE,
                        BoundedWorkQueue.WorkType.VOXEL_DIRTY_BRICK,
                        BoundedWorkQueue.WorkType.IRRADIANCE_UPDATE,
                        BoundedWorkQueue.WorkType.FROXEL_REFRESH,
                        BoundedWorkQueue.WorkType.PROBE_REFLECTION_UPDATE)),
                "future lighting work types are incomplete");
    }

    private static void testBoundedQueueAndOverflow() {
        BoundedWorkQueue queue = queue(3, 3, OptionalLong.empty(), 5L);
        enqueue(queue, BoundedWorkQueue.WorkType.LIGHT_UPDATE, "a", BoundedWorkQueue.Priority.NORMAL, 1L);
        enqueue(queue, BoundedWorkQueue.WorkType.SHADOW_UPDATE, "b", BoundedWorkQueue.Priority.NORMAL, 1L);
        enqueue(queue, BoundedWorkQueue.WorkType.VOXEL_DIRTY_BRICK, "c", BoundedWorkQueue.Priority.NORMAL, 1L);
        BoundedWorkQueue.OfferResult overflow = queue.offer(
                BoundedWorkQueue.WorkType.FROXEL_REFRESH,
                "d",
                BoundedWorkQueue.Priority.CRITICAL,
                1L
        );
        require(overflow.status() == BoundedWorkQueue.OfferStatus.REJECTED
                        && overflow.overflowReason()
                        == BoundedWorkQueue.OverflowReason.CAPACITY_REACHED_REQUIRES_FALLBACK,
                "capacity overflow did not request fallback");
        for (int index = 0; index < 1_000; index++) {
            queue.offer(BoundedWorkQueue.WorkType.PROBE_REFLECTION_UPDATE, "overflow-" + index,
                    BoundedWorkQueue.Priority.LOW, 1L);
        }
        require(queue.size() == 3 && queue.telemetry().highWaterMark() == 3,
                "bounded queue grew past capacity");
    }

    private static void testCoalescingAndPriorityUpgrade() {
        BoundedWorkQueue queue = queue(2, 2, OptionalLong.empty(), 5L);
        enqueue(queue, BoundedWorkQueue.WorkType.LIGHT_UPDATE, "section-1",
                BoundedWorkQueue.Priority.LOW, 2L);
        BoundedWorkQueue.OfferResult result = queue.offer(
                BoundedWorkQueue.WorkType.LIGHT_UPDATE,
                "section-1",
                BoundedWorkQueue.Priority.HIGH,
                7L
        );
        require(result.status() == BoundedWorkQueue.OfferStatus.COALESCED && queue.size() == 1,
                "same key did not coalesce");
        BoundedWorkQueue.ProcessedWork work = queue.drainBudget().getFirst();
        require(work.priority() == BoundedWorkQueue.Priority.HIGH
                        && work.estimatedNanos() == 7L
                        && work.coalescedUpdates() == 1,
                "coalescing lost priority/time/update metadata");
    }

    private static void testPriorityAndDeterministicOrder() {
        List<String> first = deterministicDrain();
        List<String> second = deterministicDrain();
        require(first.equals(List.of("critical", "high-old", "high-new", "low")),
                "priority/FIFO order mismatch: " + first);
        require(first.equals(second), "processing order is not deterministic");
    }

    private static List<String> deterministicDrain() {
        BoundedWorkQueue queue = queue(8, 8, OptionalLong.empty(), 100L);
        enqueue(queue, BoundedWorkQueue.WorkType.LIGHT_UPDATE, "low", BoundedWorkQueue.Priority.LOW, 1L);
        enqueue(queue, BoundedWorkQueue.WorkType.SHADOW_UPDATE, "high-old", BoundedWorkQueue.Priority.HIGH, 1L);
        enqueue(queue, BoundedWorkQueue.WorkType.VOXEL_DIRTY_BRICK, "critical",
                BoundedWorkQueue.Priority.CRITICAL, 1L);
        enqueue(queue, BoundedWorkQueue.WorkType.IRRADIANCE_UPDATE, "high-new",
                BoundedWorkQueue.Priority.HIGH, 1L);
        return queue.drainBudget().stream().map(work -> work.key().value()).toList();
    }

    private static void testStarvationPromotion() {
        BoundedWorkQueue queue = queue(8, 1, OptionalLong.empty(), 5L);
        enqueue(queue, BoundedWorkQueue.WorkType.PROBE_REFLECTION_UPDATE, "old-low",
                BoundedWorkQueue.Priority.LOW, 1L);
        enqueue(queue, BoundedWorkQueue.WorkType.SHADOW_UPDATE, "old-high",
                BoundedWorkQueue.Priority.HIGH, 1L);
        queue.advanceTo(4L);
        enqueue(queue, BoundedWorkQueue.WorkType.LIGHT_UPDATE, "new-critical",
                BoundedWorkQueue.Priority.CRITICAL, 1L);
        queue.advanceTo(5L);
        BoundedWorkQueue.ProcessedWork selected = queue.drainBudget().getFirst();
        require(selected.key().value().equals("old-low") && selected.starvationPromoted()
                        && selected.ageTicks() == 5L,
                "starved work did not outrank fresh critical work");
    }

    private static void testEstimatedTimeBudgetMakesProgress() {
        BoundedWorkQueue queue = queue(4, 4, OptionalLong.of(10L), 10L);
        enqueue(queue, BoundedWorkQueue.WorkType.LIGHT_UPDATE, "oversized",
                BoundedWorkQueue.Priority.HIGH, 20L);
        enqueue(queue, BoundedWorkQueue.WorkType.SHADOW_UPDATE, "next",
                BoundedWorkQueue.Priority.NORMAL, 2L);
        List<BoundedWorkQueue.ProcessedWork> first = queue.drainBudget();
        require(first.size() == 1 && first.getFirst().key().value().equals("oversized"),
                "soft time budget blocked all progress");
        require(queue.drainBudget().getFirst().key().value().equals("next"),
                "time-budget remainder was lost");
    }

    private static void testTelemetrySnapshot() {
        BoundedWorkQueue queue = queue(2, 1, OptionalLong.of(5L), 3L);
        enqueue(queue, BoundedWorkQueue.WorkType.FROXEL_REFRESH, "f", BoundedWorkQueue.Priority.NORMAL, 2L);
        queue.offer(BoundedWorkQueue.WorkType.FROXEL_REFRESH, "f", BoundedWorkQueue.Priority.HIGH, 3L);
        enqueue(queue, BoundedWorkQueue.WorkType.IRRADIANCE_UPDATE, "i",
                BoundedWorkQueue.Priority.NORMAL, 2L);
        queue.offer(BoundedWorkQueue.WorkType.SHADOW_UPDATE, "overflow", BoundedWorkQueue.Priority.LOW, 1L);
        queue.advanceTo(3L);
        queue.drainBudget();
        BoundedWorkQueue.TelemetrySnapshot snapshot = queue.telemetry();
        require(snapshot.capacity() == 2
                        && snapshot.queuedItems() == 1
                        && snapshot.highWaterMark() == 2
                        && snapshot.offered() == 4L
                        && snapshot.enqueued() == 2L
                        && snapshot.coalesced() == 1L
                        && snapshot.rejected() == 1L
                        && snapshot.processed() == 1L
                        && snapshot.starvationPromotions() == 1L
                        && snapshot.oldestAgeTicks() == 3L,
                "telemetry snapshot mismatch: " + snapshot);
    }

    private static BoundedWorkQueue queue(
            final int capacity,
            final int itemBudget,
            final OptionalLong timeBudget,
            final long starvationBound
    ) {
        return new BoundedWorkQueue(capacity, itemBudget, timeBudget, starvationBound);
    }

    private static void enqueue(
            final BoundedWorkQueue queue,
            final BoundedWorkQueue.WorkType type,
            final String key,
            final BoundedWorkQueue.Priority priority,
            final long estimatedNanos
    ) {
        require(queue.offer(type, key, priority, estimatedNanos).status()
                        == BoundedWorkQueue.OfferStatus.ENQUEUED,
                "work was not enqueued: " + key);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
