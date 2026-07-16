package com.metallum.client.lighting.shader;

import com.metallum.client.renderer.AdvancedLightingLayout;

import java.util.Arrays;

/** Fixed Metal fragment-buffer contract for L3 clustered direct lighting. */
public final class AdvancedLightingBindingAbi {
    public static final int VERSION = AdvancedLightingLayout.ABI_VERSION;

    public static final int PARAMS_SLOT = 27;
    public static final int LIGHTS_SLOT = 28;
    public static final int CLUSTER_HEADERS_SLOT = 29;
    public static final int CLUSTER_INDICES_SLOT = 30;

    public static final int PARAMS_BYTES = AdvancedLightingLayout.LIGHTING_PARAMS_BYTES;
    public static final int GPU_LIGHT_STRIDE = AdvancedLightingLayout.GPU_LIGHT_STRIDE;
    public static final int CLUSTER_HEADER_STRIDE = AdvancedLightingLayout.CLUSTER_HEADER_STRIDE;
    public static final int CLUSTER_INDEX_STRIDE = AdvancedLightingLayout.LIGHT_INDEX_STRIDE;

    public static final int PARAMS_VIEW_ROTATION_OFFSET = 0;
    public static final int PARAMS_PROJECTION_OFFSET = 64;
    public static final int PARAMS_GRID_AND_LIGHT_COUNT_OFFSET = 128;
    public static final int PARAMS_EXTENT_AND_CLUSTER_CAP_OFFSET = 144;
    public static final int PARAMS_DEPTH_OFFSET = 160;
    public static final int PARAMS_FRAME_ID_AND_GENERATION_OFFSET = 176;
    public static final int PARAMS_CAPACITIES_AND_FLAGS_OFFSET = 192;
    public static final int PARAMS_RESERVED0_OFFSET = 208;
    public static final int PARAMS_RESERVED1_OFFSET = 224;
    public static final int PARAMS_RESERVED2_OFFSET = 240;

    public static final int LIGHT_POSITION_RADIUS_OFFSET = 0;
    public static final int LIGHT_LINEAR_COLOR_INTENSITY_OFFSET = 16;
    public static final int LIGHT_METADATA_OFFSET = 32;

    private static final int[] FRAGMENT_SLOTS = {
            PARAMS_SLOT,
            LIGHTS_SLOT,
            CLUSTER_HEADERS_SLOT,
            CLUSTER_INDICES_SLOT
    };

    private AdvancedLightingBindingAbi() {
    }

    public static int[] fragmentSlots() {
        return FRAGMENT_SLOTS.clone();
    }

    public static boolean ownsFragmentSlot(final int slot) {
        return Arrays.stream(FRAGMENT_SLOTS).anyMatch(candidate -> candidate == slot);
    }

    /** Log2 depth coefficients shared by cluster build and fragment lookup. */
    public record DepthCoefficients(float scale, float bias) {
        public DepthCoefficients {
            if (!Float.isFinite(scale) || scale <= 0.0f || !Float.isFinite(bias)) {
                throw new IllegalArgumentException("Invalid logarithmic depth coefficients");
            }
        }
    }

    public static DepthCoefficients depthCoefficients(
            final float nearPlane,
            final float farPlane,
            final int slices
    ) {
        if (!Float.isFinite(nearPlane) || !Float.isFinite(farPlane)
                || nearPlane <= 0.0f || farPlane <= nearPlane || slices <= 0) {
            throw new IllegalArgumentException("Invalid clustered depth range");
        }
        double scale = slices / (Math.log(farPlane / nearPlane) / Math.log(2.0));
        double bias = -(Math.log(nearPlane) / Math.log(2.0)) * scale;
        return new DepthCoefficients((float) scale, (float) bias);
    }

    public static int depthSlice(
            final float viewDepth,
            final float nearPlane,
            final DepthCoefficients coefficients,
            final int slices
    ) {
        if (!Float.isFinite(viewDepth) || !Float.isFinite(nearPlane)
                || nearPlane <= 0.0f || slices <= 0) {
            throw new IllegalArgumentException("Invalid clustered fragment depth");
        }
        DepthCoefficients checked = java.util.Objects.requireNonNull(
                coefficients,
                "coefficients"
        );
        double boundedDepth = Math.max(viewDepth, nearPlane);
        double raw = Math.floor(
                (Math.log(boundedDepth) / Math.log(2.0)) * checked.scale()
                        + checked.bias()
        );
        return Math.clamp((int) raw, 0, slices - 1);
    }

    /**
     * Rejects a Java/native packing mismatch before an Advanced generation can be admitted.
     */
    public static void requireCompatibleLayout(
            final int version,
            final int paramsBytes,
            final int lightStride,
            final int headerStride,
            final int indexStride
    ) {
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "Advanced lighting ABI version mismatch: expected " + VERSION + ", got " + version
            );
        }
        if (paramsBytes != PARAMS_BYTES
                || lightStride != GPU_LIGHT_STRIDE
                || headerStride != CLUSTER_HEADER_STRIDE
                || indexStride != CLUSTER_INDEX_STRIDE) {
            throw new IllegalArgumentException(
                    "Advanced lighting ABI layout mismatch: params=" + paramsBytes
                            + ", light=" + lightStride
                            + ", header=" + headerStride
                            + ", index=" + indexStride
            );
        }
    }
}
