package com.metallum.client.gi.source;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.FrameLightOrder;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.lighting.LocalShadowSourceClass;
import com.metallum.client.lighting.ShadowEmitterFootprint;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, camera-independent G6 live-source publication. */
public record GiDynamicSourceSnapshot(
        int version,
        GiDynamicSourceEpoch epoch,
        long publishedAtWorldTick,
        int capacity,
        long expiryTicks,
        List<AdvancedLight> sources,
        long sourceHash
) {
    public static final int CURRENT_VERSION = 1;
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * Total camera-independent order. The tail makes same-ID coalescing deterministic even when
     * two observations differ only in chromaticity or another non-admission field.
     */
    static final Comparator<AdvancedLight> SOURCE_ORDER = (left, right) -> {
        int order = FrameLightOrder.admissionComparator().compare(left, right);
        if (order != 0) {
            return order;
        }
        order = compareUnsignedFloat(left.radius(), right.radius());
        if (order != 0) {
            return order;
        }
        order = compareUnsignedFloat(left.red(), right.red());
        if (order != 0) {
            return order;
        }
        order = compareUnsignedFloat(left.green(), right.green());
        if (order != 0) {
            return order;
        }
        order = compareUnsignedFloat(left.blue(), right.blue());
        if (order != 0) {
            return order;
        }
        order = compareUnsignedFloat(left.intensity(), right.intensity());
        if (order != 0) {
            return order;
        }
        order = Boolean.compare(left.denseCellEligible(), right.denseCellEligible());
        if (order != 0) {
            return order;
        }
        order = left.shadowSourceClass().compareTo(right.shadowSourceClass());
        if (order != 0) {
            return order;
        }
        return compareFootprints(left.shadowEmitterFootprint(), right.shadowEmitterFootprint());
    };

    public GiDynamicSourceSnapshot {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported G6 dynamic-source snapshot version");
        }
        Objects.requireNonNull(epoch, "epoch");
        if (publishedAtWorldTick < 0L) {
            throw new IllegalArgumentException("G6 publication tick must be non-negative");
        }
        if (capacity <= 0 || capacity > GiDynamicSourceCollector.MAX_CAPACITY
                || expiryTicks <= 0L) {
            throw new IllegalArgumentException("Invalid G6 dynamic-source bounds");
        }
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.size() > capacity) {
            throw new IllegalArgumentException("G6 dynamic-source snapshot exceeded its cap");
        }
        Set<Long> stableIds = new HashSet<>(sources.size());
        AdvancedLight previous = null;
        for (AdvancedLight source : sources) {
            requireLiveSource(epoch.world(), source);
            if (!stableIds.add(source.stableId())) {
                throw new IllegalArgumentException(
                        "G6 dynamic-source snapshot contains a duplicate stable ID"
                );
            }
            if (previous != null && SOURCE_ORDER.compare(previous, source) > 0) {
                throw new IllegalArgumentException(
                        "G6 dynamic sources are not in deterministic world-space order"
                );
            }
            previous = source;
        }
        if (sourceHash != computeSourceHash(epoch.world(), sources)) {
            throw new IllegalArgumentException("G6 dynamic-source hash does not match its sources");
        }
    }

    public static long computeSourceHash(
            final LightWorldToken world,
            final List<AdvancedLight> sources
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(sources, "sources");
        long hash = mixLong(FNV_OFFSET_BASIS, world.generation());
        for (int index = 0; index < world.dimensionId().length(); index++) {
            hash = mixLong(hash, world.dimensionId().charAt(index));
        }
        hash = mixLong(hash, sources.size());
        for (AdvancedLight source : sources) {
            Objects.requireNonNull(source, "sources contains null");
            hash = mixLong(hash, source.stableId());
            hash = mixLong(hash, source.generation());
            hash = mixLong(hash, source.kind().ordinal());
            hash = mixLong(hash, Double.doubleToRawLongBits(source.x()));
            hash = mixLong(hash, Double.doubleToRawLongBits(source.y()));
            hash = mixLong(hash, Double.doubleToRawLongBits(source.z()));
            hash = mixLong(hash, Integer.toUnsignedLong(Float.floatToRawIntBits(source.radius())));
            hash = mixLong(hash, Integer.toUnsignedLong(Float.floatToRawIntBits(source.red())));
            hash = mixLong(hash, Integer.toUnsignedLong(Float.floatToRawIntBits(source.green())));
            hash = mixLong(hash, Integer.toUnsignedLong(Float.floatToRawIntBits(source.blue())));
            hash = mixLong(hash, Integer.toUnsignedLong(Float.floatToRawIntBits(source.intensity())));
            hash = mixLong(hash, Integer.toUnsignedLong(source.priority()));
            hash = mixLong(hash, source.denseCellEligible() ? 1L : 0L);
            hash = mixLong(hash, source.shadowSourceClass().ordinal());
            List<ShadowEmitterFootprint.Block> blocks = source.shadowEmitterFootprint().blocks();
            hash = mixLong(hash, blocks.size());
            for (ShadowEmitterFootprint.Block block : blocks) {
                hash = mixLong(hash, Integer.toUnsignedLong(block.x()));
                hash = mixLong(hash, Integer.toUnsignedLong(block.y()));
                hash = mixLong(hash, Integer.toUnsignedLong(block.z()));
            }
        }
        return hash;
    }

    static void requireLiveSource(
            final LightWorldToken world,
            final AdvancedLight source
    ) {
        Objects.requireNonNull(source, "sources contains null");
        if (source.kind() != LightSourceKind.ENTITY) {
            throw new IllegalArgumentException("G6 dynamic collector accepts only live sources");
        }
        if (source.generation() != world.generation()) {
            throw new IllegalArgumentException("G6 dynamic source belongs to another world");
        }
        if (source.shadowSourceClass() == LocalShadowSourceClass.STATIC_CACHE
                || !source.shadowEmitterFootprint().isEmpty()) {
            throw new IllegalArgumentException("G6 dynamic source carries static-light state");
        }
    }

    private static int compareUnsignedFloat(final float left, final float right) {
        return Integer.compareUnsigned(
                Float.floatToRawIntBits(left),
                Float.floatToRawIntBits(right)
        );
    }

    private static int compareFootprints(
            final ShadowEmitterFootprint left,
            final ShadowEmitterFootprint right
    ) {
        List<ShadowEmitterFootprint.Block> leftBlocks = left.blocks();
        List<ShadowEmitterFootprint.Block> rightBlocks = right.blocks();
        int limit = Math.min(leftBlocks.size(), rightBlocks.size());
        for (int index = 0; index < limit; index++) {
            ShadowEmitterFootprint.Block leftBlock = leftBlocks.get(index);
            ShadowEmitterFootprint.Block rightBlock = rightBlocks.get(index);
            int order = Integer.compare(leftBlock.x(), rightBlock.x());
            if (order == 0) {
                order = Integer.compare(leftBlock.y(), rightBlock.y());
            }
            if (order == 0) {
                order = Integer.compare(leftBlock.z(), rightBlock.z());
            }
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(leftBlocks.size(), rightBlocks.size());
    }

    private static long mixLong(long hash, final long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash ^= value >>> shift & 0xffL;
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
