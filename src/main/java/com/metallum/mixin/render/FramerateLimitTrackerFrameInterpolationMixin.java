package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.renderer.interpolation.FrameInterpolationCompatibilityProfile;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies FI Auto's source cadence without rewriting the user's saved FPS setting. */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerFrameInterpolationMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void metallum$applyFrameInterpolationSourceLimit(
            final CallbackInfoReturnable<Integer> cir
    ) {
        MetalDevice device = MetalDevice.getInstance();
        cir.setReturnValue(FrameInterpolationCompatibilityProfile.applySourceLimit(
                cir.getReturnValue(),
                device != null && device.frameInterpolationSourceLimitActive()
        ));
    }
}
