package com.metallum.client.metal.render;

/**
 * Render-thread-confined Java workload counters for one Metal submit.
 *
 * <p>The class deliberately owns no native state, maps, or locks. It exists
 * only when the primary GPU timing report is enabled.</p>
 */
final class MetalJavaWorkloadTelemetry {
    private long cpuToSharedBytes;
    private long cpuToSharedOperations;
    private long cpuTransientRequestedBytes;
    private long cpuTransientReservedBytes;
    private long gpuTransientRequestedBytes;
    private long gpuTransientReservedBytes;

    void recordCpuToShared(final long bytes) {
        this.cpuToSharedBytes = add(this.cpuToSharedBytes, bytes, "CPU-to-shared bytes");
        this.cpuToSharedOperations = Math.addExact(this.cpuToSharedOperations, 1L);
    }

    void recordCpuTransientRequested(final long bytes) {
        this.cpuTransientRequestedBytes = add(
                this.cpuTransientRequestedBytes,
                bytes,
                "CPU transient requested bytes"
        );
    }

    void recordCpuTransientReserved(final long bytes) {
        this.cpuTransientReservedBytes = add(
                this.cpuTransientReservedBytes,
                bytes,
                "CPU transient reserved bytes"
        );
    }

    void recordGpuTransientRequested(final long bytes) {
        this.gpuTransientRequestedBytes = add(
                this.gpuTransientRequestedBytes,
                bytes,
                "GPU transient requested bytes"
        );
    }

    void recordGpuTransientReserved(final long bytes) {
        this.gpuTransientReservedBytes = add(
                this.gpuTransientReservedBytes,
                bytes,
                "GPU transient reserved bytes"
        );
    }

    Snapshot snapshot() {
        return new Snapshot(
                this.cpuToSharedBytes,
                this.cpuToSharedOperations,
                this.cpuTransientRequestedBytes,
                this.cpuTransientReservedBytes,
                this.gpuTransientRequestedBytes,
                this.gpuTransientReservedBytes
        );
    }

    void reset() {
        this.cpuToSharedBytes = 0L;
        this.cpuToSharedOperations = 0L;
        this.cpuTransientRequestedBytes = 0L;
        this.cpuTransientReservedBytes = 0L;
        this.gpuTransientRequestedBytes = 0L;
        this.gpuTransientReservedBytes = 0L;
    }

    private static long add(final long current, final long value, final String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return Math.addExact(current, value);
    }

    record Snapshot(
            long cpuToSharedBytes,
            long cpuToSharedOperations,
            long cpuTransientRequestedBytes,
            long cpuTransientReservedBytes,
            long gpuTransientRequestedBytes,
            long gpuTransientReservedBytes
    ) {
        Snapshot {
            if (cpuToSharedBytes < 0L
                    || cpuToSharedOperations < 0L
                    || cpuTransientRequestedBytes < 0L
                    || cpuTransientReservedBytes < 0L
                    || gpuTransientRequestedBytes < 0L
                    || gpuTransientReservedBytes < 0L) {
                throw new IllegalArgumentException("Java workload counters must be non-negative");
            }
            if (cpuTransientReservedBytes < cpuTransientRequestedBytes) {
                throw new IllegalStateException("CPU transient reserved bytes are below requested bytes");
            }
            if (gpuTransientReservedBytes < gpuTransientRequestedBytes) {
                throw new IllegalStateException("GPU transient reserved bytes are below requested bytes");
            }
        }
    }
}
