import Darwin
import Foundation

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

private func validPacket() -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: 160)
    writeUInt32(1, at: 0, into: &bytes)
    writeUInt32(160, at: 4, into: &bytes)
    writeUInt32(1, at: 8, into: &bytes)
    writeUInt32(2, at: 12, into: &bytes)
    writeUInt64(42, at: 16, into: &bytes)
    writeUInt64(7, at: 24, into: &bytes)
    writeUInt64(8, at: 32, into: &bytes)
    writeUInt64(9, at: 40, into: &bytes)
    writeUInt64(10, at: 48, into: &bytes)
    writeUInt32(0, at: 56, into: &bytes) // legacy
    writeUInt32(1, at: 60, into: &bytes) // HDR
    writeUInt32(0, at: 64, into: &bytes) // Metal 3
    writeUInt32(1, at: 68, into: &bytes) // Balanced
    writeUInt64(1, at: 72, into: &bytes) // Spatial
    writeUInt32(1280, at: 80, into: &bytes)
    writeUInt32(720, at: 84, into: &bytes)
    writeUInt32(2560, at: 88, into: &bytes)
    writeUInt32(1440, at: 92, into: &bytes)
    writeUInt64(64, at: 104, into: &bytes) // HDR resources
    writeUInt64(128, at: 120, into: &bytes) // upscale resources
    return bytes
}

private func validate(_ function: NativeValidateFunction, _ bytes: [UInt8]) -> Int32 {
    bytes.withUnsafeBytes { function($0.baseAddress, UInt64($0.count)) }
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
            guard let symbol = dlsym(handle, "metallum_validate_frame_state_v1") else {
                throw ValidationFailure.message("Native FrameState ABI validator symbol is missing")
            }
            let nativeValidate = unsafeBitCast(symbol, to: NativeValidateFunction.self)
            let valid = validPacket()
            try require(validate(nativeValidate, valid) == 1, "Valid FrameState packet was rejected")

            var invalidVersion = valid
            writeUInt32(2, at: 0, into: &invalidVersion)
            try require(validate(nativeValidate, invalidVersion) == -2, "Version mismatch was accepted")
            var lightingLeak = valid
            writeUInt64(1, at: 112, into: &lightingLeak)
            try require(validate(nativeValidate, lightingLeak) == -5, "Legacy lighting bytes were accepted")
            var sdrHdrLeak = valid
            writeUInt32(0, at: 60, into: &sdrHdrLeak)
            try require(validate(nativeValidate, sdrHdrLeak) == -6, "SDR HDR bytes were accepted")
            var nativeUpscaleLeak = valid
            writeUInt64(0, at: 72, into: &nativeUpscaleLeak)
            try require(validate(nativeValidate, nativeUpscaleLeak) == -7,
                        "Native-resolution upscale bytes were accepted")
            var interpolationLeak = valid
            writeUInt64(1, at: 128, into: &interpolationLeak)
            try require(validate(nativeValidate, interpolationLeak) == -8,
                        "Disabled interpolation bytes were accepted")
            let truncated = valid.withUnsafeBytes { nativeValidate($0.baseAddress, 159) }
            try require(truncated == -1, "Truncated FrameState packet was accepted")
            print("Native FrameState ABI validation passed (6 negative cases)")
        } catch {
            fputs("Native FrameState ABI validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
