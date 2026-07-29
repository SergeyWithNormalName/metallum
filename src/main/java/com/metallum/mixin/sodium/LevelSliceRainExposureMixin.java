package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRainExposureSnapshot;
import com.metallum.client.sodium.SodiumRainExposureSnapshotAccess;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures vanilla's precipitation-blocking heightmap before the mesh task leaves Level access. */
@Mixin(value = LevelSlice.class, remap = false)
abstract class LevelSliceRainExposureMixin implements SodiumRainExposureSnapshotAccess {
    @Unique
    @Nullable
    private SodiumRainExposureSnapshot metallum$rainExposureSnapshot;

    @Inject(method = "prepare", at = @At("RETURN"))
    private static void metallum$captureRainExposure(
            final Level level,
            final SectionPos sectionPos,
            final ClonedChunkSectionCache cache,
            final CallbackInfoReturnable<ChunkRenderContext> cir
    ) {
        ChunkRenderContext context = cir.getReturnValue();
        if (context == null) {
            return;
        }

        int minBlockX = sectionPos.minBlockX();
        int minBlockZ = sectionPos.minBlockZ();
        int[] heights = new int[SodiumRainExposureSnapshot.AREA];
        for (int localZ = 0; localZ < SodiumRainExposureSnapshot.WIDTH; localZ++) {
            for (int localX = 0; localX < SodiumRainExposureSnapshot.WIDTH; localX++) {
                heights[(localZ << 4) | localX] = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING,
                        minBlockX + localX,
                        minBlockZ + localZ
                );
            }
        }
        ((SodiumRainExposureSnapshotAccess) context).metallum$setRainExposureSnapshot(
                new SodiumRainExposureSnapshot(minBlockX, minBlockZ, heights));
    }

    @Inject(method = "copyData", at = @At("HEAD"))
    private void metallum$copyRainExposure(
            final ChunkRenderContext context,
            final CallbackInfo ci
    ) {
        this.metallum$rainExposureSnapshot =
                ((SodiumRainExposureSnapshotAccess) context).metallum$getRainExposureSnapshot();
    }

    @Inject(method = "reset", at = @At("TAIL"))
    private void metallum$clearRainExposure(final CallbackInfo ci) {
        this.metallum$rainExposureSnapshot = null;
    }

    @Override
    @Nullable
    public SodiumRainExposureSnapshot metallum$getRainExposureSnapshot() {
        return this.metallum$rainExposureSnapshot;
    }

    @Override
    public void metallum$setRainExposureSnapshot(
            @Nullable final SodiumRainExposureSnapshot snapshot
    ) {
        this.metallum$rainExposureSnapshot = snapshot;
    }
}
