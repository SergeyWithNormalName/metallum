package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSceneState;
import com.mojang.blaze3d.GpuFormat;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;<init>(IIZLorg/joml/Vector4fc;Lcom/mojang/blaze3d/GpuFormat;)V"
            ),
            index = 4,
            require = 1
    )
    private GpuFormat metallum$upgradeSceneContinuationTargets(final GpuFormat original) {
        return HdrSceneState.isRequested() && original == GpuFormat.RGBA8_UNORM
                ? GpuFormat.RGBA16_FLOAT
                : original;
    }
}
