package com.metallum.client.radiance;

import com.metallum.Metallum;
import com.metallum.client.lighting.reflection.WaterReflectionQualityConfig;
import com.metallum.mixin.render.SpriteContentsImageAccess;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, resource-pack-aware TOP/SIDE/BOTTOM appearance table for the coarse radiance source.
 *
 * <p>The GPU field intentionally still stores one RGBA value per source cell. Consequently this
 * class does not claim ray-dependent face selection. It replaces the old single MapColor with a
 * water-relevant blend of the block's actually exposed baked faces before that one value is
 * deposited into the field.</p>
 */
public final class FaceAwareRadianceAppearance {
    public static final int TOP_MASK = 1 << Direction.UP.ordinal();
    public static final int BOTTOM_MASK = 1 << Direction.DOWN.ordinal();
    public static final int SIDE_MASK = (1 << Direction.NORTH.ordinal())
            | (1 << Direction.SOUTH.ordinal())
            | (1 << Direction.WEST.ordinal())
            | (1 << Direction.EAST.ordinal());

    // A reflected ray leaving horizontal water normally reaches a bank side. Top faces are still
    // retained for sloped/thin models, while downward faces matter for overhangs.
    static final float TOP_EXPOSURE_WEIGHT = 0.22F;
    static final float SIDE_EXPOSURE_WEIGHT = 1.0F;
    static final float BOTTOM_EXPOSURE_WEIGHT = 0.65F;

    private static volatile Table table = Table.EMPTY;

    private FaceAwareRadianceAppearance() {
    }

    /** Rebuilds once after model/atlas reload and atomically publishes only primitive samples. */
    public static void rebuild(final BlockStateModelSet models, final BlockColors blockColors) {
        if (!WaterReflectionQualityConfig.isFaceAwareAppearanceEnabled()) {
            table = Table.EMPTY;
            Metallum.LOGGER.info("Skipped disabled face-aware water-reflection appearance table");
            return;
        }
        IdentityHashMap<BlockState, FacePalette> palettes = new IdentityHashMap<>();
        IdentityHashMap<SpriteContents, LinearColor> spriteColors = new IdentityHashMap<>();
        List<BlockStateModelPart> parts = new ArrayList<>();
        int failures = 0;

        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                try {
                    BlockStateModel model = models.get(state);
                    if (model == null || model == models.missingModel()) {
                        continue;
                    }
                    parts.clear();
                    model.collectParts(RandomSource.create(Block.getId(state)), parts);
                    FacePaletteBuilder palette = new FacePaletteBuilder();
                    for (BlockStateModelPart part : parts) {
                        for (Direction cullFace : Direction.values()) {
                            addQuads(palette, part.getQuads(cullFace), spriteColors);
                        }
                        addQuads(palette, part.getQuads(null), spriteColors);
                    }
                    FacePalette built = palette.build();
                    if (built != null) {
                        palettes.put(state, built);
                    }
                } catch (RuntimeException failure) {
                    failures++;
                }
            }
        }

        table = new Table(Map.copyOf(palettes), blockColors);
        if (palettes.isEmpty()) {
            Metallum.LOGGER.warn(
                    "Face-aware water-reflection appearance table is empty; source extraction will use MapColor"
            );
        } else {
            Metallum.LOGGER.info(
                    "Built face-aware water-reflection appearance for {} block states ({} fallbacks)",
                    palettes.size(), failures
            );
        }
    }

    /**
     * Resolves a linear RGB albedo into {@code outputRgb}. No allocations or texture reads occur
     * on the Sodium worker path.
     */
    public static boolean resolve(
            final BlockState state,
            final BlockAndTintGetter world,
            final BlockPos pos,
            final int exposedFaceMask,
            final float[] outputRgb
    ) {
        if (outputRgb == null || outputRgb.length < 3) {
            return false;
        }
        Table current = table;
        FacePalette palette = current.palettes().get(state);
        if (palette == null) {
            return false;
        }
        if (exposedFaceMask == 0) {
            // A fully enclosed block contributes opacity but has no visible face radiance. This
            // prevents hidden grass/foliage inside a 2x2x2 source cell from tinting its boundary.
            outputRgb[0] = 0.0F;
            outputRgb[1] = 0.0F;
            outputRgb[2] = 0.0F;
            return true;
        }
        return resolvePalette(
                palette, current.blockColors(), state, world, pos, exposedFaceMask, outputRgb
        );
    }

    static boolean resolvePalette(
            final FacePalette palette,
            final BlockColors blockColors,
            final BlockState state,
            final BlockAndTintGetter world,
            final BlockPos pos,
            final int exposedFaceMask,
            final float[] outputRgb
    ) {
        float totalWeight = 0.0F;
        outputRgb[0] = 0.0F;
        outputRgb[1] = 0.0F;
        outputRgb[2] = 0.0F;

        if ((exposedFaceMask & TOP_MASK) != 0) {
            totalWeight += accumulate(
                    palette.top(), TOP_EXPOSURE_WEIGHT, blockColors, state, world, pos, outputRgb
            );
        }
        int exposedSides = Integer.bitCount(exposedFaceMask & SIDE_MASK);
        if (exposedSides > 0) {
            totalWeight += accumulate(
                    palette.side(), SIDE_EXPOSURE_WEIGHT * exposedSides,
                    blockColors, state, world, pos, outputRgb
            );
        }
        if ((exposedFaceMask & BOTTOM_MASK) != 0) {
            totalWeight += accumulate(
                    palette.bottom(), BOTTOM_EXPOSURE_WEIGHT, blockColors, state, world, pos, outputRgb
            );
        }
        if (!(totalWeight > 1.0e-6F)) {
            return false;
        }
        float inverse = 1.0F / totalWeight;
        outputRgb[0] *= inverse;
        outputRgb[1] *= inverse;
        outputRgb[2] *= inverse;
        return Float.isFinite(outputRgb[0]) && Float.isFinite(outputRgb[1]) && Float.isFinite(outputRgb[2]);
    }

    private static float accumulate(
            final FaceGroup group,
            final float exposureWeight,
            final BlockColors blockColors,
            final BlockState state,
            final BlockAndTintGetter world,
            final BlockPos pos,
            final float[] outputRgb
    ) {
        if (group == null || !(exposureWeight > 0.0F)) {
            return 0.0F;
        }
        float groupWeight = 0.0F;
        for (FaceSample sample : group.samples()) {
            float tintRed = 1.0F;
            float tintGreen = 1.0F;
            float tintBlue = 1.0F;
            if (sample.tintIndex() >= 0 && blockColors != null && state != null && world != null && pos != null) {
                try {
                    BlockTintSource tintSource = blockColors.getTintSource(state, sample.tintIndex());
                    if (tintSource != null) {
                        int tint = tintSource.colorInWorld(state, world, pos);
                        tintRed = RadianceAppearanceModel.srgbToLinear((tint >> 16) & 0xff);
                        tintGreen = RadianceAppearanceModel.srgbToLinear((tint >> 8) & 0xff);
                        tintBlue = RadianceAppearanceModel.srgbToLinear(tint & 0xff);
                    }
                } catch (RuntimeException ignored) {
                    // A modded tint failure affects this sample only; its untinted texture remains usable.
                }
            }
            float weight = exposureWeight * sample.areaWeight();
            outputRgb[0] += sample.red() * tintRed * weight;
            outputRgb[1] += sample.green() * tintGreen * weight;
            outputRgb[2] += sample.blue() * tintBlue * weight;
            groupWeight += weight;
        }
        return groupWeight;
    }

    private static void addQuads(
            final FacePaletteBuilder palette,
            final List<BakedQuad> quads,
            final IdentityHashMap<SpriteContents, LinearColor> spriteColors
    ) {
        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite = quad.materialInfo().sprite();
            if (sprite == null) {
                continue;
            }
            LinearColor color = spriteColors.computeIfAbsent(sprite.contents(), FaceAwareRadianceAppearance::averageSprite);
            if (color == null) {
                continue;
            }
            float area = quadArea(quad);
            if (!(area > 1.0e-5F)) {
                area = 1.0F;
            }
            palette.add(quad.direction(), new FaceSample(
                    color.red(), color.green(), color.blue(), quad.materialInfo().tintIndex(), area
            ));
        }
    }

    private static LinearColor averageSprite(final SpriteContents contents) {
        try {
            NativeImage image = ((SpriteContentsImageAccess) (Object) contents).metallum$getOriginalImage();
            if (image == null || image.isClosed()) {
                return null;
            }
            double red = 0.0;
            double green = 0.0;
            double blue = 0.0;
            double alphaWeight = 0.0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getPixel(x, y);
                    float alpha = ((argb >>> 24) & 0xff) / 255.0F;
                    if (alpha <= 1.0F / 255.0F) {
                        continue;
                    }
                    red += RadianceAppearanceModel.srgbToLinear((argb >>> 16) & 0xff) * alpha;
                    green += RadianceAppearanceModel.srgbToLinear((argb >>> 8) & 0xff) * alpha;
                    blue += RadianceAppearanceModel.srgbToLinear(argb & 0xff) * alpha;
                    alphaWeight += alpha;
                }
            }
            if (!(alphaWeight > 1.0e-6)) {
                return null;
            }
            return new LinearColor(
                    (float) (red / alphaWeight),
                    (float) (green / alphaWeight),
                    (float) (blue / alphaWeight)
            );
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static float quadArea(final BakedQuad quad) {
        return triangleArea(quad.position0(), quad.position1(), quad.position2())
                + triangleArea(quad.position0(), quad.position2(), quad.position3());
    }

    private static float triangleArea(final Vector3fc a, final Vector3fc b, final Vector3fc c) {
        float abX = b.x() - a.x();
        float abY = b.y() - a.y();
        float abZ = b.z() - a.z();
        float acX = c.x() - a.x();
        float acY = c.y() - a.y();
        float acZ = c.z() - a.z();
        float crossX = abY * acZ - abZ * acY;
        float crossY = abZ * acX - abX * acZ;
        float crossZ = abX * acY - abY * acX;
        return 0.5F * (float) Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
    }

    static record FaceSample(float red, float green, float blue, int tintIndex, float areaWeight) {
    }

    static record FaceGroup(FaceSample[] samples) {
    }

    static record FacePalette(FaceGroup top, FaceGroup side, FaceGroup bottom) {
    }

    private record LinearColor(float red, float green, float blue) {
    }

    private record Table(Map<BlockState, FacePalette> palettes, BlockColors blockColors) {
        private static final Table EMPTY = new Table(Map.of(), null);
    }

    private static final class FacePaletteBuilder {
        private final List<FaceSample> top = new ArrayList<>();
        private final List<FaceSample> side = new ArrayList<>();
        private final List<FaceSample> bottom = new ArrayList<>();

        void add(final Direction direction, final FaceSample sample) {
            switch (direction) {
                case UP -> this.top.add(sample);
                case DOWN -> this.bottom.add(sample);
                default -> this.side.add(sample);
            }
        }

        FacePalette build() {
            if (this.top.isEmpty() && this.side.isEmpty() && this.bottom.isEmpty()) {
                return null;
            }
            return new FacePalette(group(this.top), group(this.side), group(this.bottom));
        }

        private static FaceGroup group(final List<FaceSample> samples) {
            return samples.isEmpty() ? null : new FaceGroup(samples.toArray(FaceSample[]::new));
        }
    }
}
