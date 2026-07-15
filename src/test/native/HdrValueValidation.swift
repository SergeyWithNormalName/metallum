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

private struct HdrBlurUniforms {
    var texelStep: SIMD2<Float>
    var _padding0: SIMD2<Float>
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

private struct HeadroomLimiterCase {
    var baseAndHeadroom: SIMD4<Float>
    var delta: SIMD4<Float>
}

private typealias NativeInitFunction = @convention(c) (UnsafeRawPointer?) -> Int32
private typealias NativeSetFrameStateFunction = @convention(c) (
    UnsafeRawPointer?,
    UInt64
) -> Int32
private typealias NativeGenerationContractFunction = @convention(c) (
    UnsafeRawPointer?
) -> UInt64
private typealias NativeCreateEdrMonitorFunction = @convention(c) (
    UnsafeRawPointer? // window
) -> UnsafeMutableRawPointer?
private typealias NativeEdrMonitorQueryFunction = @convention(c) (
    UnsafeMutableRawPointer? // monitor
) -> UInt64
private typealias NativeReleaseFunction = @convention(c) (UnsafeMutableRawPointer?) -> Void
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
    UnsafeRawPointer?, // sceneDepthTexture
    UnsafeRawPointer?, // semanticTexture
    UnsafeRawPointer?, // globalFence
    Int32,             // sourceEncoding
    Int32,             // materialGenerationActive
    Int32,             // spatialScalingEnabled
    Int32,             // hdrPrecomposeEnabled
    Int32,             // perceptualScalingEnabled
    Int32,             // deferSpatialHdrUiSeed
    Float,             // currentHeadroom
    Float,             // hdrStrength
    Float              // bloomStrength
) -> Int32
private typealias NativeFusedBackdropFunction = @convention(c) (
    UnsafeRawPointer?, // commandBuffer
    UnsafeRawPointer?, // renderCommandEncoder
    UnsafeRawPointer?, // sourceTexture
    UnsafeRawPointer?, // destinationTexture
    UInt,              // depthFormat
    UInt               // stencilFormat
) -> Int32
private typealias NativeMaterializePreparedBackdropFunction = @convention(c) (
    UnsafeRawPointer?, // commandBuffer
    UnsafeRawPointer?, // sourceTexture
    UnsafeRawPointer?, // destinationTexture
    UnsafeRawPointer?  // globalFence
) -> Int32
private typealias NativeCoherentMenuBlurFunction = @convention(c) (
    UnsafeRawPointer?, // commandBuffer
    UnsafeRawPointer?, // sourceTexture
    UnsafeRawPointer?, // uiTexture
    UnsafeRawPointer?, // globalFence
    Float,             // radius
    Float              // currentHeadroom
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
    Int32,             // spatialHdrPrecomposed
    Int32,             // outputMode
    Int32,             // sourceEncoding
    Int32,             // materialGenerationActive
    Int32,             // diagnosticPattern
    Float,             // currentHeadroom
    Float,             // hdrStrength
    Float              // bloomStrength
) -> Int32
private typealias NativeSpatialScreenshotFunction = @convention(c) (
    UnsafeRawPointer?, // commandBuffer
    UnsafeRawPointer?, // rawSceneTexture
    UnsafeRawPointer?, // uiTexture
    UnsafeRawPointer?, // destinationTexture
    UnsafeRawPointer?, // globalFence
    Int32,             // sourceEncoding
    Float              // currentHeadroom
) -> Int32

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else {
        throw ValidationFailure.message(message)
    }
}

private func objectPointer(_ object: AnyObject) -> UnsafeRawPointer {
    UnsafeRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

private func writeFrameUInt32(_ value: UInt32, at offset: Int, into bytes: inout [UInt8]) {
    var little = value.littleEndian
    Swift.withUnsafeBytes(of: &little) { source in
        bytes.replaceSubrange(offset..<(offset + source.count), with: source)
    }
}

private func writeFrameUInt64(_ value: UInt64, at offset: Int, into bytes: inout [UInt8]) {
    var little = value.littleEndian
    Swift.withUnsafeBytes(of: &little) { source in
        bytes.replaceSubrange(offset..<(offset + source.count), with: source)
    }
}

private func writeFrameFloat(_ value: Float, at offset: Int, into bytes: inout [UInt8]) {
    writeFrameUInt32(value.bitPattern, at: offset, into: &bytes)
}

private func writeFrameDouble(_ value: Double, at offset: Int, into bytes: inout [UInt8]) {
    writeFrameUInt64(value.bitPattern, at: offset, into: &bytes)
}

private func rendererGenerationPacket(
    generation: UInt64,
    lightingMode: UInt32,
    outputMode: UInt32
) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 816)
    writeFrameUInt32(2, at: 0, into: &bytes)
    writeFrameUInt32(816, at: 4, into: &bytes)
    writeFrameUInt32(1, at: 8, into: &bytes)
    writeFrameUInt32(2, at: 12, into: &bytes)
    writeFrameUInt64(42, at: 16, into: &bytes)
    writeFrameUInt64(7, at: 24, into: &bytes)
    writeFrameUInt64(generation, at: 32, into: &bytes)
    writeFrameUInt64(9, at: 40, into: &bytes)
    writeFrameUInt64(generation, at: 48, into: &bytes)
    writeFrameUInt64(generation, at: 56, into: &bytes)
    writeFrameUInt64(12, at: 64, into: &bytes)
    writeFrameUInt64(13, at: 72, into: &bytes)
    writeFrameUInt64(1, at: 80, into: &bytes)
    writeFrameUInt32(lightingMode, at: 96, into: &bytes)
    writeFrameUInt32(outputMode, at: 100, into: &bytes)
    writeFrameUInt32(0, at: 104, into: &bytes)
    writeFrameUInt32(1, at: 108, into: &bytes)
    writeFrameUInt32(16, at: 112, into: &bytes)
    writeFrameUInt32(16, at: 116, into: &bytes)
    writeFrameUInt32(16, at: 120, into: &bytes)
    writeFrameUInt32(16, at: 124, into: &bytes)
    writeFrameUInt32(1, at: 128, into: &bytes)
    writeFrameFloat(1.0 / 60.0, at: 136, into: &bytes)
    writeFrameFloat(0.05, at: 140, into: &bytes)
    writeFrameFloat(1024, at: 144, into: &bytes)
    writeFrameFloat(1, at: 156, into: &bytes)
    writeFrameFloat(1, at: 160, into: &bytes)
    writeFrameFloat(1, at: 164, into: &bytes)
    writeFrameFloat(1, at: 168, into: &bytes)
    writeFrameUInt64(outputMode == 0 ? 0 : 64, at: 184, into: &bytes)
    for index in 0..<6 {
        writeFrameDouble(Double(index), at: 248 + index * 8, into: &bytes)
    }
    for matrix in 0..<8 {
        for diagonal in 0..<4 {
            writeFrameFloat(1, at: 296 + matrix * 64 + diagonal * 20, into: &bytes)
        }
    }
    return bytes
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

private func legacyBlurMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct LegacyBlurVertexOut {
      float4 position [[position]];
      float2 uv;
    };

    struct LegacyBlurUniforms {
      float2 texelStep;
      float2 _padding0;
    };

    vertex LegacyBlurVertexOut metallum_legacy_blur_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };
      const float2 uvs[3] = {
        float2(0.0,  1.0),
        float2(2.0,  1.0),
        float2(0.0, -1.0)
      };
      LegacyBlurVertexOut out;
      out.position = float4(positions[vertexId], 0.0, 1.0);
      out.uv = uvs[vertexId];
      return out;
    }

    fragment float4 metallum_legacy_blur_fs(
      LegacyBlurVertexOut in [[stage_in]],
      texture2d<float> source [[texture(0)]],
      sampler smp [[sampler(0)]],
      constant LegacyBlurUniforms& uniforms [[buffer(0)]]
    ) {
      float4 result = source.sample(smp, in.uv) * 0.2270270270;
      result += source.sample(smp, in.uv + uniforms.texelStep * 1.0) * 0.1945945946;
      result += source.sample(smp, in.uv - uniforms.texelStep * 1.0) * 0.1945945946;
      result += source.sample(smp, in.uv + uniforms.texelStep * 2.0) * 0.1216216216;
      result += source.sample(smp, in.uv - uniforms.texelStep * 2.0) * 0.1216216216;
      result += source.sample(smp, in.uv + uniforms.texelStep * 3.0) * 0.0540540541;
      result += source.sample(smp, in.uv - uniforms.texelStep * 3.0) * 0.0540540541;
      result += source.sample(smp, in.uv + uniforms.texelStep * 4.0) * 0.0162162162;
      result += source.sample(smp, in.uv - uniforms.texelStep * 4.0) * 0.0162162162;
      return result;
    }
    """
}

private func headroomLimiterTestMslSuffix() -> String {
    """

    struct MetallumHeadroomLimiterTestCase {
      float4 baseAndHeadroom;
      float4 delta;
    };

    float metallum_test_legacy_visible_delta_scale(
      float3 mappedBaseColor,
      float3 visibleDelta,
      float currentHeadroom
    ) {
      if (metallum_peak_metric(mappedBaseColor + visibleDelta) <= currentHeadroom) {
        return 1.0;
      }
      float low = 0.0;
      float high = 1.0;
      for (uint iteration = 0u; iteration < 7u; ++iteration) {
        float candidate = 0.5 * (low + high);
        if (metallum_peak_metric(mappedBaseColor + visibleDelta * candidate) <= currentHeadroom) {
          low = candidate;
        } else {
          high = candidate;
        }
      }
      return low;
    }

    kernel void metallum_test_headroom_limiter(
      device const MetallumHeadroomLimiterTestCase* cases [[buffer(0)]],
      device float2* results [[buffer(1)]],
      uint index [[thread_position_in_grid]]
    ) {
      MetallumHeadroomLimiterTestCase value = cases[index];
      results[index] = float2(
        metallum_visible_delta_scale(
          value.baseAndHeadroom.xyz,
          value.delta.xyz,
          value.baseAndHeadroom.w
        ),
        metallum_test_legacy_visible_delta_scale(
          value.baseAndHeadroom.xyz,
          value.delta.xyz,
          value.baseAndHeadroom.w
        )
      );
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
    private let actualWorldPipeline: MTLRenderPipelineState
    private let actualNativeWorldUiPipeline: MTLRenderPipelineState
    private let actualUiOnlyPipeline: MTLRenderPipelineState
    private let actualLinearUiOnlyPipeline: MTLRenderPipelineState
    private let worldPresentPipeline: MTLRenderPipelineState
    private let spatialWorldPipeline: MTLRenderPipelineState
    private let spatialPresentPipeline: MTLRenderPipelineState
    private let extractPipeline: MTLRenderPipelineState
    private let actualExtractPipeline: MTLRenderPipelineState
    private let blurPipeline: MTLComputePipelineState
    private let legacyBlurPipeline: MTLRenderPipelineState
    private let uiComparePipeline: MTLRenderPipelineState
    private let uiDilatePipeline: MTLRenderPipelineState
    private let boundaryBlendPipeline: MTLRenderPipelineState
    private let uiAlphaBlendPipeline: MTLRenderPipelineState
    private let uiAlphaBlendDepthPipeline: MTLRenderPipelineState
    private let histogramReducePipeline: MTLComputePipelineState
    private let actualExposureReducePipeline: MTLComputePipelineState
    private let headroomLimiterTestPipeline: MTLComputePipelineState
    private let nearestSampler: MTLSamplerState
    private let linearSampler: MTLSamplerState

    init(device: MTLDevice, shaderSourceDirectory: String) throws {
        self.device = device
        guard let queue = device.makeCommandQueue() else {
            throw ValidationFailure.message("Metal command queue creation failed")
        }
        self.queue = queue

        let shaderDirectory = URL(fileURLWithPath: shaderSourceDirectory, isDirectory: true)
        let presentMsl = try String(
            contentsOf: shaderDirectory.appendingPathComponent("MetallumPresent.metal"),
            encoding: .utf8
        )
        let effectsMsl = try String(
            contentsOf: shaderDirectory.appendingPathComponent("MetallumHdrEffects.metal"),
            encoding: .utf8
        )
        let presentLibrary = try device.makeLibrary(
            source: presentMsl + "\n" + headroomLimiterTestMslSuffix(),
            options: nil
        )
        let effectsLibrary = try device.makeLibrary(source: effectsMsl, options: nil)

        guard
            let presentVertex = presentLibrary.makeFunction(name: "metallum_present_vs"),
            let offscreenVertex = presentLibrary.makeFunction(name: "metallum_offscreen_vs"),
            let presentFragment = presentLibrary.makeFunction(name: "metallum_present_fs"),
            let actualWorldFragment = presentLibrary.makeFunction(name: "metallum_actual_spatial_world_fs"),
            let actualNativeWorldUiFragment = presentLibrary.makeFunction(
                name: "metallum_actual_native_world_ui_fs"
            ),
            let actualUiOnlyFragment = presentLibrary.makeFunction(name: "metallum_actual_hdr_ui_only_fs"),
            let actualLinearUiOnlyFragment = presentLibrary.makeFunction(name: "metallum_actual_hdr_linear_ui_only_fs"),
            let spatialWorldFragment = presentLibrary.makeFunction(name: "metallum_spatial_world_fs"),
            let spatialPresentFragment = presentLibrary.makeFunction(name: "metallum_spatial_present_fs"),
            let headroomLimiterTest = presentLibrary.makeFunction(name: "metallum_test_headroom_limiter")
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
        let actualWorldDescriptor = MTLRenderPipelineDescriptor()
        actualWorldDescriptor.label = "Metallum actual-radiance HDR validation"
        actualWorldDescriptor.vertexFunction = offscreenVertex
        actualWorldDescriptor.fragmentFunction = actualWorldFragment
        actualWorldDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        actualWorldDescriptor.colorAttachments[0].isBlendingEnabled = false
        self.actualWorldPipeline = try device.makeRenderPipelineState(
            descriptor: actualWorldDescriptor
        )
        let actualNativeWorldUiDescriptor = MTLRenderPipelineDescriptor()
        actualNativeWorldUiDescriptor.label = "Metallum actual HDR/UI seed validation"
        actualNativeWorldUiDescriptor.vertexFunction = offscreenVertex
        actualNativeWorldUiDescriptor.fragmentFunction = actualNativeWorldUiFragment
        actualNativeWorldUiDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        actualNativeWorldUiDescriptor.colorAttachments[0].isBlendingEnabled = false
        actualNativeWorldUiDescriptor.colorAttachments[1].pixelFormat = .rgba8Unorm
        actualNativeWorldUiDescriptor.colorAttachments[1].isBlendingEnabled = false
        self.actualNativeWorldUiPipeline = try device.makeRenderPipelineState(
            descriptor: actualNativeWorldUiDescriptor
        )
        let actualUiOnlyDescriptor = MTLRenderPipelineDescriptor()
        actualUiOnlyDescriptor.label = "Metallum actual-HDR UI-only validation"
        actualUiOnlyDescriptor.vertexFunction = presentVertex
        actualUiOnlyDescriptor.fragmentFunction = actualUiOnlyFragment
        actualUiOnlyDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        actualUiOnlyDescriptor.colorAttachments[0].isBlendingEnabled = false
        self.actualUiOnlyPipeline = try device.makeRenderPipelineState(
            descriptor: actualUiOnlyDescriptor
        )
        actualUiOnlyDescriptor.label = "Metallum actual-HDR linear UI-only validation"
        actualUiOnlyDescriptor.fragmentFunction = actualLinearUiOnlyFragment
        self.actualLinearUiOnlyPipeline = try device.makeRenderPipelineState(
            descriptor: actualUiOnlyDescriptor
        )
        let worldPresentDescriptor = MTLRenderPipelineDescriptor()
        worldPresentDescriptor.label = "Metallum spatial HDR world validation present"
        worldPresentDescriptor.vertexFunction = offscreenVertex
        worldPresentDescriptor.fragmentFunction = presentFragment
        worldPresentDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        worldPresentDescriptor.colorAttachments[0].isBlendingEnabled = false
        self.worldPresentPipeline = try device.makeRenderPipelineState(
            descriptor: worldPresentDescriptor
        )
        let spatialWorldDescriptor = MTLRenderPipelineDescriptor()
        spatialWorldDescriptor.label = "Metallum specialized spatial HDR world validation"
        spatialWorldDescriptor.vertexFunction = offscreenVertex
        spatialWorldDescriptor.fragmentFunction = spatialWorldFragment
        spatialWorldDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        spatialWorldDescriptor.colorAttachments[0].isBlendingEnabled = false
        self.spatialWorldPipeline = try device.makeRenderPipelineState(
            descriptor: spatialWorldDescriptor
        )
        let spatialPresentDescriptor = MTLRenderPipelineDescriptor()
        spatialPresentDescriptor.label = "Metallum spatial HDR value validation present"
        spatialPresentDescriptor.vertexFunction = presentVertex
        spatialPresentDescriptor.fragmentFunction = spatialPresentFragment
        spatialPresentDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        spatialPresentDescriptor.colorAttachments[0].isBlendingEnabled = false
        self.spatialPresentPipeline = try device.makeRenderPipelineState(
            descriptor: spatialPresentDescriptor
        )
        self.headroomLimiterTestPipeline = try device.makeComputePipelineState(
            function: headroomLimiterTest
        )

        guard
            let effectsVertex = effectsLibrary.makeFunction(name: "metallum_hdr_vs"),
            let extractFragment = effectsLibrary.makeFunction(name: "metallum_hdr_extract_fs"),
            let actualExtractFragment = effectsLibrary.makeFunction(name: "metallum_actual_hdr_extract_fs"),
            let histogramReduce = effectsLibrary.makeFunction(name: "metallum_hdr_histogram_reduce"),
            let actualExposureReduce = effectsLibrary.makeFunction(name: "metallum_actual_hdr_exposure_reduce"),
            let blurFunction = effectsLibrary.makeFunction(name: "metallum_hdr_blur"),
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
        extractDescriptor.label = "Metallum actual-radiance HDR extract validation"
        extractDescriptor.fragmentFunction = actualExtractFragment
        self.actualExtractPipeline = try device.makeRenderPipelineState(descriptor: extractDescriptor)
        self.histogramReducePipeline = try device.makeComputePipelineState(function: histogramReduce)
        self.actualExposureReducePipeline = try device.makeComputePipelineState(
            function: actualExposureReduce
        )

        self.blurPipeline = try device.makeComputePipelineState(function: blurFunction)

        let legacyBlurLibrary = try device.makeLibrary(source: legacyBlurMslSource(), options: nil)
        guard
            let legacyBlurVertex = legacyBlurLibrary.makeFunction(name: "metallum_legacy_blur_vs"),
            let legacyBlurFragment = legacyBlurLibrary.makeFunction(name: "metallum_legacy_blur_fs")
        else {
            throw ValidationFailure.message("Legacy HDR blur shader functions are missing")
        }
        let legacyBlurDescriptor = MTLRenderPipelineDescriptor()
        legacyBlurDescriptor.label = "Metallum legacy HDR blur reference"
        legacyBlurDescriptor.vertexFunction = legacyBlurVertex
        legacyBlurDescriptor.fragmentFunction = legacyBlurFragment
        legacyBlurDescriptor.colorAttachments[0].pixelFormat = .rgba16Float
        legacyBlurDescriptor.colorAttachments[0].isBlendingEnabled = false
        self.legacyBlurPipeline = try device.makeRenderPipelineState(descriptor: legacyBlurDescriptor)

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

        let uiAlphaBlendDescriptor = MTLRenderPipelineDescriptor()
        uiAlphaBlendDescriptor.label = "Metallum fused UI seed alpha blend validation"
        uiAlphaBlendDescriptor.vertexFunction = boundaryVertex
        uiAlphaBlendDescriptor.fragmentFunction = boundaryFragment
        let uiAlphaAttachment = uiAlphaBlendDescriptor.colorAttachments[0]!
        uiAlphaAttachment.pixelFormat = .rgba8Unorm
        uiAlphaAttachment.isBlendingEnabled = true
        uiAlphaAttachment.sourceRGBBlendFactor = .sourceAlpha
        uiAlphaAttachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
        uiAlphaAttachment.sourceAlphaBlendFactor = .one
        uiAlphaAttachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
        self.uiAlphaBlendPipeline = try device.makeRenderPipelineState(
            descriptor: uiAlphaBlendDescriptor
        )
        uiAlphaBlendDescriptor.label = "Metallum fused UI seed depth alpha blend validation"
        uiAlphaBlendDescriptor.depthAttachmentPixelFormat = .depth32Float
        self.uiAlphaBlendDepthPipeline = try device.makeRenderPipelineState(
            descriptor: uiAlphaBlendDescriptor
        )

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

    func makeRgba16FloatTexture(
        width: Int,
        height: Int,
        pixels: [SIMD4<Float>]
    ) throws -> MTLTexture {
        try require(pixels.count == width * height, "RGBA16Float pixel count does not match texture size")
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .shared
        descriptor.usage = [.shaderRead, .renderTarget]
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("RGBA16Float patterned texture creation failed")
        }

        var values = [UInt16]()
        values.reserveCapacity(pixels.count * 4)
        for pixel in pixels {
            values.append(Float16(pixel.x).bitPattern)
            values.append(Float16(pixel.y).bitPattern)
            values.append(Float16(pixel.z).bitPattern)
            values.append(Float16(pixel.w).bitPattern)
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

    func readRgba8Bytes(texture: MTLTexture) throws -> [UInt8] {
        try require(texture.pixelFormat == .rgba8Unorm, "RGBA8 byte readback requires RGBA8Unorm")
        var bytes = [UInt8](repeating: 0, count: texture.width * texture.height * 4)
        bytes.withUnsafeMutableBytes { rawBytes in
            texture.getBytes(
                rawBytes.baseAddress!,
                bytesPerRow: texture.width * 4,
                from: MTLRegionMake2D(0, 0, texture.width, texture.height),
                mipmapLevel: 0
            )
        }
        return bytes
    }

    func makePrivateRgba8Texture(width: Int, height: Int) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.shaderRead, .renderTarget]
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationFailure.message("Private RGBA8 texture creation failed")
        }
        return texture
    }

    func clearPrivateRgba8(_ texture: MTLTexture, color: MTLClearColor) throws {
        try require(texture.pixelFormat == .rgba8Unorm, "Private RGBA8 clear requires RGBA8Unorm")
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Private RGBA8 clear command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = color
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Private RGBA8 clear encoder creation failed")
        }
        encoder.endEncoding()
        try complete(commandBuffer, label: "RGBA8 private sentinel clear")
    }

    func readPrivateRgba8(texture: MTLTexture, x: Int = 0, y: Int = 0) throws -> SIMD4<UInt8> {
        try require(texture.pixelFormat == .rgba8Unorm, "Private readback requires RGBA8Unorm")
        let bytesPerRow = ((texture.width * 4 + 255) / 256) * 256
        let byteCount = bytesPerRow * texture.height
        guard let buffer = device.makeBuffer(length: byteCount, options: .storageModeShared),
              let commandBuffer = queue.makeCommandBuffer(),
              let encoder = commandBuffer.makeBlitCommandEncoder() else {
            throw ValidationFailure.message("Private RGBA8 readback resource creation failed")
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
        try complete(commandBuffer, label: "RGBA8 private readback")
        let offset = y * bytesPerRow + x * 4
        let bytes = buffer.contents().advanced(by: offset).assumingMemoryBound(to: UInt8.self)
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

    func renderLegacyBlur(
        source: MTLTexture,
        texelStep: SIMD2<Float>
    ) throws -> MTLTexture {
        let output = try makePrivateRgba16FloatTexture(width: source.width, height: source.height)
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("HDR blur command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("HDR blur encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(source.width),
            height: Double(source.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(legacyBlurPipeline)
        encoder.setFragmentTexture(source, index: 0)
        encoder.setFragmentSamplerState(linearSampler, index: 0)
        var uniforms = HdrBlurUniforms(
            texelStep: texelStep,
            _padding0: SIMD2<Float>(repeating: 0)
        )
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<HdrBlurUniforms>.stride, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "HDR Gaussian blur")
        return output
    }

    func renderCombinedBlur(source: MTLTexture) throws -> MTLTexture {
        let output = try makePrivateRgba16FloatTexture(
            width: source.width,
            height: source.height,
            usage: [.shaderRead, .shaderWrite]
        )
        guard blurPipeline.maxTotalThreadsPerThreadgroup >= 16 * 16,
              device.maxThreadgroupMemoryLength >= (24 * 24 + 16 * 24) * 4 * MemoryLayout<Float16>.stride,
              let commandBuffer = queue.makeCommandBuffer(),
              let encoder = commandBuffer.makeComputeCommandEncoder() else {
            throw ValidationFailure.message("Combined HDR blur command buffer creation failed")
        }
        encoder.setComputePipelineState(blurPipeline)
        encoder.setTexture(source, index: 0)
        encoder.setTexture(output, index: 1)
        encoder.setThreadgroupMemoryLength(
            24 * 24 * 4 * MemoryLayout<Float16>.stride,
            index: 0
        )
        encoder.setThreadgroupMemoryLength(
            16 * 24 * 4 * MemoryLayout<Float16>.stride,
            index: 1
        )
        encoder.dispatchThreadgroups(
            MTLSize(
                width: (source.width + 15) / 16,
                height: (source.height + 15) / 16,
                depth: 1
            ),
            threadsPerThreadgroup: MTLSize(width: 16, height: 16, depth: 1)
        )
        encoder.endEncoding()
        try complete(commandBuffer, label: "combined HDR Gaussian blur")
        return output
    }

    func analyzeActualRadiance(
        scene: MTLTexture,
        currentHeadroom: Float
    ) throws -> (extract: MTLTexture, state: HdrAdaptiveState) {
        let outputWidth = max((scene.width + 3) / 4, 1)
        let outputHeight = max((scene.height + 3) / 4, 1)
        let output = try makePrivateRgba16FloatTexture(width: outputWidth, height: outputHeight)
        var initialState = HdrAdaptiveState(
            breakpoint: 1.0,
            inferredPeak: 1.0,
            medianLog2: -12.0,
            p90Log2: -12.0,
            p99Log2: -12.0,
            brightCoverage: 0.0,
            currentHeadroom: 1.0,
            valid: 0
        )
        guard let histogram = device.makeBuffer(
                length: 64 * MemoryLayout<UInt32>.stride,
                options: .storageModeShared
              ), let stateBuffer = withUnsafeBytes(of: &initialState, { bytes in
                device.makeBuffer(
                    bytes: bytes.baseAddress!,
                    length: bytes.count,
                    options: .storageModeShared
                )
              }), let commandBuffer = queue.makeCommandBuffer(),
              let clear = commandBuffer.makeBlitCommandEncoder() else {
            throw ValidationFailure.message("Actual HDR analysis resource creation failed")
        }
        clear.fill(buffer: histogram, range: 0..<histogram.length, value: 0)
        clear.endEncoding()

        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let extract = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Actual HDR extract encoder creation failed")
        }
        extract.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(outputWidth),
            height: Double(outputHeight),
            znear: 0,
            zfar: 1
        ))
        extract.setRenderPipelineState(actualExtractPipeline)
        // The actual-radiance function exposes no semantic/depth bindings.
        extract.setFragmentTexture(scene, index: 0)
        extract.setFragmentBuffer(histogram, offset: 0, index: 1)
        var extractUniforms = HdrExtractUniforms(
            sourceEncoding: 2,
            semanticAvailable: 0,
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
            throw ValidationFailure.message("Actual HDR exposure encoder creation failed")
        }
        reduce.setComputePipelineState(actualExposureReducePipeline)
        reduce.setBuffer(histogram, offset: 0, index: 0)
        reduce.setBuffer(stateBuffer, offset: 0, index: 1)
        var reduceUniforms = HdrHistogramReduceUniforms(
            currentHeadroom: currentHeadroom,
            deltaTime: 0,
            forceReset: 1,
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
        try complete(commandBuffer, label: "actual HDR extract/exposure")
        let state = stateBuffer.contents().assumingMemoryBound(to: HdrAdaptiveState.self).pointee
        return (output, state)
    }

    func renderActualWorld(
        scene: MTLTexture,
        bloom: MTLTexture,
        state: HdrAdaptiveState,
        headroom: Float,
        legacyReconstructionStrength: Float
    ) throws -> MTLTexture {
        let output = try makePrivateRgba16FloatTexture(width: scene.width, height: scene.height)
        var mutableState = state
        guard let stateBuffer = withUnsafeBytes(of: &mutableState, { bytes in
                device.makeBuffer(
                    bytes: bytes.baseAddress!,
                    length: bytes.count,
                    options: .storageModeShared
                )
              }), let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Actual HDR world resource creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Actual HDR world encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(scene.width),
            height: Double(scene.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(actualWorldPipeline)
        encoder.setFragmentTexture(scene, index: 0)
        encoder.setFragmentTexture(bloom, index: 1)
        encoder.setFragmentSamplerState(nearestSampler, index: 0)
        encoder.setFragmentSamplerState(linearSampler, index: 1)
        var uniforms = PresentUniforms(
            mode: 2,
            sourceEncoding: 2,
            diagnosticPattern: 0,
            currentHeadroom: headroom,
            hdrStrength: legacyReconstructionStrength,
            bloomStrength: 0.22,
            sceneAvailable: 1,
            uiAvailable: 0,
            semanticAvailable: 0
        )
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<PresentUniforms>.stride, index: 0)
        encoder.setFragmentBuffer(stateBuffer, offset: 0, index: 1)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "actual HDR world mapping")
        return output
    }

    func renderActualHdrUiOnly(ui: MTLTexture) throws -> MTLTexture {
        let output = try makePrivateRgba16FloatTexture(width: ui.width, height: ui.height)
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Actual HDR UI-only command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Actual HDR UI-only encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(ui.width),
            height: Double(ui.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(actualUiOnlyPipeline)
        encoder.setFragmentTexture(ui, index: 0)
        encoder.setFragmentSamplerState(nearestSampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "actual HDR UI-only mapping")
        return output
    }

    func renderActualNativeWorldUi(
        scene: MTLTexture,
        bloom: MTLTexture,
        state: HdrAdaptiveState,
        headroom: Float
    ) throws -> (world: MTLTexture, uiSeed: MTLTexture) {
        let world = try makePrivateRgba16FloatTexture(width: scene.width, height: scene.height)
        let uiSeed = try makeRgba8Texture(
            width: scene.width,
            height: scene.height,
            bytes: SIMD4<UInt8>(255, 0, 255, 255)
        )
        var mutableState = state
        guard let stateBuffer = withUnsafeBytes(of: &mutableState, { bytes in
                device.makeBuffer(
                    bytes: bytes.baseAddress!,
                    length: bytes.count,
                    options: .storageModeShared
                )
              }), let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Actual HDR/UI seed resources are unavailable")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = world
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[1].texture = uiSeed
        pass.colorAttachments[1].loadAction = .dontCare
        pass.colorAttachments[1].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Actual HDR/UI seed encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(scene.width),
            height: Double(scene.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(actualNativeWorldUiPipeline)
        encoder.setFragmentTexture(scene, index: 0)
        encoder.setFragmentTexture(bloom, index: 1)
        encoder.setFragmentSamplerState(nearestSampler, index: 0)
        encoder.setFragmentSamplerState(linearSampler, index: 1)
        var uniforms = PresentUniforms(
            mode: 2,
            sourceEncoding: 2,
            diagnosticPattern: 0,
            currentHeadroom: headroom,
            hdrStrength: 0,
            bloomStrength: 0.22,
            sceneAvailable: 1,
            uiAvailable: 0,
            semanticAvailable: 0
        )
        encoder.setFragmentBytes(
            &uniforms,
            length: MemoryLayout<PresentUniforms>.stride,
            index: 0
        )
        encoder.setFragmentBuffer(stateBuffer, offset: 0, index: 1)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "actual HDR world and UI seed")
        return (world, uiSeed)
    }

    func renderActualHdrLinearUiOnly(source: MTLTexture) throws -> MTLTexture {
        let output = try makePrivateRgba16FloatTexture(width: source.width, height: source.height)
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Actual HDR linear UI-only command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Actual HDR linear UI-only encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(source.width),
            height: Double(source.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(actualLinearUiOnlyPipeline)
        encoder.setFragmentTexture(source, index: 0)
        encoder.setFragmentSamplerState(nearestSampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "actual HDR linear UI-only mapping")
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
            let histogramSnapshot = device.makeBuffer(
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

        guard let snapshot = commandBuffer.makeBlitCommandEncoder() else {
            throw ValidationFailure.message("Histogram snapshot encoder creation failed")
        }
        snapshot.copy(
            from: histogram,
            sourceOffset: 0,
            to: histogramSnapshot,
            destinationOffset: 0,
            size: histogram.length
        )
        snapshot.endEncoding()

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

        let histogramWords = histogramSnapshot.contents().assumingMemoryBound(to: UInt32.self)
        let bins = Array(UnsafeBufferPointer(start: histogramWords, count: 64))
        let clearedWords = histogram.contents().assumingMemoryBound(to: UInt32.self)
        let clearedBins = UnsafeBufferPointer(start: clearedWords, count: 64)
        try require(clearedBins.allSatisfy { $0 == 0 }, "Histogram reduction did not clear every bin")
        let state = stateBuffer.contents().assumingMemoryBound(to: HdrAdaptiveState.self).pointee
        return (bins, state)
    }

    func analyzeReusedPrivateHistogram(
        scenes: [MTLTexture],
        sourceEncoding: UInt32 = 0,
        currentHeadroom: Float = 4.0
    ) throws -> HdrAdaptiveState {
        try require(scenes.count >= 2, "Histogram reuse validation requires multiple frames")
        guard let firstScene = scenes.first else {
            throw ValidationFailure.message("Histogram reuse validation has no source scene")
        }
        try require(
            scenes.allSatisfy { $0.width == firstScene.width && $0.height == firstScene.height },
            "Histogram reuse scenes must have matching dimensions"
        )

        let outputWidth = max((firstScene.width + 3) / 4, 1)
        let outputHeight = max((firstScene.height + 3) / 4, 1)
        let output = try makePrivateRgba16FloatTexture(width: outputWidth, height: outputHeight)
        let semantic = try makeRgba8Texture(
            width: firstScene.width,
            height: firstScene.height,
            bytes: SIMD4<UInt8>(0, 0, 0, 0)
        )
        let depth = try makeDepthTexture(
            width: firstScene.width,
            height: firstScene.height,
            clearDepth: 0.5
        )
        guard
            let histogram = device.makeBuffer(
                length: 64 * MemoryLayout<UInt32>.stride,
                options: .storageModePrivate
            ),
            let histogramReadback = device.makeBuffer(
                length: 64 * MemoryLayout<UInt32>.stride,
                options: .storageModeShared
            )
        else {
            throw ValidationFailure.message("Private histogram validation buffers could not be created")
        }
        var initialState = HdrAdaptiveState(
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
            throw ValidationFailure.message("Private histogram adaptive state could not be created")
        }

        var submitted = [MTLCommandBuffer]()
        submitted.reserveCapacity(scenes.count)
        for (index, scene) in scenes.enumerated() {
            guard let commandBuffer = queue.makeCommandBuffer() else {
                throw ValidationFailure.message("Private histogram command buffer creation failed")
            }
            commandBuffer.label = "Metallum validation: reused private histogram frame \(index)"
            if index == 0 {
                guard let initialize = commandBuffer.makeBlitCommandEncoder() else {
                    throw ValidationFailure.message("Private histogram initialization encoder failed")
                }
                initialize.fill(buffer: histogram, range: 0..<histogram.length, value: 0)
                initialize.endEncoding()
            }

            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = output
            pass.colorAttachments[0].loadAction = .dontCare
            pass.colorAttachments[0].storeAction = .store
            guard let extract = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
                throw ValidationFailure.message("Private histogram extract encoder failed")
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
            extract.setFragmentTexture(semantic, index: 1)
            extract.setFragmentTexture(depth, index: 2)
            extract.setFragmentBuffer(histogram, offset: 0, index: 1)
            var extractUniforms = HdrExtractUniforms(
                sourceEncoding: sourceEncoding,
                semanticAvailable: 0,
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
                throw ValidationFailure.message("Private histogram reduction encoder failed")
            }
            reduce.setComputePipelineState(histogramReducePipeline)
            reduce.setBuffer(histogram, offset: 0, index: 0)
            reduce.setBuffer(stateBuffer, offset: 0, index: 1)
            var reduceUniforms = HdrHistogramReduceUniforms(
                currentHeadroom: currentHeadroom,
                deltaTime: 0,
                forceReset: 1,
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

            if index == scenes.count - 1 {
                guard let readback = commandBuffer.makeBlitCommandEncoder() else {
                    throw ValidationFailure.message("Private histogram readback encoder failed")
                }
                readback.copy(
                    from: histogram,
                    sourceOffset: 0,
                    to: histogramReadback,
                    destinationOffset: 0,
                    size: histogram.length
                )
                readback.endEncoding()
            }
            commandBuffer.commit()
            submitted.append(commandBuffer)
        }

        submitted.last?.waitUntilCompleted()
        for (index, commandBuffer) in submitted.enumerated() {
            guard commandBuffer.status == .completed else {
                let detail = commandBuffer.error.map(String.init(describing:)) ?? "unknown GPU error"
                throw ValidationFailure.message("Private histogram frame \(index) failed: \(detail)")
            }
        }
        let words = histogramReadback.contents().assumingMemoryBound(to: UInt32.self)
        let cleared = UnsafeBufferPointer(start: words, count: 64)
        try require(
            cleared.allSatisfy { $0 == 0 },
            "Reused private histogram retained counts after reduction"
        )
        return stateBuffer.contents().assumingMemoryBound(to: HdrAdaptiveState.self).pointee
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
        readCoordinate: SIMD2<Int>? = nil,
        offscreen: Bool = false
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
        encoder.setRenderPipelineState(offscreen ? worldPresentPipeline : presentPipeline)
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

    func renderSpatialPresent(
        uiFrame: MTLTexture,
        spatialHdrFrame: MTLTexture,
        sourceEncoding: UInt32,
        currentHeadroom: Float
    ) throws -> SIMD4<Float> {
        let output = try makePrivateRgba16FloatTexture(width: uiFrame.width, height: uiFrame.height)
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Spatial present command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0)
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Spatial present encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(output.width),
            height: Double(output.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(spatialPresentPipeline)
        encoder.setFragmentTexture(uiFrame, index: 0)
        encoder.setFragmentTexture(spatialHdrFrame, index: 1)
        encoder.setFragmentSamplerState(nearestSampler, index: 0)
        encoder.setFragmentSamplerState(linearSampler, index: 1)
        var uniforms = PresentUniforms(
            mode: 2,
            sourceEncoding: sourceEncoding,
            diagnosticPattern: 0,
            currentHeadroom: currentHeadroom,
            hdrStrength: 0,
            bloomStrength: 0,
            sceneAvailable: 1,
            uiAvailable: 1,
            semanticAvailable: 0
        )
        encoder.setFragmentBytes(
            &uniforms,
            length: MemoryLayout<PresentUniforms>.stride,
            index: 0
        )
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        try complete(commandBuffer, label: "spatial HDR present")
        return try readRgba16Float(texture: output)
    }

    func renderSpatialWorld(
        sceneFrame: MTLTexture,
        emissionFrame: MTLTexture,
        bloomFrame: MTLTexture,
        semanticFrame: MTLTexture,
        sceneDepthFrame: MTLTexture,
        uniforms: PresentUniforms,
        adaptiveState: HdrAdaptiveState
    ) throws -> SIMD4<Float> {
        let output = try makePrivateRgba16FloatTexture(
            width: sceneFrame.width,
            height: sceneFrame.height
        )
        var mutableAdaptiveState = adaptiveState
        let adaptiveBuffer = withUnsafeBytes(of: &mutableAdaptiveState) { bytes in
            device.makeBuffer(
                bytes: bytes.baseAddress!,
                length: bytes.count,
                options: .storageModeShared
            )
        }
        guard let commandBuffer = queue.makeCommandBuffer(), let adaptiveBuffer else {
            throw ValidationFailure.message("Spatial world command buffer creation failed")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = output
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw ValidationFailure.message("Spatial world encoder creation failed")
        }
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(output.width),
            height: Double(output.height),
            znear: 0,
            zfar: 1
        ))
        encoder.setRenderPipelineState(spatialWorldPipeline)
        encoder.setFragmentTexture(sceneFrame, index: 0)
        encoder.setFragmentTexture(emissionFrame, index: 1)
        encoder.setFragmentTexture(bloomFrame, index: 2)
        encoder.setFragmentTexture(semanticFrame, index: 3)
        encoder.setFragmentTexture(sceneDepthFrame, index: 4)
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
        try complete(commandBuffer, label: "specialized spatial HDR world")
        return try readRgba16Float(texture: output)
    }

    func compareHeadroomLimiter(
        cases: [HeadroomLimiterCase]
    ) throws -> [SIMD2<Float>] {
        try require(!cases.isEmpty, "Headroom limiter validation requires at least one case")
        let caseBuffer = cases.withUnsafeBytes { bytes in
            device.makeBuffer(
                bytes: bytes.baseAddress!,
                length: bytes.count,
                options: .storageModeShared
            )
        }
        let resultLength = cases.count * MemoryLayout<SIMD2<Float>>.stride
        guard
            let caseBuffer,
            let resultBuffer = device.makeBuffer(
                length: resultLength,
                options: .storageModeShared
            ),
            let commandBuffer = queue.makeCommandBuffer(),
            let encoder = commandBuffer.makeComputeCommandEncoder()
        else {
            throw ValidationFailure.message("Headroom limiter validation resources could not be created")
        }
        encoder.setComputePipelineState(headroomLimiterTestPipeline)
        encoder.setBuffer(caseBuffer, offset: 0, index: 0)
        encoder.setBuffer(resultBuffer, offset: 0, index: 1)
        encoder.dispatchThreads(
            MTLSize(width: cases.count, height: 1, depth: 1),
            threadsPerThreadgroup: MTLSize(
                width: min(cases.count, headroomLimiterTestPipeline.maxTotalThreadsPerThreadgroup),
                height: 1,
                depth: 1
            )
        )
        encoder.endEncoding()
        try complete(commandBuffer, label: "HDR headroom limiter comparison")

        let values = resultBuffer.contents().assumingMemoryBound(to: SIMD2<Float>.self)
        return Array(UnsafeBufferPointer(start: values, count: cases.count))
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

    func encodeUiAlphaOverlay(
        encoder: MTLRenderCommandEncoder,
        encodedSource: SIMD4<Float>,
        depthAttached: Bool = false
    ) {
        encoder.setRenderPipelineState(
            depthAttached ? uiAlphaBlendDepthPipeline : uiAlphaBlendPipeline
        )
        var mutableSource = encodedSource
        encoder.setFragmentBytes(
            &mutableSource,
            length: MemoryLayout<SIMD4<Float>>.stride,
            index: 0
        )
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
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

    func readRgba16FloatPixels(texture: MTLTexture) throws -> [SIMD4<Float>] {
        try require(texture.pixelFormat == .rgba16Float, "Readback requires RGBA16Float")
        let bytesPerRow = ((texture.width * 8 + 255) / 256) * 256
        let byteCount = bytesPerRow * texture.height
        guard let buffer = device.makeBuffer(length: byteCount, options: .storageModeShared),
              let commandBuffer = queue.makeCommandBuffer(),
              let encoder = commandBuffer.makeBlitCommandEncoder() else {
            throw ValidationFailure.message("Bulk readback resource creation failed")
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
        try complete(commandBuffer, label: "RGBA16Float bulk readback")

        var pixels = [SIMD4<Float>]()
        pixels.reserveCapacity(texture.width * texture.height)
        for y in 0..<texture.height {
            let row = buffer.contents().advanced(by: y * bytesPerRow)
                .assumingMemoryBound(to: UInt16.self)
            for x in 0..<texture.width {
                let words = row.advanced(by: x * 4)
                pixels.append(SIMD4<Float>(
                    Float(Float16(bitPattern: words[0])),
                    Float(Float16(bitPattern: words[1])),
                    Float(Float16(bitPattern: words[2])),
                    Float(Float16(bitPattern: words[3]))
                ))
            }
        }
        return pixels
    }

    private func makePrivateRgba16FloatTexture(
        width: Int,
        height: Int,
        usage: MTLTextureUsage = [.renderTarget, .shaderRead]
    ) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = usage
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
        try validateActualRadiancePath()
        try validateNonsemanticWhiteUsesHeadroom()
        try validateMidtoneIdentity()
        try validateSaturatedSdrIdentity()
        try validateBoundaryLinearRasterAndBlend()
        try validateSemanticVisibilityAndOcclusion()
        try validateFullResolutionSemanticTargets()
        try validateLowSemanticStrengthGradient()
        try validateCoverageWeightedBloomSeed()
        try validateBilinearGaussianEquivalence()
        try validateBloomPresentBoundsAndUiControl()
        try validateExtendedSrgbIsUnclipped()
        try validateSdrUiCeiling()
        try validateSeededUiQuantizationAndDeterminism()
        try validateContinuousVignetteControl()
        try validateHardAndFallbackUiControl()
        try validateMixedSeededUiControl()
        try validateUiControlDilationChannels()
        try validateTwoChannelPresentVisibility()
        try validateSpatialPrecomposedPresent()
        try validateAnalyticHeadroomLimiter()
        try validateUniformMidgrayHistogram()
        try validateOutdoorSkyReconstruction()
        try validateSparseAndBroadWhiteTargets()
        try validateImmediateHeadroomDropCap()
        try validateFrameRateIndependentSmoothing()
        try validateNativeBackdropAndPresentAbi()
        try require(passCount == 47, "HDR validation check count changed unexpectedly: \(passCount), expected 47")
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

    private func validateActualRadiancePath() throws {
        let scene = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(2.5, 1.5, 0.5, 1.0)
        )
        let analysis = try gpu.analyzeActualRadiance(scene: scene, currentHeadroom: 4.0)
        let extracted = try gpu.readRgba16Float(texture: analysis.extract)
        try require(
            extracted.w > 1.0,
            "Actual-radiance histogram input was clipped before exposure: \(extracted.w)"
        )
        try require(
            extracted.x > 0.5 && extracted.y > 0.05 && extracted.z < 0.001,
            "Actual bloom seed was not extracted from over-reference radiance: \(rgbaDescription(extracted))"
        )
        try require(
            analysis.state.valid == 1
                && analysis.state.inferredPeak > 1.0
                && analysis.state.breakpoint > 0.0
                && analysis.state.breakpoint <= 1.0,
            "Actual exposure state did not report measured HDR radiance"
        )

        let reconstructionOff = try gpu.renderActualWorld(
            scene: scene,
            bloom: analysis.extract,
            state: analysis.state,
            headroom: 4.0,
            legacyReconstructionStrength: 0.0
        )
        let reconstructionMax = try gpu.renderActualWorld(
            scene: scene,
            bloom: analysis.extract,
            state: analysis.state,
            headroom: 4.0,
            legacyReconstructionStrength: 2.0
        )
        let outputOff = try gpu.readRgba16Float(texture: reconstructionOff)
        let outputMax = try gpu.readRgba16Float(texture: reconstructionMax)
        let toggleDelta = max(
            abs(outputOff.x - outputMax.x),
            max(abs(outputOff.y - outputMax.y), abs(outputOff.z - outputMax.z))
        )
        try require(toggleDelta < 0.0005, "Legacy reconstruction control changed actual HDR: \(toggleDelta)")
        try require(
            max(outputOff.x, max(outputOff.y, outputOff.z)) > 1.0
                && max(outputOff.x, max(outputOff.y, outputOff.z)) <= 4.001,
            "Actual HDR display mapping lost or exceeded headroom: \(rgbaDescription(outputOff))"
        )

        let referenceWhite = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(1.0, 1.0, 1.0, 1.0)
        )
        let referenceAnalysis = try gpu.analyzeActualRadiance(
            scene: referenceWhite,
            currentHeadroom: 4.0
        )
        let referenceExtract = try gpu.readRgba16Float(texture: referenceAnalysis.extract)
        try require(
            max(referenceExtract.x, max(referenceExtract.y, referenceExtract.z)) < 0.001,
            "Reference white incorrectly generated actual HDR bloom: \(rgbaDescription(referenceExtract))"
        )

        let boundedBase = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(0.2, 0.2, 0.2, 1.0)
        )
        let boundedBloom = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(1.0, 1.0, 1.0, 0.0)
        )
        let unitExposureState = HdrAdaptiveState(
            breakpoint: 1.0,
            inferredPeak: 1.0,
            medianLog2: -2.0,
            p90Log2: -2.0,
            p99Log2: -2.0,
            brightCoverage: 0.0,
            currentHeadroom: 1.2,
            valid: 1
        )
        let boundedWorld = try gpu.renderActualWorld(
            scene: boundedBase,
            bloom: boundedBloom,
            state: unitExposureState,
            headroom: 1.2,
            legacyReconstructionStrength: 2.0
        )
        let boundedPixel = try gpu.readRgba16Float(texture: boundedWorld)
        let boundedDelta = boundedPixel.x - 0.2
        try require(
            boundedDelta >= 0.029 && boundedDelta <= 0.031,
            "Actual low-headroom bloom escaped its 15%-of-range cap: delta=\(boundedDelta)"
        )

        var invalidPixels = Array(
            repeating: SIMD4<Float>(0.2, 0.2, 0.2, 1.0),
            count: 17 * 17
        )
        invalidPixels[8 * 17 + 8] = SIMD4<Float>(.nan, .infinity, -.infinity, 1.0)
        let invalidScene = try gpu.makeRgba16FloatTexture(
            width: 17,
            height: 17,
            pixels: invalidPixels
        )
        let invalidAnalysis = try gpu.analyzeActualRadiance(
            scene: invalidScene,
            currentHeadroom: 4.0
        )
        let invalidExtractPixels = try gpu.readRgba16FloatPixels(
            texture: invalidAnalysis.extract
        )
        try require(
            invalidExtractPixels.allSatisfy {
                $0.x.isFinite && $0.y.isFinite && $0.z.isFinite && $0.w.isFinite
            },
            "Non-finite actual radiance escaped extract/histogram sanitization"
        )
        try require(
            invalidAnalysis.state.breakpoint.isFinite
                && invalidAnalysis.state.inferredPeak.isFinite
                && invalidAnalysis.state.medianLog2.isFinite
                && invalidAnalysis.state.p90Log2.isFinite
                && invalidAnalysis.state.p99Log2.isFinite
                && invalidAnalysis.state.brightCoverage.isFinite
                && invalidAnalysis.state.currentHeadroom.isFinite,
            "Non-finite actual radiance corrupted exposure state"
        )
        let invalidBloom = try gpu.renderCombinedBlur(source: invalidAnalysis.extract)
        let invalidWorld = try gpu.renderActualWorld(
            scene: invalidScene,
            bloom: invalidBloom,
            state: invalidAnalysis.state,
            headroom: 4.0,
            legacyReconstructionStrength: 2.0
        )
        let invalidWorldPixels = try gpu.readRgba16FloatPixels(texture: invalidWorld)
        try require(
            invalidWorldPixels.allSatisfy {
                $0.x.isFinite && $0.y.isFinite && $0.z.isFinite && $0.w.isFinite
                    && $0.x >= 0 && $0.y >= 0 && $0.z >= 0
                    && $0.x <= 4.001 && $0.y <= 4.001 && $0.z <= 4.001
            },
            "Non-finite actual radiance reached the FP16 display boundary"
        )

        let invalidLinearUi = try gpu.renderActualHdrLinearUiOnly(source: invalidScene)
        let invalidLinearUiPixels = try gpu.readRgba16FloatPixels(texture: invalidLinearUi)
        try require(
            invalidLinearUiPixels.allSatisfy {
                $0.x.isFinite && $0.y.isFinite && $0.z.isFinite
                    && $0.x >= 0 && $0.y >= 0 && $0.z >= 0
                    && $0.x <= 1.001 && $0.y <= 1.001 && $0.z <= 1.001
            },
            "Title/loading linear UI-only output retained NaN/Inf"
        )

        let seedScene = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(0.4, 0.2, 0.1, 1.0)
        )
        let seedBloom = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(2.0, 0.5, 0.0, 1.0)
        )
        let exposureState = HdrAdaptiveState(
            breakpoint: 0.5,
            inferredPeak: 2.0,
            medianLog2: -1.0,
            p90Log2: 0.0,
            p99Log2: 1.0,
            brightCoverage: 0.1,
            currentHeadroom: 4.0,
            valid: 1
        )
        let separated = try gpu.renderActualNativeWorldUi(
            scene: seedScene,
            bloom: seedBloom,
            state: exposureState,
            headroom: 4.0
        )
        let separatedWorld = try gpu.readRgba16Float(texture: separated.world)
        let separatedSeed = gpu.readRgba8(texture: separated.uiSeed)
        let separatedSeedLinear = SIMD3<Float>(
            srgbToLinear(Float(separatedSeed.x) / 255.0),
            srgbToLinear(Float(separatedSeed.y) / 255.0),
            srgbToLinear(Float(separatedSeed.z) / 255.0)
        )
        try require(
            abs(separatedWorld.x - separatedSeedLinear.x) < 0.008
                && abs(separatedWorld.y - separatedSeedLinear.y) < 0.008
                && abs(separatedWorld.z - separatedSeedLinear.z) < 0.008
                && separatedSeed.w == 0,
            "Actual precompose seed diverged from exposed/bloomed world: world \(rgbaDescription(separatedWorld)), seed \(separatedSeed)"
        )
        let separatedPresent = try gpu.renderSpatialPresent(
            uiFrame: separated.uiSeed,
            spatialHdrFrame: separated.world,
            sourceEncoding: 2,
            currentHeadroom: 4.0
        )
        try require(
            abs(separatedPresent.x - separatedWorld.x) < 0.008
                && abs(separatedPresent.y - separatedWorld.y) < 0.008
                && abs(separatedPresent.z - separatedWorld.z) < 0.008,
            "Untouched actual HDR precompose was misclassified as alpha-zero UI: present \(rgbaDescription(separatedPresent)), world \(rgbaDescription(separatedWorld))"
        )
        passCount += 1
        print(String(
            format: "PASS actual FP16 radiance survives exposure/bloom mapping and ignores legacy reconstruction: pre-map %.3f, output %.3f",
            extracted.w,
            max(outputOff.x, max(outputOff.y, outputOff.z))
        ))
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

    private func validateBilinearGaussianEquivalence() throws {
        func maximumError(
            width: Int,
            height: Int,
            pixels: [SIMD4<Float>]
        ) throws -> Float {
            let source = try gpu.makeRgba16FloatTexture(
                width: width,
                height: height,
                pixels: pixels
            )
            let actual = try gpu.renderCombinedBlur(source: source)
            let horizontalReference = try gpu.renderLegacyBlur(
                source: source,
                texelStep: SIMD2<Float>(1.0 / Float(width), 0)
            )
            let reference = try gpu.renderLegacyBlur(
                source: horizontalReference,
                texelStep: SIMD2<Float>(0, 1.0 / Float(height))
            )
            let actualPixels = try gpu.readRgba16FloatPixels(texture: actual)
            let referencePixels = try gpu.readRgba16FloatPixels(texture: reference)
            return zip(actualPixels, referencePixels).reduce(Float.zero) { result, pair in
                let error = abs(pair.0 - pair.1)
                return max(result, max(error.x, max(error.y, max(error.z, error.w))))
            }
        }

        typealias BlurCase = (width: Int, height: Int, pixels: [SIMD4<Float>])
        var horizontalPixels = [SIMD4<Float>]()
        var verticalPixels = [SIMD4<Float>]()
        for index in 0..<9 {
            let horizontalValue = Float((index * 5) % 9) / 8.0
            horizontalPixels.append(SIMD4<Float>(repeating: horizontalValue))
            let red = Float(index) / 8.0
            let green = Float((index * 3) % 9) / 8.0
            let blue = Float((index * 7) % 9) / 8.0
            verticalPixels.append(SIMD4<Float>(red, green, blue, 1.0))
        }
        var smallColorPixels = [SIMD4<Float>]()
        for y in 0..<4 {
            for x in 0..<5 {
                let red = Float((x * 3 + y * 5) % 11) / 10.0
                let green = Float((x * 7 + y * 2 + 1) % 13) / 12.0
                let blue = Float((x + y * 4 + 2) % 9) / 8.0
                let alpha = Float((x * 5 + y * 3 + 3) % 10) / 9.0
                smallColorPixels.append(SIMD4<Float>(red, green, blue, alpha))
            }
        }
        var tiledHdrPixels = [SIMD4<Float>]()
        for y in 0..<19 {
            for x in 0..<17 {
                let red = Float((x * 11 + y * 7) % 41) / 10.0
                let green = Float((x * 3 + y * 13 + 1) % 37) / 12.0
                let blue = Float((x * 17 + y * 5 + 2) % 43) / 11.0
                let alpha = Float((x * 7 + y * 19 + 3) % 29) / 9.0
                tiledHdrPixels.append(SIMD4<Float>(red, green, blue, alpha))
            }
        }
        var impulsePixels = [SIMD4<Float>](
            repeating: SIMD4<Float>(repeating: 0.0),
            count: 31 * 18
        )
        impulsePixels[9 * 31 + 16] = SIMD4<Float>(4.0, 2.0, 1.0, 0.5)
        let cases: [BlurCase] = [
            (1, 1, [SIMD4<Float>(repeating: 0.625)]),
            (9, 1, horizontalPixels),
            (1, 9, verticalPixels),
            (5, 4, smallColorPixels),
            (17, 19, tiledHdrPixels),
            (31, 18, impulsePixels)
        ]

        var largestError: Float = 0
        for testCase in cases {
            largestError = max(
                largestError,
                try maximumError(
                    width: testCase.width,
                    height: testCase.height,
                    pixels: testCase.pixels
                )
            )
        }
        try require(
            largestError <= 0.002,
            String(
                format: "Combined tiled Gaussian diverged from the two-pass FP16 reference: max error %.8f",
                largestError
            )
        )
        passCount += 1
        print(String(
            format: "PASS combined tiled Gaussian matches two-pass RGBA FP16 reference across edge and tile boundaries: %.6f",
            largestError
        ))
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

    private func validateMixedSeededUiControl() throws {
        let baseline: UInt8 = 200
        let dimmed: UInt8 = 100
        let scene = try gpu.makeRgba8Texture(
            width: 2,
            height: 2,
            bytes: SIMD4<UInt8>(baseline, baseline, baseline, 255)
        )
        let final = try gpu.makeRgba8Texture(
            width: 2,
            height: 2,
            pixels: [
                // Covered RGB is deliberately unrelated to the seeded backdrop.
                // Its alpha is the complete hard-coverage contract, so it must
                // not be reclassified by the alpha-zero RGB path.
                SIMD4<UInt8>(20, 250, 40, 128),
                SIMD4<UInt8>(baseline, baseline, baseline, 0),
                SIMD4<UInt8>(dimmed, dimmed, dimmed, 0),
                SIMD4<UInt8>(baseline, baseline, baseline, 0)
            ]
        )
        let control = try gpu.renderUiControl(
            finalFrame: final,
            sceneFrame: scene,
            sourceEncoding: 0,
            seededUiAvailable: true
        )
        try require(control.count == 1, "Unexpected mixed seeded UI control dimensions")
        let expectedDimming = Int(((1.0 - srgbToLinear(Float(dimmed) / 255.0)
            / srgbToLinear(Float(baseline) / 255.0)) * 255.0).rounded())
        try require(
            control[0].x == 128 && abs(Int(control[0].y) - expectedDimming) <= 2,
            "Mixed covered/alpha-zero UI control mismatch: \(control), expected dimming \(expectedDimming)"
        )
        passCount += 1
        print("PASS mixed 2x2 seeded UI keeps alpha coverage separate from alpha-zero RGB classification")
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

    private func validateSpatialPrecomposedPresent() throws {
        let parityScene = try gpu.makeRgba8Texture(
            bytes: SIMD4<UInt8>(204, 179, 128, 255)
        )
        let parityEmission = try gpu.makeRgba16FloatTexture(
            width: 1,
            height: 1,
            value: SIMD4<Float>(0.08, 0.04, 0.02, 0.30)
        )
        let parityBloom = try gpu.makeRgba16FloatTexture(
            width: 1,
            height: 1,
            value: SIMD4<Float>(0.06, 0.03, 0.01, 0)
        )
        let paritySemantic = try gpu.makeSemanticTexture(markerDepth: 0.5)
        let parityDepth = try gpu.makeDepthTexture(clearDepth: 0.5)
        let parityMask = try gpu.makeRg8Texture(bytes: SIMD2<UInt8>(0, 0))
        let parityUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(0, 0, 0, 0))
        var parityUniforms = baseUniforms(semanticAvailable: true)
        parityUniforms.bloomStrength = 0.22
        let parityAdaptive = defaultAdaptiveState()
        let generalWorld = try gpu.renderPresent(
            finalFrame: parityScene,
            sceneFrame: parityScene,
            emissionFrame: parityEmission,
            bloomFrame: parityBloom,
            uiMaskFrame: parityMask,
            uiFrame: parityUi,
            uniforms: parityUniforms,
            adaptiveState: parityAdaptive,
            semanticFrame: paritySemantic,
            sceneDepthFrame: parityDepth,
            offscreen: true
        )
        let specializedWorld = try gpu.renderSpatialWorld(
            sceneFrame: parityScene,
            emissionFrame: parityEmission,
            bloomFrame: parityBloom,
            semanticFrame: paritySemantic,
            sceneDepthFrame: parityDepth,
            uniforms: parityUniforms,
            adaptiveState: parityAdaptive
        )
        try require(
            abs(generalWorld.x - specializedWorld.x) < 0.003
                && abs(generalWorld.y - specializedWorld.y) < 0.003
                && abs(generalWorld.z - specializedWorld.z) < 0.003,
            "Specialized spatial world diverged from general HDR present: general \(rgbaDescription(generalWorld)), specialized \(rgbaDescription(specializedWorld))"
        )

        let verticalScene = try gpu.makeRgba16FloatTexture(
            width: 2,
            height: 2,
            pixels: [
                SIMD4<Float>(0.1, 0.1, 0.1, 1),
                SIMD4<Float>(0.1, 0.1, 0.1, 1),
                SIMD4<Float>(0.8, 0.8, 0.8, 1),
                SIMD4<Float>(0.8, 0.8, 0.8, 1)
            ]
        )
        let orientationAux = try auxiliaries()
        let orientationUniforms = baseUniforms(
            mode: 1,
            sourceEncoding: 2,
            headroom: 4,
            sceneAvailable: false
        )
        let worldTop = try gpu.renderPresent(
            finalFrame: verticalScene,
            sceneFrame: verticalScene,
            emissionFrame: orientationAux.emission,
            bloomFrame: orientationAux.bloom,
            uiMaskFrame: orientationAux.uiMask,
            uiFrame: orientationAux.transparentUi,
            uniforms: orientationUniforms,
            readCoordinate: SIMD2<Int>(0, 0),
            offscreen: true
        )
        let worldBottom = try gpu.renderPresent(
            finalFrame: verticalScene,
            sceneFrame: verticalScene,
            emissionFrame: orientationAux.emission,
            bloomFrame: orientationAux.bloom,
            uiMaskFrame: orientationAux.uiMask,
            uiFrame: orientationAux.transparentUi,
            uniforms: orientationUniforms,
            readCoordinate: SIMD2<Int>(0, 1),
            offscreen: true
        )
        try require(
            abs(worldTop.x - 0.1) < 0.002 && abs(worldBottom.x - 0.8) < 0.002,
            "Spatial world precompose flipped vertically: top \(rgbaDescription(worldTop)), bottom \(rgbaDescription(worldBottom))"
        )

        let spatialHdr = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(1.5, 1.25, 1.0, 1)
        )
        // The MetalFX HDR seed clamps to SDR white in the GUI target. The
        // fast path must reconstruct that exact quantized seed before adding
        // the HDR delta, otherwise untouched pixels acquire a seam.
        let seededUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 0))
        let untouched = try gpu.renderSpatialPresent(
            uiFrame: seededUi,
            spatialHdrFrame: spatialHdr,
            sourceEncoding: 2,
            currentHeadroom: 4
        )
        try require(
            abs(untouched.x - 1.5) < 0.002
                && abs(untouched.y - 1.25) < 0.002
                && abs(untouched.z - 1.0) < 0.002,
            "Spatial precomposed present changed an untouched pixel: \(rgbaDescription(untouched))"
        )

        let opaqueUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(64, 96, 128, 255))
        let hiddenScene = try gpu.renderSpatialPresent(
            uiFrame: opaqueUi,
            spatialHdrFrame: spatialHdr,
            sourceEncoding: 2,
            currentHeadroom: 4
        )
        let expectedUi = SIMD3<Float>(
            srgbToLinear(Float(64) / 255.0),
            srgbToLinear(Float(96) / 255.0),
            srgbToLinear(Float(128) / 255.0)
        )
        try require(
            abs(hiddenScene.x - expectedUi.x) < 0.002
                && abs(hiddenScene.y - expectedUi.y) < 0.002
                && abs(hiddenScene.z - expectedUi.z) < 0.002,
            "Spatial precomposed present leaked HDR through opaque UI: \(rgbaDescription(hiddenScene))"
        )

        let halfUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(128, 128, 128, 128))
        let halfCovered = try gpu.renderSpatialPresent(
            uiFrame: halfUi,
            spatialHdrFrame: spatialHdr,
            sourceEncoding: 2,
            currentHeadroom: 4
        )
        let halfBase = srgbToLinear(Float(128) / 255.0)
        let halfVisibility = 1.0 - Float(128) / 255.0
        let expectedHalfRed = halfBase + halfVisibility * (1.5 - 1.0)
        try require(
            abs(halfCovered.x - expectedHalfRed) < 0.003,
            "Spatial precompose did not preserve partial alpha coverage: \(rgbaDescription(halfCovered))"
        )

        let vignetteUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(128, 128, 128, 0))
        let vignette = try gpu.renderSpatialPresent(
            uiFrame: vignetteUi,
            spatialHdrFrame: spatialHdr,
            sourceEncoding: 2,
            currentHeadroom: 4
        )
        let expectedVignetteRed = halfBase * 1.5
        try require(
            abs(vignette.x - expectedVignetteRed) < 0.003,
            "Spatial precompose vignette transmission is incorrect: \(rgbaDescription(vignette))"
        )

        let boundedHdr = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(0.5, 0.5, 0.5, 1)
        )
        let invertUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 0))
        let inverted = try gpu.renderSpatialPresent(
            uiFrame: invertUi,
            spatialHdrFrame: boundedHdr,
            sourceEncoding: 2,
            currentHeadroom: 4
        )
        try require(
            abs(inverted.x - 1.0) < 0.002,
            "Spatial precompose leaked the scene through alpha-zero invert: \(rgbaDescription(inverted))"
        )

        let ringingHdr = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(-0.1, -0.1, -0.1, 1)
        )
        let ringingUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(128, 128, 128, 128))
        let ringingCovered = try gpu.renderSpatialPresent(
            uiFrame: ringingUi,
            spatialHdrFrame: ringingHdr,
            sourceEncoding: 2,
            currentHeadroom: 4
        )
        try require(
            abs(ringingCovered.x - halfBase) < 0.002,
            "Negative MetalFX ringing darkened translucent UI: \(rgbaDescription(ringingCovered))"
        )
        let overshootHdr = try gpu.makeRgba16FloatTexture(
            value: SIMD4<Float>(5.0, 3.0, 1.0, 1)
        )
        let overshootUi = try gpu.makeRgba8Texture(bytes: SIMD4<UInt8>(255, 255, 255, 0))
        let limitedOvershoot = try gpu.renderSpatialPresent(
            uiFrame: overshootUi,
            spatialHdrFrame: overshootHdr,
            sourceEncoding: 2,
            currentHeadroom: 4
        )
        try require(
            abs(limitedOvershoot.x - 4.0) < 0.01
                && abs(limitedOvershoot.y - 2.5) < 0.01
                && abs(limitedOvershoot.z - 1.0) < 0.01,
            "Spatial early-out changed hue while limiting MetalFX overshoot: \(rgbaDescription(limitedOvershoot))"
        )
        passCount += 1
        print("PASS spatial precompose preserves orientation, alpha, vignette, and invert semantics")
    }

    private func validateAnalyticHeadroomLimiter() throws {
        func peak(_ value: SIMD3<Float>) -> Float {
            max(value.x, max(value.y, value.z))
        }

        func legacyScale(base: SIMD3<Float>, delta: SIMD3<Float>, headroom: Float) -> Float {
            if peak(base + delta) <= headroom {
                return 1.0
            }
            var low: Float = 0.0
            var high: Float = 1.0
            for _ in 0..<7 {
                let candidate = 0.5 * (low + high)
                if peak(base + delta * candidate) <= headroom {
                    low = candidate
                } else {
                    high = candidate
                }
            }
            return low
        }

        func analyticScale(base: SIMD3<Float>, delta: SIMD3<Float>, headroom: Float) -> Float {
            if peak(base + delta) <= headroom {
                return 1.0
            }
            let baseMargin = headroom - peak(base)
            if baseMargin <= max(headroom, 1.0) * (4.0 * Float.ulpOfOne) {
                return legacyScale(base: base, delta: delta, headroom: headroom)
            }
            var allowed: Float = 1.0
            if delta.x > 0 {
                allowed = min(allowed, (headroom - base.x) / delta.x)
            }
            if delta.y > 0 {
                allowed = min(allowed, (headroom - base.y) / delta.y)
            }
            if delta.z > 0 {
                allowed = min(allowed, (headroom - base.z) / delta.z)
            }
            let step: Float = 1.0 / 128.0
            var quantized = floor(min(max(allowed, 0.0), 1.0) * 128.0) * step
            if peak(base + delta * quantized) > headroom {
                quantized = max(0.0, quantized - step)
            }
            let next = min(1.0, quantized + step)
            if next > quantized && peak(base + delta * next) <= headroom {
                quantized = next
            }
            return quantized
        }

        var randomState: UInt64 = 0x8f3d_7a21_c495_e6b0
        func randomUnit() -> Float {
            randomState = randomState &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Float((randomState >> 40) & 0x00ff_ffff) / Float(0x0100_0000)
        }

        var cases = [
            HeadroomLimiterCase(
                baseAndHeadroom: SIMD4<Float>(0.17, 0, 0, 1.02),
                delta: SIMD4<Float>(1.36, 0, 0, 0)
            ),
            HeadroomLimiterCase(
                baseAndHeadroom: SIMD4<Float>(
                    1.9790434837341309,
                    0,
                    0,
                    3.934260606765747
                ),
                delta: SIMD4<Float>(3.7353403568267822, 0, 0, 0)
            ),
            HeadroomLimiterCase(
                baseAndHeadroom: SIMD4<Float>(2.0, 0.5, 0.5, 2.0),
                delta: SIMD4<Float>(2.3841858e-7, 0.25, 0, 0)
            )
        ]
        cases.reserveCapacity(16_387)
        for _ in 0..<16_384 {
            let headroom = 1.0 + randomUnit() * 7.0
            let base = SIMD3<Float>(randomUnit(), randomUnit(), randomUnit()) * headroom
            let delta = SIMD3<Float>(randomUnit(), randomUnit(), randomUnit()) * 5.0
                - SIMD3<Float>(repeating: 0.5)
            cases.append(HeadroomLimiterCase(
                baseAndHeadroom: SIMD4<Float>(base.x, base.y, base.z, headroom),
                delta: SIMD4<Float>(delta.x, delta.y, delta.z, 0)
            ))
        }

        for value in cases {
            let base = SIMD3<Float>(
                value.baseAndHeadroom.x,
                value.baseAndHeadroom.y,
                value.baseAndHeadroom.z
            )
            let headroom = value.baseAndHeadroom.w
            let delta = SIMD3<Float>(value.delta.x, value.delta.y, value.delta.z)
            let legacy = legacyScale(base: base, delta: delta, headroom: headroom)
            let analytic = analyticScale(base: base, delta: delta, headroom: headroom)
            try require(
                legacy == analytic,
                "Analytic limiter changed seven-step result: legacy \(legacy), analytic \(analytic), base \(base), delta \(delta), headroom \(headroom)"
            )
        }
        let gpuResults = try gpu.compareHeadroomLimiter(cases: cases)
        for (index, result) in gpuResults.enumerated() {
            try require(
                result.x == result.y,
                "Production GPU limiter changed legacy result at case \(index): optimized \(result.x), legacy \(result.y), input \(cases[index])"
            )
        }
        try require(gpuResults[0] == SIMD2<Float>(repeating: 0.625),
                    "Lower-rounded boundary did not recover the accepted 80/128 bin: \(gpuResults[0])")
        passCount += 1
        print("PASS production GPU headroom limiter matches seven-step search across \(cases.count) cases")
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

        let darkScene = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(32, 32, 32, 255)
        )
        let brightScene = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(240, 240, 240, 255)
        )
        let reusedState = try gpu.analyzeReusedPrivateHistogram(
            scenes: [darkScene, brightScene, scene]
        )
        try require(
            abs(reusedState.medianLog2 - analysis.state.medianLog2) < 0.001
                && abs(reusedState.p90Log2 - analysis.state.p90Log2) < 0.001
                && abs(reusedState.p99Log2 - analysis.state.p99Log2) < 0.001
                && abs(reusedState.breakpoint - analysis.state.breakpoint) < 0.001
                && abs(reusedState.inferredPeak - analysis.state.inferredPeak) < 0.001,
            "Reused private histogram accumulated prior frames: \(reusedState)"
        )

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
        print("PASS reused private GPU histogram self-clears across in-flight frames and excludes semantic emission")
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
            let setFrameStateSymbol = dlsym(handle, "metallum_set_frame_state_v2"),
            let generationContractSymbol = dlsym(
                handle,
                "metallum_renderer_generation_native_contract_v1"
            ),
            let createEdrMonitorSymbol = dlsym(handle, "metallum_create_edr_monitor"),
            let edrMonitorQuerySymbol = dlsym(handle, "metallum_EDRMonitor_query"),
            let releaseSymbol = dlsym(handle, "metallum_release_object"),
            let configureLayerSymbol = dlsym(handle, "metallum_configure_layer"),
            let updateLayerHeadroomSymbol = dlsym(handle, "metallum_update_layer_contents_headroom"),
            let backdropSymbol = dlsym(handle, "metallum_MTLCommandBuffer_encodeHdrUiBackdrop"),
            let fusedBackdropSymbol = dlsym(
                handle,
                "metallum_MTLRenderCommandEncoder_encodePreparedHdrUiBackdrop"
            ),
            let materializePreparedBackdropSymbol = dlsym(
                handle,
                "metallum_MTLCommandBuffer_materializePreparedHdrUiBackdrop"
            ),
            let coherentMenuBlurSymbol = dlsym(
                handle,
                "metallum_MTLCommandBuffer_encodeCoherentMenuBlur"
            ),
            let spatialScreenshotSymbol = dlsym(handle, "metallum_MTLCommandBuffer_encodeSpatialScreenshot"),
            let presentSymbol = dlsym(handle, "metallum_MTLCommandBuffer_encodePresentTextureToDrawable")
        else {
            throw ValidationFailure.message("Native HDR present symbols are missing")
        }
        let initialize = unsafeBitCast(initSymbol, to: NativeInitFunction.self)
        let setFrameState = unsafeBitCast(
            setFrameStateSymbol,
            to: NativeSetFrameStateFunction.self
        )
        let generationContract = unsafeBitCast(
            generationContractSymbol,
            to: NativeGenerationContractFunction.self
        )
        let createEdrMonitor = unsafeBitCast(
            createEdrMonitorSymbol,
            to: NativeCreateEdrMonitorFunction.self
        )
        let queryEdrMonitor = unsafeBitCast(
            edrMonitorQuerySymbol,
            to: NativeEdrMonitorQueryFunction.self
        )
        let release = unsafeBitCast(releaseSymbol, to: NativeReleaseFunction.self)
        let configureLayer = unsafeBitCast(configureLayerSymbol, to: NativeConfigureLayerFunction.self)
        let updateLayerHeadroom = unsafeBitCast(
            updateLayerHeadroomSymbol,
            to: NativeUpdateLayerContentsHeadroomFunction.self
        )
        let backdrop = unsafeBitCast(backdropSymbol, to: NativeBackdropFunction.self)
        let fusedBackdrop = unsafeBitCast(
            fusedBackdropSymbol,
            to: NativeFusedBackdropFunction.self
        )
        let materializePreparedBackdrop = unsafeBitCast(
            materializePreparedBackdropSymbol,
            to: NativeMaterializePreparedBackdropFunction.self
        )
        let coherentMenuBlur = unsafeBitCast(
            coherentMenuBlurSymbol,
            to: NativeCoherentMenuBlurFunction.self
        )
        let spatialScreenshot = unsafeBitCast(
            spatialScreenshotSymbol,
            to: NativeSpatialScreenshotFunction.self
        )
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

        guard let edrMonitor = createEdrMonitor(objectPointer(window)) else {
            throw ValidationFailure.message("Native EDR monitor creation returned nil")
        }
        let packedHeadroom = queryEdrMonitor(edrMonitor)
        release(edrMonitor)
        let currentHeadroom = Float(bitPattern: UInt32(truncatingIfNeeded: packedHeadroom))
        let potentialHeadroom = Float(bitPattern: UInt32(truncatingIfNeeded: packedHeadroom >> 32))
        try require(
            currentHeadroom.isFinite && currentHeadroom >= 1.0
                && potentialHeadroom.isFinite && potentialHeadroom >= currentHeadroom,
            "Packed EDR monitor ABI returned invalid values: current \(currentHeadroom), potential \(potentialHeadroom)"
        )

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
        print("PASS packed EDR monitor ABI and native layer SDR/HDR switching")

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

        let initializationStatus = initialize(objectPointer(gpu.device as AnyObject))
        try require(
            initializationStatus == 1,
            "Native built-in shaders did not use the precompiled library (status \(initializationStatus))"
        )
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
            nil,
            nil,
            1,
            0,
            0,
            0,
            0,
            0,
            1,
            1,
            0
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
            nil,
            nil,
            2,
            0,
            0,
            0,
            0,
            0,
            1,
            1,
            0
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

        let fenceProducerValues = [
            SIMD4<Float>(0.0625, 0.25, 0.75, 1),
            SIMD4<Float>(0.8125, 0.125, 0.375, 1)
        ]
        let fenceStagingSources = try fenceProducerValues.map {
            try gpu.makeRgba16FloatTexture(width: 8, height: 8, value: $0)
        }
        let untrackedSourceDescriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: 8,
            height: 8,
            mipmapped: false
        )
        untrackedSourceDescriptor.storageMode = .private
        untrackedSourceDescriptor.hazardTrackingMode = .untracked
        untrackedSourceDescriptor.usage = [.shaderRead]
        let untrackedDestinationDescriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm,
            width: 8,
            height: 8,
            mipmapped: false
        )
        untrackedDestinationDescriptor.storageMode = .private
        untrackedDestinationDescriptor.hazardTrackingMode = .untracked
        untrackedDestinationDescriptor.usage = [.shaderRead, .renderTarget]
        guard
            let fenceSource = gpu.device.makeTexture(descriptor: untrackedSourceDescriptor),
            let fenceDestination = gpu.device.makeTexture(descriptor: untrackedDestinationDescriptor),
            let backdropFence = gpu.device.makeFence()
        else {
            throw ValidationFailure.message("Untracked UI backdrop fence resources are unavailable")
        }
        func encodedByte(_ linear: Float) -> UInt8 {
            UInt8(clamping: Int((min(max(linearToSrgb(linear), 0), 1) * 255).rounded()))
        }
        for iteration in 0..<6 {
            let valueIndex = iteration % fenceProducerValues.count
            let sourceValue = fenceProducerValues[valueIndex]
            guard
                let fenceCommandBuffer = backdropQueue.makeCommandBuffer(),
                let producer = fenceCommandBuffer.makeBlitCommandEncoder()
            else {
                throw ValidationFailure.message("UI backdrop fence command resources are unavailable")
            }
            producer.label = "Metallum test untracked UI seed producer"
            producer.copy(
                from: fenceStagingSources[valueIndex],
                sourceSlice: 0,
                sourceLevel: 0,
                sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
                sourceSize: MTLSize(width: 8, height: 8, depth: 1),
                to: fenceSource,
                destinationSlice: 0,
                destinationLevel: 0,
                destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0)
            )
            producer.updateFence(backdropFence)
            producer.endEncoding()

            let fenceBackdropStatus = backdrop(
                objectPointer(fenceCommandBuffer as AnyObject),
                objectPointer(fenceSource as AnyObject),
                objectPointer(fenceDestination as AnyObject),
                nil,
                nil,
                objectPointer(backdropFence as AnyObject),
                2,
                0,
                0,
                0,
                0,
                0,
                1,
                1,
                0
            )
            try require(
                fenceBackdropStatus == 1,
                "Untracked UI backdrop fence chain returned \(fenceBackdropStatus) at iteration \(iteration)"
            )

            let downstreamPass = MTLRenderPassDescriptor()
            downstreamPass.colorAttachments[0].texture = fenceDestination
            downstreamPass.colorAttachments[0].loadAction = .load
            downstreamPass.colorAttachments[0].storeAction = .store
            guard let downstream = fenceCommandBuffer.makeRenderCommandEncoder(
                descriptor: downstreamPass
            ) else {
                throw ValidationFailure.message("Downstream UI fence encoder creation failed")
            }
            downstream.label = "Metallum test downstream UI fence consumer"
            downstream.waitForFence(backdropFence, before: .fragment)
            downstream.setViewport(MTLViewport(
                originX: 0,
                originY: 0,
                width: 8,
                height: 8,
                znear: 0,
                zfar: 1
            ))
            downstream.setScissorRect(MTLScissorRect(x: 4, y: 0, width: 4, height: 8))
            gpu.encodeUiAlphaOverlay(
                encoder: downstream,
                encodedSource: SIMD4<Float>(0, 0, 0, 0.5)
            )
            downstream.endEncoding()
            fenceCommandBuffer.commit()
            fenceCommandBuffer.waitUntilCompleted()
            try require(
                fenceCommandBuffer.status == .completed,
                "Untracked UI backdrop fence chain failed at iteration \(iteration): \(String(describing: fenceCommandBuffer.error))"
            )

            let expectedSeed = SIMD4<UInt8>(
                encodedByte(sourceValue.x),
                encodedByte(sourceValue.y),
                encodedByte(sourceValue.z),
                0
            )
            let seedPixel = try gpu.readPrivateRgba8(texture: fenceDestination, x: 1, y: 4)
            try require(
                abs(Int(seedPixel.x) - Int(expectedSeed.x)) <= 2
                    && abs(Int(seedPixel.y) - Int(expectedSeed.y)) <= 2
                    && abs(Int(seedPixel.z) - Int(expectedSeed.z)) <= 2
                    && seedPixel.w == 0,
                "UI backdrop consumed a stale untracked producer at iteration \(iteration): \(seedPixel), expected \(expectedSeed)"
            )
            let downstreamPixel = try gpu.readPrivateRgba8(
                texture: fenceDestination,
                x: 6,
                y: 4
            )
            try require(
                abs(Int(downstreamPixel.x) - Int(expectedSeed.x) / 2) <= 2
                    && abs(Int(downstreamPixel.y) - Int(expectedSeed.y) / 2) <= 2
                    && abs(Int(downstreamPixel.z) - Int(expectedSeed.z) / 2) <= 2
                    && abs(Int(downstreamPixel.w) - 128) <= 1,
                "Downstream UI pass did not observe the fenced seed at iteration \(iteration): \(downstreamPixel), seed \(expectedSeed)"
            )
        }
        passCount += 1
        print("PASS native UI backdrop preserves the untracked producer -> seed -> downstream fence chain")

        let scaledSource = try gpu.makeRgba16FloatTexture(
            width: 8,
            height: 8,
            value: SIMD4<Float>(0.5, 1, 0, 1)
        )
        let scaledDestination = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(0, 0, 0, 255)
        )
        guard let scaledCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Spatial backdrop command buffer creation failed")
        }
        let scaledStatus = backdrop(
            objectPointer(scaledCommandBuffer as AnyObject),
            objectPointer(scaledSource as AnyObject),
            objectPointer(scaledDestination as AnyObject),
            nil,
            nil,
            nil,
            2,
            0,
            1,
            0,
            0,
            0,
            1,
            1,
            0
        )
        try require(scaledStatus == 1, "Spatial backdrop ABI returned \(scaledStatus)")
        scaledCommandBuffer.commit()
        scaledCommandBuffer.waitUntilCompleted()
        try require(scaledCommandBuffer.status == .completed, "Spatial backdrop GPU command failed")
        let scaledPixel = gpu.readRgba8(texture: scaledDestination)
        try require(
            abs(Int(scaledPixel.x) - 188) <= 2 && scaledPixel.y >= 252 && scaledPixel.z == 0 && scaledPixel.w == 0,
            "Spatial backdrop mismatch: \(scaledPixel)"
        )
        passCount += 1
        print("PASS native MetalFX backdrop scales FP16 scene before full-resolution SDR UI")

        let perceptualDestination = try gpu.makePrivateRgba8Texture(width: 16, height: 16)
        guard let perceptualCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Perceptual spatial command buffer creation failed")
        }
        let perceptualStatus = backdrop(
            objectPointer(perceptualCommandBuffer as AnyObject),
            objectPointer(scaledSource as AnyObject),
            objectPointer(perceptualDestination as AnyObject),
            nil,
            nil,
            nil,
            2,
            0,
            1,
            0,
            1,
            0,
            1,
            1,
            0
        )
        try require(perceptualStatus == 3, "Perceptual spatial backdrop ABI returned \(perceptualStatus)")
        perceptualCommandBuffer.commit()
        perceptualCommandBuffer.waitUntilCompleted()
        try require(
            perceptualCommandBuffer.status == .completed,
            "Perceptual spatial backdrop GPU command failed: \(String(describing: perceptualCommandBuffer.error))"
        )
        let perceptualPixel = try gpu.readPrivateRgba8(texture: perceptualDestination)
        try require(
            abs(Int(perceptualPixel.x) - 188) <= 2
                && perceptualPixel.y >= 252
                && perceptualPixel.z == 0
                && perceptualPixel.w == 255,
            "Perceptual spatial backdrop mismatch: \(perceptualPixel)"
        )
        passCount += 1
        print("PASS native MetalFX SDR fast path tone-maps low-res and writes full-resolution GUI directly")

        let quadrantPixels = (0..<8).flatMap { y in
            (0..<8).map { x in
                switch (x < 4, y < 4) {
                case (true, true):
                    return SIMD4<Float>(1, 0, 0, 1)
                case (false, true):
                    return SIMD4<Float>(0, 1, 0, 1)
                case (true, false):
                    return SIMD4<Float>(0, 0, 1, 1)
                case (false, false):
                    return SIMD4<Float>(1, 1, 0, 1)
                }
            }
        }
        let quadrantSource = try gpu.makeRgba16FloatTexture(
            width: 8,
            height: 8,
            pixels: quadrantPixels
        )
        let quadrantDestination = try gpu.makePrivateRgba8Texture(width: 16, height: 16)
        try gpu.clearPrivateRgba8(
            quadrantDestination,
            color: MTLClearColor(red: 1, green: 0, blue: 1, alpha: 1)
        )
        guard let quadrantCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Perceptual quadrant command buffer creation failed")
        }
        let quadrantStatus = backdrop(
            objectPointer(quadrantCommandBuffer as AnyObject),
            objectPointer(quadrantSource as AnyObject),
            objectPointer(quadrantDestination as AnyObject),
            nil,
            nil,
            nil,
            2,
            0,
            1,
            0,
            1,
            0,
            1,
            1,
            0
        )
        try require(quadrantStatus == 3, "Perceptual quadrant backdrop ABI returned \(quadrantStatus)")
        quadrantCommandBuffer.commit()
        quadrantCommandBuffer.waitUntilCompleted()
        try require(
            quadrantCommandBuffer.status == .completed,
            "Perceptual quadrant GPU command failed: \(String(describing: quadrantCommandBuffer.error))"
        )
        let quadrantTopLeft = try gpu.readPrivateRgba8(texture: quadrantDestination, x: 0, y: 0)
        let quadrantTopRight = try gpu.readPrivateRgba8(texture: quadrantDestination, x: 15, y: 0)
        let quadrantBottomLeft = try gpu.readPrivateRgba8(texture: quadrantDestination, x: 0, y: 15)
        let quadrantBottomRight = try gpu.readPrivateRgba8(texture: quadrantDestination, x: 15, y: 15)
        try require(
            quadrantTopLeft.x > 220 && quadrantTopLeft.y < 32 && quadrantTopLeft.z < 32
                && quadrantTopRight.x < 32 && quadrantTopRight.y > 220 && quadrantTopRight.z < 32
                && quadrantBottomLeft.x < 32 && quadrantBottomLeft.y < 32 && quadrantBottomLeft.z > 220
                && quadrantBottomRight.x > 220 && quadrantBottomRight.y > 220 && quadrantBottomRight.z < 32,
            "Perceptual direct output flipped, clipped, or left a border unwritten: TL \(quadrantTopLeft), TR \(quadrantTopRight), BL \(quadrantBottomLeft), BR \(quadrantBottomRight)"
        )
        try require(
            [quadrantTopLeft, quadrantTopRight, quadrantBottomLeft, quadrantBottomRight]
                .allSatisfy { $0.w == 255 },
            "Perceptual direct output did not preserve the opaque final-composite alpha contract: TL \(quadrantTopLeft), TR \(quadrantTopRight), BL \(quadrantBottomLeft), BR \(quadrantBottomRight)"
        )
        passCount += 1
        print("PASS native MetalFX SDR direct output preserves orientation, borders and opaque composite alpha")

        let nativePrecomposeSource = try gpu.makeRgba16FloatTexture(
            width: 16,
            height: 16,
            value: SIMD4<Float>(0.5, 0.5, 0.5, 1)
        )
        let nativePrecomposeDepth = try gpu.makeDepthTexture(width: 16, height: 16, clearDepth: 0.5)
        let nativePrecomposeUi = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(0, 0, 0, 255)
        )
        guard let nativePrecomposeCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Native HDR precompose command buffer creation failed")
        }
        let nativePrecomposeStatus = backdrop(
            objectPointer(nativePrecomposeCommandBuffer as AnyObject),
            objectPointer(nativePrecomposeSource as AnyObject),
            objectPointer(nativePrecomposeUi as AnyObject),
            objectPointer(nativePrecomposeDepth as AnyObject),
            nil,
            nil,
            2,
            0,
            0,
            1,
            0,
            0,
            4,
            1,
            0
        )
        try require(
            nativePrecomposeStatus == 4,
            "Native HDR fused precompose returned \(nativePrecomposeStatus)"
        )
        let nativePrecomposePresentStatus = present(
            objectPointer(nativePrecomposeCommandBuffer as AnyObject),
            objectPointer(layer),
            objectPointer(nativePrecomposeSource as AnyObject),
            objectPointer(nativePrecomposeSource as AnyObject),
            objectPointer(nativePrecomposeDepth as AnyObject),
            nil,
            objectPointer(nativePrecomposeUi as AnyObject),
            nil,
            1,
            2,
            2,
            0,
            0,
            4,
            1,
            0
        )
        try require(
            nativePrecomposePresentStatus == 1,
            "Native HDR fused precomposed present returned \(nativePrecomposePresentStatus)"
        )
        nativePrecomposeCommandBuffer.commit()
        nativePrecomposeCommandBuffer.waitUntilCompleted()
        let nativePrecomposeDetail = nativePrecomposeCommandBuffer.error.map(String.init(describing:))
            ?? "unknown GPU error"
        try require(
            nativePrecomposeCommandBuffer.status == .completed,
            "Native HDR fused precompose GPU command failed: \(nativePrecomposeDetail)"
        )
        let nativePrecomposePixel = gpu.readRgba8(texture: nativePrecomposeUi, x: 8, y: 8)
        try require(
            nativePrecomposePixel.x >= 205 && nativePrecomposePixel.x <= 215
                && nativePrecomposePixel.y == nativePrecomposePixel.x
                && nativePrecomposePixel.z == nativePrecomposePixel.x
                && nativePrecomposePixel.w == 0,
            "Native HDR fused UI seed mismatch: \(nativePrecomposePixel)"
        )
        passCount += 1
        print("PASS native-resolution HDR reconstruction fuses the exact SDR UI seed and uses lightweight present")

        let coherentBlurUi = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(0, 0, 0, 255)
        )
        guard let coherentBlurCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Coherent HDR menu blur command buffer creation failed")
        }
        let coherentBlurBackdropStatus = backdrop(
            objectPointer(coherentBlurCommandBuffer as AnyObject),
            objectPointer(nativePrecomposeSource as AnyObject),
            objectPointer(coherentBlurUi as AnyObject),
            objectPointer(nativePrecomposeDepth as AnyObject),
            nil,
            nil,
            2,
            0,
            0,
            1,
            0,
            0,
            4,
            1,
            0
        )
        try require(
            coherentBlurBackdropStatus == 4,
            "Coherent HDR menu blur precompose returned \(coherentBlurBackdropStatus)"
        )
        let preBlurUiPass = MTLRenderPassDescriptor()
        preBlurUiPass.colorAttachments[0].texture = coherentBlurUi
        preBlurUiPass.colorAttachments[0].loadAction = .load
        preBlurUiPass.colorAttachments[0].storeAction = .store
        guard let preBlurUiEncoder = coherentBlurCommandBuffer.makeRenderCommandEncoder(
            descriptor: preBlurUiPass
        ) else {
            throw ValidationFailure.message("Pre-blur UI encoder creation failed")
        }
        preBlurUiEncoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: 16,
            height: 16,
            znear: 0,
            zfar: 1
        ))
        preBlurUiEncoder.setScissorRect(MTLScissorRect(x: 7, y: 0, width: 2, height: 16))
        gpu.encodeUiAlphaOverlay(
            encoder: preBlurUiEncoder,
            encodedSource: SIMD4<Float>(1, 0, 0, 1)
        )
        preBlurUiEncoder.endEncoding()
        let coherentBlurStatus = coherentMenuBlur(
            objectPointer(coherentBlurCommandBuffer as AnyObject),
            objectPointer(nativePrecomposeSource as AnyObject),
            objectPointer(coherentBlurUi as AnyObject),
            nil,
            2,
            4
        )
        try require(coherentBlurStatus == 1, "Coherent HDR menu blur returned \(coherentBlurStatus)")
        let coherentBlurPresentStatus = present(
            objectPointer(coherentBlurCommandBuffer as AnyObject),
            objectPointer(layer),
            objectPointer(nativePrecomposeSource as AnyObject),
            objectPointer(nativePrecomposeSource as AnyObject),
            objectPointer(nativePrecomposeDepth as AnyObject),
            nil,
            objectPointer(coherentBlurUi as AnyObject),
            nil,
            1,
            2,
            2,
            0,
            0,
            4,
            1,
            0
        )
        try require(
            coherentBlurPresentStatus == 1,
            "Coherent HDR menu blur present returned \(coherentBlurPresentStatus)"
        )
        coherentBlurCommandBuffer.commit()
        coherentBlurCommandBuffer.waitUntilCompleted()
        try require(
            coherentBlurCommandBuffer.status == .completed,
            "Coherent HDR menu blur GPU command failed: \(String(describing: coherentBlurCommandBuffer.error))"
        )
        let coherentBlurEdge = gpu.readRgba8(texture: coherentBlurUi, x: 0, y: 8)
        let coherentBlurSpread = gpu.readRgba8(texture: coherentBlurUi, x: 5, y: 8)
        let coherentBlurCenter = gpu.readRgba8(texture: coherentBlurUi, x: 8, y: 8)
        try require(
            coherentBlurCenter.x > coherentBlurEdge.x
                && coherentBlurCenter.y + 4 < coherentBlurEdge.y
                && coherentBlurCenter.z + 4 < coherentBlurEdge.z
                && coherentBlurSpread.y < coherentBlurEdge.y
                && coherentBlurEdge.w == 0
                && coherentBlurSpread.w == 0
                && coherentBlurCenter.w == 0,
            "Coherent HDR menu blur did not include and spread the pre-blur UI while preserving the exact alpha-zero seed: edge \(coherentBlurEdge), spread \(coherentBlurSpread), center \(coherentBlurCenter)"
        )
        passCount += 1
        print("PASS coherent HDR menu blur includes pre-blur UI and resolves one matching alpha-zero SDR seed")

        let precomposedPixels = (0..<8).flatMap { y in
            Array(
                repeating: SIMD4<Float>(
                    y < 4 ? 0.1 : 0.8,
                    y < 4 ? 0.1 : 0.8,
                    y < 4 ? 0.1 : 0.8,
                    1
                ),
                count: 8
            )
        }
        let precomposedSource = try gpu.makeRgba16FloatTexture(
            width: 8,
            height: 8,
            pixels: precomposedPixels
        )
        let precomposedDepth = try gpu.makeDepthTexture(width: 8, height: 8, clearDepth: 0.5)
        let precomposedUi = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(0, 0, 0, 255)
        )
        guard let precomposedCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Spatial HDR precompose command buffer creation failed")
        }
        let precomposedBackdropStatus = backdrop(
            objectPointer(precomposedCommandBuffer as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(precomposedUi as AnyObject),
            objectPointer(precomposedDepth as AnyObject),
            nil,
            nil,
            2,
            0,
            1,
            1,
            0,
            0,
            4,
            1,
            0
        )
        try require(
            precomposedBackdropStatus == 2,
            "Spatial HDR precompose backdrop returned \(precomposedBackdropStatus)"
        )
        let precomposedPresentStatus = present(
            objectPointer(precomposedCommandBuffer as AnyObject),
            objectPointer(layer),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(precomposedDepth as AnyObject),
            nil,
            objectPointer(precomposedUi as AnyObject),
            nil,
            1,
            2,
            2,
            0,
            0,
            4,
            1,
            0
        )
        try require(
            precomposedPresentStatus == 1,
            "Spatial HDR precomposed present returned \(precomposedPresentStatus)"
        )
        precomposedCommandBuffer.commit()
        precomposedCommandBuffer.waitUntilCompleted()
        let precomposedDetail = precomposedCommandBuffer.error.map(String.init(describing:))
            ?? "unknown GPU error"
        try require(
            precomposedCommandBuffer.status == .completed,
            "Spatial HDR precomposed GPU command failed: \(precomposedDetail)"
        )
        let precomposedTop = gpu.readRgba8(texture: precomposedUi, x: 8, y: 0)
        let precomposedBottom = gpu.readRgba8(texture: precomposedUi, x: 8, y: 15)
        try require(
            precomposedTop.x < 110 && precomposedBottom.x > 210,
            "Spatial raw UI seed flipped or flattened the vertical gradient: top \(precomposedTop), bottom \(precomposedBottom)"
        )
        let spatialScreenshotDestination = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(0, 0, 0, 0)
        )
        guard let spatialScreenshotCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Spatial screenshot command buffer creation failed")
        }
        let spatialScreenshotStatus = spatialScreenshot(
            objectPointer(spatialScreenshotCommandBuffer as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(precomposedUi as AnyObject),
            objectPointer(spatialScreenshotDestination as AnyObject),
            nil,
            2,
            4
        )
        try require(spatialScreenshotStatus == 1, "Spatial screenshot ABI returned \(spatialScreenshotStatus)")
        spatialScreenshotCommandBuffer.commit()
        spatialScreenshotCommandBuffer.waitUntilCompleted()
        let spatialScreenshotDetail = spatialScreenshotCommandBuffer.error.map(String.init(describing:))
            ?? "unknown GPU error"
        try require(
            spatialScreenshotCommandBuffer.status == .completed,
            "Spatial screenshot GPU command failed: \(spatialScreenshotDetail)"
        )
        let screenshotTop = gpu.readRgba8(texture: spatialScreenshotDestination, x: 8, y: 0)
        let screenshotBottom = gpu.readRgba8(texture: spatialScreenshotDestination, x: 8, y: 15)
        try require(
            screenshotTop.x < screenshotBottom.x,
            "Spatial screenshot flipped or flattened the world: top \(screenshotTop), bottom \(screenshotBottom)"
        )
        passCount += 1
        print("PASS native FP16 HDR precompose and F2 composite preserve orientation")

        let guiOverlay = SIMD4<Float>(0.85, 0.20, 0.60, 0.50)
        guard let referenceGuiCommandBuffer = backdropQueue.makeCommandBuffer() else {
            throw ValidationFailure.message("Standalone UI blend reference command buffer creation failed")
        }
        let referenceGuiPass = MTLRenderPassDescriptor()
        referenceGuiPass.colorAttachments[0].texture = precomposedUi
        referenceGuiPass.colorAttachments[0].loadAction = .load
        referenceGuiPass.colorAttachments[0].storeAction = .store
        guard let referenceGuiEncoder = referenceGuiCommandBuffer.makeRenderCommandEncoder(
            descriptor: referenceGuiPass
        ) else {
            throw ValidationFailure.message("Standalone UI blend reference encoder creation failed")
        }
        referenceGuiEncoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(precomposedUi.width),
            height: Double(precomposedUi.height),
            znear: 0,
            zfar: 1
        ))
        gpu.encodeUiAlphaOverlay(encoder: referenceGuiEncoder, encodedSource: guiOverlay)
        referenceGuiEncoder.endEncoding()
        referenceGuiCommandBuffer.commit()
        referenceGuiCommandBuffer.waitUntilCompleted()
        try require(
            referenceGuiCommandBuffer.status == .completed,
            "Standalone UI blend reference failed: \(String(describing: referenceGuiCommandBuffer.error))"
        )
        let referenceGuiBytes = try gpu.readRgba8Bytes(texture: precomposedUi)

        let fusedUi = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(255, 0, 255, 255)
        )
        guard
            let fusedCommandBuffer = backdropQueue.makeCommandBuffer(),
            let fusedFence = gpu.device.makeFence()
        else {
            throw ValidationFailure.message("Fused UI seed command resources are unavailable")
        }
        let deferredStatus = backdrop(
            objectPointer(fusedCommandBuffer as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(fusedUi as AnyObject),
            objectPointer(precomposedDepth as AnyObject),
            nil,
            objectPointer(fusedFence as AnyObject),
            2,
            0,
            1,
            1,
            0,
            1,
            4,
            1,
            0
        )
        try require(deferredStatus == 2, "Deferred spatial HDR backdrop returned \(deferredStatus)")
        let fusedPass = MTLRenderPassDescriptor()
        fusedPass.colorAttachments[0].texture = fusedUi
        fusedPass.colorAttachments[0].loadAction = .dontCare
        fusedPass.colorAttachments[0].storeAction = .store
        guard let fusedEncoder = fusedCommandBuffer.makeRenderCommandEncoder(descriptor: fusedPass) else {
            throw ValidationFailure.message("Fused UI render encoder creation failed")
        }
        fusedEncoder.waitForFence(fusedFence, before: .fragment)
        let fusedStatus = fusedBackdrop(
            objectPointer(fusedCommandBuffer as AnyObject),
            objectPointer(fusedEncoder as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(fusedUi as AnyObject),
            MTLPixelFormat.invalid.rawValue,
            MTLPixelFormat.invalid.rawValue
        )
        try require(fusedStatus == 1, "Prepared UI seed did not fuse into the GUI encoder")
        let consumedAgainStatus = fusedBackdrop(
            objectPointer(fusedCommandBuffer as AnyObject),
            objectPointer(fusedEncoder as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(fusedUi as AnyObject),
            MTLPixelFormat.invalid.rawValue,
            MTLPixelFormat.invalid.rawValue
        )
        try require(consumedAgainStatus == 0, "Prepared UI seed was consumed more than once")
        gpu.encodeUiAlphaOverlay(encoder: fusedEncoder, encodedSource: guiOverlay)
        fusedEncoder.endEncoding()
        fusedCommandBuffer.commit()
        fusedCommandBuffer.waitUntilCompleted()
        try require(
            fusedCommandBuffer.status == .completed,
            "Fused UI seed command failed: \(String(describing: fusedCommandBuffer.error))"
        )
        let fusedGuiBytes = try gpu.readRgba8Bytes(texture: fusedUi)
        try require(
            fusedGuiBytes == referenceGuiBytes,
            "Fused seed + GUI alpha blend differs from standalone 16x16 RGBA8 output"
        )

        let fusedDepthUi = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(255, 0, 255, 255)
        )
        let fusedGuiDepth = try gpu.makeDepthTexture(width: 16, height: 16, clearDepth: 0.5)
        guard
            let fusedDepthCommandBuffer = backdropQueue.makeCommandBuffer(),
            let fusedDepthFence = gpu.device.makeFence()
        else {
            throw ValidationFailure.message("Depth-compatible fused UI seed resources are unavailable")
        }
        let deferredDepthStatus = backdrop(
            objectPointer(fusedDepthCommandBuffer as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(fusedDepthUi as AnyObject),
            objectPointer(precomposedDepth as AnyObject),
            nil,
            objectPointer(fusedDepthFence as AnyObject),
            2,
            0,
            1,
            1,
            0,
            1,
            4,
            1,
            0
        )
        try require(
            deferredDepthStatus == 2,
            "Depth-compatible deferred spatial HDR backdrop returned \(deferredDepthStatus)"
        )
        let fusedDepthPass = MTLRenderPassDescriptor()
        fusedDepthPass.colorAttachments[0].texture = fusedDepthUi
        fusedDepthPass.colorAttachments[0].loadAction = .dontCare
        fusedDepthPass.colorAttachments[0].storeAction = .store
        fusedDepthPass.depthAttachment.texture = fusedGuiDepth
        fusedDepthPass.depthAttachment.loadAction = .clear
        fusedDepthPass.depthAttachment.clearDepth = 0.0
        fusedDepthPass.depthAttachment.storeAction = .store
        guard let fusedDepthEncoder = fusedDepthCommandBuffer.makeRenderCommandEncoder(
            descriptor: fusedDepthPass
        ) else {
            throw ValidationFailure.message("Depth-compatible fused UI encoder creation failed")
        }
        fusedDepthEncoder.waitForFence(fusedDepthFence, before: .fragment)
        let fusedDepthStatus = fusedBackdrop(
            objectPointer(fusedDepthCommandBuffer as AnyObject),
            objectPointer(fusedDepthEncoder as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(fusedDepthUi as AnyObject),
            MTLPixelFormat.depth32Float.rawValue,
            MTLPixelFormat.invalid.rawValue
        )
        try require(
            fusedDepthStatus == 1,
            "Prepared UI seed did not support the GUI Depth32Float attachment"
        )
        gpu.encodeUiAlphaOverlay(
            encoder: fusedDepthEncoder,
            encodedSource: guiOverlay,
            depthAttached: true
        )
        fusedDepthEncoder.endEncoding()
        fusedDepthCommandBuffer.commit()
        fusedDepthCommandBuffer.waitUntilCompleted()
        try require(
            fusedDepthCommandBuffer.status == .completed,
            "Depth-compatible fused UI seed command failed: \(String(describing: fusedDepthCommandBuffer.error))"
        )
        let fusedDepthGuiBytes = try gpu.readRgba8Bytes(texture: fusedDepthUi)
        try require(
            fusedDepthGuiBytes == referenceGuiBytes,
            "Depth-compatible fused seed + GUI blend differs from standalone 16x16 RGBA8 output"
        )
        passCount += 1
        print("PASS deferred spatial HDR seed is byte-exact in color-only and Depth32Float GUI passes")

        let materializedUi = try gpu.makeRgba8Texture(
            width: 16,
            height: 16,
            bytes: SIMD4<UInt8>(255, 0, 255, 255)
        )
        guard
            let materializedCommandBuffer = backdropQueue.makeCommandBuffer(),
            let materializedFence = gpu.device.makeFence()
        else {
            throw ValidationFailure.message("Materialized UI seed fence resources are unavailable")
        }
        let deferredMaterializedStatus = backdrop(
            objectPointer(materializedCommandBuffer as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(materializedUi as AnyObject),
            objectPointer(precomposedDepth as AnyObject),
            nil,
            objectPointer(materializedFence as AnyObject),
            2,
            0,
            1,
            1,
            0,
            1,
            4,
            1,
            0
        )
        try require(
            deferredMaterializedStatus == 2,
            "Deferred materialized UI seed returned \(deferredMaterializedStatus)"
        )
        let materializedStatus = materializePreparedBackdrop(
            objectPointer(materializedCommandBuffer as AnyObject),
            objectPointer(precomposedSource as AnyObject),
            objectPointer(materializedUi as AnyObject),
            objectPointer(materializedFence as AnyObject)
        )
        try require(materializedStatus == 1, "Prepared UI seed materialization returned \(materializedStatus)")
        let materializedGuiPass = MTLRenderPassDescriptor()
        materializedGuiPass.colorAttachments[0].texture = materializedUi
        materializedGuiPass.colorAttachments[0].loadAction = .load
        materializedGuiPass.colorAttachments[0].storeAction = .store
        guard let materializedGuiEncoder = materializedCommandBuffer.makeRenderCommandEncoder(
            descriptor: materializedGuiPass
        ) else {
            throw ValidationFailure.message("Materialized UI downstream encoder creation failed")
        }
        materializedGuiEncoder.waitForFence(materializedFence, before: .fragment)
        materializedGuiEncoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: 16,
            height: 16,
            znear: 0,
            zfar: 1
        ))
        gpu.encodeUiAlphaOverlay(
            encoder: materializedGuiEncoder,
            encodedSource: guiOverlay
        )
        materializedGuiEncoder.endEncoding()
        materializedCommandBuffer.commit()
        materializedCommandBuffer.waitUntilCompleted()
        try require(
            materializedCommandBuffer.status == .completed,
            "Materialized UI seed fence command failed: \(String(describing: materializedCommandBuffer.error))"
        )
        let materializedGuiBytes = try gpu.readRgba8Bytes(texture: materializedUi)
        try require(
            materializedGuiBytes == referenceGuiBytes,
            "Materialized seed + downstream GUI differs from the standalone output"
        )
        passCount += 1
        print("PASS prepared spatial UI seed materializes and signals the downstream fence chain")

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
            0,
            2,
            0,
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
            0,
            1,
            0,
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

        guard let menuCommandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("METALLUM menu ABI command buffer creation failed")
        }
        let menuContractBefore = generationContract(objectPointer(gpu.device as AnyObject))
        let menuStatus = present(
            objectPointer(menuCommandBuffer as AnyObject),
            objectPointer(layer),
            objectPointer(source as AnyObject),
            nil,
            nil,
            nil,
            objectPointer(ui as AnyObject),
            nil,
            0,
            2,
            0,
            1,
            0,
            4,
            1,
            0
        )
        try require(
            menuStatus == 1,
            "METALLUM menu present without world FrameState returned \(menuStatus)"
        )
        menuCommandBuffer.commit()
        menuCommandBuffer.waitUntilCompleted()
        let menuDetail = menuCommandBuffer.error.map(String.init(describing:))
            ?? "unknown GPU error"
        try require(
            menuCommandBuffer.status == .completed,
            "METALLUM menu present without world FrameState failed: \(menuDetail)"
        )

        let edrMenuSource = try gpu.makeRgba16FloatTexture(
            width: 16,
            height: 16,
            value: SIMD4<Float>(0.5, 0.5, 0.5, 1)
        )
        guard let edrMenuCommandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("METALLUM EDR menu command buffer creation failed")
        }
        let edrMenuStatus = present(
            objectPointer(edrMenuCommandBuffer as AnyObject),
            objectPointer(layer),
            objectPointer(edrMenuSource as AnyObject),
            nil,
            nil,
            nil,
            objectPointer(ui as AnyObject),
            nil,
            0,
            1,
            1,
            1,
            0,
            1,
            1,
            0
        )
        try require(
            edrMenuStatus == 1,
            "METALLUM EDR menu lost its seeded SDR UI target: \(edrMenuStatus)"
        )
        edrMenuCommandBuffer.commit()
        edrMenuCommandBuffer.waitUntilCompleted()
        try require(
            edrMenuCommandBuffer.status == .completed,
            "METALLUM EDR menu GPU command failed: \(String(describing: edrMenuCommandBuffer.error))"
        )
        let menuContractAfter = generationContract(objectPointer(gpu.device as AnyObject))
        try require(
            menuContractBefore & (1 << 9) == 0 && menuContractAfter & (1 << 9) != 0,
            "METALLUM menu present did not select the actual-HDR RGBA8 UI-only PSO"
        )
        passCount += 1
        print("PASS native present ABI selects Legacy, EDR and METALLUM RGBA8 UI-only paths without a world FrameState")

        let actualHdrPacket = rendererGenerationPacket(
            generation: 200,
            lightingMode: 1,
            outputMode: 1
        )
        let actualSetStatus = actualHdrPacket.withUnsafeBytes {
            setFrameState($0.baseAddress, UInt64($0.count))
        }
        try require(actualSetStatus == 1, "METALLUM HDR FrameState returned \(actualSetStatus)")
        let expectedActualContract: UInt64 = (1 << 0) | (1 << 1) | (1 << 4) | (1 << 5) | (1 << 9) | (1 << 10)
        let prePresentContract = generationContract(objectPointer(gpu.device as AnyObject))
        try require(
            prePresentContract == expectedActualContract,
            "METALLUM HDR UI-only prewarm contract was not exact: \(prePresentContract)"
        )

        let uiOnlyOutput = try gpu.renderActualHdrUiOnly(ui: ui)
        let uiOnlyPixel = try gpu.readRgba16Float(texture: uiOnlyOutput, x: 8, y: 8)
        let quantizedHalf = Float(128) / 255.0
        let expectedUiLinear = srgbToLinear(quantizedHalf)
        try require(
            abs(uiOnlyPixel.x - expectedUiLinear) < 0.001
                && abs(uiOnlyPixel.y - expectedUiLinear) < 0.001
                && abs(uiOnlyPixel.z - expectedUiLinear) < 0.001
                && abs(uiOnlyPixel.w - 1.0) < 0.001,
            "METALLUM HDR UI-only output did not decode sRGB once: \(rgbaDescription(uiOnlyPixel)), expected \(expectedUiLinear)"
        )
        try require(
            abs(uiOnlyPixel.x - srgbToLinear(0.5)) < 0.003,
            "Quantized 0.5 sRGB did not map near 0.214 linear: \(uiOnlyPixel.x)"
        )

        guard let actualUiOnlyCommandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("METALLUM HDR UI-only command buffer creation failed")
        }
        let actualUiOnlyStatus = present(
            objectPointer(actualUiOnlyCommandBuffer as AnyObject),
            objectPointer(layer),
            objectPointer(source as AnyObject),
            nil,
            nil,
            nil,
            objectPointer(ui as AnyObject),
            nil,
            0,
            2,
            0,
            1,
            0,
            4,
            1,
            1
        )
        try require(
            actualUiOnlyStatus == 1,
            "METALLUM HDR UI-only present returned \(actualUiOnlyStatus)"
        )
        actualUiOnlyCommandBuffer.commit()
        actualUiOnlyCommandBuffer.waitUntilCompleted()
        let actualUiOnlyDetail = actualUiOnlyCommandBuffer.error.map(String.init(describing:))
            ?? "unknown GPU error"
        try require(
            actualUiOnlyCommandBuffer.status == .completed,
            "METALLUM HDR UI-only GPU command failed: \(actualUiOnlyDetail)"
        )
        let postPresentContract = generationContract(objectPointer(gpu.device as AnyObject))
        try require(
            postPresentContract == prePresentContract && postPresentContract & (1 << 6) == 0,
            "METALLUM HDR UI-only present allocated HDR workspace/effects state: \(postPresentContract)"
        )

        let linearUiSource = try gpu.makeRgba16FloatTexture(
            width: 16,
            height: 16,
            value: SIMD4<Float>(0.214, 1.25, -0.1, 0.5)
        )
        let linearUiOnlyOutput = try gpu.renderActualHdrLinearUiOnly(source: linearUiSource)
        let linearUiOnlyPixel = try gpu.readRgba16Float(
            texture: linearUiOnlyOutput,
            x: 8,
            y: 8
        )
        try require(
            abs(linearUiOnlyPixel.x - 0.214) < 0.001
                && abs(linearUiOnlyPixel.y - 1.0) < 0.001
                && abs(linearUiOnlyPixel.z) < 0.001
                && abs(linearUiOnlyPixel.w - 1.0) < 0.001,
            "METALLUM HDR linear UI-only output changed transfer or missed SDR clamp: \(rgbaDescription(linearUiOnlyPixel))"
        )
        try require(
            abs(linearUiOnlyPixel.x - srgbToLinear(0.214)) > 0.15,
            "METALLUM HDR linear UI-only output decoded an already-linear value again: \(linearUiOnlyPixel.x)"
        )

        guard let actualLinearUiOnlyCommandBuffer = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("METALLUM HDR linear UI-only command buffer creation failed")
        }
        let actualLinearUiOnlyStatus = present(
            objectPointer(actualLinearUiOnlyCommandBuffer as AnyObject),
            objectPointer(layer),
            objectPointer(linearUiSource as AnyObject),
            nil,
            nil,
            nil,
            nil,
            nil,
            0,
            2,
            2,
            1,
            0,
            4,
            1,
            1
        )
        try require(
            actualLinearUiOnlyStatus == 1,
            "METALLUM HDR linear UI-only present returned \(actualLinearUiOnlyStatus)"
        )
        actualLinearUiOnlyCommandBuffer.commit()
        actualLinearUiOnlyCommandBuffer.waitUntilCompleted()
        let actualLinearUiOnlyDetail = actualLinearUiOnlyCommandBuffer.error
            .map(String.init(describing:)) ?? "unknown GPU error"
        try require(
            actualLinearUiOnlyCommandBuffer.status == .completed,
            "METALLUM HDR linear UI-only GPU command failed: \(actualLinearUiOnlyDetail)"
        )
        let linearPostPresentContract = generationContract(objectPointer(gpu.device as AnyObject))
        try require(
            linearPostPresentContract == prePresentContract
                && linearPostPresentContract & (1 << 6) == 0,
            "METALLUM HDR linear UI-only present allocated HDR workspace/effects state: \(linearPostPresentContract)"
        )

        passCount += 1
        print(
            String(
                format: "PASS METALLUM HDR linear UI-only present preserves 0.214 linear without effects/workspace: %.3f",
                linearUiOnlyPixel.x
            )
        )

        let actualSdrPacket = rendererGenerationPacket(
            generation: 201,
            lightingMode: 1,
            outputMode: 0
        )
        let actualSdrSetStatus = actualSdrPacket.withUnsafeBytes {
            setFrameState($0.baseAddress, UInt64($0.count))
        }
        try require(actualSdrSetStatus == 1, "METALLUM SDR FrameState returned \(actualSdrSetStatus)")
        try require(
            generationContract(objectPointer(gpu.device as AnyObject)) == 3,
            "METALLUM SDR transition retained an actual-HDR UI-only PSO"
        )
        passCount += 1
        print(
            String(
                format: "PASS METALLUM HDR UI-only present completes without HDR effects/workspace and decodes 0.5 sRGB once: %.3f linear",
                uiOnlyPixel.x
            )
        )
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
            try require(arguments.count == 3, "Usage: HdrValueValidation <shader-source-directory> <libmetallum.dylib>")
            guard let device = MTLCreateSystemDefaultDevice() else {
                throw ValidationFailure.message("No Metal device is available")
            }
            print("Metal device: \(device.name)")
            let gpu = try GpuHarness(device: device, shaderSourceDirectory: arguments[1])
            let validation = ValueValidation(gpu: gpu, nativeLibraryPath: arguments[2])
            try validation.run()
        } catch {
            fputs("HDR GPU value validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
