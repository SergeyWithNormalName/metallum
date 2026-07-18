package com.metallum.client.voxel;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.SectionPos;

/**
 * Bounded, accepted-geometry-only L5 producer. It owns no GPU objects and never changes L3/L4
 * admission: a full ring is represented by {@link #retryUploadBatch(long)}, not a renderer
 * failure. Clipmap scrolling advances generation tags and queues only incoming slabs.
 */
public final class VoxelClipmapController {
    private static final VoxelClipmapController GLOBAL = new VoxelClipmapController(
            VoxelClipmapLayout.forPreset(VoxelClipmapLayout.Preset.BALANCED)
    );

    private VoxelClipmapLayout.Budget budget;
    private final Supplier<VoxelBrickPacker> packerFactory;
    private long nextWorldGeneration;
    private long nextOwnerToken;
    private long nextBatchId;
    private WorldState activeWorld;

    private long worldTransitions;
    private long resourceReloads;
    private long sectionTasksStarted;
    private long sectionCandidatesEncoded;
    private long sectionStatesScanned;
    private long acceptedPublications;
    private long stalePublications;
    private long discardedCandidates;
    private long blockInvalidations;
    private long sectionUnloads;
    private long cameraScrolls;
    private long scrollSlabsScheduled;
    private long queueRejected;
    private long batchesLeased;
    private long batchesCompleted;
    private long busyRetries;
    private long staleBatches;
    private long deferredWithoutAcceptedGeometry;

    public VoxelClipmapController() {
        this(VoxelClipmapLayout.forPreset(VoxelClipmapLayout.Preset.BALANCED));
    }

    public VoxelClipmapController(final VoxelClipmapLayout.Budget budget) {
        this(budget, VoxelBrickPacker::new);
    }

    VoxelClipmapController(
            final VoxelClipmapLayout.Budget budget,
            final Supplier<VoxelBrickPacker> packerFactory
    ) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.packerFactory = Objects.requireNonNull(packerFactory, "packerFactory");
    }

    public static VoxelClipmapController global() {
        return GLOBAL;
    }

    /**
     * Replaces the producer topology atomically at a generation boundary. The renderer chooses
     * the preset before creating its L5 resources. If a world is already active, rotate its
     * token into a fresh producer state so neither queued patches nor an old native context can
     * observe mixed dimensions. Keeping the identity active lets the next render-frame camera
     * update seed the new topology without waiting for another world-lifecycle event.
     */
    public synchronized void configurePreset(final VoxelClipmapLayout.Preset preset) {
        VoxelClipmapLayout.Budget next = VoxelClipmapLayout.forPreset(
                Objects.requireNonNull(preset, "preset")
        );
        if (this.budget.preset() == next.preset()) {
            return;
        }
        this.budget = next;
        if (this.activeWorld != null) {
            WorldState previous = this.activeWorld;
            previous.close();
            this.activeWorld = new WorldState(
                    previous.identity,
                    new VoxelWorldToken(++this.nextWorldGeneration, previous.token.dimensionId()),
                    next,
                    this.packerFactory.get()
            );
            this.worldTransitions++;
        }
    }

    public synchronized VoxelClipmapLayout.Preset configuredPreset() {
        return this.budget.preset();
    }

    public synchronized VoxelWorldToken openWorld(final Object worldIdentity, final String dimensionId) {
        requireWorldIdentity(worldIdentity);
        requireDimension(dimensionId);
        if (this.activeWorld != null && this.activeWorld.identity == worldIdentity
                && this.activeWorld.token.dimensionId().equals(dimensionId)) {
            return this.activeWorld.token;
        }
        closeActiveWorld();
        this.activeWorld = new WorldState(
                worldIdentity,
                new VoxelWorldToken(++this.nextWorldGeneration, dimensionId),
                this.budget,
                this.packerFactory.get()
        );
        this.worldTransitions++;
        return this.activeWorld.token;
    }

    public synchronized void closeWorld(final Object worldIdentity) {
        if (this.activeWorld != null && this.activeWorld.identity == worldIdentity) {
            this.activeWorld.close();
            this.activeWorld = null;
            this.worldTransitions++;
        }
    }

    /** Rotates the token; no worker snapshot from before a resource reload can be accepted. */
    public synchronized VoxelWorldToken reloadWorld(final Object worldIdentity, final String dimensionId) {
        requireWorldIdentity(worldIdentity);
        requireDimension(dimensionId);
        closeActiveWorld();
        this.activeWorld = new WorldState(
                worldIdentity,
                new VoxelWorldToken(++this.nextWorldGeneration, dimensionId),
                this.budget,
                this.packerFactory.get()
        );
        this.resourceReloads++;
        return this.activeWorld.token;
    }

    public synchronized void clear() {
        if (this.activeWorld != null) {
            this.activeWorld.close();
            this.activeWorld = null;
            this.worldTransitions++;
        }
    }

    /**
     * Recovers only the producer after an asynchronous native L5 command failure.  The old
     * private context may have been partially mutated, so rotate the voxel generation and
     * refill the current windows; direct-light and shadow admission remain outside this path.
     */
    public synchronized void recoverAfterGpuFailure() {
        if (this.activeWorld != null) {
            recoverFromQueueOverflow(this.activeWorld);
        }
    }

    private void closeActiveWorld() {
        if (this.activeWorld != null) {
            this.activeWorld.close();
        }
    }

    /** Stamps an already-created Sodium full rebuild; no independent voxel task is scheduled. */
    public synchronized VoxelSectionTask beginSectionTask(
            final Object worldIdentity,
            final String dimensionId,
            final long sectionKey
    ) {
        VoxelWorldToken token = this.openWorld(worldIdentity, dimensionId);
        WorldState world = requireActive(token);
        SectionState section = world.sections.computeIfAbsent(sectionKey, ignored -> new SectionState());
        section.issuedOwnerToken = ++this.nextOwnerToken;
        this.sectionTasksStarted++;
        return new VoxelSectionTask(token, sectionKey, section.revision, section.issuedOwnerToken);
    }

    public synchronized void noteSectionCandidateEncoded(final VoxelSectionCandidate candidate) {
        if (candidate != null) {
            this.sectionCandidatesEncoded++;
            this.sectionStatesScanned += candidate.scannedStateCount();
        }
    }

    /**
     * Publishes only after Sodium's geometry upload returned successfully. Revision, newest task
     * owner and world generation all have to match before the immutable snapshot becomes resident.
     */
    public synchronized boolean publishAccepted(final VoxelSectionCandidate candidate) {
        if (candidate == null || !candidate.claimForPublication()) {
            this.stalePublications++;
            return false;
        }
        VoxelSectionTask task = candidate.task();
        if (this.activeWorld == null || !this.activeWorld.token.equals(task.world())) {
            this.stalePublications++;
            return false;
        }
        SectionState section = this.activeWorld.sections.get(task.sectionKey());
        if (section == null || section.revision != task.revision()
                || section.issuedOwnerToken != task.ownerToken()) {
            this.stalePublications++;
            return false;
        }
        section.snapshot = candidate.snapshot();
        section.snapshotRevision = task.revision();
        section.acceptedOwnerToken = task.ownerToken();
        enqueueSectionBricks(this.activeWorld, task.sectionKey(), VoxelDirtyQueue.Priority.HIGH);
        this.acceptedPublications++;
        return true;
    }

    public synchronized boolean discardCandidate(final VoxelSectionCandidate candidate) {
        if (candidate != null && candidate.discard()) {
            this.discardedCandidates++;
            return true;
        }
        return false;
    }

    /**
     * A block event is revision/invalidation only. It never samples a live BlockState or emits a
     * patch; a later accepted Sodium geometry result supplies the replacement snapshot.
     */
    public synchronized void markBlockDirty(
            final Object worldIdentity,
            final String dimensionId,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        VoxelWorldToken token = this.openWorld(worldIdentity, dimensionId);
        WorldState world = requireActive(token);
        long sectionKey = SectionPos.asLong(
                SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockY),
                SectionPos.blockToSectionCoord(blockZ)
        );
        SectionState section = world.sections.computeIfAbsent(sectionKey, ignored -> new SectionState());
        section.revision++;
        enqueueSectionBricks(world, sectionKey, VoxelDirtyQueue.Priority.CRITICAL);
        this.blockInvalidations++;
    }

    /** Removes a resident snapshot only when the exact accepted owner is being unloaded. */
    public synchronized boolean removeSectionIfOwner(
            final Object worldIdentity,
            final long sectionKey,
            final long ownerToken
    ) {
        if (this.activeWorld == null || this.activeWorld.identity != worldIdentity) {
            return false;
        }
        SectionState section = this.activeWorld.sections.get(sectionKey);
        if (section == null || ownerToken == 0L || section.acceptedOwnerToken != ownerToken) {
            return false;
        }
        this.activeWorld.sections.remove(sectionKey);
        enqueueSectionBricks(this.activeWorld, sectionKey, VoxelDirtyQueue.Priority.HIGH);
        this.sectionUnloads++;
        this.activeWorld.unloadClearsPending++;
        return true;
    }

    /** Updates toroidal origins by generation/tag rotation; it never synchronously clears a volume. */
    public synchronized void updateCamera(
            final Object worldIdentity,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        if (this.activeWorld == null || this.activeWorld.identity != worldIdentity) {
            return;
        }
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) {
            return;
        }
        WorldState world = this.activeWorld;
        world.frameTick++;
        world.dirtyQueue.advanceTo(world.frameTick);
        long blockX = (long) Math.floor(cameraX);
        long blockY = (long) Math.floor(cameraY);
        long blockZ = (long) Math.floor(cameraZ);
        List<PendingScroll> scrolls = new ArrayList<>(world.levels.size());
        boolean hadInitializedLevel = world.hasInitializedLevel();
        for (int levelIndex = 0; levelIndex < world.levels.size(); levelIndex++) {
            LevelState level = world.levels.get(levelIndex);
            long nextX = centeredOrigin(blockX, level.level);
            long nextY = centeredOrigin(blockY, level.level);
            long nextZ = centeredOrigin(blockZ, level.level);
            if (!level.initialized) {
                scrolls.add(new PendingScroll(levelIndex, nextX, nextY, nextZ, true));
                continue;
            }
            if (level.originX == nextX && level.originY == nextY && level.originZ == nextZ) {
                continue;
            }
            scrolls.add(new PendingScroll(levelIndex, nextX, nextY, nextZ, false));
        }
        if (!scrolls.isEmpty()) {
            boolean fullReset = false;
            if (hadInitializedLevel) {
                for (PendingScroll scroll : scrolls) {
                    LevelState level = world.levels.get(scroll.levelIndex);
                    if (!scroll.initial && (Math.abs(scroll.originX - level.originX)
                            >= level.level.spanBlocks()
                            || Math.abs(scroll.originY - level.originY) >= level.level.spanBlocks()
                            || Math.abs(scroll.originZ - level.originZ) >= level.level.spanBlocks())) {
                        fullReset = true;
                        break;
                    }
                }
            }
            if (fullReset) {
                world.clipmapGeneration++;
                retireInFlightForScroll(world);
                Map<Integer, PendingScroll> targets = new HashMap<>();
                for (PendingScroll scroll : scrolls) {
                    targets.put(scroll.levelIndex, scroll);
                }
                List<PendingScroll> resetAllLevels = new ArrayList<>(world.levels.size());
                for (int levelIndex = 0; levelIndex < world.levels.size(); levelIndex++) {
                    LevelState level = world.levels.get(levelIndex);
                    PendingScroll target = targets.get(levelIndex);
                    resetAllLevels.add(target != null ? target : new PendingScroll(
                            levelIndex, level.originX, level.originY, level.originZ, true
                    ));
                }
                scrolls = resetAllLevels;
            }
            for (PendingScroll scroll : scrolls) {
                LevelState level = world.levels.get(scroll.levelIndex);
                long previousX = level.originX;
                long previousY = level.originY;
                long previousZ = level.originZ;
                boolean levelReset = fullReset || scroll.initial || Math.abs(scroll.originX - previousX)
                        >= level.level.spanBlocks()
                        || Math.abs(scroll.originY - previousY) >= level.level.spanBlocks()
                        || Math.abs(scroll.originZ - previousZ) >= level.level.spanBlocks();
                level.setOrigin(scroll.originX, scroll.originY, scroll.originZ);
                if (levelReset) {
                    scheduleWholeLevel(world, scroll.levelIndex, level);
                } else {
                    scheduleIncomingSlab(world, scroll.levelIndex, level, 0,
                            scroll.originX - previousX, scroll.originX, scroll.originY, scroll.originZ);
                    scheduleIncomingSlab(world, scroll.levelIndex, level, 1,
                            scroll.originY - previousY, scroll.originX, scroll.originY, scroll.originZ);
                    scheduleIncomingSlab(world, scroll.levelIndex, level, 2,
                            scroll.originZ - previousZ, scroll.originX, scroll.originY, scroll.originZ);
                }
            }
            pruneQueuedBricksOutsideCurrentWindows(world);
            this.cameraScrolls++;
        }
    }

    /** Identity-free render bridge entry point; safe because controller owns the active world. */
    public synchronized void updateCameraCurrent(
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        if (this.activeWorld != null) {
            this.updateCamera(this.activeWorld.identity, cameraX, cameraY, cameraZ);
        }
    }

    /** Returns the exact layout context to pair with the next native upload packet. */
    public synchronized VoxelClipmapSnapshot snapshot() {
        if (this.activeWorld == null) {
            return null;
        }
        List<VoxelClipmapSnapshot.Level> levels = new ArrayList<>(this.activeWorld.levels.size());
        for (int index = 0; index < this.activeWorld.levels.size(); index++) {
            LevelState state = this.activeWorld.levels.get(index);
            int edge = brickBlockEdge(state.level);
            levels.add(new VoxelClipmapSnapshot.Level(
                    index,
                    state.level.subdivision().scale(),
                    state.level.logicalEdge(),
                    Math.floorDiv(state.originX, edge),
                    Math.floorDiv(state.originY, edge),
                    Math.floorDiv(state.originZ, edge),
                    state.brickDimension()
            ));
        }
        return new VoxelClipmapSnapshot(
                this.activeWorld.token,
                this.activeWorld.clipmapGeneration,
                levels
        );
    }

    /**
     * Leases one bounded actual-count batch. A future bridge must call complete or retry; merely
     * observing a batch does not imply native submission succeeded.
     */
    public synchronized VoxelUploadBatch leaseUploadBatch(final long frameId) {
        if (frameId < 0L || this.activeWorld == null || !this.activeWorld.hasInitializedLevel()) {
            return null;
        }
        WorldState world = this.activeWorld;
        VoxelUploadBatch retried = leaseRetriedBatch(world, frameId);
        if (retried != null) {
            return retried;
        }
        harvestPackResults(world);
        schedulePackJobs(world);
        harvestPackResults(world);
        List<ReadyBrick> readyCandidates = new ArrayList<>(world.readyCapacity);
        for (ArrayDeque<ReadyBrick> levelReady : world.readyByLevel) {
            levelReady.removeIf(ready -> !isTicketCurrent(world, ready.ticket()));
            readyCandidates.addAll(levelReady);
        }
        if (readyCandidates.isEmpty()) {
            return null;
        }
        readyCandidates.sort((left, right) -> compareReadyForPublication(
                left.ticket().dirty(), right.ticket().dirty(),
                world.frameTick, world.starvationBoundTicks
        ));
        int actualCount = Math.min(
                this.budget.hardDrainBudget(), readyCandidates.size()
        );
        List<ReadyBrick> leased = new ArrayList<>(
                readyCandidates.subList(0, actualCount)
        );
        // The admission set is fair across levels; the native packet ABI still requires the
        // selected records to be grouped by level.
        leased.sort((left, right) -> Integer.compare(
                left.patch().level(), right.patch().level()
        ));
        List<VoxelBrickPatch> patches = new ArrayList<>(actualCount);
        for (ReadyBrick ready : leased) {
            if (!world.readyByLevel.get(ready.patch().level()).remove(ready)) {
                throw new IllegalStateException("Selected L5 ready brick disappeared");
            }
            patches.add(ready.patch());
        }
        VoxelDirtyQueue.Telemetry queueTelemetry = world.dirtyQueue.telemetry();
        QueueTelemetryDelta queueDelta = takeQueueTelemetryDelta(world, queueTelemetry);
        long batchId = ++this.nextBatchId;
        VoxelUploadBatch batch = new VoxelUploadBatch(
                batchId,
                world.token,
                world.clipmapGeneration,
                frameId,
                patches,
                pendingProducerCount(world),
                oldestPendingAge(world, queueTelemetry.oldestAgeTicks()),
                world.scrollSlabsPending,
                world.unloadClearsPending,
                queueDelta.coalesced(),
                queueDelta.rejected()
        );
        world.inFlight.put(batchId, new InFlightBatch(batch, leased));
        world.scrollSlabsPending = 0;
        world.unloadClearsPending = 0;
        this.batchesLeased++;
        return batch;
    }

    static int compareReadyForPublication(
            final VoxelDirtyQueue.DirtyBrick left,
            final VoxelDirtyQueue.DirtyBrick right,
            final long currentTick,
            final long starvationBoundTicks
    ) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (currentTick < 0L || starvationBoundTicks <= 0L) {
            throw new IllegalArgumentException("Invalid L5 ready-publication clock");
        }
        boolean leftStarved = Math.max(0L, currentTick - left.enqueuedTick())
                >= starvationBoundTicks;
        boolean rightStarved = Math.max(0L, currentTick - right.enqueuedTick())
                >= starvationBoundTicks;
        if (leftStarved != rightStarved) {
            return leftStarved ? -1 : 1;
        }
        int order = leftStarved
                ? Long.compare(left.enqueueSequence(), right.enqueueSequence())
                : Integer.compare(left.priority().ordinal(), right.priority().ordinal());
        if (order != 0) {
            return order;
        }
        order = Long.compare(left.enqueueSequence(), right.enqueueSequence());
        if (order != 0) {
            return order;
        }
        VoxelDirtyQueue.BrickKey leftKey = left.key();
        VoxelDirtyQueue.BrickKey rightKey = right.key();
        order = Integer.compare(leftKey.level(), rightKey.level());
        if (order != 0) {
            return order;
        }
        order = Long.compare(leftKey.brickX(), rightKey.brickX());
        if (order != 0) {
            return order;
        }
        order = Long.compare(leftKey.brickY(), rightKey.brickY());
        return order != 0 ? order : Long.compare(leftKey.brickZ(), rightKey.brickZ());
    }

    /** Confirms that the exact leased batch reached the native upload ring. */
    public synchronized boolean completeUploadBatch(final long batchId) {
        InFlightBatch inFlight = removeCurrentBatch(batchId);
        if (inFlight == null) {
            this.staleBatches++;
            return false;
        }
        this.batchesCompleted++;
        return true;
    }

    /** Requeues a ring-busy batch without changing renderer admission or allocating a fallback. */
    public synchronized boolean retryUploadBatch(final long batchId) {
        InFlightBatch inFlight = removeCurrentBatch(batchId);
        if (inFlight == null) {
            this.staleBatches++;
            return false;
        }
        WorldState world = this.activeWorld;
        boolean exactCurrent = inFlight.leased.stream()
                .allMatch(ready -> isTicketCurrent(world, ready.ticket()));
        if (exactCurrent) {
            world.retries.addFirst(new RetryBatch(inFlight.batch, inFlight.leased));
        } else {
            restoreBatchTelemetry(world, inFlight.batch);
        }
        this.busyRetries++;
        return true;
    }

    private VoxelUploadBatch leaseRetriedBatch(final WorldState world, final long frameId) {
        while (!world.retries.isEmpty()) {
            RetryBatch retry = world.retries.removeFirst();
            if (!retry.leased().stream().allMatch(ready -> isTicketCurrent(world, ready.ticket()))) {
                restoreBatchTelemetry(world, retry.previous());
                continue;
            }
            long batchId = ++this.nextBatchId;
            VoxelUploadBatch previous = retry.previous();
            VoxelUploadBatch batch = new VoxelUploadBatch(
                    batchId, world.token, world.clipmapGeneration, frameId,
                    retry.leased().stream().map(ReadyBrick::patch).toList(),
                    previous.queueRemaining(), previous.oldestAgeTicks(),
                    previous.scrollSlabs(), previous.unloadClears(),
                    previous.coalescedDelta(), previous.rejectedDelta()
            );
            world.inFlight.put(batchId, new InFlightBatch(batch, retry.leased()));
            this.batchesLeased++;
            return batch;
        }
        return null;
    }

    public synchronized VoxelClipmapTelemetry telemetry() {
        int sections = this.activeWorld == null ? 0 : this.activeWorld.sections.size();
        int inFlight = this.activeWorld == null ? 0 : this.activeWorld.inFlight.size();
        VoxelDirtyQueue.Telemetry queue = this.activeWorld == null
                ? emptyQueueTelemetry(this.budget)
                : this.activeWorld.dirtyQueue.telemetry();
        return new VoxelClipmapTelemetry(
                this.worldTransitions,
                this.resourceReloads,
                this.sectionTasksStarted,
                this.sectionCandidatesEncoded,
                this.sectionStatesScanned,
                this.acceptedPublications,
                this.stalePublications,
                this.discardedCandidates,
                this.blockInvalidations,
                this.sectionUnloads,
                this.cameraScrolls,
                this.scrollSlabsScheduled,
                this.queueRejected,
                this.batchesLeased,
                this.batchesCompleted,
                this.busyRetries,
                this.staleBatches,
                this.deferredWithoutAcceptedGeometry,
                sections,
                inFlight,
                queue
        );
    }

    private static VoxelDirtyQueue.Telemetry emptyQueueTelemetry(
            final VoxelClipmapLayout.Budget budget
    ) {
        return new VoxelDirtyQueue.Telemetry(
                budget.hardQueueCapacity(), 0, 0, budget.hardDrainBudget(),
                java.util.OptionalLong.empty(), budget.starvationBoundTicks(),
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                VoxelDirtyQueue.OverflowReason.NONE
        );
    }

    synchronized int asyncPipelineDepth() {
        return this.activeWorld == null ? 0 : pipelineCount(this.activeWorld);
    }

    synchronized int asyncPipelineCapacity() {
        return this.activeWorld == null
                ? Math.multiplyExact(2, this.budget.hardDrainBudget())
                : this.activeWorld.readyCapacity;
    }

    synchronized int asyncPendingPackJobs() {
        return this.activeWorld == null ? 0 : this.activeWorld.packing.size();
    }

    private void scheduleIncomingSlab(
            final WorldState world,
            final int levelIndex,
            final LevelState state,
            final int axis,
            final long delta,
            final long nextX,
            final long nextY,
            final long nextZ
    ) {
        if (delta == 0L) {
            return;
        }
        int edge = brickBlockEdge(state.level);
        int width = Math.toIntExact(Math.abs(delta) / edge);
        int dimension = state.brickDimension();
        long originX = Math.floorDiv(nextX, edge);
        long originY = Math.floorDiv(nextY, edge);
        long originZ = Math.floorDiv(nextZ, edge);
        for (int slab = 0; slab < width; slab++) {
            long slabCoordinate = delta > 0L ? dimension - width + slab : slab;
            for (int secondary = 0; secondary < dimension; secondary++) {
                for (int tertiary = 0; tertiary < dimension; tertiary++) {
                    switch (axis) {
                        case 0 -> enqueueBrick(world, levelIndex,
                                originX + slabCoordinate, originY + secondary, originZ + tertiary,
                                VoxelDirtyQueue.Priority.NORMAL);
                        case 1 -> enqueueBrick(world, levelIndex,
                                originX + secondary, originY + slabCoordinate, originZ + tertiary,
                                VoxelDirtyQueue.Priority.NORMAL);
                        case 2 -> enqueueBrick(world, levelIndex,
                                originX + secondary, originY + tertiary, originZ + slabCoordinate,
                                VoxelDirtyQueue.Priority.NORMAL);
                        default -> throw new IllegalStateException("Unknown voxel scroll axis");
                    }
                }
            }
        }
        this.scrollSlabsScheduled++;
        world.scrollSlabsPending++;
    }

    private void scheduleWholeLevel(
            final WorldState world,
            final int levelIndex,
            final LevelState level
    ) {
        int edge = brickBlockEdge(level.level);
        long originX = Math.floorDiv(level.originX, edge);
        long originY = Math.floorDiv(level.originY, edge);
        long originZ = Math.floorDiv(level.originZ, edge);
        int dimension = level.brickDimension();
        for (int z = 0; z < dimension; z++) {
            for (int y = 0; y < dimension; y++) {
                for (int x = 0; x < dimension; x++) {
                    enqueueBrick(world, levelIndex, originX + x, originY + y, originZ + z,
                            VoxelDirtyQueue.Priority.NORMAL);
                }
            }
        }
    }

    private void enqueueSectionBricks(
            final WorldState world,
            final long sectionKey,
            final VoxelDirtyQueue.Priority priority
    ) {
        int sectionX = SectionPos.x(sectionKey);
        int sectionY = SectionPos.y(sectionKey);
        int sectionZ = SectionPos.z(sectionKey);
        long minX = (long) sectionX << 4;
        long minY = (long) sectionY << 4;
        long minZ = (long) sectionZ << 4;
        for (int levelIndex = 0; levelIndex < world.levels.size(); levelIndex++) {
            LevelState level = world.levels.get(levelIndex);
            if (!level.initialized) {
                continue;
            }
            int edge = brickBlockEdge(level.level);
            long maxX = minX + 15L;
            long maxY = minY + 15L;
            long maxZ = minZ + 15L;
            for (long z = Math.floorDiv(minZ, edge); z <= Math.floorDiv(maxZ, edge); z++) {
                for (long y = Math.floorDiv(minY, edge); y <= Math.floorDiv(maxY, edge); y++) {
                    for (long x = Math.floorDiv(minX, edge); x <= Math.floorDiv(maxX, edge); x++) {
                        if (isVisible(level, x, y, z)) {
                            enqueueBrick(world, levelIndex, x, y, z, priority);
                        }
                    }
                }
            }
        }
    }

    private void enqueueBrick(
            final WorldState world,
            final int level,
            final long brickX,
            final long brickY,
            final long brickZ,
            final VoxelDirtyQueue.Priority priority
    ) {
        BrickCoordinate coordinate = new BrickCoordinate(level, brickX, brickY, brickZ);
        world.desiredVersions.merge(coordinate, 1L,
                (current, ignored) -> saturatingIncrement(current));
        discardReadyCoordinate(world, coordinate);
        VoxelDirtyQueue.BrickKey key = new VoxelDirtyQueue.BrickKey(
                        world.token.generation(),
                        world.clipmapGeneration,
                        level,
                        brickX,
                        brickY,
                        brickZ
                );
        VoxelDirtyQueue.BrickKey previous = world.queued.get(coordinate);
        if (previous != null && !previous.equals(key)) {
            world.dirtyQueue.discardIf(previous::equals);
        }
        VoxelDirtyQueue.OfferResult offered = world.dirtyQueue.offer(
                key,
                priority,
                1L
        );
        if (offered.status() != VoxelDirtyQueue.OfferStatus.REJECTED) {
            world.queued.put(coordinate, key);
        }
        if (offered.status() == VoxelDirtyQueue.OfferStatus.REJECTED) {
            this.queueRejected++;
            recoverFromQueueOverflow(world);
        }
    }

    private boolean deferLeasedBrick(
            final WorldState world,
            final VoxelDirtyQueue.DirtyBrick brick
    ) {
        BrickCoordinate coordinate = coordinate(brick.key());
        VoxelDirtyQueue.OfferResult offered = world.dirtyQueue.defer(brick);
        if (offered.status() != VoxelDirtyQueue.OfferStatus.REJECTED) {
            world.queued.put(coordinate, brick.key());
            return true;
        }
        this.queueRejected++;
        recoverFromQueueOverflow(world);
        return false;
    }

    private static void discardReadyCoordinate(
            final WorldState world,
            final BrickCoordinate coordinate
    ) {
        if (coordinate.level < 0 || coordinate.level >= world.readyByLevel.size()) {
            return;
        }
        world.readyByLevel.get(coordinate.level).removeIf(ready ->
                ready.coordinate().equals(coordinate));
    }

    /**
     * Queue telemetry is cumulative, while the native ABI aggregates packet values. Lease only
     * the not-yet-submitted increments and return them with a busy batch so retries stay exact.
     */
    private static QueueTelemetryDelta takeQueueTelemetryDelta(
            final WorldState world,
            final VoxelDirtyQueue.Telemetry telemetry
    ) {
        world.pendingCoalescedTelemetry = saturatingAdd(
                world.pendingCoalescedTelemetry,
                counterDelta(telemetry.coalesced(), world.observedCoalescedTelemetry)
        );
        world.pendingRejectedTelemetry = saturatingAdd(
                world.pendingRejectedTelemetry,
                counterDelta(telemetry.rejected(), world.observedRejectedTelemetry)
        );
        world.observedCoalescedTelemetry = telemetry.coalesced();
        world.observedRejectedTelemetry = telemetry.rejected();
        QueueTelemetryDelta delta = new QueueTelemetryDelta(
                world.pendingCoalescedTelemetry,
                world.pendingRejectedTelemetry
        );
        world.pendingCoalescedTelemetry = 0L;
        world.pendingRejectedTelemetry = 0L;
        return delta;
    }

    private static void restoreQueueTelemetryDelta(
            final WorldState world,
            final VoxelUploadBatch batch
    ) {
        world.pendingCoalescedTelemetry = saturatingAdd(
                world.pendingCoalescedTelemetry, batch.coalescedDelta()
        );
        world.pendingRejectedTelemetry = saturatingAdd(
                world.pendingRejectedTelemetry, batch.rejectedDelta()
        );
    }

    private static void restoreBatchTelemetry(
            final WorldState world,
            final VoxelUploadBatch batch
    ) {
        restoreQueueTelemetryDelta(world, batch);
        world.scrollSlabsPending = saturatingAdd(
                world.scrollSlabsPending, batch.scrollSlabs()
        );
        world.unloadClearsPending = saturatingAdd(
                world.unloadClearsPending, batch.unloadClears()
        );
    }

    private static long counterDelta(final long current, final long observed) {
        return current >= observed ? current - observed : current;
    }

    private static long saturatingAdd(final long left, final long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static int saturatingAdd(final int left, final int right) {
        return right > Integer.MAX_VALUE - left ? Integer.MAX_VALUE : left + right;
    }

    private static long saturatingIncrement(final long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    /**
     * Capacity is a hard L5 boundary. On overflow rotate only voxel generation/tags and enqueue
     * a bounded refill of the current windows; direct lighting admission and terrain geometry are
     * deliberately untouched.
     */
    private void recoverFromQueueOverflow(final WorldState world) {
        if (world.recoveringOverflow) {
            return;
        }
        world.recoveringOverflow = true;
        try {
            world.clipmapGeneration++;
            world.dirtyQueue.discardIf(ignored -> true);
            world.queued.clear();
            world.desiredVersions.clear();
            for (ArrayDeque<ReadyBrick> ready : world.readyByLevel) {
                ready.clear();
            }
            for (InFlightBatch inFlight : world.inFlight.values()) {
                restoreBatchTelemetry(world, inFlight.batch);
            }
            world.inFlight.clear();
            for (RetryBatch retry : world.retries) {
                restoreBatchTelemetry(world, retry.previous());
            }
            world.retries.clear();
            for (int levelIndex = 0; levelIndex < world.levels.size(); levelIndex++) {
                LevelState level = world.levels.get(levelIndex);
                if (level.initialized) {
                    scheduleWholeLevel(world, levelIndex, level);
                }
            }
        } finally {
            world.recoveringOverflow = false;
        }
    }

    /** Keeps teleport/flight churn bounded without clearing any toroidal GPU slot synchronously. */
    private static void pruneQueuedBricksOutsideCurrentWindows(final WorldState world) {
        List<Map.Entry<BrickCoordinate, VoxelDirtyQueue.BrickKey>> entries =
                List.copyOf(world.queued.entrySet());
        for (Map.Entry<BrickCoordinate, VoxelDirtyQueue.BrickKey> entry : entries) {
            BrickCoordinate coordinate = entry.getKey();
            if (coordinate.level < 0 || coordinate.level >= world.levels.size()
                    || !isVisible(world.levels.get(coordinate.level), coordinate.x, coordinate.y,
                    coordinate.z)) {
                world.dirtyQueue.discardIf(entry.getValue()::equals);
                world.queued.remove(coordinate);
            }
        }
        Iterator<BrickCoordinate> desired = world.desiredVersions.keySet().iterator();
        while (desired.hasNext()) {
            BrickCoordinate coordinate = desired.next();
            if (coordinate.level < 0 || coordinate.level >= world.levels.size()
                    || !isVisible(world.levels.get(coordinate.level), coordinate.x, coordinate.y,
                    coordinate.z)) {
                desired.remove();
            }
        }
        for (ArrayDeque<ReadyBrick> ready : world.readyByLevel) {
            ready.removeIf(brick -> !world.desiredVersions.containsKey(brick.coordinate()));
        }
    }

    private void schedulePackJobs(final WorldState world) {
        int scanBudget = world.dirtyQueue.size();
        int scanned = 0;
        while (scanned < scanBudget) {
            int freePipeline = world.readyCapacity - pipelineCount(world);
            int requested = Math.min(
                    freePipeline,
                    VoxelBrickPacker.MAX_PENDING_JOBS - world.packer.pendingJobs()
            );
            requested = Math.min(requested, scanBudget - scanned);
            if (requested <= 0) {
                return;
            }
            VoxelDirtyQueue.Drain drain = world.dirtyQueue.drainForAsyncPacking(requested);
            if (drain.actualCount() == 0) {
                return;
            }
            scanned += drain.actualCount();
            for (VoxelDirtyQueue.DirtyBrick dirty : drain.bricks()) {
                BrickCoordinate coordinate = coordinate(dirty.key());
                world.queued.remove(coordinate, dirty.key());
                VoxelBrickPacker.Ticket ticket = captureTicket(world, dirty);
                if (ticket == null) {
                    this.deferredWithoutAcceptedGeometry++;
                    if (isDirtyCurrentBasic(world, dirty.key())) {
                        deferLeasedBrick(world, dirty);
                    }
                    continue;
                }
                world.packing.add(ticket);
                if (!world.packer.submit(ticket)) {
                    world.packing.remove(ticket);
                    deferLeasedBrick(world, dirty);
                }
            }
        }
    }

    private void harvestPackResults(final WorldState world) {
        VoxelBrickPacker.Result result;
        while ((result = world.packer.pollCompleted()) != null) {
            world.packing.remove(result.ticket());
            if (result.failure() != null) {
                if (isTicketCurrent(world, result.ticket())) {
                    deferLeasedBrick(world, result.ticket().dirty());
                }
                continue;
            }
            if (!isTicketCurrent(world, result.ticket())) {
                this.staleBatches++;
                continue;
            }
            if (pipelineCount(world) >= world.readyCapacity) {
                deferLeasedBrick(world, result.ticket().dirty());
                continue;
            }
            int level = result.patch().level();
            world.readyByLevel.get(level).addLast(new ReadyBrick(
                    coordinate(result.ticket().dirty().key()), result.ticket(), result.patch()
            ));
        }
    }

    private VoxelBrickPacker.Ticket captureTicket(
            final WorldState world,
            final VoxelDirtyQueue.DirtyBrick dirty
    ) {
        VoxelDirtyQueue.BrickKey key = dirty.key();
        if (key.worldGeneration() != world.token.generation()
                || key.clipmapGeneration() != world.clipmapGeneration
                || key.level() < 0 || key.level() >= world.levels.size()) {
            return null;
        }
        LevelState level = world.levels.get(key.level());
        if (!isVisible(level, key.brickX(), key.brickY(), key.brickZ())) {
            return null;
        }
        BrickCoordinate coordinate = coordinate(key);
        Long desiredVersion = world.desiredVersions.get(coordinate);
        if (desiredVersion == null) {
            return null;
        }
        List<VoxelBrickPacker.Contributor> contributors = captureContributors(
                world, level.level, key.brickX(), key.brickY(), key.brickZ()
        );
        if (contributors == null) {
            return null;
        }
        return new VoxelBrickPacker.Ticket(
                dirty,
                world.token,
                world.clipmapGeneration,
                level.level,
                level.brickDimension(),
                desiredVersion,
                world.nextContentStamp(),
                contributors
        );
    }

    private static List<VoxelBrickPacker.Contributor> captureContributors(
            final WorldState world,
            final VoxelClipmapLayout.Level level,
            final long brickX,
            final long brickY,
            final long brickZ
    ) {
        int edge = brickBlockEdge(level);
        long minX = Math.multiplyExact(brickX, edge);
        long minY = Math.multiplyExact(brickY, edge);
        long minZ = Math.multiplyExact(brickZ, edge);
        long maxX = minX + edge - 1L;
        long maxY = minY + edge - 1L;
        long maxZ = minZ + edge - 1L;
        List<VoxelBrickPacker.Contributor> contributors = new ArrayList<>(8);
        for (long sectionZ = Math.floorDiv(minZ, 16L); sectionZ <= Math.floorDiv(maxZ, 16L); sectionZ++) {
            for (long sectionY = Math.floorDiv(minY, 16L); sectionY <= Math.floorDiv(maxY, 16L); sectionY++) {
                for (long sectionX = Math.floorDiv(minX, 16L); sectionX <= Math.floorDiv(maxX, 16L); sectionX++) {
                    long sectionKey = SectionPos.asLong(
                            Math.toIntExact(sectionX), Math.toIntExact(sectionY), Math.toIntExact(sectionZ)
                    );
                    SectionState state = world.sections.get(sectionKey);
                    if (state == null) {
                        contributors.add(new VoxelBrickPacker.Contributor(
                                sectionKey, 0L, 0L, null, true
                        ));
                    } else {
                        if (state.snapshot == null || state.snapshotRevision != state.revision) {
                            return null;
                        }
                        contributors.add(new VoxelBrickPacker.Contributor(
                                sectionKey, state.snapshotRevision, state.acceptedOwnerToken,
                                state.snapshot, false
                        ));
                    }
                }
            }
        }
        return List.copyOf(contributors);
    }

    private static boolean isTicketCurrent(
            final WorldState world,
            final VoxelBrickPacker.Ticket ticket
    ) {
        VoxelDirtyQueue.BrickKey key = ticket.dirty().key();
        BrickCoordinate coordinate = coordinate(key);
        if (!world.token.equals(ticket.world())
                || world.clipmapGeneration != ticket.clipmapGeneration()
                || key.level() < 0 || key.level() >= world.levels.size()
                || !Objects.equals(world.desiredVersions.get(coordinate), ticket.desiredVersion())
                || !isVisible(world.levels.get(key.level()), key.brickX(), key.brickY(), key.brickZ())) {
            return false;
        }
        for (VoxelBrickPacker.Contributor contributor : ticket.contributors()) {
            SectionState state = world.sections.get(contributor.sectionKey());
            if (contributor.absent()) {
                if (state != null) {
                    return false;
                }
            } else if (state == null || state.snapshot != contributor.snapshot()
                    || state.snapshotRevision != contributor.revision()
                    || state.revision != contributor.revision()
                    || state.acceptedOwnerToken != contributor.ownerToken()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDirtyCurrentBasic(
            final WorldState world,
            final VoxelDirtyQueue.BrickKey key
    ) {
        BrickCoordinate coordinate = coordinate(key);
        return key.worldGeneration() == world.token.generation()
                && key.clipmapGeneration() == world.clipmapGeneration
                && key.level() >= 0 && key.level() < world.levels.size()
                && world.desiredVersions.containsKey(coordinate)
                && isVisible(world.levels.get(key.level()), key.brickX(), key.brickY(), key.brickZ());
    }

    private static BrickCoordinate coordinate(final VoxelDirtyQueue.BrickKey key) {
        return new BrickCoordinate(key.level(), key.brickX(), key.brickY(), key.brickZ());
    }

    private static int readyCount(final WorldState world) {
        int count = 0;
        for (ArrayDeque<ReadyBrick> ready : world.readyByLevel) {
            count += ready.size();
        }
        for (RetryBatch retry : world.retries) {
            count += retry.leased().size();
        }
        return count;
    }

    private static int pipelineCount(final WorldState world) {
        int count = readyCount(world) + world.packing.size();
        for (InFlightBatch batch : world.inFlight.values()) {
            count += batch.leased().size();
        }
        return count;
    }

    private static int pendingProducerCount(final WorldState world) {
        long count = (long) world.dirtyQueue.size() + readyCount(world) + world.packing.size();
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    private static long oldestPendingAge(final WorldState world, final long dirtyOldestAge) {
        long oldest = dirtyOldestAge;
        for (ArrayDeque<ReadyBrick> ready : world.readyByLevel) {
            for (ReadyBrick brick : ready) {
                oldest = Math.max(oldest,
                        Math.max(0L, world.frameTick - brick.ticket().dirty().enqueuedTick()));
            }
        }
        for (VoxelBrickPacker.Ticket ticket : world.packing) {
            oldest = Math.max(oldest,
                    Math.max(0L, world.frameTick - ticket.dirty().enqueuedTick()));
        }
        return oldest;
    }

    private static boolean isVisible(
            final LevelState level,
            final long brickX,
            final long brickY,
            final long brickZ
    ) {
        if (!level.initialized) {
            return false;
        }
        int edge = brickBlockEdge(level.level);
        long originX = Math.floorDiv(level.originX, edge);
        long originY = Math.floorDiv(level.originY, edge);
        long originZ = Math.floorDiv(level.originZ, edge);
        int dimension = level.brickDimension();
        return brickX >= originX && brickX < originX + dimension
                && brickY >= originY && brickY < originY + dimension
                && brickZ >= originZ && brickZ < originZ + dimension;
    }

    private static int brickBlockEdge(final VoxelClipmapLayout.Level level) {
        return level.brickBlockEdge();
    }

    private static long centeredOrigin(final long cameraBlock, final VoxelClipmapLayout.Level level) {
        int edge = brickBlockEdge(level);
        long candidate = Math.addExact(
                Math.subtractExact(cameraBlock, level.spanBlocks() / 2L),
                VoxelClipmapLayout.scrollPhaseBlocks(level)
        );
        return Math.multiplyExact(Math.floorDiv(candidate, edge), edge);
    }

    private InFlightBatch removeCurrentBatch(final long batchId) {
        if (this.activeWorld == null) {
            return null;
        }
        InFlightBatch inFlight = this.activeWorld.inFlight.remove(batchId);
        if (inFlight == null || !inFlight.batch.world().equals(this.activeWorld.token)
                || inFlight.batch.clipmapGeneration() != this.activeWorld.clipmapGeneration) {
            return null;
        }
        return inFlight;
    }

    /** A previous-generation ring lease cannot be completed after scroll; requeue its exact keys. */
    private void retireInFlightForScroll(final WorldState world) {
        if (world.inFlight.isEmpty() && world.retries.isEmpty()) {
            return;
        }
        List<InFlightBatch> leases = List.copyOf(world.inFlight.values());
        world.inFlight.clear();
        for (InFlightBatch lease : leases) {
            for (ReadyBrick ready : lease.leased) {
                VoxelDirtyQueue.DirtyBrick brick = ready.ticket().dirty();
                enqueueBrick(world, brick.key().level(), brick.key().brickX(), brick.key().brickY(),
                        brick.key().brickZ(), brick.priority());
            }
            restoreBatchTelemetry(world, lease.batch);
        }
        List<RetryBatch> retries = List.copyOf(world.retries);
        world.retries.clear();
        for (RetryBatch retry : retries) {
            for (ReadyBrick ready : retry.leased()) {
                VoxelDirtyQueue.DirtyBrick brick = ready.ticket().dirty();
                enqueueBrick(world, brick.key().level(), brick.key().brickX(), brick.key().brickY(),
                        brick.key().brickZ(), brick.priority());
            }
            restoreBatchTelemetry(world, retry.previous());
        }
    }

    private WorldState requireActive(final VoxelWorldToken token) {
        if (this.activeWorld == null || !this.activeWorld.token.equals(token)) {
            throw new IllegalStateException("Voxel world token is no longer active");
        }
        return this.activeWorld;
    }

    private static void requireWorldIdentity(final Object worldIdentity) {
        if (worldIdentity == null) {
            throw new NullPointerException("worldIdentity");
        }
    }

    private static void requireDimension(final String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
    }

    private static final class WorldState {
        private final Object identity;
        private final VoxelWorldToken token;
        private final List<LevelState> levels;
        private final Map<Long, SectionState> sections = new HashMap<>();
        private final Map<Long, InFlightBatch> inFlight = new HashMap<>();
        private final ArrayDeque<RetryBatch> retries = new ArrayDeque<>();
        private final Map<BrickCoordinate, VoxelDirtyQueue.BrickKey> queued = new HashMap<>();
        private final Map<BrickCoordinate, Long> desiredVersions = new HashMap<>();
        private final List<ArrayDeque<ReadyBrick>> readyByLevel;
        private final List<VoxelBrickPacker.Ticket> packing = new ArrayList<>();
        private final VoxelDirtyQueue dirtyQueue;
        private final VoxelBrickPacker packer;
        private final int readyCapacity;
        private final long starvationBoundTicks;
        private long clipmapGeneration = 1L;
        private long frameTick;
        private int scrollSlabsPending;
        private int unloadClearsPending;
        private int contentStamp;
        private long observedCoalescedTelemetry;
        private long observedRejectedTelemetry;
        private long pendingCoalescedTelemetry;
        private long pendingRejectedTelemetry;
        private boolean recoveringOverflow;

        private WorldState(
                final Object identity,
                final VoxelWorldToken token,
                final VoxelClipmapLayout.Budget budget,
                final VoxelBrickPacker packer
        ) {
            this.identity = identity;
            this.token = token;
            this.levels = budget.levels().stream().map(LevelState::new).toList();
            this.readyByLevel = budget.levels().stream()
                    .map(ignored -> new ArrayDeque<ReadyBrick>())
                    .toList();
            this.packer = Objects.requireNonNull(packer, "packer");
            this.readyCapacity = Math.multiplyExact(2, budget.hardDrainBudget());
            this.starvationBoundTicks = budget.starvationBoundTicks();
            this.dirtyQueue = new VoxelDirtyQueue(
                    budget.hardQueueCapacity(),
                    budget.hardDrainBudget(),
                    java.util.OptionalLong.empty(),
                    budget.starvationBoundTicks()
            );
        }

        private boolean hasInitializedLevel() {
            return this.levels.stream().anyMatch(level -> level.initialized);
        }

        private int nextContentStamp() {
            this.contentStamp++;
            if (this.contentStamp == 0) {
                this.contentStamp++;
            }
            return this.contentStamp;
        }

        private void close() {
            this.packer.close();
            this.packing.clear();
            for (ArrayDeque<ReadyBrick> ready : this.readyByLevel) {
                ready.clear();
            }
            this.inFlight.clear();
            this.retries.clear();
        }
    }

    private static final class LevelState {
        private final VoxelClipmapLayout.Level level;
        private boolean initialized;
        private long originX;
        private long originY;
        private long originZ;

        private LevelState(final VoxelClipmapLayout.Level level) {
            this.level = level;
        }

        private void setOrigin(final long x, final long y, final long z) {
            this.initialized = true;
            this.originX = x;
            this.originY = y;
            this.originZ = z;
        }

        private int brickDimension() {
            return this.level.spanBlocks() / brickBlockEdge(this.level);
        }
    }

    private static final class SectionState {
        private long revision = 1L;
        private long issuedOwnerToken;
        private long acceptedOwnerToken;
        private long snapshotRevision;
        private VoxelSectionSnapshot snapshot;
    }

    private record InFlightBatch(
            VoxelUploadBatch batch,
            List<ReadyBrick> leased
    ) {
    }

    private record RetryBatch(
            VoxelUploadBatch previous,
            List<ReadyBrick> leased
    ) {
        private RetryBatch {
            leased = List.copyOf(leased);
        }
    }

    private record ReadyBrick(
            BrickCoordinate coordinate,
            VoxelBrickPacker.Ticket ticket,
            VoxelBrickPatch patch
    ) {
    }

    private record QueueTelemetryDelta(long coalesced, long rejected) {
        private QueueTelemetryDelta {
            if (coalesced < 0L || rejected < 0L) {
                throw new IllegalArgumentException("Voxel queue telemetry delta is invalid");
            }
        }
    }

    private record BrickCoordinate(int level, long x, long y, long z) {
    }

    private record PendingScroll(
            int levelIndex,
            long originX,
            long originY,
            long originZ,
            boolean initial
    ) {
    }
}
