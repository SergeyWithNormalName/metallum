package com.metallum.mixin.sodium;

import com.metallum.client.display.FullscreenSnapshot;
import com.metallum.client.display.NativeFullscreen;
import com.metallum.client.metal.render.MetalDevice;
import com.mojang.blaze3d.platform.MacosUtil;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.gui.options.FullscreenMode;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EnumOption.class, remap = false)
abstract class SodiumEnumOptionMixin {
    private static final Identifier FULLSCREEN_MODE_ID = Identifier.fromNamespaceAndPath("sodium", "general.fullscreen_mode");

    @Shadow private Identifier id;

    @Inject(method = "isValueAllowed", at = @At("HEAD"), cancellable = true)
    private void metallum$disallowExclusiveFullscreenOnMac(final Object value, final CallbackInfoReturnable<Boolean> cir) {
        if (!MacosUtil.IS_MACOS) {
            return;
        }

        if (FULLSCREEN_MODE_ID.equals(this.id)) {
            if (value == FullscreenMode.EXCLUSIVE) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "getValidatedValue", at = @At("HEAD"), cancellable = true)
    private void metallum$dynamicFullscreenModeValue(final CallbackInfoReturnable<Object> cir) {
        if (!MacosUtil.IS_MACOS) {
            return;
        }

        if (FULLSCREEN_MODE_ID.equals(this.id)) {
            MetalDevice device = MetalDevice.getInstance();
            if (device != null && device.nativeFullscreen() != null) {
                FullscreenSnapshot snapshot = device.nativeFullscreen().snapshot();
                if (snapshot.isFullscreenOrEntering()) {
                    cir.setReturnValue(FullscreenMode.BORDERLESS);
                } else {
                    cir.setReturnValue(FullscreenMode.OFF);
                }
            }
        }
    }

    @Inject(method = "modifyValue", at = @At("HEAD"))
    private void metallum$onModifyFullscreenMode(final Object value, final CallbackInfo ci) {
        if (!MacosUtil.IS_MACOS) {
            return;
        }

        if (FULLSCREEN_MODE_ID.equals(this.id)) {
            MetalDevice device = MetalDevice.getInstance();
            if (device != null && device.nativeFullscreen() != null) {
                boolean targetFs = (value != FullscreenMode.OFF);
                device.nativeFullscreen().setFullscreen(targetFs);
            }
        }
    }
}
