package com.metallum.mixin.sodium;

import com.metallum.client.lighting.AdvancedLightResidentSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Associates lifecycle deletion with the exact accepted registry owner. */
@Mixin(value = RenderSection.class, remap = false)
abstract class RenderSectionAdvancedLightMixin implements AdvancedLightResidentSlot {
    @Unique
    private Object metallum$advancedLightWorldIdentity;

    @Unique
    private long metallum$advancedLightSectionKey;

    @Unique
    private long metallum$advancedLightOwnerToken;

    @Override
    public void metallum$bindAdvancedLightSection(
            final Object worldIdentity,
            final long sectionKey
    ) {
        if (worldIdentity == null) {
            throw new NullPointerException("worldIdentity");
        }
        if (this.metallum$advancedLightWorldIdentity != null
                && (this.metallum$advancedLightWorldIdentity != worldIdentity
                || this.metallum$advancedLightSectionKey != sectionKey)) {
            throw new IllegalStateException("RenderSection light lifecycle was rebound");
        }
        this.metallum$advancedLightWorldIdentity = worldIdentity;
        this.metallum$advancedLightSectionKey = sectionKey;
    }

    @Override
    public Object metallum$getAdvancedLightWorldIdentity() {
        return this.metallum$advancedLightWorldIdentity;
    }

    @Override
    public long metallum$getAdvancedLightSectionKey() {
        return this.metallum$advancedLightSectionKey;
    }

    @Override
    public long metallum$getAdvancedLightOwnerToken() {
        return this.metallum$advancedLightOwnerToken;
    }

    @Override
    public void metallum$setAdvancedLightOwnerToken(final long ownerToken) {
        if (ownerToken < 0L) {
            throw new IllegalArgumentException("Advanced light owner token must be non-negative");
        }
        this.metallum$advancedLightOwnerToken = ownerToken;
    }

    @Inject(method = "delete()V", at = @At("HEAD"))
    private void metallum$deleteOwnedAdvancedLights(final CallbackInfo ci) {
        if (this.metallum$advancedLightWorldIdentity != null) {
            com.metallum.client.lighting.AdvancedLightRegistry.global().removeSectionIfOwner(
                    this.metallum$advancedLightWorldIdentity,
                    this.metallum$advancedLightSectionKey,
                    this.metallum$advancedLightOwnerToken
            );
        }
    }
}
