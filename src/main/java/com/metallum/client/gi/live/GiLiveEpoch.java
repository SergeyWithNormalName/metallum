package com.metallum.client.gi.live;

import com.metallum.client.gi.field.GiFieldLayout;

import java.util.Objects;

/** Complete immutable identity of one G6 live field generation. */
public record GiLiveEpoch(
        long version,
        String dimensionId,
        long worldGeneration,
        long resourceEpoch,
        long materialEpoch,
        long clipmapGeneration,
        long paletteGeneration,
        long contentGeneration,
        long staticSourceEpoch,
        long dynamicSourceEpoch,
        long environmentEpoch,
        Origin cascade0Origin,
        Origin cascade1Origin,
        Origin cascade2Origin
) {
    public record Origin(int x, int y, int z) {
    }

    public GiLiveEpoch {
        if (version <= 0L || worldGeneration <= 0L || resourceEpoch <= 0L
                || materialEpoch <= 0L || clipmapGeneration <= 0L
                || paletteGeneration <= 0L || contentGeneration <= 0L
                || staticSourceEpoch <= 0L
                || dynamicSourceEpoch <= 0L || environmentEpoch <= 0L) {
            throw new IllegalArgumentException("G6 live epoch identities must be positive");
        }
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("G6 live dimension identity must not be blank");
        }
        dimensionId = dimensionId.strip();
        Objects.requireNonNull(cascade0Origin, "cascade0Origin");
        Objects.requireNonNull(cascade1Origin, "cascade1Origin");
        Objects.requireNonNull(cascade2Origin, "cascade2Origin");
        requireAligned(cascade0Origin, 0);
        requireAligned(cascade1Origin, 1);
        requireAligned(cascade2Origin, 2);
    }

    public Origin origin(final int cascade) {
        return switch (cascade) {
            case 0 -> this.cascade0Origin;
            case 1 -> this.cascade1Origin;
            case 2 -> this.cascade2Origin;
            default -> throw new IndexOutOfBoundsException(
                    "G6 cascade outside fixed topology: " + cascade
            );
        };
    }

    public boolean sameWorld(final GiLiveEpoch other) {
        Objects.requireNonNull(other, "other");
        return this.worldGeneration == other.worldGeneration
                && this.dimensionId.equals(other.dimensionId);
    }

    public boolean sameContentAndSources(final GiLiveEpoch other) {
        Objects.requireNonNull(other, "other");
        return sameWorld(other)
                && this.resourceEpoch == other.resourceEpoch
                && this.materialEpoch == other.materialEpoch
                && this.paletteGeneration == other.paletteGeneration
                && this.contentGeneration == other.contentGeneration
                && this.staticSourceEpoch == other.staticSourceEpoch
                && this.dynamicSourceEpoch == other.dynamicSourceEpoch
                && this.environmentEpoch == other.environmentEpoch;
    }

    /**
     * Latest same-grid G2/static input may inherit pending brick ownership. Dynamic,
     * environment, origin and structural transitions remain full scheduler rotations.
     */
    boolean isSameGridInputSuccessorOf(final GiLiveEpoch previous) {
        Objects.requireNonNull(previous, "previous");
        return this.version > previous.version
                && this.dimensionId.equals(previous.dimensionId)
                && this.worldGeneration == previous.worldGeneration
                && this.resourceEpoch == previous.resourceEpoch
                && this.materialEpoch == previous.materialEpoch
                && this.clipmapGeneration == previous.clipmapGeneration
                && this.paletteGeneration == previous.paletteGeneration
                && this.dynamicSourceEpoch == previous.dynamicSourceEpoch
                && this.environmentEpoch == previous.environmentEpoch
                && this.cascade0Origin.equals(previous.cascade0Origin)
                && this.cascade1Origin.equals(previous.cascade1Origin)
                && this.cascade2Origin.equals(previous.cascade2Origin)
                && this.contentGeneration >= previous.contentGeneration
                && this.staticSourceEpoch >= previous.staticSourceEpoch
                && (this.contentGeneration > previous.contentGeneration
                || this.staticSourceEpoch > previous.staticSourceEpoch);
    }

    /** Version is the sole ordering authority; component epochs are immutable identity fields. */
    public void requireStrictlyNewerThan(final GiLiveEpoch previous) {
        Objects.requireNonNull(previous, "previous");
        if (this.version <= previous.version) {
            throw new IllegalArgumentException("G6 live epoch version did not advance");
        }
    }

    private static void requireAligned(final Origin origin, final int cascade) {
        int originSnap = GiFieldLayout.originSnapBlocks(cascade);
        if (Math.floorMod(origin.x, originSnap) != 0
                || Math.floorMod(origin.y, originSnap) != 0
                || Math.floorMod(origin.z, originSnap) != 0) {
            throw new IllegalArgumentException(
                    "G6 cascade origin is not aligned to its world-grid origin quantum"
            );
        }
    }
}
