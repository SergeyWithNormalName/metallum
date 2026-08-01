#include <metal_stdlib>

using namespace metal;

// L5 voxel occupancy ABI. This file intentionally has no fragment entry point:
// clipmaps are an acceleration structure only until L6/L7 explicitly consume them.
constant uint METALLUM_VOXEL_LOGICAL_BRICK_EDGE_V1 = 32u;
constant uint METALLUM_VOXEL_OCCUPANCY_WORDS_PER_BRICK_V1 = 1024u;

struct MetallumVoxelPatchRecordV1 {
    uint level;
    uint destinationBrickX;
    uint destinationBrickY;
    uint destinationBrickZ;
    uint payloadOffset;
    uint occupancyBytes;
    uint opticalBytes;
    uint chromaticBytes;
    uint flags;
    uint brickGenerationLow;
    uint brickGenerationHigh;
    int logicalBrickX;
    int logicalBrickY;
    int logicalBrickZ;
    uint contentStamp;
    uint reserved;
};

struct MetallumVoxelParamsV1 {
    uint patchCount;
    uint headerBytes;
    uint recordBytes;
    uint levelIndex;
    uint recordStart;
    uint logicalEdge;
    uint subdivision;
    uint brickDimension;
    uint occupancyWordsPerBrick;
    uint reserved0;
    ulong lightingGeneration;
    ulong clipmapGeneration;
    ulong worldGeneration;
    ulong frameId;
};

struct MetallumVoxelChecksumParamsV1 {
    uint occupancyWords;
    uint opticalBytes;
    uint chromaticBytes;
    uint threadCount;
};

static_assert(sizeof(MetallumVoxelPatchRecordV1) == 64,
    "Voxel patch ABI must stay 64 bytes");
static_assert(sizeof(MetallumVoxelParamsV1) == 72,
    "Voxel params ABI must stay 72 bytes");
static_assert(__builtin_offsetof(MetallumVoxelPatchRecordV1, payloadOffset) == 16,
    "Voxel payload offset ABI");

inline uint metallum_voxel_brick_index(
    uint x,
    uint y,
    uint z,
    uint brickDimension
) {
    return (z * brickDimension + y) * brickDimension + x;
}

kernel void metallum_voxel_apply_v1(
    device const uchar *packet [[buffer(0)]],
    constant MetallumVoxelParamsV1 &params [[buffer(1)]],
    device uint *occupancy [[buffer(2)]],
    device uchar *optical [[buffer(3)]],
    device uchar *chromatic [[buffer(4)]],
    device uint4 *brickMetadata [[buffer(5)]],
    uint3 group [[threadgroup_position_in_grid]],
    uint3 threadPosition [[thread_position_in_threadgroup]]
) {
    const uint threadIndex = threadPosition.x;
    const uint patchIndex = group.x;
    if (patchIndex >= params.patchCount
        || params.occupancyWordsPerBrick != METALLUM_VOXEL_OCCUPANCY_WORDS_PER_BRICK_V1) {
        return;
    }

    const device MetallumVoxelPatchRecordV1 *records =
        reinterpret_cast<device const MetallumVoxelPatchRecordV1 *>(packet + params.headerBytes);
    const MetallumVoxelPatchRecordV1 record = records[params.recordStart + patchIndex];
    if (record.level != params.levelIndex) {
        return;
    }

    const device uint *sourceOccupancy =
        reinterpret_cast<device const uint *>(packet + record.payloadOffset);
    const uint baseEdge = METALLUM_VOXEL_LOGICAL_BRICK_EDGE_V1 / params.subdivision;
    const uint baseDimension = params.logicalEdge / params.subdivision;
    const uint brickBaseX = record.destinationBrickX * baseEdge;
    const uint brickBaseY = record.destinationBrickY * baseEdge;
    const uint brickBaseZ = record.destinationBrickZ * baseEdge;
    const uint brickLogicalX = record.destinationBrickX * METALLUM_VOXEL_LOGICAL_BRICK_EDGE_V1;
    const uint brickLogicalY = record.destinationBrickY * METALLUM_VOXEL_LOGICAL_BRICK_EDGE_V1;
    const uint brickLogicalZ = record.destinationBrickZ * METALLUM_VOXEL_LOGICAL_BRICK_EDGE_V1;

    // A 32³ logical brick occupies exactly one 32-bit word per row. Its aligned
    // destination means no two valid records race on the same occupancy word.
    for (uint localWord = threadIndex;
         localWord < params.occupancyWordsPerBrick;
         localWord += 256u) {
        const uint localY = localWord & 31u;
        const uint localZ = localWord >> 5u;
        const uint destination = ((brickLogicalZ + localZ) * params.logicalEdge
            + (brickLogicalY + localY)) * (params.logicalEdge >> 5u)
            + (brickLogicalX >> 5u);
        occupancy[destination] = sourceOccupancy[localWord];
    }

    const device uchar *sourceOptical = packet + record.payloadOffset + record.occupancyBytes;
    for (uint local = threadIndex; local < record.opticalBytes; local += 256u) {
        const uint localX = local % baseEdge;
        const uint localY = (local / baseEdge) % baseEdge;
        const uint localZ = local / (baseEdge * baseEdge);
        const uint destination = ((brickBaseZ + localZ) * baseDimension
            + (brickBaseY + localY)) * baseDimension + (brickBaseX + localX);
        optical[destination] = sourceOptical[local];
    }

    // The base edge and base dimension are both even for all valid L5 levels. Each source
    // nibble pair therefore maps to exactly one destination byte without cross-brick races.
    const device uchar *sourceChromatic = sourceOptical + record.opticalBytes;
    for (uint localPair = threadIndex; localPair < record.chromaticBytes; localPair += 256u) {
        const uint firstLocalBlock = localPair * 2u;
        const uint localX = firstLocalBlock % baseEdge;
        const uint localY = (firstLocalBlock / baseEdge) % baseEdge;
        const uint localZ = firstLocalBlock / (baseEdge * baseEdge);
        const uint destinationBlock = ((brickBaseZ + localZ) * baseDimension
            + (brickBaseY + localY)) * baseDimension + (brickBaseX + localX);
        chromatic[destinationBlock >> 1u] = sourceChromatic[localPair];
    }

    if (threadIndex == 0u) {
        const uint destinationBrick = metallum_voxel_brick_index(
            record.destinationBrickX,
            record.destinationBrickY,
            record.destinationBrickZ,
            params.brickDimension
        );
        // A toroidal physical slot is valid only for this exact logical brick
        // and content stamp. Reuse after a normal scroll cannot masquerade as
        // valid merely because the context generations still match.
        brickMetadata[destinationBrick] = uint4(
            as_type<uint>(record.logicalBrickX),
            as_type<uint>(record.logicalBrickY),
            as_type<uint>(record.logicalBrickZ),
            record.contentStamp
        );
    }
}

// Diagnostic-only reduction. It is issued only through the explicit debug ABI;
// normal L5 uploads never scan a clipmap when the patch list is empty.
kernel void metallum_voxel_checksum_v1(
    device const uint *occupancy [[buffer(0)]],
    device const uchar *optical [[buffer(1)]],
    device const uchar *chromatic [[buffer(2)]],
    device atomic_uint *checksum [[buffer(3)]],
    constant MetallumVoxelChecksumParamsV1 &params [[buffer(4)]],
    uint index [[thread_position_in_grid]]
) {
    uint value = 0u;
    for (uint word = index; word < params.occupancyWords; word += params.threadCount) {
        value ^= occupancy[word];
    }
    for (uint byteIndex = index; byteIndex < params.opticalBytes; byteIndex += params.threadCount) {
        value ^= uint(optical[byteIndex]) << ((byteIndex & 3u) * 8u);
    }
    for (uint byteIndex = index; byteIndex < params.chromaticBytes; byteIndex += params.threadCount) {
        value ^= uint(chromatic[byteIndex]) << ((byteIndex & 3u) * 8u);
    }
    atomic_fetch_xor_explicit(&checksum[0], value, memory_order_relaxed);
}
