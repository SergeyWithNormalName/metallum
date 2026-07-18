package com.metallum.client.lighting.shader;

import com.metallum.client.renderer.LocalVoxelShadowAtlasLayout;
import com.metallum.client.renderer.LocalVoxelShadowLayout;

import java.util.Arrays;

/** Fixed fragment-stage L6 bindings and the 256-byte local-shadow parameter packet. */
public final class VoxelShadowBindingAbi {
    public static final int VERSION = LocalVoxelShadowLayout.ABI_VERSION;
    public static final int VISIBILITY_CACHE_BUFFER_SLOT = 14;
    public static final int PROXY_BUFFER_SLOT = 15;
    public static final int PARAMS_BUFFER_SLOT = 16;
    public static final int OCCUPANCY_TEXTURE_0_SLOT = 17;
    public static final int OCCUPANCY_TEXTURE_1_SLOT = 18;
    public static final int OCCUPANCY_TEXTURE_2_SLOT = 19;
    public static final int OPTICAL_TEXTURE_0_SLOT = 20;
    public static final int OPTICAL_TEXTURE_1_SLOT = 21;
    public static final int OPTICAL_TEXTURE_2_SLOT = 22;
    public static final int METADATA_BUFFER_0_SLOT = 23;
    public static final int METADATA_BUFFER_1_SLOT = 24;
    public static final int METADATA_BUFFER_2_SLOT = 25;
    /** Resident-atlas reference table; buffer 13 is free and Metal buffer indices stop at 30. */
    public static final int SHADOW_REF_BUFFER_SLOT = 13;

    public static final int PARAMS_BYTES = LocalVoxelShadowLayout.PARAMS_BYTES;
    public static final int PROXY_STRIDE_BYTES = LocalVoxelShadowLayout.PROXY_STRIDE_BYTES;
    public static final int LEVEL_COUNT = 3;
    public static final int LEVEL_STRIDE_BYTES = 32;
    public static final int SHADOW_REF_DESCRIPTOR_STRIDE_BYTES =
            LocalVoxelShadowAtlasLayout.DESCRIPTOR_STRIDE_BYTES;
    public static final int SHADOW_REF_DESCRIPTOR_STATE_OFFSET =
            LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_OFFSET;
    public static final int SHADOW_REF_DESCRIPTOR_ATLAS_OFFSET_LO_OFFSET =
            LocalVoxelShadowAtlasLayout.DESCRIPTOR_ATLAS_OFFSET_LO_OFFSET;
    public static final int SHADOW_REF_DESCRIPTOR_ATLAS_OFFSET_HI_OFFSET =
            LocalVoxelShadowAtlasLayout.DESCRIPTOR_ATLAS_OFFSET_HI_OFFSET;
    public static final int SHADOW_REF_DESCRIPTOR_PAGE_EDGE_OFFSET =
            LocalVoxelShadowAtlasLayout.DESCRIPTOR_PAGE_EDGE_OFFSET;
    /** Valid direct contribution that intentionally has no resident shadow page. */
    public static final int SHADOW_REF_STATE_APPROXIMATE_DIRECT =
            LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_APPROXIMATE_DIRECT;
    /**
     * @deprecated State zero never invokes DDA. Use {@link #SHADOW_REF_STATE_APPROXIMATE_DIRECT}.
     */
    @Deprecated(forRemoval = false)
    public static final int SHADOW_REF_STATE_DDA_FALLBACK = SHADOW_REF_STATE_APPROXIMATE_DIRECT;
    public static final int SHADOW_REF_STATE_READY =
            LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_READY;
    public static final int SHADOW_REF_STATE_STALE_RETAINED =
            LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_STALE_RETAINED;
    public static final int SHADOW_REF_STATE_BUILDING =
            LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_BUILDING;

    // Exact 256-byte Metal packet. Absolute generation values are split into two u32 words.
    public static final int WORLD_FROM_VIEW_MATRIX_OFFSET = 0;
    public static final int CAMERA_BLOCK_AND_FLAGS_OFFSET = 64;
    /** Number of eight-byte atlas hits; replaces the retired legacy shadow-light index field. */
    public static final int ATLAS_HIT_CAPACITY_OFFSET = CAMERA_BLOCK_AND_FLAGS_OFFSET + 12;
    public static final int CAMERA_FRACTION_AND_MIN_TRANSMITTANCE_OFFSET = 80;
    public static final int CAPS_OFFSET = 96;
    public static final int PROXY_AND_FRAME_OFFSET = 112;
    public static final int LEVELS_OFFSET = 128;
    public static final int CONTRACT_OFFSET = 224;
    public static final int WORLD_AND_FLAGS_OFFSET = 240;

    public static final int CAPS_VERSION_OFFSET = CAPS_OFFSET;
    public static final int CAPS_LEVEL_COUNT_OFFSET = CAPS_OFFSET + 4;
    public static final int CAPS_MAX_STEPS_OFFSET = CAPS_OFFSET + 8;
    public static final int CAPS_SHADOWED_LIGHT_CAP_OFFSET = CAPS_OFFSET + 12;
    public static final int PROXY_COUNT_OFFSET = PROXY_AND_FRAME_OFFSET;
    public static final int PROXY_CAP_OFFSET = PROXY_AND_FRAME_OFFSET + 4;
    public static final int FRAME_LO_OFFSET = PROXY_AND_FRAME_OFFSET + 8;
    public static final int FRAME_HI_OFFSET = PROXY_AND_FRAME_OFFSET + 12;

    // Each compact descriptor is absolute world-block minimum origin xyz plus span, then
    // subdivision/logical edge/brick dimension/brick block edge. The shader reconstructs the
    // absolute receiver from worldFromView plus camera block/fraction before addressing it.
    public static final int LEVEL_ORIGIN_AND_SPAN_OFFSET = 0;
    public static final int LEVEL_LAYOUT_OFFSET = 16;
    public static final int LEVEL_SUBDIVISION_OFFSET = 16;
    public static final int LEVEL_LOGICAL_EDGE_OFFSET = 20;
    public static final int LEVEL_BRICK_DIMENSION_OFFSET = 24;
    public static final int LEVEL_BRICK_BLOCK_EDGE_OFFSET = 28;

    public static final int LIGHTING_GENERATION_LO_OFFSET = CONTRACT_OFFSET;
    public static final int LIGHTING_GENERATION_HI_OFFSET = CONTRACT_OFFSET + 4;
    public static final int CLIPMAP_GENERATION_LO_OFFSET = CONTRACT_OFFSET + 8;
    public static final int CLIPMAP_GENERATION_HI_OFFSET = CONTRACT_OFFSET + 12;
    public static final int WORLD_GENERATION_LO_OFFSET = WORLD_AND_FLAGS_OFFSET;
    public static final int WORLD_GENERATION_HI_OFFSET = WORLD_AND_FLAGS_OFFSET + 4;
    public static final int ACTIVE_OFFSET = WORLD_AND_FLAGS_OFFSET + 8;
    public static final int WORLD_RESERVED_OFFSET = WORLD_AND_FLAGS_OFFSET + 12;

    private static final int[] OCCUPANCY_TEXTURE_SLOTS = {17, 18, 19};
    private static final int[] OPTICAL_TEXTURE_SLOTS = {20, 21, 22};
    private static final int[] METADATA_BUFFER_SLOTS = {23, 24, 25};

    private VoxelShadowBindingAbi() {
    }

    public static int levelOffset(final int level) {
        if (level < 0 || level >= LEVEL_COUNT) {
            throw new IndexOutOfBoundsException("L6 level is outside the fixed ABI: " + level);
        }
        return LEVELS_OFFSET + level * LEVEL_STRIDE_BYTES;
    }

    public static int[] occupancyTextureSlots() {
        return OCCUPANCY_TEXTURE_SLOTS.clone();
    }

    public static int[] opticalTextureSlots() {
        return OPTICAL_TEXTURE_SLOTS.clone();
    }

    public static int[] metadataBufferSlots() {
        return METADATA_BUFFER_SLOTS.clone();
    }

    public static boolean ownsFragmentSlot(final int slot) {
        return slot == VISIBILITY_CACHE_BUFFER_SLOT
                || slot == PROXY_BUFFER_SLOT || slot == PARAMS_BUFFER_SLOT
                || slot == SHADOW_REF_BUFFER_SLOT
                || Arrays.stream(OCCUPANCY_TEXTURE_SLOTS).anyMatch(candidate -> candidate == slot)
                || Arrays.stream(OPTICAL_TEXTURE_SLOTS).anyMatch(candidate -> candidate == slot)
                || Arrays.stream(METADATA_BUFFER_SLOTS).anyMatch(candidate -> candidate == slot);
    }
}
