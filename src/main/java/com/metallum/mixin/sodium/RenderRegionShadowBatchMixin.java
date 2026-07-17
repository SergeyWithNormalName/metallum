package com.metallum.mixin.sodium;

import com.metallum.client.metal.render.SunShadowRenderer;
import com.metallum.client.sodium.SodiumShadowBatchAccess;
import com.metallum.client.sodium.SodiumShadowBatchCache;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents shadow caster lists from invalidating Sodium's main-camera draw batches. */
@Mixin(value = RenderRegion.class, remap = false)
abstract class RenderRegionShadowBatchMixin implements SodiumShadowBatchAccess {
    @Unique
    private SodiumShadowBatchCache metallum$shadowBatchCache;

    @Override
    public void metallum$prepareShadowBatch(
            final long cascadeToken,
            final long sections0,
            final long sections1,
            final long sections2,
            final long sections3
    ) {
        if (this.metallum$shadowBatchCache == null) {
            this.metallum$shadowBatchCache = new SodiumShadowBatchCache();
        }
        this.metallum$shadowBatchCache.prepare(
                cascadeToken,
                sections0,
                sections1,
                sections2,
                sections3
        );
    }

    @Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true)
    private void metallum$useShadowBatch(
            final TerrainRenderPass pass,
            final CallbackInfoReturnable<MultiDrawBatch> cir
    ) {
        long token = SunShadowRenderer.activeCascadeToken();
        if (token == 0L) {
            return;
        }
        if (this.metallum$shadowBatchCache == null) {
            this.metallum$shadowBatchCache = new SodiumShadowBatchCache();
        }
        cir.setReturnValue(this.metallum$shadowBatchCache.acquire(pass, token));
    }

    @Inject(method = "onBufferResized", at = @At("TAIL"))
    private void metallum$invalidateResizedShadowBatches(final CallbackInfo ci) {
        if (this.metallum$shadowBatchCache != null) {
            this.metallum$shadowBatchCache.clearAll();
        }
    }

    @Inject(method = "removeSection", at = @At("TAIL"))
    private void metallum$invalidateRemovedShadowSection(final CallbackInfo ci) {
        if (this.metallum$shadowBatchCache != null) {
            this.metallum$shadowBatchCache.clearAll();
        }
    }

    @Inject(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/"
                            + "RenderRegion$DeviceResources;delete()V",
                    shift = At.Shift.AFTER
            ),
            require = 1
    )
    private void metallum$invalidateDeletedShadowResources(final CallbackInfo ci) {
        if (this.metallum$shadowBatchCache != null) {
            this.metallum$shadowBatchCache.clearAll();
        }
    }

    @Inject(method = "clearCachedBatchFor", at = @At("TAIL"))
    private void metallum$clearShadowBatch(
            final TerrainRenderPass pass,
            final CallbackInfo ci
    ) {
        if (this.metallum$shadowBatchCache != null) {
            this.metallum$shadowBatchCache.clear(pass);
        }
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void metallum$deleteShadowBatches(final CallbackInfo ci) {
        if (this.metallum$shadowBatchCache != null) {
            this.metallum$shadowBatchCache.delete();
            this.metallum$shadowBatchCache = null;
        }
    }
}
