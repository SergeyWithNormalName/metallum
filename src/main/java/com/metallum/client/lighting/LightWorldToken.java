package com.metallum.client.lighting;

/** Instance token prevents old worker outputs from leaking across worlds or reloads. */
public record LightWorldToken(long generation, String dimensionId) {
    public LightWorldToken {
        if (generation <= 0L) {
            throw new IllegalArgumentException("World generation must be positive");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
    }
}
