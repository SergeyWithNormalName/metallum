package com.metallum.client.gi.field;

/** Thread-safe accounting gate for immutable worker-produced G1 field candidates. */
public final class GiFieldCandidateBudget {
    public static final int DEFAULT_MAX_CANDIDATES = 64;
    public static final long DEFAULT_MAX_BYTES = 4L * 1024L * 1024L;

    public record Snapshot(int activeCandidates, long activeBytes, int peakCandidates, long peakBytes,
                           long accepted, long rejected) {
    }

    public final class Lease implements AutoCloseable {
        private final long bytes;
        private boolean closed;

        private Lease(final long bytes) {
            this.bytes = bytes;
        }

        public long bytes() {
            return this.bytes;
        }

        @Override
        public void close() {
            synchronized (GiFieldCandidateBudget.this) {
                if (this.closed) {
                    return;
                }
                this.closed = true;
                activeCandidates--;
                activeBytes -= this.bytes;
                if (activeCandidates < 0 || activeBytes < 0L) {
                    throw new IllegalStateException("G1 candidate budget accounting underflow");
                }
            }
        }
    }

    private final int maxCandidates;
    private final long maxBytes;
    private int activeCandidates;
    private long activeBytes;
    private int peakCandidates;
    private long peakBytes;
    private long accepted;
    private long rejected;

    public GiFieldCandidateBudget() {
        this(DEFAULT_MAX_CANDIDATES, DEFAULT_MAX_BYTES);
    }

    public GiFieldCandidateBudget(final int maxCandidates, final long maxBytes) {
        if (maxCandidates <= 0 || maxBytes <= 0L) {
            throw new IllegalArgumentException("G1 candidate limits must be positive");
        }
        this.maxCandidates = maxCandidates;
        this.maxBytes = maxBytes;
    }

    public synchronized Lease tryAcquire(final long bytes) {
        if (bytes <= 0L || bytes > this.maxBytes
                || this.activeCandidates >= this.maxCandidates
                || bytes > this.maxBytes - this.activeBytes) {
            this.rejected++;
            return null;
        }
        this.activeCandidates++;
        this.activeBytes += bytes;
        this.peakCandidates = Math.max(this.peakCandidates, this.activeCandidates);
        this.peakBytes = Math.max(this.peakBytes, this.activeBytes);
        this.accepted++;
        return new Lease(bytes);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                this.activeCandidates,
                this.activeBytes,
                this.peakCandidates,
                this.peakBytes,
                this.accepted,
                this.rejected
        );
    }

    /** Allocation-free render-thread readiness check for frozen benchmark admission. */
    public synchronized boolean hasActiveCandidates() {
        return this.activeCandidates != 0;
    }
}
