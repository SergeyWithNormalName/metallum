import AppKit
import Darwin
import Foundation
import Metal
import QuartzCore

/**
 * Immutable AppKit display contract captured on the main thread.
 *
 * `minimumRefreshInterval` is the fastest display interval and
 * `maximumRefreshInterval` is the slowest interval accepted by Adaptive-Sync.
 * They are intentionally kept separate from `maximumFramesPerSecond`: system
 * policy can alter the usable interval range without changing the panel's
 * advertised maximum.
 */
struct MetallumDisplayTimingSnapshot: Sendable {
    static let unavailable = MetallumDisplayTimingSnapshot(
        displayID: 0,
        maximumFramesPerSecond: 0,
        minimumRefreshInterval: 0,
        maximumRefreshInterval: 0,
        displayUpdateGranularity: 0,
        lastDisplayUpdateTimestamp: 0,
        fullscreen: false,
        isBuiltin: false
    )

    let displayID: UInt32
    let maximumFramesPerSecond: Int
    let minimumRefreshInterval: Double
    let maximumRefreshInterval: Double
    let displayUpdateGranularity: Double
    let lastDisplayUpdateTimestamp: Double
    let fullscreen: Bool
    let isBuiltin: Bool

    var isValid: Bool {
        maximumFramesPerSecond > 0
            && minimumRefreshInterval.isFinite
            && maximumRefreshInterval.isFinite
            && minimumRefreshInterval > 0
            && maximumRefreshInterval >= minimumRefreshInterval
    }

    var supportsVariableRefreshRate: Bool {
        isValid && maximumRefreshInterval - minimumRefreshInterval > 0.000_001
    }

    var adaptiveSchedulingActive: Bool {
        supportsVariableRefreshRate && fullscreen
    }

    func hasSameSchedulingContract(as other: MetallumDisplayTimingSnapshot) -> Bool {
        displayID == other.displayID
            && maximumFramesPerSecond == other.maximumFramesPerSecond
            && abs(minimumRefreshInterval - other.minimumRefreshInterval) < 0.000_001
            && abs(maximumRefreshInterval - other.maximumRefreshInterval) < 0.000_001
            && abs(displayUpdateGranularity - other.displayUpdateGranularity) < 0.000_001
            && fullscreen == other.fullscreen
    }
}

/** Monotonic nanosecond clock shared by render, interpolation and pacing code. */
enum MetallumMonotonicClock {
    private static let timebase: mach_timebase_info_data_t = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        return info
    }()

    static func nowNanoseconds() -> UInt64 {
        let ticks = mach_absolute_time()
        return UInt64(
            Double(ticks) * Double(timebase.numer) / Double(timebase.denom)
        )
    }

    static func machTicks(forNanoseconds nanoseconds: UInt64) -> UInt64 {
        UInt64(
            Double(nanoseconds) * Double(timebase.denom) / Double(timebase.numer)
        )
    }
}

/**
 * Absolute Mach-time timer used only by the dedicated FI presentation worker.
 * Metal presentation itself remains asynchronous.  The timer never runs on
 * the Java render thread and never holds a renderer/coordinator lock.
 */
final class MetallumPrecisionDeadlineTimer: @unchecked Sendable {
    private let timerQueue: Int32

    init() {
        timerQueue = kqueue()
    }

    deinit {
        if timerQueue >= 0 {
            close(timerQueue)
        }
    }

    @discardableResult
    func wait(untilNanoseconds deadlineNanoseconds: UInt64) -> Bool {
        if MetallumMonotonicClock.nowNanoseconds() >= deadlineNanoseconds {
            return true
        }

        let deadlineTicks = MetallumMonotonicClock.machTicks(
            forNanoseconds: deadlineNanoseconds
        )
        if timerQueue < 0 {
            return mach_wait_until(deadlineTicks) == KERN_SUCCESS
        }

        var timerEvent = kevent64_s()
        timerEvent.ident = 1
        timerEvent.filter = Int16(EVFILT_TIMER)
        timerEvent.flags = UInt16(EV_ADD | EV_ONESHOT | EV_ENABLE)
        timerEvent.fflags = UInt32(
            NOTE_CRITICAL | NOTE_LEEWAY | NOTE_MACHTIME | NOTE_ABSOLUTE
        )
        timerEvent.data = Int64(min(deadlineTicks, UInt64(Int64.max)))
        // Keep timer coalescing leeway at zero for the real-frame midpoint.
        timerEvent.ext.1 = 0

        var eventOut = kevent64_s()
        var timeout = timespec(tv_sec: 0, tv_nsec: 100_000_000)
        while MetallumMonotonicClock.nowNanoseconds() < deadlineNanoseconds {
            let result = kevent64(
                timerQueue,
                &timerEvent,
                1,
                &eventOut,
                1,
                0,
                &timeout
            )
            if result > 0 {
                return true
            }
            if result < 0 && errno != EINTR {
                return mach_wait_until(deadlineTicks) == KERN_SUCCESS
            }
        }
        return true
    }
}

/**
 * Shared adaptive scheduler for both the ordinary real-only presenter and the
 * MetalFX generated/real presenter.
 *
 * The scheduler changes presentation timing only.  It never changes render
 * dimensions, scaler presets, HDR values, texture formats or image contents.
 */
final class MetallumExtendedProMotionScheduler: @unchecked Sendable {
    enum Mode: String, Sendable {
        case unmanaged
        case fixedRefresh
        case adaptiveRefresh
    }

    enum PresentationKind: Equatable, Sendable {
        case realOnly
        case generated
        case interpolatedReal
    }

    enum InterpolationRejection: String, Sendable {
        case disabled
        case displayUnavailable
        case displaySyncDisabled
        case warmingUp
        case realCadenceTooFast
        case realCadenceTooSlow
        case fixedCadenceMismatch
    }

    struct Plan: Sendable {
        let displayGeneration: UInt64
        let mode: Mode
        let presentationIntervalSeconds: Double
        let targetFramesPerSecond: Double
        let useTimedPresentation: Bool
    }

    struct InterpolationDecision: Sendable {
        let plan: Plan?
        let rejection: InterpolationRejection?
    }

    struct Snapshot: Sendable {
        let enabled: Bool
        let displayGeneration: UInt64
        let display: MetallumDisplayTimingSnapshot
        let mode: Mode
        let targetFramesPerSecond: Double
        let timedPresentationRequests: UInt64
        let plainPresentationRequests: UInt64
        let presentedRealOnly: UInt64
        let presentedGenerated: UInt64
        let presentedInterpolatedReal: UInt64
        let skippedPresentations: UInt64
        let stalePresentationCallbacks: UInt64
        let outOfOrderPresentations: UInt64
        let targetMisses: UInt64
        let rateTransitions: UInt64
        let meanPresentedIntervalSeconds: Double
        let presentationIntervals: [UInt64]

        var report: [String: Any] {
            let bucketCount = Set(presentationIntervals.map { ($0 + 125_000) / 250_000 }).count
            return [
                "enabled": enabled,
                "mode": mode.rawValue,
                "display_generation": displayGeneration,
                "display_id": display.displayID,
                "display_maximum_fps": display.maximumFramesPerSecond,
                "display_minimum_refresh_hz": display.maximumRefreshInterval > 0
                    ? 1.0 / display.maximumRefreshInterval : 0.0,
                "display_minimum_interval_ms": display.minimumRefreshInterval * 1_000.0,
                "display_maximum_interval_ms": display.maximumRefreshInterval * 1_000.0,
                "display_update_granularity_ms": display.displayUpdateGranularity * 1_000.0,
                "display_last_update_timestamp": display.lastDisplayUpdateTimestamp,
                "fullscreen": display.fullscreen,
                "display_is_builtin": display.isBuiltin,
                "variable_refresh_supported": display.supportsVariableRefreshRate,
                "adaptive_scheduling_active": display.adaptiveSchedulingActive,
                "target_presented_fps": targetFramesPerSecond,
                "timed_present_requests": timedPresentationRequests,
                "plain_present_requests": plainPresentationRequests,
                "presented_real_only": presentedRealOnly,
                "presented_generated": presentedGenerated,
                "presented_interpolated_real": presentedInterpolatedReal,
                "skipped_presentations": skippedPresentations,
                "stale_presentation_callbacks": stalePresentationCallbacks,
                "out_of_order_presentations": outOfOrderPresentations,
                "target_misses": targetMisses,
                "rate_transitions": rateTransitions,
                "mean_present_interval_ms": meanPresentedIntervalSeconds * 1_000.0,
                "presentation_interval_histogram_buckets": bucketCount
            ]
        }
    }

    private struct CadenceEstimator {
        private(set) var sampleCount = 0
        private(set) var mean = 0.0
        private(set) var deviation = 0.0

        mutating func reset() {
            sampleCount = 0
            mean = 0
            deviation = 0
        }

        @discardableResult
        mutating func observe(_ sample: Double, range: ClosedRange<Double>) -> Bool {
            guard sample.isFinite, range.contains(sample) else { return false }
            if sampleCount == 0 {
                mean = sample
                deviation = 0
                sampleCount = 1
                return true
            }

            let error = sample - mean
            // Slowdowns receive more weight so the scheduler lowers its target
            // before repeated missed presents.  Speedups require sustained
            // evidence and are deliberately approached more slowly.
            let alpha = error > 0 ? 0.28 : 0.12
            mean += alpha * error
            deviation += 0.18 * (abs(error) - deviation)
            if sampleCount < Int.max {
                sampleCount += 1
            }
            return true
        }

        var sustainableInterval: Double {
            guard sampleCount > 0 else { return 0 }
            // Keep a small jitter reserve without turning a single spike into
            // a permanent low-rate mode.
            return mean + min(deviation * 0.5, mean * 0.02)
        }
    }

    private static let intervalRingSize = 128
    private static let interpolationWarmupSamples = 6
    private static let fasterTargetConfirmationSamples = 8

    private let lock = NSLock()
    private let enabled: Bool
    private var display = MetallumDisplayTimingSnapshot.unavailable
    private var displayGeneration: UInt64 = 0
    private var displaySyncEnabled = true
    private var realOnlyCadence = CadenceEstimator()
    private var interpolationCadence = CadenceEstimator()
    private var renderGpuCadence = CadenceEstimator()
    private var realOnlySelectedInterval = 0.0
    private var interpolationSelectedInterval = 0.0
    private var fasterRealOnlyConfirmations = 0
    private var fasterInterpolationConfirmations = 0
    private var currentMode: Mode = .unmanaged
    private var currentTargetFramesPerSecond = 0.0
    private var timedPresentationRequests: UInt64 = 0
    private var plainPresentationRequests: UInt64 = 0
    private var presentedRealOnly: UInt64 = 0
    private var presentedGenerated: UInt64 = 0
    private var presentedInterpolatedReal: UInt64 = 0
    private var skippedPresentations: UInt64 = 0
    private var stalePresentationCallbacks: UInt64 = 0
    /// Actual CAMetalDrawable callback timestamps that regress within the
    /// current display generation.  A correct single-queue FI path stays 0.
    private var outOfOrderPresentations: UInt64 = 0
    private var targetMisses: UInt64 = 0
    private var rateTransitions: UInt64 = 0
    private var lastPresentedTime = 0.0
    private var presentedIntervalSum = 0.0
    private var presentedIntervalCount: UInt64 = 0
    private var intervalRing = [UInt64](repeating: 0, count: intervalRingSize)
    private var intervalRingCount = 0
    private var intervalRingCursor = 0

    init(enabled: Bool = ProcessInfo.processInfo.environment[
        "METALLUM_PROMOTION_SCHEDULER"
    ] != "0") {
        self.enabled = enabled
    }

    func updateDisplay(_ next: MetallumDisplayTimingSnapshot) {
        lock.lock()
        defer { lock.unlock() }
        let contractChanged = !display.hasSameSchedulingContract(as: next)
        display = next
        if contractChanged {
            displayGeneration &+= 1
            resetEstimatorsLocked()
        }
    }

    func updateDisplaySyncEnabled(_ enabled: Bool) {
        lock.lock()
        defer { lock.unlock() }
        if displaySyncEnabled != enabled {
            displaySyncEnabled = enabled
            resetEstimatorsLocked()
        }
    }

    func realOnlyPlan(
        renderDeltaSeconds: Double,
        displaySyncEnabled requestedDisplaySync: Bool
    ) -> Plan {
        lock.lock()
        defer { lock.unlock() }
        synchronizeDisplaySyncLocked(requestedDisplaySync)
        guard enabled, requestedDisplaySync, display.isValid else {
            currentMode = .unmanaged
            currentTargetFramesPerSecond = 0
            return unmanagedPlanLocked()
        }

        // This path owns no cadence.  GLFW/CAMetalLayer's ordinary synced
        // present already chooses the next legal vblank, while feeding the
        // Minecraft frame delta back into `afterMinimumDuration` created a
        // second limiter (including the historical 24-FPS floor).  Keep the
        // display model for telemetry and FI admission, but do not throttle
        // real-only frames here.
        let mode: Mode = display.adaptiveSchedulingActive ? .adaptiveRefresh : .fixedRefresh
        let interval = fastestIntervalLocked()
        publishTargetLocked(mode: mode, interval: interval)
        return Plan(
            displayGeneration: displayGeneration,
            mode: mode,
            presentationIntervalSeconds: interval,
            targetFramesPerSecond: 1.0 / interval,
            useTimedPresentation: false
        )
    }

    func frameInterpolationPlan(
        realDeltaSeconds: Double,
        displaySyncEnabled requestedDisplaySync: Bool
    ) -> InterpolationDecision {
        lock.lock()
        defer { lock.unlock() }
        synchronizeDisplaySyncLocked(requestedDisplaySync)
        guard enabled else {
            return InterpolationDecision(plan: nil, rejection: .disabled)
        }
        guard display.isValid, display.maximumFramesPerSecond >= 60 else {
            return InterpolationDecision(plan: nil, rejection: .displayUnavailable)
        }
        guard requestedDisplaySync else {
            return InterpolationDecision(plan: nil, rejection: .displaySyncDisabled)
        }
        let fastest = fastestIntervalLocked()
        guard interpolationCadence.observe(
            realDeltaSeconds,
            // Admit bounded one-off 15 ms / 35 ms jitter into the estimator.
            // Sustained cadence, not one Minecraft delta, decides ownership.
            range: (1.0 / 240.0)...(1.0 / 20.0)
        ) else {
            return InterpolationDecision(plan: nil, rejection: .realCadenceTooSlow)
        }
        guard interpolationCadence.sampleCount >= Self.interpolationWarmupSamples else {
            return InterpolationDecision(plan: nil, rejection: .warmingUp)
        }

        // FI must follow the cadence of mandatory real frames.  Retargeting it
        // to an individual command buffer's GPU duration would silently cap or
        // discard real frames when queues overlap, which is outside scheduler
        // ownership and would increase input latency.
        let sustainableRealInterval = interpolationCadence.sustainableInterval
        // 2x synthesis cannot sustain a real stream materially faster than
        // half the panel maximum, but a single 15 ms sample at 60 FPS should
        // not force a presenter switch/re-prime.
        guard sustainableRealInterval >= fastest * 2.0 * 0.95 else {
            return InterpolationDecision(plan: nil, rejection: .realCadenceTooFast)
        }
        guard sustainableRealInterval <= 1.0 / 30.0 else {
            return InterpolationDecision(plan: nil, rejection: .realCadenceTooSlow)
        }
        var candidate = max(sustainableRealInterval * 0.5, fastest)
        // MetalFX input must remain at least 30 real FPS, so the synthesized
        // presentation stream never intentionally falls below 60 FPS.
        candidate = min(candidate, 1.0 / 60.0)
        candidate = quantizedIntervalLocked(candidate, allowFixedMultiples: true)

        if !display.adaptiveSchedulingActive {
            // A fixed display can only show evenly spaced factor cadences.  Do
            // not claim 80 presented FPS on a fixed 120 Hz mode, for example.
            let expectedRealInterval = candidate * 2.0
            let relativeError = abs(sustainableRealInterval - expectedRealInterval)
                / expectedRealInterval
            guard relativeError <= 0.05 else {
                return InterpolationDecision(plan: nil, rejection: .fixedCadenceMismatch)
            }
        } else if candidate > display.maximumRefreshInterval + 0.000_001 {
            return InterpolationDecision(plan: nil, rejection: .realCadenceTooSlow)
        }

        interpolationSelectedInterval = stabilizedIntervalLocked(
            current: interpolationSelectedInterval,
            candidate: candidate,
            fasterConfirmations: &fasterInterpolationConfirmations
        )
        let interval = max(interpolationSelectedInterval, fastest)
        let mode: Mode = display.adaptiveSchedulingActive ? .adaptiveRefresh : .fixedRefresh
        publishTargetLocked(mode: mode, interval: interval)
        return InterpolationDecision(
            plan: Plan(
                displayGeneration: displayGeneration,
                mode: mode,
                presentationIntervalSeconds: interval,
                targetFramesPerSecond: 1.0 / interval,
                // The FI coordinator submits generated and mandatory-real
                // consecutively to one serial presentation queue.  This is
                // the sole pacing source for that pair; no parallel Mach-time
                // wait or DisplayLink callback may also delay either member.
                useTimedPresentation: true
            ),
            rejection: nil
        )
    }

    /**
     * While FI is armed but still priming/recovering cadence, keep mandatory
     * real frames at a display-derived 2x base interval.  This is deliberately
     * independent of Minecraft delta: it converges a 120-Hz producer to the
     * 60-real/120-output contract without reintroducing the old 24-FPS loop.
     */
    func frameInterpolationBaseRealPlan(
        displaySyncEnabled requestedDisplaySync: Bool
    ) -> Plan? {
        lock.lock()
        defer { lock.unlock() }
        synchronizeDisplaySyncLocked(requestedDisplaySync)
        guard enabled, requestedDisplaySync, display.isValid,
              display.maximumFramesPerSecond >= 60 else { return nil }
        let interval = quantizedIntervalLocked(
            fastestIntervalLocked() * 2.0,
            allowFixedMultiples: true
        )
        let mode: Mode = display.adaptiveSchedulingActive ? .adaptiveRefresh : .fixedRefresh
        publishTargetLocked(mode: mode, interval: interval)
        return Plan(
            displayGeneration: displayGeneration,
            mode: mode,
            presentationIntervalSeconds: interval,
            targetFramesPerSecond: 1.0 / interval,
            useTimedPresentation: true
        )
    }

    func isCurrent(_ plan: Plan) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return enabled && displaySyncEnabled && plan.displayGeneration == displayGeneration
    }

    func recordRenderCompletion(_ commandBuffer: MTLCommandBuffer) {
        guard commandBuffer.status == .completed, commandBuffer.error == nil else { return }
        let duration = commandBuffer.gpuEndTime - commandBuffer.gpuStartTime
        guard duration.isFinite, duration > 0 else { return }
        lock.lock()
        _ = renderGpuCadence.observe(duration, range: (1.0 / 10_000.0)...0.250)
        lock.unlock()
    }

    func present(
        commandBuffer: MTLCommandBuffer,
        drawable: CAMetalDrawable,
        kind: PresentationKind,
        plan: Plan
    ) {
        drawable.addPresentedHandler { [weak self] presented in
            self?.recordPresented(
                kind: kind,
                presentedTime: presented.presentedTime,
                targetInterval: plan.presentationIntervalSeconds,
                planGeneration: plan.displayGeneration
            )
        }
        if kind == .realOnly {
            commandBuffer.addCompletedHandler { [weak self] completed in
                self?.recordRenderCompletion(completed)
            }
        }

        lock.lock()
        if plan.useTimedPresentation && isPlanCurrentLocked(plan) {
            timedPresentationRequests &+= 1
            lock.unlock()
            commandBuffer.present(
                drawable,
                afterMinimumDuration: plan.presentationIntervalSeconds
            )
        } else {
            plainPresentationRequests &+= 1
            lock.unlock()
            commandBuffer.present(drawable)
        }
    }

    func snapshot() -> Snapshot {
        lock.lock()
        defer { lock.unlock() }
        var intervals: [UInt64] = []
        intervals.reserveCapacity(intervalRingCount)
        if intervalRingCount > 0 {
            let start = intervalRingCount == Self.intervalRingSize ? intervalRingCursor : 0
            for offset in 0..<intervalRingCount {
                intervals.append(intervalRing[(start + offset) % Self.intervalRingSize])
            }
        }
        return Snapshot(
            enabled: enabled,
            displayGeneration: displayGeneration,
            display: display,
            mode: currentMode,
            targetFramesPerSecond: currentTargetFramesPerSecond,
            timedPresentationRequests: timedPresentationRequests,
            plainPresentationRequests: plainPresentationRequests,
            presentedRealOnly: presentedRealOnly,
            presentedGenerated: presentedGenerated,
            presentedInterpolatedReal: presentedInterpolatedReal,
            skippedPresentations: skippedPresentations,
            stalePresentationCallbacks: stalePresentationCallbacks,
            outOfOrderPresentations: outOfOrderPresentations,
            targetMisses: targetMisses,
            rateTransitions: rateTransitions,
            meanPresentedIntervalSeconds: presentedIntervalCount > 0
                ? presentedIntervalSum / Double(presentedIntervalCount) : 0,
            presentationIntervals: intervals
        )
    }

    /** Deterministic native-harness hook for the callback-order counter. */
    func recordPresentationForTests(
        kind: PresentationKind,
        presentedTime: Double,
        targetInterval: Double
    ) {
        lock.lock()
        let generation = displayGeneration
        lock.unlock()
        recordPresented(
            kind: kind,
            presentedTime: presentedTime,
            targetInterval: targetInterval,
            planGeneration: generation
        )
    }

    private func synchronizeDisplaySyncLocked(_ requested: Bool) {
        if displaySyncEnabled != requested {
            displaySyncEnabled = requested
            resetEstimatorsLocked()
        }
    }

    private func unmanagedPlanLocked() -> Plan {
        Plan(
            displayGeneration: displayGeneration,
            mode: .unmanaged,
            presentationIntervalSeconds: 0,
            targetFramesPerSecond: 0,
            useTimedPresentation: false
        )
    }

    private func fastestIntervalLocked() -> Double {
        if display.minimumRefreshInterval > 0 {
            return display.minimumRefreshInterval
        }
        return display.maximumFramesPerSecond > 0
            ? 1.0 / Double(display.maximumFramesPerSecond) : 1.0 / 60.0
    }

    private func adaptiveSlowestIntervalLocked(
        minimumTotalFramesPerSecond: Double
    ) -> Double {
        let policyMaximum = 1.0 / minimumTotalFramesPerSecond
        if display.adaptiveSchedulingActive {
            return min(display.maximumRefreshInterval, policyMaximum)
        }
        return policyMaximum
    }

    private func quantizedIntervalLocked(
        _ interval: Double,
        allowFixedMultiples: Bool
    ) -> Double {
        let fastest = fastestIntervalLocked()
        if !display.adaptiveSchedulingActive {
            guard allowFixedMultiples else { return fastest }
            // Windowed ProMotion still reports the panel's variable interval
            // range, but macOS only enables Adaptive-Sync for fullscreen
            // content.  Treat it exactly like any other fixed refresh mode so
            // an 80 FPS FI stream is never advertised on a fixed 120 Hz scan.
            return max(fastest, ceil(interval / fastest) * fastest)
        }
        let granularity = display.displayUpdateGranularity
        guard granularity.isFinite, granularity > 0 else { return interval }
        let steps = max(0, ceil((interval - fastest) / granularity))
        return min(
            fastest + steps * granularity,
            display.maximumRefreshInterval
        )
    }

    private func stabilizedIntervalLocked(
        current: Double,
        candidate: Double,
        fasterConfirmations: inout Int
    ) -> Double {
        guard current > 0 else {
            fasterConfirmations = 0
            return candidate
        }
        let relativeDifference = abs(candidate - current) / current
        guard relativeDifference > 0.015 else {
            fasterConfirmations = 0
            return current
        }
        if candidate > current {
            // Lower refresh immediately when the workload becomes slower.
            fasterConfirmations = 0
            rateTransitions &+= 1
            return candidate
        }
        fasterConfirmations += 1
        guard fasterConfirmations >= Self.fasterTargetConfirmationSamples else {
            return current
        }
        fasterConfirmations = 0
        rateTransitions &+= 1
        return candidate
    }

    private func publishTargetLocked(mode: Mode, interval: Double) {
        currentMode = mode
        currentTargetFramesPerSecond = interval > 0 ? 1.0 / interval : 0
    }

    private func isPlanCurrentLocked(_ plan: Plan) -> Bool {
        enabled && displaySyncEnabled && plan.displayGeneration == displayGeneration
    }

    private func recordPresented(
        kind: PresentationKind,
        presentedTime: Double,
        targetInterval: Double,
        planGeneration: UInt64
    ) {
        var recordGenerated = false
        var recordInterpolatedReal = false
        lock.lock()
        guard presentedTime.isFinite, presentedTime > 0 else {
            skippedPresentations &+= 1
            lock.unlock()
            return
        }
        switch kind {
        case .realOnly:
            presentedRealOnly &+= 1
        case .generated:
            presentedGenerated &+= 1
            recordGenerated = true
        case .interpolatedReal:
            presentedInterpolatedReal &+= 1
            recordInterpolatedReal = true
        }

        // A drawable submitted before a screen/fullscreen transition can call
        // back after the new display generation is installed.  Count that it
        // really reached a display, but keep its timestamp out of the new
        // generation's cadence window.
        guard planGeneration == displayGeneration else {
            stalePresentationCallbacks &+= 1
            lock.unlock()
            if recordGenerated {
                MetallumFrameInterpolationTelemetry.shared.recordGeneratedPresentation()
            } else if recordInterpolatedReal {
                MetallumFrameInterpolationTelemetry.shared.recordRealPresentation()
            }
            return
        }

        if lastPresentedTime > 0 {
            if presentedTime + 0.000_001 < lastPresentedTime {
                // This is an on-glass order regression, not a submission
                // counter.  It makes hidden dual-presenter races visible in
                // the existing scheduler telemetry export.
                outOfOrderPresentations &+= 1
            } else if presentedTime > lastPresentedTime {
                let interval = presentedTime - lastPresentedTime
                presentedIntervalSum += interval
                presentedIntervalCount &+= 1
                let nanoseconds = UInt64(min(interval * 1_000_000_000.0, Double(UInt64.max)))
                intervalRing[intervalRingCursor] = nanoseconds
                intervalRingCursor = (intervalRingCursor + 1) % Self.intervalRingSize
                intervalRingCount = min(intervalRingCount + 1, Self.intervalRingSize)
                if targetInterval > 0, interval > targetInterval * 1.25 {
                    targetMisses &+= 1
                }
            }
        }
        lastPresentedTime = max(lastPresentedTime, presentedTime)
        lock.unlock()

        // Existing FI counters now describe frames that actually reached the
        // display, not merely command buffers that completed on the GPU.
        if recordGenerated {
            MetallumFrameInterpolationTelemetry.shared.recordGeneratedPresentation()
        } else if recordInterpolatedReal {
            MetallumFrameInterpolationTelemetry.shared.recordRealPresentation()
        }
    }

    private func resetEstimatorsLocked() {
        realOnlyCadence.reset()
        interpolationCadence.reset()
        renderGpuCadence.reset()
        realOnlySelectedInterval = 0
        interpolationSelectedInterval = 0
        fasterRealOnlyConfirmations = 0
        fasterInterpolationConfirmations = 0
        currentMode = .unmanaged
        currentTargetFramesPerSecond = 0
        lastPresentedTime = 0
        // Interval statistics describe one display/sync contract.  Keeping
        // samples from the previous screen or fullscreen state would make the
        // histogram report a transition as persistent pacing jitter.
        presentedIntervalSum = 0
        presentedIntervalCount = 0
        intervalRingCount = 0
        intervalRingCursor = 0
    }
}

/** One scheduler per CAMetalLayer; AppKit objects stay weakly referenced. */
final class MetallumExtendedProMotionSchedulerRegistry: @unchecked Sendable {
    static let shared = MetallumExtendedProMotionSchedulerRegistry()

    private final class Binding {
        weak var layer: CAMetalLayer?
        weak var view: NSView?
        let viewID: ObjectIdentifier?
        let scheduler: MetallumExtendedProMotionScheduler

        init(
            layer: CAMetalLayer,
            view: NSView?,
            scheduler: MetallumExtendedProMotionScheduler
        ) {
            self.layer = layer
            self.view = view
            self.viewID = view.map(ObjectIdentifier.init)
            self.scheduler = scheduler
        }
    }

    private let lock = NSLock()
    private var bindings: [ObjectIdentifier: Binding] = [:]

    private init() {
    }

    func bind(layer: CAMetalLayer, view: NSView) {
        let key = ObjectIdentifier(layer)
        lock.lock()
        let scheduler = bindings[key]?.scheduler ?? MetallumExtendedProMotionScheduler()
        bindings[key] = Binding(layer: layer, view: view, scheduler: scheduler)
        lock.unlock()
        if let window = view.window {
            update(window: window)
        }
    }

    func unbind(layer: CAMetalLayer) {
        lock.lock()
        bindings.removeValue(forKey: ObjectIdentifier(layer))
        lock.unlock()
    }

    func unbind(view: NSView) {
        let viewID = ObjectIdentifier(view)
        lock.lock()
        bindings = bindings.filter { _, binding in binding.viewID != viewID }
        lock.unlock()
    }

    func scheduler(for layer: CAMetalLayer) -> MetallumExtendedProMotionScheduler {
        let key = ObjectIdentifier(layer)
        lock.lock()
        if let existing = bindings[key]?.scheduler {
            lock.unlock()
            return existing
        }
        let scheduler = MetallumExtendedProMotionScheduler()
        bindings[key] = Binding(layer: layer, view: nil, scheduler: scheduler)
        lock.unlock()
        return scheduler
    }

    /** Must be called on the AppKit/main thread. */
    func update(window: NSWindow) {
        let next = Self.capture(window: window)
        lock.lock()
        let schedulers = bindings.values.compactMap { binding -> MetallumExtendedProMotionScheduler? in
            guard binding.view?.window === window else { return nil }
            return binding.scheduler
        }
        lock.unlock()
        for scheduler in schedulers {
            scheduler.updateDisplay(next)
        }
    }

    func report() -> [String: Any] {
        lock.lock()
        let scheduler = bindings.values.first?.scheduler
        lock.unlock()
        return scheduler?.snapshot().report ?? [
            "enabled": ProcessInfo.processInfo.environment["METALLUM_PROMOTION_SCHEDULER"] != "0",
            "mode": MetallumExtendedProMotionScheduler.Mode.unmanaged.rawValue
        ]
    }

    private static func capture(window: NSWindow) -> MetallumDisplayTimingSnapshot {
        guard let screen = window.screen else { return .unavailable }
        let rawDisplayID = screen.deviceDescription[
            NSDeviceDescriptionKey("NSScreenNumber")
        ] as? NSNumber
        return MetallumDisplayTimingSnapshot(
            displayID: rawDisplayID?.uint32Value ?? 0,
            maximumFramesPerSecond: max(screen.maximumFramesPerSecond, 0),
            minimumRefreshInterval: max(screen.minimumRefreshInterval, 0),
            maximumRefreshInterval: max(screen.maximumRefreshInterval, 0),
            displayUpdateGranularity: max(screen.displayUpdateGranularity, 0),
            lastDisplayUpdateTimestamp: max(screen.lastDisplayUpdateTimestamp, 0),
            fullscreen: isFullscreenPresentation(window: window, screen: screen),
            isBuiltin: rawDisplayID.map { CGDisplayIsBuiltin($0.uint32Value) != 0 } ?? false
        )
    }

    /**
     * GLFW monitor-attached fullscreen is normally a borderless NSWindow and
     * does not set AppKit's `.fullScreen` style bit.  Treating only that bit as
     * authoritative disabled ProMotion exactly in the Minecraft path.  A
     * borderless window whose frame covers its attached screen is the native
     * contract GLFW exposes; titled maximized windows deliberately stay out.
     */
    private static func isFullscreenPresentation(window: NSWindow, screen: NSScreen) -> Bool {
        if window.styleMask.contains(.fullScreen) {
            return true
        }
        guard !window.styleMask.contains(.titled) else { return false }
        let tolerance = max(1.0 / max(screen.backingScaleFactor, 1.0), 0.5)
        let frame = window.frame
        let screenFrame = screen.frame
        return abs(frame.origin.x - screenFrame.origin.x) <= tolerance
            && abs(frame.origin.y - screenFrame.origin.y) <= tolerance
            && abs(frame.width - screenFrame.width) <= tolerance
            && abs(frame.height - screenFrame.height) <= tolerance
    }
}

/** Deterministic policy and precision-timer validation loaded by the native harness. */
@_cdecl("metallum_extended_promotion_scheduler_stress_v1")
public func metallum_extended_promotion_scheduler_stress_v1() -> Int32 {
    let adaptive = MetallumDisplayTimingSnapshot(
        displayID: 1,
        maximumFramesPerSecond: 120,
        minimumRefreshInterval: 1.0 / 120.0,
        maximumRefreshInterval: 1.0 / 48.0,
        displayUpdateGranularity: 0,
        lastDisplayUpdateTimestamp: 1,
        fullscreen: true,
        isBuiltin: true
    )
    let scheduler = MetallumExtendedProMotionScheduler(enabled: true)
    scheduler.updateDisplay(adaptive)

    var decision = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil,
        rejection: .warmingUp
    )
    for _ in 0..<8 {
        decision = scheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 60.0,
            displaySyncEnabled: true
        )
    }
    guard let sixtyToOneTwenty = decision.plan,
          sixtyToOneTwenty.mode == .adaptiveRefresh,
          sixtyToOneTwenty.useTimedPresentation,
          abs(sixtyToOneTwenty.targetFramesPerSecond - 120.0) < 0.5 else {
        return -1
    }

    let adaptiveForty = MetallumExtendedProMotionScheduler(enabled: true)
    adaptiveForty.updateDisplay(adaptive)
    var fortyToEighty = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil,
        rejection: .warmingUp
    )
    for _ in 0..<8 {
        fortyToEighty = adaptiveForty.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 40.0,
            displaySyncEnabled: true
        )
    }
    guard let adaptivePlan = fortyToEighty.plan,
          adaptivePlan.mode == .adaptiveRefresh,
          abs(adaptivePlan.targetFramesPerSecond - 80.0) < 0.5 else {
        return -2
    }

    let realOnly = MetallumExtendedProMotionScheduler(enabled: true)
    realOnly.updateDisplay(adaptive)
    var realOnlyPlan = realOnly.realOnlyPlan(
        renderDeltaSeconds: 1.0 / 60.0,
        displaySyncEnabled: true
    )
    for _ in 0..<8 {
        realOnlyPlan = realOnly.realOnlyPlan(
            renderDeltaSeconds: 1.0 / 60.0,
            displaySyncEnabled: true
        )
    }
    guard realOnlyPlan.mode == .adaptiveRefresh,
          !realOnlyPlan.useTimedPresentation,
          abs(realOnlyPlan.targetFramesPerSecond - 120.0) < 0.5 else {
        return -3
    }

    guard let primingPlan = scheduler.frameInterpolationBaseRealPlan(
        displaySyncEnabled: true
    ), primingPlan.useTimedPresentation,
       abs(primingPlan.targetFramesPerSecond - 60.0) < 0.5 else {
        return -4
    }

    let jitterScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    jitterScheduler.updateDisplay(adaptive)
    var jitterDecision = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil, rejection: .warmingUp
    )
    for delta in [1.0 / 60.0, 1.0 / 60.0, 1.0 / 60.0, 1.0 / 60.0,
                  1.0 / 60.0, 1.0 / 60.0, 0.015, 0.035] {
        jitterDecision = jitterScheduler.frameInterpolationPlan(
            realDeltaSeconds: delta, displaySyncEnabled: true
        )
    }
    guard jitterDecision.plan != nil else { return -5 }

    let tooFastScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    tooFastScheduler.updateDisplay(adaptive)
    var tooFast = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil, rejection: .warmingUp
    )
    for _ in 0..<8 {
        tooFast = tooFastScheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 90.0, displaySyncEnabled: true
        )
    }
    guard tooFast.plan == nil, tooFast.rejection == .realCadenceTooFast else {
        return -6
    }
    let noSync = scheduler.frameInterpolationPlan(
        realDeltaSeconds: 1.0 / 60.0,
        displaySyncEnabled: false
    )
    guard noSync.plan == nil, noSync.rejection == .displaySyncDisabled else {
        return -7
    }

    let unmanagedRealOnly = realOnly.realOnlyPlan(
        renderDeltaSeconds: 1.0 / 60.0,
        displaySyncEnabled: false
    )
    guard unmanagedRealOnly.mode == .unmanaged,
          !unmanagedRealOnly.useTimedPresentation else {
        return -8
    }

    let windowed = MetallumDisplayTimingSnapshot(
        displayID: 1,
        maximumFramesPerSecond: 120,
        minimumRefreshInterval: 1.0 / 120.0,
        maximumRefreshInterval: 1.0 / 48.0,
        displayUpdateGranularity: 0,
        lastDisplayUpdateTimestamp: 2,
        fullscreen: false,
        isBuiltin: false
    )
    let windowedScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    windowedScheduler.updateDisplay(windowed)
    let windowedRealOnly = windowedScheduler.realOnlyPlan(
        renderDeltaSeconds: 1.0 / 60.0,
        displaySyncEnabled: true
    )
    guard windowedRealOnly.mode == .fixedRefresh,
          !windowedRealOnly.useTimedPresentation else {
        return -9
    }
    var invalidWindowedFi = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil,
        rejection: .warmingUp
    )
    for _ in 0..<8 {
        invalidWindowedFi = windowedScheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 40.0,
            displaySyncEnabled: true
        )
    }
    guard invalidWindowedFi.plan == nil,
          invalidWindowedFi.rejection == .fixedCadenceMismatch else {
        return -10
    }

    scheduler.updateDisplay(MetallumDisplayTimingSnapshot(
        displayID: 2,
        maximumFramesPerSecond: 60,
        minimumRefreshInterval: 1.0 / 60.0,
        maximumRefreshInterval: 1.0 / 60.0,
        displayUpdateGranularity: 1.0 / 60.0,
        lastDisplayUpdateTimestamp: 2,
        fullscreen: true,
        isBuiltin: true
    ))
    var fixedDecision = scheduler.frameInterpolationPlan(
        realDeltaSeconds: 1.0 / 30.0,
        displaySyncEnabled: true
    )
    for _ in 0..<7 {
        fixedDecision = scheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 30.0,
            displaySyncEnabled: true
        )
    }
    guard let fixedPlan = fixedDecision.plan,
          fixedPlan.mode == .fixedRefresh,
          abs(fixedPlan.targetFramesPerSecond - 60.0) < 0.5 else {
        return -11
    }
    guard !scheduler.isCurrent(sixtyToOneTwenty) else { return -12 }

    let callbackOrder = MetallumExtendedProMotionScheduler(enabled: true)
    callbackOrder.updateDisplay(adaptive)
    callbackOrder.recordPresentationForTests(
        kind: .generated, presentedTime: 10.0, targetInterval: 1.0 / 120.0
    )
    callbackOrder.recordPresentationForTests(
        kind: .interpolatedReal, presentedTime: 10.0 + 1.0 / 120.0,
        targetInterval: 1.0 / 120.0
    )
    guard callbackOrder.snapshot().outOfOrderPresentations == 0 else { return -13 }
    callbackOrder.recordPresentationForTests(
        kind: .generated, presentedTime: 10.0 + 0.5 / 120.0,
        targetInterval: 1.0 / 120.0
    )
    guard callbackOrder.snapshot().outOfOrderPresentations == 1 else { return -14 }

    let timer = MetallumPrecisionDeadlineTimer()
    let start = MetallumMonotonicClock.nowNanoseconds()
    guard timer.wait(untilNanoseconds: start + 2_000_000) else { return -11 }
    let elapsed = MetallumMonotonicClock.nowNanoseconds() - start
    guard elapsed >= 1_500_000, elapsed < 500_000_000 else { return -12 }
    return 1
}
