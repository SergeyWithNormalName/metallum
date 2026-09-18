package com.metallum.client.gi.live;

import com.metallum.client.gi.source.GiDynamicSourceSnapshot;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.renderer.RendererConfig;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** Process-wide restart-gated admission and camera-independent dynamic publication for G6. */
public final class GiLiveRuntime {
    public enum AdmissionState { DISABLED, WAITING, READY, INVALID }

    /** Low-frequency immutable receipt created only when the benchmark closes its segment. */
    public record FinalSnapshot(
            long deviceGeneration,
            int readyMask,
            boolean buildInFlight,
            long staleRejects,
            long rejectedCount,
            long accountedBytes,
            long fieldGeneration,
            long sourceTick,
            long blockSamples,
            int blockP95Submits,
            int blockP99Submits,
            boolean blockSla,
            long staticSourceSamples,
            int staticSourceP95Submits,
            int staticSourceP99Submits,
            boolean staticSourceSla,
            long scrollSamples,
            int scrollP95Submits,
            int scrollP99Submits,
            boolean scrollSla,
            long fullResetSamples,
            int fullResetP95Submits,
            int fullResetP99Submits,
            boolean fullResetSla,
            long schedulerQueued,
            long schedulerCompleted,
            long schedulerDiscarded,
            int schedulerPending,
            int schedulerInFlight,
            boolean schedulerAlgebraExact,
            long measurementStartAccountedBytes,
            long terrainBindings,
            long terrainZeroBindings,
            long terrainFieldBindings,
            long measurementStartTerrainZeroBindings,
            long measurementStartTerrainFieldBindings,
            boolean admissionReceiptEmitted,
            long admissionDeviceGeneration,
            long admissionSubmitIndex,
            boolean latestTerrainBindingObserved,
            long latestTerrainDeviceGeneration,
            long latestTerrainSubmitIndex,
            int latestTerrainBindStatus,
            boolean latestTerrainCarrierSafe,
            boolean latestTerrainFrameCompatible,
            int latestTerrainReadyMask,
            boolean latestTerrainExactMaskNonzero,
            int latestTerrainVisibleMask,
            long latestTerrainFieldGeneration,
            long latestTerrainSourceTick
    ) {
    }

    private static final RendererConfig STARTUP_CONFIG = RendererConfig.loadForStartupGate();
    private static final boolean REQUESTED = STARTUP_CONFIG.improvedLighting()
            && STARTUP_CONFIG.globalIllumination().isDynamic();
    private static final boolean BENCHMARK_ACTIVE = "1".equals(
            System.getenv("METALLUM_BENCHMARK")
    );

    private static volatile AdmissionState admissionState = REQUESTED
            ? AdmissionState.WAITING : AdmissionState.DISABLED;
    private static volatile String invalidReason = "none";
    private static long deviceGenerationCounter;
    private static volatile long deviceGeneration;
    private static volatile GiDynamicSourceSnapshot dynamicSources;
    private static volatile long dynamicSourceTick = -1L;
    private static volatile boolean benchmarkWarmupStarted;
    private static volatile boolean benchmarkMeasurementStarted;
    private static volatile long finalDeviceGeneration = -1L;
    private static volatile int finalReadyMask;
    private static volatile boolean finalBuildInFlight;
    private static volatile long finalStaleRejects;
    private static volatile long finalRejectedCount;
    private static volatile long finalAccountedBytes;
    private static volatile long finalFieldGeneration;
    private static volatile long finalSourceTick = -1L;
    private static volatile long finalBlockSamples;
    private static volatile int finalBlockP95Submits = -1;
    private static volatile int finalBlockP99Submits = -1;
    private static volatile boolean finalBlockSla;
    private static volatile long finalStaticSourceSamples;
    private static volatile int finalStaticSourceP95Submits = -1;
    private static volatile int finalStaticSourceP99Submits = -1;
    private static volatile boolean finalStaticSourceSla;
    private static volatile long finalScrollSamples;
    private static volatile int finalScrollP95Submits = -1;
    private static volatile int finalScrollP99Submits = -1;
    private static volatile boolean finalScrollSla;
    private static volatile long finalFullResetSamples;
    private static volatile int finalFullResetP95Submits = -1;
    private static volatile int finalFullResetP99Submits = -1;
    private static volatile boolean finalFullResetSla;
    private static volatile long finalSchedulerQueued;
    private static volatile long finalSchedulerCompleted;
    private static volatile long finalSchedulerDiscarded;
    private static volatile int finalSchedulerPending;
    private static volatile int finalSchedulerInFlight;
    private static volatile boolean finalSchedulerAlgebraExact;
    private static volatile long measurementStartAccountedBytes = -1L;
    private static volatile long terrainBindings;
    private static volatile long terrainZeroBindings;
    private static volatile long terrainFieldBindings;
    private static volatile long measurementStartTerrainZeroBindings = -1L;
    private static volatile long measurementStartTerrainFieldBindings = -1L;
    private static volatile boolean admissionReceiptEmitted;
    private static volatile long admissionDeviceGeneration = -1L;
    private static volatile long admissionSubmitIndex = -1L;
    private static volatile boolean latestTerrainBindingObserved;
    private static volatile long latestTerrainDeviceGeneration = -1L;
    private static volatile long latestTerrainSubmitIndex = -1L;
    private static volatile int latestTerrainBindStatus = GiLiveLayout.STATUS_INVALID;
    private static volatile boolean latestTerrainCarrierSafe;
    private static volatile boolean latestTerrainFrameCompatible;
    private static volatile int latestTerrainReadyMask;
    private static volatile boolean latestTerrainExactMaskNonzero;
    private static volatile int latestTerrainVisibleMask;
    private static volatile long latestTerrainFieldGeneration;
    private static volatile long latestTerrainSourceTick = -1L;

    private GiLiveRuntime() {
    }

    public static boolean isRequested() {
        return REQUESTED;
    }

    /** True only for the explicit benchmark process; production code cannot request probes. */
    public static boolean isBenchmarkActive() {
        return BENCHMARK_ACTIVE;
    }

    /** Runtime work may continue while admission is waiting/ready, but never after fail-close. */
    public static boolean isOperational() {
        return isOperationalState(REQUESTED, admissionState);
    }

    static boolean isOperationalState(
            final boolean requested,
            final AdmissionState state
    ) {
        AdmissionState checked = Objects.requireNonNull(state, "state");
        return requested && (checked == AdmissionState.WAITING
                || checked == AdmissionState.READY);
    }

    public static AdmissionState admissionState() {
        return admissionState;
    }

    public static String invalidReason() {
        return invalidReason;
    }

    public static synchronized long resetDeviceState() {
        deviceGenerationCounter = Math.incrementExact(deviceGenerationCounter);
        deviceGeneration = deviceGenerationCounter;
        admissionState = REQUESTED ? AdmissionState.WAITING : AdmissionState.DISABLED;
        invalidReason = "none";
        dynamicSources = null;
        dynamicSourceTick = -1L;
        benchmarkWarmupStarted = false;
        benchmarkMeasurementStarted = false;
        clearBenchmarkReceipts();
        return deviceGeneration;
    }

    private static void clearBenchmarkReceipts() {
        finalDeviceGeneration = -1L;
        finalReadyMask = 0;
        finalBuildInFlight = false;
        finalStaleRejects = 0L;
        finalRejectedCount = 0L;
        finalAccountedBytes = 0L;
        finalFieldGeneration = 0L;
        finalSourceTick = -1L;
        finalBlockSamples = 0L;
        finalBlockP95Submits = -1;
        finalBlockP99Submits = -1;
        finalBlockSla = false;
        finalStaticSourceSamples = 0L;
        finalStaticSourceP95Submits = -1;
        finalStaticSourceP99Submits = -1;
        finalStaticSourceSla = false;
        finalScrollSamples = 0L;
        finalScrollP95Submits = -1;
        finalScrollP99Submits = -1;
        finalScrollSla = false;
        finalFullResetSamples = 0L;
        finalFullResetP95Submits = -1;
        finalFullResetP99Submits = -1;
        finalFullResetSla = false;
        finalSchedulerQueued = 0L;
        finalSchedulerCompleted = 0L;
        finalSchedulerDiscarded = 0L;
        finalSchedulerPending = 0;
        finalSchedulerInFlight = 0;
        finalSchedulerAlgebraExact = false;
        measurementStartAccountedBytes = -1L;
        terrainBindings = 0L;
        terrainZeroBindings = 0L;
        terrainFieldBindings = 0L;
        measurementStartTerrainZeroBindings = -1L;
        measurementStartTerrainFieldBindings = -1L;
        admissionReceiptEmitted = false;
        admissionDeviceGeneration = -1L;
        admissionSubmitIndex = -1L;
        latestTerrainBindingObserved = false;
        latestTerrainDeviceGeneration = -1L;
        latestTerrainSubmitIndex = -1L;
        latestTerrainBindStatus = GiLiveLayout.STATUS_INVALID;
        latestTerrainCarrierSafe = false;
        latestTerrainFrameCompatible = false;
        latestTerrainReadyMask = 0;
        latestTerrainExactMaskNonzero = false;
        latestTerrainVisibleMask = 0;
        latestTerrainFieldGeneration = 0L;
        latestTerrainSourceTick = -1L;
    }

    /** Allocation-free render-thread publication consumed once by the benchmark controller. */
    public static void publishFinalTelemetry(
            final long sourceDeviceGeneration,
            final int readyMask,
            final boolean buildInFlight,
            final long staleRejects,
            final long rejectedCount,
            final long accountedBytes,
            final long fieldGeneration,
            final long sourceTick,
            final long blockSamples,
            final int blockP95Submits,
            final int blockP99Submits,
            final boolean blockSla,
            final long staticSourceSamples,
            final int staticSourceP95Submits,
            final int staticSourceP99Submits,
            final boolean staticSourceSla,
            final long scrollSamples,
            final int scrollP95Submits,
            final int scrollP99Submits,
            final boolean scrollSla,
            final long fullResetSamples,
            final int fullResetP95Submits,
            final int fullResetP99Submits,
            final boolean fullResetSla,
            final long schedulerQueued,
            final long schedulerCompleted,
            final long schedulerDiscarded,
            final int schedulerPending,
            final int schedulerInFlight,
            final boolean schedulerAlgebraExact
    ) {
        if (!REQUESTED) return;
        if (sourceDeviceGeneration != deviceGeneration) {
            reportInvalid("G6 final telemetry belongs to a stale device generation");
            return;
        }
        finalDeviceGeneration = sourceDeviceGeneration;
        finalReadyMask = readyMask;
        finalBuildInFlight = buildInFlight;
        finalStaleRejects = staleRejects;
        finalRejectedCount = rejectedCount;
        finalAccountedBytes = accountedBytes;
        finalFieldGeneration = fieldGeneration;
        finalSourceTick = sourceTick;
        finalBlockSamples = blockSamples;
        finalBlockP95Submits = blockP95Submits;
        finalBlockP99Submits = blockP99Submits;
        finalBlockSla = blockSla;
        finalStaticSourceSamples = staticSourceSamples;
        finalStaticSourceP95Submits = staticSourceP95Submits;
        finalStaticSourceP99Submits = staticSourceP99Submits;
        finalStaticSourceSla = staticSourceSla;
        finalScrollSamples = scrollSamples;
        finalScrollP95Submits = scrollP95Submits;
        finalScrollP99Submits = scrollP99Submits;
        finalScrollSla = scrollSla;
        finalFullResetSamples = fullResetSamples;
        finalFullResetP95Submits = fullResetP95Submits;
        finalFullResetP99Submits = fullResetP99Submits;
        finalFullResetSla = fullResetSla;
        finalSchedulerQueued = schedulerQueued;
        finalSchedulerCompleted = schedulerCompleted;
        finalSchedulerDiscarded = schedulerDiscarded;
        finalSchedulerPending = schedulerPending;
        finalSchedulerInFlight = schedulerInFlight;
        finalSchedulerAlgebraExact = schedulerAlgebraExact;
    }

    public static FinalSnapshot finalSnapshot() {
        return new FinalSnapshot(
                finalDeviceGeneration, finalReadyMask, finalBuildInFlight, finalStaleRejects,
                finalRejectedCount, finalAccountedBytes, finalFieldGeneration,
                finalSourceTick, finalBlockSamples, finalBlockP95Submits,
                finalBlockP99Submits, finalBlockSla,
                finalStaticSourceSamples, finalStaticSourceP95Submits,
                finalStaticSourceP99Submits, finalStaticSourceSla,
                finalScrollSamples, finalScrollP95Submits,
                finalScrollP99Submits, finalScrollSla,
                finalFullResetSamples, finalFullResetP95Submits,
                finalFullResetP99Submits, finalFullResetSla,
                finalSchedulerQueued, finalSchedulerCompleted, finalSchedulerDiscarded,
                finalSchedulerPending, finalSchedulerInFlight, finalSchedulerAlgebraExact,
                measurementStartAccountedBytes,
                terrainBindings, terrainZeroBindings, terrainFieldBindings,
                measurementStartTerrainZeroBindings, measurementStartTerrainFieldBindings,
                admissionReceiptEmitted, admissionDeviceGeneration, admissionSubmitIndex,
                latestTerrainBindingObserved, latestTerrainDeviceGeneration,
                latestTerrainSubmitIndex, latestTerrainBindStatus,
                latestTerrainCarrierSafe, latestTerrainFrameCompatible,
                latestTerrainReadyMask, latestTerrainExactMaskNonzero,
                latestTerrainVisibleMask,
                latestTerrainFieldGeneration, latestTerrainSourceTick
        );
    }

    public static long deviceGeneration() {
        return deviceGeneration;
    }

    public static void reportReady(final long sourceDeviceGeneration) {
        if (REQUESTED && sourceDeviceGeneration == deviceGeneration
                && admissionState == AdmissionState.WAITING) {
            admissionState = AdmissionState.READY;
        }
    }

    public static void reportAdmissionReceiptEmitted(
            final long sourceDeviceGeneration,
            final long submitIndex
    ) {
        if (!REQUESTED) return;
        if (sourceDeviceGeneration != deviceGeneration || submitIndex < 0L) {
            reportInvalid("G6 admission receipt belongs to a stale device generation");
            return;
        }
        admissionReceiptEmitted = true;
        admissionDeviceGeneration = sourceDeviceGeneration;
        admissionSubmitIndex = submitIndex;
    }

    /** Replaces the prior terrain receipt even when the newest relevant bind is zero fallback. */
    public static void publishTerrainBinding(
            final long sourceDeviceGeneration,
            final long submitIndex,
            final int bindStatus,
            final boolean carrierSafe,
            final boolean frameCompatible,
            final int readyMask,
            final int visibleMask,
            final long fieldGeneration,
            final long sourceTick
    ) {
        if (!REQUESTED) return;
        if (sourceDeviceGeneration != deviceGeneration || submitIndex < 0L
                || (readyMask & ~GiLiveLayout.READY_MASK_ALL) != 0
                || (visibleMask & ~GiLiveLayout.READY_MASK_ALL) != 0
                || (bindStatus == GiLiveLayout.STATUS_OK) != (visibleMask != 0)
                || fieldGeneration < 0L || sourceTick < 0L) {
            reportInvalid("G6 terrain binding receipt is invalid or belongs to a stale device");
            return;
        }
        latestTerrainBindingObserved = true;
        terrainBindings = Math.incrementExact(terrainBindings);
        if (bindStatus == GiLiveLayout.STATUS_OK) {
            terrainFieldBindings = Math.incrementExact(terrainFieldBindings);
        } else {
            terrainZeroBindings = Math.incrementExact(terrainZeroBindings);
        }
        latestTerrainDeviceGeneration = sourceDeviceGeneration;
        latestTerrainSubmitIndex = submitIndex;
        latestTerrainBindStatus = bindStatus;
        latestTerrainCarrierSafe = carrierSafe;
        latestTerrainFrameCompatible = frameCompatible;
        latestTerrainReadyMask = readyMask;
        latestTerrainExactMaskNonzero = bindStatus == GiLiveLayout.STATUS_OK;
        latestTerrainVisibleMask = visibleMask;
        latestTerrainFieldGeneration = fieldGeneration;
        latestTerrainSourceTick = sourceTick;
    }

    /** Exact terminal proof; historical counters cannot qualify a newer zero/fallback bind. */
    public static boolean finalReceiptIsCurrent(
            final FinalSnapshot snapshot,
            final long currentDeviceGeneration
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return currentDeviceGeneration > 0L
                && snapshot.deviceGeneration() == currentDeviceGeneration
                && snapshot.admissionReceiptEmitted()
                && snapshot.admissionDeviceGeneration() == currentDeviceGeneration
                && snapshot.admissionSubmitIndex() >= 0L
                && snapshot.latestTerrainBindingObserved()
                && snapshot.latestTerrainDeviceGeneration() == currentDeviceGeneration
                && snapshot.latestTerrainSubmitIndex() >= snapshot.admissionSubmitIndex()
                && snapshot.latestTerrainBindStatus() == GiLiveLayout.STATUS_OK
                && snapshot.latestTerrainCarrierSafe()
                && snapshot.latestTerrainFrameCompatible()
                && snapshot.latestTerrainReadyMask() == snapshot.readyMask()
                && snapshot.latestTerrainVisibleMask() == GiLiveLayout.READY_MASK_ALL
                && snapshot.latestTerrainFieldGeneration() == snapshot.fieldGeneration()
                && snapshot.latestTerrainSourceTick() == snapshot.sourceTick();
    }

    /**
     * Bind-time continuity proof for a compatible successor update.  The global ready/generation
     * tuple may already describe the field being rebuilt; this predicate deliberately consumes
     * only the receiver mask actually written into the terrain params packet.
     */
    public static boolean latestTerrainAllCascadeBindingIsUsable(
            final FinalSnapshot snapshot,
            final long currentDeviceGeneration
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return currentDeviceGeneration > 0L
                && snapshot.deviceGeneration() == currentDeviceGeneration
                && snapshot.admissionReceiptEmitted()
                && snapshot.admissionDeviceGeneration() == currentDeviceGeneration
                && snapshot.admissionSubmitIndex() >= 0L
                && snapshot.latestTerrainBindingObserved()
                && snapshot.latestTerrainDeviceGeneration() == currentDeviceGeneration
                && snapshot.latestTerrainSubmitIndex() >= snapshot.admissionSubmitIndex()
                && snapshot.latestTerrainBindStatus() == GiLiveLayout.STATUS_OK
                && snapshot.latestTerrainCarrierSafe()
                && snapshot.latestTerrainFrameCompatible()
                && snapshot.latestTerrainVisibleMask() == GiLiveLayout.READY_MASK_ALL
                && snapshot.staleRejects() == 0L
                && snapshot.rejectedCount() == 0L;
    }

    /** Opens the G6 admission-receipt window independently of diagnostic G4. */
    public static void beginBenchmarkWarmup() {
        if (REQUESTED) {
            if (!benchmarkWarmupStarted) {
                clearBenchmarkReceipts();
            }
            benchmarkWarmupStarted = true;
        }
    }

    public static void beginBenchmarkMeasurement() {
        if (REQUESTED) {
            benchmarkMeasurementStarted = true;
            measurementStartAccountedBytes = finalAccountedBytes;
            measurementStartTerrainZeroBindings = terrainZeroBindings;
            measurementStartTerrainFieldBindings = terrainFieldBindings;
        }
    }

    public static boolean isBenchmarkWarmup() {
        return BENCHMARK_ACTIVE && benchmarkWarmupStarted
                && !benchmarkMeasurementStarted;
    }

    public static synchronized void reportInvalid(final String reason) {
        if (!REQUESTED) return;
        if (admissionState != AdmissionState.INVALID) {
            invalidReason = reason == null || reason.isBlank()
                    ? "unspecified G6 failure" : reason;
            admissionState = AdmissionState.INVALID;
        }
        // Retire the publication in the same monitor transition as INVALID. Otherwise the G3
        // live path can consume an older immutable snapshot after the terminal G6 failure.
        dynamicSources = null;
        dynamicSourceTick = -1L;
    }

    public static void publishDynamicSources(final GiDynamicSourceSnapshot snapshot) {
        publishDynamicSources(snapshot, snapshot.publishedAtWorldTick());
    }

    /** Publishes immutable membership and the independently advancing world/source tick. */
    public static synchronized void publishDynamicSources(
            final GiDynamicSourceSnapshot snapshot,
            final long sourceTick
    ) {
        if (!isOperational()) return;
        if (sourceTick < snapshot.publishedAtWorldTick()) {
            throw new IllegalArgumentException("G6 source tick predates its publication");
        }
        dynamicSources = Objects.requireNonNull(snapshot, "snapshot");
        dynamicSourceTick = sourceTick;
    }

    public static synchronized void closeDynamicWorld(final @Nullable LightWorldToken world) {
        GiDynamicSourceSnapshot current = dynamicSources;
        if (shouldClearDynamicWorld(current, world)) {
            dynamicSources = null;
            dynamicSourceTick = -1L;
        }
    }

    public static @Nullable GiDynamicSourceSnapshot dynamicSources(
            final LightWorldToken expectedWorld
    ) {
        Objects.requireNonNull(expectedWorld, "expectedWorld");
        GiDynamicSourceSnapshot current = dynamicSources;
        return dynamicWorldMatches(current, expectedWorld) ? current : null;
    }

    public static synchronized long dynamicSourceTick(
            final GiDynamicSourceSnapshot expectedSnapshot
    ) {
        return matchingDynamicSourceTick(
                dynamicSources,
                Objects.requireNonNull(expectedSnapshot, "expectedSnapshot"),
                dynamicSourceTick
        );
    }

    /** G2 and L3 own independent generation domains; only the exact L3 token identifies sources. */
    static boolean dynamicWorldMatches(
            final @Nullable GiDynamicSourceSnapshot snapshot,
            final LightWorldToken expectedWorld
    ) {
        Objects.requireNonNull(expectedWorld, "expectedWorld");
        return snapshot != null && snapshot.epoch().world().equals(expectedWorld);
    }

    /** Reference identity turns the synchronized read into an exact snapshot/tick pair. */
    static long matchingDynamicSourceTick(
            final @Nullable GiDynamicSourceSnapshot current,
            final GiDynamicSourceSnapshot expected,
            final long tick
    ) {
        Objects.requireNonNull(expected, "expected");
        return current == expected ? tick : -1L;
    }

    static boolean shouldClearDynamicWorld(
            final @Nullable GiDynamicSourceSnapshot current,
            final @Nullable LightWorldToken closingWorld
    ) {
        return current == null || closingWorld == null
                || current.epoch().world().equals(closingWorld);
    }
}
