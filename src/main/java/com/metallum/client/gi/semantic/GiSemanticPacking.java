package com.metallum.client.gi.semantic;

/** Exact normalized-value and face packing for the versioned G2 CPU contract. */
public final class GiSemanticPacking {
    public static final int FACE_NEG_X = 1;
    public static final int FACE_POS_X = 1 << 1;
    public static final int FACE_NEG_Y = 1 << 2;
    public static final int FACE_POS_Y = 1 << 3;
    public static final int FACE_NEG_Z = 1 << 4;
    public static final int FACE_POS_Z = 1 << 5;
    public static final int FACE_MASK = 0x3f;
    public static final int UNORM16_MAX = 0xffff;

    private GiSemanticPacking() {
    }

    public static byte unorm8(final float value) {
        requireFinite(value, "UNORM8");
        return (byte) Math.round(Math.clamp(value, 0.0F, 1.0F) * 255.0F);
    }

    public static float unorm8(final byte value) {
        return Byte.toUnsignedInt(value) * (1.0F / 255.0F);
    }

    public static short unorm16(final float value) {
        requireFinite(value, "UNORM16");
        return (short) Math.round(Math.clamp(value, 0.0F, 1.0F) * UNORM16_MAX);
    }

    public static float unorm16(final short value) {
        return Short.toUnsignedInt(value) * (1.0F / UNORM16_MAX);
    }

    public static int faceForNormal(final float x, final float y, final float z) {
        requireFinite(x, "normal X");
        requireFinite(y, "normal Y");
        requireFinite(z, "normal Z");
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (ax == 0.0F && ay == 0.0F && az == 0.0F) {
            throw new IllegalArgumentException("G2 quad normal must not be zero");
        }
        if (ax >= ay && ax >= az) {
            return x < 0.0F ? FACE_NEG_X : FACE_POS_X;
        }
        if (ay >= az) {
            return y < 0.0F ? FACE_NEG_Y : FACE_POS_Y;
        }
        return z < 0.0F ? FACE_NEG_Z : FACE_POS_Z;
    }

    public static int faceIndex(final int faceBit) {
        if (Integer.bitCount(faceBit) != 1 || (faceBit & ~FACE_MASK) != 0) {
            throw new IllegalArgumentException("G2 face must contain exactly one direction bit");
        }
        return Integer.numberOfTrailingZeros(faceBit);
    }

    public static float srgbToLinear(final int component) {
        int bounded = Math.clamp(component, 0, 255);
        float normalized = bounded * (1.0F / 255.0F);
        return normalized <= 0.04045F
                ? normalized / 12.92F
                : (float) Math.pow((normalized + 0.055F) / 1.055F, 2.4F);
    }

    static int roundedAverage(final long sum, final long weight) {
        if (weight <= 0L) {
            return 0;
        }
        return Math.toIntExact(Math.min(UNORM16_MAX, (sum + weight / 2L) / weight));
    }

    private static void requireFinite(final float value, final String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("G2 " + label + " value must be finite");
        }
    }
}
