import Darwin
import Foundation
import Metal

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case let .message(message): message
        }
    }
}

private typealias NativeEncodeBatch = @convention(c) (
    UnsafeMutableRawPointer?,
    UnsafeMutableRawPointer?,
    UnsafeRawPointer?,
    UInt64,
    UInt64
) -> Int32

private let recordBytes = 32
private let legacyVertexBytes = 20
private let legacyLightUshortIndex = 8

private struct PatchRecord {
    let geometry: MTLBuffer?
    let sidecar: MTLBuffer?
    let vertexOffset: UInt64
    let vertexCount: UInt64
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw ValidationFailure.message(message) }
}

private func objectPointer(_ object: AnyObject) -> UnsafeMutableRawPointer {
    Unmanaged.passUnretained(object).toOpaque()
}

private func objectAddress(_ object: AnyObject?) -> UInt64 {
    guard let object else { return 0 }
    return UInt64(UInt(bitPattern: objectPointer(object)))
}

private func writeUInt64(_ value: UInt64, at offset: Int, into bytes: inout [UInt8]) {
    var littleEndian = value.littleEndian
    withUnsafeBytes(of: &littleEndian) {
        bytes.replaceSubrange(offset..<(offset + MemoryLayout<UInt64>.size), with: $0)
    }
}

private func packet(_ records: [PatchRecord]) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: records.count * recordBytes)
    for (index, record) in records.enumerated() {
        let base = index * recordBytes
        writeUInt64(objectAddress(record.geometry), at: base, into: &bytes)
        writeUInt64(objectAddress(record.sidecar), at: base + 8, into: &bytes)
        writeUInt64(record.vertexOffset, at: base + 16, into: &bytes)
        writeUInt64(record.vertexCount, at: base + 24, into: &bytes)
    }
    return bytes
}

private func encode(
    _ function: NativeEncodeBatch,
    commandBuffer: MTLCommandBuffer?,
    fence: MTLFence?,
    records: [PatchRecord],
    capacity: UInt64? = nil,
    count: UInt64? = nil
) -> Int32 {
    let bytes = packet(records)
    return bytes.withUnsafeBytes { raw in
        function(
            commandBuffer.map { objectPointer($0 as AnyObject) },
            fence.map { objectPointer($0 as AnyObject) },
            raw.baseAddress,
            capacity ?? UInt64(raw.count),
            count ?? UInt64(records.count)
        )
    }
}

private func fill(_ buffer: MTLBuffer, with value: UInt16) {
    let count = buffer.length / MemoryLayout<UInt16>.stride
    let values = buffer.contents().bindMemory(to: UInt16.self, capacity: count)
    for index in 0..<count {
        values[index] = value
    }
}

private func requireFilled(_ buffer: MTLBuffer, with value: UInt16, _ message: String) throws {
    let count = buffer.length / MemoryLayout<UInt16>.stride
    let values = buffer.contents().bindMemory(to: UInt16.self, capacity: count)
    try require((0..<count).allSatisfy { values[$0] == value }, message)
}

private func validateFailureAtomicity(
    _ nativeEncode: NativeEncodeBatch,
    device: MTLDevice,
    queue: MTLCommandQueue
) throws {
    let vertices = 8
    guard let geometry = device.makeBuffer(
              length: vertices * legacyVertexBytes,
              options: .storageModeShared
          ),
          let secondGeometry = device.makeBuffer(
              length: vertices * legacyVertexBytes,
              options: .storageModeShared
          ),
          let sidecar = device.makeBuffer(
              length: vertices * MemoryLayout<UInt16>.stride,
              options: .storageModeShared
          ),
          let commandBuffer = queue.makeCommandBuffer(),
          let fence = device.makeFence() else {
        throw ValidationFailure.message("Could not create native preflight validation resources")
    }
    let sentinel: UInt16 = 0x6BAD
    fill(geometry, with: sentinel)
    fill(secondGeometry, with: sentinel)
    fill(sidecar, with: 0x1234)

    let valid = [PatchRecord(geometry: geometry, sidecar: sidecar, vertexOffset: 1, vertexCount: 2)]
    try require(encode(nativeEncode, commandBuffer: nil, fence: fence, records: valid) == -1,
                "Null command buffer was not rejected")
    try require(encode(nativeEncode, commandBuffer: commandBuffer, fence: nil, records: valid) == -1,
                "Null fence was not rejected")
    let nullPacket = nativeEncode(
        objectPointer(commandBuffer as AnyObject),
        objectPointer(fence as AnyObject),
        nil,
        UInt64(recordBytes),
        1
    )
    try require(nullPacket == -1, "Null batch packet was not rejected")
    try require(encode(
        nativeEncode,
        commandBuffer: commandBuffer,
        fence: fence,
        records: valid,
        capacity: UInt64(recordBytes - 1)
    ) == -2, "Truncated batch packet was not rejected")
    try require(encode(
        nativeEncode,
        commandBuffer: commandBuffer,
        fence: fence,
        records: valid,
        count: 4_097
    ) == -2, "Oversized batch count was not rejected")

    let nullHandle = [PatchRecord(geometry: nil, sidecar: sidecar, vertexOffset: 0, vertexCount: 1)]
    try require(encode(nativeEncode, commandBuffer: commandBuffer, fence: fence, records: nullHandle) == -3,
                "Null native buffer handle was not rejected")

    let textureDescriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .rgba8Unorm,
        width: 1,
        height: 1,
        mipmapped: false
    )
    guard let texture = device.makeTexture(descriptor: textureDescriptor) else {
        throw ValidationFailure.message("Could not create wrong-type validation object")
    }
    var wrongTypeBytes = packet(valid)
    writeUInt64(objectAddress(texture), at: 0, into: &wrongTypeBytes)
    let wrongType = wrongTypeBytes.withUnsafeBytes { raw in
        nativeEncode(
            objectPointer(commandBuffer as AnyObject),
            objectPointer(fence as AnyObject),
            raw.baseAddress,
            UInt64(raw.count),
            1
        )
    }
    try require(wrongType == -4, "Non-buffer native object was not rejected")

    let emptyRange = [PatchRecord(geometry: geometry, sidecar: sidecar, vertexOffset: 0, vertexCount: 0)]
    try require(encode(nativeEncode, commandBuffer: commandBuffer, fence: fence, records: emptyRange) == -6,
                "Empty vertex range was not rejected")
    let geometryOutOfBounds = [PatchRecord(
        geometry: geometry,
        sidecar: sidecar,
        vertexOffset: UInt64(vertices - 1),
        vertexCount: 2
    )]
    try require(encode(
        nativeEncode,
        commandBuffer: commandBuffer,
        fence: fence,
        records: geometryOutOfBounds
    ) == -6, "Out-of-bounds geometry range was not rejected")
    let overflowingRange = [PatchRecord(
        geometry: geometry,
        sidecar: sidecar,
        vertexOffset: UInt64.max,
        vertexCount: 1
    )]
    try require(encode(nativeEncode, commandBuffer: commandBuffer, fence: fence, records: overflowingRange) == -6,
                "Overflowing vertex range was not rejected")

    let geometryOverlap = [
        PatchRecord(geometry: geometry, sidecar: sidecar, vertexOffset: 0, vertexCount: 3),
        PatchRecord(geometry: geometry, sidecar: sidecar, vertexOffset: 2, vertexCount: 2)
    ]
    try require(encode(nativeEncode, commandBuffer: commandBuffer, fence: fence, records: geometryOverlap) == -7,
                "Overlapping geometry ranges were not rejected")
    let sidecarOverlap = [
        PatchRecord(geometry: geometry, sidecar: sidecar, vertexOffset: 0, vertexCount: 3),
        PatchRecord(geometry: secondGeometry, sidecar: sidecar, vertexOffset: 2, vertexCount: 2)
    ]
    try require(encode(nativeEncode, commandBuffer: commandBuffer, fence: fence, records: sidecarOverlap) == -7,
                "Overlapping sidecar ranges were not rejected")

    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try require(commandBuffer.status == .completed,
                "Command buffer failed after native preflight rejections")
    try requireFilled(geometry, with: sentinel,
                      "Rejected native batch mutated the first geometry buffer")
    try requireFilled(secondGeometry, with: sentinel,
                      "Rejected native batch mutated the second geometry buffer")
}

private func validateGpuBatch(
    _ nativeEncode: NativeEncodeBatch,
    device: MTLDevice,
    queue: MTLCommandQueue
) throws {
    let vertices = 8
    let geometryBytes = vertices * legacyVertexBytes
    let sidecarBytes = vertices * MemoryLayout<UInt16>.stride
    guard let geometryUpload = device.makeBuffer(length: geometryBytes, options: .storageModeShared),
          let sidecarUpload = device.makeBuffer(length: sidecarBytes, options: .storageModeShared),
          let geometry = device.makeBuffer(length: geometryBytes, options: .storageModePrivate),
          let sidecar = device.makeBuffer(length: sidecarBytes, options: .storageModePrivate),
          let geometryReadback = device.makeBuffer(length: geometryBytes, options: .storageModeShared),
          let sidecarReadback = device.makeBuffer(length: sidecarBytes, options: .storageModeShared),
          let commandBuffer = queue.makeCommandBuffer(),
          let upload = commandBuffer.makeBlitCommandEncoder(),
          let fence = device.makeFence() else {
        throw ValidationFailure.message("Could not create native GPU batch validation resources")
    }

    let sentinel: UInt16 = 0x7BAD
    fill(geometryUpload, with: sentinel)
    let expectedLights: [UInt16] = [
        0x0101, 0x1212, 0x2323, 0x3434,
        0x4545, 0x5656, 0x6767, 0x7878
    ]
    let sidecarValues = sidecarUpload.contents().bindMemory(
        to: UInt16.self,
        capacity: expectedLights.count
    )
    for (index, light) in expectedLights.enumerated() {
        sidecarValues[index] = light
    }

    upload.waitForFence(fence)
    upload.copy(from: geometryUpload, sourceOffset: 0, to: geometry, destinationOffset: 0, size: geometryBytes)
    upload.copy(from: sidecarUpload, sourceOffset: 0, to: sidecar, destinationOffset: 0, size: sidecarBytes)
    upload.updateFence(fence)
    upload.endEncoding()

    let records = [
        PatchRecord(geometry: geometry, sidecar: sidecar, vertexOffset: 1, vertexCount: 2),
        PatchRecord(geometry: geometry, sidecar: sidecar, vertexOffset: 5, vertexCount: 2)
    ]
    try require(encode(nativeEncode, commandBuffer: commandBuffer, fence: fence, records: records) == 1,
                "Valid native Sodium light patch batch was rejected")

    guard let readback = commandBuffer.makeBlitCommandEncoder() else {
        throw ValidationFailure.message("Could not create native patch readback encoder")
    }
    readback.waitForFence(fence)
    readback.copy(from: geometry, sourceOffset: 0, to: geometryReadback, destinationOffset: 0, size: geometryBytes)
    readback.copy(from: sidecar, sourceOffset: 0, to: sidecarReadback, destinationOffset: 0, size: sidecarBytes)
    readback.updateFence(fence)
    readback.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try require(commandBuffer.status == .completed,
                "Native Sodium light patch command buffer did not complete")

    let geometryValues = geometryReadback.contents().bindMemory(
        to: UInt16.self,
        capacity: geometryBytes / MemoryLayout<UInt16>.stride
    )
    let patchedVertices: Set<Int> = [1, 2, 5, 6]
    for vertex in 0..<vertices {
        let actual = geometryValues[vertex * 10 + legacyLightUshortIndex]
        let expected = patchedVertices.contains(vertex) ? expectedLights[vertex] : sentinel
        try require(actual == expected,
                    "Legacy light mismatch at vertex \(vertex): got \(actual), expected \(expected)")
    }
    let sidecarResult = sidecarReadback.contents().bindMemory(
        to: UInt16.self,
        capacity: expectedLights.count
    )
    for (index, expected) in expectedLights.enumerated() {
        try require(sidecarResult[index] == expected,
                    "Native patch mutated sidecar vertex \(index)")
    }
}

@main
private enum SodiumLightLegacyPatchValidationMain {
    static func main() {
        do {
            let arguments = CommandLine.arguments
            try require(arguments.count == 2,
                        "Usage: SodiumLightLegacyPatchValidation <libmetallum.dylib>")
            guard let library = dlopen(arguments[1], RTLD_NOW | RTLD_LOCAL) else {
                let detail = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
                throw ValidationFailure.message("Could not load native library: \(detail)")
            }
            defer { dlclose(library) }
            guard let symbol = dlsym(
                library,
                "metallum_MTLCommandBuffer_encodeSodiumLightLegacyPatchBatch_v1"
            ) else {
                throw ValidationFailure.message("Native Sodium light patch batch symbol is missing")
            }
            guard let device = MTLCreateSystemDefaultDevice(),
                  let queue = device.makeCommandQueue() else {
                throw ValidationFailure.message("No Metal validation device or command queue is available")
            }

            let nativeEncode = unsafeBitCast(symbol, to: NativeEncodeBatch.self)
            try validateFailureAtomicity(nativeEncode, device: device, queue: queue)
            try validateGpuBatch(nativeEncode, device: device, queue: queue)
            print("Native Sodium legacy-light patch validation passed on \(device.name)")
        } catch {
            fputs("Native Sodium legacy-light patch validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
