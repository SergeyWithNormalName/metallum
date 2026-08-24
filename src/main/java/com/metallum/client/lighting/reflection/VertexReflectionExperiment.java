package com.metallum.client.lighting.reflection;

/**
 * Feasibility experiment configuration and constants for vertex-stage rough reflection sampling.
 *
 * <p>This test evaluates whether sampling frozen world-space reflection textures in the
 * terrain vertex stage is viable and cost-effective on Apple Silicon / Metal.</p>
 */
public final class VertexReflectionExperiment {
    public static final String ACTIVE_PROPERTY = "metallum.vertex.reflection.experiment";
    public static final String ACTIVE_ENV = "METALLUM_VERTEX_REFLECTION_EXPERIMENT";
    /**
     * Benchmark invocations acknowledge runtime allocation explicitly. Interactive users do so
     * through the restart-gated Sodium option persisted by {@link VertexReflectionExperimentConfig}.
     */
    public static final String RUNTIME_PROPERTY = "metallum.vertex.reflection.runtime";

    public static final int GRID_SIZE = 32;
    public static final String RADIANCE_SAMPLER_NAME = "metallumReflectionRadiance";
    public static final String MOMENT_SAMPLER_NAME = "metallumReflectionMoment";
    public static final String PARAMS_BUFFER_NAME = "metallumVertexReflection";
    public static final int RADIANCE_BINDING_SLOT = VertexReflectionBindingAbi.RADIANCE_TEXTURE_AND_SAMPLER_SLOT;
    public static final int MOMENT_BINDING_SLOT = VertexReflectionBindingAbi.MOMENT_TEXTURE_AND_SAMPLER_SLOT;
    public static final int PARAMS_BINDING_SLOT = VertexReflectionBindingAbi.PARAMS_BUFFER_SLOT;

    private static volatile Boolean activeOverride = null;

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

    public static boolean isRuntimeEnabled() {
        return isActive() && (Boolean.getBoolean(RUNTIME_PROPERTY) || VertexReflectionExperimentConfig.isEnabled());
    }

    public static void setOverride(final Boolean active) {
        activeOverride = active;
    }

    public static boolean isExperimentSampler(final String name) {
        return RADIANCE_SAMPLER_NAME.equals(name) || MOMENT_SAMPLER_NAME.equals(name);
    }
}
