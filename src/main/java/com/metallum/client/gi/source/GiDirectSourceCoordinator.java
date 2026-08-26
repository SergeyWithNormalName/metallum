package com.metallum.client.gi.source;

import com.metallum.client.gi.semantic.GiSemanticDirectFieldView;
import com.metallum.client.gi.semantic.GiSemanticWorldToken;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.EnvironmentDescriptor;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** Bounded scheduler joining accepted G2 truth with physical L3/L4 sources. */
public final class GiDirectSourceCoordinator implements AutoCloseable {
    public static final int STATUS_NO_WORK = 0;
    public static final int STATUS_INPUT_NOT_READY = -7;
    public static final int STATUS_CREATE_FAILED = -8;
    static final long STATIC_SOURCE_SETTLE_TICKS = 16L;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

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

    private final Thread ownerThread = Thread.currentThread();
    private final int[] drainedBricks = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
    private final AdvancedLight[] sourceScratch =
            new AdvancedLight[GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK];
    private final long[] submittedBrickStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
    private GiDirectDirtyQueue dirtyQueue = new GiDirectDirtyQueue();
    @Nullable private GiDirectSourceGpuResources resources;
    @Nullable private GiDirectSourceEpoch activeEpoch;
    @Nullable private GiEnvironmentSource environment;
    @Nullable private GiStaticSourceState staticState;
    @Nullable private GiStaticSourceState observedStaticState;
    @Nullable private TransportSource cachedTransportSource;
    private long observedStaticSinceTick;
    private long logicalStaticSourceEpoch = 1L;
    private long logicalEnvironmentEpoch = 1L;
    private int activeNearOriginX;
    private int activeNearOriginY;
    private int activeNearOriginZ;

    /** Precreates the fixed G3 field, staging ring and PSOs outside frame submission. */
    public GiDirectSourceCoordinator(
            final MemorySegment device,
            final MemorySegment commandQueue,
            final Consumer<MemorySegment> deferredRelease
    ) {
        this.resources = GiDirectSourceGpuResources.create(
                device, commandQueue, 1L,
                Objects.requireNonNull(deferredRelease, "deferredRelease")
        );
        if (this.resources == null) {
            throw new IllegalStateException("Failed to precreate G3 direct-source resources");
        }
    }

    public int encodeFrame(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final GiSemanticDirectFieldView field,
            final EnvironmentDescriptor environmentDescriptor,
            final AdvancedLightRegistry registry,
            final long tick
    ) {
        assertOwnerThread();
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(environmentDescriptor, "environmentDescriptor");
        Objects.requireNonNull(registry, "registry");

        GiSemanticWorldToken world = field.world();
        if (this.activeEpoch != null
                && this.activeEpoch.g2WorldGeneration() != world.worldGeneration()) {
            this.activeEpoch = null;
            this.environment = null;
            this.staticState = null;
            this.observedStaticState = null;
            this.cachedTransportSource = null;
        }
        GiStaticSourceState nextStatic = registry.staticSourceStateForGi(world.dimensionId());
        if (nextStatic == null) {
            return STATUS_INPUT_NOT_READY;
        }
        if (!nextStatic.equals(this.observedStaticState)) {
            this.observedStaticState = nextStatic;
            this.observedStaticSinceTick = tick;
        }
        boolean staticSourceChanged = !nextStatic.equals(this.staticState);
        if (staticSourceChanged
                && tick - this.observedStaticSinceTick < STATIC_SOURCE_SETTLE_TICKS) {
            // Never mix queries from a changing registry into one source epoch. G3 is field-only,
            // so retaining the previous private field (or exact-zero before first admission) is
            // safer than repeatedly invalidating all 192 bricks during chunk publication.
            return STATUS_INPUT_NOT_READY;
        }
        GiEnvironmentSource desiredEnvironment = GiEnvironmentSource.fromDescriptor(
                1L, environmentDescriptor);

        boolean newContext = this.activeEpoch == null;
        if (newContext) {
            if (this.resources == null) {
                return STATUS_CREATE_FAILED;
            }
            this.dirtyQueue = new GiDirectDirtyQueue();
            Arrays.fill(this.submittedBrickStamps, 0L);
            this.logicalStaticSourceEpoch = 1L;
            this.logicalEnvironmentEpoch = 1L;
            this.staticState = nextStatic;
            this.environment = desiredEnvironment.withEpoch(this.logicalEnvironmentEpoch);
            this.activeEpoch = null;
        } else {
            if (staticSourceChanged) {
                this.staticState = nextStatic;
                this.logicalStaticSourceEpoch = Math.incrementExact(this.logicalStaticSourceEpoch);
            }
            // Finish the current bounded generation before admitting another sun/sky sample.
            // The next sample is the latest desired value, so intermediate changes coalesce.
            if (this.dirtyQueue.telemetry().pending() == 0
                    && this.environment != null
                    && desiredEnvironment.quantizedDigest() != this.environment.quantizedDigest()) {
                this.logicalEnvironmentEpoch = Math.incrementExact(this.logicalEnvironmentEpoch);
                this.environment = desiredEnvironment.withEpoch(this.logicalEnvironmentEpoch);
            }
        }
        if (this.environment == null || this.staticState == null || this.resources == null) {
            return STATUS_INPUT_NOT_READY;
        }

        GiDirectSourceEpoch nextEpoch = new GiDirectSourceEpoch(
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
            this.cachedTransportSource = null;
            boolean allSourcesChanged = this.activeEpoch == null
                    || nextEpoch.staticLightRegistryEpoch()
                    != this.activeEpoch.staticLightRegistryEpoch()
                    || nextEpoch.environmentEpoch() != this.activeEpoch.environmentEpoch();
            this.dirtyQueue.rotateEpoch(nextEpoch);
            this.activeEpoch = nextEpoch;
            this.activeNearOriginX = field.originComponent(0);
            this.activeNearOriginY = field.originComponent(1);
            this.activeNearOriginZ = field.originComponent(2);
            if (allSourcesChanged) {
                this.dirtyQueue.enqueueAll(nextEpoch, tick);
            } else {
                enqueueChangedBricks(field, nextEpoch, tick);
            }
        } else {
            if (this.activeNearOriginX != field.originComponent(0)
                    || this.activeNearOriginY != field.originComponent(1)
                    || this.activeNearOriginZ != field.originComponent(2)) {
                throw new IllegalStateException("G3 near origin changed without a clipmap epoch");
            }
            enqueueChangedBricks(field, nextEpoch, tick);
        }

        int count = this.dirtyQueue.drainTo(nextEpoch, tick, this.drainedBricks);
        if (count == 0) {
            int telemetryStatus = this.resources.publishScheduler(this.dirtyQueue.telemetry());
            return telemetryStatus == GiDirectSourceGpuResources.STATUS_OK
                    ? STATUS_NO_WORK : telemetryStatus;
        }
        GiDirectSourceGpuResources.PreparedBatch batch;
        try {
            batch = this.resources.prepare(
                    nextEpoch, field, this.environment, registry,
                    this.drainedBricks, count, this.sourceScratch);
        } catch (RuntimeException failure) {
            this.dirtyQueue.retryBatch(nextEpoch, this.drainedBricks, count, tick);
            throw failure;
        }
        int status = this.resources.encode(commandBuffer, fence, batch);
        if (status == GiDirectSourceGpuResources.STATUS_OK) {
            this.dirtyQueue.completeBatch(nextEpoch, this.drainedBricks, count);
            for (int index = 0; index < count; index++) {
                int brick = this.drainedBricks[index];
                this.submittedBrickStamps[brick] = field.brickContentStamp(brick);
            }
        } else {
            this.dirtyQueue.retryBatch(nextEpoch, this.drainedBricks, count, tick);
        }
        int telemetryStatus = this.resources.publishScheduler(this.dirtyQueue.telemetry());
        return telemetryStatus == GiDirectSourceGpuResources.STATUS_OK ? status : telemetryStatus;
    }

    public GiDirectDirtyQueue.Telemetry queueTelemetry() {
        assertOwnerThread();
        return this.dirtyQueue.telemetry();
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
        GiDirectDirtyQueue.Telemetry queue = this.dirtyQueue.telemetry();
        if (queue.completed() != GiDirectSourceLayout.TOTAL_BRICKS
                || queue.pending() != 0 || queue.discarded() != 0L) {
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
                && registry.staticSourceIdentityMatchesForGi(
                        epoch.staticLightWorld(), epoch.staticLightRegistryEpoch()
                )
                && GiEnvironmentSource.quantizedDigest(descriptor)
                == this.environment.quantizedDigest();
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

    private void enqueueChangedBricks(
            final GiSemanticDirectFieldView field,
            final GiDirectSourceEpoch epoch,
            final long tick
    ) {
        for (int brick = 0; brick < GiDirectSourceLayout.TOTAL_BRICKS; brick++) {
            if (field.brickContentStamp(brick) != this.submittedBrickStamps[brick]) {
                this.dirtyQueue.enqueue(epoch, brick, tick);
            }
        }
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
        this.cachedTransportSource = null;
        this.observedStaticSinceTick = 0L;
        this.activeNearOriginX = 0;
        this.activeNearOriginY = 0;
        this.activeNearOriginZ = 0;
        Arrays.fill(this.submittedBrickStamps, 0L);
    }

    private static long fnvLong(long hash, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            hash ^= value & 0xffL;
            hash *= FNV_PRIME;
            value >>>= Byte.SIZE;
        }
        return hash;
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G3 direct-source coordinator is render-thread confined");
        }
    }
}
