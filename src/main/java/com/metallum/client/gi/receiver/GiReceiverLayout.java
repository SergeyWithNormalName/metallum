package com.metallum.client.gi.receiver;

import com.metallum.client.gi.GiRuntimeStages;
import com.metallum.client.gi.transport.GiTransportLayout;

/** Exact Java/Swift/generated-MSL ABI for the isolated G5 vertex receiver. */
public final class GiReceiverLayout {
    public static final int ABI_VERSION = 1;
    public static final int LAYOUT_BYTES = 128;
    public static final int PARAMS_BYTES = 64;
    public static final int STATS_BYTES = 80;

    public static final int SH_RED_TEXTURE_SLOT = 6;
    public static final int SH_GREEN_TEXTURE_SLOT = 7;
    public static final int SH_BLUE_TEXTURE_SLOT = 8;
    public static final int CONFIDENCE_TEXTURE_SLOT = 9;
    public static final int PARAMS_BUFFER_SLOT = 25;
    public static final int RESOURCE_COUNT = 5;
    public static final int BINDING_COUNT = 5;
    /** Conservative charge for sampler, Swift/registry and Java capability lifetime storage. */
    public static final long LIFETIME_OVERHEAD_BYTES = 65_536L;

    public static final int ARM_CONTROL = 0;
    public static final int ARM_CANDIDATE = 1;
    public static final int ARM_FIELD = 2;

    public static final int STATUS_OK = 1;
    public static final int STATUS_ZERO_READY = 0;
    public static final int STATUS_INVALID = -1;
    public static final int STATUS_STALE = -3;
    public static final int STATUS_WRONG_THREAD = -5;

    public static final int EDGE = GiTransportLayout.EDGE;
    public static final int CELL_SIZE_BLOCKS = GiTransportLayout.CELL_SIZE_BLOCKS;
    public static final int SPAN_BLOCKS = EDGE * CELL_SIZE_BLOCKS;
    public static final float INVERSE_SPAN = 1.0F / SPAN_BLOCKS;
    public static final float INVERSE_PI = 0.3183098861837907F;

    // MetallumGiReceiverParamsV1 offsets.
    public static final int PARAM_ORIGIN_X_OFFSET = 0;
    public static final int PARAM_ORIGIN_Y_OFFSET = 4;
    public static final int PARAM_ORIGIN_Z_OFFSET = 8;
    public static final int PARAM_EDGE_OFFSET = 12;
    public static final int PARAM_INVERSE_SPAN_X_OFFSET = 16;
    public static final int PARAM_INVERSE_SPAN_Y_OFFSET = 20;
    public static final int PARAM_INVERSE_SPAN_Z_OFFSET = 24;
    public static final int PARAM_FIELD_READY_FLOAT_OFFSET = 28;
    public static final int PARAM_ARM_OFFSET = 32;
    public static final int PARAM_CARRIER_SAFE_OFFSET = 36;
    public static final int PARAM_CELL_SIZE_OFFSET = 40;
    public static final int PARAM_ABI_VERSION_OFFSET = 44;

    // MetallumGiReceiverStatsV1 offsets.
    public static final int STATS_READY_OFFSET = 0;
    public static final int STATS_LAST_ARM_OFFSET = 4;
    public static final int STATS_BIND_COUNT_OFFSET = 8;
    public static final int STATS_ZERO_BINDINGS_OFFSET = 16;
    public static final int STATS_FIELD_BINDINGS_OFFSET = 24;
    public static final int STATS_ALLOCATED_BYTES_OFFSET = 32;
    public static final int STATS_ORIGIN_X_OFFSET = 40;
    public static final int STATS_ORIGIN_Y_OFFSET = 44;
    public static final int STATS_ORIGIN_Z_OFFSET = 48;
    public static final int STATS_CARRIER_SAFE_OFFSET = 52;
    public static final int STATS_RESOURCE_COUNT_OFFSET = 56;
    public static final int STATS_BINDING_COUNT_OFFSET = 60;
    public static final int STATS_SHARED_TEXTURE_BYTES_OFFSET = 64;
    public static final int STATS_RESERVED_OFFSET = 72;

    private GiReceiverLayout() {
    }

    public static int nativeArm(final GiRuntimeStages.ReceiverArm arm) {
        if (arm == null || !arm.isRequested()) {
            throw new IllegalArgumentException("G5 binding requires an active receiver arm");
        }
        int id = arm.nativeId();
        if (id < ARM_CONTROL || id > ARM_FIELD) {
            throw new IllegalArgumentException("Unknown G5 native receiver arm: " + id);
        }
        return id;
    }
}
