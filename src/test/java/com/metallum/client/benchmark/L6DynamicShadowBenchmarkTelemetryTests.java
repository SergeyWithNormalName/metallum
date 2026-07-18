package com.metallum.client.benchmark;

public final class L6DynamicShadowBenchmarkTelemetryTests {
    private L6DynamicShadowBenchmarkTelemetryTests() {
    }

    public static void main(final String[] arguments) {
        aggregatesEveryMeasuredFrame();
        rejectsBrokenPreparedFrameInvariants();
        abortClearsPartialMeasurement();
        System.out.println("L6 dynamic-shadow benchmark telemetry tests passed");
    }

    private static void aggregatesEveryMeasuredFrame() {
        L6DynamicShadowBenchmarkTelemetry.Recorder recorder =
                L6DynamicShadowBenchmarkTelemetry.recorderForTests();
        recorder.begin();
        recorder.recordFrame(5, 2, 3, true, 1, 12_288, 2, 0, 0, 0, 393_216L);
        recorder.recordFrame(5, 2, 3, true, 1, 12_288, 2, 0, 0, 0, 393_216L);
        L6DynamicShadowBenchmarkTelemetry.Snapshot snapshot = recorder.end();

        require(!snapshot.active(), "ended snapshot must be inactive");
        require(snapshot.frames() == 2, "all render frames must be counted");
        require(snapshot.heldAdmittedFrames() == 2, "held admission must cover every frame");
        require(snapshot.heldReadyFrames() == 2, "held READY must cover every frame");
        require(snapshot.dispatchFrames() == 2, "one dynamic dispatch must cover every frame");
        require(snapshot.candidatesMin() == 5 && snapshot.candidatesMax() == 5,
                "candidate range must remain exact");
        require(snapshot.selectedMin() == 2 && snapshot.selectedMax() == 2,
                "selection range must remain exact");
        require(snapshot.droppedMin() == 3 && snapshot.droppedMax() == 3,
                "drop range must remain exact");
        require(snapshot.raysMin() == 12_288 && snapshot.raysMax() == 12_288,
                "ray range must remain exact");
        require(snapshot.readyMin() == 2 && snapshot.readyMax() == 2,
                "ready-page range must remain exact");
        require(snapshot.fallbackTotal() == 0L
                        && snapshot.coverageMissTotal() == 0L
                        && snapshot.asyncFailureTotal() == 0L,
                "successful measurement must contain no fallback or failure");
        require(snapshot.pageBytesMin() == 393_216L && snapshot.pageBytesMax() == 393_216L,
                "dynamic page-byte range must remain exact");

        recorder.recordFrame(1, 1, 0, true, 1, 1, 1, 0, 0, 0, 1L);
        require(recorder.snapshot().frames() == 2, "inactive recorder must ignore late frames");
    }

    private static void rejectsBrokenPreparedFrameInvariants() {
        L6DynamicShadowBenchmarkTelemetry.Recorder recorder =
                L6DynamicShadowBenchmarkTelemetry.recorderForTests();
        recorder.begin();
        expectIllegalArgument(
                () -> recorder.recordFrame(5, 2, 2, true, 1, 1, 2, 0, 0, 0, 1L),
                "candidate accounting"
        );
        expectIllegalArgument(
                () -> recorder.recordFrame(5, 2, 3, true, 1, 1, 1, 0, 0, 0, 1L),
                "ready/fallback accounting"
        );
    }

    private static void abortClearsPartialMeasurement() {
        L6DynamicShadowBenchmarkTelemetry.Recorder recorder =
                L6DynamicShadowBenchmarkTelemetry.recorderForTests();
        recorder.begin();
        recorder.recordFrame(5, 1, 4, true, 1, 1_536, 1, 0, 0, 0, 49_152L);
        recorder.abort();
        L6DynamicShadowBenchmarkTelemetry.Snapshot snapshot = recorder.snapshot();
        require(!snapshot.active() && snapshot.frames() == 0,
                "abort must clear and deactivate a partial measurement");
    }

    private static void expectIllegalArgument(final Runnable runnable, final String contract) {
        try {
            runnable.run();
            throw new AssertionError("expected rejection for " + contract);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
