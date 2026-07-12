package com.metallum.client.metal.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class MetalHdrFrame {
    private MetalHdrFrame() {
    }

    public static void captureScene(final GpuTextureView textureView) {
        if (textureView == null || textureView.isClosed()) {
            return;
        }
        if (textureView.texture() instanceof MetalGpuTexture texture) {
            texture.device().captureHdrScene(texture);
        }
    }
}
