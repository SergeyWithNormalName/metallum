package com.metallum.client.lighting;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Allocation-free L8 defaults for terrain surface materials.
 *
 * <p>The compact Sodium vertex contract has one conditionally-free semantic bit: exact emission
 * only consumes it when surface emission is non-zero. Non-emissive terrain uses that bit together
 * with version-locked base values to distinguish metal, smooth dielectric, water, glass,
 * stone, wood, and porous surfaces.
 * Unknown terrain fails safely to dielectric unless the remesher identifies a genuine translucent
 * render pass. Fragment alpha is deliberately not a material classifier: foliage, grass overlays,
 * and their mip levels are alpha-tested but not glass.</p>
 */
public final class SurfaceMaterialPolicy {
    public static final float RAIN_FACING_START = 0.55f;
    public static final float RAIN_FACING_FULL = 0.85f;
    public static final float RAIN_WETTING_RESPONSE_SECONDS = 1.25f;
    public static final float RAIN_DRYING_RESPONSE_SECONDS = 4.0f;
    public static final float RAIN_WETNESS_EPSILON = 0.01f;
    private static final float RAIN_WETNESS_MINIMUM_INTENSITY = 0.80f;

    public enum Kind {
        DIELECTRIC,
        STONE,
        WOOD,
        POROUS,
        SMOOTH_DIELECTRIC,
        METAL,
        GLASS,
        WATER
    }

    public record Descriptor(
            Kind kind,
            float roughness,
            float wetRoughnessTarget,
            float wetSpecularScale,
            float albedoRoughnessAmplitude,
            float wetAlbedoScale,
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
            requireUnit(wetRoughnessTarget, "wet roughness target");
            requireUnit(wetSpecularScale, "wet specular scale");
            requireUnit(albedoRoughnessAmplitude, "albedo roughness amplitude");
            requireUnit(wetAlbedoScale, "wet albedo scale");
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
            Kind.DIELECTRIC, 0.68f, 0.50f, 0.28f, 0.060f, 0.80f,
            0.0f, 0.04f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f
    );
    public static final Descriptor STONE = new Descriptor(
            Kind.STONE, 0.70f, 0.28f, 0.78f, 0.050f, 0.84f,
            0.0f, 0.04f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f
    );
    public static final Descriptor WOOD = new Descriptor(
            Kind.WOOD, 0.72f, 0.42f, 0.48f, 0.050f, 0.82f,
            0.0f, 0.04f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f
    );
    public static final Descriptor POROUS = new Descriptor(
            Kind.POROUS, 0.80f, 0.72f, 0.10f, 0.025f, 0.92f,
            0.0f, 0.04f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f
    );
    public static final Descriptor SMOOTH_DIELECTRIC = new Descriptor(
            Kind.SMOOTH_DIELECTRIC, 0.24f, 0.16f, 0.85f, 0.020f, 0.90f,
            0.0f, 0.04f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.12f
    );
    public static final Descriptor METAL = new Descriptor(
            Kind.METAL, 0.22f, 0.14f, 0.95f, 0.015f, 0.92f,
            0.92f, 0.04f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.18f
    );
    public static final Descriptor GLASS = new Descriptor(
            Kind.GLASS, 0.10f, 0.10f, 1.0f, 0.0f, 1.0f,
            0.0f, 0.04f, 0.92f,
            0.08f, 0.035f, 0.018f, 0.82f
    );
    public static final Descriptor WATER = new Descriptor(
            Kind.WATER, 0.075f, 0.075f, 1.0f, 0.0f, 1.0f,
            0.0f, 0.0204f, 0.82f,
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
        if (isPorous(state, sound)) {
            return POROUS;
        }
        if (isWood(state, sound)) {
            return WOOD;
        }
        if (isStone(sound)) {
            return STONE;
        }
        return DIELECTRIC;
    }

    /**
     * Promotes only a genuine translucent terrain pass to the conservative glass-like fallback.
     * Cutout and cutout-mipped materials must pass {@code false}, regardless of sampled alpha.
     */
    public static Descriptor forTerrain(
            final BlockState state,
            final boolean translucentRenderPass
    ) {
        Descriptor explicit = forBlock(state);
        return explicit == DIELECTRIC && translucentRenderPass ? GLASS : explicit;
    }

    public static float wetRoughness(
            final Descriptor material,
            final float wetness,
            final float albedoLuminance
    ) {
        Objects.requireNonNull(material, "material");
        float wet = clampUnit(wetness);
        float centeredLuminance = (clampUnit(albedoLuminance) - 0.5f) * 2.0f;
        float texturedTarget = material.wetRoughnessTarget()
                - centeredLuminance * material.albedoRoughnessAmplitude();
        return Math.clamp(
                mix(material.roughness(), texturedTarget, wet),
                0.08f,
                0.95f
        );
    }

    public static float wetSpecularScale(final Descriptor material, final float wetness) {
        Objects.requireNonNull(material, "material");
        return mix(1.0f, material.wetSpecularScale(), clampUnit(wetness));
    }

    public static float wetAlbedoScale(final Descriptor material, final float wetness) {
        Objects.requireNonNull(material, "material");
        return mix(1.0f, material.wetAlbedoScale(), clampUnit(wetness));
    }

    public static float wetDielectricF0(final float dryF0, final float wetness) {
        return mix(clampUnit(dryF0), 0.025f, clampUnit(wetness));
    }

    /**
     * Stable remesh/shader contract for rain accumulation. Vertical and near-vertical faces are
     * dry; upward slopes transition smoothly so roofs still receive rain without letting an
     * unstable screen derivative activate wet optics along primitive edges.
     */
    public static float rainExposure(final float worldNormalY) {
        if (!Float.isFinite(worldNormalY)) {
            throw new IllegalArgumentException("world normal Y must be finite");
        }
        float amount = Math.clamp(
                (worldNormalY - RAIN_FACING_START) / (RAIN_FACING_FULL - RAIN_FACING_START),
                0.0f,
                1.0f
        );
        return amount * amount * (3.0f - 2.0f * amount);
    }

    /**
     * Frame-rate-independent rain-film response for the L8 material packet. Wetting is quick,
     * while drying retains a short visual tail so weather changes do not toggle wet optics.
     */
    public static float smoothRainWetness(
            final float currentWetness,
            final float targetRain,
            final float deltaSeconds
    ) {
        float current = clampUnit(currentWetness);
        float target = clampUnit(targetRain);
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("delta seconds must be finite and non-negative");
        }
        float responseSeconds = target > current
                ? RAIN_WETTING_RESPONSE_SECONDS : RAIN_DRYING_RESPONSE_SECONDS;
        float blend = 1.0f - (float) Math.exp(-deltaSeconds / responseSeconds);
        float next = mix(current, target, blend);
        return Math.abs(next - target) <= RAIN_WETNESS_EPSILON ? target : next;
    }

    /**
     * Maps Minecraft's interpolated rain level to a deliberately narrow L8 film range. Rain
     * intensity remains visible without making light rain look almost dry; exact zero is retained
     * so the smoothed film can finish drying and leave the shader's wet-only path.
     */
    public static float rainWetnessTarget(final float interpolatedRainLevel) {
        float rain = clampUnit(interpolatedRainLevel);
        return rain == 0.0f
                ? 0.0f
                : mix(RAIN_WETNESS_MINIMUM_INTENSITY, 1.0f, rain);
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

    private static boolean isPorous(final BlockState state, final SoundType sound) {
        return state.getBlock() instanceof LeavesBlock
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.WOOL)
                || state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.getBlock() instanceof BushBlock
                || sound == SoundType.SNOW
                || sound == SoundType.POWDER_SNOW
                || sound == SoundType.VINE
                || sound == SoundType.CROP
                || sound == SoundType.HARD_CROP
                || sound == SoundType.NETHER_WART
                || sound == SoundType.ROOTS
                || sound == SoundType.NETHER_SPROUTS
                || sound == SoundType.MOSS
                || sound == SoundType.MOSS_CARPET
                || sound == SoundType.AZALEA_LEAVES
                || sound == SoundType.CHERRY_LEAVES;
    }

    private static boolean isWood(final BlockState state, final SoundType sound) {
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_TRAPDOORS)
                || sound == SoundType.WOOD
                || sound == SoundType.BAMBOO_WOOD
                || sound == SoundType.NETHER_WOOD
                || sound == SoundType.CHERRY_WOOD
                || sound == SoundType.HANGING_SIGN
                || sound == SoundType.NETHER_WOOD_HANGING_SIGN
                || sound == SoundType.BAMBOO_WOOD_HANGING_SIGN
                || sound == SoundType.CHERRY_WOOD_HANGING_SIGN
                || sound == SoundType.CHISELED_BOOKSHELF
                || sound == SoundType.SHELF
                || sound == SoundType.LADDER;
    }

    private static boolean isStone(final SoundType sound) {
        return sound == SoundType.STONE
                || sound == SoundType.DEEPSLATE
                || sound == SoundType.DEEPSLATE_BRICKS
                || sound == SoundType.DEEPSLATE_TILES
                || sound == SoundType.TUFF
                || sound == SoundType.TUFF_BRICKS
                || sound == SoundType.CALCITE
                || sound == SoundType.DRIPSTONE_BLOCK
                || sound == SoundType.POINTED_DRIPSTONE
                || sound == SoundType.BASALT
                || sound == SoundType.NETHER_BRICKS
                || sound == SoundType.MUD_BRICKS
                || sound == SoundType.PACKED_MUD
                || sound == SoundType.RESIN_BRICKS;
    }

    private static float clampUnit(final float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        return Math.clamp(value, 0.0f, 1.0f);
    }

    private static float mix(final float start, final float end, final float amount) {
        return start + (end - start) * amount;
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
