package com.metallum.client.metal.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class MetalHdrFrame {
    private MetalHdrFrame() {
    }

    /** Mirrors GameRenderer's world-render predicate so title/loading frames stay output-only. */
    public static boolean shouldCaptureWorldScene(
            final boolean resourcesLoaded,
            final boolean advanceGameTime,
            final boolean hasLevel
    ) {
        return resourcesLoaded && advanceGameTime && hasLevel;
    }

    /** Limits the material shader flavor to GameRenderer's real world pass. */
    public static void setWorldScenePass(final GpuTextureView colorView, final boolean active) {
        if (colorView != null
                && !colorView.isClosed()
                && colorView.texture() instanceof MetalGpuTexture color) {
            color.device().setMaterialWorldPassActive(active);
        }
    }

    /**
     * Declares that a Java render target stores scene color rather than
     * numerical FP16 data. The declaration lives on the physical texture so
     * deferred, partial, and clear-only command paths use the same color
     * contract before any render pipeline has been bound.
     */
    public static void markSceneColor(final GpuTexture texture) {
        if (texture instanceof MetalGpuTexture color) {
            color.markSceneColorClearRole();
        }
    }

    /** Declares an encoded SDR/UI attachment which must never select the material flavor. */
    public static void markDisplaySdrColor(final GpuTexture texture) {
        if (texture instanceof MetalGpuTexture color) {
            color.markDisplaySdrColorRole();
        }
    }

    public static void captureScene(
            final GpuTextureView colorView,
            final GpuTextureView depthView,
            final boolean worldSceneRendered
    ) {
        if (colorView == null || colorView.isClosed()) {
            return;
        }
        if (colorView.texture() instanceof MetalGpuTexture color) {
            MetalGpuTexture depth = null;
            if (depthView != null
                    && !depthView.isClosed()
                    && depthView.texture() instanceof MetalGpuTexture metalDepth
                    && color.device() == metalDepth.device()) {
                depth = metalDepth;
            }
            // captureScene is an explicit scene-color contract. Record it
            // before captureHdrScene flushes a pending clear, including the
            // first empty/loading frame that has not bound a pipeline yet.
            markSceneColor(color);
            color.device().captureHdrScene(color, depth, worldSceneRendered);
        }
    }

    public static void captureUi(
            final GpuTextureView colorView,
            final boolean suppressSceneEnhancement
    ) {
        if (colorView == null || colorView.isClosed()) {
            return;
        }
        if (colorView.texture() instanceof MetalGpuTexture color) {
            color.device().captureHdrUi(color, suppressSceneEnhancement);
        }
    }

    public static boolean prepareUiBackdrop(
            final GpuTextureView mainColorView,
            final GpuTextureView uiColorView
    ) {
        if (mainColorView == null
                || mainColorView.isClosed()
                || uiColorView == null
                || uiColorView.isClosed()
                || mainColorView == uiColorView) {
            return false;
        }
        if (mainColorView.texture() instanceof MetalGpuTexture source
                && uiColorView.texture() instanceof MetalGpuTexture destination
                && source != destination
                && source.device() == destination.device()) {
            return source.device().prepareHdrUiBackdrop(source, destination);
        }
        return false;
    }

    public static void materializeUiBackdrop(final GpuTextureView uiColorView) {
        if (uiColorView != null
                && !uiColorView.isClosed()
                && uiColorView.texture() instanceof MetalGpuTexture ui) {
            ui.device().materializeHdrUiBackdrop(ui);
        }
    }

    /** Preserves the pre-GUI scene before GUI rendering falls back to MainTarget. */
    public static boolean materializeSceneFallback(final GpuTextureView mainColorView) {
        if (mainColorView == null || mainColorView.isClosed()) {
            return false;
        }
        return mainColorView.texture() instanceof MetalGpuTexture color
                && color.device().materializeHdrSceneFallback(color);
    }

    /** Marks the live scene safe only after GUI redirection is fully established. */
    public static boolean confirmUiRedirect(final GpuTextureView mainColorView) {
        if (mainColorView == null || mainColorView.isClosed()) {
            return false;
        }
        return mainColorView.texture() instanceof MetalGpuTexture color
                && color.device().confirmHdrUiRedirect(color);
    }

    public static boolean isSceneReadyForUi(final GpuTextureView mainColorView) {
        if (mainColorView == null || mainColorView.isClosed()) {
            return false;
        }
        return mainColorView.texture() instanceof MetalGpuTexture color
                && color.device().isHdrSceneReadyForUi(color);
    }

    public static boolean isSpatialHdrPrecomposed(final GpuTextureView mainColorView) {
        return mainColorView != null
                && !mainColorView.isClosed()
                && mainColorView.texture() instanceof MetalGpuTexture color
                && color.device().isSpatialHdrPrecomposedForCurrentSubmit();
    }

    public static boolean prepareSpatialScreenshot(
            final GpuTextureView mainColorView,
            final GpuTextureView uiColorView,
            final GpuTextureView destinationColorView
    ) {
        if (mainColorView == null
                || mainColorView.isClosed()
                || uiColorView == null
                || uiColorView.isClosed()
                || destinationColorView == null
                || destinationColorView.isClosed()) {
            return false;
        }
        if (mainColorView.texture() instanceof MetalGpuTexture source
                && uiColorView.texture() instanceof MetalGpuTexture ui
                && destinationColorView.texture() instanceof MetalGpuTexture destination
                && source.device() == ui.device()
                && source.device() == destination.device()) {
            return source.device().prepareSpatialScreenshot(source, ui, destination);
        }
        return false;
    }
}
