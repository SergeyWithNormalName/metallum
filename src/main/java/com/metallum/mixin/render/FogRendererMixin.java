package com.metallum.mixin.render;

import com.metallum.client.renderer.style.AtmosphereStylePolicy;
import com.metallum.client.renderer.style.VisualStyleRuntime;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
abstract class FogRendererMixin {

    @Inject(
            method = "setupFog",
            at = @At("RETURN")
    )
    private void metallum$applyStyleAtmosphere(
            final Camera camera,
            final int renderDistanceChunks,
            final DeltaTracker deltaTracker,
            final float bossOverlayDarkening,
            final ClientLevel clientLevel,
            final CallbackInfoReturnable<FogData> cir
    ) {
        FogData fogData = cir.getReturnValue();
        if (fogData != null) {
            AtmosphereStylePolicy.apply(
                    fogData,
                    camera,
                    renderDistanceChunks,
                    deltaTracker,
                    bossOverlayDarkening,
                    clientLevel,
                    VisualStyleRuntime.activeProfile()
            );
        }
    }
}
