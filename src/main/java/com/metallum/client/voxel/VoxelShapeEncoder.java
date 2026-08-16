package com.metallum.client.voxel;

import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
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
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(subdivision, "subdivision");
        Objects.requireNonNull(material, "material");

        int cells = subdivision.cellCount();
        double[] coverage = new double[cells];
        int shapeProxyId = VoxelShapeRegistry.FAST_PATH_ID;
        if (!shape.isEmpty()) {
            List<Box> boxes = new ArrayList<>();
            shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                Box clipped = Box.clipped(minX, minY, minZ, maxX, maxY, maxZ);
                if (clipped != null) {
                    boxes.add(clipped);
                }
            });
            for (Box box : boxes) {
                rasterize(box, subdivision, coverage);
            }
            if (!boxes.isEmpty() && !allAlignedToQuarter(boxes)) {
                List<VoxelShapeRegistry.Box> registryBoxes = new ArrayList<>(boxes.size());
                for (Box b : boxes) {
                    registryBoxes.add(new VoxelShapeRegistry.Box(
                            (float) b.minX, (float) b.minY, (float) b.minZ,
                            (float) b.maxX, (float) b.maxY, (float) b.maxZ
                    ));
                }
                shapeProxyId = VoxelShapeRegistry.register(registryBoxes);
            }
        }

        long occupancyMask = 0L;
        byte[] coverageBytes = new byte[cells];
        byte[] opticalBytes = new byte[cells];
        double aggregateCoverage = 0.0d;
        for (int index = 0; index < cells; index++) {
            double fraction = Math.min(1.0d, coverage[index]);
            aggregateCoverage += fraction;
            if (fraction <= EPSILON) {
                continue;
            }
            occupancyMask |= 1L << index;
            coverageBytes[index] = (byte) quantizeUp(fraction);
            opticalBytes[index] = (byte) quantizeUp(fraction * material.opacity());
        }
        aggregateCoverage /= cells;
        return new EncodedShape(
                subdivision,
                material,
                occupancyMask,
                shapeProxyId,
                coverageBytes,
                opticalBytes,
                quantizeUp(aggregateCoverage),
                quantizeUp(aggregateCoverage * material.opacity())
        );
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

    private static boolean allAlignedToQuarter(final List<Box> boxes) {
        for (Box b : boxes) {
            if (!isQuarterMultiple(b.minX) || !isQuarterMultiple(b.minY) || !isQuarterMultiple(b.minZ)
                    || !isQuarterMultiple(b.maxX) || !isQuarterMultiple(b.maxY) || !isQuarterMultiple(b.maxZ)) {
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
            final Box box,
            final VoxelSubdivision subdivision,
            final double[] coverage
    ) {
        int scale = subdivision.scale();
        double cellLength = 1.0d / scale;
        double cellVolume = cellLength * cellLength * cellLength;
        int minX = firstCell(box.minX, scale);
        int minY = firstCell(box.minY, scale);
        int minZ = firstCell(box.minZ, scale);
        int maxX = lastCell(box.maxX, scale);
        int maxY = lastCell(box.maxY, scale);
        int maxZ = lastCell(box.maxZ, scale);
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    double volume = intersectionLength(box.minX, box.maxX, x * cellLength,
                            (x + 1) * cellLength)
                            * intersectionLength(box.minY, box.maxY, y * cellLength,
                            (y + 1) * cellLength)
                            * intersectionLength(box.minZ, box.maxZ, z * cellLength,
                            (z + 1) * cellLength);
                    if (volume > EPSILON) {
                        int index = subdivision.cellIndex(x, y, z);
                        coverage[index] = Math.min(1.0d, coverage[index] + volume / cellVolume);
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

    /** Immutable encoded output. Arrays are copied at its boundary to preserve determinism. */
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
            this.coverageBytes = coverageBytes.clone();
            this.opticalBytes = opticalBytes.clone();
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

    private record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        private static Box clipped(
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
            return clippedMaxX - clippedMinX > EPSILON
                    && clippedMaxY - clippedMinY > EPSILON
                    && clippedMaxZ - clippedMinZ > EPSILON
                    ? new Box(clippedMinX, clippedMinY, clippedMinZ,
                    clippedMaxX, clippedMaxY, clippedMaxZ)
                    : null;
        }
    }
}
