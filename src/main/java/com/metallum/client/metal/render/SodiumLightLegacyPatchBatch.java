package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Bridges Sodium's compact light companion back into the legacy 20-byte
 * terrain vertex only when a compatibility consumer needs that field.
 */
@Environment(EnvType.CLIENT)
public final class SodiumLightLegacyPatchBatch {
    public static final int MAX_PATCHES = 4_096;
    static final long RECORD_BYTES = 32L;
    private static final ValueLayout.OfLong PACKET_LONG = ValueLayout.JAVA_LONG_UNALIGNED
            .withOrder(ByteOrder.LITTLE_ENDIAN);

    private SodiumLightLegacyPatchBatch() {
    }

    public record Patch(
            GpuBuffer geometry,
            GpuBuffer sidecar,
            long vertexOffset,
            long vertexCount
    ) {
    }

    public enum Status {
        ENCODED(1),
        EMPTY(0),
        INVALID_ARGUMENT(-1),
        INVALID_PACKET(-2),
        INVALID_HANDLE(-3),
        INVALID_OBJECT_TYPE(-4),
        WRONG_DEVICE(-5),
        INVALID_RANGE(-6),
        OVERLAPPING_RANGE(-7),
        PIPELINE_UNAVAILABLE(-8),
        ENCODER_UNAVAILABLE(-9),
        INVALID_BUFFER_TYPE(-101),
        NO_METAL_DEVICE(-102),
        UNKNOWN_NATIVE_FAILURE(Integer.MIN_VALUE);

        private final int nativeCode;

        Status(final int nativeCode) {
            this.nativeCode = nativeCode;
        }

        public int nativeCode() {
            return this.nativeCode;
        }

        static Status fromNative(final int nativeCode) {
            return switch (nativeCode) {
                case 1 -> ENCODED;
                case 0 -> EMPTY;
                case -1 -> INVALID_ARGUMENT;
                case -2 -> INVALID_PACKET;
                case -3 -> INVALID_HANDLE;
                case -4 -> INVALID_OBJECT_TYPE;
                case -5 -> WRONG_DEVICE;
                case -6 -> INVALID_RANGE;
                case -7 -> OVERLAPPING_RANGE;
                case -8 -> PIPELINE_UNAVAILABLE;
                case -9 -> ENCODER_UNAVAILABLE;
                default -> UNKNOWN_NATIVE_FAILURE;
            };
        }
    }

    public static Status encode(final List<Patch> patches) {
        if (patches == null) {
            return Status.INVALID_ARGUMENT;
        }
        if (patches.isEmpty()) {
            return Status.EMPTY;
        }
        if (patches.size() > MAX_PATCHES) {
            return Status.INVALID_PACKET;
        }

        for (int index = 0; index < patches.size(); index++) {
            Patch patch = patches.get(index);
            if (patch == null || patch.geometry() == null || patch.sidecar() == null) {
                return Status.INVALID_ARGUMENT;
            }
            if (!(patch.geometry() instanceof MetalGpuBuffer geometry)
                    || !(patch.sidecar() instanceof MetalGpuBuffer sidecar)) {
                return Status.INVALID_BUFFER_TYPE;
            }
            if (geometry.isClosed() || sidecar.isClosed()) {
                return Status.INVALID_HANDLE;
            }
            if (!rangeFits(patch, geometry.size(), sidecar.size())) {
                return Status.INVALID_RANGE;
            }
        }

        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            return Status.NO_METAL_DEVICE;
        }

        return Status.fromNative(
                device.createCommandEncoder().encodeSodiumLightLegacyPatchBatch(patches)
        );
    }

    static long packetBytes(final int count) {
        if (count < 0 || count > MAX_PATCHES) {
            throw new IllegalArgumentException("Sodium light patch count is outside the ABI limit: " + count);
        }
        return Math.multiplyExact((long) count, RECORD_BYTES);
    }

    static void writeRecord(
            final MemorySegment packet,
            final int index,
            final MemorySegment geometry,
            final MemorySegment sidecar,
            final long vertexOffset,
            final long vertexCount
    ) {
        long record = Math.multiplyExact((long) index, RECORD_BYTES);
        packet.set(PACKET_LONG, record, geometry.address());
        packet.set(PACKET_LONG, record + 8L, sidecar.address());
        packet.set(PACKET_LONG, record + 16L, vertexOffset);
        packet.set(PACKET_LONG, record + 24L, vertexCount);
    }

    private static boolean rangeFits(
            final Patch patch,
            final long geometryBytes,
            final long sidecarBytes
    ) {
        if (patch.vertexOffset() < 0L || patch.vertexCount() <= 0L) {
            return false;
        }
        try {
            long vertexEnd = Math.addExact(patch.vertexOffset(), patch.vertexCount());
            if (vertexEnd > Integer.toUnsignedLong(-1)) {
                return false;
            }
            return Math.multiplyExact(vertexEnd, 20L) <= geometryBytes
                    && Math.multiplyExact(vertexEnd, 2L) <= sidecarBytes;
        } catch (ArithmeticException ignored) {
            return false;
        }
    }
}
