package com.metallum.client.lighting.shader;

/** Startup-only gate shared by the experimental shader, PSO and history-resource paths. */
public final class L6TemporalShadowExperiment {
    public static final String PROPERTY = "metallum.experimental.l6Temporal";
    public static final String ENVIRONMENT = "METALLUM_L6_TEMPORAL";

    private L6TemporalShadowExperiment() {
    }

    public static boolean enabled() {
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            return parse(property);
        }
        String environment = System.getenv(ENVIRONMENT);
        if (environment != null && !environment.isBlank()) {
            return parse(environment);
        }
        return L6TemporalShadowExperimentConfig.isEnabled();
    }

    private static boolean parse(final String value) {
        if (value == null) {
            return false;
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
