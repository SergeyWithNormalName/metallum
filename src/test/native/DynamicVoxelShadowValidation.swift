import Darwin
import Foundation
import Metal

private enum Failure: Error, CustomStringConvertible {
    case message(String)
    var description: String {
        switch self { case let .message(value): return value }
    }
}

private typealias VoxelCreate = @convention(c) (UnsafeMutableRawPointer?, UInt64, UInt64, UInt64,
    UnsafeRawPointer?, UInt64, UInt32, UInt32, UInt64) -> UnsafeMutableRawPointer?
private typealias VoxelRelease = @convention(c) (UnsafeMutableRawPointer?) -> Void
private typealias VoxelUpload = @convention(c) (UnsafeMutableRawPointer?, UnsafeMutableRawPointer?,
    UnsafeRawPointer?, UInt64) -> Int32
private typealias DynamicVersion = @convention(c) () -> UInt32
private typealias DynamicLayout = @convention(c) (UnsafeMutableRawPointer?, UInt64) -> Int32
private typealias DynamicCreate = @convention(c) (
    UnsafeMutableRawPointer?, UInt64, UInt64
) -> UnsafeMutableRawPointer?
private typealias DynamicRelease = @convention(c) (UnsafeMutableRawPointer?) -> Void
private typealias DynamicEncode = @convention(c) (UnsafeMutableRawPointer?, UnsafeMutableRawPointer?,
    UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UnsafeRawPointer?, UInt64) -> Int32

private let atlasSuffixOffset = 4_096
private let atlasSuffixBytes = 393_216
private let atlasTotalBytes = atlasSuffixOffset + atlasSuffixBytes

private func require(_ value: @autoclosure () throws -> Bool, _ message: String) throws {
    guard try value() else { throw Failure.message(message) }
}

private func objectPointer(_ value: AnyObject) -> UnsafeMutableRawPointer {
    Unmanaged.passUnretained(value).toOpaque()
}

private func symbol<T>(_ handle: UnsafeMutableRawPointer, _ name: String, _: T.Type) throws -> T {
    guard let value = dlsym(handle, name) else { throw Failure.message("missing symbol \(name)") }
    return unsafeBitCast(value, to: T.self)
}

private func put32(_ value: UInt32, _ offset: Int, _ bytes: inout [UInt8]) {
    var little = value.littleEndian
    withUnsafeBytes(of: &little) { bytes.replaceSubrange(offset..<(offset + 4), with: $0) }
}

private func put64(_ value: UInt64, _ offset: Int, _ bytes: inout [UInt8]) {
    var little = value.littleEndian
    withUnsafeBytes(of: &little) { bytes.replaceSubrange(offset..<(offset + 8), with: $0) }
}

private func putFloat(_ value: Float, _ offset: Int, _ bytes: inout [UInt8]) {
    put32(value.bitPattern, offset, &bytes)
}

private func read32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
    bytes.withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)) }
}

private func readFloat(_ bytes: [UInt8], _ offset: Int) -> Float {
    Float(bitPattern: read32(bytes, offset))
}

private func l5Layout() -> [UInt8] {
    var value = [UInt8](repeating: 0, count: 32)
    put32(64, 0, &value)
    put32(4, 4, &value)
    return value
}

private enum FixtureKind { case layered, slab, fence }

private func fixtureOccupied(_ kind: FixtureKind, _ x: Int, _ y: Int, _ z: Int) -> Bool {
    switch kind {
    case .layered: return (4..<20).contains(x)
    // A top half-slab and a narrow fence post are deliberately represented at sub-voxel
    // resolution; neither can be reduced to the block-wide optical material alone.
    case .slab: return (4..<8).contains(x) && (2..<4).contains(y)
    case .fence: return (4..<6).contains(x) && (0..<4).contains(y) && (1..<3).contains(z)
    }
}

private func fixtureMaterial(_ kind: FixtureKind, _ blockX: Int) -> UInt8 {
    switch kind {
    case .layered: return [119, 145, 182, 32][blockX - 1] // glass, foliage, water, opaque
    case .slab, .fence: return 32 // opaque geometry
    }
}

private func fixtureChromaticId(_ kind: FixtureKind, _ blockX: Int) -> UInt8 {
    switch kind {
    case .layered:
        // red stained glass, green foliage, light-blue water, then opaque neutral.
        return [14, 13, 3, 0][blockX - 1]
    case .slab, .fence:
        return 0
    }
}

private func chromaticFilter(_ paletteId: UInt8) -> SIMD3<Float> {
    switch paletteId {
    case 0: SIMD3(1.000, 1.000, 1.000)
    case 1: SIMD3(1.000, 0.250, 0.030)
    case 2: SIMD3(1.000, 0.080, 0.680)
    case 3: SIMD3(0.100, 0.500, 1.000)
    case 4: SIMD3(1.000, 0.850, 0.050)
    case 5: SIMD3(0.250, 1.000, 0.040)
    case 6: SIMD3(1.000, 0.250, 0.400)
    case 7: SIMD3(0.230, 0.250, 0.250)
    case 8: SIMD3(0.600, 0.600, 0.580)
    case 9: SIMD3(0.030, 0.650, 0.650)
    case 10: SIMD3(0.320, 0.040, 0.600)
    case 11: SIMD3(0.040, 0.070, 0.650)
    case 12: SIMD3(0.200, 0.050, 0.015)
    case 13: SIMD3(0.080, 0.350, 0.010)
    case 14: SIMD3(1.000, 0.040, 0.025)
    default: SIMD3(0.005, 0.005, 0.006)
    }
}

private func packRgb(_ value: SIMD3<Float>) -> UInt32 {
    func quantize(_ component: Float) -> UInt32 {
        UInt32(max(0, min(255, Int((max(0, min(1, component)) * 255).rounded()))))
    }
    return 0xff00_0000 | quantize(value.x) | (quantize(value.y) << 8) | (quantize(value.z) << 16)
}

private func multiply(_ value: SIMD3<Float>, _ scalar: Float, _ filter: SIMD3<Float>) -> SIMD3<Float> {
    SIMD3(value.x * scalar * filter.x, value.y * scalar * filter.y, value.z * scalar * filter.z)
}

private func fixtureBlockOccupied(_ kind: FixtureKind, _ blockX: Int, _ blockY: Int, _ blockZ: Int) -> Bool {
    for z in (blockZ * 4)..<(blockZ * 4 + 4) {
        for y in (blockY * 4)..<(blockY * 4 + 4) {
            for x in (blockX * 4)..<(blockX * 4 + 4) where fixtureOccupied(kind, x, y, z) { return true }
        }
    }
    return false
}

private func floorMod(_ value: Int32, _ divisor: Int32) -> UInt32 {
    let remainder = value % divisor
    return UInt32(remainder < 0 ? remainder + divisor : remainder)
}

private func l5PlanesPacket(_ kind: FixtureKind = .layered, logicalBrickX: Int32 = 0) -> [UInt8] {
    let header = 96, record = 64, occupancyBytes = 4096, opticalBytes = 512, chromaticBytes = 256
    let payloadOffset = header + record
    var value = [UInt8](repeating: 0, count: payloadOffset + occupancyBytes + opticalBytes + chromaticBytes)
    put32(0x3142_564d, 0, &value); put32(2, 4, &value); put32(UInt32(value.count), 8, &value)
    put32(UInt32(record), 16, &value); put32(1, 20, &value); put32(0, 24, &value); put32(1, 28, &value)
    put64(101, 32, &value); put64(202, 40, &value); put64(303, 48, &value); put64(1, 56, &value)
    put32(UInt32(occupancyBytes + opticalBytes + chromaticBytes), 64, &value); put32(UInt32(payloadOffset), 68, &value)
    let r = header
    put32(0, r, &value); put32(floorMod(logicalBrickX, 2), r + 4, &value); put32(0, r + 8, &value); put32(0, r + 12, &value)
    put32(UInt32(payloadOffset), r + 16, &value); put32(UInt32(occupancyBytes), r + 20, &value)
    put32(UInt32(opticalBytes), r + 24, &value); put32(UInt32(chromaticBytes), r + 28, &value)
    put32(202, r + 36, &value); put32(0, r + 40, &value)
    put32(UInt32(bitPattern: logicalBrickX), r + 44, &value); put32(1, r + 56, &value)
    for z in 0..<32 { for y in 0..<32 {
        let word = payloadOffset + (z * 32 + y) * 4
        var bits = read32(value, word)
        for x in 0..<32 where fixtureOccupied(kind, x, y, z) { bits |= UInt32(1) << UInt32(x) }
        put32(bits, word, &value)
    } }
    let optical = payloadOffset + occupancyBytes
    for z in 0..<8 { for y in 0..<8 { for blockX in 0..<8 where fixtureBlockOccupied(kind, blockX, y, z) {
        let index = (z * 8 + y) * 8 + blockX
        value[optical + index] = fixtureMaterial(kind, max(1, blockX))
        let chromatic = optical + opticalBytes + index / 2
        let shift = (index & 1) * 4
        value[chromatic] = (value[chromatic] & ~(UInt8(15) << UInt8(shift)))
            | (fixtureChromaticId(kind, max(1, blockX)) << UInt8(shift))
    } } }
    return value
}

private func dynamicPacket(
    edge: UInt32 = 16,
    sourceBlockX: Int32 = 0,
    atlasOffset: UInt64 = UInt64(atlasSuffixOffset)
) -> [UInt8] {
    var value = [UInt8](repeating: 0, count: 112)
    put32(0x3153_564d, 0, &value); put32(1, 4, &value); put32(112, 8, &value); put32(1, 12, &value)
    put64(101, 16, &value); put64(202, 24, &value); put64(303, 32, &value); put64(1, 40, &value)
    let r = 48
    put64(77, r, &value); put64(atlasOffset, r + 8, &value); put32(0, r + 16, &value)
    put32(edge, r + 20, &value); put32(96, r + 24, &value)
    put32(UInt32(bitPattern: sourceBlockX), r + 32, &value)
    putFloat(0.5, r + 44, &value); putFloat(0.5, r + 48, &value); putFloat(0.5, r + 52, &value); putFloat(8.0, r + 56, &value)
    return value
}

private func dynamicBatchPacket() -> [UInt8] {
    let single = dynamicPacket()
    var value = [UInt8](repeating: 0, count: 176)
    value.replaceSubrange(0..<48, with: single[0..<48])
    value.replaceSubrange(48..<112, with: single[48..<112])
    value.replaceSubrange(112..<176, with: single[48..<112])
    put32(176, 8, &value); put32(2, 12, &value)
    put64(78, 112, &value)
    put64(UInt64(atlasSuffixOffset + 196_608), 120, &value); put32(32, 132, &value)
    return value
}

// Intentionally independent from the Metal kernel: a compact double-precision DDA over the
// deterministic fixture planes. Comparing every face/texel catches cube-face orientation and
// seam regressions, not merely the hand-picked centre ray.
private func cubeDirection(_ face: Int, _ x: Int, _ y: Int, _ edge: Int) -> SIMD3<Double> {
    let u = 2.0 * (Double(x) + 0.5) / Double(edge) - 1.0
    let v = 2.0 * (Double(y) + 0.5) / Double(edge) - 1.0
    let raw: SIMD3<Double> = switch face {
    case 0: SIMD3(1, -v, -u); case 1: SIMD3(-1, -v, u)
    case 2: SIMD3(u, 1, v); case 3: SIMD3(u, -1, -v)
    case 4: SIMD3(u, -v, 1); default: SIMD3(-u, -v, -1)
    }
    return raw / sqrt(raw.x * raw.x + raw.y * raw.y + raw.z * raw.z)
}

private func referenceTrace(_ direction: SIMD3<Double>, _ kind: FixtureKind = .layered) -> [(Float, UInt32)] {
    let radius = 8.0, subdivision = 4.0, startDistance = min(0.08 / subdivision, radius * 0.02)
    let source = SIMD3<Double>(repeating: 2.0)
    let start = source + direction * (startDistance * subdivision)
    let end = source + direction * (radius * subdivision)
    let delta = end - start
    var cell = SIMD3<Int>(Int(floor(start.x)), Int(floor(start.y)), Int(floor(start.z)))
    // The fixture contains exactly one resident 8x8x8-block L5 brick.  The shader stops a
    // ray when its toroidal metadata no longer names that brick; modelling those bounds here
    // is essential -- treating the four planes as infinite was a false CPU reference.
    let endCell = SIMD3<Int>(Int(floor(end.x)), Int(floor(end.y)), Int(floor(end.z)))
    let step = SIMD3<Int>(delta.x > 0 ? 1 : delta.x < 0 ? -1 : 0, delta.y > 0 ? 1 : delta.y < 0 ? -1 : 0, delta.z > 0 ? 1 : delta.z < 0 ? -1 : 0)
    func axis(_ coordinate: Double, _ cell: Int, _ delta: Double, _ step: Int) -> (Double, Double) {
        guard step != 0 else { return (.infinity, .infinity) }
        return ((Double(step > 0 ? cell + 1 : cell) - coordinate) / delta, 1.0 / abs(delta))
    }
    var x = axis(start.x, cell.x, delta.x, step.x), y = axis(start.y, cell.y, delta.y, step.y), z = axis(start.z, cell.z, delta.z, step.z)
    var output = Array(repeating: (Float.infinity, UInt32(0xffff_ffff)), count: 4)
    var hitCount = 0, visibility = SIMD3<Float>(repeating: 1), entry = 0.0
    var lastBlock = SIMD3<Int>(repeating: Int.min)
    for _ in 0..<96 {
        if cell == endCell { break }
        guard (0..<32).contains(cell.x), (0..<32).contains(cell.y), (0..<32).contains(cell.z) else { break }
        let block = SIMD3<Int>(Int(floor(Double(cell.x) / subdivision)), Int(floor(Double(cell.y) / subdivision)), Int(floor(Double(cell.z) / subdivision)))
        if fixtureOccupied(kind, cell.x, cell.y, cell.z) && block != lastBlock {
            let transmittance = Float(fixtureMaterial(kind, max(1, block.x)) & 31) / 31.0
            visibility = multiply(
                visibility,
                transmittance,
                chromaticFilter(fixtureChromaticId(kind, max(1, block.x)))
            )
            let hit = (Float(max(0, startDistance + entry * (radius - startDistance))), packRgb(visibility))
            output[min(hitCount, 3)] = hit
            hitCount += 1
            lastBlock = block
            if visibility == SIMD3<Float>(repeating: 0) { break }
        }
        let next = min(x.0, min(y.0, z.0)); if !next.isFinite || next > 1 { break }
        let tie = next + 1e-10
        if x.0 <= tie { cell.x += step.x; x.0 += x.1 }; if y.0 <= tie { cell.y += step.y; y.0 += y.1 }; if z.0 <= tie { cell.z += step.z; z.0 += z.1 }
        entry = next
    }
    return output
}

private func compareReference(_ page: [UInt8], _ atlasOffset: Int, _ edge: Int, _ kind: FixtureKind = .layered) throws {
    for face in 0..<6 { for y in 0..<edge { for x in 0..<edge {
        let expected = referenceTrace(cubeDirection(face, x, y, edge), kind)
        let base = atlasOffset + ((face * edge * edge + y * edge + x) * 4 * 8)
        for layer in 0..<4 {
            let distance = readFloat(page, base + layer * 8), packedRgb = read32(page, base + layer * 8 + 4)
            if expected[layer].0.isFinite {
                try require(distance.isFinite && abs(distance - expected[layer].0) < 0.04
                                && packedRgb == expected[layer].1,
                            "GPU/CPU L6 mismatch face=\(face) texel=\(x),\(y) layer=\(layer)")
            } else {
                try require(!distance.isFinite && packedRgb == 0xffff_ffff,
                            "GPU wrote an unexpected hit face=\(face) texel=\(x),\(y) layer=\(layer)")
            }
        }
    } } }
}

private func requireFixtureHit(_ page: [UInt8], _ label: String) throws {
    let hitCount = stride(from: 0, to: page.count, by: 8).reduce(0) {
        $0 + (readFloat(page, $1).isFinite ? 1 : 0)
    }
    try require(hitCount > 0, "\(label) fixture produced no covered shadow sample")
}

private func uploadFixture(
    _ packet: [UInt8], _ voxel: UnsafeMutableRawPointer, _ queue: MTLCommandQueue, _ upload: VoxelUpload
) throws {
    guard let command = queue.makeCommandBuffer() else { throw Failure.message("fixture upload command unavailable") }
    try require(packet.withUnsafeBytes { upload(voxel, objectPointer(command), $0.baseAddress, UInt64($0.count)) } == 1,
                "L5 fixture upload rejected")
    command.commit(); command.waitUntilCompleted()
    try require(command.status == .completed, "L5 fixture upload failed")
}

private func renderSinglePage(
    _ packet: [UInt8], _ voxel: UnsafeMutableRawPointer, _ dynamic: UnsafeMutableRawPointer,
    _ device: MTLDevice, _ queue: MTLCommandQueue, _ encode: DynamicEncode
) throws -> [UInt8] {
    let bytes = 196_608 // 6 * 16 * 16 * four eight-byte distance/RGB layers
    guard let atlas = device.makeBuffer(length: atlasTotalBytes, options: .storageModePrivate),
          let fence = device.makeFence(), let command = queue.makeCommandBuffer(),
          let seed = command.makeBlitCommandEncoder() else { throw Failure.message("single-page command unavailable") }
    seed.updateFence(fence); seed.endEncoding()
    try require(packet.withUnsafeBytes {
        encode(dynamic, voxel, objectPointer(command), objectPointer(atlas), objectPointer(fence), $0.baseAddress, UInt64($0.count))
    } == 1, "single-page dynamic encode rejected")
    command.commit(); command.waitUntilCompleted()
    try require(command.status == .completed, "single-page dynamic command failed")
    guard let readback = device.makeBuffer(length: bytes, options: .storageModeShared),
          let copy = queue.makeCommandBuffer(), let blit = copy.makeBlitCommandEncoder() else {
        throw Failure.message("single-page readback unavailable")
    }
    blit.copy(
        from: atlas,
        sourceOffset: atlasSuffixOffset,
        to: readback,
        destinationOffset: 0,
        size: bytes
    ); blit.endEncoding()
    copy.commit(); copy.waitUntilCompleted()
    try require(copy.status == .completed, "single-page readback failed")
    return Array(UnsafeBufferPointer(start: readback.contents().bindMemory(to: UInt8.self, capacity: bytes), count: bytes))
}

@main private enum Main {
    static func main() {
        do { try run(); print("Dynamic voxel-shadow validation passed") }
        catch { fputs("Dynamic voxel-shadow validation failed: \(error)\n", stderr); exit(1) }
    }

    static func run() throws {
        try require(CommandLine.arguments.count == 2, "Usage: DynamicVoxelShadowValidation <dylib>")
        guard let library = dlopen(CommandLine.arguments[1], RTLD_NOW | RTLD_LOCAL) else {
            throw Failure.message(String(cString: dlerror()))
        }
        defer { dlclose(library) }
        let voxelCreate = try symbol(library, "metallum_voxel_create_context_v1", VoxelCreate.self)
        let voxelRelease = try symbol(library, "metallum_voxel_release_context_v1", VoxelRelease.self)
        let voxelUpload = try symbol(library, "metallum_voxel_upload_apply_v1", VoxelUpload.self)
        let version = try symbol(library, "metallum_dynamic_shadow_abi_version_v1", DynamicVersion.self)
        let layout = try symbol(library, "metallum_dynamic_shadow_layout_v1", DynamicLayout.self)
        let create = try symbol(library, "metallum_dynamic_shadow_create_context_v1", DynamicCreate.self)
        let release = try symbol(library, "metallum_dynamic_shadow_release_context_v1", DynamicRelease.self)
        let encode = try symbol(library, "metallum_dynamic_shadow_encode_v1", DynamicEncode.self)
        try require(version() == 1, "dynamic ABI version changed")
        var layoutBytes = [UInt8](repeating: 0, count: 32)
        try require(layoutBytes.withUnsafeMutableBytes { layout($0.baseAddress, 32) } == 1
                    && read32(layoutBytes, 4) == 48 && read32(layoutBytes, 8) == 64
                    && read32(layoutBytes, 12) == 8 && read32(layoutBytes, 16) == 256,
                    "dynamic layout ABI changed")
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
            throw Failure.message("Metal unavailable")
        }
        let layouts = l5Layout()
        let voxel = layouts.withUnsafeBytes { voxelCreate(objectPointer(device), 101, 202, 303, $0.baseAddress, 32, 1, 8, 65_536) }
        guard let voxel else { throw Failure.message("L5 context unavailable") }
        defer { voxelRelease(voxel) }
        guard let dynamic = create(
            objectPointer(device), UInt64(atlasSuffixOffset), UInt64(atlasSuffixBytes)
        ) else { throw Failure.message("dynamic context unavailable") }
        defer { release(dynamic) }
        let patch = l5PlanesPacket()
        guard let uploadCommand = queue.makeCommandBuffer() else { throw Failure.message("upload command unavailable") }
        try require(patch.withUnsafeBytes { voxelUpload(voxel, objectPointer(uploadCommand), $0.baseAddress, UInt64($0.count)) } == 1,
                    "L5 fixture upload rejected")
        uploadCommand.commit(); uploadCommand.waitUntilCompleted()
        try require(uploadCommand.status == .completed, "L5 fixture upload failed")
        guard let atlas = device.makeBuffer(length: atlasTotalBytes, options: .storageModePrivate),
              let fence = device.makeFence(), let command = queue.makeCommandBuffer(),
              let seed = command.makeBlitCommandEncoder() else { throw Failure.message("dynamic command unavailable") }
        seed.updateFence(fence); seed.endEncoding()
        let request = dynamicBatchPacket()
        try require(request.withUnsafeBytes {
            encode(dynamic, voxel, objectPointer(command), objectPointer(atlas), objectPointer(fence), $0.baseAddress, UInt64($0.count))
        } == 1, "dynamic page encode rejected")
        command.commit(); command.waitUntilCompleted()
        try require(command.status == .completed, "dynamic page command failed")
        guard let readback = device.makeBuffer(length: atlasSuffixBytes, options: .storageModeShared),
              let copy = queue.makeCommandBuffer(), let blit = copy.makeBlitCommandEncoder() else { throw Failure.message("readback unavailable") }
        blit.copy(
            from: atlas,
            sourceOffset: atlasSuffixOffset,
            to: readback,
            destinationOffset: 0,
            size: atlasSuffixBytes
        ); blit.endEncoding()
        copy.commit(); copy.waitUntilCompleted()
        let page = Array(UnsafeBufferPointer(
            start: readback.contents().bindMemory(to: UInt8.self, capacity: atlasSuffixBytes),
            count: atlasSuffixBytes
        ))
        // The independent tracer verifies the deterministic forward cube ray at both edges;
        // other faces are covered by the native page-format/fence and seam-addressing checks.
        try compareReference(page, 0, 16)
        let ray = 7 * 16 + 7 // positive-X face's near-central texel
        let base = ray * 4 * 8
        try require(readFloat(page, base).isFinite && readFloat(page, base) > 0,
                    "opaque/glass fixture produced no first hit")
        let redGlass = multiply(
            SIMD3<Float>(repeating: 1), 23.0 / 31.0, chromaticFilter(14)
        )
        let foliage = multiply(redGlass, 17.0 / 31.0, chromaticFilter(13))
        let water = multiply(foliage, 22.0 / 31.0, chromaticFilter(3))
        try require(read32(page, base + 4) == packRgb(redGlass)
                        && read32(page, base + 12) == packRgb(foliage)
                        && read32(page, base + 20) == packRgb(water)
                        && read32(page, base + 28) == 0xff00_0000,
                    "four-layer packed RGB transmittance does not match the L6 reference")
        let secondRay = 196_608 + (15 * 32 + 15) * 4 * 8
        try require(readFloat(page, secondRay).isFinite && readFloat(page, secondRay) > 0,
                    "second request in one dynamic batch did not write its own atlas page")
        // Exact sub-voxel fixtures.  These are separate from the broad planes above so a
        // block-level approximation cannot accidentally pass the CPU/GPU comparison.
        try uploadFixture(l5PlanesPacket(.slab), voxel, queue, voxelUpload)
        let slabPage = try renderSinglePage(dynamicPacket(), voxel, dynamic, device, queue, encode)
        try requireFixtureHit(slabPage, "slab"); try compareReference(slabPage, 0, 16, .slab)
        try uploadFixture(l5PlanesPacket(.fence), voxel, queue, voxelUpload)
        let fencePage = try renderSinglePage(dynamicPacket(), voxel, dynamic, device, queue, encode)
        try requireFixtureHit(fencePage, "fence"); try compareReference(fencePage, 0, 16, .fence)

        // These are covered, resident ±30M-world-coordinate cases, not merely no-fault
        // dispatches: metadata tags and every cube face/layer are checked against the same
        // local double-precision reference as the origin fixture.
        for sourceBlockX: Int32 in [30_000_000, -30_000_000] {
            try require(sourceBlockX % 8 == 0, "large-coordinate fixture must begin on a brick boundary")
            try uploadFixture(l5PlanesPacket(.layered, logicalBrickX: sourceBlockX / 8), voxel, queue, voxelUpload)
            let largePage = try renderSinglePage(dynamicPacket(sourceBlockX: sourceBlockX), voxel, dynamic, device, queue, encode)
            try requireFixtureHit(largePage, "covered \(sourceBlockX) coordinate")
            try compareReference(largePage, 0, 16, .layered)
        }
        guard let badCommand = queue.makeCommandBuffer(), let badFence = device.makeFence(),
              let badSeed = badCommand.makeBlitCommandEncoder() else { throw Failure.message("bad packet command unavailable") }
        badSeed.updateFence(badFence); badSeed.endEncoding()
        let bad = dynamicPacket(edge: 8, sourceBlockX: 30_000_000)
        try require(bad.withUnsafeBytes {
            encode(dynamic, voxel, objectPointer(badCommand), objectPointer(atlas), objectPointer(badFence), $0.baseAddress, UInt64($0.count))
        } == -2, "invalid edge/large-coordinate packet was not rejected before dispatch")

        guard let staticCommand = queue.makeCommandBuffer(), let staticFence = device.makeFence(),
              let staticSeed = staticCommand.makeBlitCommandEncoder() else {
            throw Failure.message("static-prefix rejection command unavailable")
        }
        staticSeed.updateFence(staticFence); staticSeed.endEncoding()
        let staticOverwrite = dynamicPacket(atlasOffset: 0)
        try require(staticOverwrite.withUnsafeBytes {
            encode(dynamic, voxel, objectPointer(staticCommand), objectPointer(atlas), objectPointer(staticFence), $0.baseAddress, UInt64($0.count))
        } == -2, "dynamic request could overwrite the static atlas prefix")

        guard let overlapCommand = queue.makeCommandBuffer(), let overlapFence = device.makeFence(),
              let overlapSeed = overlapCommand.makeBlitCommandEncoder() else {
            throw Failure.message("overlap rejection command unavailable")
        }
        overlapSeed.updateFence(overlapFence); overlapSeed.endEncoding()
        var overlapping = dynamicBatchPacket()
        put64(UInt64(atlasSuffixOffset), 120, &overlapping)
        try require(overlapping.withUnsafeBytes {
            encode(dynamic, voxel, objectPointer(overlapCommand), objectPointer(atlas), objectPointer(overlapFence), $0.baseAddress, UInt64($0.count))
        } == -2, "overlapping dynamic pages were not rejected")
    }
}
