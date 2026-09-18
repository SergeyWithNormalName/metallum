package com.metallum.client.gi.live;

/** G6 update classes and their predeclared recovery limits in renderer submits. */
public enum GiLiveUpdateClass {
    BLOCK(8, 16, 0),
    STATIC_SOURCE(8, 16, 1),
    SCROLL(16, 32, 2),
    FULL_RESET(32, 64, 3);

    private final int p95SubmitLimit;
    private final int p99SubmitLimit;
    private final int severity;

    GiLiveUpdateClass(
            final int p95SubmitLimit,
            final int p99SubmitLimit,
            final int severity
    ) {
        this.p95SubmitLimit = p95SubmitLimit;
        this.p99SubmitLimit = p99SubmitLimit;
        this.severity = severity;
    }

    public int p95SubmitLimit() {
        return this.p95SubmitLimit;
    }

    public int p99SubmitLimit() {
        return this.p99SubmitLimit;
    }

    public boolean requiresFirstSubmitZero() {
        return this == FULL_RESET;
    }

    /** Coalesced work retains the more conservative recovery classification. */
    public static GiLiveUpdateClass merge(
            final GiLiveUpdateClass left,
            final GiLiveUpdateClass right
    ) {
        if (left == null) return right;
        if (right == null) return left;
        return left.severity >= right.severity ? left : right;
    }
}
