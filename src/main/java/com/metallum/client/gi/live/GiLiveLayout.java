package com.metallum.client.gi.live;

import com.metallum.client.gi.semantic.GiSemanticTransportFieldView;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/** Exact Java/Swift ABI and fixed topology for the G6 three-cascade live field. */
public final class GiLiveLayout {
    public static final int ABI_VERSION = 1;
    public static final int LAYOUT_BYTES = 160;
    public static final int LAYOUT_WORDS = LAYOUT_BYTES / Integer.BYTES;
    public static final int HEADER_BYTES = 192;
    public static final int CELL_BYTES = GiSemanticTransportFieldView.CELL_BYTES;
    public static final int STATS_BYTES = 216;
    public static final int PARAMS_BYTES = 160;

    public static final int EDGE = GiSemanticTransportFieldView.EDGE;
    public static final int CELL_COUNT = GiSemanticTransportFieldView.CELL_COUNT;
    public static final long CELLS_BYTES = GiSemanticTransportFieldView.PAYLOAD_BYTES;
    public static final int CASCADE_COUNT = 3;
    public static final int ATLAS_DEPTH = EDGE * CASCADE_COUNT;
    public static final int IN_FLIGHT_SLOTS = 3;
    public static final int READY_MASK_ALL = (1 << CASCADE_COUNT) - 1;
    public static final int ITERATION_COUNT = 1;
    public static final int MAXIMUM_DISTANCE = 8;
    public static final float FORM_WEIGHT_NORMALIZATION = (float) 29.17999846648958;
    public static final float FP16_ABSOLUTE_TOLERANCE = 1.0F / 1_024.0F;
    public static final float FP16_RELATIVE_TOLERANCE = 1.0F / 512.0F;
    public static final int FLAGS_NONE = 0;
    /** The header describes a bounded brick batch rather than a whole-cascade rebuild. */
    public static final int FLAG_INCREMENTAL_BRICKS = 1;
    /** Exact cells from the preceding epoch may be retained after compatibility proof. */
    public static final int FLAG_PRESERVE_EXACT = 1 << 1;
    /** The first batch must remap the preceding world-grid overlap through scratch. */
    public static final int FLAG_SCROLL_REMAP = 1 << 2;
    /** This is the first batch for the selected cascade in the new field generation. */
    public static final int FLAG_PREPARE_CASCADE = 1 << 3;
    /** Reuse the exact snapshot captured before an earlier compatible provisional epoch. */
    public static final int FLAG_RETAIN_CAPTURED_BASIS = 1 << 4;
    public static final int KNOWN_FLAGS = FLAG_INCREMENTAL_BRICKS
            | FLAG_PRESERVE_EXACT | FLAG_SCROLL_REMAP | FLAG_PREPARE_CASCADE
            | FLAG_RETAIN_CAPTURED_BASIS;

    public static final int BRICK_EDGE = 8;
    public static final int BRICKS_PER_AXIS = EDGE / BRICK_EDGE;
    public static final int BRICKS_PER_CASCADE = BRICKS_PER_AXIS
            * BRICKS_PER_AXIS * BRICKS_PER_AXIS;
    public static final int MAX_BRICKS_PER_SUBMIT = 8;
    public static final long ALL_BRICKS_MASK = -1L;

    public static final int SH_RED_TEXTURE_SLOT = 6;
    public static final int SH_GREEN_TEXTURE_SLOT = 7;
    public static final int SH_BLUE_TEXTURE_SLOT = 8;
    public static final int CONFIDENCE_TEXTURE_SLOT = 9;
    public static final int PARAMS_BUFFER_SLOT = 25;

    public static final int STATUS_OK = 1;
    public static final int STATUS_ZERO_READY = 0;
    public static final int STATUS_INVALID = -1;
    public static final int STATUS_BUSY = -2;
    public static final int STATUS_STALE = -3;
    public static final int STATUS_WRONG_THREAD = -5;
    public static final int STATUS_REJECTED = -6;

    /** Conservative native sampler/context/registry lifetime charge reported by Swift. */
    public static final long LIFETIME_OVERHEAD_BYTES = 65_536L;
    public static final long JAVA_PACKET_BYTES = STATS_BYTES
            + (long) IN_FLIGHT_SLOTS * (HEADER_BYTES + CELLS_BYTES);

    public enum ResetKind {
        WORLD(0),
        TELEPORT(1),
        SCROLL(2),
        SOURCE(3),
        EXPLICIT(4),
        DEVICE(5);

        private final int nativeId;

        ResetKind(final int nativeId) {
            this.nativeId = nativeId;
        }

        public int nativeId() {
            return this.nativeId;
        }
    }

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG
            .withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    public static final StructLayout HEADER_LAYOUT = MemoryLayout.structLayout(
            LE_INT.withName("abiVersion"),
            LE_INT.withName("headerBytes"),
            LE_LONG.withName("worldGeneration"),
            LE_LONG.withName("clipmapGeneration"),
            LE_LONG.withName("paletteGeneration"),
            LE_LONG.withName("contentGeneration"),
            LE_LONG.withName("staticSourceEpoch"),
            LE_LONG.withName("dynamicSourceEpoch"),
            LE_LONG.withName("environmentEpoch"),
            LE_INT.withName("origin0X"), LE_INT.withName("origin0Y"),
            LE_INT.withName("origin0Z"), LE_INT.withName("origin1X"),
            LE_INT.withName("origin1Y"), LE_INT.withName("origin1Z"),
            LE_INT.withName("origin2X"), LE_INT.withName("origin2Y"),
            LE_INT.withName("origin2Z"),
            LE_INT.withName("cascadeIndex"),
            LE_INT.withName("cellCount"),
            LE_INT.withName("iterationCount"),
            LE_INT.withName("maximumDistance"),
            LE_INT.withName("validSurfaceCount"),
            LE_INT.withName("unknownCellCount"),
            LE_FLOAT.withName("formWeightNormalization"),
            LE_FLOAT.withName("fp16AbsoluteTolerance"),
            LE_FLOAT.withName("fp16RelativeTolerance"),
            LE_INT.withName("flags"),
            MemoryLayout.paddingLayout(Integer.BYTES),
            LE_LONG.withName("sourceStamp"),
            LE_LONG.withName("fieldGeneration"),
            LE_LONG.withName("sourceTick"),
            LE_INT.withName("resetKind"),
            LE_INT.withName("reserved32"),
            LE_LONG.withName("reserved0"),
            LE_LONG.withName("reserved1")
    );

    public static final int HEADER_ABI_VERSION_OFFSET = 0;
    public static final int HEADER_HEADER_BYTES_OFFSET = 4;
    public static final int HEADER_WORLD_GENERATION_OFFSET = 8;
    public static final int HEADER_CLIPMAP_GENERATION_OFFSET = 16;
    public static final int HEADER_PALETTE_GENERATION_OFFSET = 24;
    public static final int HEADER_CONTENT_GENERATION_OFFSET = 32;
    public static final int HEADER_STATIC_SOURCE_EPOCH_OFFSET = 40;
    public static final int HEADER_DYNAMIC_SOURCE_EPOCH_OFFSET = 48;
    public static final int HEADER_ENVIRONMENT_EPOCH_OFFSET = 56;
    public static final int HEADER_ORIGIN_0_X_OFFSET = 64;
    public static final int HEADER_ORIGIN_0_Y_OFFSET = 68;
    public static final int HEADER_ORIGIN_0_Z_OFFSET = 72;
    public static final int HEADER_ORIGIN_1_X_OFFSET = 76;
    public static final int HEADER_ORIGIN_1_Y_OFFSET = 80;
    public static final int HEADER_ORIGIN_1_Z_OFFSET = 84;
    public static final int HEADER_ORIGIN_2_X_OFFSET = 88;
    public static final int HEADER_ORIGIN_2_Y_OFFSET = 92;
    public static final int HEADER_ORIGIN_2_Z_OFFSET = 96;
    public static final int HEADER_CASCADE_INDEX_OFFSET = 100;
    public static final int HEADER_CELL_COUNT_OFFSET = 104;
    public static final int HEADER_ITERATION_COUNT_OFFSET = 108;
    public static final int HEADER_MAXIMUM_DISTANCE_OFFSET = 112;
    public static final int HEADER_VALID_SURFACE_COUNT_OFFSET = 116;
    public static final int HEADER_UNKNOWN_CELL_COUNT_OFFSET = 120;
    public static final int HEADER_FORM_WEIGHT_NORMALIZATION_OFFSET = 124;
    public static final int HEADER_FP16_ABSOLUTE_TOLERANCE_OFFSET = 128;
    public static final int HEADER_FP16_RELATIVE_TOLERANCE_OFFSET = 132;
    public static final int HEADER_FLAGS_OFFSET = 136;
    public static final int HEADER_ALIGNMENT_PADDING_OFFSET = 140;
    public static final int HEADER_SOURCE_STAMP_OFFSET = 144;
    public static final int HEADER_FIELD_GENERATION_OFFSET = 152;
    public static final int HEADER_SOURCE_TICK_OFFSET = 160;
    public static final int HEADER_RESET_KIND_OFFSET = 168;
    public static final int HEADER_RESERVED_32_OFFSET = 172;
    public static final int HEADER_RESERVED_0_OFFSET = 176;
    public static final int HEADER_RESERVED_1_OFFSET = 184;

    public static final StructLayout STATS_LAYOUT = MemoryLayout.structLayout(
            LE_INT.withName("readyMask"), LE_INT.withName("buildInFlight"),
            LE_INT.withName("shaderLibraryMode"), LE_INT.withName("padding0"),
            LE_LONG.withName("worldGeneration"),
            LE_LONG.withName("clipmapGeneration"),
            LE_LONG.withName("contentGeneration"),
            LE_LONG.withName("staticSourceEpoch"),
            LE_LONG.withName("dynamicSourceEpoch"),
            LE_LONG.withName("environmentEpoch"),
            LE_LONG.withName("fieldGeneration"), LE_LONG.withName("sourceTick"),
            LE_LONG.withName("allocatedBytes"), LE_LONG.withName("residentBytes"),
            LE_LONG.withName("stagingBytes"), LE_LONG.withName("transportDispatches"),
            LE_LONG.withName("cascadeBuilds0"), LE_LONG.withName("cascadeBuilds1"),
            LE_LONG.withName("cascadeBuilds2"), LE_LONG.withName("invalidations"),
            LE_LONG.withName("staleRejects"), LE_LONG.withName("busyRejects"),
            LE_LONG.withName("rejectedCount"), LE_LONG.withName("bindCount"),
            LE_LONG.withName("zeroBindings"), LE_LONG.withName("fieldBindings"),
            LE_INT.withName("resetWorld"), LE_INT.withName("resetTeleport"),
            LE_INT.withName("resetScroll"), LE_INT.withName("resetSource"),
            LE_INT.withName("resetExplicit"), LE_INT.withName("resetDevice")
    );

    public static final int STATS_READY_MASK_OFFSET = 0;
    public static final int STATS_BUILD_IN_FLIGHT_OFFSET = 4;
    public static final int STATS_SHADER_LIBRARY_MODE_OFFSET = 8;
    public static final int STATS_PADDING_0_OFFSET = 12;
    public static final int STATS_WORLD_GENERATION_OFFSET = 16;
    public static final int STATS_CLIPMAP_GENERATION_OFFSET = 24;
    public static final int STATS_CONTENT_GENERATION_OFFSET = 32;
    public static final int STATS_STATIC_SOURCE_EPOCH_OFFSET = 40;
    public static final int STATS_DYNAMIC_SOURCE_EPOCH_OFFSET = 48;
    public static final int STATS_ENVIRONMENT_EPOCH_OFFSET = 56;
    public static final int STATS_FIELD_GENERATION_OFFSET = 64;
    public static final int STATS_SOURCE_TICK_OFFSET = 72;
    public static final int STATS_ALLOCATED_BYTES_OFFSET = 80;
    public static final int STATS_RESIDENT_BYTES_OFFSET = 88;
    public static final int STATS_STAGING_BYTES_OFFSET = 96;
    public static final int STATS_TRANSPORT_DISPATCHES_OFFSET = 104;
    public static final int STATS_CASCADE_BUILDS_0_OFFSET = 112;
    public static final int STATS_CASCADE_BUILDS_1_OFFSET = 120;
    public static final int STATS_CASCADE_BUILDS_2_OFFSET = 128;
    public static final int STATS_INVALIDATIONS_OFFSET = 136;
    public static final int STATS_STALE_REJECTS_OFFSET = 144;
    public static final int STATS_BUSY_REJECTS_OFFSET = 152;
    public static final int STATS_REJECTED_COUNT_OFFSET = 160;
    public static final int STATS_BIND_COUNT_OFFSET = 168;
    public static final int STATS_ZERO_BINDINGS_OFFSET = 176;
    public static final int STATS_FIELD_BINDINGS_OFFSET = 184;
    public static final int STATS_RESET_WORLD_OFFSET = 192;
    public static final int STATS_RESET_TELEPORT_OFFSET = 196;
    public static final int STATS_RESET_SCROLL_OFFSET = 200;
    public static final int STATS_RESET_SOURCE_OFFSET = 204;
    public static final int STATS_RESET_EXPLICIT_OFFSET = 208;
    public static final int STATS_RESET_DEVICE_OFFSET = 212;

    // Native receiver params written into the selected in-flight slot.
    public static final int PARAM_ORIGINS_OFFSET = 0;
    public static final int PARAM_SCALES_OFFSET = 48;
    public static final int PARAM_READY_MASK_OFFSET = 96;
    public static final int PARAM_CARRIER_SAFE_OFFSET = 100;
    public static final int PARAM_ABI_VERSION_OFFSET = 104;
    public static final int PARAM_FIELD_GENERATION_OFFSET = 108;
    public static final int PARAM_ATLAS_DEPTH_OFFSET = 112;
    public static final int PARAM_ATLAS_GUTTER_OFFSET = 116;
    public static final int PARAM_WORLD_GENERATION_OFFSET = 120;
    public static final int PARAM_SOURCE_TICK_OFFSET = 124;
    /** Three little-endian uint64 masks, stored as GLSL uvec2[3]. */
    public static final int PARAM_EXACT_BRICK_MASK_0_OFFSET = 128;
    public static final int PARAM_EXACT_BRICK_MASK_1_OFFSET = 136;
    public static final int PARAM_EXACT_BRICK_MASK_2_OFFSET = 144;
    public static final int PARAM_RESERVED_OFFSET = 152;
    public static final int PARAM_RESERVED_BYTES = 8;

    private GiLiveLayout() {
    }

    /** Fails locally before any downcall if Java's native struct layout drifts. */
    public static void validateJavaLayouts() {
        if (HEADER_LAYOUT.byteSize() != HEADER_BYTES || STATS_LAYOUT.byteSize() != STATS_BYTES) {
            throw new IllegalStateException("G6 Java struct size differs from Swift ABI");
        }
        requireOffset(HEADER_LAYOUT, "sourceStamp", HEADER_SOURCE_STAMP_OFFSET);
        requireOffset(HEADER_LAYOUT, "fieldGeneration", HEADER_FIELD_GENERATION_OFFSET);
        requireOffset(HEADER_LAYOUT, "resetKind", HEADER_RESET_KIND_OFFSET);
        requireOffset(HEADER_LAYOUT, "reserved1", HEADER_RESERVED_1_OFFSET);
        requireOffset(STATS_LAYOUT, "worldGeneration", STATS_WORLD_GENERATION_OFFSET);
        requireOffset(STATS_LAYOUT, "transportDispatches", STATS_TRANSPORT_DISPATCHES_OFFSET);
        requireOffset(STATS_LAYOUT, "resetWorld", STATS_RESET_WORLD_OFFSET);
        requireOffset(STATS_LAYOUT, "resetDevice", STATS_RESET_DEVICE_OFFSET);
    }

    private static void requireOffset(
            final StructLayout layout,
            final String name,
            final long expected
    ) {
        long actual = layout.byteOffset(groupElement(name));
        if (actual != expected) {
            throw new IllegalStateException(
                    "G6 Java offset for " + name + " differs: " + actual + " != " + expected
            );
        }
    }
}
