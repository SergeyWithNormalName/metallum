package com.metallum.client.lighting;

import com.metallum.client.renderer.SunShadowLayout;
import com.metallum.client.voxel.VoxelBrickPatch;
import com.metallum.client.voxel.VoxelClipmapLayout;
import com.metallum.client.voxel.VoxelUploadBatch;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * Bounded CPU policy for L6 cached terrain shadows. GPU ownership remains in
 * {@code SunShadowGpuResources}; this class only decides whether a submitted frame needs a
 * terrain refresh and keeps the static transform used to reproject the working cascades.
 */
public final class SunShadowCache {
    public record Decision(
            SunShadowFrame workingFrame,
            SunShadowFrame staticFrame,
            boolean staticRefresh
    ) {
        public Decision {
            Objects.requireNonNull(workingFrame, "workingFrame");
            Objects.requireNonNull(staticFrame, "staticFrame");
        }
    }

    public record Telemetry(
            long staticUpdates,
            long staticReuses,
            long dynamicUpdates,
            long blockInvalidations,
            long resourceBytes
    ) {
    }

    private final long resourceBytes;
    private SunShadowFrame frozen;
    private boolean explicitlyDirty;
    private long staticUpdates;
    private long staticReuses;
    private long dynamicUpdates;
    private long blockInvalidations;

    public SunShadowCache(final long resourceBytes) {
        if (resourceBytes <= 0L) {
            throw new IllegalArgumentException("Sun-shadow cache resources must be positive");
        }
        this.resourceBytes = resourceBytes;
    }

    public Decision prepare(final SunShadowFrame planned) {
        Objects.requireNonNull(planned, "planned");
        if (!planned.needsShadowPass()) {
            return new Decision(planned, planned, false);
        }
        if (needsStaticRefresh(planned)) {
            return new Decision(planned, planned, true);
        }
        return new Decision(SunShadowFrame.reprojectCached(this.frozen, planned), this.frozen, false);
    }

    /** Called only once the static copy/replay and dynamic feature pass have completed. */
    public void complete(final Decision decision) {
        Objects.requireNonNull(decision, "decision");
        if (!decision.workingFrame().needsShadowPass()) {
            return;
        }
        if (decision.staticRefresh()) {
            this.frozen = decision.staticFrame();
            this.explicitlyDirty = false;
            this.staticUpdates = increment(this.staticUpdates);
        } else {
            this.staticReuses = increment(this.staticReuses);
        }
        this.dynamicUpdates = increment(this.dynamicUpdates);
    }

    /**
     * Invalidates only when an accepted L5 brick intersects a frozen cascade. Unknown metadata
     * is deliberately fail-closed: it requests a static refresh rather than risking stale cover.
     */
    public boolean invalidateVoxelBatch(final VoxelUploadBatch batch) {
        Objects.requireNonNull(batch, "batch");
        SunShadowFrame current = this.frozen;
        if (current == null || !current.needsShadowPass()) {
            return false;
        }
        VoxelClipmapLayout.Budget voxelBudget = VoxelClipmapLayout.forPreset(voxelPreset(
                current.budget().maximumDistance(), current.budget().cascadeCount()
        ));
        for (VoxelBrickPatch patch : batch.patches()) {
            if (patch.level() < 0 || patch.level() >= voxelBudget.levels().size()) {
                return markBlockInvalidated();
            }
            VoxelClipmapLayout.Level level = voxelBudget.levels().get(patch.level());
            try {
                int edge = level.brickBlockEdge();
                long minimumX = Math.multiplyExact((long) patch.logicalBrickX(), edge);
                long minimumY = Math.multiplyExact((long) patch.logicalBrickY(), edge);
                long minimumZ = Math.multiplyExact((long) patch.logicalBrickZ(), edge);
                long maximumX = Math.addExact(minimumX, edge);
                long maximumY = Math.addExact(minimumY, edge);
                long maximumZ = Math.addExact(minimumZ, edge);
                for (int cascade = 0; cascade < current.cascadeCount(); cascade++) {
                    if (SunShadowClipVolume.intersectsAabb(
                            current.shadowFromWorldRelative(cascade), current.cameraPosition(),
                            minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ
                    )) {
                        return markBlockInvalidated();
                    }
                }
            } catch (ArithmeticException failure) {
                return markBlockInvalidated();
            }
        }
        return false;
    }

    /** Explicit fail-closed hook for world edits that do not have an L5 batch yet. */
    public void invalidate() {
        this.explicitlyDirty = true;
    }

    public Telemetry telemetry() {
        return new Telemetry(this.staticUpdates, this.staticReuses, this.dynamicUpdates,
                this.blockInvalidations, this.resourceBytes);
    }

    public long staticUpdates() {
        return this.staticUpdates;
    }

    public long staticReuses() {
        return this.staticReuses;
    }

    public long dynamicUpdates() {
        return this.dynamicUpdates;
    }

    public long blockInvalidations() {
        return this.blockInvalidations;
    }

    public long resourceBytes() {
        return this.resourceBytes;
    }

    private boolean needsStaticRefresh(final SunShadowFrame planned) {
        if (this.frozen == null || this.explicitlyDirty
                || this.frozen.submitIndex() > planned.submitIndex()
                || planned.submitIndex() - this.frozen.submitIndex()
                >= SunShadowLayout.STATIC_CACHE_MAX_AGE_SUBMITS
                || !sameStaticEnvironment(this.frozen.environment(), planned.environment())
                || !SunShadowFrame.cachedReceiverFootprintContains(this.frozen, planned)) {
            return true;
        }
        Vector3f frozenDirection = this.frozen.toLightWorld();
        Vector3f currentDirection = planned.toLightWorld();
        return frozenDirection.dot(currentDirection) < SunShadowLayout.STATIC_CACHE_MIN_SUN_DIRECTION_DOT;
    }

    private static boolean sameStaticEnvironment(
            final EnvironmentDescriptor frozen,
            final EnvironmentDescriptor current
    ) {
        return frozen.profile() == current.profile()
                && frozen.medium() == current.medium()
                && frozen.moon() == current.moon()
                && frozen.sunShadowEligible() == current.sunShadowEligible();
    }

    private boolean markBlockInvalidated() {
        this.explicitlyDirty = true;
        this.blockInvalidations = increment(this.blockInvalidations);
        return true;
    }

    private static VoxelClipmapLayout.Preset voxelPreset(
            final float maximumDistance,
            final int cascadeCount
    ) {
        // The sun layouts are fixed per LightingPreset. Keep this mapping data-only so L6 can
        // interpret L5 logical brick coordinates without observing the mutable controller.
        if (cascadeCount == 2) {
            return VoxelClipmapLayout.Preset.PERFORMANCE;
        }
        return maximumDistance >= 150.0f
                ? VoxelClipmapLayout.Preset.ULTRA
                : VoxelClipmapLayout.Preset.BALANCED;
    }

    private static long increment(final long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }
}
