#include <metal_stdlib>
using namespace metal;

constant uint metallumGiTransportEdge = 32u;
constant uint metallumGiTransportCellCount = 32u * 32u * 32u;
constant uint metallumGiTransportValidityUnknown = 0u;
constant uint metallumGiTransportValidityEmpty = 1u;
constant uint metallumGiTransportValidityContent = 2u;
constant uint metallumGiTransportValidityFallback = 3u;
constant float metallumGiTransportPi = 3.14159265358979323846f;
constant float metallumGiTransportConfidenceWeightNormalization = 29.17999846648958f;
constant float metallumGiTransportAxisBinSolidAngle = 0.8054316831613232f;
constant float metallumGiTransportEdgeBinSolidAngle = 0.48157053442524556f;
constant float metallumGiTransportCornerBinSolidAngle = 0.24436676278603597f;

// G4 uses a separate accepted-G2 ABI. G3's reserved cell bytes stay reserved.
struct MetallumGiTransportCellV1 {
    ushort4 material; // rho RGB UNorm16 + occupancy UNorm16
    uchar faceWeights[6]; // -X,+X,-Y,+Y,-Z,+Z
    uchar validity;
    uchar coverage;
};

static_assert(sizeof(MetallumGiTransportCellV1) == 16, "G4 cell ABI must remain 16 bytes");

struct MetallumGiTransportHeaderV1 {
    uint abiVersion;
    uint headerBytes;
    ulong worldGeneration;
    ulong clipmapGeneration;
    ulong paletteGeneration;
    ulong contentGeneration;
    ulong staticSourceEpoch;
    ulong environmentEpoch;
    int originX;
    int originY;
    int originZ;
    uint cellCount;
    uint iterationCount;
    uint maximumDistance;
    uint validSurfaceCount;
    uint unknownCellCount;
    float formWeightNormalization;
    float fp16AbsoluteTolerance;
    float fp16RelativeTolerance;
    uint flags;
    ulong sourceStamp;
    ulong reserved0;
    ulong reserved1;
};

static_assert(sizeof(MetallumGiTransportHeaderV1) == 128, "G4 header ABI must remain 128 bytes");

inline bool metallum_gi_transport_inside(int3 position) {
    return all(position >= int3(0)) && all(position < int3(int(metallumGiTransportEdge)));
}

inline uint metallum_gi_transport_index(uint3 position) {
    return (position.z * metallumGiTransportEdge + position.y) * metallumGiTransportEdge + position.x;
}

inline bool metallum_gi_transport_has_face_support(MetallumGiTransportCellV1 cell) {
    return cell.faceWeights[0] != 0 || cell.faceWeights[1] != 0
        || cell.faceWeights[2] != 0 || cell.faceWeights[3] != 0
        || cell.faceWeights[4] != 0 || cell.faceWeights[5] != 0;
}

inline bool metallum_gi_transport_surface_is_valid(MetallumGiTransportCellV1 cell) {
    return uint(cell.validity) == metallumGiTransportValidityContent
        && cell.material.w != 0 && cell.coverage != 0
        && metallum_gi_transport_has_face_support(cell);
}

inline float metallum_gi_transport_source_face_support(
    MetallumGiTransportCellV1 cell,
    float3 outwardDirection
) {
    float3 magnitude = abs(outwardDirection);
    float directionLength = length(outwardDirection);
    float faceSum = 0.0f;
    for (uint face = 0u; face < 6u; ++face) {
        faceSum += float(cell.faceWeights[face]) * (1.0f / 255.0f);
    }
    if (!(directionLength > 0.0f) || !(faceSum > 0.0f)) return 0.0f;
    float x = float(cell.faceWeights[outwardDirection.x < 0.0f ? 0 : 1]) * (1.0f / 255.0f);
    float y = float(cell.faceWeights[outwardDirection.y < 0.0f ? 2 : 3]) * (1.0f / 255.0f);
    float z = float(cell.faceWeights[outwardDirection.z < 0.0f ? 4 : 5]) * (1.0f / 255.0f);
    // Face weights describe relative exposed area. Normalizing by their total prevents a cell
    // with several visible faces from duplicating its reflected energy, while the Euclidean
    // projection preserves the source cosine for edge/corner directions.
    return clamp((magnitude.x * x + magnitude.y * y + magnitude.z * z)
        / (directionLength * faceSum), 0.0f, 1.0f);
}

inline float metallum_gi_transport_direction_solid_angle(int3 direction) {
    uint active = uint(direction.x != 0) + uint(direction.y != 0) + uint(direction.z != 0);
    return active == 1u ? metallumGiTransportAxisBinSolidAngle
        : active == 2u ? metallumGiTransportEdgeBinSolidAngle
        : active == 3u ? metallumGiTransportCornerBinSolidAngle : 0.0f;
}

// Quantize DC first, then conservatively project the directional lobe inside
// that stored DC radius.  The 1/512 margin is larger than the worst combined
// normal-half rounding error of three independently stored coefficients; for
// subnormals the declared absolute tolerance covers the remaining error.
inline half4 metallum_gi_transport_quantize_sh(
    float dc,
    float3 directional,
    float relativeTolerance
) {
    // 65504 is the largest finite IEEE binary16 value. Clamping can only remove
    // energy and prevents a rounded high-dynamic-range accumulation from
    // becoming Inf at the storage boundary.
    half storedDc = half(clamp(dc, 0.0f, 65504.0f));
    float radius = float(storedDc);
    float directionalLength = length(directional);
    float safeRadius = max(radius * (1.0f - relativeTolerance), 0.0f);
    if (directionalLength > safeRadius && directionalLength > 0.0f) {
        directional *= safeRadius / directionalLength;
    }
    half3 storedDirectional = half3(directional);
    return half4(storedDc, storedDirectional.x, storedDirectional.y, storedDirectional.z);
}

enum MetallumGiTransportPathStatus : uint {
    MetallumGiTransportPathUnknown = 0u,
    MetallumGiTransportPathVisible = 1u,
    MetallumGiTransportPathOccluded = 2u,
};

// Conservative lattice supercover. For a diagonal step, every non-empty subset
// of the crossed axes is tested. CONTENT before the endpoint is an occluder;
// UNKNOWN/FALLBACK makes the path invalid rather than approximately visible.
inline MetallumGiTransportPathStatus metallum_gi_transport_path_status(
    texture3d<uint, access::read> geometry,
    int3 receiver,
    int3 direction,
    uint distance
) {
    const int activeX = direction.x == 0 ? 0 : 1;
    const int activeY = direction.y == 0 ? 0 : 1;
    const int activeZ = direction.z == 0 ? 0 : 1;
    for (uint step = 1u; step <= distance; ++step) {
        int3 base = receiver + direction * int(step - 1u);
        for (int includeZ = 0; includeZ <= activeZ; ++includeZ) {
            for (int includeY = 0; includeY <= activeY; ++includeY) {
                for (int includeX = 0; includeX <= activeX; ++includeX) {
                    if (includeX == 0 && includeY == 0 && includeZ == 0) continue;
                    bool endpoint = step == distance
                        && includeX == activeX && includeY == activeY && includeZ == activeZ;
                    if (endpoint) continue;
                    int3 position = base + int3(
                        includeX == 0 ? 0 : direction.x,
                        includeY == 0 ? 0 : direction.y,
                        includeZ == 0 ? 0 : direction.z
                    );
                    if (!metallum_gi_transport_inside(position)) {
                        return MetallumGiTransportPathUnknown;
                    }
                    uint state = geometry.read(uint3(position)).r;
                    if (state == metallumGiTransportValidityUnknown
                            || state == metallumGiTransportValidityFallback) {
                        return MetallumGiTransportPathUnknown;
                    }
                    if (state == metallumGiTransportValidityContent) {
                        return MetallumGiTransportPathOccluded;
                    }
                    if (state != metallumGiTransportValidityEmpty) {
                        return MetallumGiTransportPathUnknown;
                    }
                }
            }
        }
    }
    return MetallumGiTransportPathVisible;
}

kernel void metallum_gi_transport_clear_v1(
    texture3d<half, access::write> bounce [[texture(0)]],
    texture3d<half, access::write> shRed [[texture(1)]],
    texture3d<half, access::write> shGreen [[texture(2)]],
    texture3d<half, access::write> shBlue [[texture(3)]],
    texture3d<half, access::write> confidence [[texture(4)]],
    uint3 position [[thread_position_in_grid]]
) {
    if (any(position >= uint3(metallumGiTransportEdge))) return;
    bounce.write(half4(0.0h), position);
    shRed.write(half4(0.0h), position);
    shGreen.write(half4(0.0h), position);
    shBlue.write(half4(0.0h), position);
    confidence.write(half4(0.0h), position);
}

// G6 keeps the accepted G4 numerical kernel, but publishes all three clipmap
// cascades through one texture per SH channel.  Z is the atlas axis: each
// cascade owns one exact 32-slice slab.  The clear is admission/reset work and
// never runs in the steady-state update loop.
kernel void metallum_gi_live_clear_v1(
    texture3d<half, access::write> shRed [[texture(0)]],
    texture3d<half, access::write> shGreen [[texture(1)]],
    texture3d<half, access::write> shBlue [[texture(2)]],
    texture3d<half, access::write> confidence [[texture(3)]],
    uint3 position [[thread_position_in_grid]]
) {
    if (position.x >= metallumGiTransportEdge
            || position.y >= metallumGiTransportEdge
            || position.z >= metallumGiTransportEdge * 3u) return;
    shRed.write(half4(0.0h), position);
    shGreen.write(half4(0.0h), position);
    shBlue.write(half4(0.0h), position);
    confidence.write(half4(0.0h), position);
}

kernel void metallum_gi_transport_bounce_init_v1(
    constant MetallumGiTransportHeaderV1 &header [[buffer(0)]],
    device const MetallumGiTransportCellV1 *cells [[buffer(1)]],
    texture3d<half, access::read> directIrradiance [[texture(0)]],
    texture3d<uint, access::read> geometry [[texture(1)]],
    texture3d<half, access::write> bounce [[texture(2)]],
    uint3 position [[thread_position_in_grid]]
) {
    if (any(position >= uint3(metallumGiTransportEdge))
            || header.cellCount != metallumGiTransportCellCount
            || header.iterationCount != 1u || header.maximumDistance != 8u) {
        return;
    }
    uint index = metallum_gi_transport_index(position);
    MetallumGiTransportCellV1 cell = cells[index];
    if (!metallum_gi_transport_surface_is_valid(cell)
            || geometry.read(position).r != metallumGiTransportValidityContent) {
        bounce.write(half4(0.0h), position);
        return;
    }
    float4 directSample = float4(directIrradiance.read(position));
    float3 direct = max(directSample.rgb, float3(0.0f));
    if (!all(isfinite(direct)) || !(directSample.a > 0.0f)) {
        bounce.write(half4(0.0h), position);
        return;
    }
    float3 rho = clamp(float3(cell.material.xyz) * (1.0f / 65535.0f), 0.0f, 1.0f);
    float3 outgoing = rho * (1.0f / metallumGiTransportPi) * direct;
    outgoing = all(isfinite(outgoing)) ? max(outgoing, float3(0.0f)) : float3(0.0f);
    bounce.write(half4(half3(outgoing), half(any(outgoing > float3(0.0f)) ? 1.0f : 0.0f)), position);
}

kernel void metallum_gi_transport_jacobi_sh_v1(
    constant MetallumGiTransportHeaderV1 &header [[buffer(0)]],
    device const MetallumGiTransportCellV1 *cells [[buffer(1)]],
    texture3d<half, access::read> bounce [[texture(0)]],
    texture3d<uint, access::read> geometry [[texture(1)]],
    texture3d<half, access::write> shRed [[texture(2)]],
    texture3d<half, access::write> shGreen [[texture(3)]],
    texture3d<half, access::write> shBlue [[texture(4)]],
    texture3d<half, access::write> confidence [[texture(5)]],
    uint3 position [[thread_position_in_grid]]
) {
    if (any(position >= uint3(metallumGiTransportEdge))
            || header.cellCount != metallumGiTransportCellCount
            || header.iterationCount != 1u || header.maximumDistance != 8u
            || !(header.formWeightNormalization > 0.0f)) {
        return;
    }

    uint receiverIndex = metallum_gi_transport_index(position);
    MetallumGiTransportCellV1 receiverCell = cells[receiverIndex];
    if (!metallum_gi_transport_surface_is_valid(receiverCell)
            || geometry.read(position).r != metallumGiTransportValidityContent) {
        shRed.write(half4(0.0h), position);
        shGreen.write(half4(0.0h), position);
        shBlue.write(half4(0.0h), position);
        confidence.write(half4(0.0h), position);
        return;
    }

    float3 coefficient0 = float3(0.0f);
    float3 coefficientX = float3(0.0f);
    float3 coefficientY = float3(0.0f);
    float3 coefficientZ = float3(0.0f);
    float knownWeight = 0.0f;
    int3 receiver = int3(position);

    // Fixed z/y/x/distance loop order is part of the deterministic ABI.
    for (int directionZ = -1; directionZ <= 1; ++directionZ) {
        for (int directionY = -1; directionY <= 1; ++directionY) {
            for (int directionX = -1; directionX <= 1; ++directionX) {
                int3 direction = int3(directionX, directionY, directionZ);
                if (all(direction == int3(0))) continue;
                float confidenceDirectionWeight = rsqrt(float(directionX * directionX
                    + directionY * directionY + directionZ * directionZ));
                float formDirectionWeight = metallum_gi_transport_direction_solid_angle(direction);
                float3 omega = normalize(float3(direction));
                for (uint distance = 1u; distance <= header.maximumDistance; ++distance) {
                    float formWeight = formDirectionWeight
                        / (float(distance * distance) * header.formWeightNormalization);
                    float confidenceWeight = confidenceDirectionWeight
                        / (float(distance * distance)
                        * metallumGiTransportConfidenceWeightNormalization);
                    int3 sourcePosition = receiver + direction * int(distance);
                    if (!metallum_gi_transport_inside(sourcePosition)) continue;

                    uint endpointState = geometry.read(uint3(sourcePosition)).r;
                    if (endpointState == metallumGiTransportValidityUnknown
                            || endpointState == metallumGiTransportValidityFallback) {
                        continue;
                    }
                    MetallumGiTransportPathStatus path = metallum_gi_transport_path_status(
                        geometry, receiver, direction, distance);
                    if (path == MetallumGiTransportPathUnknown) continue;
                    knownWeight += confidenceWeight;
                    if (path == MetallumGiTransportPathOccluded
                            || endpointState != metallumGiTransportValidityContent) {
                        continue;
                    }

                    uint sourceIndex = metallum_gi_transport_index(uint3(sourcePosition));
                    MetallumGiTransportCellV1 sourceCell = cells[sourceIndex];
                    if (!metallum_gi_transport_surface_is_valid(sourceCell)) continue;
                    float sourceSupport = metallum_gi_transport_source_face_support(
                        sourceCell, -float3(direction));
                    if (!(sourceSupport > 0.0f)) continue;
                    float3 outgoing = max(float3(bounce.read(uint3(sourcePosition)).rgb), float3(0.0f));
                    if (!all(isfinite(outgoing)) || !any(outgoing > float3(0.0f))) continue;
                    float formFactor = metallumGiTransportPi * formWeight * sourceSupport;
                    float3 transfer = outgoing * formFactor;
                    coefficient0 += transfer;
                    coefficientX += transfer * omega.x;
                    coefficientY += transfer * omega.y;
                    coefficientZ += transfer * omega.z;
                }
            }
        }
    }

    bool finite = all(isfinite(coefficient0)) && all(isfinite(coefficientX))
        && all(isfinite(coefficientY)) && all(isfinite(coefficientZ));
    if (!finite) {
        coefficient0 = coefficientX = coefficientY = coefficientZ = float3(0.0f);
        knownWeight = 0.0f;
    }
    coefficient0 = max(coefficient0, float3(0.0f));
    shRed.write(metallum_gi_transport_quantize_sh(
        coefficient0.r, float3(coefficientX.r, coefficientY.r, coefficientZ.r),
        header.fp16RelativeTolerance), position);
    shGreen.write(metallum_gi_transport_quantize_sh(
        coefficient0.g, float3(coefficientX.g, coefficientY.g, coefficientZ.g),
        header.fp16RelativeTolerance), position);
    shBlue.write(metallum_gi_transport_quantize_sh(
        coefficient0.b, float3(coefficientX.b, coefficientY.b, coefficientZ.b),
        header.fp16RelativeTolerance), position);
    confidence.write(half4(half(clamp(knownWeight, 0.0f, 1.0f)), 0.0h, 0.0h, 0.0h), position);
}

constant uint metallumGiLiveBrickEdge = 8u;

struct MetallumGiLiveRemapParamsV1 {
    uint cascadeIndex;
    int deltaX;
    int deltaY;
    int deltaZ;
    ulong previousReceiverMask;
    ulong requiredMask;
};

static_assert(sizeof(MetallumGiLiveRemapParamsV1) == 32,
    "G6 internal remap params must remain 32 bytes");

inline uint metallum_gi_live_nth_brick(ulong mask, uint ordinal) {
    for (uint brick = 0u; brick < 64u; ++brick) {
        if ((mask & (1ul << brick)) == 0ul) continue;
        if (ordinal == 0u) return brick;
        --ordinal;
    }
    return 64u;
}

inline uint3 metallum_gi_live_brick_position(
    ulong mask, uint3 dispatchedPosition
) {
    uint brick = metallum_gi_live_nth_brick(
        mask, dispatchedPosition.z / metallumGiLiveBrickEdge);
    uint brickX = brick & 3u;
    uint brickY = (brick >> 2u) & 3u;
    uint brickZ = brick >> 4u;
    return uint3(brickX * metallumGiLiveBrickEdge + dispatchedPosition.x,
        brickY * metallumGiLiveBrickEdge + dispatchedPosition.y,
        brickZ * metallumGiLiveBrickEdge
            + dispatchedPosition.z % metallumGiLiveBrickEdge);
}

// First-submit invalidation: every incompatible receiver cell becomes physical
// zero before the same command buffer can reach a render draw.
kernel void metallum_gi_live_clear_bricks_v1(
    constant MetallumGiTransportHeaderV1 &header [[buffer(0)]],
    constant uint &cascadeIndex [[buffer(1)]],
    texture3d<half, access::write> shRed [[texture(0)]],
    texture3d<half, access::write> shGreen [[texture(1)]],
    texture3d<half, access::write> shBlue [[texture(2)]],
    texture3d<half, access::write> confidence [[texture(3)]],
    uint3 dispatchedPosition [[thread_position_in_grid]]
) {
    if (cascadeIndex >= 3u || header.reserved1 == 0ul
            || dispatchedPosition.x >= metallumGiLiveBrickEdge
            || dispatchedPosition.y >= metallumGiLiveBrickEdge) return;
    uint3 position = metallum_gi_live_brick_position(
        header.reserved1, dispatchedPosition);
    if (any(position >= uint3(metallumGiTransportEdge))) return;
    uint3 atlasPosition = uint3(position.x, position.y,
        position.z + cascadeIndex * metallumGiTransportEdge);
    shRed.write(half4(0.0h), atlasPosition);
    shGreen.write(half4(0.0h), atlasPosition);
    shBlue.write(half4(0.0h), atlasPosition);
    confidence.write(half4(0.0h), atlasPosition);
}

// Scrolls always read the old atlas and write a distinct one-cascade scratch.
// This removes the intra-dispatch overlap race for one-cell clipmap motion.
kernel void metallum_gi_live_remap_v1(
    constant MetallumGiLiveRemapParamsV1 &params [[buffer(0)]],
    texture3d<half, access::read> oldRed [[texture(0)]],
    texture3d<half, access::read> oldGreen [[texture(1)]],
    texture3d<half, access::read> oldBlue [[texture(2)]],
    texture3d<half, access::read> oldConfidence [[texture(3)]],
    texture3d<half, access::write> scratchRed [[texture(4)]],
    texture3d<half, access::write> scratchGreen [[texture(5)]],
    texture3d<half, access::write> scratchBlue [[texture(6)]],
    texture3d<half, access::write> scratchConfidence [[texture(7)]],
    uint3 destination [[thread_position_in_grid]]
) {
    if (params.cascadeIndex >= 3u
            || any(destination >= uint3(metallumGiTransportEdge))) return;
    int3 source = int3(destination)
        + int3(params.deltaX, params.deltaY, params.deltaZ);
    bool sourceInside = metallum_gi_transport_inside(source);
    uint sourceBrick = sourceInside
        ? ((uint(source.z) >> 3u) << 4u) | ((uint(source.y) >> 3u) << 2u)
            | (uint(source.x) >> 3u)
        : 64u;
    // Visual history remains sampleable while exact/SLA publication is rebuilt separately.
    // Copy every last-proven receiver texel in the spatial overlap, including dirty successors.
    bool retain = sourceInside
        && (params.previousReceiverMask & (1ul << sourceBrick)) != 0ul;
    if (!retain) {
        scratchRed.write(half4(0.0h), destination);
        scratchGreen.write(half4(0.0h), destination);
        scratchBlue.write(half4(0.0h), destination);
        scratchConfidence.write(half4(0.0h), destination);
        return;
    }
    uint3 atlasSource = uint3(uint(source.x), uint(source.y),
        uint(source.z) + params.cascadeIndex * metallumGiTransportEdge);
    scratchRed.write(oldRed.read(atlasSource), destination);
    scratchGreen.write(oldGreen.read(atlasSource), destination);
    scratchBlue.write(oldBlue.read(atlasSource), destination);
    scratchConfidence.write(oldConfidence.read(atlasSource), destination);
}

kernel void metallum_gi_live_publish_remap_v1(
    constant MetallumGiLiveRemapParamsV1 &params [[buffer(0)]],
    texture3d<half, access::read> scratchRed [[texture(0)]],
    texture3d<half, access::read> scratchGreen [[texture(1)]],
    texture3d<half, access::read> scratchBlue [[texture(2)]],
    texture3d<half, access::read> scratchConfidence [[texture(3)]],
    texture3d<half, access::write> shRed [[texture(4)]],
    texture3d<half, access::write> shGreen [[texture(5)]],
    texture3d<half, access::write> shBlue [[texture(6)]],
    texture3d<half, access::write> confidence [[texture(7)]],
    uint3 position [[thread_position_in_grid]]
) {
    if (params.cascadeIndex >= 3u
            || any(position >= uint3(metallumGiTransportEdge))) return;
    uint3 atlasPosition = uint3(position.x, position.y,
        position.z + params.cascadeIndex * metallumGiTransportEdge);
    shRed.write(scratchRed.read(position), atlasPosition);
    shGreen.write(scratchGreen.read(position), atlasPosition);
    shBlue.write(scratchBlue.read(position), atlasPosition);
    confidence.write(scratchConfidence.read(position), atlasPosition);
}

// Live G6 retains the accepted G4 one-bounce/L1-SH equation but dispatches
// only scheduler-owned receiver bricks. Endpoint outgoing radiance is computed
// inline from E_direct, eliminating a whole-cascade shared bounce pass.
kernel void metallum_gi_live_jacobi_sh_v1(
    constant MetallumGiTransportHeaderV1 &header [[buffer(0)]],
    device const MetallumGiTransportCellV1 *cells [[buffer(1)]],
    constant uint &cascadeIndex [[buffer(2)]],
    texture3d<half, access::read> directIrradiance [[texture(0)]],
    texture3d<uint, access::read> geometry [[texture(1)]],
    texture3d<half, access::write> shRed [[texture(2)]],
    texture3d<half, access::write> shGreen [[texture(3)]],
    texture3d<half, access::write> shBlue [[texture(4)]],
    texture3d<half, access::write> confidence [[texture(5)]],
    uint3 dispatchedPosition [[thread_position_in_grid]]
) {
    if (cascadeIndex >= 3u || header.reserved0 == 0ul
            || dispatchedPosition.x >= metallumGiLiveBrickEdge
            || dispatchedPosition.y >= metallumGiLiveBrickEdge
            || header.cellCount != metallumGiTransportCellCount
            || header.iterationCount != 1u || header.maximumDistance != 8u
            || !(header.formWeightNormalization > 0.0f)) {
        return;
    }
    uint3 position = metallum_gi_live_brick_position(
        header.reserved0, dispatchedPosition);
    if (any(position >= uint3(metallumGiTransportEdge))) return;

    uint receiverIndex = metallum_gi_transport_index(position);
    MetallumGiTransportCellV1 receiverCell = cells[receiverIndex];
    uint3 atlasPosition = uint3(position.x, position.y,
        position.z + cascadeIndex * metallumGiTransportEdge);
    if (!metallum_gi_transport_surface_is_valid(receiverCell)
            || geometry.read(position).r != metallumGiTransportValidityContent) {
        shRed.write(half4(0.0h), atlasPosition);
        shGreen.write(half4(0.0h), atlasPosition);
        shBlue.write(half4(0.0h), atlasPosition);
        confidence.write(half4(0.0h), atlasPosition);
        return;
    }

    float3 coefficient0 = float3(0.0f);
    float3 coefficientX = float3(0.0f);
    float3 coefficientY = float3(0.0f);
    float3 coefficientZ = float3(0.0f);
    float knownWeight = 0.0f;
    int3 receiver = int3(position);
    for (int directionZ = -1; directionZ <= 1; ++directionZ) {
        for (int directionY = -1; directionY <= 1; ++directionY) {
            for (int directionX = -1; directionX <= 1; ++directionX) {
                int3 direction = int3(directionX, directionY, directionZ);
                if (all(direction == int3(0))) continue;
                float confidenceDirectionWeight = rsqrt(float(directionX * directionX
                    + directionY * directionY + directionZ * directionZ));
                float formDirectionWeight = metallum_gi_transport_direction_solid_angle(direction);
                float3 omega = normalize(float3(direction));
                for (uint distance = 1u; distance <= header.maximumDistance; ++distance) {
                    float formWeight = formDirectionWeight
                        / (float(distance * distance) * header.formWeightNormalization);
                    float confidenceWeight = confidenceDirectionWeight
                        / (float(distance * distance)
                        * metallumGiTransportConfidenceWeightNormalization);
                    int3 sourcePosition = receiver + direction * int(distance);
                    if (!metallum_gi_transport_inside(sourcePosition)) continue;
                    uint endpointState = geometry.read(uint3(sourcePosition)).r;
                    if (endpointState == metallumGiTransportValidityUnknown
                            || endpointState == metallumGiTransportValidityFallback) continue;
                    MetallumGiTransportPathStatus path = metallum_gi_transport_path_status(
                        geometry, receiver, direction, distance);
                    if (path == MetallumGiTransportPathUnknown) continue;
                    knownWeight += confidenceWeight;
                    if (path == MetallumGiTransportPathOccluded
                            || endpointState != metallumGiTransportValidityContent) continue;
                    uint sourceIndex = metallum_gi_transport_index(uint3(sourcePosition));
                    MetallumGiTransportCellV1 sourceCell = cells[sourceIndex];
                    if (!metallum_gi_transport_surface_is_valid(sourceCell)) continue;
                    float sourceSupport = metallum_gi_transport_source_face_support(
                        sourceCell, -float3(direction));
                    if (!(sourceSupport > 0.0f)) continue;
                    float4 directSample = float4(
                        directIrradiance.read(uint3(sourcePosition)));
                    float3 direct = max(directSample.rgb, float3(0.0f));
                    if (!all(isfinite(direct)) || !(directSample.a > 0.0f)) continue;
                    float3 rho = clamp(float3(sourceCell.material.xyz)
                        * (1.0f / 65535.0f), 0.0f, 1.0f);
                    float3 outgoing = rho * (1.0f / metallumGiTransportPi) * direct;
                    outgoing = all(isfinite(outgoing))
                        ? max(outgoing, float3(0.0f)) : float3(0.0f);
                    if (!all(isfinite(outgoing)) || !any(outgoing > float3(0.0f))) continue;
                    float formFactor = metallumGiTransportPi * formWeight * sourceSupport;
                    float3 transfer = outgoing * formFactor;
                    coefficient0 += transfer;
                    coefficientX += transfer * omega.x;
                    coefficientY += transfer * omega.y;
                    coefficientZ += transfer * omega.z;
                }
            }
        }
    }

    bool finite = all(isfinite(coefficient0)) && all(isfinite(coefficientX))
        && all(isfinite(coefficientY)) && all(isfinite(coefficientZ));
    if (!finite) {
        coefficient0 = coefficientX = coefficientY = coefficientZ = float3(0.0f);
        knownWeight = 0.0f;
    }
    coefficient0 = max(coefficient0, float3(0.0f));
    shRed.write(metallum_gi_transport_quantize_sh(
        coefficient0.r, float3(coefficientX.r, coefficientY.r, coefficientZ.r),
        header.fp16RelativeTolerance), atlasPosition);
    shGreen.write(metallum_gi_transport_quantize_sh(
        coefficient0.g, float3(coefficientX.g, coefficientY.g, coefficientZ.g),
        header.fp16RelativeTolerance), atlasPosition);
    shBlue.write(metallum_gi_transport_quantize_sh(
        coefficient0.b, float3(coefficientX.b, coefficientY.b, coefficientZ.b),
        header.fp16RelativeTolerance), atlasPosition);
    // R is known-path reliability. G is binary valid-surface coverage. The
    // receiver filters both alongside SH and divides by filtered coverage so
    // neighbouring AIR zeros cannot attenuate a valid surface sample.
    confidence.write(half4(half(clamp(knownWeight, 0.0f, 1.0f)),
        1.0h, 0.0h, 0.0h), atlasPosition);
}
