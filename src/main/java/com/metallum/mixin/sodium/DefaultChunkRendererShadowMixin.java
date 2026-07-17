package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.SunShadowRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Selects directional-light-facing mesh slices without depending on the main camera. */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
abstract class DefaultChunkRendererShadowMixin {
    @Redirect(
            method = "fillCommandBuffer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/"
                            + "DefaultChunkRenderer;getVisibleFaces(IIIIII)I"
            ),
            require = 1
    )
    private static int metallum$useLightFacingSlices(
            final int originX,
            final int originY,
            final int originZ,
            final int chunkX,
            final int chunkY,
            final int chunkZ
    ) {
        Vector3fc toLight = SunShadowRenderer.activeTerrainToLightWorld();
        if (toLight == null) {
            return DefaultChunkRenderer.getVisibleFaces(
                    originX,
                    originY,
                    originZ,
                    chunkX,
                    chunkY,
                    chunkZ
            );
        }
        int centerX = (chunkX << 4) + 8;
        int centerY = (chunkY << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;
        int lightX = directionalOrigin(centerX, toLight.x());
        int lightY = directionalOrigin(centerY, toLight.y());
        int lightZ = directionalOrigin(centerZ, toLight.z());
        return DefaultChunkRenderer.getVisibleFaces(
                lightX,
                lightY,
                lightZ,
                chunkX,
                chunkY,
                chunkZ
        );
    }

    private static int directionalOrigin(final int center, final float direction) {
        if (direction > 1.0e-4f) {
            return center + 1_000_000;
        }
        if (direction < -1.0e-4f) {
            return center - 1_000_000;
        }
        return center;
    }
}
