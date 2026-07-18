package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.EntityShadowProxy;
import com.metallum.client.lighting.EntityShadowProxySnapshot;
import com.metallum.client.lighting.FrameLightOrder;
import com.metallum.client.lighting.LightFrameSnapshot;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.ShadowEmitterFootprint;
import com.metallum.client.lighting.shader.VoxelShadowBindingAbi;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;
import com.metallum.client.voxel.VoxelBrickPatch;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.metallum.client.voxel.VoxelShadowCacheBuilder;
import com.metallum.client.voxel.VoxelShadowCacheMirror;
import com.mojang.blaze3d.buffers.GpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns L6's bounded upload rings and update-driven cached visibility. */
final class LocalVoxelShadowGpuResources implements AutoCloseable {
    static final int PRODUCTION_WORK_QUEUE_COUNT = 1;
    record PreparedFrame(
            boolean active,
            int shadowedLocalLights,
            int proxyCount,
            long submitIndex
    ) {
    }

    private static final float MINIMUM_TRANSMITTANCE = 1.0f / 32.0f;
    private static final String DIAGNOSTIC_SHADOWED_LIGHTS_ENV =
            "METALLUM_L6_DIAGNOSTIC_SHADOWED_LIGHTS";

    private final long generation;
    private final LocalVoxelShadowLayout.Budget budget;
    private final MetalDevice device;
    private final MetalGpuBuffer paramsRing;
    private final MetalGpuBuffer proxyRing;
    private final MetalGpuBuffer visibilityCache;
    private final ExecutorService cacheWorker;
    private CacheKey desiredCacheKey;
    private CacheKey activeCacheKey;
    private VoxelShadowCacheMirror.Snapshot activeCacheMirror;
    private CacheKey failedCacheKey;
    private CacheKey pendingCacheKey;
    private CompletableFuture<CacheBuild> pendingCacheBuild;
    private PreparedFrame prepared;
    private boolean closed;

    private LocalVoxelShadowGpuResources(
            final MetalDevice device,
            final long generation,
            final LocalVoxelShadowLayout.Budget budget,
            final MetalGpuBuffer paramsRing,
            final MetalGpuBuffer proxyRing,
            final MetalGpuBuffer visibilityCache
    ) {
        this.device = device;
        this.generation = generation;
        this.budget = budget;
        this.paramsRing = paramsRing;
        this.proxyRing = proxyRing;
        this.visibilityCache = visibilityCache;
        this.cacheWorker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Metallum L6 shadow-cache builder");
            thread.setDaemon(true);
            return thread;
        });
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
        MetalGpuBuffer cache = null;
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
            cache = new MetalGpuBuffer(
                    device,
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM,
                    budget.visibilityCacheBytes()
            );
            cache.sliceStorage(0L, budget.visibilityCacheBytes()).put(
                    VoxelShadowCacheBuilder.visiblePayload(budget.shadowedLocalLights())
            );
            return new LocalVoxelShadowGpuResources(
                    device, generation, budget, params, proxies, cache
            );
        } catch (RuntimeException | Error failure) {
            if (cache != null) {
                cache.close();
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
                LocalVoxelShadowLayout.PARAMS_BYTES,
                Math.multiplyExact(
                        (long) budget.maxEntityProxies(),
                        LocalVoxelShadowLayout.PROXY_STRIDE_BYTES
                )
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
        int slot = frame.inFlightSlot();
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
        zero(params);
        zero(proxies);

        CameraParts camera = cameraParts(frame.currentCameraPosition());
        putMatrix(params, VoxelShadowBindingAbi.WORLD_FROM_VIEW_MATRIX_OFFSET,
                frame.currentTransforms().unjitteredCamera());
        putInt4(params, VoxelShadowBindingAbi.CAMERA_BLOCK_AND_FLAGS_OFFSET,
                camera.blockX(), camera.blockY(), camera.blockZ(), -1);
        putFloat4(params,
                VoxelShadowBindingAbi.CAMERA_FRACTION_AND_MIN_TRANSMITTANCE_OFFSET,
                camera.fractionX(), camera.fractionY(), camera.fractionZ(),
                MINIMUM_TRANSMITTANCE);
        putInt4(params, VoxelShadowBindingAbi.PROXY_AND_FRAME_OFFSET,
                0, this.budget.maxEntityProxies(),
                (int) frame.frameId(), (int) (frame.frameId() >>> 32));

        boolean active = voxelResources != null && snapshot != null
                && voxelResources.matches(
                this.generation, voxelResources.budget(), snapshot);
        int shadowedLocalLights = diagnosticShadowedLocalLights(
                this.budget.shadowedLocalLights(),
                MetalGpuTiming.isEnabled(),
                System.getenv(DIAGNOSTIC_SHADOWED_LIGHTS_ENV)
        );
        int proxyCount = 0;
        int[] shadowLightIndices = {-1, -1};
        if (active) {
            try {
                packLevels(params, snapshot);
                proxyCount = packProxies(
                        proxies, proxySnapshot, frame.currentCameraPosition());
                shadowLightIndices = selectShadowLightIndices(
                        lightSnapshot,
                        snapshot,
                        frame.currentCameraPosition(),
                        shadowedLocalLights
                );
                if (!prepareVisibilityCache(
                        snapshot, lightSnapshot, shadowLightIndices)) {
                    shadowLightIndices = new int[]{-1, -1};
                }
                shadowedLocalLights = shadowLightIndices[0] < 0
                        ? 0 : shadowLightIndices[1] < 0 ? 1 : 2;
                params.putInt(
                        VoxelShadowBindingAbi.SHADOW_LIGHT_INDEX_0_OFFSET,
                        shadowLightIndices[0]
                );
                putInt4(params, VoxelShadowBindingAbi.CAPS_OFFSET,
                        VoxelShadowBindingAbi.VERSION,
                        snapshot.levels().size(),
                        this.budget.maxSteps(),
                        shadowedLocalLights);
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
                params.putInt(
                        VoxelShadowBindingAbi.SHADOW_LIGHT_INDEX_1_OFFSET,
                        shadowLightIndices[1]
                );
            } catch (RuntimeException failure) {
                active = false;
                shadowedLocalLights = 0;
                proxyCount = 0;
                zero(params);
                zero(proxies);
                putMatrix(params, VoxelShadowBindingAbi.WORLD_FROM_VIEW_MATRIX_OFFSET,
                        frame.currentTransforms().unjitteredCamera());
                putInt4(params, VoxelShadowBindingAbi.CAMERA_BLOCK_AND_FLAGS_OFFSET,
                        camera.blockX(), camera.blockY(), camera.blockZ(), -1);
                putFloat4(params,
                        VoxelShadowBindingAbi.CAMERA_FRACTION_AND_MIN_TRANSMITTANCE_OFFSET,
                        camera.fractionX(), camera.fractionY(), camera.fractionZ(),
                        MINIMUM_TRANSMITTANCE);
                putInt4(params, VoxelShadowBindingAbi.CAPS_OFFSET,
                        VoxelShadowBindingAbi.VERSION, 0, this.budget.maxSteps(), 0);
                putInt4(params, VoxelShadowBindingAbi.PROXY_AND_FRAME_OFFSET,
                        0, this.budget.maxEntityProxies(),
                        (int) frame.frameId(), (int) (frame.frameId() >>> 32));
                params.putInt(VoxelShadowBindingAbi.SHADOW_LIGHT_INDEX_1_OFFSET, -1);
            }
        } else {
            putInt4(params, VoxelShadowBindingAbi.CAPS_OFFSET,
                    VoxelShadowBindingAbi.VERSION, 0, this.budget.maxSteps(), 0);
            params.putInt(VoxelShadowBindingAbi.SHADOW_LIGHT_INDEX_1_OFFSET, -1);
        }
        this.prepared = new PreparedFrame(
                active,
                active ? shadowedLocalLights : 0,
                proxyCount,
                frame.submitIndex()
        );
        return this.prepared;
    }

    static int diagnosticShadowedLocalLights(
            final int productionValue,
            final boolean detailTimingEnabled,
            final String configuredValue
    ) {
        if (productionValue < 0
                || productionValue > LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS) {
            throw new IllegalArgumentException("Production L6 light cap is outside the shader ABI");
        }
        if (!detailTimingEnabled || configuredValue == null) {
            return productionValue;
        }
        try {
            int parsed = Integer.parseInt(configuredValue.trim());
            return parsed >= 0 && parsed <= productionValue ? parsed : productionValue;
        } catch (NumberFormatException ignored) {
            return productionValue;
        }
    }

    static int[] selectShadowLightIndices(
            final LightFrameSnapshot lightSnapshot,
            final VoxelClipmapSnapshot voxelSnapshot,
            final FrameState.CameraPosition camera,
            final int maximumCount
    ) {
        Objects.requireNonNull(camera, "camera");
        if (maximumCount < 0
                || maximumCount > LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS) {
            throw new IllegalArgumentException("L6 selected-light cap is outside the shader ABI");
        }
        int[] selected = {-1, -1};
        if (maximumCount == 0 || lightSnapshot == null || lightSnapshot.lights().isEmpty()
                || voxelSnapshot == null || voxelSnapshot.levels().isEmpty()) {
            return selected;
        }
        Comparator<AdvancedLight> materialRelevance = FrameLightOrder.comparator(
                camera.x(), camera.y(), camera.z());
        Comparator<AdvancedLight> localRelevance = (left, right) -> {
            int proximity = Double.compare(
                    influenceDistance(left, camera),
                    influenceDistance(right, camera)
            );
            return proximity != 0 ? proximity : materialRelevance.compare(left, right);
        };
        VoxelClipmapSnapshot.Level coarsest = voxelSnapshot.levels().getLast();
        List<AdvancedLight> lights = lightSnapshot.lights();
        for (int index = 0; index < lights.size(); index++) {
            // A moving emitter would invalidate the whole cube every frame. Dynamic entities
            // remain unshadowed emitters; their proxies still occlude cached block lights.
            if (lights.get(index).kind() != LightSourceKind.BLOCK
                    || !fullyCoveredBy(coarsest, lights.get(index))) {
                continue;
            }
            if (selected[0] < 0
                    || localRelevance.compare(lights.get(index), lights.get(selected[0])) < 0) {
                if (maximumCount > 1) {
                    selected[1] = selected[0];
                }
                selected[0] = index;
            } else if (maximumCount > 1 && (selected[1] < 0
                    || localRelevance.compare(lights.get(index), lights.get(selected[1])) < 0)) {
                selected[1] = index;
            }
        }
        return selected;
    }

    private static double influenceDistance(
            final AdvancedLight light,
            final FrameState.CameraPosition camera
    ) {
        double dx = light.x() - camera.x();
        double dy = light.y() - camera.y();
        double dz = light.z() - camera.z();
        return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - light.radius());
    }

    private static boolean fullyCoveredBy(
            final VoxelClipmapSnapshot.Level level,
            final AdvancedLight light
    ) {
        int brickBlockEdge = VoxelBrickPatch.LOGICAL_EDGE / level.subdivision();
        double minimumX = (double) level.originBrickX() * brickBlockEdge;
        double minimumY = (double) level.originBrickY() * brickBlockEdge;
        double minimumZ = (double) level.originBrickZ() * brickBlockEdge;
        double maximumX = minimumX + (double) level.brickDimension() * brickBlockEdge;
        double maximumY = minimumY + (double) level.brickDimension() * brickBlockEdge;
        double maximumZ = minimumZ + (double) level.brickDimension() * brickBlockEdge;
        double radius = light.radius();
        return light.x() - radius >= minimumX && light.x() + radius < maximumX
                && light.y() - radius >= minimumY && light.y() + radius < maximumY
                && light.z() - radius >= minimumZ && light.z() + radius < maximumZ;
    }

    void bind(final MTLRenderCommandEncoder encoder, final int inFlightSlot, final long submitIndex) {
        ensureOpen();
        Objects.requireNonNull(encoder, "encoder");
        if (inFlightSlot < 0 || inFlightSlot >= LocalVoxelShadowLayout.PARAMS_RING_SLOTS
                || this.prepared == null || this.prepared.submitIndex() != submitIndex) {
            throw new IllegalStateException("L6 bindings are not ready for this frame");
        }
        long proxySlotBytes = Math.multiplyExact(
                (long) this.budget.maxEntityProxies(),
                LocalVoxelShadowLayout.PROXY_STRIDE_BYTES
        );
        encoder.setBuffer(
                this.visibilityCache.nativeHandle(), 0L,
                VoxelShadowBindingAbi.VISIBILITY_CACHE_BUFFER_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        encoder.setBuffer(
                this.proxyRing.nativeHandle(), (long) inFlightSlot * proxySlotBytes,
                VoxelShadowBindingAbi.PROXY_BUFFER_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        encoder.setBuffer(
                this.paramsRing.nativeHandle(),
                (long) inFlightSlot * LocalVoxelShadowLayout.PARAMS_BYTES,
                VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );

    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.prepared = null;
        if (this.pendingCacheBuild != null) {
            this.pendingCacheBuild.cancel(true);
            this.pendingCacheBuild = null;
            this.pendingCacheKey = null;
        }
        this.cacheWorker.shutdownNow();
        this.visibilityCache.close();
        this.proxyRing.close();
        this.paramsRing.close();
    }

    private boolean prepareVisibilityCache(
            final VoxelClipmapSnapshot clipmap,
            final LightFrameSnapshot lightSnapshot,
            final int[] selectedIndices
    ) {
        VoxelShadowCacheMirror.Snapshot mirror =
                VoxelShadowCacheMirror.global().snapshot(clipmap);
        List<AdvancedLight> selectedLights = selectedLights(
                lightSnapshot, selectedIndices
        );
        if (mirror == null || selectedLights.isEmpty()) {
            this.desiredCacheKey = null;
            return false;
        }
        CacheKey requested = CacheKey.of(
                mirror, selectedLights, this.budget.maxSteps()
        );
        this.desiredCacheKey = requested;
        consumeCompletedCacheBuild(mirror, selectedLights, requested);
        if (requested.equals(this.activeCacheKey)) {
            return true;
        }
        if (mirror.current()
                && CacheKey.sameStableConfiguration(this.activeCacheKey, requested)
                && VoxelShadowCacheBuilder.relevantGeometryEquals(
                this.activeCacheMirror, mirror, selectedLights)) {
            this.activeCacheKey = requested;
            this.activeCacheMirror = mirror;
            return true;
        }
        if (!mirror.current()) {
            return false;
        }
        if (this.pendingCacheBuild == null
                && !requested.equals(this.failedCacheKey)) {
            VoxelShadowCacheMirror.Snapshot capturedMirror = mirror;
            List<AdvancedLight> capturedLights = List.copyOf(selectedLights);
            this.pendingCacheKey = requested;
            this.pendingCacheBuild = CompletableFuture.supplyAsync(
                    () -> buildVisibilityCache(
                            requested,
                            capturedMirror,
                            capturedLights,
                            this.budget.shadowedLocalLights(),
                            this.budget.maxSteps()
                    ),
                    this.cacheWorker
            );
        }
        return canReuseWhileUpdating(this.activeCacheKey, requested);
    }

    private void consumeCompletedCacheBuild(
            final VoxelShadowCacheMirror.Snapshot requestedMirror,
            final List<AdvancedLight> requestedLights,
            final CacheKey requestedKey
    ) {
        CompletableFuture<CacheBuild> pending = this.pendingCacheBuild;
        if (pending == null || !pending.isDone()) {
            return;
        }
        CacheKey submittedKey = this.pendingCacheKey;
        this.pendingCacheBuild = null;
        this.pendingCacheKey = null;
        try {
            CacheBuild completed = pending.join();
            boolean exactRevision = completed.key().equals(requestedKey);
            boolean equivalentRevision = CacheKey.sameStableConfiguration(
                    completed.key(), requestedKey
            ) && VoxelShadowCacheBuilder.relevantGeometryEquals(
                    completed.mirror(), requestedMirror, requestedLights
            );
            if (!exactRevision && !equivalentRevision) {
                return;
            }
            byte[] payload = completed.result().payload();
            if (payload.length != this.budget.visibilityCacheBytes()) {
                throw new IllegalStateException("L6 visibility cache payload size changed");
            }
            this.device.waitForPreviouslySubmittedGpuWork();
            ByteBuffer destination = this.visibilityCache.sliceStorage(
                    0L, this.budget.visibilityCacheBytes()
            );
            destination.clear();
            destination.put(payload);
            this.activeCacheKey = requestedKey;
            this.activeCacheMirror = requestedMirror;
            this.failedCacheKey = null;
            Metallum.LOGGER.info(
                    "L6 cached local shadows ready: lights={}, levels={}, hitRays={}/{}, "
                            + "bytes={}, buildMs={}",
                    completed.key().lights().size(),
                    completed.result().cacheLevels(),
                    completed.result().raysWithHits(),
                    completed.result().totalRays(),
                    payload.length,
                    completed.buildNanos() / 1_000_000.0
            );
        } catch (RuntimeException failure) {
            if (!canReuseWhileUpdating(this.activeCacheKey, this.desiredCacheKey)) {
                this.activeCacheKey = null;
                this.activeCacheMirror = null;
            }
            this.failedCacheKey = submittedKey;
            Metallum.LOGGER.warn(
                    "L6 visibility-cache update failed; retaining only a compatible prior cache",
                    failure
            );
        }
    }

    private static List<AdvancedLight> selectedLights(
            final LightFrameSnapshot snapshot,
            final int[] selectedIndices
    ) {
        if (snapshot == null || selectedIndices == null
                || selectedIndices.length != LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS) {
            return List.of();
        }
        List<AdvancedLight> selected = new ArrayList<>(selectedIndices.length);
        for (int index : selectedIndices) {
            if (index < 0) {
                continue;
            }
            if (index >= snapshot.lights().size()) {
                return List.of();
            }
            selected.add(snapshot.lights().get(index));
        }
        return List.copyOf(selected);
    }

    private static CacheBuild buildVisibilityCache(
            final CacheKey key,
            final VoxelShadowCacheMirror.Snapshot mirror,
            final List<AdvancedLight> lights,
            final int lightCapacity,
            final int maxSteps
    ) {
        long started = System.nanoTime();
        VoxelShadowCacheBuilder.Result result = VoxelShadowCacheBuilder.build(
                mirror, lights, lightCapacity, maxSteps
        );
        return new CacheBuild(key, mirror, result, System.nanoTime() - started);
    }

    private record CacheBuild(
            CacheKey key,
            VoxelShadowCacheMirror.Snapshot mirror,
            VoxelShadowCacheBuilder.Result result,
            long buildNanos
    ) {
        private CacheBuild {
            if (buildNanos < 0L) {
                throw new IllegalArgumentException("L6 cache build duration is negative");
            }
        }
    }

    private record CacheKey(
            long worldGeneration,
            long clipmapGeneration,
            long mirrorRevision,
            int maxSteps,
            List<CacheLevelKey> levels,
            List<CacheLightKey> lights
    ) {
        private CacheKey {
            levels = List.copyOf(levels);
            lights = List.copyOf(lights);
        }

        private static CacheKey of(
                final VoxelShadowCacheMirror.Snapshot mirror,
                final List<AdvancedLight> lights,
                final int maxSteps
        ) {
            return new CacheKey(
                    mirror.clipmap().world().generation(),
                    mirror.clipmap().clipmapGeneration(),
                    mirror.revision(),
                    maxSteps,
                    mirror.clipmap().levels().stream().map(CacheLevelKey::of).toList(),
                    lights.stream().map(CacheLightKey::of).toList()
            );
        }

        private static boolean sameStableConfiguration(
                final CacheKey left,
                final CacheKey right
        ) {
            return left != null && right != null
                    && left.worldGeneration == right.worldGeneration
                    && left.clipmapGeneration == right.clipmapGeneration
                    && left.maxSteps == right.maxSteps
                    && left.levels.equals(right.levels)
                    && left.lights.equals(right.lights);
        }
    }

    private record CacheLevelKey(
            int level,
            int subdivision,
            int logicalEdge,
            long originBrickX,
            long originBrickY,
            long originBrickZ,
            int brickDimension
    ) {
        private static CacheLevelKey of(final VoxelClipmapSnapshot.Level level) {
            return new CacheLevelKey(
                    level.level(), level.subdivision(), level.logicalEdge(),
                    level.originBrickX(), level.originBrickY(), level.originBrickZ(),
                    level.brickDimension()
            );
        }
    }

    private static boolean canReuseWhileUpdating(
            final CacheKey active,
            final CacheKey requested
    ) {
        return CacheKey.sameStableConfiguration(active, requested);
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

    private void packLevels(final ByteBuffer params, final VoxelClipmapSnapshot snapshot) {
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
            int spanBlocks = Math.multiplyExact(level.brickDimension(), brickBlockEdge);
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
        int count = Math.min(snapshot.proxies().size(), this.budget.maxEntityProxies());
        for (int index = 0; index < count; index++) {
            EntityShadowProxy proxy = snapshot.proxies().get(index);
            int offset = index * LocalVoxelShadowLayout.PROXY_STRIDE_BYTES;
            putFloat4(proxies, offset,
                    proxy.minRelativeX(camera.x()),
                    proxy.minRelativeY(camera.y()),
                    proxy.minRelativeZ(camera.z()), 0.0f);
            putFloat4(proxies, offset + 16,
                    proxy.maxRelativeX(camera.x()),
                    proxy.maxRelativeY(camera.y()),
                    proxy.maxRelativeZ(camera.z()), 0.0f);
        }
        return count;
    }

    private static CameraParts cameraParts(final FrameState.CameraPosition camera) {
        double blockX = Math.floor(camera.x());
        double blockY = Math.floor(camera.y());
        double blockZ = Math.floor(camera.z());
        if (blockX < Integer.MIN_VALUE || blockX > Integer.MAX_VALUE
                || blockY < Integer.MIN_VALUE || blockY > Integer.MAX_VALUE
                || blockZ < Integer.MIN_VALUE || blockZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("L6 camera block is outside the shader integer range");
        }
        return new CameraParts(
                (int) blockX, (int) blockY, (int) blockZ,
                (float) (camera.x() - blockX),
                (float) (camera.y() - blockY),
                (float) (camera.z() - blockZ)
        );
    }

    private static void putMatrix(final ByteBuffer target, final int offset, final Matrix4 matrix) {
        for (int index = 0; index < Matrix4.ELEMENT_COUNT; index++) {
            target.putFloat(offset + index * Float.BYTES, (float) matrix.element(index));
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

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("L6 local-shadow resources are closed");
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
}
