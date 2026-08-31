package com.metallum.client.gi.source;

import com.metallum.client.gi.semantic.GiSemanticDirectFieldView;
import com.metallum.client.gi.semantic.GiSemanticFieldIdentityView;
import com.metallum.client.gi.semantic.GiSemanticWorldToken;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Bounded scheduler joining accepted G2 truth with physical L3/L4 sources. */
public final class GiDirectSourceCoordinator implements AutoCloseable {
    public static final int STATUS_NO_WORK = 0;
    public static final int STATUS_INPUT_NOT_READY = -7;
    public static final int STATUS_CREATE_FAILED = -8;
    public static final int STATUS_FROZEN_INPUT_DRIFT = -9;
    public static final int STATUS_FROZEN_PREPARATION_RESTARTED = -10;
    public static final int STATUS_ASYNC_COMPLETION_FAILED = -11;
    static final long STATIC_SOURCE_SETTLE_TICKS = 16L;
    static final long FROZEN_INPUT_SETTLE_FRAMES = 600L;
    static final long INTERACTIVE_FROZEN_INPUT_SETTLE_NANOS = 10_000_000_000L;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int MAX_ASYNC_COMPLETION_RETRIES = 3;

    enum ProductionRootRelation { SAME, ADVANCE, STALE }

    enum AcceptedBatchState {
        IDLE, IN_FLIGHT, COMPLETED_CURRENT, FAILED_CURRENT, STALE_COMPLETION,
        INVALID_COMPLETION
    }

    /** Fixed-storage proof retained from native admission until asynchronous completion. */
    static final class AcceptedBatch {
        private final int[] brickIds = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        private final long[] brickStamps = new long[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        private final long[] sourceKeys = new long[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        @Nullable private GiDirectSourceEpoch epoch;
        private int count;
        private long sourceTick;
        private long commandBufferAddress;
        private long fenceAddress;
        private long batchesBaseline;
        private long rejectedBaseline;

        void admit(
                final GiDirectSourceEpoch acceptedEpoch,
                final int[] acceptedBrickIds,
                final int acceptedCount,
                final long[] desiredBrickStamps,
                final long[] desiredSourceKeys,
                final long acceptedSourceTick,
                final long acceptedCommandBufferAddress,
                final long acceptedFenceAddress,
                final long acceptedBatchesBaseline,
                final long acceptedRejectedBaseline
        ) {
            Objects.requireNonNull(acceptedEpoch, "acceptedEpoch");
            Objects.requireNonNull(acceptedBrickIds, "acceptedBrickIds");
            Objects.requireNonNull(desiredBrickStamps, "desiredBrickStamps");
            Objects.requireNonNull(desiredSourceKeys, "desiredSourceKeys");
            if (this.epoch != null || acceptedCount <= 0
                    || acceptedCount > this.brickIds.length
                    || acceptedCount > acceptedBrickIds.length
                    || desiredBrickStamps.length != GiDirectSourceLayout.TOTAL_BRICKS
                    || desiredSourceKeys.length != GiDirectSourceLayout.TOTAL_BRICKS
                    || acceptedSourceTick < 0L || acceptedCommandBufferAddress == 0L
                    || acceptedFenceAddress == 0L || acceptedBatchesBaseline < 0L
                    || acceptedRejectedBaseline < 0L) {
                throw new IllegalArgumentException("Invalid G3 accepted-batch proof");
            }
            this.epoch = acceptedEpoch;
            this.count = acceptedCount;
            this.sourceTick = acceptedSourceTick;
            this.commandBufferAddress = acceptedCommandBufferAddress;
            this.fenceAddress = acceptedFenceAddress;
            this.batchesBaseline = acceptedBatchesBaseline;
            this.rejectedBaseline = acceptedRejectedBaseline;
            for (int index = 0; index < acceptedCount; index++) {
                int brickId = acceptedBrickIds[index];
                GiDirectSourceLayout.validateBrickId(brickId);
                this.brickIds[index] = brickId;
                this.brickStamps[index] = desiredBrickStamps[brickId];
                this.sourceKeys[index] = desiredSourceKeys[brickId];
            }
        }

        AcceptedBatchState classify(
                final @Nullable GiDirectSourceEpoch activeEpoch,
                final int nativeStatus
        ) {
            if (this.epoch == null) return AcceptedBatchState.IDLE;
            if (nativeStatus == GiDirectSourceGpuResources.STATUS_BUSY) {
                return AcceptedBatchState.IN_FLIGHT;
            }
            if (nativeStatus == GiDirectSourceGpuResources.STATUS_OK) {
                return this.epoch.equals(activeEpoch)
                        ? AcceptedBatchState.COMPLETED_CURRENT
                        : AcceptedBatchState.STALE_COMPLETION;
            }
            if (nativeStatus == GiDirectSourceGpuResources.STATUS_REJECTED) {
                return this.epoch.equals(activeEpoch)
                        ? AcceptedBatchState.FAILED_CURRENT
                        : AcceptedBatchState.STALE_COMPLETION;
            }
            return AcceptedBatchState.INVALID_COMPLETION;
        }

        void publishTo(final long[] submittedBrickStamps, final long[] submittedSourceKeys) {
            if (this.epoch == null
                    || submittedBrickStamps.length != GiDirectSourceLayout.TOTAL_BRICKS
                    || submittedSourceKeys.length != GiDirectSourceLayout.TOTAL_BRICKS) {
                throw new IllegalStateException("G3 accepted batch cannot be published");
            }
            for (int index = 0; index < this.count; index++) {
                int brickId = this.brickIds[index];
                submittedBrickStamps[brickId] = this.brickStamps[index];
                submittedSourceKeys[brickId] = this.sourceKeys[index];
            }
        }

        boolean pending() { return this.epoch != null; }
        GiDirectSourceEpoch epoch() { return Objects.requireNonNull(this.epoch, "epoch"); }
        int[] brickIds() { return this.brickIds; }
        int count() { return this.count; }
        long sourceTick() { return this.sourceTick; }
        long commandBufferAddress() { return this.commandBufferAddress; }
        long fenceAddress() { return this.fenceAddress; }
        long batchesBaseline() { return this.batchesBaseline; }
        long rejectedBaseline() { return this.rejectedBaseline; }

        boolean matchesSubmission(
                final long expectedCommandBufferAddress,
                final long expectedFenceAddress,
                final long expectedSourceTick
        ) {
            return this.epoch != null
                    && expectedCommandBufferAddress != 0L
                    && expectedFenceAddress != 0L
                    && this.commandBufferAddress == expectedCommandBufferAddress
                    && this.fenceAddress == expectedFenceAddress
                    && this.sourceTick == expectedSourceTick;
        }

        boolean closesEveryGapInCascade(
                final int cascade,
                final long[] submittedBrickStamps,
                final long[] submittedSourceKeys,
                final long[] desiredBrickStamps,
                final long[] desiredSourceKeys
        ) {
            if (cascade < 0 || cascade >= GiDirectSourceLayout.CASCADE_COUNT
                    || submittedBrickStamps.length != GiDirectSourceLayout.TOTAL_BRICKS
                    || submittedSourceKeys.length != GiDirectSourceLayout.TOTAL_BRICKS
                    || desiredBrickStamps.length != GiDirectSourceLayout.TOTAL_BRICKS
                    || desiredSourceKeys.length != GiDirectSourceLayout.TOTAL_BRICKS) {
                throw new IllegalArgumentException("Invalid G3 projected-completion proof");
            }
            int start = cascade * GiDirectSourceLayout.BRICKS_PER_CASCADE;
            int end = start + GiDirectSourceLayout.BRICKS_PER_CASCADE;
            for (int brick = start; brick < end; brick++) {
                if (submittedBrickStamps[brick] == desiredBrickStamps[brick]
                        && submittedSourceKeys[brick] == desiredSourceKeys[brick]) {
                    continue;
                }
                boolean covered = false;
                for (int index = 0; index < this.count; index++) {
                    if (this.brickIds[index] == brick
                            && this.brickStamps[index] == desiredBrickStamps[brick]
                            && this.sourceKeys[index] == desiredSourceKeys[brick]) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) return false;
            }
            return true;
        }

        void clear() {
            this.epoch = null;
            this.count = 0;
            this.sourceTick = 0L;
            this.commandBufferAddress = 0L;
            this.fenceAddress = 0L;
            this.batchesBaseline = 0L;
            this.rejectedBaseline = 0L;
        }
    }

    /** Public, handle-free identity used to compare an accepted G3 source with G2 truth. */
    public record TransportSourceIdentity(
            GiDirectSourceEpoch epoch,
            long sourceStamp,
            int nearOriginX,
            int nearOriginY,
            int nearOriginZ
    ) {
        public TransportSourceIdentity {
            Objects.requireNonNull(epoch, "epoch");
            if (sourceStamp == 0L || sourceStamp != transportSourceStamp(
                    epoch, nearOriginX, nearOriginY, nearOriginZ)) {
                throw new IllegalArgumentException("Invalid frozen G3 transport-source identity");
            }
        }
    }

    /**
     * Unforgeable, fully converged G3 capability admitted to exactly one frozen G4 build.
     * The raw native owner can only enter this object through {@link #transportSource()}.
     */
    public static final class TransportSource {
        private final MemorySegment directContext;
        private final TransportSourceIdentity identity;

        private TransportSource(
                final MemorySegment directContext,
                final TransportSourceIdentity identity
        ) {
            this.directContext = Objects.requireNonNull(directContext, "directContext");
            this.identity = Objects.requireNonNull(identity, "identity");
            if (directContext.address() == 0L) {
                throw new IllegalArgumentException("Invalid frozen G3 native capability");
            }
        }

        public MemorySegment directContext() { return this.directContext; }
        public TransportSourceIdentity identity() { return this.identity; }
        public GiDirectSourceEpoch epoch() { return this.identity.epoch(); }
        public long sourceStamp() { return this.identity.sourceStamp(); }
        public int nearOriginX() { return this.identity.nearOriginX(); }
        public int nearOriginY() { return this.identity.nearOriginY(); }
        public int nearOriginZ() { return this.identity.nearOriginZ(); }
    }

    /** Unforgeable per-cascade capability used only by the G6 live owner. */
    public static final class LiveSource {
        private final GiDirectSourceEpoch epoch;
        private final long dynamicSourceEpoch;
        private final long dynamicSourceHash;
        private final long sourceStamp;
        /** Dirt not yet transferred into a durable G6 plan. */
        private final long affectedBrickMask;
        /** Audit truth retained until a current fully-exact G6 cascade acknowledges it. */
        private final long cumulativeAffectedBrickMask;
        private final int cascade;
        private final int[] origins;

        private LiveSource(
                final GiDirectSourceEpoch epoch,
                final long dynamicSourceEpoch,
                final long dynamicSourceHash,
                final long sourceStamp,
                final long affectedBrickMask,
                final long cumulativeAffectedBrickMask,
                final int cascade,
                final int[] origins
        ) {
            this.epoch = Objects.requireNonNull(epoch, "epoch");
            this.dynamicSourceEpoch = dynamicSourceEpoch;
            this.dynamicSourceHash = dynamicSourceHash;
            this.sourceStamp = sourceStamp;
            this.affectedBrickMask = affectedBrickMask;
            this.cumulativeAffectedBrickMask = cumulativeAffectedBrickMask;
            this.cascade = cascade;
            this.origins = origins.clone();
            if (dynamicSourceEpoch <= 0L || sourceStamp == 0L
                    || cascade < 0 || cascade >= GiDirectSourceLayout.CASCADE_COUNT
                    || origins.length != 9 || sourceStamp != liveTransportSourceStamp(
                            epoch, dynamicSourceEpoch, dynamicSourceHash, cascade, origins
                    )) {
                throw new IllegalArgumentException("Invalid G6 live G3 capability");
            }
        }

        public GiDirectSourceEpoch epoch() { return this.epoch; }
        public long dynamicSourceEpoch() { return this.dynamicSourceEpoch; }
        public long dynamicSourceHash() { return this.dynamicSourceHash; }
        public long sourceStamp() { return this.sourceStamp; }
        /** Local 4x4x4 brick bits which G6 has not yet accepted into a durable plan. */
        public long affectedBrickMask() { return this.affectedBrickMask; }
        /** Local bits changed since this cascade's last fully-exact acknowledgement. */
        public long cumulativeAffectedBrickMask() { return this.cumulativeAffectedBrickMask; }
        public int cascade() { return this.cascade; }
        public int originComponent(final int index) { return this.origins[index]; }
    }

    /**
     * Unforgeable admission-time capability for attaching truthful G4 resource telemetry.
     * It does not contain a frozen source identity and therefore cannot authorize transport.
     */
    public static final class TelemetrySource {
        private final MemorySegment directContext;

        private TelemetrySource(final MemorySegment directContext) {
            this.directContext = Objects.requireNonNull(directContext, "directContext");
            if (directContext.address() == 0L) {
                throw new IllegalArgumentException("Invalid G3 telemetry capability");
            }
        }

        /** Attaches resource telemetry without revealing the G3 owner handle. */
        public int attachTransportTelemetry(final MemorySegment transportContext) {
            return MetalNativeBridge.metallum_gi_transport_attach_telemetry_v1(
                    Objects.requireNonNull(transportContext, "transportContext"),
                    this.directContext
            );
        }
    }

    /** Admission-time owner capability for creating the attached G6 native context. */
    public static final class LiveOwner {
        private final MemorySegment directContext;

        private LiveOwner(final MemorySegment directContext) {
            this.directContext = Objects.requireNonNull(directContext, "directContext");
            if (directContext.address() == 0L) {
                throw new IllegalArgumentException("Invalid G3 live-owner capability");
            }
        }

        public MemorySegment createContext(
                final MemorySegment device,
                final MemorySegment commandQueue
        ) {
            return MetalNativeBridge.metallum_gi_live_create_context_v1(
                    Objects.requireNonNull(device, "device"),
                    Objects.requireNonNull(commandQueue, "commandQueue"),
                    this.directContext,
                    1L
            );
        }
    }

    private final Thread ownerThread = Thread.currentThread();
    private final boolean frozenTransportRequested;
    private final boolean interactiveFrozenPreparationRetry;
    private final int[] drainedBricks = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
    private final long[] completionBaselines = new long[2];
    private final AcceptedBatch acceptedBatch = new AcceptedBatch();
    private final AdvancedLight[] sourceScratch =
            new AdvancedLight[GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK];
    private final long[] submittedBrickStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
    private final long[] desiredBrickStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
    private final long[] submittedSourceKeys = new long[GiDirectSourceLayout.TOTAL_BRICKS];
    private final long[] desiredSourceKeys = new long[GiDirectSourceLayout.TOTAL_BRICKS];
    private final long[] affectedBrickMasks = new long[GiDirectSourceLayout.CASCADE_COUNT];
    /**
     * CPU truth relative to the last fully-exact G6 acknowledgement, not merely the latest
     * logical G3 epoch. A later metadata/source rotation must not forget a brick that G3 already
     * rewrote while G6 still retained an older atlas basis.
     */
    private final long[] unacknowledgedAffectedBrickMasks =
            new long[GiDirectSourceLayout.CASCADE_COUNT];
    /**
     * Ownership-transfer accumulator. Unlike the audit accumulator above, bits leave this mask
     * as soon as an exact current G6 plan has synchronously accepted them. The G6 exact/required
     * masks then own unfinished work across later publication epochs; a later G3 change re-marks
     * the bit. Keeping the two lifetimes separate prevents a completed eight-brick child from
     * being invalidated again by an old cumulative FULL_RESET mask.
     */
    private final long[] untransferredAffectedBrickMasks =
            new long[GiDirectSourceLayout.CASCADE_COUNT];
    private final int[] activeOrigins = new int[9];
    private final int[] nextOrigins = new int[9];
    private final LiveSource[] cachedLiveSources =
            new LiveSource[GiDirectSourceLayout.CASCADE_COUNT];
    private final int[] observedFrozenOrigins = new int[9];
    private GiDirectDirtyQueue dirtyQueue = new GiDirectDirtyQueue();
    @Nullable private GiDirectSourceGpuResources resources;
    @Nullable private GiDirectSourceEpoch activeEpoch;
    @Nullable private GiEnvironmentSource environment;
    @Nullable private GiStaticSourceState staticState;
    @Nullable private GiStaticSourceState observedStaticState;
    @Nullable private GiSemanticWorldToken observedFrozenWorld;
    @Nullable private TelemetrySource telemetrySource;
    @Nullable private LiveOwner liveOwner;
    @Nullable private TransportSource cachedTransportSource;
    @Nullable private GiDynamicSourceSnapshot activeDynamicSources;
    private long observedStaticSinceTick;
    private long observedFrozenClipmapGeneration;
    private long observedFrozenPaletteGeneration;
    private long observedFrozenContentGeneration;
    private long observedFrozenEnvironmentDigest;
    private long observedFrozenSinceNanos;
    private long frozenTupleChangeCount;
    private boolean frozenPreparationCommitted;
    private String frozenTupleChangeReason = "unobserved";
    private String frozenPreparationRestartReason = "none";
    private long logicalStaticSourceEpoch = 1L;
    private long logicalEnvironmentEpoch = 1L;
    private int activeNearOriginX;
    private int activeNearOriginY;
    private int activeNearOriginZ;
    private long activeSourceKey;
    private long activeDynamicSourceEpoch;
    private long activeDynamicSourceHash;
    private long affectedMaskStaticRegistryEpoch;
    private long affectedMaskEnvironmentDigest;
    private long liveDrainTick = Long.MIN_VALUE;
    private boolean liveDrainConsumed;
    private boolean metadataRelabelPending;
    private boolean liveSourceObservationBlocked;
    private int consecutiveAsyncCompletionFailures;

    /** Precreates the fixed G3 field, staging ring and PSOs outside frame submission. */
    public GiDirectSourceCoordinator(
            final MemorySegment device,
            final MemorySegment commandQueue,
            final boolean frozenTransportRequested,
            final boolean interactiveFrozenPreparationRetry,
            final Consumer<MemorySegment> deferredRelease
    ) {
        this.frozenTransportRequested = frozenTransportRequested;
        this.interactiveFrozenPreparationRetry = interactiveFrozenPreparationRetry;
        this.resources = GiDirectSourceGpuResources.create(
                device, commandQueue, 1L,
                Objects.requireNonNull(deferredRelease, "deferredRelease")
        );
        if (this.resources == null) {
            throw new IllegalStateException("Failed to precreate G3 direct-source resources");
        }
        this.telemetrySource = new TelemetrySource(this.resources.transportContextHandle());
        this.liveOwner = new LiveOwner(this.resources.transportContextHandle());
    }

    public int encodeFrame(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final GiSemanticDirectFieldView field,
            final EnvironmentDescriptor environmentDescriptor,
            final AdvancedLightRegistry registry,
            final long tick
    ) {
        return encodeFrame(
                commandBuffer, fence, field, environmentDescriptor, registry, null, tick
        );
    }

    /** Live G6 overload; the legacy overload above remains the exact frozen/debug path. */
    public int encodeFrame(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final GiSemanticDirectFieldView field,
            final EnvironmentDescriptor environmentDescriptor,
            final AdvancedLightRegistry registry,
            final @Nullable GiDynamicSourceSnapshot dynamicSources,
            final long tick
    ) {
        assertOwnerThread();
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(environmentDescriptor, "environmentDescriptor");
        Objects.requireNonNull(registry, "registry");
        int retirementStatus = retireAcceptedBatch();
        if (retirementStatus != GiDirectSourceGpuResources.STATUS_OK) {
            return retirementStatus;
        }
        if (this.frozenTransportRequested && dynamicSources != null) {
            throw new IllegalArgumentException(
                    "Frozen/debug G3 cannot admit G6 dynamic sources"
            );
        }

        GiSemanticWorldToken world = field.world();
        long frozenNowNanos = this.interactiveFrozenPreparationRetry
                ? System.nanoTime() : 0L;
        boolean preparationRestarted = false;
        if (this.frozenTransportRequested && this.activeEpoch != null
                && this.activeEpoch.g2WorldGeneration() != world.worldGeneration()) {
            if (this.frozenPreparationCommitted) {
                if (!this.interactiveFrozenPreparationRetry) {
                    return STATUS_FROZEN_INPUT_DRIFT;
                }
                restartFrozenPreparation("world_generation");
                preparationRestarted = true;
            }
        }
        GiStaticSourceState nextStatic = this.observedStaticState != null
                && this.observedStaticState.world().dimensionId().equals(world.dimensionId())
                && registry.staticSourceIdentityMatchesForGi(
                this.observedStaticState.world(), this.observedStaticState.registryEpoch()
        ) ? this.observedStaticState : registry.staticSourceStateForGi(world.dimensionId());
        if (nextStatic == null) {
            this.liveSourceObservationBlocked = true;
            this.frozenTupleChangeReason = "static_sources_unavailable";
            return STATUS_INPUT_NOT_READY;
        }
        if (dynamicSources != null
                && !dynamicSources.epoch().world().equals(nextStatic.world())) {
            // A resource reload may rotate L3 between the allocation-free lookup and this
            // synchronized registry observation. Keep the prior owner intact for an exact retry,
            // but expose no LiveSource until the matching immutable dynamic publication arrives.
            this.liveSourceObservationBlocked = true;
            this.frozenTupleChangeReason = "dynamic_sources_waiting_for_l3_world";
            return STATUS_INPUT_NOT_READY;
        }
        ProductionRootRelation rootRelation = productionRootRelation(
                this.activeEpoch, world, nextStatic.world()
        );
        if (!this.frozenTransportRequested
                && rootRelation == ProductionRootRelation.STALE) {
            this.liveSourceObservationBlocked = true;
            this.frozenTupleChangeReason = "production_root_regressed";
            return STATUS_INPUT_NOT_READY;
        }
        validateDynamicSourceWorld(world, nextStatic.world(), dynamicSources);
        if (!this.frozenTransportRequested
                && rootRelation == ProductionRootRelation.ADVANCE) {
            // Resource reload may recreate G2/L3 child owners while their outer semantic world
            // generation stays numerically equal. Those fresh clipmap/palette/content counters
            // are allowed to restart only through this explicit full-volume reset path.
            resetActiveSource();
        }
        this.liveSourceObservationBlocked = false;
        long desiredEnvironmentDigest = GiEnvironmentSource.quantizedDigest(
                environmentDescriptor
        );
        if (this.frozenTransportRequested) {
            boolean tupleMatches = frozenTupleMatches(
                    field, world, nextStatic, desiredEnvironmentDigest
            );
            if (!tupleMatches) {
                String driftReason = this.observedFrozenWorld == null
                        ? "initial" : frozenTupleDriftReason(
                                field, world, nextStatic, desiredEnvironmentDigest
                        );
                if (this.frozenPreparationCommitted) {
                    if (!this.interactiveFrozenPreparationRetry) {
                        return STATUS_FROZEN_INPUT_DRIFT;
                    }
                    restartCommittedPreparation(driftReason);
                    preparationRestarted = true;
                }
                captureFrozenTuple(
                        field, world, nextStatic, desiredEnvironmentDigest,
                        tick, frozenNowNanos, driftReason,
                        this.observedFrozenWorld == null || isStructuralDrift(driftReason)
                );
                if (preparationRestarted && !isStructuralDrift(driftReason)) {
                    // Ordinary Sodium can finish late section uploads while origins stay fixed.
                    // Retry the short bounded G3 population immediately; do not charge another
                    // ten-second origin-stability interval for content-only convergence.
                    this.frozenPreparationCommitted = true;
                }
            }
            if (preparationRestarted) {
                return STATUS_FROZEN_PREPARATION_RESTARTED;
            }
            if (!this.frozenPreparationCommitted) {
                if (!frozenInputSettled(
                        this.interactiveFrozenPreparationRetry,
                        this.observedStaticSinceTick, tick,
                        this.observedFrozenSinceNanos, frozenNowNanos
                )) {
                    return STATUS_INPUT_NOT_READY;
                }
                this.frozenPreparationCommitted = true;
            }
        } else {
            if (!nextStatic.equals(this.observedStaticState)) {
                this.observedStaticState = nextStatic;
                this.observedStaticSinceTick = tick;
            }
            boolean staticSourceChanged = !nextStatic.equals(this.staticState);
            if (dynamicSources == null && staticSourceChanged
                    && tick - this.observedStaticSinceTick < STATIC_SOURCE_SETTLE_TICKS) {
                // Never mix queries from a changing registry into one source epoch.
                return STATUS_INPUT_NOT_READY;
            }
        }

        boolean staticSourceChanged = !nextStatic.equals(this.staticState);
        GiDynamicSourceSnapshot previousDynamicSources = this.activeDynamicSources;
        boolean dynamicSourceChanged = dynamicSourceIdentityChanged(
                this.activeDynamicSourceEpoch, this.activeDynamicSourceHash, dynamicSources
        );
        GiEnvironmentSource desiredEnvironment = this.environment != null
                && desiredEnvironmentDigest == this.environment.quantizedDigest()
                ? this.environment
                : GiEnvironmentSource.fromDescriptor(1L, environmentDescriptor);

        boolean newContext = this.activeEpoch == null;
        if (newContext) {
            if (this.resources == null) {
                return STATUS_CREATE_FAILED;
            }
            this.dirtyQueue = new GiDirectDirtyQueue();
            this.liveDrainTick = Long.MIN_VALUE;
            this.liveDrainConsumed = false;
            this.metadataRelabelPending = false;
            Arrays.fill(this.submittedBrickStamps, 0L);
            Arrays.fill(this.desiredBrickStamps, 0L);
            Arrays.fill(this.submittedSourceKeys, 0L);
            Arrays.fill(this.desiredSourceKeys, 0L);
            Arrays.fill(this.affectedBrickMasks, 0L);
            Arrays.fill(this.unacknowledgedAffectedBrickMasks, 0L);
            Arrays.fill(this.untransferredAffectedBrickMasks, 0L);
            this.logicalStaticSourceEpoch = 1L;
            this.logicalEnvironmentEpoch = 1L;
            this.staticState = nextStatic;
            adoptDynamicSources(dynamicSources);
            this.environment = desiredEnvironment.withEpoch(this.logicalEnvironmentEpoch);
            this.activeEpoch = null;
        } else {
            if (staticSourceChanged) {
                this.staticState = nextStatic;
            }
            if (staticSourceChanged || dynamicSourceChanged) {
                this.logicalStaticSourceEpoch = Math.incrementExact(this.logicalStaticSourceEpoch);
            }
            adoptDynamicSources(dynamicSources);
            // Finish the current bounded generation before admitting another sun/sky sample.
            // The next sample is the latest desired value, so intermediate changes coalesce.
            if (this.dirtyQueue.pendingCount() == 0
                    && this.environment != null
                    && desiredEnvironment.quantizedDigest() != this.environment.quantizedDigest()) {
                this.logicalEnvironmentEpoch = Math.incrementExact(this.logicalEnvironmentEpoch);
                this.environment = desiredEnvironment.withEpoch(this.logicalEnvironmentEpoch);
            }
        }
        if (this.environment == null || this.staticState == null || this.resources == null) {
            return STATUS_INPUT_NOT_READY;
        }

        GiDirectSourceEpoch nextEpoch = matchesEpoch(
                this.activeEpoch, world, field, this.staticState,
                this.logicalStaticSourceEpoch, this.logicalEnvironmentEpoch
        ) ? this.activeEpoch : new GiDirectSourceEpoch(
                world.worldGeneration(), world.resourceEpoch(), world.materialEpoch(),
                field.clipmapGeneration(), field.paletteGeneration(), field.contentGeneration(),
                this.staticState.world(), this.logicalStaticSourceEpoch,
                this.logicalEnvironmentEpoch
        );
        if (newContext) {
            int resetStatus = this.resources.reset(nextEpoch);
            if (resetStatus != GiDirectSourceGpuResources.STATUS_OK) {
                return resetStatus;
            }
        }
        boolean epochChanged = !nextEpoch.equals(this.activeEpoch);
        if (epochChanged) {
            GiDirectSourceEpoch previousEpoch = this.activeEpoch;
            GiDirectSourceEpoch queuedEpoch = this.dirtyQueue.activeEpoch();
            if (!Objects.equals(previousEpoch, queuedEpoch)) {
                throw new IllegalStateException(
                        "G3 coordinator/queue epoch invariant failed; active="
                                + previousEpoch + ", queued=" + queuedEpoch
                                + ", next=" + nextEpoch
                );
            }
            long previousEnvironmentDigest = this.affectedMaskEnvironmentDigest;
            this.cachedTransportSource = null;
            Arrays.fill(this.cachedLiveSources, null);
            boolean structuralReset = previousEpoch == null
                    || nextEpoch.g2WorldGeneration() != previousEpoch.g2WorldGeneration()
                    || nextEpoch.g2ResourceEpoch() != previousEpoch.g2ResourceEpoch()
                    || nextEpoch.g2MaterialEpoch() != previousEpoch.g2MaterialEpoch()
                    || nextEpoch.g2PaletteGeneration() != previousEpoch.g2PaletteGeneration();
            for (int index = 0; index < this.nextOrigins.length; index++) {
                this.nextOrigins[index] = field.originComponent(index);
            }
            boolean originsChanged = !Arrays.equals(this.activeOrigins, this.nextOrigins);
            boolean environmentChanged = previousEpoch == null
                    || nextEpoch.environmentEpoch() != previousEpoch.environmentEpoch();
            captureDesiredBrickInputs(
                    field, registry,
                    structuralReset || staticSourceChanged || environmentChanged || originsChanged,
                    dynamicSourceChanged ? previousDynamicSources : null,
                    dynamicSourceChanged ? this.activeOrigins : null,
                    dynamicSourceChanged ? this.activeDynamicSources : null,
                    dynamicSourceChanged ? this.nextOrigins : null
            );
            if (!registry.staticSourceIdentityMatchesForGi(
                    this.staticState.world(), this.staticState.registryEpoch())) {
                this.observedStaticState = null;
                return STATUS_INPUT_NOT_READY;
            }
            boolean metadataOnlyRollover = dynamicSources != null
                    && previousEpoch != null
                    && metadataOnlyRolloverCompatible(
                    previousEpoch, nextEpoch,
                    this.activeOrigins, this.nextOrigins,
                    previousEnvironmentDigest, this.environment.quantizedDigest(),
                    this.submittedBrickStamps, this.desiredBrickStamps,
                    this.submittedSourceKeys, this.desiredSourceKeys
            );
            this.dirtyQueue.rotateEpoch(nextEpoch);
            this.activeEpoch = nextEpoch;
            this.consecutiveAsyncCompletionFailures = 0;
            this.activeNearOriginX = this.nextOrigins[0];
            this.activeNearOriginY = this.nextOrigins[1];
            this.activeNearOriginZ = this.nextOrigins[2];
            this.activeSourceKey = liveSourceKey(
                    nextEpoch, this.activeDynamicSourceEpoch,
                    this.activeDynamicSourceHash, this.nextOrigins
            );
            this.affectedMaskStaticRegistryEpoch = this.staticState.registryEpoch();
            this.affectedMaskEnvironmentDigest = this.environment.quantizedDigest();
            Arrays.fill(this.affectedBrickMasks, 0L);
            if (structuralReset) {
                this.dirtyQueue.enqueueAll(nextEpoch, tick);
                Arrays.fill(this.affectedBrickMasks, -1L);
                Arrays.fill(this.unacknowledgedAffectedBrickMasks, -1L);
                Arrays.fill(this.untransferredAffectedBrickMasks, -1L);
            } else {
                boolean originOnlyRelocation = originOnlyFieldRelocation(
                        previousEpoch, nextEpoch, originsChanged,
                        staticSourceChanged, dynamicSourceChanged, environmentChanged
                );
                enqueueRotatedBricks(nextEpoch, tick, !originOnlyRelocation);
            }
            this.metadataRelabelPending = metadataOnlyRollover
                    && this.dirtyQueue.pendingCount() == 0;
            System.arraycopy(
                    this.nextOrigins, 0,
                    this.activeOrigins, 0,
                    this.activeOrigins.length
            );
        } else {
            for (int index = 0; index < this.activeOrigins.length; index++) {
                if (this.activeOrigins[index] != field.originComponent(index)) {
                    throw new IllegalStateException(
                            "G3 cascade origin changed without a clipmap epoch"
                    );
                }
            }
            enqueueChangedBricks(field, nextEpoch, tick);
        }

        if (dynamicSources != null) {
            if (this.liveDrainTick != tick) {
                this.liveDrainTick = tick;
                this.liveDrainConsumed = false;
            } else if (this.liveDrainConsumed) {
                int telemetryStatus = this.resources.publishScheduler(this.dirtyQueue);
                return telemetryStatus == GiDirectSourceGpuResources.STATUS_OK
                        ? STATUS_NO_WORK : telemetryStatus;
            }
        }
        int count = this.dirtyQueue.drainTo(nextEpoch, tick, this.drainedBricks);
        if (count == 0) {
            int telemetryStatus = this.resources.publishScheduler(this.dirtyQueue);
            if (telemetryStatus != GiDirectSourceGpuResources.STATUS_OK) {
                return telemetryStatus;
            }
            if (!this.metadataRelabelPending) {
                return STATUS_NO_WORK;
            }
            if (this.resources.fieldMatches(nextEpoch)) {
                this.metadataRelabelPending = false;
                return STATUS_NO_WORK;
            }
            int relabelStatus = this.resources.relabelMetadataOnly(
                    nextEpoch, this.environment, this.activeOrigins
            );
            if (relabelStatus == GiDirectSourceGpuResources.STATUS_OK) {
                this.metadataRelabelPending = false;
                return STATUS_NO_WORK;
            }
            return relabelStatus;
        }
        if (dynamicSources != null) {
            this.liveDrainConsumed = true;
        }
        GiDirectSourceGpuResources.PreparedBatch batch;
        try {
            batch = this.resources.prepare(
                    nextEpoch, field, this.environment, registry, this.activeDynamicSources,
                    this.drainedBricks, count, this.sourceScratch);
        } catch (RuntimeException failure) {
            this.dirtyQueue.retryBatch(nextEpoch, this.drainedBricks, count, tick);
            throw failure;
        }
        if (!registry.staticSourceIdentityMatchesForGi(
                this.staticState.world(), this.staticState.registryEpoch())) {
            this.dirtyQueue.retryBatch(nextEpoch, this.drainedBricks, count, tick);
            this.observedStaticState = null;
            return STATUS_INPUT_NOT_READY;
        }
        this.resources.captureCompletionBaselines(this.completionBaselines);
        int status = this.resources.encode(commandBuffer, fence, batch);
        if (status == GiDirectSourceGpuResources.STATUS_OK) {
            this.acceptedBatch.admit(
                    nextEpoch, this.drainedBricks, count,
                    this.desiredBrickStamps, this.desiredSourceKeys, tick,
                    commandBuffer.address(), fence.address(),
                    this.completionBaselines[0], this.completionBaselines[1]
            );
        } else {
            this.dirtyQueue.retryBatch(nextEpoch, this.drainedBricks, count, tick);
        }
        int telemetryStatus = this.resources.publishScheduler(this.dirtyQueue);
        return telemetryStatus == GiDirectSourceGpuResources.STATUS_OK ? status : telemetryStatus;
    }

    /** Retires one accepted asynchronous write before any newer input identity can be observed. */
    private int retireAcceptedBatch() {
        if (!this.acceptedBatch.pending()) {
            return GiDirectSourceGpuResources.STATUS_OK;
        }
        GiDirectSourceGpuResources currentResources = this.resources;
        if (currentResources == null) {
            return STATUS_ASYNC_COMPLETION_FAILED;
        }
        int nativeStatus = currentResources.pollAcceptedBatchCompletion(
                this.acceptedBatch.epoch(),
                this.acceptedBatch.batchesBaseline(), this.acceptedBatch.rejectedBaseline()
        );
        AcceptedBatchState state = this.acceptedBatch.classify(this.activeEpoch, nativeStatus);
        if (state == AcceptedBatchState.IN_FLIGHT) {
            return GiDirectSourceGpuResources.STATUS_BUSY;
        }
        if (state == AcceptedBatchState.STALE_COMPLETION) {
            // Rotation already discarded the old queue ownership. Never copy its stamps/keys
            // into the current identity and never let its success counters publish readiness.
            this.acceptedBatch.clear();
            this.consecutiveAsyncCompletionFailures = 0;
            return GiDirectSourceGpuResources.STATUS_OK;
        }
        if (state == AcceptedBatchState.COMPLETED_CURRENT) {
            GiDirectDirtyQueue.CompletionResult completed = this.dirtyQueue.completeBatch(
                    this.acceptedBatch.epoch(), this.acceptedBatch.brickIds(),
                    this.acceptedBatch.count()
            );
            if (completed != GiDirectDirtyQueue.CompletionResult.COMPLETED) {
                this.acceptedBatch.clear();
                return STATUS_ASYNC_COMPLETION_FAILED;
            }
            this.acceptedBatch.publishTo(
                    this.submittedBrickStamps, this.submittedSourceKeys
            );
            this.acceptedBatch.clear();
            this.consecutiveAsyncCompletionFailures = 0;
            return currentResources.publishScheduler(this.dirtyQueue);
        }
        if (state == AcceptedBatchState.FAILED_CURRENT) {
            GiDirectDirtyQueue.CompletionResult retried = this.dirtyQueue.retryBatch(
                    this.acceptedBatch.epoch(), this.acceptedBatch.brickIds(),
                    this.acceptedBatch.count(), this.acceptedBatch.sourceTick()
            );
            this.acceptedBatch.clear();
            if (retried != GiDirectDirtyQueue.CompletionResult.COMPLETED) {
                return STATUS_ASYNC_COMPLETION_FAILED;
            }
            this.consecutiveAsyncCompletionFailures = Math.incrementExact(
                    this.consecutiveAsyncCompletionFailures
            );
            int telemetryStatus = currentResources.publishScheduler(this.dirtyQueue);
            if (telemetryStatus != GiDirectSourceGpuResources.STATUS_OK) {
                return telemetryStatus;
            }
            return this.consecutiveAsyncCompletionFailures >= MAX_ASYNC_COMPLETION_RETRIES
                    ? STATUS_ASYNC_COMPLETION_FAILED
                    : GiDirectSourceGpuResources.STATUS_OK;
        }
        this.acceptedBatch.clear();
        return STATUS_ASYNC_COMPLETION_FAILED;
    }

    public GiDirectDirtyQueue.Telemetry queueTelemetry() {
        assertOwnerThread();
        return this.dirtyQueue.telemetry();
    }

    public String frozenPreparationRestartReason() {
        assertOwnerThread();
        return this.frozenPreparationRestartReason;
    }

    /** Low-frequency diagnostic snapshot; callers must not request it in every render frame. */
    public FrozenPreparationDebugState frozenPreparationDebugState(final long nowNanos) {
        assertOwnerThread();
        long settledMillis = this.observedFrozenSinceNanos == 0L
                ? 0L : Math.max(0L, nowNanos - this.observedFrozenSinceNanos) / 1_000_000L;
        return new FrozenPreparationDebugState(
                this.observedFrozenWorld != null,
                this.frozenPreparationCommitted,
                this.frozenTupleChangeReason,
                this.frozenTupleChangeCount,
                settledMillis,
                this.observedFrozenClipmapGeneration,
                this.observedFrozenPaletteGeneration,
                this.observedFrozenContentGeneration
        );
    }

    public record FrozenPreparationDebugState(
            boolean tupleObserved,
            boolean committed,
            String changeReason,
            long changeCount,
            long settledMillis,
            long clipmapGeneration,
            long paletteGeneration,
            long contentGeneration
    ) {
    }

    public GiDirectSourceGpuResources.@Nullable Stats nativeStats() {
        assertOwnerThread();
        return this.resources == null ? null : this.resources.stats();
    }

    /**
     * Returns G3's opaque private-field owner only after the single 192-brick population is
     * complete and the native epoch/origin agrees with the render-thread scheduler.
     */
    public @Nullable TransportSource transportSource() {
        assertOwnerThread();
        if (this.resources == null || this.activeEpoch == null) {
            return null;
        }
        GiDirectDirtyQueue.EpochTelemetry epochQueue = this.dirtyQueue.epochTelemetry();
        if (!isSettledTransportSource(epochQueue)) {
            return null;
        }
        GiDirectSourceGpuResources.Stats stats = this.resources.stats();
        if (!stats.ready() || stats.buildInFlight()
                || stats.worldGeneration() != this.activeEpoch.g2WorldGeneration()
                || stats.clipmapGeneration() != this.activeEpoch.g2ClipmapGeneration()
                || stats.paletteGeneration() != this.activeEpoch.g2PaletteGeneration()
                || stats.contentGeneration() != this.activeEpoch.g2ContentGeneration()
                || stats.staticSourceEpoch() != this.activeEpoch.staticLightRegistryEpoch()
                || stats.environmentEpoch() != this.activeEpoch.environmentEpoch()
                || stats.nearOriginX() != this.activeNearOriginX
                || stats.nearOriginY() != this.activeNearOriginY
                || stats.nearOriginZ() != this.activeNearOriginZ) {
            return null;
        }
        long stamp = transportSourceStamp(
                this.activeEpoch,
                this.activeNearOriginX, this.activeNearOriginY, this.activeNearOriginZ
        );
        if (this.cachedTransportSource != null
                && this.cachedTransportSource.sourceStamp() == stamp
                && this.cachedTransportSource.epoch().equals(this.activeEpoch)) {
            return this.cachedTransportSource;
        }
        TransportSourceIdentity identity = new TransportSourceIdentity(
                this.activeEpoch, stamp,
                this.activeNearOriginX, this.activeNearOriginY, this.activeNearOriginZ
        );
        this.cachedTransportSource = new TransportSource(
                this.resources.transportContextHandle(), identity
        );
        return this.cachedTransportSource;
    }

    /**
     * Returns one G6 cascade after every currently required direct brick is compatible.
     * Native readiness is checked after the asynchronous G3 command completed; outer-cascade
     * work may remain queued without delaying an already-converged near cascade.
     */
    public @Nullable LiveSource liveTransportSource(
            final int cascade,
            final long dynamicSourceEpoch,
            final long dynamicSourceHash
    ) {
        return liveTransportSource(
                cascade, dynamicSourceEpoch, dynamicSourceHash,
                0L, 0L, -1L, false
        );
    }

    /**
     * Same-submit G3 -> G6 hand-off. The accepted batch is only projected complete when its
     * immutable payload closes every current stamp/source-key gap in this cascade and belongs to
     * the exact command buffer, fence and renderer tick that will encode G6 after it. Native
     * independently re-proves the same command-buffer/header/fence identity before exposing the
     * private textures; a different submit therefore cannot consume an unretired batch.
     */
    public @Nullable LiveSource liveTransportSourceForSubmit(
            final int cascade,
            final long dynamicSourceEpoch,
            final long dynamicSourceHash,
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final long sourceTick
    ) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(fence, "fence");
        if (MetalNativeBridge.isNullHandle(commandBuffer)
                || MetalNativeBridge.isNullHandle(fence) || sourceTick < 0L) {
            throw new IllegalArgumentException("Invalid G3 same-submit source proof");
        }
        return liveTransportSource(
                cascade, dynamicSourceEpoch, dynamicSourceHash,
                commandBuffer.address(), fence.address(), sourceTick, true
        );
    }

    private @Nullable LiveSource liveTransportSource(
            final int cascade,
            final long dynamicSourceEpoch,
            final long dynamicSourceHash,
            final long commandBufferAddress,
            final long fenceAddress,
            final long sourceTick,
            final boolean allowAcceptedProjection
    ) {
        assertOwnerThread();
        if (cascade < 0 || cascade >= GiDirectSourceLayout.CASCADE_COUNT
                || dynamicSourceEpoch <= 0L || this.activeDynamicSources == null
                || this.liveSourceObservationBlocked
                || dynamicSourceEpoch != this.activeDynamicSourceEpoch
                || dynamicSourceHash != this.activeDynamicSourceHash
                || this.resources == null
                || this.activeEpoch == null || this.activeSourceKey == 0L) {
            return null;
        }
        int start = cascade * GiDirectSourceLayout.BRICKS_PER_CASCADE;
        int end = start + GiDirectSourceLayout.BRICKS_PER_CASCADE;
        boolean submittedExact = true;
        for (int brick = start; brick < end; brick++) {
            if (this.submittedSourceKeys[brick] != this.desiredSourceKeys[brick]
                    || this.submittedBrickStamps[brick] != this.desiredBrickStamps[brick]) {
                submittedExact = false;
                break;
            }
        }
        boolean settled = submittedExact && this.resources.fieldMatches(this.activeEpoch);
        boolean projected = allowAcceptedProjection
                && this.acceptedBatch.pending()
                && this.acceptedBatch.epoch().equals(this.activeEpoch)
                && this.dirtyQueue.inFlightCount() == this.acceptedBatch.count()
                && this.acceptedBatch.matchesSubmission(
                        commandBufferAddress, fenceAddress, sourceTick
                )
                && this.acceptedBatch.closesEveryGapInCascade(
                        cascade,
                        this.submittedBrickStamps, this.submittedSourceKeys,
                        this.desiredBrickStamps, this.desiredSourceKeys
                );
        if (!settled && !projected) {
            return null;
        }
        long stamp = liveTransportSourceStamp(
                this.activeEpoch, dynamicSourceEpoch, dynamicSourceHash,
                cascade, this.activeOrigins
        );
        LiveSource cached = this.cachedLiveSources[cascade];
        if (cached != null && cached.epoch().equals(this.activeEpoch)
                && cached.dynamicSourceEpoch() == dynamicSourceEpoch
                && cached.dynamicSourceHash() == dynamicSourceHash
                && cached.sourceStamp() == stamp) {
            return cached;
        }
        cached = new LiveSource(
                this.activeEpoch, dynamicSourceEpoch, dynamicSourceHash, stamp,
                this.untransferredAffectedBrickMasks[cascade],
                this.unacknowledgedAffectedBrickMasks[cascade],
                cascade, this.activeOrigins
        );
        this.cachedLiveSources[cascade] = cached;
        return cached;
    }

    /**
     * Lifetime scheduler counters remain monotonic. A retired older epoch may therefore leave
     * discarded work behind; exact native epochs/origins below still prove the current field.
     */
    static boolean isSettledTransportSource(final GiDirectDirtyQueue.EpochTelemetry queue) {
        Objects.requireNonNull(queue, "queue");
        return queue.fullVolumeEnqueued()
                && queue.queued() == GiDirectSourceLayout.TOTAL_BRICKS
                && queue.completed() == GiDirectSourceLayout.TOTAL_BRICKS
                && queue.discarded() == 0L
                && queue.pending() == 0
                && queue.inFlight() == 0
                && queue.queued() == queue.completed() + queue.discarded();
    }

    /** Admission-time telemetry ownership only; it cannot reveal a G3 dispatch capability. */
    public TelemetrySource telemetrySource() {
        assertOwnerThread();
        if (this.telemetrySource == null) {
            throw new IllegalStateException("G3 telemetry capability is closed");
        }
        return this.telemetrySource;
    }

    public LiveOwner liveOwner() {
        assertOwnerThread();
        if (this.liveOwner == null) {
            throw new IllegalStateException("G3 live-owner capability is closed");
        }
        return this.liveOwner;
    }

    /** Last camera-independent static identity observed by the live G3 preparation path. */
    public long observedLiveStaticSourceEpoch() {
        assertOwnerThread();
        return this.observedStaticState == null ? 0L : this.observedStaticState.registryEpoch();
    }

    /** Adopted camera-independent environment payload, or zero before a live G3 epoch exists. */
    public long observedLiveEnvironmentDigest() {
        assertOwnerThread();
        return this.affectedMaskEnvironmentDigest;
    }

    /** One-shot failure-boundary identity telemetry; never used by the steady submit path. */
    public String liveSourceDebugSummary(
            final long requestedDynamicSourceEpoch,
            final long requestedDynamicSourceHash
    ) {
        assertOwnerThread();
        GiDirectSourceGpuResources.Stats nativeState = this.resources == null
                ? null : this.resources.stats();
        int[] gaps = new int[GiDirectSourceLayout.CASCADE_COUNT];
        int[] acceptedCoverage = new int[GiDirectSourceLayout.CASCADE_COUNT];
        boolean[] acceptedCloses = new boolean[GiDirectSourceLayout.CASCADE_COUNT];
        for (int cascade = 0; cascade < GiDirectSourceLayout.CASCADE_COUNT; cascade++) {
            gaps[cascade] = cascadeGapCount(cascade);
            acceptedCoverage[cascade] = acceptedGapCoverageCount(cascade);
            acceptedCloses[cascade] = this.acceptedBatch.pending()
                    && this.activeEpoch != null
                    && this.acceptedBatch.epoch().equals(this.activeEpoch)
                    && this.acceptedBatch.closesEveryGapInCascade(
                            cascade,
                            this.submittedBrickStamps, this.submittedSourceKeys,
                            this.desiredBrickStamps, this.desiredSourceKeys
                    );
        }
        return "{requested_dynamic=" + requestedDynamicSourceEpoch + "/"
                + requestedDynamicSourceHash
                + ",active_dynamic=" + this.activeDynamicSourceEpoch + "/"
                + this.activeDynamicSourceHash
                + ",dynamic_match="
                + (requestedDynamicSourceEpoch == this.activeDynamicSourceEpoch
                && requestedDynamicSourceHash == this.activeDynamicSourceHash)
                + ",active_content=" + (this.activeEpoch == null
                ? 0L : this.activeEpoch.g2ContentGeneration())
                + ",active_static=" + (this.activeEpoch == null
                ? 0L : this.activeEpoch.staticLightRegistryEpoch())
                + ",native_content=" + (nativeState == null
                ? 0L : nativeState.contentGeneration())
                + ",native_static=" + (nativeState == null
                ? 0L : nativeState.staticSourceEpoch())
                + ",native_ready=" + (nativeState != null && nativeState.ready())
                + ",native_in_flight="
                + (nativeState != null && nativeState.buildInFlight())
                + ",accepted={pending=" + this.acceptedBatch.pending()
                + ",count=" + this.acceptedBatch.count()
                + ",tick=" + this.acceptedBatch.sourceTick()
                + ",content=" + (this.acceptedBatch.pending()
                ? this.acceptedBatch.epoch().g2ContentGeneration() : 0L) + "}"
                + ",gaps=" + Arrays.toString(gaps)
                + ",accepted_coverage=" + Arrays.toString(acceptedCoverage)
                + ",accepted_closes=" + Arrays.toString(acceptedCloses)
                + ",transfer_pending="
                + Arrays.toString(this.untransferredAffectedBrickMasks)
                + ",cumulative_audit="
                + Arrays.toString(this.unacknowledgedAffectedBrickMasks) + "}";
    }

    private int cascadeGapCount(final int cascade) {
        int start = cascade * GiDirectSourceLayout.BRICKS_PER_CASCADE;
        int end = start + GiDirectSourceLayout.BRICKS_PER_CASCADE;
        int count = 0;
        for (int brick = start; brick < end; brick++) {
            if (this.submittedBrickStamps[brick] != this.desiredBrickStamps[brick]
                    || this.submittedSourceKeys[brick] != this.desiredSourceKeys[brick]) {
                count++;
            }
        }
        return count;
    }

    private int acceptedGapCoverageCount(final int cascade) {
        if (!this.acceptedBatch.pending()) return 0;
        int start = cascade * GiDirectSourceLayout.BRICKS_PER_CASCADE;
        int end = start + GiDirectSourceLayout.BRICKS_PER_CASCADE;
        int count = 0;
        for (int brick = start; brick < end; brick++) {
            if (this.submittedBrickStamps[brick] == this.desiredBrickStamps[brick]
                    && this.submittedSourceKeys[brick] == this.desiredSourceKeys[brick]) {
                continue;
            }
            for (int index = 0; index < this.acceptedBatch.count(); index++) {
                if (this.acceptedBatch.brickIds()[index] == brick
                        && this.acceptedBatch.brickStamps[index]
                        == this.desiredBrickStamps[brick]
                        && this.acceptedBatch.sourceKeys[index]
                        == this.desiredSourceKeys[brick]) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * Copies CPU-proven source dirt which no exact G6 plan owns yet. This mask survives G3 epoch
     * rotation, so an unavailable A -> B observation cannot be skipped by a later B -> C source;
     * once a matching authoritative plan accepts it, unfinished work is represented by G6's
     * exact/required masks instead of being re-applied on every streaming child.
     */
    public boolean copyObservedLiveAffectedBrickMasks(
            final GiSemanticFieldIdentityView field,
            final long desiredStaticRegistryEpoch,
            final long desiredEnvironmentDigest,
            final long dynamicSourceEpoch,
            final long dynamicSourceHash,
            final long[] destination
    ) {
        assertOwnerThread();
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(destination, "destination");
        if (destination.length != GiDirectSourceLayout.CASCADE_COUNT
                || this.liveSourceObservationBlocked
                || this.activeEpoch == null
                || this.activeDynamicSources == null
                || this.staticState == null
                || this.environment == null
                || !liveAffectedMaskIdentityMatches(
                        this.affectedMaskStaticRegistryEpoch,
                        this.affectedMaskEnvironmentDigest,
                        desiredStaticRegistryEpoch,
                        desiredEnvironmentDigest
                )
                || this.staticState.registryEpoch() != desiredStaticRegistryEpoch
                || this.environment.quantizedDigest() != desiredEnvironmentDigest
                || dynamicSourceEpoch != this.activeDynamicSourceEpoch
                || dynamicSourceHash != this.activeDynamicSourceHash
                || field.world().worldGeneration() != this.activeEpoch.g2WorldGeneration()
                || field.world().resourceEpoch() != this.activeEpoch.g2ResourceEpoch()
                || field.world().materialEpoch() != this.activeEpoch.g2MaterialEpoch()
                || field.clipmapGeneration() != this.activeEpoch.g2ClipmapGeneration()
                || field.paletteGeneration() != this.activeEpoch.g2PaletteGeneration()
                || field.contentGeneration() != this.activeEpoch.g2ContentGeneration()) {
            return false;
        }
        for (int index = 0; index < this.activeOrigins.length; index++) {
            if (field.originComponent(index) != this.activeOrigins[index]) return false;
        }
        System.arraycopy(
                this.untransferredAffectedBrickMasks, 0, destination, 0,
                GiDirectSourceLayout.CASCADE_COUNT
        );
        return true;
    }

    /**
     * Transfers exactly one current per-cascade source mask after native G6 plan admission.
     * Same-submit projected sources are revalidated against their exact CB/fence/tick here;
     * settled sources retain their ordinary no-writer proof. A race or newer G3 mark leaves the
     * accumulator untouched, so a later plan remains conservative.
     */
    public boolean transferLiveAffectedBrickMask(
            final int cascade,
            final LiveSource plannedSource,
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final long sourceTick
    ) {
        assertOwnerThread();
        Objects.requireNonNull(plannedSource, "plannedSource");
        if (cascade < 0 || cascade >= GiDirectSourceLayout.CASCADE_COUNT
                || plannedSource.cascade() != cascade
                || this.activeEpoch == null || this.activeDynamicSources == null
                || this.resources == null) {
            return false;
        }
        LiveSource current = liveTransportSourceForSubmit(
                cascade,
                plannedSource.dynamicSourceEpoch(), plannedSource.dynamicSourceHash(),
                commandBuffer, fence, sourceTick
        );
        long plannedMask = plannedSource.affectedBrickMask();
        if (current == null
                || !sameLiveSourceIdentity(current, plannedSource)
                || current.affectedBrickMask() != plannedMask
                || plannedMask != this.untransferredAffectedBrickMasks[cascade]) {
            return false;
        }
        this.untransferredAffectedBrickMasks[cascade] =
                untransferredAffectedMaskAfterPlan(
                        this.untransferredAffectedBrickMasks[cascade],
                        plannedMask, true, true
                );
        this.cachedLiveSources[cascade] = null;
        return true;
    }

    /**
     * Clears exactly one cumulative mask after G6 has asynchronously made that cascade fully
     * exact. The unforgeable source must still be the current converged G3 identity and must
     * describe the whole cumulative mask; an identity advance or intervening mark leaves it
     * untouched so a later authoritative build cannot skip the newer dirt.
     */
    public boolean acknowledgeLiveAffectedBrickMask(
            final int cascade,
            final LiveSource acknowledgedSource
    ) {
        assertOwnerThread();
        Objects.requireNonNull(acknowledgedSource, "acknowledgedSource");
        if (cascade < 0 || cascade >= GiDirectSourceLayout.CASCADE_COUNT
                || acknowledgedSource.cascade() != cascade
                || this.activeEpoch == null
                || this.activeDynamicSources == null
                || this.resources == null) {
            return false;
        }
        LiveSource current = liveTransportSource(
                cascade, this.activeDynamicSourceEpoch, this.activeDynamicSourceHash
        );
        if (current == null
                || !sameLiveSourceIdentity(current, acknowledgedSource)
                || acknowledgedSource.cumulativeAffectedBrickMask()
                != this.unacknowledgedAffectedBrickMasks[cascade]) {
            return false;
        }
        this.unacknowledgedAffectedBrickMasks[cascade] =
                cumulativeAffectedMaskAfterAcknowledgement(
                        this.unacknowledgedAffectedBrickMasks[cascade],
                        acknowledgedSource.cumulativeAffectedBrickMask(), true, true
                );
        // A future same-identity query must expose the post-ack cumulative truth, not the
        // immutable pre-ack LiveSource cached above.
        this.cachedLiveSources[cascade] = null;
        return true;
    }

    private static boolean sameLiveSourceIdentity(
            final LiveSource current,
            final LiveSource acknowledged
    ) {
        if (current.cascade() != acknowledged.cascade()
                || !current.epoch().equals(acknowledged.epoch())
                || current.dynamicSourceEpoch() != acknowledged.dynamicSourceEpoch()
                || current.dynamicSourceHash() != acknowledged.dynamicSourceHash()
                || current.sourceStamp() != acknowledged.sourceStamp()) {
            return false;
        }
        for (int index = 0; index < 9; index++) {
            if (current.originComponent(index) != acknowledged.originComponent(index)) {
                return false;
            }
        }
        return true;
    }

    /** Package-private deterministic oracle used by the no-skipped A -> B -> C tests. */
    static long cumulativeAffectedMaskAfterMark(
            final long cumulativeMask,
            final long newlyAffectedMask
    ) {
        return cumulativeMask | newlyAffectedMask;
    }

    /** Package-private exact/race oracle for G3 -> G6 plan ownership transfer. */
    static long untransferredAffectedMaskAfterPlan(
            final long currentMask,
            final long plannedMask,
            final boolean identityStillCurrent,
            final boolean nativePlanAccepted
    ) {
        return identityStillCurrent && nativePlanAccepted && currentMask == plannedMask
                ? 0L : currentMask;
    }

    /** Package-private exact/race oracle shared by production acknowledgement and CPU tests. */
    static long cumulativeAffectedMaskAfterAcknowledgement(
            final long cumulativeMask,
            final long acknowledgedMask,
            final boolean identityStillCurrent,
            final boolean cascadeFullyExact
    ) {
        return identityStillCurrent && cascadeFullyExact && cumulativeMask == acknowledgedMask
                ? 0L : cumulativeMask;
    }

    /** Primitive oracle for the fail-closed provisional G3 -> G6 mask handoff. */
    static boolean liveAffectedMaskIdentityMatches(
            final long activeStaticRegistryEpoch,
            final long activeEnvironmentDigest,
            final long desiredStaticRegistryEpoch,
            final long desiredEnvironmentDigest
    ) {
        return activeStaticRegistryEpoch > 0L
                && activeStaticRegistryEpoch == desiredStaticRegistryEpoch
                && activeEnvironmentDigest == desiredEnvironmentDigest;
    }

    /** Allocation-free health check used to zero G6 before a replacement G3 epoch converges. */
    public boolean liveStaticSourceObservationIsCurrent(
            final AdvancedLightRegistry registry,
            final String dimensionId
    ) {
        assertOwnerThread();
        Objects.requireNonNull(registry, "registry");
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("G6 static-source dimension is blank");
        }
        return this.observedStaticState != null
                && !this.liveSourceObservationBlocked
                && this.observedStaticState.world().dimensionId().equals(dimensionId)
                && registry.staticSourceIdentityMatchesForGi(
                        this.observedStaticState.world(),
                        this.observedStaticState.registryEpoch()
                );
    }

    /** Allocation-free observation of source-only drift after G4 has latched this capability. */
    public boolean transportSourceIdentityStillCurrent(
            final TransportSourceIdentity source,
            final EnvironmentDescriptor descriptor,
            final AdvancedLightRegistry registry
    ) {
        assertOwnerThread();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(registry, "registry");
        GiDirectSourceEpoch epoch = source.epoch();
        return epoch.equals(this.activeEpoch)
                && source.nearOriginX() == this.activeNearOriginX
                && source.nearOriginY() == this.activeNearOriginY
                && source.nearOriginZ() == this.activeNearOriginZ
                && this.environment != null
                && frozenStaticSourceIdentityStillCurrent(
                        epoch, this.observedStaticState, registry
                )
                && GiEnvironmentSource.quantizedDigest(descriptor)
                == this.environment.quantizedDigest();
    }

    /**
     * G3 publishes a compact logical source epoch to native, while the registry retains its own
     * process-local mutation epoch. Post-submit drift checks must compare the frozen registry
     * identity captured for that logical epoch, rather than confusing the two counters.
     */
    static boolean frozenStaticSourceIdentityStillCurrent(
            final GiDirectSourceEpoch logicalEpoch,
            final @Nullable GiStaticSourceState frozenStaticState,
            final AdvancedLightRegistry registry
    ) {
        Objects.requireNonNull(logicalEpoch, "logicalEpoch");
        Objects.requireNonNull(registry, "registry");
        return frozenStaticState != null
                && frozenStaticState.world().equals(logicalEpoch.staticLightWorld())
                && registry.staticSourceIdentityMatchesForGi(
                        frozenStaticState.world(), frozenStaticState.registryEpoch()
                );
    }

    /**
     * FNV-1a over the six native G3 epochs and signed near origin, followed by a fixed avalanche.
     * Zero is remapped to one so a published opaque source can never alias an absent stamp.
     */
    public static long transportSourceStamp(
            final GiDirectSourceEpoch epoch,
            final int nearOriginX,
            final int nearOriginY,
            final int nearOriginZ
    ) {
        Objects.requireNonNull(epoch, "epoch");
        long hash = FNV_OFFSET_BASIS;
        hash = fnvLong(hash, epoch.g2WorldGeneration());
        hash = fnvLong(hash, epoch.g2ClipmapGeneration());
        hash = fnvLong(hash, epoch.g2PaletteGeneration());
        hash = fnvLong(hash, epoch.g2ContentGeneration());
        hash = fnvLong(hash, epoch.staticLightRegistryEpoch());
        hash = fnvLong(hash, epoch.environmentEpoch());
        hash = fnvLong(hash, nearOriginX);
        hash = fnvLong(hash, nearOriginY);
        hash = fnvLong(hash, nearOriginZ);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return hash == 0L ? 1L : hash;
    }

    public static long liveTransportSourceStamp(
            final GiDirectSourceEpoch epoch,
            final long dynamicSourceEpoch,
            final long dynamicSourceHash,
            final int cascade,
            final int[] origins
    ) {
        Objects.requireNonNull(epoch, "epoch");
        if (dynamicSourceEpoch <= 0L || cascade < 0
                || cascade >= GiDirectSourceLayout.CASCADE_COUNT
                || origins == null || origins.length != 9) {
            throw new IllegalArgumentException("Invalid G6 live source-stamp input");
        }
        long hash = FNV_OFFSET_BASIS;
        hash = fnvLong(hash, epoch.g2WorldGeneration());
        hash = fnvLong(hash, epoch.g2ClipmapGeneration());
        hash = fnvLong(hash, epoch.g2PaletteGeneration());
        hash = fnvLong(hash, epoch.g2ContentGeneration());
        hash = fnvLong(hash, epoch.staticLightRegistryEpoch());
        hash = fnvLong(hash, dynamicSourceEpoch);
        hash = fnvLong(hash, dynamicSourceHash);
        hash = fnvLong(hash, epoch.environmentEpoch());
        hash = fnvLong(hash, cascade);
        for (int origin : origins) hash = fnvLong(hash, origin);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return hash == 0L ? 1L : hash;
    }

    /** Frozen source-stamp compatibility helper retained for focused legacy tests. */
    public static long liveTransportSourceStamp(
            final GiDirectSourceEpoch epoch,
            final long dynamicSourceEpoch,
            final int cascade,
            final int[] origins
    ) {
        return liveTransportSourceStamp(epoch, dynamicSourceEpoch, 0L, cascade, origins);
    }

    private void enqueueChangedBricks(
            final GiSemanticDirectFieldView field,
            final GiDirectSourceEpoch epoch,
            final long tick
    ) {
        for (int brick = 0; brick < GiDirectSourceLayout.TOTAL_BRICKS; brick++) {
            long desired = field.brickContentStamp(brick);
            this.desiredBrickStamps[brick] = desired;
            if (desired != this.submittedBrickStamps[brick]
                    || this.submittedSourceKeys[brick] != this.desiredSourceKeys[brick]) {
                this.dirtyQueue.enqueue(epoch, brick, tick);
                markAffected(brick);
            }
        }
    }

    /** Avoids constructing an immutable epoch object on every unchanged live submit. */
    private static boolean matchesEpoch(
            final @Nullable GiDirectSourceEpoch epoch,
            final GiSemanticWorldToken world,
            final GiSemanticDirectFieldView field,
            final GiStaticSourceState staticState,
            final long staticSourceEpoch,
            final long environmentEpoch
    ) {
        return epoch != null
                && epoch.g2WorldGeneration() == world.worldGeneration()
                && epoch.g2ResourceEpoch() == world.resourceEpoch()
                && epoch.g2MaterialEpoch() == world.materialEpoch()
                && epoch.g2ClipmapGeneration() == field.clipmapGeneration()
                && epoch.g2PaletteGeneration() == field.paletteGeneration()
                && epoch.g2ContentGeneration() == field.contentGeneration()
                && epoch.staticLightWorld().equals(staticState.world())
                && epoch.staticLightRegistryEpoch() == staticSourceEpoch
                && epoch.environmentEpoch() == environmentEpoch;
    }

    /**
     * Pure zero-dirty rollover proof. Equal per-brick inputs mean raw G2/static mutations outside
     * every clipmap may advance logical/native identity without rewriting any field resource.
     */
    static boolean metadataOnlyRolloverCompatible(
            final GiDirectSourceEpoch previousEpoch,
            final GiDirectSourceEpoch nextEpoch,
            final int[] previousOrigins,
            final int[] nextOrigins,
            final long previousEnvironmentDigest,
            final long nextEnvironmentDigest,
            final long[] submittedBrickStamps,
            final long[] desiredBrickStamps,
            final long[] submittedSourceKeys,
            final long[] desiredSourceKeys
    ) {
        Objects.requireNonNull(previousEpoch, "previousEpoch");
        Objects.requireNonNull(nextEpoch, "nextEpoch");
        Objects.requireNonNull(previousOrigins, "previousOrigins");
        Objects.requireNonNull(nextOrigins, "nextOrigins");
        Objects.requireNonNull(submittedBrickStamps, "submittedBrickStamps");
        Objects.requireNonNull(desiredBrickStamps, "desiredBrickStamps");
        Objects.requireNonNull(submittedSourceKeys, "submittedSourceKeys");
        Objects.requireNonNull(desiredSourceKeys, "desiredSourceKeys");
        if (previousOrigins.length != 9 || nextOrigins.length != 9
                || submittedBrickStamps.length != GiDirectSourceLayout.TOTAL_BRICKS
                || desiredBrickStamps.length != GiDirectSourceLayout.TOTAL_BRICKS
                || submittedSourceKeys.length != GiDirectSourceLayout.TOTAL_BRICKS
                || desiredSourceKeys.length != GiDirectSourceLayout.TOTAL_BRICKS) {
            throw new IllegalArgumentException("G3 metadata-only rollover inputs are invalid");
        }
        return nextEpoch.isMetadataOnlySuccessorOf(previousEpoch)
                && previousEnvironmentDigest == nextEnvironmentDigest
                && Arrays.equals(previousOrigins, nextOrigins)
                && Arrays.equals(submittedBrickStamps, desiredBrickStamps)
                && Arrays.equals(submittedSourceKeys, desiredSourceKeys);
    }

    /** Carries only byte-identical direct inputs across a logical G3 epoch rotation. */
    private void enqueueRotatedBricks(
            final GiDirectSourceEpoch epoch,
            final long tick,
            final boolean markPhysicalChanges
    ) {
        for (int brick = 0; brick < GiDirectSourceLayout.TOTAL_BRICKS; brick++) {
            if (this.desiredBrickStamps[brick] != this.submittedBrickStamps[brick]
                    || this.desiredSourceKeys[brick] != this.submittedSourceKeys[brick]) {
                this.dirtyQueue.enqueue(epoch, brick, tick);
                if (markPhysicalChanges) markAffected(brick);
            }
        }
    }

    /**
     * A bounded G2 scroll rewrites every slot-relative G3 brick even though overlapping
     * world-space lighting did not change. G3 must still repopulate its slot-addressed private
     * textures, but that relocation work is not physical source dirt for G6: the downstream
     * stage owns the exact exposed-slab plus transport-halo mask and remaps compatible output.
     *
     * <p>Any coalesced semantic mutation advances content beyond the number of clipmap moves;
     * any source/environment mutation has its own identity flag. Those cases stay conservative
     * and continue marking every actually queued G3 brick.</p>
     */
    static boolean originOnlyFieldRelocation(
            final @Nullable GiDirectSourceEpoch previous,
            final GiDirectSourceEpoch next,
            final boolean originsChanged,
            final boolean staticSourceChanged,
            final boolean dynamicSourceChanged,
            final boolean environmentChanged
    ) {
        Objects.requireNonNull(next, "next");
        if (previous == null || !originsChanged || staticSourceChanged
                || dynamicSourceChanged || environmentChanged) {
            return false;
        }
        long clipmapDelta = next.g2ClipmapGeneration() - previous.g2ClipmapGeneration();
        long contentDelta = next.g2ContentGeneration() - previous.g2ContentGeneration();
        return clipmapDelta > 0L && contentDelta == clipmapDelta;
    }

    private void captureDesiredBrickInputs(
            final GiSemanticDirectFieldView field,
            final AdvancedLightRegistry registry,
            final boolean rehashAllSources,
            final @Nullable GiDynamicSourceSnapshot previousDynamicSources,
            final int @Nullable [] previousOrigins,
            final @Nullable GiDynamicSourceSnapshot nextDynamicSources,
            final int @Nullable [] nextOrigins
    ) {
        for (int brick = 0; brick < GiDirectSourceLayout.TOTAL_BRICKS; brick++) {
            this.desiredBrickStamps[brick] = field.brickContentStamp(brick);
            if (rehashAllSources || previousOrigins != null && dynamicTransitionAffectsBrick(
                    previousDynamicSources, previousOrigins,
                    nextDynamicSources, nextOrigins, brick
            )) {
                this.desiredSourceKeys[brick] = brickSourceKey(field, registry, brick);
            }
        }
    }

    private long brickSourceKey(
            final GiSemanticDirectFieldView field,
            final AdvancedLightRegistry registry,
            final int brick
    ) {
        if (this.staticState == null || this.environment == null) {
            throw new IllegalStateException("G3 source-key inputs are unavailable");
        }
        int cascade = GiDirectSourceLayout.cascadeForBrickId(brick);
        int originIndex = cascade * 3;
        int originX = field.originComponent(originIndex);
        int originY = field.originComponent(originIndex + 1);
        int originZ = field.originComponent(originIndex + 2);
        int minX = GiDirectSourceLayout.brickMinWorldBlock(
                cascade, originX, GiDirectSourceLayout.brickX(brick)
        );
        int minY = GiDirectSourceLayout.brickMinWorldBlock(
                cascade, originY, GiDirectSourceLayout.brickY(brick)
        );
        int minZ = GiDirectSourceLayout.brickMinWorldBlock(
                cascade, originZ, GiDirectSourceLayout.brickZ(brick)
        );
        int span = GiDirectSourceLayout.BRICK_EDGE_CELLS
                * GiDirectSourceLayout.cellSizeBlocks(cascade);
        int selected = registry.copyStaticSourcesForGi(
                this.staticState.world(),
                minX, minY, minZ,
                (double) minX + span, (double) minY + span, (double) minZ + span,
                this.sourceScratch, 0, GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK
        );
        selected = GiDirectSourceGpuResources.mergeDynamicSourcesForBrick(
                this.activeDynamicSources,
                minX, minY, minZ,
                (double) minX + span, (double) minY + span, (double) minZ + span,
                this.sourceScratch, selected
        );
        long hash = fnvLong(FNV_OFFSET_BASIS, this.environment.quantizedDigest());
        hash = fnvLong(hash, selected);
        for (int index = 0; index < selected; index++) {
            AdvancedLight source = this.sourceScratch[index];
            hash = fnvLong(hash, Float.floatToRawIntBits((float) (source.x() - originX)));
            hash = fnvLong(hash, Float.floatToRawIntBits((float) (source.y() - originY)));
            hash = fnvLong(hash, Float.floatToRawIntBits((float) (source.z() - originZ)));
            hash = fnvLong(hash, Float.floatToRawIntBits(source.radius()));
            hash = fnvLong(hash, Float.floatToRawIntBits(source.red()));
            hash = fnvLong(hash, Float.floatToRawIntBits(source.green()));
            hash = fnvLong(hash, Float.floatToRawIntBits(source.blue()));
            hash = fnvLong(hash, Float.floatToRawIntBits(source.intensity()));
        }
        return hash == 0L ? 1L : hash;
    }

    private void markAffected(final int brick) {
        int cascade = GiDirectSourceLayout.cascadeForBrickId(brick);
        int local = brick - cascade * GiDirectSourceLayout.BRICKS_PER_CASCADE;
        long affected = 1L << local;
        this.affectedBrickMasks[cascade] |= affected;
        this.unacknowledgedAffectedBrickMasks[cascade] = cumulativeAffectedMaskAfterMark(
                this.unacknowledgedAffectedBrickMasks[cascade], affected
        );
        this.untransferredAffectedBrickMasks[cascade] = cumulativeAffectedMaskAfterMark(
                this.untransferredAffectedBrickMasks[cascade], affected
        );
        this.cachedLiveSources[cascade] = null;
    }

    /** Exact old/new world-space union used for a bounded dynamic-source transition. */
    static boolean dynamicTransitionAffectsBrick(
            final @Nullable GiDynamicSourceSnapshot previous,
            final int[] previousOrigins,
            final @Nullable GiDynamicSourceSnapshot next,
            final int[] nextOrigins,
            final int brick
    ) {
        Objects.requireNonNull(previousOrigins, "previousOrigins");
        Objects.requireNonNull(nextOrigins, "nextOrigins");
        if (previousOrigins.length != 9 || nextOrigins.length != 9) {
            throw new IllegalArgumentException("G6 source-transition origins are invalid");
        }
        GiDirectSourceLayout.validateBrickId(brick);
        return snapshotAffectsBrick(previous, previousOrigins, brick)
                || snapshotAffectsBrick(next, nextOrigins, brick);
    }

    private static boolean snapshotAffectsBrick(
            final @Nullable GiDynamicSourceSnapshot snapshot,
            final int[] origins,
            final int brick
    ) {
        if (snapshot == null || snapshot.sources().isEmpty()) {
            return false;
        }
        int cascade = GiDirectSourceLayout.cascadeForBrickId(brick);
        int originIndex = cascade * 3;
        int minX = GiDirectSourceLayout.brickMinWorldBlock(
                cascade, origins[originIndex], GiDirectSourceLayout.brickX(brick)
        );
        int minY = GiDirectSourceLayout.brickMinWorldBlock(
                cascade, origins[originIndex + 1], GiDirectSourceLayout.brickY(brick)
        );
        int minZ = GiDirectSourceLayout.brickMinWorldBlock(
                cascade, origins[originIndex + 2], GiDirectSourceLayout.brickZ(brick)
        );
        int span = GiDirectSourceLayout.BRICK_EDGE_CELLS
                * GiDirectSourceLayout.cellSizeBlocks(cascade);
        List<AdvancedLight> sources = snapshot.sources();
        for (int index = 0; index < sources.size(); index++) {
            if (GiDirectSourceGpuResources.intersectsSphere(
                    sources.get(index),
                    minX, minY, minZ,
                    (double) minX + span, (double) minY + span, (double) minZ + span
            )) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        assertOwnerThread();
        if (this.resources != null) {
            this.resources.close();
            this.resources = null;
        }
        this.activeEpoch = null;
        this.environment = null;
        this.staticState = null;
        this.observedStaticState = null;
        this.observedFrozenWorld = null;
        this.cachedTransportSource = null;
        this.activeDynamicSources = null;
        this.telemetrySource = null;
        this.liveOwner = null;
        this.observedStaticSinceTick = 0L;
        resetFrozenObservation();
        this.activeNearOriginX = 0;
        this.activeNearOriginY = 0;
        this.activeNearOriginZ = 0;
        Arrays.fill(this.submittedBrickStamps, 0L);
        Arrays.fill(this.desiredBrickStamps, 0L);
        Arrays.fill(this.submittedSourceKeys, 0L);
        Arrays.fill(this.desiredSourceKeys, 0L);
        Arrays.fill(this.affectedBrickMasks, 0L);
        Arrays.fill(this.unacknowledgedAffectedBrickMasks, 0L);
        Arrays.fill(this.untransferredAffectedBrickMasks, 0L);
        Arrays.fill(this.activeOrigins, 0);
        Arrays.fill(this.cachedLiveSources, null);
        this.activeSourceKey = 0L;
        this.activeDynamicSourceEpoch = 0L;
        this.activeDynamicSourceHash = 0L;
        this.affectedMaskStaticRegistryEpoch = 0L;
        this.affectedMaskEnvironmentDigest = 0L;
        this.metadataRelabelPending = false;
        this.liveSourceObservationBlocked = false;
    }

    private boolean frozenTupleMatches(
            final GiSemanticDirectFieldView field,
            final GiSemanticWorldToken world,
            final GiStaticSourceState staticSources,
            final long environmentDigest
    ) {
        if (!world.equals(this.observedFrozenWorld)
                || !staticSources.equals(this.observedStaticState)
                || field.clipmapGeneration() != this.observedFrozenClipmapGeneration
                || field.paletteGeneration() != this.observedFrozenPaletteGeneration
                || field.contentGeneration() != this.observedFrozenContentGeneration
                || environmentDigest != this.observedFrozenEnvironmentDigest) {
            return false;
        }
        for (int index = 0; index < this.observedFrozenOrigins.length; index++) {
            if (field.originComponent(index) != this.observedFrozenOrigins[index]) {
                return false;
            }
        }
        return true;
    }

    private String frozenTupleDriftReason(
            final GiSemanticDirectFieldView field,
            final GiSemanticWorldToken world,
            final GiStaticSourceState staticSources,
            final long environmentDigest
    ) {
        if (!world.equals(this.observedFrozenWorld)) return "world";
        if (!staticSources.equals(this.observedStaticState)) return "static_sources";
        if (field.clipmapGeneration() != this.observedFrozenClipmapGeneration) {
            return "clipmap";
        }
        if (field.paletteGeneration() != this.observedFrozenPaletteGeneration) {
            return "palette";
        }
        if (field.contentGeneration() != this.observedFrozenContentGeneration) {
            return "content";
        }
        if (environmentDigest != this.observedFrozenEnvironmentDigest) return "environment";
        for (int index = 0; index < this.observedFrozenOrigins.length; index++) {
            if (field.originComponent(index) != this.observedFrozenOrigins[index]) {
                return "origin";
            }
        }
        return "unknown";
    }

    private void captureFrozenTuple(
            final GiSemanticDirectFieldView field,
            final GiSemanticWorldToken world,
            final GiStaticSourceState staticSources,
            final long environmentDigest,
            final long tick,
            final long nowNanos,
            final String changeReason,
            final boolean resetSettleClock
    ) {
        this.observedFrozenWorld = world;
        this.observedStaticState = staticSources;
        this.observedFrozenClipmapGeneration = field.clipmapGeneration();
        this.observedFrozenPaletteGeneration = field.paletteGeneration();
        this.observedFrozenContentGeneration = field.contentGeneration();
        this.observedFrozenEnvironmentDigest = environmentDigest;
        for (int index = 0; index < this.observedFrozenOrigins.length; index++) {
            this.observedFrozenOrigins[index] = field.originComponent(index);
        }
        this.observedStaticSinceTick = tick;
        if (resetSettleClock) {
            this.observedFrozenSinceNanos = nowNanos;
        }
        this.frozenTupleChangeReason = changeReason;
        this.frozenTupleChangeCount = Math.incrementExact(this.frozenTupleChangeCount);
    }

    private void restartFrozenPreparation(final String reason) {
        resetActiveSource();
        this.frozenPreparationRestartReason = reason;
    }

    private void restartCommittedPreparation(final String reason) {
        this.activeEpoch = null;
        this.environment = null;
        this.staticState = null;
        this.cachedTransportSource = null;
        this.activeDynamicSources = null;
        this.activeDynamicSourceEpoch = 0L;
        this.activeDynamicSourceHash = 0L;
        this.affectedMaskStaticRegistryEpoch = 0L;
        this.affectedMaskEnvironmentDigest = 0L;
        this.metadataRelabelPending = false;
        Arrays.fill(this.affectedBrickMasks, 0L);
        Arrays.fill(this.unacknowledgedAffectedBrickMasks, 0L);
        Arrays.fill(this.untransferredAffectedBrickMasks, 0L);
        Arrays.fill(this.cachedLiveSources, null);
        this.frozenPreparationCommitted = false;
        this.frozenPreparationRestartReason = reason;
    }

    static boolean isStructuralDrift(final String reason) {
        return reason.equals("world") || reason.equals("world_generation")
                || reason.equals("clipmap") || reason.equals("palette")
                || reason.equals("origin");
    }

    private void resetActiveSource() {
        this.activeEpoch = null;
        this.environment = null;
        this.staticState = null;
        this.observedStaticState = null;
        this.cachedTransportSource = null;
        this.activeDynamicSources = null;
        this.activeDynamicSourceEpoch = 0L;
        this.activeDynamicSourceHash = 0L;
        this.affectedMaskStaticRegistryEpoch = 0L;
        this.affectedMaskEnvironmentDigest = 0L;
        this.metadataRelabelPending = false;
        this.liveDrainTick = Long.MIN_VALUE;
        this.liveDrainConsumed = false;
        this.liveSourceObservationBlocked = false;
        Arrays.fill(this.affectedBrickMasks, 0L);
        Arrays.fill(this.unacknowledgedAffectedBrickMasks, 0L);
        Arrays.fill(this.untransferredAffectedBrickMasks, 0L);
        Arrays.fill(this.cachedLiveSources, null);
        resetFrozenObservation();
    }

    static boolean frozenInputSettled(
            final boolean interactive,
            final long observedFrame,
            final long currentFrame,
            final long observedNanos,
            final long currentNanos
    ) {
        return interactive
                ? currentNanos - observedNanos >= INTERACTIVE_FROZEN_INPUT_SETTLE_NANOS
                : currentFrame - observedFrame >= FROZEN_INPUT_SETTLE_FRAMES;
    }

    private void resetFrozenObservation() {
        this.observedFrozenWorld = null;
        this.observedFrozenClipmapGeneration = 0L;
        this.observedFrozenPaletteGeneration = 0L;
        this.observedFrozenContentGeneration = 0L;
        this.observedFrozenEnvironmentDigest = 0L;
        this.observedFrozenSinceNanos = 0L;
        this.frozenPreparationCommitted = false;
        Arrays.fill(this.observedFrozenOrigins, 0);
    }

    private static long fnvLong(long hash, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            hash ^= value & 0xffL;
            hash *= FNV_PRIME;
            value >>>= Byte.SIZE;
        }
        return hash;
    }

    private static long liveSourceKey(
            final GiDirectSourceEpoch epoch,
            final long dynamicSourceEpoch,
            final long dynamicSourceHash,
            final int[] origins
    ) {
        Objects.requireNonNull(epoch, "epoch");
        if (dynamicSourceEpoch < 0L || origins == null || origins.length != 9) {
            throw new IllegalArgumentException("Invalid G6 direct-source key input");
        }
        long hash = FNV_OFFSET_BASIS;
        hash = fnvLong(hash, epoch.g2WorldGeneration());
        hash = fnvLong(hash, epoch.g2ClipmapGeneration());
        hash = fnvLong(hash, epoch.g2PaletteGeneration());
        hash = fnvLong(hash, epoch.g2ContentGeneration());
        hash = fnvLong(hash, epoch.staticLightRegistryEpoch());
        hash = fnvLong(hash, dynamicSourceEpoch);
        hash = fnvLong(hash, dynamicSourceHash);
        hash = fnvLong(hash, epoch.environmentEpoch());
        for (int origin : origins) {
            hash = fnvLong(hash, origin);
        }
        return hash == 0L ? 1L : hash;
    }

    private void adoptDynamicSources(
            final @Nullable GiDynamicSourceSnapshot dynamicSources
    ) {
        this.activeDynamicSources = dynamicSources;
        if (dynamicSources == null) {
            this.activeDynamicSourceEpoch = 0L;
            this.activeDynamicSourceHash = 0L;
        } else {
            this.activeDynamicSourceEpoch = dynamicSources.epoch().sourceEpoch();
            this.activeDynamicSourceHash = dynamicSources.sourceHash();
        }
    }

    static boolean dynamicSourceIdentityChanged(
            final long activeDynamicSourceEpoch,
            final long activeDynamicSourceHash,
            final @Nullable GiDynamicSourceSnapshot desired
    ) {
        if (activeDynamicSourceEpoch < 0L) {
            throw new IllegalArgumentException("Tracked G6 dynamic-source epoch is negative");
        }
        long desiredEpoch = desired == null ? 0L : desired.epoch().sourceEpoch();
        long desiredHash = desired == null ? 0L : desired.sourceHash();
        if (activeDynamicSourceEpoch > 0L && desiredEpoch > 0L) {
            if (desiredEpoch < activeDynamicSourceEpoch) {
                throw new IllegalArgumentException("Stale G6 dynamic-source epoch was supplied");
            }
            if (desiredEpoch == activeDynamicSourceEpoch
                    && desiredHash != activeDynamicSourceHash) {
                throw new IllegalArgumentException(
                        "G6 dynamic-source content changed without an epoch rotation"
                );
            }
        }
        return desiredEpoch != activeDynamicSourceEpoch
                || desiredHash != activeDynamicSourceHash;
    }

    static void validateDynamicSourceWorld(
            final GiSemanticWorldToken semanticWorld,
            final com.metallum.client.lighting.LightWorldToken staticWorld,
            final @Nullable GiDynamicSourceSnapshot dynamicSources
    ) {
        Objects.requireNonNull(semanticWorld, "semanticWorld");
        Objects.requireNonNull(staticWorld, "staticWorld");
        if (dynamicSources == null) {
            return;
        }
        com.metallum.client.lighting.LightWorldToken dynamicWorld =
                dynamicSources.epoch().world();
        if (!dynamicWorld.equals(staticWorld)
                || !dynamicWorld.dimensionId().equals(semanticWorld.dimensionId())) {
            throw new IllegalArgumentException(
                    "G6 dynamic-source snapshot belongs to another G2/L3 world"
            );
        }
    }

    /**
     * Root lifecycle boundary whose child epochs may legally restart after resource reload.
     * A newer outer world owns arbitrary fresh children. Inside one world, every root component
     * must be monotonic and dimension-compatible; a strict resource/material/L3-root advance is
     * the only trusted same-world reset. Regressions remain fail-closed without mutating owners.
     */
    static ProductionRootRelation productionRootRelation(
            final @Nullable GiDirectSourceEpoch previous,
            final GiSemanticWorldToken semanticWorld,
            final com.metallum.client.lighting.LightWorldToken staticWorld
    ) {
        Objects.requireNonNull(semanticWorld, "semanticWorld");
        Objects.requireNonNull(staticWorld, "staticWorld");
        if (!semanticWorld.dimensionId().equals(staticWorld.dimensionId())) {
            return ProductionRootRelation.STALE;
        }
        if (previous == null) return ProductionRootRelation.SAME;
        long world = semanticWorld.worldGeneration();
        if (world > previous.g2WorldGeneration()) return ProductionRootRelation.ADVANCE;
        if (world < previous.g2WorldGeneration()
                || !semanticWorld.dimensionId().equals(
                        previous.staticLightWorld().dimensionId()
                )) {
            return ProductionRootRelation.STALE;
        }
        long resource = semanticWorld.resourceEpoch();
        long material = semanticWorld.materialEpoch();
        long staticGeneration = staticWorld.generation();
        if (resource < previous.g2ResourceEpoch()
                || material < previous.g2MaterialEpoch()
                || staticGeneration < previous.staticLightWorld().generation()) {
            return ProductionRootRelation.STALE;
        }
        return resource > previous.g2ResourceEpoch()
                || material > previous.g2MaterialEpoch()
                || staticGeneration > previous.staticLightWorld().generation()
                ? ProductionRootRelation.ADVANCE : ProductionRootRelation.SAME;
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G3 direct-source coordinator is render-thread confined");
        }
    }
}
