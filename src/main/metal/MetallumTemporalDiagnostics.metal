#include <metal_stdlib>
using namespace metal;

struct MetallumTemporalVertexOut {
    float4 position [[position]];
};

struct MetallumTemporalUniforms {
    float4x4 currentView;
    float4x4 currentProjection;
    float4x4 inverseCurrentView;
    float4x4 inverseCurrentProjection;
    float4x4 previousView;
    float4x4 previousProjection;
    float4 currentCameraPosition;
    float4 previousCameraPosition;
    float2 renderExtent;
    float2 jitter;
    float2 previousJitter;
    float2 reserved_padding;
    uint resetMask;
    uint previousDepthValid;
    uint reserved0;
    uint reserved1;
};

struct MetallumTemporalOutputs {
    float2 motion [[color(0)]];
    float reactive [[color(1)]];
    float classification [[color(2)]];
};

struct MetallumMotionValidationInput {
    float4 currentClip;
    float4 previousClip;
    uint invalidDepth;
    uint reserved;
};

struct MetallumMotionValidationOutput {
    float2 motion;
    float reactive;
    float reserved;
};

inline float2 metallum_motion_pixels(
    float2 currentNdc,
    float2 previousNdc,
    float2 renderExtent
) {
    float2 ndcDelta = previousNdc - currentNdc;
    return float2(
        ndcDelta.x * (0.5f * renderExtent.x),
        -ndcDelta.y * (0.5f * renderExtent.y)
    );
}

vertex MetallumTemporalVertexOut metallum_temporal_diagnostic_vs(uint vertexId [[vertex_id]]) {
    float2 positions[3] = {
        float2(-1.0f, -1.0f),
        float2(3.0f, -1.0f),
        float2(-1.0f, 3.0f)
    };
    MetallumTemporalVertexOut output;
    output.position = float4(positions[vertexId], 0.0f, 1.0f);
    return output;
}

fragment MetallumTemporalOutputs metallum_temporal_diagnostic_fs(
    MetallumTemporalVertexOut input [[stage_in]],
    texture2d<float, access::read> depthTexture [[texture(0)]],
    texture2d<float, access::read> previousDepthTexture [[texture(1)]],
    constant MetallumTemporalUniforms& uniforms [[buffer(0)]]
) {
    MetallumTemporalOutputs output;
    uint2 pixel = uint2(input.position.xy);
    float depth = depthTexture.read(pixel).x;

    // Global reset check
    if (uniforms.resetMask != 0u) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
        output.classification = 1.0f / 255.0f; // 1: reset
        return output;
    }
    if (!isfinite(depth) || depth <= 0.0f || depth >= 1.0f) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
        output.classification = 2.0f / 255.0f; // 2: sky/invalid depth
        return output;
    }

    // Current NDC coordinate of the pixel
    float2 currentNdc = float2(
        (input.position.x / uniforms.renderExtent.x) * 2.0f - 1.0f,
        1.0f - (input.position.y / uniforms.renderExtent.y) * 2.0f
    );

    // Unjitter the current NDC coordinate
    float2 currentNdcUnjittered = float2(
        currentNdc.x - uniforms.jitter.x * 2.0f / uniforms.renderExtent.x,
        currentNdc.y + uniforms.jitter.y * 2.0f / uniforms.renderExtent.y
    );

    // Reconstruct camera space position from depth
    float4 currentLocalH = uniforms.inverseCurrentProjection * float4(currentNdc, depth, 1.0f);
    if (!isfinite(currentLocalH.w) || abs(currentLocalH.w) < 1.0e-7f) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
        output.classification = 4.0f / 255.0f; // 4: other invalid
        return output;
    }
    float3 currentLocal = currentLocalH.xyz / currentLocalH.w;

    // Static terrain reprojection (camera motion vector generation)
    float3 worldRelativeCurrent = (uniforms.inverseCurrentView * float4(currentLocal, 1.0f)).xyz;
    float3 previousRelative = worldRelativeCurrent
        + uniforms.currentCameraPosition.xyz
        - uniforms.previousCameraPosition.xyz;

    // Project to previous clip space
    float4 previousClip = uniforms.previousProjection * uniforms.previousView * float4(previousRelative, 1.0f);
    if (!all(isfinite(previousClip)) || abs(previousClip.w) < 1.0e-7f) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
        output.classification = 4.0f / 255.0f; // 4: other invalid
        return output;
    }
    float2 previousNdc = previousClip.xy / previousClip.w;
    float previousDepth = previousClip.z / previousClip.w;

    // Compute motion vectors
    output.motion = metallum_motion_pixels(currentNdcUnjittered, previousNdc, uniforms.renderExtent);

    // The stored depth belongs to the previous *jittered* raster.  Motion is
    // deliberately measured between unjittered frames, so shift the previous
    // location back to the raster coordinate only for the depth lookup.
    float2 previousNdcJittered = float2(
        previousNdc.x + uniforms.previousJitter.x * 2.0f / uniforms.renderExtent.x,
        previousNdc.y - uniforms.previousJitter.y * 2.0f / uniforms.renderExtent.y
    );
    bool outOfBounds = previousNdc.x < -1.0f || previousNdc.x > 1.0f
        || previousNdc.y < -1.0f || previousNdc.y > 1.0f
        || previousNdcJittered.x < -1.0f || previousNdcJittered.x > 1.0f
        || previousNdcJittered.y < -1.0f || previousNdcJittered.y > 1.0f;
    if (outOfBounds) {
        output.reactive = 1.0f;
        output.classification = 3.0f / 255.0f; // 3: out-of-frame
        output.motion = float2(0.0f);
    } else {
        bool valid = all(isfinite(output.motion)) && isfinite(previousDepth);
        bool depthDisoccluded = false;
        if (valid && uniforms.previousDepthValid != 0u) {
            float2 previousPixel = float2(
                (previousNdcJittered.x * 0.5f + 0.5f) * uniforms.renderExtent.x,
                (1.0f - previousNdcJittered.y) * 0.5f * uniforms.renderExtent.y
            );
            uint2 previousPixelCoordinate = uint2(previousPixel);
            float recordedPreviousDepth = previousDepthTexture.read(previousPixelCoordinate).x;
            // Reversed-Z depth has much finer resolution near the camera.  A
            // small relative allowance preserves history on continuous slopes,
            // while a different surface at the reprojected location rejects it.
            float depthTolerance = max(1.0e-5f, abs(previousDepth) * 0.015f);
            depthDisoccluded = !isfinite(recordedPreviousDepth)
                || abs(recordedPreviousDepth - previousDepth) > depthTolerance;
        }
        valid = valid && !depthDisoccluded;
        output.reactive = valid ? 0.0f : 1.0f;
        output.classification = valid ? 0.0f : (4.0f / 255.0f); // 0: valid, 4: other invalid
        if (!valid) {
            output.motion = float2(0.0f);
        }
    }

    return output;
}

struct MetallumFastTemporalUniforms {
    float2 inputExtent;
    float2 jitter;
    float historyWeight;
    uint historyValid;
    uint resetMask;
    uint reserved;
};

fragment float4 metallum_fast_temporal_resolve_fs(
    MetallumTemporalVertexOut input [[stage_in]],
    texture2d<float, access::sample> colorTexture [[texture(0)]],
    texture2d<float, access::sample> historyTexture [[texture(1)]],
    texture2d<float, access::sample> motionTexture [[texture(2)]],
    texture2d<float, access::sample> reactiveTexture [[texture(3)]],
    constant MetallumFastTemporalUniforms& uniforms [[buffer(0)]]
) {
    constexpr sampler linearSampler(coord::normalized, address::clamp_to_edge, filter::linear);
    float2 outputExtent = float2(historyTexture.get_width(), historyTexture.get_height());
    float2 outputUv = input.position.xy / outputExtent;
    float2 inputTexel = 1.0f / uniforms.inputExtent;

    // The low-resolution scene carries the current projection jitter. Sample
    // at the matching shifted coordinate before blending it with the
    // unjittered display-resolution history.
    float2 currentUv = clamp(
        outputUv + uniforms.jitter / uniforms.inputExtent,
        0.0f,
        1.0f
    );
    float4 current = colorTexture.sample(linearSampler, currentUv);

    if (uniforms.historyValid == 0u || uniforms.resetMask != 0u) {
        return current;
    }

    float2 motionPixels = motionTexture.sample(linearSampler, currentUv).xy;
    float2 historyUv = clamp(
        outputUv + motionPixels / uniforms.inputExtent,
        0.0f,
        1.0f
    );
    float4 history = historyTexture.sample(linearSampler, historyUv);
    float reactive = clamp(reactiveTexture.sample(linearSampler, currentUv).x, 0.0f, 1.0f);

    // Clamp reprojected history to the local low-resolution color bounds.
    // This preserves accumulated sub-pixel detail while rejecting disoccluded
    // geometry and high-contrast moving edges rather than smearing them.
    float3 neighborX = colorTexture.sample(linearSampler, currentUv + float2(inputTexel.x, 0.0f)).rgb;
    float3 neighborY = colorTexture.sample(linearSampler, currentUv + float2(0.0f, inputTexel.y)).rgb;
    float3 neighborDiagonal = colorTexture.sample(
        linearSampler,
        currentUv - float2(inputTexel.x, inputTexel.y)
    ).rgb;
    float3 lower = min(current.rgb, min(neighborX, min(neighborY, neighborDiagonal)));
    float3 upper = max(current.rgb, max(neighborX, max(neighborY, neighborDiagonal)));
    float3 clampedHistory = clamp(history.rgb, lower, upper);
    float colorDelta = max(
        abs(clampedHistory.r - current.r),
        max(abs(clampedHistory.g - current.g), abs(clampedHistory.b - current.b))
    );
    float consistency = 1.0f - smoothstep(0.03f, 0.18f, colorDelta);
    float weight = uniforms.historyWeight * (1.0f - reactive) * consistency;
    return float4(mix(current.rgb, clampedHistory, weight), current.a);
}

kernel void metallum_motion_vector_validate(
    const device MetallumMotionValidationInput* inputs [[buffer(0)]],
    device MetallumMotionValidationOutput* outputs [[buffer(1)]],
    constant float2& renderExtent [[buffer(2)]],
    constant uint& resetMask [[buffer(3)]],
    constant float2& jitter [[buffer(4)]],
    constant float2& prevJitter [[buffer(5)]],
    uint index [[thread_position_in_grid]]
) {
    MetallumMotionValidationInput input = inputs[index];
    MetallumMotionValidationOutput output;
    bool invalid = resetMask != 0u || input.invalidDepth != 0u
        || !all(isfinite(input.currentClip)) || !all(isfinite(input.previousClip))
        || abs(input.currentClip.w) < 1.0e-7f || abs(input.previousClip.w) < 1.0e-7f;
    if (invalid) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
    } else {
        float2 currentNdc = input.currentClip.xy / input.currentClip.w;
        float2 currentNdcUnjittered = float2(
            currentNdc.x - jitter.x * 2.0f / renderExtent.x,
            currentNdc.y + jitter.y * 2.0f / renderExtent.y
        );
        float2 previousNdc = input.previousClip.xy / input.previousClip.w;
        float2 previousNdcUnjittered = float2(
            previousNdc.x - prevJitter.x * 2.0f / renderExtent.x,
            previousNdc.y + prevJitter.y * 2.0f / renderExtent.y
        );
        output.motion = metallum_motion_pixels(currentNdcUnjittered, previousNdcUnjittered, renderExtent);
        output.reactive = all(isfinite(output.motion)) ? 0.0f : 1.0f;
        if (output.reactive != 0.0f) {
            output.motion = float2(0.0f);
        }
    }
    output.reserved = 0.0f;
    outputs[index] = output;
}

struct MetallumReprojectionValidationInput {
    float2 pixelCoord;
    float depth;
    uint reserved;
};

kernel void metallum_reprojection_validate(
    const device MetallumReprojectionValidationInput* inputs [[buffer(0)]],
    device MetallumMotionValidationOutput* outputs [[buffer(1)]],
    constant MetallumTemporalUniforms& uniforms [[buffer(2)]],
    uint index [[thread_position_in_grid]]
) {
    MetallumReprojectionValidationInput input = inputs[index];
    MetallumMotionValidationOutput output;

    float depth = input.depth;
    if (uniforms.resetMask != 0u || !isfinite(depth) || depth <= 0.0f || depth >= 1.0f) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
    } else {
        // Current NDC coordinate of the pixel
        float2 currentNdc = float2(
            (input.pixelCoord.x / uniforms.renderExtent.x) * 2.0f - 1.0f,
            1.0f - (input.pixelCoord.y / uniforms.renderExtent.y) * 2.0f
        );

        // Unjitter the current NDC coordinate
        float2 currentNdcUnjittered = float2(
            currentNdc.x - uniforms.jitter.x * 2.0f / uniforms.renderExtent.x,
            currentNdc.y + uniforms.jitter.y * 2.0f / uniforms.renderExtent.y
        );

        // Reconstruct camera space position from depth
        float4 currentLocalH = uniforms.inverseCurrentProjection * float4(currentNdc, depth, 1.0f);
        if (!isfinite(currentLocalH.w) || abs(currentLocalH.w) < 1.0e-7f) {
            output.motion = float2(0.0f);
            output.reactive = 1.0f;
        } else {
            float3 currentLocal = currentLocalH.xyz / currentLocalH.w;

            // Static terrain reprojection
            float3 worldRelativeCurrent = (uniforms.inverseCurrentView * float4(currentLocal, 1.0f)).xyz;
            float3 previousRelative = worldRelativeCurrent
                + uniforms.currentCameraPosition.xyz
                - uniforms.previousCameraPosition.xyz;

            // Project to previous clip space
            float4 previousClip = uniforms.previousProjection * uniforms.previousView * float4(previousRelative, 1.0f);
            if (!all(isfinite(previousClip)) || abs(previousClip.w) < 1.0e-7f) {
                output.motion = float2(0.0f);
                output.reactive = 1.0f;
            } else {
                float2 previousNdc = previousClip.xy / previousClip.w;

                // Compute motion vectors
                output.motion = metallum_motion_pixels(currentNdcUnjittered, previousNdc, uniforms.renderExtent);
                output.reactive = all(isfinite(output.motion)) ? 0.0f : 1.0f;
                if (output.reactive != 0.0f) {
                    output.motion = float2(0.0f);
                }
            }
        }
    }
    output.reserved = 0.0f;
    outputs[index] = output;
}
