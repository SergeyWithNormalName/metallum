package com.metallum.client.sodium;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded LRU owner for immutable section relight plans.
 *
 * <p>An active {@link Lease} pins its plan. Capacity pressure never evicts a
 * pinned owner and never exceeds the configured budget; admission is rejected
 * when the unpinned LRU set cannot free enough space.</p>
 */
public final class SodiumRelightPlanCache {
    public static final long DEFAULT_CAPACITY_BYTES = 32L * 1024L * 1024L;

    private final long capacityBytes;
    private final LinkedHashMap<Owner, Boolean> residents =
            new LinkedHashMap<>(16, 0.75f, true);
    private long liveBytes;
    private long peakBytes;
    private long pinnedLeases;
    private long evictionCount;
    private long oversizedRejectionCount;
    private long pinnedPressureRejectionCount;

    public SodiumRelightPlanCache() {
        this(DEFAULT_CAPACITY_BYTES);
    }

    public SodiumRelightPlanCache(final long capacityBytes) {
        if (capacityBytes < 0L) {
            throw new IllegalArgumentException("negative relight plan capacity: " + capacityBytes);
        }
        this.capacityBytes = capacityBytes;
    }

    public Owner capture(final SodiumRelightPlan plan) {
        Objects.requireNonNull(plan, "plan");
        long requiredBytes = plan.estimatedRetainedBytes();
        synchronized (this) {
            if (requiredBytes > this.capacityBytes) {
                this.oversizedRejectionCount = Math.addExact(this.oversizedRejectionCount, 1L);
                return new Owner(null, 0L);
            }

            long requiredEviction = Math.max(
                    0L,
                    Math.subtractExact(Math.addExact(this.liveBytes, requiredBytes), this.capacityBytes)
            );
            List<Owner> victims = new ArrayList<>();
            long reclaimable = 0L;
            if (requiredEviction > 0L) {
                for (Map.Entry<Owner, Boolean> entry : this.residents.entrySet()) {
                    Owner candidate = entry.getKey();
                    if (candidate.pinCount != 0) {
                        continue;
                    }
                    victims.add(candidate);
                    reclaimable = Math.addExact(reclaimable, candidate.retainedBytes);
                    if (reclaimable >= requiredEviction) {
                        break;
                    }
                }
                if (reclaimable < requiredEviction) {
                    this.pinnedPressureRejectionCount = Math.addExact(
                            this.pinnedPressureRejectionCount,
                            1L
                    );
                    return new Owner(null, 0L);
                }
            }

            for (Owner victim : victims) {
                this.removeResident(victim, true);
            }
            Owner owner = new Owner(plan, requiredBytes);
            this.residents.put(owner, Boolean.TRUE);
            this.liveBytes = Math.addExact(this.liveBytes, requiredBytes);
            this.peakBytes = Math.max(this.peakBytes, this.liveBytes);
            return owner;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                this.capacityBytes,
                this.liveBytes,
                this.peakBytes,
                this.residents.size(),
                this.pinnedLeases,
                this.evictionCount,
                this.oversizedRejectionCount,
                this.pinnedPressureRejectionCount
        );
    }

    /** Invalidates every owner; pinned plans are released when their last lease closes. */
    public synchronized void clear() {
        List<Owner> owners = new ArrayList<>(this.residents.keySet());
        for (Owner owner : owners) {
            owner.acceptingLeases = false;
            if (owner.pinCount == 0) {
                this.removeResident(owner, false);
            } else {
                owner.closePending = true;
            }
        }
    }

    private void removeResident(final Owner owner, final boolean capacityEviction) {
        if (owner.pinCount != 0) {
            throw new IllegalStateException("attempted to evict a pinned relight plan");
        }
        SodiumRelightPlan plan = owner.plan;
        if (plan == null || this.residents.remove(owner) == null) {
            return;
        }
        owner.plan = null;
        owner.acceptingLeases = false;
        this.liveBytes = Math.subtractExact(this.liveBytes, owner.retainedBytes);
        if (capacityEviction) {
            this.evictionCount = Math.addExact(this.evictionCount, 1L);
        }
    }

    public final class Owner implements AutoCloseable {
        @Nullable
        private SodiumRelightPlan plan;
        private final long retainedBytes;
        private int pinCount;
        private boolean acceptingLeases;
        private boolean closePending;

        private Owner(@Nullable final SodiumRelightPlan plan, final long retainedBytes) {
            this.plan = plan;
            this.retainedBytes = retainedBytes;
            this.acceptingLeases = plan != null;
        }

        @Nullable
        public Lease acquire() {
            synchronized (SodiumRelightPlanCache.this) {
                SodiumRelightPlan resident = this.plan;
                if (resident == null
                        || !this.acceptingLeases
                        || SodiumRelightPlanCache.this.residents.get(this) == null) {
                    return null;
                }
                this.pinCount = Math.addExact(this.pinCount, 1);
                SodiumRelightPlanCache.this.pinnedLeases = Math.addExact(
                        SodiumRelightPlanCache.this.pinnedLeases,
                        1L
                );
                return new Lease(this, resident);
            }
        }

        public boolean isResident() {
            synchronized (SodiumRelightPlanCache.this) {
                return this.plan != null && SodiumRelightPlanCache.this.residents.containsKey(this);
            }
        }

        public int pinCount() {
            synchronized (SodiumRelightPlanCache.this) {
                return this.pinCount;
            }
        }

        @Override
        public void close() {
            synchronized (SodiumRelightPlanCache.this) {
                this.acceptingLeases = false;
                if (this.plan == null) {
                    return;
                }
                if (this.pinCount == 0) {
                    SodiumRelightPlanCache.this.removeResident(this, false);
                } else {
                    this.closePending = true;
                }
            }
        }

        private void releaseLease() {
            synchronized (SodiumRelightPlanCache.this) {
                if (this.pinCount <= 0 || SodiumRelightPlanCache.this.pinnedLeases <= 0L) {
                    throw new IllegalStateException("relight plan lease accounting underflow");
                }
                this.pinCount--;
                SodiumRelightPlanCache.this.pinnedLeases--;
                if (this.pinCount == 0 && this.closePending) {
                    SodiumRelightPlanCache.this.removeResident(this, false);
                }
            }
        }
    }

    /**
     * A worker-confined pin on one immutable plan.
     *
     * <p>The acquiring worker owns the lease through replay and closes it from
     * that same worker's {@code finally} block. A lease must not be used or
     * closed concurrently from another thread.</p>
     */
    public static final class Lease implements AutoCloseable {
        private final Owner owner;
        private final SodiumRelightPlan plan;
        private boolean closed;

        private Lease(final Owner owner, final SodiumRelightPlan plan) {
            this.owner = owner;
            this.plan = plan;
        }

        public SodiumRelightPlan plan() {
            if (this.closed) {
                throw new IllegalStateException("relight plan lease is closed");
            }
            return this.plan;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.owner.releaseLease();
        }
    }

    public record Snapshot(
            long capacityBytes,
            long liveBytes,
            long peakBytes,
            int residentPlans,
            long pinnedLeases,
            long evictionCount,
            long oversizedRejectionCount,
            long pinnedPressureRejectionCount
    ) {
    }
}
