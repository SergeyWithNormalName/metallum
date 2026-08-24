#include <metal_stdlib>
using namespace metal;

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

// Frozen Directional Probe Stage A -------------------------------------------------------------
// The source field covers a 128-block volume (64^3, 2 blocks/cell).
// The directional probe covers the SAME 128-block volume (32^3, 4 blocks/cell).
// Six deterministic axis directions times two fixed samples (12 and 32 blocks) are evaluated
// in this one-shot compute pass (12 logical spatial samples, 24 physical texture reads per probe).

struct MetallumDirectionalProbeBuildParams {
    int4 sourceOriginAndSpan; // xyz = origin, w = span (128)
    int4 probeOriginAndSpan;  // xyz = origin, w = span (128)
};

constexpr sampler metallumDirectionalProbeSourceSampler(
    coord::normalized,
    address::clamp_to_edge,
    filter::linear,
    mip_filter::linear);

constant float3 metallumDirectionalProbeDirections[6] = {
    float3(1.0f, 0.0f, 0.0f), float3(-1.0f, 0.0f, 0.0f),
    float3(0.0f, 1.0f, 0.0f), float3(0.0f, -1.0f, 0.0f),
    float3(0.0f, 0.0f, 1.0f), float3(0.0f, 0.0f, -1.0f)
};
constant float metallumDirectionalProbeDistances[2] = { 12.0f, 32.0f };

kernel void metallum_directional_probe_build_frozen(
    texture3d<half, access::sample> sourceRadiance [[texture(0)]],
    texture3d<half, access::sample> sourceCoverage [[texture(1)]],
    texture3d<half, access::write> destinationRadiance [[texture(2)]],
    texture3d<half, access::write> destinationMoment [[texture(3)]],
    constant MetallumDirectionalProbeBuildParams& params [[buffer(0)]],
    uint3 probePos [[thread_position_in_grid]]
) {
    if (probePos.x >= destinationRadiance.get_width()
            || probePos.y >= destinationRadiance.get_height()
            || probePos.z >= destinationRadiance.get_depth()) {
        return;
    }

    int3 sourceOrigin = params.sourceOriginAndSpan.xyz;
    float sourceSpan = float(params.sourceOriginAndSpan.w);
    int3 probeOrigin = params.probeOriginAndSpan.xyz;
    float probeSpan = float(params.probeOriginAndSpan.w);
    float probeEdge = float(destinationRadiance.get_width());
    float probeCellSize = probeSpan / probeEdge;

    float3 worldProbe = float3(probeOrigin) + (float3(probePos) + 0.5f) * probeCellSize;
    float3 radianceSum = float3(0.0f);
    float3 directionalMomentSum = float3(0.0f);
    float energySum = 0.0f;
    float supportSum = 0.0f;

    for (uint directionIndex = 0u; directionIndex < 6u; ++directionIndex) {
        float3 direction = metallumDirectionalProbeDirections[directionIndex];
        for (uint stepIndex = 0u; stepIndex < 2u; ++stepIndex) {
            float3 sampleWorld = worldProbe + direction * metallumDirectionalProbeDistances[stepIndex];
            float3 uvw = (sampleWorld - float3(sourceOrigin)) / sourceSpan;
            // Outside source field is unavailable, not edge-clamped world data. It contributes no
            // support so the receiver conservatively keeps confidence = 0.
            if (all(uvw >= float3(0.0f)) && all(uvw < float3(1.0f))) {
                half4 sourceRadHalf = sourceRadiance.sample(
                    metallumDirectionalProbeSourceSampler, uvw, level(1.0f));
                float coverage = clamp(float(sourceCoverage.sample(
                    metallumDirectionalProbeSourceSampler, uvw, level(1.0f)).r), 0.0f, 1.0f);
                float3 radiance = max(float3(sourceRadHalf.rgb), float3(0.0f));
                // Coverage only means that the frozen source has accepted data at this point;
                // air is therefore fully covered but contributes no outgoing radiance.  Weighting
                // the probe by coverage alone diluted every surface sample with known-empty air
                // and gave water a high confidence black result.  The source alpha is the
                // prefiltered optical presence from the coverage-aware mip chain, so it is the
                // conservative support term for this rough directional sample.
                float opticalSupport = coverage * clamp(float(sourceRadHalf.a), 0.0f, 1.0f);
                float energy = max(dot(radiance, float3(0.2126f, 0.7152f, 0.0722f)), 0.0f);
                radianceSum += radiance * opticalSupport;
                directionalMomentSum += direction * (energy * opticalSupport);
                energySum += energy * opticalSupport;
                supportSum += opticalSupport;
            }
        }
    }

    float inverseSupport = supportSum > 1.0e-5f ? 1.0f / supportSum : 0.0f;
    float support = supportSum * (1.0f / 12.0f);
    // A is coverage-weighted radiance plus support. B is the unencoded Cartesian first moment
    // plus its scalar energy. Both survive hardware trilinear interpolation without wraps.
    destinationRadiance.write(half4(half3(radianceSum * inverseSupport), half(support)), probePos);
    destinationMoment.write(half4(half3(directionalMomentSum * inverseSupport),
            half(energySum * inverseSupport)), probePos);
}

kernel void metallum_directional_probe_downsample_mip(
    texture3d<half, access::read> sourceRadiance [[texture(0)]],
    texture3d<half, access::read> sourceMoment [[texture(1)]],
    texture3d<half, access::write> destinationRadiance [[texture(2)]],
    texture3d<half, access::write> destinationMoment [[texture(3)]],
    uint3 destinationPos [[thread_position_in_grid]]
) {
    if (destinationPos.x >= destinationRadiance.get_width()
            || destinationPos.y >= destinationRadiance.get_height()
            || destinationPos.z >= destinationRadiance.get_depth()) {
        return;
    }

    uint3 sourceBase = destinationPos * 2u;
    float3 weightedRadiance = float3(0.0f);
    float3 weightedMoment = float3(0.0f);
    float weightedEnergy = 0.0f;
    float totalSupport = 0.0f;

    for (uint z = 0u; z < 2u; ++z) {
        for (uint y = 0u; y < 2u; ++y) {
            for (uint x = 0u; x < 2u; ++x) {
                half4 radiance = sourceRadiance.read(sourceBase + uint3(x, y, z));
                half4 moment = sourceMoment.read(sourceBase + uint3(x, y, z));
                float support = clamp(float(radiance.a), 0.0f, 1.0f);
                weightedRadiance += float3(radiance.rgb) * support;
                weightedMoment += float3(moment.rgb) * support;
                weightedEnergy += float(moment.a) * support;
                totalSupport += support;
            }
        }
    }

    float inverseWeight = totalSupport > 1.0e-5f ? 1.0f / totalSupport : 0.0f;
    float parentSupport = totalSupport * 0.125f;
    destinationRadiance.write(half4(half3(weightedRadiance * inverseWeight), half(parentSupport)),
            destinationPos);
    destinationMoment.write(half4(half3(weightedMoment * inverseWeight),
            half(weightedEnergy * inverseWeight)), destinationPos);
}
