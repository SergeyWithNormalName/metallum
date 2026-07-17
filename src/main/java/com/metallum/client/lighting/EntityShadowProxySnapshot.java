package com.metallum.client.lighting;

import java.util.List;
import java.util.Objects;

/** Immutable, bounded and deterministically ordered proxy view for one extracted entity frame. */
public record EntityShadowProxySnapshot(
        int version,
        LightWorldToken world,
        long epoch,
        List<EntityShadowProxy> proxies,
        int offeredCount
) {
    public static final int CURRENT_VERSION = 1;

    public EntityShadowProxySnapshot {
        if (version != CURRENT_VERSION || epoch < 0L || offeredCount < 0) {
            throw new IllegalArgumentException("Invalid L6 proxy snapshot header");
        }
        Objects.requireNonNull(world, "world");
        proxies = List.copyOf(Objects.requireNonNull(proxies, "proxies"));
        if (offeredCount < proxies.size()) {
            throw new IllegalArgumentException("Proxy snapshot offered count is below its retained count");
        }
    }

    public int droppedCount() {
        return offeredCount - proxies.size();
    }
}
