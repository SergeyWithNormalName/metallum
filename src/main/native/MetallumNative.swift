import Foundation
import AppKit
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

private struct MetallumHdrBlurUniforms {
    var texelStep: SIMD2<Float>
    var _padding0: SIMD2<Float>
}

private struct MetallumHdrUiBackdropUniforms {
    var sourceEncoding: UInt32
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
    let histogramBuild: MTLComputePipelineState
    let histogramReduce: MTLComputePipelineState
    let blur: MTLRenderPipelineState
    let uiBackdrop: MTLRenderPipelineState
    let uiCompare: MTLRenderPipelineState
    let uiDilate: MTLRenderPipelineState

    init(
        extract: MTLRenderPipelineState,
        histogramBuild: MTLComputePipelineState,
        histogramReduce: MTLComputePipelineState,
        blur: MTLRenderPipelineState,
        uiBackdrop: MTLRenderPipelineState,
        uiCompare: MTLRenderPipelineState,
        uiDilate: MTLRenderPipelineState
    ) {
        self.extract = extract
        self.histogramBuild = histogramBuild
        self.histogramReduce = histogramReduce
        self.blur = blur
        self.uiBackdrop = uiBackdrop
        self.uiCompare = uiCompare
        self.uiDilate = uiDilate
    }
}

private final class MetallumHdrWorkspace {
    let sourceWidth: Int
    let sourceHeight: Int
    var displayWidth: Int
    var displayHeight: Int
    let emission: MTLTexture
    let bloomA: MTLTexture
    let bloomB: MTLTexture
    var worldComposite: MTLTexture?
    var worldCompositeCommandBufferAddress: UInt?
    var uiMaskA: MTLTexture?
    var uiMaskB: MTLTexture?
    let histogram: MTLBuffer
    let adaptiveState: MTLBuffer
    var lastHistogramUptime: TimeInterval?
    var histogramNeedsInitialization: Bool

    init(
        sourceWidth: Int,
        sourceHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        emission: MTLTexture,
        bloomA: MTLTexture,
        bloomB: MTLTexture,
        histogram: MTLBuffer,
        adaptiveState: MTLBuffer
    ) {
        self.sourceWidth = sourceWidth
        self.sourceHeight = sourceHeight
        self.displayWidth = displayWidth
        self.displayHeight = displayHeight
        self.emission = emission
        self.bloomA = bloomA
        self.bloomB = bloomB
        self.worldComposite = nil
        self.worldCompositeCommandBufferAddress = nil
        self.uiMaskA = nil
        self.uiMaskB = nil
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
    let scaler: MTLFXSpatialScaler
    let perceptualInput: MTLTexture?
    var output: MTLTexture?
    let usesDirectOutput: Bool

    init(
        sourcePixelFormat: MTLPixelFormat,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        inputPixelFormat: MTLPixelFormat,
        outputPixelFormat: MTLPixelFormat,
        colorProcessingMode: MTLFXSpatialScalerColorProcessingMode,
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
        self.scaler = scaler
        self.perceptualInput = perceptualInput
        self.output = output
        self.usesDirectOutput = usesDirectOutput
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
    case ui = 9
    case present = 10

    var reportName: String {
        switch self {
        case .worldOpaque: "world opaque"
        case .translucent: "translucent"
        case .entities: "entities/features"
        case .hdrExtract: "HDR extract"
        case .histogramExposure: "histogram/exposure"
        case .bloomHorizontal: "bloom horizontal"
        case .bloomVertical: "bloom vertical"
        case .hdrReconstruction: "HDR reconstruction"
        case .metalFx: "MetalFX"
        case .ui: "UI"
        case .present: "present"
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

    private let lock = NSLock()
    private var frames: [UInt: MetallumGpuCounterFrame] = [:]
    private var counterSets: [UInt: MTLCounterSet] = [:]
    private var unsupportedDevices: Set<UInt> = []

    func register(_ commandBuffer: MTLCommandBuffer) {
        guard NativeState.gpuTimingStats != nil else { return }
        let device = commandBuffer.device
        let deviceKey = objectAddress(device)

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
        frames[objectAddress(commandBuffer)] = frame
        lock.unlock()
        if discoveredNow {
            NSLog(
                "[metallum] Per-stage GPU timing enabled: timestamp counter set, %d samples per command buffer",
                MetallumGpuCounterFrame.sampleCapacity
            )
        }
    }

    func frame(for commandBuffer: MTLCommandBuffer) -> MetallumGpuCounterFrame? {
        lock.lock()
        let frame = frames[objectAddress(commandBuffer)]
        lock.unlock()
        return frame
    }

    func take(_ commandBuffer: MTLCommandBuffer) -> MetallumGpuCounterFrame? {
        lock.lock()
        let frame = frames.removeValue(forKey: objectAddress(commandBuffer))
        lock.unlock()
        return frame
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

private final class MetallumGpuTimingStats: @unchecked Sendable {
    private let lock = NSLock()
    private var sampleCount = 0
    private var totalGpuSeconds = 0.0
    private var maximumGpuSeconds = 0.0
    private var stageTotals = Array(repeating: 0.0, count: MetallumGpuTimingStage.allCases.count)
    private var stageMaximums = Array(repeating: 0.0, count: MetallumGpuTimingStage.allCases.count)
    private var stageCounts = Array(repeating: 0, count: MetallumGpuTimingStage.allCases.count)
    private var syncWaitNanoseconds = 0.0
    private var maximumSyncWaitNanoseconds = 0.0
    private var syncWaitCount = 0
    private var droppedEvents = 0
    private var intervalStart = ProcessInfo.processInfo.systemUptime

    func record(_ commandBuffer: MTLCommandBuffer, snapshot: MetallumGpuTimingSnapshot?) {
        if commandBuffer.status == .error {
            NSLog(
                "[metallum] Metal command buffer failed (%@): %@",
                commandBuffer.label ?? "unlabeled",
                String(describing: commandBuffer.error)
            )
            return
        }
        let duration = commandBuffer.gpuEndTime - commandBuffer.gpuStartTime
        guard duration.isFinite, duration > 0.0 else {
            return
        }
        lock.lock()
        sampleCount += 1
        totalGpuSeconds += duration
        maximumGpuSeconds = max(maximumGpuSeconds, duration)
        if let snapshot {
            droppedEvents += snapshot.droppedEvents
            for stage in MetallumGpuTimingStage.allCases {
                guard let nanoseconds = snapshot.stageNanoseconds[stage.rawValue] else { continue }
                stageTotals[stage.rawValue] += nanoseconds
                stageMaximums[stage.rawValue] = max(stageMaximums[stage.rawValue], nanoseconds)
                stageCounts[stage.rawValue] += 1
            }
        }
        if sampleCount >= 300 {
            let now = ProcessInfo.processInfo.systemUptime
            let completedFps = Double(sampleCount) / max(now - intervalStart, 1e-6)
            var lines = [String(format:
                "[metallum] GPU timing (%d completed frames, %.1f FPS): total %.3f ms avg / %.3f ms max",
                sampleCount,
                completedFps,
                totalGpuSeconds * 1000.0 / Double(sampleCount),
                maximumGpuSeconds * 1000.0
            )]
            for stage in MetallumGpuTimingStage.allCases {
                let count = stageCounts[stage.rawValue]
                if count == 0 {
                    lines.append("  \(stage.reportName): n/a")
                } else {
                    lines.append(String(format:
                        "  %@: %.3f ms avg / %.3f ms max (%d frames)",
                        stage.reportName,
                        stageTotals[stage.rawValue] / Double(count) / 1_000_000.0,
                        stageMaximums[stage.rawValue] / 1_000_000.0,
                        count
                    ))
                }
            }
            if syncWaitCount == 0 {
                lines.append("  synchronization wait (CPU): n/a")
            } else {
                lines.append(String(format:
                    "  synchronization wait (CPU): %.3f ms/frame avg, %.3f ms max interval (%d waits)",
                    syncWaitNanoseconds / Double(sampleCount) / 1_000_000.0,
                    maximumSyncWaitNanoseconds / 1_000_000.0,
                    syncWaitCount
                ))
            }
            if droppedEvents > 0 {
                lines.append("  dropped timing events: \(droppedEvents)")
            }
            NSLog("%@", lines.joined(separator: "\n"))
            sampleCount = 0
            totalGpuSeconds = 0.0
            maximumGpuSeconds = 0.0
            stageTotals = Array(repeating: 0.0, count: MetallumGpuTimingStage.allCases.count)
            stageMaximums = Array(repeating: 0.0, count: MetallumGpuTimingStage.allCases.count)
            stageCounts = Array(repeating: 0, count: MetallumGpuTimingStage.allCases.count)
            syncWaitNanoseconds = 0.0
            maximumSyncWaitNanoseconds = 0.0
            syncWaitCount = 0
            droppedEvents = 0
            intervalStart = now
        }
        lock.unlock()
    }

    func recordSynchronizationWait(nanoseconds: UInt64) {
        lock.lock()
        let value = Double(nanoseconds)
        syncWaitNanoseconds += value
        maximumSyncWaitNanoseconds = max(maximumSyncWaitNanoseconds, value)
        syncWaitCount += 1
        lock.unlock()
    }
}

private enum NativeState {
    static var debugLabelsEnabled = false
    static var depthStencilStates: [DepthStencilKey: MTLDepthStencilState] = [:]
    static var clearPipelines: [PipelineVariantKey: MTLRenderPipelineState] = [:]
    static var presentPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var spatialPresentPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var spatialScreenshotPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var worldPresentPipelines: [PresentPipelineKey: MTLRenderPipelineState] = [:]
    static var nativeWorldUiPipelines: [UInt: MTLRenderPipelineState] = [:]
    static var hdrPipelines: [UInt: MetallumHdrPipelines] = [:]
    static var hdrWorkspaces: [UInt: MetallumHdrWorkspace] = [:]
    static var hdrFallbackAdaptiveStates: [UInt: MTLBuffer] = [:]
    static var hdrFallbackDepthTextures: [UInt: MTLTexture] = [:]
    static var spatialWorkspaces: [UInt: MetallumSpatialWorkspace] = [:]
    static var presentNearestSamplers: [UInt: MTLSamplerState] = [:]
    static var presentLinearSamplers: [UInt: MTLSamplerState] = [:]
    static let gpuTimingStats: MetallumGpuTimingStats? = ProcessInfo.processInfo.environment["METALLUM_GPU_TIMING"] == "1"
        ? MetallumGpuTimingStats()
        : nil
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

private func gpuTimingIsEnabled(for commandBuffer: MTLCommandBuffer) -> Bool {
    MetallumGpuTimingCoordinator.shared.frame(for: commandBuffer) != nil
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
    guard let encoder = commandBuffer.makeBlitCommandEncoder(descriptor: descriptor) else {
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
    encoder.endEncoding()
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
    guard let encoder = commandBuffer.makeBlitCommandEncoder(descriptor: descriptor) else {
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
    encoder.endEncoding()
}

private func completeGpuTiming(
    commandBuffer: MTLCommandBuffer,
    frame: MetallumGpuCounterFrame?
) {
    NativeState.gpuTimingStats?.record(commandBuffer, snapshot: frame?.resolve())
}

private func addGpuTimingCompletionHandler(
    to commandBuffer: MTLCommandBuffer,
    signal semaphore: DispatchSemaphore? = nil
) {
    let frame = MetallumGpuTimingCoordinator.shared.take(commandBuffer)
    commandBuffer.addCompletedHandler { completed in
        if completed.status == .error || NativeState.gpuTimingStats != nil {
            completeGpuTiming(commandBuffer: completed, frame: frame)
            if completed.status == .error && NativeState.gpuTimingStats == nil {
                NSLog(
                    "[metallum] Metal command buffer failed (%@): %@",
                    completed.label ?? "unlabeled",
                    String(describing: completed.error)
                )
            }
        }
        semaphore?.signal()
    }
}

private final class MetallumEdrMonitor: NSObject, @unchecked Sendable {
    private weak var window: NSWindow?
    private let lock = NSLock()
    private var currentHeadroom: Float = 1.0
    private var potentialHeadroom: Float = 1.0
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

    func snapshot() -> (current: Float, potential: Float) {
        requestRefresh()
        lock.lock()
        defer { lock.unlock() }
        return (currentHeadroom, potentialHeadroom)
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

        lock.lock()
        currentHeadroom = current.isFinite ? current : 1.0
        potentialHeadroom = potential.isFinite ? potential : 1.0
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

private func presentMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct PresentVertexOut {
      float4 position [[position]];
      float2 uv;
    };

    struct PresentUniforms {
      uint mode;
      uint sourceEncoding;
      uint diagnosticPattern;
      float currentHeadroom;
      float hdrStrength;
      float bloomStrength;
      uint sceneAvailable;
      uint uiAvailable;
      uint semanticAvailable;
    };

    struct HdrAdaptiveState {
      float breakpoint;
      float inferredPeak;
      float medianLog2;
      float p90Log2;
      float p99Log2;
      float brightCoverage;
      float currentHeadroom;
      uint valid;
    };

    float3 metallum_srgb_to_linear(float3 encoded, bool extendedRange) {
      float3 magnitude = extendedRange ? abs(encoded) : clamp(encoded, 0.0, 1.0);
      float3 low = magnitude / 12.92;
      float3 high = pow((magnitude + 0.055) / 1.055, float3(2.4));
      float3 decoded = select(high, low, magnitude <= float3(0.04045));
      return extendedRange ? copysign(decoded, encoded) : decoded;
    }

    float3 metallum_decode(float3 value, uint sourceEncoding) {
      if (sourceEncoding == 0u) {
        return metallum_srgb_to_linear(value, false);
      }
      if (sourceEncoding == 1u) {
        return max(metallum_srgb_to_linear(value, true), 0.0);
      }
      return max(value, 0.0);
    }

    float3 metallum_linear_to_srgb(float3 linearValue) {
      float3 bounded = clamp(linearValue, 0.0, 1.0);
      float3 low = bounded * 12.92;
      float3 high = 1.055 * pow(bounded, float3(1.0 / 2.4)) - 0.055;
      return select(high, low, bounded <= float3(0.0031308));
    }

    float3 metallum_encode_sdr(float3 value, uint sourceEncoding) {
      // RGBA8 and legacy FP16 sources already contain display-encoded sRGB.
      // A scene-linear FP16 source needs the inverse transfer when HDR output
      // is disabled live, because the startup-only scene contract remains
      // linear until Minecraft is restarted.
      return sourceEncoding == 2u
        ? metallum_linear_to_srgb(value)
        : clamp(value, 0.0, 1.0);
    }

    float metallum_luminance(float3 color) {
      return dot(max(color, 0.0), float3(0.2126, 0.7152, 0.0722));
    }

    float metallum_spatial_scene_visibility(float4 uiValue, float3 expectedBackdrop) {
      constexpr float residualTolerance = 1.1 / 255.0;
      float alphaCoverage = clamp(uiValue.a, 0.0, 1.0);
      if (alphaCoverage > 0.0) {
        return 1.0 - alphaCoverage;
      }
      float3 finalEncoded = clamp(uiValue.rgb, 0.0, 1.0);
      float3 delta = finalEncoded - expectedBackdrop;
      float difference = max(abs(delta.r), max(abs(delta.g), abs(delta.b)));
      if (difference <= residualTolerance) {
        return 1.0;
      }
      bool darkeningOnly = all(finalEncoded <= expectedBackdrop + residualTolerance);
      if (!darkeningOnly) {
        return 0.0;
      }
      float expectedY = metallum_luminance(
        metallum_srgb_to_linear(expectedBackdrop, false)
      );
      float finalY = metallum_luminance(
        metallum_srgb_to_linear(finalEncoded, false)
      );
      return expectedY > 1e-7
        ? clamp(finalY / expectedY, 0.0, 1.0)
        : 1.0;
    }

    float metallum_peak_metric(float3 color) {
      return max(color.r, max(color.g, color.b));
    }

    float3 metallum_map_to_headroom(float3 color, float headroom) {
      color = max(color, 0.0);
      if (headroom <= 1.0001) {
        return min(color, 1.0);
      }

      float peak = metallum_peak_metric(color);
      float knee = 1.0 + 0.65 * (headroom - 1.0);
      if (peak > knee) {
        float span = max(headroom - knee, 1e-5);
        float mappedPeak = knee + span * (1.0 - exp(-(peak - knee) / span));
        color *= mappedPeak / max(peak, 1e-6);
      }
      return color;
    }

    float metallum_diagnostic_level(float x) {
      constexpr float levels[11] = {
        0.0, 0.02, 0.10, 0.18, 0.50, 1.0,
        1.25, 1.50, 2.0, 4.0, 8.0
      };
      uint index = min(uint(clamp(x, 0.0, 0.999999) * 11.0), 10u);
      return levels[index];
    }

    float metallum_visible_delta_scale(
      float3 mappedBaseColor,
      float3 visibleDelta,
      float currentHeadroom
    ) {
      if (metallum_peak_metric(mappedBaseColor + visibleDelta) <= currentHeadroom) {
        return 1.0;
      }

      // Near the representable headroom boundary, float addition may absorb
      // a sub-ULP positive delta. Preserve the legacy predicate sequence for
      // this rare saturated-base path instead of relying on a ratio formed by
      // subtracting nearly equal floats.
      constexpr float floatEpsilon = 1.1920928955078125e-7;
      float baseMargin = currentHeadroom - metallum_peak_metric(mappedBaseColor);
      if (baseMargin <= max(currentHeadroom, 1.0) * (4.0 * floatEpsilon)) {
        float low = 0.0;
        float high = 1.0;
        for (uint iteration = 0u; iteration < 7u; ++iteration) {
          float candidate = 0.5 * (low + high);
          if (metallum_peak_metric(mappedBaseColor + visibleDelta * candidate)
              <= currentHeadroom) {
            low = candidate;
          } else {
            high = candidate;
          }
        }
        return low;
      }

      float allowedScale = 1.0;
      if (visibleDelta.r > 0.0) {
        allowedScale = min(
          allowedScale,
          (currentHeadroom - mappedBaseColor.r) / visibleDelta.r
        );
      }
      if (visibleDelta.g > 0.0) {
        allowedScale = min(
          allowedScale,
          (currentHeadroom - mappedBaseColor.g) / visibleDelta.g
        );
      }
      if (visibleDelta.b > 0.0) {
        allowedScale = min(
          allowedScale,
          (currentHeadroom - mappedBaseColor.b) / visibleDelta.b
        );
      }

      // Preserve the exact 1/128 result of the previous seven-step binary
      // search. Division can round across a bin boundary, so validate the
      // estimated bin and its successor with the original peak predicate.
      constexpr float scaleStep = 1.0 / 128.0;
      float quantizedScale = floor(clamp(allowedScale, 0.0, 1.0) * 128.0) * scaleStep;
      if (metallum_peak_metric(mappedBaseColor + visibleDelta * quantizedScale)
          > currentHeadroom) {
        quantizedScale = max(0.0, quantizedScale - scaleStep);
      }
      float nextScale = min(1.0, quantizedScale + scaleStep);
      if (nextScale > quantizedScale
          && metallum_peak_metric(mappedBaseColor + visibleDelta * nextScale)
            <= currentHeadroom) {
        quantizedScale = nextScale;
      }
      return quantizedScale;
    }

    vertex PresentVertexOut metallum_present_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };

      // Y-flip version:
      // old equivalent was uvMin=(0,1), uvMax=(1,0)
      const float2 uvs[3] = {
        float2(0.0,  1.0),
        float2(2.0,  1.0),
        float2(0.0, -1.0)
      };

      PresentVertexOut out;
      out.position = float4(positions[vertexId], 0.0, 1.0);
      out.uv = uvs[vertexId];
      return out;
    }

    vertex PresentVertexOut metallum_offscreen_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };
      const float2 uvs[3] = {
        float2(0.0, 0.0),
        float2(2.0, 0.0),
        float2(0.0, 2.0)
      };
      PresentVertexOut out;
      out.position = float4(positions[vertexId], 0.0, 1.0);
      out.uv = uvs[vertexId];
      return out;
    }

    fragment float4 metallum_present_fs(
      PresentVertexOut in [[stage_in]],
      texture2d<float> finalFrame [[texture(0)]],
      texture2d<float> sceneFrame [[texture(1)]],
      texture2d<float> emissionFrame [[texture(2)]],
      texture2d<float> bloomFrame [[texture(3)]],
      texture2d<float> uiMaskFrame [[texture(4)]],
      texture2d<float> uiFrame [[texture(5)]],
      texture2d<float> semanticFrame [[texture(6)]],
      depth2d<float> sceneDepthFrame [[texture(7)]],
      sampler smp [[sampler(0)]],
      sampler auxiliarySmp [[sampler(1)]],
      constant PresentUniforms& uniforms [[buffer(0)]],
      constant HdrAdaptiveState& adaptive [[buffer(1)]]
    ) {
      if (uniforms.diagnosticPattern != 0u) {
        float level = metallum_diagnostic_level(in.uv.x);
        float grid = step(0.012, fract(in.uv.x * 11.0));
        float3 value = float3(level * grid);

        // The lower strip identifies the current safe EDR ceiling in green.
        if (in.uv.y > 0.92) {
          value = in.uv.x <= min(uniforms.currentHeadroom / 8.0, 1.0)
            ? float3(0.0, min(uniforms.currentHeadroom, 8.0), 0.0)
            : float3(0.0);
        }

        return float4(
          uniforms.mode == 0u ? metallum_linear_to_srgb(min(value, 1.0)) : value,
          1.0
        );
      }

      float4 displayBase;
      if (uniforms.uiAvailable != 0u) {
        displayBase = uiFrame.sample(auxiliarySmp, in.uv);
      } else {
        displayBase = finalFrame.sample(smp, in.uv);
      }
      if (uniforms.mode == 0u) {
        return float4(
          uniforms.uiAvailable != 0u
            ? clamp(displayBase.rgb, 0.0, 1.0)
            : metallum_encode_sdr(displayBase.rgb, uniforms.sourceEncoding),
          1.0
        );
      }

      // A seeded UI texture is a complete SDR composite, not a transparent
      // premultiplied overlay. It is therefore the display-referred base and
      // must always be decoded as bounded sRGB regardless of scene encoding.
      float3 linearColor = uniforms.uiAvailable != 0u
        ? metallum_srgb_to_linear(clamp(displayBase.rgb, 0.0, 1.0), false)
        : metallum_decode(displayBase.rgb, uniforms.sourceEncoding);
      float3 mappedBaseColor = metallum_map_to_headroom(linearColor, uniforms.currentHeadroom);
      if (uniforms.mode != 2u || uniforms.sceneAvailable == 0u || uniforms.currentHeadroom <= 1.001) {
        return float4(mappedBaseColor, 1.0);
      }

      float headroomActivation = smoothstep(1.0, 1.15, uniforms.currentHeadroom);
      float strength = uniforms.hdrStrength * headroomActivation;
      float3 sceneLinear = metallum_decode(sceneFrame.sample(smp, in.uv).rgb, uniforms.sourceEncoding);
      float4 emissionSample = max(emissionFrame.sample(auxiliarySmp, in.uv), 0.0);
      float3 bloom = max(bloomFrame.sample(auxiliarySmp, in.uv).rgb, 0.0);
      float availableBloomRange = min(max(uniforms.currentHeadroom - 1.0, 0.0), 2.0);
      float bloomScale = uniforms.bloomStrength * strength * availableBloomRange;
      float3 bloomContribution = bloom * bloomScale;
      float maximumBloomPeak = 0.15 * availableBloomRange;
      float bloomPeak = metallum_peak_metric(bloomContribution);
      if (bloomPeak > maximumBloomPeak && maximumBloomPeak > 0.0) {
        bloomContribution *= maximumBloomPeak / bloomPeak;
      }
      float2 uiControl = clamp(uiMaskFrame.sample(auxiliarySmp, in.uv).rg, 0.0, 1.0);
      float sceneVisibility = (1.0 - uiControl.r) * (1.0 - uiControl.g);

      // Direct semantic reconstruction is full-resolution. The quarter-size
      // emission texture is deliberately used only as a coverage-weighted
      // bloom seed so one bright texel cannot flatten or enlarge a 4x4 cell.
      float semanticStrength = 0.0;
      float semanticExact = 0.0;
      if (uniforms.semanticAvailable != 0u) {
        float2 boundedUv = clamp(in.uv, float2(0.0), float2(0.999999));
        uint2 semanticSize = uint2(semanticFrame.get_width(), semanticFrame.get_height());
        uint2 semanticMaximum = max(semanticSize, uint2(1u)) - 1u;
        uint2 semanticCoordinate = min(
          uint2(boundedUv * float2(semanticSize)),
          semanticMaximum
        );
        uint4 semanticBytes = uint4(round(clamp(
          semanticFrame.read(semanticCoordinate),
          0.0,
          1.0
        ) * 255.0));
        uint code = semanticBytes.x;
        uint strengthCode = code & 127u;
        if (strengthCode != 0u) {
          uint markerPackedDepth = semanticBytes.y
            | (semanticBytes.z << 8u)
            | (semanticBytes.w << 16u);
          uint2 depthSize = uint2(sceneDepthFrame.get_width(), sceneDepthFrame.get_height());
          uint2 depthMaximum = max(depthSize, uint2(1u)) - 1u;
          uint2 depthCoordinate = min(uint2(boundedUv * float2(depthSize)), depthMaximum);
          uint scenePackedDepth = uint(round(
            clamp(sceneDepthFrame.read(depthCoordinate), 0.0, 1.0) * 16777215.0
          ));
          if (markerPackedDepth + 2u >= scenePackedDepth) {
            semanticStrength = float(strengthCode) / 127.0;
            semanticExact = (code & 128u) != 0u ? 1.0 : 0.0;
          }
        }
      }

      // Scene-wide highlight reconstruction. The SDR artistic exposure is
      // preserved below the shoulder; isolated and broadly bright world
      // highlights can use EDR without multiplying shadows or midtones.
      float sceneY = metallum_luminance(sceneLinear);
      float scenePeak = metallum_peak_metric(sceneLinear);
      float chromaticHighlightGate = smoothstep(0.18, 0.45, sceneY);
      float sceneSignal = max(sceneY, scenePeak * chromaticHighlightGate);
      float localY = emissionSample.a;
      float isolationDetail = max(sceneY - localY, 0.0);
      float localIsolation = smoothstep(0.06, 0.42, isolationDetail);
      float adaptiveBreakpoint = clamp(adaptive.breakpoint, 0.34, 0.70);
      float isolatedBreakpoint = max(0.34, adaptiveBreakpoint - 0.15);
      float expansionStart = mix(adaptiveBreakpoint, isolatedBreakpoint, localIsolation);
      float expansionX = clamp((min(sceneSignal, 1.0) - expansionStart)
        / max(1.0 - expansionStart, 1e-5), 0.0, 1.0);
      float expansionCurve = expansionX * expansionX * (3.0 - 2.0 * expansionX);
      float adaptivePeak = clamp(
        adaptive.inferredPeak,
        1.0,
        min(uniforms.currentHeadroom, 3.0)
      );
      float adaptiveActivation = smoothstep(1.0, 1.02, adaptivePeak);
      float reconstructedPeak = scenePeak
        + max(adaptivePeak - scenePeak, 0.0)
        * expansionCurve * strength * adaptiveActivation;
      float3 inferredScene = sceneLinear
        * (reconstructedPeak / max(scenePeak, 1e-6));

      // Semantic light is source-authored, but its body stays tied to the
      // actual display range and to the source texel's brightness. This keeps
      // the hierarchy and texture detail instead of driving every emitter to
      // the same EDR ceiling.
      float availableSemanticRange = max(min(uniforms.currentHeadroom, 4.0) - 1.0, 0.0);
      float semanticFraction = semanticStrength * mix(0.55, 0.78, semanticExact);
      float semanticDetail = smoothstep(0.12, 0.90, scenePeak);
      float semanticScale = 1.0
        + availableSemanticRange * semanticFraction * semanticDetail * strength;
      float3 semanticScene = sceneLinear * semanticScale;
      // Semantic strength is also confidence in source authorship. Fade weak
      // markers into scene reconstruction so the first quantized level cannot
      // replace the neighboring sky with a different HDR curve.
      float semanticAuthority = smoothstep(0.0, 0.20, semanticStrength);
      float3 selectedScene = mix(inferredScene, semanticScene, semanticAuthority);
      float3 sceneHdr = metallum_map_to_headroom(
        selectedScene + bloomContribution,
        uniforms.currentHeadroom
      );
      float3 mappedSceneBase = metallum_map_to_headroom(sceneLinear, uniforms.currentHeadroom);
      float3 hdrDelta = sceneHdr - mappedSceneBase;
      float3 visibleDelta = sceneVisibility * hdrDelta;
      visibleDelta *= metallum_visible_delta_scale(
        mappedBaseColor,
        visibleDelta,
        uniforms.currentHeadroom
      );
      return float4(mappedBaseColor + visibleDelta, 1.0);
    }

    float3 metallum_reconstruct_world(
      float2 uv,
      texture2d<float> sceneFrame,
      texture2d<float> emissionFrame,
      texture2d<float> bloomFrame,
      texture2d<float> semanticFrame,
      depth2d<float> sceneDepthFrame,
      sampler smp,
      sampler auxiliarySmp,
      constant PresentUniforms& uniforms,
      constant HdrAdaptiveState& adaptive
    ) {
      float headroomActivation = smoothstep(1.0, 1.15, uniforms.currentHeadroom);
      float strength = uniforms.hdrStrength * headroomActivation;
      float3 sceneLinear = metallum_decode(
        sceneFrame.sample(smp, uv).rgb,
        uniforms.sourceEncoding
      );
      float4 emissionSample = max(emissionFrame.sample(auxiliarySmp, uv), 0.0);
      float3 bloom = max(bloomFrame.sample(auxiliarySmp, uv).rgb, 0.0);
      float availableBloomRange = min(max(uniforms.currentHeadroom - 1.0, 0.0), 2.0);
      float bloomScale = uniforms.bloomStrength * strength * availableBloomRange;
      float3 bloomContribution = bloom * bloomScale;
      float maximumBloomPeak = 0.15 * availableBloomRange;
      float bloomPeak = metallum_peak_metric(bloomContribution);
      if (bloomPeak > maximumBloomPeak && maximumBloomPeak > 0.0) {
        bloomContribution *= maximumBloomPeak / bloomPeak;
      }

      float semanticStrength = 0.0;
      float semanticExact = 0.0;
      if (uniforms.semanticAvailable != 0u) {
        float2 boundedUv = clamp(uv, float2(0.0), float2(0.999999));
        uint2 semanticSize = uint2(semanticFrame.get_width(), semanticFrame.get_height());
        uint2 semanticMaximum = max(semanticSize, uint2(1u)) - 1u;
        uint2 semanticCoordinate = min(
          uint2(boundedUv * float2(semanticSize)),
          semanticMaximum
        );
        uint4 semanticBytes = uint4(round(clamp(
          semanticFrame.read(semanticCoordinate),
          0.0,
          1.0
        ) * 255.0));
        uint code = semanticBytes.x;
        uint strengthCode = code & 127u;
        if (strengthCode != 0u) {
          uint markerPackedDepth = semanticBytes.y
            | (semanticBytes.z << 8u)
            | (semanticBytes.w << 16u);
          uint2 depthSize = uint2(sceneDepthFrame.get_width(), sceneDepthFrame.get_height());
          uint2 depthMaximum = max(depthSize, uint2(1u)) - 1u;
          uint2 depthCoordinate = min(uint2(boundedUv * float2(depthSize)), depthMaximum);
          uint scenePackedDepth = uint(round(
            clamp(sceneDepthFrame.read(depthCoordinate), 0.0, 1.0) * 16777215.0
          ));
          if (markerPackedDepth + 2u >= scenePackedDepth) {
            semanticStrength = float(strengthCode) / 127.0;
            semanticExact = (code & 128u) != 0u ? 1.0 : 0.0;
          }
        }
      }

      float sceneY = metallum_luminance(sceneLinear);
      float scenePeak = metallum_peak_metric(sceneLinear);
      float chromaticHighlightGate = smoothstep(0.18, 0.45, sceneY);
      float sceneSignal = max(sceneY, scenePeak * chromaticHighlightGate);
      float localY = emissionSample.a;
      float isolationDetail = max(sceneY - localY, 0.0);
      float localIsolation = smoothstep(0.06, 0.42, isolationDetail);
      float adaptiveBreakpoint = clamp(adaptive.breakpoint, 0.34, 0.70);
      float isolatedBreakpoint = max(0.34, adaptiveBreakpoint - 0.15);
      float expansionStart = mix(adaptiveBreakpoint, isolatedBreakpoint, localIsolation);
      float expansionX = clamp((min(sceneSignal, 1.0) - expansionStart)
        / max(1.0 - expansionStart, 1e-5), 0.0, 1.0);
      float expansionCurve = expansionX * expansionX * (3.0 - 2.0 * expansionX);
      float adaptivePeak = clamp(
        adaptive.inferredPeak,
        1.0,
        min(uniforms.currentHeadroom, 3.0)
      );
      float adaptiveActivation = smoothstep(1.0, 1.02, adaptivePeak);
      float reconstructedPeak = scenePeak
        + max(adaptivePeak - scenePeak, 0.0)
        * expansionCurve * strength * adaptiveActivation;
      float3 inferredScene = sceneLinear
        * (reconstructedPeak / max(scenePeak, 1e-6));

      float availableSemanticRange = max(min(uniforms.currentHeadroom, 4.0) - 1.0, 0.0);
      float semanticFraction = semanticStrength * mix(0.55, 0.78, semanticExact);
      float semanticDetail = smoothstep(0.12, 0.90, scenePeak);
      float semanticScale = 1.0
        + availableSemanticRange * semanticFraction * semanticDetail * strength;
      float3 semanticScene = sceneLinear * semanticScale;
      float semanticAuthority = smoothstep(0.0, 0.20, semanticStrength);
      float3 selectedScene = mix(inferredScene, semanticScene, semanticAuthority);
      float3 sceneHdr = metallum_map_to_headroom(
        selectedScene + bloomContribution,
        uniforms.currentHeadroom
      );
      return sceneHdr;
    }

    struct NativeWorldUiOutput {
      float4 hdr [[color(0)]];
      float4 uiSeed [[color(1)]];
    };

    fragment float4 metallum_spatial_world_fs(
      PresentVertexOut in [[stage_in]],
      texture2d<float> sceneFrame [[texture(0)]],
      texture2d<float> emissionFrame [[texture(1)]],
      texture2d<float> bloomFrame [[texture(2)]],
      texture2d<float> semanticFrame [[texture(3)]],
      depth2d<float> sceneDepthFrame [[texture(4)]],
      sampler smp [[sampler(0)]],
      sampler auxiliarySmp [[sampler(1)]],
      constant PresentUniforms& uniforms [[buffer(0)]],
      constant HdrAdaptiveState& adaptive [[buffer(1)]]
    ) {
      return float4(metallum_reconstruct_world(
        in.uv,
        sceneFrame,
        emissionFrame,
        bloomFrame,
        semanticFrame,
        sceneDepthFrame,
        smp,
        auxiliarySmp,
        uniforms,
        adaptive
      ), 1.0);
    }

    fragment NativeWorldUiOutput metallum_native_world_ui_fs(
      PresentVertexOut in [[stage_in]],
      texture2d<float> sceneFrame [[texture(0)]],
      texture2d<float> emissionFrame [[texture(1)]],
      texture2d<float> bloomFrame [[texture(2)]],
      texture2d<float> semanticFrame [[texture(3)]],
      depth2d<float> sceneDepthFrame [[texture(4)]],
      sampler smp [[sampler(0)]],
      sampler auxiliarySmp [[sampler(1)]],
      constant PresentUniforms& uniforms [[buffer(0)]],
      constant HdrAdaptiveState& adaptive [[buffer(1)]]
    ) {
      float3 sceneHdr = metallum_reconstruct_world(
        in.uv,
        sceneFrame,
        emissionFrame,
        bloomFrame,
        semanticFrame,
        sceneDepthFrame,
        smp,
        auxiliarySmp,
        uniforms,
        adaptive
      );
      float3 seedEncoded = metallum_encode_sdr(sceneHdr, 2u);
      seedEncoded = floor(clamp(seedEncoded, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
      NativeWorldUiOutput out;
      out.hdr = float4(sceneHdr, 1.0);
      out.uiSeed = float4(seedEncoded, 0.0);
      return out;
    }

    fragment float4 metallum_spatial_present_fs(
      PresentVertexOut in [[stage_in]],
      texture2d<float> uiFrame [[texture(0)]],
      texture2d<float> spatialHdrFrame [[texture(1)]],
      constant PresentUniforms& uniforms [[buffer(0)]]
    ) {
      uint2 textureSize = uint2(uiFrame.get_width(), uiFrame.get_height());
      uint2 maximumCoordinate = max(textureSize, uint2(1u)) - 1u;
      uint2 coordinate = min(uint2(in.position.xy), maximumCoordinate);
      if (uniforms.diagnosticPattern == 0u) {
        coordinate.y = maximumCoordinate.y - coordinate.y;
      }
      float4 uiValue = uiFrame.read(coordinate);
      float3 uiEncoded = clamp(uiValue.rgb, 0.0, 1.0);

      float3 spatialHdr = max(spatialHdrFrame.read(coordinate).rgb, 0.0);
      float3 seedEncoded = metallum_encode_sdr(spatialHdr, 2u);
      seedEncoded = floor(clamp(seedEncoded, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
      float visibility = metallum_spatial_scene_visibility(uiValue, seedEncoded);
      if (visibility >= 1.0
          && metallum_peak_metric(spatialHdr) <= uniforms.currentHeadroom) {
        return float4(spatialHdr, 1.0);
      }
      float3 uiLinear = metallum_srgb_to_linear(uiEncoded, false);
      if (visibility <= 0.0) {
        return float4(uiLinear, 1.0);
      }
      float3 seedLinear = metallum_srgb_to_linear(seedEncoded, false);
      float3 visibleDelta = visibility * (spatialHdr - seedLinear);
      visibleDelta *= metallum_visible_delta_scale(
        uiLinear,
        visibleDelta,
        uniforms.currentHeadroom
      );
      return float4(clamp(uiLinear + visibleDelta, 0.0, uniforms.currentHeadroom), 1.0);
    }

    fragment float4 metallum_spatial_screenshot_fs(
      PresentVertexOut in [[stage_in]],
      texture2d<float> uiFrame [[texture(0)]],
      texture2d<float> spatialHdrFrame [[texture(1)]],
      constant PresentUniforms& uniforms [[buffer(0)]]
    ) {
      uint2 textureSize = uint2(uiFrame.get_width(), uiFrame.get_height());
      uint2 maximumCoordinate = max(textureSize, uint2(1u)) - 1u;
      uint2 coordinate = min(uint2(in.position.xy), maximumCoordinate);
      if (uniforms.diagnosticPattern == 0u) {
        coordinate.y = maximumCoordinate.y - coordinate.y;
      }
      float4 uiValue = uiFrame.read(coordinate);
      float3 uiEncoded = clamp(uiValue.rgb, 0.0, 1.0);

      float3 spatialHdr = max(spatialHdrFrame.read(coordinate).rgb, 0.0);
      float3 seedEncoded = metallum_encode_sdr(spatialHdr, 2u);
      seedEncoded = floor(clamp(seedEncoded, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
      float visibility = metallum_spatial_scene_visibility(uiValue, seedEncoded);
      if (visibility >= 1.0) {
        return float4(seedEncoded, 1.0);
      }
      if (visibility <= 0.0) {
        return float4(uiEncoded, 1.0);
      }
      float3 uiLinear = metallum_srgb_to_linear(uiEncoded, false);
      float3 seedLinear = metallum_srgb_to_linear(seedEncoded, false);
      float3 visibleDelta = visibility * (spatialHdr - seedLinear);
      visibleDelta *= metallum_visible_delta_scale(
        uiLinear,
        visibleDelta,
        uniforms.currentHeadroom
      );
      float3 finalLinear = clamp(uiLinear + visibleDelta, 0.0, 1.0);
      return float4(metallum_linear_to_srgb(finalLinear), 1.0);
    }
    """
}

private func hdrEffectsMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct HdrVertexOut {
      float4 position [[position]];
      float2 uv;
    };

    struct HdrExtractUniforms {
      uint sourceEncoding;
      uint semanticAvailable;
      uint2 sourceSize;
      uint histogramEnabled;
      uint _padding0;
    };

    struct HdrBlurUniforms {
      float2 texelStep;
      float2 _padding0;
    };

    struct HdrUiBackdropUniforms {
      uint sourceEncoding;
    };

    struct HdrUiCompareUniforms {
      uint sourceEncoding;
      uint seededUiAvailable;
      uint scaleScene;
      uint _padding0;
    };

    struct HdrHistogramReduceUniforms {
      float currentHeadroom;
      float deltaTime;
      uint forceReset;
      uint _padding0;
    };

    struct HdrAdaptiveState {
      float breakpoint;
      float inferredPeak;
      float medianLog2;
      float p90Log2;
      float p99Log2;
      float brightCoverage;
      float currentHeadroom;
      uint valid;
    };

    vertex HdrVertexOut metallum_hdr_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };
      const float2 uvs[3] = {
        float2(0.0,  1.0),
        float2(2.0,  1.0),
        float2(0.0, -1.0)
      };
      HdrVertexOut out;
      out.position = float4(positions[vertexId], 0.0, 1.0);
      out.uv = uvs[vertexId];
      return out;
    }

    float3 metallum_hdr_srgb_to_linear(float3 encoded, bool extendedRange) {
      float3 magnitude = extendedRange ? abs(encoded) : clamp(encoded, 0.0, 1.0);
      float3 low = magnitude / 12.92;
      float3 high = pow((magnitude + 0.055) / 1.055, float3(2.4));
      float3 decoded = select(high, low, magnitude <= float3(0.04045));
      return extendedRange ? copysign(decoded, encoded) : decoded;
    }

    float3 metallum_hdr_decode(float3 value, uint sourceEncoding) {
      if (sourceEncoding == 0u) {
        return metallum_hdr_srgb_to_linear(value, false);
      }
      if (sourceEncoding == 1u) {
        return max(metallum_hdr_srgb_to_linear(value, true), 0.0);
      }
      return max(value, 0.0);
    }

    float3 metallum_hdr_linear_to_srgb(float3 linearValue) {
      float3 bounded = clamp(linearValue, 0.0, 1.0);
      float3 low = bounded * 12.92;
      float3 high = 1.055 * pow(bounded, float3(1.0 / 2.4)) - 0.055;
      return select(high, low, bounded <= float3(0.0031308));
    }

    float3 metallum_hdr_sdr_encoded_appearance(float3 value, uint sourceEncoding) {
      // SRGB and extended-SRGB scene values are already display encoded. A
      // linear source needs an explicit bounded transfer into the RGBA8 UI
      // target so the seeded backdrop represents the same SDR appearance.
      return sourceEncoding == 2u
        ? metallum_hdr_linear_to_srgb(value)
        : clamp(value, 0.0, 1.0);
    }

    float3 metallum_hdr_quantize_unorm8(float3 value) {
      return floor(clamp(value, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
    }

    float metallum_hdr_luminance(float3 color) {
      return dot(max(color, 0.0), float3(0.2126, 0.7152, 0.0722));
    }

    fragment float4 metallum_hdr_extract_fs(
      HdrVertexOut in [[stage_in]],
      texture2d<float> scene [[texture(0)]],
      texture2d<float> semantic [[texture(1)]],
      depth2d<float> sceneDepth [[texture(2)]],
      constant HdrExtractUniforms& uniforms [[buffer(0)]],
      device atomic_uint* histogram [[buffer(1)]]
    ) {
      uint2 origin = uint2(in.position.xy) * 4u;
      uint2 maximumCoordinate = max(uniforms.sourceSize, uint2(1u)) - 1u;
      float3 semanticBloomSum = float3(0.0);
      float averageY = 0.0;
      float semanticStrength = 0.0;

      for (uint yIndex = 0u; yIndex < 4u; ++yIndex) {
        for (uint xIndex = 0u; xIndex < 4u; ++xIndex) {
          uint2 coordinate = min(origin + uint2(xIndex, yIndex), maximumCoordinate);
          float4 encodedSample = scene.read(coordinate);
          float3 color = metallum_hdr_decode(encodedSample.rgb, uniforms.sourceEncoding);
          float y = metallum_hdr_luminance(color);
          averageY += y;

          if (uniforms.semanticAvailable != 0u) {
            uint4 semanticBytes = uint4(round(clamp(semantic.read(coordinate), 0.0, 1.0) * 255.0));
            uint code = semanticBytes.x;
            uint strengthCode = code & 127u;
            if (strengthCode != 0u) {
              uint markerPackedDepth = semanticBytes.y
                | (semanticBytes.z << 8u)
                | (semanticBytes.w << 16u);
              uint scenePackedDepth = uint(round(
                clamp(sceneDepth.read(coordinate), 0.0, 1.0) * 16777215.0
              ));
              // Minecraft 26.2 uses reversed-Z. A semantic fragment may be
              // nearer than the stored scene depth when translucent terrain
              // was rendered through an offscreen target, but it must not be
              // clearly behind a later opaque fragment.
              if (markerPackedDepth + 2u >= scenePackedDepth) {
                float candidateStrength = float(strengthCode) / 127.0;
                float candidateExact = (code & 128u) != 0u ? 1.0 : 0.0;
                float candidateBloomGain = candidateStrength
                  * mix(0.20, 0.42, candidateExact);
                semanticBloomSum += max(color, 0.0) * candidateBloomGain;
                semanticStrength = max(semanticStrength, candidateStrength);
              }
            }
          }
        }
      }
      averageY *= 1.0 / 16.0;

      // Each quarter-resolution fragment contributes exactly one sample.
      // Source-authored semantic emitters are excluded so they do not teach
      // the generic scene reconstruction to boost themselves a second time.
      if (uniforms.histogramEnabled != 0u && semanticStrength <= 0.0) {
        float logY = clamp(log2(max(averageY, exp2(-12.0))), -12.0, 4.0);
        uint bin = min(uint((logY + 12.0) * 4.0), 63u);
        atomic_fetch_add_explicit(&histogram[bin], 1u, memory_order_relaxed);
      }

      if (uniforms.semanticAvailable != 0u) {
        return float4(
          semanticBloomSum * (1.0 / 16.0),
          averageY
        );
      }
      // Generic scene reconstruction now handles non-semantic highlights.
      // Keeping the old visual fallback would apply two unrelated heuristics
      // to the same pixel and create excessive halos.
      return float4(0.0, 0.0, 0.0, averageY);
    }

    kernel void metallum_hdr_histogram_build(
      texture2d<float, access::read> scene [[texture(0)]],
      texture2d<float, access::read> semantic [[texture(1)]],
      depth2d<float, access::read> sceneDepth [[texture(2)]],
      constant HdrExtractUniforms& uniforms [[buffer(0)]],
      device atomic_uint* histogram [[buffer(1)]],
      uint2 position [[thread_position_in_grid]]
    ) {
      uint2 quarterSize = (max(uniforms.sourceSize, uint2(1u)) + 3u) / 4u;
      if (any(position >= quarterSize)) {
        return;
      }
      uint2 origin = position * 4u;
      uint2 maximumCoordinate = max(uniforms.sourceSize, uint2(1u)) - 1u;
      float averageY = 0.0;
      float semanticStrength = 0.0;
      for (uint yIndex = 0u; yIndex < 4u; ++yIndex) {
        for (uint xIndex = 0u; xIndex < 4u; ++xIndex) {
          uint2 coordinate = min(origin + uint2(xIndex, yIndex), maximumCoordinate);
          float3 color = metallum_hdr_decode(scene.read(coordinate).rgb, uniforms.sourceEncoding);
          averageY += metallum_hdr_luminance(color);
          if (uniforms.semanticAvailable != 0u) {
            uint4 semanticBytes = uint4(round(clamp(semantic.read(coordinate), 0.0, 1.0) * 255.0));
            uint code = semanticBytes.x;
            uint strengthCode = code & 127u;
            if (strengthCode != 0u) {
              uint markerPackedDepth = semanticBytes.y
                | (semanticBytes.z << 8u)
                | (semanticBytes.w << 16u);
              uint scenePackedDepth = uint(round(
                clamp(sceneDepth.read(coordinate), 0.0, 1.0) * 16777215.0
              ));
              if (markerPackedDepth + 2u >= scenePackedDepth) {
                semanticStrength = max(semanticStrength, float(strengthCode) / 127.0);
              }
            }
          }
        }
      }
      averageY *= 1.0 / 16.0;
      if (semanticStrength <= 0.0) {
        float logY = clamp(log2(max(averageY, exp2(-12.0))), -12.0, 4.0);
        uint bin = min(uint((logY + 12.0) * 4.0), 63u);
        atomic_fetch_add_explicit(&histogram[bin], 1u, memory_order_relaxed);
      }
    }

    float metallum_hdr_temporal_scalar(float current, float target, float deltaTime) {
      float timeConstant = target > current ? 0.75 : 0.12;
      float blend = 1.0 - exp(-max(deltaTime, 0.0) / timeConstant);
      return mix(current, target, clamp(blend, 0.0, 1.0));
    }

    kernel void metallum_hdr_histogram_reduce(
      device atomic_uint* histogram [[buffer(0)]],
      device HdrAdaptiveState* stateBuffer [[buffer(1)]],
      constant HdrHistogramReduceUniforms& uniforms [[buffer(2)]],
      uint index [[thread_position_in_grid]]
    ) {
      if (index != 0u) {
        return;
      }

      uint bins[64];
      uint total = 0u;
      uint brightCount = 0u;
      for (uint bin = 0u; bin < 64u; ++bin) {
        uint count = atomic_exchange_explicit(&histogram[bin], 0u, memory_order_relaxed);
        bins[bin] = count;
        total += count;
        // Bin 46 starts at log2(Y)=-0.5 (Y~=0.707), a stable quantized
        // threshold for SDR highlights in this 0.25-stop histogram.
        if (bin >= 46u) {
          brightCount += count;
        }
      }

      HdrAdaptiveState previous = stateBuffer[0];
      float safeHeadroom = clamp(uniforms.currentHeadroom, 1.0, 8.0);
      float maximumInferredPeak = min(safeHeadroom, 3.0);
      if (total == 0u) {
        if (previous.valid == 0u) {
          previous.breakpoint = 0.70;
          previous.inferredPeak = 1.0;
        }
        previous.breakpoint = clamp(previous.breakpoint, 0.34, 0.70);
        previous.inferredPeak = clamp(previous.inferredPeak, 1.0, maximumInferredPeak);
        previous.currentHeadroom = safeHeadroom;
        stateBuffer[0] = previous;
        return;
      }

      uint rank50 = max(uint(ceil(float(total) * 0.50)), 1u);
      uint rank90 = max(uint(ceil(float(total) * 0.90)), 1u);
      uint rank99 = max(uint(ceil(float(total) * 0.99)), 1u);
      uint cumulative = 0u;
      uint bin50 = 63u;
      uint bin90 = 63u;
      uint bin99 = 63u;
      bool found50 = false;
      bool found90 = false;
      bool found99 = false;
      for (uint bin = 0u; bin < 64u; ++bin) {
        cumulative += bins[bin];
        if (!found50 && cumulative >= rank50) {
          bin50 = bin;
          found50 = true;
        }
        if (!found90 && cumulative >= rank90) {
          bin90 = bin;
          found90 = true;
        }
        if (!found99 && cumulative >= rank99) {
          bin99 = bin;
          found99 = true;
        }
      }

      float p50Log2 = -12.0 + (float(bin50) + 0.5) * 0.25;
      float p90Log2 = -12.0 + (float(bin90) + 0.5) * 0.25;
      float p99Log2 = -12.0 + (float(bin99) + 0.5) * 0.25;
      float p90Y = exp2(p90Log2);
      float p99Y = exp2(p99Log2);
      float brightCoverage = float(brightCount) / max(float(total), 1.0);

      float isolatedPresence = brightCount == 0u
        ? 0.0
        : 1.0 - smoothstep(0.02, 0.08, brightCoverage);
      float upperHighlightSignal = max(
        smoothstep(0.55, 1.0, p99Y),
        isolatedPresence
      );
      float broadHighlightSignal = smoothstep(0.24, 0.50, p90Y);
      float breakpointSignal = max(broadHighlightSignal, 0.5 * upperHighlightSignal);
      float targetBreakpoint = clamp(0.70 - 0.36 * breakpointSignal, 0.34, 0.70);

      // Sparse highlights can approach 92% of the inferred EDR range. Broad
      // outdoor light receives a larger fraction when headroom is scarce, so
      // sky and clouds visibly enter EDR at 1.2x without becoming multi-stop
      // emitters on a high-headroom display. Dense white remains restrained.
      float sparseWeight = 1.0 - smoothstep(0.08, 0.55, brightCoverage);
      float mappingHeadroom = min(safeHeadroom, 3.0);
      float lowHeadroomWeight = exp(-1.5 * max(mappingHeadroom - 1.0, 0.0));
      float denseFraction = mix(0.16, 0.35, lowHeadroomWeight);
      float broadSparseFraction = mix(0.20, 0.92, lowHeadroomWeight);
      float sparseExpansion = upperHighlightSignal
        * mix(denseFraction, 0.92, sparseWeight);
      float broadExpansion = broadHighlightSignal
        * mix(denseFraction, broadSparseFraction, sparseWeight);
      float expansionFraction = max(sparseExpansion, broadExpansion);
      float targetPeak = 1.0
        + (maximumInferredPeak - 1.0) * clamp(expansionFraction, 0.0, 0.92);
      targetPeak = min(targetPeak, maximumInferredPeak);

      bool reset = uniforms.forceReset != 0u
        || previous.valid == 0u
        || uniforms.deltaTime > 1.0
        || abs(p50Log2 - previous.medianLog2) > 2.0;
      float breakpoint = reset
        ? targetBreakpoint
        // A lower breakpoint means more HDR expansion, so invert it while
        // applying the same slow-rise / fast-fall response as inferredPeak.
        : -metallum_hdr_temporal_scalar(
            -previous.breakpoint,
            -targetBreakpoint,
            uniforms.deltaTime
          );
      float inferredPeak = reset
        ? targetPeak
        : metallum_hdr_temporal_scalar(previous.inferredPeak, targetPeak, uniforms.deltaTime);

      HdrAdaptiveState next;
      next.breakpoint = clamp(breakpoint, 0.34, 0.70);
      // This cap is deliberately immediate, independent of temporal fall, so
      // an EDR headroom drop can never leave an over-range frame in flight.
      next.inferredPeak = clamp(inferredPeak, 1.0, maximumInferredPeak);
      next.medianLog2 = p50Log2;
      next.p90Log2 = p90Log2;
      next.p99Log2 = p99Log2;
      next.brightCoverage = clamp(brightCoverage, 0.0, 1.0);
      next.currentHeadroom = safeHeadroom;
      next.valid = 1u;
      stateBuffer[0] = next;
    }

    fragment float4 metallum_hdr_blur_fs(
      HdrVertexOut in [[stage_in]],
      texture2d<float> source [[texture(0)]],
      sampler smp [[sampler(0)]],
      constant HdrBlurUniforms& uniforms [[buffer(0)]]
    ) {
      float4 result = source.sample(smp, in.uv) * 0.2270270270;
      result += source.sample(smp, in.uv + uniforms.texelStep * 1.3846153846) * 0.3162162162;
      result += source.sample(smp, in.uv - uniforms.texelStep * 1.3846153846) * 0.3162162162;
      result += source.sample(smp, in.uv + uniforms.texelStep * 3.2307692308) * 0.0702702703;
      result += source.sample(smp, in.uv - uniforms.texelStep * 3.2307692308) * 0.0702702703;
      return result;
    }

    fragment float4 metallum_hdr_ui_backdrop_fs(
      HdrVertexOut in [[stage_in]],
      texture2d<float> source [[texture(0)]],
      constant HdrUiBackdropUniforms& uniforms [[buffer(0)]]
    ) {
      uint2 sourceSize = uint2(source.get_width(), source.get_height());
      uint2 maximumCoordinate = max(sourceSize, uint2(1u)) - 1u;
      uint2 coordinate = min(uint2(in.position.xy), maximumCoordinate);
      float3 sourceValue = source.read(coordinate).rgb;
      float3 encoded = metallum_hdr_sdr_encoded_appearance(
        sourceValue,
        uniforms.sourceEncoding
      );
      encoded = metallum_hdr_quantize_unorm8(encoded);
      return float4(encoded, 0.0);
    }

    fragment float4 metallum_hdr_ui_compare_fs(
      HdrVertexOut in [[stage_in]],
      texture2d<float> finalFrame [[texture(0)]],
      texture2d<float> sceneFrame [[texture(1)]],
      constant HdrUiCompareUniforms& uniforms [[buffer(0)]]
    ) {
      constexpr sampler smp(coord::normalized, address::clamp_to_edge, filter::linear);
      uint2 sourceSize = uint2(finalFrame.get_width(), finalFrame.get_height());
      uint2 maximumCoordinate = max(sourceSize, uint2(1u)) - 1u;
      uint2 origin = uint2(in.position.xy) * 2u;
      constexpr float residualTolerance = 1.1 / 255.0;
      float hardCoverage = 0.0;
      float dimmingCoverage = 0.0;
      for (uint yIndex = 0u; yIndex < 2u; ++yIndex) {
        for (uint xIndex = 0u; xIndex < 2u; ++xIndex) {
          uint2 coordinate = min(origin + uint2(xIndex, yIndex), maximumCoordinate);
          float4 finalValue = finalFrame.read(coordinate);
          if (uniforms.seededUiAvailable != 0u) {
            float alphaCoverage = clamp(finalValue.a, 0.0, 1.0);
            hardCoverage = max(hardCoverage, alphaCoverage);

            // The seeded target stores ordinary source-over coverage in alpha.
            // Alpha-zero GUI passes need their RGB operation classified:
            // multiplicative darkening (the vanilla vignette) attenuates the
            // HDR delta continuously, while invert/additive changes mask it.
            if (alphaCoverage == 0.0) {
              float2 sceneUv = (float2(coordinate) + 0.5) / float2(sourceSize);
              float3 sceneValue = uniforms.scaleScene != 0u
                ? sceneFrame.sample(smp, sceneUv).rgb
                : sceneFrame.read(coordinate).rgb;
              float3 expectedBackdrop = metallum_hdr_quantize_unorm8(
                metallum_hdr_sdr_encoded_appearance(
                  sceneValue,
                  uniforms.sourceEncoding
                )
              );
              float3 finalEncoded = clamp(finalValue.rgb, 0.0, 1.0);
              float3 delta = finalEncoded - expectedBackdrop;
              float difference = max(abs(delta.r), max(abs(delta.g), abs(delta.b)));
              if (difference > residualTolerance) {
                bool darkeningOnly = all(finalEncoded <= expectedBackdrop + residualTolerance);
                if (darkeningOnly) {
                  float expectedY = metallum_hdr_luminance(
                    metallum_hdr_srgb_to_linear(expectedBackdrop, false)
                  );
                  float finalY = metallum_hdr_luminance(
                    metallum_hdr_srgb_to_linear(finalEncoded, false)
                  );
                  float transmission = expectedY > 1e-7
                    ? clamp(finalY / expectedY, 0.0, 1.0)
                    : 1.0;
                  dimmingCoverage = max(dimmingCoverage, 1.0 - transmission);
                } else {
                  hardCoverage = 1.0;
                }
              }
            }
          } else {
            float2 sceneUv = (float2(coordinate) + 0.5) / float2(sourceSize);
            float3 sceneValue = uniforms.scaleScene != 0u
              ? sceneFrame.sample(smp, sceneUv).rgb
              : sceneFrame.read(coordinate).rgb;
            float3 delta = abs(finalValue.rgb - sceneValue);
            float difference = max(delta.r, max(delta.g, delta.b));
            hardCoverage = max(
              hardCoverage,
              smoothstep(0.25 / 255.0, 0.75 / 255.0, difference)
            );
          }
        }
      }
      return float4(hardCoverage, dimmingCoverage, 0.0, 1.0);
    }

    fragment float4 metallum_hdr_ui_dilate_fs(
      HdrVertexOut in [[stage_in]],
      texture2d<float> source [[texture(0)]]
    ) {
      uint2 sourceSize = uint2(source.get_width(), source.get_height());
      int2 maximumCoordinate = int2(max(sourceSize, uint2(1u)) - 1u);
      int2 center = int2(in.position.xy);
      float2 centerControl = source.read(uint2(clamp(center, int2(0), maximumCoordinate))).rg;
      float hardCoverage = 0.0;
      for (int yOffset = -1; yOffset <= 1; ++yOffset) {
        for (int xOffset = -1; xOffset <= 1; ++xOffset) {
          uint2 coordinate = uint2(clamp(center + int2(xOffset, yOffset), int2(0), maximumCoordinate));
          hardCoverage = max(hardCoverage, source.read(coordinate).r);
        }
      }
      return float4(hardCoverage, centerControl.g, 0.0, 1.0);
    }
    """
}

private func buildHdrPipelines(device: MTLDevice) -> MetallumHdrPipelines? {
    do {
        let library = try device.makeLibrary(source: hdrEffectsMslSource(), options: nil)
        guard
            let vertexFunction = library.makeFunction(name: "metallum_hdr_vs"),
            let extractFunction = library.makeFunction(name: "metallum_hdr_extract_fs"),
            let histogramBuildFunction = library.makeFunction(name: "metallum_hdr_histogram_build"),
            let histogramReduceFunction = library.makeFunction(name: "metallum_hdr_histogram_reduce"),
            let blurFunction = library.makeFunction(name: "metallum_hdr_blur_fs"),
            let uiBackdropFunction = library.makeFunction(name: "metallum_hdr_ui_backdrop_fs"),
            let uiCompareFunction = library.makeFunction(name: "metallum_hdr_ui_compare_fs"),
            let uiDilateFunction = library.makeFunction(name: "metallum_hdr_ui_dilate_fs")
        else {
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

        return try MetallumHdrPipelines(
            extract: makePipeline(extractFunction, colorFormat: .rgba16Float),
            histogramBuild: device.makeComputePipelineState(function: histogramBuildFunction),
            histogramReduce: device.makeComputePipelineState(function: histogramReduceFunction),
            blur: makePipeline(blurFunction, colorFormat: .rgba16Float),
            uiBackdrop: makePipeline(uiBackdropFunction, colorFormat: .rgba8Unorm),
            uiCompare: makePipeline(uiCompareFunction, colorFormat: .rg8Unorm),
            uiDilate: makePipeline(uiDilateFunction, colorFormat: .rg8Unorm)
        )
    } catch {
        NSLog("[metallum] Failed to create HDR effect pipelines: %@", String(describing: error))
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

private func makeHdrAdaptiveStateBuffer(device: MTLDevice, label: String) -> MTLBuffer? {
    var initialState = MetallumHdrAdaptiveState(
        breakpoint: 0.70,
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
    sourceWidth: Int,
    sourceHeight: Int,
    displayWidth: Int,
    displayHeight: Int
) -> MetallumHdrWorkspace? {
    let key = objectAddress(device)
    if let cached = NativeState.hdrWorkspaces[key],
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
        label: String
    ) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.hazardTrackingMode = .tracked
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        texture.label = label
        return texture
    }

    guard
        let emission = makeTexture(format: .rgba16Float, width: bloomWidth, height: bloomHeight, label: "Metallum HDR emission"),
        let bloomA = makeTexture(format: .rgba16Float, width: bloomWidth, height: bloomHeight, label: "Metallum HDR bloom A"),
        let bloomB = makeTexture(format: .rgba16Float, width: bloomWidth, height: bloomHeight, label: "Metallum HDR bloom B"),
        let histogram = device.makeBuffer(
            length: 64 * MemoryLayout<UInt32>.stride,
            options: .storageModePrivate
        ),
        let adaptiveState = makeHdrAdaptiveStateBuffer(
            device: device,
            label: "Metallum HDR adaptive state"
        )
    else {
        NSLog("[metallum] Failed to allocate HDR workspace for %dx%d", sourceWidth, sourceHeight)
        return nil
    }

    let workspace = MetallumHdrWorkspace(
        sourceWidth: sourceWidth,
        sourceHeight: sourceHeight,
        displayWidth: displayWidth,
        displayHeight: displayHeight,
        emission: emission,
        bloomA: bloomA,
        bloomB: bloomB,
        histogram: histogram,
        adaptiveState: adaptiveState
    )
    histogram.label = "Metallum HDR luminance histogram"
    NativeState.hdrWorkspaces[key] = workspace
    return workspace
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
    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
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
    if sourcePixelFormat != inputPixelFormat {
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
        textureDescriptor.hazardTrackingMode = .untracked
        textureDescriptor.usage = scaler.outputTextureUsage.union(.shaderRead)
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
        ),
        let samplers = presentSamplers(device: commandBuffer.device)
    else {
        return nil
    }
    let linearSampler = samplers.linear

    let now = ProcessInfo.processInfo.systemUptime
    let previousUptime = workspace.lastHistogramUptime
    let deltaTime = previousUptime.map { max(now - $0, 0.0) } ?? 0.0
    let forceReset = previousUptime == nil || deltaTime > 1.0
    workspace.lastHistogramUptime = now
    let separateHistogramPass = gpuTimingIsEnabled(for: commandBuffer)

    if workspace.histogramNeedsInitialization {
        let histogramClearPass = MTLBlitPassDescriptor()
        attachGpuTiming(
            histogramClearPass,
            commandBuffer: commandBuffer,
            stage: .histogramExposure
        )
        guard let histogramClear = commandBuffer.makeBlitCommandEncoder(descriptor: histogramClearPass) else {
            return nil
        }
        histogramClear.label = "Metallum HDR histogram initialization"
        histogramClear.fill(
            buffer: workspace.histogram,
            range: 0..<workspace.histogram.length,
            value: 0
        )
        histogramClear.endEncoding()
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
        histogramEnabled: separateHistogramPass ? 0 : 1,
        _padding0: 0
    )
    withUnsafeBytes(of: &extractUniforms) { bytes in
        extract.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    extract.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    if let globalFence {
        extract.updateFence(globalFence, after: .fragment)
    }
    extract.endEncoding()

    if separateHistogramPass {
        let histogramBuildPass = MTLComputePassDescriptor()
        attachGpuTiming(
            histogramBuildPass,
            commandBuffer: commandBuffer,
            stage: .histogramExposure
        )
        guard let histogramBuild = commandBuffer.makeComputeCommandEncoder(descriptor: histogramBuildPass) else {
            workspace.histogramNeedsInitialization = true
            return nil
        }
        histogramBuild.label = "Metallum HDR histogram build"
        histogramBuild.setComputePipelineState(pipelines.histogramBuild)
        histogramBuild.setTexture(sceneTexture, index: 0)
        histogramBuild.setTexture(semanticTexture ?? sceneTexture, index: 1)
        histogramBuild.setTexture(sceneDepthTexture, index: 2)
        withUnsafeBytes(of: &extractUniforms) { bytes in
            histogramBuild.setBytes(bytes.baseAddress!, length: bytes.count, index: 0)
        }
        histogramBuild.setBuffer(workspace.histogram, offset: 0, index: 1)
        histogramBuild.dispatchThreads(
            MTLSize(width: workspace.emission.width, height: workspace.emission.height, depth: 1),
            threadsPerThreadgroup: MTLSize(width: 8, height: 8, depth: 1)
        )
        histogramBuild.endEncoding()
    }

    let histogramReducePass = MTLComputePassDescriptor()
    attachGpuTiming(
        histogramReducePass,
        commandBuffer: commandBuffer,
        stage: .histogramExposure
    )
    guard let histogramReduce = commandBuffer.makeComputeCommandEncoder(descriptor: histogramReducePass) else {
        workspace.histogramNeedsInitialization = true
        return nil
    }
    histogramReduce.label = "Metallum HDR histogram reduction"
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
    histogramReduce.endEncoding()
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

    guard let horizontal = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: workspace.bloomA,
        pipeline: pipelines.blur,
        stage: .bloomHorizontal
    ) else {
        return nil
    }
    horizontal.setFragmentTexture(workspace.emission, index: 0)
    horizontal.setFragmentSamplerState(linearSampler, index: 0)
    var horizontalUniforms = MetallumHdrBlurUniforms(
        texelStep: SIMD2<Float>(1.0 / Float(workspace.emission.width), 0.0),
        _padding0: SIMD2<Float>(repeating: 0.0)
    )
    withUnsafeBytes(of: &horizontalUniforms) { bytes in
        horizontal.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    horizontal.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    horizontal.endEncoding()

    guard let vertical = makeHdrPassEncoder(
        commandBuffer: commandBuffer,
        target: workspace.bloomB,
        pipeline: pipelines.blur,
        stage: .bloomVertical
    ) else {
        return nil
    }
    vertical.setFragmentTexture(workspace.bloomA, index: 0)
    vertical.setFragmentSamplerState(linearSampler, index: 0)
    var verticalUniforms = MetallumHdrBlurUniforms(
        texelStep: SIMD2<Float>(0.0, 1.0 / Float(workspace.bloomA.height)),
        _padding0: SIMD2<Float>(repeating: 0.0)
    )
    withUnsafeBytes(of: &verticalUniforms) { bytes in
        vertical.setFragmentBytes(bytes.baseAddress!, length: bytes.count, index: 0)
    }
    vertical.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    vertical.endEncoding()

    return MetallumHdrWorldOutputs(
        emission: workspace.emission,
        bloom: workspace.bloomB,
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
    uiCompare.endEncoding()

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
    uiDilate.endEncoding()

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
    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
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
    encoder.endEncoding()
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
    encoder.endEncoding()
    return worldComposite
}

private struct MetallumClearUniforms {
    var z: Float
    var _padding0: SIMD3<Float>
    var color: SIMD4<Float>
}

private func clearMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct ClearUniforms {
      float z;
      float3 _padding0;
      float4 color;
    };

    struct ClearVertexOut {
      float4 position [[position]];
      float4 color;
    };

    vertex ClearVertexOut metallum_clear_vs(
      uint vertexId [[vertex_id]],
      constant ClearUniforms& u [[buffer(1)]]
    ) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };

      ClearVertexOut out;
      out.position = float4(positions[vertexId], u.z, 1.0);
      out.color = u.color;
      return out;
    }

    fragment float4 metallum_clear_fs(ClearVertexOut in [[stage_in]]) {
      return in.color;
    }
    """
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
        let library = try device.makeLibrary(source: clearMslSource(), options: nil)

        guard
            let vertexFunction = library.makeFunction(name: "metallum_clear_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_clear_fs")
        else {
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

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create clear pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat,
    fragmentName: String = "metallum_present_fs",
    vertexName: String = "metallum_present_vs"
) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: presentMslSource(), options: nil)

        guard
            let vertexFunction = library.makeFunction(name: vertexName),
            let fragmentFunction = library.makeFunction(name: fragmentName)
        else {
            NSLog("[metallum] Failed to create present shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
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
        let library = try device.makeLibrary(source: presentMslSource(), options: nil)
        guard
            let vertexFunction = library.makeFunction(name: "metallum_offscreen_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_native_world_ui_fs")
        else {
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
        NativeState.nativeWorldUiPipelines[key] = pipeline
        return pipeline
    } catch {
        NSLog("[metallum] Failed to create fused native HDR/UI pipeline: %@", String(describing: error))
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

@_cdecl("metallum_init_pipelines")
public func metallum_init_pipelines(_ device: MTLDevice) {
    autoreleasepool {
        let deviceAddress = objectAddress(device)
        _ = ensurePresentPipeline(device: device, colorFormat: .bgra8Unorm)
        _ = ensurePresentPipeline(device: device, colorFormat: .rgba16Float)
        _ = ensureWorldPresentPipeline(device: device, colorFormat: .rgba16Float)
        _ = ensureNativeWorldUiPipeline(device: device)
        _ = ensureSpatialPresentPipeline(device: device, colorFormat: .rgba16Float)
        _ = ensureSpatialScreenshotPipeline(device: device, colorFormat: .rgba8Unorm)
        _ = ensureHdrPipelines(device: device)
        _ = ensureHdrFallbackAdaptiveState(device: device)
        _ = ensureHdrFallbackDepthTexture(device: device)
        NativeState.presentLinearSamplers[deviceAddress] = buildPresentSampler(device: device, filter: .linear)
        NativeState.presentNearestSamplers[deviceAddress] = buildPresentSampler(device: device, filter: .nearest)
        _ = ensureClearColorDepthPipeline(device, .bgra8Unorm, .depth32Float)
        _ = ensureClearColorDepthPipeline(device, .rgba8Unorm, .depth32Float)
        _ = ensureClearColorDepthPipeline(device, .bgra8Unorm, .invalid)
    }
}

@_cdecl("metallum_release_device_caches")
public func metallum_release_device_caches(_ device: MTLDevice) {
    autoreleasepool {
        let deviceAddress = objectAddress(device)
        NativeState.depthStencilStates = NativeState.depthStencilStates.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.clearPipelines = NativeState.clearPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.presentPipelines = NativeState.presentPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.spatialPresentPipelines = NativeState.spatialPresentPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.spatialScreenshotPipelines = NativeState.spatialScreenshotPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.worldPresentPipelines = NativeState.worldPresentPipelines.filter {
            $0.key.deviceAddress != deviceAddress
        }
        NativeState.nativeWorldUiPipelines.removeValue(forKey: deviceAddress)
        NativeState.hdrPipelines.removeValue(forKey: deviceAddress)
        NativeState.hdrWorkspaces.removeValue(forKey: deviceAddress)
        NativeState.hdrFallbackAdaptiveStates.removeValue(forKey: deviceAddress)
        NativeState.hdrFallbackDepthTextures.removeValue(forKey: deviceAddress)
        NativeState.spatialWorkspaces.removeValue(forKey: deviceAddress)
        NativeState.presentNearestSamplers.removeValue(forKey: deviceAddress)
        NativeState.presentLinearSamplers.removeValue(forKey: deviceAddress)
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
    let waitStart = DispatchTime.now().uptimeNanoseconds
    let result: DispatchTimeoutResult
    if timeoutMs >= UInt64(Int.max) {
        result = semaphore.wait(timeout: .distantFuture)
    } else {
        result = semaphore.wait(timeout: .now() + .milliseconds(Int(timeoutMs)))
    }
    let waitEnd = DispatchTime.now().uptimeNanoseconds
    NativeState.gpuTimingStats?.recordSynchronizationWait(
        nanoseconds: waitEnd >= waitStart ? waitEnd - waitStart : 0
    )
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
    let waitStart = DispatchTime.now().uptimeNanoseconds
    commandBuffer.waitUntilCompleted()
    let waitEnd = DispatchTime.now().uptimeNanoseconds
    NativeState.gpuTimingStats?.recordSynchronizationWait(
        nanoseconds: waitEnd >= waitStart ? waitEnd - waitStart : 0
    )
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

@_cdecl("metallum_MTLCommandBuffer_makeBlitCommandEncoder")
public func metallum_MTLCommandBuffer_makeBlitCommandEncoder(
    _ commandBuffer: MTLCommandBuffer
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(commandBuffer.makeBlitCommandEncoder())
    }
}

@_cdecl("metallum_MTLCommandEncoder_endEncoding")
public func metallum_MTLCommandEncoder_endEncoding(_ encoder: MTLCommandEncoder) {
    encoder.endEncoding()
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
        retainedPointer(device.makeBuffer(length: length, options: options))
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
        descriptor.hazardTrackingMode = .untracked
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        texture.label = stringFromOptionalCString(labelPtr)
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
    _ maxAnisotropy: Int32,
    _ lodMaxClamp: Double
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = minFilter
        descriptor.magFilter = magFilter
        descriptor.mipFilter = mipFilter
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
    _ clearColorEnabled: Int32,
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
            if clearColorEnabled != 0 {
                renderPass.colorAttachments[0].loadAction = .clear
                renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
            } else {
                renderPass.colorAttachments[0].loadAction = .load
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

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
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

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
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
                encoder.endEncoding()
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

        encoder.endEncoding()
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
    _ spatialScalingEnabled: Int32,
    _ hdrPrecomposeEnabled: Int32,
    _ perceptualScalingEnabled: Int32,
    _ currentHeadroom: Float,
    _ hdrStrength: Float,
    _ bloomStrength: Float
) -> Int32 {
    return autoreleasepool {
        guard
            (0...2).contains(sourceEncoding),
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
        let canPrecomposeHdr = hdrPrecomposeEnabled != 0
            && sourceTexture.pixelFormat == .rgba16Float
            && compatibleDepth
            && compatibleSemantic
        if spatialScalingEnabled == 0 && canPrecomposeHdr {
            guard destinationTexture.width == sourceTexture.width,
                  destinationTexture.height == sourceTexture.height,
                  encodeNativeHdrWorldUiComposite(
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
                  ) != nil else {
                return -3
            }
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
                guard let composite = encodeSpatialHdrWorldComposite(
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
                ) else {
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
                    usesDirectOutput: useDirectPerceptualOutput
                )
            else {
                return -2
            }

            let preparedScalerInput: MTLTexture
            if let perceptualInput = workspace.perceptualInput {
                guard let pipelines = ensureHdrPipelines(device: commandBuffer.device),
                      let encoder = makeHdrPassEncoder(
                        commandBuffer: commandBuffer,
                        target: perceptualInput,
                        pipeline: pipelines.uiBackdrop,
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
                encoder.endEncoding()
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

        if let globalFence {
            // MetalFX signals this fence from its opaque encoder sequence. Do
            // not begin the timed/tiled UI seed pass until that dependency is
            // complete: otherwise the render-pass counter attributes the
            // scaler wait to UI, and the pass can reserve tile resources while
            // it is still unable to execute.
            guard let dependencyWait = commandBuffer.makeBlitCommandEncoder() else {
                return -1
            }
            dependencyWait.label = "Metallum UI backdrop dependency"
            dependencyWait.waitForFence(globalFence)
            dependencyWait.endEncoding()
        }

        guard
            let pipelines = ensureHdrPipelines(device: commandBuffer.device),
            let encoder = makeHdrPassEncoder(
                commandBuffer: commandBuffer,
                target: destinationTexture,
                pipeline: pipelines.uiBackdrop,
                stage: .ui
            )
        else {
            return -1
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
        encoder.endEncoding()
        return hdrPrecomposed ? 2 : 1
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
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
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
        encoder.endEncoding()
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
    _ diagnosticPattern: Int32,
    _ currentHeadroom: Float,
    _ hdrStrength: Float,
    _ bloomStrength: Float
) -> Int32 {
    return autoreleasepool {
        guard (0...2).contains(outputMode) else {
            return -1
        }

        let effectiveHeadroom = min(
            max(1.0, currentHeadroom.isFinite ? currentHeadroom : 1.0),
            8.0
        )
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
        let hasCompatibleScene = canEnhance
            && sceneTexture != nil
            && hasCompatibleDepth
        let hasCompatibleSemantic = hasCompatibleScene
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
            guard
                ensureHdrPipelines(device: commandBuffer.device) != nil,
                ensureHdrWorkspace(
                    device: commandBuffer.device,
                    sourceWidth: sceneTexture!.width,
                    sourceHeight: sceneTexture!.height,
                    displayWidth: displaySceneTexture.width,
                    displayHeight: displaySceneTexture.height
                ) != nil
            else {
                return -1
            }
        }

        let presentPipeline = hasHdrPrecompose
            ? ensureSpatialPresentPipeline(device: commandBuffer.device, colorFormat: layer.pixelFormat)
            : ensurePresentPipeline(device: commandBuffer.device, colorFormat: layer.pixelFormat)
        guard let presentPipeline else {
            NSLog("[metallum] No present pipeline for layer format %lu", layer.pixelFormat.rawValue)
            return -1
        }

        guard let samplers = presentSamplers(device: commandBuffer.device) else {
            NSLog("[metallum] No present samplers for Metal device")
            return -1
        }

        let drawableWaitStart = DispatchTime.now().uptimeNanoseconds
        let nextDrawable: CAMetalDrawable? = layer.nextDrawable()
        let drawableWaitEnd = DispatchTime.now().uptimeNanoseconds
        NativeState.gpuTimingStats?.recordSynchronizationWait(
            nanoseconds: drawableWaitEnd >= drawableWaitStart
                ? drawableWaitEnd - drawableWaitStart
                : 0
        )
        guard let drawable = nextDrawable else {
            return 0
        }

        var separatedHdrTexture = hasHdrPrecompose ? candidatePrecomposedOutput : nil
        if separatedHdrTexture == nil,
           gpuTimingIsEnabled(for: commandBuffer),
           hasCompatibleScene,
           let sceneTexture,
           let sceneDepthTexture {
            separatedHdrTexture = encodeSpatialHdrWorldComposite(
                commandBuffer: commandBuffer,
                sceneTexture: sceneTexture,
                sceneDepthTexture: sceneDepthTexture,
                semanticTexture: hasCompatibleSemantic ? semanticTexture : nil,
                globalFence: globalFence,
                sourceEncoding: sourceEncoding,
                currentHeadroom: effectiveHeadroom,
                hdrStrength: hdrStrength.isFinite ? min(max(hdrStrength, 0.0), 2.0) : 1.0,
                bloomStrength: bloomStrength.isFinite ? min(max(bloomStrength, 0.0), 1.0) : 0.22,
                displayWidth: drawable.texture.width,
                displayHeight: drawable.texture.height
            )
        }

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
            guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
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
            encoder.endEncoding()
            commandBuffer.present(drawable)
            return 1
        }

        var hdrOutputs: MetallumHdrOutputs?
        var hasHdrScene = false
        if hasCompatibleScene, let sceneTexture, let sceneDepthTexture {
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
            guard hdrOutputs != nil else {
                return -1
            }
            hasHdrScene = true
        }

        let adaptiveState: MTLBuffer?
        if hasHdrScene {
            adaptiveState = hdrOutputs?.adaptiveState
        } else {
            adaptiveState = ensureHdrFallbackAdaptiveState(device: commandBuffer.device)
        }
        guard let adaptiveState else {
            return -1
        }

        let presentDepthTexture: MTLTexture
        if hasHdrScene, let sceneDepthTexture {
            presentDepthTexture = sceneDepthTexture
        } else if let fallbackDepth = ensureHdrFallbackDepthTexture(device: commandBuffer.device) {
            presentDepthTexture = fallbackDepth
        } else {
            return -1
        }

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = drawable.texture
        renderPass.colorAttachments[0].loadAction = .dontCare
        renderPass.colorAttachments[0].storeAction = .store
        attachGpuTiming(renderPass, commandBuffer: commandBuffer, stage: .present)

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
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
        encoder.setFragmentTexture(hasHdrScene ? sceneTexture : sourceTexture, index: 1)
        encoder.setFragmentTexture(hasHdrScene ? hdrOutputs?.emission : sourceTexture, index: 2)
        encoder.setFragmentTexture(hasHdrScene ? hdrOutputs?.bloom : sourceTexture, index: 3)
        encoder.setFragmentTexture(hasHdrScene ? hdrOutputs?.uiMask : sourceTexture, index: 4)
        encoder.setFragmentTexture(hasCompatibleUi ? uiTexture : sourceTexture, index: 5)
        encoder.setFragmentTexture(hasCompatibleSemantic ? semanticTexture : sourceTexture, index: 6)
        encoder.setFragmentTexture(presentDepthTexture, index: 7)

        var uniforms = MetallumPresentUniforms(
            mode: UInt32(clamping: max(outputMode, 0)),
            sourceEncoding: UInt32(clamping: max(sourceEncoding, 0)),
            diagnosticPattern: diagnosticPattern == 0 ? 0 : 1,
            currentHeadroom: effectiveHeadroom,
            hdrStrength: hdrStrength.isFinite ? min(max(hdrStrength, 0.0), 2.0) : 1.0,
            bloomStrength: bloomStrength.isFinite ? min(max(bloomStrength, 0.0), 1.0) : 0.22,
            sceneAvailable: hasHdrScene ? 1 : 0,
            uiAvailable: hasCompatibleUi ? 1 : 0,
            semanticAvailable: hasCompatibleSemantic ? 1 : 0
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

        encoder.endEncoding()
        commandBuffer.present(drawable)
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
