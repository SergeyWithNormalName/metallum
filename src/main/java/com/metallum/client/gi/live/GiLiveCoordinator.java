package com.metallum.client.gi.live;

import com.metallum.Metallum;
import com.metallum.client.gi.semantic.GiSemanticTransportFieldView;
import com.metallum.client.gi.source.GiDirectSourceCoordinator;
import com.metallum.client.gi.source.GiDirectSourceEpoch;
import com.metallum.client.gi.source.GiDirectSourceLayout;
import com.metallum.client.gi.source.GiDynamicSourceSnapshot;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.function.Consumer;

/** Bounded, source-tick-gated G6 incremental transport and exact-valid publication owner. */
public final class GiLiveCoordinator implements AutoCloseable {
    public static final int STATUS_NO_WORK = 0;
    /** A compatible successor is deferred; the last proven receiver history remains usable. */
    public static final int STATUS_RETAINED_HISTORY = 2;
    public static final int STATUS_INPUT_NOT_READY = -7;
    public static final int STATUS_ASYNC_COMPLETION_FAILED = -11;
    static final int FULL_RESET_STABLE_PUBLICATION_SUBMITS = 10;
    public static final long G2_G3_ACCOUNTED_BYTES = 19_196_840L;
    public static final long TOTAL_BUDGET_BYTES = 25_165_824L;

    private enum EpochTransition {
        PROVISIONAL(false, true),
        RETAINED_PROVISIONAL(true, false),
        RETAINED_PROVISIONAL_NEW_LATENCY(true, true),
        FULL_RESET_ROOT_CHILD(false, false),
        AUTHORITATIVE_HANDOFF(true, false),
        AUTHORITATIVE_REBASE(false, false);

        private final boolean retainCapturedBasis;
        private final boolean startsLatency;

        EpochTransition(
                final boolean retainCapturedBasis,
                final boolean startsLatency
        ) {
            this.retainCapturedBasis = retainCapturedBasis;
            this.startsLatency = startsLatency;
        }
    }

    private final Thread ownerThread = Thread.currentThread();
    private final long deviceGeneration;
    private final GiDirectSourceCoordinator direct;
    private final GiLiveGpuResources resources;
    private final GiLivePublication publication = new GiLivePublication();
    private final GiLiveLatencyTracker latency = new GiLiveLatencyTracker();
    private final GiLiveDirtyScheduler scheduler = new GiLiveDirtyScheduler();
    private final GiLiveCompletionTracker completion = new GiLiveCompletionTracker();
    private final int[] drainedBricks = new int[GiLiveLayout.MAX_BRICKS_PER_SUBMIT];
    private final GiLiveUpdateClass[] drainedClasses =
            new GiLiveUpdateClass[GiLiveLayout.MAX_BRICKS_PER_SUBMIT];
    private final long[] requiredMasks = new long[GiLiveLayout.CASCADE_COUNT];
    private final long[] conservativeMasks = new long[GiLiveLayout.CASCADE_COUNT];
    private final long[] coalescedMasks = new long[GiLiveLayout.CASCADE_COUNT];
    private final long[] observedAffectedMasks = new long[GiLiveLayout.CASCADE_COUNT];
    private final long[] deferredAffectedMasks = new long[GiLiveLayout.CASCADE_COUNT];
    private final long[] exactMasks = new long[GiLiveLayout.CASCADE_COUNT];
    private final long[] handoffRetainedMasks = new long[GiLiveLayout.CASCADE_COUNT];
    private final long[] authoritativeBaseExactMasks = new long[GiLiveLayout.CASCADE_COUNT];
    private final long[] observedBrickStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
    private final int[] observedOrigins = new int[GiLiveLayout.CASCADE_COUNT * 3];
    /** World grid currently owned by each physical receiver-atlas cascade. */
    private final int[] receiverOrigins = new int[GiLiveLayout.CASCADE_COUNT * 3];
    private final int[] scrollDeltaXBlocks = new int[GiLiveLayout.CASCADE_COUNT];
    private final int[] scrollDeltaYBlocks = new int[GiLiveLayout.CASCADE_COUNT];
    private final int[] scrollDeltaZBlocks = new int[GiLiveLayout.CASCADE_COUNT];
    private final boolean[] scrollCascade = new boolean[GiLiveLayout.CASCADE_COUNT];
    private final boolean[] cascadePrepared = new boolean[GiLiveLayout.CASCADE_COUNT];
    private final boolean[] authoritativeMask = new boolean[GiLiveLayout.CASCADE_COUNT];
    private final GiDirectSourceCoordinator.LiveSource[] authoritativeSources =
            new GiDirectSourceCoordinator.LiveSource[GiLiveLayout.CASCADE_COUNT];

    @Nullable private GiLiveEpoch activeEpoch;
    private GiLiveUpdateClass activeUpdateClass = GiLiveUpdateClass.FULL_RESET;
    private GiLiveLayout.ResetKind activeResetKind = GiLiveLayout.ResetKind.EXPLICIT;
    private boolean activeEpochAuthoritative;
    private boolean preserveExact;
    private int nextCascadeToEnqueue;
    private long nextEpochVersion;
    private long logicalSourceTick;
    private long lastInputSourceTick = Long.MIN_VALUE;
    private long firstAffectedSubmitIndex = -1L;
    @Nullable private GiLiveUpdateClass deferredUpdateClass;
    private long deferredFirstAffectedSubmitIndex = -1L;
    private boolean deferredSourceMaskUnknown;
    private boolean capturedBasisAvailable;
    private boolean workAdmissionOccurred;
    private boolean fullResetRootOpen;
    private int fullResetStablePublicationSubmits;
    private long lastFullResetStableSubmit = -1L;
    private boolean admissionLogged;
    private boolean receiverOriginsKnown;

    private boolean observed;
    @Nullable private String observedDimension;
    private long observedWorld;
    private long observedResource;
    private long observedMaterial;
    private long observedClipmap;
    private long observedPalette;
    private long observedContent;
    private long observedStatic;
    private long observedDynamic;
    private long observedDynamicHash;
    private long observedEnvironmentDigest;
    private boolean observedStaticHealthy;

    public GiLiveCoordinator(
            final MemorySegment device,
            final MemorySegment commandQueue,
            final GiDirectSourceCoordinator direct,
            final long deviceGeneration,
            final Consumer<MemorySegment> deferredRelease
    ) {
        if (deviceGeneration <= 0L) {
            throw new IllegalArgumentException("G6 device generation must be positive");
        }
        this.deviceGeneration = deviceGeneration;
        this.direct = Objects.requireNonNull(direct, "direct");
        this.resources = GiLiveGpuResources.create(
                Objects.requireNonNull(device, "device"),
                Objects.requireNonNull(commandQueue, "commandQueue"),
                direct.liveOwner(), Objects.requireNonNull(deferredRelease, "deferredRelease")
        );
        if (this.resources == null) {
            throw new IllegalStateException("Native G6 live context creation returned null");
        }
        long accounted = accountedBytes();
        if (accounted > TOTAL_BUDGET_BYTES) {
            this.resources.close();
            throw new IllegalStateException(
                    "G6 memory census exceeds the 24 MiB cap: " + accounted
                            + " > " + TOTAL_BUDGET_BYTES
            );
        }
    }

    /** Encodes no more than eight bricks and grants one allowance per renderer/source tick. */
    public int observeAndEncode(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final int inFlightSlot,
            final GiSemanticTransportFieldView field,
            final GiDynamicSourceSnapshot dynamic,
            final EnvironmentDescriptor environment,
            final AdvancedLightRegistry registry,
            final long inputSourceTick,
            final long submitIndex
    ) {
        assertOwnerThread();
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(dynamic, "dynamic");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(registry, "registry");
        if (MetalNativeBridge.isNullHandle(commandBuffer)
                || inputSourceTick < 0L || submitIndex < 0L) {
            throw new IllegalArgumentException("Invalid G6 submission input");
        }
        requireDynamicWorld(field, dynamic);
        advanceSourceTick(inputSourceTick);
        long staticEpoch = this.direct.observedLiveStaticSourceEpoch();
        boolean staticHealthy = this.direct.liveStaticSourceObservationIsCurrent(
                registry, field.world().dimensionId()
        );
        // G3 deliberately coalesces renderer environment samples while an accepted direct
        // generation is still converging. G6 must key invalidation to that admitted identity,
        // not to a newer raw descriptor which the attached direct field cannot yet serve.
        long environmentDigest = this.direct.observedLiveEnvironmentDigest();
        GiLiveGpuResources.Stats stats = this.resources.stats();
        int retirementStatus = retirePendingBatch(stats);
        if (retirementStatus != GiLiveLayout.STATUS_OK) {
            publishFinalTelemetry(this.resources.stats());
            return retirementStatus;
        }
        if (!sourceEnvironmentIdentityReady(environmentDigest)) {
            // A temporarily unavailable G3 identity is itself an incompatible observation once
            // G6 has published an atlas. Retire any accepted write first, then keep the earliest
            // affected submit and fail the eventual successor closed to a full reset.
            if (this.observed) {
                recordDeferredObservation(GiLiveUpdateClass.FULL_RESET, submitIndex);
                this.deferredSourceMaskUnknown = true;
            }
            publishFinalTelemetry(stats);
            return STATUS_INPUT_NOT_READY;
        }
        reconcileExactReadyMasks(stats);
        boolean changed = observationChanged(
                field, dynamic, staticEpoch, staticHealthy, environmentDigest
        );
        if (changed) {
            GiLiveUpdateClass pendingClass = classifyObservation(
                    field, dynamic, staticEpoch, staticHealthy, environmentDigest
            );
            if (shouldHoldCompletedFullResetPublication(
                    this.fullResetRootOpen, this.activeEpochAuthoritative,
                    stats.allCascadesReady(), sameObservedStructuralRoot(field),
                    sameObservedOrigins(field), pendingClass
            )) {
                recordDeferredObservation(pendingClass, submitIndex);
                recordDeferredSourceMasks(
                        field, dynamic, staticEpoch, staticHealthy, environmentDigest
                );
                // Keep one bounded, fully-exact publication interval observable by terrain and
                // other consumers. Compatible child dirt is accumulated and applied immediately
                // after the interval; scroll and structural reset never enter this path.
                observeCompletion(stats, submitIndex);
                publishFinalTelemetry(stats);
                return STATUS_RETAINED_HISTORY;
            }
        }
        if (changed) {
            // The accepted batch owns shared atlas writes until its asynchronous receipt. An
            // epoch rotation here could capture pre-completion masks while the old encoder later
            // publishes new-origin texels. Consume the receipt first, then coalesce latest input.
            if (stats.buildInFlight()) {
                recordDeferredObservation(
                        classifyObservation(
                                field, dynamic, staticEpoch, staticHealthy, environmentDigest
                        ),
                        submitIndex
                );
                recordDeferredSourceMasks(
                        field, dynamic, staticEpoch, staticHealthy, environmentDigest
                );
                publishFinalTelemetry(stats);
                // The current atlas predates this compatible incremental/scroll successor, but
                // still contains a spatially valid, last-proven field. Keep it visible until the
                // accepted writer retires; beginObservedUpdate will then publish per-brick
                // history masks for the successor. Structural resets remain globally fail-closed.
                return canRetainDeferredHistory(
                        this.deferredUpdateClass, field, environmentDigest
                ) ? STATUS_RETAINED_HISTORY : STATUS_INPUT_NOT_READY;
            }
            beginObservedUpdate(
                    field, dynamic, staticEpoch, staticHealthy, environmentDigest, submitIndex
            );
            stats = this.resources.stats();
        } else {
            clearDeferredObservation();
        }
        // Readiness belongs to the final identity observed by this submit. An old epoch becoming
        // ready in the same submit that invalidates it is not a visible recovery receipt.
        observeCompletion(stats, submitIndex);
        publishFinalTelemetry(stats);
        if (stats.buildInFlight()) return GiLiveLayout.STATUS_BUSY;

        if (shouldEncodeProvisionalNearScrollRemap(
                this.activeEpochAuthoritative,
                this.activeUpdateClass,
                this.scrollCascade[0],
                this.cascadePrepared[0]
        )) {
            GiLiveEpoch epoch = Objects.requireNonNull(this.activeEpoch, "activeEpoch");
            long dispatchBaseline = stats.transportDispatches();
            long rejectBaseline = stats.rejectedCount();
            int status = this.resources.encodeScrollRemap(
                    commandBuffer, fence, inFlightSlot, epoch, field, 0,
                    this.requiredMasks[0]
            );
            if (status != GiLiveLayout.STATUS_OK) return status;
            this.workAdmissionOccurred = true;
            this.capturedBasisAvailable = false;
            this.cascadePrepared[0] = true;
            advanceReceiverOrigin(epoch, 0);
            this.completion.admit(
                    0, 0, true, 0L, epoch.version(),
                    dispatchBaseline, rejectBaseline
            );
            publishFinalTelemetry(this.resources.stats());
            return status;
        }

        long dynamicEpoch = dynamic.epoch().sourceEpoch();
        long dynamicHash = dynamic.sourceHash();
        GiDirectSourceCoordinator.LiveSource available = staticHealthy
                ? firstAvailableSource(
                        commandBuffer, fence, dynamicEpoch, dynamicHash, inputSourceTick
                ) : null;
        if (available == null) {
            return provisionalReceiverHistoryCanBind(
                    this.activeEpochAuthoritative, this.preserveExact,
                    this.activeUpdateClass, this.cascadePrepared[0]
            ) ? STATUS_NO_WORK : STATUS_INPUT_NOT_READY;
        }
        if (!sourceMatchesObservation(field, available, dynamic)) {
            return provisionalReceiverHistoryCanBind(
                    this.activeEpochAuthoritative, this.preserveExact,
                    this.activeUpdateClass, this.cascadePrepared[0]
            ) ? STATUS_NO_WORK : STATUS_INPUT_NOT_READY;
        }
        if (!this.activeEpochAuthoritative
                || !matchesAuthoritative(this.activeEpoch, field, available, dynamic)) {
            installAuthoritativeEpoch(field, available, dynamic, submitIndex);
            stats = this.resources.stats();
            publishFinalTelemetry(stats);
        }

        boolean authoritativeSourceUnavailable = false;
        boolean authoritativePlanAvailable = hasAnyAuthoritativePlan();
        do {
            if (!prepareNextCascadePlan(
                    commandBuffer, fence, dynamicEpoch, dynamicHash, inputSourceTick
            )) {
                authoritativeSourceUnavailable = true;
                break;
            }
            authoritativePlanAvailable = true;
            enqueueNextCascadeIfNeeded();
        } while (shouldContinueAuthoritativePlanning(
                this.scheduler.pendingCount(), this.nextCascadeToEnqueue
        ));
        // Authoritative plan refinement is synchronous and may restore a fully exact near
        // cascade from the captured basis without dispatching a brick. Sample that final
        // end-of-submit identity; otherwise continuous children can provisional-zero near every
        // frame and hide its restored SLA receipt forever.
        stats = this.resources.stats();
        reconcileExactReadyMasks(stats);
        observeCompletion(stats, submitIndex);
        publishFinalTelemetry(stats);
        if (authoritativeSourceUnavailable) {
            // With no authoritative cascade, the provisional tuple cannot authorize this frame.
            // Once any current cascade is authoritative, unplanned outer cascades remain exact
            // zero/per-brick fail-closed and the restored near mask is safe to bind visibly.
            return unavailableAuthoritativeSourceStatus(authoritativePlanAvailable);
        }
        if (this.scheduler.pendingCount() == 0) return STATUS_NO_WORK;

        GiLiveEpoch epoch = Objects.requireNonNull(this.activeEpoch, "activeEpoch");
        int count = this.scheduler.drainTo(
                epoch, this.logicalSourceTick, this.drainedBricks, this.drainedClasses
        );
        if (count == 0) return STATUS_NO_WORK;
        int cascade = GiDirectSourceLayout.cascadeForBrickId(this.drainedBricks[0]);
        long batchMask = 0L;
        for (int index = 0; index < count; index++) {
            if (GiDirectSourceLayout.cascadeForBrickId(this.drainedBricks[index]) != cascade) {
                this.scheduler.retryBatch(epoch, this.drainedBricks, count);
                throw new IllegalStateException("G6 near-to-far scheduler crossed cascades");
            }
            int local = this.drainedBricks[index]
                    - cascade * GiLiveLayout.BRICKS_PER_CASCADE;
            batchMask |= 1L << local;
        }
        GiDirectSourceCoordinator.LiveSource source = this.direct.liveTransportSourceForSubmit(
                cascade, dynamicEpoch, dynamicHash,
                commandBuffer, fence, inputSourceTick
        );
        if (source == null) {
            this.scheduler.retryBatch(epoch, this.drainedBricks, count);
            return STATUS_INPUT_NOT_READY;
        }
        boolean prepare = !this.cascadePrepared[cascade];
        long dispatchBaseline = stats.transportDispatches();
        long rejectBaseline = stats.rejectedCount();
        int status = this.resources.encodeBricks(
                commandBuffer, fence, inFlightSlot, epoch, source, field,
                batchMask, count, prepare ? this.requiredMasks[cascade] : 0L,
                prepare, this.preserveExact, prepare && this.scrollCascade[cascade]
        );
        if (status != GiLiveLayout.STATUS_OK) {
            this.scheduler.retryBatch(epoch, this.drainedBricks, count);
            if (prepare) {
                // Native may have rolled a failed prepared admission back to exact zero.
                // Conservative Java truth must never retain more coverage than native.
                this.exactMasks[cascade] = 0L;
            }
            return status;
        }
        this.workAdmissionOccurred = true;
        this.capturedBasisAvailable = false;
        this.cascadePrepared[cascade] = true;
        if (prepare) advanceReceiverOrigin(epoch, cascade);
        this.completion.admit(
                count, cascade, prepare, batchMask, epoch.version(),
                dispatchBaseline, rejectBaseline
        );
        publishFinalTelemetry(this.resources.stats());
        return status;
    }

    public int bindVertex(
            final MemorySegment renderEncoder, final int inFlightSlot, final boolean carrierSafe
    ) {
        assertOwnerThread();
        return this.resources.bindVertex(renderEncoder, inFlightSlot, carrierSafe);
    }

    /** Ephemeral allocation-free telemetry view; consume primitive getters immediately. */
    public GiLiveGpuResources.Stats stats() { assertOwnerThread(); return this.resources.stats(); }
    public GiLivePublication.Snapshot publication() {
        assertOwnerThread(); return this.publication.snapshot();
    }
    public GiLiveLatencyTracker.Snapshot latency(final GiLiveUpdateClass updateClass) {
        assertOwnerThread(); return this.latency.snapshot(updateClass);
    }
    public long accountedBytes() {
        assertOwnerThread();
        long nativeBytes = this.resources.stats().allocatedBytes();
        return Math.addExact(G2_G3_ACCOUNTED_BYTES,
                Math.addExact(nativeBytes, GiLiveLayout.JAVA_PACKET_BYTES));
    }
    public boolean admissionLogged() { assertOwnerThread(); return this.admissionLogged; }
    public void markAdmissionLogged() { assertOwnerThread(); this.admissionLogged = true; }

    /** One-shot benchmark-boundary diagnostics; never called from the steady render loop. */
    public String debugSummary() {
        assertOwnerThread();
        GiLiveGpuResources.Stats live = this.resources.stats();
        GiLiveDirtyScheduler.Telemetry schedulerStats = this.scheduler.telemetry();
        com.metallum.client.gi.source.GiDirectDirtyQueue.Telemetry directQueue =
                this.direct.queueTelemetry();
        com.metallum.client.gi.source.GiDirectSourceGpuResources.Stats directNative =
                this.direct.nativeStats();
        return "g6_native={ready=" + live.readyMask()
                + ",field=" + live.fieldGeneration()
                + ",source=" + live.sourceTick()
                + ",dispatches=" + live.transportDispatches()
                + ",cascades=" + live.cascadeBuilds0() + "/"
                + live.cascadeBuilds1() + "/" + live.cascadeBuilds2()
                + ",invalidations=" + live.invalidations() + "}"
                + " g6_queue={pending=" + schedulerStats.pending()
                + ",in_flight=" + schedulerStats.inFlight()
                + ",queued=" + schedulerStats.queued()
                + ",completed=" + schedulerStats.completed()
                + ",discarded=" + schedulerStats.discarded() + "}"
                + " class=" + this.activeUpdateClass
                + " latency_pending={class=" + this.latency.pendingClassification()
                + ",first=" + this.latency.pendingFirstAffectedSubmitIndex() + "}"
                + " authoritative=" + this.activeEpochAuthoritative
                + " basis_available=" + this.capturedBasisAvailable
                + " work_admitted=" + this.workAdmissionOccurred
                + " full_reset_root_open=" + this.fullResetRootOpen
                + " deferred={class=" + this.deferredUpdateClass
                + ",first=" + this.deferredFirstAffectedSubmitIndex
                + ",source_unknown=" + this.deferredSourceMaskUnknown + "}"
                + " next_cascade=" + this.nextCascadeToEnqueue
                + " exact=" + java.util.Arrays.toString(this.exactMasks)
                + " required=" + java.util.Arrays.toString(this.requiredMasks)
                + " base=" + java.util.Arrays.toString(this.authoritativeBaseExactMasks)
                + " prepared=" + java.util.Arrays.toString(this.cascadePrepared)
                + " g3_queue={pending=" + directQueue.pending()
                + ",in_flight=" + directQueue.inFlight()
                + ",queued=" + directQueue.queued()
                + ",completed=" + directQueue.completed()
                + ",discarded=" + directQueue.discarded()
                + ",full=" + directQueue.fullVolumeRebuilds() + "}"
                + (directNative == null ? " g3_native=null"
                : " g3_native={ready=" + directNative.ready()
                + ",in_flight=" + directNative.buildInFlight()
                + ",content=" + directNative.contentGeneration()
                + ",direct_dispatches=" + directNative.directInjectDispatches()
                + ",stale=" + directNative.staleRejects()
                + ",rejected=" + directNative.rejectedCount() + "}")
                + " g3_source=" + this.direct.liveSourceDebugSummary(
                        this.observedDynamic, this.observedDynamicHash
                );
    }

    @Override
    public void close() {
        assertOwnerThread();
        this.resources.close();
        this.activeEpoch = null;
    }

    private void beginObservedUpdate(
            final GiSemanticTransportFieldView field,
            final GiDynamicSourceSnapshot dynamic,
            final long staticEpoch,
            final boolean staticHealthy,
            final long environmentDigest,
            final long submitIndex
    ) {
        GiLiveUpdateClass observedUpdateClass = classifyObservation(
                field, dynamic, staticEpoch, staticHealthy, environmentDigest
        );
        // An observation deferred behind an accepted shared-atlas write owns the earliest
        // physical transition. Merge it before deciding whether the visible successor is only a
        // child of an already-open reset root. Otherwise a deferred structural reset followed by
        // a same-root source child can be mistaken for that child and leave an older SCROLL SLA
        // pending until unrelated receiver coverage happens to return.
        observedUpdateClass = GiLiveUpdateClass.merge(
                this.deferredUpdateClass, observedUpdateClass
        );
        boolean compatibleEnvironmentSuccessor = compatibleEnvironmentSuccessor(
                field, environmentDigest
        );
        boolean retainedFullResetRoot = shouldRetainOpenFullResetChild(
                this.fullResetRootOpen,
                sameObservedStructuralRoot(field), sameObservedOrigins(field),
                this.exactMasks[0] == GiLiveLayout.ALL_BRICKS_MASK,
                observedUpdateClass
        );
        if (observedUpdateClass == GiLiveUpdateClass.FULL_RESET
                && !compatibleEnvironmentSuccessor) {
            this.fullResetRootOpen = true;
            this.fullResetStablePublicationSubmits = 0;
            this.lastFullResetStableSubmit = -1L;
        }
        long affectedSubmitIndex = this.deferredFirstAffectedSubmitIndex >= 0L
                ? this.deferredFirstAffectedSubmitIndex : submitIndex;
        boolean sourceIdentityChanged = sourceIdentityChanged(
                dynamic, staticEpoch, staticHealthy, environmentDigest
        );
        if (shouldRetainPendingBasis(
                this.activeUpdateClass,
                this.capturedBasisAvailable,
                observedUpdateClass,
                this.workAdmissionOccurred
        )) {
            beginRetainedProvisionalOverlap(
                    field, dynamic, staticEpoch, staticHealthy, environmentDigest,
                    observedUpdateClass, sourceIdentityChanged,
                    submitIndex, affectedSubmitIndex
            );
            clearDeferredObservation();
            return;
        }
        this.activeUpdateClass = observedUpdateClass;
        this.preserveExact = this.activeUpdateClass != GiLiveUpdateClass.FULL_RESET
                || compatibleEnvironmentSuccessor;
        if (!this.receiverOriginsKnown || !this.preserveExact) {
            resetReceiverOrigins(field);
        }
        computeProvisionalMasks(field);
        boolean exactAffectedMasks = copyCombinedAffectedMasks(
                field, dynamic, staticEpoch, staticHealthy, environmentDigest
        );
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            long conservative = this.requiredMasks[cascade];
            long retained = this.preserveExact ? this.exactMasks[cascade] : 0L;
            if (this.preserveExact && this.scrollCascade[cascade]) {
                retained = scrollRetainedExactMask(
                        retained, cascade,
                        this.scrollDeltaXBlocks[cascade],
                        this.scrollDeltaYBlocks[cascade],
                        this.scrollDeltaZBlocks[cascade]
                );
            }
            this.handoffRetainedMasks[cascade] = retained;
            long stickyKnown = stickyKnownPhysicalDirt(
                    this.activeUpdateClass,
                    conservative,
                    this.observedAffectedMasks[cascade], exactAffectedMasks
            );
            long affected = provisionalAffectedMask(
                    this.activeUpdateClass,
                    conservative,
                    this.observedAffectedMasks[cascade], exactAffectedMasks,
                    sourceIdentityChanged
            );
            // Keep every transition relative to this newly captured G6 basis. G3 may advance
            // independently before G6 admits its first batch, so latest-only source dirt is not
            // sufficient for a later provisional handoff.
            // An unavailable G3 mask makes provisional visibility zero, but it is not itself
            // physical dirt. Keep only CPU-proven semantic/exact-source dirt sticky so the later
            // authoritative handoff can recover captured unaffected bricks.
            this.conservativeMasks[cascade] = stickyKnown;
            this.requiredMasks[cascade] = affected;
            // Native provisional scroll coverage remains zero until the remap encoder completes.
            this.exactMasks[cascade] = this.preserveExact && !this.scrollCascade[cascade]
                    ? retained & ~this.requiredMasks[cascade] : 0L;
        }
        this.activeResetKind = resetKindFor(
                this.activeUpdateClass, field, staticEpoch, staticHealthy
        );
        this.activeEpochAuthoritative = false;
        GiLiveEpoch provisional = epochFromObservation(
                field, positiveOrOne(staticEpoch), dynamic.epoch().sourceEpoch(),
                positiveDigest(environmentDigest)
        );
        beginEpoch(
                provisional, submitIndex, affectedSubmitIndex,
                retainedFullResetRoot ? EpochTransition.FULL_RESET_ROOT_CHILD
                        : EpochTransition.PROVISIONAL
        );
        resetProvisionalPlanState();
        this.capturedBasisAvailable = this.preserveExact;
        this.workAdmissionOccurred = false;
        rememberObservation(field, dynamic, staticEpoch, staticHealthy, environmentDigest);
        clearDeferredObservation();
    }

    /**
     * Coalesces same-origin block/source dirt before this provisional chain admits GPU work. The
     * original per-cascade snapshot remains the only exact basis; after any accepted batch the
     * caller waits for its receipt and starts a new chain from the completed physical atlas.
     */
    private void beginRetainedProvisionalOverlap(
            final GiSemanticTransportFieldView field,
            final GiDynamicSourceSnapshot dynamic,
            final long staticEpoch,
            final boolean staticHealthy,
            final long environmentDigest,
            final GiLiveUpdateClass observedUpdateClass,
            final boolean sourceIdentityChanged,
            final long publicationSubmitIndex,
            final long affectedSubmitIndex
    ) {
        if (this.firstAffectedSubmitIndex < 0L) {
            throw new IllegalStateException("G6 retained provisional basis is unavailable");
        }
        computeCoalescedMasks(field, observedUpdateClass, this.coalescedMasks);
        boolean exactAffectedMasks = copyCombinedAffectedMasks(
                field, dynamic, staticEpoch, staticHealthy, environmentDigest
        );
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            this.conservativeMasks[cascade] |= this.coalescedMasks[cascade];
            long stickyKnown = stickyKnownPhysicalDirt(
                    observedUpdateClass,
                    this.conservativeMasks[cascade],
                    this.observedAffectedMasks[cascade], exactAffectedMasks
            );
            long affected = retainedProvisionalAffectedMask(
                    observedUpdateClass,
                    this.conservativeMasks[cascade],
                    this.observedAffectedMasks[cascade],
                    exactAffectedMasks,
                    sourceIdentityChanged
            );
            // Sticky union is relative to G6's captured atlas, not merely G3's latest
            // direct-field epoch. A G3 brick may already have advanced A->B before B->C, while
            // the unprepared G6 receiver still contains A and therefore needs both transitions.
            this.conservativeMasks[cascade] = stickyKnown;
            this.requiredMasks[cascade] = affected;
            this.exactMasks[cascade] = this.scrollCascade[cascade]
                    ? 0L : this.handoffRetainedMasks[cascade] & ~affected;
        }
        this.activeUpdateClass = GiLiveUpdateClass.merge(
                this.activeUpdateClass, observedUpdateClass
        );
        this.preserveExact = this.activeUpdateClass != GiLiveUpdateClass.FULL_RESET;
        if (this.activeUpdateClass == GiLiveUpdateClass.STATIC_SOURCE) {
            this.activeResetKind = GiLiveLayout.ResetKind.SOURCE;
        }
        GiLiveEpoch provisional = epochFromObservation(
                field, positiveOrOne(staticEpoch), dynamic.epoch().sourceEpoch(),
                positiveDigest(environmentDigest)
        );
        boolean startsLatency = !this.fullResetRootOpen && !this.latency.hasPending();
        if (this.latency.hasPending()) {
            // The tracker preserves unfinished scroll/full-reset barriers, while a newer
            // incremental epoch owns last-event attribution. This historical SLA index is kept
            // separate from the current publication submit, which must never move backwards.
            this.latency.beginPending(observedUpdateClass, affectedSubmitIndex);
            this.firstAffectedSubmitIndex = this.latency.pendingFirstAffectedSubmitIndex();
        }
        beginEpoch(
                provisional, publicationSubmitIndex, affectedSubmitIndex,
                startsLatency ? EpochTransition.RETAINED_PROVISIONAL_NEW_LATENCY
                        : EpochTransition.RETAINED_PROVISIONAL);
        resetProvisionalPlanState();
        rememberObservation(field, dynamic, staticEpoch, staticHealthy, environmentDigest);
    }

    private void installAuthoritativeEpoch(
            final GiSemanticTransportFieldView field,
            final GiDirectSourceCoordinator.LiveSource source,
            final GiDynamicSourceSnapshot dynamic,
            final long publicationSubmitIndex
    ) {
        if (!source.epoch().staticLightWorld().equals(dynamic.epoch().world())) {
            throw new IllegalArgumentException(
                    "G6 authoritative G3 source belongs to another L3 light world"
            );
        }
        GiLiveEpoch authoritative = epochFromSource(field, source, dynamic);
        EpochTransition handoff = this.workAdmissionOccurred
                ? EpochTransition.AUTHORITATIVE_REBASE
                : EpochTransition.AUTHORITATIVE_HANDOFF;
        if (handoff == EpochTransition.AUTHORITATIVE_REBASE) {
            for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
                this.handoffRetainedMasks[cascade] = this.exactMasks[cascade];
                this.scrollCascade[cascade] = scrollRemapPendingAfterProvisionalRebase(
                        this.scrollCascade[cascade], this.cascadePrepared[cascade]
                );
            }
        }
        beginEpoch(
                authoritative, publicationSubmitIndex, this.firstAffectedSubmitIndex,
                handoff
        );
        this.activeEpochAuthoritative = true;
        this.workAdmissionOccurred = false;
        this.scheduler.rotateEpoch(authoritative);
        this.nextCascadeToEnqueue = 0;
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            this.cascadePrepared[cascade] = false;
            this.authoritativeMask[cascade] = false;
            this.authoritativeSources[cascade] = null;
        }
    }

    private void beginEpoch(
            final GiLiveEpoch epoch,
            final long publicationSubmitIndex,
            final long latencyFirstAffectedSubmitIndex,
            final EpochTransition transition
    ) {
        this.publication.beginEpoch(
                epoch, this.activeUpdateClass, publicationSubmitIndex
        );
        int status = this.resources.invalidatePlanned(
                epoch, this.activeResetKind, this.logicalSourceTick,
                this.preserveExact,
                transition.retainCapturedBasis && this.preserveExact,
                this.scrollCascade, this.requiredMasks
        );
        if (status != GiLiveLayout.STATUS_OK) {
            throw new IllegalStateException("Native G6 invalidation failed with status " + status);
        }
        this.activeEpoch = epoch;
        this.completion.beginEpoch();
        if (transition != EpochTransition.AUTHORITATIVE_HANDOFF) {
            this.activeEpochAuthoritative = false;
        }
        if (transition.startsLatency) {
            this.latency.beginPending(
                    this.activeUpdateClass, latencyFirstAffectedSubmitIndex
            );
            // An unfinished FULL_RESET is the root transaction. Child content/source epochs may
            // advance publication identity, but they must never retarget its SLA start.
            this.firstAffectedSubmitIndex = this.latency.pendingFirstAffectedSubmitIndex();
        }
    }

    private void resetProvisionalPlanState() {
        this.nextCascadeToEnqueue = 0;
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            this.cascadePrepared[cascade] = false;
            this.authoritativeMask[cascade] = false;
        }
    }

    private int retirePendingBatch(final GiLiveGpuResources.Stats stats) {
        GiLiveCompletionTracker.State state = this.completion.classify(
                this.activeEpoch, stats.buildInFlight(), stats.fieldGeneration(),
                stats.transportDispatches(), stats.rejectedCount()
        );
        if (state == GiLiveCompletionTracker.State.IDLE
                || state == GiLiveCompletionTracker.State.IN_FLIGHT) {
            // The caller defers observation rotation while this accepted shared-atlas write is
            // in flight, then consumes its exact receipt before capturing a successor basis.
            return GiLiveLayout.STATUS_OK;
        }
        if (state == GiLiveCompletionTracker.State.STALE_COMPLETION) {
            // Epoch rotation owns the discard accounting. Never complete/retry old ownership and
            // never let a stale receipt alter the current epoch's completion-failure budget.
            this.completion.clearAccepted();
            return GiLiveLayout.STATUS_OK;
        }

        GiLiveEpoch epoch = this.activeEpoch;
        if (state == GiLiveCompletionTracker.State.COMPLETED_CURRENT && epoch != null) {
            GiLiveDirtyScheduler.CompletionResult result = this.completion.remapOnly()
                    ? GiLiveDirtyScheduler.CompletionResult.COMPLETED
                    : this.scheduler.completeBatch(
                            epoch, this.drainedBricks, this.completion.batchCount()
                    );
            if (result != GiLiveDirtyScheduler.CompletionResult.COMPLETED) {
                failClosedPreparedBatch();
                this.completion.clearAccepted();
                return STATUS_ASYNC_COMPLETION_FAILED;
            }
            int cascade = this.completion.cascade();
            if (this.completion.remapOnly()) {
                this.exactMasks[cascade] = this.handoffRetainedMasks[cascade]
                        & ~this.requiredMasks[cascade];
            } else if (this.completion.preparedCascade()) {
                this.exactMasks[cascade] = this.authoritativeBaseExactMasks[cascade];
            }
            this.exactMasks[cascade] |= this.completion.batchMask();
            acknowledgeFullyExactCascade(cascade);
            this.completion.clearAccepted();
            this.completion.recordSuccess();
            return GiLiveLayout.STATUS_OK;
        }
        if (state == GiLiveCompletionTracker.State.FAILED_CURRENT && epoch != null) {
            boolean retryWouldRepeatScrollRemap = retryWouldRepeatNonIdempotentScroll(
                    this.completion.preparedCascade(),
                    this.scrollCascade[this.completion.cascade()]
            );
            GiLiveDirtyScheduler.CompletionResult result = this.completion.remapOnly()
                    ? GiLiveDirtyScheduler.CompletionResult.COMPLETED
                    : this.scheduler.retryBatch(
                            epoch, this.drainedBricks, this.completion.batchCount()
                    );
            failClosedPreparedBatch();
            this.completion.clearAccepted();
            // An accepted scroll batch may already have published its atlas remap before the
            // command buffer failed. Its queue ownership is restored for accounting, but this
            // runtime must stop immediately: retrying would shift the physical atlas twice.
            if (retryWouldRepeatScrollRemap
                    || result != GiLiveDirtyScheduler.CompletionResult.COMPLETED) {
                return STATUS_ASYNC_COMPLETION_FAILED;
            }
            return this.completion.recordFailure()
                    ? STATUS_ASYNC_COMPLETION_FAILED : GiLiveLayout.STATUS_OK;
        }

        // Unchanged, regressed, double-advanced or cross-counter receipts cannot prove whether
        // native published this batch. Stop production GI rather than retrying ambiguous work.
        failClosedPreparedBatch();
        this.completion.clearAccepted();
        return STATUS_ASYNC_COMPLETION_FAILED;
    }

    private void failClosedPreparedBatch() {
        if (!this.completion.pending() || !this.completion.preparedCascade()) return;
        int cascade = this.completion.cascade();
        this.cascadePrepared[cascade] = false;
        this.exactMasks[cascade] = 0L;
        this.resources.markCascadeUnprepared(cascade);
    }

    private void reconcileExactReadyMasks(final GiLiveGpuResources.Stats stats) {
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            if ((stats.readyMask() & (1 << cascade)) != 0) {
                this.exactMasks[cascade] = GiLiveLayout.ALL_BRICKS_MASK;
            }
        }
    }

    private boolean prepareNextCascadePlan(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final long dynamicEpoch,
            final long dynamicHash,
            final long sourceTick
    ) {
        if (this.nextCascadeToEnqueue >= GiLiveLayout.CASCADE_COUNT) return true;
        int cascade = this.nextCascadeToEnqueue;
        if (this.authoritativeMask[cascade]) return true;
        GiDirectSourceCoordinator.LiveSource source = this.direct.liveTransportSourceForSubmit(
                cascade, dynamicEpoch, dynamicHash, commandBuffer, fence, sourceTick
        );
        if (source == null) return false;
        long affected = authoritativePlanAffectedMask(
                this.conservativeMasks[cascade], source.affectedBrickMask()
        );
        long baseExact = this.preserveExact
                ? this.handoffRetainedMasks[cascade] & ~affected : 0L;
        this.authoritativeBaseExactMasks[cascade] = baseExact;
        this.requiredMasks[cascade] = requiredToConverge(baseExact, affected);
        this.exactMasks[cascade] = this.scrollCascade[cascade] ? 0L : baseExact;
        this.authoritativeMask[cascade] = true;
        int status = this.resources.planCascade(
                Objects.requireNonNull(this.activeEpoch, "activeEpoch"),
                cascade,
                this.preserveExact, this.scrollCascade[cascade], this.requiredMasks[cascade]
        );
        if (status != GiLiveLayout.STATUS_OK) {
            throw new IllegalStateException("Native G6 cascade plan failed with status " + status);
        }
        this.authoritativeSources[cascade] = source;
        this.conservativeMasks[cascade] = conservativeMaskAfterAcceptedPlan(
                this.preserveExact, this.conservativeMasks[cascade],
                source.affectedBrickMask()
        );
        // The native plan now durably owns every unfinished affected brick through its exact /
        // required masks. Clearing G3's transfer accumulator prevents the same historical dirt
        // from erasing completed progress on the next same-root streaming child. A mismatch
        // leaves G3 ownership intact and is therefore conservative rather than stale.
        this.direct.transferLiveAffectedBrickMask(
                cascade, source, commandBuffer, fence, sourceTick
        );
        return true;
    }

    /**
     * Advances G3's cumulative-mask baseline only after an authoritative asynchronous G6 build
     * has made this whole cascade exact. G3 revalidates its current native/source identity; a
     * source advance between admission and completion therefore leaves the accumulator intact.
     */
    private void acknowledgeFullyExactCascade(final int cascade) {
        if (!this.activeEpochAuthoritative
                || this.exactMasks[cascade] != GiLiveLayout.ALL_BRICKS_MASK) {
            return;
        }
        GiLiveEpoch epoch = this.activeEpoch;
        GiDirectSourceCoordinator.LiveSource source = this.authoritativeSources[cascade];
        if (epoch == null || source == null
                || !matchesAuthoritativeSourceIdentity(epoch, source)) {
            return;
        }
        if (this.direct.acknowledgeLiveAffectedBrickMask(cascade, source)) {
            this.authoritativeSources[cascade] = null;
        }
    }

    static boolean matchesAuthoritativeSourceIdentity(
            final GiLiveEpoch active,
            final GiDirectSourceCoordinator.LiveSource source
    ) {
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(source, "source");
        GiDirectSourceEpoch direct = source.epoch();
        if (!active.dimensionId().equals(direct.staticLightWorld().dimensionId())
                || active.worldGeneration() != direct.g2WorldGeneration()
                || active.resourceEpoch() != direct.g2ResourceEpoch()
                || active.materialEpoch() != direct.g2MaterialEpoch()
                || active.clipmapGeneration() != direct.g2ClipmapGeneration()
                || active.paletteGeneration() != direct.g2PaletteGeneration()
                || active.contentGeneration() != direct.g2ContentGeneration()
                || active.staticSourceEpoch() != direct.staticLightRegistryEpoch()
                || active.dynamicSourceEpoch() != source.dynamicSourceEpoch()
                || active.environmentEpoch() != direct.environmentEpoch()) {
            return false;
        }
        for (int index = 0; index < 9; index++) {
            GiLiveEpoch.Origin origin = active.origin(index / 3);
            int expected = switch (index % 3) {
                case 0 -> origin.x();
                case 1 -> origin.y();
                default -> origin.z();
            };
            if (source.originComponent(index) != expected) return false;
        }
        return true;
    }

    private void enqueueNextCascadeIfNeeded() {
        if (this.scheduler.ownedCount() != 0 || this.activeEpoch == null) return;
        while (this.nextCascadeToEnqueue < GiLiveLayout.CASCADE_COUNT) {
            int cascade = this.nextCascadeToEnqueue;
            if (!this.authoritativeMask[cascade]) return;
            this.nextCascadeToEnqueue++;
            long mask = this.requiredMasks[cascade];
            if (mask == 0L) continue;
            int base = cascade * GiLiveLayout.BRICKS_PER_CASCADE;
            for (int local = 0; local < GiLiveLayout.BRICKS_PER_CASCADE; local++) {
                if ((mask & (1L << local)) != 0L) {
                    this.scheduler.enqueue(this.activeEpoch, base + local,
                            this.activeUpdateClass, this.logicalSourceTick);
                }
            }
            return;
        }
    }

    private boolean hasAnyAuthoritativePlan() {
        for (boolean planned : this.authoritativeMask) {
            if (planned) return true;
        }
        return false;
    }

    private GiDirectSourceCoordinator.@Nullable LiveSource firstAvailableSource(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final long dynamicEpoch,
            final long dynamicHash,
            final long sourceTick
    ) {
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            GiDirectSourceCoordinator.LiveSource source =
                    this.direct.liveTransportSourceForSubmit(
                            cascade, dynamicEpoch, dynamicHash,
                            commandBuffer, fence, sourceTick
                    );
            if (source != null) return source;
        }
        return null;
    }

    private void observeCompletion(final GiLiveGpuResources.Stats stats, final long submitIndex) {
        if (this.activeEpoch == null
                || stats.fieldGeneration() != this.activeEpoch.version()) return;
        GiLiveUpdateClass pending = this.latency.pendingClassification();
        if (pending != null && latencyRecoveryIsReceiverVisible(
                pending, this.exactMasks[0], (stats.readyMask() & 1) != 0
        )) {
            long recoverySubmits = submitIndex
                    - this.latency.pendingFirstAffectedSubmitIndex();
            if (recoverySubmits > pending.p99SubmitLimit()) {
                Metallum.LOGGER.warn(
                        "[GI_G6] receiver-visible {} SLA breach: submits={} first={} ready={} "
                                + "field={} source_tick={} exact_near={} ready_mask={} "
                                + "active_class={} reset_kind={} full_reset_root={} authoritative={} "
                                + "queue_pending={} queue_in_flight={} next_cascade={}",
                        pending, recoverySubmits,
                        this.latency.pendingFirstAffectedSubmitIndex(),
                        submitIndex, stats.fieldGeneration(), stats.sourceTick(),
                        Long.toHexString(this.exactMasks[0]), stats.readyMask(),
                        this.activeUpdateClass, this.activeResetKind, this.fullResetRootOpen,
                        this.activeEpochAuthoritative, this.scheduler.pendingCount(),
                        this.scheduler.inFlightCount(), this.nextCascadeToEnqueue
                );
            }
            this.latency.recordPendingReady(submitIndex);
        }
        if (stats.allCascadesReady()) {
            this.publication.publishReady(this.activeEpoch, submitIndex);
            if (this.fullResetRootOpen && this.activeEpochAuthoritative
                    && this.lastFullResetStableSubmit != submitIndex) {
                this.fullResetStablePublicationSubmits++;
                this.lastFullResetStableSubmit = submitIndex;
            }
            if (shouldCloseOpenFullResetRoot(
                    this.fullResetRootOpen, this.activeEpochAuthoritative, true,
                    this.fullResetStablePublicationSubmits
            )) {
                this.fullResetRootOpen = false;
                this.fullResetStablePublicationSubmits = 0;
                this.lastFullResetStableSubmit = -1L;
            }
        } else if (this.fullResetRootOpen) {
            this.fullResetStablePublicationSubmits = 0;
            this.lastFullResetStableSubmit = -1L;
        }
    }

    /**
     * Matches the predeclared SLA to the exact coverage consumed by the vertex receiver.
     * Incremental changes recover only when every affected near brick is exact again, represented
     * by native's full-cascade ready bit. Scroll/reset starts from incompatible zero coverage and
     * recovers once the first current exact near brick is admitted; all-cascade convergence stays
     * the independent publication/root-closure barrier below.
     */
    static boolean latencyRecoveryIsReceiverVisible(
            final GiLiveUpdateClass classification,
            final long exactNearMask,
            final boolean nearCascadeFullyReady
    ) {
        Objects.requireNonNull(classification, "classification");
        if (classification == GiLiveUpdateClass.SCROLL
                || classification == GiLiveUpdateClass.FULL_RESET) {
            return exactNearMask != 0L;
        }
        return nearCascadeFullyReady;
    }

    private void publishFinalTelemetry(final GiLiveGpuResources.Stats stats) {
        long accounted = Math.addExact(G2_G3_ACCOUNTED_BYTES,
                Math.addExact(stats.allocatedBytes(), GiLiveLayout.JAVA_PACKET_BYTES));
        GiLiveRuntime.publishFinalTelemetry(
                this.deviceGeneration,
                stats.readyMask(), stats.buildInFlight(), stats.staleRejects(),
                stats.rejectedCount(), accounted, stats.fieldGeneration(), stats.sourceTick(),
                this.latency.sampleCount(GiLiveUpdateClass.BLOCK),
                this.latency.p95Submits(GiLiveUpdateClass.BLOCK),
                this.latency.p99Submits(GiLiveUpdateClass.BLOCK),
                this.latency.meetsSla(GiLiveUpdateClass.BLOCK),
                this.latency.sampleCount(GiLiveUpdateClass.STATIC_SOURCE),
                this.latency.p95Submits(GiLiveUpdateClass.STATIC_SOURCE),
                this.latency.p99Submits(GiLiveUpdateClass.STATIC_SOURCE),
                this.latency.meetsSla(GiLiveUpdateClass.STATIC_SOURCE),
                this.latency.sampleCount(GiLiveUpdateClass.SCROLL),
                this.latency.p95Submits(GiLiveUpdateClass.SCROLL),
                this.latency.p99Submits(GiLiveUpdateClass.SCROLL),
                this.latency.meetsSla(GiLiveUpdateClass.SCROLL),
                this.latency.sampleCount(GiLiveUpdateClass.FULL_RESET),
                this.latency.p95Submits(GiLiveUpdateClass.FULL_RESET),
                this.latency.p99Submits(GiLiveUpdateClass.FULL_RESET),
                this.latency.meetsSla(GiLiveUpdateClass.FULL_RESET),
                this.scheduler.queuedTotal(), this.scheduler.completedTotal(),
                this.scheduler.discardedTotal(), this.scheduler.pendingCount(),
                this.scheduler.inFlightCount(), this.scheduler.algebraIsExact()
        );
    }

    private void computeProvisionalMasks(final GiSemanticTransportFieldView field) {
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            this.scrollCascade[cascade] = false;
            this.scrollDeltaXBlocks[cascade] = 0;
            this.scrollDeltaYBlocks[cascade] = 0;
            this.scrollDeltaZBlocks[cascade] = 0;
            this.requiredMasks[cascade] = 0L;
        }
        if (!this.observed || this.activeUpdateClass == GiLiveUpdateClass.FULL_RESET) {
            for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
                this.requiredMasks[cascade] = GiLiveLayout.ALL_BRICKS_MASK;
            }
            return;
        }
        // Event attribution stays tied to the newly observed semantic/source mutation. A
        // receiver cascade that still occupies an older physical grid nevertheless keeps its
        // remap plan across those successor epochs. Reclassifying every source receipt as a new
        // SCROLL churned the near barrier and delayed visible recovery until the next movement.
        computePhysicalScrollPlan(field);
        if (this.activeUpdateClass == GiLiveUpdateClass.STATIC_SOURCE) {
            // Source dirt is supplied by G3's exact per-cascade affected masks. A simultaneous
            // semantic content receipt remains independently CPU-provable and must stay sticky
            // even while that source mask is unavailable.
            if (this.observedContent != field.contentGeneration()) {
                mergeChangedBlockMasks(field, this.requiredMasks);
            }
            return;
        }
        if (this.activeUpdateClass == GiLiveUpdateClass.SCROLL) {
            return;
        }
        mergeChangedBlockMasks(field, this.requiredMasks);
    }

    private void computePhysicalScrollPlan(final GiSemanticTransportFieldView field) {
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            int offset = cascade * 3;
            int dx = field.originComponent(offset) - this.receiverOrigins[offset];
            int dy = field.originComponent(offset + 1) - this.receiverOrigins[offset + 1];
            int dz = field.originComponent(offset + 2) - this.receiverOrigins[offset + 2];
            this.scrollDeltaXBlocks[cascade] = dx;
            this.scrollDeltaYBlocks[cascade] = dy;
            this.scrollDeltaZBlocks[cascade] = dz;
            this.scrollCascade[cascade] = dx != 0 || dy != 0 || dz != 0;
            if (this.scrollCascade[cascade]) {
                this.requiredMasks[cascade] |= expandTransportHalo(
                        scrollExposedMask(cascade, dx, dy, dz)
                );
            }
        }
    }

    private void computeCoalescedMasks(
            final GiSemanticTransportFieldView field,
            final GiLiveUpdateClass observedUpdateClass,
            final long[] destination
    ) {
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            destination[cascade] = 0L;
        }
        if (observedUpdateClass == GiLiveUpdateClass.STATIC_SOURCE) {
            if (this.observedContent != field.contentGeneration()) {
                computeChangedBlockMasks(field, destination);
            }
            return;
        }
        if (observedUpdateClass != GiLiveUpdateClass.BLOCK) {
            throw new IllegalArgumentException(
                    "G6 retained provisional overlap must be block/source-only"
            );
        }
        computeChangedBlockMasks(field, destination);
    }

    private void computeChangedBlockMasks(
            final GiSemanticTransportFieldView field,
            final long[] destination
    ) {
        for (int brick = 0; brick < GiDirectSourceLayout.TOTAL_BRICKS; brick++) {
            if (field.brickContentStamp(brick) != this.observedBrickStamps[brick]) {
                int cascade = GiDirectSourceLayout.cascadeForBrickId(brick);
                destination[cascade] |= 1L
                        << (brick - cascade * GiLiveLayout.BRICKS_PER_CASCADE);
            }
        }
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            destination[cascade] = expandTransportHalo(destination[cascade]);
        }
    }

    private void mergeChangedBlockMasks(
            final GiSemanticTransportFieldView field, final long[] destination
    ) {
        java.util.Arrays.fill(this.coalescedMasks, 0L);
        computeChangedBlockMasks(field, this.coalescedMasks);
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            destination[cascade] |= this.coalescedMasks[cascade];
        }
    }

    static long expandTransportHalo(final long coreMask) {
        long expanded = coreMask;
        for (int brick = 0; brick < 64; brick++) {
            if ((coreMask & (1L << brick)) == 0L) continue;
            int x = brick & 3;
            int y = (brick >> 2) & 3;
            int z = brick >> 4;
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (nx >= 0 && nx < 4 && ny >= 0 && ny < 4 && nz >= 0 && nz < 4) {
                        expanded |= 1L << ((nz * 4 + ny) * 4 + nx);
                    }
                }
            }
        }
        return expanded;
    }

    /** Never lets a source-only refinement erase semantic reset/block/scroll work. */
    static long authoritativeAffectedMask(
            final GiLiveUpdateClass updateClass,
            final long conservativeMask,
            final long affectedMask,
            final boolean exactAffectedMaskAvailable
    ) {
        if (!exactAffectedMaskAvailable || updateClass == GiLiveUpdateClass.FULL_RESET) {
            return conservativeMask;
        }
        long sourceMask = expandTransportHalo(affectedMask);
        return updateClass == GiLiveUpdateClass.STATIC_SOURCE
                ? sourceMask : conservativeMask | sourceMask;
    }

    /** Source drift with no exact G3 affected mask invalidates every provisional receiver brick. */
    static long provisionalAffectedMask(
            final GiLiveUpdateClass updateClass,
            final long conservativeMask,
            final long affectedMask,
            final boolean exactAffectedMaskAvailable,
            final boolean sourceIdentityChanged
    ) {
        if (sourceIdentityChanged && !exactAffectedMaskAvailable) {
            return GiLiveLayout.ALL_BRICKS_MASK;
        }
        if (exactAffectedMaskAvailable) {
            return stickyKnownAffectedMask(
                    updateClass, conservativeMask, affectedMask, true
            );
        }
        return authoritativeAffectedMask(
                updateClass, conservativeMask, affectedMask, exactAffectedMaskAvailable
        );
    }

    /** Unknown source identity is a temporary visibility barrier, never sticky physical dirt. */
    static long stickyKnownAffectedMask(
            final GiLiveUpdateClass updateClass,
            final long conservativeMask,
            final long affectedMask,
            final boolean exactAffectedMaskAvailable
    ) {
        Objects.requireNonNull(updateClass, "updateClass");
        return exactAffectedMaskAvailable
                ? conservativeMask | expandTransportHalo(affectedMask)
                : conservativeMask;
    }

    /**
     * A structural FULL_RESET has no compatible exact basis, so its ALL visibility requirement
     * belongs to exact/requiredToConverge rather than sticky incremental dirt. Incremental and
     * scroll transitions keep their CPU-proven semantic/source masks normally.
     */
    static long stickyKnownPhysicalDirt(
            final GiLiveUpdateClass updateClass,
            final long conservativeMask,
            final long affectedMask,
            final boolean exactAffectedMaskAvailable
    ) {
        return updateClass == GiLiveUpdateClass.FULL_RESET
                ? 0L : stickyKnownAffectedMask(
                        updateClass, conservativeMask,
                        affectedMask, exactAffectedMaskAvailable
                );
    }

    /** Final G3 refinement never erases dirt accumulated relative to G6's captured basis. */
    static long authoritativePlanAffectedMask(
            final long conservativeMask,
            final long authoritativeSourceMask
    ) {
        return conservativeMask | expandTransportHalo(authoritativeSourceMask);
    }

    /** Keeps a transferred incremental source delta until physical exact/required owns it. */
    static long conservativeMaskAfterAcceptedPlan(
            final boolean preserveExact,
            final long conservativeMask,
            final long transferredSourceMask
    ) {
        // FULL_RESET intentionally keeps structural ALL out of sticky dirt: exact=0 owns it.
        return preserveExact
                ? conservativeMask | expandTransportHalo(transferredSourceMask)
                : 0L;
    }

    /**
     * A source identity with no exact G3 mask must provisionally hide every cascade. The retained
     * scroll basis is kept separately so the later authoritative plan can recover unaffected
     * cells without turning this temporary fail-closed mask into permanent full-volume dirt.
     */
    static long retainedProvisionalAffectedMask(
            final GiLiveUpdateClass observedUpdateClass,
            final long conservativeMask,
            final long affectedSourceMask,
            final boolean exactAffectedMaskAvailable,
            final boolean sourceIdentityChanged
    ) {
        if (observedUpdateClass != GiLiveUpdateClass.BLOCK
                && observedUpdateClass != GiLiveUpdateClass.STATIC_SOURCE) {
            throw new IllegalArgumentException(
                    "G6 retained provisional overlap must be block/source-only"
            );
        }
        if (sourceIdentityChanged && !exactAffectedMaskAvailable) {
            return GiLiveLayout.ALL_BRICKS_MASK;
        }
        return exactAffectedMaskAvailable
                ? conservativeMask | expandTransportHalo(affectedSourceMask)
                : conservativeMask;
    }

    /** A captured provisional basis is reusable only until the first GPU batch is admitted. */
    static boolean shouldRetainPendingBasis(
            final GiLiveUpdateClass activeUpdateClass,
            final boolean capturedBasisAvailable,
            final GiLiveUpdateClass observedUpdateClass,
            final boolean workAdmissionOccurred
    ) {
        return activeUpdateClass != null
                && capturedBasisAvailable
                && (observedUpdateClass == GiLiveUpdateClass.BLOCK
                || observedUpdateClass == GiLiveUpdateClass.STATIC_SOURCE)
                && !workAdmissionOccurred;
    }

    /**
     * Same-grid content/source churn remains inside the earliest unfinished reset root. A
     * bounded origin move is also a root child while near lacks a fully exact compatible basis;
     * once near is complete it is an independently visible scroll and receives its own SLA.
     */
    static boolean shouldRetainOpenFullResetChild(
            final boolean fullResetRootOpen,
            final boolean sameStructuralRoot,
            final boolean sameOrigins,
            final boolean nearCascadeFullyReady,
            final GiLiveUpdateClass childUpdateClass
    ) {
        if (!fullResetRootOpen || !sameStructuralRoot) return false;
        if (childUpdateClass == GiLiveUpdateClass.SCROLL) {
            return !nearCascadeFullyReady;
        }
        return !nearCascadeFullyReady && sameOrigins
                && (childUpdateClass == GiLiveUpdateClass.BLOCK
                || childUpdateClass == GiLiveUpdateClass.STATIC_SOURCE);
    }

    static boolean shouldCloseOpenFullResetRoot(
            final boolean fullResetRootOpen,
            final boolean activeEpochAuthoritative,
            final boolean allCascadesReady,
            final int stablePublicationSubmits
    ) {
        if (stablePublicationSubmits < 0) {
            throw new IllegalArgumentException("Negative G6 full-reset stability count");
        }
        return fullResetRootOpen && activeEpochAuthoritative && allCascadesReady
                && stablePublicationSubmits >= FULL_RESET_STABLE_PUBLICATION_SUBMITS;
    }

    static boolean shouldHoldCompletedFullResetPublication(
            final boolean fullResetRootOpen,
            final boolean activeEpochAuthoritative,
            final boolean allCascadesReady,
            final boolean sameStructuralRoot,
            final boolean sameOrigins,
            final GiLiveUpdateClass pendingClass
    ) {
        return fullResetRootOpen && activeEpochAuthoritative && allCascadesReady
                && sameStructuralRoot && sameOrigins
                && (pendingClass == GiLiveUpdateClass.BLOCK
                || pendingClass == GiLiveUpdateClass.STATIC_SOURCE);
    }

    static boolean shouldContinueAuthoritativePlanning(
            final int pendingBricks,
            final int nextCascade
    ) {
        if (pendingBricks < 0 || nextCascade < 0
                || nextCascade > GiLiveLayout.CASCADE_COUNT) {
            throw new IllegalArgumentException("Invalid G6 authoritative planning state");
        }
        return pendingBricks == 0 && nextCascade < GiLiveLayout.CASCADE_COUNT;
    }

    static boolean shouldEncodeProvisionalNearScrollRemap(
            final boolean authoritative,
            final GiLiveUpdateClass updateClass,
            final boolean nearScroll,
            final boolean nearPrepared
    ) {
        return !authoritative
                && updateClass == GiLiveUpdateClass.SCROLL
                && nearScroll
                && !nearPrepared;
    }

    /** A provisional near remap cannot silently consume pending outer-cascade scrolls. */
    static boolean scrollRemapPendingAfterProvisionalRebase(
            final boolean scrolling,
            final boolean provisionalRemapPrepared
    ) {
        return scrolling && !provisionalRemapPrepared;
    }

    /**
     * A compatible receiver tuple is safe before any brick becomes exact. Native keeps its
     * receiver mask separate from exact publication: same-origin invalidation retains it
     * immediately, while scroll makes it usable only after the remap encoder has been admitted
     * ahead of terrain draws. Requiring a non-zero exact mask here made ordinary walking bind
     * global zero for several frames even though compatible receiver history already existed.
     */
    static boolean provisionalReceiverHistoryCanBind(
            final boolean authoritative,
            final boolean preserveExact,
            final GiLiveUpdateClass updateClass,
            final boolean nearPrepared
    ) {
        Objects.requireNonNull(updateClass, "updateClass");
        return !authoritative && preserveExact
                && (updateClass != GiLiveUpdateClass.SCROLL || nearPrepared);
    }

    static int unavailableAuthoritativeSourceStatus(
            final boolean authoritativePlanAvailable
    ) {
        return authoritativePlanAvailable ? STATUS_NO_WORK : STATUS_INPUT_NOT_READY;
    }

    /** Includes unfinished work inherited from a superseded epoch, not only its latest dirt. */
    static long requiredToConverge(
            final long retainedCompatibleExactMask,
            final long affectedMask
    ) {
        return ~(retainedCompatibleExactMask & ~affectedMask);
    }

    /** Exact Java mirror of native scrollRetainedMask, expressed in block-space deltas. */
    static long scrollRetainedExactMask(
            final long previousMask,
            final int cascade,
            final int dxBlocks,
            final int dyBlocks,
            final int dzBlocks
    ) {
        int cellSize = GiDirectSourceLayout.cellSizeBlocks(cascade);
        if (dxBlocks % cellSize != 0 || dyBlocks % cellSize != 0
                || dzBlocks % cellSize != 0) return 0L;
        int dx = dxBlocks / cellSize;
        int dy = dyBlocks / cellSize;
        int dz = dzBlocks / cellSize;
        if (Math.abs(dx) >= 32 || Math.abs(dy) >= 32 || Math.abs(dz) >= 32) return 0L;
        long result = 0L;
        for (int destinationBrick = 0; destinationBrick < 64; destinationBrick++) {
            int brickX = destinationBrick & 3;
            int brickY = (destinationBrick >> 2) & 3;
            int brickZ = destinationBrick >> 4;
            boolean compatible = true;
            for (int cornerZIndex = 0; cornerZIndex < 2; cornerZIndex++) {
                int cornerZ = cornerZIndex * 7;
                for (int cornerYIndex = 0; cornerYIndex < 2; cornerYIndex++) {
                    int cornerY = cornerYIndex * 7;
                    for (int cornerXIndex = 0; cornerXIndex < 2; cornerXIndex++) {
                        int cornerX = cornerXIndex * 7;
                        int sourceX = brickX * 8 + cornerX + dx;
                        int sourceY = brickY * 8 + cornerY + dy;
                        int sourceZ = brickZ * 8 + cornerZ + dz;
                        if (sourceX < 0 || sourceX >= 32 || sourceY < 0 || sourceY >= 32
                                || sourceZ < 0 || sourceZ >= 32) {
                            compatible = false;
                            continue;
                        }
                        int sourceBrick = (sourceZ >> 3) << 4
                                | (sourceY >> 3) << 2 | (sourceX >> 3);
                        if ((previousMask & (1L << sourceBrick)) == 0L) {
                            compatible = false;
                        }
                    }
                }
            }
            if (compatible) result |= 1L << destinationBrick;
        }
        return result;
    }

    /** Mirrors the native same-origin plan refinement used after provisional -> authoritative. */
    static long exactMaskAfterPlan(
            final long retainedExactMask,
            final long requiredMask,
            final boolean scrolling
    ) {
        return scrolling ? 0L : retainedExactMask & ~requiredMask;
    }

    /** Per-cascade retained tuple selection for provisional -> authoritative handoff. */
    static long retainedExactForTransition(
            final long preProvisionalExactMask,
            final long currentExactMask,
            final boolean retainCapturedBasis
    ) {
        return retainCapturedBasis ? preProvisionalExactMask : currentExactMask;
    }

    private static long scrollExposedMask(
            final int cascade, final int dxBlocks, final int dyBlocks, final int dzBlocks
    ) {
        int cellSize = GiDirectSourceLayout.cellSizeBlocks(cascade);
        if (dxBlocks % cellSize != 0 || dyBlocks % cellSize != 0
                || dzBlocks % cellSize != 0) return GiLiveLayout.ALL_BRICKS_MASK;
        int dx = dxBlocks / cellSize, dy = dyBlocks / cellSize, dz = dzBlocks / cellSize;
        long mask = 0L;
        for (int brick = 0; brick < 64; brick++) {
            int x0 = (brick & 3) * 8 + dx;
            int y0 = ((brick >> 2) & 3) * 8 + dy;
            int z0 = (brick >> 4) * 8 + dz;
            if (x0 < 0 || x0 + 7 >= 32 || y0 < 0 || y0 + 7 >= 32
                    || z0 < 0 || z0 + 7 >= 32) mask |= 1L << brick;
        }
        return mask;
    }

    private GiLiveEpoch epochFromObservation(
            final GiSemanticTransportFieldView field, final long staticEpoch,
            final long dynamicEpoch, final long environmentEpoch
    ) {
        return new GiLiveEpoch(nextVersion(), field.world().dimensionId(),
                field.worldGeneration(), field.resourceEpoch(), field.materialEpoch(),
                field.clipmapGeneration(), field.paletteGeneration(), field.contentGeneration(),
                staticEpoch, dynamicEpoch, environmentEpoch,
                origin(field, 0), origin(field, 1), origin(field, 2));
    }

    private GiLiveEpoch epochFromSource(
            final GiSemanticTransportFieldView field,
            final GiDirectSourceCoordinator.LiveSource source,
            final GiDynamicSourceSnapshot dynamic
    ) {
        GiDirectSourceEpoch directEpoch = source.epoch();
        return new GiLiveEpoch(nextVersion(), field.world().dimensionId(),
                directEpoch.g2WorldGeneration(), directEpoch.g2ResourceEpoch(),
                directEpoch.g2MaterialEpoch(), directEpoch.g2ClipmapGeneration(),
                directEpoch.g2PaletteGeneration(), directEpoch.g2ContentGeneration(),
                directEpoch.staticLightRegistryEpoch(), dynamic.epoch().sourceEpoch(),
                directEpoch.environmentEpoch(),
                origin(source, 0), origin(source, 1), origin(source, 2));
    }

    private long nextVersion() {
        this.nextEpochVersion = Math.incrementExact(this.nextEpochVersion);
        return this.nextEpochVersion;
    }

    private static GiLiveEpoch.Origin origin(
            final GiSemanticTransportFieldView field, final int cascade
    ) {
        int offset = cascade * 3;
        return new GiLiveEpoch.Origin(field.originComponent(offset),
                field.originComponent(offset + 1), field.originComponent(offset + 2));
    }

    private static GiLiveEpoch.Origin origin(
            final GiDirectSourceCoordinator.LiveSource source, final int cascade
    ) {
        int offset = cascade * 3;
        return new GiLiveEpoch.Origin(source.originComponent(offset),
                source.originComponent(offset + 1), source.originComponent(offset + 2));
    }

    private boolean observationChanged(
            final GiSemanticTransportFieldView field, final GiDynamicSourceSnapshot dynamic,
            final long staticEpoch, final boolean staticHealthy, final long environmentDigest
    ) {
        if (!this.observed) return true;
        if (!Objects.equals(this.observedDimension, field.world().dimensionId())
                || this.observedWorld != field.worldGeneration()
                || this.observedResource != field.resourceEpoch()
                || this.observedMaterial != field.materialEpoch()
                || this.observedClipmap != field.clipmapGeneration()
                || this.observedPalette != field.paletteGeneration()
                || this.observedContent != field.contentGeneration()
                || this.observedStatic != staticEpoch
                || this.observedStaticHealthy != staticHealthy
                || this.observedDynamic != dynamic.epoch().sourceEpoch()
                || this.observedDynamicHash != dynamic.sourceHash()
                || this.observedEnvironmentDigest != environmentDigest) return true;
        for (int index = 0; index < this.observedOrigins.length; index++) {
            if (this.observedOrigins[index] != field.originComponent(index)) return true;
        }
        return false;
    }

    private boolean sameObservedStructuralRoot(final GiSemanticTransportFieldView field) {
        return this.observed
                && Objects.equals(this.observedDimension, field.world().dimensionId())
                && this.observedWorld == field.worldGeneration()
                && this.observedResource == field.resourceEpoch()
                && this.observedMaterial == field.materialEpoch()
                && this.observedPalette == field.paletteGeneration();
    }

    private boolean sameObservedOrigins(final GiSemanticTransportFieldView field) {
        if (!this.observed) return false;
        for (int index = 0; index < this.observedOrigins.length; index++) {
            if (this.observedOrigins[index] != field.originComponent(index)) return false;
        }
        return true;
    }

    private boolean sourceIdentityChanged(
            final GiDynamicSourceSnapshot dynamic,
            final long staticEpoch,
            final boolean staticHealthy,
            final long environmentDigest
    ) {
        return !this.observed
                || this.observedStatic != staticEpoch
                || this.observedStaticHealthy != staticHealthy
                || this.observedDynamic != dynamic.epoch().sourceEpoch()
                || this.observedDynamicHash != dynamic.sourceHash()
                || this.observedEnvironmentDigest != environmentDigest;
    }

    private void recordDeferredObservation(
            final GiLiveUpdateClass updateClass,
            final long submitIndex
    ) {
        if (submitIndex < 0L) {
            throw new IllegalArgumentException("G6 deferred observation submit is invalid");
        }
        if (this.deferredUpdateClass == null) {
            this.deferredFirstAffectedSubmitIndex = submitIndex;
        } else if (submitIndex < this.deferredFirstAffectedSubmitIndex) {
            throw new IllegalArgumentException("G6 deferred observation moved backwards");
        }
        this.deferredUpdateClass = GiLiveUpdateClass.merge(
                this.deferredUpdateClass, updateClass
        );
    }

    private void recordDeferredSourceMasks(
            final GiSemanticTransportFieldView field,
            final GiDynamicSourceSnapshot dynamic,
            final long staticEpoch,
            final boolean staticHealthy,
            final long environmentDigest
    ) {
        if (!sourceIdentityChanged(dynamic, staticEpoch, staticHealthy, environmentDigest)) {
            return;
        }
        for (int index = 0; index < this.observedOrigins.length; index++) {
            if (field.originComponent(index) != this.observedOrigins[index]) {
                // Brick bits are only comparable inside the same cascade coordinate system.
                // A scroll observed while an older-origin batch is in flight therefore needs a
                // conservative successor, not a union of masks from two different grids.
                this.deferredSourceMaskUnknown = true;
                return;
            }
        }
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            this.observedAffectedMasks[cascade] = 0L;
        }
        boolean exact = this.direct.copyObservedLiveAffectedBrickMasks(
                field, staticHealthy ? staticEpoch : 0L, environmentDigest,
                dynamic.epoch().sourceEpoch(), dynamic.sourceHash(),
                this.observedAffectedMasks
        );
        if (!exact) {
            this.deferredSourceMaskUnknown = true;
            return;
        }
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            this.deferredAffectedMasks[cascade] |= this.observedAffectedMasks[cascade];
        }
    }

    private boolean copyCombinedAffectedMasks(
            final GiSemanticTransportFieldView field,
            final GiDynamicSourceSnapshot dynamic,
            final long staticEpoch,
            final boolean staticHealthy,
            final long environmentDigest
    ) {
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            this.observedAffectedMasks[cascade] = 0L;
        }
        boolean exact = this.direct.copyObservedLiveAffectedBrickMasks(
                field, staticHealthy ? staticEpoch : 0L, environmentDigest,
                dynamic.epoch().sourceEpoch(), dynamic.sourceHash(),
                this.observedAffectedMasks
        );
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            this.observedAffectedMasks[cascade] |= this.deferredAffectedMasks[cascade];
        }
        return exact && !this.deferredSourceMaskUnknown;
    }

    private void clearDeferredObservation() {
        this.deferredUpdateClass = null;
        this.deferredFirstAffectedSubmitIndex = -1L;
        this.deferredSourceMaskUnknown = false;
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            this.deferredAffectedMasks[cascade] = 0L;
        }
    }

    private GiLiveUpdateClass classifyObservation(
            final GiSemanticTransportFieldView field, final GiDynamicSourceSnapshot dynamic,
            final long staticEpoch, final boolean staticHealthy, final long environmentDigest
    ) {
        if (!this.observed
                || !Objects.equals(this.observedDimension, field.world().dimensionId())
                || this.observedWorld != field.worldGeneration()
                || this.observedResource != field.resourceEpoch()
                || this.observedMaterial != field.materialEpoch()
                || this.observedPalette != field.paletteGeneration()) {
            return GiLiveUpdateClass.FULL_RESET;
        }
        boolean originsChanged = false, boundedScroll = true;
        for (int index = 0; index < this.observedOrigins.length; index++) {
            int next = field.originComponent(index);
            if (next == this.observedOrigins[index]) continue;
            originsChanged = true;
            int cascade = index / 3;
            int maximumStep = GiDirectSourceLayout.BRICK_EDGE_CELLS
                    * GiDirectSourceLayout.cellSizeBlocks(cascade);
            if (Math.abs((long) next - this.observedOrigins[index]) > maximumStep) {
                boundedScroll = false;
            }
        }
        if (originsChanged || this.observedClipmap != field.clipmapGeneration()) {
            return boundedScroll ? GiLiveUpdateClass.SCROLL : GiLiveUpdateClass.FULL_RESET;
        }
        boolean contentChanged = this.observedContent != field.contentGeneration();
        boolean environmentChanged = this.observedEnvironmentDigest != environmentDigest;
        boolean sourceChanged = this.observedStatic != staticEpoch
                || this.observedStaticHealthy != staticHealthy
                || this.observedDynamic != dynamic.epoch().sourceEpoch()
                || this.observedDynamicHash != dynamic.sourceHash()
                || environmentChanged;
        if (contentChanged || sourceChanged) {
            return classifyIncrementalObservation(
                    contentChanged, sourceChanged, environmentChanged
            );
        }
        return GiLiveUpdateClass.BLOCK;
    }

    /** Environment irradiance changes every direct-field brick and keeps the global SLA class. */
    static GiLiveUpdateClass classifyIncrementalObservation(
            final boolean contentChanged,
            final boolean sourceChanged,
            final boolean environmentChanged
    ) {
        if (environmentChanged) return GiLiveUpdateClass.FULL_RESET;
        return GiLiveUpdateClass.merge(
                contentChanged ? GiLiveUpdateClass.BLOCK : null,
                sourceChanged ? GiLiveUpdateClass.STATIC_SOURCE : null
        );
    }

    private GiLiveLayout.ResetKind resetKindFor(
            final GiLiveUpdateClass updateClass, final GiSemanticTransportFieldView field,
            final long staticEpoch, final boolean staticHealthy
    ) {
        if (!this.observed || this.observedWorld != field.worldGeneration()
                || !Objects.equals(this.observedDimension, field.world().dimensionId())) {
            return GiLiveLayout.ResetKind.WORLD;
        }
        if (updateClass == GiLiveUpdateClass.FULL_RESET) {
            boolean originJump = false;
            for (int index = 0; index < this.observedOrigins.length; index++) {
                originJump |= this.observedOrigins[index] != field.originComponent(index);
            }
            return originJump ? GiLiveLayout.ResetKind.TELEPORT
                    : GiLiveLayout.ResetKind.EXPLICIT;
        }
        if (updateClass == GiLiveUpdateClass.SCROLL) return GiLiveLayout.ResetKind.SCROLL;
        if (updateClass == GiLiveUpdateClass.STATIC_SOURCE
                || this.observedStatic != staticEpoch
                || this.observedStaticHealthy != staticHealthy) {
            return GiLiveLayout.ResetKind.SOURCE;
        }
        return GiLiveLayout.ResetKind.EXPLICIT;
    }

    private void rememberObservation(
            final GiSemanticTransportFieldView field, final GiDynamicSourceSnapshot dynamic,
            final long staticEpoch, final boolean staticHealthy, final long environmentDigest
    ) {
        this.observed = true;
        this.observedDimension = field.world().dimensionId();
        this.observedWorld = field.worldGeneration();
        this.observedResource = field.resourceEpoch();
        this.observedMaterial = field.materialEpoch();
        this.observedClipmap = field.clipmapGeneration();
        this.observedPalette = field.paletteGeneration();
        this.observedContent = field.contentGeneration();
        this.observedStatic = staticEpoch;
        this.observedStaticHealthy = staticHealthy;
        this.observedDynamic = dynamic.epoch().sourceEpoch();
        this.observedDynamicHash = dynamic.sourceHash();
        this.observedEnvironmentDigest = environmentDigest;
        for (int index = 0; index < this.observedOrigins.length; index++) {
            this.observedOrigins[index] = field.originComponent(index);
        }
        for (int brick = 0; brick < this.observedBrickStamps.length; brick++) {
            this.observedBrickStamps[brick] = field.brickContentStamp(brick);
        }
    }

    private void resetReceiverOrigins(final GiSemanticTransportFieldView field) {
        for (int index = 0; index < this.receiverOrigins.length; index++) {
            this.receiverOrigins[index] = field.originComponent(index);
        }
        this.receiverOriginsKnown = true;
    }

    /**
     * Native changes the physical atlas grid when it accepts a prepared encode. The encoder is
     * ordered before terrain draws in the same command buffer; an asynchronous failure makes the
     * whole G6 runtime invalid, so Java can advance this mirror at admission without ambiguity.
     */
    private void advanceReceiverOrigin(final GiLiveEpoch epoch, final int cascade) {
        GiLiveEpoch.Origin origin = epoch.origin(cascade);
        int offset = cascade * 3;
        this.receiverOrigins[offset] = origin.x();
        this.receiverOrigins[offset + 1] = origin.y();
        this.receiverOrigins[offset + 2] = origin.z();
        this.receiverOriginsKnown = true;
    }

    private void advanceSourceTick(final long inputSourceTick) {
        if (this.lastInputSourceTick == Long.MIN_VALUE) this.logicalSourceTick = inputSourceTick;
        else if (inputSourceTick > this.lastInputSourceTick) {
            this.logicalSourceTick = Math.addExact(
                    this.logicalSourceTick, inputSourceTick - this.lastInputSourceTick);
        } else if (inputSourceTick < this.lastInputSourceTick) {
            this.logicalSourceTick = Math.incrementExact(this.logicalSourceTick);
        }
        this.lastInputSourceTick = inputSourceTick;
    }

    private static boolean matchesAuthoritative(
            final @Nullable GiLiveEpoch active, final GiSemanticTransportFieldView field,
            final GiDirectSourceCoordinator.LiveSource source,
            final GiDynamicSourceSnapshot dynamic
    ) {
        if (active == null) return false;
        GiDirectSourceEpoch direct = source.epoch();
        if (!active.dimensionId().equals(field.world().dimensionId())
                || active.worldGeneration() != direct.g2WorldGeneration()
                || active.resourceEpoch() != direct.g2ResourceEpoch()
                || active.materialEpoch() != direct.g2MaterialEpoch()
                || active.clipmapGeneration() != direct.g2ClipmapGeneration()
                || active.paletteGeneration() != direct.g2PaletteGeneration()
                || active.contentGeneration() != direct.g2ContentGeneration()
                || active.staticSourceEpoch() != direct.staticLightRegistryEpoch()
                || active.dynamicSourceEpoch() != dynamic.epoch().sourceEpoch()
                || active.environmentEpoch() != direct.environmentEpoch()) return false;
        for (int index = 0; index < 9; index++) {
            GiLiveEpoch.Origin origin = active.origin(index / 3);
            int expected = switch (index % 3) {
                case 0 -> origin.x();
                case 1 -> origin.y();
                default -> origin.z();
            };
            if (source.originComponent(index) != expected
                    || field.originComponent(index) != expected) return false;
        }
        return true;
    }

    private static boolean sourceMatchesObservation(
            final GiSemanticTransportFieldView field,
            final GiDirectSourceCoordinator.LiveSource source,
            final GiDynamicSourceSnapshot dynamic
    ) {
        GiDirectSourceEpoch direct = source.epoch();
        if (!field.world().dimensionId().equals(direct.staticLightWorld().dimensionId())
                || field.worldGeneration() != direct.g2WorldGeneration()
                || field.resourceEpoch() != direct.g2ResourceEpoch()
                || field.materialEpoch() != direct.g2MaterialEpoch()
                || field.clipmapGeneration() != direct.g2ClipmapGeneration()
                || field.paletteGeneration() != direct.g2PaletteGeneration()
                || field.contentGeneration() != direct.g2ContentGeneration()
                || source.dynamicSourceEpoch() != dynamic.epoch().sourceEpoch()
                || source.dynamicSourceHash() != dynamic.sourceHash()) {
            return false;
        }
        for (int index = 0; index < 9; index++) {
            if (source.originComponent(index) != field.originComponent(index)) return false;
        }
        return true;
    }

    private static void requireDynamicWorld(
            final GiSemanticTransportFieldView field, final GiDynamicSourceSnapshot dynamic
    ) {
        if (!dynamic.epoch().world().dimensionId().equals(field.world().dimensionId())) {
            throw new IllegalArgumentException("G6 dynamic sources belong to another world");
        }
    }

    private static long positiveDigest(final long digest) {
        long positive = digest & Long.MAX_VALUE;
        return positive == 0L ? 1L : positive;
    }
    static boolean sourceEnvironmentIdentityReady(final long digest) {
        return digest != 0L;
    }
    private boolean canRetainDeferredHistory(
            final GiLiveUpdateClass updateClass,
            final GiSemanticTransportFieldView field,
            final long environmentDigest
    ) {
        return deferredHistoryCanBind(
                updateClass, this.observed, sameObservedStructuralRoot(field),
                compatibleEnvironmentSuccessor(field, environmentDigest)
        );
    }
    static boolean deferredHistoryCanBind(
            final GiLiveUpdateClass updateClass,
            final boolean observed,
            final boolean sameStructuralRoot,
            final boolean compatibleEnvironmentSuccessor
    ) {
        return observed && sameStructuralRoot && updateClass != null
                && (updateClass != GiLiveUpdateClass.FULL_RESET
                || compatibleEnvironmentSuccessor);
    }
    private boolean compatibleEnvironmentSuccessor(
            final GiSemanticTransportFieldView field,
            final long environmentDigest
    ) {
        return compatibleEnvironmentSuccessor(
                this.fullResetRootOpen, this.observed,
                sameObservedStructuralRoot(field), sameObservedOrigins(field),
                this.observedEnvironmentDigest, environmentDigest
        );
    }
    static boolean compatibleEnvironmentSuccessor(
            final boolean fullResetRootOpen,
            final boolean observed,
            final boolean sameStructuralRoot,
            final boolean sameOrigins,
            final long previousEnvironmentDigest,
            final long nextEnvironmentDigest
    ) {
        return !fullResetRootOpen && observed && sameStructuralRoot && sameOrigins
                && previousEnvironmentDigest != nextEnvironmentDigest;
    }
    static boolean retryWouldRepeatNonIdempotentScroll(
            final boolean preparedCascade, final boolean scrollCascade
    ) {
        return preparedCascade && scrollCascade;
    }
    private static long positiveOrOne(final long value) { return value > 0L ? value : 1L; }
    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G6 live coordinator is render-thread confined");
        }
    }
}
