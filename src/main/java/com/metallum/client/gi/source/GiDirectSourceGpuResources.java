package com.metallum.client.gi.source;

import com.metallum.client.gi.semantic.GiSemanticDirectFieldView;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Consumer;

/** Render-thread owner of the private, field-only G3 direct-irradiance resources. */
public final class GiDirectSourceGpuResources implements AutoCloseable {
    public static final int ABI_VERSION = 1;
    public static final int LAYOUT_BYTES = 160;
    public static final int HEADER_BYTES = 160;
    public static final int BRICK_BYTES = 32;
    public static final int CELL_BYTES = 16;
    public static final int SOURCE_BYTES = 32;
    public static final int STATS_BYTES = 168;
    public static final int CAPTURE_DIRECT_BYTES = 8_192;
    public static final int CAPTURE_GEOMETRY_BYTES = 1_024;
    public static final long JAVA_PERSISTENT_PACKET_BYTES = HEADER_BYTES
            + (long) GiDirectSourceLayout.MAX_DRAIN_PER_FRAME * BRICK_BYTES
            + (long) GiDirectSourceLayout.MAX_DRAIN_PER_FRAME
            * GiSemanticDirectFieldView.CELLS_PER_BRICK * CELL_BYTES
            + (long) GiDirectSourceLayout.MAX_DRAIN_PER_FRAME
            * GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK * SOURCE_BYTES
            + STATS_BYTES;

    public static final int STATUS_OK = 1;
    public static final int STATUS_INVALID = -1;
    public static final int STATUS_BUSY = -2;
    public static final int STATUS_STALE = -3;
    public static final int STATUS_CAPTURE_CONSUMED = -4;
    public static final int STATUS_WRONG_THREAD = -5;
    public static final int STATUS_REJECTED = -6;

    private static final int RGBA16_FLOAT = 115;
    private static final int R8_UINT = 13;
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    public record PreparedBatch(
            MemorySegment header,
            MemorySegment bricks,
            MemorySegment cells,
            MemorySegment sources,
            int dirtyBrickCount,
            int sourceCount
    ) {
        public PreparedBatch {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(bricks, "bricks");
            Objects.requireNonNull(cells, "cells");
            Objects.requireNonNull(sources, "sources");
            if (header.byteSize() != HEADER_BYTES || dirtyBrickCount <= 0
                    || dirtyBrickCount > GiDirectSourceLayout.MAX_DRAIN_PER_FRAME
                    || bricks.byteSize() != (long) dirtyBrickCount * BRICK_BYTES
                    || cells.byteSize() != (long) dirtyBrickCount
                    * GiDirectSourceLayout.BRICK_EDGE_CELLS
                    * GiDirectSourceLayout.BRICK_EDGE_CELLS
                    * GiDirectSourceLayout.BRICK_EDGE_CELLS * CELL_BYTES
                    || sourceCount < 0
                    || sourceCount > dirtyBrickCount
                    * GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK
                    || sources.byteSize() != (long) sourceCount * SOURCE_BYTES) {
                throw new IllegalArgumentException("Invalid G3 direct-source batch slices");
            }
        }
    }

    public record Stats(
            boolean ready,
            boolean buildInFlight,
            long worldGeneration,
            long clipmapGeneration,
            long paletteGeneration,
            long contentGeneration,
            long staticSourceEpoch,
            long environmentEpoch,
            long persistentBytes,
            long stagingBytes,
            long readbackBytes,
            long batches,
            long dirtyBricks,
            long geometryApplyDispatches,
            long directInjectDispatches,
            long staleRejects,
            long busyRejects,
            long rejectedCount,
            long zeroStaticSourceBatches,
            long fullVolumeRebuilds,
            int nearOriginX,
            int nearOriginY,
            int nearOriginZ
    ) {
        public long accountedBytes() {
            return Math.addExact(persistentBytes, Math.addExact(stagingBytes, readbackBytes));
        }
    }

    public record Capture(short[] directRgbaFloat16, byte[] geometryState) {
        public Capture {
            directRgbaFloat16 = directRgbaFloat16.clone();
            geometryState = geometryState.clone();
        }
    }

    private final Thread ownerThread;
    private final Consumer<MemorySegment> deferredRelease;
    private final Arena arena;
    private final MemorySegment header;
    private final MemorySegment bricks;
    private final MemorySegment cells;
    private final MemorySegment sources;
    private final MemorySegment stats;
    private MemorySegment context;

    private GiDirectSourceGpuResources(
            final MemorySegment context,
            final Consumer<MemorySegment> deferredRelease,
            final Arena arena
    ) {
        this.ownerThread = Thread.currentThread();
        this.context = context;
        this.deferredRelease = deferredRelease;
        this.arena = arena;
        this.header = arena.allocate(HEADER_BYTES, Long.BYTES);
        this.bricks = arena.allocate(
                (long) GiDirectSourceLayout.MAX_DRAIN_PER_FRAME * BRICK_BYTES, Long.BYTES);
        this.cells = arena.allocate(
                (long) GiDirectSourceLayout.MAX_DRAIN_PER_FRAME
                        * GiSemanticDirectFieldView.CELLS_PER_BRICK * CELL_BYTES,
                Long.BYTES
        );
        this.sources = arena.allocate(
                (long) GiDirectSourceLayout.MAX_DRAIN_PER_FRAME
                        * GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK * SOURCE_BYTES,
                Long.BYTES
        );
        this.stats = arena.allocate(STATS_BYTES, Long.BYTES);
    }

    public static void validateNativeAbi() {
        if (MetalNativeBridge.metallum_gi_direct_source_abi_version_v1() != ABI_VERSION) {
            throw new IllegalStateException("Native G3 direct-source ABI version mismatch");
        }
        int[] expected = {
                ABI_VERSION, LAYOUT_BYTES, HEADER_BYTES, BRICK_BYTES, CELL_BYTES, SOURCE_BYTES,
                STATS_BYTES, GiDirectSourceLayout.CASCADE_COUNT,
                GiDirectSourceLayout.CELLS_PER_AXIS, GiDirectSourceLayout.BRICK_EDGE_CELLS,
                GiDirectSourceLayout.MAX_DRAIN_PER_FRAME,
                GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK,
                3, 2, 4, 8, RGBA16_FLOAT, R8_UINT,
                STATUS_STALE, STATUS_BUSY, STATUS_CAPTURE_CONSUMED, STATUS_WRONG_THREAD,
                STATUS_REJECTED, CAPTURE_DIRECT_BYTES, CAPTURE_GEOMETRY_BYTES
        };
        try (Arena probe = Arena.ofConfined()) {
            MemorySegment layout = probe.allocate(LAYOUT_BYTES, Long.BYTES);
            layout.fill((byte) 0x55);
            int status = MetalNativeBridge.metallum_gi_direct_source_layout_v1(
                    layout, layout.byteSize());
            if (status != STATUS_OK) {
                throw new IllegalStateException("Native G3 direct-source layout query failed: " + status);
            }
            for (int index = 0; index < expected.length; index++) {
                int actual = layout.get(LE_INT, (long) index * Integer.BYTES);
                if (actual != expected[index]) {
                    throw new IllegalStateException("Native G3 layout mismatch at word " + index
                            + ": expected " + expected[index] + ", got " + actual);
                }
            }
            for (int index = expected.length; index < LAYOUT_BYTES / Integer.BYTES; index++) {
                if (layout.get(LE_INT, (long) index * Integer.BYTES) != 0) {
                    throw new IllegalStateException("Native G3 reserved layout word is non-zero: " + index);
                }
            }
        }
    }

    public static @Nullable GiDirectSourceGpuResources create(
            final MemorySegment device,
            final MemorySegment commandQueue,
            final long worldGeneration,
            final Consumer<MemorySegment> deferredRelease
    ) {
        Objects.requireNonNull(deferredRelease, "deferredRelease");
        if (worldGeneration <= 0L || MetalNativeBridge.isNullHandle(device)
                || MetalNativeBridge.isNullHandle(commandQueue)) {
            return null;
        }
        validateNativeAbi();
        MemorySegment context = MetalNativeBridge.metallum_gi_direct_source_create_context_v1(
                device, commandQueue, worldGeneration);
        if (MetalNativeBridge.isNullHandle(context)) {
            return null;
        }
        Arena arena = Arena.ofConfined();
        try {
            return new GiDirectSourceGpuResources(context, deferredRelease, arena);
        } catch (RuntimeException failure) {
            arena.close();
            MetalNativeBridge.metallum_gi_direct_source_release_context_v1(context);
            throw failure;
        }
    }

    public PreparedBatch prepare(
            final GiDirectSourceEpoch epoch,
            final GiSemanticDirectFieldView field,
            final GiEnvironmentSource environment,
            final AdvancedLightRegistry registry,
            final int[] dirtyBricks,
            final int dirtyBrickCount,
            final AdvancedLight[] sourceScratch
    ) {
        assertUsable();
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(dirtyBricks, "dirtyBricks");
        Objects.requireNonNull(sourceScratch, "sourceScratch");
        if (dirtyBrickCount <= 0 || dirtyBrickCount > GiDirectSourceLayout.MAX_DRAIN_PER_FRAME
                || dirtyBrickCount > dirtyBricks.length
                || sourceScratch.length < GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK) {
            throw new IllegalArgumentException("G3 direct-source preparation exceeds fixed bounds");
        }
        if (!field.world().dimensionId().equals(epoch.staticLightWorld().dimensionId())
                || field.world().worldGeneration() != epoch.g2WorldGeneration()
                || field.clipmapGeneration() != epoch.g2ClipmapGeneration()
                || field.paletteGeneration() != epoch.g2PaletteGeneration()
                || field.contentGeneration() != epoch.g2ContentGeneration()
                || environment.epoch() != epoch.environmentEpoch()) {
            throw new IllegalArgumentException("G3 source inputs do not match their epoch");
        }

        this.header.fill((byte) 0);
        this.header.set(LE_INT, 0L, ABI_VERSION);
        this.header.set(LE_INT, 4L, HEADER_BYTES);
        this.header.set(LE_LONG, 8L, epoch.g2WorldGeneration());
        this.header.set(LE_LONG, 16L, epoch.g2ClipmapGeneration());
        this.header.set(LE_LONG, 24L, epoch.g2PaletteGeneration());
        this.header.set(LE_LONG, 32L, epoch.g2ContentGeneration());
        this.header.set(LE_LONG, 40L, epoch.staticLightRegistryEpoch());
        this.header.set(LE_LONG, 48L, epoch.environmentEpoch());
        for (int index = 0; index < 9; index++) {
            this.header.set(LE_INT, 56L + (long) index * Integer.BYTES,
                    field.originComponent(index));
        }
        float sunEnabled = environment.directionalRed() + environment.directionalGreen()
                + environment.directionalBlue() > 0.0F ? 1.0F : 0.0F;
        putFloat4(this.header, 96L, environment.toLightX(), environment.toLightY(),
                environment.toLightZ(), sunEnabled);
        putFloat4(this.header, 112L, environment.directionalRed(),
                environment.directionalGreen(), environment.directionalBlue(), sunEnabled);
        float skyEnabled = environment.skyRed() + environment.skyGreen()
                + environment.skyBlue() > 0.0F ? 1.0F : 0.0F;
        putFloat4(this.header, 128L, environment.skyRed(), environment.skyGreen(),
                environment.skyBlue(), skyEnabled);
        this.header.set(LE_INT, 144L, dirtyBrickCount);

        int totalSources = 0;
        for (int batchIndex = 0; batchIndex < dirtyBrickCount; batchIndex++) {
            int brickId = dirtyBricks[batchIndex];
            GiDirectSourceLayout.validateBrickId(brickId);
            int cascade = GiDirectSourceLayout.cascadeForBrickId(brickId);
            int brickX = GiDirectSourceLayout.brickX(brickId);
            int brickY = GiDirectSourceLayout.brickY(brickId);
            int brickZ = GiDirectSourceLayout.brickZ(brickId);
            int originIndex = cascade * 3;
            int originX = field.originComponent(originIndex);
            int originY = field.originComponent(originIndex + 1);
            int originZ = field.originComponent(originIndex + 2);
            int minX = GiDirectSourceLayout.brickMinWorldBlock(cascade, originX, brickX);
            int minY = GiDirectSourceLayout.brickMinWorldBlock(cascade, originY, brickY);
            int minZ = GiDirectSourceLayout.brickMinWorldBlock(cascade, originZ, brickZ);
            int span = GiDirectSourceLayout.BRICK_EDGE_CELLS
                    * GiDirectSourceLayout.cellSizeBlocks(cascade);
            int selected = registry.copyStaticSourcesForGi(
                    epoch.staticLightWorld(),
                    minX, minY, minZ, (double) minX + span, (double) minY + span,
                    (double) minZ + span,
                    sourceScratch, 0, GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK
            );

            long brickOffset = (long) batchIndex * BRICK_BYTES;
            this.bricks.asSlice(brickOffset, BRICK_BYTES).fill((byte) 0);
            this.bricks.set(LE_INT, brickOffset, cascade);
            this.bricks.set(LE_INT, brickOffset + 4L, brickX);
            this.bricks.set(LE_INT, brickOffset + 8L, brickY);
            this.bricks.set(LE_INT, brickOffset + 12L, brickZ);
            this.bricks.set(LE_INT, brickOffset + 16L, totalSources);
            this.bricks.set(LE_INT, brickOffset + 20L, selected);
            long stamp = field.brickContentStamp(brickId);
            if (stamp <= 0L) {
                throw new IllegalStateException("G3 dirty brick has no accepted content stamp");
            }
            this.bricks.set(LE_LONG, brickOffset + 24L, stamp);
            field.copyBrick(
                    brickId,
                    this.cells,
                    (long) batchIndex * GiSemanticDirectFieldView.CELLS_PER_BRICK * CELL_BYTES,
                    CELL_BYTES
            );

            for (int sourceIndex = 0; sourceIndex < selected; sourceIndex++) {
                AdvancedLight source = sourceScratch[sourceIndex];
                long sourceOffset = (long) totalSources * SOURCE_BYTES;
                putFloat4(this.sources, sourceOffset,
                        (float) (source.x() - originX),
                        (float) (source.y() - originY),
                        (float) (source.z() - originZ),
                        source.radius());
                putFloat4(this.sources, sourceOffset + 16L,
                        source.red(), source.green(), source.blue(), source.intensity());
                totalSources++;
            }
        }
        this.header.set(LE_INT, 148L, totalSources);
        return new PreparedBatch(
                this.header,
                this.bricks.asSlice(0L, (long) dirtyBrickCount * BRICK_BYTES),
                this.cells.asSlice(0L, (long) dirtyBrickCount
                        * GiSemanticDirectFieldView.CELLS_PER_BRICK * CELL_BYTES),
                this.sources.asSlice(0L, (long) totalSources * SOURCE_BYTES),
                dirtyBrickCount,
                totalSources
        );
    }

    public int encode(
            final MemorySegment commandBuffer,
            final MemorySegment fence,
            final PreparedBatch batch
    ) {
        assertUsable();
        Objects.requireNonNull(batch, "batch");
        return MetalNativeBridge.metallum_gi_direct_source_encode_dirty_v1(
                this.context, commandBuffer, fence,
                batch.header(), batch.bricks(), batch.cells(), batch.sources());
    }

    public int reset(final GiDirectSourceEpoch epoch) {
        assertUsable();
        Objects.requireNonNull(epoch, "epoch");
        return MetalNativeBridge.metallum_gi_direct_source_reset_v1(
                this.context,
                epoch.g2WorldGeneration(), epoch.g2ClipmapGeneration(),
                epoch.g2PaletteGeneration(), epoch.g2ContentGeneration(),
                epoch.staticLightRegistryEpoch(), epoch.environmentEpoch()
        );
    }

    public boolean awaitReady(final long timeoutMillis) {
        assertUsable();
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("G3 await timeout must be positive");
        }
        return MetalNativeBridge.metallum_gi_direct_source_await_ready_v1(
                this.context, timeoutMillis) == STATUS_OK;
    }

    public Stats stats() {
        assertUsable();
        this.stats.fill((byte) 0);
        int status = MetalNativeBridge.metallum_gi_direct_source_get_stats_v1(
                this.context, this.stats, this.stats.byteSize());
        if (status != STATUS_OK) {
            throw new IllegalStateException("Native G3 stats query failed: " + status);
        }
        return new Stats(
                this.stats.get(LE_INT, 0L) == 1,
                this.stats.get(LE_INT, 4L) == 1,
                this.stats.get(LE_LONG, 8L), this.stats.get(LE_LONG, 16L),
                this.stats.get(LE_LONG, 24L), this.stats.get(LE_LONG, 32L),
                this.stats.get(LE_LONG, 40L), this.stats.get(LE_LONG, 48L),
                this.stats.get(LE_LONG, 56L), this.stats.get(LE_LONG, 64L),
                this.stats.get(LE_LONG, 72L), this.stats.get(LE_LONG, 80L),
                this.stats.get(LE_LONG, 88L), this.stats.get(LE_LONG, 96L),
                this.stats.get(LE_LONG, 104L), this.stats.get(LE_LONG, 112L),
                this.stats.get(LE_LONG, 120L), this.stats.get(LE_LONG, 128L),
                this.stats.get(LE_LONG, 136L), this.stats.get(LE_LONG, 144L),
                this.stats.get(LE_INT, 152L), this.stats.get(LE_INT, 156L),
                this.stats.get(LE_INT, 160L)
        );
    }

    public int publishScheduler(final GiDirectDirtyQueue.Telemetry telemetry) {
        assertUsable();
        Objects.requireNonNull(telemetry, "telemetry");
        return MetalNativeBridge.metallum_gi_direct_source_publish_scheduler_v1(
                this.context,
                telemetry.queued(), telemetry.completed(), telemetry.discarded(), telemetry.pending(),
                telemetry.fullVolumeRebuilds()
        );
    }

    /** Opaque native owner passed only to the separately gated frozen G4 build. */
    MemorySegment transportContextHandle() {
        assertUsable();
        return this.context;
    }

    /** Test/debug-only raw slice; never used by the frame loop. */
    public @Nullable Capture captureSliceOnce(final int cascade, final int slice) {
        assertUsable();
        if (cascade < 0 || cascade >= GiDirectSourceLayout.CASCADE_COUNT
                || slice < 0 || slice >= GiDirectSourceLayout.CELLS_PER_AXIS) {
            throw new IllegalArgumentException("G3 capture slice is outside the field");
        }
        try (Arena captureArena = Arena.ofConfined()) {
            MemorySegment direct = captureArena.allocate(CAPTURE_DIRECT_BYTES, Long.BYTES);
            MemorySegment geometry = captureArena.allocate(CAPTURE_GEOMETRY_BYTES, Long.BYTES);
            int status = MetalNativeBridge.metallum_gi_direct_source_capture_slice_once_v1(
                    this.context, cascade, slice, direct, geometry);
            if (status != STATUS_OK) {
                return null;
            }
            short[] directArray = new short[CAPTURE_DIRECT_BYTES / Short.BYTES];
            byte[] geometryArray = new byte[CAPTURE_GEOMETRY_BYTES];
            MemorySegment.copy(direct, 0L, MemorySegment.ofArray(directArray), 0L,
                    CAPTURE_DIRECT_BYTES);
            MemorySegment.copy(geometry, 0L, MemorySegment.ofArray(geometryArray), 0L,
                    CAPTURE_GEOMETRY_BYTES);
            return new Capture(directArray, geometryArray);
        }
    }

    @Override
    public void close() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return;
        }
        assertOwnerThread();
        MemorySegment stale = this.context;
        this.context = MemorySegment.NULL;
        this.deferredRelease.accept(stale);
        this.arena.close();
    }

    private static void putFloat4(
            final MemorySegment destination,
            final long offset,
            final float x, final float y, final float z, final float w
    ) {
        destination.set(LE_FLOAT, offset, x);
        destination.set(LE_FLOAT, offset + 4L, y);
        destination.set(LE_FLOAT, offset + 8L, z);
        destination.set(LE_FLOAT, offset + 12L, w);
    }

    private void assertUsable() {
        assertOwnerThread();
        if (MetalNativeBridge.isNullHandle(this.context)) {
            throw new IllegalStateException("G3 direct-source resources are closed");
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G3 direct-source resources are render-thread confined");
        }
    }
}
