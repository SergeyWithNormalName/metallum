package com.metallum.client.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.metallum.client.benchmark.TorchEpochTelemetry;
import com.metallum.mixin.sodium.SodiumRelightBlockContextAccess;
import net.caffeinemc.mods.sodium.client.model.light.LightMode;
import net.caffeinemc.mods.sodium.client.model.light.LightPipelineProvider;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.model.SodiumShadeMode;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exact-version, diagnostic oracle for a future Sodium light-only rebuild.
 *
 * <p>The oracle always invokes Sodium's original meshing task exactly once. It
 * captures an immutable recipe candidate, replays the previously published
 * candidate, and compares that replay with the mandatory full-remesh output.
 * It never exposes an API capable of skipping the full remesh.</p>
 */
public final class SodiumRelightOracle {
    public static final String ENVIRONMENT_VARIABLE = "METALLUM_SODIUM_RELIGHT_ORACLE";

    private static final boolean CONFIGURED = parseEnabled(System.getenv(ENVIRONMENT_VARIABLE));
    private static final AtomicBoolean RUNTIME_ACTIVE = new AtomicBoolean(CONFIGURED);
    private static final SodiumRelightPlanCache PLAN_CACHE = new SodiumRelightPlanCache(
            SodiumRelightPlanCache.DEFAULT_CAPACITY_BYTES
    );
    private static final ScopedValue<TaskSession> CURRENT_TASK = ScopedValue.newInstance();
    private static final ObservationRecorder OBSERVATION = new ObservationRecorder();
    private static final Scope NO_OP_SCOPE = () -> { };

    private SodiumRelightOracle() {
    }

    public static boolean isConfigured() {
        return CONFIGURED;
    }

    public static boolean isRuntimeActive() {
        return CONFIGURED && RUNTIME_ACTIVE.get();
    }

    /** Starts a counter window without clearing cached or resident plans. */
    public static void beginObservation(final long epochId) {
        try {
            OBSERVATION.begin(epochId);
        } catch (Throwable throwable) {
            failRuntime();
        }
    }

    /** Ends only the counter window; the returned runtime-active bit remains stable. */
    public static Snapshot endObservation() {
        try {
            return OBSERVATION.end();
        } catch (Throwable throwable) {
            failRuntime();
            return fallbackSnapshot();
        }
    }

    public static Snapshot snapshot() {
        try {
            return OBSERVATION.snapshot();
        } catch (Throwable throwable) {
            failRuntime();
            return fallbackSnapshot();
        }
    }

    /** Aborts and zeros only the current counter window. */
    public static void abortObservation() {
        try {
            OBSERVATION.abort();
        } catch (Throwable throwable) {
            failRuntime();
        }
    }

    /** Releases all unpublished and resident plan-cache storage. */
    public static void releaseAll() {
        try {
            PLAN_CACHE.clear();
        } catch (Throwable throwable) {
            failRuntime();
        }
    }

    /** Called after an accepted candidate is transferred into a render section. */
    public static void recordPublishedCandidate() {
        safeRecord(ObservationRecorder::recordPublishedCandidate);
    }

    /** Called whenever a candidate is closed without resident publication. */
    public static void recordDiscardedCandidate() {
        safeRecord(ObservationRecorder::recordDiscardedCandidate);
    }

    /** Called for the stale-output subset of discarded candidates. */
    public static void recordStaleCandidate() {
        safeRecord(ObservationRecorder::recordStaleCandidate);
    }

    /** Records a structural oracle/lifecycle failure and disables further capture. */
    public static void recordError() {
        safeRecord(ObservationRecorder::recordError);
        RUNTIME_ACTIVE.set(false);
    }

    /**
     * Runs the mandatory Sodium full-remesh exactly once and observes its output.
     * Oracle failures are swallowed; failures thrown by the original task are
     * propagated unchanged.
     */
    @Nullable
    public static ChunkBuildOutput executeMeshingTask(
            final ChunkBuilderMeshingTask task,
            final ChunkBuildContext context,
            final CancellationToken cancellationToken,
            final Operation<ChunkBuildOutput> original
    ) {
        if (!isRuntimeActive()) {
            return original.call(context, cancellationToken);
        }

        TaskSession session;
        try {
            session = createSession(task);
        } catch (Throwable throwable) {
            failRuntime();
            return original.call(context, cancellationToken);
        }
        if (session == null) {
            return original.call(context, cancellationToken);
        }

        OriginalCall call = new OriginalCall(original, context, cancellationToken);
        try {
            ScopedValue.where(CURRENT_TASK, session).run(call);
        } catch (Throwable throwable) {
            safeClose(session);
            if (!call.invoked) {
                failRuntime();
                return original.call(context, cancellationToken);
            }
            if (!call.completed) {
                return rethrow(throwable);
            }
            failRuntime();
            return call.output;
        }

        try {
            if (call.output != null) {
                session.finish(call.output);
            }
        } catch (Throwable throwable) {
            session.reject("task finalization failed", true);
        } finally {
            safeClose(session);
        }
        return call.output;
    }

    /** Opens the exact outer BlockRenderer.renderModel scope. */
    public static Scope openBlockModelScope() {
        TaskSession session = currentTask();
        return session == null ? NO_OP_SCOPE : session.openBlockModelScope();
    }

    /** Opens the exact Fabric vanilla model-part encoder scope. */
    public static Scope openVanillaModelPartScope() {
        TaskSession session = currentTask();
        return session == null ? NO_OP_SCOPE : session.openVanillaModelPartScope();
    }

    /** Captures the unmodified quad inputs immediately before Sodium shades it. */
    public static void beforeShade(
            final BlockRenderer renderer,
            final MutableQuadViewImpl quad,
            final LightMode lightMode,
            final boolean emissive,
            final SodiumShadeMode shadeMode
    ) {
        TaskSession session = currentTask();
        if (session == null) {
            return;
        }
        try {
            session.beforeShade(renderer, quad, lightMode, emissive, shadeMode);
        } catch (Throwable throwable) {
            session.reject("quad shade capture failed", true);
        }
    }

    /** Associates the captured quad with Sodium's actual pass and facing. */
    public static void bufferQuad(
            final BlockRenderer renderer,
            final MutableQuadViewImpl quad,
            final Material material
    ) {
        TaskSession session = currentTask();
        if (session == null) {
            return;
        }
        try {
            session.bufferQuad(renderer, quad, material);
        } catch (Throwable throwable) {
            session.reject("quad buffer capture failed", true);
        }
    }

    /** Nonthrowing fallback used by capture mixins after a locally caught failure. */
    public static void rejectCurrentTask(final String reason, final RuntimeException failure) {
        try {
            TaskSession session = currentTask();
            if (session != null) {
                session.reject(reason, true);
            } else {
                failRuntime();
            }
        } catch (Throwable throwable) {
            failRuntime();
        }
    }

    @Nullable
    private static TaskSession createSession(final ChunkBuilderMeshingTask task) {
        Objects.requireNonNull(task, "task");
        RenderSection section = Objects.requireNonNull(task.getRenderSection(), "render section");
        if (!(section instanceof SodiumRelightResidentPlanSlot residentSlot)
                || !(section instanceof SodiumTerrainUploadBaselineAccess baselineAccess)) {
            failRuntime();
            return null;
        }

        long sectionKey = SectionPos.asLong(
                section.getChunkX(),
                section.getChunkY(),
                section.getChunkZ()
        );
        TorchEpochTelemetry.RebuildCause cause = TorchEpochTelemetry.isActive()
                ? TorchEpochTelemetry.rebuildCause(sectionKey)
                : TorchEpochTelemetry.RebuildCause.NONE;
        if (cause == null) {
            cause = TorchEpochTelemetry.RebuildCause.NONE;
        }

        SodiumRelightPlanCache.Lease previous = null;
        try {
            if (cause == TorchEpochTelemetry.RebuildCause.LIGHT_ONLY) {
                previous = residentSlot.metallum$acquireRelightPlan();
            }
            SodiumTerrainUploadBaseline baseline = baselineAccess.metallum$getTerrainUploadBaseline();
            TaskSession session = new TaskSession(section, cause, previous, baseline);
            previous = null;
            OBSERVATION.recordTask(cause == TorchEpochTelemetry.RebuildCause.LIGHT_ONLY);
            return session;
        } finally {
            if (previous != null) {
                previous.close();
            }
        }
    }

    @Nullable
    private static TaskSession currentTask() {
        if (!isRuntimeActive() || !CURRENT_TASK.isBound()) {
            return null;
        }
        return CURRENT_TASK.get();
    }

    private static void safeClose(final TaskSession session) {
        try {
            session.close();
        } catch (Throwable throwable) {
            failRuntime();
        }
    }

    private static void safeRecord(final RecorderAction action) {
        try {
            action.record(OBSERVATION);
        } catch (Throwable throwable) {
            RUNTIME_ACTIVE.set(false);
        }
    }

    private static void failRuntime() {
        try {
            OBSERVATION.recordError();
        } catch (Throwable ignored) {
            // The diagnostic oracle must never affect the renderer.
        }
        RUNTIME_ACTIVE.set(false);
    }

    private static Snapshot fallbackSnapshot() {
        return new Snapshot(
                CONFIGURED,
                false,
                0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1L,
                0L,
                PLAN_CACHE.snapshot()
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T rethrow(final Throwable throwable) {
        return SodiumRelightOracle.<RuntimeException, T>throwUnchecked(throwable);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T throwUnchecked(final Throwable throwable) throws E {
        throw (E) throwable;
    }

    private static boolean parseEnabled(@Nullable final String value) {
        return "1".equals(value);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final class OriginalCall implements Runnable {
        private final Operation<ChunkBuildOutput> original;
        private final ChunkBuildContext context;
        private final CancellationToken cancellationToken;
        private boolean invoked;
        private boolean completed;
        @Nullable
        private ChunkBuildOutput output;

        private OriginalCall(
                final Operation<ChunkBuildOutput> original,
                final ChunkBuildContext context,
                final CancellationToken cancellationToken
        ) {
            this.original = Objects.requireNonNull(original, "original");
            this.context = context;
            this.cancellationToken = cancellationToken;
        }

        @Override
        public void run() {
            this.invoked = true;
            this.output = this.original.call(this.context, this.cancellationToken);
            this.completed = true;
        }
    }

    private static final class TaskSession implements AutoCloseable {
        private final Thread ownerThread = Thread.currentThread();
        private final RenderSection section;
        private final TorchEpochTelemetry.RebuildCause cause;
        private SodiumRelightPlanCache.@Nullable Lease previousPlan;
        @Nullable
        private final SodiumTerrainUploadBaseline residentBaseline;
        private final SodiumRelightPlan.Builder builder = new SodiumRelightPlan.Builder();
        @Nullable
        private LightPipelineProvider lightPipelines;
        @Nullable
        private PendingQuad pendingQuad;
        @Nullable
        private String rejectionReason;
        private int blockModelDepth;
        private int vanillaModelPartDepth;
        private boolean scopeFailureRecorded;
        private boolean rejectionRecorded;
        private boolean closed;

        private TaskSession(
                final RenderSection section,
                final TorchEpochTelemetry.RebuildCause cause,
                final SodiumRelightPlanCache.@Nullable Lease previousPlan,
                @Nullable final SodiumTerrainUploadBaseline residentBaseline
        ) {
            this.section = section;
            this.cause = cause;
            this.previousPlan = previousPlan;
            this.residentBaseline = residentBaseline;
        }

        private Scope openBlockModelScope() {
            if (!this.checkThread() || this.closed) {
                this.scopeFailure("block model scope opened outside its worker task");
                return NO_OP_SCOPE;
            }
            if (this.blockModelDepth != 0 || this.vanillaModelPartDepth != 0 || this.pendingQuad != null) {
                this.scopeFailure("nested or dirty block model scope");
                return NO_OP_SCOPE;
            }
            this.blockModelDepth = 1;
            return new SessionScope(this, ScopeKind.BLOCK_MODEL);
        }

        private Scope openVanillaModelPartScope() {
            if (!this.checkThread() || this.closed || this.blockModelDepth != 1) {
                this.scopeFailure("vanilla model-part scope outside one block model");
                return NO_OP_SCOPE;
            }
            if (this.vanillaModelPartDepth != 0 || this.pendingQuad != null) {
                this.scopeFailure("nested or dirty vanilla model-part scope");
                return NO_OP_SCOPE;
            }
            this.vanillaModelPartDepth = 1;
            return new SessionScope(this, ScopeKind.VANILLA_MODEL_PART);
        }

        private void closeScope(final ScopeKind kind) {
            if (!this.checkThread() || this.closed) {
                this.scopeFailure("scope closed outside its worker task");
                return;
            }
            if (kind == ScopeKind.VANILLA_MODEL_PART) {
                if (this.vanillaModelPartDepth != 1 || this.pendingQuad != null) {
                    this.scopeFailure("invalid vanilla model-part scope close");
                    return;
                }
                this.vanillaModelPartDepth = 0;
                return;
            }
            if (this.blockModelDepth != 1
                    || this.vanillaModelPartDepth != 0
                    || this.pendingQuad != null) {
                this.scopeFailure("invalid block model scope close");
                return;
            }
            this.blockModelDepth = 0;
        }

        private void beforeShade(
                final BlockRenderer renderer,
                final MutableQuadViewImpl quad,
                final LightMode lightMode,
                final boolean emissive,
                final SodiumShadeMode shadeMode
        ) {
            if (!this.checkThread() || this.closed || this.blockModelDepth != 1) {
                this.scopeFailure("quad shade outside one block model");
                return;
            }
            if (this.vanillaModelPartDepth != 1) {
                this.reject("non-vanilla block quad is not replay-safe", false);
                return;
            }
            if (this.pendingQuad != null) {
                this.scopeFailure("quad shade capture overlapped a pending quad");
                return;
            }

            SodiumRelightBlockContextAccess access = (SodiumRelightBlockContextAccess) renderer;
            BlockPos position = Objects.requireNonNull(
                    access.metallum$getRelightPosition(),
                    "relight block position"
            );
            LightPipelineProvider provider = Objects.requireNonNull(
                    access.metallum$getRelightLighters(),
                    "relight light pipelines"
            );
            if (this.lightPipelines != null && this.lightPipelines != provider) {
                this.scopeFailure("meshing task changed its light pipeline provider");
                return;
            }
            this.lightPipelines = provider;
            SodiumRelightQuadRecipe recipe = SodiumRelightQuadRecipe.capture(
                    quad,
                    position,
                    quad.getCullFace(),
                    quad.getLightFace(),
                    Objects.requireNonNull(lightMode, "light mode"),
                    quad.hasShade(),
                    shadeMode == SodiumShadeMode.ENHANCED,
                    emissive
            );
            this.pendingQuad = new PendingQuad(renderer, quad, recipe);
        }

        private void bufferQuad(
                final BlockRenderer renderer,
                final MutableQuadViewImpl quad,
                final Material material
        ) {
            if (!this.checkThread() || this.closed
                    || this.blockModelDepth != 1
                    || this.vanillaModelPartDepth != 1) {
                this.scopeFailure("quad buffer outside one vanilla model-part scope");
                return;
            }
            PendingQuad pending = this.pendingQuad;
            this.pendingQuad = null;
            if (pending == null || pending.renderer() != renderer || pending.quad() != quad) {
                this.scopeFailure("quad buffer did not match its shade capture");
                return;
            }
            ModelQuadFacing facing = Objects.requireNonNull(quad.normalFace(), "quad facing");
            int previousRejection = this.rejectionReason == null ? 0 : 1;
            this.builder.add(Objects.requireNonNull(material, "material"), facing, pending.recipe());
            OBSERVATION.recordCapturedQuad();
            if (previousRejection == 0 && material.pass != DefaultTerrainRenderPasses.SOLID
                    && material.pass != DefaultTerrainRenderPasses.CUTOUT) {
                this.reject("unsupported terrain pass", false);
            }
        }

        private void finish(final ChunkBuildOutput output) {
            if (!this.checkThread() || this.closed || output.section != this.section) {
                this.scopeFailure("task output did not match its capture session");
                return;
            }
            if (this.blockModelDepth != 0 || this.vanillaModelPartDepth != 0 || this.pendingQuad != null) {
                this.scopeFailure("task completed with an open capture scope");
                return;
            }
            if (!(output instanceof SodiumRelightCandidateSlot candidateSlot)) {
                this.reject("task output lacks candidate ownership", true);
                return;
            }

            BuiltSectionMeshParts solid = output.getMesh(DefaultTerrainRenderPasses.SOLID);
            BuiltSectionMeshParts cutout = output.getMesh(DefaultTerrainRenderPasses.CUTOUT);
            BuiltSectionMeshParts translucent = output.getMesh(DefaultTerrainRenderPasses.TRANSLUCENT);
            SodiumRelightPlan.BuildResult build = this.builder.buildFromMeshes(solid, cutout, translucent);
            if (this.rejectionReason != null || !build.accepted()) {
                this.reject(
                        this.rejectionReason == null ? build.rejectionReason() : this.rejectionReason,
                        false
                );
                return;
            }

            SodiumRelightPlan plan = Objects.requireNonNull(build.plan(), "accepted relight plan");
            SodiumRelightPlanCache.Owner candidate = PLAN_CACHE.capture(plan);
            if (!candidate.isResident()) {
                candidate.close();
                OBSERVATION.recordDiscardedCandidate();
                this.reject("relight plan cache rejected candidate", false);
                return;
            }
            boolean transferred = false;
            try {
                candidateSlot.metallum$setRelightCandidate(candidate);
                transferred = true;
                OBSERVATION.recordCapturedPlan(plan.quadCount());
            } finally {
                if (!transferred) {
                    candidate.close();
                    OBSERVATION.recordDiscardedCandidate();
                }
            }

            if (this.cause == TorchEpochTelemetry.RebuildCause.LIGHT_ONLY) {
                this.comparePreviousPlan(output, solid, cutout, translucent);
            }
        }

        private void comparePreviousPlan(
                final ChunkBuildOutput output,
                @Nullable final BuiltSectionMeshParts solid,
                @Nullable final BuiltSectionMeshParts cutout,
                @Nullable final BuiltSectionMeshParts translucent
        ) {
            SodiumRelightPlanCache.Lease previous = this.previousPlan;
            if (previous == null) {
                this.reject("light-only task has no resident previous plan", false);
                return;
            }
            SodiumTerrainUploadBaseline baseline = this.residentBaseline;
            if (baseline == null || !baseline.hasResidentStaticGeometry()) {
                OBSERVATION.recordStaticShadowRejection();
                this.reject("resident static terrain shadow is unavailable", false);
                return;
            }
            LightPipelineProvider provider = this.lightPipelines;
            if (provider == null) {
                this.reject("meshing task captured no light pipeline provider", false);
                return;
            }

            ByteBuffer[] currentGeometry = geometryByPass(output);
            boolean staticMatches = baseline.matchesStaticGeometry(currentGeometry);
            OBSERVATION.recordReplayAttempt();
            SodiumRelightPlan.ReplayResult replayResult = previous.plan().replayFromMeshes(
                    provider,
                    solid,
                    cutout,
                    translucent
            );
            if (!replayResult.accepted()) {
                this.reject("previous relight plan replay rejected: " + replayResult.rejectionReason(), false);
                return;
            }

            SodiumRelightPlan.Replay replay = Objects.requireNonNull(
                    replayResult.replay(),
                    "accepted relight replay"
            );
            boolean byteMatches = comparePass(replay, SodiumRelightPlan.Pass.SOLID, solid)
                    && comparePass(replay, SodiumRelightPlan.Pass.CUTOUT, cutout);
            if (staticMatches && byteMatches) {
                OBSERVATION.recordReplayMatch();
                return;
            }
            OBSERVATION.recordMismatchedTask();
            if (!staticMatches) {
                OBSERVATION.recordStaticShadowMismatch();
            }
            if (!byteMatches) {
                OBSERVATION.recordByteMismatch();
            }
        }

        private void reject(final String reason, final boolean error) {
            if (this.rejectionReason == null) {
                this.rejectionReason = reason == null ? "unspecified oracle rejection" : reason;
            }
            if (!this.rejectionRecorded) {
                this.rejectionRecorded = true;
                OBSERVATION.recordRejectedTask();
            }
            if (error) {
                failRuntime();
            }
        }

        private void scopeFailure(final String reason) {
            this.reject(reason, false);
            if (!this.scopeFailureRecorded) {
                this.scopeFailureRecorded = true;
                OBSERVATION.recordScopeFailure();
            }
        }

        private boolean checkThread() {
            return Thread.currentThread() == this.ownerThread;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            SodiumRelightPlanCache.Lease previous = this.previousPlan;
            this.previousPlan = null;
            if (previous != null) {
                previous.close();
            }
        }
    }

    private static boolean comparePass(
            final SodiumRelightPlan.Replay replay,
            final SodiumRelightPlan.Pass pass,
            @Nullable final BuiltSectionMeshParts mesh
    ) {
        if (mesh == null) {
            return replay.vertexCount(pass) == 0;
        }
        ByteBuffer geometry = mesh.getVertexData().getDirectBuffer().duplicate();
        return replay.compare(pass, geometry).matches();
    }

    private static ByteBuffer[] geometryByPass(final ChunkBuildOutput output) {
        TerrainRenderPass[] passes = DefaultTerrainRenderPasses.ALL;
        ByteBuffer[] geometry = new ByteBuffer[passes.length];
        for (int index = 0; index < passes.length; index++) {
            BuiltSectionMeshParts mesh = output.getMesh(passes[index]);
            if (mesh != null) {
                geometry[index] = mesh.getVertexData().getDirectBuffer().duplicate();
            }
        }
        return geometry;
    }

    private record PendingQuad(
            BlockRenderer renderer,
            MutableQuadViewImpl quad,
            SodiumRelightQuadRecipe recipe
    ) {
    }

    private enum ScopeKind {
        BLOCK_MODEL,
        VANILLA_MODEL_PART
    }

    private static final class SessionScope implements Scope {
        private final TaskSession session;
        private final ScopeKind kind;
        private boolean closed;

        private SessionScope(final TaskSession session, final ScopeKind kind) {
            this.session = session;
            this.kind = kind;
        }

        @Override
        public void close() {
            if (this.closed) {
                this.session.scopeFailure("capture scope was closed twice");
                return;
            }
            this.closed = true;
            this.session.closeScope(this.kind);
        }
    }

    public record Snapshot(
            boolean configured,
            boolean active,
            long epochId,
            long tasks,
            long lightOnlyTasks,
            long capturedPlans,
            long capturedQuads,
            long replayAttempts,
            long replayMatches,
            long mismatchedTasks,
            long byteMismatches,
            long staticShadowMismatches,
            long staticShadowRejections,
            long rejectedTasks,
            long scopeFailures,
            long staleCandidates,
            long discardedCandidates,
            long publishedCandidates,
            long errors,
            long skippedFullRemeshes,
            SodiumRelightPlanCache.Snapshot planCache
    ) {
    }

    /** Synchronized because task events arrive from several Sodium workers. */
    static final class ObservationRecorder {
        private boolean observing;
        private long epochId;
        private long tasks;
        private long lightOnlyTasks;
        private long capturedPlans;
        private long capturedQuads;
        private long replayAttempts;
        private long replayMatches;
        private long mismatchedTasks;
        private long byteMismatches;
        private long staticShadowMismatches;
        private long staticShadowRejections;
        private long rejectedTasks;
        private long scopeFailures;
        private long staleCandidates;
        private long discardedCandidates;
        private long publishedCandidates;
        private long errors;

        synchronized void begin(final long newEpochId) {
            boolean replaced = this.observing;
            this.clear(newEpochId);
            this.observing = true;
            if (replaced) {
                this.errors = 1L;
            }
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
                    this.tasks,
                    this.lightOnlyTasks,
                    this.capturedPlans,
                    this.capturedQuads,
                    this.replayAttempts,
                    this.replayMatches,
                    this.mismatchedTasks,
                    this.byteMismatches,
                    this.staticShadowMismatches,
                    this.staticShadowRejections,
                    this.rejectedTasks,
                    this.scopeFailures,
                    this.staleCandidates,
                    this.discardedCandidates,
                    this.publishedCandidates,
                    this.errors,
                    0L,
                    PLAN_CACHE.snapshot()
            );
        }

        synchronized void recordTask(final boolean lightOnly) {
            if (!this.observing) {
                return;
            }
            this.tasks = increment(this.tasks);
            if (lightOnly) {
                this.lightOnlyTasks = increment(this.lightOnlyTasks);
            }
        }

        synchronized void recordCapturedPlan(final long quads) {
            if (this.observing) {
                this.capturedPlans = increment(this.capturedPlans);
            }
        }

        synchronized void recordCapturedQuad() {
            if (this.observing) {
                this.capturedQuads = increment(this.capturedQuads);
            }
        }

        synchronized void recordReplayAttempt() {
            if (this.observing) {
                this.replayAttempts = increment(this.replayAttempts);
            }
        }

        synchronized void recordReplayMatch() {
            if (this.observing) {
                this.replayMatches = increment(this.replayMatches);
            }
        }

        synchronized void recordMismatchedTask() {
            if (this.observing) {
                this.mismatchedTasks = increment(this.mismatchedTasks);
            }
        }

        synchronized void recordByteMismatch() {
            if (this.observing) {
                this.byteMismatches = increment(this.byteMismatches);
            }
        }

        synchronized void recordStaticShadowMismatch() {
            if (this.observing) {
                this.staticShadowMismatches = increment(this.staticShadowMismatches);
            }
        }

        synchronized void recordStaticShadowRejection() {
            if (this.observing) {
                this.staticShadowRejections = increment(this.staticShadowRejections);
            }
        }

        synchronized void recordRejectedTask() {
            if (this.observing) {
                this.rejectedTasks = increment(this.rejectedTasks);
            }
        }

        synchronized void recordScopeFailure() {
            if (this.observing) {
                this.scopeFailures = increment(this.scopeFailures);
            }
        }

        synchronized void recordStaleCandidate() {
            if (this.observing) {
                this.staleCandidates = increment(this.staleCandidates);
            }
        }

        synchronized void recordDiscardedCandidate() {
            if (this.observing) {
                this.discardedCandidates = increment(this.discardedCandidates);
            }
        }

        synchronized void recordPublishedCandidate() {
            if (this.observing) {
                this.publishedCandidates = increment(this.publishedCandidates);
            }
        }

        synchronized void recordError() {
            if (this.observing) {
                this.errors = increment(this.errors);
            }
        }

        private void clear(final long newEpochId) {
            this.epochId = newEpochId;
            this.tasks = 0L;
            this.lightOnlyTasks = 0L;
            this.capturedPlans = 0L;
            this.capturedQuads = 0L;
            this.replayAttempts = 0L;
            this.replayMatches = 0L;
            this.mismatchedTasks = 0L;
            this.byteMismatches = 0L;
            this.staticShadowMismatches = 0L;
            this.staticShadowRejections = 0L;
            this.rejectedTasks = 0L;
            this.scopeFailures = 0L;
            this.staleCandidates = 0L;
            this.discardedCandidates = 0L;
            this.publishedCandidates = 0L;
            this.errors = 0L;
        }

        private static long increment(final long value) {
            return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
        }
    }

    @FunctionalInterface
    private interface RecorderAction {
        void record(ObservationRecorder recorder);
    }
}
