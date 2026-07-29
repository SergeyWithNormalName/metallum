package com.metallum.client.lighting.shader;

import com.metallum.client.hdr.MetallumMaterialShaderPatcher;

/** Adds the L8 material weight to Temporal's existing R8 reactive attachment. */
public final class L8ReactiveShaderPatcher {
    private static final String OUTPUT = "metallumL8ReactiveMask";
    private static final String FOG_ANCHOR =
            "    fragColor = _linearFog(color, v_FragDistance, "
                    + "metallumMaterialDecodeColor(u_FogColor), u_EnvironmentFog, "
                    + "u_RenderFog, fadeFactor);";

    private L8ReactiveShaderPatcher() {
    }

    public static Result patch(
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage,
            final String source
    ) {
        if (source == null) {
            return Result.failure(null, "shader source is missing");
        }
        if (!namespace.equals("sodium")
                || !path.equals(AdvancedDirectLightingShaderPatcher.SODIUM_TERRAIN_PATH)) {
            return Result.failure(source, "L8 reactive output is only defined for Sodium terrain");
        }
        if (stage == MetallumMaterialShaderPatcher.Stage.VERTEX) {
            return Result.success(source);
        }
        if (stage != MetallumMaterialShaderPatcher.Stage.FRAGMENT
                || !AdvancedDirectLightingShaderPatcher.isPatched(source)) {
            return Result.failure(source, "L8 reactive output requires an Advanced fragment");
        }
        if (source.contains(OUTPUT)) {
            return source.contains("layout(location = 0) out vec4 fragColor;")
                    && source.contains("layout(location = 1) out float " + OUTPUT + ";")
                    && source.contains(OUTPUT + " = clamp(metallumL8ReactiveWeight, 0.0, 1.0);")
                    ? Result.success(source)
                    : Result.failure(source, "L8 reactive output is partial or non-canonical");
        }
        String patched = replaceExactlyOnce(
                source,
                "out vec4 fragColor;",
                "layout(location = 0) out vec4 fragColor;\n"
                        + "layout(location = 1) out float " + OUTPUT + ";"
        );
        patched = replaceExactlyOnce(
                patched,
                FOG_ANCHOR,
                "    " + OUTPUT + " = clamp(metallumL8ReactiveWeight, 0.0, 1.0);\n"
                        + FOG_ANCHOR
        );
        if (patched == null
                || !patched.contains("float metallumL8ReactiveWeight = 0.0;")
                || !patched.contains(
                "metallumL8ReactiveWeight = metallumSurfaceMaterial.reactiveWeight;")) {
            return Result.failure(source, "L8 reactive terrain anchors changed");
        }
        return Result.success(patched);
    }

    private static String replaceExactlyOnce(
            final String source,
            final String needle,
            final String replacement
    ) {
        if (source == null) {
            return null;
        }
        int first = source.indexOf(needle);
        if (first < 0 || source.indexOf(needle, first + needle.length()) >= 0) {
            return null;
        }
        return source.substring(0, first) + replacement
                + source.substring(first + needle.length());
    }

    public record Result(boolean success, String source, String failureReason) {
        static Result success(final String source) {
            return new Result(true, source, "");
        }

        static Result failure(final String source, final String failureReason) {
            return new Result(false, source, failureReason);
        }
    }
}
