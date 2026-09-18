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

// G3 direct-source ABI. This remains a compute-only field path: it has no terrain
// binding, receiver, reflectance/albedo input, or bounce transport.
constant uint metallumGiDirectUnknown = 0u;
constant uint metallumGiDirectEmpty = 1u;
constant uint metallumGiDirectContent = 2u;
constant uint metallumGiDirectFallback = 3u;
constant uint metallumGiDirectBrickEdge = 8u;
constant uint metallumGiDirectCellsPerBrick = 512u;
constant uint metallumGiDirectMaxSourcesPerBrick = 16u;
constant uint metallumGiDirectDdaSteps = 32u;

struct MetallumGiDirectHeaderV1 {
    uint abiVersion;
    uint headerBytes;
    ulong worldGeneration;
    ulong clipmapGeneration;
    ulong paletteGeneration;
    ulong contentGeneration;
    ulong staticSourceEpoch;
    ulong environmentEpoch;
    int origins[9];
    float4 sunDirectionAndEnabled;
    float4 sunRgbAndEnabled;
    float4 skyRgbAndEnabled;
    uint dirtyBrickCount;
    uint sourceCount;
    uint flags;
    uint reserved0;
};

struct MetallumGiDirectBrickV1 {
    uint cascade;
    int brickX;
    int brickY;
    int brickZ;
    uint sourceOffset;
    uint sourceCount;
    ulong stamp;
};

struct MetallumGiDirectCellV1 {
    half4 emissionRgbIntensity;
    uchar geometryState;
    uchar padding[7];
};

struct MetallumGiDirectSourceV1 {
    // Static BLOCK source only; xyz is relative to the descriptor cascade origin.
    float4 positionRadius;
    float4 rgbIntensity;
};

inline bool metallum_gi_direct_inside(int3 position) {
    return all(position >= int3(0)) && all(position < int3(32));
}

// UNKNOWN and FALLBACK are deliberately conservative occluders. Only a known
// empty cell permits a direct source ray to pass.
inline bool metallum_gi_direct_occludes(
    texture3d<uint, access::read> geometry,
    int3 position
) {
    return geometry.read(uint3(position)).r != metallumGiDirectEmpty;
}

inline bool metallum_gi_direct_visible_to_boundary(
    texture3d<uint, access::read> geometry,
    int3 start,
    float3 direction
) {
    if (dot(direction, direction) <= 1.0e-8f) return false;
    float3 unitDirection = normalize(direction);
    int3 previous = start;
    bool observedKnownEmpty = false;
    for (uint step = 1u; step <= metallumGiDirectDdaSteps; ++step) {
        int3 position = start + int3(round(unitDirection * float(step)));
        if (all(position == previous)) continue;
        previous = position;
        // Leaving directly from a content cell at the clipmap boundary is not proof of sky.
        // At least one authoritative AIR cell must connect the surface to the boundary.
        if (!metallum_gi_direct_inside(position)) return observedKnownEmpty;
        if (metallum_gi_direct_occludes(geometry, position)) return false;
        observedKnownEmpty = true;
    }
    return false;
}

inline bool metallum_gi_direct_visible_to_source(
    texture3d<uint, access::read> geometry,
    int3 start,
    int3 source
) {
    float3 delta = float3(source - start);
    float distance = length(delta);
    if (distance <= 1.0e-5f) return true;
    uint steps = min(metallumGiDirectDdaSteps, uint(ceil(distance)));
    int3 previous = start;
    // Do not classify the source cell itself as an intervening occluder.
    for (uint step = 1u; step < steps; ++step) {
        int3 position = start + int3(round(normalize(delta) * float(step)));
        if (all(position == previous)) continue;
        previous = position;
        if (!metallum_gi_direct_inside(position)) return false;
        // ceil(distance) bounds the loop, but component-wise round() can reach a diagonal
        // endpoint one iteration early. The endpoint is the emissive CONTENT cell itself, not
        // an intervening occluder; treating it as geometry made common diagonal point lights
        // disappear from the direct field and therefore from the one-bounce transport.
        if (all(position == source)) return true;
        if (metallum_gi_direct_occludes(geometry, position)) return false;
    }
    return metallum_gi_direct_inside(source);
}

// Create-time only initialization.  This is intentionally not part of the
// dirty path: steady state touches only the supplied 8^3 bricks.
kernel void metallum_gi_direct_clear_v1(
    texture3d<uint, access::write> geometry [[texture(0)]],
    texture3d<half, access::write> directIrradiance [[texture(1)]],
    uint3 position [[thread_position_in_grid]]
) {
    if (!metallum_gi_direct_inside(int3(position))) return;
    geometry.write(uint4(metallumGiDirectUnknown, 0u, 0u, 0u), position);
    directIrradiance.write(half4(0.0h), position);
}

// Diagnostic-only compact R8 readback.  Texture-to-buffer blits require a
// 256-byte row stride; this avoids reserving 8 KiB for a 1 KiB geometry slice.
kernel void metallum_gi_direct_capture_geometry_slice_v1(
    constant uint &slice [[buffer(0)]],
    texture3d<uint, access::read> geometry [[texture(0)]],
    device uchar *outGeometry [[buffer(1)]],
    uint2 position [[thread_position_in_grid]]
) {
    if (position.x >= 32u || position.y >= 32u || slice >= 32u) return;
    outGeometry[position.y * 32u + position.x] = uchar(geometry.read(uint3(position, slice)).r);
}

kernel void metallum_gi_direct_geometry_apply_v1(
    constant MetallumGiDirectHeaderV1 &header [[buffer(0)]],
    device const MetallumGiDirectBrickV1 *bricks [[buffer(1)]],
    device const MetallumGiDirectCellV1 *cells [[buffer(2)]],
    texture3d<uint, access::write> geometry [[texture(0)]],
    uint3 threadPosition [[thread_position_in_grid]]
) {
    uint brickIndex = threadPosition.z / metallumGiDirectBrickEdge;
    uint localZ = threadPosition.z % metallumGiDirectBrickEdge;
    if (brickIndex >= header.dirtyBrickCount || threadPosition.x >= metallumGiDirectBrickEdge
            || threadPosition.y >= metallumGiDirectBrickEdge || localZ >= metallumGiDirectBrickEdge) {
        return;
    }
    MetallumGiDirectBrickV1 brick = bricks[brickIndex];
    if (brick.cascade >= 3u || brick.stamp == 0ul) return;
    int3 destination = int3(brick.brickX, brick.brickY, brick.brickZ) * int(metallumGiDirectBrickEdge)
        + int3(threadPosition.x, threadPosition.y, localZ);
    if (!metallum_gi_direct_inside(destination)) return;
    uint cellIndex = brickIndex * metallumGiDirectCellsPerBrick
        + (localZ * metallumGiDirectBrickEdge + threadPosition.y) * metallumGiDirectBrickEdge + threadPosition.x;
    geometry.write(uint4(uint(cells[cellIndex].geometryState), 0u, 0u, 0u), uint3(destination));
}

kernel void metallum_gi_direct_inject_v1(
    constant MetallumGiDirectHeaderV1 &header [[buffer(0)]],
    device const MetallumGiDirectBrickV1 *bricks [[buffer(1)]],
    device const MetallumGiDirectCellV1 *cells [[buffer(2)]],
    device const MetallumGiDirectSourceV1 *sources [[buffer(3)]],
    texture3d<uint, access::read> geometry [[texture(0)]],
    texture3d<half, access::write> directIrradiance [[texture(1)]],
    uint3 threadPosition [[thread_position_in_grid]]
) {
    uint brickIndex = threadPosition.z / metallumGiDirectBrickEdge;
    uint localZ = threadPosition.z % metallumGiDirectBrickEdge;
    if (brickIndex >= header.dirtyBrickCount || threadPosition.x >= metallumGiDirectBrickEdge
            || threadPosition.y >= metallumGiDirectBrickEdge || localZ >= metallumGiDirectBrickEdge) {
        return;
    }
    MetallumGiDirectBrickV1 brick = bricks[brickIndex];
    if (brick.cascade >= 3u || brick.stamp == 0ul || brick.sourceCount > metallumGiDirectMaxSourcesPerBrick) {
        return;
    }
    int3 position = int3(brick.brickX, brick.brickY, brick.brickZ) * int(metallumGiDirectBrickEdge)
        + int3(threadPosition.x, threadPosition.y, localZ);
    if (!metallum_gi_direct_inside(position)) return;
    uint cellIndex = brickIndex * metallumGiDirectCellsPerBrick
        + (localZ * metallumGiDirectBrickEdge + threadPosition.y) * metallumGiDirectBrickEdge + threadPosition.x;
    MetallumGiDirectCellV1 cell = cells[cellIndex];
    if (cell.geometryState == metallumGiDirectUnknown || cell.geometryState == metallumGiDirectEmpty
            || cell.geometryState == metallumGiDirectFallback || cell.geometryState != metallumGiDirectContent) {
        directIrradiance.write(half4(0.0h), uint3(position));
        return;
    }

    float3 energy = max(float3(cell.emissionRgbIntensity.rgb), float3(0.0f))
        * max(float(cell.emissionRgbIntensity.a), 0.0f);
    if (header.sunDirectionAndEnabled.w > 0.0f
            && metallum_gi_direct_visible_to_boundary(geometry, position, header.sunDirectionAndEnabled.xyz)) {
        energy += max(header.sunRgbAndEnabled.rgb, float3(0.0f)) * header.sunRgbAndEnabled.w;
    }
    // Fixed +Y sky probe is deterministic and conservative; a later stage may add a
    // declared sky quadrature, but cannot turn an unknown geometry cell into sky.
    if (header.skyRgbAndEnabled.w > 0.0f
            && metallum_gi_direct_visible_to_boundary(geometry, position, float3(0.0f, 1.0f, 0.0f))) {
        energy += max(header.skyRgbAndEnabled.rgb, float3(0.0f)) * header.skyRgbAndEnabled.w;
    }

    const int cellSize = brick.cascade == 0u ? 1 : (brick.cascade == 1u ? 4 : 8);
    // Source coordinates are relative to this cascade origin.  The producer
    // duplicates the bounded source list per brick/cascade, avoiding loss of
    // block precision at large absolute Minecraft world coordinates.
    float3 worldPosition = float3(position * cellSize) + 0.5f * float(cellSize);
    for (uint sourceIndex = 0u; sourceIndex < brick.sourceCount; ++sourceIndex) {
        MetallumGiDirectSourceV1 source = sources[brick.sourceOffset + sourceIndex];
        float radius = source.positionRadius.w;
        if (!(radius > 0.0f)) continue;
        float distance = length(source.positionRadius.xyz - worldPosition);
        float attenuation = max(0.0f, 1.0f - distance / radius);
        attenuation *= attenuation;
        if (attenuation <= 0.0f) continue;
        int3 sourceCell = int3(floor(source.positionRadius.xyz / float(cellSize)));
        if (metallum_gi_direct_visible_to_source(geometry, position, sourceCell)) {
            energy += max(source.rgbIntensity.rgb, float3(0.0f))
                * max(source.rgbIntensity.w, 0.0f) * attenuation;
        }
    }
    energy = max(energy, float3(0.0f));
    float support = any(energy > float3(0.0f)) ? 1.0f : 0.0f;
    directIrradiance.write(half4(half3(energy), half(support)), uint3(position));
}
