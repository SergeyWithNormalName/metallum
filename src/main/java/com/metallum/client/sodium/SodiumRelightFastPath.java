package com.metallum.client.sodium;

import com.metallum.mixin.sodium.SodiumRelightBlockContextAccess;
import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fail-closed Sodium relight fast path.
 *
 * <p>The worker reconstructs a normal, complete {@link ChunkBuildOutput}. The
 * existing compact Metal upload can therefore patch only bytes 16/17, while
 * Sodium retains a complete ordinary upload fallback if that patch is rejected.</p>
 */
public final class SodiumRelightFastPath {
    public static final String ENVIRONMENT_VARIABLE = "METALLUM_SODIUM_RELIGHT_FAST_PATH";

    private static final boolean CONFIGURED = parseEnabled(System.getenv(ENVIRONMENT_VARIABLE))
            && !"1".equals(System.getenv(SodiumRelightOracle.ENVIRONMENT_VARIABLE))
            && parseEnabled(System.getenv(SodiumLightSidecar.ENVIRONMENT_VARIABLE))
            && parseEnabled(System.getenv(SodiumTerrainLightPatch.ENVIRONMENT_VARIABLE));
    private static final AtomicBoolean RUNTIME_ACTIVE = new AtomicBoolean(CONFIGURED);
    private static final Recorder RECORDER = new Recorder();

    private SodiumRelightFastPath() {
    }

    public static boolean isConfigured() {
        return CONFIGURED;
    }

    public static boolean isRuntimeActive() {
        return CONFIGURED
                && RUNTIME_ACTIVE.get()
                && SodiumLightSidecar.isRuntimeActive()
                && SodiumTerrainLightPatch.isRuntimeActive();
    }

    /** Render-thread preflight used only to isolate estimator samples. */
    public static boolean shouldUseFastTaskClass(
            final RenderSection section,
            final SodiumRelightTaskStamp stamp
    ) {
        if (!isRuntimeActive()
                || stamp == null
                || stamp.cause() != SodiumRelightRebuildCause.LIGHT_ONLY
                || stamp.forceSort()
                || !stamp.isolatedAtCreation()
                || section == null
                || section.isDisposed()
                || !section.isBuilt()
                || !(section instanceof SodiumRelightResidentPlanSlot residentSlot)
                || !(section instanceof SodiumTerrainUploadBaselineAccess baselineAccess)) {
            return false;
        }

        try (SodiumRelightResidentState.Lease lease = residentSlot.metallum$acquireRelightState()) {
            SodiumTerrainUploadBaseline baseline = baselineAccess.metallum$getTerrainUploadBaseline();
            return lease != null
                    && baseline != null
                    && baseline.matchesResidentMetadata(lease.generation(), lease.info())
                    && baseline.hasResidentStaticGeometry()
                    && lease.plan().topologySnapshot() != null
                    && hasOnlySupportedMeshes(baseline);
        } catch (Throwable throwable) {
            failRuntime();
            return false;
        }
    }

    /** Worker entry point. It never invokes the original task itself. */
    public static Attempt tryCreateOutput(
            final ChunkBuilderMeshingTask task,
            final ChunkBuildContext context,
            final CancellationToken cancellationToken
    ) {
        if (!(task instanceof SodiumRelightFastMeshingTask)
                || !(task instanceof SodiumRelightTaskAccess taskAccess)) {
            return Attempt.notAttempted();
        }
        if (!isRuntimeActive()) {
            RECORDER.recordFallback(Fallback.RUNTIME_INACTIVE);
            return Attempt.fallback();
        }

        SodiumRelightTaskStamp stamp = taskAccess.metallum$getRelightTaskStamp();
        if (stamp == null
                || stamp.cause() != SodiumRelightRebuildCause.LIGHT_ONLY
                || stamp.forceSort()
                || !stamp.isolatedAtCreation()) {
            RECORDER.recordFallback(Fallback.TASK_STAMP);
            return Attempt.fallback();
        }

        RenderSection section = task.getRenderSection();
        if (cancellationToken.isCancelled() || section.isDisposed()) {
            RECORDER.recordCancelled();
            return Attempt.cancelled();
        }
        if (!section.isBuilt()
                || !(section instanceof SodiumRelightResidentPlanSlot residentSlot)
                || !(section instanceof SodiumTerrainUploadBaselineAccess baselineAccess)
                || !(section instanceof SodiumRelightSectionTrackerSlot trackerSlot)) {
            RECORDER.recordFallback(Fallback.RESIDENT_STATE);
            return Attempt.fallback();
        }
        if (trackerSlot.metallum$getRelightGeometryEpoch() != stamp.geometryEpoch()) {
            RECORDER.recordFallback(Fallback.GENERATION);
            return Attempt.fallback();
        }

        SodiumRelightResidentState.Lease resident = null;
        IdentityHashMap<TerrainRenderPass, BuiltSectionMeshParts> meshes = new IdentityHashMap<>();
        ChunkBuildOutput output = null;
        try {
            resident = residentSlot.metallum$acquireRelightState();
            SodiumTerrainUploadBaseline baseline = baselineAccess.metallum$getTerrainUploadBaseline();
            if (resident == null
                    || baseline == null
                    || !baseline.matchesResidentMetadata(resident.generation(), resident.info())
                    || !baseline.hasResidentStaticGeometry()
                    || !hasOnlySupportedMeshes(baseline)) {
                RECORDER.recordFallback(Fallback.RESIDENT_STATE);
                return Attempt.fallback();
            }

            ChunkRenderContext renderContext = Objects.requireNonNull(
                    taskAccess.metallum$getRelightRenderContext(),
                    "relight render context"
            );
            if (!renderContext.getOrigin().equals(section.getPosition())) {
                RECORDER.recordFallback(Fallback.GENERATION);
                return Attempt.fallback();
            }

            context.cache.init(renderContext);
            if (cancellationToken.isCancelled() || section.isDisposed()) {
                RECORDER.recordCancelled();
                return Attempt.cancelled();
            }

            SodiumRelightPlan plan = resident.plan();
            SodiumRelightTopologySnapshot topology = plan.topologySnapshot();
            if (topology == null || !topology.matches(context.cache.getWorldSlice(), section)) {
                RECORDER.recordFallback(Fallback.TOPOLOGY);
                return Attempt.fallback();
            }

            SodiumRelightPlan.MeshLayout solidLayout = relightLayout(
                    baseline,
                    DefaultTerrainRenderPasses.SOLID
            );
            SodiumRelightPlan.MeshLayout cutoutLayout = relightLayout(
                    baseline,
                    DefaultTerrainRenderPasses.CUTOUT
            );
            LightPipelineProvider lightPipelines = ((SodiumRelightBlockContextAccess)
                    context.cache.getBlockRenderer()).metallum$getRelightLighters();
            SodiumRelightPlan.ReplayResult replayResult = plan.replay(
                    Objects.requireNonNull(lightPipelines, "relight light pipelines"),
                    solidLayout,
                    cutoutLayout,
                    null
            );
            if (!replayResult.accepted()) {
                RECORDER.recordFallback(Fallback.REPLAY);
                return Attempt.fallback();
            }

            SodiumRelightPlan.Replay replay = Objects.requireNonNull(
                    replayResult.replay(),
                    "accepted relight replay"
            );
            long createdBytes = reconstructMeshes(baseline, replay, meshes);
            if (createdBytes < 0L) {
                RECORDER.recordFallback(Fallback.RECONSTRUCTION);
                return Attempt.fallback();
            }
            if (cancellationToken.isCancelled() || section.isDisposed()) {
                RECORDER.recordCancelled();
                return Attempt.cancelled();
            }
            if (!isRuntimeActive()
                    || trackerSlot.metallum$getRelightGeometryEpoch() != stamp.geometryEpoch()
                    || baselineAccess.metallum$getTerrainUploadBaseline() != baseline) {
                RECORDER.recordFallback(Fallback.GENERATION);
                return Attempt.fallback();
            }

            BuiltSectionInfo info = resident.info();
            output = new ChunkBuildOutput(
                    section,
                    stamp.submitTime(),
                    null,
                    info,
                    meshes,
                    stamp.blockingTask()
            );
            ((SodiumRelightFastOutputSlot) output).metallum$markFastRelightOutput(
                    stamp.geometryEpoch(),
                    resident.generation(),
                    info,
                    baseline
            );
            RECORDER.recordCreated(createdBytes);
            ChunkBuildOutput completed = output;
            output = null;
            meshes = null;
            return Attempt.created(completed);
        } catch (Throwable throwable) {
            failRuntime();
            RECORDER.recordFallback(Fallback.STRUCTURAL_ERROR);
            return Attempt.fallback();
        } finally {
            if (resident != null) {
                resident.close();
            }
            if (output != null) {
                safeDestroy(output);
            } else if (meshes != null) {
                freeMeshes(meshes);
            }
        }
    }

    /** Render-thread validation immediately before Sodium arbitrates the output. */
    public static boolean isOutputCurrent(final ChunkBuildOutput output) {
        if (!(output instanceof SodiumRelightFastOutputSlot marker)
                || !marker.metallum$isFastRelightOutput()) {
            return true;
        }
        RenderSection section = output.section;
        if (section.isDisposed()
                || !(section instanceof SodiumRelightSectionTrackerSlot tracker)
                || !(section instanceof SodiumRelightResidentPlanSlot residentSlot)
                || !(section instanceof SodiumTerrainUploadBaselineAccess baselineAccess)
                || tracker.metallum$getRelightGeometryEpoch()
                != marker.metallum$getFastRelightGeometryEpoch()
                || baselineAccess.metallum$getTerrainUploadBaseline()
                != marker.metallum$getFastRelightResidentBaseline()) {
            RECORDER.recordGenerationMismatch();
            return false;
        }
        try (SodiumRelightResidentState.Lease lease = residentSlot.metallum$acquireRelightState()) {
            boolean current = lease != null
                    && lease.generation() == marker.metallum$getFastRelightResidentGeneration()
                    && lease.info() == marker.metallum$getFastRelightResidentInfo();
            if (!current) {
                RECORDER.recordGenerationMismatch();
            }
            return current;
        } catch (Throwable throwable) {
            failRuntime();
            return false;
        }
    }

    public static void beginObservation(final long epochId) {
        RECORDER.begin(epochId);
    }

    public static Snapshot endObservation() {
        return RECORDER.end();
    }

    public static void abortObservation() {
        RECORDER.abort();
    }

    public static Snapshot snapshot() {
        return RECORDER.snapshot();
    }

    public static void recordOriginalCall() {
        RECORDER.recordOriginalCall();
    }

    public static void recordAcceptedOutput(final ChunkBuildOutput output) {
        if (output instanceof SodiumRelightFastOutputSlot marker
                && marker.metallum$isFastRelightOutput()) {
            marker.metallum$markFastRelightAccepted();
            RECORDER.recordAccepted();
        }
    }

    public static void recordDestroyedOutput(final boolean accepted) {
        if (!accepted) {
            RECORDER.recordStaleOrDisposed();
        }
    }

    public static void recordCompactCommit() {
        RECORDER.recordCompactCommit();
    }

    public static void recordFullUploadCommit() {
        RECORDER.recordFullUploadCommit();
    }

    public static void recordForcedRebuild() {
        RECORDER.recordForcedRebuild();
    }

    private static long reconstructMeshes(
            final SodiumTerrainUploadBaseline baseline,
            final SodiumRelightPlan.Replay replay,
            final IdentityHashMap<TerrainRenderPass, BuiltSectionMeshParts> meshes
    ) {
        long createdBytes = 0L;
        TerrainRenderPass[] passes = DefaultTerrainRenderPasses.ALL;
        for (int index = 0; index < passes.length; index++) {
            TerrainRenderPass pass = passes[index];
            SodiumTerrainMeshLayout layout = baseline.mesh(index);
            SodiumRelightPlan.Pass relightPass;
            if (pass == DefaultTerrainRenderPasses.SOLID) {
                relightPass = SodiumRelightPlan.Pass.SOLID;
            } else if (pass == DefaultTerrainRenderPasses.CUTOUT) {
                relightPass = SodiumRelightPlan.Pass.CUTOUT;
            } else {
                if (layout != null) {
                    return -1L;
                }
                continue;
            }
            byte[] light = replay.copyLightBytes(relightPass);
            if (layout == null) {
                if (light.length != 0) {
                    return -1L;
                }
                continue;
            }

            NativeBuffer buffer = new NativeBuffer(layout.geometryBytes());
            boolean retained = false;
            try {
                ByteBuffer destination = buffer.getDirectBuffer();
                if (!baseline.reconstructGeometry(index, ByteBuffer.wrap(light), destination)
                        || destination.position() != layout.geometryBytes()) {
                    return -1L;
                }
                meshes.put(pass, new BuiltSectionMeshParts(buffer, layout.vertexSegments()));
                retained = true;
                createdBytes = Math.addExact(createdBytes, layout.geometryBytes());
            } finally {
                if (!retained) {
                    buffer.free();
                }
            }
        }
        return meshes.isEmpty() ? -1L : createdBytes;
    }

    private static SodiumRelightPlan.@Nullable MeshLayout relightLayout(
            final SodiumTerrainUploadBaseline baseline,
            final TerrainRenderPass target
    ) {
        TerrainRenderPass[] passes = DefaultTerrainRenderPasses.ALL;
        for (int index = 0; index < passes.length; index++) {
            if (passes[index] != target) {
                continue;
            }
            SodiumTerrainMeshLayout layout = baseline.mesh(index);
            return layout == null ? null : SodiumRelightPlan.MeshLayout.of(
                    layout.geometryBytes(),
                    layout.vertexSegments()
            );
        }
        throw new IllegalStateException("Sodium terrain pass is absent from ALL");
    }

    private static boolean hasOnlySupportedMeshes(final SodiumTerrainUploadBaseline baseline) {
        TerrainRenderPass[] passes = DefaultTerrainRenderPasses.ALL;
        if (baseline.meshCount() != passes.length) {
            return false;
        }
        boolean renderable = false;
        for (int index = 0; index < passes.length; index++) {
            SodiumTerrainMeshLayout layout = baseline.mesh(index);
            TerrainRenderPass pass = passes[index];
            if (pass == DefaultTerrainRenderPasses.SOLID
                    || pass == DefaultTerrainRenderPasses.CUTOUT) {
                renderable |= layout != null;
            } else if (layout != null) {
                return false;
            }
        }
        return renderable;
    }

    private static void freeMeshes(final Map<TerrainRenderPass, BuiltSectionMeshParts> meshes) {
        for (BuiltSectionMeshParts mesh : meshes.values()) {
            try {
                mesh.getVertexData().free();
            } catch (Throwable throwable) {
                failRuntime();
            }
        }
        meshes.clear();
    }

    private static void safeDestroy(final ChunkBuildOutput output) {
        try {
            output.destroy();
        } catch (Throwable throwable) {
            failRuntime();
        }
    }

    private static void failRuntime() {
        RUNTIME_ACTIVE.set(false);
        RECORDER.recordError();
    }

    private static boolean parseEnabled(@Nullable final String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    public enum AttemptStatus {
        NOT_ATTEMPTED,
        CREATED,
        FALLBACK,
        CANCELLED
    }

    public record Attempt(AttemptStatus status, @Nullable ChunkBuildOutput output) {
        private static Attempt notAttempted() {
            return new Attempt(AttemptStatus.NOT_ATTEMPTED, null);
        }

        private static Attempt created(final ChunkBuildOutput output) {
            return new Attempt(AttemptStatus.CREATED, Objects.requireNonNull(output, "output"));
        }

        private static Attempt fallback() {
            return new Attempt(AttemptStatus.FALLBACK, null);
        }

        private static Attempt cancelled() {
            return new Attempt(AttemptStatus.CANCELLED, null);
        }
    }

    enum Fallback {
        RUNTIME_INACTIVE,
        TASK_STAMP,
        RESIDENT_STATE,
        GENERATION,
        TOPOLOGY,
        REPLAY,
        RECONSTRUCTION,
        STRUCTURAL_ERROR
    }

    public record Snapshot(
            boolean configured,
            boolean active,
            long epochId,
            long taskDecisions,
            long fastOutputsCreated,
            long fallbackToOriginal,
            long cancelledTasks,
            long originalCalls,
            long acceptedOutputs,
            long staleOrDisposedOutputs,
            long compactCommits,
            long fullUploadCommits,
            long forcedRebuilds,
            long generationMismatches,
            long topologyFallbacks,
            long replayFallbacks,
            long reconstructionFallbacks,
            long createdGeometryBytes,
            long errors
    ) {
    }

    static final class Recorder {
        private boolean observing;
        private long epochId;
        private long taskDecisions;
        private long fastOutputsCreated;
        private long fallbackToOriginal;
        private long cancelledTasks;
        private long originalCalls;
        private long acceptedOutputs;
        private long staleOrDisposedOutputs;
        private long compactCommits;
        private long fullUploadCommits;
        private long forcedRebuilds;
        private long generationMismatches;
        private long topologyFallbacks;
        private long replayFallbacks;
        private long reconstructionFallbacks;
        private long createdGeometryBytes;
        private long errors;

        synchronized void begin(final long newEpochId) {
            this.clear(newEpochId);
            this.observing = true;
        }

        synchronized Snapshot end() {
            this.observing = false;
            return this.snapshot();
        }

        synchronized void abort() {
            this.observing = false;
            this.clear(0L);
        }

        synchronized Snapshot snapshot() {
            return new Snapshot(
                    CONFIGURED,
                    isRuntimeActive(),
                    this.epochId,
                    this.taskDecisions,
                    this.fastOutputsCreated,
                    this.fallbackToOriginal,
                    this.cancelledTasks,
                    this.originalCalls,
                    this.acceptedOutputs,
                    this.staleOrDisposedOutputs,
                    this.compactCommits,
                    this.fullUploadCommits,
                    this.forcedRebuilds,
                    this.generationMismatches,
                    this.topologyFallbacks,
                    this.replayFallbacks,
                    this.reconstructionFallbacks,
                    this.createdGeometryBytes,
                    this.errors
            );
        }

        synchronized void recordCreated(final long bytes) {
            if (!this.observing) {
                return;
            }
            this.taskDecisions++;
            this.fastOutputsCreated++;
            this.createdGeometryBytes = Math.addExact(this.createdGeometryBytes, bytes);
        }

        synchronized void recordFallback(final Fallback fallback) {
            if (!this.observing) {
                return;
            }
            this.taskDecisions++;
            this.fallbackToOriginal++;
            if (fallback == Fallback.TOPOLOGY) {
                this.topologyFallbacks++;
            } else if (fallback == Fallback.REPLAY) {
                this.replayFallbacks++;
            } else if (fallback == Fallback.RECONSTRUCTION) {
                this.reconstructionFallbacks++;
            }
        }

        synchronized void recordCancelled() {
            if (this.observing) {
                this.taskDecisions++;
                this.cancelledTasks++;
            }
        }

        synchronized void recordOriginalCall() {
            if (this.observing) {
                this.originalCalls++;
            }
        }

        synchronized void recordAccepted() {
            if (this.observing) {
                this.acceptedOutputs++;
            }
        }

        synchronized void recordStaleOrDisposed() {
            if (this.observing) {
                this.staleOrDisposedOutputs++;
            }
        }

        synchronized void recordCompactCommit() {
            if (this.observing) {
                this.compactCommits++;
            }
        }

        synchronized void recordFullUploadCommit() {
            if (this.observing) {
                this.fullUploadCommits++;
            }
        }

        synchronized void recordForcedRebuild() {
            if (this.observing) {
                this.forcedRebuilds++;
            }
        }

        synchronized void recordGenerationMismatch() {
            if (this.observing) {
                this.generationMismatches++;
            }
        }

        synchronized void recordError() {
            if (this.observing) {
                this.errors++;
            }
        }

        private void clear(final long newEpochId) {
            this.epochId = newEpochId;
            this.taskDecisions = 0L;
            this.fastOutputsCreated = 0L;
            this.fallbackToOriginal = 0L;
            this.cancelledTasks = 0L;
            this.originalCalls = 0L;
            this.acceptedOutputs = 0L;
            this.staleOrDisposedOutputs = 0L;
            this.compactCommits = 0L;
            this.fullUploadCommits = 0L;
            this.forcedRebuilds = 0L;
            this.generationMismatches = 0L;
            this.topologyFallbacks = 0L;
            this.replayFallbacks = 0L;
            this.reconstructionFallbacks = 0L;
            this.createdGeometryBytes = 0L;
            this.errors = 0L;
        }
    }
}
