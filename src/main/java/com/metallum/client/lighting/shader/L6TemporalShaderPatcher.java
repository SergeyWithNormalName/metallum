package com.metallum.client.lighting.shader;

import com.metallum.client.hdr.MetallumMaterialShaderPatcher;

/**
 * Experimental opaque-terrain L6 path: one unbiased soft-filter tap per frame, reconstructed
 * as a visibility ratio before fog/compositing.  It deliberately duplicates only the diffuse
 * visibility helpers, leaving the production material-specular helper exact.
 */
public final class L6TemporalShaderPatcher {
    public static final String HISTORY_OUTPUT = "metallumL6TemporalHistory";
    public static final String HISTORY_SAMPLER = "metallumL6TemporalHistorySampler";
    public static final String PARAMS_BLOCK = "MetallumL6TemporalParamsV1";

    private static final String DIRECT_FUNCTION = "vec3 metallumEvaluateClusteredDirectV1(";
    private static final String SOFT_VISIBILITY_FUNCTION = "vec3 metallumVoxelSoftCachedVisibilityV1(";
    private static final String VISIBILITY_FUNCTION = "vec3 metallumVoxelVisibilityV1(";

    private L6TemporalShaderPatcher() {
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
        if (!"sodium".equals(namespace)
                || !AdvancedDirectLightingShaderPatcher.SODIUM_TERRAIN_PATH.equals(path)) {
            return Result.failure(source, "L6 temporal output is only defined for Sodium terrain");
        }
        if (stage == MetallumMaterialShaderPatcher.Stage.VERTEX) {
            return Result.success(source);
        }
        if (stage != MetallumMaterialShaderPatcher.Stage.FRAGMENT
                || !AdvancedDirectLightingShaderPatcher.isPatched(source)) {
            return Result.failure(source, "L6 temporal output requires an Advanced terrain fragment");
        }
        if (source.contains(HISTORY_OUTPUT)) {
            return isCanonical(source)
                    ? Result.success(source)
                    : Result.failure(source, "L6 temporal output is partial or non-canonical");
        }
        if (source.contains("metallumL8ReactiveMask")) {
            return Result.failure(source, "L6 temporal and L8 reactive outputs cannot share color(1)");
        }

        String softVisibility = extractFunction(source, SOFT_VISIBILITY_FUNCTION);
        String visibility = extractFunction(source, VISIBILITY_FUNCTION);
        String direct = extractFunction(source, DIRECT_FUNCTION);
        if (softVisibility == null || visibility == null || direct == null) {
            return Result.failure(source, "L6 temporal helper anchors changed");
        }

        String temporalVisibilityHelpers = softVisibility
                .replace("metallumVoxelSoftCachedVisibilityV1", "metallumL6TemporalSoftVisibilityV1")
                + "\n"
                + visibility
                .replace("metallumVoxelVisibilityV1", "metallumL6TemporalStochasticVisibilityV1")
                .replace("metallumVoxelSoftCachedVisibilityV1", "metallumL6TemporalSoftVisibilityV1");
        try {
            temporalVisibilityHelpers = AdvancedDirectLightingShaderPatcher
                    .installL6TemporalStochasticOneTap(temporalVisibilityHelpers);
        } catch (RuntimeException exception) {
            return Result.failure(source, "L6 temporal stochastic helper rewrite failed: " + exception.getMessage());
        }

        String temporalDirect = temporalDirectFunction(direct);
        if (temporalDirect == null) {
            return Result.failure(source, "L6 temporal clustered-direct anchors changed");
        }

        String patched = replaceExactlyOnce(
                source,
                "out vec4 fragColor;",
                temporalDeclarations()
        );
        if (patched == null) {
            return Result.failure(source, "L6 temporal fragment output anchor changed");
        }
        int directEnd = patched.indexOf(direct) + direct.length();
        if (directEnd < direct.length()) {
            return Result.failure(source, "L6 temporal direct helper moved after declaration rewrite");
        }
        patched = patched.substring(0, directEnd)
                + "\n"
                + temporalVisibilityHelpers
                + "\n"
                + temporalHelpers()
                + "\n"
                + temporalDirect
                + patched.substring(directEnd);
        patched = replaceDirectCall(patched);
        if (patched == null || !isCanonical(patched)) {
            return Result.failure(source, "L6 temporal terrain direct-light anchor changed");
        }
        return Result.success(patched);
    }

    private static String temporalDeclarations() {
        return """
                layout(location = 0) out vec4 fragColor;
                layout(location = 1) out vec4 metallumL6TemporalHistory;
                layout(binding = 10) uniform sampler2D metallumL6TemporalHistorySampler;
                layout(std430, binding = 12) readonly buffer MetallumL6TemporalParamsV1 {
                    mat4 previousViewProjection;
                    mat4 inversePreviousProjection;
                    mat4 previousView;
                    mat4 inversePreviousView;
                    vec4 cameraDelta;
                    uvec4 extentAndFlags;
                    vec4 jitter;
                    uvec4 reserved;
                } metallumL6Temporal;
                """.stripTrailing();
    }

    private static String temporalHelpers() {
        return """
                vec3 metallumL6TemporalViewPositionV1(
                        ivec2 pixel, float depth, ivec2 extent) {
                    vec2 rasterUv = (vec2(pixel) + vec2(0.5)) / vec2(extent);
                    vec2 ndc = vec2(rasterUv.x * 2.0 - 1.0, 1.0 - rasterUv.y * 2.0);
                    vec4 homogeneous = metallumL6Temporal.inversePreviousProjection
                            * vec4(ndc, depth, 1.0);
                    if (abs(homogeneous.w) < 0.000001) {
                        return vec3(0.0);
                    }
                    return homogeneous.xyz / homogeneous.w;
                }

                bool metallumL6TemporalHistoryNormalV1(
                        ivec2 pixel, ivec2 extent, float centerDepth,
                        float depthTolerance, out vec3 normal) {
                    if (pixel.x + 1 >= extent.x || pixel.y + 1 >= extent.y) {
                        return false;
                    }
                    float depthX = texelFetch(
                            metallumL6TemporalHistorySampler, pixel + ivec2(1, 0), 0).a;
                    float depthY = texelFetch(
                            metallumL6TemporalHistorySampler, pixel + ivec2(0, 1), 0).a;
                    if (depthX <= 0.0 || depthY <= 0.0
                            || abs(depthX - centerDepth) > depthTolerance
                            || abs(depthY - centerDepth) > depthTolerance) {
                        return false;
                    }
                    vec3 center = metallumL6TemporalViewPositionV1(pixel, centerDepth, extent);
                    vec3 right = metallumL6TemporalViewPositionV1(
                            pixel + ivec2(1, 0), depthX, extent);
                    vec3 down = metallumL6TemporalViewPositionV1(
                            pixel + ivec2(0, 1), depthY, extent);
                    vec3 crossNormal = cross(right - center, down - center);
                    float normalLengthSquared = dot(crossNormal, crossNormal);
                    if (normalLengthSquared < 0.00000001
                            || any(isnan(crossNormal)) || any(isinf(crossNormal))) {
                        return false;
                    }
                    normal = crossNormal * inversesqrt(normalLengthSquared);
                    return true;
                }

                vec3 metallumL6TemporalResolveRatioV1(
                        vec3 currentRatio, vec3 viewPosition, vec3 currentNormal) {
                    if ((metallumL6Temporal.extentAndFlags.z & 1u) == 0u) {
                        return currentRatio;
                    }
                    vec3 worldRelative = mat3(metallumVoxelShadow.worldFromView) * viewPosition;
                    vec4 previousClip = metallumL6Temporal.previousViewProjection
                            * vec4(worldRelative + metallumL6Temporal.cameraDelta.xyz, 1.0);
                    if (previousClip.w <= 0.000001) {
                        return currentRatio;
                    }
                    vec3 previousNdc = previousClip.xyz / previousClip.w;
                    if (any(lessThan(previousNdc.xy, vec2(-1.0)))
                            || any(greaterThan(previousNdc.xy, vec2(1.0)))
                            || previousNdc.z <= 0.0 || previousNdc.z >= 1.0) {
                        return currentRatio;
                    }
                    ivec2 extent = textureSize(metallumL6TemporalHistorySampler, 0);
                    if (any(lessThanEqual(extent, ivec2(1)))) {
                        return currentRatio;
                    }
                    vec2 historyUv = vec2(
                            previousNdc.x * 0.5 + 0.5,
                            (1.0 - previousNdc.y) * 0.5);
                    ivec2 pixel = ivec2(historyUv * vec2(extent));
                    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, extent))) {
                        return currentRatio;
                    }
                    vec4 history = texelFetch(metallumL6TemporalHistorySampler, pixel, 0);
                    float normalizedDepthTolerance = max(0.00075, previousNdc.z * 0.004);
                    if (history.a < 0.0 || history.a > 1.0
                            || any(isnan(history.rgb)) || any(isinf(history.rgb))) {
                        return currentRatio;
                    }
                    vec4 expectedPreviousView = metallumL6Temporal.previousView
                            * vec4(worldRelative + metallumL6Temporal.cameraDelta.xyz, 1.0);
                    vec3 recordedPreviousView = metallumL6TemporalViewPositionV1(
                            pixel, history.a, extent);
                    float expectedPreviousViewDepth = abs(expectedPreviousView.z);
                    float recordedPreviousViewDepth = abs(recordedPreviousView.z);
                    float viewDepthTolerance = min(0.25, max(
                            0.03125, expectedPreviousViewDepth * 0.001));
                    if (!(expectedPreviousViewDepth > 0.0)
                            || any(isnan(recordedPreviousView)) || any(isinf(recordedPreviousView))
                            || abs(recordedPreviousViewDepth - expectedPreviousViewDepth)
                            > viewDepthTolerance) {
                        return currentRatio;
                    }
                    vec3 historyNormal;
                    if (!metallumL6TemporalHistoryNormalV1(
                            pixel, extent, history.a, normalizedDepthTolerance, historyNormal)) {
                        return currentRatio;
                    }
                    vec3 worldNormal = mat3(metallumVoxelShadow.worldFromView) * currentNormal;
                    vec3 expectedNormal = mat3(metallumL6Temporal.previousView) * worldNormal;
                    float expectedLengthSquared = dot(expectedNormal, expectedNormal);
                    if (expectedLengthSquared < 0.00000001) {
                        return currentRatio;
                    }
                    expectedNormal *= inversesqrt(expectedLengthSquared);
                    if (dot(historyNormal, expectedNormal) < 0.78) {
                        return currentRatio;
                    }
                    return mix(currentRatio, clamp(history.rgb, vec3(0.0), vec3(1.0)), 0.75);
                }
                """.stripTrailing();
    }

    private static String temporalDirectFunction(final String source) {
        String renamed = source.replaceFirst(
                "vec3 metallumEvaluateClusteredDirectV1\\(",
                "vec3 metallumEvaluateClusteredDirectTemporalV1("
        );
        int albedoEnd = renamed.indexOf("vec3 albedo)");
        if (albedoEnd < 0) {
            return null;
        }
        int bodyStart = renamed.indexOf('{', albedoEnd + "vec3 albedo)".length());
        if (bodyStart < 0) {
            return null;
        }
        renamed = renamed.substring(0, albedoEnd)
                + "vec3 albedo,\n"
                + "                    out vec3 unshadowedDirect,\n"
                + "                    out vec3 shadowedDirect,\n"
                + "                    out bool temporalHistoryEligible) {\n"
                + "                unshadowedDirect = vec3(0.0);\n"
                + "                shadowedDirect = vec3(0.0);\n"
                + "                temporalHistoryEligible = false;"
                + renamed.substring(bodyStart + 1);
        renamed = replaceExactlyOnce(
                renamed,
                "visibility = metallumVoxelVisibilityV1(",
                "visibility = metallumL6TemporalStochasticVisibilityV1("
        );
        renamed = replaceExactlyOnce(
                renamed,
                "                    direct += unshadowedContribution * visibility;",
                """
                                    unshadowedDirect += unshadowedContribution;
                                    shadowedDirect += unshadowedContribution * visibility;
                                    direct += unshadowedContribution * visibility;"""
        );
        if (renamed == null) {
            return null;
        }
        renamed = replaceExactlyOnce(
                renamed,
                "                vec3 direct = vec3(0.0);",
                """
                                temporalHistoryEligible = localShadowContractValid;
                                vec3 direct = vec3(0.0);"""
        );
        if (renamed == null) {
            return null;
        }
        int returnIndex = renamed.lastIndexOf("                return direct;");
        if (returnIndex < 0 || renamed.indexOf("                return direct;", returnIndex + 1) >= 0) {
            return null;
        }
        return renamed.substring(0, returnIndex)
                + "                return direct;"
                + renamed.substring(returnIndex + "                return direct;".length());
    }

    private static String temporalDirectCall() {
        return """
                    vec3 metallumL6TemporalUnshadowedDirect;
                    vec3 metallumL6TemporalShadowedDirect;
                    bool metallumL6TemporalHistoryEligible;
                    vec3 metallumL6TemporalExactDirect = metallumEvaluateClusteredDirectTemporalV1(
                            metallumLightingPosition, metallumDirectNormal, metallumPreparedAlbedo,
                            metallumL6TemporalUnshadowedDirect, metallumL6TemporalShadowedDirect,
                            metallumL6TemporalHistoryEligible);
                    vec3 metallumL6TemporalCurrentRatio = clamp(
                            metallumL6TemporalShadowedDirect
                                    / max(metallumL6TemporalUnshadowedDirect, vec3(0.00001)),
                            vec3(0.0), vec3(1.0));
                    vec3 metallumL6TemporalReconstructedRatio = metallumL6TemporalHistoryEligible
                            ? metallumL6TemporalResolveRatioV1(
                            metallumL6TemporalCurrentRatio, metallumLightingPosition, metallumDirectNormal)
                            : metallumL6TemporalCurrentRatio;
                    color.rgb += metallumL6TemporalExactDirect - metallumL6TemporalShadowedDirect
                            + metallumL6TemporalUnshadowedDirect * metallumL6TemporalReconstructedRatio;
                    metallumL6TemporalHistory = vec4(
                            metallumL6TemporalReconstructedRatio, gl_FragCoord.z);""";
    }

    private static String replaceDirectCall(final String source) {
        final String startMarker = "color.rgb += metallumEvaluateClusteredDirectV1(";
        final String endMarker = "metallumPreparedAlbedo);";
        int start = source.indexOf(startMarker);
        if (start < 0 || source.indexOf(startMarker, start + startMarker.length()) >= 0) {
            return null;
        }
        int lineStart = source.lastIndexOf('\n', start) + 1;
        int end = source.indexOf(endMarker, start);
        if (end < 0) {
            return null;
        }
        end += endMarker.length();
        return source.substring(0, lineStart) + temporalDirectCall()
                + source.substring(end);
    }

    private static boolean isCanonical(final String source) {
        return source.contains("layout(location = 0) out vec4 fragColor;")
                && source.contains("layout(location = 1) out vec4 " + HISTORY_OUTPUT + ";")
                && source.contains("layout(binding = 10) uniform sampler2D " + HISTORY_SAMPLER + ";")
                && source.contains("layout(std430, binding = 12) readonly buffer " + PARAMS_BLOCK)
                && source.contains("metallumEvaluateClusteredDirectTemporalV1(")
                && source.contains("METALLUM_EXPERIMENTAL_L6_TEMPORAL_ONE_TAP")
                && source.contains(HISTORY_OUTPUT + " = vec4(");
    }

    private static String extractFunction(final String source, final String signature) {
        int start = source.indexOf(signature);
        if (start < 0 || source.indexOf(signature, start + signature.length()) >= 0) {
            return null;
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        return null;
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
