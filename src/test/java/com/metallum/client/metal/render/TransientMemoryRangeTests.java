package com.metallum.client.metal.render;

/** Fail-closed range checks shared with the renderer architecture verification target. */
public final class TransientMemoryRangeTests {
    private TransientMemoryRangeTests() {
    }

    public static void run() {
        require(MetalTransientMemory.checkedUploadCopyLength(64L, 16L, 48L) == 48L,
                "exact transient upload range was rejected");
        require(MetalTransientMemory.checkedUploadCopyLength(64L, 64L, 0L) == 0L,
                "empty transient upload at the slice end was rejected");
        expectIllegalState(() -> MetalTransientMemory.checkedUploadCopyLength(64L, 17L, 48L));
        expectIllegalState(() -> MetalTransientMemory.checkedUploadCopyLength(64L, 65L, 0L));
        expectIllegalState(() -> MetalTransientMemory.checkedUploadCopyLength(-1L, 0L, 0L));
        expectIllegalState(() -> MetalTransientMemory.checkedUploadCopyLength(64L, -1L, 1L));
        expectIllegalState(() -> MetalTransientMemory.checkedUploadCopyLength(64L, 0L, -1L));
    }

    private static void expectIllegalState(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
