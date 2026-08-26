package com.metallum.client.gi.transport;

import com.metallum.client.gi.semantic.GiSemanticTransportFieldView;
import com.metallum.client.gi.source.GiDirectSourceCoordinator;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.function.Consumer;

/** One-shot render-thread coordinator. A submitted epoch is never rebuilt or scrolled. */
public final class GiTransportCoordinator implements AutoCloseable {
    public static final int STATUS_NO_WORK = 0;
    public static final int STATUS_INPUT_NOT_READY = -7;
    public static final int STATUS_CREATE_FAILED = -8;
    public static final int STATUS_BUILD_FAILED = -9;
    static final long MAX_SUBMISSION_FRAMES = 120L;

    enum BuildState {
        IDLE,
        SUBMITTED,
        READY,
        FAILED
    }

    private final Thread ownerThread = Thread.currentThread();
    @Nullable private GiTransportGpuResources resources;
    @Nullable private GiTransportEpoch submittedEpoch;
    private GiDirectSourceCoordinator.@Nullable TransportSourceIdentity submittedSourceIdentity;
    private BuildState buildState = BuildState.IDLE;
    private long submittedFrame = -1L;
    private boolean staleReported;

    /** Creates every fixed Java/native resource at device admission, outside frame submission. */
    public GiTransportCoordinator(
            final MemorySegment device,
            final MemorySegment commandQueue,
            final Consumer<MemorySegment> deferredRelease
    ) {
        this.resources = GiTransportGpuResources.create(
                device, commandQueue, Objects.requireNonNull(deferredRelease, "deferredRelease")
        );
        if (this.resources == null) {
            throw new IllegalStateException("Failed to precreate G4 frozen transport resources");
        }
    }

    /** CPU-contract-only constructor; it never creates or encodes native resources. */
    GiTransportCoordinator(final Consumer<MemorySegment> deferredRelease) {
        Objects.requireNonNull(deferredRelease, "deferredRelease");
    }

    /** Submits the only allowed immutable G4 build. Readiness is confirmed on later frames. */
    public int encodeFrame(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final GiSemanticTransportFieldView field,
            final GiDirectSourceCoordinator.TransportSource source,
            final long frameIndex
    ) {
        assertOwnerThread();
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(source, "source");
        if (this.buildState != BuildState.IDLE) {
            return STATUS_NO_WORK;
        }

        final GiTransportEpoch nextEpoch;
        try {
            nextEpoch = GiTransportEpoch.from(field, source);
        } catch (IllegalArgumentException staleInput) {
            return GiTransportGpuResources.STATUS_STALE;
        }
        if (this.resources == null) {
            return STATUS_CREATE_FAILED;
        }

        GiTransportGpuResources.PreparedFrozen prepared = this.resources.prepare(nextEpoch, field);
        int status = this.resources.encode(source, commandBuffer, fence, prepared);
        if (status == GiTransportGpuResources.STATUS_OK) {
            this.submittedEpoch = nextEpoch;
            this.submittedSourceIdentity = source.identity();
            this.submittedFrame = frameIndex;
            this.buildState = BuildState.SUBMITTED;
        }
        return status;
    }

    /**
     * Polls completion and observes epoch drift without ending an encoder or dispatching GPU work.
     * A changed/null post-submission input is published once as STALE and never causes a rebuild.
     */
    public int observeFrame(
            final @Nullable GiSemanticTransportFieldView field,
            final GiDirectSourceCoordinator.@Nullable TransportSourceIdentity source,
            final long frameIndex
    ) {
        assertOwnerThread();
        if (this.buildState == BuildState.IDLE) {
            return STATUS_INPUT_NOT_READY;
        }
        if (this.buildState == BuildState.FAILED || this.resources == null
                || this.submittedEpoch == null) {
            return STATUS_BUILD_FAILED;
        }
        if (this.buildState == BuildState.SUBMITTED) {
            GiTransportGpuResources.Stats stats = this.resources.stats();
            this.buildState = resolveSubmittedState(
                    stats, this.submittedEpoch, frameIndex - this.submittedFrame
            );
            if (this.buildState == BuildState.FAILED) {
                return STATUS_BUILD_FAILED;
            }
        }

        boolean matches = field != null && source != null
                && matches(this.submittedEpoch, field, source);
        if (!matches) {
            if (!this.staleReported) {
                int staleStatus = this.resources.reportStale();
                if (staleStatus != GiTransportGpuResources.STATUS_STALE) {
                    this.buildState = BuildState.FAILED;
                    return STATUS_BUILD_FAILED;
                }
                this.staleReported = true;
            }
            return GiTransportGpuResources.STATUS_STALE;
        }
        return STATUS_NO_WORK;
    }

    static BuildState resolveSubmittedState(
            final GiTransportGpuResources.Stats stats,
            final GiTransportEpoch epoch,
            final long submittedAgeFrames
    ) {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(epoch, "epoch");
        if (stats.buildInFlight()) {
            return submittedAgeFrames <= MAX_SUBMISSION_FRAMES
                    ? BuildState.SUBMITTED : BuildState.FAILED;
        }
        if (!stats.ready() || stats.rejectedCount() != 0L
                || stats.transportDispatches() != 1L || stats.fullVolumeBuilds() != 1L
                || stats.worldGeneration() != epoch.worldGeneration()
                || stats.clipmapGeneration() != epoch.clipmapGeneration()
                || stats.paletteGeneration() != epoch.paletteGeneration()
                || stats.contentGeneration() != epoch.contentGeneration()
                || stats.staticSourceEpoch() != epoch.staticSourceEpoch()
                || stats.environmentEpoch() != epoch.environmentEpoch()
                || stats.sourceStamp() != epoch.sourceStamp()
                || stats.nearOriginX() != epoch.nearOriginX()
                || stats.nearOriginY() != epoch.nearOriginY()
                || stats.nearOriginZ() != epoch.nearOriginZ()) {
            return BuildState.FAILED;
        }
        return BuildState.READY;
    }

    /** True after native accepted the sole build, including while completion is pending. */
    public boolean hasAcceptedEpoch() {
        assertOwnerThread();
        return this.buildState != BuildState.IDLE;
    }

    /** True only after native stats prove the one transport dispatch completed successfully. */
    public boolean isFrozen() {
        assertOwnerThread();
        return this.buildState == BuildState.READY;
    }

    public @Nullable GiTransportEpoch frozenEpoch() {
        assertOwnerThread();
        return this.buildState == BuildState.READY ? this.submittedEpoch : null;
    }

    public GiDirectSourceCoordinator.@Nullable TransportSourceIdentity submittedSourceIdentity() {
        assertOwnerThread();
        return this.submittedSourceIdentity;
    }

    public GiTransportGpuResources.@Nullable Stats nativeStats() {
        assertOwnerThread();
        return this.resources == null ? null : this.resources.stats();
    }

    public boolean awaitReady(final long timeoutMillis) {
        assertOwnerThread();
        return this.resources != null && this.resources.awaitReady(timeoutMillis);
    }

    public GiTransportGpuResources.@Nullable Capture captureVolumeOnce() {
        assertOwnerThread();
        return this.resources == null ? null : this.resources.captureVolumeOnce();
    }

    @Override
    public void close() {
        assertOwnerThread();
        if (this.resources != null) {
            this.resources.close();
            this.resources = null;
        }
        this.submittedEpoch = null;
        this.submittedSourceIdentity = null;
        this.buildState = BuildState.IDLE;
        this.submittedFrame = -1L;
        this.staleReported = false;
    }

    static boolean matches(
            final GiTransportEpoch frozen,
            final GiSemanticTransportFieldView field,
            final GiDirectSourceCoordinator.TransportSourceIdentity source
    ) {
        Objects.requireNonNull(frozen, "frozen");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(source, "source");
        return frozen.matches(field)
                && source.sourceStamp() == frozen.sourceStamp()
                && source.epoch().staticLightRegistryEpoch() == frozen.staticSourceEpoch()
                && source.epoch().environmentEpoch() == frozen.environmentEpoch()
                && source.nearOriginX() == frozen.nearOriginX()
                && source.nearOriginY() == frozen.nearOriginY()
                && source.nearOriginZ() == frozen.nearOriginZ();
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G4 transport coordinator is render-thread confined");
        }
    }
}
