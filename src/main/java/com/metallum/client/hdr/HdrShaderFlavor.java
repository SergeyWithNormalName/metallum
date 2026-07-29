package com.metallum.client.hdr;

/**
 * Selects the color contract used to compile and bind a scene shader.
 *
 * <p>Each flavor is an immutable shader/cache contract. Raw Legacy and
 * METALLUM never carry the Legacy HDR semantic MRT; HDR semantic and
 * scene-linear variants remain isolated from SDR/UI pipelines.</p>
 */
public enum HdrShaderFlavor {
    LEGACY,
    LEGACY_HDR_SEMANTIC,
    SCENE_RASTER_LINEAR,
    SCENE_POST_LINEAR,
    METALLUM,
    METALLUM_ADVANCED,
    METALLUM_ADVANCED_REACTIVE,
    SUN_SHADOW
}
