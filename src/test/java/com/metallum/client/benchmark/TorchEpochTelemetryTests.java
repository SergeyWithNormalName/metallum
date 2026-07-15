package com.metallum.client.benchmark;

public final class TorchEpochTelemetryTests {
    private TorchEpochTelemetryTests() {
    }

    public static void main(final String[] args) {
        testInactiveEpochIsANoOp();
        testLifecycleCountersAndSafeEnd();
        testSidecarCountersAndValidation();
        testBoundedUniqueSectionTracking();
        testRestartAndAbortRecovery();
    }

    private static void testInactiveEpochIsANoOp() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(4);
        recorder.recordRebuildRequest(1L);
        recorder.recordRebuildTask(1L, 99L);
        recorder.recordBuildOutput(1L);
        recorder.recordAcceptedMeshPayloadBytes(256L);
        recorder.recordAcceptedGeometryPayloadBytes(240L);
        recorder.recordSidecarUpload(64L, 1L);
        recorder.recordSidecarResizeCopies(32L, 2L);
        recorder.recordSidecarFallback();
        recorder.recordBuilderWorkState(9, 2, 1);
        recorder.recordError();

        TorchEpochTelemetry.Snapshot snapshot = recorder.snapshot();
        require(!snapshot.active(), "inactive recorder became active");
        require(snapshot.epochId() == 0L, "inactive recorder changed epoch ID");
        require(snapshot.rebuildRequestCount() == 0L, "inactive recorder counted requests");
        require(snapshot.rebuildTaskCount() == 0L, "inactive recorder counted tasks");
        require(snapshot.buildOutputCount() == 0L, "inactive recorder counted outputs");
        require(snapshot.acceptedMeshPayloadBytes() == 0L, "inactive recorder counted mesh bytes");
        require(snapshot.acceptedGeometryPayloadBytes() == 0L, "inactive recorder counted geometry bytes");
        require(snapshot.sidecarProducedBytes() == 0L, "inactive recorder counted sidecar production");
        require(snapshot.sidecarUploadedBytes() == 0L, "inactive recorder counted sidecar uploads");
        require(snapshot.sidecarUploadCommands() == 0L, "inactive recorder counted sidecar upload commands");
        require(snapshot.sidecarResizeCopyBytes() == 0L, "inactive recorder counted sidecar resize bytes");
        require(snapshot.sidecarResizeCopyCommands() == 0L, "inactive recorder counted sidecar resize commands");
        require(snapshot.sidecarFallbackCount() == 0L, "inactive recorder counted sidecar fallbacks");
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
        recorder.recordAcceptedGeometryPayloadBytes(100L);
        recorder.recordAcceptedGeometryPayloadBytes(60L);
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
        require(active.acceptedGeometryPayloadBytes() == 160L, "geometry payload byte count mismatch");
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

    private static void testSidecarCountersAndValidation() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(4);
        recorder.begin(84L);
        recorder.recordSidecarUpload(20L, 1L);
        recorder.recordSidecarUpload(36L, 2L);
        recorder.recordSidecarResizeCopies(100L, 3L);
        recorder.recordSidecarResizeCopies(20L, 1L);
        recorder.recordSidecarFallback();
        recorder.recordSidecarFallback();

        recorder.recordSidecarUpload(-1L, 1L);
        recorder.recordSidecarUpload(1L, -1L);
        recorder.recordSidecarResizeCopies(-1L, 0L);
        recorder.recordSidecarResizeCopies(0L, -1L);

        TorchEpochTelemetry.Snapshot snapshot = recorder.end();
        require(snapshot.sidecarProducedBytes() == 56L, "sidecar produced byte count mismatch");
        require(snapshot.sidecarUploadedBytes() == 56L, "sidecar uploaded byte count mismatch");
        require(snapshot.sidecarUploadCommands() == 3L, "sidecar upload command count mismatch");
        require(snapshot.sidecarResizeCopyBytes() == 120L, "sidecar resize byte count mismatch");
        require(snapshot.sidecarResizeCopyCommands() == 4L, "sidecar resize command count mismatch");
        require(snapshot.sidecarFallbackCount() == 2L, "sidecar fallback count mismatch");
        require(snapshot.errorCount() == 4L, "invalid sidecar samples were not counted as errors");
        require(snapshot.overflowCount() == 0L, "ordinary sidecar counters unexpectedly overflowed");
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
        require(aborted.sidecarUploadedBytes() == 0L, "abort retained sidecar counters");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
