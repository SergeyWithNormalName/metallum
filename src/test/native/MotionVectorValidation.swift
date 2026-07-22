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
    var inversePreviousJitteredProjection: simd_float4x4
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
    try require(commandBuffer.status == .completed, "Reprojection validation command buffer failed")
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

private func perspectiveProjection(
    verticalFovRadians: Float,
    aspect: Float,
    near: Float,
    far: Float
) -> simd_float4x4 {
    let focalLength = 1.0 / tanf(verticalFovRadians * 0.5)
    // Right-handed, depth [0, 1], column-vector convention.  This deliberately
    // stays independent of the shader's inverse/reprojection implementation.
    return simd_float4x4(columns: (
        SIMD4<Float>(focalLength / aspect, 0, 0, 0),
        SIMD4<Float>(0, focalLength, 0, 0),
        SIMD4<Float>(0, 0, far / (near - far), -1),
        SIMD4<Float>(0, 0, near * far / (near - far), 0)
    ))
}

private func translation(_ x: Float, _ y: Float, _ z: Float) -> simd_float4x4 {
    simd_float4x4(columns: (
        SIMD4<Float>(1, 0, 0, 0),
        SIMD4<Float>(0, 1, 0, 0),
        SIMD4<Float>(0, 0, 1, 0),
        SIMD4<Float>(x, y, z, 1)
    ))
}

private func rotationX(_ radians: Float) -> simd_float4x4 {
    let cosine = cosf(radians)
    let sine = sinf(radians)
    return simd_float4x4(columns: (
        SIMD4<Float>(1, 0, 0, 0),
        SIMD4<Float>(0, cosine, sine, 0),
        SIMD4<Float>(0, -sine, cosine, 0),
        SIMD4<Float>(0, 0, 0, 1)
    ))
}

private func rotationZ(_ radians: Float) -> simd_float4x4 {
    let cosine = cosf(radians)
    let sine = sinf(radians)
    return simd_float4x4(columns: (
        SIMD4<Float>(cosine, sine, 0, 0),
        SIMD4<Float>(-sine, cosine, 0, 0),
        SIMD4<Float>(0, 0, 1, 0),
        SIMD4<Float>(0, 0, 0, 1)
    ))
}

private func jitterBeforeBobbing(
    _ baseProjection: simd_float4x4,
    jitter: SIMD2<Float>,
    extent: SIMD2<Float>
) -> simd_float4x4 {
    var projection = baseProjection
    // Match TemporalJitterProjection: logical MetalFX Y is render-target down,
    // whereas the projection's clip-space Y is up.
    projection.columns.2.x += 2.0 * jitter.x / extent.x
    projection.columns.2.y -= 2.0 * jitter.y / extent.y
    return projection
}

private func ndc(_ projection: simd_float4x4, _ point: SIMD4<Float>) -> SIMD3<Float> {
    let clip = projection * point
    return SIMD3<Float>(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w)
}

private func pixelCoordinate(_ ndc: SIMD3<Float>, extent: SIMD2<Float>) -> SIMD2<Float> {
    SIMD2<Float>(
        (ndc.x * 0.5 + 0.5) * extent.x,
        (1.0 - ndc.y) * 0.5 * extent.y
    )
}

private func expectedMotionPixels(
    currentUnjitteredNdc: SIMD3<Float>,
    previousUnjitteredNdc: SIMD3<Float>,
    extent: SIMD2<Float>
) -> SIMD2<Float> {
    SIMD2<Float>(
        (previousUnjitteredNdc.x - currentUnjitteredNdc.x) * 0.5 * extent.x,
        -(previousUnjitteredNdc.y - currentUnjitteredNdc.y) * 0.5 * extent.y
    )
}

/**
 * Reproduces Minecraft's relevant projection shape: a perspective matrix followed by a changing
 * view-bob transform.  The CPU expected value projects the same world point through the two
 * unjittered matrices; the GPU under test must reconstruct that value from the jittered depth
 * sample.  Covering near/middle/far points makes a post-bobbing jitter depth-dependent and
 * therefore observable.
 */
private func validateCameraBobJitterReprojection(
    device: MTLDevice,
    queue: MTLCommandQueue,
    pipeline: MTLComputePipelineState
) throws {
    let extent = SIMD2<Float>(1512, 982) // Exact production Performance render extent at 3024x1964.
    let baseProjection = perspectiveProjection(
        verticalFovRadians: 70.0 * .pi / 180.0,
        aspect: extent.x / extent.y,
        near: 0.05,
        far: 1_024.0
    )
    // Deliberately stress the same translation/roll/pitch composition as view bob.
    // Normal gameplay bob is smaller; this larger deterministic pair makes an accidental
    // P * B_jitter construction fail loudly instead of hiding below a sub-pixel tolerance.
    let previousBob = translation(0.0, -0.18, 0.0)
        * rotationZ(-13.0 * .pi / 180.0)
        * rotationX(15.0 * .pi / 180.0)
    let currentBob = translation(0.0, 0.27, 0.0)
        * rotationZ(20.0 * .pi / 180.0)
        * rotationX(-18.0 * .pi / 180.0)
    let jitter = SIMD2<Float>(0.45, -0.35)
    let previousJitter = SIMD2<Float>(-0.125, 0.375)
    let previousProjection = baseProjection * previousBob
    let previousJitteredProjection = jitterBeforeBobbing(
        baseProjection, jitter: previousJitter, extent: extent
    ) * previousBob
    let currentUnjitteredProjection = baseProjection * currentBob
    let currentProjection = jitterBeforeBobbing(baseProjection, jitter: jitter, extent: extent)
        * currentBob

    let points: [SIMD4<Float>] = [
        SIMD4<Float>(-0.22, -0.14, -0.75, 1.0),
        SIMD4<Float>(0.31, 0.19, -0.75, 1.0),
        SIMD4<Float>(-0.55, 0.28, -3.5, 1.0),
        SIMD4<Float>(0.47, -0.31, -3.5, 1.0),
        SIMD4<Float>(-1.2, -0.7, -28.0, 1.0),
        SIMD4<Float>(1.35, 0.82, -28.0, 1.0)
    ]

    var inputs: [ReprojectionInput] = []
    var expected: [SIMD2<Float>] = []
    for point in points {
        let rasterNdc = ndc(currentProjection, point)
        let currentUnjitteredNdc = ndc(currentUnjitteredProjection, point)
        let previousUnjitteredNdc = ndc(previousProjection, point)
        try require(
            abs(rasterNdc.x) < 0.95 && abs(rasterNdc.y) < 0.95
                && rasterNdc.z > 0.0 && rasterNdc.z < 1.0,
            "camera-bob fixture escaped the raster/depth range: \(rasterNdc)"
        )
        inputs.append(ReprojectionInput(
            pixelCoord: pixelCoordinate(rasterNdc, extent: extent), depth: rasterNdc.z
        ))
        expected.append(expectedMotionPixels(
            currentUnjitteredNdc: currentUnjitteredNdc,
            previousUnjitteredNdc: previousUnjitteredNdc,
            extent: extent
        ))
    }

    let identity = matrix_identity_float4x4
    let uniforms = MetallumTemporalDiagnosticUniforms(
        currentView: identity,
        currentProjection: currentProjection,
        inverseCurrentView: identity,
        inverseCurrentProjection: simd_inverse(currentProjection),
        previousView: identity,
        previousProjection: previousProjection,
        inversePreviousJitteredProjection: simd_inverse(previousJitteredProjection),
        currentCameraPosition: .zero,
        previousCameraPosition: .zero,
        renderExtent: extent,
        jitter: jitter,
        previousJitter: previousJitter,
        reserved_padding: .zero,
        resetMask: 0,
        previousDepthValid: 0
    )
    let outputs = try dispatchReprojection(
        device: device, queue: queue, pipeline: pipeline, inputs: inputs, uniforms: uniforms
    )
    var maximumResidual: Float = 0.0
    for index in inputs.indices {
        try validate(
            outputs[index], expected: expected[index], reactive: 0,
            name: "camera bob/jitter point \(index)"
        )
        maximumResidual = max(maximumResidual, length(outputs[index].motion - expected[index]))
    }
    try require(maximumResidual <= 0.25,
                "camera bob/jitter residual exceeded 0.25 render pixels: \(maximumResidual)")

    // This is the historical failure shape: applying the nominal projection jitter after the
    // view-bob transform makes the offset non-constant in depth.  It must diverge materially
    // from the independent P_jittered * B reference above; otherwise this fixture is too weak
    // to guard the regression it was introduced for.
    let currentProjectionWithWrongOrder = jitterBeforeBobbing(
        baseProjection * currentBob, jitter: jitter, extent: extent
    )
    var wrongOrderInputs: [ReprojectionInput] = []
    for point in points {
        let rasterNdc = ndc(currentProjectionWithWrongOrder, point)
        try require(
            abs(rasterNdc.x) < 0.95 && abs(rasterNdc.y) < 0.95
                && rasterNdc.z > 0.0 && rasterNdc.z < 1.0,
            "wrong-order camera-bob fixture escaped the raster/depth range: \(rasterNdc)"
        )
        wrongOrderInputs.append(ReprojectionInput(
            pixelCoord: pixelCoordinate(rasterNdc, extent: extent), depth: rasterNdc.z
        ))
    }
    var wrongOrderUniforms = uniforms
    wrongOrderUniforms.currentProjection = currentProjectionWithWrongOrder
    wrongOrderUniforms.inverseCurrentProjection = simd_inverse(currentProjectionWithWrongOrder)
    let wrongOrderOutputs = try dispatchReprojection(
        device: device, queue: queue, pipeline: pipeline,
        inputs: wrongOrderInputs, uniforms: wrongOrderUniforms
    )
    let wrongOrderResiduals = zip(wrongOrderOutputs, expected).map { output, reference in
        length(output.motion - reference)
    }
    let wrongOrderMaximumResidual = wrongOrderResiduals.max() ?? 0.0
    let wrongOrderDepthSpread = (wrongOrderResiduals.max() ?? 0.0) - (wrongOrderResiduals.min() ?? 0.0)
    try require(wrongOrderMaximumResidual > 0.05 && wrongOrderDepthSpread > 0.04,
                "camera-bob wrong-order guard is not depth-sensitive: max=\(wrongOrderMaximumResidual), spread=\(wrongOrderDepthSpread)")
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
                inversePreviousJitteredProjection: ident,
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
            try validateCameraBobJitterReprojection(
                device: device, queue: queue, pipeline: reprojPipeline
            )

            print("GPU motion-vector validation passed (11 scalar cases + camera-bob depth grid)")
        } catch {
            fputs("GPU motion-vector validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
