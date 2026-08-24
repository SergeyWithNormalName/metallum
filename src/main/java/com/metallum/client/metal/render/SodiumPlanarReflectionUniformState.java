package com.metallum.client.metal.render;

/** Tracks Sodium's one-upload-per-frame uniform cache across reflected terrain passes. */
public final class SodiumPlanarReflectionUniformState {
    private long reflectionToken;

    public boolean transition(final long nextToken) {
        if (this.reflectionToken == nextToken) {
            return false;
        }
        this.reflectionToken = nextToken;
        return true;
    }
}
