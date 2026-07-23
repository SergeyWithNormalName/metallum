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
        let pixelFormat: MTLPixelFormat
        let rendererGeneration: UInt64

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
            self.pixelFormat = pixelFormat
            self.rendererGeneration = rendererGeneration
        }
    }

    private enum SlotState {
        case free
        case realFrameReserved
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
        var state: TicketState = .prepared
        var commandBufferCompleted = false

        init(slot: Int, commandBuffer: MTLCommandBuffer) {
            self.slot = slot
            self.commandBuffer = commandBuffer
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
    private var historyState: HistoryState = .primingFirst
    private var previousEncodedSlot: Int?
    private var primedPreviousSlot: Int?
    private var nextHistorySlot = 0
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
        configureFixedTemporalInterpolator()

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
    private func configureFixedTemporalInterpolator() {
        guard key.isDrawableSized else { return }
        guard #available(macOS 26.0, *),
              MTLFXFrameInterpolatorDescriptor.supportsDevice(device) else {
            return
        }

        let inputWidth = max(key.width / 2, 1)
        let inputHeight = max(key.height / 2, 1)
        // Reuse the renderer's fixed Temporal workspace when it already exists
        // for this immutable descriptor key; the cache only creates it when
        // this validation workspace is the first owner.
        guard let scaler = existingFixedTemporalScalerForFrameInterpolation(
            device: device,
            sourcePixelFormat: key.pixelFormat,
            inputWidth: inputWidth,
            inputHeight: inputHeight,
            outputWidth: key.width,
            outputHeight: key.height
        ) as? MTLFXTemporalScaler else { return }

        let descriptor = MTLFXFrameInterpolatorDescriptor()
        descriptor.colorTextureFormat = key.pixelFormat
        descriptor.outputTextureFormat = key.pixelFormat
        descriptor.depthTextureFormat = .depth32Float
        descriptor.motionTextureFormat = .rg16Float
        descriptor.inputWidth = inputWidth
        descriptor.inputHeight = inputHeight
        descriptor.outputWidth = key.width
        descriptor.outputHeight = key.height
        descriptor.scaler = scaler
        guard let created = descriptor.makeFrameInterpolator(device: device) else { return }
        temporalScaler = scaler
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

        var depthDescriptor: MTLTextureDescriptor?
        var motionDescriptor: MTLTextureDescriptor?
        if #available(macOS 26.0, *), let interpolator = interpolator as? MTLFXFrameInterpolator {
            descriptor.usage.formUnion(interpolator.colorTextureUsage)
            generatedDescriptor.usage.formUnion(interpolator.outputTextureUsage)
            let inputWidth = max(key.width / 2, 1)
            let inputHeight = max(key.height / 2, 1)
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
                  let generatedColor = device.makeTexture(descriptor: generatedDescriptor) else {
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
            allocated.append(Slot(
                realColor: realColor,
                generatedColor: generatedColor,
                depth: depth,
                motion: motion,
                state: .free
            ))
        }
        slots = allocated
        textureAllocationCount = allocated.count * 2
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
        if pending.commandBufferCompleted {
            tickets.removeValue(forKey: ticket)
            releaseSlotLocked(pending.slot)
        }
        state.unlock()
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
        defer { state.unlock() }
        guard let pending = tickets[ticket] else { return }
        pending.commandBufferCompleted = true
        guard pending.state == .published else { return }
        tickets.removeValue(forKey: ticket)
        releaseSlotLocked(pending.slot)
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
        guard warmAllocationCount == 6 else { return -2 }
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
