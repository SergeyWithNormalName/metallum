package com.metallum.client.benchmark;

/**
 * Render-thread-confined coverage proof for the deterministic L6 dynamic-shadow route.
 *
 * <p>Metal timestamp queries are deliberately not used as a per-frame liveness oracle:
 * API/Shader Validation can occasionally return an invalid timestamp sample even though
 * the command was encoded. This recorder instead observes the prepared L6 frame before
 * submission and proves that the held light had a ready dynamic page on every measured
 * frame. GPU timestamps remain the source of the compute-stage duration.</p>
 */
public final class L6DynamicShadowBenchmarkTelemetry {
    private static final Recorder GLOBAL = new Recorder();

    private L6DynamicShadowBenchmarkTelemetry() {
    }

    public static void begin() {
        GLOBAL.begin();
    }

    public static Snapshot end() {
        return GLOBAL.end();
    }

    public static void abort() {
        GLOBAL.abort();
    }

    public static boolean isActive() {
        return GLOBAL.active;
    }

    public static void recordFrame(
            final int candidates,
            final int selected,
            final int dropped,
            final boolean heldAdmitted,
            final int dispatches,
            final int rays,
            final int ready,
            final int fallback,
            final int coverageMisses,
            final int asyncFailures,
            final long pageBytes
    ) {
        GLOBAL.recordFrame(
                candidates,
                selected,
                dropped,
                heldAdmitted,
                dispatches,
                rays,
                ready,
                fallback,
                coverageMisses,
                asyncFailures,
                pageBytes
        );
    }

    static Recorder recorderForTests() {
        return new Recorder();
    }

    public record Snapshot(
            boolean active,
            int frames,
            int heldAdmittedFrames,
            int heldReadyFrames,
            int dispatchFrames,
            int candidatesMin,
            int candidatesMax,
            int selectedMin,
            int selectedMax,
            int droppedMin,
            int droppedMax,
            int raysMin,
            int raysMax,
            int readyMin,
            int readyMax,
            long fallbackTotal,
            long coverageMissTotal,
            long asyncFailureTotal,
            long pageBytesMin,
            long pageBytesMax
    ) {
    }

    static final class Recorder {
        private boolean active;
        private int frames;
        private int heldAdmittedFrames;
        private int heldReadyFrames;
        private int dispatchFrames;
        private int candidatesMin;
        private int candidatesMax;
        private int selectedMin;
        private int selectedMax;
        private int droppedMin;
        private int droppedMax;
        private int raysMin;
        private int raysMax;
        private int readyMin;
        private int readyMax;
        private long fallbackTotal;
        private long coverageMissTotal;
        private long asyncFailureTotal;
        private long pageBytesMin;
        private long pageBytesMax;

        void begin() {
            reset(true);
        }

        Snapshot end() {
            Snapshot snapshot = snapshot();
            this.active = false;
            return new Snapshot(
                    false,
                    snapshot.frames(),
                    snapshot.heldAdmittedFrames(),
                    snapshot.heldReadyFrames(),
                    snapshot.dispatchFrames(),
                    snapshot.candidatesMin(),
                    snapshot.candidatesMax(),
                    snapshot.selectedMin(),
                    snapshot.selectedMax(),
                    snapshot.droppedMin(),
                    snapshot.droppedMax(),
                    snapshot.raysMin(),
                    snapshot.raysMax(),
                    snapshot.readyMin(),
                    snapshot.readyMax(),
                    snapshot.fallbackTotal(),
                    snapshot.coverageMissTotal(),
                    snapshot.asyncFailureTotal(),
                    snapshot.pageBytesMin(),
                    snapshot.pageBytesMax()
            );
        }

        void abort() {
            reset(false);
        }

        void recordFrame(
                final int candidates,
                final int selected,
                final int dropped,
                final boolean heldAdmitted,
                final int dispatches,
                final int rays,
                final int ready,
                final int fallback,
                final int coverageMisses,
                final int asyncFailures,
                final long pageBytes
        ) {
            if (!this.active) {
                return;
            }
            requireNonNegative(candidates, "candidates");
            requireNonNegative(selected, "selected");
            requireNonNegative(dropped, "dropped");
            requireNonNegative(dispatches, "dispatches");
            requireNonNegative(rays, "rays");
            requireNonNegative(ready, "ready");
            requireNonNegative(fallback, "fallback");
            requireNonNegative(coverageMisses, "coverageMisses");
            requireNonNegative(asyncFailures, "asyncFailures");
            if (pageBytes < 0L) {
                throw new IllegalArgumentException("pageBytes must be non-negative");
            }
            if (candidates != selected + dropped) {
                throw new IllegalArgumentException("candidates must equal selected + dropped");
            }
            if (selected != ready + fallback) {
                throw new IllegalArgumentException("selected must equal ready + fallback");
            }

            if (this.frames == 0) {
                this.candidatesMin = candidates;
                this.candidatesMax = candidates;
                this.selectedMin = selected;
                this.selectedMax = selected;
                this.droppedMin = dropped;
                this.droppedMax = dropped;
                this.raysMin = rays;
                this.raysMax = rays;
                this.readyMin = ready;
                this.readyMax = ready;
                this.pageBytesMin = pageBytes;
                this.pageBytesMax = pageBytes;
            } else {
                this.candidatesMin = Math.min(this.candidatesMin, candidates);
                this.candidatesMax = Math.max(this.candidatesMax, candidates);
                this.selectedMin = Math.min(this.selectedMin, selected);
                this.selectedMax = Math.max(this.selectedMax, selected);
                this.droppedMin = Math.min(this.droppedMin, dropped);
                this.droppedMax = Math.max(this.droppedMax, dropped);
                this.raysMin = Math.min(this.raysMin, rays);
                this.raysMax = Math.max(this.raysMax, rays);
                this.readyMin = Math.min(this.readyMin, ready);
                this.readyMax = Math.max(this.readyMax, ready);
                this.pageBytesMin = Math.min(this.pageBytesMin, pageBytes);
                this.pageBytesMax = Math.max(this.pageBytesMax, pageBytes);
            }

            this.frames++;
            if (heldAdmitted) {
                this.heldAdmittedFrames++;
            }
            if (dispatches > 0) {
                this.dispatchFrames++;
            }
            if (heldAdmitted
                    && dispatches == 1
                    && ready == selected
                    && fallback == 0
                    && coverageMisses == 0
                    && asyncFailures == 0) {
                this.heldReadyFrames++;
            }
            this.fallbackTotal = Math.addExact(this.fallbackTotal, fallback);
            this.coverageMissTotal = Math.addExact(this.coverageMissTotal, coverageMisses);
            this.asyncFailureTotal = Math.addExact(this.asyncFailureTotal, asyncFailures);
        }

        Snapshot snapshot() {
            return new Snapshot(
                    this.active,
                    this.frames,
                    this.heldAdmittedFrames,
                    this.heldReadyFrames,
                    this.dispatchFrames,
                    this.candidatesMin,
                    this.candidatesMax,
                    this.selectedMin,
                    this.selectedMax,
                    this.droppedMin,
                    this.droppedMax,
                    this.raysMin,
                    this.raysMax,
                    this.readyMin,
                    this.readyMax,
                    this.fallbackTotal,
                    this.coverageMissTotal,
                    this.asyncFailureTotal,
                    this.pageBytesMin,
                    this.pageBytesMax
            );
        }

        private void reset(final boolean active) {
            this.active = active;
            this.frames = 0;
            this.heldAdmittedFrames = 0;
            this.heldReadyFrames = 0;
            this.dispatchFrames = 0;
            this.candidatesMin = 0;
            this.candidatesMax = 0;
            this.selectedMin = 0;
            this.selectedMax = 0;
            this.droppedMin = 0;
            this.droppedMax = 0;
            this.raysMin = 0;
            this.raysMax = 0;
            this.readyMin = 0;
            this.readyMax = 0;
            this.fallbackTotal = 0L;
            this.coverageMissTotal = 0L;
            this.asyncFailureTotal = 0L;
            this.pageBytesMin = 0L;
            this.pageBytesMax = 0L;
        }

        private static void requireNonNegative(final int value, final String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be non-negative");
            }
        }
    }
}
