package com.metallum.client.hdr;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalHdrFrame;
import com.metallum.client.metalfx.MetalFxUpscaling;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.ProgressScreen;
import org.jspecify.annotations.Nullable;

/** Owns the scene-seeded SDR target used by GuiRenderer in the FP16 scene mode. */
public final class HdrUiRenderTarget {
    private static final int LOADING_SETTLE_FRAMES = 3;
    @Nullable
    private static TextureTarget target;
    @Nullable
    private static TextureTarget screenshotTarget;
    private static boolean activeThisFrame;
    private static boolean unavailable;
    private static boolean activationLogged;
    private static boolean screenshotLogged;
    private static boolean screenshotFallbackLogged;
    private static boolean coherentBlurFallbackLogged;
    private static boolean lastUiFinished;
    private static boolean backdropBlurredThisFrame;
    private static boolean spatialActiveThisFrame;
    private static boolean spatialHdrPrecomposedThisFrame;
    private static boolean lastSpatialHdrPrecomposed;
    private static int loadingSettleFramesRemaining;
    private static boolean loadingTransitionThisFrame;
    @Nullable
    private static RenderTarget activeSource;
    @Nullable
    private static RenderTarget lastUiSource;

    private HdrUiRenderTarget() {
    }

    public static RenderTarget begin(final RenderTarget mainTarget) {
        if (shouldReuseActiveTarget(
                activeThisFrame,
                target != null,
                activeSource == mainTarget
        )) {
            return target;
        }
        activeThisFrame = false;
        activeSource = null;
        lastUiFinished = false;
        lastUiSource = null;
        backdropBlurredThisFrame = false;
        spatialActiveThisFrame = MetalFxUpscaling.isActive();
        spatialHdrPrecomposedThisFrame = false;
        Minecraft minecraft = Minecraft.getInstance();
        boolean loadingSurfaceActive = minecraft.gui.screen() instanceof LevelLoadingScreen
                || minecraft.gui.screen() instanceof ProgressScreen
                || minecraft.gui.overlay() instanceof LoadingOverlay;
        if (loadingSurfaceActive) {
            loadingSettleFramesRemaining = LOADING_SETTLE_FRAMES;
        }
        loadingTransitionThisFrame = loadingSurfaceActive || loadingSettleFramesRemaining > 0;
        if (!loadingSurfaceActive && loadingSettleFramesRemaining > 0) {
            loadingSettleFramesRemaining--;
        }
        if ((!HdrSceneState.isRequested()
                && !MetallumMaterialState.isGenerationActive()
                && !spatialActiveThisFrame)
                || (unavailable && !spatialActiveThisFrame)) {
            return fallbackToMainTarget(mainTarget);
        }

        try {
            int targetWidth = spatialActiveThisFrame
                    ? Minecraft.getInstance().getWindow().getWidth()
                    : mainTarget.width;
            int targetHeight = spatialActiveThisFrame
                    ? Minecraft.getInstance().getWindow().getHeight()
                    : mainTarget.height;
            ensureUiTarget(mainTarget, targetWidth, targetHeight);
            if (!MetalHdrFrame.isSceneReadyForUi(mainTarget.getColorTextureView())) {
                return fallbackWithoutScaledMainTarget(mainTarget);
            }
            MetalHdrFrame.markDisplaySdrColor(target.getColorTexture());

            if (!MetalHdrFrame.prepareUiBackdrop(
                    mainTarget.getColorTextureView(),
                    target.getColorTextureView(),
                    shouldPrecomposeHdrBackdrop(loadingTransitionThisFrame)
            )) {
                throw new IllegalStateException("Metal rejected the SDR UI backdrop");
            }
            spatialHdrPrecomposedThisFrame = MetalHdrFrame.isSpatialHdrPrecomposed(
                    mainTarget.getColorTextureView()
            );
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(target.getDepthTexture(), 0.0);
            if (!activationLogged) {
                activationLogged = true;
                Metallum.LOGGER.info("Seeded SDR GUI target is active (RGBA8_UNORM)");
            }
            if (!MetalHdrFrame.confirmUiRedirect(mainTarget.getColorTextureView())) {
                throw new IllegalStateException("HDR scene changed before GUI redirection completed");
            }
            activeThisFrame = true;
            activeSource = mainTarget;
            unavailable = false;
            return target;
        } catch (Throwable throwable) {
            rejectMaterialGenerationAfterSeedFailure();
            try {
                MetalHdrFrame.materializeSceneFallback(mainTarget.getColorTextureView());
            } catch (Throwable fallbackFailure) {
                throwable.addSuppressed(fallbackFailure);
            }
            if (spatialActiveThisFrame) {
                TextureTarget fullResolutionUiTarget = target;
                boolean retainFullResolutionUiTarget = shouldRetainFullResolutionUiTargetAfterScalingFailure(
                        spatialActiveThisFrame,
                        fullResolutionUiTarget != null
                );
                disableScalingAfterFailure(throwable, retainFullResolutionUiTarget);
                if (retainFullResolutionUiTarget && fullResolutionUiTarget != null) {
                    // The GUI Scissor coordinates remain in display pixels until the
                    // renderer consumes the requested native-resolution resize next frame.
                    // Never send them to the low-resolution MainTarget in that window.
                    return fullResolutionUiTarget;
                }
            } else {
                disableAfterFailure(throwable);
            }
            return mainTarget;
        }
    }

    public static void finish() {
        if (activeThisFrame && target != null) {
            try {
                boolean suppressSceneEnhancement = shouldSuppressSceneEnhancement(
                        backdropBlurredThisFrame,
                        spatialHdrPrecomposedThisFrame,
                        loadingTransitionThisFrame,
                        false
                );
                MetalHdrFrame.captureUi(target.getColorTextureView(), suppressSceneEnhancement);
                lastUiFinished = true;
                lastUiSource = activeSource;
            } catch (Throwable throwable) {
                rejectMaterialGenerationAfterSeedFailure();
                if (spatialActiveThisFrame) {
                    disableScalingAfterFailure(throwable, false);
                } else {
                    disableAfterFailure(throwable);
                }
            }
        } else {
            lastUiFinished = false;
            lastUiSource = null;
        }
        activeThisFrame = false;
        activeSource = null;
        lastSpatialHdrPrecomposed = spatialHdrPrecomposedThisFrame && !backdropBlurredThisFrame;
        backdropBlurredThisFrame = false;
        spatialActiveThisFrame = false;
        spatialHdrPrecomposedThisFrame = false;
        loadingTransitionThisFrame = false;
    }

    /** Marks a menu frame whose seeded scene was intentionally blurred. */
    public static void markBackdropBlurred() {
        if (activeThisFrame) {
            backdropBlurredThisFrame = true;
        }
    }

    /** Resolves a deferred MetalFX HDR seed before blur or another texture read. */
    public static void ensureBackdropMaterialized() {
        if (activeThisFrame && target != null) {
            MetalHdrFrame.materializeUiBackdrop(target.getColorTextureView());
        }
    }

    @Nullable
    public static RenderTarget activeTarget() {
        return activeThisFrame ? target : null;
    }

    /** Allows SDR blur only when no separately presented HDR world must match it. */
    public static boolean shouldProcessSdrBackdropBlur() {
        return shouldProcessSdrBackdropBlur(spatialHdrPrecomposedThisFrame);
    }

    static boolean shouldProcessSdrBackdropBlur(final boolean hdrWorldPrecomposed) {
        return !hdrWorldPrecomposed;
    }

    static boolean shouldPrecomposeHdrBackdrop(final boolean loadingTransitionActive) {
        return !loadingTransitionActive;
    }

    static boolean shouldReuseActiveTarget(
            final boolean active,
            final boolean targetAvailable,
            final boolean sameSource
    ) {
        return active && targetAvailable && sameSource;
    }

    /**
     * A MetalFX rejection occurs after the renderer has adopted a reduced MainTarget,
     * while vanilla GUI layout still expresses scissors in display coordinates.
     */
    static boolean shouldRetainFullResolutionUiTargetAfterScalingFailure(
            final boolean upscalingActive,
            final boolean fullResolutionUiTargetAvailable
    ) {
        return upscalingActive && fullResolutionUiTargetAvailable;
    }

    /** Blurs the composed FP16 world and the pre-blur GUI into matching HDR/SDR outputs. */
    public static boolean processCoherentBackdropBlur(final float radius) {
        if (!activeThisFrame
                || !spatialHdrPrecomposedThisFrame
                || activeSource == null
                || target == null) {
            return false;
        }
        boolean blurred = MetalHdrFrame.blurUiBackdrop(
                activeSource.getColorTextureView(),
                target.getColorTextureView(),
                radius
        );
        if (blurred) {
            markBackdropBlurred();
        } else if (!coherentBlurFallbackLogged) {
            coherentBlurFallbackLogged = true;
            Metallum.LOGGER.warn("Coherent HDR menu blur was unavailable; keeping the stable sharp backdrop");
        }
        return blurred;
    }

    public static RenderTarget screenshotTargetFor(final RenderTarget source) {
        boolean available = lastUiFinished
                && target != null
                && source == lastUiSource
                && source != target;
        RenderTarget screenshotSource = available ? target : source;
        if (available && lastSpatialHdrPrecomposed) {
            try {
                if (screenshotTarget == null
                        || screenshotTarget.width != target.width
                        || screenshotTarget.height != target.height) {
                    TextureTarget replacement = new TextureTarget(
                            "Metallum spatial screenshot",
                            target.width,
                            target.height,
                            false,
                            GpuFormat.RGBA8_UNORM
                    );
                    TextureTarget previous = screenshotTarget;
                    screenshotTarget = replacement;
                    if (previous != null) {
                        previous.destroyBuffers();
                    }
                }
                if (MetalHdrFrame.prepareSpatialScreenshot(
                        source.getColorTextureView(),
                        target.getColorTextureView(),
                        screenshotTarget.getColorTextureView()
                )) {
                    screenshotSource = screenshotTarget;
                } else if (!screenshotFallbackLogged) {
                    screenshotFallbackLogged = true;
                    Metallum.LOGGER.warn("F2 spatial composite was unavailable; using the seeded SDR GUI target");
                }
            } catch (Throwable throwable) {
                if (!screenshotFallbackLogged) {
                    screenshotFallbackLogged = true;
                    Metallum.LOGGER.warn("Failed to prepare the F2 spatial composite; using the seeded SDR GUI target", throwable);
                }
            }
        }
        if (available && !screenshotLogged) {
            screenshotLogged = true;
            Metallum.LOGGER.info("F2 screenshot source: full-resolution SDR GUI composite");
        }
        return screenshotSource;
    }

    public static void destroy() {
        activeThisFrame = false;
        activeSource = null;
        lastUiFinished = false;
        lastUiSource = null;
        backdropBlurredThisFrame = false;
        spatialActiveThisFrame = false;
        spatialHdrPrecomposedThisFrame = false;
        lastSpatialHdrPrecomposed = false;
        loadingSettleFramesRemaining = 0;
        loadingTransitionThisFrame = false;
        if (target != null) {
            MetalHdrFrame.materializeUiBackdrop(target.getColorTextureView());
            target.destroyBuffers();
            target = null;
        }
        if (screenshotTarget != null) {
            screenshotTarget.destroyBuffers();
            screenshotTarget = null;
        }
    }

    private static void disableAfterFailure(final Throwable throwable) {
        unavailable = true;
        activeThisFrame = false;
        activeSource = null;
        lastUiFinished = false;
        lastUiSource = null;
        backdropBlurredThisFrame = false;
        spatialActiveThisFrame = false;
        spatialHdrPrecomposedThisFrame = false;
        lastSpatialHdrPrecomposed = false;
        if (target != null) {
            try {
                MetalHdrFrame.materializeUiBackdrop(target.getColorTextureView());
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

    static boolean shouldRejectMaterialGenerationAfterSeedFailure(
            final boolean materialGenerationActive
    ) {
        return materialGenerationActive;
    }

    static boolean shouldSuppressSceneEnhancement(
            final boolean backdropBlurred,
            final boolean hdrWorldPrecomposed,
            final boolean levelLoadingScreenActive,
            final boolean loadingOverlayActive
    ) {
        // Loading surfaces are SDR UI. Keep presenting the complete seeded
        // RGBA8 composite while GameRenderer transitions from output-only to
        // its first scene-linear world frame; otherwise the same loading UI
        // abruptly inherits world exposure/headroom during its final frames.
        // Native-resolution and MetalFX HDR precompose already provide an
        // exact HDR world plus its quantized SDR seed. Their lightweight
        // present shader classifies the blurred seed itself, so discarding
        // that HDR world here would create a visible HDR -> SDR -> HDR flash
        // whenever an in-world screen opens or closes. Retain the older
        // output-only fallback only when no matching HDR world exists.
        return levelLoadingScreenActive
                || loadingOverlayActive
                || (backdropBlurred && !hdrWorldPrecomposed);
    }

    private static void rejectMaterialGenerationAfterSeedFailure() {
        if (shouldRejectMaterialGenerationAfterSeedFailure(
                MetallumMaterialState.isGenerationActive()
        )) {
            MetallumMaterialPreflightGate.rejectMaterialVariant(
                    "seeded SDR UI target failed during a METALLUM generation"
            );
        }
    }

    private static void ensureUiTarget(
            final RenderTarget mainTarget,
            final int targetWidth,
            final int targetHeight
    ) {
        if (target != null && target.width == targetWidth && target.height == targetHeight) {
            return;
        }
        TextureTarget replacement = MetalHdrFrame.createTrackedUiTarget(
                mainTarget.getColorTextureView(),
                "Metallum SDR UI",
                targetWidth,
                targetHeight,
                true,
                GpuFormat.RGBA8_UNORM
        );
        TextureTarget previous = target;
        target = replacement;
        if (previous != null) {
            MetalHdrFrame.materializeUiBackdrop(previous.getColorTextureView());
            previous.destroyBuffers();
        }
    }

    private static RenderTarget fallbackWithoutScaledMainTarget(final RenderTarget mainTarget) {
        TextureTarget fullResolutionUiTarget = target;
        if (shouldRetainFullResolutionUiTargetAfterScalingFailure(
                spatialActiveThisFrame,
                fullResolutionUiTarget != null
        ) && fullResolutionUiTarget != null) {
            return fullResolutionUiTarget;
        }
        return fallbackToMainTarget(mainTarget);
    }

    private static RenderTarget fallbackToMainTarget(final RenderTarget mainTarget) {
        try {
            MetalHdrFrame.materializeSceneFallback(mainTarget.getColorTextureView());
        } catch (Throwable throwable) {
            Metallum.LOGGER.error(
                    "Failed to preserve the HDR scene before rendering GUI into MainTarget; using EDR output",
                    throwable
            );
        }
        return mainTarget;
    }

    private static void disableScalingAfterFailure(
            final Throwable throwable,
            final boolean retainFullResolutionUiTarget
    ) {
        activeThisFrame = false;
        activeSource = null;
        lastUiFinished = false;
        lastUiSource = null;
        backdropBlurredThisFrame = false;
        spatialActiveThisFrame = false;
        spatialHdrPrecomposedThisFrame = false;
        lastSpatialHdrPrecomposed = false;
        if (target != null && !retainFullResolutionUiTarget) {
            try {
                MetalHdrFrame.materializeUiBackdrop(target.getColorTextureView());
                target.destroyBuffers();
            } catch (Throwable closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            target = null;
        }
        MetalFxUpscaling.disableRuntimeAfterFailure(throwable);
    }
}
