package com.metallum.client.lighting;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Allocation-free L8 defaults for terrain surface materials.
 *
 * <p>The compact Sodium vertex contract has one conditionally-free semantic bit: exact emission
 * only consumes it when surface emission is non-zero. Non-emissive terrain uses that bit together
 * with version-locked base values to distinguish metal, smooth dielectric, water, and glass.
 * Unknown translucent terrain remains glass-like, while unknown opaque materials fail safely to
 * dielectric.</p>
 */
public final class SurfaceMaterialPolicy {
    public enum Kind {
        DIELECTRIC,
        SMOOTH_DIELECTRIC,
        METAL,
        GLASS,
        WATER
    }

    public record Descriptor(
            Kind kind,
            float roughness,
            float metalness,
            float dielectricF0,
            float transmission,
            float absorptionRed,
            float absorptionGreen,
            float absorptionBlue,
            float reactiveWeight
    ) {
        public Descriptor {
            Objects.requireNonNull(kind, "kind");
            requireUnit(roughness, "roughness");
            requireUnit(metalness, "metalness");
            requireUnit(dielectricF0, "dielectric F0");
            requireUnit(transmission, "transmission");
            requireNonNegative(absorptionRed, "red absorption");
            requireNonNegative(absorptionGreen, "green absorption");
            requireNonNegative(absorptionBlue, "blue absorption");
            requireUnit(reactiveWeight, "reactive weight");
        }
    }

    public static final Descriptor DIELECTRIC = new Descriptor(
            Kind.DIELECTRIC, 0.68f, 0.0f, 0.04f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f
    );
    public static final Descriptor SMOOTH_DIELECTRIC = new Descriptor(
            Kind.SMOOTH_DIELECTRIC, 0.24f, 0.0f, 0.04f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.12f
    );
    public static final Descriptor METAL = new Descriptor(
            Kind.METAL, 0.22f, 0.92f, 0.04f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.18f
    );
    public static final Descriptor GLASS = new Descriptor(
            Kind.GLASS, 0.10f, 0.0f, 0.04f, 0.92f,
            0.08f, 0.035f, 0.018f, 0.82f
    );
    public static final Descriptor WATER = new Descriptor(
            Kind.WATER, 0.075f, 0.0f, 0.0204f, 0.96f,
            0.36f, 0.095f, 0.035f, 0.94f
    );

    private SurfaceMaterialPolicy() {
    }

    /** Sound types give vanilla and well-behaved mod blocks one stable, remesh-time metal policy. */
    public static Descriptor forBlock(final BlockState state) {
        if (state == null) {
            return DIELECTRIC;
        }
        SoundType sound = state.getSoundType();
        if (sound == SoundType.METAL
                || sound == SoundType.IRON
                || sound == SoundType.COPPER
                || sound == SoundType.NETHERITE_BLOCK
                || sound == SoundType.ANVIL
                || sound == SoundType.CHAIN
                || sound == SoundType.LANTERN
                || sound == SoundType.HEAVY_CORE) {
            return METAL;
        }
        if (sound == SoundType.GLASS) {
            return GLASS;
        }
        if (sound == SoundType.AMETHYST
                || sound == SoundType.POLISHED_TUFF
                || sound == SoundType.POLISHED_DEEPSLATE) {
            return SMOOTH_DIELECTRIC;
        }
        return DIELECTRIC;
    }

    public static float wetRoughness(final float dryRoughness, final float wetness) {
        float dry = clampUnit(dryRoughness);
        float wet = clampUnit(wetness);
        return Math.max(0.055f, dry * (1.0f - 0.68f * wet));
    }

    public static float wetAlbedoScale(final float wetness) {
        return 1.0f - 0.16f * clampUnit(wetness);
    }

    public static float schlickFresnel(final float f0, final float nDotV) {
        float base = clampUnit(f0);
        float grazing = 1.0f - clampUnit(nDotV);
        float grazing2 = grazing * grazing;
        float grazing5 = grazing2 * grazing2 * grazing;
        return base + (1.0f - base) * grazing5;
    }

    public static float ggxDistribution(final float nDotH, final float roughness) {
        float boundedRoughness = Math.max(clampUnit(roughness), 0.045f);
        float alpha = boundedRoughness * boundedRoughness;
        float alpha2 = alpha * alpha;
        float boundedNdotH = clampUnit(nDotH);
        float denominator = boundedNdotH * boundedNdotH * (alpha2 - 1.0f) + 1.0f;
        return alpha2 / ((float) Math.PI * denominator * denominator + 1.0e-6f);
    }

    public static float beerLambert(final float absorption, final float distance) {
        if (!Float.isFinite(absorption) || absorption < 0.0f
                || !Float.isFinite(distance) || distance < 0.0f) {
            throw new IllegalArgumentException("Beer-Lambert inputs must be finite and non-negative");
        }
        return (float) Math.exp(-absorption * distance);
    }

    private static float clampUnit(final float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        return Math.clamp(value, 0.0f, 1.0f);
    }

    private static void requireUnit(final float value, final String label) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(label + " must be in [0, 1]");
        }
    }

    private static void requireNonNegative(final float value, final String label) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(label + " must be finite and non-negative");
        }
    }
}
