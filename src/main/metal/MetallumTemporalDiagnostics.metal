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
    float4x4 inversePreviousJitteredProjection;
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

    // A positive logical jitter moves Minecraft's right-handed perspective
    // raster by the opposite amount. Undo that physical shift before static
    // reprojection; applying the signs below in reverse injects up to two
    // jitter samples of false camera motion every frame.
    float2 currentNdcUnjittered = float2(
        currentNdc.x + uniforms.jitter.x * 2.0f / uniforms.renderExtent.x,
        currentNdc.y - uniforms.jitter.y * 2.0f / uniforms.renderExtent.y
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
    float4 expectedPreviousViewH = uniforms.previousView * float4(previousRelative, 1.0f);
    if (!all(isfinite(expectedPreviousViewH)) || abs(expectedPreviousViewH.w) < 1.0e-7f) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
        output.classification = 4.0f / 255.0f; // 4: other invalid
        return output;
    }
    float expectedPreviousViewDepth = abs(expectedPreviousViewH.z / expectedPreviousViewH.w);

    // Compute motion vectors
    output.motion = metallum_motion_pixels(currentNdcUnjittered, previousNdc, uniforms.renderExtent);

    // The stored depth belongs to the previous *jittered* raster.  Motion is
    // deliberately measured between unjittered frames, so shift the previous
    // location back to the raster coordinate only for the depth lookup.
    float2 previousNdcJittered = float2(
        previousNdc.x - uniforms.previousJitter.x * 2.0f / uniforms.renderExtent.x,
        previousNdc.y + uniforms.previousJitter.y * 2.0f / uniforms.renderExtent.y
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
        bool valid = all(isfinite(output.motion)) && isfinite(expectedPreviousViewDepth)
            && expectedPreviousViewDepth > 0.0f;
        bool depthDisoccluded = false;
        if (valid && uniforms.previousDepthValid != 0u) {
            float2 previousPixel = float2(
                (previousNdcJittered.x * 0.5f + 0.5f) * uniforms.renderExtent.x,
                (1.0f - previousNdcJittered.y) * 0.5f * uniforms.renderExtent.y
            );
            uint2 previousPixelCoordinate = uint2(previousPixel);
            float recordedPreviousDepth = previousDepthTexture.read(previousPixelCoordinate).x;
            float4 recordedPreviousViewH = uniforms.inversePreviousJitteredProjection
                * float4(previousNdcJittered, recordedPreviousDepth, 1.0f);
            bool recordedDepthValid = isfinite(recordedPreviousDepth)
                && recordedPreviousDepth > 0.0f && recordedPreviousDepth < 1.0f
                && all(isfinite(recordedPreviousViewH))
                && abs(recordedPreviousViewH.w) >= 1.0e-7f;
            float recordedPreviousViewDepth = recordedDepthValid
                ? abs(recordedPreviousViewH.z / recordedPreviousViewH.w)
                : 0.0f;

            // Compare in view-space distance, not non-linear reversed-Z.  At
            // long range a fixed normalized-depth epsilon covers many blocks,
            // allowing a reprojected neighbour to contaminate history.  The
            // lower bound absorbs float/raster quantization; the relative
            // allowance is capped at one quarter of a Minecraft block so a
            // distinct voxel surface cannot become a valid history match.
            float viewDepthTolerance = min(
                0.25f,
                max(0.03125f, expectedPreviousViewDepth * 0.001f)
            );
            depthDisoccluded = !recordedDepthValid
                || !isfinite(recordedPreviousViewDepth)
                || abs(recordedPreviousViewDepth - expectedPreviousViewDepth) > viewDepthTolerance;
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
            currentNdc.x + jitter.x * 2.0f / renderExtent.x,
            currentNdc.y - jitter.y * 2.0f / renderExtent.y
        );
        float2 previousNdc = input.previousClip.xy / input.previousClip.w;
        float2 previousNdcUnjittered = float2(
            previousNdc.x + prevJitter.x * 2.0f / renderExtent.x,
            previousNdc.y - prevJitter.y * 2.0f / renderExtent.y
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

        // See metallum_temporal_diagnostic_fs for the right-handed jitter
        // convention used by the Minecraft projection matrix.
        float2 currentNdcUnjittered = float2(
            currentNdc.x + uniforms.jitter.x * 2.0f / uniforms.renderExtent.x,
            currentNdc.y - uniforms.jitter.y * 2.0f / uniforms.renderExtent.y
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
