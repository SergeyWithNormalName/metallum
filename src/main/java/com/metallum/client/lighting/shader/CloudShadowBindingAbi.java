package com.metallum.client.lighting.shader;

/**
 * Fixed GPU binding slots and layout offsets for Metallum Cloud Shadows.
 */
public final class CloudShadowBindingAbi {
    public static final int VERSION = 1;
    public static final int TEXTURE_SLOT = 12;
    public static final int PARAMS_SLOT = 26;

    public static final int CLOUD_OFFSET_AND_GRID_SIZE_OFFSET = 384;
    public static final int CLOUD_PARAMS_OFFSET = 400;
    public static final int CLOUD_SHADOW_FADE_AND_STRENGTH_OFFSET = 416;
    public static final int CLOUD_CONTRACT_OFFSET = 432;
    public static final int PARAMS_BYTES = 448;

    private CloudShadowBindingAbi() {
    }
}
