package com.metallum.client.lighting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/** Reused once per LevelExtractor frame; bounds entity lights without losing visible influence. */
public final class BoundedDynamicLightCollector {
    private final LightWorldToken world;
    private final int capacity;
    private final PriorityQueue<Candidate> worstFirst;
    private final Comparator<AdvancedLight> admissionOrder;
    private final Predicate<AdvancedLight> intersectsView;
    private final Comparator<Candidate> selectionOrder;
    private final Map<Long, Candidate> retainedByStableId;
    private int offered;

    public BoundedDynamicLightCollector(final LightWorldToken world, final int capacity) {
        this(world, capacity, light -> true);
    }

    public BoundedDynamicLightCollector(
            final LightWorldToken world,
            final int capacity,
            final Predicate<AdvancedLight> intersectsView
    ) {
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (intersectsView == null) {
            throw new NullPointerException("intersectsView");
        }
        if (capacity <= 0 || capacity > AdvancedLightRegistry.MAX_DYNAMIC_LIGHTS) {
            throw new IllegalArgumentException("Dynamic capacity is outside the registry bound");
        }
        this.world = world;
        this.capacity = capacity;
        this.admissionOrder = FrameLightOrder.admissionComparator();
        this.intersectsView = intersectsView;
        this.selectionOrder = (left, right) -> {
            int visibilityOrder = Boolean.compare(right.intersectsView, left.intersectsView);
            return visibilityOrder != 0
                    ? visibilityOrder
                    : this.admissionOrder.compare(left.light, right.light);
        };
        this.worstFirst = new PriorityQueue<>(capacity, this.selectionOrder.reversed());
        this.retainedByStableId = new HashMap<>(capacity);
    }

    public LightWorldToken world() {
        return this.world;
    }

    public void offer(final AdvancedLight light) {
        if (light == null) {
            return;
        }
        if (light.kind() != LightSourceKind.ENTITY) {
            throw new IllegalArgumentException("Dynamic collectors accept only ENTITY lights");
        }
        this.offered++;
        Candidate candidate = new Candidate(light, this.intersectsView.test(light));
        Candidate existing = this.retainedByStableId.get(light.stableId());
        if (existing != null) {
            if (this.selectionOrder.compare(candidate, existing) < 0) {
                if (!this.worstFirst.remove(existing)) {
                    throw new IllegalStateException(
                            "Dynamic light stable-ID index diverged from its bounded queue"
                    );
                }
                this.worstFirst.add(candidate);
                this.retainedByStableId.put(light.stableId(), candidate);
            }
            return;
        }
        if (this.worstFirst.size() < this.capacity) {
            this.worstFirst.add(candidate);
            this.retainedByStableId.put(light.stableId(), candidate);
            return;
        }
        Candidate worst = this.worstFirst.peek();
        if (this.selectionOrder.compare(candidate, worst) < 0) {
            this.worstFirst.remove();
            this.retainedByStableId.remove(worst.light.stableId(), worst);
            this.worstFirst.add(candidate);
            this.retainedByStableId.put(light.stableId(), candidate);
        }
    }

    public int offered() {
        return this.offered;
    }

    public int dropped() {
        return this.offered - this.worstFirst.size();
    }

    public List<AdvancedLight> finish() {
        List<AdvancedLight> result = this.worstFirst.stream()
                .map(Candidate::light)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        result.sort(this.admissionOrder);
        return List.copyOf(result);
    }

    private record Candidate(AdvancedLight light, boolean intersectsView) {
    }
}
