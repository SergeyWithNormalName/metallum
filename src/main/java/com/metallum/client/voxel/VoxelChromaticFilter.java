package com.metallum.client.voxel;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Compact scene-linear colour filters used by L5/L6 direct local-light transmission.
 *
 * <p>One four-bit palette ID is stored per world block, packed two IDs per byte. ID zero is
 * deliberately neutral, so a cleared L5 resource and every legacy/non-coloured material retain
 * white transmission. The remaining IDs match vanilla {@link DyeColor#getId()} values. The
 * table is calibrated in scene-linear space; it must not receive raw sRGB texture bytes.</p>
 */
public final class VoxelChromaticFilter {
    public static final int NEUTRAL_ID = DyeColor.WHITE.getId();
    public static final int MAX_ID = DyeColor.BLACK.getId();
    public static final int PACKED_BITS_PER_VALUE = 4;
    public static final int PACKED_MASK = (1 << PACKED_BITS_PER_VALUE) - 1;
    /** High byte marks a valid packed L6 RGB hit while preserving the eight-byte hit stride. */
    public static final int PACKED_RGB_VALID_MASK = 0xff00_0000;
    public static final int VISIBLE_PACKED_RGB = 0xffff_ffff;

    /*
     * The order is the stable vanilla dye ID order. Values are intentionally scene-linear
     * filters, never brighter than one; the scalar optical transmittance remains responsible
     * for geometry/material loss. Black is nearly absorbing rather than a neutral hue.
     */
    private static final float[][] LINEAR_FILTERS = {
            {1.000f, 1.000f, 1.000f}, // white / neutral
            {1.000f, 0.250f, 0.030f}, // orange
            {1.000f, 0.080f, 0.680f}, // magenta
            {0.100f, 0.500f, 1.000f}, // light blue
            {1.000f, 0.850f, 0.050f}, // yellow
            {0.250f, 1.000f, 0.040f}, // lime
            {1.000f, 0.250f, 0.400f}, // pink
            {0.230f, 0.250f, 0.250f}, // gray
            {0.600f, 0.600f, 0.580f}, // light gray
            {0.030f, 0.650f, 0.650f}, // cyan
            {0.320f, 0.040f, 0.600f}, // purple
            {0.040f, 0.070f, 0.650f}, // blue
            {0.200f, 0.050f, 0.015f}, // brown
            {0.080f, 0.350f, 0.010f}, // green
            {1.000f, 0.040f, 0.025f}, // red
            {0.005f, 0.005f, 0.006f}  // black
    };

    private VoxelChromaticFilter() {
    }

    public static int packedBytesFor(final int valueCount) {
        if (valueCount < 0) {
            throw new IllegalArgumentException("Chromatic value count must be non-negative");
        }
        return (valueCount + 1) >>> 1;
    }

    public static byte[] neutralPackedValues(final int valueCount) {
        return new byte[packedBytesFor(valueCount)];
    }

    public static int idFor(final BlockState state, final VoxelMaterialClass materialClass) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(materialClass, "materialClass");
        if (state.getBlock() instanceof StainedGlassBlock glass) {
            return dyeId(glass.getColor());
        }
        if (state.getBlock() instanceof StainedGlassPaneBlock pane) {
            return dyeId(pane.getColor());
        }
        return switch (materialClass) {
            case FOLIAGE -> DyeColor.GREEN.getId();
            case WATER -> DyeColor.LIGHT_BLUE.getId();
            case AIR, OPAQUE, CUTOUT, GLASS, TRANSLUCENT, UNKNOWN_CONSERVATIVE -> NEUTRAL_ID;
        };
    }

    public static int packedId(final byte[] packedValues, final int valueIndex) {
        Objects.requireNonNull(packedValues, "packedValues");
        if (valueIndex < 0 || valueIndex >= packedValues.length * 2) {
            throw new IndexOutOfBoundsException("Chromatic palette index is outside its packed payload");
        }
        int packed = Byte.toUnsignedInt(packedValues[valueIndex >>> 1]);
        return (packed >>> ((valueIndex & 1) * PACKED_BITS_PER_VALUE)) & PACKED_MASK;
    }

    public static void putPackedId(
            final byte[] packedValues,
            final int valueIndex,
            final int paletteId
    ) {
        Objects.requireNonNull(packedValues, "packedValues");
        requireId(paletteId);
        if (valueIndex < 0 || valueIndex >= packedValues.length * 2) {
            throw new IndexOutOfBoundsException("Chromatic palette index is outside its packed payload");
        }
        int byteIndex = valueIndex >>> 1;
        int shift = (valueIndex & 1) * PACKED_BITS_PER_VALUE;
        int existing = Byte.toUnsignedInt(packedValues[byteIndex]);
        packedValues[byteIndex] = (byte) ((existing & ~(PACKED_MASK << shift))
                | (paletteId << shift));
    }

    public static float red(final int paletteId) {
        return filter(paletteId, 0);
    }

    public static float green(final int paletteId) {
        return filter(paletteId, 1);
    }

    public static float blue(final int paletteId) {
        return filter(paletteId, 2);
    }

    public static int packRgbUnorm8(final float red, final float green, final float blue) {
        return PACKED_RGB_VALID_MASK
                | quantize(red)
                | quantize(green) << 8
                | quantize(blue) << 16;
    }

    public static boolean isValidPackedRgb(final int packed) {
        return (packed & PACKED_RGB_VALID_MASK) == PACKED_RGB_VALID_MASK;
    }

    public static float unpackRed(final int packed) {
        return (packed & 0xff) * (1.0f / 255.0f);
    }

    public static float unpackGreen(final int packed) {
        return ((packed >>> 8) & 0xff) * (1.0f / 255.0f);
    }

    public static float unpackBlue(final int packed) {
        return ((packed >>> 16) & 0xff) * (1.0f / 255.0f);
    }

    private static int dyeId(final DyeColor color) {
        Objects.requireNonNull(color, "color");
        int id = color.getId();
        requireId(id);
        return id;
    }

    private static float filter(final int paletteId, final int component) {
        requireId(paletteId);
        return LINEAR_FILTERS[paletteId][component];
    }

    private static int quantize(final float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Chromatic transmittance must be finite");
        }
        return Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 255.0f);
    }

    private static void requireId(final int paletteId) {
        if (paletteId < NEUTRAL_ID || paletteId > MAX_ID) {
            throw new IllegalArgumentException("Unknown chromatic palette ID: " + paletteId);
        }
    }
}
