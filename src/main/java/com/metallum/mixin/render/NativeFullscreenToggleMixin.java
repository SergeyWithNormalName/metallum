package com.metallum.mixin.render;

import com.metallum.client.display.FullscreenSnapshot;
import com.metallum.client.display.NativeFullscreen;
import com.metallum.client.display.NativeFullscreenStartup;
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
        if (!MacosUtil.IS_MACOS || NativeFullscreenStartup.allowsGlfwModeChange()) {
            return;
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device == null || device.nativeFullscreen() == null) {
            return;
        }

        NativeFullscreen nativeFs = device.nativeFullscreen();
        nativeFs.setFullscreen(this.fullscreen);
        this.actuallyFullscreen = this.fullscreen;
        this.metallum$disableExclusiveFullscreen();

        ci.cancel();
    }

    @Inject(method = "updateFullscreenIfChanged", at = @At("HEAD"))
    private void metallum$syncAppKitFullscreenState(final CallbackInfo ci) {
        if (!MacosUtil.IS_MACOS || NativeFullscreenStartup.allowsGlfwModeChange()) {
            return;
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device == null || device.nativeFullscreen() == null) {
            return;
        }

        NativeFullscreen nativeFs = device.nativeFullscreen();
        if (this.fullscreen != this.actuallyFullscreen) {
            nativeFs.setFullscreen(this.fullscreen);
            this.actuallyFullscreen = this.fullscreen;
            this.metallum$disableExclusiveFullscreen();
            return;
        }

        FullscreenSnapshot snapshot = nativeFs.snapshot();
        boolean nativeIsFs = snapshot.isFullscreenOrEntering();
        Minecraft mc = Minecraft.getInstance();
        boolean optionNeedsSync = mc != null
                && mc.options != null
                && mc.options.fullscreen().get() != nativeIsFs;

        if (this.fullscreen != nativeIsFs || this.actuallyFullscreen != nativeIsFs || optionNeedsSync) {
            this.fullscreen = nativeIsFs;
            this.actuallyFullscreen = nativeIsFs;

            if (mc != null) {
                if (mc.options != null && mc.options.fullscreen().get() != nativeIsFs) {
                    mc.options.fullscreen().set(nativeIsFs);
                }
                this.metallum$disableExclusiveFullscreen();
                if (mc.gui != null && mc.gui.screen() != null) {
                    mc.gui.screen().init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                }
            }
        }
    }

    @Inject(method = "isFullscreen", at = @At("HEAD"), cancellable = true)
    private void metallum$nativeIsFullscreen(final CallbackInfoReturnable<Boolean> cir) {
        if (!MacosUtil.IS_MACOS || NativeFullscreenStartup.allowsGlfwModeChange()) {
            return;
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device != null && device.nativeFullscreen() != null) {
            FullscreenSnapshot snapshot = device.nativeFullscreen().snapshot();
            cir.setReturnValue(snapshot.isFullscreenOrEntering());
        }
    }

    private void metallum$disableExclusiveFullscreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null
                && minecraft.options != null
                && minecraft.options.exclusiveFullscreen().get()) {
            minecraft.options.exclusiveFullscreen().set(false);
        }
    }
}
