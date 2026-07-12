package com.metallum.client.metal.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class MetalHdrFrame {
    private MetalHdrFrame() {
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

    public static void captureScene(final GpuTextureView colorView, final GpuTextureView depthView) {
        if (colorView == null || colorView.isClosed() || depthView == null || depthView.isClosed()) {
            return;
        }
        if (colorView.texture() instanceof MetalGpuTexture color
                && depthView.texture() instanceof MetalGpuTexture depth
                && color.device() == depth.device()) {
            // captureScene is an explicit scene-color contract. Record it
            // before captureHdrScene flushes a pending clear, including the
            // first empty/loading frame that has not bound a pipeline yet.
            markSceneColor(color);
            color.device().captureHdrScene(color, depth);
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

    public static boolean isSceneReadyForUi(final GpuTextureView mainColorView) {
        if (mainColorView == null || mainColorView.isClosed()) {
            return false;
        }
        return mainColorView.texture() instanceof MetalGpuTexture color
                && color.device().isHdrSceneReadyForUi(color);
    }
}
