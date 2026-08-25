package com.metallum.client.lighting.shader;

/**
 * Fixed GPU binding slots and layout offsets for Metallum Cloud Shadows.
 */
public final class CloudShadowBindingAbi {
    public static final int VERSION = 3;
    public static final int TEXTURE_SLOT = 12;
    public static final int PARAMS_SLOT = 26;

    public static final int CLOUD_OFFSET_AND_GRID_SIZE_OFFSET = 384;
    public static final int CLOUD_PARAMS_OFFSET = 400;
    public static final int CLOUD_COLOR_AND_REFLECTION_STRENGTH_OFFSET = 416;
    public static final int CLOUD_CONTRACT_OFFSET = 432;
    public static final int SKY_REFLECTION_COLOR_AND_HORIZON_STRENGTH_OFFSET = 448;
    public static final int HORIZON_REFLECTION_COLOR_AND_CLOUD_FOG_END_OFFSET = 464;
    public static final int PARAMS_BYTES = 480;

    private CloudShadowBindingAbi() {
    }
}
