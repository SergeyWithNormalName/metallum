package com.metallum.client.metal.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class MetalHdrFrame {
    private MetalHdrFrame() {
    }

    public static void captureScene(final GpuTextureView colorView, final GpuTextureView depthView) {
        if (colorView == null || colorView.isClosed() || depthView == null || depthView.isClosed()) {
            return;
        }
        if (colorView.texture() instanceof MetalGpuTexture color
                && depthView.texture() instanceof MetalGpuTexture depth
                && color.device() == depth.device()) {
            color.device().captureHdrScene(color, depth);
        }
    }

    public static void captureUi(final GpuTextureView colorView) {
        if (colorView == null || colorView.isClosed()) {
            return;
        }
        if (colorView.texture() instanceof MetalGpuTexture color) {
            color.device().captureHdrUi(color);
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
