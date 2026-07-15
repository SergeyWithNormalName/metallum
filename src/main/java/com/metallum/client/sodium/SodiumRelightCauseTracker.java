package com.metallum.client.sodium;

import org.jspecify.annotations.Nullable;

/**
 * Synchronous light-dirty scope used to classify a rebuild on the render thread.
 *
 * <p>No mutable cause map is read by Sodium workers. The render thread transfers
 * one immutable {@link SodiumRelightTaskStamp} to each created task instead.</p>
 */
public final class SodiumRelightCauseTracker {
    private static final ThreadLocal<ScopeState> CURRENT_SCOPE = new ThreadLocal<>();

    private SodiumRelightCauseTracker() {
    }

    public static Scope openExact(final int sectionX, final int sectionY, final int sectionZ) {
        return open(sectionX, sectionY, sectionZ, 0);
    }

    public static Scope openNeighbors(final int sectionX, final int sectionY, final int sectionZ) {
        return open(sectionX, sectionY, sectionZ, 1);
    }

    public static SodiumRelightRebuildCause classify(
            final int sectionX,
            final int sectionY,
            final int sectionZ
    ) {
        ScopeState state = CURRENT_SCOPE.get();
        if (state == null || !state.valid || !state.contains(sectionX, sectionY, sectionZ)) {
            return SodiumRelightRebuildCause.GEOMETRY_OR_UNKNOWN;
        }
        return SodiumRelightRebuildCause.LIGHT_ONLY;
    }

    /** Test/shutdown helper; a leaked scope becomes fail-closed unknown work. */
    public static void clear() {
        ScopeState state = CURRENT_SCOPE.get();
        while (state != null) {
            state.valid = false;
            state = state.parent;
        }
        CURRENT_SCOPE.remove();
    }

    private static Scope open(
            final int sectionX,
            final int sectionY,
            final int sectionZ,
            final int radius
    ) {
        ScopeState state = new ScopeState(
                CURRENT_SCOPE.get(),
                sectionX,
                sectionY,
                sectionZ,
                radius
        );
        CURRENT_SCOPE.set(state);
        return new Scope(state);
    }

    public static final class Scope implements AutoCloseable {
        @Nullable
        private ScopeState state;

        private Scope(final ScopeState state) {
            this.state = state;
        }

        @Override
        public void close() {
            ScopeState closing = this.state;
            this.state = null;
            if (closing == null) {
                clear();
                return;
            }
            ScopeState current = CURRENT_SCOPE.get();
            if (current != closing) {
                closing.valid = false;
                clear();
                return;
            }
            closing.valid = false;
            if (closing.parent == null) {
                CURRENT_SCOPE.remove();
            } else {
                CURRENT_SCOPE.set(closing.parent);
            }
        }
    }

    private static final class ScopeState {
        @Nullable
        private final ScopeState parent;
        private final int sectionX;
        private final int sectionY;
        private final int sectionZ;
        private final int radius;
        private boolean valid = true;

        private ScopeState(
                @Nullable final ScopeState parent,
                final int sectionX,
                final int sectionY,
                final int sectionZ,
                final int radius
        ) {
            this.parent = parent;
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
            this.radius = radius;
        }

        private boolean contains(final int x, final int y, final int z) {
            return Math.abs((long) x - this.sectionX) <= this.radius
                    && Math.abs((long) y - this.sectionY) <= this.radius
                    && Math.abs((long) z - this.sectionZ) <= this.radius;
        }
    }
}
