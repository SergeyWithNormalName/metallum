import Darwin
import Foundation
import Metal

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self { case let .message(value): value }
    }
}

private typealias NativeAbiVersion = @convention(c) () -> UInt32
private typealias NativeLayout = @convention(c) (UnsafeMutableRawPointer?, UInt64) -> Int32
private typealias NativeCreate = @convention(c) (
    UnsafeMutableRawPointer?, UInt64, UInt64, UInt64, UnsafeRawPointer?, UInt64,
    UInt32, UInt32, UInt64
) -> UnsafeMutableRawPointer?
private typealias NativeRelease = @convention(c) (UnsafeMutableRawPointer?) -> Void
private typealias NativeBuffer = @convention(c) (
    UnsafeMutableRawPointer?, Int32, Int32
) -> UnsafeMutableRawPointer?
private typealias NativeBufferBytes = @convention(c) (
    UnsafeMutableRawPointer?, Int32, Int32
) -> UInt64
private typealias NativeUpload = @convention(c) (
    UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UnsafeRawPointer?, UInt64
) -> Int32
private typealias NativeStats = @convention(c) (
    UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UInt64
) -> Int32
private typealias NativeDebugChecksum = @convention(c) (
    UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UInt32, UInt32
) -> Int32
private typealias NativeDebugReadback = @convention(c) (
    UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UInt64
) -> Int32

private struct NativeApi {
    let abiVersion: NativeAbiVersion
    let layout: NativeLayout
    let create: NativeCreate
    let release: NativeRelease
    let buffer: NativeBuffer
    let bufferBytes: NativeBufferBytes
    let upload: NativeUpload
    let stats: NativeStats
    let debugChecksum: NativeDebugChecksum
    let debugReadback: NativeDebugReadback
}

private let magic: UInt32 = 0x3142_564d
private let headerBytes = 96
private let recordBytes = 56
private let logicalBrickEdge = 32
private let occupancyBytes = 4096
private let busyStatus: Int32 = -22
private let lightingGeneration: UInt64 = 101
private let clipmapGeneration: UInt64 = 202
private let worldGeneration: UInt64 = 303

private func require(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
    guard try condition() else { throw ValidationFailure.message(message) }
}

private func writeUInt32(_ value: UInt32, _ offset: Int, _ bytes: inout [UInt8]) {
    var little = value.littleEndian
    withUnsafeBytes(of: &little) { bytes.replaceSubrange(offset..<(offset + 4), with: $0) }
}

private func writeUInt64(_ value: UInt64, _ offset: Int, _ bytes: inout [UInt8]) {
    var little = value.littleEndian
    withUnsafeBytes(of: &little) { bytes.replaceSubrange(offset..<(offset + 8), with: $0) }
}

private func readUInt32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
    bytes.withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)) }
}

private func readUInt64(_ bytes: [UInt8], _ offset: Int) -> UInt64 {
    bytes.withUnsafeBytes { UInt64(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self)) }
}

private func objectPointer(_ object: AnyObject) -> UnsafeMutableRawPointer {
    Unmanaged.passUnretained(object).toOpaque()
}

private func dylibSymbol<T>(_ handle: UnsafeMutableRawPointer, _ name: String, _: T.Type) throws -> T {
    guard let symbol = dlsym(handle, name) else {
        throw ValidationFailure.message("Missing native symbol \(name)")
    }
    return unsafeBitCast(symbol, to: T.self)
}

private func layout64(subdivision: UInt32 = 4, origin: (UInt32, UInt32, UInt32) = (1, 1, 1)) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 32)
    writeUInt32(64, 0, &bytes)
    writeUInt32(subdivision, 4, &bytes)
    writeUInt32(origin.0, 8, &bytes)
    writeUInt32(origin.1, 12, &bytes)
    writeUInt32(origin.2, 16, &bytes)
    return bytes
}

private func twoLevelLayouts() -> [UInt8] {
    var result = layout64(subdivision: 4)
    result += layout64(subdivision: 2)
    return result
}

private struct Patch {
    let level: UInt32
    let x: UInt32
    let y: UInt32
    let z: UInt32
    let logicalX: Int32
    let logicalY: Int32
    let logicalZ: Int32
    let contentStamp: UInt32
    let occupancy: [UInt32]
    let optical: [UInt8]
}

private func packet(
    slot: UInt32 = 0,
    flags: UInt32 = 0,
    patches: [Patch],
    levelCount: UInt32 = 1,
    lighting: UInt64 = lightingGeneration,
    clipmap: UInt64 = clipmapGeneration,
    world: UInt64 = worldGeneration,
    frame: UInt64 = 1,
    coalescedDelta: UInt32 = 0,
    rejectedDelta: UInt32 = 0
) -> [UInt8] {
    precondition(patches.allSatisfy { $0.occupancy.count == 1024 })
    let payloadOffset = headerBytes + patches.count * recordBytes
    let payloadBytes = patches.reduce(0) { $0 + occupancyBytes + $1.optical.count }
    var bytes = [UInt8](repeating: 0, count: payloadOffset + payloadBytes)
    writeUInt32(magic, 0, &bytes)
    writeUInt32(1, 4, &bytes)
    writeUInt32(UInt32(bytes.count), 8, &bytes)
    writeUInt32(flags, 12, &bytes)
    writeUInt32(UInt32(recordBytes), 16, &bytes)
    writeUInt32(UInt32(patches.count), 20, &bytes)
    writeUInt32(slot, 24, &bytes)
    writeUInt32(levelCount, 28, &bytes)
    writeUInt64(lighting, 32, &bytes)
    writeUInt64(clipmap, 40, &bytes)
    writeUInt64(world, 48, &bytes)
    writeUInt64(frame, 56, &bytes)
    writeUInt32(UInt32(payloadBytes), 64, &bytes)
    writeUInt32(UInt32(payloadOffset), 68, &bytes)
    writeUInt32(coalescedDelta, 88, &bytes)
    writeUInt32(rejectedDelta, 92, &bytes)
    var cursor = payloadOffset
    for (index, patch) in patches.enumerated() {
        let record = headerBytes + index * recordBytes
        writeUInt32(patch.level, record, &bytes)
        writeUInt32(patch.x, record + 4, &bytes)
        writeUInt32(patch.y, record + 8, &bytes)
        writeUInt32(patch.z, record + 12, &bytes)
        writeUInt32(UInt32(cursor), record + 16, &bytes)
        writeUInt32(UInt32(occupancyBytes), record + 20, &bytes)
        writeUInt32(UInt32(patch.optical.count), record + 24, &bytes)
        writeUInt32(UInt32(truncatingIfNeeded: clipmap), record + 32, &bytes)
        writeUInt32(UInt32(truncatingIfNeeded: clipmap >> 32), record + 36, &bytes)
        writeUInt32(UInt32(bitPattern: patch.logicalX), record + 40, &bytes)
        writeUInt32(UInt32(bitPattern: patch.logicalY), record + 44, &bytes)
        writeUInt32(UInt32(bitPattern: patch.logicalZ), record + 48, &bytes)
        writeUInt32(patch.contentStamp, record + 52, &bytes)
        for wordIndex in patch.occupancy.indices {
            writeUInt32(patch.occupancy[wordIndex], cursor + wordIndex * 4, &bytes)
        }
        cursor += occupancyBytes
        bytes.replaceSubrange(cursor..<(cursor + patch.optical.count), with: patch.optical)
        cursor += patch.optical.count
    }
    return bytes
}

private func noPatchPacket(slot: UInt32 = 0, flags: UInt32 = 0) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: headerBytes)
    writeUInt32(magic, 0, &bytes)
    writeUInt32(1, 4, &bytes)
    writeUInt32(UInt32(headerBytes), 8, &bytes)
    writeUInt32(flags, 12, &bytes)
    writeUInt32(UInt32(recordBytes), 16, &bytes)
    writeUInt32(slot, 24, &bytes)
    writeUInt32(1, 28, &bytes)
    writeUInt64(lightingGeneration, 32, &bytes)
    writeUInt64(clipmapGeneration, 40, &bytes)
    writeUInt64(worldGeneration, 48, &bytes)
    writeUInt64(2, 56, &bytes)
    writeUInt32(0, 64, &bytes)
    writeUInt32(UInt32(headerBytes), 68, &bytes)
    return bytes
}

private func invokeUpload(
    _ api: NativeApi,
    context: UnsafeMutableRawPointer,
    queue: MTLCommandQueue,
    packet: [UInt8],
    commit: Bool = true
) throws -> (Int32, MTLCommandBuffer) {
    guard let command = queue.makeCommandBuffer() else {
        throw ValidationFailure.message("Could not create command buffer")
    }
    let status = packet.withUnsafeBytes {
        api.upload(context, objectPointer(command), $0.baseAddress, UInt64(packet.count))
    }
    if commit && status == 1 {
        command.commit()
        command.waitUntilCompleted()
        try require(command.status == .completed, "Voxel command buffer failed")
    }
    return (status, command)
}

private func privateBytes(
    _ api: NativeApi,
    context: UnsafeMutableRawPointer,
    kind: Int32,
    index: Int32,
    device: MTLDevice,
    queue: MTLCommandQueue
) throws -> [UInt8] {
    guard let pointer = api.buffer(context, kind, index) else {
        throw ValidationFailure.message("Context did not expose buffer kind \(kind)")
    }
    let object = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue()
    guard let source = object as? MTLBuffer else {
        throw ValidationFailure.message("Context ABI returned a non-buffer")
    }
    let count = Int(api.bufferBytes(context, kind, index))
    guard count == source.length,
          let readback = device.makeBuffer(length: count, options: .storageModeShared),
          let command = queue.makeCommandBuffer(),
          let blit = command.makeBlitCommandEncoder() else {
        throw ValidationFailure.message("Could not read private voxel buffer")
    }
    blit.copy(from: source, sourceOffset: 0, to: readback, destinationOffset: 0, size: count)
    blit.endEncoding()
    command.commit()
    command.waitUntilCompleted()
    try require(command.status == .completed, "Private voxel readback failed")
    return Array(UnsafeBufferPointer(
        start: readback.contents().bindMemory(to: UInt8.self, capacity: count), count: count
    ))
}

@main
private enum VoxelOccupancyValidationMain {
    static func main() {
        do {
            try run()
            print("Voxel occupancy validation passed")
        } catch {
            fputs("Voxel occupancy validation failed: \(error)\n", stderr)
            exit(1)
        }
    }

    private static func run() throws {
        try require(CommandLine.arguments.count == 2,
                    "Usage: VoxelOccupancyValidation <libmetallum.dylib>")
        guard let handle = dlopen(CommandLine.arguments[1], RTLD_NOW | RTLD_LOCAL) else {
            throw ValidationFailure.message(String(cString: dlerror()))
        }
        defer { dlclose(handle) }
        let api = NativeApi(
            abiVersion: try dylibSymbol(handle, "metallum_voxel_abi_version_v1", NativeAbiVersion.self),
            layout: try dylibSymbol(handle, "metallum_voxel_layout_v1", NativeLayout.self),
            create: try dylibSymbol(handle, "metallum_voxel_create_context_v1", NativeCreate.self),
            release: try dylibSymbol(handle, "metallum_voxel_release_context_v1", NativeRelease.self),
            buffer: try dylibSymbol(handle, "metallum_voxel_context_buffer_v1", NativeBuffer.self),
            bufferBytes: try dylibSymbol(handle, "metallum_voxel_context_buffer_bytes_v1", NativeBufferBytes.self),
            upload: try dylibSymbol(handle, "metallum_voxel_upload_apply_v1", NativeUpload.self),
            stats: try dylibSymbol(handle, "metallum_voxel_last_completed_stats_v1", NativeStats.self),
            debugChecksum: try dylibSymbol(handle, "metallum_voxel_debug_checksum_v1", NativeDebugChecksum.self),
            debugReadback: try dylibSymbol(handle, "metallum_voxel_debug_readback_v1", NativeDebugReadback.self)
        )
        try require(api.abiVersion() == 1, "Voxel ABI version mismatch")
        var layout = [UInt8](repeating: 0, count: 160)
        try require(layout.withUnsafeMutableBytes { api.layout($0.baseAddress, UInt64($0.count)) } == 1,
                    "Voxel ABI layout rejected")
        try require(readUInt32(layout, 8) == magic && readUInt32(layout, 12) == UInt32(headerBytes),
                    "Voxel ABI layout constants changed")

        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
            throw ValidationFailure.message("Metal device/queue unavailable")
        }
        let layouts = layout64()
        let context = layouts.withUnsafeBytes {
            api.create(objectPointer(device), lightingGeneration, clipmapGeneration, worldGeneration,
                       $0.baseAddress, UInt64($0.count), 1, 8, 65_536)
        }
        guard let context else {
            throw ValidationFailure.message(
                "Voxel context creation failed (set METALLUM_VOXEL_SHADER_SOURCE for source fallback)"
            )
        }
        defer { api.release(context) }

        var malformed = noPatchPacket()
        writeUInt32(0, 0, &malformed)
        let malformedStatus = try invokeUpload(api, context: context, queue: queue, packet: malformed).0
        try require(malformedStatus == -2, "Malformed magic was not rejected")

        var occupancy = [UInt32](repeating: 0, count: 1024)
        occupancy[0] = 0x0000_000f
        occupancy[1023] = 0xa5a5_5a5a
        var optical = [UInt8](repeating: 0, count: 512)
        optical[0] = 0x3c
        optical[511] = 0x81
        let patch = Patch(
            level: 0, x: 1, y: 0, z: 1,
            logicalX: 1, logicalY: 0, logicalZ: -1, contentStamp: 7,
            occupancy: occupancy, optical: optical
        )

        let update = packet(patches: [patch])
        try require(try invokeUpload(api, context: context, queue: queue, packet: update).0 == 1,
                    "Valid indirect voxel patch was rejected")
        let telemetryFirst = packet(
            slot: 1, patches: [patch], frame: 2, coalescedDelta: 3, rejectedDelta: 2
        )
        let telemetrySecond = packet(
            slot: 2, patches: [patch], frame: 3, coalescedDelta: 4, rejectedDelta: 5
        )
        let telemetryFirstStatus = try invokeUpload(
            api, context: context, queue: queue, packet: telemetryFirst
        ).0
        let telemetrySecondStatus = try invokeUpload(
            api, context: context, queue: queue, packet: telemetrySecond
        ).0
        try require(telemetryFirstStatus == 1 && telemetrySecondStatus == 1,
                    "Delta telemetry packet was rejected")
        var deltaStats = [UInt8](repeating: 0, count: 160)
        _ = deltaStats.withUnsafeMutableBytes { api.stats(context, $0.baseAddress, 160) }
        try require(readUInt64(deltaStats, 64) == 7 && readUInt64(deltaStats, 72) == 8,
                    "Packet queue telemetry deltas were not accumulated exactly once")

        let occupancyBytesRead = try privateBytes(api, context: context, kind: 0, index: 0, device: device, queue: queue)
        let toroidalWord = ((32 * 64 + 0) * 2 + 1) // brick (1,0,1), local row 0.
        try require(readUInt32(occupancyBytesRead, toroidalWord * 4) == occupancy[0],
                    "Toroidal occupancy destination is wrong")
        try require(readUInt32(occupancyBytesRead, 0) == 0,
                    "First L5 upload left untouched occupancy undefined")
        let opticalRead = try privateBytes(api, context: context, kind: 1, index: 0, device: device, queue: queue)
        let toroidalOptical = ((8 * 16 + 0) * 16 + 8) // base brick edge is 8 at 4x.
        try require(opticalRead[toroidalOptical] == optical[0], "Toroidal optical destination is wrong")
        let initialMetadata = try privateBytes(
            api, context: context, kind: 2, index: 0, device: device, queue: queue
        )
        try require(initialMetadata.prefix(16).allSatisfy { $0 == 0 },
                    "First L5 upload left an untouched brick tag undefined")
        let indirect = try privateBytes(api, context: context, kind: 4, index: 0, device: device, queue: queue)
        try require(readUInt32(indirect, 0) == 1 && readUInt32(indirect, 4) == 1,
                    "Indirect dispatch did not use actual patch count")

        // Both first uploads are encoded before either command buffer completes. The initial
        // full clear therefore must already have completed during context creation; otherwise
        // the later command's clear erases the earlier destination.
        let concurrentContext = layouts.withUnsafeBytes {
            api.create(objectPointer(device), lightingGeneration + 20, clipmapGeneration + 20,
                       worldGeneration + 20, $0.baseAddress, UInt64($0.count), 1, 8, 65_536)
        }
        guard let concurrentContext else {
            throw ValidationFailure.message("Concurrent-first-upload context creation failed")
        }
        defer { api.release(concurrentContext) }
        var firstWords = [UInt32](repeating: 0, count: 1024)
        firstWords[0] = 0x1357_9bdf
        var secondWords = [UInt32](repeating: 0, count: 1024)
        secondWords[0] = 0x2468_ace0
        let firstPatch = Patch(
            level: 0, x: 0, y: 0, z: 0,
            logicalX: 0, logicalY: 0, logicalZ: 0, contentStamp: 1,
            occupancy: firstWords, optical: [UInt8](repeating: 0x11, count: 512)
        )
        let secondPatch = Patch(
            level: 0, x: 1, y: 0, z: 0,
            logicalX: 1, logicalY: 0, logicalZ: 0, contentStamp: 2,
            occupancy: secondWords, optical: [UInt8](repeating: 0x22, count: 512)
        )
        let concurrentFirst = packet(
            slot: 0, patches: [firstPatch],
            lighting: lightingGeneration + 20, clipmap: clipmapGeneration + 20,
            world: worldGeneration + 20
        )
        let concurrentSecond = packet(
            slot: 1, patches: [secondPatch],
            lighting: lightingGeneration + 20, clipmap: clipmapGeneration + 20,
            world: worldGeneration + 20
        )
        guard let firstCommand = queue.makeCommandBuffer(),
              let secondCommand = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Could not create concurrent first-upload commands")
        }
        try require(concurrentFirst.withUnsafeBytes {
            api.upload(concurrentContext, objectPointer(firstCommand), $0.baseAddress, UInt64($0.count))
        } == 1, "First concurrent L5 upload was rejected")
        try require(concurrentSecond.withUnsafeBytes {
            api.upload(concurrentContext, objectPointer(secondCommand), $0.baseAddress, UInt64($0.count))
        } == 1, "Second concurrent L5 upload was rejected")
        firstCommand.commit()
        secondCommand.commit()
        firstCommand.waitUntilCompleted()
        secondCommand.waitUntilCompleted()
        try require(firstCommand.status == .completed && secondCommand.status == .completed,
                    "Concurrent first L5 command failed")
        let concurrentOccupancy = try privateBytes(
            api, context: concurrentContext, kind: 0, index: 0, device: device, queue: queue
        )
        try require(readUInt32(concurrentOccupancy, 0) == firstWords[0]
                        && readUInt32(concurrentOccupancy, 1 * 4) == secondWords[0],
                    "Later first-upload initialization erased an earlier L5 destination")

        // Normal scroll can reuse the same physical toroidal slot. The metadata tag
        // must change to the new logical coordinate/content stamp rather than appear
        // valid solely because the context generations remain unchanged.
        var scrolledOccupancy = occupancy
        scrolledOccupancy[0] = 0xf0f0_0001
        let scrolled = Patch(
            level: 0, x: 1, y: 0, z: 1,
            logicalX: 3, logicalY: 0, logicalZ: -1, contentStamp: 8,
            occupancy: scrolledOccupancy, optical: optical
        )
        try require(try invokeUpload(
            api, context: context, queue: queue, packet: packet(patches: [scrolled])
        ).0 == 1, "Normal-scroll patch was rejected")
        let metadataRead = try privateBytes(api, context: context, kind: 2, index: 0, device: device, queue: queue)
        let metadataIndex = ((1 * 2 + 0) * 2 + 1) * 16
        try require(readUInt32(metadataRead, metadataIndex) == 3,
                    "Reused toroidal slot kept a stale logical-brick tag")
        try require(readUInt32(metadataRead, metadataIndex + 8) == UInt32(bitPattern: -1),
                    "Logical negative toroidal coordinate was not retained")
        try require(readUInt32(metadataRead, metadataIndex + 12) == 8,
                    "Reused toroidal slot kept a stale content stamp")

        guard let checksumCommand = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Could not create checksum command")
        }
        try require(api.debugChecksum(context, objectPointer(checksumCommand), 0, 2) == 1,
                    "Diagnostic checksum was rejected")
        checksumCommand.commit()
        checksumCommand.waitUntilCompleted()
        var checksum = [UInt8](repeating: 0, count: 16)
        try require(checksum.withUnsafeMutableBytes { api.debugReadback(context, $0.baseAddress, 16) } == 1,
                    "Diagnostic checksum readback failed")
        try require(readUInt32(checksum, 0) == 1,
                    "Diagnostic checksum readback ABI is invalid")

        var stale = update
        writeUInt64(worldGeneration + 1, 48, &stale)
        try require(try invokeUpload(api, context: context, queue: queue, packet: stale).0 == -9,
                    "Stale world generation was not rejected")
        var outOfBounds = update
        writeUInt32(2, headerBytes + 4, &outOfBounds)
        try require(try invokeUpload(api, context: context, queue: queue, packet: outOfBounds).0 == -6,
                    "Out-of-bounds brick destination was not rejected")
        var badRange = update
        writeUInt32(UInt32((badRange.count - 4) & ~3), headerBytes + 16, &badRange)
        try require(try invokeUpload(api, context: context, queue: queue, packet: badRange).0 == -7,
                    "Out-of-range patch payload was not rejected")

        let reset = noPatchPacket(flags: 1)
        try require(try invokeUpload(api, context: context, queue: queue, packet: reset).0 == 1,
                    "Reset packet was rejected")
        let afterReset = try privateBytes(api, context: context, kind: 0, index: 0, device: device, queue: queue)
        try require(readUInt32(afterReset, toroidalWord * 4) == 0, "Reset did not clear occupancy")
        try require(afterReset.suffix(64).allSatisfy { $0 == 0xa5 },
                    "Occupancy guard region changed after reset/OOB validation")
        try require(try invokeUpload(api, context: context, queue: queue, packet: update).0 == 1,
                    "Patch after reset was rejected")
        try require(try invokeUpload(api, context: context, queue: queue, packet: noPatchPacket(flags: 2)).0 == 1,
                    "Unload clear packet was rejected")
        let afterUnload = try privateBytes(api, context: context, kind: 1, index: 0, device: device, queue: queue)
        try require(afterUnload[toroidalOptical] == 0, "Unload did not clear optical metadata")
        try require(afterUnload.suffix(64).allSatisfy { $0 == 0xa5 },
                    "Optical guard region changed after unload/OOB validation")

        guard let emptyCommand = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Could not create empty command")
        }
        let empty = noPatchPacket()
        let emptyStatus = empty.withUnsafeBytes {
            api.upload(context, objectPointer(emptyCommand), $0.baseAddress, UInt64($0.count))
        }
        try require(emptyStatus == 1 && emptyCommand.status == .notEnqueued,
                    "Zero patch packet encoded work or scanned the clipmap")

        let heldPacket = packet(slot: 1, patches: [patch])
        let held = try invokeUpload(api, context: context, queue: queue, packet: heldPacket, commit: false)
        try require(held.0 == 1, "Could not reserve voxel ring slot")
        let busy = try invokeUpload(api, context: context, queue: queue, packet: heldPacket, commit: false)
        try require(busy.0 == busyStatus, "Voxel ring busy did not return dedicated transient status")
        held.1.commit()
        held.1.waitUntilCompleted()
        try require(held.1.status == .completed, "Held voxel upload failed")
        try require(try invokeUpload(api, context: context, queue: queue, packet: heldPacket).0 == 1,
                    "Voxel ring slot was not released on completion")

        // A multi-level packet must be compacted by level: each indirect argument
        // contains only that level's dirty records, so aggregate groups == patchCount.
        let multiLayouts = twoLevelLayouts()
        let multiContext = multiLayouts.withUnsafeBytes {
            api.create(objectPointer(device), lightingGeneration + 10, clipmapGeneration + 10,
                       worldGeneration + 10, $0.baseAddress, UInt64($0.count), 2, 8, 65_536)
        }
        guard let multiContext else {
            throw ValidationFailure.message("Multi-level voxel context creation failed")
        }
        defer { api.release(multiContext) }
        var level0Words = [UInt32](repeating: 0, count: 1024)
        level0Words[0] = 0x11
        var level1Words = [UInt32](repeating: 0, count: 1024)
        level1Words[0] = 0x22
        let level0 = Patch(
            level: 0, x: 0, y: 0, z: 0,
            logicalX: 0, logicalY: 0, logicalZ: 0, contentStamp: 1,
            occupancy: level0Words, optical: [UInt8](repeating: 0x31, count: 512)
        )
        let level1 = Patch(
            level: 1, x: 1, y: 1, z: 1,
            logicalX: -1, logicalY: -1, logicalZ: -1, contentStamp: 2,
            occupancy: level1Words, optical: [UInt8](repeating: 0x42, count: 4096)
        )
        let multiPacket = packet(
            patches: [level0, level1], levelCount: 2,
            lighting: lightingGeneration + 10, clipmap: clipmapGeneration + 10,
            world: worldGeneration + 10
        )
        try require(try invokeUpload(
            api, context: multiContext, queue: queue, packet: multiPacket
        ).0 == 1, "Sorted multi-level packet was rejected")
        let multiIndirect = try privateBytes(
            api, context: multiContext, kind: 4, index: 0, device: device, queue: queue
        )
        try require(multiIndirect.count == 24, "Multi-level indirect buffer size is wrong")
        try require(readUInt32(multiIndirect, 0) == 1 && readUInt32(multiIndirect, 12) == 1,
                    "Per-level indirect counts were not compacted")
        try require(readUInt32(multiIndirect, 0) + readUInt32(multiIndirect, 12) == 2,
                    "Aggregate indirect groups do not equal actual dirty patch count")
        let level1Read = try privateBytes(
            api, context: multiContext, kind: 0, index: 1, device: device, queue: queue
        )
        let level1Word = ((32 * 64 + 32) * 2 + 1)
        try require(readUInt32(level1Read, level1Word * 4) == 0x22,
                    "Per-level indirect dispatch did not update level 1")
        let unsorted = packet(
            patches: [level1, level0], levelCount: 2,
            lighting: lightingGeneration + 10, clipmap: clipmapGeneration + 10,
            world: worldGeneration + 10
        )
        try require(try invokeUpload(
            api, context: multiContext, queue: queue, packet: unsorted
        ).0 == -6, "Unsorted multi-level records were not rejected")

        var stats = [UInt8](repeating: 0, count: 160)
        _ = stats.withUnsafeMutableBytes { api.stats(context, $0.baseAddress, 160) }
        try require(readUInt32(stats, 104) > 0, "Ring high-water telemetry was not populated")

        // Storage and guards are initialized synchronously during context creation. An upload
        // encoded into a command buffer that is abandoned must not prevent a later upload.
        let retryContext = layouts.withUnsafeBytes {
            api.create(objectPointer(device), lightingGeneration + 1, clipmapGeneration + 1,
                       worldGeneration + 1, $0.baseAddress, UInt64($0.count), 1, 8, 65_536)
        }
        guard let retryContext else {
            throw ValidationFailure.message("Retry voxel context creation failed")
        }
        defer { api.release(retryContext) }
        let retryPatch = Patch(
            level: 0, x: 1, y: 0, z: 1,
            logicalX: 1, logicalY: 0, logicalZ: -1, contentStamp: 7,
            occupancy: occupancy, optical: optical
        )
        guard let abandonedCommand = queue.makeCommandBuffer() else {
            throw ValidationFailure.message("Could not create abandoned voxel command")
        }
        let abandonedPacket = packet(
            slot: 0, patches: [retryPatch],
            lighting: lightingGeneration + 1, clipmap: clipmapGeneration + 1,
            world: worldGeneration + 1
        )
        try require(abandonedPacket.withUnsafeBytes {
            api.upload(retryContext, objectPointer(abandonedCommand), $0.baseAddress, UInt64($0.count))
        } == 1, "Could not encode abandoned first voxel upload")
        let retryPacket = packet(
            slot: 1, patches: [retryPatch],
            lighting: lightingGeneration + 1, clipmap: clipmapGeneration + 1,
            world: worldGeneration + 1
        )
        try require(try invokeUpload(
            api, context: retryContext, queue: queue, packet: retryPacket
        ).0 == 1, "Retry after abandoned initialization upload was rejected")
        let retryOccupancy = try privateBytes(
            api, context: retryContext, kind: 0, index: 0, device: device, queue: queue
        )
        let retryToroidalWord = ((32 * 64 + 0) * 2 + 1)
        try require(readUInt32(retryOccupancy, retryToroidalWord * 4) == occupancy[0]
                        && readUInt32(retryOccupancy, 0) == 0
                        && retryOccupancy.suffix(64).allSatisfy { $0 == 0xa5 },
                    "Retry after abandoned first upload did not initialize L5 private storage")
    }
}
