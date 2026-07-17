package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.voxel.VoxelBrickPatch;
import com.metallum.client.voxel.VoxelClipmapLayout;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.metallum.client.voxel.VoxelUploadBatch;
import com.metallum.client.voxel.VoxelWorldToken;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns one versioned L5 native clipmap context and its reusable upload packet. */
final class VoxelOccupancyGpuResources implements AutoCloseable {
    static final int STATUS_OK = 1;
    static final int STATUS_RING_SLOT_BUSY = -22;
    static final int ABI_VERSION = 1;
    static final int BATCH_MAGIC = 0x3142564d;
    static final int NATIVE_LAYOUT_BYTES = 160;
    static final int STATS_BYTES = 160;
    static final int DEBUG_READBACK_BYTES = 16;
    static final int NATIVE_GUARD_BYTES = 64;
    static final int SCROLL_FLAG = 1 << 2;
    static final int PRODUCTION_PASS_COUNT = 2;
    static final int PRODUCTION_ENCODER_COUNT = 2;
    static final int RESIDENT_PSO_COUNT = 2;
    static final int PRODUCTION_WORK_QUEUE_COUNT = 2;

    private static final int BUFFER_OCCUPANCY = 0;
    private static final int BUFFER_OPTICAL = 1;
    private static final int BUFFER_METADATA = 2;
    private static final int BUFFER_PRIVATE_PAYLOAD = 3;
    private static final int BUFFER_INDIRECT = 4;
    private static final int BUFFER_DEBUG_READBACK = 5;
    private static final int NATIVE_INDIRECT_BYTES = 12;
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    record FrameUpload(long batchId, MemorySegment packet, int patchCount, long uploadBytes) {
        FrameUpload {
            if (batchId <= 0L || packet == null || patchCount <= 0
                    || packet.byteSize() != uploadBytes || uploadBytes <= 0L) {
                throw new IllegalArgumentException("Invalid L5 frame upload declaration");
            }
        }
    }

    record CompletedStats(
            long lightingGeneration,
            long clipmapGeneration,
            long worldGeneration,
            long frameId,
            long submitted,
            long completed,
            int remaining,
            int oldestAge,
            long rejected,
            long ringBusyRejects,
            long resourceBytes
    ) {
    }

    /** Read-only L6 views of the persistent private clipmap resources. */
    record ShadowBindings(
            List<MemorySegment> occupancy,
            List<MemorySegment> optical,
            List<MemorySegment> metadata
    ) {
        ShadowBindings {
            occupancy = List.copyOf(occupancy);
            optical = List.copyOf(optical);
            metadata = List.copyOf(metadata);
            if (occupancy.isEmpty() || occupancy.size() != optical.size()
                    || occupancy.size() != metadata.size()
                    || occupancy.size() > 3) {
                throw new IllegalArgumentException("Invalid L6 voxel shadow bindings");
            }
            for (int index = 0; index < occupancy.size(); index++) {
                requireHandle(occupancy.get(index), "L6 occupancy level " + index);
                requireHandle(optical.get(index), "L6 optical level " + index);
                requireHandle(metadata.get(index), "L6 metadata level " + index);
            }
        }

        int levelCount() {
            return this.occupancy.size();
        }
    }

    private final long lightingGeneration;
    private final long clipmapGeneration;
    private final long worldGeneration;
    private final VoxelClipmapLayout.Budget budget;
    private final List<VoxelClipmapSnapshot.Level> levels;
    private final int stagingBytes;
    private final Arena arena;
    private final MemorySegment uploadPacket;
    private final MemorySegment completedStats;
    private final MemorySegment debugReadback;
    private MemorySegment context;

    private VoxelOccupancyGpuResources(
            final long lightingGeneration,
            final long clipmapGeneration,
            final long worldGeneration,
            final VoxelClipmapLayout.Budget budget,
            final List<VoxelClipmapSnapshot.Level> levels,
            final int stagingBytes,
            final Arena arena,
            final MemorySegment uploadPacket,
            final MemorySegment completedStats,
            final MemorySegment debugReadback,
            final MemorySegment context
    ) {
        this.lightingGeneration = lightingGeneration;
        this.clipmapGeneration = clipmapGeneration;
        this.worldGeneration = worldGeneration;
        this.budget = budget;
        this.levels = List.copyOf(levels);
        this.stagingBytes = stagingBytes;
        this.arena = arena;
        this.uploadPacket = uploadPacket;
        this.completedStats = completedStats;
        this.debugReadback = debugReadback;
        this.context = context;
    }

    static void validateNativeAbi() {
        if (MetalNativeBridge.metallum_voxel_abi_version_v1() != ABI_VERSION) {
            throw new IllegalStateException("Native L5 voxel ABI version mismatch");
        }
        try (Arena probe = Arena.ofConfined()) {
            MemorySegment layout = probe.allocate(NATIVE_LAYOUT_BYTES, Long.BYTES);
            if (MetalNativeBridge.metallum_voxel_layout_v1(layout) != STATUS_OK) {
                throw new IllegalStateException("Native L5 voxel layout query failed");
            }
            int[] expected = {
                    ABI_VERSION,
                    NATIVE_LAYOUT_BYTES,
                    BATCH_MAGIC,
                    VoxelClipmapLayout.PACKET_HEADER_BYTES,
                    VoxelClipmapLayout.PATCH_RECORD_BYTES,
                    VoxelClipmapLayout.LAYOUT_DESCRIPTOR_BYTES,
                    VoxelClipmapLayout.PARAMETER_BYTES_PER_LEVEL,
                    STATS_BYTES,
                    VoxelClipmapLayout.RING_SLOTS,
                    VoxelClipmapLayout.LOGICAL_BRICK_VOXEL_EDGE,
                    VoxelClipmapLayout.OCCUPANCY_WORDS_PER_BRICK,
                    VoxelClipmapLayout.OCCUPANCY_BYTES_PER_BRICK,
                    1,
                    2,
                    SCROLL_FLAG,
                    STATUS_RING_SLOT_BUSY,
                    0,
                    4,
                    8,
                    12,
                    16,
                    20,
                    24,
                    28,
                    32,
                    40,
                    48,
                    56,
                    64,
                    68,
                    72,
                    76,
                    80,
                    84,
                    88,
                    92
            };
            for (int index = 0; index < expected.length; index++) {
                int actual = getInt(layout, (long) index * Integer.BYTES);
                if (actual != expected[index]) {
                    throw new IllegalStateException(
                            "Native L5 voxel layout mismatch at word " + index
                                    + ": expected " + expected[index] + ", got " + actual
                    );
                }
            }
        }
    }

    static VoxelOccupancyGpuResources create(
            final MemorySegment device,
            final long lightingGeneration,
            final VoxelClipmapLayout.Budget budget,
            final VoxelClipmapSnapshot requestedSnapshot
    ) {
        Objects.requireNonNull(budget, "budget");
        if (lightingGeneration <= 0L) {
            throw new IllegalArgumentException("L5 lighting generation must be positive");
        }
        validateNativeAbi();
        VoxelClipmapSnapshot snapshot = requestedSnapshot == null
                ? syntheticSnapshot(budget)
                : requestedSnapshot;
        validateSnapshot(budget, snapshot);
        int stagingBytes = stagingBytes(budget);
        Arena arena = Arena.ofShared();
        MemorySegment context = MemorySegment.NULL;
        try {
            MemorySegment descriptors = arena.allocate(
                    Math.multiplyExact(
                            (long) snapshot.levels().size(),
                            VoxelClipmapLayout.LAYOUT_DESCRIPTOR_BYTES
                    ),
                    Long.BYTES
            );
            descriptors.fill((byte) 0);
            for (int index = 0; index < snapshot.levels().size(); index++) {
                VoxelClipmapSnapshot.Level level = snapshot.levels().get(index);
                long base = (long) index * VoxelClipmapLayout.LAYOUT_DESCRIPTOR_BYTES;
                putInt(descriptors, base, level.logicalEdge());
                putInt(descriptors, base + 4L, level.subdivision());
                putInt(descriptors, base + 8L,
                        Math.floorMod(level.originBrickX(), level.brickDimension()));
                putInt(descriptors, base + 12L,
                        Math.floorMod(level.originBrickY(), level.brickDimension()));
                putInt(descriptors, base + 16L,
                        Math.floorMod(level.originBrickZ(), level.brickDimension()));
            }
            context = MetalNativeBridge.metallum_voxel_create_context_v1(
                    device,
                    lightingGeneration,
                    snapshot.clipmapGeneration(),
                    snapshot.world().generation(),
                    descriptors,
                    snapshot.levels().size(),
                    budget.maxBricksPerSubmit(),
                    stagingBytes
            );
            if (MetalNativeBridge.isNullHandle(context)) {
                throw new IllegalStateException("Native L5 voxel context creation failed");
            }
            validateBuffers(context, budget, snapshot.levels(), stagingBytes);
            MemorySegment uploadPacket = arena.allocate(stagingBytes, Long.BYTES);
            MemorySegment stats = arena.allocate(STATS_BYTES, Long.BYTES);
            MemorySegment debug = arena.allocate(DEBUG_READBACK_BYTES, Long.BYTES);
            return new VoxelOccupancyGpuResources(
                    lightingGeneration,
                    snapshot.clipmapGeneration(),
                    snapshot.world().generation(),
                    budget,
                    snapshot.levels(),
                    stagingBytes,
                    arena,
                    uploadPacket,
                    stats,
                    debug,
                    context
            );
        } catch (RuntimeException | Error failure) {
            if (!MetalNativeBridge.isNullHandle(context)) {
                MetalNativeBridge.metallum_voxel_release_context_v1(context);
            }
            arena.close();
            throw failure;
        }
    }

    boolean matches(
            final long expectedLightingGeneration,
            final VoxelClipmapLayout.Budget expectedBudget,
            final VoxelClipmapSnapshot snapshot
    ) {
        if (snapshot == null || expectedLightingGeneration != this.lightingGeneration
                || !this.budget.equals(expectedBudget)
                || snapshot.clipmapGeneration() != this.clipmapGeneration
                || snapshot.world().generation() != this.worldGeneration
                || snapshot.levels().size() != this.levels.size()) {
            return false;
        }
        for (int index = 0; index < this.levels.size(); index++) {
            VoxelClipmapSnapshot.Level left = this.levels.get(index);
            VoxelClipmapSnapshot.Level right = snapshot.levels().get(index);
            if (left.level() != right.level()
                    || left.subdivision() != right.subdivision()
                    || left.logicalEdge() != right.logicalEdge()
                    || left.brickDimension() != right.brickDimension()) {
                return false;
            }
        }
        return true;
    }

    boolean matchesGenerationAndBudget(
            final long expectedLightingGeneration,
            final VoxelClipmapLayout.Budget expectedBudget
    ) {
        return expectedLightingGeneration == this.lightingGeneration
                && this.budget.equals(Objects.requireNonNull(expectedBudget, "expectedBudget"));
    }

    FrameUpload encode(final VoxelUploadBatch batch, final FrameState frame) {
        ensureOpen();
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(frame, "frame");
        if (frame.lightingGenerationId() != this.lightingGeneration
                || batch.world().generation() != this.worldGeneration
                || batch.clipmapGeneration() != this.clipmapGeneration
                || batch.frameId() != frame.frameId()
                || batch.patches().size() > this.budget.maxBricksPerSubmit()) {
            throw new IllegalArgumentException("L5 upload does not match its native context/frame");
        }
        List<VoxelBrickPatch> patches = batch.patches();
        long payloadOffset = Math.addExact(
                VoxelClipmapLayout.PACKET_HEADER_BYTES,
                Math.multiplyExact(
                        (long) patches.size(), VoxelClipmapLayout.PATCH_RECORD_BYTES)
        );
        long cursor = payloadOffset;
        for (VoxelBrickPatch patch : patches) {
            cursor = Math.addExact(cursor, patch.packedPayloadLength());
        }
        if (cursor > this.stagingBytes) {
            throw new IllegalArgumentException("L5 upload exceeds its staging slot");
        }
        MemorySegment packet = this.uploadPacket.asSlice(0L, cursor);
        putInt(packet, 0L, BATCH_MAGIC);
        putInt(packet, 4L, ABI_VERSION);
        putInt(packet, 8L, Math.toIntExact(cursor));
        putInt(packet, 12L, batch.scrollSlabs() > 0 ? SCROLL_FLAG : 0);
        putInt(packet, 16L, VoxelClipmapLayout.PATCH_RECORD_BYTES);
        putInt(packet, 20L, patches.size());
        putInt(packet, 24L, frame.inFlightSlot());
        putInt(packet, 28L, this.levels.size());
        putLong(packet, 32L, this.lightingGeneration);
        putLong(packet, 40L, this.clipmapGeneration);
        putLong(packet, 48L, this.worldGeneration);
        putLong(packet, 56L, frame.frameId());
        putInt(packet, 64L, Math.toIntExact(cursor - payloadOffset));
        putInt(packet, 68L, Math.toIntExact(payloadOffset));
        putInt(packet, 72L, batch.scrollSlabs());
        putInt(packet, 76L, batch.unloadClears());
        putInt(packet, 80L, batch.queueRemaining());
        putInt(packet, 84L, saturatedUnsignedInt(batch.oldestAgeTicks()));
        putInt(packet, 88L, saturatedUnsignedInt(batch.coalescedDelta()));
        putInt(packet, 92L, saturatedUnsignedInt(batch.rejectedDelta()));

        cursor = payloadOffset;
        for (int index = 0; index < patches.size(); index++) {
            VoxelBrickPatch patch = patches.get(index);
            requirePatch(patch);
            long record = VoxelClipmapLayout.PACKET_HEADER_BYTES
                    + (long) index * VoxelClipmapLayout.PATCH_RECORD_BYTES;
            putInt(packet, record, patch.level());
            putInt(packet, record + 4L, patch.destinationBrickX());
            putInt(packet, record + 8L, patch.destinationBrickY());
            putInt(packet, record + 12L, patch.destinationBrickZ());
            putInt(packet, record + 16L, Math.toIntExact(cursor));
            putInt(packet, record + 20L, VoxelBrickPatch.OCCUPANCY_BYTES);
            putInt(packet, record + 24L, patch.opticalLength());
            putInt(packet, record + 28L, 0);
            putInt(packet, record + 32L, (int) this.clipmapGeneration);
            putInt(packet, record + 36L, (int) (this.clipmapGeneration >>> 32));
            putInt(packet, record + 40L, patch.logicalBrickX());
            putInt(packet, record + 44L, patch.logicalBrickY());
            putInt(packet, record + 48L, patch.logicalBrickZ());
            putInt(packet, record + 52L, patch.contentStamp());

            patch.copyPackedPayloadTo(packet, cursor);
            cursor += patch.packedPayloadLength();
        }
        return new FrameUpload(batch.batchId(), packet, patches.size(), packet.byteSize());
    }

    int upload(final MTLCommandBuffer commandBuffer, final FrameUpload upload) {
        ensureOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(upload, "upload");
        return commandBuffer.encodeVoxelOccupancy(this.context, upload.packet());
    }

    CompletedStats readLastCompletedStats() {
        ensureOpen();
        this.completedStats.fill((byte) 0);
        int status = MetalNativeBridge.metallum_voxel_last_completed_stats_v1(
                this.context, this.completedStats);
        if (status < 0) {
            throw new IllegalStateException("Native L5 statistics query failed: " + status);
        }
        return new CompletedStats(
                getLong(this.completedStats, 8L),
                getLong(this.completedStats, 16L),
                getLong(this.completedStats, 24L),
                getLong(this.completedStats, 32L),
                getLong(this.completedStats, 40L),
                getLong(this.completedStats, 48L),
                getInt(this.completedStats, 56L),
                getInt(this.completedStats, 60L),
                getLong(this.completedStats, 72L),
                Integer.toUnsignedLong(getInt(this.completedStats, 108L)),
                getLong(this.completedStats, 120L)
        );
    }

    int encodeDebugChecksum(final MTLCommandBuffer commandBuffer, final int level, final int slot) {
        ensureOpen();
        return commandBuffer.encodeVoxelDebugChecksum(this.context, level, slot);
    }

    int readDebugChecksum() {
        ensureOpen();
        this.debugReadback.fill((byte) 0);
        int status = MetalNativeBridge.metallum_voxel_debug_readback_v1(
                this.context, this.debugReadback);
        if (status != STATUS_OK) {
            throw new IllegalStateException("Native L5 debug readback failed: " + status);
        }
        return getInt(this.debugReadback, 8L);
    }

    long lightingGeneration() {
        return this.lightingGeneration;
    }

    VoxelClipmapLayout.Budget budget() {
        return this.budget;
    }

    long clipmapGeneration() {
        return this.clipmapGeneration;
    }

    long worldGeneration() {
        return this.worldGeneration;
    }

    List<VoxelClipmapSnapshot.Level> levels() {
        return this.levels;
    }

    ShadowBindings shadowBindings() {
        ensureOpen();
        List<MemorySegment> occupancy = new ArrayList<>(this.levels.size());
        List<MemorySegment> optical = new ArrayList<>(this.levels.size());
        List<MemorySegment> metadata = new ArrayList<>(this.levels.size());
        for (int index = 0; index < this.levels.size(); index++) {
            occupancy.add(buffer(this.context, BUFFER_OCCUPANCY, index));
            optical.add(buffer(this.context, BUFFER_OPTICAL, index));
            metadata.add(buffer(this.context, BUFFER_METADATA, index));
        }
        return new ShadowBindings(occupancy, optical, metadata);
    }

    @Override
    public void close() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return;
        }
        MetalNativeBridge.metallum_voxel_release_context_v1(this.context);
        this.context = MemorySegment.NULL;
        this.arena.close();
    }

    private void requirePatch(final VoxelBrickPatch patch) {
        if (patch.level() < 0 || patch.level() >= this.levels.size()
                || patch.worldGeneration() != this.worldGeneration
                || patch.clipmapGeneration() != this.clipmapGeneration) {
            throw new IllegalArgumentException("L5 patch has a stale level/generation");
        }
        VoxelClipmapSnapshot.Level level = this.levels.get(patch.level());
        int expectedOptical = Math.toIntExact(
                this.budget.levels().get(patch.level()).opticalBytesPerBrick());
        if (patch.opticalLength() != expectedOptical
                || patch.packedPayloadLength() != VoxelBrickPatch.OCCUPANCY_BYTES + expectedOptical
                || patch.destinationBrickX() != Math.floorMod(
                patch.logicalBrickX(), level.brickDimension())
                || patch.destinationBrickY() != Math.floorMod(
                patch.logicalBrickY(), level.brickDimension())
                || patch.destinationBrickZ() != Math.floorMod(
                patch.logicalBrickZ(), level.brickDimension())) {
            throw new IllegalArgumentException("L5 patch does not match its toroidal level");
        }
    }

    private static int stagingBytes(final VoxelClipmapLayout.Budget budget) {
        long bytes = Math.addExact(
                VoxelClipmapLayout.PACKET_HEADER_BYTES,
                Math.multiplyExact(
                        (long) budget.maxBricksPerSubmit(),
                        VoxelClipmapLayout.PATCH_RECORD_BYTES
                )
        );
        bytes = Math.addExact(
                bytes,
                Math.multiplyExact(
                        budget.maxBricksPerSubmit(),
                        Math.addExact(
                                VoxelClipmapLayout.OCCUPANCY_BYTES_PER_BRICK,
                                budget.largestFullBrickUploadBytes()
                                        - VoxelClipmapLayout.OCCUPANCY_BYTES_PER_BRICK
                        )
                )
        );
        return Math.toIntExact(bytes);
    }

    private static void validateBuffers(
            final MemorySegment context,
            final VoxelClipmapLayout.Budget budget,
            final List<VoxelClipmapSnapshot.Level> levels,
            final int stagingBytes
    ) {
        for (int index = 0; index < levels.size(); index++) {
            VoxelClipmapLayout.Level level = budget.levels().get(index);
            requireBuffer(context, BUFFER_OCCUPANCY, index,
                    level.occupancyBytes() + NATIVE_GUARD_BYTES, "occupancy");
            requireBuffer(context, BUFFER_OPTICAL, index,
                    level.materialBytes() + NATIVE_GUARD_BYTES, "optical");
            long bricks = level.brickCountPerAxis();
            long metadataBytes = Math.multiplyExact(
                    Math.multiplyExact(Math.multiplyExact(bricks, bricks), bricks), 16L);
            requireBuffer(context, BUFFER_METADATA, index,
                    metadataBytes + NATIVE_GUARD_BYTES, "metadata");
        }
        for (int slot = 0; slot < budget.ringSlots(); slot++) {
            requireBuffer(context, BUFFER_PRIVATE_PAYLOAD, slot,
                    stagingBytes, "private payload");
            requireBuffer(context, BUFFER_INDIRECT, slot,
                    Math.multiplyExact((long) levels.size(), NATIVE_INDIRECT_BYTES),
                    "indirect arguments");
            requireBuffer(context, BUFFER_DEBUG_READBACK, slot, Integer.BYTES,
                    "debug readback");
        }
    }

    private static void requireBuffer(
            final MemorySegment context,
            final int kind,
            final int index,
            final long expectedBytes,
            final String name
    ) {
        MemorySegment handle = buffer(context, kind, index);
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("Native L5 " + name + " buffer is unavailable");
        }
        long actual = MetalNativeBridge.metallum_voxel_context_buffer_bytes_v1(
                context, kind, index);
        if (actual != expectedBytes) {
            throw new IllegalStateException(
                    "Native L5 " + name + " buffer size mismatch: expected "
                            + expectedBytes + ", got " + actual
            );
        }
    }

    private static MemorySegment buffer(
            final MemorySegment context,
            final int kind,
            final int index
    ) {
        return MetalNativeBridge.metallum_voxel_context_buffer_v1(context, kind, index);
    }

    private static void requireHandle(final MemorySegment handle, final String name) {
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("Native " + name + " buffer is unavailable");
        }
    }

    private static VoxelClipmapSnapshot syntheticSnapshot(
            final VoxelClipmapLayout.Budget budget
    ) {
        List<VoxelClipmapSnapshot.Level> levels = new ArrayList<>(budget.levels().size());
        for (int index = 0; index < budget.levels().size(); index++) {
            VoxelClipmapLayout.Level level = budget.levels().get(index);
            levels.add(new VoxelClipmapSnapshot.Level(
                    index,
                    level.subdivision().scale(),
                    level.logicalEdge(),
                    0L,
                    0L,
                    0L,
                    level.brickCountPerAxis()
            ));
        }
        return new VoxelClipmapSnapshot(
                new VoxelWorldToken(1L, "metallum:unbound"), 1L, levels);
    }

    private static void validateSnapshot(
            final VoxelClipmapLayout.Budget budget,
            final VoxelClipmapSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.levels().size() != budget.levels().size()) {
            throw new IllegalArgumentException("L5 snapshot level count differs from its preset");
        }
        for (int index = 0; index < budget.levels().size(); index++) {
            VoxelClipmapLayout.Level expected = budget.levels().get(index);
            VoxelClipmapSnapshot.Level actual = snapshot.levels().get(index);
            if (actual.level() != index
                    || actual.subdivision() != expected.subdivision().scale()
                    || actual.logicalEdge() != expected.logicalEdge()
                    || actual.brickDimension() != expected.brickCountPerAxis()) {
                throw new IllegalArgumentException("L5 snapshot layout differs from its preset");
            }
        }
    }

    private static int saturatedUnsignedInt(final long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("L5 counter must be non-negative");
        }
        return (int) Math.min(value, 0xffff_ffffL);
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

    private static long getLong(final MemorySegment segment, final long offset) {
        return segment.get(LE_LONG, offset);
    }

    private void ensureOpen() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            throw new IllegalStateException("L5 voxel resources are closed");
        }
    }
}
