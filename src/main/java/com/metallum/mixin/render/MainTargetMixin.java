package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSceneState;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.MainTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MainTarget.class)
abstract class MainTargetMixin {
    @ModifyArg(
            method = "allocateColorAttachment(Lcom/mojang/blaze3d/pipeline/MainTarget$Dimension;)Lcom/mojang/blaze3d/textures/GpuTexture;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
                    ordinal = 0
            ),
            index = 2,
            require = 1
    )
    private GpuFormat metallum$modifyMainTargetFormat(GpuFormat original) {
        return HdrSceneState.isRequested() ? GpuFormat.RGBA16_FLOAT : original;
    }

    @Inject(method = "<init>(II)V", at = @At("RETURN"))
    private void metallum$persistMainTargetFormat(final int width, final int height, final CallbackInfo ci) {
        if (HdrSceneState.isRequested()) {
            // MainTarget's private initial allocator is separate from the
            // inherited resize path. Keep RenderTarget.format in sync so a
            // resize/fullscreen transition cannot silently return to RGBA8.
            ((RenderTargetAccessor) this).metallum$setFormat(GpuFormat.RGBA16_FLOAT);
            com.metallum.Metallum.LOGGER.info("MainTarget color format: RGBA16_FLOAT");
        }
    }
}
