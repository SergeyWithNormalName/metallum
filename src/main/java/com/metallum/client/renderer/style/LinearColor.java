package com.metallum.client.renderer.style;

import java.util.Objects;

/**
 * Immutable linear-light RGB color representation used by visual style profiles.
 */
public record LinearColor(float red, float green, float blue) {
    public static final LinearColor BLACK = new LinearColor(0.0f, 0.0f, 0.0f);
    public static final LinearColor WHITE = new LinearColor(1.0f, 1.0f, 1.0f);

    public LinearColor {
        requireFinite(red, "red");
        requireFinite(green, "green");
        requireFinite(blue, "blue");
        requireNonNegative(red, "red");
        requireNonNegative(green, "green");
        requireNonNegative(blue, "blue");
    }

    /**
     * Computes the scene-linear relative luminance: Y = 0.2126*R + 0.7152*G + 0.0722*B.
     */
    public float luminance() {
        return 0.2126f * this.red + 0.7152f * this.green + 0.0722f * this.blue;
    }

    /**
     * Scales this color by a non-negative scalar factor.
     */
    public LinearColor scale(final float factor) {
        requireFinite(factor, "factor");
        requireNonNegative(factor, "factor");
        return new LinearColor(this.red * factor, this.green * factor, this.blue * factor);
    }

    /**
     * Linearly interpolates between two colors with clamped factor t in [0, 1].
     */
    public static LinearColor lerp(final LinearColor a, final LinearColor b, final float t) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        requireFinite(t, "t");
        float clampedT = Math.clamp(t, 0.0f, 1.0f);
        if (clampedT <= 0.0f) {
            return a;
        }
        if (clampedT >= 1.0f) {
            return b;
        }
        float r = a.red + (b.red - a.red) * clampedT;
        float g = a.green + (b.green - a.green) * clampedT;
        float bl = a.blue + (b.blue - a.blue) * clampedT;
        return new LinearColor(r, g, bl);
    }

    /**
     * Creates a LinearColor by converting encoded sRGB color channels [0, 1] to linear space.
     */
    public static LinearColor fromSrgb(final float srgbRed, final float srgbGreen, final float srgbBlue) {
        requireFinite(srgbRed, "srgbRed");
        requireFinite(srgbGreen, "srgbGreen");
        requireFinite(srgbBlue, "srgbBlue");
        return new LinearColor(
                srgbToLinearChannel(Math.max(0.0f, srgbRed)),
                srgbToLinearChannel(Math.max(0.0f, srgbGreen)),
                srgbToLinearChannel(Math.max(0.0f, srgbBlue))
        );
    }

    /**
     * Converts the red linear channel to sRGB [0, 1].
     */
    public float toSrgbRed() {
        return linearToSrgbChannel(this.red);
    }

    /**
     * Converts the green linear channel to sRGB [0, 1].
     */
    public float toSrgbGreen() {
        return linearToSrgbChannel(this.green);
    }

    /**
     * Converts the blue linear channel to sRGB [0, 1].
     */
    public float toSrgbBlue() {
        return linearToSrgbChannel(this.blue);
    }

    public static float srgbToLinearChannel(final float srgb) {
        if (srgb <= 0.04045f) {
            return srgb / 12.92f;
        }
        return (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4);
    }

    public static float linearToSrgbChannel(final float linear) {
        if (linear <= 0.0031308f) {
            return linear * 12.92f;
        }
        return (float) (1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055);
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireNonNegative(final float value, final String name) {
        if (value < 0.0f) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
