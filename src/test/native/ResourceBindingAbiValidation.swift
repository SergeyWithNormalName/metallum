import Darwin
import Foundation
import Metal

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case let .message(message): return message
        }
    }
}

private typealias NativeApplyFunction = @convention(c) (
    UnsafeMutableRawPointer?,
    UnsafeRawPointer?,
    UInt64
) -> Int32

private typealias NativeCreateBuffer = @convention(c) (
    UnsafeMutableRawPointer?,
    Int,
    UInt
) -> UnsafeMutableRawPointer?

private typealias NativeReleaseObject = @convention(c) (
    UnsafeMutableRawPointer?
) -> Void

private typealias NativeBufferHandleIsLive = @convention(c) (
    UnsafeMutableRawPointer?
) -> Int32

private typealias NativeDrawIndexedCpuCommands = @convention(c) (
    UnsafeMutableRawPointer?,
    UInt,
    UInt,
    UnsafeMutableRawPointer?,
    UnsafeRawPointer?,
    Int,
    UInt64
) -> Void

private let headerBytes = 32
private let recordBytes = 48

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw ValidationFailure.message(message) }
}

private func writeUInt32(_ value: UInt32, at offset: Int, into bytes: inout [UInt8]) {
    var native = value.littleEndian
    withUnsafeBytes(of: &native) { bytes.replaceSubrange(offset..<(offset + 4), with: $0) }
}

private func writeUInt64(_ value: UInt64, at offset: Int, into bytes: inout [UInt8]) {
    var native = value.littleEndian
    withUnsafeBytes(of: &native) { bytes.replaceSubrange(offset..<(offset + 8), with: $0) }
}

private func objectAddress(_ object: AnyObject) -> UInt64 {
    UInt64(UInt(bitPattern: Unmanaged.passUnretained(object).toOpaque()))
}

private func writeRecord(
    type: UInt32,
    stage: UInt32,
    index: UInt32,
    primary: UInt64,
    secondary: UInt64 = 0,
    offset: UInt64 = 0,
    length: UInt64 = 0,
    recordIndex: Int,
    into bytes: inout [UInt8]
) {
    let record = headerBytes + recordIndex * recordBytes
    writeUInt32(type, at: record, into: &bytes)
    writeUInt32(stage, at: record + 4, into: &bytes)
    writeUInt32(index, at: record + 8, into: &bytes)
    writeUInt32(0, at: record + 12, into: &bytes)
    writeUInt64(primary, at: record + 16, into: &bytes)
    writeUInt64(secondary, at: record + 24, into: &bytes)
    writeUInt64(offset, at: record + 32, into: &bytes)
    writeUInt64(length, at: record + 40, into: &bytes)
}

private func validPacket(buffer: MTLBuffer, texture: MTLTexture, sampler: MTLSamplerState) -> [UInt8] {
    let count = 3
    var bytes = [UInt8](repeating: 0, count: headerBytes + count * recordBytes)
    writeUInt32(1, at: 0, into: &bytes)
    writeUInt32(UInt32(bytes.count), at: 4, into: &bytes)
    writeUInt64(7, at: 8, into: &bytes)
    writeUInt32(UInt32(count), at: 16, into: &bytes)
    writeUInt32(UInt32(recordBytes), at: 20, into: &bytes)
    writeUInt32(64, at: 24, into: &bytes)
    writeUInt32(0, at: 28, into: &bytes)
    writeRecord(
        type: 1, stage: 1, index: 0,
        primary: objectAddress(buffer), offset: 16, length: 16,
        recordIndex: 0, into: &bytes
    )
    writeRecord(
        type: 2, stage: 2, index: 1,
        primary: objectAddress(texture), secondary: objectAddress(sampler),
        recordIndex: 1, into: &bytes
    )
    writeRecord(
        type: 3, stage: 3, index: 2,
        primary: objectAddress(texture),
        recordIndex: 2, into: &bytes
    )
    return bytes
}

private func apply(
    _ function: NativeApplyFunction,
    encoder: MTLRenderCommandEncoder?,
    bytes: [UInt8]
) -> Int32 {
    let encoderPointer = encoder.map { Unmanaged.passUnretained($0 as AnyObject).toOpaque() }
    return bytes.withUnsafeBytes { raw in
        function(encoderPointer, raw.baseAddress, UInt64(raw.count))
    }
}

@main
private enum ResourceBindingAbiValidationMain {
    static func main() {
        do {
            let arguments = CommandLine.arguments
            try require(arguments.count == 2, "Usage: ResourceBindingAbiValidation <libmetallum.dylib>")
            guard let library = dlopen(arguments[1], RTLD_NOW | RTLD_LOCAL) else {
                let detail = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
                throw ValidationFailure.message("Could not load native library: \(detail)")
            }
            defer { dlclose(library) }
            guard let symbol = dlsym(library, "metallum_MTLRenderCommandEncoder_applyResourceBindings_v1") else {
                throw ValidationFailure.message("Native resource binding ABI symbol is missing")
            }
            let nativeApply = unsafeBitCast(symbol, to: NativeApplyFunction.self)
            guard let createBufferSymbol = dlsym(library, "metallum_create_buffer"),
                  let releaseObjectSymbol = dlsym(library, "metallum_release_object"),
                  let bufferHandleIsLiveSymbol = dlsym(
                      library,
                      "metallum_buffer_handle_is_live"
                  ),
                  let drawIndexedCpuCommandsSymbol = dlsym(
                      library,
                      "metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesCpuCommands"
                  ) else {
                throw ValidationFailure.message("Native buffer lifecycle ABI symbols are missing")
            }
            let nativeCreateBuffer = unsafeBitCast(createBufferSymbol, to: NativeCreateBuffer.self)
            let nativeReleaseObject = unsafeBitCast(releaseObjectSymbol, to: NativeReleaseObject.self)
            let nativeBufferHandleIsLive = unsafeBitCast(
                bufferHandleIsLiveSymbol,
                to: NativeBufferHandleIsLive.self
            )
            let nativeDrawIndexedCpuCommands = unsafeBitCast(
                drawIndexedCpuCommandsSymbol,
                to: NativeDrawIndexedCpuCommands.self
            )

            guard let device = MTLCreateSystemDefaultDevice(),
                  let queue = device.makeCommandQueue(),
                  let commandBuffer = queue.makeCommandBuffer() else {
                throw ValidationFailure.message("Could not create Metal validation resources")
            }
            let devicePointer = Unmanaged.passUnretained(device as AnyObject).toOpaque()
            guard let lifecycleProbe = nativeCreateBuffer(
                devicePointer,
                64,
                MTLResourceOptions.storageModeShared.rawValue
            ) else {
                throw ValidationFailure.message("Native ABI could not create a lifecycle probe")
            }
            let lifecycleProbeObject = Unmanaged<AnyObject>.fromOpaque(
                lifecycleProbe
            ).takeUnretainedValue()
            try require(
                lifecycleProbeObject is MTLBuffer,
                "General native lifecycle probe was not an MTLBuffer"
            )
            try require(
                nativeBufferHandleIsLive(lifecycleProbe) == 1,
                "General native buffer handle was not registered"
            )
            nativeReleaseObject(lifecycleProbe)
            try require(
                nativeBufferHandleIsLive(lifecycleProbe) == 0,
                "Generally released buffer handle remained eligible for GPU encoding"
            )

            guard let bufferHandle = nativeCreateBuffer(
                devicePointer,
                64,
                MTLResourceOptions.storageModeShared.rawValue
            ) else {
                throw ValidationFailure.message("Native ABI could not create a validation buffer")
            }
            defer { nativeReleaseObject(bufferHandle) }
            let bufferObject = Unmanaged<AnyObject>.fromOpaque(bufferHandle).takeUnretainedValue()
            guard let buffer = bufferObject as? MTLBuffer else {
                throw ValidationFailure.message("Native buffer ABI returned a non-MTLBuffer object")
            }
            let textureDescriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .rgba8Unorm,
                width: 4,
                height: 4,
                mipmapped: false
            )
            textureDescriptor.usage = [.renderTarget, .shaderRead]
            guard let texture = device.makeTexture(descriptor: textureDescriptor) else {
                throw ValidationFailure.message("Could not create Metal validation texture")
            }
            let samplerDescriptor = MTLSamplerDescriptor()
            guard let sampler = device.makeSamplerState(descriptor: samplerDescriptor) else {
                throw ValidationFailure.message("Could not create Metal validation sampler")
            }
            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = texture
            pass.colorAttachments[0].loadAction = .dontCare
            pass.colorAttachments[0].storeAction = .store
            guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
                throw ValidationFailure.message("Could not create Metal validation encoder")
            }

            let staleDrawCommand: [UInt32] = [3, 1, 0, 0, 0]
            withExtendedLifetime(lifecycleProbeObject) {
                staleDrawCommand.withUnsafeBytes { raw in
                    nativeDrawIndexedCpuCommands(
                        Unmanaged.passUnretained(encoder as AnyObject).toOpaque(),
                        MTLPrimitiveType.triangle.rawValue,
                        MTLIndexType.uint16.rawValue,
                        lifecycleProbe,
                        raw.baseAddress,
                        1,
                        UInt64(raw.count)
                    )
                }
            }

            let valid = validPacket(buffer: buffer, texture: texture, sampler: sampler)
            try require(apply(nativeApply, encoder: nil, bytes: valid) == -1,
                        "Null encoder was not rejected")
            let nullPacketResult = nativeApply(
                Unmanaged.passUnretained(encoder as AnyObject).toOpaque(), nil, UInt64(valid.count)
            )
            try require(nullPacketResult == -1, "Null packet was not rejected")
            let shortCapacity = valid.withUnsafeBytes { raw in
                nativeApply(Unmanaged.passUnretained(encoder as AnyObject).toOpaque(), raw.baseAddress, 31)
            }
            try require(shortCapacity == -2, "Short packet capacity was not rejected")
            let truncatedRecords = valid.withUnsafeBytes { raw in
                nativeApply(
                    Unmanaged.passUnretained(encoder as AnyObject).toOpaque(),
                    raw.baseAddress,
                    UInt64(valid.count - 1)
                )
            }
            try require(truncatedRecords == -4,
                        "Header byte size exceeding packet capacity was not rejected")

            var invalidVersion = valid
            writeUInt32(2, at: 0, into: &invalidVersion)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidVersion) == -3,
                        "Version mismatch was not rejected")
            var invalidSize = valid
            writeUInt32(UInt32(valid.count - 1), at: 4, into: &invalidSize)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidSize) == -4,
                        "Byte-size mismatch was not rejected")
            var invalidCapabilities = valid
            writeUInt64(8, at: 8, into: &invalidCapabilities)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidCapabilities) == -5,
                        "Unsupported capability was not rejected")
            var missingCapability = valid
            writeUInt64(6, at: 8, into: &missingCapability)
            try require(apply(nativeApply, encoder: encoder, bytes: missingCapability) == -5,
                        "Record missing its declared capability was not rejected")
            var invalidCount = valid
            writeUInt32(65, at: 16, into: &invalidCount)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidCount) == -6,
                        "Out-of-range record count was not rejected")
            var invalidLayout = valid
            writeUInt32(40, at: 20, into: &invalidLayout)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidLayout) == -7,
                        "Record stride mismatch was not rejected")
            var invalidType = valid
            writeUInt32(99, at: headerBytes, into: &invalidType)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidType) == -8,
                        "Unknown record type was not rejected")
            var invalidStage = valid
            writeUInt32(4, at: headerBytes + 4, into: &invalidStage)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidStage) == -9,
                        "Invalid stage mask was not rejected")
            var invalidIndex = valid
            writeUInt32(31, at: headerBytes + 8, into: &invalidIndex)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidIndex) == -10,
                        "Out-of-range buffer binding was not rejected")
            var nullHandle = valid
            writeUInt64(0, at: headerBytes + 16, into: &nullHandle)
            try require(apply(nativeApply, encoder: encoder, bytes: nullHandle) == -11,
                        "Null resource handle was not rejected")
            var invalidRange = valid
            writeUInt64(0, at: headerBytes + 40, into: &invalidRange)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidRange) == -12,
                        "Empty uniform range was not rejected")
            var duplicateIndex = valid
            writeUInt32(0, at: headerBytes + 2 * recordBytes + 8, into: &duplicateIndex)
            try require(apply(nativeApply, encoder: encoder, bytes: duplicateIndex) == -13,
                        "Duplicate binding index was not rejected")
            var invalidObjectType = valid
            writeUInt64(objectAddress(texture), at: headerBytes + 16, into: &invalidObjectType)
            try require(apply(nativeApply, encoder: encoder, bytes: invalidObjectType) == -11,
                        "Unregistered uniform-buffer handle was not rejected")
            var outOfBounds = valid
            writeUInt64(60, at: headerBytes + 32, into: &outOfBounds)
            writeUInt64(16, at: headerBytes + 40, into: &outOfBounds)
            try require(apply(nativeApply, encoder: encoder, bytes: outOfBounds) == -15,
                        "Out-of-bounds native buffer range was not rejected")
            var intOverflow = valid
            writeUInt64(UInt64(Int.max) + 1, at: headerBytes + 32, into: &intOverflow)
            try require(apply(nativeApply, encoder: encoder, bytes: intOverflow) == -12,
                        "Uniform offset exceeding Int.max was not rejected")

            try require(apply(nativeApply, encoder: encoder, bytes: valid) == 1,
                        "Valid resource binding packet was rejected")
            encoder.endEncoding()
            commandBuffer.commit()
            commandBuffer.waitUntilCompleted()
            try require(commandBuffer.status == .completed,
                        "Command buffer failed after applying valid resource bindings")
            print("Native resource binding ABI validation passed (19 negative cases + stale indexed draw)")
        } catch {
            fputs("Native resource binding ABI validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
