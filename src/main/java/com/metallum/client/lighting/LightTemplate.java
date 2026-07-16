package com.metallum.client.lighting;

/** Source parameters before a stable id and registry generation are assigned. */
public record LightTemplate(
        LightSourceKind kind,
        double x,
        double y,
        double z,
        float radius,
        float red,
        float green,
        float blue,
        float intensity,
        int priority,
        boolean denseCellEligible
) {
    public LightTemplate(
            final LightSourceKind kind,
            final double x,
            final double y,
            final double z,
            final float radius,
            final float red,
            final float green,
            final float blue,
            final float intensity,
            final int priority
    ) {
        this(kind, x, y, z, radius, red, green, blue, intensity, priority, false);
    }

    public LightTemplate {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requirePositiveFinite(radius, "radius");
        requireNonNegativeFinite(red, "red");
        requireNonNegativeFinite(green, "green");
        requireNonNegativeFinite(blue, "blue");
        requirePositiveFinite(intensity, "intensity");
        if (red == 0.0F && green == 0.0F && blue == 0.0F) {
            throw new IllegalArgumentException("A light cannot have a black linear color");
        }
    }

    public AdvancedLight materialize(final long stableId, final long generation) {
        return new AdvancedLight(
                stableId,
                generation,
                this.kind,
                this.x,
                this.y,
                this.z,
                this.radius,
                this.red,
                this.green,
                this.blue,
                this.intensity,
                this.priority,
                this.denseCellEligible
        );
    }

    private static void requireFinite(final double value, final String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requirePositiveFinite(final float value, final String name) {
        if (!Float.isFinite(value) || value <= 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireNonNegativeFinite(final float value, final String name) {
        if (!Float.isFinite(value) || value < 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
