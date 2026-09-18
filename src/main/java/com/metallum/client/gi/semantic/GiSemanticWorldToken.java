package com.metallum.client.gi.semantic;

/** Immutable world/resource/material epoch identity carried by every G2 worker result. */
public record GiSemanticWorldToken(
        long worldGeneration,
        long resourceEpoch,
        long materialEpoch,
        String dimensionId
) {
    public GiSemanticWorldToken {
        if (worldGeneration <= 0L || resourceEpoch <= 0L || materialEpoch <= 0L) {
            throw new IllegalArgumentException("G2 world/resource/material epochs must be positive");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("G2 dimension must not be blank");
        }
    }
}
