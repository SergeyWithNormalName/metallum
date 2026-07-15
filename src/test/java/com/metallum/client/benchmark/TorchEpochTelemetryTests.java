package com.metallum.client.benchmark;

public final class TorchEpochTelemetryTests {
    private TorchEpochTelemetryTests() {
    }

    public static void main(final String[] args) {
        testInactiveEpochIsANoOp();
        testLifecycleCountersAndSafeEnd();
        testBoundedUniqueSectionTracking();
        testRestartAndAbortRecovery();
    }

    private static void testInactiveEpochIsANoOp() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(4);
        recorder.recordRebuildRequest(1L);
        recorder.recordRebuildTask(1L, 99L);
        recorder.recordBuildOutput(1L);
        recorder.recordAcceptedMeshPayloadBytes(256L);
        recorder.recordBuilderWorkState(9, 2, 1);
        recorder.recordError();

        TorchEpochTelemetry.Snapshot snapshot = recorder.snapshot();
        require(!snapshot.active(), "inactive recorder became active");
        require(snapshot.epochId() == 0L, "inactive recorder changed epoch ID");
        require(snapshot.rebuildRequestCount() == 0L, "inactive recorder counted requests");
        require(snapshot.rebuildTaskCount() == 0L, "inactive recorder counted tasks");
        require(snapshot.buildOutputCount() == 0L, "inactive recorder counted outputs");
        require(snapshot.acceptedMeshPayloadBytes() == 0L, "inactive recorder counted mesh bytes");
        require(snapshot.errorCount() == 0L, "inactive recorder counted errors");
    }

    private static void testLifecycleCountersAndSafeEnd() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(8);
        recorder.begin(42L);
        recorder.recordRebuildRequest(10L);
        recorder.recordRebuildRequest(10L);
        recorder.recordRebuildRequest(20L);
        recorder.recordRebuildTask(10L, 50L);
        recorder.recordRebuildTask(30L, 125L);
        recorder.recordBuildOutput(10L);
        recorder.recordBuildOutput(10L);
        recorder.recordBuildOutput(30L);
        recorder.recordAcceptedMeshPayloadBytes(120L);
        recorder.recordAcceptedMeshPayloadBytes(80L);
        recorder.recordBuilderWorkState(2, 1, 0);
        recorder.recordBuilderWorkState(5, 3, 2);
        recorder.recordBuilderWorkState(3, 2, 1);
        recorder.recordBuilderWorkState(-1, 0, 0);
        recorder.recordAcceptedMeshPayloadBytes(-1L);
        recorder.recordRebuildTask(40L, -1L);

        TorchEpochTelemetry.Snapshot active = recorder.snapshot();
        require(active.active(), "active epoch was not reported active");
        require(active.epochId() == 42L, "epoch ID mismatch");
        require(active.rebuildRequestCount() == 3L, "request count mismatch");
        require(active.uniqueRebuildRequestSections() == 2L, "unique request section count mismatch");
        require(active.rebuildTaskCount() == 3L, "task count mismatch");
        require(active.uniqueRebuildTaskSections() == 3L, "unique task section count mismatch");
        require(active.buildOutputCount() == 3L, "output count mismatch");
        require(active.uniqueBuildOutputSections() == 2L, "unique output section count mismatch");
        require(active.acceptedMeshPayloadBytes() == 200L, "mesh payload byte count mismatch");
        require(active.maximumBuilderQueueDepth() == 5, "maximum builder queue depth mismatch");
        require(active.finalBuilderQueueDepth() == 3, "final builder queue depth mismatch");
        require(active.maximumBusyWorkerCount() == 3, "maximum busy worker count mismatch");
        require(active.finalBusyWorkerCount() == 2, "final busy worker count mismatch");
        require(active.maximumPendingResultCount() == 2, "maximum pending result count mismatch");
        require(active.finalPendingResultCount() == 1, "final pending result count mismatch");
        require(active.maximumPendingAgeNanos() == 125L, "maximum pending age mismatch");
        require(active.errorCount() == 3L, "invalid samples were not counted as errors");
        require(active.overflowCount() == 0L, "ordinary epoch unexpectedly overflowed");

        TorchEpochTelemetry.Snapshot ended = recorder.end();
        require(!ended.active(), "ended epoch remained active");
        recorder.recordRebuildRequest(99L);
        require(recorder.end().equals(ended), "safe repeated end changed a closed epoch");
    }

    private static void testBoundedUniqueSectionTracking() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(2);
        recorder.begin(7L);
        recorder.recordRebuildRequest(0L);
        recorder.recordRebuildRequest(Long.MIN_VALUE);
        recorder.recordRebuildRequest(0L);
        recorder.recordRebuildRequest(Long.MAX_VALUE);

        TorchEpochTelemetry.Snapshot snapshot = recorder.end();
        require(snapshot.rebuildRequestCount() == 4L, "bounded recorder lost total requests");
        require(snapshot.uniqueRebuildRequestSections() == 2L, "bounded recorder exceeded its unique limit");
        require(snapshot.overflowCount() == 1L, "bounded recorder did not report unique overflow");
        require(snapshot.errorCount() == 0L, "bounded overflow was misreported as an error");
    }

    private static void testRestartAndAbortRecovery() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(4);
        recorder.begin(1L);
        recorder.recordRebuildRequest(1L);
        recorder.begin(2L);

        TorchEpochTelemetry.Snapshot restarted = recorder.snapshot();
        require(restarted.active(), "replacement epoch was not active");
        require(restarted.epochId() == 2L, "replacement epoch ID mismatch");
        require(restarted.rebuildRequestCount() == 0L, "replacement epoch retained old counters");
        require(restarted.errorCount() == 1L, "replacement of active epoch was not diagnosed");

        recorder.abort();
        TorchEpochTelemetry.Snapshot aborted = recorder.snapshot();
        require(!aborted.active(), "aborted epoch remained active");
        require(aborted.epochId() == 0L, "abort retained epoch ID");
        require(aborted.errorCount() == 0L, "abort retained diagnostics");
        require(aborted.rebuildRequestCount() == 0L, "abort retained lifecycle counters");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
