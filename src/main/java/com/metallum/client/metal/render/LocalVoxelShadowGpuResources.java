package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.benchmark.L6DynamicShadowBenchmarkTelemetry;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.EntityShadowProxy;
import com.metallum.client.lighting.EntityShadowProxySnapshot;
import com.metallum.client.lighting.LightFrameSnapshot;
import com.metallum.client.lighting.LocalShadowSourceClass;
import com.metallum.client.lighting.ShadowEmitterFootprint;
import com.metallum.client.lighting.shader.VoxelShadowBindingAbi;
import com.metallum.client.metal.render.mtl.MTLBlitCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.renderer.LocalVoxelShadowAtlasLayout;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;
import com.metallum.client.voxel.VoxelBrickPatch;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.metallum.client.voxel.VoxelShadowCacheBuilder;
import com.metallum.client.voxel.VoxelShadowCacheMirror;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns L6's resident point-shadow atlas, descriptor ring and bounded page builders. */
final class LocalVoxelShadowGpuResources implements AutoCloseable {
    static final int PRODUCTION_WORK_QUEUE_COUNT = 3;
    static final int PRODUCTION_PASS_COUNT = 2;
    static final int PRODUCTION_ENCODER_COUNT = 2;
    static final int RESIDENT_PSO_COUNT = 1;
    static final int MAX_DYNAMIC_DISPATCH_COUNT = 1;

    record PreparedFrame(
            boolean active,
            int shadowedLocalLights,
            int proxyCount,
            long submitIndex,
            int descriptorLights,
            int readyLights,
            int staleLights,
            int approximateDirectLights,
            int buildingLights,
            int failClosedLights,
            int cacheCoveredLights,
            int coverageLimitedLights,
            int residentPages,
            int pendingBuilds,
            int pendingUploads,
            long pendingPayloadBytes,
            int capacityBlockedLights,
            int retryBackoffLights,
            int cacheUploads,
            long cacheUploadBytes,
            int dynamicCandidates,
            int dynamicSelected,
            int dynamicDropped,
            boolean heldAdmitted,
            int dynamicDispatches,
            int dynamicRays,
            int dynamicReady,
            int dynamicFallback,
            int dynamicCoverageMisses,
            int staticToDynamicTransitions,
            int dynamicToStaticTransitions,
            int dynamicAsyncFailures,
            long dynamicPageBytes
    ) {
        PreparedFrame {
            if (shadowedLocalLights < 0 || proxyCount < 0 || submitIndex < 0L
                    || descriptorLights < 0 || readyLights < 0 || staleLights < 0
                    || approximateDirectLights < 0 || buildingLights < 0
                    || failClosedLights < 0 || residentPages < 0
                    || cacheCoveredLights < 0 || coverageLimitedLights < 0
                    || pendingBuilds < 0 || pendingUploads < 0
                    || pendingPayloadBytes < 0L || capacityBlockedLights < 0
                    || retryBackoffLights < 0
                    || cacheUploads < 0 || cacheUploadBytes < 0L
                    || dynamicCandidates < 0 || dynamicSelected < 0 || dynamicDropped < 0
                    || dynamicDispatches < 0 || dynamicDispatches > 1 || dynamicRays < 0
                    || dynamicReady < 0 || dynamicFallback < 0
                    || dynamicCoverageMisses < 0 || staticToDynamicTransitions < 0
                    || dynamicToStaticTransitions < 0 || dynamicAsyncFailures < 0
                    || dynamicPageBytes < 0L
                    || dynamicCandidates != dynamicSelected + dynamicDropped
                    || dynamicSelected != dynamicReady + dynamicFallback
                    || dynamicCoverageMisses > dynamicSelected
                    || heldAdmitted && dynamicSelected == 0
                    || dynamicDispatches == 0 && (dynamicRays != 0 || dynamicReady != 0
                    || dynamicPageBytes != 0L)
                    || descriptorLights != readyLights + staleLights
                    + approximateDirectLights + buildingLights + failClosedLights
                    || shadowedLocalLights != readyLights + staleLights
                    || active && descriptorLights
                    != cacheCoveredLights + coverageLimitedLights
                    || !active && (cacheCoveredLights != 0 || coverageLimitedLights != 0
                    || dynamicCandidates != 0)) {
                throw new IllegalArgumentException("Invalid L6 prepared-frame accounting");
            }
        }
    }

    record DescriptorCoverage(
            int ready,
            int stale,
            int approximateDirect,
            int building,
            int failClosed
    ) {
        DescriptorCoverage {
            if (ready < 0 || stale < 0 || approximateDirect < 0
                    || building < 0 || failClosed < 0) {
                throw new IllegalArgumentException("Negative L6 descriptor coverage");
            }
        }

        int total() {
            return this.ready + this.stale + this.approximateDirect
                    + this.building + this.failClosed;
        }
    }

    record UploadBudget(int maxPages, long maxBytes) {
        UploadBudget {
            if (maxPages <= 0 || maxBytes < LocalVoxelShadowAtlasLayout.pagePayloadBytes(64)) {
                throw new IllegalArgumentException("Invalid L6 per-frame atlas upload budget");
            }
        }
    }

    private static final float MINIMUM_TRANSMITTANCE = 1.0f / 32.0f;
    private static final int MAX_PENDING_BUILDS = 64;
    private static final int MAX_RETRY_BACKOFF_SUBMITS = 32;
    static final long REPLACEMENT_RESERVE_BYTES =
            LocalVoxelShadowAtlasLayout.pageAllocationBytes(64);
    private static final long PERFORMANCE_PENDING_PAYLOAD_BYTES = 8L << 20;
    private static final long BALANCED_PENDING_PAYLOAD_BYTES = 16L << 20;
    private static final long ULTRA_PENDING_PAYLOAD_BYTES = 32L << 20;
    private static final long PAGE_LEASE_SUBMITS = 120L;
    private static final double EDGE_16_PROJECTED_RATIO = 0.0875;
    private static final double EDGE_32_PROJECTED_RATIO = 0.175;
    private static final double EDGE_64_PROJECTED_RATIO = 0.35;
    private static final double UPGRADE_GUARD = 1.20;
    private static final double DOWNGRADE_GUARD = 0.70;

    private final long generation;
    private final LocalVoxelShadowLayout.Budget budget;
    private final MetalGpuBuffer paramsRing;
    private final MetalGpuBuffer proxyRing;
    private final MetalGpuBuffer visibilityAtlas;
    private final MetalGpuBuffer shadowReferenceRing;
    private final LocalVoxelShadowAtlasResidency residency;
    private final DynamicShadowAdmission dynamicAdmission = new DynamicShadowAdmission();
    private final DynamicVoxelShadowGpuResources dynamicBackend;
    private final ThreadPoolExecutor cacheWorkers;
    private final ThreadPoolExecutor cacheRefreshWorkers;
    private final Map<Long, ResidentPage> residents = new HashMap<>();
    private final LinkedHashMap<Long, BuildTicket> builds = new LinkedHashMap<>();
    private final LinkedHashMap<Long, AtlasUpload> uploads = new LinkedHashMap<>();
    private final Map<Long, BuildFailure> failedBuilds = new HashMap<>();
    private final Set<Long> capacityBlockedBuilds = new HashSet<>();
    /** Last foreground admission per source; retained while visible to prevent refresh starvation. */
    private final Map<Long, Long> refreshLastScheduledSubmit = new HashMap<>();
    /** Target -> fence after a deliberately retired page can provide replacement capacity. */
    private final Map<Long, Long> capacityRecoveryAfterSubmit = new HashMap<>();
    /** Visible sources intentionally evicted for recovery may consume the protected scratch. */
    private final Set<Long> evictedRecoveryTargets = new HashSet<>();
    private PreparedFrame prepared;
    private FrameContext frameContext;
    private VoxelOccupancyGpuResources boundVoxelResources;
    private boolean closed;
    private boolean dynamicBackendAvailable;
    private int dynamicAsyncFailures;

    private LocalVoxelShadowGpuResources(
            final long generation,
            final LocalVoxelShadowLayout.Budget budget,
            final MetalGpuBuffer paramsRing,
            final MetalGpuBuffer proxyRing,
            final MetalGpuBuffer visibilityAtlas,
            final MetalGpuBuffer shadowReferenceRing,
            final DynamicVoxelShadowGpuResources dynamicBackend
    ) {
        this.generation = generation;
        this.budget = budget;
        this.paramsRing = paramsRing;
        this.proxyRing = proxyRing;
        this.visibilityAtlas = visibilityAtlas;
        this.shadowReferenceRing = shadowReferenceRing;
        this.dynamicBackend = dynamicBackend;
        this.dynamicBackendAvailable = dynamicBackend != null;
        this.residency = new LocalVoxelShadowAtlasResidency(
                budget.visibilityCacheBytes(), budget.maxShadowDescriptors()
        );
        AtomicInteger workerIndex = new AtomicInteger();
        int cacheWorkerCount = cacheWorkerCount(budget.preset());
        this.cacheWorkers = cacheExecutor(
                cacheWorkerCount,
                "Metallum L6 atlas builder-",
                workerIndex
        );
        this.cacheRefreshWorkers = cacheExecutor(
                cacheRefreshWorkerCount(budget.preset()),
                "Metallum L6 atlas refresh-",
                new AtomicInteger()
        );
    }

    static LocalVoxelShadowGpuResources create(
            final MetalDevice device,
            final long generation,
            final LocalVoxelShadowLayout.Budget budget
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(budget, "budget");
        if (generation <= 0L) {
            throw new IllegalArgumentException("L6 lighting generation must be positive");
        }
        MetalGpuBuffer params = null;
        MetalGpuBuffer proxies = null;
        MetalGpuBuffer atlas = null;
        MetalGpuBuffer references = null;
        DynamicVoxelShadowGpuResources dynamic = null;
        try {
            params = new MetalGpuBuffer(
                    device,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    budget.paramsRingBytes()
            );
            proxies = new MetalGpuBuffer(
                    device,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    budget.proxyRingBytes()
            );
            atlas = new MetalGpuBuffer(
                    device,
                    // MetalGpuBuffer treats UNIFORM|COPY_DST as a shared dynamic ring.
                    // This atlas is fragment-bound directly by native handle, so COPY_DST is
                    // sufficient and deliberately selects private Metal storage.
                    GpuBuffer.USAGE_COPY_DST,
                    budget.totalVisibilityAtlasBytes()
            );
            references = new MetalGpuBuffer(
                    device,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    budget.shadowReferenceRingBytes()
            );
            zero(references.sliceStorage(0L, budget.shadowReferenceRingBytes()));
            try {
                dynamic = DynamicVoxelShadowGpuResources.create(
                        device,
                        budget.visibilityCacheBytes(),
                        budget.dynamicShadows().atlasBytes()
                );
            } catch (RuntimeException | Error failure) {
                // Dynamic pages are an isolated quality path. Static L6 remains valid when
                // a machine or packaged shader cannot create its optional compute PSO.
                Metallum.LOGGER.warn(
                        "Dynamic L6 shadow backend unavailable for generation {}",
                        generation,
                        failure
                );
            }
            return new LocalVoxelShadowGpuResources(
                    generation, budget, params, proxies, atlas, references, dynamic
            );
        } catch (RuntimeException | Error failure) {
            if (dynamic != null) {
                dynamic.close();
            }
            if (references != null) {
                references.close();
            }
            if (atlas != null) {
                atlas.close();
            }
            if (proxies != null) {
                proxies.close();
            }
            if (params != null) {
                params.close();
            }
            throw failure;
        }
    }

    long generation() {
        return this.generation;
    }

    LocalVoxelShadowLayout.Budget budget() {
        return this.budget;
    }

    static long frameUploadBytes(final LocalVoxelShadowLayout.Budget budget) {
        Objects.requireNonNull(budget, "budget");
        return Math.addExact(
                Math.addExact(
                        LocalVoxelShadowLayout.PARAMS_BYTES,
                        Math.multiplyExact(
                                (long) budget.maxEntityProxies(),
                                LocalVoxelShadowLayout.PROXY_STRIDE_BYTES
                        )
                ),
                descriptorSlotBytes(budget)
        );
    }

    PreparedFrame encode(
            final FrameState frame,
            final VoxelOccupancyGpuResources voxelResources,
            final VoxelClipmapSnapshot snapshot,
            final EntityShadowProxySnapshot proxySnapshot,
            final LightFrameSnapshot lightSnapshot
    ) {
        ensureOpen();
        Objects.requireNonNull(frame, "frame");
        if (frame.lightingGenerationId() != this.generation
                || frame.inFlightSlot() < 0
                || frame.inFlightSlot() >= LocalVoxelShadowLayout.PARAMS_RING_SLOTS) {
            throw new IllegalArgumentException("L6 frame does not match its generation/ring");
        }
        List<AdvancedLight> lights = lightSnapshot == null
                ? List.of() : lightSnapshot.lights();
        if (lights.size() > this.budget.maxShadowDescriptors()) {
            throw new IllegalArgumentException("L3 snapshot exceeds the L6 descriptor ring");
        }

        long submitIndex = frame.submitIndex();
        int slot = frame.inFlightSlot();
        this.residency.releaseCompleted(submitIndex);
        this.residency.retireExpiredLeases(
                submitIndex,
                delayedReuseSubmit(submitIndex)
        );
        removeRetiredResidentMetadata();
        cancelBuildsOutsideSnapshot(lights, submitIndex);

        ByteBuffer params = this.paramsRing.sliceStorage(
                (long) slot * LocalVoxelShadowLayout.PARAMS_BYTES,
                LocalVoxelShadowLayout.PARAMS_BYTES
        ).order(ByteOrder.nativeOrder());
        long proxySlotBytes = Math.multiplyExact(
                (long) this.budget.maxEntityProxies(),
                LocalVoxelShadowLayout.PROXY_STRIDE_BYTES
        );
        ByteBuffer proxies = this.proxyRing.sliceStorage(
                (long) slot * proxySlotBytes,
                proxySlotBytes
        ).order(ByteOrder.nativeOrder());
        ByteBuffer descriptors = descriptorSlot(slot);
        zero(params);
        zero(proxies);
        zero(descriptors);

        CameraParts camera = cameraParts(frame.currentCameraPosition());
        packCommonParams(params, frame, camera, lights.size());
        this.boundVoxelResources = voxelResources;
        boolean active = voxelResources != null && snapshot != null
                && voxelResources.matches(
                this.generation, voxelResources.budget(), snapshot
        );
        int proxyCount = 0;
        VoxelShadowCacheMirror.Snapshot mirror = null;
        List<FrameLight> frameLights = new ArrayList<>(lights.size());
        DynamicFramePlan dynamicPlan = DynamicFramePlan.empty();
        try {
            if (active) {
                boolean staticOnly = DynamicShadowAdmission.isStaticOnly(lights);
                mirror = VoxelShadowCacheMirror.global().snapshot(snapshot);
                if (mirror == null) {
                    mirror = VoxelShadowCacheMirror.global().latestAcceptedSnapshot(snapshot);
                }
                VoxelClipmapSnapshot acceptedSnapshot = mirror == null
                        ? snapshot : mirror.clipmap();
                packLevels(params, acceptedSnapshot);
                proxyCount = packProxies(
                        proxies, proxySnapshot, frame.currentCameraPosition()
                );
                frameLights = describeFrameLights(
                        lights, acceptedSnapshot, mirror, frame.currentCameraPosition()
                );
                dynamicPlan = prepareDynamicFrame(
                        frameLights,
                        mirror,
                        voxelResources,
                        frame,
                        frame.currentCameraPosition(),
                        staticOnly
                );
                frameLights = dynamicPlan.lights();
                cancelBuildsBlockedByMotion(frameLights, submitIndex);
                consumeCompletedBuilds(frameLights, mirror, submitIndex);
                scheduleBuildsInSnapshotOrder(frameLights, mirror, submitIndex);
                packFrameDescriptors(
                        descriptors, frameLights, mirror, submitIndex,
                        dynamicPlan, false
                );
                putInt4(params, VoxelShadowBindingAbi.CAPS_OFFSET,
                        VoxelShadowBindingAbi.VERSION,
                        snapshot.levels().size(),
                        this.budget.maxSteps(),
                        lights.size());
                putInt4(params, VoxelShadowBindingAbi.PROXY_AND_FRAME_OFFSET,
                        proxyCount, this.budget.maxEntityProxies(),
                        (int) frame.frameId(), (int) (frame.frameId() >>> 32));
                putLongParts(params, VoxelShadowBindingAbi.CONTRACT_OFFSET,
                        frame.lightingGenerationId());
                putLongParts(params, VoxelShadowBindingAbi.CONTRACT_OFFSET + 8,
                        snapshot.clipmapGeneration());
                putLongParts(params, VoxelShadowBindingAbi.WORLD_AND_FLAGS_OFFSET,
                        snapshot.world().generation());
                params.putInt(VoxelShadowBindingAbi.ACTIVE_OFFSET, 1);
            } else {
                packFailClosedDescriptors(descriptors, lights.size());
            }
        } catch (RuntimeException failure) {
            active = false;
            proxyCount = 0;
            frameLights = List.of();
            mirror = null;
            dynamicPlan = DynamicFramePlan.empty();
            zero(params);
            zero(proxies);
            zero(descriptors);
            packCommonParams(params, frame, camera, lights.size());
            packFailClosedDescriptors(descriptors, lights.size());
            Metallum.LOGGER.warn("L6 resident-atlas frame failed closed", failure);
        }

        DescriptorCoverage coverage = descriptorCoverage(descriptors, lights.size());
        if (coverage.total() != lights.size()) {
            throw new IllegalStateException("L6 descriptor coverage lost an L3 upload light");
        }
        this.frameContext = new FrameContext(
                submitIndex, slot, List.copyOf(frameLights), mirror, active, dynamicPlan
        );
        this.prepared = preparedFrame(
                active, proxyCount, submitIndex, coverage, frameLights, 0, 0L,
                dynamicPlan, false, false
        );
        return this.prepared;
    }

    /**
     * Records ordered staging blits for every completed CPU page. Descriptors are promoted to
     * READY only after their copy is present earlier in this same Metal submit.
     */
    PreparedFrame uploadPending(final MetalCommandEncoder encoder) {
        ensureOpen();
        Objects.requireNonNull(encoder, "encoder");
        FrameContext context = this.frameContext;
        if (context == null || this.prepared == null
                || context.submitIndex() != encoder.currentSubmitIndex()) {
            throw new IllegalStateException("L6 atlas uploads are not ready for this submit");
        }
        if (!context.active()) {
            return this.prepared;
        }
        DynamicFramePlan dynamicPlan = context.dynamicPlan();
        boolean dynamicDispatchAttempted = dynamicPlan.upload() != null;
        boolean dynamicReady = false;
        if (dynamicDispatchAttempted && this.dynamicBackendAvailable
                && this.dynamicBackend != null) {
            try {
                int status = encoder.encodeDynamicVoxelShadow(
                        this.dynamicBackend,
                        this.boundVoxelResources,
                        this.visibilityAtlas,
                        dynamicPlan.upload()
                );
                dynamicReady = status == DynamicVoxelShadowGpuResources.STATUS_OK;
                if (!dynamicReady) {
                    disableDynamicBackend(
                            "encode status " + status,
                            new IllegalStateException("Native dynamic L6 encode rejected")
                    );
                }
            } catch (RuntimeException failure) {
                disableDynamicBackend("compute encode", failure);
            }
        }
        consumeCompletedBuilds(
                context.lights(), context.mirror(), context.submitIndex()
        );
        ByteBuffer descriptors = descriptorSlot(context.slot());
        int uploaded = 0;
        long uploadedBytes = 0L;
        UploadBudget uploadBudget = uploadBudget(this.budget.preset());
        List<Long> completedUploads = new ArrayList<>();
        List<Map.Entry<Long, AtlasUpload>> orderedUploads = new ArrayList<>(
                this.uploads.entrySet()
        );
        orderedUploads.sort((left, right) -> Boolean.compare(
                right.getValue().request().foreground(),
                left.getValue().request().foreground()
        ));
        for (Map.Entry<Long, AtlasUpload> entry : orderedUploads) {
            AtlasUpload upload = entry.getValue();
            FrameLight current = findFrameLight(context.lights(), entry.getKey());
            if (current == null || !upload.matches(current, context.mirror())) {
                completedUploads.add(entry.getKey());
                this.capacityBlockedBuilds.remove(entry.getKey());
                continue;
            }
            long requiredBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(
                    upload.result().edge()
            );
            if (uploaded >= uploadBudget.maxPages()
                    || requiredBytes > uploadBudget.maxBytes() - uploadedBytes) {
                continue;
            }
            boolean needsActiveSlot = this.residency.activePage(entry.getKey()) == null;
            boolean recoveryAdmission = this.evictedRecoveryTargets.contains(
                    entry.getKey()
            );
            if (!atlasCapacityAllows(
                    this.residency.freeBytes(), 0L, requiredBytes,
                    needsActiveSlot && !recoveryAdmission
            )) {
                this.capacityBlockedBuilds.add(entry.getKey());
                if (upload.request().foreground()
                        && this.residency.retiredPageCount() == 0) {
                    scheduleCapacityRecoveryEviction(
                            current, context.lights(), requiredBytes,
                            context.submitIndex()
                    );
                }
                continue;
            }
            if (!this.residency.canAcquireReplacement(
                    entry.getKey(), upload.result().edge()
            )) {
                this.capacityBlockedBuilds.add(entry.getKey());
                if (upload.request().foreground()
                        && this.residency.retiredPageCount() == 0) {
                    scheduleCapacityRecoveryEviction(
                            current, context.lights(), requiredBytes,
                            context.submitIndex()
                    );
                }
                continue;
            }
            LocalVoxelShadowAtlasResidency.ReplacementReservation reservation = null;
            try {
                byte[] payload = upload.result().payload();
                if ((long) payload.length != requiredBytes) {
                    throw new IllegalStateException("L6 atlas payload size does not match its page");
                }
                GpuBufferSlice staging;
                try (GpuBufferSlice.MappedView mapped =
                             encoder.transientMemory().allocateStaging(
                                     requiredBytes,
                                     LocalVoxelShadowAtlasLayout.PAGE_ALIGNMENT_BYTES,
                                     GpuBuffer.USAGE_COPY_SRC
                             )) {
                    copyPagePayload(mapped.data(), payload);
                    staging = mapped.slice();
                }
                reservation = this.residency.reserveReplacement(
                        entry.getKey(),
                        upload.result().edge(),
                        context.submitIndex(),
                        PAGE_LEASE_SUBMITS,
                        delayedReuseSubmit(context.submitIndex())
                );
                if (reservation == null) {
                    this.capacityBlockedBuilds.add(entry.getKey());
                    continue;
                }
                if (reservation.page().edge() != upload.result().edge()) {
                    throw new IllegalStateException("L6 atlas reservation edge changed");
                }
                LocalVoxelShadowAtlasResidency.Page reservedPage = reservation.page();
                MTLBlitCommandEncoder blit = encoder.blitCommandEncoder();
                blit.copyFromBufferToBuffer(
                        ((MetalGpuBuffer) staging.buffer()).nativeHandle(),
                        staging.offset(),
                        this.visibilityAtlas.nativeHandle(),
                        reservedPage.offsetBytes(),
                        reservedPage.payloadBytes()
                );
                LocalVoxelShadowAtlasResidency.Page page =
                        this.residency.commitReplacement(reservation);
                reservation = null;
                ResidentPage resident = new ResidentPage(
                        upload.request().lightKey(),
                        context.mirror(),
                        page,
                        upload.result().cacheLevel()
                );
                this.residents.put(entry.getKey(), resident);
                this.failedBuilds.remove(entry.getKey());
                this.capacityBlockedBuilds.remove(entry.getKey());
                this.capacityRecoveryAfterSubmit.remove(entry.getKey());
                this.evictedRecoveryTargets.remove(entry.getKey());
                completedUploads.add(entry.getKey());
                uploaded++;
                uploadedBytes = Math.addExact(uploadedBytes, page.payloadBytes());
                Metallum.LOGGER.debug(
                        "L6 atlas page ready: stableId={}, edge={}, level={}, offset={}, hitRays={}/{}, buildMs={}",
                        Long.toUnsignedString(entry.getKey()),
                        upload.result().edge(),
                        upload.result().cacheLevel(),
                        page.offsetBytes(),
                        upload.result().raysWithHits(),
                        upload.result().totalRays(),
                        upload.buildNanos() / 1_000_000.0
                );
            } catch (RuntimeException failure) {
                if (reservation != null) {
                    this.residency.abandonReplacement(reservation);
                }
                completedUploads.add(entry.getKey());
                this.capacityBlockedBuilds.remove(entry.getKey());
                recordBuildFailure(
                        entry.getKey(), upload.request(), upload.mirror(),
                        current.light(), context.submitIndex()
                );
                Metallum.LOGGER.warn(
                        "L6 atlas upload failed; previous cache or approximate direct remains active",
                        failure
                );
            }
        }
        for (long stableId : completedUploads) {
            this.uploads.remove(stableId);
        }
        DescriptorCoverage coverage;
        if (descriptorRepackRequired(uploaded, dynamicReady)) {
            packFrameDescriptors(
                    descriptors,
                    context.lights(),
                    context.mirror(),
                    context.submitIndex(),
                    dynamicPlan,
                    dynamicReady
            );
            coverage = descriptorCoverage(descriptors, context.lights().size());
        } else {
            // prepareFrame already packed this exact slot for the current submit. If no
            // resident page was promoted and no dynamic page became ready, uploadPending
            // cannot change a descriptor; preserve both bytes and the validated accounting.
            coverage = new DescriptorCoverage(
                    this.prepared.readyLights(),
                    this.prepared.staleLights(),
                    this.prepared.approximateDirectLights(),
                    this.prepared.buildingLights(),
                    this.prepared.failClosedLights()
            );
        }
        this.prepared = preparedFrame(
                true,
                this.prepared.proxyCount(),
                context.submitIndex(),
                coverage,
                context.lights(),
                uploaded,
                uploadedBytes,
                dynamicPlan,
                dynamicDispatchAttempted,
                dynamicReady
        );
        return this.prepared;
    }

    static boolean descriptorRepackRequired(
            final int uploadedPages,
            final boolean dynamicReady
    ) {
        if (uploadedPages < 0) {
            throw new IllegalArgumentException("Negative L6 uploaded-page count");
        }
        return uploadedPages != 0 || dynamicReady;
    }

    static void copyPagePayload(final ByteBuffer destination, final byte[] payload) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(payload, "payload");
        if (destination.isReadOnly() || destination.remaining() < payload.length) {
            throw new IllegalArgumentException("L6 staging view cannot hold the atlas payload");
        }
        destination.put(payload);
    }

    void bind(
            final MTLRenderCommandEncoder encoder,
            final int inFlightSlot,
            final long submitIndex
    ) {
        ensureOpen();
        Objects.requireNonNull(encoder, "encoder");
        if (inFlightSlot < 0 || inFlightSlot >= LocalVoxelShadowLayout.PARAMS_RING_SLOTS
                || this.prepared == null
                || this.prepared.submitIndex() != submitIndex) {
            throw new IllegalStateException("L6 bindings are not ready for this frame");
        }
        long proxySlotBytes = Math.multiplyExact(
                (long) this.budget.maxEntityProxies(),
                LocalVoxelShadowLayout.PROXY_STRIDE_BYTES
        );
        encoder.setBuffer(
                this.visibilityAtlas.nativeHandle(), 0L,
                VoxelShadowBindingAbi.VISIBILITY_CACHE_BUFFER_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        encoder.setBuffer(
                this.shadowReferenceRing.nativeHandle(),
                (long) inFlightSlot * descriptorSlotBytes(this.budget),
                VoxelShadowBindingAbi.SHADOW_REF_BUFFER_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        encoder.setBuffer(
                this.proxyRing.nativeHandle(),
                (long) inFlightSlot * proxySlotBytes,
                VoxelShadowBindingAbi.PROXY_BUFFER_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        encoder.setBuffer(
                this.paramsRing.nativeHandle(),
                (long) inFlightSlot * LocalVoxelShadowLayout.PARAMS_BYTES,
                VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        bindVoxelLevels(encoder);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.prepared = null;
        this.frameContext = null;
        this.boundVoxelResources = null;
        for (BuildTicket ticket : this.builds.values()) {
            cancelTicket(ticket);
        }
        this.builds.clear();
        this.uploads.clear();
        this.residents.clear();
        this.failedBuilds.clear();
        this.capacityBlockedBuilds.clear();
        this.refreshLastScheduledSubmit.clear();
        this.capacityRecoveryAfterSubmit.clear();
        this.evictedRecoveryTargets.clear();
        this.cacheRefreshWorkers.shutdownNow();
        this.cacheWorkers.shutdownNow();
        if (this.dynamicBackend != null) {
            this.dynamicBackend.close();
        }
        this.shadowReferenceRing.close();
        this.visibilityAtlas.close();
        this.proxyRing.close();
        this.paramsRing.close();
    }

    static int desiredEdge(
            final float radius,
            final double centerDistance,
            final int residentEdge
    ) {
        if (!(radius > 0.0f) || !Float.isFinite(radius)
                || centerDistance < 0.0 || !Double.isFinite(centerDistance)
                || (residentEdge != 0
                && !LocalVoxelShadowAtlasLayout.supportsPageEdge(residentEdge))) {
            throw new IllegalArgumentException("Invalid L6 projected-radius input");
        }
        double ratio = centerDistance <= radius
                ? Double.POSITIVE_INFINITY : radius / centerDistance;
        int raw = edgeForProjectedRatio(ratio);
        if (residentEdge == 0 || raw == residentEdge) {
            return raw;
        }
        if (raw > residentEdge) {
            double threshold = upgradeThreshold(raw) * UPGRADE_GUARD;
            return ratio >= threshold ? raw : residentEdge;
        }
        double threshold = upgradeThreshold(residentEdge) * DOWNGRADE_GUARD;
        return ratio < threshold ? raw : residentEdge;
    }

    static void packDescriptor(
            final ByteBuffer target,
            final int descriptorIndex,
            final int state,
            final long atlasOffset,
            final int pageEdge
    ) {
        Objects.requireNonNull(target, "target");
        int offset = Math.multiplyExact(
                descriptorIndex,
                LocalVoxelShadowAtlasLayout.DESCRIPTOR_STRIDE_BYTES
        );
        if (descriptorIndex < 0
                || offset > target.capacity()
                - LocalVoxelShadowAtlasLayout.DESCRIPTOR_STRIDE_BYTES
                || state < LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_APPROXIMATE_DIRECT
                || state > LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_FAIL_CLOSED) {
            throw new IllegalArgumentException("Invalid L6 descriptor index/state");
        }
        boolean cached = state == LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_READY
                || state == LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_STALE_RETAINED;
        if (cached) {
            if (atlasOffset < 0L
                    || atlasOffset % LocalVoxelShadowAtlasLayout.PAGE_ALIGNMENT_BYTES != 0L
                    || !LocalVoxelShadowAtlasLayout.supportsPageEdge(pageEdge)) {
                throw new IllegalArgumentException("Invalid cached L6 descriptor");
            }
        } else if (atlasOffset != 0L || pageEdge != 0) {
            throw new IllegalArgumentException("Fallback L6 descriptor retained atlas data");
        }
        target.putInt(
                offset + LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_OFFSET,
                state
        );
        target.putInt(
                offset + LocalVoxelShadowAtlasLayout.DESCRIPTOR_ATLAS_OFFSET_LO_OFFSET,
                (int) atlasOffset
        );
        target.putInt(
                offset + LocalVoxelShadowAtlasLayout.DESCRIPTOR_ATLAS_OFFSET_HI_OFFSET,
                (int) (atlasOffset >>> 32)
        );
        target.putInt(
                offset + LocalVoxelShadowAtlasLayout.DESCRIPTOR_PAGE_EDGE_OFFSET,
                pageEdge
        );
    }

    static DescriptorCoverage descriptorCoverage(
            final ByteBuffer descriptors,
            final int lightCount
    ) {
        Objects.requireNonNull(descriptors, "descriptors");
        if (lightCount < 0 || (long) lightCount
                * LocalVoxelShadowAtlasLayout.DESCRIPTOR_STRIDE_BYTES
                > descriptors.capacity()) {
            throw new IllegalArgumentException("Invalid L6 descriptor coverage range");
        }
        int ready = 0;
        int stale = 0;
        int approximateDirect = 0;
        int building = 0;
        int failClosed = 0;
        for (int index = 0; index < lightCount; index++) {
            int state = descriptors.getInt(
                    index * LocalVoxelShadowAtlasLayout.DESCRIPTOR_STRIDE_BYTES
                            + LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_OFFSET
            );
            switch (state) {
                case LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_READY -> ready++;
                case LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_STALE_RETAINED -> stale++;
                case LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_APPROXIMATE_DIRECT ->
                        approximateDirect++;
                case LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_BUILDING -> building++;
                case LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_FAIL_CLOSED -> failClosed++;
                default -> throw new IllegalStateException(
                        "Unknown L6 descriptor state " + state
                );
            }
        }
        return new DescriptorCoverage(
                ready, stale, approximateDirect, building, failClosed
        );
    }

    static int cacheWorkerCount(final LightingPreset preset) {
        return switch (Objects.requireNonNull(preset, "preset")) {
            case PERFORMANCE, BALANCED -> 2;
            case ULTRA -> 3;
        };
    }

    static int cacheRefreshWorkerCount(final LightingPreset preset) {
        return switch (Objects.requireNonNull(preset, "preset")) {
            case PERFORMANCE -> 1;
            case BALANCED, ULTRA -> 2;
        };
    }

    static int cacheRefreshPendingBuildLimit(final LightingPreset preset) {
        return Math.multiplyExact(cacheRefreshWorkerCount(preset), 2);
    }

    static int backgroundPendingBuildLimit(final LightingPreset preset) {
        return Math.multiplyExact(cacheWorkerCount(preset), 2);
    }

    /** Nearby large lights may spend the hard CPU cap to retain L5's finest occupancy. */
    static int effectiveMaxSteps(
            final LightingPreset preset,
            final int desiredEdge,
            final int configuredMaxSteps
    ) {
        Objects.requireNonNull(preset, "preset");
        if (!LocalVoxelShadowAtlasLayout.supportsPageEdge(desiredEdge)
                || configuredMaxSteps < 1
                || configuredMaxSteps > LocalVoxelShadowLayout.MAX_DDA_STEPS) {
            throw new IllegalArgumentException("Invalid L6 adaptive step budget");
        }
        if (preset == LightingPreset.PERFORMANCE || desiredEdge < 32) {
            return Math.min(configuredMaxSteps, 64);
        }
        return configuredMaxSteps;
    }

    static boolean atlasCapacityAllows(
            final long freeBytes,
            final long pendingBytes,
            final long requiredBytes,
            final boolean preserveReplacementReserve
    ) {
        if (freeBytes < 0L || pendingBytes < 0L || requiredBytes <= 0L) {
            throw new IllegalArgumentException("Invalid L6 atlas capacity accounting");
        }
        long reserve = preserveReplacementReserve ? REPLACEMENT_RESERVE_BYTES : 0L;
        return pendingBytes <= freeBytes
                && requiredBytes <= freeBytes - pendingBytes
                && reserve <= freeBytes - pendingBytes - requiredBytes;
    }

    static boolean capacityRecoveryEvictionAllowed(
            final long pendingBytes,
            final int retiredPages
    ) {
        if (pendingBytes < 0L || retiredPages < 0) {
            throw new IllegalArgumentException("Invalid L6 recovery accounting");
        }
        return pendingBytes == 0L && retiredPages == 0;
    }

    private static ThreadPoolExecutor cacheExecutor(
            final int workerCount,
            final String threadPrefix,
            final AtomicInteger workerIndex
    ) {
        return new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_BUILDS),
                task -> {
                    Thread thread = new Thread(
                            task, threadPrefix + workerIndex.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    static UploadBudget uploadBudget(final LightingPreset preset) {
        return switch (Objects.requireNonNull(preset, "preset")) {
            case PERFORMANCE -> new UploadBudget(4, 1L << 20);
            case BALANCED -> new UploadBudget(8, 2L << 20);
            case ULTRA -> new UploadBudget(12, 4L << 20);
        };
    }

    static long pendingPayloadBudget(final LightingPreset preset) {
        return switch (Objects.requireNonNull(preset, "preset")) {
            case PERFORMANCE -> PERFORMANCE_PENDING_PAYLOAD_BYTES;
            case BALANCED -> BALANCED_PENDING_PAYLOAD_BYTES;
            case ULTRA -> ULTRA_PENDING_PAYLOAD_BYTES;
        };
    }

    static int uncachedDescriptorState(
            final boolean building,
            final boolean capacityBlocked,
            final boolean irrecoverable
    ) {
        // These flags control background cache work, never whether a valid L3 source contributes.
        return LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_APPROXIMATE_DIRECT;
    }

    private PreparedFrame preparedFrame(
            final boolean active,
            final int proxyCount,
            final long submitIndex,
            final DescriptorCoverage coverage,
            final List<FrameLight> frameLights,
            final int cacheUploads,
            final long cacheUploadBytes,
            final DynamicFramePlan dynamicPlan,
            final boolean dynamicDispatchAttempted,
            final boolean dynamicReady
    ) {
        int cacheCovered = 0;
        for (FrameLight frameLight : frameLights) {
            if (frameLight.cacheLevel() >= 0) {
                cacheCovered++;
            }
        }
        int coverageLimited = active ? coverage.total() - cacheCovered : 0;
        DynamicShadowAdmission.Result admission = dynamicPlan.admission();
        int dynamicReadyCount = dynamicReady ? dynamicPlan.pages().size() : 0;
        int dynamicRays = 0;
        long dynamicPageBytes = 0L;
        if (dynamicDispatchAttempted) {
            for (DynamicPage page : dynamicPlan.pages()) {
                dynamicRays = Math.addExact(
                        dynamicRays,
                        Math.multiplyExact(Math.multiplyExact(page.edge(), page.edge()), 6)
                );
                dynamicPageBytes = Math.addExact(
                        dynamicPageBytes,
                        LocalVoxelShadowAtlasLayout.pageAllocationBytes(page.edge())
                );
            }
        }
        return new PreparedFrame(
                active,
                coverage.ready() + coverage.stale(),
                proxyCount,
                submitIndex,
                coverage.total(),
                coverage.ready(),
                coverage.stale(),
                coverage.approximateDirect(),
                coverage.building(),
                coverage.failClosed(),
                active ? cacheCovered : 0,
                coverageLimited,
                this.residency.activePageCount(),
                this.builds.size(),
                this.uploads.size(),
                pendingPayloadBytes(),
                this.capacityBlockedBuilds.size(),
                retryBackoffCount(submitIndex),
                cacheUploads,
                cacheUploadBytes,
                active ? admission.candidates() : 0,
                active ? admission.selected().size() : 0,
                active ? admission.dropped() : 0,
                active && admission.heldAdmitted(),
                active && dynamicDispatchAttempted ? 1 : 0,
                active ? dynamicRays : 0,
                active ? dynamicReadyCount : 0,
                active ? admission.selected().size() - dynamicReadyCount : 0,
                active ? dynamicPlan.coverageMisses() : 0,
                active ? admission.staticToDynamicTransitions() : 0,
                active ? admission.dynamicToStaticTransitions() : 0,
                this.dynamicAsyncFailures,
                active ? dynamicPageBytes : 0L
        );
    }

    private List<FrameLight> describeFrameLights(
            final List<AdvancedLight> lights,
            final VoxelClipmapSnapshot snapshot,
            final VoxelShadowCacheMirror.Snapshot mirror,
            final FrameState.CameraPosition camera
    ) {
        List<FrameLight> described = new ArrayList<>(lights.size());
        for (int index = 0; index < lights.size(); index++) {
            AdvancedLight light = lights.get(index);
            ResidentPage resident = resident(light.stableId());
            boolean sameSource = resident != null
                    && resident.lightKey().equals(CacheLightKey.of(light));
            int residentEdge = sameSource ? resident.page().edge() : 0;
            double dx = light.x() - camera.x();
            double dy = light.y() - camera.y();
            double dz = light.z() - camera.z();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int edge = desiredEdge(light.radius(), distance, residentEdge);
            int maxSteps = effectiveMaxSteps(
                    this.budget.preset(), edge, this.budget.maxSteps()
            );
            int cacheLevel = VoxelShadowCacheBuilder.selectCacheLevel(
                    snapshot, light, maxSteps
            );
            boolean cacheEligible = mirror != null && mirror.current() && cacheLevel >= 0;
            boolean exactStaticReady = false;
            if (cacheEligible && sameSource && resident != null
                    && residentQualityUsable(
                    resident.page().edge(), edge, resident.cacheLevel(), cacheLevel
            )) {
                exactStaticReady = resident.cacheLevel() == cacheLevel
                        && resident.mirror().clipmap().equals(snapshot)
                        && VoxelShadowCacheBuilder.relevantGeometryEquals(
                        resident.mirror(), mirror, light, cacheLevel
                );
            }
            // Exact toroidal-tag coverage is intentionally checked only after top-K admission.
            // This cheap precondition keeps a 4096-entity snapshot from scanning every light
            // sphere when the preset can publish at most four dynamic pages.
            boolean dynamicCoverage = light.shadowSourceClass()
                    != LocalShadowSourceClass.STATIC_CACHE
                    && cacheEligible
                    && light.shadowEmitterFootprint().isEmpty();
            described.add(new FrameLight(
                    index,
                    light,
                    CacheLightKey.of(light),
                    edge,
                    distance,
                    maxSteps,
                    cacheLevel,
                    cacheEligible,
                    snapshot,
                    true,
                    DynamicShadowAdmission.Phase.STATIC,
                    -1,
                    dynamicCoverage,
                    exactStaticReady
            ));
        }
        return List.copyOf(described);
    }

    private DynamicFramePlan prepareDynamicFrame(
            final List<FrameLight> described,
            final VoxelShadowCacheMirror.Snapshot mirror,
            final VoxelOccupancyGpuResources voxelResources,
            final FrameState frame,
            final FrameState.CameraPosition camera,
            final boolean staticOnly
    ) {
        if (staticOnly) {
            return DynamicFramePlan.staticOnly(
                    described, this.dynamicAdmission.resetForStaticOnlyFrame()
            );
        }
        List<DynamicShadowAdmission.Candidate> candidates = new ArrayList<>(described.size());
        for (FrameLight frameLight : described) {
            candidates.add(new DynamicShadowAdmission.Candidate(
                    frameLight.light(),
                    frameLight.dynamicCoverage(),
                    frameLight.exactStaticReady()
            ));
        }
        DynamicShadowAdmission.Result admission = this.dynamicAdmission.select(
                candidates,
                this.budget.dynamicShadows(),
                camera.x(), camera.y(), camera.z()
        );
        List<FrameLight> lights = new ArrayList<>(described.size());
        Set<Long> claimedDynamicStableIds = new HashSet<>();
        for (FrameLight frameLight : described) {
            long stableId = frameLight.light().stableId();
            DynamicShadowAdmission.TrackedCandidate tracked = admission.tracked(stableId);
            DynamicShadowAdmission.Selected selected = admission.selected(stableId);
            if (tracked == null) {
                throw new IllegalStateException("Dynamic L6 admission lost an L3 light");
            }
            int dynamicHeroSlot = claimDynamicHeroSlot(
                    frameLight.light(), selected, claimedDynamicStableIds
            );
            lights.add(new FrameLight(
                    frameLight.uploadIndex(),
                    frameLight.light(),
                    frameLight.lightKey(),
                    frameLight.desiredEdge(),
                    frameLight.centerDistance(),
                    frameLight.maxSteps(),
                    frameLight.cacheLevel(),
                    frameLight.cacheEligible(),
                    frameLight.clipmap(),
                    tracked.staticBuildAllowed(),
                    tracked.phase(),
                    dynamicHeroSlot,
                    frameLight.dynamicCoverage(),
                    frameLight.exactStaticReady()
            ));
        }

        List<DynamicPage> pages = new ArrayList<>();
        List<DynamicVoxelShadowGpuResources.Request> requests = new ArrayList<>();
        int coverageMisses = 0;
        if (this.dynamicBackendAvailable && this.dynamicBackend != null) {
            for (FrameLight frameLight : lights) {
                if (frameLight.dynamicHeroSlot() < 0) {
                    continue;
                }
                int dynamicCacheLevel = frameLight.dynamicCoverage()
                        ? VoxelShadowCacheBuilder.selectCompleteCacheLevel(
                        mirror, frameLight.light(), frameLight.maxSteps()
                ) : -1;
                if (dynamicCacheLevel < 0) {
                    if (L6DynamicShadowBenchmarkTelemetry.isActive()) {
                        StringBuilder levelCoverage = new StringBuilder();
                        for (int level = 0; level < mirror.clipmap().levels().size(); level++) {
                            if (!levelCoverage.isEmpty()) {
                                levelCoverage.append(',');
                            }
                            VoxelClipmapSnapshot.Level descriptor =
                                    mirror.clipmap().levels().get(level);
                            levelCoverage.append(level)
                                    .append(':')
                                    .append(VoxelShadowCacheBuilder.hasCompleteCoverage(
                                            mirror, frameLight.light(), level
                                    ))
                                    .append('@')
                                    .append(descriptor.originBrickX()).append('/')
                                    .append(descriptor.originBrickY()).append('/')
                                    .append(descriptor.originBrickZ());
                        }
                        Metallum.LOGGER.info(
                                "METALLUM_L6_COVERAGE_GAP frame={} stable_id={} class={} position=[{},{},{}] radius={} preferred_level={} mirror_revision={} mirror_bricks={} levels=[{}]",
                                frame.frameId(),
                                Long.toUnsignedString(frameLight.light().stableId()),
                                frameLight.light().shadowSourceClass(),
                                frameLight.light().x(),
                                frameLight.light().y(),
                                frameLight.light().z(),
                                frameLight.light().radius(),
                                frameLight.cacheLevel(),
                                mirror.revision(),
                                mirror.bricks().size(),
                                levelCoverage
                        );
                    }
                    coverageMisses++;
                    continue;
                }
                long atlasOffset = this.budget.dynamicShadows().pageOffset(
                        this.budget.visibilityCacheBytes(),
                        frameLight.dynamicHeroSlot(),
                        frame.inFlightSlot()
                );
                SourceParts source = sourceParts(frameLight.light());
                requests.add(new DynamicVoxelShadowGpuResources.Request(
                        frameLight.light().stableId(),
                        atlasOffset,
                        dynamicCacheLevel,
                        this.budget.dynamicShadows().pageEdge(),
                        this.budget.dynamicShadows().maxSteps(),
                        source.blockX(), source.blockY(), source.blockZ(),
                        source.fractionX(), source.fractionY(), source.fractionZ(),
                        frameLight.light().radius()
                ));
                pages.add(new DynamicPage(
                        frameLight.light().stableId(),
                        frameLight.uploadIndex(),
                        frameLight.dynamicHeroSlot(),
                        atlasOffset,
                        this.budget.dynamicShadows().pageEdge()
                ));
            }
        } else {
            for (FrameLight frameLight : lights) {
                if (frameLight.dynamicHeroSlot() >= 0 && !frameLight.dynamicCoverage()) {
                    coverageMisses++;
                }
            }
        }

        DynamicVoxelShadowGpuResources.FrameUpload upload = null;
        if (!requests.isEmpty()) {
            try {
                upload = this.dynamicBackend.prepare(
                        this.generation, voxelResources, frame.frameId(), requests
                );
            } catch (RuntimeException failure) {
                disableDynamicBackend("packet preparation", failure);
                pages.clear();
            }
        }
        return new DynamicFramePlan(
                List.copyOf(lights), admission, List.copyOf(pages), upload, coverageMisses
        );
    }

    /**
     * Assign one dynamic atlas page to the exact representation selected for a stable ID.
     * A camera-held proxy and its entity body may coexist in the original L3 snapshot; only
     * the preferred representation may publish the shared hero slot and backend request.
     */
    static int claimDynamicHeroSlot(
            final AdvancedLight light,
            final DynamicShadowAdmission.Selected selected,
            final Set<Long> claimedStableIds
    ) {
        Objects.requireNonNull(light, "light");
        Objects.requireNonNull(claimedStableIds, "claimedStableIds");
        if (selected == null
                || !light.equals(selected.candidate().candidate().light())
                || !claimedStableIds.add(light.stableId())) {
            return -1;
        }
        return selected.heroSlot();
    }

    private void cancelBuildsBlockedByMotion(
            final List<FrameLight> frameLights,
            final long submitIndex
    ) {
        for (FrameLight frameLight : frameLights) {
            if (frameLight.staticBuildAllowed()) {
                continue;
            }
            long stableId = frameLight.light().stableId();
            BuildTicket ticket = this.builds.remove(stableId);
            if (ticket != null) {
                cancelTicket(ticket);
            }
            this.uploads.remove(stableId);
            this.failedBuilds.remove(stableId);
            this.capacityBlockedBuilds.remove(stableId);
            this.refreshLastScheduledSubmit.remove(stableId);
            this.capacityRecoveryAfterSubmit.remove(stableId);
            this.evictedRecoveryTargets.remove(stableId);
            if (frameLight.dynamicPhase() == DynamicShadowAdmission.Phase.MOVING
                    || frameLight.dynamicPhase() == DynamicShadowAdmission.Phase.CAMERA_HELD) {
                this.residency.retire(stableId, delayedReuseSubmit(submitIndex));
            }
        }
        removeRetiredResidentMetadata();
    }

    private void disableDynamicBackend(final String operation, final Throwable failure) {
        if (!this.dynamicBackendAvailable) {
            return;
        }
        this.dynamicBackendAvailable = false;
        this.dynamicAsyncFailures = Math.incrementExact(this.dynamicAsyncFailures);
        Metallum.LOGGER.warn(
                "Dynamic L6 shadows disabled for generation {} after {} failure",
                this.generation,
                operation,
                failure
        );
    }

    private void consumeCompletedBuilds(
            final List<FrameLight> frameLights,
            final VoxelShadowCacheMirror.Snapshot mirror,
            final long submitIndex
    ) {
        if (mirror == null || !mirror.current()) {
            return;
        }
        for (FrameLight frameLight : frameLights) {
            BuildTicket ticket = this.builds.get(frameLight.light().stableId());
            if (ticket == null || !ticket.future().isDone()) {
                continue;
            }
            if (!ticketMatches(ticket, frameLight, mirror)) {
                cancelTicket(ticket);
                this.builds.remove(frameLight.light().stableId());
                continue;
            }
            BuildCompletion completion;
            try {
                completion = ticket.future().join();
            } catch (CancellationException | CompletionException failure) {
                this.builds.remove(frameLight.light().stableId());
                recordBuildFailure(
                        frameLight.light().stableId(), ticket.request(), ticket.mirror(),
                        ticket.light(), submitIndex
                );
                Metallum.LOGGER.warn("L6 atlas page build failed", failure);
                continue;
            }
            VoxelShadowCacheBuilder.PageResult result = completion.result();
            if (!result.complete() || result.cacheLevel() < 0
                    || result.edge() != ticket.request().edge()
                    || result.cacheLevel() != ticket.request().cacheLevel()) {
                this.builds.remove(frameLight.light().stableId());
                recordBuildFailure(
                        frameLight.light().stableId(), ticket.request(), ticket.mirror(),
                        ticket.light(), submitIndex
                );
                continue;
            }
            this.uploads.put(
                    frameLight.light().stableId(),
                    new AtlasUpload(
                            ticket.request(),
                            ticket.mirror(),
                            result,
                            completion.buildNanos()
                    )
            );
            this.builds.remove(frameLight.light().stableId());
        }
    }

    private void scheduleBuildsInSnapshotOrder(
            final List<FrameLight> frameLights,
            final VoxelShadowCacheMirror.Snapshot mirror,
            final long submitIndex
    ) {
        if (mirror == null || !mirror.current()) {
            return;
        }
        discardObsoletePending(frameLights, mirror);
        Set<Long> eligible = new HashSet<>();
        for (FrameLight frameLight : frameLights) {
            if (frameLight.staticCacheEligible()) {
                eligible.add(frameLight.light().stableId());
            }
        }
        this.capacityBlockedBuilds.removeIf(stableId -> !eligible.contains(stableId));
        this.failedBuilds.keySet().removeIf(stableId -> !eligible.contains(stableId));

        BuildScheduleBudget schedule = new BuildScheduleBudget(
                Math.addExact(this.builds.size(), this.uploads.size()),
                foregroundPendingBuildWork(),
                backgroundPendingBuildWork(),
                pendingPayloadBytes(),
                pendingPayloadBudget(this.budget.preset()),
                cacheRefreshPendingBuildLimit(this.budget.preset()),
                backgroundPendingBuildLimit(this.budget.preset())
        );
        CapacityRecoveryVictimCache recoveryVictims =
                new CapacityRecoveryVictimCache(frameLights);

        // Never publish a coarse intermediate page for a dirty close light. The previous page
        // remains STALE and visible until a target-quality replacement is atomically committed.
        List<FrameLight> dirtyResidents = new ArrayList<>();
        for (FrameLight frameLight : frameLights) {
            ResidentPage resident = matchingResident(frameLight);
            if (!frameLight.staticCacheEligible()) {
                continue;
            }
            if (resident == null) {
                if (this.evictedRecoveryTargets.contains(
                        frameLight.light().stableId()
                )) {
                    dirtyResidents.add(frameLight);
                }
                continue;
            }
            if (residentGeometryCurrent(resident, frameLight, mirror)) {
                continue;
            }
            dirtyResidents.add(frameLight);
        }
        dirtyResidents.sort((left, right) -> {
            boolean leftCapacityTarget = this.capacityRecoveryAfterSubmit.containsKey(
                    left.light().stableId()
            );
            boolean rightCapacityTarget = this.capacityRecoveryAfterSubmit.containsKey(
                    right.light().stableId()
            );
            if (leftCapacityTarget != rightCapacityTarget) {
                return leftCapacityTarget ? -1 : 1;
            }
            long leftLast = this.refreshLastScheduledSubmit.getOrDefault(
                    left.light().stableId(), Long.MIN_VALUE
            );
            long rightLast = this.refreshLastScheduledSubmit.getOrDefault(
                    right.light().stableId(), Long.MIN_VALUE
            );
            int ageOrder = Long.compare(leftLast, rightLast);
            if (ageOrder != 0) {
                return ageOrder;
            }
            int qualityOrder = Integer.compare(right.desiredEdge(), left.desiredEdge());
            if (qualityOrder != 0) {
                return qualityOrder;
            }
            int distanceOrder = Double.compare(left.centerDistance(), right.centerDistance());
            return distanceOrder != 0 ? distanceOrder : Long.compareUnsigned(
                    left.light().stableId(), right.light().stableId()
            );
        });
        for (FrameLight frameLight : dirtyResidents) {
            if (hasUsefulPending(frameLight, mirror)) {
                continue;
            }
            ResidentPage resident = matchingResident(frameLight);
            int buildEdge = nextBuildEdge(
                    resident == null ? 0 : resident.page().edge(),
                    frameLight.desiredEdge(), false
            );
            if (!ensureForegroundReplacementCapacity(
                    frameLight, frameLights, buildEdge, submitIndex, schedule,
                    recoveryVictims
            )) {
                continue;
            }
            if (tryScheduleBuild(
                    frameLight, mirror, buildEdge, true,
                    submitIndex, schedule
            )) {
                this.capacityRecoveryAfterSubmit.remove(
                        frameLight.light().stableId()
                );
                this.refreshLastScheduledSubmit.put(
                        frameLight.light().stableId(), submitIndex
                );
            }
        }

        // A new nearby static source has no stale page to preserve, so leaving it exclusively
        // in the background pool can indefinitely keep its slab/fence shadow approximate when
        // the atlas is full. Reserve only the preset's declared hero count for the most visible
        // eligible block lights; the existing foreground recovery path still enforces fences,
        // bounded workers and one safe eviction at a time.
        List<FrameLight> foregroundBootstraps = new ArrayList<>();
        for (FrameLight frameLight : frameLights) {
            if (frameLight.light().shadowSourceClass() != LocalShadowSourceClass.STATIC_CACHE
                    || !frameLight.staticCacheEligible()
                    || matchingResident(frameLight) != null
                    || this.evictedRecoveryTargets.contains(frameLight.light().stableId())) {
                continue;
            }
            foregroundBootstraps.add(frameLight);
        }
        foregroundBootstraps.sort(LocalVoxelShadowGpuResources::compareStaticBootstrapPriority);
        int remainingForegroundBootstraps = this.budget.shadowedLocalLights();
        for (FrameLight frameLight : foregroundBootstraps) {
            if (remainingForegroundBootstraps == 0) {
                break;
            }
            if (hasUsefulPending(frameLight, mirror)) {
                remainingForegroundBootstraps--;
                continue;
            }
            int buildEdge = nextBuildEdge(0, frameLight.desiredEdge(), false);
            if (!ensureForegroundReplacementCapacity(
                    frameLight, frameLights, buildEdge, submitIndex, schedule,
                    recoveryVictims
            )) {
                continue;
            }
            if (tryScheduleBuild(
                    frameLight, mirror, buildEdge, true, submitIndex, schedule
            )) {
                this.capacityRecoveryAfterSubmit.remove(frameLight.light().stableId());
                remainingForegroundBootstraps--;
            }
        }

        // Bootstrap and camera-driven resolution changes share a bounded background pool.
        // Direct-to-target builds avoid the former global bootstrap barrier and 8->16->32->64
        // visible staircase while retaining approximate direct light until the page is ready.
        List<FrameLight> backgroundCandidates = new ArrayList<>();
        for (FrameLight frameLight : frameLights) {
            if (!frameLight.staticCacheEligible()) {
                continue;
            }
            ResidentPage resident = matchingResident(frameLight);
            if ((resident == null && !this.evictedRecoveryTargets.contains(
                    frameLight.light().stableId()
            )) || (resident != null
                    && residentGeometryCurrent(resident, frameLight, mirror)
                    && resident.page().edge() != frameLight.desiredEdge())) {
                backgroundCandidates.add(frameLight);
            }
        }
        backgroundCandidates.sort(LocalVoxelShadowGpuResources::compareStaticBootstrapPriority);
        for (FrameLight frameLight : backgroundCandidates) {
            if (hasUsefulPending(frameLight, mirror)) {
                continue;
            }
            ResidentPage resident = matchingResident(frameLight);
            int buildEdge = nextBuildEdge(
                    resident == null ? 0 : resident.page().edge(),
                    frameLight.desiredEdge(), true
            );
            if (buildEdge == 0) {
                continue;
            }
            tryScheduleBuild(
                    frameLight, mirror, buildEdge, false,
                    submitIndex, schedule
            );
        }
    }

    private void discardObsoletePending(
            final List<FrameLight> frameLights,
            final VoxelShadowCacheMirror.Snapshot mirror
    ) {
        this.builds.entrySet().removeIf(entry -> {
            FrameLight current = findFrameLight(frameLights, entry.getKey());
            if (current != null && ticketMatches(entry.getValue(), current, mirror)) {
                return false;
            }
            cancelTicket(entry.getValue());
            return true;
        });
        this.uploads.entrySet().removeIf(entry -> {
            FrameLight current = findFrameLight(frameLights, entry.getKey());
            return current == null || !entry.getValue().matches(current, mirror);
        });
    }

    /**
     * Pre-sorts the current resident candidates at first recovery, once per scheduling pass.
     * The live maps are still rechecked immediately before retiring a victim, because foreground scheduling can
     * add pending work and a retirement can remove an active page during the same pass.
     */
    private Map<Long, List<CapacityRecoveryCandidate>> buildCapacityRecoveryVictims(
            final List<FrameLight> frameLights
    ) {
        List<CapacityRecoveryCandidate> residents = new ArrayList<>();
        for (int index = 0; index < frameLights.size(); index++) {
            FrameLight frameLight = frameLights.get(index);
            LocalVoxelShadowAtlasResidency.Page page = this.residency.activePage(
                    frameLight.light().stableId()
            );
            if (page == null) {
                continue;
            }
            residents.add(new CapacityRecoveryCandidate(
                    index,
                    frameLight.light().stableId(),
                    frameLight.cacheLevel(),
                    frameLight.desiredEdge(),
                    frameLight.centerDistance(),
                    frameLight.light().priority(),
                    page.allocationBytes()
            ));
        }
        Map<Long, List<CapacityRecoveryCandidate>> candidatesByRequiredBytes =
                new HashMap<>();
        for (int edge : LocalVoxelShadowAtlasLayout.PAGE_EDGES) {
            long requiredBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(edge);
            candidatesByRequiredBytes.put(
                    requiredBytes,
                    orderedCapacityRecoveryCandidates(residents, requiredBytes)
            );
        }
        return Map.copyOf(candidatesByRequiredBytes);
    }

    static List<CapacityRecoveryCandidate> orderedCapacityRecoveryCandidates(
            final List<CapacityRecoveryCandidate> candidates,
            final long requiredBytes
    ) {
        if (requiredBytes <= 0L) {
            throw new IllegalArgumentException("L6 capacity-recovery size must be positive");
        }
        List<CapacityRecoveryCandidate> ordered = new ArrayList<>();
        for (CapacityRecoveryCandidate candidate : candidates) {
            if (candidate.allocationBytes() >= requiredBytes) {
                ordered.add(candidate);
            }
        }
        ordered.sort(LocalVoxelShadowGpuResources::compareCapacityRecoveryCandidates);
        return List.copyOf(ordered);
    }

    private boolean ensureForegroundReplacementCapacity(
            final FrameLight target,
            final List<FrameLight> frameLights,
            final int buildEdge,
            final long submitIndex,
            final BuildScheduleBudget schedule,
            final CapacityRecoveryVictimCache recoveryVictims
    ) {
        long stableId = target.light().stableId();
        long requiredBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(buildEdge);
        boolean needsActiveSlot = this.residency.activePage(stableId) == null;
        boolean recoveryAdmission = this.evictedRecoveryTargets.contains(stableId);
        boolean capacityAllowed = atlasCapacityAllows(
                this.residency.freeBytes(), schedule.pendingBytes, requiredBytes,
                needsActiveSlot && !recoveryAdmission
        );
        if (capacityAllowed && (schedule.pendingBytes > 0L
                || this.residency.canAcquireReplacement(stableId, buildEdge))) {
            return true;
        }
        // Another admitted/retired page already owns the scratch range. Let it complete before
        // evicting another visible shadow; this bounds recovery to one victim per fence window.
        if (!capacityRecoveryEvictionAllowed(
                schedule.pendingBytes, this.residency.retiredPageCount()
        )) {
            this.capacityBlockedBuilds.add(stableId);
            return false;
        }
        scheduleCapacityRecoveryEviction(
                target, frameLights, requiredBytes, submitIndex, recoveryVictims
        );
        return false;
    }

    private void scheduleCapacityRecoveryEviction(
            final FrameLight target,
            final List<FrameLight> frameLights,
            final long requiredBytes,
            final long submitIndex
    ) {
        scheduleCapacityRecoveryEviction(
                target, frameLights, requiredBytes, submitIndex, null
        );
    }

    private void scheduleCapacityRecoveryEviction(
            final FrameLight target,
            final List<FrameLight> frameLights,
            final long requiredBytes,
            final long submitIndex,
            final CapacityRecoveryVictimCache recoveryVictims
    ) {
        long stableId = target.light().stableId();
        if (!capacityRecoveryEvictionAllowed(
                0L, this.residency.retiredPageCount()
        )) {
            this.capacityBlockedBuilds.add(stableId);
            return;
        }
        Long recoveryAfter = this.capacityRecoveryAfterSubmit.get(stableId);
        if (recoveryAfter != null && submitIndex < recoveryAfter) {
            this.capacityBlockedBuilds.add(stableId);
            return;
        }
        this.capacityRecoveryAfterSubmit.remove(stableId);

        FrameLight victim = null;
        LocalVoxelShadowAtlasResidency.Page victimPage = null;
        LocalVoxelShadowAtlasResidency.Page own = this.residency.activePage(stableId);
        if (own != null && own.allocationBytes() >= requiredBytes) {
            victim = target;
            victimPage = own;
        }
        if (recoveryVictims == null) {
            for (FrameLight candidate : frameLights) {
                long candidateId = candidate.light().stableId();
                if (candidateId == stableId || this.builds.containsKey(candidateId)
                        || this.uploads.containsKey(candidateId)) {
                    continue;
                }
                LocalVoxelShadowAtlasResidency.Page page =
                        this.residency.activePage(candidateId);
                if (page == null || page.allocationBytes() < requiredBytes) {
                    continue;
                }
                if (victim == null || lessImportantForShadowResidency(candidate, victim)) {
                    victim = candidate;
                    victimPage = page;
                }
            }
        } else {
            for (CapacityRecoveryCandidate cachedCandidate
                    : recoveryVictims.candidates(requiredBytes)) {
                long candidateId = cachedCandidate.stableId();
                if (candidateId == stableId || this.builds.containsKey(candidateId)
                        || this.uploads.containsKey(candidateId)) {
                    continue;
                }
                FrameLight candidate = frameLights.get(cachedCandidate.frameLightIndex());
                LocalVoxelShadowAtlasResidency.Page page =
                        this.residency.activePage(candidateId);
                if (page == null || page.allocationBytes() < requiredBytes) {
                    continue;
                }
                if (victim == null || lessImportantForShadowResidency(candidate, victim)) {
                    victim = candidate;
                    victimPage = page;
                }
                // The cache is least-important-first. Once its first live candidate was
                // compared against the target's own page, no later candidate can win.
                break;
            }
        }
        if (victim == null) {
            this.capacityBlockedBuilds.add(stableId);
            return;
        }
        if (victim != target && !lessImportantForShadowResidency(victim, target)) {
            this.capacityBlockedBuilds.add(stableId);
            return;
        }

        long reusableAfter = delayedReuseSubmit(submitIndex);
        long victimId = victim.light().stableId();
        if (!this.residency.retire(victimId, reusableAfter)) {
            this.capacityBlockedBuilds.add(stableId);
            return;
        }
        if (recoveryVictims != null) {
            recoveryVictims.invalidate();
        }
        this.evictedRecoveryTargets.add(victimId);
        this.residents.remove(victimId);
        this.capacityBlockedBuilds.remove(victimId);
        this.capacityRecoveryAfterSubmit.put(stableId, reusableAfter);
        this.capacityBlockedBuilds.add(stableId);
        Metallum.LOGGER.debug(
                "L6 atlas capacity recovery: target={}, evicted={}, edge={}, bytes={}, reusableAfter={}",
                Long.toUnsignedString(stableId),
                Long.toUnsignedString(victimId),
                victimPage.edge(),
                victimPage.allocationBytes(),
                reusableAfter
        );
    }

    private static boolean lessImportantForShadowResidency(
            final FrameLight candidate,
            final FrameLight currentVictim
    ) {
        return compareCapacityRecoveryPriority(
                candidate.light().stableId(), candidate.cacheLevel(), candidate.desiredEdge(),
                candidate.centerDistance(), candidate.light().priority(),
                currentVictim.light().stableId(), currentVictim.cacheLevel(),
                currentVictim.desiredEdge(), currentVictim.centerDistance(),
                currentVictim.light().priority()
        ) < 0;
    }

    private static int compareCapacityRecoveryCandidates(
            final CapacityRecoveryCandidate candidate,
            final CapacityRecoveryCandidate currentVictim
    ) {
        return compareCapacityRecoveryPriority(
                candidate.stableId(), candidate.cacheLevel(), candidate.desiredEdge(),
                candidate.centerDistance(), candidate.priority(),
                currentVictim.stableId(), currentVictim.cacheLevel(),
                currentVictim.desiredEdge(), currentVictim.centerDistance(),
                currentVictim.priority()
        );
    }

    private static int compareCapacityRecoveryPriority(
            final long candidateId,
            final int candidateCacheLevel,
            final int candidateDesiredEdge,
            final double candidateCenterDistance,
            final int candidatePriority,
            final long victimId,
            final int victimCacheLevel,
            final int victimDesiredEdge,
            final double victimCenterDistance,
            final int victimPriority
    ) {
        boolean candidateCovered = candidateCacheLevel >= 0;
        boolean victimCovered = victimCacheLevel >= 0;
        if (candidateCovered != victimCovered) {
            return candidateCovered ? 1 : -1;
        }
        int quality = Integer.compare(candidateDesiredEdge, victimDesiredEdge);
        if (quality != 0) {
            return quality;
        }
        int distance = Double.compare(victimCenterDistance, candidateCenterDistance);
        if (distance != 0) {
            return distance;
        }
        int priority = Integer.compare(candidatePriority, victimPriority);
        return priority != 0 ? priority : Long.compareUnsigned(victimId, candidateId);
    }

    private static int compareStaticBootstrapPriority(
            final FrameLight left,
            final FrameLight right
    ) {
        int qualityOrder = Integer.compare(right.desiredEdge(), left.desiredEdge());
        if (qualityOrder != 0) {
            return qualityOrder;
        }
        int distanceOrder = Double.compare(left.centerDistance(), right.centerDistance());
        if (distanceOrder != 0) {
            return distanceOrder;
        }
        int priorityOrder = Integer.compare(right.light().priority(), left.light().priority());
        return priorityOrder != 0 ? priorityOrder : Long.compareUnsigned(
                left.light().stableId(), right.light().stableId()
        );
    }

    private boolean tryScheduleBuild(
            final FrameLight frameLight,
            final VoxelShadowCacheMirror.Snapshot mirror,
            final int buildEdge,
            final boolean foreground,
            final long submitIndex,
            final BuildScheduleBudget schedule
    ) {
        long stableId = frameLight.light().stableId();
        AtlasUpload upload = this.uploads.get(stableId);
        if (upload != null) {
            if (upload.matches(frameLight, mirror)) {
                return true;
            }
            this.uploads.remove(stableId);
            schedule.removeUpload(upload.request());
        }
        BuildTicket pending = this.builds.get(stableId);
        if (pending != null) {
            if (ticketMatches(pending, frameLight, mirror)) {
                return true;
            }
            cancelTicket(pending);
            this.builds.remove(stableId);
            schedule.removeBuild(pending.request());
        }
        if (schedule.pendingWork >= MAX_PENDING_BUILDS
                || foreground
                && schedule.foregroundWork >= schedule.foregroundWorkLimit
                || !foreground
                && schedule.backgroundWork >= schedule.backgroundWorkLimit) {
            return false;
        }

        BuildRequest request = BuildRequest.of(
                frameLight, mirror, buildEdge, foreground
        );
        BuildFailure previousFailure = this.failedBuilds.get(stableId);
        if (previousFailure != null) {
            if (!previousFailure.matches(request, mirror, frameLight.light())) {
                this.failedBuilds.remove(stableId);
            } else if (submitIndex < previousFailure.retryAfterSubmit()) {
                return false;
            }
        }
        long requiredBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(buildEdge);
        if (requiredBytes > schedule.pendingByteBudget - schedule.pendingBytes) {
            return false;
        }
        boolean needsActiveSlot = this.residency.activePage(stableId) == null;
        boolean recoveryAdmission = this.evictedRecoveryTargets.contains(stableId);
        if (!atlasCapacityAllows(
                this.residency.freeBytes(), schedule.pendingBytes, requiredBytes,
                needsActiveSlot && !recoveryAdmission
        )
                || needsActiveSlot
                && this.residency.activePageCount() >= this.budget.maxShadowDescriptors()) {
            this.capacityBlockedBuilds.add(stableId);
            return false;
        }
        this.capacityBlockedBuilds.remove(stableId);
        AdvancedLight capturedLight = frameLight.light();
        VoxelShadowCacheMirror.Snapshot capturedMirror = mirror;
        BuildCancellation cancellation = new BuildCancellation();
        long started = System.nanoTime();
        ThreadPoolExecutor executor = foreground
                ? this.cacheRefreshWorkers : this.cacheWorkers;
        CompletableFuture<BuildCompletion> future = new CompletableFuture<>();
        FutureTask<Void> workerTask = new FutureTask<>(() -> {
            try {
                future.complete(new BuildCompletion(
                        VoxelShadowCacheBuilder.buildPage(
                                capturedMirror,
                                capturedLight,
                                request.edge(),
                                request.maxSteps(),
                                cancellation::cancelled
                        ),
                        Math.max(0L, System.nanoTime() - started)
                ));
            } catch (CancellationException cancelled) {
                future.cancel(false);
            } catch (RuntimeException | Error failure) {
                future.completeExceptionally(failure);
            }
            return null;
        });
        try {
            executor.execute(workerTask);
            this.builds.put(
                    stableId,
                    new BuildTicket(
                            request, capturedMirror, capturedLight, cancellation,
                            executor, workerTask, future
                    )
            );
            schedule.addBuild(request);
            return true;
        } catch (RuntimeException rejected) {
            cancellation.cancel();
            workerTask.cancel(true);
            future.cancel(true);
            executor.remove(workerTask);
            return false;
        }
    }

    private ResidentPage matchingResident(final FrameLight frameLight) {
        ResidentPage resident = resident(frameLight.light().stableId());
        return resident != null && resident.lightKey().equals(frameLight.lightKey())
                ? resident : null;
    }

    private static boolean residentGeometryCurrent(
            final ResidentPage resident,
            final FrameLight frameLight,
            final VoxelShadowCacheMirror.Snapshot mirror
    ) {
        return mirror != null && mirror.current()
                && resident.cacheLevel() == frameLight.cacheLevel()
                && VoxelShadowCacheBuilder.relevantGeometryEquals(
                resident.mirror(), mirror, frameLight.light(), resident.cacheLevel()
        );
    }

    private boolean hasUsefulPending(
            final FrameLight frameLight,
            final VoxelShadowCacheMirror.Snapshot mirror
    ) {
        BuildTicket ticket = this.builds.get(frameLight.light().stableId());
        if (ticket != null && ticketMatches(ticket, frameLight, mirror)) {
            return true;
        }
        AtlasUpload upload = this.uploads.get(frameLight.light().stableId());
        return upload != null && upload.matches(frameLight, mirror);
    }

    private int backgroundPendingBuildWork() {
        int count = 0;
        for (BuildTicket ticket : this.builds.values()) {
            if (!ticket.request().foreground()) {
                count++;
            }
        }
        return count;
    }

    private int foregroundPendingBuildWork() {
        return Math.subtractExact(
                this.builds.size(),
                backgroundPendingBuildWork()
        );
    }

    private static void cancelTicket(final BuildTicket ticket) {
        ticket.cancellation().cancel();
        ticket.workerTask().cancel(true);
        ticket.future().cancel(true);
        ticket.executor().remove(ticket.workerTask());
    }

    private void recordBuildFailure(
            final long stableId,
            final BuildRequest request,
            final VoxelShadowCacheMirror.Snapshot mirror,
            final AdvancedLight light,
            final long submitIndex
    ) {
        BuildFailure previous = this.failedBuilds.get(stableId);
        int failures = previous != null && previous.matches(request, mirror, light)
                ? Math.min(previous.failures() + 1, 30) : 1;
        long retryAfter = Math.addExact(
                submitIndex, retryDelaySubmits(failures)
        );
        this.failedBuilds.put(
                stableId, new BuildFailure(request, mirror, failures, retryAfter)
        );
    }

    private int retryBackoffCount(final long submitIndex) {
        int count = 0;
        for (BuildFailure failure : this.failedBuilds.values()) {
            if (submitIndex < failure.retryAfterSubmit()) {
                count++;
            }
        }
        return count;
    }

    static int retryDelaySubmits(final int failures) {
        if (failures <= 0) {
            throw new IllegalArgumentException("L6 retry count must be positive");
        }
        return Math.min(
                1 << Math.min(failures - 1, 30),
                MAX_RETRY_BACKOFF_SUBMITS
        );
    }

    static int nextBuildEdge(
            final int residentEdge,
            final int desiredEdge,
            final boolean geometryCurrent
    ) {
        if ((residentEdge != 0
                && !LocalVoxelShadowAtlasLayout.supportsPageEdge(residentEdge))
                || !LocalVoxelShadowAtlasLayout.supportsPageEdge(desiredEdge)) {
            throw new IllegalArgumentException("Unsupported L6 progressive page edge");
        }
        if (residentEdge != 0 && geometryCurrent && residentEdge == desiredEdge) {
            return 0;
        }
        return desiredEdge;
    }

    static int residentDescriptorState(
            final boolean geometryCurrent,
            final int residentEdge,
            final int desiredEdge
    ) {
        if (!LocalVoxelShadowAtlasLayout.supportsPageEdge(residentEdge)
                || !LocalVoxelShadowAtlasLayout.supportsPageEdge(desiredEdge)) {
            throw new IllegalArgumentException("Unsupported L6 resident descriptor edge");
        }
        return geometryCurrent && residentEdge == desiredEdge
                ? LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_READY
                : LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_STALE_RETAINED;
    }

    static boolean residentQualityUsable(
            final int residentEdge,
            final int desiredEdge,
            final int residentCacheLevel,
            final int desiredCacheLevel
    ) {
        if (!LocalVoxelShadowAtlasLayout.supportsPageEdge(residentEdge)
                || !LocalVoxelShadowAtlasLayout.supportsPageEdge(desiredEdge)
                || residentCacheLevel < 0 || desiredCacheLevel < 0) {
            throw new IllegalArgumentException("Invalid L6 resident quality transition");
        }
        // Any immutable page for the same source and clipmap topology is a safer continuity
        // approximation than state zero, which removes the shadow entirely. Edge/cache-level
        // mismatches are published as STALE until the direct-to-target replacement commits.
        return true;
    }

    static boolean replacementTargetStillUseful(
            final int requestedEdge,
            final int desiredEdge,
            final float radius,
            final double centerDistance
    ) {
        if (!LocalVoxelShadowAtlasLayout.supportsPageEdge(requestedEdge)
                || !LocalVoxelShadowAtlasLayout.supportsPageEdge(desiredEdge)
                || !(radius > 0.0f) || !Float.isFinite(radius)
                || centerDistance < 0.0 || !Double.isFinite(centerDistance)) {
            throw new IllegalArgumentException("Invalid L6 replacement target transition");
        }
        if (requestedEdge == desiredEdge) {
            return true;
        }
        if (requestedEdge < desiredEdge) {
            return false;
        }
        double ratio = centerDistance <= radius
                ? Double.POSITIVE_INFINITY : radius / centerDistance;
        return ratio >= upgradeThreshold(requestedEdge) * DOWNGRADE_GUARD;
    }

    private long pendingPayloadBytes() {
        long bytes = 0L;
        for (BuildTicket build : this.builds.values()) {
            bytes = Math.addExact(
                    bytes,
                    LocalVoxelShadowAtlasLayout.pageAllocationBytes(
                            build.request().edge()
                    )
            );
        }
        for (AtlasUpload upload : this.uploads.values()) {
            bytes = Math.addExact(
                    bytes,
                    LocalVoxelShadowAtlasLayout.pageAllocationBytes(
                            upload.result().edge()
                    )
            );
        }
        return bytes;
    }

    private void packFrameDescriptors(
            final ByteBuffer descriptors,
            final List<FrameLight> frameLights,
            final VoxelShadowCacheMirror.Snapshot mirror,
            final long submitIndex,
            final DynamicFramePlan dynamicPlan,
            final boolean dynamicReady
    ) {
        initializeDescriptorRange(descriptors, frameLights.size());
        for (FrameLight frameLight : frameLights) {
            DynamicPage page = dynamicReady
                    ? dynamicPlan.page(frameLight.light().stableId()) : null;
            Descriptor descriptor = page == null
                    ? descriptorFor(frameLight, mirror, submitIndex)
                    : new Descriptor(
                    LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_READY,
                    page.atlasOffset(),
                    page.edge()
            );
            packDescriptor(
                    descriptors,
                    frameLight.uploadIndex(),
                    descriptor.state(),
                    descriptor.atlasOffset(),
                    descriptor.pageEdge()
            );
        }
    }

    static void initializeDescriptorRange(
            final ByteBuffer descriptors,
            final int lightCount
    ) {
        Objects.requireNonNull(descriptors, "descriptors");
        if (lightCount < 0 || (long) lightCount
                * LocalVoxelShadowAtlasLayout.DESCRIPTOR_STRIDE_BYTES
                > descriptors.capacity()) {
            throw new IllegalArgumentException("Invalid L6 descriptor initialization range");
        }
        zero(descriptors);
        for (int index = 0; index < lightCount; index++) {
            descriptors.putInt(
                    index * LocalVoxelShadowAtlasLayout.DESCRIPTOR_STRIDE_BYTES
                            + LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_OFFSET,
                    LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_FAIL_CLOSED
            );
        }
    }

    private Descriptor descriptorFor(
            final FrameLight frameLight,
            final VoxelShadowCacheMirror.Snapshot mirror,
            final long submitIndex
    ) {
        ResidentPage resident = matchingResident(frameLight);
        if (frameLight.dynamicPhase() == DynamicShadowAdmission.Phase.STATIC
                && frameLight.cacheLevel() >= 0 && resident != null
                && residentQualityUsable(
                resident.page().edge(), frameLight.desiredEdge(),
                resident.cacheLevel(), frameLight.cacheLevel()
        )
                && sameClipmapContract(resident.mirror().clipmap(), frameLight)) {
            boolean geometryCurrent = residentGeometryCurrent(
                    resident, frameLight, mirror
            );
            LocalVoxelShadowAtlasResidency.Page touched = this.residency.acquire(
                    frameLight.light().stableId(),
                    resident.page().edge(),
                    submitIndex,
                    PAGE_LEASE_SUBMITS,
                    delayedReuseSubmit(submitIndex)
            ).page();
            if (touched != null && touched.allocationId()
                    == resident.page().allocationId()) {
                this.residents.put(
                        frameLight.light().stableId(),
                        new ResidentPage(
                                resident.lightKey(),
                                geometryCurrent ? mirror : resident.mirror(),
                                touched,
                                resident.cacheLevel()
                        )
                );
                return new Descriptor(
                        residentDescriptorState(
                                geometryCurrent, touched.edge(), frameLight.desiredEdge()
                        ),
                        touched.offsetBytes(),
                        touched.edge()
                );
            }
        }
        return Descriptor.approximateDirect();
    }

    private ResidentPage resident(final long stableId) {
        ResidentPage resident = this.residents.get(stableId);
        LocalVoxelShadowAtlasResidency.Page active = this.residency.activePage(stableId);
        if (resident == null || active == null
                || active.allocationId() != resident.page().allocationId()) {
            return null;
        }
        return resident;
    }

    private void removeRetiredResidentMetadata() {
        this.residents.entrySet().removeIf(entry -> {
            LocalVoxelShadowAtlasResidency.Page active =
                    this.residency.activePage(entry.getKey());
            if (active != null && active.allocationId()
                    == entry.getValue().page().allocationId()) {
                return false;
            }
            return true;
        });
    }

    private void cancelBuildsOutsideSnapshot(
            final List<AdvancedLight> lights,
            final long submitIndex
    ) {
        Set<Long> retained = new HashSet<>(lights.size());
        for (AdvancedLight light : lights) {
            retained.add(light.stableId());
        }
        this.builds.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) {
                return false;
            }
            cancelTicket(entry.getValue());
            return true;
        });
        this.uploads.keySet().removeIf(stableId -> !retained.contains(stableId));
        this.failedBuilds.keySet().removeIf(stableId -> !retained.contains(stableId));
        this.capacityBlockedBuilds.removeIf(stableId -> !retained.contains(stableId));
        this.refreshLastScheduledSubmit.keySet().removeIf(
                stableId -> !retained.contains(stableId)
        );
        this.capacityRecoveryAfterSubmit.keySet().removeIf(
                stableId -> !retained.contains(stableId)
        );
        this.evictedRecoveryTargets.removeIf(
                stableId -> !retained.contains(stableId)
        );
        for (long stableId : this.residency.activeStableIds()) {
            if (!retained.contains(stableId)) {
                this.residency.retire(stableId, delayedReuseSubmit(submitIndex));
            }
        }
    }

    private static boolean ticketMatches(
            final BuildTicket ticket,
            final FrameLight frameLight,
            final VoxelShadowCacheMirror.Snapshot mirror
    ) {
        BuildRequest request = ticket.request();
        if (!frameLight.staticCacheEligible()
                || !request.lightKey().equals(frameLight.lightKey())
                || !requestMatchesFrameQuality(request, frameLight)
                || !LocalVoxelShadowAtlasLayout.supportsPageEdge(request.edge())
                || request.maxSteps() <= 0
                || request.worldGeneration() != mirror.clipmap().world().generation()
                || request.clipmapGeneration()
                != mirror.clipmap().clipmapGeneration()
                || !request.levels().equals(
                mirror.clipmap().levels().stream().map(CacheLevelKey::of).toList()
        )) {
            return false;
        }
        return request.mirrorRevision() == mirror.revision()
                && ticket.mirror().clipmap().equals(mirror.clipmap())
                || VoxelShadowCacheBuilder.relevantGeometryEquals(
                ticket.mirror(),
                mirror,
                frameLight.light(),
                request.cacheLevel()
        );
    }

    private static boolean requestMatchesFrameQuality(
            final BuildRequest request,
            final FrameLight frameLight
    ) {
        if (!replacementTargetStillUseful(
                request.edge(), frameLight.desiredEdge(),
                frameLight.light().radius(), frameLight.centerDistance()
        )) {
            return false;
        }
        return request.edge() != frameLight.desiredEdge()
                || (request.maxSteps() == frameLight.maxSteps()
                && request.cacheLevel() == frameLight.cacheLevel());
    }

    private static FrameLight findFrameLight(
            final List<FrameLight> lights,
            final long stableId
    ) {
        for (FrameLight light : lights) {
            if (light.light().stableId() == stableId) {
                return light;
            }
        }
        return null;
    }

    private static boolean sameClipmapContract(
            final VoxelClipmapSnapshot resident,
            final FrameLight current
    ) {
        VoxelClipmapSnapshot next = current.clipmap();
        if (resident == null || next == null
                || !resident.world().equals(next.world())
                || resident.clipmapGeneration() != next.clipmapGeneration()
                || resident.levels().size() != next.levels().size()) {
            return false;
        }
        for (int index = 0; index < resident.levels().size(); index++) {
            VoxelClipmapSnapshot.Level left = resident.levels().get(index);
            VoxelClipmapSnapshot.Level right = next.levels().get(index);
            if (left.level() != right.level()
                    || left.subdivision() != right.subdivision()
                    || left.logicalEdge() != right.logicalEdge()
                    || left.brickDimension() != right.brickDimension()) {
                return false;
            }
        }
        return true;
    }

    private static int edgeForProjectedRatio(final double ratio) {
        if (ratio >= EDGE_64_PROJECTED_RATIO) {
            return 64;
        }
        if (ratio >= EDGE_32_PROJECTED_RATIO) {
            return 32;
        }
        if (ratio >= EDGE_16_PROJECTED_RATIO) {
            return 16;
        }
        return 8;
    }

    private static double upgradeThreshold(final int edge) {
        return switch (edge) {
            case 8 -> 0.0;
            case 16 -> EDGE_16_PROJECTED_RATIO;
            case 32 -> EDGE_32_PROJECTED_RATIO;
            case 64 -> EDGE_64_PROJECTED_RATIO;
            default -> throw new IllegalArgumentException("Unsupported L6 page edge");
        };
    }

    private static long delayedReuseSubmit(final long submitIndex) {
        return Math.addExact(
                submitIndex,
                MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT
        );
    }

    private ByteBuffer descriptorSlot(final int slot) {
        long slotBytes = descriptorSlotBytes(this.budget);
        return this.shadowReferenceRing.sliceStorage(
                (long) slot * slotBytes, slotBytes
        ).order(ByteOrder.nativeOrder());
    }

    private static long descriptorSlotBytes(
            final LocalVoxelShadowLayout.Budget budget
    ) {
        return budget.shadowReferenceRingBytes()
                / LocalVoxelShadowAtlasLayout.DESCRIPTOR_RING_SLOTS;
    }

    private static void packFailClosedDescriptors(
            final ByteBuffer descriptors,
            final int lightCount
    ) {
        for (int index = 0; index < lightCount; index++) {
            packDescriptor(
                    descriptors,
                    index,
                    LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_FAIL_CLOSED,
                    0L,
                    0
            );
        }
    }

    private void packCommonParams(
            final ByteBuffer params,
            final FrameState frame,
            final CameraParts camera,
            final int lightCount
    ) {
        putMatrix(params, VoxelShadowBindingAbi.WORLD_FROM_VIEW_MATRIX_OFFSET,
                frame.currentTransforms().unjitteredCamera());
        int atlasHitCapacity = Math.toIntExact(
                this.budget.totalVisibilityAtlasBytes()
                        / LocalVoxelShadowAtlasLayout.HIT_STRIDE_BYTES
        );
        putInt4(params, VoxelShadowBindingAbi.CAMERA_BLOCK_AND_FLAGS_OFFSET,
                camera.blockX(), camera.blockY(), camera.blockZ(), atlasHitCapacity);
        putFloat4(params,
                VoxelShadowBindingAbi.CAMERA_FRACTION_AND_MIN_TRANSMITTANCE_OFFSET,
                camera.fractionX(), camera.fractionY(), camera.fractionZ(),
                MINIMUM_TRANSMITTANCE);
        putInt4(params, VoxelShadowBindingAbi.CAPS_OFFSET,
                VoxelShadowBindingAbi.VERSION, 0, this.budget.maxSteps(), lightCount);
        putInt4(params, VoxelShadowBindingAbi.PROXY_AND_FRAME_OFFSET,
                0, this.budget.maxEntityProxies(),
                (int) frame.frameId(), (int) (frame.frameId() >>> 32));
    }

    private static void packLevels(
            final ByteBuffer params,
            final VoxelClipmapSnapshot snapshot
    ) {
        List<VoxelClipmapSnapshot.Level> levels = snapshot.levels();
        if (levels.isEmpty() || levels.size() > VoxelShadowBindingAbi.LEVEL_COUNT) {
            throw new IllegalArgumentException("L6 level count is outside its shader ABI");
        }
        for (int index = 0; index < levels.size(); index++) {
            VoxelClipmapSnapshot.Level level = levels.get(index);
            int brickBlockEdge = VoxelBrickPatch.LOGICAL_EDGE / level.subdivision();
            int originX = Math.toIntExact(Math.multiplyExact(
                    level.originBrickX(), (long) brickBlockEdge));
            int originY = Math.toIntExact(Math.multiplyExact(
                    level.originBrickY(), (long) brickBlockEdge));
            int originZ = Math.toIntExact(Math.multiplyExact(
                    level.originBrickZ(), (long) brickBlockEdge));
            int spanBlocks = Math.multiplyExact(
                    level.brickDimension(), brickBlockEdge
            );
            int offset = VoxelShadowBindingAbi.levelOffset(index);
            putInt4(params, offset, originX, originY, originZ, spanBlocks);
            putInt4(params, offset + VoxelShadowBindingAbi.LEVEL_LAYOUT_OFFSET,
                    level.subdivision(), level.logicalEdge(), level.brickDimension(),
                    brickBlockEdge);
        }
    }

    private int packProxies(
            final ByteBuffer proxies,
            final EntityShadowProxySnapshot snapshot,
            final FrameState.CameraPosition camera
    ) {
        if (snapshot == null) {
            return 0;
        }
        int count = Math.min(
                snapshot.proxies().size(), this.budget.maxEntityProxies()
        );
        for (int index = 0; index < count; index++) {
            EntityShadowProxy proxy = snapshot.proxies().get(index);
            int offset = index * LocalVoxelShadowLayout.PROXY_STRIDE_BYTES;
            putFloat4(proxies, offset,
                    proxy.minRelativeX(camera.x()),
                    proxy.minRelativeY(camera.y()),
                    proxy.minRelativeZ(camera.z()),
                    Float.intBitsToFloat((int) proxy.stableId()));
            putFloat4(proxies, offset + 16,
                    proxy.maxRelativeX(camera.x()),
                    proxy.maxRelativeY(camera.y()),
                    proxy.maxRelativeZ(camera.z()),
                    Float.intBitsToFloat((int) (proxy.stableId() >>> 32)));
        }
        return count;
    }

    private void bindVoxelLevels(final MTLRenderCommandEncoder encoder) {
        int[] occupancySlots = VoxelShadowBindingAbi.occupancyTextureSlots();
        int[] opticalSlots = VoxelShadowBindingAbi.opticalTextureSlots();
        int[] metadataSlots = VoxelShadowBindingAbi.metadataBufferSlots();
        VoxelOccupancyGpuResources resources = this.boundVoxelResources;
        if (resources == null || resources.levels().isEmpty()) {
            for (int level = 0; level < VoxelShadowBindingAbi.LEVEL_COUNT; level++) {
                encoder.setBuffer(
                        this.visibilityAtlas.nativeHandle(), 0L, occupancySlots[level],
                        MetalCompiledRenderPipeline.STAGE_FRAGMENT
                );
                encoder.setBuffer(
                        this.visibilityAtlas.nativeHandle(), 0L, opticalSlots[level],
                        MetalCompiledRenderPipeline.STAGE_FRAGMENT
                );
                encoder.setBuffer(
                        this.visibilityAtlas.nativeHandle(), 0L, metadataSlots[level],
                        MetalCompiledRenderPipeline.STAGE_FRAGMENT
                );
            }
            return;
        }
        int actualLevels = resources.levels().size();
        if (actualLevels > VoxelShadowBindingAbi.LEVEL_COUNT) {
            throw new IllegalStateException("L6 retained too many L5 fragment bindings");
        }
        for (int level = 0; level < VoxelShadowBindingAbi.LEVEL_COUNT; level++) {
            VoxelOccupancyGpuResources.FragmentLevelBindings bindings =
                    resources.fragmentLevelBindings(Math.min(level, actualLevels - 1));
            encoder.setBuffer(
                    bindings.occupancy(), 0L, occupancySlots[level],
                    MetalCompiledRenderPipeline.STAGE_FRAGMENT
            );
            encoder.setBuffer(
                    bindings.optical(), 0L, opticalSlots[level],
                    MetalCompiledRenderPipeline.STAGE_FRAGMENT
            );
            encoder.setBuffer(
                    bindings.metadata(), 0L, metadataSlots[level],
                    MetalCompiledRenderPipeline.STAGE_FRAGMENT
            );
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("L6 local-shadow resources are closed");
        }
    }

    private static CameraParts cameraParts(final FrameState.CameraPosition camera) {
        double blockX = Math.floor(camera.x());
        double blockY = Math.floor(camera.y());
        double blockZ = Math.floor(camera.z());
        if (blockX < Integer.MIN_VALUE || blockX > Integer.MAX_VALUE
                || blockY < Integer.MIN_VALUE || blockY > Integer.MAX_VALUE
                || blockZ < Integer.MIN_VALUE || blockZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "L6 camera block is outside the shader integer range"
            );
        }
        return new CameraParts(
                (int) blockX, (int) blockY, (int) blockZ,
                checkedFraction(camera.x() - blockX),
                checkedFraction(camera.y() - blockY),
                checkedFraction(camera.z() - blockZ)
        );
    }

    private static SourceParts sourceParts(final AdvancedLight light) {
        int blockX = floorWorldCoordinate(light.x());
        int blockY = floorWorldCoordinate(light.y());
        int blockZ = floorWorldCoordinate(light.z());
        return new SourceParts(
                blockX,
                blockY,
                blockZ,
                checkedFraction(light.x() - blockX),
                checkedFraction(light.y() - blockY),
                checkedFraction(light.z() - blockZ)
        );
    }

    private static int floorWorldCoordinate(final double value) {
        double floor = Math.floor(value);
        if (!Double.isFinite(value) || floor < -30_000_000.0 || floor > 30_000_000.0) {
            throw new IllegalArgumentException("Dynamic L6 source is outside the supported world");
        }
        return (int) floor;
    }

    static float checkedFraction(final double value) {
        if (!Double.isFinite(value) || value < 0.0 || value >= 1.0) {
            throw new IllegalArgumentException("L6 coordinate fraction is invalid");
        }
        // A valid double immediately below 1.0 can round upward when narrowed to float. Keep
        // the already-selected floor block and quantize to the largest representable fraction
        // instead of rejecting one otherwise valid moving-light frame.
        return Math.min((float) value, Math.nextDown(1.0f));
    }

    private static void putMatrix(
            final ByteBuffer target,
            final int offset,
            final Matrix4 matrix
    ) {
        for (int index = 0; index < Matrix4.ELEMENT_COUNT; index++) {
            target.putFloat(
                    offset + index * Float.BYTES,
                    (float) matrix.element(index)
            );
        }
    }

    private static void putInt4(
            final ByteBuffer target,
            final int offset,
            final int x,
            final int y,
            final int z,
            final int w
    ) {
        target.putInt(offset, x);
        target.putInt(offset + 4, y);
        target.putInt(offset + 8, z);
        target.putInt(offset + 12, w);
    }

    private static void putFloat4(
            final ByteBuffer target,
            final int offset,
            final float x,
            final float y,
            final float z,
            final float w
    ) {
        target.putFloat(offset, x);
        target.putFloat(offset + 4, y);
        target.putFloat(offset + 8, z);
        target.putFloat(offset + 12, w);
    }

    private static void putLongParts(
            final ByteBuffer target,
            final int offset,
            final long value
    ) {
        target.putInt(offset, (int) value);
        target.putInt(offset + 4, (int) (value >>> 32));
    }

    private static void zero(final ByteBuffer target) {
        target.clear();
        while (target.hasRemaining()) {
            target.put((byte) 0);
        }
        target.clear();
    }

    record CacheLightKey(
            long stableId,
            long xBits,
            long yBits,
            long zBits,
            int radiusBits,
            ShadowEmitterFootprint emitterFootprint
    ) {
        static CacheLightKey of(final AdvancedLight light) {
            return new CacheLightKey(
                    light.stableId(),
                    Double.doubleToRawLongBits(light.x()),
                    Double.doubleToRawLongBits(light.y()),
                    Double.doubleToRawLongBits(light.z()),
                    Float.floatToRawIntBits(light.radius()),
                    light.shadowEmitterFootprint()
            );
        }
    }

    record CacheLevelKey(
            int level,
            int subdivision,
            int logicalEdge,
            int brickDimension
    ) {
        static CacheLevelKey of(final VoxelClipmapSnapshot.Level level) {
            return new CacheLevelKey(
                    level.level(), level.subdivision(), level.logicalEdge(),
                    level.brickDimension()
            );
        }
    }

    record BuildRequest(
            CacheLightKey lightKey,
            long worldGeneration,
            long clipmapGeneration,
            long mirrorRevision,
            int edge,
            int maxSteps,
            int cacheLevel,
            boolean foreground,
            List<CacheLevelKey> levels
    ) {
        BuildRequest {
            Objects.requireNonNull(lightKey, "lightKey");
            if (worldGeneration <= 0L || clipmapGeneration <= 0L
                    || mirrorRevision <= 0L
                    || !LocalVoxelShadowAtlasLayout.supportsPageEdge(edge)
                    || maxSteps < 1 || maxSteps > LocalVoxelShadowLayout.MAX_DDA_STEPS
                    || cacheLevel < 0) {
                throw new IllegalArgumentException("Invalid L6 build request");
            }
            levels = List.copyOf(levels);
            if (cacheLevel >= levels.size()) {
                throw new IllegalArgumentException("L6 cache level is outside build topology");
            }
        }

        boolean sameSemanticWork(final BuildRequest other) {
            return other != null
                    && this.lightKey.equals(other.lightKey)
                    && this.worldGeneration == other.worldGeneration
                    && this.clipmapGeneration == other.clipmapGeneration
                    && this.edge == other.edge
                    && this.maxSteps == other.maxSteps
                    && this.cacheLevel == other.cacheLevel
                    && this.levels.equals(other.levels);
        }

        static BuildRequest of(
                final FrameLight frameLight,
                final VoxelShadowCacheMirror.Snapshot mirror,
                final int edge,
                final boolean foreground
        ) {
            return new BuildRequest(
                    frameLight.lightKey(),
                    mirror.clipmap().world().generation(),
                    mirror.clipmap().clipmapGeneration(),
                    mirror.revision(),
                    edge,
                    frameLight.maxSteps(),
                    frameLight.cacheLevel(),
                    foreground,
                    mirror.clipmap().levels().stream().map(CacheLevelKey::of).toList()
            );
        }
    }

    private record BuildFailure(
            BuildRequest request,
            VoxelShadowCacheMirror.Snapshot mirror,
            int failures,
            long retryAfterSubmit
    ) {
        BuildFailure {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(mirror, "mirror");
            if (failures <= 0 || retryAfterSubmit < 0L) {
                throw new IllegalArgumentException("Invalid L6 build retry state");
            }
        }

        boolean matches(
                final BuildRequest next,
                final VoxelShadowCacheMirror.Snapshot currentMirror,
                final AdvancedLight light
        ) {
            return this.request.sameSemanticWork(next)
                    && currentMirror != null && currentMirror.current()
                    && VoxelShadowCacheBuilder.relevantRetryGeometryEquals(
                    this.mirror, currentMirror, light, this.request.cacheLevel()
            );
        }
    }

    private record BuildTicket(
            BuildRequest request,
            VoxelShadowCacheMirror.Snapshot mirror,
            AdvancedLight light,
            BuildCancellation cancellation,
            ThreadPoolExecutor executor,
            FutureTask<Void> workerTask,
            CompletableFuture<BuildCompletion> future
    ) {
    }

    private static final class BuildCancellation {
        private volatile boolean cancelled;

        private void cancel() {
            this.cancelled = true;
        }

        private boolean cancelled() {
            return this.cancelled;
        }
    }

    private record BuildCompletion(
            VoxelShadowCacheBuilder.PageResult result,
            long buildNanos
    ) {
        BuildCompletion {
            Objects.requireNonNull(result, "result");
            if (buildNanos < 0L) {
                throw new IllegalArgumentException("Negative L6 page build duration");
            }
        }
    }

    private record ResidentPage(
            CacheLightKey lightKey,
            VoxelShadowCacheMirror.Snapshot mirror,
            LocalVoxelShadowAtlasResidency.Page page,
            int cacheLevel
    ) {
    }

    private record AtlasUpload(
            BuildRequest request,
            VoxelShadowCacheMirror.Snapshot mirror,
            VoxelShadowCacheBuilder.PageResult result,
            long buildNanos
    ) {
        boolean matches(
                final FrameLight light,
                final VoxelShadowCacheMirror.Snapshot currentMirror
        ) {
            return currentMirror != null && currentMirror.current()
                    && light.staticCacheEligible()
                    && this.request.lightKey().equals(light.lightKey())
                    && requestMatchesFrameQuality(this.request, light)
                    && this.result.edge() == this.request.edge()
                    && this.result.cacheLevel() == this.request.cacheLevel()
                    && (this.mirror.revision() == currentMirror.revision()
                    && this.mirror.clipmap().equals(currentMirror.clipmap())
                    || VoxelShadowCacheBuilder.relevantGeometryEquals(
                    this.mirror,
                    currentMirror,
                    light.light(),
                    this.result.cacheLevel()
            ));
        }
    }

    private record FrameLight(
            int uploadIndex,
            AdvancedLight light,
            CacheLightKey lightKey,
            int desiredEdge,
            double centerDistance,
            int maxSteps,
            int cacheLevel,
            boolean cacheEligible,
            VoxelClipmapSnapshot clipmap,
            boolean staticBuildAllowed,
            DynamicShadowAdmission.Phase dynamicPhase,
            int dynamicHeroSlot,
            boolean dynamicCoverage,
            boolean exactStaticReady
    ) {
        boolean staticCacheEligible() {
            return this.cacheEligible && this.staticBuildAllowed;
        }
    }

    record CapacityRecoveryCandidate(
            int frameLightIndex,
            long stableId,
            int cacheLevel,
            int desiredEdge,
            double centerDistance,
            int priority,
            long allocationBytes
    ) {
        CapacityRecoveryCandidate {
            if (frameLightIndex < 0 || stableId == 0L
                    || !LocalVoxelShadowAtlasLayout.supportsPageEdge(desiredEdge)
                    || !Double.isFinite(centerDistance) || centerDistance < 0.0
                    || allocationBytes <= 0L) {
                throw new IllegalArgumentException("Invalid L6 capacity-recovery candidate");
            }
        }
    }

    private final class CapacityRecoveryVictimCache {
        private final List<FrameLight> frameLights;
        private Map<Long, List<CapacityRecoveryCandidate>> candidatesByRequiredBytes;
        private boolean valid = true;

        private CapacityRecoveryVictimCache(
                final List<FrameLight> frameLights
        ) {
            this.frameLights = frameLights;
        }

        private List<CapacityRecoveryCandidate> candidates(final long requiredBytes) {
            if (!this.valid) {
                return List.of();
            }
            if (this.candidatesByRequiredBytes == null) {
                this.candidatesByRequiredBytes = buildCapacityRecoveryVictims(this.frameLights);
            }
            return this.candidatesByRequiredBytes.getOrDefault(requiredBytes, List.of());
        }

        private void invalidate() {
            this.valid = false;
        }
    }

    private record FrameContext(
            long submitIndex,
            int slot,
            List<FrameLight> lights,
            VoxelShadowCacheMirror.Snapshot mirror,
            boolean active,
            DynamicFramePlan dynamicPlan
    ) {
    }

    private record DynamicPage(
            long stableId,
            int uploadIndex,
            int heroSlot,
            long atlasOffset,
            int edge
    ) {
        DynamicPage {
            if (stableId == 0L || uploadIndex < 0 || heroSlot < 0
                    || atlasOffset < 0L || atlasOffset % 256L != 0L
                    || edge != 16 && edge != 32) {
                throw new IllegalArgumentException("Invalid dynamic L6 page declaration");
            }
        }
    }

    private record DynamicFramePlan(
            List<FrameLight> lights,
            DynamicShadowAdmission.Result admission,
            List<DynamicPage> pages,
            DynamicVoxelShadowGpuResources.FrameUpload upload,
            int coverageMisses
    ) {
        private static final DynamicFramePlan EMPTY = new DynamicFramePlan(
                List.of(), DynamicShadowAdmission.emptyResult(), List.of(), null, 0
        );

        DynamicFramePlan {
            lights = List.copyOf(lights);
            Objects.requireNonNull(admission, "admission");
            pages = List.copyOf(pages);
            if (coverageMisses < 0 || coverageMisses > admission.selected().size()
                    || upload == null && !pages.isEmpty()
                    || upload != null && upload.requestCount() != pages.size()) {
                throw new IllegalArgumentException("Invalid dynamic L6 frame plan");
            }
        }

        static DynamicFramePlan empty() {
            return EMPTY;
        }

        static DynamicFramePlan staticOnly(
                final List<FrameLight> lights,
                final DynamicShadowAdmission.Result admission
        ) {
            return new DynamicFramePlan(lights, admission, List.of(), null, 0);
        }

        DynamicPage page(final long stableId) {
            for (DynamicPage page : this.pages) {
                if (page.stableId() == stableId) {
                    return page;
                }
            }
            return null;
        }
    }

    private static final class BuildScheduleBudget {
        private int pendingWork;
        private int foregroundWork;
        private int backgroundWork;
        private long pendingBytes;
        private final long pendingByteBudget;
        private final int foregroundWorkLimit;
        private final int backgroundWorkLimit;

        private BuildScheduleBudget(
                final int pendingWork,
                final int foregroundWork,
                final int backgroundWork,
                final long pendingBytes,
                final long pendingByteBudget,
                final int foregroundWorkLimit,
                final int backgroundWorkLimit
        ) {
            if (pendingWork < 0 || foregroundWork < 0 || backgroundWork < 0
                    || foregroundWork + backgroundWork > pendingWork
                    || pendingBytes < 0L || pendingByteBudget < 0L
                    || foregroundWorkLimit <= 0 || backgroundWorkLimit <= 0) {
                throw new IllegalArgumentException("Invalid L6 build scheduling budget");
            }
            this.pendingWork = pendingWork;
            this.foregroundWork = foregroundWork;
            this.backgroundWork = backgroundWork;
            this.pendingBytes = pendingBytes;
            this.pendingByteBudget = pendingByteBudget;
            this.foregroundWorkLimit = foregroundWorkLimit;
            this.backgroundWorkLimit = backgroundWorkLimit;
        }

        private void addBuild(final BuildRequest request) {
            this.pendingWork++;
            if (request.foreground()) {
                this.foregroundWork++;
            } else {
                this.backgroundWork++;
            }
            this.pendingBytes = Math.addExact(
                    this.pendingBytes,
                    LocalVoxelShadowAtlasLayout.pageAllocationBytes(request.edge())
            );
        }

        private void removeBuild(final BuildRequest request) {
            this.pendingWork--;
            if (request.foreground()) {
                this.foregroundWork--;
            } else {
                this.backgroundWork--;
            }
            this.pendingBytes = Math.subtractExact(
                    this.pendingBytes,
                    LocalVoxelShadowAtlasLayout.pageAllocationBytes(request.edge())
            );
            if (this.pendingWork < 0 || this.foregroundWork < 0
                    || this.backgroundWork < 0
                    || this.pendingBytes < 0L) {
                throw new IllegalStateException("L6 build scheduling accounting underflow");
            }
        }

        private void removeUpload(final BuildRequest request) {
            this.pendingWork--;
            this.pendingBytes = Math.subtractExact(
                    this.pendingBytes,
                    LocalVoxelShadowAtlasLayout.pageAllocationBytes(request.edge())
            );
            if (this.pendingWork < 0 || this.pendingBytes < 0L) {
                throw new IllegalStateException("L6 upload scheduling accounting underflow");
            }
        }
    }

    private record Descriptor(int state, long atlasOffset, int pageEdge) {
        static Descriptor approximateDirect() {
            return new Descriptor(
                    LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_APPROXIMATE_DIRECT,
                    0L,
                    0
            );
        }

        static Descriptor building() {
            return new Descriptor(
                    LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_BUILDING,
                    0L,
                    0
            );
        }

        static Descriptor failClosed() {
            return new Descriptor(
                    LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_FAIL_CLOSED,
                    0L,
                    0
            );
        }
    }

    private record CameraParts(
            int blockX,
            int blockY,
            int blockZ,
            float fractionX,
            float fractionY,
            float fractionZ
    ) {
    }

    private record SourceParts(
            int blockX,
            int blockY,
            int blockZ,
            float fractionX,
            float fractionY,
            float fractionZ
    ) {
    }
}
