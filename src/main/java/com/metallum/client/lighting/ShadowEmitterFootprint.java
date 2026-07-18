package com.metallum.client.lighting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Exact world-block cells represented by one compacted static light. */
public final class ShadowEmitterFootprint {
    public record Block(int x, int y, int z) {
    }

    private static final Comparator<Block> BLOCK_ORDER = Comparator
            .comparingInt(Block::x)
            .thenComparingInt(Block::y)
            .thenComparingInt(Block::z);
    private static final ShadowEmitterFootprint EMPTY = new ShadowEmitterFootprint(List.of());

    private final List<Block> blocks;

    private ShadowEmitterFootprint(final List<Block> blocks) {
        this.blocks = blocks;
    }

    public static ShadowEmitterFootprint empty() {
        return EMPTY;
    }

    public static ShadowEmitterFootprint of(final Iterable<Block> source) {
        Objects.requireNonNull(source, "source");
        ArrayList<Block> ordered = new ArrayList<>();
        for (Block block : source) {
            ordered.add(Objects.requireNonNull(block, "source contains null"));
        }
        ordered.sort(BLOCK_ORDER);
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).equals(ordered.get(index))) {
                throw new IllegalArgumentException("Shadow emitter footprint contains duplicates");
            }
        }
        return ordered.isEmpty()
                ? EMPTY : new ShadowEmitterFootprint(List.copyOf(ordered));
    }

    public List<Block> blocks() {
        return this.blocks;
    }

    public boolean isEmpty() {
        return this.blocks.isEmpty();
    }

    /** Allocation-free binary search used by the asynchronous cache tracer. */
    public boolean contains(final int x, final int y, final int z) {
        int low = 0;
        int high = this.blocks.size() - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            Block candidate = this.blocks.get(middle);
            int order = Integer.compare(x, candidate.x());
            if (order == 0) {
                order = Integer.compare(y, candidate.y());
            }
            if (order == 0) {
                order = Integer.compare(z, candidate.z());
            }
            if (order == 0) {
                return true;
            }
            if (order < 0) {
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return false;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof ShadowEmitterFootprint footprint
                && this.blocks.equals(footprint.blocks);
    }

    @Override
    public int hashCode() {
        return this.blocks.hashCode();
    }

    @Override
    public String toString() {
        return "ShadowEmitterFootprint" + this.blocks;
    }
}
