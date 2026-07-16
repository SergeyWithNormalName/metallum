package com.metallum.client.hdr;

import com.metallum.Metallum;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Two-phase, fail-closed coverage gate for one complete METALLUM material generation. */
public final class MetallumMaterialPreflightGate {
    public record ShaderKey(String namespace, String path, MetallumMaterialShaderPatcher.Stage stage) {
    }

    public record Evaluation(boolean active, String reason) {
    }

    private record Candidate(Evaluation evaluation, String rejectionReason) {
        boolean rejected() {
            return this.rejectionReason != null;
        }

        Candidate reject(final String reason) {
            return new Candidate(this.evaluation, reason);
        }
    }

    private static final AtomicLong EPOCH = new AtomicLong();
    private static volatile Evaluation current = new Evaluation(false, "material shaders have not been preflighted");
    private static volatile Candidate candidate;

    private MetallumMaterialPreflightGate() {
    }

    public static boolean isActive() {
        return current.active();
    }

    public static String reason() {
        return current.reason();
    }

    /** Changes whenever a renderer-generation key must be reconsidered. */
    public static long epoch() {
        return EPOCH.get();
    }

    public static synchronized void beginCandidate(final Evaluation evaluation) {
        Evaluation checked = Objects.requireNonNull(evaluation, "evaluation");
        candidate = new Candidate(checked, null);
        installCurrent(new Evaluation(
                false,
                checked.active() ? "METALLUM material candidate is pending compilation" : checked.reason()
        ));
    }

    public static boolean shouldCompileMaterialVariants() {
        Candidate pending = candidate;
        if (pending != null) {
            return pending.evaluation().active() && !pending.rejected();
        }
        return current.active();
    }

    public static synchronized void rejectMaterialVariant(final String reason) {
        String checkedReason = Objects.requireNonNullElse(reason, "METALLUM material variant failed");
        Candidate pending = candidate;
        if (pending != null) {
            if (!pending.rejected()) {
                candidate = pending.reject(checkedReason);
                installCurrent(new Evaluation(false, checkedReason));
                Metallum.LOGGER.warn(
                        "METALLUM material candidate rejected; preserving output with LEGACY + VANILLA: {}",
                        checkedReason
                );
            }
            return;
        }
        if (current.active()) {
            installCurrent(new Evaluation(false, checkedReason));
            Metallum.LOGGER.warn(
                    "METALLUM material generation disabled; preserving output with LEGACY + VANILLA: {}",
                    checkedReason
            );
        }
    }

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

    public static Evaluation evaluate(
            final Map<ShaderKey, String> sources,
            final boolean materialRequested,
            final boolean irisLoaded,
            final boolean sodiumLoaded
    ) {
        Objects.requireNonNull(sources, "sources");
        if (!materialRequested) {
            return new Evaluation(false, "METALLUM material contract is not requested");
        }
        if (irisLoaded) {
            return new Evaluation(false, "Iris shader output contracts are not supported");
        }
        if (!sodiumLoaded) {
            return new Evaluation(false, "Sodium is required for METALLUM terrain material coverage");
        }

        for (String path : HdrPipelinePolicy.requiredVanillaRasterVertexShaders()) {
            Evaluation vertexFailure = validate(sources, "minecraft", path,
                    MetallumMaterialShaderPatcher.Stage.VERTEX);
            if (vertexFailure != null) {
                return vertexFailure;
            }
        }
        for (String path : HdrPipelinePolicy.requiredVanillaRasterFragmentShaders()) {
            Evaluation fragmentFailure = validate(sources, "minecraft", path,
                    MetallumMaterialShaderPatcher.Stage.FRAGMENT);
            if (fragmentFailure != null) {
                return fragmentFailure;
            }
        }
        for (String path : HdrPipelinePolicy.requiredVanillaPostVertexShaders()) {
            Evaluation failure = validate(sources, "minecraft", path,
                    MetallumMaterialShaderPatcher.Stage.VERTEX);
            if (failure != null) {
                return failure;
            }
        }
        for (String path : HdrPipelinePolicy.requiredVanillaPostFragmentShaders()) {
            Evaluation failure = validate(sources, "minecraft", path,
                    MetallumMaterialShaderPatcher.Stage.FRAGMENT);
            if (failure != null) {
                return failure;
            }
        }
        for (MetallumMaterialShaderPatcher.Stage stage : MetallumMaterialShaderPatcher.Stage.values()) {
            Evaluation failure = validate(sources, "sodium", "blocks/block_layer_opaque", stage);
            if (failure != null) {
                return failure;
            }
        }
        return new Evaluation(true, "all Minecraft 26.2 and Sodium material roles passed source and PSO preflight");
    }

    public static synchronized void install(final Evaluation evaluation) {
        candidate = null;
        installCurrent(Objects.requireNonNull(evaluation, "evaluation"));
        if (evaluation.active()) {
            Metallum.LOGGER.info("METALLUM material contract active: {}", evaluation.reason());
        } else if (MetallumMaterialState.isRequested()) {
            Metallum.LOGGER.warn("METALLUM material contract unavailable: {}", evaluation.reason());
        }
    }

    public static synchronized void reset() {
        candidate = null;
        installCurrent(new Evaluation(false, "material shaders have not been preflighted"));
    }

    static void resetForTests() {
        reset();
    }

    private static Evaluation validate(
            final Map<ShaderKey, String> sources,
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage
    ) {
        ShaderKey key = new ShaderKey(namespace, path, stage);
        String source = sources.get(key);
        if (source == null) {
            return new Evaluation(false, "required shader is missing: " + namespace + ':' + path + ' ' + stage);
        }
        MetallumMaterialShaderPatcher.Result result = MetallumMaterialShaderPatcher.patch(
                namespace, path, stage, source
        );
        return result.success()
                ? null
                : new Evaluation(false, "required material shader is not patchable: "
                + namespace + ':' + path + ' ' + stage + " (" + result.failureReason() + ')');
    }

    private static void installCurrent(final Evaluation next) {
        if (!next.equals(current)) {
            current = next;
            EPOCH.incrementAndGet();
        } else {
            current = next;
        }
    }
}
