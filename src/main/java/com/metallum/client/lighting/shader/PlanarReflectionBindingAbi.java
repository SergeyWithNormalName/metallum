package com.metallum.client.lighting.shader;

import com.metallum.client.renderer.PlanarReflectionLayout;

/** Fixed fragment shader binding contract for Planar Reflection textures. */
public final class PlanarReflectionBindingAbi {
    public static final int TEXTURE_SLOT = PlanarReflectionLayout.TEXTURE_SLOT;
    public static final int SAMPLER_SLOT = PlanarReflectionLayout.SAMPLER_SLOT;

    private PlanarReflectionBindingAbi() {
    }
}
