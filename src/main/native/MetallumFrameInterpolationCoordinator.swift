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
        let realOnlyPresentations: Int
        let droppedGeneratedLate: Int
        let backpressureDrops: Int
        let sourceAttempts: Int
        let schedulerAcceptedFrames: Int
        let schedulerWarmingFrames: Int
        let schedulerTooSlowFrames: Int
        let schedulerTooFastFrames: Int
        let coordinatorRealOnlyFrames: Int
        let interpolationFailures: Int
        let rawCadenceTooSlowFrames: Int
        let sourceDeltaSamples: Int
        let sourceDeltaNanosecondsTotal: UInt64
        let outOfOrderPresentations: Int
        let targetMisses: Int
        let timedIntervals: Int
        let timedIntervalNanosecondsTotal: UInt64
        let generatedToRealIntervals: Int
        let generatedToRealNanosecondsTotal: UInt64
        let generatedToRealMisses: Int
        let realToGeneratedIntervals: Int
        let realToGeneratedNanosecondsTotal: UInt64
        let realToGeneratedMisses: Int
        let target120Intervals: Int
        let target80Intervals: Int
        let target60Intervals: Int
        let intervalsOver22Milliseconds: Int
        let timedTargetNanosecondsTotal: UInt64
        let timedMeanSlackNanosecondsTotal: UInt64
        let severeLateIntervals: Int
        let retargetBoundaryIntervals: Int
        let admissionWaits: Int
        let admissionWaitNanosecondsTotal: UInt64
        let severeGeneratedToRealIntervals: Int
        let severeRealToGeneratedIntervals: Int
        let productionGateSignals: Int
        let productionGateLatenessNanosecondsTotal: UInt64
        let productionGateLatenessMaximumNanoseconds: UInt64
        let generatedDrawableWaitSamples: Int
        let generatedDrawableWaitNanosecondsTotal: UInt64
        let generatedDrawableWaitMaximumNanoseconds: UInt64
        let realDrawableWaitSamples: Int
        let realDrawableWaitNanosecondsTotal: UInt64
        let realDrawableWaitMaximumNanoseconds: UInt64
        let maximumHistogramBuckets: Int

        var report: [String: Any] {
            [
                "accepted_pairs": acceptedPairs,
                "generated_presentations": generatedPresentations,
                "real_presentations": realPresentations,
                "real_only_presentations": realOnlyPresentations,
                "dropped_generated_late": droppedGeneratedLate,
                "backpressure_drops": backpressureDrops,
                "source_attempts": sourceAttempts,
                "scheduler_accepted_frames": schedulerAcceptedFrames,
                "scheduler_warming_frames": schedulerWarmingFrames,
                "scheduler_too_slow_frames": schedulerTooSlowFrames,
                "scheduler_too_fast_frames": schedulerTooFastFrames,
                "coordinator_real_only_frames": coordinatorRealOnlyFrames,
                "interpolation_failures": interpolationFailures,
                "raw_cadence_too_slow_frames": rawCadenceTooSlowFrames,
                "source_delta_samples": sourceDeltaSamples,
                "source_delta_nanoseconds_total": sourceDeltaNanosecondsTotal,
                "out_of_order_presentations": outOfOrderPresentations,
                "target_misses": targetMisses,
                "timed_intervals": timedIntervals,
                "timed_interval_nanoseconds_total": timedIntervalNanosecondsTotal,
                "generated_to_real_intervals": generatedToRealIntervals,
                "generated_to_real_nanoseconds_total": generatedToRealNanosecondsTotal,
                "generated_to_real_misses": generatedToRealMisses,
                "real_to_generated_intervals": realToGeneratedIntervals,
                "real_to_generated_nanoseconds_total": realToGeneratedNanosecondsTotal,
                "real_to_generated_misses": realToGeneratedMisses,
                "target_120_intervals": target120Intervals,
                "target_80_intervals": target80Intervals,
                "target_60_intervals": target60Intervals,
                "intervals_over_22_ms": intervalsOver22Milliseconds,
                "timed_target_nanoseconds_total": timedTargetNanosecondsTotal,
                "timed_mean_slack_nanoseconds_total": timedMeanSlackNanosecondsTotal,
                "severe_late_intervals": severeLateIntervals,
                "retarget_boundary_intervals": retargetBoundaryIntervals,
                "admission_waits": admissionWaits,
                "admission_wait_nanoseconds_total": admissionWaitNanosecondsTotal,
                "severe_generated_to_real_intervals": severeGeneratedToRealIntervals,
                "severe_real_to_generated_intervals": severeRealToGeneratedIntervals,
                "production_gate_signals": productionGateSignals,
                "production_gate_lateness_nanoseconds_total": productionGateLatenessNanosecondsTotal,
                "production_gate_lateness_maximum_nanoseconds": productionGateLatenessMaximumNanoseconds,
                "generated_drawable_wait_samples": generatedDrawableWaitSamples,
                "generated_drawable_wait_nanoseconds_total": generatedDrawableWaitNanosecondsTotal,
                "generated_drawable_wait_maximum_nanoseconds": generatedDrawableWaitMaximumNanoseconds,
                "real_drawable_wait_samples": realDrawableWaitSamples,
                "real_drawable_wait_nanoseconds_total": realDrawableWaitNanosecondsTotal,
                "real_drawable_wait_maximum_nanoseconds": realDrawableWaitMaximumNanoseconds,
                "maximum_pacing_histogram_buckets": maximumHistogramBuckets
            ]
        }
    }

    private let lock = NSLock()
    private var acceptedPairs = 0
    private var generatedPresentations = 0
    private var realPresentations = 0
    private var realOnlyPresentations = 0
    private var droppedGeneratedLate = 0
    private var backpressureDrops = 0
    private var sourceAttempts = 0
    private var schedulerAcceptedFrames = 0
    private var schedulerWarmingFrames = 0
    private var schedulerTooSlowFrames = 0
    private var schedulerTooFastFrames = 0
    private var coordinatorRealOnlyFrames = 0
    private var interpolationFailures = 0
    private var rawCadenceTooSlowFrames = 0
    private var sourceDeltaSamples = 0
    private var sourceDeltaNanosecondsTotal: UInt64 = 0
    private var outOfOrderPresentations = 0
    private var targetMisses = 0
    private var timedIntervals = 0
    private var timedIntervalNanosecondsTotal: UInt64 = 0
    private var generatedToRealIntervals = 0
    private var generatedToRealNanosecondsTotal: UInt64 = 0
    private var generatedToRealMisses = 0
    private var realToGeneratedIntervals = 0
    private var realToGeneratedNanosecondsTotal: UInt64 = 0
    private var realToGeneratedMisses = 0
    private var target120Intervals = 0
    private var target80Intervals = 0
    private var target60Intervals = 0
    private var intervalsOver22Milliseconds = 0
    private var timedTargetNanosecondsTotal: UInt64 = 0
    private var timedMeanSlackNanosecondsTotal: UInt64 = 0
    private var severeLateIntervals = 0
    private var retargetBoundaryIntervals = 0
    private var admissionWaits = 0
    private var admissionWaitNanosecondsTotal: UInt64 = 0
    private var severeGeneratedToRealIntervals = 0
    private var severeRealToGeneratedIntervals = 0
    private var productionGateSignals = 0
    private var productionGateLatenessNanosecondsTotal: UInt64 = 0
    private var productionGateLatenessMaximumNanoseconds: UInt64 = 0
    private var generatedDrawableWaitSamples = 0
    private var generatedDrawableWaitNanosecondsTotal: UInt64 = 0
    private var generatedDrawableWaitMaximumNanoseconds: UInt64 = 0
    private var realDrawableWaitSamples = 0
    private var realDrawableWaitNanosecondsTotal: UInt64 = 0
    private var realDrawableWaitMaximumNanoseconds: UInt64 = 0
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

    func recordAdmissionWait(nanoseconds: UInt64) {
        guard nanoseconds > 0 else { return }
        lock.lock()
        admissionWaits += 1
        admissionWaitNanosecondsTotal &+= nanoseconds
        lock.unlock()
    }

    func recordProductionGateRelease(latenessNanoseconds: UInt64) {
        lock.lock()
        productionGateSignals += 1
        productionGateLatenessNanosecondsTotal &+= latenessNanoseconds
        productionGateLatenessMaximumNanoseconds = max(
            productionGateLatenessMaximumNanoseconds,
            latenessNanoseconds
        )
        lock.unlock()
    }

    func recordDrawableWait(generated: Bool, nanoseconds: UInt64) {
        lock.lock()
        if generated {
            generatedDrawableWaitSamples += 1
            generatedDrawableWaitNanosecondsTotal &+= nanoseconds
            generatedDrawableWaitMaximumNanoseconds = max(
                generatedDrawableWaitMaximumNanoseconds,
                nanoseconds
            )
        } else {
            realDrawableWaitSamples += 1
            realDrawableWaitNanosecondsTotal &+= nanoseconds
            realDrawableWaitMaximumNanoseconds = max(
                realDrawableWaitMaximumNanoseconds,
                nanoseconds
            )
        }
        lock.unlock()
    }

    func recordSourceAttempt(deltaSeconds: Double?) {
        lock.lock()
        sourceAttempts += 1
        if let deltaSeconds, deltaSeconds.isFinite, deltaSeconds > 0 {
            sourceDeltaSamples += 1
            let nanoseconds = UInt64(min(deltaSeconds * 1_000_000_000.0, Double(UInt64.max)))
            sourceDeltaNanosecondsTotal &+= nanoseconds
            if deltaSeconds
                > MetallumExtendedProMotionScheduler.maximumInterpolationSourceSampleInterval {
                rawCadenceTooSlowFrames += 1
            }
        }
        lock.unlock()
    }

    func recordSchedulerAccepted() {
        lock.lock()
        schedulerAcceptedFrames += 1
        lock.unlock()
    }

    func recordSchedulerRejection(
        _ rejection: MetallumExtendedProMotionScheduler.InterpolationRejection?
    ) {
        lock.lock()
        switch rejection {
        case .warmingUp:
            schedulerWarmingFrames += 1
        case .realCadenceTooSlow:
            schedulerTooSlowFrames += 1
        case .realCadenceTooFast, .fixedCadenceMismatch:
            schedulerTooFastFrames += 1
        default:
            schedulerWarmingFrames += 1
        }
        lock.unlock()
    }

    func recordCoordinatorRealOnly() {
        lock.lock()
        coordinatorRealOnlyFrames += 1
        lock.unlock()
    }

    func recordInterpolationFailure() {
        lock.lock()
        interpolationFailures += 1
        lock.unlock()
    }

    func recordOutOfOrderPresentation() {
        lock.lock()
        outOfOrderPresentations += 1
        lock.unlock()
    }

    func recordTargetMiss() {
        lock.lock()
        targetMisses += 1
        lock.unlock()
    }

    /**
     * Read-only on-glass cadence attribution. Transition: 1 = G->R, 2 = R->G.
     * Values come only from CAMetalDrawable.presentedTime callbacks.
     */
    func recordTimedInterval(
        seconds: Double,
        targetSeconds: Double,
        meanSlackSeconds: Double,
        transition: Int,
        missed: Bool,
        severe: Bool
    ) {
        guard seconds.isFinite, seconds > 0,
              targetSeconds.isFinite, targetSeconds > 0,
              meanSlackSeconds.isFinite, meanSlackSeconds >= 0 else { return }
        let nanoseconds = UInt64(min(seconds * 1_000_000_000.0, Double(UInt64.max)))
        let targetNanoseconds = UInt64(min(
            targetSeconds * 1_000_000_000.0,
            Double(UInt64.max)
        ))
        let meanSlackNanoseconds = UInt64(min(
            meanSlackSeconds * 1_000_000_000.0,
            Double(UInt64.max)
        ))
        lock.lock()
        timedIntervals += 1
        timedIntervalNanosecondsTotal &+= nanoseconds
        timedTargetNanosecondsTotal &+= targetNanoseconds
        timedMeanSlackNanosecondsTotal &+= meanSlackNanoseconds
        if severe {
            severeLateIntervals += 1
        }
        if seconds > 0.022 {
            intervalsOver22Milliseconds += 1
        }
        switch transition {
        case 1:
            generatedToRealIntervals += 1
            generatedToRealNanosecondsTotal &+= nanoseconds
            if missed { generatedToRealMisses += 1 }
            if severe { severeGeneratedToRealIntervals += 1 }
        case 2:
            realToGeneratedIntervals += 1
            realToGeneratedNanosecondsTotal &+= nanoseconds
            if missed { realToGeneratedMisses += 1 }
            if severe { severeRealToGeneratedIntervals += 1 }
        default:
            break
        }
        let targetHz = 1.0 / targetSeconds
        let distance120 = abs(targetHz - 120.0)
        let distance80 = abs(targetHz - 80.0)
        let distance60 = abs(targetHz - 60.0)
        if distance120 <= distance80, distance120 <= distance60 {
            target120Intervals += 1
        } else if distance80 <= distance60 {
            target80Intervals += 1
        } else {
            target60Intervals += 1
        }
        lock.unlock()
    }

    func recordRetargetBoundary() {
        lock.lock()
        retargetBoundaryIntervals += 1
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

    func recordRealOnlyPresentation() {
        lock.lock()
        realOnlyPresentations += 1
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
            realOnlyPresentations: realOnlyPresentations,
            droppedGeneratedLate: droppedGeneratedLate,
            backpressureDrops: backpressureDrops,
            sourceAttempts: sourceAttempts,
            schedulerAcceptedFrames: schedulerAcceptedFrames,
            schedulerWarmingFrames: schedulerWarmingFrames,
            schedulerTooSlowFrames: schedulerTooSlowFrames,
            schedulerTooFastFrames: schedulerTooFastFrames,
            coordinatorRealOnlyFrames: coordinatorRealOnlyFrames,
            interpolationFailures: interpolationFailures,
            rawCadenceTooSlowFrames: rawCadenceTooSlowFrames,
            sourceDeltaSamples: sourceDeltaSamples,
            sourceDeltaNanosecondsTotal: sourceDeltaNanosecondsTotal,
            outOfOrderPresentations: outOfOrderPresentations,
            targetMisses: targetMisses,
            timedIntervals: timedIntervals,
            timedIntervalNanosecondsTotal: timedIntervalNanosecondsTotal,
            generatedToRealIntervals: generatedToRealIntervals,
            generatedToRealNanosecondsTotal: generatedToRealNanosecondsTotal,
            generatedToRealMisses: generatedToRealMisses,
            realToGeneratedIntervals: realToGeneratedIntervals,
            realToGeneratedNanosecondsTotal: realToGeneratedNanosecondsTotal,
            realToGeneratedMisses: realToGeneratedMisses,
            target120Intervals: target120Intervals,
            target80Intervals: target80Intervals,
            target60Intervals: target60Intervals,
            intervalsOver22Milliseconds: intervalsOver22Milliseconds,
            timedTargetNanosecondsTotal: timedTargetNanosecondsTotal,
            timedMeanSlackNanosecondsTotal: timedMeanSlackNanosecondsTotal,
            severeLateIntervals: severeLateIntervals,
            retargetBoundaryIntervals: retargetBoundaryIntervals,
            admissionWaits: admissionWaits,
            admissionWaitNanosecondsTotal: admissionWaitNanosecondsTotal,
            severeGeneratedToRealIntervals: severeGeneratedToRealIntervals,
            severeRealToGeneratedIntervals: severeRealToGeneratedIntervals,
            productionGateSignals: productionGateSignals,
            productionGateLatenessNanosecondsTotal: productionGateLatenessNanosecondsTotal,
            productionGateLatenessMaximumNanoseconds: productionGateLatenessMaximumNanoseconds,
            generatedDrawableWaitSamples: generatedDrawableWaitSamples,
            generatedDrawableWaitNanosecondsTotal: generatedDrawableWaitNanosecondsTotal,
            generatedDrawableWaitMaximumNanoseconds: generatedDrawableWaitMaximumNanoseconds,
            realDrawableWaitSamples: realDrawableWaitSamples,
            realDrawableWaitNanosecondsTotal: realDrawableWaitNanosecondsTotal,
            realDrawableWaitMaximumNanoseconds: realDrawableWaitMaximumNanoseconds,
            maximumHistogramBuckets: maximumHistogramBuckets
        )
    }
}

/**
 * Read-only HUD counter. This is deliberately the on-screen presentation
 * counter populated by CAMetalDrawable.addPresentedHandler, not the number of
 * accepted pairs, interpolation encodes, or submitted command buffers.
 */
@_cdecl("metallum_frame_interpolation_presented_generated_count_v1")
public func metallum_frame_interpolation_presented_generated_count_v1() -> UInt64 {
    UInt64(max(MetallumFrameInterpolationTelemetry.shared.snapshot().generatedPresentations, 0))
}

/**
 * Selector-based read-only transport telemetry for the live FI benchmark.
 *
 * These are process-lifetime counters.  The benchmark takes two snapshots and
 * reports their deltas; no per-frame packet, allocation, or CPU readback is
 * introduced by this diagnostic ABI.
 *
 * 0 accepted pairs, 1 generated presentations, 2 real presentations,
 * 3 generated frames dropped late, 4 admission backpressure drops,
 * 5 source attempts, 6 scheduler-accepted sources, 7 scheduler warm-up,
 * 8 too-slow cadence, 9 too-fast/fixed mismatch, 10 coordinator real-only,
 * 11 interpolation failures, 12 raw source deltas slower than 30 FPS,
 * 13 accumulated source-delta nanoseconds, 14 valid source-delta samples,
 * 15 out-of-order on-glass callbacks, 16 missed presentation targets,
 * 17 timed intervals, 18 timed interval nanoseconds, 19 G->R intervals,
 * 20 G->R nanoseconds, 21 G->R misses, 22 R->G intervals,
 * 23 R->G nanoseconds, 24 R->G misses, 25/26/27 target 120/80/60 counts,
 * 28 intervals longer than 22 ms, 29 exact target interval nanoseconds,
 * 30 aggregate hardware-derived mean slack nanoseconds,
 * 31 severe same-target late intervals, 32 target-transition boundaries,
 * 33 bounded admission waits, 34 accumulated admission-wait nanoseconds,
 * 35/36 severe G->R/R->G intervals, 37 gate releases, 38/39 gate lateness
 * total/maximum, 40/41/42 generated drawable wait samples/total/maximum,
 * 43/44/45 real drawable wait samples/total/maximum,
 * 46 ordinary real-only drawable presentations.
 */
@_cdecl("metallum_frame_interpolation_telemetry_counter_v1")
public func metallum_frame_interpolation_telemetry_counter_v1(_ selector: Int32) -> UInt64 {
    let snapshot = MetallumFrameInterpolationTelemetry.shared.snapshot()
    let value: Int
    switch selector {
    case 0: value = snapshot.acceptedPairs
    case 1: value = snapshot.generatedPresentations
    case 2: value = snapshot.realPresentations
    case 3: value = snapshot.droppedGeneratedLate
    case 4: value = snapshot.backpressureDrops
    case 5: value = snapshot.sourceAttempts
    case 6: value = snapshot.schedulerAcceptedFrames
    case 7: value = snapshot.schedulerWarmingFrames
    case 8: value = snapshot.schedulerTooSlowFrames
    case 9: value = snapshot.schedulerTooFastFrames
    case 10: value = snapshot.coordinatorRealOnlyFrames
    case 11: value = snapshot.interpolationFailures
    case 12: value = snapshot.rawCadenceTooSlowFrames
    case 13: return snapshot.sourceDeltaNanosecondsTotal
    case 14: value = snapshot.sourceDeltaSamples
    case 15: value = snapshot.outOfOrderPresentations
    case 16: value = snapshot.targetMisses
    case 17: value = snapshot.timedIntervals
    case 18: return snapshot.timedIntervalNanosecondsTotal
    case 19: value = snapshot.generatedToRealIntervals
    case 20: return snapshot.generatedToRealNanosecondsTotal
    case 21: value = snapshot.generatedToRealMisses
    case 22: value = snapshot.realToGeneratedIntervals
    case 23: return snapshot.realToGeneratedNanosecondsTotal
    case 24: value = snapshot.realToGeneratedMisses
    case 25: value = snapshot.target120Intervals
    case 26: value = snapshot.target80Intervals
    case 27: value = snapshot.target60Intervals
    case 28: value = snapshot.intervalsOver22Milliseconds
    case 29: return snapshot.timedTargetNanosecondsTotal
    case 30: return snapshot.timedMeanSlackNanosecondsTotal
    case 31: value = snapshot.severeLateIntervals
    case 32: value = snapshot.retargetBoundaryIntervals
    case 33: value = snapshot.admissionWaits
    case 34: return snapshot.admissionWaitNanosecondsTotal
    case 35: value = snapshot.severeGeneratedToRealIntervals
    case 36: value = snapshot.severeRealToGeneratedIntervals
    case 37: value = snapshot.productionGateSignals
    case 38: return snapshot.productionGateLatenessNanosecondsTotal
    case 39: return snapshot.productionGateLatenessMaximumNanoseconds
    case 40: value = snapshot.generatedDrawableWaitSamples
    case 41: return snapshot.generatedDrawableWaitNanosecondsTotal
    case 42: return snapshot.generatedDrawableWaitMaximumNanoseconds
    case 43: value = snapshot.realDrawableWaitSamples
    case 44: return snapshot.realDrawableWaitNanosecondsTotal
    case 45: return snapshot.realDrawableWaitMaximumNanoseconds
    case 46: value = snapshot.realOnlyPresentations
    default: return 0
    }
    return UInt64(max(value, 0))
}

/**
 * The Java renderer retains the coordinator, but the legacy real-only
 * presenter receives only a CAMetalLayer.  This narrow registry lets that
 * presenter drain a live FI pair instead of overtaking it on a second command
 * queue.  Entries are weak so it cannot extend a renderer generation.
 */
private final class MetallumFrameInterpolationPresentationRegistry {
    static let shared = MetallumFrameInterpolationPresentationRegistry()

    private final class Entry {
        weak var coordinator: MetallumFrameInterpolationCoordinator?

        init(_ coordinator: MetallumFrameInterpolationCoordinator) {
            self.coordinator = coordinator
        }
    }

    private let lock = NSLock()
    private var entries: [ObjectIdentifier: Entry] = [:]

    func install(_ coordinator: MetallumFrameInterpolationCoordinator, layer: CAMetalLayer) {
        lock.lock()
        entries[ObjectIdentifier(layer)] = Entry(coordinator)
        lock.unlock()
    }

    func remove(_ coordinator: MetallumFrameInterpolationCoordinator, layer: CAMetalLayer) {
        lock.lock()
        let key = ObjectIdentifier(layer)
        if entries[key]?.coordinator === coordinator {
            entries.removeValue(forKey: key)
        }
        lock.unlock()
    }

    func coordinator(for layer: CAMetalLayer) -> MetallumFrameInterpolationCoordinator? {
        lock.lock()
        defer { lock.unlock() }
        let key = ObjectIdentifier(layer)
        guard let entry = entries[key] else { return nil }
        guard let coordinator = entry.coordinator else {
            entries.removeValue(forKey: key)
            return nil
        }
        return coordinator
    }
}

/**
 * Called by the legacy GLFW presentation ABI before acquiring a drawable.
 * Returning false means the pair could not drain in the bounded interval, so
 * the caller must drop this fallback frame rather than display N+1 before the
 * mandatory real member of N.
 */
func metallumAwaitFrameInterpolationPresentationDrain(
    _ layer: CAMetalLayer,
    timeoutNanoseconds: UInt64 = MetallumFrameInterpolationCoordinator
        .legacyFallbackDrainTimeoutNanoseconds
) -> Bool {
    MetallumFrameInterpolationPresentationRegistry.shared.coordinator(for: layer)?
        .awaitUnmanagedPresentationDrain(timeoutNanoseconds: timeoutNanoseconds) ?? true
}

/** Called by layer mutation paths to stop new FI admission and re-prime safely. */
func metallumInvalidateFrameInterpolationForLayerMutation(_ layer: CAMetalLayer) {
    MetallumFrameInterpolationPresentationRegistry.shared.coordinator(for: layer)?
        .invalidateForLayerMutation()
}

/**
 * Generation-local MetalFX lifecycle and presentation owner.
 *
 * It never acquires a drawable or presents from the Java render thread.  A
 * dedicated native worker serializes generated/real output, gates the real
 * member at the measured midpoint, and always attempts that mandatory real
 * member.  The stage-numbered entry points below remain deterministic native
 * regression harnesses for the contracts that built the production path.
 */
private final class MetallumFrameInterpolationCoordinator: @unchecked Sendable {
    private static let ringSize = 3
    private static let maximumProductionTickets = 2
    private static let maximumMandatoryRealRetries = 2
    /// Apple's PresentThread blocks the producer when both FI jobs are live.
    /// Keep that backpressure bounded so a genuinely wedged GPU still fails open.
    private static let productionAdmissionTimeoutNanoseconds: UInt64 = 100_000_000
    /// Apple's pacing sample is capped at 100 ms. Sequential gaps inside that
    /// bound skip FI without masquerading as a frame-history discontinuity.
    private static let maximumRecoverableProductionTimingGapSeconds = 0.100
    /// Legacy fallback runs on Minecraft's render thread.  Preserve pair order
    /// but drop the fallback after this bounded interval instead of creating a
    /// one-second gameplay hitch under a wedged drawable/presentation path.
    static let legacyFallbackDrainTimeoutNanoseconds: UInt64 = 100_000_000
    // One display interval is reserved for the real frame.  A generated frame
    // that misses its own slot by more than this small scheduler tolerance is
    // discarded instead of creating a two-present burst immediately before it.
    private static let generatedLatenessToleranceNanoseconds: UInt64 = 1_000_000
    /// A committed producer must either reach its GPU-ready signal or fail open.
    /// Drawable availability itself is owned by CAMetalLayer.nextDrawable().
    private static let producerSignalWatchdogMaximumNanoseconds: UInt64 = 100_000_000

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
        /// This is the only cross-queue ownership fence for this physical
        /// ring slot.  A later producer waits here before it overwrites any
        /// texture in the slot; the real presentation signals it after its
        /// final composite has consumed the slot.
        let reuseFence: MTLSharedEvent
        var state: SlotState
        /// The ticket currently allowed to change `state`.  A completed
        /// presentation callback may arrive after this physical slot has been
        /// reserved by a newer source, so callbacks must never use an index as
        /// identity on its own.
        var ownerTicket: UInt64?
        var ownershipEpoch: UInt64
        /// Last signal encoded by a real presentation that read this slot.
        /// It is intentionally updated only after that command buffer commits;
        /// a pre-commit failure must not manufacture an unsatisfiable wait.
        var reusableFenceValue: UInt64
        /// Per-slot monotonic values avoid coupling unrelated ring slots and
        /// let a stale CPU completion signal only the exact retired use.
        var nextFenceSignalValue: UInt64
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

    /** Synthetic Stage-7 declaration retained for pacing regression tests. */
    private struct PacingWork {
        let epoch: UInt64
        let generatedPresentationID: UInt64
        let realPresentationID: UInt64
        let generatedDeadlineNanoseconds: UInt64
        let realDeadlineNanoseconds: UInt64
    }

    private struct ProductionPacingWork {
        let ticket: UInt64
        let includeGenerated: Bool
    }

    /** One preallocated-thread midpoint signal; it never owns a drawable. */
    private struct ProductionMidpointSignal {
        let ticket: UInt64
        let deadlineNanoseconds: UInt64
        let eventValue: UInt64
    }

    private struct ProductionPresentationGate {
        let event: MTLSharedEvent
        let eventValue: UInt64
    }

    /** Stable low-byte values exported by runtimeStatusPacked(). */
    enum RuntimeState: UInt64 {
        case disabled = 0
        case warming = 1
        case active = 2
        case unavailable = 3
    }

    /** Stable second-byte reason values exported by runtimeStatusPacked(). */
    enum RuntimeReason: UInt64 {
        case none = 0
        case measuringOnGlass = 1
        case onGlassCadence = 2
        case onGlassTimestamp = 3
        case nativeInterpolatorUnavailable = 4
        case awaitingProductionSource = 5
    }

    /**
     * Session-local circuit breaker fed exclusively by drawable presentedTime.
     * A complete block is both the warm-up proof and the continuing health
     * check. Once unavailable, ordinary cadence/history resets cannot reopen
     * the session; Java must install a new coordinator generation.
     */
    private struct ProductionOnGlassHealthGate {
        static let evaluationIntervalCount = 60
        static let maximumTargetMisses = 2
        static let maximumSevereIntervals = 1
        static let maximumTimestampFaults = 1

        private(set) var runtimeState: RuntimeState = .warming
        private(set) var runtimeReason: RuntimeReason = .awaitingProductionSource
        private var comparableIntervals = 0
        private var targetMisses = 0
        private var severeIntervals = 0
        private var timestampFaults = 0
        private var hasSeenGeneratedPresentation = false

        mutating func observeProductionSource() {
            guard runtimeState == .warming,
                  runtimeReason == .awaitingProductionSource else { return }
            runtimeReason = .measuringOnGlass
        }

        mutating func observe(
            _ feedback: MetallumExtendedProMotionScheduler.OnGlassPresentationFeedback,
            generatedPresentation: Bool
        ) -> Bool {
            guard runtimeState != .unavailable, feedback.currentGeneration else {
                return false
            }
            if feedback.validTimestamp, generatedPresentation {
                hasSeenGeneratedPresentation = true
            }
            guard feedback.validTimestamp else {
                guard feedback.trackedPresentation,
                      hasSeenGeneratedPresentation || generatedPresentation else {
                    return false
                }
                timestampFaults += 1
                if timestampFaults > Self.maximumTimestampFaults {
                    return trip(.onGlassTimestamp)
                }
                return false
            }
            if feedback.outOfOrder, feedback.trackedPresentation,
               hasSeenGeneratedPresentation {
                timestampFaults += 1
                if timestampFaults > Self.maximumTimestampFaults {
                    return trip(.onGlassTimestamp)
                }
                return false
            }
            timestampFaults = 0
            if feedback.retargetBoundary {
                resetBlock()
                runtimeState = .warming
                runtimeReason = .measuringOnGlass
                return false
            }
            let unexpectedTransition = feedback.unexpectedFiTransition
                && hasSeenGeneratedPresentation
            guard feedback.comparableFiInterval || unexpectedTransition else { return false }

            comparableIntervals += 1
            if feedback.targetMissed || unexpectedTransition { targetMisses += 1 }
            if feedback.severeLate || unexpectedTransition { severeIntervals += 1 }
            guard comparableIntervals >= Self.evaluationIntervalCount else { return false }

            if targetMisses > Self.maximumTargetMisses
                    || severeIntervals > Self.maximumSevereIntervals {
                return trip(.onGlassCadence)
            }
            resetBlock()
            runtimeState = .active
            runtimeReason = .none
            return false
        }

        private mutating func trip(_ reason: RuntimeReason) -> Bool {
            runtimeState = .unavailable
            runtimeReason = reason
            resetBlock()
            return true
        }

        private mutating func resetBlock() {
            comparableIntervals = 0
            targetMisses = 0
            severeIntervals = 0
            timestampFaults = 0
        }
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
        var production: ProductionPresentation?
        var state: TicketState = .prepared
        var commandBufferCompleted = false
        var productionScheduled = false
        var generatedPresentationEncoded = false
        var generatedPresentationSuppressed = false
        var generatedPresentationSubmitted = false
        var fiEnqueued = false
        var fiCompleted = false
        var fiPredecessorSlot: Int?
        var productionEpoch: UInt64 = 0
        var renderReadyEventValue: UInt64 = 0
        var interpolationReadyEventValue: UInt64 = 0
        var producerOutputSignaled = false
        var producerFailed = false
        var producerFailureWatchdogDeadline: UInt64?
        var interpolationOutputSignaled = false
        var interpolationReadyTimestampNanoseconds: UInt64 = 0
        var interpolationFailed = false
        var resetsInterpolator = false
        var mandatoryRealRetries = 0
        var realPresentGateValue: UInt64 = 0
        var realPresentationSubmitted = false
        /// Immutable reservation identity for the source producer.  This is
        /// compared with the slot before a late callback is allowed to mutate
        /// slot state, preventing ABA after triple-ring reuse.
        var slotEpoch: UInt64 = 0
        var reuseFence: MTLSharedEvent?
        var reuseFenceWaitValue: UInt64 = 0
        var realPresentationFenceValue: UInt64 = 0
        var realPresentationFencePublished = false
        var realPresentationFenceCpuSignaled = false
        var realPresentationCompleted = false
        /** Serializes a completion that can race the CPU return from commit(). */
        var realPresentationCommitInProgress = false
        var deferredRealPresentationCompletionSucceeded: Bool?
        var realPresentationCompletionFinalized = false
        var realPresentationPublicationFailed = false
        /// Once R has committed, the ticket stays retained for completion and
        /// retry bookkeeping but no longer consumes producer-admission budget.
        var admissionDetached = false
        var abandoned = false
        /// Kept explicit so native lifecycle tests can exercise admission
        /// ownership without manufacturing a full renderer frame snapshot.
        let isProductionTicket: Bool

        init(
            slot: Int,
            commandBuffer: MTLCommandBuffer,
            production: ProductionPresentation? = nil,
            slotEpoch: UInt64 = 0,
            reuseFence: MTLSharedEvent? = nil,
            reuseFenceWaitValue: UInt64 = 0,
            realPresentationFenceValue: UInt64 = 0,
            isProductionTicket: Bool = false
        ) {
            self.slot = slot
            self.commandBuffer = commandBuffer
            self.production = production
            self.slotEpoch = slotEpoch
            self.reuseFence = reuseFence
            self.reuseFenceWaitValue = reuseFenceWaitValue
            self.realPresentationFenceValue = realPresentationFenceValue
            self.isProductionTicket = isProductionTicket
        }
    }

    /** Exact post-world presentation settings captured with a production ticket. */
    private struct ProductionPresentation {
        let frame: MetallumRendererFrameStateSnapshot
        /** CPU hand-off after source encoding, before coordinator admission can block. */
        let sourceReadyTimestampNanoseconds: UInt64
        var pacingPlan: MetallumExtendedProMotionScheduler.Plan
        var allowsInterpolation: Bool
        var sourceDeltaSeconds: Double
        let outputMode: Int32
        let sourceEncoding: Int32
        let materialGenerationActive: Int32
        let diagnosticPattern: Int32
        let currentHeadroom: Float
        let hdrStrength: Float
        let bloomStrength: Float
    }

    /** Monotonic CPU hand-off identity of the last real source accepted after commit. */
    private struct AcceptedProductionSource {
        let timestampNanoseconds: UInt64
        let frameId: UInt64
        let submitIndex: UInt64
        let rendererGenerationId: UInt64
        let historyGeneration: UInt64
        let renderContractGenerationId: UInt64
        let outputGenerationId: UInt64
        let worldIdentity: UInt64
        let dimensionIdentity: UInt64

        init(timestampNanoseconds: UInt64, frame: MetallumRendererFrameStateSnapshot) {
            self.timestampNanoseconds = timestampNanoseconds
            self.frameId = frame.frameId
            self.submitIndex = frame.submitIndex
            self.rendererGenerationId = frame.rendererGenerationId
            self.historyGeneration = frame.historyGeneration
            self.renderContractGenerationId = frame.renderContractGenerationId
            self.outputGenerationId = frame.outputGenerationId
            self.worldIdentity = frame.worldIdentity
            self.dimensionIdentity = frame.dimensionIdentity
        }

        func isImmediatePredecessor(of frame: MetallumRendererFrameStateSnapshot) -> Bool {
            frameId != UInt64.max
                && submitIndex != UInt64.max
                && frame.frameId == frameId + 1
                && frame.submitIndex == submitIndex + 1
                && frame.rendererGenerationId == rendererGenerationId
                && frame.historyGeneration == historyGeneration
                && frame.renderContractGenerationId == renderContractGenerationId
                && frame.outputGenerationId == outputGenerationId
                && frame.worldIdentity == worldIdentity
                && frame.dimensionIdentity == dimensionIdentity
        }
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
    private let productionPacingWake = DispatchSemaphore(value: 0)
    private let productionPacingStopped = DispatchSemaphore(value: 0)
    private let productionPacingStarted = DispatchSemaphore(value: 0)
    private let eventListener = MTLSharedEventListener(
        dispatchQueue: DispatchQueue(
            label: "Metallum FI GPU-ready notifications",
            qos: .userInteractive
        )
    )
    private let precisionDeadlineTimer = MetallumPrecisionDeadlineTimer()
    private let productionDeadlineTimer = MetallumPrecisionDeadlineTimer()
    private lazy var schedulerThread: Thread = Thread { [weak self] in
        self?.runPresentationScheduler()
    }
    private lazy var productionPacingThread: Thread = Thread { [weak self] in
        self?.runProductionPacingScheduler()
    }

    private var renderQueue: MTLCommandQueue?
    private var presentationQueue: MTLCommandQueue?
    private var completionEvent: MTLSharedEvent?
    /// Separate from stage-7's synthetic accounting event.  Producer writes
    /// signal this event on the renderer queue; presentation work waits on it
    /// entirely on-GPU, with no CPU polling or cross-queue race.
    private var renderReadyEvent: MTLSharedEvent?
    private var interpolationReadyEvent: MTLSharedEvent?
    private var realPresentGate: MTLSharedEvent?
    private var slots: [Slot] = []
    private var acceptingFrames = false
    private var shuttingDown = false
    private var schedulerExited = false
    private var productionPacingExited = false
    private var pendingRealFrames = 0
    private var pendingPacingWorks = 0
    private var resetEpoch: UInt64 = 0
    private var textureAllocationCount = 0
    private var nextTicket: UInt64 = 1
    private var nextRenderReadyEventValue: UInt64 = 1
    private var nextInterpolationReadyEventValue: UInt64 = 1
    private var nextRealPresentGateValue: UInt64 = 1
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
    private var lastAcceptedProductionSource: AcceptedProductionSource?
    /** One isolated 50-100 ms source gap is recoverable; a repeated gap is a slow stream. */
    private var productionTimingGapPending = false
    /// Prevents an actually slow source from treating every frame as an
    /// isolated gap and resuming FI without a fresh cadence warm-up.
    private var productionPrimingFrames = 0
    private var activeProductionFiJobs = 0
    /** Exactly one G/R pair may own the presentation queue's pacing gate. */
    private var activePresentationTicket: UInt64?
    /** Committed presentation composites may outlive a fail-open ticket. */
    private var activePresentationCommandBuffers = 0
    private var fiReaderLeases: [Int] = []
    private var orphanedFiJobs: [UInt64: (predecessor: Int, current: Int)] = [:]
    /// A frame presented by the normal renderer, or a generated member that
    /// missed its slot, breaks adjacency with coordinator-owned history.  The
    /// next production ticket must re-prime instead of interpolating across it.
    private var productionNeedsReprime = false
    /// MetalFX keeps private temporal state beyond our texture slots.  Any
    /// visible real-frame discontinuity must reset it on the next FI encode.
    private var productionInterpolatorNeedsReset = true
    private var productionOnGlassHealth = ProductionOnGlassHealthGate()
    /** Session-local generated drawables confirmed by their presented handler. */
    private var productionPresentedGeneratedCount: UInt64 = 0
    private var reasonCounters = ReasonCounters()
    private var nextPresentationID: UInt64 = 1
    private var lastPresentationID: UInt64 = 0
    private var pacingWork: PacingWork?
    private var productionPacingWork: ProductionPacingWork?
    private var productionMidpointSignal: ProductionMidpointSignal?
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
              let completionEvent = device.makeSharedEvent(),
              let renderReadyEvent = device.makeSharedEvent(),
              let interpolationReadyEvent = device.makeSharedEvent(),
              let realPresentGate = device.makeSharedEvent() else {
            return nil
        }
        renderQueue.label = "Metallum FI render queue (stage 4)"
        presentationQueue.label = "Metallum FI present queue (stage 4)"
        self.renderQueue = renderQueue
        self.presentationQueue = presentationQueue
        self.completionEvent = completionEvent
        self.renderReadyEvent = renderReadyEvent
        self.interpolationReadyEvent = interpolationReadyEvent
        self.realPresentGate = realPresentGate

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
                self.renderReadyEvent = nil
                self.interpolationReadyEvent = nil
                self.realPresentGate = nil
                return nil
            }
            acceptingFrames = true
        }

        schedulerThread.name = "Metallum Extended ProMotion scheduler"
        schedulerThread.qualityOfService = .userInteractive
        productionPacingThread.name = "Metallum FI midpoint pacing"
        productionPacingThread.qualityOfService = .userInteractive
        schedulerThread.start()
        productionPacingThread.start()
        // Thread.start() is asynchronous; neither worker needs to be running
        // before the coordinator can accept work because its semaphore keeps
        // the first wake pending. Avoid a failable-start path that could let a
        // late weak-self worker race coordinator deinitialization.
        MetallumFrameInterpolationPresentationRegistry.shared.install(self, layer: layer)
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
        precondition(
            schedulerExited && productionPacingExited,
            "FI coordinator released without joining its presentation workers"
        )
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
                  let realPresentation = device.makeTexture(descriptor: presentationDescriptor),
                  // Exactly one reusable GPU fence belongs to each fixed ring
                  // slot.  It is allocated with the textures, never per frame.
                  let reuseFence = device.makeSharedEvent() else {
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
            reuseFence.label = "Metallum FI slot reuse fence \(index)"
            allocated.append(Slot(
                realColor: realColor,
                generatedColor: generatedColor,
                depth: depth,
                motion: motion,
                sdrUi: sdrUi,
                generatedPresentation: generatedPresentation,
                realPresentation: realPresentation,
                reuseFence: reuseFence,
                state: .free,
                ownerTicket: nil,
                ownershipEpoch: 0,
                reusableFenceValue: 0,
                nextFenceSignalValue: 1
            ))
        }
        slots = allocated
        fiReaderLeases = Array(repeating: 0, count: allocated.count)
        // Each slot owns world history (2), one SDR UI texture, and separate
        // generated/real composite targets.  The targets make the UI lifetime
        // explicit and avoid sharing a drawable across the two presentations.
        textureAllocationCount = allocated.count * 5
        return true
    }

    /**
     * Native-only stage-6 encoder used by the validation harness.  It exercises
     * the exact descriptor usages and per-frame MetalFX contract independently
     * of the Java production admission path.
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
        let retiredHistorySlot = productionHistorySlot
        productionHistorySlot = nil
        if let retiredHistorySlot {
            releaseHistoryLeaseIfPossibleLocked(retiredHistorySlot)
        }
        productionPrimingFrames = 0
        productionInterpolatorNeedsReset = true
    }

    /** Clears only the committed-source timing chain and invalidates old plans. */
    private func resetProductionCadenceLocked() {
        productionTimingGapPending = false
        guard lastAcceptedProductionSource != nil else { return }
        lastAcceptedProductionSource = nil
        MetallumExtendedProMotionSchedulerRegistry.shared.scheduler(for: layer)
            .resetFrameInterpolationCadence()
    }

    /** A source remains resident while a later FI job samples it. */
    private func releaseHistoryLeaseIfPossibleLocked(_ index: Int) {
        guard slots.indices.contains(index),
              slots[index].state == .history,
              slots[index].ownerTicket == nil,
              productionHistorySlot != index,
              fiReaderLeases.indices.contains(index),
              fiReaderLeases[index] == 0 else {
            return
        }
        slots[index].state = .free
    }

    /** Only a current reservation may change a slot's logical ownership. */
    private func slotIsOwnedByTicketLocked(_ index: Int, ticket: UInt64, epoch: UInt64) -> Bool {
        slots.indices.contains(index)
            && epoch != 0
            && slots[index].ownerTicket == ticket
            && slots[index].ownershipEpoch == epoch
    }

    /** Bounded admission counts live source ownership, not retired callbacks. */
    private func productionAdmissionCountLocked() -> Int {
        var count = 0
        for pending in tickets.values {
            if pending.isProductionTicket && !pending.admissionDetached && !pending.abandoned {
                count += 1
            }
        }
        return count
    }

    /**
     * Claims a free physical slot for a production source and snapshots the
     * previous real reader's fence value.  The producer encodes that wait
     * before writing the slot, while this ticket's distinct signal is encoded
     * by its mandatory real presentation.
     */
    private func reserveProductionSlotLocked(
        _ index: Int,
        ticket: UInt64
    ) -> (epoch: UInt64, fence: MTLSharedEvent, waitValue: UInt64, signalValue: UInt64)? {
        guard slots.indices.contains(index),
              slots[index].state == .free,
              slots[index].ownerTicket == nil,
              slots[index].nextFenceSignalValue != 0 else {
            return nil
        }
        slots[index].ownershipEpoch &+= 1
        guard slots[index].ownershipEpoch != 0 else { return nil }
        let epoch = slots[index].ownershipEpoch
        let waitValue = slots[index].reusableFenceValue
        let signalValue = slots[index].nextFenceSignalValue
        slots[index].nextFenceSignalValue &+= 1
        guard slots[index].nextFenceSignalValue != 0 else { return nil }
        slots[index].state = .realFrameReserved
        slots[index].ownerTicket = ticket
        pendingRealFrames += 1
        return (epoch, slots[index].reuseFence, waitValue, signalValue)
    }

    /**
     * R has committed with its per-slot fence in the command stream.  The
     * ticket remains retained, but it must no longer block admission.  Its
     * slot becomes history/free based solely on active FI readers and the
     * current exact predecessor.
     */
    private func detachProductionAdmissionAfterRealCommitLocked(
        _ pending: Ticket,
        ticket: UInt64
    ) {
        guard pending.isProductionTicket,
              pending.realPresentationFencePublished,
              !pending.admissionDetached else {
            return
        }
        pending.admissionDetached = true
        guard slotIsOwnedByTicketLocked(pending.slot, ticket: ticket, epoch: pending.slotEpoch) else {
            // A late path can retire its bookkeeping, but never take ownership
            // back from a newer epoch.
            state.broadcast()
            return
        }
        slots[pending.slot].ownerTicket = nil
        if productionHistorySlot == pending.slot
            || (fiReaderLeases.indices.contains(pending.slot) && fiReaderLeases[pending.slot] > 0) {
            slots[pending.slot].state = .history
        } else {
            slots[pending.slot].state = .free
        }
        state.broadcast()
    }

    /** Completion failure proves GPU execution has stopped, so this exact
     * pre-present fence can be CPU-signaled to release a waiting future writer. */
    private func signalFailedRealPresentationFenceLocked(_ pending: Ticket, ticket: UInt64) {
        guard pending.isProductionTicket,
              pending.realPresentationFencePublished,
              !pending.realPresentationFenceCpuSignaled,
              pending.realPresentationFenceValue != 0,
              let fence = pending.reuseFence else {
            return
        }
        // A later reservation may already have claimed the physical slot. The
        // old value remains strictly lower than that reservation's signal and
        // is therefore the only safe value this stale completion can release.
        fence.signaledValue = max(fence.signaledValue, pending.realPresentationFenceValue)
        pending.realPresentationFenceCpuSignaled = true
    }

    /** Retires one ticket's accounting without allowing a stale epoch to free
     * a newer physical reservation. */
    private func retireProductionTicketReservationLocked(_ pending: Ticket, ticket: UInt64) {
        guard pendingRealFrames > 0 else {
            assertionFailure("FI production pending-real accounting underflow")
            return
        }
        defer {
            pendingRealFrames -= 1
            state.broadcast()
        }
        guard !pending.admissionDetached,
              slotIsOwnedByTicketLocked(pending.slot, ticket: ticket, epoch: pending.slotEpoch) else {
            return
        }
        slots[pending.slot].ownerTicket = nil
        if productionHistorySlot == pending.slot
            || (fiReaderLeases.indices.contains(pending.slot) && fiReaderLeases[pending.slot] > 0) {
            slots[pending.slot].state = .history
        } else {
            slots[pending.slot].state = .free
        }
    }

    /**
     * A failed committed real CB can retry only if no newer producer has
     * claimed this exact epoch.  Re-attach the old ticket and allocate a new
     * fence value for the retry; otherwise fail-open rather than replaying
     * data that a newer source may overwrite.
     */
    private func reacquireProductionSlotForMandatoryRealRetryLocked(
        _ pending: Ticket,
        ticket: UInt64
    ) -> Bool {
        guard pending.isProductionTicket,
              pending.admissionDetached,
              slots.indices.contains(pending.slot),
              slots[pending.slot].ownerTicket == nil,
              slots[pending.slot].ownershipEpoch == pending.slotEpoch,
              slots[pending.slot].nextFenceSignalValue != 0 else {
            return false
        }
        // A history state is safe for the same ticket to retry; it already
        // prevents a new producer from selecting the slot.  A free state is
        // temporarily re-reserved so no producer can race this retry.
        if slots[pending.slot].state == .free {
            slots[pending.slot].state = .realFrameReserved
        } else if slots[pending.slot].state != .history {
            return false
        }
        slots[pending.slot].ownerTicket = ticket
        pending.admissionDetached = false
        pending.realPresentationFenceValue = slots[pending.slot].nextFenceSignalValue
        slots[pending.slot].nextFenceSignalValue &+= 1
        guard slots[pending.slot].nextFenceSignalValue != 0 else { return false }
        pending.realPresentationFencePublished = false
        pending.realPresentationFenceCpuSignaled = false
        pending.realPresentationCompleted = false
        pending.realPresentationCommitInProgress = false
        pending.deferredRealPresentationCompletionSucceeded = nil
        pending.realPresentationCompletionFinalized = false
        pending.realPresentationPublicationFailed = false
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
        commandBuffer.addCompletedHandler { [weak self] completed in
            guard let self else { return }
            MetallumExtendedProMotionSchedulerRegistry.shared.scheduler(for: self.layer)
                .recordRenderCompletion(completed)
            self.markTicketCompleted(ticket, commandBuffer: completed)
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
        // This source already exists in the renderer's offscreen textures.
        // Capture its cadence before bounded FI admission can inject transport
        // queue time into the current frame's temporal delta.
        let sourceReadyTimestampNanoseconds = MetallumMonotonicClock.nowNanoseconds()
        // A resize/output-generation transition must never interpolate a
        // fixed-size ring into a newly sized drawable.  Stop admission here;
        // the normal real-only path remains fail-open until Java installs the
        // matching generation.
        guard layerMatchesGenerationSize() else {
            invalidateForLayerMutation()
            return .bypassGeneration
        }
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

        // Cadence is sampled only after the renderer command buffer commits.
        // This early plan merely proves that the current display/VSync
        // contract can keep mandatory real frames coordinator-owned while the
        // accepted-source clock warms up.
        let promotionScheduler = MetallumExtendedProMotionSchedulerRegistry.shared.scheduler(
            for: layer
        )
        guard let pacingPlan = promotionScheduler.frameInterpolationBaseRealPlan(
            displaySyncEnabled: layer.displaySyncEnabled
        ) else {
            return .bypassUnsupported
        }

        let ticket: UInt64
        let slot: Int
        let renderReadyEventValue: UInt64
        let interpolationReadyEventValue: UInt64
        let presentation = ProductionPresentation(
            frame: frame,
            sourceReadyTimestampNanoseconds: sourceReadyTimestampNanoseconds,
            pacingPlan: pacingPlan,
            allowsInterpolation: false,
            sourceDeltaSeconds: 0,
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
        // Three ring slots hold retained history plus up to two live producer
        // tickets.  N+1 is accepted while N is presenting and is later drained
        // in ticket order on the one presentation queue; a third ticket falls
        // back only after that bounded pipeline is full.
        // The presenter no longer throttles GLFW's producer through
        // swapBuffers.  Bound the producer here for a couple of output slots
        // before fail-open, keeping a fast VSync-on stream coordinator-owned
        // instead of repeatedly falling back/re-priming.
        let admissionDeadline = Date(
            timeIntervalSinceNow: Double(Self.productionAdmissionTimeoutNanoseconds)
                / 1_000_000_000.0
        )
        var admissionWaitStartedAt: UInt64?
        while productionAdmissionCountLocked() >= Self.maximumProductionTickets
                || slots.firstIndex(where: { $0.state == .free }) == nil {
            if admissionWaitStartedAt == nil {
                admissionWaitStartedAt = MetallumMonotonicClock.nowNanoseconds()
            }
            guard state.wait(until: admissionDeadline), !shuttingDown, acceptingFrames else {
                let waitNanoseconds = admissionWaitStartedAt.map {
                    MetallumMonotonicClock.nowNanoseconds() &- $0
                } ?? 0
                state.unlock()
                MetallumFrameInterpolationTelemetry.shared.recordAdmissionWait(
                    nanoseconds: waitNanoseconds
                )
                MetallumFrameInterpolationTelemetry.shared.recordBackpressureDrop()
                return .bypassBackpressure
            }
        }
        let admissionWaitNanoseconds = admissionWaitStartedAt.map {
            MetallumMonotonicClock.nowNanoseconds() &- $0
        }
        guard let reservedSlot = slots.firstIndex(where: {
                  $0.state == .free && $0.ownerTicket == nil
              }),
              nextTicket != 0,
              nextRenderReadyEventValue != 0,
              nextInterpolationReadyEventValue != 0 else {
            state.unlock()
            if let admissionWaitNanoseconds {
                MetallumFrameInterpolationTelemetry.shared.recordAdmissionWait(
                    nanoseconds: admissionWaitNanoseconds
                )
            }
            MetallumFrameInterpolationTelemetry.shared.recordBackpressureDrop()
            return .bypassBackpressure
        }
        ticket = nextTicket
        nextTicket &+= 1
        renderReadyEventValue = nextRenderReadyEventValue
        nextRenderReadyEventValue &+= 1
        interpolationReadyEventValue = nextInterpolationReadyEventValue
        nextInterpolationReadyEventValue &+= 1
        slot = reservedSlot
        guard let reservation = reserveProductionSlotLocked(slot, ticket: ticket) else {
            state.unlock()
            if let admissionWaitNanoseconds {
                MetallumFrameInterpolationTelemetry.shared.recordAdmissionWait(
                    nanoseconds: admissionWaitNanoseconds
                )
            }
            MetallumFrameInterpolationTelemetry.shared.recordBackpressureDrop()
            return .bypassBackpressure
        }
        tickets[ticket] = Ticket(
            slot: slot,
            commandBuffer: commandBuffer,
            production: presentation,
            slotEpoch: reservation.epoch,
            reuseFence: reservation.fence,
            reuseFenceWaitValue: reservation.waitValue,
            realPresentationFenceValue: reservation.signalValue,
            isProductionTicket: true
        )
        tickets[ticket]?.renderReadyEventValue = renderReadyEventValue
        tickets[ticket]?.interpolationReadyEventValue = interpolationReadyEventValue
        state.unlock()
        if let admissionWaitNanoseconds {
            MetallumFrameInterpolationTelemetry.shared.recordAdmissionWait(
                nanoseconds: admissionWaitNanoseconds
            )
        }

        // `metallum_encodePresentationWorld` sees the current Temporal output
        // recorded on this exact renderer command buffer.  It performs world
        // tone mapping/reconstruction before UI, preserving the required
        // world-only interpolation input.
        // The only producer-side dependency for this reused ring slot is
        // inserted before *any* coordinator texture write.  A previous real
        // CB will signal this value after it consumes the old UI/world images.
        if reservation.waitValue > 0 {
            commandBuffer.encodeWaitForEvent(reservation.fence, value: reservation.waitValue)
        }
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
        // All producer writes precede this signal.  A later FI command buffer
        // is made from this exact renderer queue after the signal: that keeps
        // a linked Temporal scaler and interpolation serial without assigning
        // drawable ownership to the renderer queue.
        if let renderReadyEvent = renderReadyEvent {
            commandBuffer.encodeSignalEvent(
                renderReadyEvent,
                value: renderReadyEventValue
            )
        }
        commandBuffer.addCompletedHandler { [weak self] completed in
            guard let self else { return }
            MetallumExtendedProMotionSchedulerRegistry.shared.scheduler(for: self.layer)
                .recordRenderCompletion(completed)
            self.markTicketCompleted(ticket, commandBuffer: completed)
        }
        outTicket.pointee = ticket
        return .prepared
    }

    private func discardPreparedProductionTicket(_ ticket: UInt64) {
        state.lock()
        defer { state.unlock() }
        guard let pending = tickets.removeValue(forKey: ticket) else { return }
        if pending.isProductionTicket {
            retireProductionTicketReservationLocked(pending, ticket: ticket)
        } else {
            releaseSlotLocked(pending.slot)
        }
    }

    /** Publishes only an already-committed renderer command buffer. */
    func publishCommitted(ticket: UInt64) -> TicketStatus {
        var producerNotification: (MTLSharedEvent, UInt64)?
        var producerAlreadyFailed = false
        var fiPredecessorSlot: Int?
        state.lock()
        guard let pending = tickets[ticket], pending.state == .prepared else {
            state.unlock()
            return .staleTicket
        }
        guard pending.commandBuffer.status != .notEnqueued else {
            state.unlock()
            return .staleTicket
        }
        if pending.production != nil, pending.producerFailed {
            producerAlreadyFailed = true
        } else if let production = pending.production,
           let renderReadyEvent = renderReadyEvent {
            configureProductionCadenceAtPublishLocked(
                pending,
                acceptedTimestampNanoseconds: production.sourceReadyTimestampNanoseconds
            )
            pending.state = .published
            // A committed producer can otherwise neither signal nor complete
            // (for example after a driver fault).  This deadline is armed at
            // publish, never from a presentation callback.
            pending.producerFailureWatchdogDeadline = MetallumMonotonicClock
                .nowNanoseconds() &+ Self.producerSignalWatchdogMaximumNanoseconds
            producerNotification = (renderReadyEvent, pending.renderReadyEventValue)
            fiPredecessorSlot = reserveProductionRouteAtPublishLocked(pending)
        } else {
            pending.state = .published
        }
        if pending.commandBufferCompleted && pending.production == nil {
            tickets.removeValue(forKey: ticket)
            releaseSlotLocked(pending.slot)
        }
        state.unlock()
        if producerAlreadyFailed {
            abandonProductionTicket(ticket)
            return .transientFailure
        }
        // This call is deliberately synchronous with Java's submit boundary:
        // `publishCommitted` runs after producer commit but before Java can
        // create/commit N+1, so this FI CB is queue-ordered as N -> FI -> N+1.
        if let fiPredecessorSlot,
           !encodeProductionInterpolation(ticket: ticket, previousSlot: fiPredecessorSlot) {
            interpolationFailed(ticket)
        }
        if producerNotification != nil {
            // If completion raced ahead of publish with an error, this wake
            // installs its bounded producer-failure watchdog even though the
            // GPU signal will never arrive.
            schedulerWake.signal()
        }
        if let (event, value) = producerNotification {
            // The callback fires as soon as the GPU executes the signal after
            // our last producer write, rather than after the whole Java command
            // buffer reaches its completion handler.
            event.notify(eventListener, atValue: value) { [weak self] _, _ in
                self?.producerOutputSignaled(ticket: ticket, eventValue: value)
            }
        }
        return .prepared
    }

    /**
     * Advances source cadence only at the exact commit/publish frontier, using
     * the retained pre-admission time at which this already-rendered source
     * entered the coordinator. The game delta can include frames FI never
     * accepted; the post-admission publish time incorrectly includes FI's own
     * transport wait in the current source.
     */
    private func configureProductionCadenceAtPublishLocked(
        _ pending: Ticket,
        acceptedTimestampNanoseconds: UInt64
    ) {
        guard var production = pending.production else { return }
        let frame = production.frame
        let previous = lastAcceptedProductionSource
        let isSequential = previous?.isImmediatePredecessor(of: frame) ?? false
        let sourceDeltaSeconds: Double?
        if frame.resetMask == 0,
           let previous,
           isSequential {
            sourceDeltaSeconds = Self.acceptedSourceDeltaSeconds(
                previousTimestampNanoseconds: previous.timestampNanoseconds,
                acceptedTimestampNanoseconds: acceptedTimestampNanoseconds
            )
        } else {
            sourceDeltaSeconds = nil
        }
        let validSchedulerSourceDelta = sourceDeltaSeconds.map {
            $0.isFinite
                && $0 >= MetallumExtendedProMotionScheduler.minimumInterpolationSourceInterval
                && $0
                    <= MetallumExtendedProMotionScheduler.maximumInterpolationSourceSampleInterval
        } ?? false
        let recoverableTimingGap = Self.isRecoverableProductionTimingGap(
            hasPrevious: previous != nil,
            isSequential: isSequential,
            resetMask: frame.resetMask,
            sourceDeltaSeconds: sourceDeltaSeconds
        )
        let repeatedRecoverableTimingGap = Self.shouldEscalateRecoverableTimingGap(
            recoverable: recoverableTimingGap,
            gapAlreadyPending: productionTimingGapPending
        )
        let cadenceDiscontinuity = frame.resetMask != 0
            || (previous != nil
                && (!isSequential
                    || (!validSchedulerSourceDelta && !recoverableTimingGap)
                    || repeatedRecoverableTimingGap))
        let promotionScheduler = MetallumExtendedProMotionSchedulerRegistry.shared.scheduler(
            for: layer
        )
        if cadenceDiscontinuity {
            productionTimingGapPending = false
            productionNeedsReprime = true
            productionInterpolatorNeedsReset = true
            // The discontinuity is between the previous source and this new
            // one.  Re-prime future history without cancelling a previously
            // accepted pair that is already waiting on the PresentThread.
            promotionScheduler.resetFrameInterpolationCadence(
                preservingAcceptedPlans: true
            )
        } else if recoverableTimingGap {
            // One isolated long hand-off may be timer jitter. A second
            // consecutive gap is escalated above so a genuinely slow stream
            // cannot resume against a stale armed scheduler window.
            productionTimingGapPending = true
            productionInterpolatorNeedsReset = true
        } else {
            productionTimingGapPending = false
        }

        lastAcceptedProductionSource = AcceptedProductionSource(
            timestampNanoseconds: acceptedTimestampNanoseconds,
            frame: frame
        )
        MetallumFrameInterpolationTelemetry.shared.recordSourceAttempt(
            deltaSeconds: sourceDeltaSeconds
        )
        productionOnGlassHealth.observeProductionSource()

        let pacingDecision: MetallumExtendedProMotionScheduler.InterpolationDecision
        if productionOnGlassHealth.runtimeState == .unavailable {
            pacingDecision = .init(plan: nil, rejection: .disabled)
        } else if validSchedulerSourceDelta, let sourceDeltaSeconds {
            pacingDecision = promotionScheduler.frameInterpolationPlan(
                realDeltaSeconds: sourceDeltaSeconds,
                displaySyncEnabled: layer.displaySyncEnabled
            )
        } else {
            pacingDecision = .init(
                plan: nil,
                rejection: previous == nil || frame.resetMask != 0 || !isSequential
                    ? .warmingUp
                    : sourceDeltaSeconds.map {
                        $0 < MetallumExtendedProMotionScheduler.minimumInterpolationSourceInterval
                            ? .realCadenceTooFast : .realCadenceTooSlow
                    } ?? .warmingUp
            )
        }
        if let acceptedPlan = pacingDecision.plan, let sourceDeltaSeconds {
            MetallumFrameInterpolationTelemetry.shared.recordSchedulerAccepted()
            production.pacingPlan = acceptedPlan
            production.allowsInterpolation = true
            production.sourceDeltaSeconds = sourceDeltaSeconds
        } else {
            MetallumFrameInterpolationTelemetry.shared.recordSchedulerRejection(
                pacingDecision.rejection
            )
            if let currentBasePlan = promotionScheduler.frameInterpolationBaseRealPlan(
                displaySyncEnabled: layer.displaySyncEnabled
            ) {
                production.pacingPlan = currentBasePlan
            }
            production.allowsInterpolation = false
            production.sourceDeltaSeconds = 0
        }
        pending.production = production
    }

    private static func acceptedSourceDeltaSeconds(
        previousTimestampNanoseconds: UInt64,
        acceptedTimestampNanoseconds: UInt64
    ) -> Double? {
        guard acceptedTimestampNanoseconds > previousTimestampNanoseconds else { return nil }
        return Double(acceptedTimestampNanoseconds - previousTimestampNanoseconds)
            / 1_000_000_000.0
    }

    private static func isRecoverableProductionTimingGap(
        hasPrevious: Bool,
        isSequential: Bool,
        resetMask: UInt64,
        sourceDeltaSeconds: Double?
    ) -> Bool {
        guard hasPrevious,
              isSequential,
              resetMask == 0,
              let sourceDeltaSeconds,
              sourceDeltaSeconds.isFinite else {
            return false
        }
        return sourceDeltaSeconds
                > MetallumExtendedProMotionScheduler.maximumInterpolationSourceSampleInterval
            && sourceDeltaSeconds <= Self.maximumRecoverableProductionTimingGapSeconds
    }

    /** Deterministic boundary proof for isolated-gap recovery. */
    static func productionTimingGapRecoveryStressForTests() -> Bool {
        isRecoverableProductionTimingGap(
            hasPrevious: true,
            isSequential: true,
            resetMask: 0,
            sourceDeltaSeconds: 0.075
        )
            && !isRecoverableProductionTimingGap(
                hasPrevious: true,
                isSequential: true,
                resetMask: 0,
                sourceDeltaSeconds: 0.040
            )
            && !isRecoverableProductionTimingGap(
                hasPrevious: true,
                isSequential: false,
                resetMask: 0,
                sourceDeltaSeconds: 0.075
            )
            && !isRecoverableProductionTimingGap(
                hasPrevious: true,
                isSequential: true,
                resetMask: 1,
                sourceDeltaSeconds: 0.075
            )
            && !isRecoverableProductionTimingGap(
                hasPrevious: true,
                isSequential: true,
                resetMask: 0,
                sourceDeltaSeconds: 0.101
            )
            && !shouldEscalateRecoverableTimingGap(
                recoverable: true,
                gapAlreadyPending: false
            )
            && shouldEscalateRecoverableTimingGap(
                recoverable: true,
                gapAlreadyPending: true
            )
            && !shouldEscalateRecoverableTimingGap(
                recoverable: false,
                gapAlreadyPending: true
            )
    }

    private static func shouldEscalateRecoverableTimingGap(
        recoverable: Bool,
        gapAlreadyPending: Bool
    ) -> Bool {
        recoverable && gapAlreadyPending
    }

    /** Deterministic proof for the session-local on-glass circuit breaker. */
    static func productionOnGlassHealthStressForTests() -> Bool {
        func feedback(
            missed: Bool = false,
            severe: Bool = false,
            valid: Bool = true,
            outOfOrder: Bool = false,
            retarget: Bool = false,
            unexpected: Bool = false,
            tracked: Bool = true
        ) -> MetallumExtendedProMotionScheduler.OnGlassPresentationFeedback {
            .init(
                currentGeneration: true,
                validTimestamp: valid,
                trackedPresentation: tracked,
                comparableFiInterval: !retarget && !unexpected && valid,
                unexpectedFiTransition: unexpected,
                targetMissed: missed,
                severeLate: severe,
                outOfOrder: outOfOrder,
                retargetBoundary: retarget
            )
        }
        func sample(
            _ gate: inout ProductionOnGlassHealthGate,
            _ observation: MetallumExtendedProMotionScheduler.OnGlassPresentationFeedback,
            generated: Bool = true
        ) -> Bool {
            gate.observe(observation, generatedPresentation: generated)
        }

        var healthy = ProductionOnGlassHealthGate()
        guard healthy.runtimeState == .warming,
              healthy.runtimeReason == .awaitingProductionSource else { return false }
        healthy.observeProductionSource()
        guard healthy.runtimeReason == .measuringOnGlass else { return false }
        for _ in 0..<(ProductionOnGlassHealthGate.evaluationIntervalCount - 1) {
            guard !sample(&healthy, feedback()) else { return false }
        }
        guard healthy.runtimeState == .warming,
              !sample(&healthy, feedback()),
              healthy.runtimeState == .active,
              healthy.runtimeReason == .none else {
            return false
        }
        guard !sample(&healthy, feedback(retarget: true)),
              healthy.runtimeState == .warming,
              healthy.runtimeReason == .measuringOnGlass else {
            return false
        }

        var cadenceFailure = ProductionOnGlassHealthGate()
        for index in 0..<ProductionOnGlassHealthGate.evaluationIntervalCount {
            let tripped = sample(&cadenceFailure, feedback(
                missed: index < 3,
                severe: index < 2
            ))
            if index + 1 < ProductionOnGlassHealthGate.evaluationIntervalCount, tripped {
                return false
            }
        }
        guard cadenceFailure.runtimeState == .unavailable,
              cadenceFailure.runtimeReason == .onGlassCadence,
              !sample(&cadenceFailure, feedback()) else {
            return false
        }

        var timestampFailure = ProductionOnGlassHealthGate()
        guard !sample(&timestampFailure, feedback(valid: false)),
              sample(&timestampFailure, feedback(valid: false)),
              timestampFailure.runtimeState == .unavailable,
              timestampFailure.runtimeReason == .onGlassTimestamp else {
            return false
        }
        var orderingFailure = ProductionOnGlassHealthGate()
        guard !sample(&orderingFailure, feedback(outOfOrder: true)),
              sample(&orderingFailure, feedback(outOfOrder: true)),
              orderingFailure.runtimeState == .unavailable else {
            return false
        }
        var untrackedRealOnly = ProductionOnGlassHealthGate()
        guard !sample(
                &untrackedRealOnly,
                feedback(valid: false, tracked: false),
                generated: false
              ),
              untrackedRealOnly.runtimeState == .warming else {
            return false
        }
        var missingMember = ProductionOnGlassHealthGate()
        for index in 0..<ProductionOnGlassHealthGate.evaluationIntervalCount {
            let tripped = sample(
                &missingMember,
                feedback(unexpected: index < 2),
                generated: index % 2 == 0
            )
            if index + 1 < ProductionOnGlassHealthGate.evaluationIntervalCount, tripped {
                return false
            }
        }
        guard missingMember.runtimeState == .unavailable,
              missingMember.runtimeReason == .onGlassCadence else {
            return false
        }
        return true
    }

    /** Deterministic no-sleep proof for the accepted-source clock. */
    static func acceptedSourceClockStressForTests() -> Bool {
        var baseline: UInt64?
        func accept(_ timestamp: UInt64) -> Double? {
            defer { baseline = timestamp }
            guard let baseline else { return nil }
            return acceptedSourceDeltaSeconds(
                previousTimestampNanoseconds: baseline,
                acceptedTimestampNanoseconds: timestamp
            )
        }

        guard accept(1_000_000_000) == nil else { return false }
        // A rejected/non-published attempt at 1_008_333_333 deliberately does
        // not call accept and therefore cannot advance the source clock.
        guard let first = accept(1_016_666_667),
              abs(first - 0.016_666_667) < 0.000_000_001,
              let second = accept(1_033_333_334),
              abs(second - 0.016_666_667) < 0.000_000_001,
              accept(1_033_333_334) == nil else {
            return false
        }
        baseline = nil
        guard accept(9_000_000_000) == nil,
              let afterReset = accept(9_016_666_667),
              abs(afterReset - 0.016_666_667) < 0.000_000_001 else {
            return false
        }

        // Alternating 0/13-ms transport waits wildly distort publish deltas
        // (38 then 12 ms), but these already-rendered sources remain exactly
        // 25 ms apart. A real overrun still appears in the following ready
        // timestamp rather than being hidden.
        baseline = nil
        let readyTimes: [UInt64] = [20_000_000_000, 20_025_000_000, 20_050_000_000]
        let publishTimes: [UInt64] = [20_000_000_000, 20_038_000_000, 20_050_000_000]
        guard publishTimes[1] - publishTimes[0] == 38_000_000,
              publishTimes[2] - publishTimes[1] == 12_000_000,
              accept(readyTimes[0]) == nil,
              let stableOne = accept(readyTimes[1]),
              abs(stableOne - 0.025) < 0.000_000_001,
              let stableTwo = accept(readyTimes[2]),
              abs(stableTwo - 0.025) < 0.000_000_001,
              let trueOverrun = accept(20_090_000_000),
              abs(trueOverrun - 0.040) < 0.000_000_001 else {
            return false
        }
        return true
    }

    /**
     * Reserves the render-history chain while Java is still at the commit
     * frontier.  It performs no presentation and no GPU wait.
     */
    private func reserveProductionRouteAtPublishLocked(_ pending: Ticket) -> Int? {
        guard let production = pending.production else { return nil }
        let mustReprime = production.frame.resetMask != 0 || productionNeedsReprime
        if mustReprime {
            resetInterpolationHistoryLocked()
            productionNeedsReprime = false
            reasonCounters.reset += 1
        }
        let predecessor = productionHistorySlot
        let canInterpolate = production.allowsInterpolation
            && !mustReprime
            && productionPrimingFrames >= 2
            && activeProductionFiJobs < Self.maximumProductionTickets
            && predecessor != nil
            && predecessor != pending.slot
            && production.sourceDeltaSeconds.isFinite
            && production.sourceDeltaSeconds
                >= MetallumExtendedProMotionScheduler.minimumInterpolationSourceInterval
            && production.sourceDeltaSeconds
                <= MetallumExtendedProMotionScheduler.maximumInterpolationSourceSampleInterval

        // A committed source can immediately become the predecessor for N+1;
        // its ticket lease still prevents reuse until mandatory real composite
        // completion, and an FI reader lease protects it after that point.
        productionHistorySlot = pending.slot
        productionPrimingFrames = min(productionPrimingFrames + 1, 2)
        pending.productionEpoch = resetEpoch

        guard canInterpolate, let predecessor else {
            MetallumFrameInterpolationTelemetry.shared.recordCoordinatorRealOnly()
            if let predecessor {
                releaseHistoryLeaseIfPossibleLocked(predecessor)
            }
            return nil
        }
        guard fiReaderLeases.indices.contains(predecessor) else { return nil }
        fiReaderLeases[predecessor] += 1
        fiReaderLeases[pending.slot] += 1
        activeProductionFiJobs += 1
        pending.fiEnqueued = true
        pending.fiPredecessorSlot = predecessor
        pending.generatedPresentationEncoded = true
        if predecessor != pending.slot {
            releaseHistoryLeaseIfPossibleLocked(predecessor)
        }
        return predecessor
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
        if pending.production != nil {
            retireProductionTicketReservationLocked(pending, ticket: ticket)
        } else {
            releaseSlotLocked(pending.slot)
        }
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
        // Reset invalidates only coordinator-owned history/resources.  The
        // next admitted ticket or normal real-only fallback owns presentation.
        resetEpoch &+= 1
        resetInterpolationHistoryLocked()
        resetProductionCadenceLocked()
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
        guard hasOutstandingPresentationWorkLocked() else {
            state.unlock()
            return key.isDrawableSized ? .ready : .suspendedZeroSize
        }

        let timeoutSeconds = Double(timeoutNanoseconds) / 1_000_000_000.0
        let deadline = Date(timeIntervalSinceNow: max(timeoutSeconds, 0.0))
        while hasOutstandingPresentationWorkLocked() {
            if !state.wait(until: deadline) {
                state.unlock()
                return .drainTimedOut
            }
        }
        state.unlock()
        return key.isDrawableSized ? .ready : .suspendedZeroSize
    }

    /** Includes orphaned FI readers whose ticket was fail-open-abandoned. */
    private func hasOutstandingPresentationWorkLocked() -> Bool {
        pendingRealFrames > 0
            || pendingPacingWorks > 0
            || productionPacingWork != nil
            || productionMidpointSignal != nil
            || activeProductionFiJobs > 0
            || activePresentationCommandBuffers > 0
    }

    func release(after timeoutNanoseconds: UInt64) -> LifecycleStatus {
        state.lock()
        let resumingShutdown = shuttingDown
        state.unlock()

        if !resumingShutdown {
            let drainStatus = drain(timeoutNanoseconds: timeoutNanoseconds)
            guard drainStatus == .ready || drainStatus == .suspendedZeroSize else {
                return drainStatus
            }

            state.lock()
            shuttingDown = true
            acceptingFrames = false
            state.unlock()
        }

        // Both signals are idempotent and make a timed-out release resumable:
        // a later call retries only workers whose stop semaphore has not yet
        // been consumed.
        schedulerWake.signal()
        productionPacingWake.signal()

        state.lock()
        let needsSchedulerJoin = !schedulerExited
        let needsPacingJoin = !productionPacingExited
        state.unlock()
        let schedulerJoined = !needsSchedulerJoin
            || schedulerStopped.wait(timeout: .now() + .seconds(2)) == .success
        let pacingJoined = !needsPacingJoin
            || productionPacingStopped.wait(timeout: .now() + .seconds(2)) == .success

        state.lock()
        if schedulerJoined { schedulerExited = true }
        if pacingJoined { productionPacingExited = true }
        let workersExited = schedulerExited && productionPacingExited
        guard workersExited else {
            state.unlock()
            // Do not retire native resources while their owner thread could
            // still observe them.  A later release may retry the bounded join.
            return .drainTimedOut
        }

        slots.removeAll(keepingCapacity: false)
        textureAllocationCount = 0
        completionEvent = nil
        renderReadyEvent = nil
        interpolationReadyEvent = nil
        realPresentGate = nil
        presentationQueue = nil
        renderQueue = nil
        layer.maximumDrawableCount = priorMaximumDrawableCount
        state.unlock()
        MetallumFrameInterpolationPresentationRegistry.shared.remove(self, layer: layer)
        return .ready
    }

    private func markTicketCompleted(_ ticket: UInt64, commandBuffer: MTLCommandBuffer) {
        let succeeded = commandBuffer.status == .completed && commandBuffer.error == nil
        state.lock()
        guard let pending = tickets[ticket] else {
            state.unlock()
            return
        }
        pending.commandBufferCompleted = true
        guard pending.state == .published else {
            if !succeeded, pending.production != nil {
                pending.producerFailureWatchdogDeadline = MetallumMonotonicClock
                    .nowNanoseconds() &+ Self.producerSignalWatchdogMaximumNanoseconds
            }
            state.unlock()
            return
        }
        if pending.production != nil {
            if !succeeded {
                if pending.producerOutputSignaled {
                    // The producer event sits after all textures consumed by
                    // FI. A later CB error cannot reclaim those in-flight
                    // resources; retain them and let normal presentation
                    // completion own their lifetime.
                    state.unlock()
                    return
                }
                pending.producerFailureWatchdogDeadline = MetallumMonotonicClock
                    .nowNanoseconds() &+ Self.producerSignalWatchdogMaximumNanoseconds
                state.unlock()
                // Wait briefly for a signal that may have reached the GPU
                // before the completion callback. If it never arrives, the
                // existing worker watchdog abandons without an unsignaled wait.
                schedulerWake.signal()
                return
            }
            state.unlock()
            return
        }
        tickets.removeValue(forKey: ticket)
        releaseSlotLocked(pending.slot)
        state.unlock()
    }

    private func producerOutputSignaled(ticket: UInt64, eventValue: UInt64) {
        state.lock()
        guard let pending = tickets[ticket],
              pending.production != nil,
              pending.state == .published,
              pending.renderReadyEventValue == eventValue,
              !pending.producerFailed else {
            state.unlock()
            return
        }
        pending.producerOutputSignaled = true
        pending.producerFailureWatchdogDeadline = nil
        state.unlock()
        tryScheduleProductionPresentation()
    }

    /** Starts only the oldest ready ticket, preserving G_N,R_N FIFO ordering. */
    private func tryScheduleProductionPresentation() {
        state.lock()
        guard !shuttingDown,
              activePresentationTicket == nil,
              productionPacingWork == nil,
              productionMidpointSignal == nil,
              let ticket = oldestUnscheduledProductionTicketLocked(),
              let pending = tickets[ticket],
              pending.producerOutputSignaled,
              !pending.productionScheduled else {
            state.unlock()
            return
        }
        let includeGenerated = pending.generatedPresentationEncoded
            && !pending.generatedPresentationSuppressed
            && pending.productionEpoch == resetEpoch
        guard !includeGenerated || pending.interpolationOutputSignaled else {
            state.unlock()
            return
        }
        pending.productionScheduled = true
        activePresentationTicket = ticket
        productionPacingWork = ProductionPacingWork(
            ticket: ticket,
            includeGenerated: includeGenerated
        )
        state.unlock()
        schedulerWake.signal()
    }

    /** Already-committed R_N must not hide the queued N+1 successor. */
    private func oldestUnscheduledProductionTicketLocked() -> UInt64? {
        var oldest: UInt64?
        for (ticket, pending) in tickets
                where pending.production != nil && pending.state == .published && !pending.abandoned {
            if Self.shouldSelectUnscheduledPresentationTicket(
                ticket: ticket,
                scheduled: pending.productionScheduled,
                currentOldest: oldest
            ) {
                oldest = ticket
            }
        }
        return oldest
    }

    private static func shouldSelectUnscheduledPresentationTicket(
        ticket: UInt64,
        scheduled: Bool,
        currentOldest: UInt64?
    ) -> Bool {
        guard !scheduled else { return false }
        guard let currentOldest else { return true }
        return ticket < currentOldest
    }

    /** Deterministic regression for release-at-commit successor selection. */
    static func presentationTurnPipeliningStressForTests() -> Bool {
        var oldest: UInt64?
        for (ticket, scheduled) in [(7 as UInt64, true), (9, false), (8, false)] {
            if shouldSelectUnscheduledPresentationTicket(
                ticket: ticket,
                scheduled: scheduled,
                currentOldest: oldest
            ) {
                oldest = ticket
            }
        }
        return oldest == 8
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
              slots[previousSlot].state != .free,
              fiReaderLeases.indices.contains(previousSlot),
              fiReaderLeases[previousSlot] > 0,
              pending.fiEnqueued,
              let interpolator = interpolator as? MTLFXFrameInterpolator,
              let interpolationReadyEvent = interpolationReadyEvent,
              let depth = slots[pending.slot].depth,
              let motion = slots[pending.slot].motion else {
            state.unlock()
            return false
        }
        let currentSlot = pending.slot
        let interpolationReadyEventValue = pending.interpolationReadyEventValue
        let resetsInterpolator = productionInterpolatorNeedsReset
        let frame = production.frame
        let verticalProjectionScale = abs(frame.currentUnjitteredProjection.columns.1.y)
        guard verticalProjectionScale.isFinite, verticalProjectionScale > 0.0001 else {
            state.unlock()
            return false
        }
        let fieldOfView = Float(2.0 * atan(1.0 / Double(verticalProjectionScale)) * 180.0 / Double.pi)
        // Apple requires FI to follow rendering on the *same* command queue.
        // This method runs synchronously from publish after producer commit,
        // before Java can submit N+1, so this is its queue-ordered continuation.
        // `renderQueue` remains validation-only; a second production queue
        // would race a linked Temporal scaler's mutable input state.
        let producerQueue = pending.commandBuffer.commandQueue
        guard fieldOfView.isFinite, fieldOfView > 1.0, fieldOfView < 179.0,
              let commandBuffer = producerQueue.makeCommandBuffer() else {
            state.unlock()
            return false
        }
        pending.resetsInterpolator = resetsInterpolator
        let deltaSeconds = production.sourceDeltaSeconds
        state.unlock()

        // This FI command buffer is committed synchronously immediately after
        // its producer on the exact same queue. Queue order is the producer
        // dependency; an additional shared-event wait would be redundant and
        // could leave an uncancellable FI job behind a producer that faults
        // before signaling.
        interpolator.colorTexture = slots[currentSlot].realColor
        interpolator.prevColorTexture = slots[previousSlot].realColor
        interpolator.depthTexture = depth
        interpolator.motionTexture = motion
        interpolator.outputTexture = slots[currentSlot].generatedColor
        interpolator.deltaTime = Float(deltaSeconds)
        interpolator.nearPlane = max(frame.nearPlane, 0.0001)
        interpolator.farPlane = max(frame.farPlane, interpolator.nearPlane + 0.001)
        interpolator.fieldOfView = fieldOfView
        interpolator.aspectRatio = Float(key.width) / Float(key.height)
        // Spatial resolve is not jittered. Do not leak a stale Temporal Halton
        // offset into a standalone FI profile.
        interpolator.jitterOffsetX = key.usesSpatialInputs ? 0.0 : frame.jitterX
        interpolator.jitterOffsetY = key.usesSpatialInputs ? 0.0 : frame.jitterY
        if key.usesSpatialInputs {
            let motionScale = Self.spatialMotionScale(
                displayWidth: key.width, displayHeight: key.height,
                renderWidth: key.inputWidth, renderHeight: key.inputHeight
            )
            interpolator.motionVectorScaleX = motionScale.x
            interpolator.motionVectorScaleY = motionScale.y
        } else {
            // Linked Fixed Temporal inputs use the scaler's native motion
            // convention; their calibrated MetalFX scale is identity.
            interpolator.motionVectorScaleX = 1.0
            interpolator.motionVectorScaleY = 1.0
        }
        interpolator.isDepthReversed = true
        interpolator.shouldResetHistory = resetsInterpolator
        interpolator.encode(commandBuffer: commandBuffer)
        // Signal only after MetalFX has written the generated world.  The
        // listener starts presentation at this GPU point rather than waiting
        // for the interpolation command buffer completion callback.
        commandBuffer.encodeSignalEvent(
            interpolationReadyEvent,
            value: interpolationReadyEventValue
        )
        interpolationReadyEvent.notify(
            eventListener,
            atValue: interpolationReadyEventValue
        ) { [weak self] _, _ in
            self?.interpolationOutputSignaled(
                ticket: ticket,
                eventValue: interpolationReadyEventValue
            )
        }
        commandBuffer.addCompletedHandler { [weak self] buffer in
            self?.completeProductionFiJob(ticket)
            guard buffer.status == .completed, buffer.error == nil else {
                self?.interpolationFailed(ticket)
                return
            }
        }
        commandBuffer.commit()
        consumeInterpolatorResetAtPublish(ticket)
        MetallumFrameInterpolationTelemetry.shared.recordAcceptedPair()
        return true
    }

    /** The reset bit belongs to the synchronous queue submission, not its callback. */
    private func consumeInterpolatorResetAtPublish(_ ticket: UInt64) {
        state.lock()
        guard let pending = tickets[ticket], pending.resetsInterpolator,
              pending.productionEpoch == resetEpoch else {
            state.unlock()
            return
        }
        productionInterpolatorNeedsReset = false
        state.unlock()
    }

    private func completeProductionFiJob(_ ticket: UInt64) {
        state.lock()
        let lease: (predecessor: Int, current: Int)?
        if let pending = tickets[ticket], !pending.fiCompleted {
            pending.fiCompleted = true
            lease = (pending.fiPredecessorSlot ?? pending.slot, pending.slot)
        } else {
            lease = orphanedFiJobs.removeValue(forKey: ticket)
        }
        guard let lease else {
            state.unlock()
            return
        }
        activeProductionFiJobs = max(activeProductionFiJobs - 1, 0)
        for index in Set([lease.predecessor, lease.current]) where fiReaderLeases.indices.contains(index) {
            fiReaderLeases[index] = max(fiReaderLeases[index] - 1, 0)
            releaseHistoryLeaseIfPossibleLocked(index)
        }
        state.broadcast()
        state.unlock()
    }

    private func interpolationOutputSignaled(ticket: UInt64, eventValue: UInt64) {
        state.lock()
        guard let pending = tickets[ticket],
              pending.production != nil,
              pending.state == .published,
              pending.interpolationReadyEventValue == eventValue,
              !pending.interpolationFailed else {
            state.unlock()
            return
        }
        pending.interpolationOutputSignaled = true
        pending.interpolationReadyTimestampNanoseconds = MetallumMonotonicClock.nowNanoseconds()
        if pending.productionEpoch != resetEpoch {
            pending.generatedPresentationSuppressed = true
        }
        state.unlock()
        tryScheduleProductionPresentation()
    }

    private func interpolationFailed(_ ticket: UInt64) {
        state.lock()
        guard let pending = tickets[ticket], pending.production != nil else {
            state.unlock()
            return
        }
        // A signal is placed after the final MetalFX write; if it was reached,
        // the generated surface is valid even if later completion bookkeeping
        // reports an unrelated command-buffer error.
        guard !pending.interpolationOutputSignaled else {
            state.unlock()
            return
        }
        pending.interpolationFailed = true
        MetallumFrameInterpolationTelemetry.shared.recordInterpolationFailure()
        pending.generatedPresentationEncoded = false
        pending.generatedPresentationSuppressed = true
        if !pending.fiCompleted {
            pending.fiCompleted = true
            activeProductionFiJobs = max(activeProductionFiJobs - 1, 0)
            for index in Set([pending.fiPredecessorSlot ?? pending.slot, pending.slot])
                    where fiReaderLeases.indices.contains(index) {
                fiReaderLeases[index] = max(fiReaderLeases[index] - 1, 0)
                releaseHistoryLeaseIfPossibleLocked(index)
            }
        }
        // A completion for an older pair may arrive after a resize/reset and
        // after its physical slot has been reused. Its own leases were retired
        // above, but it must not reset the newer generation's cadence/history.
        guard pending.productionEpoch == resetEpoch else {
            state.unlock()
            return
        }
        // A publish-time encode failure still owns the Java source ticket.
        // Its mandatory real is scheduled once the producer signal arrives.
        // The failed FI may have been followed by a pre-encoded successor.
        // Its generated output is unsafe across this history discontinuity.
        resetEpoch &+= 1
        productionNeedsReprime = true
        resetInterpolationHistoryLocked()
        resetProductionCadenceLocked()
        for candidate in tickets.values where candidate.production != nil && candidate !== pending {
            candidate.generatedPresentationEncoded = false
            candidate.generatedPresentationSuppressed = true
        }
        let needsRealOnlyWake = pending.productionScheduled
        if needsRealOnlyWake, productionPacingWork == nil {
            productionPacingWork = ProductionPacingWork(ticket: ticket, includeGenerated: false)
        }
        state.unlock()
        if needsRealOnlyWake {
            schedulerWake.signal()
        }
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

    /** Allocation-free per-context status consumed by the Java render thread/HUD. */
    func runtimeStatusPacked() -> UInt64 {
        state.lock()
        defer { state.unlock() }
        let count = min(productionPresentedGeneratedCount, 0x0000_FFFF_FFFF_FFFF)
        if shuttingDown {
            return RuntimeState.disabled.rawValue | (count << 16)
        }
        guard interpolator != nil else {
            return RuntimeState.unavailable.rawValue
                | (RuntimeReason.nativeInterpolatorUnavailable.rawValue << 8)
                | (count << 16)
        }
        return productionOnGlassHealth.runtimeState.rawValue
            | (productionOnGlassHealth.runtimeReason.rawValue << 8)
            | (count << 16)
    }

    /**
     * Opens the fail-closed generation gate without cancelling any mandatory
     * real presentation already committed. Future tickets stay real-only;
     * in-flight optional generated members are suppressed where still legal.
     */
    private func observeProductionOnGlass(
        _ feedback: MetallumExtendedProMotionScheduler.OnGlassPresentationFeedback,
        generated: Bool
    ) {
        state.lock()
        if generated, feedback.validTimestamp, feedback.currentGeneration,
           productionPresentedGeneratedCount != UInt64.max {
            productionPresentedGeneratedCount &+= 1
        }
        guard !shuttingDown, productionOnGlassHealth.observe(
            feedback,
            generatedPresentation: generated
        ) else {
            state.unlock()
            return
        }
        resetEpoch &+= 1
        productionNeedsReprime = true
        productionInterpolatorNeedsReset = true
        resetInterpolationHistoryLocked()
        resetProductionCadenceLocked()
        for candidate in tickets.values where candidate.production != nil {
            candidate.generatedPresentationSuppressed = true
        }
        state.broadcast()
        state.unlock()
        schedulerWake.signal()
        productionPacingWake.signal()
    }

    /** Runs only on the dedicated user-interactive presentation worker. */
    private func runProductionPresentation(_ work: ProductionPacingWork) {
        state.lock()
        let production = tickets[work.ticket]?.production
        let interpolationReadyTimestampNanoseconds = tickets[work.ticket]?
            .interpolationReadyTimestampNanoseconds ?? 0
        let healthAllowsGenerated = productionOnGlassHealth.runtimeState != .unavailable
        state.unlock()
        guard let production else {
            abandonProductionTicket(work.ticket)
            return
        }
        let pacingPlan = production.pacingPlan
        guard work.includeGenerated, healthAllowsGenerated else {
            presentProductionReal(work.ticket)
            return
        }

        let promotionScheduler = MetallumExtendedProMotionSchedulerRegistry.shared.scheduler(
            for: layer
        )
        guard promotionScheduler.isCurrent(pacingPlan) else {
            markProductionDiscontinuity()
            recordDroppedProductionGenerated()
            presentProductionReal(work.ticket)
            return
        }
        let pairReadyNanoseconds = interpolationReadyTimestampNanoseconds > 0
            ? interpolationReadyTimestampNanoseconds
            : MetallumMonotonicClock.nowNanoseconds()
        let submissionCompensation = promotionScheduler
            .midpointSubmissionCompensationSeconds(for: pacingPlan)
        let realMidpointDeadline = Self.productionMidpointDeadline(
            readyNanoseconds: pairReadyNanoseconds,
            deltaSeconds: production.sourceDeltaSeconds,
            submissionCompensationSeconds: submissionCompensation
        )
        guard scheduleProductionMidpoint(
            ticket: work.ticket,
            deadlineNanoseconds: realMidpointDeadline
        ) != nil else {
            recordDroppedProductionGenerated()
            presentProductionReal(work.ticket)
            return
        }
        let generatedSubmitted = presentProductionWorld(
            ticket: work.ticket,
            generated: true
        ) { [weak self] succeeded in
            if !succeeded {
                self?.recordDroppedProductionGenerated()
            }
        }
        guard generatedSubmitted else {
            flushProductionMidpoint(ticket: work.ticket, clearTicketGate: true)
            presentProductionReal(work.ticket)
            return
        }

        state.lock()
        let stillCurrent = !shuttingDown && tickets[work.ticket] != nil
        state.unlock()
        guard stillCurrent else {
            abandonProductionTicket(work.ticket)
            return
        }
        presentProductionReal(work.ticket)
    }

    private func scheduleProductionMidpoint(
        ticket: UInt64,
        deadlineNanoseconds: UInt64
    ) -> ProductionPresentationGate? {
        state.lock()
        guard !shuttingDown,
              activePresentationTicket == ticket,
              productionMidpointSignal == nil,
              let event = realPresentGate,
              nextRealPresentGateValue != 0 else {
            state.unlock()
            return nil
        }
        let value = nextRealPresentGateValue
        nextRealPresentGateValue &+= 1
        productionMidpointSignal = ProductionMidpointSignal(
            ticket: ticket,
            deadlineNanoseconds: deadlineNanoseconds,
            eventValue: value
        )
        tickets[ticket]?.realPresentGateValue = value
        state.unlock()
        productionPacingWake.signal()
        return ProductionPresentationGate(event: event, eventValue: value)
    }

    /** Idempotently releases only the gate reserved by this production pair. */
    private func flushProductionMidpoint(ticket: UInt64, clearTicketGate: Bool) {
        state.lock()
        guard let signal = productionMidpointSignal,
              signal.ticket == ticket,
              let event = realPresentGate else {
            if clearTicketGate {
                tickets[ticket]?.realPresentGateValue = 0
            }
            state.unlock()
            return
        }
        event.signaledValue = max(event.signaledValue, signal.eventValue)
        productionMidpointSignal = nil
        if clearTicketGate {
            tickets[ticket]?.realPresentGateValue = 0
        }
        state.broadcast()
        state.unlock()
        schedulerWake.signal()
        productionPacingWake.signal()
    }

    private func presentProductionReal(_ ticket: UInt64) {
        state.lock()
        let gate: ProductionPresentationGate?
        if let pending = tickets[ticket],
           pending.realPresentGateValue > 0,
           let event = realPresentGate {
            gate = ProductionPresentationGate(
                event: event,
                eventValue: pending.realPresentGateValue
            )
        } else {
            gate = nil
        }
        state.unlock()
        let submitted = presentProductionWorld(
            ticket: ticket,
            generated: false,
            gate: gate
        ) { _ in }
        if !submitted {
            retryOrAbandonMandatoryReal(ticket)
        }
    }

    /** Acquires a fresh drawable only when a production world frame is ready. */
    @discardableResult
    private func presentProductionWorld(
        ticket: UInt64,
        generated: Bool,
        gate: ProductionPresentationGate? = nil,
        completion: @escaping (Bool) -> Void
    ) -> Bool {
        state.lock()
        guard !shuttingDown,
              let pending = tickets[ticket],
              let production = pending.production,
              let queue = presentationQueue,
              let renderReadyEvent = renderReadyEvent,
              let interpolationReadyEvent = interpolationReadyEvent,
              slots.indices.contains(pending.slot) else {
            state.unlock()
            completion(false)
            return false
        }
        let slot = pending.slot
        let renderReadyEventValue = pending.renderReadyEventValue
        let interpolationReadyEventValue = pending.interpolationReadyEventValue
        let realPresentationFence = pending.reuseFence
        let realPresentationFenceValue = pending.realPresentationFenceValue
        let realBelongsToSubmittedPair = !generated
            && pending.generatedPresentationSubmitted
        let world = generated ? slots[slot].generatedColor : slots[slot].realColor
        let ui = slots[slot].sdrUi
        state.unlock()

        guard layerMatchesGenerationSize(),
              let commandBuffer = queue.makeCommandBuffer() else {
            completion(false)
            return false
        }
        let drawableWaitStartedAt = MetallumMonotonicClock.nowNanoseconds()
        guard let drawable = layer.nextDrawable() else {
            completion(false)
            return false
        }
        MetallumFrameInterpolationTelemetry.shared.recordDrawableWait(
            generated: generated,
            nanoseconds: MetallumMonotonicClock.nowNanoseconds() &- drawableWaitStartedAt
        )
        if generated {
            // Generated color is valid only after the standalone MetalFX CB
            // signals its own event.  Real color/UI still wait directly on the
            // producer event.
            commandBuffer.encodeWaitForEvent(
                interpolationReadyEvent,
                value: interpolationReadyEventValue
            )
        } else {
            commandBuffer.encodeWaitForEvent(renderReadyEvent, value: renderReadyEventValue)
        }
        guard metallum_encodeUIComposite(
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
            completion(false)
            return false
        }
        let promotionScheduler = MetallumExtendedProMotionSchedulerRegistry.shared.scheduler(
            for: layer
        )
        // A screen/fullscreen/VSync transition invalidates an accepted pair.
        // Generated output is optional, so re-check after drawable acquisition
        // and composite encoding instead of presenting it with a stale cadence.
        if generated && !promotionScheduler.isCurrent(production.pacingPlan) {
            markProductionDiscontinuity()
            completion(false)
            return false
        }
        let effectivePlan: MetallumExtendedProMotionScheduler.Plan
        if generated || (realBelongsToSubmittedPair
                && promotionScheduler.isCurrent(production.pacingPlan)) {
            effectivePlan = production.pacingPlan
        } else {
            effectivePlan = promotionScheduler.realOnlyPlan(
                renderDeltaSeconds: Double(production.frame.deltaSeconds),
                displaySyncEnabled: layer.displaySyncEnabled
            )
        }
        if realBelongsToSubmittedPair, effectivePlan.useTimedPresentation, let gate {
            // Composite work may be prepared early, but the command buffer
            // cannot reach its present operation before the absolute midpoint.
            // Queueing this wait ahead of time removes CPU encode/drawable
            // latency from the final sub-millisecond scheduling margin.
            commandBuffer.encodeWaitForEvent(gate.event, value: gate.eventValue)
        }
        if !generated,
           let realPresentationFence,
           realPresentationFenceValue > 0 {
            // The fence follows both the UI composite and the midpoint wait.
            // It is deliberately before present so the next producer can be
            // queued without waiting for a CPU completion callback, while the
            // GPU still preserves the exact last read of this slot.
            commandBuffer.encodeSignalEvent(
                realPresentationFence,
                value: realPresentationFenceValue
            )
        }
        promotionScheduler.present(
            commandBuffer: commandBuffer,
            drawable: drawable,
            kind: generated ? .generated
                : (realBelongsToSubmittedPair ? .interpolatedReal : .realOnly),
            plan: effectivePlan,
            onPresented: { [weak self] feedback in
                self?.observeProductionOnGlass(feedback, generated: generated)
            }
        )
        commandBuffer.addCompletedHandler { [weak self] buffer in
            // This completion is the resource/commit frontier: after it, this
            // composite no longer reads a slot, so the next source may advance
            // history. CAMetalLayer alone owns drawable-pool availability;
            // on-glass callbacks are telemetry, never coordinator admission.
            let succeeded = buffer.status == .completed && buffer.error == nil
            if generated {
                self?.finishProductionPresentationCommandBuffer()
                completion(succeeded)
            } else {
                self?.completeProductionRealPresentationCommandBuffer(
                    ticket,
                    succeeded: succeeded
                )
            }
        }
        if generated {
            state.lock()
            guard productionOnGlassHealth.runtimeState != .unavailable,
                  let pending = tickets[ticket],
                  !pending.generatedPresentationSuppressed else {
                state.unlock()
                completion(false)
                return false
            }
            activePresentationCommandBuffers += 1
            pending.generatedPresentationSubmitted = true
            state.unlock()
            commandBuffer.commit()
        } else {
            state.lock()
            guard let pending = tickets[ticket],
                  !pending.realPresentationCommitInProgress,
                  !pending.realPresentationCompletionFinalized else {
                state.unlock()
                completion(false)
                return false
            }
            activePresentationCommandBuffers += 1
            pending.realPresentationCommitInProgress = true
            pending.deferredRealPresentationCompletionSucceeded = nil
            pending.realPresentationPublicationFailed = false
            state.unlock()

            // Never call into Metal while holding the midpoint scheduler's
            // state lock. A driver-side commit stall otherwise delays the
            // high-priority timer signal and turns a sub-millisecond gate into
            // a missed display interval. Immediate completion is serialized
            // by the per-ticket two-phase state below.
            commandBuffer.commit()

            var deferredOutcome: (succeeded: Bool, abandoned: Bool)?
            state.lock()
            if let current = tickets[ticket] {
                current.realPresentationCommitInProgress = false
                let published = markProductionRealSubmittedLocked(ticket)
                current.realPresentationPublicationFailed = !published
                if let deferredSucceeded = current.deferredRealPresentationCompletionSucceeded {
                    current.deferredRealPresentationCompletionSucceeded = nil
                    deferredOutcome = claimProductionRealPresentationCompletionLocked(
                        current,
                        ticket: ticket,
                        succeeded: deferredSucceeded && published
                    )
                }
            }
            state.unlock()
            // Replay scheduling after the post-commit fence publication. The
            // next pair is serialized by this worker and CAMetalLayer's pool.
            schedulerWake.signal()
            tryScheduleProductionPresentation()
            if let deferredOutcome {
                finalizeProductionRealPresentationCompletion(
                    ticket,
                    succeeded: deferredOutcome.succeeded,
                    abandoned: deferredOutcome.abandoned
                )
            }
        }
        return true
    }

    /**
     * Publishes R_N's slot fence and releases producer admission at commit.
     * The serial PresentThread may now begin N+1: CAMetalLayer's three-drawable
     * pool is the sole authority for its nextDrawable availability.
     */
    private func markProductionRealSubmittedLocked(_ ticket: UInt64) -> Bool {
        guard let pending = tickets[ticket] else {
            return false
        }
        pending.realPresentationSubmitted = true
        guard pending.production != nil,
              pending.realPresentationFenceValue != 0,
              pending.reuseFence != nil else {
            assertionFailure("FI real presentation committed without a slot reuse fence")
            return false
        }
        // The signal encoder has already been appended to this committed CB.
        // Publish only now: a pre-commit/encoder failure must leave the next
        // producer free of a wait value that can never be reached.
        pending.realPresentationFencePublished = true
        if slotIsOwnedByTicketLocked(pending.slot, ticket: ticket, epoch: pending.slotEpoch) {
            slots[pending.slot].reusableFenceValue = max(
                slots[pending.slot].reusableFenceValue,
                pending.realPresentationFenceValue
            )
        }
        detachProductionAdmissionAfterRealCommitLocked(pending, ticket: ticket)
        if activePresentationTicket == ticket {
            activePresentationTicket = nil
        }
        state.broadcast()
        return true
    }

    /**
     * Completion can race `commit()` returning on another driver thread.  If
     * publication is still in progress, retain only the result; the commit
     * side claims and finalizes it after the slot fence becomes visible.
     */
    private func completeProductionRealPresentationCommandBuffer(
        _ ticket: UInt64,
        succeeded: Bool
    ) {
        var outcome: (succeeded: Bool, abandoned: Bool)?
        state.lock()
        activePresentationCommandBuffers = max(activePresentationCommandBuffers - 1, 0)
        guard let pending = tickets[ticket] else {
            state.broadcast()
            state.unlock()
            return
        }
        if pending.realPresentationCommitInProgress {
            pending.deferredRealPresentationCompletionSucceeded = succeeded
            state.broadcast()
            state.unlock()
            return
        }
        let effectiveSuccess = succeeded
            && pending.realPresentationFencePublished
            && !pending.realPresentationPublicationFailed
        outcome = claimProductionRealPresentationCompletionLocked(
            pending,
            ticket: ticket,
            succeeded: effectiveSuccess
        )
        state.broadcast()
        state.unlock()
        if let outcome {
            finalizeProductionRealPresentationCompletion(
                ticket,
                succeeded: outcome.succeeded,
                abandoned: outcome.abandoned
            )
        }
    }

    /** Claims exactly one post-publication completion while coordinator state is locked. */
    private func claimProductionRealPresentationCompletionLocked(
        _ pending: Ticket,
        ticket: UInt64,
        succeeded: Bool
    ) -> (succeeded: Bool, abandoned: Bool)? {
        guard !pending.realPresentationCompletionFinalized else { return nil }
        pending.realPresentationCompletionFinalized = true
        pending.realPresentationCompleted = true
        if !succeeded {
            let successorMayBePipelined = tickets.contains { candidate in
                let (successorTicket, successor) = candidate
                return successorTicket > ticket
                    && successor.production != nil
                    && (successor.productionScheduled
                        || successor.generatedPresentationSubmitted
                        || successor.realPresentationSubmitted)
            }
            // Completion is the earliest safe CPU fallback frontier; if the
            // command buffer faulted before its GPU signal, release only this
            // ticket's recorded event value.
            signalFailedRealPresentationFenceLocked(pending, ticket: ticket)
            if successorMayBePipelined {
                // Once successor G may already be committed on the serial
                // present queue, retrying this older R would violate G/R FIFO.
                // Fail open and invalidate FI history instead.
                pending.abandoned = true
            }
        }
        return (succeeded, pending.abandoned)
    }

    private func finalizeProductionRealPresentationCompletion(
        _ ticket: UInt64,
        succeeded: Bool,
        abandoned: Bool
    ) {
        if abandoned {
            finishProductionTicket(ticket, realPresented: false)
        } else if succeeded {
            finishProductionTicket(ticket, realPresented: true)
        } else {
            retryOrAbandonMandatoryReal(ticket)
        }
    }

    private func finishProductionPresentationCommandBuffer() {
        state.lock()
        activePresentationCommandBuffers = max(activePresentationCommandBuffers - 1, 0)
        state.broadcast()
        state.unlock()
    }

    /** Fails open if a committed producer never reaches its GPU-ready signal. */
    private func expirePresentationWatchdogs() {
        let now = MetallumMonotonicClock.nowNanoseconds()
        var producerExpired: [UInt64] = []
        state.lock()
        for (ticket, pending) in tickets {
            if let deadline = pending.producerFailureWatchdogDeadline,
               deadline <= now,
               !pending.producerOutputSignaled {
                pending.producerFailureWatchdogDeadline = nil
                pending.producerFailed = true
                producerExpired.append(ticket)
            }
        }
        state.unlock()
        for ticket in producerExpired {
            abandonProductionTicket(ticket)
        }
    }

    private func nextPresentationWatchdogDeadline() -> UInt64? {
        state.lock()
        defer { state.unlock() }
        let deadlines = tickets.values.compactMap { pending in
            [
                pending.producerFailureWatchdogDeadline
            ]
                .compactMap { $0 }
                .min()
        }
        return deadlines.min()
    }

    /**
     * Deterministic producer-signal regression: an unsignaled producer must
     * release its ring slot without involving an on-glass callback.
     */
    func nativeProducerWatchdogStressForTests() -> Bool {
        state.lock()
        guard !shuttingDown,
              let queue = presentationQueue,
              let commandBuffer = queue.makeCommandBuffer(),
              let slot = slots.firstIndex(where: { $0.state == .free }),
              nextTicket != 0 else {
            state.unlock()
            return false
        }
        let ticket = nextTicket
        nextTicket &+= 1
        slots[slot].state = .realFrameReserved
        pendingRealFrames += 1
        let pending = Ticket(slot: slot, commandBuffer: commandBuffer)
        pending.producerFailureWatchdogDeadline = MetallumMonotonicClock.nowNanoseconds() - 1
        tickets[ticket] = pending
        state.unlock()

        expirePresentationWatchdogs()

        state.lock()
        defer { state.unlock() }
        return tickets[ticket] == nil
            && slots.indices.contains(slot)
            && slots[slot].state == .free
            && pendingRealFrames == 0
    }

    /**
     * Deterministic production-fence regression.  It proves that R_N can
     * detach admission at commit, N+1 immediately reserves the old physical
     * slot with the exact fence wait, and a stale R_N completion cannot free
     * or otherwise mutate N+1's newer epoch.
     */
    func nativeSlotReuseFenceStressForTests() -> Bool {
        state.lock()
        guard !shuttingDown,
              tickets.isEmpty,
              slots.count == Self.ringSize,
              let queue = presentationQueue,
              let oldCommandBuffer = queue.makeCommandBuffer(),
              let newCommandBuffer = queue.makeCommandBuffer(),
              nextTicket != 0 else {
            state.unlock()
            return false
        }
        let priorSlots = slots
        let priorTickets = tickets
        let priorPendingRealFrames = pendingRealFrames
        let priorNextTicket = nextTicket
        let priorHistory = productionHistorySlot
        defer {
            slots = priorSlots
            tickets = priorTickets
            pendingRealFrames = priorPendingRealFrames
            nextTicket = priorNextTicket
            productionHistorySlot = priorHistory
            state.unlock()
        }

        let oldTicketID = nextTicket
        nextTicket &+= 1
        let oldEpoch: UInt64 = max(slots[0].ownershipEpoch &+ 1, 1)
        let oldSignal: UInt64 = max(slots[0].nextFenceSignalValue, 1)
        slots[0].ownershipEpoch = oldEpoch
        slots[0].nextFenceSignalValue = oldSignal &+ 1
        slots[0].reusableFenceValue = max(oldSignal &- 1, 0)
        slots[0].state = .realFrameReserved
        slots[0].ownerTicket = oldTicketID
        pendingRealFrames += 1
        let old = Ticket(
            slot: 0,
            commandBuffer: oldCommandBuffer,
            slotEpoch: oldEpoch,
            reuseFence: slots[0].reuseFence,
            realPresentationFenceValue: oldSignal,
            isProductionTicket: true
        )
        old.realPresentationFencePublished = true
        tickets[oldTicketID] = old
        // This mirrors markProductionRealSubmitted after the signal encoder
        // was committed, without requiring a drawable in the native harness.
        slots[0].reusableFenceValue = oldSignal
        detachProductionAdmissionAfterRealCommitLocked(old, ticket: oldTicketID)
        guard old.admissionDetached,
              slots[0].state == .free,
              slots[0].ownerTicket == nil,
              productionAdmissionCountLocked() == 0 else {
            return false
        }

        let newTicketID = nextTicket
        nextTicket &+= 1
        guard let newReservation = reserveProductionSlotLocked(0, ticket: newTicketID),
              newReservation.waitValue == oldSignal,
              newReservation.epoch != oldEpoch else {
            return false
        }
        let newer = Ticket(
            slot: 0,
            commandBuffer: newCommandBuffer,
            slotEpoch: newReservation.epoch,
            reuseFence: newReservation.fence,
            reuseFenceWaitValue: newReservation.waitValue,
            realPresentationFenceValue: newReservation.signalValue,
            isProductionTicket: true
        )
        tickets[newTicketID] = newer
        guard productionAdmissionCountLocked() == 1,
              slots[0].state == .realFrameReserved,
              slots[0].ownerTicket == newTicketID else {
            return false
        }

        // An old CB completion retires only old accounting.  It cannot free
        // the same index now owned by the newer epoch.
        retireProductionTicketReservationLocked(old, ticket: oldTicketID)
        return pendingRealFrames == priorPendingRealFrames + 1
            && slots[0].state == .realFrameReserved
            && slots[0].ownerTicket == newTicketID
            && slots[0].ownershipEpoch == newReservation.epoch
    }

    /** A scheduled real-frame gate must be signaled and retired without GPU polling. */
    func nativeProductionMidpointGateStressForTests() -> Bool {
        let testTicket: UInt64 = 0
        state.lock()
        guard activePresentationTicket == nil, productionMidpointSignal == nil else {
            state.unlock()
            return false
        }
        activePresentationTicket = testTicket
        state.unlock()
        defer {
            state.lock()
            if activePresentationTicket == testTicket {
                activePresentationTicket = nil
            }
            state.unlock()
        }
        let deadline = MetallumMonotonicClock.nowNanoseconds() + 2_000_000
        guard let gate = scheduleProductionMidpoint(
            ticket: testTicket,
            deadlineNanoseconds: deadline
        ) else {
            return false
        }
        state.lock()
        let timeout = Date(timeIntervalSinceNow: 1.0)
        while productionMidpointSignal != nil && gate.event.signaledValue < gate.eventValue {
            if !state.wait(until: timeout) {
                state.unlock()
                return false
            }
        }
        let passed = productionMidpointSignal == nil
            && gate.event.signaledValue >= gate.eventValue
        state.unlock()
        return passed
    }

    /** Deterministic lease/FIFO regression for the synchronous publish path. */
    func nativeCommitFrontierStressForTests() -> Bool {
        state.lock()
        guard slots.count == Self.ringSize, fiReaderLeases.count == Self.ringSize,
              Self.maximumProductionTickets == 2,
              tickets.isEmpty, activeProductionFiJobs == 0 else {
            state.unlock()
            return false
        }
        let priorStates = slots.map(\.state)
        let priorLeases = fiReaderLeases
        let priorHistory = productionHistorySlot
        let priorActiveJobs = activeProductionFiJobs
        let priorInterpolatorReset = productionInterpolatorNeedsReset
        let priorEpoch = resetEpoch
        defer {
            for index in slots.indices { slots[index].state = priorStates[index] }
            fiReaderLeases = priorLeases
            productionHistorySlot = priorHistory
            activeProductionFiJobs = priorActiveJobs
            productionInterpolatorNeedsReset = priorInterpolatorReset
            resetEpoch = priorEpoch
            state.unlock()
        }

        // An abandoned ticket may have released its real slot while its FI CB
        // still owns an orphaned reader.  Drain/release must wait for it.
        activeProductionFiJobs = 1
        guard hasOutstandingPresentationWorkLocked() else { return false }
        activeProductionFiJobs = 0
        guard !hasOutstandingPresentationWorkLocked() else { return false }

        // A synchronous publish consumes exactly one reset bit; a stale
        // callback from an older epoch cannot consume a newer reset request.
        productionInterpolatorNeedsReset = true
        let firstPublishEpoch = resetEpoch
        guard productionInterpolatorNeedsReset else { return false }
        productionInterpolatorNeedsReset = false
        guard !productionInterpolatorNeedsReset else { return false }
        resetEpoch &+= 1
        productionInterpolatorNeedsReset = true
        guard firstPublishEpoch != resetEpoch, productionInterpolatorNeedsReset else { return false }

        // N-1 stays leased while FI(N) reads it; N remains leased while
        // FI(N+1) reads it.  Both become reusable only after their reader.
        slots[0].state = .history
        slots[1].state = .history
        slots[2].state = .realFrameReserved
        productionHistorySlot = 2
        fiReaderLeases = [1, 1, 0]
        releaseHistoryLeaseIfPossibleLocked(0)
        releaseHistoryLeaseIfPossibleLocked(1)
        guard slots[0].state == .history, slots[1].state == .history else { return false }
        fiReaderLeases[0] = 0
        releaseHistoryLeaseIfPossibleLocked(0)
        guard slots[0].state == .free, slots[1].state == .history else { return false }
        fiReaderLeases[1] = 0
        releaseHistoryLeaseIfPossibleLocked(1)
        return slots[1].state == .free && slots[2].state == .realFrameReserved
    }

    /** Immediate completion is deferred until post-commit fence publication, exactly once. */
    func nativeRealCommitCompletionRaceStressForTests() -> Bool {
        state.lock()
        guard tickets.isEmpty,
              activePresentationCommandBuffers == 0,
              let queue = presentationQueue,
              let commandBuffer = queue.makeCommandBuffer(),
              nextTicket != 0 else {
            state.unlock()
            return false
        }
        let ticket = nextTicket
        let pending = Ticket(slot: 0, commandBuffer: commandBuffer)
        pending.realPresentationCommitInProgress = true
        tickets[ticket] = pending
        activePresentationCommandBuffers = 1
        state.unlock()

        completeProductionRealPresentationCommandBuffer(ticket, succeeded: true)

        state.lock()
        guard activePresentationCommandBuffers == 0,
              pending.deferredRealPresentationCompletionSucceeded == true,
              !pending.realPresentationCompletionFinalized else {
            tickets.removeValue(forKey: ticket)
            state.unlock()
            return false
        }
        pending.realPresentationCommitInProgress = false
        pending.realPresentationFencePublished = true
        pending.deferredRealPresentationCompletionSucceeded = nil
        let claimed = claimProductionRealPresentationCompletionLocked(
            pending,
            ticket: ticket,
            succeeded: true
        )
        let duplicate = claimProductionRealPresentationCompletionLocked(
            pending,
            ticket: ticket,
            succeeded: true
        )
        tickets.removeValue(forKey: ticket)
        state.unlock()
        return claimed?.succeeded == true
            && claimed?.abandoned == false
            && duplicate == nil
            && pending.realPresentationCompleted
    }

    /** Event-before-FI-completion must transfer the job to orphaned leases. */
    func nativeOrphanFiCompletionStressForTests() -> Bool {
        state.lock()
        guard tickets.isEmpty, orphanedFiJobs.isEmpty, activeProductionFiJobs == 0,
              let queue = presentationQueue,
              let commandBuffer = queue.makeCommandBuffer(),
              nextTicket != 0 else {
            state.unlock()
            return false
        }
        let priorStates = slots.map(\.state)
        let priorLeases = fiReaderLeases
        let priorHistory = productionHistorySlot
        let priorPendingRealFrames = pendingRealFrames
        let priorNextTicket = nextTicket
        let ticket = nextTicket
        nextTicket &+= 1
        slots[0].state = .history
        slots[1].state = .realFrameReserved
        fiReaderLeases = [1, 1, 0]
        let pending = Ticket(slot: 1, commandBuffer: commandBuffer)
        pending.fiEnqueued = true
        pending.fiPredecessorSlot = 0
        tickets[ticket] = pending
        activeProductionFiJobs = 1
        pendingRealFrames = 1
        state.unlock()

        finishProductionTicket(ticket, realPresented: true)
        completeProductionFiJob(ticket)

        state.lock()
        let passed = tickets[ticket] == nil
            && orphanedFiJobs[ticket] == nil
            && activeProductionFiJobs == 0
            && fiReaderLeases == [0, 0, 0]
            && slots[0].state == .free
            && slots[1].state == .free
        for index in slots.indices { slots[index].state = priorStates[index] }
        fiReaderLeases = priorLeases
        productionHistorySlot = priorHistory
        pendingRealFrames = priorPendingRealFrames
        nextTicket = priorNextTicket
        state.unlock()
        return passed
    }

    /** Defers reset until no interpolator command can still read its history. */
    private func recordDroppedProductionGenerated() {
        // Generated output is optional.  Its matching mandatory real remains
        // coordinator-owned and becomes exact history, so a late drawable does
        // not itself break MetalFX adjacency or force a two-real re-prime.
        MetallumFrameInterpolationTelemetry.shared.recordLateGeneratedDrop()
    }

    /** Display/sync contract changes are real discontinuities, unlike a late optional drawable. */
    private func markProductionDiscontinuity() {
        state.lock()
        resetEpoch &+= 1
        productionNeedsReprime = true
        productionInterpolatorNeedsReset = true
        resetProductionCadenceLocked()
        for candidate in tickets.values where candidate.production != nil {
            candidate.generatedPresentationSuppressed = true
        }
        state.unlock()
    }

    static func productionMidpointDeadline(
        readyNanoseconds: UInt64,
        deltaSeconds: Double,
        submissionCompensationSeconds: Double = 0
    ) -> UInt64 {
        let bounded = min(
            max(deltaSeconds, 1.0 / 240.0),
            MetallumExtendedProMotionScheduler.nominalMaximumInterpolationSourceInterval
        )
        let compensation = submissionCompensationSeconds.isFinite
            ? min(max(submissionCompensationSeconds, 0), bounded * 0.25) : 0
        // Match Apple's PresentThread helper: 31/64 (~48%) of the measured
        // source interval leaves scheduling margin before midpoint. Adaptive
        // presentation adds only a sub-step phase correction for the
        // generated drawable's post-callback submission cost.
        return readyNanoseconds &+ UInt64(
            (bounded * (31.0 / 64.0) + compensation) * 1_000_000_000.0
        )
    }

    /** Called when Java falls back to a normal real-frame presentation. */
    func noteUnmanagedProductionFrame() {
        state.lock()
        guard !shuttingDown else {
            state.unlock()
            return
        }
        productionNeedsReprime = true
        productionInterpolatorNeedsReset = true
        resetProductionCadenceLocked()
        state.unlock()
    }

    /**
     * Called by the legacy presenter before it acquires a drawable.  It waits
     * only for the bounded FI pipeline and then marks the following normal
     * real presentation as a history break.  It never lets normal N+1 overtake
     * a generated/mandatory-real pair already owned by this coordinator.
     */
    func awaitUnmanagedPresentationDrain(timeoutNanoseconds: UInt64) -> Bool {
        state.lock()
        guard !shuttingDown else {
            state.unlock()
            return true
        }
        let timeout = Date(timeIntervalSinceNow: max(
            Double(timeoutNanoseconds) / 1_000_000_000.0, 0.0
        ))
        while !tickets.isEmpty
                || activeProductionFiJobs > 0
                || productionPacingWork != nil
                || activePresentationCommandBuffers > 0 {
            if !state.wait(until: timeout) {
                state.unlock()
                return false
            }
        }
        productionNeedsReprime = true
        productionInterpolatorNeedsReset = true
        resetProductionCadenceLocked()
        state.unlock()
        return true
    }

    /** Stops admission across a resize/display generation and drains existing work. */
    func invalidateForLayerMutation() {
        state.lock()
        guard !shuttingDown else {
            state.unlock()
            return
        }
        acceptingFrames = false
        productionNeedsReprime = true
        productionInterpolatorNeedsReset = true
        resetEpoch &+= 1
        // Slot leases keep active FI reads safe after this history reset.
        resetInterpolationHistoryLocked()
        resetProductionCadenceLocked()
        state.broadcast()
        state.unlock()
        schedulerWake.signal()
    }

    private func layerMatchesGenerationSize() -> Bool {
        let size = layer.drawableSize
        // Fresh offscreen/native validation layers are intentionally created
        // before a drawable size is installed.  A non-zero size is a real
        // presentation contract and must match the immutable FI generation.
        guard size.width.isFinite, size.height.isFinite else { return false }
        if size.width < 0.5 || size.height < 0.5 { return true }
        return abs(size.width - CGFloat(key.width)) < 0.5
            && abs(size.height - CGFloat(key.height)) < 0.5
    }

    /**
     * The coordinator has already accepted this source, so a real composite
     * that fails before completing needs a bounded coordinator-owned retry.
     * GPU completion owns retry safety. Drawable acquisition remains bounded
     * by CAMetalLayer's existing nextDrawable timeout, rather than a separate
     * callback-driven pool credit.
     */
    private func retryOrAbandonMandatoryReal(_ ticket: UInt64) {
        state.lock()
        guard let pending = tickets[ticket], !shuttingDown,
              !pending.abandoned,
              layerMatchesGenerationSize() else {
            state.unlock()
            abandonProductionTicket(ticket)
            return
        }
        guard pending.mandatoryRealRetries < Self.maximumMandatoryRealRetries,
              productionPacingWork == nil else {
            state.unlock()
            abandonProductionTicket(ticket)
            return
        }
        if pending.realPresentationFencePublished {
            // The previous real CB has retired (this method is called from its
            // completion). It may retry only before a newer reservation takes
            // this epoch; otherwise the mandatory-real contract fails open.
            guard reacquireProductionSlotForMandatoryRealRetryLocked(pending, ticket: ticket) else {
                state.unlock()
                abandonProductionTicket(ticket)
                return
            }
            activePresentationTicket = ticket
        } else {
            guard activePresentationTicket == ticket else {
                state.unlock()
                abandonProductionTicket(ticket)
                return
            }
        }
        pending.mandatoryRealRetries += 1
        productionPacingWork = ProductionPacingWork(
            ticket: ticket,
            includeGenerated: false
        )
        state.unlock()
        schedulerWake.signal()
    }

    /** A mandatory real that never reached the display cannot advance history. */
    private func abandonProductionTicket(_ ticket: UInt64) {
        // A committed mandatory-real retry may still be waiting on this gate.
        // Signaling is monotonic and the active-CB counter keeps its resources
        // alive until the command buffer actually retires.
        flushProductionMidpoint(ticket: ticket, clearTicketGate: true)
        state.lock()
        guard let pending = tickets[ticket] else {
            let wasActive = activePresentationTicket == ticket
            if activePresentationTicket == ticket {
                activePresentationTicket = nil
            }
            state.broadcast()
            state.unlock()
            if wasActive {
                tryScheduleProductionPresentation()
            }
            return
        }
        // Once real commit begins, keep this ticket/resource identity until
        // that CB retires. Publication may still be between commit() and the
        // coordinator lock; retiring the slot in that window would expose it
        // to reuse before the committed R read/fence becomes visible.
        if pending.realPresentationCommitInProgress
                || (pending.realPresentationFencePublished && !pending.realPresentationCompleted) {
            pending.abandoned = true
            pending.generatedPresentationEncoded = false
            pending.generatedPresentationSuppressed = true
            if activePresentationTicket == ticket {
                activePresentationTicket = nil
            }
            state.broadcast()
            state.unlock()
            return
        }
        _ = tickets.removeValue(forKey: ticket)
        if pending.fiEnqueued && !pending.fiCompleted,
           let predecessor = pending.fiPredecessorSlot {
            orphanedFiJobs[ticket] = (predecessor, pending.slot)
        }
        if pending.isProductionTicket {
            retireProductionTicketReservationLocked(pending, ticket: ticket)
        } else {
            releaseSlotLocked(pending.slot)
        }
        resetEpoch &+= 1
        productionNeedsReprime = true
        resetInterpolationHistoryLocked()
        resetProductionCadenceLocked()
        for candidate in tickets.values where candidate.production != nil {
            candidate.generatedPresentationEncoded = false
            candidate.generatedPresentationSuppressed = true
        }
        if activePresentationTicket == ticket {
            activePresentationTicket = nil
        }
        state.broadcast()
        state.unlock()
        tryScheduleProductionPresentation()
    }

    private func finishProductionTicket(_ ticket: UInt64, realPresented: Bool) {
        guard realPresented else {
            abandonProductionTicket(ticket)
            return
        }
        state.lock()
        guard let pending = tickets.removeValue(forKey: ticket) else {
            state.broadcast()
            state.unlock()
            return
        }
        // The GPU event can make G/R presentation complete before the FI CB's
        // CPU completion handler.  Preserve its leases until that handler.
        if pending.fiEnqueued && !pending.fiCompleted,
           let predecessor = pending.fiPredecessorSlot {
            orphanedFiJobs[ticket] = (predecessor, pending.slot)
        }
        if pending.isProductionTicket {
            retireProductionTicketReservationLocked(pending, ticket: ticket)
        } else {
            releaseSlotLocked(pending.slot)
        }
        if activePresentationTicket == ticket {
            activePresentationTicket = nil
        }
        state.broadcast()
        state.unlock()
        tryScheduleProductionPresentation()
    }

    private func releaseSlotLocked(_ index: Int) {
        guard slots.indices.contains(index), slots[index].state == .realFrameReserved else {
            assertionFailure("FI ticket attempted to release an invalid slot")
            return
        }
        if fiReaderLeases.indices.contains(index), fiReaderLeases[index] > 0
            || productionHistorySlot == index {
            slots[index].state = .history
        } else {
            slots[index].state = .free
        }
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
            let now = MetallumMonotonicClock.nowNanoseconds()
            if now >= deadlineNanoseconds {
                return true
            }
            _ = precisionDeadlineTimer.wait(untilNanoseconds: deadlineNanoseconds)
            state.lock()
            let shouldStop = shuttingDown || resetEpoch != epoch || pacingWork == nil
            state.unlock()
            if shouldStop {
                return false
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

    /**
     * Timer-only companion to the drawable-owning PresentThread.
     *
     * It performs no Metal encoding and allocates no per-frame timer.  Its one
     * bounded work slot is safe because the following production turn cannot
     * reserve a gate until the current one has been signaled and cleared.
     */
    private func runProductionPacingScheduler() {
        Thread.current.threadPriority = 1.0
        productionPacingStarted.signal()
        while true {
            productionPacingWake.wait()
            while true {
                state.lock()
                if shuttingDown {
                    let pending = productionMidpointSignal
                    productionMidpointSignal = nil
                    let event = realPresentGate
                    if let pending, let event {
                        event.signaledValue = max(event.signaledValue, pending.eventValue)
                    }
                    state.broadcast()
                    state.unlock()
                    // Never strand a committed mandatory-real CB behind an
                    // unsignaled event during teardown.
                    productionPacingStopped.signal()
                    return
                }
                guard let signal = productionMidpointSignal,
                      let event = realPresentGate else {
                    state.unlock()
                    break
                }
                state.unlock()

                _ = productionDeadlineTimer.wait(
                    untilNanoseconds: signal.deadlineNanoseconds
                )
                var releasedGateLatenessNanoseconds: UInt64?
                state.lock()
                if let current = productionMidpointSignal,
                   current.ticket == signal.ticket,
                   current.eventValue == signal.eventValue {
                    event.signaledValue = max(event.signaledValue, signal.eventValue)
                    // Measure the actual CPU signal frontier, including any
                    // contention while reacquiring coordinator state after the
                    // timer fired. Sampling before `state.lock()` hid exactly
                    // the commit/publication contention this diagnostic is
                    // intended to expose.
                    let releasedAtNanoseconds = MetallumMonotonicClock.nowNanoseconds()
                    productionMidpointSignal = nil
                    releasedGateLatenessNanoseconds = releasedAtNanoseconds
                        > signal.deadlineNanoseconds
                        ? releasedAtNanoseconds - signal.deadlineNanoseconds : 0
                }
                state.broadcast()
                state.unlock()
                if let releasedGateLatenessNanoseconds {
                    MetallumFrameInterpolationTelemetry.shared.recordProductionGateRelease(
                        latenessNanoseconds: releasedGateLatenessNanoseconds
                    )
                }
                // Real submission may have released the presentation turn
                // before this gate cleared. Reconsider an already-ready N+1
                // directly; merely waking the worker would not create work.
                tryScheduleProductionPresentation()
            }
        }
    }

    private func runPresentationScheduler() {
        Thread.current.threadPriority = 1.0
        schedulerStarted.signal()
        while true {
            if let deadline = nextPresentationWatchdogDeadline() {
                let now = MetallumMonotonicClock.nowNanoseconds()
                if deadline > now {
                    let waitNanoseconds = min(deadline - now, UInt64(Int.max))
                    _ = schedulerWake.wait(timeout: .now() + .nanoseconds(Int(waitNanoseconds)))
                }
            } else {
                schedulerWake.wait()
            }
            expirePresentationWatchdogs()
            while true {
                state.lock()
                let shouldStop = shuttingDown
                let productionWork = productionPacingWork
                if productionWork != nil {
                    productionPacingWork = nil
                }
                let work = productionWork == nil ? pacingWork : nil
                state.unlock()
                if shouldStop {
                    schedulerStopped.signal()
                    return
                }
                if let productionWork {
                    autoreleasepool {
                        runProductionPresentation(productionWork)
                    }
                    continue
                }
                guard let work else { break }

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
                    || MetallumMonotonicClock.nowNanoseconds()
                        > work.generatedDeadlineNanoseconds + Self.generatedLatenessToleranceNanoseconds
                if generatedLate {
                    droppedGeneratedLate += 1
                    recordPacingLocked(
                        .droppedGeneratedLate,
                        presentationID: work.generatedPresentationID,
                        timestampNanoseconds: MetallumMonotonicClock.nowNanoseconds()
                    )
                } else {
                    recordPacingLocked(
                        .generated,
                        presentationID: work.generatedPresentationID,
                        timestampNanoseconds: MetallumMonotonicClock.nowNanoseconds()
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
                // A real frame is never dropped here.  It is the fail-open
                // member of every accepted pair, even after a late generated.
                recordPacingLocked(
                    .real,
                    presentationID: work.realPresentationID,
                    timestampNanoseconds: MetallumMonotonicClock.nowNanoseconds()
                )
                finishPacingWorkLocked()
                state.unlock()
            }
        }
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

/**
 * Per-coordinator runtime status: low byte state, next byte reason.
 * This read-only ABI is allocation-free and safe to poll from the render
 * thread; a null/stale Java owner resolves to Disabled without dereferencing.
 */
@_cdecl("metallum_frame_interpolation_runtime_status_v1")
public func metallum_frame_interpolation_runtime_status_v1(
    _ rawContext: UnsafeMutableRawPointer?
) -> UInt64 {
    coordinatorFromRawPointer(rawContext)?.runtimeStatusPacked()
        ?? MetallumFrameInterpolationCoordinator.RuntimeState.disabled.rawValue
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
    let status = coordinator.prepareProduction(
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
    )
    // Any non-prepared result is presented by Java's normal real-frame path.
    // Tell the coordinator that this visible frame is not part of its retained
    // history, so a later accepted pair cannot interpolate across the gap.
    if status != .prepared {
        coordinator.noteUnmanagedProductionFrame()
    }
    return status.rawValue
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
        guard MetallumFrameInterpolationCoordinator.legacyFallbackDrainTimeoutNanoseconds
                == 100_000_000 else {
            return -11
        }
        // Production pacing is absolute from the completed real input.  A
        // generated composite that itself costs time must not shift the real
        // member by another half interval.
        let productionReady: UInt64 = 10_000_000
        guard MetallumFrameInterpolationCoordinator.productionMidpointDeadline(
            readyNanoseconds: productionReady,
            deltaSeconds: 1.0 / 60.0
        ) == 18_072_916 else {
            return -10
        }
        guard MetallumFrameInterpolationCoordinator.productionMidpointDeadline(
            readyNanoseconds: productionReady,
            deltaSeconds: 1.0 / 40.0,
            submissionCompensationSeconds: 1.0 / 960.0
        ) == 23_151_041 else {
            return -14
        }
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
        guard coordinator.nativeProducerWatchdogStressForTests() else { return -12 }
        guard coordinator.nativeProductionMidpointGateStressForTests() else { return -13 }

        let now = MetallumMonotonicClock.nowNanoseconds()
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
        let lateNow = MetallumMonotonicClock.nowNanoseconds()
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
              MetallumFrameInterpolationCoordinator.acceptedSourceClockStressForTests(),
              MetallumFrameInterpolationCoordinator.productionTimingGapRecoveryStressForTests(),
              MetallumFrameInterpolationCoordinator.productionOnGlassHealthStressForTests(),
              MetallumFrameInterpolationCoordinator.presentationTurnPipeliningStressForTests(),
              coordinator.nativeProducerWatchdogStressForTests(),
              coordinator.nativeCommitFrontierStressForTests(),
              coordinator.nativeRealCommitCompletionRaceStressForTests(),
              coordinator.nativeOrphanFiCompletionStressForTests(),
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
        guard coordinator.nativeSlotReuseFenceStressForTests() else {
            return -9
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
        guard metallumSpatialOutputIsCurrent(
            workspaceCommandBufferAddress: 72, commandBufferAddress: 72
        ), !metallumSpatialOutputIsCurrent(
            workspaceCommandBufferAddress: 72, commandBufferAddress: 73
        ), !metallumSpatialOutputIsCurrent(
            workspaceCommandBufferAddress: nil, commandBufferAddress: 72
        ) else { return -3 }
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
