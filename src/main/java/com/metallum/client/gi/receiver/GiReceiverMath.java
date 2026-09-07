package com.metallum.client.gi.receiver;

/** Dependency-free reference math for G5 shader and ABI tests. */
public final class GiReceiverMath {
    /**
     * One valid texel at an exact three-axis block corner contributes 1/8 to a
     * trilinear lookup.  The reciprocal is capped at that one-of-eight gain instead of
     * hard-rejecting values just below 1/8: RG8 linear filtering and floating-point camera
     * motion must not turn a surface contribution on and off at that numerical boundary.
     */
    public static final float MAX_SPARSE_SURFACE_RECIPROCAL = 8.0F;

    public record Vec3(float x, float y, float z) {
        public Vec3 {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("G5 vector contains a non-finite component");
            }
        }
    }

    /** Scaled real L1 basis `[c0,cx,cy,cz]` for one color channel. */
    public record ShChannel(float c0, float cx, float cy, float cz) {
    }

    public record TextureCoordinate(float u, float v, float w, boolean inside) {
    }

    private GiReceiverMath() {
    }

    public static Vec3 faceNormal(final int faceCode) {
        return switch (faceCode) {
            case 1 -> new Vec3(-1.0F, 0.0F, 0.0F);
            case 2 -> new Vec3(1.0F, 0.0F, 0.0F);
            case 3 -> new Vec3(0.0F, -1.0F, 0.0F);
            case 4 -> new Vec3(0.0F, 1.0F, 0.0F);
            case 5 -> new Vec3(0.0F, 0.0F, -1.0F);
            case 6 -> new Vec3(0.0F, 0.0F, 1.0F);
            default -> new Vec3(0.0F, 0.0F, 0.0F);
        };
    }

    /** Reconstructs `c0 + dot(cxyz,n)` and fails closed on malformed field data. */
    public static float reconstruct(final ShChannel coefficients, final Vec3 normal) {
        if (coefficients == null || normal == null
                || !Float.isFinite(coefficients.c0())
                || !Float.isFinite(coefficients.cx())
                || !Float.isFinite(coefficients.cy())
                || !Float.isFinite(coefficients.cz())) {
            return 0.0F;
        }
        float value = coefficients.c0()
                + coefficients.cx() * normal.x()
                + coefficients.cy() * normal.y()
                + coefficients.cz() * normal.z();
        return Float.isFinite(value) ? Math.max(value, 0.0F) : 0.0F;
    }

    public static Vec3 reconstructRgb(
            final ShChannel red,
            final ShChannel green,
            final ShChannel blue,
            final int faceCode
    ) {
        Vec3 normal = faceNormal(faceCode);
        if (normal.x() == 0.0F && normal.y() == 0.0F && normal.z() == 0.0F) {
            return new Vec3(0.0F, 0.0F, 0.0F);
        }
        return new Vec3(
                reconstruct(red, normal),
                reconstruct(green, normal),
                reconstruct(blue, normal)
        );
    }

    public static float confidence(final float value) {
        return Float.isFinite(value) ? Math.clamp(value, 0.0F, 1.0F) : 0.0F;
    }

    /** Returns a continuous bounded reciprocal for G6 sparse-surface filtering. */
    public static float sparseSurfaceReciprocal(final float filteredCoverage) {
        if (!Float.isFinite(filteredCoverage)
                || filteredCoverage <= 0.0F
                || filteredCoverage > 1.0F) {
            return 0.0F;
        }
        float reciprocal = 1.0F / filteredCoverage;
        return Float.isFinite(reciprocal)
                ? Math.min(reciprocal, MAX_SPARSE_SURFACE_RECIPROCAL) : 0.0F;
    }

    /** Normalizes one filtered SH component without clamping its directional sign. */
    public static float normalizeSparseComponent(
            final float filteredValue,
            final float filteredCoverage
    ) {
        float reciprocal = sparseSurfaceReciprocal(filteredCoverage);
        if (reciprocal == 0.0F || !Float.isFinite(filteredValue)) {
            return 0.0F;
        }
        float normalized = filteredValue * reciprocal;
        return Float.isFinite(normalized) ? normalized : 0.0F;
    }

    /** Normalizes filtered known-path confidence; it remains reliability, never energy gain. */
    public static float normalizeSparseConfidence(
            final float filteredConfidence,
            final float filteredCoverage
    ) {
        return confidence(normalizeSparseComponent(filteredConfidence, filteredCoverage));
    }

    /** Maps world blocks into the one frozen block-scale G4 near-cascade domain. */
    public static TextureCoordinate worldToTexture(
            final float worldX,
            final float worldY,
            final float worldZ,
            final int originX,
            final int originY,
            final int originZ
    ) {
        if (!Float.isFinite(worldX) || !Float.isFinite(worldY) || !Float.isFinite(worldZ)) {
            return new TextureCoordinate(0.0F, 0.0F, 0.0F, false);
        }
        float u = (worldX - originX) * GiReceiverLayout.INVERSE_SPAN;
        float v = (worldY - originY) * GiReceiverLayout.INVERSE_SPAN;
        float w = (worldZ - originZ) * GiReceiverLayout.INVERSE_SPAN;
        boolean inside = u >= 0.0F && u < 1.0F
                && v >= 0.0F && v < 1.0F
                && w >= 0.0F && w < 1.0F;
        return new TextureCoordinate(u, v, w, inside);
    }

    /**
     * Adds the explicit one-bounce correction to the approximate ambient fallback. Incoming SH is
     * irradiance; receiver albedo and `1/pi` are applied here exactly once. Confidence is a
     * validity proof, not an energy weight: zero returns the original term exactly, while any
     * known transport support adds only finite, non-negative transported energy.
     */
    public static Vec3 replaceApproximateAmbient(
            final Vec3 approximateAmbient,
            final Vec3 receiverAlbedo,
            final Vec3 incomingIrradiance,
            final float rawConfidence
    ) {
        if (approximateAmbient == null || receiverAlbedo == null || incomingIrradiance == null) {
            throw new IllegalArgumentException("G5 ambient composition input is missing");
        }
        float weight = confidence(rawConfidence);
        if (weight == 0.0F) {
            return approximateAmbient;
        }
        float physicalR = Math.max(receiverAlbedo.x(), 0.0F)
                * Math.max(incomingIrradiance.x(), 0.0F) * GiReceiverLayout.INVERSE_PI;
        float physicalG = Math.max(receiverAlbedo.y(), 0.0F)
                * Math.max(incomingIrradiance.y(), 0.0F) * GiReceiverLayout.INVERSE_PI;
        float physicalB = Math.max(receiverAlbedo.z(), 0.0F)
                * Math.max(incomingIrradiance.z(), 0.0F) * GiReceiverLayout.INVERSE_PI;
        return new Vec3(
                approximateAmbient.x() + physicalR,
                approximateAmbient.y() + physicalG,
                approximateAmbient.z() + physicalB
        );
    }
}
