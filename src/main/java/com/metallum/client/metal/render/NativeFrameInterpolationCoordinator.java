package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.Optional;

/** Typed Java owner for the native coordinator handle introduced in stage 5. */
final class NativeFrameInterpolationCoordinator implements FrameInterpolationCommitBoundary.TicketBridge, AutoCloseable {
    private static final long DEFAULT_DRAIN_TIMEOUT_NANOS = 1_000_000_000L;

    private MemorySegment context;

    private NativeFrameInterpolationCoordinator(final MemorySegment context) {
        this.context = context;
    }

    static Optional<NativeFrameInterpolationCoordinator> create(
            final MemorySegment device,
            final MemorySegment layer,
            final int width,
            final int height,
            final MTLPixelFormat pixelFormat,
            final long rendererGeneration
    ) {
        Objects.requireNonNull(pixelFormat, "pixelFormat");
        if (width < 0 || height < 0 || rendererGeneration < 0L) {
            return Optional.empty();
        }
        MemorySegment context = MetalNativeBridge.metallum_frame_interpolation_create_v1(
                device, layer, width, height, pixelFormat.value, rendererGeneration
        );
        return MetalNativeBridge.isNullHandle(context)
                ? Optional.empty()
                : Optional.of(new NativeFrameInterpolationCoordinator(context));
    }

    @Override
    public synchronized FrameInterpolationCommitBoundary.Preparation prepare(
            final MemorySegment commandBuffer,
            final long rendererGeneration
    ) {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return FrameInterpolationCommitBoundary.Preparation.bypass(
                    FrameInterpolationCommitBoundary.Status.BYPASS_DISABLED
            );
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outTicket = arena.allocate(ValueLayout.JAVA_LONG);
            int rawStatus = MetalNativeBridge.metallum_frame_interpolation_prepare_v1(
                    this.context, commandBuffer, rendererGeneration, outTicket
            );
            return new FrameInterpolationCommitBoundary.Preparation(
                    decodeStatus(rawStatus), outTicket.get(ValueLayout.JAVA_LONG, 0L)
            );
        }
    }

    @Override
    public synchronized FrameInterpolationCommitBoundary.Status publish(final long ticket) {
        return invokeTicket("publish", ticket, MetalNativeBridge::metallum_frame_interpolation_publish_committed_v1);
    }

    @Override
    public synchronized FrameInterpolationCommitBoundary.Status cancel(final long ticket) {
        return invokeTicket("cancel", ticket, MetalNativeBridge::metallum_frame_interpolation_cancel_v1);
    }

    synchronized FrameInterpolationCommitBoundary.Status drain(final long timeoutNanoseconds) {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return FrameInterpolationCommitBoundary.Status.BYPASS_DISABLED;
        }
        return decodeStatus(MetalNativeBridge.metallum_frame_interpolation_drain_v1(
                this.context, Math.max(timeoutNanoseconds, 0L)
        ));
    }

    @Override
    public synchronized void close() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return;
        }
        int rawStatus = MetalNativeBridge.metallum_frame_interpolation_release_v1(
                this.context, DEFAULT_DRAIN_TIMEOUT_NANOS
        );
        FrameInterpolationCommitBoundary.Status status = decodeStatus(rawStatus);
        if (status.prepared() || status == FrameInterpolationCommitBoundary.Status.BYPASS_DISABLED) {
            this.context = MemorySegment.NULL;
            return;
        }
        throw new IllegalStateException("Native frame-interpolation coordinator release failed: " + status);
    }

    private FrameInterpolationCommitBoundary.Status invokeTicket(
            final String operation,
            final long ticket,
            final TicketOperation nativeOperation
    ) {
        if (ticket == 0L || MetalNativeBridge.isNullHandle(this.context)) {
            return FrameInterpolationCommitBoundary.Status.STALE_TICKET;
        }
        return decodeStatus(nativeOperation.invoke(this.context, ticket));
    }

    private static FrameInterpolationCommitBoundary.Status decodeStatus(final int rawStatus) {
        return switch (rawStatus) {
            case 1 -> FrameInterpolationCommitBoundary.Status.PREPARED;
            case 2 -> FrameInterpolationCommitBoundary.Status.BYPASS_DISABLED;
            case 3 -> FrameInterpolationCommitBoundary.Status.BYPASS_UNSUPPORTED;
            case 4 -> FrameInterpolationCommitBoundary.Status.BYPASS_PRIMING;
            case 5 -> FrameInterpolationCommitBoundary.Status.BYPASS_CADENCE;
            case 6 -> FrameInterpolationCommitBoundary.Status.BYPASS_BACKPRESSURE;
            case 7 -> FrameInterpolationCommitBoundary.Status.BYPASS_NO_UI;
            case 8 -> FrameInterpolationCommitBoundary.Status.BYPASS_GENERATION;
            case 9 -> FrameInterpolationCommitBoundary.Status.BYPASS_INPUT_CONTRACT;
            case 10 -> FrameInterpolationCommitBoundary.Status.NO_DRAWABLE;
            case 11 -> FrameInterpolationCommitBoundary.Status.STALE_TICKET;
            case 12 -> FrameInterpolationCommitBoundary.Status.TRANSIENT_FAILURE;
            default -> FrameInterpolationCommitBoundary.Status.FATAL_FOR_GENERATION;
        };
    }

    @FunctionalInterface
    private interface TicketOperation {
        int invoke(MemorySegment context, long ticket);
    }
}
