package com.metallum.client.gi.source;

import com.metallum.client.lighting.EnvironmentDescriptor;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Allocation-free latch for the last environment packet captured at its producer's coherent
 * update boundary.
 */
public final class GiEnvironmentObservationLatch {
    @Nullable private EnvironmentDescriptor descriptor;
    @Nullable private Object worldIdentity;

    /**
     * Initial/world changes invalidate authority until a fresh source boundary. Ordinary changes
     * retain the prior coherent packet until that same boundary.
     */
    public @Nullable EnvironmentDescriptor observe(
            final Object observedWorldIdentity,
            final EnvironmentDescriptor observed,
            final boolean sourceBoundary
    ) {
        Objects.requireNonNull(observedWorldIdentity, "observedWorldIdentity");
        Objects.requireNonNull(observed, "observed");
        if (observedWorldIdentity != this.worldIdentity) {
            this.descriptor = null;
            this.worldIdentity = observedWorldIdentity;
        }
        if (sourceBoundary) {
            this.descriptor = observed;
        }
        return this.descriptor;
    }
}
