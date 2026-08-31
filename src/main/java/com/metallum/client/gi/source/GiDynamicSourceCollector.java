package com.metallum.client.gi.source;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.lighting.LocalShadowSourceClass;
import com.metallum.client.lighting.ShadowEmitterFootprint;
import com.metallum.client.lighting.StableLightIds;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Render-thread G6 truth for held/entity GI sources.
 *
 * <p>Membership is deliberately world-space and has no camera or frustum input. One call to
 * {@link #beginTick(LightWorldToken, long)} represents a renderer/world source tick, not a
 * displayed frame. Missing observations remain valid only for the configured tick expiry.</p>
 */
public final class GiDynamicSourceCollector {
    public static final int MAX_CAPACITY = 512;
    /** Dynamic GI cannot represent source motion finer than its two-block near field. */
    static final int POSITION_QUANTUM_BLOCKS = GiDirectSourceLayout.cellSizeBlocks(0);

    public enum OfferResult {
        ACCEPTED,
        COALESCED,
        CAPACITY_REJECTED
    }

    public record Telemetry(
            long offered,
            long coalesced,
            long capacityRejected,
            long expired,
            long worldResets,
            long epochChanges,
            int residentSources,
            int capacity
    ) {
    }

    private static final Comparator<AdvancedLight> SOURCE_ORDER =
            GiDynamicSourceSnapshot.SOURCE_ORDER;

    private final Thread ownerThread = Thread.currentThread();
    private final int capacity;
    private final long expiryTicks;
    private final AdvancedLight[] residentSources;
    private final long[] residentLastSeenTicks;
    private final AdvancedLight[] residentUpdates;
    private final AdvancedLight[] newCandidates;
    private int residentCount;
    private int newCandidateCount;
    @Nullable private LightWorldToken activeWorld;
    @Nullable private GiDynamicSourceSnapshot published;
    private long sourceEpoch;
    private long activeTick = -1L;
    private long lastCompletedTick = -1L;
    private boolean tickOpen;
    private long offered;
    private long coalesced;
    private long capacityRejected;
    private long expired;
    private long worldResets;
    private long epochChanges;

    public GiDynamicSourceCollector(final int capacity, final long expiryTicks) {
        if (capacity <= 0 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("G6 dynamic-source capacity is outside its bound");
        }
        if (expiryTicks <= 0L) {
            throw new IllegalArgumentException("G6 dynamic-source expiry must be positive");
        }
        this.capacity = capacity;
        this.expiryTicks = expiryTicks;
        this.residentSources = new AdvancedLight[capacity];
        this.residentLastSeenTicks = new long[capacity];
        this.residentUpdates = new AdvancedLight[capacity];
        this.newCandidates = new AdvancedLight[capacity];
    }

    /** Opens exactly one source update tick. World transitions publish an empty set immediately. */
    public void beginTick(final LightWorldToken world, final long worldTick) {
        assertOwnerThread();
        Objects.requireNonNull(world, "world");
        if (worldTick < 0L) {
            throw new IllegalArgumentException("G6 world/source tick must be non-negative");
        }
        if (this.tickOpen) {
            throw new IllegalStateException("A G6 dynamic-source tick is already open");
        }
        if (this.activeWorld == null || !this.activeWorld.equals(world)) {
            if (this.activeWorld != null && world.generation() <= this.activeWorld.generation()) {
                throw new IllegalArgumentException("G6 dynamic-source world generation regressed");
            }
            resetWorld(world, worldTick);
        } else if (worldTick <= this.lastCompletedTick) {
            throw new IllegalArgumentException("G6 world/source tick did not advance");
        }
        this.activeTick = worldTick;
        this.tickOpen = true;
    }

    /**
     * Returns the sole authoritative next observation tick for this collector. Keeping the
     * sequence beside {@code lastCompletedTick} prevents a LevelExtractor lifecycle callback
     * from desynchronizing a second counter while the collector itself survives.
     */
    public long nextObservationTick(final LightWorldToken world) {
        assertOwnerThread();
        Objects.requireNonNull(world, "world");
        if (this.tickOpen) {
            throw new IllegalStateException("A G6 dynamic-source tick is already open");
        }
        return this.activeWorld != null && this.activeWorld.equals(world)
                ? Math.incrementExact(this.lastCompletedTick)
                : 0L;
    }

    /** Allocation-free lifecycle probe; unlike {@link #snapshot()} it is valid before open. */
    public boolean isWorldOpen(final LightWorldToken world) {
        assertOwnerThread();
        return this.activeWorld != null
                && this.activeWorld.equals(Objects.requireNonNull(world, "world"));
    }

    /**
     * Offers an immutable world-space source. Same-ID observations in one tick coalesce by a
     * complete deterministic order rather than call order.
     */
    public OfferResult offer(final AdvancedLight source) {
        assertOwnerThread();
        requireOpenTick();
        AdvancedLight stableSource = stabilizeForField(source);
        GiDynamicSourceSnapshot.requireLiveSource(
                Objects.requireNonNull(this.activeWorld, "activeWorld"),
                stableSource
        );
        this.offered = Math.incrementExact(this.offered);

        int residentIndex = indexOfStableId(
                this.residentSources, this.residentCount, stableSource.stableId()
        );
        if (residentIndex >= 0) {
            AdvancedLight previous = this.residentUpdates[residentIndex];
            if (previous == null) {
                this.residentUpdates[residentIndex] = stableSource;
                return OfferResult.ACCEPTED;
            }
            this.coalesced = Math.incrementExact(this.coalesced);
            if (SOURCE_ORDER.compare(stableSource, previous) < 0) {
                this.residentUpdates[residentIndex] = stableSource;
            }
            return OfferResult.COALESCED;
        }

        int candidateIndex = indexOfStableId(
                this.newCandidates, this.newCandidateCount, stableSource.stableId()
        );
        if (candidateIndex >= 0) {
            AdvancedLight previous = this.newCandidates[candidateIndex];
            this.coalesced = Math.incrementExact(this.coalesced);
            if (SOURCE_ORDER.compare(stableSource, previous) < 0) {
                this.newCandidates[candidateIndex] = stableSource;
            }
            return OfferResult.COALESCED;
        }

        if (this.newCandidateCount < this.capacity) {
            this.newCandidates[this.newCandidateCount++] = stableSource;
            return OfferResult.ACCEPTED;
        }
        int worstIndex = worstIndex(this.newCandidates, this.newCandidateCount);
        AdvancedLight worst = this.newCandidates[worstIndex];
        if (SOURCE_ORDER.compare(stableSource, worst) < 0) {
            this.newCandidates[worstIndex] = stableSource;
            this.capacityRejected = Math.incrementExact(this.capacityRejected);
            return OfferResult.ACCEPTED;
        }
        this.capacityRejected = Math.incrementExact(this.capacityRejected);
        return OfferResult.CAPACITY_REJECTED;
    }

    /**
     * Snaps only the private G6 source truth to the finest field cell. Direct L3 lighting keeps
     * its independently interpolated position, while sub-cell render residuals cannot rotate a
     * transport epoch or alternate the visible result between exact GI and ambient fallback.
     */
    static AdvancedLight stabilizeForField(final AdvancedLight source) {
        Objects.requireNonNull(source, "source");
        double x = quantizedCellCenter(source.x());
        double y = quantizedCellCenter(source.y());
        double z = quantizedCellCenter(source.z());
        if (x == source.x() && y == source.y() && z == source.z()) {
            return source;
        }
        return new AdvancedLight(
                source.stableId(), source.generation(), source.kind(),
                x, y, z, source.radius(),
                source.red(), source.green(), source.blue(), source.intensity(),
                source.priority(), source.denseCellEligible(),
                source.shadowEmitterFootprint(), source.shadowSourceClass()
        );
    }

    private static double quantizedCellCenter(final double position) {
        return Math.floor(position / POSITION_QUANTUM_BLOCKS) * POSITION_QUANTUM_BLOCKS
                + POSITION_QUANTUM_BLOCKS * 0.5;
    }

    /** Finishes the tick and returns the last immutable publication, reusing it when unchanged. */
    public GiDynamicSourceSnapshot finishTick() {
        assertOwnerThread();
        requireOpenTick();
        boolean mayHaveChanged = false;
        try {
            for (int index = 0; index < this.residentCount; index++) {
                AdvancedLight next = this.residentUpdates[index];
                if (next == null) {
                    continue;
                }
                mayHaveChanged |= !this.residentSources[index].equals(next);
                this.residentSources[index] = next;
                this.residentLastSeenTicks[index] = this.activeTick;
                this.residentUpdates[index] = null;
            }

            int residentIndex = 0;
            while (residentIndex < this.residentCount) {
                if (this.activeTick - this.residentLastSeenTicks[residentIndex]
                        >= this.expiryTicks) {
                    removeResidentAt(residentIndex);
                    this.expired = Math.incrementExact(this.expired);
                    mayHaveChanged = true;
                } else {
                    residentIndex++;
                }
            }

            for (int index = 0; index < this.newCandidateCount; index++) {
                AdvancedLight candidate = this.newCandidates[index];
                if (this.residentCount < this.capacity) {
                    this.residentSources[this.residentCount] = candidate;
                    this.residentLastSeenTicks[this.residentCount] = this.activeTick;
                    this.residentCount++;
                    mayHaveChanged = true;
                    continue;
                }
                int worstIndex = worstIndex(this.residentSources, this.residentCount);
                if (SOURCE_ORDER.compare(candidate, this.residentSources[worstIndex]) < 0) {
                    this.residentSources[worstIndex] = candidate;
                    this.residentLastSeenTicks[worstIndex] = this.activeTick;
                    this.capacityRejected = Math.incrementExact(this.capacityRejected);
                    mayHaveChanged = true;
                } else {
                    this.capacityRejected = Math.incrementExact(this.capacityRejected);
                }
            }

            GiDynamicSourceSnapshot result = this.published;
            if (mayHaveChanged || result == null) {
                List<AdvancedLight> ordered = new ArrayList<>(this.residentCount);
                for (int index = 0; index < this.residentCount; index++) {
                    ordered.add(this.residentSources[index]);
                }
                ordered.sort(SOURCE_ORDER);
                List<AdvancedLight> immutable = List.copyOf(ordered);
                if (result == null || !result.sources().equals(immutable)) {
                    advanceEpoch();
                    this.published = publish(immutable, this.activeTick);
                    result = this.published;
                }
            }
            if (result == null) {
                throw new IllegalStateException("G6 dynamic-source publication is unavailable");
            }
            this.lastCompletedTick = this.activeTick;
            return result;
        } finally {
            Arrays.fill(this.residentUpdates, null);
            Arrays.fill(this.newCandidates, 0, this.newCandidateCount, null);
            this.newCandidateCount = 0;
            this.tickOpen = false;
        }
    }

    /** Current publication; valid even between reset and the end of its first new-world tick. */
    public GiDynamicSourceSnapshot snapshot() {
        assertOwnerThread();
        if (this.published == null) {
            throw new IllegalStateException("G6 dynamic-source world has not been opened");
        }
        return this.published;
    }

    public Telemetry telemetry() {
        assertOwnerThread();
        return new Telemetry(
                this.offered,
                this.coalesced,
                this.capacityRejected,
                this.expired,
                this.worldResets,
                this.epochChanges,
                this.residentCount,
                this.capacity
        );
    }

    /**
     * Creates an entity source from an absolute world position and stable entity identity.
     */
    public static AdvancedLight entityAtWorldPosition(
            final LightWorldToken world,
            final UUID entityId,
            final double worldX,
            final double worldY,
            final double worldZ,
            final float radius,
            final float red,
            final float green,
            final float blue,
            final float intensity,
            final int priority
    ) {
        return atWorldPosition(
                world, entityId, worldX, worldY, worldZ,
                radius, red, green, blue, intensity, priority,
                LocalShadowSourceClass.ENTITY_DYNAMIC
        );
    }

    /**
     * Creates a held source anchored to its entity/body position in world space. Camera yaw and
     * camera-relative hand offsets intentionally are not inputs to this G6 truth path.
     */
    public static AdvancedLight heldAtEntityWorldPosition(
            final LightWorldToken world,
            final UUID entityId,
            final double worldX,
            final double worldY,
            final double worldZ,
            final float radius,
            final float red,
            final float green,
            final float blue,
            final float intensity,
            final int priority
    ) {
        return atWorldPosition(
                world, entityId, worldX, worldY, worldZ,
                radius, red, green, blue, intensity, priority,
                LocalShadowSourceClass.CAMERA_HELD
        );
    }

    private static AdvancedLight atWorldPosition(
            final LightWorldToken world,
            final UUID entityId,
            final double worldX,
            final double worldY,
            final double worldZ,
            final float radius,
            final float red,
            final float green,
            final float blue,
            final float intensity,
            final int priority,
            final LocalShadowSourceClass sourceClass
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(entityId, "entityId");
        return new AdvancedLight(
                StableLightIds.entity(world.dimensionId(), entityId),
                world.generation(),
                LightSourceKind.ENTITY,
                worldX,
                worldY,
                worldZ,
                radius,
                red,
                green,
                blue,
                intensity,
                priority,
                false,
                ShadowEmitterFootprint.empty(),
                sourceClass
        );
    }

    private void resetWorld(final LightWorldToken world, final long worldTick) {
        this.activeWorld = world;
        Arrays.fill(this.residentSources, 0, this.residentCount, null);
        Arrays.fill(this.residentUpdates, null);
        Arrays.fill(this.newCandidates, 0, this.newCandidateCount, null);
        this.residentCount = 0;
        this.newCandidateCount = 0;
        this.lastCompletedTick = -1L;
        this.worldResets = Math.incrementExact(this.worldResets);
        advanceEpoch();
        this.published = publish(List.of(), worldTick);
    }

    private GiDynamicSourceSnapshot publish(
            final List<AdvancedLight> sources,
            final long worldTick
    ) {
        LightWorldToken world = Objects.requireNonNull(this.activeWorld, "activeWorld");
        GiDynamicSourceEpoch epoch = new GiDynamicSourceEpoch(world, this.sourceEpoch);
        return new GiDynamicSourceSnapshot(
                GiDynamicSourceSnapshot.CURRENT_VERSION,
                epoch,
                worldTick,
                this.capacity,
                this.expiryTicks,
                sources,
                GiDynamicSourceSnapshot.computeSourceHash(world, sources)
        );
    }

    private void advanceEpoch() {
        this.sourceEpoch = Math.incrementExact(this.sourceEpoch);
        this.epochChanges = Math.incrementExact(this.epochChanges);
    }

    private void removeResidentAt(final int index) {
        int last = --this.residentCount;
        if (index != last) {
            this.residentSources[index] = this.residentSources[last];
            this.residentLastSeenTicks[index] = this.residentLastSeenTicks[last];
            this.residentUpdates[index] = this.residentUpdates[last];
        }
        this.residentSources[last] = null;
        this.residentLastSeenTicks[last] = 0L;
        this.residentUpdates[last] = null;
    }

    private static int indexOfStableId(
            final AdvancedLight[] sources,
            final int count,
            final long stableId
    ) {
        for (int index = 0; index < count; index++) {
            if (sources[index].stableId() == stableId) {
                return index;
            }
        }
        return -1;
    }

    private static int worstIndex(final AdvancedLight[] sources, final int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Cannot select from an empty G6 source set");
        }
        int worst = 0;
        for (int index = 1; index < count; index++) {
            if (SOURCE_ORDER.compare(sources[index], sources[worst]) > 0) {
                worst = index;
            }
        }
        return worst;
    }

    private void requireOpenTick() {
        if (!this.tickOpen) {
            throw new IllegalStateException("No G6 dynamic-source tick is open");
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G6 dynamic-source collector left its owner thread");
        }
    }
}
