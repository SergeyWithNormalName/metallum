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
    uint resetMask;
    uint reserved;
};

struct MetallumTemporalOutputs {
    float2 motion [[color(0)]];
    float reactive [[color(1)]];
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
    constant MetallumTemporalUniforms& uniforms [[buffer(0)]]
) {
    MetallumTemporalOutputs output;
    uint2 pixel = uint2(input.position.xy);
    float depth = depthTexture.read(pixel).x;
    if (uniforms.resetMask != 0u || !isfinite(depth) || depth <= 0.0f) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
        return output;
    }

    float2 currentNdc = float2(
        (input.position.x / uniforms.renderExtent.x) * 2.0f - 1.0f,
        1.0f - (input.position.y / uniforms.renderExtent.y) * 2.0f
    );
    float4 currentLocalH = uniforms.inverseCurrentProjection * float4(currentNdc, depth, 1.0f);
    if (!isfinite(currentLocalH.w) || abs(currentLocalH.w) < 1.0e-7f) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
        return output;
    }
    float3 currentLocal = currentLocalH.xyz / currentLocalH.w;
    float3 worldRelativeCurrent = (uniforms.inverseCurrentView * float4(currentLocal, 1.0f)).xyz;
    float3 previousRelative = worldRelativeCurrent
        + uniforms.currentCameraPosition.xyz
        - uniforms.previousCameraPosition.xyz;
    float4 previousClip = uniforms.previousProjection
        * uniforms.previousView
        * float4(previousRelative, 1.0f);
    if (!all(isfinite(previousClip)) || abs(previousClip.w) < 1.0e-7f) {
        output.motion = float2(0.0f);
        output.reactive = 1.0f;
        return output;
    }
    float2 previousNdc = previousClip.xy / previousClip.w;
    output.motion = metallum_motion_pixels(currentNdc, previousNdc, uniforms.renderExtent);
    output.reactive = all(isfinite(output.motion)) ? 0.0f : 1.0f;
    if (output.reactive != 0.0f) {
        output.motion = float2(0.0f);
    }
    return output;
}

kernel void metallum_motion_vector_validate(
    const device MetallumMotionValidationInput* inputs [[buffer(0)]],
    device MetallumMotionValidationOutput* outputs [[buffer(1)]],
    constant float2& renderExtent [[buffer(2)]],
    constant uint& resetMask [[buffer(3)]],
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
        float2 previousNdc = input.previousClip.xy / input.previousClip.w;
        output.motion = metallum_motion_pixels(currentNdc, previousNdc, renderExtent);
        output.reactive = all(isfinite(output.motion)) ? 0.0f : 1.0f;
        if (output.reactive != 0.0f) {
            output.motion = float2(0.0f);
        }
    }
    output.reserved = 0.0f;
    outputs[index] = output;
}
