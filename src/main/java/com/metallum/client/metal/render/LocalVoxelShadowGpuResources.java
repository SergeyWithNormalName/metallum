package com.metallum.client.metal.render;

import com.metallum.client.lighting.EntityShadowProxy;
import com.metallum.client.lighting.EntityShadowProxySnapshot;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.FrameLightOrder;
import com.metallum.client.lighting.LightFrameSnapshot;
import com.metallum.client.lighting.shader.VoxelShadowBindingAbi;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;
import com.metallum.client.voxel.VoxelBrickPatch;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Comparator;
import java.util.Objects;

/** Owns L6's bounded shared upload rings and binds read-only L5 private resources. */
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
    private final MetalGpuBuffer paramsRing;
    private final MetalGpuBuffer proxyRing;
    private VoxelOccupancyGpuResources.ShadowBindings voxelBindings;
    private PreparedFrame prepared;
    private boolean closed;

    private LocalVoxelShadowGpuResources(
            final long generation,
            final LocalVoxelShadowLayout.Budget budget,
            final MetalGpuBuffer paramsRing,
            final MetalGpuBuffer proxyRing
    ) {
        this.generation = generation;
        this.budget = budget;
        this.paramsRing = paramsRing;
        this.proxyRing = proxyRing;
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
            return new LocalVoxelShadowGpuResources(generation, budget, params, proxies);
        } catch (RuntimeException | Error failure) {
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
        VoxelOccupancyGpuResources.ShadowBindings nextBindings = null;
        if (active) {
            try {
                nextBindings = voxelResources.shadowBindings();
                if (nextBindings.levelCount() != snapshot.levels().size()) {
                    throw new IllegalArgumentException("L6/L5 active level counts differ");
                }
                packLevels(params, snapshot);
                proxyCount = packProxies(
                        proxies, proxySnapshot, frame.currentCameraPosition());
                shadowLightIndices = selectShadowLightIndices(
                        lightSnapshot,
                        frame.currentCameraPosition(),
                        shadowedLocalLights
                );
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
                nextBindings = null;
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
        this.voxelBindings = nextBindings;
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
            final LightFrameSnapshot snapshot,
            final FrameState.CameraPosition camera,
            final int maximumCount
    ) {
        Objects.requireNonNull(camera, "camera");
        if (maximumCount < 0
                || maximumCount > LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS) {
            throw new IllegalArgumentException("L6 selected-light cap is outside the shader ABI");
        }
        int[] selected = {-1, -1};
        if (maximumCount == 0 || snapshot == null || snapshot.lights().isEmpty()) {
            return selected;
        }
        Comparator<AdvancedLight> relevance = FrameLightOrder.comparator(
                camera.x(), camera.y(), camera.z());
        List<AdvancedLight> lights = snapshot.lights();
        for (int index = 0; index < lights.size(); index++) {
            if (selected[0] < 0
                    || relevance.compare(lights.get(index), lights.get(selected[0])) < 0) {
                if (maximumCount > 1) {
                    selected[1] = selected[0];
                }
                selected[0] = index;
            } else if (maximumCount > 1 && (selected[1] < 0
                    || relevance.compare(lights.get(index), lights.get(selected[1])) < 0)) {
                selected[1] = index;
            }
        }
        return selected;
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

        MemorySegment fallback = this.proxyRing.nativeHandle();
        VoxelOccupancyGpuResources.ShadowBindings bindings = this.voxelBindings;
        int[] occupancySlots = VoxelShadowBindingAbi.occupancyTextureSlots();
        int[] opticalSlots = VoxelShadowBindingAbi.opticalTextureSlots();
        int[] metadataSlots = VoxelShadowBindingAbi.metadataBufferSlots();
        for (int level = 0; level < VoxelShadowBindingAbi.LEVEL_COUNT; level++) {
            MemorySegment occupancy = bindings != null && level < bindings.levelCount()
                    ? bindings.occupancy().get(level) : fallback;
            MemorySegment optical = bindings != null && level < bindings.levelCount()
                    ? bindings.optical().get(level) : fallback;
            MemorySegment metadata = bindings != null && level < bindings.levelCount()
                    ? bindings.metadata().get(level) : fallback;
            encoder.setBuffer(occupancy, 0L, occupancySlots[level],
                    MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            encoder.setBuffer(optical, 0L, opticalSlots[level],
                    MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            encoder.setBuffer(metadata, 0L, metadataSlots[level],
                    MetalCompiledRenderPipeline.STAGE_FRAGMENT);
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.prepared = null;
        this.voxelBindings = null;
        this.proxyRing.close();
        this.paramsRing.close();
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
