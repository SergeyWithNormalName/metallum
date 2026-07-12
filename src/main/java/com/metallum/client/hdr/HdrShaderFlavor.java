package com.metallum.client.hdr;

/**
 * Selects the color contract used to compile and bind a scene shader.
 *
 * <p>Phase A deliberately compiles each optional scene flavor from GLSL that
 * is identical to the legacy source. Keeping the flavor in every cache key
 * lets later phases change raster boundaries and post-processing independently
 * without mutating the legacy GUI path.</p>
 */
public enum HdrShaderFlavor {
    LEGACY,
    SCENE_RASTER_LINEAR,
    SCENE_POST_LINEAR
}
