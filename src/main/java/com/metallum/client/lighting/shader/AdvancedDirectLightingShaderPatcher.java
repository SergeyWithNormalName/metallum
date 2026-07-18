package com.metallum.client.lighting.shader;

import com.metallum.client.hdr.MetallumMaterialShaderPatcher;
import com.metallum.client.renderer.AdvancedLightingLayout;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.SunShadowLayout;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 26.2 / Sodium 0.9.1 source adapter for the first clustered direct-light model.
 *
 * <p>The adapter accepts an already-patched METALLUM material source. The lighting model is
 * an independent variant axis: {@link LightingModel#VANILLA} is byte-for-byte inert, while
 * {@link LightingModel#ADVANCED} adds only the L3 resources and direct-light evaluation.</p>
 */
public final class AdvancedDirectLightingShaderPatcher {
    public record Result(String source, boolean success, String failureReason) {
        private static Result success(final String source) {
            return new Result(source, true, "");
        }

        private static Result failure(final String source, final String reason) {
            return new Result(source, false, reason);
        }
    }

    public record ShaderKey(
            String namespace,
            String path,
            MetallumMaterialShaderPatcher.Stage stage
    ) {
        public ShaderKey {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(stage, "stage");
        }
    }

    public record Preflight(boolean ready, String failureReason) {
        private static Preflight admitted() {
            return new Preflight(true, "");
        }

        private static Preflight rejected(final String reason) {
            return new Preflight(false, reason);
        }
    }

    public static final String SODIUM_TERRAIN_PATH = "blocks/block_layer_opaque";
    public static final String VANILLA_ENTITY_PATH = "core/entity";
    public static final String SHADOW_SAMPLER_0 = "metallumSunShadow0";
    public static final String SHADOW_SAMPLER_1 = "metallumSunShadow1";
    public static final String SHADOW_SAMPLER_2 = "metallumSunShadow2";

    private static final String MARKER = "METALLUM_ADVANCED_DIRECT_LIGHTING_V1";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^\\s*#version\\s+\\d+[^\\r\\n]*");
    private static final Pattern LAYOUT_PATTERN = Pattern.compile("layout\\s*\\(([^)]*)\\)");
    private static final Pattern BINDING_TOKEN_PATTERN = Pattern.compile("\\bbinding\\b");
    private static final Pattern BINDING_PATTERN = Pattern.compile(
            "\\bbinding\\s*=\\s*(0|[1-9]\\d*)\\s*(?=,|$)"
    );
    private static final int MALFORMED_SLOT = Integer.MIN_VALUE;
    private static final List<ShaderKey> REQUIRED_TARGETS = List.of(
            new ShaderKey("sodium", SODIUM_TERRAIN_PATH,
                    MetallumMaterialShaderPatcher.Stage.VERTEX),
            new ShaderKey("sodium", SODIUM_TERRAIN_PATH,
                    MetallumMaterialShaderPatcher.Stage.FRAGMENT),
            new ShaderKey("minecraft", VANILLA_ENTITY_PATH,
                    MetallumMaterialShaderPatcher.Stage.VERTEX),
            new ShaderKey("minecraft", VANILLA_ENTITY_PATH,
                    MetallumMaterialShaderPatcher.Stage.FRAGMENT)
    );

    private static final String FRAGMENT_ABI_AND_HELPERS = """

            // METALLUM_ADVANCED_DIRECT_LIGHTING_V1
            struct MetallumGpuLightV1 {
                vec4 positionRadius;
                vec4 linearColorIntensity;
                uvec4 metadata;
            };

            struct MetallumClusterHeaderV1 {
                uint offset;
                uint count;
            };

            layout(std430, binding = 26) readonly buffer MetallumEnvironmentShadowV1 {
                mat4 shadowFromView0;
                mat4 shadowFromView1;
                mat4 shadowFromView2;
                vec4 directionAndFlags;
                vec4 directionalRadiance;
                vec4 skyIrradiance;
                vec4 ambientRadiance;
                vec4 cascadeSplits;
                vec4 texelAndBias;
                vec4 cascadeBlend;
                uvec4 contract;
                vec4 worldUpAndMedium;
                vec4 cascadeNormalBias;
                vec4 reserved1;
                vec4 reserved2;
            } metallumEnvironment;

            layout(binding = 13) uniform sampler2DShadow metallumSunShadow0;
            layout(binding = 14) uniform sampler2DShadow metallumSunShadow1;
            layout(binding = 15) uniform sampler2DShadow metallumSunShadow2;

            layout(std430, binding = 27) readonly buffer MetallumLightingParamsV1 {
                mat4 viewRotation;
                mat4 projection;
                uvec4 gridAndLightCount;
                uvec4 extentAndClusterCap;
                vec4 depth;
                uvec4 frameIdAndGeneration;
                uvec4 capacitiesAndFlags;
                uvec4 reserved0;
                uvec4 reserved1;
                uvec4 reserved2;
            } metallumLighting;

            layout(std430, binding = 28) readonly buffer MetallumGpuLightsV1 {
                MetallumGpuLightV1 lights[];
            } metallumLightBuffer;

            layout(std430, binding = 29) readonly buffer MetallumClusterHeadersV1 {
                MetallumClusterHeaderV1 headers[];
            } metallumClusterHeaderBuffer;

            layout(std430, binding = 30) readonly buffer MetallumClusterIndicesV1 {
                uint indices[];
            } metallumClusterIndexBuffer;

            struct MetallumVoxelProxyV1 {
                vec4 minWorldRelative;
                vec4 maxWorldRelative;
            };

            struct MetallumVoxelLevelV1 {
                ivec4 originAndSpan;
                uvec4 levelLayout;
            };

            layout(std430, binding = 14) readonly buffer MetallumVoxelVisibilityCacheV1 {
                uvec2 hits[];
            } metallumVoxelVisibilityCache;

            layout(std430, binding = 15) readonly buffer MetallumVoxelProxiesV1 {
                MetallumVoxelProxyV1 proxies[];
            } metallumVoxelProxyBuffer;

            layout(std430, binding = 16) readonly buffer MetallumVoxelShadowParamsV1 {
                mat4 worldFromView;
                ivec4 cameraBlockAndFlags;
                vec4 cameraFractionAndMinTrans;
                uvec4 caps;
                uvec4 proxyAndFrame;
                ivec4 levelOriginAndSpan0;
                uvec4 levelLayout0;
                ivec4 levelOriginAndSpan1;
                uvec4 levelLayout1;
                ivec4 levelOriginAndSpan2;
                uvec4 levelLayout2;
                uvec4 contract;
                uvec4 worldAndFlags;
            } metallumVoxelShadow;

            layout(std430, binding = 17) readonly buffer MetallumVoxelOccupancy0V1 {
                uint words[];
            } metallumVoxelOccupancy0;
            layout(std430, binding = 18) readonly buffer MetallumVoxelOccupancy1V1 {
                uint words[];
            } metallumVoxelOccupancy1;
            layout(std430, binding = 19) readonly buffer MetallumVoxelOccupancy2V1 {
                uint words[];
            } metallumVoxelOccupancy2;

            // L5 stores optical/material data as raw bytes. Reading uint words here and
            // extracting little-endian lanes keeps GLSL 430 accesses naturally aligned.
            layout(std430, binding = 20) readonly buffer MetallumVoxelOptical0V1 {
                uint words[];
            } metallumVoxelOptical0;
            layout(std430, binding = 21) readonly buffer MetallumVoxelOptical1V1 {
                uint words[];
            } metallumVoxelOptical1;
            layout(std430, binding = 22) readonly buffer MetallumVoxelOptical2V1 {
                uint words[];
            } metallumVoxelOptical2;

            layout(std430, binding = 23) readonly buffer MetallumVoxelMetadata0V1 {
                uvec4 tags[];
            } metallumVoxelMetadata0;
            layout(std430, binding = 24) readonly buffer MetallumVoxelMetadata1V1 {
                uvec4 tags[];
            } metallumVoxelMetadata1;
            layout(std430, binding = 25) readonly buffer MetallumVoxelMetadata2V1 {
                uvec4 tags[];
            } metallumVoxelMetadata2;

            vec3 metallumSafeNormalV1(vec3 surfaceNormal) {
                float normalScale = max(
                        abs(surfaceNormal.x),
                        max(abs(surfaceNormal.y), abs(surfaceNormal.z)));
                if (!(normalScale > 0.0) || isinf(normalScale)) {
                    return vec3(0.0);
                }
                vec3 scaledNormal = surfaceNormal / normalScale;
                float lengthSquared = dot(scaledNormal, scaledNormal);
                if (!(lengthSquared > 0.0) || isnan(lengthSquared) || isinf(lengthSquared)) {
                    return vec3(0.0);
                }
                return scaledNormal * inversesqrt(lengthSquared);
            }

            float metallumPcfV1(sampler2DShadow shadowMap, vec3 coordinate) {
                if (any(lessThan(coordinate.xy, vec2(0.0)))
                        || any(greaterThan(coordinate.xy, vec2(1.0)))
                        || coordinate.z < 0.0 || coordinate.z > 1.0) {
                    return 1.0;
                }
                float lit = 0.0;
                float texel = max(metallumEnvironment.texelAndBias.x, 0.000001)
                        * max(metallumEnvironment.texelAndBias.w, 0.5);
                float receiverDepth = coordinate.z
                        + max(metallumEnvironment.texelAndBias.y, 0.0);
                for (int y = -1; y <= 1; ++y) {
                    for (int x = -1; x <= 1; ++x) {
                        vec2 uv = clamp(
                                coordinate.xy + vec2(float(x), float(y)) * texel,
                                vec2(0.0), vec2(1.0));
                        lit += texture(shadowMap, vec3(uv, receiverDepth));
                    }
                }
                return lit * (1.0 / 9.0);
            }

            float metallumCascadeVisibilityV1(int cascade, vec3 viewPosition, vec3 normal) {
                float normalBias = cascade == 0
                        ? metallumEnvironment.cascadeNormalBias.x
                        : cascade == 1
                                ? metallumEnvironment.cascadeNormalBias.y
                                : metallumEnvironment.cascadeNormalBias.z;
                vec3 offsetPosition = viewPosition
                        + normal * max(
                                normalBias,
                                max(metallumEnvironment.texelAndBias.z, 0.0));
                vec4 clip;
                if (cascade == 0) {
                    clip = metallumEnvironment.shadowFromView0 * vec4(offsetPosition, 1.0);
                } else if (cascade == 1) {
                    clip = metallumEnvironment.shadowFromView1 * vec4(offsetPosition, 1.0);
                } else {
                    clip = metallumEnvironment.shadowFromView2 * vec4(offsetPosition, 1.0);
                }
                if (!(abs(clip.w) > 0.000001) || isnan(clip.w) || isinf(clip.w)) {
                    return 1.0;
                }
                vec3 coordinate = clip.xyz / clip.w;
                coordinate.xy = coordinate.xy * 0.5 + 0.5;
                if (cascade == 0) {
                    return metallumPcfV1(metallumSunShadow0, coordinate);
                }
                if (cascade == 1) {
                    return metallumPcfV1(metallumSunShadow1, coordinate);
                }
                return metallumPcfV1(metallumSunShadow2, coordinate);
            }

            float metallumSunVisibilityV1(vec3 viewPosition, vec3 normal) {
                if (metallumEnvironment.contract.x != 1u
                        || (metallumEnvironment.contract.w & 1u) == 0u) {
                    return 1.0;
                }
                int cascadeCount = int(clamp(metallumEnvironment.contract.y, 2u, 3u));
                float viewDepth = max(-viewPosition.z, 0.0);
                int cascade = viewDepth <= metallumEnvironment.cascadeSplits.x ? 0
                        : viewDepth <= metallumEnvironment.cascadeSplits.y ? 1 : 2;
                if (cascade >= cascadeCount
                        || viewDepth > metallumEnvironment.cascadeSplits[cascadeCount - 1]) {
                    return 1.0;
                }
                float visibility = metallumCascadeVisibilityV1(cascade, viewPosition, normal);
                float split = metallumEnvironment.cascadeSplits[cascade];
                float previous = cascade == 0 ? 0.0
                        : metallumEnvironment.cascadeSplits[cascade - 1];
                float blendWidth = max(
                        (split - previous) * metallumEnvironment.cascadeBlend[cascade],
                        0.0001);
                float blend = smoothstep(split - blendWidth, split, viewDepth);
                if (blend > 0.0) {
                    if (cascade + 1 < cascadeCount) {
                        visibility = mix(
                                visibility,
                                metallumCascadeVisibilityV1(cascade + 1, viewPosition, normal),
                                blend);
                    } else {
                        visibility = mix(visibility, 1.0, blend);
                    }
                }
                return visibility;
            }

            vec3 metallumEvaluateEnvironmentV1(
                    vec3 viewPosition,
                    vec3 normal,
                    vec3 albedo,
                    float skyVisibility) {
                if (metallumEnvironment.contract.x != 1u) {
                    return vec3(0.0);
                }
                if (dot(normal, normal) == 0.0) {
                    return vec3(0.0);
                }
                float skyOcclusion = clamp(skyVisibility, 0.0, 1.0);
                float hemisphere = 0.30 + 0.70 * max(
                        dot(normal, normalize(metallumEnvironment.worldUpAndMedium.xyz)),
                        0.0);
                vec3 toLight = metallumEnvironment.directionAndFlags.xyz;
                float nDotL = max(dot(normal, toLight), 0.0);
                float directionalWeight = skyOcclusion * nDotL;
                float sunVisibility = 1.0;
                if (directionalWeight > 0.0) {
                    sunVisibility = metallumSunVisibilityV1(viewPosition, normal);
                }
                const float shadowedSkyVisibility = 0.42;
                float skyShadow = mix(shadowedSkyVisibility, 1.0, sunVisibility);
                vec3 diffuse = max(metallumEnvironment.ambientRadiance.rgb, vec3(0.0));
                diffuse += max(metallumEnvironment.skyIrradiance.rgb, vec3(0.0))
                        * (skyOcclusion * hemisphere * skyShadow);
                if (directionalWeight > 0.0) {
                    diffuse += max(metallumEnvironment.directionalRadiance.rgb, vec3(0.0))
                            * (directionalWeight * sunVisibility);
                }
                return albedo * diffuse * 0.31830988618;
            }

            bool metallumFiniteVec3V1(vec3 value) {
                return !any(isnan(value)) && !any(isinf(value));
            }

            int metallumPositiveModV1(int value, int modulus) {
                int remainder = value % modulus;
                return remainder < 0 ? remainder + modulus : remainder;
            }

            int metallumFloorDivV1(int value, int divisor) {
                int quotient = value / divisor;
                int remainder = value % divisor;
                return remainder < 0 ? quotient - 1 : quotient;
            }

            bool metallumPowerOfTwoV1(int value) {
                return value > 0 && (value & (value - 1)) == 0;
            }

            ivec3 metallumShiftRightV1(ivec3 value, int shift) {
                return ivec3(
                        value.x >> shift,
                        value.y >> shift,
                        value.z >> shift);
            }

            ivec3 metallumPowerOfTwoModV1(ivec3 value, int mask) {
                return value & ivec3(mask);
            }

            MetallumVoxelLevelV1 metallumVoxelLevelV1(uint level) {
                if (level == 0u) {
                    return MetallumVoxelLevelV1(
                            metallumVoxelShadow.levelOriginAndSpan0,
                            metallumVoxelShadow.levelLayout0);
                }
                if (level == 1u) {
                    return MetallumVoxelLevelV1(
                            metallumVoxelShadow.levelOriginAndSpan1,
                            metallumVoxelShadow.levelLayout1);
                }
                return MetallumVoxelLevelV1(
                        metallumVoxelShadow.levelOriginAndSpan2,
                        metallumVoxelShadow.levelLayout2);
            }

            bool metallumVoxelLevelValidV1(MetallumVoxelLevelV1 level) {
                uint subdivision = level.levelLayout.x;
                uint logicalEdge = level.levelLayout.y;
                uint brickDimension = level.levelLayout.z;
                uint brickBlockEdge = level.levelLayout.w;
                return (subdivision == 1u || subdivision == 2u || subdivision == 4u)
                        && logicalEdge >= 32u && logicalEdge <= 384u
                        && (logicalEdge & 31u) == 0u
                        && brickDimension == logicalEdge / 32u
                        && brickDimension > 0u
                        && brickBlockEdge == 32u / subdivision
                        && level.originAndSpan.w > 0
                        && level.originAndSpan.w <= 384
                        && all(greaterThanEqual(
                        level.originAndSpan.xyz, ivec3(-500000000)))
                        && all(lessThanEqual(
                        level.originAndSpan.xyz, ivec3(500000000)))
                        && uint(level.originAndSpan.w) == logicalEdge / subdivision;
            }

            bool metallumVoxelLevelContainsV1(
                    MetallumVoxelLevelV1 level,
                    vec3 startWorldRelative,
                    vec3 endWorldRelative) {
                vec3 relativeOrigin = vec3(
                        level.originAndSpan.xyz
                                - metallumVoxelShadow.cameraBlockAndFlags.xyz);
                vec3 relativeEnd = relativeOrigin + vec3(level.originAndSpan.w);
                return all(greaterThanEqual(startWorldRelative, relativeOrigin))
                        && all(lessThan(startWorldRelative, relativeEnd))
                        && all(greaterThanEqual(endWorldRelative, relativeOrigin))
                        && all(lessThan(endWorldRelative, relativeEnd));
            }

            uint metallumVoxelOccupancyWordV1(uint level, uint index) {
                if (level == 0u) {
                    return metallumVoxelOccupancy0.words[index];
                }
                if (level == 1u) {
                    return metallumVoxelOccupancy1.words[index];
                }
                return metallumVoxelOccupancy2.words[index];
            }

            uint metallumVoxelOpticalWordV1(uint level, uint index) {
                if (level == 0u) {
                    return metallumVoxelOptical0.words[index];
                }
                if (level == 1u) {
                    return metallumVoxelOptical1.words[index];
                }
                return metallumVoxelOptical2.words[index];
            }

            uvec4 metallumVoxelMetadataV1(uint level, uint index) {
                if (level == 0u) {
                    return metallumVoxelMetadata0.tags[index];
                }
                if (level == 1u) {
                    return metallumVoxelMetadata1.tags[index];
                }
                return metallumVoxelMetadata2.tags[index];
            }

            bool metallumSegmentIntersectsProxyV1(
                    vec3 startWorldRelative,
                    vec3 endWorldRelative,
                    vec3 minimum,
                    vec3 maximum) {
                if (all(greaterThanEqual(startWorldRelative, minimum))
                        && all(lessThanEqual(startWorldRelative, maximum))) {
                    return false;
                }
                // An entity-emitted light terminates inside its own proxy. Treat either
                // endpoint as ownership rather than letting the proxy extinguish that light.
                if (all(greaterThanEqual(endWorldRelative, minimum))
                        && all(lessThanEqual(endWorldRelative, maximum))) {
                    return false;
                }
                vec3 delta = endWorldRelative - startWorldRelative;
                float entry = 0.0;
                float exit = 1.0;
                for (int axis = 0; axis < 3; ++axis) {
                    if (abs(delta[axis]) <= 0.000001) {
                        if (startWorldRelative[axis] < minimum[axis]
                                || startWorldRelative[axis] > maximum[axis]) {
                            return false;
                        }
                        continue;
                    }
                    float inverse = 1.0 / delta[axis];
                    float first = (minimum[axis] - startWorldRelative[axis]) * inverse;
                    float second = (maximum[axis] - startWorldRelative[axis]) * inverse;
                    if (first > second) {
                        float swap = first;
                        first = second;
                        second = swap;
                    }
                    entry = max(entry, first);
                    exit = min(exit, second);
                    if (entry > exit) {
                        return false;
                    }
                }
                return exit >= 0.0 && entry < 1.0;
            }

            // Returns false only for a valid, bounded proxy test with no hit. Invalid proxy
            // data sets failOpen so the caller can discard the entire shadow result.
            bool metallumProxyVisibilityV1(
                    vec3 startWorldRelative,
                    vec3 endWorldRelative,
                    out bool failOpen) {
                failOpen = false;
                uint proxyCount = metallumVoxelShadow.proxyAndFrame.x;
                uint proxyCapacity = metallumVoxelShadow.proxyAndFrame.y;
                if (proxyCapacity > 32u || proxyCount > proxyCapacity) {
                    failOpen = true;
                    return true;
                }
                for (uint proxyIndex = 0u; proxyIndex < 32u; ++proxyIndex) {
                    if (proxyIndex >= proxyCount) {
                        break;
                    }
                    MetallumVoxelProxyV1 proxy =
                            metallumVoxelProxyBuffer.proxies[proxyIndex];
                    vec3 minimum = proxy.minWorldRelative.xyz;
                    vec3 maximum = proxy.maxWorldRelative.xyz;
                    if (!metallumFiniteVec3V1(minimum)
                            || !metallumFiniteVec3V1(maximum)
                            || any(greaterThan(minimum, maximum))) {
                        failOpen = true;
                        return true;
                    }
                    if (metallumSegmentIntersectsProxyV1(
                            startWorldRelative, endWorldRelative, minimum, maximum)) {
                        return false;
                    }
                }
                return true;
            }

            bool metallumVoxelStepBudgetFitsV1(
                    MetallumVoxelLevelV1 level,
                    vec3 startWorldRelative,
                    vec3 endWorldRelative,
                    uint maxSteps) {
                float subdivision = float(level.levelLayout.x);
                vec3 startCellFloat = floor(startWorldRelative * subdivision);
                vec3 endCellFloat = floor(endWorldRelative * subdivision);
                if (any(lessThan(startCellFloat, vec3(-1000000.0)))
                        || any(greaterThan(startCellFloat, vec3(1000000.0)))
                        || any(lessThan(endCellFloat, vec3(-1000000.0)))
                        || any(greaterThan(endCellFloat, vec3(1000000.0)))) {
                    return false;
                }
                uvec3 cellDelta = uvec3(abs(
                        ivec3(endCellFloat) - ivec3(startCellFloat)));
                // Manhattan crossings are conservative when a corner advances multiple axes.
                // Selecting by this bound guarantees that the hard shader loop can finish.
                uint requiredSteps = cellDelta.x + cellDelta.y + cellDelta.z;
                return requiredSteps <= maxSteps;
            }

            // Retained as the exact fail-open traversal contract for source validation. The
            // production light loop uses the update-driven cache below and never calls this.
            float metallumVoxelDdaVisibilityV1(
                    vec3 receiverViewPosition,
                    vec3 lightViewPosition,
                    vec3 receiverViewNormal) {
                if (metallumVoxelShadow.caps.x != 1u
                        || metallumVoxelShadow.worldAndFlags.z != 1u
                        || metallumVoxelShadow.caps.y == 0u
                        || metallumVoxelShadow.caps.y > 3u
                        || metallumVoxelShadow.caps.z == 0u
                        || metallumVoxelShadow.caps.z > 96u
                        || metallumVoxelShadow.caps.w > 2u
                        || any(notEqual(
                        metallumVoxelShadow.contract.xy,
                        metallumLighting.frameIdAndGeneration.zw))
                        || any(notEqual(
                        metallumVoxelShadow.proxyAndFrame.zw,
                        metallumLighting.frameIdAndGeneration.xy))) {
                    return 1.0;
                }
                ivec3 cameraBlock = metallumVoxelShadow.cameraBlockAndFlags.xyz;
                if (any(lessThan(cameraBlock, ivec3(-500000000)))
                        || any(greaterThan(cameraBlock, ivec3(500000000)))) {
                    return 1.0;
                }
                if (!metallumFiniteVec3V1(receiverViewPosition)
                        || !metallumFiniteVec3V1(lightViewPosition)
                        || !metallumFiniteVec3V1(receiverViewNormal)
                        || !metallumFiniteVec3V1(
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz)
                        || any(lessThan(
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz, vec3(0.0)))
                        || any(greaterThanEqual(
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz, vec3(1.0)))
                        || isnan(metallumVoxelShadow.cameraFractionAndMinTrans.w)
                        || isinf(metallumVoxelShadow.cameraFractionAndMinTrans.w)
                        || metallumVoxelShadow.cameraFractionAndMinTrans.w < 0.0
                        || metallumVoxelShadow.cameraFractionAndMinTrans.w > 1.0) {
                    return 1.0;
                }

                vec3 receiverCameraRelative =
                        mat3(metallumVoxelShadow.worldFromView) * receiverViewPosition;
                vec3 lightCameraRelative =
                        mat3(metallumVoxelShadow.worldFromView) * lightViewPosition;
                vec3 receiverWorldRelative =
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz
                        + receiverCameraRelative;
                vec3 lightWorldRelative =
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz
                        + lightCameraRelative;
                vec3 worldNormal = mat3(metallumVoxelShadow.worldFromView)
                        * receiverViewNormal;
                if (!metallumFiniteVec3V1(receiverWorldRelative)
                        || !metallumFiniteVec3V1(lightWorldRelative)
                        || !metallumFiniteVec3V1(worldNormal)) {
                    return 1.0;
                }

                vec3 unshortenedDelta = lightWorldRelative - receiverWorldRelative;
                float segmentLength = length(unshortenedDelta);
                if (!(segmentLength > 0.0001) || isnan(segmentLength) || isinf(segmentLength)) {
                    return 1.0;
                }
                vec3 rayDirection = unshortenedDelta / segmentLength;

                uint selectedLevel = 3u;
                uint previousSubdivision = 5u;
                MetallumVoxelLevelV1 level;
                vec3 selectedStartWorldRelative = vec3(0.0);
                vec3 selectedEndWorldRelative = vec3(0.0);
                for (uint levelIndex = 0u; levelIndex < 3u; ++levelIndex) {
                    if (levelIndex >= metallumVoxelShadow.caps.y) {
                        break;
                    }
                    MetallumVoxelLevelV1 candidate = metallumVoxelLevelV1(levelIndex);
                    if (!metallumVoxelLevelValidV1(candidate)
                            || candidate.levelLayout.x >= previousSubdivision) {
                        return 1.0;
                    }
                    previousSubdivision = candidate.levelLayout.x;
                    if (selectedLevel == 3u && metallumVoxelLevelContainsV1(
                            candidate, receiverWorldRelative, lightWorldRelative)) {
                        float candidateVoxelSize = 1.0 / float(candidate.levelLayout.x);
                        vec3 candidateStartWorldRelative = receiverWorldRelative
                                + worldNormal * (candidateVoxelSize * 0.06)
                                + rayDirection * (candidateVoxelSize * 0.02);
                        vec3 candidateEndWorldRelative = lightWorldRelative
                                - rayDirection * min(
                                candidateVoxelSize * 0.25, segmentLength * 0.25);
                        if (metallumFiniteVec3V1(candidateStartWorldRelative)
                                && metallumFiniteVec3V1(candidateEndWorldRelative)
                                && dot(candidateEndWorldRelative
                                - candidateStartWorldRelative, rayDirection) > 0.0
                                && metallumVoxelLevelContainsV1(
                                candidate,
                                candidateStartWorldRelative,
                                candidateEndWorldRelative)
                                && metallumVoxelStepBudgetFitsV1(
                                candidate,
                                candidateStartWorldRelative,
                                candidateEndWorldRelative,
                                metallumVoxelShadow.caps.z)) {
                            selectedLevel = levelIndex;
                            level = candidate;
                            selectedStartWorldRelative = candidateStartWorldRelative;
                            selectedEndWorldRelative = candidateEndWorldRelative;
                        }
                    }
                }
                if (selectedLevel == 3u) {
                    return 1.0;
                }

                float subdivision = float(level.levelLayout.x);
                vec3 startWorldRelative = selectedStartWorldRelative;
                vec3 endWorldRelative = selectedEndWorldRelative;
                vec3 shortenedDelta = endWorldRelative - startWorldRelative;
                if (!metallumFiniteVec3V1(startWorldRelative)
                        || !metallumFiniteVec3V1(endWorldRelative)
                        || dot(shortenedDelta, rayDirection) <= 0.0
                        || !metallumVoxelLevelContainsV1(
                        level, startWorldRelative, endWorldRelative)) {
                    return 1.0;
                }

                bool proxyFailOpen = false;
                if (!metallumProxyVisibilityV1(
                        receiverCameraRelative,
                        endWorldRelative
                                - metallumVoxelShadow.cameraFractionAndMinTrans.xyz,
                        proxyFailOpen)) {
                    return 0.0;
                }
                if (proxyFailOpen) {
                    return 1.0;
                }

                vec3 startBlockOffsetFloat = floor(startWorldRelative);
                vec3 endBlockOffsetFloat = floor(endWorldRelative);
                if (any(lessThan(startBlockOffsetFloat, vec3(-1000000.0)))
                        || any(greaterThan(startBlockOffsetFloat, vec3(1000000.0)))
                        || any(lessThan(endBlockOffsetFloat, vec3(-1000000.0)))
                        || any(greaterThan(endBlockOffsetFloat, vec3(1000000.0)))) {
                    return 1.0;
                }

                int subdivisionInt = int(level.levelLayout.x);
                ivec3 startBlockOffset = ivec3(startBlockOffsetFloat);
                ivec3 endBlockOffset = ivec3(endBlockOffsetFloat);
                vec3 startWithinBlock = startWorldRelative - startBlockOffsetFloat;
                vec3 endWithinBlock = endWorldRelative - endBlockOffsetFloat;
                ivec3 cell = (cameraBlock + startBlockOffset) * subdivisionInt
                        + ivec3(floor(startWithinBlock * subdivision));
                ivec3 endCell = (cameraBlock + endBlockOffset) * subdivisionInt
                        + ivec3(floor(endWithinBlock * subdivision));
                vec3 cellFraction = fract(startWithinBlock * subdivision);
                vec3 gridDelta = shortenedDelta * subdivision;
                ivec3 stepDirection = ivec3(sign(gridDelta));
                vec3 absoluteDelta = abs(gridDelta);
                vec3 tDelta = vec3(
                        absoluteDelta.x > 0.000001 ? 1.0 / absoluteDelta.x : 1.0e30,
                        absoluteDelta.y > 0.000001 ? 1.0 / absoluteDelta.y : 1.0e30,
                        absoluteDelta.z > 0.000001 ? 1.0 / absoluteDelta.z : 1.0e30);
                vec3 tMax = vec3(
                        stepDirection.x > 0 ? (1.0 - cellFraction.x) * tDelta.x
                                : stepDirection.x < 0 ? cellFraction.x * tDelta.x : 1.0e30,
                        stepDirection.y > 0 ? (1.0 - cellFraction.y) * tDelta.y
                                : stepDirection.y < 0 ? cellFraction.y * tDelta.y : 1.0e30,
                        stepDirection.z > 0 ? (1.0 - cellFraction.z) * tDelta.z
                                : stepDirection.z < 0 ? cellFraction.z * tDelta.z : 1.0e30);

                float visibility = 1.0;
                ivec3 lastOpticalBlock = ivec3(0);
                bool hasLastOpticalBlock = false;
                ivec3 lastMetadataBrick = ivec3(0);
                bool hasLastMetadataBrick = false;
                uint maxSteps = metallumVoxelShadow.caps.z;
                int brickBlockEdge = int(level.levelLayout.w);
                int brickDimension = int(level.levelLayout.z);
                int logicalEdge = int(level.levelLayout.y);
                int spanBlocks = level.originAndSpan.w;
                bool powerOfTwoAddressing = metallumPowerOfTwoV1(subdivisionInt)
                        && metallumPowerOfTwoV1(brickBlockEdge)
                        && metallumPowerOfTwoV1(brickDimension)
                        && metallumPowerOfTwoV1(logicalEdge)
                        && metallumPowerOfTwoV1(spanBlocks);
                int subdivisionShift = subdivisionInt == 4
                        ? 2 : subdivisionInt == 2 ? 1 : 0;
                int brickShift = brickBlockEdge == 32
                        ? 5 : brickBlockEdge == 16 ? 4 : 3;
                for (uint hardStep = 0u; hardStep < maxSteps; ++hardStep) {
                    // The light endpoint cell belongs to the emitter and must not
                    // self-occlude. It is intentionally never sampled.
                    if (all(equal(cell, endCell))) {
                        return visibility;
                    }

                    ivec3 worldBlock;
                    ivec3 logicalBrick;
                    if (powerOfTwoAddressing) {
                        worldBlock = metallumShiftRightV1(cell, subdivisionShift);
                        logicalBrick = metallumShiftRightV1(worldBlock, brickShift);
                    } else {
                        worldBlock = ivec3(
                                metallumFloorDivV1(cell.x, subdivisionInt),
                                metallumFloorDivV1(cell.y, subdivisionInt),
                                metallumFloorDivV1(cell.z, subdivisionInt));
                        logicalBrick = ivec3(
                                metallumFloorDivV1(worldBlock.x, brickBlockEdge),
                                metallumFloorDivV1(worldBlock.y, brickBlockEdge),
                                metallumFloorDivV1(worldBlock.z, brickBlockEdge));
                    }
                    if (!hasLastMetadataBrick
                            || any(notEqual(logicalBrick, lastMetadataBrick))) {
                        ivec3 physicalBrick = powerOfTwoAddressing
                                ? metallumPowerOfTwoModV1(
                                logicalBrick, brickDimension - 1)
                                : ivec3(
                                metallumPositiveModV1(logicalBrick.x, brickDimension),
                                metallumPositiveModV1(logicalBrick.y, brickDimension),
                                metallumPositiveModV1(logicalBrick.z, brickDimension));
                        uint metadataIndex = uint(physicalBrick.x
                                + brickDimension * (physicalBrick.y
                                + brickDimension * physicalBrick.z));
                        uvec4 metadata = metallumVoxelMetadataV1(
                                selectedLevel, metadataIndex);
                        if (metadata.w == 0u
                                || any(notEqual(ivec3(metadata.xyz), logicalBrick))) {
                            return 1.0;
                        }
                        lastMetadataBrick = logicalBrick;
                        hasLastMetadataBrick = true;
                    }

                    ivec3 physicalCell = powerOfTwoAddressing
                            ? metallumPowerOfTwoModV1(cell, logicalEdge - 1)
                            : ivec3(
                            metallumPositiveModV1(cell.x, logicalEdge),
                            metallumPositiveModV1(cell.y, logicalEdge),
                            metallumPositiveModV1(cell.z, logicalEdge));
                    uint occupancyIndex = uint((physicalCell.z * logicalEdge
                            + physicalCell.y) * (logicalEdge >> 5)
                            + (physicalCell.x >> 5));
                    uint occupancyWord = metallumVoxelOccupancyWordV1(
                            selectedLevel, occupancyIndex);
                    bool occupied = (occupancyWord
                            & (1u << uint(physicalCell.x & 31))) != 0u;
                    if (occupied && (!hasLastOpticalBlock
                            || any(notEqual(worldBlock, lastOpticalBlock)))) {
                        ivec3 physicalBlock = powerOfTwoAddressing
                                ? metallumPowerOfTwoModV1(worldBlock, spanBlocks - 1)
                                : ivec3(
                                metallumPositiveModV1(worldBlock.x, spanBlocks),
                                metallumPositiveModV1(worldBlock.y, spanBlocks),
                                metallumPositiveModV1(worldBlock.z, spanBlocks));
                        uint opticalByteIndex = uint(physicalBlock.x
                                + spanBlocks * (physicalBlock.y
                                + spanBlocks * physicalBlock.z));
                        uint packedWord = metallumVoxelOpticalWordV1(
                                selectedLevel, opticalByteIndex >> 2u);
                        uint packedOptical = (packedWord
                                >> ((opticalByteIndex & 3u) * 8u)) & 255u;
                        uint materialClass = packedOptical >> 5u;
                        uint quantizedTransmittance = packedOptical & 31u;
                        if (materialClass == 0u || materialClass > 7u) {
                            return 1.0;
                        }
                        if (materialClass == 1u || materialClass == 7u
                                || quantizedTransmittance == 0u) {
                            return 0.0;
                        }
                        visibility *= float(quantizedTransmittance) * (1.0 / 31.0);
                        if (isnan(visibility) || isinf(visibility)) {
                            return 1.0;
                        }
                        float minimumTransmittance =
                                metallumVoxelShadow.cameraFractionAndMinTrans.w;
                        if (visibility <= minimumTransmittance) {
                            return 0.0;
                        }
                        lastOpticalBlock = worldBlock;
                        hasLastOpticalBlock = true;
                    }

                    float nextBoundary = min(tMax.x, min(tMax.y, tMax.z));
                    if (nextBoundary > 1.0) {
                        return 1.0;
                    }
                    float tieLimit = nextBoundary + 0.0000001;
                    if (tMax.x <= tieLimit) {
                        cell.x += stepDirection.x;
                        tMax.x += tDelta.x;
                    }
                    if (tMax.y <= tieLimit) {
                        cell.y += stepDirection.y;
                        tMax.y += tDelta.y;
                    }
                    if (tMax.z <= tieLimit) {
                        cell.z += stepDirection.z;
                        tMax.z += tDelta.z;
                    }
                    if (all(equal(cell, endCell))) {
                        return visibility;
                    }
                }
                return 1.0;
            }

            float metallumVoxelCachedVisibilityV1(
                    uint cacheSlot,
                    vec3 lightToReceiver,
                    float receiverDistance) {
                if (cacheSlot >= metallumVoxelShadow.caps.w
                        || !metallumFiniteVec3V1(lightToReceiver)
                        || !(receiverDistance > 0.0001)
                        || isnan(receiverDistance) || isinf(receiverDistance)) {
                    return 1.0;
                }
                vec3 magnitude = abs(lightToReceiver);
                float major = max(magnitude.x, max(magnitude.y, magnitude.z));
                if (!(major > 0.000001) || isnan(major) || isinf(major)) {
                    return 1.0;
                }

                uint face;
                vec2 uv;
                if (magnitude.x >= magnitude.y && magnitude.x >= magnitude.z) {
                    if (lightToReceiver.x >= 0.0) {
                        face = 0u;
                        uv = vec2(-lightToReceiver.z, -lightToReceiver.y) / major;
                    } else {
                        face = 1u;
                        uv = vec2(lightToReceiver.z, -lightToReceiver.y) / major;
                    }
                } else if (magnitude.y >= magnitude.z) {
                    if (lightToReceiver.y >= 0.0) {
                        face = 2u;
                        uv = vec2(lightToReceiver.x, lightToReceiver.z) / major;
                    } else {
                        face = 3u;
                        uv = vec2(lightToReceiver.x, -lightToReceiver.z) / major;
                    }
                } else if (lightToReceiver.z >= 0.0) {
                    face = 4u;
                    uv = vec2(lightToReceiver.x, -lightToReceiver.y) / major;
                } else {
                    face = 5u;
                    uv = vec2(-lightToReceiver.x, -lightToReceiver.y) / major;
                }
                ivec2 texel = clamp(
                        ivec2(floor((uv * 0.5 + 0.5) * 64.0)),
                        ivec2(0),
                        ivec2(63));
                uint texelIndex = ((cacheSlot * 6u + face) * 64u
                        + uint(texel.y)) * 64u + uint(texel.x);
                uint firstHit = texelIndex * 4u;
                float visibility = 1.0;
                for (uint layer = 0u; layer < 4u; ++layer) {
                    uvec2 packed = metallumVoxelVisibilityCache.hits[firstHit + layer];
                    float hitDistance = uintBitsToFloat(packed.x);
                    float hitVisibility = uintBitsToFloat(packed.y);
                    if (isnan(hitDistance) || hitDistance < 0.0
                            || isnan(hitVisibility) || isinf(hitVisibility)
                            || hitVisibility < 0.0 || hitVisibility > 1.0) {
                        return 1.0;
                    }
                    if (isinf(hitDistance)
                            || receiverDistance <= hitDistance + 0.08) {
                        return visibility;
                    }
                    visibility = hitVisibility;
                    if (visibility <= 0.0) {
                        return 0.0;
                    }
                }
                return visibility;
            }

            float metallumVoxelVisibilityV1(
                    vec3 receiverViewPosition,
                    vec3 lightViewPosition,
                    vec3 receiverViewNormal,
                    uint cacheSlot) {
                if (metallumVoxelShadow.caps.x != 1u
                        || metallumVoxelShadow.worldAndFlags.z != 1u
                        || metallumVoxelShadow.caps.y == 0u
                        || metallumVoxelShadow.caps.y > 3u
                        || metallumVoxelShadow.caps.z == 0u
                        || metallumVoxelShadow.caps.z > 96u
                        || metallumVoxelShadow.caps.w == 0u
                        || metallumVoxelShadow.caps.w > 2u
                        || any(notEqual(
                        metallumVoxelShadow.contract.xy,
                        metallumLighting.frameIdAndGeneration.zw))
                        || any(notEqual(
                        metallumVoxelShadow.proxyAndFrame.zw,
                        metallumLighting.frameIdAndGeneration.xy))) {
                    return 1.0;
                }
                ivec3 cameraBlock = metallumVoxelShadow.cameraBlockAndFlags.xyz;
                if (any(lessThan(cameraBlock, ivec3(-500000000)))
                        || any(greaterThan(cameraBlock, ivec3(500000000)))
                        || !metallumFiniteVec3V1(receiverViewPosition)
                        || !metallumFiniteVec3V1(lightViewPosition)
                        || !metallumFiniteVec3V1(receiverViewNormal)
                        || !metallumFiniteVec3V1(
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz)
                        || any(lessThan(
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz, vec3(0.0)))
                        || any(greaterThanEqual(
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz, vec3(1.0)))) {
                    return 1.0;
                }
                vec3 receiverCameraRelative =
                        mat3(metallumVoxelShadow.worldFromView) * receiverViewPosition;
                vec3 lightCameraRelative =
                        mat3(metallumVoxelShadow.worldFromView) * lightViewPosition;
                vec3 receiverWorldRelative =
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz
                        + receiverCameraRelative;
                vec3 lightWorldRelative =
                        metallumVoxelShadow.cameraFractionAndMinTrans.xyz
                        + lightCameraRelative;
                if (!metallumFiniteVec3V1(receiverCameraRelative)
                        || !metallumFiniteVec3V1(lightCameraRelative)
                        || !metallumFiniteVec3V1(receiverWorldRelative)
                        || !metallumFiniteVec3V1(lightWorldRelative)) {
                    return 1.0;
                }
                bool proxyFailOpen = false;
                if (!metallumProxyVisibilityV1(
                        receiverCameraRelative,
                        lightCameraRelative,
                        proxyFailOpen)) {
                    return 0.0;
                }
                if (proxyFailOpen) {
                    return 1.0;
                }
                vec3 lightToReceiver = receiverWorldRelative - lightWorldRelative;
                float receiverDistance = length(lightToReceiver);
                return metallumVoxelCachedVisibilityV1(
                        cacheSlot, lightToReceiver, receiverDistance);
            }

            int metallumVoxelShadowLightSlotV1(uint lightIndex) {
                uint shadowedCap = metallumVoxelShadow.caps.w;
                if (shadowedCap == 0u || shadowedCap > 2u) {
                    return -1;
                }
                uint selected0 = uint(metallumVoxelShadow.cameraBlockAndFlags.w);
                uint selected1 = metallumVoxelShadow.worldAndFlags.w;
                if (selected0 != uint(-1) && lightIndex == selected0) {
                    return 0;
                }
                return shadowedCap > 1u
                        && selected1 != uint(-1) && lightIndex == selected1 ? 1 : -1;
            }

            uint metallumClusterIndexV1(vec3 viewPosition) {
                uvec3 grid = max(metallumLighting.gridAndLightCount.xyz, uvec3(1u));
                uint tileSize = max(metallumLighting.capacitiesAndFlags.w, 1u);
                uvec2 tile = min(
                        uvec2(max(gl_FragCoord.xy, vec2(0.0))) / tileSize,
                        grid.xy - uvec2(1u));
                float nearDepth = max(metallumLighting.depth.x, 0.0001);
                float viewDepth = max(-viewPosition.z, nearDepth);
                float rawSlice = floor(
                        log2(viewDepth) * metallumLighting.depth.z
                                + metallumLighting.depth.w);
                uint slice = uint(clamp(rawSlice, 0.0, float(grid.z - 1u)));
                return tile.x + grid.x * (tile.y + grid.y * slice);
            }

            vec3 metallumEvaluateClusteredDirectV1(
                    vec3 viewPosition,
                    vec3 normal,
                    vec3 albedo) {
                if (metallumLighting.reserved0.w != 1u
                        || metallumLighting.capacitiesAndFlags.w != 64u
                        || metallumLighting.gridAndLightCount.z != 6u) {
                    return vec3(0.0);
                }
                if (dot(normal, normal) == 0.0) {
                    return vec3(0.0);
                }

                uint activeLightCount = min(
                        metallumLighting.gridAndLightCount.w,
                        metallumLighting.capacitiesAndFlags.y);
                if (activeLightCount == 0u) {
                    return vec3(0.0);
                }

                uint cluster = metallumClusterIndexV1(viewPosition);
                uint clusterCapacity = metallumLighting.capacitiesAndFlags.x;
                if (cluster >= clusterCapacity) {
                    return vec3(0.0);
                }

                MetallumClusterHeaderV1 header =
                        metallumClusterHeaderBuffer.headers[cluster];
                uint indexCapacity = metallumLighting.capacitiesAndFlags.z;
                if (header.offset > indexCapacity
                        || header.count > indexCapacity - header.offset) {
                    return vec3(0.0);
                }

                uint countLimit = min(
                        min(header.count, metallumLighting.extentAndClusterCap.z),
                        256u);

                vec3 direct = vec3(0.0);
                uint evaluated = 0u;
                for (uint candidate = 0u; candidate < countLimit; ++candidate) {
                    uint lightIndex = metallumClusterIndexBuffer.indices[
                            header.offset + candidate];
                    if (lightIndex >= activeLightCount) {
                        continue;
                    }

                    MetallumGpuLightV1 light = metallumLightBuffer.lights[lightIndex];
                    float radius = max(light.positionRadius.w, 0.0);
                    vec3 toLight = light.positionRadius.xyz - viewPosition;
                    float distanceSquared = dot(toLight, toLight);
                    if (radius <= 0.0 || distanceSquared >= radius * radius) {
                        continue;
                    }

                    float inverseDistance = inversesqrt(max(distanceSquared, 0.000001));
                    float range = max(1.0 - sqrt(max(distanceSquared, 0.0)) / radius, 0.0);
                    float attenuation = range * range;
                    float nDotL = max(dot(normal, toLight * inverseDistance), 0.0);
                    vec3 radiance = max(light.linearColorIntensity.rgb, vec3(0.0))
                            * max(light.linearColorIntensity.a, 0.0);
                    float visibility = 1.0;
                    int shadowSlot = metallumVoxelShadowLightSlotV1(lightIndex);
                    if (nDotL > 0.0 && any(greaterThan(radiance, vec3(0.0)))
                            && shadowSlot >= 0) {
                        visibility = metallumVoxelVisibilityV1(
                                viewPosition, light.positionRadius.xyz, normal,
                                uint(shadowSlot));
                    }
                    direct += albedo
                            * radiance
                            * (attenuation * nDotL * visibility * 0.31830988618);
                    evaluated += 1u;
                }
                return direct;
            }
            """;

    private static final String SODIUM_VERTEX_DECLARATION =
            "out vec2 v_TexCoord;\nout vec3 metallumLightingPosition;\n"
                    + "out float metallumSkyVisibility;\n// " + MARKER;
    private static final String SODIUM_VERTEX_ASSIGNMENT =
            "    vec3 position = _vert_position + translation;\n"
                    + "    metallumLightingPosition = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;";
    private static final String SODIUM_MATERIAL_LIGHTMAP =
            "    vec4 metallumLightmap = metallumMaterialDecodeLegacyLightmap("
                    + "texture(u_LightTex, _vert_tex_light_coord));";
    private static final String SODIUM_ADVANCED_SKY_LIGHTMAP =
            "    metallumSkyVisibility = clamp("
                    + "(_vert_tex_light_coord.y * 256.0 - 8.0) / 240.0, 0.0, 1.0);\n"
                    + "    vec4 metallumLightmap = metallumMaterialDecodeLegacyLightmap("
                    + "texture(u_LightTex, vec2(8.0 / 256.0)));";
    private static final String SODIUM_FRAGMENT_INPUT =
            "in vec2 v_TexCoord;\nin vec3 metallumLightingPosition;\n"
                    + "in float metallumSkyVisibility;";
    private static final String SODIUM_FOG_ANCHOR =
            "    fragColor = _linearFog(color, v_FragDistance, "
                    + "metallumMaterialDecodeColor(u_FogColor), u_EnvironmentFog, "
                    + "u_RenderFog, fadeFactor);";
    private static final String SODIUM_DIRECT_BLOCK =
            "    vec3 metallumDerivativeNormal = cross(\n"
                    + "            dFdx(metallumLightingPosition),\n"
                    + "            dFdy(metallumLightingPosition));\n"
                    + "    if (!gl_FrontFacing) {\n"
                    + "        metallumDerivativeNormal = -metallumDerivativeNormal;\n"
                    + "    }\n"
                    + "    vec3 metallumDirectNormal = metallumSafeNormalV1(\n"
                    + "            metallumDerivativeNormal);\n"
                    + "    vec3 metallumPreparedAlbedo = max(metallumUnlitBase, vec3(0.0));\n"
                    + "    color.rgb += metallumEvaluateEnvironmentV1(\n"
                    + "            metallumLightingPosition, metallumDirectNormal,\n"
                    + "            metallumPreparedAlbedo, metallumSkyVisibility);\n"
                    + "    color.rgb += metallumEvaluateClusteredDirectV1(\n"
                    + "            metallumLightingPosition, metallumDirectNormal,\n"
                    + "            metallumPreparedAlbedo);\n"
                    + SODIUM_FOG_ANCHOR;

    private static final String ENTITY_VERTEX_DECLARATION =
            "out vec2 texCoord0;\nout vec3 metallumLightingPosition;\n"
                    + "out vec3 metallumLightingNormal;\n"
                    + "out vec4 metallumLightingTint;\n"
                    + "out float metallumSkyVisibility;\n// " + MARKER;
    private static final String ENTITY_VERTEX_ASSIGNMENT =
            "    vec4 metallumViewPosition = ModelViewMat * vec4(Position, 1.0);\n"
                    + "    gl_Position = ProjMat * metallumViewPosition;\n"
                    + "    metallumLightingPosition = metallumViewPosition.xyz;\n"
                    + "    metallumLightingNormal = mat3(ModelViewMat) * Normal;\n"
                    + "    metallumLightingTint = metallumMaterialDecodeColor(Color);";
    private static final String ENTITY_MATERIAL_LIGHTMAP =
            "    lightMapColor = metallumMaterialDecodeLegacyLightmap("
                    + "sample_lightmap(Sampler2, UV2));";
    private static final String ENTITY_ADVANCED_SKY_LIGHTMAP =
            "    metallumSkyVisibility = clamp((float(UV2.y) - 8.0) / 240.0, 0.0, 1.0);\n"
                    + "    lightMapColor = metallumMaterialDecodeLegacyLightmap("
                    + "sample_lightmap(Sampler2, ivec2(0, 0)));";
    private static final String ENTITY_FRAGMENT_INPUT =
            "in vec2 texCoord0;\nin vec3 metallumLightingPosition;\n"
                    + "in vec3 metallumLightingNormal;\nin vec4 metallumLightingTint;\n"
                    + "in float metallumSkyVisibility;";
    private static final String ENTITY_COLOR_ANCHOR =
            "    color *= faceVertexColor * metallumMaterialDecodeColor(ColorModulator);";
    private static final String ENTITY_DIRECT_ALBEDO =
            "    vec3 metallumDirectAlbedo = max(\n"
                    + "            color.rgb * metallumLightingTint.rgb\n"
                    + "                    * metallumMaterialDecodeColor(ColorModulator).rgb,\n"
                    + "            vec3(0.0));\n"
                    + ENTITY_COLOR_ANCHOR;
    private static final String ENTITY_OVERLAY_ANCHOR =
            "#ifndef NO_OVERLAY\n"
                    + "    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);\n"
                    + "#endif";
    private static final String ENTITY_OVERLAY_WITH_DIRECT_ALBEDO =
            "#ifndef NO_OVERLAY\n"
                    + "    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);\n"
                    + "    metallumDirectAlbedo = mix(\n"
                    + "            overlayColor.rgb, metallumDirectAlbedo, overlayColor.a);\n"
                    + "#endif";
    private static final String ENTITY_FOG_ANCHOR =
            "    fragColor = apply_fog(color, sphericalVertexDistance, "
                    + "cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, "
                    + "FogRenderDistanceStart, FogRenderDistanceEnd, "
                    + "metallumMaterialDecodeColor(FogColor));";
    private static final String ENTITY_DIRECT_BLOCK =
            "    vec3 metallumEntityNormal = gl_FrontFacing\n"
                    + "            ? metallumLightingNormal\n"
                    + "            : -metallumLightingNormal;\n"
                    + "    vec3 metallumDirectNormal = metallumSafeNormalV1(\n"
                    + "            metallumEntityNormal);\n"
                    + "    vec3 metallumPreparedAlbedo = max(metallumDirectAlbedo, vec3(0.0));\n"
                    + "    color.rgb += metallumEvaluateEnvironmentV1(\n"
                    + "            metallumLightingPosition, metallumDirectNormal,\n"
                    + "            metallumPreparedAlbedo, metallumSkyVisibility);\n"
                    + "    color.rgb += metallumEvaluateClusteredDirectV1(\n"
                    + "            metallumLightingPosition, metallumDirectNormal,\n"
                    + "            metallumPreparedAlbedo);\n"
                    + ENTITY_FOG_ANCHOR;

    private AdvancedDirectLightingShaderPatcher() {
    }

    public static Result patch(
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage,
            final LightingModel lightingModel,
            final String materialSource
    ) {
        if (materialSource == null) {
            return Result.failure(null, "shader source is missing");
        }
        if (lightingModel == null) {
            return Result.failure(materialSource, "lighting model is missing");
        }
        if (lightingModel == LightingModel.VANILLA) {
            if (materialSource.contains(MARKER)) {
                return Result.failure(
                        materialSource,
                        "Vanilla lighting cannot reuse an Advanced shader source"
                );
            }
            return Result.success(materialSource);
        }
        if (stage == null) {
            return Result.failure(materialSource, "shader stage is missing");
        }
        if (!isSupportedTarget(namespace, path)) {
            return Result.failure(
                    materialSource,
                    "no Advanced direct-light adapter for shader " + namespace + ':' + path + ' ' + stage
            );
        }
        if (!MetallumMaterialShaderPatcher.isPatched(materialSource)) {
            return Result.failure(
                    materialSource,
                    "Advanced lighting requires an already-patched METALLUM material source"
            );
        }
        if (materialSource.contains(MARKER)) {
            return validateAlreadyPatched(namespace, path, stage, materialSource);
        }
        String collision = helperCollision(materialSource);
        if (collision != null) {
            return Result.failure(materialSource, "shader collides with Advanced helper " + collision);
        }
        if (stage == MetallumMaterialShaderPatcher.Stage.FRAGMENT) {
            int occupiedSlot = occupiedAdvancedSlot(materialSource);
            if (occupiedSlot == MALFORMED_SLOT) {
                return Result.failure(materialSource, "fragment has a malformed explicit buffer binding");
            }
            if (occupiedSlot >= 0) {
                return Result.failure(
                        materialSource,
                        "fragment buffer slot " + occupiedSlot + " is already occupied"
                );
            }
        }

        return isSodiumTerrain(namespace, path)
                ? patchSodium(stage, materialSource)
                : patchEntity(stage, materialSource);
    }

    public static boolean isPatched(final String source) {
        return source != null && source.contains(MARKER);
    }

    public static List<ShaderKey> requiredTargets() {
        return REQUIRED_TARGETS;
    }

    public static boolean isExternalShadowSampler(final String name) {
        return SHADOW_SAMPLER_0.equals(name)
                || SHADOW_SAMPLER_1.equals(name)
                || SHADOW_SAMPLER_2.equals(name);
    }

    public static int externalShadowSamplerSlot(final String name) {
        if (SHADOW_SAMPLER_0.equals(name)) {
            return EnvironmentShadowBindingAbi.SHADOW_TEXTURE_0_SLOT;
        }
        if (SHADOW_SAMPLER_1.equals(name)) {
            return EnvironmentShadowBindingAbi.SHADOW_TEXTURE_1_SLOT;
        }
        if (SHADOW_SAMPLER_2.equals(name)) {
            return EnvironmentShadowBindingAbi.SHADOW_TEXTURE_2_SLOT;
        }
        throw new IllegalArgumentException("Not an L4 shadow sampler: " + name);
    }

    /**
     * Source-only coverage gate for resource reload. Successful pipeline/PSO creation remains a
     * separate admission condition because this method deliberately performs no GPU work.
     */
    public static Preflight preflight(final Map<ShaderKey, String> originalSources) {
        if (originalSources == null) {
            return Preflight.rejected("Advanced shader source map is missing");
        }
        for (ShaderKey key : REQUIRED_TARGETS) {
            String original = originalSources.get(key);
            if (original == null) {
                return Preflight.rejected("missing Advanced shader source " + key);
            }
            MetallumMaterialShaderPatcher.Result material = MetallumMaterialShaderPatcher.patch(
                    key.namespace(), key.path(), key.stage(), original
            );
            if (!material.success()) {
                return Preflight.rejected(
                        "METALLUM material adapter rejected " + key + ": "
                                + material.failureReason()
                );
            }
            Result advanced = patch(
                    key.namespace(), key.path(), key.stage(), LightingModel.ADVANCED,
                    material.source()
            );
            if (!advanced.success()) {
                return Preflight.rejected(
                        "Advanced direct-light adapter rejected " + key + ": "
                                + advanced.failureReason()
                );
            }
        }
        return Preflight.admitted();
    }

    private static Result patchSodium(
            final MetallumMaterialShaderPatcher.Stage stage,
            final String source
    ) {
        if (stage == MetallumMaterialShaderPatcher.Stage.VERTEX) {
            String patched = replaceExactlyOnce(
                    source,
                    "out vec2 v_TexCoord;",
                    SODIUM_VERTEX_DECLARATION
            );
            patched = replaceExactlyOnce(
                    patched,
                    "    vec3 position = _vert_position + translation;",
                    SODIUM_VERTEX_ASSIGNMENT
            );
            patched = replaceExactlyOnce(
                    patched,
                    SODIUM_MATERIAL_LIGHTMAP,
                    SODIUM_ADVANCED_SKY_LIGHTMAP
            );
            if (patched == null
                    || !patched.contains("out vec3 metallumLightingPosition;")
                    || !patched.contains("metallumLightingPosition = (u_ModelViewMatrix")
                    || !patched.contains(SODIUM_ADVANCED_SKY_LIGHTMAP)) {
                return Result.failure(source, "Sodium Advanced vertex anchors changed");
            }
            return Result.success(patched);
        }

        String withStorageBuffers = installStorageBufferVersion(source);
        if (withStorageBuffers == null) {
            return Result.failure(source, "Sodium fragment has no unique GLSL version directive");
        }
        String patched = replaceExactlyOnce(
                withStorageBuffers,
                "in vec2 v_TexCoord;",
                SODIUM_FRAGMENT_INPUT
        );
        patched = installFragmentAbi(patched);
        patched = replaceExactlyOnce(patched, SODIUM_FOG_ANCHOR, SODIUM_DIRECT_BLOCK);
        if (patched == null
                || !patched.contains("dFdx(metallumLightingPosition)")
                || !patched.contains("dFdy(metallumLightingPosition)")
                || !patched.contains(SODIUM_DIRECT_BLOCK)) {
            return Result.failure(source, "Sodium Advanced fragment anchors changed");
        }
        return Result.success(patched);
    }

    private static Result patchEntity(
            final MetallumMaterialShaderPatcher.Stage stage,
            final String source
    ) {
        if (stage == MetallumMaterialShaderPatcher.Stage.VERTEX) {
            String patched = replaceExactlyOnce(
                    source,
                    "out vec2 texCoord0;",
                    ENTITY_VERTEX_DECLARATION
            );
            patched = replaceExactlyOnce(
                    patched,
                    "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);",
                    ENTITY_VERTEX_ASSIGNMENT
            );
            patched = replaceExactlyOnce(
                    patched,
                    ENTITY_MATERIAL_LIGHTMAP,
                    ENTITY_ADVANCED_SKY_LIGHTMAP
            );
            if (patched == null
                    || !patched.contains("out vec3 metallumLightingNormal;")
                    || !patched.contains("metallumLightingNormal = mat3(ModelViewMat) * Normal;")
                    || !patched.contains("metallumLightingTint = metallumMaterialDecodeColor(Color);")
                    || !patched.contains(ENTITY_ADVANCED_SKY_LIGHTMAP)) {
                return Result.failure(source, "entity Advanced vertex anchors changed");
            }
            return Result.success(patched);
        }

        String withStorageBuffers = installStorageBufferVersion(source);
        if (withStorageBuffers == null) {
            return Result.failure(source, "entity fragment has no unique GLSL version directive");
        }
        String patched = replaceExactlyOnce(
                withStorageBuffers,
                "in vec2 texCoord0;",
                ENTITY_FRAGMENT_INPUT
        );
        patched = installFragmentAbi(patched);
        patched = replaceExactlyOnce(patched, ENTITY_COLOR_ANCHOR, ENTITY_DIRECT_ALBEDO);
        patched = replaceExactlyOnce(
                patched,
                ENTITY_OVERLAY_ANCHOR,
                ENTITY_OVERLAY_WITH_DIRECT_ALBEDO
        );
        patched = replaceExactlyOnce(patched, ENTITY_FOG_ANCHOR, ENTITY_DIRECT_BLOCK);
        if (patched == null
                || !patched.contains("? metallumLightingNormal")
                || !patched.contains(ENTITY_DIRECT_ALBEDO)
                || !patched.contains(ENTITY_DIRECT_BLOCK)) {
            return Result.failure(source, "entity Advanced fragment anchors changed");
        }
        return Result.success(patched);
    }

    private static String installFragmentAbi(final String source) {
        if (source == null) {
            return null;
        }
        return replaceExactlyOnce(
                source,
                "out vec4 fragColor;",
                "out vec4 fragColor;" + FRAGMENT_ABI_AND_HELPERS
        );
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
        return source.substring(0, start)
                + "#version 430 core"
                + source.substring(end);
    }

    private static Result validateAlreadyPatched(
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage,
            final String source
    ) {
        if (countOccurrences(source, MARKER) != 1) {
            return Result.failure(source, "Advanced source has a partial or duplicate marker ABI");
        }
        boolean fragment = stage == MetallumMaterialShaderPatcher.Stage.FRAGMENT;
        if (fragment) {
            if (!source.contains("#version 430 core")) {
                return Result.failure(source, "Advanced fragment marker retained a non-storage GLSL version");
            }
            if (!source.contains(FRAGMENT_ABI_AND_HELPERS)) {
                return Result.failure(source, "Advanced fragment helper ABI is not canonical");
            }
            for (int slot : AdvancedLightingBindingAbi.fragmentSlots()) {
                if (countOccurrences(source, "layout(std430, binding = " + slot) != 1) {
                    return Result.failure(source, "Advanced fragment marker has an incomplete binding ABI");
                }
            }
            if (countOccurrences(
                    source,
                    "layout(std430, binding = " + EnvironmentShadowBindingAbi.PARAMS_SLOT
            ) != 1) {
                return Result.failure(source, "Advanced fragment marker has no environment ABI");
            }
            if (countOccurrences(
                    source,
                    "layout(std430, binding = "
                            + VoxelShadowBindingAbi.VISIBILITY_CACHE_BUFFER_SLOT
            ) != 1) {
                return Result.failure(
                        source,
                        "Advanced fragment marker has no L6 visibility-cache ABI"
                );
            }
            for (int slot = VoxelShadowBindingAbi.PROXY_BUFFER_SLOT;
                 slot <= VoxelShadowBindingAbi.METADATA_BUFFER_2_SLOT;
                 slot++) {
                if (countOccurrences(source, "layout(std430, binding = " + slot) != 1) {
                    return Result.failure(
                            source,
                            "Advanced fragment marker has an incomplete L6 local-shadow ABI"
                    );
                }
            }
            for (int slot : EnvironmentShadowBindingAbi.shadowTextureSlots()) {
                if (countOccurrences(
                        source,
                        "layout(binding = " + slot + ") uniform sampler2DShadow"
                ) != 1) {
                    return Result.failure(source, "Advanced fragment marker has an incomplete shadow ABI");
                }
            }
            if (countOccurrences(source, "metallumEvaluateClusteredDirectV1(") != 2) {
                return Result.failure(source, "Advanced fragment marker has no direct-light helper");
            }
            if (countOccurrences(source, "metallumEvaluateEnvironmentV1(") != 2) {
                return Result.failure(source, "Advanced fragment marker has no environment helper");
            }
            if (isSodiumTerrain(namespace, path)) {
                if (!source.contains(SODIUM_FRAGMENT_INPUT)
                        || !source.contains(SODIUM_DIRECT_BLOCK)) {
                    return Result.failure(source, "Advanced terrain fragment body is not canonical");
                }
            } else if (!source.contains(ENTITY_FRAGMENT_INPUT)
                    || !source.contains(ENTITY_DIRECT_ALBEDO)
                    || !source.contains(ENTITY_OVERLAY_WITH_DIRECT_ALBEDO)
                    || !source.contains(ENTITY_DIRECT_BLOCK)) {
                return Result.failure(source, "Advanced entity fragment body is not canonical");
            }
        } else if (isSodiumTerrain(namespace, path)) {
            if (!source.contains(SODIUM_VERTEX_DECLARATION)
                    || !source.contains(SODIUM_VERTEX_ASSIGNMENT)
                    || !source.contains(SODIUM_ADVANCED_SKY_LIGHTMAP)) {
                return Result.failure(source, "Advanced terrain vertex body is not canonical");
            }
        } else if (!source.contains(ENTITY_VERTEX_DECLARATION)
                || !source.contains(ENTITY_VERTEX_ASSIGNMENT)
                || !source.contains(ENTITY_ADVANCED_SKY_LIGHTMAP)) {
            return Result.failure(source, "Advanced entity vertex body is not canonical");
        }
        return Result.success(source);
    }

    private static boolean isSupportedTarget(final String namespace, final String path) {
        return isSodiumTerrain(namespace, path) || isEntity(namespace, path);
    }

    private static boolean isSodiumTerrain(final String namespace, final String path) {
        return "sodium".equals(namespace) && SODIUM_TERRAIN_PATH.equals(path);
    }

    private static boolean isEntity(final String namespace, final String path) {
        return "minecraft".equals(namespace) && VANILLA_ENTITY_PATH.equals(path);
    }

    private static int occupiedAdvancedSlot(final String source) {
        Matcher layout = LAYOUT_PATTERN.matcher(source);
        while (layout.find()) {
            String qualifiers = layout.group(1);
            if (countOccurrences(qualifiers, "binding") > 1) {
                return MALFORMED_SLOT;
            }
            Matcher binding = BINDING_PATTERN.matcher(qualifiers);
            if (!binding.find()) {
                if (BINDING_TOKEN_PATTERN.matcher(qualifiers).find()) {
                    return MALFORMED_SLOT;
                }
                continue;
            }
            int slot;
            try {
                slot = Integer.parseInt(binding.group(1));
            } catch (NumberFormatException exception) {
                return MALFORMED_SLOT;
            }
            if (AdvancedLightingBindingAbi.ownsFragmentSlot(slot)
                    || slot == EnvironmentShadowBindingAbi.PARAMS_SLOT
                    || VoxelShadowBindingAbi.ownsFragmentSlot(slot)
                    || EnvironmentShadowBindingAbi.ownsShadowTextureSlot(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private static String helperCollision(final String source) {
        for (String helper : new String[]{
                "MetallumGpuLightV1",
                "MetallumLightingParamsV1",
                "MetallumEnvironmentShadowV1",
                "MetallumVoxelVisibilityCacheV1",
                "MetallumVoxelProxyV1",
                "MetallumVoxelShadowParamsV1",
                "metallumLightingPosition",
                "metallumLightingNormal",
                "metallumLightingTint",
                "metallumDirectAlbedo",
                "metallumDirectNormal",
                "metallumPreparedAlbedo",
                "metallumClusterIndexV1",
                "metallumEvaluateClusteredDirectV1",
                "metallumEvaluateEnvironmentV1",
                "metallumVoxelDdaVisibilityV1",
                "metallumVoxelCachedVisibilityV1",
                "metallumVoxelVisibilityV1",
                "metallumVoxelShadowLightSlotV1",
                "metallumProxyVisibilityV1",
                SHADOW_SAMPLER_0,
                SHADOW_SAMPLER_1,
                SHADOW_SAMPLER_2
        }) {
            if (source.contains(helper)) {
                return helper;
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
        return source.substring(0, first) + replacement + source.substring(first + needle.length());
    }

    private static int countOccurrences(final String source, final String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    static {
        AdvancedLightingBindingAbi.requireCompatibleLayout(
                AdvancedLightingLayout.ABI_VERSION,
                AdvancedLightingLayout.LIGHTING_PARAMS_BYTES,
                AdvancedLightingLayout.GPU_LIGHT_STRIDE,
                AdvancedLightingLayout.CLUSTER_HEADER_STRIDE,
                AdvancedLightingLayout.LIGHT_INDEX_STRIDE
        );
        if (AdvancedLightingLayout.TILE_SIZE != 64
                || AdvancedLightingLayout.DEPTH_SLICES != 6
                || AdvancedLightingLayout.MAX_LIGHTS_PER_CLUSTER != 256) {
            throw new ExceptionInInitializerError("Advanced shader constants do not match the generation layout");
        }
        if (EnvironmentShadowBindingAbi.PARAMS_BYTES != SunShadowLayout.PARAMS_BYTES
                || EnvironmentShadowBindingAbi.VERSION != SunShadowLayout.ABI_VERSION) {
            throw new ExceptionInInitializerError("Environment/shadow shader ABI does not match its layout");
        }
        if (VoxelShadowBindingAbi.VERSION != 1
                || VoxelShadowBindingAbi.PARAMS_BYTES != 256
                || VoxelShadowBindingAbi.PROXY_STRIDE_BYTES != 32
                || VoxelShadowBindingAbi.VISIBILITY_CACHE_BUFFER_SLOT != 14
                || VoxelShadowBindingAbi.PROXY_BUFFER_SLOT != 15
                || VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT != 16
                || VoxelShadowBindingAbi.METADATA_BUFFER_2_SLOT != 25) {
            throw new ExceptionInInitializerError("L6 local-shadow shader ABI does not match its layout");
        }
    }
}
