package com.metallum.client.gi.semantic;

import java.util.Arrays;

/** Immutable complete 16^3 cloned-state input for one accepted Sodium mesh attempt. */
public final class GiSemanticSectionSeed {
    public static final int SECTION_EDGE = 16;
    public static final int BLOCK_COUNT = SECTION_EDGE * SECTION_EDGE * SECTION_EDGE;

    public static final class Builder {
        private final GiSemanticStateSeed[] states = new GiSemanticStateSeed[BLOCK_COUNT];

        private Builder(final boolean acceptedEmpty) {
            for (int index = 0; index < BLOCK_COUNT; index++) {
                this.states[index] = acceptedEmpty
                        ? GiSemanticStateSeed.empty(index)
                        : GiSemanticStateSeed.unknown(index);
            }
        }

        public Builder set(final GiSemanticStateSeed state) {
            if (state == null) {
                throw new NullPointerException("state");
            }
            this.states[state.localIndex()] = state;
            return this;
        }

        public GiSemanticSectionSeed build() {
            return new GiSemanticSectionSeed(this.states);
        }
    }

    private final GiSemanticStateSeed[] states;

    private GiSemanticSectionSeed(final GiSemanticStateSeed[] states) {
        if (states == null || states.length != BLOCK_COUNT) {
            throw new IllegalArgumentException("G2 section seed requires exactly 4096 cloned states");
        }
        this.states = states.clone();
        for (int index = 0; index < BLOCK_COUNT; index++) {
            if (this.states[index] == null || this.states[index].localIndex() != index) {
                throw new IllegalArgumentException("G2 section state index differs at " + index);
            }
        }
    }

    public static Builder unknownBuilder() {
        return new Builder(false);
    }

    public static Builder acceptedEmptyBuilder() {
        return new Builder(true);
    }

    public GiSemanticStateSeed state(final int localIndex) {
        if (localIndex < 0 || localIndex >= BLOCK_COUNT) {
            throw new IndexOutOfBoundsException("G2 block index outside 16^3 section");
        }
        return this.states[localIndex];
    }

    public GiSemanticStateSeed[] states() {
        return this.states.clone();
    }

    @Override
    public String toString() {
        return "GiSemanticSectionSeed[states=" + Arrays.hashCode(this.states) + "]";
    }
}
