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
    var semanticAvailable: UInt32
}

private struct HdrExtractUniforms {
    var sourceEncoding: UInt32
    var semanticAvailable: UInt32
    var sourceSize: SIMD2<UInt32>
    var histogramEnabled: UInt32
    var _padding0: UInt32
}

private struct HdrHistogramReduceUniforms {
    var currentHeadroom: Float
    var deltaTime: Float
    var forceReset: UInt32
    var _padding0: UInt32
}

private struct HdrUiCompareUniforms {
    var sourceEncoding: UInt32
    var seededUiAvailable: UInt32
    var _padding0: SIMD2<UInt32>
}

private struct HdrAdaptiveState {
    var breakpoint: Float
    var inferredPeak: Float
    var medianLog2: Float
    var p90Log2: Float
    var p99Log2: Float
    var brightCoverage: Float
    var currentHeadroom: Float
    var valid: UInt32
}

private typealias NativeInitFunction = @convention(c) (UnsafeRawPointer?) -> Void
private typealias NativeUpdateLayerContentsHeadroomFunction = @convention(c) (
    UnsafeRawPointer?, // layer
    Float              // contentHeadroom
) -> Int32
private typealias NativeConfigureLayerFunction = @convention(c) (
    UnsafeRawPointer?, // layer
    Double,            // width
    Double,            // height
    Int32,             // immediatePresentMode
    Int32,             // outputMode
    Float              // contentHeadroom
) -> Int32
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

private func colorSpacesEqual(_ lhs: CGColorSpace?, _ rhs: CGColorSpace?) -> Bool {
    switch (lhs, rhs) {
    case (nil, nil):
        return true
    case let (lhs?, rhs?):
        return CFEqual(lhs, rhs)
    default:
        return false
    }
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

private func linearToSrgb(_ linear: Float) -> Float {
    let bounded = min(max(linear, 0.0), 1.0)
    if bounded <= 0.0031308 {
        return bounded * 12.92
    }
    return Float(1.055 * pow(Double(bounded), 1.0 / 2.4) - 0.055)
}

private func semanticPixel(
    markerDepth: Float,
    strength: UInt8 = 127,
    exact: Bool = true
) -> SIMD4<UInt8> {
    let clamped = min(max(markerDepth, 0.0), 1.0)
    let packed = UInt32((clamped * 16_777_215.0).rounded())
    let boundedStrength = strength & 0x7f
    let code = boundedStrength == 0
        ? UInt8(0)
        : boundedStrength | (exact ? UInt8(0x80) : UInt8(0))
    return SIMD4<UInt8>(
        code,
        UInt8(packed & 0xff),
        UInt8((packed >> 8) & 0xff),
        UInt8((packed >> 16) & 0xff)
    )
}

private func boundaryBlendMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct BoundaryVertexOut {
      float4 position [[position]];
    };

    vertex BoundaryVertexOut metallum_boundary_blend_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };
      BoundaryVertexOut out;
      out.position = float4(positions[vertexId], 0.0, 1.0);
      return out;
    }

    float3 metallum_boundary_srgb_to_linear(float3 encoded) {
      float3 bounded = clamp(encoded, 0.0, 1.0);
      float3 low = bounded / 12.92;
      float3 high = pow((bounded + 0.055) / 1.055, float3(2.4));
      return select(high, low, bounded <= float3(0.04045));
    }

    fragment float4 metallum_boundary_blend_fs(
      BoundaryVertexOut in [[stage_in]],
      constant float4& encodedColor [[buffer(0)]]) {
      return float4(metallum_boundary_srgb_to_linear(encodedColor.rgb), encodedColor.a);
    }
    """
}

private func rgbaDescription(_ value: SIMD4<Float>) -> String {
    String(format: "(%.4f, %.4f, %.4f, %.4f)", value.x, value.y, value.z, value.w)
}

private func defaultAdaptiveState() -> HdrAdaptiveState {
    HdrAdaptiveState(
        breakpoint: 0.62,
        inferredPeak: 2.65,
        medianLog2: -2.0,
        p90Log2: 0.0,
        p99Log2: 0.0,
        brightCoverage: 0.05,
        currentHeadroom: 4.0,
        valid: 1
    )
}

private func identityAdaptiveState(headroom: Float) -> HdrAdaptiveState {
    HdrAdaptiveState(
        breakpoint: 0.70,
        inferredPeak: 1.0,
        medianLog2: -2.0,
        p90Log2: -2.0,
        p99Log2: -2.0,
        brightCoverage: 0.0,
        currentHeadroom: headroom,
        valid: 1
    )
}

private final class GpuHarness {
    let device: MTLDevice
    private let queue: MTLCommandQueue
    private let presentPipeline: MTLRenderPipelineState
    private let extractPipeline: MTLRenderPipelineState
    private let uiComparePipeline: MTLRenderPipelineState
    private let uiDilatePipeline: MTLRenderPipelineState
    private let boundaryBlendPipeline: MTLRenderPipelineState
    private let histogramReducePipeline: MTLComputePipelineState
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
            let extractFragment = effectsLibrary.makeFunction(name: "metallum_hdr_extract_fs"),
            let histogramReduce = effectsLibrary.makeFunction(name: "metallum_hdr_histogram_reduce"),
            let uiCompareFragment = effectsLibrary.makeFunction(name: "metallum_hdr_ui_compare_fs"),
            let uiDilateFragment = effectsLibrary.makeFunction(name: "metallum_hdr_ui_dilate_fs")
        else {
            throw ValidationFailure.message("HDR effects shader functions are missing")
        }
        let extractDescriptor = MTLRenderPipelineDescriptor()
        extractDescriptor.label = "Metallum HDR value validation extract"
        extractDescriptor.vertexFunction = effectsVertex
        extractDescriptor.fragmentFunction = extractFragment
        extractDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        extractDescriptor.colorAttachments[0].isBlendingEnabled = false
        self.extractPipeline = try device.makeRenderPipelineState(descriptor: extractDescriptor)
        self.histogramReducePipeline = try device.makeComputePipelineState(function: histogramReduce)

        func makeUiControlPipeline(
            fragment: MTLFunction,
            label: String
        ) throws -> MTLRenderPipelineState {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.label = label
            descriptor.vertexFunction = effectsVertex
            descriptor.fragmentFunction = fragment
            descriptor.colorAttachments[0].pixelFormat = .rg8Unorm
            descriptor.colorAttachments[0].isBlendingEnabled = false
            return try device.makeRenderPipelineState(descriptor: descriptor)
        }
        self.uiComparePipeline = try makeUiControlPipeline(
            fragment: uiCompareFragment,
            label: "Metallum seeded UI control validation compare"
        )
        self.uiDilatePipeline = try makeUiControlPipeline(
            fragment: uiDilateFragment,
            label: "Metallum seeded UI control validation dilation"
        )

        let boundaryLibrary = try device.makeLibrary(source: boundaryBlendMslSource(), options: nil)
        guard
            let boundaryVertex = boundaryLibrary.makeFunction(name: "metallum_boundary_blend_vs"),
            let boundaryFragment = boundaryLibrary.makeFunction(name: "metallum_boundary_blend_fs")
        else {
            throw ValidationFailure.message("Boundary-linear blend shader functions are missing")
        }
        let boundaryDescriptor = MTLRenderPipelineDescriptor()
        boundaryDescriptor.label = "Metallum HDR boundary-linear blend validation"
        boundaryDescriptor.vertexFunction = boundaryVertex
        boundaryDescriptor.fragmentFunction = boundaryFragment
        let boundaryAttachment = boundaryDescriptor.colorAttachments[0]!
        boundaryAttachment.pixelFormat = .rgba16Float
        boundaryAttachment.isBlendingEnabled = true
        boundaryAttachment.sourceRGBBlendFactor = .sourceAlpha
        boundaryAttachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
        boundaryAttachment.sourceAlphaBlendFactor = .one
        boundaryAttachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
        self.boundaryBlendPipeline = try device.makeRenderPipelineState(descriptor: boundaryDescriptor)

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

    func makeRgba8Texture(
        width: Int,
        height: Int,
        pixels: [SIMD4<UInt8>]
    ) throws -> MTLTexture {
        try require(pixels.count == width * height, "RGBA8 pixel count does not match texture size")
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .shared
        descriptor.usage = [.shaderRead, .renderTarget]
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("RGBA8 patterned texture creation failed")
        }
        var values = [UInt8]()
        values.reserveCapacity(pixels.count * 4)
        for pixel in pixels {
            values.append(pixel.x)
            values.append(pixel.y)
            values.append(pixel.z)
            values.append(pixel.w)
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

    func makeRg8Texture(
        width: Int = 2,
        height: Int = 2,
        bytes: SIMD2<UInt8> = SIMD2<UInt8>(repeating: 0)
    ) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rg8Unorm,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .shared
        descriptor.usage = [.shaderRead, .renderTarget]
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("RG8 texture creation failed")
        }
        var values = [UInt8]()
        values.reserveCapacity(width * height * 2)
        for _ in 0..<(width * height) {
            values.append(bytes.x)
            values.append(bytes.y)
        }
        values.withUnsafeBytes { rawBytes in
            texture.replace(
                region: MTLRegionMake2D(0, 0, width, height),
                mipmapLevel: 0,
                withBytes: rawBytes.baseAddress!,
                bytesPerRow: width * 2
            )
        }
        return texture
    }

    func readRg8(texture: MTLTexture) throws -> [SIMD2<UInt8>] {
        try require(texture.pixelFormat == .rg8Unorm, "Readback requires RG8Unorm")
        var bytes = [UInt8](repeating: 0, count: texture.width * texture.height * 2)
        bytes.withUnsafeMutableBytes { rawBytes in
            texture.getBytes(
                rawBytes.baseAddress!,
                bytesPerRow: texture.width * 2,
                from: MTLRegionMake2D(0, 0, texture.width, texture.height),
                mipmapLevel: 0
            )
        }
        return stride(from: 0, to: bytes.count, by: 2).map {
            SIMD2<UInt8>(bytes[$0], bytes[$0 + 1])
        }
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

    func makeSemanticTexture(
        markerDepth: Float,
        strength: UInt8 = 127,
        exact: Bool = true
    ) throws -> MTLTexture {
        try makeRgba8Texture(bytes: semanticPixel(
            markerDepth: markerDepth,
            strength: strength,
            exact: exact
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
        guard
            let commandBuffer = queue.makeCommandBuffer(),
            let histogram = device.makeBuffer(
                length: 64 * MemoryLayout<UInt32>.stride,
                options: .storageModeShared
            )
        else {
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
        encoder.setFragmentBuffer(histogram, offset: 0, index: 1)
        var uniforms = HdrExtractUniforms(
            sourceEncoding: sourceEncoding,
            semanticAvailable: semanticAvailable ? 1 : 0,
            sourceSize: SIMD2<UInt32>(UInt32(scene.width), UInt32(scene.height)),
            histogramEnabled: 0,
            _padding0: 0
        )
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<HdrExtractUniforms>.stride, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "HDR semantic extract")
        return output
    }

    func analyzeHistogram(
        scene: MTLTexture,
        sourceEncoding: UInt32 = 0,
        currentHeadroom: Float = 4.0,
        deltaTime: Float = 0.0,
        forceReset: Bool = true,
        previousState: HdrAdaptiveState? = nil,
        semantic: MTLTexture? = nil,
        sceneDepth: MTLTexture? = nil
    ) throws -> (bins: [UInt32], state: HdrAdaptiveState) {
        let outputWidth = max((scene.width + 3) / 4, 1)
        let outputHeight = max((scene.height + 3) / 4, 1)
        let output = try makePrivateRgba16FloatTexture(width: outputWidth, height: outputHeight)
        let semanticTexture: MTLTexture
        if let semantic {
            semanticTexture = semantic
        } else {
            semanticTexture = try makeRgba8Texture(
                width: scene.width,
                height: scene.height,
                bytes: SIMD4<UInt8>(0, 0, 0, 0)
            )
        }
        let depthTexture: MTLTexture
        if let sceneDepth {
            depthTexture = sceneDepth
        } else {
            depthTexture = try makeDepthTexture(
                width: scene.width,
                height: scene.height,
                clearDepth: 0.5
            )
        }
        guard
            let histogram = device.makeBuffer(
                length: 64 * MemoryLayout<UInt32>.stride,
                options: .storageModeShared
            ),
            let commandBuffer = queue.makeCommandBuffer(),
            let clear = commandBuffer.makeBlitCommandEncoder()
        else {
            throw ValidationFailure.message("Histogram validation resource creation failed")
        }

        var initialState = previousState ?? HdrAdaptiveState(
            breakpoint: 0.70,
            inferredPeak: 1.0,
            medianLog2: -12.0,
            p90Log2: -12.0,
            p99Log2: -12.0,
            brightCoverage: 0.0,
            currentHeadroom: 1.0,
            valid: 0
        )
        guard let stateBuffer = withUnsafeBytes(of: &initialState, { bytes in
            device.makeBuffer(
                bytes: bytes.baseAddress!,
                length: bytes.count,
                options: .storageModeShared
            )
        }) else {
            throw ValidationFailure.message("Adaptive state buffer creation failed")
        }

        clear.fill(buffer: histogram, range: 0..<histogram.length, value: 0)
        clear.endEncoding()

        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let extract = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Histogram extract encoder creation failed")
        }
        extract.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(outputWidth),
            height: Double(outputHeight),
            znear: 0,
            zfar: 1
        ))
        extract.setRenderPipelineState(extractPipeline)
        extract.setFragmentTexture(scene, index: 0)
        extract.setFragmentTexture(semanticTexture, index: 1)
        extract.setFragmentTexture(depthTexture, index: 2)
        extract.setFragmentBuffer(histogram, offset: 0, index: 1)
        var extractUniforms = HdrExtractUniforms(
            sourceEncoding: sourceEncoding,
            semanticAvailable: semantic == nil ? 0 : 1,
            sourceSize: SIMD2<UInt32>(UInt32(scene.width), UInt32(scene.height)),
            histogramEnabled: 1,
            _padding0: 0
        )
        extract.setFragmentBytes(
            &extractUniforms,
            length: MemoryLayout<HdrExtractUniforms>.stride,
            index: 0
        )
        extract.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        extract.endEncoding()

        guard let reduce = commandBuffer.makeComputeCommandEncoder() else {
            throw ValidationFailure.message("Histogram reduction encoder creation failed")
        }
        reduce.setComputePipelineState(histogramReducePipeline)
        reduce.setBuffer(histogram, offset: 0, index: 0)
        reduce.setBuffer(stateBuffer, offset: 0, index: 1)
        var reduceUniforms = HdrHistogramReduceUniforms(
            currentHeadroom: currentHeadroom,
            deltaTime: deltaTime,
            forceReset: forceReset ? 1 : 0,
            _padding0: 0
        )
        reduce.setBytes(
            &reduceUniforms,
            length: MemoryLayout<HdrHistogramReduceUniforms>.stride,
            index: 2
        )
        reduce.dispatchThreads(
            MTLSize(width: 1, height: 1, depth: 1),
            threadsPerThreadgroup: MTLSize(width: 1, height: 1, depth: 1)
        )
        reduce.endEncoding()
        try complete(commandBuffer, label: "HDR histogram analysis")

        let histogramWords = histogram.contents().assumingMemoryBound(to: UInt32.self)
        let bins = Array(UnsafeBufferPointer(start: histogramWords, count: 64))
        let state = stateBuffer.contents().assumingMemoryBound(to: HdrAdaptiveState.self).pointee
        return (bins, state)
    }

    func renderUiControl(
        finalFrame: MTLTexture,
        sceneFrame: MTLTexture,
        sourceEncoding: UInt32,
        seededUiAvailable: Bool
    ) throws -> [SIMD2<UInt8>] {
        try require(
            finalFrame.width == sceneFrame.width && finalFrame.height == sceneFrame.height,
            "UI control inputs must have matching dimensions"
        )
        let outputWidth = max((finalFrame.width + 1) / 2, 1)
        let outputHeight = max((finalFrame.height + 1) / 2, 1)
        let compared = try makeRg8Texture(width: outputWidth, height: outputHeight)
        let dilated = try makeRg8Texture(width: outputWidth, height: outputHeight)
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("UI control command buffer creation failed")
        }

        func beginPass(
            target: MTLTexture,
            pipeline: MTLRenderPipelineState
        ) throws -> MTLRenderCommandEncoder {
            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = target
            pass.colorAttachments[0].loadAction = .dontCare
            pass.colorAttachments[0].storeAction = .store
            guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
                throw ValidationFailure.message("UI control render encoder creation failed")
            }
            encoder.setViewport(MTLViewport(
                originX: 0,
                originY: 0,
                width: Double(target.width),
                height: Double(target.height),
                znear: 0,
                zfar: 1
            ))
            encoder.setRenderPipelineState(pipeline)
            return encoder
        }

        let compare = try beginPass(target: compared, pipeline: uiComparePipeline)
        compare.setFragmentTexture(finalFrame, index: 0)
        compare.setFragmentTexture(sceneFrame, index: 1)
        var uniforms = HdrUiCompareUniforms(
            sourceEncoding: sourceEncoding,
            seededUiAvailable: seededUiAvailable ? 1 : 0,
            _padding0: SIMD2<UInt32>(repeating: 0)
        )
        compare.setFragmentBytes(
            &uniforms,
            length: MemoryLayout<HdrUiCompareUniforms>.stride,
            index: 0
        )
        compare.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        compare.endEncoding()

        let dilate = try beginPass(target: dilated, pipeline: uiDilatePipeline)
        dilate.setFragmentTexture(compared, index: 0)
        dilate.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        dilate.endEncoding()

        try complete(commandBuffer, label: "two-channel seeded UI control")
        return try readRg8(texture: dilated)
    }

    func renderPresent(
        finalFrame: MTLTexture,
        sceneFrame: MTLTexture,
        emissionFrame: MTLTexture,
        bloomFrame: MTLTexture,
        uiMaskFrame: MTLTexture,
        uiFrame: MTLTexture,
        uniforms: PresentUniforms,
        adaptiveState: HdrAdaptiveState = defaultAdaptiveState(),
        semanticFrame: MTLTexture? = nil,
        sceneDepthFrame: MTLTexture? = nil,
        readCoordinate: SIMD2<Int>? = nil
    ) throws -> SIMD4<Float> {
        let output = try makePrivateRgba16FloatTexture(width: finalFrame.width, height: finalFrame.height)
        let boundDepth: MTLTexture
        if let sceneDepthFrame {
            boundDepth = sceneDepthFrame
        } else {
            boundDepth = try makeDepthTexture(
                width: finalFrame.width,
                height: finalFrame.height,
                clearDepth: 1.0
            )
        }
        var mutableAdaptiveState = adaptiveState
        let adaptiveBuffer = withUnsafeBytes(of: &mutableAdaptiveState) { bytes in
            device.makeBuffer(
                bytes: bytes.baseAddress!,
                length: bytes.count,
                options: .storageModeShared
            )
        }
        guard let commandBuffer = queue.makeCommandBuffer(), let adaptiveBuffer else {
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
        encoder.setFragmentTexture(semanticFrame ?? finalFrame, index: 6)
        encoder.setFragmentTexture(boundDepth, index: 7)
        encoder.setFragmentSamplerState(nearestSampler, index: 0)
        encoder.setFragmentSamplerState(linearSampler, index: 1)
        var mutableUniforms = uniforms
        encoder.setFragmentBytes(
            &mutableUniforms,
            length: MemoryLayout<PresentUniforms>.stride,
            index: 0
        )
        encoder.setFragmentBuffer(adaptiveBuffer, offset: 0, index: 1)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "HDR present")
        let coordinate = readCoordinate ?? SIMD2<Int>(output.width / 2, output.height / 2)
        return try readRgba16Float(texture: output, x: coordinate.x, y: coordinate.y)
    }

    func renderBoundaryLinearBlend(
        encodedSource: SIMD4<Float>,
        clearColor: SIMD4<Float> = SIMD4<Float>(0, 0, 0, 0)
    ) throws -> SIMD4<Float> {
        let output = try makePrivateRgba16FloatTexture(width: 4, height: 4)
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Boundary-linear blend command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = MTLClearColorMake(
            Double(clearColor.x),
            Double(clearColor.y),
            Double(clearColor.z),
            Double(clearColor.w)
        )
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Boundary-linear blend encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(output.width),
            height: Double(output.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(boundaryBlendPipeline)
        var mutableSource = encodedSource
        encoder.setFragmentBytes(
            &mutableSource,
            length: MemoryLayout<SIMD4<Float>>.stride,
            index: 0
        )
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "boundary-linear fixed-function blend")
        return try readRgba16Float(texture: output, x: 2, y: 2)
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
        try validateSdrOutputTransferContracts()
        try validateNonsemanticWhiteUsesHeadroom()
        try validateMidtoneIdentity()
        try validateSaturatedSdrIdentity()
        try validateBoundaryLinearRasterAndBlend()
        try validateSemanticVisibilityAndOcclusion()
        try validateFullResolutionSemanticTargets()
        try validateLowSemanticStrengthGradient()
        try validateCoverageWeightedBloomSeed()
        try validateBloomPresentBoundsAndUiControl()
        try validateExtendedSrgbIsUnclipped()
        try validateSdrUiCeiling()
        try validateSeededUiQuantizationAndDeterminism()
        try validateContinuousVignetteControl()
        try validateHardAndFallbackUiControl()
        try validateUiControlDilationChannels()
        try validateTwoChannelPresentVisibility()
        try validateUniformMidgrayHistogram()
        try validateOutdoorSkyReconstruction()
        try validateSparseAndBroadWhiteTargets()
        try validateImmediateHeadroomDropCap()
        try validateFrameRateIndependentSmoothing()
        try validateNativeBackdropAndPresentAbi()
        print("HDR GPU value validation passed (\(passCount) checks)")
    }

    private func baseUniforms(
        mode: UInt32 = 2,
        sourceEncoding: UInt32 = 0,
        headroom: Float = 4,
        sceneAvailable: Bool = true,
        uiAvailable: Bool = false,
        semanticAvailable: Bool = false
    ) -> PresentUniforms {
        PresentUniforms(
            mode: mode,
            sourceEncoding: sourceEncoding,
            diagnosticPattern: 0,
            currentHeadroom: headroom,
            hdrStrength: 1,
            bloomStrength: 0,
            sceneAvailable: sceneAvailable ? 1 : 0,
            uiAvailable: uiAvailable ? 1 : 0,
            semanticAvailable: semanticAvailable ? 1 : 0
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
            try gpu.makeRg8Texture(),
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

    private func validateSdrOutputTransferContracts() throws {
        let aux = try auxiliaries()
        let sceneLinear = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(srgbToLinear(0.5), 0.5, 1.5, 1)
        )
        let linearValue = try gpu.renderPresent(
            finalFrame: sceneLinear,
            sceneFrame: sceneLinear,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms(
                mode: 0,
                sourceEncoding: 2,
                headroom: 1,
                sceneAvailable: false
            )
        )
        try require(abs(linearValue.x - 0.5) < 0.004,
                    "Linear FP16 SDR red expected 0.5 sRGB, got \(rgbaDescription(linearValue))")
        try require(abs(linearValue.y - linearToSrgb(0.5)) < 0.004,
                    "Linear FP16 SDR green was not sRGB encoded: \(rgbaDescription(linearValue))")
        try require(abs(linearValue.z - 1.0) < 0.004,
                    "Linear FP16 SDR highlight was not clamped: \(rgbaDescription(linearValue))")

        let extendedSrgb = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(0.5, 1.5, 0.25, 1)
        )
        let encodedValue = try gpu.renderPresent(
            finalFrame: extendedSrgb,
            sceneFrame: extendedSrgb,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms(
                mode: 0,
                sourceEncoding: 1,
                headroom: 1,
                sceneAvailable: false
            )
        )
        try require(abs(encodedValue.x - 0.5) < 0.004 && abs(encodedValue.y - 1.0) < 0.004,
                    "Encoded FP16 SDR source was double-transferred or unclamped: \(rgbaDescription(encodedValue))")
        try require(abs(encodedValue.z - 0.25) < 0.004,
                    "Encoded FP16 SDR source changed color: \(rgbaDescription(encodedValue))")
        passCount += 1
        print("PASS SDR output encodes linear FP16 and preserves encoded sRGB sources")
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

    private func validateSaturatedSdrIdentity() throws {
        let yellow = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 0, 255))
        let aux = try auxiliaries()
        let value = try gpu.renderPresent(
            finalFrame: yellow,
            sceneFrame: yellow,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms(headroom: 1.2),
            adaptiveState: identityAdaptiveState(headroom: 1.2)
        )
        try require(abs(value.x - 1.0) < 0.004 && abs(value.y - 1.0) < 0.004,
                    "SDR yellow was darkened by the HDR peak metric: \(rgbaDescription(value))")
        try require(abs(value.z) < 0.002, "SDR yellow changed hue: \(rgbaDescription(value))")
        pass("saturated SDR yellow preserves reference white and hue", value)
    }

    private func validateBoundaryLinearRasterAndBlend() throws {
        let opaqueMidgray = try gpu.renderBoundaryLinearBlend(
            encodedSource: SIMD4<Float>(0.5, 0.5, 0.5, 1.0)
        )
        let expectedMidgray = srgbToLinear(0.5)
        try require(
            abs(opaqueMidgray.x - expectedMidgray) < 0.003,
            "Boundary decode expected encoded 0.5 to become \(expectedMidgray), got \(rgbaDescription(opaqueMidgray))"
        )
        pass("raster boundary decodes encoded 0.5 into linear FP16", opaqueMidgray)

        let halfAlphaWhite = try gpu.renderBoundaryLinearBlend(
            encodedSource: SIMD4<Float>(1.0, 1.0, 1.0, 0.5)
        )
        let expectedLinearBlend: Float = 0.5
        let lateDecodeResult = srgbToLinear(0.5)
        try require(
            abs(halfAlphaWhite.x - expectedLinearBlend) < 0.003,
            "Linear fixed-function blend expected 0.5, got \(rgbaDescription(halfAlphaWhite))"
        )
        try require(
            halfAlphaWhite.x > lateDecodeResult + 0.25,
            "Blend still resembles encoded blend followed by late decode (\(lateDecodeResult)): \(rgbaDescription(halfAlphaWhite))"
        )
        pass("50%-alpha white blends to 0.5 linear instead of late-decode 0.214", halfAlphaWhite)
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
        try require(visibleExtract.x > 0.20 && visibleExtract.x < 0.30,
                    "Visible semantic marker produced an invalid bloom seed: \(rgbaDescription(visibleExtract))")

        let visibleValue = try gpu.renderPresent(
            finalFrame: scene,
            sceneFrame: scene,
            emissionFrame: visibleEmission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms(semanticAvailable: true),
            semanticFrame: visibleSemantic,
            sceneDepthFrame: visibleDepth
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
            uniforms: baseUniforms(semanticAvailable: true),
            semanticFrame: occludedSemantic,
            sceneDepthFrame: occludingDepth
        )
        let expectedBase = srgbToLinear(0.8)
        try require(abs(occludedValue.x - expectedBase) < 0.01, "Occluded semantic marker changed the scene: \(rgbaDescription(occludedValue))")
        try require(occludedValue.x <= 1.001, "Occluded semantic marker entered EDR: \(rgbaDescription(occludedValue))")
        pass("occluded semantic marker is rejected", occludedValue)
    }

    private func validateFullResolutionSemanticTargets() throws {
        let white = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 255))
        let depth = try gpu.makeDepthTexture(clearDepth: 0.5)
        let sun = try gpu.makeSemanticTexture(markerDepth: 0.5, strength: 102, exact: false)
        let exact = try gpu.makeSemanticTexture(markerDepth: 0.5)
        let aux = try auxiliaries()
        let adaptive = identityAdaptiveState(headroom: 1.2)
        let uniforms = baseUniforms(headroom: 1.2, semanticAvailable: true)

        let sunValue = try gpu.renderPresent(
            finalFrame: white,
            sceneFrame: white,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: uniforms,
            adaptiveState: adaptive,
            semanticFrame: sun,
            sceneDepthFrame: depth
        )
        let exactValue = try gpu.renderPresent(
            finalFrame: white,
            sceneFrame: white,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: uniforms,
            adaptiveState: adaptive,
            semanticFrame: exact,
            sceneDepthFrame: depth
        )
        var aggressiveAdaptive = adaptive
        aggressiveAdaptive.breakpoint = 0.34
        aggressiveAdaptive.inferredPeak = 2.8
        let sunWithAggressiveHistory = try gpu.renderPresent(
            finalFrame: white,
            sceneFrame: white,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: uniforms,
            adaptiveState: aggressiveAdaptive,
            semanticFrame: sun,
            sceneDepthFrame: depth
        )
        try require(sunValue.x > 1.05 && sunValue.x < 1.14,
                    "Low-headroom sun target clipped or stayed SDR: \(rgbaDescription(sunValue))")
        try require(exactValue.x > sunValue.x + 0.02 && exactValue.x < 1.18,
                    "Exact emitter lost its bounded hierarchy: sun=\(rgbaDescription(sunValue)), exact=\(rgbaDescription(exactValue))")
        try require(abs(sunWithAggressiveHistory.x - sunValue.x) < 0.004,
                    "Generic adaptive history overrode authoritative semantic brightness: \(rgbaDescription(sunWithAggressiveHistory))")

        let gray = try gpu.makeRgba8Texture(
            width: 4,
            height: 4,
            bytes: SIMD4<UInt8>(204, 204, 204, 255)
        )
        var isolatedPixels = [SIMD4<UInt8>](
            repeating: SIMD4<UInt8>(0, 0, 0, 0),
            count: 16
        )
        let exactPixel = semanticPixel(markerDepth: 0.5)
        isolatedPixels[0] = exactPixel
        let isolated = try gpu.makeRgba8Texture(width: 4, height: 4, pixels: isolatedPixels)
        let isolatedDepth = try gpu.makeDepthTexture(width: 4, height: 4, clearDepth: 0.5)
        let base = srgbToLinear(0.8)
        let tagged = try gpu.renderPresent(
            finalFrame: gray,
            sceneFrame: gray,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: uniforms,
            adaptiveState: adaptive,
            semanticFrame: isolated,
            sceneDepthFrame: isolatedDepth,
            readCoordinate: SIMD2<Int>(0, 3)
        )
        let horizontalNeighbor = try gpu.renderPresent(
            finalFrame: gray,
            sceneFrame: gray,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: uniforms,
            adaptiveState: adaptive,
            semanticFrame: isolated,
            sceneDepthFrame: isolatedDepth,
            readCoordinate: SIMD2<Int>(1, 3)
        )
        let verticalNeighbor = try gpu.renderPresent(
            finalFrame: gray,
            sceneFrame: gray,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: uniforms,
            adaptiveState: adaptive,
            semanticFrame: isolated,
            sceneDepthFrame: isolatedDepth,
            readCoordinate: SIMD2<Int>(0, 2)
        )
        try require(tagged.x > base + 0.035,
                    "Tagged full-resolution texel did not receive semantic HDR: \(rgbaDescription(tagged))")
        try require(abs(horizontalNeighbor.x - base) < 0.006,
                    "Semantic HDR leaked horizontally from a tagged texel: \(rgbaDescription(horizontalNeighbor))")
        try require(abs(verticalNeighbor.x - base) < 0.006,
                    "Semantic HDR used the wrong vertically flipped source row: \(rgbaDescription(verticalNeighbor))")
        passCount += 1
        print(String(format: "PASS full-resolution semantic targets preserve hierarchy, XY isolation, and Y flip: sun %.3f, exact %.3f, neighbors %.3f/%.3f",
                     sunValue.x, exactValue.x, horizontalNeighbor.x, verticalNeighbor.x))
    }

    private func validateLowSemanticStrengthGradient() throws {
        let scene = try gpu.makeRgba8Texture(
            width: 4,
            height: 4,
            bytes: SIMD4<UInt8>(204, 204, 204, 255)
        )
        let depth = try gpu.makeDepthTexture(width: 4, height: 4, clearDepth: 0.5)
        let strengthOne = try gpu.makeSemanticTexture(
            markerDepth: 0.5,
            strength: 1,
            exact: false
        )
        let strengthTwo = try gpu.makeSemanticTexture(
            markerDepth: 0.5,
            strength: 2,
            exact: false
        )
        let seedOne = try gpu.readRgba16Float(texture: gpu.renderExtract(
            scene: scene,
            semantic: strengthOne,
            depth: depth,
            sourceEncoding: 0
        ))
        let seedTwo = try gpu.readRgba16Float(texture: gpu.renderExtract(
            scene: scene,
            semantic: strengthTwo,
            depth: depth,
            sourceEncoding: 0
        ))
        let ratio = seedTwo.x / max(seedOne.x, 1e-8)
        try require(seedOne.x > 0.0005,
                    "Lowest nonzero semantic strength was discarded: \(rgbaDescription(seedOne))")
        try require(ratio > 1.8 && ratio < 2.2,
                    "Seven-bit semantic gradient is not monotonic: one=\(rgbaDescription(seedOne)), two=\(rgbaDescription(seedTwo))")

        let white = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 255))
        let zeroStrength = try gpu.makeSemanticTexture(
            markerDepth: 0.5,
            strength: 0,
            exact: false
        )
        let aux = try auxiliaries()
        let uniforms = baseUniforms(headroom: 4.0, semanticAvailable: true)
        let untagged = try gpu.renderPresent(
            finalFrame: white,
            sceneFrame: white,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: uniforms,
            semanticFrame: zeroStrength,
            sceneDepthFrame: depth
        )
        let weakestTagged = try gpu.renderPresent(
            finalFrame: white,
            sceneFrame: white,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: uniforms,
            semanticFrame: strengthOne,
            sceneDepthFrame: depth
        )
        try require(abs(weakestTagged.x - untagged.x) < 0.02,
                    "First semantic strength level introduced an HDR contour: zero=\(rgbaDescription(untagged)), one=\(rgbaDescription(weakestTagged))")
        passCount += 1
        print(String(format: "PASS seven-bit semantic strength preserves weak gradients without an authority contour: %.6f -> %.6f, edge %.4f",
                     seedOne.x, seedTwo.x, abs(weakestTagged.x - untagged.x)))
    }

    private func validateCoverageWeightedBloomSeed() throws {
        let scene = try gpu.makeRgba8Texture(
            width: 4,
            height: 4,
            bytes: SIMD4<UInt8>(204, 204, 204, 255)
        )
        let depth = try gpu.makeDepthTexture(width: 4, height: 4, clearDepth: 0.5)
        let full = try gpu.makeSemanticTexture(markerDepth: 0.5)
        var sparsePixels = [SIMD4<UInt8>](
            repeating: SIMD4<UInt8>(0, 0, 0, 0),
            count: 16
        )
        sparsePixels[0] = semanticPixel(markerDepth: 0.5)
        let sparse = try gpu.makeRgba8Texture(width: 4, height: 4, pixels: sparsePixels)
        var mixedPixels = [SIMD4<UInt8>](repeating: semanticPixel(
            markerDepth: 0.5,
            strength: 8,
            exact: false
        ), count: 16)
        for index in 0..<8 {
            mixedPixels[index] = semanticPixel(markerDepth: 0.5)
        }
        let mixed = try gpu.makeRgba8Texture(width: 4, height: 4, pixels: mixedPixels)
        let fullSeed = try gpu.readRgba16Float(texture: gpu.renderExtract(
            scene: scene,
            semantic: full,
            depth: depth,
            sourceEncoding: 0
        ))
        let sparseSeed = try gpu.readRgba16Float(texture: gpu.renderExtract(
            scene: scene,
            semantic: sparse,
            depth: depth,
            sourceEncoding: 0
        ))
        let mixedSeed = try gpu.readRgba16Float(texture: gpu.renderExtract(
            scene: scene,
            semantic: mixed,
            depth: depth,
            sourceEncoding: 0
        ))
        let energyRatio = sparseSeed.x / max(fullSeed.x, 1e-6)
        let mixedRatio = mixedSeed.x / max(fullSeed.x, 1e-6)
        let expectedMixedRatio: Float = 0.5 + 0.5 * (((8.0 / 127.0) * 0.20) / 0.42)
        try require(fullSeed.x > 0.20 && fullSeed.x < 0.30,
                    "Full semantic bloom seed is outside its bounded range: \(rgbaDescription(fullSeed))")
        try require(abs(energyRatio - 1.0 / 16.0) < 0.01,
                    "One semantic texel did not contribute 1/16 bloom energy: ratio=\(energyRatio)")
        try require(abs(mixedRatio - expectedMixedRatio) < 0.01,
                    "Mixed semantic strengths were not accumulated per texel: ratio=\(mixedRatio), expected=\(expectedMixedRatio)")
        passCount += 1
        print(String(format: "PASS quarter-resolution bloom seed is coverage weighted: one %.4f, mixed %.4f",
                     energyRatio, mixedRatio))
    }

    private func validateBloomPresentBoundsAndUiControl() throws {
        let gray = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(128, 128, 128, 255))
        let bloom = try gpu.makeRgba16FloatTexture(
            width: 1,
            height: 1,
            value: SIMD4<Float>(1, 1, 1, 1)
        )
        let aux = try auxiliaries()
        let base = srgbToLinear(Float(128) / 255.0)

        var lowUniforms = baseUniforms(headroom: 1.2)
        lowUniforms.bloomStrength = 0.18
        let low = try gpu.renderPresent(
            finalFrame: gray,
            sceneFrame: gray,
            emissionFrame: aux.emission,
            bloomFrame: bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: lowUniforms,
            adaptiveState: identityAdaptiveState(headroom: 1.2)
        )
        let lowDelta = low.x - base
        try require(lowDelta >= 0.028 && lowDelta <= 0.031,
                    "Low-headroom bloom escaped its 15%-of-range cap: delta=\(lowDelta)")

        var highUniforms = baseUniforms(headroom: 3.0)
        highUniforms.bloomStrength = 0.18
        let high = try gpu.renderPresent(
            finalFrame: gray,
            sceneFrame: gray,
            emissionFrame: aux.emission,
            bloomFrame: bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: highUniforms,
            adaptiveState: identityAdaptiveState(headroom: 3.0)
        )
        let highDelta = high.x - base
        try require(highDelta >= 0.295 && highDelta <= 0.305,
                    "High-headroom bloom escaped its bounded scale: delta=\(highDelta)")

        let halfUiControl = try gpu.makeRg8Texture(bytes: SIMD2<UInt8>(128, 0))
        let controlled = try gpu.renderPresent(
            finalFrame: gray,
            sceneFrame: gray,
            emissionFrame: aux.emission,
            bloomFrame: bloom,
            uiMaskFrame: halfUiControl,
            uiFrame: aux.transparentUi,
            uniforms: lowUniforms,
            adaptiveState: identityAdaptiveState(headroom: 1.2)
        )
        let controlledRatio = (controlled.x - base) / max(lowDelta, 1e-6)
        let expectedRatio = 1.0 - Float(128) / 255.0
        try require(abs(controlledRatio - expectedRatio) < 0.01,
                    "UI control did not attenuate bloom with the complete HDR delta: ratio=\(controlledRatio)")

        let brightUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 0))
        let brightScene = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(204, 204, 204, 255))
        let brightSceneLinear = srgbToLinear(0.8)
        let sceneAverage = try gpu.makeRgba16FloatTexture(
            width: 1,
            height: 1,
            value: SIMD4<Float>(0, 0, 0, brightSceneLinear)
        )
        var aggressive = identityAdaptiveState(headroom: 1.2)
        aggressive.breakpoint = 0.34
        aggressive.inferredPeak = 2.8
        let capped = try gpu.renderPresent(
            finalFrame: brightScene,
            sceneFrame: brightScene,
            emissionFrame: sceneAverage,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: brightUi,
            uniforms: baseUniforms(headroom: 1.2, uiAvailable: true),
            adaptiveState: aggressive
        )
        let partialControl = try gpu.makeRg8Texture(bytes: SIMD2<UInt8>(128, 128))
        let partiallyControlled = try gpu.renderPresent(
            finalFrame: brightScene,
            sceneFrame: brightScene,
            emissionFrame: sceneAverage,
            bloomFrame: aux.bloom,
            uiMaskFrame: partialControl,
            uiFrame: brightUi,
            uniforms: baseUniforms(headroom: 1.2, uiAvailable: true),
            adaptiveState: aggressive
        )
        try require(capped.x >= 1.19 && capped.x <= 1.2005,
                    "Seeded UI base plus HDR delta escaped the drawable headroom cap: \(rgbaDescription(capped))")
        try require(partiallyControlled.x > 1.0 && partiallyControlled.x < capped.x - 0.02,
                    "Partial R/G UI control was not monotonic below the headroom cap: \(rgbaDescription(partiallyControlled))")
        passCount += 1
        print(String(format: "PASS bloom/UI delta stays bounded and monotonic: low %.3f, high %.3f, visibility %.3f, cap %.3f",
                     lowDelta, highDelta, controlledRatio, capped.x))
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

    private func validateSeededUiQuantizationAndDeterminism() throws {
        // This value is almost half an RGBA8 step above byte 128. Comparing
        // the quantized backdrop with the raw FP16 source used to create a
        // false mask near 0.5 even though no UI had been drawn.
        let encodedHalfStep = (Float(128) + 0.49) / 255.0
        let scene = try gpu.makeRgba16FloatTexture(
            width: 4,
            height: 4,
            value: SIMD4<Float>(encodedHalfStep, encodedHalfStep, encodedHalfStep, 1)
        )
        let unchangedBackdrop = try gpu.makeRgba8Texture(
            width: 4,
            height: 4,
            bytes: SIMD4<UInt8>(128, 128, 128, 0)
        )
        let first = try gpu.renderUiControl(
            finalFrame: unchangedBackdrop,
            sceneFrame: scene,
            sourceEncoding: 1,
            seededUiAvailable: true
        )
        try require(
            first.allSatisfy { $0 == SIMD2<UInt8>(0, 0) },
            "Unchanged half-LSB backdrop created UI control values: \(first)"
        )
        for iteration in 0..<8 {
            let repeated = try gpu.renderUiControl(
                finalFrame: unchangedBackdrop,
                sceneFrame: scene,
                sourceEncoding: 1,
                seededUiAvailable: true
            )
            try require(repeated == first, "Static UI control changed on deterministic repeat \(iteration)")
        }
        passCount += 1
        print("PASS seeded RGBA8 quantization yields exact zero and deterministic UI control")
    }

    private func validateContinuousVignetteControl() throws {
        let baseline: UInt8 = 204
        let dimmedValues: [UInt8] = [204, 190, 170, 140]
        var finalPixels = [SIMD4<UInt8>]()
        finalPixels.reserveCapacity(dimmedValues.count * 4)
        for _ in 0..<2 {
            for value in dimmedValues {
                finalPixels.append(SIMD4<UInt8>(value, value, value, 0))
                finalPixels.append(SIMD4<UInt8>(value, value, value, 0))
            }
        }
        let scene = try gpu.makeRgba8Texture(
            width: 8,
            height: 2,
            bytes: SIMD4<UInt8>(baseline, baseline, baseline, 255)
        )
        let vignette = try gpu.makeRgba8Texture(width: 8, height: 2, pixels: finalPixels)
        let control = try gpu.renderUiControl(
            finalFrame: vignette,
            sceneFrame: scene,
            sourceEncoding: 0,
            seededUiAvailable: true
        )
        try require(control.count == dimmedValues.count, "Unexpected vignette control dimensions")

        let baselineLinear = srgbToLinear(Float(baseline) / 255.0)
        for (index, value) in dimmedValues.enumerated() {
            let expectedCoverage = 1.0 - srgbToLinear(Float(value) / 255.0) / baselineLinear
            let expectedByte = Int((min(max(expectedCoverage, 0.0), 1.0) * 255.0).rounded())
            try require(control[index].x == 0, "Vignette became hard UI coverage at cell \(index): \(control)")
            try require(
                abs(Int(control[index].y) - expectedByte) <= 2,
                "Vignette transmission mismatch at cell \(index): got \(control[index].y), expected \(expectedByte)"
            )
        }
        try require(
            zip(control, control.dropFirst()).allSatisfy { pair in pair.0.y < pair.1.y },
            "Vignette dimming coverage is not continuous and monotonic: \(control)"
        )
        try require(control[1].y > 0 && control[1].y < 255, "Vignette control collapsed to a binary mask")
        passCount += 1
        print("PASS alpha-zero vignette produces continuous bounded-linear dimming coverage")
    }

    private func validateHardAndFallbackUiControl() throws {
        let scene = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(64, 128, 200, 255))

        let opaqueHud = try gpu.renderUiControl(
            finalFrame: gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(64, 128, 200, 255)),
            sceneFrame: scene,
            sourceEncoding: 0,
            seededUiAvailable: true
        )
        try require(opaqueHud.allSatisfy { $0 == SIMD2<UInt8>(255, 0) }, "Opaque HUD control mismatch: \(opaqueHud)")

        let halfAlphaHud = try gpu.renderUiControl(
            finalFrame: gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(64, 128, 200, 128)),
            sceneFrame: scene,
            sourceEncoding: 0,
            seededUiAvailable: true
        )
        try require(halfAlphaHud.allSatisfy { $0 == SIMD2<UInt8>(128, 0) }, "Half-alpha HUD control mismatch: \(halfAlphaHud)")

        let invert = try gpu.renderUiControl(
            finalFrame: gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(191, 127, 55, 0)),
            sceneFrame: scene,
            sourceEncoding: 0,
            seededUiAvailable: true
        )
        try require(invert.allSatisfy { $0 == SIMD2<UInt8>(255, 0) }, "Alpha-zero invert was not hard-covered: \(invert)")

        let fallbackScene = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(100, 100, 50, 255))
        let fallbackDifference = try gpu.renderUiControl(
            finalFrame: gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(150, 100, 50, 255)),
            sceneFrame: fallbackScene,
            sourceEncoding: 0,
            seededUiAvailable: false
        )
        try require(
            fallbackDifference.allSatisfy { $0 == SIMD2<UInt8>(255, 0) },
            "Unseeded RGB fallback was not retained in hard coverage: \(fallbackDifference)"
        )
        let fallbackIdentity = try gpu.renderUiControl(
            finalFrame: fallbackScene,
            sceneFrame: fallbackScene,
            sourceEncoding: 0,
            seededUiAvailable: false
        )
        try require(
            fallbackIdentity.allSatisfy { $0 == SIMD2<UInt8>(0, 0) },
            "Unseeded identical frames created UI coverage: \(fallbackIdentity)"
        )
        passCount += 1
        print("PASS hard HUD/invert coverage and unseeded RGB fallback remain isolated in R")
    }

    private func validateUiControlDilationChannels() throws {
        let baseline: UInt8 = 200
        let cells = [
            SIMD4<UInt8>(baseline, baseline, baseline, 255),
            SIMD4<UInt8>(100, 100, 100, 0),
            SIMD4<UInt8>(baseline, baseline, baseline, 0)
        ]
        var pixels = [SIMD4<UInt8>]()
        for _ in 0..<2 {
            for cell in cells {
                pixels.append(cell)
                pixels.append(cell)
            }
        }
        let scene = try gpu.makeRgba8Texture(
            width: 6,
            height: 2,
            bytes: SIMD4<UInt8>(baseline, baseline, baseline, 255)
        )
        let final = try gpu.makeRgba8Texture(width: 6, height: 2, pixels: pixels)
        let control = try gpu.renderUiControl(
            finalFrame: final,
            sceneFrame: scene,
            sourceEncoding: 0,
            seededUiAvailable: true
        )
        try require(control.count == 3, "Unexpected dilation fixture dimensions")
        try require(control[0].x == 255 && control[1].x == 255 && control[2].x == 0,
                    "Hard coverage dilation mismatch: \(control)")
        let expectedDimming = Int(((1.0 - srgbToLinear(Float(100) / 255.0)
            / srgbToLinear(Float(baseline) / 255.0)) * 255.0).rounded())
        try require(control[0].y == 0 && abs(Int(control[1].y) - expectedDimming) <= 2 && control[2].y == 0,
                    "Dilation did not preserve each center's dimming channel: \(control)")
        passCount += 1
        print("PASS dilation expands hard coverage only and preserves center dimming coverage")
    }

    private func validateTwoChannelPresentVisibility() throws {
        let white = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 255))
        let ui = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 0))
        let aux = try auxiliaries()
        let noControl = try gpu.makeRg8Texture(bytes: SIMD2<UInt8>(0, 0))
        let twoChannelControl = try gpu.makeRg8Texture(bytes: SIMD2<UInt8>(64, 128))
        let uniforms = baseUniforms(uiAvailable: true)
        let unmasked = try gpu.renderPresent(
            finalFrame: white,
            sceneFrame: white,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: noControl,
            uiFrame: ui,
            uniforms: uniforms
        )
        let controlled = try gpu.renderPresent(
            finalFrame: white,
            sceneFrame: white,
            emissionFrame: aux.emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: twoChannelControl,
            uiFrame: ui,
            uniforms: uniforms
        )
        let expectedVisibility = (1.0 - Float(64) / 255.0) * (1.0 - Float(128) / 255.0)
        let measuredVisibility = (controlled.x - 1.0) / (unmasked.x - 1.0)
        try require(
            abs(measuredVisibility - expectedVisibility) < 0.01,
            "Present did not multiply hard and dimming visibility: got \(measuredVisibility), expected \(expectedVisibility)"
        )
        passCount += 1
        print(String(format: "PASS present multiplies R/G UI visibility: %.4f", measuredVisibility))
    }

    private func validateUniformMidgrayHistogram() throws {
        let scene = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(128, 128, 128, 255)
        )
        let analysis = try gpu.analyzeHistogram(scene: scene)
        let total = analysis.bins.reduce(UInt32(0), +)
        let linearMidgray = srgbToLinear(Float(128) / 255)
        let expectedBin = min(max(Int((log2(Double(linearMidgray)) + 12.0) * 4.0), 0), 63)
        try require(total == 16, "Uniform 16x16 scene must contribute 16 quarter cells, got \(total)")
        try require(analysis.bins[expectedBin] == 16, "Midgray histogram bin \(expectedBin) contains \(analysis.bins[expectedBin]), expected 16")
        let expectedQuantile = -12.0 + (Float(expectedBin) + 0.5) * 0.25
        try require(abs(analysis.state.medianLog2 - expectedQuantile) < 0.001,
                    "GPU p50 does not match the populated bin: \(analysis.state.medianLog2)")
        try require(abs(analysis.state.p90Log2 - expectedQuantile) < 0.001,
                    "GPU p90 does not match the populated bin: \(analysis.state.p90Log2)")
        try require(abs(analysis.state.p99Log2 - expectedQuantile) < 0.001,
                    "GPU p99 does not match the populated bin: \(analysis.state.p99Log2)")
        try require(abs(analysis.state.breakpoint - 0.70) < 0.001, "Midgray breakpoint should stay at 0.70")
        try require(abs(analysis.state.inferredPeak - 1.0) < 0.001, "Midgray must not infer an HDR peak")

        let semanticScene = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(204, 204, 204, 255))
        let semantic = try gpu.makeSemanticTexture(markerDepth: 0.5)
        let semanticDepth = try gpu.makeDepthTexture(clearDepth: 0.5)
        let excluded = try gpu.analyzeHistogram(
            scene: semanticScene,
            semantic: semantic,
            sceneDepth: semanticDepth
        )
        try require(excluded.bins.reduce(UInt32(0), +) == 0,
                    "A quarter cell with valid semantic emission entered the generic histogram")
        passCount += 1
        print("PASS 64-bin GPU histogram counts one midgray sample per quarter cell and excludes semantic emission")
    }

    private func validateOutdoorSkyReconstruction() throws {
        let cellsWide = 8
        let cellsHigh = 8
        let width = cellsWide * 4
        let height = cellsHigh * 4
        let sky = SIMD4<UInt8>(132, 168, 241, 255)
        let cloud = SIMD4<UInt8>(210, 220, 240, 255)
        let landscape = SIMD4<UInt8>(100, 140, 80, 255)
        var pixels = [SIMD4<UInt8>](repeating: sky, count: width * height)
        for cell in 0..<(cellsWide * cellsHigh) {
            let cellColor: SIMD4<UInt8>
            if cell < 12 {
                cellColor = landscape
            } else if cell < 52 {
                cellColor = sky
            } else {
                cellColor = cloud
            }
            let cellX = cell % cellsWide
            let cellY = cell / cellsWide
            for y in 0..<4 {
                for x in 0..<4 {
                    pixels[(cellY * 4 + y) * width + cellX * 4 + x] = cellColor
                }
            }
        }

        let outdoor = try gpu.makeRgba8Texture(width: width, height: height, pixels: pixels)
        let analysis = try gpu.analyzeHistogram(scene: outdoor, currentHeadroom: 1.2)
        try require(analysis.state.breakpoint <= 0.45,
                    "Outdoor scene did not lower the reconstruction breakpoint: \(analysis.state.breakpoint)")
        try require(analysis.state.inferredPeak >= 1.10 && analysis.state.inferredPeak <= 1.201,
                    "Outdoor scene did not allocate bounded low-headroom EDR: \(analysis.state.inferredPeak)")

        let skyFrame = try gpu.makeRgba8Texture(bytes: sky)
        let skyR = srgbToLinear(Float(sky.x) / 255.0)
        let skyG = srgbToLinear(Float(sky.y) / 255.0)
        let skyB = srgbToLinear(Float(sky.z) / 255.0)
        let skyY = 0.2126 * skyR + 0.7152 * skyG + 0.0722 * skyB
        let emission = try gpu.makeRgba16FloatTexture(
            width: 1,
            height: 1,
            value: SIMD4<Float>(0, 0, 0, skyY)
        )
        let aux = try auxiliaries()
        let value = try gpu.renderPresent(
            finalFrame: skyFrame,
            sceneFrame: skyFrame,
            emissionFrame: emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms(headroom: 1.2),
            adaptiveState: analysis.state
        )
        let outputPeak = max(value.x, max(value.y, value.z))
        try require(outputPeak > 1.01 && outputPeak <= 1.201,
                    "Blue outdoor sky did not enter safe EDR: \(rgbaDescription(value))")

        var disabledUniforms = baseUniforms(headroom: 1.2)
        disabledUniforms.hdrStrength = 0
        let disabled = try gpu.renderPresent(
            finalFrame: skyFrame,
            sceneFrame: skyFrame,
            emissionFrame: emission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: disabledUniforms,
            adaptiveState: analysis.state
        )
        try require(abs(disabled.x - skyR) < 0.004
                    && abs(disabled.y - skyG) < 0.004
                    && abs(disabled.z - skyB) < 0.004,
                    "hdrStrength=0 changed the outdoor scene: \(rgbaDescription(disabled))")

        let darkBlue = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(0, 0, 200, 255))
        let darkBlueLinear = srgbToLinear(Float(200) / 255.0)
        let darkBlueY = 0.0722 * darkBlueLinear
        let darkEmission = try gpu.makeRgba16FloatTexture(
            width: 1,
            height: 1,
            value: SIMD4<Float>(0, 0, 0, darkBlueY)
        )
        let darkValue = try gpu.renderPresent(
            finalFrame: darkBlue,
            sceneFrame: darkBlue,
            emissionFrame: darkEmission,
            bloomFrame: aux.bloom,
            uiMaskFrame: aux.uiMask,
            uiFrame: aux.transparentUi,
            uniforms: baseUniforms(headroom: 1.2),
            adaptiveState: analysis.state
        )
        try require(abs(darkValue.x) < 0.002
                    && abs(darkValue.y) < 0.002
                    && abs(darkValue.z - darkBlueLinear) < 0.004,
                    "Outdoor adaptation made a dark saturated texel emissive: \(rgbaDescription(darkValue))")
        passCount += 1
        print(String(format: "PASS outdoor sky uses low-headroom EDR without lifting dark chroma: peak %.3f",
                     outputPeak))
    }

    private func validateSparseAndBroadWhiteTargets() throws {
        let sparse = try makeQuarterCellScene(
            cellsWide: 16,
            cellsHigh: 16,
            whiteCells: Set([0])
        )
        let broad = try gpu.makeRgba8Texture(
            width: 64,
            height: 64,
            bytes: SIMD4<UInt8>(255, 255, 255, 255)
        )
        let sparseAnalysis = try gpu.analyzeHistogram(scene: sparse, currentHeadroom: 4)
        let broadAnalysis = try gpu.analyzeHistogram(scene: broad, currentHeadroom: 4)
        let broadAtThree = try gpu.analyzeHistogram(scene: broad, currentHeadroom: 3).state
        let broadAtEight = try gpu.analyzeHistogram(scene: broad, currentHeadroom: 8).state
        let sparseState = sparseAnalysis.state
        let broadState = broadAnalysis.state

        for (name, state) in [("sparse", sparseState), ("broad", broadState)] {
            try require((0.34...0.70).contains(state.breakpoint),
                        "\(name) breakpoint escaped [0.34, 0.70]: \(state.breakpoint)")
            try require(state.inferredPeak >= 1.0 && state.inferredPeak <= 3.001,
                        "\(name) inferred peak is unsafe: \(state.inferredPeak)")
            try require(state.inferredPeak <= state.currentHeadroom + 0.001,
                        "\(name) inferred peak exceeded headroom")
        }
        try require(abs(sparseState.brightCoverage - (1.0 / 256.0)) < 0.0002,
                    "Sparse bright coverage mismatch: \(sparseState.brightCoverage)")
        try require(broadState.brightCoverage > 0.99,
                    "Broad white coverage mismatch: \(broadState.brightCoverage)")
        try require(sparseState.inferredPeak > broadState.inferredPeak + 0.5,
                    "Sparse highlight peak must exceed broad white: sparse=\(sparseState.inferredPeak), broad=\(broadState.inferredPeak)")
        let broadExpansionFraction = (broadState.inferredPeak - 1.0) / 2.0
        try require(broadExpansionFraction >= 0.15 && broadExpansionFraction <= 0.36,
                    "Broad white escaped its restrained expansion band: \(broadExpansionFraction)")
        try require(abs(broadAtEight.inferredPeak - broadAtThree.inferredPeak) < 0.002,
                    "Broad target became darker above the 3x reconstruction ceiling: H3=\(broadAtThree.inferredPeak), H8=\(broadAtEight.inferredPeak)")

        // The p50 jump from midgray-dominated sparse content to full white is
        // over two stops, so temporal state must reset instead of ghosting.
        let medianJump = try gpu.analyzeHistogram(
            scene: broad,
            currentHeadroom: 4,
            deltaTime: 1.0 / 60.0,
            forceReset: false,
            previousState: sparseState
        ).state
        try require(abs(medianJump.inferredPeak - broadState.inferredPeak) < 0.005,
                    "Median jump did not reset adaptive peak")
        passCount += 1
        print(String(format: "PASS adaptive targets: sparse peak %.3f > broad %.3f; broad expansion %.1f%%",
                     sparseState.inferredPeak, broadState.inferredPeak, broadExpansionFraction * 100.0))
    }

    private func validateImmediateHeadroomDropCap() throws {
        let sparse = try makeQuarterCellScene(
            cellsWide: 16,
            cellsHigh: 16,
            whiteCells: Set([0])
        )
        let highState = try gpu.analyzeHistogram(scene: sparse, currentHeadroom: 4).state
        try require(highState.inferredPeak > 2.0, "Headroom cap test needs a high initial peak")
        let dropped = try gpu.analyzeHistogram(
            scene: sparse,
            currentHeadroom: 1.5,
            deltaTime: 1.0 / 60.0,
            forceReset: false,
            previousState: highState
        ).state
        try require(dropped.inferredPeak <= 1.5001,
                    "Headroom drop was temporally delayed: \(dropped.inferredPeak)")
        try require(dropped.inferredPeak >= 1.0, "Headroom drop produced an invalid peak")
        passCount += 1
        print(String(format: "PASS headroom drop caps inferred peak immediately: %.3f -> %.3f",
                     highState.inferredPeak, dropped.inferredPeak))
    }

    private func validateFrameRateIndependentSmoothing() throws {
        // Keep p50 fixed at midgray so the GPU path exercises temporal
        // smoothing rather than the intentional >2-stop median reset.
        let sparse = try makeQuarterCellScene(
            cellsWide: 8,
            cellsHigh: 8,
            whiteCells: Set(0..<4)
        )
        let broadish = try makeQuarterCellScene(
            cellsWide: 8,
            cellsHigh: 8,
            whiteCells: Set(0..<19)
        )
        let highTarget = try gpu.analyzeHistogram(scene: sparse, currentHeadroom: 4).state
        let lowTarget = try gpu.analyzeHistogram(scene: broadish, currentHeadroom: 4).state
        try require(abs(highTarget.medianLog2 - lowTarget.medianLog2) < 0.001,
                    "GPU smoothing fixtures changed the median")
        try require(highTarget.inferredPeak > lowTarget.inferredPeak,
                    "GPU smoothing fixtures do not produce rise/fall targets")

        let oneFrame: Float = 1.0 / 60.0
        let gpuRise = try gpu.analyzeHistogram(
            scene: sparse,
            currentHeadroom: 4,
            deltaTime: oneFrame,
            forceReset: false,
            previousState: lowTarget
        ).state.inferredPeak
        let expectedGpuRise = temporalSequence(
            initial: lowTarget.inferredPeak,
            target: highTarget.inferredPeak,
            frames: 1,
            deltaTime: oneFrame
        )
        let gpuFall = try gpu.analyzeHistogram(
            scene: broadish,
            currentHeadroom: 4,
            deltaTime: oneFrame,
            forceReset: false,
            previousState: highTarget
        ).state.inferredPeak
        let expectedGpuFall = temporalSequence(
            initial: highTarget.inferredPeak,
            target: lowTarget.inferredPeak,
            frames: 1,
            deltaTime: oneFrame
        )
        try require(abs(gpuRise - expectedGpuRise) < 0.0002,
                    "GPU rise does not use tau=0.75: got \(gpuRise), expected \(expectedGpuRise)")
        try require(abs(gpuFall - expectedGpuFall) < 0.0002,
                    "GPU fall does not use tau=0.12: got \(gpuFall), expected \(expectedGpuFall)")
        let stalledReset = try gpu.analyzeHistogram(
            scene: broadish,
            currentHeadroom: 4,
            deltaTime: 1.01,
            forceReset: false,
            previousState: highTarget
        ).state.inferredPeak
        try require(abs(stalledReset - lowTarget.inferredPeak) < 0.0002,
                    "dt>1 second did not reset temporal state")

        let rise30 = temporalSequence(initial: 1.0, target: 2.8, frames: 30, deltaTime: 1.0 / 30.0)
        let rise120 = temporalSequence(initial: 1.0, target: 2.8, frames: 120, deltaTime: 1.0 / 120.0)
        let fall30 = temporalSequence(initial: 2.8, target: 1.2, frames: 30, deltaTime: 1.0 / 30.0)
        let fall120 = temporalSequence(initial: 2.8, target: 1.2, frames: 120, deltaTime: 1.0 / 120.0)
        let expectedRise = Float(2.8 + (1.0 - 2.8) * exp(-1.0 / 0.75))
        let expectedFall = Float(1.2 + (2.8 - 1.2) * exp(-1.0 / 0.12))
        try require(abs(rise30 - rise120) < 0.0001 && abs(rise30 - expectedRise) < 0.0001,
                    "Rise smoothing depends on frame rate: 30=\(rise30), 120=\(rise120)")
        try require(abs(fall30 - fall120) < 0.0001 && abs(fall30 - expectedFall) < 0.0001,
                    "Fall smoothing depends on frame rate: 30=\(fall30), 120=\(fall120)")
        passCount += 1
        print(String(format: "PASS GPU tau rise/fall and frame-rate-independent smoothing: %.4f / %.4f",
                     gpuRise, gpuFall))
    }

    private func makeQuarterCellScene(
        cellsWide: Int,
        cellsHigh: Int,
        whiteCells: Set<Int>
    ) throws -> MTLTexture {
        let width = cellsWide * 4
        let height = cellsHigh * 4
        var pixels = [SIMD4<UInt8>]()
        pixels.reserveCapacity(width * height)
        for y in 0..<height {
            for x in 0..<width {
                let cell = (y / 4) * cellsWide + x / 4
                let value: UInt8 = whiteCells.contains(cell) ? 255 : 128
                pixels.append(SIMD4<UInt8>(value, value, value, 255))
            }
        }
        return try gpu.makeRgba8Texture(width: width, height: height, pixels: pixels)
    }

    private func temporalSequence(
        initial: Float,
        target: Float,
        frames: Int,
        deltaTime: Float
    ) -> Float {
        var value = initial
        for _ in 0..<frames {
            let timeConstant: Float = target > value ? 0.75 : 0.12
            let blend = Float(1.0 - exp(-Double(deltaTime / timeConstant)))
            value += (target - value) * blend
        }
        return value
    }

    private func validateNativeBackdropAndPresentAbi() throws {
        guard let handle = dlopen(nativeLibraryPath, RTLD_NOW | RTLD_LOCAL) else {
            let detail = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
            throw ValidationFailure.message("Could not load native library: \(detail)")
        }
        guard
            let initSymbol = dlsym(handle, "metallum_init_pipelines"),
            let configureLayerSymbol = dlsym(handle, "metallum_configure_layer"),
            let updateLayerHeadroomSymbol = dlsym(handle, "metallum_update_layer_contents_headroom"),
            let backdropSymbol = dlsym(handle, "metallum_MTLCommandBuffer_encodeHdrUiBackdrop"),
            let presentSymbol = dlsym(handle, "metallum_MTLCommandBuffer_encodePresentTextureToDrawable")
        else {
            throw ValidationFailure.message("Native HDR present symbols are missing")
        }
        let initialize = unsafeBitCast(initSymbol, to: NativeInitFunction.self)
        let configureLayer = unsafeBitCast(configureLayerSymbol, to: NativeConfigureLayerFunction.self)
        let updateLayerHeadroom = unsafeBitCast(
            updateLayerHeadroomSymbol,
            to: NativeUpdateLayerContentsHeadroomFunction.self
        )
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
        layer.displaySyncEnabled = false
        view.wantsLayer = true
        view.layer = layer
        window.contentView = view
        window.orderFrontRegardless()
        defer { window.orderOut(nil) }
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.05))

        let sdrConfigureStatus = configureLayer(objectPointer(layer), 16, 16, 1, 0, 1)
        try require(sdrConfigureStatus == 1, "SDR layer configuration returned \(sdrConfigureStatus)")
        try require(layer.pixelFormat == .bgra8Unorm, "SDR layer did not use BGRA8Unorm")
        try require(
            colorSpacesEqual(layer.colorspace, CGColorSpace(name: CGColorSpace.sRGB)),
            "SDR layer did not declare the sRGB color space"
        )
        if #available(macOS 26.0, *) {
            try require(abs(layer.contentsHeadroom - 1.0) < 0.0001,
                        "SDR layer retained HDR contents headroom: \(layer.contentsHeadroom)")
        }

        let hdrConfigureStatus = configureLayer(objectPointer(layer), 16, 16, 1, 2, 4)
        try require(hdrConfigureStatus == 1, "HDR layer reconfiguration returned \(hdrConfigureStatus)")
        try require(layer.pixelFormat == .rgba16Float, "HDR layer did not restore RGBA16Float")
        try require(
            colorSpacesEqual(layer.colorspace, CGColorSpace(name: CGColorSpace.extendedLinearSRGB)),
            "HDR layer did not restore extended-linear sRGB"
        )
        passCount += 1
        print("PASS native layer switches between explicit sRGB SDR and extended-linear HDR")

        if #available(macOS 26.0, *) {
            layer.contentsHeadroom = 1.0
        }
        let originalPixelFormat = layer.pixelFormat
        let originalColorSpace = layer.colorspace
        let originalDrawableSize = layer.drawableSize
        let originalDisplaySyncEnabled = layer.displaySyncEnabled
        let updateStatus = updateLayerHeadroom(objectPointer(layer), 2.5)
        try require(updateStatus == 1, "Layer contents-headroom ABI returned \(updateStatus)")
        if #available(macOS 26.0, *) {
            try require(
                abs(layer.contentsHeadroom - 2.5) < 0.0001,
                "Layer contents headroom was not updated: \(layer.contentsHeadroom)"
            )
        }
        try require(layer.pixelFormat == originalPixelFormat, "Layer headroom update changed pixel format")
        try require(
            colorSpacesEqual(layer.colorspace, originalColorSpace),
            "Layer headroom update changed color space"
        )
        try require(layer.drawableSize == originalDrawableSize, "Layer headroom update changed drawable size")
        try require(
            layer.displaySyncEnabled == originalDisplaySyncEnabled,
            "Layer headroom update changed display sync"
        )
        passCount += 1
        print("PASS native layer contents-headroom ABI changes only the EDR declaration")

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

        guard let noSceneCommandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("No-scene ABI smoke command buffer creation failed")
        }
        let noSceneStatus = present(
            objectPointer(noSceneCommandBuffer as AnyObject),
            objectPointer(layer),
            objectPointer(source as AnyObject),
            nil,
            nil,
            nil,
            objectPointer(ui as AnyObject),
            nil,
            1,
            0,
            0,
            4,
            1,
            0
        )
        try require(noSceneStatus == 1, "No-scene present ABI returned \(noSceneStatus)")
        noSceneCommandBuffer.commit()
        noSceneCommandBuffer.waitUntilCompleted()
        let noSceneDetail = noSceneCommandBuffer.error.map(String.init(describing:)) ?? "unknown GPU error"
        try require(noSceneCommandBuffer.status == .completed,
                    "No-scene present command failed: \(noSceneDetail)")
        passCount += 1
        print("PASS native present ABI accepts uiTexture and binds adaptive fallback without a scene")
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
