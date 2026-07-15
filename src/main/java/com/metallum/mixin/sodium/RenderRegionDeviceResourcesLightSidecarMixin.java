package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumLightSidecar;
import com.metallum.client.sodium.SodiumLightSidecarArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.staging.StagingBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderRegion.DeviceResources.class, remap = false)
abstract class RenderRegionDeviceResourcesLightSidecarMixin {
    @Shadow
    @Final
    private GlBufferArena geometryArena;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$enableGeometryLightSidecar(
            final StagingBuffer stagingBuffer,
            final CallbackInfo ci
    ) {
        if (SodiumLightSidecar.isConfigured()) {
            ((SodiumLightSidecarArena) this.geometryArena).metallum$enableLightSidecar();
        }
    }
}
