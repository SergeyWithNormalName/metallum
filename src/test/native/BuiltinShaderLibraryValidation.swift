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

private typealias NativeInitFunction = @convention(c) (UnsafeRawPointer?) -> Int32

private func objectPointer(_ object: AnyObject) -> UnsafeRawPointer {
    UnsafeRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

@main
private enum BuiltinShaderLibraryValidationMain {
    static func main() {
        do {
            let arguments = CommandLine.arguments
            guard arguments.count == 3, let expectedStatus = Int32(arguments[2]) else {
                throw ValidationFailure.message(
                    "Usage: BuiltinShaderLibraryValidation <libmetallum.dylib> <expected-status>"
                )
            }
            guard let handle = dlopen(arguments[1], RTLD_NOW | RTLD_LOCAL) else {
                let detail = dlerror().map { String(cString: $0) } ?? "unknown dlopen error"
                throw ValidationFailure.message("Could not load native library: \(detail)")
            }
            defer { dlclose(handle) }
            guard let symbol = dlsym(handle, "metallum_init_pipelines") else {
                throw ValidationFailure.message("Native pipeline initializer symbol is missing")
            }
            guard let device = MTLCreateSystemDefaultDevice() else {
                throw ValidationFailure.message("No Metal device is available")
            }

            let initialize = unsafeBitCast(symbol, to: NativeInitFunction.self)
            let status = initialize(objectPointer(device as AnyObject))
            guard status == expectedStatus else {
                throw ValidationFailure.message(
                    "Native shader initialization returned \(status), expected \(expectedStatus)"
                )
            }
            let mode = status == 1 ? "PRECOMPILED" : "SOURCE_FALLBACK"
            print("Built-in Metal shader library validation passed (\(mode))")
        } catch {
            fputs("Built-in Metal shader library validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
