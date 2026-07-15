package com.metallum.client.sodium;

/** Dependency-free executable tests for the bounded oracle counter window. */
public final class SodiumRelightOracleTests {
    private SodiumRelightOracleTests() {
    }

    public static void main(final String[] args) {
        testObservationWindowIsolationAndReset();
        testExactTaskCauseScopesFailClosed();
        testFastObservationAccounting();
        testDisabledOracleInvokesOriginalExactlyOnce();
        System.out.println("Sodium relight oracle tests passed");
    }

    private static void testExactTaskCauseScopesFailClosed() {
        SodiumRelightCauseTracker.clear();
        require(SodiumRelightCauseTracker.classify(1, 2, 3)
                        == SodiumRelightRebuildCause.GEOMETRY_OR_UNKNOWN,
                "unscoped rebuild was classified as light-only");
        try (SodiumRelightCauseTracker.Scope exact =
                     SodiumRelightCauseTracker.openExact(1, 2, 3)) {
            require(SodiumRelightCauseTracker.classify(1, 2, 3)
                            == SodiumRelightRebuildCause.LIGHT_ONLY,
                    "exact light scope rejected its target section");
            require(SodiumRelightCauseTracker.classify(2, 2, 3)
                            == SodiumRelightRebuildCause.GEOMETRY_OR_UNKNOWN,
                    "exact light scope admitted a neighbor");
            try (SodiumRelightCauseTracker.Scope neighbors =
                         SodiumRelightCauseTracker.openNeighbors(4, 5, 6)) {
                require(SodiumRelightCauseTracker.classify(3, 4, 5)
                                == SodiumRelightRebuildCause.LIGHT_ONLY,
                        "neighbor light scope rejected its halo");
                require(SodiumRelightCauseTracker.classify(2, 4, 5)
                                == SodiumRelightRebuildCause.GEOMETRY_OR_UNKNOWN,
                        "neighbor light scope escaped its halo");
            }
            require(SodiumRelightCauseTracker.classify(1, 2, 3)
                            == SodiumRelightRebuildCause.LIGHT_ONLY,
                    "nested scope did not restore its parent");
        }
        require(SodiumRelightCauseTracker.classify(1, 2, 3)
                        == SodiumRelightRebuildCause.GEOMETRY_OR_UNKNOWN,
                "closed light scope leaked into a later rebuild");
        require(SodiumRelightRebuildCause.LIGHT_ONLY.merge(
                        SodiumRelightRebuildCause.GEOMETRY_OR_UNKNOWN
                ) == SodiumRelightRebuildCause.GEOMETRY_OR_UNKNOWN,
                "geometry cause did not dominate light-only coalescing");
    }

    private static void testFastObservationAccounting() {
        SodiumRelightFastPath.Recorder recorder = new SodiumRelightFastPath.Recorder();
        recorder.recordCreated(10L);
        require(recorder.snapshot().taskDecisions() == 0L,
                "inactive fast observation recorded a task");
        recorder.begin(81L);
        recorder.recordCreated(40L);
        recorder.recordFallback(SodiumRelightFastPath.Fallback.TOPOLOGY);
        recorder.recordCancelled();
        recorder.recordOriginalCall();
        recorder.recordAccepted();
        recorder.recordCompactCommit();
        recorder.recordGenerationMismatch();
        SodiumRelightFastPath.Snapshot snapshot = recorder.end();
        require(snapshot.epochId() == 81L, "fast observation epoch changed");
        require(snapshot.taskDecisions() == 3L
                        && snapshot.fastOutputsCreated() == 1L
                        && snapshot.fallbackToOriginal() == 1L
                        && snapshot.cancelledTasks() == 1L,
                "fast task decision partition changed");
        require(snapshot.originalCalls() == 1L,
                "fast fallback original-call accounting changed");
        require(snapshot.acceptedOutputs() == 1L && snapshot.compactCommits() == 1L,
                "fast accepted/commit accounting changed");
        require(snapshot.topologyFallbacks() == 1L
                        && snapshot.generationMismatches() == 1L,
                "fast fail-closed reason accounting changed");
        require(snapshot.createdGeometryBytes() == 40L,
                "fast reconstructed byte accounting changed");
        recorder.begin(82L);
        require(recorder.snapshot().taskDecisions() == 0L,
                "new fast observation did not reset counters");
        recorder.abort();
        require(recorder.snapshot().epochId() == 0L,
                "fast observation abort did not clear its epoch");
    }

    private static void testObservationWindowIsolationAndReset() {
        SodiumRelightOracle.ObservationRecorder recorder =
                new SodiumRelightOracle.ObservationRecorder();
        recorder.recordTask(true);
        require(recorder.snapshot().tasks() == 0L, "inactive observation recorded a task");

        recorder.begin(73L);
        recorder.recordTask(false);
        recorder.recordTask(true);
        recorder.recordCapturedPlan(4L);
        recorder.recordCapturedQuad();
        recorder.recordReplayAttempt();
        recorder.recordReplayMatch();
        recorder.recordMismatchedTask();
        recorder.recordByteMismatch();
        recorder.recordStaticShadowMismatch();
        recorder.recordStaticShadowRejection();
        recorder.recordRejectedTask();
        recorder.recordScopeFailure();
        recorder.recordStaleCandidate();
        recorder.recordDiscardedCandidate();
        recorder.recordPublishedCandidate();
        recorder.recordError();

        SodiumRelightOracle.Snapshot snapshot = recorder.end();
        require(snapshot.epochId() == 73L, "observation epoch changed");
        require(snapshot.tasks() == 2L && snapshot.lightOnlyTasks() == 1L,
                "task counters changed");
        require(snapshot.capturedPlans() == 1L && snapshot.capturedQuads() == 1L,
                "capture counters changed");
        require(snapshot.replayAttempts() == 1L && snapshot.replayMatches() == 1L,
                "replay counters changed");
        require(snapshot.mismatchedTasks() == 1L && snapshot.byteMismatches() == 1L,
                "byte mismatch counters changed");
        require(snapshot.staticShadowMismatches() == 1L
                        && snapshot.staticShadowRejections() == 1L,
                "static shadow counters changed");
        require(snapshot.rejectedTasks() == 1L && snapshot.scopeFailures() == 1L,
                "rejection counters changed");
        require(snapshot.staleCandidates() == 1L
                        && snapshot.discardedCandidates() == 1L
                        && snapshot.publishedCandidates() == 1L,
                "candidate lifecycle counters changed");
        require(snapshot.errors() == 1L, "error counter changed");
        require(snapshot.skippedFullRemeshes() == 0L,
                "diagnostic oracle exposed a skipped full remesh");

        recorder.recordTask(true);
        require(recorder.snapshot().tasks() == 2L, "closed observation kept recording");
        recorder.begin(74L);
        require(recorder.snapshot().tasks() == 0L, "new observation did not reset counters");
        recorder.abort();
        require(recorder.snapshot().epochId() == 0L && recorder.snapshot().tasks() == 0L,
                "observation abort did not clear counters");
    }

    private static void testDisabledOracleInvokesOriginalExactlyOnce() {
        require(!SodiumRelightOracle.isConfigured(),
                "unit test must run with METALLUM_SODIUM_RELIGHT_ORACLE=0");
        int[] calls = {0};
        SodiumRelightOracle.executeMeshingTask(
                null,
                null,
                null,
                arguments -> {
                    calls[0]++;
                    require(arguments.length == 2, "original task arguments changed");
                    return null;
                }
        );
        require(calls[0] == 1, "disabled oracle did not invoke the full remesh exactly once");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
