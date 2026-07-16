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
private typealias NativeCreateLightingContextFunction = @convention(c) (
    UnsafeRawPointer?, UInt64, UInt32, UInt32, UInt32, UInt32, UInt32
) -> UnsafeMutableRawPointer?
private typealias NativeReleaseLightingContextFunction = @convention(c) (
    UnsafeMutableRawPointer?
) -> Void

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
            guard let initSymbol = dlsym(handle, "metallum_init_pipelines"),
                  let createLightingSymbol = dlsym(handle, "metallum_lighting_create_context_v1"),
                  let releaseLightingSymbol = dlsym(handle, "metallum_lighting_release_context_v1") else {
                throw ValidationFailure.message("Native pipeline validation symbols are missing")
            }
            guard let device = MTLCreateSystemDefaultDevice() else {
                throw ValidationFailure.message("No Metal device is available")
            }

            let initialize = unsafeBitCast(initSymbol, to: NativeInitFunction.self)
            let createLightingContext = unsafeBitCast(
                createLightingSymbol,
                to: NativeCreateLightingContextFunction.self
            )
            let releaseLightingContext = unsafeBitCast(
                releaseLightingSymbol,
                to: NativeReleaseLightingContextFunction.self
            )
            let status = initialize(objectPointer(device as AnyObject))
            guard status == expectedStatus else {
                throw ValidationFailure.message(
                    "Native shader initialization returned \(status), expected \(expectedStatus)"
                )
            }

            // Clustered lighting is lazy and optional: its failure disables only the
            // Advanced generation and must not poison the already-valid base Metal device.
            do {
                guard setenv("METALLUM_NATIVE_CLUSTER_PIPELINE_FORCE_FAILURE", "1", 1) == 0 else {
                    throw ValidationFailure.message("Could not enable cluster-pipeline failure injection")
                }
                defer { unsetenv("METALLUM_NATIVE_CLUSTER_PIPELINE_FORCE_FAILURE") }
                let rejected = createLightingContext(
                    objectPointer(device as AnyObject), 1, 1, 64, 1, 1, 6
                )
                if let rejected {
                    releaseLightingContext(rejected)
                    throw ValidationFailure.message(
                        "Forced optional cluster-pipeline failure created a lighting context"
                    )
                }
                guard initialize(objectPointer(device as AnyObject)) == expectedStatus else {
                    throw ValidationFailure.message(
                        "Optional cluster-pipeline failure poisoned base Metal initialization"
                    )
                }
            }

            guard let lightingContext = createLightingContext(
                objectPointer(device as AnyObject), 2, 1, 64, 1, 1, 6
            ) else {
                throw ValidationFailure.message("Lazy clustered-lighting recovery failed")
            }
            releaseLightingContext(lightingContext)
            guard initialize(objectPointer(device as AnyObject)) == expectedStatus else {
                throw ValidationFailure.message(
                    "Successful lazy clustered-lighting initialization changed base status"
                )
            }
            let mode = status == 1 ? "PRECOMPILED" : "SOURCE_FALLBACK"
            print("Built-in Metal shader library validation passed (\(mode), optional lighting isolated)")
        } catch {
            fputs("Built-in Metal shader library validation FAILED: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }
}
