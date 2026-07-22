package com.metallum.mixin.render;

import com.metallum.client.metalfx.DrsResolutionOverlayHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class GuiDrsResolutionOverlayMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void metallum$renderResolutionOverlay(
            final GuiGraphicsExtractor graphics,
            final DeltaTracker deltaTracker,
            final CallbackInfo callback
    ) {
        DrsResolutionOverlayHud.render(graphics);
    }
}
