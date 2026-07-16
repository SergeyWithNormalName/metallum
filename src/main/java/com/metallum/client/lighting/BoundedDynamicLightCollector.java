package com.metallum.client.lighting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Reused once per LevelExtractor frame; accepts entities only from Minecraft's visible loop. */
public final class BoundedDynamicLightCollector {
    private final LightWorldToken world;
    private final int capacity;
    private final PriorityQueue<AdvancedLight> worstFirst;
    private final Comparator<AdvancedLight> admissionOrder;
    private int offered;

    public BoundedDynamicLightCollector(final LightWorldToken world, final int capacity) {
        if (world == null) {
            throw new NullPointerException("world");
        }
        if (capacity <= 0 || capacity > AdvancedLightRegistry.MAX_DYNAMIC_LIGHTS) {
            throw new IllegalArgumentException("Dynamic capacity is outside the registry bound");
        }
        this.world = world;
        this.capacity = capacity;
        this.admissionOrder = FrameLightOrder.admissionComparator();
        this.worstFirst = new PriorityQueue<>(capacity, this.admissionOrder.reversed());
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
        if (this.worstFirst.size() < this.capacity) {
            this.worstFirst.add(light);
            return;
        }
        AdvancedLight worst = this.worstFirst.peek();
        if (this.admissionOrder.compare(light, worst) < 0) {
            this.worstFirst.remove();
            this.worstFirst.add(light);
        }
    }

    public int offered() {
        return this.offered;
    }

    public int dropped() {
        return this.offered - this.worstFirst.size();
    }

    public List<AdvancedLight> finish() {
        List<AdvancedLight> result = new ArrayList<>(this.worstFirst);
        result.sort(this.admissionOrder);
        return List.copyOf(result);
    }
}
