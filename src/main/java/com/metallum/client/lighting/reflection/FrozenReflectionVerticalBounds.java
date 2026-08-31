package com.metallum.client.lighting.reflection;

import net.minecraft.world.level.LevelHeightAccessor;

import java.util.Objects;

/** Vertical storage guard for the finite frozen-reflection preload domain. */
public final class FrozenReflectionVerticalBounds {
    private FrozenReflectionVerticalBounds() {
    }

    /**
     * Sections outside the dimension's build-height storage are authoritative empty space.
     * Sodium's section-array access is only valid inside this closed section range.
     */
    public static boolean isOutsideBuildHeight(
            final LevelHeightAccessor level,
            final int sectionY
    ) {
        Objects.requireNonNull(level, "level");
        return sectionY < level.getMinSectionY() || sectionY > level.getMaxSectionY();
    }
}
