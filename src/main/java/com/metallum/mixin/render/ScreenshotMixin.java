package com.metallum.mixin.render;

import com.metallum.client.hdr.HdrScreenshot;
import com.metallum.client.hdr.HdrUiRenderTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(Screenshot.class)
abstract class ScreenshotMixin {
    @Inject(
            method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void metallum$captureFp16AsSdr(
            final RenderTarget target,
            final int downscale,
            final Consumer<NativeImage> callback,
            final CallbackInfo ci
    ) {
        RenderTarget uiTarget = HdrUiRenderTarget.screenshotTargetFor(target);
        if (uiTarget != null && uiTarget != target) {
            Screenshot.takeScreenshot(uiTarget, downscale, callback);
            ci.cancel();
            return;
        }

        if (HdrScreenshot.capture(target, downscale, callback)) {
            ci.cancel();
        }
    }
}
