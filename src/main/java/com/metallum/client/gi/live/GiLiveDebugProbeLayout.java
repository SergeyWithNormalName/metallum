package com.metallum.client.gi.live;

/**
 * Benchmark-only, asynchronous readback ABI for a handful of exact G6 atlas texels.
 *
 * <p>This is deliberately independent of the production G6 ABI: it exists only when the
 * native process was started with {@code METALLUM_GI_G6_DEBUG_PROBE=1}. The request names
 * world-space points in a cascade; coarse cascades map a point to its containing cell. The
 * native side snapshots field identity, receiver origins and masks before issuing a
 * non-blocking blit.</p>
 */
public final class GiLiveDebugProbeLayout {
    public static final int ABI_VERSION = 1;
    public static final int LAYOUT_BYTES = 64;
    public static final int MAX_SAMPLES = 7;
    public static final int READBACK_BYTES = 7_168;

    public static final int REQUEST_BYTES = 120;
    public static final int REQUEST_ABI_VERSION_OFFSET = 0;
    public static final int REQUEST_SAMPLE_COUNT_OFFSET = 4;
    public static final int REQUEST_SAMPLES_OFFSET = 8;
    public static final int REQUEST_SAMPLE_BYTES = 16;
    /** Compatibility aliases used by the benchmark forwarding code. */
    public static final int REQUEST_SAMPLE_BASE = REQUEST_SAMPLES_OFFSET;
    public static final int REQUEST_SAMPLE_STRIDE = REQUEST_SAMPLE_BYTES;
    public static final int REQUEST_SAMPLE_CASCADE_OFFSET = 0;
    public static final int REQUEST_SAMPLE_WORLD_X_OFFSET = 4;
    public static final int REQUEST_SAMPLE_WORLD_Y_OFFSET = 8;
    public static final int REQUEST_SAMPLE_WORLD_Z_OFFSET = 12;

    public static final int RESULT_BYTES = 800;
    public static final int RESULT_ABI_VERSION_OFFSET = 0;
    public static final int RESULT_SAMPLE_COUNT_OFFSET = 4;
    public static final int RESULT_WORLD_GENERATION_OFFSET = 8;
    public static final int RESULT_CLIPMAP_GENERATION_OFFSET = 16;
    public static final int RESULT_PALETTE_GENERATION_OFFSET = 24;
    public static final int RESULT_CONTENT_GENERATION_OFFSET = 32;
    public static final int RESULT_STATIC_SOURCE_EPOCH_OFFSET = 40;
    public static final int RESULT_DYNAMIC_SOURCE_EPOCH_OFFSET = 48;
    public static final int RESULT_ENVIRONMENT_EPOCH_OFFSET = 56;
    public static final int RESULT_FIELD_GENERATION_OFFSET = 64;
    public static final int RESULT_SOURCE_TICK_OFFSET = 72;
    public static final int RESULT_RECEIVER_ORIGINS_OFFSET = 80;
    public static final int RESULT_READY_MASK_OFFSET = 116;
    public static final int RESULT_RECEIVER_MASK_OFFSET = 120;
    public static final int RESULT_SAMPLE_VALID_MASK_OFFSET = 124;
    public static final int RESULT_SAMPLES_OFFSET = 128;
    public static final int RESULT_SAMPLE_BYTES = 96;
    public static final int RESULT_SAMPLE_BASE = RESULT_SAMPLES_OFFSET;
    public static final int RESULT_SAMPLE_STRIDE = RESULT_SAMPLE_BYTES;
    public static final int RESULT_SAMPLE_CASCADE_OFFSET = 0;
    public static final int RESULT_SAMPLE_WORLD_X_OFFSET = 4;
    public static final int RESULT_SAMPLE_WORLD_Y_OFFSET = 8;
    public static final int RESULT_SAMPLE_WORLD_Z_OFFSET = 12;
    public static final int RESULT_SAMPLE_LOCAL_X_OFFSET = 16;
    public static final int RESULT_SAMPLE_LOCAL_Y_OFFSET = 20;
    public static final int RESULT_SAMPLE_LOCAL_Z_OFFSET = 24;
    public static final int RESULT_SAMPLE_FLAGS_OFFSET = 28;
    /** Twelve little-endian floats: R/G/B each contain the four L1 SH coefficients. */
    public static final int RESULT_SAMPLE_SH_COEFFICIENTS_OFFSET = 32;
    public static final int RESULT_SAMPLE_CONFIDENCE_OFFSET = 80;
    public static final int RESULT_SAMPLE_SURFACE_COVERAGE_OFFSET = 81;

    public static final int STATUS_OK = GiLiveLayout.STATUS_OK;
    public static final int STATUS_INVALID = GiLiveLayout.STATUS_INVALID;
    public static final int STATUS_BUSY = GiLiveLayout.STATUS_BUSY;
    public static final int STATUS_STALE = GiLiveLayout.STATUS_STALE;
    public static final int STATUS_WRONG_THREAD = GiLiveLayout.STATUS_WRONG_THREAD;
    public static final int STATUS_REJECTED = GiLiveLayout.STATUS_REJECTED;

    private GiLiveDebugProbeLayout() {
    }

    public static int requestSampleOffset(final int sampleIndex) {
        if (sampleIndex < 0 || sampleIndex >= MAX_SAMPLES) {
            throw new IllegalArgumentException("G6 debug sample index outside [0, 6]");
        }
        return REQUEST_SAMPLES_OFFSET + sampleIndex * REQUEST_SAMPLE_BYTES;
    }

    public static int resultSampleOffset(final int sampleIndex) {
        if (sampleIndex < 0 || sampleIndex >= MAX_SAMPLES) {
            throw new IllegalArgumentException("G6 debug sample index outside [0, 6]");
        }
        return RESULT_SAMPLES_OFFSET + sampleIndex * RESULT_SAMPLE_BYTES;
    }

    public static void validateJavaLayouts() {
        if (REQUEST_SAMPLES_OFFSET + MAX_SAMPLES * REQUEST_SAMPLE_BYTES != REQUEST_BYTES
                || RESULT_SAMPLES_OFFSET + MAX_SAMPLES * RESULT_SAMPLE_BYTES != RESULT_BYTES
                || RESULT_SAMPLE_CONFIDENCE_OFFSET != RESULT_SAMPLE_SH_COEFFICIENTS_OFFSET
                + 12 * Float.BYTES) {
            throw new IllegalStateException("G6 debug probe Java layout drift");
        }
    }
}
