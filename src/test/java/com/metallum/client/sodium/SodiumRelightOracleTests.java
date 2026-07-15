package com.metallum.client.sodium;

/** Dependency-free executable tests for the bounded oracle counter window. */
public final class SodiumRelightOracleTests {
    private SodiumRelightOracleTests() {
    }

    public static void main(final String[] args) {
        testObservationWindowIsolationAndReset();
        testDisabledOracleInvokesOriginalExactlyOnce();
        System.out.println("Sodium relight oracle tests passed");
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
