package com.metallum.client.metal.render;

import org.jspecify.annotations.Nullable;

/** Render-thread stage markers used by Minecraft mixins in detailed GPU timing mode. */
public final class MetalGpuTiming {
    private static final boolean ENABLED = detailEnabled(
            System.getenv("METALLUM_GPU_TIMING"),
            System.getenv("METALLUM_GPU_TIMING_DETAIL")
    );

    private MetalGpuTiming() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    static boolean detailEnabled(final String timing, final String detail) {
        return "1".equals(timing) && "1".equals(detail);
    }

    public static void begin(final MetalGpuTimingStage stage) {
        if (!ENABLED || stage == null || stage == MetalGpuTimingStage.NONE) {
            return;
        }
        MetalDevice device = MetalDevice.getInstance();
        if (device != null) {
            device.setGpuTimingStage(stage);
        }
    }

    public static void end() {
        if (!ENABLED) {
            return;
        }
        MetalDevice device = MetalDevice.getInstance();
        if (device != null) {
            device.setGpuTimingStage(MetalGpuTimingStage.NONE);
        }
    }

    static @Nullable MetalGpuTimingStage currentStageForTests() {
        MetalDevice device = MetalDevice.getInstance();
        return device == null ? null : device.gpuTimingStageForTests();
    }
}
