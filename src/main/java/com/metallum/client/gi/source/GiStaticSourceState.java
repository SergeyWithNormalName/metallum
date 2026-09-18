package com.metallum.client.gi.source;

import com.metallum.client.lighting.LightWorldToken;

import java.util.Objects;

/** Current healthy L3 static-source identity, captured before any per-brick query. */
public record GiStaticSourceState(LightWorldToken world, long registryEpoch) {
    public GiStaticSourceState {
        Objects.requireNonNull(world, "world");
        if (registryEpoch <= 0L) {
            throw new IllegalArgumentException("G3 L3 registry epoch must be positive");
        }
    }
}
