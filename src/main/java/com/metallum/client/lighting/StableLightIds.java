package com.metallum.client.lighting;

import java.util.UUID;

/** Deterministic 64-bit ids; zero is remapped because the upload ABI reserves it. */
public final class StableLightIds {
    private static final long BLOCK_DOMAIN = 0x4d4554414c424c4bL;
    private static final long ENTITY_DOMAIN = 0x4d4554414c454e54L;
    private static final long DENSE_BLOCK_DOMAIN = 0x4d4554414c444e53L;

    private StableLightIds() {
    }

    public static long block(
            final String dimensionId,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        long position = mix64(Integer.toUnsignedLong(blockX));
        position ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(blockY)), 21);
        position ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(blockZ)), 42);
        return nonZero(mix64(BLOCK_DOMAIN ^ hashString(dimensionId) ^ position));
    }

    public static long entity(final String dimensionId, final UUID uuid) {
        if (uuid == null) {
            throw new NullPointerException("uuid");
        }
        return nonZero(mix64(
                ENTITY_DOMAIN
                        ^ hashString(dimensionId)
                        ^ mix64(uuid.getMostSignificantBits())
                        ^ Long.rotateLeft(mix64(uuid.getLeastSignificantBits()), 29)
        ));
    }

    public static long denseBlock(
            final String dimensionId,
            final int cellX,
            final int cellY,
            final int cellZ,
            final int cellEdge,
            final float radius,
            final float red,
            final float green,
            final float blue,
            final float intensity,
            final int priority
    ) {
        long cell = mix64(Integer.toUnsignedLong(cellX));
        cell ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(cellY)), 21);
        cell ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(cellZ)), 42);
        long signature = mix64(Integer.toUnsignedLong(cellEdge));
        signature ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(
                Float.floatToRawIntBits(radius))), 7);
        signature ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(
                Float.floatToRawIntBits(red))), 14);
        signature ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(
                Float.floatToRawIntBits(green))), 21);
        signature ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(
                Float.floatToRawIntBits(blue))), 28);
        signature ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(
                Float.floatToRawIntBits(intensity))), 35);
        signature ^= Long.rotateLeft(mix64(Integer.toUnsignedLong(priority)), 49);
        return nonZero(mix64(
                DENSE_BLOCK_DOMAIN ^ hashString(dimensionId) ^ cell ^ signature
        ));
    }

    private static long hashString(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return mix64(hash);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    private static long nonZero(final long value) {
        return value == 0L ? 1L : value;
    }
}
