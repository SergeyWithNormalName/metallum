package com.metallum.client.lighting;

import com.metallum.client.renderer.LocalVoxelShadowLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Objects;

/** Selects the nearest useful rigid proxies without an unbounded entity-to-shadow work list. */
public final class BoundedEntityShadowProxyCollector {
    private final LightWorldToken world;
    private final int capacity;
    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final Comparator<EntityShadowProxy> admissionOrder;
    private final PriorityQueue<EntityShadowProxy> worstFirst;
    private int offered;

    public BoundedEntityShadowProxyCollector(
            final LightWorldToken world,
            final int capacity,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        this.world = Objects.requireNonNull(world, "world");
        if (capacity < 1 || capacity > LocalVoxelShadowLayout.MAX_ENTITY_PROXIES
                || !Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) {
            throw new IllegalArgumentException("Invalid L6 proxy collector bounds");
        }
        this.capacity = capacity;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.admissionOrder = (left, right) -> {
            int distanceOrder = Double.compare(
                    left.distanceSquaredTo(cameraX, cameraY, cameraZ),
                    right.distanceSquaredTo(cameraX, cameraY, cameraZ)
            );
            if (distanceOrder != 0) {
                return distanceOrder;
            }
            int volumeOrder = Double.compare(right.volume(), left.volume());
            return volumeOrder != 0 ? volumeOrder : Long.compareUnsigned(left.stableId(), right.stableId());
        };
        this.worstFirst = new PriorityQueue<>(capacity, admissionOrder.reversed());
    }

    public LightWorldToken world() {
        return world;
    }

    public void offer(final EntityShadowProxy proxy) {
        if (proxy == null) {
            return;
        }
        offered++;
        if (worstFirst.size() < capacity) {
            worstFirst.add(proxy);
            return;
        }
        EntityShadowProxy worst = worstFirst.peek();
        if (admissionOrder.compare(proxy, worst) < 0) {
            worstFirst.remove();
            worstFirst.add(proxy);
        }
    }

    public void offerAll(final List<EntityShadowProxy> proxies) {
        if (proxies == null || proxies.isEmpty()) {
            return;
        }
        for (int index = 0; index < proxies.size(); index++) {
            offer(proxies.get(index));
        }
    }

    public int offered() {
        return offered;
    }

    public List<EntityShadowProxy> finish() {
        List<EntityShadowProxy> result = new ArrayList<>(worstFirst);
        result.sort(admissionOrder);
        return List.copyOf(result);
    }
}
