package com.metallum.client.renderer.temporal;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RenderContractMode;
import com.metallum.client.renderer.RendererFeatureMask;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/** Fixed-width v3 packet; this remains the only Java/native frame-state ABI. */
public final class FrameStateAbi {
    public static final int CURRENT_VERSION = 3;
    public static final int PACKET_BYTES = 848;

    private static final long ABI_VERSION = 0L;
    private static final long BYTE_SIZE = 4L;
    private static final long FRAME_CONTRACT_VERSION = 8L;
    private static final long FRAME_GRAPH_VERSION = 12L;
    private static final long FRAME_ID = 16L;
    private static final long SUBMIT_INDEX = 24L;
    private static final long RENDERER_GENERATION_ID = 32L;
    private static final long HISTORY_GENERATION = 40L;
    private static final long RENDER_CONTRACT_GENERATION_ID = 48L;
    private static final long LIGHTING_GENERATION_ID = 56L;
    private static final long OUTPUT_GENERATION_ID = 64L;
    private static final long WORLD_IDENTITY = 72L;
    private static final long DIMENSION_IDENTITY = 80L;
    private static final long RESET_MASK = 88L;
    private static final long FEATURE_MASK = 96L;
    private static final long RENDER_CONTRACT_MODE = 104L;
    private static final long LIGHTING_MODEL = 108L;
    private static final long OUTPUT_MODE = 112L;
    private static final long EXECUTOR_KIND = 116L;
    private static final long LIGHTING_PRESET = 120L;
    private static final long RENDER_WIDTH = 124L;
    private static final long RENDER_HEIGHT = 128L;
    private static final long DISPLAY_WIDTH = 132L;
    private static final long DISPLAY_HEIGHT = 136L;
    private static final long IN_FLIGHT_SLOT = 140L;
    private static final long RESERVED_FLAGS = 144L;
    private static final long DELTA_SECONDS = 148L;
    private static final long NEAR_PLANE = 152L;
    private static final long FAR_PLANE = 156L;
    private static final long JITTER_X = 160L;
    private static final long JITTER_Y = 164L;
    private static final long EXPOSURE = 168L;
    private static final long PRE_EXPOSURE = 172L;
    private static final long CURRENT_HEADROOM = 176L;
    private static final long POTENTIAL_HEADROOM = 180L;
    private static final long RESERVED_FLOAT = 184L;
    private static final long BASE_RESOURCE_BYTES = 192L;
    private static final long MATERIAL_RESOURCE_BYTES = 200L;
    private static final long HDR_RESOURCE_BYTES = 208L;
    private static final long ADVANCED_LIGHTING_RESOURCE_BYTES = 216L;
    private static final long UPSCALE_RESOURCE_BYTES = 224L;
    private static final long INTERPOLATION_RESOURCE_BYTES = 232L;
    private static final long DIAGNOSTIC_RESOURCE_BYTES = 240L;
    private static final long ADVANCED_LIGHT_COUNT = 248L;
    private static final long ADVANCED_PASS_COUNT = 252L;
    private static final long ADVANCED_ENCODER_COUNT = 256L;
    private static final long ADVANCED_PSO_COUNT = 260L;
    private static final long ADVANCED_WORK_QUEUE_COUNT = 264L;
    private static final long ADVANCED_DISPATCH_COUNT = 268L;
    private static final long ADVANCED_UPLOAD_BYTES = 272L;
    private static final long CURRENT_CAMERA_POSITION = 280L;
    private static final long PREVIOUS_CAMERA_POSITION = 304L;
    private static final long CURRENT_VIEW = 328L;
    private static final long CURRENT_PROJECTION = 392L;
    private static final long CURRENT_UNJITTERED_VIEW = 456L;
    private static final long CURRENT_UNJITTERED_PROJECTION = 520L;
    private static final long PREVIOUS_VIEW = 584L;
    private static final long PREVIOUS_PROJECTION = 648L;
    private static final long PREVIOUS_UNJITTERED_VIEW = 712L;
    private static final long PREVIOUS_UNJITTERED_PROJECTION = 776L;

    private FrameStateAbi() {
    }

    public static MemorySegment encode(final FrameState state, final Arena arena) {
        Objects.requireNonNull(arena, "arena");
        MemorySegment packet = arena.allocate(PACKET_BYTES, 16L);
        encodeInto(state, packet);
        validatePacket(packet);
        return packet;
    }

    /** Encodes into a caller-owned in-flight slot without allocating. */
    public static void encodeInto(final FrameState state, final MemorySegment packet) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(packet, "packet");
        if (packet.byteSize() != PACKET_BYTES) {
            throw new IllegalArgumentException("FrameState ABI destination has the wrong size");
        }
        packet.fill((byte) 0);
        putInt(packet, ABI_VERSION, CURRENT_VERSION);
        putInt(packet, BYTE_SIZE, PACKET_BYTES);
        putInt(packet, FRAME_CONTRACT_VERSION, state.contract().version());
        putInt(packet, FRAME_GRAPH_VERSION, state.frameGraphVersion());
        putLong(packet, FRAME_ID, state.frameId());
        putLong(packet, SUBMIT_INDEX, state.submitIndex());
        putLong(packet, RENDERER_GENERATION_ID, state.rendererGenerationId());
        putLong(packet, HISTORY_GENERATION, state.historyGeneration());
        putLong(packet, RENDER_CONTRACT_GENERATION_ID, state.renderContractGenerationId());
        putLong(packet, LIGHTING_GENERATION_ID, state.lightingGenerationId());
        putLong(packet, OUTPUT_GENERATION_ID, state.outputGenerationId());
        putLong(packet, WORLD_IDENTITY, state.worldIdentity());
        putLong(packet, DIMENSION_IDENTITY, state.dimensionIdentity());
        putLong(packet, RESET_MASK, resetMask(state));
        putLong(packet, FEATURE_MASK, state.featureMask().bits());
        putInt(packet, RENDER_CONTRACT_MODE, renderContractModeCode(state.renderContractMode()));
        putInt(packet, LIGHTING_MODEL, lightingModelCode(state.lightingModel()));
        putInt(packet, OUTPUT_MODE, outputModeCode(state.outputMode()));
        putInt(packet, EXECUTOR_KIND, executorCode(state.executorKind()));
        putInt(packet, LIGHTING_PRESET, presetCode(state.lightingPreset()));
        putInt(packet, RENDER_WIDTH, state.renderExtent().width());
        putInt(packet, RENDER_HEIGHT, state.renderExtent().height());
        putInt(packet, DISPLAY_WIDTH, state.displayExtent().width());
        putInt(packet, DISPLAY_HEIGHT, state.displayExtent().height());
        putInt(packet, IN_FLIGHT_SLOT, state.inFlightSlot());
        putFloat(packet, DELTA_SECONDS, state.deltaSeconds());
        putFloat(packet, NEAR_PLANE, state.nearPlane());
        putFloat(packet, FAR_PLANE, state.farPlane());
        putFloat(packet, JITTER_X, state.jitterOffset().x());
        putFloat(packet, JITTER_Y, state.jitterOffset().y());
        putFloat(packet, EXPOSURE, state.exposure());
        putFloat(packet, PRE_EXPOSURE, state.preExposure());
        putFloat(packet, CURRENT_HEADROOM, state.currentDisplayHeadroom());
        putFloat(packet, POTENTIAL_HEADROOM, state.potentialDisplayHeadroom());
        putLong(packet, BASE_RESOURCE_BYTES, state.resourceBytes().base());
        putLong(packet, MATERIAL_RESOURCE_BYTES, state.resourceBytes().material());
        putLong(packet, HDR_RESOURCE_BYTES, state.resourceBytes().hdr());
        putLong(packet, ADVANCED_LIGHTING_RESOURCE_BYTES,
                state.resourceBytes().advancedLighting());
        putLong(packet, UPSCALE_RESOURCE_BYTES, state.resourceBytes().upscale());
        putLong(packet, INTERPOLATION_RESOURCE_BYTES, state.resourceBytes().interpolation());
        putLong(packet, DIAGNOSTIC_RESOURCE_BYTES, state.resourceBytes().diagnostic());
        putInt(packet, ADVANCED_LIGHT_COUNT, state.advancedLightingWork().lightCount());
        putInt(packet, ADVANCED_PASS_COUNT, state.advancedLightingWork().passCount());
        putInt(packet, ADVANCED_ENCODER_COUNT, state.advancedLightingWork().encoderCount());
        putInt(packet, ADVANCED_PSO_COUNT, state.advancedLightingWork().psoCount());
        putInt(packet, ADVANCED_WORK_QUEUE_COUNT,
                state.advancedLightingWork().workQueueCount());
        putInt(packet, ADVANCED_DISPATCH_COUNT, state.advancedLightingWork().dispatchCount());
        putLong(packet, ADVANCED_UPLOAD_BYTES, state.advancedLightingWork().uploadBytes());
        putPosition(packet, CURRENT_CAMERA_POSITION, state.currentCameraPosition());
        putPosition(packet, PREVIOUS_CAMERA_POSITION, state.previousCameraPosition());
        putMatrix(packet, CURRENT_VIEW, state.currentTransforms().view());
        putMatrix(packet, CURRENT_PROJECTION, state.currentTransforms().projection());
        putMatrix(packet, CURRENT_UNJITTERED_VIEW, state.currentTransforms().unjitteredView());
        putMatrix(packet, CURRENT_UNJITTERED_PROJECTION, state.currentTransforms().unjitteredProjection());
        putMatrix(packet, PREVIOUS_VIEW, state.previousTransforms().view());
        putMatrix(packet, PREVIOUS_PROJECTION, state.previousTransforms().projection());
        putMatrix(packet, PREVIOUS_UNJITTERED_VIEW, state.previousTransforms().unjitteredView());
        putMatrix(packet, PREVIOUS_UNJITTERED_PROJECTION, state.previousTransforms().unjitteredProjection());
    }

    /** Java-side mirror of native header, numeric and independent-axis validation. */
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
        int renderContract = getInt(packet, RENDER_CONTRACT_MODE);
        int lighting = getInt(packet, LIGHTING_MODEL);
        int output = getInt(packet, OUTPUT_MODE);
        int executor = getInt(packet, EXECUTOR_KIND);
        int preset = getInt(packet, LIGHTING_PRESET);
        if (renderContract < 0 || renderContract > 1 || lighting < 0 || lighting > 1
                || output < 0 || output > 1
                || executor < 0 || executor > 1 || preset < 0 || preset > 2) {
            throw new IllegalArgumentException("FrameState ABI enum value is invalid");
        }
        if (getInt(packet, RESERVED_FLAGS) != 0 || getFloat(packet, RESERVED_FLOAT) != 0.0f) {
            throw new IllegalArgumentException("FrameState ABI reserved fields are non-zero");
        }
        RendererFeatureMask mask = RendererFeatureMask.of(getLong(packet, FEATURE_MASK));
        if (getInt(packet, RENDER_WIDTH) <= 0 || getInt(packet, RENDER_HEIGHT) <= 0
                || getInt(packet, DISPLAY_WIDTH) <= 0 || getInt(packet, DISPLAY_HEIGHT) <= 0) {
            throw new IllegalArgumentException("FrameState ABI extents must be positive");
        }
        int slot = getInt(packet, IN_FLIGHT_SLOT);
        if (slot < 0 || slot >= 3 || getLong(packet, SUBMIT_INDEX) % 3L != slot) {
            throw new IllegalArgumentException("FrameState ABI in-flight slot is invalid");
        }
        requireNonNegative(getFloat(packet, DELTA_SECONDS), "delta seconds");
        float near = getFloat(packet, NEAR_PLANE);
        float far = getFloat(packet, FAR_PLANE);
        requirePositive(near, "near plane");
        requirePositive(far, "far plane");
        if (far <= near) {
            throw new IllegalArgumentException("FrameState ABI projection range is invalid");
        }
        requirePositive(getFloat(packet, EXPOSURE), "exposure");
        requirePositive(getFloat(packet, PRE_EXPOSURE), "pre-exposure");
        float jitterX = getFloat(packet, JITTER_X);
        float jitterY = getFloat(packet, JITTER_Y);
        if (!Float.isFinite(jitterX) || !Float.isFinite(jitterY)
                || Math.abs(jitterX) > 0.5f || Math.abs(jitterY) > 0.5f) {
            throw new IllegalArgumentException("FrameState ABI jitter is invalid");
        }
        float currentHeadroom = getFloat(packet, CURRENT_HEADROOM);
        float potentialHeadroom = getFloat(packet, POTENTIAL_HEADROOM);
        if (!Float.isFinite(currentHeadroom) || currentHeadroom < 1.0f
                || !Float.isFinite(potentialHeadroom) || potentialHeadroom < currentHeadroom) {
            throw new IllegalArgumentException("FrameState ABI display headroom is invalid");
        }
        long knownResetBits = (1L << FrameState.HistoryResetReason.values().length) - 1L;
        if ((getLong(packet, RESET_MASK) & ~knownResetBits) != 0L) {
            throw new IllegalArgumentException("FrameState ABI reset mask contains unknown bits");
        }
        requireNonNegativeLongs(
                packet,
                FRAME_ID,
                SUBMIT_INDEX,
                RENDERER_GENERATION_ID,
                HISTORY_GENERATION,
                RENDER_CONTRACT_GENERATION_ID,
                LIGHTING_GENERATION_ID,
                OUTPUT_GENERATION_ID,
                WORLD_IDENTITY,
                DIMENSION_IDENTITY,
                BASE_RESOURCE_BYTES,
                MATERIAL_RESOURCE_BYTES,
                HDR_RESOURCE_BYTES,
                ADVANCED_LIGHTING_RESOURCE_BYTES,
                UPSCALE_RESOURCE_BYTES,
                INTERPOLATION_RESOURCE_BYTES,
                DIAGNOSTIC_RESOURCE_BYTES,
                ADVANCED_UPLOAD_BYTES
        );
        requireNonNegativeInts(
                packet,
                ADVANCED_LIGHT_COUNT,
                ADVANCED_PASS_COUNT,
                ADVANCED_ENCODER_COUNT,
                ADVANCED_PSO_COUNT,
                ADVANCED_WORK_QUEUE_COUNT,
                ADVANCED_DISPATCH_COUNT
        );
        validateFiniteRange(packet, CURRENT_CAMERA_POSITION, 6, ValueLayout.JAVA_DOUBLE.byteSize());
        validateFiniteRange(packet, CURRENT_VIEW, 128, ValueLayout.JAVA_FLOAT.byteSize());
        if (renderContract == 0 && lighting == 1) {
            throw new IllegalArgumentException("Legacy FrameState ABI requests Advanced lighting");
        }
        if (renderContract == 0 && getLong(packet, MATERIAL_RESOURCE_BYTES) != 0L) {
            throw new IllegalArgumentException("Legacy FrameState ABI contains material resources");
        }
        if (lighting == 0 && (getLong(packet, ADVANCED_LIGHTING_RESOURCE_BYTES) != 0L
                || getInt(packet, ADVANCED_LIGHT_COUNT) != 0
                || getInt(packet, ADVANCED_PASS_COUNT) != 0
                || getInt(packet, ADVANCED_ENCODER_COUNT) != 0
                || getInt(packet, ADVANCED_PSO_COUNT) != 0
                || getInt(packet, ADVANCED_WORK_QUEUE_COUNT) != 0
                || getInt(packet, ADVANCED_DISPATCH_COUNT) != 0
                || getLong(packet, ADVANCED_UPLOAD_BYTES) != 0L)) {
            throw new IllegalArgumentException("Vanilla FrameState ABI contains Advanced work");
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

    public static long resetMask(final FrameState state) {
        long mask = 0L;
        for (FrameState.HistoryResetReason reason : state.historyResetReasons()) {
            mask |= 1L << reason.ordinal();
        }
        return mask;
    }

    private static void putPosition(
            final MemorySegment packet,
            final long offset,
            final FrameState.CameraPosition position
    ) {
        putDouble(packet, offset, position.x());
        putDouble(packet, offset + Double.BYTES, position.y());
        putDouble(packet, offset + 2L * Double.BYTES, position.z());
    }

    private static void putMatrix(final MemorySegment packet, final long offset, final Matrix4 matrix) {
        for (int index = 0; index < Matrix4.ELEMENT_COUNT; index++) {
            putFloat(packet, offset + (long) index * Float.BYTES, matrix.element(index));
        }
    }

    private static void validateFiniteRange(
            final MemorySegment packet,
            final long offset,
            final int count,
            final long stride
    ) {
        for (int index = 0; index < count; index++) {
            long elementOffset = offset + index * stride;
            boolean finite = stride == Double.BYTES
                    ? Double.isFinite(packet.get(ValueLayout.JAVA_DOUBLE, elementOffset))
                    : Float.isFinite(packet.get(ValueLayout.JAVA_FLOAT, elementOffset));
            if (!finite) {
                throw new IllegalArgumentException("FrameState ABI contains a non-finite transform");
            }
        }
    }

    private static void requirePositive(final float value, final String name) {
        if (!(value > 0.0f) || !Float.isFinite(value)) {
            throw new IllegalArgumentException("FrameState ABI " + name + " is invalid");
        }
    }

    private static void requireNonNegative(final float value, final String name) {
        if (value < 0.0f || !Float.isFinite(value)) {
            throw new IllegalArgumentException("FrameState ABI " + name + " is invalid");
        }
    }

    private static void requireNonNegativeLongs(
            final MemorySegment packet,
            final long... offsets
    ) {
        for (long offset : offsets) {
            if (getLong(packet, offset) < 0L) {
                throw new IllegalArgumentException("FrameState ABI contains a negative long");
            }
        }
    }

    private static void requireNonNegativeInts(
            final MemorySegment packet,
            final long... offsets
    ) {
        for (long offset : offsets) {
            if (getInt(packet, offset) < 0) {
                throw new IllegalArgumentException("FrameState ABI contains a negative counter");
            }
        }
    }

    private static int renderContractModeCode(final RenderContractMode mode) {
        return mode == RenderContractMode.LEGACY ? 0 : 1;
    }

    private static int lightingModelCode(final LightingModel model) {
        return model == LightingModel.VANILLA ? 0 : 1;
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

    private static void putFloat(final MemorySegment packet, final long offset, final double value) {
        float converted = (float) value;
        if (!Float.isFinite(converted)) {
            throw new IllegalArgumentException("FrameState ABI float value is not finite");
        }
        packet.set(ValueLayout.JAVA_FLOAT, offset, converted);
    }

    private static float getFloat(final MemorySegment packet, final long offset) {
        return packet.get(ValueLayout.JAVA_FLOAT, offset);
    }

    private static void putDouble(final MemorySegment packet, final long offset, final double value) {
        packet.set(ValueLayout.JAVA_DOUBLE, offset, value);
    }
}
