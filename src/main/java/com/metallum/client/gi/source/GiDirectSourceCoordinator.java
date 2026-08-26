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

    private final Thread ownerThread = Thread.currentThread();
    private final Consumer<MemorySegment> deferredRelease;
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
    private long observedStaticSinceTick;
    private long logicalStaticSourceEpoch = 1L;
    private long logicalEnvironmentEpoch = 1L;

    public GiDirectSourceCoordinator(final Consumer<MemorySegment> deferredRelease) {
        this.deferredRelease = Objects.requireNonNull(deferredRelease, "deferredRelease");
    }

    public int encodeFrame(
            final MemorySegment device,
            final MemorySegment commandQueue,
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
            if (this.resources != null) {
                this.resources.close();
                this.resources = null;
            }
            this.activeEpoch = null;
            this.environment = null;
            this.staticState = null;
            this.observedStaticState = null;
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

        boolean newContext = this.resources == null || this.activeEpoch == null
                || this.activeEpoch.g2WorldGeneration() != world.worldGeneration();
        if (newContext) {
            replaceResources(device, commandQueue, world.worldGeneration());
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
        boolean epochChanged = !nextEpoch.equals(this.activeEpoch);
        if (epochChanged) {
            boolean allSourcesChanged = this.activeEpoch == null
                    || nextEpoch.staticLightRegistryEpoch()
                    != this.activeEpoch.staticLightRegistryEpoch()
                    || nextEpoch.environmentEpoch() != this.activeEpoch.environmentEpoch();
            this.dirtyQueue.rotateEpoch(nextEpoch);
            this.activeEpoch = nextEpoch;
            if (allSourcesChanged) {
                this.dirtyQueue.enqueueAll(nextEpoch, tick);
            } else {
                enqueueChangedBricks(field, nextEpoch, tick);
            }
        } else {
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

    private void replaceResources(
            final MemorySegment device,
            final MemorySegment commandQueue,
            final long worldGeneration
    ) {
        if (this.resources != null) {
            this.resources.close();
            this.resources = null;
        }
        this.resources = GiDirectSourceGpuResources.create(
                device, commandQueue, worldGeneration, this.deferredRelease);
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
        this.observedStaticSinceTick = 0L;
        Arrays.fill(this.submittedBrickStamps, 0L);
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G3 direct-source coordinator is render-thread confined");
        }
    }
}
