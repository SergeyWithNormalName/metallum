package com.metallum.client.metal.render;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Reusable fixed-capacity Java-to-Metal resource binding packet. */
final class MetalResourceBindingPacket implements AutoCloseable {
    static final int CURRENT_VERSION = 1;
    static final int HEADER_BYTES = 32;
    static final int RECORD_BYTES = 48;
    static final int MAX_RECORDS = Long.SIZE;
    static final int CAPACITY_BYTES = HEADER_BYTES + MAX_RECORDS * RECORD_BYTES;

    static final long CAPABILITY_UNIFORM_BUFFER = 1L;
    static final long CAPABILITY_TEXTURE_SAMPLER = 1L << 1;
    static final long CAPABILITY_TEXEL_TEXTURE = 1L << 2;
    static final long SUPPORTED_CAPABILITIES = CAPABILITY_UNIFORM_BUFFER
            | CAPABILITY_TEXTURE_SAMPLER
            | CAPABILITY_TEXEL_TEXTURE;

    static final int TYPE_UNIFORM_BUFFER = 1;
    static final int TYPE_TEXTURE_SAMPLER = 2;
    static final int TYPE_TEXEL_TEXTURE = 3;

    static final int STAGE_VERTEX = MetalCompiledRenderPipeline.STAGE_VERTEX;
    static final int STAGE_FRAGMENT = MetalCompiledRenderPipeline.STAGE_FRAGMENT;
    static final int STAGE_ALL = MetalCompiledRenderPipeline.STAGE_ALL;

    static final int MAX_BUFFER_BINDINGS = 31;
    static final int MAX_TEXTURE_BINDINGS = 128;
    static final int MAX_SAMPLER_BINDINGS = 16;

    static final int STATUS_OK = 1;
    static final int ERROR_NULL_ARGUMENT = -1;
    static final int ERROR_PACKET_CAPACITY = -2;
    static final int ERROR_VERSION = -3;
    static final int ERROR_BYTE_SIZE = -4;
    static final int ERROR_CAPABILITIES = -5;
    static final int ERROR_COUNT = -6;
    static final int ERROR_LAYOUT = -7;
    static final int ERROR_TYPE = -8;
    static final int ERROR_STAGE = -9;
    static final int ERROR_INDEX = -10;
    static final int ERROR_HANDLE = -11;
    static final int ERROR_RANGE = -12;
    static final int ERROR_DUPLICATE_INDEX = -13;
    static final int ERROR_OBJECT_TYPE = -14;
    static final int ERROR_NATIVE_BUFFER_RANGE = -15;

    static final long HEADER_VERSION = 0L;
    static final long HEADER_BYTE_SIZE = 4L;
    static final long HEADER_CAPABILITIES = 8L;
    static final long HEADER_COUNT = 16L;
    static final long HEADER_RECORD_BYTES = 20L;
    static final long HEADER_RECORD_CAPACITY = 24L;
    static final long HEADER_RESERVED = 28L;

    static final long RECORD_TYPE = 0L;
    static final long RECORD_STAGE = 4L;
    static final long RECORD_INDEX = 8L;
    static final long RECORD_RESERVED = 12L;
    static final long RECORD_PRIMARY_HANDLE = 16L;
    static final long RECORD_SECONDARY_HANDLE = 24L;
    static final long RECORD_OFFSET = 32L;
    static final long RECORD_LENGTH = 40L;

    private final Arena arena = Arena.ofShared();
    private final MemorySegment storage = this.arena.allocate(CAPACITY_BYTES, Long.BYTES);
    private int count;
    private long requiredCapabilities;
    private long occupiedBindingMask;
    private boolean closed;

    MetalResourceBindingPacket() {
        putInt(HEADER_VERSION, CURRENT_VERSION);
        putInt(HEADER_RECORD_BYTES, RECORD_BYTES);
        putInt(HEADER_RECORD_CAPACITY, MAX_RECORDS);
        putInt(HEADER_RESERVED, 0);
        reset();
    }

    void reset() {
        requireOpen();
        this.count = 0;
        this.requiredCapabilities = 0L;
        this.occupiedBindingMask = 0L;
    }

    void addUniformBuffer(
            final MemorySegment buffer,
            final long offset,
            final long length,
            final long bufferLength,
            final int bindingIndex,
            final int stageMask
    ) {
        validateCommon(buffer, bindingIndex, stageMask, MAX_BUFFER_BINDINGS);
        if (offset < 0L || length <= 0L || bufferLength <= 0L) {
            throw new IllegalArgumentException("Uniform binding range must be positive");
        }
        final long end;
        try {
            end = Math.addExact(offset, length);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Uniform binding range overflow", exception);
        }
        if (end > bufferLength) {
            throw new IllegalArgumentException("Uniform binding range exceeds its Metal buffer");
        }
        append(
                TYPE_UNIFORM_BUFFER,
                stageMask,
                bindingIndex,
                buffer,
                MemorySegment.NULL,
                offset,
                length,
                CAPABILITY_UNIFORM_BUFFER
        );
    }

    void addTextureSampler(
            final MemorySegment texture,
            final MemorySegment sampler,
            final int bindingIndex,
            final int stageMask
    ) {
        validateCommon(texture, bindingIndex, stageMask, MAX_SAMPLER_BINDINGS);
        if (isNullHandle(sampler)) {
            throw new IllegalArgumentException("Sampled texture binding requires a sampler");
        }
        append(
                TYPE_TEXTURE_SAMPLER,
                stageMask,
                bindingIndex,
                texture,
                sampler,
                0L,
                0L,
                CAPABILITY_TEXTURE_SAMPLER
        );
    }

    void addTexelTexture(
            final MemorySegment texture,
            final int bindingIndex,
            final int stageMask
    ) {
        validateCommon(texture, bindingIndex, stageMask, MAX_TEXTURE_BINDINGS);
        append(
                TYPE_TEXEL_TEXTURE,
                stageMask,
                bindingIndex,
                texture,
                MemorySegment.NULL,
                0L,
                0L,
                CAPABILITY_TEXEL_TEXTURE
        );
    }

    MemorySegment finish() {
        requireOpen();
        int byteSize = Math.addExact(HEADER_BYTES, Math.multiplyExact(this.count, RECORD_BYTES));
        putInt(HEADER_BYTE_SIZE, byteSize);
        putLong(HEADER_CAPABILITIES, this.requiredCapabilities);
        putInt(HEADER_COUNT, this.count);
        return this.storage;
    }

    MemorySegment storage() {
        requireOpen();
        return this.storage;
    }

    long capacityBytes() {
        return CAPACITY_BYTES;
    }

    int count() {
        return this.count;
    }

    long requiredCapabilities() {
        return this.requiredCapabilities;
    }

    private void append(
            final int type,
            final int stageMask,
            final int bindingIndex,
            final MemorySegment primaryHandle,
            final MemorySegment secondaryHandle,
            final long offset,
            final long length,
            final long capability
    ) {
        if (this.count >= MAX_RECORDS) {
            throw new IllegalStateException("Metal binding packet exceeds " + MAX_RECORDS + " records");
        }
        long bindingBit = 1L << bindingIndex;
        if ((this.occupiedBindingMask & bindingBit) != 0L) {
            throw new IllegalArgumentException("Metal binding packet repeats binding index " + bindingIndex);
        }

        long record = HEADER_BYTES + (long) this.count * RECORD_BYTES;
        putInt(record + RECORD_TYPE, type);
        putInt(record + RECORD_STAGE, stageMask);
        putInt(record + RECORD_INDEX, bindingIndex);
        putInt(record + RECORD_RESERVED, 0);
        putLong(record + RECORD_PRIMARY_HANDLE, primaryHandle.address());
        putLong(record + RECORD_SECONDARY_HANDLE, isNullHandle(secondaryHandle) ? 0L : secondaryHandle.address());
        putLong(record + RECORD_OFFSET, offset);
        putLong(record + RECORD_LENGTH, length);
        this.count++;
        this.requiredCapabilities |= capability;
        this.occupiedBindingMask |= bindingBit;
    }

    private void validateCommon(
            final MemorySegment primaryHandle,
            final int bindingIndex,
            final int stageMask,
            final int bindingLimit
    ) {
        requireOpen();
        if (isNullHandle(primaryHandle)) {
            throw new IllegalArgumentException("Metal binding requires a non-null native handle");
        }
        if (stageMask <= 0 || (stageMask & ~STAGE_ALL) != 0) {
            throw new IllegalArgumentException("Invalid Metal shader stage mask " + stageMask);
        }
        if (bindingIndex < 0 || bindingIndex >= bindingLimit || bindingIndex >= MAX_RECORDS) {
            throw new IllegalArgumentException("Metal binding index " + bindingIndex + " is outside its bind space");
        }
    }

    private void putInt(final long offset, final int value) {
        this.storage.set(ValueLayout.JAVA_INT, offset, value);
    }

    private void putLong(final long offset, final long value) {
        this.storage.set(ValueLayout.JAVA_LONG, offset, value);
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Metal resource binding packet is closed");
        }
    }

    private static boolean isNullHandle(final MemorySegment handle) {
        return handle == null || handle.address() == 0L;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.arena.close();
        }
    }

    static String statusName(final int status) {
        return switch (status) {
            case STATUS_OK -> "ok";
            case ERROR_NULL_ARGUMENT -> "null argument";
            case ERROR_PACKET_CAPACITY -> "packet capacity";
            case ERROR_VERSION -> "version";
            case ERROR_BYTE_SIZE -> "byte size";
            case ERROR_CAPABILITIES -> "capabilities";
            case ERROR_COUNT -> "record count";
            case ERROR_LAYOUT -> "layout";
            case ERROR_TYPE -> "record type";
            case ERROR_STAGE -> "stage mask";
            case ERROR_INDEX -> "binding index";
            case ERROR_HANDLE -> "native handle";
            case ERROR_RANGE -> "encoded range";
            case ERROR_DUPLICATE_INDEX -> "duplicate binding index";
            case ERROR_OBJECT_TYPE -> "native object type";
            case ERROR_NATIVE_BUFFER_RANGE -> "native buffer range";
            default -> "unknown status " + status;
        };
    }
}
