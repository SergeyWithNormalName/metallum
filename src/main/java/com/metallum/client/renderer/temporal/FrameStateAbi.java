package com.metallum.client.renderer.temporal;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingMode;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RendererFeatureMask;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/** Fixed-width L0 extension of the common FrameState packet; this is the only native frame-state ABI. */
public final class FrameStateAbi {
    public static final int CURRENT_VERSION = 1;
    public static final int PACKET_BYTES = 160;

    private static final long ABI_VERSION = 0L;
    private static final long BYTE_SIZE = 4L;
    private static final long FRAME_CONTRACT_VERSION = 8L;
    private static final long FRAME_GRAPH_VERSION = 12L;
    private static final long FRAME_ID = 16L;
    private static final long RENDERER_GENERATION_ID = 24L;
    private static final long HISTORY_GENERATION = 32L;
    private static final long LIGHTING_GENERATION_ID = 40L;
    private static final long OUTPUT_GENERATION_ID = 48L;
    private static final long LIGHTING_MODE = 56L;
    private static final long OUTPUT_MODE = 60L;
    private static final long EXECUTOR_KIND = 64L;
    private static final long LIGHTING_PRESET = 68L;
    private static final long FEATURE_MASK = 72L;
    private static final long RENDER_WIDTH = 80L;
    private static final long RENDER_HEIGHT = 84L;
    private static final long DISPLAY_WIDTH = 88L;
    private static final long DISPLAY_HEIGHT = 92L;
    private static final long BASE_RESOURCE_BYTES = 96L;
    private static final long HDR_RESOURCE_BYTES = 104L;
    private static final long LIGHTING_RESOURCE_BYTES = 112L;
    private static final long UPSCALE_RESOURCE_BYTES = 120L;
    private static final long INTERPOLATION_RESOURCE_BYTES = 128L;
    private static final long LIGHT_COUNT = 136L;
    private static final long LIGHTING_PASS_COUNT = 140L;
    private static final long LIGHTING_DISPATCH_COUNT = 144L;
    private static final long LIGHTING_UPLOAD_BYTES = 152L;

    private FrameStateAbi() {
    }

    public static MemorySegment encode(final FrameState state, final Arena arena) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(arena, "arena");
        MemorySegment packet = arena.allocate(PACKET_BYTES, Long.BYTES);
        packet.fill((byte) 0);
        putInt(packet, ABI_VERSION, CURRENT_VERSION);
        putInt(packet, BYTE_SIZE, PACKET_BYTES);
        putInt(packet, FRAME_CONTRACT_VERSION, state.contract().version());
        putInt(packet, FRAME_GRAPH_VERSION, state.frameGraphVersion());
        putLong(packet, FRAME_ID, state.frameId());
        putLong(packet, RENDERER_GENERATION_ID, state.rendererGenerationId());
        putLong(packet, HISTORY_GENERATION, state.historyGeneration());
        putLong(packet, LIGHTING_GENERATION_ID, state.lightingGenerationId());
        putLong(packet, OUTPUT_GENERATION_ID, state.outputGenerationId());
        putInt(packet, LIGHTING_MODE, lightingModeCode(state.lightingMode()));
        putInt(packet, OUTPUT_MODE, outputModeCode(state.outputMode()));
        putInt(packet, EXECUTOR_KIND, executorCode(state.executorKind()));
        putInt(packet, LIGHTING_PRESET, presetCode(state.lightingPreset()));
        putLong(packet, FEATURE_MASK, state.featureMask().bits());
        putInt(packet, RENDER_WIDTH, state.renderExtent().width());
        putInt(packet, RENDER_HEIGHT, state.renderExtent().height());
        putInt(packet, DISPLAY_WIDTH, state.displayExtent().width());
        putInt(packet, DISPLAY_HEIGHT, state.displayExtent().height());
        putLong(packet, BASE_RESOURCE_BYTES, state.resourceBytes().base());
        putLong(packet, HDR_RESOURCE_BYTES, state.resourceBytes().hdr());
        putLong(packet, LIGHTING_RESOURCE_BYTES, state.resourceBytes().lighting());
        putLong(packet, UPSCALE_RESOURCE_BYTES, state.resourceBytes().upscale());
        putLong(packet, INTERPOLATION_RESOURCE_BYTES, state.resourceBytes().interpolation());
        putInt(packet, LIGHT_COUNT, state.lightingWork().lightCount());
        putInt(packet, LIGHTING_PASS_COUNT, state.lightingWork().passCount());
        putInt(packet, LIGHTING_DISPATCH_COUNT, state.lightingWork().dispatchCount());
        putLong(packet, LIGHTING_UPLOAD_BYTES, state.lightingWork().uploadBytes());
        validatePacket(packet);
        return packet;
    }

    /** Java-side mirror of native header and independent-axis validation. */
    public static void validatePacket(final MemorySegment packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet.byteSize() != PACKET_BYTES || getInt(packet, BYTE_SIZE) != PACKET_BYTES) {
            throw new IllegalArgumentException("FrameState ABI byte size mismatch");
        }
        if (getInt(packet, ABI_VERSION) != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported FrameState ABI version");
        }
        if (getInt(packet, FRAME_CONTRACT_VERSION) <= 0 || getInt(packet, FRAME_GRAPH_VERSION) <= 0) {
            throw new IllegalArgumentException("FrameState contract versions must be positive");
        }
        int lighting = getInt(packet, LIGHTING_MODE);
        int output = getInt(packet, OUTPUT_MODE);
        int executor = getInt(packet, EXECUTOR_KIND);
        int preset = getInt(packet, LIGHTING_PRESET);
        if (lighting < 0 || lighting > 1 || output < 0 || output > 1
                || executor < 0 || executor > 1 || preset < 0 || preset > 2) {
            throw new IllegalArgumentException("FrameState ABI enum value is invalid");
        }
        RendererFeatureMask mask = RendererFeatureMask.of(getLong(packet, FEATURE_MASK));
        if (getInt(packet, RENDER_WIDTH) <= 0 || getInt(packet, RENDER_HEIGHT) <= 0
                || getInt(packet, DISPLAY_WIDTH) <= 0 || getInt(packet, DISPLAY_HEIGHT) <= 0) {
            throw new IllegalArgumentException("FrameState ABI extents must be positive");
        }
        if (lighting == 0 && (getLong(packet, LIGHTING_RESOURCE_BYTES) != 0L
                || getInt(packet, LIGHT_COUNT) != 0
                || getInt(packet, LIGHTING_PASS_COUNT) != 0
                || getInt(packet, LIGHTING_DISPATCH_COUNT) != 0
                || getLong(packet, LIGHTING_UPLOAD_BYTES) != 0L)) {
            throw new IllegalArgumentException("Legacy FrameState ABI contains lighting work");
        }
        if (output == 0 && getLong(packet, HDR_RESOURCE_BYTES) != 0L) {
            throw new IllegalArgumentException("SDR FrameState ABI contains HDR resources");
        }
        if (!mask.contains(RendererFeatureMask.SPATIAL_UPSCALING)
                && !mask.contains(RendererFeatureMask.TEMPORAL_UPSCALING)
                && getLong(packet, UPSCALE_RESOURCE_BYTES) != 0L) {
            throw new IllegalArgumentException("Native FrameState ABI contains upscale resources");
        }
        if (!mask.contains(RendererFeatureMask.FRAME_INTERPOLATION)
                && getLong(packet, INTERPOLATION_RESOURCE_BYTES) != 0L) {
            throw new IllegalArgumentException("FrameState ABI contains interpolation resources");
        }
    }

    private static int lightingModeCode(final LightingMode mode) {
        return mode == LightingMode.LEGACY ? 0 : 1;
    }

    private static int outputModeCode(final DisplayOutputMode mode) {
        return mode == DisplayOutputMode.SDR ? 0 : 1;
    }

    private static int executorCode(final MetalExecutorKind executor) {
        return executor == MetalExecutorKind.METAL3 ? 0 : 1;
    }

    private static int presetCode(final LightingPreset preset) {
        return switch (preset) {
            case PERFORMANCE -> 0;
            case BALANCED -> 1;
            case ULTRA -> 2;
        };
    }

    private static void putInt(final MemorySegment packet, final long offset, final int value) {
        packet.set(ValueLayout.JAVA_INT, offset, value);
    }

    private static int getInt(final MemorySegment packet, final long offset) {
        return packet.get(ValueLayout.JAVA_INT, offset);
    }

    private static void putLong(final MemorySegment packet, final long offset, final long value) {
        packet.set(ValueLayout.JAVA_LONG, offset, value);
    }

    private static long getLong(final MemorySegment packet, final long offset) {
        return packet.get(ValueLayout.JAVA_LONG, offset);
    }
}
