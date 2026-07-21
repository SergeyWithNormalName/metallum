#include <metal_stdlib>
using namespace metal;

struct MetallumEntityMotionUniforms {
    float4x4 previousFromCurrentView;
    float4x4 currentUnjitteredProjection;
    float4x4 previousUnjitteredProjection;
    float4x4 currentJitteredProjection;
    float2 renderExtent;
    float alphaCutoff;
    uint isDiscontinuous;
};

struct MetallumEntityMotionVertexIn {
    float3 positionView [[attribute(0)]];
    float2 uv           [[attribute(1)]];
};

struct MetallumEntityMotionVertexOut {
    float4 position [[position]];
    float4 currentClip;
    float4 previousClip;
    float2 uv;
};

struct MetallumEntityMotionOutputsProd {
    float2 motion   [[color(0)]];
    float  reactive [[color(1)]];
};

struct MetallumEntityMotionOutputsDebug {
    float2 motion         [[color(0)]];
    float  reactive       [[color(1)]];
    float  classification [[color(2)]];
};

vertex MetallumEntityMotionVertexOut metallum_entity_motion_vs(
    MetallumEntityMotionVertexIn input [[stage_in]],
    constant MetallumEntityMotionUniforms& uniforms [[buffer(1)]]
) {
    MetallumEntityMotionVertexOut output;

    // Raster depth position using current jittered projection for exact depth testing
    float4 posView = float4(input.positionView, 1.0f);
    output.position = uniforms.currentJitteredProjection * posView;

    // Unjittered clip positions for motion vector calculation
    output.currentClip = uniforms.currentUnjitteredProjection * posView;

    float4 prevViewPos = uniforms.previousFromCurrentView * posView;
    output.previousClip = uniforms.previousUnjitteredProjection * prevViewPos;

    output.uv = input.uv;
    return output;
}

// Inline helper for perspective divide and pixel conversion
inline void compute_entity_motion(
    MetallumEntityMotionVertexOut input,
    constant MetallumEntityMotionUniforms& uniforms,
    thread float2& outMotion,
    thread float& outReactive,
    thread float& outClassification
) {
    if (uniforms.isDiscontinuous != 0u || input.currentClip.w <= 0.0f || input.previousClip.w <= 0.0f) {
        outMotion = float2(0.0f);
        outReactive = 1.0f;
        outClassification = 6.0f / 255.0f; // 6: Entity reset
        return;
    }

    float2 currentNdc = input.currentClip.xy / input.currentClip.w;
    float2 previousNdc = input.previousClip.xy / input.previousClip.w;
    float2 ndcDelta = previousNdc - currentNdc;

    outMotion = float2(
        ndcDelta.x * (0.5f * uniforms.renderExtent.x),
        -ndcDelta.y * (0.5f * uniforms.renderExtent.y)
    );

    if (!all(isfinite(outMotion))) {
        outMotion = float2(0.0f);
        outReactive = 1.0f;
        outClassification = 6.0f / 255.0f; // 6: Entity reset
    } else {
        outReactive = 0.0f;
        outClassification = 5.0f / 255.0f; // 5: Rigid entity
    }
}

// Opaque Fragment Shader (Production)
fragment MetallumEntityMotionOutputsProd metallum_entity_motion_opaque_prod_fs(
    MetallumEntityMotionVertexOut input [[stage_in]],
    constant MetallumEntityMotionUniforms& uniforms [[buffer(1)]]
) {
    MetallumEntityMotionOutputsProd output;
    float classificationUnused;
    compute_entity_motion(input, uniforms, output.motion, output.reactive, classificationUnused);
    return output;
}

// Opaque Fragment Shader (Debug)
fragment MetallumEntityMotionOutputsDebug metallum_entity_motion_opaque_debug_fs(
    MetallumEntityMotionVertexOut input [[stage_in]],
    constant MetallumEntityMotionUniforms& uniforms [[buffer(1)]]
) {
    MetallumEntityMotionOutputsDebug output;
    compute_entity_motion(input, uniforms, output.motion, output.reactive, output.classification);
    return output;
}

// Cutout Fragment Shader (Production)
fragment MetallumEntityMotionOutputsProd metallum_entity_motion_cutout_prod_fs(
    MetallumEntityMotionVertexOut input [[stage_in]],
    texture2d<float, access::sample> tex [[texture(0)]],
    sampler smp [[sampler(0)]],
    constant MetallumEntityMotionUniforms& uniforms [[buffer(1)]]
) {
    float4 texColor = tex.sample(smp, input.uv);
    if (texColor.a < uniforms.alphaCutoff) {
        discard_fragment();
    }
    MetallumEntityMotionOutputsProd output;
    float classificationUnused;
    compute_entity_motion(input, uniforms, output.motion, output.reactive, classificationUnused);
    return output;
}

// Cutout Fragment Shader (Debug)
fragment MetallumEntityMotionOutputsDebug metallum_entity_motion_cutout_debug_fs(
    MetallumEntityMotionVertexOut input [[stage_in]],
    texture2d<float, access::sample> tex [[texture(0)]],
    sampler smp [[sampler(0)]],
    constant MetallumEntityMotionUniforms& uniforms [[buffer(1)]]
) {
    float4 texColor = tex.sample(smp, input.uv);
    if (texColor.a < uniforms.alphaCutoff) {
        discard_fragment();
    }
    MetallumEntityMotionOutputsDebug output;
    compute_entity_motion(input, uniforms, output.motion, output.reactive, output.classification);
    return output;
}
