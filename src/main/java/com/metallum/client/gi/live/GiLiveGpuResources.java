package com.metallum.client.gi.live;

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
     * Reused render-thread telemetry view. The object identity is stable for the resource
     * lifetime; callers must consume primitive values immediately and must not retain it as a
     * historical snapshot.
     */
    public static final class Stats {
        private int readyMask;
        private boolean buildInFlight;
        private int shaderLibraryMode;
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

    private final Thread ownerThread;
    private final Consumer<MemorySegment> deferredRelease;
    private final Arena arena;
    private final MemorySegment[] headers = new MemorySegment[GiLiveLayout.IN_FLIGHT_SLOTS];
    private final MemorySegment[] cells = new MemorySegment[GiLiveLayout.IN_FLIGHT_SLOTS];
    private final long[] preparedCellGeneration = new long[GiLiveLayout.CASCADE_COUNT];
    private final int[] preparedValidSurfaces = new int[GiLiveLayout.CASCADE_COUNT];
    private final int[] preparedUnknownCells = new int[GiLiveLayout.CASCADE_COUNT];
    private final MemorySegment statsPacket;
    private final Stats statsView = new Stats();
    private MemorySegment context;
    @Nullable private GiLiveEpoch activeEpoch;
    private GiLiveLayout.ResetKind activeResetKind = GiLiveLayout.ResetKind.EXPLICIT;
    private long activeSourceTick;

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
     * Admits at most eight receiver bricks. The full semantic cascade is copied only once for
     * this field generation; later batches reuse the preallocated cascade packet.
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
        if (packet.byteSize() < GiLiveLayout.STATS_BYTES
                || packet.get(LE_INT, GiLiveLayout.STATS_PADDING_0_OFFSET) != 0) {
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
