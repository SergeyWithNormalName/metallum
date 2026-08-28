package com.metallum.client.gi.receiver;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Post-patches an already admitted Sodium Advanced shader with the bounded G5 receiver. */
public final class GiReceiverShaderPatcher {
    public enum Stage { VERTEX, FRAGMENT }

    public record Result(String source, boolean success, String failureReason) {
        public Result {
            Objects.requireNonNull(failureReason, "failureReason");
        }
    }

    public static final String MARKER = "METALLUM_GI_G5_RECEIVER_V1";
    private static final String ADVANCED_MARKER = "METALLUM_ADVANCED_DIRECT_LIGHTING_V1";
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(?m)^\\s*#version\\s+\\d+[^\\r\\n]*");
    private static final String VERTEX_DECLARATION_ANCHOR = "out float metallumSkyVisibility;";
    private static final String VERTEX_BASIC_ASSIGNMENT_ANCHOR =
            "    metallumLightingPosition = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;";
    private static final String VERTEX_REFLECTION_ASSIGNMENT_ANCHOR =
            "    metallumCoarseReflectionDirection = metallumCoarseReflectionDirectionVal;";
    private static final String FRAGMENT_INPUT_ANCHOR = "in float metallumSkyVisibility;";
    private static final String AMBIENT_ANCHOR =
            "    vec3 diffuse = max(metallumEnvironment.ambientRadiance.rgb, vec3(0.0));";

    private GiReceiverShaderPatcher() {
    }

    public static Result patch(final Stage stage, final String source) {
        if (stage == null) {
            return new Result(source, false, "G5 shader stage is missing");
        }
        if (source == null) {
            return new Result(null, false, "G5 shader source is missing");
        }
        if (!source.contains(ADVANCED_MARKER)) {
            return new Result(source, false, "G5 requires an already-patched Sodium Advanced shader");
        }
        if (source.contains(MARKER)) {
            return validateAlreadyPatched(stage, source);
        }
        return stage == Stage.VERTEX ? patchVertex(source) : patchFragment(source);
    }

    public static boolean isPatched(final String source) {
        return source != null && source.contains(MARKER);
    }

    private static Result patchVertex(final String source) {
        boolean reflectionCoexists = source.contains("uint metallumReflectionFaceCode")
                && source.contains("vec3 metallumWorldPos")
                && source.contains(VERTEX_REFLECTION_ASSIGNMENT_ANCHOR);
        String working = installStorageBufferVersion(source);
        if (working == null) {
            return new Result(source, false, "G5 Sodium vertex has no unique GLSL version directive");
        }
        String declarations = vertexDeclarations(reflectionCoexists);
        String patched = replaceExactlyOnce(
                working,
                VERTEX_DECLARATION_ANCHOR,
                VERTEX_DECLARATION_ANCHOR + "\n" + declarations
        );
        if (patched == null) {
            return new Result(source, false, "G5 Sodium vertex declaration anchor changed");
        }
        String assignmentAnchor = reflectionCoexists
                ? VERTEX_REFLECTION_ASSIGNMENT_ANCHOR
                : VERTEX_BASIC_ASSIGNMENT_ANCHOR;
        patched = replaceExactlyOnce(
                patched,
                assignmentAnchor,
                assignmentAnchor + "\n" + vertexEvaluation(reflectionCoexists)
        );
        if (patched == null) {
            return new Result(source, false, "G5 Sodium vertex assignment anchor changed");
        }
        return validateAlreadyPatched(Stage.VERTEX, patched);
    }

    private static String installStorageBufferVersion(final String source) {
        Matcher version = VERSION_PATTERN.matcher(source);
        if (!version.find()) {
            return null;
        }
        int start = version.start();
        int end = version.end();
        if (version.find()) {
            return null;
        }
        return source.substring(0, start) + "#version 430 core" + source.substring(end);
    }

    private static Result patchFragment(final String source) {
        String patched = replaceExactlyOnce(
                source,
                FRAGMENT_INPUT_ANCHOR,
                FRAGMENT_INPUT_ANCHOR + "\nin vec4 "
                        + GiReceiverBindingAbi.VARYING + ";\n// " + MARKER
        );
        if (patched == null) {
            return new Result(source, false, "G5 Sodium fragment varying anchor changed");
        }
        String ambientReplacement =
                "                vec3 metallumGiFallbackAmbient = max(" +
                        "metallumEnvironment.ambientRadiance.rgb, vec3(0.0));\n"
                        + "                float metallumGiConfidence = clamp("
                        + GiReceiverBindingAbi.VARYING + ".a, 0.0, 1.0);\n"
                        + "                vec3 diffuse = metallumGiFallbackAmbient;\n"
                        + "                if (metallumGiConfidence > 0.0) {\n"
                        + "                    vec3 metallumGiIncomingAmbient = max("
                        + GiReceiverBindingAbi.VARYING + ".rgb, vec3(0.0));\n"
                        + "                    diffuse = metallumGiFallbackAmbient"
                        + " * (1.0 - metallumGiConfidence)\n"
                        + "                            + metallumGiIncomingAmbient"
                        + " * metallumGiConfidence;\n"
                        + "                }";
        patched = replaceExactlyOnce(patched, AMBIENT_ANCHOR, ambientReplacement);
        if (patched == null) {
            return new Result(source, false, "G5 Advanced ambient composition anchor changed");
        }
        return validateAlreadyPatched(Stage.FRAGMENT, patched);
    }

    private static Result validateAlreadyPatched(final Stage stage, final String source) {
        if (countOccurrences(source, "// " + MARKER) != 1) {
            return new Result(source, false, "G5 shader marker is missing or repeated");
        }
        if (stage == Stage.VERTEX) {
            for (String sampler : GiReceiverBindingAbi.samplerNames()) {
                if (countOccurrences(source, "uniform sampler3D " + sampler + ";") != 1) {
                    return new Result(source, false, "G5 vertex sampler is missing or repeated: " + sampler);
                }
            }
            if (countOccurrences(source, "out vec4 " + GiReceiverBindingAbi.VARYING + ";") != 1
                    || countOccurrences(source, "layout(std430, binding = "
                    + GiReceiverLayout.PARAMS_BUFFER_SLOT + ")") != 1
                    || !source.contains("textureLod(" + GiReceiverBindingAbi.SH_RED_SAMPLER)
                    || !source.contains(GiReceiverBindingAbi.CONFIDENCE_SAMPLER
                    + ", metallumGiSampleUvw")
                    || !source.contains("((a_Position.x >> 30u) & 3u)")
                    || !source.contains("(((a_Position.y >> 30u) & 3u) << 2u)")
                    || !source.contains("(metallumGiPositionCarrier & 8u) != 0u")
                    || !source.contains("metallumGiShRedValue.x + dot(metallumGiShRedValue.yzw")
                    || !source.contains("metallumGiArm == 1u")
                    || !source.contains("metallumGiArm == 2u")) {
                return new Result(source, false, "G5 vertex receiver structure is incomplete");
            }
        } else if (countOccurrences(source,
                "in vec4 " + GiReceiverBindingAbi.VARYING + ";") != 1
                || source.contains("sampler3D " + GiReceiverBindingAbi.SH_RED_SAMPLER)
                || !source.contains("vec3 diffuse = metallumGiFallbackAmbient;")
                || !source.contains("if (metallumGiConfidence > 0.0)")
                || !source.contains(GiReceiverBindingAbi.VARYING + ".a")) {
            return new Result(source, false, "G5 fragment receiver structure is incomplete");
        }
        return new Result(source, true, "none");
    }

    private static String vertexDeclarations(final boolean reflectionCoexists) {
        String cameraBlock = reflectionCoexists ? "" : """
                layout(std430, binding = 16) readonly buffer MetallumGiVoxelCameraV1 {
                    mat4 worldFromView;
                    ivec4 cameraBlockAndFlags;
                    vec4 cameraFractionAndMinTrans;
                } metallumGiVoxelCamera;
                """;
        return ("""
                out vec4 %s;
                layout(binding = %d) uniform sampler3D %s;
                layout(binding = %d) uniform sampler3D %s;
                layout(binding = %d) uniform sampler3D %s;
                layout(binding = %d) uniform sampler3D %s;
                layout(std430, binding = %d) readonly buffer MetallumGiReceiverParamsV1 {
                    ivec4 fieldOriginAndEdge;
                    vec4 fieldScaleAndReady;
                    uvec4 receiverState;
                    uvec4 reserved;
                } %s;
                %s// %s
                """).formatted(
                GiReceiverBindingAbi.VARYING,
                GiReceiverLayout.SH_RED_TEXTURE_SLOT, GiReceiverBindingAbi.SH_RED_SAMPLER,
                GiReceiverLayout.SH_GREEN_TEXTURE_SLOT, GiReceiverBindingAbi.SH_GREEN_SAMPLER,
                GiReceiverLayout.SH_BLUE_TEXTURE_SLOT, GiReceiverBindingAbi.SH_BLUE_SAMPLER,
                GiReceiverLayout.CONFIDENCE_TEXTURE_SLOT, GiReceiverBindingAbi.CONFIDENCE_SAMPLER,
                GiReceiverLayout.PARAMS_BUFFER_SLOT, GiReceiverBindingAbi.PARAMS_BLOCK,
                cameraBlock, MARKER
        ).stripTrailing();
    }

    private static String vertexEvaluation(final boolean reflectionCoexists) {
        String priorFace = reflectionCoexists
                ? "(metallumReflectionGiAxisEligible ? metallumReflectionFaceCode : 0u)"
                : "0u";
        String worldPosition = reflectionCoexists
                ? "metallumWorldPos"
                : "vec3(metallumGiVoxelCamera.cameraBlockAndFlags.xyz)\n"
                + "            + metallumGiVoxelCamera.cameraFractionAndMinTrans.xyz + position";
        String cameraBlock = reflectionCoexists
                ? "metallumVoxelShadow.cameraBlockAndFlags.xyz"
                : "metallumGiVoxelCamera.cameraBlockAndFlags.xyz";
        return ("""
                    bool metallumGiCarrierSafe = %s.receiverState.y == 1u;
                    uint metallumGiPositionCarrier = ((a_Position.x >> 30u) & 3u)
                            | (((a_Position.y >> 30u) & 3u) << 2u);
                    uint metallumGiPositionFaceCode = metallumGiPositionCarrier & 7u;
                    bool metallumGiPositionAxisCarrier =
                            (metallumGiPositionCarrier & 8u) != 0u
                            && metallumGiPositionFaceCode >= 1u
                            && metallumGiPositionFaceCode <= 6u;
                    uint metallumGiFaceCode = %s != 0u ? %s
                            : metallumGiCarrierSafe && metallumGiPositionAxisCarrier
                            ? metallumGiPositionFaceCode : 0u;
                    vec3 metallumGiFaceNormal = metallumGiFaceCode == 1u
                            ? vec3(-1.0, 0.0, 0.0)
                            : metallumGiFaceCode == 2u ? vec3(1.0, 0.0, 0.0)
                            : metallumGiFaceCode == 3u ? vec3(0.0, -1.0, 0.0)
                            : metallumGiFaceCode == 4u ? vec3(0.0, 1.0, 0.0)
                            : metallumGiFaceCode == 5u ? vec3(0.0, 0.0, -1.0)
                            : metallumGiFaceCode == 6u ? vec3(0.0, 0.0, 1.0)
                            : vec3(0.0);
                    vec3 metallumGiWorldPosition = %s;
                    uint metallumGiArm = %s.receiverState.x;
                    vec3 metallumGiDryOrigin = floor((vec3(%s) - vec3(32.0)) / 2.0) * 2.0;
                    vec3 metallumGiAddressOrigin = metallumGiArm == 1u
                            ? metallumGiDryOrigin : vec3(%s.fieldOriginAndEdge.xyz);
                    vec3 metallumGiUvw = (metallumGiWorldPosition
                            - metallumGiAddressOrigin) * %s.fieldScaleAndReady.xyz;
                    bool metallumGiInside = all(greaterThanEqual(metallumGiUvw, vec3(0.0)))
                            && all(lessThan(metallumGiUvw, vec3(1.0)));
                    bool metallumGiContractReady = %s.receiverState.w == %du
                            && %s.receiverState.z == %du && %s.receiverState.y == 1u;
                    bool metallumGiDrySample = metallumGiArm == 1u
                            && %s.fieldScaleAndReady.w > 0.5 && metallumGiInside;
                    bool metallumGiFieldSample = metallumGiArm == 2u
                            && %s.fieldScaleAndReady.w > 0.5 && metallumGiInside;
                    %s = vec4(0.0);
                    if (metallumGiContractReady && metallumGiFaceCode != 0u
                            && (metallumGiDrySample || metallumGiFieldSample)) {
                        vec3 metallumGiSampleUvw = metallumGiUvw;
                        vec4 metallumGiShRedValue = textureLod(%s, metallumGiSampleUvw, 0.0);
                        vec4 metallumGiShGreenValue = textureLod(%s, metallumGiSampleUvw, 0.0);
                        vec4 metallumGiShBlueValue = textureLod(%s, metallumGiSampleUvw, 0.0);
                        float metallumGiSampledConfidence = textureLod(
                                %s, metallumGiSampleUvw, 0.0).r;
                        // CANDIDATE reads the create-time-cleared G4 textures and must let all
                        // four sampled values reach the varying. An explicit arm-dependent zero
                        // here would let the Metal optimizer erase the dry texture reads.
                        float metallumGiConfidenceValue = metallumGiSampledConfidence;
                        bool metallumGiSamplesFinite =
                                !any(isnan(metallumGiShRedValue))
                                && !any(isinf(metallumGiShRedValue))
                                && !any(isnan(metallumGiShGreenValue))
                                && !any(isinf(metallumGiShGreenValue))
                                && !any(isnan(metallumGiShBlueValue))
                                && !any(isinf(metallumGiShBlueValue))
                                && !isnan(metallumGiConfidenceValue)
                                && !isinf(metallumGiConfidenceValue);
                        vec3 metallumGiIrradiance = vec3(
                                metallumGiShRedValue.x + dot(metallumGiShRedValue.yzw, metallumGiFaceNormal),
                                metallumGiShGreenValue.x + dot(metallumGiShGreenValue.yzw, metallumGiFaceNormal),
                                metallumGiShBlueValue.x + dot(metallumGiShBlueValue.yzw, metallumGiFaceNormal));
                        vec4 metallumGiEvaluated = metallumGiSamplesFinite
                                ? vec4(max(metallumGiIrradiance, vec3(0.0)),
                                clamp(metallumGiConfidenceValue, 0.0, 1.0))
                                : vec4(0.0);
                        %s = metallumGiEvaluated;
                    }
                """).formatted(
                GiReceiverBindingAbi.PARAMS_BLOCK,
                priorFace, priorFace,
                worldPosition,
                GiReceiverBindingAbi.PARAMS_BLOCK, cameraBlock,
                GiReceiverBindingAbi.PARAMS_BLOCK, GiReceiverBindingAbi.PARAMS_BLOCK,
                GiReceiverBindingAbi.PARAMS_BLOCK, GiReceiverLayout.ABI_VERSION,
                GiReceiverBindingAbi.PARAMS_BLOCK, GiReceiverLayout.CELL_SIZE_BLOCKS,
                GiReceiverBindingAbi.PARAMS_BLOCK,
                GiReceiverBindingAbi.PARAMS_BLOCK,
                GiReceiverBindingAbi.PARAMS_BLOCK,
                GiReceiverBindingAbi.VARYING,
                GiReceiverBindingAbi.SH_RED_SAMPLER,
                GiReceiverBindingAbi.SH_GREEN_SAMPLER,
                GiReceiverBindingAbi.SH_BLUE_SAMPLER,
                GiReceiverBindingAbi.CONFIDENCE_SAMPLER,
                GiReceiverBindingAbi.VARYING
        ).stripTrailing();
    }

    private static String replaceExactlyOnce(
            final String source,
            final String target,
            final String replacement
    ) {
        int first = source.indexOf(target);
        if (first < 0 || source.indexOf(target, first + target.length()) >= 0) {
            return null;
        }
        return source.substring(0, first) + replacement + source.substring(first + target.length());
    }

    private static int countOccurrences(final String source, final String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }
}
