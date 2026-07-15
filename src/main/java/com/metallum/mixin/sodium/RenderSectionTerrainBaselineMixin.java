package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumTerrainUploadBaseline;
import com.metallum.client.sodium.SodiumTerrainUploadBaselineAccess;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSection.class, remap = false)
abstract class RenderSectionTerrainBaselineMixin implements SodiumTerrainUploadBaselineAccess {
    @Unique
    @Nullable
    private SodiumTerrainUploadBaseline metallum$terrainUploadBaseline;

    @Override
    @Nullable
    public SodiumTerrainUploadBaseline metallum$getTerrainUploadBaseline() {
        return this.metallum$terrainUploadBaseline;
    }

    @Override
    public void metallum$setTerrainUploadBaseline(@Nullable final SodiumTerrainUploadBaseline baseline) {
        SodiumTerrainUploadBaseline previous = this.metallum$terrainUploadBaseline;
        if (previous == baseline) {
            return;
        }
        this.metallum$terrainUploadBaseline = baseline;
        if (previous != null) {
            previous.close();
        }
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void metallum$releaseTerrainUploadBaseline(final CallbackInfo ci) {
        this.metallum$setTerrainUploadBaseline(null);
    }
}
