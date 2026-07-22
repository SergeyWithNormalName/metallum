import Foundation
import Metal
import simd

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)
    var description: String { switch self { case let .message(message): message } }
}

private struct MotionInput {
    var currentClip: SIMD4<Float>
    var previousClip: SIMD4<Float>
    var invalidDepth: UInt32 = 0
    var reserved: UInt32 = 0
}

private struct MotionOutput {
    var motion: SIMD2<Float>
    var reactive: Float
    var reserved: Float
}

private struct ReprojectionInput {
    var pixelCoord: SIMD2<Float>
    var depth: Float
    var reserved: UInt32 = 0
}

private struct MetallumTemporalDiagnosticUniforms {
    var currentView: simd_float4x4
    var currentProjection: simd_float4x4
    var inverseCurrentView: simd_float4x4
    var inverseCurrentProjection: simd_float4x4
    var previousView: simd_float4x4
    var previousProjection: simd_float4x4
    var currentCameraPosition: SIMD4<Float>
    var previousCameraPosition: SIMD4<Float>
    var renderExtent: SIMD2<Float>
    var jitter: SIMD2<Float>
    var previousJitter: SIMD2<Float>
    var reserved_padding: SIMD2<Float> = .zero
    var resetMask: UInt32
    var previousDepthValid: UInt32
    var reserved0: UInt32 = 0
    var reserved1: UInt32 = 0
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw ValidationFailure.message(message) }
}

private func dispatch(
    device: MTLDevice,
    queue: MTLCommandQueue,
    pipeline: MTLComputePipelineState,
    inputs: [MotionInput],
    extent: SIMD2<Float>,
    resetMask: UInt32,
    jitter: SIMD2<Float> = .zero,
    prevJitter: SIMD2<Float> = .zero
) throws -> [MotionOutput] {
    let inputBytes = inputs.count * MemoryLayout<MotionInput>.stride
    let outputBytes = inputs.count * MemoryLayout<MotionOutput>.stride
    guard let inputBuffer = device.makeBuffer(bytes: inputs, length: inputBytes, options: .storageModeShared),
          let outputBuffer = device.makeBuffer(length: outputBytes, options: .storageModeShared),
          let commandBuffer = queue.makeCommandBuffer(),
          let encoder = commandBuffer.makeComputeCommandEncoder() else {
        throw ValidationFailure.message("Could not allocate motion validation resources")
    }
    var extentValue = extent
    var resetValue = resetMask
    var jitterValue = jitter
    var prevJitterValue = prevJitter
    encoder.setComputePipelineState(pipeline)
    encoder.setBuffer(inputBuffer, offset: 0, index: 0)
    encoder.setBuffer(outputBuffer, offset: 0, index: 1)
    encoder.setBytes(&extentValue, length: MemoryLayout<SIMD2<Float>>.stride, index: 2)
    encoder.setBytes(&resetValue, length: MemoryLayout<UInt32>.stride, index: 3)
    encoder.setBytes(&jitterValue, length: MemoryLayout<SIMD2<Float>>.stride, index: 4)
    encoder.setBytes(&prevJitterValue, length: MemoryLayout<SIMD2<Float>>.stride, index: 5)
    encoder.dispatchThreads(
        MTLSize(width: inputs.count, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(width: min(inputs.count, pipeline.maxTotalThreadsPerThreadgroup), height: 1, depth: 1)
    )
    encoder.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try require(commandBuffer.status == .completed, "Motion validation command buffer failed")
    let ptr = outputBuffer.contents().bindMemory(to: MotionOutput.self, capacity: inputs.count)
    return Array(UnsafeBufferPointer(start: ptr, count: inputs.count))
}

private func dispatchReprojection(
    device: MTLDevice,
    queue: MTLCommandQueue,
    pipeline: MTLComputePipelineState,
    inputs: [ReprojectionInput],
    uniforms: MetallumTemporalDiagnosticUniforms
) throws -> [MotionOutput] {
    let inputBytes = inputs.count * MemoryLayout<ReprojectionInput>.stride
    let outputBytes = inputs.count * MemoryLayout<MotionOutput>.stride
    let uniformBytes = MemoryLayout<MetallumTemporalDiagnosticUniforms>.stride
    guard let inputBuffer = device.makeBuffer(bytes: inputs, length: inputBytes, options: .storageModeShared),
          let outputBuffer = device.makeBuffer(length: outputBytes, options: .storageModeShared),
          let uniformBuffer = device.makeBuffer(bytes: [uniforms], length: uniformBytes, options: .storageModeShared),
          let commandBuffer = queue.makeCommandBuffer(),
          let encoder = commandBuffer.makeComputeCommandEncoder() else {
        throw ValidationFailure.message("Could not allocate reprojection validation resources")
    }
    encoder.setComputePipelineState(pipeline)
    encoder.setBuffer(inputBuffer, offset: 0, index: 0)
    encoder.setBuffer(outputBuffer, offset: 0, index: 1)
    encoder.setBuffer(uniformBuffer, offset: 0, index: 2)
    encoder.dispatchThreads(
        MTLSize(width: inputs.count, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(width: min(inputs.count, pipeline.maxTotalThreadsPerThreadgroup), height: 1, depth: 1)
    )
    encoder.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    let ptr = outputBuffer.contents().bindMemory(to: MotionOutput.self, capacity: inputs.count)
    return Array(UnsafeBufferPointer(start: ptr, count: inputs.count))
}

private func validate(
    _ output: MotionOutput,
    expected: SIMD2<Float>,
    reactive: Float,
    name: String
) throws {
    try require(output.motion.x.isFinite && output.motion.y.isFinite && output.reactive.isFinite,
                "\(name) emitted NaN/Inf")
    try require(abs(output.motion.x - expected.x) <= 0.01
                    && abs(output.motion.y - expected.y) <= 0.01,
                "\(name) motion \(output.motion) != \(expected)")
    try require(abs(output.reactive - reactive) <= 0.0001,
                "\(name) reactive \(output.reactive) != \(reactive)")
}

@main
private enum MotionVectorValidationMain {
    static func main() {
        do {
            try require(CommandLine.arguments.count == 2,
                        "Usage: MotionVectorValidation <metallum.metallib>")
            guard let device = MTLCreateSystemDefaultDevice(),
                  let queue = device.makeCommandQueue() else {
                throw ValidationFailure.message("No Metal device/queue is available")
            }
            let library = try device.makeLibrary(URL: URL(fileURLWithPath: CommandLine.arguments[1]))
            guard let function = library.makeFunction(name: "metallum_motion_vector_validate") else {
                throw ValidationFailure.message("Motion validation kernel is absent from generated metallib")
            }
            let pipeline = try device.makeComputePipelineState(function: function)
            let inputs = [
                MotionInput(currentClip: SIMD4(0, 0, 0.5, 1), previousClip: SIMD4(0, 0, 0.5, 1)),
                MotionInput(currentClip: SIMD4(0, 0, 0.5, 1), previousClip: SIMD4(0.1, 0, 0.5, 1)),
                MotionInput(currentClip: SIMD4(0, 0, 0.5, 1), previousClip: SIMD4(-0.1, 0, 0.5, 1)),
                MotionInput(currentClip: SIMD4(0, 0, 0.5, 1), previousClip: SIMD4(0, 0.1, 0.5, 1)),
                MotionInput(currentClip: SIMD4(0.4, 0, 0.5, 2), previousClip: SIMD4(0.2, 0, 0.5, 2)),
                MotionInput(currentClip: SIMD4(0, 0, 0.5, 1), previousClip: SIMD4(0, 0.5, 0.5, 1), invalidDepth: 1),
                MotionInput(currentClip: SIMD4(.nan, 0, 0.5, 1), previousClip: SIMD4(0, 0, 0.5, 1))
            ]
            let outputs = try dispatch(
                device: device, queue: queue, pipeline: pipeline,
                inputs: inputs, extent: SIMD2(1280, 720), resetMask: 0
            )
            try validate(outputs[0], expected: .zero, reactive: 0, name: "static")
            try validate(outputs[1], expected: SIMD2(64, 0), reactive: 0, name: "camera translation")
            try validate(outputs[2], expected: SIMD2(-64, 0), reactive: 0, name: "camera rotation")
            try validate(outputs[3], expected: SIMD2(0, -36), reactive: 0, name: "vertical orientation")
            try validate(outputs[4], expected: SIMD2(-64, 0), reactive: 0, name: "entity translation/scale")
            try validate(outputs[5], expected: .zero, reactive: 1, name: "invalid depth")
            try validate(outputs[6], expected: .zero, reactive: 1, name: "non-finite input")

            let scaled = try dispatch(
                device: device, queue: queue, pipeline: pipeline,
                inputs: [inputs[1]], extent: SIMD2(1920, 1080), resetMask: 0
            )
            try validate(scaled[0], expected: SIMD2(96, 0), reactive: 0, name: "render-resolution scaling")
            let reset = try dispatch(
                device: device, queue: queue, pipeline: pipeline,
                inputs: [inputs[1]], extent: SIMD2(1280, 720), resetMask: 1
            )
            try validate(reset[0], expected: .zero, reactive: 1, name: "history reset")

            let jittered = try dispatch(
                device: device, queue: queue, pipeline: pipeline,
                inputs: [MotionInput(
                    currentClip: SIMD4(-1.0 / 1280.0, -0.5 / 720.0, 0.5, 1.0),
                    previousClip: SIMD4(-0.5 / 1280.0, -0.25 / 720.0, 0.5, 1.0)
                )],
                extent: SIMD2(1280, 720), resetMask: 0,
                jitter: SIMD2(0.5, -0.25), prevJitter: SIMD2(0.25, -0.125)
            )
            try validate(jittered[0], expected: .zero, reactive: 0, name: "jitter unjittering")

            guard let reprojFunction = library.makeFunction(name: "metallum_reprojection_validate") else {
                throw ValidationFailure.message("Reprojection validation kernel is absent")
            }
            let reprojPipeline = try device.makeComputePipelineState(function: reprojFunction)
            let ident = simd_float4x4(
                SIMD4<Float>(1, 0, 0, 0),
                SIMD4<Float>(0, 1, 0, 0),
                SIMD4<Float>(0, 0, 1, 0),
                SIMD4<Float>(0, 0, 0, 1)
            )
            let uniforms = MetallumTemporalDiagnosticUniforms(
                currentView: ident,
                currentProjection: ident,
                inverseCurrentView: ident,
                inverseCurrentProjection: ident,
                previousView: ident,
                previousProjection: ident,
                currentCameraPosition: SIMD4<Float>(0, 0, 0, 0),
                previousCameraPosition: SIMD4<Float>(-0.1, 0, 0, 0),
                renderExtent: SIMD2<Float>(1280, 720),
                jitter: .zero,
                previousJitter: .zero,
                reserved_padding: .zero,
                resetMask: 0,
                previousDepthValid: 0
            )
            let reprojInputs = [
                ReprojectionInput(pixelCoord: SIMD2<Float>(640, 360), depth: 0.5)
            ]
            let reprojOutputs = try dispatchReprojection(
                device: device, queue: queue, pipeline: reprojPipeline,
                inputs: reprojInputs, uniforms: uniforms
            )
            try validate(reprojOutputs[0], expected: SIMD2<Float>(64, 0), reactive: 0, name: "reprojection roundtrip math")

            print("GPU motion-vector validation passed (11 cases, <=0.01 px)")
        } catch {
            fputs("GPU motion-vector validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
