package com.metallum.client.metal.render;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.FrameLightOrder;
import com.metallum.client.lighting.LocalShadowSourceClass;
import com.metallum.client.renderer.LocalVoxelShadowLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Generation-local motion detection, promotion and hysteretic hero-slot admission. */
final class DynamicShadowAdmission {
    static final double MOVEMENT_THRESHOLD = 1.0 / 64.0;
    static final int STOP_HOLD_FRAMES = 12;
    private static final double MOVEMENT_THRESHOLD_SQUARED =
            MOVEMENT_THRESHOLD * MOVEMENT_THRESHOLD;
    private static final Result EMPTY_RESULT = new Result(
            List.of(), List.of(), 0, 0, false, 0, 0
    );

    enum Phase {
        STATIC,
        MOVING,
        PROMOTING,
        CAMERA_HELD
    }

    record Candidate(
            AdvancedLight light,
            boolean cacheCovered,
            boolean exactStaticReady
    ) {
        Candidate {
            Objects.requireNonNull(light, "light");
        }
    }

    record TrackedCandidate(Candidate candidate, Phase phase, boolean staticBuildAllowed) {
        TrackedCandidate {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(phase, "phase");
        }

        boolean needsDynamicPage() {
            return this.phase != Phase.STATIC;
        }
    }

    record Selected(TrackedCandidate candidate, int heroSlot) {
        Selected {
            Objects.requireNonNull(candidate, "candidate");
            if (heroSlot < 0 || heroSlot >= LocalVoxelShadowLayout.MAX_DYNAMIC_SHADOW_LIGHTS) {
                throw new IllegalArgumentException("Dynamic shadow hero slot exceeds hard cap");
            }
        }
    }

    record Result(
            List<TrackedCandidate> tracked,
            List<Selected> selected,
            int candidates,
            int dropped,
            boolean heldAdmitted,
            int staticToDynamicTransitions,
            int dynamicToStaticTransitions
    ) {
        Result {
            tracked = List.copyOf(tracked);
            selected = List.copyOf(selected);
            if (candidates < 0 || dropped < 0 || dropped != candidates - selected.size()
                    || staticToDynamicTransitions < 0 || dynamicToStaticTransitions < 0) {
                throw new IllegalArgumentException("Invalid dynamic-shadow admission accounting");
            }
        }

        Selected selected(final long stableId) {
            for (Selected value : this.selected) {
                if (value.candidate().candidate().light().stableId() == stableId) {
                    return value;
                }
            }
            return null;
        }

        TrackedCandidate tracked(final long stableId) {
            for (TrackedCandidate value : this.tracked) {
                if (value.candidate().light().stableId() == stableId) {
                    return value;
                }
            }
            return null;
        }
    }

    private final Map<Long, MotionState> motion = new HashMap<>();
    private final long[] retainedSlots = new long[LocalVoxelShadowLayout.MAX_DYNAMIC_SHADOW_LIGHTS];

    static boolean isStaticOnly(final List<AdvancedLight> lights) {
        Objects.requireNonNull(lights, "lights");
        for (int index = 0; index < lights.size(); index++) {
            AdvancedLight light = lights.get(index);
            if (Objects.requireNonNull(light, "lights contains null").shadowSourceClass()
                    != LocalShadowSourceClass.STATIC_CACHE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Preserves the state transition performed by {@link #select} for an all-static frame
     * without materializing per-light admission records. The following dynamic frame must
     * start with neither stale motion history nor retained hero slots.
     */
    Result resetForStaticOnlyFrame() {
        this.motion.clear();
        Arrays.fill(this.retainedSlots, 0L);
        return EMPTY_RESULT;
    }

    static Result emptyResult() {
        return EMPTY_RESULT;
    }

    Result select(
            final List<Candidate> input,
            final LocalVoxelShadowLayout.DynamicShadowBudget budget,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(budget, "budget");
        requireFinite(cameraX, "cameraX");
        requireFinite(cameraY, "cameraY");
        requireFinite(cameraZ, "cameraZ");

        List<Candidate> candidates = coalesceCandidates(input, cameraX, cameraY, cameraZ);
        Set<Long> dynamicSeen = new HashSet<>();
        List<TrackedCandidate> tracked = new ArrayList<>(candidates.size());
        int staticToDynamic = 0;
        int dynamicToStatic = 0;
        for (Candidate candidate : candidates) {
            AdvancedLight light = candidate.light();
            if (light.shadowSourceClass() == LocalShadowSourceClass.STATIC_CACHE) {
                tracked.add(new TrackedCandidate(candidate, Phase.STATIC, true));
                continue;
            }
            dynamicSeen.add(light.stableId());
            MotionState state = this.motion.computeIfAbsent(
                    light.stableId(), ignored -> new MotionState()
            );
            Phase previous = state.phase;
            Phase next = observe(state, candidate);
            if (previous == Phase.STATIC && next != Phase.STATIC) {
                staticToDynamic++;
            } else if (previous != Phase.STATIC && next == Phase.STATIC) {
                dynamicToStatic++;
            }
            state.phase = next;
            tracked.add(new TrackedCandidate(
                    candidate,
                    next,
                    next == Phase.STATIC || next == Phase.PROMOTING
            ));
        }
        this.motion.keySet().removeIf(stableId -> !dynamicSeen.contains(stableId));

        List<TrackedCandidate> eligible = tracked.stream()
                .filter(TrackedCandidate::needsDynamicPage)
                .sorted(candidateOrder(cameraX, cameraY, cameraZ))
                .toList();
        List<Selected> selected = admit(
                eligible, budget.heroSlots(), cameraX, cameraY, cameraZ
        );
        Set<Long> selectedIds = new HashSet<>();
        boolean heldAdmitted = false;
        for (Selected value : selected) {
            long stableId = value.candidate().candidate().light().stableId();
            selectedIds.add(stableId);
            this.retainedSlots[value.heroSlot()] = stableId;
            heldAdmitted |= value.candidate().phase() == Phase.CAMERA_HELD;
        }
        for (int slot = 0; slot < budget.heroSlots(); slot++) {
            if (!selectedIds.contains(this.retainedSlots[slot])) {
                this.retainedSlots[slot] = 0L;
            }
        }
        for (int slot = budget.heroSlots(); slot < this.retainedSlots.length; slot++) {
            this.retainedSlots[slot] = 0L;
        }
        return new Result(
                tracked,
                selected,
                eligible.size(),
                eligible.size() - selected.size(),
                heldAdmitted,
                staticToDynamic,
                dynamicToStatic
        );
    }

    /**
     * A held-item proxy and its entity body can legitimately share one L3 stable ID for one
     * frame. Collapse that representation boundary before motion state or hero-slot accounting:
     * both structures are keyed by stable ID and must never see two candidates for one key.
     */
    private static List<Candidate> coalesceCandidates(
            final List<Candidate> input,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        Comparator<AdvancedLight> relevance = FrameLightOrder.directComparator(
                cameraX, cameraY, cameraZ
        );
        Map<Long, Candidate> byStableId = new LinkedHashMap<>();
        for (Candidate candidate : input) {
            Objects.requireNonNull(candidate, "input contains null");
            long stableId = candidate.light().stableId();
            Candidate current = byStableId.get(stableId);
            byStableId.put(
                    stableId,
                    current == null ? candidate : mergeCandidates(current, candidate, relevance)
            );
        }
        return new ArrayList<>(byStableId.values());
    }

    private static Candidate mergeCandidates(
            final Candidate left,
            final Candidate right,
            final Comparator<AdvancedLight> relevance
    ) {
        Candidate preferred = relevance.compare(left.light(), right.light()) <= 0 ? left : right;
        if (!left.light().equals(right.light())) {
            // Coverage/readiness describe one exact position/radius. Never transfer the body
            // representation's cache state to a preferred camera-held representation that only
            // shares its stable identity.
            return preferred;
        }
        return new Candidate(
                preferred.light(),
                left.cacheCovered() || right.cacheCovered(),
                left.exactStaticReady() || right.exactStaticReady()
        );
    }

    private List<Selected> admit(
            final List<TrackedCandidate> eligible,
            final int heroSlots,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        List<Selected> selected = new ArrayList<>(heroSlots);
        Set<Long> used = new HashSet<>();
        TrackedCandidate held = firstWithPhase(eligible, Phase.CAMERA_HELD, used);
        int firstRemoteSlot = 0;
        if (held != null) {
            selected.add(new Selected(held, 0));
            used.add(held.candidate().light().stableId());
            firstRemoteSlot = 1;
        }
        for (int slot = firstRemoteSlot; slot < heroSlots; slot++) {
            TrackedCandidate best = firstUnusedRemote(eligible, used);
            TrackedCandidate retained = findByStableId(
                    eligible, this.retainedSlots[slot], used
            );
            TrackedCandidate chosen;
            if (retained == null || best == null || retained == best) {
                chosen = retained != null ? retained : best;
            } else if (materiallyOutranks(
                    best, retained, cameraX, cameraY, cameraZ)) {
                chosen = best;
            } else {
                chosen = retained;
            }
            if (chosen == null) {
                break;
            }
            selected.add(new Selected(chosen, slot));
            used.add(chosen.candidate().light().stableId());
        }
        return List.copyOf(selected);
    }

    private static Phase observe(final MotionState state, final Candidate candidate) {
        AdvancedLight light = candidate.light();
        if (light.shadowSourceClass() == LocalShadowSourceClass.STATIC_CACHE) {
            state.stopHoldFrames = 0;
            state.awaitingStatic = false;
            return Phase.STATIC;
        }
        if (light.shadowSourceClass() == LocalShadowSourceClass.CAMERA_HELD) {
            state.stopHoldFrames = STOP_HOLD_FRAMES;
            state.awaitingStatic = false;
            rememberPosition(state, light);
            return Phase.CAMERA_HELD;
        }

        boolean moved = !state.initialized || distanceSquared(state, light)
                > MOVEMENT_THRESHOLD_SQUARED;
        if (moved) {
            // Keep an accumulation anchor instead of replacing it for every sub-threshold
            // frame. A continuously drifting entity must eventually exceed 1/64 block even
            // when each individual render-frame delta is smaller than that threshold.
            rememberPosition(state, light);
            state.stopHoldFrames = STOP_HOLD_FRAMES;
            state.awaitingStatic = true;
            return Phase.MOVING;
        }
        if (state.stopHoldFrames > 0) {
            state.stopHoldFrames--;
            state.awaitingStatic = true;
            return Phase.MOVING;
        }
        if (state.awaitingStatic && !candidate.exactStaticReady()) {
            return Phase.PROMOTING;
        }
        state.awaitingStatic = false;
        return Phase.STATIC;
    }

    private static void rememberPosition(
            final MotionState state,
            final AdvancedLight light
    ) {
        state.x = light.x();
        state.y = light.y();
        state.z = light.z();
        state.initialized = true;
    }

    private static Comparator<TrackedCandidate> candidateOrder(
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        Comparator<AdvancedLight> lightOrder = FrameLightOrder.comparator(
                cameraX, cameraY, cameraZ
        );
        return (left, right) -> {
            int held = Boolean.compare(
                    right.phase() == Phase.CAMERA_HELD,
                    left.phase() == Phase.CAMERA_HELD
            );
            if (held != 0) {
                return held;
            }
            int coverage = Boolean.compare(
                    right.candidate().cacheCovered(),
                    left.candidate().cacheCovered()
            );
            return coverage != 0
                    ? coverage
                    : lightOrder.compare(left.candidate().light(), right.candidate().light());
        };
    }

    private static boolean materiallyOutranks(
            final TrackedCandidate challenger,
            final TrackedCandidate retained,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        if (challenger.candidate().cacheCovered() != retained.candidate().cacheCovered()) {
            return challenger.candidate().cacheCovered();
        }
        return FrameLightOrder.materiallyOutranks(
                challenger.candidate().light(),
                retained.candidate().light(),
                cameraX, cameraY, cameraZ
        );
    }

    private static TrackedCandidate firstWithPhase(
            final List<TrackedCandidate> values,
            final Phase phase,
            final Set<Long> excluded
    ) {
        for (TrackedCandidate value : values) {
            long id = value.candidate().light().stableId();
            if (value.phase() == phase && !excluded.contains(id)) {
                return value;
            }
        }
        return null;
    }

    private static TrackedCandidate firstUnusedRemote(
            final List<TrackedCandidate> values,
            final Set<Long> used
    ) {
        for (TrackedCandidate value : values) {
            if (value.phase() != Phase.CAMERA_HELD
                    && !used.contains(value.candidate().light().stableId())) {
                return value;
            }
        }
        return null;
    }

    private static TrackedCandidate findByStableId(
            final List<TrackedCandidate> values,
            final long stableId,
            final Set<Long> used
    ) {
        if (stableId == 0L || used.contains(stableId)) {
            return null;
        }
        for (TrackedCandidate value : values) {
            if (value.phase() != Phase.CAMERA_HELD
                    && value.candidate().light().stableId() == stableId) {
                return value;
            }
        }
        return null;
    }

    private static double distanceSquared(final MotionState state, final AdvancedLight light) {
        double dx = light.x() - state.x;
        double dy = light.y() - state.y;
        double dz = light.z() - state.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void requireFinite(final double value, final String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static final class MotionState {
        private double x;
        private double y;
        private double z;
        private int stopHoldFrames;
        private boolean initialized;
        private boolean awaitingStatic;
        private Phase phase = Phase.STATIC;
    }
}
