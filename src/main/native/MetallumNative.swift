import Foundation
import AppKit
import Darwin
import Metal
import MetalFX
import QuartzCore
import simd

private struct DepthStencilKey: Hashable {
    let deviceAddress: UInt
    let compareOp: MTLCompareFunction
    let writeDepth: Bool
}

private struct PipelineVariantKey: Hashable {
    let deviceAddress: UInt
    let colorFormat: MTLPixelFormat
    let depthFormat: MTLPixelFormat
    let writeColor: Bool
}

private struct PresentPipelineKey: Hashable {
    let deviceAddress: UInt
    let colorFormat: MTLPixelFormat
}

private enum MetallumBuiltinShaderSet: String, CaseIterable {
    case present
    case hdrEffects
    case clear
    case sodiumLightPatch
    case temporalDiagnostics
    case clusterBuild
    // L5 is optional and deliberately does not participate in base renderer warm-up.
    // A missing voxel metallib/source must fail context creation, never Vanilla/L3/L4.
    case voxelOccupancy
    case dynamicVoxelShadow

    static var startupMandatory: [Self] {
        allCases.filter { $0 != .clusterBuild && $0 != .voxelOccupancy && $0 != .dynamicVoxelShadow }
    }

    var sourceFileName: String {
        switch self {
        case .present: "MetallumPresent.metal"
        case .hdrEffects: "MetallumHdrEffects.metal"
        case .clear: "MetallumClear.metal"
        case .sodiumLightPatch: "MetallumSodiumLightPatch.metal"
        case .temporalDiagnostics: "MetallumTemporalDiagnostics.metal"
        case .clusterBuild: "MetallumClusterBuild.metal"
        case .voxelOccupancy: "MetallumVoxelOccupancy.metal"
        case .dynamicVoxelShadow: "MetallumDynamicVoxelShadow.metal"
        }
    }

    var requiredFunctionNames: [String] {
        switch self {
        case .present:
            [
                "metallum_present_vs",
                "metallum_offscreen_vs",
                "metallum_sdr_present_fs",
                "metallum_present_fs",
                "metallum_actual_hdr_present_fs",
                "metallum_actual_hdr_ui_only_fs",
                "metallum_actual_hdr_linear_ui_only_fs",
                "metallum_spatial_world_fs",
                "metallum_actual_spatial_world_fs",
                "metallum_native_world_ui_fs",
                "metallum_actual_native_world_ui_fs",
                "metallum_spatial_present_fs",
                "metallum_spatial_screenshot_fs",
                "metallum_menu_blur_compose_fs",
                "metallum_menu_blur_fs",
                "metallum_menu_blur_resolve_fs"
            ]
        case .hdrEffects:
            [
                "metallum_hdr_vs",
                "metallum_hdr_extract_fs",
                "metallum_actual_hdr_extract_fs",
                "metallum_hdr_histogram_build",
                "metallum_hdr_histogram_reduce",
                "metallum_actual_hdr_exposure_reduce",
                "metallum_hdr_blur",
                "metallum_hdr_ui_backdrop_fs",
                "metallum_hdr_ui_compare_fs",
                "metallum_hdr_ui_dilate_fs"
            ]
        case .clear:
            ["metallum_clear_vs", "metallum_clear_fs"]
        case .sodiumLightPatch:
            ["metallum_sodium_light_legacy_patch"]
        case .temporalDiagnostics:
            [
                "metallum_temporal_diagnostic_vs",
                "metallum_temporal_diagnostic_fs",
                "metallum_motion_vector_validate"
            ]
        case .clusterBuild:
            [
                "metallum_cluster_prepare_v1",
                "metallum_cluster_count_v1",
                "metallum_cluster_masks_v1",
                "metallum_cluster_prefix_blocks_v1",
                "metallum_cluster_prefix_groups_v1",
                "metallum_cluster_prefix_add_v1",
                "metallum_cluster_fill_v1"
            ]
        case .voxelOccupancy:
            [
                "metallum_voxel_apply_v1",
                "metallum_voxel_checksum_v1"
            ]
        case .dynamicVoxelShadow:
            ["metallum_dynamic_voxel_shadow_v1"]
        }
    }
}

private enum MetallumBuiltinShaderLibraryMode: String {
    case uninitialized = "UNINITIALIZED"
    case precompiled = "PRECOMPILED"
    case sourceFallback = "SOURCE_FALLBACK"
    case failed = "FAILED"
}

private struct MetallumBuiltinShaderSnapshot {
    let mode: MetallumBuiltinShaderLibraryMode
    let sourceCompileCount: Int
    let libraryLoadMilliseconds: Double
    let sourceCompileMilliseconds: Double
    let pipelineWarmupMilliseconds: Double
    let pipelineCount: Int
    let pipelineFailureCount: Int
    let pipelineCacheHitCount: Int
    let pipelineCacheMissCount: Int
    let pipelineCreationsAfterWarmup: Int
    let warmupComplete: Bool
}

private final class MetallumBuiltinShaderState {
    let initializationLock = NSLock()
    private let lock = NSLock()
    var precompiledLoadAttempted = false
    var precompiledLibrary: MTLLibrary?
    var fallbackLibraries: [MetallumBuiltinShaderSet: MTLLibrary] = [:]
    var mode = MetallumBuiltinShaderLibraryMode.uninitialized
    var sourceCompileCount = 0
    var libraryLoadMilliseconds = 0.0
    var sourceCompileMilliseconds = 0.0
    var pipelineWarmupMilliseconds = 0.0
    var pipelineCount = 0
    var pipelineFailureCount = 0
    var pipelineCacheHitCount = 0
    var pipelineCacheMissCount = 0
    var pipelineCreationsAfterWarmup = 0
    var warmupComplete = false
    var generationWarmupInProgress = false

    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    func snapshot() -> MetallumBuiltinShaderSnapshot {
        withLock {
            MetallumBuiltinShaderSnapshot(
                mode: mode,
                sourceCompileCount: sourceCompileCount,
                libraryLoadMilliseconds: libraryLoadMilliseconds,
                sourceCompileMilliseconds: sourceCompileMilliseconds,
                pipelineWarmupMilliseconds: pipelineWarmupMilliseconds,
                pipelineCount: pipelineCount,
                pipelineFailureCount: pipelineFailureCount,
                pipelineCacheHitCount: pipelineCacheHitCount,
                pipelineCacheMissCount: pipelineCacheMissCount,
                pipelineCreationsAfterWarmup: pipelineCreationsAfterWarmup,
                warmupComplete: warmupComplete
            )
        }
    }
}

private struct MetallumPresentUniforms {
    var mode: UInt32
    var sourceEncoding: UInt32
    var diagnosticPattern: UInt32
    var currentHeadroom: Float
    var hdrStrength: Float
    var bloomStrength: Float
    var sceneAvailable: UInt32
    var uiAvailable: UInt32
    var semanticAvailable: UInt32
}

private struct MetallumHdrExtractUniforms {
    var sourceEncoding: UInt32
    var semanticAvailable: UInt32
    var sourceSize: SIMD2<UInt32>
    var histogramEnabled: UInt32
    var _padding0: UInt32
}

private struct MetallumHdrUiBackdropUniforms {
    var sourceEncoding: UInt32
}

private struct MetallumMenuBlurUniforms {
    var blurDirection: SIMD2<Float>
    var radius: Float
    var currentHeadroom: Float
}

private struct MetallumHdrUiBackdropPipelineKey: Hashable {
    let depthFormat: UInt
    let stencilFormat: UInt
}

private struct MetallumHdrUiCompareUniforms {
    var sourceEncoding: UInt32
    var seededUiAvailable: UInt32
    var scaleScene: UInt32
    var _padding0: UInt32
}

private struct MetallumHdrHistogramReduceUniforms {
    var currentHeadroom: Float
    var deltaTime: Float
    var forceReset: UInt32
    var _padding0: UInt32
}

private struct MetallumHdrAdaptiveState {
    var breakpoint: Float
    var inferredPeak: Float
    var medianLog2: Float
    var p90Log2: Float
    var p99Log2: Float
    var brightCoverage: Float
    var currentHeadroom: Float
    var valid: UInt32
}

private final class MetallumHdrPipelines {
    let extract: MTLRenderPipelineState
    let histogramReduce: MTLComputePipelineState
    let blur: MTLComputePipelineState
    let uiCompare: MTLRenderPipelineState
    let uiDilate: MTLRenderPipelineState

    init(
        extract: MTLRenderPipelineState,
        histogramReduce: MTLComputePipelineState,
        blur: MTLComputePipelineState,
        uiCompare: MTLRenderPipelineState,
        uiDilate: MTLRenderPipelineState
    ) {
        self.extract = extract
        self.histogramReduce = histogramReduce
        self.blur = blur
        self.uiCompare = uiCompare
        self.uiDilate = uiDilate
    }
}

private final class MetallumActualHdrPipelines {
    let extract: MTLRenderPipelineState
    let exposureReduce: MTLComputePipelineState
    let blur: MTLComputePipelineState
    let uiCompare: MTLRenderPipelineState
    let uiDilate: MTLRenderPipelineState

    init(
        extract: MTLRenderPipelineState,
        exposureReduce: MTLComputePipelineState,
        blur: MTLComputePipelineState,
        uiCompare: MTLRenderPipelineState,
        uiDilate: MTLRenderPipelineState
    ) {
        self.extract = extract
        self.exposureReduce = exposureReduce
        self.blur = blur
        self.uiCompare = uiCompare
        self.uiDilate = uiDilate
    }
}

private final class MetallumUiBackdropPipelines {
    let standard: MTLRenderPipelineState
    let vertexFunction: MTLFunction
    let fragmentFunction: MTLFunction
    var attachmentVariants: [MetallumHdrUiBackdropPipelineKey: MTLRenderPipelineState] = [:]

    init(
        standard: MTLRenderPipelineState,
        vertexFunction: MTLFunction,
        fragmentFunction: MTLFunction
    ) {
        self.standard = standard
        self.vertexFunction = vertexFunction
        self.fragmentFunction = fragmentFunction
    }
}

private final class MetallumMenuBlurPipelines {
    let compose: MTLRenderPipelineState
    let blur: MTLRenderPipelineState
    let resolve: MTLRenderPipelineState

    init(
        compose: MTLRenderPipelineState,
        blur: MTLRenderPipelineState,
        resolve: MTLRenderPipelineState
    ) {
        self.compose = compose
        self.blur = blur
        self.resolve = resolve
    }
}

private final class MetallumHdrWorkspace {
    let renderContractMode: UInt32
    let sourceWidth: Int
    let sourceHeight: Int
    var displayWidth: Int
    var displayHeight: Int
    let emission: MTLTexture
    let bloom: MTLTexture
    var worldComposite: MTLTexture?
    var worldCompositeCommandBufferAddress: UInt?
    var uiMaskA: MTLTexture?
    var uiMaskB: MTLTexture?
    var menuBlurA: MTLTexture?
    var menuBlurB: MTLTexture?
    let histogram: MTLBuffer
    let adaptiveState: MTLBuffer
    var lastHistogramUptime: TimeInterval?
    var histogramNeedsInitialization: Bool

    init(
        renderContractMode: UInt32,
        sourceWidth: Int,
        sourceHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        emission: MTLTexture,
        bloom: MTLTexture,
        histogram: MTLBuffer,
        adaptiveState: MTLBuffer
    ) {
        self.renderContractMode = renderContractMode
        self.sourceWidth = sourceWidth
        self.sourceHeight = sourceHeight
        self.displayWidth = displayWidth
        self.displayHeight = displayHeight
        self.emission = emission
        self.bloom = bloom
        self.worldComposite = nil
        self.worldCompositeCommandBufferAddress = nil
        self.uiMaskA = nil
        self.uiMaskB = nil
        self.menuBlurA = nil
        self.menuBlurB = nil
        self.histogram = histogram
        self.adaptiveState = adaptiveState
        self.lastHistogramUptime = nil
        self.histogramNeedsInitialization = true
    }
}

private final class MetallumSpatialWorkspace {
    let sourcePixelFormat: MTLPixelFormat
    let inputWidth: Int
    let inputHeight: Int
    let outputWidth: Int
    let outputHeight: Int
    let inputPixelFormat: MTLPixelFormat
    let outputPixelFormat: MTLPixelFormat
    let colorProcessingMode: MTLFXSpatialScalerColorProcessingMode
    let convertsLinearToPerceptual: Bool
    let scaler: MTLFXSpatialScaler
    let perceptualInput: MTLTexture?
    var output: MTLTexture?
    var outputCommandBufferAddress: UInt?
    let usesDirectOutput: Bool
    var preparedUiSeed: MetallumPreparedSpatialUiSeed?

    init(
        sourcePixelFormat: MTLPixelFormat,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        inputPixelFormat: MTLPixelFormat,
        outputPixelFormat: MTLPixelFormat,
        colorProcessingMode: MTLFXSpatialScalerColorProcessingMode,
        convertsLinearToPerceptual: Bool,
        scaler: MTLFXSpatialScaler,
        perceptualInput: MTLTexture?,
        output: MTLTexture?,
        usesDirectOutput: Bool
    ) {
        self.sourcePixelFormat = sourcePixelFormat
        self.inputWidth = inputWidth
        self.inputHeight = inputHeight
        self.outputWidth = outputWidth
        self.outputHeight = outputHeight
        self.inputPixelFormat = inputPixelFormat
        self.outputPixelFormat = outputPixelFormat
        self.colorProcessingMode = colorProcessingMode
        self.convertsLinearToPerceptual = convertsLinearToPerceptual
        self.scaler = scaler
        self.perceptualInput = perceptualInput
        self.output = output
        self.outputCommandBufferAddress = nil
        self.usesDirectOutput = usesDirectOutput
        self.preparedUiSeed = nil
    }
}

private struct MetallumPreparedSpatialUiSeed {
    let commandBufferAddress: UInt
    let sourceTextureAddress: UInt
    let destinationTextureAddress: UInt
    let sourceWidth: Int
    let sourceHeight: Int
    let outputWidth: Int
    let outputHeight: Int
    let output: MTLTexture
}

private final class MetallumStaticGeometryHeapPage {
    let heap: MTLHeap
    var liveAllocations = 0
    var releasesInProgress = 0
    var liveRequestedBytes = 0
    var liveQueryBytes = 0
    var retirePending = false

    init(heap: MTLHeap) {
        self.heap = heap
    }
}

private final class MetallumStaticGeometryHeapPool {
    let device: MTLDevice
    var pages: [MetallumStaticGeometryHeapPage] = []
    var maxQueryAlignment = 256

    var liveAllocations = 0
    var liveRequestedBytes = 0
    var liveQueryBytes = 0
    var requestsTotal = 0
    var requestedBytesTotal = 0
    var heapAllocationsTotal = 0
    var heapQueryBytesTotal = 0
    var pageReuseHitsTotal = 0
    var fallbackAllocationsTotal = 0
    var fallbackRequestedBytesTotal = 0
    var fallbackDisabledTotal = 0
    var fallbackOversizeTotal = 0
    var fallbackInvalidQueryTotal = 0
    var fallbackCapacityTotal = 0
    var fallbackHeapCreateTotal = 0
    var fallbackHeapAllocateTotal = 0
    var allocationFailuresTotal = 0
    var pagesCreatedTotal = 0
    var pagesRetiredTotal = 0
    var pagesPeak = 0

    init(device: MTLDevice) {
        self.device = device
    }
}

private struct MetallumStaticGeometryAllocationRecord {
    let pool: MetallumStaticGeometryHeapPool
    let page: MetallumStaticGeometryHeapPage?
    let requestedBytes: Int
    let queryBytes: Int
}

private struct MetallumStaticGeometryReleaseToken {
    let record: MetallumStaticGeometryAllocationRecord
}

private struct MetallumStaticGeometryHeapSnapshot {
    let enabled: Bool
    let poolsCurrent: Int
    let pageSizeBytes: Int
    let pageLimitPerDevice: Int
    let pagesCurrent: Int
    let pagesPeak: Int
    let pagesCreatedTotal: Int
    let pagesRetiredTotal: Int
    let retirePendingPages: Int
    let heapSizeBytesCurrent: Int
    let heapCurrentAllocatedBytes: Int
    let heapUsedBytesCurrent: Int
    let fragmentationProbeAlignment: Int
    let heapLargestAvailableBytes: Int
    let heapFragmentationEstimateBytes: Int
    let liveAllocations: Int
    let liveRequestedBytes: Int
    let liveQueryBytes: Int
    let requestsTotal: Int
    let requestedBytesTotal: Int
    let heapAllocationsTotal: Int
    let heapQueryBytesTotal: Int
    let pageReuseHitsTotal: Int
    let fallbackAllocationsTotal: Int
    let fallbackRequestedBytesTotal: Int
    let fallbackDisabledTotal: Int
    let fallbackOversizeTotal: Int
    let fallbackInvalidQueryTotal: Int
    let fallbackCapacityTotal: Int
    let fallbackHeapCreateTotal: Int
    let fallbackHeapAllocateTotal: Int
    let allocationFailuresTotal: Int
    let deviceTeardownWithLiveAllocationsTotal: Int

    var report: [String: Any] {
        [
            "enabled": enabled,
            "pools_current": poolsCurrent,
            "page_size_bytes": pageSizeBytes,
            "page_limit_per_device": pageLimitPerDevice,
            "pages_current": pagesCurrent,
            "pages_peak": pagesPeak,
            "pages_created_total": pagesCreatedTotal,
            "pages_retired_total": pagesRetiredTotal,
            "retire_pending_pages": retirePendingPages,
            "heap_size_bytes_current": heapSizeBytesCurrent,
            "heap_current_allocated_bytes": heapCurrentAllocatedBytes,
            "heap_used_bytes_current": heapUsedBytesCurrent,
            "fragmentation_probe_alignment": fragmentationProbeAlignment,
            "heap_largest_available_bytes": heapLargestAvailableBytes,
            "heap_fragmentation_estimate_bytes": heapFragmentationEstimateBytes,
            "live_allocations": liveAllocations,
            "live_requested_bytes": liveRequestedBytes,
            "live_query_bytes": liveQueryBytes,
            "requests_total": requestsTotal,
            "requested_bytes_total": requestedBytesTotal,
            "heap_allocations_total": heapAllocationsTotal,
            "heap_query_bytes_total": heapQueryBytesTotal,
            "page_reuse_hits_total": pageReuseHitsTotal,
            "fallback_allocations_total": fallbackAllocationsTotal,
            "fallback_requested_bytes_total": fallbackRequestedBytesTotal,
            "fallback_disabled_total": fallbackDisabledTotal,
            "fallback_oversize_total": fallbackOversizeTotal,
            "fallback_invalid_query_total": fallbackInvalidQueryTotal,
            "fallback_capacity_total": fallbackCapacityTotal,
            "fallback_heap_create_total": fallbackHeapCreateTotal,
            "fallback_heap_allocate_total": fallbackHeapAllocateTotal,
            "allocation_failures_total": allocationFailuresTotal,
            "backing_allocations_total": pagesCreatedTotal + fallbackAllocationsTotal,
            "device_teardown_with_live_allocations_total": deviceTeardownWithLiveAllocationsTotal
        ]
    }
}

private final class MetallumStaticGeometryHeapRegistry: @unchecked Sendable {
    static let shared = MetallumStaticGeometryHeapRegistry()
    // Sodium keeps up to eight replaced arena buffers in a process-wide
    // reuse cache. Smaller pages cap the memory a lone cached buffer can pin,
    // while the page-count limit preserves the same 512 MiB hard budget.
    static let pageSize = 16 * 1024 * 1024
    static let pageLimitPerDevice = 32
    static let sodiumArenaCacheLimit = 8
    private static let resourceOptions: MTLResourceOptions = [
        .storageModePrivate,
        .hazardTrackingModeUntracked
    ]

    private enum FallbackReason {
        case disabled
        case oversize
        case invalidQuery
        case capacity
        case heapCreate
        case heapAllocate
    }

    private let lock = NSLock()
    private var poolsByDevice: [UInt: MetallumStaticGeometryHeapPool] = [:]
    private var recordsByBuffer: [UInt: MetallumStaticGeometryAllocationRecord] = [:]
    private var deviceTeardownWithLiveAllocationsTotal = 0

    private init() {
    }

    func makeBuffer(device: MTLDevice, length: Int) -> MTLBuffer? {
        guard length > 0 else { return nil }
        let deviceAddress = objectAddress(device)

        lock.lock()
        let pool: MetallumStaticGeometryHeapPool
        if let existing = poolsByDevice[deviceAddress] {
            pool = existing
        } else {
            pool = MetallumStaticGeometryHeapPool(device: device)
            poolsByDevice[deviceAddress] = pool
        }
        pool.requestsTotal += 1
        pool.requestedBytesTotal += length

        guard NativeState.staticGeometryHeapsEnabled else {
            let buffer = makeStandaloneLocked(
                pool: pool,
                length: length,
                queryBytes: 0,
                reason: .disabled
            )
            lock.unlock()
            return buffer
        }

        let sizeAndAlign = device.heapBufferSizeAndAlign(
            length: length,
            options: Self.resourceOptions
        )
        let queryIsValid = sizeAndAlign.size > 0
            && sizeAndAlign.align > 0
            && sizeAndAlign.align.nonzeroBitCount == 1
        guard queryIsValid else {
            let buffer = makeStandaloneLocked(
                pool: pool,
                length: length,
                queryBytes: 0,
                reason: .invalidQuery
            )
            lock.unlock()
            return buffer
        }
        pool.maxQueryAlignment = max(pool.maxQueryAlignment, sizeAndAlign.align)
        guard sizeAndAlign.size <= Self.pageSize else {
            let buffer = makeStandaloneLocked(
                pool: pool,
                length: length,
                queryBytes: sizeAndAlign.size,
                reason: .oversize
            )
            lock.unlock()
            return buffer
        }

        sweepRetiredPagesLocked(pool)
        for page in pool.pages.reversed() {
            guard page.heap.maxAvailableSize(alignment: sizeAndAlign.align) >= sizeAndAlign.size,
                  let buffer = page.heap.makeBuffer(
                    length: length,
                    options: Self.resourceOptions
                  ) else {
                continue
            }
            pool.pageReuseHitsTotal += 1
            registerLocked(
                buffer: buffer,
                pool: pool,
                page: page,
                requestedBytes: length,
                queryBytes: sizeAndAlign.size
            )
            lock.unlock()
            return buffer
        }

        guard pool.pages.count < Self.pageLimitPerDevice else {
            let buffer = makeStandaloneLocked(
                pool: pool,
                length: length,
                queryBytes: sizeAndAlign.size,
                reason: .capacity
            )
            lock.unlock()
            return buffer
        }

        let descriptor = MTLHeapDescriptor()
        descriptor.size = Self.pageSize
        descriptor.storageMode = .private
        descriptor.cpuCacheMode = .defaultCache
        descriptor.hazardTrackingMode = .untracked
        descriptor.type = .automatic
        guard let heap = device.makeHeap(descriptor: descriptor) else {
            let buffer = makeStandaloneLocked(
                pool: pool,
                length: length,
                queryBytes: sizeAndAlign.size,
                reason: .heapCreate
            )
            lock.unlock()
            return buffer
        }
        heap.label = "Metallum static geometry heap page \(pool.pagesCreatedTotal + 1)"
        guard let buffer = heap.makeBuffer(
            length: length,
            options: Self.resourceOptions
        ) else {
            let fallback = makeStandaloneLocked(
                pool: pool,
                length: length,
                queryBytes: sizeAndAlign.size,
                reason: .heapAllocate
            )
            lock.unlock()
            return fallback
        }

        let page = MetallumStaticGeometryHeapPage(heap: heap)
        pool.pages.append(page)
        pool.pagesCreatedTotal += 1
        pool.pagesPeak = max(pool.pagesPeak, pool.pages.count)
        registerLocked(
            buffer: buffer,
            pool: pool,
            page: page,
            requestedBytes: length,
            queryBytes: sizeAndAlign.size
        )
        lock.unlock()
        return buffer
    }

    func beginRelease(bufferAddress: UInt) -> MetallumStaticGeometryReleaseToken? {
        lock.lock()
        guard let record = recordsByBuffer.removeValue(forKey: bufferAddress) else {
            lock.unlock()
            return nil
        }
        record.page?.releasesInProgress += 1
        lock.unlock()
        return MetallumStaticGeometryReleaseToken(record: record)
    }

    func finishRelease(_ token: MetallumStaticGeometryReleaseToken) {
        lock.lock()
        let record = token.record
        let pool = record.pool
        pool.liveAllocations -= 1
        pool.liveRequestedBytes -= record.requestedBytes
        pool.liveQueryBytes -= record.queryBytes
        if let page = record.page {
            page.releasesInProgress -= 1
            page.liveAllocations -= 1
            page.liveRequestedBytes -= record.requestedBytes
            page.liveQueryBytes -= record.queryBytes
            if page.liveAllocations == 0 && page.releasesInProgress == 0 {
                page.retirePending = true
            }
        }
        sweepRetiredPagesLocked(pool)
        lock.unlock()
    }

    func releaseDevice(_ device: MTLDevice) {
        let deviceAddress = objectAddress(device)
        var liveAllocationCount = 0
        lock.lock()
        if let pool = poolsByDevice.removeValue(forKey: deviceAddress) {
            sweepRetiredPagesLocked(pool)
            liveAllocationCount = pool.liveAllocations
            if liveAllocationCount > Self.sodiumArenaCacheLimit {
                deviceTeardownWithLiveAllocationsTotal += 1
            }
        }
        lock.unlock()
        if liveAllocationCount > Self.sodiumArenaCacheLimit {
            NSLog(
                "[metallum] Static geometry heap teardown exceeded Sodium cache bound: %d live allocations",
                liveAllocationCount
            )
        } else if liveAllocationCount > 0 {
            NSLog(
                "[metallum] Static geometry arena cache retained %d buffers at device teardown",
                liveAllocationCount
            )
        }
    }

    func snapshot() -> MetallumStaticGeometryHeapSnapshot {
        lock.lock()
        var pagesCurrent = 0
        var pagesPeak = 0
        var pagesCreatedTotal = 0
        var pagesRetiredTotal = 0
        var retirePendingPages = 0
        var heapSizeBytesCurrent = 0
        var heapCurrentAllocatedBytes = 0
        var heapUsedBytesCurrent = 0
        var fragmentationProbeAlignment = 256
        var heapLargestAvailableBytes = 0
        var heapFragmentationEstimateBytes = 0
        var liveAllocations = 0
        var liveRequestedBytes = 0
        var liveQueryBytes = 0
        var requestsTotal = 0
        var requestedBytesTotal = 0
        var heapAllocationsTotal = 0
        var heapQueryBytesTotal = 0
        var pageReuseHitsTotal = 0
        var fallbackAllocationsTotal = 0
        var fallbackRequestedBytesTotal = 0
        var fallbackDisabledTotal = 0
        var fallbackOversizeTotal = 0
        var fallbackInvalidQueryTotal = 0
        var fallbackCapacityTotal = 0
        var fallbackHeapCreateTotal = 0
        var fallbackHeapAllocateTotal = 0
        var allocationFailuresTotal = 0
        let teardownWithLiveAllocationsTotal = deviceTeardownWithLiveAllocationsTotal

        for pool in poolsByDevice.values {
            pagesCurrent += pool.pages.count
            pagesPeak += pool.pagesPeak
            pagesCreatedTotal += pool.pagesCreatedTotal
            pagesRetiredTotal += pool.pagesRetiredTotal
            fragmentationProbeAlignment = max(
                fragmentationProbeAlignment,
                pool.maxQueryAlignment
            )
            liveAllocations += pool.liveAllocations
            liveRequestedBytes += pool.liveRequestedBytes
            liveQueryBytes += pool.liveQueryBytes
            requestsTotal += pool.requestsTotal
            requestedBytesTotal += pool.requestedBytesTotal
            heapAllocationsTotal += pool.heapAllocationsTotal
            heapQueryBytesTotal += pool.heapQueryBytesTotal
            pageReuseHitsTotal += pool.pageReuseHitsTotal
            fallbackAllocationsTotal += pool.fallbackAllocationsTotal
            fallbackRequestedBytesTotal += pool.fallbackRequestedBytesTotal
            fallbackDisabledTotal += pool.fallbackDisabledTotal
            fallbackOversizeTotal += pool.fallbackOversizeTotal
            fallbackInvalidQueryTotal += pool.fallbackInvalidQueryTotal
            fallbackCapacityTotal += pool.fallbackCapacityTotal
            fallbackHeapCreateTotal += pool.fallbackHeapCreateTotal
            fallbackHeapAllocateTotal += pool.fallbackHeapAllocateTotal
            allocationFailuresTotal += pool.allocationFailuresTotal
        }

        for pool in poolsByDevice.values {
            for page in pool.pages {
                if page.retirePending {
                    retirePendingPages += 1
                }
                let heapSize = page.heap.size
                let usedSize = page.heap.usedSize
                let currentAllocated = page.heap.currentAllocatedSize
                let largestAvailable = page.heap.maxAvailableSize(
                    alignment: fragmentationProbeAlignment
                )
                heapSizeBytesCurrent += heapSize
                heapCurrentAllocatedBytes += currentAllocated
                heapUsedBytesCurrent += usedSize
                heapLargestAvailableBytes = max(
                    heapLargestAvailableBytes,
                    largestAvailable
                )
                heapFragmentationEstimateBytes += max(
                    heapSize - usedSize - largestAvailable,
                    0
                )
            }
        }
        let snapshot = MetallumStaticGeometryHeapSnapshot(
            enabled: NativeState.staticGeometryHeapsEnabled,
            poolsCurrent: poolsByDevice.count,
            pageSizeBytes: Self.pageSize,
            pageLimitPerDevice: Self.pageLimitPerDevice,
            pagesCurrent: pagesCurrent,
            pagesPeak: pagesPeak,
            pagesCreatedTotal: pagesCreatedTotal,
            pagesRetiredTotal: pagesRetiredTotal,
            retirePendingPages: retirePendingPages,
            heapSizeBytesCurrent: heapSizeBytesCurrent,
            heapCurrentAllocatedBytes: heapCurrentAllocatedBytes,
            heapUsedBytesCurrent: heapUsedBytesCurrent,
            fragmentationProbeAlignment: fragmentationProbeAlignment,
            heapLargestAvailableBytes: heapLargestAvailableBytes,
            heapFragmentationEstimateBytes: heapFragmentationEstimateBytes,
            liveAllocations: liveAllocations,
            liveRequestedBytes: liveRequestedBytes,
            liveQueryBytes: liveQueryBytes,
            requestsTotal: requestsTotal,
            requestedBytesTotal: requestedBytesTotal,
            heapAllocationsTotal: heapAllocationsTotal,
            heapQueryBytesTotal: heapQueryBytesTotal,
            pageReuseHitsTotal: pageReuseHitsTotal,
            fallbackAllocationsTotal: fallbackAllocationsTotal,
            fallbackRequestedBytesTotal: fallbackRequestedBytesTotal,
            fallbackDisabledTotal: fallbackDisabledTotal,
            fallbackOversizeTotal: fallbackOversizeTotal,
            fallbackInvalidQueryTotal: fallbackInvalidQueryTotal,
            fallbackCapacityTotal: fallbackCapacityTotal,
            fallbackHeapCreateTotal: fallbackHeapCreateTotal,
            fallbackHeapAllocateTotal: fallbackHeapAllocateTotal,
            allocationFailuresTotal: allocationFailuresTotal,
            deviceTeardownWithLiveAllocationsTotal: teardownWithLiveAllocationsTotal
        )
        lock.unlock()
        return snapshot
    }

    private func registerLocked(
        buffer: MTLBuffer,
        pool: MetallumStaticGeometryHeapPool,
        page: MetallumStaticGeometryHeapPage?,
        requestedBytes: Int,
        queryBytes: Int
    ) {
        let address = objectAddress(buffer)
        precondition(recordsByBuffer[address] == nil)
        recordsByBuffer[address] = MetallumStaticGeometryAllocationRecord(
            pool: pool,
            page: page,
            requestedBytes: requestedBytes,
            queryBytes: queryBytes
        )
        pool.liveAllocations += 1
        pool.liveRequestedBytes += requestedBytes
        pool.liveQueryBytes += queryBytes
        if let page {
            page.liveAllocations += 1
            page.liveRequestedBytes += requestedBytes
            page.liveQueryBytes += queryBytes
            page.retirePending = false
            pool.heapAllocationsTotal += 1
            pool.heapQueryBytesTotal += queryBytes
        }
    }

    private func makeStandaloneLocked(
        pool: MetallumStaticGeometryHeapPool,
        length: Int,
        queryBytes: Int,
        reason: FallbackReason
    ) -> MTLBuffer? {
        switch reason {
        case .disabled:
            pool.fallbackDisabledTotal += 1
        case .oversize:
            pool.fallbackOversizeTotal += 1
        case .invalidQuery:
            pool.fallbackInvalidQueryTotal += 1
        case .capacity:
            pool.fallbackCapacityTotal += 1
        case .heapCreate:
            pool.fallbackHeapCreateTotal += 1
        case .heapAllocate:
            pool.fallbackHeapAllocateTotal += 1
        }
        guard let buffer = pool.device.makeBuffer(
            length: length,
            options: Self.resourceOptions
        ) else {
            pool.allocationFailuresTotal += 1
            return nil
        }
        pool.fallbackAllocationsTotal += 1
        pool.fallbackRequestedBytesTotal += length
        registerLocked(
            buffer: buffer,
            pool: pool,
            page: nil,
            requestedBytes: length,
            queryBytes: queryBytes
        )
        return buffer
    }

    private func sweepRetiredPagesLocked(_ pool: MetallumStaticGeometryHeapPool) {
        for index in pool.pages.indices.reversed() {
            let page = pool.pages[index]
            guard page.retirePending,
                  page.liveAllocations == 0,
                  page.releasesInProgress == 0,
                  page.heap.usedSize == 0 else {
                continue
            }
            pool.pages.remove(at: index)
            pool.pagesRetiredTotal += 1
        }
    }
}

private enum MetallumLightingAbiV1 {
    static let version: UInt32 = 1
    static let batchMagic: UInt32 = 0x31424c4d // "MLB1" in little-endian memory.
    static let uploadHeaderBytes = 64
    static let gpuLightBytes = 48
    static let clusterHeaderBytes = 8
    static let clusterScratchBytes = 512
    static let lightIndexBytes = 4
    static let paramsBytes = 256
    static let statisticsBytes = 256
    static let completedStatsBytes = 128
    static let ringSlots = 3
    static let clusterCap: UInt32 = 256
    static let tileSize: UInt32 = 64
    static let depthSlices: UInt32 = 6
    static let prefixBlockSize: UInt32 = 256
    static let blockStatisticsBytes = 160
    static let guardBytes = 64
    static let guardValue: UInt8 = 0xa5
    static let orderedBatchFlag: UInt32 = 1
    static let clusterMaskBatchFlag: UInt32 = 1 << 1
    static let knownBatchFlags = orderedBatchFlag | clusterMaskBatchFlag
    // ABI v1 cluster membership is 128 UInt32 words per cluster, matching the full
    // 4096-candidate upload pool independently from the per-cluster quality cap.
    static let maximumLights: UInt32 = 4_096
    static let maximumClusters: UInt32 = 1_048_576
    static let maximumIndices: UInt32 = 8_000_000
    static let statisticsSampleInterval: UInt64 = 32
    static let validateEveryFrame: Bool = {
        for name in ["MTL_DEBUG_LAYER", "MTL_SHADER_VALIDATION"] {
            if let value = getenv(name), strcmp(value, "1") == 0 {
                return true
            }
        }
        return false
    }()
}

private struct MetallumLightingParamsV1 {
    var viewRotation: simd_float4x4
    var projection: simd_float4x4
    var gridAndLightCount: SIMD4<UInt32>
    var extentAndClusterCap: SIMD4<UInt32>
    var depth: SIMD4<Float>
    var frameIdAndGeneration: SIMD4<UInt32>
    var capacitiesAndFlags: SIMD4<UInt32>
    var reserved0: SIMD4<UInt32>
    var reserved1: SIMD4<UInt32>
    var reserved2: SIMD4<UInt32>
}

private final class MetallumLightingPipelines {
    let prepare: MTLComputePipelineState
    let count: MTLComputePipelineState
    let masks: MTLComputePipelineState
    let prefixBlocks: MTLComputePipelineState
    let prefixGroups: MTLComputePipelineState
    let prefixAdd: MTLComputePipelineState
    let fill: MTLComputePipelineState

    init(
        prepare: MTLComputePipelineState,
        count: MTLComputePipelineState,
        masks: MTLComputePipelineState,
        prefixBlocks: MTLComputePipelineState,
        prefixGroups: MTLComputePipelineState,
        prefixAdd: MTLComputePipelineState,
        fill: MTLComputePipelineState
    ) {
        self.prepare = prepare
        self.count = count
        self.masks = masks
        self.prefixBlocks = prefixBlocks
        self.prefixGroups = prefixGroups
        self.prefixAdd = prefixAdd
        self.fill = fill
    }
}

private final class MetallumLightingRingSlot {
    let staging: MTLBuffer
    var busy = false

    init(staging: MTLBuffer) {
        self.staging = staging
    }
}

private struct MetallumLightingCompletedStatsV1 {
    let generation: UInt64
    let frameId: UInt64
    let lightCount: UInt32
    let clusterCount: UInt32
    let acceptedIndices: UInt32
    let requestedIndices: UInt32
    let overflowClusters: UInt32
    let perClusterDrops: UInt32
    let indexCapacityDrops: UInt32
    let admissionRejectedLights: UInt32
    let occupancyP50: UInt32
    let occupancyP95: UInt32
    let occupancyP99: UInt32
    let maximumOccupancy: UInt32
}

private struct MetallumLightingCpuSnapshotV1 {
    let lastCompleted: MetallumLightingCompletedStatsV1?
    let ringHighWater: UInt32
    let ringBusyRejects: UInt32
    let uploadCalls: UInt64
    let completedCalls: UInt64
    let rejectedCalls: UInt64
}

private struct MetallumLightingTelemetrySnapshotV1 {
    let active: Bool
    let generation: UInt64
    let frameId: UInt64
    let lightCount: UInt32
    let clusterCount: UInt32
    let acceptedIndices: UInt32
    let requestedIndices: UInt32
    let overflowClusters: UInt32
    let droppedIndices: UInt32
    let indexCapacityDrops: UInt32
    let admissionRejectedLights: UInt32
    let occupancyP50: UInt32
    let occupancyP95: UInt32
    let occupancyP99: UInt32
    let maximumOccupancy: UInt32
    let ringHighWater: UInt32
    let ringBusyRejects: UInt32

    var report: [String: Any] {
        [
            "active": active,
            "generation": generation,
            "frame_id": frameId,
            "light_count": lightCount,
            "cluster_count": clusterCount,
            "cluster_accepted_indices": acceptedIndices,
            "cluster_requested_indices": requestedIndices,
            "cluster_overflow_clusters": overflowClusters,
            "cluster_dropped_indices": droppedIndices,
            "cluster_index_capacity_drops": indexCapacityDrops,
            "cluster_admission_rejected_lights": admissionRejectedLights,
            "cluster_occupancy_p50": occupancyP50,
            "cluster_occupancy_p95": occupancyP95,
            "cluster_occupancy_p99": occupancyP99,
            "cluster_occupancy_max": maximumOccupancy,
            "lighting_ring_high_water": ringHighWater,
            "lighting_ring_busy_rejects": ringBusyRejects,
            "statistics_sample_interval": active
                ? MetallumLightingAbiV1.statisticsSampleInterval : 0,
            "output_independent": true
        ]
    }
}

private final class MetallumLightingTelemetryStore: @unchecked Sendable {
    static let shared = MetallumLightingTelemetryStore()
    private let lock = NSLock()
    private var nextToken: UInt64 = 1
    private var latestToken: UInt64 = 0
    private var latest = MetallumLightingTelemetrySnapshotV1(
        active: false,
        generation: 0,
        frameId: 0,
        lightCount: 0,
        clusterCount: 0,
        acceptedIndices: 0,
        requestedIndices: 0,
        overflowClusters: 0,
        droppedIndices: 0,
        indexCapacityDrops: 0,
        admissionRejectedLights: 0,
        occupancyP50: 0,
        occupancyP95: 0,
        occupancyP99: 0,
        maximumOccupancy: 0,
        ringHighWater: 0,
        ringBusyRejects: 0
    )

    func activate(generation: UInt64, clusterCount: UInt32) -> UInt64 {
        lock.lock()
        let token = nextToken
        nextToken &+= 1
        latestToken = token
        latest = MetallumLightingTelemetrySnapshotV1(
            active: true,
            generation: generation,
            frameId: 0,
            lightCount: 0,
            clusterCount: clusterCount,
            acceptedIndices: 0,
            requestedIndices: 0,
            overflowClusters: 0,
            droppedIndices: 0,
            indexCapacityDrops: 0,
            admissionRejectedLights: 0,
            occupancyP50: 0,
            occupancyP95: 0,
            occupancyP99: 0,
            maximumOccupancy: 0,
            ringHighWater: 0,
            ringBusyRejects: 0
        )
        lock.unlock()
        return token
    }

    func publish(
        token: UInt64,
        completed: MetallumLightingCompletedStatsV1,
        ringHighWater: UInt32,
        ringBusyRejects: UInt32
    ) {
        lock.lock()
        if latestToken == token,
           latest.active,
           latest.generation == completed.generation,
           completed.frameId >= latest.frameId {
            latest = MetallumLightingTelemetrySnapshotV1(
                active: true,
                generation: completed.generation,
                frameId: completed.frameId,
                lightCount: completed.lightCount,
                clusterCount: completed.clusterCount,
                acceptedIndices: completed.acceptedIndices,
                requestedIndices: completed.requestedIndices,
                overflowClusters: completed.overflowClusters,
                droppedIndices: completed.perClusterDrops &+ completed.indexCapacityDrops,
                indexCapacityDrops: completed.indexCapacityDrops,
                admissionRejectedLights: completed.admissionRejectedLights,
                occupancyP50: completed.occupancyP50,
                occupancyP95: completed.occupancyP95,
                occupancyP99: completed.occupancyP99,
                maximumOccupancy: completed.maximumOccupancy,
                ringHighWater: ringHighWater,
                ringBusyRejects: ringBusyRejects
            )
        }
        lock.unlock()
    }

    func deactivate(token: UInt64) {
        lock.lock()
        if latestToken == token {
            latestToken = 0
            latest = MetallumLightingTelemetrySnapshotV1(
                active: false,
                generation: 0,
                frameId: 0,
                lightCount: 0,
                clusterCount: 0,
                acceptedIndices: 0,
                requestedIndices: 0,
                overflowClusters: 0,
                droppedIndices: 0,
                indexCapacityDrops: 0,
                admissionRejectedLights: 0,
                occupancyP50: 0,
                occupancyP95: 0,
                occupancyP99: 0,
                maximumOccupancy: 0,
                ringHighWater: 0,
                ringBusyRejects: 0
            )
        }
        lock.unlock()
    }

    func snapshot() -> MetallumLightingTelemetrySnapshotV1 {
        lock.lock()
        let value = latest
        lock.unlock()
        return value
    }
}

private final class MetallumLightingContext: @unchecked Sendable {
    let device: MTLDevice
    let generation: UInt64
    let telemetryToken: UInt64
    let maxLights: UInt32
    let indexCapacity: UInt32
    let clustersX: UInt32
    let clustersY: UInt32
    let clustersZ: UInt32
    let clusterCount: UInt32
    let pipelines: MetallumLightingPipelines
    let gpuLights: MTLBuffer
    let clusterHeaders: MTLBuffer
    let clusterScratch: MTLBuffer
    let lightIndices: MTLBuffer
    let params: MTLBuffer
    let statistics: MTLBuffer
    let slots: [MetallumLightingRingSlot]

    private let lock = NSLock()
    private var queueAddress: UInt?
    private var activeSlots: UInt32 = 0
    private var ringHighWater: UInt32 = 0
    private var ringBusyRejects: UInt32 = 0
    private var uploadCalls: UInt64 = 0
    private var completedCalls: UInt64 = 0
    private var rejectedCalls: UInt64 = 0
    private var lastCompleted: MetallumLightingCompletedStatsV1?
    private var guardRegionsInitialized = false
    private var retired = false

    init(
        device: MTLDevice,
        generation: UInt64,
        telemetryToken: UInt64,
        maxLights: UInt32,
        indexCapacity: UInt32,
        clustersX: UInt32,
        clustersY: UInt32,
        clustersZ: UInt32,
        clusterCount: UInt32,
        pipelines: MetallumLightingPipelines,
        gpuLights: MTLBuffer,
        clusterHeaders: MTLBuffer,
        clusterScratch: MTLBuffer,
        lightIndices: MTLBuffer,
        params: MTLBuffer,
        statistics: MTLBuffer,
        slots: [MetallumLightingRingSlot]
    ) {
        self.device = device
        self.generation = generation
        self.telemetryToken = telemetryToken
        self.maxLights = maxLights
        self.indexCapacity = indexCapacity
        self.clustersX = clustersX
        self.clustersY = clustersY
        self.clustersZ = clustersZ
        self.clusterCount = clusterCount
        self.pipelines = pipelines
        self.gpuLights = gpuLights
        self.clusterHeaders = clusterHeaders
        self.clusterScratch = clusterScratch
        self.lightIndices = lightIndices
        self.params = params
        self.statistics = statistics
        self.slots = slots
    }

    func noteUploadCall() {
        lock.lock()
        uploadCalls &+= 1
        lock.unlock()
    }

    func noteRejectedCall() {
        lock.lock()
        rejectedCalls &+= 1
        lock.unlock()
    }

    func claimGuardInitialization() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if guardRegionsInitialized {
            return false
        }
        guardRegionsInitialized = true
        return true
    }

    func shouldReadbackStatistics(frameId: UInt64) -> Bool {
        if MetallumLightingAbiV1.validateEveryFrame {
            return true
        }
        lock.lock()
        let needsFirstSample = lastCompleted == nil
        lock.unlock()
        return needsFirstSample
            || frameId.isMultiple(of: MetallumLightingAbiV1.statisticsSampleInterval)
    }

    func reserve(slot index: Int, queue: MTLCommandQueue) -> Int32 {
        lock.lock()
        defer { lock.unlock() }
        let candidateQueueAddress = objectAddress(queue)
        if let queueAddress, queueAddress != candidateQueueAddress {
            rejectedCalls &+= 1
            return -10
        }
        if slots[index].busy {
            ringBusyRejects &+= 1
            rejectedCalls &+= 1
            return -12
        }
        queueAddress = candidateQueueAddress
        slots[index].busy = true
        activeSlots &+= 1
        ringHighWater = max(ringHighWater, activeSlots)
        return 1
    }

    func cancel(slot index: Int) {
        lock.lock()
        if slots[index].busy {
            slots[index].busy = false
            activeSlots = activeSlots > 0 ? activeSlots - 1 : 0
        }
        rejectedCalls &+= 1
        lock.unlock()
    }

    func rejectAfterEncoding(slot index: Int, commandBuffer: MTLCommandBuffer) {
        lock.lock()
        rejectedCalls &+= 1
        lock.unlock()
        // Encoded upload work can still consume this slot if the caller commits the failed
        // frame. Keep it reserved until completion instead of exposing staging bytes to reuse.
        commandBuffer.addCompletedHandler { [self] _ in
            lock.lock()
            if slots[index].busy {
                slots[index].busy = false
                activeSlots = activeSlots > 0 ? activeSlots - 1 : 0
            }
            lock.unlock()
        }
    }

    func complete(
        slot index: Int,
        commandBufferSucceeded: Bool,
        statisticsAvailable: Bool
    ) {
        var completed: MetallumLightingCompletedStatsV1?
        if commandBufferSucceeded && statisticsAvailable {
            let values = slots[index].staging.contents().bindMemory(
                to: UInt32.self,
                capacity: MetallumLightingAbiV1.statisticsBytes / MemoryLayout<UInt32>.stride
            )
            if values[0] == MetallumLightingAbiV1.version {
                let clusterCount = values[2]
                let emptyClusters = min(values[14], clusterCount)
                let histogram = (0..<32).map { values[32 + $0] }
                let maximumOccupancy = values[15]
                completed = MetallumLightingCompletedStatsV1(
                    generation: UInt64(values[10]) | UInt64(values[11]) << 32,
                    frameId: UInt64(values[12]) | UInt64(values[13]) << 32,
                    lightCount: values[1],
                    clusterCount: clusterCount,
                    acceptedIndices: values[3],
                    requestedIndices: values[4],
                    overflowClusters: values[5],
                    perClusterDrops: values[6],
                    indexCapacityDrops: values[7],
                    admissionRejectedLights: values[8],
                    occupancyP50: min(
                        Self.quantile(
                            histogram,
                            emptyClusters: emptyClusters,
                            percentile: 50,
                            maximumOccupancy: maximumOccupancy
                        ),
                        maximumOccupancy
                    ),
                    occupancyP95: min(
                        Self.quantile(
                            histogram,
                            emptyClusters: emptyClusters,
                            percentile: 95,
                            maximumOccupancy: maximumOccupancy
                        ),
                        maximumOccupancy
                    ),
                    occupancyP99: min(
                        Self.quantile(
                            histogram,
                            emptyClusters: emptyClusters,
                            percentile: 99,
                            maximumOccupancy: maximumOccupancy
                        ),
                        maximumOccupancy
                    ),
                    maximumOccupancy: maximumOccupancy
                )
            }
        }

        var publish: (MetallumLightingCompletedStatsV1, UInt32, UInt32)?
        lock.lock()
        if slots[index].busy {
            slots[index].busy = false
            activeSlots = activeSlots > 0 ? activeSlots - 1 : 0
        }
        if let completed {
            completedCalls &+= 1
            if lastCompleted == nil || completed.frameId >= lastCompleted!.frameId {
                lastCompleted = completed
            }
            if !retired {
                publish = (completed, ringHighWater, ringBusyRejects)
            }
        } else if commandBufferSucceeded && !statisticsAvailable {
            completedCalls &+= 1
        } else {
            rejectedCalls &+= 1
        }
        lock.unlock()
        if let publish {
            MetallumLightingTelemetryStore.shared.publish(
                token: telemetryToken,
                completed: publish.0,
                ringHighWater: publish.1,
                ringBusyRejects: publish.2
            )
        }
    }

    func retire() {
        lock.lock()
        retired = true
        lock.unlock()
        MetallumLightingTelemetryStore.shared.deactivate(token: telemetryToken)
    }

    func snapshot() -> MetallumLightingCpuSnapshotV1 {
        lock.lock()
        defer { lock.unlock() }
        return MetallumLightingCpuSnapshotV1(
            lastCompleted: lastCompleted,
            ringHighWater: ringHighWater,
            ringBusyRejects: ringBusyRejects,
            uploadCalls: uploadCalls,
            completedCalls: completedCalls,
            rejectedCalls: rejectedCalls
        )
    }

    private static func quantile(
        _ histogram: [UInt32],
        emptyClusters: UInt32,
        percentile: UInt32,
        maximumOccupancy: UInt32
    ) -> UInt32 {
        let total = histogram.reduce(UInt64(0)) { $0 + UInt64($1) }
        guard total > 0 else { return 0 }
        let target = max(UInt64(1), (total * UInt64(percentile) + 99) / 100)
        if target <= UInt64(emptyClusters) {
            return 0
        }
        var cumulative = UInt64(0)
        for (bin, count) in histogram.enumerated() {
            cumulative += UInt64(count)
            if cumulative >= target {
                if bin == 0 {
                    return 0
                }
                return bin == histogram.count - 1 ? maximumOccupancy : UInt32(bin * 4)
            }
        }
        return maximumOccupancy
    }
}

// MARK: - L5 voxel clipmap ABI v1
//
// This is intentionally self-contained.  L3/L4 neither allocate nor bind these
// resources; an unavailable voxel library simply makes this context unavailable.
private enum MetallumVoxelAbiV1 {
    static let version: UInt32 = 1
    static let magic: UInt32 = 0x3142564d // "MVB1" in little-endian memory.
    static let headerBytes = 96
    static let recordBytes = 56
    static let levelLayoutBytes = 32
    static let paramsBytes = 72
    // Metal constant-buffer offsets are 256-byte aligned; the ABI payload itself
    // remains compact at 72 bytes, while in-flight parameter records use this stride.
    static let paramsStride = 256
    static let statsBytes = 160
    static let checksumBytes = 16
    static let logicalBrickEdge: UInt32 = 32
    static let occupancyWordsPerBrick: UInt32 = 1_024
    static let occupancyBytesPerBrick: UInt32 = 4_096
    static let guardBytes = 64
    static let guardValue: UInt8 = 0xa5
    static let ringSlots = 3
    static let indirectBytes = 12
    static let transientBusyStatus: Int32 = -22
    static let resetFlag: UInt32 = 1 << 0
    static let unloadFlag: UInt32 = 1 << 1
    static let scrollFlag: UInt32 = 1 << 2
    static let knownFlags = resetFlag | unloadFlag | scrollFlag
    // L5 uploads its packet, per-level indirect arguments, and parameters with a
    // blit encoder immediately before the compute encoder consumes them. Keep
    // these private heap resources hazard-tracked so Metal orders that producer /
    // consumer transition across encoders deterministically.
    static let privateOptions: MTLResourceOptions = .storageModePrivate
}

private struct MetallumVoxelLevelLayoutV1 {
    let logicalEdge: UInt32
    let subdivision: UInt32
    let originBrickX: UInt32
    let originBrickY: UInt32
    let originBrickZ: UInt32
    let flags: UInt32
}

private struct MetallumVoxelPatchRecordV1 {
    let level: UInt32
    let destinationBrickX: UInt32
    let destinationBrickY: UInt32
    let destinationBrickZ: UInt32
    let payloadOffset: UInt32
    let occupancyBytes: UInt32
    let opticalBytes: UInt32
    let flags: UInt32
    let brickGenerationLow: UInt32
    let brickGenerationHigh: UInt32
    let logicalBrickX: Int32
    let logicalBrickY: Int32
    let logicalBrickZ: Int32
    let contentStamp: UInt32
}

private struct MetallumVoxelLogicalDestinationKey: Hashable {
    let level: UInt32
    let x: Int32
    let y: Int32
    let z: Int32
}

private func voxelFloorMod(_ value: Int32, _ modulus: UInt32) -> UInt32 {
    let divisor = Int64(modulus)
    let remainder = Int64(value) % divisor
    return UInt32(remainder >= 0 ? remainder : remainder + divisor)
}

private struct MetallumVoxelBatchV1 {
    let flags: UInt32
    let patchCount: UInt32
    let slot: UInt32
    let frameId: UInt64
    let lightingGeneration: UInt64
    let clipmapGeneration: UInt64
    let worldGeneration: UInt64
    let queueRemaining: UInt32
    let oldestAge: UInt32
    let coalescedDelta: UInt32
    let rejectedDelta: UInt32
    let scrollSlabs: UInt32
    let unloadClears: UInt32
    let levelPatchCounts: [UInt32]
    let levelRecordStarts: [UInt32]
}

private struct MetallumVoxelParamsV1 {
    var patchCount: UInt32
    var headerBytes: UInt32
    var recordBytes: UInt32
    var levelIndex: UInt32
    var recordStart: UInt32
    var logicalEdge: UInt32
    var subdivision: UInt32
    var brickDimension: UInt32
    var occupancyWordsPerBrick: UInt32
    var reserved0: UInt32
    var lightingGeneration: UInt64
    var clipmapGeneration: UInt64
    var worldGeneration: UInt64
    var frameId: UInt64
}

private struct MetallumVoxelChecksumParamsV1 {
    var occupancyWords: UInt32
    var opticalBytes: UInt32
    var threadCount: UInt32
    var reserved: UInt32
}

private struct MetallumVoxelPipelines {
    let apply: MTLComputePipelineState
    let checksum: MTLComputePipelineState
}

private final class MetallumVoxelLevelResources {
    let layout: MetallumVoxelLevelLayoutV1
    let brickDimension: UInt32
    let occupancyWords: Int
    let opticalBytes: Int
    let brickCount: Int
    let occupancy: MTLBuffer
    let optical: MTLBuffer
    let metadata: MTLBuffer

    var occupancyPayloadBytes: Int { occupancyWords * 4 }
    var metadataPayloadBytes: Int { brickCount * 16 }

    init(
        layout: MetallumVoxelLevelLayoutV1,
        brickDimension: UInt32,
        occupancyWords: Int,
        opticalBytes: Int,
        brickCount: Int,
        occupancy: MTLBuffer,
        optical: MTLBuffer,
        metadata: MTLBuffer
    ) {
        self.layout = layout
        self.brickDimension = brickDimension
        self.occupancyWords = occupancyWords
        self.opticalBytes = opticalBytes
        self.brickCount = brickCount
        self.occupancy = occupancy
        self.optical = optical
        self.metadata = metadata
    }
}

private final class MetallumVoxelRingSlot {
    let staging: MTLBuffer
    let payload: MTLBuffer
    let indirectStaging: MTLBuffer
    let indirect: MTLBuffer
    let paramsStaging: MTLBuffer
    let params: MTLBuffer
    let debugScratch: MTLBuffer
    let debugReadback: MTLBuffer
    var busy = false

    init(
        staging: MTLBuffer,
        payload: MTLBuffer,
        indirectStaging: MTLBuffer,
        indirect: MTLBuffer,
        paramsStaging: MTLBuffer,
        params: MTLBuffer,
        debugScratch: MTLBuffer,
        debugReadback: MTLBuffer
    ) {
        self.staging = staging
        self.payload = payload
        self.indirectStaging = indirectStaging
        self.indirect = indirect
        self.paramsStaging = paramsStaging
        self.params = params
        self.debugScratch = debugScratch
        self.debugReadback = debugReadback
    }
}

private struct MetallumVoxelTelemetrySnapshotV1 {
    let active: Bool
    let lightingGeneration: UInt64
    let clipmapGeneration: UInt64
    let worldGeneration: UInt64
    let lastFrameId: UInt64
    let resourceBytes: UInt64
    let heapBytes: UInt64
    let heapUsedBytes: UInt64
    let ringStagingBytes: UInt64
    let ringPrivateBytes: UInt64
    let ringHighWater: UInt32
    let ringBusyRejects: UInt32
    let submitted: UInt64
    let completed: UInt64
    let remaining: UInt32
    let oldestAge: UInt32
    let coalesced: UInt64
    let rejected: UInt64
    let stale: UInt64
    let scrollSlabs: UInt64
    let unloadClears: UInt64
    let checksum: UInt32

    var report: [String: Any] {
        [
            "active": active,
            "output_independent": true,
            "lighting_generation": lightingGeneration,
            "clipmap_generation": clipmapGeneration,
            "world_generation": worldGeneration,
            "frame_id": lastFrameId,
            "resource_bytes": resourceBytes,
            "heap_bytes": heapBytes,
            "heap_used_bytes": heapUsedBytes,
            "ring_staging_bytes": ringStagingBytes,
            "ring_private_bytes": ringPrivateBytes,
            "ring_high_water": ringHighWater,
            "ring_busy_rejects": ringBusyRejects,
            "dirty_bricks_submitted": submitted,
            "dirty_bricks_completed": completed,
            "dirty_bricks_remaining": remaining,
            "oldest_dirty_age": oldestAge,
            "coalesced": coalesced,
            "rejected": rejected,
            "stale": stale,
            "scroll_slabs": scrollSlabs,
            "unload_clears": unloadClears,
            "debug_checksum": checksum
        ]
    }
}

private final class MetallumVoxelTelemetryStore: @unchecked Sendable {
    static let shared = MetallumVoxelTelemetryStore()
    private let lock = NSLock()
    private var nextToken: UInt64 = 1
    private var latestToken: UInt64 = 0
    private var latest = MetallumVoxelTelemetrySnapshotV1(
        active: false, lightingGeneration: 0, clipmapGeneration: 0, worldGeneration: 0, lastFrameId: 0,
        resourceBytes: 0, heapBytes: 0, heapUsedBytes: 0, ringStagingBytes: 0,
        ringPrivateBytes: 0, ringHighWater: 0, ringBusyRejects: 0, submitted: 0,
        completed: 0, remaining: 0, oldestAge: 0, coalesced: 0, rejected: 0,
        stale: 0, scrollSlabs: 0, unloadClears: 0, checksum: 0
    )

    func activate(_ snapshot: MetallumVoxelTelemetrySnapshotV1) -> UInt64 {
        lock.lock()
        let token = nextToken
        nextToken &+= 1
        latestToken = token
        latest = snapshot
        lock.unlock()
        return token
    }

    func publish(token: UInt64, _ snapshot: MetallumVoxelTelemetrySnapshotV1) {
        lock.lock()
        if latestToken == token { latest = snapshot }
        lock.unlock()
    }

    func deactivate(token: UInt64) {
        lock.lock()
        if latestToken == token {
            latestToken = 0
            latest = MetallumVoxelTelemetrySnapshotV1(
                active: false, lightingGeneration: 0, clipmapGeneration: 0, worldGeneration: 0, lastFrameId: 0,
                resourceBytes: 0, heapBytes: 0, heapUsedBytes: 0, ringStagingBytes: 0,
                ringPrivateBytes: 0, ringHighWater: 0, ringBusyRejects: 0, submitted: 0,
                completed: 0, remaining: 0, oldestAge: 0, coalesced: 0, rejected: 0,
                stale: 0, scrollSlabs: 0, unloadClears: 0, checksum: 0
            )
        }
        lock.unlock()
    }

    func snapshot() -> MetallumVoxelTelemetrySnapshotV1 {
        lock.lock()
        let value = latest
        lock.unlock()
        return value
    }
}

private final class MetallumVoxelContext: @unchecked Sendable {
    let device: MTLDevice
    let lightingGeneration: UInt64
    let clipmapGeneration: UInt64
    let worldGeneration: UInt64
    let heap: MTLHeap
    let levels: [MetallumVoxelLevelResources]
    let slots: [MetallumVoxelRingSlot]
    let maxPatchCount: UInt32
    let stagingBytes: Int
    let indirectBytesPerSlot: Int
    let pipelines: MetallumVoxelPipelines
    let telemetryToken: UInt64
    let resourceBytes: UInt64
    let ringStagingBytes: UInt64
    let ringPrivateBytes: UInt64

    private let lock = NSLock()
    private var queueAddress: UInt?
    private var activeSlots: UInt32 = 0
    private var ringHighWater: UInt32 = 0
    private var ringBusyRejects: UInt32 = 0
    private var submitted: UInt64 = 0
    private var completed: UInt64 = 0
    private var remaining: UInt32 = 0
    private var oldestAge: UInt32 = 0
    private var coalesced: UInt64 = 0
    private var rejected: UInt64 = 0
    private var stale: UInt64 = 0
    private var scrollSlabs: UInt64 = 0
    private var unloadClears: UInt64 = 0
    private var lastFrameId: UInt64 = 0
    private var lastChecksum: UInt32 = 0
    private var retired = false

    init(
        device: MTLDevice,
        lightingGeneration: UInt64,
        clipmapGeneration: UInt64,
        worldGeneration: UInt64,
        heap: MTLHeap,
        levels: [MetallumVoxelLevelResources],
        slots: [MetallumVoxelRingSlot],
        maxPatchCount: UInt32,
        stagingBytes: Int,
        indirectBytesPerSlot: Int,
        pipelines: MetallumVoxelPipelines,
        resourceBytes: UInt64,
        ringStagingBytes: UInt64,
        ringPrivateBytes: UInt64
    ) {
        self.device = device
        self.lightingGeneration = lightingGeneration
        self.clipmapGeneration = clipmapGeneration
        self.worldGeneration = worldGeneration
        self.heap = heap
        self.levels = levels
        self.slots = slots
        self.maxPatchCount = maxPatchCount
        self.stagingBytes = stagingBytes
        self.indirectBytesPerSlot = indirectBytesPerSlot
        self.pipelines = pipelines
        self.resourceBytes = resourceBytes
        self.ringStagingBytes = ringStagingBytes
        self.ringPrivateBytes = ringPrivateBytes
        self.telemetryToken = MetallumVoxelTelemetryStore.shared.activate(
            MetallumVoxelTelemetrySnapshotV1(
                active: true, lightingGeneration: lightingGeneration,
                clipmapGeneration: clipmapGeneration, worldGeneration: worldGeneration,
                lastFrameId: 0,
                resourceBytes: resourceBytes, heapBytes: UInt64(heap.size),
                heapUsedBytes: UInt64(heap.usedSize), ringStagingBytes: ringStagingBytes,
                ringPrivateBytes: ringPrivateBytes, ringHighWater: 0, ringBusyRejects: 0,
                submitted: 0, completed: 0, remaining: 0, oldestAge: 0, coalesced: 0,
                rejected: 0, stale: 0, scrollSlabs: 0, unloadClears: 0, checksum: 0
            )
        )
    }

    func reserve(slot index: Int, queue: MTLCommandQueue) -> Int32 {
        lock.lock()
        defer { lock.unlock() }
        guard !retired else { return -10 }
        let address = objectAddress(queue)
        if let queueAddress, queueAddress != address {
            rejected &+= 1
            return -11
        }
        guard !slots[index].busy else {
            ringBusyRejects &+= 1
            rejected &+= 1
            return MetallumVoxelAbiV1.transientBusyStatus
        }
        queueAddress = address
        slots[index].busy = true
        activeSlots &+= 1
        ringHighWater = max(ringHighWater, activeSlots)
        return 1
    }

    /// Diagnostic commands share the physical scratch slots but never contribute to the
    /// production rejection/high-water counters used by Java to decide whether L5 is healthy.
    func reserveDebug(slot index: Int, queue: MTLCommandQueue) -> Int32 {
        lock.lock()
        defer { lock.unlock() }
        guard !retired else { return -10 }
        let address = objectAddress(queue)
        if let queueAddress, queueAddress != address {
            return -11
        }
        guard !slots[index].busy else {
            return MetallumVoxelAbiV1.transientBusyStatus
        }
        queueAddress = address
        slots[index].busy = true
        return 1
    }

    func cancel(slot index: Int) {
        lock.lock()
        if slots[index].busy {
            slots[index].busy = false
            activeSlots = activeSlots > 0 ? activeSlots - 1 : 0
        }
        rejected &+= 1
        lock.unlock()
        publishTelemetry()
    }

    func cancelDebug(slot index: Int) {
        lock.lock()
        if slots[index].busy {
            slots[index].busy = false
        }
        lock.unlock()
    }

    func noteStale() {
        lock.lock()
        stale &+= 1
        lock.unlock()
        publishTelemetry()
    }

    func noteRejected() {
        lock.lock()
        rejected &+= 1
        lock.unlock()
        publishTelemetry()
    }

    func noteSubmission(_ batch: MetallumVoxelBatchV1) {
        lock.lock()
        submitted &+= UInt64(batch.patchCount)
        remaining = batch.queueRemaining
        oldestAge = batch.oldestAge
        coalesced &+= UInt64(batch.coalescedDelta)
        rejected &+= UInt64(batch.rejectedDelta)
        scrollSlabs &+= UInt64(batch.scrollSlabs)
        unloadClears &+= UInt64(batch.unloadClears)
        lastFrameId = max(lastFrameId, batch.frameId)
        lock.unlock()
        publishTelemetry()
    }

    func complete(slot index: Int, patchCount: UInt32, succeeded: Bool) {
        lock.lock()
        if slots[index].busy {
            slots[index].busy = false
            activeSlots = activeSlots > 0 ? activeSlots - 1 : 0
        }
        if succeeded {
            completed &+= UInt64(patchCount)
        } else {
            rejected &+= 1
        }
        lock.unlock()
        publishTelemetry()
    }

    func completeDebug(slot index: Int, succeeded: Bool) {
        lock.lock()
        if slots[index].busy {
            slots[index].busy = false
        }
        if succeeded {
            lastChecksum = slots[index].debugReadback.contents().load(as: UInt32.self)
        }
        lock.unlock()
        publishTelemetry()
    }

    func retire() {
        lock.lock()
        retired = true
        lock.unlock()
        MetallumVoxelTelemetryStore.shared.deactivate(token: telemetryToken)
    }

    func stats(into output: UnsafeMutableRawPointer) -> Bool {
        lock.lock()
        let values = snapshotLocked()
        lock.unlock()
        output.initializeMemory(as: UInt8.self, repeating: 0, count: MetallumVoxelAbiV1.statsBytes)
        writeVoxelValue(MetallumVoxelAbiV1.version, to: output, offset: 0)
        writeVoxelValue(UInt32(MetallumVoxelAbiV1.statsBytes), to: output, offset: 4)
        writeVoxelValue(lightingGeneration, to: output, offset: 8)
        writeVoxelValue(clipmapGeneration, to: output, offset: 16)
        writeVoxelValue(worldGeneration, to: output, offset: 24)
        writeVoxelValue(values.lastFrameId, to: output, offset: 32)
        writeVoxelValue(values.submitted, to: output, offset: 40)
        writeVoxelValue(values.completed, to: output, offset: 48)
        writeVoxelValue(values.remaining, to: output, offset: 56)
        writeVoxelValue(values.oldestAge, to: output, offset: 60)
        writeVoxelValue(values.coalesced, to: output, offset: 64)
        writeVoxelValue(values.rejected, to: output, offset: 72)
        writeVoxelValue(values.stale, to: output, offset: 80)
        writeVoxelValue(values.scrollSlabs, to: output, offset: 88)
        writeVoxelValue(values.unloadClears, to: output, offset: 96)
        writeVoxelValue(values.ringHighWater, to: output, offset: 104)
        writeVoxelValue(values.ringBusyRejects, to: output, offset: 108)
        writeVoxelValue(values.checksum, to: output, offset: 112)
        writeVoxelValue(UInt32(levels.count), to: output, offset: 116)
        writeVoxelValue(resourceBytes, to: output, offset: 120)
        writeVoxelValue(UInt64(heap.size), to: output, offset: 128)
        writeVoxelValue(UInt64(heap.usedSize), to: output, offset: 136)
        writeVoxelValue(ringStagingBytes, to: output, offset: 144)
        writeVoxelValue(ringPrivateBytes, to: output, offset: 152)
        return values.completed > 0
    }

    func snapshot() -> MetallumVoxelTelemetrySnapshotV1 {
        lock.lock()
        let value = snapshotLocked()
        lock.unlock()
        return value
    }

    private func snapshotLocked() -> MetallumVoxelTelemetrySnapshotV1 {
        MetallumVoxelTelemetrySnapshotV1(
            active: !retired, lightingGeneration: lightingGeneration,
            clipmapGeneration: clipmapGeneration, worldGeneration: worldGeneration,
            lastFrameId: lastFrameId,
            resourceBytes: resourceBytes, heapBytes: UInt64(heap.size),
            heapUsedBytes: UInt64(heap.usedSize), ringStagingBytes: ringStagingBytes,
            ringPrivateBytes: ringPrivateBytes, ringHighWater: ringHighWater,
            ringBusyRejects: ringBusyRejects, submitted: submitted, completed: completed,
            remaining: remaining, oldestAge: oldestAge, coalesced: coalesced,
            rejected: rejected, stale: stale, scrollSlabs: scrollSlabs,
            unloadClears: unloadClears, checksum: lastChecksum
        )
    }

    private func publishTelemetry() {
        MetallumVoxelTelemetryStore.shared.publish(token: telemetryToken, snapshot())
    }
}

private func writeVoxelValue<T>(_ value: T, to output: UnsafeMutableRawPointer, offset: Int) {
    var stored = value
    withUnsafeBytes(of: &stored) { bytes in
        output.advanced(by: offset).copyMemory(from: bytes.baseAddress!, byteCount: bytes.count)
    }
}

private struct MetallumHdrOutputs {
    let emission: MTLTexture
    let bloom: MTLTexture
    let uiMask: MTLTexture
    let adaptiveState: MTLBuffer
}

private struct MetallumHdrWorldOutputs {
    let emission: MTLTexture
    let bloom: MTLTexture
    let adaptiveState: MTLBuffer
}

private enum MetallumGpuTimingStage: Int, CaseIterable {
    case worldOpaque = 0
    case translucent = 1
    case entities = 2
    case hdrExtract = 3
    case histogramExposure = 4
    case bloomHorizontal = 5
    case bloomVertical = 6
    case hdrReconstruction = 7
    case metalFx = 8
    case uiSeed = 9
    case ui = 10
    case present = 11
    case actualHdrDisplay = 12
    case lightUploadClusterBuild = 13
    case sunShadow = 14
    // Append-only: Java's stable timing IDs 0...14 must never be renumbered.
    case voxelUploadUpdate = 15
    // Append-only: moving L6 page generation is separately attributable from L5 updates.
    case dynamicLocalShadow = 16

    var reportName: String {
        switch self {
        case .worldOpaque: "world opaque"
        case .translucent: "translucent"
        case .entities: "entities/features"
        case .hdrExtract: "HDR extract + histogram"
        case .histogramExposure: "exposure reduction"
        case .bloomHorizontal: "bloom combined"
        case .bloomVertical: "bloom vertical (reserved)"
        case .hdrReconstruction: "HDR reconstruction"
        case .metalFx: "MetalFX"
        case .uiSeed: "UI seed"
        case .ui: "UI draw"
        case .present: "present"
        case .actualHdrDisplay: "actual-radiance HDR display mapping"
        case .lightUploadClusterBuild: "light upload + cluster build"
        case .sunShadow: "sun shadow"
        case .voxelUploadUpdate: "voxel upload + update"
        case .dynamicLocalShadow: "dynamic local shadow"
        }
    }

    static func fromJavaId(_ value: Int32) -> MetallumGpuTimingStage? {
        value >= 0 ? MetallumGpuTimingStage(rawValue: Int(value)) : nil
    }
}

private struct MetallumGpuTimingEvent {
    let stage: MetallumGpuTimingStage
    let startIndex: Int
    let endIndex: Int
}

private struct MetallumGpuTimingSnapshot {
    let stageNanoseconds: [Double?]
    let droppedEvents: Int
}

private struct MetallumGpuTimingCompletion {
    let frame: MetallumGpuCounterFrame?
    let presentsDrawable: Bool
    let benchmarkContext: MetallumBenchmarkTelemetryContext?
    let workloadWindowKey: MetallumCpuWaitWindowKey?
    let presentation: MetallumPresentationTelemetry?
    let presentSubmissionUptime: Double?
}

private enum MetallumBenchmarkPhase: Int32 {
    case startup = 0
    case warmup = 1
    case measure = 2
    case complete = 3

    var reportName: String {
        switch self {
        case .startup: "startup"
        case .warmup: "warmup"
        case .measure: "measure"
        case .complete: "complete"
        }
    }
}

private struct MetallumBenchmarkTelemetryContext: Equatable {
    let generation: UInt64
    let enabled: Bool
    let segmentIndex: Int
    let phase: MetallumBenchmarkPhase
    let scalerMode: String

    var report: [String: Any] {
        [
            "enabled": enabled,
            "generation": generation,
            "segment_index": segmentIndex,
            "phase": phase.reportName,
            "scaler_mode": scalerMode
        ]
    }
}

private final class MetallumBenchmarkTelemetryState: @unchecked Sendable {
    private let lock = NSLock()
    private var context = MetallumBenchmarkTelemetryContext(
        generation: 0,
        enabled: ProcessInfo.processInfo.environment["METALLUM_BENCHMARK"] == "1",
        segmentIndex: -1,
        phase: .startup,
        scalerMode: "UNKNOWN"
    )

    func update(segmentIndex: Int32, phaseValue: Int32, scalerMode: String) {
        guard let phase = MetallumBenchmarkPhase(rawValue: phaseValue) else { return }
        lock.lock()
        context = MetallumBenchmarkTelemetryContext(
            generation: context.generation &+ 1,
            enabled: true,
            segmentIndex: Int(segmentIndex),
            phase: phase,
            scalerMode: scalerMode
        )
        lock.unlock()
    }

    func snapshot() -> MetallumBenchmarkTelemetryContext {
        lock.lock()
        let value = context
        lock.unlock()
        return value
    }
}

private struct MetallumPresentationTelemetry {
    let device: MTLDevice
    let deviceName: String
    let registryId: UInt64
    let renderWidth: Int
    let renderHeight: Int
    let displayWidth: Int
    let displayHeight: Int
    let outputMode: Int32
    let sourceEncoding: Int32
    let diagnosticPattern: Bool
    let hdrStrength: Float
    let bloomStrength: Float
    let currentHeadroom: Float
    let displaySyncEnabled: Bool

    var report: [String: Any] {
        let outputModeName = switch outputMode {
        case 0: "SDR"
        case 1: "EDR"
        case 2: "ENHANCED"
        default: "UNKNOWN"
        }
        let sourceEncodingName = switch sourceEncoding {
        case 0: "SRGB"
        case 1: "EXTENDED_SRGB"
        case 2: "LINEAR"
        default: "UNKNOWN"
        }
        var report: [String: Any] = [
            "device_name": deviceName,
            "registry_id": String(registryId),
            // Read dynamic memory gauges when the report is emitted so they
            // describe the same report-end state as the heap snapshot.
            "device_current_allocated_bytes": device.currentAllocatedSize,
            "device_recommended_max_working_set_bytes": device.recommendedMaxWorkingSetSize,
            "executor": "METAL3",
            "render_width": renderWidth,
            "render_height": renderHeight,
            "display_width": displayWidth,
            "display_height": displayHeight,
            "scaler_active": renderWidth != displayWidth || renderHeight != displayHeight,
            "hdr_output_mode": outputModeName,
            "source_encoding": sourceEncodingName,
            "diagnostic_pattern": diagnosticPattern,
            "hdr_strength": hdrStrength,
            "bloom_strength": bloomStrength,
            "current_edr_headroom": currentHeadroom,
            "display_sync_enabled": displaySyncEnabled
        ]
        if let shaderState = existingBuiltinShaderState(device: device)?.snapshot() {
            report["native_shader_library_mode"] = shaderState.mode.rawValue
            report["native_shader_source_compile_count"] = shaderState.sourceCompileCount
            report["native_shader_library_load_ms"] = shaderState.libraryLoadMilliseconds
            report["native_shader_source_compile_ms"] = shaderState.sourceCompileMilliseconds
            report["native_pipeline_warmup_ms"] = shaderState.pipelineWarmupMilliseconds
            report["native_pipeline_count"] = shaderState.pipelineCount
            report["native_pipeline_failure_count"] = shaderState.pipelineFailureCount
            report["native_pipeline_cache_hits"] = shaderState.pipelineCacheHitCount
            report["native_pipeline_cache_misses"] = shaderState.pipelineCacheMissCount
            report["native_pipeline_creations_after_warmup"] = shaderState.pipelineCreationsAfterWarmup
        }
        return report
    }
}

private enum MetallumCpuWaitKind: Int, CaseIterable {
    case nextDrawable = 0
    case frameSemaphore = 1
    case commandBufferCompletion = 2

    var reportName: String {
        switch self {
        case .nextDrawable: "nextDrawable wait (CPU)"
        case .frameSemaphore: "in-flight semaphore wait (CPU)"
        case .commandBufferCompletion: "command completion wait (CPU)"
        }
    }
}

private final class MetallumGpuCounterFrame: @unchecked Sendable {
    static let sampleCapacity = 512

    let sampleBuffer: MTLCounterSampleBuffer
    let markerBuffer: MTLBuffer
    private let device: MTLDevice
    private let startCpuTimestamp: UInt64
    private let startGpuTimestamp: UInt64
    private var nextSampleIndex = 0
    private var events: [MetallumGpuTimingEvent] = []
    private(set) var droppedEvents = 0

    init?(device: MTLDevice, counterSet: MTLCounterSet, markerBuffer: MTLBuffer) {
        let descriptor = MTLCounterSampleBufferDescriptor()
        descriptor.counterSet = counterSet
        descriptor.label = "Metallum per-stage GPU timestamps"
        descriptor.storageMode = .shared
        descriptor.sampleCount = Self.sampleCapacity
        do {
            self.sampleBuffer = try device.makeCounterSampleBuffer(descriptor: descriptor)
        } catch {
            NSLog("[metallum] Failed to create GPU timestamp sample buffer: %@", String(describing: error))
            return nil
        }
        self.device = device
        self.markerBuffer = markerBuffer
        let start = device.sampleTimestamps()
        self.startCpuTimestamp = UInt64(start.cpu)
        self.startGpuTimestamp = UInt64(start.gpu)
    }

    func allocateEvent(_ stage: MetallumGpuTimingStage) -> MetallumGpuTimingEvent? {
        guard nextSampleIndex + 2 <= Self.sampleCapacity else {
            droppedEvents += 1
            return nil
        }
        let event = MetallumGpuTimingEvent(
            stage: stage,
            startIndex: nextSampleIndex,
            endIndex: nextSampleIndex + 1
        )
        nextSampleIndex += 2
        events.append(event)
        return event
    }

    func attachRender(_ descriptor: MTLRenderPassDescriptor, stage: MetallumGpuTimingStage) {
        guard let event = allocateEvent(stage) else { return }
        let attachment = descriptor.sampleBufferAttachments[0]!
        attachment.sampleBuffer = sampleBuffer
        attachment.startOfVertexSampleIndex = event.startIndex
        attachment.endOfVertexSampleIndex = MTLCounterDontSample
        attachment.startOfFragmentSampleIndex = MTLCounterDontSample
        attachment.endOfFragmentSampleIndex = event.endIndex
    }

    func attachCompute(_ descriptor: MTLComputePassDescriptor, stage: MetallumGpuTimingStage) {
        guard let event = allocateEvent(stage) else { return }
        let attachment = descriptor.sampleBufferAttachments[0]!
        attachment.sampleBuffer = sampleBuffer
        attachment.startOfEncoderSampleIndex = event.startIndex
        attachment.endOfEncoderSampleIndex = event.endIndex
    }

    func attachBlit(_ descriptor: MTLBlitPassDescriptor, stage: MetallumGpuTimingStage) {
        guard let event = allocateEvent(stage) else { return }
        let attachment = descriptor.sampleBufferAttachments[0]!
        attachment.sampleBuffer = sampleBuffer
        attachment.startOfEncoderSampleIndex = event.startIndex
        attachment.endOfEncoderSampleIndex = event.endIndex
    }

    func allocateExternalEvent(_ stage: MetallumGpuTimingStage) -> MetallumGpuTimingEvent? {
        allocateEvent(stage)
    }

    func attachExternalStart(_ descriptor: MTLBlitPassDescriptor, event: MetallumGpuTimingEvent) {
        let attachment = descriptor.sampleBufferAttachments[0]!
        attachment.sampleBuffer = sampleBuffer
        attachment.startOfEncoderSampleIndex = MTLCounterDontSample
        attachment.endOfEncoderSampleIndex = event.startIndex
    }

    func attachExternalEnd(_ descriptor: MTLBlitPassDescriptor, event: MetallumGpuTimingEvent) {
        let attachment = descriptor.sampleBufferAttachments[0]!
        attachment.sampleBuffer = sampleBuffer
        attachment.startOfEncoderSampleIndex = event.endIndex
        attachment.endOfEncoderSampleIndex = MTLCounterDontSample
    }

    func resolve() -> MetallumGpuTimingSnapshot? {
        guard nextSampleIndex > 0 else {
            return MetallumGpuTimingSnapshot(
                stageNanoseconds: Array(repeating: nil, count: MetallumGpuTimingStage.allCases.count),
                droppedEvents: droppedEvents
            )
        }
        let final = device.sampleTimestamps()
        let finalCpu = UInt64(final.cpu)
        let finalGpu = UInt64(final.gpu)
        guard finalCpu > startCpuTimestamp, finalGpu > startGpuTimestamp else {
            return nil
        }
        guard let data = try? sampleBuffer.resolveCounterRange(0..<nextSampleIndex),
              data.count >= nextSampleIndex * MemoryLayout<MTLCounterResultTimestamp>.stride else {
            return nil
        }
        let cpuSpan = Double(finalCpu - startCpuTimestamp)
        let gpuSpan = Double(finalGpu - startGpuTimestamp)
        guard cpuSpan.isFinite, gpuSpan.isFinite, cpuSpan > 0.0, gpuSpan > 0.0 else {
            return nil
        }

        var totals = Array<Double?>(repeating: nil, count: MetallumGpuTimingStage.allCases.count)
        data.withUnsafeBytes { rawBytes in
            let timestamps = rawBytes.bindMemory(to: MTLCounterResultTimestamp.self)
            for event in events where event.startIndex < timestamps.count && event.endIndex < timestamps.count {
                let start = timestamps[event.startIndex].timestamp
                let end = timestamps[event.endIndex].timestamp
                guard start != 0,
                      end != 0,
                      start != MTLCounterErrorValue,
                      end != MTLCounterErrorValue,
                      end >= start else {
                    continue
                }
                let nanoseconds = Double(end - start) / gpuSpan * cpuSpan
                guard nanoseconds.isFinite, nanoseconds >= 0.0 else { continue }
                totals[event.stage.rawValue] = (totals[event.stage.rawValue] ?? 0.0) + nanoseconds
            }
        }
        return MetallumGpuTimingSnapshot(stageNanoseconds: totals, droppedEvents: droppedEvents)
    }
}

private final class MetallumGpuTimingCoordinator: @unchecked Sendable {
    static let shared = MetallumGpuTimingCoordinator()
    private static let retainedPresentationGenerations = 8

    private let lock = NSLock()
    private var frames: [UInt: MetallumGpuCounterFrame] = [:]
    private var presentFlags: [UInt: Bool] = [:]
    private var benchmarkContexts: [UInt: MetallumBenchmarkTelemetryContext] = [:]
    private var presentationsByGeneration: [UInt64: MetallumPresentationTelemetry] = [:]
    private var presentationGenerationOrder: [UInt64] = []
    private var presentSubmissionUptimes: [UInt: Double] = [:]
    private var counterSets: [UInt: MTLCounterSet] = [:]
    private var unsupportedDevices: Set<UInt> = []

    func register(_ commandBuffer: MTLCommandBuffer) {
        guard let stats = NativeState.gpuTimingStats else { return }
        let device = commandBuffer.device
        let deviceKey = objectAddress(device)
        let commandBufferKey = objectAddress(commandBuffer)
        let benchmarkContext = NativeState.benchmarkTelemetryState.snapshot()
        stats.recordCommandBuffer(commandBuffer, context: benchmarkContext)

        lock.lock()
        presentFlags[commandBufferKey] = false
        benchmarkContexts[commandBufferKey] = benchmarkContext
        lock.unlock()

        // The default timing mode records only command-buffer GPU time and
        // presented throughput. Counter attachments and the marker encoders
        // required to bracket opaque MetalFX work are explicitly opt-in so
        // METALLUM_GPU_TIMING=1 preserves the production frame graph.
        guard NativeState.gpuTimingDetailEnabled else { return }

        lock.lock()
        if unsupportedDevices.contains(deviceKey) {
            lock.unlock()
            return
        }
        var counterSet = counterSets[deviceKey]
        lock.unlock()

        let discoveredNow = counterSet == nil
        if counterSet == nil {
            guard device.supportsCounterSampling(.atStageBoundary),
                  let discovered = device.counterSets?.first(where: { set in
                      set.name == MTLCommonCounterSet.timestamp.rawValue
                          && set.counters.contains(where: { $0.name == MTLCommonCounter.timestamp.rawValue })
                  }) else {
                markUnsupported(deviceKey, reason: "timestamp counter set or stage-boundary sampling is unavailable")
                return
            }
            counterSet = discovered
        }
        guard let markerBuffer = device.makeBuffer(
                  length: MemoryLayout<UInt32>.stride,
                  options: .storageModePrivate
              ), let counterSet,
              let frame = MetallumGpuCounterFrame(
                device: device,
                counterSet: counterSet,
                markerBuffer: markerBuffer
              ) else {
            markUnsupported(deviceKey, reason: "failed to allocate counter resources")
            return
        }
        markerBuffer.label = "Metallum per-frame GPU timing marker"

        lock.lock()
        counterSets[deviceKey] = counterSet
        frames[commandBufferKey] = frame
        lock.unlock()
        if discoveredNow {
            NSLog(
                "[metallum] Per-stage GPU timing enabled: timestamp counter set, %d samples per command buffer",
                MetallumGpuCounterFrame.sampleCapacity
            )
        }
    }

    func frame(for commandBuffer: MTLCommandBuffer) -> MetallumGpuCounterFrame? {
        guard NativeState.gpuTimingDetailEnabled else { return nil }
        lock.lock()
        let frame = frames[objectAddress(commandBuffer)]
        lock.unlock()
        return frame
    }

    func markPresented(
        _ commandBuffer: MTLCommandBuffer,
        renderWidth: Int,
        renderHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        outputMode: Int32,
        sourceEncoding: Int32,
        diagnosticPattern: Bool,
        hdrStrength: Float,
        bloomStrength: Float,
        currentHeadroom: Float,
        displaySyncEnabled: Bool
    ) {
        guard NativeState.gpuTimingStats != nil else { return }
        let key = objectAddress(commandBuffer)
        var presentedContext: MetallumBenchmarkTelemetryContext?
        lock.lock()
        if presentFlags[key] == false {
            presentFlags[key] = true
            presentSubmissionUptimes[key] = ProcessInfo.processInfo.systemUptime
            presentedContext = benchmarkContexts[key]
        }
        lock.unlock()
        if let presentedContext,
           let stats = NativeState.gpuTimingStats {
            let presentedCount = stats.notePresented(presentedContext)
            if (presentedCount - 1) % MetallumGpuTimingStats.reportFrameCount == 0 {
                let presentation = MetallumPresentationTelemetry(
                    device: commandBuffer.device,
                    deviceName: commandBuffer.device.name,
                    registryId: commandBuffer.device.registryID,
                    renderWidth: renderWidth,
                    renderHeight: renderHeight,
                    displayWidth: displayWidth,
                    displayHeight: displayHeight,
                    outputMode: outputMode,
                    sourceEncoding: sourceEncoding,
                    diagnosticPattern: diagnosticPattern,
                    hdrStrength: hdrStrength,
                    bloomStrength: bloomStrength,
                    currentHeadroom: currentHeadroom,
                    displaySyncEnabled: displaySyncEnabled
                )
                lock.lock()
                if presentationsByGeneration[presentedContext.generation] == nil {
                    presentationGenerationOrder.append(presentedContext.generation)
                }
                presentationsByGeneration[presentedContext.generation] = presentation
                while presentationGenerationOrder.count > Self.retainedPresentationGenerations {
                    let staleGeneration = presentationGenerationOrder.removeFirst()
                    presentationsByGeneration.removeValue(forKey: staleGeneration)
                }
                lock.unlock()
            }
        }
    }

    func take(_ commandBuffer: MTLCommandBuffer) -> MetallumGpuTimingCompletion {
        guard let stats = NativeState.gpuTimingStats else {
            return MetallumGpuTimingCompletion(
                frame: nil,
                presentsDrawable: false,
                benchmarkContext: nil,
                workloadWindowKey: nil,
                presentation: nil,
                presentSubmissionUptime: nil
            )
        }
        let key = objectAddress(commandBuffer)
        lock.lock()
        let frame = frames.removeValue(forKey: key)
        let presentsDrawable = presentFlags.removeValue(forKey: key) ?? false
        let benchmarkContext = benchmarkContexts.removeValue(forKey: key)
        let presentation = benchmarkContext.flatMap {
            presentationsByGeneration[$0.generation]
        }
        let presentSubmissionUptime = presentSubmissionUptimes.removeValue(forKey: key)
        lock.unlock()
        let workloadWindowKey = stats.finishCommandBuffer(commandBuffer)
        return MetallumGpuTimingCompletion(
            frame: frame,
            presentsDrawable: presentsDrawable,
            benchmarkContext: benchmarkContext,
            workloadWindowKey: workloadWindowKey,
            presentation: presentation,
            presentSubmissionUptime: presentSubmissionUptime
        )
    }

    func abandon(_ commandBuffer: MTLCommandBuffer) {
        _ = take(commandBuffer)
    }

    private func markUnsupported(_ deviceKey: UInt, reason: String) {
        lock.lock()
        let inserted = unsupportedDevices.insert(deviceKey).inserted
        lock.unlock()
        if inserted {
            NSLog("[metallum] Per-stage GPU timing disabled: %@", reason)
        }
    }
}

private struct MetallumCpuWaitWindowKey: Hashable {
    let generation: UInt64
    let reportIndex: Int
}

private struct MetallumCpuWaitToken {
    let windowKey: MetallumCpuWaitWindowKey
}

private final class MetallumCpuWaitAccumulator {
    var nanoseconds = Array(repeating: 0.0, count: MetallumCpuWaitKind.allCases.count)
    var maximumNanoseconds = Array(repeating: 0.0, count: MetallumCpuWaitKind.allCases.count)
    var counts = Array(repeating: 0, count: MetallumCpuWaitKind.allCases.count)

    func record(_ kind: MetallumCpuWaitKind, nanoseconds value: UInt64) {
        let value = Double(value)
        nanoseconds[kind.rawValue] += value
        maximumNanoseconds[kind.rawValue] = max(maximumNanoseconds[kind.rawValue], value)
        counts[kind.rawValue] += 1
    }
}

private enum MetallumWorkloadEncoderKind {
    case render
    case compute
    case blit
}

private enum MetallumWorkloadCopyKind {
    case sharedToPrivate
    case gpuToCpu
    case gpuInternal
    case unclassified
}

private final class MetallumWorkloadAccumulator {
    var commandBuffers = 0
    var renderEncoders = 0
    var computeEncoders = 0
    var blitEncoders = 0
    var passBoundaries = 0

    // Direct CPU writes happen outside the four native blit wrappers. Java
    // reports only writes it actually performs; never infer them from shared
    // allocations because an allocation is not itself a write.
    var cpuToSharedBytes = 0
    var cpuToSharedCommands = 0
    var sharedToPrivateBytes = 0
    var sharedToPrivateCommands = 0
    var gpuToCpuBytes = 0
    var gpuToCpuCommands = 0
    var gpuInternalBytes = 0
    var gpuInternalCommands = 0
    var unclassifiedBytes = 0
    var unclassifiedCommands = 0
    var byteCountUnknownCommands = 0
    var directWriteObserved = false

    var bufferAllocationCount = 0
    var bufferAllocationBytes = 0
    var textureAllocationCount = 0
    var textureAllocationBytes = 0

    var cpuTransientRequestedHighWaterBytes = 0
    var cpuTransientReservedHighWaterBytes = 0
    var gpuSharedTransientRequestedHighWaterBytes = 0
    var gpuSharedTransientReservedHighWaterBytes = 0
    var cpuRenderSubmissionNanoseconds: [UInt64] = []

    func recordEncoder(_ kind: MetallumWorkloadEncoderKind) {
        switch kind {
        case .render:
            renderEncoders += 1
        case .compute:
            computeEncoders += 1
        case .blit:
            blitEncoders += 1
        }
        // A workload pass boundary is defined as one successful encoder
        // start, so it is exactly render + compute + blit for every report.
        passBoundaries += 1
    }

    func recordCopy(_ kind: MetallumWorkloadCopyKind, bytes: Int?) {
        switch kind {
        case .sharedToPrivate:
            sharedToPrivateCommands += 1
            if let bytes {
                sharedToPrivateBytes += bytes
            }
        case .gpuToCpu:
            gpuToCpuCommands += 1
            if let bytes {
                gpuToCpuBytes += bytes
            }
        case .gpuInternal:
            gpuInternalCommands += 1
            if let bytes {
                gpuInternalBytes += bytes
            }
        case .unclassified:
            unclassifiedCommands += 1
            if let bytes {
                unclassifiedBytes += bytes
            }
        }
        if bytes == nil {
            byteCountUnknownCommands += 1
        }
    }

    var report: [String: Any] {
        [
            "command_buffers": commandBuffers,
            "encoders": [
                "render": renderEncoders,
                "compute": computeEncoders,
                "blit": blitEncoders,
                "pass_boundaries": passBoundaries
            ],
            "copy_bytes": [
                "cpu_to_shared": cpuToSharedBytes,
                "shared_to_private": sharedToPrivateBytes,
                "gpu_to_cpu": gpuToCpuBytes,
                "gpu_internal": gpuInternalBytes,
                "unclassified": unclassifiedBytes,
                "cpu_to_shared_commands": cpuToSharedCommands,
                "shared_to_private_commands": sharedToPrivateCommands,
                "gpu_to_cpu_commands": gpuToCpuCommands,
                "gpu_internal_commands": gpuInternalCommands,
                "unclassified_commands": unclassifiedCommands,
                "byte_count_unknown_commands": byteCountUnknownCommands,
                "direct_write_observed": directWriteObserved
            ],
            "resource_allocations": [
                "buffers": [
                    "count": bufferAllocationCount,
                    "bytes": bufferAllocationBytes
                ],
                "textures": [
                    "count": textureAllocationCount,
                    "bytes": textureAllocationBytes
                ]
            ],
            "transient_memory": [
                "cpu": [
                    "requested_high_water_bytes": cpuTransientRequestedHighWaterBytes,
                    "reserved_high_water_bytes": cpuTransientReservedHighWaterBytes
                ],
                "gpu_shared": [
                    "requested_high_water_bytes": gpuSharedTransientRequestedHighWaterBytes,
                    "reserved_high_water_bytes": gpuSharedTransientReservedHighWaterBytes
                ]
            ]
        ]
    }
}

private struct MetallumGpuTimingReportWindow {
    let context: MetallumBenchmarkTelemetryContext
    let sampleCount: Int
    let totalGpuSeconds: Double
    let maximumGpuSeconds: Double
    let gpuSecondSamples: [Double]
    let presentIntervalCount: Int
    let totalPresentIntervalSeconds: Double
    let presentIntervalSamples: [Double]
    let stageTotals: [Double]
    let stageMaximums: [Double]
    let stageCounts: [Int]
    let stageSamples: [[Double]]
    let waitNanoseconds: [Double]
    let maximumWaitNanoseconds: [Double]
    let waitCounts: [Int]
    let workload: MetallumWorkloadAccumulator
    let droppedEvents: Int
    let presentation: MetallumPresentationTelemetry?
}

private final class MetallumGpuTimingWindow {
    let context: MetallumBenchmarkTelemetryContext
    var reportIndex = 0
    var sampleCount = 0
    var totalGpuSeconds = 0.0
    var maximumGpuSeconds = 0.0
    var gpuSecondSamples = Array(repeating: 0.0, count: 300)
    var presentIntervalCount = 0
    var totalPresentIntervalSeconds = 0.0
    var presentIntervalSamples = Array(repeating: 0.0, count: 300)
    var previousPresentSubmissionUptime: Double?
    var stageTotals = Array(repeating: 0.0, count: MetallumGpuTimingStage.allCases.count)
    var stageMaximums = Array(repeating: 0.0, count: MetallumGpuTimingStage.allCases.count)
    var stageCounts = Array(repeating: 0, count: MetallumGpuTimingStage.allCases.count)
    var stageSamples = Array(
        repeating: [Double](),
        count: MetallumGpuTimingStage.allCases.count
    )
    var droppedEvents = 0
    var presentation: MetallumPresentationTelemetry?

    init(context: MetallumBenchmarkTelemetryContext) {
        self.context = context
    }

    func takeReport(
        cpuWaits: MetallumCpuWaitAccumulator?,
        workload: MetallumWorkloadAccumulator
    ) -> MetallumGpuTimingReportWindow {
        let report = MetallumGpuTimingReportWindow(
            context: context,
            sampleCount: sampleCount,
            totalGpuSeconds: totalGpuSeconds,
            maximumGpuSeconds: maximumGpuSeconds,
            gpuSecondSamples: gpuSecondSamples,
            presentIntervalCount: presentIntervalCount,
            totalPresentIntervalSeconds: totalPresentIntervalSeconds,
            presentIntervalSamples: presentIntervalSamples,
            stageTotals: stageTotals,
            stageMaximums: stageMaximums,
            stageCounts: stageCounts,
            stageSamples: stageSamples,
            waitNanoseconds: cpuWaits?.nanoseconds
                ?? Array(repeating: 0.0, count: MetallumCpuWaitKind.allCases.count),
            maximumWaitNanoseconds: cpuWaits?.maximumNanoseconds
                ?? Array(repeating: 0.0, count: MetallumCpuWaitKind.allCases.count),
            waitCounts: cpuWaits?.counts
                ?? Array(repeating: 0, count: MetallumCpuWaitKind.allCases.count),
            workload: workload,
            droppedEvents: droppedEvents,
            presentation: presentation
        )
        sampleCount = 0
        totalGpuSeconds = 0.0
        maximumGpuSeconds = 0.0
        gpuSecondSamples = Array(repeating: 0.0, count: gpuSecondSamples.count)
        presentIntervalCount = 0
        totalPresentIntervalSeconds = 0.0
        presentIntervalSamples = Array(repeating: 0.0, count: presentIntervalSamples.count)
        stageTotals = Array(repeating: 0.0, count: stageTotals.count)
        stageMaximums = Array(repeating: 0.0, count: stageMaximums.count)
        stageCounts = Array(repeating: 0, count: stageCounts.count)
        stageSamples = Array(repeating: [Double](), count: stageSamples.count)
        droppedEvents = 0
        reportIndex += 1
        return report
    }
}

private final class MetallumGpuTimingStats: @unchecked Sendable {
    static let reportFrameCount = 300
    private static let retainedBenchmarkGenerations = 8

    private let lock = NSLock()
    private let reportLock = NSLock()
    private var windows: [UInt64: MetallumGpuTimingWindow] = [:]
    private var presentedCounts: [UInt64: Int] = [:]
    private var cpuWaitWindows: [MetallumCpuWaitWindowKey: MetallumCpuWaitAccumulator] = [:]
    private var workloadWindows: [MetallumCpuWaitWindowKey: MetallumWorkloadAccumulator] = [:]
    private var commandBufferWindowKeys: [UInt: MetallumCpuWaitWindowKey] = [:]
    private var encoderWindowKeys: [UInt: MetallumCpuWaitWindowKey] = [:]
    private var generationOrder: [UInt64] = []

    private func ensureGenerationLocked(_ generation: UInt64) {
        guard presentedCounts[generation] == nil else { return }
        presentedCounts[generation] = 0
        generationOrder.append(generation)
        while generationOrder.count > Self.retainedBenchmarkGenerations {
            let staleGeneration = generationOrder.removeFirst()
            presentedCounts.removeValue(forKey: staleGeneration)
            windows.removeValue(forKey: staleGeneration)
            cpuWaitWindows = cpuWaitWindows.filter {
                $0.key.generation != staleGeneration
            }
            workloadWindows = workloadWindows.filter {
                $0.key.generation != staleGeneration
            }
            commandBufferWindowKeys = commandBufferWindowKeys.filter {
                $0.value.generation != staleGeneration
            }
            encoderWindowKeys = encoderWindowKeys.filter {
                $0.value.generation != staleGeneration
            }
        }
    }

    private func currentWindowKeyLocked(
        for context: MetallumBenchmarkTelemetryContext
    ) -> MetallumCpuWaitWindowKey {
        ensureGenerationLocked(context.generation)
        return MetallumCpuWaitWindowKey(
            generation: context.generation,
            reportIndex: presentedCounts[context.generation]! / Self.reportFrameCount
        )
    }

    private func workloadLocked(
        for key: MetallumCpuWaitWindowKey
    ) -> MetallumWorkloadAccumulator {
        if let existing = workloadWindows[key] {
            return existing
        }
        let created = MetallumWorkloadAccumulator()
        workloadWindows[key] = created
        return created
    }

    private func window(for context: MetallumBenchmarkTelemetryContext) -> MetallumGpuTimingWindow {
        if let existing = windows[context.generation] {
            return existing
        }
        let created = MetallumGpuTimingWindow(context: context)
        windows[context.generation] = created
        return created
    }

    private static func percentile(_ sortedValues: [Double], fraction: Double) -> Double {
        guard !sortedValues.isEmpty else { return 0.0 }
        let index = min(
            max(Int(ceil(fraction * Double(sortedValues.count))) - 1, 0),
            sortedValues.count - 1
        )
        return sortedValues[index]
    }

    private static func lowFps(_ sortedIntervals: [Double], fraction: Double) -> Double {
        guard !sortedIntervals.isEmpty else { return 0.0 }
        let count = min(max(Int(ceil(Double(sortedIntervals.count) * fraction)), 1), sortedIntervals.count)
        let total = sortedIntervals.suffix(count).reduce(0.0, +)
        return total > 0.0 ? Double(count) / total : 0.0
    }

    func notePresented(_ context: MetallumBenchmarkTelemetryContext) -> Int {
        lock.lock()
        ensureGenerationLocked(context.generation)
        let presentedCount = presentedCounts[context.generation]! + 1
        presentedCounts[context.generation] = presentedCount
        lock.unlock()
        return presentedCount
    }

    func recordCommandBuffer(
        _ commandBuffer: MTLCommandBuffer,
        context: MetallumBenchmarkTelemetryContext
    ) {
        lock.lock()
        let key = currentWindowKeyLocked(for: context)
        commandBufferWindowKeys[objectAddress(commandBuffer)] = key
        workloadLocked(for: key).commandBuffers += 1
        lock.unlock()
    }

    func finishCommandBuffer(
        _ commandBuffer: MTLCommandBuffer
    ) -> MetallumCpuWaitWindowKey? {
        lock.lock()
        let key = commandBufferWindowKeys.removeValue(forKey: objectAddress(commandBuffer))
        lock.unlock()
        return key
    }

    func recordEncoder(
        _ encoder: MTLCommandEncoder,
        commandBuffer: MTLCommandBuffer,
        kind: MetallumWorkloadEncoderKind
    ) {
        lock.lock()
        if let key = commandBufferWindowKeys[objectAddress(commandBuffer)] {
            encoderWindowKeys[objectAddress(encoder)] = key
            workloadLocked(for: key).recordEncoder(kind)
        }
        lock.unlock()
    }

    func finishEncoder(_ encoder: MTLCommandEncoder) {
        lock.lock()
        encoderWindowKeys.removeValue(forKey: objectAddress(encoder))
        lock.unlock()
    }

    func recordCopy(
        _ encoder: MTLBlitCommandEncoder,
        sourceStorageMode: MTLStorageMode,
        destinationStorageMode: MTLStorageMode,
        bytes: Int?
    ) {
        let kind: MetallumWorkloadCopyKind
        let sourceIsCpuVisible = sourceStorageMode == .shared || sourceStorageMode == .managed
        let destinationIsCpuVisible = destinationStorageMode == .shared || destinationStorageMode == .managed
        if sourceIsCpuVisible && destinationStorageMode == .private {
            kind = .sharedToPrivate
        } else if sourceStorageMode == .private && destinationIsCpuVisible {
            kind = .gpuToCpu
        } else if sourceStorageMode == .private && destinationStorageMode == .private {
            kind = .gpuInternal
        } else {
            kind = .unclassified
        }

        lock.lock()
        if let key = encoderWindowKeys[objectAddress(encoder)] {
            workloadLocked(for: key).recordCopy(kind, bytes: bytes)
        }
        lock.unlock()
    }

    func recordJavaWorkload(
        _ commandBuffer: MTLCommandBuffer,
        cpuBytes: UInt64,
        cpuOperations: UInt64,
        cpuRequestedHighWater: UInt64,
        cpuReservedHighWater: UInt64,
        gpuRequestedHighWater: UInt64,
        gpuReservedHighWater: UInt64,
        cpuRenderSubmissionNanos: UInt64
    ) {
        guard cpuReservedHighWater >= cpuRequestedHighWater,
              gpuReservedHighWater >= gpuRequestedHighWater else {
            NSLog(
                "[metallum] Java workload telemetry invalid: reserved bytes are smaller than requested bytes"
            )
            return
        }
        guard let cpuByteCount = Int(exactly: cpuBytes),
              let cpuOperationCount = Int(exactly: cpuOperations),
              let cpuRequested = Int(exactly: cpuRequestedHighWater),
              let cpuReserved = Int(exactly: cpuReservedHighWater),
              let gpuRequested = Int(exactly: gpuRequestedHighWater),
              let gpuReserved = Int(exactly: gpuReservedHighWater) else {
            NSLog(
                "[metallum] Java workload telemetry invalid: UInt64 value exceeds the native counter range"
            )
            return
        }

        lock.lock()
        guard let key = commandBufferWindowKeys[objectAddress(commandBuffer)] else {
            lock.unlock()
            NSLog(
                "[metallum] Java workload telemetry invalid: command buffer window key is missing"
            )
            return
        }
        let accumulator = workloadLocked(for: key)
        let (nextCpuBytes, cpuBytesOverflow) = accumulator.cpuToSharedBytes
            .addingReportingOverflow(cpuByteCount)
        let (nextCpuOperations, cpuOperationsOverflow) = accumulator.cpuToSharedCommands
            .addingReportingOverflow(cpuOperationCount)
        guard !cpuBytesOverflow, !cpuOperationsOverflow else {
            lock.unlock()
            NSLog(
                "[metallum] Java workload telemetry invalid: accumulated CPU direct-write counter overflow"
            )
            return
        }

        accumulator.cpuToSharedBytes = nextCpuBytes
        accumulator.cpuToSharedCommands = nextCpuOperations
        // Reaching this hook means Java observed the submit even when its exact
        // count is zero; keep zero distinct from legacy/uninstrumented data.
        accumulator.directWriteObserved = true
        accumulator.cpuTransientRequestedHighWaterBytes = max(
            accumulator.cpuTransientRequestedHighWaterBytes,
            cpuRequested
        )
        accumulator.cpuTransientReservedHighWaterBytes = max(
            accumulator.cpuTransientReservedHighWaterBytes,
            cpuReserved
        )
        accumulator.gpuSharedTransientRequestedHighWaterBytes = max(
            accumulator.gpuSharedTransientRequestedHighWaterBytes,
            gpuRequested
        )
        accumulator.gpuSharedTransientReservedHighWaterBytes = max(
            accumulator.gpuSharedTransientReservedHighWaterBytes,
            gpuReserved
        )
        accumulator.cpuRenderSubmissionNanoseconds.append(cpuRenderSubmissionNanos)
        lock.unlock()
    }

    func recordBufferAllocation(_ allocatedSize: Int) {
        let context = NativeState.benchmarkTelemetryState.snapshot()
        lock.lock()
        let accumulator = workloadLocked(for: currentWindowKeyLocked(for: context))
        accumulator.bufferAllocationCount += 1
        accumulator.bufferAllocationBytes += allocatedSize
        lock.unlock()
    }

    func recordTextureAllocation(_ allocatedSize: Int) {
        let context = NativeState.benchmarkTelemetryState.snapshot()
        lock.lock()
        let accumulator = workloadLocked(for: currentWindowKeyLocked(for: context))
        accumulator.textureAllocationCount += 1
        accumulator.textureAllocationBytes += allocatedSize
        lock.unlock()
    }

    private static func thermalStateName() -> String {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: "nominal"
        case .fair: "fair"
        case .serious: "serious"
        case .critical: "critical"
        @unknown default: "unknown"
        }
    }

    private func writeReport(
        _ window: MetallumGpuTimingReportWindow,
        fps: Double,
        gpuAverageMs: Double,
        gpuP50Ms: Double,
        gpuP95Ms: Double,
        gpuP99Ms: Double,
        gpuMaximumMs: Double,
        sortedPresentIntervals: [Double]
    ) {
        guard let writer = NativeState.gpuTimingReportWriter else { return }

        var stages: [String: Any] = [:]
        for stage in MetallumGpuTimingStage.allCases {
            let count = window.stageCounts[stage.rawValue]
            if count == 0 {
                stages[stage.reportName] = NSNull()
            } else {
                let samples = window.stageSamples[stage.rawValue].sorted()
                stages[stage.reportName] = [
                    "frames": count,
                    "average_ms": window.stageTotals[stage.rawValue]
                        / Double(count) / 1_000_000.0,
                    "p50_ms": Self.percentile(samples, fraction: 0.50) / 1_000_000.0,
                    "p95_ms": Self.percentile(samples, fraction: 0.95) / 1_000_000.0,
                    "p99_ms": Self.percentile(samples, fraction: 0.99) / 1_000_000.0,
                    "maximum_ms": window.stageMaximums[stage.rawValue] / 1_000_000.0
                ]
            }
        }

        var waits: [String: Any] = [:]
        for kind in MetallumCpuWaitKind.allCases {
            let count = window.waitCounts[kind.rawValue]
            waits[kind.reportName] = count == 0 ? NSNull() : [
                "waits": count,
                "average_ms_per_frame": window.waitNanoseconds[kind.rawValue]
                    / Double(window.sampleCount) / 1_000_000.0,
                "maximum_ms": window.maximumWaitNanoseconds[kind.rawValue] / 1_000_000.0
            ]
        }

        let presentIntervalReport: Any
        if sortedPresentIntervals.isEmpty {
            presentIntervalReport = NSNull()
        } else {
            presentIntervalReport = [
                "samples": sortedPresentIntervals.count,
                "average": window.totalPresentIntervalSeconds * 1_000.0
                    / Double(sortedPresentIntervals.count),
                "p50": Self.percentile(sortedPresentIntervals, fraction: 0.50) * 1_000.0,
                "p95": Self.percentile(sortedPresentIntervals, fraction: 0.95) * 1_000.0,
                "p99": Self.percentile(sortedPresentIntervals, fraction: 0.99) * 1_000.0,
                "maximum": (sortedPresentIntervals.last ?? 0.0) * 1_000.0
            ]
        }

        let sortedCpuRenderSubmission = window.workload.cpuRenderSubmissionNanoseconds.sorted()
        let sortedCpuRenderSubmissionDouble = sortedCpuRenderSubmission.map { Double($0) }
        let cpuRenderSubmissionReport: [String: Any] = [
            "samples": sortedCpuRenderSubmission.count,
            "average": sortedCpuRenderSubmission.isEmpty ? 0.0
                : Double(sortedCpuRenderSubmission.reduce(0, +))
                    / Double(sortedCpuRenderSubmission.count) / 1_000_000.0,
            "p50": Self.percentile(sortedCpuRenderSubmissionDouble, fraction: 0.50)
                / 1_000_000.0,
            "p95": Self.percentile(sortedCpuRenderSubmissionDouble, fraction: 0.95)
                / 1_000_000.0,
            "p99": Self.percentile(sortedCpuRenderSubmissionDouble, fraction: 0.99)
                / 1_000_000.0,
            "maximum": Double(sortedCpuRenderSubmission.last ?? 0) / 1_000_000.0
        ]

        let environment = ProcessInfo.processInfo.environment
        var metadata: [String: Any] = [
            "commit": environment["METALLUM_BENCHMARK_COMMIT"] ?? "unknown",
            "dirty_worktree": environment["METALLUM_BENCHMARK_DIRTY"] == "1",
            "source_sha256": environment["METALLUM_BENCHMARK_SOURCE_SHA256"] ?? "unknown",
            "artifact_sha256": environment["METALLUM_BENCHMARK_ARTIFACT_SHA256"] ?? "unknown",
            "settings_id": environment["METALLUM_BENCHMARK_SETTINGS_ID"] ?? "unknown",
            "settings_spec_sha256": environment["METALLUM_BENCHMARK_SETTINGS_SPEC_SHA256"] ?? "unknown",
            "settings_sha256": environment["METALLUM_BENCHMARK_SETTINGS_SHA256"] ?? "unknown",
            "render_distance": Int(environment["METALLUM_BENCHMARK_RENDER_DISTANCE"] ?? "") ?? -1,
            "simulation_distance": Int(environment["METALLUM_BENCHMARK_SIMULATION_DISTANCE"] ?? "") ?? -1,
            "graphics_preset": environment["METALLUM_BENCHMARK_GRAPHICS_PRESET"] ?? "unknown",
            "entity_distance_scaling": Double(environment["METALLUM_BENCHMARK_ENTITY_DISTANCE_SCALING"] ?? "") ?? -1.0,
            "particles": Int(environment["METALLUM_BENCHMARK_PARTICLES"] ?? "") ?? -1,
            "mipmap_levels": Int(environment["METALLUM_BENCHMARK_MIPMAP_LEVELS"] ?? "") ?? -1,
            "biome_blend_radius": Int(environment["METALLUM_BENCHMARK_BIOME_BLEND_RADIUS"] ?? "") ?? -1,
            "max_fps": Int(environment["METALLUM_BENCHMARK_MAX_FPS"] ?? "") ?? -1,
            "ambient_occlusion": environment["METALLUM_BENCHMARK_AO"] == "true",
            "clouds_mode": environment["METALLUM_BENCHMARK_CLOUDS_MODE"] ?? "unknown",
            "cloud_range": Int(environment["METALLUM_BENCHMARK_CLOUD_RANGE"] ?? "") ?? -1,
            "texture_filtering": Int(environment["METALLUM_BENCHMARK_TEXTURE_FILTERING"] ?? "") ?? -1,
            "max_anisotropy_bit": Int(environment["METALLUM_BENCHMARK_MAX_ANISOTROPY_BIT"] ?? "") ?? -1,
            "improved_transparency": environment["METALLUM_BENCHMARK_IMPROVED_TRANSPARENCY"] == "true",
            "resource_packs_sha256": environment["METALLUM_BENCHMARK_RESOURCE_PACKS_SHA256"] ?? "unknown",
            "sodium_settings_sha256": environment["METALLUM_BENCHMARK_SODIUM_SETTINGS_SHA256"] ?? "unknown",
            "configured_gui_scale": Int(environment["METALLUM_BENCHMARK_CONFIGURED_GUI_SCALE"] ?? "") ?? -1,
            "active_resource_pack_ids": environment["METALLUM_BENCHMARK_ACTIVE_RESOURCE_PACKS"] ?? "unknown",
            "sodium_chunk_builder_threads": Int(environment["METALLUM_BENCHMARK_SODIUM_WORKER_THREADS"] ?? "") ?? -1,
            "hdr_bloom_strength": Double(environment["METALLUM_BENCHMARK_HDR_BLOOM_STRENGTH"] ?? "") ?? -1.0,
            "hdr_strength": Double(environment["METALLUM_BENCHMARK_HDR_STRENGTH"] ?? "") ?? -1.0,
            "persistent_metalfx_mode": environment["METALLUM_BENCHMARK_PERSISTENT_METALFX_MODE"] ?? "unknown",
            "world": environment["METALLUM_BENCHMARK_WORLD"] ?? "unknown",
            "fixture": environment["METALLUM_BENCHMARK_FIXTURE_ID"] ?? "unknown",
            "fixture_sha256": environment["METALLUM_BENCHMARK_FIXTURE_SHA256"] ?? "unknown",
            "route": environment["METALLUM_BENCHMARK_ROUTE_ID"] ?? "unknown",
            "route_sha256": environment["METALLUM_BENCHMARK_ROUTE_SHA256"] ?? "unknown",
            "benchmark_player_name": environment["METALLUM_BENCHMARK_PLAYER_NAME"] ?? "unknown",
            "benchmark_player_uuid": environment["METALLUM_BENCHMARK_PLAYER_UUID"] ?? "unknown",
            "benchmark_dimension": environment["METALLUM_BENCHMARK_DIMENSION"] ?? "unknown",
            "benchmark_simulation_frozen": environment["METALLUM_BENCHMARK_SIMULATION_FROZEN"] == "1",
            "monitor": environment["METALLUM_BENCHMARK_MONITOR"] ?? "unknown",
            "refresh_hz": Int(environment["METALLUM_BENCHMARK_REFRESH_HZ"] ?? "") ?? -1,
            "os_version": ProcessInfo.processInfo.operatingSystemVersionString,
            "thermal_state": Self.thermalStateName()
        ]
        if let presentation = window.presentation {
            for (key, value) in presentation.report {
                metadata[key] = value
            }
        }
        metadata["static_geometry_heaps_enabled"] = NativeState.staticGeometryHeapsEnabled
        metadata["renderer_capability_mask_v1"] = String(
            format: "0x%016llx",
            NativeState.rendererCapabilitySnapshotV1
        )
        metadata["renderer_capabilities_v1"] = rendererCapabilityReport(
            NativeState.rendererCapabilitySnapshotV1
        )
        metadata["display_maximum_fps"] = NativeState.rendererDisplayMaximumFramesPerSecond
        metadata["display_initial_current_headroom"] = NativeState.rendererDisplayCurrentHeadroom
        metadata["display_initial_potential_headroom"] = NativeState.rendererDisplayPotentialHeadroom
        var workloadReport = window.workload.report
        workloadReport["private_geometry_heap"] = MetallumStaticGeometryHeapRegistry.shared
            .snapshot().report

        let rendererGenerationReport: [String: Any]
        if let frameState = NativeState.rendererFrameState.snapshot() {
            rendererGenerationReport = frameState.report
        } else {
            let presentation = window.presentation
            let hdr = (presentation?.outputMode ?? 0) == 0 ? "sdr" : "hdr"
            let spatial = presentation.map {
                $0.renderWidth != $0.displayWidth || $0.renderHeight != $0.displayHeight
            } ?? false
            rendererGenerationReport = [
                "frame_contract_version": 1,
                "frame_graph_version": 2,
                "frame_id": 0,
                "renderer_generation_id": 0,
                "render_contract_generation_id": 0,
                "lighting_generation_id": 0,
                "output_generation_id": 0,
                "resolved_render_contract": "legacy",
                "resolved_lighting_model": "vanilla",
                "resolved_output_mode": hdr,
                "resolved_upscale_mode": spatial ? "spatial" : "native",
                "resolved_interpolation_mode": "off",
                "lighting_preset": "balanced",
                "executor": "metal3",
                "feature_mask": spatial ? 1 : 0,
                "render_width": presentation?.renderWidth ?? 1,
                "render_height": presentation?.renderHeight ?? 1,
                "display_width": presentation?.displayWidth ?? 1,
                "display_height": presentation?.displayHeight ?? 1,
                "resource_bytes": [
                    "base": 0, "material": 0, "hdr": 0, "advanced_lighting": 0,
                    "upscale": 0, "interpolation": 0, "diagnostic": 0
                ],
                "temporal_diagnostics": [
                    "resource_bytes": 0, "motion_bytes": 0, "reactive_bytes": 0,
                    "pass_count": 0, "encoder_count": 0, "pso_count": 0
                ],
                "advanced_lighting_work": [
                    "light_count": 0, "pass_count": 0,
                    "encoder_count": 0, "pso_count": 0, "work_queue_count": 0,
                    "dispatch_count": 0, "upload_bytes": 0
                ]
            ]
        }

        let clusteredLightingReport = MetallumLightingTelemetryStore.shared.snapshot().report
        let voxelClipmapsReport = MetallumVoxelTelemetryStore.shared.snapshot().report
        writer.write([
            "schema_version": 5,
            "timestamp_unix_ms": Int64(Date().timeIntervalSince1970 * 1_000.0),
            "detail_enabled": NativeState.gpuTimingDetailEnabled,
            "presented_frames": window.sampleCount,
            "fps": fps,
            "fps_1_percent_low": Self.lowFps(sortedPresentIntervals, fraction: 0.01),
            "fps_0_1_percent_low": Self.lowFps(sortedPresentIntervals, fraction: 0.001),
            "present_interval_ms": presentIntervalReport,
            "cpu_render_submission_ms": cpuRenderSubmissionReport,
            "presenting_command_buffer_gpu_ms": [
                "average": gpuAverageMs,
                "p50": gpuP50Ms,
                "p95": gpuP95Ms,
                "p99": gpuP99Ms,
                "maximum": gpuMaximumMs
            ],
            "benchmark": window.context.report,
            "metadata": metadata,
            "renderer_generation": rendererGenerationReport,
            "clustered_lighting": clusteredLightingReport,
            "voxel_clipmaps": voxelClipmapsReport,
            "stages": stages,
            "cpu_waits": waits,
            "workload": workloadReport,
            "dropped_timing_events": window.droppedEvents
        ])
    }

    private func emitReport(_ window: MetallumGpuTimingReportWindow) {
        let sortedGpuSeconds = window.gpuSecondSamples.sorted()
        let sortedPresentIntervals = Array(
            window.presentIntervalSamples.prefix(window.presentIntervalCount)
        ).sorted()
        let completedFps = window.totalPresentIntervalSeconds > 0.0
            ? Double(window.presentIntervalCount) / window.totalPresentIntervalSeconds
            : 0.0
        let gpuAverageMs = window.totalGpuSeconds * 1_000.0 / Double(window.sampleCount)
        let gpuP50Ms = Self.percentile(sortedGpuSeconds, fraction: 0.50) * 1_000.0
        let gpuP95Ms = Self.percentile(sortedGpuSeconds, fraction: 0.95) * 1_000.0
        let gpuP99Ms = Self.percentile(sortedGpuSeconds, fraction: 0.99) * 1_000.0
        let gpuMaximumMs = window.maximumGpuSeconds * 1_000.0
        var lines = [String(format:
            "[metallum] GPU timing (%@ segment %d, %d presented frames, %.1f FPS): presenting command buffer %.3f ms avg / %.3f p50 / %.3f p95 / %.3f p99 / %.3f max",
            window.context.phase.reportName,
            window.context.segmentIndex,
            window.sampleCount,
            completedFps,
            gpuAverageMs,
            gpuP50Ms,
            gpuP95Ms,
            gpuP99Ms,
            gpuMaximumMs
        )]
        if !sortedPresentIntervals.isEmpty {
            lines.append(String(format:
                "  present pacing: %.3f ms p95 / %.3f ms p99, %.1f FPS 1%% low / %.1f FPS 0.1%% low",
                Self.percentile(sortedPresentIntervals, fraction: 0.95) * 1_000.0,
                Self.percentile(sortedPresentIntervals, fraction: 0.99) * 1_000.0,
                Self.lowFps(sortedPresentIntervals, fraction: 0.01),
                Self.lowFps(sortedPresentIntervals, fraction: 0.001)
            ))
        }
        if NativeState.gpuTimingDetailEnabled {
            for stage in MetallumGpuTimingStage.allCases {
                let count = window.stageCounts[stage.rawValue]
                if count == 0 {
                    lines.append("  \(stage.reportName): n/a")
                } else {
                    let samples = window.stageSamples[stage.rawValue].sorted()
                    lines.append(String(format:
                        "  %@: %.3f ms avg / %.3f ms p95 / %.3f ms max (%d frames)",
                        stage.reportName,
                        window.stageTotals[stage.rawValue] / Double(count) / 1_000_000.0,
                        Self.percentile(samples, fraction: 0.95) / 1_000_000.0,
                        window.stageMaximums[stage.rawValue] / 1_000_000.0,
                        count
                    ))
                }
            }
        } else {
            lines.append("  per-stage counters: disabled (set METALLUM_GPU_TIMING_DETAIL=1 to enable intrusive detail)")
        }
        for kind in MetallumCpuWaitKind.allCases {
            let count = window.waitCounts[kind.rawValue]
            if count == 0 {
                lines.append("  \(kind.reportName): n/a")
            } else {
                lines.append(String(format:
                    "  %@: %.3f ms/frame avg, %.3f ms max interval (%d waits)",
                    kind.reportName,
                    window.waitNanoseconds[kind.rawValue] / Double(window.sampleCount) / 1_000_000.0,
                    window.maximumWaitNanoseconds[kind.rawValue] / 1_000_000.0,
                    count
                ))
            }
        }
        if window.droppedEvents > 0 {
            lines.append("  dropped timing events: \(window.droppedEvents)")
        }
        NSLog("%@", lines.joined(separator: "\n"))
        writeReport(
            window,
            fps: completedFps,
            gpuAverageMs: gpuAverageMs,
            gpuP50Ms: gpuP50Ms,
            gpuP95Ms: gpuP95Ms,
            gpuP99Ms: gpuP99Ms,
            gpuMaximumMs: gpuMaximumMs,
            sortedPresentIntervals: sortedPresentIntervals
        )
    }

    func record(
        _ commandBuffer: MTLCommandBuffer,
        completion: MetallumGpuTimingCompletion,
        snapshot: MetallumGpuTimingSnapshot?
    ) {
        if commandBuffer.status == .error {
            NSLog(
                "[metallum] Metal command buffer failed (%@): %@",
                commandBuffer.label ?? "unlabeled",
                String(describing: commandBuffer.error)
            )
            return
        }
        guard completion.presentsDrawable,
              let context = completion.benchmarkContext else {
            return
        }
        let duration = commandBuffer.gpuEndTime - commandBuffer.gpuStartTime
        guard duration.isFinite, duration > 0.0 else {
            NSLog(
                "[metallum] GPU timing sample invalid (%@): start %.9f, end %.9f",
                commandBuffer.label ?? "unlabeled",
                commandBuffer.gpuStartTime,
                commandBuffer.gpuEndTime
            )
            return
        }

        lock.lock()
        let window = window(for: context)
        guard let workloadWindowKey = completion.workloadWindowKey,
              workloadWindowKey.generation == context.generation,
              workloadWindowKey.reportIndex == window.reportIndex else {
            let actualGeneration = completion.workloadWindowKey?.generation ?? UInt64.max
            let actualReportIndex = completion.workloadWindowKey?.reportIndex ?? -1
            lock.unlock()
            NSLog(
                "[metallum] GPU timing workload window mismatch (%@): expected %llu/%d, got %llu/%d",
                commandBuffer.label ?? "unlabeled",
                context.generation,
                window.reportIndex,
                actualGeneration,
                actualReportIndex
            )
            return
        }
        if window.sampleCount < Self.reportFrameCount {
            window.gpuSecondSamples[window.sampleCount] = duration
        }
        window.sampleCount += 1
        window.totalGpuSeconds += duration
        window.maximumGpuSeconds = max(window.maximumGpuSeconds, duration)
        if let presentSubmissionUptime = completion.presentSubmissionUptime {
            if let previous = window.previousPresentSubmissionUptime,
               presentSubmissionUptime >= previous,
               window.presentIntervalCount < Self.reportFrameCount {
                let interval = presentSubmissionUptime - previous
                window.presentIntervalSamples[window.presentIntervalCount] = interval
                window.presentIntervalCount += 1
                window.totalPresentIntervalSeconds += interval
            }
            window.previousPresentSubmissionUptime = presentSubmissionUptime
        }
        if let presentation = completion.presentation {
            window.presentation = presentation
        }
        if let snapshot {
            window.droppedEvents += snapshot.droppedEvents
            for stage in MetallumGpuTimingStage.allCases {
                guard let nanoseconds = snapshot.stageNanoseconds[stage.rawValue] else { continue }
                window.stageTotals[stage.rawValue] += nanoseconds
                window.stageMaximums[stage.rawValue] = max(window.stageMaximums[stage.rawValue], nanoseconds)
                window.stageCounts[stage.rawValue] += 1
                window.stageSamples[stage.rawValue].append(nanoseconds)
            }
        }

        let completedWindow: MetallumGpuTimingReportWindow?
        if window.sampleCount == Self.reportFrameCount {
            let waitKey = MetallumCpuWaitWindowKey(
                generation: context.generation,
                reportIndex: window.reportIndex
            )
            completedWindow = window.takeReport(
                cpuWaits: cpuWaitWindows.removeValue(forKey: waitKey),
                workload: workloadWindows.removeValue(forKey: waitKey)
                    ?? MetallumWorkloadAccumulator()
            )
        } else {
            completedWindow = nil
        }
        lock.unlock()
        if let completedWindow {
            reportLock.lock()
            emitReport(completedWindow)
            reportLock.unlock()
        }
    }

    func beginWait() -> MetallumCpuWaitToken {
        let context = NativeState.benchmarkTelemetryState.snapshot()
        lock.lock()
        ensureGenerationLocked(context.generation)
        let reportIndex = presentedCounts[context.generation]! / Self.reportFrameCount
        lock.unlock()
        return MetallumCpuWaitToken(windowKey: MetallumCpuWaitWindowKey(
            generation: context.generation,
            reportIndex: reportIndex
        ))
    }

    func recordWait(
        _ kind: MetallumCpuWaitKind,
        nanoseconds: UInt64,
        token: MetallumCpuWaitToken
    ) {
        lock.lock()
        let accumulator: MetallumCpuWaitAccumulator
        if let existing = cpuWaitWindows[token.windowKey] {
            accumulator = existing
        } else {
            let created = MetallumCpuWaitAccumulator()
            cpuWaitWindows[token.windowKey] = created
            accumulator = created
        }
        accumulator.record(kind, nanoseconds: nanoseconds)
        lock.unlock()
    }
}

private final class MetallumGpuTimingReportWriter: @unchecked Sendable {
    private let handle: FileHandle
    private let lock = NSLock()
    private var reportedFailure = false

    init?(path: String?) {
        guard let path, !path.isEmpty else { return nil }
        let url = URL(fileURLWithPath: path)
        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            if !FileManager.default.fileExists(atPath: url.path) {
                FileManager.default.createFile(atPath: url.path, contents: nil)
            }
            let handle = try FileHandle(forWritingTo: url)
            try handle.seekToEnd()
            self.handle = handle
        } catch {
            NSLog("[metallum] GPU timing report disabled: %@", String(describing: error))
            return nil
        }
    }

    deinit {
        try? handle.close()
    }

    func write(_ report: [String: Any]) {
        lock.lock()
        defer { lock.unlock() }
        do {
            var data = try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
            data.append(0x0A)
            try handle.write(contentsOf: data)
        } catch {
            if !reportedFailure {
                reportedFailure = true
                NSLog("[metallum] GPU timing report write failed: %@", String(describing: error))
            }
        }
    }
}

private enum NativeState {
    static var debugLabelsEnabled = false
    static var depthStencilStates: [DepthStencilKey: MTLDepthStencilState] = [:]
    static var clearPipelines: [PipelineVariantKey: MTLRenderPipelineState] = [:]
    // The base present cache contains only the two-generation SDR shader.
    static var presentPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var legacyHdrPresentPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var actualHdrPresentPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var actualHdrUiOnlyPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var actualHdrLinearUiOnlyPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var spatialPresentPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var spatialScreenshotPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var worldPresentPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var nativeWorldUiPipelines: [UInt: MTLRenderPipelineState] = [:]
    static var actualWorldPresentPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var actualNativeWorldUiPipelines: [UInt: MTLRenderPipelineState] = [:]
    static var sodiumLightPatchPipelines: [UInt: MTLComputePipelineState] = [:]
    static var temporalDiagnosticPipelines: [UInt: MTLRenderPipelineState] = [:]
    static var hdrPipelines: [UInt: MetallumHdrPipelines] = [:]
    static var actualHdrPipelines: [UInt: MetallumActualHdrPipelines] = [:]
    static var uiBackdropPipelines: [UInt: MetallumUiBackdropPipelines] = [:]
    static var menuBlurPipelines: [UInt: MetallumMenuBlurPipelines] = [:]
    static var hdrWorkspaces: [UInt: MetallumHdrWorkspace] = [:]
    static var hdrFallbackAdaptiveStates: [UInt: MTLBuffer] = [:]
    static var hdrFallbackDepthTextures: [UInt: MTLTexture] = [:]
    static var spatialWorkspaces: [UInt: MetallumSpatialWorkspace] = [:]
    static var presentNearestSamplers: [UInt: MTLSamplerState] = [:]
    static var presentLinearSamplers: [UInt: MTLSamplerState] = [:]
    static var builtinShaderStates: [UInt: MetallumBuiltinShaderState] = [:]
    static var initializedDevices: [UInt: MTLDevice] = [:]
    static var preparedRendererGenerations: [UInt: UInt64] = [:]
    static let builtinShaderStatesLock = NSLock()
    static let benchmarkTelemetryState = MetallumBenchmarkTelemetryState()
    static let rendererFrameState = MetallumRendererFrameStateStore()
    static let staticGeometryHeapsEnabled = ProcessInfo.processInfo.environment[
        "METALLUM_STATIC_GEOMETRY_HEAPS"
    ] != "0"
    static let forceBuiltinShaderSource = ProcessInfo.processInfo.environment[
        "METALLUM_NATIVE_SHADER_FORCE_SOURCE"
    ] == "1"
    static var rendererCapabilitySnapshotV1: UInt64 = 0
    static var rendererDisplayMaximumFramesPerSecond = 0
    static var rendererDisplayCurrentHeadroom: Float = 1.0
    static var rendererDisplayPotentialHeadroom: Float = 1.0
    static let gpuTimingEnabled = ProcessInfo.processInfo.environment["METALLUM_GPU_TIMING"] == "1"
    static let gpuTimingDetailEnabled = gpuTimingEnabled
        && ProcessInfo.processInfo.environment["METALLUM_GPU_TIMING_DETAIL"] == "1"
    static let gpuTimingReportWriter = MetallumGpuTimingReportWriter(
        path: gpuTimingEnabled
            ? ProcessInfo.processInfo.environment["METALLUM_GPU_TIMING_REPORT"]
            : nil
    )
    static let gpuTimingStats: MetallumGpuTimingStats? = gpuTimingEnabled
        ? MetallumGpuTimingStats()
        : nil
}

@inline(__always)
private func trackedMakeRenderCommandEncoder(
    _ commandBuffer: MTLCommandBuffer,
    descriptor: MTLRenderPassDescriptor
) -> MTLRenderCommandEncoder? {
    guard let stats = NativeState.gpuTimingStats else {
        return commandBuffer.makeRenderCommandEncoder(descriptor: descriptor)
    }
    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
        return nil
    }
    stats.recordEncoder(encoder, commandBuffer: commandBuffer, kind: .render)
    return encoder
}

@inline(__always)
private func trackedMakeComputeCommandEncoder(
    _ commandBuffer: MTLCommandBuffer,
    descriptor: MTLComputePassDescriptor
) -> MTLComputeCommandEncoder? {
    guard let stats = NativeState.gpuTimingStats else {
        return commandBuffer.makeComputeCommandEncoder(descriptor: descriptor)
    }
    guard let encoder = commandBuffer.makeComputeCommandEncoder(descriptor: descriptor) else {
        return nil
    }
    stats.recordEncoder(encoder, commandBuffer: commandBuffer, kind: .compute)
    return encoder
}

@inline(__always)
private func trackedMakeBlitCommandEncoder(
    _ commandBuffer: MTLCommandBuffer,
    descriptor: MTLBlitPassDescriptor
) -> MTLBlitCommandEncoder? {
    guard let stats = NativeState.gpuTimingStats else {
        return commandBuffer.makeBlitCommandEncoder(descriptor: descriptor)
    }
    guard let encoder = commandBuffer.makeBlitCommandEncoder(descriptor: descriptor) else {
        return nil
    }
    stats.recordEncoder(encoder, commandBuffer: commandBuffer, kind: .blit)
    return encoder
}

@inline(__always)
private func trackedMakeBlitCommandEncoder(
    _ commandBuffer: MTLCommandBuffer
) -> MTLBlitCommandEncoder? {
    guard let stats = NativeState.gpuTimingStats else {
        return commandBuffer.makeBlitCommandEncoder()
    }
    guard let encoder = commandBuffer.makeBlitCommandEncoder() else {
        return nil
    }
    stats.recordEncoder(encoder, commandBuffer: commandBuffer, kind: .blit)
    return encoder
}

@inline(__always)
private func trackedEndEncoding(_ encoder: MTLCommandEncoder) {
    guard let stats = NativeState.gpuTimingStats else {
        encoder.endEncoding()
        return
    }
    stats.finishEncoder(encoder)
    encoder.endEncoding()
}

@inline(__always)
private func trackWorkloadCopy(
    _ encoder: MTLBlitCommandEncoder,
    source: MTLResource,
    destination: MTLResource,
    bytes: UInt64?
) {
    guard let stats = NativeState.gpuTimingStats else { return }
    stats.recordCopy(
        encoder,
        sourceStorageMode: source.storageMode,
        destinationStorageMode: destination.storageMode,
        bytes: bytes.map { Int($0) }
    )
}

@inline(__always)
private func trackBufferAllocation(_ buffer: MTLBuffer) {
    guard let stats = NativeState.gpuTimingStats else { return }
    stats.recordBufferAllocation(buffer.allocatedSize)
}

@inline(__always)
private func trackTextureAllocation(_ texture: MTLTexture) {
    guard let stats = NativeState.gpuTimingStats else { return }
    stats.recordTextureAllocation(texture.allocatedSize)
}

private func attachGpuTiming(
    _ descriptor: MTLRenderPassDescriptor,
    commandBuffer: MTLCommandBuffer,
    stage: MetallumGpuTimingStage?
) {
    guard let stage,
          let frame = MetallumGpuTimingCoordinator.shared.frame(for: commandBuffer) else {
        return
    }
    frame.attachRender(descriptor, stage: stage)
}

private func attachGpuTiming(
    _ descriptor: MTLComputePassDescriptor,
    commandBuffer: MTLCommandBuffer,
    stage: MetallumGpuTimingStage
) {
    MetallumGpuTimingCoordinator.shared.frame(for: commandBuffer)?.attachCompute(descriptor, stage: stage)
}

private func attachGpuTiming(
    _ descriptor: MTLBlitPassDescriptor,
    commandBuffer: MTLCommandBuffer,
    stage: MetallumGpuTimingStage
) {
    MetallumGpuTimingCoordinator.shared.frame(for: commandBuffer)?.attachBlit(descriptor, stage: stage)
}

private struct MetallumGpuExternalTimingToken {
    let frame: MetallumGpuCounterFrame
    let event: MetallumGpuTimingEvent
}

private func beginExternalGpuTiming(
    commandBuffer: MTLCommandBuffer,
    stage: MetallumGpuTimingStage,
    fence: MTLFence?
) -> MetallumGpuExternalTimingToken? {
    guard let frame = MetallumGpuTimingCoordinator.shared.frame(for: commandBuffer),
          let event = frame.allocateExternalEvent(stage) else {
        return nil
    }
    let descriptor = MTLBlitPassDescriptor()
    frame.attachExternalStart(descriptor, event: event)
    guard let encoder = trackedMakeBlitCommandEncoder(commandBuffer, descriptor: descriptor) else {
        return nil
    }
    encoder.label = "Metallum \(stage.reportName) timing start"
    if let fence {
        encoder.waitForFence(fence)
    }
    encoder.fill(buffer: frame.markerBuffer, range: 0..<MemoryLayout<UInt32>.stride, value: 0)
    if let fence {
        encoder.updateFence(fence)
    }
    trackedEndEncoding(encoder)
    return MetallumGpuExternalTimingToken(frame: frame, event: event)
}

private func endExternalGpuTiming(
    _ token: MetallumGpuExternalTimingToken?,
    commandBuffer: MTLCommandBuffer,
    fence: MTLFence?
) {
    guard let token else { return }
    let descriptor = MTLBlitPassDescriptor()
    token.frame.attachExternalEnd(descriptor, event: token.event)
    guard let encoder = trackedMakeBlitCommandEncoder(commandBuffer, descriptor: descriptor) else {
        return
    }
    encoder.label = "Metallum \(token.event.stage.reportName) timing end"
    if let fence {
        encoder.waitForFence(fence)
    }
    encoder.fill(buffer: token.frame.markerBuffer, range: 0..<MemoryLayout<UInt32>.stride, value: 1)
    if let fence {
        encoder.updateFence(fence)
    }
    trackedEndEncoding(encoder)
}

private func completeGpuTiming(
    commandBuffer: MTLCommandBuffer,
    completion: MetallumGpuTimingCompletion
) {
    NativeState.gpuTimingStats?.record(
        commandBuffer,
        completion: completion,
        snapshot: completion.frame?.resolve()
    )
}

private func addGpuTimingCompletionHandler(
    to commandBuffer: MTLCommandBuffer,
    signal semaphore: DispatchSemaphore? = nil
) {
    if NativeState.gpuTimingStats != nil {
        let completion = MetallumGpuTimingCoordinator.shared.take(commandBuffer)
        commandBuffer.addCompletedHandler { completed in
            semaphore?.signal()
            completeGpuTiming(commandBuffer: completed, completion: completion)
        }
    } else {
        commandBuffer.addCompletedHandler { completed in
            if completed.status == .error {
                NSLog(
                    "[metallum] Metal command buffer failed (%@): %@",
                    completed.label ?? "unlabeled",
                    String(describing: completed.error)
                )
            }
            semaphore?.signal()
        }
    }
}

private final class MetallumEdrMonitor: NSObject, @unchecked Sendable {
    private weak var window: NSWindow?
    private let lock = NSLock()
    private var currentHeadroom: Float = 1.0
    private var potentialHeadroom: Float = 1.0
    private var maximumFramesPerSecond = 0
    private var refreshScheduled = false
    private var lastRefreshUptime: TimeInterval = 0.0
    private var observers: [NSObjectProtocol] = []

    init(window: NSWindow) {
        self.window = window
        super.init()

        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: NSWindow.didChangeScreenNotification,
            object: window,
            queue: .main
        ) { [weak self] _ in
            self?.refreshOnMainThread()
        })
        observers.append(center.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.refreshOnMainThread()
        })

        requestRefresh()
    }

    deinit {
        for observer in observers {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    func snapshot() -> (current: Float, potential: Float, maximumFramesPerSecond: Int) {
        requestRefresh()
        lock.lock()
        defer { lock.unlock() }
        return (currentHeadroom, potentialHeadroom, maximumFramesPerSecond)
    }

    private func requestRefresh() {
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        if refreshScheduled || now - lastRefreshUptime < 0.1 {
            lock.unlock()
            return
        }
        refreshScheduled = true
        lock.unlock()

        if Thread.isMainThread {
            refreshOnMainThread()
            return
        }

        DispatchQueue.main.async { [weak self] in
            self?.refreshOnMainThread()
        }
    }

    private func refreshOnMainThread() {
        let screen = window?.screen
        let current = Float(max(
            1.0,
            screen?.maximumExtendedDynamicRangeColorComponentValue ?? 1.0
        ))
        let potential = Float(max(
            1.0,
            screen?.maximumPotentialExtendedDynamicRangeColorComponentValue ?? 1.0
        ))
        let refresh = max(0, screen?.maximumFramesPerSecond ?? 0)

        lock.lock()
        currentHeadroom = current.isFinite ? current : 1.0
        potentialHeadroom = potential.isFinite ? potential : 1.0
        maximumFramesPerSecond = refresh
        refreshScheduled = false
        lastRefreshUptime = ProcessInfo.processInfo.systemUptime
        lock.unlock()
    }
}

@inline(__always)
private func retainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passRetained(object).toOpaque())
}

@inline(__always)
private func unretainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

@inline(__always)
private func objectAddress(_ object: AnyObject) -> UInt {
    UInt(bitPattern: Unmanaged.passUnretained(object).toOpaque())
}

private func textureSliceCount(_ texture: MTLTexture) -> Int {
    switch texture.textureType {
    case .type2DArray:
        return max(texture.arrayLength, 1)
    case .typeCube:
        return 6
    case .typeCubeArray:
        return max(texture.arrayLength, 1) * 6
    default:
        return 1
    }
}

private func stencilPixelFormat(for depthFormat: MTLPixelFormat) -> MTLPixelFormat {
    switch depthFormat {
    case .depth24Unorm_stencil8, .depth32Float_stencil8:
        return depthFormat
    default:
        return .invalid
    }
}

private func makeClearColor(red: Float, green: Float, blue: Float, alpha: Float) -> MTLClearColor {
    MTLClearColor(red: Double(red), green: Double(green), blue: Double(blue), alpha: Double(alpha))
}

private func stringFromOptionalCString(_ pointer: UnsafePointer<CChar>?) -> String? {
    guard let pointer else {
        return nil
    }
    let value = String(cString: pointer)
    return value.isEmpty ? nil : value
}

private func builtinShaderState(device: MTLDevice) -> MetallumBuiltinShaderState {
    let key = objectAddress(device)
    NativeState.builtinShaderStatesLock.lock()
    defer { NativeState.builtinShaderStatesLock.unlock() }
    if let cached = NativeState.builtinShaderStates[key] {
        return cached
    }
    let created = MetallumBuiltinShaderState()
    NativeState.builtinShaderStates[key] = created
    return created
}

private func existingBuiltinShaderState(device: MTLDevice) -> MetallumBuiltinShaderState? {
    NativeState.builtinShaderStatesLock.lock()
    defer { NativeState.builtinShaderStatesLock.unlock() }
    return NativeState.builtinShaderStates[objectAddress(device)]
}

private func removeBuiltinShaderState(device: MTLDevice) {
    NativeState.builtinShaderStatesLock.lock()
    defer { NativeState.builtinShaderStatesLock.unlock() }
    NativeState.builtinShaderStates.removeValue(forKey: objectAddress(device))
}

private func nativeAssetDirectory() -> URL? {
    var info = Dl_info()
    guard dladdr(UnsafeRawPointer(#dsohandle), &info) != 0,
          let imagePath = info.dli_fname else {
        return nil
    }
    return URL(fileURLWithPath: String(cString: imagePath)).deletingLastPathComponent()
}

@discardableResult
private func loadPrecompiledBuiltinShaderLibrary(device: MTLDevice) -> MTLLibrary? {
    let state = builtinShaderState(device: device)
    return state.withLock {
        if state.precompiledLoadAttempted {
            return state.precompiledLibrary
        }
        state.precompiledLoadAttempted = true
        guard !NativeState.forceBuiltinShaderSource else {
            state.mode = .sourceFallback
            NSLog("[metallum] Built-in Metal shader source fallback forced for validation")
            return nil
        }
        guard let assetDirectory = nativeAssetDirectory() else {
            state.mode = .sourceFallback
            NSLog("[metallum] Could not resolve the native shader asset directory; using source fallback")
            return nil
        }

        let libraryURL = assetDirectory.appendingPathComponent("metallum.metallib")
        let start = ProcessInfo.processInfo.systemUptime
        do {
            let library = try device.makeLibrary(URL: libraryURL)
            state.libraryLoadMilliseconds = (ProcessInfo.processInfo.systemUptime - start) * 1_000.0
            // Clustered lighting is an optional, lazy Advanced-generation capability. A
            // missing cluster function must not invalidate the base Metal/Vanilla device.
            let requiredNames = MetallumBuiltinShaderSet.startupMandatory
                .flatMap(\.requiredFunctionNames)
            let missingNames = requiredNames.filter { library.makeFunction(name: $0) == nil }
            guard missingNames.isEmpty else {
                state.mode = .sourceFallback
                NSLog(
                    "[metallum] Precompiled Metal library is missing required functions (%@); using source fallback",
                    missingNames.joined(separator: ", ")
                )
                return nil
            }
            library.label = "Metallum built-in precompiled shaders"
            state.precompiledLibrary = library
            state.mode = .precompiled
            NSLog(
                "[metallum] Precompiled Metal library ready in %.3f ms (%d functions)",
                state.libraryLoadMilliseconds,
                requiredNames.count
            )
            return library
        } catch {
            state.libraryLoadMilliseconds = (ProcessInfo.processInfo.systemUptime - start) * 1_000.0
            state.mode = .sourceFallback
            NSLog(
                "[metallum] Failed to load precompiled Metal library at %@: %@; using source fallback",
                libraryURL.path,
                String(describing: error)
            )
            return nil
        }
    }
}

private func resolveBuiltinShaderLibrary(
    device: MTLDevice,
    shaderSet: MetallumBuiltinShaderSet
) throws -> MTLLibrary {
    let state = builtinShaderState(device: device)
    if let precompiled = loadPrecompiledBuiltinShaderLibrary(device: device) {
        return precompiled
    }
    return try state.withLock {
        if let cached = state.fallbackLibraries[shaderSet] {
            return cached
        }
        guard let assetDirectory = nativeAssetDirectory() else {
            if shaderSet != .clusterBuild {
                state.mode = .failed
            }
            throw NSError(
                domain: "MetallumBuiltinShaders",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Native shader asset directory is unavailable"]
            )
        }
        let sourceURL = assetDirectory
            .appendingPathComponent("shaders", isDirectory: true)
            .appendingPathComponent(shaderSet.sourceFileName)
        let source = try String(contentsOf: sourceURL, encoding: .utf8)
        let start = ProcessInfo.processInfo.systemUptime
        state.sourceCompileCount += 1
        do {
            let library = try device.makeLibrary(source: source, options: nil)
            state.sourceCompileMilliseconds += (ProcessInfo.processInfo.systemUptime - start) * 1_000.0
            library.label = "Metallum built-in \(shaderSet.rawValue) source fallback"
            state.fallbackLibraries[shaderSet] = library
            state.mode = .sourceFallback
            return library
        } catch {
            state.sourceCompileMilliseconds += (ProcessInfo.processInfo.systemUptime - start) * 1_000.0
            if shaderSet != .clusterBuild {
                state.mode = .failed
            }
            throw error
        }
    }
}

private func recordBuiltinPipelineCreation(
    device: MTLDevice,
    count: Int = 1,
    succeeded: Bool
) {
    let state = builtinShaderState(device: device)
    state.withLock {
        state.pipelineCacheMissCount += count
        if succeeded {
            state.pipelineCount += count
            if state.warmupComplete && !state.generationWarmupInProgress {
                state.pipelineCreationsAfterWarmup += count
            }
        } else {
            state.pipelineFailureCount += 1
        }
    }
}

private func builtinShaderInitializationStatus(_ state: MetallumBuiltinShaderState) -> Int32 {
    let snapshot = state.snapshot()
    guard snapshot.pipelineFailureCount == 0, snapshot.pipelineCount >= 5 else {
        return -1
    }
    switch snapshot.mode {
    case .precompiled where snapshot.sourceCompileCount == 0:
        return 1
    case .sourceFallback where snapshot.sourceCompileCount >= MetallumBuiltinShaderSet.startupMandatory.count:
        return 2
    default:
        return -1
    }
}

private func buildSodiumLightPatchPipeline(device: MTLDevice) -> MTLComputePipelineState? {
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .sodiumLightPatch)
        guard let function = library.makeFunction(name: "metallum_sodium_light_legacy_patch") else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            NSLog("[metallum] Failed to create Sodium legacy-light patch shader function")
            return nil
        }
        let pipeline = try device.makeComputePipelineState(function: function)
        recordBuiltinPipelineCreation(device: device, succeeded: true)
        return pipeline
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog(
            "[metallum] Failed to create Sodium legacy-light patch pipeline: %@",
            String(describing: error)
        )
        return nil
    }
}

private func ensureSodiumLightPatchPipeline(device: MTLDevice) -> MTLComputePipelineState? {
    let key = objectAddress(device)
    if let cached = NativeState.sodiumLightPatchPipelines[key] {
        return cached
    }
    let pipeline = buildSodiumLightPatchPipeline(device: device)
    if let pipeline {
        NativeState.sodiumLightPatchPipelines[key] = pipeline
    }
    return pipeline
}

private func buildLightingPipelines(device: MTLDevice) -> MetallumLightingPipelines? {
    if let forcedFailure = getenv("METALLUM_NATIVE_CLUSTER_PIPELINE_FORCE_FAILURE"),
       strcmp(forcedFailure, "1") == 0 {
        NSLog("[metallum] Clustered-lighting pipeline failure forced for validation")
        return nil
    }
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .clusterBuild)
        let names = [
            "metallum_cluster_prepare_v1",
            "metallum_cluster_count_v1",
            "metallum_cluster_masks_v1",
            "metallum_cluster_prefix_blocks_v1",
            "metallum_cluster_prefix_groups_v1",
            "metallum_cluster_prefix_add_v1",
            "metallum_cluster_fill_v1"
        ]
        let functions = names.compactMap { library.makeFunction(name: $0) }
        guard functions.count == names.count else {
            NSLog("[metallum] Cluster-build metallib is missing required ABI v1 functions")
            return nil
        }
        let states = try functions.map { try device.makeComputePipelineState(function: $0) }
        recordBuiltinPipelineCreation(device: device, count: states.count, succeeded: true)
        return MetallumLightingPipelines(
            prepare: states[0],
            count: states[1],
            masks: states[2],
            prefixBlocks: states[3],
            prefixGroups: states[4],
            prefixAdd: states[5],
            fill: states[6]
        )
    } catch {
        NSLog("[metallum] Failed to create clustered-lighting pipelines: %@", String(describing: error))
        return nil
    }
}

private func buildVoxelPipelines(device: MTLDevice) -> MetallumVoxelPipelines? {
    do {
        // Voxel functions are optional. Prefer the shipped metallib, but fall back to the
        // separately packaged source when an older metallib is paired with new native code.
        let library: MTLLibrary
        if let precompiled = loadPrecompiledBuiltinShaderLibrary(device: device),
           MetallumBuiltinShaderSet.voxelOccupancy.requiredFunctionNames.allSatisfy({
               precompiled.makeFunction(name: $0) != nil
           }) {
            library = precompiled
        } else {
            let sourceURL: URL
            if let override = ProcessInfo.processInfo.environment["METALLUM_VOXEL_SHADER_SOURCE"],
               !override.isEmpty {
                // Test/developer-only source override. Shipping still resolves exclusively
                // from the native asset directory, so no runtime working-directory contract.
                sourceURL = URL(fileURLWithPath: override)
            } else {
                guard let assetDirectory = nativeAssetDirectory() else { return nil }
                sourceURL = assetDirectory
                    .appendingPathComponent("shaders", isDirectory: true)
                    .appendingPathComponent(MetallumBuiltinShaderSet.voxelOccupancy.sourceFileName)
            }
            let source = try String(contentsOf: sourceURL, encoding: .utf8)
            library = try device.makeLibrary(source: source, options: nil)
        }
        guard let apply = library.makeFunction(name: "metallum_voxel_apply_v1"),
              let checksum = library.makeFunction(name: "metallum_voxel_checksum_v1") else {
            return nil
        }
        let pipelines = try MetallumVoxelPipelines(
            apply: device.makeComputePipelineState(function: apply),
            checksum: device.makeComputePipelineState(function: checksum)
        )
        guard pipelines.apply.maxTotalThreadsPerThreadgroup >= 256,
              pipelines.checksum.maxTotalThreadsPerThreadgroup >= 256 else {
            return nil
        }
        recordBuiltinPipelineCreation(device: device, count: 2, succeeded: true)
        return pipelines
    } catch {
        // This is a fail-closed optional L5 context. Do not mark base built-in pipeline
        // initialization failed: Vanilla and existing L3/L4 paths remain valid.
        NSLog("[metallum] Voxel occupancy pipeline unavailable: %@", String(describing: error))
        return nil
    }
}

// MARK: - Dynamic L6 voxel-page ABI v1

private enum MetallumDynamicShadowAbiV1 {
    static let version: UInt32 = 1
    static let magic: UInt32 = 0x3153_564d // "MVS1" in little-endian memory.
    static let headerBytes = 48
    static let requestBytes = 64
    static let maxLights: UInt32 = 8
    static let pageAlignment: UInt64 = 256
    static let encoded: Int32 = 1
    static let rejected: Int32 = -10
    static let badPacket: Int32 = -2
    static let staleGeneration: Int32 = -9
    static let unavailable: Int32 = -13
}

private struct MetallumDynamicShadowRequestV1 {
    let stableId: UInt64
    let atlasOffset: UInt64
    let levelIndex: UInt32
    let edge: UInt32
    let maxSteps: UInt32
    let reserved0: UInt32
    let sourceBlockX: Int32
    let sourceBlockY: Int32
    let sourceBlockZ: Int32
    let sourceFractionX: Float
    let sourceFractionY: Float
    let sourceFractionZ: Float
    let radius: Float
}

private struct MetallumDynamicShadowLevelV1 {
    let logicalEdge: UInt32
    let subdivision: UInt32
    let brickDimension: UInt32
    let reserved0: UInt32
}

private struct MetallumDynamicShadowPipelines {
    let page: MTLComputePipelineState
}

private final class MetallumDynamicShadowContext: @unchecked Sendable {
    let device: MTLDevice
    let pipelines: MetallumDynamicShadowPipelines
    let atlasSuffixOffset: UInt64
    let atlasSuffixBytes: UInt64
    private let lock = NSLock()
    private var retired = false

    init(
        device: MTLDevice,
        pipelines: MetallumDynamicShadowPipelines,
        atlasSuffixOffset: UInt64,
        atlasSuffixBytes: UInt64
    ) {
        self.device = device
        self.pipelines = pipelines
        self.atlasSuffixOffset = atlasSuffixOffset
        self.atlasSuffixBytes = atlasSuffixBytes
    }

    func canEncode() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return !retired
    }

    func retire() {
        lock.lock()
        retired = true
        lock.unlock()
    }
}

private func buildDynamicShadowPipelines(device: MTLDevice) -> MetallumDynamicShadowPipelines? {
    do {
        let library: MTLLibrary
        if let precompiled = loadPrecompiledBuiltinShaderLibrary(device: device),
           MetallumBuiltinShaderSet.dynamicVoxelShadow.requiredFunctionNames.allSatisfy({
               precompiled.makeFunction(name: $0) != nil
           }) {
            library = precompiled
        } else {
            let sourceURL: URL
            if let override = ProcessInfo.processInfo.environment["METALLUM_DYNAMIC_SHADOW_SHADER_SOURCE"],
               !override.isEmpty {
                sourceURL = URL(fileURLWithPath: override)
            } else {
                guard let assetDirectory = nativeAssetDirectory() else { return nil }
                sourceURL = assetDirectory
                    .appendingPathComponent("shaders", isDirectory: true)
                    .appendingPathComponent(MetallumBuiltinShaderSet.dynamicVoxelShadow.sourceFileName)
            }
            library = try device.makeLibrary(source: String(contentsOf: sourceURL, encoding: .utf8), options: nil)
        }
        guard let function = library.makeFunction(name: "metallum_dynamic_voxel_shadow_v1") else {
            return nil
        }
        let pipeline = try device.makeComputePipelineState(function: function)
        guard pipeline.threadExecutionWidth > 0, pipeline.maxTotalThreadsPerThreadgroup >= 32 else {
            return nil
        }
        recordBuiltinPipelineCreation(device: device, count: 1, succeeded: true)
        return MetallumDynamicShadowPipelines(page: pipeline)
    } catch {
        NSLog("[metallum] Dynamic voxel-shadow pipeline unavailable: %@", String(describing: error))
        return nil
    }
}

private func parseDynamicShadowPacketV1(
    _ pointer: UnsafeRawPointer?,
    byteSize: UInt64,
    voxel: MetallumVoxelContext,
    atlas: MTLBuffer,
    atlasSuffixOffset: UInt64,
    atlasSuffixBytes: UInt64
) -> (Int32, [MetallumDynamicShadowRequestV1]?) {
    guard let pointer, byteSize >= UInt64(MetallumDynamicShadowAbiV1.headerBytes),
          byteSize <= UInt64(Int.max) else {
        return (MetallumDynamicShadowAbiV1.badPacket, nil)
    }
    let reader = MetallumFrameGraphPacketReader(
        bytes: UnsafeRawBufferPointer(start: pointer, count: Int(byteSize))
    )
    guard let magic = reader.uint32(at: 0), let version = reader.uint32(at: 4),
          let declaredBytes = reader.uint32(at: 8), let count = reader.uint32(at: 12),
          let lightingGeneration = reader.uint64(at: 16), let clipmapGeneration = reader.uint64(at: 24),
          let worldGeneration = reader.uint64(at: 32), let frameId = reader.uint64(at: 40),
          magic == MetallumDynamicShadowAbiV1.magic, version == MetallumDynamicShadowAbiV1.version,
          count > 0, count <= MetallumDynamicShadowAbiV1.maxLights,
          UInt64(declaredBytes) == byteSize,
          byteSize == UInt64(MetallumDynamicShadowAbiV1.headerBytes)
              + UInt64(count) * UInt64(MetallumDynamicShadowAbiV1.requestBytes) else {
        return (MetallumDynamicShadowAbiV1.badPacket, nil)
    }
    guard lightingGeneration == voxel.lightingGeneration,
          clipmapGeneration == voxel.clipmapGeneration,
          worldGeneration == voxel.worldGeneration, frameId > 0 else {
        return (MetallumDynamicShadowAbiV1.staleGeneration, nil)
    }
    let (atlasSuffixEnd, atlasSuffixOverflow) = atlasSuffixOffset.addingReportingOverflow(
        atlasSuffixBytes
    )
    guard !atlasSuffixOverflow,
          atlasSuffixOffset > 0,
          atlasSuffixOffset.isMultiple(of: MetallumDynamicShadowAbiV1.pageAlignment),
          atlasSuffixBytes > 0,
          atlasSuffixBytes.isMultiple(of: MetallumDynamicShadowAbiV1.pageAlignment),
          atlasSuffixEnd == UInt64(atlas.length) else {
        return (MetallumDynamicShadowAbiV1.badPacket, nil)
    }
    var requests: [MetallumDynamicShadowRequestV1] = []
    requests.reserveCapacity(Int(count))
    for index in 0..<Int(count) {
        let offset = MetallumDynamicShadowAbiV1.headerBytes + index * MetallumDynamicShadowAbiV1.requestBytes
        guard let stableId = reader.uint64(at: offset), stableId != 0,
              let atlasOffset = reader.uint64(at: offset + 8),
              let levelIndex = reader.uint32(at: offset + 16),
              let edge = reader.uint32(at: offset + 20),
              let maxSteps = reader.uint32(at: offset + 24),
              let reserved0 = reader.uint32(at: offset + 28), reserved0 == 0,
              let sourceBlockX = reader.int32(at: offset + 32),
              let sourceBlockY = reader.int32(at: offset + 36),
              let sourceBlockZ = reader.int32(at: offset + 40),
              let sourceFractionX = reader.float32(at: offset + 44),
              let sourceFractionY = reader.float32(at: offset + 48),
              let sourceFractionZ = reader.float32(at: offset + 52),
              let radius = reader.float32(at: offset + 56),
              edge == 16 || edge == 32, maxSteps == 32 || maxSteps == 96,
              levelIndex < UInt32(voxel.levels.count),
              sourceFractionX.isFinite, sourceFractionY.isFinite, sourceFractionZ.isFinite,
              sourceFractionX >= 0, sourceFractionX < 1,
              sourceFractionY >= 0, sourceFractionY < 1,
              sourceFractionZ >= 0, sourceFractionZ < 1,
              radius.isFinite, radius > 0,
              atlasOffset.isMultiple(of: MetallumDynamicShadowAbiV1.pageAlignment) else {
            return (MetallumDynamicShadowAbiV1.badPacket, nil)
        }
        let pageBytes = UInt64(edge) * UInt64(edge) * 6 * 4 * 8
        let (pageEnd, pageOverflow) = atlasOffset.addingReportingOverflow(pageBytes)
        guard !pageOverflow,
              atlasOffset >= atlasSuffixOffset,
              pageEnd <= atlasSuffixEnd else {
            return (MetallumDynamicShadowAbiV1.badPacket, nil)
        }
        for previous in requests {
            let previousBytes = UInt64(previous.edge) * UInt64(previous.edge) * 6 * 4 * 8
            let previousEnd = previous.atlasOffset + previousBytes
            guard previous.stableId != stableId,
                  pageEnd <= previous.atlasOffset || previousEnd <= atlasOffset else {
                return (MetallumDynamicShadowAbiV1.badPacket, nil)
            }
        }
        requests.append(MetallumDynamicShadowRequestV1(
            stableId: stableId, atlasOffset: atlasOffset, levelIndex: levelIndex,
            edge: edge, maxSteps: maxSteps, reserved0: reserved0,
            sourceBlockX: sourceBlockX, sourceBlockY: sourceBlockY, sourceBlockZ: sourceBlockZ,
            sourceFractionX: sourceFractionX, sourceFractionY: sourceFractionY,
            sourceFractionZ: sourceFractionZ, radius: radius
        ))
    }
    return (MetallumDynamicShadowAbiV1.encoded, requests)
}

private func alignVoxelHeapBytes(_ value: Int, _ alignment: Int) -> Int? {
    guard value >= 0, alignment > 0, alignment.nonzeroBitCount == 1 else { return nil }
    let mask = alignment - 1
    let (sum, overflow) = value.addingReportingOverflow(mask)
    guard !overflow else { return nil }
    return sum & ~mask
}

private func parseVoxelLayoutsV1(
    _ pointer: UnsafeRawPointer?,
    byteSize: UInt64,
    levelCount: UInt32
) -> [MetallumVoxelLevelLayoutV1]? {
    guard let pointer,
          levelCount > 0,
          levelCount <= 8,
          byteSize == UInt64(levelCount) * UInt64(MetallumVoxelAbiV1.levelLayoutBytes),
          byteSize <= UInt64(Int.max) else {
        return nil
    }
    let reader = MetallumFrameGraphPacketReader(
        bytes: UnsafeRawBufferPointer(start: pointer, count: Int(byteSize))
    )
    var layouts: [MetallumVoxelLevelLayoutV1] = []
    layouts.reserveCapacity(Int(levelCount))
    for index in 0..<Int(levelCount) {
        let offset = index * MetallumVoxelAbiV1.levelLayoutBytes
        guard let logicalEdge = reader.uint32(at: offset),
              let subdivision = reader.uint32(at: offset + 4),
              let originX = reader.uint32(at: offset + 8),
              let originY = reader.uint32(at: offset + 12),
              let originZ = reader.uint32(at: offset + 16),
              let flags = reader.uint32(at: offset + 20),
              let reserved0 = reader.uint32(at: offset + 24),
              let reserved1 = reader.uint32(at: offset + 28),
              flags == 0, reserved0 == 0, reserved1 == 0,
              [UInt32(1), 2, 4].contains(subdivision),
              logicalEdge >= MetallumVoxelAbiV1.logicalBrickEdge,
              logicalEdge.isMultiple(of: MetallumVoxelAbiV1.logicalBrickEdge),
              logicalEdge.isMultiple(of: subdivision) else {
            return nil
        }
        let brickDimension = logicalEdge / MetallumVoxelAbiV1.logicalBrickEdge
        guard brickDimension > 0,
              originX < brickDimension, originY < brickDimension, originZ < brickDimension else {
            return nil
        }
        let baseDimension = logicalEdge / subdivision
        let baseEdge = MetallumVoxelAbiV1.logicalBrickEdge / subdivision
        guard baseDimension.isMultiple(of: baseEdge) else { return nil }
        layouts.append(MetallumVoxelLevelLayoutV1(
            logicalEdge: logicalEdge,
            subdivision: subdivision,
            originBrickX: originX,
            originBrickY: originY,
            originBrickZ: originZ,
            flags: flags
        ))
    }
    return layouts
}

private func makeVoxelContext(
    device: MTLDevice,
    lightingGeneration: UInt64,
    clipmapGeneration: UInt64,
    worldGeneration: UInt64,
    layouts: [MetallumVoxelLevelLayoutV1],
    maxPatchCount: UInt32,
    stagingBytes: UInt64
) -> MetallumVoxelContext? {
    guard lightingGeneration > 0,
          clipmapGeneration > 0,
          worldGeneration > 0,
          maxPatchCount > 0,
          maxPatchCount <= 65_536,
          stagingBytes >= UInt64(MetallumVoxelAbiV1.headerBytes),
          stagingBytes <= UInt64(Int.max),
          MemoryLayout<MetallumVoxelParamsV1>.size == MetallumVoxelAbiV1.paramsBytes,
          MemoryLayout<MetallumVoxelParamsV1>.stride == MetallumVoxelAbiV1.paramsBytes,
          let pipelines = buildVoxelPipelines(device: device) else {
        return nil
    }
    let stagingLength = Int(stagingBytes)
    let parameterBytes = layouts.count * MetallumVoxelAbiV1.paramsStride
    let indirectBytesPerSlot = layouts.count * MetallumVoxelAbiV1.indirectBytes
    struct Request {
        let name: String
        let length: Int
    }
    var requests: [Request] = []
    var levelSizes: [(occupancyWords: Int, opticalBytes: Int, brickCount: Int)] = []
    for (index, layout) in layouts.enumerated() {
        let edge = Int(layout.logicalEdge)
        let subdivision = Int(layout.subdivision)
        let brickDimension = edge / Int(MetallumVoxelAbiV1.logicalBrickEdge)
        let baseDimension = edge / subdivision
        let occupancyWords = edge * edge * edge / 32
        let opticalBytes = baseDimension * baseDimension * baseDimension
        let brickCount = brickDimension * brickDimension * brickDimension
        guard occupancyWords > 0, opticalBytes > 0, brickCount > 0,
              occupancyWords <= Int.max / 4,
              brickCount <= Int.max / 16 else { return nil }
        requests += [
            Request(name: "occupancy L\(index)", length: occupancyWords * 4 + MetallumVoxelAbiV1.guardBytes),
            Request(name: "optical L\(index)", length: opticalBytes + MetallumVoxelAbiV1.guardBytes),
            Request(name: "metadata L\(index)", length: brickCount * 16 + MetallumVoxelAbiV1.guardBytes)
        ]
        levelSizes.append((occupancyWords, opticalBytes, brickCount))
    }
    for index in 0..<MetallumVoxelAbiV1.ringSlots {
        requests += [
            Request(name: "payload slot \(index)", length: stagingLength),
            Request(name: "indirect slot \(index)", length: indirectBytesPerSlot),
            Request(name: "params slot \(index)", length: parameterBytes),
            Request(name: "debug slot \(index)", length: 4)
        ]
    }
    var heapBytes = 0
    for request in requests {
        let query = device.heapBufferSizeAndAlign(
            length: request.length,
            options: MetallumVoxelAbiV1.privateOptions
        )
        guard query.size > 0,
              query.align > 0,
              query.align.nonzeroBitCount == 1,
              let aligned = alignVoxelHeapBytes(heapBytes, query.align) else {
            return nil
        }
        let (next, overflow) = aligned.addingReportingOverflow(query.size)
        guard !overflow else { return nil }
        heapBytes = next
    }
    let heapDescriptor = MTLHeapDescriptor()
    heapDescriptor.size = heapBytes
    heapDescriptor.storageMode = .private
    heapDescriptor.cpuCacheMode = .defaultCache
    heapDescriptor.hazardTrackingMode = .tracked
    heapDescriptor.type = .automatic
    guard let heap = device.makeHeap(descriptor: heapDescriptor) else { return nil }
    heap.label = "Metallum L5 voxel clipmap persistent heap v1"
    var nextRequest = 0
    func allocate() -> MTLBuffer? {
        let request = requests[nextRequest]
        nextRequest += 1
        guard let buffer = heap.makeBuffer(
            length: request.length,
            options: MetallumVoxelAbiV1.privateOptions
        ) else { return nil }
        buffer.label = "Metallum voxel \(request.name) v1"
        return buffer
    }
    var levels: [MetallumVoxelLevelResources] = []
    var resourceBytes: UInt64 = 0
    for index in layouts.indices {
        guard let occupancy = allocate(), let optical = allocate(), let metadata = allocate() else {
            return nil
        }
        resourceBytes &+= UInt64(occupancy.length + optical.length + metadata.length)
        levels.append(MetallumVoxelLevelResources(
            layout: layouts[index],
            brickDimension: layouts[index].logicalEdge / MetallumVoxelAbiV1.logicalBrickEdge,
            occupancyWords: levelSizes[index].occupancyWords,
            opticalBytes: levelSizes[index].opticalBytes,
            brickCount: levelSizes[index].brickCount,
            occupancy: occupancy,
            optical: optical,
            metadata: metadata
        ))
    }
    var slots: [MetallumVoxelRingSlot] = []
    var ringStagingBytes: UInt64 = 0
    var ringPrivateBytes: UInt64 = 0
    for index in 0..<MetallumVoxelAbiV1.ringSlots {
        guard let payload = allocate(), let indirect = allocate(),
              let params = allocate(), let debugScratch = allocate(),
              let staging = device.makeBuffer(length: stagingLength, options: .storageModeShared),
              let indirectStaging = device.makeBuffer(
                  length: indirectBytesPerSlot,
                  options: .storageModeShared
              ),
              let paramsStaging = device.makeBuffer(length: parameterBytes, options: .storageModeShared),
              let debugReadback = device.makeBuffer(length: 4, options: .storageModeShared) else {
            return nil
        }
        staging.label = "Metallum voxel staging slot \(index)"
        indirectStaging.label = "Metallum voxel indirect staging slot \(index)"
        paramsStaging.label = "Metallum voxel parameter staging slot \(index)"
        debugReadback.label = "Metallum voxel diagnostic checksum slot \(index)"
        ringPrivateBytes &+= UInt64(payload.length + indirect.length + params.length + debugScratch.length)
        ringStagingBytes &+= UInt64(
            staging.length + indirectStaging.length + paramsStaging.length + debugReadback.length
        )
        slots.append(MetallumVoxelRingSlot(
            staging: staging, payload: payload, indirectStaging: indirectStaging,
            indirect: indirect, paramsStaging: paramsStaging, params: params,
            debugScratch: debugScratch, debugReadback: debugReadback
        ))
    }
    guard nextRequest == requests.count,
          initializeVoxelContextStorage(device: device, levels: levels) else { return nil }
    resourceBytes &+= ringPrivateBytes
    return MetallumVoxelContext(
        device: device, lightingGeneration: lightingGeneration,
        clipmapGeneration: clipmapGeneration, worldGeneration: worldGeneration,
        heap: heap, levels: levels, slots: slots, maxPatchCount: maxPatchCount,
        stagingBytes: stagingLength, indirectBytesPerSlot: indirectBytesPerSlot,
        pipelines: pipelines, resourceBytes: resourceBytes,
        ringStagingBytes: ringStagingBytes, ringPrivateBytes: ringPrivateBytes
    )
}

/// Completes the only destructive full-clipmap clear before exposing a context. Deferring this
/// clear to the first upload command is unsafe: several command buffers can be encoded before
/// the first completion, and a later clear would erase an earlier patch. A context-local queue
/// also keeps an abandoned caller command buffer from holding initialization hostage.
private func initializeVoxelContextStorage(
    device: MTLDevice,
    levels: [MetallumVoxelLevelResources]
) -> Bool {
    guard let queue = device.makeCommandQueue(),
          let commandBuffer = queue.makeCommandBuffer(),
          let blit = commandBuffer.makeBlitCommandEncoder() else {
        return false
    }
    blit.label = "Metallum L5 voxel context initialization v1"
    for level in levels {
        blit.fill(buffer: level.occupancy, range: 0..<level.occupancyPayloadBytes, value: 0)
        blit.fill(buffer: level.optical, range: 0..<level.opticalBytes, value: 0)
        blit.fill(buffer: level.metadata, range: 0..<level.metadataPayloadBytes, value: 0)
        blit.fill(
            buffer: level.occupancy,
            range: level.occupancyPayloadBytes..<level.occupancy.length,
            value: MetallumVoxelAbiV1.guardValue
        )
        blit.fill(
            buffer: level.optical,
            range: level.opticalBytes..<level.optical.length,
            value: MetallumVoxelAbiV1.guardValue
        )
        blit.fill(
            buffer: level.metadata,
            range: level.metadataPayloadBytes..<level.metadata.length,
            value: MetallumVoxelAbiV1.guardValue
        )
    }
    blit.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
    return commandBuffer.status == .completed
}

private func makeLightingContext(
    device: MTLDevice,
    generation: UInt64,
    maxLights: UInt32,
    indexCapacity: UInt32,
    clustersX: UInt32,
    clustersY: UInt32,
    clustersZ: UInt32
) -> MetallumLightingContext? {
    guard MemoryLayout<MetallumLightingParamsV1>.size == MetallumLightingAbiV1.paramsBytes,
          MemoryLayout<MetallumLightingParamsV1>.stride == MetallumLightingAbiV1.paramsBytes,
          MemoryLayout<MetallumLightingParamsV1>.offset(of: \.viewRotation) == 0,
          MemoryLayout<MetallumLightingParamsV1>.offset(of: \.projection) == 64,
          MemoryLayout<MetallumLightingParamsV1>.offset(of: \.gridAndLightCount) == 128,
          MemoryLayout<MetallumLightingParamsV1>.offset(of: \.extentAndClusterCap) == 144,
          MemoryLayout<MetallumLightingParamsV1>.offset(of: \.depth) == 160,
          MemoryLayout<MetallumLightingParamsV1>.offset(of: \.frameIdAndGeneration) == 176,
          MemoryLayout<MetallumLightingParamsV1>.offset(of: \.capacitiesAndFlags) == 192,
          MemoryLayout<MetallumLightingParamsV1>.offset(of: \.reserved0) == 208,
          generation > 0,
          maxLights > 0, maxLights <= MetallumLightingAbiV1.maximumLights,
          indexCapacity > 0, indexCapacity <= MetallumLightingAbiV1.maximumIndices,
          clustersX > 0, clustersY > 0, clustersZ == MetallumLightingAbiV1.depthSlices else {
        return nil
    }
    let clusterProduct = UInt64(clustersX) * UInt64(clustersY) * UInt64(clustersZ)
    guard clusterProduct > 0,
          clusterProduct <= UInt64(MetallumLightingAbiV1.maximumClusters),
          clusterProduct <= UInt64(UInt32.max),
          let pipelines = buildLightingPipelines(device: device),
          pipelines.masks.maxTotalThreadsPerThreadgroup
              >= Int(MetallumLightingAbiV1.prefixBlockSize),
          pipelines.prefixBlocks.maxTotalThreadsPerThreadgroup
              >= Int(MetallumLightingAbiV1.prefixBlockSize),
          pipelines.prefixAdd.maxTotalThreadsPerThreadgroup
              >= Int(MetallumLightingAbiV1.prefixBlockSize) else {
        return nil
    }
    let clusterCount = UInt32(clusterProduct)
    let lightPayloadBytes = Int(maxLights) * MetallumLightingAbiV1.gpuLightBytes
    let headerPayloadBytes = Int(clusterCount) * MetallumLightingAbiV1.clusterHeaderBytes
    let scratchPayloadBytes = Int(clusterCount) * MetallumLightingAbiV1.clusterScratchBytes
    let indexPayloadBytes = Int(indexCapacity) * MetallumLightingAbiV1.lightIndexBytes
    let prefixBlockCount = Int(
        (clusterCount + MetallumLightingAbiV1.prefixBlockSize - 1)
            / MetallumLightingAbiV1.prefixBlockSize
    )
    guard prefixBlockCount * MetallumLightingAbiV1.blockStatisticsBytes <= indexPayloadBytes else {
        return nil
    }
    let privateOptions: MTLResourceOptions = .storageModePrivate
    guard let gpuLights = device.makeBuffer(
              length: lightPayloadBytes + MetallumLightingAbiV1.guardBytes,
              options: privateOptions
          ),
          let clusterHeaders = device.makeBuffer(
              length: headerPayloadBytes + MetallumLightingAbiV1.guardBytes,
              options: privateOptions
          ),
          let clusterScratch = device.makeBuffer(
              length: scratchPayloadBytes + MetallumLightingAbiV1.guardBytes,
              options: privateOptions
          ),
          let lightIndices = device.makeBuffer(
              length: indexPayloadBytes + MetallumLightingAbiV1.guardBytes,
              options: privateOptions
          ),
          let params = device.makeBuffer(
              length: MetallumLightingAbiV1.paramsBytes + MetallumLightingAbiV1.guardBytes,
              options: privateOptions
          ),
          let statistics = device.makeBuffer(
              length: MetallumLightingAbiV1.statisticsBytes + MetallumLightingAbiV1.guardBytes,
              options: privateOptions
          ) else {
        return nil
    }
    let uploadSlotBytes = MetallumLightingAbiV1.uploadHeaderBytes + lightPayloadBytes
    var slots: [MetallumLightingRingSlot] = []
    slots.reserveCapacity(MetallumLightingAbiV1.ringSlots)
    for slotIndex in 0..<MetallumLightingAbiV1.ringSlots {
        guard let staging = device.makeBuffer(
                  length: uploadSlotBytes,
                  options: [.storageModeShared, .cpuCacheModeWriteCombined]
              ) else {
            return nil
        }
        staging.label = "Metallum lighting upload ring slot \(slotIndex)"
        slots.append(MetallumLightingRingSlot(staging: staging))
    }
    gpuLights.label = "Metallum clustered lights v1"
    clusterHeaders.label = "Metallum cluster headers v1"
    clusterScratch.label = "Metallum cluster scratch v1"
    lightIndices.label = "Metallum compact cluster light indices v1"
    params.label = "Metallum clustered-lighting parameters v1"
    statistics.label = "Metallum clustered-lighting statistics v1"
    let telemetryToken = MetallumLightingTelemetryStore.shared.activate(
        generation: generation,
        clusterCount: clusterCount
    )
    let context = MetallumLightingContext(
        device: device,
        generation: generation,
        telemetryToken: telemetryToken,
        maxLights: maxLights,
        indexCapacity: indexCapacity,
        clustersX: clustersX,
        clustersY: clustersY,
        clustersZ: clustersZ,
        clusterCount: clusterCount,
        pipelines: pipelines,
        gpuLights: gpuLights,
        clusterHeaders: clusterHeaders,
        clusterScratch: clusterScratch,
        lightIndices: lightIndices,
        params: params,
        statistics: statistics,
        slots: slots
    )
    return context
}

private func ensureTemporalDiagnosticPipeline(device: MTLDevice) -> MTLRenderPipelineState? {
    let key = objectAddress(device)
    if let cached = NativeState.temporalDiagnosticPipelines[key] {
        return cached
    }
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .temporalDiagnostics)
        guard let vertex = library.makeFunction(name: "metallum_temporal_diagnostic_vs"),
              let fragment = library.makeFunction(name: "metallum_temporal_diagnostic_fs") else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.label = "Metallum temporal camera-motion diagnostic"
        descriptor.vertexFunction = vertex
        descriptor.fragmentFunction = fragment
        descriptor.colorAttachments[0].pixelFormat = .rg16Float
        descriptor.colorAttachments[1].pixelFormat = .r8Unorm
        let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        recordBuiltinPipelineCreation(device: device, succeeded: true)
        NativeState.temporalDiagnosticPipelines[key] = pipeline
        return pipeline
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog("[metallum] Failed to create temporal diagnostic pipeline: %@", String(describing: error))
        return nil
    }
}


private func buildHdrPipelines(device: MTLDevice) -> MetallumHdrPipelines? {
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .hdrEffects)
        guard
            let vertexFunction = library.makeFunction(name: "metallum_hdr_vs"),
            let extractFunction = library.makeFunction(name: "metallum_hdr_extract_fs"),
            let histogramReduceFunction = library.makeFunction(name: "metallum_hdr_histogram_reduce"),
            let blurFunction = library.makeFunction(name: "metallum_hdr_blur"),
            let uiCompareFunction = library.makeFunction(name: "metallum_hdr_ui_compare_fs"),
            let uiDilateFunction = library.makeFunction(name: "metallum_hdr_ui_dilate_fs")
        else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            NSLog("[metallum] Failed to create HDR effect shader functions")
            return nil
        }

        func makePipeline(
            _ fragmentFunction: MTLFunction,
            colorFormat: MTLPixelFormat
        ) throws -> MTLRenderPipelineState {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = vertexFunction
            descriptor.fragmentFunction = fragmentFunction
            descriptor.colorAttachments[0].pixelFormat = colorFormat
            descriptor.colorAttachments[0].isBlendingEnabled = false
            return try device.makeRenderPipelineState(descriptor: descriptor)
        }

        let pipelines = try MetallumHdrPipelines(
            extract: makePipeline(extractFunction, colorFormat: .rgba16Float),
            histogramReduce: device.makeComputePipelineState(function: histogramReduceFunction),
            blur: device.makeComputePipelineState(function: blurFunction),
            uiCompare: makePipeline(uiCompareFunction, colorFormat: .rg8Unorm),
            uiDilate: makePipeline(uiDilateFunction, colorFormat: .rg8Unorm)
        )
        recordBuiltinPipelineCreation(device: device, count: 5, succeeded: true)
        return pipelines
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog("[metallum] Failed to create HDR effect pipelines: %@", String(describing: error))
        return nil
    }
}

private func buildActualHdrPipelines(device: MTLDevice) -> MetallumActualHdrPipelines? {
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .hdrEffects)
        guard
            let vertexFunction = library.makeFunction(name: "metallum_hdr_vs"),
            let extractFunction = library.makeFunction(name: "metallum_actual_hdr_extract_fs"),
            let exposureReduceFunction = library.makeFunction(name: "metallum_actual_hdr_exposure_reduce"),
            let blurFunction = library.makeFunction(name: "metallum_hdr_blur"),
            let uiCompareFunction = library.makeFunction(name: "metallum_hdr_ui_compare_fs"),
            let uiDilateFunction = library.makeFunction(name: "metallum_hdr_ui_dilate_fs")
        else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            NSLog("[metallum] Failed to create actual-radiance HDR shader functions")
            return nil
        }

        func makePipeline(
            _ fragmentFunction: MTLFunction,
            colorFormat: MTLPixelFormat
        ) throws -> MTLRenderPipelineState {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = vertexFunction
            descriptor.fragmentFunction = fragmentFunction
            descriptor.colorAttachments[0].pixelFormat = colorFormat
            descriptor.colorAttachments[0].isBlendingEnabled = false
            return try device.makeRenderPipelineState(descriptor: descriptor)
        }

        let pipelines = try MetallumActualHdrPipelines(
            extract: makePipeline(extractFunction, colorFormat: .rgba16Float),
            exposureReduce: device.makeComputePipelineState(function: exposureReduceFunction),
            blur: device.makeComputePipelineState(function: blurFunction),
            uiCompare: makePipeline(uiCompareFunction, colorFormat: .rg8Unorm),
            uiDilate: makePipeline(uiDilateFunction, colorFormat: .rg8Unorm)
        )
        recordBuiltinPipelineCreation(device: device, count: 5, succeeded: true)
        return pipelines
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog("[metallum] Failed to create actual-radiance HDR pipelines: %@", String(describing: error))
        return nil
    }
}

private func ensureActualHdrPipelines(device: MTLDevice) -> MetallumActualHdrPipelines? {
    let key = objectAddress(device)
    if let cached = NativeState.actualHdrPipelines[key] {
        return cached
    }
    let pipelines = buildActualHdrPipelines(device: device)
    if let pipelines {
        NativeState.actualHdrPipelines[key] = pipelines
    }
    return pipelines
}

private func ensureUiBackdropPipelines(device: MTLDevice) -> MetallumUiBackdropPipelines? {
    let key = objectAddress(device)
    if let cached = NativeState.uiBackdropPipelines[key] {
        return cached
    }
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .hdrEffects)
        guard let vertex = library.makeFunction(name: "metallum_hdr_vs"),
              let fragment = library.makeFunction(name: "metallum_hdr_ui_backdrop_fs") else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertex
        descriptor.fragmentFunction = fragment
        descriptor.colorAttachments[0].pixelFormat = .rgba8Unorm
        descriptor.colorAttachments[0].isBlendingEnabled = false
        let standard = try device.makeRenderPipelineState(descriptor: descriptor)
        recordBuiltinPipelineCreation(device: device, succeeded: true)
        let pipelines = MetallumUiBackdropPipelines(
            standard: standard,
            vertexFunction: vertex,
            fragmentFunction: fragment
        )
        NativeState.uiBackdropPipelines[key] = pipelines
        return pipelines
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog("[metallum] Failed to create SDR UI backdrop pipeline: %@", String(describing: error))
        return nil
    }
}

private func ensureMenuBlurPipelines(device: MTLDevice) -> MetallumMenuBlurPipelines? {
    let key = objectAddress(device)
    if let cached = NativeState.menuBlurPipelines[key] {
        return cached
    }
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .present)
        guard let vertex = library.makeFunction(name: "metallum_offscreen_vs"),
              let composeFragment = library.makeFunction(name: "metallum_menu_blur_compose_fs"),
              let blurFragment = library.makeFunction(name: "metallum_menu_blur_fs"),
              let resolveFragment = library.makeFunction(name: "metallum_menu_blur_resolve_fs") else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            return nil
        }

        func makePipeline(
            fragment: MTLFunction,
            resolvesUiSeed: Bool = false
        ) throws -> MTLRenderPipelineState {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = vertex
            descriptor.fragmentFunction = fragment
            descriptor.colorAttachments[0].pixelFormat = .rgba16Float
            descriptor.colorAttachments[0].isBlendingEnabled = false
            if resolvesUiSeed {
                descriptor.colorAttachments[1].pixelFormat = .rgba8Unorm
                descriptor.colorAttachments[1].isBlendingEnabled = false
            }
            return try device.makeRenderPipelineState(descriptor: descriptor)
        }

        let pipelines = MetallumMenuBlurPipelines(
            compose: try makePipeline(fragment: composeFragment),
            blur: try makePipeline(fragment: blurFragment),
            resolve: try makePipeline(fragment: resolveFragment, resolvesUiSeed: true)
        )
        recordBuiltinPipelineCreation(device: device, count: 3, succeeded: true)
        NativeState.menuBlurPipelines[key] = pipelines
        return pipelines
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog("[metallum] Failed to create coherent HDR menu-blur pipelines: %@", String(describing: error))
        return nil
    }
}

private func ensureHdrPipelines(device: MTLDevice) -> MetallumHdrPipelines? {
    let key = objectAddress(device)
    if let cached = NativeState.hdrPipelines[key] {
        return cached
    }
    let pipelines = buildHdrPipelines(device: device)
    if let pipelines {
        NativeState.hdrPipelines[key] = pipelines
    }
    return pipelines
}

private func ensureHdrUiBackdropPipeline(
    device: MTLDevice,
    pipelines: MetallumUiBackdropPipelines,
    depthFormat: MTLPixelFormat,
    stencilFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    if depthFormat == .invalid && stencilFormat == .invalid {
        return pipelines.standard
    }
    let key = MetallumHdrUiBackdropPipelineKey(
        depthFormat: depthFormat.rawValue,
        stencilFormat: stencilFormat.rawValue
    )
    if let cached = pipelines.attachmentVariants[key] {
        return cached
    }

    let descriptor = MTLRenderPipelineDescriptor()
    descriptor.label = "Metallum fused HDR UI backdrop"
    descriptor.vertexFunction = pipelines.vertexFunction
    descriptor.fragmentFunction = pipelines.fragmentFunction
    descriptor.colorAttachments[0].pixelFormat = .rgba8Unorm
    descriptor.colorAttachments[0].isBlendingEnabled = false
    descriptor.depthAttachmentPixelFormat = depthFormat
    descriptor.stencilAttachmentPixelFormat = stencilFormat
    do {
        let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        recordBuiltinPipelineCreation(device: device, succeeded: true)
        pipelines.attachmentVariants[key] = pipeline
        return pipeline
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog(
            "[metallum] Failed to create fused HDR UI backdrop pipeline for depth %lu / stencil %lu: %@",
            depthFormat.rawValue,
            stencilFormat.rawValue,
            String(describing: error)
        )
        return nil
    }
}

private func makeHdrAdaptiveStateBuffer(
    device: MTLDevice,
    label: String,
    actualRadiance: Bool = false
) -> MTLBuffer? {
    var initialState = MetallumHdrAdaptiveState(
        // The first two fields are exposure/measured peak in the actual path.
        breakpoint: actualRadiance ? 1.0 : 0.70,
        inferredPeak: 1.0,
        medianLog2: -12.0,
        p90Log2: -12.0,
        p99Log2: -12.0,
        brightCoverage: 0.0,
        currentHeadroom: 1.0,
        valid: 0
    )
    let buffer = withUnsafeBytes(of: &initialState) { bytes in
        device.makeBuffer(
            bytes: bytes.baseAddress!,
            length: bytes.count,
            options: .storageModeShared
        )
    }
    buffer?.label = label
    return buffer
}

private func ensureHdrFallbackAdaptiveState(device: MTLDevice) -> MTLBuffer? {
    let key = objectAddress(device)
    if let cached = NativeState.hdrFallbackAdaptiveStates[key] {
        return cached
    }
    let buffer = makeHdrAdaptiveStateBuffer(
        device: device,
        label: "Metallum HDR fallback adaptive state"
    )
    if let buffer {
        NativeState.hdrFallbackAdaptiveStates[key] = buffer
    }
    return buffer
}

private func ensureHdrFallbackDepthTexture(device: MTLDevice) -> MTLTexture? {
    let key = objectAddress(device)
    if let cached = NativeState.hdrFallbackDepthTextures[key] {
        return cached
    }
    let descriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .depth32Float,
        width: 1,
        height: 1,
        mipmapped: false
    )
    descriptor.storageMode = .private
    descriptor.usage = [.shaderRead]
    let texture = device.makeTexture(descriptor: descriptor)
    texture?.label = "Metallum HDR fallback depth"
    if let texture {
        NativeState.hdrFallbackDepthTextures[key] = texture
    }
    return texture
}

private func ensureHdrWorkspace(
    device: MTLDevice,
    renderContractMode: UInt32 = 0,
    sourceWidth: Int,
    sourceHeight: Int,
    displayWidth: Int,
    displayHeight: Int
) -> MetallumHdrWorkspace? {
    let key = objectAddress(device)
    if let cached = NativeState.hdrWorkspaces[key],
       cached.renderContractMode == renderContractMode,
       cached.sourceWidth == sourceWidth,
       cached.sourceHeight == sourceHeight {
        if cached.displayWidth != displayWidth || cached.displayHeight != displayHeight {
            // The large quarter-resolution world textures depend only on the
            // scene resolution. A display-only resize (for example a MetalFX
            // mode transition) needs new UI masks, not a second allocation of
            // the entire HDR workspace.
            cached.displayWidth = displayWidth
            cached.displayHeight = displayHeight
            cached.uiMaskA = nil
            cached.uiMaskB = nil
            cached.menuBlurA = nil
            cached.menuBlurB = nil
            cached.lastHistogramUptime = nil
            cached.histogramNeedsInitialization = true
        }
        return cached
    }

    let bloomWidth = max((sourceWidth + 3) / 4, 1)
    let bloomHeight = max((sourceHeight + 3) / 4, 1)
    func makeTexture(
        format: MTLPixelFormat,
        width: Int,
        height: Int,
        label: String,
        usage: MTLTextureUsage = [.renderTarget, .shaderRead]
    ) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = usage
        descriptor.hazardTrackingMode = .tracked
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        texture.label = label
        return texture
    }

    guard
        let emission = makeTexture(format: .rgba16Float, width: bloomWidth, height: bloomHeight, label: "Metallum HDR emission"),
        let bloom = makeTexture(
            format: .rgba16Float,
            width: bloomWidth,
            height: bloomHeight,
            label: "Metallum HDR bloom",
            usage: [.shaderRead, .shaderWrite]
        ),
        let histogram = device.makeBuffer(
            length: 64 * MemoryLayout<UInt32>.stride,
            options: .storageModePrivate
        ),
        let adaptiveState = makeHdrAdaptiveStateBuffer(
            device: device,
            label: renderContractMode == 0
                ? "Metallum legacy HDR adaptive state"
                : "Metallum actual HDR exposure state",
            actualRadiance: renderContractMode != 0
        )
    else {
        NSLog("[metallum] Failed to allocate HDR workspace for %dx%d", sourceWidth, sourceHeight)
        return nil
    }

    let workspace = MetallumHdrWorkspace(
        renderContractMode: renderContractMode,
        sourceWidth: sourceWidth,
        sourceHeight: sourceHeight,
        displayWidth: displayWidth,
        displayHeight: displayHeight,
        emission: emission,
        bloom: bloom,
        histogram: histogram,
        adaptiveState: adaptiveState
    )
    histogram.label = renderContractMode == 0
        ? "Metallum legacy HDR luminance histogram"
        : "Metallum actual HDR exposure histogram"
    NativeState.hdrWorkspaces[key] = workspace
    return workspace
}

private func ensureMenuBlurTextures(
    device: MTLDevice,
    workspace: MetallumHdrWorkspace,
    width: Int,
    height: Int
) -> (MTLTexture, MTLTexture)? {
    func valid(_ texture: MTLTexture?) -> Bool {
        texture != nil
            && texture!.width == width
            && texture!.height == height
            && texture!.pixelFormat == .rgba16Float
    }
    if valid(workspace.menuBlurA), valid(workspace.menuBlurB) {
        return (workspace.menuBlurA!, workspace.menuBlurB!)
    }

    func makeTexture(label: String) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.hazardTrackingMode = .tracked
        descriptor.usage = [.renderTarget, .shaderRead]
        let texture = device.makeTexture(descriptor: descriptor)
        texture?.label = label
        return texture
    }

    guard let first = makeTexture(label: "Metallum coherent menu blur A"),
          let second = makeTexture(label: "Metallum coherent menu blur B") else {
        return nil
    }
    workspace.menuBlurA = first
    workspace.menuBlurB = second
    return (first, second)
}

private func makeHdrPassEncoder(
    commandBuffer: MTLCommandBuffer,
    target: MTLTexture,
    pipeline: MTLRenderPipelineState,
    stage: MetallumGpuTimingStage
) -> MTLRenderCommandEncoder? {
    let descriptor = MTLRenderPassDescriptor()
    descriptor.colorAttachments[0].texture = target
    descriptor.colorAttachments[0].loadAction = .dontCare
    descriptor.colorAttachments[0].storeAction = .store
    attachGpuTiming(descriptor, commandBuffer: commandBuffer, stage: stage)
    guard let encoder = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: descriptor) else {
        return nil
    }
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(target.width),
        height: Double(target.height),
        znear: 0.0,
        zfar: 1.0
    ))
    encoder.setRenderPipelineState(pipeline)
    return encoder
}

private func ensureSpatialWorkspace(
    device: MTLDevice,
    sourcePixelFormat: MTLPixelFormat,
    inputWidth: Int,
    inputHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    inputPixelFormat: MTLPixelFormat,
    outputPixelFormat: MTLPixelFormat,
    colorProcessingMode: MTLFXSpatialScalerColorProcessingMode,
    convertsLinearToPerceptual: Bool,
    usesDirectOutput: Bool
) -> MetallumSpatialWorkspace? {
    let key = objectAddress(device)
    if let cached = NativeState.spatialWorkspaces[key],
       cached.sourcePixelFormat == sourcePixelFormat,
       cached.inputWidth == inputWidth,
       cached.inputHeight == inputHeight,
       cached.outputWidth == outputWidth,
       cached.outputHeight == outputHeight,
       cached.inputPixelFormat == inputPixelFormat,
       cached.outputPixelFormat == outputPixelFormat,
       cached.colorProcessingMode == colorProcessingMode,
       cached.convertsLinearToPerceptual == convertsLinearToPerceptual,
       cached.usesDirectOutput == usesDirectOutput {
        return cached
    }

    guard MTLFXSpatialScalerDescriptor.supportsDevice(device) else {
        return nil
    }
    let descriptor = MTLFXSpatialScalerDescriptor()
    descriptor.inputWidth = inputWidth
    descriptor.inputHeight = inputHeight
    descriptor.outputWidth = outputWidth
    descriptor.outputHeight = outputHeight
    descriptor.colorTextureFormat = inputPixelFormat
    descriptor.outputTextureFormat = outputPixelFormat
    descriptor.colorProcessingMode = colorProcessingMode
    guard let scaler = descriptor.makeSpatialScaler(device: device) else {
        NSLog(
            "[metallum] Failed to create MetalFX spatial scaler for %dx%d -> %dx%d, format %lu",
            inputWidth,
            inputHeight,
            outputWidth,
            outputHeight,
            inputPixelFormat.rawValue
        )
        return nil
    }

    var perceptualInput: MTLTexture?
    if sourcePixelFormat != inputPixelFormat || convertsLinearToPerceptual {
        let inputDescriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: inputPixelFormat,
            width: inputWidth,
            height: inputHeight,
            mipmapped: false
        )
        inputDescriptor.storageMode = .private
        inputDescriptor.hazardTrackingMode = .tracked
        inputDescriptor.usage = scaler.colorTextureUsage.union([.renderTarget, .shaderRead])
        guard let allocated = device.makeTexture(descriptor: inputDescriptor) else {
            NSLog("[metallum] Failed to allocate MetalFX perceptual input")
            return nil
        }
        allocated.label = "Metallum MetalFX perceptual input"
        perceptualInput = allocated
    }

    var output: MTLTexture?
    if !usesDirectOutput {
        let textureDescriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: outputPixelFormat,
            width: outputWidth,
            height: outputHeight,
            mipmapped: false
        )
        textureDescriptor.storageMode = .private
        textureDescriptor.hazardTrackingMode = .tracked
        textureDescriptor.usage = scaler.outputTextureUsage.union([.shaderRead, .renderTarget])
        guard let allocated = device.makeTexture(descriptor: textureDescriptor) else {
            NSLog("[metallum] Failed to allocate MetalFX spatial output")
            return nil
        }
        allocated.label = "Metallum MetalFX spatial output"
        output = allocated
    }

    let workspace = MetallumSpatialWorkspace(
        sourcePixelFormat: sourcePixelFormat,
        inputWidth: inputWidth,
        inputHeight: inputHeight,
        outputWidth: outputWidth,
        outputHeight: outputHeight,
        inputPixelFormat: inputPixelFormat,
        outputPixelFormat: outputPixelFormat,
        colorProcessingMode: colorProcessingMode,
        convertsLinearToPerceptual: convertsLinearToPerceptual,
        scaler: scaler,
        perceptualInput: perceptualInput,
        output: output,
        usesDirectOutput: usesDirectOutput
    )
    NativeState.spatialWorkspaces[key] = workspace
    NSLog(
        "[metallum] MetalFX spatial scaler ready: %dx%d -> %dx%d, input format %lu, output format %lu, direct output %d, input usage %lu, output usage %lu",
        inputWidth,
        inputHeight,
        outputWidth,
        outputHeight,
        inputPixelFormat.rawValue,
        outputPixelFormat.rawValue,
        usesDirectOutput ? 1 : 0,
        scaler.colorTextureUsage.rawValue,
        scaler.outputTextureUsage.rawValue
    )
    return workspace
}

private func currentSpatialOutput(
    device: MTLDevice,
    inputTexture: MTLTexture,
    outputWidth: Int,
    outputHeight: Int
) -> MTLTexture? {
    guard let workspace = NativeState.spatialWorkspaces[objectAddress(device)],
          workspace.inputWidth == inputTexture.width,
          workspace.inputHeight == inputTexture.height,
          workspace.outputWidth == outputWidth,
          workspace.outputHeight == outputHeight,
          workspace.sourcePixelFormat == inputTexture.pixelFormat,
          let output = workspace.output,
          output.width == outputWidth,
          output.height == outputHeight else {
        return nil
    }
    return output
}

private func currentSpatialOutput(
    commandBuffer: MTLCommandBuffer,
    inputTexture: MTLTexture,
    outputWidth: Int,
    outputHeight: Int
) -> MTLTexture? {
    guard let workspace = NativeState.spatialWorkspaces[objectAddress(commandBuffer.device)],
          workspace.outputCommandBufferAddress == objectAddress(commandBuffer) else {
        return nil
    }
    return currentSpatialOutput(
        device: commandBuffer.device,
        inputTexture: inputTexture,
        outputWidth: outputWidth,
        outputHeight: outputHeight
    )
}

private func validatedPreparedSpatialUiSeed(
    commandBuffer: MTLCommandBuffer,
    sourceTexture: MTLTexture,
    destinationTexture: MTLTexture
) -> (MetallumSpatialWorkspace, MetallumPreparedSpatialUiSeed)? {
    guard let workspace = NativeState.spatialWorkspaces[objectAddress(commandBuffer.device)],
          let prepared = workspace.preparedUiSeed,
          let currentOutput = workspace.output,
          objectAddress(currentOutput) == objectAddress(prepared.output),
          prepared.commandBufferAddress == objectAddress(commandBuffer),
          prepared.sourceTextureAddress == objectAddress(sourceTexture),
          prepared.destinationTextureAddress == objectAddress(destinationTexture),
          prepared.sourceWidth == sourceTexture.width,
          prepared.sourceHeight == sourceTexture.height,
          prepared.outputWidth == destinationTexture.width,
          prepared.outputHeight == destinationTexture.height,
          prepared.outputWidth == prepared.output.width,
          prepared.outputHeight == prepared.output.height,
          prepared.output.pixelFormat == .rgba16Float,
          prepared.output.textureType == .type2D,
          prepared.output.sampleCount == 1,
          prepared.output.usage.contains(.shaderRead),
          objectAddress(prepared.output.device) == objectAddress(commandBuffer.device),
          destinationTexture.pixelFormat == .rgba8Unorm,
          destinationTexture.textureType == .type2D,
          destinationTexture.sampleCount == 1,
          destinationTexture.usage.contains(.renderTarget),
          objectAddress(sourceTexture.device) == objectAddress(commandBuffer.device),
          objectAddress(destinationTexture.device) == objectAddress(commandBuffer.device)
    else {
        NativeState.spatialWorkspaces[objectAddress(commandBuffer.device)]?.preparedUiSeed = nil
        return nil
    }
    return (workspace, prepared)
}

private func encodePreparedSpatialUiSeedDraw(
    encoder: MTLRenderCommandEncoder,
    output: MTLTexture,
    destination: MTLTexture,
    pipeline: MTLRenderPipelineState
) {
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(destination.width),
        height: Double(destination.height),
        znear: 0.0,
        zfar: 1.0
    ))
    encoder.setScissorRect(MTLScissorRect(
        x: 0,
        y: 0,
        width: destination.width,
        height: destination.height
    ))
    encoder.setRenderPipelineState(pipeline)
    encoder.setCullMode(.none)
    encoder.setTriangleFillMode(.fill)
    encoder.setFragmentTexture(output, index: 0)
    var uniforms = MetallumHdrUiBackdropUniforms(sourceEncoding: 2)
    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
}

private func currentNativeHdrWorldComposite(
    commandBuffer: MTLCommandBuffer,
    inputTexture: MTLTexture,
    outputWidth: Int,
    outputHeight: Int
) -> MTLTexture? {
    guard let workspace = NativeState.hdrWorkspaces[objectAddress(commandBuffer.device)],
          workspace.worldCompositeCommandBufferAddress == objectAddress(commandBuffer),
          workspace.sourceWidth == inputTexture.width,
          workspace.sourceHeight == inputTexture.height,
          outputWidth == inputTexture.width,
          outputHeight == inputTexture.height,
          let output = workspace.worldComposite,
          output.pixelFormat == .rgba16Float,
          output.width == outputWidth,
          output.height == outputHeight else {
        return nil
    }
    return output
}

private func encodeHdrWorldEffects(
    commandBuffer: MTLCommandBuffer,
    sceneTexture: MTLTexture,
    sceneDepthTexture: MTLTexture,
    semanticTexture: MTLTexture?,
    globalFence: MTLFence?,
    sourceEncoding: Int32,
    currentHeadroom: Float,
    displayWidth: Int,
    displayHeight: Int
) -> MetallumHdrWorldOutputs? {
    guard
        let pipelines = ensureHdrPipelines(device: commandBuffer.device),
        let workspace = ensureHdrWorkspace(
            device: commandBuffer.device,
            sourceWidth: sceneTexture.width,
            sourceHeight: sceneTexture.height,
            displayWidth: displayWidth,
            displayHeight: displayHeight
        )
    else {
        return nil
    }

    let now = ProcessInfo.processInfo.systemUptime
    let previousUptime = workspace.lastHistogramUptime
    let deltaTime = previousUptime.map { max(now - $0, 0.0) } ?? 0.0
    let forceReset = previousUptime == nil || deltaTime > 1.0
    workspace.lastHistogramUptime = now

    if workspace.histogramNeedsInitialization {
        let histogramClearPass = MTLBlitPassDescriptor()
        attachGpuTiming(
            histogramClearPass,
            commandBuffer: commandBuffer,
            stage: .histogramExposure
        )
        guard let histogramClear = trackedMakeBlitCommandEncoder(commandBuffer, descriptor: histogramClearPass) else {
            return nil
        }
        histogramClear.label = "Metallum HDR histogram initialization"
        histogramClear.fill(
            buffer: workspace.histogram,
            range: 0..<workspace.histogram.length,
            value: 0
        )
        trackedEndEncoding(histogramClear)
    }

    guard let extract = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: workspace.emission,
        pipeline: pipelines.extract,
        stage: .hdrExtract
    ) else {
        return nil
    }
    if let globalFence {
        extract.waitForFence(globalFence, before: .fragment)
    }
    extract.setFragmentTexture(sceneTexture, index: 0)
    extract.setFragmentTexture(semanticTexture ?? sceneTexture, index: 1)
    extract.setFragmentTexture(sceneDepthTexture, index: 2)
    extract.setFragmentBuffer(workspace.histogram, offset: 0, index: 1)
    var extractUniforms = MetallumHdrExtractUniforms(
        sourceEncoding: UInt32(clamping: min(max(sourceEncoding, 0), 2)),
        semanticAvailable: semanticTexture == nil ? 0 : 1,
        sourceSize: SIMD2<UInt32>(UInt32(sceneTexture.width), UInt32(sceneTexture.height)),
        histogramEnabled: 1,
        _padding0: 0
    )
    withUnsafeBytes(of: &extractUniforms) { bytes in
        extract.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    extract.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    if let globalFence {
        extract.updateFence(globalFence, after: .fragment)
    }
    trackedEndEncoding(extract)

    let histogramReducePass = MTLComputePassDescriptor()
    attachGpuTiming(
        histogramReducePass,
        commandBuffer: commandBuffer,
        stage: .histogramExposure
    )
    let bloomThreadgroupMemoryLength = (24 * 24 + 16 * 24)
        * 4 * MemoryLayout<Float16>.stride
    // Detailed timing keeps distinct encoder boundaries so exposure and bloom
    // remain independently attributable. The production path can encode both
    // independent dispatches together and avoid one encoder boundary.
    let fuseBloomWithHistogram = semanticTexture != nil
        && !NativeState.gpuTimingDetailEnabled
        && pipelines.blur.maxTotalThreadsPerThreadgroup >= 16 * 16
        && commandBuffer.device.maxThreadgroupMemoryLength >= bloomThreadgroupMemoryLength
    guard let histogramReduce = trackedMakeComputeCommandEncoder(commandBuffer, descriptor: histogramReducePass) else {
        workspace.histogramNeedsInitialization = true
        return nil
    }
    histogramReduce.label = fuseBloomWithHistogram
        ? "Metallum HDR histogram reduction + bloom"
        : "Metallum HDR histogram reduction"
    histogramReduce.setComputePipelineState(pipelines.histogramReduce)
    histogramReduce.setBuffer(workspace.histogram, offset: 0, index: 0)
    histogramReduce.setBuffer(workspace.adaptiveState, offset: 0, index: 1)
    var reduceUniforms = MetallumHdrHistogramReduceUniforms(
        currentHeadroom: currentHeadroom,
        deltaTime: Float(min(deltaTime, 2.0)),
        forceReset: forceReset ? 1 : 0,
        _padding0: 0
    )
    withUnsafeBytes(of: &reduceUniforms) { bytes in
        histogramReduce.setBytes(bytes.baseAddress!, length: bytes.count, index: 2)
    }
    histogramReduce.dispatchThreads(
        MTLSize(width: 1, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(width: 1, height: 1, depth: 1)
    )
    if fuseBloomWithHistogram {
        histogramReduce.setComputePipelineState(pipelines.blur)
        histogramReduce.setTexture(workspace.emission, index: 0)
        histogramReduce.setTexture(workspace.bloom, index: 1)
        histogramReduce.setThreadgroupMemoryLength(
            24 * 24 * 4 * MemoryLayout<Float16>.stride,
            index: 0
        )
        histogramReduce.setThreadgroupMemoryLength(
            16 * 24 * 4 * MemoryLayout<Float16>.stride,
            index: 1
        )
        histogramReduce.dispatchThreadgroups(
            MTLSize(
                width: (workspace.bloom.width + 15) / 16,
                height: (workspace.bloom.height + 15) / 16,
                depth: 1
            ),
            threadsPerThreadgroup: MTLSize(width: 16, height: 16, depth: 1)
        )
    }
    trackedEndEncoding(histogramReduce)
    workspace.histogramNeedsInitialization = false

    if semanticTexture == nil {
        // Histogram construction is a separate compute pass. Extract RGB is
        // exactly zero without semantic emission, so reuse that zero texture
        // and skip both quarter-resolution blur passes.
        return MetallumHdrWorldOutputs(
            emission: workspace.emission,
            bloom: workspace.emission,
            adaptiveState: workspace.adaptiveState
        )
    }

    if fuseBloomWithHistogram {
        return MetallumHdrWorldOutputs(
            emission: workspace.emission,
            bloom: workspace.bloom,
            adaptiveState: workspace.adaptiveState
        )
    }

    let bloomPass = MTLComputePassDescriptor()
    attachGpuTiming(
        bloomPass,
        commandBuffer: commandBuffer,
        stage: .bloomHorizontal
    )
    guard pipelines.blur.maxTotalThreadsPerThreadgroup >= 16 * 16,
          commandBuffer.device.maxThreadgroupMemoryLength >= bloomThreadgroupMemoryLength,
          let bloom = trackedMakeComputeCommandEncoder(commandBuffer, descriptor: bloomPass) else {
        return nil
    }
    bloom.label = "Metallum combined HDR bloom"
    bloom.setComputePipelineState(pipelines.blur)
    bloom.setTexture(workspace.emission, index: 0)
    bloom.setTexture(workspace.bloom, index: 1)
    bloom.setThreadgroupMemoryLength(
        24 * 24 * 4 * MemoryLayout<Float16>.stride,
        index: 0
    )
    bloom.setThreadgroupMemoryLength(
        16 * 24 * 4 * MemoryLayout<Float16>.stride,
        index: 1
    )
    bloom.dispatchThreadgroups(
        MTLSize(
            width: (workspace.bloom.width + 15) / 16,
            height: (workspace.bloom.height + 15) / 16,
            depth: 1
        ),
        threadsPerThreadgroup: MTLSize(width: 16, height: 16, depth: 1)
    )
    trackedEndEncoding(bloom)

    return MetallumHdrWorldOutputs(
        emission: workspace.emission,
        bloom: workspace.bloom,
        adaptiveState: workspace.adaptiveState
    )
}

private func encodeActualHdrWorldEffects(
    commandBuffer: MTLCommandBuffer,
    sceneTexture: MTLTexture,
    globalFence: MTLFence?,
    sourceEncoding: Int32,
    currentHeadroom: Float,
    displayWidth: Int,
    displayHeight: Int
) -> MetallumHdrWorldOutputs? {
    guard sceneTexture.pixelFormat == .rgba16Float,
          sourceEncoding == 2,
          let pipelines = ensureActualHdrPipelines(device: commandBuffer.device),
          let workspace = ensureHdrWorkspace(
            device: commandBuffer.device,
            renderContractMode: 1,
            sourceWidth: sceneTexture.width,
            sourceHeight: sceneTexture.height,
            displayWidth: displayWidth,
            displayHeight: displayHeight
          ) else {
        return nil
    }

    let now = ProcessInfo.processInfo.systemUptime
    let previousUptime = workspace.lastHistogramUptime
    let deltaTime = previousUptime.map { max(now - $0, 0.0) } ?? 0.0
    let forceReset = previousUptime == nil || deltaTime > 1.0
    workspace.lastHistogramUptime = now

    if workspace.histogramNeedsInitialization {
        let clearPass = MTLBlitPassDescriptor()
        attachGpuTiming(clearPass, commandBuffer: commandBuffer, stage: .histogramExposure)
        guard let clear = trackedMakeBlitCommandEncoder(commandBuffer, descriptor: clearPass) else {
            return nil
        }
        clear.label = "Metallum actual HDR histogram initialization"
        clear.fill(buffer: workspace.histogram, range: 0..<workspace.histogram.length, value: 0)
        trackedEndEncoding(clear)
    }

    guard let extract = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: workspace.emission,
        pipeline: pipelines.extract,
        stage: .hdrExtract
    ) else {
        return nil
    }
    extract.label = "Metallum actual-radiance bloom extract + exposure histogram"
    if let globalFence {
        extract.waitForFence(globalFence, before: .fragment)
    }
    extract.setFragmentTexture(sceneTexture, index: 0)
    extract.setFragmentBuffer(workspace.histogram, offset: 0, index: 1)
    var extractUniforms = MetallumHdrExtractUniforms(
        sourceEncoding: 2,
        semanticAvailable: 0,
        sourceSize: SIMD2<UInt32>(UInt32(sceneTexture.width), UInt32(sceneTexture.height)),
        histogramEnabled: 1,
        _padding0: 0
    )
    withUnsafeBytes(of: &extractUniforms) { bytes in
        extract.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    extract.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    if let globalFence {
        extract.updateFence(globalFence, after: .fragment)
    }
    trackedEndEncoding(extract)

    let bloomThreadgroupMemoryLength = (24 * 24 + 16 * 24)
        * 4 * MemoryLayout<Float16>.stride
    guard pipelines.blur.maxTotalThreadsPerThreadgroup >= 16 * 16,
          commandBuffer.device.maxThreadgroupMemoryLength >= bloomThreadgroupMemoryLength else {
        workspace.histogramNeedsInitialization = true
        return nil
    }

    let reducePass = MTLComputePassDescriptor()
    attachGpuTiming(reducePass, commandBuffer: commandBuffer, stage: .histogramExposure)
    guard let reduce = trackedMakeComputeCommandEncoder(commandBuffer, descriptor: reducePass) else {
        workspace.histogramNeedsInitialization = true
        return nil
    }
    let fuseBloom = !NativeState.gpuTimingDetailEnabled
    reduce.label = fuseBloom
        ? "Metallum actual HDR exposure + bloom"
        : "Metallum actual HDR exposure"
    reduce.setComputePipelineState(pipelines.exposureReduce)
    reduce.setBuffer(workspace.histogram, offset: 0, index: 0)
    reduce.setBuffer(workspace.adaptiveState, offset: 0, index: 1)
    var reduceUniforms = MetallumHdrHistogramReduceUniforms(
        currentHeadroom: currentHeadroom,
        deltaTime: Float(min(deltaTime, 2.0)),
        forceReset: forceReset ? 1 : 0,
        _padding0: 0
    )
    withUnsafeBytes(of: &reduceUniforms) { bytes in
        reduce.setBytes(bytes.baseAddress!, length: bytes.count, index: 2)
    }
    reduce.dispatchThreads(
        MTLSize(width: 1, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(width: 1, height: 1, depth: 1)
    )
    if fuseBloom {
        reduce.setComputePipelineState(pipelines.blur)
        reduce.setTexture(workspace.emission, index: 0)
        reduce.setTexture(workspace.bloom, index: 1)
        reduce.setThreadgroupMemoryLength(
            24 * 24 * 4 * MemoryLayout<Float16>.stride,
            index: 0
        )
        reduce.setThreadgroupMemoryLength(
            16 * 24 * 4 * MemoryLayout<Float16>.stride,
            index: 1
        )
        reduce.dispatchThreadgroups(
            MTLSize(
                width: (workspace.bloom.width + 15) / 16,
                height: (workspace.bloom.height + 15) / 16,
                depth: 1
            ),
            threadsPerThreadgroup: MTLSize(width: 16, height: 16, depth: 1)
        )
    }
    trackedEndEncoding(reduce)
    workspace.histogramNeedsInitialization = false

    if !fuseBloom {
        let bloomPass = MTLComputePassDescriptor()
        attachGpuTiming(bloomPass, commandBuffer: commandBuffer, stage: .bloomHorizontal)
        guard let bloom = trackedMakeComputeCommandEncoder(commandBuffer, descriptor: bloomPass) else {
            return nil
        }
        bloom.label = "Metallum actual-radiance bloom"
        bloom.setComputePipelineState(pipelines.blur)
        bloom.setTexture(workspace.emission, index: 0)
        bloom.setTexture(workspace.bloom, index: 1)
        bloom.setThreadgroupMemoryLength(
            24 * 24 * 4 * MemoryLayout<Float16>.stride,
            index: 0
        )
        bloom.setThreadgroupMemoryLength(
            16 * 24 * 4 * MemoryLayout<Float16>.stride,
            index: 1
        )
        bloom.dispatchThreadgroups(
            MTLSize(
                width: (workspace.bloom.width + 15) / 16,
                height: (workspace.bloom.height + 15) / 16,
                depth: 1
            ),
            threadsPerThreadgroup: MTLSize(width: 16, height: 16, depth: 1)
        )
        trackedEndEncoding(bloom)
    }

    return MetallumHdrWorldOutputs(
        emission: workspace.emission,
        bloom: workspace.bloom,
        adaptiveState: workspace.adaptiveState
    )
}

private func encodeHdrUiMask(
    commandBuffer: MTLCommandBuffer,
    finalTexture: MTLTexture,
    sceneTexture: MTLTexture,
    uiTexture: MTLTexture?,
    globalFence: MTLFence?,
    sourceEncoding: Int32,
    displayWidth: Int,
    displayHeight: Int
) -> MTLTexture? {
    guard
        let pipelines = ensureHdrPipelines(device: commandBuffer.device),
        let workspace = ensureHdrWorkspace(
            device: commandBuffer.device,
            sourceWidth: sceneTexture.width,
            sourceHeight: sceneTexture.height,
            displayWidth: displayWidth,
            displayHeight: displayHeight
        )
    else {
        return nil
    }

    let maskWidth = max((displayWidth + 1) / 2, 1)
    let maskHeight = max((displayHeight + 1) / 2, 1)
    if workspace.uiMaskA?.width != maskWidth
        || workspace.uiMaskA?.height != maskHeight
        || workspace.uiMaskB?.width != maskWidth
        || workspace.uiMaskB?.height != maskHeight {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rg8Unorm,
            width: maskWidth,
            height: maskHeight,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.hazardTrackingMode = .tracked
        guard
            let maskA = commandBuffer.device.makeTexture(descriptor: descriptor),
            let maskB = commandBuffer.device.makeTexture(descriptor: descriptor)
        else {
            return nil
        }
        maskA.label = "Metallum HDR UI control A"
        maskB.label = "Metallum HDR UI control B"
        workspace.uiMaskA = maskA
        workspace.uiMaskB = maskB
    }
    guard let uiMaskA = workspace.uiMaskA, let uiMaskB = workspace.uiMaskB else {
        return nil
    }

    guard let uiCompare = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: uiMaskA,
        pipeline: pipelines.uiCompare,
        stage: .hdrReconstruction
    ) else {
        return nil
    }
    if let globalFence {
        uiCompare.waitForFence(globalFence, before: .fragment)
    }
    uiCompare.setFragmentTexture(uiTexture ?? finalTexture, index: 0)
    uiCompare.setFragmentTexture(sceneTexture, index: 1)
    var uiCompareUniforms = MetallumHdrUiCompareUniforms(
        sourceEncoding: UInt32(clamping: min(max(sourceEncoding, 0), 2)),
        seededUiAvailable: uiTexture == nil ? 0 : 1,
        scaleScene: sceneTexture.width == finalTexture.width
            && sceneTexture.height == finalTexture.height ? 0 : 1,
        _padding0: 0
    )
    withUnsafeBytes(of: &uiCompareUniforms) { bytes in
        uiCompare.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    uiCompare.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    if let globalFence {
        uiCompare.updateFence(globalFence, after: .fragment)
    }
    trackedEndEncoding(uiCompare)

    guard let uiDilate = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: uiMaskB,
        pipeline: pipelines.uiDilate,
        stage: .hdrReconstruction
    ) else {
        return nil
    }
    uiDilate.setFragmentTexture(uiMaskA, index: 0)
    uiDilate.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    trackedEndEncoding(uiDilate)

    return uiMaskB
}

private func encodeActualHdrUiMask(
    commandBuffer: MTLCommandBuffer,
    finalTexture: MTLTexture,
    sceneTexture: MTLTexture,
    uiTexture: MTLTexture?,
    globalFence: MTLFence?,
    displayWidth: Int,
    displayHeight: Int
) -> MTLTexture? {
    guard let pipelines = ensureActualHdrPipelines(device: commandBuffer.device),
          let workspace = ensureHdrWorkspace(
            device: commandBuffer.device,
            renderContractMode: 1,
            sourceWidth: sceneTexture.width,
            sourceHeight: sceneTexture.height,
            displayWidth: displayWidth,
            displayHeight: displayHeight
          ) else {
        return nil
    }

    let maskWidth = max((displayWidth + 1) / 2, 1)
    let maskHeight = max((displayHeight + 1) / 2, 1)
    if workspace.uiMaskA?.width != maskWidth
        || workspace.uiMaskA?.height != maskHeight
        || workspace.uiMaskB?.width != maskWidth
        || workspace.uiMaskB?.height != maskHeight {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rg8Unorm,
            width: maskWidth,
            height: maskHeight,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.hazardTrackingMode = .tracked
        guard let maskA = commandBuffer.device.makeTexture(descriptor: descriptor),
              let maskB = commandBuffer.device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        maskA.label = "Metallum actual HDR UI control A"
        maskB.label = "Metallum actual HDR UI control B"
        workspace.uiMaskA = maskA
        workspace.uiMaskB = maskB
    }
    guard let uiMaskA = workspace.uiMaskA, let uiMaskB = workspace.uiMaskB else {
        return nil
    }

    guard let compare = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: uiMaskA,
        pipeline: pipelines.uiCompare,
        stage: .actualHdrDisplay
    ) else {
        return nil
    }
    if let globalFence {
        compare.waitForFence(globalFence, before: .fragment)
    }
    compare.setFragmentTexture(uiTexture ?? finalTexture, index: 0)
    compare.setFragmentTexture(sceneTexture, index: 1)
    var uniforms = MetallumHdrUiCompareUniforms(
        sourceEncoding: 2,
        seededUiAvailable: uiTexture == nil ? 0 : 1,
        scaleScene: sceneTexture.width == finalTexture.width
            && sceneTexture.height == finalTexture.height ? 0 : 1,
        _padding0: 0
    )
    withUnsafeBytes(of: &uniforms) { bytes in
        compare.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    compare.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    if let globalFence {
        compare.updateFence(globalFence, after: .fragment)
    }
    trackedEndEncoding(compare)

    guard let dilate = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: uiMaskB,
        pipeline: pipelines.uiDilate,
        stage: .actualHdrDisplay
    ) else {
        return nil
    }
    dilate.setFragmentTexture(uiMaskA, index: 0)
    dilate.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    trackedEndEncoding(dilate)
    return uiMaskB
}

private func encodeHdrEffects(
    commandBuffer: MTLCommandBuffer,
    finalTexture: MTLTexture,
    sceneTexture: MTLTexture,
    displaySceneTexture: MTLTexture,
    sceneDepthTexture: MTLTexture,
    semanticTexture: MTLTexture?,
    uiTexture: MTLTexture?,
    globalFence: MTLFence?,
    sourceEncoding: Int32,
    currentHeadroom: Float
) -> MetallumHdrOutputs? {
    guard let world = encodeHdrWorldEffects(
        commandBuffer: commandBuffer,
        sceneTexture: sceneTexture,
        sceneDepthTexture: sceneDepthTexture,
        semanticTexture: semanticTexture,
        globalFence: globalFence,
        sourceEncoding: sourceEncoding,
        currentHeadroom: currentHeadroom,
        displayWidth: displaySceneTexture.width,
        displayHeight: displaySceneTexture.height
    ), let uiMask = encodeHdrUiMask(
        commandBuffer: commandBuffer,
        finalTexture: finalTexture,
        sceneTexture: displaySceneTexture,
        uiTexture: uiTexture,
        globalFence: globalFence,
        sourceEncoding: sourceEncoding,
        displayWidth: displaySceneTexture.width,
        displayHeight: displaySceneTexture.height
    ) else {
        return nil
    }
    return MetallumHdrOutputs(
        emission: world.emission,
        bloom: world.bloom,
        uiMask: uiMask,
        adaptiveState: world.adaptiveState
    )
}

private func encodeActualHdrEffects(
    commandBuffer: MTLCommandBuffer,
    finalTexture: MTLTexture,
    sceneTexture: MTLTexture,
    displaySceneTexture: MTLTexture,
    uiTexture: MTLTexture?,
    globalFence: MTLFence?,
    currentHeadroom: Float
) -> MetallumHdrOutputs? {
    guard let world = encodeActualHdrWorldEffects(
        commandBuffer: commandBuffer,
        sceneTexture: sceneTexture,
        globalFence: globalFence,
        sourceEncoding: 2,
        currentHeadroom: currentHeadroom,
        displayWidth: displaySceneTexture.width,
        displayHeight: displaySceneTexture.height
    ), let uiMask = encodeActualHdrUiMask(
        commandBuffer: commandBuffer,
        finalTexture: finalTexture,
        sceneTexture: displaySceneTexture,
        uiTexture: uiTexture,
        globalFence: globalFence,
        displayWidth: displaySceneTexture.width,
        displayHeight: displaySceneTexture.height
    ) else {
        return nil
    }
    return MetallumHdrOutputs(
        emission: world.emission,
        bloom: world.bloom,
        uiMask: uiMask,
        adaptiveState: world.adaptiveState
    )
}

private func encodeNativeHdrWorldUiComposite(
    commandBuffer: MTLCommandBuffer,
    sceneTexture: MTLTexture,
    sceneDepthTexture: MTLTexture,
    semanticTexture: MTLTexture?,
    uiSeedTexture: MTLTexture,
    globalFence: MTLFence?,
    sourceEncoding: Int32,
    currentHeadroom: Float,
    hdrStrength: Float,
    bloomStrength: Float
) -> MTLTexture? {
    guard sceneTexture.width == uiSeedTexture.width,
          sceneTexture.height == uiSeedTexture.height,
          let world = encodeHdrWorldEffects(
            commandBuffer: commandBuffer,
            sceneTexture: sceneTexture,
            sceneDepthTexture: sceneDepthTexture,
            semanticTexture: semanticTexture,
            globalFence: globalFence,
            sourceEncoding: sourceEncoding,
            currentHeadroom: currentHeadroom,
            displayWidth: sceneTexture.width,
            displayHeight: sceneTexture.height
          ), let workspace = ensureHdrWorkspace(
            device: commandBuffer.device,
            sourceWidth: sceneTexture.width,
            sourceHeight: sceneTexture.height,
            displayWidth: sceneTexture.width,
            displayHeight: sceneTexture.height
          ), let pipeline = ensureNativeWorldUiPipeline(device: commandBuffer.device),
          let samplers = presentSamplers(device: commandBuffer.device)
    else {
        return nil
    }

    let worldComposite: MTLTexture
    if let existing = workspace.worldComposite {
        worldComposite = existing
    } else {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: sceneTexture.width,
            height: sceneTexture.height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.hazardTrackingMode = .tracked
        guard let allocated = commandBuffer.device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        allocated.label = "Metallum native HDR world"
        workspace.worldComposite = allocated
        worldComposite = allocated
    }

    let renderPass = MTLRenderPassDescriptor()
    renderPass.colorAttachments[0].texture = worldComposite
    renderPass.colorAttachments[0].loadAction = .dontCare
    renderPass.colorAttachments[0].storeAction = .store
    renderPass.colorAttachments[1].texture = uiSeedTexture
    renderPass.colorAttachments[1].loadAction = .dontCare
    renderPass.colorAttachments[1].storeAction = .store
    attachGpuTiming(renderPass, commandBuffer: commandBuffer, stage: .hdrReconstruction)
    guard let encoder = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: renderPass) else {
        return nil
    }
    encoder.label = "Metallum fused native HDR world and SDR UI seed"
    if let globalFence {
        encoder.waitForFence(globalFence, before: .fragment)
    }
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(sceneTexture.width),
        height: Double(sceneTexture.height),
        znear: 0.0,
        zfar: 1.0
    ))
    encoder.setRenderPipelineState(pipeline)
    encoder.setFragmentTexture(sceneTexture, index: 0)
    encoder.setFragmentTexture(world.emission, index: 1)
    encoder.setFragmentTexture(world.bloom, index: 2)
    encoder.setFragmentTexture(semanticTexture ?? sceneTexture, index: 3)
    encoder.setFragmentTexture(sceneDepthTexture, index: 4)
    encoder.setFragmentSamplerState(samplers.nearest, index: 0)
    encoder.setFragmentSamplerState(samplers.linear, index: 1)
    var uniforms = MetallumPresentUniforms(
        mode: 2,
        sourceEncoding: UInt32(clamping: min(max(sourceEncoding, 0), 2)),
        diagnosticPattern: 0,
        currentHeadroom: currentHeadroom,
        hdrStrength: hdrStrength,
        bloomStrength: bloomStrength,
        sceneAvailable: 1,
        uiAvailable: 0,
        semanticAvailable: semanticTexture == nil ? 0 : 1
    )
    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    encoder.setFragmentBuffer(world.adaptiveState, offset: 0, index: 1)
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    if let globalFence {
        encoder.updateFence(globalFence, after: .fragment)
    }
    trackedEndEncoding(encoder)
    workspace.worldCompositeCommandBufferAddress = objectAddress(commandBuffer)
    return worldComposite
}

private func encodeActualNativeHdrWorldUiComposite(
    commandBuffer: MTLCommandBuffer,
    sceneTexture: MTLTexture,
    uiSeedTexture: MTLTexture,
    globalFence: MTLFence?,
    currentHeadroom: Float,
    hdrStrength: Float,
    bloomStrength: Float
) -> MTLTexture? {
    guard sceneTexture.pixelFormat == .rgba16Float,
          sceneTexture.width == uiSeedTexture.width,
          sceneTexture.height == uiSeedTexture.height,
          let world = encodeActualHdrWorldEffects(
            commandBuffer: commandBuffer,
            sceneTexture: sceneTexture,
            globalFence: globalFence,
            sourceEncoding: 2,
            currentHeadroom: currentHeadroom,
            displayWidth: sceneTexture.width,
            displayHeight: sceneTexture.height
          ), let workspace = ensureHdrWorkspace(
            device: commandBuffer.device,
            renderContractMode: 1,
            sourceWidth: sceneTexture.width,
            sourceHeight: sceneTexture.height,
            displayWidth: sceneTexture.width,
            displayHeight: sceneTexture.height
          ), let pipeline = ensureActualNativeWorldUiPipeline(device: commandBuffer.device),
          let samplers = presentSamplers(device: commandBuffer.device) else {
        return nil
    }

    let worldComposite: MTLTexture
    if let existing = workspace.worldComposite {
        worldComposite = existing
    } else {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: sceneTexture.width,
            height: sceneTexture.height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.hazardTrackingMode = .tracked
        guard let allocated = commandBuffer.device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        allocated.label = "Metallum actual native HDR world"
        workspace.worldComposite = allocated
        worldComposite = allocated
    }

    let renderPass = MTLRenderPassDescriptor()
    renderPass.colorAttachments[0].texture = worldComposite
    renderPass.colorAttachments[0].loadAction = .dontCare
    renderPass.colorAttachments[0].storeAction = .store
    renderPass.colorAttachments[1].texture = uiSeedTexture
    renderPass.colorAttachments[1].loadAction = .dontCare
    renderPass.colorAttachments[1].storeAction = .store
    attachGpuTiming(renderPass, commandBuffer: commandBuffer, stage: .actualHdrDisplay)
    guard let encoder = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: renderPass) else {
        return nil
    }
    encoder.label = "Metallum actual HDR world + SDR UI seed"
    if let globalFence {
        encoder.waitForFence(globalFence, before: .fragment)
    }
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(sceneTexture.width),
        height: Double(sceneTexture.height),
        znear: 0.0,
        zfar: 1.0
    ))
    encoder.setRenderPipelineState(pipeline)
    encoder.setFragmentTexture(sceneTexture, index: 0)
    encoder.setFragmentTexture(world.bloom, index: 1)
    encoder.setFragmentSamplerState(samplers.nearest, index: 0)
    encoder.setFragmentSamplerState(samplers.linear, index: 1)
    var uniforms = MetallumPresentUniforms(
        mode: 2,
        sourceEncoding: 2,
        diagnosticPattern: 0,
        currentHeadroom: currentHeadroom,
        hdrStrength: hdrStrength,
        bloomStrength: bloomStrength,
        sceneAvailable: 1,
        uiAvailable: 0,
        semanticAvailable: 0
    )
    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    encoder.setFragmentBuffer(world.adaptiveState, offset: 0, index: 1)
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    if let globalFence {
        encoder.updateFence(globalFence, after: .fragment)
    }
    trackedEndEncoding(encoder)
    workspace.worldCompositeCommandBufferAddress = objectAddress(commandBuffer)
    return worldComposite
}

private func encodeSpatialHdrWorldComposite(
    commandBuffer: MTLCommandBuffer,
    sceneTexture: MTLTexture,
    sceneDepthTexture: MTLTexture,
    semanticTexture: MTLTexture?,
    globalFence: MTLFence?,
    sourceEncoding: Int32,
    currentHeadroom: Float,
    hdrStrength: Float,
    bloomStrength: Float,
    displayWidth: Int,
    displayHeight: Int
) -> MTLTexture? {
    let worldComposite: MTLTexture
    guard let world = encodeHdrWorldEffects(
        commandBuffer: commandBuffer,
        sceneTexture: sceneTexture,
        sceneDepthTexture: sceneDepthTexture,
        semanticTexture: semanticTexture,
        globalFence: globalFence,
        sourceEncoding: sourceEncoding,
        currentHeadroom: currentHeadroom,
        displayWidth: displayWidth,
        displayHeight: displayHeight
    ), let workspace = ensureHdrWorkspace(
        device: commandBuffer.device,
        sourceWidth: sceneTexture.width,
        sourceHeight: sceneTexture.height,
        displayWidth: displayWidth,
        displayHeight: displayHeight
    ) else {
        return nil
    }
    if let existing = workspace.worldComposite {
        worldComposite = existing
    } else {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: sceneTexture.width,
            height: sceneTexture.height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.hazardTrackingMode = .tracked
        guard let allocated = commandBuffer.device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        allocated.label = "Metallum HDR spatial world"
        workspace.worldComposite = allocated
        worldComposite = allocated
    }
    guard let pipeline = ensureWorldPresentPipeline(
        device: commandBuffer.device,
        colorFormat: .rgba16Float
    ), let samplers = presentSamplers(device: commandBuffer.device),
       let encoder = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: worldComposite,
        pipeline: pipeline,
        stage: .hdrReconstruction
    ) else {
        return nil
    }

    if let globalFence {
        encoder.waitForFence(globalFence, before: .fragment)
    }
    encoder.setFragmentTexture(sceneTexture, index: 0)
    encoder.setFragmentTexture(world.emission, index: 1)
    encoder.setFragmentTexture(world.bloom, index: 2)
    encoder.setFragmentTexture(semanticTexture ?? sceneTexture, index: 3)
    encoder.setFragmentTexture(sceneDepthTexture, index: 4)
    encoder.setFragmentSamplerState(samplers.nearest, index: 0)
    encoder.setFragmentSamplerState(samplers.linear, index: 1)
    var uniforms = MetallumPresentUniforms(
        mode: 2,
        sourceEncoding: UInt32(clamping: min(max(sourceEncoding, 0), 2)),
        diagnosticPattern: 0,
        currentHeadroom: currentHeadroom,
        hdrStrength: hdrStrength,
        bloomStrength: bloomStrength,
        sceneAvailable: 1,
        uiAvailable: 0,
        semanticAvailable: semanticTexture == nil ? 0 : 1
    )
    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    encoder.setFragmentBuffer(world.adaptiveState, offset: 0, index: 1)
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    if let globalFence {
        encoder.updateFence(globalFence, after: .fragment)
    }
    trackedEndEncoding(encoder)
    return worldComposite
}

private func encodeActualSpatialHdrWorldComposite(
    commandBuffer: MTLCommandBuffer,
    sceneTexture: MTLTexture,
    globalFence: MTLFence?,
    currentHeadroom: Float,
    hdrStrength: Float,
    bloomStrength: Float,
    displayWidth: Int,
    displayHeight: Int
) -> MTLTexture? {
    guard sceneTexture.pixelFormat == .rgba16Float,
          let world = encodeActualHdrWorldEffects(
            commandBuffer: commandBuffer,
            sceneTexture: sceneTexture,
            globalFence: globalFence,
            sourceEncoding: 2,
            currentHeadroom: currentHeadroom,
            displayWidth: displayWidth,
            displayHeight: displayHeight
          ), let workspace = ensureHdrWorkspace(
            device: commandBuffer.device,
            renderContractMode: 1,
            sourceWidth: sceneTexture.width,
            sourceHeight: sceneTexture.height,
            displayWidth: displayWidth,
            displayHeight: displayHeight
          ) else {
        return nil
    }
    let worldComposite: MTLTexture
    if let existing = workspace.worldComposite {
        worldComposite = existing
    } else {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba16Float,
            width: sceneTexture.width,
            height: sceneTexture.height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.hazardTrackingMode = .tracked
        guard let allocated = commandBuffer.device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        allocated.label = "Metallum actual HDR spatial world"
        workspace.worldComposite = allocated
        worldComposite = allocated
    }
    guard let pipeline = ensureActualWorldPresentPipeline(
        device: commandBuffer.device,
        colorFormat: .rgba16Float
    ), let samplers = presentSamplers(device: commandBuffer.device),
       let encoder = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: worldComposite,
        pipeline: pipeline,
        stage: .actualHdrDisplay
       ) else {
        return nil
    }
    if let globalFence {
        encoder.waitForFence(globalFence, before: .fragment)
    }
    encoder.setFragmentTexture(sceneTexture, index: 0)
    encoder.setFragmentTexture(world.bloom, index: 1)
    encoder.setFragmentSamplerState(samplers.nearest, index: 0)
    encoder.setFragmentSamplerState(samplers.linear, index: 1)
    var uniforms = MetallumPresentUniforms(
        mode: 2,
        sourceEncoding: 2,
        diagnosticPattern: 0,
        currentHeadroom: currentHeadroom,
        hdrStrength: hdrStrength,
        bloomStrength: bloomStrength,
        sceneAvailable: 1,
        uiAvailable: 0,
        semanticAvailable: 0
    )
    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    encoder.setFragmentBuffer(world.adaptiveState, offset: 0, index: 1)
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    if let globalFence {
        encoder.updateFence(globalFence, after: .fragment)
    }
    trackedEndEncoding(encoder)
    return worldComposite
}

private struct MetallumClearUniforms {
    var z: Float
    var _padding0: SIMD3<Float>
    var color: SIMD4<Float>
}


private func encodeClearDraw(
    encoder: MTLRenderCommandEncoder,
    pipeline: MTLRenderPipelineState,
    textureWidth: Int,
    textureHeight: Int,
    clearColor: SIMD4<Float>,
    scissorRect: MTLScissorRect,
    depthState: MTLDepthStencilState? = nil,
    clearDepth: Double = 0.0
) {
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(textureWidth),
        height: Double(textureHeight),
        znear: 0.0,
        zfar: 1.0
    ))

    encoder.setScissorRect(scissorRect)
    encoder.setRenderPipelineState(pipeline)

    if let depthState {
        encoder.setDepthStencilState(depthState)
    }

    var uniforms = MetallumClearUniforms(
        z: depthState == nil ? 0.0 : Float(max(0.0, min(clearDepth, 1.0))),
        _padding0: SIMD3<Float>(0.0, 0.0, 0.0),
        color: clearColor
    )

    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setVertexBytes(bytes.baseAddress!, length: bytes.count, index: 1)
    }

    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
}

private func buildClearPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat,
    depthFormat: MTLPixelFormat = .invalid,
    writeColor: Bool = true
) -> MTLRenderPipelineState? {
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .clear)

        guard
            let vertexFunction = library.makeFunction(name: "metallum_clear_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_clear_fs")
        else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            NSLog("[metallum] Failed to create clear shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.depthAttachmentPixelFormat = depthFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false
        descriptor.colorAttachments[0].writeMask = writeColor ? .all : []

        let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        recordBuiltinPipelineCreation(device: device, succeeded: true)
        return pipeline
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog("[metallum] Failed to create clear pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat,
    fragmentName: String = "metallum_sdr_present_fs",
    vertexName: String = "metallum_present_vs"
) -> MTLRenderPipelineState? {
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .present)

        guard
            let vertexFunction = library.makeFunction(name: vertexName),
            let fragmentFunction = library.makeFunction(name: fragmentName)
        else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            NSLog("[metallum] Failed to create present shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false

        let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        recordBuiltinPipelineCreation(device: device, succeeded: true)
        return pipeline
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog("[metallum] Failed to create present render pipeline: %@", String(describing: error))
        return nil
    }
}

private func ensureNativeWorldUiPipeline(device: MTLDevice) -> MTLRenderPipelineState? {
    let key = objectAddress(device)
    if let cached = NativeState.nativeWorldUiPipelines[key] {
        return cached
    }
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .present)
        guard
            let vertexFunction = library.makeFunction(name: "metallum_offscreen_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_native_world_ui_fs")
        else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            NSLog("[metallum] Failed to create fused native HDR/UI shader functions")
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = .rgba16Float
        descriptor.colorAttachments[0].isBlendingEnabled = false
        descriptor.colorAttachments[1].pixelFormat = .rgba8Unorm
        descriptor.colorAttachments[1].isBlendingEnabled = false
        let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        recordBuiltinPipelineCreation(device: device, succeeded: true)
        NativeState.nativeWorldUiPipelines[key] = pipeline
        return pipeline
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog("[metallum] Failed to create fused native HDR/UI pipeline: %@", String(describing: error))
        return nil
    }
}

private func ensureActualNativeWorldUiPipeline(device: MTLDevice) -> MTLRenderPipelineState? {
    let key = objectAddress(device)
    if let cached = NativeState.actualNativeWorldUiPipelines[key] {
        return cached
    }
    do {
        let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: .present)
        guard let vertex = library.makeFunction(name: "metallum_offscreen_vs"),
              let fragment = library.makeFunction(name: "metallum_actual_native_world_ui_fs") else {
            recordBuiltinPipelineCreation(device: device, succeeded: false)
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertex
        descriptor.fragmentFunction = fragment
        descriptor.colorAttachments[0].pixelFormat = .rgba16Float
        descriptor.colorAttachments[0].isBlendingEnabled = false
        descriptor.colorAttachments[1].pixelFormat = .rgba8Unorm
        descriptor.colorAttachments[1].isBlendingEnabled = false
        let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        recordBuiltinPipelineCreation(device: device, succeeded: true)
        NativeState.actualNativeWorldUiPipelines[key] = pipeline
        return pipeline
    } catch {
        recordBuiltinPipelineCreation(device: device, succeeded: false)
        NSLog("[metallum] Failed to create actual HDR/UI pipeline: %@", String(describing: error))
        return nil
    }
}

private func ensureWorldPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    let key = PresentPipelineKey(
        deviceAddress: objectAddress(device),
        colorFormat: colorFormat
    )
    if let cached = NativeState.worldPresentPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(
        device: device,
        colorFormat: colorFormat,
        fragmentName: "metallum_spatial_world_fs",
        vertexName: "metallum_offscreen_vs"
    )
    if let pipeline {
        NativeState.worldPresentPipelines[key] = pipeline
    }
    return pipeline
}

private func ensureActualWorldPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    let key = PresentPipelineKey(deviceAddress: objectAddress(device), colorFormat: colorFormat)
    if let cached = NativeState.actualWorldPresentPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(
        device: device,
        colorFormat: colorFormat,
        fragmentName: "metallum_actual_spatial_world_fs",
        vertexName: "metallum_offscreen_vs"
    )
    if let pipeline {
        NativeState.actualWorldPresentPipelines[key] = pipeline
    }
    return pipeline
}

private func ensureSpatialPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    let key = PresentPipelineKey(
        deviceAddress: objectAddress(device),
        colorFormat: colorFormat
    )
    if let cached = NativeState.spatialPresentPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(
        device: device,
        colorFormat: colorFormat,
        fragmentName: "metallum_spatial_present_fs"
    )
    if let pipeline {
        NativeState.spatialPresentPipelines[key] = pipeline
    }
    return pipeline
}

private func ensureSpatialScreenshotPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    let key = PresentPipelineKey(
        deviceAddress: objectAddress(device),
        colorFormat: colorFormat
    )
    if let cached = NativeState.spatialScreenshotPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(
        device: device,
        colorFormat: colorFormat,
        fragmentName: "metallum_spatial_screenshot_fs",
        vertexName: "metallum_offscreen_vs"
    )
    if let pipeline {
        NativeState.spatialScreenshotPipelines[key] = pipeline
    }
    return pipeline
}

private func ensurePresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    let key = PresentPipelineKey(
        deviceAddress: objectAddress(device),
        colorFormat: colorFormat
    )
    if let cached = NativeState.presentPipelines[key] {
        return cached
    }

    let pipeline = buildPresentPipeline(device: device, colorFormat: colorFormat)
    if let pipeline {
        NativeState.presentPipelines[key] = pipeline
    }
    return pipeline
}

private func ensureLegacyHdrPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    let key = PresentPipelineKey(deviceAddress: objectAddress(device), colorFormat: colorFormat)
    if let cached = NativeState.legacyHdrPresentPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(
        device: device,
        colorFormat: colorFormat,
        fragmentName: "metallum_present_fs"
    )
    if let pipeline {
        NativeState.legacyHdrPresentPipelines[key] = pipeline
    }
    return pipeline
}

private func ensureActualHdrPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    let key = PresentPipelineKey(deviceAddress: objectAddress(device), colorFormat: colorFormat)
    if let cached = NativeState.actualHdrPresentPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(
        device: device,
        colorFormat: colorFormat,
        fragmentName: "metallum_actual_hdr_present_fs"
    )
    if let pipeline {
        NativeState.actualHdrPresentPipelines[key] = pipeline
    }
    return pipeline
}

private func ensureActualHdrUiOnlyPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    let key = PresentPipelineKey(deviceAddress: objectAddress(device), colorFormat: colorFormat)
    if let cached = NativeState.actualHdrUiOnlyPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(
        device: device,
        colorFormat: colorFormat,
        fragmentName: "metallum_actual_hdr_ui_only_fs"
    )
    if let pipeline {
        NativeState.actualHdrUiOnlyPipelines[key] = pipeline
    }
    return pipeline
}

private func ensureActualHdrLinearUiOnlyPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    let key = PresentPipelineKey(deviceAddress: objectAddress(device), colorFormat: colorFormat)
    if let cached = NativeState.actualHdrLinearUiOnlyPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(
        device: device,
        colorFormat: colorFormat,
        fragmentName: "metallum_actual_hdr_linear_ui_only_fs"
    )
    if let pipeline {
        NativeState.actualHdrLinearUiOnlyPipelines[key] = pipeline
    }
    return pipeline
}

private func buildPresentSampler(device: MTLDevice, filter: MTLSamplerMinMagFilter) -> MTLSamplerState? {
    let descriptor = MTLSamplerDescriptor()
    descriptor.minFilter = filter
    descriptor.magFilter = filter
    descriptor.mipFilter = .notMipmapped
    descriptor.sAddressMode = .clampToEdge
    descriptor.tAddressMode = .clampToEdge
    return device.makeSamplerState(descriptor: descriptor)
}

private func presentSamplers(device: MTLDevice) -> (nearest: MTLSamplerState, linear: MTLSamplerState)? {
    let key = objectAddress(device)
    guard
        let nearest = NativeState.presentNearestSamplers[key],
        let linear = NativeState.presentLinearSamplers[key]
    else {
        return nil
    }
    return (nearest, linear)
}

private func ensureClearColorDepthPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat, _ depthFormat: MTLPixelFormat, _ writeColor: Bool = true) -> MTLRenderPipelineState? {
    let key = PipelineVariantKey(deviceAddress: objectAddress(device), colorFormat: colorFormat, depthFormat: depthFormat, writeColor: writeColor)
    if let cached = NativeState.clearPipelines[key] {
        return cached
    }
    let pipeline = buildClearPipeline(device: device, colorFormat: colorFormat, depthFormat: depthFormat, writeColor: writeColor)
    if let pipeline {
        NativeState.clearPipelines[key] = pipeline
    }
    return pipeline
}

private func probeBuiltinPipelineCacheHits(device: MTLDevice) -> Int {
    var hits = 0
    hits += ensureSodiumLightPatchPipeline(device: device) == nil ? 0 : 1
    hits += ensurePresentPipeline(device: device, colorFormat: .bgra8Unorm) == nil ? 0 : 1
    hits += ensureClearColorDepthPipeline(device, .bgra8Unorm, .depth32Float) == nil ? 0 : 1
    hits += ensureClearColorDepthPipeline(device, .rgba8Unorm, .depth32Float) == nil ? 0 : 1
    hits += ensureClearColorDepthPipeline(device, .bgra8Unorm, .invalid) == nil ? 0 : 1
    return hits
}

private func removePipelines(
    _ pipelines: inout [PresentPipelineKey: MTLRenderPipelineState],
    deviceAddress: UInt
) {
    pipelines = pipelines.filter { $0.key.deviceAddress != deviceAddress }
}

private func purgeLegacyHdrGeneration(deviceAddress: UInt) {
    NativeState.hdrPipelines.removeValue(forKey: deviceAddress)
    removePipelines(&NativeState.legacyHdrPresentPipelines, deviceAddress: deviceAddress)
    removePipelines(&NativeState.worldPresentPipelines, deviceAddress: deviceAddress)
    NativeState.nativeWorldUiPipelines.removeValue(forKey: deviceAddress)
    NativeState.hdrFallbackAdaptiveStates.removeValue(forKey: deviceAddress)
    NativeState.hdrFallbackDepthTextures.removeValue(forKey: deviceAddress)
}

private func purgeActualHdrGeneration(deviceAddress: UInt) {
    NativeState.actualHdrPipelines.removeValue(forKey: deviceAddress)
    removePipelines(&NativeState.actualHdrPresentPipelines, deviceAddress: deviceAddress)
    removePipelines(&NativeState.actualHdrUiOnlyPipelines, deviceAddress: deviceAddress)
    removePipelines(&NativeState.actualHdrLinearUiOnlyPipelines, deviceAddress: deviceAddress)
    removePipelines(&NativeState.actualWorldPresentPipelines, deviceAddress: deviceAddress)
    NativeState.actualNativeWorldUiPipelines.removeValue(forKey: deviceAddress)
}

private func prepareRendererGeneration(
    device: MTLDevice,
    snapshot: MetallumRendererFrameStateSnapshot
) -> Bool {
    let key = objectAddress(device)
    if NativeState.preparedRendererGenerations[key] == snapshot.rendererGenerationId {
        return true
    }

    let shaderState = builtinShaderState(device: device)
    shaderState.withLock { shaderState.generationWarmupInProgress = true }
    defer { shaderState.withLock { shaderState.generationWarmupInProgress = false } }

    // Resolution-dependent HDR resources never cross renderer generations.
    NativeState.hdrWorkspaces.removeValue(forKey: key)
    NativeState.spatialWorkspaces.removeValue(forKey: key)
    let spatialEnabled = snapshot.featureMask & MetallumFrameStateAbiV3.spatialBit != 0
    if !spatialEnabled {
        removePipelines(&NativeState.spatialPresentPipelines, deviceAddress: key)
        removePipelines(&NativeState.spatialScreenshotPipelines, deviceAddress: key)
        NativeState.menuBlurPipelines.removeValue(forKey: key)
    }
    let prepared: Bool
    if snapshot.outputMode == 0 {
        // Both SDR generations retain only the compact SDR present contract.
        purgeLegacyHdrGeneration(deviceAddress: key)
        purgeActualHdrGeneration(deviceAddress: key)
        removePipelines(&NativeState.spatialPresentPipelines, deviceAddress: key)
        removePipelines(&NativeState.spatialScreenshotPipelines, deviceAddress: key)
        if snapshot.renderContractMode == 0 {
            NativeState.uiBackdropPipelines.removeValue(forKey: key)
        }
        prepared = ensurePresentPipeline(device: device, colorFormat: .bgra8Unorm) != nil
            && (snapshot.renderContractMode == 0 || ensureUiBackdropPipelines(device: device) != nil)
    } else if snapshot.renderContractMode != 0 {
        // A METALLUM generation owns no semantic/inferred-reconstruction PSO.
        purgeLegacyHdrGeneration(deviceAddress: key)
        prepared = ensureActualHdrPipelines(device: device) != nil
            && ensureActualHdrPresentPipeline(device: device, colorFormat: .rgba16Float) != nil
            && ensureActualHdrUiOnlyPipeline(device: device, colorFormat: .rgba16Float) != nil
            && ensureActualHdrLinearUiOnlyPipeline(device: device, colorFormat: .rgba16Float) != nil
            && ensureActualNativeWorldUiPipeline(device: device) != nil
            && ensureUiBackdropPipelines(device: device) != nil
            && ensureMenuBlurPipelines(device: device) != nil
            && (!spatialEnabled || (
                ensureActualWorldPresentPipeline(device: device, colorFormat: .rgba16Float) != nil
                && ensureSpatialPresentPipeline(device: device, colorFormat: .rgba16Float) != nil
                && ensureSpatialScreenshotPipeline(device: device, colorFormat: .rgba8Unorm) != nil
            ))
    } else {
        purgeActualHdrGeneration(deviceAddress: key)
        prepared = ensureHdrPipelines(device: device) != nil
            && ensureLegacyHdrPresentPipeline(device: device, colorFormat: .rgba16Float) != nil
            && ensureNativeWorldUiPipeline(device: device) != nil
            && ensureUiBackdropPipelines(device: device) != nil
            && ensureMenuBlurPipelines(device: device) != nil
            && ensureHdrFallbackAdaptiveState(device: device) != nil
            && ensureHdrFallbackDepthTexture(device: device) != nil
            && (!spatialEnabled || (
                ensureWorldPresentPipeline(device: device, colorFormat: .rgba16Float) != nil
                && ensureSpatialPresentPipeline(device: device, colorFormat: .rgba16Float) != nil
                && ensureSpatialScreenshotPipeline(device: device, colorFormat: .rgba8Unorm) != nil
            ))
    }
    if prepared {
        NativeState.preparedRendererGenerations[key] = snapshot.rendererGenerationId
    }
    return prepared
}

private enum MetallumFrameStateAbiV3 {
    static let version: UInt32 = 3
    static let packetBytes = 848
    static let knownFeatureBits: UInt64 = 0b111
    static let knownResetBits: UInt64 = 0x1fff
    static let spatialBit: UInt64 = 1
    static let temporalBit: UInt64 = 1 << 1
    static let interpolationBit: UInt64 = 1 << 2
}

private struct MetallumRendererFrameStateSnapshot {
    let frameContractVersion: UInt32
    let frameGraphVersion: UInt32
    let frameId: UInt64
    let submitIndex: UInt64
    let rendererGenerationId: UInt64
    let historyGeneration: UInt64
    let renderContractGenerationId: UInt64
    let lightingGenerationId: UInt64
    let outputGenerationId: UInt64
    let worldIdentity: UInt64
    let dimensionIdentity: UInt64
    let resetMask: UInt64
    let renderContractMode: UInt32
    let lightingModel: UInt32
    let outputMode: UInt32
    let executorKind: UInt32
    let lightingPreset: UInt32
    let featureMask: UInt64
    let renderWidth: UInt32
    let renderHeight: UInt32
    let displayWidth: UInt32
    let displayHeight: UInt32
    let inFlightSlot: UInt32
    let deltaSeconds: Float
    let nearPlane: Float
    let farPlane: Float
    let jitterX: Float
    let jitterY: Float
    let exposure: Float
    let preExposure: Float
    let currentDisplayHeadroom: Float
    let potentialDisplayHeadroom: Float
    let baseResourceBytes: UInt64
    let materialResourceBytes: UInt64
    let hdrResourceBytes: UInt64
    let advancedLightingResourceBytes: UInt64
    let upscaleResourceBytes: UInt64
    let interpolationResourceBytes: UInt64
    let diagnosticResourceBytes: UInt64
    let lightCount: UInt32
    let advancedPassCount: UInt32
    let advancedEncoderCount: UInt32
    let advancedPsoCount: UInt32
    let advancedWorkQueueCount: UInt32
    let advancedDispatchCount: UInt32
    let advancedUploadBytes: UInt64
    let currentCameraPosition: SIMD3<Double>
    let previousCameraPosition: SIMD3<Double>
    let currentView: simd_float4x4
    let currentProjection: simd_float4x4
    let currentUnjitteredView: simd_float4x4
    let currentUnjitteredProjection: simd_float4x4
    let previousView: simd_float4x4
    let previousProjection: simd_float4x4
    let previousUnjitteredView: simd_float4x4
    let previousUnjitteredProjection: simd_float4x4

    var report: [String: Any] {
        let upscaleMode: String
        if featureMask & MetallumFrameStateAbiV3.spatialBit != 0 {
            upscaleMode = "spatial"
        } else if featureMask & MetallumFrameStateAbiV3.temporalBit != 0 {
            upscaleMode = "temporal"
        } else {
            upscaleMode = "native"
        }
        return [
            "frame_contract_version": frameContractVersion,
            "frame_graph_version": frameGraphVersion,
            "frame_id": frameId,
            "submit_index": submitIndex,
            "in_flight_slot": inFlightSlot,
            "renderer_generation_id": rendererGenerationId,
            "history_generation": historyGeneration,
            "render_contract_generation_id": renderContractGenerationId,
            "lighting_generation_id": lightingGenerationId,
            "output_generation_id": outputGenerationId,
            "world_identity": worldIdentity,
            "dimension_identity": dimensionIdentity,
            "history_reset_mask": resetMask,
            "resolved_render_contract": renderContractMode == 0 ? "legacy" : "metallum",
            "resolved_lighting_model": lightingModel == 0 ? "vanilla" : "advanced",
            "resolved_output_mode": outputMode == 0 ? "sdr" : "hdr",
            "resolved_upscale_mode": upscaleMode,
            "resolved_interpolation_mode": featureMask & MetallumFrameStateAbiV3.interpolationBit == 0
                ? "off" : "frame_interpolation",
            "lighting_preset": ["performance", "balanced", "ultra"][Int(lightingPreset)],
            "executor": executorKind == 0 ? "metal3" : "metal4",
            "feature_mask": featureMask,
            "delta_seconds": deltaSeconds,
            "near_plane": nearPlane,
            "far_plane": farPlane,
            "jitter_pixels": [jitterX, jitterY],
            "exposure": exposure,
            "pre_exposure": preExposure,
            "display_headroom": [currentDisplayHeadroom, potentialDisplayHeadroom],
            "render_width": renderWidth,
            "render_height": renderHeight,
            "display_width": displayWidth,
            "display_height": displayHeight,
            "resource_bytes": [
                "base": baseResourceBytes,
                "material": materialResourceBytes,
                "hdr": hdrResourceBytes,
                "advanced_lighting": advancedLightingResourceBytes,
                "upscale": upscaleResourceBytes,
                "interpolation": interpolationResourceBytes,
                "diagnostic": diagnosticResourceBytes
            ],
            "temporal_diagnostics": [
                "resource_bytes": diagnosticResourceBytes,
                "motion_bytes": diagnosticResourceBytes == 0
                    ? UInt64(0) : UInt64(renderWidth) * UInt64(renderHeight) * 4 * 3,
                "reactive_bytes": diagnosticResourceBytes == 0
                    ? UInt64(0) : UInt64(renderWidth) * UInt64(renderHeight) * 3,
                "pass_count": diagnosticResourceBytes == 0 ? 0 : 1,
                "encoder_count": diagnosticResourceBytes == 0 ? 0 : 1,
                "pso_count": NativeState.temporalDiagnosticPipelines.isEmpty ? 0 : 1
            ],
            "advanced_lighting_work": [
                "light_count": lightCount,
                "pass_count": advancedPassCount,
                "encoder_count": advancedEncoderCount,
                "pso_count": advancedPsoCount,
                "work_queue_count": advancedWorkQueueCount,
                "dispatch_count": advancedDispatchCount,
                "upload_bytes": advancedUploadBytes
            ]
        ]
    }
}

private final class MetallumRendererFrameStateStore: @unchecked Sendable {
    private let lock = NSLock()
    private var current: MetallumRendererFrameStateSnapshot?

    func update(_ snapshot: MetallumRendererFrameStateSnapshot) {
        lock.lock()
        current = snapshot
        lock.unlock()
    }

    func snapshot() -> MetallumRendererFrameStateSnapshot? {
        lock.lock()
        let value = current
        lock.unlock()
        return value
    }
}

private struct MetallumTemporalDiagnosticUniforms {
    var currentView: simd_float4x4
    var currentProjection: simd_float4x4
    var inverseCurrentView: simd_float4x4
    var inverseCurrentProjection: simd_float4x4
    var previousView: simd_float4x4
    var previousProjection: simd_float4x4
    var currentCameraPosition: SIMD4<Float>
    var previousCameraPosition: SIMD4<Float>
    var renderExtent: SIMD2<Float>
    var resetMask: UInt32
    var reserved: UInt32 = 0
}

@_cdecl("metallum_encode_temporal_diagnostics_v1")
public func metallum_encode_temporal_diagnostics_v1(
    _ commandBuffer: MTLCommandBuffer,
    _ depthTexture: MTLTexture,
    _ motionTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
    _ globalFence: MTLFence
) -> Int32 {
    autoreleasepool {
        // Renderer generation admission and the first valid camera publication
        // are separate events. A world hook may therefore arrive during the
        // short transition with no usable frame packet; skip that frame without
        // disabling the opt-in profile.
        guard let frame = NativeState.rendererFrameState.snapshot(),
              frame.diagnosticResourceBytes > 0 else { return 0 }
        guard depthTexture.pixelFormat == .depth32Float,
              motionTexture.pixelFormat == .rg16Float,
              reactiveTexture.pixelFormat == .r8Unorm else { return -2 }
        guard depthTexture.width == Int(frame.renderWidth),
              depthTexture.height == Int(frame.renderHeight),
              motionTexture.width == depthTexture.width,
              motionTexture.height == depthTexture.height,
              reactiveTexture.width == depthTexture.width,
              reactiveTexture.height == depthTexture.height else { return 0 }
        guard let pipeline = ensureTemporalDiagnosticPipeline(device: commandBuffer.device) else { return -3 }

        let currentView = frame.currentView
        let currentProjection = frame.currentProjection
        var uniforms = MetallumTemporalDiagnosticUniforms(
            currentView: currentView,
            currentProjection: currentProjection,
            inverseCurrentView: currentView.inverse,
            inverseCurrentProjection: currentProjection.inverse,
            previousView: frame.previousView,
            previousProjection: frame.previousProjection,
            currentCameraPosition: SIMD4(
                Float(frame.currentCameraPosition[0]),
                Float(frame.currentCameraPosition[1]),
                Float(frame.currentCameraPosition[2]),
                0
            ),
            previousCameraPosition: SIMD4(
                Float(frame.previousCameraPosition[0]),
                Float(frame.previousCameraPosition[1]),
                Float(frame.previousCameraPosition[2]),
                0
            ),
            renderExtent: SIMD2(Float(frame.renderWidth), Float(frame.renderHeight)),
            resetMask: UInt32(truncatingIfNeeded: frame.resetMask)
        )
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = motionTexture
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[1].texture = reactiveTexture
        pass.colorAttachments[1].loadAction = .dontCare
        pass.colorAttachments[1].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else { return -4 }
        encoder.label = "Metallum temporal camera-motion diagnostic"
        encoder.waitForFence(globalFence, before: .fragment)
        encoder.setViewport(MTLViewport(
            originX: 0,
            originY: 0,
            width: Double(frame.renderWidth),
            height: Double(frame.renderHeight),
            znear: 0,
            zfar: 1
        ))
        encoder.setScissorRect(MTLScissorRect(
            x: 0,
            y: 0,
            width: Int(frame.renderWidth),
            height: Int(frame.renderHeight)
        ))
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(depthTexture, index: 0)
        encoder.setFragmentBytes(
            &uniforms,
            length: MemoryLayout<MetallumTemporalDiagnosticUniforms>.stride,
            index: 0
        )
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        // Main depth and all renderer textures use untracked hazards. Carry the
        // read dependency through the shared fence before the following UI
        // depth clear; the private outputs have no same-frame consumers.
        encoder.updateFence(globalFence, after: .fragment)
        encoder.endEncoding()
        return 1
    }
}

private func parseFrameStateV3(
    _ packet: UnsafeRawPointer?,
    _ byteSize: UInt64
) -> (Int32, MetallumRendererFrameStateSnapshot?) {
    guard let packet,
          byteSize == UInt64(MetallumFrameStateAbiV3.packetBytes) else {
        return (-1, nil)
    }
    let reader = MetallumFrameGraphPacketReader(
        bytes: UnsafeRawBufferPointer(start: packet, count: MetallumFrameStateAbiV3.packetBytes)
    )
    guard let version = reader.uint32(at: 0),
          let declaredBytes = reader.uint32(at: 4),
          let frameContractVersion = reader.uint32(at: 8),
          let frameGraphVersion = reader.uint32(at: 12) else {
        return (-1, nil)
    }
    guard version == MetallumFrameStateAbiV3.version else { return (-2, nil) }
    guard declaredBytes == UInt32(MetallumFrameStateAbiV3.packetBytes),
          frameContractVersion > 0, frameGraphVersion > 0 else {
        return (-3, nil)
    }
    guard let frameId = reader.uint64(at: 16),
          let submitIndex = reader.uint64(at: 24),
          let rendererGenerationId = reader.uint64(at: 32),
          let historyGeneration = reader.uint64(at: 40),
          let renderContractGenerationId = reader.uint64(at: 48),
          let lightingGenerationId = reader.uint64(at: 56),
          let outputGenerationId = reader.uint64(at: 64),
          let worldIdentity = reader.uint64(at: 72),
          let dimensionIdentity = reader.uint64(at: 80),
          let resetMask = reader.uint64(at: 88),
          let featureMask = reader.uint64(at: 96),
          let renderContractMode = reader.uint32(at: 104),
          let lightingModel = reader.uint32(at: 108),
          let outputMode = reader.uint32(at: 112),
          let executorKind = reader.uint32(at: 116),
          let lightingPreset = reader.uint32(at: 120),
          let renderWidth = reader.uint32(at: 124),
          let renderHeight = reader.uint32(at: 128),
          let displayWidth = reader.uint32(at: 132),
          let displayHeight = reader.uint32(at: 136),
          let inFlightSlot = reader.uint32(at: 140),
          let reservedFlags = reader.uint32(at: 144),
          let deltaSeconds = reader.float32(at: 148),
          let nearPlane = reader.float32(at: 152),
          let farPlane = reader.float32(at: 156),
          let jitterX = reader.float32(at: 160),
          let jitterY = reader.float32(at: 164),
          let exposure = reader.float32(at: 168),
          let preExposure = reader.float32(at: 172),
          let currentDisplayHeadroom = reader.float32(at: 176),
          let potentialDisplayHeadroom = reader.float32(at: 180),
          let reservedFloat = reader.float32(at: 184),
          let baseResourceBytes = reader.uint64(at: 192),
          let materialResourceBytes = reader.uint64(at: 200),
          let hdrResourceBytes = reader.uint64(at: 208),
          let advancedLightingResourceBytes = reader.uint64(at: 216),
          let upscaleResourceBytes = reader.uint64(at: 224),
          let interpolationResourceBytes = reader.uint64(at: 232),
          let diagnosticResourceBytes = reader.uint64(at: 240),
          let lightCount = reader.uint32(at: 248),
          let advancedPassCount = reader.uint32(at: 252),
          let advancedEncoderCount = reader.uint32(at: 256),
          let advancedPsoCount = reader.uint32(at: 260),
          let advancedWorkQueueCount = reader.uint32(at: 264),
          let advancedDispatchCount = reader.uint32(at: 268),
          let advancedUploadBytes = reader.uint64(at: 272) else {
        return (-1, nil)
    }
    guard renderContractMode <= 1, lightingModel <= 1, outputMode <= 1, executorKind <= 1,
          lightingPreset <= 2, reservedFlags == 0, reservedFloat == 0,
          featureMask & ~MetallumFrameStateAbiV3.knownFeatureBits == 0,
          resetMask & ~MetallumFrameStateAbiV3.knownResetBits == 0,
          featureMask & MetallumFrameStateAbiV3.spatialBit == 0
            || featureMask & MetallumFrameStateAbiV3.temporalBit == 0,
          renderWidth > 0, renderHeight > 0, displayWidth > 0, displayHeight > 0 else {
        return (-4, nil)
    }
    guard inFlightSlot < 3, submitIndex % 3 == UInt64(inFlightSlot),
          deltaSeconds.isFinite, nearPlane.isFinite, farPlane.isFinite,
          jitterX.isFinite, jitterY.isFinite, exposure.isFinite, preExposure.isFinite,
          currentDisplayHeadroom.isFinite, potentialDisplayHeadroom.isFinite,
          deltaSeconds >= 0,
          nearPlane > 0, farPlane > nearPlane, abs(jitterX) <= 0.5, abs(jitterY) <= 0.5,
          exposure > 0, preExposure > 0, currentDisplayHeadroom >= 1,
          potentialDisplayHeadroom >= currentDisplayHeadroom else { return (-4, nil) }
    let signedLongMax = UInt64(Int64.max)
    let signedIntMax = UInt32(Int32.max)
    guard frameId <= signedLongMax, submitIndex <= signedLongMax,
          rendererGenerationId <= signedLongMax, historyGeneration <= signedLongMax,
          renderContractGenerationId <= signedLongMax, lightingGenerationId <= signedLongMax,
          outputGenerationId <= signedLongMax, worldIdentity <= signedLongMax,
          dimensionIdentity <= signedLongMax, baseResourceBytes <= signedLongMax,
          materialResourceBytes <= signedLongMax, hdrResourceBytes <= signedLongMax,
          advancedLightingResourceBytes <= signedLongMax, upscaleResourceBytes <= signedLongMax,
          interpolationResourceBytes <= signedLongMax, diagnosticResourceBytes <= signedLongMax,
          advancedUploadBytes <= signedLongMax, lightCount <= signedIntMax,
          advancedPassCount <= signedIntMax, advancedEncoderCount <= signedIntMax,
          advancedPsoCount <= signedIntMax, advancedWorkQueueCount <= signedIntMax,
          advancedDispatchCount <= signedIntMax else { return (-4, nil) }

    guard let currentCameraPosition = reader.float64x3(at: 280),
          let previousCameraPosition = reader.float64x3(at: 304),
          let currentView = reader.floatMatrix4x4(at: 328),
          let currentProjection = reader.floatMatrix4x4(at: 392),
          let currentUnjitteredView = reader.floatMatrix4x4(at: 456),
          let currentUnjitteredProjection = reader.floatMatrix4x4(at: 520),
          let previousView = reader.floatMatrix4x4(at: 584),
          let previousProjection = reader.floatMatrix4x4(at: 648),
          let previousUnjitteredView = reader.floatMatrix4x4(at: 712),
          let previousUnjitteredProjection = reader.floatMatrix4x4(at: 776) else {
        return (-4, nil)
    }
    if renderContractMode == 0 && lightingModel == 1 { return (-5, nil) }
    if renderContractMode == 0 && materialResourceBytes != 0 { return (-5, nil) }
    if lightingModel == 0 && (advancedLightingResourceBytes != 0 || lightCount != 0
        || advancedPassCount != 0 || advancedEncoderCount != 0 || advancedPsoCount != 0
        || advancedWorkQueueCount != 0 || advancedDispatchCount != 0
        || advancedUploadBytes != 0) {
        return (-5, nil)
    }
    if outputMode == 0 && hdrResourceBytes != 0 { return (-6, nil) }
    if featureMask & (MetallumFrameStateAbiV3.spatialBit | MetallumFrameStateAbiV3.temporalBit) == 0
        && upscaleResourceBytes != 0 {
        return (-7, nil)
    }
    if featureMask & MetallumFrameStateAbiV3.interpolationBit == 0
        && interpolationResourceBytes != 0 {
        return (-8, nil)
    }
    return (1, MetallumRendererFrameStateSnapshot(
        frameContractVersion: frameContractVersion,
        frameGraphVersion: frameGraphVersion,
        frameId: frameId,
        submitIndex: submitIndex,
        rendererGenerationId: rendererGenerationId,
        historyGeneration: historyGeneration,
        renderContractGenerationId: renderContractGenerationId,
        lightingGenerationId: lightingGenerationId,
        outputGenerationId: outputGenerationId,
        worldIdentity: worldIdentity,
        dimensionIdentity: dimensionIdentity,
        resetMask: resetMask,
        renderContractMode: renderContractMode,
        lightingModel: lightingModel,
        outputMode: outputMode,
        executorKind: executorKind,
        lightingPreset: lightingPreset,
        featureMask: featureMask,
        renderWidth: renderWidth,
        renderHeight: renderHeight,
        displayWidth: displayWidth,
        displayHeight: displayHeight,
        inFlightSlot: inFlightSlot,
        deltaSeconds: deltaSeconds,
        nearPlane: nearPlane,
        farPlane: farPlane,
        jitterX: jitterX,
        jitterY: jitterY,
        exposure: exposure,
        preExposure: preExposure,
        currentDisplayHeadroom: currentDisplayHeadroom,
        potentialDisplayHeadroom: potentialDisplayHeadroom,
        baseResourceBytes: baseResourceBytes,
        materialResourceBytes: materialResourceBytes,
        hdrResourceBytes: hdrResourceBytes,
        advancedLightingResourceBytes: advancedLightingResourceBytes,
        upscaleResourceBytes: upscaleResourceBytes,
        interpolationResourceBytes: interpolationResourceBytes,
        diagnosticResourceBytes: diagnosticResourceBytes,
        lightCount: lightCount,
        advancedPassCount: advancedPassCount,
        advancedEncoderCount: advancedEncoderCount,
        advancedPsoCount: advancedPsoCount,
        advancedWorkQueueCount: advancedWorkQueueCount,
        advancedDispatchCount: advancedDispatchCount,
        advancedUploadBytes: advancedUploadBytes,
        currentCameraPosition: currentCameraPosition,
        previousCameraPosition: previousCameraPosition,
        currentView: currentView,
        currentProjection: currentProjection,
        currentUnjitteredView: currentUnjitteredView,
        currentUnjitteredProjection: currentUnjitteredProjection,
        previousView: previousView,
        previousProjection: previousProjection,
        previousUnjitteredView: previousUnjitteredView,
        previousUnjitteredProjection: previousUnjitteredProjection
    ))
}

@_cdecl("metallum_validate_frame_state_v3")
public func metallum_validate_frame_state_v3(
    _ packet: UnsafeRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    parseFrameStateV3(packet, byteSize).0
}

@_cdecl("metallum_set_frame_state_v3")
public func metallum_set_frame_state_v3(
    _ packet: UnsafeRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    let (status, snapshot) = parseFrameStateV3(packet, byteSize)
    if status == 1, let snapshot {
        for device in NativeState.initializedDevices.values {
            guard prepareRendererGeneration(device: device, snapshot: snapshot) else {
                return -7
            }
        }
        NativeState.rendererFrameState.update(snapshot)
    }
    return status
}

private struct MetallumLightingBatchHeaderV1 {
    let declaredBytes: UInt32
    let lightCount: UInt32
    let inFlightSlot: UInt32
    let flags: UInt32
    let frameId: UInt64
    let submitIndex: UInt64
    let lightingGeneration: UInt64
}

private func parseLightingBatchV1(
    _ packet: UnsafeRawPointer?,
    byteSize: UInt64,
    context: MetallumLightingContext,
    frame: MetallumRendererFrameStateSnapshot
) -> (Int32, MetallumLightingBatchHeaderV1?) {
    guard let packet,
          byteSize >= UInt64(MetallumLightingAbiV1.uploadHeaderBytes),
          byteSize <= UInt64(Int.max) else {
        return (-1, nil)
    }
    let reader = MetallumFrameGraphPacketReader(
        bytes: UnsafeRawBufferPointer(start: packet, count: Int(byteSize))
    )
    guard let magic = reader.uint32(at: 0),
          let version = reader.uint32(at: 4),
          let declaredBytes = reader.uint32(at: 8),
          let headerBytes = reader.uint32(at: 12),
          let lightStride = reader.uint32(at: 16),
          let lightCount = reader.uint32(at: 20),
          let inFlightSlot = reader.uint32(at: 24),
          let flags = reader.uint32(at: 28),
          let frameId = reader.uint64(at: 32),
          let submitIndex = reader.uint64(at: 40),
          let lightingGeneration = reader.uint64(at: 48),
          let reserved = reader.uint64(at: 56) else {
        return (-1, nil)
    }
    guard magic == MetallumLightingAbiV1.batchMagic,
          version == MetallumLightingAbiV1.version else {
        return (-2, nil)
    }
    guard headerBytes == UInt32(MetallumLightingAbiV1.uploadHeaderBytes),
          lightStride == UInt32(MetallumLightingAbiV1.gpuLightBytes),
          flags & MetallumLightingAbiV1.orderedBatchFlag != 0,
          flags & ~MetallumLightingAbiV1.knownBatchFlags == 0,
          reserved == 0 else {
        return (-3, nil)
    }
    let expectedBytes = UInt64(MetallumLightingAbiV1.uploadHeaderBytes)
        + UInt64(lightCount) * UInt64(MetallumLightingAbiV1.gpuLightBytes)
    guard expectedBytes == byteSize,
          declaredBytes == UInt32(byteSize),
          lightCount <= context.maxLights else {
        return (-4, nil)
    }
    guard inFlightSlot < UInt32(MetallumLightingAbiV1.ringSlots),
          lightingGeneration == context.generation,
          frameId == frame.frameId,
          submitIndex == frame.submitIndex,
          inFlightSlot == frame.inFlightSlot,
          lightingGeneration == frame.lightingGenerationId,
          lightCount == frame.lightCount else {
        return (-9, nil)
    }
    let generationLow = UInt32(truncatingIfNeeded: lightingGeneration)
    for index in 0..<Int(lightCount) {
        let base = MetallumLightingAbiV1.uploadHeaderBytes
            + index * MetallumLightingAbiV1.gpuLightBytes
        guard let x = reader.float32(at: base),
              let y = reader.float32(at: base + 4),
              let z = reader.float32(at: base + 8),
              let radius = reader.float32(at: base + 12),
              let red = reader.float32(at: base + 16),
              let green = reader.float32(at: base + 20),
              let blue = reader.float32(at: base + 24),
              let intensity = reader.float32(at: base + 28),
              let recordGeneration = reader.uint32(at: base + 44),
              x.isFinite, y.isFinite, z.isFinite, radius.isFinite,
              red.isFinite, green.isFinite, blue.isFinite, intensity.isFinite,
              radius > 0, red >= 0, green >= 0, blue >= 0, intensity >= 0,
              radius <= frame.farPlane,
              recordGeneration == generationLow else {
            return (-8, nil)
        }
    }
    return (1, MetallumLightingBatchHeaderV1(
        declaredBytes: declaredBytes,
        lightCount: lightCount,
        inFlightSlot: inFlightSlot,
        flags: flags,
        frameId: frameId,
        submitIndex: submitIndex,
        lightingGeneration: lightingGeneration
    ))
}

private func parseVoxelBatchV1(
    _ packet: UnsafeRawPointer?,
    byteSize: UInt64,
    context: MetallumVoxelContext
) -> (Int32, MetallumVoxelBatchV1?) {
    guard let packet,
          byteSize >= UInt64(MetallumVoxelAbiV1.headerBytes),
          byteSize <= UInt64(context.stagingBytes),
          byteSize <= UInt64(UInt32.max),
          byteSize <= UInt64(Int.max) else {
        return (-1, nil)
    }
    let reader = MetallumFrameGraphPacketReader(
        bytes: UnsafeRawBufferPointer(start: packet, count: Int(byteSize))
    )
    guard let magic = reader.uint32(at: 0),
          let version = reader.uint32(at: 4),
          let declaredBytes = reader.uint32(at: 8),
          let flags = reader.uint32(at: 12),
          let recordBytes = reader.uint32(at: 16),
          let patchCount = reader.uint32(at: 20),
          let slot = reader.uint32(at: 24),
          let levelCount = reader.uint32(at: 28),
          let lightingGeneration = reader.uint64(at: 32),
          let clipmapGeneration = reader.uint64(at: 40),
          let worldGeneration = reader.uint64(at: 48),
          let frameId = reader.uint64(at: 56),
          let payloadBytes = reader.uint32(at: 64),
          let payloadOffset = reader.uint32(at: 68),
          let scrollSlabs = reader.uint32(at: 72),
          let unloadClears = reader.uint32(at: 76),
          let queueRemaining = reader.uint32(at: 80),
          let oldestAge = reader.uint32(at: 84),
          let coalesced = reader.uint32(at: 88),
          let rejected = reader.uint32(at: 92) else {
        return (-1, nil)
    }
    guard magic == MetallumVoxelAbiV1.magic, version == MetallumVoxelAbiV1.version else {
        return (-2, nil)
    }
    guard flags & ~MetallumVoxelAbiV1.knownFlags == 0,
          recordBytes == UInt32(MetallumVoxelAbiV1.recordBytes),
          patchCount <= context.maxPatchCount,
          slot < UInt32(MetallumVoxelAbiV1.ringSlots),
          levelCount == UInt32(context.levels.count) else {
        return (-3, nil)
    }
    let recordsBytes = UInt64(patchCount) * UInt64(MetallumVoxelAbiV1.recordBytes)
    let expectedPayloadOffset = UInt64(MetallumVoxelAbiV1.headerBytes) + recordsBytes
    guard expectedPayloadOffset <= byteSize,
          payloadOffset == UInt32(expectedPayloadOffset),
          payloadBytes == UInt32(byteSize - expectedPayloadOffset),
          declaredBytes == UInt32(byteSize) else {
        return (-4, nil)
    }
    guard lightingGeneration == context.lightingGeneration,
          clipmapGeneration == context.clipmapGeneration,
          worldGeneration == context.worldGeneration else {
        return (-9, nil)
    }
    let expectedBrickGenerationLow = UInt32(truncatingIfNeeded: clipmapGeneration)
    let expectedBrickGenerationHigh = UInt32(truncatingIfNeeded: clipmapGeneration >> 32)
    var physicalDestinations = Set<UInt64>()
    var logicalDestinations = Set<MetallumVoxelLogicalDestinationKey>()
    var levelPatchCounts = Array(repeating: UInt32(0), count: context.levels.count)
    var levelRecordStarts = Array(repeating: UInt32(0), count: context.levels.count)
    var previousLevel: UInt32 = 0
    for index in 0..<Int(patchCount) {
        let offset = MetallumVoxelAbiV1.headerBytes + index * MetallumVoxelAbiV1.recordBytes
        guard let level = reader.uint32(at: offset),
              let destinationX = reader.uint32(at: offset + 4),
              let destinationY = reader.uint32(at: offset + 8),
              let destinationZ = reader.uint32(at: offset + 12),
              let patchOffset = reader.uint32(at: offset + 16),
              let occupancyBytes = reader.uint32(at: offset + 20),
              let opticalBytes = reader.uint32(at: offset + 24),
              let recordFlags = reader.uint32(at: offset + 28),
              let brickGenerationLow = reader.uint32(at: offset + 32),
              let brickGenerationHigh = reader.uint32(at: offset + 36),
              let logicalX = reader.int32(at: offset + 40),
              let logicalY = reader.int32(at: offset + 44),
              let logicalZ = reader.int32(at: offset + 48),
              let contentStamp = reader.uint32(at: offset + 52),
              level < UInt32(context.levels.count),
              recordFlags == 0, contentStamp != 0,
              brickGenerationLow == expectedBrickGenerationLow,
              brickGenerationHigh == expectedBrickGenerationHigh else {
            return (-6, nil)
        }
        // Per-level indirect dispatches index contiguous runs of records. Rejecting an
        // unsorted packet makes total dispatched groups exactly equal dirty patch count.
        if index > 0, level < previousLevel { return (-6, nil) }
        previousLevel = level
        let resource = context.levels[Int(level)]
        let baseEdge = Int(MetallumVoxelAbiV1.logicalBrickEdge) / Int(resource.layout.subdivision)
        let expectedOpticalBytes = UInt32(baseEdge * baseEdge * baseEdge)
        guard destinationX < resource.brickDimension,
              destinationY < resource.brickDimension,
              destinationZ < resource.brickDimension,
              occupancyBytes == MetallumVoxelAbiV1.occupancyBytesPerBrick,
              opticalBytes == expectedOpticalBytes,
              patchOffset >= payloadOffset,
              patchOffset.isMultiple(of: 4),
              voxelFloorMod(logicalX, resource.brickDimension) == destinationX,
              voxelFloorMod(logicalY, resource.brickDimension) == destinationY,
              voxelFloorMod(logicalZ, resource.brickDimension) == destinationZ else {
            return (-6, nil)
        }
        let length = UInt64(occupancyBytes) + UInt64(opticalBytes)
        guard UInt64(patchOffset) <= byteSize,
              length <= byteSize - UInt64(patchOffset) else {
            return (-7, nil)
        }
        let physicalKey = UInt64(level) << 48
            | UInt64(destinationX) << 32
            | UInt64(destinationY) << 16
            | UInt64(destinationZ)
        let logicalKey = MetallumVoxelLogicalDestinationKey(
            level: level, x: logicalX, y: logicalY, z: logicalZ
        )
        guard physicalDestinations.insert(physicalKey).inserted,
              logicalDestinations.insert(logicalKey).inserted else {
            return (-6, nil)
        }
        if levelPatchCounts[Int(level)] == 0 {
            levelRecordStarts[Int(level)] = UInt32(index)
        }
        levelPatchCounts[Int(level)] &+= 1
    }
    return (1, MetallumVoxelBatchV1(
        flags: flags, patchCount: patchCount, slot: slot, frameId: frameId,
        lightingGeneration: lightingGeneration, clipmapGeneration: clipmapGeneration,
        worldGeneration: worldGeneration, queueRemaining: queueRemaining, oldestAge: oldestAge,
        coalescedDelta: coalesced, rejectedDelta: rejected, scrollSlabs: scrollSlabs,
        unloadClears: unloadClears, levelPatchCounts: levelPatchCounts,
        levelRecordStarts: levelRecordStarts
    ))
}

private func lightingParamsV1(
    context: MetallumLightingContext,
    frame: MetallumRendererFrameStateSnapshot,
    batch: MetallumLightingBatchHeaderV1
) -> MetallumLightingParamsV1 {
    var viewRotation = frame.currentUnjitteredView
    viewRotation.columns.3 = SIMD4<Float>(0, 0, 0, 1)
    let logarithmicScale = Float(context.clustersZ) / log2(frame.farPlane / frame.nearPlane)
    let logarithmicBias = -log2(frame.nearPlane) * logarithmicScale
    let perClusterCap: UInt32
    switch frame.lightingPreset {
    case 0:
        perClusterCap = MetallumLightingAbiV1.clusterCap
    case 1:
        perClusterCap = MetallumLightingAbiV1.clusterCap
    case 2:
        perClusterCap = MetallumLightingAbiV1.clusterCap
    default:
        preconditionFailure("FrameState admitted an unknown Advanced-lighting preset")
    }
    let candidateLightCap = min(context.maxLights, MetallumLightingAbiV1.maximumLights)
    return MetallumLightingParamsV1(
        viewRotation: viewRotation,
        projection: frame.currentUnjitteredProjection,
        gridAndLightCount: SIMD4(
            context.clustersX,
            context.clustersY,
            context.clustersZ,
            batch.lightCount
        ),
        extentAndClusterCap: SIMD4(
            frame.renderWidth,
            frame.renderHeight,
            perClusterCap,
            context.indexCapacity
        ),
        depth: SIMD4(frame.nearPlane, frame.farPlane, logarithmicScale, logarithmicBias),
        frameIdAndGeneration: SIMD4(
            UInt32(truncatingIfNeeded: batch.frameId),
            UInt32(truncatingIfNeeded: batch.frameId >> 32),
            UInt32(truncatingIfNeeded: batch.lightingGeneration),
            UInt32(truncatingIfNeeded: batch.lightingGeneration >> 32)
        ),
        capacitiesAndFlags: SIMD4(
            context.clusterCount,
            context.maxLights,
            context.indexCapacity,
            MetallumLightingAbiV1.tileSize
        ),
        reserved0: SIMD4(
            0,
            batch.inFlightSlot,
            batch.flags,
            MetallumLightingAbiV1.version
        ),
        reserved1: SIMD4(batch.lightCount, candidateLightCap, 0, 0),
        reserved2: SIMD4(repeating: 0)
    )
}

private func lightingBufferPayloadBytes(_ context: MetallumLightingContext, kind: Int32) -> Int {
    switch kind {
    case 0: Int(context.maxLights) * MetallumLightingAbiV1.gpuLightBytes
    case 1: Int(context.clusterCount) * MetallumLightingAbiV1.clusterHeaderBytes
    case 2: Int(context.indexCapacity) * MetallumLightingAbiV1.lightIndexBytes
    case 3: MetallumLightingAbiV1.paramsBytes
    case 4: MetallumLightingAbiV1.statisticsBytes
    case 5: Int(context.clusterCount) * MetallumLightingAbiV1.clusterScratchBytes
    default: 0
    }
}

private func lightingBuffer(_ context: MetallumLightingContext, kind: Int32) -> MTLBuffer? {
    switch kind {
    case 0: context.gpuLights
    case 1: context.clusterHeaders
    case 2: context.lightIndices
    case 3: context.params
    case 4: context.statistics
    case 5: context.clusterScratch
    default: nil
    }
}

@_cdecl("metallum_lighting_batch_abi_version_v1")
public func metallum_lighting_batch_abi_version_v1() -> UInt32 {
    MetallumLightingAbiV1.version
}

@_cdecl("metallum_lighting_layout_v1")
public func metallum_lighting_layout_v1(
    _ output: UnsafeMutableRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    guard let output, byteSize == 128 else { return -1 }
    output.initializeMemory(as: UInt8.self, repeating: 0, count: 128)
    let values: [UInt32] = [
        MetallumLightingAbiV1.version, 128,
        UInt32(MetallumLightingAbiV1.uploadHeaderBytes),
        UInt32(MetallumLightingAbiV1.gpuLightBytes),
        UInt32(MetallumLightingAbiV1.paramsBytes),
        UInt32(MetallumLightingAbiV1.clusterHeaderBytes),
        UInt32(MetallumLightingAbiV1.clusterScratchBytes),
        UInt32(MetallumLightingAbiV1.lightIndexBytes),
        UInt32(MetallumLightingAbiV1.statisticsBytes),
        UInt32(MetallumLightingAbiV1.ringSlots),
        MetallumLightingAbiV1.tileSize,
        MetallumLightingAbiV1.depthSlices,
        MetallumLightingAbiV1.clusterCap,
        0, 64, 128, 144, 160, 176, 192, 208, 224, 240,
        27, 28, 29, 30,
        UInt32(MetallumLightingAbiV1.guardBytes)
    ]
    for (index, value) in values.enumerated() {
        writeLightingStatsValue(value, to: output, offset: index * 4)
    }
    return 1
}

@_cdecl("metallum_lighting_create_context_v1")
public func metallum_lighting_create_context_v1(
    _ device: MTLDevice?,
    _ generation: UInt64,
    _ maxLights: UInt32,
    _ indexCapacity: UInt32,
    _ clustersX: UInt32,
    _ clustersY: UInt32,
    _ clustersZ: UInt32
) -> UnsafeMutableRawPointer? {
    autoreleasepool {
        guard let device,
              let context = makeLightingContext(
                device: device,
                generation: generation,
                maxLights: maxLights,
                indexCapacity: indexCapacity,
                clustersX: clustersX,
                clustersY: clustersY,
                clustersZ: clustersZ
              ) else {
            return nil
        }
        return Unmanaged.passRetained(context).toOpaque()
    }
}

@_cdecl("metallum_lighting_release_context_v1")
public func metallum_lighting_release_context_v1(_ pointer: UnsafeMutableRawPointer?) {
    guard let pointer else { return }
    Unmanaged<MetallumLightingContext>.fromOpaque(pointer).takeUnretainedValue().retire()
    Unmanaged<MetallumLightingContext>.fromOpaque(pointer).release()
}

@_cdecl("metallum_lighting_context_buffer_v1")
public func metallum_lighting_context_buffer_v1(
    _ pointer: UnsafeMutableRawPointer?,
    _ kind: Int32
) -> UnsafeMutableRawPointer? {
    guard let pointer else { return nil }
    let context = Unmanaged<MetallumLightingContext>.fromOpaque(pointer).takeUnretainedValue()
    return unretainedPointer(lightingBuffer(context, kind: kind))
}

@_cdecl("metallum_lighting_context_buffer_bytes_v1")
public func metallum_lighting_context_buffer_bytes_v1(
    _ pointer: UnsafeMutableRawPointer?,
    _ kind: Int32
) -> UInt64 {
    guard let pointer else { return 0 }
    let context = Unmanaged<MetallumLightingContext>.fromOpaque(pointer).takeUnretainedValue()
    return UInt64(lightingBufferPayloadBytes(context, kind: kind))
}

@_cdecl("metallum_lighting_upload_and_build_v1")
public func metallum_lighting_upload_and_build_v1(
    _ contextPointer: UnsafeMutableRawPointer?,
    _ commandBuffer: MTLCommandBuffer?,
    _ packet: UnsafeRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    autoreleasepool {
        guard let contextPointer,
              let commandBuffer else {
            return -1
        }
        let context = Unmanaged<MetallumLightingContext>
            .fromOpaque(contextPointer)
            .takeUnretainedValue()
        context.noteUploadCall()
        guard objectAddress(commandBuffer.device) == objectAddress(context.device),
              commandBuffer.status == .notEnqueued else {
            context.noteRejectedCall()
            return -10
        }
        guard let frame = NativeState.rendererFrameState.snapshot(),
              frame.renderContractMode == 1,
              frame.lightingModel == 1,
              frame.renderWidth > 0,
              frame.renderHeight > 0,
              (frame.renderWidth + MetallumLightingAbiV1.tileSize - 1)
                / MetallumLightingAbiV1.tileSize == context.clustersX,
              (frame.renderHeight + MetallumLightingAbiV1.tileSize - 1)
                / MetallumLightingAbiV1.tileSize == context.clustersY,
              context.clustersZ == MetallumLightingAbiV1.depthSlices else {
            context.noteRejectedCall()
            return -9
        }
        let (parseStatus, parsedBatch) = parseLightingBatchV1(
            packet,
            byteSize: byteSize,
            context: context,
            frame: frame
        )
        guard parseStatus == 1,
              let batch = parsedBatch,
              let packet else {
            context.noteRejectedCall()
            return parseStatus
        }
        let slotIndex = Int(batch.inFlightSlot)
        let reserveStatus = context.reserve(slot: slotIndex, queue: commandBuffer.commandQueue)
        guard reserveStatus == 1 else {
            return reserveStatus
        }
        let slot = context.slots[slotIndex]
        memcpy(slot.staging.contents(), packet, Int(byteSize))
        var params = lightingParamsV1(context: context, frame: frame, batch: batch)

        let uploadPass = MTLBlitPassDescriptor()
        attachGpuTiming(
            uploadPass,
            commandBuffer: commandBuffer,
            stage: .lightUploadClusterBuild
        )
        guard let upload = trackedMakeBlitCommandEncoder(commandBuffer, descriptor: uploadPass) else {
            context.cancel(slot: slotIndex)
            return -13
        }
        upload.label = "Metallum lighting upload and current-slot clear"
        let lightPayloadBytes = lightingBufferPayloadBytes(context, kind: 0)
        let headerPayloadBytes = lightingBufferPayloadBytes(context, kind: 1)
        let indexPayloadBytes = lightingBufferPayloadBytes(context, kind: 2)
        let scratchPayloadBytes = lightingBufferPayloadBytes(context, kind: 5)
        if batch.lightCount > 0 {
            upload.copy(
                from: slot.staging,
                sourceOffset: MetallumLightingAbiV1.uploadHeaderBytes,
                to: context.gpuLights,
                destinationOffset: 0,
                size: Int(batch.lightCount) * MetallumLightingAbiV1.gpuLightBytes
            )
        }
        if batch.lightCount > 0 {
            upload.fill(buffer: context.clusterScratch, range: 0..<scratchPayloadBytes, value: 0)
        } else if batch.lightCount == 0 {
            // Empty frames skip prefix/fill, so retire every previous cluster membership
            // before the direct shader observes gridAndLightCount.w == 0.
            upload.fill(buffer: context.clusterHeaders, range: 0..<headerPayloadBytes, value: 0)
        }
        if context.claimGuardInitialization() {
            for (buffer, payloadBytes) in [
                (context.gpuLights, lightPayloadBytes),
                (context.clusterHeaders, headerPayloadBytes),
                (context.clusterScratch, scratchPayloadBytes),
                (context.lightIndices, indexPayloadBytes),
                (context.params, MetallumLightingAbiV1.paramsBytes),
                (context.statistics, MetallumLightingAbiV1.statisticsBytes)
            ] {
                upload.fill(
                    buffer: buffer,
                    range: payloadBytes..<(payloadBytes + MetallumLightingAbiV1.guardBytes),
                    value: MetallumLightingAbiV1.guardValue
                )
            }
        }
        upload.endEncoding()

        let computePass = MTLComputePassDescriptor()
        attachGpuTiming(
            computePass,
            commandBuffer: commandBuffer,
            stage: .lightUploadClusterBuild
        )
        guard let compute = trackedMakeComputeCommandEncoder(commandBuffer, descriptor: computePass) else {
            context.rejectAfterEncoding(slot: slotIndex, commandBuffer: commandBuffer)
            return -13
        }
        compute.label = "Metallum clustered forward+ build v1"
        compute.setComputePipelineState(context.pipelines.prepare)
        compute.setBytes(&params, length: MetallumLightingAbiV1.paramsBytes, index: 0)
        compute.setBuffer(context.params, offset: 0, index: 1)
        compute.setBuffer(context.statistics, offset: 0, index: 2)
        compute.setBuffer(context.gpuLights, offset: 0, index: 3)
        let prepareThreads = max(1, Int(batch.lightCount))
        let prepareWidth = min(
            context.pipelines.prepare.maxTotalThreadsPerThreadgroup,
            256
        )
        compute.dispatchThreads(
            MTLSize(width: prepareThreads, height: 1, depth: 1),
            threadsPerThreadgroup: MTLSize(width: prepareWidth, height: 1, depth: 1)
        )
        compute.memoryBarrier(scope: .buffers)

        if batch.lightCount > 0 {
            let prefixBlockCount = Int(
                (context.clusterCount + MetallumLightingAbiV1.prefixBlockSize - 1)
                    / MetallumLightingAbiV1.prefixBlockSize
            )
            let prefixBlockWidth = Int(MetallumLightingAbiV1.prefixBlockSize)
            compute.setComputePipelineState(context.pipelines.count)
            compute.setBuffer(context.gpuLights, offset: 0, index: 0)
            compute.setBuffer(context.clusterScratch, offset: 0, index: 1)
            compute.setBuffer(context.params, offset: 0, index: 2)
            compute.setBuffer(context.statistics, offset: 0, index: 3)
            let width = min(context.pipelines.count.maxTotalThreadsPerThreadgroup, 256)
            compute.dispatchThreadgroups(
                MTLSize(width: Int(batch.lightCount), height: 1, depth: 1),
                threadsPerThreadgroup: MTLSize(width: width, height: 1, depth: 1)
            )
            compute.memoryBarrier(scope: .buffers)

            compute.setComputePipelineState(context.pipelines.prefixBlocks)
            compute.setBuffer(context.clusterScratch, offset: 0, index: 0)
            compute.setBuffer(context.clusterHeaders, offset: 0, index: 1)
            compute.setBuffer(context.params, offset: 0, index: 2)
            // The index buffer is unused until fill, so prefix passes temporarily reuse
            // its beginning for exact per-block telemetry.
            compute.setBuffer(context.lightIndices, offset: 0, index: 3)
            compute.dispatchThreadgroups(
                MTLSize(width: prefixBlockCount, height: 1, depth: 1),
                threadsPerThreadgroup: MTLSize(width: prefixBlockWidth, height: 1, depth: 1)
            )
            compute.memoryBarrier(scope: .buffers)

            compute.setComputePipelineState(context.pipelines.prefixGroups)
            compute.setBuffer(context.clusterHeaders, offset: 0, index: 0)
            compute.setBuffer(context.params, offset: 0, index: 1)
            compute.setBuffer(context.statistics, offset: 0, index: 2)
            compute.setBuffer(context.lightIndices, offset: 0, index: 3)
            compute.dispatchThreads(
                MTLSize(width: 1, height: 1, depth: 1),
                threadsPerThreadgroup: MTLSize(width: 1, height: 1, depth: 1)
            )
            compute.memoryBarrier(scope: .buffers)

            compute.setComputePipelineState(context.pipelines.prefixAdd)
            compute.setBuffer(context.clusterHeaders, offset: 0, index: 0)
            compute.setBuffer(context.params, offset: 0, index: 1)
            let prefixAddWidth = Int(MetallumLightingAbiV1.prefixBlockSize)
            compute.dispatchThreadgroups(
                MTLSize(width: prefixBlockCount, height: 1, depth: 1),
                threadsPerThreadgroup: MTLSize(width: prefixAddWidth, height: 1, depth: 1)
            )
            compute.memoryBarrier(scope: .buffers)

            compute.setComputePipelineState(context.pipelines.fill)
            compute.setBuffer(context.clusterScratch, offset: 0, index: 0)
            compute.setBuffer(context.clusterHeaders, offset: 0, index: 1)
            compute.setBuffer(context.lightIndices, offset: 0, index: 2)
            compute.setBuffer(context.params, offset: 0, index: 3)
            let fillWidth = min(context.pipelines.fill.maxTotalThreadsPerThreadgroup, 256)
            compute.dispatchThreads(
                MTLSize(width: Int(context.clusterCount), height: 1, depth: 1),
                threadsPerThreadgroup: MTLSize(width: fillWidth, height: 1, depth: 1)
            )
        }
        compute.endEncoding()

        let statisticsAvailable = context.shouldReadbackStatistics(frameId: batch.frameId)
        if statisticsAvailable {
            let statisticsReadbackPass = MTLBlitPassDescriptor()
            attachGpuTiming(
                statisticsReadbackPass,
                commandBuffer: commandBuffer,
                stage: .lightUploadClusterBuild
            )
            guard let statisticsReadback = trackedMakeBlitCommandEncoder(
                commandBuffer,
                descriptor: statisticsReadbackPass
            ) else {
                context.rejectAfterEncoding(slot: slotIndex, commandBuffer: commandBuffer)
                return -13
            }
            statisticsReadback.label = "Metallum sampled asynchronous cluster statistics copy"
            statisticsReadback.copy(
                from: context.statistics,
                sourceOffset: 0,
                to: slot.staging,
                destinationOffset: 0,
                size: MetallumLightingAbiV1.statisticsBytes
            )
            statisticsReadback.endEncoding()
        }
        commandBuffer.addCompletedHandler { [context] completed in
            context.complete(
                slot: slotIndex,
                commandBufferSucceeded: completed.status == .completed,
                statisticsAvailable: statisticsAvailable
            )
        }
        return 1
    }
}

private func writeLightingStatsValue<T>(
    _ value: T,
    to output: UnsafeMutableRawPointer,
    offset: Int
) {
    var stored = value
    withUnsafeBytes(of: &stored) { bytes in
        output.advanced(by: offset).copyMemory(from: bytes.baseAddress!, byteCount: bytes.count)
    }
}

@_cdecl("metallum_lighting_last_completed_stats_v1")
public func metallum_lighting_last_completed_stats_v1(
    _ contextPointer: UnsafeMutableRawPointer?,
    _ output: UnsafeMutableRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    guard let contextPointer,
          let output,
          byteSize == UInt64(MetallumLightingAbiV1.completedStatsBytes) else {
        return -1
    }
    let context = Unmanaged<MetallumLightingContext>
        .fromOpaque(contextPointer)
        .takeUnretainedValue()
    let snapshot = context.snapshot()
    output.initializeMemory(
        as: UInt8.self,
        repeating: 0,
        count: MetallumLightingAbiV1.completedStatsBytes
    )
    let completed = snapshot.lastCompleted
    writeLightingStatsValue(MetallumLightingAbiV1.version, to: output, offset: 0)
    writeLightingStatsValue(UInt32(MetallumLightingAbiV1.completedStatsBytes), to: output, offset: 4)
    writeLightingStatsValue(completed?.generation ?? context.generation, to: output, offset: 8)
    writeLightingStatsValue(completed?.frameId ?? 0, to: output, offset: 16)
    writeLightingStatsValue(completed?.lightCount ?? 0, to: output, offset: 24)
    writeLightingStatsValue(completed?.clusterCount ?? context.clusterCount, to: output, offset: 28)
    writeLightingStatsValue(completed?.acceptedIndices ?? 0, to: output, offset: 32)
    writeLightingStatsValue(completed?.requestedIndices ?? 0, to: output, offset: 36)
    writeLightingStatsValue(completed?.overflowClusters ?? 0, to: output, offset: 40)
    writeLightingStatsValue(completed?.perClusterDrops ?? 0, to: output, offset: 44)
    writeLightingStatsValue(completed?.indexCapacityDrops ?? 0, to: output, offset: 48)
    writeLightingStatsValue(completed?.admissionRejectedLights ?? 0, to: output, offset: 52)
    writeLightingStatsValue(completed?.occupancyP50 ?? 0, to: output, offset: 56)
    writeLightingStatsValue(completed?.occupancyP95 ?? 0, to: output, offset: 60)
    writeLightingStatsValue(completed?.occupancyP99 ?? 0, to: output, offset: 64)
    writeLightingStatsValue(completed?.maximumOccupancy ?? 0, to: output, offset: 68)
    writeLightingStatsValue(snapshot.ringHighWater, to: output, offset: 72)
    writeLightingStatsValue(snapshot.ringBusyRejects, to: output, offset: 76)
    writeLightingStatsValue(UInt32(1), to: output, offset: 80) // SDR/HDR-independent data contract.
    writeLightingStatsValue(snapshot.uploadCalls, to: output, offset: 88)
    writeLightingStatsValue(snapshot.completedCalls, to: output, offset: 96)
    writeLightingStatsValue(snapshot.rejectedCalls, to: output, offset: 104)
    writeLightingStatsValue(context.maxLights, to: output, offset: 112)
    writeLightingStatsValue(context.indexCapacity, to: output, offset: 116)
    writeLightingStatsValue(context.clustersX, to: output, offset: 120)
    writeLightingStatsValue(context.clustersY, to: output, offset: 124)
    return completed == nil ? 0 : 1
}

@_cdecl("metallum_voxel_abi_version_v1")
public func metallum_voxel_abi_version_v1() -> UInt32 {
    MetallumVoxelAbiV1.version
}

@_cdecl("metallum_voxel_layout_v1")
public func metallum_voxel_layout_v1(
    _ output: UnsafeMutableRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    guard let output, byteSize == 160 else { return -1 }
    output.initializeMemory(as: UInt8.self, repeating: 0, count: 160)
    let values: [UInt32] = [
        MetallumVoxelAbiV1.version, 160, MetallumVoxelAbiV1.magic,
        UInt32(MetallumVoxelAbiV1.headerBytes), UInt32(MetallumVoxelAbiV1.recordBytes),
        UInt32(MetallumVoxelAbiV1.levelLayoutBytes), UInt32(MetallumVoxelAbiV1.paramsBytes),
        UInt32(MetallumVoxelAbiV1.statsBytes), UInt32(MetallumVoxelAbiV1.ringSlots),
        MetallumVoxelAbiV1.logicalBrickEdge, MetallumVoxelAbiV1.occupancyWordsPerBrick,
        MetallumVoxelAbiV1.occupancyBytesPerBrick, MetallumVoxelAbiV1.resetFlag,
        MetallumVoxelAbiV1.unloadFlag, MetallumVoxelAbiV1.scrollFlag,
        UInt32(bitPattern: MetallumVoxelAbiV1.transientBusyStatus),
        0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 68, 72, 76, 80, 84, 88, 92
    ]
    for (index, value) in values.enumerated() {
        writeVoxelValue(value, to: output, offset: index * 4)
    }
    return 1
}

@_cdecl("metallum_voxel_create_context_v1")
public func metallum_voxel_create_context_v1(
    _ device: MTLDevice?,
    _ lightingGeneration: UInt64,
    _ clipmapGeneration: UInt64,
    _ worldGeneration: UInt64,
    _ layouts: UnsafeRawPointer?,
    _ layoutByteSize: UInt64,
    _ levelCount: UInt32,
    _ maxPatchCount: UInt32,
    _ stagingBytes: UInt64
) -> UnsafeMutableRawPointer? {
    autoreleasepool {
        guard let device,
              let parsedLayouts = parseVoxelLayoutsV1(
                  layouts, byteSize: layoutByteSize, levelCount: levelCount
              ),
              let context = makeVoxelContext(
                  device: device, lightingGeneration: lightingGeneration,
                  clipmapGeneration: clipmapGeneration, worldGeneration: worldGeneration,
                  layouts: parsedLayouts, maxPatchCount: maxPatchCount, stagingBytes: stagingBytes
              ) else {
            return nil
        }
        return Unmanaged.passRetained(context).toOpaque()
    }
}

@_cdecl("metallum_voxel_release_context_v1")
public func metallum_voxel_release_context_v1(_ pointer: UnsafeMutableRawPointer?) {
    guard let pointer else { return }
    Unmanaged<MetallumVoxelContext>.fromOpaque(pointer).takeUnretainedValue().retire()
    Unmanaged<MetallumVoxelContext>.fromOpaque(pointer).release()
}

@_cdecl("metallum_dynamic_shadow_abi_version_v1")
public func metallum_dynamic_shadow_abi_version_v1() -> UInt32 {
    MetallumDynamicShadowAbiV1.version
}

/** Layout words: version, header bytes, request bytes, max lights, page alignment, magic. */
@_cdecl("metallum_dynamic_shadow_layout_v1")
public func metallum_dynamic_shadow_layout_v1(
    _ output: UnsafeMutableRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    guard let output, byteSize == 32 else { return -1 }
    output.initializeMemory(as: UInt8.self, repeating: 0, count: 32)
    let values: [UInt32] = [
        MetallumDynamicShadowAbiV1.version,
        UInt32(MetallumDynamicShadowAbiV1.headerBytes),
        UInt32(MetallumDynamicShadowAbiV1.requestBytes),
        MetallumDynamicShadowAbiV1.maxLights,
        UInt32(MetallumDynamicShadowAbiV1.pageAlignment),
        MetallumDynamicShadowAbiV1.magic,
        0, 0
    ]
    for (index, value) in values.enumerated() {
        writeVoxelValue(value, to: output, offset: index * 4)
    }
    return 1
}

@_cdecl("metallum_dynamic_shadow_create_context_v1")
public func metallum_dynamic_shadow_create_context_v1(
    _ device: MTLDevice?,
    _ atlasSuffixOffset: UInt64,
    _ atlasSuffixBytes: UInt64
) -> UnsafeMutableRawPointer? {
    autoreleasepool {
        let (atlasEnd, overflow) = atlasSuffixOffset.addingReportingOverflow(atlasSuffixBytes)
        guard let device,
              !overflow, atlasEnd > atlasSuffixOffset,
              atlasSuffixOffset > 0,
              atlasSuffixOffset.isMultiple(of: MetallumDynamicShadowAbiV1.pageAlignment),
              atlasSuffixBytes.isMultiple(of: MetallumDynamicShadowAbiV1.pageAlignment),
              let pipelines = buildDynamicShadowPipelines(device: device) else {
            return nil
        }
        return Unmanaged.passRetained(
            MetallumDynamicShadowContext(
                device: device,
                pipelines: pipelines,
                atlasSuffixOffset: atlasSuffixOffset,
                atlasSuffixBytes: atlasSuffixBytes
            )
        ).toOpaque()
    }
}

@_cdecl("metallum_dynamic_shadow_release_context_v1")
public func metallum_dynamic_shadow_release_context_v1(_ pointer: UnsafeMutableRawPointer?) {
    guard let pointer else { return }
    let context = Unmanaged<MetallumDynamicShadowContext>.fromOpaque(pointer).takeUnretainedValue()
    context.retire()
    Unmanaged<MetallumDynamicShadowContext>.fromOpaque(pointer).release()
}

@_cdecl("metallum_dynamic_shadow_encode_v1")
public func metallum_dynamic_shadow_encode_v1(
    _ dynamicPointer: UnsafeMutableRawPointer?,
    _ voxelPointer: UnsafeMutableRawPointer?,
    _ commandBuffer: MTLCommandBuffer?,
    _ atlas: MTLBuffer?,
    _ globalFence: MTLFence?,
    _ packet: UnsafeRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    autoreleasepool {
        guard let dynamicPointer, let voxelPointer, let commandBuffer, let atlas, let globalFence else {
            return -1
        }
        let dynamic = Unmanaged<MetallumDynamicShadowContext>.fromOpaque(dynamicPointer)
            .takeUnretainedValue()
        let voxel = Unmanaged<MetallumVoxelContext>.fromOpaque(voxelPointer).takeUnretainedValue()
        guard dynamic.canEncode(), objectAddress(commandBuffer.device) == objectAddress(dynamic.device),
              objectAddress(commandBuffer.device) == objectAddress(voxel.device),
              objectAddress(atlas.device) == objectAddress(dynamic.device),
              commandBuffer.status == .notEnqueued else {
            return MetallumDynamicShadowAbiV1.rejected
        }
        let (parseStatus, parsedRequests) = parseDynamicShadowPacketV1(
            packet,
            byteSize: byteSize,
            voxel: voxel,
            atlas: atlas,
            atlasSuffixOffset: dynamic.atlasSuffixOffset,
            atlasSuffixBytes: dynamic.atlasSuffixBytes
        )
        guard parseStatus == MetallumDynamicShadowAbiV1.encoded, let requests = parsedRequests else {
            return parseStatus
        }
        guard !voxel.levels.isEmpty, voxel.levels.count <= 3 else {
            return MetallumDynamicShadowAbiV1.rejected
        }
        guard MemoryLayout<MetallumDynamicShadowRequestV1>.stride
                == MetallumDynamicShadowAbiV1.requestBytes,
              MemoryLayout<MetallumDynamicShadowLevelV1>.stride == 16 else {
            return MetallumDynamicShadowAbiV1.unavailable
        }
        let pass = MTLComputePassDescriptor()
        attachGpuTiming(pass, commandBuffer: commandBuffer, stage: .dynamicLocalShadow)
        guard let encoder = trackedMakeComputeCommandEncoder(commandBuffer, descriptor: pass) else {
            return MetallumDynamicShadowAbiV1.unavailable
        }
        encoder.label = "Metallum L6 dynamic voxel shadow v1"
        encoder.waitForFence(globalFence)
        let pipeline = dynamic.pipelines.page
        let threads = min(max(32, pipeline.threadExecutionWidth), pipeline.maxTotalThreadsPerThreadgroup)
        let lastLevel = voxel.levels.count - 1
        let boundLevels = (0..<3).map { voxel.levels[min($0, lastLevel)] }
        let levelBytes = boundLevels.map {
            MetallumDynamicShadowLevelV1(
                logicalEdge: $0.layout.logicalEdge,
                subdivision: $0.layout.subdivision,
                brickDimension: $0.brickDimension,
                reserved0: 0
            )
        }
        let maxRayCount = requests.map { Int($0.edge) * Int($0.edge) * 6 }.max() ?? 0
        encoder.setComputePipelineState(pipeline)
        encoder.setBuffer(boundLevels[0].occupancy, offset: 0, index: 0)
        encoder.setBuffer(boundLevels[1].occupancy, offset: 0, index: 1)
        encoder.setBuffer(boundLevels[2].occupancy, offset: 0, index: 2)
        encoder.setBuffer(boundLevels[0].optical, offset: 0, index: 3)
        encoder.setBuffer(boundLevels[1].optical, offset: 0, index: 4)
        encoder.setBuffer(boundLevels[2].optical, offset: 0, index: 5)
        encoder.setBuffer(boundLevels[0].metadata, offset: 0, index: 6)
        encoder.setBuffer(boundLevels[1].metadata, offset: 0, index: 7)
        encoder.setBuffer(boundLevels[2].metadata, offset: 0, index: 8)
        encoder.setBuffer(atlas, offset: 0, index: 9)
        requests.withUnsafeBytes { bytes in
            encoder.setBytes(bytes.baseAddress!, length: bytes.count, index: 10)
        }
        levelBytes.withUnsafeBytes { bytes in
            encoder.setBytes(bytes.baseAddress!, length: bytes.count, index: 11)
        }
        encoder.dispatchThreads(
            MTLSize(width: maxRayCount, height: requests.count, depth: 1),
            threadsPerThreadgroup: MTLSize(width: threads, height: 1, depth: 1)
        )
        // The L6 atlas is deliberately untracked. Publish native compute writes to the
        // renderer-wide fence so Java blits and the following fragment pass cannot race them.
        encoder.updateFence(globalFence)
        trackedEndEncoding(encoder)
        return MetallumDynamicShadowAbiV1.encoded
    }
}

private func voxelContextBuffer(
    _ context: MetallumVoxelContext,
    kind: Int32,
    index: Int32
) -> MTLBuffer? {
    let value = Int(index)
    switch kind {
    case 0 where context.levels.indices.contains(value): return context.levels[value].occupancy
    case 1 where context.levels.indices.contains(value): return context.levels[value].optical
    case 2 where context.levels.indices.contains(value): return context.levels[value].metadata
    case 3 where context.slots.indices.contains(value): return context.slots[value].payload
    case 4 where context.slots.indices.contains(value): return context.slots[value].indirect
    case 5 where context.slots.indices.contains(value): return context.slots[value].debugReadback
    default: return nil
    }
}

@_cdecl("metallum_voxel_context_buffer_v1")
public func metallum_voxel_context_buffer_v1(
    _ pointer: UnsafeMutableRawPointer?,
    _ kind: Int32,
    _ index: Int32
) -> UnsafeMutableRawPointer? {
    guard let pointer else { return nil }
    let context = Unmanaged<MetallumVoxelContext>.fromOpaque(pointer).takeUnretainedValue()
    return unretainedPointer(voxelContextBuffer(context, kind: kind, index: index))
}

@_cdecl("metallum_voxel_context_buffer_bytes_v1")
public func metallum_voxel_context_buffer_bytes_v1(
    _ pointer: UnsafeMutableRawPointer?,
    _ kind: Int32,
    _ index: Int32
) -> UInt64 {
    guard let pointer else { return 0 }
    let context = Unmanaged<MetallumVoxelContext>.fromOpaque(pointer).takeUnretainedValue()
    return UInt64(voxelContextBuffer(context, kind: kind, index: index)?.length ?? 0)
}

private func writeVoxelParameters(
    _ context: MetallumVoxelContext,
    slot: MetallumVoxelRingSlot,
    batch: MetallumVoxelBatchV1
) {
    for index in context.levels.indices {
        let level = context.levels[index]
        var parameters = MetallumVoxelParamsV1(
            patchCount: batch.levelPatchCounts[index],
            headerBytes: UInt32(MetallumVoxelAbiV1.headerBytes),
            recordBytes: UInt32(MetallumVoxelAbiV1.recordBytes),
            levelIndex: UInt32(index), recordStart: batch.levelRecordStarts[index],
            logicalEdge: level.layout.logicalEdge,
            subdivision: level.layout.subdivision, brickDimension: level.brickDimension,
            occupancyWordsPerBrick: MetallumVoxelAbiV1.occupancyWordsPerBrick, reserved0: 0,
            lightingGeneration: batch.lightingGeneration,
            clipmapGeneration: batch.clipmapGeneration,
            worldGeneration: batch.worldGeneration,
            frameId: batch.frameId
        )
        withUnsafeBytes(of: &parameters) { source in
            slot.paramsStaging.contents().advanced(by: index * MetallumVoxelAbiV1.paramsStride)
                .copyMemory(from: source.baseAddress!, byteCount: source.count)
        }
    }
}

@_cdecl("metallum_voxel_upload_apply_v1")
public func metallum_voxel_upload_apply_v1(
    _ contextPointer: UnsafeMutableRawPointer?,
    _ commandBuffer: MTLCommandBuffer?,
    _ packet: UnsafeRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    autoreleasepool {
        guard let contextPointer, let commandBuffer else { return -1 }
        let context = Unmanaged<MetallumVoxelContext>.fromOpaque(contextPointer).takeUnretainedValue()
        guard objectAddress(commandBuffer.device) == objectAddress(context.device),
              commandBuffer.status == .notEnqueued else {
            context.noteRejected()
            return -10
        }
        let (parseStatus, parsed) = parseVoxelBatchV1(packet, byteSize: byteSize, context: context)
        guard parseStatus == 1, let batch = parsed, let packet else {
            if parseStatus == -9 { context.noteStale() } else { context.noteRejected() }
            return parseStatus
        }
        let needsGpuWork = batch.patchCount > 0
            || (batch.flags & (MetallumVoxelAbiV1.resetFlag | MetallumVoxelAbiV1.unloadFlag)) != 0
        if !needsGpuWork {
            // Empty queues are a true no-op: no ring claim, no encoder and no clipmap scan.
            context.noteSubmission(batch)
            return 1
        }
        let slotIndex = Int(batch.slot)
        let reserveStatus = context.reserve(slot: slotIndex, queue: commandBuffer.commandQueue)
        guard reserveStatus == 1 else { return reserveStatus }
        let slot = context.slots[slotIndex]
        context.noteSubmission(batch)
        if batch.patchCount > 0 {
            memcpy(slot.staging.contents(), packet, Int(byteSize))
            writeVoxelParameters(context, slot: slot, batch: batch)
            let arguments = slot.indirectStaging.contents().bindMemory(
                to: UInt32.self,
                capacity: context.levels.count * 3
            )
            for index in context.levels.indices {
                arguments[index * 3] = batch.levelPatchCounts[index]
                arguments[index * 3 + 1] = 1
                arguments[index * 3 + 2] = 1
            }
        }
        let uploadPass = MTLBlitPassDescriptor()
        attachGpuTiming(uploadPass, commandBuffer: commandBuffer, stage: .voxelUploadUpdate)
        guard let upload = trackedMakeBlitCommandEncoder(commandBuffer, descriptor: uploadPass) else {
            context.cancel(slot: slotIndex)
            return -13
        }
        upload.label = "Metallum L5 voxel upload/reset v1"
        if (batch.flags & (MetallumVoxelAbiV1.resetFlag | MetallumVoxelAbiV1.unloadFlag)) != 0 {
            for level in context.levels {
                upload.fill(buffer: level.occupancy, range: 0..<level.occupancyPayloadBytes, value: 0)
                upload.fill(buffer: level.optical, range: 0..<level.opticalBytes, value: 0)
                upload.fill(buffer: level.metadata, range: 0..<level.metadataPayloadBytes, value: 0)
            }
        }
        if batch.patchCount > 0 {
            upload.copy(from: slot.staging, sourceOffset: 0, to: slot.payload,
                        destinationOffset: 0, size: Int(byteSize))
            upload.copy(from: slot.indirectStaging, sourceOffset: 0, to: slot.indirect,
                        destinationOffset: 0, size: context.indirectBytesPerSlot)
            upload.copy(from: slot.paramsStaging, sourceOffset: 0, to: slot.params,
                        destinationOffset: 0, size: context.levels.count * MetallumVoxelAbiV1.paramsStride)
        }
        upload.endEncoding()
        if batch.patchCount > 0 {
            let computePass = MTLComputePassDescriptor()
            attachGpuTiming(computePass, commandBuffer: commandBuffer, stage: .voxelUploadUpdate)
            guard let compute = trackedMakeComputeCommandEncoder(commandBuffer, descriptor: computePass) else {
                commandBuffer.addCompletedHandler { [context] completed in
                    context.complete(
                        slot: slotIndex,
                        patchCount: 0,
                        succeeded: completed.status == .completed
                    )
                }
                return -13
            }
            compute.label = "Metallum L5 voxel apply indirect v1"
            for index in context.levels.indices {
                guard batch.levelPatchCounts[index] > 0 else { continue }
                let level = context.levels[index]
                compute.setComputePipelineState(context.pipelines.apply)
                compute.setBuffer(slot.payload, offset: 0, index: 0)
                compute.setBuffer(slot.params, offset: index * MetallumVoxelAbiV1.paramsStride, index: 1)
                compute.setBuffer(level.occupancy, offset: 0, index: 2)
                compute.setBuffer(level.optical, offset: 0, index: 3)
                compute.setBuffer(level.metadata, offset: 0, index: 4)
                compute.dispatchThreadgroups(
                    indirectBuffer: slot.indirect,
                    indirectBufferOffset: index * MetallumVoxelAbiV1.indirectBytes,
                    threadsPerThreadgroup: MTLSize(width: 256, height: 1, depth: 1)
                )
            }
            compute.endEncoding()
        }
        commandBuffer.addCompletedHandler { [context] completed in
            context.complete(
                slot: slotIndex,
                patchCount: batch.patchCount,
                succeeded: completed.status == .completed
            )
        }
        return 1
    }
}

@_cdecl("metallum_voxel_last_completed_stats_v1")
public func metallum_voxel_last_completed_stats_v1(
    _ contextPointer: UnsafeMutableRawPointer?,
    _ output: UnsafeMutableRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    guard let contextPointer, let output,
          byteSize == UInt64(MetallumVoxelAbiV1.statsBytes) else { return -1 }
    let context = Unmanaged<MetallumVoxelContext>.fromOpaque(contextPointer).takeUnretainedValue()
    return context.stats(into: output) ? 1 : 0
}

@_cdecl("metallum_voxel_debug_checksum_v1")
public func metallum_voxel_debug_checksum_v1(
    _ contextPointer: UnsafeMutableRawPointer?,
    _ commandBuffer: MTLCommandBuffer?,
    _ levelIndex: UInt32,
    _ slotIndex: UInt32
) -> Int32 {
    autoreleasepool {
        guard let contextPointer, let commandBuffer else { return -1 }
        let context = Unmanaged<MetallumVoxelContext>.fromOpaque(contextPointer).takeUnretainedValue()
        guard levelIndex < UInt32(context.levels.count),
              slotIndex < UInt32(MetallumVoxelAbiV1.ringSlots),
              objectAddress(commandBuffer.device) == objectAddress(context.device),
              commandBuffer.status == .notEnqueued else {
            return -10
        }
        let slot = Int(slotIndex)
        let reserveStatus = context.reserveDebug(slot: slot, queue: commandBuffer.commandQueue)
        guard reserveStatus == 1 else { return reserveStatus }
        let resource = context.levels[Int(levelIndex)]
        let clearPass = MTLBlitPassDescriptor()
        attachGpuTiming(clearPass, commandBuffer: commandBuffer, stage: .voxelUploadUpdate)
        guard let clear = trackedMakeBlitCommandEncoder(commandBuffer, descriptor: clearPass) else {
            context.cancelDebug(slot: slot)
            return -13
        }
        // A non-zero seed distinguishes a completed checksum of symmetric/empty data from the
        // initial "diagnostic has not completed" telemetry value without touching clipmap data.
        clear.fill(buffer: context.slots[slot].debugScratch, range: 0..<4, value: 0xa5)
        clear.endEncoding()
        let computePass = MTLComputePassDescriptor()
        attachGpuTiming(computePass, commandBuffer: commandBuffer, stage: .voxelUploadUpdate)
        guard let compute = trackedMakeComputeCommandEncoder(commandBuffer, descriptor: computePass) else {
            commandBuffer.addCompletedHandler { [context] completed in
                context.completeDebug(slot: slot, succeeded: completed.status == .completed)
            }
            return -13
        }
        var parameters = MetallumVoxelChecksumParamsV1(
            occupancyWords: UInt32(resource.occupancyWords),
            opticalBytes: UInt32(resource.opticalBytes), threadCount: 256, reserved: 0
        )
        compute.label = "Metallum L5 voxel diagnostic checksum v1"
        compute.setComputePipelineState(context.pipelines.checksum)
        compute.setBuffer(resource.occupancy, offset: 0, index: 0)
        compute.setBuffer(resource.optical, offset: 0, index: 1)
        compute.setBuffer(context.slots[slot].debugScratch, offset: 0, index: 2)
        compute.setBytes(&parameters, length: MemoryLayout<MetallumVoxelChecksumParamsV1>.size, index: 3)
        compute.dispatchThreads(
            MTLSize(width: 256, height: 1, depth: 1),
            threadsPerThreadgroup: MTLSize(width: 256, height: 1, depth: 1)
        )
        compute.endEncoding()
        let readbackPass = MTLBlitPassDescriptor()
        attachGpuTiming(readbackPass, commandBuffer: commandBuffer, stage: .voxelUploadUpdate)
        guard let readback = trackedMakeBlitCommandEncoder(commandBuffer, descriptor: readbackPass) else {
            commandBuffer.addCompletedHandler { [context] completed in
                context.completeDebug(slot: slot, succeeded: completed.status == .completed)
            }
            return -13
        }
        readback.copy(from: context.slots[slot].debugScratch, sourceOffset: 0,
                      to: context.slots[slot].debugReadback, destinationOffset: 0, size: 4)
        readback.endEncoding()
        commandBuffer.addCompletedHandler { [context] completed in
            context.completeDebug(slot: slot, succeeded: completed.status == .completed)
        }
        return 1
    }
}

@_cdecl("metallum_voxel_debug_readback_v1")
public func metallum_voxel_debug_readback_v1(
    _ contextPointer: UnsafeMutableRawPointer?,
    _ output: UnsafeMutableRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    guard let contextPointer, let output,
          byteSize == UInt64(MetallumVoxelAbiV1.checksumBytes) else { return -1 }
    let context = Unmanaged<MetallumVoxelContext>.fromOpaque(contextPointer).takeUnretainedValue()
    let snapshot = context.snapshot()
    output.initializeMemory(as: UInt8.self, repeating: 0, count: MetallumVoxelAbiV1.checksumBytes)
    writeVoxelValue(MetallumVoxelAbiV1.version, to: output, offset: 0)
    writeVoxelValue(UInt32(MetallumVoxelAbiV1.checksumBytes), to: output, offset: 4)
    writeVoxelValue(snapshot.checksum, to: output, offset: 8)
    writeVoxelValue(UInt32(context.levels.count), to: output, offset: 12)
    return 1
}

// Read-only native validation surface for the four renderer-generation
// contracts. Bits: 0 SDR present, 1 SDR UI seed, 2 Legacy HDR effects,
// 3 Legacy reconstruction display, 4 actual HDR effects, 5 actual HDR display,
// 6 HDR workspace, 7 Legacy fallback resources, 8 spatial HDR display,
// 9 actual-HDR RGBA8 UI-only display, 10 actual-HDR linear-FP16 UI-only display.
@_cdecl("metallum_renderer_generation_native_contract_v1")
public func metallum_renderer_generation_native_contract_v1(_ device: MTLDevice) -> UInt64 {
    let key = objectAddress(device)
    func hasPipeline(_ values: [PresentPipelineKey: MTLRenderPipelineState]) -> Bool {
        values.keys.contains { $0.deviceAddress == key }
    }
    var result: UInt64 = 0
    if hasPipeline(NativeState.presentPipelines) { result |= 1 << 0 }
    if NativeState.uiBackdropPipelines[key] != nil { result |= 1 << 1 }
    if NativeState.hdrPipelines[key] != nil { result |= 1 << 2 }
    if hasPipeline(NativeState.legacyHdrPresentPipelines)
        || hasPipeline(NativeState.worldPresentPipelines)
        || NativeState.nativeWorldUiPipelines[key] != nil {
        result |= 1 << 3
    }
    if NativeState.actualHdrPipelines[key] != nil { result |= 1 << 4 }
    if hasPipeline(NativeState.actualHdrPresentPipelines)
        || hasPipeline(NativeState.actualWorldPresentPipelines)
        || NativeState.actualNativeWorldUiPipelines[key] != nil {
        result |= 1 << 5
    }
    if NativeState.hdrWorkspaces[key] != nil { result |= 1 << 6 }
    if NativeState.hdrFallbackAdaptiveStates[key] != nil
        || NativeState.hdrFallbackDepthTextures[key] != nil {
        result |= 1 << 7
    }
    if hasPipeline(NativeState.spatialPresentPipelines)
        || hasPipeline(NativeState.spatialScreenshotPipelines) {
        result |= 1 << 8
    }
    if hasPipeline(NativeState.actualHdrUiOnlyPipelines) { result |= 1 << 9 }
    if hasPipeline(NativeState.actualHdrLinearUiOnlyPipelines) { result |= 1 << 10 }
    return result
}

private enum MetallumFrameGraphAbiV1 {
    static let version: UInt32 = 1
    static let headerBytes = 32
    static let resourceBytes = 24
    static let passBytes = 24
    static let accessBytes = 24
    static let maxPasses = 64
    static let typedAttachmentsCapability: UInt64 = 1
    static let externalMetalFxCapability: UInt64 = 1 << 1
    static let supportedCapabilities = typedAttachmentsCapability | externalMetalFxCapability
}

private struct MetallumFrameGraphPacketReader {
    let bytes: UnsafeRawBufferPointer

    func uint32(at offset: Int) -> UInt32? {
        guard offset >= 0, offset <= bytes.count - MemoryLayout<UInt32>.size else {
            return nil
        }
        return UInt32(littleEndian: bytes.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
    }

    func int32(at offset: Int) -> Int32? {
        guard let value = uint32(at: offset) else {
            return nil
        }
        return Int32(bitPattern: value)
    }

    func uint64(at offset: Int) -> UInt64? {
        guard offset >= 0, offset <= bytes.count - MemoryLayout<UInt64>.size else {
            return nil
        }
        return UInt64(littleEndian: bytes.loadUnaligned(fromByteOffset: offset, as: UInt64.self))
    }

    func float32(at offset: Int) -> Float? {
        guard let bits = uint32(at: offset) else { return nil }
        return Float(bitPattern: bits)
    }

    func float64(at offset: Int) -> Double? {
        guard let bits = uint64(at: offset) else { return nil }
        return Double(bitPattern: bits)
    }

    func float64x3(at offset: Int) -> SIMD3<Double>? {
        guard let x = float64(at: offset),
              let y = float64(at: offset + 8),
              let z = float64(at: offset + 16),
              x.isFinite, y.isFinite, z.isFinite else {
            return nil
        }
        return SIMD3(x, y, z)
    }

    func finiteFloat4(at offset: Int) -> SIMD4<Float>? {
        guard let x = float32(at: offset),
              let y = float32(at: offset + 4),
              let z = float32(at: offset + 8),
              let w = float32(at: offset + 12),
              x.isFinite, y.isFinite, z.isFinite, w.isFinite else {
            return nil
        }
        return SIMD4(x, y, z, w)
    }

    func floatMatrix4x4(at offset: Int) -> simd_float4x4? {
        guard let column0 = finiteFloat4(at: offset),
              let column1 = finiteFloat4(at: offset + 16),
              let column2 = finiteFloat4(at: offset + 32),
              let column3 = finiteFloat4(at: offset + 48) else {
            return nil
        }
        return simd_float4x4(columns: (column0, column1, column2, column3))
    }
}

private func checkedFrameGraphSectionEnd(start: Int, count: Int, stride: Int) -> Int? {
    let (bytes, productOverflow) = count.multipliedReportingOverflow(by: stride)
    guard !productOverflow else {
        return nil
    }
    let (end, sumOverflow) = start.addingReportingOverflow(bytes)
    return sumOverflow ? nil : end
}

/**
 Synchronously validates the bundled Java graph packet before any GPU encoding.

 Return values are stable diagnostics: 1 accepted; -1 header pointer/length;
 -2 version; -3 total size/reserved; -4 capabilities; -5 count/overflow;
 -6 resource record; -7 pass record; -8 access record.
 */
@_cdecl("metallum_validate_frame_graph_v1")
public func metallum_validate_frame_graph_v1(
    _ packet: UnsafeRawPointer?,
    _ byteSize: UInt64
) -> Int32 {
    guard let packet,
          byteSize >= UInt64(MetallumFrameGraphAbiV1.headerBytes),
          byteSize <= UInt64(Int.max) else {
        return -1
    }
    let packetSize = Int(byteSize)
    let reader = MetallumFrameGraphPacketReader(
        bytes: UnsafeRawBufferPointer(start: packet, count: packetSize)
    )
    guard let version = reader.uint32(at: 0),
          let declaredByteSize = reader.uint32(at: 4),
          let requiredCapabilities = reader.uint64(at: 8),
          let resourceCountValue = reader.uint32(at: 16),
          let passCountValue = reader.uint32(at: 20),
          let accessCountValue = reader.uint32(at: 24),
          let reserved = reader.uint32(at: 28) else {
        return -1
    }
    guard version == MetallumFrameGraphAbiV1.version else {
        return -2
    }
    guard UInt64(declaredByteSize) == byteSize, reserved == 0 else {
        return -3
    }
    guard requiredCapabilities & ~MetallumFrameGraphAbiV1.supportedCapabilities == 0 else {
        return -4
    }

    let resourceCount = Int(resourceCountValue)
    let passCount = Int(passCountValue)
    let accessCount = Int(accessCountValue)
    guard passCount <= MetallumFrameGraphAbiV1.maxPasses,
          let resourceEnd = checkedFrameGraphSectionEnd(
            start: MetallumFrameGraphAbiV1.headerBytes,
            count: resourceCount,
            stride: MetallumFrameGraphAbiV1.resourceBytes
          ),
          let passEnd = checkedFrameGraphSectionEnd(
            start: resourceEnd,
            count: passCount,
            stride: MetallumFrameGraphAbiV1.passBytes
          ),
          let accessEnd = checkedFrameGraphSectionEnd(
            start: passEnd,
            count: accessCount,
            stride: MetallumFrameGraphAbiV1.accessBytes
          ) else {
        return -5
    }
    guard accessEnd == packetSize else {
        return -3
    }

    var resourceTypes = [UInt32](repeating: 0, count: resourceCount)
    var resourceFirstPass = [Int32](repeating: -1, count: resourceCount)
    var resourceLastPass = [Int32](repeating: -1, count: resourceCount)
    for index in 0..<resourceCount {
        let offset = MetallumFrameGraphAbiV1.headerBytes
            + index * MetallumFrameGraphAbiV1.resourceBytes
        guard let resourceId = reader.uint32(at: offset),
              let resourceType = reader.uint32(at: offset + 4),
              let persistence = reader.uint32(at: offset + 8),
              let flags = reader.uint32(at: offset + 12),
              let firstPass = reader.int32(at: offset + 16),
              let lastPass = reader.int32(at: offset + 20),
              resourceId == UInt32(index),
              (1...2).contains(resourceType),
              (1...8).contains(persistence),
              flags & ~UInt32(1) == 0 else {
            return -6
        }
        let wholeGraph = firstPass == -1 && lastPass == -1
        let closedLifetime = firstPass >= 0
            && lastPass >= firstPass
            && lastPass < Int32(passCount)
        guard wholeGraph || closedLifetime else {
            return -6
        }
        resourceTypes[index] = resourceType
        resourceFirstPass[index] = firstPass
        resourceLastPass[index] = lastPass
    }

    var passEncoders = [UInt32](repeating: 0, count: passCount)
    var passFirstAccess = [Int](repeating: 0, count: passCount)
    var passAccessCount = [Int](repeating: 0, count: passCount)
    var expectedFirstAccess = 0
    for index in 0..<passCount {
        let offset = resourceEnd + index * MetallumFrameGraphAbiV1.passBytes
        guard let passId = reader.uint32(at: offset),
              let encoder = reader.uint32(at: offset + 4),
              let firstAccessValue = reader.uint32(at: offset + 8),
              let passAccessCountValue = reader.uint32(at: offset + 12),
              let dependencyMask = reader.uint64(at: offset + 16),
              passId == UInt32(index),
              (1...4).contains(encoder),
              encoder != 4
                || requiredCapabilities & MetallumFrameGraphAbiV1.externalMetalFxCapability != 0 else {
            return -7
        }
        let firstAccess = Int(firstAccessValue)
        let count = Int(passAccessCountValue)
        guard firstAccess == expectedFirstAccess,
              let end = checkedFrameGraphSectionEnd(start: firstAccess, count: count, stride: 1),
              end <= accessCount else {
            return -7
        }
        let allowedDependencies: UInt64 = index == 0
            ? 0
            : (UInt64(1) << UInt64(index)) - 1
        guard dependencyMask & ~allowedDependencies == 0 else {
            return -7
        }
        passEncoders[index] = encoder
        passFirstAccess[index] = firstAccess
        passAccessCount[index] = count
        expectedFirstAccess = end
    }
    guard expectedFirstAccess == accessCount else {
        return -7
    }

    for passIndex in 0..<passCount {
        let encoder = passEncoders[passIndex]
        let firstAccess = passFirstAccess[passIndex]
        let endAccess = firstAccess + passAccessCount[passIndex]
        for accessIndex in firstAccess..<endAccess {
            let offset = passEnd + accessIndex * MetallumFrameGraphAbiV1.accessBytes
            guard let resourceIdValue = reader.uint32(at: offset),
                  let accessKind = reader.uint32(at: offset + 4),
                  let stage = reader.uint32(at: offset + 8),
                  let attachmentRole = reader.uint32(at: offset + 12),
                  let loadAction = reader.uint32(at: offset + 16),
                  let storeAction = reader.uint32(at: offset + 20),
                  resourceIdValue < resourceCountValue,
                  (1...3).contains(accessKind),
                  (1...5).contains(stage),
                  attachmentRole <= 3,
                  loadAction <= 3,
                  storeAction <= 2 else {
                return -8
            }
            let resourceId = Int(resourceIdValue)
            if resourceFirstPass[resourceId] >= 0 {
                guard passIndex >= Int(resourceFirstPass[resourceId]),
                      passIndex <= Int(resourceLastPass[resourceId]) else {
                    return -8
                }
            }
            let compatibleStage = (encoder == 1 && (stage == 1 || stage == 2))
                || (encoder == 2 && stage == 3)
                || (encoder == 3 && stage == 4)
                || (encoder == 4 && stage == 5)
            guard compatibleStage else {
                return -8
            }
            guard stage != 5
                    || requiredCapabilities & MetallumFrameGraphAbiV1.externalMetalFxCapability != 0 else {
                return -8
            }
            if attachmentRole == 0 {
                guard loadAction == 0, storeAction == 0 else {
                    return -8
                }
            } else {
                let reads = accessKind == 1 || accessKind == 3
                let writes = accessKind == 2 || accessKind == 3
                guard resourceTypes[resourceId] == 2,
                      requiredCapabilities & MetallumFrameGraphAbiV1.typedAttachmentsCapability != 0,
                      encoder == 1,
                      stage == 2,
                      loadAction != 0,
                      storeAction != 0,
                      writes,
                      (loadAction == 1) == reads else {
                    return -8
                }
            }
        }
    }
    return 1
}

@_cdecl("metallum_init_pipelines")
public func metallum_init_pipelines(_ device: MTLDevice) -> Int32 {
    autoreleasepool {
        let deviceAddress = objectAddress(device)
        let shaderState = builtinShaderState(device: device)
        shaderState.initializationLock.lock()
        defer { shaderState.initializationLock.unlock() }
        guard !shaderState.snapshot().warmupComplete else {
            return builtinShaderInitializationStatus(shaderState)
        }
        NativeState.initializedDevices[deviceAddress] = device
        _ = loadPrecompiledBuiltinShaderLibrary(device: device)
        for shaderSet in MetallumBuiltinShaderSet.startupMandatory {
            do {
                let library = try resolveBuiltinShaderLibrary(device: device, shaderSet: shaderSet)
                if shaderSet.requiredFunctionNames.contains(where: { library.makeFunction(name: $0) == nil }) {
                    recordBuiltinPipelineCreation(device: device, succeeded: false)
                }
            } catch {
                recordBuiltinPipelineCreation(device: device, succeeded: false)
            }
        }
        let warmupStart = ProcessInfo.processInfo.systemUptime
        _ = ensureSodiumLightPatchPipeline(device: device)
        _ = ensurePresentPipeline(device: device, colorFormat: .bgra8Unorm)
        NativeState.presentLinearSamplers[deviceAddress] = buildPresentSampler(device: device, filter: .linear)
        NativeState.presentNearestSamplers[deviceAddress] = buildPresentSampler(device: device, filter: .nearest)
        _ = ensureClearColorDepthPipeline(device, .bgra8Unorm, .depth32Float)
        _ = ensureClearColorDepthPipeline(device, .rgba8Unorm, .depth32Float)
        _ = ensureClearColorDepthPipeline(device, .bgra8Unorm, .invalid)
        let pipelineWarmupMilliseconds = (
            ProcessInfo.processInfo.systemUptime - warmupStart
        ) * 1_000.0
        let cacheHitCount = probeBuiltinPipelineCacheHits(device: device)
        shaderState.withLock {
            shaderState.pipelineWarmupMilliseconds = pipelineWarmupMilliseconds
            shaderState.pipelineCacheHitCount = cacheHitCount
            shaderState.warmupComplete = true
        }
        let snapshot = shaderState.snapshot()
        NSLog(
            "[metallum] Built-in Metal pipeline warmup: mode %@, library %.3f ms, pipelines %.3f ms, PSOs %d, cache %d hit / %d miss, source compiles %d (%.3f ms), failures %d",
            snapshot.mode.rawValue,
            snapshot.libraryLoadMilliseconds,
            snapshot.pipelineWarmupMilliseconds,
            snapshot.pipelineCount,
            snapshot.pipelineCacheHitCount,
            snapshot.pipelineCacheMissCount,
            snapshot.sourceCompileCount,
            snapshot.sourceCompileMilliseconds,
            snapshot.pipelineFailureCount
        )
        return builtinShaderInitializationStatus(shaderState)
    }
}

@_cdecl("metallum_release_device_caches")
public func metallum_release_device_caches(_ device: MTLDevice) {
    autoreleasepool {
        let deviceAddress = objectAddress(device)
        MetallumStaticGeometryHeapRegistry.shared.releaseDevice(device)
        NativeState.depthStencilStates = NativeState.depthStencilStates.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.clearPipelines = NativeState.clearPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.presentPipelines = NativeState.presentPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        removePipelines(&NativeState.legacyHdrPresentPipelines, deviceAddress: deviceAddress)
        removePipelines(&NativeState.actualHdrPresentPipelines, deviceAddress: deviceAddress)
        removePipelines(&NativeState.actualHdrUiOnlyPipelines, deviceAddress: deviceAddress)
        removePipelines(&NativeState.actualHdrLinearUiOnlyPipelines, deviceAddress: deviceAddress)
        NativeState.spatialPresentPipelines = NativeState.spatialPresentPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.spatialScreenshotPipelines = NativeState.spatialScreenshotPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.worldPresentPipelines = NativeState.worldPresentPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        removePipelines(&NativeState.actualWorldPresentPipelines, deviceAddress: deviceAddress)
        NativeState.nativeWorldUiPipelines.removeValue(forKey: deviceAddress)
        NativeState.actualNativeWorldUiPipelines.removeValue(forKey: deviceAddress)
        NativeState.sodiumLightPatchPipelines.removeValue(forKey: deviceAddress)
        NativeState.temporalDiagnosticPipelines.removeValue(forKey: deviceAddress)
        NativeState.hdrPipelines.removeValue(forKey: deviceAddress)
        NativeState.actualHdrPipelines.removeValue(forKey: deviceAddress)
        NativeState.uiBackdropPipelines.removeValue(forKey: deviceAddress)
        NativeState.menuBlurPipelines.removeValue(forKey: deviceAddress)
        NativeState.hdrWorkspaces.removeValue(forKey: deviceAddress)
        NativeState.hdrFallbackAdaptiveStates.removeValue(forKey: deviceAddress)
        NativeState.hdrFallbackDepthTextures.removeValue(forKey: deviceAddress)
        NativeState.spatialWorkspaces.removeValue(forKey: deviceAddress)
        NativeState.presentNearestSamplers.removeValue(forKey: deviceAddress)
        NativeState.presentLinearSamplers.removeValue(forKey: deviceAddress)
        NativeState.initializedDevices.removeValue(forKey: deviceAddress)
        NativeState.preparedRendererGenerations.removeValue(forKey: deviceAddress)
        removeBuiltinShaderState(device: device)
    }
}

private func ensureDepthStencilState(device: MTLDevice, compareOp: MTLCompareFunction, writeDepth: Bool) -> MTLDepthStencilState? {
    let key = DepthStencilKey(deviceAddress: objectAddress(device), compareOp: compareOp, writeDepth: writeDepth)
    if let cached = NativeState.depthStencilStates[key] {
        return cached
    }
    let descriptor = MTLDepthStencilDescriptor()
    descriptor.depthCompareFunction = compareOp
    descriptor.isDepthWriteEnabled = writeDepth
    let state = device.makeDepthStencilState(descriptor: descriptor)
    if let state {
        NativeState.depthStencilStates[key] = state
    }
    return state
}

private func triangleFanOutputIndexCount(sourceCount: Int, buffer: MTLBuffer, offset: Int) -> Int? {
    let triangleCount = sourceCount - 2
    guard triangleCount <= Int.max / 3 else {
        return nil
    }

    let indexCount = triangleCount * 3
    let bufferIndexCapacity = UInt64((buffer.length - offset) / MemoryLayout<UInt32>.stride)
    guard indexCount <= UInt64(Int.max), indexCount <= bufferIndexCapacity else {
        return nil
    }
    return Int(indexCount)
}

private func readIndex(_ indexBuffer: MTLBuffer, byteOffset: Int, index: Int, indexType: Int) -> UInt32 {
    let base = indexBuffer.contents().advanced(by: Int(byteOffset))
    if indexType == 0 {
        return UInt32(base.assumingMemoryBound(to: UInt16.self)[Int(index)])
    }
    return base.assumingMemoryBound(to: UInt32.self)[Int(index)]
}

private func writeIndexedTriangleFanIndices(
    sourceIndexBuffer: MTLBuffer,
    destinationIndexBuffer: MTLBuffer,
    destinationOffset: Int,
    indexType: Int,
    indexOffsetBytes: Int,
    indexCount: Int
) -> Int? {
    guard indexCount >= 3, let generatedIndexCount = triangleFanOutputIndexCount(sourceCount: indexCount, buffer: destinationIndexBuffer, offset: destinationOffset) else {
        return nil
    }
    let triangleCount = indexCount - 2
    let center = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: 0, indexType: indexType)
    let indices = (destinationIndexBuffer.contents() + destinationOffset).assumingMemoryBound(to: UInt32.self)
    var writeIndex = 0
    for triangle in 0..<triangleCount {
        indices[writeIndex] = center
        indices[writeIndex + 1] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 1, indexType: indexType)
        indices[writeIndex + 2] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 2, indexType: indexType)
        writeIndex += 3
    }
    return generatedIndexCount
}

@_cdecl("metallum_create_system_default_device")
public func metallum_create_system_default_device() -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(MTLCreateSystemDefaultDevice())
    }
}

@_cdecl("metallum_copy_device_name")
public func metallum_copy_device_name(
    _ device: MTLDevice,
    _ output: UnsafeMutablePointer<CChar>?,
    _ capacity: Int64
) -> Int32 {
    return autoreleasepool {
        guard let output, capacity > 0 else {
            return 1
        }
        let maxLength = Int(capacity - 1)
        let bytes = Array(device.name.utf8.prefix(maxLength))
        for i in 0..<bytes.count {
            output[i] = CChar(bitPattern: bytes[i])
        }
        output[bytes.count] = 0
        return 0
    }
}

@_cdecl("metallum_NSWindow_backingScaleFactor")
public func metallum_NSWindow_backingScaleFactor(_ window: NSWindow) -> Double {
    Double(window.backingScaleFactor)
}

@_cdecl("metallum_create_edr_monitor")
public func metallum_create_edr_monitor(_ window: NSWindow) -> UnsafeMutableRawPointer? {
    retainedPointer(MetallumEdrMonitor(window: window))
}

@_cdecl("metallum_EDRMonitor_query")
public func metallum_EDRMonitor_query(
    _ rawMonitor: UnsafeMutableRawPointer?
) -> UInt64 {
    guard let rawMonitor else {
        return UInt64(Float(1.0).bitPattern)
            | (UInt64(Float(1.0).bitPattern) << 32)
    }

    let monitor = Unmanaged<MetallumEdrMonitor>
        .fromOpaque(rawMonitor)
        .takeUnretainedValue()
    let snapshot = monitor.snapshot()
    return UInt64(snapshot.current.bitPattern)
        | (UInt64(snapshot.potential.bitPattern) << 32)
}

private let rendererCapabilityMetal3Base: UInt64 = 1 << 0
private let rendererCapabilityMetal4OsApi: UInt64 = 1 << 1
private let rendererCapabilityMetal4GpuFamily: UInt64 = 1 << 2
private let rendererCapabilityMetal4Core: UInt64 = 1 << 3
private let rendererCapabilityMetal4Compiler: UInt64 = 1 << 4
private let rendererCapabilityMetal4CommandLifecycle: UInt64 = 1 << 5
private let rendererCapabilityMetal4ArgumentTables: UInt64 = 1 << 6
private let rendererCapabilityMetal4ExplicitBarriers: UInt64 = 1 << 7
private let rendererCapabilityMetalFxSpatial: UInt64 = 1 << 8
private let rendererCapabilityMetalFxTemporal: UInt64 = 1 << 9
private let rendererCapabilityMetalFxFrameInterpolation: UInt64 = 1 << 10
private let rendererCapabilityMetalFxTemporalMetal4: UInt64 = 1 << 11
private let rendererCapabilityMetalFxFrameInterpolationMetal4: UInt64 = 1 << 12
private let rendererCapabilityRequiredTextureFormatsUsages: UInt64 = 1 << 13
private let rendererCapabilityDisplayRefresh: UInt64 = 1 << 14
private let rendererCapabilityDisplayHeadroom: UInt64 = 1 << 15
private let rendererCapabilityTemporalProfile: UInt64 = 1 << 16
private let rendererCapabilityRefreshShift: UInt64 = 48

private func rendererCapabilityReport(_ snapshot: UInt64) -> [String: Bool] {
    [
        "metal3_base": snapshot & rendererCapabilityMetal3Base != 0,
        "metal4_os_api": snapshot & rendererCapabilityMetal4OsApi != 0,
        "metal4_gpu_family": snapshot & rendererCapabilityMetal4GpuFamily != 0,
        "metal4_core": snapshot & rendererCapabilityMetal4Core != 0,
        "metal4_compiler": snapshot & rendererCapabilityMetal4Compiler != 0,
        "metal4_command_lifecycle": snapshot & rendererCapabilityMetal4CommandLifecycle != 0,
        "metal4_argument_tables": snapshot & rendererCapabilityMetal4ArgumentTables != 0,
        "metal4_explicit_barriers": snapshot & rendererCapabilityMetal4ExplicitBarriers != 0,
        "metalfx_spatial": snapshot & rendererCapabilityMetalFxSpatial != 0,
        "metalfx_temporal": snapshot & rendererCapabilityMetalFxTemporal != 0,
        "metalfx_frame_interpolation": snapshot & rendererCapabilityMetalFxFrameInterpolation != 0,
        "metalfx_temporal_metal4": snapshot & rendererCapabilityMetalFxTemporalMetal4 != 0,
        "metalfx_frame_interpolation_metal4": snapshot
            & rendererCapabilityMetalFxFrameInterpolationMetal4 != 0,
        "required_texture_formats_usages": snapshot
            & rendererCapabilityRequiredTextureFormatsUsages != 0,
        "display_refresh": snapshot & rendererCapabilityDisplayRefresh != 0,
        "display_headroom": snapshot & rendererCapabilityDisplayHeadroom != 0,
        "temporal_diagnostic_profile": snapshot & rendererCapabilityTemporalProfile != 0
    ]
}

private func supportsRendererTextureFormatUsageProfile(_ device: MTLDevice) -> Bool {
    func supports(_ format: MTLPixelFormat, _ usage: MTLTextureUsage) -> Bool {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format,
            width: 1,
            height: 1,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = usage
        return device.makeTexture(descriptor: descriptor) != nil
    }

    let colorUsage: MTLTextureUsage = [.shaderRead, .shaderWrite, .renderTarget]
    return supports(.rgba16Float, colorUsage)
        && supports(.rg16Float, colorUsage)
        && supports(.r8Unorm, colorUsage)
        && supports(.depth32Float, [.shaderRead, .renderTarget])
}

private func supportsTemporalDiagnosticProfile(_ device: MTLDevice) -> Bool {
    guard MTLFXTemporalScalerDescriptor.supportsDevice(device) else { return false }
    guard #available(macOS 14.4, *) else { return false }

    let descriptor = MTLFXTemporalScalerDescriptor()
    descriptor.colorTextureFormat = .rgba16Float
    descriptor.depthTextureFormat = .depth32Float
    descriptor.motionTextureFormat = .rg16Float
    descriptor.outputTextureFormat = .rgba16Float
    descriptor.inputWidth = 64
    descriptor.inputHeight = 64
    descriptor.outputWidth = 128
    descriptor.outputHeight = 128
    descriptor.isReactiveMaskTextureEnabled = true
    descriptor.reactiveMaskTextureFormat = .r8Unorm
    descriptor.requiresSynchronousInitialization = false
    guard let scaler = descriptor.makeTemporalScaler(device: device) else { return false }

    func supports(_ format: MTLPixelFormat, _ usage: MTLTextureUsage) -> Bool {
        let texture = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format,
            width: 64,
            height: 64,
            mipmapped: false
        )
        texture.storageMode = .private
        texture.usage = usage
        return device.makeTexture(descriptor: texture) != nil
    }

    return supports(.depth32Float, scaler.depthTextureUsage.union([.shaderRead, .renderTarget]))
        && supports(.rg16Float, scaler.motionTextureUsage.union([.shaderRead, .renderTarget]))
        && supports(.r8Unorm, scaler.reactiveTextureUsage.union([.shaderRead, .renderTarget]))
}

@_cdecl("metallum_renderer_capabilities_v1")
public func metallum_renderer_capabilities_v1(
    _ device: MTLDevice,
    _ rawMonitor: UnsafeMutableRawPointer?
) -> UInt64 {
    autoreleasepool {
        var snapshot = rendererCapabilityMetal3Base

        if MTLFXSpatialScalerDescriptor.supportsDevice(device) {
            snapshot |= rendererCapabilityMetalFxSpatial
        }
        if MTLFXTemporalScalerDescriptor.supportsDevice(device) {
            snapshot |= rendererCapabilityMetalFxTemporal
        }
        if supportsRendererTextureFormatUsageProfile(device) {
            snapshot |= rendererCapabilityRequiredTextureFormatsUsages
        }
        if supportsTemporalDiagnosticProfile(device) {
            snapshot |= rendererCapabilityTemporalProfile
        }

        if #available(macOS 26.0, *) {
            snapshot |= rendererCapabilityMetal4OsApi
            if device.supportsFamily(.metal4) {
                snapshot |= rendererCapabilityMetal4GpuFamily
                    | rendererCapabilityMetal4Core
                    | rendererCapabilityMetal4CommandLifecycle
                    | rendererCapabilityMetal4ArgumentTables
                    | rendererCapabilityMetal4ExplicitBarriers
                do {
                    _ = try device.makeCompiler(descriptor: MTL4CompilerDescriptor())
                    snapshot |= rendererCapabilityMetal4Compiler
                } catch {
                    // Compiler support stays independently unavailable.
                }
            }
            if MTLFXFrameInterpolatorDescriptor.supportsDevice(device) {
                snapshot |= rendererCapabilityMetalFxFrameInterpolation
            }
            if MTLFXTemporalScalerDescriptor.supportsMetal4FX(device) {
                snapshot |= rendererCapabilityMetalFxTemporalMetal4
            }
            if MTLFXFrameInterpolatorDescriptor.supportsMetal4FX(device) {
                snapshot |= rendererCapabilityMetalFxFrameInterpolationMetal4
            }
        }

        var refresh = 0
        var currentHeadroom: Float = 1.0
        var potentialHeadroom: Float = 1.0
        if let rawMonitor {
            let monitor = Unmanaged<MetallumEdrMonitor>
                .fromOpaque(rawMonitor)
                .takeUnretainedValue()
            let display = monitor.snapshot()
            refresh = max(0, min(1_000, display.maximumFramesPerSecond))
            currentHeadroom = display.current
            potentialHeadroom = display.potential
            snapshot |= rendererCapabilityDisplayHeadroom
            if refresh > 0 {
                snapshot |= rendererCapabilityDisplayRefresh
            }
        }
        if refresh > 0 {
            snapshot |= UInt64(refresh) << rendererCapabilityRefreshShift
        }

        NativeState.rendererCapabilitySnapshotV1 = snapshot
        NativeState.rendererDisplayMaximumFramesPerSecond = refresh
        NativeState.rendererDisplayCurrentHeadroom = currentHeadroom
        NativeState.rendererDisplayPotentialHeadroom = potentialHeadroom
        return snapshot
    }
}

@_cdecl("metallum_create_metal_layer")
public func metallum_create_metal_layer(
    _ device: MTLDevice,
    _ contentsScale: Double
) -> UnsafeMutableRawPointer? {
    let layer = CAMetalLayer()
    layer.device = device
    layer.framebufferOnly = true
    layer.isOpaque = true
    layer.contentsScale = CGFloat(contentsScale)
    return retainedPointer(layer)
}

@_cdecl("metallum_NSView_setMetalLayer")
public func metallum_NSView_setMetalLayer(
    _ view: NSView,
    _ layer: CAMetalLayer
) {
    view.wantsLayer = true
    view.layer = layer
}

@_cdecl("metallum_NSView_clearLayer")
public func metallum_NSView_clearLayer(_ view: NSView) {
    view.layer = nil
    view.wantsLayer = false
}

@_cdecl("metallum_set_debug_labels_enabled")
public func metallum_set_debug_labels_enabled(_ enabled: Int32) {
    NativeState.debugLabelsEnabled = enabled != 0
}

@_cdecl("metallum_gpu_timing_set_benchmark_state")
public func metallum_gpu_timing_set_benchmark_state(
    _ segmentIndex: Int32,
    _ phase: Int32,
    _ scalerModePtr: UnsafePointer<CChar>?
) {
    NativeState.benchmarkTelemetryState.update(
        segmentIndex: segmentIndex,
        phaseValue: phase,
        scalerMode: stringFromOptionalCString(scalerModePtr) ?? "UNKNOWN"
    )
}

@_cdecl("metallum_gpu_timing_record_java_workload")
public func metallum_gpu_timing_record_java_workload(
    _ commandBuffer: MTLCommandBuffer,
    _ cpuBytes: UInt64,
    _ cpuOperations: UInt64,
    _ cpuRequestedHighWater: UInt64,
    _ cpuReservedHighWater: UInt64,
    _ gpuRequestedHighWater: UInt64,
    _ gpuReservedHighWater: UInt64,
    _ cpuRenderSubmissionNanos: UInt64
) {
    guard let stats = NativeState.gpuTimingStats else { return }
    stats.recordJavaWorkload(
        commandBuffer,
        cpuBytes: cpuBytes,
        cpuOperations: cpuOperations,
        cpuRequestedHighWater: cpuRequestedHighWater,
        cpuReservedHighWater: cpuReservedHighWater,
        gpuRequestedHighWater: gpuRequestedHighWater,
        gpuReservedHighWater: gpuReservedHighWater,
        cpuRenderSubmissionNanos: cpuRenderSubmissionNanos
    )
}

@_cdecl("metallum_MTLDevice_maxMemoryAllocationSize")
public func metallum_MTLDevice_maxMemoryAllocationSize(_ device: MTLDevice) -> UInt64 {
    min(UInt64(device.maxBufferLength), device.recommendedMaxWorkingSetSize)
}

@_cdecl("metallum_MTLFXSpatialScaler_supportsDevice")
public func metallum_MTLFXSpatialScaler_supportsDevice(_ device: MTLDevice) -> Int32 {
    MTLFXSpatialScalerDescriptor.supportsDevice(device) ? 1 : 0
}

@_cdecl("metallum_MTLDevice_makeCommandQueue")
public func metallum_MTLDevice_makeCommandQueue(
    _ device: MTLDevice,
    _ layer: CAMetalLayer
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard layer.device === device, let queue = device.makeCommandQueue() else {
            return nil
        }
        if #available(macOS 26.0, *),
           let residencySet = layer.residencySet as MTLResidencySet? {
            queue.addResidencySet(residencySet)
        }
        return retainedPointer(queue)
    }
}

@_cdecl("metallum_MTLCommandQueue_makeCommandBuffer")
public func metallum_MTLCommandQueue_makeCommandBuffer(
    _ queue: MTLCommandQueue,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let commandBuffer = queue.makeCommandBuffer() else {
            return nil
        }
        if NativeState.debugLabelsEnabled {
            commandBuffer.label = stringFromOptionalCString(labelPtr)
        }
        MetallumGpuTimingCoordinator.shared.register(commandBuffer)
        return retainedPointer(commandBuffer)
    }
}

@_cdecl("metallum_MTLCommandBuffer_commit")
public func metallum_MTLCommandBuffer_commit(_ commandBuffer: MTLCommandBuffer) {
    addGpuTimingCompletionHandler(to: commandBuffer)
    commandBuffer.commit()
}

@_cdecl("metallum_create_semaphore")
public func metallum_create_semaphore() -> UnsafeMutableRawPointer? {
    retainedPointer(DispatchSemaphore(value: 0))
}

@_cdecl("metallum_MTLCommandBuffer_commitWithSignal")
public func metallum_MTLCommandBuffer_commitWithSignal(_ commandBuffer: MTLCommandBuffer, _ semaphore: DispatchSemaphore) {
    while semaphore.wait(timeout: .now()) == .success {}
    addGpuTimingCompletionHandler(to: commandBuffer, signal: semaphore)
    commandBuffer.commit()
}

@_cdecl("metallum_semaphore_wait")
public func metallum_semaphore_wait(_ semaphore: DispatchSemaphore, _ timeoutMs: UInt64) -> Int32 {
    let timingStats = NativeState.gpuTimingStats
    let waitToken = timingStats?.beginWait()
    let waitStart = DispatchTime.now().uptimeNanoseconds
    let result: DispatchTimeoutResult
    if timeoutMs >= UInt64(Int.max) {
        result = semaphore.wait(timeout: .distantFuture)
    } else {
        result = semaphore.wait(timeout: .now() + .milliseconds(Int(timeoutMs)))
    }
    let waitEnd = DispatchTime.now().uptimeNanoseconds
    if let waitToken {
        timingStats?.recordWait(
            .frameSemaphore,
            nanoseconds: waitEnd >= waitStart ? waitEnd - waitStart : 0,
            token: waitToken
        )
    }
    guard result == .success else {
        return 1
    }
    semaphore.signal()
    return 0
}

@_cdecl("metallum_MTLCommandBuffer_isCompleted")
public func metallum_MTLCommandBuffer_isCompleted(_ commandBuffer: MTLCommandBuffer) -> Int32 {
    commandBuffer.status == .completed || commandBuffer.status == .error ? 1 : 0
}

@_cdecl("metallum_MTLCommandBuffer_waitUntilCompleted")
public func metallum_MTLCommandBuffer_waitUntilCompleted(_ commandBuffer: MTLCommandBuffer, _ timeoutMs: UInt64) -> Int32 {
    if commandBuffer.status == .completed || commandBuffer.status == .error {
        return 0
    }
    if timeoutMs == 0 {
        return 1
    }
    let timingStats = NativeState.gpuTimingStats
    let waitToken = timingStats?.beginWait()
    let waitStart = DispatchTime.now().uptimeNanoseconds
    commandBuffer.waitUntilCompleted()
    let waitEnd = DispatchTime.now().uptimeNanoseconds
    if let waitToken {
        timingStats?.recordWait(
            .commandBufferCompletion,
            nanoseconds: waitEnd >= waitStart ? waitEnd - waitStart : 0,
            token: waitToken
        )
    }
    return commandBuffer.status == .completed || commandBuffer.status == .error ? 0 : 1
}

@_cdecl("metallum_MTLCommandBuffer_pushDebugGroup")
public func metallum_MTLCommandBuffer_pushDebugGroup(
    _ commandBuffer: MTLCommandBuffer,
    _ labelPtr: UnsafePointer<CChar>?
) {
    autoreleasepool {
        commandBuffer.pushDebugGroup(stringFromOptionalCString(labelPtr) ?? "")
    }
}

@_cdecl("metallum_MTLCommandBuffer_popDebugGroup")
public func metallum_MTLCommandBuffer_popDebugGroup(_ commandBuffer: MTLCommandBuffer) {
    commandBuffer.popDebugGroup()
}

private enum MetallumSodiumLightPatchAbiV1 {
    static let recordBytes: UInt64 = 32
    static let maxRecords: UInt64 = 4_096

    static let encoded: Int32 = 1
    static let empty: Int32 = 0
    static let errorNullArgument: Int32 = -1
    static let errorPacket: Int32 = -2
    static let errorHandle: Int32 = -3
    static let errorObjectType: Int32 = -4
    static let errorDevice: Int32 = -5
    static let errorRange: Int32 = -6
    static let errorOverlap: Int32 = -7
    static let errorPipeline: Int32 = -8
    static let errorEncoder: Int32 = -9
}

private struct MetallumSodiumLightPatchRange {
    let start: UInt64
    let end: UInt64
}

private struct MetallumPreparedSodiumLightPatch {
    let geometry: MTLBuffer
    let sidecar: MTLBuffer
    let vertexOffset: UInt32
    let vertexCount: UInt32
}

@inline(__always)
private func sodiumLightPatchUInt64(_ packet: UnsafeRawPointer, _ offset: Int) -> UInt64 {
    UInt64(littleEndian: packet.loadUnaligned(fromByteOffset: offset, as: UInt64.self))
}

private func sodiumLightPatchRangesOverlap(
    _ rangesByBuffer: [UInt: [MetallumSodiumLightPatchRange]]
) -> Bool {
    for ranges in rangesByBuffer.values where ranges.count > 1 {
        let sorted = ranges.sorted {
            $0.start == $1.start ? $0.end < $1.end : $0.start < $1.start
        }
        for index in 1..<sorted.count where sorted[index].start < sorted[index - 1].end {
            return true
        }
    }
    return false
}

@_cdecl("metallum_MTLCommandBuffer_encodeSodiumLightLegacyPatchBatch_v1")
public func metallum_MTLCommandBuffer_encodeSodiumLightLegacyPatchBatch_v1(
    _ commandBuffer: MTLCommandBuffer?,
    _ globalFence: MTLFence?,
    _ packetPointer: UnsafeRawPointer?,
    _ packetCapacityBytes: UInt64,
    _ commandCount: UInt64
) -> Int32 {
    if commandCount == 0 {
        return MetallumSodiumLightPatchAbiV1.empty
    }
    guard let commandBuffer, let globalFence, let packet = packetPointer else {
        return MetallumSodiumLightPatchAbiV1.errorNullArgument
    }
    let (expectedBytes, byteCountOverflow) = commandCount.multipliedReportingOverflow(
        by: MetallumSodiumLightPatchAbiV1.recordBytes
    )
    guard !byteCountOverflow,
          commandCount <= MetallumSodiumLightPatchAbiV1.maxRecords,
          expectedBytes <= packetCapacityBytes,
          expectedBytes <= UInt64(Int.max) else {
        return MetallumSodiumLightPatchAbiV1.errorPacket
    }

    let deviceAddress = objectAddress(commandBuffer.device)
    guard objectAddress(globalFence.device) == deviceAddress else {
        return MetallumSodiumLightPatchAbiV1.errorDevice
    }

    // Validate and retain Swift references for every record before creating an
    // encoder. Every error status therefore leaves both buffers untouched.
    var prepared: [MetallumPreparedSodiumLightPatch] = []
    prepared.reserveCapacity(Int(commandCount))
    var geometryRanges: [UInt: [MetallumSodiumLightPatchRange]] = [:]
    var sidecarRanges: [UInt: [MetallumSodiumLightPatchRange]] = [:]
    var geometryAddresses = Set<UInt>()
    var sidecarAddresses = Set<UInt>()

    for commandIndex in 0..<Int(commandCount) {
        let record = commandIndex * Int(MetallumSodiumLightPatchAbiV1.recordBytes)
        let geometryAddress = sodiumLightPatchUInt64(packet, record)
        let sidecarAddress = sodiumLightPatchUInt64(packet, record + 8)
        let vertexOffset = sodiumLightPatchUInt64(packet, record + 16)
        let vertexCount = sodiumLightPatchUInt64(packet, record + 24)

        guard geometryAddress != 0, sidecarAddress != 0,
              let geometryObject = bindingPacketObject(geometryAddress),
              let sidecarObject = bindingPacketObject(sidecarAddress) else {
            return MetallumSodiumLightPatchAbiV1.errorHandle
        }
        guard let geometry = geometryObject as? MTLBuffer,
              let sidecar = sidecarObject as? MTLBuffer else {
            return MetallumSodiumLightPatchAbiV1.errorObjectType
        }
        guard objectAddress(geometry.device) == deviceAddress,
              objectAddress(sidecar.device) == deviceAddress else {
            return MetallumSodiumLightPatchAbiV1.errorDevice
        }

        let (vertexEnd, vertexOverflow) = vertexOffset.addingReportingOverflow(vertexCount)
        let (geometryByteEnd, geometryOverflow) = vertexEnd.multipliedReportingOverflow(by: 20)
        let (sidecarByteEnd, sidecarOverflow) = vertexEnd.multipliedReportingOverflow(by: 2)
        guard vertexCount > 0,
              !vertexOverflow, !geometryOverflow, !sidecarOverflow,
              vertexEnd <= UInt64(UInt32.max),
              geometryByteEnd <= UInt64(geometry.length),
              sidecarByteEnd <= UInt64(sidecar.length) else {
            return MetallumSodiumLightPatchAbiV1.errorRange
        }

        let geometryKey = objectAddress(geometry)
        let sidecarKey = objectAddress(sidecar)
        geometryAddresses.insert(geometryKey)
        sidecarAddresses.insert(sidecarKey)
        let range = MetallumSodiumLightPatchRange(start: vertexOffset, end: vertexEnd)
        geometryRanges[geometryKey, default: []].append(range)
        sidecarRanges[sidecarKey, default: []].append(range)
        prepared.append(
            MetallumPreparedSodiumLightPatch(
                geometry: geometry,
                sidecar: sidecar,
                vertexOffset: UInt32(vertexOffset),
                vertexCount: UInt32(vertexCount)
            )
        )
    }

    guard geometryAddresses.isDisjoint(with: sidecarAddresses),
          !sodiumLightPatchRangesOverlap(geometryRanges),
          !sodiumLightPatchRangesOverlap(sidecarRanges) else {
        return MetallumSodiumLightPatchAbiV1.errorOverlap
    }
    guard let pipeline = ensureSodiumLightPatchPipeline(device: commandBuffer.device),
          pipeline.threadExecutionWidth > 0,
          pipeline.maxTotalThreadsPerThreadgroup > 0 else {
        return MetallumSodiumLightPatchAbiV1.errorPipeline
    }

    let pass = MTLComputePassDescriptor()
    guard let encoder = trackedMakeComputeCommandEncoder(commandBuffer, descriptor: pass) else {
        return MetallumSodiumLightPatchAbiV1.errorEncoder
    }
    encoder.label = "Metallum Sodium legacy-light patch batch"
    encoder.waitForFence(globalFence)
    encoder.setComputePipelineState(pipeline)
    let threadgroupWidth = min(
        pipeline.threadExecutionWidth,
        pipeline.maxTotalThreadsPerThreadgroup
    )
    for command in prepared {
        encoder.setBuffer(command.geometry, offset: 0, index: 0)
        encoder.setBuffer(command.sidecar, offset: 0, index: 1)
        var range = SIMD2<UInt32>(command.vertexOffset, command.vertexCount)
        withUnsafeBytes(of: &range) { bytes in
            encoder.setBytes(bytes.baseAddress!, length: bytes.count, index: 2)
        }
        encoder.dispatchThreads(
            MTLSize(width: Int(command.vertexCount), height: 1, depth: 1),
            threadsPerThreadgroup: MTLSize(width: threadgroupWidth, height: 1, depth: 1)
        )
    }
    encoder.updateFence(globalFence)
    trackedEndEncoding(encoder)
    return MetallumSodiumLightPatchAbiV1.encoded
}

@_cdecl("metallum_MTLCommandBuffer_makeBlitCommandEncoder")
public func metallum_MTLCommandBuffer_makeBlitCommandEncoder(
    _ commandBuffer: MTLCommandBuffer
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(trackedMakeBlitCommandEncoder(commandBuffer))
    }
}

@_cdecl("metallum_MTLCommandEncoder_endEncoding")
public func metallum_MTLCommandEncoder_endEncoding(_ encoder: MTLCommandEncoder) {
    trackedEndEncoding(encoder)
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer(
    _ blit: MTLBlitCommandEncoder,
    _ sourceBuffer: MTLBuffer,
    _ sourceOffset: UInt64,
    _ destinationBuffer: MTLBuffer,
    _ destinationOffset: UInt64,
    _ length: UInt64
) {
    trackWorkloadCopy(
        blit,
        source: sourceBuffer,
        destination: destinationBuffer,
        bytes: length
    )
    blit.copy(from: sourceBuffer, sourceOffset: Int(sourceOffset), to: destinationBuffer, destinationOffset: Int(destinationOffset), size: Int(length))
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToTexture(
    _ blit: MTLBlitCommandEncoder,
    _ sourceBuffer: MTLBuffer,
    _ sourceOffset: UInt64,
    _ texture: MTLTexture,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) {
    trackWorkloadCopy(
        blit,
        source: sourceBuffer,
        destination: texture,
        bytes: bytesPerImage
    )
    blit.copy(
        from: sourceBuffer,
        sourceOffset: Int(sourceOffset),
        sourceBytesPerRow: Int(bytesPerRow),
        sourceBytesPerImage: Int(bytesPerImage),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: texture,
        destinationSlice: Int(slice),
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0)
    )
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromTextureToTexture")
public func metallum_MTLBlitCommandEncoder_copyFromTextureToTexture(
    _ blit: MTLBlitCommandEncoder,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ mipLevel: UInt64,
    _ sourceX: UInt64,
    _ sourceY: UInt64,
    _ destX: UInt64,
    _ destY: UInt64,
    _ width: UInt64,
    _ height: UInt64
) {
    trackWorkloadCopy(
        blit,
        source: sourceTexture,
        destination: destinationTexture,
        bytes: nil
    )
    blit.copy(
        from: sourceTexture,
        sourceSlice: 0,
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(sourceX), y: Int(sourceY), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationTexture,
        destinationSlice: 0,
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(destX), y: Int(destY), z: 0)
    )
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer")
public func metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer(
    _ blit: MTLBlitCommandEncoder,
    _ sourceTexture: MTLTexture,
    _ destinationBuffer: MTLBuffer,
    _ destinationOffset: UInt64,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) {
    trackWorkloadCopy(
        blit,
        source: sourceTexture,
        destination: destinationBuffer,
        bytes: bytesPerImage
    )
    blit.copy(
        from: sourceTexture,
        sourceSlice: Int(slice),
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationBuffer,
        destinationOffset: Int(destinationOffset),
        destinationBytesPerRow: Int(bytesPerRow),
        destinationBytesPerImage: Int(bytesPerImage)
    )
}

@_cdecl("metallum_create_buffer")
public func metallum_create_buffer(
    _ device: MTLDevice,
    _ length: Int,
    _ options: MTLResourceOptions
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let buffer = device.makeBuffer(length: length, options: options) else {
            return nil
        }
        trackBufferAllocation(buffer)
        return retainedPointer(buffer)
    }
}

@_cdecl("metallum_create_static_geometry_buffer")
public func metallum_create_static_geometry_buffer(
    _ device: MTLDevice,
    _ length: Int
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let buffer = MetallumStaticGeometryHeapRegistry.shared.makeBuffer(
            device: device,
            length: length
        ) else {
            return nil
        }
        trackBufferAllocation(buffer)
        return retainedPointer(buffer)
    }
}

@_cdecl("metallum_create_texture_2d")
public func metallum_create_texture_2d(
    _ device: MTLDevice,
    _ pixelFormat: MTLPixelFormat,
    _ width: UInt64,
    _ height: UInt64,
    _ depthOrLayers: UInt64,
    _ mipLevels: UInt64,
    _ cubeCompatible: UInt64,
    _ usage: MTLTextureUsage,
    _ storageMode: MTLStorageMode,
    _ trackedHazards: Int32,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: pixelFormat,
            width: Int(width),
            height: Int(height),
            mipmapped: mipLevels > 1
        )

        if cubeCompatible != 0 {
            if depthOrLayers > 6 {
                descriptor.textureType = MTLTextureType.typeCubeArray
                descriptor.arrayLength = Int(depthOrLayers) / 6
            } else {
                descriptor.textureType = MTLTextureType.typeCube
                descriptor.arrayLength = 1
            }
        } else if depthOrLayers > 1 {
            descriptor.textureType = MTLTextureType.type2DArray
            descriptor.arrayLength = Int(depthOrLayers)
        }

        descriptor.mipmapLevelCount = max(Int(mipLevels), 1)
        descriptor.usage = usage
        descriptor.storageMode = storageMode
        descriptor.hazardTrackingMode = trackedHazards != 0 ? .tracked : .untracked
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        texture.label = stringFromOptionalCString(labelPtr)
        trackTextureAllocation(texture)
        return retainedPointer(texture)
    }
}

@_cdecl("metallum_create_texture_view")
public func metallum_create_texture_view(_ texture: MTLTexture, _ baseMipLevel: UInt64, _ mipLevelCount: UInt64) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard mipLevelCount > 0 else {
            return nil
        }

        let baseLevel = Int(baseMipLevel)
        let levelCount = Int(mipLevelCount)
        guard baseLevel < texture.mipmapLevelCount, baseLevel + levelCount <= texture.mipmapLevelCount else {
            return nil
        }

        let view = texture.__newTextureView(
            with: texture.pixelFormat,
            textureType: texture.textureType,
            levels: NSRange(location: baseLevel, length: levelCount),
            slices: NSRange(location: 0, length: textureSliceCount(texture))
        )

        return retainedPointer(view)
    }
}

@_cdecl("metallum_create_buffer_texture_view")
public func metallum_create_buffer_texture_view(
    _ buffer: MTLBuffer,
    _ pixelFormat: MTLPixelFormat,
    _ offset: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard
            pixelFormat != .invalid,
            width > 0,
            bytesPerRow > 0
        else {
            return nil
        }

        let nativeOffset = Int(offset)
        let nativeWidth = Int(width)
        let nativeBytesPerRow = Int(bytesPerRow)
        guard nativeOffset >= 0, nativeWidth > 0, nativeBytesPerRow > 0, nativeOffset <= buffer.length, nativeBytesPerRow <= buffer.length - nativeOffset else {
            return nil
        }

        let alignment = buffer.device.minimumLinearTextureAlignment(for: pixelFormat)
        guard alignment > 0, nativeOffset % alignment == 0 else {
            return nil
        }

        let alignedBytesPerRow = roundUp(nativeBytesPerRow, alignment: alignment)
        let descriptor = MTLTextureDescriptor.textureBufferDescriptor(
            with: pixelFormat,
            width: nativeWidth,
            resourceOptions: [],
            usage: MTLTextureUsage.shaderRead
        )
        descriptor.storageMode = buffer.storageMode
        descriptor.hazardTrackingMode = .untracked

        return retainedPointer(buffer.makeTexture(descriptor: descriptor, offset: nativeOffset, bytesPerRow: alignedBytesPerRow))
    }
}

private func roundUp(_ value: Int, alignment: Int) -> Int {
    let remainder = value % alignment
    return remainder == 0 ? value : value + alignment - remainder
}

@_cdecl("metallum_create_sampler")
public func metallum_create_sampler(
    _ device: MTLDevice,
    _ addressModeU: MTLSamplerAddressMode,
    _ addressModeV: MTLSamplerAddressMode,
    _ minFilter: MTLSamplerMinMagFilter,
    _ magFilter: MTLSamplerMinMagFilter,
    _ mipFilter: MTLSamplerMipFilter,
    _ compareFunction: MTLCompareFunction,
    _ maxAnisotropy: Int32,
    _ lodMaxClamp: Double
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = minFilter
        descriptor.magFilter = magFilter
        descriptor.mipFilter = mipFilter
        descriptor.compareFunction = compareFunction
        descriptor.sAddressMode = addressModeU
        descriptor.tAddressMode = addressModeV
        descriptor.maxAnisotropy = max(Int(maxAnisotropy), 1)
        descriptor.lodMinClamp = 0.0
        descriptor.lodMaxClamp = lodMaxClamp >= 0.0 && lodMaxClamp.isFinite ? Float(lodMaxClamp) : Float.greatestFiniteMagnitude
        return retainedPointer(device.makeSamplerState(descriptor: descriptor))
    }
}

@_cdecl("metallum_MTLDevice_makeDepthStencilState")
public func metallum_MTLDevice_makeDepthStencilState(
    _ device: MTLDevice,
    _ depthCompareOp: MTLCompareFunction,
    _ writeDepth: Int32
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        unretainedPointer(ensureDepthStencilState(device: device, compareOp: depthCompareOp, writeDepth: writeDepth != 0))
    }
}

@_cdecl("metallum_MTLCommandBuffer_makeRenderCommandEncoder")
public func metallum_MTLCommandBuffer_makeRenderCommandEncoder(
    _ commandBuffer: MTLCommandBuffer,
    _ colorTexture: MTLTexture?,
    _ semanticTexture: MTLTexture?,
    _ depthTexture: MTLTexture?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ colorLoadAction: Int32,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ clearSemanticEnabled: Int32,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double,
    _ gpuTimingStageId: Int32
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard colorTexture != nil || depthTexture != nil else {
            return nil
        }
        let depthFormat = depthTexture?.pixelFormat ?? .invalid
        let stencilFormat = stencilPixelFormat(for: depthFormat)

        let renderPass = MTLRenderPassDescriptor()
        if let colorTexture {
            renderPass.colorAttachments[0].texture = colorTexture
            if colorLoadAction == 1 {
                renderPass.colorAttachments[0].loadAction = .clear
                renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
            } else if colorLoadAction == 0 {
                renderPass.colorAttachments[0].loadAction = .load
            } else if colorLoadAction == 2 {
                renderPass.colorAttachments[0].loadAction = .dontCare
            } else {
                return nil
            }
            renderPass.colorAttachments[0].storeAction = .store
        }

        if let semanticTexture {
            renderPass.colorAttachments[1].texture = semanticTexture
            renderPass.colorAttachments[1].loadAction = clearSemanticEnabled != 0 ? .clear : .load
            renderPass.colorAttachments[1].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0)
            renderPass.colorAttachments[1].storeAction = .store
        }

        if let depthTexture {
            renderPass.depthAttachment.texture = depthTexture
            renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? .clear : .load
            renderPass.depthAttachment.clearDepth = clearDepth
            renderPass.depthAttachment.storeAction = .store
            if stencilFormat != .invalid {
                renderPass.stencilAttachment.texture = depthTexture
                renderPass.stencilAttachment.loadAction = .dontCare
                renderPass.stencilAttachment.storeAction = .dontCare
            }
        }

        attachGpuTiming(
            renderPass,
            commandBuffer: commandBuffer,
            stage: MetallumGpuTimingStage.fromJavaId(gpuTimingStageId)
        )

        guard let encoder = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: renderPass) else {
            return nil
        }
        encoder.setViewport(MTLViewport(originX: 0.0, originY: 0.0, width: viewportWidth, height: viewportHeight, znear: 0.0, zfar: 1.0))
        return retainedPointer(encoder)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setRenderPipelineState")
public func metallum_MTLRenderCommandEncoder_setRenderPipelineState(_ encoder: MTLRenderCommandEncoder, _ pipeline: MTLRenderPipelineState) {
    encoder.setRenderPipelineState(pipeline)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthStencilState")
public func metallum_MTLRenderCommandEncoder_setDepthStencilState(_ encoder: MTLRenderCommandEncoder, _ state: MTLDepthStencilState?) {
    encoder.setDepthStencilState(state)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthBias")
public func metallum_MTLRenderCommandEncoder_setDepthBias(
    _ encoder: MTLRenderCommandEncoder,
    _ depthBias: Float,
    _ slopeScale: Float,
    _ clamp: Float
) {
    encoder.setDepthBias(depthBias, slopeScale: slopeScale, clamp: clamp)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setFrontFacingWinding")
public func metallum_MTLRenderCommandEncoder_setFrontFacingWinding(_ encoder: MTLRenderCommandEncoder, _ winding: MTLWinding) {
    encoder.setFrontFacing(winding)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setCullMode")
public func metallum_MTLRenderCommandEncoder_setCullMode(_ encoder: MTLRenderCommandEncoder, _ cullMode: MTLCullMode) {
    encoder.setCullMode(cullMode)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTriangleFillMode")
public func metallum_MTLRenderCommandEncoder_setTriangleFillMode(_ encoder: MTLRenderCommandEncoder, _ fillMode: MTLTriangleFillMode) {
    encoder.setTriangleFillMode(fillMode)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setBuffer")
public func metallum_MTLRenderCommandEncoder_setBuffer(_ encoder: MTLRenderCommandEncoder, _ buffer: MTLBuffer?, _ offset: UInt64, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexBuffer(buffer, offset: Int(offset), index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentBuffer(buffer, offset: Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setBufferOffset")
public func metallum_MTLRenderCommandEncoder_setBufferOffset(_ encoder: MTLRenderCommandEncoder, _ offset: UInt64, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexBufferOffset(Int(offset), index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentBufferOffset(Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTexture")
public func metallum_MTLRenderCommandEncoder_setTexture(_ encoder: MTLRenderCommandEncoder, _ texture: MTLTexture?, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexTexture(texture, index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentTexture(texture, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTextureAndSampler")
public func metallum_MTLRenderCommandEncoder_setTextureAndSampler(_ encoder: MTLRenderCommandEncoder, _ texture: MTLTexture?, _ sampler: MTLSamplerState?, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexTexture(texture, index: Int(index))
        encoder.setVertexSamplerState(sampler, index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentTexture(texture, index: Int(index))
        encoder.setFragmentSamplerState(sampler, index: Int(index))
    }
}

private enum MetallumResourceBindingAbi {
    static let version: UInt32 = 1
    static let headerBytes = 32
    static let recordBytes = 48
    static let maxRecords: UInt32 = 64

    static let capabilityUniformBuffer: UInt64 = 1
    static let capabilityTextureSampler: UInt64 = 1 << 1
    static let capabilityTexelTexture: UInt64 = 1 << 2
    static let supportedCapabilities = capabilityUniformBuffer
        | capabilityTextureSampler
        | capabilityTexelTexture

    static let typeUniformBuffer: UInt32 = 1
    static let typeTextureSampler: UInt32 = 2
    static let typeTexelTexture: UInt32 = 3

    static let stageVertex: UInt32 = 1
    static let stageFragment: UInt32 = 2
    static let stageAll = stageVertex | stageFragment

    static let maxBufferBindings: UInt32 = 31
    static let maxTextureBindings: UInt32 = 128
    static let maxSamplerBindings: UInt32 = 16

    static let ok: Int32 = 1
    static let errorNullArgument: Int32 = -1
    static let errorPacketCapacity: Int32 = -2
    static let errorVersion: Int32 = -3
    static let errorByteSize: Int32 = -4
    static let errorCapabilities: Int32 = -5
    static let errorCount: Int32 = -6
    static let errorLayout: Int32 = -7
    static let errorType: Int32 = -8
    static let errorStage: Int32 = -9
    static let errorIndex: Int32 = -10
    static let errorHandle: Int32 = -11
    static let errorRange: Int32 = -12
    static let errorDuplicateIndex: Int32 = -13
    static let errorObjectType: Int32 = -14
    static let errorNativeBufferRange: Int32 = -15
}

@inline(__always)
private func bindingPacketUInt32(_ packet: UnsafeRawPointer, _ offset: Int) -> UInt32 {
    UInt32(littleEndian: packet.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
}

@inline(__always)
private func bindingPacketUInt64(_ packet: UnsafeRawPointer, _ offset: Int) -> UInt64 {
    UInt64(littleEndian: packet.loadUnaligned(fromByteOffset: offset, as: UInt64.self))
}

@inline(__always)
private func bindingPacketObject(_ address: UInt64) -> AnyObject? {
    guard address != 0,
          let pointer = UnsafeMutableRawPointer(bitPattern: UInt(address)) else {
        return nil
    }
    return Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue()
}

@_cdecl("metallum_MTLRenderCommandEncoder_applyResourceBindings_v1")
public func metallum_MTLRenderCommandEncoder_applyResourceBindings_v1(
    _ encoder: MTLRenderCommandEncoder?,
    _ packetPointer: UnsafeRawPointer?,
    _ packetCapacityBytes: UInt64
) -> Int32 {
    guard let encoder, let packet = packetPointer else {
        return MetallumResourceBindingAbi.errorNullArgument
    }
    guard packetCapacityBytes >= UInt64(MetallumResourceBindingAbi.headerBytes),
          packetCapacityBytes <= UInt64(Int.max) else {
        return MetallumResourceBindingAbi.errorPacketCapacity
    }

    let version = bindingPacketUInt32(packet, 0)
    guard version == MetallumResourceBindingAbi.version else {
        return MetallumResourceBindingAbi.errorVersion
    }
    let byteSize = UInt64(bindingPacketUInt32(packet, 4))
    let capabilities = bindingPacketUInt64(packet, 8)
    let count = bindingPacketUInt32(packet, 16)
    let recordBytes = bindingPacketUInt32(packet, 20)
    let recordCapacity = bindingPacketUInt32(packet, 24)
    let reserved = bindingPacketUInt32(packet, 28)

    guard capabilities & ~MetallumResourceBindingAbi.supportedCapabilities == 0 else {
        return MetallumResourceBindingAbi.errorCapabilities
    }
    guard count <= MetallumResourceBindingAbi.maxRecords,
          count <= recordCapacity else {
        return MetallumResourceBindingAbi.errorCount
    }
    guard recordBytes == UInt32(MetallumResourceBindingAbi.recordBytes),
          recordCapacity == MetallumResourceBindingAbi.maxRecords,
          reserved == 0 else {
        return MetallumResourceBindingAbi.errorLayout
    }
    let expectedByteSize = UInt64(MetallumResourceBindingAbi.headerBytes)
        + UInt64(count) * UInt64(MetallumResourceBindingAbi.recordBytes)
    guard byteSize == expectedByteSize,
          byteSize <= packetCapacityBytes else {
        return MetallumResourceBindingAbi.errorByteSize
    }

    // Validate the entire packet before making the first encoder state change.
    var occupiedBindingMask: UInt64 = 0
    for recordIndex in 0..<Int(count) {
        let record = MetallumResourceBindingAbi.headerBytes
            + recordIndex * MetallumResourceBindingAbi.recordBytes
        let type = bindingPacketUInt32(packet, record)
        let stage = bindingPacketUInt32(packet, record + 4)
        let bindingIndex = bindingPacketUInt32(packet, record + 8)
        let recordReserved = bindingPacketUInt32(packet, record + 12)
        let primaryAddress = bindingPacketUInt64(packet, record + 16)
        let secondaryAddress = bindingPacketUInt64(packet, record + 24)
        let offset = bindingPacketUInt64(packet, record + 32)
        let length = bindingPacketUInt64(packet, record + 40)

        guard recordReserved == 0 else {
            return MetallumResourceBindingAbi.errorLayout
        }
        guard stage != 0, stage & ~MetallumResourceBindingAbi.stageAll == 0 else {
            return MetallumResourceBindingAbi.errorStage
        }
        guard bindingIndex < MetallumResourceBindingAbi.maxRecords else {
            return MetallumResourceBindingAbi.errorIndex
        }
        let bindingBit = UInt64(1) << bindingIndex
        guard occupiedBindingMask & bindingBit == 0 else {
            return MetallumResourceBindingAbi.errorDuplicateIndex
        }
        occupiedBindingMask |= bindingBit
        guard primaryAddress != 0,
              let primaryObject = bindingPacketObject(primaryAddress) else {
            return MetallumResourceBindingAbi.errorHandle
        }

        switch type {
        case MetallumResourceBindingAbi.typeUniformBuffer:
            guard capabilities & MetallumResourceBindingAbi.capabilityUniformBuffer != 0 else {
                return MetallumResourceBindingAbi.errorCapabilities
            }
            guard bindingIndex < MetallumResourceBindingAbi.maxBufferBindings else {
                return MetallumResourceBindingAbi.errorIndex
            }
            guard secondaryAddress == 0, length > 0 else {
                return MetallumResourceBindingAbi.errorRange
            }
            guard offset <= UInt64(Int.max) else {
                return MetallumResourceBindingAbi.errorRange
            }
            guard let buffer = primaryObject as? MTLBuffer else {
                return MetallumResourceBindingAbi.errorObjectType
            }
            let nativeLength = UInt64(buffer.length)
            guard offset <= nativeLength, length <= nativeLength - offset else {
                return MetallumResourceBindingAbi.errorNativeBufferRange
            }
        case MetallumResourceBindingAbi.typeTextureSampler:
            guard capabilities & MetallumResourceBindingAbi.capabilityTextureSampler != 0 else {
                return MetallumResourceBindingAbi.errorCapabilities
            }
            guard bindingIndex < MetallumResourceBindingAbi.maxSamplerBindings else {
                return MetallumResourceBindingAbi.errorIndex
            }
            guard offset == 0, length == 0 else {
                return MetallumResourceBindingAbi.errorRange
            }
            guard secondaryAddress != 0,
                  let secondaryObject = bindingPacketObject(secondaryAddress) else {
                return MetallumResourceBindingAbi.errorHandle
            }
            guard primaryObject is MTLTexture, secondaryObject is MTLSamplerState else {
                return MetallumResourceBindingAbi.errorObjectType
            }
        case MetallumResourceBindingAbi.typeTexelTexture:
            guard capabilities & MetallumResourceBindingAbi.capabilityTexelTexture != 0 else {
                return MetallumResourceBindingAbi.errorCapabilities
            }
            guard bindingIndex < MetallumResourceBindingAbi.maxTextureBindings else {
                return MetallumResourceBindingAbi.errorIndex
            }
            guard secondaryAddress == 0, offset == 0, length == 0 else {
                return MetallumResourceBindingAbi.errorRange
            }
            guard primaryObject is MTLTexture else {
                return MetallumResourceBindingAbi.errorObjectType
            }
        default:
            return MetallumResourceBindingAbi.errorType
        }
    }

    for recordIndex in 0..<Int(count) {
        let record = MetallumResourceBindingAbi.headerBytes
            + recordIndex * MetallumResourceBindingAbi.recordBytes
        let type = bindingPacketUInt32(packet, record)
        let stage = bindingPacketUInt32(packet, record + 4)
        let bindingIndex = Int(bindingPacketUInt32(packet, record + 8))
        let primaryAddress = bindingPacketUInt64(packet, record + 16)
        let secondaryAddress = bindingPacketUInt64(packet, record + 24)
        let offset = Int(bindingPacketUInt64(packet, record + 32))
        let primaryObject = bindingPacketObject(primaryAddress)!

        switch type {
        case MetallumResourceBindingAbi.typeUniformBuffer:
            let buffer = primaryObject as! MTLBuffer
            if stage & MetallumResourceBindingAbi.stageVertex != 0 {
                encoder.setVertexBuffer(buffer, offset: offset, index: bindingIndex)
            }
            if stage & MetallumResourceBindingAbi.stageFragment != 0 {
                encoder.setFragmentBuffer(buffer, offset: offset, index: bindingIndex)
            }
        case MetallumResourceBindingAbi.typeTextureSampler:
            let texture = primaryObject as! MTLTexture
            let sampler = bindingPacketObject(secondaryAddress)! as! MTLSamplerState
            if stage & MetallumResourceBindingAbi.stageVertex != 0 {
                encoder.setVertexTexture(texture, index: bindingIndex)
                encoder.setVertexSamplerState(sampler, index: bindingIndex)
            }
            if stage & MetallumResourceBindingAbi.stageFragment != 0 {
                encoder.setFragmentTexture(texture, index: bindingIndex)
                encoder.setFragmentSamplerState(sampler, index: bindingIndex)
            }
        case MetallumResourceBindingAbi.typeTexelTexture:
            let texture = primaryObject as! MTLTexture
            if stage & MetallumResourceBindingAbi.stageVertex != 0 {
                encoder.setVertexTexture(texture, index: bindingIndex)
            }
            if stage & MetallumResourceBindingAbi.stageFragment != 0 {
                encoder.setFragmentTexture(texture, index: bindingIndex)
            }
        default:
            // The validation pass guarantees this branch is unreachable.
            return MetallumResourceBindingAbi.errorType
        }
    }
    return MetallumResourceBindingAbi.ok
}

@_cdecl("metallum_MTLRenderCommandEncoder_setScissorRect")
public func metallum_MTLRenderCommandEncoder_setScissorRect(
    _ encoder: MTLRenderCommandEncoder,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64
) {
    encoder.setScissorRect(MTLScissorRect(x: Int(x), y: Int(y), width: Int(width), height: Int(height)))
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawPrimitives")
public func metallum_MTLRenderCommandEncoder_drawPrimitives(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ firstVertex: Int,
    _ vertexCount: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    encoder.drawPrimitives(
        type: primitiveType,
        vertexStart: firstVertex,
        vertexCount: vertexCount,
        instanceCount: instanceCount,
        baseInstance: baseInstance
    )
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitives")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitives(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indexCount: Int,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ indexBufferOffset: Int,
    _ instanceCount: Int,
    _ baseVertex: Int,
    _ baseInstance: Int
) {
    encoder.drawIndexedPrimitives(
        type: primitiveType,
        indexCount: indexCount,
        indexType: indexType,
        indexBuffer: indexBuffer,
        indexBufferOffset: indexBufferOffset,
        instanceCount: instanceCount,
        baseVertex: baseVertex,
        baseInstance: baseInstance
    )
}

@_cdecl("metallum_MTLRenderCommandEncoder_multiDrawIndexed")
public func metallum_MTLRenderCommandEncoder_multiDrawIndexed(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ firstIndexOffsets: UnsafePointer<Int>,
    _ indexCounts: UnsafePointer<Int32>,
    _ vertexOffsets: UnsafePointer<Int32>,
    _ drawCount: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    for i in 0..<drawCount {
        let indexCount = Int(indexCounts[i])
        if indexCount > 0 {
            encoder.drawIndexedPrimitives(
                type: primitiveType,
                indexCount: indexCount,
                indexType: indexType,
                indexBuffer: indexBuffer,
                indexBufferOffset: firstIndexOffsets[i],
                instanceCount: instanceCount,
                baseVertex: Int(vertexOffsets[i]),
                baseInstance: baseInstance
            )
        }
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ indirectBuffer: MTLBuffer,
    _ indirectBufferOffset: UInt64,
    _ drawCount: Int,
    _ stride: UInt64
) {
    var offset = Int(indirectBufferOffset)
    for _ in 0..<drawCount {
        encoder.drawIndexedPrimitives(
            type: primitiveType,
            indexType: indexType,
            indexBuffer: indexBuffer,
            indexBufferOffset: 0,
            indirectBuffer: indirectBuffer,
            indirectBufferOffset: offset
        )
        offset += Int(stride)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesCpuCommands")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesCpuCommands(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ commands: UnsafeRawPointer,
    _ drawCount: Int,
    _ stride: UInt64
) {
    guard drawCount > 0, stride >= 20 else { return }
    let indexStride: UInt32
    switch indexType {
    case .uint16:
        indexStride = 2
    case .uint32:
        indexStride = 4
    @unknown default:
        return
    }

    var command = commands
    for _ in 0..<drawCount {
        let indexCount = command.load(fromByteOffset: 0, as: UInt32.self)
        let instanceCount = command.load(fromByteOffset: 4, as: UInt32.self)
        let firstIndex = command.load(fromByteOffset: 8, as: UInt32.self)
        let baseVertex = command.load(fromByteOffset: 12, as: Int32.self)
        let baseInstance = command.load(fromByteOffset: 16, as: UInt32.self)
        if indexCount > 0, instanceCount > 0 {
            encoder.drawIndexedPrimitives(
                type: primitiveType,
                indexCount: Int(indexCount),
                indexType: indexType,
                indexBuffer: indexBuffer,
                indexBufferOffset: Int(UInt64(firstIndex) * UInt64(indexStride)),
                instanceCount: Int(instanceCount),
                baseVertex: Int(baseVertex),
                baseInstance: Int(baseInstance)
            )
        }
        command = command.advanced(by: Int(stride))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect")
public func metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indirectBuffer: MTLBuffer,
    _ indirectBufferOffset: UInt64,
    _ drawCount: Int,
    _ stride: UInt64
) {
    var offset = Int(indirectBufferOffset)
    for _ in 0..<drawCount {
        encoder.drawPrimitives(
            type: primitiveType,
            indirectBuffer: indirectBuffer,
            indirectBufferOffset: offset
        )
        offset += Int(stride)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
    _ encoder: MTLRenderCommandEncoder,
    _ indexBuffer: MTLBuffer,
    _ fanIndexBuffer: MTLBuffer,
    _ fanIndexBufferOffset: Int,
    _ indexType: Int,
    _ indexOffsetBytes: Int,
    _ indexCount: Int,
    _ baseVertex: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    guard let generatedIndexCount = writeIndexedTriangleFanIndices(
        sourceIndexBuffer: indexBuffer,
        destinationIndexBuffer: fanIndexBuffer,
        destinationOffset: fanIndexBufferOffset,
        indexType: indexType,
        indexOffsetBytes: indexOffsetBytes,
        indexCount: indexCount
    ) else {
        return
    }
    encoder.drawIndexedPrimitives(
        type: .triangle,
        indexCount: generatedIndexCount,
        indexType: .uint32,
        indexBuffer: fanIndexBuffer,
        indexBufferOffset: fanIndexBufferOffset,
        instanceCount: instanceCount,
        baseVertex: baseVertex,
        baseInstance: baseInstance
    )
}

@_cdecl("metallum_MTLCommandBuffer_clearColorDepthTexturesRegion")
public func metallum_MTLCommandBuffer_clearColorDepthTexturesRegion(
    _ commandBuffer: MTLCommandBuffer,
    _ colorTexture: MTLTexture,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ depthTexture: MTLTexture,
    _ clearDepth: Double,
    _ x: Int32,
    _ y: Int32,
    _ width: Int32,
    _ height: Int32,
    _ globalFence: MTLFence?
) {
    return autoreleasepool {
        guard width > 0, height > 0 else {
            return
        }

        let textureWidth = min(colorTexture.width, depthTexture.width)
        let textureHeight = min(colorTexture.height, depthTexture.height)
        let clampedX = max(Int(x), 0)
        let clampedY = max(Int(y), 0)
        let clampedMaxX = min(Int(x) + Int(width), textureWidth)
        let clampedMaxY = min(Int(y) + Int(height), textureHeight)
        if clampedX >= clampedMaxX || clampedY >= clampedMaxY {
            return
        }
        let scissorRect = MTLScissorRect(x: clampedX, y: clampedY, width: clampedMaxX - clampedX, height: clampedMaxY - clampedY)
        let fullRegion = clampedX == 0 && clampedY == 0 && clampedMaxX == textureWidth && clampedMaxY == textureHeight

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = colorTexture
        renderPass.colorAttachments[0].loadAction = fullRegion ? .clear : .load
        renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
        renderPass.colorAttachments[0].storeAction = .store

        renderPass.depthAttachment.texture = depthTexture
        renderPass.depthAttachment.loadAction = fullRegion ? .clear : .load
        renderPass.depthAttachment.clearDepth = clearDepth
        renderPass.depthAttachment.storeAction = .store

        let depthFormat = depthTexture.pixelFormat
        if depthFormat == .depth24Unorm_stencil8 || depthFormat == .depth32Float_stencil8 {
            renderPass.stencilAttachment.texture = depthTexture
            renderPass.stencilAttachment.loadAction = .dontCare
            renderPass.stencilAttachment.storeAction = .dontCare
        }

        guard let encoder = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: renderPass) else {
            return
        }

        if let globalFence {
            encoder.waitForFence(globalFence, before: .fragment)
        }

        if !fullRegion {
            guard
                let pipeline = ensureClearColorDepthPipeline(commandBuffer.device, colorTexture.pixelFormat, depthTexture.pixelFormat),
                let depthState = ensureDepthStencilState(device: commandBuffer.device, compareOp: MTLCompareFunction.always, writeDepth: true)
            else {
                trackedEndEncoding(encoder)
                return
            }
            encodeClearDraw(
                encoder: encoder,
                pipeline: pipeline,
                textureWidth: textureWidth,
                textureHeight: textureHeight,
                clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
                scissorRect: scissorRect,
                depthState: depthState,
                clearDepth: clearDepth
            )
        }

        if let globalFence {
            encoder.updateFence(globalFence, after: .fragment)
        }

        trackedEndEncoding(encoder)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_clearDraw")
public func metallum_MTLRenderCommandEncoder_clearDraw(
    _ encoder: MTLRenderCommandEncoder,
    _ colorTexture: MTLTexture?,
    _ depthTexture: MTLTexture?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ clearColorEnabled: Int32,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double
) {
    autoreleasepool {
        guard let device = colorTexture?.device ?? depthTexture?.device else {
            return
        }
        let colorFormat = colorTexture?.pixelFormat ?? .invalid
        let depthFormat = depthTexture?.pixelFormat ?? .invalid
        let writeColor = clearColorEnabled != 0

        guard let pipeline = ensureClearColorDepthPipeline(device, colorFormat, depthFormat, writeColor) else {
            return
        }

        let depthState: MTLDepthStencilState?
        if depthFormat != .invalid {
            depthState = ensureDepthStencilState(device: device, compareOp: .always, writeDepth: clearDepthEnabled != 0)
        } else {
            depthState = nil
        }

        let width = colorTexture?.width ?? depthTexture?.width ?? 0
        let height = colorTexture?.height ?? depthTexture?.height ?? 0
        guard width > 0, height > 0 else {
            return
        }

        encodeClearDraw(
            encoder: encoder,
            pipeline: pipeline,
            textureWidth: Int(viewportWidth),
            textureHeight: Int(viewportHeight),
            clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
            scissorRect: MTLScissorRect(x: 0, y: 0, width: width, height: height),
            depthState: depthState,
            clearDepth: clearDepth
        )
    }
}

@inline(__always)
private func sanitizedLayerContentsHeadroom(_ contentHeadroom: Float) -> CGFloat {
    let finiteHeadroom = contentHeadroom.isFinite ? contentHeadroom : 1.0
    return CGFloat(min(max(finiteHeadroom, 1.0), 8.0))
}

@_cdecl("metallum_update_layer_contents_headroom")
public func metallum_update_layer_contents_headroom(
    _ layer: CAMetalLayer,
    _ contentHeadroom: Float
) -> Int32 {
    if #available(macOS 26.0, *) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.contentsHeadroom = sanitizedLayerContentsHeadroom(contentHeadroom)
        CATransaction.commit()
    }
    return 1
}

@_cdecl("metallum_configure_layer")
public func metallum_configure_layer(
    _ layer: CAMetalLayer,
    _ width: Double,
    _ height: Double,
    _ immediatePresentMode: Int32,
    _ outputMode: Int32,
    _ contentHeadroom: Float
) -> Int32 {
    guard width > 0.0, height > 0.0, (0...2).contains(outputMode) else {
        return 0
    }

    let useEdr = outputMode != 0
    let colorSpace = CGColorSpace(name: useEdr
        ? CGColorSpace.extendedLinearSRGB
        : CGColorSpace.sRGB)
    guard colorSpace != nil else {
        return 0
    }

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    layer.pixelFormat = useEdr ? .rgba16Float : .bgra8Unorm
    layer.colorspace = colorSpace
    layer.edrMetadata = nil
    if #available(macOS 26.0, *) {
        layer.preferredDynamicRange = useEdr ? .high : .standard
        layer.contentsHeadroom = useEdr
            ? sanitizedLayerContentsHeadroom(contentHeadroom)
            : 1.0
        layer.wantsExtendedDynamicRangeContent = false
    } else {
        layer.wantsExtendedDynamicRangeContent = useEdr
    }
    if #available(macOS 15.0, *) {
        layer.toneMapMode = .never
    }
    layer.drawableSize = CGSize(width: width, height: height)
    layer.allowsNextDrawableTimeout = true
    layer.presentsWithTransaction = false
    layer.displaySyncEnabled = immediatePresentMode == 0
    CATransaction.commit()
    return 1
}

@_cdecl("metallum_MTLCommandBuffer_encodeHdrUiBackdrop")
public func metallum_MTLCommandBuffer_encodeHdrUiBackdrop(
    _ commandBuffer: MTLCommandBuffer,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ sceneDepthTexture: MTLTexture?,
    _ semanticTexture: MTLTexture?,
    _ globalFence: MTLFence?,
    _ sourceEncoding: Int32,
    _ materialGenerationActive: Int32,
    _ spatialScalingEnabled: Int32,
    _ hdrPrecomposeEnabled: Int32,
    _ perceptualScalingEnabled: Int32,
    _ deferSpatialHdrUiSeed: Int32,
    _ currentHeadroom: Float,
    _ hdrStrength: Float,
    _ bloomStrength: Float
) -> Int32 {
    return autoreleasepool {
        // Prepared spatial outputs are single-use and command-buffer scoped.
        // Any new backdrop request invalidates an unconsumed record before it
        // can be mistaken for this frame's MetalFX output.
        NativeState.spatialWorkspaces[objectAddress(commandBuffer.device)]?.preparedUiSeed = nil
        guard
            (0...2).contains(sourceEncoding),
            (0...1).contains(materialGenerationActive),
            sourceTexture.width > 0,
            sourceTexture.height > 0,
            destinationTexture.pixelFormat == .rgba8Unorm,
            sourceTexture.textureType == .type2D,
            destinationTexture.textureType == .type2D,
            sourceTexture.sampleCount == 1,
            destinationTexture.sampleCount == 1,
            sourceTexture.usage.contains(.shaderRead),
            destinationTexture.usage.contains(.renderTarget),
            objectAddress(sourceTexture) != objectAddress(destinationTexture),
            objectAddress(sourceTexture.device) == objectAddress(commandBuffer.device),
            objectAddress(destinationTexture.device) == objectAddress(commandBuffer.device),
            globalFence == nil || objectAddress(globalFence!.device) == objectAddress(commandBuffer.device)
        else {
            return 0
        }

        let backdropSource: MTLTexture
        var backdropEncoding = sourceEncoding
        let effectiveHeadroom = min(
            max(1.0, currentHeadroom.isFinite ? currentHeadroom : 1.0),
            8.0
        )
        let effectiveHdrStrength = hdrStrength.isFinite
            ? min(max(hdrStrength, 0.0), 2.0)
            : 1.0
        let effectiveBloomStrength = bloomStrength.isFinite
            ? min(max(bloomStrength, 0.0), 1.0)
            : 0.22
        let compatibleDepth = sceneDepthTexture != nil
            && sceneDepthTexture!.textureType == .type2D
            && sceneDepthTexture!.sampleCount == 1
            && sceneDepthTexture!.usage.contains(.shaderRead)
            && objectAddress(sceneDepthTexture!.device) == objectAddress(commandBuffer.device)
            && sceneDepthTexture!.width == sourceTexture.width
            && sceneDepthTexture!.height == sourceTexture.height
            && (sceneDepthTexture!.pixelFormat == .depth32Float
                || sceneDepthTexture!.pixelFormat == .depth32Float_stencil8)
        let compatibleSemantic = semanticTexture == nil
            || (semanticTexture!.textureType == .type2D
                && semanticTexture!.sampleCount == 1
                && semanticTexture!.usage.contains(.shaderRead)
                && objectAddress(semanticTexture!.device) == objectAddress(commandBuffer.device)
                && semanticTexture!.width == sourceTexture.width
                && semanticTexture!.height == sourceTexture.height
                && semanticTexture!.pixelFormat == .rgba8Unorm)
        let materialContractActive = materialGenerationActive != 0
        let actualHdrGeneration = materialContractActive
            && sourceEncoding == 2
        let canPrecomposeActualHdr = hdrPrecomposeEnabled != 0
            && actualHdrGeneration
            && sourceTexture.pixelFormat == .rgba16Float
            && sourceEncoding == 2
        let canPrecomposeLegacyHdr = hdrPrecomposeEnabled != 0
            && !materialContractActive
            && sourceTexture.pixelFormat == .rgba16Float
            && compatibleDepth
            && compatibleSemantic
        let canPrecomposeHdr = canPrecomposeActualHdr || canPrecomposeLegacyHdr
        if spatialScalingEnabled == 0 && canPrecomposeHdr {
            guard destinationTexture.width == sourceTexture.width,
                  destinationTexture.height == sourceTexture.height else {
                return -3
            }
            let composite = canPrecomposeActualHdr
                ? encodeActualNativeHdrWorldUiComposite(
                    commandBuffer: commandBuffer,
                    sceneTexture: sourceTexture,
                    uiSeedTexture: destinationTexture,
                    globalFence: globalFence,
                    currentHeadroom: effectiveHeadroom,
                    hdrStrength: effectiveHdrStrength,
                    bloomStrength: effectiveBloomStrength
                  )
                : encodeNativeHdrWorldUiComposite(
                    commandBuffer: commandBuffer,
                    sceneTexture: sourceTexture,
                    sceneDepthTexture: sceneDepthTexture!,
                    semanticTexture: semanticTexture,
                    uiSeedTexture: destinationTexture,
                    globalFence: globalFence,
                    sourceEncoding: sourceEncoding,
                    currentHeadroom: effectiveHeadroom,
                    hdrStrength: effectiveHdrStrength,
                    bloomStrength: effectiveBloomStrength
                  )
            guard composite != nil else { return -3 }
            return 4
        }
        var hdrPrecomposed = false
        if spatialScalingEnabled != 0 {
            let useDirectPerceptualOutput = perceptualScalingEnabled != 0 && !canPrecomposeHdr
            let scalerInput: MTLTexture
            let scalerInputPixelFormat: MTLPixelFormat
            let scalerOutputPixelFormat: MTLPixelFormat
            let scalerColorProcessingMode: MTLFXSpatialScalerColorProcessingMode
            if canPrecomposeHdr {
                let composite = canPrecomposeActualHdr
                    ? encodeActualSpatialHdrWorldComposite(
                        commandBuffer: commandBuffer,
                        sceneTexture: sourceTexture,
                        globalFence: globalFence,
                        currentHeadroom: effectiveHeadroom,
                        hdrStrength: effectiveHdrStrength,
                        bloomStrength: effectiveBloomStrength,
                        displayWidth: destinationTexture.width,
                        displayHeight: destinationTexture.height
                      )
                    : encodeSpatialHdrWorldComposite(
                        commandBuffer: commandBuffer,
                        sceneTexture: sourceTexture,
                        sceneDepthTexture: sceneDepthTexture!,
                        semanticTexture: semanticTexture,
                        globalFence: globalFence,
                        sourceEncoding: sourceEncoding,
                        currentHeadroom: effectiveHeadroom,
                        hdrStrength: effectiveHdrStrength,
                        bloomStrength: effectiveBloomStrength,
                        displayWidth: destinationTexture.width,
                        displayHeight: destinationTexture.height
                      )
                guard let composite else {
                    return -3
                }
                scalerInput = composite
                scalerInputPixelFormat = composite.pixelFormat
                scalerOutputPixelFormat = composite.pixelFormat
                scalerColorProcessingMode = .hdr
                hdrPrecomposed = true
            } else if useDirectPerceptualOutput && sourceTexture.pixelFormat == .rgba16Float {
                scalerInput = sourceTexture
                scalerInputPixelFormat = .rgba8Unorm
                scalerOutputPixelFormat = .rgba8Unorm
                scalerColorProcessingMode = .perceptual
            } else {
                scalerInput = sourceTexture
                scalerInputPixelFormat = sourceTexture.pixelFormat
                scalerOutputPixelFormat = sourceTexture.pixelFormat
                scalerColorProcessingMode = sourceTexture.pixelFormat == .rgba16Float ? .hdr : .perceptual
            }
            guard
                destinationTexture.width >= sourceTexture.width,
                destinationTexture.height >= sourceTexture.height,
                scalerInputPixelFormat == .rgba8Unorm || scalerInputPixelFormat == .rgba16Float,
                let workspace = ensureSpatialWorkspace(
                    device: commandBuffer.device,
                    sourcePixelFormat: sourceTexture.pixelFormat,
                    inputWidth: scalerInput.width,
                    inputHeight: scalerInput.height,
                    outputWidth: destinationTexture.width,
                    outputHeight: destinationTexture.height,
                    inputPixelFormat: scalerInputPixelFormat,
                    outputPixelFormat: scalerOutputPixelFormat,
                    colorProcessingMode: scalerColorProcessingMode,
                    convertsLinearToPerceptual: sourceEncoding == 2
                        && !canPrecomposeHdr
                        && scalerColorProcessingMode == .perceptual,
                    usesDirectOutput: useDirectPerceptualOutput
                )
            else {
                return -2
            }

            let preparedScalerInput: MTLTexture
            if let perceptualInput = workspace.perceptualInput {
                guard let pipelines = ensureUiBackdropPipelines(device: commandBuffer.device),
                      let encoder = makeHdrPassEncoder(
                        commandBuffer: commandBuffer,
                        target: perceptualInput,
                        pipeline: pipelines.standard,
                        stage: .metalFx
                      ) else {
                    return -1
                }
                if let globalFence {
                    encoder.waitForFence(globalFence, before: .fragment)
                }
                encoder.setFragmentTexture(sourceTexture, index: 0)
                var uniforms = MetallumHdrUiBackdropUniforms(
                    sourceEncoding: UInt32(sourceEncoding)
                )
                withUnsafeBytes(of: &uniforms) { bytes in
                    encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
                }
                encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
                if let globalFence {
                    encoder.updateFence(globalFence, after: .fragment)
                }
                trackedEndEncoding(encoder)
                preparedScalerInput = perceptualInput
            } else {
                preparedScalerInput = scalerInput
            }

            let output: MTLTexture
            if useDirectPerceptualOutput {
                guard destinationTexture.usage.isSuperset(of: workspace.scaler.outputTextureUsage) else {
                    return -2
                }
                output = destinationTexture
                workspace.output = destinationTexture
            } else if let allocatedOutput = workspace.output {
                output = allocatedOutput
            } else {
                return -2
            }
            guard preparedScalerInput.usage.isSuperset(of: workspace.scaler.colorTextureUsage) else {
                return -2
            }
            workspace.scaler.colorTexture = preparedScalerInput
            workspace.scaler.inputContentWidth = scalerInput.width
            workspace.scaler.inputContentHeight = scalerInput.height
            workspace.scaler.outputTexture = output
            workspace.scaler.fence = globalFence
            let metalFxTiming = beginExternalGpuTiming(
                commandBuffer: commandBuffer,
                stage: .metalFx,
                fence: globalFence
            )
            workspace.scaler.encode(commandBuffer: commandBuffer)
            workspace.outputCommandBufferAddress = objectAddress(commandBuffer)
            endExternalGpuTiming(
                metalFxTiming,
                commandBuffer: commandBuffer,
                fence: globalFence
            )
            if useDirectPerceptualOutput {
                // The scaler writes the tone-mapped scene directly into the
                // full-resolution GUI target. GUI rendering follows in the
                // same command buffer, so no full-resolution seed copy or
                // intermediate output texture is required on SDR displays.
                // MetalFX defines this complete display-referred composite as
                // opaque; the SDR present path intentionally consumes RGB and
                // writes drawable alpha 1, while GUI source-over remains valid.
                return 3
            } else if hdrPrecomposed {
                // Seed the GUI from the same full-resolution MetalFX HDR
                // result used by final presentation. The final shader can
                // reconstruct this quantized SDR seed directly from that
                // texture, eliminating a second raw-scene sample per pixel.
                if deferSpatialHdrUiSeed != 0 {
                    guard ensureUiBackdropPipelines(device: commandBuffer.device) != nil else {
                        return -1
                    }
                    workspace.preparedUiSeed = MetallumPreparedSpatialUiSeed(
                        commandBufferAddress: objectAddress(commandBuffer),
                        sourceTextureAddress: objectAddress(sourceTexture),
                        destinationTextureAddress: objectAddress(destinationTexture),
                        sourceWidth: sourceTexture.width,
                        sourceHeight: sourceTexture.height,
                        outputWidth: destinationTexture.width,
                        outputHeight: destinationTexture.height,
                        output: output
                    )
                    return 2
                }
                backdropSource = output
                backdropEncoding = 2
            } else {
                backdropSource = output
            }
        } else {
            guard destinationTexture.width == sourceTexture.width,
                  destinationTexture.height == sourceTexture.height else {
                return 0
            }
            backdropSource = sourceTexture
        }

        guard
            let pipelines = ensureUiBackdropPipelines(device: commandBuffer.device),
            let encoder = makeHdrPassEncoder(
                commandBuffer: commandBuffer,
                target: destinationTexture,
                pipeline: pipelines.standard,
                stage: .uiSeed
            )
        else {
            return -1
        }

        if let globalFence {
            // The wait must belong to the encoder that consumes the previous
            // producer. A wait-only encoder does not carry the dependency into
            // this pass; updating the shared fence here would then let the GUI
            // overtake uploads made before the seed pass.
            encoder.waitForFence(globalFence, before: .fragment)
        }
        encoder.setFragmentTexture(backdropSource, index: 0)
        var uniforms = MetallumHdrUiBackdropUniforms(
            sourceEncoding: UInt32(backdropEncoding)
        )
        withUnsafeBytes(of: &uniforms) { bytes in
            encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
        }
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        if let globalFence {
            encoder.updateFence(globalFence, after: .fragment)
        }
        trackedEndEncoding(encoder)
        return hdrPrecomposed ? 2 : 1
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_encodePreparedHdrUiBackdrop")
public func metallum_MTLRenderCommandEncoder_encodePreparedHdrUiBackdrop(
    _ commandBuffer: MTLCommandBuffer,
    _ encoder: MTLRenderCommandEncoder,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ depthFormat: MTLPixelFormat,
    _ stencilFormat: MTLPixelFormat
) -> Int32 {
    return autoreleasepool {
        guard let (workspace, prepared) = validatedPreparedSpatialUiSeed(
                  commandBuffer: commandBuffer,
                  sourceTexture: sourceTexture,
                  destinationTexture: destinationTexture
              ), let pipelines = ensureUiBackdropPipelines(device: commandBuffer.device),
              let pipeline = ensureHdrUiBackdropPipeline(
                device: commandBuffer.device,
                pipelines: pipelines,
                depthFormat: depthFormat,
                stencilFormat: stencilFormat
              ) else {
            return 0
        }

        // The Java render encoder has already waited on the shared frame
        // fence. Keep the seed and all following GUI draws in this one render
        // pass; its normal endEncoder update is the sole producer fence.
        encodePreparedSpatialUiSeedDraw(
            encoder: encoder,
            output: prepared.output,
            destination: destinationTexture,
            pipeline: pipeline
        )
        workspace.preparedUiSeed = nil
        return 1
    }
}

@_cdecl("metallum_MTLCommandBuffer_materializePreparedHdrUiBackdrop")
public func metallum_MTLCommandBuffer_materializePreparedHdrUiBackdrop(
    _ commandBuffer: MTLCommandBuffer,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ globalFence: MTLFence?
) -> Int32 {
    return autoreleasepool {
        guard globalFence == nil
                || objectAddress(globalFence!.device) == objectAddress(commandBuffer.device),
              let (workspace, prepared) = validatedPreparedSpatialUiSeed(
                commandBuffer: commandBuffer,
                sourceTexture: sourceTexture,
                destinationTexture: destinationTexture
              ), let pipelines = ensureUiBackdropPipelines(device: commandBuffer.device) else {
            return 0
        }

        guard let encoder = makeHdrPassEncoder(
            commandBuffer: commandBuffer,
            target: destinationTexture,
            pipeline: pipelines.standard,
            stage: .uiSeed
        ) else {
            return 0
        }
        if let globalFence {
            encoder.waitForFence(globalFence, before: .fragment)
        }
        encodePreparedSpatialUiSeedDraw(
            encoder: encoder,
            output: prepared.output,
            destination: destinationTexture,
            pipeline: pipelines.standard
        )
        if let globalFence {
            encoder.updateFence(globalFence, after: .fragment)
        }
        trackedEndEncoding(encoder)
        workspace.preparedUiSeed = nil
        return 1
    }
}

@_cdecl("metallum_MTLCommandBuffer_encodeCoherentMenuBlur")
public func metallum_MTLCommandBuffer_encodeCoherentMenuBlur(
    _ commandBuffer: MTLCommandBuffer,
    _ sourceTexture: MTLTexture,
    _ uiTexture: MTLTexture,
    _ globalFence: MTLFence?,
    _ radius: Float,
    _ currentHeadroom: Float
) -> Int32 {
    return autoreleasepool {
        guard radius.isFinite,
              radius >= 1.0,
              sourceTexture.textureType == .type2D,
              sourceTexture.sampleCount == 1,
              sourceTexture.usage.contains(.shaderRead),
              uiTexture.pixelFormat == .rgba8Unorm,
              uiTexture.textureType == .type2D,
              uiTexture.sampleCount == 1,
              uiTexture.usage.contains(.shaderRead),
              uiTexture.usage.contains(.renderTarget),
              objectAddress(sourceTexture.device) == objectAddress(commandBuffer.device),
              objectAddress(uiTexture.device) == objectAddress(commandBuffer.device),
              globalFence == nil || objectAddress(globalFence!.device) == objectAddress(commandBuffer.device),
              let hdrTexture = currentNativeHdrWorldComposite(
                commandBuffer: commandBuffer,
                inputTexture: sourceTexture,
                outputWidth: uiTexture.width,
                outputHeight: uiTexture.height
              ) ?? currentSpatialOutput(
                commandBuffer: commandBuffer,
                inputTexture: sourceTexture,
                outputWidth: uiTexture.width,
                outputHeight: uiTexture.height
              ),
              hdrTexture.pixelFormat == .rgba16Float,
              hdrTexture.usage.contains(.shaderRead),
              hdrTexture.usage.contains(.renderTarget),
              hdrTexture.width == uiTexture.width,
              hdrTexture.height == uiTexture.height,
              let workspace = NativeState.hdrWorkspaces[objectAddress(commandBuffer.device)],
              workspace.displayWidth == uiTexture.width,
              workspace.displayHeight == uiTexture.height,
              let blurTextures = ensureMenuBlurTextures(
                device: commandBuffer.device,
                workspace: workspace,
                width: uiTexture.width,
                height: uiTexture.height
              ),
              let pipelines = ensureMenuBlurPipelines(device: commandBuffer.device),
              let samplers = presentSamplers(device: commandBuffer.device) else {
            return 0
        }

        let effectiveRadius = min(max(round(radius), 1.0), 64.0)
        let effectiveHeadroom = min(
            max(1.0, currentHeadroom.isFinite ? currentHeadroom : 1.0),
            8.0
        )
        var uniforms = MetallumMenuBlurUniforms(
            blurDirection: SIMD2<Float>(0.0, 0.0),
            radius: effectiveRadius,
            currentHeadroom: effectiveHeadroom
        )

        guard let compose = makeHdrPassEncoder(
            commandBuffer: commandBuffer,
            target: blurTextures.0,
            pipeline: pipelines.compose,
            stage: .uiSeed
        ) else {
            return -1
        }
        compose.label = "Metallum coherent menu blur compose"
        if let globalFence {
            compose.waitForFence(globalFence, before: .fragment)
        }
        compose.setFragmentTexture(uiTexture, index: 0)
        compose.setFragmentTexture(hdrTexture, index: 1)
        withUnsafeBytes(of: &uniforms) { bytes in
            compose.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
        }
        compose.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        trackedEndEncoding(compose)

        let directions = [
            SIMD2<Float>(1.0, 0.0),
            SIMD2<Float>(0.0, 1.0),
            SIMD2<Float>(1.0, 0.0),
            SIMD2<Float>(0.0, 1.0),
            SIMD2<Float>(1.0, 0.0)
        ]
        var readTexture = blurTextures.0
        var writeTexture = blurTextures.1
        for (index, direction) in directions.enumerated() {
            guard let encoder = makeHdrPassEncoder(
                commandBuffer: commandBuffer,
                target: writeTexture,
                pipeline: pipelines.blur,
                stage: .uiSeed
            ) else {
                return -1
            }
            encoder.label = "Metallum coherent menu blur pass \(index + 1)"
            uniforms.blurDirection = direction
            encoder.setFragmentTexture(readTexture, index: 0)
            encoder.setFragmentSamplerState(samplers.linear, index: 0)
            withUnsafeBytes(of: &uniforms) { bytes in
                encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
            }
            encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            trackedEndEncoding(encoder)
            swap(&readTexture, &writeTexture)
        }

        let resolvePass = MTLRenderPassDescriptor()
        resolvePass.colorAttachments[0].texture = hdrTexture
        resolvePass.colorAttachments[0].loadAction = .dontCare
        resolvePass.colorAttachments[0].storeAction = .store
        resolvePass.colorAttachments[1].texture = uiTexture
        resolvePass.colorAttachments[1].loadAction = .dontCare
        resolvePass.colorAttachments[1].storeAction = .store
        attachGpuTiming(resolvePass, commandBuffer: commandBuffer, stage: .uiSeed)
        guard let resolve = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: resolvePass) else {
            return -1
        }
        resolve.label = "Metallum coherent menu blur resolve"
        resolve.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(uiTexture.width),
            height: Double(uiTexture.height),
            znear: 0.0,
            zfar: 1.0
        ))
        resolve.setRenderPipelineState(pipelines.resolve)
        uniforms.blurDirection = SIMD2<Float>(0.0, 1.0)
        resolve.setFragmentTexture(readTexture, index: 0)
        resolve.setFragmentSamplerState(samplers.linear, index: 0)
        withUnsafeBytes(of: &uniforms) { bytes in
            resolve.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
        }
        resolve.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        if let globalFence {
            resolve.updateFence(globalFence, after: .fragment)
        }
        trackedEndEncoding(resolve)
        return 1
    }
}

@_cdecl("metallum_MTLCommandBuffer_encodeSpatialScreenshot")
public func metallum_MTLCommandBuffer_encodeSpatialScreenshot(
    _ commandBuffer: MTLCommandBuffer,
    _ rawSceneTexture: MTLTexture,
    _ uiTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ globalFence: MTLFence?,
    _ sourceEncoding: Int32,
    _ currentHeadroom: Float
) -> Int32 {
    return autoreleasepool {
        guard
            (0...2).contains(sourceEncoding),
            rawSceneTexture.textureType == .type2D,
            rawSceneTexture.sampleCount == 1,
            rawSceneTexture.usage.contains(.shaderRead),
            uiTexture.pixelFormat == .rgba8Unorm,
            uiTexture.textureType == .type2D,
            uiTexture.sampleCount == 1,
            uiTexture.usage.contains(.shaderRead),
            destinationTexture.pixelFormat == .rgba8Unorm,
            destinationTexture.textureType == .type2D,
            destinationTexture.sampleCount == 1,
            destinationTexture.usage.contains(.renderTarget),
            destinationTexture.width == uiTexture.width,
            destinationTexture.height == uiTexture.height,
            objectAddress(destinationTexture) != objectAddress(uiTexture),
            objectAddress(rawSceneTexture.device) == objectAddress(commandBuffer.device),
            objectAddress(uiTexture.device) == objectAddress(commandBuffer.device),
            objectAddress(destinationTexture.device) == objectAddress(commandBuffer.device),
            globalFence == nil || objectAddress(globalFence!.device) == objectAddress(commandBuffer.device),
            let spatialHdrTexture = currentSpatialOutput(
                device: commandBuffer.device,
                inputTexture: rawSceneTexture,
                outputWidth: uiTexture.width,
                outputHeight: uiTexture.height
            ),
            spatialHdrTexture.pixelFormat == .rgba16Float,
            let pipeline = ensureSpatialScreenshotPipeline(
                device: commandBuffer.device,
                colorFormat: destinationTexture.pixelFormat
            )
        else {
            return 0
        }

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = destinationTexture
        renderPass.colorAttachments[0].loadAction = .dontCare
        renderPass.colorAttachments[0].storeAction = .store
        guard let encoder = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: renderPass) else {
            return -1
        }
        if let globalFence {
            encoder.waitForFence(globalFence, before: .fragment)
        }
        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(destinationTexture.width),
            height: Double(destinationTexture.height),
            znear: 0.0,
            zfar: 1.0
        ))
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(uiTexture, index: 0)
        encoder.setFragmentTexture(spatialHdrTexture, index: 1)
        let effectiveHeadroom = min(
            max(1.0, currentHeadroom.isFinite ? currentHeadroom : 1.0),
            8.0
        )
        var uniforms = MetallumPresentUniforms(
            mode: 2,
            sourceEncoding: UInt32(clamping: sourceEncoding),
            diagnosticPattern: 1,
            currentHeadroom: effectiveHeadroom,
            hdrStrength: 0.0,
            bloomStrength: 0.0,
            sceneAvailable: 1,
            uiAvailable: 1,
            semanticAvailable: 0
        )
        withUnsafeBytes(of: &uniforms) { bytes in
            encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
        }
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        if let globalFence {
            encoder.updateFence(globalFence, after: .fragment)
        }
        trackedEndEncoding(encoder)
        return 1
    }
}

@_cdecl("metallum_MTLCommandBuffer_encodePresentTextureToDrawable")
public func metallum_MTLCommandBuffer_encodePresentTextureToDrawable(
    _ commandBuffer: MTLCommandBuffer,
    _ layer: CAMetalLayer,
    _ sourceTexture: MTLTexture,
    _ sceneTexture: MTLTexture?,
    _ sceneDepthTexture: MTLTexture?,
    _ semanticTexture: MTLTexture?,
    _ uiTexture: MTLTexture?,
    _ globalFence: MTLFence?,
    _ spatialHdrPrecomposed: Int32,
    _ outputMode: Int32,
    _ sourceEncoding: Int32,
    _ materialGenerationActive: Int32,
    _ diagnosticPattern: Int32,
    _ currentHeadroom: Float,
    _ hdrStrength: Float,
    _ bloomStrength: Float
) -> Int32 {
    return autoreleasepool {
        guard (0...2).contains(outputMode),
              (0...1).contains(materialGenerationActive) else {
            return -1
        }

        let effectiveHeadroom = min(
            max(1.0, currentHeadroom.isFinite ? currentHeadroom : 1.0),
            8.0
        )
        let materialContractActive = materialGenerationActive != 0
        let actualHdrGeneration = materialContractActive
            && outputMode != 0
        let canEnhance = outputMode == 2 && effectiveHeadroom > 1.001
        let hasCompatibleDepth = sceneTexture != nil
            && sceneDepthTexture != nil
            && sceneDepthTexture!.width == sceneTexture!.width
            && sceneDepthTexture!.height == sceneTexture!.height
            && {
                switch sceneDepthTexture!.pixelFormat {
                case .depth32Float, .depth32Float_stencil8:
                    return true
                default:
                    return false
                }
            }()
        let hasCompatibleLegacyScene = canEnhance
            && sceneTexture != nil
            && hasCompatibleDepth
        let hasCompatibleActualScene = actualHdrGeneration
            && sceneTexture != nil
            && sceneTexture!.pixelFormat == .rgba16Float
            && sceneTexture!.textureType == .type2D
            && sceneTexture!.sampleCount == 1
            && sceneTexture!.usage.contains(.shaderRead)
            && objectAddress(sceneTexture!.device) == objectAddress(commandBuffer.device)
            && sourceEncoding == 2
        let hasCompatibleScene = materialContractActive
            ? hasCompatibleActualScene
            : hasCompatibleLegacyScene
        let hasCompatibleSemantic = !materialContractActive
            && hasCompatibleLegacyScene
            && semanticTexture != nil
            && semanticTexture!.width == sceneTexture!.width
            && semanticTexture!.height == sceneTexture!.height
            && semanticTexture!.pixelFormat == .rgba8Unorm
        // The seeded RGBA8 target is a complete SDR frame. Keep it usable
        // independently of enhanced-scene eligibility so a headroom drop or
        // an Enhanced-to-EDR fallback cannot make the GUI disappear.
        let candidateSpatialOutput = uiTexture.flatMap {
            currentSpatialOutput(
                device: commandBuffer.device,
                inputTexture: sourceTexture,
                outputWidth: $0.width,
                outputHeight: $0.height
            )
        }
        let candidateNativeOutput = spatialHdrPrecomposed != 0
            ? uiTexture.flatMap {
                currentNativeHdrWorldComposite(
                    commandBuffer: commandBuffer,
                    inputTexture: sourceTexture,
                    outputWidth: $0.width,
                    outputHeight: $0.height
                )
            }
            : nil
        // A spatial workspace can remain cached after MetalFX is disabled.
        // The native composite is command-buffer scoped, so prefer it when it
        // exists rather than accidentally presenting an older scaler output.
        let candidatePrecomposedOutput = candidateNativeOutput ?? candidateSpatialOutput
        let hasCompatibleUi = uiTexture != nil
            && uiTexture!.pixelFormat == .rgba8Unorm
            && uiTexture!.textureType == .type2D
            && uiTexture!.sampleCount == 1
            && objectAddress(uiTexture!.device) == objectAddress(commandBuffer.device)
            && ((uiTexture!.width == sourceTexture.width && uiTexture!.height == sourceTexture.height)
                || candidatePrecomposedOutput != nil)
        let actualHdrUiOnly = actualHdrGeneration
            && !hasCompatibleScene
            && hasCompatibleUi
        let actualHdrLinearUiOnly = actualHdrGeneration
            && !hasCompatibleScene
            && !hasCompatibleUi
            && sourceEncoding == 2
            && sourceTexture.pixelFormat == .rgba16Float
            && sourceTexture.textureType == .type2D
            && sourceTexture.sampleCount == 1
            && sourceTexture.usage.contains(.shaderRead)
            && objectAddress(sourceTexture.device) == objectAddress(commandBuffer.device)
        let displaySceneTexture = candidatePrecomposedOutput ?? sceneTexture ?? sourceTexture
        let hasHdrPrecompose = spatialHdrPrecomposed != 0
            && hasCompatibleScene
            && hasCompatibleUi
            && candidatePrecomposedOutput != nil
            && candidatePrecomposedOutput!.pixelFormat == .rgba16Float
            && sceneTexture!.pixelFormat == .rgba16Float
            && candidatePrecomposedOutput!.width == uiTexture!.width
            && candidatePrecomposedOutput!.height == uiTexture!.height

        if hasCompatibleScene {
            let workspace = ensureHdrWorkspace(
                    device: commandBuffer.device,
                    renderContractMode: materialContractActive ? 1 : 0,
                    sourceWidth: sceneTexture!.width,
                    sourceHeight: sceneTexture!.height,
                    displayWidth: displaySceneTexture.width,
                    displayHeight: displaySceneTexture.height
                )
            let pipelinesReady = materialContractActive
                ? ensureActualHdrPipelines(device: commandBuffer.device) != nil
                : ensureHdrPipelines(device: commandBuffer.device) != nil
            guard pipelinesReady, workspace != nil else {
                return -1
            }
        }

        let presentPipeline = hasHdrPrecompose
            ? ensureSpatialPresentPipeline(device: commandBuffer.device, colorFormat: layer.pixelFormat)
            : outputMode == 0
                ? ensurePresentPipeline(device: commandBuffer.device, colorFormat: layer.pixelFormat)
                : actualHdrUiOnly
                    ? ensureActualHdrUiOnlyPipeline(device: commandBuffer.device, colorFormat: layer.pixelFormat)
                    : actualHdrLinearUiOnly
                        ? ensureActualHdrLinearUiOnlyPipeline(device: commandBuffer.device, colorFormat: layer.pixelFormat)
                        : materialContractActive
                            ? ensureActualHdrPresentPipeline(device: commandBuffer.device, colorFormat: layer.pixelFormat)
                            : ensureLegacyHdrPresentPipeline(device: commandBuffer.device, colorFormat: layer.pixelFormat)
        guard let presentPipeline else {
            NSLog("[metallum] No present pipeline for layer format %lu", layer.pixelFormat.rawValue)
            return -1
        }

        guard let samplers = presentSamplers(device: commandBuffer.device) else {
            NSLog("[metallum] No present samplers for Metal device")
            return -1
        }

        let timingStats = NativeState.gpuTimingStats
        let waitToken = timingStats?.beginWait()
        let drawableWaitStart = DispatchTime.now().uptimeNanoseconds
        let nextDrawable: CAMetalDrawable? = layer.nextDrawable()
        let drawableWaitEnd = DispatchTime.now().uptimeNanoseconds
        if let waitToken {
            timingStats?.recordWait(
                .nextDrawable,
                nanoseconds: drawableWaitEnd >= drawableWaitStart
                    ? drawableWaitEnd - drawableWaitStart
                    : 0,
                token: waitToken
            )
        }
        guard let drawable = nextDrawable else {
            return 0
        }

        if actualHdrUiOnly || actualHdrLinearUiOnly {
            let outputOnlyTexture = actualHdrUiOnly ? uiTexture! : sourceTexture
            let renderPass = MTLRenderPassDescriptor()
            renderPass.colorAttachments[0].texture = drawable.texture
            renderPass.colorAttachments[0].loadAction = .dontCare
            renderPass.colorAttachments[0].storeAction = .store
            attachGpuTiming(renderPass, commandBuffer: commandBuffer, stage: .present)
            guard let encoder = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: renderPass) else {
                return -1
            }
            if let globalFence {
                encoder.waitForFence(globalFence, before: .fragment)
            }
            encoder.setViewport(MTLViewport(
                originX: 0.0,
                originY: 0.0,
                width: Double(drawable.texture.width),
                height: Double(drawable.texture.height),
                znear: 0.0,
                zfar: 1.0
            ))
            encoder.setRenderPipelineState(presentPipeline)
            encoder.setFragmentTexture(outputOnlyTexture, index: 0)
            let requiresScaling = outputOnlyTexture.width != drawable.texture.width
                || outputOnlyTexture.height != drawable.texture.height
            encoder.setFragmentSamplerState(requiresScaling ? samplers.linear : samplers.nearest, index: 0)
            encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            if let globalFence {
                encoder.updateFence(globalFence, after: .fragment)
            }
            trackedEndEncoding(encoder)
            commandBuffer.present(drawable)
            if NativeState.gpuTimingStats != nil {
                MetallumGpuTimingCoordinator.shared.markPresented(
                    commandBuffer,
                    renderWidth: outputOnlyTexture.width,
                    renderHeight: outputOnlyTexture.height,
                    displayWidth: drawable.texture.width,
                    displayHeight: drawable.texture.height,
                    outputMode: outputMode,
                    sourceEncoding: actualHdrUiOnly ? 0 : 2,
                    diagnosticPattern: false,
                    hdrStrength: 0.0,
                    bloomStrength: 0.0,
                    currentHeadroom: effectiveHeadroom,
                    displaySyncEnabled: layer.displaySyncEnabled
                )
            }
            return 1
        }

        if outputMode == 0 {
            let renderPass = MTLRenderPassDescriptor()
            renderPass.colorAttachments[0].texture = drawable.texture
            renderPass.colorAttachments[0].loadAction = .dontCare
            renderPass.colorAttachments[0].storeAction = .store
            attachGpuTiming(renderPass, commandBuffer: commandBuffer, stage: .present)
            guard let encoder = trackedMakeRenderCommandEncoder(
                commandBuffer,
                descriptor: renderPass
            ) else {
                return -1
            }
            if let globalFence {
                encoder.waitForFence(globalFence, before: .fragment)
            }
            encoder.setViewport(MTLViewport(
                originX: 0.0,
                originY: 0.0,
                width: Double(drawable.texture.width),
                height: Double(drawable.texture.height),
                znear: 0.0,
                zfar: 1.0
            ))
            encoder.setRenderPipelineState(presentPipeline)
            encoder.setFragmentTexture(sourceTexture, index: 0)
            encoder.setFragmentTexture(hasCompatibleUi ? uiTexture : sourceTexture, index: 1)
            let requiresScaling = sourceTexture.width != drawable.texture.width
                || sourceTexture.height != drawable.texture.height
            encoder.setFragmentSamplerState(requiresScaling ? samplers.linear : samplers.nearest, index: 0)
            var uniforms = MetallumPresentUniforms(
                mode: 0,
                sourceEncoding: UInt32(clamping: max(sourceEncoding, 0)),
                diagnosticPattern: diagnosticPattern == 0 ? 0 : 1,
                currentHeadroom: 1.0,
                hdrStrength: 0.0,
                bloomStrength: 0.0,
                sceneAvailable: 0,
                uiAvailable: hasCompatibleUi ? 1 : 0,
                semanticAvailable: 0
            )
            withUnsafeBytes(of: &uniforms) { bytes in
                encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
            }
            encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            if let globalFence {
                encoder.updateFence(globalFence, after: .fragment)
            }
            trackedEndEncoding(encoder)
            commandBuffer.present(drawable)
            if NativeState.gpuTimingStats != nil {
                MetallumGpuTimingCoordinator.shared.markPresented(
                    commandBuffer,
                    renderWidth: sourceTexture.width,
                    renderHeight: sourceTexture.height,
                    displayWidth: drawable.texture.width,
                    displayHeight: drawable.texture.height,
                    outputMode: 0,
                    sourceEncoding: sourceEncoding,
                    diagnosticPattern: diagnosticPattern != 0,
                    hdrStrength: 0.0,
                    bloomStrength: 0.0,
                    currentHeadroom: 1.0,
                    displaySyncEnabled: layer.displaySyncEnabled
                )
            }
            return 1
        }

        let separatedHdrTexture = hasHdrPrecompose ? candidatePrecomposedOutput : nil

        if hasCompatibleUi,
           let uiTexture,
           let spatialHdrTexture = separatedHdrTexture {
            guard spatialHdrTexture.width == drawable.texture.width,
                  spatialHdrTexture.height == drawable.texture.height,
                  uiTexture.width == drawable.texture.width,
                  uiTexture.height == drawable.texture.height,
                  let separatedPresentPipeline = ensureSpatialPresentPipeline(
                    device: commandBuffer.device,
                    colorFormat: layer.pixelFormat
                  ) else {
                return -1
            }

            let renderPass = MTLRenderPassDescriptor()
            renderPass.colorAttachments[0].texture = drawable.texture
            renderPass.colorAttachments[0].loadAction = .dontCare
            renderPass.colorAttachments[0].storeAction = .store
            attachGpuTiming(renderPass, commandBuffer: commandBuffer, stage: .present)
            guard let encoder = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: renderPass) else {
                return -1
            }
            if let globalFence {
                encoder.waitForFence(globalFence, before: .fragment)
            }
            encoder.setViewport(MTLViewport(
                originX: 0.0,
                originY: 0.0,
                width: Double(drawable.texture.width),
                height: Double(drawable.texture.height),
                znear: 0.0,
                zfar: 1.0
            ))
            encoder.setRenderPipelineState(separatedPresentPipeline)
            encoder.setFragmentTexture(uiTexture, index: 0)
            encoder.setFragmentTexture(spatialHdrTexture, index: 1)
            var spatialUniforms = MetallumPresentUniforms(
                mode: UInt32(clamping: max(outputMode, 0)),
                sourceEncoding: UInt32(clamping: max(sourceEncoding, 0)),
                diagnosticPattern: 0,
                currentHeadroom: effectiveHeadroom,
                hdrStrength: 0.0,
                bloomStrength: 0.0,
                sceneAvailable: 1,
                uiAvailable: 1,
                semanticAvailable: 0
            )
            withUnsafeBytes(of: &spatialUniforms) { bytes in
                encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
            }
            encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            if let globalFence {
                encoder.updateFence(globalFence, after: .fragment)
            }
            trackedEndEncoding(encoder)
            commandBuffer.present(drawable)
            if NativeState.gpuTimingStats != nil {
                MetallumGpuTimingCoordinator.shared.markPresented(
                    commandBuffer,
                    renderWidth: sourceTexture.width,
                    renderHeight: sourceTexture.height,
                    displayWidth: drawable.texture.width,
                    displayHeight: drawable.texture.height,
                    outputMode: outputMode,
                    sourceEncoding: sourceEncoding,
                    diagnosticPattern: diagnosticPattern != 0,
                    hdrStrength: hdrStrength.isFinite
                        ? min(max(hdrStrength, 0.0), 2.0) : 1.0,
                    bloomStrength: bloomStrength.isFinite
                        ? min(max(bloomStrength, 0.0), 1.0) : 0.22,
                    currentHeadroom: effectiveHeadroom,
                    displaySyncEnabled: layer.displaySyncEnabled
                )
            }
            return 1
        }

        var hdrOutputs: MetallumHdrOutputs?
        var hasHdrScene = false
        if hasCompatibleScene, let sceneTexture {
            if materialContractActive {
                hdrOutputs = encodeActualHdrEffects(
                    commandBuffer: commandBuffer,
                    finalTexture: sourceTexture,
                    sceneTexture: sceneTexture,
                    displaySceneTexture: displaySceneTexture,
                    uiTexture: hasCompatibleUi ? uiTexture : nil,
                    globalFence: globalFence,
                    currentHeadroom: effectiveHeadroom
                )
            } else if let sceneDepthTexture {
                hdrOutputs = encodeHdrEffects(
                    commandBuffer: commandBuffer,
                    finalTexture: sourceTexture,
                    sceneTexture: sceneTexture,
                    displaySceneTexture: displaySceneTexture,
                    sceneDepthTexture: sceneDepthTexture,
                    semanticTexture: hasCompatibleSemantic ? semanticTexture : nil,
                    uiTexture: hasCompatibleUi ? uiTexture : nil,
                    globalFence: globalFence,
                    sourceEncoding: sourceEncoding,
                    currentHeadroom: effectiveHeadroom
                )
            }
            guard hdrOutputs != nil else {
                return -1
            }
            hasHdrScene = true
        }

        let adaptiveState: MTLBuffer?
        if hasHdrScene {
            adaptiveState = hdrOutputs?.adaptiveState
        } else if materialContractActive {
            // A METALLUM HDR generation must never silently fall through to
            // the Legacy inferred-reconstruction state.
            return -1
        } else {
            adaptiveState = ensureHdrFallbackAdaptiveState(device: commandBuffer.device)
        }
        guard let adaptiveState else {
            return -1
        }

        let presentDepthTexture: MTLTexture?
        if materialContractActive {
            presentDepthTexture = nil
        } else if hasHdrScene, let sceneDepthTexture {
            presentDepthTexture = sceneDepthTexture
        } else {
            presentDepthTexture = ensureHdrFallbackDepthTexture(device: commandBuffer.device)
            if presentDepthTexture == nil { return -1 }
        }

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = drawable.texture
        renderPass.colorAttachments[0].loadAction = .dontCare
        renderPass.colorAttachments[0].storeAction = .store
        attachGpuTiming(renderPass, commandBuffer: commandBuffer, stage: .present)

        guard let encoder = trackedMakeRenderCommandEncoder(commandBuffer, descriptor: renderPass) else {
            return -1
        }

        if let globalFence {
            encoder.waitForFence(globalFence, before: .fragment)
        }

        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(drawable.texture.width),
            height: Double(drawable.texture.height),
            znear: 0.0,
            zfar: 1.0
        ))

        encoder.setRenderPipelineState(presentPipeline)
        if materialContractActive {
            encoder.setFragmentTexture(sourceTexture, index: 0)
            encoder.setFragmentTexture(sceneTexture!, index: 1)
            encoder.setFragmentTexture(hdrOutputs!.bloom, index: 2)
            encoder.setFragmentTexture(hdrOutputs!.uiMask, index: 3)
            encoder.setFragmentTexture(hasCompatibleUi ? uiTexture : sourceTexture, index: 4)
        } else {
            encoder.setFragmentTexture(sourceTexture, index: 0)
            encoder.setFragmentTexture(hasHdrScene ? sceneTexture : sourceTexture, index: 1)
            encoder.setFragmentTexture(hasHdrScene ? hdrOutputs?.emission : sourceTexture, index: 2)
            encoder.setFragmentTexture(hasHdrScene ? hdrOutputs?.bloom : sourceTexture, index: 3)
            encoder.setFragmentTexture(hasHdrScene ? hdrOutputs?.uiMask : sourceTexture, index: 4)
            encoder.setFragmentTexture(hasCompatibleUi ? uiTexture : sourceTexture, index: 5)
            encoder.setFragmentTexture(hasCompatibleSemantic ? semanticTexture : sourceTexture, index: 6)
            encoder.setFragmentTexture(presentDepthTexture!, index: 7)
        }

        var uniforms = MetallumPresentUniforms(
            mode: UInt32(clamping: max(outputMode, 0)),
            sourceEncoding: materialContractActive ? 2 : UInt32(clamping: max(sourceEncoding, 0)),
            diagnosticPattern: diagnosticPattern == 0 ? 0 : 1,
            currentHeadroom: effectiveHeadroom,
            hdrStrength: materialContractActive
                ? 0.0
                : (hdrStrength.isFinite ? min(max(hdrStrength, 0.0), 2.0) : 1.0),
            bloomStrength: bloomStrength.isFinite ? min(max(bloomStrength, 0.0), 1.0) : 0.22,
            sceneAvailable: hasHdrScene ? 1 : 0,
            uiAvailable: hasCompatibleUi ? 1 : 0,
            semanticAvailable: materialContractActive ? 0 : (hasCompatibleSemantic ? 1 : 0)
        )
        withUnsafeBytes(of: &uniforms) { bytes in
            encoder.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
        }
        encoder.setFragmentBuffer(adaptiveState, offset: 0, index: 1)

        let requiresScaling = sourceTexture.width != drawable.texture.width ||
                              sourceTexture.height != drawable.texture.height

        let sampler = requiresScaling ? samplers.linear : samplers.nearest
        encoder.setFragmentSamplerState(sampler, index: 0)
        encoder.setFragmentSamplerState(samplers.linear, index: 1)

        encoder.drawPrimitives(
            type: .triangle,
            vertexStart: 0,
            vertexCount: 3
        )

        if let globalFence {
            encoder.updateFence(globalFence, after: .fragment)
        }

        trackedEndEncoding(encoder)
        commandBuffer.present(drawable)
        if NativeState.gpuTimingStats != nil {
            MetallumGpuTimingCoordinator.shared.markPresented(
                commandBuffer,
                renderWidth: sourceTexture.width,
                renderHeight: sourceTexture.height,
                displayWidth: drawable.texture.width,
                displayHeight: drawable.texture.height,
                outputMode: outputMode,
                sourceEncoding: sourceEncoding,
                diagnosticPattern: diagnosticPattern != 0,
                hdrStrength: hdrStrength.isFinite
                    ? min(max(hdrStrength, 0.0), 2.0) : 1.0,
                bloomStrength: bloomStrength.isFinite
                    ? min(max(bloomStrength, 0.0), 1.0) : 0.22,
                currentHeadroom: effectiveHeadroom,
                displaySyncEnabled: layer.displaySyncEnabled
            )
        }
        return 1
    }
}

@_cdecl("metallum_create_fence")
public func metallum_create_fence(_ device: MTLDevice) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(device.makeFence())
    }
}

@_cdecl("MTLRenderCommandEncoder_updateFence")
public func MTLRenderCommandEncoder_updateFence(
    _ encoder: MTLRenderCommandEncoder,
    _ fence: MTLFence,
    _ stages: MTLRenderStages
) {
    encoder.updateFence(fence, after: stages)
}

@_cdecl("MTLRenderCommandEncoder_waitForFence")
public func MTLRenderCommandEncoder_waitForFence(
    _ encoder: MTLRenderCommandEncoder,
    _ fence: MTLFence,
    _ stages: MTLRenderStages
) {
    encoder.waitForFence(fence, before: stages)
}

@_cdecl("MTLBlitCommandEncoder_updateFence")
public func MTLBlitCommandEncoder_updateFence(
    _ encoder: MTLBlitCommandEncoder,
    _ fence: MTLFence
) {
    encoder.updateFence(fence)
}

@_cdecl("MTLBlitCommandEncoder_waitForFence")
public func MTLBlitCommandEncoder_waitForFence(
    _ encoder: MTLBlitCommandEncoder,
    _ fence: MTLFence
) {
    encoder.waitForFence(fence)
}

@_cdecl("metallum_release_object")
public func metallum_release_object(_ obj: UnsafeMutableRawPointer?) {
    autoreleasepool {
        guard let obj else { return }
        let object = Unmanaged<AnyObject>.fromOpaque(obj).takeUnretainedValue()
        if let commandBuffer = object as? MTLCommandBuffer {
            MetallumGpuTimingCoordinator.shared.abandon(commandBuffer)
        }
        Unmanaged<AnyObject>.fromOpaque(obj).release()
    }
}

@_cdecl("metallum_release_static_geometry_buffer")
public func metallum_release_static_geometry_buffer(_ obj: UnsafeMutableRawPointer?) {
    autoreleasepool {
        guard let obj else { return }
        let release = MetallumStaticGeometryHeapRegistry.shared.beginRelease(
            bufferAddress: UInt(bitPattern: obj)
        )
        Unmanaged<AnyObject>.fromOpaque(obj).release()
        if let release {
            MetallumStaticGeometryHeapRegistry.shared.finishRelease(release)
        } else {
            NSLog("[metallum] Static geometry buffer release was not registered")
        }
    }
}

@_cdecl("metallum_get_buffer_contents")
public func metallum_get_buffer_contents(_ buffer: MTLBuffer) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        buffer.contents()
    }
}

@_cdecl("metallum_MTLVertexDescriptor_create")
public func metallum_MTLVertexDescriptor_create() -> UnsafeMutableRawPointer? {
    retainedPointer(MTLVertexDescriptor())
}

@_cdecl("metallum_MTLVertexDescriptor_setAttribute")
public func metallum_MTLVertexDescriptor_setAttribute(
    _ desc: MTLVertexDescriptor,
    _ index: Int,
    _ format: MTLVertexFormat,
    _ offset: Int,
    _ bufferIndex: Int
) {
    autoreleasepool {
        desc.attributes[index].format = format
        desc.attributes[index].offset = offset
        desc.attributes[index].bufferIndex = bufferIndex
    }
}

@_cdecl("metallum_MTLVertexDescriptor_setLayout")
public func metallum_MTLVertexDescriptor_setLayout(
    _ desc: MTLVertexDescriptor,
    _ bufferIndex: Int,
    _ stride: Int,
    _ stepFunction: MTLVertexStepFunction,
    _ stepRate: Int
) {
    autoreleasepool {
        desc.layouts[bufferIndex].stride = stride
        desc.layouts[bufferIndex].stepFunction = stepFunction
        desc.layouts[bufferIndex].stepRate = stepRate
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_create")
public func metallum_MTLRenderPipelineDescriptor_create() -> UnsafeMutableRawPointer? {
    retainedPointer(MTLRenderPipelineDescriptor())
}

@_cdecl("metallum_create_shader_function")
public func metallum_create_shader_function(
    _ device: MTLDevice,
    _ sourcePtr: UnsafePointer<CChar>?,
    _ entryPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let sourcePtr, let entryPtr else {
            return nil
        }
        do {
            let library = try device.makeLibrary(source: String(cString: sourcePtr), options: nil)
            guard let function = library.makeFunction(name: String(cString: entryPtr)) else {
                NSLog("[metallum] Failed to resolve MSL entry point '%s'", entryPtr)
                return nil
            }
            return retainedPointer(function)
        } catch {
            NSLog("[metallum] Failed to compile MSL: %@", String(describing: error))
            return nil
        }
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setCompiledFunctions")
public func metallum_MTLRenderPipelineDescriptor_setCompiledFunctions(
    _ desc: MTLRenderPipelineDescriptor,
    _ vertexFunction: MTLFunction,
    _ fragmentFunction: MTLFunction
) {
    desc.vertexFunction = vertexFunction
    desc.fragmentFunction = fragmentFunction
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setVertexDescriptor")
public func metallum_MTLRenderPipelineDescriptor_setVertexDescriptor(
    _ desc: MTLRenderPipelineDescriptor,
    _ vertexDesc: MTLVertexDescriptor
) {
    desc.vertexDescriptor = vertexDesc
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setAttachmentFormats")
public func metallum_MTLRenderPipelineDescriptor_setAttachmentFormats(
    _ desc: MTLRenderPipelineDescriptor,
    _ colorFormat: MTLPixelFormat,
    _ semanticFormat: MTLPixelFormat,
    _ depthFormat: MTLPixelFormat,
    _ stencilFormat: MTLPixelFormat
) {
    autoreleasepool {
        guard
            let colorAttachment = desc.colorAttachments[0],
            let semanticAttachment = desc.colorAttachments[1]
        else {
            return
        }
        colorAttachment.pixelFormat = colorFormat
        semanticAttachment.pixelFormat = semanticFormat
        desc.depthAttachmentPixelFormat = depthFormat
        desc.stencilAttachmentPixelFormat = stencilFormat
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setBlendState")
public func metallum_MTLRenderPipelineDescriptor_setBlendState(
    _ desc: MTLRenderPipelineDescriptor,
    _ attachmentIndex: Int32,
    _ enabled: Int32,
    _ srcRgb: MTLBlendFactor,
    _ dstRgb: MTLBlendFactor,
    _ opRgb: MTLBlendOperation,
    _ srcAlpha: MTLBlendFactor,
    _ dstAlpha: MTLBlendFactor,
    _ opAlpha: MTLBlendOperation,
    _ writeMask: MTLColorWriteMask
) {
    autoreleasepool {
        guard attachmentIndex >= 0, attachmentIndex < 8 else {
            return
        }
        guard let attachment = desc.colorAttachments[Int(attachmentIndex)] else {
            return
        }
        attachment.writeMask = writeMask
        if enabled != 0 {
            attachment.isBlendingEnabled = true
            attachment.sourceRGBBlendFactor = srcRgb
            attachment.destinationRGBBlendFactor = dstRgb
            attachment.rgbBlendOperation = opRgb
            attachment.sourceAlphaBlendFactor = srcAlpha
            attachment.destinationAlphaBlendFactor = dstAlpha
            attachment.alphaBlendOperation = opAlpha
        } else {
            attachment.isBlendingEnabled = false
        }
    }
}

@_cdecl("metallum_MTLDevice_makeRenderPipelineState")
public func metallum_MTLDevice_makeRenderPipelineState(
    _ device: MTLDevice,
    _ descriptor: MTLRenderPipelineDescriptor
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        do {
            return retainedPointer(try device.makeRenderPipelineState(descriptor: descriptor))
        } catch {
            NSLog("[metallum] Failed to create render pipeline state: %@", String(describing: error))
            return nil
        }
    }
}
