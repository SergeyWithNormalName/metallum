package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.renderer.LocalVoxelShadowAtlasLayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/**
 * Isolated native backend for a bounded set of moving L6 pages.
 *
 * <p>It intentionally owns no atlas allocation or descriptor policy. The L6 owner supplies
 * disjoint, triple-buffered atlas offsets and decides whether an encoded page is routable.</p>
 */
final class DynamicVoxelShadowGpuResources implements AutoCloseable {
    static final int ABI_VERSION = 1;
    static final int HEADER_BYTES = 48;
    static final int REQUEST_BYTES = 64;
    static final int MAX_LIGHTS = 8;
    static final int PAGE_ALIGNMENT_BYTES = 256;
    static final int STATUS_OK = 1;

    private static final int MAGIC = 0x3153_564d;
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    record Request(
            long stableId,
            long atlasOffset,
            int levelIndex,
            int edge,
            int maxSteps,
            int sourceBlockX,
            int sourceBlockY,
            int sourceBlockZ,
            float sourceFractionX,
            float sourceFractionY,
            float sourceFractionZ,
            float radius
    ) {
        Request {
            if (stableId == 0L || atlasOffset < 0L || atlasOffset % PAGE_ALIGNMENT_BYTES != 0L
                    || levelIndex < 0 || (edge != 16 && edge != 32)
                    || (maxSteps != 32 && maxSteps != 96)
                    || !finiteFraction(sourceFractionX) || !finiteFraction(sourceFractionY)
                    || !finiteFraction(sourceFractionZ)
                    || !Float.isFinite(radius) || radius <= 0.0f) {
                throw new IllegalArgumentException("Invalid dynamic voxel-shadow request");
            }
        }

        private static boolean finiteFraction(final float value) {
            return Float.isFinite(value) && value >= 0.0f && value < 1.0f;
        }
    }

    record FrameUpload(long frameId, MemorySegment packet, int requestCount) {
        FrameUpload {
            if (frameId <= 0L || packet == null || requestCount < 1 || requestCount > MAX_LIGHTS
                    || packet.byteSize() != HEADER_BYTES + (long) requestCount * REQUEST_BYTES) {
                throw new IllegalArgumentException("Invalid dynamic voxel-shadow frame packet");
            }
        }
    }

    private final Arena arena;
    private final MemorySegment packetStorage;
    private final long atlasSuffixOffset;
    private final long atlasSuffixBytes;
    private MemorySegment context;

    private DynamicVoxelShadowGpuResources(
            final Arena arena,
            final MemorySegment packetStorage,
            final long atlasSuffixOffset,
            final long atlasSuffixBytes,
            final MemorySegment context
    ) {
        this.arena = arena;
        this.packetStorage = packetStorage;
        this.atlasSuffixOffset = atlasSuffixOffset;
        this.atlasSuffixBytes = atlasSuffixBytes;
        this.context = context;
    }

    static DynamicVoxelShadowGpuResources create(
            final MetalDevice device,
            final long atlasSuffixOffset,
            final long atlasSuffixBytes
    ) {
        Objects.requireNonNull(device, "device");
        if (atlasSuffixOffset <= 0L || atlasSuffixBytes <= 0L
                || atlasSuffixOffset % PAGE_ALIGNMENT_BYTES != 0L
                || atlasSuffixBytes % PAGE_ALIGNMENT_BYTES != 0L) {
            throw new IllegalArgumentException("Invalid dynamic voxel-shadow atlas suffix");
        }
        Math.addExact(atlasSuffixOffset, atlasSuffixBytes);
        validateNativeAbi();
        Arena arena = Arena.ofShared();
        try {
            MemorySegment context = MetalNativeBridge.metallum_dynamic_shadow_create_context_v1(
                    device.metalDeviceHandle(), atlasSuffixOffset, atlasSuffixBytes
            );
            if (MetalNativeBridge.isNullHandle(context)) {
                throw new IllegalStateException("Native dynamic voxel-shadow context creation failed");
            }
            return new DynamicVoxelShadowGpuResources(
                    arena,
                    arena.allocate(HEADER_BYTES + (long) MAX_LIGHTS * REQUEST_BYTES, Long.BYTES),
                    atlasSuffixOffset,
                    atlasSuffixBytes,
                    context
            );
        } catch (RuntimeException | Error failure) {
            arena.close();
            throw failure;
        }
    }

    static void validateNativeAbi() {
        if (MetalNativeBridge.metallum_dynamic_shadow_abi_version_v1() != ABI_VERSION) {
            throw new IllegalStateException("Native dynamic voxel-shadow ABI version mismatch");
        }
        try (Arena probe = Arena.ofConfined()) {
            MemorySegment layout = probe.allocate(32L, Long.BYTES);
            if (MetalNativeBridge.metallum_dynamic_shadow_layout_v1(layout) != STATUS_OK
                    || getInt(layout, 0L) != ABI_VERSION
                    || getInt(layout, 4L) != HEADER_BYTES
                    || getInt(layout, 8L) != REQUEST_BYTES
                    || getInt(layout, 12L) != MAX_LIGHTS
                    || getInt(layout, 16L) != PAGE_ALIGNMENT_BYTES
                    || getInt(layout, 20L) != MAGIC) {
                throw new IllegalStateException("Native dynamic voxel-shadow layout mismatch");
            }
        }
    }

    FrameUpload prepare(
            final long lightingGeneration,
            final VoxelOccupancyGpuResources voxels,
            final long frameId,
            final List<Request> requests
    ) {
        ensureOpen();
        Objects.requireNonNull(voxels, "voxels");
        List<Request> copied = List.copyOf(Objects.requireNonNull(requests, "requests"));
        if (lightingGeneration <= 0L || frameId <= 0L || copied.isEmpty() || copied.size() > MAX_LIGHTS
                || voxels.lightingGeneration() != lightingGeneration) {
            throw new IllegalArgumentException("Dynamic voxel-shadow frame exceeds backend bounds");
        }
        long byteSize = HEADER_BYTES + (long) copied.size() * REQUEST_BYTES;
        for (int index = 0; index < copied.size(); index++) {
            Request request = copied.get(index);
            long pageBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(request.edge());
            long relativeOffset = request.atlasOffset() - this.atlasSuffixOffset;
            if (relativeOffset < 0L || relativeOffset > this.atlasSuffixBytes
                    || pageBytes > this.atlasSuffixBytes - relativeOffset) {
                throw new IllegalArgumentException(
                        "Dynamic voxel-shadow request escapes its atlas suffix"
                );
            }
            long requestEnd = Math.addExact(request.atlasOffset(), pageBytes);
            for (int previousIndex = 0; previousIndex < index; previousIndex++) {
                Request previous = copied.get(previousIndex);
                long previousEnd = Math.addExact(
                        previous.atlasOffset(),
                        LocalVoxelShadowAtlasLayout.pageAllocationBytes(previous.edge())
                );
                if (request.stableId() == previous.stableId()
                        || (request.atlasOffset() < previousEnd
                        && previous.atlasOffset() < requestEnd)) {
                    throw new IllegalArgumentException(
                            "Dynamic voxel-shadow requests overlap or duplicate a light"
                    );
                }
            }
        }
        MemorySegment packet = this.packetStorage.asSlice(0L, byteSize);
        packet.fill((byte) 0);
        putInt(packet, 0L, MAGIC);
        putInt(packet, 4L, ABI_VERSION);
        putInt(packet, 8L, Math.toIntExact(byteSize));
        putInt(packet, 12L, copied.size());
        putLong(packet, 16L, lightingGeneration);
        putLong(packet, 24L, voxels.clipmapGeneration());
        putLong(packet, 32L, voxels.worldGeneration());
        putLong(packet, 40L, frameId);
        for (int index = 0; index < copied.size(); index++) {
            Request request = copied.get(index);
            if (request.levelIndex() >= voxels.levels().size()) {
                throw new IllegalArgumentException("Dynamic voxel-shadow request selects no L5 level");
            }
            long offset = HEADER_BYTES + (long) index * REQUEST_BYTES;
            putLong(packet, offset, request.stableId());
            putLong(packet, offset + 8L, request.atlasOffset());
            putInt(packet, offset + 16L, request.levelIndex());
            putInt(packet, offset + 20L, request.edge());
            putInt(packet, offset + 24L, request.maxSteps());
            putInt(packet, offset + 28L, 0);
            putInt(packet, offset + 32L, request.sourceBlockX());
            putInt(packet, offset + 36L, request.sourceBlockY());
            putInt(packet, offset + 40L, request.sourceBlockZ());
            packet.set(LE_FLOAT, offset + 44L, request.sourceFractionX());
            packet.set(LE_FLOAT, offset + 48L, request.sourceFractionY());
            packet.set(LE_FLOAT, offset + 52L, request.sourceFractionZ());
            packet.set(LE_FLOAT, offset + 56L, request.radius());
        }
        return new FrameUpload(frameId, packet, copied.size());
    }

    int encode(
            final MTLCommandBuffer commandBuffer,
            final VoxelOccupancyGpuResources voxels,
            final MetalGpuBuffer atlas,
            final MemorySegment globalFence,
            final FrameUpload upload
    ) {
        ensureOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(voxels, "voxels");
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(globalFence, "globalFence");
        Objects.requireNonNull(upload, "upload");
        if (atlas.allocationSize() != Math.addExact(
                this.atlasSuffixOffset, this.atlasSuffixBytes
        )) {
            throw new IllegalArgumentException(
                    "Dynamic voxel-shadow backend is bound to a different atlas layout"
            );
        }
        return commandBuffer.encodeDynamicVoxelShadow(
                this.context, voxels.nativeContext(), atlas.nativeHandle(), globalFence, upload.packet()
        );
    }

    @Override
    public void close() {
        if (!MetalNativeBridge.isNullHandle(this.context)) {
            MetalNativeBridge.metallum_dynamic_shadow_release_context_v1(this.context);
            this.context = MemorySegment.NULL;
        }
        this.arena.close();
    }

    private void ensureOpen() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            throw new IllegalStateException("Dynamic voxel-shadow backend is closed");
        }
    }

    private static void putInt(final MemorySegment segment, final long offset, final int value) {
        segment.set(LE_INT, offset, value);
    }

    private static int getInt(final MemorySegment segment, final long offset) {
        return segment.get(LE_INT, offset);
    }

    private static void putLong(final MemorySegment segment, final long offset, final long value) {
        segment.set(LE_LONG, offset, value);
    }
}
