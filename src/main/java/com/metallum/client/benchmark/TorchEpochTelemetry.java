package com.metallum.client.benchmark;

import java.util.Arrays;

/**
 * Bounded, render-thread-confined counters for one explicit torch-test epoch.
 *
 * <p>The Sodium telemetry mixins are benchmark-only, but they can still run
 * before or after the targeted mutation window. Every recorder therefore
 * remains a no-op until {@link #begin(long)} and after {@link #end()} or
 * {@link #abort()}.</p>
 */
public final class TorchEpochTelemetry {
    private static final int DEFAULT_UNIQUE_SECTION_LIMIT = 8_192;
    private static final Recorder GLOBAL = new Recorder(DEFAULT_UNIQUE_SECTION_LIMIT);

    private TorchEpochTelemetry() {
    }

    public static void begin(final long epochId) {
        GLOBAL.begin(epochId);
    }

    /**
     * Closes the current epoch and returns its final counters.
     *
     * <p>Calling this while inactive is safe and returns the last closed (or
     * reset) snapshot unchanged.</p>
     */
    public static Snapshot end() {
        return GLOBAL.end();
    }

    /** Discards any partial epoch after benchmark failure and returns to zero. */
    public static void abort() {
        GLOBAL.abort();
    }

    public static Snapshot snapshot() {
        return GLOBAL.snapshot();
    }

    /** Fast benchmark-hook guard; production clients do not load the hooks at all. */
    public static boolean isActive() {
        return GLOBAL.isActive();
    }

    public static void recordRebuildRequest(final long sectionKey) {
        GLOBAL.recordRebuildRequest(sectionKey);
    }

    public static void recordRebuildTask(
            final long sectionKey,
            final long pendingAgeNanos
    ) {
        GLOBAL.recordRebuildTask(sectionKey, pendingAgeNanos);
    }

    public static void recordBuildOutput(final long sectionKey) {
        GLOBAL.recordBuildOutput(sectionKey);
    }

    public static void recordAcceptedMeshPayloadBytes(final long bytes) {
        GLOBAL.recordAcceptedMeshPayloadBytes(bytes);
    }

    public static void recordBuilderWorkState(
            final int queuedJobs,
            final int busyWorkers,
            final int pendingResults
    ) {
        GLOBAL.recordBuilderWorkState(queuedJobs, busyWorkers, pendingResults);
    }

    public static void recordError() {
        GLOBAL.recordError();
    }

    static Recorder recorderForTests(final int uniqueSectionLimit) {
        return new Recorder(uniqueSectionLimit);
    }

    public record Snapshot(
            boolean active,
            long epochId,
            long rebuildRequestCount,
            long uniqueRebuildRequestSections,
            long rebuildTaskCount,
            long uniqueRebuildTaskSections,
            long buildOutputCount,
            long uniqueBuildOutputSections,
            long acceptedMeshPayloadBytes,
            int maximumBuilderQueueDepth,
            int finalBuilderQueueDepth,
            int maximumBusyWorkerCount,
            int finalBusyWorkerCount,
            int maximumPendingResultCount,
            int finalPendingResultCount,
            long maximumPendingAgeNanos,
            long errorCount,
            long overflowCount
    ) {
    }

    static final class Recorder {
        private final BoundedLongSet requestSections;
        private final BoundedLongSet taskSections;
        private final BoundedLongSet outputSections;

        private volatile boolean active;
        private long epochId;
        private long rebuildRequestCount;
        private long rebuildTaskCount;
        private long buildOutputCount;
        private long acceptedMeshPayloadBytes;
        private int maximumBuilderQueueDepth;
        private int finalBuilderQueueDepth;
        private int maximumBusyWorkerCount;
        private int finalBusyWorkerCount;
        private int maximumPendingResultCount;
        private int finalPendingResultCount;
        private long maximumPendingAgeNanos;
        private long errorCount;
        private long overflowCount;

        Recorder(final int uniqueSectionLimit) {
            this.requestSections = new BoundedLongSet(uniqueSectionLimit);
            this.taskSections = new BoundedLongSet(uniqueSectionLimit);
            this.outputSections = new BoundedLongSet(uniqueSectionLimit);
        }

        void begin(final long newEpochId) {
            boolean replacedActiveEpoch = this.active;
            this.clear(newEpochId);
            if (replacedActiveEpoch) {
                this.errorCount = 1L;
            }
            this.active = true;
        }

        Snapshot end() {
            this.active = false;
            return this.snapshot();
        }

        void abort() {
            this.active = false;
            this.clear(0L);
        }

        Snapshot snapshot() {
            return new Snapshot(
                    this.active,
                    this.epochId,
                    this.rebuildRequestCount,
                    this.requestSections.size(),
                    this.rebuildTaskCount,
                    this.taskSections.size(),
                    this.buildOutputCount,
                    this.outputSections.size(),
                    this.acceptedMeshPayloadBytes,
                    this.maximumBuilderQueueDepth,
                    this.finalBuilderQueueDepth,
                    this.maximumBusyWorkerCount,
                    this.finalBusyWorkerCount,
                    this.maximumPendingResultCount,
                    this.finalPendingResultCount,
                    this.maximumPendingAgeNanos,
                    this.errorCount,
                    this.overflowCount
            );
        }

        void recordRebuildRequest(final long sectionKey) {
            if (!this.active) {
                return;
            }
            this.rebuildRequestCount = this.add(this.rebuildRequestCount, 1L);
            this.recordUnique(this.requestSections, sectionKey);
        }

        void recordRebuildTask(
                final long sectionKey,
                final long pendingAgeNanos
        ) {
            if (!this.active) {
                return;
            }
            this.rebuildTaskCount = this.add(this.rebuildTaskCount, 1L);
            this.recordUnique(this.taskSections, sectionKey);
            if (pendingAgeNanos < 0L) {
                this.recordError();
            } else {
                this.maximumPendingAgeNanos = Math.max(this.maximumPendingAgeNanos, pendingAgeNanos);
            }
        }

        void recordBuildOutput(final long sectionKey) {
            if (!this.active) {
                return;
            }
            this.buildOutputCount = this.add(this.buildOutputCount, 1L);
            this.recordUnique(this.outputSections, sectionKey);
        }

        void recordAcceptedMeshPayloadBytes(final long bytes) {
            if (!this.active) {
                return;
            }
            if (bytes < 0L) {
                this.recordError();
                return;
            }
            this.acceptedMeshPayloadBytes = this.add(this.acceptedMeshPayloadBytes, bytes);
        }

        void recordBuilderWorkState(
                final int queuedJobs,
                final int busyWorkers,
                final int pendingResults
        ) {
            if (!this.active) {
                return;
            }
            if (queuedJobs < 0 || busyWorkers < 0 || pendingResults < 0) {
                this.recordError();
                return;
            }
            this.maximumBuilderQueueDepth = Math.max(this.maximumBuilderQueueDepth, queuedJobs);
            this.finalBuilderQueueDepth = queuedJobs;
            this.maximumBusyWorkerCount = Math.max(this.maximumBusyWorkerCount, busyWorkers);
            this.finalBusyWorkerCount = busyWorkers;
            this.maximumPendingResultCount = Math.max(this.maximumPendingResultCount, pendingResults);
            this.finalPendingResultCount = pendingResults;
        }

        void recordError() {
            if (!this.active) {
                return;
            }
            if (this.errorCount == Long.MAX_VALUE) {
                this.recordOverflow();
            } else {
                this.errorCount++;
            }
        }

        private void clear(final long newEpochId) {
            this.epochId = newEpochId;
            this.rebuildRequestCount = 0L;
            this.rebuildTaskCount = 0L;
            this.buildOutputCount = 0L;
            this.acceptedMeshPayloadBytes = 0L;
            this.maximumBuilderQueueDepth = 0;
            this.finalBuilderQueueDepth = 0;
            this.maximumBusyWorkerCount = 0;
            this.finalBusyWorkerCount = 0;
            this.maximumPendingResultCount = 0;
            this.finalPendingResultCount = 0;
            this.maximumPendingAgeNanos = 0L;
            this.errorCount = 0L;
            this.overflowCount = 0L;
            this.requestSections.clear();
            this.taskSections.clear();
            this.outputSections.clear();
        }

        boolean isActive() {
            return this.active;
        }

        private void recordUnique(final BoundedLongSet sections, final long sectionKey) {
            int result = sections.add(sectionKey);
            if (result == BoundedLongSet.OVERFLOW) {
                this.recordOverflow();
            }
        }

        private long add(final long current, final long value) {
            if (Long.MAX_VALUE - current < value) {
                this.recordOverflow();
                return Long.MAX_VALUE;
            }
            return current + value;
        }

        private void recordOverflow() {
            if (this.overflowCount < Long.MAX_VALUE) {
                this.overflowCount++;
            }
        }
    }

    private static final class BoundedLongSet {
        private static final int DUPLICATE = 0;
        private static final int ADDED = 1;
        private static final int OVERFLOW = -1;
        private static final int MAX_LIMIT = 1 << 20;

        private final long[] keys;
        private final byte[] occupied;
        private final int limit;
        private final int mask;
        private int size;

        BoundedLongSet(final int limit) {
            if (limit <= 0 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("unique section limit must be between 1 and " + MAX_LIMIT);
            }
            int tableSize = 1;
            while (tableSize < limit * 2) {
                tableSize <<= 1;
            }
            this.keys = new long[tableSize];
            this.occupied = new byte[tableSize];
            this.limit = limit;
            this.mask = tableSize - 1;
        }

        int add(final long key) {
            int index = mix(key) & this.mask;
            while (this.occupied[index] != 0) {
                if (this.keys[index] == key) {
                    return DUPLICATE;
                }
                index = index + 1 & this.mask;
            }
            if (this.size >= this.limit) {
                return OVERFLOW;
            }
            this.occupied[index] = 1;
            this.keys[index] = key;
            this.size++;
            return ADDED;
        }

        int size() {
            return this.size;
        }

        void clear() {
            Arrays.fill(this.occupied, (byte) 0);
            this.size = 0;
        }

        private static int mix(final long value) {
            long mixed = value;
            mixed ^= mixed >>> 33;
            mixed *= 0xff51afd7ed558ccdl;
            mixed ^= mixed >>> 33;
            mixed *= 0xc4ceb9fe1a85ec53l;
            mixed ^= mixed >>> 33;
            return (int) mixed;
        }
    }
}
