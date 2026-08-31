package com.metallum.client.gi.live;

import com.metallum.client.gi.receiver.GiReceiverBindingAbi;
import com.metallum.client.gi.receiver.GiReceiverLayout;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Installs the production G6 three-cascade receiver into admitted Sodium terrain shaders. */
public final class GiLiveReceiverShaderPatcher {
    public enum Stage { VERTEX, FRAGMENT }

    public record Result(String source, boolean success, String failureReason) {
        public Result {
            Objects.requireNonNull(failureReason, "failureReason");
        }
    }

    public static final String MARKER = "METALLUM_GI_G6_LIVE_RECEIVER_V1";
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

    private GiLiveReceiverShaderPatcher() {
    }

    public static Result patch(final Stage stage, final String source) {
        if (stage == null) return new Result(source, false, "G6 shader stage is missing");
        if (source == null) return new Result(null, false, "G6 shader source is missing");
        if (!source.contains(ADVANCED_MARKER)) {
            return new Result(source, false, "G6 requires an admitted Sodium Advanced shader");
        }
        if (source.contains(MARKER)) return validate(stage, source);
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
            return new Result(source, false, "G6 Sodium vertex has no unique GLSL version");
        }
        String patched = replaceExactlyOnce(
                working,
                VERTEX_DECLARATION_ANCHOR,
                VERTEX_DECLARATION_ANCHOR + "\n" + declarations(reflectionCoexists)
        );
        if (patched == null) {
            return new Result(source, false, "G6 Sodium vertex declaration anchor changed");
        }
        String anchor = reflectionCoexists
                ? VERTEX_REFLECTION_ASSIGNMENT_ANCHOR : VERTEX_BASIC_ASSIGNMENT_ANCHOR;
        patched = replaceExactlyOnce(patched, anchor, anchor + "\n" + evaluation(reflectionCoexists));
        if (patched == null) {
            return new Result(source, false, "G6 Sodium vertex assignment anchor changed");
        }
        return validate(Stage.VERTEX, patched);
    }

    private static Result patchFragment(final String source) {
        String patched = replaceExactlyOnce(
                source,
                FRAGMENT_INPUT_ANCHOR,
                FRAGMENT_INPUT_ANCHOR + "\nin vec4 " + GiReceiverBindingAbi.VARYING
                        + ";\n// " + MARKER
        );
        if (patched == null) {
            return new Result(source, false, "G6 Sodium fragment varying anchor changed");
        }
        String composition =
                "                vec3 metallumGiFallbackAmbient = max("
                        + "metallumEnvironment.ambientRadiance.rgb, vec3(0.0));\n"
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
        patched = replaceExactlyOnce(patched, AMBIENT_ANCHOR, composition);
        if (patched == null) {
            return new Result(source, false, "G6 Advanced ambient composition anchor changed");
        }
        return validate(Stage.FRAGMENT, patched);
    }

    private static String installStorageBufferVersion(final String source) {
        Matcher version = VERSION_PATTERN.matcher(source);
        if (!version.find()) return null;
        int start = version.start();
        int end = version.end();
        if (version.find()) return null;
        return source.substring(0, start) + "#version 430 core" + source.substring(end);
    }

    private static String declarations(final boolean reflectionCoexists) {
        String camera = reflectionCoexists ? "" : """
                layout(std430, binding = 16) readonly buffer MetallumGiLiveCameraV1 {
                    mat4 worldFromView;
                    ivec4 cameraBlockAndFlags;
                    vec4 cameraFractionAndMinTrans;
                } metallumGiLiveCamera;
                """;
        return ("""
                out vec4 %s;
                layout(binding = %d) uniform sampler3D %s;
                layout(binding = %d) uniform sampler3D %s;
                layout(binding = %d) uniform sampler3D %s;
                layout(binding = %d) uniform sampler3D %s;
                layout(std430, binding = %d) readonly buffer MetallumGiLiveReceiverParamsV1 {
                    ivec4 cascadeOrigins[3];
                    vec4 cascadeScaleAndCellSize[3];
                    uvec4 receiverState;
                    uvec4 atlasState;
                    uvec2 exactBrickMasks[3];
                    uvec2 reserved;
                } %s;
                %s// %s
                """).formatted(
                GiReceiverBindingAbi.VARYING,
                GiReceiverLayout.SH_RED_TEXTURE_SLOT, GiReceiverBindingAbi.SH_RED_SAMPLER,
                GiReceiverLayout.SH_GREEN_TEXTURE_SLOT, GiReceiverBindingAbi.SH_GREEN_SAMPLER,
                GiReceiverLayout.SH_BLUE_TEXTURE_SLOT, GiReceiverBindingAbi.SH_BLUE_SAMPLER,
                GiReceiverLayout.CONFIDENCE_TEXTURE_SLOT, GiReceiverBindingAbi.CONFIDENCE_SAMPLER,
                GiReceiverLayout.PARAMS_BUFFER_SLOT, GiReceiverBindingAbi.PARAMS_BLOCK,
                camera, MARKER
        ).stripTrailing();
    }

    private static String evaluation(final boolean reflectionCoexists) {
        String priorFace = reflectionCoexists
                ? "(metallumReflectionGiAxisEligible ? metallumReflectionFaceCode : 0u)" : "0u";
        String worldPosition = reflectionCoexists
                ? "metallumWorldPos"
                : "vec3(metallumGiLiveCamera.cameraBlockAndFlags.xyz)\n"
                + "            + metallumGiLiveCamera.cameraFractionAndMinTrans.xyz + position";
        return ("""
                    bool metallumGiCarrierSafe = %1$s.receiverState.y == 1u;
                    uint metallumGiPositionCarrier = ((a_Position.x >> 30u) & 3u)
                            | (((a_Position.y >> 30u) & 3u) << 2u);
                    uint metallumGiPositionFaceCode = metallumGiPositionCarrier & 7u;
                    bool metallumGiPositionAxisCarrier =
                            (metallumGiPositionCarrier & 8u) != 0u
                            && metallumGiPositionFaceCode >= 1u
                            && metallumGiPositionFaceCode <= 6u;
                    uint metallumGiFaceCode = %2$s != 0u ? %2$s
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
                    vec3 metallumGiWorldPosition = %3$s;
                    bool metallumGiContractReady = metallumGiCarrierSafe
                            && %1$s.receiverState.z == 1u;
                    vec4 metallumGiCascadeValue0 = vec4(0.0);
                    vec4 metallumGiCascadeValue1 = vec4(0.0);
                    vec4 metallumGiCascadeValue2 = vec4(0.0);
                    bool metallumGiCascadeValid0 = false;
                    bool metallumGiCascadeValid1 = false;
                    bool metallumGiCascadeValid2 = false;
                    float metallumGiCascadeFade0 = 0.0;
                    float metallumGiCascadeFade1 = 0.0;
                    float metallumGiCascadeFade2 = 0.0;
                %4$s
                    %5$s = vec4(0.0);
                    vec4 metallumGiBlended = vec4(0.0);
                    bool metallumGiBlendedValid = false;
                    if (metallumGiCascadeValid2) {
                        metallumGiBlended = metallumGiCascadeValue2;
                        metallumGiBlendedValid = true;
                    }
                    if (metallumGiCascadeValid1) {
                        metallumGiBlended = metallumGiBlendedValid
                                ? mix(metallumGiBlended, metallumGiCascadeValue1,
                                metallumGiCascadeFade1) : metallumGiCascadeValue1;
                        metallumGiBlendedValid = true;
                    }
                    if (metallumGiCascadeValid0) {
                        metallumGiBlended = metallumGiBlendedValid
                                ? mix(metallumGiBlended, metallumGiCascadeValue0,
                                metallumGiCascadeFade0) : metallumGiCascadeValue0;
                        metallumGiBlendedValid = true;
                    }
                    if (metallumGiBlendedValid) {
                        %5$s = metallumGiBlended;
                    }
                """).formatted(
                GiReceiverBindingAbi.PARAMS_BLOCK,
                priorFace,
                worldPosition,
                cascadeSamples(),
                GiReceiverBindingAbi.VARYING
        ).stripTrailing();
    }

    private static String cascadeSamples() {
        StringBuilder source = new StringBuilder();
        for (int cascade = 0; cascade < 3; cascade++) {
            source.append(("""
                    if (metallumGiContractReady
                            && (%1$s.receiverState.x & (1u << %2$d)) != 0u
                            && metallumGiFaceCode != 0u) {
                        vec3 metallumGiLocal%2$d = (metallumGiWorldPosition
                                - vec3(%1$s.cascadeOrigins[%2$d].xyz))
                                * %1$s.cascadeScaleAndCellSize[%2$d].xyz;
                        bool metallumGiInside%2$d = all(greaterThanEqual(
                                metallumGiLocal%2$d, vec3(0.0)))
                                && all(lessThan(metallumGiLocal%2$d, vec3(1.0)));
                        if (metallumGiInside%2$d) {
                            vec3 metallumGiSampleCell%2$d = clamp(
                                    metallumGiLocal%2$d * 32.0,
                                    vec3(0.5), vec3(31.5));
                            uvec3 metallumGiFootprintMinCell%2$d = uvec3(clamp(floor(
                                    metallumGiSampleCell%2$d - vec3(0.5)),
                                    vec3(0.0), vec3(31.0)));
                            uvec3 metallumGiFootprintMaxCell%2$d = uvec3(clamp(ceil(
                                    metallumGiSampleCell%2$d - vec3(0.5)),
                                    vec3(0.0), vec3(31.0)));
                            uvec3 metallumGiFootprintMinBrick%2$d =
                                    metallumGiFootprintMinCell%2$d >> uvec3(3u);
                            uvec3 metallumGiFootprintMaxBrick%2$d =
                                    metallumGiFootprintMaxCell%2$d >> uvec3(3u);
                            uvec2 metallumGiExactMask%2$d =
                                    %1$s.exactBrickMasks[%2$d];
                            bool metallumGiFootprintExact%2$d = true;
                            for (uint metallumGiBrickZ%2$d =
                                    metallumGiFootprintMinBrick%2$d.z;
                                    metallumGiBrickZ%2$d <= metallumGiFootprintMaxBrick%2$d.z;
                                    ++metallumGiBrickZ%2$d) {
                                for (uint metallumGiBrickY%2$d =
                                        metallumGiFootprintMinBrick%2$d.y;
                                        metallumGiBrickY%2$d <= metallumGiFootprintMaxBrick%2$d.y;
                                        ++metallumGiBrickY%2$d) {
                                    for (uint metallumGiBrickX%2$d =
                                            metallumGiFootprintMinBrick%2$d.x;
                                            metallumGiBrickX%2$d <= metallumGiFootprintMaxBrick%2$d.x;
                                            ++metallumGiBrickX%2$d) {
                                        uint metallumGiFootprintBrickId%2$d =
                                                (metallumGiBrickZ%2$d * 4u
                                                + metallumGiBrickY%2$d) * 4u
                                                + metallumGiBrickX%2$d;
                                        uint metallumGiFootprintExactWord%2$d =
                                                metallumGiFootprintBrickId%2$d < 32u
                                                ? metallumGiExactMask%2$d.x
                                                : metallumGiExactMask%2$d.y;
                                        metallumGiFootprintExact%2$d =
                                                metallumGiFootprintExact%2$d
                                                && (metallumGiFootprintExactWord%2$d
                                                & (1u << (metallumGiFootprintBrickId%2$d
                                                & 31u))) != 0u;
                                    }
                                }
                            }
                            if (metallumGiFootprintExact%2$d) {
                            float metallumGiAtlasZ%2$d = (float(%2$d * 32)
                                    + metallumGiSampleCell%2$d.z)
                                    / float(%1$s.atlasState.x);
                            vec3 metallumGiSampleUvw%2$d = vec3(
                                    metallumGiSampleCell%2$d.xy / 32.0,
                                    metallumGiAtlasZ%2$d);
                            vec4 metallumGiShRedValue%2$d = textureLod(
                                    %3$s, metallumGiSampleUvw%2$d, 0.0);
                            vec4 metallumGiShGreenValue%2$d = textureLod(
                                    %4$s, metallumGiSampleUvw%2$d, 0.0);
                            vec4 metallumGiShBlueValue%2$d = textureLod(
                                    %5$s, metallumGiSampleUvw%2$d, 0.0);
                            float metallumGiConfidence%2$d = textureLod(
                                    %6$s, metallumGiSampleUvw%2$d, 0.0).r;
                            bool metallumGiFinite%2$d =
                                    !any(isnan(metallumGiShRedValue%2$d))
                                    && !any(isinf(metallumGiShRedValue%2$d))
                                    && !any(isnan(metallumGiShGreenValue%2$d))
                                    && !any(isinf(metallumGiShGreenValue%2$d))
                                    && !any(isnan(metallumGiShBlueValue%2$d))
                                    && !any(isinf(metallumGiShBlueValue%2$d))
                                    && !isnan(metallumGiConfidence%2$d)
                                    && !isinf(metallumGiConfidence%2$d);
                            vec3 metallumGiIrradiance%2$d = vec3(
                                    metallumGiShRedValue%2$d.x + dot(
                                    metallumGiShRedValue%2$d.yzw, metallumGiFaceNormal),
                                    metallumGiShGreenValue%2$d.x + dot(
                                    metallumGiShGreenValue%2$d.yzw, metallumGiFaceNormal),
                                    metallumGiShBlueValue%2$d.x + dot(
                                    metallumGiShBlueValue%2$d.yzw, metallumGiFaceNormal));
                            metallumGiCascadeValue%2$d = metallumGiFinite%2$d
                                    ? vec4(max(metallumGiIrradiance%2$d, vec3(0.0)),
                                    clamp(metallumGiConfidence%2$d, 0.0, 1.0))
                                    : vec4(0.0);
                            float metallumGiEdge%2$d = min(min(
                                    metallumGiLocal%2$d.x, 1.0 - metallumGiLocal%2$d.x),
                                    min(min(metallumGiLocal%2$d.y,
                                    1.0 - metallumGiLocal%2$d.y),
                                    min(metallumGiLocal%2$d.z,
                                    1.0 - metallumGiLocal%2$d.z)));
                            metallumGiCascadeFade%2$d = smoothstep(
                                    0.0, float(%1$s.atlasState.y) / 32.0,
                                    metallumGiEdge%2$d);
                            metallumGiCascadeValid%2$d = true;
                            }
                        }
                    }
                    """).formatted(
                    GiReceiverBindingAbi.PARAMS_BLOCK,
                    cascade,
                    GiReceiverBindingAbi.SH_RED_SAMPLER,
                    GiReceiverBindingAbi.SH_GREEN_SAMPLER,
                    GiReceiverBindingAbi.SH_BLUE_SAMPLER,
                    GiReceiverBindingAbi.CONFIDENCE_SAMPLER
            ));
        }
        return source.toString().stripTrailing();
    }

    private static Result validate(final Stage stage, final String source) {
        if (countOccurrences(source, "// " + MARKER) != 1) {
            return new Result(source, false, "G6 shader marker is missing or repeated");
        }
        if (stage == Stage.VERTEX) {
            for (String sampler : GiReceiverBindingAbi.samplerNames()) {
                if (countOccurrences(source, "uniform sampler3D " + sampler + ";") != 1) {
                    return new Result(source, false, "G6 sampler is missing: " + sampler);
                }
            }
            for (int cascade = 0; cascade < 3; cascade++) {
                if (!hasExactFootprintGate(source, cascade)) {
                    return new Result(source, false,
                            "G6 vertex receiver exact-footprint gate is incomplete");
                }
            }
            if (countOccurrences(source, "out vec4 " + GiReceiverBindingAbi.VARYING + ";") != 1
                    || !source.contains("cascadeOrigins[3]")
                    || !source.contains("cascadeScaleAndCellSize[3]")
                    || !source.contains("exactBrickMasks[3]")
                    || !source.contains("receiverState.x & (1u << 2)")
                    || !source.contains("mix(metallumGiBlended, metallumGiCascadeValue0")
                    || !source.contains("((a_Position.x >> 30u) & 3u)")) {
                return new Result(source, false, "G6 vertex receiver structure is incomplete");
            }
        } else if (countOccurrences(source,
                "in vec4 " + GiReceiverBindingAbi.VARYING + ";") != 1
                || source.contains("sampler3D " + GiReceiverBindingAbi.SH_RED_SAMPLER)
                || !source.contains("vec3 diffuse = metallumGiFallbackAmbient;")) {
            return new Result(source, false, "G6 fragment receiver structure is incomplete");
        }
        return new Result(source, true, "none");
    }

    private static boolean hasExactFootprintGate(final String source, final int cascade) {
        String suffix = Integer.toString(cascade);
        return source.contains("vec3 metallumGiSampleCell" + suffix + " = clamp(")
                && source.contains("uvec3 metallumGiFootprintMinCell" + suffix
                + " = uvec3(clamp(floor(")
                && source.contains("uvec3 metallumGiFootprintMaxCell" + suffix
                + " = uvec3(clamp(ceil(")
                && source.contains("for (uint metallumGiBrickZ" + suffix + " =")
                && source.contains("for (uint metallumGiBrickY" + suffix + " =")
                && source.contains("for (uint metallumGiBrickX" + suffix + " =")
                && source.contains("uint metallumGiFootprintBrickId" + suffix + " =")
                && source.contains("uint metallumGiFootprintExactWord" + suffix + " =")
                && source.contains("&& (metallumGiFootprintExactWord" + suffix)
                && source.contains("if (metallumGiFootprintExact" + suffix + ")");
    }

    /** CPU mirror of the exact brick footprint consumed by one trilinear receiver lookup. */
    static long exactTrilinearFootprintMask(
            final double localX, final double localY, final double localZ
    ) {
        int[] min = new int[3];
        int[] max = new int[3];
        double[] local = {localX, localY, localZ};
        for (int axis = 0; axis < local.length; axis++) {
            if (!Double.isFinite(local[axis]) || local[axis] < 0.0 || local[axis] >= 1.0) {
                throw new IllegalArgumentException(
                        "G6 trilinear footprint coordinate is outside the cascade"
                );
            }
            double sampleCell = Math.clamp(local[axis] * 32.0, 0.5, 31.5);
            double texelCoordinate = sampleCell - 0.5;
            min[axis] = Math.clamp((int) Math.floor(texelCoordinate), 0, 31) >> 3;
            max[axis] = Math.clamp((int) Math.ceil(texelCoordinate), 0, 31) >> 3;
        }
        long footprint = 0L;
        for (int z = min[2]; z <= max[2]; z++) {
            for (int y = min[1]; y <= max[1]; y++) {
                for (int x = min[0]; x <= max[0]; x++) {
                    footprint |= 1L << ((z * 4 + y) * 4 + x);
                }
            }
        }
        return footprint;
    }

    private static String replaceExactlyOnce(
            final String source, final String target, final String replacement
    ) {
        int first = source.indexOf(target);
        if (first < 0 || source.indexOf(target, first + target.length()) >= 0) return null;
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
