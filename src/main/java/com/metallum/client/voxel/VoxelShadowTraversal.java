package com.metallum.client.voxel;

import com.metallum.client.renderer.LocalVoxelShadowLayout;

import java.util.Objects;

/**
 * Pure CPU reference for L6 local-light visibility. It intentionally returns fully visible for
 * every malformed, stale or incomplete L5 view: production shaders must preserve this policy.
 */
public final class VoxelShadowTraversal {
    public record Point(double x, double y, double z) {
    }

    /** The native L5 metadata tuple is logical brick xyz plus a non-zero content stamp. */
    public record BrickMetadata(long logicalBrickX, long logicalBrickY, long logicalBrickZ, int contentStamp) {
        public boolean matches(final long x, final long y, final long z) {
            return contentStamp != 0 && logicalBrickX == x && logicalBrickY == y && logicalBrickZ == z;
        }
    }

    /** One CPU mirror of an exact L5 level: X-fastest occupancy, optical, chromatic and shape planes. */
    public record LevelData(
            VoxelClipmapSnapshot snapshot,
            int levelIndex,
            int[] occupancyWords,
            byte[] opticalBytes,
            byte[] chromaticBytes,
            short[] shapeProxyIds,
            BrickMetadata[] metadata
    ) {
        public LevelData {
            Objects.requireNonNull(snapshot, "snapshot");
            if (levelIndex < 0 || levelIndex >= snapshot.levels().size()) {
                throw new IllegalArgumentException("L6 level is not present in its L5 snapshot");
            }
            occupancyWords = occupancyWords == null ? null : occupancyWords.clone();
            opticalBytes = opticalBytes == null ? null : opticalBytes.clone();
            chromaticBytes = chromaticBytes == null ? null : chromaticBytes.clone();
            shapeProxyIds = shapeProxyIds == null ? null : shapeProxyIds.clone();
            metadata = metadata == null ? null : metadata.clone();
        }

        public LevelData(
                final VoxelClipmapSnapshot snapshot,
                final int levelIndex,
                final int[] occupancyWords,
                final byte[] opticalBytes,
                final byte[] chromaticBytes,
                final BrickMetadata[] metadata
        ) {
            this(
                    snapshot,
                    levelIndex,
                    occupancyWords,
                    opticalBytes,
                    chromaticBytes,
                    new short[opticalBytes == null ? 0 : opticalBytes.length],
                    metadata
            );
        }

        /** Legacy test/reference constructor: absent colour data is explicitly neutral. */
        public LevelData(
                final VoxelClipmapSnapshot snapshot,
                final int levelIndex,
                final int[] occupancyWords,
                final byte[] opticalBytes,
                final BrickMetadata[] metadata
        ) {
            this(
                    snapshot,
                    levelIndex,
                    occupancyWords,
                    opticalBytes,
                    VoxelChromaticFilter.neutralPackedValues(
                            opticalBytes == null ? 0 : opticalBytes.length
                    ),
                    new short[opticalBytes == null ? 0 : opticalBytes.length],
                    metadata
            );
        }
    }

    /** Scene-linear RGB transmission returned by the chromatic L6 reference traversal. */
    public record RgbVisibility(float red, float green, float blue) {
        private static final RgbVisibility VISIBLE = new RgbVisibility(1.0f, 1.0f, 1.0f);
        private static final RgbVisibility BLOCKED = new RgbVisibility(0.0f, 0.0f, 0.0f);

        public RgbVisibility {
            if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)
                    || red < 0.0f || red > 1.0f
                    || green < 0.0f || green > 1.0f
                    || blue < 0.0f || blue > 1.0f) {
                throw new IllegalArgumentException("L6 RGB visibility must be finite UNORM");
            }
        }

        /** Conservative scalar projection for callers that only understand a float. */
        public float minimum() {
            return Math.min(this.red, Math.min(this.green, this.blue));
        }
    }

    private VoxelShadowTraversal() {
    }

    /**
     * Selects the finest containing level whose conservative Manhattan cell-crossing count fits
     * the hard DDA budget. A negative result means that the shader must fail open.
     */
    public static int selectFinestFittingLevel(
            final VoxelClipmapSnapshot snapshot,
            final Point receiver,
            final Point light,
            final int maxSteps
    ) {
        if (snapshot == null || receiver == null || light == null
                || !finite(receiver) || !finite(light)
                || maxSteps < 1 || maxSteps > LocalVoxelShadowLayout.MAX_DDA_STEPS) {
            return -1;
        }
        for (int index = 0; index < snapshot.levels().size(); index++) {
            VoxelClipmapSnapshot.Level level = snapshot.levels().get(index);
            if (inside(level, receiver) && inside(level, light)
                    && conservativeCrossings(level, receiver, light) <= maxSteps) {
                return index;
            }
        }
        return -1;
    }

    public static float visibility(
            final LevelData data,
            final VoxelWorldToken expectedWorld,
            final long expectedClipmapGeneration,
            final Point receiver,
            final Point light,
            final int maxSteps
    ) {
        return visibilityRgb(
                data, expectedWorld, expectedClipmapGeneration, receiver, light, maxSteps
        ).minimum();
    }

    public static RgbVisibility visibilityRgb(
            final LevelData data,
            final VoxelWorldToken expectedWorld,
            final long expectedClipmapGeneration,
            final Point receiver,
            final Point light,
            final int maxSteps
    ) {
        if (data == null || expectedWorld == null || receiver == null || light == null
                || maxSteps < 1 || maxSteps > LocalVoxelShadowLayout.MAX_DDA_STEPS
                || !finite(receiver) || !finite(light)
                || !data.snapshot().world().equals(expectedWorld)
                || data.snapshot().clipmapGeneration() != expectedClipmapGeneration) {
            return RgbVisibility.VISIBLE;
        }
        VoxelClipmapSnapshot.Level level = data.snapshot().levels().get(data.levelIndex());
        if (!validStructure(data, level) || !inside(level, receiver) || !inside(level, light)) {
            return RgbVisibility.VISIBLE;
        }
        return traverse(data, level, receiver, light, maxSteps);
    }

    private static RgbVisibility traverse(
            final LevelData data,
            final VoxelClipmapSnapshot.Level level,
            final Point receiver,
            final Point light,
            final int maxSteps
    ) {
        int scale = level.subdivision();
        double startX = receiver.x() * scale;
        double startY = receiver.y() * scale;
        double startZ = receiver.z() * scale;
        double endX = light.x() * scale;
        double endY = light.y() * scale;
        double endZ = light.z() * scale;
        double directionX = endX - startX;
        double directionY = endY - startY;
        double directionZ = endZ - startZ;
        if (!Double.isFinite(directionX) || !Double.isFinite(directionY) || !Double.isFinite(directionZ)
                || (directionX == 0.0 && directionY == 0.0 && directionZ == 0.0)) {
            return RgbVisibility.VISIBLE;
        }

        startX = Math.nextAfter(startX, endX);
        startY = Math.nextAfter(startY, endY);
        startZ = Math.nextAfter(startZ, endZ);
        endX = Math.nextAfter(endX, startX);
        endY = Math.nextAfter(endY, startY);
        endZ = Math.nextAfter(endZ, startZ);
        long cellX = floorToLong(startX);
        long cellY = floorToLong(startY);
        long cellZ = floorToLong(startZ);
        long endCellX = floorToLong(endX);
        long endCellY = floorToLong(endY);
        long endCellZ = floorToLong(endZ);
        if (cellX == Long.MIN_VALUE || cellY == Long.MIN_VALUE || cellZ == Long.MIN_VALUE
                || endCellX == Long.MIN_VALUE || endCellY == Long.MIN_VALUE || endCellZ == Long.MIN_VALUE) {
            return RgbVisibility.VISIBLE;
        }

        int stepX = directionX > 0.0 ? 1 : directionX < 0.0 ? -1 : 0;
        int stepY = directionY > 0.0 ? 1 : directionY < 0.0 ? -1 : 0;
        int stepZ = directionZ > 0.0 ? 1 : directionZ < 0.0 ? -1 : 0;
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(directionX);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(directionY);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(directionZ);
        double nextX = nextBoundary(startX, cellX, directionX);
        double nextY = nextBoundary(startY, cellY, directionY);
        double nextZ = nextBoundary(startZ, cellZ, directionZ);
        float red = 1.0f;
        float green = 1.0f;
        float blue = 1.0f;
        long lastOpticalBlockX = Long.MIN_VALUE;
        long lastOpticalBlockY = Long.MIN_VALUE;
        long lastOpticalBlockZ = Long.MIN_VALUE;

        for (int step = 0; step < maxSteps; step++) {
            Sample sample = sample(data, level, cellX, cellY, cellZ);
            if (sample == null) {
                return RgbVisibility.VISIBLE;
            }
            long blockX = Math.floorDiv(cellX, scale);
            long blockY = Math.floorDiv(cellY, scale);
            long blockZ = Math.floorDiv(cellZ, scale);
            if (sample.occupied && (blockX != lastOpticalBlockX
                    || blockY != lastOpticalBlockY || blockZ != lastOpticalBlockZ)) {
                lastOpticalBlockX = blockX;
                lastOpticalBlockY = blockY;
                lastOpticalBlockZ = blockZ;
                boolean hit = true;
                if (sample.shapeProxyId > 0) {
                    VoxelShapeRegistry.ShapeProxy proxy = VoxelShapeRegistry.get(sample.shapeProxyId);
                    if (proxy != null) {
                        double localStartX = receiver.x() - blockX;
                        double localStartY = receiver.y() - blockY;
                        double localStartZ = receiver.z() - blockZ;
                        double localDeltaX = light.x() - receiver.x();
                        double localDeltaY = light.y() - receiver.y();
                        double localDeltaZ = light.z() - receiver.z();
                        double uHit = proxy.intersectSegment(
                                localStartX, localStartY, localStartZ,
                                localDeltaX, localDeltaY, localDeltaZ
                        );
                        if (uHit < 0.0) {
                            hit = false;
                        }
                    }
                }
                if (hit) {
                    red *= sample.transmittance * sample.red;
                    green *= sample.transmittance * sample.green;
                    blue *= sample.transmittance * sample.blue;
                    if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)) {
                        return RgbVisibility.VISIBLE;
                    }
                    if (red <= 0.0f && green <= 0.0f && blue <= 0.0f) {
                        return RgbVisibility.BLOCKED;
                    }
                }
            }
            if (cellX == endCellX && cellY == endCellY && cellZ == endCellZ) {
                return new RgbVisibility(red, green, blue);
            }
            if (nextX <= nextY && nextX <= nextZ) {
                cellX += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                cellY += stepY;
                nextY += deltaY;
            } else {
                cellZ += stepZ;
                nextZ += deltaZ;
            }
            if (!Double.isFinite(nextX) && !Double.isFinite(nextY) && !Double.isFinite(nextZ)) {
                return RgbVisibility.VISIBLE;
            }
        }
        return RgbVisibility.VISIBLE;
    }

    private static Sample sample(
            final LevelData data,
            final VoxelClipmapSnapshot.Level level,
            final long cellX,
            final long cellY,
            final long cellZ
    ) {
        int scale = level.subdivision();
        long blockX = Math.floorDiv(cellX, scale);
        long blockY = Math.floorDiv(cellY, scale);
        long blockZ = Math.floorDiv(cellZ, scale);
        int brickBlockEdge = VoxelBrickPatch.LOGICAL_EDGE / scale;
        long logicalBrickX = Math.floorDiv(blockX, brickBlockEdge);
        long logicalBrickY = Math.floorDiv(blockY, brickBlockEdge);
        long logicalBrickZ = Math.floorDiv(blockZ, brickBlockEdge);
        int dimension = level.brickDimension();
        int physicalBrickX = Math.floorMod(logicalBrickX, dimension);
        int physicalBrickY = Math.floorMod(logicalBrickY, dimension);
        int physicalBrickZ = Math.floorMod(logicalBrickZ, dimension);
        int metadataIndex = physicalBrickX + dimension * (physicalBrickY + dimension * physicalBrickZ);
        BrickMetadata tag = data.metadata()[metadataIndex];
        if (tag == null || !tag.matches(logicalBrickX, logicalBrickY, logicalBrickZ)) {
            return null;
        }
        int edge = level.logicalEdge();
        int physicalCellX = Math.floorMod(cellX, edge);
        int physicalCellY = Math.floorMod(cellY, edge);
        int physicalCellZ = Math.floorMod(cellZ, edge);
        long bitIndex = physicalCellX + (long) edge * (physicalCellY + (long) edge * physicalCellZ);
        int word = data.occupancyWords()[(int) (bitIndex >>> 5)];
        if ((word & (1 << ((int) bitIndex & 31))) == 0) {
            return Sample.EMPTY;
        }
        int blockEdge = edge / scale;
        int opticalX = Math.floorMod(blockX, blockEdge);
        int opticalY = Math.floorMod(blockY, blockEdge);
        int opticalZ = Math.floorMod(blockZ, blockEdge);
        int opticalIndex = opticalX + blockEdge * (opticalY + blockEdge * opticalZ);
        float transmittance = VoxelMaterialDescriptor.fromPackedUnsignedByte(
                Byte.toUnsignedInt(data.opticalBytes()[opticalIndex])
        ).transmittance();
        if (!Float.isFinite(transmittance)
                || opticalIndex >= data.chromaticBytes().length * 2) {
            return null;
        }
        int chromaticId = VoxelChromaticFilter.packedId(data.chromaticBytes(), opticalIndex);
        int shapeProxyId = data.shapeProxyIds() != null && opticalIndex < data.shapeProxyIds().length
                ? data.shapeProxyIds()[opticalIndex] : 0;
        return new Sample(
                true,
                shapeProxyId,
                transmittance,
                VoxelChromaticFilter.red(chromaticId),
                VoxelChromaticFilter.green(chromaticId),
                VoxelChromaticFilter.blue(chromaticId)
        );
    }

    private static boolean validStructure(final LevelData data, final VoxelClipmapSnapshot.Level level) {
        long cells = (long) level.logicalEdge() * level.logicalEdge() * level.logicalEdge();
        long blocks = (long) (level.logicalEdge() / level.subdivision())
                * (level.logicalEdge() / level.subdivision()) * (level.logicalEdge() / level.subdivision());
        long bricks = (long) level.brickDimension() * level.brickDimension() * level.brickDimension();
        return cells <= (long) Integer.MAX_VALUE * 32L
                && blocks <= Integer.MAX_VALUE && bricks <= Integer.MAX_VALUE
                && data.occupancyWords() != null && data.occupancyWords().length == (cells + 31L) / 32L
                && data.opticalBytes() != null && data.opticalBytes().length == blocks
                && data.chromaticBytes() != null
                && data.chromaticBytes().length == VoxelChromaticFilter.packedBytesFor(
                Math.toIntExact(blocks))
                && data.metadata() != null && data.metadata().length == bricks;
    }

    private static boolean inside(final VoxelClipmapSnapshot.Level level, final Point point) {
        int brickBlockEdge = VoxelBrickPatch.LOGICAL_EDGE / level.subdivision();
        double minX = (double) level.originBrickX() * brickBlockEdge;
        double minY = (double) level.originBrickY() * brickBlockEdge;
        double minZ = (double) level.originBrickZ() * brickBlockEdge;
        double span = (double) level.brickDimension() * brickBlockEdge;
        return point.x() >= minX && point.x() < minX + span
                && point.y() >= minY && point.y() < minY + span
                && point.z() >= minZ && point.z() < minZ + span;
    }

    private static boolean finite(final Point point) {
        return Double.isFinite(point.x()) && Double.isFinite(point.y()) && Double.isFinite(point.z());
    }

    private static long conservativeCrossings(
            final VoxelClipmapSnapshot.Level level,
            final Point receiver,
            final Point light
    ) {
        long startX = floorToLong(receiver.x() * level.subdivision());
        long startY = floorToLong(receiver.y() * level.subdivision());
        long startZ = floorToLong(receiver.z() * level.subdivision());
        long endX = floorToLong(light.x() * level.subdivision());
        long endY = floorToLong(light.y() * level.subdivision());
        long endZ = floorToLong(light.z() * level.subdivision());
        if (startX == Long.MIN_VALUE || startY == Long.MIN_VALUE || startZ == Long.MIN_VALUE
                || endX == Long.MIN_VALUE || endY == Long.MIN_VALUE || endZ == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        try {
            return Math.addExact(
                    Math.addExact(
                            positiveDifference(endX, startX),
                            positiveDifference(endY, startY)
                    ),
                    positiveDifference(endZ, startZ)
                    );
        } catch (ArithmeticException failure) {
            return Long.MAX_VALUE;
        }
    }

    private static long positiveDifference(final long left, final long right) {
        long difference = Math.subtractExact(left, right);
        if (difference == Long.MIN_VALUE) {
            throw new ArithmeticException("cell delta is not representable");
        }
        return Math.abs(difference);
    }

    private static double nextBoundary(final double coordinate, final long cell, final double direction) {
        if (direction == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = direction > 0.0 ? cell + 1.0 : cell;
        double value = (boundary - coordinate) / direction;
        return value < 0.0 || !Double.isFinite(value) ? Double.POSITIVE_INFINITY : value;
    }

    private static long floorToLong(final double value) {
        if (!Double.isFinite(value) || value < Long.MIN_VALUE || value >= Long.MAX_VALUE) {
            return Long.MIN_VALUE;
        }
        return (long) Math.floor(value);
    }

    private record Sample(boolean occupied, int shapeProxyId, float transmittance, float red, float green, float blue) {
        private static final Sample EMPTY = new Sample(false, 0, 1.0f, 1.0f, 1.0f, 1.0f);
    }
}
