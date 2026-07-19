package com.metallum.client.hdr;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Carries in-world block-item emission through the otherwise legacy item lightmap coordinates. */
public final class HeldItemEmission {
    static final int LIGHT_COORD_MARKER_BASE = 0x100;
    private static final int LIGHT_COORD_LOW_WORD_MASK = 0xffff;
    private static final int MAX_EMISSION = 15;

    private HeldItemEmission() {
    }

    public static int surfaceEmission(
            final ItemStack stack,
            final ItemDisplayContext displayContext
    ) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return surfaceEmission(stack.getItem(), displayContext);
    }

    public static int surfaceEmission(
            final Item item,
            final ItemDisplayContext displayContext
    ) {
        if (!supportsSurfaceEmission(displayContext) || !(item instanceof BlockItem blockItem)) {
            return 0;
        }
        return surfaceEmission(
                blockItem.getBlock().defaultBlockState().getLightEmission(),
                displayContext
        );
    }

    static int surfaceEmission(
            final int blockEmission,
            final ItemDisplayContext displayContext
    ) {
        return supportsSurfaceEmission(displayContext)
                ? Math.clamp(blockEmission, 0, MAX_EMISSION)
                : 0;
    }

    /**
     * Uses the first normally-unused lightmap coordinate above 255 as a shader-visible marker.
     * Vanilla sampling clamps it to full block light, while the METALLUM item shader also
     * reconstructs the authored surface-emission floor from the encoded 0..15 strength.
     * The marker is admitted only for held and dropped-item rendering contexts.
     */
    public static int encodeLightCoords(final int lightCoords, final int emission) {
        int bounded = Math.clamp(emission, 0, MAX_EMISSION);
        if (bounded == 0) {
            return lightCoords;
        }
        return (lightCoords & ~LIGHT_COORD_LOW_WORD_MASK) | LIGHT_COORD_MARKER_BASE | bounded;
    }

    static int encodedEmission(final int lightCoords) {
        int lowWord = lightCoords & LIGHT_COORD_LOW_WORD_MASK;
        int emission = lowWord - LIGHT_COORD_MARKER_BASE;
        return emission >= 1 && emission <= MAX_EMISSION ? emission : 0;
    }

    private static boolean supportsSurfaceEmission(final ItemDisplayContext displayContext) {
        return displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || displayContext == ItemDisplayContext.GROUND;
    }
}
