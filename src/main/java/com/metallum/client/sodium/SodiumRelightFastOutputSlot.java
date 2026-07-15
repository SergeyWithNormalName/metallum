package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.jspecify.annotations.Nullable;

/** Lifecycle marker carried by one self-contained synthetic relight output. */
public interface SodiumRelightFastOutputSlot {
    void metallum$markFastRelightOutput(
            long geometryEpoch,
            int residentGeneration,
            BuiltSectionInfo residentInfo,
            SodiumTerrainUploadBaseline residentBaseline
    );

    boolean metallum$isFastRelightOutput();

    long metallum$getFastRelightGeometryEpoch();

    int metallum$getFastRelightResidentGeneration();

    @Nullable
    BuiltSectionInfo metallum$getFastRelightResidentInfo();

    @Nullable
    SodiumTerrainUploadBaseline metallum$getFastRelightResidentBaseline();

    void metallum$markFastRelightAccepted();

    boolean metallum$wasFastRelightAccepted();
}
