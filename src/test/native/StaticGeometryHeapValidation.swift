import Darwin
import Foundation
import Metal

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case let .message(message): return message
        }
    }
}

private typealias NativeCreateStaticGeometryBuffer = @convention(c) (
    UnsafeMutableRawPointer?,
    Int
) -> UnsafeMutableRawPointer?

private typealias NativeReleaseObject = @convention(c) (
    UnsafeMutableRawPointer?
) -> Void

private typealias NativeReleaseDeviceCaches = @convention(c) (
    UnsafeMutableRawPointer?
) -> Void

private let heapPageBytes = 16 * 1024 * 1024

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw ValidationFailure.message(message) }
}

private func objectAddress(_ object: AnyObject) -> UInt {
    UInt(bitPattern: Unmanaged.passUnretained(object).toOpaque())
}

private final class WeakHeapReference {
    weak var value: MTLHeap?

    init(_ value: MTLHeap) {
        self.value = value
    }
}

private final class NativeBufferLease {
    private var handle: UnsafeMutableRawPointer?
    private let release: NativeReleaseObject
    private(set) var buffer: MTLBuffer?

    init(handle: UnsafeMutableRawPointer, release: @escaping NativeReleaseObject) throws {
        let object = Unmanaged<AnyObject>.fromOpaque(handle).takeUnretainedValue()
        guard let buffer = object as? MTLBuffer else {
            release(handle)
            throw ValidationFailure.message("Static geometry ABI returned a non-MTLBuffer object")
        }
        self.handle = handle
        self.release = release
        self.buffer = buffer
    }

    func close() {
        guard let handle else { return }
        // Drop the validation harness' ARC reference before consuming the ABI's
        // retained reference so the native pool can observe usedSize == 0.
        buffer = nil
        self.handle = nil
        release(handle)
    }

    deinit {
        close()
    }
}

private struct NativeApi {
    let createStaticGeometryBuffer: NativeCreateStaticGeometryBuffer
    let releaseStaticGeometryBuffer: NativeReleaseObject
    let releaseDeviceCaches: NativeReleaseDeviceCaches

    func makeBuffer(device: MTLDevice, length: Int) throws -> NativeBufferLease {
        let devicePointer = Unmanaged.passUnretained(device as AnyObject).toOpaque()
        guard let handle = createStaticGeometryBuffer(devicePointer, length) else {
            throw ValidationFailure.message(
                "Static geometry ABI failed to allocate \(length) bytes"
            )
        }
        return try NativeBufferLease(
            handle: handle,
            release: releaseStaticGeometryBuffer
        )
    }
}

private func validateHeapBacked(
    _ lease: NativeBufferLease,
    expectedLength: Int,
    context: String
) throws -> UInt {
    guard let buffer = lease.buffer else {
        throw ValidationFailure.message("\(context) buffer was released too early")
    }
    try require(buffer.length == expectedLength, "\(context) buffer length changed")
    try require(buffer.storageMode == .private, "\(context) buffer is not private")
    try require(
        buffer.hazardTrackingMode == .untracked,
        "\(context) buffer is not hazard-untracked"
    )
    guard let heap = buffer.heap else {
        throw ValidationFailure.message("\(context) buffer did not come from a heap")
    }
    try require(heap.storageMode == .private, "\(context) heap is not private")
    try require(
        heap.hazardTrackingMode == .untracked,
        "\(context) heap is not hazard-untracked"
    )
    try require(heap.type == .automatic, "\(context) heap is not automatic")
    try require(heap.size >= heapPageBytes, "\(context) heap is smaller than 16 MiB")
    return objectAddress(heap)
}

private func weakHeapReference(_ lease: NativeBufferLease, context: String) throws -> WeakHeapReference {
    guard let heap = lease.buffer?.heap else {
        throw ValidationFailure.message("\(context) buffer lost its heap")
    }
    return WeakHeapReference(heap)
}

private func validateStandaloneFallback(
    _ lease: NativeBufferLease,
    expectedLength: Int
) throws {
    guard let buffer = lease.buffer else {
        throw ValidationFailure.message("Oversize fallback buffer was released too early")
    }
    try require(buffer.length == expectedLength, "Oversize fallback length changed")
    try require(buffer.storageMode == .private, "Oversize fallback is not private")
    try require(
        buffer.hazardTrackingMode == .untracked,
        "Oversize fallback is not hazard-untracked"
    )
    try require(buffer.heap == nil, "Oversize allocation unexpectedly came from a heap")
}

private func expectedByte(at index: Int) -> UInt8 {
    UInt8(truncatingIfNeeded: index &* 31 &+ 17)
}

private func validateRoundTrip(
    device: MTLDevice,
    lease: NativeBufferLease,
    byteCount: Int
) throws {
    guard let destination = lease.buffer else {
        throw ValidationFailure.message("Round-trip heap buffer was released too early")
    }
    guard let queue = device.makeCommandQueue(),
          let source = device.makeBuffer(length: byteCount, options: .storageModeShared),
          let readback = device.makeBuffer(length: byteCount, options: .storageModeShared),
          let fence = device.makeFence(),
          let commandBuffer = queue.makeCommandBuffer() else {
        throw ValidationFailure.message("Could not create Metal round-trip resources")
    }

    let sourceBytes = source.contents().bindMemory(to: UInt8.self, capacity: byteCount)
    let readbackBytes = readback.contents().bindMemory(to: UInt8.self, capacity: byteCount)
    for index in 0..<byteCount {
        sourceBytes[index] = expectedByte(at: index)
        readbackBytes[index] = 0
    }

    guard let upload = commandBuffer.makeBlitCommandEncoder() else {
        throw ValidationFailure.message("Could not create heap upload encoder")
    }
    upload.copy(
        from: source,
        sourceOffset: 0,
        to: destination,
        destinationOffset: 0,
        size: byteCount
    )
    upload.updateFence(fence)
    upload.endEncoding()

    guard let download = commandBuffer.makeBlitCommandEncoder() else {
        throw ValidationFailure.message("Could not create heap readback encoder")
    }
    download.waitForFence(fence)
    download.copy(
        from: destination,
        sourceOffset: 0,
        to: readback,
        destinationOffset: 0,
        size: byteCount
    )
    download.endEncoding()

    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    try require(
        commandBuffer.status == .completed,
        "Heap round trip failed: \(commandBuffer.error?.localizedDescription ?? "unknown GPU error")"
    )
    for index in 0..<byteCount {
        let expected = expectedByte(at: index)
        if readbackBytes[index] != expected {
            throw ValidationFailure.message(
                "Heap round-trip mismatch at byte \(index): "
                    + "expected \(expected), got \(readbackBytes[index])"
            )
        }
    }
}

private func runValidation(api: NativeApi, device: MTLDevice) throws {
    let anchorLength = 4 * 1024
    let reusableLength = 2 * 1024 * 1024
    let secondPageLength = 8 * 1024 * 1024

    let anchor = try api.makeBuffer(device: device, length: anchorLength)
    let anchorHeapAddress = try validateHeapBacked(
        anchor,
        expectedLength: anchorLength,
        context: "Anchor"
    )
    let temporary = try api.makeBuffer(device: device, length: reusableLength)
    let temporaryHeapAddress = try validateHeapBacked(
        temporary,
        expectedLength: reusableLength,
        context: "Temporary"
    )
    try require(
        temporaryHeapAddress == anchorHeapAddress,
        "Small static geometry allocations did not share one heap page"
    )

    temporary.close()
    let replacement = try api.makeBuffer(device: device, length: reusableLength)
    let replacementHeapAddress = try validateHeapBacked(
        replacement,
        expectedLength: reusableLength,
        context: "Replacement"
    )
    try require(
        replacementHeapAddress == anchorHeapAddress,
        "Freed capacity was not reused while the heap page remained live"
    )

    let firstLarge = try api.makeBuffer(device: device, length: secondPageLength)
    let firstLargeHeapAddress = try validateHeapBacked(
        firstLarge,
        expectedLength: secondPageLength,
        context: "First large"
    )
    try require(
        firstLargeHeapAddress == anchorHeapAddress,
        "First large allocation did not use remaining capacity in the first page"
    )

    let secondLarge = try api.makeBuffer(device: device, length: secondPageLength)
    let secondLargeHeapAddress = try validateHeapBacked(
        secondLarge,
        expectedLength: secondPageLength,
        context: "Second large"
    )
    try require(
        secondLargeHeapAddress != anchorHeapAddress,
        "A second heap page was not created after exhausting the first page"
    )

    let releasedSecondHeap = try weakHeapReference(secondLarge, context: "Second large")
    secondLarge.close()
    try require(releasedSecondHeap.value == nil, "Empty second heap page was not retired")
    let retirementProbe = try api.makeBuffer(device: device, length: anchorLength)
    let retirementProbeHeapAddress = try validateHeapBacked(
        retirementProbe,
        expectedLength: anchorLength,
        context: "Retirement probe"
    )
    try require(
        retirementProbeHeapAddress == anchorHeapAddress,
        "Empty second heap page remained in the allocator instead of being retired"
    )
    retirementProbe.close()

    let releasedFirstHeap = try weakHeapReference(anchor, context: "Anchor")
    firstLarge.close()
    replacement.close()
    anchor.close()
    try require(releasedFirstHeap.value == nil, "Empty first heap page was not retired")

    let roundTripLength = 256 * 1024 + 37
    let roundTrip = try api.makeBuffer(device: device, length: roundTripLength)
    _ = try validateHeapBacked(
        roundTrip,
        expectedLength: roundTripLength,
        context: "Round-trip"
    )
    try validateRoundTrip(
        device: device,
        lease: roundTrip,
        byteCount: roundTripLength
    )
    roundTrip.close()

    let oversizeLength = heapPageBytes + 1
    let oversize = try api.makeBuffer(device: device, length: oversizeLength)
    try validateStandaloneFallback(oversize, expectedLength: oversizeLength)
    oversize.close()

    var cachedArenaBuffers: [NativeBufferLease] = []
    for _ in 0..<8 {
        cachedArenaBuffers.append(
            try api.makeBuffer(device: device, length: anchorLength)
        )
    }
    let cachedHeap = try weakHeapReference(
        cachedArenaBuffers[0],
        context: "Cached arena"
    )
    let devicePointer = Unmanaged.passUnretained(device as AnyObject).toOpaque()
    api.releaseDeviceCaches(devicePointer)
    for lease in cachedArenaBuffers {
        lease.close()
    }
    try require(
        cachedHeap.value == nil,
        "Heap page survived release of the bounded post-teardown arena cache"
    )
}

@main
private enum StaticGeometryHeapValidationMain {
    static func main() {
        do {
            let arguments = CommandLine.arguments
            try require(
                arguments.count == 2,
                "Usage: StaticGeometryHeapValidation <libmetallum.dylib>"
            )
            guard let library = dlopen(arguments[1], RTLD_NOW | RTLD_LOCAL) else {
                let detail = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
                throw ValidationFailure.message("Could not load native library: \(detail)")
            }
            defer { dlclose(library) }

            guard let createSymbol = dlsym(
                library,
                "metallum_create_static_geometry_buffer"
            ) else {
                throw ValidationFailure.message("Static geometry create ABI symbol is missing")
            }
            guard let releaseSymbol = dlsym(
                library,
                "metallum_release_static_geometry_buffer"
            ) else {
                throw ValidationFailure.message("Static geometry release ABI symbol is missing")
            }
            guard let releaseCachesSymbol = dlsym(
                library,
                "metallum_release_device_caches"
            ) else {
                throw ValidationFailure.message("Device cache release ABI symbol is missing")
            }

            let api = NativeApi(
                createStaticGeometryBuffer: unsafeBitCast(
                    createSymbol,
                    to: NativeCreateStaticGeometryBuffer.self
                ),
                releaseStaticGeometryBuffer: unsafeBitCast(
                    releaseSymbol,
                    to: NativeReleaseObject.self
                ),
                releaseDeviceCaches: unsafeBitCast(
                    releaseCachesSymbol,
                    to: NativeReleaseDeviceCaches.self
                )
            )
            guard let device = MTLCreateSystemDefaultDevice() else {
                throw ValidationFailure.message("No Metal device is available")
            }
            try runValidation(api: api, device: device)
            print("Static geometry heap validation passed on \(device.name)")
        } catch {
            fputs("Static geometry heap validation failed: \(error)\n", stderr)
            exit(1)
        }
    }
}
