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
        int priority,
        boolean denseCellEligible,
        ShadowEmitterFootprint shadowEmitterFootprint,
        LocalShadowSourceClass shadowSourceClass
) {
    public AdvancedLight(
            final long stableId,
            final long generation,
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
        this(stableId, generation, kind, x, y, z, radius, red, green, blue,
                intensity, priority, false, ShadowEmitterFootprint.empty(), defaultShadowSourceClass(kind));
    }

    public AdvancedLight(
            final long stableId,
            final long generation,
            final LightSourceKind kind,
            final double x,
            final double y,
            final double z,
            final float radius,
            final float red,
            final float green,
            final float blue,
            final float intensity,
            final int priority,
            final boolean denseCellEligible
    ) {
        this(stableId, generation, kind, x, y, z, radius, red, green, blue,
                intensity, priority, denseCellEligible, ShadowEmitterFootprint.empty(),
                defaultShadowSourceClass(kind));
    }

    public AdvancedLight(
            final long stableId,
            final long generation,
            final LightSourceKind kind,
            final double x,
            final double y,
            final double z,
            final float radius,
            final float red,
            final float green,
            final float blue,
            final float intensity,
            final int priority,
            final boolean denseCellEligible,
            final ShadowEmitterFootprint shadowEmitterFootprint
    ) {
        this(stableId, generation, kind, x, y, z, radius, red, green, blue,
                intensity, priority, denseCellEligible, shadowEmitterFootprint,
                defaultShadowSourceClass(kind));
    }

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
        if (shadowEmitterFootprint == null) {
            throw new NullPointerException("shadowEmitterFootprint");
        }
        if (shadowSourceClass == null) {
            throw new NullPointerException("shadowSourceClass");
        }
        if (stableId == 0L) {
            throw new IllegalArgumentException("stableId zero is reserved");
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        // Reuse the exact parameter validation used before materialization.
        new LightTemplate(kind, x, y, z, radius, red, green, blue, intensity, priority,
                denseCellEligible);
    }

    /** Compacted emitters use their exact member cells; ordinary lights use their source cell. */
    public boolean emitsFromBlock(final int blockX, final int blockY, final int blockZ) {
        if (!this.shadowEmitterFootprint.isEmpty()) {
            return this.shadowEmitterFootprint.contains(blockX, blockY, blockZ);
        }
        return blockX == floorToInt(this.x)
                && blockY == floorToInt(this.y)
                && blockZ == floorToInt(this.z);
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
                this.priority,
                this.denseCellEligible,
                this.shadowEmitterFootprint,
                this.shadowSourceClass
        );
    }

    private static LocalShadowSourceClass defaultShadowSourceClass(final LightSourceKind kind) {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        return kind == LightSourceKind.BLOCK
                ? LocalShadowSourceClass.STATIC_CACHE
                : LocalShadowSourceClass.ENTITY_DYNAMIC;
    }

    private static int floorToInt(final double value) {
        double floor = Math.floor(value);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new IllegalStateException("Light position left the integer world range");
        }
        return (int) floor;
    }
}
