package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRelightFastOutputSlot;
import com.metallum.client.sodium.SodiumRelightFastPath;
import com.metallum.client.sodium.SodiumTerrainUploadBaseline;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/** Owns only immutable validation references; mesh buffers remain Sodium-owned. */
@Mixin(value = ChunkBuildOutput.class, remap = false)
abstract class ChunkBuildOutputRelightFastMixin implements SodiumRelightFastOutputSlot {
    @Unique
    private boolean metallum$fastRelightOutput;
    @Unique
    private long metallum$fastRelightGeometryEpoch;
    @Unique
    private int metallum$fastRelightResidentGeneration;
    @Unique
    @Nullable
    private BuiltSectionInfo metallum$fastRelightResidentInfo;
    @Unique
    @Nullable
    private SodiumTerrainUploadBaseline metallum$fastRelightResidentBaseline;
    @Unique
    private boolean metallum$fastRelightAccepted;

    @Override
    public synchronized void metallum$markFastRelightOutput(
            final long geometryEpoch,
            final int residentGeneration,
            final BuiltSectionInfo residentInfo,
            final SodiumTerrainUploadBaseline residentBaseline
    ) {
        if (this.metallum$fastRelightOutput) {
            throw new IllegalStateException("fast relight output marker was assigned twice");
        }
        this.metallum$fastRelightOutput = true;
        this.metallum$fastRelightGeometryEpoch = geometryEpoch;
        this.metallum$fastRelightResidentGeneration = residentGeneration;
        this.metallum$fastRelightResidentInfo = Objects.requireNonNull(residentInfo, "residentInfo");
        this.metallum$fastRelightResidentBaseline = Objects.requireNonNull(
                residentBaseline,
                "residentBaseline"
        );
    }

    @Override
    public boolean metallum$isFastRelightOutput() {
        return this.metallum$fastRelightOutput;
    }

    @Override
    public long metallum$getFastRelightGeometryEpoch() {
        return this.metallum$fastRelightGeometryEpoch;
    }

    @Override
    public int metallum$getFastRelightResidentGeneration() {
        return this.metallum$fastRelightResidentGeneration;
    }

    @Override
    @Nullable
    public BuiltSectionInfo metallum$getFastRelightResidentInfo() {
        return this.metallum$fastRelightResidentInfo;
    }

    @Override
    @Nullable
    public SodiumTerrainUploadBaseline metallum$getFastRelightResidentBaseline() {
        return this.metallum$fastRelightResidentBaseline;
    }

    @Override
    public void metallum$markFastRelightAccepted() {
        this.metallum$fastRelightAccepted = true;
    }

    @Override
    public boolean metallum$wasFastRelightAccepted() {
        return this.metallum$fastRelightAccepted;
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void metallum$recordUnacceptedFastOutput(final CallbackInfo ci) {
        if (this.metallum$fastRelightOutput) {
            SodiumRelightFastPath.recordDestroyedOutput(this.metallum$fastRelightAccepted);
        }
    }
}
