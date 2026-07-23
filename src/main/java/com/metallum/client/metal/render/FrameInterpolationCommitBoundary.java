package com.metallum.client.metal.render;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Owns the narrow {@code prepare -> render command-buffer commit -> publish}
 * boundary for a native frame-interpolation ticket.
 *
 * <p>The coordinator deliberately cannot present from {@link #prepare}.
 * A ticket is only published after the renderer command buffer has accepted
 * {@code commit}; a failed commit cancels it instead.  Until a later admission
 * stage installs a native ticket bridge, the disabled instance is a strict
 * bypass and the normal single-present path remains unchanged.</p>
 */
public final class FrameInterpolationCommitBoundary implements AutoCloseable {
    public enum Status {
        PREPARED,
        BYPASS_DISABLED,
        BYPASS_UNSUPPORTED,
        BYPASS_PRIMING,
        BYPASS_CADENCE,
        BYPASS_BACKPRESSURE,
        BYPASS_NO_UI,
        BYPASS_GENERATION,
        BYPASS_INPUT_CONTRACT,
        NO_DRAWABLE,
        STALE_TICKET,
        TRANSIENT_FAILURE,
        FATAL_FOR_GENERATION;

        public boolean prepared() {
            return this == PREPARED;
        }
    }

    public record Preparation(Status status, long ticket) {
        public Preparation {
            Objects.requireNonNull(status, "status");
            if (status.prepared() != (ticket != 0L)) {
                throw new IllegalArgumentException("Only PREPARED may carry a non-zero ticket");
            }
        }

        public static Preparation bypass(final Status status) {
            return new Preparation(status, 0L);
        }
    }

    /**
     * Immutable render-thread hand-off for a production interpolation ticket.
     *
     * <p>This is deliberately a typed side channel rather than a FrameState
     * ABI extension: the values are native object handles whose lifetime is
     * bounded by the renderer command buffer, not serializable per-frame
     * camera state.  A bridge may return a bypass for any incomplete input;
     * callers then retain the established single-real-frame presentation.
     * The native side copies every accepted input into its preallocated ring
     * before the renderer command buffer is committed.</p>
     */
    public record PreparationInput(
            MemorySegment commandBuffer,
            long rendererGeneration,
            MemorySegment sourceTexture,
            MemorySegment sceneTexture,
            MemorySegment sceneDepthTexture,
            MemorySegment semanticTexture,
            MemorySegment uiTexture,
            MemorySegment globalFence,
            boolean spatialHdrPrecomposed,
            int outputMode,
            int sourceEncoding,
            boolean materialGenerationActive,
            boolean diagnosticPattern,
            float currentHeadroom,
            float hdrStrength,
            float bloomStrength
    ) {
        public PreparationInput {
            Objects.requireNonNull(commandBuffer, "commandBuffer");
            Objects.requireNonNull(sourceTexture, "sourceTexture");
            Objects.requireNonNull(sceneTexture, "sceneTexture");
            Objects.requireNonNull(sceneDepthTexture, "sceneDepthTexture");
            Objects.requireNonNull(semanticTexture, "semanticTexture");
            Objects.requireNonNull(uiTexture, "uiTexture");
            Objects.requireNonNull(globalFence, "globalFence");
            if (rendererGeneration < 0L) {
                throw new IllegalArgumentException("rendererGeneration must be non-negative");
            }
            if (outputMode < 0 || outputMode > 2 || sourceEncoding < 0 || sourceEncoding > 2) {
                throw new IllegalArgumentException("Invalid presentation encoding");
            }
            if (!Float.isFinite(currentHeadroom) || !Float.isFinite(hdrStrength)
                    || !Float.isFinite(bloomStrength)) {
                throw new IllegalArgumentException("Presentation parameters must be finite");
            }
        }
    }

    public interface TicketBridge {
        Preparation prepare(MemorySegment commandBuffer, long rendererGeneration);

        /**
         * Production bridges override this to receive the exact world/UI
         * hand-off.  The legacy overload remains for the stage-5 ticket
         * lifecycle regression harness and for a strict disabled fallback.
         */
        default Preparation prepare(final PreparationInput input) {
            Objects.requireNonNull(input, "input");
            return prepare(input.commandBuffer(), input.rendererGeneration());
        }

        Status publish(long ticket);

        Status cancel(long ticket);
    }

    @FunctionalInterface
    public interface CommitOperation {
        void commit();
    }

    private static final TicketBridge DISABLED = new TicketBridge() {
        @Override
        public Preparation prepare(final MemorySegment commandBuffer, final long rendererGeneration) {
            return Preparation.bypass(Status.BYPASS_DISABLED);
        }

        @Override
        public Status publish(final long ticket) {
            return Status.BYPASS_DISABLED;
        }

        @Override
        public Status cancel(final long ticket) {
            return Status.BYPASS_DISABLED;
        }
    };

    private volatile TicketBridge bridge;
    private long pendingTicket;

    public FrameInterpolationCommitBoundary() {
        this(DISABLED);
    }

    public FrameInterpolationCommitBoundary(final TicketBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    static TicketBridge disabledBridge() {
        return DISABLED;
    }

    public Status prepare(final MemorySegment commandBuffer, final long rendererGeneration) {
        return prepare(new PreparationInput(
                commandBuffer,
                rendererGeneration,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
                false,
                0,
                0,
                false,
                false,
                1.0f,
                0.0f,
                0.0f
        ));
    }

    public Status prepare(final PreparationInput input) {
        Objects.requireNonNull(input, "input");
        if (this.pendingTicket != 0L) {
            throw new IllegalStateException("A frame-interpolation ticket is already pending commit");
        }
        Preparation preparation = Objects.requireNonNull(
                this.bridge.prepare(input), "bridge prepare result"
        );
        if (preparation.status().prepared()) {
            this.pendingTicket = preparation.ticket();
        }
        return preparation.status();
    }

    /**
     * Commits the renderer work first, then publishes its prepared ticket.
     * Publish failures are intentionally returned to the caller: the old
     * real-frame present has already been encoded and remains the fail-open
     * path for this stage.
     */
    public Status commit(final CommitOperation operation) {
        Objects.requireNonNull(operation, "operation");
        long ticket = this.pendingTicket;
        try {
            operation.commit();
        } catch (RuntimeException | Error failure) {
            cancel(ticket);
            throw failure;
        }
        if (ticket == 0L) {
            return Status.BYPASS_DISABLED;
        }
        this.pendingTicket = 0L;
        return this.bridge.publish(ticket);
    }

    public boolean hasPendingTicket() {
        return this.pendingTicket != 0L;
    }

    /** Replaces the native owner only at a generation boundary with no ticket in flight. */
    void replaceBridge(final TicketBridge bridge) {
        if (this.pendingTicket != 0L) {
            throw new IllegalStateException("Cannot replace frame-interpolation bridge with a pending ticket");
        }
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public void close() {
        cancel(this.pendingTicket);
    }

    private void cancel(final long ticket) {
        if (ticket == 0L) {
            return;
        }
        this.pendingTicket = 0L;
        this.bridge.cancel(ticket);
    }
}
