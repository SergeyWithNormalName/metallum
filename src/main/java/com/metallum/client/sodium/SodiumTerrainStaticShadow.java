package com.metallum.client.sodium;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded exact shadow of the non-light bytes in resident Sodium terrain vertices.
 *
 * <p>The shadow is deliberately populated only after a successful full in-place
 * refresh. Eviction merely makes the next update use the ordinary full upload;
 * it can never make an update eligible for the compact light-only path.</p>
 */
public final class SodiumTerrainStaticShadow implements AutoCloseable {
    public static final long DEFAULT_CAPACITY_BYTES = 32L * 1024L * 1024L;

    private static final int STATIC_VERTEX_BYTES =
            SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
                    - SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE;
    private static final Cache GLOBAL = new Cache(DEFAULT_CAPACITY_BYTES);

    private final Cache owner;
    @Nullable
    private byte[] staticBytes;

    private SodiumTerrainStaticShadow(final Cache owner, @Nullable final byte[] staticBytes) {
        this.owner = owner;
        this.staticBytes = staticBytes;
    }

    public static SodiumTerrainStaticShadow capture(final ByteBuffer geometry) {
        return GLOBAL.capture(geometry);
    }

    public static Snapshot snapshot() {
        return GLOBAL.snapshot();
    }

    public static void releaseAll() {
        GLOBAL.clear();
    }

    /** Exact comparison of every compact terrain byte except light offsets 16 and 17. */
    public boolean matches(final ByteBuffer geometry) {
        byte[] resident = this.owner.touch(this);
        if (resident == null) {
            return false;
        }

        ByteBuffer source = validatedGeometry(geometry);
        int vertexCount = source.remaining() / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
        if (resident.length != Math.multiplyExact(vertexCount, STATIC_VERTEX_BYTES)) {
            return false;
        }

        ByteBuffer expected = ByteBuffer.wrap(resident).order(ByteOrder.BIG_ENDIAN);
        int sourceStart = source.position();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int sourceOffset = sourceStart
                    + vertex * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
            int expectedOffset = vertex * STATIC_VERTEX_BYTES;
            if (source.getLong(sourceOffset) != expected.getLong(expectedOffset)
                    || source.getLong(sourceOffset + Long.BYTES)
                    != expected.getLong(expectedOffset + Long.BYTES)
                    || source.getShort(sourceOffset + SodiumLightSidecarPacking.SKY_LIGHT_OFFSET + 1)
                    != expected.getShort(expectedOffset + Long.BYTES * 2)) {
                return false;
            }
        }
        return true;
    }

    public boolean isResident() {
        return this.owner.isResident(this);
    }

    @Override
    public void close() {
        this.owner.release(this);
    }

    private static byte[] packStaticBytes(final ByteBuffer geometry) {
        ByteBuffer source = validatedGeometry(geometry);
        int vertexCount = source.remaining() / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
        byte[] packed = new byte[Math.multiplyExact(vertexCount, STATIC_VERTEX_BYTES)];
        ByteBuffer destination = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN);
        int sourceStart = source.position();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int sourceOffset = sourceStart
                    + vertex * SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE;
            int destinationOffset = vertex * STATIC_VERTEX_BYTES;
            destination.putLong(destinationOffset, source.getLong(sourceOffset));
            destination.putLong(destinationOffset + Long.BYTES, source.getLong(sourceOffset + Long.BYTES));
            destination.putShort(
                    destinationOffset + Long.BYTES * 2,
                    source.getShort(sourceOffset + SodiumLightSidecarPacking.SKY_LIGHT_OFFSET + 1)
            );
        }
        return packed;
    }

    private static ByteBuffer validatedGeometry(final ByteBuffer geometry) {
        ByteBuffer source = geometry.duplicate().order(ByteOrder.BIG_ENDIAN);
        int bytes = source.remaining();
        if (bytes % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0) {
            throw new IllegalArgumentException("unaligned compact terrain geometry shadow: " + bytes);
        }
        return source;
    }

    public record Snapshot(
            long capacityBytes,
            long liveBytes,
            long peakBytes,
            int residentShadows,
            long evictionCount,
            long rejectedCaptureCount
    ) {
    }

    /** Public only so the exact eviction policy can be dependency-free unit tested. */
    public static final class Cache {
        private final long capacityBytes;
        private final LinkedHashMap<SodiumTerrainStaticShadow, Boolean> residents =
                new LinkedHashMap<>(16, 0.75f, true);
        private long liveBytes;
        private long peakBytes;
        private long evictionCount;
        private long rejectedCaptureCount;

        public Cache(final long capacityBytes) {
            if (capacityBytes < 0L) {
                throw new IllegalArgumentException("negative terrain shadow capacity: " + capacityBytes);
            }
            this.capacityBytes = capacityBytes;
        }

        public SodiumTerrainStaticShadow capture(final ByteBuffer geometry) {
            ByteBuffer source = validatedGeometry(geometry);
            long requiredBytes = Math.multiplyExact(
                    source.remaining() / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE,
                    STATIC_VERTEX_BYTES
            );
            if (requiredBytes > this.capacityBytes) {
                synchronized (this) {
                    this.rejectedCaptureCount = Math.addExact(this.rejectedCaptureCount, 1L);
                }
                return new SodiumTerrainStaticShadow(this, null);
            }

            byte[] packed = packStaticBytes(source);
            SodiumTerrainStaticShadow shadow = new SodiumTerrainStaticShadow(this, packed);
            synchronized (this) {
                while (Math.addExact(this.liveBytes, packed.length) > this.capacityBytes) {
                    Map.Entry<SodiumTerrainStaticShadow, Boolean> eldest =
                            this.residents.entrySet().iterator().next();
                    this.evict(eldest.getKey());
                }
                this.residents.put(shadow, Boolean.TRUE);
                this.liveBytes = Math.addExact(this.liveBytes, packed.length);
                this.peakBytes = Math.max(this.peakBytes, this.liveBytes);
            }
            return shadow;
        }

        @Nullable
        private synchronized byte[] touch(final SodiumTerrainStaticShadow shadow) {
            byte[] bytes = shadow.staticBytes;
            if (bytes == null || this.residents.get(shadow) == null) {
                return null;
            }
            return bytes;
        }

        private synchronized boolean isResident(final SodiumTerrainStaticShadow shadow) {
            return shadow.staticBytes != null && this.residents.containsKey(shadow);
        }

        private synchronized void release(final SodiumTerrainStaticShadow shadow) {
            byte[] bytes = shadow.staticBytes;
            if (bytes == null) {
                return;
            }
            shadow.staticBytes = null;
            if (this.residents.remove(shadow) != null) {
                this.liveBytes = Math.subtractExact(this.liveBytes, bytes.length);
            }
        }

        private void evict(final SodiumTerrainStaticShadow shadow) {
            byte[] bytes = shadow.staticBytes;
            shadow.staticBytes = null;
            if (this.residents.remove(shadow) != null && bytes != null) {
                this.liveBytes = Math.subtractExact(this.liveBytes, bytes.length);
                this.evictionCount = Math.addExact(this.evictionCount, 1L);
            }
        }

        public synchronized Snapshot snapshot() {
            return new Snapshot(
                    this.capacityBytes,
                    this.liveBytes,
                    this.peakBytes,
                    this.residents.size(),
                    this.evictionCount,
                    this.rejectedCaptureCount
            );
        }

        public synchronized void clear() {
            for (SodiumTerrainStaticShadow shadow : this.residents.keySet()) {
                shadow.staticBytes = null;
            }
            this.residents.clear();
            this.liveBytes = 0L;
        }
    }
}
