package com.metallum.client.benchmark;

import java.util.Arrays;

/**
 * Bounded, render-thread-confined counters for one explicit torch-test epoch.
 *
 * <p>The Sodium telemetry mixins are benchmark-only, but they can still run
 * before or after the targeted mutation window. Every recorder therefore
 * remains a no-op until {@link #begin(long)} and after {@link #end()} or
 * {@link #abort()}.</p>
 */
public final class TorchEpochTelemetry {
    private static final int DEFAULT_UNIQUE_SECTION_LIMIT = 8_192;
    private static final Recorder GLOBAL = new Recorder(DEFAULT_UNIQUE_SECTION_LIMIT);
    private static final ThreadLocal<LightRebuildScopeState> LIGHT_REBUILD_SCOPE = new ThreadLocal<>();

    private TorchEpochTelemetry() {
    }

    public static void begin(final long epochId) {
        boolean leakedScope = clearLightRebuildScope();
        GLOBAL.begin(epochId);
        if (leakedScope) {
            GLOBAL.recordLightScopeFailure();
        }
    }

    /**
     * Closes the current epoch and returns its final counters.
     *
     * <p>Calling this while inactive is safe and returns the last closed (or
     * reset) snapshot unchanged.</p>
     */
    public static Snapshot end() {
        if (clearLightRebuildScope()) {
            GLOBAL.recordLightScopeFailure();
        }
        return GLOBAL.end();
    }

    /** Discards any partial epoch after benchmark failure and returns to zero. */
    public static void abort() {
        clearLightRebuildScope();
        GLOBAL.abort();
    }

    public static Snapshot snapshot() {
        return GLOBAL.snapshot();
    }

    /** Fast benchmark-hook guard; production clients do not load the hooks at all. */
    public static boolean isActive() {
        return GLOBAL.isActive();
    }

    public static void recordRebuildRequest(final long sectionKey) {
        GLOBAL.recordRebuildRequest(sectionKey);
    }

    /**
     * Records a rebuild while preserving the exact synchronous cause scope.
     * Calls outside a valid light scope are deliberately classified as
     * geometry-or-unknown.
     */
    public static void recordRebuildRequest(
            final long sectionKey,
            final int sectionX,
            final int sectionY,
            final int sectionZ
    ) {
        LightRebuildScopeState scope = LIGHT_REBUILD_SCOPE.get();
        if (scope == null || !scope.valid) {
            GLOBAL.recordRebuildRequest(sectionKey, RebuildCause.GEOMETRY_OR_UNKNOWN);
            return;
        }
        if (!scope.contains(sectionX, sectionY, sectionZ)) {
            GLOBAL.recordLightScopeMismatch();
            GLOBAL.recordRebuildRequest(sectionKey, RebuildCause.GEOMETRY_OR_UNKNOWN);
            return;
        }
        GLOBAL.recordRebuildRequest(sectionKey, RebuildCause.LIGHT_ONLY);
    }

    /** Opens a light-only scope for one exact section-dirty call. */
    public static LightRebuildScope openExactLightRebuildScope(
            final int sectionX,
            final int sectionY,
            final int sectionZ
    ) {
        return openLightRebuildScope(sectionX, sectionY, sectionZ, 0);
    }

    /** Opens a light-only scope for the 3x3x3 dirty range around one section. */
    public static LightRebuildScope openNeighborLightRebuildScope(
            final int sectionX,
            final int sectionY,
            final int sectionZ
    ) {
        return openLightRebuildScope(sectionX, sectionY, sectionZ, 1);
    }

    /** Remains queryable after {@link #end()} until the next epoch is begun. */
    public static RebuildCause rebuildCause(final long sectionKey) {
        return GLOBAL.rebuildCause(sectionKey);
    }

    public static void recordRebuildTask(
            final long sectionKey,
            final long pendingAgeNanos
    ) {
        GLOBAL.recordRebuildTask(sectionKey, pendingAgeNanos);
    }

    public static void recordBuildOutput(final long sectionKey) {
        GLOBAL.recordBuildOutput(sectionKey);
    }

    public static void recordAcceptedMeshPayloadBytes(final long bytes) {
        GLOBAL.recordAcceptedMeshPayloadBytes(bytes);
    }

    public static void recordAcceptedGeometryPayloadBytes(final long bytes) {
        GLOBAL.recordAcceptedGeometryPayloadBytes(bytes);
    }

    public static void recordInPlaceGeometryRefresh(
            final long sectionKey,
            final long bytes,
            final long meshCommands
    ) {
        GLOBAL.recordInPlaceGeometryRefresh(sectionKey, bytes, meshCommands);
    }

    /**
     * Records the compact subset of successful in-place terrain refreshes.
     *
     * <p>The caller must also record the refresh through
     * {@link #recordInPlaceGeometryRefresh(long, long, long)} so the existing
     * in-place counters remain the union of full and compact refreshes.</p>
     */
    public static void recordCompactLightPatchOutput(
            final long sectionKey,
            final long geometryBytesElided,
            final long meshCommandsElided
    ) {
        GLOBAL.recordCompactLightPatchOutput(
                sectionKey,
                geometryBytesElided,
                meshCommandsElided
        );
    }

    public static void recordNativeLightPatch(
            final long dispatches,
            final long meshCommands
    ) {
        GLOBAL.recordNativeLightPatch(dispatches, meshCommands);
    }

    public static void recordCompactLightPatchFallback() {
        GLOBAL.recordCompactLightPatchFallback();
    }

    /** Remains queryable after {@link #end()} until the next epoch is begun. */
    public static boolean wasInPlaceGeometryRefreshed(final long sectionKey) {
        return GLOBAL.wasInPlaceGeometryRefreshed(sectionKey);
    }

    /** Remains queryable after {@link #end()} until the next epoch is begun. */
    public static boolean wasCompactLightPatched(final long sectionKey) {
        return GLOBAL.wasCompactLightPatched(sectionKey);
    }

    /** Remains queryable after {@link #end()} until the next epoch is begun. */
    public static boolean wasBuildOutput(final long sectionKey) {
        return GLOBAL.wasBuildOutput(sectionKey);
    }

    public static void recordSidecarUpload(final long bytes, final long commands) {
        GLOBAL.recordSidecarUpload(bytes, commands);
    }

    public static void recordSidecarResizeCopies(final long bytes, final long commands) {
        GLOBAL.recordSidecarResizeCopies(bytes, commands);
    }

    public static void recordSidecarFallback() {
        GLOBAL.recordSidecarFallback();
    }

    public static void recordBuilderWorkState(
            final int queuedJobs,
            final int busyWorkers,
            final int pendingResults
    ) {
        GLOBAL.recordBuilderWorkState(queuedJobs, busyWorkers, pendingResults);
    }

    public static void recordError() {
        GLOBAL.recordError();
    }

    private static LightRebuildScope openLightRebuildScope(
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final int radius
    ) {
        if (!GLOBAL.isActive()) {
            return new LightRebuildScope(null);
        }
        LightRebuildScopeState state = new LightRebuildScopeState(
                LIGHT_REBUILD_SCOPE.get(),
                sectionX,
                sectionY,
                sectionZ,
                radius
        );
        LIGHT_REBUILD_SCOPE.set(state);
        return new LightRebuildScope(state);
    }

    private static boolean clearLightRebuildScope() {
        LightRebuildScopeState state = LIGHT_REBUILD_SCOPE.get();
        if (state == null) {
            return false;
        }
        while (state != null) {
            state.valid = false;
            state = state.parent;
        }
        LIGHT_REBUILD_SCOPE.remove();
        return true;
    }

    static Recorder recorderForTests(final int uniqueSectionLimit) {
        return new Recorder(uniqueSectionLimit);
    }

    public enum RebuildCause {
        NONE,
        LIGHT_ONLY,
        GEOMETRY_OR_UNKNOWN
    }

    /** A re-entrant synchronous cause scope used only by exact benchmark hooks. */
    public static final class LightRebuildScope implements AutoCloseable {
        private final LightRebuildScopeState state;
        private boolean closed;

        private LightRebuildScope(final LightRebuildScopeState state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (this.state == null) {
                this.closed = true;
                return;
            }
            if (this.closed) {
                this.state.valid = false;
                LIGHT_REBUILD_SCOPE.remove();
                GLOBAL.recordLightScopeFailure();
                return;
            }
            this.closed = true;
            LightRebuildScopeState current = LIGHT_REBUILD_SCOPE.get();
            if (current != this.state) {
                this.state.valid = false;
                if (current != null) {
                    current.valid = false;
                }
                LIGHT_REBUILD_SCOPE.remove();
                GLOBAL.recordLightScopeFailure();
                return;
            }
            this.state.valid = false;
            if (this.state.parent == null) {
                LIGHT_REBUILD_SCOPE.remove();
            } else {
                LIGHT_REBUILD_SCOPE.set(this.state.parent);
            }
        }
    }

    private static final class LightRebuildScopeState {
        private final LightRebuildScopeState parent;
        private final int centerX;
        private final int centerY;
        private final int centerZ;
        private final int radius;
        private volatile boolean valid = true;

        private LightRebuildScopeState(
                final LightRebuildScopeState parent,
                final int centerX,
                final int centerY,
                final int centerZ,
                final int radius
        ) {
            this.parent = parent;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.radius = radius;
        }

        private boolean contains(final int x, final int y, final int z) {
            return Math.abs((long) x - this.centerX) <= this.radius
                    && Math.abs((long) y - this.centerY) <= this.radius
                    && Math.abs((long) z - this.centerZ) <= this.radius;
        }
    }

    public record Snapshot(
            boolean active,
            long epochId,
            long rebuildRequestCount,
            long uniqueRebuildRequestSections,
            long lightRebuildRequestCount,
            long geometryOrUnknownRebuildRequestCount,
            long uniqueLightRebuildRequestSections,
            long uniqueGeometryOrUnknownRebuildRequestSections,
            long lightOnlyRebuildSections,
            long mixedRebuildCauseSections,
            long lightScopeMismatchCount,
            long lightScopeFailureCount,
            long rebuildTaskCount,
            long uniqueRebuildTaskSections,
            long buildOutputCount,
            long uniqueBuildOutputSections,
            long acceptedMeshPayloadBytes,
            long acceptedGeometryPayloadBytes,
            long inPlaceGeometryRefreshOutputs,
            long uniqueInPlaceGeometryRefreshSections,
            long inPlaceGeometryRefreshBytes,
            long inPlaceGeometryRefreshMeshCommands,
            long compactLightPatchOutputs,
            long uniqueCompactLightPatchSections,
            long geometryBytesElided,
            long geometryMeshCommandsElided,
            long nativeLightPatchDispatches,
            long nativeLightPatchMeshCommands,
            long compactLightPatchFallbackCount,
            long sidecarProducedBytes,
            long sidecarUploadedBytes,
            long sidecarUploadCommands,
            long sidecarResizeCopyBytes,
            long sidecarResizeCopyCommands,
            long sidecarFallbackCount,
            int maximumBuilderQueueDepth,
            int finalBuilderQueueDepth,
            int maximumBusyWorkerCount,
            int finalBusyWorkerCount,
            int maximumPendingResultCount,
            int finalPendingResultCount,
            long maximumPendingAgeNanos,
            long errorCount,
            long overflowCount
    ) {
    }

    static final class Recorder {
        private final BoundedLongCauseMap requestCauses;
        private final BoundedLongSet taskSections;
        private final BoundedLongSet outputSections;
        private final BoundedLongSet inPlaceSections;
        private final BoundedLongSet compactLightPatchSections;

        private volatile boolean active;
        private long epochId;
        private long rebuildRequestCount;
        private long lightRebuildRequestCount;
        private long geometryOrUnknownRebuildRequestCount;
        private long lightScopeMismatchCount;
        private long lightScopeFailureCount;
        private long rebuildTaskCount;
        private long buildOutputCount;
        private long acceptedMeshPayloadBytes;
        private long acceptedGeometryPayloadBytes;
        private long inPlaceGeometryRefreshOutputs;
        private long inPlaceGeometryRefreshBytes;
        private long inPlaceGeometryRefreshMeshCommands;
        private long compactLightPatchOutputs;
        private long geometryBytesElided;
        private long geometryMeshCommandsElided;
        private long nativeLightPatchDispatches;
        private long nativeLightPatchMeshCommands;
        private long compactLightPatchFallbackCount;
        private long sidecarProducedBytes;
        private long sidecarUploadedBytes;
        private long sidecarUploadCommands;
        private long sidecarResizeCopyBytes;
        private long sidecarResizeCopyCommands;
        private long sidecarFallbackCount;
        private int maximumBuilderQueueDepth;
        private int finalBuilderQueueDepth;
        private int maximumBusyWorkerCount;
        private int finalBusyWorkerCount;
        private int maximumPendingResultCount;
        private int finalPendingResultCount;
        private long maximumPendingAgeNanos;
        private long errorCount;
        private long overflowCount;

        Recorder(final int uniqueSectionLimit) {
            this.requestCauses = new BoundedLongCauseMap(uniqueSectionLimit);
            this.taskSections = new BoundedLongSet(uniqueSectionLimit);
            this.outputSections = new BoundedLongSet(uniqueSectionLimit);
            this.inPlaceSections = new BoundedLongSet(uniqueSectionLimit);
            this.compactLightPatchSections = new BoundedLongSet(uniqueSectionLimit);
        }

        void begin(final long newEpochId) {
            boolean replacedActiveEpoch = this.active;
            this.clear(newEpochId);
            if (replacedActiveEpoch) {
                this.errorCount = 1L;
            }
            this.active = true;
        }

        Snapshot end() {
            this.active = false;
            return this.snapshot();
        }

        void abort() {
            this.active = false;
            this.clear(0L);
        }

        Snapshot snapshot() {
            return new Snapshot(
                    this.active,
                    this.epochId,
                    this.rebuildRequestCount,
                    this.requestCauses.size(),
                    this.lightRebuildRequestCount,
                    this.geometryOrUnknownRebuildRequestCount,
                    this.requestCauses.countWithLight(),
                    this.requestCauses.countWithGeometryOrUnknown(),
                    this.requestCauses.countLightOnly(),
                    this.requestCauses.countMixed(),
                    this.lightScopeMismatchCount,
                    this.lightScopeFailureCount,
                    this.rebuildTaskCount,
                    this.taskSections.size(),
                    this.buildOutputCount,
                    this.outputSections.size(),
                    this.acceptedMeshPayloadBytes,
                    this.acceptedGeometryPayloadBytes,
                    this.inPlaceGeometryRefreshOutputs,
                    this.inPlaceSections.size(),
                    this.inPlaceGeometryRefreshBytes,
                    this.inPlaceGeometryRefreshMeshCommands,
                    this.compactLightPatchOutputs,
                    this.compactLightPatchSections.size(),
                    this.geometryBytesElided,
                    this.geometryMeshCommandsElided,
                    this.nativeLightPatchDispatches,
                    this.nativeLightPatchMeshCommands,
                    this.compactLightPatchFallbackCount,
                    this.sidecarProducedBytes,
                    this.sidecarUploadedBytes,
                    this.sidecarUploadCommands,
                    this.sidecarResizeCopyBytes,
                    this.sidecarResizeCopyCommands,
                    this.sidecarFallbackCount,
                    this.maximumBuilderQueueDepth,
                    this.finalBuilderQueueDepth,
                    this.maximumBusyWorkerCount,
                    this.finalBusyWorkerCount,
                    this.maximumPendingResultCount,
                    this.finalPendingResultCount,
                    this.maximumPendingAgeNanos,
                    this.errorCount,
                    this.overflowCount
            );
        }

        void recordRebuildRequest(final long sectionKey) {
            this.recordRebuildRequest(sectionKey, RebuildCause.GEOMETRY_OR_UNKNOWN);
        }

        void recordRebuildRequest(final long sectionKey, final RebuildCause cause) {
            if (!this.active) {
                return;
            }
            this.rebuildRequestCount = this.add(this.rebuildRequestCount, 1L);
            RebuildCause effectiveCause = cause;
            if (effectiveCause == null || effectiveCause == RebuildCause.NONE) {
                this.recordError();
                effectiveCause = RebuildCause.GEOMETRY_OR_UNKNOWN;
            }
            if (effectiveCause == RebuildCause.LIGHT_ONLY) {
                this.lightRebuildRequestCount = this.add(this.lightRebuildRequestCount, 1L);
            } else {
                this.geometryOrUnknownRebuildRequestCount = this.add(
                        this.geometryOrUnknownRebuildRequestCount,
                        1L
                );
            }
            if (this.requestCauses.merge(sectionKey, effectiveCause) == BoundedLongCauseMap.OVERFLOW) {
                this.recordOverflow();
            }
        }

        RebuildCause rebuildCause(final long sectionKey) {
            return this.requestCauses.cause(sectionKey);
        }

        void recordLightScopeMismatch() {
            if (!this.active) {
                return;
            }
            this.lightScopeMismatchCount = this.add(this.lightScopeMismatchCount, 1L);
            this.recordError();
        }

        void recordLightScopeFailure() {
            if (!this.active) {
                return;
            }
            this.lightScopeFailureCount = this.add(this.lightScopeFailureCount, 1L);
            this.recordError();
        }

        void recordRebuildTask(
                final long sectionKey,
                final long pendingAgeNanos
        ) {
            if (!this.active) {
                return;
            }
            this.rebuildTaskCount = this.add(this.rebuildTaskCount, 1L);
            this.recordUnique(this.taskSections, sectionKey);
            if (pendingAgeNanos < 0L) {
                this.recordError();
            } else {
                this.maximumPendingAgeNanos = Math.max(this.maximumPendingAgeNanos, pendingAgeNanos);
            }
        }

        void recordBuildOutput(final long sectionKey) {
            if (!this.active) {
                return;
            }
            this.buildOutputCount = this.add(this.buildOutputCount, 1L);
            this.recordUnique(this.outputSections, sectionKey);
        }

        void recordAcceptedMeshPayloadBytes(final long bytes) {
            if (!this.active) {
                return;
            }
            if (bytes < 0L) {
                this.recordError();
                return;
            }
            this.acceptedMeshPayloadBytes = this.add(this.acceptedMeshPayloadBytes, bytes);
        }

        void recordAcceptedGeometryPayloadBytes(final long bytes) {
            if (!this.active) {
                return;
            }
            if (bytes < 0L) {
                this.recordError();
                return;
            }
            this.acceptedGeometryPayloadBytes = this.add(this.acceptedGeometryPayloadBytes, bytes);
        }

        void recordInPlaceGeometryRefresh(
                final long sectionKey,
                final long bytes,
                final long meshCommands
        ) {
            if (!this.active) {
                return;
            }
            if (bytes < 0L || meshCommands < 0L) {
                this.recordError();
                return;
            }
            this.inPlaceGeometryRefreshOutputs = this.add(this.inPlaceGeometryRefreshOutputs, 1L);
            this.recordUnique(this.inPlaceSections, sectionKey);
            this.inPlaceGeometryRefreshBytes = this.add(this.inPlaceGeometryRefreshBytes, bytes);
            this.inPlaceGeometryRefreshMeshCommands = this.add(
                    this.inPlaceGeometryRefreshMeshCommands,
                    meshCommands
            );
        }

        boolean wasInPlaceGeometryRefreshed(final long sectionKey) {
            return this.inPlaceSections.contains(sectionKey);
        }

        void recordCompactLightPatchOutput(
                final long sectionKey,
                final long geometryBytes,
                final long meshCommands
        ) {
            if (!this.active) {
                return;
            }
            if (geometryBytes < 0L || meshCommands < 0L) {
                this.recordError();
                return;
            }
            this.compactLightPatchOutputs = this.add(this.compactLightPatchOutputs, 1L);
            this.recordUnique(this.compactLightPatchSections, sectionKey);
            this.geometryBytesElided = this.add(this.geometryBytesElided, geometryBytes);
            this.geometryMeshCommandsElided = this.add(
                    this.geometryMeshCommandsElided,
                    meshCommands
            );
        }

        boolean wasCompactLightPatched(final long sectionKey) {
            return this.compactLightPatchSections.contains(sectionKey);
        }

        void recordNativeLightPatch(final long dispatches, final long meshCommands) {
            if (!this.active) {
                return;
            }
            if (dispatches < 0L || meshCommands < 0L) {
                this.recordError();
                return;
            }
            this.nativeLightPatchDispatches = this.add(
                    this.nativeLightPatchDispatches,
                    dispatches
            );
            this.nativeLightPatchMeshCommands = this.add(
                    this.nativeLightPatchMeshCommands,
                    meshCommands
            );
        }

        void recordCompactLightPatchFallback() {
            if (!this.active) {
                return;
            }
            this.compactLightPatchFallbackCount = this.add(
                    this.compactLightPatchFallbackCount,
                    1L
            );
        }

        boolean wasBuildOutput(final long sectionKey) {
            return this.outputSections.contains(sectionKey);
        }

        void recordSidecarUpload(final long bytes, final long commands) {
            if (!this.active) {
                return;
            }
            if (bytes < 0L || commands < 0L) {
                this.recordError();
                return;
            }
            this.sidecarProducedBytes = this.add(this.sidecarProducedBytes, bytes);
            this.sidecarUploadedBytes = this.add(this.sidecarUploadedBytes, bytes);
            this.sidecarUploadCommands = this.add(this.sidecarUploadCommands, commands);
        }

        void recordSidecarResizeCopies(final long bytes, final long commands) {
            if (!this.active) {
                return;
            }
            if (bytes < 0L || commands < 0L) {
                this.recordError();
                return;
            }
            this.sidecarResizeCopyBytes = this.add(this.sidecarResizeCopyBytes, bytes);
            this.sidecarResizeCopyCommands = this.add(this.sidecarResizeCopyCommands, commands);
        }

        void recordSidecarFallback() {
            if (!this.active) {
                return;
            }
            this.sidecarFallbackCount = this.add(this.sidecarFallbackCount, 1L);
        }

        void recordBuilderWorkState(
                final int queuedJobs,
                final int busyWorkers,
                final int pendingResults
        ) {
            if (!this.active) {
                return;
            }
            if (queuedJobs < 0 || busyWorkers < 0 || pendingResults < 0) {
                this.recordError();
                return;
            }
            this.maximumBuilderQueueDepth = Math.max(this.maximumBuilderQueueDepth, queuedJobs);
            this.finalBuilderQueueDepth = queuedJobs;
            this.maximumBusyWorkerCount = Math.max(this.maximumBusyWorkerCount, busyWorkers);
            this.finalBusyWorkerCount = busyWorkers;
            this.maximumPendingResultCount = Math.max(this.maximumPendingResultCount, pendingResults);
            this.finalPendingResultCount = pendingResults;
        }

        void recordError() {
            if (!this.active) {
                return;
            }
            if (this.errorCount == Long.MAX_VALUE) {
                this.recordOverflow();
            } else {
                this.errorCount++;
            }
        }

        private void clear(final long newEpochId) {
            this.epochId = newEpochId;
            this.rebuildRequestCount = 0L;
            this.lightRebuildRequestCount = 0L;
            this.geometryOrUnknownRebuildRequestCount = 0L;
            this.lightScopeMismatchCount = 0L;
            this.lightScopeFailureCount = 0L;
            this.rebuildTaskCount = 0L;
            this.buildOutputCount = 0L;
            this.acceptedMeshPayloadBytes = 0L;
            this.acceptedGeometryPayloadBytes = 0L;
            this.inPlaceGeometryRefreshOutputs = 0L;
            this.inPlaceGeometryRefreshBytes = 0L;
            this.inPlaceGeometryRefreshMeshCommands = 0L;
            this.compactLightPatchOutputs = 0L;
            this.geometryBytesElided = 0L;
            this.geometryMeshCommandsElided = 0L;
            this.nativeLightPatchDispatches = 0L;
            this.nativeLightPatchMeshCommands = 0L;
            this.compactLightPatchFallbackCount = 0L;
            this.sidecarProducedBytes = 0L;
            this.sidecarUploadedBytes = 0L;
            this.sidecarUploadCommands = 0L;
            this.sidecarResizeCopyBytes = 0L;
            this.sidecarResizeCopyCommands = 0L;
            this.sidecarFallbackCount = 0L;
            this.maximumBuilderQueueDepth = 0;
            this.finalBuilderQueueDepth = 0;
            this.maximumBusyWorkerCount = 0;
            this.finalBusyWorkerCount = 0;
            this.maximumPendingResultCount = 0;
            this.finalPendingResultCount = 0;
            this.maximumPendingAgeNanos = 0L;
            this.errorCount = 0L;
            this.overflowCount = 0L;
            this.requestCauses.clear();
            this.taskSections.clear();
            this.outputSections.clear();
            this.inPlaceSections.clear();
            this.compactLightPatchSections.clear();
        }

        boolean isActive() {
            return this.active;
        }

        private void recordUnique(final BoundedLongSet sections, final long sectionKey) {
            int result = sections.add(sectionKey);
            if (result == BoundedLongSet.OVERFLOW) {
                this.recordOverflow();
            }
        }

        private long add(final long current, final long value) {
            if (Long.MAX_VALUE - current < value) {
                this.recordOverflow();
                return Long.MAX_VALUE;
            }
            return current + value;
        }

        private void recordOverflow() {
            if (this.overflowCount < Long.MAX_VALUE) {
                this.overflowCount++;
            }
        }
    }

    private static final class BoundedLongCauseMap {
        private static final int RECORDED = 1;
        private static final int OVERFLOW = -1;
        private static final int MAX_LIMIT = 1 << 20;
        private static final byte LIGHT = 1;
        private static final byte GEOMETRY_OR_UNKNOWN = 2;

        private final long[] keys;
        private final byte[] flags;
        private final byte[] occupied;
        private final int limit;
        private final int mask;
        private int size;

        BoundedLongCauseMap(final int limit) {
            if (limit <= 0 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("unique section limit must be between 1 and " + MAX_LIMIT);
            }
            int tableSize = 1;
            while (tableSize < limit * 2) {
                tableSize <<= 1;
            }
            this.keys = new long[tableSize];
            this.flags = new byte[tableSize];
            this.occupied = new byte[tableSize];
            this.limit = limit;
            this.mask = tableSize - 1;
        }

        int merge(final long key, final RebuildCause cause) {
            byte causeFlag = cause == RebuildCause.LIGHT_ONLY ? LIGHT : GEOMETRY_OR_UNKNOWN;
            int index = BoundedLongSet.mix(key) & this.mask;
            while (this.occupied[index] != 0) {
                if (this.keys[index] == key) {
                    this.flags[index] |= causeFlag;
                    return RECORDED;
                }
                index = index + 1 & this.mask;
            }
            if (this.size >= this.limit) {
                return OVERFLOW;
            }
            this.occupied[index] = 1;
            this.keys[index] = key;
            this.flags[index] = causeFlag;
            this.size++;
            return RECORDED;
        }

        RebuildCause cause(final long key) {
            int index = BoundedLongSet.mix(key) & this.mask;
            while (this.occupied[index] != 0) {
                if (this.keys[index] == key) {
                    return (this.flags[index] & GEOMETRY_OR_UNKNOWN) != 0
                            ? RebuildCause.GEOMETRY_OR_UNKNOWN
                            : RebuildCause.LIGHT_ONLY;
                }
                index = index + 1 & this.mask;
            }
            return RebuildCause.NONE;
        }

        int size() {
            return this.size;
        }

        int countWithLight() {
            return this.countFlags(LIGHT, LIGHT);
        }

        int countWithGeometryOrUnknown() {
            return this.countFlags(GEOMETRY_OR_UNKNOWN, GEOMETRY_OR_UNKNOWN);
        }

        int countLightOnly() {
            return this.countFlags((byte) (LIGHT | GEOMETRY_OR_UNKNOWN), LIGHT);
        }

        int countMixed() {
            return this.countFlags(
                    (byte) (LIGHT | GEOMETRY_OR_UNKNOWN),
                    (byte) (LIGHT | GEOMETRY_OR_UNKNOWN)
            );
        }

        private int countFlags(final byte mask, final byte expected) {
            int count = 0;
            for (int index = 0; index < this.occupied.length; index++) {
                if (this.occupied[index] != 0 && (this.flags[index] & mask) == expected) {
                    count++;
                }
            }
            return count;
        }

        void clear() {
            Arrays.fill(this.occupied, (byte) 0);
            this.size = 0;
        }
    }

    private static final class BoundedLongSet {
        private static final int DUPLICATE = 0;
        private static final int ADDED = 1;
        private static final int OVERFLOW = -1;
        private static final int MAX_LIMIT = 1 << 20;

        private final long[] keys;
        private final byte[] occupied;
        private final int limit;
        private final int mask;
        private int size;

        BoundedLongSet(final int limit) {
            if (limit <= 0 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("unique section limit must be between 1 and " + MAX_LIMIT);
            }
            int tableSize = 1;
            while (tableSize < limit * 2) {
                tableSize <<= 1;
            }
            this.keys = new long[tableSize];
            this.occupied = new byte[tableSize];
            this.limit = limit;
            this.mask = tableSize - 1;
        }

        int add(final long key) {
            int index = mix(key) & this.mask;
            while (this.occupied[index] != 0) {
                if (this.keys[index] == key) {
                    return DUPLICATE;
                }
                index = index + 1 & this.mask;
            }
            if (this.size >= this.limit) {
                return OVERFLOW;
            }
            this.occupied[index] = 1;
            this.keys[index] = key;
            this.size++;
            return ADDED;
        }

        int size() {
            return this.size;
        }

        boolean contains(final long key) {
            int index = mix(key) & this.mask;
            while (this.occupied[index] != 0) {
                if (this.keys[index] == key) {
                    return true;
                }
                index = index + 1 & this.mask;
            }
            return false;
        }

        void clear() {
            Arrays.fill(this.occupied, (byte) 0);
            this.size = 0;
        }

        private static int mix(final long value) {
            long mixed = value;
            mixed ^= mixed >>> 33;
            mixed *= 0xff51afd7ed558ccdl;
            mixed ^= mixed >>> 33;
            mixed *= 0xc4ceb9fe1a85ec53l;
            mixed ^= mixed >>> 33;
            return (int) mixed;
        }
    }
}
