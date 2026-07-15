package com.metallum.client.benchmark;

public final class TorchEpochTelemetryTests {
    private TorchEpochTelemetryTests() {
    }

    public static void main(final String[] args) {
        testInactiveEpochIsANoOp();
        testLifecycleCountersAndSafeEnd();
        testSidecarCountersAndValidation();
        testCompactLightPatchTransitionsAndIdentities();
        testBoundedUniqueSectionTracking();
        testCompactCounterOverflow();
        testRestartAndAbortRecovery();
    }

    private static void testInactiveEpochIsANoOp() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(4);
        recorder.recordRebuildRequest(1L);
        recorder.recordRebuildTask(1L, 99L);
        recorder.recordBuildOutput(1L);
        recorder.recordAcceptedMeshPayloadBytes(256L);
        recorder.recordAcceptedGeometryPayloadBytes(240L);
        recorder.recordInPlaceGeometryRefresh(1L, 200L, 2L);
        recorder.recordCompactLightPatchOutput(1L, 200L, 2L);
        recorder.recordNativeLightPatch(1L, 2L);
        recorder.recordCompactLightPatchFallback();
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
        require(snapshot.inPlaceGeometryRefreshOutputs() == 0L, "inactive recorder counted in-place outputs");
        require(snapshot.uniqueInPlaceGeometryRefreshSections() == 0L,
                "inactive recorder counted unique in-place sections");
        require(snapshot.inPlaceGeometryRefreshBytes() == 0L, "inactive recorder counted in-place bytes");
        require(snapshot.inPlaceGeometryRefreshMeshCommands() == 0L, "inactive recorder counted in-place commands");
        require(snapshot.compactLightPatchOutputs() == 0L, "inactive recorder counted compact outputs");
        require(snapshot.uniqueCompactLightPatchSections() == 0L,
                "inactive recorder counted unique compact sections");
        require(snapshot.geometryBytesElided() == 0L, "inactive recorder counted elided geometry bytes");
        require(snapshot.geometryMeshCommandsElided() == 0L,
                "inactive recorder counted elided geometry commands");
        require(snapshot.nativeLightPatchDispatches() == 0L,
                "inactive recorder counted native patch dispatches");
        require(snapshot.nativeLightPatchMeshCommands() == 0L,
                "inactive recorder counted native patch mesh commands");
        require(snapshot.compactLightPatchFallbackCount() == 0L,
                "inactive recorder counted compact local fallbacks");
        require(snapshot.sidecarProducedBytes() == 0L, "inactive recorder counted sidecar production");
        require(snapshot.sidecarUploadedBytes() == 0L, "inactive recorder counted sidecar uploads");
        require(snapshot.sidecarUploadCommands() == 0L, "inactive recorder counted sidecar upload commands");
        require(snapshot.sidecarResizeCopyBytes() == 0L, "inactive recorder counted sidecar resize bytes");
        require(snapshot.sidecarResizeCopyCommands() == 0L, "inactive recorder counted sidecar resize commands");
        require(snapshot.sidecarFallbackCount() == 0L, "inactive recorder counted sidecar fallbacks");
        require(snapshot.errorCount() == 0L, "inactive recorder counted errors");
        require(!recorder.wasBuildOutput(1L), "inactive recorder retained a build-output identity");
        require(!recorder.wasInPlaceGeometryRefreshed(1L), "inactive recorder retained an in-place identity");
        require(!recorder.wasCompactLightPatched(1L), "inactive recorder retained a compact identity");
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
        recorder.recordInPlaceGeometryRefresh(10L, 40L, 1L);
        recorder.recordInPlaceGeometryRefresh(30L, 80L, 2L);
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
        require(active.inPlaceGeometryRefreshOutputs() == 2L, "in-place output count mismatch");
        require(active.uniqueInPlaceGeometryRefreshSections() == 2L,
                "unique in-place section count mismatch");
        require(active.inPlaceGeometryRefreshBytes() == 120L, "in-place byte count mismatch");
        require(active.inPlaceGeometryRefreshMeshCommands() == 3L, "in-place command count mismatch");
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
        require(recorder.wasBuildOutput(10L), "closed epoch lost build-output identity");
        require(recorder.wasInPlaceGeometryRefreshed(30L), "closed epoch lost in-place identity");
        require(!recorder.wasInPlaceGeometryRefreshed(20L), "closed epoch invented in-place identity");
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
        recorder.recordInPlaceGeometryRefresh(2L, 240L, 2L);
        recorder.recordInPlaceGeometryRefresh(2L, 160L, 1L);

        recorder.recordSidecarUpload(-1L, 1L);
        recorder.recordSidecarUpload(1L, -1L);
        recorder.recordSidecarResizeCopies(-1L, 0L);
        recorder.recordSidecarResizeCopies(0L, -1L);
        recorder.recordInPlaceGeometryRefresh(0L, -1L, 0L);
        recorder.recordInPlaceGeometryRefresh(0L, 0L, -1L);
        recorder.recordCompactLightPatchOutput(0L, -1L, 0L);
        recorder.recordCompactLightPatchOutput(0L, 0L, -1L);
        recorder.recordNativeLightPatch(-1L, 0L);
        recorder.recordNativeLightPatch(0L, -1L);

        TorchEpochTelemetry.Snapshot snapshot = recorder.end();
        require(snapshot.sidecarProducedBytes() == 56L, "sidecar produced byte count mismatch");
        require(snapshot.sidecarUploadedBytes() == 56L, "sidecar uploaded byte count mismatch");
        require(snapshot.sidecarUploadCommands() == 3L, "sidecar upload command count mismatch");
        require(snapshot.sidecarResizeCopyBytes() == 120L, "sidecar resize byte count mismatch");
        require(snapshot.sidecarResizeCopyCommands() == 4L, "sidecar resize command count mismatch");
        require(snapshot.sidecarFallbackCount() == 2L, "sidecar fallback count mismatch");
        require(snapshot.inPlaceGeometryRefreshOutputs() == 2L, "in-place output counter mismatch");
        require(snapshot.uniqueInPlaceGeometryRefreshSections() == 1L,
                "duplicate in-place section was counted twice");
        require(snapshot.inPlaceGeometryRefreshBytes() == 400L, "in-place byte counter mismatch");
        require(snapshot.inPlaceGeometryRefreshMeshCommands() == 3L, "in-place command counter mismatch");
        require(snapshot.errorCount() == 10L, "invalid sidecar samples were not counted as errors");
        require(snapshot.overflowCount() == 0L, "ordinary sidecar counters unexpectedly overflowed");
    }

    private static void testCompactLightPatchTransitionsAndIdentities() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(4);
        recorder.begin(126L);
        recorder.recordBuildOutput(10L);
        recorder.recordBuildOutput(20L);
        recorder.recordInPlaceGeometryRefresh(10L, 60L, 1L);
        recorder.recordInPlaceGeometryRefresh(20L, 80L, 2L);
        recorder.recordCompactLightPatchOutput(20L, 80L, 2L);
        recorder.recordNativeLightPatch(1L, 2L);
        recorder.recordInPlaceGeometryRefresh(20L, 40L, 1L);
        recorder.recordCompactLightPatchOutput(20L, 40L, 1L);
        recorder.recordNativeLightPatch(1L, 1L);
        recorder.recordCompactLightPatchFallback();
        recorder.recordCompactLightPatchFallback();

        TorchEpochTelemetry.Snapshot active = recorder.snapshot();
        require(active.inPlaceGeometryRefreshOutputs() == 3L,
                "compact refreshes were not retained in the in-place union");
        require(active.uniqueInPlaceGeometryRefreshSections() == 2L,
                "in-place union identity count mismatch");
        require(active.compactLightPatchOutputs() == 2L, "compact output count mismatch");
        require(active.uniqueCompactLightPatchSections() == 1L,
                "duplicate compact section was counted twice");
        require(active.geometryBytesElided() == 120L, "elided geometry byte count mismatch");
        require(active.geometryMeshCommandsElided() == 3L,
                "elided geometry command count mismatch");
        require(active.nativeLightPatchDispatches() == 2L,
                "native light-patch dispatch count mismatch");
        require(active.nativeLightPatchMeshCommands() == 3L,
                "native light-patch mesh command count mismatch");
        require(active.compactLightPatchFallbackCount() == 2L,
                "compact local fallback count mismatch");
        require(recorder.wasCompactLightPatched(20L), "active epoch lost compact identity");
        require(!recorder.wasCompactLightPatched(10L), "full refresh acquired compact identity");

        TorchEpochTelemetry.Snapshot ended = recorder.end();
        require(recorder.wasCompactLightPatched(20L), "closed epoch lost compact identity");
        recorder.recordCompactLightPatchOutput(30L, 20L, 1L);
        recorder.recordNativeLightPatch(1L, 1L);
        recorder.recordCompactLightPatchFallback();
        require(recorder.end().equals(ended), "inactive compact record changed a closed epoch");
    }

    private static void testBoundedUniqueSectionTracking() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(2);
        recorder.begin(7L);
        recorder.recordRebuildRequest(0L);
        recorder.recordRebuildRequest(Long.MIN_VALUE);
        recorder.recordRebuildRequest(0L);
        recorder.recordRebuildRequest(Long.MAX_VALUE);
        recorder.recordInPlaceGeometryRefresh(0L, 20L, 1L);
        recorder.recordInPlaceGeometryRefresh(Long.MIN_VALUE, 20L, 1L);
        recorder.recordInPlaceGeometryRefresh(Long.MAX_VALUE, 20L, 1L);
        recorder.recordCompactLightPatchOutput(0L, 20L, 1L);
        recorder.recordCompactLightPatchOutput(Long.MIN_VALUE, 20L, 1L);
        recorder.recordCompactLightPatchOutput(Long.MAX_VALUE, 20L, 1L);

        TorchEpochTelemetry.Snapshot snapshot = recorder.end();
        require(snapshot.rebuildRequestCount() == 4L, "bounded recorder lost total requests");
        require(snapshot.uniqueRebuildRequestSections() == 2L, "bounded recorder exceeded its unique limit");
        require(snapshot.uniqueInPlaceGeometryRefreshSections() == 2L,
                "bounded recorder exceeded its in-place unique limit");
        require(snapshot.uniqueCompactLightPatchSections() == 2L,
                "bounded recorder exceeded its compact unique limit");
        require(snapshot.overflowCount() == 3L, "bounded recorder did not report unique overflows");
        require(snapshot.errorCount() == 0L, "bounded overflow was misreported as an error");
    }

    private static void testCompactCounterOverflow() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(2);
        recorder.begin(168L);
        recorder.recordCompactLightPatchOutput(1L, Long.MAX_VALUE, Long.MAX_VALUE);
        recorder.recordCompactLightPatchOutput(1L, 1L, 1L);
        recorder.recordNativeLightPatch(Long.MAX_VALUE, Long.MAX_VALUE);
        recorder.recordNativeLightPatch(1L, 1L);

        TorchEpochTelemetry.Snapshot snapshot = recorder.end();
        require(snapshot.compactLightPatchOutputs() == 2L,
                "numeric overflow lost compact output identities");
        require(snapshot.uniqueCompactLightPatchSections() == 1L,
                "numeric overflow changed compact unique identities");
        require(snapshot.geometryBytesElided() == Long.MAX_VALUE,
                "elided geometry byte overflow did not saturate");
        require(snapshot.geometryMeshCommandsElided() == Long.MAX_VALUE,
                "elided geometry command overflow did not saturate");
        require(snapshot.nativeLightPatchDispatches() == Long.MAX_VALUE,
                "native dispatch overflow did not saturate");
        require(snapshot.nativeLightPatchMeshCommands() == Long.MAX_VALUE,
                "native mesh-command overflow did not saturate");
        require(snapshot.overflowCount() == 4L, "compact numeric overflows were not diagnosed");
        require(snapshot.errorCount() == 0L, "compact numeric overflow was misreported as an error");
    }

    private static void testRestartAndAbortRecovery() {
        TorchEpochTelemetry.Recorder recorder = TorchEpochTelemetry.recorderForTests(4);
        recorder.begin(1L);
        recorder.recordRebuildRequest(1L);
        recorder.recordBuildOutput(1L);
        recorder.recordInPlaceGeometryRefresh(1L, 20L, 1L);
        recorder.recordCompactLightPatchOutput(1L, 20L, 1L);
        recorder.begin(2L);

        TorchEpochTelemetry.Snapshot restarted = recorder.snapshot();
        require(restarted.active(), "replacement epoch was not active");
        require(restarted.epochId() == 2L, "replacement epoch ID mismatch");
        require(restarted.rebuildRequestCount() == 0L, "replacement epoch retained old counters");
        require(!recorder.wasBuildOutput(1L), "replacement epoch retained build-output identity");
        require(!recorder.wasInPlaceGeometryRefreshed(1L), "replacement epoch retained in-place identity");
        require(!recorder.wasCompactLightPatched(1L), "replacement epoch retained compact identity");
        require(restarted.errorCount() == 1L, "replacement of active epoch was not diagnosed");

        recorder.abort();
        TorchEpochTelemetry.Snapshot aborted = recorder.snapshot();
        require(!aborted.active(), "aborted epoch remained active");
        require(aborted.epochId() == 0L, "abort retained epoch ID");
        require(aborted.errorCount() == 0L, "abort retained diagnostics");
        require(aborted.rebuildRequestCount() == 0L, "abort retained lifecycle counters");
        require(aborted.sidecarUploadedBytes() == 0L, "abort retained sidecar counters");
        require(aborted.inPlaceGeometryRefreshBytes() == 0L, "abort retained in-place counters");
        require(aborted.geometryBytesElided() == 0L, "abort retained compact counters");
        require(!recorder.wasBuildOutput(1L), "abort retained a build-output identity");
        require(!recorder.wasInPlaceGeometryRefreshed(1L), "abort retained an in-place identity");
        require(!recorder.wasCompactLightPatched(1L), "abort retained a compact identity");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
