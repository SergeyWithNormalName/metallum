package com.metallum.client.gi.field;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Render-thread owner for the isolated G1 private 3D field. It exposes no texture handle and no
 * production shader binding. Upload and capture are explicit one-shot diagnostic operations.
 */
public final class GiFieldGpuResources implements AutoCloseable {
    public static final int ABI_VERSION = 1;
    public static final int NATIVE_LAYOUT_BYTES = 64;
    public static final int STATS_BYTES = 80;
    public static final int STATUS_OK = 1;
    public static final int STATUS_INVALID = -1;
    public static final int STATUS_BUSY = -2;
    public static final int STATUS_STALE = -3;
    public static final int STATUS_CAPTURE_CONSUMED = -4;

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);

    public record Stats(boolean ready, boolean buildInFlight, long worldGeneration,
                        long persistentBytes, long uploadCount, long mipDispatchCount,
                        long rejectedCount, long resetCount, long captureCount,
                        int nearOriginX, int nearOriginY, int nearOriginZ) {
    }

    public record Capture(int cascade, int mip, int edge, short[] packedField, byte[] coverage) {
    }

    private final Thread ownerThread;
    private final Consumer<MemorySegment> deferredRelease;
    private final Arena stateArena;
    private final MemorySegment statsPacket;
    private MemorySegment context;
    private boolean closed;

    private GiFieldGpuResources(
            final MemorySegment context,
            final Consumer<MemorySegment> deferredRelease
    ) {
        this.ownerThread = Thread.currentThread();
        this.context = context;
        this.deferredRelease = deferredRelease;
        this.stateArena = Arena.ofConfined();
        this.statsPacket = this.stateArena.allocate(STATS_BYTES, Long.BYTES);
    }

    public static void validateNativeAbi() {
        if (MetalNativeBridge.metallum_gi_field_abi_version_v1() != ABI_VERSION) {
            throw new IllegalStateException("Native G1 field ABI version mismatch");
        }
        int[] expected = {
                ABI_VERSION,
                NATIVE_LAYOUT_BYTES,
                STATS_BYTES,
                GiFieldLayout.CASCADE_COUNT,
                GiFieldLayout.CELLS_PER_AXIS,
                GiFieldLayout.MIP_LEVEL_COUNT,
                2,
                4,
                8,
                GiFieldLayout.FIELD_BYTES_PER_CELL,
                GiFieldLayout.COVERAGE_BYTES_PER_CELL,
                1,
                STATUS_STALE,
                STATUS_BUSY,
                STATUS_CAPTURE_CONSUMED,
                0
        };
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment layout = arena.allocate(NATIVE_LAYOUT_BYTES, Long.BYTES);
            layout.fill((byte) 0);
            if (MetalNativeBridge.metallum_gi_field_layout_v1(layout, layout.byteSize()) != STATUS_OK) {
                throw new IllegalStateException("Native G1 field layout query failed");
            }
            for (int index = 0; index < expected.length; index++) {
                int actual = layout.get(LE_INT, (long) index * Integer.BYTES);
                if (actual != expected[index]) {
                    throw new IllegalStateException("Native G1 field layout mismatch at word " + index
                            + ": expected " + expected[index] + ", got " + actual);
                }
            }
        }
    }

    public static @Nullable GiFieldGpuResources create(
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
        validateNativeAbi();
        MemorySegment handle = MetalNativeBridge.metallum_gi_field_create_context_v1(
                device, commandQueue, worldGeneration
        );
        return MetalNativeBridge.isNullHandle(handle)
                ? null
                : new GiFieldGpuResources(handle, deferredRelease);
    }

    public int uploadOnce(final GiFieldSnapshot snapshot) {
        assertUsable();
        Objects.requireNonNull(snapshot, "snapshot");
        int[] origins = snapshot.origins();
        short[] field = snapshot.packedField();
        byte[] coverage = snapshot.coverage();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeOrigins = arena.allocate((long) origins.length * Integer.BYTES, Integer.BYTES);
            MemorySegment nativeField = arena.allocate((long) field.length * Short.BYTES, Short.BYTES);
            MemorySegment nativeCoverage = arena.allocate(coverage.length, Byte.BYTES);
            MemorySegment.copy(MemorySegment.ofArray(origins), ValueLayout.JAVA_INT, 0L,
                    nativeOrigins, ValueLayout.JAVA_INT, 0L, origins.length);
            MemorySegment.copy(MemorySegment.ofArray(field), ValueLayout.JAVA_SHORT, 0L,
                    nativeField, ValueLayout.JAVA_SHORT, 0L, field.length);
            MemorySegment.copy(MemorySegment.ofArray(coverage), 0L,
                    nativeCoverage, 0L, coverage.length);
            return MetalNativeBridge.metallum_gi_field_upload_once_v1(
                    this.context,
                    snapshot.worldGeneration(),
                    nativeOrigins,
                    nativeOrigins.byteSize(),
                    nativeField,
                    nativeField.byteSize(),
                    nativeCoverage,
                    nativeCoverage.byteSize()
            );
        }
    }

    public boolean awaitReady(final long timeoutMillis) {
        assertUsable();
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("G1 await timeout must be positive");
        }
        return MetalNativeBridge.metallum_gi_field_await_ready_v1(this.context, timeoutMillis) == STATUS_OK;
    }

    public int reset(final long newWorldGeneration) {
        assertUsable();
        if (newWorldGeneration <= 0L) {
            throw new IllegalArgumentException("G1 reset generation must be positive");
        }
        return MetalNativeBridge.metallum_gi_field_reset_v1(this.context, newWorldGeneration);
    }

    /** Diagnostic-only and accepted at most once between generation resets. */
    public @Nullable Capture captureOnce(final int cascade, final int mip) {
        assertUsable();
        int edge = GiFieldLayout.mipEdge(mip);
        int cells = Math.multiplyExact(Math.multiplyExact(edge, edge), edge);
        short[] field = new short[cells * GiFieldLayout.FIELD_CHANNELS];
        byte[] coverage = new byte[cells];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeField = arena.allocate((long) field.length * Short.BYTES, Short.BYTES);
            MemorySegment nativeCoverage = arena.allocate(coverage.length, Byte.BYTES);
            int status = MetalNativeBridge.metallum_gi_field_capture_mip_once_v1(
                    this.context,
                    cascade,
                    mip,
                    nativeField,
                    nativeField.byteSize(),
                    nativeCoverage,
                    nativeCoverage.byteSize()
            );
            if (status != STATUS_OK) {
                return null;
            }
            MemorySegment.copy(nativeField, ValueLayout.JAVA_SHORT, 0L,
                    MemorySegment.ofArray(field), ValueLayout.JAVA_SHORT, 0L, field.length);
            MemorySegment.copy(nativeCoverage, 0L, MemorySegment.ofArray(coverage), 0L, coverage.length);
            return new Capture(cascade, mip, edge, field, coverage);
        }
    }

    public Stats stats() {
        assertUsable();
        this.statsPacket.fill((byte) 0);
        if (MetalNativeBridge.metallum_gi_field_get_stats_v1(
                this.context, this.statsPacket, this.statsPacket.byteSize()) != STATUS_OK) {
            throw new IllegalStateException("Native G1 field stats query failed");
        }
        return new Stats(
                this.statsPacket.get(LE_INT, 0L) == 1,
                this.statsPacket.get(LE_INT, 4L) == 1,
                this.statsPacket.get(LE_LONG, 8L),
                this.statsPacket.get(LE_LONG, 16L),
                this.statsPacket.get(LE_LONG, 24L),
                this.statsPacket.get(LE_LONG, 32L),
                this.statsPacket.get(LE_LONG, 40L),
                this.statsPacket.get(LE_LONG, 48L),
                this.statsPacket.get(LE_LONG, 56L),
                this.statsPacket.get(LE_INT, 64L),
                this.statsPacket.get(LE_INT, 68L),
                this.statsPacket.get(LE_INT, 72L)
        );
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

    private void assertUsable() {
        assertOwnerThread();
        if (this.closed || MetalNativeBridge.isNullHandle(this.context)) {
            throw new IllegalStateException("G1 field resources are closed");
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G1 Metal resources are confined to their render thread");
        }
    }
}
