package com.metallum.client.renderer.temporal;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Cross-hook one-shot reset events consumed by the next rendered world frame. */
public final class TemporalResetEvents {
    private static final AtomicLong PENDING = new AtomicLong();
    private static final FrameState.HistoryResetReason[] REASONS =
            FrameState.HistoryResetReason.values();

    private TemporalResetEvents() {
    }

    public static void signal(final FrameState.HistoryResetReason reason) {
        long bit = bit(reason);
        PENDING.getAndUpdate(current -> current | bit);
    }

    public static Set<FrameState.HistoryResetReason> consume() {
        long mask = PENDING.getAndSet(0L);
        if (mask == 0L) {
            return Set.of();
        }
        EnumSet<FrameState.HistoryResetReason> reasons = EnumSet.noneOf(
                FrameState.HistoryResetReason.class
        );
        for (FrameState.HistoryResetReason reason : REASONS) {
            if ((mask & bit(reason)) != 0L) {
                reasons.add(reason);
            }
        }
        return reasons;
    }

    static void clearForTests() {
        PENDING.set(0L);
    }

    private static long bit(final FrameState.HistoryResetReason reason) {
        if (reason == null) {
            throw new NullPointerException("reason");
        }
        return 1L << reason.ordinal();
    }
}
