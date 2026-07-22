package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

/** Render-thread stage markers used by Minecraft mixins in detailed GPU timing mode. */
public final class MetalGpuTiming {
    private static final int BENCHMARK_PHASE_WARMUP = 1;
    private static final int BENCHMARK_PHASE_MEASURE = 2;
    private static final int BENCHMARK_PHASE_COMPLETE = 3;
    private static final boolean REPORT_ENABLED = timingEnabled(System.getenv("METALLUM_GPU_TIMING"));
    private static final boolean DETAIL_ENABLED = detailEnabled(
            System.getenv("METALLUM_GPU_TIMING"),
            System.getenv("METALLUM_GPU_TIMING_DETAIL")
    );

    private MetalGpuTiming() {
    }

    public static boolean isEnabled() {
        return DETAIL_ENABLED;
    }

    /** Lightweight report/JSONL mode; unlike {@link #isEnabled()}, this does not imply stage detail. */
    static boolean isReportEnabled() {
        return REPORT_ENABLED;
    }

    static boolean timingEnabled(final String timing) {
        return "1".equals(timing);
    }

    static boolean detailEnabled(final String timing, final String detail) {
        return "1".equals(timing) && "1".equals(detail);
    }

    static @Nullable MetalJavaWorkloadTelemetry createJavaWorkloadTelemetry() {
        return createJavaWorkloadTelemetry(REPORT_ENABLED);
    }

    static @Nullable MetalJavaWorkloadTelemetry createJavaWorkloadTelemetry(final boolean enabled) {
        return enabled ? new MetalJavaWorkloadTelemetry() : null;
    }

    public static void begin(final MetalGpuTimingStage stage) {
        if (!DETAIL_ENABLED || stage == null || stage == MetalGpuTimingStage.NONE) {
            return;
        }
        MetalDevice device = MetalDevice.getInstance();
        if (device != null) {
            device.setGpuTimingStage(stage);
        }
    }

    public static void end() {
        if (!DETAIL_ENABLED) {
            return;
        }
        MetalDevice device = MetalDevice.getInstance();
        if (device != null) {
            device.setGpuTimingStage(MetalGpuTimingStage.NONE);
        }
    }

    public static void beginBenchmarkWarmup(final int segmentIndex, final String mode) {
        setBenchmarkState(segmentIndex, BENCHMARK_PHASE_WARMUP, mode);
    }

    public static void beginBenchmarkMeasurement(final int segmentIndex, final String mode) {
        setBenchmarkState(segmentIndex, BENCHMARK_PHASE_MEASURE, mode);
    }

    public static void completeBenchmark(final int segmentIndex, final String mode) {
        setBenchmarkState(segmentIndex, BENCHMARK_PHASE_COMPLETE, mode);
    }

    private static void setBenchmarkState(
            final int segmentIndex,
            final int phase,
            final String mode
    ) {
        if (!REPORT_ENABLED || mode == null || mode.isBlank()) {
            return;
        }
        MetalNativeBridge.metallum_gpu_timing_set_benchmark_state(segmentIndex, phase, mode);
    }

    static @Nullable MetalGpuTimingStage currentStageForTests() {
        MetalDevice device = MetalDevice.getInstance();
        return device == null ? null : device.gpuTimingStageForTests();
    }
}
