package com.metallum.client.voxel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure L5 clipmap topology, resource accounting and toroidal-address helpers.
 *
 * <p>All levels use a fixed 32-cubed <em>logical voxel</em> brick. Thus a 4x level has an
 * 8-block world brick, a 2x level a 16-block world brick, and a 1x level a 32-block world
 * brick. The first implementation is deliberately dense and fixed-size: sparse residency is
 * not an L5 prerequisite. One optical byte plus a packed four-bit chromatic filter per world
 * block are budgeted alongside packed occupancy, so resource estimates cannot hide direct
 * transmittance/material storage.</p>
 */
public final class VoxelClipmapLayout {
    public static final int LOGICAL_BRICK_VOXEL_EDGE = 32;
    public static final int OCCUPANCY_WORDS_PER_BRICK = 1_024;
    public static final int OCCUPANCY_BYTES_PER_BRICK = OCCUPANCY_WORDS_PER_BRICK * Integer.BYTES;
    public static final int PACKET_HEADER_BYTES = 96;
    /** Native L5 ABI v2 patch record: header is 96 B and each patch record is 64 B. */
    public static final int PATCH_RECORD_BYTES = 64;
    public static final int LAYOUT_DESCRIPTOR_BYTES = 32;
    public static final int RING_SLOTS = 3;
    public static final int MAX_BRICKS_PER_SUBMIT = 8;
    /** Native {@code MetallumVoxelParamsV1}; one parameter block per level and ring side. */
    public static final int PARAMETER_BYTES_PER_LEVEL = 72;
    /** Native per-level parameter-buffer stride: 72 B ABI payload aligned to 256 B for Metal. */
    public static final int PARAMETER_STRIDE_BYTES_PER_LEVEL = 256;
    /** Native indirect-dispatch argument size; one indirect argument per level and ring side. */
    public static final int INDIRECT_ARGUMENT_BYTES_PER_LEVEL = 12;
    /** Native checksum scratch/readback allocation per ring side. */
    public static final int DEBUG_BYTES_PER_RING_SIDE = Integer.BYTES;
    /** Native persistent tag tuple: logical X/Y/Z plus non-zero content stamp. */
    public static final int METADATA_BYTES_PER_BRICK = 16;
    /** Native private buffers reserve this guard at the end of every persistent level resource. */
    public static final int PERSISTENT_RESOURCE_GUARD_BYTES = 64;

    private static final long MEBIBYTE = 1L << 20;

    public enum Preset {
        PERFORMANCE,
        BALANCED,
        ULTRA
    }

    public enum Axis {
        X,
        Y,
        Z
    }

    public enum Direction {
        NEGATIVE,
        POSITIVE
    }

    public record Origin(long x, long y, long z) {
    }

    /** A dense toroidal level whose world extent is measured in Minecraft blocks. */
    public record Level(int spanBlocks, VoxelSubdivision subdivision) {
        public Level {
            if (spanBlocks <= 0) {
                throw new IllegalArgumentException("Clipmap span must be positive");
            }
            Objects.requireNonNull(subdivision, "subdivision");
            int brickBlockEdge = LOGICAL_BRICK_VOXEL_EDGE / subdivision.scale();
            if (LOGICAL_BRICK_VOXEL_EDGE % subdivision.scale() != 0
                    || spanBlocks % brickBlockEdge != 0) {
                throw new IllegalArgumentException(
                        "Clipmap span must contain whole logical voxel bricks"
                );
            }
        }

        public int brickCountPerAxis() {
            return this.spanBlocks / brickBlockEdge();
        }

        public int brickBlockEdge() {
            return LOGICAL_BRICK_VOXEL_EDGE / this.subdivision.scale();
        }

        public int cellExtentPerAxis() {
            return Math.multiplyExact(this.spanBlocks, this.subdivision.scale());
        }

        /** Native layout descriptor's logicalEdge: world span expressed in voxel cells. */
        public int logicalEdge() {
            return cellExtentPerAxis();
        }

        public long cellCount() {
            long extent = cellExtentPerAxis();
            return Math.multiplyExact(Math.multiplyExact(extent, extent), extent);
        }

        public long occupancyBytes() {
            return divideRoundUp(cellCount(), Byte.SIZE);
        }

        public long materialBytes() {
            long span = this.spanBlocks;
            return Math.multiplyExact(Math.multiplyExact(span, span), span);
        }

        /** Two four-bit chromatic palette IDs per byte, one ID per world block. */
        public long chromaticBytes() {
            return VoxelChromaticFilter.packedBytesFor(Math.toIntExact(materialBytes()));
        }

        public long privateResourceBytes() {
            return Math.addExact(Math.addExact(occupancyBytes(), materialBytes()), chromaticBytes());
        }

        public int brickCellExtentPerAxis() {
            return LOGICAL_BRICK_VOXEL_EDGE;
        }

        public long fullBrickUploadBytes() {
            return Math.addExact(
                    Math.addExact(OCCUPANCY_BYTES_PER_BRICK, opticalBytesPerBrick()),
                    chromaticBytesPerBrick()
            );
        }

        public long opticalBytesPerBrick() {
            long blockEdge = brickBlockEdge();
            return Math.multiplyExact(Math.multiplyExact(blockEdge, blockEdge), blockEdge);
        }

        public long chromaticBytesPerBrick() {
            return VoxelChromaticFilter.packedBytesFor(
                    Math.toIntExact(opticalBytesPerBrick())
            );
        }
    }

    /** Incoming world-space slab after an aligned toroidal scroll. */
    public record Slab(Axis axis, Direction direction, long startBlock, int lengthBlocks) {
        public Slab {
            Objects.requireNonNull(axis, "axis");
            Objects.requireNonNull(direction, "direction");
            if (lengthBlocks <= 0) {
                throw new IllegalArgumentException("Incoming slab must contain a positive whole-brick length");
            }
        }
    }

    public record Scroll(Origin origin, boolean fullReset, List<Slab> incomingSlabs) {
        public Scroll {
            Objects.requireNonNull(origin, "origin");
            incomingSlabs = List.copyOf(Objects.requireNonNull(incomingSlabs, "incomingSlabs"));
            if (fullReset && !incomingSlabs.isEmpty()) {
                throw new IllegalArgumentException("Full clipmap reset cannot also report partial incoming slabs");
            }
        }
    }

    /**
     * Exact raw L5 buffer allocations plus bounded upload/queue limits for one preset.
     *
     * <p>Both upload sides own {@link #RING_SLOTS} independent slots: the shared staging
     * packet and its private payload mirror have the same byte size. Indirect arguments,
     * per-level parameters, and checksum scratch/readback exist on both sides and are
     * reported separately. Values deliberately exclude device-specific heap alignment slack,
     * but include every resource explicitly allocated by the native L5 context.</p>
     */
    public record Budget(
            Preset preset,
            List<Level> levels,
            int ringSlots,
            int maxBricksPerSubmit,
            int hardQueueCapacity,
            int hardDrainBudget,
            long starvationBoundTicks,
            long largestFullBrickUploadBytes,
            long occupancyBytes,
            long opticalBytes,
            long chromaticBytes,
            long metadataBytes,
            long sharedUploadRingBytes,
            long privatePatchRingBytes,
            long indirectBytes,
            long parameterBytes,
            long debugBytes,
            long totalDedicatedBytes,
            long hardResourceBudgetBytes
    ) {
        public Budget {
            Objects.requireNonNull(preset, "preset");
            levels = List.copyOf(Objects.requireNonNull(levels, "levels"));
            if (levels.isEmpty()) {
                throw new IllegalArgumentException("A clipmap budget needs at least one level");
            }
            if (ringSlots <= 0 || maxBricksPerSubmit <= 0 || hardQueueCapacity <= 0
                    || hardDrainBudget <= 0 || starvationBoundTicks <= 0L) {
                throw new IllegalArgumentException("Clipmap budgets must be positive");
            }
            if (hardDrainBudget > maxBricksPerSubmit) {
                throw new IllegalArgumentException("Drain budget must fit one staging-ring submit");
            }
            if (largestFullBrickUploadBytes <= 0L || occupancyBytes <= 0L || opticalBytes <= 0L
                    || chromaticBytes <= 0L
                    || metadataBytes <= 0L || sharedUploadRingBytes <= 0L
                    || privatePatchRingBytes <= 0L || indirectBytes <= 0L
                    || parameterBytes <= 0L || debugBytes <= 0L || totalDedicatedBytes <= 0L
                    || hardResourceBudgetBytes <= 0L || totalDedicatedBytes > hardResourceBudgetBytes) {
                throw new IllegalArgumentException("Clipmap resource budget is invalid or exceeds its hard cap");
            }
            long expectedTotal = Math.addExact(
                    Math.addExact(
                            Math.addExact(Math.addExact(occupancyBytes, opticalBytes), chromaticBytes),
                            metadataBytes
                    ),
                    Math.addExact(
                            Math.addExact(sharedUploadRingBytes, privatePatchRingBytes),
                            Math.addExact(Math.addExact(indirectBytes, parameterBytes), debugBytes)
                    )
            );
            if (totalDedicatedBytes != expectedTotal) {
                throw new IllegalArgumentException("Clipmap total must account for every native L5 allocation");
            }
        }

        /** Persistent occupancy plus optical and packed chromatic data, excluding tags. */
        public long privateResourceBytes() {
            return Math.addExact(Math.addExact(occupancyBytes, opticalBytes), chromaticBytes);
        }

        /** Backward-compatible name for the shared, CPU-visible upload packet ring. */
        public long uploadRingBytes() {
            return sharedUploadRingBytes;
        }

        public long persistentVoxelBytes() {
            return Math.addExact(
                    Math.addExact(Math.addExact(occupancyBytes, opticalBytes), chromaticBytes),
                    metadataBytes
            );
        }

        public long indirectParamsDebugOverheadBytes() {
            return Math.addExact(Math.addExact(indirectBytes, parameterBytes), debugBytes);
        }

        public long totalRingBytes() {
            return Math.addExact(sharedUploadRingBytes, privatePatchRingBytes);
        }
    }

    private VoxelClipmapLayout() {
    }

    /**
     * The Performance preset intentionally omits an optional 32-block 4x level: it has no
     * demonstrated quality need yet and would add another dense level and update source.
     */
    public static Budget forPreset(final Preset preset) {
        Objects.requireNonNull(preset, "preset");
        return switch (preset) {
            case PERFORMANCE -> budget(
                    preset,
                    List.of(new Level(64, VoxelSubdivision.TWO), new Level(128, VoxelSubdivision.ONE)),
                    2_048,
                    8,
                    120L,
                    16L * MEBIBYTE
            );
            case BALANCED -> budget(
                    preset,
                    List.of(
                            new Level(64, VoxelSubdivision.FOUR),
                            new Level(128, VoxelSubdivision.TWO),
                            new Level(256, VoxelSubdivision.ONE)
                    ),
                    4_096,
                    8,
                    180L,
                    64L * MEBIBYTE
            );
            case ULTRA -> budget(
                    preset,
                    List.of(
                            new Level(96, VoxelSubdivision.FOUR),
                            new Level(192, VoxelSubdivision.TWO),
                            new Level(384, VoxelSubdivision.ONE)
                    ),
                    8_192,
                    8,
                    240L,
                    128L * MEBIBYTE
            );
        };
    }

    public static Origin cameraCenteredOrigin(
            final Level level,
            final long cameraBlockX,
            final long cameraBlockY,
            final long cameraBlockZ
    ) {
        Objects.requireNonNull(level, "level");
        long halfSpan = level.spanBlocks() / 2L;
        long phase = scrollPhaseBlocks(level);
        return new Origin(
                alignDown(Math.addExact(Math.subtractExact(cameraBlockX, halfSpan), phase),
                        level.brickBlockEdge()),
                alignDown(Math.addExact(Math.subtractExact(cameraBlockY, halfSpan), phase),
                        level.brickBlockEdge()),
                alignDown(Math.addExact(Math.subtractExact(cameraBlockZ, halfSpan), phase),
                        level.brickBlockEdge())
        );
    }

    /**
     * Staggers nested toroidal scroll boundaries without changing their brick alignment.
     * Fine and coarse levels otherwise cross every common world-grid boundary together,
     * briefly leaving no complete level for a moving L6 hero light.
     */
    static int scrollPhaseBlocks(final Level level) {
        Objects.requireNonNull(level, "level");
        return level.subdivision() == VoxelSubdivision.FOUR
                ? 0 : level.brickBlockEdge() / 2;
    }

    /** Plans only newly exposed whole-brick slabs; a jump spanning one whole level resets it. */
    public static Scroll scroll(final Level level, final Origin previous, final Origin next) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        requireOriginAligned(level, previous);
        requireOriginAligned(level, next);
        long deltaX = Math.subtractExact(next.x(), previous.x());
        long deltaY = Math.subtractExact(next.y(), previous.y());
        long deltaZ = Math.subtractExact(next.z(), previous.z());
        long span = level.spanBlocks();
        if (deltaX <= -span || deltaX >= span
                || deltaY <= -span || deltaY >= span
                || deltaZ <= -span || deltaZ >= span) {
            return new Scroll(next, true, List.of());
        }
        List<Slab> slabs = new ArrayList<>(3);
        appendIncomingSlab(slabs, Axis.X, next.x(), deltaX, level.spanBlocks(), level.brickBlockEdge());
        appendIncomingSlab(slabs, Axis.Y, next.y(), deltaY, level.spanBlocks(), level.brickBlockEdge());
        appendIncomingSlab(slabs, Axis.Z, next.z(), deltaZ, level.spanBlocks(), level.brickBlockEdge());
        return new Scroll(next, false, slabs);
    }

    /** Stable physical torus coordinate, including negative and large Minecraft block positions. */
    public static int toroidalCellCoordinate(
            final Level level,
            final long worldBlockCoordinate,
            final int localSubcell
    ) {
        Objects.requireNonNull(level, "level");
        int scale = level.subdivision().scale();
        if (localSubcell < 0 || localSubcell >= scale) {
            throw new IndexOutOfBoundsException("Subcell coordinate outside subdivision: " + localSubcell);
        }
        long worldCell = Math.addExact(Math.multiplyExact(worldBlockCoordinate, scale), localSubcell);
        return Math.toIntExact(Math.floorMod(worldCell, (long) level.cellExtentPerAxis()));
    }

    /** X-fastest linear offset into one dense toroidal level. */
    public static long toroidalCellOffset(
            final Level level,
            final long worldBlockX,
            final long worldBlockY,
            final long worldBlockZ,
            final int subcellX,
            final int subcellY,
            final int subcellZ
    ) {
        Objects.requireNonNull(level, "level");
        long extent = level.cellExtentPerAxis();
        long x = toroidalCellCoordinate(level, worldBlockX, subcellX);
        long y = toroidalCellCoordinate(level, worldBlockY, subcellY);
        long z = toroidalCellCoordinate(level, worldBlockZ, subcellZ);
        return x + extent * (y + extent * z);
    }

    public static long brickCoordinate(final Level level, final long worldBlockCoordinate) {
        Objects.requireNonNull(level, "level");
        return Math.floorDiv(worldBlockCoordinate, level.brickBlockEdge());
    }

    private static Budget budget(
            final Preset preset,
            final List<Level> levels,
            final int hardQueueCapacity,
            final int hardDrainBudget,
            final long starvationBoundTicks,
            final long hardResourceBudgetBytes
    ) {
        long occupancyBytes = 0L;
        long opticalBytes = 0L;
        long chromaticBytes = 0L;
        long metadataBytes = 0L;
        long largestBrickUpload = 0L;
        for (Level level : levels) {
            occupancyBytes = Math.addExact(
                    occupancyBytes,
                    Math.addExact(level.occupancyBytes(), PERSISTENT_RESOURCE_GUARD_BYTES)
            );
            opticalBytes = Math.addExact(
                    opticalBytes,
                    Math.addExact(level.materialBytes(), PERSISTENT_RESOURCE_GUARD_BYTES)
            );
            chromaticBytes = Math.addExact(
                    chromaticBytes,
                    Math.addExact(level.chromaticBytes(), PERSISTENT_RESOURCE_GUARD_BYTES)
            );
            long brickCount = level.brickCountPerAxis();
            metadataBytes = Math.addExact(
                    metadataBytes,
                    Math.multiplyExact(
                            Math.multiplyExact(Math.multiplyExact(brickCount, brickCount), brickCount),
                            METADATA_BYTES_PER_BRICK
                    )
            );
            metadataBytes = Math.addExact(metadataBytes, PERSISTENT_RESOURCE_GUARD_BYTES);
            largestBrickUpload = Math.max(largestBrickUpload, level.fullBrickUploadBytes());
        }
        long ringSlotBytes = Math.addExact(
                PACKET_HEADER_BYTES,
                Math.addExact(
                        Math.multiplyExact((long) MAX_BRICKS_PER_SUBMIT, PATCH_RECORD_BYTES),
                        Math.multiplyExact(largestBrickUpload, MAX_BRICKS_PER_SUBMIT)
                )
        );
        long sharedUploadRingBytes = Math.multiplyExact(ringSlotBytes, RING_SLOTS);
        long privatePatchRingBytes = sharedUploadRingBytes;
        long indirectBytes = Math.multiplyExact(
                Math.multiplyExact(
                        Math.multiplyExact((long) RING_SLOTS, levels.size()),
                        INDIRECT_ARGUMENT_BYTES_PER_LEVEL
                ),
                2L
        );
        long parameterBytes = Math.multiplyExact(
                Math.multiplyExact(
                        Math.multiplyExact((long) RING_SLOTS, levels.size()),
                        PARAMETER_STRIDE_BYTES_PER_LEVEL
                ),
                2L
        );
        long debugBytes = Math.multiplyExact(
                Math.multiplyExact((long) RING_SLOTS, DEBUG_BYTES_PER_RING_SIDE),
                2L
        );
        long totalBytes = Math.addExact(
                Math.addExact(
                        Math.addExact(Math.addExact(occupancyBytes, opticalBytes), chromaticBytes),
                        metadataBytes
                ),
                Math.addExact(
                        Math.addExact(sharedUploadRingBytes, privatePatchRingBytes),
                        Math.addExact(Math.addExact(indirectBytes, parameterBytes), debugBytes)
                )
        );
        return new Budget(
                preset,
                levels,
                RING_SLOTS,
                MAX_BRICKS_PER_SUBMIT,
                hardQueueCapacity,
                hardDrainBudget,
                starvationBoundTicks,
                largestBrickUpload,
                occupancyBytes,
                opticalBytes,
                chromaticBytes,
                metadataBytes,
                sharedUploadRingBytes,
                privatePatchRingBytes,
                indirectBytes,
                parameterBytes,
                debugBytes,
                totalBytes,
                hardResourceBudgetBytes
        );
    }

    private static void appendIncomingSlab(
            final List<Slab> slabs,
            final Axis axis,
            final long nextOrigin,
            final long delta,
            final int span,
            final int brickBlockEdge
    ) {
        if (delta == 0L) {
            return;
        }
        long length = Math.abs(delta);
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Incoming clipmap slab is too large");
        }
        if (length % brickBlockEdge != 0L) {
            throw new IllegalArgumentException("Incoming clipmap slab does not have an integral brick length");
        }
        if (delta > 0L) {
            slabs.add(new Slab(axis, Direction.POSITIVE,
                    Math.addExact(Math.addExact(nextOrigin, span), -length), (int) length));
        } else {
            slabs.add(new Slab(axis, Direction.NEGATIVE, nextOrigin, (int) length));
        }
    }

    private static long alignDown(final long coordinate, final int alignment) {
        return Math.multiplyExact(
                Math.floorDiv(coordinate, alignment),
                alignment
        );
    }

    private static void requireOriginAligned(final Level level, final Origin origin) {
        int edge = level.brickBlockEdge();
        if (Math.floorMod(origin.x(), (long) edge) != 0L
                || Math.floorMod(origin.y(), (long) edge) != 0L
                || Math.floorMod(origin.z(), (long) edge) != 0L) {
            throw new IllegalArgumentException("Clipmap origin is not aligned to this level's world brick edge");
        }
    }

    private static long divideRoundUp(final long value, final long divisor) {
        return Math.addExact(value, divisor - 1L) / divisor;
    }
}
