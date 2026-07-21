import Darwin
import Foundation
import Metal

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)
    var description: String { switch self { case let .message(message): message } }
}

private typealias InitializePipelines = @convention(c) (UnsafeRawPointer?) -> Int32
private typealias SetFrameState = @convention(c) (UnsafeRawPointer?, UInt64) -> Int32
private typealias EncodeDiagnostics = @convention(c) (
    UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?
) -> Int32
private typealias EncodeBackdrop = @convention(c) (
    UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?,
    Int32, Int32, Int32, Int32, Int32, Int32, Float, Float, Float
) -> Int32
private typealias EncodeCoherentMenuBlur = @convention(c) (
    UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, UnsafeRawPointer?, Float, Float
) -> Int32
private typealias CreateSampler = @convention(c) (
    UnsafeRawPointer?, UInt, UInt, UInt, UInt, UInt, UInt, Int32, Double, Double
) -> UnsafeMutableRawPointer?

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
    previousProjectionScaleX: Float = 1,
    temporalProduction: Bool = false,
    temporalHdrPrecompose: Bool = false
) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 848)
    let displayWidth = temporalProduction ? width * 3 / 2 : width
    let displayHeight = temporalProduction ? height * 3 / 2 : height
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
    writeUInt64(temporalProduction ? 1 << 1 : 0, at: 96, into: &bytes)
    writeUInt32(temporalHdrPrecompose ? 1 : 0, at: 104, into: &bytes)
    writeUInt32(temporalHdrPrecompose ? 1 : 0, at: 112, into: &bytes)
    writeUInt32(UInt32(width), at: 124, into: &bytes)
    writeUInt32(UInt32(height), at: 128, into: &bytes)
    writeUInt32(UInt32(displayWidth), at: 132, into: &bytes)
    writeUInt32(UInt32(displayHeight), at: 136, into: &bytes)
    writeFloat(1 / 60, at: 148, into: &bytes)
    writeFloat(0.05, at: 152, into: &bytes)
    writeFloat(1024, at: 156, into: &bytes)
    writeFloat(1, at: 168, into: &bytes)
    writeFloat(1, at: 172, into: &bytes)
    writeFloat(1, at: 176, into: &bytes)
    writeFloat(1, at: 180, into: &bytes)
    writeUInt64(temporalHdrPrecompose ? UInt64(width * height * 8) : 0, at: 200, into: &bytes)
    writeUInt64(temporalHdrPrecompose ? UInt64(width * height * 8) : 0, at: 208, into: &bytes)
    writeUInt64(temporalProduction ? UInt64(width * height * 5 * 3) : 0, at: 224, into: &bytes)
    writeUInt64(temporalProduction ? 0 : UInt64(width * height * 5 * 3), at: 240, into: &bytes)
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
    previousProjectionScaleX: Float = 1,
    temporalProduction: Bool = false,
    temporalHdrPrecompose: Bool = false,
    backdrop: EncodeBackdrop? = nil,
    coherentBlur: EncodeCoherentMenuBlur? = nil
) throws -> (motion: SIMD2<Float>, reactive: UInt8) {
    let packet = framePacket(
        width: width,
        height: height,
        resetMask: resetMask,
        currentCameraX: currentCameraX,
        previousCameraX: previousCameraX,
        previousProjectionScaleX: previousProjectionScaleX,
        temporalProduction: temporalProduction,
        temporalHdrPrecompose: temporalHdrPrecompose
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
    let source = try texture(temporalHdrPrecompose ? .rgba16Float : .rgba8Unorm, [.renderTarget, .shaderRead])
    let displayWidth = temporalProduction ? width * 3 / 2 : width
    let displayHeight = temporalProduction ? height * 3 / 2 : height
    let destinationDescriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .rgba8Unorm, width: displayWidth, height: displayHeight, mipmapped: false
    )
    destinationDescriptor.storageMode = .private
    destinationDescriptor.usage = [.renderTarget, .shaderRead]
    guard let destination = device.makeTexture(descriptor: destinationDescriptor) else {
        throw ValidationFailure.message("Could not allocate Temporal production destination")
    }
    guard let fence = device.makeFence(), let commandBuffer = queue.makeCommandBuffer() else {
        throw ValidationFailure.message("Could not allocate command-buffer synchronization")
    }
    let clear = MTLRenderPassDescriptor()
    clear.colorAttachments[0].texture = source
    clear.colorAttachments[0].loadAction = .clear
    clear.colorAttachments[0].storeAction = .store
    clear.colorAttachments[0].clearColor = MTLClearColorMake(0.25, 0.5, 0.75, 1.0)
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
        objectPointer(reactive), nil, objectPointer(fence)
    ) == 1, "Native diagnostic render pass failed")
    if temporalProduction {
        guard let backdrop else {
            throw ValidationFailure.message("Temporal production backdrop symbol is unavailable")
        }
        let backdropStatus = backdrop(
            objectPointer(commandBuffer), objectPointer(source), objectPointer(destination),
            objectPointer(depth), nil, objectPointer(fence),
            temporalHdrPrecompose ? 2 : 0, temporalHdrPrecompose ? 1 : 0, 0,
            temporalHdrPrecompose ? 1 : 0, 0, 0,
            1.0, 1.0, 0.22
        )
        try require(backdropStatus > 0,
                    "Native MetalFX Temporal backdrop encoding failed with status \(backdropStatus) (HDR precompose: \(temporalHdrPrecompose))")
        if temporalHdrPrecompose {
            try require(backdropStatus == 2,
                        "HDR-precomposed Temporal backdrop did not report its fast path")
            guard let coherentBlur else {
                throw ValidationFailure.message("Temporal coherent menu blur symbol is unavailable")
            }
            let blurStatus = coherentBlur(
                objectPointer(commandBuffer), objectPointer(source), objectPointer(destination),
                objectPointer(fence), 12.0, 1.0
            )
            try require(blurStatus == 1,
                        "HDR-precomposed Temporal output was unavailable to coherent menu blur: \(blurStatus)")
        }
    }

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
                  let initializeSymbol = dlsym(handle, "metallum_init_pipelines"),
                  let setSymbol = dlsym(handle, "metallum_set_frame_state_v3"),
                  let encodeSymbol = dlsym(handle, "metallum_encode_temporal_diagnostics_v1"),
                  let backdropSymbol = dlsym(handle, "metallum_MTLCommandBuffer_encodeHdrUiBackdrop"),
                  let coherentBlurSymbol = dlsym(handle, "metallum_MTLCommandBuffer_encodeCoherentMenuBlur"),
                  let createSamplerSymbol = dlsym(handle, "metallum_create_sampler") else {
                throw ValidationFailure.message("Native temporal diagnostic symbols are unavailable")
            }
            defer { dlclose(handle) }
            guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
                throw ValidationFailure.message("No Metal device/queue is available")
            }
            let initialize = unsafeBitCast(initializeSymbol, to: InitializePipelines.self)
            try require(initialize(objectPointer(device as AnyObject)) > 0,
                        "Native built-in pipeline initialization failed")
            let setFrameState = unsafeBitCast(setSymbol, to: SetFrameState.self)
            let encode = unsafeBitCast(encodeSymbol, to: EncodeDiagnostics.self)
            let backdrop = unsafeBitCast(backdropSymbol, to: EncodeBackdrop.self)
            let coherentBlur = unsafeBitCast(coherentBlurSymbol, to: EncodeCoherentMenuBlur.self)
            let createSampler = unsafeBitCast(createSamplerSymbol, to: CreateSampler.self)
            let temporalSamplerHandle = createSampler(
                objectPointer(device as AnyObject),
                MTLSamplerAddressMode.clampToEdge.rawValue,
                MTLSamplerAddressMode.clampToEdge.rawValue,
                MTLSamplerMinMagFilter.linear.rawValue,
                MTLSamplerMinMagFilter.linear.rawValue,
                MTLSamplerMipFilter.linear.rawValue,
                MTLCompareFunction.never.rawValue,
                1,
                Double.greatestFiniteMagnitude,
                -2.0
            )
            if #available(macOS 26.0, *) {
                guard let temporalSamplerHandle else {
                    throw ValidationFailure.message("Native Temporal mip-bias sampler creation failed")
                }
                _ = Unmanaged<MTLSamplerState>.fromOpaque(temporalSamplerHandle).takeRetainedValue()
            } else {
                try require(temporalSamplerHandle == nil,
                            "Older macOS must keep the unbiased Temporal sampler fallback")
            }
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
                nil, objectPointer(transitionFence)
            ) == 0, "Missing first FrameState must skip diagnostics without disabling them")
            let stationary = try runCase(
                device: device, queue: queue, setFrameState: setFrameState, encode: encode,
                width: 64, height: 64
            )
            try require(abs(stationary.motion.x) <= 0.01 && abs(stationary.motion.y) <= 0.01
                            && stationary.reactive == 0,
                        "Static diagnostic output mismatch")
            let production = try runCase(
                device: device, queue: queue, setFrameState: setFrameState, encode: encode,
                width: 64, height: 64, temporalProduction: true, backdrop: backdrop
            )
            try require(abs(production.motion.x) <= 0.01 && abs(production.motion.y) <= 0.01
                            && production.reactive == 0,
                        "Production Temporal input contract mismatch")
            let hdrPrecomposedProduction = try runCase(
                device: device, queue: queue, setFrameState: setFrameState, encode: encode,
                width: 64, height: 64, temporalProduction: true, temporalHdrPrecompose: true,
                backdrop: backdrop, coherentBlur: coherentBlur
            )
            try require(abs(hdrPrecomposedProduction.motion.x) <= 0.01
                            && abs(hdrPrecomposedProduction.motion.y) <= 0.01
                            && hdrPrecomposedProduction.reactive == 0,
                        "HDR-precomposed Temporal input contract mismatch")
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
            print("Temporal runtime validation passed (transition, static, production scaler, HDR precompose + menu blur, camera, resize/FOV, reset)")
        } catch {
            fputs("Temporal diagnostic runtime validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
