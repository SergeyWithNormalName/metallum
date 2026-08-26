#include <metal_stdlib>

using namespace metal;

constant uint METALLUM_LIGHTING_ABI_V1 = 1u;
constant uint METALLUM_CLUSTER_CAP_V1 = 256u;
constant uint METALLUM_MAX_LIGHT_CANDIDATES_V1 = 4096u;
constant uint METALLUM_CLUSTER_MEMBERSHIP_WORDS_V1 = 128u;
constant uint METALLUM_LEGACY_CLUSTER_MASK_WORDS_V1 = 8u;
constant uint METALLUM_PREFIX_BLOCK_SIZE_V1 = 256u;
constant uint METALLUM_OCCUPANCY_BIN_COUNT_V1 = 32u;
constant uint METALLUM_LIGHT_ADMISSION_ACCEPTED_V1 = 1u;

struct MetallumGpuLightV1 {
    float4 positionRadius;
    float4 linearColorIntensity;
    uint4 metadata;
};

struct MetallumClusterHeaderV1 {
    uint offset;
    uint count;
};

struct MetallumClusterScratchV1 {
    atomic_uint membership[METALLUM_CLUSTER_MEMBERSHIP_WORDS_V1];
};

struct MetallumLightingParamsV1 {
    float4x4 viewRotation;
    float4x4 projection;
    uint4 gridAndLightCount;
    uint4 extentAndClusterCap;
    float4 depth;
    uint4 frameIdAndGeneration;
    uint4 capacitiesAndFlags;
    uint4 reserved0;
    uint4 reserved1;
    uint4 reserved2;
};

struct MetallumClusterStatisticsV1 {
    atomic_uint counters[32];
    atomic_uint occupancyBins[METALLUM_OCCUPANCY_BIN_COUNT_V1];
};

// Temporarily aliases the beginning of the compact-index buffer between prefix and fill.
// One record replaces hundreds of contended global atomics with one block-local summary.
struct MetallumClusterBlockStatisticsV1 {
    uint requestedIndices;
    uint overflowClusters;
    uint perClusterDrops;
    uint emptyClusters;
    uint maximumOccupancy;
    uint reserved[3];
    uint occupancyBins[METALLUM_OCCUPANCY_BIN_COUNT_V1];
};

struct MetallumClusterBoundsV1 {
    uint3 lower;
    uint3 upper;
    bool valid;
};

enum MetallumClusterCounterV1 : uint {
    MetallumCounterAbiVersion = 0,
    MetallumCounterLightCount = 1,
    MetallumCounterClusterCount = 2,
    MetallumCounterAcceptedIndices = 3,
    MetallumCounterRequestedIndices = 4,
    MetallumCounterOverflowClusters = 5,
    MetallumCounterPerClusterDrops = 6,
    MetallumCounterIndexCapacityDrops = 7,
    MetallumCounterAdmissionRejectedLights = 8,
    MetallumCounterRingSlot = 9,
    MetallumCounterGenerationLow = 10,
    MetallumCounterGenerationHigh = 11,
    MetallumCounterFrameLow = 12,
    MetallumCounterFrameHigh = 13,
    MetallumCounterEmptyClusters = 14,
    MetallumCounterMaximumOccupancy = 15
};

static_assert(sizeof(MetallumGpuLightV1) == 48, "GpuLight ABI must stay 48 bytes");
static_assert(sizeof(MetallumClusterHeaderV1) == 8, "Cluster header ABI must stay 8 bytes");
static_assert(sizeof(MetallumClusterScratchV1) == 512, "Cluster scratch ABI must stay 512 bytes");
static_assert(sizeof(MetallumClusterBlockStatisticsV1) == 160,
    "Block statistics scratch ABI must stay 160 bytes");
static_assert(sizeof(MetallumLightingParamsV1) == 256, "Lighting params ABI must stay 256 bytes");
static_assert(sizeof(MetallumClusterStatisticsV1) == 256, "Lighting statistics ABI must stay 256 bytes");
static_assert(__builtin_offsetof(MetallumLightingParamsV1, viewRotation) == 0, "view offset ABI");
static_assert(__builtin_offsetof(MetallumLightingParamsV1, projection) == 64, "projection offset ABI");
static_assert(__builtin_offsetof(MetallumLightingParamsV1, gridAndLightCount) == 128, "grid offset ABI");
static_assert(__builtin_offsetof(MetallumLightingParamsV1, extentAndClusterCap) == 144, "extent offset ABI");
static_assert(__builtin_offsetof(MetallumLightingParamsV1, depth) == 160, "depth offset ABI");
static_assert(__builtin_offsetof(MetallumLightingParamsV1, frameIdAndGeneration) == 176, "frame offset ABI");
static_assert(__builtin_offsetof(MetallumLightingParamsV1, capacitiesAndFlags) == 192, "capacity offset ABI");
static_assert(__builtin_offsetof(MetallumLightingParamsV1, reserved0) == 208, "reserved0 offset ABI");

inline uint metallum_depth_slice(float depth, constant MetallumLightingParamsV1& params) {
    const float nearPlane = params.depth.x;
    const uint slices = params.gridAndLightCount.z;
    const float slice = floor(clamp(
        log2(max(depth, nearPlane)) * params.depth.z + params.depth.w,
        0.0f,
        float(slices - 1u)
    ));
    return uint(slice);
}

inline float4 metallum_matrix_row(const float4x4 matrix, uint row) {
    return float4(matrix[0][row], matrix[1][row], matrix[2][row], matrix[3][row]);
}

inline bool metallum_sphere_strictly_outside_plane(
    const float3 center,
    const float radius,
    const float4 plane
) {
    // Invalid data must retain the coarse membership. This check runs after upload
    // validation, but remains fail-open for malformed projection math or float overflow.
    if (!(radius > 0.0f) || !isfinite(radius)
        || !all(isfinite(center)) || !all(isfinite(plane))) {
        return false;
    }
    const float normalSquared = dot(plane.xyz, plane.xyz);
    const float distance = dot(plane.xyz, center) + plane.w;
    const float radiusSquared = radius * radius;
    if (!(normalSquared > 0.0f) || !isfinite(normalSquared)
        || !isfinite(distance) || !isfinite(radiusSquared)) {
        return false;
    }
    const float distanceSquared = distance * distance;
    const float radiusNormalSquared = radiusSquared * normalSquared;
    if (!isfinite(distanceSquared) || !isfinite(radiusNormalSquared)) {
        return false;
    }
    // distance < -radius * |normal|, expressed without sqrt. Strict comparison keeps
    // tangent spheres in the cluster, so this can only reject an entirely outside sphere.
    return distance < 0.0f && distanceSquared > radiusNormalSquared;
}

inline bool metallum_sphere_strictly_outside_plane_wedge(
    const float3 center,
    const float radius,
    const float4 firstPlane,
    const float4 secondPlane
) {
    // A tile is contained in every adjacent pair of its inward side half-spaces. A
    // sphere which misses even that wider wedge cannot affect any fragment in the
    // tile. Keep malformed or ill-conditioned pairs fail-open: this is a refinement
    // of the already-conservative coarse bounds, never an authority on visibility.
    if (!(radius > 0.0f) || !isfinite(radius)
        || !all(isfinite(center)) || !all(isfinite(firstPlane))
        || !all(isfinite(secondPlane))) {
        return false;
    }
    const float firstNormalSquared = dot(firstPlane.xyz, firstPlane.xyz);
    const float secondNormalSquared = dot(secondPlane.xyz, secondPlane.xyz);
    const float normalDot = dot(firstPlane.xyz, secondPlane.xyz);
    const float firstDistance = dot(firstPlane.xyz, center) + firstPlane.w;
    const float secondDistance = dot(secondPlane.xyz, center) + secondPlane.w;
    const float radiusSquared = radius * radius;
    if (!(firstNormalSquared > 0.0f) || !(secondNormalSquared > 0.0f)
        || !isfinite(firstNormalSquared) || !isfinite(secondNormalSquared)
        || !isfinite(normalDot) || !isfinite(firstDistance) || !isfinite(secondDistance)
        || !isfinite(radiusSquared) || !(firstDistance < 0.0f) || !(secondDistance < 0.0f)) {
        return false;
    }

    // Solve the two-constraint Euclidean projection onto n0.x + w0 >= 0 and
    // n1.x + w1 >= 0. A non-positive multiplier means a single plane owns the
    // closest point; the preceding individual-plane test already handled the only
    // safe rejection in that case. The relative determinant guard treats parallel
    // and near-parallel planes as untrusted rather than amplifying float error.
    const float normalProduct = firstNormalSquared * secondNormalSquared;
    const float determinant = normalProduct - normalDot * normalDot;
    if (!(normalProduct > 0.0f) || !isfinite(normalProduct)
        || !(determinant > normalProduct * 1.0e-6f) || !isfinite(determinant)) {
        return false;
    }
    const float firstRequired = -firstDistance;
    const float secondRequired = -secondDistance;
    const float firstMultiplier = (
        firstRequired * secondNormalSquared - normalDot * secondRequired
    ) / determinant;
    const float secondMultiplier = (
        secondRequired * firstNormalSquared - normalDot * firstRequired
    ) / determinant;
    if (!(firstMultiplier > 0.0f) || !(secondMultiplier > 0.0f)
        || !isfinite(firstMultiplier) || !isfinite(secondMultiplier)) {
        return false;
    }
    const float closestDistanceSquared = firstRequired * firstMultiplier
        + secondRequired * secondMultiplier;
    const float retainedTangentRadiusSquared = radiusSquared * (1.0f + 1.0e-5f);
    if (!isfinite(closestDistanceSquared) || !isfinite(retainedTangentRadiusSquared)) {
        return false;
    }
    // Keep a small relative float guard around tangency. The test is only a conservative
    // refinement, so retaining a nearly tangent member is preferable to a rounding-driven
    // false-negative at an adjacent-plane corner.
    return closestDistanceSquared > retainedTangentRadiusSquared;
}

inline bool metallum_sphere_strictly_outside_plane_corner_depth(
    const float3 center,
    const float radius,
    const float4 firstPlane,
    const float4 secondPlane,
    const float4 depthPlane
) {
    // The cluster is contained in the intersection of these three inward
    // half-spaces. Pair-wise wedges do not cover the active-set region whose closest
    // point lies on the corner/depth vertex, so solve that three-constraint projection
    // exactly. Any malformed or ill-conditioned input retains the candidate.
    if (!(radius > 0.0f) || !isfinite(radius) || !all(isfinite(center))
        || !all(isfinite(firstPlane)) || !all(isfinite(secondPlane))
        || !all(isfinite(depthPlane))) {
        return false;
    }
    const float3 firstNormal = firstPlane.xyz;
    const float3 secondNormal = secondPlane.xyz;
    const float3 depthNormal = depthPlane.xyz;
    const float firstDistance = dot(firstNormal, center) + firstPlane.w;
    const float secondDistance = dot(secondNormal, center) + secondPlane.w;
    const float depthDistance = dot(depthNormal, center) + depthPlane.w;
    if (!(firstDistance < 0.0f) || !(secondDistance < 0.0f)
        || !(depthDistance < 0.0f) || !isfinite(firstDistance)
        || !isfinite(secondDistance) || !isfinite(depthDistance)) {
        return false;
    }

    const float firstSquared = dot(firstNormal, firstNormal);
    const float secondSquared = dot(secondNormal, secondNormal);
    const float depthSquared = dot(depthNormal, depthNormal);
    const float firstSecond = dot(firstNormal, secondNormal);
    const float firstDepth = dot(firstNormal, depthNormal);
    const float secondDepth = dot(secondNormal, depthNormal);
    const float normalProduct = firstSquared * secondSquared * depthSquared;
    if (!(normalProduct > 0.0f) || !isfinite(normalProduct)
        || !isfinite(firstSecond) || !isfinite(firstDepth)
        || !isfinite(secondDepth)) {
        return false;
    }

    const float cofactor00 = secondSquared * depthSquared - secondDepth * secondDepth;
    const float cofactor01 = firstDepth * secondDepth - firstSecond * depthSquared;
    const float cofactor02 = firstSecond * secondDepth - firstDepth * secondSquared;
    const float cofactor11 = firstSquared * depthSquared - firstDepth * firstDepth;
    const float cofactor12 = firstSecond * firstDepth - firstSquared * secondDepth;
    const float cofactor22 = firstSquared * secondSquared - firstSecond * firstSecond;
    const float determinant = firstSquared * cofactor00
        + firstSecond * cofactor01 + firstDepth * cofactor02;
    if (!(determinant > normalProduct * 1.0e-6f) || !isfinite(determinant)
        || !isfinite(cofactor00) || !isfinite(cofactor01)
        || !isfinite(cofactor02) || !isfinite(cofactor11)
        || !isfinite(cofactor12) || !isfinite(cofactor22)) {
        return false;
    }

    const float3 required = -float3(firstDistance, secondDistance, depthDistance);
    const float3 multiplier = float3(
        dot(float3(cofactor00, cofactor01, cofactor02), required),
        dot(float3(cofactor01, cofactor11, cofactor12), required),
        dot(float3(cofactor02, cofactor12, cofactor22), required)
    ) / determinant;
    if (!all(multiplier > float3(0.0f)) || !all(isfinite(multiplier))) {
        return false;
    }
    const float closestDistanceSquared = dot(required, multiplier);
    const float retainedTangentRadiusSquared = radius * radius * (1.0f + 1.0e-5f);
    if (!isfinite(closestDistanceSquared) || !isfinite(retainedTangentRadiusSquared)) {
        return false;
    }
    return closestDistanceSquared > retainedTangentRadiusSquared;
}

inline bool metallum_sphere_outside_cluster_side_planes(
    const float3 center,
    const float radius,
    const uint clusterX,
    const uint clusterY,
    const uint clusterZ,
    threadgroup const float* depthBoundaries,
    constant MetallumLightingParamsV1& params
) {
    const float extentX = float(params.extentAndClusterCap.x);
    const float extentY = float(params.extentAndClusterCap.y);
    const float tileSize = float(params.capacitiesAndFlags.w);
    if (!(extentX > 0.0f) || !(extentY > 0.0f) || !(tileSize > 0.0f)
        || !isfinite(extentX) || !isfinite(extentY) || !isfinite(tileSize)) {
        return false;
    }
    const float lowerPixelX = float(clusterX) * tileSize;
    const float lowerPixelY = float(clusterY) * tileSize;
    const float upperPixelX = min(lowerPixelX + tileSize, extentX);
    const float upperPixelY = min(lowerPixelY + tileSize, extentY);
    if (!isfinite(lowerPixelX) || !isfinite(lowerPixelY)
        || !isfinite(upperPixelX) || !isfinite(upperPixelY)
        || !(upperPixelX > lowerPixelX) || !(upperPixelY > lowerPixelY)) {
        return false;
    }
    const float2 lowerNdc = float2(
        lowerPixelX * 2.0f / extentX - 1.0f,
        lowerPixelY * 2.0f / extentY - 1.0f
    );
    const float2 upperNdc = float2(
        upperPixelX * 2.0f / extentX - 1.0f,
        upperPixelY * 2.0f / extentY - 1.0f
    );
    if (!all(isfinite(lowerNdc)) || !all(isfinite(upperNdc))) {
        return false;
    }
    const float4 rowX = metallum_matrix_row(params.projection, 0u);
    const float4 rowY = metallum_matrix_row(params.projection, 1u);
    const float4 rowW = metallum_matrix_row(params.projection, 3u);
    const float4 planes[4] = {
        rowX - lowerNdc.x * rowW,
        upperNdc.x * rowW - rowX,
        rowY - lowerNdc.y * rowW,
        upperNdc.y * rowW - rowY
    };
    for (uint index = 0u; index < 4u; ++index) {
        if (!all(isfinite(planes[index]))) {
            return false;
        }
    }
    for (uint index = 0u; index < 4u; ++index) {
        if (metallum_sphere_strictly_outside_plane(center, radius, planes[index])) {
            return true;
        }
    }
    const uint2 cornerPlanePairs[4] = {
        uint2(0u, 2u), uint2(0u, 3u), uint2(1u, 2u), uint2(1u, 3u)
    };
    for (uint index = 0u; index < 4u; ++index) {
        const uint2 pair = cornerPlanePairs[index];
        if (metallum_sphere_strictly_outside_plane_wedge(
                center,
                radius,
                planes[pair.x],
                planes[pair.y]
            )) {
            return true;
        }
    }

    // Fragment cluster selection clamps the first and final logarithmic slices, so
    // slice 0 has no lower depth half-space and the final slice has no upper one.
    // Retaining those unbounded endpoints is essential: treating them as ordinary
    // near/far planes could cull a valid clamp-region fragment. Interior Z bounds are
    // otherwise exact in this same pre-projection view space.
    const uint depthSliceCount = params.gridAndLightCount.z;
    if (depthBoundaries == nullptr || depthSliceCount != 6u || clusterZ >= depthSliceCount
        || !all(isfinite(center))) {
        return false;
    }
    const float centerDepth = -center.z;
    float4 depthPlane;
    bool hasViolatedDepthPlane = false;
    if (clusterZ > 0u && centerDepth < depthBoundaries[clusterZ]) {
        const float lowerDepth = depthBoundaries[clusterZ];
        if (!isfinite(lowerDepth) || !(lowerDepth > 0.0f)) {
            return false;
        }
        // -viewZ >= lowerDepth
        depthPlane = float4(0.0f, 0.0f, -1.0f, -lowerDepth);
        hasViolatedDepthPlane = true;
    } else if (clusterZ + 1u < depthSliceCount
            && centerDepth > depthBoundaries[clusterZ + 1u]) {
        const float upperDepth = depthBoundaries[clusterZ + 1u];
        if (!isfinite(upperDepth) || !(upperDepth > 0.0f)) {
            return false;
        }
        // -viewZ <= upperDepth
        depthPlane = float4(0.0f, 0.0f, 1.0f, upperDepth);
        hasViolatedDepthPlane = true;
    }
    if (!hasViolatedDepthPlane || !all(isfinite(depthPlane))) {
        return false;
    }
    // The selected depth plane is the only one the center can violate for this
    // slice. Pairing it with a side plane rejects a sphere that overlaps each
    // half-space alone but cannot reach their shared cluster edge.
    for (uint index = 0u; index < 4u; ++index) {
        if (metallum_sphere_strictly_outside_plane_wedge(
                center,
                radius,
                planes[index],
                depthPlane
            )) {
            return true;
        }
    }
    // A source may overlap every single plane and every pair-wise wedge while still
    // missing the three-plane corner/depth vertex. This final exact active-set check
    // removes only that remaining false-positive membership.
    for (uint index = 0u; index < 4u; ++index) {
        const uint2 pair = cornerPlanePairs[index];
        if (metallum_sphere_strictly_outside_plane_corner_depth(
                center,
                radius,
                planes[pair.x],
                planes[pair.y],
                depthPlane
            )) {
            return true;
        }
    }
    return false;
}

inline bool metallum_sphere_outside_side_frustum(
    const float3 center,
    float radius,
    const float4x4 projection
) {
    const float4 rowX = metallum_matrix_row(projection, 0u);
    const float4 rowY = metallum_matrix_row(projection, 1u);
    const float4 rowW = metallum_matrix_row(projection, 3u);
    const float4 planes[4] = {
        rowW + rowX,
        rowW - rowX,
        rowW + rowY,
        rowW - rowY
    };
    for (uint index = 0u; index < 4u; ++index) {
        const float normalLength = length(planes[index].xyz);
        if (!(normalLength > 0.0f) || !isfinite(normalLength)) {
            return true;
        }
        const float distance = dot(planes[index].xyz, center) + planes[index].w;
        if (!isfinite(distance) || distance < -radius * normalLength) {
            return true;
        }
    }
    return false;
}

inline MetallumClusterBoundsV1 metallum_cluster_bounds(
    const MetallumGpuLightV1 light,
    constant MetallumLightingParamsV1& params
) {
    MetallumClusterBoundsV1 result;
    result.lower = uint3(0u);
    result.upper = uint3(0u);
    result.valid = false;

    const float radius = light.positionRadius.w;
    const float3 viewPosition = light.positionRadius.xyz;
    const float centerDepth = -viewPosition.z;
    const float nearDepth = max(params.depth.x, centerDepth - radius);
    const float farDepth = min(params.depth.y, centerDepth + radius);
    if (!(radius > 0.0f) || !(farDepth >= nearDepth) || centerDepth + radius <= params.depth.x) {
        return result;
    }

    const uint gridX = params.gridAndLightCount.x;
    const uint gridY = params.gridAndLightCount.y;
    uint minX = 0u;
    uint minY = 0u;
    uint maxX = gridX;
    uint maxY = gridY;
    const bool cameraInside = dot(viewPosition, viewPosition) <= radius * radius;
    if (!cameraInside && centerDepth <= radius) {
        // Crossing the camera plane does not imply that the camera is inside the sphere.
        // Reject laterally distant spheres before using the conservative full-screen bound.
        if (metallum_sphere_outside_side_frustum(viewPosition, radius, params.projection)) {
            return result;
        }
    } else if (!cameraInside) {
        // Project a conservative view-space AABB. center +/- radius/depth is not a
        // conservative perspective bound as the near side approaches the camera and can
        // silently omit edge clusters.
        float2 lowerNdc = float2(INFINITY);
        float2 upperNdc = float2(-INFINITY);
        bool requiresFullScreenBounds = false;
        for (uint corner = 0u; corner < 8u; ++corner) {
            const float3 sign = float3(
                (corner & 1u) == 0u ? -1.0f : 1.0f,
                (corner & 2u) == 0u ? -1.0f : 1.0f,
                (corner & 4u) == 0u ? -1.0f : 1.0f
            );
            const float4 clip = params.projection * float4(viewPosition + sign * radius, 1.0f);
            if (!all(isfinite(clip))) {
                return result;
            }
            if (clip.w <= 1.0e-6f) {
                // View bob, hurt tilt and nausea are folded into the final projection.
                // They can move an otherwise positive-depth AABB corner onto or behind
                // the clip plane. Dividing mixed-sign w values is discontinuous, while
                // rejecting the whole light makes nearby illumination disappear. Keep
                // the already-conservative full-screen XY range after a side-frustum test.
                requiresFullScreenBounds = true;
                break;
            }
            const float2 ndc = clip.xy / clip.w;
            if (!all(isfinite(ndc))) {
                return result;
            }
            lowerNdc = min(lowerNdc, ndc);
            upperNdc = max(upperNdc, ndc);
        }
        if (requiresFullScreenBounds) {
            if (metallum_sphere_outside_side_frustum(viewPosition, radius, params.projection)) {
                return result;
            }
        } else {
            if (upperNdc.x < -1.0f || lowerNdc.x > 1.0f || upperNdc.y < -1.0f || lowerNdc.y > 1.0f) {
                return result;
            }
            const float2 extent = float2(params.extentAndClusterCap.xy);
            const float2 lowerPixels = clamp(
                (lowerNdc * float2(0.5f, 0.5f) + 0.5f) * extent,
                float2(0.0f),
                extent
            );
            const float2 upperPixels = clamp(
                (upperNdc * float2(0.5f, 0.5f) + 0.5f) * extent,
                float2(0.0f),
                extent
            );
            const float2 pixelMin = min(lowerPixels, upperPixels);
            const float2 pixelMax = max(lowerPixels, upperPixels);
            const uint tileSize = params.capacitiesAndFlags.w;
            minX = min(uint(floor(pixelMin.x / float(tileSize))), gridX - 1u);
            minY = min(uint(floor(pixelMin.y / float(tileSize))), gridY - 1u);
            maxX = min(uint(ceil(pixelMax.x / float(tileSize))), gridX);
            maxY = min(uint(ceil(pixelMax.y / float(tileSize))), gridY);
        }
    }
    const uint minZ = metallum_depth_slice(nearDepth, params);
    const uint maxZ = min(metallum_depth_slice(farDepth, params) + 1u, params.gridAndLightCount.z);
    if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
        return result;
    }

    result.lower = uint3(minX, minY, minZ);
    result.upper = uint3(maxX, maxY, maxZ);
    result.valid = true;
    return result;
}

inline uint metallum_pack_cluster_range(uint lower, uint upper) {
    return (lower & 0xffffu) | ((upper & 0xffffu) << 16u);
}

inline uint2 metallum_unpack_cluster_range(uint packed) {
    return uint2(packed & 0xffffu, packed >> 16u);
}

inline void metallum_store_cluster_bounds(
    thread MetallumGpuLightV1& light,
    const MetallumClusterBoundsV1 bounds,
    uint admission
) {
    // After upload validation, metadata is private clustered-lighting scratch. Direct-light
    // shaders consume only position/radius/color/intensity and never these source fields.
    light.metadata = uint4(
        metallum_pack_cluster_range(bounds.lower.x, bounds.upper.x),
        metallum_pack_cluster_range(bounds.lower.y, bounds.upper.y),
        metallum_pack_cluster_range(bounds.lower.z, bounds.upper.z),
        admission
    );
}

inline MetallumClusterBoundsV1 metallum_load_cluster_bounds_from_metadata(
    const uint4 metadata
) {
    const uint2 x = metallum_unpack_cluster_range(metadata.x);
    const uint2 y = metallum_unpack_cluster_range(metadata.y);
    const uint2 z = metallum_unpack_cluster_range(metadata.z);
    MetallumClusterBoundsV1 bounds;
    bounds.lower = uint3(x.x, y.x, z.x);
    bounds.upper = uint3(x.y, y.y, z.y);
    bounds.valid = metadata.w == METALLUM_LIGHT_ADMISSION_ACCEPTED_V1;
    return bounds;
}

inline MetallumClusterBoundsV1 metallum_load_cluster_bounds(
    const MetallumGpuLightV1 light
) {
    return metallum_load_cluster_bounds_from_metadata(light.metadata);
}

inline uint metallum_cluster_index(uint x, uint y, uint z, constant MetallumLightingParamsV1& params) {
    return (z * params.gridAndLightCount.y + y) * params.gridAndLightCount.x + x;
}

kernel void metallum_cluster_prepare_v1(
    constant MetallumLightingParamsV1& source [[buffer(0)]],
    device MetallumLightingParamsV1& destination [[buffer(1)]],
    device MetallumClusterStatisticsV1& statistics [[buffer(2)]],
    device MetallumGpuLightV1* lights [[buffer(3)]],
    uint index [[thread_position_in_grid]]
) {
    const uint lightCount = source.gridAndLightCount.w;
    const uint candidateLightCap = min(
        source.reserved1.y,
        METALLUM_MAX_LIGHT_CANDIDATES_V1
    );
    const uint candidateCount = min(lightCount, candidateLightCap);
    if (index == 0u) {
        destination = source;
        destination.gridAndLightCount.w = candidateCount;
        if (lightCount == 0u) {
            for (uint counter = 0u; counter < 32u; ++counter) {
                atomic_store_explicit(
                    &statistics.counters[counter],
                    0u,
                    memory_order_relaxed
                );
            }
            for (uint bin = 0u; bin < METALLUM_OCCUPANCY_BIN_COUNT_V1; ++bin) {
                atomic_store_explicit(
                    &statistics.occupancyBins[bin],
                    0u,
                    memory_order_relaxed
                );
            }
            atomic_store_explicit(
                &statistics.counters[MetallumCounterAbiVersion],
                METALLUM_LIGHTING_ABI_V1,
                memory_order_relaxed
            );
            atomic_store_explicit(
                &statistics.counters[MetallumCounterClusterCount],
                source.capacitiesAndFlags.x,
                memory_order_relaxed
            );
            atomic_store_explicit(
                &statistics.counters[MetallumCounterRingSlot],
                source.reserved0.y,
                memory_order_relaxed
            );
            atomic_store_explicit(
                &statistics.counters[MetallumCounterGenerationLow],
                source.frameIdAndGeneration.z,
                memory_order_relaxed
            );
            atomic_store_explicit(
                &statistics.counters[MetallumCounterGenerationHigh],
                source.frameIdAndGeneration.w,
                memory_order_relaxed
            );
            atomic_store_explicit(
                &statistics.counters[MetallumCounterFrameLow],
                source.frameIdAndGeneration.x,
                memory_order_relaxed
            );
            atomic_store_explicit(
                &statistics.counters[MetallumCounterFrameHigh],
                source.frameIdAndGeneration.y,
                memory_order_relaxed
            );
            atomic_store_explicit(
                &statistics.counters[MetallumCounterEmptyClusters],
                source.capacitiesAndFlags.x,
                memory_order_relaxed
            );
            atomic_store_explicit(
                &statistics.occupancyBins[0],
                source.capacitiesAndFlags.x,
                memory_order_relaxed
            );
        } else {
            atomic_store_explicit(
                &statistics.counters[MetallumCounterAdmissionRejectedLights],
                lightCount - candidateCount,
                memory_order_relaxed
            );
        }
    }

    if (index >= candidateCount) {
        return;
    }
    MetallumGpuLightV1 light = lights[index];
    light.positionRadius.xyz = (source.viewRotation
        * float4(light.positionRadius.xyz, 0.0f)).xyz;
    // Color and scalar intensity are immutable for the frame. Fold their clamps and
    // multiplication once per uploaded light instead of once per contributing fragment.
    // Alpha becomes an explicit marker for the prepared representation; no downstream
    // clustered-lighting stage consumes the original scalar independently.
    light.linearColorIntensity = float4(
        max(light.linearColorIntensity.rgb, float3(0.0f))
            * max(light.linearColorIntensity.a, 0.0f),
        1.0f
    );
    const MetallumClusterBoundsV1 bounds = metallum_cluster_bounds(light, source);
    // ABI v1 uploads at most 4096 camera-stable candidates. Preserve their exact indices
    // even when a candidate is outside the current frustum: compact cluster lists, not a
    // view-dependent global prefix, decide which lights a fragment visits.
    metallum_store_cluster_bounds(
        light,
        bounds,
        bounds.valid ? METALLUM_LIGHT_ADMISSION_ACCEPTED_V1 : 0u
    );
    lights[index] = light;
}

kernel void metallum_cluster_count_v1(
    device MetallumGpuLightV1* lights [[buffer(0)]],
    device MetallumClusterScratchV1* scratch [[buffer(1)]],
    constant MetallumLightingParamsV1& params [[buffer(2)]],
    device MetallumClusterStatisticsV1& statistics [[buffer(3)]],
    uint3 lightGroup [[threadgroup_position_in_grid]],
    uint3 threadPosition [[thread_position_in_threadgroup]],
    uint3 threadsPerThreadgroup [[threads_per_threadgroup]]
) {
    const uint lightIndex = lightGroup.x;
    const uint lane = threadPosition.x;
    if (lightIndex >= params.gridAndLightCount.w) {
        return;
    }
    const MetallumClusterBoundsV1 bounds = metallum_load_cluster_bounds(lights[lightIndex]);
    if (!bounds.valid) {
        return;
    }
    threadgroup float depthBoundaries[7];
    if (lane <= 6u) {
        const float depthScale = params.depth.z;
        const float depthBias = params.depth.w;
        if (isfinite(depthScale) && isfinite(depthBias) && depthScale > 0.0f) {
            depthBoundaries[lane] = exp2((float(lane) - depthBias) / depthScale);
        } else {
            // The side/depth refinement is optional. An invalid frame contract must
            // retain the existing coarse member rather than invent a depth boundary.
            depthBoundaries[lane] = INFINITY;
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    const uint membershipWord = lightIndex >> 5u;
    const uint membershipBit = 1u << (lightIndex & 31u);
    const uint width = bounds.upper.x - bounds.lower.x;
    const uint height = bounds.upper.y - bounds.lower.y;
    const uint depth = bounds.upper.z - bounds.lower.z;
    const uint covered = width * height * depth;
    const uint stride = max(threadsPerThreadgroup.x, 1u);
    for (uint linear = lane; linear < covered; linear += stride) {
        const uint x = bounds.lower.x + linear % width;
        const uint yz = linear / width;
        const uint y = bounds.lower.y + yz % height;
        const uint z = bounds.lower.z + yz / height;
        // Coarse bounds remain the authoritative candidate range. Side/corner and
        // side×depth edge planes only reject a sphere wholly outside this cluster;
        // stable candidate order and all caps are unchanged for members that remain.
        if (metallum_sphere_outside_cluster_side_planes(
                lights[lightIndex].positionRadius.xyz,
                lights[lightIndex].positionRadius.w,
                x,
                y,
                z,
                depthBoundaries,
                params
            )) {
            continue;
        }
        const uint cluster = metallum_cluster_index(x, y, z, params);
        atomic_fetch_or_explicit(
            &scratch[cluster].membership[membershipWord],
            membershipBit,
            memory_order_relaxed
        );
    }
}

kernel void metallum_cluster_masks_v1(
    device const MetallumGpuLightV1* lights [[buffer(0)]],
    device MetallumClusterScratchV1* scratch [[buffer(1)]],
    constant MetallumLightingParamsV1& params [[buffer(2)]],
    device MetallumClusterBlockStatisticsV1* blockStatistics [[buffer(3)]],
    uint3 blockGroup [[threadgroup_position_in_grid]],
    uint3 threadPosition [[thread_position_in_threadgroup]]
) {
    const uint blockIndex = blockGroup.x;
    const uint lane = threadPosition.x;
    const uint clusterCount = params.capacitiesAndFlags.x;
    const uint blockStart = blockIndex * METALLUM_PREFIX_BLOCK_SIZE_V1;
    const uint blockLength = min(
        METALLUM_PREFIX_BLOCK_SIZE_V1,
        clusterCount - blockStart
    );
    const uint cluster = blockStart + lane;
    const uint activeLightCount = min(params.gridAndLightCount.w, 256u);
    const uint activeWords = min(
        (activeLightCount + 31u) >> 5u,
        METALLUM_LEGACY_CLUSTER_MASK_WORDS_V1
    );
    threadgroup uint4 lightMetadata[256];
    if (lane < activeLightCount) {
        lightMetadata[lane] = lights[lane].metadata;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    uint membership[METALLUM_LEGACY_CLUSTER_MASK_WORDS_V1] = {
        0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u
    };
    uint rawCount = 0u;
    if (cluster < clusterCount) {
        const uint gridX = params.gridAndLightCount.x;
        const uint gridY = params.gridAndLightCount.y;
        const uint plane = gridX * gridY;
        const uint z = cluster / plane;
        const uint remainder = cluster - z * plane;
        const uint y = remainder / gridX;
        const uint x = remainder - y * gridX;
        for (uint lightIndex = 0u; lightIndex < activeLightCount; ++lightIndex) {
            const MetallumClusterBoundsV1 bounds =
                metallum_load_cluster_bounds_from_metadata(lightMetadata[lightIndex]);
            if (bounds.valid
                    && x >= bounds.lower.x && x < bounds.upper.x
                    && y >= bounds.lower.y && y < bounds.upper.y
                    && z >= bounds.lower.z && z < bounds.upper.z) {
                membership[lightIndex >> 5u] |= 1u << (lightIndex & 31u);
                rawCount += 1u;
            }
        }
        // One lane owns one cluster. Every word the direct shader may read this frame is
        // overwritten without a full-buffer blit clear or contended global atomic OR.
        for (uint word = 0u; word < activeWords; ++word) {
            atomic_store_explicit(
                &scratch[cluster].membership[word],
                membership[word],
                memory_order_relaxed
            );
        }
    }

    threadgroup uint rawCounts[METALLUM_PREFIX_BLOCK_SIZE_V1];
    rawCounts[lane] = rawCount;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (lane == 0u) {
        uint requestedIndices = 0u;
        uint overflowClusters = 0u;
        uint perClusterDrops = 0u;
        uint emptyClusters = 0u;
        uint maximumOccupancy = 0u;
        uint occupancyBins[METALLUM_OCCUPANCY_BIN_COUNT_V1];
        for (uint bin = 0u; bin < METALLUM_OCCUPANCY_BIN_COUNT_V1; ++bin) {
            occupancyBins[bin] = 0u;
        }
        for (uint localCluster = 0u; localCluster < blockLength; ++localCluster) {
            const uint raw = rawCounts[localCluster];
            const uint selected = min(
                raw,
                min(params.extentAndClusterCap.z, METALLUM_CLUSTER_CAP_V1)
            );
            requestedIndices += selected;
            overflowClusters += raw > selected ? 1u : 0u;
            perClusterDrops += raw - selected;
            emptyClusters += selected == 0u ? 1u : 0u;
            maximumOccupancy = max(maximumOccupancy, selected);
            const uint occupancyBin = selected == 0u ? 0u : min(
                (selected + 3u) / 4u,
                METALLUM_OCCUPANCY_BIN_COUNT_V1 - 1u
            );
            occupancyBins[occupancyBin] += 1u;
        }
        MetallumClusterBlockStatisticsV1 summary;
        summary.requestedIndices = requestedIndices;
        summary.overflowClusters = overflowClusters;
        summary.perClusterDrops = perClusterDrops;
        summary.emptyClusters = emptyClusters;
        summary.maximumOccupancy = maximumOccupancy;
        for (uint reserved = 0u; reserved < 3u; ++reserved) {
            summary.reserved[reserved] = 0u;
        }
        for (uint bin = 0u; bin < METALLUM_OCCUPANCY_BIN_COUNT_V1; ++bin) {
            summary.occupancyBins[bin] = occupancyBins[bin];
        }
        blockStatistics[blockIndex] = summary;
    }
}

kernel void metallum_cluster_prefix_blocks_v1(
    device MetallumClusterScratchV1* scratch [[buffer(0)]],
    device MetallumClusterHeaderV1* headers [[buffer(1)]],
    constant MetallumLightingParamsV1& params [[buffer(2)]],
    device MetallumClusterBlockStatisticsV1* blockStatistics [[buffer(3)]],
    uint3 blockGroup [[threadgroup_position_in_grid]],
    uint3 threadPosition [[thread_position_in_threadgroup]]
) {
    const uint blockIndex = blockGroup.x;
    const uint lane = threadPosition.x;
    const uint clusterCount = params.capacitiesAndFlags.x;
    const uint blockStart = blockIndex * METALLUM_PREFIX_BLOCK_SIZE_V1;
    if (blockStart >= clusterCount) {
        return;
    }
    const uint blockEnd = min(blockStart + METALLUM_PREFIX_BLOCK_SIZE_V1, clusterCount);
    const uint blockLength = blockEnd - blockStart;
    const uint activeWords = min(
        (params.gridAndLightCount.w + 31u) >> 5u,
        METALLUM_CLUSTER_MEMBERSHIP_WORDS_V1
    );
    threadgroup uint inclusiveOffsets[METALLUM_PREFIX_BLOCK_SIZE_V1];
    threadgroup uint rawCounts[METALLUM_PREFIX_BLOCK_SIZE_V1];
    uint rawCount = 0u;
    if (lane < blockLength) {
        const uint cluster = blockStart + lane;
        for (uint word = 0u; word < activeWords; ++word) {
            rawCount += popcount(atomic_load_explicit(
                &scratch[cluster].membership[word],
                memory_order_relaxed
            ));
        }
    }
    const uint selected = min(
        rawCount,
        min(params.extentAndClusterCap.z, METALLUM_CLUSTER_CAP_V1)
    );
    rawCounts[lane] = rawCount;
    inclusiveOffsets[lane] = selected;
    threadgroup_barrier(mem_flags::mem_threadgroup);

    // Hillis-Steele is intentionally local to one 256-cluster block. It replaces one
    // serial thread per block while keeping exact deterministic offsets.
    for (uint offset = 1u; offset < METALLUM_PREFIX_BLOCK_SIZE_V1; offset <<= 1u) {
        const uint addend = lane >= offset ? inclusiveOffsets[lane - offset] : 0u;
        threadgroup_barrier(mem_flags::mem_threadgroup);
        inclusiveOffsets[lane] += addend;
        threadgroup_barrier(mem_flags::mem_threadgroup);
    }

    if (lane < blockLength) {
        const uint cluster = blockStart + lane;
        headers[cluster] = MetallumClusterHeaderV1{
            inclusiveOffsets[lane] - selected,
            selected
        };
    }
    if (lane == 0u) {
        uint overflowClusters = 0u;
        uint perClusterDrops = 0u;
        uint emptyClusters = 0u;
        uint maximumOccupancy = 0u;
        uint occupancyBins[METALLUM_OCCUPANCY_BIN_COUNT_V1];
        for (uint bin = 0u; bin < METALLUM_OCCUPANCY_BIN_COUNT_V1; ++bin) {
            occupancyBins[bin] = 0u;
        }
        for (uint localCluster = 0u; localCluster < blockLength; ++localCluster) {
            const uint raw = rawCounts[localCluster];
            const uint retained = min(
                raw,
                min(params.extentAndClusterCap.z, METALLUM_CLUSTER_CAP_V1)
            );
            overflowClusters += raw > retained ? 1u : 0u;
            perClusterDrops += raw - retained;
            emptyClusters += retained == 0u ? 1u : 0u;
            maximumOccupancy = max(maximumOccupancy, retained);
            const uint occupancyBin = retained == 0u ? 0u : min(
                (retained + 3u) / 4u,
                METALLUM_OCCUPANCY_BIN_COUNT_V1 - 1u
            );
            occupancyBins[occupancyBin] += 1u;
        }
        // The first local offset is zero, so it carries the block total until
        // prefix-groups replaces it with the block base.
        const uint requestedIndices = inclusiveOffsets[blockLength - 1u];
        headers[blockStart].offset = requestedIndices;
        MetallumClusterBlockStatisticsV1 summary;
        summary.requestedIndices = requestedIndices;
        summary.overflowClusters = overflowClusters;
        summary.perClusterDrops = perClusterDrops;
        summary.emptyClusters = emptyClusters;
        summary.maximumOccupancy = maximumOccupancy;
        for (uint reserved = 0u; reserved < 3u; ++reserved) {
            summary.reserved[reserved] = 0u;
        }
        for (uint bin = 0u; bin < METALLUM_OCCUPANCY_BIN_COUNT_V1; ++bin) {
            summary.occupancyBins[bin] = occupancyBins[bin];
        }
        blockStatistics[blockIndex] = summary;
    }
}

kernel void metallum_cluster_prefix_groups_v1(
    device MetallumClusterHeaderV1* headers [[buffer(0)]],
    constant MetallumLightingParamsV1& params [[buffer(1)]],
    device MetallumClusterStatisticsV1& statistics [[buffer(2)]],
    device const MetallumClusterBlockStatisticsV1* blockStatistics [[buffer(3)]],
    uint index [[thread_position_in_grid]]
) {
    if (index != 0u) {
        return;
    }
    const uint clusterCount = params.capacitiesAndFlags.x;
    const uint blockCount = (clusterCount + METALLUM_PREFIX_BLOCK_SIZE_V1 - 1u)
        / METALLUM_PREFIX_BLOCK_SIZE_V1;
    ulong cursor = 0ul;
    uint overflowClusters = 0u;
    uint perClusterDrops = 0u;
    uint emptyClusters = 0u;
    uint maximumOccupancy = 0u;
    uint occupancyBins[METALLUM_OCCUPANCY_BIN_COUNT_V1];
    for (uint bin = 0u; bin < METALLUM_OCCUPANCY_BIN_COUNT_V1; ++bin) {
        occupancyBins[bin] = 0u;
    }
    for (uint block = 0u; block < blockCount; ++block) {
        const uint blockStart = block * METALLUM_PREFIX_BLOCK_SIZE_V1;
        const MetallumClusterBlockStatisticsV1 summary = blockStatistics[block];
        const uint total = summary.requestedIndices;
        headers[blockStart].offset = uint(cursor);
        cursor += ulong(total);
        overflowClusters += summary.overflowClusters;
        perClusterDrops += summary.perClusterDrops;
        emptyClusters += summary.emptyClusters;
        maximumOccupancy = max(maximumOccupancy, summary.maximumOccupancy);
        for (uint bin = 0u; bin < METALLUM_OCCUPANCY_BIN_COUNT_V1; ++bin) {
            occupancyBins[bin] += summary.occupancyBins[bin];
        }
    }
    const uint requested = uint(min(cursor, ulong(UINT_MAX)));
    const uint accepted = min(requested, params.extentAndClusterCap.w);
    atomic_store_explicit(
        &statistics.counters[MetallumCounterAbiVersion],
        METALLUM_LIGHTING_ABI_V1,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterLightCount],
        params.reserved1.x,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterClusterCount],
        clusterCount,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterAcceptedIndices],
        accepted,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterRequestedIndices],
        requested,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterOverflowClusters],
        overflowClusters,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterPerClusterDrops],
        perClusterDrops,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterIndexCapacityDrops],
        requested - accepted,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterRingSlot],
        params.reserved0.y,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterGenerationLow],
        params.frameIdAndGeneration.z,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterGenerationHigh],
        params.frameIdAndGeneration.w,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterFrameLow],
        params.frameIdAndGeneration.x,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterFrameHigh],
        params.frameIdAndGeneration.y,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterEmptyClusters],
        emptyClusters,
        memory_order_relaxed
    );
    atomic_store_explicit(
        &statistics.counters[MetallumCounterMaximumOccupancy],
        maximumOccupancy,
        memory_order_relaxed
    );
    for (uint bin = 0u; bin < METALLUM_OCCUPANCY_BIN_COUNT_V1; ++bin) {
        atomic_store_explicit(
            &statistics.occupancyBins[bin],
            occupancyBins[bin],
            memory_order_relaxed
        );
    }
}

kernel void metallum_cluster_prefix_add_v1(
    device MetallumClusterHeaderV1* headers [[buffer(0)]],
    constant MetallumLightingParamsV1& params [[buffer(1)]],
    uint3 blockGroup [[threadgroup_position_in_grid]],
    uint3 threadPosition [[thread_position_in_threadgroup]]
) {
    const uint block = blockGroup.x;
    const uint lane = threadPosition.x;
    const uint clusterCount = params.capacitiesAndFlags.x;
    const uint blockStart = block * METALLUM_PREFIX_BLOCK_SIZE_V1;
    if (blockStart >= clusterCount) {
        return;
    }
    const uint blockEnd = min(blockStart + METALLUM_PREFIX_BLOCK_SIZE_V1, clusterCount);
    const uint cluster = blockStart + lane;
    if (cluster >= blockEnd) {
        return;
    }
    const uint blockBase = headers[blockStart].offset;
    const uint capacity = params.extentAndClusterCap.w;
    const MetallumClusterHeaderV1 localHeader = headers[cluster];
    const uint localOffset = lane == 0u ? 0u : localHeader.offset;
    const uint base = blockBase + localOffset;
    const uint count = base >= capacity
        ? 0u
        : min(localHeader.count, capacity - base);
    headers[cluster] = MetallumClusterHeaderV1{min(base, capacity), count};
}

kernel void metallum_cluster_fill_v1(
    device const MetallumClusterScratchV1* scratch [[buffer(0)]],
    device const MetallumClusterHeaderV1* headers [[buffer(1)]],
    device ushort* lightIndices [[buffer(2)]],
    constant MetallumLightingParamsV1& params [[buffer(3)]],
    uint cluster [[thread_position_in_grid]]
) {
    if (cluster >= params.capacitiesAndFlags.x) {
        return;
    }
    const MetallumClusterHeaderV1 header = headers[cluster];
    const uint activeWords = min(
        (params.gridAndLightCount.w + 31u) >> 5u,
        METALLUM_CLUSTER_MEMBERSHIP_WORDS_V1
    );
    uint written = 0u;
    for (uint word = 0u; word < activeWords && written < header.count; ++word) {
        uint membership = atomic_load_explicit(
            &scratch[cluster].membership[word],
            memory_order_relaxed
        );
        while (membership != 0u && written < header.count) {
            const uint bit = ctz(membership);
            lightIndices[header.offset + written] = ushort(word * 32u + bit);
            written += 1u;
            membership &= membership - 1u;
        }
    }
}
