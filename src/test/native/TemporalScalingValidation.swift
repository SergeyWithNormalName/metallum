import Foundation
import Metal
import MetalFX

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

@available(macOS 14.4, *)
private func validatePreset(
    device: MTLDevice,
    inputWidth: Int,
    inputHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    label: String
) throws {
    let descriptor = MTLFXTemporalScalerDescriptor()
    descriptor.inputWidth = inputWidth
    descriptor.inputHeight = inputHeight
    descriptor.outputWidth = outputWidth
    descriptor.outputHeight = outputHeight
    descriptor.colorTextureFormat = .rgba16Float
    descriptor.depthTextureFormat = .depth32Float
    descriptor.motionTextureFormat = .rg16Float
    descriptor.reactiveMaskTextureFormat = .r8Unorm
    descriptor.outputTextureFormat = .rgba16Float
    descriptor.isReactiveMaskTextureEnabled = true
    descriptor.isAutoExposureEnabled = false
    descriptor.requiresSynchronousInitialization = true
    guard let scaler = descriptor.makeTemporalScaler(device: device) else {
        throw ValidationError.failed("\(label): scaler creation failed")
    }

    func texture(_ format: MTLPixelFormat, _ usage: MTLTextureUsage) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format, width: inputWidth, height: inputHeight, mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = usage.union(.renderTarget)
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw ValidationError.failed("\(label): \(format) allocation failed")
        }
        return texture
    }

    let color = try texture(.rgba16Float, scaler.colorTextureUsage)
    let depth = try texture(.depth32Float, scaler.depthTextureUsage)
    let motion = try texture(.rg16Float, scaler.motionTextureUsage)
    let reactive = try texture(.r8Unorm, scaler.reactiveTextureUsage)
    let outputDescriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .rgba16Float, width: outputWidth, height: outputHeight, mipmapped: false
    )
    outputDescriptor.storageMode = .private
    outputDescriptor.usage = scaler.outputTextureUsage.union([.shaderRead, .renderTarget])
    guard let output = device.makeTexture(descriptor: outputDescriptor),
          let queue = device.makeCommandQueue(),
          let commandBuffer = queue.makeCommandBuffer() else {
        throw ValidationError.failed("\(label): command setup failed")
    }
    try require(color.usage.isSuperset(of: scaler.colorTextureUsage), "\(label): color usage mismatch")
    try require(depth.usage.isSuperset(of: scaler.depthTextureUsage), "\(label): depth usage mismatch")
    try require(motion.usage.isSuperset(of: scaler.motionTextureUsage), "\(label): motion usage mismatch")
    try require(reactive.usage.isSuperset(of: scaler.reactiveTextureUsage), "\(label): reactive usage mismatch")
    try require(output.usage.isSuperset(of: scaler.outputTextureUsage), "\(label): output usage mismatch")

    let source = [Float16](repeating: 0.5, count: inputWidth * inputHeight * 4)
    let rowBytes = inputWidth * 8
    guard let upload = source.withUnsafeBytes({
        device.makeBuffer(bytes: $0.baseAddress!, length: $0.count, options: .storageModeShared)
    }), let uploadEncoder = commandBuffer.makeBlitCommandEncoder() else {
        throw ValidationError.failed("\(label): color upload setup failed")
    }
    uploadEncoder.copy(
        from: upload,
        sourceOffset: 0,
        sourceBytesPerRow: rowBytes,
        sourceBytesPerImage: rowBytes * inputHeight,
        sourceSize: MTLSize(width: inputWidth, height: inputHeight, depth: 1),
        to: color,
        destinationSlice: 0,
        destinationLevel: 0,
        destinationOrigin: .init()
    )
    uploadEncoder.endEncoding()

    let clear = MTLRenderPassDescriptor()
    clear.colorAttachments[0].texture = motion
    clear.colorAttachments[0].loadAction = .clear
    clear.colorAttachments[0].storeAction = .store
    clear.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0)
    clear.colorAttachments[1].texture = reactive
    clear.colorAttachments[1].loadAction = .clear
    clear.colorAttachments[1].storeAction = .store
    clear.colorAttachments[1].clearColor = MTLClearColorMake(0, 0, 0, 0)
    clear.depthAttachment.texture = depth
    clear.depthAttachment.loadAction = .clear
    clear.depthAttachment.storeAction = .store
    clear.depthAttachment.clearDepth = 1.0
    guard let clearEncoder = commandBuffer.makeRenderCommandEncoder(descriptor: clear) else {
        throw ValidationError.failed("\(label): input clear failed")
    }
    clearEncoder.endEncoding()

    scaler.colorTexture = color
    scaler.depthTexture = depth
    scaler.motionTexture = motion
    scaler.reactiveMaskTexture = reactive
    scaler.outputTexture = output
    scaler.inputContentWidth = inputWidth
    scaler.inputContentHeight = inputHeight
    scaler.preExposure = 1.0
    scaler.jitterOffsetX = 0.0
    scaler.jitterOffsetY = 0.0
    scaler.motionVectorScaleX = 1.0
    scaler.motionVectorScaleY = 1.0
    scaler.isDepthReversed = true
    scaler.reset = true
    scaler.encode(commandBuffer: commandBuffer)
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try require(commandBuffer.status == .completed,
                "\(label): command buffer failed: \(String(describing: commandBuffer.error))")
    print("MetalFX Temporal \(label): \(inputWidth)x\(inputHeight) -> \(outputWidth)x\(outputHeight)")
}

@main
private enum TemporalScalingValidation {
    static func main() throws {
        guard #available(macOS 14.4, *) else {
            throw ValidationError.failed("MetalFX Temporal requires macOS 14.4 or newer")
        }
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw ValidationError.failed("No Metal device")
        }
        try require(MTLFXTemporalScalerDescriptor.supportsDevice(device), "MetalFX Temporal unsupported")
        // 288x162 lets every exposed scale resolve exactly: 2/3, 1/2 and 1/3.
        try validatePreset(device: device, inputWidth: 192, inputHeight: 108,
                           outputWidth: 288, outputHeight: 162, label: "Quality")
        try validatePreset(device: device, inputWidth: 144, inputHeight: 81,
                           outputWidth: 288, outputHeight: 162, label: "Performance")
        try validatePreset(device: device, inputWidth: 96, inputHeight: 54,
                           outputWidth: 288, outputHeight: 162, label: "Ultra Performance")
    }
}
