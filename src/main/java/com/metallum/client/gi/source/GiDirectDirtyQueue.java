package com.metallum.client.gi.source;

import java.util.Objects;

/** Fixed-capacity render-thread queue; it only schedules dirty bricks and never rebuilds a volume. */
public final class GiDirectDirtyQueue {
    public static final long STARVATION_TICKS = 16L;

    public enum OfferResult { ENQUEUED, COALESCED, STALE }

    public record Telemetry(
            long queued, long coalesced, long completed, long discarded,
            int pending, long starvationPromotions, long fullVolumeRebuilds
    ) {
    }

    /** Counters scoped to the active native epoch; lifetime telemetry above never resets. */
    public record EpochTelemetry(
            long queued, long completed, long discarded, int pending,
            boolean fullVolumeEnqueued
    ) {
    }

    private static final class Pending {
        private boolean queued;
        private long enqueueTick;
        private long sequence;
        private int coalesced;
    }

    private final Pending[] pending = new Pending[GiDirectSourceLayout.TOTAL_BRICKS];
    private GiDirectSourceEpoch epoch;
    private long nextSequence;
    private long lastTick;
    private int pendingCount;
    private long queued;
    private long coalesced;
    private long completed;
    private long discarded;
    private long starvationPromotions;
    private long fullVolumeRebuilds;
    private long epochQueued;
    private long epochCompleted;
    private long epochDiscarded;
    private boolean epochFullVolumeEnqueued;

    public GiDirectDirtyQueue() {
        for (int index = 0; index < this.pending.length; index++) {
            this.pending[index] = new Pending();
        }
    }

    /** Drops old scheduled work; the corresponding native output must be considered zero/invalid. */
    public void rotateEpoch(final GiDirectSourceEpoch next) {
        Objects.requireNonNull(next, "next");
        if (this.epoch != null && this.epoch.equals(next)) {
            return;
        }
        if (this.epoch != null) {
            next.requireStrictlyNewerThan(this.epoch);
        }
        for (Pending entry : this.pending) {
            if (entry.queued) {
                entry.queued = false;
                this.discarded++;
            }
        }
        this.pendingCount = 0;
        this.epoch = next;
        this.epochQueued = 0L;
        this.epochCompleted = 0L;
        this.epochDiscarded = 0L;
        this.epochFullVolumeEnqueued = false;
    }

    public OfferResult enqueue(final GiDirectSourceEpoch expected, final int brickId, final long tick) {
        GiDirectSourceLayout.validateBrickId(brickId);
        advanceTick(tick);
        if (this.epoch == null || !this.epoch.equals(expected)) {
            this.discarded++;
            return OfferResult.STALE;
        }
        Pending entry = this.pending[brickId];
        if (entry.queued) {
            entry.coalesced++;
            this.coalesced++;
            return OfferResult.COALESCED;
        }
        entry.queued = true;
        entry.enqueueTick = tick;
        entry.sequence = this.nextSequence++;
        entry.coalesced = 0;
        this.pendingCount++;
        this.queued++;
        this.epochQueued++;
        return OfferResult.ENQUEUED;
    }

    public void enqueueRange(
            final GiDirectSourceEpoch expected, final int cascade,
            final int minBrickX, final int minBrickY, final int minBrickZ,
            final int maxBrickX, final int maxBrickY, final int maxBrickZ, final long tick
    ) {
        validateRange(cascade, minBrickX, minBrickY, minBrickZ, maxBrickX, maxBrickY, maxBrickZ);
        for (int z = minBrickZ; z <= maxBrickZ; z++) {
            for (int y = minBrickY; y <= maxBrickY; y++) {
                for (int x = minBrickX; x <= maxBrickX; x++) {
                    enqueue(expected, GiDirectSourceLayout.brickId(cascade, x, y, z), tick);
                }
            }
        }
    }

    /** Enqueues at most the fixed 192 logical bricks; processing remains capped at eight. */
    public void enqueueAll(final GiDirectSourceEpoch expected, final long tick) {
        this.fullVolumeRebuilds = Math.incrementExact(this.fullVolumeRebuilds);
        this.epochFullVolumeEnqueued = true;
        for (int brick = 0; brick < GiDirectSourceLayout.TOTAL_BRICKS; brick++) {
            enqueue(expected, brick, tick);
        }
    }

    /** Writes the deterministic next batch into caller-owned storage and returns its actual count. */
    public int drainTo(final GiDirectSourceEpoch expected, final long tick, final int[] destination) {
        Objects.requireNonNull(destination, "destination");
        advanceTick(tick);
        if (this.epoch == null || !this.epoch.equals(expected)) {
            return 0;
        }
        int limit = Math.min(GiDirectSourceLayout.MAX_DRAIN_PER_FRAME, destination.length);
        int count = 0;
        for (; count < limit; count++) {
            int selected = selectNext(tick);
            if (selected < 0) {
                break;
            }
            Pending entry = this.pending[selected];
            if (tick - entry.enqueueTick >= STARVATION_TICKS) {
                this.starvationPromotions++;
            }
            entry.queued = false;
            this.pendingCount--;
            destination[count] = selected;
        }
        return count;
    }

    /** Marks a native-accepted batch complete without allocating per-entry state. */
    public void completeBatch(
            final GiDirectSourceEpoch expected,
            final int[] brickIds,
            final int count
    ) {
        Objects.requireNonNull(brickIds, "brickIds");
        if (this.epoch == null || !this.epoch.equals(expected)
                || count < 0 || count > brickIds.length
                || count > GiDirectSourceLayout.MAX_DRAIN_PER_FRAME) {
            throw new IllegalArgumentException("G3 completed batch does not match the active epoch");
        }
        this.completed = Math.addExact(this.completed, count);
        this.epochCompleted = Math.addExact(this.epochCompleted, count);
    }

    /** Requeues a transiently rejected batch under the same epoch. */
    public void retryBatch(
            final GiDirectSourceEpoch expected,
            final int[] brickIds,
            final int count,
            final long tick
    ) {
        Objects.requireNonNull(brickIds, "brickIds");
        if (count < 0 || count > brickIds.length
                || count > GiDirectSourceLayout.MAX_DRAIN_PER_FRAME) {
            throw new IllegalArgumentException("G3 retry batch exceeds its fixed bound");
        }
        advanceTick(tick);
        if (this.epoch == null || !this.epoch.equals(expected)) {
            this.discarded = Math.addExact(this.discarded, count);
            return;
        }
        for (int index = 0; index < count; index++) {
            int brickId = brickIds[index];
            GiDirectSourceLayout.validateBrickId(brickId);
            Pending entry = this.pending[brickId];
            if (entry.queued) {
                this.coalesced++;
                continue;
            }
            entry.queued = true;
            entry.enqueueTick = tick;
            entry.sequence = this.nextSequence++;
            this.pendingCount++;
        }
    }

    public Telemetry telemetry() {
        return new Telemetry(this.queued, this.coalesced, this.completed, this.discarded,
                this.pendingCount, this.starvationPromotions, this.fullVolumeRebuilds);
    }

    public EpochTelemetry epochTelemetry() {
        return new EpochTelemetry(
                this.epochQueued, this.epochCompleted, this.epochDiscarded,
                this.pendingCount, this.epochFullVolumeEnqueued
        );
    }

    private int selectNext(final long tick) {
        int selected = -1;
        for (int brick = 0; brick < this.pending.length; brick++) {
            Pending candidate = this.pending[brick];
            if (!candidate.queued) {
                continue;
            }
            if (selected < 0 || before(candidate, brick, this.pending[selected], selected, tick)) {
                selected = brick;
            }
        }
        return selected;
    }

    private static boolean before(
            final Pending left, final int leftBrick, final Pending right, final int rightBrick, final long tick
    ) {
        boolean leftStarved = tick - left.enqueueTick >= STARVATION_TICKS;
        boolean rightStarved = tick - right.enqueueTick >= STARVATION_TICKS;
        if (leftStarved != rightStarved) {
            return leftStarved;
        }
        if (left.enqueueTick != right.enqueueTick) {
            return left.enqueueTick < right.enqueueTick;
        }
        if (left.sequence != right.sequence) {
            return left.sequence < right.sequence;
        }
        return leftBrick < rightBrick;
    }

    private void advanceTick(final long tick) {
        if (tick < this.lastTick) {
            throw new IllegalArgumentException("G3 dirty queue time cannot move backwards");
        }
        this.lastTick = tick;
    }

    private static void validateRange(
            final int cascade, final int minX, final int minY, final int minZ,
            final int maxX, final int maxY, final int maxZ
    ) {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("G3 dirty-brick range is inverted");
        }
        GiDirectSourceLayout.brickId(cascade, minX, minY, minZ);
        GiDirectSourceLayout.brickId(cascade, maxX, maxY, maxZ);
    }
}
