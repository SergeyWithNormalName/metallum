package com.metallum.client.lighting.shader;

import com.metallum.Metallum;
import com.metallum.client.lighting.AdvancedLightingRuntime;

import java.util.Map;
import java.util.Objects;

/** Two-phase, fail-closed source and PSO gate for one complete L3 shader generation. */
public final class AdvancedLightingPreflightGate {
    public record Evaluation(boolean active, String reason) {
        public Evaluation {
            reason = Objects.requireNonNull(reason, "reason").trim();
            if (reason.isEmpty()) {
                throw new IllegalArgumentException("Advanced shader evaluation needs a reason");
            }
        }
    }

    private record Candidate(Evaluation evaluation, String rejectionReason) {
        boolean rejected() {
            return this.rejectionReason != null;
        }

        Candidate reject(final String reason) {
            return new Candidate(this.evaluation, reason);
        }
    }

    private static volatile Evaluation current = new Evaluation(
            false,
            "Advanced shaders have not been preflighted"
    );
    private static volatile Candidate candidate;

    private AdvancedLightingPreflightGate() {
    }

    public static boolean isActive() {
        return current.active();
    }

    public static String reason() {
        return current.reason();
    }

    /**
     * Starts a source-complete candidate but keeps it invisible until every requested PSO has
     * compiled and {@link #commitCandidate()} is called.
     */
    public static synchronized void beginCandidate(final Evaluation evaluation) {
        Evaluation checked = Objects.requireNonNull(evaluation, "evaluation");
        candidate = new Candidate(checked, null);
        installPending(new Evaluation(
                false,
                checked.active()
                        ? "Advanced shader candidate is pending complete pipeline compilation"
                        : checked.reason()
        ));
    }

    public static boolean shouldCompileAdvancedVariants() {
        Candidate pending = candidate;
        if (pending != null) {
            return pending.evaluation().active() && !pending.rejected();
        }
        return current.active();
    }

    public static synchronized void rejectAdvancedVariant(final String reason) {
        String checkedReason = reason == null || reason.isBlank()
                ? "Advanced shader variant failed"
                : reason.trim();
        Candidate pending = candidate;
        if (pending != null) {
            if (!pending.rejected()) {
                candidate = pending.reject(checkedReason);
                installPending(new Evaluation(false, checkedReason));
                Metallum.LOGGER.warn(
                        "Advanced shader candidate rejected; preserving METALLUM + VANILLA lighting: {}",
                        checkedReason
                );
            }
            return;
        }
        if (current.active()) {
            installCommitted(new Evaluation(false, checkedReason));
            Metallum.LOGGER.warn(
                    "Advanced shader generation disabled; preserving METALLUM + VANILLA lighting: {}",
                    checkedReason
            );
        }
    }

    /** Publishes shader readiness only after all candidate pipeline functions are valid. */
    public static synchronized Evaluation commitCandidate() {
        Candidate pending = candidate;
        if (pending == null) {
            return current;
        }
        Evaluation committed = !pending.evaluation().active()
                ? pending.evaluation()
                : pending.rejected()
                ? new Evaluation(false, pending.rejectionReason())
                : pending.evaluation();
        candidate = null;
        install(committed);
        return committed;
    }

    /**
     * Validates the complete pinned terrain/entity/end-portal receiver source set. Each source
     * first passes the L2 material adapter and only then the independent Advanced lighting adapter.
     */
    public static Evaluation evaluate(
            final Map<AdvancedDirectLightingShaderPatcher.ShaderKey, String> sources,
            final boolean advancedRequested,
            final boolean materialCandidateAvailable
    ) {
        if (!advancedRequested) {
            return new Evaluation(false, "Advanced Lighting is not requested");
        }
        if (!materialCandidateAvailable) {
            return new Evaluation(
                    false,
                    "Advanced Lighting requires a complete METALLUM material candidate"
            );
        }
        AdvancedDirectLightingShaderPatcher.Preflight source =
                AdvancedDirectLightingShaderPatcher.preflight(sources);
        return source.ready()
                ? new Evaluation(
                        true,
                        "Minecraft 26.2 and Sodium 0.9.1 Advanced terrain/entity/end-portal sources passed preflight"
                )
                : new Evaluation(false, source.failureReason());
    }

    private static synchronized void install(final Evaluation evaluation) {
        candidate = null;
        Evaluation checked = Objects.requireNonNull(evaluation, "evaluation");
        installCommitted(checked);
        if (checked.active()) {
            Metallum.LOGGER.info("Advanced shader generation active: {}", checked.reason());
        } else if (AdvancedLightingRuntime.isRequested()) {
            Metallum.LOGGER.warn("Advanced shader generation unavailable: {}", checked.reason());
        }
    }

    public static synchronized void reset() {
        candidate = null;
        installPending(new Evaluation(false, "Advanced shaders have not been preflighted"));
    }

    static void resetForTests() {
        reset();
    }

    private static void installPending(final Evaluation next) {
        current = next;
        AdvancedLightingRuntime.reportShaderPending(next.reason());
    }

    private static void installCommitted(final Evaluation next) {
        current = next;
        AdvancedLightingRuntime.reportShaderAdmission(next.active(), next.reason());
    }
}
