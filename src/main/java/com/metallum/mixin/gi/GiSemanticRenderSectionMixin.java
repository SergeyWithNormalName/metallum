package com.metallum.mixin.gi;

import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.gi.semantic.GiSemanticEmptyTaskSlot;
import com.metallum.client.gi.semantic.GiSemanticResidentSlot;
import com.metallum.client.gi.semantic.GiSemanticSectionTask;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSection.class, remap = false)
abstract class GiSemanticRenderSectionMixin implements GiSemanticResidentSlot, GiSemanticEmptyTaskSlot {
    @Unique private Object metallum$giWorld;
    @Unique private long metallum$giSectionKey;
    @Unique private long metallum$giOwner;
    @Unique @Nullable private GiSemanticSectionTask metallum$emptyGiTask;

    @Override
    public void metallum$bindGiSemanticSection(final Object worldIdentity, final long sectionKey) {
        if (worldIdentity == null) throw new NullPointerException("worldIdentity");
        if (this.metallum$giWorld != null
                && (this.metallum$giWorld != worldIdentity || this.metallum$giSectionKey != sectionKey)) {
            throw new IllegalStateException("RenderSection G2 lifecycle was rebound");
        }
        this.metallum$giWorld = worldIdentity;
        this.metallum$giSectionKey = sectionKey;
    }

    @Override public Object metallum$getGiSemanticWorldIdentity() { return this.metallum$giWorld; }
    @Override public long metallum$getGiSemanticSectionKey() { return this.metallum$giSectionKey; }
    @Override public long metallum$getGiSemanticOwnerToken() { return this.metallum$giOwner; }

    @Override
    public void metallum$setGiSemanticOwnerToken(final long ownerToken) {
        if (ownerToken < 0L) throw new IllegalArgumentException("G2 owner must be non-negative");
        this.metallum$giOwner = ownerToken;
    }

    @Override
    public synchronized void metallum$setEmptyGiSemanticTask(final GiSemanticSectionTask task) {
        this.metallum$emptyGiTask = java.util.Objects.requireNonNull(task, "task");
    }

    @Override
    @Nullable
    public synchronized GiSemanticSectionTask metallum$claimEmptyGiSemanticTask() {
        GiSemanticSectionTask task = this.metallum$emptyGiTask;
        this.metallum$emptyGiTask = null;
        return task;
    }

    @Inject(method = "delete()V", at = @At("HEAD"))
    private void metallum$deleteGiSemanticOwner(final CallbackInfo ci) {
        this.metallum$emptyGiTask = null;
        if (this.metallum$giWorld != null) {
            GiSemanticController.global().removeSectionIfOwner(
                    this.metallum$giWorld, this.metallum$giSectionKey, this.metallum$giOwner
            );
        }
    }
}
