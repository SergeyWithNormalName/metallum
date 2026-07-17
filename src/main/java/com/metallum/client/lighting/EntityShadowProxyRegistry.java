package com.metallum.client.lighting;

import java.util.List;
import java.util.Objects;

/** Lifecycle-owned L6 proxy snapshot registry; stale extract/reload frames are simply discarded. */
public final class EntityShadowProxyRegistry {
    private static final EntityShadowProxyRegistry GLOBAL = new EntityShadowProxyRegistry();

    private Object identity;
    private LightWorldToken world;
    private long epoch;
    private EntityShadowProxySnapshot snapshot;

    public static EntityShadowProxyRegistry global() {
        return GLOBAL;
    }

    public synchronized void openWorld(final Object worldIdentity, final LightWorldToken nextWorld) {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(nextWorld, "nextWorld");
        if (identity != worldIdentity || !nextWorld.equals(world)) {
            identity = worldIdentity;
            world = nextWorld;
            snapshot = null;
            epoch++;
        }
    }

    public synchronized void closeWorld(final Object worldIdentity) {
        if (worldIdentity != null && identity == worldIdentity) {
            identity = null;
            world = null;
            snapshot = null;
            epoch++;
        }
    }

    public synchronized void publish(
            final LightWorldToken token,
            final List<EntityShadowProxy> proxies,
            final int offeredCount
    ) {
        if (world == null || !world.equals(token)) {
            throw new IllegalStateException("Stale L6 proxy frame");
        }
        snapshot = new EntityShadowProxySnapshot(
                EntityShadowProxySnapshot.CURRENT_VERSION, world, ++epoch, proxies, offeredCount
        );
    }

    public synchronized EntityShadowProxySnapshot snapshot(final LightWorldToken token) {
        return world != null && world.equals(token) ? snapshot : null;
    }

    /** L6 is optional: extraction errors invalidate only this snapshot and never L3 admission. */
    public synchronized void failOpen(final LightWorldToken token) {
        if (world != null && world.equals(token)) {
            snapshot = null;
            epoch++;
        }
    }
}
