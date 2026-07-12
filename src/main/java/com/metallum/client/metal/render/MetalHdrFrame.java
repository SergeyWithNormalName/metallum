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
}
