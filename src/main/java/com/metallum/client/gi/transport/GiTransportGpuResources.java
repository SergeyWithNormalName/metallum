package com.metallum.client.gi.transport;

import com.metallum.client.gi.debug.GiTransportDebugSettings;
import com.metallum.client.gi.semantic.GiSemanticTransportFieldView;
import com.metallum.client.gi.semantic.GiSemanticValidity;
import com.metallum.client.gi.source.GiDirectSourceCoordinator;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Consumer;

/** Render-thread owner of the one-shot private G4 bounce/SH field and fixed staging packets. */
public final class GiTransportGpuResources implements AutoCloseable {
    public static final int STATUS_OK = 1;
    public static final int STATUS_INVALID = -1;
    public static final int STATUS_BUSY = -2;
    public static final int STATUS_STALE = -3;
    public static final int STATUS_CAPTURE_CONSUMED = -4;
    public static final int STATUS_WRONG_THREAD = -5;
    public static final int STATUS_REJECTED = -6;

    /** Unforgeable Java capability for a read-only native consumer of this field. */
    public static final class ReadToken {
        private final GiTransportGpuResources owner;

        private ReadToken(final GiTransportGpuResources owner) {
            this.owner = owner;
        }

        public MemorySegment nativeContext() {
            return this.owner.nativeContextFor(this);
        }
    }

    private static final ValueLayout.OfShort LE_SHORT_UNALIGNED =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_FLOAT =
            ValueLayout.JAVA_FLOAT.withOrder(ByteOrder.LITTLE_ENDIAN);

    public record PreparedFrozen(
            GiTransportEpoch epoch,
            GiSemanticTransportFieldView.CopyResult semanticCopy,
            int validSurfaceCount,
            int unavailableCellCount
    ) {
        public PreparedFrozen {
            Objects.requireNonNull(epoch, "epoch");
            Objects.requireNonNull(semanticCopy, "semanticCopy");
            if (validSurfaceCount < 0 || unavailableCellCount < 0
                    || validSurfaceCount > GiTransportLayout.CELL_COUNT
                    || unavailableCellCount > GiTransportLayout.CELL_COUNT) {
                throw new IllegalArgumentException("Invalid G4 frozen preparation counts");
            }
        }
    }

    public record Stats(
            boolean ready,
            boolean buildInFlight,
            int shaderLibraryMode,
            long worldGeneration,
            long clipmapGeneration,
            long paletteGeneration,
            long contentGeneration,
            long staticSourceEpoch,
            long environmentEpoch,
            long persistentBytes,
            long stagingBytes,
            long readbackBytes,
            long transportDispatches,
            long validSurfaceCount,
            long unknownCellCount,
            long staleRejects,
            long busyRejects,
            long rejectedCount,
            long sourceStamp,
            long fullVolumeBuilds,
            long accountedBytes,
            int nearOriginX,
            int nearOriginY,
            int nearOriginZ,
            int cellCount,
            int iterationCount,
            int maximumDistance
    ) {
        public Stats {
            if (shaderLibraryMode < 0 || shaderLibraryMode > 2
                    || persistentBytes < 0L || stagingBytes < 0L || readbackBytes < 0L
                    || transportDispatches < 0L || validSurfaceCount < 0L
                    || unknownCellCount < 0L || staleRejects < 0L || busyRejects < 0L
                    || rejectedCount < 0L || fullVolumeBuilds < 0L || accountedBytes < 0L) {
                throw new IllegalArgumentException("Invalid native G4 transport statistics");
            }
            long expectedAccounted = Math.addExact(
                    persistentBytes, Math.addExact(stagingBytes, readbackBytes)
            );
            if (accountedBytes != expectedAccounted) {
                throw new IllegalArgumentException("Native G4 accounted memory is inconsistent");
            }
            if (ready && (buildInFlight || shaderLibraryMode < 1 || sourceStamp == 0L
                    || worldGeneration <= 0L || clipmapGeneration <= 0L
                    || paletteGeneration <= 0L || contentGeneration <= 0L
                    || staticSourceEpoch <= 0L || environmentEpoch <= 0L
                    || cellCount != GiTransportLayout.CELL_COUNT
                    || iterationCount != GiTransportLayout.ITERATION_COUNT
                    || maximumDistance != GiTransportLayout.MAXIMUM_DISTANCE)) {
                throw new IllegalArgumentException("Ready native G4 statistics violate the frozen contract");
            }
        }
    }

    /** Test/debug-only full-volume capture. Array accessors return defensive copies. */
    public record Capture(
            short[] bounceRgbaFloat16,
            short[] shRedRgbaFloat16,
            short[] shGreenRgbaFloat16,
            short[] shBlueRgbaFloat16,
            byte[] confidenceUnorm8,
            byte[] validity
    ) {
        public Capture {
            bounceRgbaFloat16 = requireRgba(bounceRgbaFloat16, "bounce").clone();
            shRedRgbaFloat16 = requireRgba(shRedRgbaFloat16, "SH red").clone();
            shGreenRgbaFloat16 = requireRgba(shGreenRgbaFloat16, "SH green").clone();
            shBlueRgbaFloat16 = requireRgba(shBlueRgbaFloat16, "SH blue").clone();
            if (confidenceUnorm8 == null
                    || confidenceUnorm8.length != GiTransportLayout.CAPTURE_CONFIDENCE_BYTES) {
                throw new IllegalArgumentException("Invalid G4 confidence capture");
            }
            confidenceUnorm8 = confidenceUnorm8.clone();
            if (validity == null || validity.length != GiTransportLayout.CELL_COUNT) {
                throw new IllegalArgumentException("Invalid G4 validity capture");
            }
            for (byte value : validity) {
                if (Byte.toUnsignedInt(value) > GiSemanticValidity.KNOWN_FALLBACK.abiId()) {
                    throw new IllegalArgumentException("Unknown G4 validity ABI in capture");
                }
            }
            validity = validity.clone();
        }

        @Override public short[] bounceRgbaFloat16() { return this.bounceRgbaFloat16.clone(); }
        @Override public short[] shRedRgbaFloat16() { return this.shRedRgbaFloat16.clone(); }
        @Override public short[] shGreenRgbaFloat16() { return this.shGreenRgbaFloat16.clone(); }
        @Override public short[] shBlueRgbaFloat16() { return this.shBlueRgbaFloat16.clone(); }
        @Override public byte[] confidenceUnorm8() { return this.confidenceUnorm8.clone(); }
        @Override public byte[] validity() { return this.validity.clone(); }

        private static short[] requireRgba(final short[] values, final String label) {
            if (values == null
                    || values.length != GiTransportLayout.CAPTURE_RGBA_BYTES / Short.BYTES) {
                throw new IllegalArgumentException("Invalid G4 " + label + " capture");
            }
            return values;
        }
    }

    private final Thread ownerThread;
    private final Consumer<MemorySegment> deferredRelease;
    private final Arena arena;
    private final MemorySegment header;
    private final MemorySegment cells;
    private final MemorySegment stats;
    private final MemorySegment debugBounce;
    private final MemorySegment debugShRed;
    private final MemorySegment debugShGreen;
    private final MemorySegment debugShBlue;
    private final MemorySegment debugConfidence;
    private final ReadToken readToken;
    private MemorySegment context;
    @Nullable private GiTransportEpoch preparedEpoch;
    private boolean debugCaptureStarted;
    private boolean debugCaptureDelivered;

    private GiTransportGpuResources(
            final MemorySegment context,
            final Consumer<MemorySegment> deferredRelease,
            final Arena arena
    ) {
        this.ownerThread = Thread.currentThread();
        this.context = context;
        this.readToken = new ReadToken(this);
        this.deferredRelease = deferredRelease;
        this.arena = arena;
        this.header = arena.allocate(GiTransportLayout.HEADER_BYTES, Long.BYTES);
        this.cells = arena.allocate(GiTransportLayout.CELLS_BYTES, Long.BYTES);
        this.stats = arena.allocate(GiTransportLayout.STATS_BYTES, Long.BYTES);
        boolean debugCapture = GiTransportDebugSettings.isEnabled()
                && !GiTransportRuntime.isBenchmarkActive();
        this.debugBounce = debugCapture
                ? arena.allocate(GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES)
                : MemorySegment.NULL;
        this.debugShRed = debugCapture
                ? arena.allocate(GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES)
                : MemorySegment.NULL;
        this.debugShGreen = debugCapture
                ? arena.allocate(GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES)
                : MemorySegment.NULL;
        this.debugShBlue = debugCapture
                ? arena.allocate(GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES)
                : MemorySegment.NULL;
        this.debugConfidence = debugCapture
                ? arena.allocate(GiTransportLayout.CAPTURE_CONFIDENCE_BYTES, Byte.BYTES)
                : MemorySegment.NULL;
    }

    ReadToken readToken() {
        assertUsable();
        return this.readToken;
    }

    private MemorySegment nativeContextFor(final ReadToken token) {
        assertOwnerThread();
        return token == this.readToken && !MetalNativeBridge.isNullHandle(this.context)
                ? this.context : MemorySegment.NULL;
    }

    public static void validateNativeAbi() {
        if (MetalNativeBridge.metallum_gi_transport_abi_version_v1()
                != GiTransportLayout.ABI_VERSION) {
            throw new IllegalStateException("Native G4 transport ABI version mismatch");
        }
        int[] expected = {
                GiTransportLayout.ABI_VERSION,
                GiTransportLayout.LAYOUT_BYTES,
                GiTransportLayout.HEADER_BYTES,
                GiTransportLayout.CELL_BYTES,
                GiTransportLayout.STATS_BYTES,
                GiTransportLayout.EDGE,
                GiTransportLayout.CELL_COUNT,
                GiTransportLayout.ITERATION_COUNT,
                GiTransportLayout.MAXIMUM_DISTANCE,
                GiTransportLayout.RGBA16_FLOAT_PIXEL_FORMAT,
                GiTransportLayout.R8_UNORM_PIXEL_FORMAT,
                STATUS_STALE,
                STATUS_BUSY,
                STATUS_CAPTURE_CONSUMED,
                STATUS_WRONG_THREAD,
                STATUS_REJECTED,
                GiTransportLayout.CAPTURE_RGBA_BYTES,
                GiTransportLayout.CAPTURE_CONFIDENCE_BYTES
        };
        try (Arena probe = Arena.ofConfined()) {
            MemorySegment layout = probe.allocate(GiTransportLayout.LAYOUT_BYTES, Long.BYTES);
            layout.fill((byte) 0x55);
            int status = MetalNativeBridge.metallum_gi_transport_layout_v1(
                    layout, layout.byteSize()
            );
            if (status != STATUS_OK) {
                throw new IllegalStateException("Native G4 transport layout query failed: " + status);
            }
            for (int index = 0; index < expected.length; index++) {
                int actual = layout.get(LE_INT, (long) index * Integer.BYTES);
                if (actual != expected[index]) {
                    throw new IllegalStateException("Native G4 layout mismatch at word " + index
                            + ": expected " + expected[index] + ", got " + actual);
                }
            }
            for (int index = expected.length;
                 index < GiTransportLayout.LAYOUT_BYTES / Integer.BYTES; index++) {
                if (layout.get(LE_INT, (long) index * Integer.BYTES) != 0) {
                    throw new IllegalStateException(
                            "Native G4 reserved layout word is non-zero: " + index
                    );
                }
            }
        }
    }

    public static @Nullable GiTransportGpuResources create(
            final MemorySegment device,
            final MemorySegment commandQueue,
            final GiDirectSourceCoordinator.TelemetrySource telemetrySource,
            final Consumer<MemorySegment> deferredRelease
    ) {
        Objects.requireNonNull(telemetrySource, "telemetrySource");
        Objects.requireNonNull(deferredRelease, "deferredRelease");
        if (MetalNativeBridge.isNullHandle(device) || MetalNativeBridge.isNullHandle(commandQueue)) {
            return null;
        }
        validateNativeAbi();
        // G4 resources are precreated at device admission, before any world is eligible.
        // The immutable world/epoch tuple is bound and validated by the first frozen header.
        MemorySegment context = MetalNativeBridge.metallum_gi_transport_create_context_v1(
                device, commandQueue, 1L
        );
        if (MetalNativeBridge.isNullHandle(context)) {
            return null;
        }
        int attachStatus = telemetrySource.attachTransportTelemetry(context);
        if (attachStatus != STATUS_OK) {
            MetalNativeBridge.metallum_gi_transport_release_context_v1(context);
            throw new IllegalStateException(
                    "Failed to attach G4 telemetry to its G3 owner: " + attachStatus
            );
        }
        Arena arena = Arena.ofConfined();
        try {
            return new GiTransportGpuResources(context, deferredRelease, arena);
        } catch (RuntimeException failure) {
            arena.close();
            MetalNativeBridge.metallum_gi_transport_release_context_v1(context);
            throw failure;
        }
    }

    public PreparedFrozen prepare(
            final GiTransportEpoch epoch,
            final GiSemanticTransportFieldView field
    ) {
        assertUsable();
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(field, "field");
        if (!epoch.matches(field)) {
            throw new IllegalArgumentException("G4 transport input does not match its frozen epoch");
        }
        this.header.fill((byte) 0);
        GiSemanticTransportFieldView.CopyResult copy = field.copyNearCascade(this.cells);
        int validSurfaces = 0;
        int unavailable = 0;
        for (int cell = 0; cell < GiTransportLayout.CELL_COUNT; cell++) {
            long offset = (long) cell * GiTransportLayout.CELL_BYTES;
            int validity = Byte.toUnsignedInt(this.cells.get(ValueLayout.JAVA_BYTE, offset + 14L));
            if (validity == GiSemanticValidity.UNKNOWN.abiId()
                    || validity == GiSemanticValidity.KNOWN_FALLBACK.abiId()) {
                unavailable++;
            }
            boolean hasFace = false;
            for (int face = 0; face < 6; face++) {
                hasFace |= this.cells.get(ValueLayout.JAVA_BYTE, offset + 8L + face) != 0;
            }
            if (validity == GiSemanticValidity.KNOWN_CONTENT.abiId()
                    && Short.toUnsignedInt(this.cells.get(LE_SHORT_UNALIGNED, offset + 6L)) != 0
                    && this.cells.get(ValueLayout.JAVA_BYTE, offset + 15L) != 0
                    && hasFace) {
                validSurfaces++;
            }
        }

        this.header.set(LE_INT, 0L, GiTransportLayout.ABI_VERSION);
        this.header.set(LE_INT, 4L, GiTransportLayout.HEADER_BYTES);
        this.header.set(LE_LONG, 8L, epoch.worldGeneration());
        this.header.set(LE_LONG, 16L, epoch.clipmapGeneration());
        this.header.set(LE_LONG, 24L, epoch.paletteGeneration());
        this.header.set(LE_LONG, 32L, epoch.contentGeneration());
        this.header.set(LE_LONG, 40L, epoch.staticSourceEpoch());
        this.header.set(LE_LONG, 48L, epoch.environmentEpoch());
        this.header.set(LE_INT, 56L, epoch.nearOriginX());
        this.header.set(LE_INT, 60L, epoch.nearOriginY());
        this.header.set(LE_INT, 64L, epoch.nearOriginZ());
        this.header.set(LE_INT, 68L, GiTransportLayout.CELL_COUNT);
        this.header.set(LE_INT, 72L, GiTransportLayout.ITERATION_COUNT);
        this.header.set(LE_INT, 76L, GiTransportLayout.MAXIMUM_DISTANCE);
        this.header.set(LE_INT, 80L, validSurfaces);
        this.header.set(LE_INT, 84L, unavailable);
        this.header.set(LE_FLOAT, 88L, (float) GiTransportLayout.FORM_WEIGHT_NORMALIZATION);
        this.header.set(LE_FLOAT, 92L, GiTransportLayout.FP16_ABSOLUTE_TOLERANCE);
        this.header.set(LE_FLOAT, 96L, GiTransportLayout.FP16_RELATIVE_TOLERANCE);
        this.header.set(LE_INT, 100L, GiTransportLayout.FLAGS_NONE);
        this.header.set(LE_LONG, 104L, epoch.sourceStamp());
        this.preparedEpoch = epoch;
        return new PreparedFrozen(epoch, copy, validSurfaces, unavailable);
    }

    public int encode(
            final GiDirectSourceCoordinator.TransportSource source,
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final PreparedFrozen prepared
    ) {
        assertUsable();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(prepared, "prepared");
        if (this.preparedEpoch == null || !this.preparedEpoch.equals(prepared.epoch())
                || source.sourceStamp() != prepared.epoch().sourceStamp()) {
            throw new IllegalArgumentException("G4 encoded source does not match prepared frozen input");
        }
        return MetalNativeBridge.metallum_gi_transport_encode_frozen_v1(
                this.context, source.directContext(), commandBuffer, fence,
                this.header, this.cells
        );
    }

    public boolean awaitReady(final long timeoutMillis) {
        assertUsable();
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("G4 await timeout must be positive");
        }
        return MetalNativeBridge.metallum_gi_transport_await_ready_v1(
                this.context, timeoutMillis
        ) == STATUS_OK;
    }

    public Stats stats() {
        assertUsable();
        this.stats.fill((byte) 0);
        int status = MetalNativeBridge.metallum_gi_transport_get_stats_v1(
                this.context, this.stats, this.stats.byteSize()
        );
        if (status != STATUS_OK) {
            throw new IllegalStateException("Native G4 transport stats query failed: " + status);
        }
        return new Stats(
                this.stats.get(LE_INT, 0L) == 1,
                this.stats.get(LE_INT, 4L) == 1,
                this.stats.get(LE_INT, 8L),
                this.stats.get(LE_LONG, 16L), this.stats.get(LE_LONG, 24L),
                this.stats.get(LE_LONG, 32L), this.stats.get(LE_LONG, 40L),
                this.stats.get(LE_LONG, 48L), this.stats.get(LE_LONG, 56L),
                this.stats.get(LE_LONG, 64L), this.stats.get(LE_LONG, 72L),
                this.stats.get(LE_LONG, 80L), this.stats.get(LE_LONG, 88L),
                this.stats.get(LE_LONG, 96L), this.stats.get(LE_LONG, 104L),
                this.stats.get(LE_LONG, 112L), this.stats.get(LE_LONG, 120L),
                this.stats.get(LE_LONG, 128L), this.stats.get(LE_LONG, 136L),
                this.stats.get(LE_LONG, 144L), this.stats.get(LE_LONG, 152L),
                this.stats.get(LE_INT, 160L), this.stats.get(LE_INT, 164L),
                this.stats.get(LE_INT, 168L), this.stats.get(LE_INT, 172L),
                this.stats.get(LE_INT, 176L), this.stats.get(LE_INT, 180L)
        );
    }

    /** Publishes one fail-closed stale transition without encoding or rebuilding the field. */
    public int reportStale() {
        assertUsable();
        return MetalNativeBridge.metallum_gi_transport_report_stale_v1(this.context);
    }

    /** Allocates compact readback destinations only for the explicit one-shot debug capture. */
    public @Nullable Capture captureVolumeOnce() {
        assertUsable();
        try (Arena captureArena = Arena.ofConfined()) {
            MemorySegment bounce = captureArena.allocate(
                    GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES
            );
            MemorySegment shRed = captureArena.allocate(
                    GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES
            );
            MemorySegment shGreen = captureArena.allocate(
                    GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES
            );
            MemorySegment shBlue = captureArena.allocate(
                    GiTransportLayout.CAPTURE_RGBA_BYTES, Long.BYTES
            );
            MemorySegment confidence = captureArena.allocate(
                    GiTransportLayout.CAPTURE_CONFIDENCE_BYTES, Byte.BYTES
            );
            int status = MetalNativeBridge.metallum_gi_transport_capture_volume_once_v1(
                    this.context, bounce, shRed, shGreen, shBlue, confidence
            );
            if (status != STATUS_OK) {
                return null;
            }
            return new Capture(
                    readShorts(bounce), readShorts(shRed), readShorts(shGreen), readShorts(shBlue),
                    readBytes(confidence), readValidity(this.cells)
            );
        }
    }

    /** Polls a one-shot asynchronous debug readback; it never waits for GPU completion. */
    public @Nullable Capture pollDebugCapture() {
        assertUsable();
        if (debugCaptureDelivered || debugBounce.equals(MemorySegment.NULL)) {
            return null;
        }
        if (!debugCaptureStarted) {
            int status = MetalNativeBridge.metallum_gi_transport_begin_debug_capture_v1(
                    this.context
            );
            if (status == STATUS_BUSY) {
                return null;
            }
            if (status != STATUS_OK) {
                throw new IllegalStateException("Failed to start G4 debug capture: " + status);
            }
            debugCaptureStarted = true;
            return null;
        }
        int status = MetalNativeBridge.metallum_gi_transport_poll_debug_capture_v1(
                this.context, this.debugBounce, this.debugShRed, this.debugShGreen,
                this.debugShBlue, this.debugConfidence
        );
        if (status == STATUS_BUSY) {
            return null;
        }
        if (status != STATUS_OK) {
            throw new IllegalStateException("Failed to finish G4 debug capture: " + status);
        }
        debugCaptureDelivered = true;
        return new Capture(
                readShorts(this.debugBounce), readShorts(this.debugShRed),
                readShorts(this.debugShGreen), readShorts(this.debugShBlue),
                readBytes(this.debugConfidence), readValidity(this.cells)
        );
    }

    @Override
    public void close() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return;
        }
        assertOwnerThread();
        MemorySegment stale = this.context;
        this.context = MemorySegment.NULL;
        this.preparedEpoch = null;
        this.deferredRelease.accept(stale);
        this.arena.close();
    }

    private static short[] readShorts(final MemorySegment source) {
        short[] output = new short[Math.toIntExact(source.byteSize() / Short.BYTES)];
        MemorySegment.copy(source, 0L, MemorySegment.ofArray(output), 0L, source.byteSize());
        return output;
    }

    private static byte[] readBytes(final MemorySegment source) {
        byte[] output = new byte[Math.toIntExact(source.byteSize())];
        MemorySegment.copy(source, 0L, MemorySegment.ofArray(output), 0L, source.byteSize());
        return output;
    }

    private static byte[] readValidity(final MemorySegment source) {
        byte[] output = new byte[GiTransportLayout.CELL_COUNT];
        for (int cell = 0; cell < output.length; cell++) {
            output[cell] = source.get(
                    ValueLayout.JAVA_BYTE,
                    (long) cell * GiTransportLayout.CELL_BYTES + 14L
            );
        }
        return output;
    }

    private void assertUsable() {
        assertOwnerThread();
        if (MetalNativeBridge.isNullHandle(this.context)) {
            throw new IllegalStateException("G4 transport resources are closed");
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G4 transport resources are render-thread confined");
        }
    }
}
