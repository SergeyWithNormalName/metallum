package com.metallum.client.gi.source;

import com.metallum.client.lighting.LightWorldToken;

import java.util.Objects;

/** Independent G6 identity for one published set of live world-space GI sources. */
public record GiDynamicSourceEpoch(LightWorldToken world, long sourceEpoch) {
    public GiDynamicSourceEpoch {
        Objects.requireNonNull(world, "world");
        if (sourceEpoch <= 0L) {
            throw new IllegalArgumentException("G6 dynamic-source epoch must be positive");
        }
    }

    public boolean isNewerThan(final GiDynamicSourceEpoch previous) {
        Objects.requireNonNull(previous, "previous");
        return this.sourceEpoch > previous.sourceEpoch;
    }
}
