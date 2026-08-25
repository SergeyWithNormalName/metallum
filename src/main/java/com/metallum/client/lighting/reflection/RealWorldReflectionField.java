package com.metallum.client.lighting.reflection;

import org.jspecify.annotations.Nullable;

/**
 * Reflection settings and facade around the accepted-Sodium frozen-field controller.
 *
 * <p>World data never enters this class from a render encoder. The only source is a candidate
 * extracted from the LevelSlice attached to an accepted Sodium output; MetalDevice later claims
 * the fully assembled immutable snapshot and hands it to the native context.</p>
 */
public final class RealWorldReflectionField {
    public static final int SPAN_BLOCKS = FrozenReflectionFieldController.SPAN_BLOCKS;
    public static final int SOURCE_EDGE = FrozenReflectionFieldController.SOURCE_EDGE;
    public static final int SOURCE_BLOCK_COUNT = SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE;
    public static final int SOURCE_CELL_SPACING = FrozenReflectionFieldController.SOURCE_CELL_BLOCKS;
    public static final int BINDING_SLOT_RADIANCE = 10;
    public static final int BINDING_SLOT_PARAMS = 27;

    private static final RealWorldReflectionField INSTANCE = new RealWorldReflectionField();

    private volatile boolean enabled = true;
    private volatile boolean contributionOnly = Boolean.getBoolean("metallum.reflection.contributionOnly")
            || "1".equals(System.getenv("METALLUM_REFLECTION_CONTRIBUTION_ONLY"));
    private volatile float strength = 0.75F;
    private volatile float roughness = 0.28F;

    private RealWorldReflectionField() {
    }

    public static RealWorldReflectionField get() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isContributionOnly() {
        return this.contributionOnly;
    }

    public void setContributionOnly(final boolean contributionOnly) {
        this.contributionOnly = contributionOnly;
    }

    public float strength() {
        return this.strength;
    }

    public void setStrength(final float strength) {
        this.strength = Math.max(0.0F, strength);
    }

    public float roughness() {
        return this.roughness;
    }

    public void setRoughness(final float roughness) {
        this.roughness = Math.clamp(roughness, 0.0F, 1.0F);
    }

    public FrozenReflectionFieldController.@Nullable SourceSnapshot claimReadySnapshotForGpuUpload() {
        return this.enabled ? FrozenReflectionFieldController.global().claimReadySnapshotForGpuUpload() : null;
    }

    public void noteGpuReady(final long worldGeneration, final long fieldGeneration) {
        FrozenReflectionFieldController.global().noteGpuReady(worldGeneration, fieldGeneration);
    }

    public void noteGpuFailure(final long worldGeneration, final long fieldGeneration) {
        FrozenReflectionFieldController.global().noteGpuFailure(worldGeneration, fieldGeneration);
    }

    public static int snapToGrid(final double coordinate, final int spacing) {
        return (int) Math.floor(coordinate / spacing) * spacing;
    }
}
