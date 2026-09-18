package com.metallum.client.radiance;

/**
 * IEEE 754 half-precision float (16-bit) conversion utilities.
 *
 * <p>Uses Java 20+ {@link Float#floatToFloat16(float)} and {@link Float#float16ToFloat(short)}
 * for deterministic, zero-allocation float16 compression.</p>
 */
public final class Float16Compressor {

    private Float16Compressor() {
    }

    /**
     * Compresses a 32-bit single-precision float into a 16-bit half-precision float stored as a short.
     */
    public static short packFloat(final float value) {
        return Float.floatToFloat16(value);
    }

    /**
     * Unpacks a 16-bit half-precision float stored as a short into a 32-bit single-precision float.
     */
    public static float unpackFloat(final short half) {
        return Float.float16ToFloat(half);
    }

    /**
     * Bulk packs an array of 32-bit floats into an array of 16-bit half-floats.
     */
    public static void packFloats(final float[] src, final int srcOffset, final short[] dst, final int dstOffset, final int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOffset + i] = Float.floatToFloat16(src[srcOffset + i]);
        }
    }

    /**
     * Bulk unpacks an array of 16-bit half-floats into an array of 32-bit floats.
     */
    public static void unpackFloats(final short[] src, final int srcOffset, final float[] dst, final int dstOffset, final int count) {
        for (int i = 0; i < count; i++) {
            dst[dstOffset + i] = Float.float16ToFloat(src[srcOffset + i]);
        }
    }
}
