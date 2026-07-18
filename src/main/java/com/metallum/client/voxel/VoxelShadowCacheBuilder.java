package com.metallum.client.voxel;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.renderer.LocalVoxelShadowLayout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure CPU builder for the bounded, update-driven L6 point-shadow cube cache. */
public final class VoxelShadowCacheBuilder {
    private static final double DIRECTION_EPSILON = 1.0e-12;

    public record Result(
            byte[] payload,
            int raysWithHits,
            int totalRays,
            List<Integer> cacheLevels
    ) {
        public Result {
            payload = payload.clone();
            cacheLevels = List.copyOf(cacheLevels);
            if (raysWithHits < 0 || totalRays < 0 || raysWithHits > totalRays) {
                throw new IllegalArgumentException("Invalid L6 cache build counters");
            }
        }
    }

    private VoxelShadowCacheBuilder() {
    }

    public static Result build(
            final VoxelShadowCacheMirror.Snapshot snapshot,
            final List<AdvancedLight> lights,
            final int lightCapacity,
            final int maxSteps
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<AdvancedLight> capturedLights = List.copyOf(
                Objects.requireNonNull(lights, "lights")
        );
        if (lightCapacity < 1
                || lightCapacity > LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS
                || capturedLights.size() > lightCapacity
                || maxSteps < 1 || maxSteps > LocalVoxelShadowLayout.MAX_DDA_STEPS) {
            throw new IllegalArgumentException("L6 cache build exceeds its fixed bounds");
        }

        byte[] payload = visiblePayload(lightCapacity);
        ByteBuffer output = ByteBuffer.wrap(payload).order(ByteOrder.nativeOrder());
        int hitRays = 0;
        int totalRays = 0;
        Integer[] selectedLevels = new Integer[capturedLights.size()];
        int edge = LocalVoxelShadowLayout.CACHE_FACE_EDGE;
        for (int lightIndex = 0; lightIndex < capturedLights.size(); lightIndex++) {
            AdvancedLight light = capturedLights.get(lightIndex);
            int levelIndex = selectCacheLevel(snapshot.clipmap(), light, maxSteps);
            selectedLevels[lightIndex] = levelIndex;
            VoxelClipmapSnapshot.Level level = levelIndex < 0
                    ? null : snapshot.clipmap().levels().get(levelIndex);
            for (int face = 0; face < LocalVoxelShadowLayout.CACHE_FACE_COUNT; face++) {
                for (int y = 0; y < edge; y++) {
                    for (int x = 0; x < edge; x++) {
                        Direction direction = cubeDirection(face, x, y, edge);
                        Trace trace = trace(snapshot, light, level, direction, maxSteps);
                        totalRays++;
                        if (!trace.valid || trace.count == 0) {
                            continue;
                        }
                        hitRays++;
                        int base = entryOffset(lightIndex, face, x, y, 0);
                        for (int layer = 0; layer < trace.count; layer++) {
                            output.putFloat(
                                    base + layer * LocalVoxelShadowLayout.CACHE_HIT_STRIDE_BYTES,
                                    trace.distances[layer]
                            );
                            output.putFloat(
                                    base + layer * LocalVoxelShadowLayout.CACHE_HIT_STRIDE_BYTES
                                            + Float.BYTES,
                                    trace.visibility[layer]
                            );
                        }
                    }
                }
            }
        }
        return new Result(payload, hitRays, totalRays, Arrays.asList(selectedLevels));
    }

    /**
     * Selects one L5 resolution for the whole point-shadow cube. Mixing resolutions between
     * directions creates visible conical seams where the DDA step budget changes level.
     */
    public static int selectCacheLevel(
            final VoxelClipmapSnapshot snapshot,
            final AdvancedLight light,
            final int maxSteps
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(light, "light");
        if (maxSteps < 1 || maxSteps > LocalVoxelShadowLayout.MAX_DDA_STEPS) {
            throw new IllegalArgumentException("L6 cache step cap is outside its fixed bounds");
        }
        double radius = light.radius();
        if (!(radius > 0.0) || !Double.isFinite(radius)
                || !finite(light.x(), light.y(), light.z())) {
            return -1;
        }
        for (int index = 0; index < snapshot.levels().size(); index++) {
            VoxelClipmapSnapshot.Level level = snapshot.levels().get(index);
            if (containsSphere(level, light)
                    && worstCaseSphereCrossings(level, radius) <= maxSteps) {
                return index;
            }
        }
        return -1;
    }

    public static byte[] visiblePayload(final int lightCapacity) {
        int bytes = Math.toIntExact(LocalVoxelShadowLayout.cacheBytes(lightCapacity));
        byte[] payload = new byte[bytes];
        ByteBuffer output = ByteBuffer.wrap(payload).order(ByteOrder.nativeOrder());
        for (int offset = 0; offset < bytes;
             offset += LocalVoxelShadowLayout.CACHE_HIT_STRIDE_BYTES) {
            output.putFloat(offset, Float.POSITIVE_INFINITY);
            output.putFloat(offset + Float.BYTES, 1.0f);
        }
        return payload;
    }

    /**
     * Exact geometry comparison for cache invalidation. Global L5 revisions may advance for
     * unrelated clipmap bricks; only occupancy/material changes inside a selected light sphere
     * can change its cached point shadow.
     */
    public static boolean relevantGeometryEquals(
            final VoxelShadowCacheMirror.Snapshot left,
            final VoxelShadowCacheMirror.Snapshot right,
            final List<AdvancedLight> lights
    ) {
        if (left == null || right == null || lights == null
                || !left.clipmap().world().equals(right.clipmap().world())
                || left.clipmap().clipmapGeneration()
                != right.clipmap().clipmapGeneration()
                || !left.clipmap().levels().equals(right.clipmap().levels())) {
            return false;
        }
        List<AdvancedLight> capturedLights = List.copyOf(lights);
        for (AdvancedLight light : capturedLights) {
            double radius = light.radius();
            if (!(radius > 0.0) || !Double.isFinite(radius)
                    || !finite(light.x(), light.y(), light.z())) {
                return false;
            }
            for (VoxelClipmapSnapshot.Level level : left.clipmap().levels()) {
                int brickBlockEdge = VoxelBrickPatch.LOGICAL_EDGE / level.subdivision();
                int minimumX = floorToInt((light.x() - radius) / brickBlockEdge);
                int minimumY = floorToInt((light.y() - radius) / brickBlockEdge);
                int minimumZ = floorToInt((light.z() - radius) / brickBlockEdge);
                int maximumX = floorToInt(
                        Math.nextAfter(light.x() + radius, Double.NEGATIVE_INFINITY)
                                / brickBlockEdge
                );
                int maximumY = floorToInt(
                        Math.nextAfter(light.y() + radius, Double.NEGATIVE_INFINITY)
                                / brickBlockEdge
                );
                int maximumZ = floorToInt(
                        Math.nextAfter(light.z() + radius, Double.NEGATIVE_INFINITY)
                                / brickBlockEdge
                );
                if (minimumX == Integer.MIN_VALUE || minimumY == Integer.MIN_VALUE
                        || minimumZ == Integer.MIN_VALUE || maximumX == Integer.MIN_VALUE
                        || maximumY == Integer.MIN_VALUE || maximumZ == Integer.MIN_VALUE
                        || (long) maximumX - minimumX >= level.brickDimension()
                        || (long) maximumY - minimumY >= level.brickDimension()
                        || (long) maximumZ - minimumZ >= level.brickDimension()) {
                    return false;
                }
                for (int logicalZ = minimumZ; logicalZ <= maximumZ; logicalZ++) {
                    for (int logicalY = minimumY; logicalY <= maximumY; logicalY++) {
                        for (int logicalX = minimumX; logicalX <= maximumX; logicalX++) {
                            VoxelShadowCacheMirror.Brick leftBrick = resolveBrick(
                                    left, level, logicalX, logicalY, logicalZ
                            );
                            VoxelShadowCacheMirror.Brick rightBrick = resolveBrick(
                                    right, level, logicalX, logicalY, logicalZ
                            );
                            if (!sameGeometry(leftBrick, rightBrick)) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    private static VoxelShadowCacheMirror.Brick resolveBrick(
            final VoxelShadowCacheMirror.Snapshot snapshot,
            final VoxelClipmapSnapshot.Level level,
            final int logicalX,
            final int logicalY,
            final int logicalZ
    ) {
        int dimension = level.brickDimension();
        VoxelShadowCacheMirror.Brick brick = snapshot.bricks().get(
                new VoxelShadowCacheMirror.Key(
                        level.level(),
                        Math.floorMod(logicalX, dimension),
                        Math.floorMod(logicalY, dimension),
                        Math.floorMod(logicalZ, dimension)
                )
        );
        return brick != null && brick.logicalX() == logicalX
                && brick.logicalY() == logicalY && brick.logicalZ() == logicalZ
                ? brick : null;
    }

    private static boolean sameGeometry(
            final VoxelShadowCacheMirror.Brick left,
            final VoxelShadowCacheMirror.Brick right
    ) {
        return left == right || left != null && right != null
                && Arrays.equals(left.occupancy(), right.occupancy())
                && Arrays.equals(left.optical(), right.optical());
    }

    private static Trace trace(
            final VoxelShadowCacheMirror.Snapshot snapshot,
            final AdvancedLight light,
            final VoxelClipmapSnapshot.Level level,
            final Direction direction,
            final int maxSteps
    ) {
        double radius = light.radius();
        if (level == null || !(radius > 0.0) || !Double.isFinite(radius)) {
            return Trace.INVALID;
        }
        Point source = new Point(light.x(), light.y(), light.z());
        Point target = new Point(
                light.x() + direction.x * radius,
                light.y() + direction.y * radius,
                light.z() + direction.z * radius
        );
        double voxelSize = 1.0 / level.subdivision();
        double startDistance = Math.min(voxelSize * 0.08, radius * 0.02);
        Point start = new Point(
                source.x + direction.x * startDistance,
                source.y + direction.y * startDistance,
                source.z + direction.z * startDistance
        );
        if (!inside(level, start)) {
            return Trace.INVALID;
        }
        return traverse(
                snapshot.bricks(), level, light, start, target,
                direction, startDistance, radius, maxSteps
        );
    }

    private static Trace traverse(
            final Map<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> bricks,
            final VoxelClipmapSnapshot.Level level,
            final AdvancedLight light,
            final Point start,
            final Point end,
            final Direction direction,
            final double startDistance,
            final double radius,
            final int maxSteps
    ) {
        int scale = level.subdivision();
        double startX = start.x * scale;
        double startY = start.y * scale;
        double startZ = start.z * scale;
        double endX = end.x * scale;
        double endY = end.y * scale;
        double endZ = end.z * scale;
        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double deltaZ = endZ - startZ;
        if (!finite(deltaX, deltaY, deltaZ)) {
            return Trace.INVALID;
        }

        int cellX = floorToInt(startX);
        int cellY = floorToInt(startY);
        int cellZ = floorToInt(startZ);
        int endCellX = floorToInt(Math.nextAfter(endX, startX));
        int endCellY = floorToInt(Math.nextAfter(endY, startY));
        int endCellZ = floorToInt(Math.nextAfter(endZ, startZ));
        if (cellX == Integer.MIN_VALUE || cellY == Integer.MIN_VALUE
                || cellZ == Integer.MIN_VALUE || endCellX == Integer.MIN_VALUE
                || endCellY == Integer.MIN_VALUE || endCellZ == Integer.MIN_VALUE) {
            return Trace.INVALID;
        }

        int stepX = Double.compare(deltaX, 0.0);
        int stepY = Double.compare(deltaY, 0.0);
        int stepZ = Double.compare(deltaZ, 0.0);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(deltaX);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(deltaY);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(deltaZ);
        double tMaxX = nextBoundary(startX, cellX, deltaX);
        double tMaxY = nextBoundary(startY, cellY, deltaY);
        double tMaxZ = nextBoundary(startZ, cellZ, deltaZ);
        int lastBlockX = Integer.MIN_VALUE;
        int lastBlockY = Integer.MIN_VALUE;
        int lastBlockZ = Integer.MIN_VALUE;
        float cumulativeVisibility = 1.0f;
        float[] distances = new float[LocalVoxelShadowLayout.CACHE_LAYER_COUNT];
        float[] visibility = new float[LocalVoxelShadowLayout.CACHE_LAYER_COUNT];
        int hitCount = 0;
        double entryT = 0.0;
        BrickCursor brickCursor = new BrickCursor();

        for (int step = 0; step < maxSteps; step++) {
            if (cellX == endCellX && cellY == endCellY && cellZ == endCellZ) {
                return new Trace(true, hitCount, distances, visibility);
            }
            int blockX = Math.floorDiv(cellX, scale);
            int blockY = Math.floorDiv(cellY, scale);
            int blockZ = Math.floorDiv(cellZ, scale);
            Sample sample = sample(bricks, level, brickCursor, cellX, cellY, cellZ);
            if (!sample.valid) {
                return Trace.INVALID;
            }
            boolean emitterBlock = sample.occupied
                    && light.emitsFromBlock(blockX, blockY, blockZ);
            if (!emitterBlock && sample.occupied
                    && (blockX != lastBlockX || blockY != lastBlockY
                    || blockZ != lastBlockZ)) {
                cumulativeVisibility *= sample.transmittance;
                if (!Float.isFinite(cumulativeVisibility)) {
                    return Trace.INVALID;
                }
                float hitDistance = (float) Math.max(
                        0.0,
                        startDistance + entryT * (radius - startDistance)
                );
                if (hitCount < LocalVoxelShadowLayout.CACHE_LAYER_COUNT) {
                    distances[hitCount] = hitDistance;
                    visibility[hitCount] = cumulativeVisibility;
                    hitCount++;
                } else {
                    visibility[LocalVoxelShadowLayout.CACHE_LAYER_COUNT - 1]
                            = cumulativeVisibility;
                }
                lastBlockX = blockX;
                lastBlockY = blockY;
                lastBlockZ = blockZ;
                if (cumulativeVisibility <= 0.0f) {
                    return new Trace(true, hitCount, distances, visibility);
                }
            }

            double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (!Double.isFinite(next) || next > 1.0) {
                return new Trace(true, hitCount, distances, visibility);
            }
            double tie = next + 1.0e-10;
            if (tMaxX <= tie) {
                cellX += stepX;
                tMaxX += tDeltaX;
            }
            if (tMaxY <= tie) {
                cellY += stepY;
                tMaxY += tDeltaY;
            }
            if (tMaxZ <= tie) {
                cellZ += stepZ;
                tMaxZ += tDeltaZ;
            }
            entryT = next;
            if (cellX == endCellX && cellY == endCellY && cellZ == endCellZ) {
                return new Trace(true, hitCount, distances, visibility);
            }
        }
        return Trace.INVALID;
    }

    private static Sample sample(
            final Map<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> bricks,
            final VoxelClipmapSnapshot.Level level,
            final BrickCursor cursor,
            final int cellX,
            final int cellY,
            final int cellZ
    ) {
        int scale = level.subdivision();
        int blockX = Math.floorDiv(cellX, scale);
        int blockY = Math.floorDiv(cellY, scale);
        int blockZ = Math.floorDiv(cellZ, scale);
        int brickBlockEdge = VoxelBrickPatch.LOGICAL_EDGE / scale;
        int logicalBrickX = Math.floorDiv(blockX, brickBlockEdge);
        int logicalBrickY = Math.floorDiv(blockY, brickBlockEdge);
        int logicalBrickZ = Math.floorDiv(blockZ, brickBlockEdge);
        int dimension = level.brickDimension();
        VoxelShadowCacheMirror.Brick brick = cursor.resolve(
                bricks,
                level.level(),
                logicalBrickX,
                logicalBrickY,
                logicalBrickZ,
                dimension
        );
        if (brick == null || brick.contentStamp() == 0
                || brick.logicalX() != logicalBrickX
                || brick.logicalY() != logicalBrickY
                || brick.logicalZ() != logicalBrickZ) {
            return Sample.INVALID;
        }
        int localCellX = Math.floorMod(cellX, VoxelBrickPatch.LOGICAL_EDGE);
        int localCellY = Math.floorMod(cellY, VoxelBrickPatch.LOGICAL_EDGE);
        int localCellZ = Math.floorMod(cellZ, VoxelBrickPatch.LOGICAL_EDGE);
        int word = brick.occupancy()[localCellZ * VoxelBrickPatch.LOGICAL_EDGE + localCellY];
        if ((word & (1 << localCellX)) == 0) {
            return Sample.EMPTY;
        }
        int localBlockX = Math.floorMod(blockX, brickBlockEdge);
        int localBlockY = Math.floorMod(blockY, brickBlockEdge);
        int localBlockZ = Math.floorMod(blockZ, brickBlockEdge);
        int opticalIndex = (localBlockZ * brickBlockEdge + localBlockY)
                * brickBlockEdge + localBlockX;
        byte[] optical = brick.optical();
        if (opticalIndex < 0 || opticalIndex >= optical.length) {
            return Sample.INVALID;
        }
        try {
            VoxelMaterialDescriptor material = VoxelMaterialDescriptor.fromPackedUnsignedByte(
                    Byte.toUnsignedInt(optical[opticalIndex])
            );
            if (material.materialClass() == VoxelMaterialClass.AIR) {
                return Sample.INVALID;
            }
            float transmittance = material.transmittance();
            return Float.isFinite(transmittance)
                    ? new Sample(true, true, transmittance) : Sample.INVALID;
        } catch (IllegalArgumentException failure) {
            return Sample.INVALID;
        }
    }

    private static boolean inside(final VoxelClipmapSnapshot.Level level, final Point point) {
        int brickBlockEdge = VoxelBrickPatch.LOGICAL_EDGE / level.subdivision();
        double minimumX = (double) level.originBrickX() * brickBlockEdge;
        double minimumY = (double) level.originBrickY() * brickBlockEdge;
        double minimumZ = (double) level.originBrickZ() * brickBlockEdge;
        double span = (double) level.brickDimension() * brickBlockEdge;
        return point.x >= minimumX && point.x < minimumX + span
                && point.y >= minimumY && point.y < minimumY + span
                && point.z >= minimumZ && point.z < minimumZ + span;
    }

    private static boolean containsSphere(
            final VoxelClipmapSnapshot.Level level,
            final AdvancedLight light
    ) {
        int brickBlockEdge = VoxelBrickPatch.LOGICAL_EDGE / level.subdivision();
        double minimumX = (double) level.originBrickX() * brickBlockEdge;
        double minimumY = (double) level.originBrickY() * brickBlockEdge;
        double minimumZ = (double) level.originBrickZ() * brickBlockEdge;
        double span = (double) level.brickDimension() * brickBlockEdge;
        double radius = light.radius();
        return light.x() - radius >= minimumX && light.x() + radius < minimumX + span
                && light.y() - radius >= minimumY && light.y() + radius < minimumY + span
                && light.z() - radius >= minimumZ && light.z() + radius < minimumZ + span;
    }

    private static long worstCaseSphereCrossings(
            final VoxelClipmapSnapshot.Level level,
            final double radius
    ) {
        double continuous = radius * level.subdivision() * Math.sqrt(3.0);
        if (!Double.isFinite(continuous) || continuous > Long.MAX_VALUE - 3.0) {
            return Long.MAX_VALUE;
        }
        // Each floored endpoint can add at most one crossing per world axis.
        return (long) Math.ceil(continuous) + 3L;
    }

    private static Direction cubeDirection(
            final int face,
            final int x,
            final int y,
            final int edge
    ) {
        double u = 2.0 * (x + 0.5) / edge - 1.0;
        double v = 2.0 * (y + 0.5) / edge - 1.0;
        double rawX;
        double rawY;
        double rawZ;
        switch (face) {
            case 0 -> { rawX = 1.0; rawY = -v; rawZ = -u; }
            case 1 -> { rawX = -1.0; rawY = -v; rawZ = u; }
            case 2 -> { rawX = u; rawY = 1.0; rawZ = v; }
            case 3 -> { rawX = u; rawY = -1.0; rawZ = -v; }
            case 4 -> { rawX = u; rawY = -v; rawZ = 1.0; }
            case 5 -> { rawX = -u; rawY = -v; rawZ = -1.0; }
            default -> throw new IllegalArgumentException("Invalid L6 cache cube face");
        }
        double length = Math.sqrt(rawX * rawX + rawY * rawY + rawZ * rawZ);
        if (!(length > DIRECTION_EPSILON) || !Double.isFinite(length)) {
            throw new IllegalStateException("Invalid L6 cache cube direction");
        }
        return new Direction(rawX / length, rawY / length, rawZ / length);
    }

    private static int entryOffset(
            final int light,
            final int face,
            final int x,
            final int y,
            final int layer
    ) {
        long edge = LocalVoxelShadowLayout.CACHE_FACE_EDGE;
        long entry = (((long) light * LocalVoxelShadowLayout.CACHE_FACE_COUNT + face)
                * edge * edge + (long) y * edge + x)
                * LocalVoxelShadowLayout.CACHE_LAYER_COUNT + layer;
        return Math.toIntExact(entry * LocalVoxelShadowLayout.CACHE_HIT_STRIDE_BYTES);
    }

    private static double nextBoundary(
            final double coordinate,
            final int cell,
            final double delta
    ) {
        if (delta == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = delta > 0.0 ? cell + 1.0 : cell;
        double value = (boundary - coordinate) / delta;
        return value >= 0.0 && Double.isFinite(value)
                ? value : Double.POSITIVE_INFINITY;
    }

    private static int floorToInt(final double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE
                || value >= Integer.MAX_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) Math.floor(value);
    }

    private static boolean finite(final double x, final double y, final double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private record Point(double x, double y, double z) {
    }

    private record Direction(double x, double y, double z) {
    }

    private record Sample(boolean valid, boolean occupied, float transmittance) {
        private static final Sample INVALID = new Sample(false, false, 1.0f);
        private static final Sample EMPTY = new Sample(true, false, 1.0f);
    }

    /** Avoids allocating and hashing one toroidal key for every DDA cell. */
    private static final class BrickCursor {
        private int level = Integer.MIN_VALUE;
        private int logicalX;
        private int logicalY;
        private int logicalZ;
        private VoxelShadowCacheMirror.Brick brick;

        private VoxelShadowCacheMirror.Brick resolve(
                final Map<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> bricks,
                final int requestedLevel,
                final int requestedLogicalX,
                final int requestedLogicalY,
                final int requestedLogicalZ,
                final int dimension
        ) {
            if (this.level != requestedLevel
                    || this.logicalX != requestedLogicalX
                    || this.logicalY != requestedLogicalY
                    || this.logicalZ != requestedLogicalZ) {
                this.level = requestedLevel;
                this.logicalX = requestedLogicalX;
                this.logicalY = requestedLogicalY;
                this.logicalZ = requestedLogicalZ;
                this.brick = bricks.get(new VoxelShadowCacheMirror.Key(
                        requestedLevel,
                        Math.floorMod(requestedLogicalX, dimension),
                        Math.floorMod(requestedLogicalY, dimension),
                        Math.floorMod(requestedLogicalZ, dimension)
                ));
            }
            return this.brick;
        }
    }

    private static final class Trace {
        private static final Trace INVALID = new Trace(
                false, 0,
                new float[LocalVoxelShadowLayout.CACHE_LAYER_COUNT],
                new float[LocalVoxelShadowLayout.CACHE_LAYER_COUNT]
        );

        private final boolean valid;
        private final int count;
        private final float[] distances;
        private final float[] visibility;

        private Trace(
                final boolean valid,
                final int count,
                final float[] distances,
                final float[] visibility
        ) {
            this.valid = valid;
            this.count = count;
            this.distances = distances;
            this.visibility = visibility;
        }
    }
}
