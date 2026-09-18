package com.metallum.client.gi.capture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import java.util.HashMap;
import java.util.Map;

/** Immutable atlas-epoch copy of alpha-weighted linear sprite averages. */
public final class GiSemanticSpriteColorAtlas {
    public record LinearRgb(float red, float green, float blue) {
    }

    private static volatile Map<Identifier, LinearRgb> colors = Map.of();

    private GiSemanticSpriteColorAtlas() {
    }

    public static void publish(final SpriteLoader.Preparations preparations) {
        Map<Identifier, LinearRgb> next = new HashMap<>();
        for (Map.Entry<Identifier, TextureAtlasSprite> entry : preparations.regions().entrySet()) {
            try {
                NativeImage image = ((com.metallum.mixin.gi.GiSemanticSpriteContentsAccessor)
                        (Object) entry.getValue().contents()).metallum$getOriginalImage();
                next.put(entry.getKey(), average(image));
            } catch (RuntimeException ignored) {
                // One malformed modded sprite becomes unknown; the completed atlas still advances.
            }
        }
        colors = Map.copyOf(next);
    }

    public static LinearRgb color(final Identifier spriteId) {
        return colors.get(spriteId);
    }

    public static boolean contains(final Identifier spriteId) {
        return colors.containsKey(spriteId);
    }

    static LinearRgb average(final NativeImage image) {
        if (image == null || image.isClosed() || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return new LinearRgb(0.0F, 0.0F, 0.0F);
        }
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;
        double alphaWeight = 0.0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixel(x, y);
                double alpha = ARGB.alpha(pixel) / 255.0;
                if (alpha <= 0.0) continue;
                red += com.metallum.client.gi.semantic.GiSemanticPacking.srgbToLinear(
                        ARGB.red(pixel)
                ) * alpha;
                green += com.metallum.client.gi.semantic.GiSemanticPacking.srgbToLinear(
                        ARGB.green(pixel)
                ) * alpha;
                blue += com.metallum.client.gi.semantic.GiSemanticPacking.srgbToLinear(
                        ARGB.blue(pixel)
                ) * alpha;
                alphaWeight += alpha;
            }
        }
        if (alphaWeight <= 0.0) {
            return new LinearRgb(0.0F, 0.0F, 0.0F);
        }
        return new LinearRgb(
                (float) (red / alphaWeight),
                (float) (green / alphaWeight),
                (float) (blue / alphaWeight)
        );
    }
}
