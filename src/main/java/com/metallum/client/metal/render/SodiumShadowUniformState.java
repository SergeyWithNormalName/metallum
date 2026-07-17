package com.metallum.client.metal.render;

/** Tracks Sodium's one-upload-per-frame cache across L4 shadow cascades. */
public final class SodiumShadowUniformState {
    private long shadowToken;
    private boolean shadowActive;

    /**
     * Returns whether Sodium must invalidate its cached terrain uniform before this draw.
     * A non-zero token identifies one shadow cascade; zero identifies ordinary rendering.
     */
    public boolean transition(final long activeToken) {
        if (activeToken != 0L) {
            boolean invalidate = !this.shadowActive || this.shadowToken != activeToken;
            this.shadowActive = true;
            this.shadowToken = activeToken;
            return invalidate;
        }
        if (!this.shadowActive) {
            return false;
        }
        this.shadowActive = false;
        this.shadowToken = 0L;
        return true;
    }
}
