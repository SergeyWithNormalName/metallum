package com.metallum.client.radiance;

import com.metallum.client.lighting.MinecraftLightPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

/**
 * Deterministic appearance producer mapping snapshot-safe BlockState, light levels,
 * and material classification into scene-linear radiance RGB and coarse opacity.
 *
 * <p>Represents a real-world frozen appearance/radiance proxy (not physical GI).</p>
 */
public final class RadianceAppearanceModel {

    private static final BlockGetter EMPTY_GETTER = new BlockGetter() {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    };

    public record EvaluatedAppearance(
            float red,
            float green,
            float blue,
            float albedoRed,
            float albedoGreen,
            float albedoBlue,
            float opacity,
            boolean emissive,
            boolean occupied
    ) {
    }

    private RadianceAppearanceModel() {
    }

    /**
     * Converts an 8-bit sRGB color component in [0, 255] to scene-linear float in [0.0, 1.0].
     */
    public static float srgbToLinear(final int c) {
        float norm = (c & 0xff) / 255.0F;
        return norm <= 0.04045F
                ? norm / 12.92F
                : (float) Math.pow((norm + 0.055F) / 1.055F, 2.4);
    }

    /**
     * Converts a scene-linear float component to an 8-bit clamped sRGB integer in [0, 255].
     */
    public static int linearToSrgbByte(final float linear) {
        if (Float.isNaN(linear) || linear <= 0.0F) {
            return 0;
        }
        float clamped = Math.min(1.0F, linear);
        float srgb = clamped <= 0.0031308F
                ? clamped * 12.92F
                : 1.055F * (float) Math.pow(clamped, 1.0F / 2.4F) - 0.055F;
        return Math.min(255, Math.max(0, Math.round(srgb * 255.0F)));
    }

    /**
     * Evaluates appearance for one block position from accepted snapshot data.
     *
     * @param state block state at position
     * @param blockGetter LevelSlice or block accessor for map color evaluation (may be null for fallback)
     * @param pos block position
     * @param skyLight vanilla sky light level in [0, 15]
     * @param blockLight vanilla block light level in [0, 15]
     * @return evaluated linear radiance (R, G, B), opacity, and classification flags
     */
    public static EvaluatedAppearance evaluate(
            final BlockState state,
            final BlockGetter blockGetter,
            final BlockPos pos,
            final int skyLight,
            final int blockLight
    ) {
        if (state == null || state.isAir()) {
            return new EvaluatedAppearance(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, false, false);
        }

        // 1. Material classification and opacity
        float opacity = opacityFor(state);
        boolean occupied = opacity > 0.0F;

        // 2. Base albedo from MapColor (stable snapshot-safe source)
        float albedoR = 0.8F;
        float albedoG = 0.8F;
        float albedoB = 0.8F;
        try {
            BlockGetter targetGetter = blockGetter != null ? blockGetter : EMPTY_GETTER;
            BlockPos targetPos = pos != null ? pos : BlockPos.ZERO;
            MapColor mapColor = state.getMapColor(targetGetter, targetPos);
            if (mapColor != null && mapColor.col != 0) {
                int col = mapColor.col;
                albedoR = srgbToLinear((col >> 16) & 0xff);
                albedoG = srgbToLinear((col >> 8) & 0xff);
                albedoB = srgbToLinear(col & 0xff);
            }
        } catch (RuntimeException ignored) {
            // Modded map color query failed; use deterministic neutral albedo
            albedoR = 0.5F;
            albedoG = 0.5F;
            albedoB = 0.5F;
        }

        // 3. Emissive evaluation
        int rawEmission = 0;
        try {
            rawEmission = Math.max(0, Math.min(15, state.getLightEmission()));
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                BlockState fluidState = fluid.createLegacyBlock();
                int fluidEmission = fluidState.getLightEmission();
                if (fluidEmission > rawEmission) {
                    rawEmission = fluidEmission;
                }
            }
        } catch (RuntimeException ignored) {
            rawEmission = 0;
        }

        float emissionR = 0.0F;
        float emissionG = 0.0F;
        float emissionB = 0.0F;
        boolean isEmissive = rawEmission > 0;

        if (isEmissive) {
            com.metallum.client.lighting.LightTemplate template = null;
            try {
                template = MinecraftLightPolicy.block(state, 0, 0, 0);
            } catch (RuntimeException ignored) {
            }
            float tintR = template != null ? template.red() : 1.0F;
            float tintG = template != null ? template.green() : 1.0F;
            float tintB = template != null ? template.blue() : 1.0F;
            float normalizedEmission = rawEmission / 15.0F;
            // Boost emissive radiance so glowing objects stand out in the coarse field
            float strength = normalizedEmission * 2.5F;
            emissionR = tintR * strength;
            emissionG = tintG * strength;
            emissionB = tintB * strength;
        }

        // 4. Illumination (sky light + block light)
        int clampedSky = Math.max(0, Math.min(15, skyLight));
        int clampedBlock = Math.max(0, Math.min(15, blockLight));
        float skyNorm = clampedSky / 15.0F;
        float blockNorm = clampedBlock / 15.0F;

        // Diffuse irradiance: outdoor full sun is ~1.0; block light adds local warmth;
        // sealed caves with sky=0, block=0 receive 0 irradiance.
        float diffuseIrradiance = skyNorm * 1.0F + blockNorm * 0.85F;

        // 5. Total linear radiance
        float radR = albedoR * diffuseIrradiance + emissionR;
        float radG = albedoG * diffuseIrradiance + emissionG;
        float radB = albedoB * diffuseIrradiance + emissionB;

        return new EvaluatedAppearance(radR, radG, radB, albedoR, albedoG, albedoB, opacity, isEmissive, occupied);
    }

    /**
     * Maps BlockState into coarse opacity based on L5 VoxelMaterialClass rules.
     */
    public static float opacityFor(final BlockState state) {
        if (state == null || state.isAir()) {
            return 0.0F;
        }
        if (state.getBlock() instanceof VegetationBlock) {
            return 0.25F;
        }
        if (!state.getFluidState().isEmpty()) {
            return 0.30F;
        }
        if (state.is(BlockTags.LEAVES)) {
            return 0.45F;
        }
        if (state.getBlock() instanceof StainedGlassBlock
                || state.getBlock() instanceof StainedGlassPaneBlock
                || state.getBlock() instanceof TransparentBlock) {
            return 0.20F;
        }
        if (!state.isSolidRender()) {
            return 0.70F;
        }
        if (state.getLightDampening() < 15) {
            return 0.50F;
        }
        return 1.0F;
    }
}
