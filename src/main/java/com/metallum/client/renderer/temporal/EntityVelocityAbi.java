package com.metallum.client.renderer.temporal;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.joml.Matrix4f;

public final class EntityVelocityAbi {
    public static final int PACKET_BYTES = 344;

    private static final long VERTEX_BUFFER_HANDLE = 0L;
    private static final long VERTEX_BUFFER_OFFSET = 8L;
    private static final long VERTEX_LAYOUT_ID = 16L;
    private static final long VERTEX_STRIDE = 20L;
    private static final long INDEX_BUFFER_HANDLE = 24L;
    private static final long INDEX_TYPE = 32L;
    private static final long INDEX_BYTE_OFFSET = 36L;
    private static final long PRIMITIVE_TYPE = 44L;
    private static final long INDEX_COUNT = 48L;
    private static final long BASE_VERTEX = 52L;
    private static final long INSTANCE_COUNT = 56L;
    private static final long BASE_INSTANCE = 60L;
    private static final long WINDING = 64L;
    private static final long CULL_MODE = 68L;
    private static final long DEPTH_BIAS = 72L;
    private static final long SLOPE_SCALE = 76L;
    private static final long CLAMP = 80L;
    private static final long TEXTURE_HANDLE = 84L;
    private static final long SAMPLER_HANDLE = 92L;
    private static final long ALPHA_CUTOFF = 100L;
    private static final long USE_ALPHA_TEST = 104L;
    private static final long UUID_MOST = 108L;
    private static final long UUID_LEAST = 116L;
    private static final long ENTITY_ID = 124L;
    private static final long IS_DISCONTINUOUS = 128L;
    private static final long PREVIOUS_FROM_CURRENT_VIEW = 132L; // 64 bytes
    private static final long CURRENT_UNJITTERED_PROJECTION = 196L; // 64 bytes
    private static final long PREVIOUS_UNJITTERED_PROJECTION = 260L; // 64 bytes
    private static final long FRAME_ID = 324L;
    private static final long RENDERER_GENERATION = 332L;
    private static final long IN_FLIGHT_SLOT = 340L;

    private EntityVelocityAbi() {}

    public static void encodeInto(EntityVelocityPacket packet, MemorySegment segment) {
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, VERTEX_BUFFER_HANDLE, packet.vertexBufferHandle());
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, VERTEX_BUFFER_OFFSET, packet.vertexBufferOffset());
        segment.set(ValueLayout.JAVA_INT, VERTEX_LAYOUT_ID, packet.vertexLayoutId());
        segment.set(ValueLayout.JAVA_INT, VERTEX_STRIDE, packet.vertexStride());
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, INDEX_BUFFER_HANDLE, packet.indexBufferHandle());
        segment.set(ValueLayout.JAVA_INT, INDEX_TYPE, packet.indexType());
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, INDEX_BYTE_OFFSET, packet.indexByteOffset());
        segment.set(ValueLayout.JAVA_INT, PRIMITIVE_TYPE, packet.primitiveType());
        segment.set(ValueLayout.JAVA_INT, INDEX_COUNT, packet.indexCount());
        segment.set(ValueLayout.JAVA_INT, BASE_VERTEX, packet.baseVertex());
        segment.set(ValueLayout.JAVA_INT, INSTANCE_COUNT, packet.instanceCount());
        segment.set(ValueLayout.JAVA_INT, BASE_INSTANCE, packet.baseInstance());
        segment.set(ValueLayout.JAVA_INT, WINDING, packet.winding());
        segment.set(ValueLayout.JAVA_INT, CULL_MODE, packet.cullMode());
        segment.set(ValueLayout.JAVA_FLOAT, DEPTH_BIAS, packet.depthBias());
        segment.set(ValueLayout.JAVA_FLOAT, SLOPE_SCALE, packet.slopeScale());
        segment.set(ValueLayout.JAVA_FLOAT, CLAMP, packet.clamp());
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, TEXTURE_HANDLE, packet.textureHandle());
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, SAMPLER_HANDLE, packet.samplerHandle());
        segment.set(ValueLayout.JAVA_FLOAT, ALPHA_CUTOFF, packet.alphaCutoff());
        segment.set(ValueLayout.JAVA_INT, USE_ALPHA_TEST, packet.useAlphaTest());
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, UUID_MOST, packet.uuid() != null ? packet.uuid().getMostSignificantBits() : 0L);
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, UUID_LEAST, packet.uuid() != null ? packet.uuid().getLeastSignificantBits() : 0L);
        segment.set(ValueLayout.JAVA_INT, ENTITY_ID, packet.entityId());
        segment.set(ValueLayout.JAVA_INT, IS_DISCONTINUOUS, packet.isDiscontinuous() ? 1 : 0);

        writeMatrix(segment, PREVIOUS_FROM_CURRENT_VIEW, packet.previousFromCurrentView());
        writeMatrix(segment, CURRENT_UNJITTERED_PROJECTION, packet.currentUnjitteredProjection());
        writeMatrix(segment, PREVIOUS_UNJITTERED_PROJECTION, packet.previousUnjitteredProjection());

        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, FRAME_ID, packet.frameId());
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED, RENDERER_GENERATION, packet.rendererGeneration());
        segment.set(ValueLayout.JAVA_INT, IN_FLIGHT_SLOT, packet.inFlightSlot());
    }

    private static void writeMatrix(MemorySegment segment, long baseOffset, Matrix4f mat) {
        if (mat == null) {
            for (int i = 0; i < 16; i++) {
                segment.set(ValueLayout.JAVA_FLOAT, baseOffset + i * 4L, 0.0f);
            }
            return;
        }
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 0L, mat.m00());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 4L, mat.m01());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 8L, mat.m02());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 12L, mat.m03());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 16L, mat.m10());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 20L, mat.m11());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 24L, mat.m12());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 28L, mat.m13());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 32L, mat.m20());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 36L, mat.m21());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 40L, mat.m22());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 44L, mat.m23());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 48L, mat.m30());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 52L, mat.m31());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 56L, mat.m32());
        segment.set(ValueLayout.JAVA_FLOAT, baseOffset + 60L, mat.m33());
    }
}
