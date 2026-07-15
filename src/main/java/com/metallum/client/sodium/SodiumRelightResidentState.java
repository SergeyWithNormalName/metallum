package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One immutable ownership unit for a section's resident relight plan and exact upload metadata.
 *
 * <p>The state exclusively owns the cache {@link SodiumRelightPlanCache.Owner}. A worker
 * {@link Lease} pins the plan and retains the matching {@link BuiltSectionInfo} and generation
 * even after the render thread replaces or closes this state.</p>
 */
public final class SodiumRelightResidentState implements AutoCloseable {
    /** Sentinel used only by the temporary plan-only publisher bridge. */
    public static final int LEGACY_GENERATION = 0;

    private final SodiumRelightPlanCache.Owner owner;
    private final BuiltSectionInfo info;
    private final int generation;

    public SodiumRelightResidentState(
            final SodiumRelightPlanCache.Owner owner,
            final BuiltSectionInfo info,
            final int generation
    ) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.info = Objects.requireNonNull(info, "info");
        this.generation = generation;
    }

    @Nullable
    public Lease acquire() {
        if (this.generation == LEGACY_GENERATION) {
            return null;
        }
        SodiumRelightPlanCache.Lease planLease = this.owner.acquire();
        return planLease == null ? null : new Lease(planLease, this.info, this.generation);
    }

    /** Backward-compatible plan-only pin for the current oracle path. */
    public SodiumRelightPlanCache.@Nullable Lease acquirePlan() {
        return this.owner.acquire();
    }

    public BuiltSectionInfo info() {
        return this.info;
    }

    public int generation() {
        return this.generation;
    }

    public boolean isResident() {
        return this.owner.isResident();
    }

    @Override
    public void close() {
        this.owner.close();
    }

    /** Worker-confined pin on the exact plan, metadata object, and upload generation. */
    public static final class Lease implements AutoCloseable {
        private final SodiumRelightPlanCache.Lease planLease;
        private final BuiltSectionInfo info;
        private final int generation;
        private boolean closed;

        private Lease(
                final SodiumRelightPlanCache.Lease planLease,
                final BuiltSectionInfo info,
                final int generation
        ) {
            this.planLease = planLease;
            this.info = info;
            this.generation = generation;
        }

        public SodiumRelightPlan plan() {
            this.requireOpen();
            return this.planLease.plan();
        }

        public BuiltSectionInfo info() {
            this.requireOpen();
            return this.info;
        }

        public int generation() {
            this.requireOpen();
            return this.generation;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.planLease.close();
        }

        private void requireOpen() {
            if (this.closed) {
                throw new IllegalStateException("resident relight state lease is closed");
            }
        }
    }
}
