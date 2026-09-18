package com.metallum.client.gi.receiver;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Shared terminal safety latch for every consumer of the compact-position sideband. */
public final class CompactPositionCarrierSafety {
    private static final AtomicReference<String> CONFLICT_REASON = new AtomicReference<>();
    private static final AtomicLong REVISION = new AtomicLong();
    /**
     * A mesh worker reports a collision before publishing the affected compact mesh. A carrier
     * consumer holds the read side from its last safety-dependent bind through the native draw,
     * so the terminal write-side transition can occur only between draws. Fair ordering prevents
     * a stream of render-thread readers from starving a worker that has found a collision.
     */
    private static final ReentrantReadWriteLock DRAW_GATE = new ReentrantReadWriteLock(true);

    private CompactPositionCarrierSafety() {
    }

    public static boolean isSafe() {
        return CONFLICT_REASON.get() == null
                && GiReceiverCompatibility.supportsInstalledCompactPositionCarrier();
    }

    public static void reportConflict(final String reason) {
        publishConflict(reason, false);
    }

    /** Atomically publishes a per-quad skip and the shared G5 terminal transition. */
    public static void reportCarrierSkip(final String reason) {
        publishConflict(reason, true);
    }

    private static void publishConflict(final String reason, final boolean carrierSkip) {
        String resolved = reason == null || reason.isBlank()
                ? "compact position carrier conflict" : reason;
        DRAW_GATE.writeLock().lock();
        try {
            if (CONFLICT_REASON.compareAndSet(null, resolved)) {
                REVISION.incrementAndGet();
            }
            if (GiReceiverRuntime.isRequested()) {
                if (carrierSkip) {
                    GiReceiverRuntime.admission().reportCarrierSkip(resolved);
                } else {
                    GiReceiverRuntime.admission().reportCarrierConflict(resolved);
                }
            }
        } finally {
            DRAW_GATE.writeLock().unlock();
        }
    }

    public static String conflictReason() {
        String reason = CONFLICT_REASON.get();
        return reason == null ? "none" : reason;
    }

    /** Monotonic process-local revision for allocation-free per-encoder binding caches. */
    public static long revision() {
        return REVISION.get();
    }

    /** Begins an allocation-free render boundary for one compact-position-aware draw. */
    public static void beginCarrierAwareDraw() {
        DRAW_GATE.readLock().lock();
    }

    /** Ends the render boundary opened by {@link #beginCarrierAwareDraw()}. */
    public static void endCarrierAwareDraw() {
        DRAW_GATE.readLock().unlock();
    }

    public static void resetForTests() {
        DRAW_GATE.writeLock().lock();
        try {
            if (CONFLICT_REASON.getAndSet(null) != null) {
                REVISION.incrementAndGet();
            }
        } finally {
            DRAW_GATE.writeLock().unlock();
        }
    }
}
