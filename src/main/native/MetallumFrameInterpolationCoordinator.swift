import Foundation
import Metal
import MetalFX
import QuartzCore

/** Process-wide counters emitted through the existing JSONL GPU telemetry. */
final class MetallumFrameInterpolationTelemetry: @unchecked Sendable {
    static let shared = MetallumFrameInterpolationTelemetry()

    struct Snapshot {
        let acceptedPairs: Int
        let generatedPresentations: Int
        let realPresentations: Int
        let droppedGeneratedLate: Int
        let backpressureDrops: Int
        let maximumHistogramBuckets: Int

        var report: [String: Any] {
            [
                "accepted_pairs": acceptedPairs,
                "generated_presentations": generatedPresentations,
                "real_presentations": realPresentations,
                "dropped_generated_late": droppedGeneratedLate,
                "backpressure_drops": backpressureDrops,
                "maximum_pacing_histogram_buckets": maximumHistogramBuckets
            ]
        }
    }

    private let lock = NSLock()
    private var acceptedPairs = 0
    private var generatedPresentations = 0
    private var realPresentations = 0
    private var droppedGeneratedLate = 0
    private var backpressureDrops = 0
    private var maximumHistogramBuckets = 0

    private init() {
    }

    func recordAcceptedPair() {
        lock.lock()
        acceptedPairs += 1
        lock.unlock()
    }

    func recordBackpressureDrop() {
        lock.lock()
        backpressureDrops += 1
        lock.unlock()
    }

    func recordGeneratedPresentation() {
        lock.lock()
        generatedPresentations += 1
        lock.unlock()
    }

    func recordRealPresentation() {
        lock.lock()
        realPresentations += 1
        lock.unlock()
    }

    func recordLateGeneratedDrop() {
        lock.lock()
        droppedGeneratedLate += 1
        lock.unlock()
    }

    func recordHistogramBuckets(_ count: Int) {
        lock.lock()
        maximumHistogramBuckets = max(maximumHistogramBuckets, count)
        lock.unlock()
    }

    func snapshot() -> Snapshot {
        lock.lock()
        defer { lock.unlock() }
        return Snapshot(
            acceptedPairs: acceptedPairs,
            generatedPresentations: generatedPresentations,
            realPresentations: realPresentations,
            droppedGeneratedLate: droppedGeneratedLate,
            backpressureDrops: backpressureDrops,
            maximumHistogramBuckets: maximumHistogramBuckets
        )
    }
}

/**
 * Stage-4 lifecycle owner for the future MetalFX presentation path.
 *
 * This deliberately does not acquire a drawable or call `present` from the
 * Java render thread.  Stage 7 owns a native pacing worker and proves its
 * ordering/back-pressure policy with synthetic work, but production admission
 * remains disabled until the later UI and admission stages.  Keeping that
 * boundary explicit prevents an accidental second present while the
 * coordinator is being brought up.
 */
private final class MetallumFrameInterpolationCoordinator: @unchecked Sendable {
    private static let ringSize = 3
    // One display interval is reserved for the real frame.  A generated frame
    // that misses its own slot by more than this small scheduler tolerance is
    // discarded instead of creating a two-present burst immediately before it.
    private static let generatedLatenessToleranceNanoseconds: UInt64 = 1_000_000

    struct Key: Hashable {
        let deviceID: ObjectIdentifier
        let layerID: ObjectIdentifier
        let width: Int
        let height: Int
        let inputWidth: Int
        let inputHeight: Int
        let pixelFormat: MTLPixelFormat
        let rendererGeneration: UInt64
        /// Spatial scalers are not MTLFXFrameInterpolatableScaler instances.
        /// Keep this immutable in the generation key so history can never cross
        /// between linked-Temporal and standalone Spatial interpolation.
        let usesSpatialInputs: Bool

        var isDrawableSized: Bool {
            width > 0 && height > 0
        }

        init?(
            device: MTLDevice,
            layer: CAMetalLayer,
            width: Int,
            height: Int,
            pixelFormat: MTLPixelFormat,
            rendererGeneration: UInt64
        ) {
            guard width >= 0,
                  height >= 0,
                  pixelFormat != .invalid,
                  layer.device === device else {
                return nil
            }
            self.deviceID = ObjectIdentifier(device)
            self.layerID = ObjectIdentifier(layer)
            self.width = width
            self.height = height
            // The stage-4/8 validation ABI only supplied output extent.  It
            // intentionally retains its historical half-resolution test
            // profile; production v2 provides the exact fixed-Temporal input
            // extent below.
            self.inputWidth = max(width / 2, 1)
            self.inputHeight = max(height / 2, 1)
            self.pixelFormat = pixelFormat
            self.rendererGeneration = rendererGeneration
            self.usesSpatialInputs = false
        }

        init?(
            device: MTLDevice,
            layer: CAMetalLayer,
            inputWidth: Int,
            inputHeight: Int,
            width: Int,
            height: Int,
            pixelFormat: MTLPixelFormat,
            rendererGeneration: UInt64,
            usesSpatialInputs: Bool = false
        ) {
            guard inputWidth > 0,
                  inputHeight > 0,
                  width > 0,
                  height > 0,
                  pixelFormat != .invalid,
                  layer.device === device else {
                return nil
            }
            self.deviceID = ObjectIdentifier(device)
            self.layerID = ObjectIdentifier(layer)
            self.width = width
            self.height = height
            self.inputWidth = inputWidth
            self.inputHeight = inputHeight
            self.pixelFormat = pixelFormat
            self.rendererGeneration = rendererGeneration
            self.usesSpatialInputs = usesSpatialInputs
        }
    }

    private enum SlotState {
        case free
        case realFrameReserved
        case history
    }

    /**
     * The MetalFX history is deliberately separate from ticket ownership.
     * A ticket protects a renderer command buffer; this state protects the
     * exact previous color submitted to the interpolator.
     */
    private enum HistoryState {
        case primingFirst
        case primingSecond
        case active
    }

    private struct Slot {
        let realColor: MTLTexture
        let generatedColor: MTLTexture
        let depth: MTLTexture?
        let motion: MTLTexture?
        // The UI remains an SDR texture.  Both present members consume this
        // exact texture through the same composite path; it is never fed to
        // MetalFX or included in world-color history.
        let sdrUi: MTLTexture
        let generatedPresentation: MTLTexture
        let realPresentation: MTLTexture
        var state: SlotState
    }

    private struct ReasonCounters {
        var priming = 0
        var unsupported = 0
        var inputContract = 0
        var reset = 0
        var encoded = 0

        mutating func record(_ status: TicketStatus) {
            switch status {
            case .bypassPriming:
                priming += 1
            case .bypassUnsupported:
                unsupported += 1
            case .bypassInputContract:
                inputContract += 1
            default:
                break
            }
        }
    }

    enum LifecycleStatus: Int32 {
        case ready = 1
        case suspendedZeroSize = 2
        case backpressure = 3
        case invalidContext = -1
        case drainTimedOut = -2
        case released = -3
    }

    /** Stable Java bridge statuses.  Do not reuse lifecycle status values. */
    enum TicketStatus: Int32 {
        case prepared = 1
        case bypassDisabled = 2
        case bypassUnsupported = 3
        case bypassPriming = 4
        case bypassCadence = 5
        case bypassBackpressure = 6
        case bypassNoUi = 7
        case bypassGeneration = 8
        case bypassInputContract = 9
        case noDrawable = 10
        case staleTicket = 11
        case transientFailure = 12
        case fatalForGeneration = -1
    }

    private enum TicketState {
        case prepared
        case published
    }

    /**
     * Scheduler-owned presentation declaration.  The real drawable composite
     * is intentionally added only after the Stage-8 UI path exists; this
     * declaration still gives Stage 7 one authoritative ordering and lateness
     * policy, instead of letting render-thread callers race to present.
     */
    private struct PacingWork {
        let epoch: UInt64
        let generatedPresentationID: UInt64
        let realPresentationID: UInt64
        let generatedDeadlineNanoseconds: UInt64
        let realDeadlineNanoseconds: UInt64
    }

    private enum PacingRecordKind: Equatable {
        case generated
        case real
        case droppedGeneratedLate
    }

    private struct PacingRecord {
        let presentationID: UInt64
        let kind: PacingRecordKind
        let timestampNanoseconds: UInt64
    }

    private final class Ticket {
        let slot: Int
        let commandBuffer: MTLCommandBuffer
        let production: ProductionPresentation?
        var state: TicketState = .prepared
        var commandBufferCompleted = false
        var productionScheduled = false
        var generatedPresentationEncoded = false

        init(
            slot: Int,
            commandBuffer: MTLCommandBuffer,
            production: ProductionPresentation? = nil
        ) {
            self.slot = slot
            self.commandBuffer = commandBuffer
            self.production = production
        }
    }

    /** Exact post-world presentation settings captured with a production ticket. */
    private struct ProductionPresentation {
        let frame: MetallumRendererFrameStateSnapshot
        let outputMode: Int32
        let sourceEncoding: Int32
        let materialGenerationActive: Int32
        let diagnosticPattern: Int32
        let currentHeadroom: Float
        let hdrStrength: Float
        let bloomStrength: Float
    }

    /**
     * Retains the UI and both output targets until the last GPU composite has
     * completed.  This is deliberately distinct from a ticket: tickets own
     * renderer command buffers, while this work item owns presentation-only
     * consumers that may outlive renderer completion.
     */
    private final class UiCompositeWork {
        let slot: Int
        let uiTexture: MTLTexture
        let generatedWorld: MTLTexture
        let realWorld: MTLTexture
        let generatedTarget: MTLTexture
        let realTarget: MTLTexture

        init(slot: Int, resources: Slot) {
            self.slot = slot
            self.uiTexture = resources.sdrUi
            self.generatedWorld = resources.generatedColor
            self.realWorld = resources.realColor
            self.generatedTarget = resources.generatedPresentation
            self.realTarget = resources.realPresentation
        }
    }

    let key: Key
    private let device: MTLDevice
    private let layer: CAMetalLayer
    private let state = NSCondition()
    private let schedulerWake = DispatchSemaphore(value: 0)
    private let schedulerStopped = DispatchSemaphore(value: 0)
    private let schedulerStarted = DispatchSemaphore(value: 0)
    private lazy var schedulerThread: Thread = Thread { [weak self] in
        self?.runRealOnlyScheduler()
    }

    private var renderQueue: MTLCommandQueue?
    private var presentationQueue: MTLCommandQueue?
    private var completionEvent: MTLSharedEvent?
    private var slots: [Slot] = []
    private var acceptingFrames = false
    private var shuttingDown = false
    private var schedulerExited = false
    private var pendingRealFrames = 0
    private var pendingPacingWorks = 0
    private var resetEpoch: UInt64 = 0
    private var textureAllocationCount = 0
    private var nextTicket: UInt64 = 1
    private var tickets: [UInt64: Ticket] = [:]
    private var pendingUiCompositeWork: UiCompositeWork?
    private var sharedUiCompositeEncodes = 0
    private var presentationHeadroom: Float = 1.0
    private var historyState: HistoryState = .primingFirst
    private var previousEncodedSlot: Int?
    private var primedPreviousSlot: Int?
    private var nextHistorySlot = 0
    // Production history never aliases the stage-6 validation-only history.
    // It owns the last real frame until a following real frame has finished
    // presentation, so the interpolator can never read a texture reused by a
    // still-visible frame.
    private var productionHistorySlot: Int?
    private var productionPrimingFrames = 0
    private var productionPairInFlight = false
    private var reasonCounters = ReasonCounters()
    private var nextPresentationID: UInt64 = 1
    private var lastPresentationID: UInt64 = 0
    private var pacingWork: PacingWork?
    private var pacingRecords: [PacingRecord] = []
    private var droppedGeneratedLate = 0
    private var maximumPacingHistogramBuckets = 0
    private var priorMaximumDrawableCount = 0
    // Keep potentially unavailable MetalFX objects erased at the macOS 14
    // coordinator boundary.  They are conditionally cast only inside a
    // macOS-26 availability region.
    private var temporalScaler: AnyObject?
    private var interpolator: AnyObject?

    init?(key: Key, device: MTLDevice, layer: CAMetalLayer) {
        guard key.deviceID == ObjectIdentifier(device), key.layerID == ObjectIdentifier(layer) else {
            return nil
        }
        self.key = key
        self.device = device
        self.layer = layer

        guard let renderQueue = device.makeCommandQueue(),
              let presentationQueue = device.makeCommandQueue(),
              let completionEvent = device.makeSharedEvent() else {
            return nil
        }
        renderQueue.label = "Metallum FI render queue (stage 4)"
        presentationQueue.label = "Metallum FI present queue (stage 4)"
        self.renderQueue = renderQueue
        self.presentationQueue = presentationQueue
        self.completionEvent = completionEvent

        // Active interpolation needs a generated and a real drawable while a
        // previously presented surface may still be retained by CoreAnimation.
        // The coordinator is the only owner allowed to change this setting;
        // release restores the layer's previous pool size.
        priorMaximumDrawableCount = layer.maximumDrawableCount
        layer.maximumDrawableCount = 3

        // This is intentionally an optional preflight.  The coordinator must
        // remain a real-only owner on macOS < 26, unsupported devices, and a
        // nil MetalFX factory result.  No user-facing path can activate this
        // workspace before stage 9.
        configureInterpolator()

        if key.isDrawableSized {
            guard allocateTextureRings(device: device) else {
                layer.maximumDrawableCount = priorMaximumDrawableCount
                self.renderQueue = nil
                self.presentationQueue = nil
                self.completionEvent = nil
                return nil
            }
            acceptingFrames = true
        }

        schedulerThread.name = "Metallum FI pacing scheduler"
        schedulerThread.qualityOfService = .userInitiated
        schedulerThread.start()
        // A bounded start wait makes creation failure deterministic without
        // holding the coordinator lock required by the worker.
        guard schedulerStarted.wait(timeout: .now() + .seconds(2)) == .success else {
            state.lock()
            shuttingDown = true
            state.unlock()
            schedulerWake.signal()
            _ = schedulerStopped.wait(timeout: .now() + .seconds(2))
            schedulerExited = true
            layer.maximumDrawableCount = priorMaximumDrawableCount
            return nil
        }
    }

    /**
     * Builds the fixed-Temporal Metal 3 effect pair used by the stage-6
     * workspace validation.  Factory failure is expected capability evidence,
     * so it never prevents the real-only coordinator from being created.
     */
    private func configureInterpolator() {
        guard key.isDrawableSized else { return }
        guard #available(macOS 26.0, *),
              MTLFXFrameInterpolatorDescriptor.supportsDevice(device) else {
            return
        }

        let inputWidth = key.inputWidth
        let inputHeight = key.inputHeight
        let descriptor = MTLFXFrameInterpolatorDescriptor()
        descriptor.colorTextureFormat = key.pixelFormat
        descriptor.outputTextureFormat = key.pixelFormat
        descriptor.depthTextureFormat = .depth32Float
        descriptor.motionTextureFormat = .rg16Float
        descriptor.inputWidth = inputWidth
        descriptor.inputHeight = inputHeight
        descriptor.outputWidth = key.width
        descriptor.outputHeight = key.height
        if key.usesSpatialInputs {
            // Frame Interpolation is a standalone effect for Spatial. Do not
            // cast or attach MTLFXSpatialScaler: it does not implement the
            // FrameInterpolatableScaler protocol.
            descriptor.scaler = nil
        } else {
            // Reuse the renderer's fixed Temporal workspace when it already
            // exists for this immutable descriptor key.
            guard let scaler = existingFixedTemporalScalerForFrameInterpolation(
                device: device,
                sourcePixelFormat: key.pixelFormat,
                inputWidth: inputWidth,
                inputHeight: inputHeight,
                outputWidth: key.width,
                outputHeight: key.height
            ) as? MTLFXTemporalScaler else { return }
            descriptor.scaler = scaler
            temporalScaler = scaler
        }
        guard let created = descriptor.makeFrameInterpolator(device: device) else { return }
        interpolator = created
    }

    deinit {
        // Public release drains and joins before the retained native object is
        // released.  This catches an ABI owner that bypassed that contract.
        precondition(schedulerExited, "FI coordinator released without joining its scheduler")
    }

    private func allocateTextureRings(device: MTLDevice) -> Bool {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: key.pixelFormat,
            width: key.width,
            height: key.height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        // MetalFX reports its concrete requirements after construction.  The
        // fixed ring is allocated once, with the union of producer/composite
        // and effect usage bits; no texture is created per accepted frame.
        descriptor.usage = [.shaderRead, .shaderWrite, .renderTarget]
        let generatedDescriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: key.pixelFormat,
            width: key.width,
            height: key.height,
            mipmapped: false
        )
        generatedDescriptor.storageMode = .private
        generatedDescriptor.usage = [.shaderRead, .shaderWrite, .renderTarget]
        let uiDescriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm,
            width: key.width,
            height: key.height,
            mipmapped: false
        )
        uiDescriptor.storageMode = .private
        uiDescriptor.usage = [.shaderRead, .renderTarget]
        let presentationDescriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: key.pixelFormat,
            width: key.width,
            height: key.height,
            mipmapped: false
        )
        presentationDescriptor.storageMode = .private
        presentationDescriptor.usage = [.shaderRead, .renderTarget]

        var depthDescriptor: MTLTextureDescriptor?
        var motionDescriptor: MTLTextureDescriptor?
        if #available(macOS 26.0, *), let interpolator = interpolator as? MTLFXFrameInterpolator {
            descriptor.usage.formUnion(interpolator.colorTextureUsage)
            generatedDescriptor.usage.formUnion(interpolator.outputTextureUsage)
            let inputWidth = key.inputWidth
            let inputHeight = key.inputHeight
            depthDescriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .depth32Float,
                width: inputWidth,
                height: inputHeight,
                mipmapped: false
            )
            depthDescriptor?.storageMode = .private
            depthDescriptor?.usage = MTLTextureUsage.renderTarget.union(interpolator.depthTextureUsage)
            motionDescriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .rg16Float,
                width: inputWidth,
                height: inputHeight,
                mipmapped: false
            )
            motionDescriptor?.storageMode = .private
            motionDescriptor?.usage = MTLTextureUsage.renderTarget.union(interpolator.motionTextureUsage)
        }

        var allocated: [Slot] = []
        allocated.reserveCapacity(Self.ringSize)
        for index in 0..<Self.ringSize {
            guard let realColor = device.makeTexture(descriptor: descriptor),
                  let generatedColor = device.makeTexture(descriptor: generatedDescriptor),
                  let sdrUi = device.makeTexture(descriptor: uiDescriptor),
                  let generatedPresentation = device.makeTexture(descriptor: presentationDescriptor),
                  let realPresentation = device.makeTexture(descriptor: presentationDescriptor) else {
                return false
            }
            let depth: MTLTexture?
            if let depthDescriptor {
                guard let allocatedDepth = device.makeTexture(descriptor: depthDescriptor) else { return false }
                depth = allocatedDepth
            } else {
                depth = nil
            }
            let motion: MTLTexture?
            if let motionDescriptor {
                guard let allocatedMotion = device.makeTexture(descriptor: motionDescriptor) else { return false }
                motion = allocatedMotion
            } else {
                motion = nil
            }
            realColor.label = "Metallum FI real color \(index)"
            generatedColor.label = "Metallum FI generated color \(index)"
            sdrUi.label = "Metallum FI SDR UI \(index)"
            generatedPresentation.label = "Metallum FI generated UI composite \(index)"
            realPresentation.label = "Metallum FI real UI composite \(index)"
            allocated.append(Slot(
                realColor: realColor,
                generatedColor: generatedColor,
                depth: depth,
                motion: motion,
                sdrUi: sdrUi,
                generatedPresentation: generatedPresentation,
                realPresentation: realPresentation,
                state: .free
            ))
        }
        slots = allocated
        // Each slot owns world history (2), one SDR UI texture, and separate
        // generated/real composite targets.  The targets make the UI lifetime
        // explicit and avoid sharing a drawable across the two presentations.
        textureAllocationCount = allocated.count * 5
        return true
    }

    /**
     * Native-only stage-6 encoder used by the validation harness.  It exercises
     * the exact descriptor usages and per-frame MetalFX contract without
     * changing the disabled Java production admission path.
     */
    func encodeValidationFrame(resetHistory: Bool, fieldOfView: Float) -> TicketStatus {
        state.lock()
        guard !shuttingDown, acceptingFrames else {
            state.unlock()
            return .bypassDisabled
        }
        guard #available(macOS 26.0, *),
              let interpolator = interpolator as? MTLFXFrameInterpolator,
              let queue = renderQueue else {
            reasonCounters.record(.bypassUnsupported)
            state.unlock()
            return .bypassUnsupported
        }
        guard fieldOfView.isFinite, fieldOfView > 0.0, fieldOfView < 180.0 else {
            reasonCounters.record(.bypassInputContract)
            state.unlock()
            return .bypassInputContract
        }
        if resetHistory {
            resetInterpolationHistoryLocked()
            reasonCounters.reset += 1
        }
        let current = nextHistorySlot
        nextHistorySlot = (nextHistorySlot + 1) % slots.count
        switch historyState {
        case .primingFirst:
            primedPreviousSlot = current
            historyState = .primingSecond
            reasonCounters.record(.bypassPriming)
            state.unlock()
            return .bypassPriming
        case .primingSecond:
            primedPreviousSlot = current
            historyState = .active
            reasonCounters.record(.bypassPriming)
            state.unlock()
            return .bypassPriming
        case .active:
            guard let previous = previousEncodedSlot ?? primedPreviousSlot,
                  previous != current,
                  let depth = slots[current].depth,
                  let motion = slots[current].motion else {
                reasonCounters.record(.bypassInputContract)
                state.unlock()
                return .bypassInputContract
            }
            let currentColor = slots[current].realColor
            let previousColor = slots[previous].realColor
            let output = slots[current].generatedColor
            let resetForFirstEncode = previousEncodedSlot == nil
            state.unlock()

            guard let commandBuffer = queue.makeCommandBuffer() else {
                return .transientFailure
            }
            guard clearValidationInputs(
                commandBuffer: commandBuffer,
                color: currentColor,
                depth: depth,
                motion: motion
            ) else {
                return .transientFailure
            }
            interpolator.colorTexture = currentColor
            interpolator.prevColorTexture = previousColor
            interpolator.depthTexture = depth
            interpolator.motionTexture = motion
            interpolator.outputTexture = output
            // This deterministic value is confined to the native test path.
            // The production path will use coordinator acceptance timestamps.
            interpolator.deltaTime = 1.0 / 60.0
            interpolator.nearPlane = 0.05
            interpolator.farPlane = 1_000.0
            interpolator.fieldOfView = fieldOfView
            interpolator.aspectRatio = Float(key.width) / Float(key.height)
            interpolator.jitterOffsetX = 0.0
            interpolator.jitterOffsetY = 0.0
            interpolator.motionVectorScaleX = 1.0
            interpolator.motionVectorScaleY = 1.0
            interpolator.isDepthReversed = true
            interpolator.shouldResetHistory = resetForFirstEncode
            interpolator.encode(commandBuffer: commandBuffer)
            commandBuffer.commit()
            commandBuffer.waitUntilCompleted()
            guard commandBuffer.status == .completed, commandBuffer.error == nil else {
                return .transientFailure
            }

            state.lock()
            previousEncodedSlot = current
            reasonCounters.encoded += 1
            state.unlock()
            return .prepared
        }
    }

    private func clearValidationInputs(
        commandBuffer: MTLCommandBuffer,
        color: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture
    ) -> Bool {
        let descriptor = MTLRenderPassDescriptor()
        descriptor.colorAttachments[0].texture = color
        descriptor.colorAttachments[0].loadAction = .clear
        descriptor.colorAttachments[0].storeAction = .store
        descriptor.colorAttachments[0].clearColor = MTLClearColorMake(0.25, 0.5, 0.75, 1.0)
        descriptor.colorAttachments[1].texture = motion
        descriptor.colorAttachments[1].loadAction = .clear
        descriptor.colorAttachments[1].storeAction = .store
        descriptor.colorAttachments[1].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0)
        descriptor.depthAttachment.texture = depth
        descriptor.depthAttachment.loadAction = .clear
        descriptor.depthAttachment.storeAction = .store
        descriptor.depthAttachment.clearDepth = 0.0
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            return false
        }
        encoder.endEncoding()
        return true
    }

    private func resetInterpolationHistoryLocked() {
        historyState = .primingFirst
        previousEncodedSlot = nil
        primedPreviousSlot = nil
        if let productionHistorySlot,
           slots.indices.contains(productionHistorySlot),
           slots[productionHistorySlot].state == .history {
            slots[productionHistorySlot].state = .free
        }
        productionHistorySlot = nil
        productionPrimingFrames = 0
    }

    /**
     * Reserves a reusable real-color slot.  This is intentionally internal to
     * the stage-4 synthetic test: production frames still use the old single
     * present path until a committed ticket can own this reservation.
     */
    func reserveRealFrameSlot() -> Int? {
        state.lock()
        defer { state.unlock() }
        guard acceptingFrames, !shuttingDown else {
            return nil
        }
        guard let index = slots.firstIndex(where: { $0.state == .free }) else {
            return nil
        }
        slots[index].state = .realFrameReserved
        pendingRealFrames += 1
        return index
    }

    /** Completes a synthetic real-only frame without performing a presentation. */
    func completeRealFrameSlot(_ index: Int) -> LifecycleStatus {
        state.lock()
        defer { state.unlock() }
        guard !shuttingDown else { return .released }
        guard slots.indices.contains(index), slots[index].state == .realFrameReserved else {
            return .invalidContext
        }
        slots[index].state = .free
        pendingRealFrames -= 1
        state.broadcast()
        return .ready
    }

    /**
     * Reserves a stage-5 ticket only while the renderer command buffer is
     * uncommitted.  The ticket strongly retains the command buffer until its
     * completion handler releases the ring slot after publish.
     */
    func prepare(
        commandBuffer: MTLCommandBuffer,
        rendererGeneration: UInt64,
        outTicket: UnsafeMutablePointer<UInt64>?
    ) -> TicketStatus {
        guard let outTicket else { return .bypassInputContract }
        outTicket.pointee = 0
        state.lock()
        defer { state.unlock() }
        guard !shuttingDown else { return .bypassDisabled }
        guard acceptingFrames else { return .bypassDisabled }
        guard rendererGeneration == key.rendererGeneration else { return .bypassGeneration }
        guard commandBuffer.device === device else { return .bypassInputContract }
        guard commandBuffer.status == .notEnqueued else { return .staleTicket }
        // Stage 5 has no MetalFX work yet, but its ticket reservation already
        // obeys the final in-flight bound rather than growing the ring.
        guard tickets.count < 2 else { return .bypassBackpressure }
        guard let slot = slots.firstIndex(where: { $0.state == .free }) else {
            return .bypassBackpressure
        }
        guard nextTicket != 0 else { return .fatalForGeneration }
        let ticket = nextTicket
        nextTicket &+= 1
        slots[slot].state = .realFrameReserved
        pendingRealFrames += 1
        tickets[ticket] = Ticket(slot: slot, commandBuffer: commandBuffer)
        // Metal requires completion handlers to be registered before commit.
        // The handler only records completion; publish still remains a strict
        // post-commit transition performed by Java.
        commandBuffer.addCompletedHandler { [weak self] _ in
            self?.markTicketCompleted(ticket)
        }
        outTicket.pointee = ticket
        return .prepared
    }

    /**
     * Stage-9 production hand-off.  It builds the finished, UI-free world
     * image and copies depth, motion and SDR UI into the coordinator's fixed
     * ring while the renderer command buffer is still open.  The only
     * presentation owner after a successful return is this coordinator.
     */
    func prepareProduction(
        commandBuffer: MTLCommandBuffer,
        rendererGeneration: UInt64,
        sourceTexture: MTLTexture,
        sceneTexture: MTLTexture?,
        sceneDepthTexture: MTLTexture?,
        semanticTexture: MTLTexture?,
        uiTexture: MTLTexture?,
        globalFence: MTLFence?,
        spatialHdrPrecomposed: Int32,
        outputMode: Int32,
        sourceEncoding: Int32,
        materialGenerationActive: Int32,
        diagnosticPattern: Int32,
        currentHeadroom: Float,
        hdrStrength: Float,
        bloomStrength: Float,
        outTicket: UnsafeMutablePointer<UInt64>?
    ) -> TicketStatus {
        guard let outTicket else { return .bypassInputContract }
        outTicket.pointee = 0
        let temporalInputs = metallumCurrentFrameInterpolationInputs()
        guard (0...2).contains(outputMode),
              (0...2).contains(sourceEncoding),
              (0...1).contains(materialGenerationActive),
              currentHeadroom.isFinite,
              hdrStrength.isFinite,
              bloomStrength.isFinite,
              commandBuffer.device === device,
              sourceTexture.device === device,
              sourceTexture.textureType == .type2D,
              sourceTexture.sampleCount == 1,
              sourceTexture.usage.contains(.shaderRead),
              let uiTexture,
              uiTexture.device === device,
              uiTexture.pixelFormat == .rgba8Unorm,
              uiTexture.textureType == .type2D,
              uiTexture.sampleCount == 1,
              uiTexture.width == key.width,
              uiTexture.height == key.height,
              let inputDepth = temporalInputs.depth,
              let inputMotion = temporalInputs.motion,
              inputDepth.device === device,
              inputMotion.device === device,
              inputDepth.pixelFormat == .depth32Float,
              inputMotion.pixelFormat == .rg16Float,
              inputDepth.width == key.inputWidth,
              inputDepth.height == key.inputHeight,
              inputMotion.width == key.inputWidth,
              inputMotion.height == key.inputHeight,
              let frame = temporalInputs.frame else {
            return uiTexture == nil ? .bypassNoUi : .bypassInputContract
        }
        let refresh = temporalInputs.displayMaximumFramesPerSecond
        guard refresh >= 60 else { return .bypassUnsupported }
        let expectedRealFramesPerSecond = Double(refresh) / 2.0
        let measuredRealFramesPerSecond = frame.deltaSeconds > 0 && frame.deltaSeconds.isFinite
            ? 1.0 / Double(frame.deltaSeconds)
            : 0.0
        let minimumRealFramesPerSecond = max(30.0, expectedRealFramesPerSecond * 0.85)
        guard measuredRealFramesPerSecond >= minimumRealFramesPerSecond,
              measuredRealFramesPerSecond <= expectedRealFramesPerSecond * 1.05 else {
            return .bypassCadence
        }
        // A production ticket is only valid for the immutable upstream profile
        // selected at workspace creation. Dynamic Temporal and Native paths
        // stay fail-open until their later dedicated stages.
        let temporalBit: UInt64 = 1 << 1
        let interpolationBit: UInt64 = 1 << 2
        let spatialBit: UInt64 = 1
        guard rendererGeneration == key.rendererGeneration,
              frame.rendererGenerationId == key.rendererGeneration,
              (key.usesSpatialInputs
                    ? frame.featureMask & spatialBit != 0 && frame.featureMask & temporalBit == 0
                    : frame.featureMask & temporalBit != 0 && frame.featureMask & spatialBit == 0),
              frame.featureMask & interpolationBit != 0,
              frame.interpolationResourceBytes > 0,
              Int(frame.renderWidth) == key.inputWidth,
              Int(frame.renderHeight) == key.inputHeight,
              Int(frame.displayWidth) == key.width,
              Int(frame.displayHeight) == key.height,
              commandBuffer.status == .notEnqueued else {
            return rendererGeneration == key.rendererGeneration
                ? .bypassGeneration : .staleTicket
        }

        let ticket: UInt64
        let slot: Int
        let presentation = ProductionPresentation(
            frame: frame,
            outputMode: outputMode,
            sourceEncoding: sourceEncoding,
            materialGenerationActive: materialGenerationActive,
            diagnosticPattern: diagnosticPattern,
            currentHeadroom: currentHeadroom,
            hdrStrength: hdrStrength,
            bloomStrength: bloomStrength
        )
        state.lock()
        guard !shuttingDown, acceptingFrames else {
            state.unlock()
            return .bypassDisabled
        }
        guard tickets.count < 2,
              let reservedSlot = slots.firstIndex(where: { $0.state == .free }),
              nextTicket != 0 else {
            MetallumFrameInterpolationTelemetry.shared.recordBackpressureDrop()
            state.unlock()
            return .bypassBackpressure
        }
        ticket = nextTicket
        nextTicket &+= 1
        slot = reservedSlot
        slots[slot].state = .realFrameReserved
        pendingRealFrames += 1
        tickets[ticket] = Ticket(
            slot: slot,
            commandBuffer: commandBuffer,
            production: presentation
        )
        state.unlock()

        // `metallum_encodePresentationWorld` sees the current Temporal output
        // recorded on this exact renderer command buffer.  It performs world
        // tone mapping/reconstruction before UI, preserving the required
        // world-only interpolation input.
        guard metallum_encodePresentationWorld(
            commandBuffer,
            slots[slot].realColor,
            sourceTexture,
            sceneTexture,
            sceneDepthTexture,
            semanticTexture,
            globalFence,
            spatialHdrPrecomposed,
            outputMode,
            sourceEncoding,
            materialGenerationActive,
            diagnosticPattern,
            currentHeadroom,
            hdrStrength,
            bloomStrength
        ) == 1,
        let slotDepth = slots[slot].depth,
        let slotMotion = slots[slot].motion,
        let blit = commandBuffer.makeBlitCommandEncoder() else {
            discardPreparedProductionTicket(ticket)
            return .transientFailure
        }
        if let globalFence {
            blit.waitForFence(globalFence)
        }
        blit.copy(
            from: inputDepth,
            sourceSlice: 0,
            sourceLevel: 0,
            sourceOrigin: .init(),
            sourceSize: MTLSize(width: key.inputWidth, height: key.inputHeight, depth: 1),
            to: slotDepth,
            destinationSlice: 0,
            destinationLevel: 0,
            destinationOrigin: .init()
        )
        blit.copy(
            from: inputMotion,
            sourceSlice: 0,
            sourceLevel: 0,
            sourceOrigin: .init(),
            sourceSize: MTLSize(width: key.inputWidth, height: key.inputHeight, depth: 1),
            to: slotMotion,
            destinationSlice: 0,
            destinationLevel: 0,
            destinationOrigin: .init()
        )
        blit.copy(
            from: uiTexture,
            sourceSlice: 0,
            sourceLevel: 0,
            sourceOrigin: .init(),
            sourceSize: MTLSize(width: key.width, height: key.height, depth: 1),
            to: slots[slot].sdrUi,
            destinationSlice: 0,
            destinationLevel: 0,
            destinationOrigin: .init()
        )
        if let globalFence {
            blit.updateFence(globalFence)
        }
        blit.endEncoding()
        commandBuffer.addCompletedHandler { [weak self] _ in
            self?.markTicketCompleted(ticket)
        }
        outTicket.pointee = ticket
        return .prepared
    }

    private func discardPreparedProductionTicket(_ ticket: UInt64) {
        state.lock()
        defer { state.unlock() }
        guard let pending = tickets.removeValue(forKey: ticket) else { return }
        releaseSlotLocked(pending.slot)
    }

    /** Publishes only an already-committed renderer command buffer. */
    func publishCommitted(ticket: UInt64) -> TicketStatus {
        state.lock()
        guard let pending = tickets[ticket], pending.state == .prepared else {
            state.unlock()
            return .staleTicket
        }
        guard pending.commandBuffer.status != .notEnqueued else {
            state.unlock()
            return .staleTicket
        }
        pending.state = .published
        let productionReady = pending.production != nil && pending.commandBufferCompleted
        if pending.commandBufferCompleted && pending.production == nil {
            tickets.removeValue(forKey: ticket)
            releaseSlotLocked(pending.slot)
        }
        state.unlock()
        if productionReady {
            scheduleProductionTicket(ticket)
        }
        return .prepared
    }

    func cancel(ticket: UInt64) -> TicketStatus {
        state.lock()
        guard let pending = tickets[ticket], pending.state == .prepared else {
            state.unlock()
            return .staleTicket
        }
        guard pending.commandBuffer.status == .notEnqueued else {
            state.unlock()
            return .staleTicket
        }
        tickets.removeValue(forKey: ticket)
        releaseSlotLocked(pending.slot)
        state.unlock()
        return .prepared
    }

    func reset(after timeoutNanoseconds: UInt64) -> LifecycleStatus {
        let drainStatus = drain(timeoutNanoseconds: timeoutNanoseconds)
        guard drainStatus == .ready || drainStatus == .suspendedZeroSize else {
            return drainStatus
        }
        state.lock()
        defer { state.unlock() }
        guard !shuttingDown else { return .released }
        // The old single-present path is still responsible for the actual
        // frame.  Reset only invalidates coordinator-owned history/resources.
        resetEpoch &+= 1
        resetInterpolationHistoryLocked()
        acceptingFrames = key.isDrawableSized
        return key.isDrawableSized ? .ready : .suspendedZeroSize
    }

    func drain(timeoutNanoseconds: UInt64) -> LifecycleStatus {
        state.lock()
        acceptingFrames = false
        guard !shuttingDown else {
            state.unlock()
            return .released
        }
        guard pendingRealFrames > 0 || pendingPacingWorks > 0 else {
            state.unlock()
            return key.isDrawableSized ? .ready : .suspendedZeroSize
        }

        let timeoutSeconds = Double(timeoutNanoseconds) / 1_000_000_000.0
        let deadline = Date(timeIntervalSinceNow: max(timeoutSeconds, 0.0))
        while pendingRealFrames > 0 || pendingPacingWorks > 0 {
            if !state.wait(until: deadline) {
                state.unlock()
                return .drainTimedOut
            }
        }
        state.unlock()
        return key.isDrawableSized ? .ready : .suspendedZeroSize
    }

    func release(after timeoutNanoseconds: UInt64) -> LifecycleStatus {
        let drainStatus = drain(timeoutNanoseconds: timeoutNanoseconds)
        guard drainStatus == .ready || drainStatus == .suspendedZeroSize else {
            return drainStatus
        }

        state.lock()
        if shuttingDown {
            state.unlock()
            return .released
        }
        shuttingDown = true
        acceptingFrames = false
        state.unlock()
        schedulerWake.signal()

        guard schedulerStopped.wait(timeout: .now() + .seconds(2)) == .success else {
            // Do not retire native resources while their owner thread could
            // still observe them.  A later release may retry the bounded join.
            return .drainTimedOut
        }

        state.lock()
        schedulerExited = true
        slots.removeAll(keepingCapacity: false)
        textureAllocationCount = 0
        completionEvent = nil
        presentationQueue = nil
        renderQueue = nil
        layer.maximumDrawableCount = priorMaximumDrawableCount
        state.unlock()
        return .ready
    }

    private func markTicketCompleted(_ ticket: UInt64) {
        state.lock()
        guard let pending = tickets[ticket] else {
            state.unlock()
            return
        }
        pending.commandBufferCompleted = true
        guard pending.state == .published else {
            state.unlock()
            return
        }
        if pending.production != nil {
            state.unlock()
            scheduleProductionTicket(ticket)
            return
        }
        tickets.removeValue(forKey: ticket)
        releaseSlotLocked(pending.slot)
        state.unlock()
    }

    /** Starts the next completed production ticket in strict real-frame order. */
    private func scheduleProductionTicket(_ ticket: UInt64) {
        state.lock()
        guard !shuttingDown,
              !productionPairInFlight,
              let pending = tickets[ticket],
              let production = pending.production,
              pending.state == .published,
              pending.commandBufferCompleted,
              !pending.productionScheduled else {
            state.unlock()
            return
        }
        pending.productionScheduled = true
        productionPairInFlight = true
        if production.frame.resetMask != 0 {
            resetInterpolationHistoryLocked()
            reasonCounters.reset += 1
        }
        let priorSlot = productionHistorySlot
        let canInterpolate = production.frame.resetMask == 0
            && productionPrimingFrames >= 2
            && priorSlot != nil
            && priorSlot != pending.slot
            && production.frame.deltaSeconds.isFinite
            && production.frame.deltaSeconds >= (1.0 / 240.0)
            && production.frame.deltaSeconds <= (1.0 / 30.0)
        state.unlock()

        guard canInterpolate, let priorSlot else {
            presentProductionReal(ticket)
            return
        }
        if encodeProductionInterpolation(ticket: ticket, previousSlot: priorSlot) {
            MetallumFrameInterpolationTelemetry.shared.recordAcceptedPair()
        } else {
            // Interpolation failure must never suppress the real member.
            presentProductionReal(ticket)
        }
    }

    private func encodeProductionInterpolation(ticket: UInt64, previousSlot: Int) -> Bool {
        guard #available(macOS 26.0, *) else {
            return false
        }
        state.lock()
        guard !shuttingDown,
              let pending = tickets[ticket],
              let production = pending.production,
              slots.indices.contains(previousSlot),
              slots[previousSlot].state == .history,
              let interpolator = interpolator as? MTLFXFrameInterpolator,
              let queue = renderQueue,
              let depth = slots[pending.slot].depth,
              let motion = slots[pending.slot].motion else {
            state.unlock()
            return false
        }
        let currentSlot = pending.slot
        let frame = production.frame
        let verticalProjectionScale = abs(frame.currentUnjitteredProjection.columns.1.y)
        guard verticalProjectionScale.isFinite, verticalProjectionScale > 0.0001 else {
            state.unlock()
            return false
        }
        let fieldOfView = Float(2.0 * atan(1.0 / Double(verticalProjectionScale)) * 180.0 / Double.pi)
        guard fieldOfView.isFinite, fieldOfView > 1.0, fieldOfView < 179.0,
              let commandBuffer = queue.makeCommandBuffer() else {
            state.unlock()
            return false
        }
        pending.generatedPresentationEncoded = true
        let deltaSeconds = frame.deltaSeconds
        state.unlock()

        interpolator.colorTexture = slots[currentSlot].realColor
        interpolator.prevColorTexture = slots[previousSlot].realColor
        interpolator.depthTexture = depth
        interpolator.motionTexture = motion
        interpolator.outputTexture = slots[currentSlot].generatedColor
        interpolator.deltaTime = deltaSeconds
        interpolator.nearPlane = max(frame.nearPlane, 0.0001)
        interpolator.farPlane = max(frame.farPlane, interpolator.nearPlane + 0.001)
        interpolator.fieldOfView = fieldOfView
        interpolator.aspectRatio = Float(key.width) / Float(key.height)
        // Spatial resolve is not jittered. Do not leak a stale Temporal Halton
        // offset into a standalone FI profile.
        interpolator.jitterOffsetX = key.usesSpatialInputs ? 0.0 : frame.jitterX
        interpolator.jitterOffsetY = key.usesSpatialInputs ? 0.0 : frame.jitterY
        if key.usesSpatialInputs {
            // Motion is generated in render pixels while the colors are
            // display-sized after Spatial resolve. Preserve non-uniform X/Y
            // scaling; a single scalar breaks asymmetric extents.
            let scale = Self.spatialMotionScale(
                displayWidth: key.width, displayHeight: key.height,
                renderWidth: key.inputWidth, renderHeight: key.inputHeight
            )
            interpolator.motionVectorScaleX = scale.x
            interpolator.motionVectorScaleY = scale.y
        } else {
            interpolator.motionVectorScaleX = 1.0
            interpolator.motionVectorScaleY = 1.0
        }
        interpolator.isDepthReversed = true
        interpolator.shouldResetHistory = false
        interpolator.encode(commandBuffer: commandBuffer)
        commandBuffer.addCompletedHandler { [weak self] buffer in
            guard buffer.status == .completed, buffer.error == nil else {
                self?.presentProductionReal(ticket)
                return
            }
            self?.presentProductionGeneratedThenReal(ticket)
        }
        commandBuffer.commit()
        return true
    }

    static func spatialMotionScale(
        displayWidth: Int,
        displayHeight: Int,
        renderWidth: Int,
        renderHeight: Int
    ) -> SIMD2<Float> {
        precondition(displayWidth > 0 && displayHeight > 0 && renderWidth > 0 && renderHeight > 0)
        return SIMD2(
            Float(displayWidth) / Float(renderWidth),
            Float(displayHeight) / Float(renderHeight)
        )
    }

    func hasStandaloneSpatialInterpolatorForTests() -> Bool {
        key.usesSpatialInputs && temporalScaler == nil && interpolator != nil
    }

    private func presentProductionGeneratedThenReal(_ ticket: UInt64) {
        presentProductionWorld(ticket: ticket, generated: true) { [weak self] in
            guard let self else { return }
            self.state.lock()
            let delta = self.tickets[ticket]?.production?.frame.deltaSeconds ?? (1.0 / 60.0)
            let shouldContinue = !self.shuttingDown && self.tickets[ticket] != nil
            self.state.unlock()
            guard shouldContinue else {
                self.finishProductionTicket(ticket)
                return
            }
            // Generated N-1/2 is presented first; the real N member follows
            // after half the measured real-frame interval.  This uses a
            // dispatch deadline rather than a CPU busy wait/readback.
            let bounded = min(max(Double(delta), 1.0 / 240.0), 1.0 / 30.0)
            DispatchQueue.global(qos: .userInteractive).asyncAfter(
                deadline: .now() + .nanoseconds(Int(bounded * 500_000_000.0))
            ) { [weak self] in
                self?.presentProductionReal(ticket)
            }
        }
    }

    private func presentProductionReal(_ ticket: UInt64) {
        presentProductionWorld(ticket: ticket, generated: false) { [weak self] in
            self?.finishProductionTicket(ticket)
        }
    }

    /** Acquires a fresh drawable only when a production world frame is ready. */
    private func presentProductionWorld(
        ticket: UInt64,
        generated: Bool,
        completion: @escaping () -> Void
    ) {
        state.lock()
        guard !shuttingDown,
              let pending = tickets[ticket],
              let production = pending.production,
              let queue = presentationQueue,
              slots.indices.contains(pending.slot) else {
            state.unlock()
            completion()
            return
        }
        let slot = pending.slot
        let world = generated ? slots[slot].generatedColor : slots[slot].realColor
        let ui = slots[slot].sdrUi
        state.unlock()

        guard let commandBuffer = queue.makeCommandBuffer(),
              let drawable = layer.nextDrawable(),
              metallum_encodeUIComposite(
                commandBuffer,
                drawable.texture,
                world,
                ui,
                nil,
                production.outputMode,
                production.sourceEncoding,
                production.currentHeadroom,
                production.hdrStrength,
                production.bloomStrength,
                production.diagnosticPattern
              ) == 1 else {
            if generated {
                MetallumFrameInterpolationTelemetry.shared.recordLateGeneratedDrop()
            }
            completion()
            return
        }
        commandBuffer.present(drawable)
        commandBuffer.addCompletedHandler { buffer in
            if buffer.status == .completed, buffer.error == nil {
                if generated {
                    MetallumFrameInterpolationTelemetry.shared.recordGeneratedPresentation()
                } else {
                    MetallumFrameInterpolationTelemetry.shared.recordRealPresentation()
                }
            } else if generated {
                MetallumFrameInterpolationTelemetry.shared.recordLateGeneratedDrop()
            }
            completion()
        }
        commandBuffer.commit()
    }

    private func finishProductionTicket(_ ticket: UInt64) {
        var nextTicket: UInt64?
        state.lock()
        guard let pending = tickets.removeValue(forKey: ticket) else {
            productionPairInFlight = false
            state.broadcast()
            state.unlock()
            return
        }
        let currentSlot = pending.slot
        if let priorSlot = productionHistorySlot,
           priorSlot != currentSlot,
           slots.indices.contains(priorSlot),
           slots[priorSlot].state == .history {
            slots[priorSlot].state = .free
        }
        if slots.indices.contains(currentSlot) {
            slots[currentSlot].state = .history
            productionHistorySlot = currentSlot
        }
        if pending.generatedPresentationEncoded == false {
            productionPrimingFrames = min(productionPrimingFrames + 1, 2)
        }
        pendingRealFrames -= 1
        productionPairInFlight = false
        nextTicket = tickets
            .filter { _, candidate in
                candidate.production != nil
                    && candidate.state == .published
                    && candidate.commandBufferCompleted
                    && !candidate.productionScheduled
            }
            .map(\.key)
            .min()
        state.broadcast()
        state.unlock()
        if let nextTicket {
            scheduleProductionTicket(nextTicket)
        }
    }

    private func releaseSlotLocked(_ index: Int) {
        guard slots.indices.contains(index), slots[index].state == .realFrameReserved else {
            assertionFailure("FI ticket attempted to release an invalid slot")
            return
        }
        slots[index].state = .free
        pendingRealFrames -= 1
        state.broadcast()
    }

    func nativeTextureAllocationCount() -> Int {
        state.lock()
        defer { state.unlock() }
        return textureAllocationCount
    }

    func nativeResetEpoch() -> UInt64 {
        state.lock()
        defer { state.unlock() }
        return resetEpoch
    }

    func nativeStage6Counter(_ kind: Int) -> Int {
        state.lock()
        defer { state.unlock() }
        switch kind {
        case 0: return reasonCounters.priming
        case 1: return reasonCounters.unsupported
        case 2: return reasonCounters.inputContract
        case 3: return reasonCounters.reset
        case 4: return reasonCounters.encoded
        default: return 0
        }
    }

    /**
     * Stage-8 native validation path.  It encodes the exact same SDR-UI
     * composite twice: once for generated world color and once for real world
     * color.  The work item holds the UI until the completion handler releases
     * both outputs, rather than releasing it at renderer-command completion.
     *
     * This does not acquire a drawable.  Production drawable ownership and
     * admission remain deliberately deferred to stage 9.
     */
    func encodeSharedUiCompositeValidation(
        outputMode: Int32,
        currentHeadroom: Float
    ) -> TicketStatus {
        guard (0...2).contains(outputMode),
              currentHeadroom.isFinite,
              currentHeadroom >= 1.0,
              currentHeadroom <= 8.0 else {
            return .bypassInputContract
        }

        state.lock()
        guard acceptingFrames, !shuttingDown,
              pendingUiCompositeWork == nil,
              let queue = presentationQueue,
              let slot = slots.firstIndex(where: { $0.state == .free }) else {
            state.unlock()
            return .bypassBackpressure
        }
        slots[slot].state = .realFrameReserved
        pendingRealFrames += 1
        let work = UiCompositeWork(slot: slot, resources: slots[slot])
        pendingUiCompositeWork = work
        state.unlock()

        guard let commandBuffer = queue.makeCommandBuffer(),
              clearUiCompositeInputs(commandBuffer: commandBuffer, work: work),
              metallum_encodeUIComposite(
                commandBuffer,
                work.generatedTarget,
                work.generatedWorld,
                work.uiTexture,
                nil,
                outputMode,
                2,
                currentHeadroom,
                1.0,
                0.22,
                0
              ) == 1,
              metallum_encodeUIComposite(
                commandBuffer,
                work.realTarget,
                work.realWorld,
                work.uiTexture,
                nil,
                outputMode,
                2,
                currentHeadroom,
                1.0,
                0.22,
                0
              ) == 1 else {
            discardUiCompositeWork(work)
            return .transientFailure
        }
        commandBuffer.addCompletedHandler { [weak self, work] _ in
            self?.completeUiCompositeWork(work)
        }
        commandBuffer.commit()
        return .prepared
    }

    /**
     * EDR headroom is a present uniform, not a history-compatible value.  A
     * change therefore drains current presentation work and resets history
     * before the next pair can be admitted.  Pixel-format changes still need
     * a new immutable coordinator key (and thus a full recreate).
     */
    func updatePresentationHeadroom(
        _ newHeadroom: Float,
        timeoutNanoseconds: UInt64
    ) -> LifecycleStatus {
        guard newHeadroom.isFinite, newHeadroom >= 1.0, newHeadroom <= 8.0 else {
            return .invalidContext
        }
        state.lock()
        let changed = abs(presentationHeadroom - newHeadroom) > 0.0001
        state.unlock()
        guard changed else {
            return key.isDrawableSized ? .ready : .suspendedZeroSize
        }
        let resetStatus = reset(after: timeoutNanoseconds)
        guard resetStatus == .ready || resetStatus == .suspendedZeroSize else {
            return resetStatus
        }
        state.lock()
        presentationHeadroom = newHeadroom
        reasonCounters.reset += 1
        state.unlock()
        return resetStatus
    }

    func nativeStage8Counter(_ kind: Int) -> Int {
        state.lock()
        defer { state.unlock() }
        switch kind {
        case 0: return sharedUiCompositeEncodes
        case 1: return pendingUiCompositeWork == nil ? 0 : 1
        case 2: return reasonCounters.reset
        default: return 0
        }
    }

    private func clearUiCompositeInputs(
        commandBuffer: MTLCommandBuffer,
        work: UiCompositeWork
    ) -> Bool {
        let clearTargets: [(MTLTexture, MTLClearColor)] = [
            (work.generatedWorld, MTLClearColorMake(0.20, 0.45, 1.25, 1.0)),
            (work.realWorld, MTLClearColorMake(0.30, 0.55, 1.50, 1.0)),
            // Transparent UI proves that both paths preserve world color;
            // live proof covers text and overlays once admission is enabled.
            (work.uiTexture, MTLClearColorMake(0.0, 0.0, 0.0, 0.0))
        ]
        for (texture, color) in clearTargets {
            let descriptor = MTLRenderPassDescriptor()
            descriptor.colorAttachments[0].texture = texture
            descriptor.colorAttachments[0].loadAction = .clear
            descriptor.colorAttachments[0].storeAction = .store
            descriptor.colorAttachments[0].clearColor = color
            guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
                return false
            }
            encoder.endEncoding()
        }
        return true
    }

    private func completeUiCompositeWork(_ work: UiCompositeWork) {
        state.lock()
        defer { state.unlock() }
        guard pendingUiCompositeWork === work else { return }
        pendingUiCompositeWork = nil
        sharedUiCompositeEncodes += 2
        releaseSlotLocked(work.slot)
    }

    private func discardUiCompositeWork(_ work: UiCompositeWork) {
        state.lock()
        defer { state.unlock() }
        guard pendingUiCompositeWork === work else { return }
        pendingUiCompositeWork = nil
        releaseSlotLocked(work.slot)
    }

    /**
     * Stage-7-only scheduler input.  It is deliberately internal: no Java
     * render path can call it until the Stage-8 drawable/UI composite is ready.
     * The caller supplies absolute monotonic deadlines for generated N-1/2 and
     * real N.  There can be at most one queued pair, which bounds latency and
     * ensures a late generated frame never holds a following real frame.
     */
    func enqueueSyntheticPresentationPair(
        generatedDeadlineNanoseconds: UInt64,
        realDeadlineNanoseconds: UInt64
    ) -> TicketStatus {
        guard generatedDeadlineNanoseconds < realDeadlineNanoseconds else {
            return .bypassInputContract
        }
        state.lock()
        guard !shuttingDown, acceptingFrames else {
            state.unlock()
            return .bypassDisabled
        }
        guard pacingWork == nil, pendingPacingWorks == 0 else {
            MetallumFrameInterpolationTelemetry.shared.recordBackpressureDrop()
            state.unlock()
            return .bypassBackpressure
        }
        guard nextPresentationID <= UInt64.max - 1 else {
            state.unlock()
            return .fatalForGeneration
        }
        let work = PacingWork(
            epoch: resetEpoch,
            generatedPresentationID: nextPresentationID,
            realPresentationID: nextPresentationID + 1,
            generatedDeadlineNanoseconds: generatedDeadlineNanoseconds,
            realDeadlineNanoseconds: realDeadlineNanoseconds
        )
        nextPresentationID += 2
        pacingWork = work
        pendingPacingWorks = 1
        MetallumFrameInterpolationTelemetry.shared.recordAcceptedPair()
        state.unlock()
        schedulerWake.signal()
        return .prepared
    }

    func nativeStage7Counter(_ kind: Int) -> Int {
        state.lock()
        defer { state.unlock() }
        switch kind {
        case 0: return pacingRecords.count
        case 1: return droppedGeneratedLate
        case 2: return maximumPacingHistogramBuckets
        case 3: return lastPresentationID > Int.max ? Int.max : Int(lastPresentationID)
        default: return 0
        }
    }

    func nativeStage7InvariantsHold() -> Bool {
        state.lock()
        defer { state.unlock() }
        var priorRecordID: UInt64?
        var priorVisibleID: UInt64 = 0
        var expectingRealAfter: UInt64?
        for record in pacingRecords {
            if let priorRecordID, record.presentationID != priorRecordID + 1 {
                return false
            }
            priorRecordID = record.presentationID
            if let expected = expectingRealAfter {
                guard record.kind == .real, record.presentationID == expected else { return false }
                expectingRealAfter = nil
            } else {
                guard record.kind == .generated || record.kind == .droppedGeneratedLate else { return false }
                expectingRealAfter = record.presentationID + 1
            }
            if record.kind != .droppedGeneratedLate {
                guard record.presentationID > priorVisibleID else { return false }
                priorVisibleID = record.presentationID
            }
        }
        return expectingRealAfter == nil && priorVisibleID == lastPresentationID
    }

    private func recordPacingLocked(
        _ kind: PacingRecordKind,
        presentationID: UInt64,
        timestampNanoseconds: UInt64
    ) {
        // A dropped generated frame is an accounting event rather than a
        // presentation, so it must not advance the visible ID sequence.
        if kind != .droppedGeneratedLate {
            precondition(presentationID > lastPresentationID,
                         "Frame-interpolation presentation IDs must be strictly increasing")
            lastPresentationID = presentationID
            // The pacing worker publishes a monotonically increasing shared
            // event value.  Stage 8 will move this signal behind the actual
            // drawable composite command buffer; keeping the event contract
            // here prevents a second unsynchronised presenter from appearing.
            completionEvent?.signaledValue = presentationID
            if kind == .generated {
                MetallumFrameInterpolationTelemetry.shared.recordGeneratedPresentation()
            } else {
                MetallumFrameInterpolationTelemetry.shared.recordRealPresentation()
            }
        } else {
            MetallumFrameInterpolationTelemetry.shared.recordLateGeneratedDrop()
        }
        pacingRecords.append(PacingRecord(
            presentationID: presentationID,
            kind: kind,
            timestampNanoseconds: timestampNanoseconds
        ))
        if pacingRecords.count > 128 {
            pacingRecords.removeFirst(pacingRecords.count - 128)
        }
        updatePacingHistogramLocked()
    }

    private func updatePacingHistogramLocked() {
        var buckets = Set<UInt64>()
        var previous: UInt64?
        for record in pacingRecords where record.kind != .droppedGeneratedLate {
            if let previous, record.timestampNanoseconds > previous {
                // 0.5 ms quantization makes this a cadence diagnostic rather
                // than a measurement of scheduler wake-up jitter.
                let interval = record.timestampNanoseconds - previous
                buckets.insert((interval + 250_000) / 500_000)
            }
            previous = record.timestampNanoseconds
        }
        maximumPacingHistogramBuckets = max(maximumPacingHistogramBuckets, buckets.count)
        MetallumFrameInterpolationTelemetry.shared.recordHistogramBuckets(buckets.count)
    }

    private func waitForPacingDeadline(_ deadlineNanoseconds: UInt64, epoch: UInt64) -> Bool {
        while true {
            let now = DispatchTime.now().uptimeNanoseconds
            if now >= deadlineNanoseconds {
                return true
            }
            let remaining = deadlineNanoseconds - now
            let result = schedulerWake.wait(timeout: .now() + .nanoseconds(Int(min(remaining, UInt64(Int.max)))))
            if result == .success {
                state.lock()
                let shouldStop = shuttingDown || resetEpoch != epoch || pacingWork == nil
                state.unlock()
                if shouldStop {
                    return false
                }
            }
        }
    }

    private func finishPacingWorkLocked() {
        pacingWork = nil
        pendingPacingWorks = 0
        state.broadcast()
    }

    static func currentToPreviousMotion(
        currentPixel: SIMD2<Float>,
        previousPixel: SIMD2<Float>
    ) -> SIMD2<Float> {
        previousPixel - currentPixel
    }

    private func runRealOnlyScheduler() {
        schedulerStarted.signal()
        while true {
            schedulerWake.wait()
            state.lock()
            let shouldStop = shuttingDown
            let work = pacingWork
            state.unlock()
            if shouldStop {
                break
            }
            guard let work else { continue }

            let generatedOnTime = waitForPacingDeadline(
                work.generatedDeadlineNanoseconds,
                epoch: work.epoch
            )
            state.lock()
            guard !shuttingDown, resetEpoch == work.epoch, pacingWork?.generatedPresentationID
                    == work.generatedPresentationID else {
                if pacingWork != nil { finishPacingWorkLocked() }
                state.unlock()
                continue
            }
            let generatedLate = !generatedOnTime
                || DispatchTime.now().uptimeNanoseconds
                    > work.generatedDeadlineNanoseconds + Self.generatedLatenessToleranceNanoseconds
            if generatedLate {
                droppedGeneratedLate += 1
                recordPacingLocked(
                    .droppedGeneratedLate,
                    presentationID: work.generatedPresentationID,
                    timestampNanoseconds: DispatchTime.now().uptimeNanoseconds
                )
            } else {
                recordPacingLocked(
                    .generated,
                    presentationID: work.generatedPresentationID,
                    timestampNanoseconds: DispatchTime.now().uptimeNanoseconds
                )
            }
            state.unlock()

            guard waitForPacingDeadline(work.realDeadlineNanoseconds, epoch: work.epoch) else {
                state.lock()
                if pacingWork != nil { finishPacingWorkLocked() }
                state.unlock()
                continue
            }
            state.lock()
            guard !shuttingDown, resetEpoch == work.epoch, pacingWork?.realPresentationID
                    == work.realPresentationID else {
                if pacingWork != nil { finishPacingWorkLocked() }
                state.unlock()
                continue
            }
            // A real frame is never dropped here.  It is the fail-open member
            // of every accepted pair, even after a late generated frame.
            recordPacingLocked(
                .real,
                presentationID: work.realPresentationID,
                timestampNanoseconds: DispatchTime.now().uptimeNanoseconds
            )
            finishPacingWorkLocked()
            state.unlock()
        }
        schedulerStopped.signal()
    }
}

private func coordinatorFromRawPointer(
    _ rawContext: UnsafeMutableRawPointer?
) -> MetallumFrameInterpolationCoordinator? {
    guard let rawContext else { return nil }
    return Unmanaged<MetallumFrameInterpolationCoordinator>
        .fromOpaque(rawContext)
        .takeUnretainedValue()
}

@_cdecl("metallum_frame_interpolation_coordinator_create_stage4")
public func metallum_frame_interpolation_coordinator_create_stage4(
    _ device: MTLDevice,
    _ layer: CAMetalLayer,
    _ width: Int32,
    _ height: Int32,
    _ pixelFormatRaw: UInt64,
    _ rendererGeneration: UInt64
) -> UnsafeMutableRawPointer? {
    autoreleasepool {
        guard width >= 0,
              height >= 0,
              let pixelFormat = MTLPixelFormat(rawValue: UInt(pixelFormatRaw)),
              let key = MetallumFrameInterpolationCoordinator.Key(
                  device: device,
                  layer: layer,
                  width: Int(width),
                  height: Int(height),
                  pixelFormat: pixelFormat,
                  rendererGeneration: rendererGeneration
              ),
              let coordinator = MetallumFrameInterpolationCoordinator(key: key, device: device, layer: layer) else {
            return nil
        }
        return Unmanaged.passRetained(coordinator).toOpaque()
    }
}

@_cdecl("metallum_frame_interpolation_coordinator_reset_stage4")
public func metallum_frame_interpolation_coordinator_reset_stage4(
    _ rawContext: UnsafeMutableRawPointer?,
    _ timeoutNanoseconds: UInt64
) -> Int32 {
    coordinatorFromRawPointer(rawContext)?.reset(after: timeoutNanoseconds).rawValue
        ?? MetallumFrameInterpolationCoordinator.LifecycleStatus.invalidContext.rawValue
}

@_cdecl("metallum_frame_interpolation_coordinator_drain_stage4")
public func metallum_frame_interpolation_coordinator_drain_stage4(
    _ rawContext: UnsafeMutableRawPointer?,
    _ timeoutNanoseconds: UInt64
) -> Int32 {
    coordinatorFromRawPointer(rawContext)?.drain(timeoutNanoseconds: timeoutNanoseconds).rawValue
        ?? MetallumFrameInterpolationCoordinator.LifecycleStatus.invalidContext.rawValue
}

@_cdecl("metallum_frame_interpolation_coordinator_release_stage4")
public func metallum_frame_interpolation_coordinator_release_stage4(
    _ rawContext: UnsafeMutableRawPointer?,
    _ timeoutNanoseconds: UInt64
) -> Int32 {
    guard let rawContext, let coordinator = coordinatorFromRawPointer(rawContext) else {
        return MetallumFrameInterpolationCoordinator.LifecycleStatus.invalidContext.rawValue
    }
    let status = coordinator.release(after: timeoutNanoseconds)
    if status == .ready {
        Unmanaged<MetallumFrameInterpolationCoordinator>.fromOpaque(rawContext).release()
    }
    return status.rawValue
}

// MARK: - Stage-5 typed Java ticket bridge

@_cdecl("metallum_frame_interpolation_create_v1")
public func metallum_frame_interpolation_create_v1(
    _ device: MTLDevice?,
    _ layer: CAMetalLayer?,
    _ width: Int32,
    _ height: Int32,
    _ pixelFormatRaw: UInt64,
    _ rendererGeneration: UInt64
) -> UnsafeMutableRawPointer? {
    guard let device, let layer else { return nil }
    return metallum_frame_interpolation_coordinator_create_stage4(
        device, layer, width, height, pixelFormatRaw, rendererGeneration
    )
}

/**
 * Production constructor with the exact fixed-Temporal input and display
 * extents.  V1 remains the validation/lifecycle ABI so its historical tests
 * do not accidentally become a user-facing presenter.
 */
@_cdecl("metallum_frame_interpolation_create_v2")
public func metallum_frame_interpolation_create_v2(
    _ device: MTLDevice?,
    _ layer: CAMetalLayer?,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ displayWidth: Int32,
    _ displayHeight: Int32,
    _ pixelFormatRaw: UInt64,
    _ rendererGeneration: UInt64
) -> UnsafeMutableRawPointer? {
    autoreleasepool {
        guard let device,
              let layer,
              inputWidth > 0,
              inputHeight > 0,
              displayWidth > 0,
              displayHeight > 0,
              let pixelFormat = MTLPixelFormat(rawValue: UInt(pixelFormatRaw)),
              let key = MetallumFrameInterpolationCoordinator.Key(
                device: device,
                layer: layer,
                inputWidth: Int(inputWidth),
                inputHeight: Int(inputHeight),
                width: Int(displayWidth),
                height: Int(displayHeight),
                pixelFormat: pixelFormat,
                rendererGeneration: rendererGeneration
              ),
              let coordinator = MetallumFrameInterpolationCoordinator(
                key: key,
                device: device,
                layer: layer
              ) else {
            return nil
        }
        return Unmanaged.passRetained(coordinator).toOpaque()
    }
}

/** Stage-10 constructor: Spatial uses standalone FI inputs, never a Temporal scaler. */
@_cdecl("metallum_frame_interpolation_create_v3")
public func metallum_frame_interpolation_create_v3(
    _ device: MTLDevice?,
    _ layer: CAMetalLayer?,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ displayWidth: Int32,
    _ displayHeight: Int32,
    _ pixelFormatRaw: UInt64,
    _ rendererGeneration: UInt64,
    _ usesSpatialInputs: Int32
) -> UnsafeMutableRawPointer? {
    autoreleasepool {
        guard let device, let layer,
              inputWidth > 0, inputHeight > 0,
              displayWidth > 0, displayHeight > 0,
              (0...1).contains(usesSpatialInputs),
              let pixelFormat = MTLPixelFormat(rawValue: UInt(pixelFormatRaw)),
              let key = MetallumFrameInterpolationCoordinator.Key(
                device: device, layer: layer,
                inputWidth: Int(inputWidth), inputHeight: Int(inputHeight),
                width: Int(displayWidth), height: Int(displayHeight),
                pixelFormat: pixelFormat, rendererGeneration: rendererGeneration,
                usesSpatialInputs: usesSpatialInputs != 0
              ),
              let coordinator = MetallumFrameInterpolationCoordinator(key: key, device: device, layer: layer)
        else { return nil }
        return Unmanaged.passRetained(coordinator).toOpaque()
    }
}

@_cdecl("metallum_frame_interpolation_prepare_v1")
public func metallum_frame_interpolation_prepare_v1(
    _ rawContext: UnsafeMutableRawPointer?,
    _ commandBuffer: MTLCommandBuffer?,
    _ rendererGeneration: UInt64,
    _ outTicket: UnsafeMutablePointer<UInt64>?
) -> Int32 {
    guard let coordinator = coordinatorFromRawPointer(rawContext), let commandBuffer else {
        return MetallumFrameInterpolationCoordinator.TicketStatus.bypassInputContract.rawValue
    }
    return coordinator.prepare(
        commandBuffer: commandBuffer,
        rendererGeneration: rendererGeneration,
        outTicket: outTicket
    ).rawValue
}

/** Typed Stage-9 production ticket preparation; no CPU readback or wait. */
@_cdecl("metallum_frame_interpolation_prepare_v2")
public func metallum_frame_interpolation_prepare_v2(
    _ rawContext: UnsafeMutableRawPointer?,
    _ commandBuffer: MTLCommandBuffer?,
    _ rendererGeneration: UInt64,
    _ sourceTexture: MTLTexture?,
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
    _ bloomStrength: Float,
    _ outTicket: UnsafeMutablePointer<UInt64>?
) -> Int32 {
    guard let coordinator = coordinatorFromRawPointer(rawContext),
          let commandBuffer,
          let sourceTexture else {
        return MetallumFrameInterpolationCoordinator.TicketStatus.bypassInputContract.rawValue
    }
    return coordinator.prepareProduction(
        commandBuffer: commandBuffer,
        rendererGeneration: rendererGeneration,
        sourceTexture: sourceTexture,
        sceneTexture: sceneTexture,
        sceneDepthTexture: sceneDepthTexture,
        semanticTexture: semanticTexture,
        uiTexture: uiTexture,
        globalFence: globalFence,
        spatialHdrPrecomposed: spatialHdrPrecomposed,
        outputMode: outputMode,
        sourceEncoding: sourceEncoding,
        materialGenerationActive: materialGenerationActive,
        diagnosticPattern: diagnosticPattern,
        currentHeadroom: currentHeadroom,
        hdrStrength: hdrStrength,
        bloomStrength: bloomStrength,
        outTicket: outTicket
    ).rawValue
}

@_cdecl("metallum_frame_interpolation_publish_committed_v1")
public func metallum_frame_interpolation_publish_committed_v1(
    _ rawContext: UnsafeMutableRawPointer?,
    _ ticket: UInt64
) -> Int32 {
    coordinatorFromRawPointer(rawContext)?.publishCommitted(ticket: ticket).rawValue
        ?? MetallumFrameInterpolationCoordinator.TicketStatus.staleTicket.rawValue
}

@_cdecl("metallum_frame_interpolation_cancel_v1")
public func metallum_frame_interpolation_cancel_v1(
    _ rawContext: UnsafeMutableRawPointer?,
    _ ticket: UInt64
) -> Int32 {
    coordinatorFromRawPointer(rawContext)?.cancel(ticket: ticket).rawValue
        ?? MetallumFrameInterpolationCoordinator.TicketStatus.staleTicket.rawValue
}

@_cdecl("metallum_frame_interpolation_drain_v1")
public func metallum_frame_interpolation_drain_v1(
    _ rawContext: UnsafeMutableRawPointer?,
    _ timeoutNanoseconds: UInt64
) -> Int32 {
    guard let coordinator = coordinatorFromRawPointer(rawContext) else {
        return MetallumFrameInterpolationCoordinator.TicketStatus.staleTicket.rawValue
    }
    switch coordinator.drain(timeoutNanoseconds: timeoutNanoseconds) {
    case .ready, .suspendedZeroSize:
        return MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue
    case .drainTimedOut:
        return MetallumFrameInterpolationCoordinator.TicketStatus.transientFailure.rawValue
    case .backpressure:
        return MetallumFrameInterpolationCoordinator.TicketStatus.bypassBackpressure.rawValue
    case .invalidContext, .released:
        return MetallumFrameInterpolationCoordinator.TicketStatus.staleTicket.rawValue
    }
}

@_cdecl("metallum_frame_interpolation_release_v1")
public func metallum_frame_interpolation_release_v1(
    _ rawContext: UnsafeMutableRawPointer?,
    _ timeoutNanoseconds: UInt64
) -> Int32 {
    guard let rawContext, let coordinator = coordinatorFromRawPointer(rawContext) else {
        return MetallumFrameInterpolationCoordinator.TicketStatus.staleTicket.rawValue
    }
    switch coordinator.release(after: timeoutNanoseconds) {
    case .ready:
        Unmanaged<MetallumFrameInterpolationCoordinator>.fromOpaque(rawContext).release()
        return MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue
    case .suspendedZeroSize:
        return MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue
    case .drainTimedOut:
        return MetallumFrameInterpolationCoordinator.TicketStatus.transientFailure.rawValue
    case .backpressure:
        return MetallumFrameInterpolationCoordinator.TicketStatus.bypassBackpressure.rawValue
    case .invalidContext, .released:
        return MetallumFrameInterpolationCoordinator.TicketStatus.staleTicket.rawValue
    }
}

/**
 * Native lifecycle/deadlock regression test for stage 4.  It performs 10,000
 * synthetic real-only enqueue/complete/reset/drain cycles after a single
 * warm-up allocation, then verifies bounded backpressure and zero-size
 * suspension.  No drawable is acquired and no command buffer is presented.
 */
@_cdecl("metallum_frame_interpolation_coordinator_stress_stage4")
public func metallum_frame_interpolation_coordinator_stress_stage4(_ device: MTLDevice) -> Int32 {
    autoreleasepool {
        let layer = CAMetalLayer()
        layer.device = device
        guard let rawCoordinator = metallum_frame_interpolation_coordinator_create_stage4(
            device,
            layer,
            16,
            16,
            UInt64(MTLPixelFormat.rgba16Float.rawValue),
            41
        ), let coordinator = coordinatorFromRawPointer(rawCoordinator) else {
            return -1
        }

        let warmAllocationCount = coordinator.nativeTextureAllocationCount()
        // Stage 8 adds one retained SDR UI texture and two separate composite
        // targets per slot, alongside the real/generated world-color pair.
        guard warmAllocationCount == 15 else { return -2 }
        for _ in 0..<10_000 {
            guard let slot = coordinator.reserveRealFrameSlot(),
                  coordinator.completeRealFrameSlot(slot) == .ready,
                  metallum_frame_interpolation_coordinator_drain_stage4(rawCoordinator, 1_000_000) == MetallumFrameInterpolationCoordinator.LifecycleStatus.ready.rawValue,
                  metallum_frame_interpolation_coordinator_reset_stage4(rawCoordinator, 1_000_000) == MetallumFrameInterpolationCoordinator.LifecycleStatus.ready.rawValue,
                  coordinator.nativeTextureAllocationCount() == warmAllocationCount else {
                _ = metallum_frame_interpolation_coordinator_release_stage4(rawCoordinator, 1_000_000_000)
                return -3
            }
        }

        var reserved: [Int] = []
        for _ in 0..<3 {
            guard let slot = coordinator.reserveRealFrameSlot() else {
                _ = metallum_frame_interpolation_coordinator_release_stage4(rawCoordinator, 1_000_000_000)
                return -4
            }
            reserved.append(slot)
        }
        guard coordinator.reserveRealFrameSlot() == nil else {
            _ = metallum_frame_interpolation_coordinator_release_stage4(rawCoordinator, 1_000_000_000)
            return -5
        }
        for slot in reserved {
            guard coordinator.completeRealFrameSlot(slot) == .ready else {
                _ = metallum_frame_interpolation_coordinator_release_stage4(rawCoordinator, 1_000_000_000)
                return -6
            }
        }

        guard metallum_frame_interpolation_coordinator_reset_stage4(rawCoordinator, 1_000_000) == MetallumFrameInterpolationCoordinator.LifecycleStatus.ready.rawValue,
              coordinator.nativeResetEpoch() == 10_001,
              metallum_frame_interpolation_coordinator_release_stage4(rawCoordinator, 1_000_000_000) == MetallumFrameInterpolationCoordinator.LifecycleStatus.ready.rawValue else {
            return -7
        }

        guard let rawZeroCoordinator = metallum_frame_interpolation_coordinator_create_stage4(
            device,
            layer,
            0,
            0,
            UInt64(MTLPixelFormat.rgba16Float.rawValue),
            42
        ), let zeroCoordinator = coordinatorFromRawPointer(rawZeroCoordinator),
              zeroCoordinator.nativeTextureAllocationCount() == 0,
              metallum_frame_interpolation_coordinator_drain_stage4(rawZeroCoordinator, 1_000_000) == MetallumFrameInterpolationCoordinator.LifecycleStatus.suspendedZeroSize.rawValue,
              metallum_frame_interpolation_coordinator_reset_stage4(rawZeroCoordinator, 1_000_000) == MetallumFrameInterpolationCoordinator.LifecycleStatus.suspendedZeroSize.rawValue,
              metallum_frame_interpolation_coordinator_release_stage4(rawZeroCoordinator, 1_000_000_000) == MetallumFrameInterpolationCoordinator.LifecycleStatus.ready.rawValue else {
            return -8
        }
        return 1
    }
}

/**
 * Stage-5 ABI regression test.  It proves that a ticket cannot publish before
 * the renderer command buffer commits, that a failed/bypassed path can cancel
 * it, and that the completion-based drain releases every pending ticket.
 */
@_cdecl("metallum_frame_interpolation_ticket_stress_stage5")
public func metallum_frame_interpolation_ticket_stress_stage5(_ device: MTLDevice) -> Int32 {
    autoreleasepool {
        let layer = CAMetalLayer()
        layer.device = device
        guard let rawCoordinator = metallum_frame_interpolation_create_v1(
            device,
            layer,
            16,
            16,
            UInt64(MTLPixelFormat.rgba16Float.rawValue),
            73
        ), let queue = device.makeCommandQueue() else {
            return -1
        }

        guard let cancelledBuffer = queue.makeCommandBuffer() else {
            _ = metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
            return -2
        }
        var cancelledTicket: UInt64 = 0
        guard metallum_frame_interpolation_prepare_v1(rawCoordinator, cancelledBuffer, 73, &cancelledTicket)
                == MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue,
              cancelledTicket != 0,
              metallum_frame_interpolation_publish_committed_v1(rawCoordinator, cancelledTicket)
                == MetallumFrameInterpolationCoordinator.TicketStatus.staleTicket.rawValue,
              metallum_frame_interpolation_cancel_v1(rawCoordinator, cancelledTicket)
                == MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue else {
            _ = metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
            return -3
        }

        guard let committedBuffer = queue.makeCommandBuffer() else {
            _ = metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
            return -4
        }
        var committedTicket: UInt64 = 0
        guard metallum_frame_interpolation_prepare_v1(rawCoordinator, committedBuffer, 73, &committedTicket)
                == MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue,
              committedTicket != 0 else {
            _ = metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
            return -5
        }
        committedBuffer.commit()
        guard metallum_frame_interpolation_publish_committed_v1(rawCoordinator, committedTicket)
                == MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue else {
            _ = metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
            return -6
        }
        committedBuffer.waitUntilCompleted()
        guard metallum_frame_interpolation_drain_v1(rawCoordinator, 1_000_000_000)
                == MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue,
              metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
                == MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue else {
            return -7
        }
        return 1
    }
}

/**
 * Stage-6 native proof for the fixed-Temporal Metal 3 encoder.  The return
 * value is intentionally tri-state: 1 pass, 2 clean unsupported skip, or a
 * negative deterministic contract failure.  It never acquires a drawable.
 */
@_cdecl("metallum_frame_interpolation_encode_stress_stage6")
public func metallum_frame_interpolation_encode_stress_stage6(_ device: MTLDevice) -> Int32 {
    guard #available(macOS 26.0, *),
          MTLFXFrameInterpolatorDescriptor.supportsDevice(device) else {
        return 2
    }
    return autoreleasepool {
        let layer = CAMetalLayer()
        layer.device = device
        guard let rawCoordinator = metallum_frame_interpolation_create_v1(
            device,
            layer,
            64,
            64,
            UInt64(MTLPixelFormat.rgba16Float.rawValue),
            106
        ), let coordinator = coordinatorFromRawPointer(rawCoordinator) else {
            return -1
        }
        defer {
            _ = metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
        }

        let priming = MetallumFrameInterpolationCoordinator.TicketStatus.bypassPriming
        let prepared = MetallumFrameInterpolationCoordinator.TicketStatus.prepared
        guard coordinator.encodeValidationFrame(resetHistory: false, fieldOfView: 70.0) == priming,
              coordinator.encodeValidationFrame(resetHistory: false, fieldOfView: 70.0) == priming,
              coordinator.encodeValidationFrame(resetHistory: false, fieldOfView: 70.0) == prepared,
              coordinator.encodeValidationFrame(resetHistory: false, fieldOfView: 70.0) == prepared else {
            return -2
        }

        // A teleport/FOV-like discontinuity must re-prime and must not encode
        // a pair that crosses the old history generation.
        guard coordinator.encodeValidationFrame(resetHistory: true, fieldOfView: 90.0) == priming,
              coordinator.encodeValidationFrame(resetHistory: false, fieldOfView: 90.0) == priming,
              coordinator.encodeValidationFrame(resetHistory: false, fieldOfView: 90.0) == prepared,
              coordinator.encodeValidationFrame(resetHistory: false, fieldOfView: .nan)
                == .bypassInputContract,
              coordinator.nativeStage6Counter(0) == 4,
              coordinator.nativeStage6Counter(2) == 1,
              coordinator.nativeStage6Counter(3) == 1,
              coordinator.nativeStage6Counter(4) == 3 else {
            return -3
        }

        // Apple requires current-pixel -> previous-pixel displacement.  This
        // known-pixel calibration rejects an accidental sign flip before live
        // camera/entity motion calibration in a later stage.
        let motion = MetallumFrameInterpolationCoordinator.currentToPreviousMotion(
            currentPixel: SIMD2<Float>(10.0, 10.0),
            previousPixel: SIMD2<Float>(0.0, 0.0)
        )
        guard motion == SIMD2<Float>(-10.0, -10.0) else { return -4 }
        return 1
    }
}

/**
 * Stage-7 pacing regression.  This intentionally exercises scheduler policy,
 * not a user-visible drawable path: Stage 8 owns the shared HDR/UI composite
 * required before a generated surface can be shown.  It proves generated ->
 * real order, one-pair backpressure, a three-drawable layer pool, and that a
 * late generated slot drops without losing its following real frame.
 */
@_cdecl("metallum_frame_interpolation_pacing_stress_stage7")
public func metallum_frame_interpolation_pacing_stress_stage7(_ device: MTLDevice) -> Int32 {
    autoreleasepool {
        let layer = CAMetalLayer()
        layer.device = device
        layer.maximumDrawableCount = 2
        guard let rawCoordinator = metallum_frame_interpolation_create_v1(
            device,
            layer,
            16,
            16,
            UInt64(MTLPixelFormat.rgba16Float.rawValue),
            107
        ), let coordinator = coordinatorFromRawPointer(rawCoordinator) else {
            return -1
        }
        var released = false
        defer {
            if !released {
                _ = metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
            }
        }
        guard layer.maximumDrawableCount == 3 else { return -2 }

        let now = DispatchTime.now().uptimeNanoseconds
        guard coordinator.enqueueSyntheticPresentationPair(
            generatedDeadlineNanoseconds: now + 2_000_000,
            realDeadlineNanoseconds: now + 4_000_000
        ) == .prepared,
              coordinator.enqueueSyntheticPresentationPair(
                generatedDeadlineNanoseconds: now + 6_000_000,
                realDeadlineNanoseconds: now + 8_000_000
              ) == .bypassBackpressure,
              coordinator.drain(timeoutNanoseconds: 1_000_000_000) == .ready,
              coordinator.nativeStage7Counter(0) == 2,
              coordinator.nativeStage7Counter(1) == 0,
              coordinator.nativeStage7Counter(3) == 2,
              coordinator.nativeStage7InvariantsHold() else {
            return -3
        }

        guard coordinator.reset(after: 1_000_000_000) == .ready else { return -4 }
        let lateNow = DispatchTime.now().uptimeNanoseconds
        guard coordinator.enqueueSyntheticPresentationPair(
            generatedDeadlineNanoseconds: lateNow - 10_000_000,
            realDeadlineNanoseconds: lateNow + 2_000_000
        ) == .prepared,
              coordinator.drain(timeoutNanoseconds: 1_000_000_000) == .ready,
              coordinator.nativeStage7Counter(0) == 4,
              coordinator.nativeStage7Counter(1) == 1,
              coordinator.nativeStage7Counter(3) == 4,
              coordinator.nativeStage7Counter(2) <= 2,
              coordinator.nativeStage7InvariantsHold() else {
            return -5
        }

        guard metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
                == MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue,
              layer.maximumDrawableCount == 2 else {
            return -6
        }
        released = true
        return 1
    }
}

/**
 * Stage-8 HDR/UI regression.  It keeps SDR UI separate from both world
 * textures, composites that UI through the common path for generated and real
 * output, and verifies a headroom transition drains/resets before reuse.  It
 * intentionally uses offscreen targets: live drawable and fullscreen proof
 * belong to the production-admission phase, not this disabled validation path.
 */
@_cdecl("metallum_frame_interpolation_hdr_ui_stress_stage8")
public func metallum_frame_interpolation_hdr_ui_stress_stage8(_ device: MTLDevice) -> Int32 {
    func validateProfile(
        pixelFormat: MTLPixelFormat,
        outputMode: Int32,
        initialHeadroom: Float,
        changedHeadroom: Float,
        generation: UInt64
    ) -> Int32 {
        let layer = CAMetalLayer()
        layer.device = device
        guard let rawCoordinator = metallum_frame_interpolation_create_v1(
            device,
            layer,
            64,
            64,
            UInt64(pixelFormat.rawValue),
            generation
        ), let coordinator = coordinatorFromRawPointer(rawCoordinator) else {
            return -1
        }
        var released = false
        defer {
            if !released {
                _ = metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
            }
        }

        guard coordinator.nativeTextureAllocationCount() == 15,
              coordinator.encodeSharedUiCompositeValidation(
                outputMode: outputMode,
                currentHeadroom: initialHeadroom
              ) == .prepared,
              coordinator.nativeStage8Counter(1) == 1,
              coordinator.drain(timeoutNanoseconds: 1_000_000_000) == .ready,
              coordinator.nativeStage8Counter(0) == 2,
              coordinator.nativeStage8Counter(1) == 0 else {
            return -2
        }

        guard coordinator.updatePresentationHeadroom(
            changedHeadroom,
            timeoutNanoseconds: 1_000_000_000
        ) == .ready else { return -3 }
        let headroomChanged = abs(changedHeadroom - initialHeadroom) > 0.0001
        guard headroomChanged
                ? coordinator.nativeStage8Counter(2) >= 1
                : coordinator.nativeStage8Counter(2) == 0 else { return -4 }
        guard coordinator.encodeSharedUiCompositeValidation(
            outputMode: outputMode,
            currentHeadroom: changedHeadroom
        ) == .prepared else { return -5 }
        guard coordinator.drain(timeoutNanoseconds: 1_000_000_000) == .ready else { return -6 }
        guard coordinator.nativeStage8Counter(0) == 4 else { return -7 }

        guard metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
                == MetallumFrameInterpolationCoordinator.TicketStatus.prepared.rawValue else {
            return -8
        }
        released = true
        return 1
    }

    return autoreleasepool {
        // SDR uses the ordinary layer format.  EDR and Enhanced/HDR share the
        // fp16 layer format but retain distinct present modes and headroom.
        for profile in [
            (MTLPixelFormat.bgra8Unorm, Int32(0), Float(1.0), Float(1.25)),
            (MTLPixelFormat.rgba16Float, Int32(1), Float(1.25), Float(1.75)),
            (MTLPixelFormat.rgba16Float, Int32(2), Float(1.50), Float(2.25))
        ].enumerated() {
            let (pixelFormat, outputMode, initialHeadroom, changedHeadroom) = profile.element
            let status = validateProfile(
                pixelFormat: pixelFormat,
                outputMode: outputMode,
                initialHeadroom: initialHeadroom,
                changedHeadroom: changedHeadroom,
                generation: UInt64(108 + profile.offset)
            )
            guard status == 1 else { return status - Int32(profile.offset * 10) }
        }
        return 1
    }
}

/**
 * Stage-9 bridge regression: verifies the production v2 descriptor preserves
 * its fixed input extent and that an incomplete world/UI hand-off is a clean
 * real-frame bypass with no ticket or drawable acquisition.  Full visual
 * cadence remains a live-game acceptance gate because CAMetalDrawable cannot
 * be deterministically supplied by a headless validation layer.
 */
@_cdecl("metallum_frame_interpolation_contract_stress_stage9")
public func metallum_frame_interpolation_contract_stress_stage9(_ device: MTLDevice) -> Int32 {
    guard #available(macOS 26.0, *),
          MTLFXFrameInterpolatorDescriptor.supportsDevice(device) else {
        return 2
    }
    return autoreleasepool {
        let layer = CAMetalLayer()
        layer.device = device
        guard let rawCoordinator = metallum_frame_interpolation_create_v2(
            device,
            layer,
            32,
            24,
            64,
            48,
            UInt64(MTLPixelFormat.rgba16Float.rawValue),
            211
        ), let coordinator = coordinatorFromRawPointer(rawCoordinator),
              coordinator.key.inputWidth == 32,
              coordinator.key.inputHeight == 24,
              coordinator.key.width == 64,
              coordinator.key.height == 48,
              let queue = device.makeCommandQueue(),
              let commandBuffer = queue.makeCommandBuffer(),
              let source = device.makeTexture(descriptor: MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .rgba16Float,
                width: 32,
                height: 24,
                mipmapped: false
              )) else {
            return -1
        }
        defer {
            _ = metallum_frame_interpolation_release_v1(rawCoordinator, 1_000_000_000)
        }
        var ticket: UInt64 = 9
        let status = metallum_frame_interpolation_prepare_v2(
            rawCoordinator,
            commandBuffer,
            211,
            source,
            nil,
            nil,
            nil,
            nil,
            nil,
            0,
            0,
            0,
            0,
            0,
            1.0,
            1.0,
            0.22,
            &ticket
        )
        guard status == MetallumFrameInterpolationCoordinator.TicketStatus.bypassNoUi.rawValue,
              ticket == 0 else {
            return -2
        }
        return 1
    }
}

/** Stage-10 Spatial descriptor/profile regression, including non-uniform motion scale. */
@_cdecl("metallum_frame_interpolation_spatial_stress_stage10")
public func metallum_frame_interpolation_spatial_stress_stage10(_ device: MTLDevice) -> Int32 {
    guard #available(macOS 26.0, *),
          MTLFXFrameInterpolatorDescriptor.supportsDevice(device) else {
        return 2
    }
    return autoreleasepool {
        let scale = MetallumFrameInterpolationCoordinator.spatialMotionScale(
            displayWidth: 96, displayHeight: 90, renderWidth: 48, renderHeight: 30
        )
        guard abs(scale.x - 2.0) < 0.0001, abs(scale.y - 3.0) < 0.0001 else { return -1 }
        let layer = CAMetalLayer()
        layer.device = device
        guard let raw = metallum_frame_interpolation_create_v3(
            device, layer, 48, 30, 96, 90,
            UInt64(MTLPixelFormat.rgba16Float.rawValue), 312, 1
        ), let coordinator = coordinatorFromRawPointer(raw),
              coordinator.hasStandaloneSpatialInterpolatorForTests() else { return -2 }
        defer { _ = metallum_frame_interpolation_release_v1(raw, 1_000_000_000) }
        return 1
    }
}
