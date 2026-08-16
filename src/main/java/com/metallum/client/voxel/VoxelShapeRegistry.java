package com.metallum.client.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deduplicated registry of refined geometric shape proxies for non-4x-aligned blocks.
 *
 * <p>ID 0 is reserved for {@link #FAST_PATH_ID} (no proxy, standard conservative voxel path).
 * Non-zero IDs index into deduplicated {@link ShapeProxy} definitions containing exact local
 * axis-aligned bounding boxes (AABBs) in {@code [0, 1]^3} block-local coordinates.</p>
 */
public final class VoxelShapeRegistry {
    public static final int FAST_PATH_ID = 0;
    public static final int MAX_PROXIES = 4096;
    public static final int MAX_BOXES_PER_PROXY = 16;
    private static final double EPSILON = 1.0e-6;

    public record Box(
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ
    ) {
        public Box {
            if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(minZ)
                    || !Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)
                    || minX < -0.01f || minY < -0.01f || minZ < -0.01f
                    || maxX > 1.01f || maxY > 1.01f || maxZ > 1.01f
                    || minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException(
                        "Invalid ShapeProxy Box bounds: [" + minX + "," + minY + "," + minZ
                                + "] to [" + maxX + "," + maxY + "," + maxZ + "]"
                );
            }
        }

        public boolean equalsEpsilon(final Box other) {
            if (other == null) return false;
            return Math.abs(this.minX - other.minX) < EPSILON
                    && Math.abs(this.minY - other.minY) < EPSILON
                    && Math.abs(this.minZ - other.minZ) < EPSILON
                    && Math.abs(this.maxX - other.maxX) < EPSILON
                    && Math.abs(this.maxY - other.maxY) < EPSILON
                    && Math.abs(this.maxZ - other.maxZ) < EPSILON;
        }

        public double intersectSegment(
                final double startX, final double startY, final double startZ,
                final double deltaX, final double deltaY, final double deltaZ
        ) {
            double uMin = 0.0;
            double uMax = 1.0;

            // X axis
            if (Math.abs(deltaX) > 1.0e-12) {
                double inv = 1.0 / deltaX;
                double u1 = (this.minX - startX) * inv;
                double u2 = (this.maxX - startX) * inv;
                double enter = Math.min(u1, u2);
                double exit = Math.max(u1, u2);
                uMin = Math.max(uMin, enter);
                uMax = Math.min(uMax, exit);
                if (uMin > uMax) return -1.0;
            } else if (startX < this.minX - 1.0e-7 || startX > this.maxX + 1.0e-7) {
                return -1.0;
            }

            // Y axis
            if (Math.abs(deltaY) > 1.0e-12) {
                double inv = 1.0 / deltaY;
                double u1 = (this.minY - startY) * inv;
                double u2 = (this.maxY - startY) * inv;
                double enter = Math.min(u1, u2);
                double exit = Math.max(u1, u2);
                uMin = Math.max(uMin, enter);
                uMax = Math.min(uMax, exit);
                if (uMin > uMax) return -1.0;
            } else if (startY < this.minY - 1.0e-7 || startY > this.maxY + 1.0e-7) {
                return -1.0;
            }

            // Z axis
            if (Math.abs(deltaZ) > 1.0e-12) {
                double inv = 1.0 / deltaZ;
                double u1 = (this.minZ - startZ) * inv;
                double u2 = (this.maxZ - startZ) * inv;
                double enter = Math.min(u1, u2);
                double exit = Math.max(u1, u2);
                uMin = Math.max(uMin, enter);
                uMax = Math.min(uMax, exit);
                if (uMin > uMax) return -1.0;
            } else if (startZ < this.minZ - 1.0e-7 || startZ > this.maxZ + 1.0e-7) {
                return -1.0;
            }

            return uMin;
        }
    }

    public static final class ShapeProxy {
        private final int id;
        private final List<Box> boxes;

        public ShapeProxy(final int id, final List<Box> boxes) {
            this.id = id;
            this.boxes = List.copyOf(Objects.requireNonNull(boxes, "boxes"));
            if (this.boxes.isEmpty()) {
                throw new IllegalArgumentException("ShapeProxy requires at least one box");
            }
            if (this.boxes.size() > MAX_BOXES_PER_PROXY) {
                throw new IllegalArgumentException(
                        "ShapeProxy exceeds max boxes: " + this.boxes.size()
                );
            }
        }

        public int id() {
            return this.id;
        }

        public List<Box> boxes() {
            return this.boxes;
        }

        public int boxCount() {
            return this.boxes.size();
        }

        /**
         * Tests whether a ray segment {@code start + u * delta} (for {@code u in [0, 1]}) intersects
         * any box of this proxy in block-local coordinates {@code [0, 1]^3}.
         *
         * @return lowest entry {@code u in [0, 1]}, or {@code -1.0} on miss.
         */
        public double intersectSegment(
                final double startX, final double startY, final double startZ,
                final double deltaX, final double deltaY, final double deltaZ
        ) {
            double bestU = Double.POSITIVE_INFINITY;
            for (int i = 0; i < this.boxes.size(); i++) {
                double u = this.boxes.get(i).intersectSegment(
                        startX, startY, startZ, deltaX, deltaY, deltaZ
                );
                if (u >= 0.0 && u < bestU) {
                    bestU = u;
                }
            }
            return Double.isInfinite(bestU) ? -1.0 : bestU;
        }
    }

    private static final class Key {
        private final Box[] boxes;
        private final int hash;

        private Key(final List<Box> boxList) {
            this.boxes = boxList.toArray(new Box[0]);
            int h = 1;
            for (Box b : this.boxes) {
                h = 31 * h + Math.round(b.minX * 1000.0f);
                h = 31 * h + Math.round(b.minY * 1000.0f);
                h = 31 * h + Math.round(b.minZ * 1000.0f);
                h = 31 * h + Math.round(b.maxX * 1000.0f);
                h = 31 * h + Math.round(b.maxY * 1000.0f);
                h = 31 * h + Math.round(b.maxZ * 1000.0f);
            }
            this.hash = h;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Key other)) return false;
            if (this.boxes.length != other.boxes.length) return false;
            for (int i = 0; i < this.boxes.length; i++) {
                if (!this.boxes[i].equalsEpsilon(other.boxes[i])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    private static final ConcurrentHashMap<Key, Integer> DEDUPLICATION_MAP = new ConcurrentHashMap<>();
    private static final List<ShapeProxy> PROXIES_BY_ID = new ArrayList<>(Collections.singletonList(null));
    private static final Object LOCK = new Object();

    private VoxelShapeRegistry() {
    }

    /**
     * Registers a deduplicated shape proxy definition from a list of local AABB boxes.
     *
     * @param boxes local AABBs in {@code [0, 1]^3} coordinates.
     * @return 1-based unique proxy ID.
     */
    public static int register(final List<Box> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return FAST_PATH_ID;
        }
        Key key = new Key(boxes);
        Integer existing = DEDUPLICATION_MAP.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (LOCK) {
            existing = DEDUPLICATION_MAP.get(key);
            if (existing != null) {
                return existing;
            }
            int newId = PROXIES_BY_ID.size();
            if (newId >= MAX_PROXIES) {
                return FAST_PATH_ID;
            }
            ShapeProxy proxy = new ShapeProxy(newId, boxes);
            PROXIES_BY_ID.add(proxy);
            DEDUPLICATION_MAP.put(key, newId);
            return newId;
        }
    }

    public static ShapeProxy get(final int id) {
        if (id <= FAST_PATH_ID || id >= PROXIES_BY_ID.size()) {
            return null;
        }
        return PROXIES_BY_ID.get(id);
    }

    public static int count() {
        synchronized (LOCK) {
            return PROXIES_BY_ID.size() - 1;
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            DEDUPLICATION_MAP.clear();
            PROXIES_BY_ID.clear();
            PROXIES_BY_ID.add(null);
        }
    }

    /**
     * Serializes the current shape proxy table into binary format for GPU buffer uploads.
     * <p>Header: uint proxyCount, uint totalBoxCount, uint proxyTableBytes, uint boxTableBytes</p>
     * <p>Proxies table: [uint boxOffset, uint boxCount] per proxy (stride 8 bytes)</p>
     * <p>Box table: [float minX, minY, minZ, float maxX, maxY, maxZ, float pad0, pad1] (stride 32 bytes)</p>
     */
    public static byte[] serializeGpuPayload() {
        synchronized (LOCK) {
            int proxyCount = PROXIES_BY_ID.size();
            int totalBoxes = 0;
            for (int i = 1; i < proxyCount; i++) {
                totalBoxes += PROXIES_BY_ID.get(i).boxCount();
            }

            int proxyTableBytes = proxyCount * 8;
            int boxTableBytes = totalBoxes * 32;
            int totalBytes = 16 + proxyTableBytes + boxTableBytes;

            byte[] data = new byte[totalBytes];
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(0, proxyCount);
            buf.putInt(4, totalBoxes);
            buf.putInt(8, proxyTableBytes);
            buf.putInt(12, boxTableBytes);

            int boxCursor = 0;
            int proxyOffset = 16;
            int boxOffset = 16 + proxyTableBytes;

            buf.putInt(proxyOffset, 0);
            buf.putInt(proxyOffset + 4, 0);

            for (int i = 1; i < proxyCount; i++) {
                ShapeProxy proxy = PROXIES_BY_ID.get(i);
                int currentProxyOffset = proxyOffset + i * 8;
                buf.putInt(currentProxyOffset, boxCursor);
                buf.putInt(currentProxyOffset + 4, proxy.boxCount());

                for (Box b : proxy.boxes()) {
                    int currentBoxOffset = boxOffset + boxCursor * 32;
                    buf.putFloat(currentBoxOffset, b.minX());
                    buf.putFloat(currentBoxOffset + 4, b.minY());
                    buf.putFloat(currentBoxOffset + 8, b.minZ());
                    buf.putFloat(currentBoxOffset + 12, b.maxX());
                    buf.putFloat(currentBoxOffset + 16, b.maxY());
                    buf.putFloat(currentBoxOffset + 20, b.maxZ());
                    buf.putFloat(currentBoxOffset + 24, 0.0f);
                    buf.putFloat(currentBoxOffset + 28, 0.0f);
                    boxCursor++;
                }
            }
            return data;
        }
    }
}
