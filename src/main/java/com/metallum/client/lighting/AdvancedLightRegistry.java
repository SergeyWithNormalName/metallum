package com.metallum.client.lighting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

/** Bounded, race-safe CPU registry for extraction and retained frame admission; owns no GPU data. */
public final class AdvancedLightRegistry {
    public static final int CONTRACT_VERSION = 1;
    // A section contains at most 4096 emitting cells. Dense fluid compaction must observe the
    // complete set before any bound is applied, otherwise its occupancy, support and energy are
    // already irrecoverably lost. The cached effective list remains compact for dense fluids.
    public static final int MAX_LIGHTS_PER_SECTION = StaticLightSectionScanner.BLOCKS_PER_SECTION;
    public static final int MAX_RESIDENT_SECTIONS = 8192;
    public static final int MAX_DYNAMIC_LIGHTS = 512;
    public static final int MAX_FRAME_LIGHTS = 4096;
    static final double RETAINED_ADMISSION_CAMERA_CUT_DISTANCE = 32.0;

    private static final double RETAINED_ADMISSION_CAMERA_CUT_DISTANCE_SQUARED =
            RETAINED_ADMISSION_CAMERA_CUT_DISTANCE * RETAINED_ADMISSION_CAMERA_CUT_DISTANCE;

    public enum Hook {
        STATIC_TASK(1 << 0),
        ACCEPTED_UPLOAD(1 << 1),
        BLOCK_CHANGE(1 << 2),
        DYNAMIC_ENTITY(1 << 3),
        WORLD_LIFECYCLE(1 << 4),
        RESOURCE_RELOAD(1 << 5);

        private final int bit;

        Hook(final int bit) {
            this.bit = bit;
        }
    }

    private static final int REQUIRED_HOOK_MASK = Hook.STATIC_TASK.bit
            | Hook.ACCEPTED_UPLOAD.bit
            | Hook.BLOCK_CHANGE.bit
            | Hook.DYNAMIC_ENTITY.bit
            | Hook.WORLD_LIFECYCLE.bit
            | Hook.RESOURCE_RELOAD.bit;
    private static final AdvancedLightRegistry GLOBAL = new AdvancedLightRegistry();

    private final AtomicInteger observedHookMask = new AtomicInteger();
    private final int maxResidentSections;
    private long nextWorldGeneration;
    private long nextOwnerToken;
    private WorldState activeWorld;
    private RetiredWorld retiredWorld;

    private long worldTransitions;
    private long resourceReloads;
    private long staticTasksStarted;
    private long staticSectionsScanned;
    private long staticStatesScanned;
    private long acceptedPublications;
    private long stalePublications;
    private long discardedCandidates;
    private long blockOverrides;
    private long sectionUnloads;
    private long dynamicFrames;
    private long dynamicCandidates;
    private long residentSectionCapacityDrops;
    private long sectionLightOverflows;
    private long frameLightOverflows;
    private long directVisibilityCulls;
    private long protectedFrameLightOverflows;
    private long registryFailures;
    private long admissionGeneration;
    private boolean healthy = true;
    private String failureReason = "";

    public AdvancedLightRegistry() {
        this(MAX_RESIDENT_SECTIONS);
    }

    AdvancedLightRegistry(final int maxResidentSections) {
        if (maxResidentSections <= 0 || maxResidentSections > MAX_RESIDENT_SECTIONS) {
            throw new IllegalArgumentException(
                    "Resident section capacity must be within 1.." + MAX_RESIDENT_SECTIONS
            );
        }
        this.maxResidentSections = maxResidentSections;
    }

    public static AdvancedLightRegistry global() {
        return GLOBAL;
    }

    public void observeHook(final Hook hook) {
        if (hook == null) {
            throw new NullPointerException("hook");
        }
        this.observedHookMask.getAndUpdate(mask -> mask | hook.bit);
    }

    public synchronized LightRegistryReadiness readiness() {
        int observed = this.observedHookMask.get();
        return new LightRegistryReadiness(
                CONTRACT_VERSION,
                true,
                this.healthy,
                (observed & REQUIRED_HOOK_MASK) == REQUIRED_HOOK_MASK,
                observed,
                REQUIRED_HOOK_MASK,
                this.registryFailures,
                this.failureReason
        );
    }

    /** Permanently rejects the current renderer instance after a systemic registry failure. */
    public void failClosed(final String reason, final Throwable failure) {
        String checked = reason == null ? "Advanced light registry failure" : reason.trim();
        if (checked.isEmpty()) {
            checked = "Advanced light registry failure";
        }
        if (failure != null) {
            checked += ": " + failure.getClass().getSimpleName();
        }
        final long failedGeneration;
        synchronized (this) {
            this.registryFailures++;
            this.healthy = false;
            this.failureReason = checked;
            this.retireActiveWorld();
            failedGeneration = this.admissionGeneration;
        }
        // Never enter the renderer admission monitor while holding the registry monitor.
        AdvancedLightingRuntime.reportRegistryFailure(failedGeneration, checked);
    }

    synchronized long resetAdmissionHealth() {
        this.admissionGeneration = Math.addExact(this.admissionGeneration, 1L);
        this.healthy = true;
        this.failureReason = "";
        this.registryFailures = 0L;
        this.observedHookMask.set(0);
        return this.admissionGeneration;
    }

    synchronized boolean matchesAdmissionFailure(final long expectedGeneration) {
        return !this.healthy && this.admissionGeneration == expectedGeneration;
    }

    synchronized boolean matchesHealthyAdmission(final long expectedGeneration) {
        return this.healthy && this.admissionGeneration == expectedGeneration;
    }

    /** Opens a new instance token when either world identity or dimension changes. */
    public synchronized LightWorldToken openWorld(
            final Object worldIdentity,
            final String dimensionId
    ) {
        requireWorldIdentity(worldIdentity);
        requireDimension(dimensionId);
        if (this.activeWorld != null
                && this.activeWorld.identity == worldIdentity
                && this.activeWorld.token.dimensionId().equals(dimensionId)) {
            return this.activeWorld.token;
        }
        WorldState next = new WorldState(
                worldIdentity,
                new LightWorldToken(++this.nextWorldGeneration, dimensionId)
        );
        this.restoreRetiredOwners(next);
        this.activeWorld = next;
        this.worldTransitions++;
        return this.activeWorld.token;
    }

    public synchronized void closeWorld(final Object worldIdentity) {
        if (worldIdentity != null
                && this.activeWorld != null
                && this.activeWorld.identity == worldIdentity) {
            this.activeWorld = null;
            this.worldTransitions++;
        }
        if (worldIdentity != null
                && this.retiredWorld != null
                && this.retiredWorld.identity == worldIdentity) {
            this.retiredWorld = null;
        }
    }

    /** Rotates the token so pre-reload worker candidates cannot repopulate stale sections. */
    public synchronized LightWorldToken reloadWorld(
            final Object worldIdentity,
            final String dimensionId
    ) {
        requireWorldIdentity(worldIdentity);
        requireDimension(dimensionId);
        WorldState previous = this.activeWorld;
        WorldState next = new WorldState(
                worldIdentity,
                new LightWorldToken(++this.nextWorldGeneration, dimensionId)
        );
        if (previous != null
                && previous.identity == worldIdentity
                && previous.token.dimensionId().equals(dimensionId)) {
            copyLifecycleOwners(previous, next);
        } else {
            this.restoreRetiredOwners(next);
        }
        this.retiredWorld = null;
        this.activeWorld = next;
        this.resourceReloads++;
        return this.activeWorld.token;
    }

    public synchronized void clear() {
        if (this.activeWorld != null) {
            this.retireActiveWorld();
            this.worldTransitions++;
        }
    }

    public synchronized LightSectionTask beginSectionTask(
            final Object worldIdentity,
            final String dimensionId,
            final long sectionKey
    ) {
        LightWorldToken token = this.openWorld(worldIdentity, dimensionId);
        WorldState world = requireActive(token);
        this.staticTasksStarted++;
        return new LightSectionTask(token, sectionKey, world.epoch, ++this.nextOwnerToken);
    }

    public synchronized void noteStaticScan(final LightSectionCandidate candidate) {
        if (candidate == null) {
            return;
        }
        this.staticSectionsScanned++;
        this.staticStatesScanned += candidate.scannedStateCount();
        this.sectionLightOverflows += candidate.droppedLightCount();
    }

    /** Transfers one accepted output into the registry. Returns false for stale/duplicate owners. */
    public synchronized boolean publishAccepted(final LightSectionCandidate candidate) {
        if (candidate == null || !candidate.claimForPublication()) {
            this.stalePublications++;
            return false;
        }
        LightSectionTask task = candidate.task();
        if (this.activeWorld == null || !this.activeWorld.token.equals(task.world())) {
            this.stalePublications++;
            return false;
        }

        WorldState world = this.activeWorld;
        SectionState current = world.sections.get(task.sectionKey());
        if (current != null && task.ownerToken() <= current.ownerToken) {
            this.stalePublications++;
            return false;
        }
        Map<Integer, AdvancedLight> nextBase = new HashMap<>();
        for (LightSectionCandidate.Entry entry : candidate.entries()) {
            if (nextBase.put(entry.localIndex(), entry.light()) != null) {
                throw new IllegalStateException("Static candidate contains duplicate local indices");
            }
        }
        if (current == null) {
            if (nextBase.isEmpty()) {
                world.epoch++;
                this.acceptedPublications++;
                return true;
            }
            if (world.sections.size() >= this.maxResidentSections) {
                this.residentSectionCapacityDrops++;
                this.sectionLightOverflows += nextBase.size();
                return false;
            }
            current = new SectionState();
            world.sections.put(task.sectionKey(), current);
        }
        current.base = nextBase;
        current.baseEpoch = task.baseEpoch();
        current.ownerToken = task.ownerToken();
        current.overrides.entrySet().removeIf(entry -> entry.getValue().epoch <= task.baseEpoch());
        current.invalidateCompaction();
        if (current.base.isEmpty() && current.overrides.isEmpty()) {
            world.sections.remove(task.sectionKey());
        }
        world.epoch++;
        this.acceptedPublications++;
        return true;
    }

    public synchronized boolean discardCandidate(final LightSectionCandidate candidate) {
        if (candidate != null && candidate.discard()) {
            this.discardedCandidates++;
            return true;
        }
        return false;
    }

    /** Applies one registry-only block-light override without starting static extraction work. */
    public synchronized void recordBlockChange(
            final Object worldIdentity,
            final String dimensionId,
            final long sectionKey,
            final int localIndex,
            final long stableId,
            final LightTemplate replacement
    ) {
        if (localIndex < 0 || localIndex >= StaticLightSectionScanner.BLOCKS_PER_SECTION) {
            throw new IllegalArgumentException("localIndex is outside a 16^3 section");
        }
        if (replacement != null && replacement.kind() != LightSourceKind.BLOCK) {
            throw new IllegalArgumentException("Block overrides require BLOCK templates");
        }
        LightWorldToken token = this.openWorld(worldIdentity, dimensionId);
        WorldState world = requireActive(token);
        long mutationEpoch = ++world.epoch;
        SectionState section = world.sections.get(sectionKey);
        if (section == null) {
            if (world.sections.size() >= this.maxResidentSections) {
                this.residentSectionCapacityDrops++;
                this.blockOverrides++;
                if (replacement == null) {
                    throw new IllegalStateException(
                            "Resident section capacity could not retain a removal tombstone"
                    );
                } else {
                    this.sectionLightOverflows++;
                }
                return;
            }
            section = new SectionState();
            world.sections.put(sectionKey, section);
        }
        AdvancedLight light = replacement == null
                ? null
                : replacement.materialize(stableId, mutationEpoch);
        section.overrides.put(localIndex, new Override(mutationEpoch, light));
        section.invalidateCompaction();
        this.blockOverrides++;
    }

    /** Returns the lifecycle owner currently stored for one successfully published section. */
    public synchronized long currentOwnerToken(
            final LightWorldToken world,
            final long sectionKey
    ) {
        if (world == null || this.activeWorld == null || !this.activeWorld.token.equals(world)) {
            return 0L;
        }
        SectionState section = this.activeWorld.sections.get(sectionKey);
        return section == null ? 0L : section.ownerToken;
    }

    public synchronized boolean removeSectionIfOwner(
            final Object worldIdentity,
            final long sectionKey,
            final long ownerToken
    ) {
        if (this.activeWorld != null && this.activeWorld.identity == worldIdentity) {
            SectionState section = this.activeWorld.sections.get(sectionKey);
            if (section == null || section.ownerToken != ownerToken) {
                return false;
            }
            this.activeWorld.sections.remove(sectionKey);
            this.activeWorld.epoch++;
            this.sectionUnloads++;
            return true;
        }
        if (this.retiredWorld != null && this.retiredWorld.identity == worldIdentity) {
            Long currentOwner = this.retiredWorld.owners.get(sectionKey);
            if (currentOwner == null || currentOwner != ownerToken) {
                return false;
            }
            this.retiredWorld.owners.remove(sectionKey);
            this.sectionUnloads++;
            return true;
        }
        return false;
    }

    public synchronized void publishDynamicFrame(
            final LightWorldToken token,
            final List<AdvancedLight> lights,
            final int offeredCount
    ) {
        WorldState world = requireActive(token);
        if (lights.size() > MAX_DYNAMIC_LIGHTS || offeredCount < lights.size()) {
            throw new IllegalArgumentException("Dynamic frame exceeds its bounded collector contract");
        }
        for (AdvancedLight light : lights) {
            if (light.kind() != LightSourceKind.ENTITY) {
                throw new IllegalArgumentException("Dynamic frame contains a non-entity light");
            }
        }
        List<AdvancedLight> ordered = new ArrayList<>(lights);
        ordered.sort(FrameLightOrder.admissionComparator());
        world.dynamicLights = List.copyOf(ordered);
        world.epoch++;
        this.dynamicFrames++;
        this.dynamicCandidates += offeredCount;
        this.frameLightOverflows += offeredCount - lights.size();
    }

    /** Produces a camera-relative top-K view without a retained native-admission prefix. */
    public synchronized LightFrameSnapshot snapshotForFrame(
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final int maxLights
    ) {
        return this.snapshotForFrame(cameraX, cameraY, cameraZ, maxLights, 0);
    }

    /**
     * Produces a bounded frame whose prefix is retained across ordinary camera motion. Native
     * whole-light admission consumes that camera-independent prefix before the remaining
     * camera-relative candidates.
     */
    public synchronized LightFrameSnapshot snapshotForFrame(
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final int maxLights,
            final int admissionLimit
    ) {
        validateFrameSnapshotRequest(
                cameraX,
                cameraY,
                cameraZ,
                maxLights,
                admissionLimit
        );
        return snapshotForFrameLocked(
                cameraX,
                cameraY,
                cameraZ,
                maxLights,
                admissionLimit,
                null
        );
    }

    /** Produces a direct-light frame whose bounded membership is conservative-view aware. */
    public synchronized LightFrameSnapshot snapshotForFrame(
            final DirectLightFrustum frustum,
            final int maxLights,
            final int admissionLimit
    ) {
        if (frustum == null) {
            throw new NullPointerException("frustum");
        }
        validateFrameSnapshotRequest(
                frustum.cameraX(),
                frustum.cameraY(),
                frustum.cameraZ(),
                maxLights,
                admissionLimit
        );
        return snapshotForFrameLocked(
                frustum.cameraX(),
                frustum.cameraY(),
                frustum.cameraZ(),
                maxLights,
                admissionLimit,
                frustum
        );
    }

    /**
     * Linearizes frame capture against {@link #failClosed(String, Throwable)}. A null result
     * means the caller must resolve a Vanilla generation instead of publishing an empty
     * Advanced-lighting frame.
     */
    public synchronized @Nullable LightFrameSnapshot snapshotForFrameIfHealthy(
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final int maxLights
    ) {
        return this.snapshotForFrameIfHealthy(cameraX, cameraY, cameraZ, maxLights, 0);
    }

    public synchronized @Nullable LightFrameSnapshot snapshotForFrameIfHealthy(
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final int maxLights,
            final int admissionLimit
    ) {
        validateFrameSnapshotRequest(
                cameraX,
                cameraY,
                cameraZ,
                maxLights,
                admissionLimit
        );
        return this.healthy
                ? snapshotForFrameLocked(
                        cameraX,
                        cameraY,
                        cameraZ,
                        maxLights,
                        admissionLimit,
                        null
                )
                : null;
    }

    public synchronized @Nullable LightFrameSnapshot snapshotForFrameIfHealthy(
            final DirectLightFrustum frustum,
            final int maxLights,
            final int admissionLimit
    ) {
        if (frustum == null) {
            throw new NullPointerException("frustum");
        }
        validateFrameSnapshotRequest(
                frustum.cameraX(),
                frustum.cameraY(),
                frustum.cameraZ(),
                maxLights,
                admissionLimit
        );
        return this.healthy
                ? snapshotForFrameLocked(
                        frustum.cameraX(),
                        frustum.cameraY(),
                        frustum.cameraZ(),
                        maxLights,
                        admissionLimit,
                        frustum
                )
                : null;
    }

    private LightFrameSnapshot snapshotForFrameLocked(
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final int maxLights,
            final int admissionLimit,
            final @Nullable DirectLightFrustum frustum
    ) {
        if (this.activeWorld == null) {
            return LightFrameSnapshot.empty();
        }

        WorldState world = this.activeWorld;
        RetainedAdmissionState admission = world.retainedAdmission;
        boolean directVisibility = frustum != null;
        boolean resetAdmission = admission.requiresReset(
                maxLights,
                admissionLimit,
                cameraX,
                cameraY,
                cameraZ
        );
        Set<Long> previousRetainedIds = directVisibility || resetAdmission
                ? Set.of()
                : new HashSet<>(admission.lightIds);
        Map<Long, AdvancedLight> currentRetained = new HashMap<>(
                Math.max(1, previousRetainedIds.size())
        );
        boolean productionFullPool = maxLights == MAX_FRAME_LIGHTS
                && admissionLimit == maxLights;
        // Direct snapshots preserve camera relevance all the way through upload: the cluster
        // builder resolves its local cap by ascending upload index. A camera-independent re-sort
        // here would therefore undo visibility-aware selection inside dense overlapping clusters.
        Comparator<AdvancedLight> materialOrder = directVisibility
                ? FrameLightOrder.directComparator(cameraX, cameraY, cameraZ)
                : !productionFullPool
                        ? FrameLightOrder.comparator(cameraX, cameraY, cameraZ)
                        : FrameLightOrder.admissionComparator();
        Comparator<FrameCandidate> selectionOrder = (left, right) -> {
            boolean leftHeld = left.light.shadowSourceClass()
                    == LocalShadowSourceClass.CAMERA_HELD;
            boolean rightHeld = right.light.shadowSourceClass()
                    == LocalShadowSourceClass.CAMERA_HELD;
            if (leftHeld != rightHeld) {
                return leftHeld ? -1 : 1;
            }
            int visibilityOrder = left.tier.compareTo(right.tier);
            return visibilityOrder != 0
                    ? visibilityOrder
                    : materialOrder.compare(left.light, right.light);
        };
        PriorityQueue<FrameCandidate> selected = new PriorityQueue<>(
                Math.max(1, maxLights),
                selectionOrder.reversed()
        );
        int eligibleTotal = 0;
        int visibilityCulled = 0;
        int protectedCandidates = 0;
        for (SectionState section : world.sections.values()) {
            List<AdvancedLight> staticLights = section.compactedLights(
                    world.token.dimensionId()
            );
            for (AdvancedLight light : staticLights) {
                DirectLightFrustum.Tier tier = frustum == null
                        ? DirectLightFrustum.Tier.INTERSECTING
                        : frustum.classify(light);
                if (directVisibility && tier == DirectLightFrustum.Tier.BACKGROUND) {
                    visibilityCulled++;
                    continue;
                }
                eligibleTotal++;
                if (directVisibility && tier == DirectLightFrustum.Tier.INTERSECTING) {
                    protectedCandidates++;
                }
                offerTopK(
                        selected,
                        new FrameCandidate(light, tier),
                        maxLights,
                        selectionOrder
                );
                if (!directVisibility) {
                    captureRetainedLight(currentRetained, previousRetainedIds, light);
                }
            }
        }
        for (AdvancedLight light : world.dynamicLights) {
            DirectLightFrustum.Tier tier = frustum == null
                    ? DirectLightFrustum.Tier.INTERSECTING
                    : frustum.classify(light);
            if (directVisibility && tier == DirectLightFrustum.Tier.BACKGROUND) {
                visibilityCulled++;
                continue;
            }
            eligibleTotal++;
            if (directVisibility && tier == DirectLightFrustum.Tier.INTERSECTING) {
                protectedCandidates++;
            }
            offerTopK(
                    selected,
                    new FrameCandidate(light, tier),
                    maxLights,
                    selectionOrder
            );
            if (!directVisibility) {
                captureRetainedLight(currentRetained, previousRetainedIds, light);
            }
        }

        List<FrameCandidate> selectedCandidates = new ArrayList<>(selected);
        selectedCandidates.sort(selectionOrder);
        List<AdvancedLight> candidates = selectedCandidates.stream()
                .map(FrameCandidate::light)
                .toList();
        if (directVisibility) {
            // Retention must never let an off-frustum incumbent displace a currently intersecting
            // source after a camera rotation. Keep both the visibility-selected set and its
            // deterministic camera-relevance order through upload so local cluster overflow does
            // not reverse that decision.
            admission.update(
                    maxLights,
                    admissionLimit,
                    cameraX,
                    cameraY,
                    cameraZ,
                    List.of()
            );
            int selectedProtected = (int) selectedCandidates.stream()
                    .filter(candidate -> candidate.tier == DirectLightFrustum.Tier.INTERSECTING)
                    .count();
            return snapshot(
                    world,
                    eligibleTotal,
                    candidates,
                    protectedCandidates - selectedProtected,
                    visibilityCulled
            );
        }
        List<AdvancedLight> retainedPrefix = retainAdmissionPrefix(
                admission,
                resetAdmission,
                currentRetained,
                candidates,
                admissionLimit,
                cameraX,
                cameraY,
                cameraZ,
                materialOrder
        );
        Set<Long> retainedIds = new HashSet<>(Math.max(1, retainedPrefix.size()));
        List<AdvancedLight> ordered = new ArrayList<>(maxLights);
        for (AdvancedLight retained : retainedPrefix) {
            if (retainedIds.add(retained.stableId())) {
                ordered.add(retained);
            }
        }
        for (AdvancedLight candidate : candidates) {
            if (ordered.size() >= maxLights) {
                break;
            }
            if (retainedIds.add(candidate.stableId())) {
                ordered.add(candidate);
            }
        }
        admission.update(
                maxLights,
                admissionLimit,
                cameraX,
                cameraY,
                cameraZ,
                retainedPrefix.stream().map(AdvancedLight::stableId).toList()
        );

        return snapshot(world, eligibleTotal, ordered, 0, 0);
    }

    private LightFrameSnapshot snapshot(
            final WorldState world,
            final int total,
            final List<AdvancedLight> ordered,
            final int protectedDropped,
            final int visibilityCulled
    ) {
        int staticCount = 0;
        for (AdvancedLight light : ordered) {
            if (light.kind() == LightSourceKind.BLOCK) {
                staticCount++;
            }
        }
        int dropped = total - ordered.size();
        this.frameLightOverflows += dropped;
        this.directVisibilityCulls += visibilityCulled;
        this.protectedFrameLightOverflows += protectedDropped;
        return new LightFrameSnapshot(
                LightFrameSnapshot.CURRENT_VERSION,
                world.token,
                world.epoch,
                ordered,
                staticCount,
                ordered.size() - staticCount,
                dropped
        );
    }

    private static void validateFrameSnapshotRequest(
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final int maxLights,
            final int admissionLimit
    ) {
        requireFinite(cameraX, "cameraX");
        requireFinite(cameraY, "cameraY");
        requireFinite(cameraZ, "cameraZ");
        if (maxLights < 0 || maxLights > MAX_FRAME_LIGHTS) {
            throw new IllegalArgumentException("maxLights is outside 0.." + MAX_FRAME_LIGHTS);
        }
        if (admissionLimit < 0 || admissionLimit > maxLights) {
            throw new IllegalArgumentException("admissionLimit is outside 0..maxLights");
        }
    }

    private static void captureRetainedLight(
            final Map<Long, AdvancedLight> currentRetained,
            final Set<Long> previousRetainedIds,
            final AdvancedLight light
    ) {
        if (!previousRetainedIds.contains(light.stableId())) {
            return;
        }
        currentRetained.merge(
                light.stableId(),
                light,
                (left, right) -> FrameLightOrder.admissionComparator().compare(left, right) <= 0
                        ? left
                        : right
        );
    }

    private static List<AdvancedLight> retainAdmissionPrefix(
            final RetainedAdmissionState admission,
            final boolean resetAdmission,
            final Map<Long, AdvancedLight> currentRetained,
            final List<AdvancedLight> candidates,
            final int admissionLimit,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final Comparator<AdvancedLight> frameOrder
    ) {
        if (admissionLimit == 0 || (candidates.isEmpty() && currentRetained.isEmpty())) {
            return List.of();
        }

        List<AdvancedLight> retained = new ArrayList<>(admissionLimit);
        Set<Long> retainedIds = new HashSet<>(Math.max(1, admissionLimit));
        if (!resetAdmission) {
            for (long stableId : admission.lightIds) {
                AdvancedLight current = currentRetained.get(stableId);
                if (current != null && retained.size() < admissionLimit
                        && retainedIds.add(current.stableId())) {
                    retained.add(current);
                }
            }
        }

        List<AdvancedLight> challengers = new ArrayList<>(candidates.size());
        for (AdvancedLight candidate : candidates) {
            if (!retainedIds.contains(candidate.stableId())) {
                challengers.add(candidate);
            }
        }

        int challengerIndex = 0;
        while (retained.size() < admissionLimit && challengerIndex < challengers.size()) {
            AdvancedLight challenger = challengers.get(challengerIndex++);
            if (retainedIds.add(challenger.stableId())) {
                retained.add(challenger);
            }
        }
        while (challengerIndex < challengers.size()) {
            AdvancedLight challenger = challengers.get(challengerIndex++);
            int replacement = replacementIndex(
                    challenger,
                    retained,
                    cameraX,
                    cameraY,
                    cameraZ,
                    frameOrder
            );
            if (replacement < 0) {
                continue;
            }
            AdvancedLight displaced = retained.set(replacement, challenger);
            retainedIds.remove(displaced.stableId());
            retainedIds.add(challenger.stableId());
        }

        retained.sort(FrameLightOrder.admissionComparator());
        return List.copyOf(retained);
    }

    private static int replacementIndex(
            final AdvancedLight challenger,
            final List<AdvancedLight> retained,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final Comparator<AdvancedLight> frameOrder
    ) {
        int replacement = -1;
        for (int index = 0; index < retained.size(); index++) {
            AdvancedLight incumbent = retained.get(index);
            if (!FrameLightOrder.materiallyOutranks(
                    challenger,
                    incumbent,
                    cameraX,
                    cameraY,
                    cameraZ
            )) {
                continue;
            }
            if (replacement < 0
                    || frameOrder.compare(incumbent, retained.get(replacement)) > 0) {
                replacement = index;
            }
        }
        return replacement;
    }

    public synchronized LightRegistryTelemetry telemetry() {
        int sections = this.activeWorld == null ? 0 : this.activeWorld.sections.size();
        int dynamic = this.activeWorld == null ? 0 : this.activeWorld.dynamicLights.size();
        return new LightRegistryTelemetry(
                this.worldTransitions,
                this.resourceReloads,
                this.staticTasksStarted,
                this.staticSectionsScanned,
                this.staticStatesScanned,
                this.acceptedPublications,
                this.stalePublications,
                this.discardedCandidates,
                this.blockOverrides,
                this.sectionUnloads,
                this.dynamicFrames,
                this.dynamicCandidates,
                this.residentSectionCapacityDrops,
                this.sectionLightOverflows,
                this.frameLightOverflows,
                this.directVisibilityCulls,
                this.protectedFrameLightOverflows,
                this.registryFailures,
                sections,
                dynamic
        );
    }

    private static <T> void offerTopK(
            final PriorityQueue<T> selected,
            final T candidate,
            final int maxLights,
            final Comparator<T> frameOrder
    ) {
        if (maxLights == 0) {
            return;
        }
        if (selected.size() < maxLights) {
            selected.add(candidate);
            return;
        }
        T worst = selected.peek();
        if (frameOrder.compare(candidate, worst) < 0) {
            selected.remove();
            selected.add(candidate);
        }
    }

    private record FrameCandidate(
            AdvancedLight light,
            DirectLightFrustum.Tier tier
    ) {
    }

    private void retireActiveWorld() {
        WorldState world = this.activeWorld;
        if (world == null) {
            return;
        }
        LinkedHashMap<Long, Long> owners = new LinkedHashMap<>();
        for (Map.Entry<Long, SectionState> entry : world.sections.entrySet()) {
            if (entry.getValue().ownerToken != 0L) {
                owners.put(entry.getKey(), entry.getValue().ownerToken);
            }
        }
        this.retiredWorld = owners.isEmpty()
                ? null
                : new RetiredWorld(world.identity, world.token.dimensionId(), owners);
        this.activeWorld = null;
    }

    private void restoreRetiredOwners(final WorldState target) {
        RetiredWorld retired = this.retiredWorld;
        this.retiredWorld = null;
        if (retired == null
                || retired.identity != target.identity
                || !retired.dimensionId.equals(target.token.dimensionId())) {
            return;
        }
        for (Map.Entry<Long, Long> entry : retired.owners.entrySet()) {
            SectionState section = new SectionState();
            section.ownerToken = entry.getValue();
            target.sections.put(entry.getKey(), section);
        }
    }

    private static void copyLifecycleOwners(
            final WorldState source,
            final WorldState target
    ) {
        for (Map.Entry<Long, SectionState> entry : source.sections.entrySet()) {
            long ownerToken = entry.getValue().ownerToken;
            if (ownerToken != 0L) {
                SectionState section = new SectionState();
                section.ownerToken = ownerToken;
                target.sections.put(entry.getKey(), section);
            }
        }
    }

    private WorldState requireActive(final LightWorldToken token) {
        if (this.activeWorld == null || !this.activeWorld.token.equals(token)) {
            throw new IllegalStateException("Light world token is no longer active");
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

    private static void requireFinite(final double value, final String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static final class RetainedAdmissionState {
        private boolean initialized;
        private int maxLights;
        private int admissionLimit;
        private double cameraX;
        private double cameraY;
        private double cameraZ;
        private List<Long> lightIds = List.of();

        private boolean requiresReset(
                final int nextMaxLights,
                final int nextAdmissionLimit,
                final double nextCameraX,
                final double nextCameraY,
                final double nextCameraZ
        ) {
            if (!this.initialized
                    || this.maxLights != nextMaxLights
                    || this.admissionLimit != nextAdmissionLimit) {
                return true;
            }
            double dx = nextCameraX - this.cameraX;
            double dy = nextCameraY - this.cameraY;
            double dz = nextCameraZ - this.cameraZ;
            return dx * dx + dy * dy + dz * dz
                    > RETAINED_ADMISSION_CAMERA_CUT_DISTANCE_SQUARED;
        }

        private void update(
                final int nextMaxLights,
                final int nextAdmissionLimit,
                final double nextCameraX,
                final double nextCameraY,
                final double nextCameraZ,
                final List<Long> nextLightIds
        ) {
            this.initialized = true;
            this.maxLights = nextMaxLights;
            this.admissionLimit = nextAdmissionLimit;
            this.cameraX = nextCameraX;
            this.cameraY = nextCameraY;
            this.cameraZ = nextCameraZ;
            this.lightIds = List.copyOf(nextLightIds);
        }
    }

    private static final class WorldState {
        private final Object identity;
        private final LightWorldToken token;
        private final LinkedHashMap<Long, SectionState> sections = new LinkedHashMap<>();
        private final RetainedAdmissionState retainedAdmission = new RetainedAdmissionState();
        private long epoch = 1L;
        private List<AdvancedLight> dynamicLights = List.of();

        private WorldState(final Object identity, final LightWorldToken token) {
            this.identity = identity;
            this.token = token;
        }
    }

    private static final class SectionState {
        private Map<Integer, AdvancedLight> base = Map.of();
        private final Map<Integer, Override> overrides = new HashMap<>();
        private List<AdvancedLight> compactedLights = List.of();
        private boolean compactionDirty = true;
        private long baseEpoch;
        private long ownerToken;

        private List<AdvancedLight> compactedLights(final String dimensionId) {
            if (!this.compactionDirty) {
                return this.compactedLights;
            }
            List<AdvancedLight> effective = new ArrayList<>(
                    this.base.size() + this.overrides.size()
            );
            for (Map.Entry<Integer, AdvancedLight> entry : this.base.entrySet()) {
                Override override = this.overrides.get(entry.getKey());
                AdvancedLight light = override == null ? entry.getValue() : override.light;
                if (light != null) {
                    effective.add(light);
                }
            }
            for (Map.Entry<Integer, Override> entry : this.overrides.entrySet()) {
                if (!this.base.containsKey(entry.getKey()) && entry.getValue().light != null) {
                    effective.add(entry.getValue().light);
                }
            }
            this.compactedLights = DenseBlockLightCompactor.compact(
                    dimensionId,
                    effective
            ).lights();
            this.compactionDirty = false;
            return this.compactedLights;
        }

        private void invalidateCompaction() {
            this.compactedLights = List.of();
            this.compactionDirty = true;
        }
    }

    private static final class RetiredWorld {
        private final Object identity;
        private final String dimensionId;
        private final LinkedHashMap<Long, Long> owners;

        private RetiredWorld(
                final Object identity,
                final String dimensionId,
                final LinkedHashMap<Long, Long> owners
        ) {
            this.identity = identity;
            this.dimensionId = dimensionId;
            this.owners = owners;
        }
    }

    private record Override(long epoch, AdvancedLight light) {
    }
}
