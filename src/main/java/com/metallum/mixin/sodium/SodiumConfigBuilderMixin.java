package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.MetalDevice;
import com.mojang.blaze3d.platform.MacosUtil;
import net.caffeinemc.mods.sodium.client.gui.options.FullscreenMode;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder")
abstract class SodiumConfigBuilderMixin {
    @Inject(method = "lambda$buildGeneralPage$3", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$simplifyFullscreenModeName(final FullscreenMode mode, final CallbackInfoReturnable<Component> cir) {
        if (!MacosUtil.IS_MACOS) {
            return;
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            return;
        }

        if (mode == FullscreenMode.OFF) {
            cir.setReturnValue(Component.translatable("options.off"));
        } else {
            cir.setReturnValue(Component.translatable("options.on"));
        }
    }
}
