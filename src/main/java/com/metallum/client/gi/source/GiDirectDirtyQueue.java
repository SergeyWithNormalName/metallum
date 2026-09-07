package com.metallum.client.gi.source;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** Fixed-capacity render-thread queue; it only schedules dirty bricks and never rebuilds a volume. */
public final class GiDirectDirtyQueue {
    public static final long STARVATION_TICKS = 16L;

    public enum OfferResult { ENQUEUED, COALESCED, STALE }

    public enum CompletionResult { COMPLETED, STALE_EPOCH }

    public record Telemetry(
            long queued, long coalesced, long completed, long discarded,
            int pending, int inFlight,
            long starvationPromotions, long fullVolumeRebuilds
    ) {
        public Telemetry(
                final long queued,
                final long coalesced,
                final long completed,
                final long discarded,
                final int pending,
                final long starvationPromotions,
                final long fullVolumeRebuilds
        ) {
            this(queued, coalesced, completed, discarded, pending, 0,
                    starvationPromotions, fullVolumeRebuilds);
        }

        public boolean algebraIsExact() {
            return this.queued == this.completed + this.discarded
                    + this.pending + this.inFlight;
        }
    }

    /** Counters scoped to the active native epoch; lifetime telemetry above never resets. */
    public record EpochTelemetry(
            long queued, long completed, long discarded, int pending, int inFlight,
            boolean fullVolumeEnqueued
    ) {
    }

    private static final class Pending {
        private boolean queued;
        private boolean inFlight;
        private long enqueueTick;
        private long sequence;
        private int coalesced;
    }

    private final Pending[] pending = new Pending[GiDirectSourceLayout.TOTAL_BRICKS];
    private GiDirectSourceEpoch epoch;
    private long nextSequence;
    private long lastTick;
    private int pendingCount;
    private int inFlightCount;
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

    @Nullable GiDirectSourceEpoch activeEpoch() {
        return this.epoch;
    }

    /** Drops old scheduled work; the corresponding native output must be considered zero/invalid. */
    public void rotateEpoch(final GiDirectSourceEpoch next) {
        Objects.requireNonNull(next, "next");
        if (this.epoch != null && this.epoch.equals(next)) {
            return;
        }
        if (this.epoch != null) {
            GiDirectSourceEpoch previous = this.epoch;
            // Matches native headerIsNewerOrEqual/reset exactly: world generation is the root
            // lifecycle identity, so only a strictly newer world may restart child counters.
            if (next.g2WorldGeneration() <= previous.g2WorldGeneration()) {
                try {
                    next.requireStrictlyNewerThan(previous);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(
                            "G3 source epoch regressed or did not advance; previous="
                                    + previous + ", next=" + next,
                            exception
                    );
                }
            }
        }
        for (Pending entry : this.pending) {
            if (entry.queued || entry.inFlight) {
                entry.queued = false;
                entry.inFlight = false;
                this.discarded++;
            }
        }
        this.pendingCount = 0;
        this.inFlightCount = 0;
        this.epoch = next;
        this.epochQueued = 0L;
        this.epochCompleted = 0L;
        this.epochDiscarded = 0L;
        this.epochFullVolumeEnqueued = false;
    }

    /**
     * Advances a live content header without throwing away already-aged pending bricks.
     * Accepted GPU work must be retired before this boundary, so no in-flight owner can be
     * relabelled to a packet it did not encode.
     */
    public void rebaseLiveInputEpoch(final GiDirectSourceEpoch next) {
        Objects.requireNonNull(next, "next");
        if (this.epoch == null || !next.isLiveInputSuccessorOf(this.epoch)) {
            throw new IllegalArgumentException(
                    "G3 live-input rebase is not a strict same-grid successor; previous="
                            + this.epoch + ", next=" + next
            );
        }
        if (this.inFlightCount != 0) {
            throw new IllegalStateException("G3 live-input rebase retained in-flight ownership");
        }
        this.epoch = next;
        // These counters describe one native header identity. Retained pending work will execute
        // under the new header, while already completed older-header work remains admissible only
        // through the live per-brick stamp proof (never frozen G4's 192-brick proof).
        this.epochQueued = this.pendingCount;
        this.epochCompleted = 0L;
        this.epochDiscarded = 0L;
        this.epochFullVolumeEnqueued = false;
    }

    public OfferResult enqueue(final GiDirectSourceEpoch expected, final int brickId, final long tick) {
        GiDirectSourceLayout.validateBrickId(brickId);
        advanceTick(tick);
        if (this.epoch == null || !this.epoch.equals(expected)) {
            this.queued++;
            this.discarded++;
            return OfferResult.STALE;
        }
        Pending entry = this.pending[brickId];
        if (entry.queued || entry.inFlight) {
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

    /** Enqueues at most the fixed 192 logical bricks; processing remains capped at sixteen. */
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
            entry.inFlight = true;
            this.pendingCount--;
            this.inFlightCount++;
            destination[count] = selected;
        }
        return count;
    }

    /** Marks a native-accepted batch complete without allocating per-entry state. */
    public CompletionResult completeBatch(
            final GiDirectSourceEpoch expected,
            final int[] brickIds,
            final int count
    ) {
        Objects.requireNonNull(brickIds, "brickIds");
        if (count < 0 || count > brickIds.length
                || count > GiDirectSourceLayout.MAX_DRAIN_PER_FRAME) {
            throw new IllegalArgumentException("G3 completed batch does not match the active epoch");
        }
        if (this.epoch == null || !this.epoch.equals(expected)) {
            return CompletionResult.STALE_EPOCH;
        }
        validateInFlight(brickIds, count);
        for (int index = 0; index < count; index++) {
            this.pending[brickIds[index]].inFlight = false;
        }
        this.inFlightCount -= count;
        this.completed = Math.addExact(this.completed, count);
        this.epochCompleted = Math.addExact(this.epochCompleted, count);
        return CompletionResult.COMPLETED;
    }

    /** Requeues a transiently rejected batch under the same epoch. */
    public CompletionResult retryBatch(
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
            return CompletionResult.STALE_EPOCH;
        }
        validateInFlight(brickIds, count);
        for (int index = 0; index < count; index++) {
            int brickId = brickIds[index];
            GiDirectSourceLayout.validateBrickId(brickId);
            Pending entry = this.pending[brickId];
            entry.inFlight = false;
            entry.queued = true;
            // Preserve the original age/sequence so the next bounded drain repeats this
            // accepted batch ahead of younger backlog instead of silently delaying recovery.
            this.pendingCount++;
        }
        this.inFlightCount -= count;
        return CompletionResult.COMPLETED;
    }

    public Telemetry telemetry() {
        return new Telemetry(this.queued, this.coalesced, this.completed, this.discarded,
                this.pendingCount, this.inFlightCount,
                this.starvationPromotions, this.fullVolumeRebuilds);
    }

    public EpochTelemetry epochTelemetry() {
        return new EpochTelemetry(
                this.epochQueued, this.epochCompleted, this.epochDiscarded,
                this.pendingCount, this.inFlightCount, this.epochFullVolumeEnqueued
        );
    }

    /* Package-private primitive view for the render-loop native telemetry packet. */
    long queuedCount() { return this.queued; }
    long completedCount() { return this.completed; }
    long discardedCount() { return this.discarded; }
    int pendingCount() { return this.pendingCount; }
    int inFlightCount() { return this.inFlightCount; }
    int ownedCount() { return Math.addExact(this.pendingCount, this.inFlightCount); }
    long fullVolumeRebuildCount() { return this.fullVolumeRebuilds; }

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

    private void validateInFlight(final int[] brickIds, final int count) {
        for (int index = 0; index < count; index++) {
            int brickId = brickIds[index];
            GiDirectSourceLayout.validateBrickId(brickId);
            Pending entry = this.pending[brickId];
            if (!entry.inFlight) {
                throw new IllegalArgumentException("G3 completion does not own the brick");
            }
            for (int prior = 0; prior < index; prior++) {
                if (brickIds[prior] == brickId) {
                    throw new IllegalArgumentException("G3 completion repeats a brick");
                }
            }
        }
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
