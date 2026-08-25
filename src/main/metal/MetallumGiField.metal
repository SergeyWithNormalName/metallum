#include <metal_stdlib>
using namespace metal;

/** G1 field-only coverage reduction retained as the neutral mechanical baseline. */
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

constant ushort metallumGiValidityUnknown = 0u;
constant ushort metallumGiValidityKnownEmpty = 1u;
constant ushort metallumGiValidityKnownContent = 2u;
constant ushort metallumGiValidityKnownFallback = 3u;
constant ushort metallumGiPaletteUnknown = 0u;
constant ushort metallumGiPaletteAir = 1u;
constant ushort metallumGiPaletteFallback = 2u;
constant ushort metallumGiPaletteFallbackProvenance = 1u << 4u;

/**
 * G2 semantic reduction. Unknown children have zero weight. Albedo, emission and six face weights
 * retain averages plus explicit support instead of summing radiance arbitrarily. Medium and
 * provenance are exact conservative ORs; palette ID is retained only when all contributing
 * material cells agree, otherwise the explicit fallback ID/provenance is used.
 */
kernel void metallum_gi_semantic_downsample_v1(
    texture3d<half, access::read> sourceMaterial [[texture(0)]],
    texture3d<half, access::read> sourceEmission [[texture(1)]],
    texture3d<half, access::read> sourceFaces0 [[texture(2)]],
    texture3d<half, access::read> sourceFaces1 [[texture(3)]],
    texture3d<ushort, access::read> sourceState [[texture(4)]],
    texture3d<ushort, access::read> sourcePalette [[texture(5)]],
    texture3d<half, access::read> sourceCoverage [[texture(6)]],
    texture3d<half, access::write> destinationMaterial [[texture(7)]],
    texture3d<half, access::write> destinationEmission [[texture(8)]],
    texture3d<half, access::write> destinationFaces0 [[texture(9)]],
    texture3d<half, access::write> destinationFaces1 [[texture(10)]],
    texture3d<ushort, access::write> destinationState [[texture(11)]],
    texture3d<ushort, access::write> destinationPalette [[texture(12)]],
    texture3d<half, access::write> destinationCoverage [[texture(13)]],
    uint3 destinationPosition [[thread_position_in_grid]]
) {
    if (destinationPosition.x >= destinationMaterial.get_width()
            || destinationPosition.y >= destinationMaterial.get_height()
            || destinationPosition.z >= destinationMaterial.get_depth()) {
        return;
    }

    uint3 sourceBase = destinationPosition * 2u;
    float3 weightedAlbedo = float3(0.0f);
    float albedoSupport = 0.0f;
    float3 weightedEmission = float3(0.0f);
    float emissionSupport = 0.0f;
    float4 faceWeights0 = float4(0.0f);
    float2 faceWeights1 = float2(0.0f);
    float knownSupport = 0.0f;
    ushort mediumMask = 0u;
    ushort provenanceMask = 0u;
    ushort commonPalette = metallumGiPaletteUnknown;
    bool paletteInitialized = false;
    bool mixedPalette = false;
    bool anyContent = false;
    bool anyFallback = false;

    for (uint z = 0u; z < 2u; ++z) {
        for (uint y = 0u; y < 2u; ++y) {
            for (uint x = 0u; x < 2u; ++x) {
                uint3 sourcePosition = sourceBase + uint3(x, y, z);
                float coverage = clamp(float(sourceCoverage.read(sourcePosition).r), 0.0f, 1.0f);
                ushort4 state = sourceState.read(sourcePosition);
                ushort validity = state.g;
                if (coverage <= 0.0f || validity == metallumGiValidityUnknown) {
                    continue;
                }

                knownSupport += coverage;
                mediumMask |= state.r;
                provenanceMask |= state.b;
                anyFallback = anyFallback || validity == metallumGiValidityKnownFallback;

                half4 materialSample = sourceMaterial.read(sourcePosition);
                float occupancy = clamp(float(materialSample.a), 0.0f, 1.0f);
                float materialWeight = coverage * occupancy;
                if (materialWeight > 0.0f) {
                    anyContent = true;
                    weightedAlbedo += float3(materialSample.rgb) * materialWeight;
                    albedoSupport += materialWeight;
                    ushort palette = sourcePalette.read(sourcePosition).r;
                    if (!paletteInitialized) {
                        commonPalette = palette;
                        paletteInitialized = true;
                    } else {
                        mixedPalette = mixedPalette || palette != commonPalette;
                    }
                }

                if (validity == metallumGiValidityKnownContent) {
                    half4 emissionSample = sourceEmission.read(sourcePosition);
                    float childEmissionSupport = coverage * clamp(float(emissionSample.a), 0.0f, 1.0f);
                    weightedEmission += max(float3(emissionSample.rgb), float3(0.0f)) * childEmissionSupport;
                    emissionSupport += childEmissionSupport;
                }

                faceWeights0 += clamp(float4(sourceFaces0.read(sourcePosition)), 0.0f, 1.0f) * coverage;
                faceWeights1 += clamp(float2(sourceFaces1.read(sourcePosition).rg), 0.0f, 1.0f) * coverage;
            }
        }
    }

    float inverseAlbedo = albedoSupport > 1.0e-6f ? 1.0f / albedoSupport : 0.0f;
    float inverseEmission = emissionSupport > 1.0e-6f ? 1.0f / emissionSupport : 0.0f;
    float inverseKnown = knownSupport > 1.0e-6f ? 1.0f / knownSupport : 0.0f;
    half4 parentMaterial = anyFallback
        ? half4(0.0h, 0.0h, 0.0h, 1.0h)
        : half4(half3(weightedAlbedo * inverseAlbedo), half(albedoSupport * inverseKnown));
    destinationMaterial.write(parentMaterial, destinationPosition);
    half4 parentEmission = anyFallback
        ? half4(0.0h)
        : half4(half3(weightedEmission * inverseEmission), half(emissionSupport * inverseKnown));
    destinationEmission.write(parentEmission, destinationPosition);
    half4 parentFaces0 = anyFallback ? half4(0.0h) : half4(faceWeights0 * inverseKnown);
    half2 parentFaces1 = anyFallback ? half2(0.0h) : half2(faceWeights1 * inverseKnown);
    destinationFaces0.write(parentFaces0, destinationPosition);
    destinationFaces1.write(half4(parentFaces1, 0.0h, 0.0h), destinationPosition);
    destinationCoverage.write(half4(half(knownSupport * 0.125f), 0.0h, 0.0h, 0.0h),
            destinationPosition);

    ushort parentValidity = metallumGiValidityUnknown;
    ushort parentPalette = metallumGiPaletteUnknown;
    if (anyFallback) {
        parentValidity = metallumGiValidityKnownFallback;
        parentPalette = metallumGiPaletteFallback;
    } else if (anyContent) {
        parentValidity = metallumGiValidityKnownContent;
        if (mixedPalette) {
            parentPalette = metallumGiPaletteFallback;
            provenanceMask |= metallumGiPaletteFallbackProvenance;
        } else {
            parentPalette = commonPalette;
        }
    } else if (knownSupport >= 7.999f) {
        parentValidity = metallumGiValidityKnownEmpty;
        parentPalette = metallumGiPaletteAir;
    }
    destinationState.write(ushort4(mediumMask, parentValidity, provenanceMask, 0u), destinationPosition);
    destinationPalette.write(ushort4(parentPalette, 0u, 0u, 0u), destinationPosition);
}
