package com.metallum.client.metal.render.framegraph;

/** Header checks shared by future versioned frame/resource packets. */
public final class FrameGraphAbi {
    public static final int CURRENT_VERSION = 1;
    public static final int HEADER_BYTES = 16;

    private FrameGraphAbi() {
    }

    public record Header(int version, int byteSize, long requiredCapabilities) {
    }

    public static void validate(
            final Header header,
            final int actualByteSize,
            final long supportedCapabilities
    ) {
        if (header.version() != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported frame graph ABI version " + header.version());
        }
        if (actualByteSize < HEADER_BYTES || header.byteSize() != actualByteSize) {
            throw new IllegalArgumentException("Frame graph ABI byte size mismatch");
        }
        long unsupported = header.requiredCapabilities() & ~supportedCapabilities;
        if (unsupported != 0L) {
            throw new IllegalArgumentException("Frame graph ABI requires unsupported capabilities");
        }
    }

    public static int checkedPacketBytes(final int recordCount, final int recordStride) {
        if (recordCount < 0 || recordStride <= 0) {
            throw new IllegalArgumentException("Frame graph ABI count/stride is invalid");
        }
        try {
            return Math.addExact(HEADER_BYTES, Math.multiplyExact(recordCount, recordStride));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Frame graph ABI packet size overflow", exception);
        }
    }
}
