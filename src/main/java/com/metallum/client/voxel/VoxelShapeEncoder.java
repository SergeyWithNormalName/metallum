package com.metallum.client.voxel;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Converts a Minecraft {@link VoxelShape} into conservative local sub-block occupancy.
 *
 * <p>An occupancy bit is set for every subcell with positive-volume intersection. Coverage is
 * quantized upward, so a visible thin element cannot disappear because of quantization. The
 * aggregate optical byte is the future per-world-block payload; per-cell coverage/optical
 * values remain available for pure Java reference validation.</p>
 */
public final class VoxelShapeEncoder {
    public static final int MAX_SUBDIVISION = 4;
    private static final double EPSILON = 1.0e-12;

    private VoxelShapeEncoder() {
    }

    public static EncodedShape encode(
            final VoxelShape shape,
            final VoxelSubdivision subdivision,
            final VoxelMaterialDescriptor material
    ) {
        Objects.requireNonNull(material, "material");
        Scratch scratch = new Scratch();
        encodeInto(shape, subdivision, scratch);
        int cells = subdivision.cellCount();
        byte[] coverageBytes = new byte[cells];
        byte[] opticalBytes = new byte[cells];
        for (int index = 0; index < cells; index++) {
            double fraction = Math.min(1.0d, scratch.coverage[index]);
            if (fraction <= EPSILON) {
                continue;
            }
            coverageBytes[index] = (byte) quantizeUp(fraction);
            opticalBytes[index] = (byte) quantizeUp(fraction * material.opacity());
        }
        return new EncodedShape(
                subdivision,
                material,
                scratch.occupancyMask,
                scratch.shapeProxyId,
                coverageBytes,
                opticalBytes,
                scratch.coverageByte,
                quantizeUp(scratch.aggregateCoverage * material.opacity())
        );
    }

    /**
     * Allocation-bounded encoder used by Sodium section workers. The result remains in the
     * caller-owned scratch object and is overwritten by the next call.
     */
    static void encodeInto(
            final VoxelShape shape,
            final VoxelSubdivision subdivision,
            final Scratch scratch
    ) {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(subdivision, "subdivision");
        Objects.requireNonNull(scratch, "scratch");

        int cells = subdivision.cellCount();
        scratch.reset(cells);
        if (shape.isEmpty()) {
            return;
        }
        if (shape == Shapes.block()) {
            Arrays.fill(scratch.coverage, 0, cells, 1.0d);
            scratch.occupancyMask = subdivision.fullMask();
            scratch.aggregateCoverage = 1.0d;
            scratch.coverageByte = 255;
            return;
        }
        shape.forAllBoxes(scratch);
        if (scratch.boxCount != 0) {
            for (int boxIndex = 0; boxIndex < scratch.boxCount; boxIndex++) {
                rasterize(scratch, boxIndex, subdivision);
            }
            if (!allAlignedToQuarter(scratch)) {
                List<VoxelShapeRegistry.Box> registryBoxes = new ArrayList<>(scratch.boxCount);
                for (int boxIndex = 0; boxIndex < scratch.boxCount; boxIndex++) {
                    int base = boxIndex * Scratch.BOX_COMPONENTS;
                    registryBoxes.add(new VoxelShapeRegistry.Box(
                            (float) scratch.boxBounds[base],
                            (float) scratch.boxBounds[base + 1],
                            (float) scratch.boxBounds[base + 2],
                            (float) scratch.boxBounds[base + 3],
                            (float) scratch.boxBounds[base + 4],
                            (float) scratch.boxBounds[base + 5]
                    ));
                }
                scratch.shapeProxyId = VoxelShapeRegistry.register(registryBoxes);
            }
        }

        double aggregateCoverage = 0.0d;
        for (int index = 0; index < cells; index++) {
            double fraction = Math.min(1.0d, scratch.coverage[index]);
            aggregateCoverage += fraction;
            if (fraction > EPSILON) {
                scratch.occupancyMask |= 1L << index;
            }
        }
        scratch.aggregateCoverage = aggregateCoverage / cells;
        scratch.coverageByte = quantizeUp(scratch.aggregateCoverage);
    }

    /** Compatibility shortcut for callers that only need conservative opaque occupancy. */
    public static long encode(final VoxelShape shape, final int subdivision) {
        return encode(
                shape,
                VoxelSubdivision.fromScale(subdivision),
                VoxelMaterialDescriptor.defaults(VoxelMaterialClass.OPAQUE)
        ).occupancyMask();
    }

    public static long encode4(final VoxelShape shape) {
        return encode(shape, MAX_SUBDIVISION);
    }

    /** Returns a conservative lower-resolution view of a stored 4x X-fastest mask. */
    public static boolean isOccupiedAt(
            final long fourByFourMask,
            final int subdivision,
            final int x,
            final int y,
            final int z
    ) {
        VoxelSubdivision requested = VoxelSubdivision.fromScale(subdivision);
        int fineSpan = MAX_SUBDIVISION / requested.scale();
        requested.cellIndex(x, y, z);
        for (int fineZ = z * fineSpan; fineZ < (z + 1) * fineSpan; fineZ++) {
            for (int fineY = y * fineSpan; fineY < (y + 1) * fineSpan; fineY++) {
                for (int fineX = x * fineSpan; fineX < (x + 1) * fineSpan; fineX++) {
                    int bit = VoxelSubdivision.FOUR.cellIndex(fineX, fineY, fineZ);
                    if ((fourByFourMask & (1L << bit)) != 0L) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean allAlignedToQuarter(final Scratch scratch) {
        for (int boxIndex = 0; boxIndex < scratch.boxCount; boxIndex++) {
            int base = boxIndex * Scratch.BOX_COMPONENTS;
            if (!isQuarterMultiple(scratch.boxBounds[base])
                    || !isQuarterMultiple(scratch.boxBounds[base + 1])
                    || !isQuarterMultiple(scratch.boxBounds[base + 2])
                    || !isQuarterMultiple(scratch.boxBounds[base + 3])
                    || !isQuarterMultiple(scratch.boxBounds[base + 4])
                    || !isQuarterMultiple(scratch.boxBounds[base + 5])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isQuarterMultiple(final double value) {
        double scaled = value * 4.0;
        return Math.abs(scaled - Math.round(scaled)) < 1.0e-5;
    }

    private static void rasterize(
            final Scratch scratch,
            final int boxIndex,
            final VoxelSubdivision subdivision
    ) {
        int base = boxIndex * Scratch.BOX_COMPONENTS;
        double minXValue = scratch.boxBounds[base];
        double minYValue = scratch.boxBounds[base + 1];
        double minZValue = scratch.boxBounds[base + 2];
        double maxXValue = scratch.boxBounds[base + 3];
        double maxYValue = scratch.boxBounds[base + 4];
        double maxZValue = scratch.boxBounds[base + 5];
        int scale = subdivision.scale();
        double cellLength = 1.0d / scale;
        double cellVolume = cellLength * cellLength * cellLength;
        int minX = firstCell(minXValue, scale);
        int minY = firstCell(minYValue, scale);
        int minZ = firstCell(minZValue, scale);
        int maxX = lastCell(maxXValue, scale);
        int maxY = lastCell(maxYValue, scale);
        int maxZ = lastCell(maxZValue, scale);
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    double volume = intersectionLength(minXValue, maxXValue, x * cellLength,
                            (x + 1) * cellLength)
                            * intersectionLength(minYValue, maxYValue, y * cellLength,
                            (y + 1) * cellLength)
                            * intersectionLength(minZValue, maxZValue, z * cellLength,
                            (z + 1) * cellLength);
                    if (volume > EPSILON) {
                        int index = subdivision.cellIndex(x, y, z);
                        scratch.coverage[index] = Math.min(
                                1.0d,
                                scratch.coverage[index] + volume / cellVolume
                        );
                    }
                }
            }
        }
    }

    private static int firstCell(final double minimum, final int scale) {
        return clamp((int) Math.floor(minimum * scale), 0, scale - 1);
    }

    private static int lastCell(final double maximum, final int scale) {
        return clamp((int) Math.ceil(maximum * scale) - 1, 0, scale - 1);
    }

    private static int clamp(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double intersectionLength(
            final double aMin,
            final double aMax,
            final double bMin,
            final double bMax
    ) {
        return Math.max(0.0d, Math.min(aMax, bMax) - Math.max(aMin, bMin));
    }

    private static int quantizeUp(final double normalized) {
        if (normalized <= EPSILON) {
            return 0;
        }
        return Math.min(255, Math.max(1, (int) Math.ceil(normalized * 255.0d - EPSILON)));
    }

    /** Immutable encoded output. The constructor takes ownership of freshly allocated arrays. */
    public static final class EncodedShape {
        private final VoxelSubdivision subdivision;
        private final VoxelMaterialDescriptor material;
        private final long occupancyMask;
        private final int shapeProxyId;
        private final byte[] coverageBytes;
        private final byte[] opticalBytes;
        private final int coverageByte;
        private final int opticalByte;

        private EncodedShape(
                final VoxelSubdivision subdivision,
                final VoxelMaterialDescriptor material,
                final long occupancyMask,
                final int shapeProxyId,
                final byte[] coverageBytes,
                final byte[] opticalBytes,
                final int coverageByte,
                final int opticalByte
        ) {
            this.subdivision = subdivision;
            this.material = material;
            this.occupancyMask = occupancyMask;
            this.shapeProxyId = shapeProxyId;
            this.coverageBytes = coverageBytes;
            this.opticalBytes = opticalBytes;
            this.coverageByte = coverageByte;
            this.opticalByte = opticalByte;
        }

        public VoxelSubdivision subdivision() {
            return this.subdivision;
        }

        public VoxelMaterialDescriptor material() {
            return this.material;
        }

        public long occupancyMask() {
            return this.occupancyMask;
        }

        public int shapeProxyId() {
            return this.shapeProxyId;
        }

        public boolean occupied(final int x, final int y, final int z) {
            return (this.occupancyMask & (1L << this.subdivision.cellIndex(x, y, z))) != 0L;
        }

        /** Aggregate block coverage packed for the one-byte future optical payload. */
        public int coverageByte() {
            return this.coverageByte;
        }

        /** Aggregate coverage multiplied by material opacity, never a forced opaque glass value. */
        public int opticalByte() {
            return this.opticalByte;
        }

        public int coverageByte(final int cellIndex) {
            checkIndex(cellIndex);
            return Byte.toUnsignedInt(this.coverageBytes[cellIndex]);
        }

        public int opticalByte(final int cellIndex) {
            checkIndex(cellIndex);
            return Byte.toUnsignedInt(this.opticalBytes[cellIndex]);
        }

        public byte[] coverageBytes() {
            return this.coverageBytes.clone();
        }

        public byte[] opticalBytes() {
            return this.opticalBytes.clone();
        }

        public int occupiedCellCount() {
            return Long.bitCount(this.occupancyMask);
        }

        private void checkIndex(final int cellIndex) {
            if (cellIndex < 0 || cellIndex >= this.subdivision.cellCount()) {
                throw new IndexOutOfBoundsException("Voxel cell index outside encoded shape: " + cellIndex);
            }
        }
    }

    static final class Scratch implements Shapes.DoubleLineConsumer {
        private static final int BOX_COMPONENTS = 6;
        private static final int INITIAL_BOX_CAPACITY = 16;

        private final double[] coverage = new double[VoxelSubdivision.FOUR.cellCount()];
        private double[] boxBounds = new double[INITIAL_BOX_CAPACITY * BOX_COMPONENTS];
        private int boxCount;
        private long occupancyMask;
        private int shapeProxyId;
        private double aggregateCoverage;
        private int coverageByte;

        long occupancyMask() {
            return this.occupancyMask;
        }

        int shapeProxyId() {
            return this.shapeProxyId;
        }

        int coverageByte() {
            return this.coverageByte;
        }

        private void reset(final int cells) {
            Arrays.fill(this.coverage, 0, cells, 0.0d);
            this.boxCount = 0;
            this.occupancyMask = 0L;
            this.shapeProxyId = VoxelShapeRegistry.FAST_PATH_ID;
            this.aggregateCoverage = 0.0d;
            this.coverageByte = 0;
        }

        @Override
        public void consume(
                final double minX,
                final double minY,
                final double minZ,
                final double maxX,
                final double maxY,
                final double maxZ
        ) {
            double clippedMinX = Math.max(0.0d, minX);
            double clippedMinY = Math.max(0.0d, minY);
            double clippedMinZ = Math.max(0.0d, minZ);
            double clippedMaxX = Math.min(1.0d, maxX);
            double clippedMaxY = Math.min(1.0d, maxY);
            double clippedMaxZ = Math.min(1.0d, maxZ);
            if (clippedMaxX - clippedMinX <= EPSILON
                    || clippedMaxY - clippedMinY <= EPSILON
                    || clippedMaxZ - clippedMinZ <= EPSILON) {
                return;
            }
            int base = this.boxCount * BOX_COMPONENTS;
            if (base == this.boxBounds.length) {
                this.boxBounds = Arrays.copyOf(this.boxBounds, this.boxBounds.length * 2);
            }
            this.boxBounds[base] = clippedMinX;
            this.boxBounds[base + 1] = clippedMinY;
            this.boxBounds[base + 2] = clippedMinZ;
            this.boxBounds[base + 3] = clippedMaxX;
            this.boxBounds[base + 4] = clippedMaxY;
            this.boxBounds[base + 5] = clippedMaxZ;
            this.boxCount++;
        }
    }
}
