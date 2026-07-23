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

    public interface TicketBridge {
        Preparation prepare(MemorySegment commandBuffer, long rendererGeneration);

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

    private final TicketBridge bridge;
    private long pendingTicket;

    public FrameInterpolationCommitBoundary() {
        this(DISABLED);
    }

    public FrameInterpolationCommitBoundary(final TicketBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    public Status prepare(final MemorySegment commandBuffer, final long rendererGeneration) {
        if (this.pendingTicket != 0L) {
            throw new IllegalStateException("A frame-interpolation ticket is already pending commit");
        }
        Preparation preparation = Objects.requireNonNull(
                this.bridge.prepare(commandBuffer, rendererGeneration), "bridge prepare result"
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
