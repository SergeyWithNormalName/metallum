package com.metallum.client.benchmark;

import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.renderer.RendererConfig;

import java.util.Objects;

/**
 * Pure benchmark gate.  It turns an interactive fail-closed renderer fallback
 * into an explicit invalid benchmark rather than a cheaper-looking result.
 */
public final class BenchmarkLightingAdmission {
    public enum RequiredModel {
        VANILLA,
        ADVANCED;

        public static RequiredModel parse(final String value) {
            return switch (Objects.requireNonNull(value, "value").strip().toLowerCase()) {
                case "vanilla" -> VANILLA;
                case "advanced" -> ADVANCED;
                default -> throw new IllegalArgumentException(
                        "METALLUM_BENCHMARK_EXPECTED_LIGHTING_MODEL must be vanilla or advanced"
                );
            };
        }
    }

    public record Decision(
            RequiredModel required,
            String parsedSchema,
            boolean defaultsUsed,
            String requested,
            String resolved,
            boolean l3Active,
            boolean l5Active,
            boolean l6Active,
            long generationId,
            long shaderAdmissionEpoch,
            boolean admitted,
            String reason
    ) {
        public boolean valid() {
            return this.reason.isEmpty();
        }
    }

    private BenchmarkLightingAdmission() {
    }

    public static Decision evaluate(
            final RequiredModel required,
            final RendererConfig.LoadStatus config,
            final AdvancedLightingRuntime.BenchmarkStatus runtime
    ) {
        Objects.requireNonNull(required, "required");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(runtime, "runtime");

        String requested = runtime.requested() ? "advanced" : "vanilla";
        String resolved = runtime.active() ? "advanced" : "vanilla";
        String reason = "";
        if (config.defaultsUsed()) {
            reason = "renderer config used defaults (" + config.disposition() + ")";
        } else if (!required.name().equalsIgnoreCase(requested)) {
            reason = "requested lighting model is " + requested + ", expected "
                    + required.name().toLowerCase();
        } else if (!required.name().equalsIgnoreCase(resolved)) {
            reason = "resolved lighting model is " + resolved + ", expected "
                    + required.name().toLowerCase() + ": " + runtime.blocker();
        } else if (required == RequiredModel.ADVANCED && !runtime.frame().observed()) {
            reason = "Advanced frame health was not observed";
        } else if (required == RequiredModel.ADVANCED
                && (!runtime.frame().l3Active()
                || !runtime.frame().l5Active()
                || !runtime.frame().l6Active())) {
            reason = "Advanced frame health is incomplete";
        } else if (!runtime.benchmarkBlocker().isEmpty()) {
            reason = runtime.benchmarkBlocker();
        }
        return new Decision(
                required,
                config.parsedSchema(),
                config.defaultsUsed(),
                requested,
                resolved,
                runtime.frame().l3Active(),
                runtime.frame().l5Active(),
                runtime.frame().l6Active(),
                runtime.frame().generationId(),
                runtime.admission().epoch(),
                runtime.admission().ready(),
                reason
        );
    }
}
