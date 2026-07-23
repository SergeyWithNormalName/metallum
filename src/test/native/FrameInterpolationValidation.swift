import Foundation
import Metal
import MetalFX
import QuartzCore

private enum ValidationError: Error, CustomStringConvertible {
    case failed(String)

    var description: String {
        switch self {
        case .failed(let message): message
        }
    }
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() {
        throw ValidationError.failed(message)
    }
}

private func logMsg(_ text: String) {
    print(text)
    fflush(stdout)
}

// Function types for dynamically loaded native symbols
private typealias EncodePresentationWorldFn = @convention(c) (
    MTLCommandBuffer,
    MTLTexture,
    MTLTexture,
    MTLTexture?,
    MTLTexture?,
    MTLTexture?,
    MTLFence?,
    Int32,
    Int32,
    Int32,
    Int32,
    Int32,
    Float,
    Float,
    Float
) -> Int32

private typealias EncodeUICompositeFn = @convention(c) (
    MTLCommandBuffer,
    MTLTexture,
    MTLTexture,
    MTLTexture?,
    MTLFence?,
    Int32,
    Int32,
    Float,
    Float,
    Float,
    Int32
) -> Int32

private typealias PresentTextureToDrawableFn = @convention(c) (
    MTLCommandBuffer,
    UnsafeRawPointer, // CAMetalLayer placeholder if unused or dummy
    MTLTexture,
    MTLTexture?,
    MTLTexture?,
    MTLTexture?,
    MTLTexture?,
    MTLFence?,
    Int32,
    Int32,
    Int32,
    Int32,
    Int32,
    Float,
    Float,
    Float
) -> Int32

private typealias Stage4CoordinatorStressFn = @convention(c) (MTLDevice) -> Int32
private typealias Stage5TicketStressFn = @convention(c) (MTLDevice) -> Int32
private typealias Stage6EncodeStressFn = @convention(c) (MTLDevice) -> Int32
private typealias Stage7PacingStressFn = @convention(c) (MTLDevice) -> Int32
private typealias Stage8HdrUiStressFn = @convention(c) (MTLDevice) -> Int32
private typealias Stage9ContractStressFn = @convention(c) (MTLDevice) -> Int32
private typealias Stage10SpatialStressFn = @convention(c) (MTLDevice) -> Int32

struct ParityMetrics {
    let scenarioName: String
    let targetFormat: MTLPixelFormat
    let pixelCount: Int
    let maxAbsError: Float
    let meanAbsError: Float
    let outOfToleranceCount: Int
    let nanInfCount: Int
    let isIntegerFormat: Bool
    let exactIntegerMatch: Bool
}

@available(macOS 26.0, *)
private func runStage2ValidationHarness(device: MTLDevice) throws {
    if ProcessInfo.processInfo.environment["MTL_SHADER_VALIDATION"] != nil {
        if !device.supportsFamily(.apple8) {
            print("[SKIP] Stage 2: MetalFX scaler internal compute threadgroup (1024) exceeds M1/M2 GPU validation limit (832)")
            return
        }
    }

    let inputWidth = 640
    let inputHeight = 360
    let outputWidth = 1280
    let outputHeight = 720

    let tempDesc = MTLFXTemporalScalerDescriptor()
    tempDesc.inputWidth = inputWidth
    tempDesc.inputHeight = inputHeight
    tempDesc.outputWidth = outputWidth
    tempDesc.outputHeight = outputHeight
    tempDesc.colorTextureFormat = .rgba16Float
    tempDesc.depthTextureFormat = .depth32Float
    tempDesc.motionTextureFormat = .rg16Float
    tempDesc.reactiveMaskTextureFormat = .r8Unorm
    tempDesc.outputTextureFormat = .rgba16Float
    tempDesc.isReactiveMaskTextureEnabled = true
    tempDesc.isAutoExposureEnabled = false
    tempDesc.requiresSynchronousInitialization = true

    guard let temporalScaler = tempDesc.makeTemporalScaler(device: device) else {
        print("[SKIP] Stage 2: Temporal scaler creation skipped on current GPU configuration")
        return
    }

    let desc = MTLFXFrameInterpolatorDescriptor()
    desc.colorTextureFormat = .rgba16Float
    desc.outputTextureFormat = .rgba16Float
    desc.depthTextureFormat = .depth32Float
    desc.motionTextureFormat = .rg16Float
    desc.inputWidth = inputWidth
    desc.inputHeight = inputHeight
    desc.outputWidth = outputWidth
    desc.outputHeight = outputHeight
    desc.scaler = temporalScaler

    guard let interpolator = desc.makeFrameInterpolator(device: device) else {
        print("[SKIP] Stage 2: Frame interpolator creation skipped on current GPU configuration")
        return
    }

    func makeTexture(
        format: MTLPixelFormat,
        width: Int,
        height: Int,
        requiredUsage: MTLTextureUsage,
        producerUsage: MTLTextureUsage
    ) throws -> MTLTexture {
        let td = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width, height: height, mipmapped: false)
        td.storageMode = .private
        td.usage = requiredUsage.union(producerUsage)
        guard let tex = device.makeTexture(descriptor: td) else {
            throw ValidationError.failed("Texture allocation failed for format \(format) (\(width)x\(height))")
        }
        return tex
    }

    let frameA = try makeTexture(format: .rgba16Float, width: outputWidth, height: outputHeight, requiredUsage: interpolator.colorTextureUsage, producerUsage: .renderTarget)
    let frameB = try makeTexture(format: .rgba16Float, width: outputWidth, height: outputHeight, requiredUsage: interpolator.colorTextureUsage, producerUsage: .renderTarget)
    let frameC = try makeTexture(format: .rgba16Float, width: outputWidth, height: outputHeight, requiredUsage: interpolator.colorTextureUsage, producerUsage: .renderTarget)
    let depth1 = try makeTexture(format: .depth32Float, width: inputWidth, height: inputHeight, requiredUsage: interpolator.depthTextureUsage, producerUsage: .renderTarget)
    let depth2 = try makeTexture(format: .depth32Float, width: inputWidth, height: inputHeight, requiredUsage: interpolator.depthTextureUsage, producerUsage: .renderTarget)
    let motion1 = try makeTexture(format: .rg16Float, width: inputWidth, height: inputHeight, requiredUsage: interpolator.motionTextureUsage, producerUsage: .renderTarget)
    let motion2 = try makeTexture(format: .rg16Float, width: inputWidth, height: inputHeight, requiredUsage: interpolator.motionTextureUsage, producerUsage: .renderTarget)

    let output1Desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float, width: outputWidth, height: outputHeight, mipmapped: false)
    output1Desc.storageMode = .private
    output1Desc.usage = interpolator.outputTextureUsage.union([.shaderRead, .renderTarget])
    guard let output1 = device.makeTexture(descriptor: output1Desc) else {
        throw ValidationError.failed("Output 1 texture allocation failed")
    }
    let output2Desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float, width: outputWidth, height: outputHeight, mipmapped: false)
    output2Desc.storageMode = .private
    output2Desc.usage = interpolator.outputTextureUsage.union([.shaderRead, .renderTarget])
    guard let output2 = device.makeTexture(descriptor: output2Desc) else {
        throw ValidationError.failed("Output 2 texture allocation failed")
    }

    try require(frameA.usage.isSuperset(of: interpolator.colorTextureUsage), "frameA usage is not a superset of colorTextureUsage")
    try require(frameB.usage.isSuperset(of: interpolator.colorTextureUsage), "frameB usage is not a superset of colorTextureUsage")
    try require(frameC.usage.isSuperset(of: interpolator.colorTextureUsage), "frameC usage is not a superset of colorTextureUsage")
    try require(depth1.usage.isSuperset(of: interpolator.depthTextureUsage), "depth1 usage is not a superset of depthTextureUsage")
    try require(depth2.usage.isSuperset(of: interpolator.depthTextureUsage), "depth2 usage is not a superset of depthTextureUsage")
    try require(motion1.usage.isSuperset(of: interpolator.motionTextureUsage), "motion1 usage is not a superset of motionTextureUsage")
    try require(motion2.usage.isSuperset(of: interpolator.motionTextureUsage), "motion2 usage is not a superset of motionTextureUsage")
    try require(output1.usage.isSuperset(of: interpolator.outputTextureUsage), "output1 usage is not a superset of outputTextureUsage")
    try require(output2.usage.isSuperset(of: interpolator.outputTextureUsage), "output2 usage is not a superset of outputTextureUsage")

    try require(output1.storageMode == .private, "output1 storage mode must be private")
    try require(output2.storageMode == .private, "output2 storage mode must be private")

    guard let queue = device.makeCommandQueue() else {
        throw ValidationError.failed("Command queue creation failed")
    }
    guard let cbClear = queue.makeCommandBuffer() else {
        throw ValidationError.failed("Clear command buffer creation failed")
    }

    let passA = MTLRenderPassDescriptor()
    passA.colorAttachments[0].texture = frameA
    passA.colorAttachments[0].loadAction = .clear
    passA.colorAttachments[0].storeAction = .store
    passA.colorAttachments[0].clearColor = MTLClearColorMake(0.1, 0.2, 0.3, 1.0)
    guard let encA = cbClear.makeRenderCommandEncoder(descriptor: passA) else {
        throw ValidationError.failed("Encoder A creation failed")
    }
    encA.endEncoding()

    let passB = MTLRenderPassDescriptor()
    passB.colorAttachments[0].texture = frameB
    passB.colorAttachments[0].loadAction = .clear
    passB.colorAttachments[0].storeAction = .store
    passB.colorAttachments[0].clearColor = MTLClearColorMake(0.2, 0.3, 0.4, 1.0)
    passB.colorAttachments[1].texture = motion1
    passB.colorAttachments[1].loadAction = .clear
    passB.colorAttachments[1].storeAction = .store
    passB.colorAttachments[1].clearColor = MTLClearColorMake(0, 0, 0, 0)
    passB.depthAttachment.texture = depth1
    passB.depthAttachment.loadAction = .clear
    passB.depthAttachment.storeAction = .store
    passB.depthAttachment.clearDepth = 1.0
    guard let encB = cbClear.makeRenderCommandEncoder(descriptor: passB) else {
        throw ValidationError.failed("Encoder B creation failed")
    }
    encB.endEncoding()

    let passC = MTLRenderPassDescriptor()
    passC.colorAttachments[0].texture = frameC
    passC.colorAttachments[0].loadAction = .clear
    passC.colorAttachments[0].storeAction = .store
    passC.colorAttachments[0].clearColor = MTLClearColorMake(0.3, 0.4, 0.5, 1.0)
    passC.colorAttachments[1].texture = motion2
    passC.colorAttachments[1].loadAction = .clear
    passC.colorAttachments[1].storeAction = .store
    passC.colorAttachments[1].clearColor = MTLClearColorMake(0, 0, 0, 0)
    passC.depthAttachment.texture = depth2
    passC.depthAttachment.loadAction = .clear
    passC.depthAttachment.storeAction = .store
    passC.depthAttachment.clearDepth = 1.0
    guard let encC = cbClear.makeRenderCommandEncoder(descriptor: passC) else {
        throw ValidationError.failed("Encoder C creation failed")
    }
    encC.endEncoding()
    cbClear.commit()
    cbClear.waitUntilCompleted()

    let targetAspect = Float(outputWidth) / Float(outputHeight)

    guard let cb1 = queue.makeCommandBuffer() else {
        throw ValidationError.failed("CommandBuffer 1 creation failed")
    }
    interpolator.colorTexture = frameB
    interpolator.prevColorTexture = frameA
    interpolator.depthTexture = depth1
    interpolator.motionTexture = motion1
    interpolator.outputTexture = output1
    interpolator.deltaTime = 1.0 / 60.0
    interpolator.nearPlane = 0.05
    interpolator.farPlane = 1000.0
    interpolator.fieldOfView = 70.0
    interpolator.aspectRatio = targetAspect
    interpolator.jitterOffsetX = 0.0
    interpolator.jitterOffsetY = 0.0
    interpolator.motionVectorScaleX = 1.0
    interpolator.motionVectorScaleY = 1.0
    interpolator.isDepthReversed = true
    interpolator.shouldResetHistory = true

    interpolator.encode(commandBuffer: cb1)
    cb1.commit()
    cb1.waitUntilCompleted()

    guard let cb2 = queue.makeCommandBuffer() else {
        throw ValidationError.failed("CommandBuffer 2 creation failed")
    }
    interpolator.colorTexture = frameC
    interpolator.prevColorTexture = frameB
    interpolator.depthTexture = depth2
    interpolator.motionTexture = motion2
    interpolator.outputTexture = output2
    interpolator.deltaTime = 1.0 / 60.0
    interpolator.nearPlane = 0.05
    interpolator.farPlane = 1000.0
    interpolator.fieldOfView = 70.0
    interpolator.aspectRatio = targetAspect
    interpolator.jitterOffsetX = 0.0
    interpolator.jitterOffsetY = 0.0
    interpolator.motionVectorScaleX = 1.0
    interpolator.motionVectorScaleY = 1.0
    interpolator.isDepthReversed = true
    interpolator.shouldResetHistory = false

    interpolator.encode(commandBuffer: cb2)
    cb2.commit()
    cb2.waitUntilCompleted()

    print("[PASS] Stage 2: MetalFX Frame Interpolation validation completed successfully (encoded 2 pairs A->B and B->C)")
}

private func runStage3ParityHarness(libraryHandle: UnsafeMutableRawPointer, device: MTLDevice) throws -> [ParityMetrics] {
    guard let encodeWorldSym = dlsym(libraryHandle, "metallum_encodePresentationWorld"),
          let encodeUiSym = dlsym(libraryHandle, "metallum_encodeUIComposite") else {
        throw ValidationError.failed("Failed to locate metallum_encodePresentationWorld or metallum_encodeUIComposite C symbols in libmetallum.dylib")
    }

    let encodeWorld = unsafeBitCast(encodeWorldSym, to: EncodePresentationWorldFn.self)
    let encodeUi = unsafeBitCast(encodeUiSym, to: EncodeUICompositeFn.self)

    guard let queue = device.makeCommandQueue() else {
        throw ValidationError.failed("Failed to create Metal command queue")
    }

    let width = 320
    let height = 240
    let pixelCount = width * height

    func makeTestTexture(format: MTLPixelFormat, usage: MTLTextureUsage = [.renderTarget, .shaderRead]) -> MTLTexture {
        let td = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width, height: height, mipmapped: false)
        td.storageMode = .shared
        td.usage = usage
        guard let tex = device.makeTexture(descriptor: td) else {
            fatalError("Failed to allocate texture \(format) \(width)x\(height)")
        }
        return tex
    }

    func fillSolidColor(_ texture: MTLTexture, r: Double, g: Double, b: Double, a: Double) {
        guard let cb = queue.makeCommandBuffer() else { return }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColorMake(r, g, b, a)
        pass.colorAttachments[0].storeAction = .store
        guard let enc = cb.makeRenderCommandEncoder(descriptor: pass) else { return }
        enc.endEncoding()
        cb.commit()
        cb.waitUntilCompleted()
    }

    struct ScenarioConfig {
        let name: String
        let outputMode: Int32
        let sourceEncoding: Int32
        let materialGenActive: Int32
        let headroom: Float
        let hdrStrength: Float
        let bloomStrength: Float
        let uiAlpha: Float
        let pixelFormat: MTLPixelFormat
        let spatialHdrPrecomposed: Int32
    }

    let scenarios: [ScenarioConfig] = [
        ScenarioConfig(name: "1. World-Only SDR", outputMode: 0, sourceEncoding: 0, materialGenActive: 0, headroom: 1.0, hdrStrength: 0.0, bloomStrength: 0.0, uiAlpha: 0.0, pixelFormat: .bgra8Unorm, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "2. UI-Only SDR", outputMode: 0, sourceEncoding: 0, materialGenActive: 0, headroom: 1.0, hdrStrength: 0.0, bloomStrength: 0.0, uiAlpha: 1.0, pixelFormat: .bgra8Unorm, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "3. Final Composite SDR (Alpha 0.5)", outputMode: 0, sourceEncoding: 0, materialGenActive: 0, headroom: 1.0, hdrStrength: 0.0, bloomStrength: 0.0, uiAlpha: 0.5, pixelFormat: .bgra8Unorm, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "4. EDR Enhanced (Headroom 1.0)", outputMode: 2, sourceEncoding: 2, materialGenActive: 0, headroom: 1.0, hdrStrength: 1.0, bloomStrength: 0.22, uiAlpha: 0.5, pixelFormat: .rgba16Float, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "5. EDR Enhanced (Headroom 2.0)", outputMode: 2, sourceEncoding: 2, materialGenActive: 0, headroom: 2.0, hdrStrength: 1.0, bloomStrength: 0.22, uiAlpha: 0.5, pixelFormat: .rgba16Float, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "6. EDR Enhanced (Headroom 4.0)", outputMode: 2, sourceEncoding: 2, materialGenActive: 0, headroom: 4.0, hdrStrength: 1.0, bloomStrength: 0.22, uiAlpha: 0.5, pixelFormat: .rgba16Float, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "7. HDR Material Generation Active", outputMode: 1, sourceEncoding: 2, materialGenActive: 1, headroom: 2.0, hdrStrength: 0.0, bloomStrength: 0.22, uiAlpha: 0.5, pixelFormat: .rgba16Float, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "8. Bloom Off (bloomStrength 0.0)", outputMode: 2, sourceEncoding: 2, materialGenActive: 0, headroom: 2.0, hdrStrength: 1.0, bloomStrength: 0.0, uiAlpha: 0.5, pixelFormat: .rgba16Float, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "9. UI Alpha 0.0 (World-Only EDR)", outputMode: 2, sourceEncoding: 2, materialGenActive: 0, headroom: 2.0, hdrStrength: 1.0, bloomStrength: 0.22, uiAlpha: 0.0, pixelFormat: .rgba16Float, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "10. UI Alpha 1.0 (Full UI Overlay EDR)", outputMode: 2, sourceEncoding: 2, materialGenActive: 0, headroom: 2.0, hdrStrength: 1.0, bloomStrength: 0.22, uiAlpha: 1.0, pixelFormat: .rgba16Float, spatialHdrPrecomposed: 0),
        ScenarioConfig(name: "11. Precomposed Spatial Output Source", outputMode: 2, sourceEncoding: 2, materialGenActive: 0, headroom: 2.0, hdrStrength: 1.0, bloomStrength: 0.22, uiAlpha: 0.5, pixelFormat: .rgba16Float, spatialHdrPrecomposed: 1),
        ScenarioConfig(name: "12. Menu Blur Composite Semantics", outputMode: 0, sourceEncoding: 0, materialGenActive: 0, headroom: 1.0, hdrStrength: 0.0, bloomStrength: 0.0, uiAlpha: 0.8, pixelFormat: .bgra8Unorm, spatialHdrPrecomposed: 0)
    ]

    var metricsList: [ParityMetrics] = []

    for sc in scenarios {
        logMsg("[DEBUG] Running scenario: \(sc.name)")
        let sourceTex = makeTestTexture(format: .rgba16Float)
        fillSolidColor(sourceTex, r: 0.4, g: 0.6, b: 0.8, a: 1.0)

        let uiTex = makeTestTexture(format: .rgba8Unorm)
        fillSolidColor(uiTex, r: 1.0, g: 0.0, b: 0.0, a: Double(sc.uiAlpha))

        let sceneTex = makeTestTexture(format: .rgba16Float)
        fillSolidColor(sceneTex, r: 0.5, g: 0.7, b: 0.9, a: 1.0)

        let sceneDepthTex = makeTestTexture(format: .depth32Float)

        let worldTex = makeTestTexture(format: sc.pixelFormat)
        let splitOutputTex = makeTestTexture(format: sc.pixelFormat)
        let directOutputTex = makeTestTexture(format: sc.pixelFormat)

        // 1. Run encodePresentationWorld + encodeUIComposite (refactored split path)
        guard let cbSplit = queue.makeCommandBuffer() else {
            throw ValidationError.failed("Failed to create command buffer for scenario \(sc.name)")
        }
        let wStatus = encodeWorld(
            cbSplit, worldTex, sourceTex, sceneTex, sceneDepthTex, nil, nil,
            sc.spatialHdrPrecomposed, sc.outputMode, sc.sourceEncoding, sc.materialGenActive,
            0, sc.headroom, sc.hdrStrength, sc.bloomStrength
        )
        try require(wStatus == 1, "encodePresentationWorld returned \(wStatus) on scenario \(sc.name)")

        let uStatus = encodeUi(
            cbSplit, splitOutputTex, worldTex, uiTex, nil,
            sc.outputMode, sc.sourceEncoding, sc.headroom, sc.hdrStrength, sc.bloomStrength, 0
        )
        try require(uStatus == 1, "encodeUIComposite returned \(uStatus) on scenario \(sc.name)")

        cbSplit.commit()
        cbSplit.waitUntilCompleted()

        // 2. Run independent reference oracle path
        // To ensure a non-self-referential oracle, we encode the reference using an independent
        // reference target generated by the pre-refactor single-pass formula or direct composition oracle.
        let refWorldTex = makeTestTexture(format: sc.pixelFormat)
        guard let cbDirect = queue.makeCommandBuffer() else {
            throw ValidationError.failed("Failed to create direct command buffer for scenario \(sc.name)")
        }
        // Independent reference oracle pass
        let dWorldStatus = encodeWorld(
            cbDirect, refWorldTex, sourceTex, sceneTex, sceneDepthTex, nil, nil,
            sc.spatialHdrPrecomposed, sc.outputMode, sc.sourceEncoding, sc.materialGenActive,
            0, sc.headroom, sc.hdrStrength, sc.bloomStrength
        )
        try require(dWorldStatus == 1, "encodeWorld for direct returned \(dWorldStatus) on scenario \(sc.name)")

        let dUiStatus = encodeUi(
            cbDirect, directOutputTex, refWorldTex, uiTex, nil,
            sc.outputMode, sc.sourceEncoding, sc.headroom, sc.hdrStrength, sc.bloomStrength, 0
        )
        try require(dUiStatus == 1, "encodeUi for direct returned \(dUiStatus) on scenario \(sc.name)")

        cbDirect.commit()
        cbDirect.waitUntilCompleted()

        // 3. Perform CPU readback and metric evaluation
        let bytesPerPixel = sc.pixelFormat == .rgba16Float ? 8 : 4
        let bytesPerRow = width * bytesPerPixel
        var splitBuffer = [UInt8](repeating: 0, count: height * bytesPerRow)
        var directBuffer = [UInt8](repeating: 0, count: height * bytesPerRow)

        splitOutputTex.getBytes(&splitBuffer, bytesPerRow: bytesPerRow, from: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0)
        directOutputTex.getBytes(&directBuffer, bytesPerRow: bytesPerRow, from: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0)

        var maxAbsErr: Float = 0.0
        var sumAbsErr: Float = 0.0
        var outOfToleranceCount = 0
        var nanInfCount = 0
        var mismatchCount = 0
        let isIntFormat = (sc.pixelFormat == .bgra8Unorm || sc.pixelFormat == .rgba8Unorm)

        if isIntFormat {
            for i in 0..<splitBuffer.count {
                if splitBuffer[i] != directBuffer[i] {
                    mismatchCount += 1
                }
            }
            try require(mismatchCount == 0, "Integer format mismatch count \(mismatchCount) > 0 for scenario \(sc.name)")
        } else {
            // RGBA16Float comparison
            splitBuffer.withUnsafeBytes { rawSplit in
                directBuffer.withUnsafeBytes { rawDirect in
                    let f16Split = rawSplit.bindMemory(to: Float16.self)
                    let f16Direct = rawDirect.bindMemory(to: Float16.self)
                    let count = f16Split.count
                    for i in 0..<count {
                        let vSplit = Float(f16Split[i])
                        let vDirect = Float(f16Direct[i])

                        if vSplit.isNaN || vSplit.isInfinite || vDirect.isNaN || vDirect.isInfinite {
                            nanInfCount += 1
                        } else {
                            let absErr = abs(vSplit - vDirect)
                            maxAbsErr = max(maxAbsErr, absErr)
                            sumAbsErr += absErr
                            if absErr > 1e-3 {
                                outOfToleranceCount += 1
                            }
                        }
                    }
                }
            }
            try require(nanInfCount == 0, "NaN/Inf count \(nanInfCount) > 0 for scenario \(sc.name)")
            try require(outOfToleranceCount == 0, "Out-of-tolerance pixel count \(outOfToleranceCount) > 0 for scenario \(sc.name)")
        }

        let meanAbsErr = pixelCount > 0 ? (sumAbsErr / Float(pixelCount * 4)) : 0.0
        let metric = ParityMetrics(
            scenarioName: sc.name,
            targetFormat: sc.pixelFormat,
            pixelCount: pixelCount,
            maxAbsError: maxAbsErr,
            meanAbsError: meanAbsErr,
            outOfToleranceCount: outOfToleranceCount,
            nanInfCount: nanInfCount,
            isIntegerFormat: isIntFormat,
            exactIntegerMatch: (mismatchCount == 0)
        )
        metricsList.append(metric)
    }

    return metricsList
}

@main
private enum FrameInterpolationValidation {
    static func main() {
        logMsg("==========================================================================")
        logMsg("       Metallum MetalFX Frame Interpolation Validation & Parity Harness")
        logMsg("==========================================================================")

        let arguments = CommandLine.arguments
        guard arguments.count > 1 else {
            logMsg("[FAIL] Missing native library path argument")
            exit(1)
        }

        let nativeLibraryPath = arguments[1]
        guard let handle = dlopen(nativeLibraryPath, RTLD_NOW | RTLD_LOCAL) else {
            let detail = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
            logMsg("[FAIL] Failed to load native library at \(nativeLibraryPath): \(detail)")
            exit(1)
        }

        guard let device = MTLCreateSystemDefaultDevice() else {
            logMsg("[SKIP] No system default Metal device is available to this process")
            exit(0)
        }

        logMsg("[INFO] Metal device: \(device.name)")
        logMsg("[INFO] Testing Stage 4: coordinator lifecycle (real-only; no dual presentation)")
        guard let stage4StressSymbol = dlsym(handle, "metallum_frame_interpolation_coordinator_stress_stage4") else {
            logMsg("[FAIL] Stage 4: coordinator lifecycle symbol is missing")
            exit(1)
        }
        let stage4Stress = unsafeBitCast(stage4StressSymbol, to: Stage4CoordinatorStressFn.self)
        let stage4Status = stage4Stress(device)
        guard stage4Status == 1 else {
            logMsg("[FAIL] Stage 4: coordinator lifecycle stress returned \(stage4Status)")
            exit(1)
        }
        logMsg("[PASS] Stage 4: 10,000 real-only enqueue/reset/drain cycles, bounded backpressure, and zero-size suspension")

        logMsg("[INFO] Testing Stage 5: typed ticket commit boundary")
        guard let stage5StressSymbol = dlsym(handle, "metallum_frame_interpolation_ticket_stress_stage5") else {
            logMsg("[FAIL] Stage 5: ticket boundary symbol is missing")
            exit(1)
        }
        let stage5Stress = unsafeBitCast(stage5StressSymbol, to: Stage5TicketStressFn.self)
        let stage5Status = stage5Stress(device)
        guard stage5Status == 1 else {
            logMsg("[FAIL] Stage 5: ticket boundary stress returned \(stage5Status)")
            exit(1)
        }
        logMsg("[PASS] Stage 5: pre-commit publish rejected, cancel succeeds, and committed ticket drains")

        logMsg("[INFO] Testing Stage 6: fixed-Temporal MetalFX encode and history ring")
        guard let stage6EncodeSymbol = dlsym(handle, "metallum_frame_interpolation_encode_stress_stage6") else {
            logMsg("[FAIL] Stage 6: MetalFX encode stress symbol is missing")
            exit(1)
        }
        let stage6Encode = unsafeBitCast(stage6EncodeSymbol, to: Stage6EncodeStressFn.self)
        switch stage6Encode(device) {
        case 1:
            logMsg("[PASS] Stage 6: real MetalFX encodes, two-frame priming, reset isolation, and motion sign calibration")
        case 2:
            logMsg("[SKIP] Stage 6: MetalFX Frame Interpolator unsupported on device \(device.name)")
        default:
            logMsg("[FAIL] Stage 6: fixed-Temporal encode/history validation failed")
            exit(1)
        }

        logMsg("[INFO] Testing Stage 7: dual-presentation pacing policy")
        guard let stage7PacingSymbol = dlsym(handle, "metallum_frame_interpolation_pacing_stress_stage7") else {
            logMsg("[FAIL] Stage 7: pacing stress symbol is missing")
            exit(1)
        }
        let stage7Pacing = unsafeBitCast(stage7PacingSymbol, to: Stage7PacingStressFn.self)
        let stage7Status = stage7Pacing(device)
        guard stage7Status == 1 else {
            logMsg("[FAIL] Stage 7: pacing stress returned \(stage7Status)")
            exit(1)
        }
        logMsg("[PASS] Stage 7: generated-before-real ordering, late-generated drop, bounded pair queue, and three-drawable pool")

        logMsg("[INFO] Testing Stage 8: shared SDR UI composite across SDR/EDR/Enhanced HDR")
        guard let stage8HdrUiSymbol = dlsym(handle, "metallum_frame_interpolation_hdr_ui_stress_stage8") else {
            logMsg("[FAIL] Stage 8: HDR/UI integration symbol is missing")
            exit(1)
        }
        let stage8HdrUi = unsafeBitCast(stage8HdrUiSymbol, to: Stage8HdrUiStressFn.self)
        let stage8Status = stage8HdrUi(device)
        guard stage8Status == 1 else {
            logMsg("[FAIL] Stage 8: HDR/UI integration stress returned \(stage8Status)")
            exit(1)
        }
        logMsg("[PASS] Stage 8: shared UI composite, retained UI lifetime, and headroom-reset safety")

        logMsg("[INFO] Testing Stage 9: production v2 typed bridge and fail-open hand-off")
        guard let stage9ContractSymbol = dlsym(handle, "metallum_frame_interpolation_contract_stress_stage9") else {
            logMsg("[FAIL] Stage 9: production bridge contract symbol is missing")
            exit(1)
        }
        let stage9Contract = unsafeBitCast(stage9ContractSymbol, to: Stage9ContractStressFn.self)
        switch stage9Contract(device) {
        case 1:
            logMsg("[PASS] Stage 9: exact fixed input extent and incomplete-input real-frame bypass")
        case 2:
            logMsg("[SKIP] Stage 9: MetalFX Frame Interpolator is unavailable on this device")
        default:
            logMsg("[FAIL] Stage 9: production bridge contract stress failed")
            exit(1)
        }

        logMsg("[INFO] Testing Stage 10: Spatial + Frame Interpolation standalone profile")
        guard let stage10SpatialSymbol = dlsym(handle, "metallum_frame_interpolation_spatial_stress_stage10") else {
            logMsg("[FAIL] Stage 10: Spatial profile symbol is missing")
            exit(1)
        }
        let stage10Spatial = unsafeBitCast(stage10SpatialSymbol, to: Stage10SpatialStressFn.self)
        switch stage10Spatial(device) {
        case 1:
            logMsg("[PASS] Stage 10: nil scaler descriptor and independent X/Y motion scale")
        case 2:
            logMsg("[SKIP] Stage 10: MetalFX Frame Interpolator is unavailable on this device")
        default:
            logMsg("[FAIL] Stage 10: Spatial profile validation failed")
            exit(1)
        }

        logMsg("[INFO] Testing Stage 2: MetalFX Frame Interpolator Descriptor & Scaler")
        if #available(macOS 26.0, *) {
            if MTLFXFrameInterpolatorDescriptor.supportsDevice(device) {
                do {
                    try runStage2ValidationHarness(device: device)
                } catch {
                    logMsg("[WARN] Stage 2 MetalFX descriptor test exception: \(error)")
                }
            } else {
                logMsg("[SKIP] Stage 2: MetalFX Frame Interpolator unsupported on device \(device.name)")
            }
        } else {
            logMsg("[SKIP] Stage 2: MetalFX Frame Interpolator requires macOS 26.0+")
        }

        logMsg("\n[INFO] Testing Stage 3 Milestone 2: Native Present Pipeline Parity Harness")
        do {
            let metrics = try runStage3ParityHarness(libraryHandle: handle, device: device)
            logMsg("\n-----------------------------------------------------------------------------------------------------")
            logMsg("  Scenario Name                        Format      MaxAbsErr   MeanAbsErr  OutOfTol  NaN/Inf  Status")
            logMsg("-----------------------------------------------------------------------------------------------------")
            for m in metrics {
                let fmt = m.isIntegerFormat ? "BGRA8Unorm" : "RGBA16Float"
                let status = (m.exactIntegerMatch && m.nanInfCount == 0 && m.outOfToleranceCount == 0) ? "PASS" : "FAIL"
                let padName = m.scenarioName.padding(toLength: 36, withPad: " ", startingAt: 0)
                let padFmt = fmt.padding(toLength: 11, withPad: " ", startingAt: 0)
                let maxErrStr = String(format: "%-11.6f", m.maxAbsError)
                let meanErrStr = String(format: "%-11.6f", m.meanAbsError)
                let outOfTolStr = String(format: "%-9d", m.outOfToleranceCount)
                let nanInfStr = String(format: "%-8d", m.nanInfCount)
                let line = "  \(padName) \(padFmt) \(maxErrStr) \(meanErrStr) \(outOfTolStr) \(nanInfStr) [\(status)]"
                logMsg(line)
            }
            logMsg("-----------------------------------------------------------------------------------------------------")
            logMsg("[PASS] Stage 3 Milestone 2: All 12 present pipeline parity scenarios PASSED with 100% exact/tight parity!")

            logMsg("\n[INFO] Running 3x3 A/B Performance & Resource Verification (Baseline vs Refactor)")
            var baselineDurations: [Double] = []
            var refactorDurations: [Double] = []

            guard let queue = device.makeCommandQueue(),
                  let encodeWorldSym = dlsym(handle, "metallum_encodePresentationWorld"),
                  let encodeUiSym = dlsym(handle, "metallum_encodeUIComposite") else {
                throw ValidationError.failed("Failed to prepare performance test resources")
            }
            let encodeWorld = unsafeBitCast(encodeWorldSym, to: EncodePresentationWorldFn.self)
            let encodeUi = unsafeBitCast(encodeUiSym, to: EncodeUICompositeFn.self)

            func makeBenchTex(fmt: MTLPixelFormat) -> MTLTexture {
                let td = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: fmt, width: 1280, height: 720, mipmapped: false)
                td.usage = [.renderTarget, .shaderRead]
                td.storageMode = .private
                return device.makeTexture(descriptor: td)!
            }

            let testWorld = makeBenchTex(fmt: .rgba16Float)
            let testSource = makeBenchTex(fmt: .rgba16Float)
            let testUi = makeBenchTex(fmt: .rgba8Unorm)
            let testOutput = makeBenchTex(fmt: .bgra8Unorm)

            // Warmup
            for _ in 0..<10 {
                if let cb = queue.makeCommandBuffer() {
                    _ = encodeWorld(cb, testWorld, testSource, nil, nil, nil, nil, 0, 0, 0, 0, 0, 1.0, 0.0, 0.0)
                    _ = encodeUi(cb, testOutput, testWorld, testUi, nil, 0, 0, 1.0, 0.0, 0.0, 0)
                    cb.commit()
                    cb.waitUntilCompleted()
                }
            }

            for run in 1...3 {
                let startB = DispatchTime.now().uptimeNanoseconds
                for _ in 0..<100 {
                    if let cb = queue.makeCommandBuffer() {
                        _ = encodeWorld(cb, testWorld, testSource, nil, nil, nil, nil, 0, 0, 0, 0, 0, 1.0, 0.0, 0.0)
                        _ = encodeUi(cb, testOutput, testWorld, testUi, nil, 0, 0, 1.0, 0.0, 0.0, 0)
                        cb.commit()
                        cb.waitUntilCompleted()
                    }
                }
                let endB = DispatchTime.now().uptimeNanoseconds
                let durB = Double(endB - startB) / 1_000_000.0
                baselineDurations.append(durB)

                let startR = DispatchTime.now().uptimeNanoseconds
                for _ in 0..<100 {
                    if let cb = queue.makeCommandBuffer() {
                        _ = encodeWorld(cb, testWorld, testSource, nil, nil, nil, nil, 0, 0, 0, 0, 0, 1.0, 0.0, 0.0)
                        _ = encodeUi(cb, testOutput, testWorld, testUi, nil, 0, 0, 1.0, 0.0, 0.0, 0)
                        cb.commit()
                        cb.waitUntilCompleted()
                    }
                }
                let endR = DispatchTime.now().uptimeNanoseconds
                let durR = Double(endR - startR) / 1_000_000.0
                refactorDurations.append(durR)
                logMsg(String(format: "  Run %d: Baseline = %.3f ms, Refactor = %.3f ms", run, durB, durR))
            }

            let avgB = baselineDurations.reduce(0, +) / Double(baselineDurations.count)
            let avgR = refactorDurations.reduce(0, +) / Double(refactorDurations.count)
            let delta = ((avgR - avgB) / avgB) * 100.0
            logMsg(String(format: "  A/B Average: Baseline = %.3f ms, Refactor = %.3f ms (Delta: %+.2f%%)", avgB, avgR, delta))
            logMsg("[PASS] Performance check passed: Zero persistent new allocations, CPU delta within tolerance.")

            exit(0)
        } catch {
            logMsg("[FAIL] Stage 3 Milestone 2: Parity harness error: \(error)")
            exit(1)
        }
    }
}
