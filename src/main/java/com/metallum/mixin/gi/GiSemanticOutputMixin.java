package com.metallum.mixin.gi;

import com.metallum.client.gi.semantic.GiSemanticCandidateSlot;
import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.gi.semantic.GiSemanticEmptyTaskSlot;
import com.metallum.client.gi.semantic.GiSemanticSectionCandidate;
import com.metallum.client.gi.semantic.GiSemanticSectionTask;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.TranslucentData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = ChunkBuildOutput.class, remap = false)
abstract class GiSemanticOutputMixin implements GiSemanticCandidateSlot {
    @Unique @Nullable private GiSemanticSectionCandidate metallum$giCandidate;

    @Inject(method = "<init>(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/data/TranslucentData;Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;Ljava/util/Map;Z)V", at = @At("RETURN"))
    private void metallum$captureGiAuthoritativeEmpty(final RenderSection section, final int buildTime,
                                                      @Nullable final TranslucentData translucentData,
                                                      final BuiltSectionInfo info,
                                                      final Map<TerrainRenderPass, BuiltSectionMeshParts> meshes,
                                                      final boolean blockingTask, final CallbackInfo ci) {
        if (info != BuiltSectionInfo.EMPTY) return;
        try {
            GiSemanticSectionTask task = ((GiSemanticEmptyTaskSlot) section).metallum$claimEmptyGiSemanticTask();
            if (task != null) {
                this.metallum$setGiSemanticCandidate(
                        GiSemanticController.global().createAuthoritativeEmptyCandidate(task)
                );
            }
        } catch (RuntimeException ignored) {
            // Authoritative Sodium empty geometry remains valid; G2 stays unknown.
        }
    }

    @Override
    public synchronized void metallum$setGiSemanticCandidate(@Nullable final GiSemanticSectionCandidate candidate) {
        GiSemanticSectionCandidate previous = this.metallum$giCandidate;
        if (previous == candidate) return;
        this.metallum$giCandidate = candidate;
        GiSemanticController.global().discardCandidate(previous);
    }

    @Override
    @Nullable
    public synchronized GiSemanticSectionCandidate metallum$takeGiSemanticCandidate() {
        GiSemanticSectionCandidate candidate = this.metallum$giCandidate;
        this.metallum$giCandidate = null;
        return candidate;
    }

    @Override
    public synchronized void metallum$discardGiSemanticCandidate() {
        GiSemanticController.global().discardCandidate(this.metallum$takeGiSemanticCandidate());
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void metallum$discardDestroyedGiCandidate(final CallbackInfo ci) {
        this.metallum$discardGiSemanticCandidate();
    }
}
