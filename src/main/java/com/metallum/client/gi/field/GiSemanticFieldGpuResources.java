package com.metallum.client.gi.field;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Render-thread-confined owner for the structurally-off G2 semantic field. It intentionally
 * exposes neither native texture handles nor any production encoder binding.
 */
public final class GiSemanticFieldGpuResources implements AutoCloseable {
    public static final int ABI_VERSION = 1;
    public static final int SEMANTIC_VERSION = 1;
    public static final int LAYOUT_BYTES = 160;
    public static final int UPLOAD_HEADER_BYTES = 128;
    public static final int STATS_BYTES = 160;
    public static final int CAPTURE_REQUEST_BYTES = 32;
    public static final int CAPTURE_INFO_BYTES = 176;
    public static final long MEMORY_BUDGET_BYTES = 24L * 1024L * 1024L;

    public static final int STATUS_OK = 1;
    public static final int STATUS_INVALID = -1;
    public static final int STATUS_BUSY = -2;
    public static final int STATUS_STALE = -3;
    public static final int STATUS_CAPTURE_CONSUMED = -4;
    public static final int STATUS_WRONG_THREAD = -5;

    public static final int MEDIUM_MASK_ALL = 0x1f;
    public static final int VALIDITY_UNKNOWN = 0;
    public static final int VALIDITY_EMPTY = 1;
    public static final int VALIDITY_CONTENT = 2;
    public static final int VALIDITY_FALLBACK = 3;
    public static final int PALETTE_UNKNOWN = 0;
    public static final int PALETTE_AIR = 1;
    public static final int PALETTE_FALLBACK = 2;
    public static final long CPU_FIELD_PAYLOAD_BYTES = (long) GiSemanticGpuSnapshot.CELL_COUNT * 27L;
    public static final long GPU_UPLOAD_PAYLOAD_BYTES = (long) GiSemanticGpuSnapshot.CELL_COUNT * 29L;

    private static final int[] METAL_PIXEL_FORMATS = {110, 115, 70, 30, 73, 23, 10};
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);

    public record Stats(
            boolean ready,
            boolean buildInFlight,
            long worldGeneration,
            long clipmapGeneration,
            long paletteGeneration,
            long contentGeneration,
            long persistentBytes,
            long peakUploadStagingBytes,
            long peakCaptureReadbackBytes,
            long uploadCount,
            long mipDispatchCount,
            long rejectedCount,
            long resetCount,
            long captureCount,
            long uploadSerial,
            int nearOriginX,
            int nearOriginY,
            int nearOriginZ,
            int shaderLibraryMode,
            byte[] sourceDigest
    ) {
        public Stats {
            sourceDigest = sourceDigest.clone();
        }

        public long peakAccountedBytes() {
            return Math.addExact(persistentBytes,
                    Math.addExact(peakUploadStagingBytes, peakCaptureReadbackBytes));
        }

        /** Includes the CPU truth plus immutable GPU packet and its confined FFM upload copy. */
        public long conservativeEndToEndBytes() {
            return Math.addExact(peakAccountedBytes(), Math.addExact(
                    CPU_FIELD_PAYLOAD_BYTES,
                    Math.addExact(UPLOAD_HEADER_BYTES, Math.multiplyExact(2L, GPU_UPLOAD_PAYLOAD_BYTES))));
        }
    }

    public record CaptureInfo(
            long worldGeneration,
            long clipmapGeneration,
            long contentGeneration,
            long paletteGeneration,
            long uploadSerial,
            long captureSerial,
            int cascade,
            int mip,
            int axis,
            int slice,
            int edge,
            int originX,
            int originY,
            int originZ,
            int cellSizeBlocks,
            int shaderLibraryMode,
            int semanticVersion,
            byte[] sourceDigest,
            int[] pixelFormats,
            long persistentBytes
    ) {
        public CaptureInfo {
            sourceDigest = sourceDigest.clone();
            pixelFormats = pixelFormats.clone();
        }
    }

    /** Raw 2D Z slice, one compact row after another, with all seven semantic planes. */
    public record Capture(
            CaptureInfo info,
            short[] materialRgbaUnorm16,
            short[] emissionRgbaFloat16,
            byte[] faceWeights0RgbaUnorm8,
            byte[] faceWeights1RgUnorm8,
            byte[] stateRgbaUint8,
            short[] paletteIdsUint16,
            byte[] knownCoverageUnorm8
    ) {
    }

    private final Thread ownerThread;
    private final Consumer<MemorySegment> deferredRelease;
    private final Arena stateArena;
    private final MemorySegment statsPacket;
    private final int[] nativePixelFormats;
    private MemorySegment context;
    private boolean closed;

    private GiSemanticFieldGpuResources(
            final MemorySegment context,
            final Consumer<MemorySegment> deferredRelease,
            final int[] nativePixelFormats
    ) {
        this.ownerThread = Thread.currentThread();
        this.context = context;
        this.deferredRelease = deferredRelease;
        this.nativePixelFormats = nativePixelFormats;
        this.stateArena = Arena.ofConfined();
        this.statsPacket = this.stateArena.allocate(STATS_BYTES, Long.BYTES);
    }

    public static int[] validateNativeAbi() {
        if (MetalNativeBridge.metallum_gi_semantic_abi_version_v1() != ABI_VERSION) {
            throw new IllegalStateException("Native G2 semantic ABI version mismatch");
        }
        int[] expectedPrefix = {
                ABI_VERSION, LAYOUT_BYTES, UPLOAD_HEADER_BYTES, STATS_BYTES,
                CAPTURE_REQUEST_BYTES, CAPTURE_INFO_BYTES,
                GiSemanticGpuSnapshot.CASCADE_COUNT, GiSemanticGpuSnapshot.EDGE,
                GiSemanticGpuSnapshot.MIP_COUNT,
                1, 4, 8,
                8, 8, 4, 2, 4, 2, 1,
                SEMANTIC_VERSION,
                STATUS_STALE, STATUS_BUSY, STATUS_CAPTURE_CONSUMED, STATUS_WRONG_THREAD,
                MEDIUM_MASK_ALL, VALIDITY_FALLBACK, 0xff, PALETTE_FALLBACK
        };
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment layout = arena.allocate(LAYOUT_BYTES, Long.BYTES);
            layout.fill((byte) 0);
            int status = MetalNativeBridge.metallum_gi_semantic_layout_v1(layout, layout.byteSize());
            if (status != STATUS_OK) {
                throw new IllegalStateException("Native G2 semantic layout query failed: " + status);
            }
            for (int index = 0; index < expectedPrefix.length; index++) {
                int actual = layout.get(LE_INT, (long) index * Integer.BYTES);
                if (actual != expectedPrefix[index]) {
                    throw new IllegalStateException("Native G2 semantic layout mismatch at word " + index
                            + ": expected " + expectedPrefix[index] + ", got " + actual);
                }
            }
            int[] formats = new int[7];
            for (int index = 0; index < formats.length; index++) {
                formats[index] = layout.get(LE_INT, (long) (28 + index) * Integer.BYTES);
                if (formats[index] != METAL_PIXEL_FORMATS[index]) {
                    throw new IllegalStateException("Native G2 semantic pixel format mismatch at plane " + index
                            + ": expected " + METAL_PIXEL_FORMATS[index] + ", got " + formats[index]);
                }
            }
            if (layout.get(LE_INT, 35L * Integer.BYTES) != 1) {
                throw new IllegalStateException("Native G2 semantic palette-generation feature is absent");
            }
            for (int index = 36; index < 40; index++) {
                if (layout.get(LE_INT, (long) index * Integer.BYTES) != 0) {
                    throw new IllegalStateException("Native G2 semantic reserved layout word is non-zero: " + index);
                }
            }
            return formats;
        }
    }

    public static @Nullable GiSemanticFieldGpuResources create(
            final MemorySegment device,
            final MemorySegment commandQueue,
            final long worldGeneration,
            final Consumer<MemorySegment> deferredRelease
    ) {
        Objects.requireNonNull(deferredRelease, "deferredRelease");
        if (worldGeneration <= 0L
                || MetalNativeBridge.isNullHandle(device)
                || MetalNativeBridge.isNullHandle(commandQueue)) {
            return null;
        }
        int[] formats = validateNativeAbi();
        MemorySegment handle = MetalNativeBridge.metallum_gi_semantic_create_context_v1(
                device, commandQueue, worldGeneration);
        return MetalNativeBridge.isNullHandle(handle)
                ? null
                : new GiSemanticFieldGpuResources(handle, deferredRelease, formats);
    }

    public int uploadOnce(final GiSemanticGpuSnapshot snapshot) {
        assertUsable();
        Objects.requireNonNull(snapshot, "snapshot");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment header = arena.allocate(UPLOAD_HEADER_BYTES, Long.BYTES);
            header.fill((byte) 0);
            header.set(LE_INT, 0L, ABI_VERSION);
            header.set(LE_INT, 4L, UPLOAD_HEADER_BYTES);
            header.set(LE_LONG, 8L, snapshot.worldGeneration());
            header.set(LE_LONG, 16L, snapshot.clipmapGeneration());
            header.set(LE_LONG, 24L, snapshot.contentGeneration());
            header.set(LE_INT, 68L, SEMANTIC_VERSION);
            header.set(LE_LONG, 112L, snapshot.paletteGeneration());
            snapshot.copyHeaderMetadataTo(header);

            MemorySegment material = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 8L, Short.BYTES);
            MemorySegment emission = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 8L, Short.BYTES);
            MemorySegment faces0 = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 4L, Byte.BYTES);
            MemorySegment faces1 = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 2L, Byte.BYTES);
            MemorySegment state = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 4L, Byte.BYTES);
            MemorySegment palette = arena.allocate((long) GiSemanticGpuSnapshot.CELL_COUNT * 2L, Short.BYTES);
            MemorySegment coverage = arena.allocate(GiSemanticGpuSnapshot.CELL_COUNT, Byte.BYTES);
            snapshot.copyPlanesTo(material, emission, faces0, faces1, state, palette, coverage);
            return MetalNativeBridge.metallum_gi_semantic_upload_once_v1(
                    this.context, header, material, emission, faces0, faces1, state, palette, coverage);
        }
    }

    public boolean awaitReady(final long timeoutMillis) {
        assertUsable();
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("G2 await timeout must be positive");
        }
        return MetalNativeBridge.metallum_gi_semantic_await_ready_v1(this.context, timeoutMillis) == STATUS_OK;
    }

    public int reset(
            final long newWorldGeneration,
            final long newClipmapGeneration,
            final long newPaletteGeneration,
            final long newContentGeneration
    ) {
        assertUsable();
        if (newWorldGeneration <= 0L || newClipmapGeneration <= 0L
                || newPaletteGeneration <= 0L || newContentGeneration <= 0L) {
            throw new IllegalArgumentException("G2 reset generations must be positive");
        }
        return MetalNativeBridge.metallum_gi_semantic_reset_v1(
                this.context, newWorldGeneration, newClipmapGeneration,
                newPaletteGeneration, newContentGeneration);
    }

    /** Diagnostic-only, one raw Z-slice accepted at most once per completed upload generation. */
    public @Nullable Capture captureSliceOnce(final int cascade, final int mip, final int sliceZ) {
        assertUsable();
        if (cascade < 0 || cascade >= GiSemanticGpuSnapshot.CASCADE_COUNT
                || mip < 0 || mip >= GiSemanticGpuSnapshot.MIP_COUNT) {
            throw new IllegalArgumentException("G2 capture cascade/mip is outside the field");
        }
        int edge = GiSemanticGpuSnapshot.EDGE >> mip;
        if (sliceZ < 0 || sliceZ >= edge) {
            throw new IllegalArgumentException("G2 capture slice is outside the selected mip");
        }
        int cells = edge * edge;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment request = arena.allocate(CAPTURE_REQUEST_BYTES, Integer.BYTES);
            request.fill((byte) 0);
            request.set(LE_INT, 0L, ABI_VERSION);
            request.set(LE_INT, 4L, CAPTURE_REQUEST_BYTES);
            request.set(LE_INT, 8L, cascade);
            request.set(LE_INT, 12L, mip);
            request.set(LE_INT, 16L, sliceZ);
            MemorySegment info = arena.allocate(CAPTURE_INFO_BYTES, Long.BYTES);
            MemorySegment material = arena.allocate((long) cells * 8L, Short.BYTES);
            MemorySegment emission = arena.allocate((long) cells * 8L, Short.BYTES);
            MemorySegment faces0 = arena.allocate((long) cells * 4L, Byte.BYTES);
            MemorySegment faces1 = arena.allocate((long) cells * 2L, Byte.BYTES);
            MemorySegment state = arena.allocate((long) cells * 4L, Byte.BYTES);
            MemorySegment palette = arena.allocate((long) cells * 2L, Short.BYTES);
            MemorySegment coverage = arena.allocate(cells, Byte.BYTES);
            int status = MetalNativeBridge.metallum_gi_semantic_capture_slice_once_v1(
                    this.context, request, info, material, emission, faces0, faces1, state, palette, coverage);
            if (status != STATUS_OK) {
                return null;
            }
            CaptureInfo captureInfo = readCaptureInfo(info);
            if (!Arrays.equals(captureInfo.pixelFormats(), this.nativePixelFormats)) {
                throw new IllegalStateException("G2 capture pixel formats differ from the ABI layout");
            }
            short[] materialArray = new short[cells * 4];
            short[] emissionArray = new short[cells * 4];
            byte[] faces0Array = new byte[cells * 4];
            byte[] faces1Array = new byte[cells * 2];
            byte[] stateArray = new byte[cells * 4];
            short[] paletteArray = new short[cells];
            byte[] coverageArray = new byte[cells];
            copyShorts(material, materialArray);
            copyShorts(emission, emissionArray);
            copyBytes(faces0, faces0Array);
            copyBytes(faces1, faces1Array);
            copyBytes(state, stateArray);
            copyShorts(palette, paletteArray);
            copyBytes(coverage, coverageArray);
            return new Capture(captureInfo, materialArray, emissionArray, faces0Array, faces1Array,
                    stateArray, paletteArray, coverageArray);
        }
    }

    public Stats stats() {
        assertUsable();
        this.statsPacket.fill((byte) 0);
        int status = MetalNativeBridge.metallum_gi_semantic_get_stats_v1(
                this.context, this.statsPacket, this.statsPacket.byteSize());
        if (status != STATUS_OK) {
            throw new IllegalStateException("Native G2 semantic stats query failed: " + status);
        }
        byte[] digest = new byte[GiSemanticGpuSnapshot.DIGEST_BYTES];
        MemorySegment.copy(this.statsPacket, 120L, MemorySegment.ofArray(digest), 0L, digest.length);
        return new Stats(
                this.statsPacket.get(LE_INT, 0L) == 1,
                this.statsPacket.get(LE_INT, 4L) == 1,
                this.statsPacket.get(LE_LONG, 8L),
                this.statsPacket.get(LE_LONG, 16L),
                this.statsPacket.get(LE_LONG, 152L),
                this.statsPacket.get(LE_LONG, 24L),
                this.statsPacket.get(LE_LONG, 32L),
                this.statsPacket.get(LE_LONG, 40L),
                this.statsPacket.get(LE_LONG, 48L),
                this.statsPacket.get(LE_LONG, 56L),
                this.statsPacket.get(LE_LONG, 64L),
                this.statsPacket.get(LE_LONG, 72L),
                this.statsPacket.get(LE_LONG, 80L),
                this.statsPacket.get(LE_LONG, 88L),
                this.statsPacket.get(LE_LONG, 96L),
                this.statsPacket.get(LE_INT, 104L),
                this.statsPacket.get(LE_INT, 108L),
                this.statsPacket.get(LE_INT, 112L),
                this.statsPacket.get(LE_INT, 116L),
                digest
        );
    }

    MemorySegment nativeContextForValidation() {
        assertUsable();
        return this.context;
    }

    @Override
    public void close() {
        assertOwnerThread();
        if (this.closed) {
            return;
        }
        this.closed = true;
        MemorySegment released = this.context;
        this.context = MemorySegment.NULL;
        this.stateArena.close();
        this.deferredRelease.accept(released);
    }

    private CaptureInfo readCaptureInfo(final MemorySegment info) {
        if (info.get(LE_INT, 0L) != ABI_VERSION || info.get(LE_INT, 4L) != CAPTURE_INFO_BYTES) {
            throw new IllegalStateException("G2 capture provenance ABI differs");
        }
        byte[] digest = new byte[GiSemanticGpuSnapshot.DIGEST_BYTES];
        MemorySegment.copy(info, 96L, MemorySegment.ofArray(digest), 0L, digest.length);
        int[] formats = new int[7];
        for (int index = 0; index < formats.length; index++) {
            formats[index] = info.get(LE_INT, 128L + (long) index * Integer.BYTES);
        }
        return new CaptureInfo(
                info.get(LE_LONG, 8L), info.get(LE_LONG, 16L), info.get(LE_LONG, 24L),
                info.get(LE_LONG, 168L), info.get(LE_LONG, 32L), info.get(LE_LONG, 40L),
                info.get(LE_INT, 48L), info.get(LE_INT, 52L), info.get(LE_INT, 56L),
                info.get(LE_INT, 60L), info.get(LE_INT, 64L),
                info.get(LE_INT, 68L), info.get(LE_INT, 72L), info.get(LE_INT, 76L),
                info.get(LE_INT, 80L), info.get(LE_INT, 84L), info.get(LE_INT, 88L),
                digest, formats, info.get(LE_LONG, 160L));
    }

    private static void copyShorts(final MemorySegment source, final short[] destination) {
        MemorySegment.copy(source, ValueLayout.JAVA_SHORT, 0L,
                MemorySegment.ofArray(destination), ValueLayout.JAVA_SHORT, 0L, destination.length);
    }

    private static void copyBytes(final MemorySegment source, final byte[] destination) {
        MemorySegment.copy(source, 0L, MemorySegment.ofArray(destination), 0L, destination.length);
    }

    private void assertUsable() {
        assertOwnerThread();
        if (this.closed || MetalNativeBridge.isNullHandle(this.context)) {
            throw new IllegalStateException("G2 semantic field resources are closed");
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G2 Metal resources are confined to their render thread");
        }
    }
}
