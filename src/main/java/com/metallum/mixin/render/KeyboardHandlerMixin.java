package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.metallum.client.hdr.HdrConfig;
import com.metallum.client.metal.render.MetalDevice;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void metallum$disableDiagnosticPatternOnEscape(
            final long window,
            final int action,
            final KeyEvent event,
            final CallbackInfo ci
    ) {
        if (action != InputConstants.PRESS || event.key() != InputConstants.KEY_ESCAPE) {
            return;
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            return;
        }

        HdrConfig config = device.hdrConfig();
        if (!config.diagnosticPattern()) {
            return;
        }

        device.updateHdrConfig(config.withDiagnosticPattern(false));
        Metallum.LOGGER.info("HDR calibration pattern disabled with Escape");
    }
}
