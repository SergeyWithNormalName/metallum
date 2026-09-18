package com.metallum.client.gi.live;

import com.metallum.client.gi.field.GiFieldLayout;
import com.metallum.client.gi.semantic.GiSemanticTransportFieldView;
import com.metallum.client.gi.semantic.GiSemanticValidity;
import com.metallum.client.gi.source.GiDirectSourceCoordinator;
import com.metallum.client.gi.source.GiDirectSourceEpoch;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Consumer;

/** Render-thread owner of the fixed G6 live atlas and its preallocated ABI packets. */
public final class GiLiveGpuResources implements AutoCloseable {
    private static final ValueLayout.OfShort LE_SHORT_UNALIGNED =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_FLOAT =
            ValueLayout.JAVA_FLOAT.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    /**
     * Explicit opt-in for a bounded, benchmark-only asynchronous atlas probe.  The permanent
     * G6 budget deliberately excludes this diagnostic packet: ordinary clients do not allocate
     * it, and it never participates in the production telemetry/accounting path.
     */
    private static final boolean DEBUG_PROBE_ENABLED = "1".equals(
            System.getenv("METALLUM_GI_G6_DEBUG_PROBE")
    );

    /**
     * Reused render-thread telemetry view. The object identity is stable for the resource
     * lifetime; callers must consume primitive values immediately and must not retain it as a
     * historical snapshot.
     */
    public static final class Stats {
        private int readyMask;
        private boolean buildInFlight;
        private int shaderLibraryMode;
        private int receiverVisibleMask;
        private int lastBindVisibleMask;
        private long worldGeneration;
        private long clipmapGeneration;
        private long contentGeneration;
        private long staticSourceEpoch;
        private long dynamicSourceEpoch;
        private long environmentEpoch;
        private long fieldGeneration;
        private long sourceTick;
        private long allocatedBytes;
        private long residentBytes;
        private long stagingBytes;
        private long transportDispatches;
        private long cascadeBuilds0;
        private long cascadeBuilds1;
        private long cascadeBuilds2;
        private long invalidations;
        private long staleRejects;
        private long busyRejects;
        private long rejectedCount;
        private long bindCount;
        private long zeroBindings;
        private long fieldBindings;
        private long resetWorld;
        private long resetTeleport;
        private long resetScroll;
        private long resetSource;
        private long resetExplicit;
        private long resetDevice;

        private Stats() {
        }

        private void validate() {
            if ((readyMask & ~GiLiveLayout.READY_MASK_ALL) != 0
                    || (receiverVisibleMask & ~GiLiveLayout.READY_MASK_ALL) != 0
                    || (lastBindVisibleMask & ~GiLiveLayout.READY_MASK_ALL) != 0
                    || shaderLibraryMode < 1 || shaderLibraryMode > 2
                    || worldGeneration < 0L || clipmapGeneration < 0L
                    || contentGeneration < 0L || staticSourceEpoch < 0L
                    || dynamicSourceEpoch < 0L || environmentEpoch < 0L
                    || fieldGeneration < 0L || sourceTick < 0L
                    || allocatedBytes < 0L || residentBytes < 0L || stagingBytes < 0L
                    || transportDispatches < 0L || cascadeBuilds0 < 0L
                    || cascadeBuilds1 < 0L || cascadeBuilds2 < 0L
                    || invalidations < 0L || staleRejects < 0L || busyRejects < 0L
                    || rejectedCount < 0L || bindCount < 0L || zeroBindings < 0L
                    || fieldBindings < 0L || resetWorld < 0L || resetTeleport < 0L
                    || resetScroll < 0L || resetSource < 0L || resetExplicit < 0L
                    || resetDevice < 0L) {
                throw new IllegalArgumentException("Invalid native G6 live statistics");
            }
            long expectedAllocated = Math.addExact(
                    Math.addExact(residentBytes, stagingBytes),
                    GiLiveLayout.LIFETIME_OVERHEAD_BYTES
            );
            if (allocatedBytes != expectedAllocated
                    || transportDispatches != Math.addExact(
                            Math.addExact(cascadeBuilds0, cascadeBuilds1), cascadeBuilds2
                    )
                    || bindCount != Math.addExact(zeroBindings, fieldBindings)
                    || invalidations != Math.addExact(
                            Math.addExact(Math.addExact(resetWorld, resetTeleport),
                                    Math.addExact(resetScroll, resetSource)),
                            Math.addExact(resetExplicit, resetDevice)
                    )) {
                throw new IllegalArgumentException("Native G6 live statistics are inconsistent");
            }
            if (readyMask != 0 && (worldGeneration <= 0L || clipmapGeneration <= 0L
                    || contentGeneration <= 0L || staticSourceEpoch <= 0L
                    || dynamicSourceEpoch <= 0L || environmentEpoch <= 0L
                    || fieldGeneration <= 0L)) {
                throw new IllegalArgumentException("Ready native G6 statistics lack an epoch");
            }
        }

        public int readyMask() { return this.readyMask; }
        public boolean buildInFlight() { return this.buildInFlight; }
        public int shaderLibraryMode() { return this.shaderLibraryMode; }
        public int receiverVisibleMask() { return this.receiverVisibleMask; }
        public int lastBindVisibleMask() { return this.lastBindVisibleMask; }
        public long worldGeneration() { return this.worldGeneration; }
        public long clipmapGeneration() { return this.clipmapGeneration; }
        public long contentGeneration() { return this.contentGeneration; }
        public long staticSourceEpoch() { return this.staticSourceEpoch; }
        public long dynamicSourceEpoch() { return this.dynamicSourceEpoch; }
        public long environmentEpoch() { return this.environmentEpoch; }
        public long fieldGeneration() { return this.fieldGeneration; }
        public long sourceTick() { return this.sourceTick; }
        public long allocatedBytes() { return this.allocatedBytes; }
        public long residentBytes() { return this.residentBytes; }
        public long stagingBytes() { return this.stagingBytes; }
        public long transportDispatches() { return this.transportDispatches; }
        public long cascadeBuilds0() { return this.cascadeBuilds0; }
        public long cascadeBuilds1() { return this.cascadeBuilds1; }
        public long cascadeBuilds2() { return this.cascadeBuilds2; }
        public long invalidations() { return this.invalidations; }
        public long staleRejects() { return this.staleRejects; }
        public long busyRejects() { return this.busyRejects; }
        public long rejectedCount() { return this.rejectedCount; }
        public long bindCount() { return this.bindCount; }
        public long zeroBindings() { return this.zeroBindings; }
        public long fieldBindings() { return this.fieldBindings; }
        public long resetWorld() { return this.resetWorld; }
        public long resetTeleport() { return this.resetTeleport; }
        public long resetScroll() { return this.resetScroll; }
        public long resetSource() { return this.resetSource; }
        public long resetExplicit() { return this.resetExplicit; }
        public long resetDevice() { return this.resetDevice; }

        public boolean allCascadesReady() {
            return this.readyMask == GiLiveLayout.READY_MASK_ALL;
        }
    }

    /** One immutable texel returned by the explicit G6 visual-probe diagnostic. */
    public record DebugProbeSample(
            int cascade,
            int worldX,
            int worldY,
            int worldZ,
            int localX,
            int localY,
            int localZ,
            int flags,
            float[] shCoefficients,
            int confidence,
            int surfaceCoverage
    ) {
        public DebugProbeSample {
            if (cascade < 0 || cascade >= GiLiveLayout.CASCADE_COUNT
                    || localX < 0 || localX >= GiLiveLayout.EDGE
                    || localY < 0 || localY >= GiLiveLayout.EDGE
                    || localZ < 0 || localZ >= GiLiveLayout.EDGE
                    || shCoefficients == null || shCoefficients.length != 12
                    || confidence < 0 || confidence > 255
                    || surfaceCoverage < 0 || surfaceCoverage > 255) {
                throw new IllegalArgumentException("Invalid G6 debug-probe sample");
            }
            shCoefficients = shCoefficients.clone();
            for (float coefficient : shCoefficients) {
                if (!Float.isFinite(coefficient)) {
                    throw new IllegalArgumentException("Non-finite G6 debug-probe SH coefficient");
                }
            }
        }

        @Override
        public float[] shCoefficients() {
            return this.shCoefficients.clone();
        }

        public float shRed0() { return this.shCoefficients[0]; }
        public float shRed1() { return this.shCoefficients[1]; }
        public float shRed2() { return this.shCoefficients[2]; }
        public float shRed3() { return this.shCoefficients[3]; }
        public float shGreen0() { return this.shCoefficients[4]; }
        public float shGreen1() { return this.shCoefficients[5]; }
        public float shGreen2() { return this.shCoefficients[6]; }
        public float shGreen3() { return this.shCoefficients[7]; }
        public float shBlue0() { return this.shCoefficients[8]; }
        public float shBlue1() { return this.shCoefficients[9]; }
        public float shBlue2() { return this.shCoefficients[10]; }
        public float shBlue3() { return this.shCoefficients[11]; }
        public int coverage() { return this.surfaceCoverage; }
    }

    /**
     * Immutable result of the one-shot diagnostic.  All identity values are copied from native's
     * snapshot and compared with the exact live epoch before this receipt is exposed.
     */
    public record DebugProbeCapture(
            long worldGeneration,
            long clipmapGeneration,
            long paletteGeneration,
            long contentGeneration,
            long staticSourceEpoch,
            long dynamicSourceEpoch,
            long environmentEpoch,
            long fieldGeneration,
            long sourceTick,
            int[] receiverOrigins,
            int readyMask,
            int receiverVisibleMask,
            int sampleValidMask,
            DebugProbeSample[] samples
    ) {
        public DebugProbeCapture {
            if (worldGeneration <= 0L || clipmapGeneration <= 0L || paletteGeneration <= 0L
                    || contentGeneration <= 0L
                    || staticSourceEpoch <= 0L || dynamicSourceEpoch <= 0L
                    || environmentEpoch <= 0L || fieldGeneration <= 0L || sourceTick < 0L
                    || receiverOrigins == null
                    || receiverOrigins.length != GiLiveLayout.CASCADE_COUNT * 3
                    || (readyMask & ~GiLiveLayout.READY_MASK_ALL) != 0
                    || (receiverVisibleMask & ~GiLiveLayout.READY_MASK_ALL) != 0
                    || (sampleValidMask & ~((1 << GiLiveDebugProbeLayout.MAX_SAMPLES) - 1)) != 0
                    || samples == null || samples.length != GiLiveDebugProbeLayout.MAX_SAMPLES) {
                throw new IllegalArgumentException("Invalid G6 debug-probe capture");
            }
            receiverOrigins = receiverOrigins.clone();
            samples = samples.clone();
            for (DebugProbeSample sample : samples) {
                Objects.requireNonNull(sample, "G6 debug-probe sample");
            }
        }

        @Override
        public int[] receiverOrigins() {
            return this.receiverOrigins.clone();
        }

        @Override
        public DebugProbeSample[] samples() {
            return this.samples.clone();
        }
    }

    private record DebugProbeExpectation(
            GiLiveEpoch epoch,
            long sourceTick,
            int readyMask,
            int receiverMask,
            int[] cascades,
            int[] worldXs,
            int[] worldYs,
            int[] worldZs
    ) {
        private DebugProbeExpectation {
            Objects.requireNonNull(epoch, "epoch");
            if (sourceTick < 0L || readyMask != GiLiveLayout.READY_MASK_ALL
                    || receiverMask != GiLiveLayout.READY_MASK_ALL) {
                throw new IllegalArgumentException("Invalid expected G6 debug-probe identity");
            }
            cascades = requireProbeCoordinates(cascades, "cascades");
            worldXs = requireProbeCoordinates(worldXs, "world X");
            worldYs = requireProbeCoordinates(worldYs, "world Y");
            worldZs = requireProbeCoordinates(worldZs, "world Z");
        }
    }

    private final Thread ownerThread;
    private final Consumer<MemorySegment> deferredRelease;
    private final Arena arena;
    private final MemorySegment[] headers = new MemorySegment[GiLiveLayout.IN_FLIGHT_SLOTS];
    private final MemorySegment[] cells = new MemorySegment[GiLiveLayout.IN_FLIGHT_SLOTS];
    private final long[] preparedCellGeneration = new long[GiLiveLayout.CASCADE_COUNT];
    private final int[] preparedValidSurfaces = new int[GiLiveLayout.CASCADE_COUNT];
    private final int[] preparedUnknownCells = new int[GiLiveLayout.CASCADE_COUNT];
    private final MemorySegment statsPacket;
    /** Null unless the process explicitly requested the benchmark-only debug route. */
    private final MemorySegment debugProbeRequest;
    /** Null unless the process explicitly requested the benchmark-only debug route. */
    private final MemorySegment debugProbeResult;
    private final Stats statsView = new Stats();
    private MemorySegment context;
    @Nullable private GiLiveEpoch activeEpoch;
    private GiLiveLayout.ResetKind activeResetKind = GiLiveLayout.ResetKind.EXPLICIT;
    private long activeSourceTick;
    @Nullable private DebugProbeExpectation debugProbeExpectation;
    private boolean debugProbeStarted;
    private boolean debugProbeDelivered;
    private int debugProbeLastStatus = GiLiveDebugProbeLayout.STATUS_REJECTED;

    private GiLiveGpuResources(
            final MemorySegment context,
            final Consumer<MemorySegment> deferredRelease,
            final Arena arena
    ) {
        this.ownerThread = Thread.currentThread();
        this.context = Objects.requireNonNull(context, "context");
        this.deferredRelease = Objects.requireNonNull(deferredRelease, "deferredRelease");
        this.arena = Objects.requireNonNull(arena, "arena");
        for (int slot = 0; slot < GiLiveLayout.IN_FLIGHT_SLOTS; slot++) {
            this.headers[slot] = arena.allocate(GiLiveLayout.HEADER_BYTES, Long.BYTES);
            this.cells[slot] = arena.allocate(GiLiveLayout.CELLS_BYTES, Long.BYTES);
        }
        this.statsPacket = arena.allocate(GiLiveLayout.STATS_BYTES, Long.BYTES);
        this.debugProbeRequest = DEBUG_PROBE_ENABLED
                ? arena.allocate(GiLiveDebugProbeLayout.REQUEST_BYTES, Long.BYTES)
                : MemorySegment.NULL;
        this.debugProbeResult = DEBUG_PROBE_ENABLED
                ? arena.allocate(GiLiveDebugProbeLayout.RESULT_BYTES, Long.BYTES)
                : MemorySegment.NULL;
    }

    /** Verifies the dylib oracle word-for-word before a live context is admitted. */
    public static void validateNativeAbi() {
        GiLiveLayout.validateJavaLayouts();
        if (MetalNativeBridge.metallum_gi_live_abi_version_v1()
                != GiLiveLayout.ABI_VERSION) {
            throw new IllegalStateException("Native G6 live ABI version mismatch");
        }
        int[] expected = {
                GiLiveLayout.ABI_VERSION,
                GiLiveLayout.LAYOUT_BYTES,
                GiLiveLayout.HEADER_BYTES,
                GiLiveLayout.CELL_BYTES,
                GiLiveLayout.STATS_BYTES,
                GiLiveLayout.PARAMS_BYTES,
                GiLiveLayout.EDGE,
                GiLiveLayout.CELL_COUNT,
                GiLiveLayout.CASCADE_COUNT,
                GiLiveLayout.ATLAS_DEPTH,
                GiLiveLayout.IN_FLIGHT_SLOTS,
                GiLiveLayout.SH_RED_TEXTURE_SLOT,
                GiLiveLayout.SH_GREEN_TEXTURE_SLOT,
                GiLiveLayout.SH_BLUE_TEXTURE_SLOT,
                GiLiveLayout.CONFIDENCE_TEXTURE_SLOT,
                GiLiveLayout.PARAMS_BUFFER_SLOT,
                GiLiveLayout.STATUS_OK,
                GiLiveLayout.STATUS_ZERO_READY,
                GiLiveLayout.STATUS_INVALID,
                GiLiveLayout.STATUS_BUSY,
                GiLiveLayout.STATUS_STALE,
                GiLiveLayout.STATUS_WRONG_THREAD,
                GiLiveLayout.STATUS_REJECTED,
                Math.toIntExact(GiLiveLayout.LIFETIME_OVERHEAD_BYTES)
        };
        try (Arena probe = Arena.ofConfined()) {
            MemorySegment layout = probe.allocate(GiLiveLayout.LAYOUT_BYTES, Long.BYTES);
            layout.fill((byte) 0x55);
            int status = MetalNativeBridge.metallum_gi_live_layout_v1(
                    layout, layout.byteSize()
            );
            if (status != GiLiveLayout.STATUS_OK) {
                throw new IllegalStateException("Native G6 live layout query failed: " + status);
            }
            for (int index = 0; index < expected.length; index++) {
                int actual = layout.get(LE_INT, (long) index * Integer.BYTES);
                if (actual != expected[index]) {
                    throw new IllegalStateException("Native G6 layout mismatch at word " + index
                            + ": expected " + expected[index] + ", got " + actual);
                }
            }
            for (int index = expected.length; index < GiLiveLayout.LAYOUT_WORDS; index++) {
                if (layout.get(LE_INT, (long) index * Integer.BYTES) != 0) {
                    throw new IllegalStateException(
                            "Native G6 reserved layout word is non-zero: " + index
                    );
                }
            }
        }
    }

    /** Validates the separately gated diagnostic ABI without changing the production contract. */
    private static void validateNativeDebugProbeAbi() {
        GiLiveDebugProbeLayout.validateJavaLayouts();
        if (MetalNativeBridge.metallum_gi_live_debug_probe_abi_version_v1()
                != GiLiveDebugProbeLayout.ABI_VERSION) {
            throw new IllegalStateException("Native G6 debug-probe ABI version mismatch");
        }
        try (Arena probe = Arena.ofConfined()) {
            MemorySegment layout = probe.allocate(GiLiveDebugProbeLayout.LAYOUT_BYTES, Long.BYTES);
            layout.fill((byte) 0x55);
            int status = MetalNativeBridge.metallum_gi_live_debug_probe_layout_v1(
                    layout, layout.byteSize()
            );
            if (status != GiLiveDebugProbeLayout.STATUS_OK) {
                throw new IllegalStateException(
                        "Native G6 debug-probe layout query failed: " + status
                );
            }
            int[] expected = {
                    GiLiveDebugProbeLayout.ABI_VERSION,
                    GiLiveDebugProbeLayout.LAYOUT_BYTES,
                    GiLiveDebugProbeLayout.REQUEST_BYTES,
                    GiLiveDebugProbeLayout.RESULT_BYTES,
                    GiLiveDebugProbeLayout.MAX_SAMPLES,
                    GiLiveDebugProbeLayout.STATUS_OK,
                    GiLiveDebugProbeLayout.STATUS_INVALID,
                    GiLiveDebugProbeLayout.STATUS_BUSY,
                    GiLiveDebugProbeLayout.STATUS_STALE,
                    GiLiveDebugProbeLayout.STATUS_WRONG_THREAD,
                    GiLiveDebugProbeLayout.STATUS_REJECTED,
                    GiLiveDebugProbeLayout.READBACK_BYTES
            };
            for (int index = 0; index < expected.length; index++) {
                int actual = layout.get(LE_INT, (long) index * Integer.BYTES);
                if (actual != expected[index]) {
                    throw new IllegalStateException(
                            "Native G6 debug-probe layout mismatch at word " + index
                                    + ": expected " + expected[index] + ", got " + actual
                    );
                }
            }
            for (int index = expected.length;
                 index < GiLiveDebugProbeLayout.LAYOUT_BYTES / Integer.BYTES;
                 index++) {
                if (layout.get(LE_INT, (long) index * Integer.BYTES) != 0) {
                    throw new IllegalStateException(
                            "Native G6 debug-probe reserved layout word is non-zero: " + index
                    );
                }
            }
        }
    }

    /** Precreates the live atlas/PSOs and binds it to the unforgeable G3 owner. */
    public static @Nullable GiLiveGpuResources create(
            final MemorySegment device,
            final MemorySegment commandQueue,
            final GiDirectSourceCoordinator.LiveOwner liveOwner,
            final Consumer<MemorySegment> deferredRelease
    ) {
        Objects.requireNonNull(liveOwner, "liveOwner");
        Objects.requireNonNull(deferredRelease, "deferredRelease");
        if (MetalNativeBridge.isNullHandle(device)
                || MetalNativeBridge.isNullHandle(commandQueue)) {
            return null;
        }
        validateNativeAbi();
        if (DEBUG_PROBE_ENABLED) {
            validateNativeDebugProbeAbi();
        }
        MemorySegment context = liveOwner.createContext(device, commandQueue);
        if (MetalNativeBridge.isNullHandle(context)) {
            return null;
        }
        Arena arena = Arena.ofConfined();
        try {
            return new GiLiveGpuResources(context, deferredRelease, arena);
        } catch (RuntimeException failure) {
            arena.close();
            MetalNativeBridge.metallum_gi_live_release_context_v1(context);
            throw failure;
        }
    }

    /**
     * Publishes exact zero coverage for a new epoch before any cascade can be encoded.
     * Repeating the same epoch is idempotent; a different epoch must advance its version.
     */
    public int invalidate(
            final GiLiveEpoch epoch,
            final GiLiveLayout.ResetKind resetKind,
            final long sourceTick
    ) {
        return invalidate(epoch, resetKind, sourceTick, false);
    }

    public int invalidate(
            final GiLiveEpoch epoch,
            final GiLiveLayout.ResetKind resetKind,
            final long sourceTick,
            final boolean preserveExact
    ) {
        assertUsable();
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(resetKind, "resetKind");
        requirePublicationTransition(
                this.activeEpoch, this.activeSourceTick, epoch, sourceTick
        );
        int flags = preserveExact ? GiLiveLayout.FLAG_PRESERVE_EXACT : GiLiveLayout.FLAGS_NONE;
        writeHeader(this.headers[0], epoch, 0, 0, 0,
                epochSourceStamp(epoch), sourceTick, resetKind,
                flags, 0, 0L, 0L);
        int status = MetalNativeBridge.metallum_gi_live_invalidate_v1(
                this.context, this.headers[0]
        );
        if (status == GiLiveLayout.STATUS_OK) {
            this.activeEpoch = epoch;
            this.activeResetKind = resetKind;
            this.activeSourceTick = sourceTick;
            java.util.Arrays.fill(this.preparedCellGeneration, 0L);
        }
        return status;
    }

    /** Publishes three cascade-local invalid masks without changing the fixed external ABI. */
    public int invalidatePlanned(
            final GiLiveEpoch epoch,
            final GiLiveLayout.ResetKind resetKind,
            final long sourceTick,
            final boolean preserveExact,
            final boolean retainCapturedBasis,
            final boolean[] scrollCascades,
            final long[] requiredMasks
    ) {
        assertUsable();
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(resetKind, "resetKind");
        Objects.requireNonNull(scrollCascades, "scrollCascades");
        Objects.requireNonNull(requiredMasks, "requiredMasks");
        if (scrollCascades.length != GiLiveLayout.CASCADE_COUNT
                || requiredMasks.length != GiLiveLayout.CASCADE_COUNT
                || retainCapturedBasis && !preserveExact) {
            throw new IllegalArgumentException("Invalid G6 cascade plan topology");
        }
        requirePublicationTransition(this.activeEpoch, this.activeSourceTick, epoch, sourceTick);
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            int flags = GiLiveLayout.FLAG_INCREMENTAL_BRICKS
                    | GiLiveLayout.FLAG_PREPARE_CASCADE;
            if (preserveExact) flags |= GiLiveLayout.FLAG_PRESERVE_EXACT;
            if (retainCapturedBasis) flags |= GiLiveLayout.FLAG_RETAIN_CAPTURED_BASIS;
            if (scrollCascades[cascade]) flags |= GiLiveLayout.FLAG_SCROLL_REMAP;
            writeHeader(this.headers[cascade], epoch, cascade, 0, 0,
                    epochSourceStamp(epoch), sourceTick, resetKind,
                    flags, 0, 0L, requiredMasks[cascade]);
            int status = MetalNativeBridge.metallum_gi_live_invalidate_v1(
                    this.context, this.headers[cascade]
            );
            if (status != GiLiveLayout.STATUS_OK) return status;
        }
        this.activeEpoch = epoch;
        this.activeResetKind = resetKind;
        this.activeSourceTick = sourceTick;
        java.util.Arrays.fill(this.preparedCellGeneration, 0L);
        return GiLiveLayout.STATUS_OK;
    }

    /** Refines one provisional cascade plan with G3's authoritative affected-source mask. */
    public int planCascade(
            final GiLiveEpoch epoch,
            final int cascade,
            final boolean preserveExact,
            final boolean scrollRemap,
            final long requiredMask
    ) {
        assertUsable();
        if (!epoch.equals(this.activeEpoch)
                || cascade < 0 || cascade >= GiLiveLayout.CASCADE_COUNT) {
            return GiLiveLayout.STATUS_STALE;
        }
        int flags = GiLiveLayout.FLAG_INCREMENTAL_BRICKS
                | GiLiveLayout.FLAG_PREPARE_CASCADE;
        if (preserveExact) flags |= GiLiveLayout.FLAG_PRESERVE_EXACT;
        if (scrollRemap) flags |= GiLiveLayout.FLAG_SCROLL_REMAP;
        writeHeader(this.headers[cascade], epoch, cascade, 0, 0,
                epochSourceStamp(epoch), this.activeSourceTick, this.activeResetKind,
                flags, 0, 0L, requiredMask);
        return MetalNativeBridge.metallum_gi_live_invalidate_v1(
                this.context, this.headers[cascade]
        );
    }

    /** Makes a failed first batch repeat semantic preparation and first-submit clearing. */
    public void markCascadeUnprepared(final int cascade) {
        assertUsable();
        if (cascade < 0 || cascade >= GiLiveLayout.CASCADE_COUNT) {
            throw new IndexOutOfBoundsException("G6 cascade is outside the fixed topology");
        }
        this.preparedCellGeneration[cascade] = 0L;
    }

    /**
     * Admits one bounded receiver-brick batch. The full semantic cascade is copied once into its
     * persistent Java packet for this field generation. Native re-stages that packet whenever
     * the single cap-bounded Metal staging buffer changes cascade ownership.
     */
    public int encodeBricks(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final int inFlightSlot,
            final GiLiveEpoch epoch,
            final GiDirectSourceCoordinator.LiveSource source,
            final GiSemanticTransportFieldView field,
            final long batchMask,
            final int batchCount,
            final long requiredMask,
            final boolean prepareCascade,
            final boolean preserveExact,
            final boolean scrollRemap
    ) {
        assertUsable();
        requireSlot(inFlightSlot);
        if (MetalNativeBridge.isNullHandle(commandBuffer)) {
            throw new IllegalArgumentException("G6 command buffer must not be null");
        }
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(field, "field");
        if (!epoch.equals(this.activeEpoch)) {
            return GiLiveLayout.STATUS_STALE;
        }
        requireMatchingField(epoch, field);
        requireMatchingSource(epoch, source);
        if (batchCount <= 0 || batchCount > GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                || Long.bitCount(batchMask) != batchCount
                || prepareCascade != (this.preparedCellGeneration[source.cascade()]
                    != epoch.version())
                || !prepareCascade && requiredMask != 0L
                || scrollRemap && (!prepareCascade || !preserveExact)) {
            throw new IllegalArgumentException("Invalid G6 incremental brick batch");
        }

        if (queryStats().buildInFlight()) {
            return GiLiveLayout.STATUS_BUSY;
        }

        int cascade = source.cascade();
        MemorySegment cellPacket = this.cells[cascade];
        if (prepareCascade) {
            GiSemanticTransportFieldView.CopyResult copy = field.copyCascade(
                    cascade, cellPacket
            );
            CellCounts counts = countCells(cellPacket);
            if (copy.unknownCells() != counts.unknown()) {
                throw new IllegalStateException(
                        "G6 semantic copy metadata differs from its packet"
                );
            }
            this.preparedValidSurfaces[cascade] = counts.validSurfaces();
            this.preparedUnknownCells[cascade] = counts.unknown();
            this.preparedCellGeneration[cascade] = epoch.version();
        }
        int flags = GiLiveLayout.FLAG_INCREMENTAL_BRICKS;
        if (prepareCascade) flags |= GiLiveLayout.FLAG_PREPARE_CASCADE;
        if (preserveExact) flags |= GiLiveLayout.FLAG_PRESERVE_EXACT;
        if (scrollRemap) flags |= GiLiveLayout.FLAG_SCROLL_REMAP;
        writeHeader(
                this.headers[inFlightSlot], epoch, cascade,
                this.preparedValidSurfaces[cascade], this.preparedUnknownCells[cascade],
                epochSourceStamp(epoch), this.activeSourceTick, this.activeResetKind,
                flags, batchCount, batchMask, requiredMask
        );
        int status = MetalNativeBridge.metallum_gi_live_encode_cascade_v1(
                this.context, commandBuffer, fence, inFlightSlot,
                this.headers[inFlightSlot], cellPacket
        );
        if (status != GiLiveLayout.STATUS_OK && prepareCascade) {
            this.preparedCellGeneration[cascade] = 0L;
        }
        return status;
    }

    /**
     * Remaps compatible near coverage before G3 has converged to the new clipmap tuple. The
     * packet also stages current cells once, but encodes no transport brick and performs no
     * source-texture access. A later authoritative epoch rebases on this completed exact mask.
     */
    public int encodeScrollRemap(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final int inFlightSlot,
            final GiLiveEpoch epoch,
            final GiSemanticTransportFieldView field,
            final int cascade,
            final long requiredMask
    ) {
        assertUsable();
        requireSlot(inFlightSlot);
        if (MetalNativeBridge.isNullHandle(commandBuffer)
                || !epoch.equals(this.activeEpoch)
                || cascade < 0 || cascade >= GiLiveLayout.CASCADE_COUNT) {
            throw new IllegalArgumentException("Invalid G6 scroll-remap admission");
        }
        Objects.requireNonNull(field, "field");
        requireMatchingField(epoch, field);
        if (queryStats().buildInFlight()) return GiLiveLayout.STATUS_BUSY;

        MemorySegment cellPacket = this.cells[cascade];
        GiSemanticTransportFieldView.CopyResult copy = field.copyCascade(cascade, cellPacket);
        CellCounts counts = countCells(cellPacket);
        if (copy.unknownCells() != counts.unknown()) {
            throw new IllegalStateException("G6 scroll-remap semantic metadata differs");
        }
        this.preparedValidSurfaces[cascade] = counts.validSurfaces();
        this.preparedUnknownCells[cascade] = counts.unknown();
        this.preparedCellGeneration[cascade] = epoch.version();
        int flags = GiLiveLayout.FLAG_INCREMENTAL_BRICKS
                | GiLiveLayout.FLAG_PREPARE_CASCADE
                | GiLiveLayout.FLAG_PRESERVE_EXACT
                | GiLiveLayout.FLAG_SCROLL_REMAP;
        writeHeader(
                this.headers[inFlightSlot], epoch, cascade,
                counts.validSurfaces(), counts.unknown(),
                epochSourceStamp(epoch), this.activeSourceTick, this.activeResetKind,
                flags, 0, 0L, requiredMask
        );
        int status = MetalNativeBridge.metallum_gi_live_encode_cascade_v1(
                this.context, commandBuffer, fence, inFlightSlot,
                this.headers[inFlightSlot], cellPacket
        );
        if (status != GiLiveLayout.STATUS_OK) {
            this.preparedCellGeneration[cascade] = 0L;
        }
        return status;
    }

    /** Binds the atlas and exact per-slot receiver packet to the vertex encoder. */
    public int bindVertex(
            final MemorySegment renderEncoder,
            final int inFlightSlot,
            final boolean carrierSafe
    ) {
        assertUsable();
        requireSlot(inFlightSlot);
        if (MetalNativeBridge.isNullHandle(renderEncoder)) {
            throw new IllegalArgumentException("G6 render encoder must not be null");
        }
        return MetalNativeBridge.metallum_gi_live_bind_vertex_v1(
                this.context, renderEncoder, inFlightSlot, carrierSafe
        );
    }

    public Stats stats() {
        assertUsable();
        return queryStats();
    }

    /**
     * Begins the exactly-once benchmark probe after the complete current field is receiver
     * visible.  The request and response packets are persistent arena allocations; no render
     * submission waits for the blit or performs a CPU readback.
     */
    public int beginDebugProbe(
            final int[] cascades,
            final int[] worldXs,
            final int[] worldYs,
            final int[] worldZs
    ) {
        assertUsable();
        if (this.debugProbeRequest.equals(MemorySegment.NULL)) {
            return GiLiveDebugProbeLayout.STATUS_REJECTED;
        }
        if (this.debugProbeStarted || this.debugProbeDelivered) {
            return GiLiveDebugProbeLayout.STATUS_REJECTED;
        }
        validateProbeInput(cascades, worldXs, worldYs, worldZs);
        GiLiveEpoch epoch = this.activeEpoch;
        Stats stats = queryStats();
        if (epoch == null || !statsMatchesDebugProbeEpoch(stats, epoch, this.activeSourceTick)
                || !stats.allCascadesReady()
                || stats.receiverVisibleMask() != GiLiveLayout.READY_MASK_ALL) {
            return GiLiveDebugProbeLayout.STATUS_STALE;
        }
        this.debugProbeRequest.fill((byte) 0);
        this.debugProbeRequest.set(
                LE_INT, GiLiveDebugProbeLayout.REQUEST_ABI_VERSION_OFFSET,
                GiLiveDebugProbeLayout.ABI_VERSION
        );
        this.debugProbeRequest.set(
                LE_INT, GiLiveDebugProbeLayout.REQUEST_SAMPLE_COUNT_OFFSET,
                GiLiveDebugProbeLayout.MAX_SAMPLES
        );
        for (int index = 0; index < GiLiveDebugProbeLayout.MAX_SAMPLES; index++) {
            long offset = GiLiveDebugProbeLayout.requestSampleOffset(index);
            this.debugProbeRequest.set(
                    LE_INT, offset + GiLiveDebugProbeLayout.REQUEST_SAMPLE_CASCADE_OFFSET,
                    cascades[index]
            );
            this.debugProbeRequest.set(
                    LE_INT, offset + GiLiveDebugProbeLayout.REQUEST_SAMPLE_WORLD_X_OFFSET,
                    worldXs[index]
            );
            this.debugProbeRequest.set(
                    LE_INT, offset + GiLiveDebugProbeLayout.REQUEST_SAMPLE_WORLD_Y_OFFSET,
                    worldYs[index]
            );
            this.debugProbeRequest.set(
                    LE_INT, offset + GiLiveDebugProbeLayout.REQUEST_SAMPLE_WORLD_Z_OFFSET,
                    worldZs[index]
            );
        }
        int status = MetalNativeBridge.metallum_gi_live_begin_debug_probe_v1(
                this.context, this.debugProbeRequest
        );
        this.debugProbeLastStatus = status;
        if (status == GiLiveDebugProbeLayout.STATUS_OK) {
            this.debugProbeExpectation = new DebugProbeExpectation(
                    epoch, this.activeSourceTick, stats.readyMask(), stats.receiverVisibleMask(),
                    cascades, worldXs, worldYs, worldZs
            );
            this.debugProbeStarted = true;
        }
        return status;
    }

    /**
     * Polls the one-shot native blit without waiting.  A capture is exposed only if its echoed
     * epoch, source tick, origins, masks and requested world coordinates still exactly match the
     * request-time live receiver state.
     */
    public @Nullable DebugProbeCapture pollDebugProbe() {
        assertUsable();
        if (this.debugProbeResult.equals(MemorySegment.NULL) || !this.debugProbeStarted
                || this.debugProbeDelivered || this.debugProbeExpectation == null) {
            return null;
        }
        this.debugProbeResult.fill((byte) 0);
        int status = MetalNativeBridge.metallum_gi_live_poll_debug_probe_v1(
                this.context, this.debugProbeResult
        );
        this.debugProbeLastStatus = status;
        if (status == GiLiveDebugProbeLayout.STATUS_BUSY) {
            return null;
        }
        DebugProbeExpectation expectation = this.debugProbeExpectation;
        if (status == GiLiveDebugProbeLayout.STATUS_STALE) {
            // A movement/source successor won the race with the optional diagnostic blit.
            // It is not a production failure: clear this one-shot ownership and let the
            // benchmark re-prove a current receiver tuple before issuing its bounded retry.
            this.debugProbeStarted = false;
            this.debugProbeExpectation = null;
            return null;
        }
        this.debugProbeDelivered = true;
        this.debugProbeExpectation = null;
        if (status != GiLiveDebugProbeLayout.STATUS_OK) {
            throw new IllegalStateException("G6 debug-probe poll failed: " + status);
        }
        try {
            return decodeDebugProbeCapture(this.debugProbeResult, expectation);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("G6 debug-probe receipt failed exact validation", invalid);
        }
    }

    /** Most recent begin/poll status for the benchmark's bounded STALE retry only. */
    public int debugProbeLastStatus() {
        assertUsable();
        return this.debugProbeLastStatus;
    }

    private Stats queryStats() {
        this.statsPacket.fill((byte) 0);
        int status = MetalNativeBridge.metallum_gi_live_get_stats_v1(
                this.context, this.statsPacket, this.statsPacket.byteSize()
        );
        if (status != GiLiveLayout.STATUS_OK) {
            throw new IllegalStateException("Native G6 live stats query failed: " + status);
        }
        return decodeStats(this.statsPacket, this.statsView);
    }

    @Override
    public void close() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return;
        }
        assertOwnerThread();
        MemorySegment stale = this.context;
        this.context = MemorySegment.NULL;
        this.activeEpoch = null;
        this.deferredRelease.accept(stale);
        this.arena.close();
    }

    static void writeHeader(
            final MemorySegment header,
            final GiLiveEpoch epoch,
            final int cascade,
            final int validSurfaceCount,
            final int unknownCellCount,
            final long sourceStamp,
            final long sourceTick,
            final GiLiveLayout.ResetKind resetKind
    ) {
        writeHeader(
                header, epoch, cascade, validSurfaceCount, unknownCellCount,
                sourceStamp, sourceTick, resetKind,
                GiLiveLayout.FLAGS_NONE, 0, 0L, 0L
        );
    }

    static void writeHeader(
            final MemorySegment header,
            final GiLiveEpoch epoch,
            final int cascade,
            final int validSurfaceCount,
            final int unknownCellCount,
            final long sourceStamp,
            final long sourceTick,
            final GiLiveLayout.ResetKind resetKind,
            final int flags,
            final int batchCount,
            final long batchMask,
            final long requiredMask
    ) {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(resetKind, "resetKind");
        if (header.byteSize() != GiLiveLayout.HEADER_BYTES
                || cascade < 0 || cascade >= GiLiveLayout.CASCADE_COUNT
                || validSurfaceCount < 0 || validSurfaceCount > GiLiveLayout.CELL_COUNT
                || unknownCellCount < 0 || unknownCellCount > GiLiveLayout.CELL_COUNT
                || sourceStamp == 0L || sourceTick < 0L
                || (flags & ~GiLiveLayout.KNOWN_FLAGS) != 0
                || batchCount < 0 || batchCount > GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                || Long.bitCount(batchMask) != batchCount
                || (batchCount == 0) != (batchMask == 0L)
                || (flags & GiLiveLayout.FLAG_INCREMENTAL_BRICKS) == 0
                    && (batchCount != 0 || batchMask != 0L || requiredMask != 0L)
                || (flags & GiLiveLayout.FLAG_SCROLL_REMAP) != 0
                    && (flags & GiLiveLayout.FLAG_PREPARE_CASCADE) == 0
                || (flags & GiLiveLayout.FLAG_RETAIN_CAPTURED_BASIS) != 0
                    && ((flags & (GiLiveLayout.FLAG_INCREMENTAL_BRICKS
                            | GiLiveLayout.FLAG_PRESERVE_EXACT
                            | GiLiveLayout.FLAG_PREPARE_CASCADE))
                            != (GiLiveLayout.FLAG_INCREMENTAL_BRICKS
                            | GiLiveLayout.FLAG_PRESERVE_EXACT
                            | GiLiveLayout.FLAG_PREPARE_CASCADE)
                            || batchCount != 0 || batchMask != 0L)
                || (flags & GiLiveLayout.FLAG_PREPARE_CASCADE) == 0
                    && requiredMask != 0L) {
            throw new IllegalArgumentException("Invalid G6 header input");
        }
        header.fill((byte) 0);
        header.set(LE_INT, GiLiveLayout.HEADER_ABI_VERSION_OFFSET, GiLiveLayout.ABI_VERSION);
        header.set(LE_INT, GiLiveLayout.HEADER_HEADER_BYTES_OFFSET, GiLiveLayout.HEADER_BYTES);
        header.set(LE_LONG, GiLiveLayout.HEADER_WORLD_GENERATION_OFFSET,
                epoch.worldGeneration());
        header.set(LE_LONG, GiLiveLayout.HEADER_CLIPMAP_GENERATION_OFFSET,
                epoch.clipmapGeneration());
        header.set(LE_LONG, GiLiveLayout.HEADER_PALETTE_GENERATION_OFFSET,
                epoch.paletteGeneration());
        header.set(LE_LONG, GiLiveLayout.HEADER_CONTENT_GENERATION_OFFSET,
                epoch.contentGeneration());
        header.set(LE_LONG, GiLiveLayout.HEADER_STATIC_SOURCE_EPOCH_OFFSET,
                epoch.staticSourceEpoch());
        header.set(LE_LONG, GiLiveLayout.HEADER_DYNAMIC_SOURCE_EPOCH_OFFSET,
                epoch.dynamicSourceEpoch());
        header.set(LE_LONG, GiLiveLayout.HEADER_ENVIRONMENT_EPOCH_OFFSET,
                epoch.environmentEpoch());
        for (int index = 0; index < 9; index++) {
            GiLiveEpoch.Origin origin = epoch.origin(index / 3);
            header.set(LE_INT, GiLiveLayout.HEADER_ORIGIN_0_X_OFFSET
                    + (long) index * Integer.BYTES,
                    switch (index % 3) {
                        case 0 -> origin.x();
                        case 1 -> origin.y();
                        default -> origin.z();
                    });
        }
        header.set(LE_INT, GiLiveLayout.HEADER_CASCADE_INDEX_OFFSET, cascade);
        header.set(LE_INT, GiLiveLayout.HEADER_CELL_COUNT_OFFSET, GiLiveLayout.CELL_COUNT);
        header.set(LE_INT, GiLiveLayout.HEADER_ITERATION_COUNT_OFFSET,
                GiLiveLayout.ITERATION_COUNT);
        header.set(LE_INT, GiLiveLayout.HEADER_MAXIMUM_DISTANCE_OFFSET,
                GiLiveLayout.MAXIMUM_DISTANCE);
        header.set(LE_INT, GiLiveLayout.HEADER_VALID_SURFACE_COUNT_OFFSET,
                validSurfaceCount);
        header.set(LE_INT, GiLiveLayout.HEADER_UNKNOWN_CELL_COUNT_OFFSET, unknownCellCount);
        header.set(LE_FLOAT, GiLiveLayout.HEADER_FORM_WEIGHT_NORMALIZATION_OFFSET,
                GiLiveLayout.FORM_WEIGHT_NORMALIZATION);
        header.set(LE_FLOAT, GiLiveLayout.HEADER_FP16_ABSOLUTE_TOLERANCE_OFFSET,
                GiLiveLayout.FP16_ABSOLUTE_TOLERANCE);
        header.set(LE_FLOAT, GiLiveLayout.HEADER_FP16_RELATIVE_TOLERANCE_OFFSET,
                GiLiveLayout.FP16_RELATIVE_TOLERANCE);
        header.set(LE_INT, GiLiveLayout.HEADER_FLAGS_OFFSET, flags);
        header.set(LE_LONG, GiLiveLayout.HEADER_SOURCE_STAMP_OFFSET, sourceStamp);
        header.set(LE_LONG, GiLiveLayout.HEADER_FIELD_GENERATION_OFFSET, epoch.version());
        header.set(LE_LONG, GiLiveLayout.HEADER_SOURCE_TICK_OFFSET, sourceTick);
        header.set(LE_INT, GiLiveLayout.HEADER_RESET_KIND_OFFSET, resetKind.nativeId());
        header.set(LE_INT, GiLiveLayout.HEADER_RESERVED_32_OFFSET, batchCount);
        header.set(LE_LONG, GiLiveLayout.HEADER_RESERVED_0_OFFSET, batchMask);
        header.set(LE_LONG, GiLiveLayout.HEADER_RESERVED_1_OFFSET, requiredMask);
    }

    /** Allocating decoder reserved for focused ABI tests and explicit diagnostic receipts. */
    static Stats decodeStats(final MemorySegment packet) {
        return decodeStats(packet, new Stats());
    }

    static Stats decodeStats(final MemorySegment packet, final Stats target) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(target, "target");
        if (packet.byteSize() < GiLiveLayout.STATS_BYTES) {
            throw new IllegalArgumentException("Invalid G6 native stats packet");
        }
        int inFlight = packet.get(LE_INT, GiLiveLayout.STATS_BUILD_IN_FLIGHT_OFFSET);
        if (inFlight != 0 && inFlight != 1) {
            throw new IllegalArgumentException("Invalid G6 build-in-flight flag");
        }
        target.readyMask = packet.get(LE_INT, GiLiveLayout.STATS_READY_MASK_OFFSET);
        target.buildInFlight = inFlight == 1;
        target.shaderLibraryMode = packet.get(
                LE_INT, GiLiveLayout.STATS_SHADER_LIBRARY_MODE_OFFSET
        );
        int receiverMaskState = packet.get(
                LE_INT, GiLiveLayout.STATS_RECEIVER_MASK_STATE_OFFSET
        );
        if ((receiverMaskState & ~GiLiveLayout.STATS_RECEIVER_MASK_STATE_KNOWN_BITS) != 0) {
            throw new IllegalArgumentException("Invalid G6 receiver mask state");
        }
        target.receiverVisibleMask = receiverMaskState & GiLiveLayout.READY_MASK_ALL;
        target.lastBindVisibleMask = receiverMaskState
                >>> GiLiveLayout.STATS_LAST_BIND_VISIBLE_MASK_SHIFT;
        target.worldGeneration = packet.get(LE_LONG, GiLiveLayout.STATS_WORLD_GENERATION_OFFSET);
        target.clipmapGeneration = packet.get(
                LE_LONG, GiLiveLayout.STATS_CLIPMAP_GENERATION_OFFSET
        );
        target.contentGeneration = packet.get(
                LE_LONG, GiLiveLayout.STATS_CONTENT_GENERATION_OFFSET
        );
        target.staticSourceEpoch = packet.get(
                LE_LONG, GiLiveLayout.STATS_STATIC_SOURCE_EPOCH_OFFSET
        );
        target.dynamicSourceEpoch = packet.get(
                LE_LONG, GiLiveLayout.STATS_DYNAMIC_SOURCE_EPOCH_OFFSET
        );
        target.environmentEpoch = packet.get(
                LE_LONG, GiLiveLayout.STATS_ENVIRONMENT_EPOCH_OFFSET
        );
        target.fieldGeneration = packet.get(
                LE_LONG, GiLiveLayout.STATS_FIELD_GENERATION_OFFSET
        );
        target.sourceTick = packet.get(LE_LONG, GiLiveLayout.STATS_SOURCE_TICK_OFFSET);
        target.allocatedBytes = packet.get(LE_LONG, GiLiveLayout.STATS_ALLOCATED_BYTES_OFFSET);
        target.residentBytes = packet.get(LE_LONG, GiLiveLayout.STATS_RESIDENT_BYTES_OFFSET);
        target.stagingBytes = packet.get(LE_LONG, GiLiveLayout.STATS_STAGING_BYTES_OFFSET);
        target.transportDispatches = packet.get(
                LE_LONG, GiLiveLayout.STATS_TRANSPORT_DISPATCHES_OFFSET
        );
        target.cascadeBuilds0 = packet.get(
                LE_LONG, GiLiveLayout.STATS_CASCADE_BUILDS_0_OFFSET
        );
        target.cascadeBuilds1 = packet.get(
                LE_LONG, GiLiveLayout.STATS_CASCADE_BUILDS_1_OFFSET
        );
        target.cascadeBuilds2 = packet.get(
                LE_LONG, GiLiveLayout.STATS_CASCADE_BUILDS_2_OFFSET
        );
        target.invalidations = packet.get(LE_LONG, GiLiveLayout.STATS_INVALIDATIONS_OFFSET);
        target.staleRejects = packet.get(LE_LONG, GiLiveLayout.STATS_STALE_REJECTS_OFFSET);
        target.busyRejects = packet.get(LE_LONG, GiLiveLayout.STATS_BUSY_REJECTS_OFFSET);
        target.rejectedCount = packet.get(LE_LONG, GiLiveLayout.STATS_REJECTED_COUNT_OFFSET);
        target.bindCount = packet.get(LE_LONG, GiLiveLayout.STATS_BIND_COUNT_OFFSET);
        target.zeroBindings = packet.get(LE_LONG, GiLiveLayout.STATS_ZERO_BINDINGS_OFFSET);
        target.fieldBindings = packet.get(LE_LONG, GiLiveLayout.STATS_FIELD_BINDINGS_OFFSET);
        target.resetWorld = Integer.toUnsignedLong(
                packet.get(LE_INT, GiLiveLayout.STATS_RESET_WORLD_OFFSET)
        );
        target.resetTeleport = Integer.toUnsignedLong(
                packet.get(LE_INT, GiLiveLayout.STATS_RESET_TELEPORT_OFFSET)
        );
        target.resetScroll = Integer.toUnsignedLong(
                packet.get(LE_INT, GiLiveLayout.STATS_RESET_SCROLL_OFFSET)
        );
        target.resetSource = Integer.toUnsignedLong(
                packet.get(LE_INT, GiLiveLayout.STATS_RESET_SOURCE_OFFSET)
        );
        target.resetExplicit = Integer.toUnsignedLong(
                packet.get(LE_INT, GiLiveLayout.STATS_RESET_EXPLICIT_OFFSET)
        );
        target.resetDevice = Integer.toUnsignedLong(
                packet.get(LE_INT, GiLiveLayout.STATS_RESET_DEVICE_OFFSET)
        );
        target.validate();
        return target;
    }

    static long epochSourceStamp(final GiLiveEpoch epoch) {
        Objects.requireNonNull(epoch, "epoch");
        long hash = FNV_OFFSET_BASIS;
        hash = fnvLong(hash, epoch.version());
        hash = fnvLong(hash, epoch.worldGeneration());
        hash = fnvLong(hash, epoch.resourceEpoch());
        hash = fnvLong(hash, epoch.materialEpoch());
        hash = fnvLong(hash, epoch.clipmapGeneration());
        hash = fnvLong(hash, epoch.paletteGeneration());
        hash = fnvLong(hash, epoch.contentGeneration());
        hash = fnvLong(hash, epoch.staticSourceEpoch());
        hash = fnvLong(hash, epoch.dynamicSourceEpoch());
        hash = fnvLong(hash, epoch.environmentEpoch());
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            GiLiveEpoch.Origin origin = epoch.origin(cascade);
            hash = fnvLong(hash, origin.x());
            hash = fnvLong(hash, origin.y());
            hash = fnvLong(hash, origin.z());
        }
        for (int index = 0; index < epoch.dimensionId().length(); index++) {
            hash ^= epoch.dimensionId().charAt(index);
            hash *= FNV_PRIME;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return hash == 0L ? 1L : hash;
    }

    static void requirePublicationTransition(
            final @Nullable GiLiveEpoch previous,
            final long previousSourceTick,
            final GiLiveEpoch next,
            final long nextSourceTick
    ) {
        Objects.requireNonNull(next, "next");
        if (previousSourceTick < 0L || nextSourceTick < 0L) {
            throw new IllegalArgumentException("G6 source tick must not be negative");
        }
        if (previous == null) {
            return;
        }
        if (previous.equals(next)) {
            if (previousSourceTick != nextSourceTick) {
                throw new IllegalArgumentException(
                        "G6 source tick changed without a new field generation"
                );
            }
            return;
        }
        next.requireStrictlyNewerThan(previous);
        if (next.sameWorld(previous) && nextSourceTick < previousSourceTick) {
            throw new IllegalArgumentException("G6 source tick regressed within one world");
        }
    }

    private static void requireMatchingField(
            final GiLiveEpoch epoch,
            final GiSemanticTransportFieldView field
    ) {
        if (!epoch.dimensionId().equals(field.world().dimensionId())
                || epoch.worldGeneration() != field.worldGeneration()
                || epoch.resourceEpoch() != field.resourceEpoch()
                || epoch.materialEpoch() != field.materialEpoch()
                || epoch.clipmapGeneration() != field.clipmapGeneration()
                || epoch.paletteGeneration() != field.paletteGeneration()
                || epoch.contentGeneration() != field.contentGeneration()) {
            throw new IllegalArgumentException("G6 semantic field does not match its live epoch");
        }
        for (int index = 0; index < 9; index++) {
            GiLiveEpoch.Origin origin = epoch.origin(index / 3);
            int expected = switch (index % 3) {
                case 0 -> origin.x();
                case 1 -> origin.y();
                default -> origin.z();
            };
            if (field.originComponent(index) != expected) {
                throw new IllegalArgumentException(
                        "G6 semantic field origin does not match its live epoch"
                );
            }
        }
    }

    private static void requireMatchingSource(
            final GiLiveEpoch epoch,
            final GiDirectSourceCoordinator.LiveSource source
    ) {
        GiDirectSourceEpoch direct = source.epoch();
        if (!epoch.dimensionId().equals(direct.staticLightWorld().dimensionId())
                || epoch.worldGeneration() != direct.g2WorldGeneration()
                || epoch.resourceEpoch() != direct.g2ResourceEpoch()
                || epoch.materialEpoch() != direct.g2MaterialEpoch()
                || epoch.clipmapGeneration() != direct.g2ClipmapGeneration()
                || epoch.paletteGeneration() != direct.g2PaletteGeneration()
                || epoch.contentGeneration() != direct.g2ContentGeneration()
                || epoch.staticSourceEpoch() != direct.staticLightRegistryEpoch()
                || epoch.dynamicSourceEpoch() != source.dynamicSourceEpoch()
                || epoch.environmentEpoch() != direct.environmentEpoch()
                || source.sourceStamp() == 0L) {
            throw new IllegalArgumentException("G6 direct source does not match its live epoch");
        }
        for (int index = 0; index < 9; index++) {
            GiLiveEpoch.Origin origin = epoch.origin(index / 3);
            int expected = switch (index % 3) {
                case 0 -> origin.x();
                case 1 -> origin.y();
                default -> origin.z();
            };
            if (source.originComponent(index) != expected) {
                throw new IllegalArgumentException(
                        "G6 direct-source origin does not match its live epoch"
                );
            }
        }
    }

    private static void validateProbeInput(
            final int[] cascades,
            final int[] worldXs,
            final int[] worldYs,
            final int[] worldZs
    ) {
        requireProbeCoordinateLength(cascades, "cascades");
        requireProbeCoordinateLength(worldXs, "world X");
        requireProbeCoordinateLength(worldYs, "world Y");
        requireProbeCoordinateLength(worldZs, "world Z");
        for (int cascade : cascades) {
            if (cascade < 0 || cascade >= GiLiveLayout.CASCADE_COUNT) {
                throw new IllegalArgumentException("G6 debug-probe cascade is outside topology");
            }
        }
    }

    private static int[] requireProbeCoordinates(final int[] values, final String label) {
        requireProbeCoordinateLength(values, label);
        return values.clone();
    }

    private static void requireProbeCoordinateLength(final int[] values, final String label) {
        if (values == null || values.length != GiLiveDebugProbeLayout.MAX_SAMPLES) {
            throw new IllegalArgumentException(
                    "G6 debug-probe " + label + " must contain exactly "
                            + GiLiveDebugProbeLayout.MAX_SAMPLES + " samples"
            );
        }
    }

    private static boolean statsMatchesDebugProbeEpoch(
            final Stats stats,
            final GiLiveEpoch epoch,
            final long sourceTick
    ) {
        return sourceTick >= 0L
                && stats.worldGeneration() == epoch.worldGeneration()
                && stats.clipmapGeneration() == epoch.clipmapGeneration()
                && stats.contentGeneration() == epoch.contentGeneration()
                && stats.staticSourceEpoch() == epoch.staticSourceEpoch()
                && stats.dynamicSourceEpoch() == epoch.dynamicSourceEpoch()
                && stats.environmentEpoch() == epoch.environmentEpoch()
                && stats.fieldGeneration() == epoch.version()
                && stats.sourceTick() == sourceTick;
    }

    private static DebugProbeCapture decodeDebugProbeCapture(
            final MemorySegment packet,
            final DebugProbeExpectation expected
    ) {
        if (packet.byteSize() != GiLiveDebugProbeLayout.RESULT_BYTES
                || packet.get(LE_INT, GiLiveDebugProbeLayout.RESULT_ABI_VERSION_OFFSET)
                != GiLiveDebugProbeLayout.ABI_VERSION
                || packet.get(LE_INT, GiLiveDebugProbeLayout.RESULT_SAMPLE_COUNT_OFFSET)
                != GiLiveDebugProbeLayout.MAX_SAMPLES) {
            throw new IllegalArgumentException("Invalid G6 debug-probe result header");
        }
        long worldGeneration = packet.get(
                LE_LONG, GiLiveDebugProbeLayout.RESULT_WORLD_GENERATION_OFFSET
        );
        long clipmapGeneration = packet.get(
                LE_LONG, GiLiveDebugProbeLayout.RESULT_CLIPMAP_GENERATION_OFFSET
        );
        long paletteGeneration = packet.get(
                LE_LONG, GiLiveDebugProbeLayout.RESULT_PALETTE_GENERATION_OFFSET
        );
        long contentGeneration = packet.get(
                LE_LONG, GiLiveDebugProbeLayout.RESULT_CONTENT_GENERATION_OFFSET
        );
        long staticSourceEpoch = packet.get(
                LE_LONG, GiLiveDebugProbeLayout.RESULT_STATIC_SOURCE_EPOCH_OFFSET
        );
        long dynamicSourceEpoch = packet.get(
                LE_LONG, GiLiveDebugProbeLayout.RESULT_DYNAMIC_SOURCE_EPOCH_OFFSET
        );
        long environmentEpoch = packet.get(
                LE_LONG, GiLiveDebugProbeLayout.RESULT_ENVIRONMENT_EPOCH_OFFSET
        );
        long fieldGeneration = packet.get(
                LE_LONG, GiLiveDebugProbeLayout.RESULT_FIELD_GENERATION_OFFSET
        );
        long sourceTick = packet.get(LE_LONG, GiLiveDebugProbeLayout.RESULT_SOURCE_TICK_OFFSET);
        int readyMask = packet.get(LE_INT, GiLiveDebugProbeLayout.RESULT_READY_MASK_OFFSET);
        int receiverMask = packet.get(LE_INT, GiLiveDebugProbeLayout.RESULT_RECEIVER_MASK_OFFSET);
        int validMask = packet.get(LE_INT, GiLiveDebugProbeLayout.RESULT_SAMPLE_VALID_MASK_OFFSET);
        GiLiveEpoch epoch = expected.epoch();
        if (worldGeneration != epoch.worldGeneration()
                || clipmapGeneration != epoch.clipmapGeneration()
                || paletteGeneration != epoch.paletteGeneration()
                || contentGeneration != epoch.contentGeneration()
                || staticSourceEpoch != epoch.staticSourceEpoch()
                || dynamicSourceEpoch != epoch.dynamicSourceEpoch()
                || environmentEpoch != epoch.environmentEpoch()
                || fieldGeneration != epoch.version()
                || sourceTick != expected.sourceTick()
                || readyMask != expected.readyMask()
                || receiverMask != expected.receiverMask()
                || validMask != (1 << GiLiveDebugProbeLayout.MAX_SAMPLES) - 1) {
            throw new IllegalArgumentException("Stale or incomplete G6 debug-probe result");
        }
        int[] origins = new int[GiLiveLayout.CASCADE_COUNT * 3];
        for (int index = 0; index < origins.length; index++) {
            origins[index] = packet.get(
                    LE_INT,
                    GiLiveDebugProbeLayout.RESULT_RECEIVER_ORIGINS_OFFSET
                            + (long) index * Integer.BYTES
            );
            GiLiveEpoch.Origin expectedOrigin = epoch.origin(index / 3);
            int expectedComponent = switch (index % 3) {
                case 0 -> expectedOrigin.x();
                case 1 -> expectedOrigin.y();
                default -> expectedOrigin.z();
            };
            if (origins[index] != expectedComponent) {
                throw new IllegalArgumentException("G6 debug-probe receiver origin drifted");
            }
        }
        DebugProbeSample[] samples = new DebugProbeSample[GiLiveDebugProbeLayout.MAX_SAMPLES];
        for (int index = 0; index < samples.length; index++) {
            long offset = GiLiveDebugProbeLayout.resultSampleOffset(index);
            int cascade = packet.get(
                    LE_INT, offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_CASCADE_OFFSET
            );
            int worldX = packet.get(
                    LE_INT, offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_WORLD_X_OFFSET
            );
            int worldY = packet.get(
                    LE_INT, offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_WORLD_Y_OFFSET
            );
            int worldZ = packet.get(
                    LE_INT, offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_WORLD_Z_OFFSET
            );
            if (cascade != expected.cascades()[index]
                    || worldX != expected.worldXs()[index]
                    || worldY != expected.worldYs()[index]
                    || worldZ != expected.worldZs()[index]) {
                throw new IllegalArgumentException("G6 debug-probe sample identity drifted");
            }
            int localX = packet.get(
                    LE_INT, offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_LOCAL_X_OFFSET
            );
            int localY = packet.get(
                    LE_INT, offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_LOCAL_Y_OFFSET
            );
            int localZ = packet.get(
                    LE_INT, offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_LOCAL_Z_OFFSET
            );
            GiLiveEpoch.Origin origin = epoch.origin(cascade);
            int cellSize = GiFieldLayout.cellSizeBlocks(cascade);
            if (localX != Math.floorDiv(worldX - origin.x(), cellSize)
                    || localY != Math.floorDiv(worldY - origin.y(), cellSize)
                    || localZ != Math.floorDiv(worldZ - origin.z(), cellSize)) {
                throw new IllegalArgumentException("G6 debug-probe local coordinate drifted");
            }
            float[] sh = new float[12];
            for (int coefficient = 0; coefficient < sh.length; coefficient++) {
                sh[coefficient] = packet.get(
                        LE_FLOAT,
                        offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_SH_COEFFICIENTS_OFFSET
                                + (long) coefficient * Float.BYTES
                );
            }
            samples[index] = new DebugProbeSample(
                    cascade, worldX, worldY, worldZ, localX, localY, localZ,
                    packet.get(LE_INT, offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_FLAGS_OFFSET),
                    sh,
                    Byte.toUnsignedInt(packet.get(
                            ValueLayout.JAVA_BYTE,
                            offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_CONFIDENCE_OFFSET
                    )),
                    Byte.toUnsignedInt(packet.get(
                            ValueLayout.JAVA_BYTE,
                            offset + GiLiveDebugProbeLayout.RESULT_SAMPLE_SURFACE_COVERAGE_OFFSET
                    ))
            );
        }
        return new DebugProbeCapture(
                worldGeneration, clipmapGeneration, paletteGeneration, contentGeneration,
                staticSourceEpoch,
                dynamicSourceEpoch, environmentEpoch, fieldGeneration, sourceTick,
                origins, readyMask, receiverMask, validMask, samples
        );
    }

    private record CellCounts(int validSurfaces, int unknown) {
    }

    private static CellCounts countCells(final MemorySegment cells) {
        int validSurfaces = 0;
        int unknown = 0;
        for (int cell = 0; cell < GiLiveLayout.CELL_COUNT; cell++) {
            long offset = (long) cell * GiLiveLayout.CELL_BYTES;
            int validity = Byte.toUnsignedInt(cells.get(ValueLayout.JAVA_BYTE, offset + 14L));
            if (validity < GiSemanticValidity.UNKNOWN.abiId()
                    || validity > GiSemanticValidity.KNOWN_FALLBACK.abiId()) {
                throw new IllegalStateException("Unknown G6 semantic validity ABI");
            }
            if (validity == GiSemanticValidity.UNKNOWN.abiId()
                    || validity == GiSemanticValidity.KNOWN_FALLBACK.abiId()) {
                unknown++;
            }
            boolean hasFace = false;
            for (int face = 0; face < 6; face++) {
                hasFace |= cells.get(ValueLayout.JAVA_BYTE, offset + 8L + face) != 0;
            }
            if (validity == GiSemanticValidity.KNOWN_CONTENT.abiId()
                    && Short.toUnsignedInt(cells.get(LE_SHORT_UNALIGNED, offset + 6L)) != 0
                    && cells.get(ValueLayout.JAVA_BYTE, offset + 15L) != 0
                    && hasFace) {
                validSurfaces++;
            }
        }
        return new CellCounts(validSurfaces, unknown);
    }

    private static long fnvLong(long hash, final long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash ^= (value >>> shift) & 0xffL;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static void requireSlot(final int inFlightSlot) {
        if (inFlightSlot < 0 || inFlightSlot >= GiLiveLayout.IN_FLIGHT_SLOTS) {
            throw new IndexOutOfBoundsException("G6 in-flight slot is outside the fixed ring");
        }
    }

    private void assertUsable() {
        assertOwnerThread();
        if (MetalNativeBridge.isNullHandle(this.context)) {
            throw new IllegalStateException("G6 live resources are closed");
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G6 live resources are render-thread confined");
        }
    }
}
