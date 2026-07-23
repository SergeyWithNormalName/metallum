import Foundation
import Metal
import QuartzCore

/**
 * Stage-4 lifecycle owner for the future MetalFX presentation path.
 *
 * This deliberately does not acquire a drawable, encode MetalFX, or call
 * `present`.  Until the ticket/commit boundary exists (stage 5), the normal
 * one-drawable Java presentation path remains the only producer of visible
 * frames.  Keeping that boundary explicit prevents an accidental second
 * present while the coordinator is being brought up.
 */
private final class MetallumFrameInterpolationCoordinator: @unchecked Sendable {
    private static let ringSize = 3

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

    private struct Slot {
        let realColor: MTLTexture
        let generatedColor: MTLTexture
        var state: SlotState
    }

    enum LifecycleStatus: Int32 {
        case ready = 1
        case suspendedZeroSize = 2
        case backpressure = 3
        case invalidContext = -1
        case drainTimedOut = -2
        case released = -3
    }

    let key: Key
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
    private var resetEpoch: UInt64 = 0
    private var textureAllocationCount = 0

    init?(key: Key, device: MTLDevice, layer: CAMetalLayer) {
        guard key.deviceID == ObjectIdentifier(device), key.layerID == ObjectIdentifier(layer) else {
            return nil
        }
        self.key = key
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

        if key.isDrawableSized {
            guard allocateTextureRings(device: device) else {
                self.renderQueue = nil
                self.presentationQueue = nil
                self.completionEvent = nil
                return nil
            }
            acceptingFrames = true
        }

        schedulerThread.name = "Metallum FI real-only scheduler"
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
            return nil
        }
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
        // Stage 6 will union the exact MetalFX-reported usages into this
        // descriptor.  These are the producer/composite usages needed by the
        // present path and avoid a later per-frame texture allocation.
        descriptor.usage = [.shaderRead, .shaderWrite, .renderTarget]

        var allocated: [Slot] = []
        allocated.reserveCapacity(Self.ringSize)
        for index in 0..<Self.ringSize {
            guard let realColor = device.makeTexture(descriptor: descriptor),
                  let generatedColor = device.makeTexture(descriptor: descriptor) else {
                return false
            }
            realColor.label = "Metallum FI real color \(index)"
            generatedColor.label = "Metallum FI generated color \(index)"
            allocated.append(Slot(realColor: realColor, generatedColor: generatedColor, state: .free))
        }
        slots = allocated
        textureAllocationCount = allocated.count * 2
        return true
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
        guard pendingRealFrames > 0 else {
            state.unlock()
            return key.isDrawableSized ? .ready : .suspendedZeroSize
        }

        let timeoutSeconds = Double(timeoutNanoseconds) / 1_000_000_000.0
        let deadline = Date(timeIntervalSinceNow: max(timeoutSeconds, 0.0))
        while pendingRealFrames > 0 {
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
        state.unlock()
        return .ready
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

    private func runRealOnlyScheduler() {
        schedulerStarted.signal()
        while true {
            schedulerWake.wait()
            state.lock()
            let shouldStop = shuttingDown
            state.unlock()
            if shouldStop {
                break
            }
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
