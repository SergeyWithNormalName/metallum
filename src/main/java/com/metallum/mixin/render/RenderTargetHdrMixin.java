package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrSceneState;
import com.metallum.client.hdr.MetallumMaterialState;
import com.metallum.client.metal.render.MetalHdrFrame;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives every FP16 scene RenderTarget an attachment-owned clear contract. */
@Mixin(RenderTarget.class)
abstract class RenderTargetHdrMixin {
    @Shadow
    protected GpuTexture colorTexture;

    @Shadow @Final
    protected GpuFormat format;

    @Inject(method = "createBuffers", at = @At("RETURN"))
    private void metallum$markFp16SceneColorAfterAllocation(
            final int width,
            final int height,
            final CallbackInfo ci
    ) {
        boolean legacyFp16Scene = HdrSceneState.isRequested()
                && this.format == GpuFormat.RGBA16_FLOAT;
        boolean materialMainScene = MetallumMaterialState.isRequested()
                && ((Object) this) instanceof MainTarget;
        if ((legacyFp16Scene || materialMainScene)
                && this.colorTexture != null) {
            MetalHdrFrame.markSceneColor(this.colorTexture);
        }
    }
}
