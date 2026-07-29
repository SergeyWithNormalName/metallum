import Darwin
import Foundation
import Metal
import simd

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case let .message(message): message
        }
    }
}

private typealias NativeAbiVersion = @convention(c) () -> UInt32
private typealias NativeLayout = @convention(c) (UnsafeMutableRawPointer?, UInt64) -> Int32
private typealias NativeInit = @convention(c) (UnsafeRawPointer?) -> Int32
private typealias NativeSetFrame = @convention(c) (UnsafeRawPointer?, UInt64) -> Int32
private typealias NativeCreateContext = @convention(c) (
    UnsafeRawPointer?, UInt64, UInt32, UInt32, UInt32, UInt32, UInt32
) -> UnsafeMutableRawPointer?
private typealias NativeReleaseContext = @convention(c) (UnsafeMutableRawPointer?) -> Void
private typealias NativeUploadAndBuild = @convention(c) (
    UnsafeMutableRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UInt64
) -> Int32
private typealias NativeContextBuffer = @convention(c) (
    UnsafeMutableRawPointer?, Int32
) -> UnsafeMutableRawPointer?
private typealias NativeContextBufferBytes = @convention(c) (
    UnsafeMutableRawPointer?, Int32
) -> UInt64
private typealias NativeLastCompletedStats = @convention(c) (
    UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UInt64
) -> Int32

private let lightingMagic: UInt32 = 0x31424c4d
private let headerBytes = 64
private let lightBytes = 48
private let guardBytes = 64
private let clusterCap = 256
private let tileSize = 64
private let depthSlices = 6
private let clusterMaskBatchFlag: UInt32 = 1 << 1
private let maximumLightCandidates: UInt32 = 4_096
private let membershipWordsPerCluster = 128

private struct NativeApi {
    let abiVersion: NativeAbiVersion
    let layout: NativeLayout
    let initialize: NativeInit
    let setFrame: NativeSetFrame
    let createContext: NativeCreateContext
    let releaseContext: NativeReleaseContext
    let uploadAndBuild: NativeUploadAndBuild
    let contextBuffer: NativeContextBuffer
    let contextBufferBytes: NativeContextBufferBytes
    let lastCompletedStats: NativeLastCompletedStats
}

private struct Light: Equatable {
    let position: SIMD3<Float>
    let radius: Float
    let color: SIMD3<Float>
    let intensity: Float
    let stableId: UInt64
    let flags: UInt32
}

private struct ReferenceResult: Equatable {
    let headers: [SIMD2<UInt32>]
    let indices: [UInt32]
    let requestedIndices: UInt32
    let overflowClusters: UInt32
    let perClusterDrops: UInt32
    let indexCapacityDrops: UInt32
    let admissionRejectedLights: UInt32
    let uploadedLightCount: UInt32
    let admittedLightCount: UInt32
    let admittedLightCap: UInt32
    let perClusterCap: UInt32
    let workloadBudget: UInt32
    let indexCapacity: UInt32
}

private struct PresetContract {
    let perClusterCap: Int
}

private func presetContract(_ preset: UInt32) -> PresetContract {
    switch preset {
    case 0, 1, 2: return PresetContract(perClusterCap: 256)
    default: preconditionFailure("Unknown validation lighting preset \(preset)")
    }
}

private struct ReferenceBounds {
    let lower: SIMD3<Int>
    let upper: SIMD3<Int>
}

private struct GpuResult: Equatable {
    let lights: [UInt8]
    let headers: [SIMD2<UInt32>]
    let indices: [UInt32]
    let masks: [UInt32]
    let params: [UInt8]
    let stats: [UInt8]
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw ValidationFailure.message(message) }
}

private func objectPointer(_ object: AnyObject) -> UnsafeRawPointer {
    UnsafeRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

private func writeUInt32(_ value: UInt32, at offset: Int, into bytes: inout [UInt8]) {
    var little = value.littleEndian
    withUnsafeBytes(of: &little) { source in
        bytes.replaceSubrange(offset..<(offset + source.count), with: source)
    }
}

private func writeUInt64(_ value: UInt64, at offset: Int, into bytes: inout [UInt8]) {
    var little = value.littleEndian
    withUnsafeBytes(of: &little) { source in
        bytes.replaceSubrange(offset..<(offset + source.count), with: source)
    }
}

private func writeFloat(_ value: Float, at offset: Int, into bytes: inout [UInt8]) {
    writeUInt32(value.bitPattern, at: offset, into: &bytes)
}

private func writeDouble(_ value: Double, at offset: Int, into bytes: inout [UInt8]) {
    writeUInt64(value.bitPattern, at: offset, into: &bytes)
}

private func readUInt32(_ bytes: [UInt8], at offset: Int) -> UInt32 {
    bytes.withUnsafeBytes { raw in
        UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
    }
}

private func readUInt64(_ bytes: [UInt8], at offset: Int) -> UInt64 {
    bytes.withUnsafeBytes { raw in
        UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt64.self))
    }
}

private func readFloat(_ bytes: [UInt8], at offset: Int) -> Float {
    Float(bitPattern: readUInt32(bytes, at: offset))
}

private func perspective(near: Float, far: Float, aspect: Float = 1) -> simd_float4x4 {
    let y = 1 / tan(Float.pi / 6)
    let x = y / aspect
    let z = far / (near - far)
    return simd_float4x4(columns: (
        SIMD4(x, 0, 0, 0),
        SIMD4(0, y, 0, 0),
        SIMD4(0, 0, z, -1),
        SIMD4(0, 0, z * near, 0)
    ))
}

private func bobbedProjection(
    near: Float,
    far: Float,
    aspect: Float,
    xRotationDegrees: Float,
    zTranslation: Float
) -> simd_float4x4 {
    let angle = xRotationDegrees * Float.pi / 180
    let cosine = cos(angle)
    let sine = sin(angle)
    let bob = simd_float4x4(columns: (
        SIMD4(1, 0, 0, 0),
        SIMD4(0, cosine, sine, 0),
        SIMD4(0, -sine, cosine, 0),
        SIMD4(0, 0, zTranslation, 1)
    ))
    return perspective(near: near, far: far, aspect: aspect) * bob
}

private func writeMatrix(_ matrix: simd_float4x4, at offset: Int, into bytes: inout [UInt8]) {
    for column in 0..<4 {
        for row in 0..<4 {
            writeFloat(matrix[column][row], at: offset + column * 16 + row * 4, into: &bytes)
        }
    }
}

private func framePacket(
    generation: UInt64,
    frameId: UInt64,
    submitIndex: UInt64,
    width: UInt32,
    height: UInt32,
    lightCount: UInt32,
    hdr: Bool,
    preset: UInt32 = 1,
    projectionOverride: simd_float4x4? = nil
) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 848)
    writeUInt32(3, at: 0, into: &bytes)
    writeUInt32(848, at: 4, into: &bytes)
    writeUInt32(1, at: 8, into: &bytes)
    writeUInt32(2, at: 12, into: &bytes)
    writeUInt64(frameId, at: 16, into: &bytes)
    writeUInt64(submitIndex, at: 24, into: &bytes)
    writeUInt64(generation, at: 32, into: &bytes)
    writeUInt64(generation, at: 40, into: &bytes)
    writeUInt64(generation, at: 48, into: &bytes)
    writeUInt64(generation, at: 56, into: &bytes)
    writeUInt64(generation, at: 64, into: &bytes)
    writeUInt64(1, at: 72, into: &bytes)
    writeUInt64(1, at: 80, into: &bytes)
    writeUInt32(1, at: 104, into: &bytes) // Metallum material contract.
    writeUInt32(1, at: 108, into: &bytes) // Advanced lighting.
    writeUInt32(hdr ? 1 : 0, at: 112, into: &bytes)
    writeUInt32(0, at: 116, into: &bytes) // Metal 3 executor.
    writeUInt32(preset, at: 120, into: &bytes)
    writeUInt32(width, at: 124, into: &bytes)
    writeUInt32(height, at: 128, into: &bytes)
    writeUInt32(width, at: 132, into: &bytes)
    writeUInt32(height, at: 136, into: &bytes)
    writeUInt32(UInt32(submitIndex % 3), at: 140, into: &bytes)
    writeFloat(1 / 60, at: 148, into: &bytes)
    writeFloat(0.1, at: 152, into: &bytes)
    writeFloat(100, at: 156, into: &bytes)
    writeFloat(1, at: 168, into: &bytes)
    writeFloat(1, at: 172, into: &bytes)
    writeFloat(1, at: 176, into: &bytes)
    writeFloat(1, at: 180, into: &bytes)
    writeUInt64(32, at: 192, into: &bytes)
    writeUInt64(32, at: 200, into: &bytes)
    writeUInt64(hdr ? 64 : 0, at: 208, into: &bytes)
    writeUInt64(1_024, at: 216, into: &bytes)
    writeUInt32(lightCount, at: 248, into: &bytes)
    writeUInt32(3, at: 252, into: &bytes)
    writeUInt32(3, at: 256, into: &bytes)
    writeUInt32(6, at: 260, into: &bytes)
    writeUInt32(1, at: 264, into: &bytes)
    writeUInt32(6, at: 268, into: &bytes)
    writeUInt64(UInt64(headerBytes) + UInt64(lightCount) * UInt64(lightBytes), at: 272, into: &bytes)
    for index in 0..<6 { writeDouble(0, at: 280 + index * 8, into: &bytes) }
    let identity = matrix_identity_float4x4
    let projection = projectionOverride
        ?? perspective(near: 0.1, far: 100, aspect: Float(width) / Float(height))
    for offset in [328, 456, 584, 712] { writeMatrix(identity, at: offset, into: &bytes) }
    for offset in [392, 520, 648, 776] { writeMatrix(projection, at: offset, into: &bytes) }
    return bytes
}

private func batchPacket(
    generation: UInt64,
    frameId: UInt64,
    submitIndex: UInt64,
    lights: [Light],
    flags: UInt32 = 1
) -> [UInt8] {
    let totalBytes = headerBytes + lights.count * lightBytes
    var bytes = [UInt8](repeating: 0, count: totalBytes)
    writeUInt32(lightingMagic, at: 0, into: &bytes)
    writeUInt32(1, at: 4, into: &bytes)
    writeUInt32(UInt32(totalBytes), at: 8, into: &bytes)
    writeUInt32(UInt32(headerBytes), at: 12, into: &bytes)
    writeUInt32(UInt32(lightBytes), at: 16, into: &bytes)
    writeUInt32(UInt32(lights.count), at: 20, into: &bytes)
    writeUInt32(UInt32(submitIndex % 3), at: 24, into: &bytes)
    writeUInt32(flags, at: 28, into: &bytes) // CPU priority + stable-ID order is authoritative.
    writeUInt64(frameId, at: 32, into: &bytes)
    writeUInt64(submitIndex, at: 40, into: &bytes)
    writeUInt64(generation, at: 48, into: &bytes)
    for (index, light) in lights.enumerated() {
        let base = headerBytes + index * lightBytes
        writeFloat(light.position.x, at: base, into: &bytes)
        writeFloat(light.position.y, at: base + 4, into: &bytes)
        writeFloat(light.position.z, at: base + 8, into: &bytes)
        writeFloat(light.radius, at: base + 12, into: &bytes)
        writeFloat(light.color.x, at: base + 16, into: &bytes)
        writeFloat(light.color.y, at: base + 20, into: &bytes)
        writeFloat(light.color.z, at: base + 24, into: &bytes)
        writeFloat(light.intensity, at: base + 28, into: &bytes)
        writeUInt32(UInt32(truncatingIfNeeded: light.stableId), at: base + 32, into: &bytes)
        writeUInt32(UInt32(truncatingIfNeeded: light.stableId >> 32), at: base + 36, into: &bytes)
        writeUInt32(light.flags, at: base + 40, into: &bytes)
        writeUInt32(UInt32(truncatingIfNeeded: generation), at: base + 44, into: &bytes)
    }
    return bytes
}

private func setFrame(_ api: NativeApi, _ bytes: [UInt8]) throws {
    let status = bytes.withUnsafeBytes { api.setFrame($0.baseAddress, UInt64($0.count)) }
    try require(status == 1, "FrameState v3 admission failed with status \(status)")
}

private func upload(
    _ api: NativeApi,
    context: UnsafeMutableRawPointer?,
    commandBuffer: MTLCommandBuffer?,
    packet: [UInt8]
) -> Int32 {
    packet.withUnsafeBytes { raw in
        api.uploadAndBuild(
            context,
            commandBuffer.map { objectPointer($0 as AnyObject) },
            raw.baseAddress,
            UInt64(raw.count)
        )
    }
}

private func borrowedBuffer(
    _ api: NativeApi,
    context: UnsafeMutableRawPointer,
    kind: Int32
) throws -> MTLBuffer {
    guard let pointer = api.contextBuffer(context, kind) else {
        throw ValidationFailure.message("Native buffer kind \(kind) is missing")
    }
    let object = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue()
    guard let buffer = object as? MTLBuffer else {
        throw ValidationFailure.message("Native buffer kind \(kind) is not an MTLBuffer")
    }
    try require(
        UInt64(buffer.length - guardBytes) == api.contextBufferBytes(context, kind),
        "Native buffer kind \(kind) payload/guard length mismatch"
    )
    return buffer
}

private func depthSlice(_ depth: Float, near: Float, far: Float) -> Int {
    let scale = Float(depthSlices) / log2(far / near)
    let bias = -log2(near) * scale
    return min(max(Int(floor(log2(max(depth, near)) * scale + bias)), 0), depthSlices - 1)
}

private func sphereOutsideSideFrustum(
    center: SIMD3<Float>,
    radius: Float,
    projection: simd_float4x4
) -> Bool {
    func row(_ index: Int) -> SIMD4<Float> {
        SIMD4(
            projection[0][index],
            projection[1][index],
            projection[2][index],
            projection[3][index]
        )
    }
    let rowX = row(0)
    let rowY = row(1)
    let rowW = row(3)
    let planes = [rowW + rowX, rowW - rowX, rowW + rowY, rowW - rowY]
    return planes.contains { plane in
        let normal = SIMD3(plane.x, plane.y, plane.z)
        let normalLength = simd_length(normal)
        let distance = simd_dot(normal, center) + plane.w
        return !normalLength.isFinite || normalLength <= 0
            || !distance.isFinite || distance < -radius * normalLength
    }
}

private func clusterSidePlanes(
    clusterX: Int,
    clusterY: Int,
    width: Int,
    height: Int,
    projection: simd_float4x4
) -> [SIMD4<Float>]? {
    guard width > 0, height > 0,
          clusterX >= 0, clusterY >= 0 else { return nil }
    let lowerX = Float(clusterX * tileSize)
    let lowerY = Float(clusterY * tileSize)
    let upperX = min(lowerX + Float(tileSize), Float(width))
    let upperY = min(lowerY + Float(tileSize), Float(height))
    guard lowerX.isFinite, lowerY.isFinite, upperX.isFinite, upperY.isFinite,
          upperX > lowerX, upperY > lowerY else { return nil }
    let lowerNdc = SIMD2(lowerX * 2 / Float(width) - 1, lowerY * 2 / Float(height) - 1)
    let upperNdc = SIMD2(upperX * 2 / Float(width) - 1, upperY * 2 / Float(height) - 1)
    guard lowerNdc.x.isFinite, lowerNdc.y.isFinite,
          upperNdc.x.isFinite, upperNdc.y.isFinite else { return nil }
    func row(_ index: Int) -> SIMD4<Float> {
        SIMD4(
            projection[0][index], projection[1][index],
            projection[2][index], projection[3][index]
        )
    }
    let rowX = row(0)
    let rowY = row(1)
    let rowW = row(3)
    let planes = [
        rowX - lowerNdc.x * rowW,
        upperNdc.x * rowW - rowX,
        rowY - lowerNdc.y * rowW,
        upperNdc.y * rowW - rowY
    ]
    guard planes.allSatisfy({ $0.x.isFinite && $0.y.isFinite && $0.z.isFinite && $0.w.isFinite })
    else { return nil }
    return planes
}

// This oracle deliberately uses a normalized-plane comparison rather than the shader's
// squared form. It proves the same strict half-space relation independently.
private func sphereStrictlyOutsidePlane(
    center: SIMD3<Float>,
    radius: Float,
    plane: SIMD4<Float>
) -> Bool {
    guard radius > 0, radius.isFinite,
          center.x.isFinite, center.y.isFinite, center.z.isFinite,
          plane.x.isFinite, plane.y.isFinite, plane.z.isFinite, plane.w.isFinite else {
        return false
    }
    let normal = SIMD3(plane.x, plane.y, plane.z)
    let normalLength = simd_length(normal)
    let distance = simd_dot(normal, center) + plane.w
    guard normalLength > 0, normalLength.isFinite, distance.isFinite else { return false }
    return distance < -radius * normalLength
}

// This deliberately normalizes the two planes and solves the constrained projection
// independently of the shader's unnormalized Gram-matrix implementation. A rejected
// sphere misses the larger pair-of-halfspaces wedge, so it cannot reach the tile inside it.
private func sphereStrictlyOutsidePlaneWedge(
    center: SIMD3<Float>,
    radius: Float,
    firstPlane: SIMD4<Float>,
    secondPlane: SIMD4<Float>
) -> Bool {
    guard radius > 0, radius.isFinite,
          center.x.isFinite, center.y.isFinite, center.z.isFinite,
          firstPlane.x.isFinite, firstPlane.y.isFinite,
          firstPlane.z.isFinite, firstPlane.w.isFinite,
          secondPlane.x.isFinite, secondPlane.y.isFinite,
          secondPlane.z.isFinite, secondPlane.w.isFinite else {
        return false
    }
    let firstNormal = SIMD3(firstPlane.x, firstPlane.y, firstPlane.z)
    let secondNormal = SIMD3(secondPlane.x, secondPlane.y, secondPlane.z)
    let firstLength = simd_length(firstNormal)
    let secondLength = simd_length(secondNormal)
    guard firstLength > 0, secondLength > 0,
          firstLength.isFinite, secondLength.isFinite else {
        return false
    }
    let firstUnit = firstNormal / firstLength
    let secondUnit = secondNormal / secondLength
    let firstDistance = (simd_dot(firstNormal, center) + firstPlane.w) / firstLength
    let secondDistance = (simd_dot(secondNormal, center) + secondPlane.w) / secondLength
    guard firstDistance.isFinite, secondDistance.isFinite,
          firstDistance < 0, secondDistance < 0 else {
        return false
    }
    let normalDot = simd_dot(firstUnit, secondUnit)
    let determinant = 1 - normalDot * normalDot
    guard normalDot.isFinite, determinant.isFinite, determinant > 1e-6 else {
        return false
    }
    let firstRequired = -firstDistance
    let secondRequired = -secondDistance
    let firstMultiplier = (firstRequired - normalDot * secondRequired) / determinant
    let secondMultiplier = (secondRequired - normalDot * firstRequired) / determinant
    guard firstMultiplier > 0, secondMultiplier > 0,
          firstMultiplier.isFinite, secondMultiplier.isFinite else {
        return false
    }
    let closestDistanceSquared = firstRequired * firstMultiplier
        + secondRequired * secondMultiplier
    let retainedTangentRadiusSquared = radius * radius * (1 + 1e-5)
    guard closestDistanceSquared.isFinite, retainedTangentRadiusSquared.isFinite else { return false }
    return closestDistanceSquared > retainedTangentRadiusSquared
}

// Independent normalized-plane oracle for the three-active-constraint region. The
// Metal kernel uses an unnormalized cofactor solve; this version normalizes first and
// inverts the symmetric Gram matrix through simd, avoiding a duplicated implementation.
private func sphereStrictlyOutsidePlaneCornerDepth(
    center: SIMD3<Float>,
    radius: Float,
    firstPlane: SIMD4<Float>,
    secondPlane: SIMD4<Float>,
    depthPlane: SIMD4<Float>
) -> Bool {
    guard radius > 0, radius.isFinite,
          center.x.isFinite, center.y.isFinite, center.z.isFinite else {
        return false
    }
    let sourcePlanes = [firstPlane, secondPlane, depthPlane]
    var normals: [SIMD3<Float>] = []
    var required = SIMD3<Float>(repeating: 0)
    for (index, plane) in sourcePlanes.enumerated() {
        guard plane.x.isFinite, plane.y.isFinite, plane.z.isFinite, plane.w.isFinite else {
            return false
        }
        let normal = SIMD3(plane.x, plane.y, plane.z)
        let length = simd_length(normal)
        guard length > 0, length.isFinite else { return false }
        let unit = normal / length
        let distance = (simd_dot(normal, center) + plane.w) / length
        guard distance < 0, distance.isFinite else { return false }
        normals.append(unit)
        required[index] = -distance
    }
    let gram = simd_float3x3(rows: [
        SIMD3(simd_dot(normals[0], normals[0]), simd_dot(normals[0], normals[1]),
              simd_dot(normals[0], normals[2])),
        SIMD3(simd_dot(normals[1], normals[0]), simd_dot(normals[1], normals[1]),
              simd_dot(normals[1], normals[2])),
        SIMD3(simd_dot(normals[2], normals[0]), simd_dot(normals[2], normals[1]),
              simd_dot(normals[2], normals[2]))
    ])
    let determinant = simd_determinant(gram)
    guard determinant.isFinite, determinant > 1e-6 else { return false }
    let multiplier = simd_inverse(gram) * required
    guard multiplier.x > 0, multiplier.y > 0, multiplier.z > 0,
          multiplier.x.isFinite, multiplier.y.isFinite, multiplier.z.isFinite else {
        return false
    }
    let closestDistanceSquared = simd_dot(required, multiplier)
    let retainedTangentRadiusSquared = radius * radius * (1 + 1e-5)
    guard closestDistanceSquared.isFinite, retainedTangentRadiusSquared.isFinite else {
        return false
    }
    return closestDistanceSquared > retainedTangentRadiusSquared
}

private func sphereOutsideClusterSidePlanes(
    center: SIMD3<Float>,
    radius: Float,
    clusterX: Int,
    clusterY: Int,
    width: Int,
    height: Int,
    projection: simd_float4x4
) -> Bool {
    guard let planes = clusterSidePlanes(
        clusterX: clusterX, clusterY: clusterY, width: width, height: height,
        projection: projection
    ) else {
        return false
    }
    return planes.contains { sphereStrictlyOutsidePlane(center: center, radius: radius, plane: $0) }
}

private func sphereOutsideClusterSideWedges(
    center: SIMD3<Float>,
    radius: Float,
    clusterX: Int,
    clusterY: Int,
    width: Int,
    height: Int,
    projection: simd_float4x4
) -> Bool {
    guard let planes = clusterSidePlanes(
        clusterX: clusterX, clusterY: clusterY, width: width, height: height,
        projection: projection
    ) else {
        return false
    }
    let pairs = [(0, 2), (0, 3), (1, 2), (1, 3)]
    return pairs.contains { pair in
        sphereStrictlyOutsidePlaneWedge(
            center: center, radius: radius,
            firstPlane: planes[pair.0], secondPlane: planes[pair.1]
        )
    }
}

private func clusterDepthBoundaries(near: Float = 0.1, far: Float = 100) -> [Float]? {
    guard near > 0, far > near, near.isFinite, far.isFinite else { return nil }
    let scale = Float(depthSlices) / log2(far / near)
    let bias = -log2(near) * scale
    guard scale > 0, scale.isFinite, bias.isFinite else { return nil }
    let boundaries = (0...depthSlices).map { exp2((Float($0) - bias) / scale) }
    guard boundaries.allSatisfy({ $0 > 0 && $0.isFinite }) else { return nil }
    return boundaries
}

// Fragment Z selection clamps both endpoints: slice zero is open toward the camera and
// the final slice is open toward infinity. This oracle deliberately models that exact
// contract while using the normalized two-plane solver independently of the MSL form.
private func sphereOutsideClusterSideDepthEdges(
    center: SIMD3<Float>,
    radius: Float,
    clusterX: Int,
    clusterY: Int,
    clusterZ: Int,
    width: Int,
    height: Int,
    projection: simd_float4x4,
    depthBoundaries: [Float]
) -> Bool {
    guard clusterZ >= 0, clusterZ < depthSlices,
          depthBoundaries.count == depthSlices + 1,
          center.x.isFinite, center.y.isFinite, center.z.isFinite,
          depthBoundaries.allSatisfy({ $0 > 0 && $0.isFinite }),
          let sidePlanes = clusterSidePlanes(
              clusterX: clusterX, clusterY: clusterY, width: width, height: height,
              projection: projection
          ) else {
        return false
    }
    let centerDepth = -center.z
    let depthPlane: SIMD4<Float>
    if clusterZ > 0 && centerDepth < depthBoundaries[clusterZ] {
        depthPlane = SIMD4(0, 0, -1, -depthBoundaries[clusterZ])
    } else if clusterZ + 1 < depthSlices && centerDepth > depthBoundaries[clusterZ + 1] {
        depthPlane = SIMD4(0, 0, 1, depthBoundaries[clusterZ + 1])
    } else {
        return false
    }
    return sidePlanes.contains {
        sphereStrictlyOutsidePlaneWedge(
            center: center, radius: radius, firstPlane: $0, secondPlane: depthPlane
        )
    }
}

private func sphereOutsideClusterCornerDepthVertices(
    center: SIMD3<Float>,
    radius: Float,
    clusterX: Int,
    clusterY: Int,
    clusterZ: Int,
    width: Int,
    height: Int,
    projection: simd_float4x4,
    depthBoundaries: [Float]
) -> Bool {
    guard clusterZ >= 0, clusterZ < depthSlices,
          depthBoundaries.count == depthSlices + 1,
          center.x.isFinite, center.y.isFinite, center.z.isFinite,
          depthBoundaries.allSatisfy({ $0 > 0 && $0.isFinite }),
          let sidePlanes = clusterSidePlanes(
              clusterX: clusterX, clusterY: clusterY, width: width, height: height,
              projection: projection
          ) else {
        return false
    }
    let centerDepth = -center.z
    let depthPlane: SIMD4<Float>
    if clusterZ > 0 && centerDepth < depthBoundaries[clusterZ] {
        depthPlane = SIMD4(0, 0, -1, -depthBoundaries[clusterZ])
    } else if clusterZ + 1 < depthSlices && centerDepth > depthBoundaries[clusterZ + 1] {
        depthPlane = SIMD4(0, 0, 1, depthBoundaries[clusterZ + 1])
    } else {
        return false
    }
    let pairs = [(0, 2), (0, 3), (1, 2), (1, 3)]
    return pairs.contains { pair in
        sphereStrictlyOutsidePlaneCornerDepth(
            center: center,
            radius: radius,
            firstPlane: sidePlanes[pair.0],
            secondPlane: sidePlanes[pair.1],
            depthPlane: depthPlane
        )
    }
}

private func closestPlaneEdgePoint(_ first: SIMD4<Float>, _ second: SIMD4<Float>) -> SIMD3<Float>? {
    let firstNormal = SIMD3(first.x, first.y, first.z)
    let secondNormal = SIMD3(second.x, second.y, second.z)
    let firstSquared = simd_dot(firstNormal, firstNormal)
    let secondSquared = simd_dot(secondNormal, secondNormal)
    let normalDot = simd_dot(firstNormal, secondNormal)
    let determinant = firstSquared * secondSquared - normalDot * normalDot
    guard firstSquared > 0, secondSquared > 0, determinant > firstSquared * secondSquared * 1e-6,
          firstSquared.isFinite, secondSquared.isFinite, normalDot.isFinite, determinant.isFinite else {
        return nil
    }
    let firstRequired = -first.w
    let secondRequired = -second.w
    let firstMultiplier = (firstRequired * secondSquared - normalDot * secondRequired) / determinant
    let secondMultiplier = (secondRequired * firstSquared - normalDot * firstRequired) / determinant
    let point = firstMultiplier * firstNormal + secondMultiplier * secondNormal
    return point.x.isFinite && point.y.isFinite && point.z.isFinite ? point : nil
}

private func closestPlaneCornerPoint(
    _ first: SIMD4<Float>,
    _ second: SIMD4<Float>,
    _ third: SIMD4<Float>
) -> SIMD3<Float>? {
    let normals = simd_float3x3(rows: [
        SIMD3(first.x, first.y, first.z),
        SIMD3(second.x, second.y, second.z),
        SIMD3(third.x, third.y, third.z)
    ])
    let determinant = simd_determinant(normals)
    guard determinant.isFinite, abs(determinant) > 1e-6 else { return nil }
    let point = simd_inverse(normals) * SIMD3(-first.w, -second.w, -third.w)
    return point.x.isFinite && point.y.isFinite && point.z.isFinite ? point : nil
}

private func reference(
    lights: [Light],
    width: Int,
    height: Int,
    indexCapacity: Int,
    preset: UInt32 = 1,
    candidateCapacity: UInt32 = 128,
    projectionOverride: simd_float4x4? = nil
) -> ReferenceResult {
    let gridX = (width + tileSize - 1) / tileSize
    let gridY = (height + tileSize - 1) / tileSize
    let clusterCount = gridX * gridY * depthSlices
    let projection = projectionOverride
        ?? perspective(near: 0.1, far: 100, aspect: Float(width) / Float(height))
    let depthBoundaries = clusterDepthBoundaries()!

    func bounds(for light: Light) -> ReferenceBounds? {
        let view = light.position
        let centerDepth = -view.z
        let nearDepth = max(0.1, centerDepth - light.radius)
        let farDepth = min(100, centerDepth + light.radius)
        if farDepth < nearDepth || centerDepth + light.radius <= 0.1 { return nil }
        var minX = 0
        var minY = 0
        var maxX = gridX
        var maxY = gridY
        let cameraInside = simd_length_squared(view) <= light.radius * light.radius
        if !cameraInside && centerDepth <= light.radius {
            if sphereOutsideSideFrustum(center: view, radius: light.radius, projection: projection) {
                return nil
            }
        } else if !cameraInside {
            var lower = SIMD2<Float>(repeating: .infinity)
            var upper = SIMD2<Float>(repeating: -.infinity)
            var projectionValid = true
            var requiresFullScreenBounds = false
            for corner in 0..<8 {
                let sign = SIMD3<Float>(
                    corner & 1 == 0 ? -1 : 1,
                    corner & 2 == 0 ? -1 : 1,
                    corner & 4 == 0 ? -1 : 1
                )
                let clip = projection * SIMD4(view + sign * light.radius, 1)
                if !clip.x.isFinite || !clip.y.isFinite
                        || !clip.z.isFinite || !clip.w.isFinite {
                    projectionValid = false
                    break
                }
                if clip.w <= 1e-6 {
                    requiresFullScreenBounds = true
                    break
                }
                let ndc = SIMD2(clip.x / clip.w, clip.y / clip.w)
                if !ndc.x.isFinite || !ndc.y.isFinite {
                    projectionValid = false
                    break
                }
                lower = simd_min(lower, ndc)
                upper = simd_max(upper, ndc)
            }
            if !projectionValid { return nil }
            if requiresFullScreenBounds {
                if sphereOutsideSideFrustum(center: view, radius: light.radius, projection: projection) {
                    return nil
                }
            } else {
                if upper.x < -1 || lower.x > 1 || upper.y < -1 || lower.y > 1 { return nil }
                func pixel(_ ndc: SIMD2<Float>) -> SIMD2<Float> {
                    // SPIRV-Cross flips vertex clip Y for Metal. Metal's top-left
                    // viewport transform then yields the original GLSL bottom-left
                    // gl_FragCoord value, so both components use a positive scale.
                    let mapped = ndc * SIMD2(repeating: 0.5) + SIMD2(repeating: 0.5)
                    return simd_clamp(mapped * SIMD2(Float(width), Float(height)), .zero, SIMD2(Float(width), Float(height)))
                }
                let a = pixel(lower)
                let b = pixel(upper)
                let pixelMin = simd_min(a, b)
                let pixelMax = simd_max(a, b)
                minX = min(Int(floor(pixelMin.x / Float(tileSize))), gridX - 1)
                minY = min(Int(floor(pixelMin.y / Float(tileSize))), gridY - 1)
                maxX = min(Int(ceil(pixelMax.x / Float(tileSize))), gridX)
                maxY = min(Int(ceil(pixelMax.y / Float(tileSize))), gridY)
            }
        }
        let minZ = depthSlice(nearDepth, near: 0.1, far: 100)
        let maxZ = min(depthSlice(farDepth, near: 0.1, far: 100) + 1, depthSlices)
        if maxX <= minX || maxY <= minY || maxZ <= minZ { return nil }
        return ReferenceBounds(
            lower: SIMD3(minX, minY, minZ),
            upper: SIMD3(maxX, maxY, maxZ)
        )
    }

    let contract = presetContract(preset)
    let bounded = lights.map { bounds(for: $0) }

    var raw = [[UInt32]](repeating: [], count: clusterCount)
    for (lightIndex, optionalBounds) in bounded.enumerated() {
        guard let lightBounds = optionalBounds else { continue }
        for z in lightBounds.lower.z..<lightBounds.upper.z {
            for y in lightBounds.lower.y..<lightBounds.upper.y {
                for x in lightBounds.lower.x..<lightBounds.upper.x {
                    if sphereOutsideClusterSidePlanes(
                        center: lights[lightIndex].position,
                        radius: lights[lightIndex].radius,
                        clusterX: x,
                        clusterY: y,
                        width: width,
                        height: height,
                        projection: projection
                    ) {
                        continue
                    }
                    if sphereOutsideClusterSideWedges(
                        center: lights[lightIndex].position,
                        radius: lights[lightIndex].radius,
                        clusterX: x,
                        clusterY: y,
                        width: width,
                        height: height,
                        projection: projection
                    ) {
                        continue
                    }
                    if sphereOutsideClusterSideDepthEdges(
                        center: lights[lightIndex].position,
                        radius: lights[lightIndex].radius,
                        clusterX: x,
                        clusterY: y,
                        clusterZ: z,
                        width: width,
                        height: height,
                        projection: projection,
                        depthBoundaries: depthBoundaries
                    ) {
                        continue
                    }
                    if sphereOutsideClusterCornerDepthVertices(
                        center: lights[lightIndex].position,
                        radius: lights[lightIndex].radius,
                        clusterX: x,
                        clusterY: y,
                        clusterZ: z,
                        width: width,
                        height: height,
                        projection: projection,
                        depthBoundaries: depthBoundaries
                    ) {
                        continue
                    }
                    raw[(z * gridY + y) * gridX + x].append(UInt32(lightIndex))
                }
            }
        }
    }
    var headers: [SIMD2<UInt32>] = []
    var indices: [UInt32] = []
    var requested = 0
    var overflowClusters = 0
    var perClusterDrops = 0
    for candidates in raw {
        let selectedCount = min(candidates.count, contract.perClusterCap)
        requested += selectedCount
        if candidates.count > selectedCount {
            overflowClusters += 1
            perClusterDrops += candidates.count - selectedCount
        }
        let offset = min(requested - selectedCount, indexCapacity)
        let accepted = min(selectedCount, max(0, indexCapacity - offset))
        headers.append(SIMD2(UInt32(offset), UInt32(accepted)))
        if accepted > 0 {
            indices.append(contentsOf: candidates.prefix(accepted))
        }
    }
    return ReferenceResult(
        headers: headers,
        indices: indices,
        requestedIndices: UInt32(requested),
        overflowClusters: UInt32(overflowClusters),
        perClusterDrops: UInt32(perClusterDrops),
        indexCapacityDrops: UInt32(max(0, requested - indexCapacity)),
        admissionRejectedLights: 0,
        uploadedLightCount: UInt32(lights.count),
        admittedLightCount: UInt32(lights.count),
        admittedLightCap: candidateCapacity,
        perClusterCap: UInt32(contract.perClusterCap),
        workloadBudget: 0,
        indexCapacity: UInt32(indexCapacity)
    )
}

private func runGpu(
    api: NativeApi,
    context: UnsafeMutableRawPointer,
    queue: MTLCommandQueue,
    generation: UInt64,
    frameId: UInt64,
    submitIndex: UInt64,
    width: Int,
    height: Int,
    lights: [Light],
    hdr: Bool,
    preset: UInt32 = 1,
    batchFlags: UInt32 = 1,
    projectionOverride: simd_float4x4? = nil
) throws -> GpuResult {
    try setFrame(api, framePacket(
        generation: generation,
        frameId: frameId,
        submitIndex: submitIndex,
        width: UInt32(width),
        height: UInt32(height),
        lightCount: UInt32(lights.count),
        hdr: hdr,
        preset: preset,
        projectionOverride: projectionOverride
    ))
    let packet = batchPacket(
        generation: generation,
        frameId: frameId,
        submitIndex: submitIndex,
        lights: lights,
        flags: batchFlags
    )
    guard let commandBuffer = queue.makeCommandBuffer() else {
        throw ValidationFailure.message("Could not create clustered-lighting command buffer")
    }
    try require(upload(api, context: context, commandBuffer: commandBuffer, packet: packet) == 1,
                "Valid lighting batch was rejected")

    var nativeBuffers: [MTLBuffer] = []
    var readbacks: [MTLBuffer] = []
    for kind in 0...5 {
        let source = try borrowedBuffer(api, context: context, kind: Int32(kind))
        guard let readback = source.device.makeBuffer(length: source.length, options: .storageModeShared) else {
            throw ValidationFailure.message("Could not allocate buffer-kind \(kind) readback")
        }
        nativeBuffers.append(source)
        readbacks.append(readback)
    }
    guard let copy = commandBuffer.makeBlitCommandEncoder() else {
        throw ValidationFailure.message("Could not create clustered-lighting validation readback")
    }
    for index in nativeBuffers.indices {
        copy.copy(
            from: nativeBuffers[index],
            sourceOffset: 0,
            to: readbacks[index],
            destinationOffset: 0,
            size: nativeBuffers[index].length
        )
    }
    copy.endEncoding()
    let completion = DispatchSemaphore(value: 0)
    commandBuffer.addCompletedHandler { _ in completion.signal() }
    commandBuffer.commit()
    try require(
        completion.wait(timeout: .now() + .seconds(10)) == .success,
        "Clustered-lighting GPU work exceeded the 10-second safety timeout"
    )
    try require(
        commandBuffer.status == .completed,
        "Clustered-lighting GPU work failed: \(commandBuffer.error?.localizedDescription ?? "unknown")"
    )
    for (kind, readback) in readbacks.enumerated() {
        let bytes = readback.contents().bindMemory(to: UInt8.self, capacity: readback.length)
        try require(
            ((readback.length - guardBytes)..<readback.length).allSatisfy { bytes[$0] == 0xa5 },
            "Buffer kind \(kind) guard sentinel was corrupted"
        )
    }
    let headerValues = readbacks[1].contents().bindMemory(
        to: UInt32.self,
        capacity: Int(api.contextBufferBytes(context, 1) / 4)
    )
    let clusterCount = Int(api.contextBufferBytes(context, 1) / 8)
    let headers = (0..<clusterCount).map {
        SIMD2(headerValues[$0 * 2], headerValues[$0 * 2 + 1])
    }
    let indexCapacity = api.contextBufferBytes(context, 2) / UInt64(MemoryLayout<UInt32>.stride)
    var accepted: UInt64 = 0
    for (cluster, header) in headers.enumerated() {
        let offset = UInt64(header.x)
        let count = UInt64(header.y)
        try require(offset <= indexCapacity,
                    "GPU cluster header \(cluster) offset exceeds index-buffer capacity")
        let remaining = indexCapacity - offset
        try require(count <= remaining,
                    "GPU cluster header \(cluster) count exceeds remaining index capacity")
        accepted = max(accepted, offset + count)
    }
    // Empty submissions clear every header, so their bounded readback remains zero.
    try require(!lights.isEmpty || accepted == 0,
                "Empty submission exposed a non-zero bounded compact-index range")
    let acceptedCount = Int(accepted)
    let indexValues = readbacks[2].contents().bindMemory(
        to: UInt32.self,
        capacity: max(acceptedCount, 1)
    )
    let indices = (0..<acceptedCount).map { indexValues[$0] }
    let maskWordCount = Int(api.contextBufferBytes(context, 5) / 4)
    let maskValues = readbacks[5].contents().bindMemory(
        to: UInt32.self,
        capacity: max(maskWordCount, 1)
    )
    let masks = (0..<maskWordCount).map { maskValues[$0] }
    let lightByteCount = Int(api.contextBufferBytes(context, 0))
    let lightPointer = readbacks[0].contents().bindMemory(to: UInt8.self, capacity: lightByteCount)
    let gpuLights = Array(UnsafeBufferPointer(start: lightPointer, count: lightByteCount))
    let paramsPointer = readbacks[3].contents().bindMemory(to: UInt8.self, capacity: 256)
    let params = Array(UnsafeBufferPointer(start: paramsPointer, count: 256))
    var stats = [UInt8](repeating: 0, count: 128)
    let statsStatus = stats.withUnsafeMutableBytes {
        api.lastCompletedStats(context, $0.baseAddress, UInt64($0.count))
    }
    try require(statsStatus == 1, "Asynchronous completed cluster statistics are unavailable")
    return GpuResult(
        lights: gpuLights,
        headers: headers,
        indices: indices,
        masks: masks,
        params: params,
        stats: stats
    )
}

private func requireMaskMatches(
    _ gpu: GpuResult,
    _ reference: ReferenceResult,
    context: String
) throws {
    let activeWords = min(
        (Int(reference.admittedLightCount) + 31) / 32,
        membershipWordsPerCluster
    )
    for (cluster, header) in reference.headers.enumerated() {
        var selected: [UInt32] = []
        for word in 0..<activeWords {
            var membership = gpu.masks[cluster * membershipWordsPerCluster + word]
            while membership != 0 {
                let bit = UInt32(membership.trailingZeroBitCount)
                let lightIndex = UInt32(word * 32) + bit
                try require(lightIndex < reference.admittedLightCount,
                            "\(context): membership mask references an unadmitted light")
                if selected.count < Int(reference.perClusterCap) {
                    selected.append(lightIndex)
                }
                membership &= membership - 1
            }
        }
        let start = Int(header.x)
        let end = start + Int(header.y)
        try require(selected == Array(reference.indices[start..<end]),
                    "\(context): deterministic mask prefix differs in cluster \(cluster)")
    }
    try require(readUInt32(gpu.params, at: 140) == reference.admittedLightCount,
                "\(context): compute/direct-shader candidate light count mismatch")
    try require(readUInt32(gpu.stats, at: 24) == reference.uploadedLightCount,
                "\(context): original uploaded-light telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 32) == reference.requestedIndices,
                "\(context): accepted-membership telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 36) == reference.requestedIndices,
                "\(context): requested-membership telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 40) == reference.overflowClusters,
                "\(context): overflow-cluster telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 44) == reference.perClusterDrops,
                "\(context): per-cluster-drop telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 48) == 0,
                "\(context): full-capacity scratch oracle reported an index-capacity drop")
    try require(readUInt32(gpu.stats, at: 52) == reference.admissionRejectedLights,
                "\(context): deterministic workload-admission telemetry mismatch")
}

private func activeMaskPayload(_ gpu: GpuResult, admittedLightCount: UInt32) -> [UInt32] {
    let activeWords = min(
        (Int(admittedLightCount) + 31) / 32,
        membershipWordsPerCluster
    )
    guard activeWords > 0 else { return [] }
    let clusterCount = gpu.masks.count / membershipWordsPerCluster
    return (0..<clusterCount).flatMap { cluster in
        let start = cluster * membershipWordsPerCluster
        return Array(gpu.masks[start..<(start + activeWords)])
    }
}

private func requireMatches(
    _ gpu: GpuResult,
    _ reference: ReferenceResult,
    context: String
) throws {
    try require(gpu.headers == reference.headers, "\(context): cluster headers differ from CPU reference")
    try require(gpu.indices == reference.indices, "\(context): compact indices differ from CPU reference")
    for (cluster, header) in gpu.headers.enumerated() {
        try require(header.x <= reference.indexCapacity,
                    "\(context): cluster \(cluster) offset exceeds index capacity")
        try require(header.y <= reference.perClusterCap,
                    "\(context): cluster \(cluster) count exceeds preset cap")
        try require(header.y <= reference.indexCapacity - header.x,
                    "\(context): cluster \(cluster) range exceeds index capacity")
        let start = Int(header.x)
        let end = start + Int(header.y)
        try require(end <= gpu.indices.count,
                    "\(context): cluster \(cluster) range exceeds compact readback")
        try require(gpu.indices[start..<end].allSatisfy { $0 < reference.admittedLightCount },
                    "\(context): cluster \(cluster) references a non-candidate light")
    }
    try require(readUInt32(gpu.params, at: 140) == reference.admittedLightCount,
                "\(context): compute/direct-shader candidate count mismatch")
    try require(readUInt32(gpu.params, at: 152) == reference.perClusterCap,
                "\(context): preset per-cluster cap parameter mismatch")
    try require(readUInt32(gpu.params, at: 208) == reference.workloadBudget,
                "\(context): removed global workload budget was reintroduced")
    try require(readUInt32(gpu.params, at: 224) == reference.uploadedLightCount,
                "\(context): original uploaded count was not retained in params")
    try require(readUInt32(gpu.params, at: 228) == reference.admittedLightCap,
                "\(context): total candidate-cap parameter mismatch")
    try require(readUInt32(gpu.stats, at: 24) == reference.uploadedLightCount,
                "\(context): original uploaded-light telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 32) == UInt32(reference.indices.count),
                "\(context): accepted-index telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 36) == reference.requestedIndices,
                "\(context): requested-index telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 40) == reference.overflowClusters,
                "\(context): overflow-cluster telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 44) == reference.perClusterDrops,
                "\(context): per-cluster-drop telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 48) == reference.indexCapacityDrops,
                "\(context): index-capacity telemetry mismatch")
    try require(readUInt32(gpu.stats, at: 52) == reference.admissionRejectedLights,
                "\(context): global candidate admission rejected a valid upload")
    try require(readUInt32(gpu.stats, at: 52) <= reference.uploadedLightCount,
                "\(context): workload rejections exceed the original upload count")
    try require(readUInt32(gpu.stats, at: 68) <= reference.perClusterCap
                    && readUInt32(gpu.stats, at: 68) <= UInt32(clusterCap),
                "\(context): maximum occupancy exceeds the preset or shader hard cap")
    let maximumOccupancy = readUInt32(gpu.stats, at: 68)
    try require(
        [56, 60, 64].allSatisfy { readUInt32(gpu.stats, at: $0) <= maximumOccupancy },
        "\(context): occupancy quantile exceeds maximum occupancy"
    )
    try require(readUInt32(gpu.stats, at: 80) == 1,
                "\(context): statistics lost the SDR/HDR-independent contract flag")
}

private func loadApi(_ library: UnsafeMutableRawPointer) throws -> NativeApi {
    func symbol<T>(_ name: String, as type: T.Type) throws -> T {
        guard let value = dlsym(library, name) else {
            throw ValidationFailure.message("Missing native symbol \(name)")
        }
        return unsafeBitCast(value, to: type)
    }
    return try NativeApi(
        abiVersion: symbol("metallum_lighting_batch_abi_version_v1", as: NativeAbiVersion.self),
        layout: symbol("metallum_lighting_layout_v1", as: NativeLayout.self),
        initialize: symbol("metallum_init_pipelines", as: NativeInit.self),
        setFrame: symbol("metallum_set_frame_state_v3", as: NativeSetFrame.self),
        createContext: symbol("metallum_lighting_create_context_v1", as: NativeCreateContext.self),
        releaseContext: symbol("metallum_lighting_release_context_v1", as: NativeReleaseContext.self),
        uploadAndBuild: symbol("metallum_lighting_upload_and_build_v1", as: NativeUploadAndBuild.self),
        contextBuffer: symbol("metallum_lighting_context_buffer_v1", as: NativeContextBuffer.self),
        contextBufferBytes: symbol("metallum_lighting_context_buffer_bytes_v1", as: NativeContextBufferBytes.self),
        lastCompletedStats: symbol("metallum_lighting_last_completed_stats_v1", as: NativeLastCompletedStats.self)
    )
}

private func deterministicLights(count: Int, dense: Bool = false) -> [Light] {
    (0..<count).map { index in
        if dense {
            return Light(
                position: SIMD3(0, 0, -8),
                radius: 20,
                color: SIMD3(1, 0.5, 0.25),
                intensity: 4,
                stableId: UInt64(index + 1),
                flags: 0
            )
        }
        let x = Float((index * 17) % 13 - 6) * 0.45
        let y = Float((index * 11) % 9 - 4) * 0.35
        let z = -Float(3 + (index * 7) % 42)
        return Light(
            position: SIMD3(x, y, z),
            radius: Float(1 + (index % 8)),
            color: SIMD3(Float((index % 5) + 1) / 5, 0.6, 0.3),
            intensity: Float((index % 7) + 1),
            stableId: UInt64(index + 1),
            flags: 0
        )
    }
}

private func clusterOverflowLights(count: Int) -> [Light] {
    (0..<count).map { index in
        Light(
            position: SIMD3(0, 0, -8),
            radius: 0.25,
            color: SIMD3(1, 0.5, 0.25),
            intensity: 4,
            stableId: UInt64(index + 1),
            flags: 0
        )
    }
}

@main
private enum LightClusterValidationMain {
    static func main() {
        do {
            try require(CommandLine.arguments.count == 2,
                        "Usage: LightClusterValidation <libmetallum.dylib>")
            guard let library = dlopen(CommandLine.arguments[1], RTLD_NOW | RTLD_LOCAL) else {
                let detail = dlerror().map { String(cString: $0) } ?? "unknown"
                throw ValidationFailure.message("Could not load native library: \(detail)")
            }
            defer { dlclose(library) }
            let api = try loadApi(library)
            try require(api.abiVersion() == 1, "Unexpected native lighting ABI version")
            var layout = [UInt8](repeating: 0, count: 128)
            try require(layout.withUnsafeMutableBytes {
                api.layout($0.baseAddress, UInt64($0.count))
            } == 1, "Native lighting layout descriptor is unavailable")
            let expectedLayout: [UInt32] = [
                1, 128, 64, 48, 256, 8, 512, 4, 256, 3,
                UInt32(tileSize), UInt32(depthSlices), 256,
                0, 64, 128, 144, 160, 176, 192, 208, 224, 240,
                27, 28, 29, 30, 64
            ]
            try require(expectedLayout.enumerated().allSatisfy {
                readUInt32(layout, at: $0.offset * 4) == $0.element
            }, "Native/Java lighting layout offsets or binding slots diverged")
            let scale = Float(depthSlices) / log2(100 / 0.1)
            let bias = -log2(0.1 as Float) * scale
            for boundary in 0...depthSlices {
                let depth = 0.1 * pow(2, Float(boundary) / scale)
                let shared = min(max(Int(floor(log2(max(depth, 0.1)) * scale + bias)), 0), depthSlices - 1)
                try require(shared == depthSlice(depth, near: 0.1, far: 100),
                            "Count/fragment logarithmic Z boundary diverged at \(boundary)")
            }
            guard let device = MTLCreateSystemDefaultDevice(),
                  let queue = device.makeCommandQueue() else {
                throw ValidationFailure.message("No Metal device or queue is available")
            }
            try require(api.initialize(objectPointer(device as AnyObject)) > 0,
                        "Native built-in pipeline initialization failed")

            // Side-plane oracle fixtures are intentionally independent of the GPU reference
            // build. A tangent sphere is retained; moving it strictly outside each individual
            // plane rejects it. Edge/corner contact must also remain a member.
            let planeWidth = 256
            let planeHeight = 192
            let planeClusterX = 1
            let planeClusterY = 1
            let identityProjection = matrix_identity_float4x4
            guard let explicitPlanes = clusterSidePlanes(
                clusterX: planeClusterX,
                clusterY: planeClusterY,
                width: planeWidth,
                height: planeHeight,
                projection: identityProjection
            ) else {
                throw ValidationFailure.message("Could not construct explicit cluster side planes")
            }
            let fixtureRadius: Float = 0.25
            for (planeIndex, plane) in explicitPlanes.enumerated() {
                let normal = SIMD3(plane.x, plane.y, plane.z)
                let normalSquared = simd_dot(normal, normal)
                let normalLength = simd_length(normal)
                let tangentDistance = -fixtureRadius * normalLength
                let tangentCenter = normal * ((tangentDistance - plane.w) / normalSquared)
                let outsideCenter = normal * ((tangentDistance - 0.125 - plane.w) / normalSquared)
                try require(
                    !sphereStrictlyOutsidePlane(
                        center: tangentCenter, radius: fixtureRadius, plane: plane
                    ),
                    "Tangent sphere was rejected by side plane \(planeIndex)"
                )
                try require(
                    sphereStrictlyOutsidePlane(
                        center: outsideCenter, radius: fixtureRadius, plane: plane
                    ),
                    "Strictly outside sphere was retained by side plane \(planeIndex)"
                )
                try require(
                    !sphereOutsideClusterSideWedges(
                        center: outsideCenter, radius: fixtureRadius,
                        clusterX: planeClusterX, clusterY: planeClusterY,
                        width: planeWidth, height: planeHeight, projection: identityProjection
                    ),
                    "Single-plane miss was incorrectly claimed by a corner wedge \(planeIndex)"
                )
            }
            let edgeCenter = SIMD3<Float>(-0.5, 0, 0)
            let cornerCenter = SIMD3<Float>(-0.5, -1.0 / 3.0, 0)
            try require(
                !sphereOutsideClusterSidePlanes(
                    center: edgeCenter, radius: fixtureRadius,
                    clusterX: planeClusterX, clusterY: planeClusterY,
                    width: planeWidth, height: planeHeight, projection: identityProjection
                ),
                "Tile-edge tangent sphere was rejected"
            )
            try require(
                !sphereOutsideClusterSidePlanes(
                    center: cornerCenter, radius: fixtureRadius,
                    clusterX: planeClusterX, clusterY: planeClusterY,
                    width: planeWidth, height: planeHeight, projection: identityProjection
                ),
                "Tile-corner tangent sphere was rejected"
            )
            let cornerFixtures: [(String, Int, Int, SIMD3<Float>)] = [
                ("left-bottom", 0, 2, SIMD3(-0.5, -1.0 / 3.0, 0)),
                ("left-top", 0, 3, SIMD3(-0.5, 1.0 / 3.0, 0)),
                ("right-bottom", 1, 2, SIMD3(0, -1.0 / 3.0, 0)),
                ("right-top", 1, 3, SIMD3(0, 1.0 / 3.0, 0))
            ]
            for (name, firstIndex, secondIndex, corner) in cornerFixtures {
                let firstNormal = SIMD3(
                    explicitPlanes[firstIndex].x,
                    explicitPlanes[firstIndex].y,
                    explicitPlanes[firstIndex].z
                )
                let secondNormal = SIMD3(
                    explicitPlanes[secondIndex].x,
                    explicitPlanes[secondIndex].y,
                    explicitPlanes[secondIndex].z
                )
                let inward = simd_normalize(
                    firstNormal / simd_length(firstNormal)
                        + secondNormal / simd_length(secondNormal)
                )
                let insideCenter = corner + inward * 0.05
                let tangentCenter = corner - inward * fixtureRadius
                // This remains inside both individual radius-expanded planes: only the
                // two-plane constrained distance may safely reject it.
                let outsideCenter = corner - inward * (fixtureRadius + 0.05)
                try require(
                    !sphereOutsideClusterSideWedges(
                        center: insideCenter, radius: fixtureRadius,
                        clusterX: planeClusterX, clusterY: planeClusterY,
                        width: planeWidth, height: planeHeight, projection: identityProjection
                    ),
                    "Inside corner sphere was rejected by \(name) wedge"
                )
                try require(
                    !sphereOutsideClusterSideWedges(
                        center: tangentCenter, radius: fixtureRadius,
                        clusterX: planeClusterX, clusterY: planeClusterY,
                        width: planeWidth, height: planeHeight, projection: identityProjection
                    ),
                    "Tangent corner sphere was rejected by \(name) wedge"
                )
                try require(
                    !sphereOutsideClusterSidePlanes(
                        center: outsideCenter, radius: fixtureRadius,
                        clusterX: planeClusterX, clusterY: planeClusterY,
                        width: planeWidth, height: planeHeight, projection: identityProjection
                    ) && sphereOutsideClusterSideWedges(
                        center: outsideCenter, radius: fixtureRadius,
                        clusterX: planeClusterX, clusterY: planeClusterY,
                        width: planeWidth, height: planeHeight, projection: identityProjection
                    ),
                    "Corner-only miss was not isolated by \(name) wedge"
                )
            }
            var invalidProjection = identityProjection
            invalidProjection[0][0] = .nan
            try require(
                !sphereOutsideClusterSidePlanes(
                    center: SIMD3(0, -9, 0), radius: fixtureRadius,
                    clusterX: planeClusterX, clusterY: planeClusterY,
                    width: planeWidth, height: planeHeight, projection: invalidProjection
                ) && !sphereOutsideClusterSidePlanes(
                    center: .zero, radius: .infinity,
                    clusterX: planeClusterX, clusterY: planeClusterY,
                    width: planeWidth, height: planeHeight, projection: identityProjection
                ) && !sphereOutsideClusterSidePlanes(
                    center: .zero, radius: fixtureRadius,
                    clusterX: planeClusterX, clusterY: planeClusterY,
                    width: 0, height: planeHeight, projection: identityProjection
                ),
                "Invalid side-plane input was not fail-open"
            )
            let parallelPlane = SIMD4<Float>(1, 0, 0, 0)
            let nearParallelPlane = SIMD4<Float>(1, 0.0001, 0, 0)
            try require(
                !sphereStrictlyOutsidePlaneWedge(
                    center: SIMD3(-1, -1, 0), radius: fixtureRadius,
                    firstPlane: SIMD4(.nan, 0, 0, 0), secondPlane: parallelPlane
                ) && !sphereStrictlyOutsidePlaneWedge(
                    center: SIMD3(-1, -1, 0), radius: fixtureRadius,
                    firstPlane: parallelPlane, secondPlane: parallelPlane
                ) && !sphereStrictlyOutsidePlaneWedge(
                    center: SIMD3(-1, -1, 0), radius: fixtureRadius,
                    firstPlane: parallelPlane, secondPlane: nearParallelPlane
                ),
                "Invalid, parallel, or near-parallel wedge was not fail-open"
            )

            // A sphere may cross one side plane and one valid logarithmic-depth plane
            // independently while missing their shared edge. Cover all four side planes
            // at both lower/upper interior-Z boundaries with inside/tangent/edge-only
            // fixtures. This stays independent of the shader's unnormalized math.
            try require(clusterDepthBoundaries() != nil,
                        "Could not construct logarithmic depth boundaries")
            let depthBoundaries = clusterDepthBoundaries()!
            let interiorSlice = 2
            let depthFixtures: [(String, SIMD4<Float>)] = [
                ("lower", SIMD4(0, 0, -1, -depthBoundaries[interiorSlice])),
                ("upper", SIMD4(0, 0, 1, depthBoundaries[interiorSlice + 1]))
            ]
            for (depthName, depthPlane) in depthFixtures {
                for (sideIndex, sidePlane) in explicitPlanes.enumerated() {
                    guard let edge = closestPlaneEdgePoint(sidePlane, depthPlane) else {
                        throw ValidationFailure.message("Could not construct \(depthName) edge \(sideIndex)")
                    }
                    let sideNormal = SIMD3(sidePlane.x, sidePlane.y, sidePlane.z)
                    let depthNormal = SIMD3(depthPlane.x, depthPlane.y, depthPlane.z)
                    let outward = -simd_normalize(
                        sideNormal / simd_length(sideNormal)
                            + depthNormal / simd_length(depthNormal)
                    )
                    let insideCenter = edge - outward * 0.05
                    let tangentCenter = edge + outward * fixtureRadius
                    let outsideCenter = edge + outward * (fixtureRadius + 0.05)
                    try require(
                        !sphereOutsideClusterSideDepthEdges(
                            center: insideCenter, radius: fixtureRadius,
                            clusterX: planeClusterX, clusterY: planeClusterY, clusterZ: interiorSlice,
                            width: planeWidth, height: planeHeight, projection: identityProjection,
                            depthBoundaries: depthBoundaries
                        ) && !sphereOutsideClusterSideDepthEdges(
                            center: tangentCenter, radius: fixtureRadius,
                            clusterX: planeClusterX, clusterY: planeClusterY, clusterZ: interiorSlice,
                            width: planeWidth, height: planeHeight, projection: identityProjection,
                            depthBoundaries: depthBoundaries
                        ),
                        "Inside/tangent \(depthName) side-depth edge was rejected for side \(sideIndex)"
                    )
                    try require(
                        !sphereOutsideClusterSidePlanes(
                            center: outsideCenter, radius: fixtureRadius,
                            clusterX: planeClusterX, clusterY: planeClusterY,
                            width: planeWidth, height: planeHeight, projection: identityProjection
                        ) && !sphereOutsideClusterSideWedges(
                            center: outsideCenter, radius: fixtureRadius,
                            clusterX: planeClusterX, clusterY: planeClusterY,
                            width: planeWidth, height: planeHeight, projection: identityProjection
                        ) && sphereOutsideClusterSideDepthEdges(
                            center: outsideCenter, radius: fixtureRadius,
                            clusterX: planeClusterX, clusterY: planeClusterY, clusterZ: interiorSlice,
                            width: planeWidth, height: planeHeight, projection: identityProjection,
                            depthBoundaries: depthBoundaries
                        ),
                        "\(depthName) edge-only miss was not isolated for side \(sideIndex)"
                    )
                }
            }
            // Pair-wise wedge tests still retain a sphere whose closest point lies on
            // the three-plane corner/depth vertex. Isolate that active-set region for
            // every XY corner at both interior depth boundaries.
            for (depthName, depthPlane) in depthFixtures {
                for (cornerName, firstIndex, secondIndex, _) in cornerFixtures {
                    let firstPlane = explicitPlanes[firstIndex]
                    let secondPlane = explicitPlanes[secondIndex]
                    guard let vertex = closestPlaneCornerPoint(
                        firstPlane, secondPlane, depthPlane
                    ) else {
                        throw ValidationFailure.message(
                            "Could not construct \(cornerName) \(depthName) depth vertex"
                        )
                    }
                    let outward = -simd_normalize(
                        SIMD3(firstPlane.x, firstPlane.y, firstPlane.z)
                            / simd_length(SIMD3(firstPlane.x, firstPlane.y, firstPlane.z))
                        + SIMD3(secondPlane.x, secondPlane.y, secondPlane.z)
                            / simd_length(SIMD3(secondPlane.x, secondPlane.y, secondPlane.z))
                        + SIMD3(depthPlane.x, depthPlane.y, depthPlane.z)
                            / simd_length(SIMD3(depthPlane.x, depthPlane.y, depthPlane.z))
                    )
                    let insideCenter = vertex - outward * 0.05
                    let tangentCenter = vertex + outward * fixtureRadius
                    let outsideCenter = vertex + outward * (fixtureRadius + 0.05)
                    try require(
                        !sphereStrictlyOutsidePlaneCornerDepth(
                            center: insideCenter, radius: fixtureRadius,
                            firstPlane: firstPlane, secondPlane: secondPlane,
                            depthPlane: depthPlane
                        ) && !sphereStrictlyOutsidePlaneCornerDepth(
                            center: tangentCenter, radius: fixtureRadius,
                            firstPlane: firstPlane, secondPlane: secondPlane,
                            depthPlane: depthPlane
                        ),
                        "Inside/tangent \(cornerName) \(depthName) depth vertex was rejected"
                    )
                    try require(
                        !sphereStrictlyOutsidePlaneWedge(
                            center: outsideCenter, radius: fixtureRadius,
                            firstPlane: firstPlane, secondPlane: secondPlane
                        ) && !sphereStrictlyOutsidePlaneWedge(
                            center: outsideCenter, radius: fixtureRadius,
                            firstPlane: firstPlane, secondPlane: depthPlane
                        ) && !sphereStrictlyOutsidePlaneWedge(
                            center: outsideCenter, radius: fixtureRadius,
                            firstPlane: secondPlane, secondPlane: depthPlane
                        ) && sphereStrictlyOutsidePlaneCornerDepth(
                            center: outsideCenter, radius: fixtureRadius,
                            firstPlane: firstPlane, secondPlane: secondPlane,
                            depthPlane: depthPlane
                        ),
                        "Trihedral-only miss was not isolated for \(cornerName) \(depthName)"
                    )
                }
            }
            try require(
                !sphereStrictlyOutsidePlaneCornerDepth(
                    center: SIMD3(-0.7, -0.7, -0.7), radius: fixtureRadius,
                    firstPlane: SIMD4(.nan, 0, 0, 0),
                    secondPlane: SIMD4(0, 1, 0, 0),
                    depthPlane: SIMD4(0, 0, 1, 0)
                ) && !sphereStrictlyOutsidePlaneCornerDepth(
                    center: SIMD3(-0.7, -0.7, -0.7), radius: fixtureRadius,
                    firstPlane: SIMD4(1, 0, 0, 0),
                    secondPlane: SIMD4(1, 0.0001, 0, 0),
                    depthPlane: SIMD4(0, 0, 1, 0)
                ),
                "Invalid or near-singular trihedral input was not fail-open"
            )
            // Endpoint clamps are part of the fragment ABI, not an approximation: a
            // hypothetical lower plane for slice 0 or upper plane for the final slice
            // must never be used by this cull.
            let clampSide = explicitPlanes[0]
            let firstClampPlane = SIMD4<Float>(0, 0, -1, -depthBoundaries[0])
            let lastClampPlane = SIMD4<Float>(0, 0, 1, depthBoundaries[depthSlices])
            for (clusterZ, clampPlane, name) in [
                (0, firstClampPlane, "first"),
                (depthSlices - 1, lastClampPlane, "last")
            ] {
                guard let edge = closestPlaneEdgePoint(clampSide, clampPlane) else {
                    throw ValidationFailure.message("Could not construct \(name) clamp edge")
                }
                let outward = -simd_normalize(
                    SIMD3(clampSide.x, clampSide.y, clampSide.z) / simd_length(SIMD3(clampSide.x, clampSide.y, clampSide.z))
                        + SIMD3(clampPlane.x, clampPlane.y, clampPlane.z) / simd_length(SIMD3(clampPlane.x, clampPlane.y, clampPlane.z))
                )
                try require(
                    !sphereOutsideClusterSideDepthEdges(
                        center: edge + outward * (fixtureRadius + 0.05), radius: fixtureRadius,
                        clusterX: planeClusterX, clusterY: planeClusterY, clusterZ: clusterZ,
                        width: planeWidth, height: planeHeight, projection: identityProjection,
                        depthBoundaries: depthBoundaries
                    ),
                    "\(name) clamp endpoint was incorrectly treated as a depth half-space"
                )
                let clampCornerFirst = explicitPlanes[0]
                let clampCornerSecond = explicitPlanes[2]
                guard let vertex = closestPlaneCornerPoint(
                    clampCornerFirst, clampCornerSecond, clampPlane
                ) else {
                    throw ValidationFailure.message(
                        "Could not construct \(name) clamp corner-depth vertex"
                    )
                }
                let cornerOutward = -simd_normalize(
                    SIMD3(clampCornerFirst.x, clampCornerFirst.y, clampCornerFirst.z)
                        / simd_length(SIMD3(
                            clampCornerFirst.x, clampCornerFirst.y, clampCornerFirst.z
                        ))
                    + SIMD3(clampCornerSecond.x, clampCornerSecond.y, clampCornerSecond.z)
                        / simd_length(SIMD3(
                            clampCornerSecond.x, clampCornerSecond.y, clampCornerSecond.z
                        ))
                    + SIMD3(clampPlane.x, clampPlane.y, clampPlane.z)
                        / simd_length(SIMD3(clampPlane.x, clampPlane.y, clampPlane.z))
                )
                try require(
                    !sphereOutsideClusterCornerDepthVertices(
                        center: vertex + cornerOutward * (fixtureRadius + 0.05),
                        radius: fixtureRadius,
                        clusterX: planeClusterX, clusterY: planeClusterY,
                        clusterZ: clusterZ,
                        width: planeWidth, height: planeHeight,
                        projection: identityProjection,
                        depthBoundaries: depthBoundaries
                    ),
                    "\(name) clamp endpoint was incorrectly treated as a corner-depth vertex"
                )
            }
            var invalidDepthBoundaries = depthBoundaries
            invalidDepthBoundaries[interiorSlice] = .infinity
            try require(
                !sphereOutsideClusterSideDepthEdges(
                    center: SIMD3(-0.7, -0.2, -0.8), radius: fixtureRadius,
                    clusterX: planeClusterX, clusterY: planeClusterY, clusterZ: interiorSlice,
                    width: planeWidth, height: planeHeight, projection: identityProjection,
                    depthBoundaries: invalidDepthBoundaries
                ),
                "Invalid side-depth boundary was not fail-open"
            )

            // A witness point at each representative tile/depth/projection is inside a
            // small source sphere. The conservative reject must therefore never remove it.
            let witnessProjections = [
                perspective(near: 0.1, far: 100, aspect: Float(planeWidth) / Float(planeHeight)),
                bobbedProjection(near: 0.1, far: 100, aspect: Float(planeWidth) / Float(planeHeight),
                                  xRotationDegrees: -0.5, zTranslation: 0.001),
                bobbedProjection(near: 0.1, far: 100, aspect: Float(planeWidth) / Float(planeHeight),
                                  xRotationDegrees: 0.5, zTranslation: 0.001)
            ]
            for (projectionIndex, projection) in witnessProjections.enumerated() {
                let inverseProjection = simd_inverse(projection)
                for clusterY in [0, 1, 2] {
                    for clusterX in [0, 1, 3] {
                        // Centre plus four interior corners exercise the tile wedge rather
                        // than only the old single-plane boundaries.
                        for localPixel in [
                            SIMD2<Float>(Float(tileSize) * 0.5, Float(tileSize) * 0.5),
                            SIMD2<Float>(1, 1),
                            SIMD2<Float>(Float(tileSize - 1), 1),
                            SIMD2<Float>(1, Float(tileSize - 1)),
                            SIMD2<Float>(Float(tileSize - 1), Float(tileSize - 1))
                        ] {
                            let ndc = SIMD2<Float>(
                                (Float(clusterX * tileSize) + localPixel.x) * 2 / Float(planeWidth) - 1,
                                (Float(clusterY * tileSize) + localPixel.y) * 2 / Float(planeHeight) - 1
                            )
                            for clipDepth in [Float(0.05), 0.5, 0.95] {
                            let homogeneous = inverseProjection * SIMD4(ndc.x, ndc.y, clipDepth, 1)
                            try require(
                                homogeneous.x.isFinite && homogeneous.y.isFinite
                                    && homogeneous.z.isFinite && homogeneous.w.isFinite
                                    && abs(homogeneous.w) > 1e-6,
                                "Invalid side-plane witness inverse at projection \(projectionIndex)"
                            )
                            let witness = SIMD3(
                                homogeneous.x / homogeneous.w,
                                homogeneous.y / homogeneous.w,
                                homogeneous.z / homogeneous.w
                            )
                            let reprojected = projection * SIMD4(witness, 1)
                            try require(
                                reprojected.w > 1e-6 && reprojected.w.isFinite,
                                "Side-plane witness is not visible at projection \(projectionIndex)"
                            )
                            let reprojectedNdc = SIMD2(
                                reprojected.x / reprojected.w,
                                reprojected.y / reprojected.w
                            )
                            try require(
                                reprojectedNdc.x.isFinite && reprojectedNdc.y.isFinite
                                    && abs(reprojectedNdc.x - ndc.x) < 1e-4
                                    && abs(reprojectedNdc.y - ndc.y) < 1e-4,
                                "Side-plane witness left its representative tile"
                            )
                            try require(
                                !sphereOutsideClusterSidePlanes(
                                    center: witness, radius: 0.01,
                                    clusterX: clusterX, clusterY: clusterY,
                                    width: planeWidth, height: planeHeight, projection: projection
                                ),
                                "Side-plane false-negative at projection \(projectionIndex), tile \(clusterX),\(clusterY), depth \(clipDepth)"
                            )
                            try require(
                                !sphereOutsideClusterSideWedges(
                                    center: witness, radius: 0.01,
                                    clusterX: clusterX, clusterY: clusterY,
                                    width: planeWidth, height: planeHeight, projection: projection
                                ),
                                "Corner-wedge false-negative at projection \(projectionIndex), tile \(clusterX),\(clusterY), depth \(clipDepth)"
                            )
                            let witnessSlice = depthSlice(-witness.z, near: 0.1, far: 100)
                            try require(
                                !sphereOutsideClusterSideDepthEdges(
                                    center: witness, radius: 0.01,
                                    clusterX: clusterX, clusterY: clusterY, clusterZ: witnessSlice,
                                    width: planeWidth, height: planeHeight, projection: projection,
                                    depthBoundaries: depthBoundaries
                                ),
                                "Side-depth edge false-negative at projection \(projectionIndex), tile \(clusterX),\(clusterY), depth \(clipDepth)"
                            )
                            try require(
                                !sphereOutsideClusterCornerDepthVertices(
                                    center: witness, radius: 0.01,
                                    clusterX: clusterX, clusterY: clusterY,
                                    clusterZ: witnessSlice,
                                    width: planeWidth, height: planeHeight,
                                    projection: projection,
                                    depthBoundaries: depthBoundaries
                                ),
                                "Corner-depth false-negative at projection \(projectionIndex), tile \(clusterX),\(clusterY), depth \(clipDepth)"
                            )
                        }
                        }
                    }
                }
            }

            let generation: UInt64 = 700
            let width = 64
            let height = 48
            let clustersX = UInt32((width + tileSize - 1) / tileSize)
            let clustersY = UInt32((height + tileSize - 1) / tileSize)
            let maxLights: UInt32 = 128
            let fullIndexCapacity = UInt32(Int(clustersX * clustersY * UInt32(depthSlices)) * clusterCap)
            guard let context = api.createContext(
                objectPointer(device as AnyObject),
                generation,
                maxLights,
                fullIndexCapacity,
                clustersX,
                clustersY,
                UInt32(depthSlices)
            ) else {
                throw ValidationFailure.message("Could not create native lighting context")
            }
            defer { api.releaseContext(context) }

            // Preflight and failure atomicity: malformed packets add no encoder.
            let oneLight = deterministicLights(count: 1)
            let frame = framePacket(
                generation: generation,
                frameId: 1,
                submitIndex: 0,
                width: UInt32(width),
                height: UInt32(height),
                lightCount: 1,
                hdr: false
            )
            try setFrame(api, frame)
            guard let rejectedCommandBuffer = queue.makeCommandBuffer() else {
                throw ValidationFailure.message("Could not make rejection command buffer")
            }
            var valid = batchPacket(generation: generation, frameId: 1, submitIndex: 0, lights: oneLight)
            try require(upload(api, context: nil, commandBuffer: rejectedCommandBuffer, packet: valid) == -1,
                        "Null context was accepted")
            try require(upload(api, context: context, commandBuffer: nil, packet: valid) == -1,
                        "Null command buffer was accepted")
            var badMagic = valid
            writeUInt32(0, at: 0, into: &badMagic)
            try require(upload(api, context: context, commandBuffer: rejectedCommandBuffer, packet: badMagic) == -2,
                        "Bad batch magic was accepted")
            var unordered = valid
            writeUInt32(0, at: 28, into: &unordered)
            try require(upload(api, context: context, commandBuffer: rejectedCommandBuffer, packet: unordered) == -3,
                        "Batch without authoritative CPU order was accepted")
            var wrongGeneration = valid
            writeUInt32(999, at: headerBytes + 44, into: &wrongGeneration)
            try require(upload(api, context: context, commandBuffer: rejectedCommandBuffer, packet: wrongGeneration) == -8,
                        "Per-light generation mismatch was accepted")
            writeFloat(.nan, at: headerBytes, into: &valid)
            try require(upload(api, context: context, commandBuffer: rejectedCommandBuffer, packet: valid) == -8,
                        "Non-finite light data was accepted")
            rejectedCommandBuffer.commit()
            rejectedCommandBuffer.waitUntilCompleted()
            try require(rejectedCommandBuffer.status == .completed,
                        "Rejected packets damaged the command buffer")

            var submit: UInt64 = 1

            // A diagonal corner miss remains inside both individual radius-expanded
            // planes, but it cannot intersect their shared wedge. Exercise the actual
            // Metal kernel (not only the normalized CPU oracle) on all four tile corners.
            do {
                let cornerWidth = planeWidth
                let cornerHeight = planeHeight
                let cornerClustersX = UInt32((cornerWidth + tileSize - 1) / tileSize)
                let cornerClustersY = UInt32((cornerHeight + tileSize - 1) / tileSize)
                let cornerClusterCount = Int(cornerClustersX * cornerClustersY * UInt32(depthSlices))
                let cornerGeneration: UInt64 = 704
                guard let cornerContext = api.createContext(
                    objectPointer(device as AnyObject),
                    cornerGeneration,
                    4,
                    UInt32(cornerClusterCount * clusterCap),
                    cornerClustersX,
                    cornerClustersY,
                    UInt32(depthSlices)
                ) else {
                    throw ValidationFailure.message("Could not create corner-wedge validation context")
                }
                defer { api.releaseContext(cornerContext) }
                for (cornerIndex, (_, firstIndex, secondIndex, corner)) in cornerFixtures.enumerated() {
                    let firstNormal = SIMD3(
                        explicitPlanes[firstIndex].x,
                        explicitPlanes[firstIndex].y,
                        explicitPlanes[firstIndex].z
                    )
                    let secondNormal = SIMD3(
                        explicitPlanes[secondIndex].x,
                        explicitPlanes[secondIndex].y,
                        explicitPlanes[secondIndex].z
                    )
                    let outward = -simd_normalize(
                        firstNormal / simd_length(firstNormal)
                            + secondNormal / simd_length(secondNormal)
                    )
                    let cornerLight = [Light(
                        position: corner + outward * (fixtureRadius + 0.05),
                        radius: fixtureRadius,
                        color: SIMD3(1, 0.6, 0.2),
                        intensity: 2,
                        stableId: UInt64(20 + cornerIndex),
                        flags: 0
                    )]
                    submit += 1
                    let gpu = try runGpu(
                        api: api, context: cornerContext, queue: queue,
                        generation: cornerGeneration, frameId: UInt64(50 + cornerIndex),
                        submitIndex: submit, width: cornerWidth, height: cornerHeight,
                        lights: cornerLight, hdr: cornerIndex.isMultiple(of: 2),
                        projectionOverride: identityProjection
                    )
                    let cpu = reference(
                        lights: cornerLight, width: cornerWidth, height: cornerHeight,
                        indexCapacity: cornerClusterCount * clusterCap,
                        candidateCapacity: 4, projectionOverride: identityProjection
                    )
                    try requireMatches(gpu, cpu, context: "GPU corner-wedge \(cornerIndex)")
                    let targetCluster = Int(cornerClustersX) + 1
                    try require(
                        gpu.headers[targetCluster].y == 0,
                        "GPU corner-wedge \(cornerIndex) retained its diagonal tile"
                    )
                }
            }

            // GPU contract for every side×interior-depth edge. The source sphere crosses
            // each plane alone but cannot reach the selected cluster edge, so only the new
            // refinement may remove the target membership.
            do {
                let edgeClustersX = UInt32((planeWidth + tileSize - 1) / tileSize)
                let edgeClustersY = UInt32((planeHeight + tileSize - 1) / tileSize)
                let edgeClusterCount = Int(edgeClustersX * edgeClustersY * UInt32(depthSlices))
                let edgeGeneration: UInt64 = 705
                guard let edgeContext = api.createContext(
                    objectPointer(device as AnyObject), edgeGeneration, 4,
                    UInt32(edgeClusterCount * clusterCap), edgeClustersX, edgeClustersY,
                    UInt32(depthSlices)
                ) else {
                    throw ValidationFailure.message("Could not create side-depth edge validation context")
                }
                defer { api.releaseContext(edgeContext) }
                let edgeDepthFixtures: [(String, SIMD4<Float>)] = [
                    ("lower", SIMD4(0, 0, -1, -depthBoundaries[interiorSlice])),
                    ("upper", SIMD4(0, 0, 1, depthBoundaries[interiorSlice + 1]))
                ]
                for (depthName, depthPlane) in edgeDepthFixtures {
                    for (sideIndex, sidePlane) in explicitPlanes.enumerated() {
                        guard let edge = closestPlaneEdgePoint(sidePlane, depthPlane) else {
                            throw ValidationFailure.message("Could not construct GPU \(depthName) edge \(sideIndex)")
                        }
                        let outward = -simd_normalize(
                            SIMD3(sidePlane.x, sidePlane.y, sidePlane.z)
                                / simd_length(SIMD3(sidePlane.x, sidePlane.y, sidePlane.z))
                                + SIMD3(depthPlane.x, depthPlane.y, depthPlane.z)
                                / simd_length(SIMD3(depthPlane.x, depthPlane.y, depthPlane.z))
                        )
                        let edgeLight = [Light(
                            position: edge + outward * (fixtureRadius + 0.05),
                            radius: fixtureRadius,
                            color: SIMD3(1, 0.6, 0.2),
                            intensity: 2,
                            stableId: UInt64(40 + sideIndex),
                            flags: 0
                        )]
                        submit += 1
                        let gpu = try runGpu(
                            api: api, context: edgeContext, queue: queue,
                            generation: edgeGeneration,
                            frameId: UInt64(70 + sideIndex + (depthName == "upper" ? 4 : 0)),
                            submitIndex: submit, width: planeWidth, height: planeHeight,
                            lights: edgeLight, hdr: sideIndex.isMultiple(of: 2),
                            projectionOverride: identityProjection
                        )
                        let cpu = reference(
                            lights: edgeLight, width: planeWidth, height: planeHeight,
                            indexCapacity: edgeClusterCount * clusterCap,
                            candidateCapacity: 4, projectionOverride: identityProjection
                        )
                        try requireMatches(gpu, cpu, context: "GPU \(depthName) side-depth edge \(sideIndex)")
                        let targetCluster = (interiorSlice * Int(edgeClustersY) + planeClusterY)
                            * Int(edgeClustersX) + planeClusterX
                        try require(gpu.headers[targetCluster].y == 0,
                                    "GPU \(depthName) side-depth edge \(sideIndex) retained its target cluster")
                    }
                }
            }

            // GPU contract for all four corner×depth vertices at both interior depth
            // boundaries. Each sphere intersects every constituent plane and every
            // pair-wise wedge, but misses the three-half-space intersection.
            do {
                let vertexClustersX = UInt32((planeWidth + tileSize - 1) / tileSize)
                let vertexClustersY = UInt32((planeHeight + tileSize - 1) / tileSize)
                let vertexClusterCount = Int(
                    vertexClustersX * vertexClustersY * UInt32(depthSlices)
                )
                let vertexGeneration: UInt64 = 706
                guard let vertexContext = api.createContext(
                    objectPointer(device as AnyObject), vertexGeneration, 4,
                    UInt32(vertexClusterCount * clusterCap),
                    vertexClustersX, vertexClustersY, UInt32(depthSlices)
                ) else {
                    throw ValidationFailure.message(
                        "Could not create corner-depth vertex validation context"
                    )
                }
                defer { api.releaseContext(vertexContext) }
                for (depthName, depthPlane) in depthFixtures {
                    for (cornerIndex, (_, firstIndex, secondIndex, _))
                            in cornerFixtures.enumerated() {
                        let firstPlane = explicitPlanes[firstIndex]
                        let secondPlane = explicitPlanes[secondIndex]
                        guard let vertex = closestPlaneCornerPoint(
                            firstPlane, secondPlane, depthPlane
                        ) else {
                            throw ValidationFailure.message(
                                "Could not construct GPU \(depthName) vertex \(cornerIndex)"
                            )
                        }
                        let outward = -simd_normalize(
                            SIMD3(firstPlane.x, firstPlane.y, firstPlane.z)
                                / simd_length(SIMD3(firstPlane.x, firstPlane.y, firstPlane.z))
                            + SIMD3(secondPlane.x, secondPlane.y, secondPlane.z)
                                / simd_length(SIMD3(secondPlane.x, secondPlane.y, secondPlane.z))
                            + SIMD3(depthPlane.x, depthPlane.y, depthPlane.z)
                                / simd_length(SIMD3(depthPlane.x, depthPlane.y, depthPlane.z))
                        )
                        let vertexLight = [Light(
                            position: vertex + outward * (fixtureRadius + 0.05),
                            radius: fixtureRadius,
                            color: SIMD3(1, 0.6, 0.2),
                            intensity: 2,
                            stableId: UInt64(80 + cornerIndex),
                            flags: 0
                        )]
                        submit += 1
                        let gpu = try runGpu(
                            api: api, context: vertexContext, queue: queue,
                            generation: vertexGeneration,
                            frameId: UInt64(90 + cornerIndex
                                + (depthName == "upper" ? 4 : 0)),
                            submitIndex: submit, width: planeWidth, height: planeHeight,
                            lights: vertexLight, hdr: cornerIndex.isMultiple(of: 2),
                            projectionOverride: identityProjection
                        )
                        let cpu = reference(
                            lights: vertexLight, width: planeWidth, height: planeHeight,
                            indexCapacity: vertexClusterCount * clusterCap,
                            candidateCapacity: 4, projectionOverride: identityProjection
                        )
                        try requireMatches(
                            gpu, cpu,
                            context: "GPU \(depthName) corner-depth vertex \(cornerIndex)"
                        )
                        let targetCluster = (
                            interiorSlice * Int(vertexClustersY) + planeClusterY
                        ) * Int(vertexClustersX) + planeClusterX
                        try require(
                            gpu.headers[targetCluster].y == 0,
                            "GPU \(depthName) corner-depth vertex \(cornerIndex) retained its target cluster"
                        )
                    }
                }
                let endpointFixtures: [(String, Int, SIMD4<Float>)] = [
                    ("first", 0, SIMD4(0, 0, -1, -depthBoundaries[0])),
                    ("last", depthSlices - 1,
                     SIMD4(0, 0, 1, depthBoundaries[depthSlices]))
                ]
                for (endpointIndex, (name, clusterZ, hypotheticalPlane))
                        in endpointFixtures.enumerated() {
                    let firstPlane = explicitPlanes[0]
                    let secondPlane = explicitPlanes[2]
                    guard let vertex = closestPlaneCornerPoint(
                        firstPlane, secondPlane, hypotheticalPlane
                    ) else {
                        throw ValidationFailure.message(
                            "Could not construct GPU \(name) endpoint vertex"
                        )
                    }
                    let outward = -simd_normalize(
                        SIMD3(firstPlane.x, firstPlane.y, firstPlane.z)
                            / simd_length(SIMD3(firstPlane.x, firstPlane.y, firstPlane.z))
                        + SIMD3(secondPlane.x, secondPlane.y, secondPlane.z)
                            / simd_length(SIMD3(secondPlane.x, secondPlane.y, secondPlane.z))
                        + SIMD3(hypotheticalPlane.x, hypotheticalPlane.y, hypotheticalPlane.z)
                            / simd_length(SIMD3(
                                hypotheticalPlane.x, hypotheticalPlane.y, hypotheticalPlane.z
                            ))
                    )
                    let endpointLight = [Light(
                        position: vertex + outward * (fixtureRadius + 0.05),
                        radius: fixtureRadius,
                        color: SIMD3(1, 0.6, 0.2),
                        intensity: 2,
                        stableId: UInt64(100 + endpointIndex),
                        flags: 0
                    )]
                    submit += 1
                    let gpu = try runGpu(
                        api: api, context: vertexContext, queue: queue,
                        generation: vertexGeneration,
                        frameId: UInt64(110 + endpointIndex),
                        submitIndex: submit, width: planeWidth, height: planeHeight,
                        lights: endpointLight, hdr: endpointIndex.isMultiple(of: 2),
                        projectionOverride: identityProjection
                    )
                    let cpu = reference(
                        lights: endpointLight, width: planeWidth, height: planeHeight,
                        indexCapacity: vertexClusterCount * clusterCap,
                        candidateCapacity: 4, projectionOverride: identityProjection
                    )
                    try requireMatches(
                        gpu, cpu, context: "GPU \(name) corner-depth endpoint clamp"
                    )
                    let targetCluster = (
                        clusterZ * Int(vertexClustersY) + planeClusterY
                    ) * Int(vertexClustersX) + planeClusterX
                    try require(
                        gpu.headers[targetCluster].y == 1,
                        "GPU \(name) endpoint used a hypothetical corner-depth plane"
                    )
                }
            }

            let emptyGpu = try runGpu(
                api: api, context: context, queue: queue, generation: generation,
                frameId: 2, submitIndex: submit, width: width, height: height,
                lights: [], hdr: false
            )
            try require(emptyGpu.indices.isEmpty, "Empty batch exposed stale compact indices")
            try require(readUInt32(emptyGpu.stats, at: 24) == 0,
                        "Empty batch reported a non-zero light count")
            try require((32...68).allSatisfy { readUInt32(emptyGpu.stats, at: $0) == 0 },
                        "Empty batch reported non-zero cluster work or occupancy")
            try require(readUInt32(emptyGpu.stats, at: 80) == 1,
                        "Empty batch lost the SDR/HDR-independent contract flag")

            // A camera-containing point light must cover the screen instead of disappearing
            // through the clip.w == 0 path or an arbitrary per-light cluster-work cutoff.
            let cameraInside = [Light(
                position: SIMD3(0, 0, 0),
                radius: 12,
                color: SIMD3(1, 0.8, 0.5),
                intensity: 3,
                stableId: 1,
                flags: 0
            )]
            submit += 1
            let cameraInsideGpu = try runGpu(
                api: api, context: context, queue: queue, generation: generation,
                frameId: 3, submitIndex: submit, width: width, height: height,
                lights: cameraInside, hdr: false
            )
            let cameraInsideCpu = reference(
                lights: cameraInside,
                width: width,
                height: height,
                indexCapacity: Int(fullIndexCapacity)
            )
            try requireMatches(cameraInsideGpu, cameraInsideCpu, context: "camera-containing light")
            try require(cameraInsideCpu.requestedIndices > 0,
                        "Camera-containing light vanished from every cluster")

            // Empty reuse must retire the previous non-empty headers; otherwise the direct
            // shader can observe ghost cluster counts even though admitted light count is zero.
            submit += 1
            let reusedEmptyGpu = try runGpu(
                api: api, context: context, queue: queue, generation: generation,
                frameId: 4, submitIndex: submit, width: width, height: height,
                lights: [], hdr: true
            )
            try require(reusedEmptyGpu.headers.allSatisfy { $0 == SIMD2<UInt32>(repeating: 0) },
                        "Empty reuse exposed stale cluster headers from a non-empty frame")
            try require(reusedEmptyGpu.indices.isEmpty,
                        "Empty reuse exposed stale compact indices")
            try require(readUInt32(reusedEmptyGpu.params, at: 140) == 0
                            && readUInt32(reusedEmptyGpu.params, at: 224) == 0,
                        "Empty reuse retained a non-zero compact or original light count")
            try require((32...68).allSatisfy { readUInt32(reusedEmptyGpu.stats, at: $0) == 0 },
                        "Empty reuse reported non-zero cluster work or occupancy")

            // A sphere can cross the camera-depth plane while remaining far outside a side
            // frustum plane. It must not inherit the camera-containing full-screen bound.
            let cameraPlaneOutside = [Light(
                position: SIMD3(90, 0, 0),
                radius: 13,
                color: SIMD3(1, 0.2, 0.05),
                intensity: 3,
                stableId: 2,
                flags: 0
            )]
            submit += 1
            let cameraPlaneOutsideGpu = try runGpu(
                api: api, context: context, queue: queue, generation: generation,
                frameId: 5, submitIndex: submit, width: width, height: height,
                lights: cameraPlaneOutside, hdr: false
            )
            let cameraPlaneOutsideCpu = reference(
                lights: cameraPlaneOutside,
                width: width,
                height: height,
                indexCapacity: Int(fullIndexCapacity)
            )
            try requireMatches(
                cameraPlaneOutsideGpu,
                cameraPlaneOutsideCpu,
                context: "camera-plane off-axis light"
            )
            try require(cameraPlaneOutsideCpu.requestedIndices == 0,
                        "Off-axis camera-plane light was expanded to a full-screen bound")

            // View-invalid candidates retain their camera-stable upload index. Compact cluster
            // lists omit them without renumbering the visible candidates that follow.
            let compactionLights = cameraPlaneOutside + [Light(
                position: SIMD3(0, 0, -8),
                radius: 1,
                color: SIMD3(0.2, 0.9, 0.4),
                intensity: 2,
                stableId: 3,
                flags: 0
            )]
            submit += 1
            let compactionGpu = try runGpu(
                api: api, context: context, queue: queue, generation: generation,
                frameId: 7, submitIndex: submit, width: width, height: height,
                lights: compactionLights, hdr: false
            )
            let compactionCpu = reference(
                lights: compactionLights, width: width, height: height,
                indexCapacity: Int(fullIndexCapacity)
            )
            try requireMatches(compactionGpu, compactionCpu, context: "stable candidate indices")
            try require(compactionCpu.admittedLightCount == 2
                            && Set(compactionGpu.indices) == Set([UInt32(1)]),
                        "Cluster compaction renumbered the visible candidate after an invalid one")
            let visibleOffset = lightBytes
            try require(readFloat(compactionGpu.lights, at: visibleOffset) == 0
                            && readFloat(compactionGpu.lights, at: visibleOffset + 4) == 0
                            && readFloat(compactionGpu.lights, at: visibleOffset + 8) == -8
                            && readFloat(compactionGpu.lights, at: visibleOffset + 12) == 1,
                        "Stable candidate payload moved away from gpuLights[1]")

            // A perspective sphere bound must include tangent edge tiles. The old
            // center +/- radius/depth approximation omitted both outer X tiles here.
            let tangentLight = [Light(
                position: SIMD3(0, 0, -8),
                radius: 3,
                color: SIMD3(0.3, 0.7, 1),
                intensity: 2,
                stableId: 2,
                flags: 0
            )]
            submit += 1
            let tangentGpu = try runGpu(
                api: api, context: context, queue: queue, generation: generation,
                frameId: 8, submitIndex: submit, width: width, height: height,
                lights: tangentLight, hdr: true
            )
            let tangentCpu = reference(
                lights: tangentLight,
                width: width,
                height: height,
                indexCapacity: Int(fullIndexCapacity)
            )
            try requireMatches(tangentGpu, tangentCpu, context: "perspective tangent bound")
            let tangentOccupiedX = Set(tangentCpu.headers.enumerated().compactMap {
                $0.element.y > 0 ? $0.offset % Int(clustersX) : nil
            })
            try require(tangentOccupiedX.contains(0) && tangentOccupiedX.contains(Int(clustersX) - 1),
                        "Conservative perspective bound omitted an outer tangent tile")

            // The final projection contains view-bob/hurt transforms. A near-eye sphere can
            // therefore have a raw positive-depth AABB while one projected corner reaches or
            // crosses clip.w == 0. The conservative fallback must keep a real in-sphere
            // fragment's cluster membership stable instead of dropping the entire light.
            do {
                let bobWidth = 256
                let bobHeight = 192
                let bobClustersX = (bobWidth + tileSize - 1) / tileSize
                let bobClustersY = (bobHeight + tileSize - 1) / tileSize
                let bobGeneration: UInt64 = 703
                let bobClusterCount = bobClustersX * bobClustersY * depthSlices
                guard let bobContext = api.createContext(
                    objectPointer(device as AnyObject),
                    bobGeneration,
                    8,
                    UInt32(bobClusterCount * clusterCap),
                    UInt32(bobClustersX),
                    UInt32(bobClustersY),
                    UInt32(depthSlices)
                ) else {
                    throw ValidationFailure.message("Could not create view-bob regression context")
                }
                defer { api.releaseContext(bobContext) }

                let nearEyeLight = Light(
                    position: SIMD3(0, 0, -1.001),
                    radius: 1,
                    color: SIMD3(1, 0.6, 0.2),
                    intensity: 3,
                    stableId: 4,
                    flags: 0
                )
                let inSphereFragment = SIMD3<Float>(0, 0, -0.5)
                try require(
                    simd_distance(inSphereFragment, nearEyeLight.position) < nearEyeLight.radius,
                    "View-bob regression fragment is not inside its source sphere"
                )

                for (iteration, angle) in [Float(-0.5), 0, 0.5].enumerated() {
                    let projection = bobbedProjection(
                        near: 0.1,
                        far: 100,
                        aspect: Float(bobWidth) / Float(bobHeight),
                        xRotationDegrees: angle,
                        zTranslation: 0.001
                    )
                    var cornerW: [Float] = []
                    for corner in 0..<8 {
                        let sign = SIMD3<Float>(
                            corner & 1 == 0 ? -1 : 1,
                            corner & 2 == 0 ? -1 : 1,
                            corner & 4 == 0 ? -1 : 1
                        )
                        let clip = projection * SIMD4(
                            nearEyeLight.position + sign * nearEyeLight.radius,
                            1
                        )
                        cornerW.append(clip.w)
                    }
                    if angle == 0 {
                        try require(
                            cornerW.contains { abs($0) <= 1e-6 },
                            "Zero-angle view-bob fixture did not reach clip.w == 0"
                        )
                    } else {
                        try require(
                            cornerW.contains { $0 < 0 },
                            "Rotated view-bob fixture did not cross behind the clip plane"
                        )
                    }

                    submit += 1
                    let gpu = try runGpu(
                        api: api,
                        context: bobContext,
                        queue: queue,
                        generation: bobGeneration,
                        frameId: UInt64(20 + iteration),
                        submitIndex: submit,
                        width: bobWidth,
                        height: bobHeight,
                        lights: [nearEyeLight],
                        hdr: iteration.isMultiple(of: 2),
                        projectionOverride: projection
                    )
                    let cpu = reference(
                        lights: [nearEyeLight],
                        width: bobWidth,
                        height: bobHeight,
                        indexCapacity: bobClusterCount * clusterCap,
                        candidateCapacity: 8,
                        projectionOverride: projection
                    )
                    try requireMatches(gpu, cpu, context: "view-bob angle \(angle)")

                    let fragmentClip = projection * SIMD4(inSphereFragment, 1)
                    try require(fragmentClip.w > 0 && fragmentClip.w.isFinite,
                                "View-bob regression fragment is not visible")
                    let fragmentNdc = SIMD2(fragmentClip.x, fragmentClip.y) / fragmentClip.w
                    let fragmentPixel = simd_clamp(
                        (fragmentNdc * SIMD2(repeating: 0.5) + SIMD2(repeating: 0.5))
                            * SIMD2(Float(bobWidth), Float(bobHeight)),
                        .zero,
                        SIMD2(Float(bobWidth - 1), Float(bobHeight - 1))
                    )
                    let fragmentX = min(Int(floor(fragmentPixel.x / Float(tileSize))), bobClustersX - 1)
                    let fragmentY = min(Int(floor(fragmentPixel.y / Float(tileSize))), bobClustersY - 1)
                    let fragmentZ = depthSlice(-inSphereFragment.z, near: 0.1, far: 100)
                    try require(
                        !sphereOutsideClusterSideWedges(
                            center: nearEyeLight.position,
                            radius: nearEyeLight.radius,
                            clusterX: fragmentX,
                            clusterY: fragmentY,
                            width: bobWidth,
                            height: bobHeight,
                            projection: projection
                        ),
                        "View-bob angle \(angle) corner wedge dropped an in-sphere fragment tile"
                    )
                    let fragmentCluster = (fragmentZ * bobClustersY + fragmentY) * bobClustersX + fragmentX
                    let fragmentHeader = gpu.headers[fragmentCluster]
                    let fragmentStart = Int(fragmentHeader.x)
                    let fragmentEnd = fragmentStart + Int(fragmentHeader.y)
                    try require(
                        gpu.indices[fragmentStart..<fragmentEnd].contains(0),
                        "View-bob angle \(angle) dropped the light from an in-sphere fragment cluster"
                    )
                }
            }

            // Repeated CPU/GPU property comparison and exact determinism.
            let propertyLights = deterministicLights(count: 47)
            var firstResult: GpuResult?
            for iteration in 0..<12 {
                submit += 1
                let gpu = try runGpu(
                    api: api, context: context, queue: queue, generation: generation,
                    frameId: UInt64(10 + iteration), submitIndex: submit,
                    width: width, height: height, lights: propertyLights,
                    hdr: iteration.isMultiple(of: 2)
                )
                let cpu = reference(
                    lights: propertyLights,
                    width: width,
                    height: height,
                    indexCapacity: Int(fullIndexCapacity)
                )
                try requireMatches(gpu, cpu, context: "property iteration \(iteration)")
                if let firstResult {
                    try require(gpu.headers == firstResult.headers && gpu.indices == firstResult.indices,
                                "Repeated cluster build was not bit-deterministic")
                    try require(
                        (32...68).allSatisfy {
                            readUInt32(gpu.stats, at: $0) == readUInt32(firstResult.stats, at: $0)
                        },
                        "SDR/HDR changed cluster counters for the same scene"
                    )
                } else {
                    firstResult = gpu
                }
            }

            // Dense membership retains every uploaded candidate globally. This base context
            // fits exactly inside Balanced's local cap; the 256-light preset matrix below
            // exercises deterministic local truncation independently.
            let denseLights = clusterOverflowLights(count: Int(maxLights))
            submit += 1
            let denseGpu = try runGpu(
                api: api, context: context, queue: queue, generation: generation,
                frameId: 40, submitIndex: submit, width: width, height: height,
                lights: denseLights, hdr: false
            )
            let denseCpu = reference(
                lights: denseLights,
                width: width,
                height: height,
                indexCapacity: Int(fullIndexCapacity)
            )
            try requireMatches(denseGpu, denseCpu, context: "max/dense batch")
            try require(
                denseCpu.admittedLightCount == maxLights
                    && denseCpu.admissionRejectedLights == 0
                    && denseCpu.overflowClusters == 0
                    && denseCpu.perClusterDrops == 0,
                "Balanced dropped candidates that fit its per-cluster cap"
            )
            for header in denseGpu.headers where header.y == denseCpu.perClusterCap {
                let start = Int(header.x)
                let count = Int(denseCpu.perClusterCap)
                try require(
                    Array(denseGpu.indices[start..<(start + count)])
                        == Array(UInt32(0)..<denseCpu.perClusterCap),
                    "Membership compaction did not preserve CPU priority order"
                )
            }

            // Bin zero is reserved for empty clusters. Occupancies 1...4 share the first
            // non-empty bin and must never be reported below their exact maximum.
            for occupancy in 1...4 {
                submit += 1
                let lowGpu = try runGpu(
                    api: api, context: context, queue: queue, generation: generation,
                    frameId: UInt64(40 + occupancy), submitIndex: submit,
                    width: width, height: height,
                    lights: clusterOverflowLights(count: occupancy), hdr: false
                )
                try require(readUInt32(lowGpu.stats, at: 60) == UInt32(occupancy)
                                && readUInt32(lowGpu.stats, at: 64) == UInt32(occupancy)
                                && readUInt32(lowGpu.stats, at: 68) == UInt32(occupancy),
                            "Low clustered occupancy \(occupancy) was under-reported")
            }

            // P/B/U share one global candidate pool and one correctness-preserving local cap.
            do {
                let presetGeneration: UInt64 = 703
                let presetMaxLights: UInt32 = 512
                guard let presetContext = api.createContext(
                    objectPointer(device as AnyObject), presetGeneration, presetMaxLights,
                    fullIndexCapacity, clustersX, clustersY, UInt32(depthSlices)
                ) else {
                    throw ValidationFailure.message("Could not create preset-cap context")
                }
                defer { api.releaseContext(presetContext) }
                let capLights = clusterOverflowLights(count: Int(presetMaxLights))
                for preset in UInt32(0)...UInt32(2) {
                    submit += 1
                    let gpu = try runGpu(
                        api: api, context: presetContext, queue: queue,
                        generation: presetGeneration, frameId: UInt64(45 + preset),
                        submitIndex: submit, width: width, height: height,
                        lights: capLights, hdr: preset == 2, preset: preset
                    )
                    let cpu = reference(
                        lights: capLights, width: width, height: height,
                        indexCapacity: Int(fullIndexCapacity), preset: preset,
                        candidateCapacity: presetMaxLights
                    )
                    try requireMatches(gpu, cpu, context: "preset \(preset) membership caps")
                    let contract = presetContract(preset)
                    try require(cpu.admittedLightCount == presetMaxLights
                                    && cpu.admissionRejectedLights == 0,
                                "Preset \(preset) changed the shared global candidate pool")
                    try require(cpu.overflowClusters > 0 && cpu.perClusterDrops > 0,
                                "Preset \(preset) did not enforce its local cluster cap")
                    if preset == 2 {
                        try require(readUInt32(gpu.stats, at: 60) == 256
                                        && readUInt32(gpu.stats, at: 64) == 256
                                        && readUInt32(gpu.stats, at: 68) == 256,
                                    "Ultra dense occupancy telemetry saturated below 256")
                    }
                    let cappedHeaders = gpu.headers.filter { $0.y == UInt32(contract.perClusterCap) }
                    try require(!cappedHeaders.isEmpty,
                                "Preset \(preset) never reached its per-cluster cap")
                    for header in cappedHeaders {
                        let start = Int(header.x)
                        let end = start + contract.perClusterCap
                        try require(
                            Array(gpu.indices[start..<end])
                                == Array(UInt32(0)..<UInt32(contract.perClusterCap)),
                            "Preset \(preset) did not retain earliest compact members"
                        )
                    }
                }
            }

            // The 128-word ABI must reach candidate #4095, not merely enlarge allocation
            // metadata. Keep the first 4095 candidates view-invalid so every preset's compact
            // list must recover the sole visible source from membership word 127.
            do {
                let fullPoolGeneration: UInt64 = 704
                guard let fullPoolContext = api.createContext(
                    objectPointer(device as AnyObject), fullPoolGeneration,
                    maximumLightCandidates, fullIndexCapacity,
                    clustersX, clustersY, UInt32(depthSlices)
                ) else {
                    throw ValidationFailure.message("Could not create 4096-candidate context")
                }
                defer { api.releaseContext(fullPoolContext) }
                let visibleLast = Light(
                    position: SIMD3(0, 0, -8),
                    radius: 1,
                    color: SIMD3(0.4, 0.8, 1),
                    intensity: 2,
                    stableId: UInt64(maximumLightCandidates),
                    flags: 0
                )
                let fullPoolLights = Array(
                    repeating: cameraPlaneOutside[0],
                    count: Int(maximumLightCandidates) - 1
                ) + [visibleLast]
                for preset in UInt32(0)...UInt32(2) {
                    submit += 1
                    let gpu = try runGpu(
                        api: api, context: fullPoolContext, queue: queue,
                        generation: fullPoolGeneration,
                        frameId: UInt64(61 + preset), submitIndex: submit,
                        width: width, height: height, lights: fullPoolLights,
                        hdr: preset == 2, preset: preset
                    )
                    let cpu = reference(
                        lights: fullPoolLights, width: width, height: height,
                        indexCapacity: Int(fullIndexCapacity), preset: preset,
                        candidateCapacity: maximumLightCandidates
                    )
                    try requireMatches(
                        gpu,
                        cpu,
                        context: "preset \(preset) candidate #4095"
                    )
                    try require(Set(gpu.indices) == Set([maximumLightCandidates - 1]),
                                "Preset \(preset) could not address membership word 127")
                }
                let oversized = api.createContext(
                    objectPointer(device as AnyObject), 705,
                    maximumLightCandidates + 1, fullIndexCapacity,
                    clustersX, clustersY, UInt32(depthSlices)
                )
                if let oversized { api.releaseContext(oversized) }
                try require(oversized == nil, "ABI v1 admitted more than 4096 candidates")
            }

            // The legacy mask flag remains packet-compatible but production must ignore it
            // and still build compact headers plus indices.
            do {
                let maskGeneration: UInt64 = 699
                guard let maskContext = api.createContext(
                    objectPointer(device as AnyObject), maskGeneration, maxLights,
                    fullIndexCapacity, clustersX, clustersY, UInt32(depthSlices)
                ) else {
                    throw ValidationFailure.message("Could not create legacy-flag context")
                }
                defer { api.releaseContext(maskContext) }
                submit += 1
                let maskGpu = try runGpu(
                    api: api,
                    context: maskContext,
                    queue: queue,
                    generation: maskGeneration,
                    frameId: 49,
                    submitIndex: submit,
                    width: width,
                    height: height,
                    lights: denseLights,
                    hdr: false,
                    batchFlags: 1 | clusterMaskBatchFlag
                )
                let maskCpu = reference(
                    lights: denseLights,
                    width: width,
                    height: height,
                    indexCapacity: Int(fullIndexCapacity)
                )
                try requireMatches(maskGpu, maskCpu, context: "legacy flag compact output")
                try requireMaskMatches(maskGpu, maskCpu, context: "legacy flag scratch oracle")

                // Repeating the same compact build across SDR/HDR must preserve exact output
                // and all cluster-work counters even when old Java still sends the flag.
                let firstMaskPayload = activeMaskPayload(
                    maskGpu,
                    admittedLightCount: maskCpu.admittedLightCount
                )
                for (iteration, hdr) in [true, false].enumerated() {
                    submit += 1
                    let repeated = try runGpu(
                        api: api,
                        context: maskContext,
                        queue: queue,
                        generation: maskGeneration,
                        frameId: UInt64(50 + iteration),
                        submitIndex: submit,
                        width: width,
                        height: height,
                        lights: denseLights,
                        hdr: hdr,
                        batchFlags: 1 | clusterMaskBatchFlag
                    )
                    try requireMaskMatches(
                        repeated,
                        maskCpu,
                        context: "repeated legacy-flag scratch \(iteration)"
                    )
                    try requireMatches(
                        repeated,
                        maskCpu,
                        context: "repeated legacy-flag compact output \(iteration)"
                    )
                    try require(
                        activeMaskPayload(
                            repeated,
                            admittedLightCount: maskCpu.admittedLightCount
                        ) == firstMaskPayload,
                        "SDR/HDR or repetition changed active production mask words"
                    )
                    try require(
                        (32...68).allSatisfy {
                            readUInt32(repeated.stats, at: $0)
                                == readUInt32(maskGpu.stats, at: $0)
                        },
                        "SDR/HDR or repetition changed production-mask counters"
                    )
                }

                // Every preset keeps the same candidate pool and changes only its local cap.
                for preset in UInt32(0)...UInt32(2) {
                    submit += 1
                    let presetMask = try runGpu(
                        api: api,
                        context: maskContext,
                        queue: queue,
                        generation: maskGeneration,
                        frameId: UInt64(53 + preset),
                        submitIndex: submit,
                        width: width,
                        height: height,
                        lights: denseLights,
                        hdr: preset == 2,
                        preset: preset,
                        batchFlags: 1 | clusterMaskBatchFlag
                    )
                    let presetMaskCpu = reference(
                        lights: denseLights,
                        width: width,
                        height: height,
                        indexCapacity: Int(fullIndexCapacity),
                        preset: preset
                    )
                    try requireMaskMatches(
                        presetMask,
                        presetMaskCpu,
                        context: "preset \(preset) production mask"
                    )
                    try requireMatches(
                        presetMask,
                        presetMaskCpu,
                        context: "preset \(preset) legacy-flag compact output"
                    )
                    try require(
                        presetMaskCpu.admittedLightCount == maxLights
                            && presetMaskCpu.admissionRejectedLights == 0,
                        "Preset \(preset) legacy flag changed the shared candidate pool"
                    )
                }

                // The production path consumes Java's camera-stable retained prefix exactly.
                // An invalid first light therefore keeps index zero reserved while the visible
                // second light contributes membership bit/index one.
                let retainedVisible = Light(
                    position: SIMD3(0, 0, -8),
                    radius: 1,
                    color: SIMD3(0.2, 0.9, 0.4),
                    intensity: 2,
                    stableId: 3,
                    flags: 0
                )
                let retainedPrefix = cameraPlaneOutside + [retainedVisible]
                let retainedReference = reference(
                    lights: retainedPrefix,
                    width: width,
                    height: height,
                    indexCapacity: Int(fullIndexCapacity)
                )
                let visibleReference = reference(
                    lights: [retainedVisible],
                    width: width,
                    height: height,
                    indexCapacity: Int(fullIndexCapacity)
                )

                func requireRetainedPrefixMask(
                    _ gpu: GpuResult,
                    context: String
                ) throws {
                    try requireMatches(gpu, retainedReference, context: "\(context) compact output")
                    try require(readUInt32(gpu.params, at: 140) == 2,
                                "\(context): candidate prefix count changed")
                    try require(readUInt32(gpu.params, at: 224) == 2,
                                "\(context): original retained-prefix count changed")
                    try require(readUInt32(gpu.stats, at: 24) == 2,
                                "\(context): uploaded retained-prefix count changed")
                    try require(readUInt32(gpu.stats, at: 52) == 0,
                                "\(context): invalid prefix light was reported as rejected")
                    try require(readUInt32(gpu.stats, at: 32) == visibleReference.requestedIndices
                                    && readUInt32(gpu.stats, at: 36)
                                        == visibleReference.requestedIndices,
                                "\(context): visible retained light membership count changed")
                    try require(readFloat(gpu.lights, at: lightBytes) == retainedVisible.position.x
                                    && readFloat(gpu.lights, at: lightBytes + 4)
                                        == retainedVisible.position.y
                                    && readFloat(gpu.lights, at: lightBytes + 8)
                                        == retainedVisible.position.z
                                    && readFloat(gpu.lights, at: lightBytes + 12)
                                        == retainedVisible.radius,
                                "\(context): visible retained light moved away from gpuLights[1]")
                    for (cluster, header) in visibleReference.headers.enumerated() {
                        let membership = gpu.masks[cluster * membershipWordsPerCluster]
                        let expected = header.y > 0 ? UInt32(1 << 1) : 0
                        try require(membership == expected,
                                    "\(context): retained bit/index one differs in cluster \(cluster)")
                    }
                }

                submit += 1
                let retainedSdr = try runGpu(
                    api: api,
                    context: maskContext,
                    queue: queue,
                    generation: maskGeneration,
                    frameId: 57,
                    submitIndex: submit,
                    width: width,
                    height: height,
                    lights: retainedPrefix,
                    hdr: false,
                    batchFlags: 1 | clusterMaskBatchFlag
                )
                try requireRetainedPrefixMask(retainedSdr, context: "SDR retained-prefix mask")

                submit += 1
                let retainedHdr = try runGpu(
                    api: api,
                    context: maskContext,
                    queue: queue,
                    generation: maskGeneration,
                    frameId: 58,
                    submitIndex: submit,
                    width: width,
                    height: height,
                    lights: retainedPrefix,
                    hdr: true,
                    batchFlags: 1 | clusterMaskBatchFlag
                )
                try requireRetainedPrefixMask(retainedHdr, context: "HDR retained-prefix mask")
                let retainedPayload = activeMaskPayload(retainedSdr, admittedLightCount: 2)
                try require(activeMaskPayload(retainedHdr, admittedLightCount: 2) == retainedPayload,
                            "SDR/HDR changed the exact retained-prefix production mask")

                submit += 1
                let retainedEmpty = try runGpu(
                    api: api,
                    context: maskContext,
                    queue: queue,
                    generation: maskGeneration,
                    frameId: 59,
                    submitIndex: submit,
                    width: width,
                    height: height,
                    lights: [],
                    hdr: false,
                    batchFlags: 1 | clusterMaskBatchFlag
                )
                try require(readUInt32(retainedEmpty.params, at: 140) == 0
                                && readUInt32(retainedEmpty.params, at: 224) == 0,
                            "Empty production mask retained a non-zero light count")
                try require((32...68).allSatisfy {
                    readUInt32(retainedEmpty.stats, at: $0) == 0
                }, "Empty production mask reported stale cluster work")

                submit += 1
                let retainedAfterEmpty = try runGpu(
                    api: api,
                    context: maskContext,
                    queue: queue,
                    generation: maskGeneration,
                    frameId: 60,
                    submitIndex: submit,
                    width: width,
                    height: height,
                    lights: retainedPrefix,
                    hdr: false,
                    batchFlags: 1 | clusterMaskBatchFlag
                )
                try requireRetainedPrefixMask(
                    retainedAfterEmpty,
                    context: "retained-prefix mask after empty"
                )
                try require(
                    activeMaskPayload(retainedAfterEmpty, admittedLightCount: 2)
                        == retainedPayload,
                    "Empty-to-nonempty transition changed the retained-prefix mask"
                )
            }

            // Prefix telemetry aliases the compact-index buffer before fill. This fixture has
            // one prefix block and needs 160 bytes; reject 39 UInt32 indices (156 bytes).
            let undersizedPrefixScratch = api.createContext(
                objectPointer(device as AnyObject), 700, maxLights, 39,
                clustersX, clustersY, UInt32(depthSlices)
            )
            if let undersizedPrefixScratch {
                api.releaseContext(undersizedPrefixScratch)
            }
            try require(undersizedPrefixScratch == nil,
                        "Context admitted an undersized prefix-summary scratch alias")

            let clippedGeneration: UInt64 = 701
            guard let clippedContext = api.createContext(
                objectPointer(device as AnyObject), clippedGeneration, maxLights, 40,
                clustersX, clustersY, UInt32(depthSlices)
            ) else {
                throw ValidationFailure.message("Could not create clipped-capacity context")
            }
            submit += 1
            let clippedGpu = try runGpu(
                api: api, context: clippedContext, queue: queue, generation: clippedGeneration,
                frameId: 50, submitIndex: submit, width: width, height: height,
                lights: denseLights, hdr: true
            )
            let clippedCpu = reference(lights: denseLights, width: width, height: height, indexCapacity: 40)
            try requireMatches(clippedGpu, clippedCpu, context: "diagnosed index-capacity clipping")
            try require(clippedCpu.admissionRejectedLights == 0,
                        "Small index capacity rejected global light candidates")
            try require(clippedCpu.indexCapacityDrops > 0,
                        "Small index capacity did not report deterministic clipping")
            api.releaseContext(clippedContext)

            // Retina-scale pathological overlap keeps the full candidate pool, applies only
            // the local Balanced cap, and completes deterministically.
            do {
                let retinaWidth = 3024
                let retinaHeight = 1964
                let retinaClustersX = UInt32((retinaWidth + tileSize - 1) / tileSize)
                let retinaClustersY = UInt32((retinaHeight + tileSize - 1) / tileSize)
                let retinaGeneration: UInt64 = 702
                let retinaMaxLights = maximumLightCandidates
                let retinaIndexCapacity: UInt32 = 4_000_000
                guard let retinaContext = api.createContext(
                    objectPointer(device as AnyObject),
                    retinaGeneration,
                    retinaMaxLights,
                    retinaIndexCapacity,
                    retinaClustersX,
                    retinaClustersY,
                    UInt32(depthSlices)
                ) else {
                    throw ValidationFailure.message("Could not create Retina workload-admission context")
                }
                defer { api.releaseContext(retinaContext) }

                let retinaDense = deterministicLights(count: Int(retinaMaxLights), dense: true)
                let retinaCpu = reference(
                    lights: retinaDense,
                    width: retinaWidth,
                    height: retinaHeight,
                    indexCapacity: Int(retinaIndexCapacity),
                    candidateCapacity: retinaMaxLights
                )
                try require(
                    retinaCpu.admittedLightCount == retinaMaxLights
                        && retinaCpu.admissionRejectedLights == 0
                        && retinaCpu.perClusterDrops > 0,
                    "Retina scene changed the global pool instead of the local cluster cap"
                )
                try require(retinaCpu.indexCapacityDrops == 0,
                            "Retina compact index capacity was unexpectedly exhausted")
                var firstRetina: GpuResult?
                for iteration in 0..<3 {
                    submit += 1
                    let start = ProcessInfo.processInfo.systemUptime
                    let gpu = try runGpu(
                        api: api,
                        context: retinaContext,
                        queue: queue,
                        generation: retinaGeneration,
                        frameId: UInt64(100 + iteration),
                        submitIndex: submit,
                        width: retinaWidth,
                        height: retinaHeight,
                        lights: retinaDense,
                        hdr: !iteration.isMultiple(of: 2)
                    )
                    let elapsed = ProcessInfo.processInfo.systemUptime - start
                    try require(elapsed < 10,
                                "Retina compact build exceeded its finite-time budget")
                    try requireMatches(gpu, retinaCpu, context: "Retina compact iteration \(iteration)")
                    try require(gpu.indices.contains(0),
                                "Workload admission dropped the first camera-containing light")
                    if let firstRetina {
                        try require(
                            gpu.headers == firstRetina.headers && gpu.indices == firstRetina.indices,
                            "Retina compact build was not bit-deterministic"
                        )
                        try require(
                            (32...68).allSatisfy {
                                readUInt32(gpu.stats, at: $0) == readUInt32(firstRetina.stats, at: $0)
                            },
                            "SDR/HDR changed Retina compact-build counters"
                        )
                    } else {
                        firstRetina = gpu
                    }
                }
            }

            // Ring reuse never waits on CPU: an occupied slot fails closed.
            submit = 15 // slot 0
            let busyFrame = framePacket(
                generation: generation, frameId: 60, submitIndex: submit,
                width: UInt32(width), height: UInt32(height), lightCount: 1, hdr: false
            )
            try setFrame(api, busyFrame)
            let busyPacket = batchPacket(
                generation: generation, frameId: 60, submitIndex: submit, lights: oneLight
            )
            guard let firstBusy = queue.makeCommandBuffer(), let secondBusy = queue.makeCommandBuffer() else {
                throw ValidationFailure.message("Could not create ring-busy command buffers")
            }
            try require(upload(api, context: context, commandBuffer: firstBusy, packet: busyPacket) == 1,
                        "Initial ring-slot submission failed")
            try require(upload(api, context: context, commandBuffer: secondBusy, packet: busyPacket) == -12,
                        "Busy staging slot waited or overwrote instead of failing closed")
            firstBusy.commit()
            secondBusy.commit()
            firstBusy.waitUntilCompleted()
            secondBusy.waitUntilCompleted()

            // Generation resize/teardown releases all context-owned resources explicitly.
            for resize in 1...8 {
                let resizedWidth = UInt32(resize * 16)
                guard let resized = api.createContext(
                    objectPointer(device as AnyObject),
                    UInt64(800 + resize),
                    8,
                    128,
                    resizedWidth / 16,
                    1,
                    UInt32(depthSlices)
                ) else {
                    throw ValidationFailure.message("Context resize \(resize) failed")
                }
                try require(api.contextBufferBytes(resized, 1) == UInt64(resize * depthSlices * 8),
                            "Resized header capacity is incorrect")
                api.releaseContext(resized)
            }

            // Releasing the owning handle while a command is in flight is safe: the completion
            // closure retains the context until GPU consumption and async stats copy finish.
            let retireGeneration: UInt64 = 900
            guard let retiring = api.createContext(
                objectPointer(device as AnyObject), retireGeneration, 8, 256, 1, 1, UInt32(depthSlices)
            ) else {
                throw ValidationFailure.message("Could not create teardown context")
            }
            let retireFrame = framePacket(
                generation: retireGeneration, frameId: 70, submitIndex: 18,
                width: 16, height: 16, lightCount: 1, hdr: false
            )
            try setFrame(api, retireFrame)
            let retirePacket = batchPacket(
                generation: retireGeneration, frameId: 70, submitIndex: 18, lights: oneLight
            )
            guard let retiringCommand = queue.makeCommandBuffer() else {
                throw ValidationFailure.message("Could not create teardown command buffer")
            }
            try require(upload(api, context: retiring, commandBuffer: retiringCommand, packet: retirePacket) == 1,
                        "In-flight teardown batch was rejected")
            api.releaseContext(retiring)
            retiringCommand.commit()
            retiringCommand.waitUntilCompleted()
            try require(retiringCommand.status == .completed,
                        "Context release raced GPU resource lifetime")

            var finalStats = [UInt8](repeating: 0, count: 128)
            try require(finalStats.withUnsafeMutableBytes {
                api.lastCompletedStats(context, $0.baseAddress, UInt64($0.count))
            } == 1, "Final asynchronous statistics snapshot is missing")
            try require(readUInt32(finalStats, at: 72) >= 1, "Ring high-water was not recorded")
            try require(readUInt32(finalStats, at: 76) >= 1, "Ring busy rejection was not recorded")
            try require(readUInt64(finalStats, at: 88) > readUInt64(finalStats, at: 96),
                        "Rejected upload calls were not separated from completed calls")
            print(
                "Native clustered-lighting ABI v1 validation passed on \(device.name) "
                    + "(compact lists, candidate #4095, P/B/U caps, Retina maximum-pool bound, "
                    + "empty/overflow/OOB guards, ring and teardown)"
            )
        } catch {
            fputs("Native clustered-lighting validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
