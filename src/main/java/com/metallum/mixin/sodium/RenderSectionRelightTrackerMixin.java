package com.metallum.mixin.sodium;

import com.metallum.client.sodium.SodiumRelightRebuildCause;
import com.metallum.client.sodium.SodiumRelightResidentPlanSlot;
import com.metallum.client.sodium.SodiumRelightSectionTrackerSlot;
import com.metallum.client.sodium.SodiumRelightTaskStamp;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Render-thread cause coalescing and geometry epoch for one resident section. */
@Mixin(value = RenderSection.class, remap = false)
abstract class RenderSectionRelightTrackerMixin implements SodiumRelightSectionTrackerSlot {
    @Shadow
    @Final
    private List<ChunkJob> runningJobs;

    @Shadow
    private ChunkBuildOutput pendingBuildOutput;

    @Shadow
    private ChunkSortOutput pendingDynamicSortOutput;

    @Unique
    private SodiumRelightRebuildCause metallum$pendingRelightCause =
            SodiumRelightRebuildCause.NONE;

    @Unique
    private volatile long metallum$relightGeometryEpoch;

    @Override
    public synchronized void metallum$recordRelightCause(final SodiumRelightRebuildCause cause) {
        if (cause == null) {
            throw new NullPointerException("cause");
        }
        this.metallum$pendingRelightCause = this.metallum$pendingRelightCause.merge(cause);
        if (cause == SodiumRelightRebuildCause.GEOMETRY_OR_UNKNOWN) {
            this.metallum$relightGeometryEpoch = Math.addExact(
                    this.metallum$relightGeometryEpoch,
                    1L
            );
            ((SodiumRelightResidentPlanSlot) this).metallum$clearRelightPlan();
        }
    }

    @Override
    public synchronized SodiumRelightTaskStamp metallum$takeRelightTaskStamp(
            final int submitTime,
            final boolean blockingTask,
            final boolean forceSort
    ) {
        SodiumRelightRebuildCause cause = this.metallum$pendingRelightCause;
        this.metallum$pendingRelightCause = SodiumRelightRebuildCause.NONE;
        if (cause == SodiumRelightRebuildCause.NONE) {
            cause = SodiumRelightRebuildCause.GEOMETRY_OR_UNKNOWN;
            this.metallum$relightGeometryEpoch = Math.addExact(
                    this.metallum$relightGeometryEpoch,
                    1L
            );
            ((SodiumRelightResidentPlanSlot) this).metallum$clearRelightPlan();
        }
        return new SodiumRelightTaskStamp(
                cause,
                this.metallum$relightGeometryEpoch,
                submitTime,
                blockingTask,
                forceSort,
                this.runningJobs.isEmpty()
                        && this.pendingBuildOutput == null
                        && this.pendingDynamicSortOutput == null
        );
    }

    @Override
    public synchronized void metallum$discardPendingRelightCause() {
        this.metallum$pendingRelightCause = SodiumRelightRebuildCause.NONE;
    }

    @Override
    public long metallum$getRelightGeometryEpoch() {
        return this.metallum$relightGeometryEpoch;
    }

    @Inject(method = "delete()V", at = @At("HEAD"))
    private void metallum$invalidateTrackedRelightTasks(final CallbackInfo ci) {
        synchronized (this) {
            this.metallum$pendingRelightCause = SodiumRelightRebuildCause.NONE;
            this.metallum$relightGeometryEpoch = Math.addExact(
                    this.metallum$relightGeometryEpoch,
                    1L
            );
        }
    }
}
