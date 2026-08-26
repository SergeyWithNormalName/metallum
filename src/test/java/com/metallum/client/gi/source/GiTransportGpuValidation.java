package com.metallum.client.gi.source;

import com.metallum.client.gi.transport.GiTransportGpuResources;
import com.metallum.client.gi.transport.GiTransportLayout;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLCommandQueue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

/** Source/bundled end-to-end Metal validation for the frozen G3 -> G4 private field. */
public final class GiTransportGpuValidation {
    private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final int UNKNOWN = 0;
    private static final int AIR = 1;
    private static final int CONTENT = 2;
    private static final int FALLBACK = 3;
    private static final int SOURCE_X = 8;
    private static final int SOURCE_Y = 8;
    private static final int SOURCE_Z = 8;
    private static final int RECEIVER_X = 4;
    private static final int RECEIVER_Y = 4;
    private static final int RECEIVER_Z = 4;
    // A side cell touched only by the diagonal supercover. A center-line-only
    // traversal from (4,4,4) to (8,8,8) would incorrectly skip it.
    private static final int BARRIER_X = 6;
    private static final int BARRIER_Y = 5;
    private static final int BARRIER_Z = 5;
    private static final float DIRECT_IRRADIANCE = 65_504.0F;
    private static final long G2_CONSERVATIVE_BYTES = 18_022_528L;
    private static final long G3_NATIVE_ACCOUNTED_BYTES = 1_104_096L;
    private static final long G3_END_TO_END_BYTES = G3_NATIVE_ACCOUNTED_BYTES
            + GiDirectSourceGpuResources.JAVA_PERSISTENT_PACKET_BYTES;
    private static final long GI_BUDGET_BYTES = 25_165_824L;
    private static final String DIMENSION = "test:g4-gpu-validation";

    private GiTransportGpuValidation() {
    }

    public static void main(final String[] arguments) throws Exception {
        String requestedMode = arguments.length == 1 ? arguments[0] : "source";
        int expectedLibraryMode = switch (requestedMode) {
            case "source" -> 2;
            case "bundled" -> 1;
            default -> throw new IllegalArgumentException("Expected source or bundled mode");
        };
        MemorySegment device = MetalNativeBridge.metallum_create_system_default_device();
        require(!MetalNativeBridge.isNullHandle(device), "Metal device is unavailable");
        MemorySegment layer = MemorySegment.NULL;
        MTLCommandQueue queue = null;
        try {
            layer = MetalNativeBridge.metallum_create_metal_layer(device, 1.0);
            require(!MetalNativeBridge.isNullHandle(layer), "Metal layer creation failed");
            queue = MTLCommandQueue.create(device, layer);
            GiTransportGpuResources.validateNativeAbi();
            validatePhysicalField(device, queue, expectedLibraryMode);
            validateWrongThread(device, queue, expectedLibraryMode);
            validateReleaseWhileInFlight(device, queue);
            System.out.println("G4 frozen one-bounce Metal validation passed ("
                    + requestedMode + ")");
        } finally {
            if (queue != null) queue.close();
            if (!MetalNativeBridge.isNullHandle(layer)) {
                MetalNativeBridge.metallum_release_object(layer);
            }
            MetalNativeBridge.metallum_release_device_caches(device);
            MetalNativeBridge.metallum_release_object(device);
        }
    }

    private static void validatePhysicalField(
            final MemorySegment device,
            final MTLCommandQueue queue,
            final int expectedLibraryMode
    ) {
        try (DirectField open = DirectField.create(device, queue, 401L, Scene.OPEN)) {
            TransportResult black = runTransport(
                    device, queue, open, SurfaceRho.BLACK, expectedLibraryMode, false);
            require(allZero(black.capture().bounceRgbaFloat16())
                            && allZero(black.capture().shRedRgbaFloat16())
                            && allZero(black.capture().shGreenRgbaFloat16())
                            && allZero(black.capture().shBlueRgbaFloat16()),
                    "black G2 rho created G4 bounce or SH energy");

            TransportResult first = runTransport(
                    device, queue, open, SurfaceRho.RED, expectedLibraryMode, true);
            assertOpenRedField(first);
            System.out.println("G4_VALIDATION RECEIPT library_mode=" + expectedLibraryMode
                    + " persistent_bytes=" + first.stats().persistentBytes()
                    + " staging_bytes=" + first.stats().stagingBytes()
                    + " readback_bytes=" + first.stats().readbackBytes()
                    + " native_accounted_bytes=" + first.stats().accountedBytes()
                    + " java_packet_bytes=" + GiTransportLayout.JAVA_PERSISTENT_PACKET_BYTES
                    + " end_to_end_bytes="
                    + (first.stats().accountedBytes()
                    + GiTransportLayout.JAVA_PERSISTENT_PACKET_BYTES)
                    + " raw_sha256=" + first.rawHash());
            TransportResult repeat = runTransport(
                    device, queue, open, SurfaceRho.RED, expectedLibraryMode, false);
            require(first.rawHash().equals(repeat.rawHash()),
                    "identical source/mode contexts changed the raw G4 hash");
            require(equalCapture(first.capture(), repeat.capture()),
                    "identical source/mode contexts changed the raw G4 payload");
        }

        try (DirectField zero = DirectField.create(device, queue, 402L, Scene.ZERO_SOURCE)) {
            TransportResult result = runTransport(
                    device, queue, zero, SurfaceRho.RED, expectedLibraryMode, false);
            require(allZero(result.capture().bounceRgbaFloat16())
                            && allZero(result.capture().shRedRgbaFloat16())
                            && allZero(result.capture().shGreenRgbaFloat16())
                            && allZero(result.capture().shBlueRgbaFloat16()),
                    "zero G3 source is not bit-exact zero in G4");
        }

        TransportResult sealed;
        try (DirectField field = DirectField.create(device, queue, 403L, Scene.SEALED)) {
            sealed = runTransport(
                    device, queue, field, SurfaceRho.RED, expectedLibraryMode, false);
            assertNoTransport(sealed, "sealed supercover wall leaked");
        }
        TransportResult aperture;
        try (DirectField field = DirectField.create(device, queue, 404L, Scene.OPEN)) {
            aperture = runTransport(
                    device, queue, field, SurfaceRho.RED, expectedLibraryMode, false);
            assertOpenRedField(aperture);
        }
        require(nonZeroCellCount(aperture.capture().shRedRgbaFloat16()) == 1
                        && nonZeroCellCount(sealed.capture().shRedRgbaFloat16()) == 0,
                "aperture changed energy outside the one physically connected receiver");
        require(equalCell(aperture.capture().bounceRgbaFloat16(),
                        sealed.capture().bounceRgbaFloat16(), SOURCE_X, SOURCE_Y, SOURCE_Z),
                "aperture changed the frozen source bounce");

        for (Scene scene : new Scene[] {Scene.UNKNOWN_BARRIER, Scene.FALLBACK_BARRIER}) {
            try (DirectField field = DirectField.create(
                    device, queue, 405L + scene.ordinal(), scene)) {
                assertNoTransport(runTransport(
                                device, queue, field, SurfaceRho.RED,
                                expectedLibraryMode, false),
                        scene + " path transferred energy");
            }
        }
        try (DirectField field = DirectField.create(device, queue, 410L, Scene.AIR_ENDPOINT)) {
            TransportResult result = runTransport(
                    device, queue, field, SurfaceRho.RED, expectedLibraryMode, false);
            assertNoTransport(result, "AIR endpoint transferred energy");
            require(allZero(result.capture().bounceRgbaFloat16()),
                    "AIR endpoint produced a source bounce");
        }
    }

    private static void validateWrongThread(
            final MemorySegment device,
            final MTLCommandQueue queue,
            final int expectedLibraryMode
    ) throws InterruptedException {
        try (DirectField direct = DirectField.create(device, queue, 501L, Scene.OPEN);
             Arena arena = Arena.ofShared()) {
            TransportInput input = transportInput(arena, direct, SurfaceRho.RED);
            MemorySegment context = MetalNativeBridge.metallum_gi_transport_create_context_v1(
                    device, queue.nativeHandle(), direct.world());
            require(!MetalNativeBridge.isNullHandle(context),
                    "G4 wrong-thread context creation failed");
            assertLibraryMode(context, arena, expectedLibraryMode);
            MTLCommandBuffer commandBuffer = queue.makeCommandBuffer("G4 wrong-thread validation");
            MemorySegment directContext = direct.context();
            AtomicInteger status = new AtomicInteger();
            Thread thread = new Thread(() -> status.set(
                    MetalNativeBridge.metallum_gi_transport_encode_frozen_v1(
                            context, directContext, commandBuffer.handle(), MemorySegment.NULL,
                            input.header(), input.cells())
            ), "g4-wrong-thread");
            thread.start();
            thread.join();
            require(status.get() == GiTransportGpuResources.STATUS_WRONG_THREAD,
                    "G4 wrong-thread encode was admitted");
            commandBuffer.close();
            MetalNativeBridge.metallum_gi_transport_release_context_v1(context);
        }
    }

    private static void validateReleaseWhileInFlight(
            final MemorySegment device,
            final MTLCommandQueue queue
    ) {
        DirectField direct = DirectField.create(device, queue, 601L, Scene.OPEN);
        try (Arena arena = Arena.ofShared()) {
            TransportInput input = transportInput(arena, direct, SurfaceRho.RED);
            MemorySegment context = MetalNativeBridge.metallum_gi_transport_create_context_v1(
                    device, queue.nativeHandle(), direct.world());
            require(!MetalNativeBridge.isNullHandle(context),
                    "G4 in-flight context creation failed");
            MTLCommandBuffer commandBuffer = queue.makeCommandBuffer("G4 release validation");
            require(MetalNativeBridge.metallum_gi_transport_encode_frozen_v1(
                            context, direct.context(), commandBuffer.handle(), MemorySegment.NULL,
                            input.header(), input.cells()) == GiTransportGpuResources.STATUS_OK,
                    "G4 in-flight encode failed");
            MemorySegment semaphore = MetalNativeBridge.metallum_create_semaphore();
            require(!MetalNativeBridge.isNullHandle(semaphore),
                    "G4 completion semaphore creation failed");
            commandBuffer.commitWithSignal(semaphore);
            MetalNativeBridge.metallum_gi_transport_release_context_v1(context);
            direct.close();
            direct = null;
            require(MetalNativeBridge.metallum_semaphore_wait(semaphore, 10_000L) == 0,
                    "released in-flight G3/G4 owners did not complete safely");
            MetalNativeBridge.metallum_release_object(semaphore);
            commandBuffer.close();
        } finally {
            if (direct != null) direct.close();
        }
    }

    private static TransportResult runTransport(
            final MemorySegment device,
            final MTLCommandQueue queue,
            final DirectField direct,
            final SurfaceRho rho,
            final int expectedLibraryMode,
            final boolean validateStale
    ) {
        try (Arena arena = Arena.ofConfined()) {
            TransportInput input = transportInput(arena, direct, rho);
            MemorySegment context = MetalNativeBridge.metallum_gi_transport_create_context_v1(
                    device, queue.nativeHandle(), direct.world());
            require(!MetalNativeBridge.isNullHandle(context), "G4 context creation failed");
            try {
                assertLibraryMode(context, arena, expectedLibraryMode);
                MTLCommandBuffer forgedSource = queue.makeCommandBuffer(
                        "G4 forged G3 capability validation"
                );
                try {
                    require(MetalNativeBridge.metallum_gi_transport_encode_frozen_v1(
                                    context, MemorySegment.ofAddress(1L), forgedSource.handle(),
                                    MemorySegment.NULL, input.header(), input.cells())
                                    == GiTransportGpuResources.STATUS_INVALID,
                            "forged G3 native capability did not fail cleanly");
                } finally {
                    forgedSource.close();
                }
                MemorySegment misalignedHeaderStorage = arena.allocate(
                        GiTransportLayout.HEADER_BYTES + 1L, Byte.BYTES
                );
                MemorySegment misalignedCellsStorage = arena.allocate(
                        GiTransportLayout.CELLS_BYTES + 1L, Byte.BYTES
                );
                MemorySegment misalignedHeader = misalignedHeaderStorage.asSlice(
                        1L, GiTransportLayout.HEADER_BYTES
                );
                MemorySegment misalignedCells = misalignedCellsStorage.asSlice(
                        1L, GiTransportLayout.CELLS_BYTES
                );
                MemorySegment.copy(
                        input.header(), 0L, misalignedHeader, 0L,
                        GiTransportLayout.HEADER_BYTES
                );
                MemorySegment.copy(
                        input.cells(), 0L, misalignedCells, 0L,
                        GiTransportLayout.CELLS_BYTES
                );
                MTLCommandBuffer misaligned = queue.makeCommandBuffer(
                        "G4 misaligned packet validation"
                );
                try {
                    require(MetalNativeBridge.metallum_gi_transport_encode_frozen_v1(
                                    context, direct.context(), misaligned.handle(),
                                    MemorySegment.NULL, misalignedHeader, misalignedCells)
                                    == GiTransportGpuResources.STATUS_INVALID,
                            "misaligned G4 packet did not fail cleanly");
                } finally {
                    misaligned.close();
                }
                MTLCommandBuffer commandBuffer = queue.makeCommandBuffer("G4 physical validation");
                try {
                    require(MetalNativeBridge.metallum_gi_transport_encode_frozen_v1(
                                    context, direct.context(), commandBuffer.handle(),
                                    MemorySegment.NULL, input.header(), input.cells())
                                    == GiTransportGpuResources.STATUS_OK,
                            "G4 frozen encode failed");
                    commandBuffer.commit();
                    require(MetalNativeBridge.metallum_gi_transport_await_ready_v1(
                                    context, 10_000L) == GiTransportGpuResources.STATUS_OK,
                            "G4 frozen transport did not complete");
                } finally {
                    commandBuffer.close();
                }

                MTLCommandBuffer repeated = queue.makeCommandBuffer("G4 frozen no-op validation");
                try {
                    require(MetalNativeBridge.metallum_gi_transport_encode_frozen_v1(
                                    context, direct.context(), repeated.handle(), MemorySegment.NULL,
                                    input.header(), input.cells()) == GiTransportGpuResources.STATUS_OK,
                            "identical frozen G4 tuple was rejected");
                } finally {
                    repeated.close();
                }

                if (validateStale) {
                    long originalContent = input.header().get(LE_LONG, 32L);
                    input.header().set(LE_LONG, 32L, originalContent + 1L);
                    MTLCommandBuffer stale = queue.makeCommandBuffer("G4 stale validation");
                    try {
                        require(MetalNativeBridge.metallum_gi_transport_encode_frozen_v1(
                                        context, direct.context(), stale.handle(), MemorySegment.NULL,
                                        input.header(), input.cells())
                                        == GiTransportGpuResources.STATUS_STALE,
                                "changed G4 content epoch was admitted");
                    } finally {
                        stale.close();
                    }
                    input.header().set(LE_LONG, 32L, originalContent);
                }

                Stats stats = stats(context, arena);
                require(stats.ready() && !stats.buildInFlight()
                                && stats.shaderLibraryMode() == expectedLibraryMode
                                && stats.transportDispatches() == 1L
                                && stats.fullVolumeBuilds() == 1L
                                && stats.iterationCount() == 1
                                && stats.maximumDistance() == 8
                                && stats.cellCount() == GiTransportLayout.CELL_COUNT
                                && stats.accountedBytes() == stats.persistentBytes()
                                + stats.stagingBytes() + stats.readbackBytes()
                                && GiDirectSourceGpuResources.JAVA_PERSISTENT_PACKET_BYTES
                                == 70_216L
                                && GiTransportLayout.JAVA_PERSISTENT_PACKET_BYTES == 524_608L
                                && G2_CONSERVATIVE_BYTES + G3_END_TO_END_BYTES
                                + stats.accountedBytes()
                                + GiTransportLayout.JAVA_PERSISTENT_PACKET_BYTES
                                <= GI_BUDGET_BYTES,
                        "G4 lifecycle, bounded-work or allocatedSize budget differs");
                if (validateStale) {
                    require(stats.staleRejects() == 1L, "G4 stale reject was not counted");
                }
                GiTransportGpuResources.Capture capture = capture(
                        context, arena, input.cells()
                );
                return new TransportResult(capture, stats, rawHash(capture));
            } finally {
                MetalNativeBridge.metallum_gi_transport_release_context_v1(context);
            }
        }
    }

    private static TransportInput transportInput(
            final Arena arena,
            final DirectField direct,
            final SurfaceRho rho
    ) {
        MemorySegment header = arena.allocate(GiTransportLayout.HEADER_BYTES, Long.BYTES);
        MemorySegment cells = arena.allocate(GiTransportLayout.CELLS_BYTES, Long.BYTES);
        header.fill((byte) 0);
        cells.fill((byte) 0);
        int validSurfaces = 0;
        int unavailable = 0;
        for (int z = 0; z < GiTransportLayout.EDGE; z++) {
            for (int y = 0; y < GiTransportLayout.EDGE; y++) {
                for (int x = 0; x < GiTransportLayout.EDGE; x++) {
                    int geometry = direct.scene().geometry(x, y, z);
                    long offset = (long) GiTransportLayout.cellIndex(x, y, z)
                            * GiTransportLayout.CELL_BYTES;
                    cells.set(ValueLayout.JAVA_BYTE, offset + 14L, (byte) geometry);
                    cells.set(ValueLayout.JAVA_BYTE, offset + 15L,
                            (byte) (geometry == UNKNOWN || geometry == FALLBACK ? 0 : 0xff));
                    if (geometry == UNKNOWN || geometry == FALLBACK) unavailable++;
                    boolean surface = geometry == CONTENT
                            && ((x == SOURCE_X && y == SOURCE_Y && z == SOURCE_Z)
                            || (x == RECEIVER_X && y == RECEIVER_Y && z == RECEIVER_Z));
                    if (surface) {
                        int red = x == SOURCE_X && y == SOURCE_Y && z == SOURCE_Z
                                ? rho.redUnorm16() : 0xffff;
                        int green = x == SOURCE_X && y == SOURCE_Y && z == SOURCE_Z
                                ? rho.greenUnorm16() : 0xffff;
                        int blue = x == SOURCE_X && y == SOURCE_Y && z == SOURCE_Z
                                ? rho.blueUnorm16() : 0xffff;
                        cells.set(LE_SHORT, offset, (short) red);
                        cells.set(LE_SHORT, offset + 2L, (short) green);
                        cells.set(LE_SHORT, offset + 4L, (short) blue);
                        cells.set(LE_SHORT, offset + 6L, (short) 0xffff);
                        for (int face = 0; face < 6; face++) {
                            cells.set(ValueLayout.JAVA_BYTE, offset + 8L + face, (byte) 0xff);
                        }
                        validSurfaces++;
                    } else if (geometry == CONTENT) {
                        cells.set(LE_SHORT, offset + 6L, (short) 0xffff);
                    }
                }
            }
        }
        header.set(LE_INT, 0L, GiTransportLayout.ABI_VERSION);
        header.set(LE_INT, 4L, GiTransportLayout.HEADER_BYTES);
        header.set(LE_LONG, 8L, direct.world());
        header.set(LE_LONG, 16L, direct.clipmap());
        header.set(LE_LONG, 24L, direct.palette());
        header.set(LE_LONG, 32L, direct.content());
        header.set(LE_LONG, 40L, direct.staticSources());
        header.set(LE_LONG, 48L, direct.environment());
        header.set(LE_INT, 68L, GiTransportLayout.CELL_COUNT);
        header.set(LE_INT, 72L, GiTransportLayout.ITERATION_COUNT);
        header.set(LE_INT, 76L, GiTransportLayout.MAXIMUM_DISTANCE);
        header.set(LE_INT, 80L, validSurfaces);
        header.set(LE_INT, 84L, unavailable);
        header.set(LE_FLOAT, 88L, (float) GiTransportLayout.FORM_WEIGHT_NORMALIZATION);
        header.set(LE_FLOAT, 92L, GiTransportLayout.FP16_ABSOLUTE_TOLERANCE);
        header.set(LE_FLOAT, 96L, GiTransportLayout.FP16_RELATIVE_TOLERANCE);
        header.set(LE_LONG, 104L, direct.sourceStamp());
        return new TransportInput(header, cells);
    }

    private static void assertOpenRedField(final TransportResult result) {
        short[] bounce = result.capture().bounceRgbaFloat16();
        short[] shRed = result.capture().shRedRgbaFloat16();
        short[] shGreen = result.capture().shGreenRgbaFloat16();
        short[] shBlue = result.capture().shBlueRgbaFloat16();
        int source = rgbaIndex(SOURCE_X, SOURCE_Y, SOURCE_Z);
        int receiver = rgbaIndex(RECEIVER_X, RECEIVER_Y, RECEIVER_Z);
        require(half(bounce[source]) > 20_000.0F && bounce[source + 1] == 0
                        && bounce[source + 2] == 0 && half(bounce[source + 3]) > 0.99F,
                "red source did not create a channel-preserving rho/pi bounce");
        require(half(shRed[receiver]) > 0.001F
                        && half(shRed[receiver + 1]) > 0.001F
                        && half(shRed[receiver + 2]) > 0.001F
                        && half(shRed[receiver + 3]) > 0.001F
                        && allZero(shGreen) && allZero(shBlue),
                "visible red wall did not reach the white receiver as red-only L1 SH");
        require(Byte.toUnsignedInt(result.capture().confidenceUnorm8()[
                        GiTransportLayout.cellIndex(RECEIVER_X, RECEIVER_Y, RECEIVER_Z)]) > 0,
                "known aperture path produced no confidence");

        double dcRed = 0.0;
        for (int cell = 0; cell < GiTransportLayout.CELL_COUNT; cell++) {
            int base = cell * 4;
            float c0 = half(shRed[base]);
            float cx = half(shRed[base + 1]);
            float cy = half(shRed[base + 2]);
            float cz = half(shRed[base + 3]);
            require(Float.isFinite(c0) && Float.isFinite(cx)
                            && Float.isFinite(cy) && Float.isFinite(cz),
                    "G4 L1 SH contains a non-finite coefficient");
            double length = Math.sqrt((double) cx * cx + (double) cy * cy + (double) cz * cz);
            require(c0 >= -GiTransportLayout.FP16_ABSOLUTE_TOLERANCE
                            && length <= c0 + GiTransportLayout.FP16_ABSOLUTE_TOLERANCE,
                    "G4 L1 reconstruction can become negative beyond FP16 tolerance");
            dcRed += Math.max(0.0F, c0);
        }
        double tolerance = Math.max(
                GiTransportLayout.FP16_ABSOLUTE_TOLERANCE,
                DIRECT_IRRADIANCE * GiTransportLayout.FP16_RELATIVE_TOLERANCE
        );
        require(dcRed <= DIRECT_IRRADIANCE + tolerance,
                "G4 captured DC energy exceeds reflected direct input");
    }

    private static void assertNoTransport(final TransportResult result, final String message) {
        int receiver = rgbaIndex(RECEIVER_X, RECEIVER_Y, RECEIVER_Z);
        require(isZeroCell(result.capture().shRedRgbaFloat16(), receiver)
                        && isZeroCell(result.capture().shGreenRgbaFloat16(), receiver)
                        && isZeroCell(result.capture().shBlueRgbaFloat16(), receiver),
                message);
    }

    private static GiTransportGpuResources.Capture capture(
            final MemorySegment context,
            final Arena arena,
            final MemorySegment cells
    ) {
        MemorySegment bounce = arena.allocate(GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES);
        MemorySegment shRed = arena.allocate(GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES);
        MemorySegment shGreen = arena.allocate(GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES);
        MemorySegment shBlue = arena.allocate(GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES);
        MemorySegment confidence = arena.allocate(
                GiTransportLayout.CAPTURE_CONFIDENCE_BYTES, Byte.BYTES);
        require(MetalNativeBridge.metallum_gi_transport_capture_volume_once_v1(
                        context, bounce, shRed, shGreen, shBlue, confidence)
                        == GiTransportGpuResources.STATUS_OK,
                "G4 full-volume diagnostic capture failed");
        return new GiTransportGpuResources.Capture(
                readShorts(bounce), readShorts(shRed), readShorts(shGreen), readShorts(shBlue),
                confidence.toArray(ValueLayout.JAVA_BYTE), validity(cells)
        );
    }

    private static byte[] validity(final MemorySegment cells) {
        byte[] values = new byte[GiTransportLayout.CELL_COUNT];
        for (int cell = 0; cell < values.length; cell++) {
            values[cell] = cells.get(
                    ValueLayout.JAVA_BYTE,
                    (long) cell * GiTransportLayout.CELL_BYTES + 14L
            );
        }
        return values;
    }

    private static Stats stats(final MemorySegment context, final Arena arena) {
        MemorySegment stats = arena.allocate(GiTransportLayout.STATS_BYTES, Long.BYTES);
        require(MetalNativeBridge.metallum_gi_transport_get_stats_v1(
                        context, stats, stats.byteSize()) == GiTransportGpuResources.STATUS_OK,
                "G4 native statistics query failed");
        return new Stats(
                stats.get(LE_INT, 0L) == 1,
                stats.get(LE_INT, 4L) == 1,
                stats.get(LE_INT, 8L),
                stats.get(LE_LONG, 64L), stats.get(LE_LONG, 72L),
                stats.get(LE_LONG, 80L), stats.get(LE_LONG, 88L),
                stats.get(LE_LONG, 112L), stats.get(LE_LONG, 144L),
                stats.get(LE_LONG, 152L), stats.get(LE_INT, 172L),
                stats.get(LE_INT, 176L), stats.get(LE_INT, 180L)
        );
    }

    private static void assertLibraryMode(
            final MemorySegment context,
            final Arena arena,
            final int expected
    ) {
        require(stats(context, arena).shaderLibraryMode() == expected,
                "G4 actual shader-library mode differs from the requested validation task");
    }

    private static String rawHash(final GiTransportGpuResources.Capture capture) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, capture.bounceRgbaFloat16());
            update(digest, capture.shRedRgbaFloat16());
            update(digest, capture.shGreenRgbaFloat16());
            update(digest, capture.shBlueRgbaFloat16());
            digest.update(capture.confidenceUnorm8());
            digest.update(capture.validity());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void update(final MessageDigest digest, final short[] values) {
        for (short value : values) {
            digest.update((byte) value);
            digest.update((byte) (value >>> 8));
        }
    }

    private static boolean equalCapture(
            final GiTransportGpuResources.Capture left,
            final GiTransportGpuResources.Capture right
    ) {
        return Arrays.equals(left.bounceRgbaFloat16(), right.bounceRgbaFloat16())
                && Arrays.equals(left.shRedRgbaFloat16(), right.shRedRgbaFloat16())
                && Arrays.equals(left.shGreenRgbaFloat16(), right.shGreenRgbaFloat16())
                && Arrays.equals(left.shBlueRgbaFloat16(), right.shBlueRgbaFloat16())
                && Arrays.equals(left.confidenceUnorm8(), right.confidenceUnorm8())
                && Arrays.equals(left.validity(), right.validity());
    }

    private static short[] readShorts(final MemorySegment source) {
        short[] output = new short[Math.toIntExact(source.byteSize() / Short.BYTES)];
        MemorySegment.copy(source, 0L, MemorySegment.ofArray(output), 0L, source.byteSize());
        return output;
    }

    private static boolean allZero(final short[] values) {
        for (short value : values) if (value != 0) return false;
        return true;
    }

    private static boolean isZeroCell(final short[] values, final int base) {
        return values[base] == 0 && values[base + 1] == 0
                && values[base + 2] == 0 && values[base + 3] == 0;
    }

    private static int nonZeroCellCount(final short[] values) {
        int result = 0;
        for (int cell = 0; cell < GiTransportLayout.CELL_COUNT; cell++) {
            if (!isZeroCell(values, cell * 4)) result++;
        }
        return result;
    }

    private static boolean equalCell(
            final short[] left,
            final short[] right,
            final int x,
            final int y,
            final int z
    ) {
        int base = rgbaIndex(x, y, z);
        return left[base] == right[base] && left[base + 1] == right[base + 1]
                && left[base + 2] == right[base + 2] && left[base + 3] == right[base + 3];
    }

    private static int rgbaIndex(final int x, final int y, final int z) {
        return GiTransportLayout.cellIndex(x, y, z) * 4;
    }

    private static float half(final short value) {
        return Float.float16ToFloat(value);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }

    private enum SurfaceRho {
        BLACK(0, 0, 0),
        RED(0xffff, 0, 0);

        private final int redUnorm16;
        private final int greenUnorm16;
        private final int blueUnorm16;

        SurfaceRho(final int redUnorm16, final int greenUnorm16, final int blueUnorm16) {
            this.redUnorm16 = redUnorm16;
            this.greenUnorm16 = greenUnorm16;
            this.blueUnorm16 = blueUnorm16;
        }

        int redUnorm16() { return this.redUnorm16; }
        int greenUnorm16() { return this.greenUnorm16; }
        int blueUnorm16() { return this.blueUnorm16; }
    }

    private enum Scene {
        OPEN(true, CONTENT, AIR),
        ZERO_SOURCE(false, CONTENT, AIR),
        SEALED(true, CONTENT, CONTENT),
        UNKNOWN_BARRIER(true, CONTENT, UNKNOWN),
        FALLBACK_BARRIER(true, CONTENT, FALLBACK),
        AIR_ENDPOINT(true, AIR, AIR);

        private final boolean emits;
        private final int sourceState;
        private final int barrierState;

        Scene(final boolean emits, final int sourceState, final int barrierState) {
            this.emits = emits;
            this.sourceState = sourceState;
            this.barrierState = barrierState;
        }

        int geometry(final int x, final int y, final int z) {
            if (x == SOURCE_X && y == SOURCE_Y && z == SOURCE_Z) return this.sourceState;
            if (x == RECEIVER_X && y == RECEIVER_Y && z == RECEIVER_Z) return CONTENT;
            if (x == BARRIER_X && y == BARRIER_Y && z == BARRIER_Z) return this.barrierState;
            return AIR;
        }
    }

    private static final class DirectField implements AutoCloseable {
        private final Arena arena;
        private final GiDirectSourceGpuResources resources;
        private final long world;
        private final long clipmap = 1L;
        private final long palette = 1L;
        private final long content = 1L;
        private final long staticSources = 1L;
        private final long environment = 1L;
        private final long sourceStamp;
        private final Scene scene;
        private boolean closed;

        private DirectField(
                final Arena arena,
                final GiDirectSourceGpuResources resources,
                final long world,
                final Scene scene
        ) {
            this.arena = arena;
            this.resources = resources;
            this.world = world;
            this.scene = scene;
            GiDirectSourceEpoch epoch = new GiDirectSourceEpoch(
                    world, 1L, 1L, this.clipmap, this.palette, this.content,
                    new LightWorldToken(world, DIMENSION), this.staticSources, this.environment
            );
            this.sourceStamp = GiDirectSourceCoordinator.transportSourceStamp(epoch, 0, 0, 0);
        }

        static DirectField create(
                final MemorySegment device,
                final MTLCommandQueue queue,
                final long world,
                final Scene scene
        ) {
            Arena arena = Arena.ofConfined();
            GiDirectSourceGpuResources resources = GiDirectSourceGpuResources.create(
                    device, queue.nativeHandle(), world,
                    MetalNativeBridge::metallum_gi_direct_source_release_context_v1
            );
            require(resources != null, "complete G3 fixture context creation failed");
            DirectField result = new DirectField(arena, resources, world, scene);
            try {
                result.populate(queue);
                return result;
            } catch (RuntimeException | Error failure) {
                result.close();
                throw failure;
            }
        }

        private void populate(final MTLCommandQueue queue) {
            for (int start = 0; start < GiDirectSourceLayout.TOTAL_BRICKS;
                 start += GiDirectSourceLayout.MAX_DRAIN_PER_FRAME) {
                DirectPacket packet = directPacket(this.arena, this, start);
                MTLCommandBuffer commandBuffer = queue.makeCommandBuffer(
                        "G3 complete fixture bricks " + start);
                try {
                    require(this.resources.encode(
                                    commandBuffer.handle(), MemorySegment.NULL, packet.batch())
                                    == GiDirectSourceGpuResources.STATUS_OK,
                            "complete G3 fixture batch was rejected");
                    commandBuffer.commit();
                    require(this.resources.awaitReady(10_000L),
                            "complete G3 fixture batch did not finish");
                } finally {
                    commandBuffer.close();
                }
            }
            require(this.resources.publishScheduler(new GiDirectDirtyQueue.Telemetry(
                            GiDirectSourceLayout.TOTAL_BRICKS, 0L,
                            GiDirectSourceLayout.TOTAL_BRICKS, 0L, 0, 0L, 1L))
                            == GiDirectSourceGpuResources.STATUS_OK,
                    "complete G3 scheduler publication failed");
            GiDirectSourceGpuResources.Stats stats = this.resources.stats();
            require(stats.ready() && !stats.buildInFlight()
                            && stats.batches() == 24L
                            && stats.dirtyBricks() == GiDirectSourceLayout.TOTAL_BRICKS
                            && stats.geometryApplyDispatches() == GiDirectSourceLayout.TOTAL_BRICKS
                            && stats.directInjectDispatches() == GiDirectSourceLayout.TOTAL_BRICKS
                            && stats.fullVolumeRebuilds() == 1L,
                    "complete 192-brick G3 source fixture differs");
        }

        long world() { return this.world; }
        long clipmap() { return this.clipmap; }
        long palette() { return this.palette; }
        long content() { return this.content; }
        long staticSources() { return this.staticSources; }
        long environment() { return this.environment; }
        long sourceStamp() { return this.sourceStamp; }
        Scene scene() { return this.scene; }
        MemorySegment context() { return this.resources.transportContextHandle(); }

        @Override
        public void close() {
            if (this.closed) return;
            this.closed = true;
            this.resources.close();
            this.arena.close();
        }
    }

    private static DirectPacket directPacket(
            final Arena arena,
            final DirectField field,
            final int startBrick
    ) {
        int count = Math.min(GiDirectSourceLayout.MAX_DRAIN_PER_FRAME,
                GiDirectSourceLayout.TOTAL_BRICKS - startBrick);
        MemorySegment header = arena.allocate(GiDirectSourceGpuResources.HEADER_BYTES, Long.BYTES);
        MemorySegment bricks = arena.allocate(
                (long) count * GiDirectSourceGpuResources.BRICK_BYTES, Long.BYTES);
        MemorySegment cells = arena.allocate(
                (long) count * 512L * GiDirectSourceGpuResources.CELL_BYTES, Long.BYTES);
        MemorySegment sources = arena.allocate(1L, Byte.BYTES).asSlice(0L, 0L);
        header.fill((byte) 0);
        bricks.fill((byte) 0);
        cells.fill((byte) 0);
        header.set(LE_INT, 0L, GiDirectSourceGpuResources.ABI_VERSION);
        header.set(LE_INT, 4L, GiDirectSourceGpuResources.HEADER_BYTES);
        header.set(LE_LONG, 8L, field.world());
        header.set(LE_LONG, 16L, field.clipmap());
        header.set(LE_LONG, 24L, field.palette());
        header.set(LE_LONG, 32L, field.content());
        header.set(LE_LONG, 40L, field.staticSources());
        header.set(LE_LONG, 48L, field.environment());
        header.set(LE_INT, 144L, count);
        for (int batch = 0; batch < count; batch++) {
            int brickId = startBrick + batch;
            int cascade = GiDirectSourceLayout.cascadeForBrickId(brickId);
            int brickX = GiDirectSourceLayout.brickX(brickId);
            int brickY = GiDirectSourceLayout.brickY(brickId);
            int brickZ = GiDirectSourceLayout.brickZ(brickId);
            long brickOffset = (long) batch * GiDirectSourceGpuResources.BRICK_BYTES;
            bricks.set(LE_INT, brickOffset, cascade);
            bricks.set(LE_INT, brickOffset + 4L, brickX);
            bricks.set(LE_INT, brickOffset + 8L, brickY);
            bricks.set(LE_INT, brickOffset + 12L, brickZ);
            bricks.set(LE_LONG, brickOffset + 24L, brickId + 1L);
            for (int localZ = 0; localZ < 8; localZ++) {
                for (int localY = 0; localY < 8; localY++) {
                    for (int localX = 0; localX < 8; localX++) {
                        int globalX = brickX * 8 + localX;
                        int globalY = brickY * 8 + localY;
                        int globalZ = brickZ * 8 + localZ;
                        int geometry = cascade == 0
                                ? field.scene().geometry(globalX, globalY, globalZ) : AIR;
                        long cellOffset = ((long) batch * 512L
                                + (localZ * 8L + localY) * 8L + localX)
                                * GiDirectSourceGpuResources.CELL_BYTES;
                        if (cascade == 0 && field.scene().emits
                                && globalX == SOURCE_X && globalY == SOURCE_Y
                                && globalZ == SOURCE_Z && geometry == CONTENT) {
                            cells.set(LE_SHORT, cellOffset, Float.floatToFloat16(DIRECT_IRRADIANCE));
                            cells.set(LE_SHORT, cellOffset + 2L, Float.floatToFloat16(DIRECT_IRRADIANCE));
                            cells.set(LE_SHORT, cellOffset + 4L, Float.floatToFloat16(DIRECT_IRRADIANCE));
                            cells.set(LE_SHORT, cellOffset + 6L, Float.floatToFloat16(1.0F));
                        }
                        cells.set(ValueLayout.JAVA_BYTE, cellOffset + 8L, (byte) geometry);
                    }
                }
            }
        }
        GiDirectSourceGpuResources.PreparedBatch prepared =
                new GiDirectSourceGpuResources.PreparedBatch(
                        header, bricks, cells, sources, count, 0);
        return new DirectPacket(prepared);
    }

    private record DirectPacket(GiDirectSourceGpuResources.PreparedBatch batch) {
    }

    private record TransportInput(MemorySegment header, MemorySegment cells) {
    }

    private record TransportResult(
            GiTransportGpuResources.Capture capture,
            Stats stats,
            String rawHash
    ) {
    }

    private record Stats(
            boolean ready,
            boolean buildInFlight,
            int shaderLibraryMode,
            long persistentBytes,
            long stagingBytes,
            long readbackBytes,
            long transportDispatches,
            long staleRejects,
            long fullVolumeBuilds,
            long accountedBytes,
            int cellCount,
            int iterationCount,
            int maximumDistance
    ) {
    }
}
