package com.metallum.client.hdr;

import com.metallum.Metallum;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Atomically selects either a wholly patched linear scene or the legacy gamma scene. */
public final class SceneLinearPreflightGate {
    public record ShaderKey(String namespace, String path, SceneLinearShaderPatcher.Stage stage) {
    }

    public record Evaluation(boolean active, String reason) {
    }

    private record Candidate(Evaluation evaluation, String rejectionReason) {
        boolean rejected() {
            return rejectionReason != null;
        }

        Candidate reject(final String reason) {
            return new Candidate(this.evaluation, reason);
        }
    }

    private static final Set<String> DISPLAY_AUTHORED_POST_SHADERS = Set.of(
            "post/invert",
            "post/color_convolve",
            "post/bits"
    );

    private static volatile Evaluation current = new Evaluation(false, "shader sources have not been preflighted");
    private static volatile Candidate candidate;

    private SceneLinearPreflightGate() {
    }

    public static boolean isActive() {
        return current.active();
    }

    public static String reason() {
        return current.reason();
    }

    /**
     * Starts a two-phase shader generation. The visible scene contract stays
     * legacy until {@link #commitCandidate()} observes that every optional
     * scene variant compiled successfully.
     */
    public static synchronized void beginCandidate(final Evaluation evaluation) {
        Evaluation checked = Objects.requireNonNull(evaluation, "evaluation");
        candidate = new Candidate(checked, null);
        current = new Evaluation(
                false,
                checked.active()
                        ? "linear scene shader candidate is pending compilation"
                        : checked.reason()
        );
    }

    /** Returns whether pipeline compilation should attempt the optional scene-linear flavor. */
    public static boolean shouldCompileSceneVariants() {
        Candidate pending = candidate;
        if (pending != null) {
            return pending.evaluation().active() && !pending.rejected();
        }
        return current.active();
    }

    /**
     * Rejects the pending generation, or disables an already active generation
     * if a lazily compiled scene pipeline fails after resource reload.
     */
    public static synchronized void rejectSceneVariant(final String reason) {
        String checkedReason = Objects.requireNonNullElse(reason, "scene-linear shader variant failed");
        Candidate pending = candidate;
        if (pending != null) {
            if (!pending.rejected()) {
                candidate = pending.reject(checkedReason);
                current = new Evaluation(false, checkedReason);
                Metallum.LOGGER.warn(
                        "Linear scene shader candidate rejected; compiling the generation on the legacy gamma contract: {}",
                        checkedReason
                );
            }
            return;
        }

        if (current.active()) {
            current = new Evaluation(false, checkedReason);
            Metallum.LOGGER.warn(
                    "Linear scene shader generation disabled after a lazy scene-variant failure; using the legacy gamma contract: {}",
                    checkedReason
            );
        }
    }

    /** Commits the pending generation only if source preflight and optional compilation both succeeded. */
    public static synchronized Evaluation commitCandidate() {
        Candidate pending = candidate;
        if (pending == null) {
            return current;
        }

        Evaluation committed;
        if (!pending.evaluation().active()) {
            committed = pending.evaluation();
        } else if (pending.rejected()) {
            committed = new Evaluation(false, pending.rejectionReason());
        } else {
            committed = pending.evaluation();
        }
        install(committed);
        return committed;
    }

    public static Evaluation evaluate(
            final Map<ShaderKey, String> sources,
            final boolean sceneRequested,
            final boolean irisLoaded,
            final boolean sodiumLoaded
    ) {
        if (!sceneRequested) {
            return new Evaluation(false, "FP16 scene HDR is not requested");
        }
        if (irisLoaded) {
            return new Evaluation(false, "Iris is loaded; its shader output contract is not supported");
        }

        for (String path : HdrPipelinePolicy.requiredVanillaRasterFragmentShaders()) {
            Evaluation failure = validateRequiredRaster(sources, "minecraft", path);
            if (failure != null) {
                return failure;
            }
        }
        if (sodiumLoaded) {
            Evaluation failure = validateRequiredRaster(sources, "sodium", "blocks/block_layer_opaque");
            if (failure != null) {
                return failure;
            }
        }

        for (String path : DISPLAY_AUTHORED_POST_SHADERS) {
            ShaderKey key = new ShaderKey("minecraft", path, SceneLinearShaderPatcher.Stage.FRAGMENT);
            String source = sources.get(key);
            if (source == null) {
                return new Evaluation(false, "required shader is missing: minecraft:" + path + " fragment");
            }
            SceneLinearShaderPatcher.Result result = SceneLinearShaderPatcher.patch(
                    "minecraft",
                    path,
                    SceneLinearShaderPatcher.Stage.FRAGMENT,
                    HdrShaderFlavor.SCENE_POST_LINEAR,
                    source
            );
            if (!result.success()) {
                return new Evaluation(
                        false,
                        "required shader is not patchable: minecraft:" + path + " fragment ("
                                + result.failureReason() + ")"
                );
            }
        }

        return new Evaluation(
                true,
                sodiumLoaded
                        ? "all required Minecraft 26.2 and Sodium scene shaders passed preflight"
                        : "all required Minecraft 26.2 scene shaders passed preflight"
        );
    }

    public static synchronized void install(final Evaluation evaluation) {
        Evaluation checked = Objects.requireNonNull(evaluation, "evaluation");
        candidate = null;
        current = checked;
        if (checked.active()) {
            Metallum.LOGGER.info("Linear scene shader preflight active: {}", checked.reason());
        } else if (HdrSceneState.isRequested()) {
            Metallum.LOGGER.warn(
                    "Linear scene shader preflight disabled; using the legacy gamma scene contract: {}",
                    checked.reason()
            );
        } else {
            Metallum.LOGGER.debug("Linear scene shader preflight inactive: {}", checked.reason());
        }
    }

    public static synchronized void reset() {
        candidate = null;
        current = new Evaluation(false, "shader sources have not been preflighted");
    }

    static void resetForTests() {
        reset();
    }

    private static Evaluation validateRequiredRaster(
            final Map<ShaderKey, String> sources,
            final String namespace,
            final String path
    ) {
        ShaderKey key = new ShaderKey(namespace, path, SceneLinearShaderPatcher.Stage.FRAGMENT);
        String source = sources.get(key);
        if (source == null) {
            return new Evaluation(false, "required shader is missing: " + namespace + ":" + path + " fragment");
        }
        SceneLinearShaderPatcher.Result result = SceneLinearShaderPatcher.patch(
                namespace,
                path,
                SceneLinearShaderPatcher.Stage.FRAGMENT,
                HdrShaderFlavor.SCENE_RASTER_LINEAR,
                source
        );
        if (!result.success()) {
            return new Evaluation(
                    false,
                    "required shader is not patchable: " + namespace + ":" + path + " fragment ("
                            + result.failureReason() + ")"
            );
        }
        return null;
    }
}
