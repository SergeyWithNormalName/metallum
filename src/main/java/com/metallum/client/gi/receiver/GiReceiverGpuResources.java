package com.metallum.client.gi.receiver;

import com.metallum.client.gi.GiRuntimeStages;
import com.metallum.client.gi.transport.GiTransportGpuResources;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Consumer;

/** Render-thread owner of the native G5 sampler/parameter context; G4 retains all textures. */
public final class GiReceiverGpuResources implements AutoCloseable {
    private static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);

    public record Stats(
            boolean ready,
            int lastArm,
            long bindCount,
            long zeroBindings,
            long fieldBindings,
            long allocatedBytes,
            int originX,
            int originY,
            int originZ,
            boolean carrierSafe,
            int resourceCount,
            int bindingCount,
            long sharedTextureBytes
    ) {
        public Stats {
            if (lastArm < GiReceiverLayout.ARM_CONTROL
                    || lastArm > GiReceiverLayout.ARM_FIELD
                    || bindCount < 0L || zeroBindings < 0L || fieldBindings < 0L
                    || allocatedBytes < GiReceiverLayout.LIFETIME_OVERHEAD_BYTES
                    || sharedTextureBytes != 0L
                    || resourceCount != GiReceiverLayout.RESOURCE_COUNT
                    || bindingCount != GiReceiverLayout.BINDING_COUNT
                    || zeroBindings + fieldBindings != bindCount) {
                throw new IllegalArgumentException("Invalid native G5 receiver statistics");
            }
            if (ready && fieldBindings == 0L && lastArm == GiReceiverLayout.ARM_FIELD) {
                throw new IllegalArgumentException("Ready G5 field statistics contain no field binding");
            }
        }
    }

    private final Thread ownerThread;
    private final Consumer<MemorySegment> deferredRelease;
    private @Nullable MemorySegment context;
    private boolean closed;

    private GiReceiverGpuResources(
            final MemorySegment context,
            final Consumer<MemorySegment> deferredRelease
    ) {
        this.ownerThread = Thread.currentThread();
        this.context = context;
        this.deferredRelease = deferredRelease;
    }

    public static void validateNativeAbi() {
        if (MetalNativeBridge.metallum_gi_receiver_abi_version_v1()
                != GiReceiverLayout.ABI_VERSION) {
            throw new IllegalStateException("Native G5 receiver ABI version differs");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment layout = arena.allocate(GiReceiverLayout.LAYOUT_BYTES, Integer.BYTES);
            int status = MetalNativeBridge.metallum_gi_receiver_layout_v1(
                    layout, GiReceiverLayout.LAYOUT_BYTES
            );
            if (status != GiReceiverLayout.STATUS_OK) {
                throw new IllegalStateException("Native G5 receiver layout query failed: " + status);
            }
            int[] expected = {
                    GiReceiverLayout.ABI_VERSION,
                    GiReceiverLayout.LAYOUT_BYTES,
                    GiReceiverLayout.PARAMS_BYTES,
                    GiReceiverLayout.STATS_BYTES,
                    GiReceiverLayout.SH_RED_TEXTURE_SLOT,
                    GiReceiverLayout.SH_GREEN_TEXTURE_SLOT,
                    GiReceiverLayout.SH_BLUE_TEXTURE_SLOT,
                    GiReceiverLayout.CONFIDENCE_TEXTURE_SLOT,
                    GiReceiverLayout.PARAMS_BUFFER_SLOT,
                    GiReceiverLayout.STATUS_OK,
                    GiReceiverLayout.STATUS_ZERO_READY,
                    GiReceiverLayout.STATUS_INVALID,
                    GiReceiverLayout.STATUS_STALE,
                    GiReceiverLayout.STATUS_WRONG_THREAD,
                    GiReceiverLayout.RESOURCE_COUNT,
                    GiReceiverLayout.BINDING_COUNT,
                    Math.toIntExact(GiReceiverLayout.LIFETIME_OVERHEAD_BYTES)
            };
            for (int index = 0; index < GiReceiverLayout.LAYOUT_BYTES / Integer.BYTES; index++) {
                int actual = layout.get(LE_INT, (long) index * Integer.BYTES);
                int wanted = index < expected.length ? expected[index] : 0;
                if (actual != wanted) {
                    throw new IllegalStateException(
                            "Native G5 receiver layout word " + index
                                    + " differs: " + actual + " != " + wanted
                    );
                }
            }
        }
        GiReceiverBindingAbi.requireLegal();
    }

    public static @Nullable GiReceiverGpuResources create(
            final GiTransportGpuResources.ReadToken transport,
            final Consumer<MemorySegment> deferredRelease
    ) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(deferredRelease, "deferredRelease");
        MemorySegment transportContext = transport.nativeContext();
        if (MetalNativeBridge.isNullHandle(transportContext)) {
            return null;
        }
        MemorySegment context = MetalNativeBridge.metallum_gi_receiver_create_context_v1(
                transportContext
        );
        return MetalNativeBridge.isNullHandle(context)
                ? null : new GiReceiverGpuResources(context, deferredRelease);
    }

    /** Allocation-free render-thread binding of four shared G4 textures and one params buffer. */
    public synchronized int bindVertex(
            final MemorySegment encoder,
            final GiRuntimeStages.ReceiverArm arm,
            final boolean carrierSafe
    ) {
        assertOwnerThread();
        if (this.closed || this.context == null || MetalNativeBridge.isNullHandle(encoder)) {
            return GiReceiverLayout.STATUS_INVALID;
        }
        return MetalNativeBridge.metallum_gi_receiver_bind_vertex_v1(
                this.context,
                encoder,
                GiReceiverLayout.nativeArm(arm),
                carrierSafe ? 1 : 0
        );
    }

    /** Diagnostic-only snapshot. It is intentionally not used by the per-draw binding path. */
    public synchronized @Nullable Stats stats() {
        assertOwnerThread();
        if (this.closed || this.context == null) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packet = arena.allocate(GiReceiverLayout.STATS_BYTES, Long.BYTES);
            int status = MetalNativeBridge.metallum_gi_receiver_get_stats_v1(
                    this.context, packet, GiReceiverLayout.STATS_BYTES
            );
            if (status != GiReceiverLayout.STATUS_OK
                    || packet.get(LE_LONG, GiReceiverLayout.STATS_RESERVED_OFFSET) != 0L) {
                return null;
            }
            return new Stats(
                    packet.get(LE_INT, GiReceiverLayout.STATS_READY_OFFSET) == 1,
                    packet.get(LE_INT, GiReceiverLayout.STATS_LAST_ARM_OFFSET),
                    packet.get(LE_LONG, GiReceiverLayout.STATS_BIND_COUNT_OFFSET),
                    packet.get(LE_LONG, GiReceiverLayout.STATS_ZERO_BINDINGS_OFFSET),
                    packet.get(LE_LONG, GiReceiverLayout.STATS_FIELD_BINDINGS_OFFSET),
                    packet.get(LE_LONG, GiReceiverLayout.STATS_ALLOCATED_BYTES_OFFSET),
                    packet.get(LE_INT, GiReceiverLayout.STATS_ORIGIN_X_OFFSET),
                    packet.get(LE_INT, GiReceiverLayout.STATS_ORIGIN_Y_OFFSET),
                    packet.get(LE_INT, GiReceiverLayout.STATS_ORIGIN_Z_OFFSET),
                    packet.get(LE_INT, GiReceiverLayout.STATS_CARRIER_SAFE_OFFSET) == 1,
                    packet.get(LE_INT, GiReceiverLayout.STATS_RESOURCE_COUNT_OFFSET),
                    packet.get(LE_INT, GiReceiverLayout.STATS_BINDING_COUNT_OFFSET),
                    packet.get(LE_LONG, GiReceiverLayout.STATS_SHARED_TEXTURE_BYTES_OFFSET)
            );
        }
    }

    public synchronized boolean isClosed() {
        return this.closed;
    }

    @Override
    public synchronized void close() {
        assertOwnerThread();
        if (this.closed) {
            return;
        }
        this.closed = true;
        MemorySegment stale = this.context;
        this.context = null;
        if (stale != null && !MetalNativeBridge.isNullHandle(stale)) {
            this.deferredRelease.accept(stale);
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G5 receiver resources are render-thread confined");
        }
    }
}
