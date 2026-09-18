package com.metallum.client.gi.transport;

import com.metallum.client.gi.field.GiFieldLayout;
import com.metallum.client.gi.semantic.GiSemanticTransportFieldView;

/** Fixed v1 topology and numerical contract for the frozen G4 near cascade. */
public final class GiTransportLayout {
    public static final int ABI_VERSION = 1;
    public static final int LAYOUT_BYTES = 160;
    public static final int HEADER_BYTES = 128;
    public static final int CELL_BYTES = GiSemanticTransportFieldView.CELL_BYTES;
    public static final int STATS_BYTES = 192;

    public static final int EDGE = GiSemanticTransportFieldView.EDGE;
    public static final int CELL_COUNT = GiSemanticTransportFieldView.CELL_COUNT;
    public static final long CELLS_BYTES = GiSemanticTransportFieldView.PAYLOAD_BYTES;
    public static final long JAVA_PERSISTENT_PACKET_BYTES =
            HEADER_BYTES + CELLS_BYTES + STATS_BYTES;
    public static final int CASCADE_COUNT = 1;
    public static final int CELL_SIZE_BLOCKS = GiFieldLayout.cellSizeBlocks(0);
    public static final int ITERATION_COUNT = 1;
    public static final int MAXIMUM_DISTANCE = 8;
    public static final int SIGNED_DIRECTION_COUNT = 26;
    public static final int STENCIL_OFFSET_COUNT = SIGNED_DIRECTION_COUNT * MAXIMUM_DISTANCE;
    public static final int PRIVATE_RESOURCE_COUNT = 5;
    public static final int COMPUTE_PASS_COUNT = 2;
    public static final double AXIS_BIN_SOLID_ANGLE = 0.8054316831613232;
    public static final double EDGE_BIN_SOLID_ANGLE = 0.48157053442524556;
    public static final double CORNER_BIN_SOLID_ANGLE = 0.24436676278603597;
    /**
     * Twice the projected solid-angle quadrature for one unit axis face. The factor two is the
     * non-negative L1 reconstruction kernel: 0.5 * (1 + dot(n, omega)).
     */
    public static final double FORM_WEIGHT_NORMALIZATION = 5.463719420620535;
    /** Retains the accepted full-stencil known-path confidence normalization. */
    public static final double CONFIDENCE_WEIGHT_NORMALIZATION = 29.17999846648958;
    public static final float FP16_ABSOLUTE_TOLERANCE = 1.0F / 1_024.0F;
    public static final float FP16_RELATIVE_TOLERANCE = 1.0F / 512.0F;
    public static final int FLAGS_NONE = 0;

    public static final int RGBA16_FLOAT_PIXEL_FORMAT = 115;
    public static final int R8_UNORM_PIXEL_FORMAT = 10;
    public static final int CAPTURE_RGBA_BYTES = CELL_COUNT * 4 * Short.BYTES;
    public static final int CAPTURE_CONFIDENCE_BYTES = CELL_COUNT;
    public static final long CAPTURE_COMPACT_BYTES =
            (long) CAPTURE_RGBA_BYTES * 4L + CAPTURE_CONFIDENCE_BYTES;

    private GiTransportLayout() {
    }

    public static int cellIndex(final int x, final int y, final int z) {
        if (x < 0 || x >= EDGE || y < 0 || y >= EDGE || z < 0 || z >= EDGE) {
            throw new IndexOutOfBoundsException("G4 transport cell is outside the frozen near cascade");
        }
        return (z * EDGE + y) * EDGE + x;
    }

    /** Recomputes the bounded projected-solid-angle normalization in double precision. */
    public static double computedFormWeightNormalization() {
        double oneFace = AXIS_BIN_SOLID_ANGLE
                + 4.0 * EDGE_BIN_SOLID_ANGLE / Math.sqrt(2.0)
                + 4.0 * CORNER_BIN_SOLID_ANGLE / Math.sqrt(3.0);
        return 2.0 * oneFace;
    }

    /** Recomputes the full 26-direction, eight-distance confidence normalization. */
    public static double computedConfidenceWeightNormalization() {
        double sum = 0.0;
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    double directionWeight = 1.0 / Math.sqrt(x * x + y * y + z * z);
                    for (int distance = 1; distance <= MAXIMUM_DISTANCE; distance++) {
                        sum += directionWeight / ((double) distance * distance);
                    }
                }
            }
        }
        return sum;
    }
}
