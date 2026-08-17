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
