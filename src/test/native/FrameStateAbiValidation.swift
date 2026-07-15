import Darwin
import Foundation
import Metal

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case let .message(message): message
        }
    }
}

private typealias NativeValidateFunction = @convention(c) (
    UnsafeRawPointer?,
    UInt64
) -> Int32
private typealias NativeInitFunction = @convention(c) (UnsafeRawPointer?) -> Int32
private typealias NativeGenerationContractFunction = @convention(c) (UnsafeRawPointer?) -> UInt64

private func objectPointer(_ object: AnyObject) -> UnsafeRawPointer {
    UnsafeRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw ValidationFailure.message(message) }
}

private func writeUInt32(_ value: UInt32, at offset: Int, into bytes: inout [UInt8]) {
    var little = value.littleEndian
    withUnsafeBytes(of: &little) { source in
        bytes.replaceSubrange(offset..<(offset + source.count), with: source)
    }
}

private func writeUInt64(_ value: UInt64, at offset: Int, into bytes: inout [UInt8]) {
    var little = value.littleEndian
    withUnsafeBytes(of: &little) { source in
        bytes.replaceSubrange(offset..<(offset + source.count), with: source)
    }
}

private func writeFloat(_ value: Float, at offset: Int, into bytes: inout [UInt8]) {
    writeUInt32(value.bitPattern, at: offset, into: &bytes)
}

private func writeDouble(_ value: Double, at offset: Int, into bytes: inout [UInt8]) {
    writeUInt64(value.bitPattern, at: offset, into: &bytes)
}

private func validPacket() -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 816)
    writeUInt32(2, at: 0, into: &bytes)
    writeUInt32(816, at: 4, into: &bytes)
    writeUInt32(1, at: 8, into: &bytes)
    writeUInt32(2, at: 12, into: &bytes)
    writeUInt64(42, at: 16, into: &bytes)
    writeUInt64(7, at: 24, into: &bytes) // submit; slot 1
    writeUInt64(8, at: 32, into: &bytes)
    writeUInt64(9, at: 40, into: &bytes)
    writeUInt64(10, at: 48, into: &bytes)
    writeUInt64(11, at: 56, into: &bytes)
    writeUInt64(12, at: 64, into: &bytes)
    writeUInt64(13, at: 72, into: &bytes)
    writeUInt64(1, at: 80, into: &bytes) // first frame reset
    writeUInt64(1, at: 88, into: &bytes) // Spatial
    writeUInt32(0, at: 96, into: &bytes) // legacy
    writeUInt32(1, at: 100, into: &bytes) // HDR
    writeUInt32(0, at: 104, into: &bytes) // Metal 3
    writeUInt32(1, at: 108, into: &bytes) // Balanced
    writeUInt32(1280, at: 112, into: &bytes)
    writeUInt32(720, at: 116, into: &bytes)
    writeUInt32(2560, at: 120, into: &bytes)
    writeUInt32(1440, at: 124, into: &bytes)
    writeUInt32(1, at: 128, into: &bytes)
    writeFloat(1.0 / 60.0, at: 136, into: &bytes)
    writeFloat(0.05, at: 140, into: &bytes)
    writeFloat(1024, at: 144, into: &bytes)
    writeFloat(1, at: 156, into: &bytes)
    writeFloat(1, at: 160, into: &bytes)
    writeFloat(1, at: 164, into: &bytes)
    writeFloat(1, at: 168, into: &bytes)
    writeUInt64(64, at: 184, into: &bytes) // HDR resources
    writeUInt64(128, at: 200, into: &bytes) // upscale resources
    for index in 0..<6 { writeDouble(Double(index), at: 248 + index * 8, into: &bytes) }
    for matrix in 0..<8 {
        for diagonal in 0..<4 {
            writeFloat(1, at: 296 + matrix * 64 + diagonal * 20, into: &bytes)
        }
    }
    return bytes
}

private func validate(_ function: NativeValidateFunction, _ bytes: [UInt8]) -> Int32 {
    bytes.withUnsafeBytes { function($0.baseAddress, UInt64($0.count)) }
}

private func generationPacket(
    _ source: [UInt8],
    generation: UInt64,
    lightingMode: UInt32,
    outputMode: UInt32,
    spatial: Bool
) -> [UInt8] {
    var bytes = source
    writeUInt64(generation, at: 32, into: &bytes)
    writeUInt64(generation, at: 48, into: &bytes)
    writeUInt64(generation, at: 56, into: &bytes)
    writeUInt32(lightingMode, at: 96, into: &bytes)
    writeUInt32(outputMode, at: 100, into: &bytes)
    writeUInt64(spatial ? 1 : 0, at: 88, into: &bytes)
    writeUInt64(outputMode == 0 ? 0 : 64, at: 184, into: &bytes)
    writeUInt64(spatial ? 128 : 0, at: 200, into: &bytes)
    return bytes
}

@main
private enum FrameStateAbiValidationMain {
    static func main() {
        do {
            try require(CommandLine.arguments.count == 2,
                        "Usage: FrameStateAbiValidation <libmetallum.dylib>")
            guard let handle = dlopen(CommandLine.arguments[1], RTLD_NOW | RTLD_LOCAL) else {
                throw ValidationFailure.message("Could not load native library")
            }
            defer { dlclose(handle) }
            guard let symbol = dlsym(handle, "metallum_validate_frame_state_v2"),
                  let setSymbol = dlsym(handle, "metallum_set_frame_state_v2"),
                  let initSymbol = dlsym(handle, "metallum_init_pipelines"),
                  let contractSymbol = dlsym(
                    handle,
                    "metallum_renderer_generation_native_contract_v1"
                  ) else {
                throw ValidationFailure.message("Native FrameState ABI validator symbol is missing")
            }
            let nativeValidate = unsafeBitCast(symbol, to: NativeValidateFunction.self)
            let nativeSet = unsafeBitCast(setSymbol, to: NativeValidateFunction.self)
            let nativeInit = unsafeBitCast(initSymbol, to: NativeInitFunction.self)
            let nativeContract = unsafeBitCast(
                contractSymbol,
                to: NativeGenerationContractFunction.self
            )
            let valid = validPacket()
            try require(validate(nativeValidate, valid) == 1, "Valid FrameState packet was rejected")

            var invalidVersion = valid
            writeUInt32(1, at: 0, into: &invalidVersion)
            try require(validate(nativeValidate, invalidVersion) == -2, "Version mismatch was accepted")
            var lightingLeak = valid
            writeUInt64(1, at: 192, into: &lightingLeak)
            try require(validate(nativeValidate, lightingLeak) == -5, "Legacy lighting bytes were accepted")
            var sdrHdrLeak = valid
            writeUInt32(0, at: 100, into: &sdrHdrLeak)
            try require(validate(nativeValidate, sdrHdrLeak) == -6, "SDR HDR bytes were accepted")
            var nativeUpscaleLeak = valid
            writeUInt64(0, at: 88, into: &nativeUpscaleLeak)
            try require(validate(nativeValidate, nativeUpscaleLeak) == -7,
                        "Native-resolution upscale bytes were accepted")
            var interpolationLeak = valid
            writeUInt64(1, at: 208, into: &interpolationLeak)
            try require(validate(nativeValidate, interpolationLeak) == -8,
                        "Disabled interpolation bytes were accepted")
            var badSlot = valid
            writeUInt32(2, at: 128, into: &badSlot)
            try require(validate(nativeValidate, badSlot) == -4, "Mismatched in-flight slot was accepted")
            var nonFiniteMatrix = valid
            writeFloat(.nan, at: 296, into: &nonFiniteMatrix)
            try require(validate(nativeValidate, nonFiniteMatrix) == -4, "NaN transform was accepted")
            let truncated = valid.withUnsafeBytes { nativeValidate($0.baseAddress, 815) }
            try require(truncated == -1, "Truncated FrameState packet was accepted")

            guard let device = MTLCreateSystemDefaultDevice() else {
                throw ValidationFailure.message("No Metal device is available")
            }
            let devicePointer = objectPointer(device as AnyObject)
            try require(nativeInit(devicePointer) > 0, "Base SDR pipeline initialization failed")
            try require(nativeContract(devicePointer) == 1, "Base initialization created HDR PSOs/resources")

            let legacyHdr = generationPacket(
                valid,
                generation: 100,
                lightingMode: 0,
                outputMode: 1,
                spatial: true
            )
            try require(validate(nativeSet, legacyHdr) == 1, "Legacy HDR generation prewarm failed")
            let legacyHdrContract = nativeContract(devicePointer)
            try require(
                legacyHdrContract & ((1 << 2) | (1 << 3) | (1 << 7))
                    == ((1 << 2) | (1 << 3) | (1 << 7)),
                "Legacy HDR generation is missing effects/reconstruction/fallback PSOs"
            )
            try require(
                legacyHdrContract & ((1 << 4) | (1 << 5) | (1 << 6)) == 0,
                "Legacy HDR generation leaked actual-HDR PSOs or eager workspace"
            )

            let actualHdr = generationPacket(
                valid,
                generation: 101,
                lightingMode: 1,
                outputMode: 1,
                spatial: true
            )
            try require(validate(nativeSet, actualHdr) == 1, "METALLUM HDR generation prewarm failed")
            let actualHdrContract = nativeContract(devicePointer)
            try require(
                actualHdrContract == ((1 << 0) | (1 << 1) | (1 << 4) | (1 << 5) | (1 << 8) | (1 << 9) | (1 << 10)),
                "METALLUM HDR generation prewarmed an incomplete or non-exact PSO set: \(actualHdrContract)"
            )
            try require(
                actualHdrContract & ((1 << 2) | (1 << 3) | (1 << 6) | (1 << 7)) == 0,
                "METALLUM HDR generation retained Legacy reconstruction/semantic resources"
            )

            let actualSdr = generationPacket(
                valid,
                generation: 102,
                lightingMode: 1,
                outputMode: 0,
                spatial: false
            )
            try require(validate(nativeSet, actualSdr) == 1, "METALLUM SDR generation prewarm failed")
            try require(
                nativeContract(devicePointer) == 3,
                "METALLUM SDR generation retained actual-HDR UI-only/effects PSOs or created work beyond SDR present/UI seed"
            )

            let legacySdr = generationPacket(
                valid,
                generation: 103,
                lightingMode: 0,
                outputMode: 0,
                spatial: false
            )
            try require(validate(nativeSet, legacySdr) == 1, "Legacy SDR generation prewarm failed")
            try require(
                nativeContract(devicePointer) == 1,
                "Legacy SDR generation retained HDR/UI-seed PSOs or resources"
            )
            print("Native FrameState ABI v2 validation passed (8 negative cases + 4 exact native generations)")
        } catch {
            fputs("Native FrameState ABI validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
