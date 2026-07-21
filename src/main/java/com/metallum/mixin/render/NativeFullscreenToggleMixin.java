package com.metallum.mixin.render;

import com.metallum.client.display.FullscreenSnapshot;
import com.metallum.client.display.NativeFullscreen;
import com.metallum.client.metal.render.MetalDevice;
import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
abstract class NativeFullscreenToggleMixin {
    @Shadow private boolean fullscreen;
    @Shadow private boolean actuallyFullscreen;

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void metallum$interceptSetMode(final CallbackInfo ci) {
        if (!MacosUtil.IS_MACOS) {
            return;
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device == null || device.nativeFullscreen() == null) {
            return;
        }

        NativeFullscreen nativeFs = device.nativeFullscreen();
        nativeFs.setFullscreen(this.fullscreen);
        this.actuallyFullscreen = this.fullscreen;

        ci.cancel();
    }

    @Inject(method = "updateFullscreenIfChanged", at = @At("HEAD"))
    private void metallum$syncAppKitFullscreenState(final CallbackInfo ci) {
        if (!MacosUtil.IS_MACOS) {
            return;
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device == null || device.nativeFullscreen() == null) {
            return;
        }

        NativeFullscreen nativeFs = device.nativeFullscreen();
        FullscreenSnapshot snapshot = nativeFs.snapshot();
        boolean nativeIsFs = snapshot.isFullscreenOrEntering();
        boolean nativeActualFs = snapshot.isFullscreen();

        if (this.fullscreen != nativeIsFs || this.actuallyFullscreen != nativeActualFs) {
            this.fullscreen = nativeIsFs;
            this.actuallyFullscreen = nativeActualFs;

            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                if (mc.options != null && mc.options.fullscreen().get() != nativeIsFs) {
                    mc.options.fullscreen().set(nativeIsFs);
                }
                if (mc.gui != null && mc.gui.screen() != null) {
                    mc.gui.screen().init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                }
            }
        }
    }

    @Inject(method = "isFullscreen", at = @At("HEAD"), cancellable = true)
    private void metallum$nativeIsFullscreen(final CallbackInfoReturnable<Boolean> cir) {
        if (!MacosUtil.IS_MACOS) {
            return;
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device != null && device.nativeFullscreen() != null) {
            FullscreenSnapshot snapshot = device.nativeFullscreen().snapshot();
            cir.setReturnValue(snapshot.isFullscreenOrEntering());
        }
    }
}
