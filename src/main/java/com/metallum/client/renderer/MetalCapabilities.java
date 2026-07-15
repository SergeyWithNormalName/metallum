package com.metallum.client.renderer;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable capability snapshot captured once per renderer generation. */
public final class MetalCapabilities {
    public enum Feature {
        METAL3_BASE,
        METAL4_CORE,
        HDR_OUTPUT,
        METALLUM_LIGHTING
    }

    private final Set<Feature> features;

    private MetalCapabilities(final Set<Feature> features) {
        Objects.requireNonNull(features, "features");
        EnumSet<Feature> copy = features.isEmpty()
                ? EnumSet.noneOf(Feature.class)
                : EnumSet.copyOf(features);
        this.features = Collections.unmodifiableSet(copy);
    }

    public static MetalCapabilities of(final Feature... features) {
        Objects.requireNonNull(features, "features");
        EnumSet<Feature> values = EnumSet.noneOf(Feature.class);
        Collections.addAll(values, features);
        return new MetalCapabilities(values);
    }

    /** Current production baseline. Improved lighting is intentionally not advertised yet. */
    public static MetalCapabilities productionMetal3(final boolean hdrOutputAvailable) {
        return hdrOutputAvailable
                ? of(Feature.METAL3_BASE, Feature.HDR_OUTPUT)
                : of(Feature.METAL3_BASE);
    }

    public Set<Feature> features() {
        return this.features;
    }

    public boolean supports(final Feature feature) {
        return this.features.contains(Objects.requireNonNull(feature, "feature"));
    }

    public boolean supports(final LightingMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case LEGACY -> true;
            case METALLUM -> supports(Feature.METALLUM_LIGHTING);
        };
    }

    public boolean supports(final DisplayOutputMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case SDR -> true;
            case HDR -> supports(Feature.HDR_OUTPUT);
        };
    }

    public boolean supports(final MetalExecutorKind executor) {
        return switch (Objects.requireNonNull(executor, "executor")) {
            case METAL3 -> supports(Feature.METAL3_BASE);
            case METAL4 -> supports(Feature.METAL4_CORE);
        };
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof MetalCapabilities capabilities
                && this.features.equals(capabilities.features);
    }

    @Override
    public int hashCode() {
        return this.features.hashCode();
    }

    @Override
    public String toString() {
        return "MetalCapabilities" + this.features;
    }
}
