package com.metallum.client.renderer.temporal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Objects;

/**
 * Reusable direct packet storage for native entity-velocity replay.
 *
 * <p>The native replay consumes this packet memory synchronously while it encodes
 * Metal commands, so a slot can be reused after the downcall returns. Three slots
 * still mirror the renderer's in-flight cadence and keep resize/growth isolated
 * from the currently selected submission slot.</p>
 */
public final class EntityVelocityPacketRing implements AutoCloseable {
    private static final int INITIAL_CAPACITY = 16;

    private final Arena[] arenas = new Arena[FrameStatePacketRing.SLOT_COUNT];
    private final MemorySegment[] storage = new MemorySegment[FrameStatePacketRing.SLOT_COUNT];
    private final int[] capacities = new int[FrameStatePacketRing.SLOT_COUNT];

    public EntityVelocityPacketRing() {
        for (int slot = 0; slot < FrameStatePacketRing.SLOT_COUNT; slot++) {
            replaceSlot(slot, INITIAL_CAPACITY);
        }
    }

    /** Encodes the current recorder contents without allocating a frame-local Arena. */
    int encode(final int slot, final List<EntityVelocityPacket> packets) {
        validateSlot(slot);
        Objects.requireNonNull(packets, "packets");
        int packetCount = packets.size();
        ensureCapacity(slot, packetCount);
        MemorySegment buffer = this.storage[slot];
        for (int index = 0; index < packetCount; index++) {
            EntityVelocityAbi.encodeInto(
                    packets.get(index),
                    buffer.asSlice((long) index * EntityVelocityAbi.PACKET_BYTES,
                            EntityVelocityAbi.PACKET_BYTES)
            );
        }
        return packetCount;
    }

    public MemorySegment packetBuffer(final int slot, final int packetCount) {
        validateSlot(slot);
        if (packetCount < 0 || packetCount > this.capacities[slot]) {
            throw new IllegalArgumentException("Entity velocity packet count exceeds its slot capacity");
        }
        return this.storage[slot].asSlice(
                0L,
                (long) packetCount * EntityVelocityAbi.PACKET_BYTES
        );
    }

    int capacity(final int slot) {
        validateSlot(slot);
        return this.capacities[slot];
    }

    private void ensureCapacity(final int slot, final int requestedCapacity) {
        if (requestedCapacity <= this.capacities[slot]) {
            return;
        }
        int nextCapacity = this.capacities[slot];
        while (nextCapacity < requestedCapacity) {
            int doubled = Math.multiplyExact(nextCapacity, 2);
            nextCapacity = Math.max(doubled, requestedCapacity);
        }
        replaceSlot(slot, nextCapacity);
    }

    private void replaceSlot(final int slot, final int capacity) {
        Arena replacementArena = Arena.ofShared();
        MemorySegment replacementStorage;
        try {
            replacementStorage = replacementArena.allocate(
                    Math.multiplyExact((long) capacity, EntityVelocityAbi.PACKET_BYTES),
                    16L
            );
        } catch (RuntimeException exception) {
            replacementArena.close();
            throw exception;
        }
        Arena previousArena = this.arenas[slot];
        this.arenas[slot] = replacementArena;
        this.storage[slot] = replacementStorage;
        this.capacities[slot] = capacity;
        if (previousArena != null) {
            previousArena.close();
        }
    }

    private static void validateSlot(final int slot) {
        if (slot < 0 || slot >= FrameStatePacketRing.SLOT_COUNT) {
            throw new IllegalArgumentException("Entity velocity packet slot is invalid");
        }
    }

    @Override
    public void close() {
        for (int slot = 0; slot < this.arenas.length; slot++) {
            Arena arena = this.arenas[slot];
            this.arenas[slot] = null;
            this.storage[slot] = null;
            this.capacities[slot] = 0;
            if (arena != null) {
                arena.close();
            }
        }
    }
}
