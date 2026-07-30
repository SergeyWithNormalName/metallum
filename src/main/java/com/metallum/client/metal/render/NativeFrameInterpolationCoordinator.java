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

    private final Arena scratchArena;
    private final MemorySegment outTicketScratch;
    private MemorySegment context;

    private NativeFrameInterpolationCoordinator(final MemorySegment context) {
        this.context = context;
        this.scratchArena = Arena.ofShared();
        this.outTicketScratch = this.scratchArena.allocate(ValueLayout.JAVA_LONG);
    }

    static Optional<NativeFrameInterpolationCoordinator> create(
            final MemorySegment device,
            final MemorySegment layer,
            final int renderWidth,
            final int renderHeight,
            final int displayWidth,
            final int displayHeight,
            final MTLPixelFormat pixelFormat,
            final long rendererGeneration,
            final boolean spatialInputs
    ) {
        Objects.requireNonNull(pixelFormat, "pixelFormat");
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0
                || rendererGeneration < 0L) {
            return Optional.empty();
        }
        MemorySegment context = MetalNativeBridge.metallum_frame_interpolation_create_v3(
                device,
                layer,
                renderWidth,
                renderHeight,
                displayWidth,
                displayHeight,
                pixelFormat.value,
                rendererGeneration,
                spatialInputs
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
        this.outTicketScratch.set(ValueLayout.JAVA_LONG, 0L, 0L);
        int rawStatus = MetalNativeBridge.metallum_frame_interpolation_prepare_v1(
                this.context, commandBuffer, rendererGeneration, this.outTicketScratch
        );
        return new FrameInterpolationCommitBoundary.Preparation(
                decodeStatus(rawStatus), this.outTicketScratch.get(ValueLayout.JAVA_LONG, 0L)
        );
    }

    @Override
    public synchronized FrameInterpolationCommitBoundary.Preparation prepare(
            final FrameInterpolationCommitBoundary.PreparationInput input
    ) {
        Objects.requireNonNull(input, "input");
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return FrameInterpolationCommitBoundary.Preparation.bypass(
                    FrameInterpolationCommitBoundary.Status.BYPASS_DISABLED
            );
        }
        this.outTicketScratch.set(ValueLayout.JAVA_LONG, 0L, 0L);
        int rawStatus = MetalNativeBridge.metallum_frame_interpolation_prepare_v2(
                this.context,
                input.commandBuffer(),
                input.rendererGeneration(),
                input.sourceTexture(),
                input.sceneTexture(),
                input.sceneDepthTexture(),
                input.semanticTexture(),
                input.uiTexture(),
                input.globalFence(),
                input.spatialHdrPrecomposed() ? 1 : 0,
                input.outputMode(),
                input.sourceEncoding(),
                input.materialGenerationActive() ? 1 : 0,
                input.diagnosticPattern() ? 1 : 0,
                input.currentHeadroom(),
                input.hdrStrength(),
                input.bloomStrength(),
                this.outTicketScratch
        );
        return new FrameInterpolationCommitBoundary.Preparation(
                decodeStatus(rawStatus), this.outTicketScratch.get(ValueLayout.JAVA_LONG, 0L)
        );
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

    synchronized long runtimeStatusPacked() {
        if (MetalNativeBridge.isNullHandle(this.context)) {
            return 0L;
        }
        return MetalNativeBridge.metallum_frame_interpolation_runtime_status_v1(this.context);
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
            this.scratchArena.close();
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
