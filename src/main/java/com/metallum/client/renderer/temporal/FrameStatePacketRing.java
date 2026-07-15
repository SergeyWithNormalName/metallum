package com.metallum.client.renderer.temporal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** Three reusable native packets, one for every command-buffer in-flight slot. */
public final class FrameStatePacketRing implements AutoCloseable {
    public static final int SLOT_COUNT = 3;

    private final Arena arena = Arena.ofShared();
    private final MemorySegment[] packets = new MemorySegment[SLOT_COUNT];

    public FrameStatePacketRing() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            this.packets[slot] = this.arena.allocate(FrameStateAbi.PACKET_BYTES, 16L);
        }
    }

    public MemorySegment encode(final FrameState state) {
        if (state.inFlightSlot() != state.submitIndex() % SLOT_COUNT) {
            throw new IllegalArgumentException("FrameState in-flight slot does not match submit index");
        }
        MemorySegment packet = this.packets[state.inFlightSlot()];
        FrameStateAbi.encodeInto(state, packet);
        return packet;
    }

    public MemorySegment packet(final int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("FrameState packet slot is invalid");
        }
        return this.packets[slot];
    }

    @Override
    public void close() {
        this.arena.close();
    }
}
