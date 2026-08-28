package com.metallum.client.lighting.reflection;

/**
 * Six-axis receiver-face codec used by the always-applied Sodium material path.
 *
 * <p>The bit order is intentionally identical to the private G2 semantic field's face ABI,
 * but this class stays independent from the structurally gated G2 capture implementation.</p>
 */
public final class VoxelReflectionFace {
    private static final float AXIS_EPSILON = 1.0e-4F;
    public static final int NEG_X = 1;
    public static final int POS_X = 1 << 1;
    public static final int NEG_Y = 1 << 2;
    public static final int POS_Y = 1 << 3;
    public static final int NEG_Z = 1 << 4;
    public static final int POS_Z = 1 << 5;
    public static final int MASK = 0x3f;

    private VoxelReflectionFace() {
    }

    public static int forNormal(final float x, final float y, final float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || (x == 0.0F && y == 0.0F && z == 0.0F)) {
            return 0;
        }
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (ax >= ay && ax >= az) {
            return x < 0.0F ? NEG_X : POS_X;
        }
        if (ay >= az) {
            return y < 0.0F ? NEG_Y : POS_Y;
        }
        return z < 0.0F ? NEG_Z : POS_Z;
    }

    /**
     * Strict six-axis codec for consumers which reconstruct a physical normal from the face.
     * Reflection deliberately keeps {@link #forNormal(float, float, float)} and its dominant-axis
     * approximation; diffuse G5 must reject diagonal, crossed-plant and non-unit normals instead
     * of pretending that they are an exact cube face.
     */
    public static int forAxisAlignedUnitNormal(final float x, final float y, final float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            return 0;
        }
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (Math.abs(ax - 1.0F) <= AXIS_EPSILON
                && ay <= AXIS_EPSILON && az <= AXIS_EPSILON) {
            return x < 0.0F ? NEG_X : POS_X;
        }
        if (Math.abs(ay - 1.0F) <= AXIS_EPSILON
                && ax <= AXIS_EPSILON && az <= AXIS_EPSILON) {
            return y < 0.0F ? NEG_Y : POS_Y;
        }
        if (Math.abs(az - 1.0F) <= AXIS_EPSILON
                && ax <= AXIS_EPSILON && ay <= AXIS_EPSILON) {
            return z < 0.0F ? NEG_Z : POS_Z;
        }
        return 0;
    }

    public static int index(final int faceBit) {
        if (Integer.bitCount(faceBit) != 1 || (faceBit & ~MASK) != 0) {
            throw new IllegalArgumentException("reflection face must contain exactly one direction bit");
        }
        return Integer.numberOfTrailingZeros(faceBit);
    }
}
