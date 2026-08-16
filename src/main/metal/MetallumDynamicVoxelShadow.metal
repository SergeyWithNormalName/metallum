#include <metal_stdlib>

using namespace metal;

// Dynamic L6 pages deliberately use the resident-atlas payload unchanged:
// six faces, four ordered distance/RGB-transmittance hits per ray, eight bytes per hit.
constant uint METALLUM_DYNAMIC_SHADOW_LAYERS_V1 = 4u;
constant uint METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1 = 32u;

struct MetallumDynamicShadowRequestV1 {
    ulong stableId;
    ulong atlasOffset;
    uint levelIndex;
    uint edge;
    uint maxSteps;
    uint reserved0;
    int sourceBlockX;
    int sourceBlockY;
    int sourceBlockZ;
    float sourceFractionX;
    float sourceFractionY;
    float sourceFractionZ;
    float radius;
};

struct MetallumDynamicShadowLevelV1 {
    uint logicalEdge;
    uint subdivision;
    uint brickDimension;
    uint reserved0;
};

struct MetallumDynamicShadowHitV1 {
    float distance;
    uint packedRgb;
};

static_assert(sizeof(MetallumDynamicShadowRequestV1) == 64,
    "Dynamic shadow request ABI must remain 64 bytes");
static_assert(sizeof(MetallumDynamicShadowHitV1) == 8,
    "Dynamic shadow hit ABI must remain eight bytes");

inline int metallum_floor_div(int value, int divisor) {
    const int quotient = value / divisor;
    const int remainder = value % divisor;
    return remainder < 0 ? quotient - 1 : quotient;
}

inline int metallum_floor_mod(int value, int divisor) {
    const int remainder = value % divisor;
    return remainder < 0 ? remainder + divisor : remainder;
}

inline float3 metallum_cube_direction(uint face, uint x, uint y, uint edge) {
    const float u = 2.0f * (float(x) + 0.5f) / float(edge) - 1.0f;
    const float v = 2.0f * (float(y) + 0.5f) / float(edge) - 1.0f;
    switch (face) {
        case 0u: return normalize(float3(1.0f, -v, -u));
        case 1u: return normalize(float3(-1.0f, -v, u));
        case 2u: return normalize(float3(u, 1.0f, v));
        case 3u: return normalize(float3(u, -1.0f, -v));
        case 4u: return normalize(float3(u, -v, 1.0f));
        default: return normalize(float3(-u, -v, -1.0f));
    }
}

inline void metallum_store_visible_page(
    device uchar *atlas,
    ulong atlasOffset,
    uint ray
) {
    device MetallumDynamicShadowHitV1 *entries =
        reinterpret_cast<device MetallumDynamicShadowHitV1 *>(atlas + atlasOffset);
    const uint base = ray * METALLUM_DYNAMIC_SHADOW_LAYERS_V1;
    for (uint layer = 0u; layer < METALLUM_DYNAMIC_SHADOW_LAYERS_V1; ++layer) {
        MetallumDynamicShadowHitV1 visible;
        visible.distance = INFINITY;
        visible.packedRgb = 0xffffffffu;
        entries[base + layer] = visible;
    }
}

inline float3 metallum_chromatic_filter(uint paletteId) {
    switch (paletteId & 15u) {
        case 0u: return float3(1.000f, 1.000f, 1.000f);
        case 1u: return float3(1.000f, 0.250f, 0.030f);
        case 2u: return float3(1.000f, 0.080f, 0.680f);
        case 3u: return float3(0.100f, 0.500f, 1.000f);
        case 4u: return float3(1.000f, 0.850f, 0.050f);
        case 5u: return float3(0.250f, 1.000f, 0.040f);
        case 6u: return float3(1.000f, 0.250f, 0.400f);
        case 7u: return float3(0.230f, 0.250f, 0.250f);
        case 8u: return float3(0.600f, 0.600f, 0.580f);
        case 9u: return float3(0.030f, 0.650f, 0.650f);
        case 10u: return float3(0.320f, 0.040f, 0.600f);
        case 11u: return float3(0.040f, 0.070f, 0.650f);
        case 12u: return float3(0.200f, 0.050f, 0.015f);
        case 13u: return float3(0.080f, 0.350f, 0.010f);
        case 14u: return float3(1.000f, 0.040f, 0.025f);
        default: return float3(0.005f, 0.005f, 0.006f);
    }
}

inline uint metallum_pack_rgb_unorm8(float3 value) {
    const uint red = uint(round(clamp(value.x, 0.0f, 1.0f) * 255.0f));
    const uint green = uint(round(clamp(value.y, 0.0f, 1.0f) * 255.0f));
    const uint blue = uint(round(clamp(value.z, 0.0f, 1.0f) * 255.0f));
    return 0xff000000u | red | (green << 8u) | (blue << 16u);
}

struct MetallumShapeBoxV1 {
    float minX;
    float minY;
    float minZ;
    float maxX;
    float maxY;
    float maxZ;
    float pad0;
    float pad1;
};

struct MetallumShapeProxyEntryV1 {
    uint boxOffset;
    uint boxCount;
};

struct MetallumShapeProxyHeaderV1 {
    uint proxyCount;
    uint totalBoxCount;
    uint proxyTableBytes;
    uint boxTableBytes;
};

inline float metallum_intersect_box(
    float3 localStart,
    float3 localDelta,
    MetallumShapeBoxV1 box
) {
    float uMin = 0.0f;
    float uMax = 1.0f;

    // X axis
    if (abs(localDelta.x) > 1.0e-7f) {
        const float inv = 1.0f / localDelta.x;
        const float u1 = (box.minX - localStart.x) * inv;
        const float u2 = (box.maxX - localStart.x) * inv;
        const float enter = min(u1, u2);
        const float exit = max(u1, u2);
        uMin = max(uMin, enter);
        uMax = min(uMax, exit);
        if (uMin > uMax) return -1.0f;
    } else if (localStart.x < box.minX - 1.0e-5f || localStart.x > box.maxX + 1.0e-5f) {
        return -1.0f;
    }

    // Y axis
    if (abs(localDelta.y) > 1.0e-7f) {
        const float inv = 1.0f / localDelta.y;
        const float u1 = (box.minY - localStart.y) * inv;
        const float u2 = (box.maxY - localStart.y) * inv;
        const float enter = min(u1, u2);
        const float exit = max(u1, u2);
        uMin = max(uMin, enter);
        uMax = min(uMax, exit);
        if (uMin > uMax) return -1.0f;
    } else if (localStart.y < box.minY - 1.0e-5f || localStart.y > box.maxY + 1.0e-5f) {
        return -1.0f;
    }

    // Z axis
    if (abs(localDelta.z) > 1.0e-7f) {
        const float inv = 1.0f / localDelta.z;
        const float u1 = (box.minZ - localStart.z) * inv;
        const float u2 = (box.maxZ - localStart.z) * inv;
        const float enter = min(u1, u2);
        const float exit = max(u1, u2);
        uMin = max(uMin, enter);
        uMax = min(uMax, exit);
        if (uMin > uMax) return -1.0f;
    } else if (localStart.z < box.minZ - 1.0e-5f || localStart.z > box.maxZ + 1.0e-5f) {
        return -1.0f;
    }

    return uMin;
}

inline float metallum_intersect_proxy(
    float3 localStart,
    float3 localDelta,
    uint proxyId,
    device const uchar *shapeProxyTable
) {
    if (proxyId == 0u || shapeProxyTable == nullptr) {
        return -1.0f;
    }
    device const MetallumShapeProxyHeaderV1 *header =
        reinterpret_cast<device const MetallumShapeProxyHeaderV1 *>(shapeProxyTable);
    if (proxyId >= header->proxyCount) {
        return -1.0f;
    }
    device const MetallumShapeProxyEntryV1 *entries =
        reinterpret_cast<device const MetallumShapeProxyEntryV1 *>(shapeProxyTable + 16u);
    const MetallumShapeProxyEntryV1 entry = entries[proxyId];
    if (entry.boxCount == 0u) {
        return -1.0f;
    }
    device const MetallumShapeBoxV1 *boxes =
        reinterpret_cast<device const MetallumShapeBoxV1 *>(shapeProxyTable + 16u + header->proxyTableBytes);

    float bestU = INFINITY;
    for (uint i = 0u; i < entry.boxCount; ++i) {
        const float u = metallum_intersect_box(localStart, localDelta, boxes[entry.boxOffset + i]);
        if (u >= 0.0f && u < bestU) {
            bestU = u;
        }
    }
    return isinf(bestU) ? -1.0f : bestU;
}

inline bool metallum_sample_voxel(
    device const uint *occupancy,
    device const uchar *optical,
    device const uchar *chromatic,
    device const uint4 *metadata,
    device const ushort *shapeProxyIds,
    uint logicalEdge,
    uint subdivision,
    uint brickDimension,
    int cellX,
    int cellY,
    int cellZ,
    thread bool &occupied,
    thread float &transmittance,
    thread float3 &filter,
    thread uint &shapeProxyId
) {
    occupied = false;
    transmittance = 1.0f;
    filter = float3(1.0f);
    shapeProxyId = 0u;
    const int scale = int(subdivision);
    const int blockX = metallum_floor_div(cellX, scale);
    const int blockY = metallum_floor_div(cellY, scale);
    const int blockZ = metallum_floor_div(cellZ, scale);
    const int brickBlockEdge = int(METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1 / subdivision);
    const int logicalBrickX = metallum_floor_div(blockX, brickBlockEdge);
    const int logicalBrickY = metallum_floor_div(blockY, brickBlockEdge);
    const int logicalBrickZ = metallum_floor_div(blockZ, brickBlockEdge);
    const int dimension = int(brickDimension);
    const int physicalBrickX = metallum_floor_mod(logicalBrickX, dimension);
    const int physicalBrickY = metallum_floor_mod(logicalBrickY, dimension);
    const int physicalBrickZ = metallum_floor_mod(logicalBrickZ, dimension);
    const uint metadataIndex = uint((physicalBrickZ * dimension + physicalBrickY) * dimension + physicalBrickX);
    const uint4 tag = metadata[metadataIndex];
    if (as_type<int>(tag.x) != logicalBrickX || as_type<int>(tag.y) != logicalBrickY
        || as_type<int>(tag.z) != logicalBrickZ || tag.w == 0u) {
        return false;
    }

    const int localCellX = metallum_floor_mod(cellX, int(METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1));
    const int localCellY = metallum_floor_mod(cellY, int(METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1));
    const int localCellZ = metallum_floor_mod(cellZ, int(METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1));
    const int physicalCellX = physicalBrickX * int(METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1) + localCellX;
    const int physicalCellY = physicalBrickY * int(METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1) + localCellY;
    const int physicalCellZ = physicalBrickZ * int(METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1) + localCellZ;
    const uint word = occupancy[(uint(physicalCellZ) * logicalEdge + uint(physicalCellY))
        * (logicalEdge >> 5u) + (uint(physicalCellX) >> 5u)];
    occupied = (word & (1u << uint(localCellX))) != 0u;
    if (!occupied) {
        return true;
    }

    const int baseEdge = int(METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1 / subdivision);
    const int baseDimension = int(logicalEdge / subdivision);
    const int localBlockX = metallum_floor_mod(blockX, brickBlockEdge);
    const int localBlockY = metallum_floor_mod(blockY, brickBlockEdge);
    const int localBlockZ = metallum_floor_mod(blockZ, brickBlockEdge);
    const int physicalBlockX = physicalBrickX * baseEdge + localBlockX;
    const int physicalBlockY = physicalBrickY * baseEdge + localBlockY;
    const int physicalBlockZ = physicalBrickZ * baseEdge + localBlockZ;
    const uint opticalIndex = uint((physicalBlockZ * baseDimension + physicalBlockY)
        * baseDimension + physicalBlockX);
    const uint packed = uint(optical[opticalIndex]);
    // AIR in an occupied cell is an inconsistent L5 patch: fail this ray rather than
    // treating it as transparent geometry.
    if ((packed >> 5u) == 0u) {
        return false;
    }
    transmittance = float(packed & 31u) / 31.0f;
    const uint packedChromatic = uint(chromatic[opticalIndex >> 1u]);
    const uint paletteId = (packedChromatic >> ((opticalIndex & 1u) * 4u)) & 15u;
    filter = metallum_chromatic_filter(paletteId);
    if (shapeProxyIds != nullptr) {
        shapeProxyId = uint(shapeProxyIds[opticalIndex]);
    }
    return isfinite(transmittance) && all(isfinite(filter));
}

kernel void metallum_dynamic_voxel_shadow_v1(
    device const uint *occupancy0 [[buffer(0)]],
    device const uint *occupancy1 [[buffer(1)]],
    device const uint *occupancy2 [[buffer(2)]],
    device const uchar *optical0 [[buffer(3)]],
    device const uchar *optical1 [[buffer(4)]],
    device const uchar *optical2 [[buffer(5)]],
    device const uchar *chromatic0 [[buffer(6)]],
    device const uchar *chromatic1 [[buffer(7)]],
    device const uchar *chromatic2 [[buffer(8)]],
    device const uint4 *metadata0 [[buffer(9)]],
    device const uint4 *metadata1 [[buffer(10)]],
    device const uint4 *metadata2 [[buffer(11)]],
    device uchar *atlas [[buffer(12)]],
    device const MetallumDynamicShadowRequestV1 *requests [[buffer(13)]],
    device const MetallumDynamicShadowLevelV1 *levels [[buffer(14)]],
    device const ushort *shapeProxyIds0 [[buffer(15)]],
    device const ushort *shapeProxyIds1 [[buffer(16)]],
    device const ushort *shapeProxyIds2 [[buffer(17)]],
    device const uchar *shapeProxyTable [[buffer(18)]],
    uint2 gid [[thread_position_in_grid]]
) {
    const uint requestIndex = gid.y;
    const uint ray = gid.x;
    const MetallumDynamicShadowRequestV1 request = requests[requestIndex];
    const MetallumDynamicShadowLevelV1 level = levels[request.levelIndex];
    const uint rayCount = 6u * request.edge * request.edge;
    if (ray >= rayCount) { return; }
    metallum_store_visible_page(atlas, request.atlasOffset, ray);
    if ((request.edge != 16u && request.edge != 32u)
        || (request.maxSteps != 32u && request.maxSteps != 96u)
        || !(request.radius > 0.0f) || !isfinite(request.radius)
        || request.sourceFractionX < 0.0f || request.sourceFractionX >= 1.0f
        || request.sourceFractionY < 0.0f || request.sourceFractionY >= 1.0f
        || request.sourceFractionZ < 0.0f || request.sourceFractionZ >= 1.0f) {
        return;
    }

    const uint face = ray / (request.edge * request.edge);
    const uint texel = ray % (request.edge * request.edge);
    const uint y = texel / request.edge;
    const uint x = texel % request.edge;
    const float3 direction = metallum_cube_direction(face, x, y, request.edge);
    const device uint *occupancy = request.levelIndex == 0u ? occupancy0
        : (request.levelIndex == 1u ? occupancy1 : occupancy2);
    const device uchar *optical = request.levelIndex == 0u ? optical0
        : (request.levelIndex == 1u ? optical1 : optical2);
    const device uchar *chromatic = request.levelIndex == 0u ? chromatic0
        : (request.levelIndex == 1u ? chromatic1 : chromatic2);
    const device uint4 *metadata = request.levelIndex == 0u ? metadata0
        : (request.levelIndex == 1u ? metadata1 : metadata2);
    const device ushort *shapeProxyIds = request.levelIndex == 0u ? shapeProxyIds0
        : (request.levelIndex == 1u ? shapeProxyIds1 : shapeProxyIds2);
    // All DDA arithmetic stays near the source cell. The absolute cell is reconstructed
    // with integers for metadata lookup, preserving sub-voxel source precision at ±30M.
    if (level.subdivision == 0u || level.logicalEdge == 0u
        || level.logicalEdge % METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1 != 0u
        || level.brickDimension != level.logicalEdge / METALLUM_DYNAMIC_SHADOW_LOGICAL_BRICK_EDGE_V1) {
        return;
    }
    const float voxelSize = 1.0f / float(level.subdivision);
    const float startDistance = min(voxelSize * 0.08f, request.radius * 0.02f);
    const float3 source = float3(
        request.sourceFractionX * float(level.subdivision),
        request.sourceFractionY * float(level.subdivision),
        request.sourceFractionZ * float(level.subdivision)
    );
    const float3 start = source + direction * (startDistance * float(level.subdivision));
    const float3 end = source + direction * (request.radius * float(level.subdivision));
    const float3 delta = end - start;
    if (!all(isfinite(delta))) { return; }

    int3 cell = int3(floor(start));
    const int3 endCell = int3(floor(nextafter(end, start)));
    const int3 step = int3(
        delta.x > 0.0f ? 1 : (delta.x < 0.0f ? -1 : 0),
        delta.y > 0.0f ? 1 : (delta.y < 0.0f ? -1 : 0),
        delta.z > 0.0f ? 1 : (delta.z < 0.0f ? -1 : 0)
    );
    const float infinity = INFINITY;
    const float3 tDelta = float3(
        step.x == 0 ? infinity : 1.0f / abs(delta.x),
        step.y == 0 ? infinity : 1.0f / abs(delta.y),
        step.z == 0 ? infinity : 1.0f / abs(delta.z)
    );
    const float3 boundary = float3(
        step.x > 0 ? cell.x + 1 : cell.x,
        step.y > 0 ? cell.y + 1 : cell.y,
        step.z > 0 ? cell.z + 1 : cell.z
    );
    float3 tMax = float3(
        step.x == 0 ? infinity : (boundary.x - start.x) / delta.x,
        step.y == 0 ? infinity : (boundary.y - start.y) / delta.y,
        step.z == 0 ? infinity : (boundary.z - start.z) / delta.z
    );
    if (tMax.x < 0.0f || !isfinite(tMax.x)) { tMax.x = infinity; }
    if (tMax.y < 0.0f || !isfinite(tMax.y)) { tMax.y = infinity; }
    if (tMax.z < 0.0f || !isfinite(tMax.z)) { tMax.z = infinity; }

    device MetallumDynamicShadowHitV1 *entries =
        reinterpret_cast<device MetallumDynamicShadowHitV1 *>(atlas + request.atlasOffset);
    const uint outputBase = ray * METALLUM_DYNAMIC_SHADOW_LAYERS_V1;
    float3 cumulativeVisibility = float3(1.0f);
    uint hitCount = 0u;
    int3 lastBlock = int3(INT_MIN);
    float entryT = 0.0f;
    for (uint iteration = 0u; iteration < request.maxSteps; ++iteration) {
        if (all(cell == endCell)) { return; }
        const int3 worldCell = int3(
            request.sourceBlockX * int(level.subdivision) + cell.x,
            request.sourceBlockY * int(level.subdivision) + cell.y,
            request.sourceBlockZ * int(level.subdivision) + cell.z
        );
        const int3 block = int3(
            metallum_floor_div(worldCell.x, int(level.subdivision)),
            metallum_floor_div(worldCell.y, int(level.subdivision)),
            metallum_floor_div(worldCell.z, int(level.subdivision))
        );
        bool occupied = false;
        float transmittance = 1.0f;
        float3 chromaticFilter = float3(1.0f);
        uint shapeProxyId = 0u;
        if (!metallum_sample_voxel(
                occupancy, optical, chromatic, metadata, shapeProxyIds, level.logicalEdge, level.subdivision,
                level.brickDimension, worldCell.x, worldCell.y, worldCell.z,
                occupied, transmittance, chromaticFilter, shapeProxyId
            )) {
            return;
        }
        // `stableId` is deliberately carried by the request for a later proxy-footprint
        // binding. Until that route is connected, the exact ordinary emitter block is
        // still skipped, matching AdvancedLight's non-compacted fallback.
        const bool emitterBlock = all(block == int3(
            request.sourceBlockX, request.sourceBlockY, request.sourceBlockZ
        ));
        if (!emitterBlock && occupied && any(block != lastBlock)) {
            bool hit = true;
            float effectiveHitT = entryT;
            if (shapeProxyId > 0u && shapeProxyTable != nullptr) {
                const float3 worldSource = float3(
                    float(request.sourceBlockX) + request.sourceFractionX,
                    float(request.sourceBlockY) + request.sourceFractionY,
                    float(request.sourceBlockZ) + request.sourceFractionZ
                );
                const float3 startWorld = worldSource + direction * startDistance;
                const float3 localStart = startWorld - float3(block);
                const float3 localDelta = direction * (request.radius - startDistance);
                const float uHit = metallum_intersect_proxy(localStart, localDelta, shapeProxyId, shapeProxyTable);
                if (uHit < 0.0f) {
                    hit = false;
                } else {
                    effectiveHitT = uHit;
                }
            }
            if (hit) {
                cumulativeVisibility *= transmittance * chromaticFilter;
                if (!all(isfinite(cumulativeVisibility))) { return; }
                const float hitDistance = max(0.0f,
                    startDistance + effectiveHitT * (request.radius - startDistance));
                const uint outputLayer = min(hitCount, METALLUM_DYNAMIC_SHADOW_LAYERS_V1 - 1u);
                MetallumDynamicShadowHitV1 hitRecord;
                hitRecord.distance = hitDistance;
                hitRecord.packedRgb = metallum_pack_rgb_unorm8(cumulativeVisibility);
                entries[outputBase + outputLayer] = hitRecord;
                hitCount = min(hitCount + 1u, METALLUM_DYNAMIC_SHADOW_LAYERS_V1);
                lastBlock = block;
                if (all(cumulativeVisibility <= float3(0.0f))) { return; }
            }
        }

        const float next = min(tMax.x, min(tMax.y, tMax.z));
        if (!isfinite(next) || next > 1.0f) { return; }
        const float tie = next + 1.0e-7f;
        if (tMax.x <= tie) { cell.x += step.x; tMax.x += tDelta.x; }
        if (tMax.y <= tie) { cell.y += step.y; tMax.y += tDelta.y; }
        if (tMax.z <= tie) { cell.z += step.z; tMax.z += tDelta.z; }
        entryT = next;
        if (all(cell == endCell)) { return; }
    }
}
