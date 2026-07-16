import Darwin
import Foundation
import Metal

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)
    var description: String { switch self { case let .message(message): message } }
}

private typealias SetFrameState = @convention(c) (UnsafeRawPointer?, UInt64) -> Int32
private typealias EncodeDiagnostics = @convention(c) (
    UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?
) -> Int32

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw ValidationFailure.message(message) }
}

private func objectPointer(_ object: AnyObject) -> UnsafeRawPointer {
    UnsafeRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

private func writeUInt32(_ value: UInt32, at offset: Int, into bytes: inout [UInt8]) {
    var value = value.littleEndian
    withUnsafeBytes(of: &value) { bytes.replaceSubrange(offset..<(offset + $0.count), with: $0) }
}

private func writeUInt64(_ value: UInt64, at offset: Int, into bytes: inout [UInt8]) {
    var value = value.littleEndian
    withUnsafeBytes(of: &value) { bytes.replaceSubrange(offset..<(offset + $0.count), with: $0) }
}

private func writeFloat(_ value: Float, at offset: Int, into bytes: inout [UInt8]) {
    writeUInt32(value.bitPattern, at: offset, into: &bytes)
}

private func writeDouble(_ value: Double, at offset: Int, into bytes: inout [UInt8]) {
    writeUInt64(value.bitPattern, at: offset, into: &bytes)
}

private func framePacket(
    width: Int,
    height: Int,
    resetMask: UInt64,
    currentCameraX: Double,
    previousCameraX: Double,
    previousProjectionScaleX: Float = 1
) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 848)
    writeUInt32(3, at: 0, into: &bytes)
    writeUInt32(848, at: 4, into: &bytes)
    writeUInt32(1, at: 8, into: &bytes)
    writeUInt32(2, at: 12, into: &bytes)
    writeUInt64(1, at: 16, into: &bytes)
    writeUInt64(3, at: 24, into: &bytes)
    writeUInt64(1, at: 32, into: &bytes)
    writeUInt64(resetMask == 0 ? 1 : 2, at: 40, into: &bytes)
    writeUInt64(1, at: 48, into: &bytes)
    writeUInt64(1, at: 56, into: &bytes)
    writeUInt64(1, at: 64, into: &bytes)
    writeUInt64(1, at: 72, into: &bytes)
    writeUInt64(1, at: 80, into: &bytes)
    writeUInt64(resetMask, at: 88, into: &bytes)
    writeUInt32(UInt32(width), at: 124, into: &bytes)
    writeUInt32(UInt32(height), at: 128, into: &bytes)
    writeUInt32(UInt32(width), at: 132, into: &bytes)
    writeUInt32(UInt32(height), at: 136, into: &bytes)
    writeFloat(1 / 60, at: 148, into: &bytes)
    writeFloat(0.05, at: 152, into: &bytes)
    writeFloat(1024, at: 156, into: &bytes)
    writeFloat(1, at: 168, into: &bytes)
    writeFloat(1, at: 172, into: &bytes)
    writeFloat(1, at: 176, into: &bytes)
    writeFloat(1, at: 180, into: &bytes)
    writeUInt64(UInt64(width * height * 5 * 3), at: 240, into: &bytes)
    writeDouble(currentCameraX, at: 280, into: &bytes)
    writeDouble(previousCameraX, at: 304, into: &bytes)
    for matrix in 0..<8 {
        for diagonal in 0..<4 {
            let value: Float = matrix == 5 && diagonal == 0 ? previousProjectionScaleX : 1
            writeFloat(value, at: 328 + matrix * 64 + diagonal * 20, into: &bytes)
        }
    }
    return bytes
}

private func runCase(
    device: MTLDevice,
    queue: MTLCommandQueue,
    setFrameState: SetFrameState,
    encode: EncodeDiagnostics,
    width: Int,
    height: Int,
    resetMask: UInt64 = 0,
    currentCameraX: Double = 0,
    previousCameraX: Double = 0,
    previousProjectionScaleX: Float = 1
) throws -> (motion: SIMD2<Float>, reactive: UInt8) {
    let packet = framePacket(
        width: width,
        height: height,
        resetMask: resetMask,
        currentCameraX: currentCameraX,
        previousCameraX: previousCameraX,
        previousProjectionScaleX: previousProjectionScaleX
    )
    try require(packet.withUnsafeBytes { setFrameState($0.baseAddress, UInt64($0.count)) } == 1,
                "Native FrameState admission failed")

    func texture(_ format: MTLPixelFormat, _ usage: MTLTextureUsage) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format, width: width, height: height, mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = usage
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("Could not allocate diagnostic texture")
        }
        return texture
    }
    let depth = try texture(.depth32Float, [.renderTarget, .shaderRead])
    let motion = try texture(.rg16Float, [.renderTarget, .shaderRead])
    let reactive = try texture(.r8Unorm, [.renderTarget, .shaderRead])
    guard let fence = device.makeFence(), let commandBuffer = queue.makeCommandBuffer() else {
        throw ValidationFailure.message("Could not allocate command-buffer synchronization")
    }
    let clear = MTLRenderPassDescriptor()
    clear.depthAttachment.texture = depth
    clear.depthAttachment.loadAction = .clear
    clear.depthAttachment.storeAction = .store
    clear.depthAttachment.clearDepth = 0.5
    guard let clearEncoder = commandBuffer.makeRenderCommandEncoder(descriptor: clear) else {
        throw ValidationFailure.message("Could not clear diagnostic depth")
    }
    clearEncoder.updateFence(fence, after: .fragment)
    clearEncoder.endEncoding()
    try require(encode(
        objectPointer(commandBuffer), objectPointer(depth), objectPointer(motion),
        objectPointer(reactive), objectPointer(fence)
    ) == 1, "Native diagnostic render pass failed")

    let motionRowBytes = ((width * 4 + 255) / 256) * 256
    let reactiveRowBytes = ((width + 255) / 256) * 256
    guard let motionReadback = device.makeBuffer(length: motionRowBytes * height, options: .storageModeShared),
          let reactiveReadback = device.makeBuffer(length: reactiveRowBytes * height, options: .storageModeShared),
          let blit = commandBuffer.makeBlitCommandEncoder() else {
        throw ValidationFailure.message("Could not allocate diagnostic readback")
    }
    let size = MTLSize(width: width, height: height, depth: 1)
    blit.copy(from: motion, sourceSlice: 0, sourceLevel: 0, sourceOrigin: .init(), sourceSize: size,
              to: motionReadback, destinationOffset: 0, destinationBytesPerRow: motionRowBytes,
              destinationBytesPerImage: motionRowBytes * height)
    blit.copy(from: reactive, sourceSlice: 0, sourceLevel: 0, sourceOrigin: .init(), sourceSize: size,
              to: reactiveReadback, destinationOffset: 0, destinationBytesPerRow: reactiveRowBytes,
              destinationBytesPerImage: reactiveRowBytes * height)
    blit.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try require(commandBuffer.status == .completed, "Diagnostic command buffer failed")
    let half = motionReadback.contents().bindMemory(to: UInt16.self, capacity: 2)
    return (
        SIMD2(Float(Float16(bitPattern: half[0])), Float(Float16(bitPattern: half[1]))),
        reactiveReadback.contents().load(as: UInt8.self)
    )
}

@main
private enum TemporalDiagnosticRuntimeValidationMain {
    static func main() {
        do {
            try require(CommandLine.arguments.count == 2,
                        "Usage: TemporalDiagnosticRuntimeValidation <libmetallum.dylib>")
            guard let handle = dlopen(CommandLine.arguments[1], RTLD_NOW | RTLD_LOCAL),
                  let setSymbol = dlsym(handle, "metallum_set_frame_state_v3"),
                  let encodeSymbol = dlsym(handle, "metallum_encode_temporal_diagnostics_v1") else {
                throw ValidationFailure.message("Native temporal diagnostic symbols are unavailable")
            }
            defer { dlclose(handle) }
            guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
                throw ValidationFailure.message("No Metal device/queue is available")
            }
            let setFrameState = unsafeBitCast(setSymbol, to: SetFrameState.self)
            let encode = unsafeBitCast(encodeSymbol, to: EncodeDiagnostics.self)
            let transitionDescriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .depth32Float, width: 1, height: 1, mipmapped: false
            )
            transitionDescriptor.usage = [.renderTarget, .shaderRead]
            let motionDescriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .rg16Float, width: 1, height: 1, mipmapped: false
            )
            motionDescriptor.usage = [.renderTarget, .shaderRead]
            let reactiveDescriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .r8Unorm, width: 1, height: 1, mipmapped: false
            )
            reactiveDescriptor.usage = [.renderTarget, .shaderRead]
            guard let transitionDepth = device.makeTexture(descriptor: transitionDescriptor),
                  let transitionMotion = device.makeTexture(descriptor: motionDescriptor),
                  let transitionReactive = device.makeTexture(descriptor: reactiveDescriptor),
                  let transitionFence = device.makeFence(),
                  let transitionCommandBuffer = queue.makeCommandBuffer() else {
                throw ValidationFailure.message("Could not allocate transition diagnostic resources")
            }
            try require(encode(
                objectPointer(transitionCommandBuffer), objectPointer(transitionDepth),
                objectPointer(transitionMotion), objectPointer(transitionReactive),
                objectPointer(transitionFence)
            ) == 0, "Missing first FrameState must skip diagnostics without disabling them")
            let stationary = try runCase(
                device: device, queue: queue, setFrameState: setFrameState, encode: encode,
                width: 64, height: 64
            )
            try require(abs(stationary.motion.x) <= 0.01 && abs(stationary.motion.y) <= 0.01
                            && stationary.reactive == 0,
                        "Static diagnostic output mismatch")
            let moved = try runCase(
                device: device, queue: queue, setFrameState: setFrameState, encode: encode,
                width: 64, height: 64, currentCameraX: 1
            )
            try require(moved.motion.x.isFinite && moved.motion.x > 0 && moved.reactive == 0,
                        "Camera translation direction is invalid")
            let resized = try runCase(
                device: device, queue: queue, setFrameState: setFrameState, encode: encode,
                width: 96, height: 48, previousProjectionScaleX: 0.75
            )
            try require(resized.motion.x.isFinite && resized.motion.y.isFinite,
                        "Resize/FOV diagnostic output is non-finite")
            let reset = try runCase(
                device: device, queue: queue, setFrameState: setFrameState, encode: encode,
                width: 64, height: 64, resetMask: 1 << 4, currentCameraX: 1
            )
            try require(abs(reset.motion.x) <= 0.01 && abs(reset.motion.y) <= 0.01
                            && reset.reactive == 255,
                        "Teleport/dimension reset output mismatch")
            print("Temporal diagnostic runtime validation passed (transition, static, camera, resize/FOV, reset)")
        } catch {
            fputs("Temporal diagnostic runtime validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
