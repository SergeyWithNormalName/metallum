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

private func validatePreset(
    device: MTLDevice,
    inputWidth: Int,
    inputHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    label: String
) throws {
    let descriptor = MTLFXSpatialScalerDescriptor()
    descriptor.inputWidth = inputWidth
    descriptor.inputHeight = inputHeight
    descriptor.outputWidth = outputWidth
    descriptor.outputHeight = outputHeight
    descriptor.colorTextureFormat = .rgba16Float
    descriptor.outputTextureFormat = .rgba16Float
    descriptor.colorProcessingMode = .hdr
    guard let scaler = descriptor.makeSpatialScaler(device: device) else {
        throw ValidationError.failed("\(label): scaler creation failed")
    }

    let inputDescriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .rgba16Float,
        width: inputWidth,
        height: inputHeight,
        mipmapped: false
    )
    inputDescriptor.storageMode = .private
    inputDescriptor.usage = scaler.colorTextureUsage.union(.shaderRead)
    guard let input = device.makeTexture(descriptor: inputDescriptor) else {
        throw ValidationError.failed("\(label): input allocation failed")
    }

    let outputDescriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .rgba16Float,
        width: outputWidth,
        height: outputHeight,
        mipmapped: false
    )
    outputDescriptor.storageMode = .private
    outputDescriptor.usage = scaler.outputTextureUsage.union(.shaderRead)
    guard let output = device.makeTexture(descriptor: outputDescriptor) else {
        throw ValidationError.failed("\(label): output allocation failed")
    }
    try require(output.storageMode == .private, "\(label): MetalFX output is not private")
    try require(input.usage.isSuperset(of: scaler.colorTextureUsage), "\(label): input usage mismatch")
    try require(output.usage.isSuperset(of: scaler.outputTextureUsage), "\(label): output usage mismatch")

    let inputRowBytes = inputWidth * 8
    let outputRowBytes = outputWidth * 8
    var source = [Float16](repeating: 0, count: inputWidth * inputHeight * 4)
    for y in 0..<inputHeight {
        for x in 0..<inputWidth {
            let index = (y * inputWidth + x) * 4
            source[index] = Float16(Float(x) / Float(max(inputWidth - 1, 1)) * 2.0)
            source[index + 1] = Float16(Float(y) / Float(max(inputHeight - 1, 1)) * 1.5)
            source[index + 2] = Float16((x + y).isMultiple(of: 2) ? 0.25 : 0.75)
            source[index + 3] = 1.0
        }
    }
    guard let upload = source.withUnsafeBytes({ bytes in
        device.makeBuffer(bytes: bytes.baseAddress!, length: bytes.count, options: .storageModeShared)
    }), let readback = device.makeBuffer(
        length: outputRowBytes * outputHeight,
        options: .storageModeShared
    ), let queue = device.makeCommandQueue(), let commandBuffer = queue.makeCommandBuffer() else {
        throw ValidationError.failed("\(label): staging allocation failed")
    }

    guard let uploadEncoder = commandBuffer.makeBlitCommandEncoder() else {
        throw ValidationError.failed("\(label): upload encoder failed")
    }
    uploadEncoder.copy(
        from: upload,
        sourceOffset: 0,
        sourceBytesPerRow: inputRowBytes,
        sourceBytesPerImage: inputRowBytes * inputHeight,
        sourceSize: MTLSize(width: inputWidth, height: inputHeight, depth: 1),
        to: input,
        destinationSlice: 0,
        destinationLevel: 0,
        destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0)
    )
    uploadEncoder.endEncoding()

    scaler.colorTexture = input
    scaler.outputTexture = output
    scaler.inputContentWidth = inputWidth
    scaler.inputContentHeight = inputHeight
    scaler.encode(commandBuffer: commandBuffer)

    guard let downloadEncoder = commandBuffer.makeBlitCommandEncoder() else {
        throw ValidationError.failed("\(label): download encoder failed")
    }
    downloadEncoder.copy(
        from: output,
        sourceSlice: 0,
        sourceLevel: 0,
        sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
        sourceSize: MTLSize(width: outputWidth, height: outputHeight, depth: 1),
        to: readback,
        destinationOffset: 0,
        destinationBytesPerRow: outputRowBytes,
        destinationBytesPerImage: outputRowBytes * outputHeight
    )
    downloadEncoder.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try require(commandBuffer.status == .completed, "\(label): command buffer failed: \(String(describing: commandBuffer.error))")

    let pixels = readback.contents().bindMemory(
        to: Float16.self,
        capacity: outputWidth * outputHeight * 4
    )
    var maximum: Float = 0
    var topGreen: Float = 0
    var bottomGreen: Float = 0
    var leftRed: Float = 0
    var rightRed: Float = 0
    for y in 0..<outputHeight {
        for x in 0..<outputWidth {
            let index = (y * outputWidth + x) * 4
            let red = Float(pixels[index])
            let green = Float(pixels[index + 1])
            let blue = Float(pixels[index + 2])
            let alpha = Float(pixels[index + 3])
            try require(red.isFinite && green.isFinite && blue.isFinite && alpha.isFinite, "\(label): non-finite output")
            try require(alpha > 0.9, "\(label): transparent or uninitialized border")
            maximum = max(maximum, red, green, blue)
            if y < outputHeight / 4 { topGreen += green }
            if y >= outputHeight * 3 / 4 { bottomGreen += green }
            if x < outputWidth / 4 { leftRed += red }
            if x >= outputWidth * 3 / 4 { rightRed += red }
        }
    }
    try require(maximum > 1.05, "\(label): HDR range was clipped")
    try require(bottomGreen > topGreen, "\(label): vertical orientation/crop mismatch")
    try require(rightRed > leftRed, "\(label): horizontal orientation/crop mismatch")
    print("MetalFX \(label): \(inputWidth)x\(inputHeight) -> \(outputWidth)x\(outputHeight), max=\(maximum)")
}

@main
private enum SpatialScalingValidation {
    static func main() throws {
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw ValidationError.failed("No Metal device")
        }
        try require(MTLFXSpatialScalerDescriptor.supportsDevice(device), "MetalFX spatial scaling unsupported")
        try validatePreset(device: device, inputWidth: 192, inputHeight: 108, outputWidth: 256, outputHeight: 144, label: "Quality")
        try validatePreset(device: device, inputWidth: 128, inputHeight: 72, outputWidth: 256, outputHeight: 144, label: "Performance")
    }
}
