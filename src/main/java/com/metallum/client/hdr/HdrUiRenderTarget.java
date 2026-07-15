package com.metallum.client.hdr;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalHdrFrame;
import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.jspecify.annotations.Nullable;

/** Owns the scene-seeded SDR target used by GuiRenderer in the FP16 scene mode. */
public final class HdrUiRenderTarget {
    @Nullable
    private static TextureTarget target;
    @Nullable
    private static TextureTarget screenshotTarget;
    private static boolean activeThisFrame;
    private static boolean unavailable;
    private static boolean activationLogged;
    private static boolean screenshotLogged;
    private static boolean screenshotFallbackLogged;
    private static boolean lastUiFinished;
    private static boolean backdropBlurredThisFrame;
    private static boolean spatialActiveThisFrame;
    private static boolean spatialHdrPrecomposedThisFrame;
    private static boolean lastSpatialHdrPrecomposed;
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
        spatialActiveThisFrame = MetalFxSpatialScaling.isActive();
        spatialHdrPrecomposedThisFrame = false;
        if ((!HdrSceneState.isRequested()
                && !MetallumMaterialState.isGenerationActive()
                && !spatialActiveThisFrame)
                || (unavailable && !spatialActiveThisFrame)) {
            return fallbackToMainTarget(mainTarget);
        }

        try {
            if (!MetalHdrFrame.isSceneReadyForUi(mainTarget.getColorTextureView())) {
                return fallbackToMainTarget(mainTarget);
            }
            int targetWidth = spatialActiveThisFrame
                    ? Minecraft.getInstance().getWindow().getWidth()
                    : mainTarget.width;
            int targetHeight = spatialActiveThisFrame
                    ? Minecraft.getInstance().getWindow().getHeight()
                    : mainTarget.height;
            if (target == null || target.width != targetWidth || target.height != targetHeight) {
                TextureTarget replacement = new TextureTarget(
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
            MetalHdrFrame.markDisplaySdrColor(target.getColorTexture());

            if (!MetalHdrFrame.prepareUiBackdrop(
                    mainTarget.getColorTextureView(),
                    target.getColorTextureView()
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
                disableScalingAfterFailure(throwable);
            } else {
                disableAfterFailure(throwable);
            }
            return mainTarget;
        }
    }

    public static void finish() {
        if (activeThisFrame && target != null) {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                boolean suppressSceneEnhancement = shouldSuppressSceneEnhancement(
                        backdropBlurredThisFrame,
                        minecraft.gui.screen() instanceof LevelLoadingScreen,
                        minecraft.gui.overlay() instanceof LoadingOverlay
                );
                MetalHdrFrame.captureUi(target.getColorTextureView(), suppressSceneEnhancement);
                lastUiFinished = true;
                lastUiSource = activeSource;
            } catch (Throwable throwable) {
                rejectMaterialGenerationAfterSeedFailure();
                if (spatialActiveThisFrame) {
                    disableScalingAfterFailure(throwable);
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
            final boolean levelLoadingScreenActive,
            final boolean loadingOverlayActive
    ) {
        // Loading surfaces are SDR UI. Keep presenting the complete seeded
        // RGBA8 composite while GameRenderer transitions from output-only to
        // its first scene-linear world frame; otherwise the same loading UI
        // abruptly inherits world exposure/headroom during its final frames.
        return backdropBlurred || levelLoadingScreenActive || loadingOverlayActive;
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

    private static void disableScalingAfterFailure(final Throwable throwable) {
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
        MetalFxSpatialScaling.disableRuntimeAfterFailure(throwable);
    }
}
