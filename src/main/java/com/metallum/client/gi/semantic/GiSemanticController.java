package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldCandidateBudget;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Accepted-output owner. It stores bounded field truth and never reads mutable live world state. */
public final class GiSemanticController {
    public record Telemetry(
            long accepted, long stale, long outside, long capacityRejected, long discarded,
            long resets, long palettePublications, int worlds, int residentSectionTags,
            GiFieldCandidateBudget.Snapshot candidateBudget
    ) {
    }

    private static final GiSemanticController GLOBAL = new GiSemanticController();

    private final IdentityHashMap<Object, WorldState> worlds = new IdentityHashMap<>();
    private final GiFieldCandidateBudget candidateBudget;
    private long nextWorldGeneration;
    private long nextResourceEpoch;
    private long nextOwnerToken;
    private long materialEpoch = 1L;
    private List<GiSemanticPalette.Seed> atlasSeeds;
    private long accepted;
    private long stale;
    private long outside;
    private long capacityRejected;
    private long discarded;
    private long resets;
    private long palettePublications;

    public GiSemanticController() {
        this(new GiFieldCandidateBudget());
    }

    public GiSemanticController(final GiFieldCandidateBudget candidateBudget) {
        this.candidateBudget = Objects.requireNonNull(candidateBudget, "candidateBudget");
    }

    public static GiSemanticController global() { return GLOBAL; }

    public synchronized GiSemanticWorldToken openWorld(final Object world, final String dimensionId) {
        Objects.requireNonNull(world, "world");
        requireDimension(dimensionId);
        WorldState current = this.worlds.get(world);
        if (current != null && current.token.dimensionId().equals(dimensionId)) return current.token;
        if (current != null) {
            this.worlds.remove(world);
            this.resets++;
        }
        GiSemanticWorldToken token = new GiSemanticWorldToken(
                ++this.nextWorldGeneration, Math.max(1L, ++this.nextResourceEpoch),
                this.materialEpoch, dimensionId
        );
        List<GiSemanticPalette.Seed> seeds = this.atlasSeeds == null ? List.of() : this.atlasSeeds;
        GiSemanticPalette palette = GiSemanticPalette.build(1L, token.resourceEpoch(), token.materialEpoch(), seeds);
        this.worlds.put(world, new WorldState(token, palette, this.atlasSeeds != null));
        return token;
    }

    public synchronized GiSemanticWorldToken reloadWorld(final Object world, final String dimensionId) {
        return beginResourceReload(world, dimensionId);
    }

    /**
     * Starts a resource reload without advancing the material epoch. The replacement token rejects
     * every pre-reload task, and no new task is admitted before a complete atlas palette is published.
     */
    public synchronized GiSemanticWorldToken beginResourceReload(final Object world, final String dimensionId) {
        Objects.requireNonNull(world, "world");
        requireDimension(dimensionId);
        WorldState old = this.worlds.get(world);
        int cameraX = old == null ? 0 : old.cameraX;
        int cameraY = old == null ? 0 : old.cameraY;
        int cameraZ = old == null ? 0 : old.cameraZ;
        long paletteGeneration = old == null ? 1L : Math.incrementExact(old.palette.generation());
        GiSemanticWorldToken token = new GiSemanticWorldToken(
                Math.incrementExact(this.nextWorldGeneration),
                Math.max(1L, Math.incrementExact(this.nextResourceEpoch)),
                this.materialEpoch, dimensionId
        );
        GiSemanticPalette blockedPalette = GiSemanticPalette.build(
                paletteGeneration, token.resourceEpoch(), token.materialEpoch(), List.of()
        );
        this.atlasSeeds = null;
        if (old == null) {
            this.worlds.put(world, new WorldState(
                    token, blockedPalette, false, cameraX, cameraY, cameraZ
            ));
        } else {
            old.token = token;
            old.palette = blockedPalette;
            old.paletteReady = false;
            old.revisions.clear();
            old.newestOwners.clear();
            old.assembler.reset(token, blockedPalette, cameraX, cameraY, cameraZ);
        }
        this.resets++;
        return token;
    }

    public synchronized void closeWorld(final Object world) {
        if (this.worlds.remove(world) != null) this.resets++;
    }

    /** Compatibility invalidation used before a full atlas palette is available; no task is admitted. */
    public synchronized void advanceMaterialAtlasEpoch() {
        long nextMaterialEpoch = Math.incrementExact(this.materialEpoch);
        IdentityHashMap<Object, GiSemanticPalette> nextPalettes = new IdentityHashMap<>();
        for (Map.Entry<Object, WorldState> entry : this.worlds.entrySet()) {
            WorldState old = entry.getValue();
            nextPalettes.put(entry.getKey(), GiSemanticPalette.build(
                    Math.incrementExact(old.palette.generation()), old.token.resourceEpoch(),
                    nextMaterialEpoch, List.of()
            ));
        }
        this.materialEpoch = nextMaterialEpoch;
        this.atlasSeeds = null;
        for (Map.Entry<Object, WorldState> entry : this.worlds.entrySet()) {
            WorldState old = entry.getValue();
            GiSemanticWorldToken token = new GiSemanticWorldToken(
                    old.token.worldGeneration(), old.token.resourceEpoch(), this.materialEpoch,
                    old.token.dimensionId()
            );
            GiSemanticPalette palette = nextPalettes.get(entry.getKey());
            old.token = token;
            old.palette = palette;
            old.paletteReady = false;
            old.revisions.clear();
            old.newestOwners.clear();
            old.assembler.reset(token, palette, old.cameraX, old.cameraY, old.cameraZ);
            this.resets++;
        }
    }

    /** Atomically rotates atlas/material epoch and publishes the complete sorted palette. */
    public synchronized void advanceMaterialAtlasEpoch(final Collection<GiSemanticPalette.Seed> seeds) {
        Objects.requireNonNull(seeds, "seeds");
        List<GiSemanticPalette.Seed> frozenSeeds = List.copyOf(seeds);
        long nextMaterialEpoch = Math.incrementExact(this.materialEpoch);
        IdentityHashMap<Object, GiSemanticPalette> nextPalettes = new IdentityHashMap<>();
        for (Map.Entry<Object, WorldState> entry : this.worlds.entrySet()) {
            WorldState old = entry.getValue();
            nextPalettes.put(entry.getKey(), GiSemanticPalette.build(
                    Math.incrementExact(old.palette.generation()), old.token.resourceEpoch(),
                    nextMaterialEpoch, frozenSeeds
            ));
        }
        this.materialEpoch = nextMaterialEpoch;
        this.atlasSeeds = frozenSeeds;
        for (Map.Entry<Object, WorldState> entry : this.worlds.entrySet()) {
            WorldState old = entry.getValue();
            GiSemanticWorldToken token = new GiSemanticWorldToken(
                    old.token.worldGeneration(), old.token.resourceEpoch(), this.materialEpoch,
                    old.token.dimensionId()
            );
            GiSemanticPalette palette = nextPalettes.get(entry.getKey());
            old.token = token;
            old.palette = palette;
            old.paletteReady = true;
            old.revisions.clear();
            old.newestOwners.clear();
            old.assembler.reset(token, palette, old.cameraX, old.cameraY, old.cameraZ);
            this.palettePublications++;
            this.resets++;
        }
    }

    /** Publishes one complete deterministic atlas palette before any task may resolve its keys. */
    public synchronized boolean publishPalette(final Object world, final GiSemanticPalette palette) {
        WorldState state = this.worlds.get(world);
        if (state == null || palette == null
                || palette.resourceEpoch() != state.token.resourceEpoch()
                || palette.materialEpoch() != state.token.materialEpoch()
                || palette.generation() <= state.palette.generation()) return false;
        state.palette = palette;
        state.paletteReady = true;
        state.assembler.reset(state.token, palette, state.cameraX, state.cameraY, state.cameraZ);
        state.revisions.clear();
        state.newestOwners.clear();
        this.palettePublications++;
        this.resets++;
        return true;
    }

    @Nullable
    public synchronized GiSemanticPalette paletteFor(final GiSemanticSectionTask task) {
        WorldState state = findWorld(task.world());
        return state != null && state.paletteReady && state.palette.generation() == task.paletteGeneration()
                ? state.palette : null;
    }

    @Nullable
    public synchronized GiSemanticSectionTask beginSectionTask(
            final Object world, final String dimensionId, final long sectionKey
    ) {
        WorldState state = this.worlds.get(world);
        if (state == null || !state.paletteReady || !state.token.dimensionId().equals(dimensionId)) return null;
        if (!state.assembler.containsSection(sectionKey)) return null;
        long revision = state.revisions.computeIfAbsent(sectionKey, ignored -> 1L);
        long owner = Math.incrementExact(this.nextOwnerToken);
        state.newestOwners.put(sectionKey, owner);
        return new GiSemanticSectionTask(
                state.token, state.assembler.clipmapGeneration(), state.palette.generation(),
                sectionKey, revision, owner
        );
    }

    public synchronized void markBlockDirty(
            final Object world, final String dimensionId,
            final int blockX, final int blockY, final int blockZ
    ) {
        WorldState state = this.worlds.get(world);
        if (state == null || !state.token.dimensionId().equals(dimensionId)) return;
        long key = GiSemanticCoordinates.sectionKey(
                GiSemanticCoordinates.blockToSection(blockX),
                GiSemanticCoordinates.blockToSection(blockY),
                GiSemanticCoordinates.blockToSection(blockZ)
        );
        if (!state.assembler.containsSection(key)) return;
        state.revisions.merge(key, 1L, Math::addExact);
    }

    public synchronized boolean updateCamera(
            final Object world, final int blockX, final int blockY, final int blockZ
    ) {
        WorldState state = this.worlds.get(world);
        if (state == null) return false;
        state.cameraX = blockX;
        state.cameraY = blockY;
        state.cameraZ = blockZ;
        boolean changed = state.assembler.updateCamera(blockX, blockY, blockZ);
        if (changed) {
            state.revisions.clear();
            state.newestOwners.clear();
            this.resets++;
        }
        return changed;
    }

    @Nullable
    public synchronized GiSemanticSectionReservation reserve(final GiSemanticSectionTask task) {
        WorldState state = currentState(task);
        if (state == null) return null;
        GiFieldCandidateBudget.Lease lease = this.candidateBudget.tryAcquire(
                GiSemanticSectionSnapshot.PAYLOAD_BYTES
        );
        return lease == null ? null : new GiSemanticSectionReservation(task, state.palette, lease);
    }

    @Nullable
    public GiSemanticSectionCandidate createCandidate(
            final GiSemanticSectionTask task,
            final GiSemanticSectionSeed seed,
            final List<GiSemanticQuadObservation> observations
    ) {
        GiSemanticSectionReservation reservation = reserve(task);
        if (reservation == null) return null;
        try (reservation) {
            return reservation.complete(seed, observations);
        }
    }

    @Nullable
    public GiSemanticSectionCandidate createAuthoritativeEmptyCandidate(final GiSemanticSectionTask task) {
        GiSemanticSectionReservation reservation = reserve(task);
        if (reservation == null) return null;
        try (reservation) {
            return reservation.authoritativeEmpty();
        }
    }

    public synchronized boolean publishAccepted(final GiSemanticSectionCandidate candidate) {
        if (candidate == null || !candidate.claimForPublication()) return false;
        try (candidate) {
            GiSemanticSectionTask task = candidate.task();
            WorldState state = currentState(task);
            if (state == null
                    || state.revisions.getOrDefault(task.sectionKey(), 1L) != task.revision()
                    || state.newestOwners.getOrDefault(task.sectionKey(), 0L) != task.ownerToken()) {
                this.stale++;
                return false;
            }
            GiSemanticFieldAssembler.ApplyResult result = state.assembler.apply(task, candidate.snapshot());
            switch (result) {
                case ACCEPTED -> {
                    this.accepted++;
                    return true;
                }
                case STALE -> this.stale++;
                case OUTSIDE -> this.outside++;
                case CAPACITY -> this.capacityRejected++;
            }
            return false;
        }
    }

    public void discardCandidate(@Nullable final GiSemanticSectionCandidate candidate) {
        if (candidate != null && candidate.discard()) {
            synchronized (this) { this.discarded++; }
        }
    }

    public synchronized void removeSectionIfOwner(
            final Object world, final long sectionKey, final long ownerToken
    ) {
        WorldState state = this.worlds.get(world);
        if (state != null && state.assembler.removeIfOwner(sectionKey, ownerToken)) {
            state.revisions.remove(sectionKey);
            state.newestOwners.remove(sectionKey);
        }
    }

    public synchronized long currentOwnerToken(final Object world, final long sectionKey) {
        WorldState state = this.worlds.get(world);
        return state == null ? 0L : state.assembler.currentOwner(sectionKey);
    }

    @Nullable
    public synchronized GiSemanticFieldSnapshot fieldSnapshot(final Object world) {
        WorldState state = this.worlds.get(world);
        return state == null ? null : state.assembler.snapshot();
    }

    public synchronized Telemetry telemetry() {
        int residentTags = this.worlds.values().stream()
                .mapToInt(state -> state.assembler.residentSections()).sum();
        return new Telemetry(
                this.accepted, this.stale, this.outside, this.capacityRejected, this.discarded,
                this.resets, this.palettePublications, this.worlds.size(), residentTags,
                this.candidateBudget.snapshot()
        );
    }

    public GiFieldCandidateBudget.Snapshot budgetSnapshot() { return this.candidateBudget.snapshot(); }

    @Nullable
    private WorldState currentState(final GiSemanticSectionTask task) {
        WorldState state = findWorld(task.world());
        return state != null && state.paletteReady
                && state.assembler.clipmapGeneration() == task.clipmapGeneration()
                && state.palette.generation() == task.paletteGeneration() ? state : null;
    }

    @Nullable
    private WorldState findWorld(final GiSemanticWorldToken token) {
        for (WorldState state : this.worlds.values()) {
            if (state.token.equals(token)) return state;
        }
        return null;
    }

    private static void requireDimension(final String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("G2 dimension must not be blank");
        }
    }

    private static final class WorldState {
        private GiSemanticWorldToken token;
        private GiSemanticPalette palette;
        private boolean paletteReady;
        private final GiSemanticFieldAssembler assembler;
        private final Map<Long, Long> revisions = new HashMap<>();
        private final Map<Long, Long> newestOwners = new HashMap<>();
        private int cameraX;
        private int cameraY;
        private int cameraZ;

        private WorldState(final GiSemanticWorldToken token, final GiSemanticPalette palette) {
            this(token, palette, false, 0, 0, 0);
        }

        private WorldState(
                final GiSemanticWorldToken token, final GiSemanticPalette palette, final boolean paletteReady
        ) {
            this(token, palette, paletteReady, 0, 0, 0);
        }

        private WorldState(
                final GiSemanticWorldToken token, final GiSemanticPalette palette,
                final boolean paletteReady,
                final int cameraX, final int cameraY, final int cameraZ
        ) {
            this.token = token;
            this.palette = palette;
            this.paletteReady = paletteReady;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
            this.assembler = new GiSemanticFieldAssembler(token, palette, cameraX, cameraY, cameraZ);
        }
    }
}
