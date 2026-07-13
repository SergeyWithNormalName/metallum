package com.metallum.client.metal.render;

import org.jspecify.annotations.Nullable;

/** Render-thread stage markers used by Minecraft mixins when GPU timing is enabled. */
public final class MetalGpuTiming {
    private static final boolean ENABLED = "1".equals(System.getenv("METALLUM_GPU_TIMING"));

    private MetalGpuTiming() {
    }

    public static boolean isEnabled() {
        return ENABLED;
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
