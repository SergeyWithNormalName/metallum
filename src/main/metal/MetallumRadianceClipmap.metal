#include <metal_stdlib>
using namespace metal;

/**
 * G1 field-only coverage reduction. The four payload channels are deliberately semantic-free:
 * G2 will define material/source meaning. Unknown children contribute no payload weight, while
 * the parent coverage records the known fraction of its 2x2x2 footprint.
 */
kernel void metallum_gi_field_downsample_v1(
    texture3d<half, access::read> sourceField [[texture(0)]],
    texture3d<half, access::read> sourceCoverage [[texture(1)]],
    texture3d<half, access::write> destinationField [[texture(2)]],
    texture3d<half, access::write> destinationCoverage [[texture(3)]],
    uint3 destinationPosition [[thread_position_in_grid]]
) {
    if (destinationPosition.x >= destinationField.get_width()
            || destinationPosition.y >= destinationField.get_height()
            || destinationPosition.z >= destinationField.get_depth()) {
        return;
    }

    uint3 sourceBase = destinationPosition * 2u;
    float4 weightedPayload = float4(0.0f);
    float support = 0.0f;
    for (uint z = 0u; z < 2u; ++z) {
        for (uint y = 0u; y < 2u; ++y) {
            for (uint x = 0u; x < 2u; ++x) {
                uint3 sourcePosition = sourceBase + uint3(x, y, z);
                float childCoverage = clamp(float(sourceCoverage.read(sourcePosition).r), 0.0f, 1.0f);
                weightedPayload += float4(sourceField.read(sourcePosition)) * childCoverage;
                support += childCoverage;
            }
        }
    }

    float inverseSupport = support > 1.0e-6f ? 1.0f / support : 0.0f;
    destinationField.write(half4(weightedPayload * inverseSupport), destinationPosition);
    destinationCoverage.write(half4(half(support * 0.125f), 0.0h, 0.0h, 0.0h),
            destinationPosition);
}

/**
 * Coverage-aware 3D radiance and opacity downsampling compute kernel for the frozen source field.
 *
 * Each invocation at dstPos computes 1 parent texel in mip (d + 1) from an 8-texel 2x2x2 sub-block in mip d.
 * Child radiance is weighted by valid coverage fraction and optical presence.
 */
kernel void metallum_radiance_downsample_mip(
    texture3d<half, access::read> srcRadiance [[texture(0)]],
    texture3d<half, access::read> srcCoverage [[texture(1)]],
    texture3d<half, access::write> dstRadiance [[texture(2)]],
    texture3d<half, access::write> dstCoverage [[texture(3)]],
    uint3 dstPos [[thread_position_in_grid]]
) {
    if (dstPos.x >= dstRadiance.get_width() ||
        dstPos.y >= dstRadiance.get_height() ||
        dstPos.z >= dstRadiance.get_depth()) {
        return;
    }

    uint3 baseSrc = dstPos * 2;
    half totalCoverage = 0.0h;
    half totalOpacity = 0.0h;
    half3 totalWeightedRadiance = half3(0.0h);
    half totalWeight = 0.0h;

    for (uint dz = 0; dz < 2; ++dz) {
        for (uint dy = 0; dy < 2; ++dy) {
            for (uint dx = 0; dx < 2; ++dx) {
                uint3 srcPos = baseSrc + uint3(dx, dy, dz);
                half4 radSample = srcRadiance.read(srcPos);
                half covSample = srcCoverage.read(srcPos).r;

                half3 childRad = radSample.rgb;
                half childAlpha = radSample.a;

                totalCoverage += covSample;
                totalOpacity += childAlpha * covSample;

                half maxChannel = max(childRad.r, max(childRad.g, childRad.b));
                half emissiveBoost = maxChannel > 1.0h ? 2.0h : 0.0h;
                half weight = covSample * (childAlpha + emissiveBoost);

                totalWeightedRadiance += childRad * weight;
                totalWeight += weight;
            }
        }
    }

    half parentCoverage = totalCoverage * 0.125h;
    half parentOpacity = totalOpacity * 0.125h;
    half3 parentRadiance = half3(0.0h);

    if (totalWeight > 0.00001h) {
        parentRadiance = totalWeightedRadiance / totalWeight;
    } else if (totalCoverage > 0.00001h) {
        half3 unweightedRad = half3(0.0h);
        for (uint dz = 0; dz < 2; ++dz) {
            for (uint dy = 0; dy < 2; ++dy) {
                for (uint dx = 0; dx < 2; ++dx) {
                    uint3 srcPos = baseSrc + uint3(dx, dy, dz);
                    half4 radSample = srcRadiance.read(srcPos);
                    half covSample = srcCoverage.read(srcPos).r;
                    unweightedRad += radSample.rgb * covSample;
                }
            }
        }
        parentRadiance = unweightedRad / totalCoverage;
    }

    dstRadiance.write(half4(parentRadiance, parentOpacity), dstPos);
    dstCoverage.write(half4(parentCoverage, 0.0h, 0.0h, 0.0h), dstPos);
}
