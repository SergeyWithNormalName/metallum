package com.metallum.client.metalfx;

import com.metallum.client.metal.render.FrameInterpolationCommitBoundary;
import com.metallum.client.metal.render.NativeFrameInterpolationCoordinator;
import com.metallum.client.renderer.RendererConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;
import java.util.Optional;

/** Fail-isolated HUD overlay showing real-time FPS and 3D render resolution telemetry. */
public final class DrsResolutionOverlayHud {
    private static int frameCount = 0;
    private static int currentFps = 0;
    private static int currentGenFps = 0;
    private static long lastFpsUpdateNanos = 0;
    private static NativeFrameInterpolationCoordinator.Telemetry lastTelemetrySnapshot = null;
    private static volatile FrameInterpolationCommitBoundary.Status lastInterpolationStatus =
            FrameInterpolationCommitBoundary.Status.BYPASS_DISABLED;

    private DrsResolutionOverlayHud() {
    }

    public static void updateInterpolationStatus(final FrameInterpolationCommitBoundary.Status status) {
        if (status != null) {
            lastInterpolationStatus = status;
        }
    }

    private static int calculateFps() {
        long now = System.nanoTime();
        frameCount++;
        if (lastFpsUpdateNanos == 0) {
            lastFpsUpdateNanos = now;
            Optional<NativeFrameInterpolationCoordinator.Telemetry> initialTelem =
                    NativeFrameInterpolationCoordinator.queryTelemetry(null);
            initialTelem.ifPresent(telemetry -> lastTelemetrySnapshot = telemetry);
        } else {
            long elapsed = now - lastFpsUpdateNanos;
            if (elapsed >= 500_000_000L) { // Update twice per second
                currentFps = Math.round((frameCount * 1_000_000_000.0f) / elapsed);
                frameCount = 0;

                Optional<NativeFrameInterpolationCoordinator.Telemetry> telemOpt =
                        NativeFrameInterpolationCoordinator.queryTelemetry(null);
                if (telemOpt.isPresent()) {
                    NativeFrameInterpolationCoordinator.Telemetry newTelem = telemOpt.get();
                    if (lastTelemetrySnapshot != null) {
                        long deltaGen = newTelem.generatedPresentations() - lastTelemetrySnapshot.generatedPresentations();
                        long deltaReal = newTelem.realPresentations() - lastTelemetrySnapshot.realPresentations();
                        if (deltaGen >= 0 && deltaReal >= 0) {
                            currentGenFps = (int) Math.round((deltaGen * 1_000_000_000.0) / elapsed);
                            if (deltaReal > 0) {
                                currentFps = (int) Math.round((deltaReal * 1_000_000_000.0) / elapsed);
                            }
                        }
                    }
                    lastTelemetrySnapshot = newTelem;
                }

                lastFpsUpdateNanos = now;
            }
        }
        return currentFps;
    }

    public static void render(final GuiGraphicsExtractor graphics) {
        if (!MetalFxUpscaling.isResolutionOverlayEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null || minecraft.font == null) {
            return;
        }

        int realFps = calculateFps();
        FrameInterpolationCommitBoundary.Status interpolationStatus = lastInterpolationStatus;
        boolean fiConfigured = RendererConfig.load().frameInterpolation();
        boolean fiActive = fiConfigured && interpolationStatus.prepared();

        int genFps = fiActive ? currentGenFps : 0;
        int displayFps = realFps + genFps;

        String fpsText = fiConfigured
                ? String.format(Locale.ROOT, "FPS: %d (%d real + %d gen)", displayFps, realFps, genFps)
                : String.format(Locale.ROOT, "FPS: %d", realFps);

        String fiTag;
        if (!fiConfigured) {
            fiTag = "FI: OFF";
        } else if (fiActive) {
            fiTag = "FI: ACTIVE ⚡";
        } else {
            fiTag = "FI: BYPASS (" + formatStatusReason(interpolationStatus) + ")";
        }

        int displayWidth = minecraft.getWindow().getWidth();
        int displayHeight = minecraft.getWindow().getHeight();
        MetalFxUpscaling.Dimensions dims = MetalFxUpscaling.effectiveDimensions(displayWidth, displayHeight);

        String label;
        if (MetalFxUpscaling.isActive()) {
            boolean dynamic = MetallumDrsController.isEnabled();
            label = dynamic
                    ? String.format(
                            Locale.ROOT,
                            "%s | DRS Render: %dx%d (%.0f%%) → %dx%d [%s, GPU %.1f ms] | %s",
                            fpsText, dims.renderWidth(), dims.renderHeight(), dims.actualWidthScale() * 100.0f,
                            dims.displayWidth(), dims.displayHeight(), MetalFxUpscaling.activeType().name(),
                            MetallumDrsController.emaGpuTimeMs(), fiTag
                    )
                    : String.format(
                            Locale.ROOT,
                            "%s | MetalFX Render: %dx%d (%.0f%%) → %dx%d [%s] | %s",
                            fpsText, dims.renderWidth(), dims.renderHeight(), dims.actualWidthScale() * 100.0f,
                            dims.displayWidth(), dims.displayHeight(), MetalFxUpscaling.activeType().name(), fiTag
                    );
        } else {
            label = String.format(
                    Locale.ROOT,
                    "%s | Render: %dx%d (100%% Native) | %s",
                    fpsText,
                    displayWidth,
                    displayHeight,
                    fiTag
            );
        }

        int textWidth = minecraft.font.width(label);
        int x = 10;
        int y = 10;
        graphics.fill(x - 4, y - 4, x + textWidth + 4, y + 12, 0xd0000000);
        graphics.text(minecraft.font, label, x, y, 0xff00ff88, true);
    }

    private static String formatStatusReason(final FrameInterpolationCommitBoundary.Status status) {
        if (status == null) {
            return "Inactive";
        }
        return switch (status) {
            case BYPASS_DISABLED -> "Disabled";
            case BYPASS_UNSUPPORTED -> "Unsupported";
            case BYPASS_PRIMING -> "Priming";
            case BYPASS_CADENCE -> "Cadence";
            case BYPASS_BACKPRESSURE -> "Backpressure";
            case BYPASS_NO_UI -> "UI Discontinuity";
            case BYPASS_GENERATION -> "Generation Mismatch";
            case BYPASS_INPUT_CONTRACT -> "Input Contract";
            case NO_DRAWABLE -> "No Drawable";
            case STALE_TICKET -> "Stale Ticket";
            case TRANSIENT_FAILURE -> "Transient Error";
            case FATAL_FOR_GENERATION -> "Fatal Error";
            default -> status.name();
        };
    }
}

