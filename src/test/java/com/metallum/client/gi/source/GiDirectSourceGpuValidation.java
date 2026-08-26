package com.metallum.client.gi.source;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Source/bundled private-texture validation for the bounded G3 field. */
public final class GiDirectSourceGpuValidation {
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    private GiDirectSourceGpuValidation() {
    }

    public static void main(final String[] args) throws Exception {
        String expectedMode = args.length == 1 ? args[0] : "source";
        MemorySegment device = MetalNativeBridge.metallum_create_system_default_device();
        require(!MetalNativeBridge.isNullHandle(device), "Metal device is unavailable");
        MemorySegment layer = MemorySegment.NULL;
        MTLCommandQueue queue = null;
        try {
            layer = MetalNativeBridge.metallum_create_metal_layer(device, 1.0);
            require(!MetalNativeBridge.isNullHandle(layer), "Metal layer creation failed");
            queue = MTLCommandQueue.create(device, layer);
            validateField(device, queue);
            validateWrongThread(device, queue);
            validateReleaseWhileInFlight(device, queue);
            System.out.println("G3 bounded direct-source Metal validation passed (" + expectedMode + ")");
        } finally {
            if (queue != null) queue.close();
            if (!MetalNativeBridge.isNullHandle(layer)) MetalNativeBridge.metallum_release_object(layer);
            MetalNativeBridge.metallum_release_device_caches(device);
            MetalNativeBridge.metallum_release_object(device);
        }
    }

    private static void validateField(
            final MemorySegment device,
            final MTLCommandQueue queue
    ) {
        try (Arena arena = Arena.ofShared();
             GiDirectSourceGpuResources resources = GiDirectSourceGpuResources.create(
                     device, queue.nativeHandle(), 101L,
                     MetalNativeBridge::metallum_gi_direct_source_release_context_v1)) {
            require(resources != null, "G3 context creation failed");
            GiDirectSourceGpuResources.Stats initial = resources.stats();
            require(!initial.ready() && !initial.buildInFlight()
                            && initial.worldGeneration() == 101L
                            && initial.persistentBytes() > 0L
                            && initial.stagingBytes() > 0L
                            && initial.accountedBytes() < 2L * 1024L * 1024L
                            && initial.fullVolumeRebuilds() == 0L,
                    "G3 initial lifecycle/budget differs");

            Packet zero = packet(arena, 101L, 1L, 1, false, false, false);
            GiDirectSourceGpuResources.Capture zeroCapture = encodeAndCapture(
                    resources, queue, zero, 0, 4);
            require(allZero(zeroCapture.directRgbaFloat16()), "zero source is not bit-exact zero");

            Packet redEmission = packet(arena, 101L, 2L, 1, false, true, false);
            GiDirectSourceGpuResources.Capture redCapture = encodeAndCapture(
                    resources, queue, redEmission, 0, 4);
            assertRedOnly(redCapture.directRgbaFloat16(), captureCell(4, 1));

            Packet redStatic = packet(arena, 101L, 3L, 1, false, false, true);
            GiDirectSourceGpuResources.Capture staticCapture = encodeAndCapture(
                    resources, queue, redStatic, 0, 4);
            assertRedOnly(staticCapture.directRgbaFloat16(), captureCell(4, 1));

            Packet sealed = packet(arena, 101L, 4L, 4, true, false, false);
            GiDirectSourceGpuResources.Capture sealedCapture = encodeAndCapture(
                    resources, queue, sealed, 0, 4);
            require(allZero(sealedCapture.directRgbaFloat16()),
                    "sealed field boundary admitted sky without an AIR aperture");

            Packet aperture = packet(arena, 101L, 5L, 4, false, false, false);
            GiDirectSourceGpuResources.Capture apertureCapture = encodeAndCapture(
                    resources, queue, aperture, 0, 4);
            int apertureCell = captureCell(4, 1);
            require(half(apertureCapture.directRgbaFloat16()[apertureCell]) > 0.9F
                            && half(apertureCapture.directRgbaFloat16()[apertureCell + 1]) > 0.9F
                            && half(apertureCapture.directRgbaFloat16()[apertureCell + 2]) > 0.9F,
                    "valid aperture did not admit sky");

            Packet repeated = packet(arena, 101L, 6L, 4, false, false, false);
            GiDirectSourceGpuResources.Capture repeatedCapture = encodeAndCapture(
                    resources, queue, repeated, 0, 4);
            require(Arrays.equals(
                            apertureCapture.directRgbaFloat16(), repeatedCapture.directRgbaFloat16()),
                    "unchanged world-space inputs changed the direct-field hash payload");

            Packet stale = packet(arena, 101L, 5L, 1, false, false, false);
            MTLCommandBuffer staleBuffer = queue.makeCommandBuffer("G3 stale validation");
            try {
                require(resources.encode(staleBuffer.handle(), MemorySegment.NULL, stale.batch())
                                == GiDirectSourceGpuResources.STATUS_STALE,
                        "stale G3 content epoch was admitted");
            } finally {
                staleBuffer.close();
            }

            require(resources.publishScheduler(new GiDirectDirtyQueue.Telemetry(
                            15L, 0L, 15L, 0L, 0, 0L, 1L))
                            == GiDirectSourceGpuResources.STATUS_OK,
                    "G3 scheduler telemetry publication failed");
            GiDirectSourceGpuResources.Stats completed = resources.stats();
            require(completed.batches() == 6L && completed.dirtyBricks() == 15L
                            && completed.geometryApplyDispatches() == 15L
                            && completed.directInjectDispatches() == 15L
                            && completed.staleRejects() == 1L
                            && completed.fullVolumeRebuilds() == 1L,
                    "G3 bounded work counters differ");
        }
    }

    private static void validateWrongThread(
            final MemorySegment device,
            final MTLCommandQueue queue
    ) throws Exception {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment context = MetalNativeBridge.metallum_gi_direct_source_create_context_v1(
                    device, queue.nativeHandle(), 202L);
            require(!MetalNativeBridge.isNullHandle(context), "G3 wrong-thread context creation failed");
            Packet packet = packet(arena, 202L, 1L, 1, false, false, false);
            MTLCommandBuffer commandBuffer = queue.makeCommandBuffer("G3 wrong-thread validation");
            AtomicInteger status = new AtomicInteger();
            Thread thread = new Thread(() -> status.set(
                    MetalNativeBridge.metallum_gi_direct_source_encode_dirty_v1(
                            context, commandBuffer.handle(), MemorySegment.NULL,
                            packet.header(), packet.bricks(), packet.cells(), packet.sources())
            ), "g3-wrong-thread");
            thread.start();
            thread.join();
            require(status.get() == GiDirectSourceGpuResources.STATUS_WRONG_THREAD,
                    "G3 wrong-thread encode was admitted");
            commandBuffer.close();
            MetalNativeBridge.metallum_gi_direct_source_release_context_v1(context);
        }
    }

    private static void validateReleaseWhileInFlight(
            final MemorySegment device,
            final MTLCommandQueue queue
    ) {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment context = MetalNativeBridge.metallum_gi_direct_source_create_context_v1(
                    device, queue.nativeHandle(), 303L);
            require(!MetalNativeBridge.isNullHandle(context), "G3 in-flight context creation failed");
            Packet packet = packet(arena, 303L, 1L, 1, false, true, false);
            MTLCommandBuffer commandBuffer = queue.makeCommandBuffer("G3 release validation");
            require(MetalNativeBridge.metallum_gi_direct_source_encode_dirty_v1(
                            context, commandBuffer.handle(), MemorySegment.NULL,
                            packet.header(), packet.bricks(), packet.cells(), packet.sources())
                            == GiDirectSourceGpuResources.STATUS_OK,
                    "G3 in-flight encode failed");
            MemorySegment semaphore = MetalNativeBridge.metallum_create_semaphore();
            require(!MetalNativeBridge.isNullHandle(semaphore), "G3 completion semaphore creation failed");
            commandBuffer.commitWithSignal(semaphore);
            MetalNativeBridge.metallum_gi_direct_source_release_context_v1(context);
            require(MetalNativeBridge.metallum_semaphore_wait(semaphore, 10_000L) == 0,
                    "G3 released in-flight command did not complete");
            MetalNativeBridge.metallum_release_object(semaphore);
            commandBuffer.close();
        }
    }

    private static GiDirectSourceGpuResources.Capture encodeAndCapture(
            final GiDirectSourceGpuResources resources,
            final MTLCommandQueue queue,
            final Packet packet,
            final int cascade,
            final int slice
    ) {
        MTLCommandBuffer commandBuffer = queue.makeCommandBuffer("G3 validation");
        try {
            require(resources.encode(commandBuffer.handle(), MemorySegment.NULL, packet.batch())
                            == GiDirectSourceGpuResources.STATUS_OK,
                    "G3 bounded batch encode failed");
            commandBuffer.commit();
            require(resources.awaitReady(10_000L), "G3 bounded batch did not complete");
        } finally {
            commandBuffer.close();
        }
        GiDirectSourceGpuResources.Capture capture = resources.captureSliceOnce(cascade, slice);
        require(capture != null, "G3 diagnostic capture failed");
        return capture;
    }

    private static Packet packet(
            final Arena arena,
            final long world,
            final long content,
            final int brickCount,
            final boolean sealed,
            final boolean redEmission,
            final boolean redStatic
    ) {
        MemorySegment header = arena.allocate(GiDirectSourceGpuResources.HEADER_BYTES, Long.BYTES);
        MemorySegment bricks = arena.allocate(
                (long) brickCount * GiDirectSourceGpuResources.BRICK_BYTES, Long.BYTES);
        MemorySegment cells = arena.allocate(
                (long) brickCount * GiDirectSourceLayout.BRICK_EDGE_CELLS
                        * GiDirectSourceLayout.BRICK_EDGE_CELLS
                        * GiDirectSourceLayout.BRICK_EDGE_CELLS
                        * GiDirectSourceGpuResources.CELL_BYTES,
                Long.BYTES
        );
        int sourceCount = redStatic ? 1 : 0;
        MemorySegment sources = arena.allocate(
                Math.max(1L, (long) sourceCount * GiDirectSourceGpuResources.SOURCE_BYTES),
                Long.BYTES
        ).asSlice(0L, (long) sourceCount * GiDirectSourceGpuResources.SOURCE_BYTES);
        header.fill((byte) 0);
        bricks.fill((byte) 0);
        cells.fill((byte) 0);
        header.set(LE_INT, 0L, GiDirectSourceGpuResources.ABI_VERSION);
        header.set(LE_INT, 4L, GiDirectSourceGpuResources.HEADER_BYTES);
        header.set(LE_LONG, 8L, world);
        header.set(LE_LONG, 16L, 1L);
        header.set(LE_LONG, 24L, 1L);
        header.set(LE_LONG, 32L, content);
        header.set(LE_LONG, 40L, 1L);
        header.set(LE_LONG, 48L, 1L);
        if (brickCount == 4) {
            putFloat4(header, 128L, 1.0F, 1.0F, 1.0F, 1.0F);
        }
        header.set(LE_INT, 144L, brickCount);
        header.set(LE_INT, 148L, sourceCount);

        for (int brick = 0; brick < brickCount; brick++) {
            long brickOffset = (long) brick * GiDirectSourceGpuResources.BRICK_BYTES;
            bricks.set(LE_INT, brickOffset, 0);
            bricks.set(LE_INT, brickOffset + 4L, 0);
            bricks.set(LE_INT, brickOffset + 8L, brickCount == 4 ? brick : 0);
            bricks.set(LE_INT, brickOffset + 12L, 0);
            bricks.set(LE_INT, brickOffset + 16L, 0);
            bricks.set(LE_INT, brickOffset + 20L, brick == 0 ? sourceCount : 0);
            bricks.set(LE_LONG, brickOffset + 24L, content + brick);
            for (int localZ = 0; localZ < 8; localZ++) {
                for (int localY = 0; localY < 8; localY++) {
                    for (int localX = 0; localX < 8; localX++) {
                        int globalY = brick * 8 + localY;
                        int state;
                        if (brickCount == 4) {
                            boolean aperture = localX == 4 && localZ == 4 && globalY >= 2;
                            state = !sealed && aperture ? 1 : 2;
                        } else {
                            state = localX == 4 && localY == 1 && localZ == 4 ? 2 : 1;
                        }
                        long cellOffset = ((long) brick * 512L
                                + (localZ * 8L + localY) * 8L + localX)
                                * GiDirectSourceGpuResources.CELL_BYTES;
                        cells.set(ValueLayout.JAVA_BYTE, cellOffset + 8L, (byte) state);
                    }
                }
            }
        }
        if (redEmission) {
            long target = ((4L * 8L + 1L) * 8L + 4L)
                    * GiDirectSourceGpuResources.CELL_BYTES;
            cells.set(LE_SHORT, target, Float.floatToFloat16(1.0F));
            cells.set(LE_SHORT, target + 6L, Float.floatToFloat16(1.0F));
        }
        if (redStatic) {
            putFloat4(sources, 0L, 9.0F, 3.0F, 9.0F, 8.0F);
            putFloat4(sources, 16L, 1.0F, 0.0F, 0.0F, 1.0F);
        }
        GiDirectSourceGpuResources.PreparedBatch batch = new GiDirectSourceGpuResources.PreparedBatch(
                header, bricks, cells, sources, brickCount, sourceCount);
        return new Packet(header, bricks, cells, sources, batch);
    }

    private static int captureCell(final int x, final int y) {
        return (y * 32 + x) * 4;
    }

    private static boolean allZero(final short[] values) {
        for (short value : values) {
            if (value != 0) return false;
        }
        return true;
    }

    private static boolean isZeroCell(final short[] values, final int base) {
        return values[base] == 0 && values[base + 1] == 0
                && values[base + 2] == 0 && values[base + 3] == 0;
    }

    private static void assertRedOnly(final short[] values, final int base) {
        require(half(values[base]) > 0.9F, "red source produced no red energy");
        require(values[base + 1] == 0 && values[base + 2] == 0,
                "red source leaked into green/blue");
        require(half(values[base + 3]) > 0.9F, "red source support bit is absent");
    }

    private static float half(final short value) {
        return Float.float16ToFloat(value);
    }

    private static void putFloat4(
            final MemorySegment segment,
            final long offset,
            final float x, final float y, final float z, final float w
    ) {
        segment.set(LE_FLOAT, offset, x);
        segment.set(LE_FLOAT, offset + 4L, y);
        segment.set(LE_FLOAT, offset + 8L, z);
        segment.set(LE_FLOAT, offset + 12L, w);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Packet(
            MemorySegment header,
            MemorySegment bricks,
            MemorySegment cells,
            MemorySegment sources,
            GiDirectSourceGpuResources.PreparedBatch batch
    ) {
    }
}
