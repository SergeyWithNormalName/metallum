package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSceneState;
import com.mojang.blaze3d.GpuFormat;
import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Lightmap.class)
abstract class LightmapMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"
            ),
            index = 2,
            require = 1
    )
    private GpuFormat metallum$upgradeWorldLightmap(final GpuFormat original) {
        return HdrSceneState.isRequested() ? GpuFormat.RGBA16_FLOAT : original;
    }
}
