package com.metallum.client.metalfx;

import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.renderer.interpolation.FrameInterpolationRuntimeStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/** Fail-isolated HUD overlay showing real-time FPS and 3D render resolution telemetry. */
public final class DrsResolutionOverlayHud {
    private static int frameCount = 0;
    private static int currentFps = 0;
    private static long lastFpsUpdateNanos = 0;
    private static final GeneratedFrameRateTracker GENERATED_FRAMES = new GeneratedFrameRateTracker();
    private static boolean generatedTelemetryAvailable = true;
    private static FrameInterpolationRuntimeStatus frameInterpolationStatus =
            new FrameInterpolationRuntimeStatus(
                    FrameInterpolationRuntimeStatus.State.DISABLED,
                    FrameInterpolationRuntimeStatus.Reason.USER_DISABLED,
                    0L,
                    0L
            );
    private static final FrameInterpolationRuntimeStatus NO_DEVICE_STATUS =
            new FrameInterpolationRuntimeStatus(
                    FrameInterpolationRuntimeStatus.State.DISABLED,
                    FrameInterpolationRuntimeStatus.Reason.USER_DISABLED,
                    0L,
                    0L
            );

    private DrsResolutionOverlayHud() {
    }

    private static int calculateFps() {
        long now = System.nanoTime();
        frameCount++;
        if (lastFpsUpdateNanos == 0) {
            lastFpsUpdateNanos = now;
        } else {
            long elapsed = now - lastFpsUpdateNanos;
            if (elapsed >= 500_000_000L) { // Update twice per second
                currentFps = Math.round((frameCount * 1_000_000_000.0f) / elapsed);
                frameCount = 0;
                lastFpsUpdateNanos = now;
            }
        }
        return currentFps;
    }

    private static void sampleGeneratedFrames(
            final long nowNanos,
            final MetalDevice device
    ) {
        if (!GENERATED_FRAMES.shouldSample(nowNanos)) {
            return;
        }
        frameInterpolationStatus = device.frameInterpolationRuntimeStatus();
        if (!generatedTelemetryAvailable) {
            return;
        }
        try {
            GENERATED_FRAMES.observe(
                    nowNanos,
                    frameInterpolationStatus.sessionId(),
                    MetalNativeBridge.metallum_frame_interpolation_presented_generated_count_v1()
            );
        } catch (RuntimeException | LinkageError unavailable) {
            // The overlay must never make an otherwise usable fallback
            // renderer fail because native telemetry is unavailable.
            generatedTelemetryAvailable = false;
        }
    }

    public static void render(final GuiGraphicsExtractor graphics) {
        if (!MetalFxUpscaling.isResolutionOverlayEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null || minecraft.font == null) {
            return;
        }

        int fps = calculateFps();
        MetalDevice device = MetalDevice.getInstance();
        if (device != null) {
            sampleGeneratedFrames(System.nanoTime(), device);
        } else {
            frameInterpolationStatus = NO_DEVICE_STATUS;
        }
        String generatedLabel = generatedTelemetryAvailable
                ? String.format(
                        Locale.ROOT,
                        "Generated shown: %d FPS (%d this launch)",
                        GENERATED_FRAMES.framesPerSecond(),
                        GENERATED_FRAMES.presentedCount()
                )
                : "Generated shown: unavailable";
        String fiLabel = frameInterpolationLabel(frameInterpolationStatus);
        int displayWidth = minecraft.getWindow().getWidth();
        int displayHeight = minecraft.getWindow().getHeight();
        MetalFxUpscaling.Dimensions dims = MetalFxUpscaling.effectiveDimensions(displayWidth, displayHeight);

        String renderLabel;
        if (MetalFxUpscaling.isActive()) {
            boolean dynamic = MetallumDrsController.isEnabled();
            renderLabel = dynamic
                    ? String.format(
                            Locale.ROOT,
                            "Render FPS: %d | DRS: %dx%d (%.0f%%) → %dx%d [%s, GPU %.1f ms]",
                            fps,
                            dims.renderWidth(), dims.renderHeight(), dims.actualWidthScale() * 100.0f,
                            dims.displayWidth(), dims.displayHeight(), MetalFxUpscaling.activeType().name(),
                            MetallumDrsController.emaGpuTimeMs()
                    )
                    : String.format(
                            Locale.ROOT,
                            "Render FPS: %d | MetalFX: %dx%d (%.0f%%) → %dx%d [%s]",
                            fps,
                            dims.renderWidth(), dims.renderHeight(), dims.actualWidthScale() * 100.0f,
                            dims.displayWidth(), dims.displayHeight(), MetalFxUpscaling.activeType().name()
                    );
        } else {
            renderLabel = String.format(
                    Locale.ROOT,
                    "Render FPS: %d | Render: %dx%d (100%% Native)",
                    fps,
                    displayWidth,
                    displayHeight
            );
        }

        int textWidth = Math.max(
                minecraft.font.width(fiLabel),
                Math.max(
                        minecraft.font.width(generatedLabel),
                        minecraft.font.width(renderLabel)
                )
        );
        int x = 10;
        int y = 10;
        graphics.fill(x - 4, y - 4, x + textWidth + 4, y + 36, 0xd0000000);
        graphics.text(minecraft.font, fiLabel, x, y, 0xff00ff88, true);
        graphics.text(minecraft.font, generatedLabel, x, y + 12, 0xff00ff88, true);
        graphics.text(minecraft.font, renderLabel, x, y + 24, 0xff00ff88, true);
    }

    private static String frameInterpolationLabel(
            final FrameInterpolationRuntimeStatus status
    ) {
        return switch (status.state()) {
            case DISABLED -> "FI: Off";
            case WARMING -> status.reason()
                    == FrameInterpolationRuntimeStatus.Reason.AWAITING_PRODUCTION_SOURCE
                    ? "FI: Warming — waiting for world"
                    : "FI: Warming — measuring shown cadence";
            case ACTIVE -> "FI: Active — 30 real → 60 shown";
            case UNAVAILABLE -> "FI: Unavailable — "
                    + unavailableReason(status.reason()) + "; real-only"
                    + (requiresRestartToRetry(status.reason()) ? "; restart to retry" : "");
        };
    }

    private static String unavailableReason(
            final FrameInterpolationRuntimeStatus.Reason reason
    ) {
        return switch (reason) {
            case FRAME_INTERPOLATION_UNSUPPORTED -> "MetalFX FI unsupported";
            case TEMPORAL_UNSUPPORTED -> "fixed Temporal inputs unsupported";
            case NATIVE_PROFILE_UNVALIDATED -> "native profile unavailable";
            case DISPLAY_SYNC_DISABLED -> "VSync is off";
            case DISPLAY_REFRESH_UNSUPPORTED -> "display refresh below 60 Hz";
            case NATIVE_INTERPOLATOR_UNAVAILABLE -> "native interpolator unavailable";
            case ON_GLASS_CADENCE -> "shown cadence unstable";
            case ON_GLASS_TIMESTAMP -> "shown order/timestamps invalid";
            case WARMUP_TIMEOUT -> "no healthy shown-frame pairs during warm-up";
            case COORDINATOR_NOT_INSTALLED -> "native workspace not installed";
            case NATIVE_FACTORY_UNAVAILABLE -> "native workspace unavailable";
            case NATIVE_STATUS_INVALID -> "runtime status unavailable";
            case NONE, USER_DISABLED, AWAITING_PRODUCTION_SOURCE,
                    MEASURING_ON_GLASS -> "runtime unavailable";
        };
    }

    private static boolean requiresRestartToRetry(
            final FrameInterpolationRuntimeStatus.Reason reason
    ) {
        return switch (reason) {
            case NATIVE_INTERPOLATOR_UNAVAILABLE, ON_GLASS_CADENCE,
                    ON_GLASS_TIMESTAMP, WARMUP_TIMEOUT,
                    NATIVE_FACTORY_UNAVAILABLE, NATIVE_STATUS_INVALID -> true;
            case NONE, USER_DISABLED, AWAITING_PRODUCTION_SOURCE,
                    MEASURING_ON_GLASS,
                    FRAME_INTERPOLATION_UNSUPPORTED, TEMPORAL_UNSUPPORTED,
                    NATIVE_PROFILE_UNVALIDATED, DISPLAY_SYNC_DISABLED,
                    DISPLAY_REFRESH_UNSUPPORTED, COORDINATOR_NOT_INSTALLED -> false;
        };
    }
}
