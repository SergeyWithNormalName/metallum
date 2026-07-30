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
        /** On-glass cadence target used by telemetry and pair scheduling. */
        let presentationIntervalSeconds: Double
        let targetFramesPerSecond: Double
        /** CAMetalLayer safety floor; midpoint pacing is owned separately. */
        let minimumPresentationDurationSeconds: Double
        let useTimedPresentation: Bool
    }

    struct InterpolationDecision: Sendable {
        let plan: Plan?
        let rejection: InterpolationRejection?
    }

    /**
     * One immutable observation from CAMetalDrawable's presented callback.
     * Coordinator policy may consume it after the scheduler lock is released;
     * command-buffer completion and CPU submission time never enter this ABI.
     */
    struct OnGlassPresentationFeedback: Sendable {
        let currentGeneration: Bool
        let validTimestamp: Bool
        let trackedPresentation: Bool
        let comparableFiInterval: Bool
        let unexpectedFiTransition: Bool
        let targetMissed: Bool
        let severeLate: Bool
        let outOfOrder: Bool
        let retargetBoundary: Bool
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
        let retargetBoundaries: UInt64
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
                "retarget_boundaries": retargetBoundaries,
                "rate_transitions": rateTransitions,
                "mean_present_interval_ms": meanPresentedIntervalSeconds * 1_000.0,
                "presentation_interval_histogram_buckets": bucketCount
            ]
        }
    }

    private struct CadenceEstimator {
        private static let admissionWindowSize = 16

        private(set) var sampleCount = 0
        private(set) var mean = 0.0
        private(set) var deviation = 0.0
        private var admissionWindow = [Double](
            repeating: 0,
            count: CadenceEstimator.admissionWindowSize
        )
        private var admissionWindowCount = 0
        private var admissionWindowCursor = 0
        private var admissionWindowSum = 0.0

        mutating func reset() {
            sampleCount = 0
            mean = 0
            deviation = 0
            admissionWindowCount = 0
            admissionWindowCursor = 0
            admissionWindowSum = 0
        }

        @discardableResult
        mutating func observe(_ sample: Double, range: ClosedRange<Double>) -> Bool {
            guard sample.isFinite, range.contains(sample) else { return false }
            if admissionWindowCount == Self.admissionWindowSize {
                admissionWindowSum -= admissionWindow[admissionWindowCursor]
            } else {
                admissionWindowCount += 1
            }
            admissionWindow[admissionWindowCursor] = sample
            admissionWindowSum += sample
            admissionWindowCursor = (admissionWindowCursor + 1) % Self.admissionWindowSize
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

        /** Arithmetic source-rate estimate without the EMA's deliberate late-sample bias. */
        var admissionMean: Double {
            admissionWindowCount > 0
                ? admissionWindowSum / Double(admissionWindowCount) : 0
        }

        var sustainableInterval: Double {
            guard sampleCount > 0 else { return 0 }
            // Keep a small jitter reserve without turning a single spike into
            // a permanent low-rate mode.
            return mean + min(deviation * 0.5, mean * 0.02)
        }
    }

    private static let intervalRingSize = 128
    private static let interpolationWarmupSamples = 8
    private static let fasterTargetConfirmationSamples = 8
    private static let interpolationAdmissionConfirmationSamples = 8
    static let minimumInterpolationSourceInterval = 1.0 / 240.0
    /**
     * History continuity and sustainable cadence are different contracts.
     * The scheduler accepts individual CPU hand-off samples as far as 20 FPS;
     * the coordinator separately decides whether such a gap preserves image
     * history. A rolling arithmetic mean decides whether the admitted stream
     * still satisfies the nominal 30 -> 60 policy.
     */
    static let nominalMaximumInterpolationSourceInterval = 1.0 / 30.0
    private static let interpolationSourceJitterAllowance = 0.02
    static let maximumSustainableInterpolationSourceInterval =
        nominalMaximumInterpolationSourceInterval * (1.0 + interpolationSourceJitterAllowance)
    static let maximumInterpolationSourceSampleInterval = 1.0 / 20.0

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
    private var interpolationCadenceArmed = false
    private var interpolationCadenceGoodWindows = 0
    private var interpolationCadenceSlowWindows = 0
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
    private var retargetBoundaries: UInt64 = 0
    private var rateTransitions: UInt64 = 0
    private var lastPresentedTime = 0.0
    private var presentedIntervalSum = 0.0
    private var presentedIntervalCount: UInt64 = 0
    private var intervalRing = [UInt64](repeating: 0, count: intervalRingSize)
    private var intervalRingCount = 0
    private var intervalRingCursor = 0
    private var lastPresentationTrackedTarget = false
    private var lastPresentationTargetInterval = 0.0
    private var lastPresentationKind: PresentationKind?

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
            displayGeneration &+= 1
            resetEstimatorsLocked()
        }
    }

    /**
     * A renderer/FI history discontinuity also invalidates the cadence window.
     * Keeping pre-stall samples would allow a new history chain to bypass the
     * stable-source warm-up required by MetalFX.
     */
    func resetFrameInterpolationCadence(preservingAcceptedPlans: Bool = false) {
        lock.lock()
        // A source discontinuity belongs to the new/future history chain.  It
        // must not cancel an older G_N/R_N pair that was valid when accepted
        // and is already queued for presentation.  Display/VSync mutations,
        // on the other hand, invalidate every outstanding plan.
        if !preservingAcceptedPlans {
            displayGeneration &+= 1
        }
        resetEstimatorsLocked()
        lock.unlock()
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
            minimumPresentationDurationSeconds: 0,
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
        guard realDeltaSeconds.isFinite,
              realDeltaSeconds >= Self.minimumInterpolationSourceInterval else {
            return InterpolationDecision(plan: nil, rejection: .realCadenceTooFast)
        }
        guard realDeltaSeconds <= Self.maximumInterpolationSourceSampleInterval else {
            return InterpolationDecision(plan: nil, rejection: .realCadenceTooSlow)
        }
        let fastest = fastestIntervalLocked()
        guard interpolationCadence.observe(
            realDeltaSeconds,
            // This is the CPU hand-off interval between sequential sources.
            // Sustained cadence is decided by the unbiased window below;
            // one late software-limiter wake is not a history discontinuity.
            range: Self.minimumInterpolationSourceInterval...Self.maximumInterpolationSourceSampleInterval
        ) else {
            return InterpolationDecision(plan: nil, rejection: .realCadenceTooSlow)
        }
        let sustainableCadence = interpolationCadence.admissionMean
            <= Self.maximumSustainableInterpolationSourceInterval
        updateInterpolationAdmissionLocked(sustainable: sustainableCadence)
        guard interpolationCadence.sampleCount >= Self.interpolationWarmupSamples else {
            return InterpolationDecision(plan: nil, rejection: .warmingUp)
        }
        guard interpolationCadenceArmed else {
            return InterpolationDecision(
                plan: nil,
                rejection: sustainableCadence ? .warmingUp : .realCadenceTooSlow
            )
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
        var candidate = max(sustainableRealInterval * 0.5, fastest)
        // MetalFX input must remain at least 30 real FPS, so the synthesized
        // presentation stream never intentionally falls below 60 FPS.
        candidate = min(candidate, 1.0 / 60.0)
        candidate = quantizedIntervalLocked(
            candidate,
            allowFixedMultiples: true,
            useNearestAdaptiveStep: true
        )

        if !display.adaptiveSchedulingActive {
            // A fixed display can only show evenly spaced factor cadences.  Do
            // not claim 80 presented FPS on a fixed 120 Hz mode, for example.
            let expectedRealInterval = candidate * 2.0
            // Fixed-mode admission is a source-rate question. Reusing the
            // deliberately late-biased target estimator here rejects an
            // otherwise healthy 30-FPS software limiter on a fixed 120-Hz
            // display even though its arithmetic mean matches 2x cadence.
            let relativeError = abs(interpolationCadence.admissionMean - expectedRealInterval)
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
                // Apple's PresentThread uses the panel minimum here.  The
                // measured half-source midpoint is enforced by the dedicated
                // pacing gate; reusing `interval` would delay the real member
                // twice and turn 40 -> 80 back into roughly 30 -> 60.
                minimumPresentationDurationSeconds: fastest,
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
     * real frames coordinator-owned but do not add a second FPS limiter. The
     * ordinary VSync path already selects a legal vblank; timed presentation
     * begins only after a generated/real pair has been admitted.
     */
    func frameInterpolationBaseRealPlan(
        displaySyncEnabled requestedDisplaySync: Bool
    ) -> Plan? {
        lock.lock()
        defer { lock.unlock() }
        synchronizeDisplaySyncLocked(requestedDisplaySync)
        guard enabled, requestedDisplaySync, display.isValid,
              display.maximumFramesPerSecond >= 60 else { return nil }
        // This interval describes the expected cadence of the one real member
        // while FI is recovering (60 real on a 120-Hz panel). It remains
        // telemetry only because useTimedPresentation is false below.
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
            minimumPresentationDurationSeconds: 0,
            useTimedPresentation: false
        )
    }

    func isCurrent(_ plan: Plan) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return enabled && displaySyncEnabled && plan.displayGeneration == displayGeneration
    }

    /**
     * The generated drawable is encoded after the FI-ready CPU callback, so
     * its first eligible scan boundary trails the timer anchor. One quarter of
     * the panel update step compensates that phase without adding another full
     * refresh opportunity or changing fixed-refresh pacing.
     */
    func midpointSubmissionCompensationSeconds(for plan: Plan) -> Double {
        lock.lock()
        defer { lock.unlock() }
        guard enabled,
              displaySyncEnabled,
              plan.displayGeneration == displayGeneration,
              display.adaptiveSchedulingActive else {
            return 0
        }
        return max(display.displayUpdateGranularity, 0) * 0.25
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
        plan: Plan,
        onPresented: ((OnGlassPresentationFeedback) -> Void)? = nil
    ) {
        drawable.addPresentedHandler { [weak self] presented in
            // Pool ownership and cadence accounting are separate contracts.
            // A stale-generation or zero-time callback can be unusable for
            // cadence telemetry while still being the exact physical signal
            // that this drawable reached the display.
            guard let feedback = self?.recordPresented(
                kind: kind,
                presentedTime: presented.presentedTime,
                targetInterval: plan.presentationIntervalSeconds,
                planGeneration: plan.displayGeneration,
                tracksTarget: plan.useTimedPresentation
            ) else { return }
            onPresented?(feedback)
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
                afterMinimumDuration: plan.minimumPresentationDurationSeconds
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
            retargetBoundaries: retargetBoundaries,
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
        targetInterval: Double,
        tracksTarget: Bool = true
    ) {
        lock.lock()
        let generation = displayGeneration
        lock.unlock()
        _ = recordPresented(
            kind: kind,
            presentedTime: presentedTime,
            targetInterval: targetInterval,
            planGeneration: generation,
            tracksTarget: tracksTarget
        )
    }

    private func synchronizeDisplaySyncLocked(_ requested: Bool) {
        if displaySyncEnabled != requested {
            displaySyncEnabled = requested
            displayGeneration &+= 1
            resetEstimatorsLocked()
        }
    }

    private func unmanagedPlanLocked() -> Plan {
        Plan(
            displayGeneration: displayGeneration,
            mode: .unmanaged,
            presentationIntervalSeconds: 0,
            targetFramesPerSecond: 0,
            minimumPresentationDurationSeconds: 0,
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
        allowFixedMultiples: Bool,
        useNearestAdaptiveStep: Bool = false
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
        let rawSteps = max(0, (interval - fastest) / granularity)
        // A directional ceil/floor is unstable at a legal cadence boundary:
        // 24.8 ms of source time would floor to 120 output FPS, while 25.6 ms
        // would ceil to 60.  Both are ordinary jitter around the same 40 -> 80
        // contract.  Choose the nearest ProMotion step; fixed refresh keeps
        // its separate exact-multiple policy above.
        let steps = useNearestAdaptiveStep
            ? floor(rawSteps + 0.5)
            : ceil(rawSteps - 0.000_001)
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

    /**
     * A small cadence latch prevents a rolling window on the 30-FPS boundary
     * from enabling/disabling FI every other source. Initial warm-up supplies
     * the same eight good windows; a sustained slowdown or recovery must then
     * remain visible for eight consecutive window updates.
     */
    private func updateInterpolationAdmissionLocked(sustainable: Bool) {
        if interpolationCadenceArmed {
            interpolationCadenceGoodWindows = 0
            if sustainable {
                interpolationCadenceSlowWindows = 0
                return
            }
            interpolationCadenceSlowWindows += 1
            if interpolationCadenceSlowWindows
                >= Self.interpolationAdmissionConfirmationSamples {
                interpolationCadenceArmed = false
                interpolationCadenceSlowWindows = 0
            }
            return
        }
        interpolationCadenceSlowWindows = 0
        if sustainable {
            interpolationCadenceGoodWindows += 1
            if interpolationCadenceGoodWindows
                >= Self.interpolationAdmissionConfirmationSamples {
                interpolationCadenceArmed = true
                interpolationCadenceGoodWindows = 0
            }
        } else {
            interpolationCadenceGoodWindows = 0
        }
    }

    private func recordPresented(
        kind: PresentationKind,
        presentedTime: Double,
        targetInterval: Double,
        planGeneration: UInt64,
        tracksTarget: Bool
    ) -> OnGlassPresentationFeedback {
        var recordGenerated = false
        var recordInterpolatedReal = false
        var recordRealOnly = false
        var recordOutOfOrder = false
        var recordTargetMiss = false
        var recordRetargetBoundary = false
        var comparableFiInterval = false
        var unexpectedFiTransition = false
        var severeLate = false
        var timedIntervalRecord: (
            seconds: Double,
            targetSeconds: Double,
            meanSlackSeconds: Double,
            transition: Int,
            missed: Bool,
            severe: Bool
        )?
        lock.lock()
        guard presentedTime.isFinite, presentedTime > 0 else {
            skippedPresentations &+= 1
            let currentGeneration = planGeneration == displayGeneration
            lock.unlock()
            return OnGlassPresentationFeedback(
                currentGeneration: currentGeneration,
                validTimestamp: false,
                trackedPresentation: tracksTarget,
                comparableFiInterval: false,
                unexpectedFiTransition: false,
                targetMissed: false,
                severeLate: false,
                outOfOrder: false,
                retargetBoundary: false
            )
        }
        switch kind {
        case .realOnly:
            presentedRealOnly &+= 1
            recordRealOnly = true
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
            } else if recordRealOnly {
                MetallumFrameInterpolationTelemetry.shared.recordRealOnlyPresentation()
            }
            return OnGlassPresentationFeedback(
                currentGeneration: false,
                validTimestamp: true,
                trackedPresentation: tracksTarget,
                comparableFiInterval: false,
                unexpectedFiTransition: false,
                targetMissed: false,
                severeLate: false,
                outOfOrder: false,
                retargetBoundary: false
            )
        }

        if lastPresentedTime > 0 {
            let sameTarget = tracksTarget
                && lastPresentationTrackedTarget
                && targetInterval > 0
                && lastPresentationTargetInterval > 0
                && abs(targetInterval - lastPresentationTargetInterval) <= 0.000_001
            let alternatingFiKinds = (lastPresentationKind == .generated
                    && kind == .interpolatedReal)
                || (lastPresentationKind == .interpolatedReal && kind == .generated)
            let bothFiKinds = (lastPresentationKind == .generated
                    || lastPresentationKind == .interpolatedReal)
                && (kind == .generated || kind == .interpolatedReal)
            unexpectedFiTransition = sameTarget && bothFiKinds && !alternatingFiKinds
            if presentedTime + 0.000_001 < lastPresentedTime {
                // This is an on-glass order regression, not a submission
                // counter.  It makes hidden dual-presenter races visible in
                // the existing scheduler telemetry export.
                outOfOrderPresentations &+= 1
                recordOutOfOrder = true
                comparableFiInterval = sameTarget && alternatingFiKinds
                severeLate = comparableFiInterval
            } else if presentedTime > lastPresentedTime {
                let interval = presentedTime - lastPresentedTime
                presentedIntervalSum += interval
                presentedIntervalCount &+= 1
                let nanoseconds = UInt64(min(interval * 1_000_000_000.0, Double(UInt64.max)))
                intervalRing[intervalRingCursor] = nanoseconds
                intervalRingCursor = (intervalRingCursor + 1) % Self.intervalRingSize
                intervalRingCount = min(intervalRingCount + 1, Self.intervalRingSize)
                // A ProMotion panel can realize an intermediate average rate
                // by alternating the two adjacent hardware intervals.  For
                // example, its advertised 4.1667-ms granularity permits an
                // 80-Hz stream as 8.33/16.67 ms around a 12.5-ms target.  The
                // old percentage-only threshold falsely called every upper
                // legal step a miss.  Allow exactly one adaptive hardware
                // step (plus timestamp epsilon); a skipped additional step is
                // still counted and remains a benchmark failure signal.
                let adaptiveStep = display.adaptiveSchedulingActive
                    ? max(display.displayUpdateGranularity, 0) : 0
                let targetTolerance = max(
                    targetInterval * 0.25,
                    adaptiveStep + 0.000_250
                )
                if sameTarget,
                   interval > targetInterval + targetTolerance {
                    targetMisses &+= 1
                    recordTargetMiss = true
                }
                if tracksTarget, lastPresentationTrackedTarget, !sameTarget {
                    retargetBoundaries &+= 1
                    recordRetargetBoundary = true
                }
                if sameTarget {
                    let transition: Int
                    if lastPresentationKind == .generated, kind == .interpolatedReal {
                        transition = 1
                    } else if lastPresentationKind == .interpolatedReal, kind == .generated {
                        transition = 2
                    } else {
                        transition = 0
                    }
                    let meanSlack = max(adaptiveStep * 0.25, 0.000_250)
                    let severeTolerance = max(
                        targetInterval * 0.50,
                        adaptiveStep * 2.0 + 0.000_250
                    )
                    comparableFiInterval = transition != 0
                    severeLate = comparableFiInterval
                        && interval > targetInterval + severeTolerance
                    timedIntervalRecord = (
                        seconds: interval,
                        targetSeconds: targetInterval,
                        meanSlackSeconds: meanSlack,
                        transition: transition,
                        missed: recordTargetMiss,
                        severe: severeLate
                    )
                }
            }
        }
        lastPresentedTime = max(lastPresentedTime, presentedTime)
        lastPresentationTrackedTarget = tracksTarget
        lastPresentationTargetInterval = tracksTarget ? targetInterval : 0
        lastPresentationKind = kind
        lock.unlock()

        // Existing FI counters now describe frames that actually reached the
        // display, not merely command buffers that completed on the GPU.
        if recordGenerated {
            MetallumFrameInterpolationTelemetry.shared.recordGeneratedPresentation()
        } else if recordInterpolatedReal {
            MetallumFrameInterpolationTelemetry.shared.recordRealPresentation()
        } else if recordRealOnly {
            MetallumFrameInterpolationTelemetry.shared.recordRealOnlyPresentation()
        }
        if recordOutOfOrder {
            MetallumFrameInterpolationTelemetry.shared.recordOutOfOrderPresentation()
        }
        if recordTargetMiss {
            MetallumFrameInterpolationTelemetry.shared.recordTargetMiss()
        }
        if recordRetargetBoundary {
            MetallumFrameInterpolationTelemetry.shared.recordRetargetBoundary()
        }
        if let timedIntervalRecord {
            MetallumFrameInterpolationTelemetry.shared.recordTimedInterval(
                seconds: timedIntervalRecord.seconds,
                targetSeconds: timedIntervalRecord.targetSeconds,
                meanSlackSeconds: timedIntervalRecord.meanSlackSeconds,
                transition: timedIntervalRecord.transition,
                missed: timedIntervalRecord.missed,
                severe: timedIntervalRecord.severe
            )
        }
        return OnGlassPresentationFeedback(
            currentGeneration: true,
            validTimestamp: true,
            trackedPresentation: tracksTarget,
            comparableFiInterval: comparableFiInterval,
            unexpectedFiTransition: unexpectedFiTransition,
            targetMissed: recordTargetMiss,
            severeLate: severeLate,
            outOfOrder: recordOutOfOrder,
            retargetBoundary: recordRetargetBoundary
        )
    }

    private func resetEstimatorsLocked() {
        realOnlyCadence.reset()
        interpolationCadence.reset()
        renderGpuCadence.reset()
        realOnlySelectedInterval = 0
        interpolationSelectedInterval = 0
        fasterRealOnlyConfirmations = 0
        fasterInterpolationConfirmations = 0
        interpolationCadenceArmed = false
        interpolationCadenceGoodWindows = 0
        interpolationCadenceSlowWindows = 0
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
        lastPresentationTrackedTarget = false
        lastPresentationTargetInterval = 0
        lastPresentationKind = nil
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
          abs(sixtyToOneTwenty.minimumPresentationDurationSeconds - 1.0 / 120.0)
            < 0.000_001,
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

    // Real ProMotion panels advertise 1/240-second update granularity.  A
    // small sustainable-cadence reserve above 25 ms must stay at the faster
    // 12.5-ms step (40 -> 80), never round up to a self-throttling 16.67 ms.
    let granularAdaptive = MetallumDisplayTimingSnapshot(
        displayID: 1,
        maximumFramesPerSecond: 120,
        minimumRefreshInterval: 1.0 / 120.0,
        maximumRefreshInterval: 1.0 / 24.0,
        displayUpdateGranularity: 1.0 / 240.0,
        lastDisplayUpdateTimestamp: 1,
        fullscreen: true,
        isBuiltin: true
    )
    let granularForty = MetallumExtendedProMotionScheduler(enabled: true)
    granularForty.updateDisplay(granularAdaptive)
    var granularDecision = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil,
        rejection: .warmingUp
    )
    for _ in 0..<8 {
        granularDecision = granularForty.frameInterpolationPlan(
            realDeltaSeconds: 0.0256,
            displaySyncEnabled: true
        )
    }
    guard let granularPlan = granularDecision.plan,
          abs(granularPlan.targetFramesPerSecond - 80.0) < 0.5,
          abs(granularForty.midpointSubmissionCompensationSeconds(for: granularPlan)
                - 1.0 / 960.0) < 0.000_001 else {
        return -24
    }
    let granularFortyFastSide = MetallumExtendedProMotionScheduler(enabled: true)
    granularFortyFastSide.updateDisplay(granularAdaptive)
    var granularFastDecision = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil,
        rejection: .warmingUp
    )
    for _ in 0..<8 {
        granularFastDecision = granularFortyFastSide.frameInterpolationPlan(
            realDeltaSeconds: 0.0248,
            displaySyncEnabled: true
        )
    }
    guard let granularFastPlan = granularFastDecision.plan,
          abs(granularFastPlan.targetFramesPerSecond - 80.0) < 0.5 else {
        return -31
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
    ), !primingPlan.useTimedPresentation,
       primingPlan.minimumPresentationDurationSeconds == 0,
       abs(primingPlan.targetFramesPerSecond - 60.0) < 0.5 else {
        return -4
    }

    let resetScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    resetScheduler.updateDisplay(adaptive)
    var preResetPlan: MetallumExtendedProMotionScheduler.Plan?
    for _ in 0..<8 {
        preResetPlan = resetScheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 60.0,
            displaySyncEnabled: true
        ).plan
    }
    guard let preResetPlan else { return -17 }
    resetScheduler.resetFrameInterpolationCadence()
    guard !resetScheduler.isCurrent(preResetPlan),
          resetScheduler.snapshot().mode == .unmanaged,
          resetScheduler.snapshot().targetFramesPerSecond == 0 else {
        return -18
    }
    for sample in 1...8 {
        let recovered = resetScheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 60.0,
            displaySyncEnabled: true
        )
        if sample < 8 {
            guard recovered.plan == nil, recovered.rejection == .warmingUp else { return -19 }
        } else {
            guard let plan = recovered.plan,
                  plan.displayGeneration != preResetPlan.displayGeneration,
                  abs(plan.targetFramesPerSecond - 120.0) < 0.5 else { return -20 }
        }
    }

    let futureResetScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    futureResetScheduler.updateDisplay(adaptive)
    var acceptedBeforeFutureReset: MetallumExtendedProMotionScheduler.Plan?
    for _ in 0..<8 {
        acceptedBeforeFutureReset = futureResetScheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 60.0,
            displaySyncEnabled: true
        ).plan
    }
    guard let acceptedBeforeFutureReset else { return -25 }
    futureResetScheduler.resetFrameInterpolationCadence(preservingAcceptedPlans: true)
    guard futureResetScheduler.isCurrent(acceptedBeforeFutureReset),
          futureResetScheduler.snapshot().mode == .unmanaged else {
        return -26
    }

    let jitterScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    jitterScheduler.updateDisplay(adaptive)
    var jitterDecision = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil, rejection: .warmingUp
    )
    for delta in [1.0 / 60.0, 1.0 / 60.0, 1.0 / 60.0, 1.0 / 60.0,
                  1.0 / 60.0, 1.0 / 60.0, 0.015, 0.032] {
        jitterDecision = jitterScheduler.frameInterpolationPlan(
            realDeltaSeconds: delta, displaySyncEnabled: true
        )
    }
    guard jitterDecision.plan != nil else { return -5 }

    let tooSlowScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    tooSlowScheduler.updateDisplay(adaptive)
    var tooSlow = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil, rejection: .warmingUp
    )
    for _ in 0..<8 {
        tooSlow = tooSlowScheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 25.0,
            displaySyncEnabled: true
        )
    }
    guard tooSlow.plan == nil, tooSlow.rejection == .realCadenceTooSlow,
          let tooSlowBase = tooSlowScheduler.frameInterpolationBaseRealPlan(
            displaySyncEnabled: true
          ), !tooSlowBase.useTimedPresentation else {
        return -21
    }

    // Reproduce the measured limiter shape: 30% of individual samples are
    // slower than 34 ms while the unbiased mean remains 33.441 ms. Consecutive
    // late wakes must not erase history or interrupt the 30 -> 60 plan.
    let limiterJitterScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    limiterJitterScheduler.updateDisplay(adaptive)
    var limiterJitterDecision = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil, rejection: .warmingUp
    )
    for _ in 0..<8 {
        limiterJitterDecision = limiterJitterScheduler.frameInterpolationPlan(
            realDeltaSeconds: 0.033_44,
            displaySyncEnabled: true
        )
    }
    guard limiterJitterDecision.plan != nil else { return -32 }
    for index in 0..<300 {
        let delta = index % 10 < 3 ? 0.035_45 : 0.032_58
        limiterJitterDecision = limiterJitterScheduler.frameInterpolationPlan(
            realDeltaSeconds: delta,
            displaySyncEnabled: true
        )
        guard let limiterJitterPlan = limiterJitterDecision.plan,
              abs(limiterJitterPlan.targetFramesPerSecond - 60.0) < 0.5 else {
            return -32
        }
    }

    let belowMinimumRateScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    belowMinimumRateScheduler.updateDisplay(adaptive)
    var belowMinimumRate = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil, rejection: .warmingUp
    )
    for _ in 0..<32 {
        belowMinimumRate = belowMinimumRateScheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 29.0,
            displaySyncEnabled: true
        )
    }
    guard belowMinimumRate.plan == nil,
          belowMinimumRate.rejection == .realCadenceTooSlow else {
        return -33
    }

    let cadenceRecoveryScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    cadenceRecoveryScheduler.updateDisplay(adaptive)
    var cadenceRecovery = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil, rejection: .warmingUp
    )
    for _ in 0..<8 {
        cadenceRecovery = cadenceRecoveryScheduler.frameInterpolationPlan(
            realDeltaSeconds: 0.033_44,
            displaySyncEnabled: true
        )
    }
    guard cadenceRecovery.plan != nil else { return -34 }
    for _ in 0..<32 {
        cadenceRecovery = cadenceRecoveryScheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 29.0,
            displaySyncEnabled: true
        )
    }
    guard cadenceRecovery.plan == nil,
          cadenceRecovery.rejection == .realCadenceTooSlow else {
        return -35
    }
    for _ in 0..<32 {
        cadenceRecovery = cadenceRecoveryScheduler.frameInterpolationPlan(
            realDeltaSeconds: 0.033_44,
            displaySyncEnabled: true
        )
    }
    guard let recoveredCadencePlan = cadenceRecovery.plan,
          abs(recoveredCadencePlan.targetFramesPerSecond - 60.0) < 0.5 else {
        return -36
    }

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
    guard !scheduler.isCurrent(sixtyToOneTwenty) else { return -22 }
    var resynchronizedPlan: MetallumExtendedProMotionScheduler.Plan?
    for _ in 0..<8 {
        resynchronizedPlan = scheduler.frameInterpolationPlan(
            realDeltaSeconds: 1.0 / 60.0,
            displaySyncEnabled: true
        ).plan
    }
    guard let resynchronizedPlan,
          resynchronizedPlan.displayGeneration != sixtyToOneTwenty.displayGeneration else {
        return -23
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
    let fixedLimiterJitterScheduler = MetallumExtendedProMotionScheduler(enabled: true)
    fixedLimiterJitterScheduler.updateDisplay(windowed)
    var fixedLimiterJitter = MetallumExtendedProMotionScheduler.InterpolationDecision(
        plan: nil,
        rejection: .warmingUp
    )
    for index in 0..<300 {
        fixedLimiterJitter = fixedLimiterJitterScheduler.frameInterpolationPlan(
            realDeltaSeconds: index % 10 < 3 ? 0.035_45 : 0.032_58,
            displaySyncEnabled: true
        )
        if index >= 15 {
            guard let plan = fixedLimiterJitter.plan,
                  plan.mode == .fixedRefresh,
                  abs(plan.targetFramesPerSecond - 60.0) < 0.5 else {
                return -36
            }
        }
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
    let generatedBefore = metallum_frame_interpolation_presented_generated_count_v1()
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
    guard metallum_frame_interpolation_presented_generated_count_v1()
            == generatedBefore + 2 else {
        return -15
    }
    callbackOrder.recordPresentationForTests(
        kind: .generated, presentedTime: 0, targetInterval: 1.0 / 120.0
    )
    guard metallum_frame_interpolation_presented_generated_count_v1()
            == generatedBefore + 2 else {
        return -16
    }

    let targetBoundary = MetallumExtendedProMotionScheduler(enabled: true)
    targetBoundary.updateDisplay(adaptive)
    targetBoundary.recordPresentationForTests(
        kind: .realOnly,
        presentedTime: 20.0,
        targetInterval: 1.0 / 40.0,
        tracksTarget: false
    )
    targetBoundary.recordPresentationForTests(
        kind: .generated,
        presentedTime: 20.025,
        targetInterval: 1.0 / 80.0
    )
    guard targetBoundary.snapshot().targetMisses == 0 else { return -27 }
    targetBoundary.recordPresentationForTests(
        kind: .interpolatedReal,
        presentedTime: 20.050,
        targetInterval: 1.0 / 80.0
    )
    guard targetBoundary.snapshot().targetMisses == 1 else { return -28 }

    let granularTarget = MetallumExtendedProMotionScheduler(enabled: true)
    granularTarget.updateDisplay(granularAdaptive)
    granularTarget.recordPresentationForTests(
        kind: .generated,
        presentedTime: 30.0,
        targetInterval: 1.0 / 80.0
    )
    granularTarget.recordPresentationForTests(
        kind: .interpolatedReal,
        presentedTime: 30.0 + 1.0 / 60.0,
        targetInterval: 1.0 / 80.0
    )
    guard granularTarget.snapshot().targetMisses == 0 else { return -29 }
    granularTarget.recordPresentationForTests(
        kind: .generated,
        presentedTime: 30.0 + 1.0 / 60.0 + 1.0 / 48.0,
        targetInterval: 1.0 / 80.0
    )
    guard granularTarget.snapshot().targetMisses == 1 else { return -30 }

    let retargeted = MetallumExtendedProMotionScheduler(enabled: true)
    retargeted.updateDisplay(granularAdaptive)
    retargeted.recordPresentationForTests(
        kind: .generated,
        presentedTime: 40.0,
        targetInterval: 1.0 / 60.0
    )
    retargeted.recordPresentationForTests(
        kind: .interpolatedReal,
        presentedTime: 40.025,
        targetInterval: 1.0 / 80.0
    )
    guard retargeted.snapshot().targetMisses == 0,
          retargeted.snapshot().retargetBoundaries == 1 else { return -31 }

    let timer = MetallumPrecisionDeadlineTimer()
    let start = MetallumMonotonicClock.nowNanoseconds()
    guard timer.wait(untilNanoseconds: start + 2_000_000) else { return -11 }
    let elapsed = MetallumMonotonicClock.nowNanoseconds() - start
    guard elapsed >= 1_500_000, elapsed < 500_000_000 else { return -12 }
    return 1
}
