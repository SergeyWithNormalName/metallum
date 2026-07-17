package com.metallum.client.lighting.shader;

import com.metallum.client.renderer.SunShadowLayout;

import java.util.Arrays;

/** Fixed externally-bound fragment ABI for L4 environment and three separate CSM textures. */
public final class EnvironmentShadowBindingAbi {
    public static final int VERSION = SunShadowLayout.ABI_VERSION;
    public static final int PARAMS_SLOT = 26;
    public static final int SHADOW_TEXTURE_0_SLOT = 13;
    public static final int SHADOW_TEXTURE_1_SLOT = 14;
    public static final int SHADOW_TEXTURE_2_SLOT = 15;
    public static final int PARAMS_BYTES = SunShadowLayout.PARAMS_BYTES;

    public static final int MATRIX_0_OFFSET = 0;
    public static final int MATRIX_1_OFFSET = 64;
    public static final int MATRIX_2_OFFSET = 128;
    public static final int DIRECTION_AND_FLAGS_OFFSET = 192;
    public static final int DIRECTIONAL_RADIANCE_OFFSET = 208;
    public static final int SKY_IRRADIANCE_OFFSET = 224;
    public static final int AMBIENT_RADIANCE_OFFSET = 240;
    public static final int CASCADE_SPLITS_OFFSET = 256;
    public static final int TEXEL_AND_BIAS_OFFSET = 272;
    public static final int CASCADE_BLEND_OFFSET = 288;
    public static final int CONTRACT_OFFSET = 304;
    public static final int WORLD_UP_AND_MEDIUM_OFFSET = 320;

    private static final int[] SHADOW_TEXTURE_SLOTS = {
            SHADOW_TEXTURE_0_SLOT,
            SHADOW_TEXTURE_1_SLOT,
            SHADOW_TEXTURE_2_SLOT
    };

    private EnvironmentShadowBindingAbi() {
    }

    public static int[] shadowTextureSlots() {
        return SHADOW_TEXTURE_SLOTS.clone();
    }

    public static boolean ownsShadowTextureSlot(final int slot) {
        return Arrays.stream(SHADOW_TEXTURE_SLOTS).anyMatch(candidate -> candidate == slot);
    }
}
