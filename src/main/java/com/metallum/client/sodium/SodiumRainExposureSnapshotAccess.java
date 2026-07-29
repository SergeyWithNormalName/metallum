package com.metallum.client.sodium;

import org.jspecify.annotations.Nullable;

/** Internal slot shared by Sodium's main-thread render context and reusable worker level slice. */
public interface SodiumRainExposureSnapshotAccess {
    @Nullable
    SodiumRainExposureSnapshot metallum$getRainExposureSnapshot();

    void metallum$setRainExposureSnapshot(@Nullable SodiumRainExposureSnapshot snapshot);
}
