package com.metallum.client.renderer;

/** Layout constants for Planar Reflections on water. */
public final class PlanarReflectionLayout {
    public static final int TEXTURE_SLOT = 11;
    public static final int SAMPLER_SLOT = 11;

    /** Water waves hide the remaining low-frequency sampling error at this quarter-resolution target. */
    public static final float DEFAULT_RESOLUTION_SCALE = 0.25f;

    /** Re-render world geometry at 30 Hz while the water sample and wave distortion remain 60 Hz. */
    public static final int DEFAULT_UPDATE_INTERVAL_FRAMES = 2;

    private PlanarReflectionLayout() {
    }
}
