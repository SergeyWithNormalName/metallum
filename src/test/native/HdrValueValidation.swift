import AppKit
import Darwin
import Foundation
import Metal
import QuartzCore
import simd

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case let .message(message):
            return message
        }
    }
}

private struct PresentUniforms {
    var mode: UInt32
    var sourceEncoding: UInt32
    var diagnosticPattern: UInt32
    var currentHeadroom: Float
    var hdrStrength: Float
    var bloomStrength: Float
    var sceneAvailable: UInt32
    var uiAvailable: UInt32
}

private struct HdrExtractUniforms {
    var sourceEncoding: UInt32
    var semanticAvailable: UInt32
    var sourceSize: SIMD2<UInt32>
}

private typealias NativeInitFunction = @convention(c) (UnsafeRawPointer?) -> Void
private typealias NativeBackdropFunction = @convention(c) (
    UnsafeRawPointer?, // commandBuffer
    UnsafeRawPointer?, // sourceTexture
    UnsafeRawPointer?, // destinationTexture
    UnsafeRawPointer?, // globalFence
    Int32              // sourceEncoding
) -> Int32
private typealias NativePresentFunction = @convention(c) (
    UnsafeRawPointer?, // commandBuffer
    UnsafeRawPointer?, // layer
    UnsafeRawPointer?, // sourceTexture
    UnsafeRawPointer?, // sceneTexture
    UnsafeRawPointer?, // sceneDepthTexture
    UnsafeRawPointer?, // semanticTexture
    UnsafeRawPointer?, // uiTexture
    UnsafeRawPointer?, // globalFence
    Int32,             // outputMode
    Int32,             // sourceEncoding
    Int32,             // diagnosticPattern
    Float,             // currentHeadroom
    Float,             // hdrStrength
    Float              // bloomStrength
) -> Int32

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else {
        throw ValidationFailure.message(message)
    }
}

private func objectPointer(_ object: AnyObject) -> UnsafeRawPointer {
    UnsafeRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

private func embeddedMsl(functionName: String, sourcePath: String) throws -> String {
    let nativeSource = try String(contentsOfFile: sourcePath, encoding: .utf8)
    let declaration = "private func \(functionName)() -> String {"
    guard let declarationRange = nativeSource.range(of: declaration) else {
        throw ValidationFailure.message("Missing \(declaration) in \(sourcePath)")
    }
    let functionTail = nativeSource[declarationRange.upperBound...]
    guard let openingQuotes = functionTail.range(of: "\"\"\"") else {
        throw ValidationFailure.message("Missing opening multiline string for \(functionName)")
    }
    let mslTail = functionTail[openingQuotes.upperBound...]
    guard let closingQuotes = mslTail.range(of: "\"\"\"") else {
        throw ValidationFailure.message("Missing closing multiline string for \(functionName)")
    }
    return String(mslTail[..<closingQuotes.lowerBound])
}

private func srgbToLinear(_ encoded: Float) -> Float {
    if encoded <= 0.04045 {
        return encoded / 12.92
    }
    return Float(pow(Double((encoded + 0.055) / 1.055), 2.4))
}

private func rgbaDescription(_ value: SIMD4<Float>) -> String {
    String(format: "(%.4f, %.4f, %.4f, %.4f)", value.x, value.y, value.z, value.w)
}

private final class GpuHarness {
    let device: MTLDevice
    private let queue: MTLCommandQueue
    private let presentPipeline: MTLRenderPipelineState
    private let extractPipeline: MTLRenderPipelineState
    private let nearestSampler: MTLSamplerState
    private let linearSampler: MTLSamplerState

    init(device: MTLDevice, nativeSourcePath: String) throws {
        self.device = device
        guard let queue = device.makeCommandQueue() else {
            throw ValidationFailure.message("Metal command queue creation failed")
        }
        self.queue = queue

        let presentMsl = try embeddedMsl(functionName: "presentMslSource", sourcePath: nativeSourcePath)
        let effectsMsl = try embeddedMsl(functionName: "hdrEffectsMslSource", sourcePath: nativeSourcePath)
        let presentLibrary = try device.makeLibrary(source: presentMsl, options: nil)
        let effectsLibrary = try device.makeLibrary(source: effectsMsl, options: nil)

        guard
            let presentVertex = presentLibrary.makeFunction(name: "metallum_present_vs"),
            let presentFragment = presentLibrary.makeFunction(name: "metallum_present_fs")
        else {
            throw ValidationFailure.message("Present shader functions are missing")
        }
        let presentDescriptor = MTLRenderPipelineDescriptor()
        presentDescriptor.label = "Metallum HDR value validation present"
        presentDescriptor.vertexFunction = presentVertex
        presentDescriptor.fragmentFunction = presentFragment
        presentDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        presentDescriptor.colorAttachments[0].isBlendingEnabled = false
        self.presentPipeline = try device.makeRenderPipelineState(descriptor: presentDescriptor)

        guard
            let effectsVertex = effectsLibrary.makeFunction(name: "metallum_hdr_vs"),
            let extractFragment = effectsLibrary.makeFunction(name: "metallum_hdr_extract_fs")
        else {
            throw ValidationFailure.message("HDR extract shader functions are missing")
        }
        let extractDescriptor = MTLRenderPipelineDescriptor()
        extractDescriptor.label = "Metallum HDR value validation extract"
        extractDescriptor.vertexFunction = effectsVertex
        extractDescriptor.fragmentFunction = extractFragment
        extractDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        extractDescriptor.colorAttachments[0].isBlendingEnabled = false
        self.extractPipeline = try device.makeRenderPipelineState(descriptor: extractDescriptor)

        func makeSampler(filter: MTLSamplerMinMagFilter) throws -> MTLSamplerState {
            let descriptor = MTLSamplerDescriptor()
            descriptor.minFilter = filter
            descriptor.magFilter = filter
            descriptor.mipFilter = .notMipmapped
            descriptor.sAddressMode = .clampToEdge
            descriptor.tAddressMode = .clampToEdge
            guard let sampler = device.makeSamplerState(descriptor: descriptor) else {
                throw ValidationFailure.message("Metal sampler creation failed")
            }
            return sampler
        }
        self.nearestSampler = try makeSampler(filter: .nearest)
        self.linearSampler = try makeSampler(filter: .linear)
    }

    func makeRgba8Texture(
        width: Int = 4,
        height: Int = 4,
        bytes: SIMD4<UInt8>
    ) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .shared
        descriptor.usage = [.shaderRead, .renderTarget]
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("RGBA8 texture creation failed")
        }

        var values = [UInt8]()
        values.reserveCapacity(width * height * 4)
        for _ in 0..<(width * height) {
            values.append(bytes.x)
            values.append(bytes.y)
            values.append(bytes.z)
            values.append(bytes.w)
        }
        values.withUnsafeBytes { rawBytes in
            texture.replace(
                region: MTLRegionMake2D(0, 0, width, height),
                mipmapLevel: 0,
                withBytes: rawBytes.baseAddress!,
                bytesPerRow: width * 4
            )
        }
        return texture
    }

    func makeRgba16FloatTexture(
        width: Int = 4,
        height: Int = 4,
        value: SIMD4<Float>
    ) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .shared
        descriptor.usage = [.shaderRead, .renderTarget]
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("RGBA16Float texture creation failed")
        }

        let pixel = [
            Float16(value.x).bitPattern,
            Float16(value.y).bitPattern,
            Float16(value.z).bitPattern,
            Float16(value.w).bitPattern
        ]
        var values = [UInt16]()
        values.reserveCapacity(width * height * 4)
        for _ in 0..<(width * height) {
            values.append(contentsOf: pixel)
        }
        values.withUnsafeBytes { rawBytes in
            texture.replace(
                region: MTLRegionMake2D(0, 0, width, height),
                mipmapLevel: 0,
                withBytes: rawBytes.baseAddress!,
                bytesPerRow: width * 8
            )
        }
        return texture
    }

    func makeR8Texture(width: Int = 2, height: Int = 2, value: UInt8 = 0) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .r8Unorm,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .shared
        descriptor.usage = [.shaderRead, .renderTarget]
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("R8 texture creation failed")
        }
        let values = [UInt8](repeating: value, count: width * height)
        values.withUnsafeBytes { rawBytes in
            texture.replace(
                region: MTLRegionMake2D(0, 0, width, height),
                mipmapLevel: 0,
                withBytes: rawBytes.baseAddress!,
                bytesPerRow: width
            )
        }
        return texture
    }

    func makeDepthTexture(width: Int = 4, height: Int = 4, clearDepth: Double) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .depth32Float,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.renderTarget, .shaderRead]
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("Depth texture creation failed")
        }
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Depth clear command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.depthAttachment.texture = texture
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.storeAction = .store
        pass.depthAttachment.clearDepth = clearDepth
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Depth clear encoder creation failed")
        }
        encoder.endEncoding()
        try complete(commandBuffer, label: "depth clear")
        return texture
    }

    func readRgba8(texture: MTLTexture, x: Int = 0, y: Int = 0) -> SIMD4<UInt8> {
        var bytes = [UInt8](repeating: 0, count: 4)
        bytes.withUnsafeMutableBytes { rawBytes in
            texture.getBytes(
                rawBytes.baseAddress!,
                bytesPerRow: 4,
                from: MTLRegionMake2D(x, y, 1, 1),
                mipmapLevel: 0
            )
        }
        return SIMD4<UInt8>(bytes[0], bytes[1], bytes[2], bytes[3])
    }

    func makeSemanticTexture(markerDepth: Float, code: UInt8 = 63) throws -> MTLTexture {
        let clamped = min(max(markerDepth, 0.0), 1.0)
        let packed = UInt32((clamped * 16_777_215.0).rounded())
        return try makeRgba8Texture(bytes: SIMD4<UInt8>(
            code,
            UInt8(packed & 0xff),
            UInt8((packed >> 8) & 0xff),
            UInt8((packed >> 16) & 0xff)
        ))
    }

    func renderExtract(
        scene: MTLTexture,
        semantic: MTLTexture,
        depth: MTLTexture,
        sourceEncoding: UInt32,
        semanticAvailable: Bool = true
    ) throws -> MTLTexture {
        let outputWidth = max((scene.width + 3) / 4, 1)
        let outputHeight = max((scene.height + 3) / 4, 1)
        let output = try makePrivateRgba16FloatTexture(width: outputWidth, height: outputHeight)
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("HDR extract command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0)
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("HDR extract encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(outputWidth),
            height: Double(outputHeight),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(extractPipeline)
        encoder.setFragmentTexture(scene, index: 0)
        encoder.setFragmentTexture(semantic, index: 1)
        encoder.setFragmentTexture(depth, index: 2)
        var uniforms = HdrExtractUniforms(
            sourceEncoding: sourceEncoding,
            semanticAvailable: semanticAvailable ? 1 : 0,
            sourceSize: SIMD2<UInt32>(UInt32(scene.width), UInt32(scene.height))
        )
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<HdrExtractUniforms>.stride, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "HDR semantic extract")
        return output
    }

    func renderPresent(
        finalFrame: MTLTexture,
        sceneFrame: MTLTexture,
        emissionFrame: MTLTexture,
        bloomFrame: MTLTexture,
        uiMaskFrame: MTLTexture,
        uiFrame: MTLTexture,
        uniforms: PresentUniforms
    ) throws -> SIMD4<Float> {
        let output = try makePrivateRgba16FloatTexture(width: finalFrame.width, height: finalFrame.height)
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Present command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0)
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Present encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(output.width),
            height: Double(output.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(presentPipeline)
        encoder.setFragmentTexture(finalFrame, index: 0)
        encoder.setFragmentTexture(sceneFrame, index: 1)
        encoder.setFragmentTexture(emissionFrame, index: 2)
        encoder.setFragmentTexture(bloomFrame, index: 3)
        encoder.setFragmentTexture(uiMaskFrame, index: 4)
        encoder.setFragmentTexture(uiFrame, index: 5)
        encoder.setFragmentSamplerState(nearestSampler, index: 0)
        encoder.setFragmentSamplerState(linearSampler, index: 1)
        var mutableUniforms = uniforms
        encoder.setFragmentBytes(
            &mutableUniforms,
            length: MemoryLayout<PresentUniforms>.stride,
            index: 0
        )
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "HDR present")
        return try readRgba16Float(texture: output, x: output.width / 2, y: output.height / 2)
    }

    func readRgba16Float(texture: MTLTexture, x: Int = 0, y: Int = 0) throws -> SIMD4<Float> {
        try require(texture.pixelFormat == .rgba16Float, "Readback requires RGBA16Float")
        let bytesPerRow = ((texture.width * 8 + 255) / 256) * 256
        let byteCount = bytesPerRow * texture.height
        guard
            let buffer = device.makeBuffer(length: byteCount, options: .storageModeShared),
            let commandBuffer = queue.makeCommandBuffer(),
            let encoder = commandBuffer.makeBlitCommandEncoder()
        else {
            throw ValidationFailure.message("Readback resource creation failed")
        }
        encoder.copy(
            from: texture,
            sourceSlice: 0,
            sourceLevel: 0,
            sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
            sourceSize: MTLSize(width: texture.width, height: texture.height, depth: 1),
            to: buffer,
            destinationOffset: 0,
            destinationBytesPerRow: bytesPerRow,
            destinationBytesPerImage: byteCount
        )
        encoder.endEncoding()
        try complete(commandBuffer, label: "RGBA16Float readback")

        let pixelOffset = y * bytesPerRow + x * 8
        let words = buffer.contents().advanced(by: pixelOffset).assumingMemoryBound(to: UInt16.self)
        return SIMD4<Float>(
            Float(Float16(bitPattern: words[0])),
            Float(Float16(bitPattern: words[1])),
            Float(Float16(bitPattern: words[2])),
            Float(Float16(bitPattern: words[3]))
        )
    }

    private func makePrivateRgba16FloatTexture(width: Int, height: Int) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.renderTarget, .shaderRead]
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("Private RGBA16Float texture creation failed")
        }
        return texture
    }

    private func complete(_ commandBuffer: MTLCommandBuffer, label: String) throws {
        commandBuffer.label = "Metallum validation: \(label)"
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()
        guard commandBuffer.status == .completed else {
            let detail = commandBuffer.error.map(String.init(describing:)) ?? "unknown GPU error"
            throw ValidationFailure.message("\(label) failed: \(detail)")
        }
    }
}

private final class ValueValidation {
    private let gpu: GpuHarness
    private let nativeLibraryPath: String
    private var passCount = 0

    init(gpu: GpuHarness, nativeLibraryPath: String) {
        self.gpu = gpu
        self.nativeLibraryPath = nativeLibraryPath
    }

    func run() throws {
        try validateSdrIdentityAtHeadroomOne()
        try validateNonsemanticWhiteUsesHeadroom()
        try validateMidtoneIdentity()
        try validateSemanticVisibilityAndOcclusion()
        try validateExtendedSrgbIsUnclipped()
        try validateSdrUiCeiling()
        try validateNativeBackdropAndPresentAbi()
        print("HDR GPU value validation passed (\(passCount) checks)")
    }

    private func baseUniforms(
        mode: UInt32 = 2,
        sourceEncoding: UInt32 = 0,
        headroom: Float = 4,
        sceneAvailable: Bool = true,
        uiAvailable: Bool = false
    ) -> PresentUniforms {
        PresentUniforms(
            mode: mode,
            sourceEncoding: sourceEncoding,
            diagnosticPattern: 0,
            currentHeadroom: headroom,
            hdrStrength: 1,
            bloomStrength: 0,
            sceneAvailable: sceneAvailable ? 1 : 0,
            uiAvailable: uiAvailable ? 1 : 0
        )
    }

    private func auxiliaries() throws -> (
        emission: MTLTexture,
        bloom: MTLTexture,
        uiMask: MTLTexture,
        transparentUi: MTLTexture
    ) {
        (
            try gpu.makeRgba16FloatTexture(width: 1, height: 1, value: SIMD4<Float>(0, 0, 0, 0)),
            try gpu.makeRgba16FloatTexture(width: 1, height: 1, value: SIMD4<Float>(0, 0, 0, 0)),
            try gpu.makeR8Texture(),
            try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(0, 0, 0, 0))
        )
    }

    private func validateSdrIdentityAtHeadroomOne() throws {
        let frame = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(128, 128, 128, 255))
        let aux = try auxiliaries()
        let value = try gpu.renderPresent(
            finalFrame: frame,
            sceneFrame: frame,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms(headroom: 1)
        )
        let expected = srgbToLinear(Float(128) / 255)
        try require(abs(value.x - expected) < 0.004, "SDR identity expected \(expected), got \(rgbaDescription(value))")
        try require(value.x <= 1.001, "SDR identity escaped SDR range: \(rgbaDescription(value))")
        pass("SDR appearance identity at headroom 1", value)
    }

    private func validateNonsemanticWhiteUsesHeadroom() throws {
        let frame = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 255))
        let aux = try auxiliaries()
        let value = try gpu.renderPresent(
            finalFrame: frame,
            sceneFrame: frame,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms()
        )
        try require(value.x > 1.05, "Non-semantic scene white did not enter EDR: \(rgbaDescription(value))")
        try require(value.x <= 4.01, "Non-semantic scene white exceeded headroom: \(rgbaDescription(value))")
        pass("non-semantic scene white exceeds 1.0", value)
    }

    private func validateMidtoneIdentity() throws {
        let frame = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(128, 128, 128, 255))
        let aux = try auxiliaries()
        let value = try gpu.renderPresent(
            finalFrame: frame,
            sceneFrame: frame,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms()
        )
        let expected = srgbToLinear(Float(128) / 255)
        try require(abs(value.x - expected) < 0.004, "HDR midtone drifted: expected \(expected), got \(rgbaDescription(value))")
        pass("midtone remains appearance-identical", value)
    }

    private func validateSemanticVisibilityAndOcclusion() throws {
        let scene = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(204, 204, 204, 255))
        let aux = try auxiliaries()
        let visibleDepth = try gpu.makeDepthTexture(clearDepth: 0.5)
        let visibleSemantic = try gpu.makeSemanticTexture(markerDepth: 0.5)
        let visibleEmission = try gpu.renderExtract(
            scene: scene,
            semantic: visibleSemantic,
            depth: visibleDepth,
            sourceEncoding: 0
        )
        let visibleExtract = try gpu.readRgba16Float(texture: visibleEmission)
        try require(visibleExtract.x > 1.8, "Visible semantic marker produced no HDR emission: \(rgbaDescription(visibleExtract))")

        let visibleValue = try gpu.renderPresent(
            finalFrame: scene,
            sceneFrame: scene,
            emissionFrame: visibleEmission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms()
        )
        try require(visibleValue.x > 1.05, "Visible semantic light did not enter EDR: \(rgbaDescription(visibleValue))")
        pass("visible semantic marker emits HDR", visibleValue)

        let occludingDepth = try gpu.makeDepthTexture(clearDepth: 0.75)
        let occludedSemantic = try gpu.makeSemanticTexture(markerDepth: 0.25)
        let occludedEmission = try gpu.renderExtract(
            scene: scene,
            semantic: occludedSemantic,
            depth: occludingDepth,
            sourceEncoding: 0
        )
        let occludedExtract = try gpu.readRgba16Float(texture: occludedEmission)
        try require(occludedExtract.x < 0.001, "Occluded semantic marker leaked emission: \(rgbaDescription(occludedExtract))")

        let occludedValue = try gpu.renderPresent(
            finalFrame: scene,
            sceneFrame: scene,
            emissionFrame: occludedEmission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms()
        )
        let expectedBase = srgbToLinear(0.8)
        try require(abs(occludedValue.x - expectedBase) < 0.01, "Occluded semantic marker changed the scene: \(rgbaDescription(occludedValue))")
        try require(occludedValue.x <= 1.001, "Occluded semantic marker entered EDR: \(rgbaDescription(occludedValue))")
        pass("occluded semantic marker is rejected", occludedValue)
    }

    private func validateExtendedSrgbIsUnclipped() throws {
        let frame = try gpu.makeRgba16FloatTexture(value: SIMD4<Float>(1.5, 1.5, 1.5, 1))
        let aux = try auxiliaries()
        let value = try gpu.renderPresent(
            finalFrame: frame,
            sceneFrame: frame,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms(
                mode: 1,
                sourceEncoding: 1,
                headroom: 8,
                sceneAvailable: false
            )
        )
        let expected = srgbToLinear(1.5)
        try require(value.x > 1.5, "Extended-sRGB 1.5 was clipped or treated as SDR: \(rgbaDescription(value))")
        try require(abs(value.x - expected) < 0.02, "Extended-sRGB decode expected \(expected), got \(rgbaDescription(value))")
        pass("extended-sRGB 1.5 remains unclipped", value)
    }

    private func validateSdrUiCeiling() throws {
        let black = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(0, 0, 0, 255))
        let aux = try auxiliaries()
        let opaqueUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 255))
        let opaqueValue = try gpu.renderPresent(
            finalFrame: black,
            sceneFrame: black,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: opaqueUi,
            uniforms: baseUniforms(uiAvailable: true)
        )
        try require(abs(opaqueValue.x - 1.0) < 0.004, "Opaque SDR UI expected 1.0, got \(rgbaDescription(opaqueValue))")
        try require(opaqueValue.x <= 1.001, "Opaque SDR UI exceeded SDR white: \(rgbaDescription(opaqueValue))")
        pass("opaque SDR UI stays at or below 1.0", opaqueValue)

        // The seeded UI target is a complete SDR composite in encoded sRGB.
        let halfAlphaUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(128, 128, 128, 128))
        let halfAlphaValue = try gpu.renderPresent(
            finalFrame: black,
            sceneFrame: black,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: halfAlphaUi,
            uniforms: baseUniforms(uiAvailable: true)
        )
        let expectedAlpha = srgbToLinear(Float(128) / 255)
        try require(abs(halfAlphaValue.x - expectedAlpha) < 0.006, "Seeded SDR UI expected \(expectedAlpha), got \(rgbaDescription(halfAlphaValue))")
        try require(halfAlphaValue.x <= 1.001, "Half-alpha SDR UI exceeded SDR white: \(rgbaDescription(halfAlphaValue))")
        pass("seeded SDR UI stays at or below 1.0", halfAlphaValue)
    }

    private func validateNativeBackdropAndPresentAbi() throws {
        guard let handle = dlopen(nativeLibraryPath, RTLD_NOW | RTLD_LOCAL) else {
            let detail = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
            throw ValidationFailure.message("Could not load native library: \(detail)")
        }
        guard
            let initSymbol = dlsym(handle, "metallum_init_pipelines"),
            let backdropSymbol = dlsym(handle, "metallum_MTLCommandBuffer_encodeHdrUiBackdrop"),
            let presentSymbol = dlsym(handle, "metallum_MTLCommandBuffer_encodePresentTextureToDrawable")
        else {
            throw ValidationFailure.message("Native HDR present symbols are missing")
        }
        let initialize = unsafeBitCast(initSymbol, to: NativeInitFunction.self)
        let backdrop = unsafeBitCast(backdropSymbol, to: NativeBackdropFunction.self)
        let present = unsafeBitCast(presentSymbol, to: NativePresentFunction.self)

        let application = NSApplication.shared
        application.setActivationPolicy(.prohibited)
        if !application.isRunning {
            application.finishLaunching()
        }
        let contentSize = NSSize(width: 16, height: 16)
        let window = NSWindow(
            contentRect: NSRect(origin: .zero, size: contentSize),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        let view = NSView(frame: NSRect(origin: .zero, size: contentSize))
        let layer = CAMetalLayer()
        layer.device = gpu.device
        layer.pixelFormat = .rgba16Float
        layer.colorspace = CGColorSpace(name: CGColorSpace.extendedLinearSRGB)
        layer.framebufferOnly = false
        layer.drawableSize = CGSize(width: 16, height: 16)
        layer.allowsNextDrawableTimeout = false
        view.wantsLayer = true
        view.layer = layer
        window.contentView = view
        window.orderFrontRegardless()
        defer { window.orderOut(nil) }
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.05))

        initialize(objectPointer(gpu.device as AnyObject))
        guard let backdropQueue = gpu.device.makeCommandQueue() else {
            throw ValidationFailure.message("Native backdrop queue creation failed")
        }

        let extendedSource = try gpu.makeRgba16FloatTexture(
            width: 16,
            height: 16,
            value: SIMD4<Float>(1.5, 0.5, 0, 1)
        )
        let extendedDestination = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(0, 0, 0, 255)
        )
        guard let extendedCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Extended backdrop command buffer creation failed")
        }
        let extendedStatus = backdrop(
            objectPointer(extendedCommandBuffer as AnyObject),
            objectPointer(extendedSource as AnyObject),
            objectPointer(extendedDestination as AnyObject),
            nil,
            1
        )
        try require(extendedStatus == 1, "Extended backdrop ABI returned \(extendedStatus)")
        extendedCommandBuffer.commit()
        extendedCommandBuffer.waitUntilCompleted()
        try require(extendedCommandBuffer.status == .completed, "Extended backdrop GPU command failed")
        let extendedPixel = gpu.readRgba8(texture: extendedDestination)
        try require(extendedPixel == SIMD4<UInt8>(255, 128, 0, 0), "Extended backdrop mismatch: \(extendedPixel)")
        passCount += 1
        print("PASS native seeded UI backdrop clamps extended sRGB and clears alpha")

        let linearSource = try gpu.makeRgba16FloatTexture(
            width: 16,
            height: 16,
            value: SIMD4<Float>(0.5, 1, 0, 1)
        )
        let linearDestination = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(0, 0, 0, 255)
        )
        guard let linearCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Linear backdrop command buffer creation failed")
        }
        let linearStatus = backdrop(
            objectPointer(linearCommandBuffer as AnyObject),
            objectPointer(linearSource as AnyObject),
            objectPointer(linearDestination as AnyObject),
            nil,
            2
        )
        try require(linearStatus == 1, "Linear backdrop ABI returned \(linearStatus)")
        linearCommandBuffer.commit()
        linearCommandBuffer.waitUntilCompleted()
        try require(linearCommandBuffer.status == .completed, "Linear backdrop GPU command failed")
        let linearPixel = gpu.readRgba8(texture: linearDestination)
        try require(abs(Int(linearPixel.x) - 188) <= 1 && linearPixel.y == 255 && linearPixel.z == 0 && linearPixel.w == 0,
                    "Linear backdrop mismatch: \(linearPixel)")
        passCount += 1
        print("PASS native seeded UI backdrop encodes linear RGB to SDR sRGB")

        guard
            let queue = gpu.device.makeCommandQueue(),
            let commandBuffer = queue.makeCommandBuffer()
        else {
            throw ValidationFailure.message("Native ABI smoke command buffer creation failed")
        }
        let source = try gpu.makeRgba8Texture(width: 16, height: 16, bytes: SIMD4<UInt8>(204, 204, 204, 255))
        let scene = try gpu.makeRgba8Texture(width: 16, height: 16, bytes: SIMD4<UInt8>(204, 204, 204, 255))
        let depth = try gpu.makeDepthTexture(width: 16, height: 16, clearDepth: 0.5)
        let semantic = try gpu.makeRgba8Texture(width: 16, height: 16, bytes: SIMD4<UInt8>(0, 0, 0, 0))
        let ui = try gpu.makeRgba8Texture(width: 16, height: 16, bytes: SIMD4<UInt8>(128, 128, 128, 128))

        let status = present(
            objectPointer(commandBuffer as AnyObject),
            objectPointer(layer),
            objectPointer(source as AnyObject),
            objectPointer(scene as AnyObject),
            objectPointer(depth as AnyObject),
            objectPointer(semantic as AnyObject),
            objectPointer(ui as AnyObject),
            nil,
            2,
            0,
            0,
            4,
            1,
            0
        )
        try require(status == 1, "Native present ABI returned \(status)")
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()
        let detail = commandBuffer.error.map(String.init(describing:)) ?? "unknown GPU error"
        try require(commandBuffer.status == .completed, "Native present command failed: \(detail)")
        passCount += 1
        print("PASS native present ABI accepts uiTexture (8 pointer arguments)")
    }

    private func pass(_ name: String, _ value: SIMD4<Float>) {
        passCount += 1
        print("PASS \(name): \(rgbaDescription(value))")
    }
}

@main
private enum HdrValueValidationMain {
    static func main() {
        do {
            let arguments = CommandLine.arguments
            try require(arguments.count == 3, "Usage: HdrValueValidation <MetallumNative.swift> <libmetallum.dylib>")
            guard let device = MTLCreateSystemDefaultDevice() else {
                throw ValidationFailure.message("No Metal device is available")
            }
            print("Metal device: \(device.name)")
            let gpu = try GpuHarness(device: device, nativeSourcePath: arguments[1])
            let validation = ValueValidation(gpu: gpu, nativeLibraryPath: arguments[2])
            try validation.run()
        } catch {
            fputs("HDR GPU value validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
