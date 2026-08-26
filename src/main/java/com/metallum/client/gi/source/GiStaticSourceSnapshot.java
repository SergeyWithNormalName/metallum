package com.metallum.client.gi.source;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.lighting.LocalShadowSourceClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable static-only L3 query result before view admission. */
public record GiStaticSourceSnapshot(
        LightWorldToken world,
        long registryEpoch,
        WorldAabb query,
        List<AdvancedLight> sources,
        int droppedSources
) {
    public record WorldAabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public WorldAabb {
            requireFinite(minX, "minX");
            requireFinite(minY, "minY");
            requireFinite(minZ, "minZ");
            requireFinite(maxX, "maxX");
            requireFinite(maxY, "maxY");
            requireFinite(maxZ, "maxZ");
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("G3 static-source AABB is inverted");
            }
        }

        public boolean intersectsSphere(final AdvancedLight source) {
            double dx = clamp(source.x(), this.minX, this.maxX) - source.x();
            double dy = clamp(source.y(), this.minY, this.maxY) - source.y();
            double dz = clamp(source.z(), this.minZ, this.maxZ) - source.z();
            return dx * dx + dy * dy + dz * dz <= (double) source.radius() * source.radius();
        }
    }

    public GiStaticSourceSnapshot {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(query, "query");
        if (registryEpoch <= 0L || droppedSources < 0) {
            throw new IllegalArgumentException("G3 static-source epochs and counters must be positive/non-negative");
        }
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.size() > GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK) {
            throw new IllegalArgumentException("G3 static-source query exceeded its fixed cap");
        }
        AdvancedLight prior = null;
        for (AdvancedLight source : sources) {
            requireStatic(source);
            if (!query.intersectsSphere(source)) {
                throw new IllegalArgumentException("G3 static source lies outside its query AABB");
            }
            if (prior != null && AdvancedLight.PRIORITY_ORDER.compare(prior, source) > 0) {
                throw new IllegalArgumentException("G3 static sources are not in deterministic order");
            }
            prior = source;
        }
    }

    public static GiStaticSourceSnapshot select(
            final LightWorldToken world, final long registryEpoch, final WorldAabb query,
            final List<AdvancedLight> offered, final int maxSources
    ) {
        Objects.requireNonNull(offered, "offered");
        if (maxSources < 0 || maxSources > GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK) {
            throw new IllegalArgumentException("G3 static-source cap is outside its fixed contract");
        }
        List<AdvancedLight> selected = new ArrayList<>();
        for (AdvancedLight source : offered) {
            if (isStaticSource(source) && query.intersectsSphere(source)) {
                selected.add(source);
            }
        }
        selected.sort(AdvancedLight.PRIORITY_ORDER);
        int dropped = Math.max(0, selected.size() - maxSources);
        if (selected.size() > maxSources) {
            selected.subList(maxSources, selected.size()).clear();
        }
        return new GiStaticSourceSnapshot(world, registryEpoch, query, selected, dropped);
    }

    public static boolean isStaticSource(final AdvancedLight source) {
        return source != null && source.kind() == LightSourceKind.BLOCK
                && source.shadowSourceClass() == LocalShadowSourceClass.STATIC_CACHE;
    }

    private static void requireStatic(final AdvancedLight source) {
        if (!isStaticSource(source)) {
            throw new IllegalArgumentException("G3 accepts only static block sources");
        }
    }

    private static double clamp(final double value, final double low, final double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static void requireFinite(final double value, final String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("G3 static-source " + name + " must be finite");
        }
    }
}
