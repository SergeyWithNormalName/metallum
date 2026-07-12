package com.metallum.client.hdr;

/**
 * Exact extended-sRGB decode used at scene-linear clear-color boundaries.
 *
 * <p>The transfer function is extended to negative values symmetrically. This
 * matters for FP16 attachments, where neither negative values nor values above
 * one are implicitly clamped by the storage format.</p>
 */
public final class SceneLinearClearColor {
    private static final double SRGB_LINEAR_THRESHOLD = 0.04045;
    private static final double SRGB_LINEAR_SCALE = 12.92;
    private static final double SRGB_NONLINEAR_OFFSET = 0.055;
    private static final double SRGB_NONLINEAR_SCALE = 1.055;
    private static final double SRGB_EXPONENT = 2.4;

    private SceneLinearClearColor() {
    }

    /** Returns whether an encoded clear belongs to an active FP16 scene-color boundary. */
    public static boolean shouldDecode(
            final boolean rgba16FloatAttachment,
            final boolean sceneColorRole
    ) {
        return SceneLinearPreflightGate.isActive()
                && rgba16FloatAttachment
                && sceneColorRole;
    }

    /** Decodes one sign-preserving extended-sRGB component to linear light. */
    public static float extendedSrgbToLinear(final float encoded) {
        double magnitude = Math.abs((double) encoded);
        if (magnitude <= SRGB_LINEAR_THRESHOLD) {
            return (float) (encoded / SRGB_LINEAR_SCALE);
        }

        double linearMagnitude = Math.pow(
                (magnitude + SRGB_NONLINEAR_OFFSET) / SRGB_NONLINEAR_SCALE,
                SRGB_EXPONENT
        );
        return (float) Math.copySign(linearMagnitude, encoded);
    }

    /** Decodes RGB only. Alpha is intentionally not represented or transformed. */
    public static Rgb extendedSrgbToLinear(
            final float red,
            final float green,
            final float blue
    ) {
        return new Rgb(
                extendedSrgbToLinear(red),
                extendedSrgbToLinear(green),
                extendedSrgbToLinear(blue)
        );
    }

    public record Rgb(float red, float green, float blue) {
    }
}
