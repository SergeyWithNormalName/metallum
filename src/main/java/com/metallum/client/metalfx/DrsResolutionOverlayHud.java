package com.metallum.client.metalfx;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
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

    private static void sampleGeneratedFrames(final long nowNanos) {
        if (!generatedTelemetryAvailable || !GENERATED_FRAMES.shouldSample(nowNanos)) {
            return;
        }
        try {
            GENERATED_FRAMES.observe(
                    nowNanos,
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
        sampleGeneratedFrames(System.nanoTime());
        String generatedLabel = generatedTelemetryAvailable
                ? String.format(
                        Locale.ROOT,
                        "Generated shown: %d FPS (%d total)",
                        GENERATED_FRAMES.framesPerSecond(),
                        GENERATED_FRAMES.presentedCount()
                )
                : "Generated shown: unavailable";
        int displayWidth = minecraft.getWindow().getWidth();
        int displayHeight = minecraft.getWindow().getHeight();
        MetalFxUpscaling.Dimensions dims = MetalFxUpscaling.effectiveDimensions(displayWidth, displayHeight);

        String label;
        if (MetalFxUpscaling.isActive()) {
            boolean dynamic = MetallumDrsController.isEnabled();
            label = dynamic
                    ? String.format(
                            Locale.ROOT,
                            "FPS: %d | %s | DRS Render: %dx%d (%.0f%%) → %dx%d [%s, GPU %.1f ms]",
                            fps, generatedLabel,
                            dims.renderWidth(), dims.renderHeight(), dims.actualWidthScale() * 100.0f,
                            dims.displayWidth(), dims.displayHeight(), MetalFxUpscaling.activeType().name(),
                            MetallumDrsController.emaGpuTimeMs()
                    )
                    : String.format(
                            Locale.ROOT,
                            "FPS: %d | %s | MetalFX Render: %dx%d (%.0f%%) → %dx%d [%s]",
                            fps, generatedLabel,
                            dims.renderWidth(), dims.renderHeight(), dims.actualWidthScale() * 100.0f,
                            dims.displayWidth(), dims.displayHeight(), MetalFxUpscaling.activeType().name()
                    );
        } else {
            label = String.format(
                    Locale.ROOT,
                    "FPS: %d | %s | Render: %dx%d (100%% Native)",
                    fps,
                    generatedLabel,
                    displayWidth,
                    displayHeight
            );
        }

        int textWidth = minecraft.font.width(label);
        int x = 10;
        int y = 10;
        graphics.fill(x - 4, y - 4, x + textWidth + 4, y + 12, 0xd0000000);
        graphics.text(minecraft.font, label, x, y, 0xff00ff88, true);
    }
}
