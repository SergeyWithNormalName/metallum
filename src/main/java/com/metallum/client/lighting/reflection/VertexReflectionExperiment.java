package com.metallum.client.lighting.reflection;

import com.metallum.client.gi.receiver.CompactPositionCarrierSafety;
import com.metallum.client.gi.receiver.GiReceiverCompatibility;

/**
 * Feasibility experiment configuration and constants for vertex-stage rough reflection sampling.
 *
 * <p>This test evaluates whether sampling frozen world-space reflection textures in the
 * material-gated terrain vertex stage is viable and cost-effective on Apple Silicon / Metal.</p>
 */
public final class VertexReflectionExperiment {
    public static final String ACTIVE_PROPERTY = "metallum.vertex.reflection.experiment";
    public static final String ACTIVE_ENV = "METALLUM_VERTEX_REFLECTION_EXPERIMENT";
    /**
     * Benchmark invocations acknowledge runtime allocation explicitly. Interactive users do so
     * through the restart-gated Sodium option persisted by {@link VertexReflectionExperimentConfig}.
     */
    public static final String RUNTIME_PROPERTY = "metallum.vertex.reflection.runtime";

    public static final int GRID_SIZE = FrozenReflectionFieldController.SOURCE_EDGE;
    public static final String RADIANCE_SAMPLER_NAME = "metallumReflectionRadiance";
    public static final String PARAMS_BUFFER_NAME = "metallumVertexReflection";
    public static final int RADIANCE_BINDING_SLOT = VertexReflectionBindingAbi.RADIANCE_TEXTURE_AND_SAMPLER_SLOT;
    public static final int PARAMS_BINDING_SLOT = VertexReflectionBindingAbi.PARAMS_BUFFER_SLOT;

    private static volatile Boolean activeOverride = null;
    private static volatile Boolean layoutEnabled;

    private VertexReflectionExperiment() {
    }

    public static boolean isActive() {
        if (activeOverride != null) {
            return activeOverride;
        }
        String prop = System.getProperty(ACTIVE_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return "1".equals(prop.trim()) || "true".equalsIgnoreCase(prop.trim());
        }
        String env = System.getenv(ACTIVE_ENV);
        if (env != null && !env.isBlank()) {
            return "1".equals(env.trim()) || "true".equalsIgnoreCase(env.trim());
        }
        return VertexReflectionExperimentConfig.isEnabled();
    }

    /**
     * Restart-stable shader/resource layout gate. A worker-observed carrier conflict must never
     * remove declared Metal bindings from an already selected pipeline.
     */
    public static boolean isLayoutEnabled() {
        Boolean current = layoutEnabled;
        if (current != null) {
            return current;
        }
        synchronized (VertexReflectionExperiment.class) {
            if (layoutEnabled == null) {
                layoutEnabled = GiReceiverCompatibility.supportsInstalledCompactPositionCarrier()
                        && isActive()
                        && (Boolean.getBoolean(RUNTIME_PROPERTY)
                        || VertexReflectionExperimentConfig.isEnabled());
            }
            return layoutEnabled;
        }
    }

    /** Dynamic contribution gate; false keeps the ON layout bound to zero-ready parameters. */
    public static boolean isRuntimeEnabled() {
        return isLayoutEnabled() && CompactPositionCarrierSafety.isSafe();
    }

    public static void setOverride(final Boolean active) {
        activeOverride = active;
        // Unit tests run without a renderer-generation restart. Production never calls this hook.
        layoutEnabled = null;
    }

    public static boolean isExperimentSampler(final String name) {
        return RADIANCE_SAMPLER_NAME.equals(name);
    }
}
