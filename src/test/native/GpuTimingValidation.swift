import Foundation
import Metal

private enum ValidationFailure: Error, CustomStringConvertible {
    case failed(String)

    var description: String {
        switch self {
        case .failed(let message): message
        }
    }
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() {
        throw ValidationFailure.failed(message)
    }
}

private func timestampSet(for device: MTLDevice) -> MTLCounterSet? {
    device.counterSets?.first(where: { set in
        set.name == MTLCommonCounterSet.timestamp.rawValue
            && set.counters.contains(where: { $0.name == MTLCommonCounter.timestamp.rawValue })
    })
}

private func attachRenderSamples(
    _ descriptor: MTLRenderPassDescriptor,
    buffer: MTLCounterSampleBuffer,
    start: Int,
    end: Int
) {
    let attachment = descriptor.sampleBufferAttachments[0]!
    attachment.sampleBuffer = buffer
    attachment.startOfVertexSampleIndex = start
    attachment.endOfVertexSampleIndex = MTLCounterDontSample
    attachment.startOfFragmentSampleIndex = MTLCounterDontSample
    attachment.endOfFragmentSampleIndex = end
}

private func attachComputeSamples(
    _ descriptor: MTLComputePassDescriptor,
    buffer: MTLCounterSampleBuffer,
    start: Int,
    end: Int
) {
    let attachment = descriptor.sampleBufferAttachments[0]!
    attachment.sampleBuffer = buffer
    attachment.startOfEncoderSampleIndex = start
    attachment.endOfEncoderSampleIndex = end
}

private func attachBlitSamples(
    _ descriptor: MTLBlitPassDescriptor,
    buffer: MTLCounterSampleBuffer,
    start: Int,
    end: Int
) {
    let attachment = descriptor.sampleBufferAttachments[0]!
    attachment.sampleBuffer = buffer
    attachment.startOfEncoderSampleIndex = start
    attachment.endOfEncoderSampleIndex = end
}

private func makeLibrary(device: MTLDevice) throws -> MTLLibrary {
    try device.makeLibrary(source: """
    #include <metal_stdlib>
    using namespace metal;

    vertex float4 timing_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)
      };
      return float4(positions[vertexId], 0.0, 1.0);
    }

    fragment float4 timing_fs() {
      return float4(0.25, 0.5, 0.75, 1.0);
    }

    kernel void timing_cs(device uint* output [[buffer(0)]], uint index [[thread_position_in_grid]]) {
      output[index] = index * 1664525u + 1013904223u;
    }
    """, options: nil)
}

private func runValidation() throws {
    guard let device = MTLCreateSystemDefaultDevice() else {
        print("SKIP GPU timing validation: no Metal device")
        return
    }
    guard device.supportsCounterSampling(.atStageBoundary),
          let counterSet = timestampSet(for: device) else {
        print("SKIP GPU timing validation: timestamp stage sampling is unsupported")
        return
    }
    let sampleDescriptor = MTLCounterSampleBufferDescriptor()
    sampleDescriptor.counterSet = counterSet
    sampleDescriptor.label = "Metallum GPU timing validation"
    sampleDescriptor.storageMode = .shared
    sampleDescriptor.sampleCount = 8
    let sampleBuffer = try device.makeCounterSampleBuffer(descriptor: sampleDescriptor)

    guard let queue = device.makeCommandQueue(), let commandBuffer = queue.makeCommandBuffer() else {
        throw ValidationFailure.failed("failed to create Metal command queue/buffer")
    }
    commandBuffer.label = "Metallum GPU timing validation"
    let startTimes = device.sampleTimestamps()

    let library = try makeLibrary(device: device)
    guard let vertex = library.makeFunction(name: "timing_vs"),
          let fragment = library.makeFunction(name: "timing_fs"),
          let compute = library.makeFunction(name: "timing_cs") else {
        throw ValidationFailure.failed("failed to load timing validation functions")
    }
    let renderPipelineDescriptor = MTLRenderPipelineDescriptor()
    renderPipelineDescriptor.vertexFunction = vertex
    renderPipelineDescriptor.fragmentFunction = fragment
    renderPipelineDescriptor.colorAttachments[0].pixelFormat = .rgba8Unorm
    let renderPipeline = try device.makeRenderPipelineState(descriptor: renderPipelineDescriptor)
    let computePipeline = try device.makeComputePipelineState(function: compute)

    let textureDescriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .rgba8Unorm,
        width: 1024,
        height: 1024,
        mipmapped: false
    )
    textureDescriptor.storageMode = .private
    textureDescriptor.usage = [.renderTarget]
    guard let target = device.makeTexture(descriptor: textureDescriptor) else {
        throw ValidationFailure.failed("failed to allocate timing render target")
    }
    let renderPass = MTLRenderPassDescriptor()
    renderPass.colorAttachments[0].texture = target
    renderPass.colorAttachments[0].loadAction = .dontCare
    renderPass.colorAttachments[0].storeAction = .store
    attachRenderSamples(renderPass, buffer: sampleBuffer, start: 0, end: 1)
    guard let render = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
        throw ValidationFailure.failed("failed to create timestamped render pass")
    }
    render.setRenderPipelineState(renderPipeline)
    render.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    render.endEncoding()

    let elementCount = 1_048_576
    guard let workBuffer = device.makeBuffer(
        length: elementCount * MemoryLayout<UInt32>.stride,
        options: .storageModePrivate
    ) else {
        throw ValidationFailure.failed("failed to allocate timing work buffer")
    }
    let computePass = MTLComputePassDescriptor()
    attachComputeSamples(computePass, buffer: sampleBuffer, start: 2, end: 3)
    guard let computeEncoder = commandBuffer.makeComputeCommandEncoder(descriptor: computePass) else {
        throw ValidationFailure.failed("failed to create timestamped compute pass")
    }
    computeEncoder.setComputePipelineState(computePipeline)
    computeEncoder.setBuffer(workBuffer, offset: 0, index: 0)
    computeEncoder.dispatchThreads(
        MTLSize(width: elementCount, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(width: computePipeline.threadExecutionWidth, height: 1, depth: 1)
    )
    computeEncoder.endEncoding()

    let blitPass = MTLBlitPassDescriptor()
    attachBlitSamples(blitPass, buffer: sampleBuffer, start: 4, end: 5)
    guard let blit = commandBuffer.makeBlitCommandEncoder(descriptor: blitPass) else {
        throw ValidationFailure.failed("failed to create timestamped blit pass")
    }
    blit.fill(buffer: workBuffer, range: 0..<workBuffer.length, value: 0x5a)
    blit.endEncoding()

    let markerStartPass = MTLBlitPassDescriptor()
    let markerStartAttachment = markerStartPass.sampleBufferAttachments[0]!
    markerStartAttachment.sampleBuffer = sampleBuffer
    markerStartAttachment.startOfEncoderSampleIndex = MTLCounterDontSample
    markerStartAttachment.endOfEncoderSampleIndex = 6
    guard let markerStart = commandBuffer.makeBlitCommandEncoder(descriptor: markerStartPass) else {
        throw ValidationFailure.failed("failed to create MetalFX-style start marker")
    }
    markerStart.fill(buffer: workBuffer, range: 0..<4, value: 0)
    markerStart.endEncoding()

    guard let externalWork = commandBuffer.makeBlitCommandEncoder() else {
        throw ValidationFailure.failed("failed to create external marker work pass")
    }
    externalWork.fill(buffer: workBuffer, range: 0..<workBuffer.length, value: 0xa5)
    externalWork.endEncoding()

    let markerEndPass = MTLBlitPassDescriptor()
    let markerEndAttachment = markerEndPass.sampleBufferAttachments[0]!
    markerEndAttachment.sampleBuffer = sampleBuffer
    markerEndAttachment.startOfEncoderSampleIndex = 7
    markerEndAttachment.endOfEncoderSampleIndex = MTLCounterDontSample
    guard let markerEnd = commandBuffer.makeBlitCommandEncoder(descriptor: markerEndPass) else {
        throw ValidationFailure.failed("failed to create MetalFX-style end marker")
    }
    markerEnd.fill(buffer: workBuffer, range: 0..<4, value: 1)
    markerEnd.endEncoding()

    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try require(commandBuffer.status == .completed, "timing validation command buffer failed: \(String(describing: commandBuffer.error))")
    let finalTimes = device.sampleTimestamps()
    guard let resolved = try sampleBuffer.resolveCounterRange(0..<8) else {
        throw ValidationFailure.failed("failed to resolve timestamp samples")
    }
    try require(resolved.count >= 8 * MemoryLayout<MTLCounterResultTimestamp>.stride, "resolved timestamp data is too short")

    let timestamps = resolved.withUnsafeBytes { rawBytes in
        Array(rawBytes.bindMemory(to: MTLCounterResultTimestamp.self).prefix(8).map(\.timestamp))
    }
    for (index, timestamp) in timestamps.enumerated() {
        try require(timestamp != 0, "timestamp \(index) is zero")
        try require(timestamp != MTLCounterErrorValue, "timestamp \(index) contains MTLCounterErrorValue")
    }
    for pairStart in stride(from: 0, to: 6, by: 2) {
        try require(timestamps[pairStart + 1] > timestamps[pairStart], "timestamp pair \(pairStart / 2) is not positive")
    }
    try require(timestamps[7] > timestamps[6], "cross-pass external interval is not positive")

    let cpuSpan = Double(UInt64(finalTimes.cpu) - UInt64(startTimes.cpu))
    let gpuSpan = Double(UInt64(finalTimes.gpu) - UInt64(startTimes.gpu))
    try require(cpuSpan > 0.0 && gpuSpan > 0.0, "timestamp calibration span is invalid")
    let durations = stride(from: 0, to: 6, by: 2).map { index in
        Double(timestamps[index + 1] - timestamps[index]) / gpuSpan * cpuSpan
    }
    let externalDuration = Double(timestamps[7] - timestamps[6]) / gpuSpan * cpuSpan
    try require(durations.allSatisfy { $0.isFinite && $0 > 0.0 }, "calibrated GPU durations are invalid")
    try require(externalDuration.isFinite && externalDuration > 0.0, "calibrated external GPU duration is invalid")
    print(String(
        format: "PASS GPU counter samples: render %.3f ms, compute %.3f ms, blit %.3f ms, external %.3f ms",
        durations[0] / 1_000_000.0,
        durations[1] / 1_000_000.0,
        durations[2] / 1_000_000.0,
        externalDuration / 1_000_000.0
    ))
}

@main
private enum GpuTimingValidation {
    static func main() {
        do {
            try runValidation()
        } catch {
            fputs("FAIL GPU timing validation: \(error)\n", stderr)
            exit(1)
        }
    }
}
