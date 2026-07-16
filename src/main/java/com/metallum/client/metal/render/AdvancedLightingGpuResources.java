package com.metallum.client.metal.render;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.LightFrameSnapshot;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.shader.AdvancedLightingBindingAbi;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.framegraph.AdvancedLightingFrameGraph;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.renderer.AdvancedLightingLayout;
import com.metallum.client.renderer.temporal.FrameState;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;

/** Owns one size-generation of native clustered-lighting buffers and its Java upload packet. */
final class AdvancedLightingGpuResources implements AutoCloseable {
    static final int STATUS_OK = 1;
    static final int STATUS_RING_SLOT_BUSY = -12;
    static final int EMPTY_PRODUCTION_PASS_COUNT = 3;
    static final int PRODUCTION_PASS_COUNT = 4;
    static final int PRODUCTION_ENCODER_COUNT = 2;
    static final int RESIDENT_PSO_COUNT = 9;
    static final int PRODUCTION_WORK_QUEUE_COUNT = 2;

    private static final int BATCH_MAGIC = 0x31424c4d;
    private static final int ORDERED_BATCH_FLAG = 1;
    private static final int CLUSTER_MASK_BATCH_FLAG = 1 << 1;
    private static final int NATIVE_LAYOUT_BYTES = 128;
    private static final int COMPLETED_STATS_BYTES = 128;

    private static final int BUFFER_LIGHTS = 0;
    private static final int BUFFER_HEADERS = 1;
    private static final int BUFFER_INDICES = 2;
    private static final int BUFFER_PARAMS = 3;
    private static final int BUFFER_STATISTICS = 4;
    private static final int BUFFER_SCRATCH = 5;

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    record Bindings(
            MemorySegment params,
            MemorySegment lights,
            MemorySegment masks,
            MemorySegment indices
    ) {
        Bindings {
            requireHandle(params, "lighting params");
            requireHandle(lights, "GPU lights");
            requireHandle(masks, "cluster membership masks");
            requireHandle(indices, "cluster indices");
        }
    }

    record FrameUpload(MemorySegment packet, int lightCount, long uploadBytes, int dispatchCount) {
        FrameUpload {
            Objects.requireNonNull(packet, "packet");
            if (packet.byteSize() != uploadBytes || lightCount < 0 || uploadBytes <= 0L
                    || dispatchCount != productionDispatchCount(lightCount)) {
                throw new IllegalArgumentException("Invalid Advanced frame upload declaration");
            }
        }
    }

    private final AdvancedLightingLayout.Budget budget;
    private final long generation;
    private final Arena arena;
    private final MemorySegment uploadPacket;
    private final MemorySegment completedStats;
    private final Bindings bindings;
    private MemorySegment context;

    private AdvancedLightingGpuResources(
            final AdvancedLightingLayout.Budget budget,
            final long generation,
            final Arena arena,
            final MemorySegment uploadPacket,
            final MemorySegment completedStats,
            final Bindings bindings,
            final MemorySegment context
    ) {
        this.budget = budget;
        this.generation = generation;
        this.arena = arena;
        this.uploadPacket = uploadPacket;
        this.completedStats = completedStats;
        this.bindings = bindings;
        this.context = context;
    }

    static void validateNativeAbi() {
        AdvancedLightingFrameGraph.initialize();
        int version = MetalNativeBridge.metallum_lighting_batch_abi_version_v1();
        if (version != AdvancedLightingBindingAbi.VERSION) {
            throw new IllegalStateException(
                    "Native Advanced lighting ABI version mismatch: " + version
            );
        }
        try (Arena probeArena = Arena.ofConfined()) {
            MemorySegment layout = probeArena.allocate(NATIVE_LAYOUT_BYTES, Long.BYTES);
            int status = MetalNativeBridge.metallum_lighting_layout_v1(layout);
            if (status != STATUS_OK) {
                throw new IllegalStateException(
                        "Native Advanced lighting layout query failed: " + status
                );
            }
            requireLayout(layout);
        }
    }

    static AdvancedLightingGpuResources create(
            final MemorySegment device,
            final long generation,
            final AdvancedLightingLayout.Budget budget
    ) {
        Objects.requireNonNull(budget, "budget");
        if (generation <= 0L) {
            throw new IllegalArgumentException("Lighting generation must be positive");
        }
        validateNativeAbi();
        MemorySegment context = MetalNativeBridge.metallum_lighting_create_context_v1(
                device,
                generation,
                budget.maxLights(),
                budget.indexCapacity(),
                budget.clustersX(),
                budget.clustersY(),
                budget.clustersZ()
        );
        if (MetalNativeBridge.isNullHandle(context)) {
            throw new IllegalStateException("Native Advanced lighting context creation failed");
        }

        Arena arena = null;
        try {
            requireBuffer(context, BUFFER_LIGHTS, budget.gpuLightBytes(), "GPU lights");
            requireBuffer(context, BUFFER_HEADERS, budget.clusterHeaderBytes(), "cluster headers");
            requireBuffer(context, BUFFER_INDICES, budget.clusterIndexBytes(), "cluster indices");
            requireBuffer(context, BUFFER_PARAMS, AdvancedLightingLayout.LIGHTING_PARAMS_BYTES,
                    "lighting params");
            requireBuffer(context, BUFFER_STATISTICS, AdvancedLightingLayout.STATISTICS_BYTES,
                    "cluster statistics");
            requireBuffer(context, BUFFER_SCRATCH, budget.clusterScratchBytes(), "cluster scratch");

            Bindings bindings = new Bindings(
                    buffer(context, BUFFER_PARAMS),
                    buffer(context, BUFFER_LIGHTS),
                    buffer(context, BUFFER_SCRATCH),
                    buffer(context, BUFFER_INDICES)
            );
            arena = Arena.ofShared();
            long packetCapacity = Math.addExact(
                    AdvancedLightingLayout.UPLOAD_HEADER_BYTES,
                    Math.multiplyExact((long) budget.maxLights(),
                            AdvancedLightingLayout.GPU_LIGHT_STRIDE)
            );
            MemorySegment uploadPacket = arena.allocate(packetCapacity, Long.BYTES);
            MemorySegment completedStats = arena.allocate(COMPLETED_STATS_BYTES, Long.BYTES);
            return new AdvancedLightingGpuResources(
                    budget,
                    generation,
                    arena,
                    uploadPacket,
                    completedStats,
                    bindings,
                    context
            );
        } catch (RuntimeException | Error failure) {
            if (arena != null) {
                arena.close();
            }
            MetalNativeBridge.metallum_lighting_release_context_v1(context);
            throw failure;
        }
    }

    long generation() {
        return this.generation;
    }

    AdvancedLightingLayout.Budget budget() {
        return this.budget;
    }

    Bindings bindings() {
        ensureOpen();
        return this.bindings;
    }

    FrameUpload encode(
            final LightFrameSnapshot snapshot,
            final FrameState frame
    ) {
        ensureOpen();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(frame, "frame");
        int lightCount = snapshot.lights().size();
        if (lightCount > this.budget.maxLights()) {
            throw new IllegalArgumentException("Light snapshot exceeds the generation capacity");
        }
        if (frame.lightingGenerationId() != this.generation
                || frame.advancedLightingWork().lightCount() != lightCount) {
            throw new IllegalArgumentException("Light upload does not match its FrameState");
        }
        long byteSize = Math.addExact(
                AdvancedLightingLayout.UPLOAD_HEADER_BYTES,
                Math.multiplyExact((long) lightCount, AdvancedLightingLayout.GPU_LIGHT_STRIDE)
        );
        MemorySegment packet = this.uploadPacket.asSlice(0L, byteSize);
        packet.fill((byte) 0);
        putInt(packet, 0, BATCH_MAGIC);
        putInt(packet, 4, AdvancedLightingBindingAbi.VERSION);
        putInt(packet, 8, Math.toIntExact(byteSize));
        putInt(packet, 12, AdvancedLightingLayout.UPLOAD_HEADER_BYTES);
        putInt(packet, 16, AdvancedLightingLayout.GPU_LIGHT_STRIDE);
        putInt(packet, 20, lightCount);
        putInt(packet, 24, frame.inFlightSlot());
        putInt(packet, 28, ORDERED_BATCH_FLAG | CLUSTER_MASK_BATCH_FLAG);
        putLong(packet, 32, frame.frameId());
        putLong(packet, 40, frame.submitIndex());
        putLong(packet, 48, frame.lightingGenerationId());

        FrameState.CameraPosition camera = frame.currentCameraPosition();
        int generationLow = (int) frame.lightingGenerationId();
        for (int index = 0; index < lightCount; index++) {
            AdvancedLight light = snapshot.lights().get(index);
            long base = AdvancedLightingLayout.UPLOAD_HEADER_BYTES
                    + (long) index * AdvancedLightingLayout.GPU_LIGHT_STRIDE;
            putFloat(packet, base, relative(light.x(), camera.x(), "light x"));
            putFloat(packet, base + 4, relative(light.y(), camera.y(), "light y"));
            putFloat(packet, base + 8, relative(light.z(), camera.z(), "light z"));
            putFloat(packet, base + 12, Math.min(light.radius(), (float) frame.farPlane()));
            putFloat(packet, base + 16, light.red());
            putFloat(packet, base + 20, light.green());
            putFloat(packet, base + 24, light.blue());
            putFloat(packet, base + 28, light.intensity());
            putInt(packet, base + 32, (int) light.stableId());
            putInt(packet, base + 36, (int) (light.stableId() >>> 32));
            putInt(packet, base + 40, light.kind() == LightSourceKind.BLOCK ? 1 : 2);
            putInt(packet, base + 44, generationLow);
        }
        return new FrameUpload(packet, lightCount, byteSize, productionDispatchCount(lightCount));
    }

    static int productionDispatchCount(final int lightCount) {
        if (lightCount < 0) {
            throw new IllegalArgumentException("Light count must be non-negative");
        }
        return lightCount == 0 ? 1 : 3;
    }

    int uploadAndBuild(final MTLCommandBuffer commandBuffer, final FrameUpload upload) {
        ensureOpen();
        return commandBuffer.encodeAdvancedLighting(this.context, upload.packet());
    }

    int readLastCompletedStats() {
        ensureOpen();
        this.completedStats.fill((byte) 0);
        return MetalNativeBridge.metallum_lighting_last_completed_stats_v1(
                this.context,
                this.completedStats
        );
    }

    @Override
    public void close() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return;
        }
        MetalNativeBridge.metallum_lighting_release_context_v1(this.context);
        this.context = MemorySegment.NULL;
        this.arena.close();
    }

    private static void requireLayout(final MemorySegment layout) {
        int[] expected = {
                AdvancedLightingBindingAbi.VERSION,
                NATIVE_LAYOUT_BYTES,
                AdvancedLightingLayout.UPLOAD_HEADER_BYTES,
                AdvancedLightingLayout.GPU_LIGHT_STRIDE,
                AdvancedLightingLayout.LIGHTING_PARAMS_BYTES,
                AdvancedLightingLayout.CLUSTER_HEADER_STRIDE,
                AdvancedLightingLayout.CLUSTER_SCRATCH_STRIDE,
                AdvancedLightingLayout.LIGHT_INDEX_STRIDE,
                AdvancedLightingLayout.STATISTICS_BYTES,
                AdvancedLightingLayout.UPLOAD_RING_SLOTS,
                AdvancedLightingLayout.TILE_SIZE,
                AdvancedLightingLayout.DEPTH_SLICES,
                AdvancedLightingLayout.MAX_LIGHTS_PER_CLUSTER,
                AdvancedLightingBindingAbi.PARAMS_VIEW_ROTATION_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_PROJECTION_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_GRID_AND_LIGHT_COUNT_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_EXTENT_AND_CLUSTER_CAP_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_DEPTH_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_FRAME_ID_AND_GENERATION_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_CAPACITIES_AND_FLAGS_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_RESERVED0_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_RESERVED1_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_RESERVED2_OFFSET,
                AdvancedLightingBindingAbi.PARAMS_SLOT,
                AdvancedLightingBindingAbi.LIGHTS_SLOT,
                AdvancedLightingBindingAbi.CLUSTER_MASKS_SLOT,
                AdvancedLightingBindingAbi.CLUSTER_INDICES_SLOT,
                AdvancedLightingLayout.NATIVE_BUFFER_GUARD_BYTES
        };
        for (int index = 0; index < expected.length; index++) {
            int actual = layout.get(LE_INT, (long) index * Integer.BYTES);
            if (actual != expected[index]) {
                throw new IllegalStateException(
                        "Native Advanced lighting layout mismatch at word " + index
                                + ": expected " + expected[index] + ", got " + actual
                );
            }
        }
    }

    private static void requireBuffer(
            final MemorySegment context,
            final int kind,
            final long expectedBytes,
            final String name
    ) {
        MemorySegment handle = buffer(context, kind);
        requireHandle(handle, name);
        long actualBytes = MetalNativeBridge.metallum_lighting_context_buffer_bytes_v1(
                context,
                kind
        );
        if (actualBytes != expectedBytes) {
            throw new IllegalStateException(
                    "Native " + name + " size mismatch: expected " + expectedBytes
                            + ", got " + actualBytes
            );
        }
    }

    private static MemorySegment buffer(final MemorySegment context, final int kind) {
        return MetalNativeBridge.metallum_lighting_context_buffer_v1(context, kind);
    }

    private static void requireHandle(final MemorySegment handle, final String name) {
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("Native " + name + " buffer is unavailable");
        }
    }

    private static float relative(final double value, final double camera, final String name) {
        float result = (float) (value - camera);
        if (!Float.isFinite(result)) {
            throw new IllegalArgumentException(name + " is outside the GPU coordinate range");
        }
        return result;
    }

    private static void putInt(final MemorySegment packet, final long offset, final int value) {
        packet.set(LE_INT, offset, value);
    }

    private static void putLong(final MemorySegment packet, final long offset, final long value) {
        packet.set(LE_LONG, offset, value);
    }

    private static void putFloat(final MemorySegment packet, final long offset, final float value) {
        packet.set(LE_FLOAT, offset, value);
    }

    private void ensureOpen() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            throw new IllegalStateException("Advanced lighting resources are closed");
        }
    }
}
