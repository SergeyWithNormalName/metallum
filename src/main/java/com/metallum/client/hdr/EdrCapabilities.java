package com.metallum.client.hdr;

public record EdrCapabilities(float currentHeadroom, float potentialHeadroom) {
    public static final EdrCapabilities SDR = new EdrCapabilities(1.0f, 1.0f);

    public EdrCapabilities {
        currentHeadroom = sanitize(currentHeadroom);
        potentialHeadroom = Math.max(sanitize(potentialHeadroom), currentHeadroom);
    }

    public boolean isHdrDisplay() {
        return this.potentialHeadroom > 1.01f;
    }

    private static float sanitize(final float value) {
        return Float.isFinite(value) ? Math.clamp(value, 1.0f, 64.0f) : 1.0f;
    }
}
