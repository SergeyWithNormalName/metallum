package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRainExposureSnapshot;
import com.metallum.client.sodium.SodiumRainExposureSnapshotAccess;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
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
        boolean[] rainfallColumns = new boolean[SodiumRainExposureSnapshot.AREA];
        BlockPos.MutableBlockPos precipitationPosition = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < SodiumRainExposureSnapshot.WIDTH; localZ++) {
            for (int localX = 0; localX < SodiumRainExposureSnapshot.WIDTH; localX++) {
                int column = (localZ << 4) | localX;
                int blockX = minBlockX + localX;
                int blockZ = minBlockZ + localZ;
                int precipitationHeight = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING,
                        blockX,
                        blockZ
                );
                heights[column] = precipitationHeight;
                // Use the exact location-sensitive vanilla result instead of the global rain
                // level: deserts report NONE and cold columns report SNOW, neither of which
                // should make terrain wet or add rain-driven specular highlights.
                precipitationPosition.set(blockX, precipitationHeight, blockZ);
                rainfallColumns[column] = level.precipitationAt(precipitationPosition)
                        == Biome.Precipitation.RAIN;
            }
        }
        ((SodiumRainExposureSnapshotAccess) context).metallum$setRainExposureSnapshot(
                new SodiumRainExposureSnapshot(minBlockX, minBlockZ, heights, rainfallColumns));
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
