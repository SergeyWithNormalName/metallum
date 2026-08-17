import Foundation
import Metal
import MetalFX

struct GpuCapabilityReport: Codable {
    let schema_version: Int
    let timestamp_iso8601: String
    let device_name: String
    let registry_id: UInt64
    let is_low_power: Bool
    let is_headless: Bool
    let recommended_max_working_set_bytes: UInt64
    let max_buffer_length_bytes: UInt64
    
    let supported_gpu_families: [String: String]
    let counter_sets: [CounterSetReport]
    let counter_sampling_boundaries: [String: String]
    let feature_classifications: [String: String]
}

struct CounterSetReport: Codable {
    let name: String
    let counter_names: [String]
}

func main() {
    guard let device = MTLCreateSystemDefaultDevice() else {
        print("ERROR: No default Metal device found.")
        exit(1)
    }

    let dateFormatter = ISO8601DateFormatter()
    let timestamp = dateFormatter.string(from: Date())

    var families: [String: String] = [:]
    families["apple7"] = device.supportsFamily(.apple7) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    families["apple8"] = device.supportsFamily(.apple8) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    families["apple9"] = device.supportsFamily(.apple9) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    families["metal3"] = device.supportsFamily(.metal3) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    families["metal4"] = "DOCUMENTATION_CONFIRMED" // Apple Dev Specs confirm Metal 4 supports M1 and later on macOS 26+ SDK

    var counterSetReports: [CounterSetReport] = []
    if let sets = device.counterSets {
        for set in sets {
            let names = set.counters.map { $0.name }
            counterSetReports.append(CounterSetReport(name: set.name, counter_names: names))
        }
    }

    var boundaries: [String: String] = [:]
    boundaries["stage_boundary"] = device.supportsCounterSampling(.atStageBoundary) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    boundaries["draw_boundary"] = device.supportsCounterSampling(.atDrawBoundary) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    boundaries["blit_boundary"] = device.supportsCounterSampling(.atBlitBoundary) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    boundaries["dispatch_boundary"] = device.supportsCounterSampling(.atDispatchBoundary) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"

    var features: [String: String] = [:]
    features["metal3_architecture"] = device.supportsFamily(.apple7) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    features["metal4_api_availability"] = "DOCUMENTATION_CONFIRMED" // Apple Dev Specs: Metal 4 supports M1 and later
    features["timestamp_stage_sampling"] = device.supportsCounterSampling(.atStageBoundary) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    features["hardware_ray_tracing"] = device.supportsFamily(.apple9) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    features["dynamic_caching"] = device.supportsFamily(.apple9) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    features["mesh_shaders_family9"] = device.supportsFamily(.apple9) ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"
    features["xcode_shader_cost_graph"] = "UNAVAILABLE" // Xcode IDE tooling restriction requiring M3+ hardware in Xcode Instruments

    let hasStatistic = counterSetReports.contains(where: { $0.name == MTLCommonCounterSet.statistic.rawValue })
    features["statistic_counters"] = hasStatistic ? "RUNTIME_CONFIRMED" : "UNAVAILABLE"

    let report = GpuCapabilityReport(
        schema_version: 1,
        timestamp_iso8601: timestamp,
        device_name: device.name,
        registry_id: device.registryID,
        is_low_power: device.isLowPower,
        is_headless: device.isHeadless,
        recommended_max_working_set_bytes: device.recommendedMaxWorkingSetSize,
        max_buffer_length_bytes: UInt64(device.maxBufferLength),
        supported_gpu_families: families,
        counter_sets: counterSetReports,
        counter_sampling_boundaries: boundaries,
        feature_classifications: features
    )

    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    
    do {
        let jsonData = try encoder.encode(report)
        let fileManager = FileManager.default
        let currentDir = URL(fileURLWithPath: "benchmark/current")
        try fileManager.createDirectory(at: currentDir, withIntermediateDirectories: true, attributes: nil)
        let outputFile = currentDir.appendingPathComponent("M1_PRO_GPU_CAPABILITIES.json")
        try jsonData.write(to: outputFile)
        print("Successfully generated capability probe output: \(outputFile.path)")
    } catch {
        print("ERROR: Failed to write GPU capabilities JSON: \(error)")
        exit(1)
    }
}

main()
