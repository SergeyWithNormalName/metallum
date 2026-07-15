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

@available(macOS 26.0, *)
private final class CommitFeedbackState: @unchecked Sendable {
    private let lock = NSLock()
    private var failure: String?
    private var startTime = 0.0
    private var endTime = 0.0

    func record(_ feedback: MTL4CommitFeedback) {
        lock.lock()
        failure = feedback.error.map(String.init(describing:))
        startTime = feedback.gpuStartTime
        endTime = feedback.gpuEndTime
        lock.unlock()
    }

    func validate() throws -> (start: Double, end: Double) {
        lock.lock()
        defer { lock.unlock() }
        try require(failure == nil, "Metal 4 commit feedback reported: \(failure ?? "unknown error")")
        try require(startTime > 0.0 && endTime >= startTime, "Metal 4 commit feedback timestamps are invalid")
        return (startTime, endTime)
    }
}

@available(macOS 26.0, *)
private struct HarnessResources {
    let device: MTLDevice
    let compiler: MTL4Compiler
    let pipeline: MTLComputePipelineState
    let queue: MTL4CommandQueue
    let allocator: MTL4CommandAllocator
    let commandBuffer: MTL4CommandBuffer
    let argumentTable: MTL4ArgumentTable
    let residencySet: MTLResidencySet
    let source: MTLBuffer
    let destination: MTLBuffer
    let readback: MTLBuffer
}

@available(macOS 26.0, *)
private func buildResources(_ device: MTLDevice) throws -> HarnessResources {
    let compilerDescriptor = MTL4CompilerDescriptor()
    compilerDescriptor.label = "Metallum Metal 4 lifecycle validation compiler"
    let compiler = try device.makeCompiler(descriptor: compilerDescriptor)

    let invalidLibraryDescriptor = MTL4LibraryDescriptor()
    invalidLibraryDescriptor.name = "Metallum expected compiler failure"
    invalidLibraryDescriptor.source = "kernel void deliberately_invalid("
    var observedCompilerFailure = false
    do {
        _ = try compiler.makeLibrary(descriptor: invalidLibraryDescriptor)
    } catch {
        observedCompilerFailure = true
    }
    try require(observedCompilerFailure, "artificial Metal 4 compiler failure was not reported")
    print("PASS Metal 4 artificial compiler failure")

    let libraryDescriptor = MTL4LibraryDescriptor()
    libraryDescriptor.name = "Metallum Metal 4 lifecycle validation library"
    libraryDescriptor.source = """
        #include <metal_stdlib>
        using namespace metal;

        kernel void add_one(
            device const uint *source [[buffer(0)]],
            device uint *destination [[buffer(1)]],
            uint index [[thread_position_in_grid]]) {
            destination[index] = source[index] + 1u;
        }
        """
    let library = try compiler.makeLibrary(descriptor: libraryDescriptor)

    let functionDescriptor = MTL4LibraryFunctionDescriptor()
    functionDescriptor.library = library
    functionDescriptor.name = "add_one"
    let pipelineDescriptor = MTL4ComputePipelineDescriptor()
    pipelineDescriptor.label = "Metallum Metal 4 lifecycle validation pipeline"
    pipelineDescriptor.computeFunctionDescriptor = functionDescriptor
    pipelineDescriptor.maxTotalThreadsPerThreadgroup = 1
    let pipeline = try compiler.makeComputePipelineState(descriptor: pipelineDescriptor)

    guard let queue = device.makeMTL4CommandQueue(),
          let allocator = device.makeCommandAllocator(),
          let commandBuffer = device.makeCommandBuffer(),
          let source = device.makeBuffer(length: 64, options: .storageModeShared),
          let destination = device.makeBuffer(length: 64, options: .storageModeShared),
          let readback = device.makeBuffer(length: 64, options: .storageModeShared) else {
        throw ValidationFailure.failed("failed to create isolated Metal 4 validation resources")
    }
    commandBuffer.label = "Metallum reusable Metal 4 validation command buffer"
    source.label = "Metallum Metal 4 validation source"
    destination.label = "Metallum Metal 4 validation destination"
    readback.label = "Metallum Metal 4 validation readback"

    let argumentDescriptor = MTL4ArgumentTableDescriptor()
    argumentDescriptor.label = "Metallum bounded two-buffer argument table"
    argumentDescriptor.maxBufferBindCount = 2
    argumentDescriptor.maxTextureBindCount = 0
    argumentDescriptor.maxSamplerStateBindCount = 0
    argumentDescriptor.initializeBindings = true
    let argumentTable = try device.makeArgumentTable(descriptor: argumentDescriptor)
    argumentTable.setAddress(source.gpuAddress, index: 0)
    argumentTable.setAddress(destination.gpuAddress, index: 1)

    let residencyDescriptor = MTLResidencySetDescriptor()
    residencyDescriptor.label = "Metallum Metal 4 lifecycle validation residency"
    residencyDescriptor.initialCapacity = 4
    let residencySet = try device.makeResidencySet(descriptor: residencyDescriptor)
    residencySet.addAllocation(source)
    residencySet.addAllocation(destination)
    residencySet.addAllocation(readback)
    residencySet.addAllocation(pipeline)
    residencySet.commit()

    return HarnessResources(
        device: device,
        compiler: compiler,
        pipeline: pipeline,
        queue: queue,
        allocator: allocator,
        commandBuffer: commandBuffer,
        argumentTable: argumentTable,
        residencySet: residencySet,
        source: source,
        destination: destination,
        readback: readback
    )
}

@available(macOS 26.0, *)
private func commitAndWait(
    _ resources: HarnessResources,
    encode: (MTL4ComputeCommandEncoder) throws -> Void
) throws -> (start: Double, end: Double) {
    resources.commandBuffer.beginCommandBuffer(allocator: resources.allocator)
    // beginCommandBuffer clears residency declarations; every reuse must declare them again.
    resources.commandBuffer.useResidencySet(resources.residencySet)
    guard let encoder = resources.commandBuffer.makeComputeCommandEncoder() else {
        throw ValidationFailure.failed("failed to create a Metal 4 compute encoder")
    }
    try encode(encoder)
    encoder.endEncoding()
    resources.commandBuffer.endCommandBuffer()

    let feedbackState = CommitFeedbackState()
    let completion = DispatchSemaphore(value: 0)
    let commitOptions = MTL4CommitOptions()
    commitOptions.addFeedbackHandler { feedback in
        feedbackState.record(feedback)
        // The allocator is reset only after Metal reports completion of its submitted work.
        resources.allocator.reset()
        completion.signal()
    }
    resources.queue.commit([resources.commandBuffer], options: commitOptions)
    guard completion.wait(timeout: .now() + 10.0) == .success else {
        throw ValidationFailure.failed("timed out waiting for Metal 4 commit feedback")
    }
    return try feedbackState.validate()
}

@available(macOS 26.0, *)
private func runHarness(_ device: MTLDevice) throws {
    let resources = try buildResources(device)

    let firstFeedback = try commitAndWait(resources) { encoder in
        encoder.fill(buffer: resources.source, range: 0..<64, value: 0x11)
        encoder.barrier(
            afterEncoderStages: .blit,
            beforeEncoderStages: .dispatch,
            visibilityOptions: .device
        )
        encoder.setComputePipelineState(resources.pipeline)
        encoder.setArgumentTable(resources.argumentTable)
        encoder.dispatchThreads(
            threadsPerGrid: MTLSize(width: 1, height: 1, depth: 1),
            threadsPerThreadgroup: MTLSize(width: 1, height: 1, depth: 1)
        )
        encoder.barrier(
            afterEncoderStages: .dispatch,
            beforeEncoderStages: .blit,
            visibilityOptions: .device
        )
        encoder.copy(
            sourceBuffer: resources.destination,
            sourceOffset: 0,
            destinationBuffer: resources.readback,
            destinationOffset: 0,
            size: 4
        )
    }
    let firstValue = resources.readback.contents().assumingMemoryBound(to: UInt32.self).pointee
    try require(firstValue == 0x1111_1112, "Metal 4 synchronized compute result mismatch: \(firstValue)")

    let secondFeedback = try commitAndWait(resources) { encoder in
        encoder.fill(buffer: resources.source, range: 0..<64, value: 0x5a)
        encoder.barrier(
            afterEncoderStages: .blit,
            beforeEncoderStages: .blit,
            visibilityOptions: .device
        )
        encoder.copy(
            sourceBuffer: resources.source,
            sourceOffset: 0,
            destinationBuffer: resources.readback,
            destinationOffset: 0,
            size: 4
        )
    }
    let secondValue = resources.readback.contents().assumingMemoryBound(to: UInt32.self).pointee
    try require(secondValue == 0x5a5a_5a5a, "Metal 4 command-buffer reuse result mismatch: \(secondValue)")

    try require(firstFeedback.end >= firstFeedback.start, "first feedback interval is invalid")
    try require(secondFeedback.end >= secondFeedback.start, "second feedback interval is invalid")
    print("PASS Metal 4 lifecycle validation on \(device.name)")
}

@main
private struct Metal4LifecycleValidation {
    static func main() {
        guard #available(macOS 26.0, *) else {
            print("SKIP Metal 4 lifecycle validation: macOS 26 or newer is required")
            return
        }
        guard let device = MTLCreateSystemDefaultDevice() else {
            fputs("FAIL Metal 4 lifecycle validation: no Metal device\n", stderr)
            exit(1)
        }
        guard device.supportsFamily(.metal4) else {
            print("SKIP Metal 4 lifecycle validation: \(device.name) does not support Metal 4")
            return
        }

        do {
            try runHarness(device)
        } catch {
            fputs("FAIL Metal 4 lifecycle validation: \(error)\n", stderr)
            exit(1)
        }
    }
}
