package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRainExposureSnapshot;
import com.metallum.client.sodium.SodiumRainExposureSnapshotAccess;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ChunkRenderContext.class, remap = false)
abstract class ChunkRenderContextRainExposureMixin implements SodiumRainExposureSnapshotAccess {
    @Unique
    @Nullable
    private SodiumRainExposureSnapshot metallum$rainExposureSnapshot;

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
