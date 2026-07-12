package com.metallum.client.hdr;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalHdrFrame;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

/** Owns the scene-seeded SDR target used by GuiRenderer in the FP16 scene mode. */
public final class HdrUiRenderTarget {
    @Nullable
    private static TextureTarget target;
    private static boolean activeThisFrame;
    private static boolean unavailable;
    private static boolean activationLogged;
    private static boolean screenshotLogged;
    private static boolean lastUiFinished;
    private static boolean backdropBlurredThisFrame;
    @Nullable
    private static RenderTarget activeSource;
    @Nullable
    private static RenderTarget lastUiSource;

    private HdrUiRenderTarget() {
    }

    public static RenderTarget begin(final RenderTarget mainTarget) {
        activeThisFrame = false;
        activeSource = null;
        lastUiFinished = false;
        lastUiSource = null;
        backdropBlurredThisFrame = false;
        if (unavailable || !HdrSceneState.isRequested()) {
            return mainTarget;
        }

        try {
            if (!MetalHdrFrame.isSceneReadyForUi(mainTarget.getColorTextureView())) {
                return mainTarget;
            }
            if (target == null || target.width != mainTarget.width || target.height != mainTarget.height) {
                TextureTarget replacement = new TextureTarget(
                        "Metallum SDR UI",
                        mainTarget.width,
                        mainTarget.height,
                        true,
                        GpuFormat.RGBA8_UNORM
                );
                TextureTarget previous = target;
                target = replacement;
                if (previous != null) {
                    previous.destroyBuffers();
                }
            }

            if (!MetalHdrFrame.prepareUiBackdrop(
                    mainTarget.getColorTextureView(),
                    target.getColorTextureView()
            )) {
                throw new IllegalStateException("Metal rejected the SDR UI backdrop");
            }
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(target.getDepthTexture(), 0.0);
            if (!activationLogged) {
                activationLogged = true;
                Metallum.LOGGER.info("Seeded SDR GUI target is active (RGBA8_UNORM)");
            }
            activeThisFrame = true;
            activeSource = mainTarget;
            return target;
        } catch (Throwable throwable) {
            disableAfterFailure(throwable);
            return mainTarget;
        }
    }

    public static void finish() {
        if (activeThisFrame && target != null) {
            try {
                MetalHdrFrame.captureUi(target.getColorTextureView(), backdropBlurredThisFrame);
                lastUiFinished = true;
                lastUiSource = activeSource;
            } catch (Throwable throwable) {
                disableAfterFailure(throwable);
            }
        } else {
            lastUiFinished = false;
            lastUiSource = null;
        }
        activeThisFrame = false;
        activeSource = null;
        backdropBlurredThisFrame = false;
    }

    /** Marks a menu frame whose seeded scene was intentionally blurred. */
    public static void markBackdropBlurred() {
        if (activeThisFrame) {
            backdropBlurredThisFrame = true;
        }
    }

    @Nullable
    public static RenderTarget activeTarget() {
        return activeThisFrame ? target : null;
    }

    public static RenderTarget screenshotTargetFor(final RenderTarget source) {
        boolean available = lastUiFinished
                && target != null
                && source == lastUiSource
                && source != target
                && source.width == target.width
                && source.height == target.height;
        if (available && !screenshotLogged) {
            screenshotLogged = true;
            Metallum.LOGGER.info("F2 screenshot source: seeded SDR GUI composite");
        }
        return available ? target : source;
    }

    public static void destroy() {
        activeThisFrame = false;
        activeSource = null;
        lastUiFinished = false;
        lastUiSource = null;
        backdropBlurredThisFrame = false;
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    private static void disableAfterFailure(final Throwable throwable) {
        unavailable = true;
        activeThisFrame = false;
        activeSource = null;
        lastUiFinished = false;
        lastUiSource = null;
        backdropBlurredThisFrame = false;
        if (target != null) {
            try {
                target.destroyBuffers();
            } catch (Throwable closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            target = null;
        }
        Metallum.LOGGER.error(
                "Failed to prepare the seeded SDR UI target; rendering GUI into MainTarget for safety",
                throwable
        );
    }
}
