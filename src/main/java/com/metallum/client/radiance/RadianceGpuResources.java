package com.metallum.client.radiance;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Java owner token for one native frozen-reflection context.
 *
 * <p>The native context owns the private textures, sampler, and vertex parameter buffer. Java
 * receives neither texture nor buffer handles: the snapshot is copied into native staging during
 * {@link #queueFrozenBuild}, and the native command-buffer completion retains that staging until
 * the private-texture blit and compute work have finished.</p>
 */
public final class RadianceGpuResources implements AutoCloseable {
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int SOURCE_EDGE = 64;

    public record GpuStats(
            boolean ready,
            long worldGeneration,
            long persistentBytes,
            long uploadCount,
            long sourceMipBuildCount,
            int sourceOriginX,
            int sourceOriginY,
            int sourceOriginZ,
            boolean probeReady,
            long probePersistentBytes,
            long probeBuildCount,
            long probeMipBuildCount,
            long probeBuildGpuNanoseconds,
            int probeOriginX,
            int probeOriginY,
            int probeOriginZ,
            boolean buildInFlight
    ) {
        public long allocatedBytes() {
            return this.persistentBytes + this.probePersistentBytes;
        }
    }

    private final Consumer<MemorySegment> deferredNativeRelease;
    private @Nullable MemorySegment nativeHandle;
    private long worldGeneration;
    private boolean closed;

    private RadianceGpuResources(
            final MemorySegment handle,
            final long worldGeneration,
            final Consumer<MemorySegment> deferredNativeRelease
    ) {
        this.nativeHandle = handle;
        this.worldGeneration = worldGeneration;
        this.deferredNativeRelease = deferredNativeRelease;
    }

    public static @Nullable RadianceGpuResources create(
            final MemorySegment deviceHandle,
            final MemorySegment commandQueueHandle,
            final long worldGeneration,
            final Consumer<MemorySegment> deferredNativeRelease
    ) {
        Objects.requireNonNull(deferredNativeRelease, "deferredNativeRelease");
        if (MetalNativeBridge.isNullHandle(deviceHandle) || MetalNativeBridge.isNullHandle(commandQueueHandle)) {
            return null;
        }
        MemorySegment handle = MetalNativeBridge.metallum_radiance_context_create(
                deviceHandle, commandQueueHandle, worldGeneration
        );
        return MetalNativeBridge.isNullHandle(handle)
                ? null
                : new RadianceGpuResources(handle, worldGeneration, deferredNativeRelease);
    }

    public synchronized boolean isClosed() {
        return this.closed;
    }

    public synchronized long worldGeneration() {
        return this.worldGeneration;
    }

    /**
     * Copies the complete, immutable 64^3 source snapshot into owned native staging and enqueues
     * exactly one private-texture upload plus directional-probe build. No FFM segment escapes the
     * downcall.
     */
    public synchronized boolean queueFrozenBuild(
            final long worldGeneration,
            final int originX,
            final int originY,
            final int originZ,
            final short[] packedRgba,
            final byte[] validity,
            final float strength,
            final float roughness,
            final boolean contributionOnly
    ) {
        if (this.closed || this.nativeHandle == null) {
            return false;
        }
        Objects.requireNonNull(packedRgba, "packedRgba");
        Objects.requireNonNull(validity, "validity");
        int cellCount = SOURCE_EDGE * SOURCE_EDGE * SOURCE_EDGE;
        if (packedRgba.length != cellCount * 4 || validity.length != cellCount) {
            return false;
        }
        // The native entry point immediately copies both segments into Metal-owned staging before
        // returning. This one-shot build allocation therefore cannot escape to asynchronous GPU
        // work and is not part of the steady-state render loop.
        boolean queued;
        try (Arena staging = Arena.ofConfined()) {
            MemorySegment rgba = staging.allocate((long) packedRgba.length * Short.BYTES);
            MemorySegment support = staging.allocate(validity.length);
            MemorySegment.copy(
                    MemorySegment.ofArray(packedRgba), ValueLayout.JAVA_SHORT, 0L,
                    rgba, ValueLayout.JAVA_SHORT, 0L, packedRgba.length
            );
            MemorySegment.copy(
                    MemorySegment.ofArray(validity), ValueLayout.JAVA_BYTE, 0L,
                    support, ValueLayout.JAVA_BYTE, 0L, validity.length
            );
            queued = MetalNativeBridge.metallum_radiance_context_upload_source_frozen(
                    this.nativeHandle,
                    worldGeneration,
                    originX, originY, originZ,
                    rgba,
                    support,
                    strength,
                    roughness,
                    contributionOnly
            );
        }
        if (queued) {
            this.worldGeneration = worldGeneration;
        }
        return queued;
    }

    /** Returns true only once the native completion handler has made the field bindable. */
    public synchronized boolean bindVertexResources(final MemorySegment encoder) {
        return !this.closed
                && this.nativeHandle != null
                && MetalNativeBridge.metallum_radiance_context_bind_vertex_resources(this.nativeHandle, encoder);
    }

    /** Diagnostic-only snapshot; never call this from the frame loop. */
    public synchronized @Nullable GpuStats getStats() {
        if (this.closed || this.nativeHandle == null) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment stats = arena.allocate(104L);
            if (!MetalNativeBridge.metallum_radiance_context_get_stats(this.nativeHandle, stats)) {
                return null;
            }
            return new GpuStats(
                    stats.get(LE_INT, 0L) == 1,
                    stats.get(LE_LONG, 8L),
                    stats.get(LE_LONG, 16L),
                    stats.get(LE_LONG, 24L),
                    stats.get(LE_LONG, 32L),
                    stats.get(LE_INT, 40L), stats.get(LE_INT, 44L), stats.get(LE_INT, 48L),
                    stats.get(LE_INT, 52L) == 1,
                    stats.get(LE_LONG, 56L),
                    stats.get(LE_LONG, 64L),
                    stats.get(LE_LONG, 72L),
                    stats.get(LE_LONG, 80L),
                    stats.get(LE_INT, 88L), stats.get(LE_INT, 92L), stats.get(LE_INT, 96L),
                    stats.get(LE_INT, 100L) == 1
            );
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        MemorySegment handle = this.nativeHandle;
        this.nativeHandle = null;
        if (handle != null && !MetalNativeBridge.isNullHandle(handle)) {
            this.deferredNativeRelease.accept(handle);
        }
    }
}
