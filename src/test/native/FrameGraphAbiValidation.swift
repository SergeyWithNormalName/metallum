import Darwin
import Foundation

private enum ValidationFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case let .message(message):
            return message
        }
    }
}

private typealias NativeValidateFunction = @convention(c) (
    UnsafeRawPointer?,
    UInt64
) -> Int32

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else {
        throw ValidationFailure.message(message)
    }
}

private func writeUInt32(_ value: UInt32, at offset: Int, into bytes: inout [UInt8]) {
    let little = value.littleEndian
    withUnsafeBytes(of: little) { source in
        bytes.replaceSubrange(offset..<(offset + source.count), with: source)
    }
}

private func writeInt32(_ value: Int32, at offset: Int, into bytes: inout [UInt8]) {
    writeUInt32(UInt32(bitPattern: value), at: offset, into: &bytes)
}

private func writeUInt64(_ value: UInt64, at offset: Int, into bytes: inout [UInt8]) {
    let little = value.littleEndian
    withUnsafeBytes(of: little) { source in
        bytes.replaceSubrange(offset..<(offset + source.count), with: source)
    }
}

private func validPacket() -> [UInt8] {
    let headerBytes = 32
    let recordBytes = 24
    var bytes = [UInt8](repeating: 0, count: headerBytes + recordBytes * 3)
    writeUInt32(1, at: 0, into: &bytes) // version
    writeUInt32(UInt32(bytes.count), at: 4, into: &bytes)
    writeUInt64(1, at: 8, into: &bytes) // typed attachments capability
    writeUInt32(1, at: 16, into: &bytes) // resources
    writeUInt32(1, at: 20, into: &bytes) // passes
    writeUInt32(1, at: 24, into: &bytes) // accesses

    let resource = headerBytes
    writeUInt32(0, at: resource, into: &bytes)
    writeUInt32(2, at: resource + 4, into: &bytes) // texture
    writeUInt32(3, at: resource + 8, into: &bytes) // size generation
    writeUInt32(0, at: resource + 12, into: &bytes)
    writeInt32(0, at: resource + 16, into: &bytes)
    writeInt32(0, at: resource + 20, into: &bytes)

    let pass = resource + recordBytes
    writeUInt32(0, at: pass, into: &bytes)
    writeUInt32(1, at: pass + 4, into: &bytes) // render
    writeUInt32(0, at: pass + 8, into: &bytes)
    writeUInt32(1, at: pass + 12, into: &bytes)
    writeUInt64(0, at: pass + 16, into: &bytes)

    let access = pass + recordBytes
    writeUInt32(0, at: access, into: &bytes)
    writeUInt32(2, at: access + 4, into: &bytes) // write
    writeUInt32(2, at: access + 8, into: &bytes) // fragment
    writeUInt32(1, at: access + 12, into: &bytes) // color attachment
    writeUInt32(3, at: access + 16, into: &bytes) // dontCare
    writeUInt32(1, at: access + 20, into: &bytes) // store
    return bytes
}

private func validExternalMetalFxPacket() -> [UInt8] {
    var bytes = validPacket()
    let headerBytes = 32
    let recordBytes = 24
    let pass = headerBytes + recordBytes
    let access = pass + recordBytes
    writeUInt64(3, at: 8, into: &bytes) // typed attachments + external MetalFX
    writeUInt32(4, at: pass + 4, into: &bytes) // external MetalFX encoder
    writeUInt32(5, at: access + 8, into: &bytes) // MetalFX stage
    writeUInt32(0, at: access + 12, into: &bytes) // non-attachment access
    writeUInt32(0, at: access + 16, into: &bytes)
    writeUInt32(0, at: access + 20, into: &bytes)
    return bytes
}

private func validate(_ function: NativeValidateFunction, _ bytes: [UInt8]) -> Int32 {
    bytes.withUnsafeBytes { raw in
        function(raw.baseAddress, UInt64(raw.count))
    }
}

@main
private enum FrameGraphAbiValidationMain {
    static func main() {
        do {
            let arguments = CommandLine.arguments
            try require(arguments.count == 2, "Usage: FrameGraphAbiValidation <libmetallum.dylib>")
            guard let handle = dlopen(arguments[1], RTLD_NOW | RTLD_LOCAL) else {
                let detail = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
                throw ValidationFailure.message("Could not load native library: \(detail)")
            }
            defer { dlclose(handle) }
            guard let symbol = dlsym(handle, "metallum_validate_frame_graph_v1") else {
                throw ValidationFailure.message("Native frame graph ABI validator symbol is missing")
            }
            let nativeValidate = unsafeBitCast(symbol, to: NativeValidateFunction.self)

            let valid = validPacket()
            try require(validate(nativeValidate, valid) == 1, "Valid frame graph ABI packet was rejected")
            let validExternal = validExternalMetalFxPacket()
            try require(validate(nativeValidate, validExternal) == 1,
                        "Valid external MetalFX frame graph ABI packet was rejected")

            var invalidVersion = valid
            writeUInt32(2, at: 0, into: &invalidVersion)
            try require(validate(nativeValidate, invalidVersion) == -2, "Version mismatch was not rejected")

            var invalidSize = valid
            writeUInt32(UInt32(valid.count - 1), at: 4, into: &invalidSize)
            try require(validate(nativeValidate, invalidSize) == -3, "Byte-size mismatch was not rejected")

            var invalidCapability = valid
            writeUInt64(4, at: 8, into: &invalidCapability)
            try require(validate(nativeValidate, invalidCapability) == -4,
                        "Unsupported capability was not rejected")

            var missingTypedAttachmentCapability = valid
            writeUInt64(0, at: 8, into: &missingTypedAttachmentCapability)
            try require(validate(nativeValidate, missingTypedAttachmentCapability) == -8,
                        "Attachment packet without typed-attachment capability was not rejected")

            var missingExternalCapability = validExternal
            writeUInt64(1, at: 8, into: &missingExternalCapability)
            try require(validate(nativeValidate, missingExternalCapability) == -7,
                        "External encoder without MetalFX capability was not rejected")

            var invalidExternalStage = validExternal
            writeUInt32(2, at: 32 + 48 + 8, into: &invalidExternalStage)
            try require(validate(nativeValidate, invalidExternalStage) == -8,
                        "External MetalFX encoder accepted a fragment-stage access")

            var invalidPassCount = valid
            writeUInt32(65, at: 20, into: &invalidPassCount)
            try require(validate(nativeValidate, invalidPassCount) == -5,
                        "Out-of-range pass count was not rejected")

            var invalidResource = valid
            writeUInt32(1, at: 32, into: &invalidResource)
            try require(validate(nativeValidate, invalidResource) == -6,
                        "Non-dense resource ID was not rejected")

            var invalidPassRange = valid
            writeUInt32(1, at: 32 + 24 + 8, into: &invalidPassRange)
            try require(validate(nativeValidate, invalidPassRange) == -7,
                        "Invalid pass access range was not rejected")

            var invalidAccessReference = valid
            writeUInt32(1, at: 32 + 48, into: &invalidAccessReference)
            try require(validate(nativeValidate, invalidAccessReference) == -8,
                        "Invalid access resource reference was not rejected")

            var invalidAttachmentRead = valid
            writeUInt32(3, at: 32 + 48 + 4, into: &invalidAttachmentRead) // read-write
            writeUInt32(2, at: 32 + 48 + 16, into: &invalidAttachmentRead) // clear
            try require(validate(nativeValidate, invalidAttachmentRead) == -8,
                        "Clear attachment with read access was not rejected")

            let truncated = valid.withUnsafeBytes { raw in
                nativeValidate(raw.baseAddress, 16)
            }
            try require(truncated == -1, "Truncated header was not rejected")
            print("Native frame graph ABI validation passed (12 negative cases)")
        } catch {
            fputs("Native frame graph ABI validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
