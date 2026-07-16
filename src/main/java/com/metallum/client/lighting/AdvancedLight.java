package com.metallum.client.lighting;

import java.util.Comparator;

/** Immutable CPU light record. Positions remain absolute doubles until frame upload. */
public record AdvancedLight(
        long stableId,
        long generation,
        LightSourceKind kind,
        double x,
        double y,
        double z,
        float radius,
        float red,
        float green,
        float blue,
        float intensity,
        int priority
) {
    /** Camera-independent section order and final stable-id tie-break. */
    public static final Comparator<AdvancedLight> PRIORITY_ORDER = (left, right) -> {
        int priorityOrder = Integer.compare(right.priority, left.priority);
        if (priorityOrder != 0) {
            return priorityOrder;
        }
        int idOrder = Long.compareUnsigned(left.stableId, right.stableId);
        if (idOrder != 0) {
            return idOrder;
        }
        int kindOrder = left.kind.compareTo(right.kind);
        if (kindOrder != 0) {
            return kindOrder;
        }
        int generationOrder = Long.compareUnsigned(right.generation, left.generation);
        if (generationOrder != 0) {
            return generationOrder;
        }
        int xOrder = Double.compare(left.x, right.x);
        if (xOrder != 0) {
            return xOrder;
        }
        int yOrder = Double.compare(left.y, right.y);
        return yOrder != 0 ? yOrder : Double.compare(left.z, right.z);
    };

    public AdvancedLight {
        if (stableId == 0L) {
            throw new IllegalArgumentException("stableId zero is reserved");
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        // Reuse the exact parameter validation used before materialization.
        new LightTemplate(kind, x, y, z, radius, red, green, blue, intensity, priority);
    }

    public AdvancedLight withGeneration(final long nextGeneration) {
        return new AdvancedLight(
                this.stableId,
                nextGeneration,
                this.kind,
                this.x,
                this.y,
                this.z,
                this.radius,
                this.red,
                this.green,
                this.blue,
                this.intensity,
                this.priority
        );
    }
}
