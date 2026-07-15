#include <metal_stdlib>

using namespace metal;

kernel void metallum_sodium_light_legacy_patch(
        device ushort *geometry [[buffer(0)]],
        const device ushort *sidecar [[buffer(1)]],
        constant uint2 &range [[buffer(2)]],
        uint index [[thread_position_in_grid]]) {
    const uint vertexCount = range.y;
    if (index >= vertexCount) {
        return;
    }

    const uint vertexIndex = range.x + index;
    // Sodium's legacy 20-byte terrain vertex stores packed light at byte 16.
    geometry[vertexIndex * 10u + 8u] = sidecar[vertexIndex];
}
