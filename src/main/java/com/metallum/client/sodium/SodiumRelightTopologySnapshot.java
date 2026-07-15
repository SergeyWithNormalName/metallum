package com.metallum.client.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Immutable identity snapshot of every block state that can affect a section mesh.
 *
 * <p>The captured volume is the section's 16-cubed body plus a one-block halo on
 * every side. Matching deliberately uses reference identity rather than
 * {@link Object#equals(Object)}: Minecraft block states are canonical values,
 * and any changed canonical state must invalidate a relight-only replay.</p>
 */
public final class SodiumRelightTopologySnapshot {
    public static final int SECTION_EDGE_LENGTH = 16;
    public static final int HALO_RADIUS = 1;
    public static final int EDGE_LENGTH = SECTION_EDGE_LENGTH + HALO_RADIUS * 2;
    public static final int STATE_COUNT = EDGE_LENGTH * EDGE_LENGTH * EDGE_LENGTH;

    private static final long ESTIMATED_BASE_BYTES = 64L;
    private static final long ESTIMATED_REFERENCE_BYTES = Long.BYTES;
    private static final long ESTIMATED_RETAINED_BYTES = Math.addExact(
            ESTIMATED_BASE_BYTES,
            Math.multiplyExact((long) STATE_COUNT, ESTIMATED_REFERENCE_BYTES)
    );

    private final int sectionOriginX;
    private final int sectionOriginY;
    private final int sectionOriginZ;
    // Production capture stores only BlockState references; Object keeps the test seam bootstrap-free.
    private final Object[] blockStateIdentities;

    private SodiumRelightTopologySnapshot(
            final int sectionOriginX,
            final int sectionOriginY,
            final int sectionOriginZ,
            final Object[] blockStateIdentities
    ) {
        if (blockStateIdentities.length != STATE_COUNT) {
            throw new IllegalArgumentException(
                    "topology state count " + blockStateIdentities.length + " != " + STATE_COUNT
            );
        }
        this.sectionOriginX = sectionOriginX;
        this.sectionOriginY = sectionOriginY;
        this.sectionOriginZ = sectionOriginZ;
        // captureIdentities exclusively owns this freshly allocated, fully validated array.
        this.blockStateIdentities = blockStateIdentities;
    }

    public static SodiumRelightTopologySnapshot capture(
            final LevelSlice slice,
            final RenderSection section
    ) {
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(section, "section");
        if (section.isDisposed()) {
            throw new IllegalStateException("cannot capture topology for a disposed section");
        }
        int originX = section.getOriginX();
        int originY = section.getOriginY();
        int originZ = section.getOriginZ();
        SodiumRelightTopologySnapshot snapshot = captureIdentities(
                originX,
                originY,
                originZ,
                (blockX, blockY, blockZ) -> {
                    BlockState state = slice.getBlockState(blockX, blockY, blockZ);
                    return state;
                }
        );
        if (section.isDisposed()
                || section.getOriginX() != originX
                || section.getOriginY() != originY
                || section.getOriginZ() != originZ) {
            throw new IllegalStateException("render section changed during topology capture");
        }
        return snapshot;
    }

    /**
     * Returns whether the same live section still has exactly the captured block-state identities.
     */
    public boolean matches(final LevelSlice slice, final RenderSection section) {
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(section, "section");
        if (section.isDisposed()) {
            return false;
        }
        int originX = section.getOriginX();
        int originY = section.getOriginY();
        int originZ = section.getOriginZ();
        return this.matchesIdentities(originX, originY, originZ, slice::getBlockState)
                && !section.isDisposed()
                && section.getOriginX() == originX
                && section.getOriginY() == originY
                && section.getOriginZ() == originZ;
    }

    public long estimatedRetainedBytes() {
        return ESTIMATED_RETAINED_BYTES;
    }

    /* Package-private identity seam keeps exhaustive tests independent of a live ClientLevel. */
    static SodiumRelightTopologySnapshot captureIdentities(
            final int sectionOriginX,
            final int sectionOriginY,
            final int sectionOriginZ,
            final IdentityReader reader
    ) {
        Objects.requireNonNull(reader, "reader");
        Object[] identities = new Object[STATE_COUNT];
        int startX = Math.subtractExact(sectionOriginX, HALO_RADIUS);
        int startY = Math.subtractExact(sectionOriginY, HALO_RADIUS);
        int startZ = Math.subtractExact(sectionOriginZ, HALO_RADIUS);
        int index = 0;
        for (int y = 0; y < EDGE_LENGTH; y++) {
            int blockY = Math.addExact(startY, y);
            for (int z = 0; z < EDGE_LENGTH; z++) {
                int blockZ = Math.addExact(startZ, z);
                for (int x = 0; x < EDGE_LENGTH; x++) {
                    int blockX = Math.addExact(startX, x);
                    identities[index++] = Objects.requireNonNull(
                            reader.get(blockX, blockY, blockZ),
                            "block state identity"
                    );
                }
            }
        }
        return new SodiumRelightTopologySnapshot(
                sectionOriginX,
                sectionOriginY,
                sectionOriginZ,
                identities
        );
    }

    boolean matchesIdentities(
            final int sectionOriginX,
            final int sectionOriginY,
            final int sectionOriginZ,
            final IdentityReader reader
    ) {
        Objects.requireNonNull(reader, "reader");
        if (sectionOriginX != this.sectionOriginX
                || sectionOriginY != this.sectionOriginY
                || sectionOriginZ != this.sectionOriginZ) {
            return false;
        }

        int startX = Math.subtractExact(sectionOriginX, HALO_RADIUS);
        int startY = Math.subtractExact(sectionOriginY, HALO_RADIUS);
        int startZ = Math.subtractExact(sectionOriginZ, HALO_RADIUS);
        int index = 0;
        for (int y = 0; y < EDGE_LENGTH; y++) {
            int blockY = Math.addExact(startY, y);
            for (int z = 0; z < EDGE_LENGTH; z++) {
                int blockZ = Math.addExact(startZ, z);
                for (int x = 0; x < EDGE_LENGTH; x++) {
                    int blockX = Math.addExact(startX, x);
                    if (reader.get(blockX, blockY, blockZ)
                            != this.blockStateIdentities[index++]) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @FunctionalInterface
    interface IdentityReader {
        Object get(int blockX, int blockY, int blockZ);
    }
}
